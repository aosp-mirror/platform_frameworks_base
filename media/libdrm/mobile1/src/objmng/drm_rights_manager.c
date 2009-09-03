/*
 * Copyright (C) 2007 The Android Open Source Project
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

#include <drm_rights_manager.h>
#include <drm_inner.h>
#include <drm_file.h>
#include <drm_i18n.h>

static int32_t drm_getString(uint8_t* string, int32_t len, int32_t handle)
{
    int32_t i;

    for (i = 0; i < len; i++) {
        if (DRM_FILE_FAILURE == DRM_file_read(handle, &string[i], 1))
            return FALSE;
        if (string[i] == '\n') {
            string[i + 1] = '\0';
            break;
        }
    }
    return TRUE;
}

static int32_t drm_putString(uint8_t* string, int32_t handle)
{
    int32_t i = 0;

    for (i = 0;; i++) {
        if (string[i] == '\0')
            break;
        if (DRM_FILE_FAILURE == DRM_file_write(handle, &string[i], 1))
            return FALSE;
    }
    return TRUE;
}

static int32_t drm_writeToUidTxt(uint8_t* Uid, int32_t* id)
{
    int32_t length;
    int32_t i;
    uint8_t idStr[8];
    int32_t idMax;
    uint8_t(*uidStr)[256];
    uint16_t nameUcs2[MAX_FILENAME_LEN];
    int32_t nameLen;
    int32_t bytesConsumed;
    int32_t handle;
    int32_t fileRes;

    if (*id < 1)
        return FALSE;

    /* convert in ucs2 */
    nameLen = strlen(DRM_UID_FILE_PATH);
    nameLen = DRM_i18n_mbsToWcs(DRM_CHARSET_UTF8,
                        (uint8_t *)DRM_UID_FILE_PATH,
                        nameLen,
                        nameUcs2,
                        MAX_FILENAME_LEN,
                        &bytesConsumed);
    fileRes = DRM_file_open(nameUcs2,
                        nameLen,
                        DRM_FILE_MODE_READ,
                        &handle);
    if (DRM_FILE_SUCCESS != fileRes) {
        DRM_file_open(nameUcs2,
                        nameLen,
                        DRM_FILE_MODE_WRITE,
                        &handle);
        DRM_file_write(handle, (uint8_t *)"0\n", 2);
        DRM_file_close(handle);
        DRM_file_open(nameUcs2,
                        nameLen,
                        DRM_FILE_MODE_READ,
                        &handle);
    }

    if (!drm_getString(idStr, 8, handle)) {
        DRM_file_close(handle);
        return FALSE;
    }
    idMax = atoi((char *)idStr);

    if (idMax < *id)
        uidStr = malloc((idMax + 1) * 256);
    else
        uidStr = malloc(idMax * 256);

    for (i = 0; i < idMax; i++) {
        if (!drm_getString(uidStr[i], 256, handle)) {
            DRM_file_close(handle);
            free(uidStr);
            return FALSE;
        }
    }
    length = strlen((char *)Uid);
    strcpy((char *)uidStr[*id - 1], (char *)Uid);
    uidStr[*id - 1][length] = '\n';
    uidStr[*id - 1][length + 1] = '\0';
    if (idMax < (*id))
        idMax++;
    DRM_file_close(handle);

    DRM_file_open(nameUcs2,
                    nameLen,
                    DRM_FILE_MODE_WRITE,
                    &handle);
    sprintf((char *)idStr, "%d", idMax);

    if (!drm_putString(idStr, handle)) {
        DRM_file_close(handle);
        free(uidStr);
        return FALSE;
    }
    if (DRM_FILE_FAILURE == DRM_file_write(handle, (uint8_t *)"\n", 1)) {
        DRM_file_close(handle);
        free(uidStr);
        return FALSE;
    }
    for (i = 0; i < idMax; i++) {
        if (!drm_putString(uidStr[i], handle)) {
            DRM_file_close(handle);
            free(uidStr);
            return FALSE;
        }
    }
    if (DRM_FILE_FAILURE == DRM_file_write(handle, (uint8_t *)"\n", 1)) {
        DRM_file_close(handle);
        free(uidStr);
        return FALSE;
    }
    DRM_file_close(handle);
    free(uidStr);
    return TRUE;
}

