CC = gcc
CFLAGS = -Wall -Wextra -std=c11 -g -lpthread
BUILD = build
BIN = bin

TESTS = test_tracker_utils test_tracker_storage test_tracker_protocol test_tracker_config
TEST_BINS = $(addprefix $(BIN)/,$(TESTS))

SRC_FILES = config.c tracker_utils.c tracker_storage.c tracker_protocol.c
COMMON_OBJS = $(addprefix $(BUILD)/,$(SRC_FILES:.c=.o))

all: $(BUILD) $(BIN) $(TEST_BINS)

$(BUILD) $(BIN):
	mkdir -p $@

$(BUILD)/%.o: ../src/%.c | $(BUILD)
	$(CC) $(CFLAGS) -I../src -c $< -o $@

$(BUILD)/test_%.o: test_%.c | $(BUILD)
	$(CC) $(CFLAGS) -I../src -c $< -o $@

$(BIN)/test_tracker_utils: $(BUILD)/test_tracker_utils.o $(COMMON_OBJS) | $(BIN)
	$(CC) $(CFLAGS) -o $@ $^ -lpthread

$(BIN)/test_tracker_storage: $(BUILD)/test_tracker_storage.o $(COMMON_OBJS) | $(BIN)
	$(CC) $(CFLAGS) -o $@ $^ -lpthread

$(BIN)/test_tracker_protocol: $(BUILD)/test_tracker_protocol.o $(COMMON_OBJS) | $(BIN)
	$(CC) $(CFLAGS) -o $@ $^ -lpthread

$(BIN)/test_tracker_config: $(BUILD)/test_tracker_config.o $(BUILD)/config.o | $(BIN)
	$(CC) $(CFLAGS) -o $@ $^ -lpthread

test: $(TEST_BINS)
	@for test in $(TEST_BINS); do ./$$test || exit 1; done

clean:
	rm -rf $(BUILD) $(BIN)

.PHONY: all clean test
