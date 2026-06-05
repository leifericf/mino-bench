/*
 * fuzz/targets/fuzz_target.h -- the contract between the zig fuzz
 * runtime (fuzz/rt/main.zig) and each C fuzz target.
 *
 * A target is one .c file implementing both functions below against
 * mino's public API (mino.h only -- no internal headers, so the same
 * target compiles against any mino revision the submodule points at).
 *
 * Crash discipline: mino_fuzz_one signals a finding by crashing --
 * abort() for a violated target-level invariant, or any sanitizer
 * trap / segfault out of mino itself. Returning normally means the
 * input was handled correctly (parse errors, eval errors, and limit
 * hits are all correct handling). The runtime's supervisor records
 * the crashing input and restarts the loop.
 */

#ifndef MINO_FUZZ_TARGET_H
#define MINO_FUZZ_TARGET_H

#include <stddef.h>
#include <stdint.h>

/* Called once in each fuzz child before the loop starts. Set up
 * long-lived state here (mino_state, env, limits). */
void mino_fuzz_init(void);

/* Process one input. Return 0; signal findings by crashing. */
int mino_fuzz_one(const uint8_t *data, size_t len);

#endif /* MINO_FUZZ_TARGET_H */