/* See objmng_files.h */
int32_t drm_readFromUidTxt(uint8_t* Uid, int32_t* id, int32_t option)
{
    int32_t i;
    uint8_t p[256] = { 0 };
    uint8_t idStr[8];
    int32_t idMax = 0;
    uint16_t nameUcs2[MAX_FILENAME_LEN];
    int32_t nameLen = 0;
    int32_t bytesConsumed;
    int32_t handle;
    int32_t fileRes;

    if (NULL == id || NULL == Uid)
        return FALSE;

    DRM_file_startup();

    /* convert in ucs2 */
    nameLen = strlen(DRM_UID_FILE_PATH);
    nameLen = DRM_i18n_mbsToWcs(DRM_CHARSET_UTF8,
                        (uint8_t *)DRM_UID_FILE_PATH,
                        nameLen,
                        nameUcs2,
                        MAX_FILENAME_LEN,
                        &bytesConsumed);
    fileRes = DRM_file_open(nameUcs2,
                        nameLen,
                        DRM_FILE_MODE_READ,
                        &handle);
    if (DRM_FILE_SUCCESS != fileRes) {
        DRM_file_open(nameUcs2,
                        nameLen,
                        DRM_FILE_MODE_WRITE,
                        &handle);
        DRM_file_write(handle, (uint8_t *)"0\n", 2);
        DRM_file_close(handle);
        DRM_file_open(nameUcs2,
                        nameLen,
                        DRM_FILE_MODE_READ,
                        &handle);
    }

    if (!drm_getString(idStr, 8, handle)) {
        DRM_file_close(handle);
        return FALSE;
    }
    idMax = atoi((char *)idStr);

    if (option == GET_UID) {
        if (*id < 1 || *id > idMax) {
            DRM_file_close(handle);
            return FALSE;
        }
        for (i = 1; i <= *id; i++) {
            if (!drm_getString(Uid, 256, handle)) {
                DRM_file_close(handle);
                return FALSE;
            }
        }
        DRM_file_close(handle);
        return TRUE;
    }
    if (option == GET_ID) {
        *id = -1;
        for (i = 1; i <= idMax; i++) {
            if (!drm_getString(p, 256, handle)) {
                DRM_file_close(handle);
                return FALSE;
            }
            if (strstr((char *)p, (char *)Uid) != NULL
                && strlen((char *)p) == strlen((char *)Uid) + 1) {
                *id = i;
                DRM_file_close(handle);
                return TRUE;
            }
            if ((*id == -1) && (strlen((char *)p) < 3))
                *id = i;
        }
        if (*id != -1) {
            DRM_file_close(handle);
            return FALSE;
        }
        *id = idMax + 1;
        DRM_file_close(handle);
        return FALSE;
    }
    DRM_file_close(handle);
    return FALSE;
}

static int32_t drm_acquireId(uint8_t* uid, int32_t* id)
{
    if (TRUE == drm_readFromUidTxt(uid, id, GET_ID))
        return TRUE;

    drm_writeToUidTxt(uid, id);

    return FALSE; /* The Uid is not exit, then return FALSE indicate it */
}

