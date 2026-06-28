/*
 * fuzz_store.c — fuzz target for the mino.store transaction parser.
 *
 * Random bytes are wrapped into a `(mino.store/transact conn <BYTES>)`
 * call so the tx-data parser sees arbitrary EDN-shaped (or unshaped)
 * input. The parser must not crash; it either accepts the input or
 * throws a classified ex-info, both of which are fine.
 *
 * Two modes:
 *   1. libFuzzer:  cc -fsanitize=fuzzer,address -I.. fuzz_store.c ../mino.c
 *   2. stdin:      cc -DFUZZ_STDIN -I.. -o fuzz_store fuzz_store.c ../mino.c
 *                  echo '[:db/add 1 :name "x"]' | ./fuzz_store
 *
 * Each input gets a fresh mino_state + store. State is freed at exit.
 */

#include "mino.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* The transact form is built as:
 *   (require 'mino.store)
 *   (mino.store/transact (def c (mino.store/open)) <INPUT>)
 * Wrapped in (do ...) so a single eval_string call covers both setup
 * and the probe. Quasi-safe: even if <INPUT> is unbalanced, the reader
 * surfaces a clean parse error rather than crashing. */
static int fuzz_one(const char *data, size_t size)
{
    /* NUL-terminate. */
    char *buf = (char *)malloc(size + 1);
    if (buf == NULL) return 0;
    memcpy(buf, data, size);
    buf[size] = '\0';

    /* Reject inputs containing NUL bytes — they would truncate the
     * embedded string early, which doesn't exercise the tx-data parser
     * (the C string layer truncates before Clojure sees the rest). */
    if (memchr(buf, '\0', size) != NULL) { free(buf); return 0; }

    {
        size_t   prefix_len = strlen("(require 'mino.store) (do (def c (mino.store/open)) (mino.store/transact c ");
        size_t   suffix_len = strlen("))");
        size_t   out_len = prefix_len + size + suffix_len + 1;
        char    *form = (char *)malloc(out_len);
        if (form != NULL) {
            memcpy(form, "(require 'mino.store) (do (def c (mino.store/open)) (mino.store/transact c ", prefix_len);
            memcpy(form + prefix_len, buf, size);
            memcpy(form + prefix_len + size, "))", suffix_len);
            form[out_len - 1] = '\0';

            {
                mino_state *S = mino_state_new();
                if (S != NULL) {
                    mino_env *env = mino_env_new(S);
                    if (env != NULL) {
                        mino_install_all(S, env);
                        /* Return value ignored: the parser must not
                         * crash regardless of accept/reject. */
                        (void)mino_eval_string(S, form, env);
                    }
                    mino_state_free(S);
                }
            }
            free(form);
        }
    }

    free(buf);
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
    if (size > 64 * 1024) return 0;
    return fuzz_one((const char *)data, size);
}
#endif
