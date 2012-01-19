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

struct Value {
    int length;
    uint8_t value[VALUE_SIZE];
};

/* Here is the encoding of keys. This is necessary in order to allow arbitrary
 * characters in keys. Characters in [0-~] are not encoded. Others are encoded
 * into two bytes. The first byte is one of [+-.] which represents the first
 * two bits of the character. The second byte encodes the rest of the bits into
 * [0-o]. Therefore in the worst case the length of a key gets doubled. Note
 * that Base64 cannot be used here due to the need of prefix match on keys. */

static int encode_key(char* out, uid_t uid, const Value* key) {
    int n = snprintf(out, NAME_MAX, "%u_", uid);
    out += n;
    const uint8_t* in = key->value;
    int length = key->length;
    for (int i = length; i > 0; --i, ++in, ++out) {
        if (*in >= '0' && *in <= '~') {
            *out = *in;
        } else {
            *out = '+' + (*in >> 6);
            *++out = '0' + (*in & 0x3F);
            ++length;
        }
    }
    *out = '\0';
    return n + length;
}

static int decode_key(uint8_t* out, char* in, int length) {
    for (int i = 0; i < length; ++i, ++in, ++out) {
        if (*in >= '0' && *in <= '~') {
            *out = *in;
        } else {
            *out = (*in - '+') << 6;
            *out |= (*++in - '0') & 0x3F;
            --length;
        }
    }
    *out = '\0';
    return length;
}

static size_t readFully(int fd, uint8_t* data, size_t size) {
    size_t remaining = size;
    while (remaining > 0) {
        ssize_t n = TEMP_FAILURE_RETRY(read(fd, data, size));
        if (n == -1 || n == 0) {
            return size-remaining;
        }
        data += n;
        remaining -= n;
    }
    return size;
}

static size_t writeFully(int fd, uint8_t* data, size_t size) {
    size_t remaining = size;
    while (remaining > 0) {
        ssize_t n = TEMP_FAILURE_RETRY(write(fd, data, size));
        if (n == -1 || n == 0) {
            return size-remaining;
        }
        data += n;
        remaining -= n;
    }
    return size;
}

class Entropy {
public:
    Entropy() : mRandom(-1) {}
    ~Entropy() {
        if (mRandom != -1) {
            close(mRandom);
        }
    }

    bool open() {
        const char* randomDevice = "/dev/urandom";
        mRandom = ::open(randomDevice, O_RDONLY);
        if (mRandom == -1) {
            ALOGE("open: %s: %s", randomDevice, strerror(errno));
            return false;
        }
        return true;
    }

    bool generate_random_data(uint8_t* data, size_t size) {
        return (readFully(mRandom, data, size) == size);
    }

private:
    int mRandom;
};

/* Here is the file format. There are two parts in blob.value, the secret and
 * the description. The secret is stored in ciphertext, and its original size
 * can be found in blob.length. The description is stored after the secret in
 * plaintext, and its size is specified in blob.info. The total size of the two
 * parts must be no more than VALUE_SIZE bytes. The first three bytes of the
 * file are reserved for future use and are always set to zero. Fields other
 * than blob.info, blob.length, and blob.value are modified by encryptBlob()
 * and decryptBlob(). Thus they should not be accessed from outside. */

struct __attribute__((packed)) blob {
    uint8_t reserved[3];
    uint8_t info;
    uint8_t vector[AES_BLOCK_SIZE];
    uint8_t encrypted[0];
    uint8_t digest[MD5_DIGEST_LENGTH];
    uint8_t digested[0];
    int32_t length; // in network byte order when encrypted
    uint8_t value[VALUE_SIZE + AES_BLOCK_SIZE];
};

class Blob {
public:
    Blob(uint8_t* value, int32_t valueLength, uint8_t* info, uint8_t infoLength) {
        mBlob.length = valueLength;
        memcpy(mBlob.value, value, valueLength);

        mBlob.info = infoLength;
        memcpy(mBlob.value + valueLength, info, infoLength);
    }

