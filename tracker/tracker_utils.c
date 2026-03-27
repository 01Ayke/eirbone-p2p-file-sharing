#include <string.h>
#include <sys/socket.h>
#include <stdio.h>
#include <time.h>
#include <stdarg.h>
#include <pthread.h>
#include "tracker_utils.h"

static FILE *log_fp = NULL;
static pthread_mutex_t log_mutex = PTHREAD_MUTEX_INITIALIZER;

void log_init(const char *filename) {
    if (filename && filename[0]) {
        log_fp = fopen(filename, "a");
    }
}

void log_message(const char *level, const char *fmt, ...) {
    time_t now = time(NULL);
    char timebuf[32];
    struct tm *tm_info = localtime(&now);
    strftime(timebuf, sizeof(timebuf), "%Y-%m-%d %H:%M:%S", tm_info);

    va_list args1, args2;
    va_start(args1, fmt);
    va_copy(args2, args1);

    pthread_mutex_lock(&log_mutex);

    printf("[%s] [%s] ", timebuf, level);
    vprintf(fmt, args1);
    printf("\n");

    if (log_fp) {
        fprintf(log_fp, "[%s] [%s] ", timebuf, level);
        vfprintf(log_fp, fmt, args2);
        fprintf(log_fp, "\n");
        fflush(log_fp);
    }

    pthread_mutex_unlock(&log_mutex);

    va_end(args1);
    va_end(args2);
}

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
