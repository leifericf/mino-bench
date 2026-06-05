/*
 * fuzz/rt/loop.c -- portable persistent-loop fuzz runtime.
 *
 * Drives a fuzz target (fuzz/targets/<name>.c, implementing
 * mino_fuzz_init + mino_fuzz_one from fuzz_target.h) in one of two
 * modes:
 *
 *   replay <file>...   run each named input through the target once.
 *                      Exit non-zero on the first crash. Used by the
 *                      corpus smoke test and to reproduce a finding.
 *
 *   fuzz <corpus-dir>  mutation loop: load the corpus as seeds, run
 *                      mutated inputs until --secs elapses or --runs
 *                      is hit. Each input is written to <artifacts>/
 *                      .current before the call, so a crash (signal,
 *                      sanitizer trap, or watchdog SIGALRM on a hang)
 *                      leaves the exact reproducer on disk for the
 *                      supervisor to promote into the corpus / a bug
 *                      report.
 *
 * This is NOT a coverage-guided engine: zig 0.16 ships no libFuzzer
 * runtime and its native std.testing.fuzz does not yet build. It is a
 * mutation fuzzer over a seeded corpus -- the reproducible, toolchain-
 * portable lane. The host-clang libFuzzer build (fuzz-build-libfuzzer)
 * remains the coverage-guided path where that toolchain is present.
 *
 * Determinism: the PRNG is seeded from --seed (default 0), so a fuzz
 * run replays identically. No wall-clock seeding.
 */

#include "fuzz_target.h"

#include <signal.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#ifndef _WIN32
#include <dirent.h>
#endif

#define MAX_INPUT   (256 * 1024)
#define MAX_SEEDS   65536

static uint64_t rng_state = 0x9e3779b97f4a7c15ULL;

static uint64_t rng_next(void)
{
    /* xorshift64*. */
    uint64_t x = rng_state;
    x ^= x >> 12;
    x ^= x << 25;
    x ^= x >> 27;
    rng_state = x;
    return x * 0x2545f4914f6cdd1dULL;
}

static size_t rng_below(size_t n)
{
    return n == 0 ? 0 : (size_t)(rng_next() % (uint64_t)n);
}

/* ---- input buffers -------------------------------------------------- */

typedef struct {
    uint8_t *data;
    size_t   len;
} input_t;

static input_t seeds[MAX_SEEDS];
static size_t  n_seeds;

static uint8_t scratch[MAX_INPUT];

static char artifact_path[4096];

/* Read an entire file into a freshly malloc'd buffer (truncated to
 * MAX_INPUT). Returns 0 on success. */
static int read_file(const char *path, input_t *out)
{
    FILE *f = fopen(path, "rb");
    if (f == NULL) return -1;
    uint8_t *buf = (uint8_t *)malloc(MAX_INPUT);
    if (buf == NULL) { fclose(f); return -1; }
    size_t n = fread(buf, 1, MAX_INPUT, f);
    fclose(f);
    out->data = buf;
    out->len  = n;
    return 0;
}

/* ---- mutation ------------------------------------------------------- */

/* Build one mutated input into `scratch` from a random seed (or from
 * nothing when the corpus is empty). Returns the mutated length. */
static size_t mutate(void)
{
    size_t len = 0;
    if (n_seeds > 0) {
        input_t *s = &seeds[rng_below(n_seeds)];
        len = s->len < MAX_INPUT ? s->len : MAX_INPUT;
        memcpy(scratch, s->data, len);
    }
    int rounds = 1 + (int)rng_below(8);
    for (int r = 0; r < rounds; r++) {
        switch (rng_below(5)) {
        case 0: /* bit flip */
            if (len > 0) scratch[rng_below(len)] ^=
                (uint8_t)(1u << rng_below(8));
            break;
        case 1: /* random byte set */
            if (len > 0) scratch[rng_below(len)] = (uint8_t)rng_next();
            break;
        case 2: /* insert a byte */
            if (len < MAX_INPUT) {
                size_t at = rng_below(len + 1);
                memmove(scratch + at + 1, scratch + at, len - at);
                scratch[at] = (uint8_t)rng_next();
                len++;
            }
            break;
        case 3: /* delete a byte */
            if (len > 0) {
                size_t at = rng_below(len);
                memmove(scratch + at, scratch + at + 1, len - at - 1);
                len--;
            }
            break;
        case 4: /* splice from another seed */
            if (n_seeds > 0 && len < MAX_INPUT) {
                input_t *o = &seeds[rng_below(n_seeds)];
                size_t take = o->len < (MAX_INPUT - len)
                    ? o->len : (MAX_INPUT - len);
                take = rng_below(take + 1);
                memcpy(scratch + len, o->data, take);
                len += take;
            }
            break;
        default:
            break;
        }
    }
    return len;
}

/* ---- watchdog ------------------------------------------------------- */

#ifndef _WIN32
static volatile sig_atomic_t in_target;

static void on_alarm(int sig)
{
    (void)sig;
    if (in_target) {
        /* A single input took longer than the per-input budget --
         * catastrophic backtracking or an infinite loop. Abort so the
         * supervisor records .current as a hang finding. */
        const char msg[] = "[fuzz] watchdog: input exceeded time budget"
                           " (hang); see artifacts/.current\n";
        (void)write(2, msg, sizeof msg - 1);
        abort();
    }
}
#endif

