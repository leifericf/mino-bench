/*
 * min_embed_floor.c -- the smallest plausible mino embedder under the
 * Floor tier (mino_install_minimal, no core.clj eval).
 *
 * The Floor tier registers the C-level prim surface (reader, evaluator,
 * GC, persistent collections, numeric ops, basic seq, foundational
 * macros) but does NOT evaluate core.clj. defn / defmacro from
 * user code work; capability-gated names like re-find / slurp raise
 * the MNS002 capability-disabled diagnostic if referenced.
 *
 * Built with -ffunction-sections / -fdata-sections and linked with
 * --gc-sections so the linker drops unreachable subsystems and we
 * get an honest Floor-tier footprint number.
 */

#define _POSIX_C_SOURCE 200809L

#include "mino.h"

#include <stdio.h>

int main(int argc, char **argv)
{
    if (argc < 2) {
        fprintf(stderr, "usage: %s EXPR\n", argv[0]);
        return 2;
    }
    mino_state_t *S = mino_state_new();
    mino_env_t   *env = mino_env_new(S);
    mino_install_minimal(S, env);
    mino_val_t   *r = mino_eval_string(S, argv[1], env);
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