    Blob(blob b) {
        mBlob = b;
    }

    Blob() {}

    uint8_t* getValue() {
        return mBlob.value;
    }

    int32_t getLength() {
        return mBlob.length;
    }

    uint8_t getInfo() {
        return mBlob.info;
    }

    ResponseCode encryptBlob(const char* filename, AES_KEY *aes_key, Entropy* entropy) {
        if (!entropy->generate_random_data(mBlob.vector, AES_BLOCK_SIZE)) {
            return SYSTEM_ERROR;
        }

        // data includes the value and the value's length
        size_t dataLength = mBlob.length + sizeof(mBlob.length);
        // pad data to the AES_BLOCK_SIZE
        size_t digestedLength = ((dataLength + AES_BLOCK_SIZE - 1)
                                 / AES_BLOCK_SIZE * AES_BLOCK_SIZE);
        // encrypted data includes the digest value
        size_t encryptedLength = digestedLength + MD5_DIGEST_LENGTH;
        // move info after space for padding
        memmove(&mBlob.encrypted[encryptedLength], &mBlob.value[mBlob.length], mBlob.info);
        // zero padding area
        memset(mBlob.value + mBlob.length, 0, digestedLength - dataLength);

        mBlob.length = htonl(mBlob.length);
        MD5(mBlob.digested, digestedLength, mBlob.digest);

        uint8_t vector[AES_BLOCK_SIZE];
        memcpy(vector, mBlob.vector, AES_BLOCK_SIZE);
        AES_cbc_encrypt(mBlob.encrypted, mBlob.encrypted, encryptedLength,
                        aes_key, vector, AES_ENCRYPT);

        memset(mBlob.reserved, 0, sizeof(mBlob.reserved));
        size_t headerLength = (mBlob.encrypted - (uint8_t*) &mBlob);
        size_t fileLength = encryptedLength + headerLength + mBlob.info;

        const char* tmpFileName = ".tmp";
        int out = open(tmpFileName, O_WRONLY | O_TRUNC | O_CREAT, S_IRUSR | S_IWUSR);
        if (out == -1) {
            return SYSTEM_ERROR;
        }
        size_t writtenBytes = writeFully(out, (uint8_t*) &mBlob, fileLength);
        if (close(out) != 0) {
            return SYSTEM_ERROR;
        }
        if (writtenBytes != fileLength) {
            unlink(tmpFileName);
            return SYSTEM_ERROR;
        }
        return (rename(tmpFileName, filename) == 0) ? NO_ERROR : SYSTEM_ERROR;
    }

    ResponseCode decryptBlob(const char* filename, AES_KEY *aes_key) {
        int in = open(filename, O_RDONLY);
        if (in == -1) {
            return (errno == ENOENT) ? KEY_NOT_FOUND : SYSTEM_ERROR;
        }
        // fileLength may be less than sizeof(mBlob) since the in
        // memory version has extra padding to tolerate rounding up to
        // the AES_BLOCK_SIZE
        size_t fileLength = readFully(in, (uint8_t*) &mBlob, sizeof(mBlob));
        if (close(in) != 0) {
            return SYSTEM_ERROR;
        }
        size_t headerLength = (mBlob.encrypted - (uint8_t*) &mBlob);
        if (fileLength < headerLength) {
            return VALUE_CORRUPTED;
        }

        ssize_t encryptedLength = fileLength - (headerLength + mBlob.info);
        if (encryptedLength < 0 || encryptedLength % AES_BLOCK_SIZE != 0) {
            return VALUE_CORRUPTED;
        }
        AES_cbc_encrypt(mBlob.encrypted, mBlob.encrypted, encryptedLength, aes_key,
                        mBlob.vector, AES_DECRYPT);
        size_t digestedLength = encryptedLength - MD5_DIGEST_LENGTH;
        uint8_t computedDigest[MD5_DIGEST_LENGTH];
        MD5(mBlob.digested, digestedLength, computedDigest);
        if (memcmp(mBlob.digest, computedDigest, MD5_DIGEST_LENGTH) != 0) {
            return VALUE_CORRUPTED;
        }

        ssize_t maxValueLength = digestedLength - sizeof(mBlob.length);
        mBlob.length = ntohl(mBlob.length);
        if (mBlob.length < 0 || mBlob.length > maxValueLength) {
            return VALUE_CORRUPTED;
        }
        if (mBlob.info != 0) {
            // move info from after padding to after data
            memmove(&mBlob.value[mBlob.length], &mBlob.value[maxValueLength], mBlob.info);
        }
        return NO_ERROR;
    }

private:
    struct blob mBlob;
};

