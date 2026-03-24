#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <arpa/inet.h>

#include "tracker_protocol.h"
#include "tracker_utils.h"
#include "tracker_storage.h"
#include "config.h"

void parse_seed_list_and_register(Peer *peer, const char *ip, const char *seed_content) {
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
        filename[sizeof(filename) - 1] = '\0';

        tok = strtok(NULL, " ");
        if (!tok) break;
        length = atol(tok);

        tok = strtok(NULL, " ");
        if (!tok) break;
        piece_size = atoi(tok);

        tok = strtok(NULL, " ");
        if (!tok) break;
        strncpy(key, tok, sizeof(key) - 1);
        key[sizeof(key) - 1] = '\0';

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

void handle_announce(int client_fd, const char *line, const char *client_ip) {
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
        if (leech_ptr) seed_end = leech_ptr;
        else seed_end = strchr(seed_start, ']');
    }

    if (seed_start && seed_end && seed_end > seed_start) {
        size_t len = (size_t)(seed_end - seed_start);
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
            size_t len = (size_t)(leech_end - leech_start);
            if (len >= sizeof(leech_block)) len = sizeof(leech_block) - 1;
            strncpy(leech_block, leech_start, len);
            leech_block[len] = '\0';
            parse_key_list(peer, leech_block, 0);
        }
    }

    send_response(client_fd, "ok\n");
}

void handle_look(int client_fd, const char *line) {
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
            size_t len = (size_t)(end - start);
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
            size_t len = (size_t)(end - start);
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