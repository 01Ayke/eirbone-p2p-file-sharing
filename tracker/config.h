#ifndef CONFIG_H
#define CONFIG_H

typedef struct {
    int tracker_port;
    int max_peers;
    int max_files;
    int timeout;
    int backlog;
    char log_level[32];
    char log_file[256];
} Config;

void set_default_config(Config *cfg);
void trim_spaces(char *s);
void trim_newline(char *s);
int load_config(const char *filename, Config *cfg);

#endif