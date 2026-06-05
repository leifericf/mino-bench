/*
 * fuzz/targets/reader.c -- fuzz target for the mino reader.
 *
 * The reader must never crash, regardless of input: it either returns
 * a value or returns NULL with the error reported via
 * mino_last_error(). Reads every form in the input until EOF or the
 * first error.
 */

#include "fuzz_target.h"
#include "mino.h"

#include <stdlib.h>
#include <string.h>

/* One state reused across inputs for throughput (state creation is
 * orders of magnitude slower than a read). Recycled periodically so
 * reader-allocated garbage and intern tables cannot grow without
 * bound across a long fuzz run. */
static mino_state *S;
static unsigned long n_inputs;

#define RECYCLE_EVERY 4096

void mino_fuzz_init(void)
{
    S = mino_state_new();
    if (S == NULL) abort();
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
            if (v == NULL) break;                /* EOF or parse error */
            if (end == NULL || end <= pos) break; /* no forward progress */
            pos = end;
        }
    }

    free(buf);
    return 0;
}
