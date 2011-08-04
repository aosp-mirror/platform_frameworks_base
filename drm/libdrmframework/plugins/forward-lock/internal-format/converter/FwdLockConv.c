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
#include <ctype.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include <openssl/aes.h>
#include <openssl/hmac.h>

#include "FwdLockConv.h"
#include "FwdLockGlue.h"

#define TRUE 1
#define FALSE 0

#define INVALID_OFFSET ((off64_t)-1)

#define MAX_NUM_SESSIONS 32

#define OUTPUT_BUFFER_SIZE_INCREMENT 1024
#define READ_BUFFER_SIZE 1024

#define MAX_BOUNDARY_LENGTH 70
#define MAX_DELIMITER_LENGTH (MAX_BOUNDARY_LENGTH + 4)

#define STRING_LENGTH_INCREMENT 25

#define KEY_SIZE AES_BLOCK_SIZE
#define KEY_SIZE_IN_BITS (KEY_SIZE * 8)

#define SHA1_HASH_SIZE 20

#define FWD_LOCK_VERSION 0
#define FWD_LOCK_SUBFORMAT 0
#define USAGE_RESTRICTION_FLAGS 0
#define CONTENT_TYPE_LENGTH_POS 7
#define TOP_HEADER_SIZE 8

/**
 * Data type for the parser states of the converter.
 */
typedef enum FwdLockConv_ParserState {
    FwdLockConv_ParserState_WantsOpenDelimiter,
    FwdLockConv_ParserState_WantsMimeHeaders,
    FwdLockConv_ParserState_WantsBinaryEncodedData,
    FwdLockConv_ParserState_WantsBase64EncodedData,
    FwdLockConv_ParserState_Done
} FwdLockConv_ParserState_t;

/**
 * Data type for the scanner states of the converter.
 */
typedef enum FwdLockConv_ScannerState {
    FwdLockConv_ScannerState_WantsFirstDash,
    FwdLockConv_ScannerState_WantsSecondDash,
    FwdLockConv_ScannerState_WantsCR,
    FwdLockConv_ScannerState_WantsLF,
    FwdLockConv_ScannerState_WantsBoundary,
    FwdLockConv_ScannerState_WantsBoundaryEnd,
    FwdLockConv_ScannerState_WantsMimeHeaderNameStart,
    FwdLockConv_ScannerState_WantsMimeHeaderName,
    FwdLockConv_ScannerState_WantsMimeHeaderNameEnd,
    FwdLockConv_ScannerState_WantsContentTypeStart,
    FwdLockConv_ScannerState_WantsContentType,
    FwdLockConv_ScannerState_WantsContentTransferEncodingStart,
    FwdLockConv_ScannerState_Wants_A_OR_I,
    FwdLockConv_ScannerState_Wants_N,
    FwdLockConv_ScannerState_Wants_A,
    FwdLockConv_ScannerState_Wants_R,
    FwdLockConv_ScannerState_Wants_Y,
    FwdLockConv_ScannerState_Wants_S,
    FwdLockConv_ScannerState_Wants_E,
    FwdLockConv_ScannerState_Wants_6,
    FwdLockConv_ScannerState_Wants_4,
    FwdLockConv_ScannerState_Wants_B,
    FwdLockConv_ScannerState_Wants_I,
    FwdLockConv_ScannerState_Wants_T,
    FwdLockConv_ScannerState_WantsContentTransferEncodingEnd,
    FwdLockConv_ScannerState_WantsMimeHeaderValueEnd,
    FwdLockConv_ScannerState_WantsMimeHeadersEnd,
    FwdLockConv_ScannerState_WantsByte1,
    FwdLockConv_ScannerState_WantsByte1_AfterCRLF,
    FwdLockConv_ScannerState_WantsByte2,
    FwdLockConv_ScannerState_WantsByte3,
    FwdLockConv_ScannerState_WantsByte4,
    FwdLockConv_ScannerState_WantsPadding,
    FwdLockConv_ScannerState_WantsWhitespace,
    FwdLockConv_ScannerState_WantsWhitespace_AfterCRLF,
    FwdLockConv_ScannerState_WantsDelimiter
} FwdLockConv_ScannerState_t;

/**
 * Data type for the content transfer encoding.
 */
typedef enum FwdLockConv_ContentTransferEncoding {
    FwdLockConv_ContentTransferEncoding_Undefined,
    FwdLockConv_ContentTransferEncoding_Binary,
    FwdLockConv_ContentTransferEncoding_Base64
} FwdLockConv_ContentTransferEncoding_t;

/**
 * Data type for a dynamically growing string.
 */
typedef struct FwdLockConv_String {
    char *ptr;
    size_t length;
    size_t maxLength;
    size_t lengthIncrement;
} FwdLockConv_String_t;

/**
 * Data type for the per-file state information needed by the converter.
 */
typedef struct FwdLockConv_Session {
    FwdLockConv_ParserState_t parserState;
    FwdLockConv_ScannerState_t scannerState;
    FwdLockConv_ScannerState_t savedScannerState;
    off64_t numCharsConsumed;
    char delimiter[MAX_DELIMITER_LENGTH];
    size_t delimiterLength;
    size_t delimiterMatchPos;
    FwdLockConv_String_t mimeHeaderName;
    FwdLockConv_String_t contentType;
    FwdLockConv_ContentTransferEncoding_t contentTransferEncoding;
    unsigned char sessionKey[KEY_SIZE];
    void *pEncryptedSessionKey;
    size_t encryptedSessionKeyLength;
    AES_KEY encryptionRoundKeys;
    HMAC_CTX signingContext;
    unsigned char topHeader[TOP_HEADER_SIZE];
    unsigned char counter[AES_BLOCK_SIZE];
    unsigned char keyStream[AES_BLOCK_SIZE];
    int keyStreamIndex;
    unsigned char ch;
    size_t outputBufferSize;
    size_t dataOffset;
    size_t numDataBytes;
} FwdLockConv_Session_t;

static FwdLockConv_Session_t *sessionPtrs[MAX_NUM_SESSIONS] = { NULL };

static pthread_mutex_t sessionAcquisitionMutex = PTHREAD_MUTEX_INITIALIZER;

static const FwdLockConv_String_t nullString = { NULL, 0, 0, STRING_LENGTH_INCREMENT };

static const unsigned char topHeaderTemplate[] =
    { 'F', 'W', 'L', 'K', FWD_LOCK_VERSION, FWD_LOCK_SUBFORMAT, USAGE_RESTRICTION_FLAGS };

static const char strContent[] = "content-";
static const char strType[] = "type";
static const char strTransferEncoding[] = "transfer-encoding";
static const char strTextPlain[] = "text/plain";
static const char strApplicationVndOmaDrmRightsXml[] = "application/vnd.oma.drm.rights+xml";
static const char strApplicationVndOmaDrmContent[] = "application/vnd.oma.drm.content";

static const size_t strlenContent = sizeof strContent - 1;
static const size_t strlenTextPlain = sizeof strTextPlain - 1;

static const signed char base64Values[] = {
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 62, -1, -1, -1, 63,
    52, 53, 54, 55, 56, 57, 58, 59, 60, 61, -1, -1, -1, -2, -1, -1,
    -1,  0,  1,  2,  3,  4,  5,  6,  7,  8,  9, 10, 11, 12, 13, 14,
    15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, -1, -1, -1, -1, -1,
    -1, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
    41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51
};

