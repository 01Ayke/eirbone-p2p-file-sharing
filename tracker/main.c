#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <netinet/in.h>

#include "config.h"
#include "tracker_state.h"
#include "tracker_protocol.h"

int main(void) {
    Config cfg;
    set_default_config(&cfg);

    if (load_config("config.ini", &cfg) != 0) {
        printf("config.ini non trouvé, valeurs par défaut utilisées\n");
    }

    int port = cfg.tracker_port;
    peers_capacity = cfg.max_peers;
    files_capacity = cfg.max_files;

    peers = malloc(sizeof(Peer) * peers_capacity);
    files = malloc(sizeof(FileEntry) * files_capacity);

    if (!peers || !files) {
        perror("malloc");
        free(peers);
        free(files);
        return 1;
    }

    memset(peers, 0, sizeof(Peer) * peers_capacity);
    memset(files, 0, sizeof(FileEntry) * files_capacity);

    int server_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (server_fd < 0) {
        perror("socket");
        free(peers);
        free(files);
        return 1;
    }

    int opt = 1;
    if (setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt)) < 0) {
        perror("setsockopt");
        close(server_fd);
        free(peers);
        free(files);
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
        free(peers);
        free(files);
        return 1;
    }

    if (listen(server_fd, cfg.backlog) < 0) {
        perror("listen");
        close(server_fd);
        free(peers);
        free(files);
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
    free(peers);
    free(files);
    return 0;
}