class KeyStore {
public:
    KeyStore(Entropy* entropy) : mEntropy(entropy), mRetry(MAX_RETRY) {
        if (access(MASTER_KEY_FILE, R_OK) == 0) {
            setState(STATE_LOCKED);
        } else {
            setState(STATE_UNINITIALIZED);
        }
    }

    State getState() {
        return mState;
    }

    int8_t getRetry() {
        return mRetry;
    }

    ResponseCode initialize(Value* pw) {
        if (!generateMasterKey()) {
            return SYSTEM_ERROR;
        }
        ResponseCode response = writeMasterKey(pw);
        if (response != NO_ERROR) {
            return response;
        }
        setupMasterKeys();
        return NO_ERROR;
    }

    ResponseCode writeMasterKey(Value* pw) {
        uint8_t passwordKey[MASTER_KEY_SIZE_BYTES];
        generateKeyFromPassword(passwordKey, MASTER_KEY_SIZE_BYTES, pw, mSalt);
        AES_KEY passwordAesKey;
        AES_set_encrypt_key(passwordKey, MASTER_KEY_SIZE_BITS, &passwordAesKey);
        Blob masterKeyBlob(mMasterKey, sizeof(mMasterKey), mSalt, sizeof(mSalt));
        return masterKeyBlob.encryptBlob(MASTER_KEY_FILE, &passwordAesKey, mEntropy);
    }

    ResponseCode readMasterKey(Value* pw) {
        int in = open(MASTER_KEY_FILE, O_RDONLY);
        if (in == -1) {
            return SYSTEM_ERROR;
        }

        // we read the raw blob to just to get the salt to generate
        // the AES key, then we create the Blob to use with decryptBlob
        blob rawBlob;
        size_t length = readFully(in, (uint8_t*) &rawBlob, sizeof(rawBlob));
        if (close(in) != 0) {
            return SYSTEM_ERROR;
        }
        // find salt at EOF if present, otherwise we have an old file
        uint8_t* salt;
        if (length > SALT_SIZE && rawBlob.info == SALT_SIZE) {
            salt = (uint8_t*) &rawBlob + length - SALT_SIZE;
        } else {
            salt = NULL;
        }
        uint8_t passwordKey[MASTER_KEY_SIZE_BYTES];
        generateKeyFromPassword(passwordKey, MASTER_KEY_SIZE_BYTES, pw, salt);
        AES_KEY passwordAesKey;
        AES_set_decrypt_key(passwordKey, MASTER_KEY_SIZE_BITS, &passwordAesKey);
        Blob masterKeyBlob(rawBlob);
        ResponseCode response = masterKeyBlob.decryptBlob(MASTER_KEY_FILE, &passwordAesKey);
        if (response == SYSTEM_ERROR) {
            return SYSTEM_ERROR;
        }
        if (response == NO_ERROR && masterKeyBlob.getLength() == MASTER_KEY_SIZE_BYTES) {
            // if salt was missing, generate one and write a new master key file with the salt.
            if (salt == NULL) {
                if (!generateSalt()) {
                    return SYSTEM_ERROR;
                }
                response = writeMasterKey(pw);
            }
            if (response == NO_ERROR) {
                memcpy(mMasterKey, masterKeyBlob.getValue(), MASTER_KEY_SIZE_BYTES);
                setupMasterKeys();
            }
            return response;
        }
        if (mRetry <= 0) {
            reset();
            return UNINITIALIZED;
        }
        --mRetry;
        switch (mRetry) {
            case 0: return WRONG_PASSWORD_0;
            case 1: return WRONG_PASSWORD_1;
            case 2: return WRONG_PASSWORD_2;
            case 3: return WRONG_PASSWORD_3;
            default: return WRONG_PASSWORD_3;
        }
    }