/**
 * Acquires an unused converter session.
 *
 * @return A session ID.
 */
static int FwdLockConv_AcquireSession() {
    int sessionId = -1;
    int i;
    pthread_mutex_lock(&sessionAcquisitionMutex);
    for (i = 0; i < MAX_NUM_SESSIONS; ++i) {
        if (sessionPtrs[i] == NULL) {
            sessionPtrs[i] = malloc(sizeof *sessionPtrs[i]);
            if (sessionPtrs[i] != NULL) {
                sessionId = i;
            }
            break;
        }
    }
    pthread_mutex_unlock(&sessionAcquisitionMutex);
    return sessionId;
}

/**
 * Checks whether a session ID is in range and currently in use.
 *
 * @param[in] sessionID A session ID.
 *
 * @return A Boolean value indicating whether the session ID is in range and currently in use.
 */
static int FwdLockConv_IsValidSession(int sessionId) {
    return 0 <= sessionId && sessionId < MAX_NUM_SESSIONS && sessionPtrs[sessionId] != NULL;
}

/**
 * Releases a converter session.
 *
 * @param[in] sessionID A session ID.
 */
static void FwdLockConv_ReleaseSession(int sessionId) {
    pthread_mutex_lock(&sessionAcquisitionMutex);
    assert(FwdLockConv_IsValidSession(sessionId));
    memset(sessionPtrs[sessionId], 0, sizeof *sessionPtrs[sessionId]); // Zero out key data.
    free(sessionPtrs[sessionId]);
    sessionPtrs[sessionId] = NULL;
    pthread_mutex_unlock(&sessionAcquisitionMutex);
}

/**
 * Derives cryptographically independent keys for encryption and signing from the session key.
 *
 * @param[in,out] pSession A reference to a converter session.
 *
 * @return A status code.
 */
static int FwdLockConv_DeriveKeys(FwdLockConv_Session_t *pSession) {
    FwdLockConv_Status_t status;
    struct FwdLockConv_DeriveKeys_Data {
        AES_KEY sessionRoundKeys;
        unsigned char value[KEY_SIZE];
        unsigned char key[KEY_SIZE];
    } *pData = malloc(sizeof *pData);
    if (pData == NULL) {
        status = FwdLockConv_Status_OutOfMemory;
    } else {
        if (AES_set_encrypt_key(pSession->sessionKey, KEY_SIZE_IN_BITS,
                                &pData->sessionRoundKeys) != 0) {
            status = FwdLockConv_Status_ProgramError;
        } else {
            // Encrypt the 16-byte value {0, 0, ..., 0} to produce the encryption key.
            memset(pData->value, 0, KEY_SIZE);
            AES_encrypt(pData->value, pData->key, &pData->sessionRoundKeys);
            if (AES_set_encrypt_key(pData->key, KEY_SIZE_IN_BITS,
                                    &pSession->encryptionRoundKeys) != 0) {
                status = FwdLockConv_Status_ProgramError;
            } else {
                // Encrypt the 16-byte value {1, 0, ..., 0} to produce the signing key.
                ++pData->value[0];
                AES_encrypt(pData->value, pData->key, &pData->sessionRoundKeys);
                HMAC_CTX_init(&pSession->signingContext);
                HMAC_Init_ex(&pSession->signingContext, pData->key, KEY_SIZE, EVP_sha1(), NULL);
                status = FwdLockConv_Status_OK;
            }
        }
        memset(pData, 0, sizeof pData); // Zero out key data.
        free(pData);
    }
    return status;
}

/**
 * Checks whether a given character is valid in a boundary. Allows some non-standard characters that
 * are invalid according to RFC 2046 but nevertheless used by one vendor's DRM packager. Note that
 * the boundary may contain leading and internal spaces.
 *
 * @param[in] ch The character to check.
 *
 * @return A Boolean value indicating whether the given character is valid in a boundary.
 */
static int FwdLockConv_IsBoundaryChar(int ch) {
    return isalnum(ch) || ch == '\'' || ch == '(' || ch == ')' || ch == '+' || ch == '_' ||
            ch == ',' || ch == '-' || ch == '.' || ch == '/' || ch == ':' || ch == '=' ||
            ch == '?' || ch == ' ' || ch == '%' || ch == '[' || ch == '&' || ch == '*' || ch == '^';
}

/**
 * Checks whether a given character should be considered whitespace, using a narrower definition
 * than the standard-library isspace() function.
 *
 * @param[in] ch The character to check.
 *
 * @return A Boolean value indicating whether the given character should be considered whitespace.
 */
static int FwdLockConv_IsWhitespace(int ch) {
    return ch == ' ' || ch == '\t';
}

/**
 * Removes trailing spaces from the delimiter.
 *
 * @param[in,out] pSession A reference to a converter session.
 *
 * @return A status code.
 */
static FwdLockConv_Status_t FwdLockConv_RightTrimDelimiter(FwdLockConv_Session_t *pSession) {
    while (pSession->delimiterLength > 4 &&
           pSession->delimiter[pSession->delimiterLength - 1] == ' ') {
        --pSession->delimiterLength;
    }
    if (pSession->delimiterLength > 4) {
        return FwdLockConv_Status_OK;
    }
    return FwdLockConv_Status_SyntaxError;
}

/**
 * Matches the open delimiter.
 *
 * @param[in,out] pSession A reference to a converter session.
 * @param[in] ch A character.
 *
 * @return A status code.
 */
static FwdLockConv_Status_t FwdLockConv_MatchOpenDelimiter(FwdLockConv_Session_t *pSession,
                                                           int ch) {
    FwdLockConv_Status_t status = FwdLockConv_Status_OK;
    switch (pSession->scannerState) {
    case FwdLockConv_ScannerState_WantsFirstDash:
        if (ch == '-') {
            pSession->scannerState = FwdLockConv_ScannerState_WantsSecondDash;
        } else if (ch == '\r') {
            pSession->scannerState = FwdLockConv_ScannerState_WantsLF;
        } else {
            pSession->scannerState = FwdLockConv_ScannerState_WantsCR;
        }
        break;
    case FwdLockConv_ScannerState_WantsSecondDash:
        if (ch == '-') {
            // The delimiter starts with "\r\n--" (the open delimiter may omit the initial "\r\n").
            // The rest is the user-defined boundary that should come next.
            pSession->delimiter[0] = '\r';
            pSession->delimiter[1] = '\n';
            pSession->delimiter[2] = '-';
            pSession->delimiter[3] = '-';
            pSession->delimiterLength = 4;
            pSession->scannerState = FwdLockConv_ScannerState_WantsBoundary;
        } else if (ch == '\r') {
            pSession->scannerState = FwdLockConv_ScannerState_WantsLF;
        } else {
            pSession->scannerState = FwdLockConv_ScannerState_WantsCR;
        }
        break;
    case FwdLockConv_ScannerState_WantsCR:
        if (ch == '\r') {
            pSession->scannerState = FwdLockConv_ScannerState_WantsLF;
        }
        break;
    case FwdLockConv_ScannerState_WantsLF:
        if (ch == '\n') {
            pSession->scannerState = FwdLockConv_ScannerState_WantsFirstDash;
        } else if (ch != '\r') {
            pSession->scannerState = FwdLockConv_ScannerState_WantsCR;
        }
        break;
    case FwdLockConv_ScannerState_WantsBoundary:
        if (FwdLockConv_IsBoundaryChar(ch)) {
            // The boundary may contain leading and internal spaces, so trailing spaces will also be
            // matched here. These will be removed later.
            if (pSession->delimiterLength < MAX_DELIMITER_LENGTH) {
                pSession->delimiter[pSession->delimiterLength++] = ch;
            } else if (ch != ' ') {
                status = FwdLockConv_Status_SyntaxError;
            }
        } else if (ch == '\r') {
            status = FwdLockConv_RightTrimDelimiter(pSession);
            if (status == FwdLockConv_Status_OK) {
                pSession->scannerState = FwdLockConv_ScannerState_WantsBoundaryEnd;
            }
        } else if (ch == '\t') {
            status = FwdLockConv_RightTrimDelimiter(pSession);
            if (status == FwdLockConv_Status_OK) {
                pSession->scannerState = FwdLockConv_ScannerState_WantsWhitespace;
            }
        } else {
            status = FwdLockConv_Status_SyntaxError;
        }
        break;
    case FwdLockConv_ScannerState_WantsWhitespace:
        if (ch == '\r') {
            pSession->scannerState = FwdLockConv_ScannerState_WantsBoundaryEnd;
        } else if (!FwdLockConv_IsWhitespace(ch)) {
            status = FwdLockConv_Status_SyntaxError;
        }
        break;
    case FwdLockConv_ScannerState_WantsBoundaryEnd:
        if (ch == '\n') {
            pSession->parserState = FwdLockConv_ParserState_WantsMimeHeaders;
            pSession->scannerState = FwdLockConv_ScannerState_WantsMimeHeaderNameStart;
        } else {
            status = FwdLockConv_Status_SyntaxError;
        }
        break;
    default:
        status = FwdLockConv_Status_ProgramError;
        break;
    }
    return status;
}

