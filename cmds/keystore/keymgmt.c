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

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <ctype.h>
#include <fcntl.h>
#include <dirent.h>
#include <errno.h>
#include <openssl/aes.h>
#include <openssl/evp.h>
#include <cutils/log.h>

#include "common.h"
#include "keymgmt.h"

static int  retry_count = 0;
static unsigned char iv[IV_LEN];
static KEYSTORE_STATE state = BOOTUP;
static AES_KEY encryptKey, decryptKey;

inline void unlock_keystore(unsigned char *master_key)
{
    AES_set_encrypt_key(master_key, AES_KEY_LEN, &encryptKey);
    AES_set_decrypt_key(master_key, AES_KEY_LEN, &decryptKey);
    memset(master_key, 0, sizeof(master_key));
    state = UNLOCKED;
}

inline void lock_keystore()
{
    memset(&encryptKey, 0 , sizeof(AES_KEY));
    memset(&decryptKey, 0 , sizeof(AES_KEY));
    state = LOCKED;
}

inline void get_encrypt_key(char *passwd, AES_KEY *key)
{
    unsigned char user_key[USER_KEY_LEN];
    gen_key(passwd, user_key, USER_KEY_LEN);
    AES_set_encrypt_key(user_key, AES_KEY_LEN, key);
}

inline void get_decrypt_key(char *passwd, AES_KEY *key)
{
    unsigned char user_key[USER_KEY_LEN];
    gen_key(passwd, user_key, USER_KEY_LEN);
    AES_set_decrypt_key(user_key, AES_KEY_LEN, key);
}

static int gen_random_blob(unsigned char *key, int size)
{
    int ret = 0;
    int fd = open("/dev/urandom", O_RDONLY);
    if (fd == -1) return -1;
    if (read(fd, key, size) != size) ret = -1;
    close(fd);
    return ret;
}

static int encrypt_n_save(AES_KEY *enc_key, DATA_BLOB *blob,
                          const char *keyfile)
{
    int size, fd, ret = -1;
    unsigned char enc_blob[MAX_BLOB_LEN];
    char tmpfile[KEYFILE_LEN];

    if ((keyfile == NULL) || (strlen(keyfile) >= (KEYFILE_LEN - 4))) {
        LOGE("keyfile name is too long or null");
        return -1;
    }
    strcpy(tmpfile, keyfile);
    strcat(tmpfile, ".tmp");

    // prepare the blob
    if (IV_LEN > USER_KEY_LEN) {
        LOGE("iv length is too long.");
        return -1;
    }
    memcpy(blob->iv, iv, IV_LEN);
    blob->blob_size = get_blob_size(blob);
    if (blob->blob_size > MAX_BLOB_LEN) {
        LOGE("blob data size is too large.");
        return -1;
    }
    memcpy(enc_blob, blob->blob, blob->blob_size);
    AES_cbc_encrypt((unsigned char *)enc_blob, (unsigned char *)blob->blob,
                    blob->blob_size, enc_key, iv, AES_ENCRYPT);
    // write to keyfile
    size = data_blob_size(blob);
    if ((fd = open(tmpfile, O_CREAT|O_RDWR)) == -1) return -1;
    if (write(fd, blob, size) == size) ret = 0;
    close(fd);
    if (!ret) {
        unlink(keyfile);
        rename(tmpfile, keyfile);
        chmod(keyfile, 0440);
    }
    return ret;
}

static int load_n_decrypt(const char *keyname, const char *keyfile,
                          AES_KEY *key, DATA_BLOB *blob)
{
    int fd, ret = -1;
    if ((fd = open(keyfile, O_RDONLY)) == -1) return -1;
    // get the encrypted blob and iv
    if ((read(fd, blob->iv, sizeof(blob->iv)) != sizeof(blob->iv)) ||
        (read(fd, &blob->blob_size, sizeof(uint32_t)) != sizeof(uint32_t)) ||
        (blob->blob_size > MAX_BLOB_LEN)) {
        goto err;
    } else {
        unsigned char enc_blob[MAX_BLOB_LEN];
        if (read(fd, enc_blob, blob->blob_size) !=
            (int) blob->blob_size) goto err;
        // decrypt the blob
        AES_cbc_encrypt((unsigned char *)enc_blob, (unsigned char*)blob->blob,
                        blob->blob_size, key, blob->iv, AES_DECRYPT);
        if (strcmp(keyname, (char*)blob->keyname) == 0) ret = 0;
    }
err:
    close(fd);
    return ret;
}