    bool reset() {
        clearMasterKeys();
        setState(STATE_UNINITIALIZED);

        DIR* dir = opendir(".");
        struct dirent* file;

        if (!dir) {
            return false;
        }
        while ((file = readdir(dir)) != NULL) {
            unlink(file->d_name);
        }
        closedir(dir);
        return true;
    }

    bool isEmpty() {
        DIR* dir = opendir(".");
        struct dirent* file;
        if (!dir) {
            return true;
        }
        bool result = true;
        while ((file = readdir(dir)) != NULL) {
            if (isKeyFile(file->d_name)) {
                result = false;
                break;
            }
        }
        closedir(dir);
        return result;
    }

    void lock() {
        clearMasterKeys();
        setState(STATE_LOCKED);
    }

    ResponseCode get(const char* filename, Blob* keyBlob) {
        return keyBlob->decryptBlob(filename, &mMasterKeyDecryption);
    }

    ResponseCode put(const char* filename, Blob* keyBlob) {
        return keyBlob->encryptBlob(filename, &mMasterKeyEncryption, mEntropy);
    }

private:
    static const char* MASTER_KEY_FILE;
    static const int MASTER_KEY_SIZE_BYTES = 16;
    static const int MASTER_KEY_SIZE_BITS = MASTER_KEY_SIZE_BYTES * 8;

    static const int MAX_RETRY = 4;
    static const size_t SALT_SIZE = 16;

    Entropy* mEntropy;

    State mState;
    int8_t mRetry;

    uint8_t mMasterKey[MASTER_KEY_SIZE_BYTES];
    uint8_t mSalt[SALT_SIZE];

    AES_KEY mMasterKeyEncryption;
    AES_KEY mMasterKeyDecryption;

    void setState(State state) {
        mState = state;
        if (mState == STATE_NO_ERROR || mState == STATE_UNINITIALIZED) {
            mRetry = MAX_RETRY;
        }
    }

    bool generateSalt() {
        return mEntropy->generate_random_data(mSalt, sizeof(mSalt));
    }

    bool generateMasterKey() {
        if (!mEntropy->generate_random_data(mMasterKey, sizeof(mMasterKey))) {
            return false;
        }
        if (!generateSalt()) {
            return false;
        }
        return true;
    }

    void setupMasterKeys() {
        AES_set_encrypt_key(mMasterKey, MASTER_KEY_SIZE_BITS, &mMasterKeyEncryption);
        AES_set_decrypt_key(mMasterKey, MASTER_KEY_SIZE_BITS, &mMasterKeyDecryption);
        setState(STATE_NO_ERROR);
    }

    void clearMasterKeys() {
        memset(mMasterKey, 0, sizeof(mMasterKey));
        memset(mSalt, 0, sizeof(mSalt));
        memset(&mMasterKeyEncryption, 0, sizeof(mMasterKeyEncryption));
        memset(&mMasterKeyDecryption, 0, sizeof(mMasterKeyDecryption));
    }

