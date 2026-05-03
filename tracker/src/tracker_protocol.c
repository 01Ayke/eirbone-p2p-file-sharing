#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <arpa/inet.h>
#include <time.h>

#include "tracker_protocol.h"
#include "tracker_utils.h"
#include "tracker_storage.h"
#include "config.h"

/* ─────────────────────────────────────────────────────────────
 * Helpers internes
 * ───────────────────────────────────────────────────────────── */

/*
 * Parse le bloc seed d'un announce et enregistre chaque fichier.
 * Format attendu dans seed_content : "nom taille psize hash nom taille psize hash ..."
 */
void parse_seed_list_and_register(Peer *peer, const char *ip, const char *seed_content) {
    char copy[MAX_LINE];
    strncpy(copy, seed_content, sizeof(copy) - 1);
    copy[sizeof(copy) - 1] = '\0';

    char *tok = strtok(copy, " ");
    while (tok) {
        char filename[MAX_FILENAME];
        char key[MAX_KEY];
        long length;
        int  piece_size;

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

/*
 * Parse une liste de clés séparées par des espaces et les
 * ajoute en seed (is_seed=1) ou leech (is_seed=0) au peer.
 */
void parse_key_list(Peer *peer, const char *list_content, int is_seed) {
    char copy[MAX_LINE];
    strncpy(copy, list_content, sizeof(copy) - 1);
    copy[sizeof(copy) - 1] = '\0';

    char *tok = strtok(copy, " ");
    while (tok) {
        if (tok[0] != '\0') {
            if (is_seed) add_seed_key(peer, tok);
            else         add_leech_key(peer, tok);
        }
        tok = strtok(NULL, " ");
    }
}

/*
 * Extrait le contenu d'un bloc délimité par '[' et ']'.
 * Retourne la longueur extraite, 0 si introuvable.
 * Le bloc va de `open_bracket` (inclus, sans le '[') jusqu'à `close_bracket`.
 */
static size_t extract_bracket_block(const char *open_bracket, const char *close_bracket,
                                    char *out, size_t outsize) {
    if (!open_bracket || !close_bracket || close_bracket <= open_bracket) return 0;
    size_t len = (size_t)(close_bracket - open_bracket);
    if (len >= outsize) len = outsize - 1;
    strncpy(out, open_bracket, len);
    out[len] = '\0';
    return len;
}

/* ─────────────────────────────────────────────────────────────
 * Handlers protocole
 * ───────────────────────────────────────────────────────────── */

/*
 * ANNOUNCE : enregistre le peer et ses fichiers.
 * Format : announce listen <port> seed [nom taille psize hash ...] leech [hash ...]
 */
void handle_announce(int client_fd, const char *line, ClientContext *ctx) {
    char *listen_ptr = strstr(line, "listen ");
    char *seed_ptr   = strstr(line, " seed [");
    char *leech_ptr  = strstr(line, "] leech [");

    if (!listen_ptr || !seed_ptr) {
        send_response(client_fd, "error invalid announce\n");
        return;
    }

    int listen_port = atoi(listen_ptr + strlen("listen "));

    /* Extraction du bloc seed */
    char seed_block[MAX_LINE]  = {0};
    char *seed_open  = strchr(seed_ptr, '[');
    char *seed_close = leech_ptr ? leech_ptr : (seed_open ? strchr(seed_open + 1, ']') : NULL);
    if (seed_open) {
        extract_bracket_block(seed_open + 1, seed_close, seed_block, sizeof(seed_block));
    }

    /* Extraction du bloc leech */
    char leech_block[MAX_LINE] = {0};
    if (leech_ptr) {
        char *leech_open  = strchr(leech_ptr, '[');
        char *leech_close = strrchr(leech_ptr, ']');
        if (leech_open && leech_close && leech_close > leech_open) {
            extract_bracket_block(leech_open + 1, leech_close, leech_block, sizeof(leech_block));
        }
    }

    storage_lock();

    Peer *peer = find_or_create_peer(ctx->client_ip, listen_port);
    if (!peer) {
        storage_unlock();
        send_response(client_fd, "error tracker full\n");
        return;
    }

    ctx->listen_port = listen_port;
    ctx->peer        = peer;

    /* Supprime le peer des listes d'owners des fichiers qu'il seedait avant */
    char owner[64];
    snprintf(owner, sizeof(owner), "%s:%d", ctx->client_ip, listen_port);
    for (int i = 0; i < peer->seed_count; i++) {
        FileEntry *f = find_file_by_key(peer->seeded_keys[i]);
        if (f) remove_owner_from_file(f, owner);
    }

    clear_peer_keys(peer);

    if (seed_block[0])  parse_seed_list_and_register(peer, ctx->client_ip, seed_block);
    if (leech_block[0]) parse_key_list(peer, leech_block, 0);

    purge_orphan_files();

    storage_unlock();

    log_message("INFO", "Announce: %s:%d — %d seed(s), %d leech(es)",
                ctx->client_ip, listen_port, peer->seed_count, peer->leech_count);
    send_response(client_fd, "ok\n");
}

/*
 * LOOK : liste les fichiers connus (filtrés par nom optionnel).
 * Format : look [filename=nom]  ou  look []
 */
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

    storage_lock();

    char response[MAX_LINE];
    int pos = snprintf(response, sizeof(response), "list [");
    int first = 1;

    for (int i = 0; i < file_count && pos < (int)sizeof(response) - 16; i++) {
        if (filename[0] == '\0' || strcmp(files[i].filename, filename) == 0) {
            pos += snprintf(response + pos, sizeof(response) - (size_t)pos,
                            "%s%s %ld %d %s",
                            first ? "" : " ",
                            files[i].filename,
                            files[i].length,
                            files[i].piece_size,
                            files[i].key);
            first = 0;
        }
    }

    if (pos < (int)sizeof(response) - 3) {
        pos += snprintf(response + pos, sizeof(response) - (size_t)pos, "]\n");
    }

    storage_unlock();
    send_response(client_fd, response);
}

/*
 * GETFILE : retourne la liste des peers qui ont un fichier donné.
 * Format : getfile <hash>
 * Réponse : peers <hash> [ip:port ip:port ...]
 */
void handle_getfile(int client_fd, const char *line) {
    char key[MAX_KEY] = {0};

    if (sscanf(line, "getfile %63s", key) != 1) {
        send_response(client_fd, "error invalid getfile\n");
        return;
    }

    storage_lock();

    FileEntry *f = find_file_by_key(key);
    char response[MAX_LINE];
    int pos = snprintf(response, sizeof(response), "peers %s [", key);

    if (f) {
        for (int i = 0; i < f->owner_count && pos < (int)sizeof(response) - 10; i++) {
            pos += snprintf(response + pos, sizeof(response) - (size_t)pos,
                            "%s%s",
                            i > 0 ? " " : "",
                            f->owners[i]);
        }
    }

    if (pos < (int)sizeof(response) - 3) {
        snprintf(response + pos, sizeof(response) - (size_t)pos, "]\n");
    }

    storage_unlock();
    send_response(client_fd, response);
}

/*
 * UPDATE : le peer met à jour ses listes seed/leech.
 * Format : update seed [hash1 hash2] leech [hash3]
 * Corrige les listes d'owners dans les FileEntry en conséquence.
 */
void handle_update(int client_fd, const char *line, ClientContext *ctx) {
    if (!ctx->peer) {
        send_response(client_fd, "error not announced\n");
        return;
    }

    Peer *peer = ctx->peer;
    char owner[64];
    snprintf(owner, sizeof(owner), "%s:%d", ctx->client_ip, ctx->listen_port);

    /* Extraction des blocs seed/leech depuis la ligne */
    char seed_block[MAX_LINE]  = {0};
    char leech_block[MAX_LINE] = {0};

    char *seed_ptr  = strstr(line, "seed [");
    char *leech_ptr = strstr(line, "] leech [");

    if (seed_ptr) {
        char *start = strchr(seed_ptr, '[');
        char *end   = leech_ptr ? leech_ptr : (start ? strchr(start + 1, ']') : NULL);
        if (start && end && end > start) {
            extract_bracket_block(start + 1, end, seed_block, sizeof(seed_block));
        }
    }

    if (leech_ptr) {
        char *start = strchr(leech_ptr, '[');
        char *end   = strrchr(leech_ptr, ']');
        if (start && end && end > start) {
            extract_bracket_block(start + 1, end, leech_block, sizeof(leech_block));
        }
    }

    storage_lock();

    /* 1. Retire le peer des owners de tous les fichiers qu'il seedait */
    for (int i = 0; i < peer->seed_count; i++) {
        FileEntry *f = find_file_by_key(peer->seeded_keys[i]);
        if (f) remove_owner_from_file(f, owner);
    }

    /* 2. Réinitialise les listes du peer */
    peer->seed_count  = 0;
    peer->leech_count = 0;
    peer->last_seen   = time(NULL);

    /* 3. Applique les nouvelles listes */
    if (seed_block[0])  parse_key_list(peer, seed_block, 1);
    if (leech_block[0]) parse_key_list(peer, leech_block, 0);

    /* 4. Ré-ajoute le peer comme owner pour les fichiers seedés */
    for (int i = 0; i < peer->seed_count; i++) {
        FileEntry *f = find_file_by_key(peer->seeded_keys[i]);
        if (f) add_owner_to_file(f, owner);
    }

    purge_orphan_files();

    storage_unlock();

    log_message("INFO", "Update: %s — %d seed(s), %d leech(es)",
                owner, peer->seed_count, peer->leech_count);
    send_response(client_fd, "ok\n");
}

/*
 * BYE : déconnexion propre du peer.
 * Retire le peer de toutes les listes d'owners et le marque déconnecté.
 */
void handle_bye(int client_fd, ClientContext *ctx) {
    if (ctx->peer) {
        char owner[64];
        snprintf(owner, sizeof(owner), "%s:%d", ctx->client_ip, ctx->listen_port);

        storage_lock();
        for (int i = 0; i < ctx->peer->seed_count; i++) {
            FileEntry *f = find_file_by_key(ctx->peer->seeded_keys[i]);
            if (f) remove_owner_from_file(f, owner);
        }
        ctx->peer->connected  = 0;
        ctx->peer->seed_count = 0;
        ctx->peer->leech_count = 0;
        purge_orphan_files();
        storage_unlock();

        log_message("INFO", "Bye: %s", owner);
    }
    send_response(client_fd, "bye\n");
}

/*
 * Boucle principale de traitement d'un client.
 * Appelée dans un thread dédié par main().
 */
void handle_client(int client_fd, struct sockaddr_in *client_addr) {
    char line[MAX_LINE];
    ClientContext ctx;

    inet_ntop(AF_INET, &(client_addr->sin_addr), ctx.client_ip, sizeof(ctx.client_ip));
    ctx.listen_port = 0;
    ctx.peer        = NULL;

    log_message("INFO", "Connexion: %s", ctx.client_ip);

    while (1) {
        int r = read_line(client_fd, line, sizeof(line));
        if (r <= 0) break;

        trim_newline(line);
        if (line[0] == '\0') continue;

        log_message("DEBUG", "%s >> %s", ctx.client_ip, line);

        if      (starts_with(line, "announce ")) handle_announce(client_fd, line, &ctx);
        else if (starts_with(line, "look") && (line[4] == '\0' || line[4] == ' ' || line[4] == '\n')) handle_look(client_fd, line);
        else if (starts_with(line, "getfile "))  handle_getfile(client_fd, line);
        else if (starts_with(line, "update "))   handle_update(client_fd, line, &ctx);
        else if (starts_with(line, "bye") ||
                 starts_with(line, "BYE"))       { handle_bye(client_fd, &ctx); break; }
        else send_response(client_fd, "error unknown command\n");
    }

    /* Déconnexion sans bye : nettoyage de l'état du peer */
    if (ctx.peer && ctx.peer->connected) {
        char owner[64];
        snprintf(owner, sizeof(owner), "%s:%d", ctx.client_ip, ctx.listen_port);

        storage_lock();
        for (int i = 0; i < ctx.peer->seed_count; i++) {
            FileEntry *f = find_file_by_key(ctx.peer->seeded_keys[i]);
            if (f) remove_owner_from_file(f, owner);
        }
        ctx.peer->connected   = 0;
        ctx.peer->seed_count  = 0;
        ctx.peer->leech_count = 0;
        purge_orphan_files();
        storage_unlock();

        log_message("INFO", "Déconnexion inattendue: %s", owner);
    }

    log_message("INFO", "Fin connexion: %s", ctx.client_ip);
    close(client_fd);
}
