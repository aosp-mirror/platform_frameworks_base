/*
 * Copyright (C) 2009 The Android Open Source Project
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

#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <unistd.h>
#include <signal.h>
#include <errno.h>
#include <dirent.h>
#include <fcntl.h>
#include <limits.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <arpa/inet.h>

#include <openssl/aes.h>
#include <openssl/evp.h>
#include <openssl/md5.h>

#define LOG_TAG "keystore"
#include <cutils/log.h>
#include <cutils/sockets.h>
#include <private/android_filesystem_config.h>

#include "keystore.h"

/* KeyStore is a secured storage for key-value pairs. In this implementation,
 * each file stores one key-value pair. Keys are encoded in file names, and
 * values are encrypted with checksums. The encryption key is protected by a
 * user-defined password. To keep things simple, buffers are always larger than
 * the maximum space we needed, so boundary checks on buffers are omitted. */

#define KEY_SIZE        ((NAME_MAX - 15) / 2)
#define VALUE_SIZE      32768
#define PASSWORD_SIZE   VALUE_SIZE

/* Here is the encoding of keys. This is necessary in order to allow arbitrary
 * characters in keys. Characters in [0-~] are not encoded. Others are encoded
 * into two bytes. The first byte is one of [+-.] which represents the first
 * two bits of the character. The second byte encodes the rest of the bits into
 * [0-o]. Therefore in the worst case the length of a key gets doubled. Note
 * that Base64 cannot be used here due to the need of prefix match on keys. */

static int encode_key(char *out, uint8_t *in, int length)
{
    int i;
    for (i = length; i > 0; --i, ++in, ++out) {
        if (*in >= '0' && *in <= '~') {
            *out = *in;
        } else {
            *out = '+' + (*in >> 6);
            *++out = '0' + (*in & 0x3F);
            ++length;
        }
    }
    *out = 0;
    return length;
}

static int decode_key(uint8_t *out, char *in, int length)
{
    int i;
    for (i = 0; i < length; ++i, ++in, ++out) {
        if (*in >= '0' && *in <= '~') {
            *out = *in;
        } else {
            *out = (*in - '+') << 6;
            *out |= (*++in - '0') & 0x3F;
            --length;
        }
    }
    *out = 0;
    return length;
}

/* Here is the protocol used in both requests and responses:
 *     code [length_1 message_1 ... length_n message_n] end-of-file
 * where code is one byte long and lengths are unsigned 16-bit integers in
 * network order. Thus the maximum length of a message is 65535 bytes. */

static int the_socket = -1;

static int recv_code(int8_t *code)
{
    return recv(the_socket, code, 1, 0) == 1;
}

static int recv_message(uint8_t *message, int length)
{
    uint8_t bytes[2];
    if (recv(the_socket, &bytes[0], 1, 0) != 1 ||
        recv(the_socket, &bytes[1], 1, 0) != 1) {
        return -1;
    } else {
        int offset = bytes[0] << 8 | bytes[1];
        if (length < offset) {
            return -1;
        }
        length = offset;
        offset = 0;
        while (offset < length) {
            int n = recv(the_socket, &message[offset], length - offset, 0);
            if (n <= 0) {
                return -1;
            }
            offset += n;
        }
    }
    return length;
}

static int recv_end_of_file()
{
    uint8_t byte;
    return recv(the_socket, &byte, 1, 0) == 0;
}

static void send_code(int8_t code)
{
    send(the_socket, &code, 1, 0);
}

static void send_message(uint8_t *message, int length)
{
    uint16_t bytes = htons(length);
    send(the_socket, &bytes, 2, 0);
    send(the_socket, message, length, 0);
}

/* Here is the file format. Values are encrypted by AES CBC, and MD5 is used to
 * compute their checksums. To make the files portable, the length is stored in
 * network order. Note that the first four bytes are reserved for future use and
 * are always set to zero in this implementation. */

static int the_entropy = -1;

static struct __attribute__((packed)) {
    uint32_t reserved;
    uint8_t vector[AES_BLOCK_SIZE];
    uint8_t encrypted[0];
    uint8_t digest[MD5_DIGEST_LENGTH];
    uint8_t digested[0];
    int32_t length;
    uint8_t value[VALUE_SIZE + AES_BLOCK_SIZE];
} blob;

