#ifndef FILE_H
#define FILE_H

#define MAX_FILENAME 256
#define PORT_ADDR_SIZE 256
#define MD5_HASH_SIZE 256

struct file_t {
  char filename[MAX_FILENAME];
  char port[PORT_ADDR_SIZE];
  char hash[MD5_HASH_SIZE];
  int chunk_size;
};

#endif // FILE_H
