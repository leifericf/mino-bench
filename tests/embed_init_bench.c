/*
 * embed_init_bench.c -- measures the in-process cost of
 * mino_state_new + (install_core | install_all) + state_free, plus
 * the wall time to spawn this binary and exit immediately.
 *
 * Two roles:
 *
 *   ./embed_init_bench               # spawn-and-exit; the surrounding
 *                                    # shell loop measures cold start for
 *                                    # a minimal embed binary.
 *
 *   ./embed_init_bench iters N       # run state_new + install_core +
 *                                    # state_free N times, print mean/median.
 *
 *   ./embed_init_bench iters-all N   # run state_new + install_all +
 *                                    # state_free N times, print mean/median.
 *
 * Source path (link against the same object files that build mino itself):
 *   cc -std=c99 -O2 -Imino/src -Imino/src/public -Imino/src/runtime \
 *       -Imino/src/gc -Imino/src/eval -Imino/src/collections \
 *       -Imino/src/prim -Imino/src/async -Imino/src/interop \
 *       -Imino/src/diag -Imino/src/vendor/imath \
 *       -o tests/embed_init_bench tests/embed_init_bench.c \
 *       mino/src/<everything>.o -lm -lpthread
 */

#define _POSIX_C_SOURCE 200809L

#include "mino.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

static double now_ns(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec * 1e9 + (double)ts.tv_nsec;
}

static int cmp_double(const void *a, const void *b)
{
    double da = *(const double *)a, db = *(const double *)b;
    return (da < db) ? -1 : (da > db) ? 1 : 0;
}

static void run_iters(int n, int with_all)
{
    double *samples = (double *)calloc((size_t)n, sizeof(double));
    if (samples == NULL) { fprintf(stderr, "oom\n"); exit(1); }
    int i;
    double total = 0.0;
    for (i = 0; i < n; i++) {
        double t0 = now_ns();
        mino_state_t *S = mino_state_new();
        mino_env_t   *env = mino_env_new(S);
        if (with_all) {
            mino_install_all(S, env);
        } else {
            mino_install_core(S, env);
        }
        mino_env_free(S, env);
        mino_state_free(S);
        double dt = now_ns() - t0;
        samples[i] = dt;
        total += dt;
    }
    qsort(samples, (size_t)n, sizeof(double), cmp_double);
    double median = samples[n / 2];
    double mean   = total / (double)n;
    double minv   = samples[0];
    double maxv   = samples[n - 1];
    double p90    = samples[(int)(0.9 * n) - 1];
    printf("iters=%d kind=%s\n", n, with_all ? "install_all" : "install_core");
    printf("  min    = %7.3f ms\n", minv / 1e6);
    printf("  median = %7.3f ms\n", median / 1e6);
    printf("  mean   = %7.3f ms\n", mean / 1e6);
    printf("  p90    = %7.3f ms\n", p90 / 1e6);
    printf("  max    = %7.3f ms\n", maxv / 1e6);
    free(samples);
}

/* Eval-once mode: same shape as `mino -e EXPR` but with the minimal
 * surface (state + install_core + eval + free). Used by the shell-side
 * cold-start harness to compare full-standalone cold start against an
 * absolute-minimum embed cold start. */
static int eval_once(const char *expr)
{
    mino_state_t *S = mino_state_new();
    mino_env_t   *env = mino_env_new(S);
    mino_install_core(S, env);
    mino_val_t *r = mino_eval_string(S, expr, env);
    int rc = (r == NULL) ? 1 : 0;
    if (r != NULL) {
        mino_println(S, r);
    } else {
        fprintf(stderr, "eval failed: %s\n", mino_last_error(S));
    }
    mino_env_free(S, env);
    mino_state_free(S);
    return rc;
}

int main(int argc, char **argv)
{
    if (argc >= 3 && strcmp(argv[1], "iters") == 0) {
        run_iters(atoi(argv[2]), 0);
        return 0;
    }
    if (argc >= 3 && strcmp(argv[1], "iters-all") == 0) {
        run_iters(atoi(argv[2]), 1);
        return 0;
    }
    if (argc >= 3 && strcmp(argv[1], "-e") == 0) {
        return eval_once(argv[2]);
    }
    /* Default: bare spawn-and-exit so the shell can `time` cold start
     * of a "do-nothing minimal embed". No mino_state_new -- we want to
     * isolate process-spawn cost from runtime init cost. */
    (void)argc;
    (void)argv;
    return 0;
}
