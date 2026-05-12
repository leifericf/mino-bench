/*
 * min_embed.c -- the smallest plausible mino embedder.
 *
 * Calls only mino_state_new + mino_env_new + mino_install_core +
 * mino_eval_string + cleanup. No mino_install_all, no I/O capabilities,
 * no STM, no agents, no batteries. Built with -ffunction-sections /
 * -fdata-sections and linked with --gc-sections so the linker drops
 * unreachable subsystems and we get an honest minimum-footprint number.
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
    mino_install_core(S, env);
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