/**
 * Checks whether a given character is valid in a MIME header name.
 *
 * @param[in] ch The character to check.
 *
 * @return A Boolean value indicating whether the given character is valid in a MIME header name.
 */
static int FwdLockConv_IsMimeHeaderNameChar(int ch) {
    return isgraph(ch) && ch != ':';
}

/**
 * Checks whether a given character is valid in a MIME header value.
 *
 * @param[in] ch The character to check.
 *
 * @return A Boolean value indicating whether the given character is valid in a MIME header value.
 */
static int FwdLockConv_IsMimeHeaderValueChar(int ch) {
    return isgraph(ch) && ch != ';';
}

/**
 * Appends a character to the specified dynamically growing string.
 *
 * @param[in,out] pString A reference to a dynamically growing string.
 * @param[in] ch The character to append.
 *
 * @return A status code.
 */
static FwdLockConv_Status_t FwdLockConv_StringAppend(FwdLockConv_String_t *pString, int ch) {
    if (pString->length == pString->maxLength) {
        size_t newMaxLength = pString->maxLength + pString->lengthIncrement;
        char *newPtr = realloc(pString->ptr, newMaxLength + 1);
        if (newPtr == NULL) {
            return FwdLockConv_Status_OutOfMemory;
        }
        pString->ptr = newPtr;
        pString->maxLength = newMaxLength;
    }
    pString->ptr[pString->length++] = ch;
    pString->ptr[pString->length] = '\0';
    return FwdLockConv_Status_OK;
}

/**
 * Attempts to recognize the MIME header name and changes the scanner state accordingly.
 *
 * @param[in,out] pSession A reference to a converter session.
 *
 * @return A status code.
 */
static FwdLockConv_Status_t FwdLockConv_RecognizeMimeHeaderName(FwdLockConv_Session_t *pSession) {
    FwdLockConv_Status_t status = FwdLockConv_Status_OK;
    if (strncmp(pSession->mimeHeaderName.ptr, strContent, strlenContent) == 0) {
        if (strcmp(pSession->mimeHeaderName.ptr + strlenContent, strType) == 0) {
            if (pSession->contentType.ptr == NULL) {
                pSession->scannerState = FwdLockConv_ScannerState_WantsContentTypeStart;
            } else {
                status = FwdLockConv_Status_SyntaxError;
            }
        } else if (strcmp(pSession->mimeHeaderName.ptr + strlenContent, strTransferEncoding) == 0) {
            if (pSession->contentTransferEncoding ==
                    FwdLockConv_ContentTransferEncoding_Undefined) {
                pSession->scannerState = FwdLockConv_ScannerState_WantsContentTransferEncodingStart;
            } else {
                status = FwdLockConv_Status_SyntaxError;
            }
        } else {
            pSession->scannerState = FwdLockConv_ScannerState_WantsCR;
        }
    } else {
        pSession->scannerState = FwdLockConv_ScannerState_WantsCR;
    }
    return status;
}

/**
 * Applies defaults to missing MIME header values.
 *
 * @param[in,out] pSession A reference to a converter session.
 *
 * @return A status code.
 */
static FwdLockConv_Status_t FwdLockConv_ApplyDefaults(FwdLockConv_Session_t *pSession) {
    if (pSession->contentType.ptr == NULL) {
        // Content type is missing: default to "text/plain".
        pSession->contentType.ptr = malloc(sizeof strTextPlain);
        if (pSession->contentType.ptr == NULL) {
            return FwdLockConv_Status_OutOfMemory;
        }
        memcpy(pSession->contentType.ptr, strTextPlain, sizeof strTextPlain);
        pSession->contentType.length = strlenTextPlain;
        pSession->contentType.maxLength = strlenTextPlain;
    }
    if (pSession->contentTransferEncoding == FwdLockConv_ContentTransferEncoding_Undefined) {
        // Content transfer encoding is missing: default to binary.
        pSession->contentTransferEncoding = FwdLockConv_ContentTransferEncoding_Binary;
    }
    return FwdLockConv_Status_OK;
}

/**
 * Verifies that the content type is supported.
 *
 * @param[in,out] pSession A reference to a converter session.
 *
 * @return A status code.
 */
static FwdLockConv_Status_t FwdLockConv_VerifyContentType(FwdLockConv_Session_t *pSession) {
    FwdLockConv_Status_t status;
    if (pSession->contentType.ptr == NULL) {
        status = FwdLockConv_Status_ProgramError;
    } else if (strcmp(pSession->contentType.ptr, strApplicationVndOmaDrmRightsXml) == 0 ||
               strcmp(pSession->contentType.ptr, strApplicationVndOmaDrmContent) == 0) {
        status = FwdLockConv_Status_UnsupportedFileFormat;
    } else {
        status = FwdLockConv_Status_OK;
    }
    return status;
}

/**
 * Writes the header of the output file.
 *
 * @param[in,out] pSession A reference to a converter session.
 * @param[out] pOutput The output from the conversion process.
 *
 * @return A status code.
 */