static int store_master_key(char *upasswd, unsigned char *master_key)
{
    AES_KEY key;
    DATA_BLOB blob;

    // prepare the blob
    if (strlen(MASTER_KEY_TAG) >= USER_KEY_LEN) return -1;
    strlcpy(blob.keyname, MASTER_KEY_TAG, USER_KEY_LEN);
    blob.value_size = USER_KEY_LEN;
    if (USER_KEY_LEN > MAX_KEY_VALUE_LENGTH) {
        LOGE("master_key length is too long.");
        return -1;
    }
    memcpy((void*)blob.value, (const void*)master_key, USER_KEY_LEN);

    // generate the encryption key
    get_encrypt_key(upasswd, &key);
    return encrypt_n_save(&key, &blob, MASTER_KEY);
}

static int get_master_key(char *upasswd, unsigned char *master_key)
{
    AES_KEY key;
    int size, ret = 0;
    DATA_BLOB blob;

    get_decrypt_key(upasswd, &key);
    ret = load_n_decrypt(MASTER_KEY_TAG, MASTER_KEY, &key, &blob);
    if (blob.value_size > USER_KEY_LEN) {
        LOGE("the blob's value size is too large");
        return -1;
    }
    if (!ret) memcpy(master_key, blob.value, blob.value_size);
    return ret;
}

static int create_master_key(char *upasswd)
{
    int ret;
    unsigned char mpasswd[AES_KEY_LEN];
    unsigned char master_key[USER_KEY_LEN];

    gen_random_blob(mpasswd, AES_KEY_LEN);
    gen_key((char*)mpasswd, master_key, USER_KEY_LEN);
    if ((ret = store_master_key(upasswd, master_key)) == 0) {
        unlock_keystore(master_key);
    }
    memset(master_key, 0, USER_KEY_LEN);
    memset(mpasswd, 0, AES_KEY_LEN);

    return ret;
}

int change_passwd(char *old_pass, char *new_pass)
{
    unsigned char master_key[USER_KEY_LEN];
    int ret;

    if (state == UNINITIALIZED) return -1;
    if ((strlen(old_pass) < MIN_PASSWD_LENGTH) ||
        (strlen(new_pass) < MIN_PASSWD_LENGTH)) return -1;

    if ((ret = get_master_key(old_pass, master_key)) == 0) {
        ret = store_master_key(new_pass, master_key);
        retry_count = 0;
    } else {
        ret = MAX_RETRY_COUNT - ++retry_count;
        if (ret == 0) {
            retry_count = 0;
            LOGE("passwd:reach max retry count, reset the keystore now.");
            reset_keystore();
            return -1;
        }

    }
    return ret;
}

int remove_key(const char *namespace, const char *keyname)
{
    char keyfile[KEYFILE_LEN];

    if (state != UNLOCKED) return -state;
    if ((strlen(namespace) >= MAX_KEY_NAME_LENGTH) ||
        (strlen(keyname) >= MAX_KEY_NAME_LENGTH)) {
        LOGE("keyname is too long.");
        return -1;
    }
    sprintf(keyfile, KEYFILE_NAME, namespace, keyname);
    return unlink(keyfile);
}

int put_key(const char *namespace, const char *keyname,
            unsigned char *data, int size)
{
    DATA_BLOB blob;
    uint32_t  real_size;
    char keyfile[KEYFILE_LEN];

    if (state != UNLOCKED) {
        LOGE("Can not store key with current state %d\n", state);
        return -state;
    }
    if ((strlen(namespace) >= MAX_KEY_NAME_LENGTH) ||
        (strlen(keyname) >= MAX_KEY_NAME_LENGTH)) {
        LOGE("keyname is too long.");
        return -1;
    }
    sprintf(keyfile, KEYFILE_NAME, namespace, keyname);
    strcpy(blob.keyname, keyname);
    blob.value_size = size;
    if (size > MAX_KEY_VALUE_LENGTH) {
        LOGE("the data size is too large.");
        return -1;
    }
    memcpy(blob.value, data, size);
    return encrypt_n_save(&encryptKey, &blob, keyfile);
}

