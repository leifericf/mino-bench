# mino-bench

Benchmarks, stress tests, and fuzz testing for [mino](https://github.com/leifericf/mino).

## Bootstrap

```
git submodule update --init
cd mino && cc -std=c99 -O2 -Isrc -o mino src/*.c main.c -lm && cd ..
```

## Tasks

```
./mino/mino task              # list all tasks
./mino/mino task build        # build mino binary and C benchmarks
./mino/mino task clean        # remove build artifacts

./mino/mino task bench        # mino-level benchmarks (end-to-end)
./mino/mino task bench-c      # C-level benchmarks (raw data structures)
./mino/mino task bench-c-vec  # C benchmark: vectors only
./mino/mino task bench-c-map  # C benchmark: maps only
./mino/mino task bench-c-seq  # C benchmark: sequences only
./mino/mino task bench-c-perf # C benchmark: core profiling

./mino/mino task stress          # GC stress test
./mino/mino task stress-sharded  # all GC stress shards
./mino/mino task fuzz-build      # build fuzz reader
```

## License

ISC
