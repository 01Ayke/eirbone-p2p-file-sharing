#include "../src/tracker_utils.h"
#include <assert.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <unistd.h>
#include <sys/socket.h>

char *red = "\033[31m";
char *green = "\033[32m";
char *reset = "\033[0m";
char *purple = "\033[35m";

void test_starts_with() {
    assert(starts_with("announce listen 6000", "announce") == 1);
    assert(starts_with("look file.iso", "announce") == 0);
    assert(starts_with("", "prefix") == 0);
    assert(starts_with("hello", "") == 1);
    assert(starts_with("look", "look") == 1);
    assert(starts_with("hi", "hello") == 0);
    printf("starts_with() %spassed%s\n", green, reset);
}

void test_read_line() {
    
    int sockets[2];
    assert(socketpair(AF_UNIX, SOCK_STREAM, 0, sockets) == 0);
    int write_fd = sockets[1];
    int read_fd = sockets[0];
    const char *line1 = "announce listen 6000\n";
    write(write_fd, line1, strlen(line1));
    char buffer[256];
    int n = read_line(read_fd, buffer, sizeof(buffer));
    assert(n > 0);
    assert(strcmp(buffer, line1) == 0);
    const char *line2 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n";
    write(write_fd, line2, strlen(line2));
    memset(buffer, 0, sizeof(buffer));
    n = read_line(read_fd, buffer, 10);
    assert(n == 9);
    close(read_fd);
    close(write_fd);
    
    printf("read_line() %spassed%s\n", green, reset);
}

int main() {
    printf("\n%s-> tracker_utils Tests%s\n", purple, reset);
    
    test_starts_with();
    test_read_line();
    
    printf("%sAll tracker_utils tests passed!%s\n\n", green, reset);
    return 0;
}