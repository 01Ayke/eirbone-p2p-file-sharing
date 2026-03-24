#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "config.h"

void trim_newline(char *s) {
    size_t n = strlen(s);
    while (n > 0 && (s[n - 1] == '\n' || s[n - 1] == '\r')) {
        s[n - 1] = '\0';
        n--;
    }
}

void trim_spaces(char *s) {
    char *start = s;
    while (*start == ' ' || *start == '\t') {
        start++;
    }

    if (start != s) {
        memmove(s, start, strlen(start) + 1);
    }

    if (strlen(s) == 0) {
        return;
    }

    char *end = s + strlen(s) - 1;
    while (end >= s && (*end == ' ' || *end == '\t')) {
        *end = '\0';
        end--;
    }
}

void set_default_config(Config *cfg) {
    cfg->tracker_port = 9000;
    cfg->max_peers = 100;
    cfg->max_files = 500;
    cfg->timeout = 30;
    cfg->backlog = 10;
    strcpy(cfg->log_level, "info");
    strcpy(cfg->log_file, "tracker.log");
}

int load_config(const char *filename, Config *cfg) {
    FILE *f = fopen(filename, "r");
    if (!f) {
        return -1;
    }

    char line[512];
    char section[64] = "";

    while (fgets(line, sizeof(line), f)) {
        trim_newline(line);
        trim_spaces(line);

        if (line[0] == '\0' || line[0] == '#' || line[0] == ';') {
            continue;
        }

        if (line[0] == '[') {
            char *end = strchr(line, ']');
            if (end) {
                *end = '\0';
                strncpy(section, line + 1, sizeof(section) - 1);
                section[sizeof(section) - 1] = '\0';
            }
            continue;
        }

        char *eq = strchr(line, '=');
        if (!eq) {
            continue;
        }

        *eq = '\0';

        char key[128];
        char value[256];

        strncpy(key, line, sizeof(key) - 1);
        key[sizeof(key) - 1] = '\0';

        strncpy(value, eq + 1, sizeof(value) - 1);
        value[sizeof(value) - 1] = '\0';

        trim_spaces(key);
        trim_spaces(value);

        if (strcmp(section, "tracker") == 0) {
            if (strcmp(key, "port") == 0) {
                cfg->tracker_port = atoi(value);
            } else if (strcmp(key, "max_peers") == 0) {
                cfg->max_peers = atoi(value);
            } else if (strcmp(key, "max_files") == 0) {
                cfg->max_files = atoi(value);
            }
        } else if (strcmp(section, "network") == 0) {
            if (strcmp(key, "timeout") == 0) {
                cfg->timeout = atoi(value);
            } else if (strcmp(key, "backlog") == 0) {
                cfg->backlog = atoi(value);
            }
        } else if (strcmp(section, "log") == 0) {
            if (strcmp(key, "level") == 0) {
                strncpy(cfg->log_level, value, sizeof(cfg->log_level) - 1);
                cfg->log_level[sizeof(cfg->log_level) - 1] = '\0';
            } else if (strcmp(key, "file") == 0) {
                strncpy(cfg->log_file, value, sizeof(cfg->log_file) - 1);
                cfg->log_file[sizeof(cfg->log_file) - 1] = '\0';
            }
        }
    }

    fclose(f);
    return 0;
}