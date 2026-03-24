#ifndef TRACKER_H
#define TRACKER_H

#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>

#define MAX_FILENAME 256
#define MAX_KEY 64
#define MAX_LINE 4096
#define MAX_PEERS_PER_FILE 100
#define DEFAULT_MAX_PEERS 100
#define DEFAULT_MAX_FILES 500

typedef struct {
    char ip[INET_ADDRSTRLEN];
    int listen_port;
    int connected;
    char seeded_keys[100][MAX_KEY];
    int seed_count;
    char leeched_keys[100][MAX_KEY];
    int leech_count;
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

int starts_with(const char *s, const char *prefix);
void send_response(int client_fd, const char *msg);
int read_line(int fd, char *buffer, size_t maxlen);

Peer *find_or_create_peer(const char *ip, int listen_port);
int file_has_owner(FileEntry *f, const char *owner);
FileEntry *find_file_by_key(const char *key);
FileEntry *find_or_create_file(const char *filename, long length, int piece_size, const char *key);
void add_owner_to_file(FileEntry *f, const char *owner);
void clear_peer_keys(Peer *p);
void add_seed_key(Peer *p, const char *key);
void add_leech_key(Peer *p, const char *key);

void parse_seed_list_and_register(Peer *peer, const char *ip, const char *seed_content);
void parse_key_list(Peer *peer, const char *list_content, int is_seed);

void handle_announce(int client_fd, const char *line, const char *client_ip);
void handle_look(int client_fd, const char *line);
void handle_getfile(int client_fd, const char *line);
void handle_update(int client_fd, const char *line, const char *client_ip);
void handle_client(int client_fd, struct sockaddr_in *client_addr);

#endif