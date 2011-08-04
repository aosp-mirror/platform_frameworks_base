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

#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <openssl/aes.h>
#include <openssl/hmac.h>

#include "FwdLockFile.h"
#include "FwdLockGlue.h"

#define TRUE 1
#define FALSE 0

#define INVALID_OFFSET ((off64_t)-1)

#define INVALID_BLOCK_INDEX ((uint64_t)-1)

#define MAX_NUM_SESSIONS 128

#define KEY_SIZE AES_BLOCK_SIZE
#define KEY_SIZE_IN_BITS (KEY_SIZE * 8)

#define SHA1_HASH_SIZE 20
#define SHA1_BLOCK_SIZE 64

#define FWD_LOCK_VERSION 0
#define FWD_LOCK_SUBFORMAT 0
#define USAGE_RESTRICTION_FLAGS 0
#define CONTENT_TYPE_LENGTH_POS 7
#define TOP_HEADER_SIZE 8

#define SIG_CALC_BUFFER_SIZE (16 * SHA1_BLOCK_SIZE)

/**
 * Data type for the per-file state information needed by the decoder.
 */
typedef struct FwdLockFile_Session {
    int fileDesc;
    unsigned char topHeader[TOP_HEADER_SIZE];
    char *pContentType;
    size_t contentTypeLength;
    void *pEncryptedSessionKey;
    size_t encryptedSessionKeyLength;
    unsigned char dataSignature[SHA1_HASH_SIZE];
    unsigned char headerSignature[SHA1_HASH_SIZE];
    off64_t dataOffset;
    off64_t filePos;
    AES_KEY encryptionRoundKeys;
    HMAC_CTX signingContext;
    unsigned char keyStream[AES_BLOCK_SIZE];
    uint64_t blockIndex;
} FwdLockFile_Session_t;

static FwdLockFile_Session_t *sessionPtrs[MAX_NUM_SESSIONS] = { NULL };

static pthread_mutex_t sessionAcquisitionMutex = PTHREAD_MUTEX_INITIALIZER;

static const unsigned char topHeaderTemplate[] =
    { 'F', 'W', 'L', 'K', FWD_LOCK_VERSION, FWD_LOCK_SUBFORMAT, USAGE_RESTRICTION_FLAGS };

/**
 * Acquires an unused file session for the given file descriptor.
 *
 * @param[in] fileDesc A file descriptor.
 *
 * @return A session ID.
 */
static int FwdLockFile_AcquireSession(int fileDesc) {
    int sessionId = -1;
    if (fileDesc < 0) {
        errno = EBADF;
    } else {
        int i;
        pthread_mutex_lock(&sessionAcquisitionMutex);
        for (i = 0; i < MAX_NUM_SESSIONS; ++i) {
            int candidateSessionId = (fileDesc + i) % MAX_NUM_SESSIONS;
            if (sessionPtrs[candidateSessionId] == NULL) {
                sessionPtrs[candidateSessionId] = malloc(sizeof **sessionPtrs);
                if (sessionPtrs[candidateSessionId] != NULL) {
                    sessionPtrs[candidateSessionId]->fileDesc = fileDesc;
                    sessionPtrs[candidateSessionId]->pContentType = NULL;
                    sessionPtrs[candidateSessionId]->pEncryptedSessionKey = NULL;
                    sessionId = candidateSessionId;
                }
                break;
            }
        }
        pthread_mutex_unlock(&sessionAcquisitionMutex);
        if (i == MAX_NUM_SESSIONS) {
            errno = ENFILE;
        }
    }
    return sessionId;
}

/**
 * Finds the file session associated with the given file descriptor.
 *
 * @param[in] fileDesc A file descriptor.
 *
 * @return A session ID.
 */
static int FwdLockFile_FindSession(int fileDesc) {
    int sessionId = -1;
    if (fileDesc < 0) {
        errno = EBADF;
    } else {
        int i;
        pthread_mutex_lock(&sessionAcquisitionMutex);
        for (i = 0; i < MAX_NUM_SESSIONS; ++i) {
            int candidateSessionId = (fileDesc + i) % MAX_NUM_SESSIONS;
            if (sessionPtrs[candidateSessionId] != NULL &&
                sessionPtrs[candidateSessionId]->fileDesc == fileDesc) {
                sessionId = candidateSessionId;
                break;
            }
        }
        pthread_mutex_unlock(&sessionAcquisitionMutex);
        if (i == MAX_NUM_SESSIONS) {
            errno = EBADF;
        }
    }
    return sessionId;
}

