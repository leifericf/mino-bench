# mino-bench -- benchmarks, stress tests, and fuzz testing for mino.

CC      ?= cc
CFLAGS  ?= -std=c99 -Wall -Wpedantic -Wextra -O2 -Imino/src
LDFLAGS ?=
LIBS    ?= -lm

MINO_SRCS := mino/src/mino.c mino/src/eval_special.c \
             mino/src/eval_special_defs.c mino/src/eval_special_bindings.c \
             mino/src/eval_special_control.c mino/src/eval_special_fn.c \
             mino/src/runtime_state.c mino/src/runtime_var.c \
             mino/src/runtime_error.c mino/src/runtime_env.c mino/src/runtime_gc.c \
             mino/src/val.c mino/src/vec.c mino/src/map.c mino/src/rbtree.c \
             mino/src/read.c mino/src/print.c \
             mino/src/prim.c mino/src/prim_numeric.c mino/src/prim_collections.c \
             mino/src/prim_sequences.c mino/src/prim_string.c mino/src/prim_io.c \
             mino/src/prim_reflection.c mino/src/prim_meta.c mino/src/prim_regex.c \
             mino/src/prim_stateful.c mino/src/prim_module.c \
             mino/src/prim_host.c mino/src/host_interop.c \
             mino/src/clone.c mino/src/re.c
MINO_OBJS := $(MINO_SRCS:.c=.o)

# Build the mino binary for stress tests.
MINO_BIN_SRCS := $(MINO_SRCS) mino/main.c
MINO_BIN_OBJS := $(MINO_BIN_SRCS:.c=.o)
MINO_BIN      := mino-bin

.PHONY: all clean bench bench-map bench-seq bench-perf \
        fuzz-stdin stress stress-sharded

all: bench

# --- Benchmarks ---

bench: src/vector_bench
	./src/vector_bench

bench-map: src/map_bench
	./src/map_bench

bench-seq: src/seq_bench
	./src/seq_bench

bench-perf: src/perf_profile
	./src/perf_profile

src/vector_bench: src/vector_bench.c $(MINO_OBJS) mino/src/mino.h
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ src/vector_bench.c $(MINO_OBJS) $(LIBS)

src/map_bench: src/map_bench.c $(MINO_OBJS) mino/src/mino.h
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ src/map_bench.c $(MINO_OBJS) $(LIBS)

src/seq_bench: src/seq_bench.c $(MINO_OBJS) mino/src/mino.h
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ src/seq_bench.c $(MINO_OBJS) $(LIBS)

src/perf_profile: src/perf_profile.c $(MINO_OBJS) mino/src/mino.h
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ src/perf_profile.c $(MINO_OBJS) $(LIBS)

# --- Fuzz testing ---

fuzz-stdin: fuzz/fuzz_reader
	@echo "fuzz_reader built (stdin mode). Pipe input to: ./fuzz/fuzz_reader"

fuzz/fuzz_reader: fuzz/fuzz_reader.c $(MINO_SRCS) mino/src/mino.h
	$(CC) $(CFLAGS) $(LDFLAGS) -DFUZZ_STDIN -o $@ fuzz/fuzz_reader.c $(MINO_SRCS) $(LIBS)

# --- Stress tests ---

$(MINO_BIN): $(MINO_BIN_OBJS)
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ $(MINO_BIN_OBJS) $(LIBS)

stress: $(MINO_BIN)
	MINO_GC_STRESS=1 ./$(MINO_BIN) stress/stress_test.mino

stress-sharded: $(MINO_BIN)
	@for i in 1 2 3 4 5 6 7 8 9 10 11; do \
	  printf "shard %s/11... " "$$i"; \
	  MINO_GC_STRESS=1 ./$(MINO_BIN) stress/run_gc_shard$$i.mino || exit 1; \
	done
	@echo "all shards passed"

# --- Generated header ---

mino/src/core_mino.h: mino/src/core.mino
	@printf 'static const char *core_mino_src =\n' > $@
	@sed 's/\\/\\\\/g; s/"/\\"/g; s/^/    "/; s/$$/\\n"/' $< >> $@
	@printf '    ;\n' >> $@

mino/src/prim.o: mino/src/prim.c mino/src/core_mino.h

# --- Object files ---

%.o: %.c
	$(CC) $(CFLAGS) -c -o $@ $<

clean:
	rm -f $(MINO_OBJS) $(MINO_BIN_OBJS) $(MINO_BIN) \
	      src/vector_bench src/map_bench src/seq_bench src/perf_profile \
	      fuzz/fuzz_reader mino/src/core_mino.h
