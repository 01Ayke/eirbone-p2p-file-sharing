CC = gcc
CFLAGS = -Wall -Wextra -std=c11 -g
CFLAGS_TEST = -Wall -Wextra -std=c11 -g -lpthread

SRC_DIR = src
TEST_DIR = test
SRC_BUILD = $(SRC_DIR)/build
SRC_INSTALL = $(SRC_DIR)/install
TEST_BUILD = $(TEST_DIR)/build
TEST_BIN = $(TEST_DIR)/bin

SRC_OBJS = $(addprefix $(SRC_BUILD)/,main.o config.o tracker_utils.o tracker_storage.o tracker_protocol.o)
TEST_OBJS = $(addprefix $(TEST_BUILD)/,config.o tracker_utils.o tracker_storage.o tracker_protocol.o)

UNIT_TESTS = test_tracker_utils test_tracker_storage test_tracker_protocol test_tracker_config
INTEGRATION_TESTS = test_integration
TEST_BINS = $(addprefix $(TEST_BIN)/,$(UNIT_TESTS) $(INTEGRATION_TESTS))

.PHONY: all build test clean help re run

help:
	@echo "Tracker P2P - Makefile"
	@echo ""
	@echo "Targets:"
	@echo "  all      - Build tracker and run tests (default)"
	@echo "  build    - Build tracker application"
	@echo "  test     - Run all tests (unit + integration)"
	@echo "  run      - Run tracker application"
	@echo "  clean    - Remove all build artifacts"
	@echo "  re       - Clean and rebuild"
	@echo "  help     - Display this message"

all: build test

build: $(SRC_INSTALL)/tracker

$(SRC_BUILD) $(SRC_INSTALL) $(TEST_BUILD) $(TEST_BIN):
	mkdir -p $@

$(SRC_BUILD)/%.o: $(SRC_DIR)/%.c | $(SRC_BUILD)
	$(CC) $(CFLAGS) -c $< -o $@

$(SRC_INSTALL)/tracker: $(SRC_OBJS) | $(SRC_INSTALL)
	$(CC) $(CFLAGS) -o $@ $(SRC_OBJS)

$(TEST_BUILD)/%.o: $(SRC_DIR)/%.c | $(TEST_BUILD)
	$(CC) $(CFLAGS_TEST) -c $< -o $@

$(TEST_BUILD)/test_%.o: $(TEST_DIR)/test_%.c | $(TEST_BUILD)
	$(CC) $(CFLAGS_TEST) -I$(SRC_DIR) -c $< -o $@

$(TEST_BIN)/test_tracker_utils: $(TEST_BUILD)/test_tracker_utils.o $(TEST_BUILD)/config.o $(TEST_BUILD)/tracker_utils.o $(TEST_BUILD)/tracker_storage.o $(TEST_BUILD)/tracker_protocol.o | $(TEST_BIN)
	$(CC) $(CFLAGS_TEST) -o $@ $^ -lpthread

$(TEST_BIN)/test_tracker_storage: $(TEST_BUILD)/test_tracker_storage.o $(TEST_OBJS) | $(TEST_BIN)
	$(CC) $(CFLAGS_TEST) -o $@ $^ -lpthread

$(TEST_BIN)/test_tracker_protocol: $(TEST_BUILD)/test_tracker_protocol.o $(TEST_OBJS) | $(TEST_BIN)
	$(CC) $(CFLAGS_TEST) -o $@ $^ -lpthread

$(TEST_BIN)/test_tracker_config: $(TEST_BUILD)/test_tracker_config.o $(TEST_BUILD)/config.o | $(TEST_BIN)
	$(CC) $(CFLAGS_TEST) -o $@ $^ -lpthread

$(TEST_BIN)/test_integration: $(TEST_BUILD)/test_integration.o $(TEST_OBJS) | $(TEST_BIN)
	$(CC) $(CFLAGS_TEST) -o $@ $^ -lpthread

test: $(TEST_BINS)
	@echo ""
	@echo "========== Running all tests =========="
	@for test in $(TEST_BINS); do echo "Running $$test..."; $$test || exit 1; done
	@echo "========== All tests passed =========="
	@echo ""

run: $(SRC_INSTALL)/tracker
	./$(SRC_INSTALL)/tracker

clean:
	@echo "Cleaning build artifacts..."
	rm -rf $(SRC_BUILD) $(SRC_INSTALL) $(TEST_BUILD) $(TEST_BIN)

re: clean build