#ifndef PEER_H
#define PEER_H
#include "file.h"

#define IP_ADDR_SIZE 256
#define PORT_ADDR_SIZE 256

struct peer_t {
  char ip_addr[IP_ADDR_SIZE];
  char port[PORT_ADDR_SIZE];
  struct file_t *files;
  unsigned int **bitmaps;
};

#endif // PEER_H
