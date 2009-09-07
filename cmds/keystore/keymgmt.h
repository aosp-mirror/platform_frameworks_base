/*
** Copyright 2009, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

#ifndef __KEYMGMT_H__
#define __KEYMGMT_H__

#define MASTER_KEY_TAG  "master_key"
#define MASTER_KEY      ".keymaster"
#define MAX_PATH_LEN    128
#define SALT            "Android Keystore 0.1"
#define NAME_DELIMITER  "_"
#define KEYFILE_NAME    "%s"NAME_DELIMITER"%s"
#define KEYGEN_ITER     1024
#define AES_KEY_LEN     128
#define USER_KEY_LEN    (AES_KEY_LEN/8)
#define IV_LEN          USER_KEY_LEN
#define MAX_RETRY_COUNT   6
#define MIN_PASSWD_LENGTH 8

#define gen_key(passwd, key, len) \
                PKCS5_PBKDF2_HMAC_SHA1(passwd, strlen(passwd), \
                                       (unsigned char*)SALT, \
                                       strlen(SALT), KEYGEN_ITER, \
                                       len, key)

#define KEYFILE_LEN MAX_NAMESPACE_LENGTH + MAX_KEY_NAME_LENGTH + 6

#define get_blob_size(blob) \
        (((blob->value_size + sizeof(uint32_t) + MAX_KEY_NAME_LENGTH \
        + USER_KEY_LEN - 1) / USER_KEY_LEN) * USER_KEY_LEN)

#define MAX_BLOB_LEN    ((MAX_KEY_VALUE_LENGTH + MAX_KEY_NAME_LENGTH + \
                         sizeof(uint32_t) + USER_KEY_LEN - 1) / USER_KEY_LEN)\
                         * USER_KEY_LEN

#define data_blob_size(blob) USER_KEY_LEN + sizeof(uint32_t) + blob->blob_size

typedef struct {
    unsigned char iv[USER_KEY_LEN];
    uint32_t blob_size;
    union {
        unsigned char blob[1];
        struct {
            uint32_t value_size;
            char keyname[MAX_KEY_NAME_LENGTH];
            unsigned char value[MAX_KEY_VALUE_LENGTH];
        } __attribute__((packed));
    };
} DATA_BLOB;

typedef struct {
    char tag[USER_KEY_LEN];
    unsigned char master_key[USER_KEY_LEN];
} MASTER_BLOB;

int put_key(const char *namespace, const char *keyname,
            unsigned char *data, int size);
int get_key(const char *namespace, const char *keyname,
            unsigned char *data, int *size);
int remove_key(const char *namespace, const char *keyname);
int list_keys(const char *namespace, char reply[BUFFER_MAX]);
int new_passwd(char *password);
int change_passwd(char *old_pass, char *new_pass);
int lock();
int unlock(char *passwd);
KEYSTORE_STATE get_state();
int reset_keystore();
int init_keystore(const char *dir);

#endif