/**
 * Releases a file session.
 *
 * @param[in] sessionID A session ID.
 */
static void FwdLockFile_ReleaseSession(int sessionId) {
    pthread_mutex_lock(&sessionAcquisitionMutex);
    assert(0 <= sessionId && sessionId < MAX_NUM_SESSIONS && sessionPtrs[sessionId] != NULL);
    free(sessionPtrs[sessionId]->pContentType);
    free(sessionPtrs[sessionId]->pEncryptedSessionKey);
    memset(sessionPtrs[sessionId], 0, sizeof *sessionPtrs[sessionId]); // Zero out key data.
    free(sessionPtrs[sessionId]);
    sessionPtrs[sessionId] = NULL;
    pthread_mutex_unlock(&sessionAcquisitionMutex);
}

/**
 * Derives keys for encryption and signing from the encrypted session key.
 *
 * @param[in,out] pSession A reference to a file session.
 *
 * @return A Boolean value indicating whether key derivation was successful.
 */
static int FwdLockFile_DeriveKeys(FwdLockFile_Session_t * pSession) {
    int result;
    struct FwdLockFile_DeriveKeys_Data {
        AES_KEY sessionRoundKeys;
        unsigned char value[KEY_SIZE];
        unsigned char key[KEY_SIZE];
    } *pData = malloc(sizeof *pData);
    if (pData == NULL) {
        result = FALSE;
    } else {
        result = FwdLockGlue_DecryptKey(pSession->pEncryptedSessionKey,
                                        pSession->encryptedSessionKeyLength, pData->key, KEY_SIZE);
        if (result) {
            if (AES_set_encrypt_key(pData->key, KEY_SIZE_IN_BITS, &pData->sessionRoundKeys) != 0) {
                result = FALSE;
            } else {
                // Encrypt the 16-byte value {0, 0, ..., 0} to produce the encryption key.
                memset(pData->value, 0, KEY_SIZE);
                AES_encrypt(pData->value, pData->key, &pData->sessionRoundKeys);
                if (AES_set_encrypt_key(pData->key, KEY_SIZE_IN_BITS,
                                        &pSession->encryptionRoundKeys) != 0) {
                    result = FALSE;
                } else {
                    // Encrypt the 16-byte value {1, 0, ..., 0} to produce the signing key.
                    ++pData->value[0];
                    AES_encrypt(pData->value, pData->key, &pData->sessionRoundKeys);
                    HMAC_CTX_init(&pSession->signingContext);
                    HMAC_Init_ex(&pSession->signingContext, pData->key, KEY_SIZE, EVP_sha1(), NULL);
                }
            }
        }
        if (!result) {
            errno = ENOSYS;
        }
        memset(pData, 0, sizeof pData); // Zero out key data.
        free(pData);
    }
    return result;
}

/**
 * Calculates the counter, treated as a 16-byte little-endian number, used to generate the keystream
 * for the given block.
 *
 * @param[in] pNonce A reference to the nonce.
 * @param[in] blockIndex The index number of the block.
 * @param[out] pCounter A reference to the counter.
 */
static void FwdLockFile_CalculateCounter(const unsigned char *pNonce,
                                         uint64_t blockIndex,
                                         unsigned char *pCounter) {
    unsigned char carry = 0;
    size_t i = 0;
    for (; i < sizeof blockIndex; ++i) {
        unsigned char part = pNonce[i] + (unsigned char)(blockIndex >> (i * CHAR_BIT));
        pCounter[i] = part + carry;
        carry = (part < pNonce[i] || pCounter[i] < part) ? 1 : 0;
    }
    for (; i < AES_BLOCK_SIZE; ++i) {
        pCounter[i] = pNonce[i] + carry;
        carry = (pCounter[i] < pNonce[i]) ? 1 : 0;
    }
}

/**
 * Decrypts the byte at the current file position using AES-128-CTR. In CTR (or "counter") mode,
 * encryption and decryption are performed using the same algorithm.
 *
 * @param[in,out] pSession A reference to a file session.
 * @param[in] pByte The byte to decrypt.
 */
void FwdLockFile_DecryptByte(FwdLockFile_Session_t * pSession, unsigned char *pByte) {
    uint64_t blockIndex = pSession->filePos / AES_BLOCK_SIZE;
    uint64_t blockOffset = pSession->filePos % AES_BLOCK_SIZE;
    if (blockIndex != pSession->blockIndex) {
        // The first 16 bytes of the encrypted session key is used as the nonce.
        unsigned char counter[AES_BLOCK_SIZE];
        FwdLockFile_CalculateCounter(pSession->pEncryptedSessionKey, blockIndex, counter);
        AES_encrypt(counter, pSession->keyStream, &pSession->encryptionRoundKeys);
        pSession->blockIndex = blockIndex;
    }
    *pByte ^= pSession->keyStream[blockOffset];
}