    static void generateKeyFromPassword(uint8_t* key, ssize_t keySize, Value* pw, uint8_t* salt) {
        size_t saltSize;
        if (salt != NULL) {
            saltSize = SALT_SIZE;
        } else {
            // pre-gingerbread used this hardwired salt, readMasterKey will rewrite these when found
            salt = (uint8_t*) "keystore";
            // sizeof = 9, not strlen = 8
            saltSize = sizeof("keystore");
        }
        PKCS5_PBKDF2_HMAC_SHA1((char*) pw->value, pw->length, salt, saltSize, 8192, keySize, key);
    }

    static bool isKeyFile(const char* filename) {
        return ((strcmp(filename, MASTER_KEY_FILE) != 0)
                && (strcmp(filename, ".") != 0)
                && (strcmp(filename, "..") != 0));
    }
};

const char* KeyStore::MASTER_KEY_FILE = ".masterkey";

/* Here is the protocol used in both requests and responses:
 *     code [length_1 message_1 ... length_n message_n] end-of-file
 * where code is one byte long and lengths are unsigned 16-bit integers in
 * network order. Thus the maximum length of a message is 65535 bytes. */

static int recv_code(int sock, int8_t* code) {
    return recv(sock, code, 1, 0) == 1;
}

static int recv_message(int sock, uint8_t* message, int length) {
    uint8_t bytes[2];
    if (recv(sock, &bytes[0], 1, 0) != 1 ||
        recv(sock, &bytes[1], 1, 0) != 1) {
        return -1;
    } else {
        int offset = bytes[0] << 8 | bytes[1];
        if (length < offset) {
            return -1;
        }
        length = offset;
        offset = 0;
        while (offset < length) {
            int n = recv(sock, &message[offset], length - offset, 0);
            if (n <= 0) {
                return -1;
            }
            offset += n;
        }
    }
    return length;
}

static int recv_end_of_file(int sock) {
    uint8_t byte;
    return recv(sock, &byte, 1, 0) == 0;
}

static void send_code(int sock, int8_t code) {
    send(sock, &code, 1, 0);
}

static void send_message(int sock, uint8_t* message, int length) {
    uint16_t bytes = htons(length);
    send(sock, &bytes, 2, 0);
    send(sock, message, length, 0);
}

/* Here are the actions. Each of them is a function without arguments. All
 * information is defined in global variables, which are set properly before
 * performing an action. The number of parameters required by each action is
 * fixed and defined in a table. If the return value of an action is positive,
 * it will be treated as a response code and transmitted to the client. Note
 * that the lengths of parameters are checked when they are received, so
 * boundary checks on parameters are omitted. */

static const ResponseCode NO_ERROR_RESPONSE_CODE_SENT = (ResponseCode) 0;

static ResponseCode test(KeyStore* keyStore, int sock, uid_t uid, Value*, Value*) {
    return (ResponseCode) keyStore->getState();
}

static ResponseCode get(KeyStore* keyStore, int sock, uid_t uid, Value* keyName, Value*) {
    char filename[NAME_MAX];
    encode_key(filename, uid, keyName);
    Blob keyBlob;
    ResponseCode responseCode = keyStore->get(filename, &keyBlob);
    if (responseCode != NO_ERROR) {
        return responseCode;
    }
    send_code(sock, NO_ERROR);
    send_message(sock, keyBlob.getValue(), keyBlob.getLength());
    return NO_ERROR_RESPONSE_CODE_SENT;
}

static ResponseCode insert(KeyStore* keyStore, int sock, uid_t uid, Value* keyName, Value* val) {
    char filename[NAME_MAX];
    encode_key(filename, uid, keyName);
    Blob keyBlob(val->value, val->length, 0, NULL);
    return keyStore->put(filename, &keyBlob);
}

static ResponseCode del(KeyStore* keyStore, int sock, uid_t uid, Value* keyName, Value*) {
    char filename[NAME_MAX];
    encode_key(filename, uid, keyName);
    return (unlink(filename) && errno != ENOENT) ? SYSTEM_ERROR : NO_ERROR;
}

