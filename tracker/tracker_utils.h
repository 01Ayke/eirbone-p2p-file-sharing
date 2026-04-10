#ifndef TRACKER_UTILS_H
#define TRACKER_UTILS_H

#include <stddef.h>
#include <pthread.h>

typedef struct {
    pthread_t thread;
    int active;
} WorkerThread;

typedef struct {
    WorkerThread *threads;
    int thread_count;
    int queue_size;
    int front, rear;
    int count;
    int *client_fds;
    struct sockaddr_in *client_addrs;
    pthread_mutex_t mutex;
    pthread_cond_t cond;
    int shutdown;
} ThreadPool;

void thread_pool_init(ThreadPool *pool, int num_threads, int queue_size);
void thread_pool_destroy(ThreadPool *pool);
int thread_pool_add_task(ThreadPool *pool, int client_fd, struct sockaddr_in *addr);
int starts_with(const char *s, const char *prefix);
void send_response(int client_fd, const char *msg);
int read_line(int fd, char *buffer, size_t maxlen);

/* Logging : appeler log_init() au démarrage, puis log_message() partout */
void log_init(const char *filename);
void log_message(const char *level, const char *fmt, ...);

#endif
