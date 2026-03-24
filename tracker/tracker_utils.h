#ifndef TRACKER_UTILS_H
#define TRACKER_UTILS_H

#include <stddef.h>

int starts_with(const char *s, const char *prefix);
void send_response(int client_fd, const char *msg);
int read_line(int fd, char *buffer, size_t maxlen);

#endif