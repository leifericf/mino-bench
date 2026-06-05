# Fuzzing mino

Two fuzzing lanes live here, complementary by design.

## 1. Multi-target persistent-loop fuzzer (zig-built, portable)

`fuzz/targets/<name>.c` each implement two functions against `mino.h`
only (`fuzz/targets/fuzz_target.h`): `mino_fuzz_init` and
`mino_fuzz_one`. `fuzz/rt/loop.c` is the shared runtime, built with
the pinned `zig cc` under UBSan so the lane reproduces across
machines.

Targets:

| target | exercises |
|---|---|
| `reader` | `mino_read` over arbitrary bytes — must never crash |
| `print_roundtrip` | `read` → `print` → `read` → `print` stability and readability |
| `eval` | `mino_eval_string` in a step/heap/capability sandbox |
| `regex` | `re-find` with a fuzzed pattern and subject |

Tasks (run from the mino-bench root):

```
./mino/mino task fuzz-build-targets    # build all four (zig cc, UBSan)
./mino/mino task fuzz-smoke-targets    # replay every corpus seed through each
./mino/mino task fuzz-run              # time-boxed mutation fuzz; FUZZ_SECS / FUZZ_SEED
```

`fuzz-run` is deterministic (`FUZZ_SEED`, default 0) and writes the
in-flight input to `fuzz/artifacts/<target>/.current` before each call,
so a crash — signal, UBSan trap, or the per-input watchdog firing on a
hang — leaves the exact reproducer on disk. Replay it with:

```
./fuzz/bin/mino_fuzz_<target> replay fuzz/artifacts/<target>/.current
```

This is a **mutation fuzzer over a seeded corpus**, not coverage-guided:
zig 0.16 ships no libFuzzer runtime and its native `std.testing.fuzz`
does not yet build. It is the reproducible, toolchain-portable lane.
Findings triage into `mino/.local/BUGS.md`; a minimized reproducer
that should never regress goes into `fuzz/corpus/`.

## 2. libFuzzer reader (host clang, coverage-guided)

`fuzz/fuzz_reader.c` + `./mino/mino task fuzz-build-libfuzzer` build a
coverage-guided libFuzzer binary where a host clang with
`-fsanitize=fuzzer` is available. This is the deeper reader-only lane;
the multi-target lane above covers the eval / print / regex surface
libFuzzer does not.

## CI

`.github/workflows/fuzz.yml`: every push/PR runs both corpus smokes
(reader stdin + all four zig targets). The scheduled nightly run adds
the 24h libFuzzer reader job and the time-boxed `fuzz-targets` sweep.
