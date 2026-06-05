/*
 * fuzz/targets/print_roundtrip.c -- reader/printer round-trip target.
 *
 * Invariants checked per successfully-read form:
 *
 *   1. Printing never crashes and never overruns the buffer.
 *   2. The printed form of reader-built data is itself readable
 *      (reader output is plain data -- lists, vectors, maps, atoms --
 *      so its readable print form must parse).
 *   3. Printing is stable: print(read(print(v))) == print(v). A
 *      mismatch means the printer and reader disagree about some
 *      value's round-trip and is reported by abort().
 *
 * Two outputs are legitimately not round-trippable and skip checks 2
 * and 3:
 *   - a truncated print (form longer than the buffer);
 *   - a depth-elided print: the printer caps recursion at
 *     MINO_PRINT_DEPTH_MAX (128) and emits the `#<...>` marker as a
 *     stack-overflow guard, while the reader accepts far deeper
 *     nesting. The marker makes the output deliberately lossy. (The
 *     read/print depth asymmetry itself is logged for triage in
 *     mino/.local/BUGS.md -- it is a mino behavior question, not a
 *     harness bug.)
 */

#include "fuzz_target.h"
#include "mino.h"

#include <stdlib.h>
#include <string.h>

static mino_state *S;
static unsigned long n_inputs;

#define RECYCLE_EVERY 2048
#define PRINT_CAP     (64 * 1024)

static char print_buf_a[PRINT_CAP];
static char print_buf_b[PRINT_CAP];

void mino_fuzz_init(void)
{
    S = mino_state_new();
    if (S == NULL) abort();
}

static void roundtrip_one(mino_val *v)
{
    int n_a = mino_print_to_buf(S, v, print_buf_a, sizeof print_buf_a);
    if (n_a < 0) return;                      /* print error: handled */
    if ((size_t)n_a >= sizeof print_buf_a - 1) return; /* truncated */
    /* Depth-elided print: the `#<...>` marker is the printer's
     * recursion-depth safety cutoff and is intentionally unreadable. */
    if (memchr(print_buf_a, '#', (size_t)n_a) != NULL
        && strstr(print_buf_a, "#<...>") != NULL) return;

    {
        const char *end = NULL;
        mino_val *v2 = mino_read(S, print_buf_a, &end);
        if (v2 == NULL) {
            /* Reader-built data printed readably must re-read. */
            abort();
        }
        {
            int n_b = mino_print_to_buf(S, v2, print_buf_b,
                                        sizeof print_buf_b);
            if (n_b < 0) abort();
            if (n_b != n_a || memcmp(print_buf_a, print_buf_b,
                                     (size_t)n_a) != 0) {
                /* Print instability: v and read(print(v)) print
                 * differently. */
                abort();
            }
        }
    }
}

int mino_fuzz_one(const uint8_t *data, size_t len)
{
    char *buf = (char *)malloc(len + 1);
    if (buf == NULL) return 0;
    memcpy(buf, data, len);
    buf[len] = '\0';

    if (++n_inputs % RECYCLE_EVERY == 0) {
        mino_state_free(S);
        S = mino_state_new();
        if (S == NULL) abort();
    }

    {
        const char *pos = buf;
        while (*pos != '\0') {
            const char *end = NULL;
            mino_val *v = mino_read(S, pos, &end);
            if (v == NULL) break;
            if (end == NULL || end <= pos) break;
            roundtrip_one(v);
            pos = end;
        }
    }

    free(buf);
    return 0;
}