int FwdLockFile_attach(int fileDesc) {
    int sessionId = FwdLockFile_AcquireSession(fileDesc);
    if (sessionId >= 0) {
        FwdLockFile_Session_t *pSession = sessionPtrs[sessionId];
        int isSuccess = FALSE;
        if (read(fileDesc, pSession->topHeader, TOP_HEADER_SIZE) == TOP_HEADER_SIZE &&
                memcmp(pSession->topHeader, topHeaderTemplate, sizeof topHeaderTemplate) == 0) {
            pSession->contentTypeLength = pSession->topHeader[CONTENT_TYPE_LENGTH_POS];
            assert(pSession->contentTypeLength <= UCHAR_MAX); // Untaint scalar for code checkers.
            pSession->pContentType = malloc(pSession->contentTypeLength + 1);
            if (pSession->pContentType != NULL &&
                    read(fileDesc, pSession->pContentType, pSession->contentTypeLength) ==
                            (ssize_t)pSession->contentTypeLength) {
                pSession->pContentType[pSession->contentTypeLength] = '\0';
                pSession->encryptedSessionKeyLength = FwdLockGlue_GetEncryptedKeyLength(KEY_SIZE);
                pSession->pEncryptedSessionKey = malloc(pSession->encryptedSessionKeyLength);
                if (pSession->pEncryptedSessionKey != NULL &&
                        read(fileDesc, pSession->pEncryptedSessionKey,
                             pSession->encryptedSessionKeyLength) ==
                                (ssize_t)pSession->encryptedSessionKeyLength &&
                        read(fileDesc, pSession->dataSignature, SHA1_HASH_SIZE) ==
                                SHA1_HASH_SIZE &&
                        read(fileDesc, pSession->headerSignature, SHA1_HASH_SIZE) ==
                                SHA1_HASH_SIZE) {
                    isSuccess = FwdLockFile_DeriveKeys(pSession);
                }
            }
        }
        if (isSuccess) {
            pSession->dataOffset = pSession->contentTypeLength +
                    pSession->encryptedSessionKeyLength + TOP_HEADER_SIZE + 2 * SHA1_HASH_SIZE;
            pSession->filePos = 0;
            pSession->blockIndex = INVALID_BLOCK_INDEX;
        } else {
            FwdLockFile_ReleaseSession(sessionId);
            sessionId = -1;
        }
    }
    return (sessionId >= 0) ? 0 : -1;
}

int FwdLockFile_open(const char *pFilename) {
    int fileDesc = open(pFilename, O_RDONLY);
    if (fileDesc >= 0 && FwdLockFile_attach(fileDesc) < 0) {
        (void)close(fileDesc);
        fileDesc = -1;
    }
    return fileDesc;
}

ssize_t FwdLockFile_read(int fileDesc, void *pBuffer, size_t numBytes) {
    ssize_t numBytesRead;
    int sessionId = FwdLockFile_FindSession(fileDesc);
    if (sessionId < 0) {
        numBytesRead = -1;
    } else {
        FwdLockFile_Session_t *pSession = sessionPtrs[sessionId];
        ssize_t i;
        numBytesRead = read(pSession->fileDesc, pBuffer, numBytes);
        for (i = 0; i < numBytesRead; ++i) {
            FwdLockFile_DecryptByte(pSession, &((unsigned char *)pBuffer)[i]);
            ++pSession->filePos;
        }
    }
    return numBytesRead;
}

off64_t FwdLockFile_lseek(int fileDesc, off64_t offset, int whence) {
    off64_t newFilePos;
    int sessionId = FwdLockFile_FindSession(fileDesc);
    if (sessionId < 0) {
        newFilePos = INVALID_OFFSET;
    } else {
        FwdLockFile_Session_t *pSession = sessionPtrs[sessionId];
        switch (whence) {
        case SEEK_SET:
            newFilePos = lseek64(pSession->fileDesc, pSession->dataOffset + offset, whence);
            break;
        case SEEK_CUR:
        case SEEK_END:
            newFilePos = lseek64(pSession->fileDesc, offset, whence);
            break;
        default:
            errno = EINVAL;
            newFilePos = INVALID_OFFSET;
            break;
        }
        if (newFilePos != INVALID_OFFSET) {
            if (newFilePos < pSession->dataOffset) {
                // The new file position is illegal for an internal Forward Lock file. Restore the
                // original file position.
                (void)lseek64(pSession->fileDesc, pSession->dataOffset + pSession->filePos,
                              SEEK_SET);
                errno = EINVAL;
                newFilePos = INVALID_OFFSET;
            } else {
                // The return value should be the file position that lseek64() would have returned
                // for the embedded content file.
                pSession->filePos = newFilePos - pSession->dataOffset;
                newFilePos = pSession->filePos;
            }
        }
    }
    return newFilePos;
}