int32_t drm_writeOrReadInfo(int32_t id, T_DRM_Rights* Ro, int32_t* RoAmount, int32_t option)
{
    uint8_t fullname[MAX_FILENAME_LEN] = {0};
    int32_t tmpRoAmount;
    uint16_t nameUcs2[MAX_FILENAME_LEN];
    int32_t nameLen = 0;
    int32_t bytesConsumed;
    int32_t handle;
    int32_t fileRes;

    sprintf((char *)fullname, ANDROID_DRM_CORE_PATH"%d"EXTENSION_NAME_INFO, id);

    /* convert in ucs2 */
    nameLen = strlen((char *)fullname);
    nameLen = DRM_i18n_mbsToWcs(DRM_CHARSET_UTF8,
                        fullname,
                        nameLen,
                        nameUcs2,
                        MAX_FILENAME_LEN,
                        &bytesConsumed);
    fileRes = DRM_file_open(nameUcs2,
                            nameLen,
                            DRM_FILE_MODE_READ,
                            &handle);
    if (DRM_FILE_SUCCESS != fileRes) {
        if (GET_ALL_RO == option || GET_A_RO == option)
            return FALSE;

        if (GET_ROAMOUNT == option) {
            *RoAmount = -1;
            return TRUE;
        }
    }

    DRM_file_close(handle);
    DRM_file_open(nameUcs2,
                nameLen,
                DRM_FILE_MODE_READ | DRM_FILE_MODE_WRITE,
                &handle);

    switch(option) {
    case GET_ROAMOUNT:
        if (DRM_FILE_FAILURE == DRM_file_read(handle, (uint8_t*)RoAmount, sizeof(int32_t))) {
            DRM_file_close(handle);
            return FALSE;
        }
        break;
    case GET_ALL_RO:
        DRM_file_setPosition(handle, sizeof(int32_t));

        if (DRM_FILE_FAILURE == DRM_file_read(handle, (uint8_t*)Ro, (*RoAmount) * sizeof(T_DRM_Rights))) {
            DRM_file_close(handle);
            return FALSE;
        }
        break;
    case SAVE_ALL_RO:
        if (DRM_FILE_FAILURE == DRM_file_write(handle, (uint8_t*)RoAmount, sizeof(int32_t))) {
            DRM_file_close(handle);
            return FALSE;
        }

        if (NULL != Ro && *RoAmount >= 1) {
            if (DRM_FILE_FAILURE == DRM_file_write(handle, (uint8_t*) Ro, (*RoAmount) * sizeof(T_DRM_Rights))) {
                DRM_file_close(handle);
                return FALSE;
            }
        }
        break;
    case GET_A_RO:
        DRM_file_setPosition(handle, sizeof(int32_t) + (*RoAmount - 1) * sizeof(T_DRM_Rights));

        if (DRM_FILE_FAILURE == DRM_file_read(handle, (uint8_t*)Ro, sizeof(T_DRM_Rights))) {
            DRM_file_close(handle);
            return FALSE;
        }
        break;
    case SAVE_A_RO:
        DRM_file_setPosition(handle, sizeof(int32_t) + (*RoAmount - 1) * sizeof(T_DRM_Rights));

        if (DRM_FILE_FAILURE == DRM_file_write(handle, (uint8_t*)Ro, sizeof(T_DRM_Rights))) {
            DRM_file_close(handle);
            return FALSE;
        }

        DRM_file_setPosition(handle, 0);
        if (DRM_FILE_FAILURE == DRM_file_read(handle, (uint8_t*)&tmpRoAmount, sizeof(int32_t))) {
            DRM_file_close(handle);
            return FALSE;
        }
        if (tmpRoAmount < *RoAmount) {
            DRM_file_setPosition(handle, 0);
            DRM_file_write(handle, (uint8_t*)RoAmount, sizeof(int32_t));
        }
        break;
    default:
        DRM_file_close(handle);
        return FALSE;
    }

    DRM_file_close(handle);
    return TRUE;
}

