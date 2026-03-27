#ifndef TRACKER_STATE_H
#define TRACKER_STATE_H

#include <arpa/inet.h>
#include <time.h>

#define MAX_FILENAME 256
#define MAX_KEY 64
#define MAX_LINE 4096
#define MAX_PEERS_PER_FILE 100

typedef struct {
    char ip[INET_ADDRSTRLEN];
    int listen_port;
    int connected;
    char seeded_keys[100][MAX_KEY];
    int seed_count;
    char leeched_keys[100][MAX_KEY];
    int leech_count;
    time_t last_seen;
} Peer;

typedef struct {
    char filename[MAX_FILENAME];
    long length;
    int piece_size;
    char key[MAX_KEY];
    char owners[MAX_PEERS_PER_FILE][64];
    int owner_count;
} FileEntry;

extern Peer *peers;
extern int peer_count;
extern int peers_capacity;

extern FileEntry *files;
extern int file_count;
extern int files_capacity;

#endif