int FwdLockFile_detach(int fileDesc) {
    int sessionId = FwdLockFile_FindSession(fileDesc);
    if (sessionId < 0) {
        return -1;
    }
    HMAC_CTX_cleanup(&sessionPtrs[sessionId]->signingContext);
    FwdLockFile_ReleaseSession(sessionId);
    return 0;
}

int FwdLockFile_close(int fileDesc) {
    return (FwdLockFile_detach(fileDesc) == 0) ? close(fileDesc) : -1;
}

int FwdLockFile_CheckDataIntegrity(int fileDesc) {
    int result;
    int sessionId = FwdLockFile_FindSession(fileDesc);
    if (sessionId < 0) {
        result = FALSE;
    } else {
        struct FwdLockFile_CheckDataIntegrity_Data {
            unsigned char signature[SHA1_HASH_SIZE];
            unsigned char buffer[SIG_CALC_BUFFER_SIZE];
        } *pData = malloc(sizeof *pData);
        if (pData == NULL) {
            result = FALSE;
        } else {
            FwdLockFile_Session_t *pSession = sessionPtrs[sessionId];
            if (lseek64(pSession->fileDesc, pSession->dataOffset, SEEK_SET) !=
                    pSession->dataOffset) {
                result = FALSE;
            } else {
                ssize_t numBytesRead;
                unsigned int signatureSize = SHA1_HASH_SIZE;
                while ((numBytesRead =
                        read(pSession->fileDesc, pData->buffer, SIG_CALC_BUFFER_SIZE)) > 0) {
                    HMAC_Update(&pSession->signingContext, pData->buffer, (size_t)numBytesRead);
                }
                if (numBytesRead < 0) {
                    result = FALSE;
                } else {
                    HMAC_Final(&pSession->signingContext, pData->signature, &signatureSize);
                    assert(signatureSize == SHA1_HASH_SIZE);
                    result = memcmp(pData->signature, pSession->dataSignature, SHA1_HASH_SIZE) == 0;
                }
                HMAC_Init_ex(&pSession->signingContext, NULL, KEY_SIZE, NULL, NULL);
                (void)lseek64(pSession->fileDesc, pSession->dataOffset + pSession->filePos,
                              SEEK_SET);
            }
            free(pData);
        }
    }
    return result;
}

int FwdLockFile_CheckHeaderIntegrity(int fileDesc) {
    int result;
    int sessionId = FwdLockFile_FindSession(fileDesc);
    if (sessionId < 0) {
        result = FALSE;
    } else {
        FwdLockFile_Session_t *pSession = sessionPtrs[sessionId];
        unsigned char signature[SHA1_HASH_SIZE];
        unsigned int signatureSize = SHA1_HASH_SIZE;
        HMAC_Update(&pSession->signingContext, pSession->topHeader, TOP_HEADER_SIZE);
        HMAC_Update(&pSession->signingContext, (unsigned char *)pSession->pContentType,
                    pSession->contentTypeLength);
        HMAC_Update(&pSession->signingContext, pSession->pEncryptedSessionKey,
                    pSession->encryptedSessionKeyLength);
        HMAC_Update(&pSession->signingContext, pSession->dataSignature, SHA1_HASH_SIZE);
        HMAC_Final(&pSession->signingContext, signature, &signatureSize);
        assert(signatureSize == SHA1_HASH_SIZE);
        result = memcmp(signature, pSession->headerSignature, SHA1_HASH_SIZE) == 0;
        HMAC_Init_ex(&pSession->signingContext, NULL, KEY_SIZE, NULL, NULL);
    }
    return result;
}

int FwdLockFile_CheckIntegrity(int fileDesc) {
    return FwdLockFile_CheckHeaderIntegrity(fileDesc) && FwdLockFile_CheckDataIntegrity(fileDesc);
}

const char *FwdLockFile_GetContentType(int fileDesc) {
    int sessionId = FwdLockFile_FindSession(fileDesc);
    if (sessionId < 0) {
        return NULL;
    }
    return sessionPtrs[sessionId]->pContentType;
}
