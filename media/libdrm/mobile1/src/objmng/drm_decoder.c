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

#include <objmng/drm_decoder.h>

/* global variables */
static const uint8_t * base64_alphabet = (const uint8_t *)"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

#define SKIP_CRLF(p) while('\r' == *(p) || '\n' == *(p)) \
                         p++

static int8_t get_alphabet_index(int8_t ch)
{
    uint8_t * tmp;

    if ('=' == ch)
        return 64;

    tmp = (uint8_t *)strchr((const char *)base64_alphabet, ch);
    if (NULL == tmp)
        return -1;

    return (int8_t)(tmp - base64_alphabet);
}

/* See drm_decoder.h */
int32_t drm_decodeBase64(uint8_t * dest, int32_t destLen, uint8_t * src, int32_t * srcLen)
{
    int32_t maxDestSize, i, maxGroup;
    uint8_t *pDest, *pSrc;
    int8_t tpChar;

    if (NULL == src || NULL == srcLen || *srcLen <= 0 || destLen < 0)
        return -1;

    maxDestSize = (*srcLen) * 3/4;
    if (NULL == dest || 0 == destLen)
        return maxDestSize;

    if (destLen < maxDestSize)
        maxDestSize = destLen;
    maxGroup = maxDestSize/3;

    pDest = dest;   /* start to decode src to dest */
    pSrc = src;
    for (i = 0; i < maxGroup && *srcLen - (pSrc - src) >= 4; i++) {
        SKIP_CRLF(pSrc);
        if (pSrc - src >= *srcLen)
            break;
        tpChar = get_alphabet_index(*pSrc);       /* to first byte */
        if (-1 == tpChar || 64 == tpChar)
            return -1;
        pDest[0] = tpChar << 2;
        pSrc++;
        SKIP_CRLF(pSrc);
        tpChar = get_alphabet_index(*pSrc);
        if (-1 == tpChar || 64 == tpChar)
            return -1;
        pDest[0] |= (tpChar >> 4);
        pDest[1] = tpChar << 4;                     /* to second byte */
        pSrc++;
        SKIP_CRLF(pSrc);
        tpChar = get_alphabet_index(*pSrc);
        if (-1 == tpChar)
            return -1;
        if (64 == tpChar)           /* end */
            return pDest - dest + 1;
        pDest[1] |= (tpChar >> 2);
        pDest[2] = tpChar << 6;                     /* to third byte */
        pSrc++;
        SKIP_CRLF(pSrc);
        tpChar = get_alphabet_index(*pSrc);
        if (-1 == tpChar)
            return -1;
        if (64 == tpChar)           /* end */
            return pDest - dest + 2;
        pDest[2] |= tpChar;
        pDest += 3;
        pSrc++;
    }
    *srcLen = pSrc - src;
    return pDest - dest;
}
