#include <string.h>
#include <stdio.h>
#include <time.h>
#include <pthread.h>
#include "tracker_storage.h"

Peer *peers = NULL;
int peer_count = 0;
int peers_capacity = 0;

FileEntry *files = NULL;
int file_count = 0;
int files_capacity = 0;

static pthread_mutex_t storage_mutex = PTHREAD_MUTEX_INITIALIZER;

void storage_lock(void) {
    pthread_mutex_lock(&storage_mutex);
}

void storage_unlock(void) {
    pthread_mutex_unlock(&storage_mutex);
}

Peer *find_or_create_peer(const char *ip, int listen_port) {
    for (int i = 0; i < peer_count; i++) {
        if (strcmp(peers[i].ip, ip) == 0 && peers[i].listen_port == listen_port) {
            peers[i].connected = 1;
            peers[i].last_seen = time(NULL);
            return &peers[i];
        }
    }

    if (peer_count >= peers_capacity) {
        return NULL;
    }

    Peer *p = &peers[peer_count++];
    memset(p, 0, sizeof(Peer));
    strncpy(p->ip, ip, sizeof(p->ip) - 1);
    p->ip[sizeof(p->ip) - 1] = '\0';
    p->listen_port = listen_port;
    p->connected = 1;
    p->last_seen = time(NULL);
    return p;
}

Peer *find_peer(const char *ip, int listen_port) {
    for (int i = 0; i < peer_count; i++) {
        if (strcmp(peers[i].ip, ip) == 0 && peers[i].listen_port == listen_port) {
            return &peers[i];
        }
    }
    return NULL;
}

int file_has_owner(FileEntry *f, const char *owner) {
    for (int i = 0; i < f->owner_count; i++) {
        if (strcmp(f->owners[i], owner) == 0) {
            return 1;
        }
    }
    return 0;
}

FileEntry *find_file_by_key(const char *key) {
    for (int i = 0; i < file_count; i++) {
        if (strcmp(files[i].key, key) == 0) {
            return &files[i];
        }
    }
    return NULL;
}

FileEntry *find_or_create_file(const char *filename, long length, int piece_size, const char *key) {
    FileEntry *f = find_file_by_key(key);
    if (f) {
        return f;
    }

    if (file_count >= files_capacity) {
        return NULL;
    }

    f = &files[file_count++];
    memset(f, 0, sizeof(FileEntry));
    strncpy(f->filename, filename, MAX_FILENAME - 1);
    f->filename[MAX_FILENAME - 1] = '\0';
    f->length = length;
    f->piece_size = piece_size;
    strncpy(f->key, key, MAX_KEY - 1);
    f->key[MAX_KEY - 1] = '\0';
    return f;
}

void add_owner_to_file(FileEntry *f, const char *owner) {
    if (!f) return;
    if (f->owner_count >= MAX_PEERS_PER_FILE) return;
    if (file_has_owner(f, owner)) return;

    strncpy(f->owners[f->owner_count], owner, sizeof(f->owners[f->owner_count]) - 1);
    f->owners[f->owner_count][sizeof(f->owners[f->owner_count]) - 1] = '\0';
    f->owner_count++;
}

void remove_owner_from_file(FileEntry *f, const char *owner) {
    if (!f) return;

    for (int i = 0; i < f->owner_count; i++) {
        if (strcmp(f->owners[i], owner) == 0) {
            /* Décale les owners restants d'une position vers le haut */
            for (int j = i; j < f->owner_count - 1; j++) {
                strncpy(f->owners[j], f->owners[j + 1], sizeof(f->owners[j]));
                f->owners[j][sizeof(f->owners[j]) - 1] = '\0';
            }
            f->owner_count--;
            return;
        }
    }
}

void clear_peer_keys(Peer *p) {
    p->seed_count = 0;
    p->leech_count = 0;
}

void add_seed_key(Peer *p, const char *key) {
    if (p->seed_count < 100) {
        strncpy(p->seeded_keys[p->seed_count], key, MAX_KEY - 1);
        p->seeded_keys[p->seed_count][MAX_KEY - 1] = '\0';
        p->seed_count++;
    }
}

void add_leech_key(Peer *p, const char *key) {
    if (p->leech_count < 100) {
        strncpy(p->leeched_keys[p->leech_count], key, MAX_KEY - 1);
        p->leeched_keys[p->leech_count][MAX_KEY - 1] = '\0';
        p->leech_count++;
    }
}