static FwdLockConv_Status_t FwdLockConv_WriteHeader(FwdLockConv_Session_t *pSession,
                                                    FwdLockConv_Output_t *pOutput) {
    FwdLockConv_Status_t status;
    if (pSession->contentType.length > UCHAR_MAX) {
        status = FwdLockConv_Status_SyntaxError;
    } else {
        pSession->outputBufferSize = OUTPUT_BUFFER_SIZE_INCREMENT;
        pOutput->fromConvertData.pBuffer = malloc(pSession->outputBufferSize);
        if (pOutput->fromConvertData.pBuffer == NULL) {
            status = FwdLockConv_Status_OutOfMemory;
        } else {
            size_t encryptedSessionKeyPos = TOP_HEADER_SIZE + pSession->contentType.length;
            size_t dataSignaturePos = encryptedSessionKeyPos + pSession->encryptedSessionKeyLength;
            size_t headerSignaturePos = dataSignaturePos + SHA1_HASH_SIZE;
            pSession->dataOffset = headerSignaturePos + SHA1_HASH_SIZE;
            memcpy(pSession->topHeader, topHeaderTemplate, sizeof topHeaderTemplate);
            pSession->topHeader[CONTENT_TYPE_LENGTH_POS] =
                    (unsigned char)pSession->contentType.length;
            memcpy(pOutput->fromConvertData.pBuffer, pSession->topHeader, TOP_HEADER_SIZE);
            memcpy((char *)pOutput->fromConvertData.pBuffer + TOP_HEADER_SIZE,
                   pSession->contentType.ptr, pSession->contentType.length);
            memcpy((char *)pOutput->fromConvertData.pBuffer + encryptedSessionKeyPos,
                   pSession->pEncryptedSessionKey, pSession->encryptedSessionKeyLength);

            // Set the signatures to all zeros for now; they will have to be updated later.
            memset((char *)pOutput->fromConvertData.pBuffer + dataSignaturePos, 0,
                   SHA1_HASH_SIZE);
            memset((char *)pOutput->fromConvertData.pBuffer + headerSignaturePos, 0,
                   SHA1_HASH_SIZE);

            pOutput->fromConvertData.numBytes = pSession->dataOffset;
            status = FwdLockConv_Status_OK;
        }
    }
    return status;
}

/**
 * Matches the MIME headers.
 *
 * @param[in,out] pSession A reference to a converter session.
 * @param[in] ch A character.
 * @param[out] pOutput The output from the conversion process.
 *
 * @return A status code.
 */
static FwdLockConv_Status_t FwdLockConv_MatchMimeHeaders(FwdLockConv_Session_t *pSession,
                                                         int ch,
                                                         FwdLockConv_Output_t *pOutput) {
    FwdLockConv_Status_t status = FwdLockConv_Status_OK;
    switch (pSession->scannerState) {
    case FwdLockConv_ScannerState_WantsMimeHeaderNameStart:
        if (FwdLockConv_IsMimeHeaderNameChar(ch)) {
            pSession->mimeHeaderName.length = 0;
            status = FwdLockConv_StringAppend(&pSession->mimeHeaderName, tolower(ch));
            if (status == FwdLockConv_Status_OK) {
                pSession->scannerState = FwdLockConv_ScannerState_WantsMimeHeaderName;
            }
        } else if (ch == '\r') {
            pSession->scannerState = FwdLockConv_ScannerState_WantsMimeHeadersEnd;
        } else if (!FwdLockConv_IsWhitespace(ch)) {
            status = FwdLockConv_Status_SyntaxError;
        }
        break;
    case FwdLockConv_ScannerState_WantsMimeHeaderName:
        if (FwdLockConv_IsMimeHeaderNameChar(ch)) {
            status = FwdLockConv_StringAppend(&pSession->mimeHeaderName, tolower(ch));
        } else if (ch == ':') {
            status = FwdLockConv_RecognizeMimeHeaderName(pSession);
        } else if (FwdLockConv_IsWhitespace(ch)) {
            pSession->scannerState = FwdLockConv_ScannerState_WantsMimeHeaderNameEnd;
        } else {
            status = FwdLockConv_Status_SyntaxError;
        }
        break;
    case FwdLockConv_ScannerState_WantsMimeHeaderNameEnd:
        if (ch == ':') {
            status = FwdLockConv_RecognizeMimeHeaderName(pSession);
        } else if (!FwdLockConv_IsWhitespace(ch)) {
            status = FwdLockConv_Status_SyntaxError;
        }
        break;
    case FwdLockConv_ScannerState_WantsContentTypeStart:
        if (FwdLockConv_IsMimeHeaderValueChar(ch)) {
            status = FwdLockConv_StringAppend(&pSession->contentType, tolower(ch));
            if (status == FwdLockConv_Status_OK) {
                pSession->scannerState = FwdLockConv_ScannerState_WantsContentType;
            }
        } else if (!FwdLockConv_IsWhitespace(ch)) {
            status = FwdLockConv_Status_SyntaxError;
        }
        break;
    case FwdLockConv_ScannerState_WantsContentType:
        if (FwdLockConv_IsMimeHeaderValueChar(ch)) {
            status = FwdLockConv_StringAppend(&pSession->contentType, tolower(ch));
        } else if (ch == ';') {
            pSession->scannerState = FwdLockConv_ScannerState_WantsCR;
        } else if (ch == '\r') {
            pSession->scannerState = FwdLockConv_ScannerState_WantsLF;
        } else if (FwdLockConv_IsWhitespace(ch)) {
            pSession->scannerState = FwdLockConv_ScannerState_WantsMimeHeaderValueEnd;
        } else {
            status = FwdLockConv_Status_SyntaxError;
        }
        break;
    case FwdLockConv_ScannerState_WantsContentTransferEncodingStart:
        if (ch == 'b' || ch == 'B') {
            pSession->scannerState = FwdLockConv_ScannerState_Wants_A_OR_I;
        } else if (ch == '7' || ch == '8') {
            pSession->scannerState = FwdLockConv_ScannerState_Wants_B;
        } else if (!FwdLockConv_IsWhitespace(ch)) {
            status = FwdLockConv_Status_UnsupportedContentTransferEncoding;
        }
        break;
    case FwdLockConv_ScannerState_Wants_A_OR_I:
        if (ch == 'i' || ch == 'I') {
            pSession->scannerState = FwdLockConv_ScannerState_Wants_N;
        } else if (ch == 'a' || ch == 'A') {
            pSession->scannerState = FwdLockConv_ScannerState_Wants_S;
        } else {
            status = FwdLockConv_Status_UnsupportedContentTransferEncoding;
        }
        break;
    case FwdLockConv_ScannerState_Wants_N:
        if (ch == 'n' || ch == 'N') {
            pSession->scannerState = FwdLockConv_ScannerState_Wants_A;
        } else {
            status = FwdLockConv_Status_UnsupportedContentTransferEncoding;
        }
        break;
    case FwdLockConv_ScannerState_Wants_A:
        if (ch == 'a' || ch == 'A') {
            pSession->scannerState = FwdLockConv_ScannerState_Wants_R;
        } else {
            status = FwdLockConv_Status_UnsupportedContentTransferEncoding;
        }
        break;
    case FwdLockConv_ScannerState_Wants_R:
        if (ch == 'r' || ch == 'R') {
            pSession->scannerState = FwdLockConv_ScannerState_Wants_Y;
        } else {
            status = FwdLockConv_Status_UnsupportedContentTransferEncoding;
        }
        break;
    case FwdLockConv_ScannerState_Wants_Y:
        if (ch == 'y' || ch == 'Y') {
            pSession->contentTransferEncoding = FwdLockConv_ContentTransferEncoding_Binary;
            pSession->scannerState = FwdLockConv_ScannerState_WantsContentTransferEncodingEnd;
        } else {
            status = FwdLockConv_Status_UnsupportedContentTransferEncoding;
        }
        break;
    case FwdLockConv_ScannerState_Wants_S:
        if (ch == 's' || ch == 'S') {
            pSession->scannerState = FwdLockConv_ScannerState_Wants_E;
        } else {
            status = FwdLockConv_Status_UnsupportedContentTransferEncoding;
        }
        break;
    case FwdLockConv_ScannerState_Wants_E:
        if (ch == 'e' || ch == 'E') {
            pSession->scannerState = FwdLockConv_ScannerState_Wants_6;
        } else {
            status = FwdLockConv_Status_UnsupportedContentTransferEncoding;
        }
        break;
    case FwdLockConv_ScannerState_Wants_6:
        if (ch == '6') {
            pSession->scannerState = FwdLockConv_ScannerState_Wants_4;
        } else {
            status = FwdLockConv_Status_UnsupportedContentTransferEncoding;
        }
        break;
    case FwdLockConv_ScannerState_Wants_4:
        if (ch == '4') {
            pSession->contentTransferEncoding = FwdLockConv_ContentTransferEncoding_Base64;
            pSession->scannerState = FwdLockConv_ScannerState_WantsContentTransferEncodingEnd;
        } else {
            status = FwdLockConv_Status_UnsupportedContentTransferEncoding;
        }
        break;
    case FwdLockConv_ScannerState_Wants_B:
        if (ch == 'b' || ch == 'B') {
            pSession->scannerState = FwdLockConv_ScannerState_Wants_I;
        } else {
            status = FwdLockConv_Status_UnsupportedContentTransferEncoding;
        }
        break;
    case FwdLockConv_ScannerState_Wants_I:
        if (ch == 'i' || ch == 'I') {
            pSession->scannerState = FwdLockConv_ScannerState_Wants_T;
        } else {
            status = FwdLockConv_Status_UnsupportedContentTransferEncoding;
        }
        break;
    case FwdLockConv_ScannerState_Wants_T:
        if (ch == 't' || ch == 'T') {
            pSession->contentTransferEncoding = FwdLockConv_ContentTransferEncoding_Binary;
            pSession->scannerState = FwdLockConv_ScannerState_WantsContentTransferEncodingEnd;
        } else {
            status = FwdLockConv_Status_UnsupportedContentTransferEncoding;
        }
        break;
    case FwdLockConv_ScannerState_WantsContentTransferEncodingEnd:
        if (ch == ';') {
            pSession->scannerState = FwdLockConv_ScannerState_WantsCR;
        } else if (ch == '\r') {
            pSession->scannerState = FwdLockConv_ScannerState_WantsLF;
        } else if (FwdLockConv_IsWhitespace(ch)) {
            pSession->scannerState = FwdLockConv_ScannerState_WantsMimeHeaderValueEnd;
        } else {
            status = FwdLockConv_Status_UnsupportedContentTransferEncoding;
        }
        break;
    case FwdLockConv_ScannerState_WantsMimeHeaderValueEnd:
        if (ch == ';') {
            pSession->scannerState = FwdLockConv_ScannerState_WantsCR;
        } else if (ch == '\r') {
            pSession->scannerState = FwdLockConv_ScannerState_WantsLF;
        } else if (!FwdLockConv_IsWhitespace(ch)) {
            status = FwdLockConv_Status_SyntaxError;
        }
        break;
    case FwdLockConv_ScannerState_WantsCR:
        if (ch == '\r') {
            pSession->scannerState = FwdLockConv_ScannerState_WantsLF;
        }
        break;
    case FwdLockConv_ScannerState_WantsLF:
        if (ch == '\n') {
            pSession->scannerState = FwdLockConv_ScannerState_WantsMimeHeaderNameStart;
        } else {
            status = FwdLockConv_Status_SyntaxError;
        }
        break;
    case FwdLockConv_ScannerState_WantsMimeHeadersEnd:
        if (ch == '\n') {
            status = FwdLockConv_ApplyDefaults(pSession);
            if (status == FwdLockConv_Status_OK) {
                status = FwdLockConv_VerifyContentType(pSession);
            }
            if (status == FwdLockConv_Status_OK) {
                status = FwdLockConv_WriteHeader(pSession, pOutput);
            }
            if (status == FwdLockConv_Status_OK) {
                if (pSession->contentTransferEncoding ==
                        FwdLockConv_ContentTransferEncoding_Binary) {
                    pSession->parserState = FwdLockConv_ParserState_WantsBinaryEncodedData;
                } else {
                    pSession->parserState = FwdLockConv_ParserState_WantsBase64EncodedData;
                }
                pSession->scannerState = FwdLockConv_ScannerState_WantsByte1;
            }
        } else {
            status = FwdLockConv_Status_SyntaxError;
        }
        break;
    default:
        status = FwdLockConv_Status_ProgramError;
        break;
    }
    return status;
}

