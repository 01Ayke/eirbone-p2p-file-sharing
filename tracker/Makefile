CC = gcc
CFLAGS = -Wall -Wextra -std=c11 -g
BUILD  = build
TARGET = tracker

SRCS = main.c config.c tracker_utils.c tracker_storage.c tracker_protocol.c
OBJS = $(BUILD)/$(SRCS:.c=.o)

all: $(TARGET)

$(TARGET): $(OBJS)
	$(CC) $(CFLAGS) -o $(TARGET) $(OBJS)

$(BUILD)/main.o: main.c config.h tracker_state.h tracker_protocol.h
	$(CC) $(CFLAGS) -c main.c

$(BUILD)/config.o: config.c config.h
	$(CC) $(CFLAGS) -c config.c

$(BUILD)/tracker_utils.o: tracker_utils.c tracker_utils.h
	$(CC) $(CFLAGS) -c tracker_utils.c

$(BUILD)/tracker_storage.o: tracker_storage.c tracker_storage.h tracker_state.h
	$(CC) $(CFLAGS) -c tracker_storage.c

$(BUILD)/tracker_protocol.o: tracker_protocol.c tracker_protocol.h tracker_utils.h tracker_storage.h tracker_state.h config.h
	$(CC) $(CFLAGS) -c tracker_protocol.c

clean:
	rm -f $(OBJS) $(TARGET)

re: clean all

run: $(TARGET)
	./$(TARGET)

.PHONY: all clean re run