static ResponseCode exist(KeyStore* keyStore, int sock, uid_t uid, Value* keyName, Value*) {
    char filename[NAME_MAX];
    encode_key(filename, uid, keyName);
    if (access(filename, R_OK) == -1) {
        return (errno != ENOENT) ? SYSTEM_ERROR : KEY_NOT_FOUND;
    }
    return NO_ERROR;
}

static ResponseCode saw(KeyStore* keyStore, int sock, uid_t uid, Value* keyPrefix, Value*) {
    DIR* dir = opendir(".");
    if (!dir) {
        return SYSTEM_ERROR;
    }
    char filename[NAME_MAX];
    int n = encode_key(filename, uid, keyPrefix);
    send_code(sock, NO_ERROR);

    struct dirent* file;
    while ((file = readdir(dir)) != NULL) {
        if (!strncmp(filename, file->d_name, n)) {
            char* p = &file->d_name[n];
            keyPrefix->length = decode_key(keyPrefix->value, p, strlen(p));
            send_message(sock, keyPrefix->value, keyPrefix->length);
        }
    }
    closedir(dir);
    return NO_ERROR_RESPONSE_CODE_SENT;
}

static ResponseCode reset(KeyStore* keyStore, int sock, uid_t uid, Value*, Value*) {
    return keyStore->reset() ? NO_ERROR : SYSTEM_ERROR;
}

/* Here is the history. To improve the security, the parameters to generate the
 * master key has been changed. To make a seamless transition, we update the
 * file using the same password when the user unlock it for the first time. If
 * any thing goes wrong during the transition, the new file will not overwrite
 * the old one. This avoids permanent damages of the existing data. */

static ResponseCode password(KeyStore* keyStore, int sock, uid_t uid, Value* pw, Value*) {
    switch (keyStore->getState()) {
        case STATE_UNINITIALIZED: {
            // generate master key, encrypt with password, write to file, initialize mMasterKey*.
            return keyStore->initialize(pw);
        }
        case STATE_NO_ERROR: {
            // rewrite master key with new password.
            return keyStore->writeMasterKey(pw);
        }
        case STATE_LOCKED: {
            // read master key, decrypt with password, initialize mMasterKey*.
            return keyStore->readMasterKey(pw);
        }
    }
    return SYSTEM_ERROR;
}

static ResponseCode lock(KeyStore* keyStore, int sock, uid_t uid, Value*, Value*) {
    keyStore->lock();
    return NO_ERROR;
}

static ResponseCode unlock(KeyStore* keyStore, int sock, uid_t uid, Value* pw, Value* unused) {
    return password(keyStore, sock, uid, pw, unused);
}

static ResponseCode zero(KeyStore* keyStore, int sock, uid_t uid, Value*, Value*) {
    return keyStore->isEmpty() ? KEY_NOT_FOUND : NO_ERROR;
}

/* Here are the permissions, actions, users, and the main function. */

enum perm {
    TEST     =    1,
    GET      =    2,
    INSERT   =    4,
    DELETE   =    8,
    EXIST    =   16,
    SAW      =   32,
    RESET    =   64,
    PASSWORD =  128,
    LOCK     =  256,
    UNLOCK   =  512,
    ZERO     = 1024,
};

static const int MAX_PARAM = 2;

static const State STATE_ANY = (State) 0;