static int8_t encrypt_blob(char *name, AES_KEY *aes_key)
{
    uint8_t vector[AES_BLOCK_SIZE];
    int length;
    int fd;

    if (read(the_entropy, vector, AES_BLOCK_SIZE) != AES_BLOCK_SIZE) {
        return SYSTEM_ERROR;
    }

    length = blob.length + blob.value - blob.encrypted;
    length = (length + AES_BLOCK_SIZE - 1) / AES_BLOCK_SIZE * AES_BLOCK_SIZE;

    blob.length = htonl(blob.length);
    MD5(blob.digested, length - (blob.digested - blob.encrypted), blob.digest);

    memcpy(vector, blob.vector, AES_BLOCK_SIZE);
    AES_cbc_encrypt(blob.encrypted, blob.encrypted, length, aes_key, vector,
                    AES_ENCRYPT);

    blob.reserved = 0;
    length += blob.encrypted - (uint8_t *)&blob;

    fd = open(".tmp", O_WRONLY | O_TRUNC | O_CREAT, S_IRUSR | S_IWUSR);
    length -= write(fd, &blob, length);
    close(fd);
    return (length || rename(".tmp", name)) ? SYSTEM_ERROR : NO_ERROR;
}

static int8_t decrypt_blob(char *name, AES_KEY *aes_key)
{
    int fd = open(name, O_RDONLY);
    int length;

    if (fd == -1) {
        return (errno == ENOENT) ? KEY_NOT_FOUND : SYSTEM_ERROR;
    }
    length = read(fd, &blob, sizeof(blob));
    close(fd);

    length -= blob.encrypted - (uint8_t *)&blob;
    if (length < blob.value - blob.encrypted || length % AES_BLOCK_SIZE != 0) {
        return VALUE_CORRUPTED;
    }

    AES_cbc_encrypt(blob.encrypted, blob.encrypted, length, aes_key,
                    blob.vector, AES_DECRYPT);
    length -= blob.digested - blob.encrypted;
    if (memcmp(blob.digest, MD5(blob.digested, length, NULL),
               MD5_DIGEST_LENGTH)) {
        return VALUE_CORRUPTED;
    }

    length -= blob.value - blob.digested;
    blob.length = ntohl(blob.length);
    return (blob.length < 0 || blob.length > length) ? VALUE_CORRUPTED :
           NO_ERROR;
}

/* Here are the actions. Each of them is a function without arguments. All
 * information is defined in global variables, which are set properly before
 * performing an action. The number of parameters required by each action is
 * fixed and defined in a table. If the return value of an action is positive,
 * it will be treated as a response code and transmitted to the client. Note
 * that the lengths of parameters are checked when they are received, so
 * boundary checks on parameters are omitted. */

#define MAX_PARAM   2
#define MAX_RETRY   4

static uid_t uid = -1;
static int8_t state = UNINITIALIZED;
static int8_t retry = MAX_RETRY;

static struct {
    int length;
    uint8_t value[VALUE_SIZE];
} params[MAX_PARAM];

static AES_KEY encryption_key;
static AES_KEY decryption_key;

static int8_t test()
{
    return state;
}

static int8_t get()
{
    char name[NAME_MAX];
    int n = sprintf(name, "%u_", uid);
    encode_key(&name[n], params[0].value, params[0].length);
    n = decrypt_blob(name, &decryption_key);
    if (n != NO_ERROR) {
        return n;
    }
    send_code(NO_ERROR);
    send_message(blob.value, blob.length);
    return -NO_ERROR;
}

static int8_t insert()
{
    char name[NAME_MAX];
    int n = sprintf(name, "%u_", uid);
    encode_key(&name[n], params[0].value, params[0].length);
    blob.length = params[1].length;
    memcpy(blob.value, params[1].value, params[1].length);
    return encrypt_blob(name, &encryption_key);
}

static int8_t delete()
{
    char name[NAME_MAX];
    int n = sprintf(name, "%u_", uid);
    encode_key(&name[n], params[0].value, params[0].length);
    return (unlink(name) && errno != ENOENT) ? SYSTEM_ERROR : NO_ERROR;
}

