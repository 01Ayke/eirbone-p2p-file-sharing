#include "../src/config.h"
#include <assert.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

char *red = "\033[31m";
char *green = "\033[32m";
char *reset = "\033[0m";
char *purple = "\033[35m";

void test_trim_newline() {
    
    char s1[] = "hello\n";
    trim_newline(s1);
    assert(strcmp(s1, "hello") == 0);
    
    char s2[] = "world\r\n";
    trim_newline(s2);
    assert(strcmp(s2, "world") == 0);
    
    char s3[] = "test";
    trim_newline(s3);
    assert(strcmp(s3, "test") == 0);
    
    char s4[] = "\n\n";
    trim_newline(s4);
    assert(strcmp(s4, "") == 0);
    
    printf("trim_newline() %spassed%s\n", green, reset);
}

void test_trim_spaces() {
    
    char s1[] = "  hello  ";
    trim_spaces(s1);
    assert(strcmp(s1, "hello") == 0);
    
    char s2[] = "\t\tworld\t";
    trim_spaces(s2);
    assert(strcmp(s2, "world") == 0);
    
    char s3[] = "no spaces";
    trim_spaces(s3);
    assert(strcmp(s3, "no spaces") == 0);
    
    char s4[] = "   ";
    trim_spaces(s4);
    assert(strcmp(s4, "") == 0);
    
    char s5[] = " a b c ";
    trim_spaces(s5);
    assert(strcmp(s5, "a b c") == 0);
    
    printf("trim_spaces() %spassed%s\n", green, reset);
}

void test_set_default_config() {
    
    Config cfg;
    set_default_config(&cfg);
    
    assert(cfg.tracker_port == 9000);
    assert(cfg.max_peers == 100);
    assert(cfg.max_files == 500);
    assert(cfg.timeout == 30);
    assert(cfg.backlog == 10);
    assert(cfg.thread_pool_size == 4);
    assert(cfg.thread_pool_queue == 50);
    assert(strcmp(cfg.log_level, "info") == 0);
    assert(strcmp(cfg.log_file, "tracker.log") == 0);
    
    printf("set_default_config() %spassed%s\n", green, reset);
}

void test_load_config() {
    
    const char *test_file = "test_config.ini";
    FILE *f = fopen(test_file, "w");
    assert(f != NULL);
    
    fprintf(f, "[tracker]\n");
    fprintf(f, "port = 8080\n");
    fprintf(f, "max_peers = 200\n");
    fprintf(f, "max_files = 1000\n");
    fprintf(f, "\n");
    fprintf(f, "[network]\n");
    fprintf(f, "timeout = 60\n");
    fprintf(f, "backlog = 20\n");
    fprintf(f, "thread_pool_size = 8\n");
    fprintf(f, "thread_pool_queue = 100\n");
    fprintf(f, "\n");
    fprintf(f, "[log]\n");
    fprintf(f, "level = debug\n");
    fprintf(f, "file = debug.log\n");
    
    fclose(f);
    
    Config cfg;
    set_default_config(&cfg);
    int ret = load_config(test_file, &cfg);
    
    assert(ret == 0);
    assert(cfg.tracker_port == 8080);
    assert(cfg.max_peers == 200);
    assert(cfg.max_files == 1000);
    assert(cfg.timeout == 60);
    assert(cfg.backlog == 20);
    assert(cfg.thread_pool_size == 8);
    assert(cfg.thread_pool_queue == 100);
    assert(strcmp(cfg.log_level, "debug") == 0);
    assert(strcmp(cfg.log_file, "debug.log") == 0);
    
    unlink(test_file);
    
    printf("load_config() %spassed%s\n", green, reset);
}

void test_load_config_nonexistent() {
    
    Config cfg;
    int ret = load_config("nonexistent_file_xyz.ini", &cfg);
    assert(ret == -1);
    
    printf("load_config() nonexistent file test %spassed%s\n", green, reset);
}

int main() {
    printf("\n%s-> tracker_config Tests%s\n", purple, reset);
    
    test_trim_newline();
    test_trim_spaces();
    test_set_default_config();
    test_load_config();
    test_load_config_nonexistent();
    
    printf("%sAll tracker_config tests passed!%s\n\n", green, reset);
    return 0;
}