int get_key(const char *namespace, const char *keyname,
            unsigned char *data, int *size)
{
    int ret;
    DATA_BLOB blob;
    uint32_t  blob_size;
    char keyfile[KEYFILE_LEN];

    if (state != UNLOCKED) {
        LOGE("Can not retrieve key value with current state %d\n", state);
        return -state;
    }
    if ((strlen(namespace) >= MAX_KEY_NAME_LENGTH) ||
        (strlen(keyname) >= MAX_KEY_NAME_LENGTH)) {
        LOGE("keyname is too long.");
        return -1;
    }
    sprintf(keyfile, KEYFILE_NAME, namespace, keyname);
    ret = load_n_decrypt(keyname, keyfile, &decryptKey, &blob);
    if (!ret) {
        if ((blob.value_size > MAX_KEY_VALUE_LENGTH)) {
            LOGE("blob value size is too large.");
            ret = -1;
        } else {
            *size = blob.value_size;
            memcpy(data, blob.value, *size);
        }
    }
    return ret;
}

int list_keys(const char *namespace, char reply[BUFFER_MAX])
{
    DIR *d;
    struct dirent *de;

    if (state != UNLOCKED) {
        LOGE("Can not list key with current state %d\n", state);
        return -1;
    }

    if (!namespace || ((d = opendir("."))) == NULL) {
        LOGE("cannot open keystore dir or namespace is null\n");
        return -1;
    }

    if (strlen(namespace) >= MAX_KEY_NAME_LENGTH) {
        LOGE("namespace is too long.");
        return -1;
    }

    reply[0] = 0;
    while ((de = readdir(d))) {
        char *prefix, *name, *keyfile = de->d_name;
        char *context = NULL;

        if (de->d_type != DT_REG) continue;
        if ((prefix = strtok_r(keyfile, NAME_DELIMITER, &context))
            == NULL) continue;
        if (strcmp(prefix, namespace)) continue;
        if ((name = strtok_r(NULL, NAME_DELIMITER, &context)) == NULL) continue;
        // append the key name into reply
        if (reply[0] != 0) strlcat(reply, " ", BUFFER_MAX);
        if (strlcat(reply, name, BUFFER_MAX) >= BUFFER_MAX) {
            LOGE("too many files under keystore directory\n");
            return -1;
        }
    }
    closedir(d);
    return 0;
}

int new_passwd(char *password)
{
    int passwdlen = strlen(password);

    if ((state != UNINITIALIZED) || (passwdlen < MIN_PASSWD_LENGTH)) return -1;
    return create_master_key(password);
}

int lock()
{
    switch(state) {
        case UNLOCKED:
            lock_keystore();
        case LOCKED:
            return 0;
        default:
            return -1;
    }
}

int unlock(char *passwd)
{
    unsigned char master_key[USER_KEY_LEN];
    int ret = get_master_key(passwd, master_key);
    if (!ret) {
        unlock_keystore(master_key);
        retry_count = 0;
    } else {
        ret = MAX_RETRY_COUNT - ++retry_count;
        if (ret == 0) {
            retry_count = 0;
            LOGE("unlock:reach max retry count, reset the keystore now.");
            reset_keystore();
            return -1;
        }
    }
    return ret;
}

KEYSTORE_STATE get_state()
{
    return state;
}

int reset_keystore()
{
    int ret = 0;
    DIR *d;
    struct dirent *de;

    if ((d = opendir(".")) == NULL) {
        LOGE("cannot open keystore dir\n");
        return -1;
    }
    while ((de = readdir(d))) {
        char *dirname = de->d_name;
        if (strcmp(".", dirname) == 0) continue;
        if (strcmp("..", dirname) == 0) continue;
        if (unlink(dirname) != 0) ret = -1;
    }
    closedir(d);
    state = UNINITIALIZED;
    if (ret == 0) {
        LOGI("keystore is reset.");
    } else {
        LOGI("keystore can not be cleaned up entirely.");
    }
    return ret;
}

int init_keystore(const char *dir)
{
    int fd;

    if (dir) mkdir(dir, 0770);
    if (!dir || chdir(dir)) {
        LOGE("Can not open/create the keystore directory %s\n",
             dir ? dir : "(null)");
        return -1;
    }
    gen_random_blob(iv, IV_LEN);
    if ((fd = open(MASTER_KEY, O_RDONLY)) == -1) {
        state = UNINITIALIZED;
        return 0;
    }
    close(fd);
    state = LOCKED;
    return 0;
}