int32_t drm_appendRightsInfo(T_DRM_Rights* rights)
{
    int32_t id;
    int32_t roAmount;

    if (NULL == rights)
        return FALSE;

    drm_acquireId(rights->uid, &id);

    if (FALSE == drm_writeOrReadInfo(id, NULL, &roAmount, GET_ROAMOUNT))
        return FALSE;

    if (-1 == roAmount)
        roAmount = 0;

    /* The RO amount increase */
    roAmount++;

    /* Save the rights information */
    if (FALSE == drm_writeOrReadInfo(id, rights, &roAmount, SAVE_A_RO))
        return FALSE;

    return TRUE;
}

int32_t drm_getMaxIdFromUidTxt()
{
    uint8_t idStr[8];
    int32_t idMax = 0;
    uint16_t nameUcs2[MAX_FILENAME_LEN] = {0};
    int32_t nameLen = 0;
    int32_t bytesConsumed;
    int32_t handle;
    int32_t fileRes;

    /* convert in ucs2 */
    nameLen = strlen(DRM_UID_FILE_PATH);
    nameLen = DRM_i18n_mbsToWcs(DRM_CHARSET_UTF8,
                        (uint8_t *)DRM_UID_FILE_PATH,
                        nameLen,
                        nameUcs2,
                        MAX_FILENAME_LEN,
                        &bytesConsumed);
    fileRes = DRM_file_open(nameUcs2,
                        nameLen,
                        DRM_FILE_MODE_READ,
                        &handle);

    /* this means the uid.txt file is not exist, so there is not any DRM object */
    if (DRM_FILE_SUCCESS != fileRes)
        return 0;

    if (!drm_getString(idStr, 8, handle)) {
        DRM_file_close(handle);
        return -1;
    }
    DRM_file_close(handle);

    idMax = atoi((char *)idStr);
    return idMax;
}

int32_t drm_removeIdInfoFile(int32_t id)
{
    uint8_t filename[MAX_FILENAME_LEN] = {0};
    uint16_t nameUcs2[MAX_FILENAME_LEN];
    int32_t nameLen = 0;
    int32_t bytesConsumed;

    if (id <= 0)
        return FALSE;

    sprintf((char *)filename, ANDROID_DRM_CORE_PATH"%d"EXTENSION_NAME_INFO, id);

    /* convert in ucs2 */
    nameLen = strlen((char *)filename);
    nameLen = DRM_i18n_mbsToWcs(DRM_CHARSET_UTF8,
                        filename,
                        nameLen,
                        nameUcs2,
                        MAX_FILENAME_LEN,
                        &bytesConsumed);
    if (DRM_FILE_SUCCESS != DRM_file_delete(nameUcs2, nameLen))
        return FALSE;

    return TRUE;
}

int32_t drm_updateUidTxtWhenDelete(int32_t id)
{
    uint16_t nameUcs2[MAX_FILENAME_LEN];
    int32_t nameLen = 0;
    int32_t bytesConsumed;
    int32_t handle;
    int32_t fileRes;
    int32_t bufferLen;
    uint8_t *buffer;
    uint8_t idStr[8];
    int32_t idMax;

    if (id <= 0)
        return FALSE;

    nameLen = strlen(DRM_UID_FILE_PATH);
    nameLen = DRM_i18n_mbsToWcs(DRM_CHARSET_UTF8,
                        (uint8_t *)DRM_UID_FILE_PATH,
                        nameLen,
                        nameUcs2,
                        MAX_FILENAME_LEN,
                        &bytesConsumed);
    bufferLen = DRM_file_getFileLength(nameUcs2, nameLen);
    if (bufferLen <= 0)
        return FALSE;

    buffer = (uint8_t *)malloc(bufferLen);
    if (NULL == buffer)
        return FALSE;

    fileRes = DRM_file_open(nameUcs2,
                            nameLen,
                            DRM_FILE_MODE_READ,
                            &handle);
    if (DRM_FILE_SUCCESS != fileRes) {
        free(buffer);
        return FALSE;
    }

    drm_getString(idStr, 8, handle);
    idMax = atoi((char *)idStr);

    bufferLen -= strlen((char *)idStr);
    fileRes = DRM_file_read(handle, buffer, bufferLen);
    buffer[bufferLen] = '\0';
    DRM_file_close(handle);

    /* handle this buffer */
    {
        uint8_t *pStart, *pEnd;
        int32_t i, movLen;

        pStart = buffer;
        pEnd = pStart;
        for (i = 0; i < id; i++) {
            if (pEnd != pStart)
                pStart = ++pEnd;
            while ('\n' != *pEnd)
                pEnd++;
            if (pStart == pEnd)
                pStart--;
        }
        movLen = bufferLen - (pEnd - buffer);
        memmove(pStart, pEnd, movLen);
        bufferLen -= (pEnd - pStart);
    }

    if (DRM_FILE_SUCCESS != DRM_file_delete(nameUcs2, nameLen)) {
        free(buffer);
        return FALSE;
    }

    fileRes = DRM_file_open(nameUcs2,
        nameLen,
        DRM_FILE_MODE_WRITE,
        &handle);
    if (DRM_FILE_SUCCESS != fileRes) {
        free(buffer);
        return FALSE;
    }
    sprintf((char *)idStr, "%d", idMax);
    drm_putString(idStr, handle);
    DRM_file_write(handle, (uint8_t*)"\n", 1);
    DRM_file_write(handle, buffer, bufferLen);
    free(buffer);
    DRM_file_close(handle);
    return TRUE;
}