/**
 * Increments the counter, treated as a 16-byte little-endian number, by one.
 *
 * @param[in,out] pSession A reference to a converter session.
 */
static void FwdLockConv_IncrementCounter(FwdLockConv_Session_t *pSession) {
    size_t i = 0;
    while ((++pSession->counter[i] == 0) && (++i < AES_BLOCK_SIZE))
        ;
}

/**
 * Encrypts the given character and writes it to the output buffer.
 *
 * @param[in,out] pSession A reference to a converter session.
 * @param[in] ch The character to encrypt and write.
 * @param[in,out] pOutput The output from the conversion process.
 *
 * @return A status code.
 */
static FwdLockConv_Status_t FwdLockConv_WriteEncryptedChar(FwdLockConv_Session_t *pSession,
                                                           unsigned char ch,
                                                           FwdLockConv_Output_t *pOutput) {
    if (pOutput->fromConvertData.numBytes == pSession->outputBufferSize) {
        void *pBuffer;
        pSession->outputBufferSize += OUTPUT_BUFFER_SIZE_INCREMENT;
        pBuffer = realloc(pOutput->fromConvertData.pBuffer, pSession->outputBufferSize);
        if (pBuffer == NULL) {
            return FwdLockConv_Status_OutOfMemory;
        }
        pOutput->fromConvertData.pBuffer = pBuffer;
    }
    if (++pSession->keyStreamIndex == AES_BLOCK_SIZE) {
        FwdLockConv_IncrementCounter(pSession);
        pSession->keyStreamIndex = 0;
    }
    if (pSession->keyStreamIndex == 0) {
        AES_encrypt(pSession->counter, pSession->keyStream, &pSession->encryptionRoundKeys);
    }
    ch ^= pSession->keyStream[pSession->keyStreamIndex];
    ((unsigned char *)pOutput->fromConvertData.pBuffer)[pOutput->fromConvertData.numBytes++] = ch;
    ++pSession->numDataBytes;
    return FwdLockConv_Status_OK;
}

/**
 * Matches binary-encoded content data and encrypts it, while looking out for the close delimiter.
 *
 * @param[in,out] pSession A reference to a converter session.
 * @param[in] ch A character.
 * @param[in,out] pOutput The output from the conversion process.
 *
 * @return A status code.
 */
