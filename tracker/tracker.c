#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <netinet/in.h>

#define MAX_PEERS 100
#define MAX_FILES 500
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
} Peer;

typedef struct {
    char filename[MAX_FILENAME];
    long length;
    int piece_size;
    char key[MAX_KEY];
    char owners[MAX_PEERS_PER_FILE][64]; // "ip:port"
    int owner_count;
} FileEntry;

static Peer peers[MAX_PEERS];
static int peer_count = 0;

static FileEntry files[MAX_FILES];
static int file_count = 0;

/* ===========================
   Utilitaires
   =========================== */

void trim_newline(char *s) {
    size_t n = strlen(s);
    while (n > 0 && (s[n - 1] == '\n' || s[n - 1] == '\r')) {
        s[n - 1] = '\0';
        n--;
    }
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

Peer *find_or_create_peer(const char *ip, int listen_port) {
    for (int i = 0; i < peer_count; i++) {
        if (strcmp(peers[i].ip, ip) == 0 && peers[i].listen_port == listen_port) {
            peers[i].connected = 1;
            return &peers[i];
        }
    }

    if (peer_count >= MAX_PEERS) return NULL;

    Peer *p = &peers[peer_count++];
    memset(p, 0, sizeof(Peer));
    strncpy(p->ip, ip, sizeof(p->ip) - 1);
    p->listen_port = listen_port;
    p->connected = 1;
    return p;
}

int file_has_owner(FileEntry *f, const char *owner) {
    for (int i = 0; i < f->owner_count; i++) {
        if (strcmp(f->owners[i], owner) == 0) return 1;
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
    if (f) return f;

    if (file_count >= MAX_FILES) return NULL;

    f = &files[file_count++];
    memset(f, 0, sizeof(FileEntry));
    strncpy(f->filename, filename, MAX_FILENAME - 1);
    f->length = length;
    f->piece_size = piece_size;
    strncpy(f->key, key, MAX_KEY - 1);
    return f;
}

void add_owner_to_file(FileEntry *f, const char *owner) {
    if (!f) return;
    if (f->owner_count >= MAX_PEERS_PER_FILE) return;
    if (file_has_owner(f, owner)) return;

    strncpy(f->owners[f->owner_count++], owner, 63);
}

void clear_peer_keys(Peer *p) {
    p->seed_count = 0;
    p->leech_count = 0;
}

void add_seed_key(Peer *p, const char *key) {
    if (p->seed_count < 100) {
        strncpy(p->seeded_keys[p->seed_count++], key, MAX_KEY - 1);
    }
}

void add_leech_key(Peer *p, const char *key) {
    if (p->leech_count < 100) {
        strncpy(p->leeched_keys[p->leech_count++], key, MAX_KEY - 1);
    }
}

/* ===========================
   Parsing simple
   =========================== */

void parse_seed_list_and_register(Peer *peer, const char *ip, const char *seed_content) {
    // seed_content contient : "fileA 1000 1024 keyA fileB 2000 1024 keyB"
    char copy[MAX_LINE];
    strncpy(copy, seed_content, sizeof(copy) - 1);
    copy[sizeof(copy) - 1] = '\0';

    char *tok = strtok(copy, " ");
    while (tok) {
        char filename[MAX_FILENAME];
        char key[MAX_KEY];
        long length;
        int piece_size;

        strncpy(filename, tok, sizeof(filename) - 1);

        tok = strtok(NULL, " ");
        if (!tok) break;
        length = atol(tok);

        tok = strtok(NULL, " ");
        if (!tok) break;
        piece_size = atoi(tok);

        tok = strtok(NULL, " ");
        if (!tok) break;
        strncpy(key, tok, sizeof(key) - 1);

        FileEntry *f = find_or_create_file(filename, length, piece_size, key);
        char owner[64];
        snprintf(owner, sizeof(owner), "%s:%d", ip, peer->listen_port);
        add_owner_to_file(f, owner);
        add_seed_key(peer, key);

        tok = strtok(NULL, " ");
    }
}

void parse_key_list(Peer *peer, const char *list_content, int is_seed) {
    char copy[MAX_LINE];
    strncpy(copy, list_content, sizeof(copy) - 1);
    copy[sizeof(copy) - 1] = '\0';

    char *tok = strtok(copy, " ");
    while (tok) {
        if (strlen(tok) > 0) {
            if (is_seed) add_seed_key(peer, tok);
            else add_leech_key(peer, tok);
        }
        tok = strtok(NULL, " ");
    }
}

/* ===========================
   Commandes tracker
   =========================== */

void handle_announce(int client_fd, const char *line, const char *client_ip) {
    // Format visé :
    // announce listen 2222 seed [file_a.dat 2097152 1024 hashA ...] leech [key1 key2]
    int listen_port = 0;

    char *listen_ptr = strstr(line, "listen ");
    char *seed_ptr = strstr(line, " seed [");
    char *leech_ptr = strstr(line, "] leech [");

    if (!listen_ptr || !seed_ptr) {
        send_response(client_fd, "error invalid announce\n");
        return;
    }

    listen_ptr += strlen("listen ");
    listen_port = atoi(listen_ptr);

    Peer *peer = find_or_create_peer(client_ip, listen_port);
    if (!peer) {
        send_response(client_fd, "error tracker full\n");
        return;
    }

    clear_peer_keys(peer);

    char seed_block[MAX_LINE] = {0};
    char leech_block[MAX_LINE] = {0};

    char *seed_start = strchr(seed_ptr, '[');
    char *seed_end = NULL;
    if (seed_start) {
        seed_start++;
        if (leech_ptr) {
            seed_end = leech_ptr;
        } else {
            seed_end = strchr(seed_start, ']');
        }
    }

    if (seed_start && seed_end && seed_end > seed_start) {
        size_t len = seed_end - seed_start;
        if (len >= sizeof(seed_block)) len = sizeof(seed_block) - 1;
        strncpy(seed_block, seed_start, len);
        seed_block[len] = '\0';
        parse_seed_list_and_register(peer, client_ip, seed_block);
    }

    if (leech_ptr) {
        char *leech_start = strchr(leech_ptr, '[');
        char *leech_end = strrchr(leech_ptr, ']');
        if (leech_start && leech_end && leech_end > leech_start) {
            leech_start++;
            size_t len = leech_end - leech_start;
            if (len >= sizeof(leech_block)) len = sizeof(leech_block) - 1;
            strncpy(leech_block, leech_start, len);
            leech_block[len] = '\0';
            parse_key_list(peer, leech_block, 0);
        }
    }

    send_response(client_fd, "ok\n");
}

void handle_look(int client_fd, const char *line) {
    // Version simple : supporte seulement [filename=xxx]
    char filename[MAX_FILENAME] = {0};
    char *p = strstr(line, "filename=");
    if (p) {
        p += strlen("filename=");
        int i = 0;
        while (*p && *p != ']' && *p != ' ' && i < MAX_FILENAME - 1) {
            filename[i++] = *p++;
        }
        filename[i] = '\0';
    }

    char response[MAX_LINE];
    strcpy(response, "list [");

    int first = 1;
    for (int i = 0; i < file_count; i++) {
        if (filename[0] == '\0' || strcmp(files[i].filename, filename) == 0) {
            char tmp[512];
            snprintf(tmp, sizeof(tmp), "%s%s %ld %d %s",
                     first ? "" : " ",
                     files[i].filename,
                     files[i].length,
                     files[i].piece_size,
                     files[i].key);
            strncat(response, tmp, sizeof(response) - strlen(response) - 1);
            first = 0;
        }
    }

    strncat(response, "]\n", sizeof(response) - strlen(response) - 1);
    send_response(client_fd, response);
}

void handle_getfile(int client_fd, const char *line) {
    char key[MAX_KEY] = {0};
    if (sscanf(line, "getfile %63s", key) != 1) {
        send_response(client_fd, "error invalid getfile\n");
        return;
    }

    FileEntry *f = find_file_by_key(key);
    if (!f) {
        send_response(client_fd, "peers ");
        send_response(client_fd, key);
        send_response(client_fd, " []\n");
        return;
    }

    char response[MAX_LINE];
    snprintf(response, sizeof(response), "peers %s [", key);

    for (int i = 0; i < f->owner_count; i++) {
        strncat(response, f->owners[i], sizeof(response) - strlen(response) - 1);
        if (i != f->owner_count - 1) {
            strncat(response, " ", sizeof(response) - strlen(response) - 1);
        }
    }

    strncat(response, "]\n", sizeof(response) - strlen(response) - 1);
    send_response(client_fd, response);
}

void handle_update(int client_fd, const char *line, const char *client_ip) {
    // Format simple :
    // update seed [key1 key2] leech [key3 key4]
    // Ici on met juste à jour les listes dans le peer si on retrouve le peer par IP.
    // Dans une vraie version, il faut associer précisément la socket au peer annoncé.
    Peer *peer = NULL;
    for (int i = 0; i < peer_count; i++) {
        if (strcmp(peers[i].ip, client_ip) == 0) {
            peer = &peers[i];
            break;
        }
    }

    if (!peer) {
        send_response(client_fd, "error unknown peer\n");
        return;
    }

    peer->seed_count = 0;
    peer->leech_count = 0;

    char *seed_ptr = strstr(line, "seed [");
    char *leech_ptr = strstr(line, "] leech [");

    if (seed_ptr) {
        char *start = strchr(seed_ptr, '[');
        char *end = NULL;
        char block[MAX_LINE] = {0};

        if (start) {
            start++;
            if (leech_ptr) end = leech_ptr;
            else end = strchr(start, ']');
        }

        if (start && end && end > start) {
            size_t len = end - start;
            if (len >= sizeof(block)) len = sizeof(block) - 1;
            strncpy(block, start, len);
            block[len] = '\0';
            parse_key_list(peer, block, 1);
        }
    }

    if (leech_ptr) {
        char *start = strchr(leech_ptr, '[');
        char *end = strrchr(leech_ptr, ']');
        char block[MAX_LINE] = {0};

        if (start && end && end > start) {
            start++;
            size_t len = end - start;
            if (len >= sizeof(block)) len = sizeof(block) - 1;
            strncpy(block, start, len);
            block[len] = '\0';
            parse_key_list(peer, block, 0);
        }
    }

    send_response(client_fd, "ok\n");
}

void handle_client(int client_fd, struct sockaddr_in *client_addr) {
    char line[MAX_LINE];
    char client_ip[INET_ADDRSTRLEN];
    inet_ntop(AF_INET, &(client_addr->sin_addr), client_ip, sizeof(client_ip));

    printf("Client connecté: %s\n", client_ip);

    while (1) {
        int r = read_line(client_fd, line, sizeof(line));
        if (r <= 0) break;

        trim_newline(line);
        printf("Reçu de %s : %s\n", client_ip, line);

        if (starts_with(line, "announce ")) {
            handle_announce(client_fd, line, client_ip);
        } else if (starts_with(line, "look ")) {
            handle_look(client_fd, line);
        } else if (starts_with(line, "getfile ")) {
            handle_getfile(client_fd, line);
        } else if (starts_with(line, "update ")) {
            handle_update(client_fd, line, client_ip);
        } else {
            send_response(client_fd, "error unknown command\n");
        }
    }

    printf("Client déconnecté: %s\n", client_ip);
    close(client_fd);
}

/* ===========================
   Main
   =========================== */

int main(int argc, char *argv[]) {
    if (argc != 2) {
        fprintf(stderr, "Usage: %s <port>\n", argv[0]);
        return 1;
    }

    int port = atoi(argv[1]);

    int server_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (server_fd < 0) {
        perror("socket");
        return 1;
    }

    int opt = 1;
    if (setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt)) < 0) {
        perror("setsockopt");
        close(server_fd);
        return 1;
    }

    struct sockaddr_in server_addr;
    memset(&server_addr, 0, sizeof(server_addr));
    server_addr.sin_family = AF_INET;
    server_addr.sin_addr.s_addr = INADDR_ANY;
    server_addr.sin_port = htons(port);

    if (bind(server_fd, (struct sockaddr *)&server_addr, sizeof(server_addr)) < 0) {
        perror("bind");
        close(server_fd);
        return 1;
    }

    if (listen(server_fd, 10) < 0) {
        perror("listen");
        close(server_fd);
        return 1;
    }

    printf("Tracker en écoute sur le port %d...\n", port);

    while (1) {
        struct sockaddr_in client_addr;
        socklen_t client_len = sizeof(client_addr);

        int client_fd = accept(server_fd, (struct sockaddr *)&client_addr, &client_len);
        if (client_fd < 0) {
            perror("accept");
            continue;
        }

        handle_client(client_fd, &client_addr);
    }

    close(server_fd);
    return 0;
}