static int8_t exist()
{
    char name[NAME_MAX];
    int n = sprintf(name, "%u_", uid);
    encode_key(&name[n], params[0].value, params[0].length);
    if (access(name, R_OK) == -1) {
        return (errno != ENOENT) ? SYSTEM_ERROR : KEY_NOT_FOUND;
    }
    return NO_ERROR;
}

static int8_t saw()
{
    DIR *dir = opendir(".");
    struct dirent *file;
    char name[NAME_MAX];
    int n;

    if (!dir) {
        return SYSTEM_ERROR;
    }
    n = sprintf(name, "%u_", uid);
    n += encode_key(&name[n], params[0].value, params[0].length);
    send_code(NO_ERROR);
    while ((file = readdir(dir)) != NULL) {
        if (!strncmp(name, file->d_name, n)) {
            char *p = &file->d_name[n];
            params[0].length = decode_key(params[0].value, p, strlen(p));
            send_message(params[0].value, params[0].length);
        }
    }
    closedir(dir);
    return -NO_ERROR;
}

static int8_t reset()
{
    DIR *dir = opendir(".");
    struct dirent *file;

    memset(&encryption_key, 0, sizeof(encryption_key));
    memset(&decryption_key, 0, sizeof(decryption_key));
    state = UNINITIALIZED;
    retry = MAX_RETRY;

    if (!dir) {
        return SYSTEM_ERROR;
    }
    while ((file = readdir(dir)) != NULL) {
        unlink(file->d_name);
    }
    closedir(dir);
    return NO_ERROR;
}

#define MASTER_KEY_FILE ".masterkey"
#define MASTER_KEY_SIZE 16

static void generate_key(uint8_t *key, uint8_t *password, int length)
{
    PKCS5_PBKDF2_HMAC_SHA1((char *)password, length, (uint8_t *)"keystore",
                           sizeof("keystore"), 1024, MASTER_KEY_SIZE, key);
}

static int8_t password()
{
    uint8_t key[MASTER_KEY_SIZE];
    AES_KEY aes_key;
    int n;

    if (state == UNINITIALIZED) {
        blob.length = MASTER_KEY_SIZE;
        if (read(the_entropy, blob.value, MASTER_KEY_SIZE) != MASTER_KEY_SIZE) {
           return SYSTEM_ERROR;
        }
    } else {
        generate_key(key, params[0].value, params[0].length);
        AES_set_decrypt_key(key, MASTER_KEY_SIZE * 8, &aes_key);
        n = decrypt_blob(MASTER_KEY_FILE, &aes_key);
        if (n == SYSTEM_ERROR) {
            return SYSTEM_ERROR;
        }
        if (n != NO_ERROR || blob.length != MASTER_KEY_SIZE) {
            if (retry <= 0) {
                reset();
                return UNINITIALIZED;
            }
            return WRONG_PASSWORD + --retry;
        }
    }

    if (params[1].length == -1) {
        memcpy(key, blob.value, MASTER_KEY_SIZE);
    } else {
        generate_key(key, params[1].value, params[1].length);
        AES_set_encrypt_key(key, MASTER_KEY_SIZE * 8, &aes_key);
        memcpy(key, blob.value, MASTER_KEY_SIZE);
        n = encrypt_blob(MASTER_KEY_FILE, &aes_key);
    }

    if (n == NO_ERROR) {
        AES_set_encrypt_key(key, MASTER_KEY_SIZE * 8, &encryption_key);
        AES_set_decrypt_key(key, MASTER_KEY_SIZE * 8, &decryption_key);
        state = NO_ERROR;
        retry = MAX_RETRY;
    }
    return n;
}

static int8_t lock()
{
    memset(&encryption_key, 0, sizeof(encryption_key));
    memset(&decryption_key, 0, sizeof(decryption_key));
    state = LOCKED;
    return NO_ERROR;
}

static int8_t unlock()
{
    params[1].length = -1;
    return password();
}

/* Here are the permissions, actions, users, and the main function. */

enum perm {
    TEST     =   1,
    GET      =   2,
    INSERT   =   4,
    DELETE   =   8,
    EXIST    =  16,
    SAW      =  32,
    RESET    =  64,
    PASSWORD = 128,
    LOCK     = 256,
    UNLOCK   = 512,
};