static FwdLockConv_Status_t FwdLockConv_MatchBinaryEncodedData(FwdLockConv_Session_t *pSession,
                                                               int ch,
                                                               FwdLockConv_Output_t *pOutput) {
    FwdLockConv_Status_t status = FwdLockConv_Status_OK;
    switch (pSession->scannerState) {
    case FwdLockConv_ScannerState_WantsByte1:
        if (ch != pSession->delimiter[pSession->delimiterMatchPos]) {
            // The partial match of the delimiter turned out to be spurious. Flush the matched bytes
            // to the output buffer and start over.
            size_t i;
            for (i = 0; i < pSession->delimiterMatchPos; ++i) {
                status = FwdLockConv_WriteEncryptedChar(pSession, pSession->delimiter[i], pOutput);
                if (status != FwdLockConv_Status_OK) {
                    return status;
                }
            }
            pSession->delimiterMatchPos = 0;
        }
        if (ch != pSession->delimiter[pSession->delimiterMatchPos]) {
            // The current character isn't part of the delimiter. Write it to the output buffer.
            status = FwdLockConv_WriteEncryptedChar(pSession, ch, pOutput);
        } else if (++pSession->delimiterMatchPos == pSession->delimiterLength) {
            // The entire delimiter has been matched. The only valid characters now are the "--"
            // that complete the close delimiter (no more message parts are expected).
            pSession->scannerState = FwdLockConv_ScannerState_WantsFirstDash;
        }
        break;
    case FwdLockConv_ScannerState_WantsFirstDash:
        if (ch == '-') {
            pSession->scannerState = FwdLockConv_ScannerState_WantsSecondDash;
        } else {
            status = FwdLockConv_Status_SyntaxError;
        }
        break;
    case FwdLockConv_ScannerState_WantsSecondDash:
        if (ch == '-') {
            pSession->parserState = FwdLockConv_ParserState_Done;
        } else {
            status = FwdLockConv_Status_SyntaxError;
        }
        break;
    default:
        status = FwdLockConv_Status_ProgramError;
        break;
    }
    return status;
}

/**
 * Checks whether a given character is valid in base64-encoded data.
 *
 * @param[in] ch The character to check.
 *
 * @return A Boolean value indicating whether the given character is valid in base64-encoded data.
 */
static int FwdLockConv_IsBase64Char(int ch) {
    return 0 <= ch && ch <= 'z' && base64Values[ch] >= 0;
}

/**
 * Matches base64-encoded content data and encrypts it, while looking out for the close delimiter.
 *
 * @param[in,out] pSession A reference to a converter session.
 * @param[in] ch A character.
 * @param[in,out] pOutput The output from the conversion process.
 *
 * @return A status code.
 */
static FwdLockConv_Status_t FwdLockConv_MatchBase64EncodedData(FwdLockConv_Session_t *pSession,
                                                               int ch,
                                                               FwdLockConv_Output_t *pOutput) {
    FwdLockConv_Status_t status = FwdLockConv_Status_OK;
    switch (pSession->scannerState) {
    case FwdLockConv_ScannerState_WantsByte1:
    case FwdLockConv_ScannerState_WantsByte1_AfterCRLF:
        if (FwdLockConv_IsBase64Char(ch)) {
            pSession->ch = base64Values[ch] << 2;
            pSession->scannerState = FwdLockConv_ScannerState_WantsByte2;
        } else if (ch == '\r') {
            pSession->savedScannerState = FwdLockConv_ScannerState_WantsByte1_AfterCRLF;
            pSession->scannerState = FwdLockConv_ScannerState_WantsLF;
        } else if (ch == '-') {
            if (pSession->scannerState == FwdLockConv_ScannerState_WantsByte1_AfterCRLF) {
                pSession->delimiterMatchPos = 3;
                pSession->scannerState = FwdLockConv_ScannerState_WantsDelimiter;
            } else {
                status = FwdLockConv_Status_SyntaxError;
            }
        } else if (!FwdLockConv_IsWhitespace(ch)) {
            status = FwdLockConv_Status_SyntaxError;
        }
        break;
    case FwdLockConv_ScannerState_WantsByte2:
        if (FwdLockConv_IsBase64Char(ch)) {
            pSession->ch |= base64Values[ch] >> 4;
            status = FwdLockConv_WriteEncryptedChar(pSession, pSession->ch, pOutput);
            if (status == FwdLockConv_Status_OK) {
                pSession->ch = base64Values[ch] << 4;
                pSession->scannerState = FwdLockConv_ScannerState_WantsByte3;
            }
        } else if (ch == '\r') {
            pSession->savedScannerState = pSession->scannerState;
            pSession->scannerState = FwdLockConv_ScannerState_WantsLF;
        } else if (!FwdLockConv_IsWhitespace(ch)) {
            status = FwdLockConv_Status_SyntaxError;
        }
        break;
    case FwdLockConv_ScannerState_WantsByte3:
        if (FwdLockConv_IsBase64Char(ch)) {
            pSession->ch |= base64Values[ch] >> 2;
            status = FwdLockConv_WriteEncryptedChar(pSession, pSession->ch, pOutput);
            if (status == FwdLockConv_Status_OK) {
                pSession->ch = base64Values[ch] << 6;
                pSession->scannerState = FwdLockConv_ScannerState_WantsByte4;
            }
        } else if (ch == '\r') {
            pSession->savedScannerState = pSession->scannerState;
            pSession->scannerState = FwdLockConv_ScannerState_WantsLF;
        } else if (ch == '=') {
            pSession->scannerState = FwdLockConv_ScannerState_WantsPadding;
        } else if (!FwdLockConv_IsWhitespace(ch)) {
            status = FwdLockConv_Status_SyntaxError;
        }
        break;
    case FwdLockConv_ScannerState_WantsByte4:
        if (FwdLockConv_IsBase64Char(ch)) {
            pSession->ch |= base64Values[ch];
            status = FwdLockConv_WriteEncryptedChar(pSession, pSession->ch, pOutput);
            if (status == FwdLockConv_Status_OK) {
                pSession->scannerState = FwdLockConv_ScannerState_WantsByte1;
            }
        } else if (ch == '\r') {
            pSession->savedScannerState = pSession->scannerState;
            pSession->scannerState = FwdLockConv_ScannerState_WantsLF;
        } else if (ch == '=') {
            pSession->scannerState = FwdLockConv_ScannerState_WantsWhitespace;
        } else if (!FwdLockConv_IsWhitespace(ch)) {
            status = FwdLockConv_Status_SyntaxError;
        }
        break;
    case FwdLockConv_ScannerState_WantsLF:
        if (ch == '\n') {
            pSession->scannerState = pSession->savedScannerState;
        } else {
            status = FwdLockConv_Status_SyntaxError;
        }
        break;
    case FwdLockConv_ScannerState_WantsPadding:
        if (ch == '=') {
            pSession->scannerState = FwdLockConv_ScannerState_WantsWhitespace;
        } else {
            status = FwdLockConv_Status_SyntaxError;
        }
        break;
    case FwdLockConv_ScannerState_WantsWhitespace:
    case FwdLockConv_ScannerState_WantsWhitespace_AfterCRLF:
        if (ch == '\r') {
            pSession->savedScannerState = FwdLockConv_ScannerState_WantsWhitespace_AfterCRLF;
            pSession->scannerState = FwdLockConv_ScannerState_WantsLF;
        } else if (ch == '-') {
            if (pSession->scannerState == FwdLockConv_ScannerState_WantsWhitespace_AfterCRLF) {
                pSession->delimiterMatchPos = 3;
                pSession->scannerState = FwdLockConv_ScannerState_WantsDelimiter;
            } else {
                status = FwdLockConv_Status_SyntaxError;
            }
        } else if (FwdLockConv_IsWhitespace(ch)) {
            pSession->scannerState = FwdLockConv_ScannerState_WantsWhitespace;
        } else {
            status = FwdLockConv_Status_SyntaxError;
        }
        break;
    case FwdLockConv_ScannerState_WantsDelimiter:
        if (ch != pSession->delimiter[pSession->delimiterMatchPos]) {
            status = FwdLockConv_Status_SyntaxError;
        } else if (++pSession->delimiterMatchPos == pSession->delimiterLength) {
            pSession->scannerState = FwdLockConv_ScannerState_WantsFirstDash;
        }
        break;
    case FwdLockConv_ScannerState_WantsFirstDash:
        if (ch == '-') {
            pSession->scannerState = FwdLockConv_ScannerState_WantsSecondDash;
        } else {
            status = FwdLockConv_Status_SyntaxError;
        }
        break;
    case FwdLockConv_ScannerState_WantsSecondDash:
        if (ch == '-') {
            pSession->parserState = FwdLockConv_ParserState_Done;
        } else {
            status = FwdLockConv_Status_SyntaxError;
        }
        break;
    default:
        status = FwdLockConv_Status_ProgramError;
        break;
    }
    return status;
}