/* Persist the about-to-run input so a crash leaves a reproducer. */
static void stash_current(const uint8_t *data, size_t len)
{
    if (artifact_path[0] == '\0') return;
    FILE *f = fopen(artifact_path, "wb");
    if (f == NULL) return;
    (void)fwrite(data, 1, len, f);
    fclose(f);
}

/* ---- corpus loading ------------------------------------------------- */

static void load_corpus(const char *dir)
{
#ifndef _WIN32
    DIR *d = opendir(dir);
    if (d == NULL) {
        fprintf(stderr, "[fuzz] cannot open corpus dir: %s\n", dir);
        return;
    }
    struct dirent *e;
    char path[4096];
    while ((e = readdir(d)) != NULL && n_seeds < MAX_SEEDS) {
        if (e->d_name[0] == '.') continue;
        snprintf(path, sizeof path, "%s/%s", dir, e->d_name);
        if (read_file(path, &seeds[n_seeds]) == 0) n_seeds++;
    }
    closedir(d);
#else
    (void)dir;
#endif
}

/* ---- modes ---------------------------------------------------------- */

static int mode_replay(int argc, char **argv)
{
    mino_fuzz_init();
    for (int i = 0; i < argc; i++) {
        input_t in;
        if (read_file(argv[i], &in) != 0) {
            fprintf(stderr, "[fuzz] replay: cannot read %s\n", argv[i]);
            return 1;
        }
        /* A crash here kills the process; the file name is the last
         * line printed, so the failing seed is obvious. */
        fprintf(stderr, "[fuzz] replay %s (%zu bytes)\n", argv[i], in.len);
        (void)mino_fuzz_one(in.data, in.len);
        free(in.data);
    }
    fprintf(stderr, "[fuzz] replay: %d input(s) OK\n", argc);
    return 0;
}

static int mode_fuzz(const char *corpus_dir, long secs, long max_runs,
                     long per_input_secs)
{
    load_corpus(corpus_dir);
    fprintf(stderr, "[fuzz] %zu seed(s) loaded from %s\n",
            n_seeds, corpus_dir);

    mino_fuzz_init();

#ifndef _WIN32
    if (per_input_secs > 0) {
        struct sigaction sa;
        memset(&sa, 0, sizeof sa);
        sa.sa_handler = on_alarm;
        sigaction(SIGALRM, &sa, NULL);
    }
#endif

    time_t start = time(NULL);
    long runs = 0;
    for (;;) {
        if (max_runs > 0 && runs >= max_runs) break;
        if (secs > 0 && (long)(time(NULL) - start) >= secs) break;

        size_t len = mutate();
        stash_current(scratch, len);

#ifndef _WIN32
        if (per_input_secs > 0) {
            in_target = 1;
            alarm((unsigned)per_input_secs);
        }
#endif
        (void)mino_fuzz_one(scratch, len);
#ifndef _WIN32
        if (per_input_secs > 0) {
            alarm(0);
            in_target = 0;
        }
#endif
        runs++;
        if ((runs & 0xFFFF) == 0) {
            fprintf(stderr, "[fuzz] %ld runs, %ld s\n",
                    runs, (long)(time(NULL) - start));
        }
    }
    fprintf(stderr, "[fuzz] done: %ld runs in %ld s, no crash\n",
            runs, (long)(time(NULL) - start));
    return 0;
}

/* ---- argv ----------------------------------------------------------- */

static long arg_long(const char *v, long dflt)
{
    if (v == NULL || v[0] == '\0') return dflt;
    return strtol(v, NULL, 10);
}

int main(int argc, char **argv)
{
    const char *mode = argc > 1 ? argv[1] : "";

    long secs = 0, runs = 0, per_input = 10;
    const char *positional[64];
    int n_pos = 0;
    const char *seed_arg = NULL, *artifacts = NULL;

    for (int i = 2; i < argc; i++) {
        if (strcmp(argv[i], "--secs") == 0 && i + 1 < argc) {
            secs = arg_long(argv[++i], 0);
        } else if (strcmp(argv[i], "--runs") == 0 && i + 1 < argc) {
            runs = arg_long(argv[++i], 0);
        } else if (strcmp(argv[i], "--per-input-secs") == 0 && i + 1 < argc) {
            per_input = arg_long(argv[++i], 10);
        } else if (strcmp(argv[i], "--seed") == 0 && i + 1 < argc) {
            seed_arg = argv[++i];
        } else if (strcmp(argv[i], "--artifacts") == 0 && i + 1 < argc) {
            artifacts = argv[++i];
        } else if (n_pos < 64) {
            positional[n_pos++] = argv[i];
        }
    }

    if (seed_arg != NULL) {
        rng_state = (uint64_t)arg_long(seed_arg, 0) + 0x9e3779b97f4a7c15ULL;
    }
    if (artifacts != NULL) {
        snprintf(artifact_path, sizeof artifact_path,
                 "%s/.current", artifacts);
    }

    if (strcmp(mode, "replay") == 0) {
        return mode_replay(n_pos, (char **)positional);
    }
    if (strcmp(mode, "fuzz") == 0) {
        const char *corpus = n_pos > 0 ? positional[0] : "fuzz/corpus";
        return mode_fuzz(corpus, secs, runs, per_input);
    }

    fprintf(stderr,
            "usage:\n"
            "  %s replay <file>...\n"
            "  %s fuzz <corpus-dir> [--secs N] [--runs N]\n"
            "      [--per-input-secs N] [--seed N] [--artifacts DIR]\n",
            argv[0], argv[0]);
    return 2;
}
