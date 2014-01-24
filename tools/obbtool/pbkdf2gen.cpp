/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <openssl/evp.h>

#include <sys/types.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

/**
 * Simple program to generate a key based on PBKDF2 with preset inputs.
 *
 * Will print out the salt and key in hex.
 */

#define SALT_LEN 8
#define ROUNDS 1024
#define KEY_BITS 128

int main(int argc, char* argv[])
{
    if (argc != 2) {
        fprintf(stderr, "Usage: %s <password>\n", argv[0]);
        exit(1);
    }

    int fd = open("/dev/urandom", O_RDONLY);
    if (fd < 0) {
        fprintf(stderr, "Could not open /dev/urandom: %s\n", strerror(errno));
        close(fd);
        exit(1);
    }

    unsigned char salt[SALT_LEN];

    if (read(fd, &salt, SALT_LEN) != SALT_LEN) {
        fprintf(stderr, "Could not read salt from /dev/urandom: %s\n", strerror(errno));
        close(fd);
        exit(1);
    }
    close(fd);

    unsigned char rawKey[KEY_BITS];

    if (PKCS5_PBKDF2_HMAC_SHA1(argv[1], strlen(argv[1]), salt, SALT_LEN,
            ROUNDS, KEY_BITS, rawKey) != 1) {
        fprintf(stderr, "Could not generate PBKDF2 output: %s\n", strerror(errno));
        exit(1);
    }

    printf("salt=");
    for (int i = 0; i < SALT_LEN; i++) {
        printf("%02x", salt[i]);
    }
    printf("\n");

    printf("key=");
    for (int i = 0; i < (KEY_BITS / 8); i++) {
        printf("%02x", rawKey[i]);
    }
    printf("\n");
}
