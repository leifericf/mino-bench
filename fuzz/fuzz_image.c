/*
 * fuzz_image.c — fuzz target for the SLAD image loader.
 *
 * The loader parses a host-authored artifact (ADR 12 trust model) but
 * still has to be crash-free on every input: a truncated or corrupted
 * image file must surface as a clean -1 return with a diagnostic, never
 * as a segfault or uncatchable throw out of mino_load_image_into.
 *
 * Two modes:
 *   1. libFuzzer:  cc -fsanitize=fuzzer,address -I.. fuzz_image.c ../mino.c
 *   2. stdin:      cc -DFUZZ_STDIN -I.. -o fuzz_image fuzz_image.c ../mino.c
 *                  cat bad.img | ./fuzz_image
 *
 * Each input is written to a tmp file, installed into a fresh state
 * via mino_load_image_into, and the state is freed. The fuzzer's
 * contract is "no crash, no leak" — return value is ignored.
 */

#include "mino.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static int fuzz_one(const char *data, size_t size)
{
    char path[] = "/tmp/fuzz_image_XXXXXX";
    int fd = mkstemp(path);
    if (fd < 0) return 0;
    if (write(fd, data, size) != (ssize_t)size) {
        close(fd);
        unlink(path);
        return 0;
    }
    close(fd);

    {
        mino_state *S = mino_state_new();
        if (S != NULL) {
            mino_env *env = mino_env_new(S);
            if (env != NULL) {
                /* mino_install_all re-creates the baseline primitive /
                 * namespace layer the loader patches on top of. */
                mino_install_all(S, env);
                /* Return value is intentionally ignored: the loader must
                 * not crash regardless of whether it accepts or rejects
                 * the input. */
                (void)mino_load_image_into(S, path);
            }
            mino_state_free(S);
        }
    }

    unlink(path);
    return 0;
}

#ifdef FUZZ_STDIN
int main(void)
{
    char   buf[1024 * 1024];
    size_t n = fread(buf, 1, sizeof(buf), stdin);
    return fuzz_one(buf, n);
}
#else
int LLVMFuzzerTestOneInput(const unsigned char *data, size_t size)
{
    /* Cap input to avoid slow units. Images larger than 1 MiB don't
     * exercise new code paths, just stretch the patch loop. */
    if (size > 1024 * 1024) return 0;
    return fuzz_one((const char *)data, size);
}
#endif