/**
 * Pushes a single character into the converter's state machine.
 *
 * @param[in,out] pSession A reference to a converter session.
 * @param[in] ch A character.
 * @param[in,out] pOutput The output from the conversion process.
 *
 * @return A status code.
 */
static FwdLockConv_Status_t FwdLockConv_PushChar(FwdLockConv_Session_t *pSession,
                                                 int ch,
                                                 FwdLockConv_Output_t *pOutput) {
    FwdLockConv_Status_t status;
    ++pSession->numCharsConsumed;
    switch (pSession->parserState) {
    case FwdLockConv_ParserState_WantsOpenDelimiter:
        status = FwdLockConv_MatchOpenDelimiter(pSession, ch);
        break;
    case FwdLockConv_ParserState_WantsMimeHeaders:
        status = FwdLockConv_MatchMimeHeaders(pSession, ch, pOutput);
        break;
    case FwdLockConv_ParserState_WantsBinaryEncodedData:
        status = FwdLockConv_MatchBinaryEncodedData(pSession, ch, pOutput);
        break;
    case FwdLockConv_ParserState_WantsBase64EncodedData:
        if (ch == '\n' && pSession->scannerState != FwdLockConv_ScannerState_WantsLF) {
            // Repair base64-encoded data that doesn't have carriage returns in its line breaks.
            status = FwdLockConv_MatchBase64EncodedData(pSession, '\r', pOutput);
            if (status != FwdLockConv_Status_OK) {
                break;
            }
        }
        status = FwdLockConv_MatchBase64EncodedData(pSession, ch, pOutput);
        break;
    case FwdLockConv_ParserState_Done:
        status = FwdLockConv_Status_OK;
        break;
    default:
        status = FwdLockConv_Status_ProgramError;
        break;
    }
    return status;
}

FwdLockConv_Status_t FwdLockConv_OpenSession(int *pSessionId, FwdLockConv_Output_t *pOutput) {
    FwdLockConv_Status_t status;
    if (pSessionId == NULL || pOutput == NULL) {
        status = FwdLockConv_Status_InvalidArgument;
    } else {
        *pSessionId = FwdLockConv_AcquireSession();
        if (*pSessionId < 0) {
            status = FwdLockConv_Status_TooManySessions;
        } else {
            FwdLockConv_Session_t *pSession = sessionPtrs[*pSessionId];
            pSession->encryptedSessionKeyLength = FwdLockGlue_GetEncryptedKeyLength(KEY_SIZE);
            if (pSession->encryptedSessionKeyLength < AES_BLOCK_SIZE) {
                // The encrypted session key is used as the CTR-mode nonce, so it must be at least
                // the size of a single AES block.
                status = FwdLockConv_Status_ProgramError;
            } else {
                pSession->pEncryptedSessionKey = malloc(pSession->encryptedSessionKeyLength);
                if (pSession->pEncryptedSessionKey == NULL) {
                    status = FwdLockConv_Status_OutOfMemory;
                } else {
                    if (!FwdLockGlue_GetRandomNumber(pSession->sessionKey, KEY_SIZE)) {
                        status = FwdLockConv_Status_RandomNumberGenerationFailed;
                    } else if (!FwdLockGlue_EncryptKey(pSession->sessionKey, KEY_SIZE,
                                                       pSession->pEncryptedSessionKey,
                                                       pSession->encryptedSessionKeyLength)) {
                        status = FwdLockConv_Status_KeyEncryptionFailed;
                    } else {
                        status = FwdLockConv_DeriveKeys(pSession);
                    }
                    if (status == FwdLockConv_Status_OK) {
                        memset(pSession->sessionKey, 0, KEY_SIZE); // Zero out key data.
                        memcpy(pSession->counter, pSession->pEncryptedSessionKey, AES_BLOCK_SIZE);
                        pSession->parserState = FwdLockConv_ParserState_WantsOpenDelimiter;
                        pSession->scannerState = FwdLockConv_ScannerState_WantsFirstDash;
                        pSession->numCharsConsumed = 0;
                        pSession->delimiterMatchPos = 0;
                        pSession->mimeHeaderName = nullString;
                        pSession->contentType = nullString;
                        pSession->contentTransferEncoding =
                                FwdLockConv_ContentTransferEncoding_Undefined;
                        pSession->keyStreamIndex = -1;
                        pOutput->fromConvertData.pBuffer = NULL;
                        pOutput->fromConvertData.errorPos = INVALID_OFFSET;
                    } else {
                        free(pSession->pEncryptedSessionKey);
                    }
                }
            }
            if (status != FwdLockConv_Status_OK) {
                FwdLockConv_ReleaseSession(*pSessionId);
                *pSessionId = -1;
            }
        }
    }
    return status;
}

FwdLockConv_Status_t FwdLockConv_ConvertData(int sessionId,
                                             const void *pBuffer,
                                             size_t numBytes,
                                             FwdLockConv_Output_t *pOutput) {
    FwdLockConv_Status_t status;
    if (!FwdLockConv_IsValidSession(sessionId) || pBuffer == NULL || pOutput == NULL) {
        status = FwdLockConv_Status_InvalidArgument;
    } else {
        size_t i;
        FwdLockConv_Session_t *pSession = sessionPtrs[sessionId];
        pSession->dataOffset = 0;
        pSession->numDataBytes = 0;
        pOutput->fromConvertData.numBytes = 0;
        status = FwdLockConv_Status_OK;

        for (i = 0; i < numBytes; ++i) {
            status = FwdLockConv_PushChar(pSession, ((char *)pBuffer)[i], pOutput);
            if (status != FwdLockConv_Status_OK) {
                break;
            }
        }
        if (status == FwdLockConv_Status_OK) {
            // Update the data signature.
            HMAC_Update(&pSession->signingContext,
                        &((unsigned char *)pOutput->fromConvertData.pBuffer)[pSession->dataOffset],
                        pSession->numDataBytes);
        } else if (status == FwdLockConv_Status_SyntaxError) {
            pOutput->fromConvertData.errorPos = pSession->numCharsConsumed;
        }
    }
    return status;
}

