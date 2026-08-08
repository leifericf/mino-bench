/*
 * fuzz/targets/regex.c -- regex engine target.
 *
 * The input splits at its first NUL byte into PATTERN and SUBJECT.
 * Both halves are escaped into mino string literals and run through
 * `(re-find (re-pattern "P") "S")` in a capability-sandboxed state.
 * Compile errors on a garbage pattern are correct handling; what this
 * target hunts is crashes inside the regex compiler/matcher and
 * catastrophic-backtracking hangs (the runtime's supervisor records a
 * stalled child as a hang finding).
 *
 * Inputs with bytes below 0x20 (other than tab/newline/CR) are
 * skipped instead of escaped: the goal is exercising the regex
 * engine, not the reader's string-literal error paths, which
 * reader.c already covers.
 */

#include "fuzz_target.h"
#include "mino.h"

#include <stdlib.h>
#include <string.h>

static mino_state *S;
static mino_env   *env;
static unsigned long n_inputs;

#define RECYCLE_EVERY 2048
#define STEP_LIMIT    100000
#define HALF_CAP      2048

static char script[2 * 4 * HALF_CAP + 64];

static void fresh_state(void)
{
    S = mino_state_new();
    if (S == NULL) abort();
    env = mino_env_new(S);
    if (env == NULL) abort();
    mino_install(S, env, MINO_CAP_FLOOR | MINO_CAP_REGEX);
    mino_set_option(S, MINO_OPT_LIMIT_STEPS, STEP_LIMIT);
}

void mino_fuzz_init(void)
{
    fresh_state();
}

/* Append a NUL-terminated literal to `dst`, returning the new
 * cursor. */
static char *append_lit(char *dst, const char *lit)
{
    size_t n = strlen(lit);
    memcpy(dst, lit, n);
    return dst + n;
}

/* Append `len` bytes of `src` to `dst` as the body of a mino string
 * literal: backslash and double-quote escaped, everything else
 * verbatim. Returns the new cursor, or NULL when a byte below 0x20
 * (other than \t \n \r) makes the input unsuitable. `dst` must have
 * room for 2 * len bytes. */
static char *escape_into(char *dst, const uint8_t *src, size_t len)
{
    size_t i;
    for (i = 0; i < len; i++) {
        uint8_t c = src[i];
        if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') return NULL;
        if (c == '\\' || c == '"') *dst++ = '\\';
        *dst++ = (char)c;
    }
    return dst;
}

int mino_fuzz_one(const uint8_t *data, size_t len)
{
    const uint8_t *nul = (const uint8_t *)memchr(data, '\0', len);
    const uint8_t *pat = data;
    size_t pat_len, subj_len;
    const uint8_t *subj;
    char *cur = script;

    if (nul == NULL) {
        pat_len  = len;
        subj     = data;
        subj_len = 0;
    } else {
        pat_len  = (size_t)(nul - data);
        subj     = nul + 1;
        subj_len = len - pat_len - 1;
    }
    if (pat_len > HALF_CAP || subj_len > HALF_CAP) return 0;

    if (++n_inputs % RECYCLE_EVERY == 0) {
        mino_env_free(S, env);
        mino_state_free(S);
        fresh_state();
    }

    cur = append_lit(cur, "(re-find (re-pattern \"");
    cur = escape_into(cur, pat, pat_len);
    if (cur == NULL) return 0;
    cur = append_lit(cur, "\") \"");
    cur = escape_into(cur, subj, subj_len);
    if (cur == NULL) return 0;
    cur = append_lit(cur, "\")");
    *cur = '\0';

    (void)mino_eval_string(S, script, env);
    return 0;
}
