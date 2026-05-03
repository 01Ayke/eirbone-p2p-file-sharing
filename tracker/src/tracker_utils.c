#include <string.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <stdio.h>
#include <time.h>
#include <stdarg.h>
#include <pthread.h>
#include <stdlib.h>
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

static void *worker_thread(void *arg) {
    ThreadPool *pool = (ThreadPool *)arg;
    while (1) {
        pthread_mutex_lock(&pool->mutex);
        while (pool->count == 0 && !pool->shutdown) {
            pthread_cond_wait(&pool->cond, &pool->mutex);
        }
        if (pool->shutdown) {
            pthread_mutex_unlock(&pool->mutex);
            break;
        }
        int client_fd = pool->client_fds[pool->front];
        struct sockaddr_in client_addr = pool->client_addrs[pool->front];
        pool->front = (pool->front + 1) % pool->queue_size;
        pool->count--;
        pthread_mutex_unlock(&pool->mutex);
        handle_client(client_fd, &client_addr);
    }
    return NULL;
}

void thread_pool_init(ThreadPool *pool, int num_threads, int queue_size) {
    pool->thread_count = num_threads;
    pool->queue_size = queue_size;
    pool->front = pool->rear = pool->count = 0;
    pool->shutdown = 0;
    pool->threads = malloc(num_threads * sizeof(WorkerThread));
    pool->client_fds = malloc(queue_size * sizeof(int));
    pool->client_addrs = malloc(queue_size * sizeof(struct sockaddr_in));
    pthread_mutex_init(&pool->mutex, NULL);
    pthread_cond_init(&pool->cond, NULL);
    for (int i = 0; i < num_threads; i++) {
        pool->threads[i].active = 1;
        pthread_create(&pool->threads[i].thread, NULL, worker_thread, pool);
    }
    log_message("INFO", "Thread pool initialisé: %d threads, queue %d", num_threads, queue_size);
}

void thread_pool_destroy(ThreadPool *pool) {
    pthread_mutex_lock(&pool->mutex);
    pool->shutdown = 1;
    pthread_cond_broadcast(&pool->cond);
    pthread_mutex_unlock(&pool->mutex);
    for (int i = 0; i < pool->thread_count; i++) {
        pthread_join(pool->threads[i].thread, NULL);
    }
    free(pool->threads);
    free(pool->client_fds);
    free(pool->client_addrs);
    pthread_mutex_destroy(&pool->mutex);
    pthread_cond_destroy(&pool->cond);
    log_message("INFO", "Thread pool détruit");
}

int thread_pool_add_task(ThreadPool *pool, int client_fd, struct sockaddr_in *addr) {
    pthread_mutex_lock(&pool->mutex);
    if (pool->count >= pool->queue_size) {
        pthread_mutex_unlock(&pool->mutex);
        return -1;
    }
    pool->client_fds[pool->rear]   = client_fd;
    pool->client_addrs[pool->rear] = *addr;
    pool->rear = (pool->rear + 1) % pool->queue_size;
    pool->count++;
    pthread_cond_signal(&pool->cond);
    pthread_mutex_unlock(&pool->mutex);
    return 0;
}
