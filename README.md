# mino-bench

Benchmarks, stress tests, and fuzz testing for [mino](https://github.com/leifericf/mino).

## Build

```
git submodule update --init
make
```

## Benchmarks

```
make bench          # vector operations
make bench-map      # map operations
make bench-seq      # sequence operations
make bench-perf     # core operation profiling
```

## Stress tests

```
make stress             # single stress test with GC stress enabled
make stress-sharded     # all 11 GC stress shards
```

## Fuzz testing

```
make fuzz-stdin
echo '(+ 1 2)' | ./fuzz/fuzz_reader
```