FwdLockConv_Status_t FwdLockConv_CloseSession(int sessionId, FwdLockConv_Output_t *pOutput) {
    FwdLockConv_Status_t status;
    if (!FwdLockConv_IsValidSession(sessionId) || pOutput == NULL) {
        status = FwdLockConv_Status_InvalidArgument;
    } else {
        FwdLockConv_Session_t *pSession = sessionPtrs[sessionId];
        free(pOutput->fromConvertData.pBuffer);
        if (pSession->parserState != FwdLockConv_ParserState_Done) {
            pOutput->fromCloseSession.errorPos = pSession->numCharsConsumed;
            status = FwdLockConv_Status_SyntaxError;
        } else {
            // Finalize the data signature.
            unsigned int signatureSize = SHA1_HASH_SIZE;
            HMAC_Final(&pSession->signingContext, pOutput->fromCloseSession.signatures,
                       &signatureSize);
            if (signatureSize != SHA1_HASH_SIZE) {
                status = FwdLockConv_Status_ProgramError;
            } else {
                // Calculate the header signature, which is a signature of the rest of the header
                // including the data signature.
                HMAC_Init_ex(&pSession->signingContext, NULL, KEY_SIZE, NULL, NULL);
                HMAC_Update(&pSession->signingContext, pSession->topHeader, TOP_HEADER_SIZE);
                HMAC_Update(&pSession->signingContext, (unsigned char *)pSession->contentType.ptr,
                            pSession->contentType.length);
                HMAC_Update(&pSession->signingContext, pSession->pEncryptedSessionKey,
                            pSession->encryptedSessionKeyLength);
                HMAC_Update(&pSession->signingContext, pOutput->fromCloseSession.signatures,
                            SHA1_HASH_SIZE);
                HMAC_Final(&pSession->signingContext,
                           &pOutput->fromCloseSession.signatures[SHA1_HASH_SIZE], &signatureSize);
                if (signatureSize != SHA1_HASH_SIZE) {
                    status = FwdLockConv_Status_ProgramError;
                } else {
                    pOutput->fromCloseSession.fileOffset = TOP_HEADER_SIZE +
                            pSession->contentType.length + pSession->encryptedSessionKeyLength;
                    status = FwdLockConv_Status_OK;
                }
            }
            pOutput->fromCloseSession.errorPos = INVALID_OFFSET;
        }
        free(pSession->mimeHeaderName.ptr);
        free(pSession->contentType.ptr);
        free(pSession->pEncryptedSessionKey);
        HMAC_CTX_cleanup(&pSession->signingContext);
        FwdLockConv_ReleaseSession(sessionId);
    }
    return status;
}

FwdLockConv_Status_t FwdLockConv_ConvertOpenFile(int inputFileDesc,
                                                 FwdLockConv_ReadFunc_t *fpReadFunc,
                                                 int outputFileDesc,
                                                 FwdLockConv_WriteFunc_t *fpWriteFunc,
                                                 FwdLockConv_LSeekFunc_t *fpLSeekFunc,
                                                 off64_t *pErrorPos) {
    FwdLockConv_Status_t status;
    if (pErrorPos != NULL) {
        *pErrorPos = INVALID_OFFSET;
    }
    if (fpReadFunc == NULL || fpWriteFunc == NULL || fpLSeekFunc == NULL || inputFileDesc < 0 ||
        outputFileDesc < 0) {
        status = FwdLockConv_Status_InvalidArgument;
    } else {
        char *pReadBuffer = malloc(READ_BUFFER_SIZE);
        if (pReadBuffer == NULL) {
            status = FwdLockConv_Status_OutOfMemory;
        } else {
            int sessionId;
            FwdLockConv_Output_t output;
            status = FwdLockConv_OpenSession(&sessionId, &output);
            if (status == FwdLockConv_Status_OK) {
                ssize_t numBytesRead;
                FwdLockConv_Status_t closeStatus;
                while ((numBytesRead =
                        fpReadFunc(inputFileDesc, pReadBuffer, READ_BUFFER_SIZE)) > 0) {
                    status = FwdLockConv_ConvertData(sessionId, pReadBuffer, (size_t)numBytesRead,
                                                     &output);
                    if (status == FwdLockConv_Status_OK) {
                        if (output.fromConvertData.pBuffer != NULL &&
                            output.fromConvertData.numBytes > 0) {
                            ssize_t numBytesWritten = fpWriteFunc(outputFileDesc,
                                                                  output.fromConvertData.pBuffer,
                                                                  output.fromConvertData.numBytes);
                            if (numBytesWritten != (ssize_t)output.fromConvertData.numBytes) {
                                status = FwdLockConv_Status_FileWriteError;
                                break;
                            }
                        }
                    } else {
                        if (status == FwdLockConv_Status_SyntaxError && pErrorPos != NULL) {
                            *pErrorPos = output.fromConvertData.errorPos;
                        }
                        break;
                    }
                } // end while
                if (numBytesRead < 0) {
                    status = FwdLockConv_Status_FileReadError;
                }
                closeStatus = FwdLockConv_CloseSession(sessionId, &output);
                if (status == FwdLockConv_Status_OK) {
                    if (closeStatus != FwdLockConv_Status_OK) {
                        if (closeStatus == FwdLockConv_Status_SyntaxError && pErrorPos != NULL) {
                            *pErrorPos = output.fromCloseSession.errorPos;
                        }
                        status = closeStatus;
                    } else if (fpLSeekFunc(outputFileDesc, output.fromCloseSession.fileOffset,
                                           SEEK_SET) < 0) {
                        status = FwdLockConv_Status_FileSeekError;
                    } else if (fpWriteFunc(outputFileDesc, output.fromCloseSession.signatures,
                                           FWD_LOCK_SIGNATURES_SIZE) != FWD_LOCK_SIGNATURES_SIZE) {
                        status = FwdLockConv_Status_FileWriteError;
                    }
                }
            }
            free(pReadBuffer);
        }
    }
    return status;
}

FwdLockConv_Status_t FwdLockConv_ConvertFile(const char *pInputFilename,
                                             const char *pOutputFilename,
                                             off64_t *pErrorPos) {
    FwdLockConv_Status_t status;
    if (pErrorPos != NULL) {
        *pErrorPos = INVALID_OFFSET;
    }
    if (pInputFilename == NULL || pOutputFilename == NULL) {
        status = FwdLockConv_Status_InvalidArgument;
    } else {
        int inputFileDesc = open(pInputFilename, O_RDONLY);
        if (inputFileDesc < 0) {
            status = FwdLockConv_Status_FileNotFound;
        } else {
            int outputFileDesc = open(pOutputFilename, O_CREAT | O_TRUNC | O_WRONLY,
                                      S_IRUSR | S_IWUSR | S_IRGRP | S_IROTH);
            if (outputFileDesc < 0) {
                status = FwdLockConv_Status_FileCreationFailed;
            } else {
                status = FwdLockConv_ConvertOpenFile(inputFileDesc, read, outputFileDesc, write,
                                                     lseek64, pErrorPos);
                if (close(outputFileDesc) == 0 && status != FwdLockConv_Status_OK) {
                    remove(pOutputFilename);
                }
            }
            (void)close(inputFileDesc);
        }
    }
    return status;
}
