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

#include <parser_dcf.h>
#include <svc_drm.h>

static int32_t drm_parseUintVar(uint8_t * buffer, uint8_t * len)
{
    int32_t i;
    int32_t byteLen;
    int32_t sum;

    if (NULL == buffer)
        return DRM_UINT_VAR_ERR;

    byteLen = 0;
    while ((buffer[byteLen] & UINT_VAR_FLAG) > 0 && byteLen < MAX_UINT_VAR_BYTE) /* UINT_VAR_FLAG == 0x80 */
        byteLen++;

    if (byteLen >= MAX_UINT_VAR_BYTE) /* MAX_UINT_VAR_BYTE == 5 */
        return DRM_UINT_VAR_ERR; /* The var int is too large, and that is impossible */

    *len = (uint8_t)(byteLen + 1);
    sum = buffer[byteLen];
    for (i = byteLen - 1; i >= 0; i--)
        sum += ((buffer[i] & UINT_VAR_DATA) << 7 * (byteLen - i)); /* UINT_VAR_DATA == 0x7F */

    return sum;
}

/* See parser_dcf.h */
int32_t drm_dcfParser(uint8_t *buffer, int32_t bufferLen, T_DRM_DCF_Info *pDcfInfo,
                      uint8_t **ppEncryptedData)
{
    uint8_t *tmpBuf;
    uint8_t *pStart, *pEnd;
    uint8_t *pHeader, *pData;
    uint8_t varLen;

    if (NULL == buffer || bufferLen <= 0 || NULL == pDcfInfo)
        return FALSE;

    tmpBuf = buffer;
    /* 1. Parse the version, content-type and content-url */
    pDcfInfo->Version = *(tmpBuf++);
    if (0x01 != pDcfInfo->Version) /* Because it is OMA DRM v1.0, the vension must be 1 */
        return FALSE;

    pDcfInfo->ContentTypeLen = *(tmpBuf++);
    if (pDcfInfo->ContentTypeLen >= MAX_CONTENT_TYPE_LEN)
        return FALSE;

    pDcfInfo->ContentURILen = *(tmpBuf++);
    if (pDcfInfo->ContentURILen >= MAX_CONTENT_URI_LEN)
        return FALSE;

    strncpy((char *)pDcfInfo->ContentType, (char *)tmpBuf, pDcfInfo->ContentTypeLen);
    pDcfInfo->ContentType[MAX_CONTENT_TYPE_LEN - 1] = 0;
    tmpBuf += pDcfInfo->ContentTypeLen;
    strncpy((char *)pDcfInfo->ContentURI, (char *)tmpBuf, pDcfInfo->ContentURILen);
    pDcfInfo->ContentURI[MAX_CONTENT_URI_LEN - 1] = 0;
    tmpBuf += pDcfInfo->ContentURILen;

    /* 2. Get the headers length and data length */
    pDcfInfo->HeadersLen = drm_parseUintVar(tmpBuf, &varLen);
    if (DRM_UINT_VAR_ERR == pDcfInfo->HeadersLen)
        return FALSE;
    tmpBuf += varLen;
    pDcfInfo->DecryptedDataLen = DRM_UNKNOWN_DATA_LEN;
    pDcfInfo->EncryptedDataLen = drm_parseUintVar(tmpBuf, &varLen);
    if (DRM_UINT_VAR_ERR == pDcfInfo->EncryptedDataLen)
        return FALSE;
    tmpBuf += varLen;
    pHeader = tmpBuf;
    tmpBuf += pDcfInfo->HeadersLen;
    pData = tmpBuf;

    /* 3. Parse the headers */
    pStart = pHeader;
    while (pStart < pData) {
        pEnd = pStart;
        while ('\r' != *pEnd && pEnd < pData)
            pEnd++;

        if (0 == strncmp((char *)pStart, HEADER_ENCRYPTION_METHOD, HEADER_ENCRYPTION_METHOD_LEN)) {
            if ((pEnd - pStart - HEADER_ENCRYPTION_METHOD_LEN) >= MAX_ENCRYPTION_METHOD_LEN)
                return FALSE;
            strncpy((char *)pDcfInfo->Encryption_Method,
                         (char *)(pStart + HEADER_ENCRYPTION_METHOD_LEN),
                         pEnd - pStart - HEADER_ENCRYPTION_METHOD_LEN);
            pDcfInfo->Encryption_Method[MAX_ENCRYPTION_METHOD_LEN - 1] = 0;
        } else if (0 == strncmp((char *)pStart, HEADER_RIGHTS_ISSUER, HEADER_RIGHTS_ISSUER_LEN)) {
            if ((pEnd - pStart - HEADER_RIGHTS_ISSUER_LEN) >= MAX_RIGHTS_ISSUER_LEN)
                return FALSE;
            strncpy((char *)pDcfInfo->Rights_Issuer,
                         (char *)(pStart + HEADER_RIGHTS_ISSUER_LEN),
                         pEnd - pStart - HEADER_RIGHTS_ISSUER_LEN);
            pDcfInfo->Rights_Issuer[MAX_RIGHTS_ISSUER_LEN - 1] = 0;
        } else if (0 == strncmp((char *)pStart, HEADER_CONTENT_NAME, HEADER_CONTENT_NAME_LEN)) {
            if ((pEnd - pStart - HEADER_CONTENT_NAME_LEN) >= MAX_CONTENT_NAME_LEN)
                return FALSE;
            strncpy((char *)pDcfInfo->Content_Name,
                         (char *)(pStart + HEADER_CONTENT_NAME_LEN),
                         pEnd - pStart - HEADER_CONTENT_NAME_LEN);
            pDcfInfo->Content_Name[MAX_CONTENT_NAME_LEN - 1] = 0;
        } else if (0 == strncmp((char *)pStart, HEADER_CONTENT_DESCRIPTION, HEADER_CONTENT_DESCRIPTION_LEN)) {
            if ((pEnd - pStart - HEADER_CONTENT_DESCRIPTION_LEN) >= MAX_CONTENT_DESCRIPTION_LEN)
                return FALSE;
            strncpy((char *)pDcfInfo->ContentDescription,
                         (char *)(pStart + HEADER_CONTENT_DESCRIPTION_LEN),
                         pEnd - pStart - HEADER_CONTENT_DESCRIPTION_LEN);
            pDcfInfo->ContentDescription[MAX_CONTENT_DESCRIPTION_LEN - 1] = 0;
        } else if (0 == strncmp((char *)pStart, HEADER_CONTENT_VENDOR, HEADER_CONTENT_VENDOR_LEN)) {
            if ((pEnd - pStart - HEADER_CONTENT_VENDOR_LEN) >= MAX_CONTENT_VENDOR_LEN)
                return FALSE;
            strncpy((char *)pDcfInfo->ContentVendor,
                         (char *)(pStart + HEADER_CONTENT_VENDOR_LEN),
                         pEnd - pStart - HEADER_CONTENT_VENDOR_LEN);
            pDcfInfo->ContentVendor[MAX_CONTENT_VENDOR_LEN - 1] = 0;
        } else if (0 == strncmp((char *)pStart, HEADER_ICON_URI, HEADER_ICON_URI_LEN)) {
            if ((pEnd - pStart - HEADER_ICON_URI_LEN) >= MAX_ICON_URI_LEN)
                return FALSE;
            strncpy((char *)pDcfInfo->Icon_URI,
                         (char *)(pStart + HEADER_ICON_URI_LEN),
                         pEnd - pStart - HEADER_ICON_URI_LEN);
            pDcfInfo->Icon_URI[MAX_ICON_URI_LEN - 1] = 0;
        }

        if ('\n' == *(pEnd + 1))
            pStart = pEnd + 2;  /* Two bytes: a '\r' and a '\n' */
        else
            pStart = pEnd + 1;
    }

    /* 4. Give out the location of encrypted data */
    if (NULL != ppEncryptedData)
        *ppEncryptedData = pData;

    return TRUE;
}