int32_t drm_getKey(uint8_t* uid, uint8_t* KeyValue)
{
    T_DRM_Rights ro;
    int32_t id, roAmount;

    if (NULL == uid || NULL == KeyValue)
        return FALSE;

    if (FALSE == drm_readFromUidTxt(uid, &id, GET_ID))
        return FALSE;

    if (FALSE == drm_writeOrReadInfo(id, NULL, &roAmount, GET_ROAMOUNT))
        return FALSE;

    if (roAmount <= 0)
        return FALSE;

    memset(&ro, 0, sizeof(T_DRM_Rights));
    roAmount = 1;
    if (FALSE == drm_writeOrReadInfo(id, &ro, &roAmount, GET_A_RO))
        return FALSE;

    memcpy(KeyValue, ro.KeyValue, DRM_KEY_LEN);
    return TRUE;
}

void drm_discardPaddingByte(uint8_t *decryptedBuf, int32_t *decryptedBufLen)
{
    int32_t tmpLen = *decryptedBufLen;
    int32_t i;

    if (NULL == decryptedBuf || *decryptedBufLen < 0)
        return;

    /* Check whether the last several bytes are padding or not */
    for (i = 1; i < decryptedBuf[tmpLen - 1]; i++) {
        if (decryptedBuf[tmpLen - 1 - i] != decryptedBuf[tmpLen - 1])
            break; /* Not the padding bytes */
    }
    if (i == decryptedBuf[tmpLen - 1]) /* They are padding bytes */
        *decryptedBufLen = tmpLen - i;
    return;
}