static struct action {
    int8_t (*run)();
    int8_t code;
    int8_t state;
    uint32_t perm;
    int lengths[MAX_PARAM];
} actions[] = {
    {test,     't', 0,        TEST,     {0}},
    {get,      'g', NO_ERROR, GET,      {KEY_SIZE}},
    {insert,   'i', NO_ERROR, INSERT,   {KEY_SIZE, VALUE_SIZE}},
    {delete,   'd', 0,        DELETE,   {KEY_SIZE}},
    {exist,    'e', 0,        EXIST,    {KEY_SIZE}},
    {saw,      's', 0,        SAW,      {KEY_SIZE}},
    {reset,    'r', 0,        RESET,    {0}},
    {password, 'p', 0,        PASSWORD, {PASSWORD_SIZE, PASSWORD_SIZE}},
    {lock,     'l', NO_ERROR, LOCK,     {0}},
    {unlock,   'u', LOCKED,   UNLOCK,   {PASSWORD_SIZE}},
    {NULL,      0 , 0,        0,        {0}},
};

static struct user {
    uid_t uid;
    uid_t euid;
    uint32_t perms;
} users[] = {
    {AID_SYSTEM,   ~0,         ~GET},
    {AID_VPN,      AID_SYSTEM, GET},
    {AID_WIFI,     AID_SYSTEM, GET},
    {AID_ROOT,     AID_SYSTEM, GET},
    {~0,           ~0,         TEST | GET | INSERT | DELETE | EXIST | SAW},
};

static int8_t process(int8_t code) {
    struct user *user = users;
    struct action *action = actions;
    int i;

    while (~user->uid && user->uid != uid) {
        ++user;
    }
    while (action->code && action->code != code) {
        ++action;
    }
    if (!action->code) {
        return UNDEFINED_ACTION;
    }
    if (!(action->perm & user->perms)) {
        return PERMISSION_DENIED;
    }
    if (action->state && action->state != state) {
        return state;
    }
    if (~user->euid) {
        uid = user->euid;
    }
    for (i = 0; i < MAX_PARAM && action->lengths[i]; ++i) {
        params[i].length = recv_message(params[i].value, action->lengths[i]);
        if (params[i].length == -1) {
            return PROTOCOL_ERROR;
        }
    }
    if (!recv_end_of_file()) {
        return PROTOCOL_ERROR;
    }
    return action->run();
}

#define RANDOM_DEVICE   "/dev/urandom"

int main(int argc, char **argv)
{
    int control_socket = android_get_control_socket("keystore");
    if (argc < 2) {
        LOGE("A directory must be specified!");
        return 1;
    }
    if (chdir(argv[1]) == -1) {
        LOGE("chdir: %s: %s", argv[1], strerror(errno));
        return 1;
    }
    if ((the_entropy = open(RANDOM_DEVICE, O_RDONLY)) == -1) {
        LOGE("open: %s: %s", RANDOM_DEVICE, strerror(errno));
        return 1;
    }
    if (listen(control_socket, 3) == -1) {
        LOGE("listen: %s", strerror(errno));
        return 1;
    }

    signal(SIGPIPE, SIG_IGN);
    if (access(MASTER_KEY_FILE, R_OK) == 0) {
        state = LOCKED;
    }

    while ((the_socket = accept(control_socket, NULL, 0)) != -1) {
        struct timeval tv = {.tv_sec = 3};
        struct ucred cred;
        socklen_t size = sizeof(cred);
        int8_t request;

        setsockopt(the_socket, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
        setsockopt(the_socket, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));

        if (getsockopt(the_socket, SOL_SOCKET, SO_PEERCRED, &cred, &size)) {
            LOGW("getsockopt: %s", strerror(errno));
        } else if (recv_code(&request)) {
            int8_t old_state = state;
            int8_t response;
            uid = cred.uid;

            if ((response = process(request)) > 0) {
                send_code(response);
                response = -response;
            }

            LOGI("uid: %d action: %c -> %d state: %d -> %d retry: %d",
                 cred.uid, request, -response, old_state, state, retry);
        }
        close(the_socket);
    }
    LOGE("accept: %s", strerror(errno));
    return 1;
}
