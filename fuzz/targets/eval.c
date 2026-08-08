/*
 * fuzz/targets/eval.c -- sandboxed evaluator target.
 *
 * Runs arbitrary bytes through mino_eval_string under a step limit, a
 * heap limit, and a capability sandbox with no IO / FS / PROC / HOST
 * / ASYNC / AGENT bits -- fuzz inputs must not touch the filesystem
 * or spawn anything. Eval errors and limit hits are correct handling;
 * only crashes count.
 */

#include "fuzz_target.h"
#include "mino.h"

#include <stdlib.h>
#include <string.h>

static mino_state *S;
static mino_env   *env;
static unsigned long n_inputs;

#define RECYCLE_EVERY 512
#define STEP_LIMIT    200000
#define HEAP_LIMIT    (64u * 1024u * 1024u)

#define FUZZ_CAPS                                                      \
    (MINO_CAP_FLOOR | MINO_CAP_REGEX | MINO_CAP_BIGNUM                 \
     | MINO_CAP_MULTIMETHODS | MINO_CAP_PROTOCOLS                      \
     | MINO_CAP_TRANSDUCERS | MINO_CAP_STM | MINO_CAP_STRING_LIB       \
     | MINO_CAP_SET_LIB | MINO_CAP_WALK | MINO_CAP_EDN)

static void fresh_state(void)
{
    S = mino_state_new();
    if (S == NULL) abort();
    env = mino_env_new(S);
    if (env == NULL) abort();
    mino_install(S, env, FUZZ_CAPS);
    mino_set_option(S, MINO_OPT_LIMIT_STEPS, STEP_LIMIT);
    mino_set_option(S, MINO_OPT_LIMIT_HEAP, HEAP_LIMIT);
}

void mino_fuzz_init(void)
{
    fresh_state();
}

int mino_fuzz_one(const uint8_t *data, size_t len)
{
    char *buf = (char *)malloc(len + 1);
    if (buf == NULL) return 0;
    memcpy(buf, data, len);
    buf[len] = '\0';

    if (++n_inputs % RECYCLE_EVERY == 0) {
        mino_env_free(S, env);
        mino_state_free(S);
        fresh_state();
    }

    (void)mino_eval_string(S, buf, env);

    free(buf);
    return 0;
}
