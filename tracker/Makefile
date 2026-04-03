CC = gcc
CFLAGS = -Wall -Wextra -std=c11 -g
BUILD   = build
INSTALL = install
TARGET  = $(INSTALL)/tracker

SRCS = main.c config.c tracker_utils.c tracker_storage.c tracker_protocol.c
OBJS = $(patsubst %.c,$(BUILD)/%.o,$(SRCS))

all: $(BUILD) $(INSTALL) $(TARGET)

$(BUILD):
	mkdir -p $(BUILD)

$(INSTALL):
	mkdir -p $(INSTALL)

$(TARGET): $(OBJS)
	$(CC) $(CFLAGS) -o $(TARGET) $(OBJS)

$(BUILD)/main.o: main.c config.h tracker_state.h tracker_protocol.h
	$(CC) $(CFLAGS) -c main.c -o $(BUILD)/main.o

$(BUILD)/config.o: config.c config.h
	$(CC) $(CFLAGS) -c config.c -o $(BUILD)/config.o

$(BUILD)/tracker_utils.o: tracker_utils.c tracker_utils.h
	$(CC) $(CFLAGS) -c tracker_utils.c -o $(BUILD)/tracker_utils.o

$(BUILD)/tracker_storage.o: tracker_storage.c tracker_storage.h tracker_state.h
	$(CC) $(CFLAGS) -c tracker_storage.c -o $(BUILD)/tracker_storage.o

$(BUILD)/tracker_protocol.o: tracker_protocol.c tracker_protocol.h tracker_utils.h tracker_storage.h tracker_state.h config.h
	$(CC) $(CFLAGS) -c tracker_protocol.c -o $(BUILD)/tracker_protocol.o

clean:
	rm -rf $(BUILD) $(INSTALL)

re: clean all

run: $(TARGET)
	./$(TARGET)

.PHONY: all clean re run