int32_t drm_aesDecBuffer(uint8_t * Buffer, int32_t * BufferLen, AES_KEY *key)
{
    uint8_t dbuf[3 * DRM_ONE_AES_BLOCK_LEN], buf[DRM_ONE_AES_BLOCK_LEN];
    uint64_t i, len, wlen = DRM_ONE_AES_BLOCK_LEN, curLen, restLen;
    uint8_t *pTarget, *pTargetHead;

    pTargetHead = Buffer;
    pTarget = Buffer;
    curLen = 0;
    restLen = *BufferLen;

    if (restLen > 2 * DRM_ONE_AES_BLOCK_LEN) {
        len = 2 * DRM_ONE_AES_BLOCK_LEN;
    } else {
        len = restLen;
    }
    memcpy(dbuf, Buffer, (size_t)len);
    restLen -= len;
    Buffer += len;

    if (len < 2 * DRM_ONE_AES_BLOCK_LEN) { /* The original file is less than one block in length */
        len -= DRM_ONE_AES_BLOCK_LEN;
        /* Decrypt from position len to position len + DRM_ONE_AES_BLOCK_LEN */
        AES_decrypt((dbuf + len), (dbuf + len), key);

        /* Undo the CBC chaining */
        for (i = 0; i < len; ++i)
            dbuf[i] ^= dbuf[i + DRM_ONE_AES_BLOCK_LEN];

        /* Output the decrypted bytes */
        memcpy(pTarget, dbuf, (size_t)len);
        pTarget += len;
    } else {
        uint8_t *b1 = dbuf, *b2 = b1 + DRM_ONE_AES_BLOCK_LEN, *b3 = b2 + DRM_ONE_AES_BLOCK_LEN, *bt;

        for (;;) { /* While some ciphertext remains, prepare to decrypt block b2 */
            /* Read in the next block to see if ciphertext stealing is needed */
            b3 = Buffer;
            if (restLen > DRM_ONE_AES_BLOCK_LEN) {
                len = DRM_ONE_AES_BLOCK_LEN;
            } else {
                len = restLen;
            }
            restLen -= len;
            Buffer += len;

            /* Decrypt the b2 block */
            AES_decrypt((uint8_t *)b2, buf, key);

            if (len == 0 || len == DRM_ONE_AES_BLOCK_LEN) { /* No ciphertext stealing */
                /* Unchain CBC using the previous ciphertext block in b1 */
                for (i = 0; i < DRM_ONE_AES_BLOCK_LEN; ++i)
                    buf[i] ^= b1[i];
            } else { /* Partial last block - use ciphertext stealing */
                wlen = len;
                /* Produce last 'len' bytes of plaintext by xoring with */
                /* The lowest 'len' bytes of next block b3 - C[N-1] */
                for (i = 0; i < len; ++i)
                    buf[i] ^= b3[i];

                /* Reconstruct the C[N-1] block in b3 by adding in the */
                /* Last (DRM_ONE_AES_BLOCK_LEN - len) bytes of C[N-2] in b2 */
                for (i = len; i < DRM_ONE_AES_BLOCK_LEN; ++i)
                    b3[i] = buf[i];

                /* Decrypt the C[N-1] block in b3 */
                AES_decrypt((uint8_t *)b3, (uint8_t *)b3, key);

                /* Produce the last but one plaintext block by xoring with */
                /* The last but two ciphertext block */
                for (i = 0; i < DRM_ONE_AES_BLOCK_LEN; ++i)
                    b3[i] ^= b1[i];

                /* Write decrypted plaintext blocks */
                memcpy(pTarget, b3, DRM_ONE_AES_BLOCK_LEN);
                pTarget += DRM_ONE_AES_BLOCK_LEN;
            }

            /* Write the decrypted plaintext block */
            memcpy(pTarget, buf, (size_t)wlen);
            pTarget += wlen;

            if (len != DRM_ONE_AES_BLOCK_LEN) {
                *BufferLen = pTarget - pTargetHead;
                return 0;
            }

            /* Advance the buffer pointers */
            bt = b1, b1 = b2, b2 = b3, b3 = bt;
        }
    }
    return 0;
}

int32_t drm_updateDcfDataLen(uint8_t* pDcfLastData, uint8_t* keyValue, int32_t* moreBytes)
{
    AES_KEY key;
    int32_t len = DRM_TWO_AES_BLOCK_LEN;

    if (NULL == pDcfLastData || NULL == keyValue)
        return FALSE;

    AES_set_decrypt_key(keyValue, DRM_KEY_LEN * 8, &key);

    if (drm_aesDecBuffer(pDcfLastData, &len, &key) < 0)
        return FALSE;

    drm_discardPaddingByte(pDcfLastData, &len);

    *moreBytes = DRM_TWO_AES_BLOCK_LEN - len;

    return TRUE;
}
