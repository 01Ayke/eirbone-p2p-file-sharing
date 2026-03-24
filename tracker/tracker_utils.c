#include <string.h>
#include <sys/socket.h>
#include "tracker_utils.h"

int starts_with(const char *s, const char *prefix) {
    return strncmp(s, prefix, strlen(prefix)) == 0;
}

void send_response(int client_fd, const char *msg) {
    send(client_fd, msg, strlen(msg), 0);
}

int read_line(int fd, char *buffer, size_t maxlen) {
    size_t i = 0;
    char c;

    while (i < maxlen - 1) {
        ssize_t r = recv(fd, &c, 1, 0);
        if (r <= 0) {
            return -1;
        }

        buffer[i++] = c;
        if (c == '\n') {
            break;
        }
    }

    buffer[i] = '\0';
    return (int)i;
}