static struct action {
    ResponseCode (*run)(KeyStore* keyStore, int sock, uid_t uid, Value* param1, Value* param2);
    int8_t code;
    State state;
    uint32_t perm;
    int lengths[MAX_PARAM];
} actions[] = {
    {test,     't', STATE_ANY,      TEST,     {0, 0}},
    {get,      'g', STATE_NO_ERROR, GET,      {KEY_SIZE, 0}},
    {insert,   'i', STATE_NO_ERROR, INSERT,   {KEY_SIZE, VALUE_SIZE}},
    {del,      'd', STATE_ANY,      DELETE,   {KEY_SIZE, 0}},
    {exist,    'e', STATE_ANY,      EXIST,    {KEY_SIZE, 0}},
    {saw,      's', STATE_ANY,      SAW,      {KEY_SIZE, 0}},
    {reset,    'r', STATE_ANY,      RESET,    {0, 0}},
    {password, 'p', STATE_ANY,      PASSWORD, {PASSWORD_SIZE, 0}},
    {lock,     'l', STATE_NO_ERROR, LOCK,     {0, 0}},
    {unlock,   'u', STATE_LOCKED,   UNLOCK,   {PASSWORD_SIZE, 0}},
    {zero,     'z', STATE_ANY,      ZERO,     {0, 0}},
    {NULL,      0 , STATE_ANY,      0,        {0, 0}},
};

static struct user {
    uid_t uid;
    uid_t euid;
    uint32_t perms;
} users[] = {
    {AID_SYSTEM,   ~0,         ~0},
    {AID_VPN,      AID_SYSTEM, GET},
    {AID_WIFI,     AID_SYSTEM, GET},
    {AID_ROOT,     AID_SYSTEM, GET},
    {~0,           ~0,         TEST | GET | INSERT | DELETE | EXIST | SAW},
};

static ResponseCode process(KeyStore* keyStore, int sock, uid_t uid, int8_t code) {
    struct user* user = users;
    struct action* action = actions;
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
    if (action->state != STATE_ANY && action->state != keyStore->getState()) {
        return (ResponseCode) keyStore->getState();
    }
    if (~user->euid) {
        uid = user->euid;
    }
    Value params[MAX_PARAM];
    for (i = 0; i < MAX_PARAM && action->lengths[i] != 0; ++i) {
        params[i].length = recv_message(sock, params[i].value, action->lengths[i]);
        if (params[i].length < 0) {
            return PROTOCOL_ERROR;
        }
    }
    if (!recv_end_of_file(sock)) {
        return PROTOCOL_ERROR;
    }
    return action->run(keyStore, sock, uid, &params[0], &params[1]);
}

int main(int argc, char* argv[]) {
    int controlSocket = android_get_control_socket("keystore");
    if (argc < 2) {
        ALOGE("A directory must be specified!");
        return 1;
    }
    if (chdir(argv[1]) == -1) {
        ALOGE("chdir: %s: %s", argv[1], strerror(errno));
        return 1;
    }

    Entropy entropy;
    if (!entropy.open()) {
        return 1;
    }
    if (listen(controlSocket, 3) == -1) {
        ALOGE("listen: %s", strerror(errno));
        return 1;
    }

    signal(SIGPIPE, SIG_IGN);

    KeyStore keyStore(&entropy);
    int sock;
    while ((sock = accept(controlSocket, NULL, 0)) != -1) {
        struct timeval tv;
        tv.tv_sec = 3;
        setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
        setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));

        struct ucred cred;
        socklen_t size = sizeof(cred);
        int credResult = getsockopt(sock, SOL_SOCKET, SO_PEERCRED, &cred, &size);
        if (credResult != 0) {
            ALOGW("getsockopt: %s", strerror(errno));
        } else {
            int8_t request;
            if (recv_code(sock, &request)) {
                State old_state = keyStore.getState();
                ResponseCode response = process(&keyStore, sock, cred.uid, request);
                if (response == NO_ERROR_RESPONSE_CODE_SENT) {
                    response = NO_ERROR;
                } else {
                    send_code(sock, response);
                }
                ALOGI("uid: %d action: %c -> %d state: %d -> %d retry: %d",
                     cred.uid,
                     request, response,
                     old_state, keyStore.getState(),
                     keyStore.getRetry());
            }
        }
        close(sock);
    }
    ALOGE("accept: %s", strerror(errno));
    return 1;
}
