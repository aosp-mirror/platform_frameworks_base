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


#ifndef __DRM_I18N_H__
#define __DRM_I18N_H__

#ifdef __cplusplus
extern "C" {
#endif

#include <drm_common_types.h>

/**
 * @name Charset value defines
 * @ingroup i18n
 *
 * Charset value defines
 * see http://msdn.microsoft.com/library/default.asp?url=/library/en-us/intl/unicode_81rn.asp
 */
typedef enum {
    DRM_CHARSET_GBK        = 936,      /** Simplified Chinese GBK (CP936) */
    DRM_CHARSET_GB2312     = 20936,    /** Simplified Chinese GB2312 (CP936) */
    DRM_CHARSET_BIG5       = 950,      /** BIG5 (CP950) */
    DRM_CHARSET_LATIN1     = 28591,    /** ISO 8859-1, Latin 1 */
    DRM_CHARSET_LATIN2     = 28592,    /** ISO 8859-2, Latin 2 */
    DRM_CHARSET_LATIN3     = 28593,    /** ISO 8859-3, Latin 3 */
    DRM_CHARSET_LATIN4     = 28594,    /** ISO 8859-4, Latin 4 */
    DRM_CHARSET_CYRILLIC   = 28595,    /** ISO 8859-5, Cyrillic */
    DRM_CHARSET_ARABIC     = 28596,    /** ISO 8859-6, Arabic */
    DRM_CHARSET_GREEK      = 28597,    /** ISO 8859-7, Greek */
    DRM_CHARSET_HEBREW     = 28598,    /** ISO 8859-8, Hebrew */
    DRM_CHARSET_LATIN5     = 28599,    /** ISO 8859-9, Latin 5 */
    DRM_CHARSET_LATIN6     = 865,      /** ISO 8859-10, Latin 6 (not sure here) */
    DRM_CHARSET_THAI       = 874,      /** ISO 8859-11, Thai */
    DRM_CHARSET_LATIN7     = 1257,     /** ISO 8859-13, Latin 7 (not sure here) */
    DRM_CHARSET_LATIN8     = 38598,    /** ISO 8859-14, Latin 8 (not sure here) */
    DRM_CHARSET_LATIN9     = 28605,    /** ISO 8859-15, Latin 9 */
    DRM_CHARSET_LATIN10    = 28606,    /** ISO 8859-16, Latin 10 */
    DRM_CHARSET_UTF8       = 65001,    /** UTF-8 */
    DRM_CHARSET_UTF16LE    = 1200,     /** UTF-16 LE */
    DRM_CHARSET_UTF16BE    = 1201,     /** UTF-16 BE */
    DRM_CHARSET_HINDI      = 57002,    /** Hindi/Mac Devanagari */
    DRM_CHARSET_UNSUPPORTED = -1
} DRM_Charset_t;

/**
 * Convert multibyte string of specified charset to unicode string.
 * Note NO terminating '\0' will be appended to the output unicode string.
 *
 * @param charset Charset of the multibyte string.
 * @param mbs Multibyte string to be converted.
 * @param mbsLen Number of the bytes (in mbs) to be converted.
 * @param wcsBuf Buffer for the converted unicode characters.
 *               If wcsBuf is NULL, the function returns the number of unicode
 *               characters required for the buffer.
 * @param bufSizeInWideChar The size (in wide char) of wcsBuf
 * @param bytesConsumed The number of bytes in mbs that have been successfully
 *                      converted. The value of *bytesConsumed is undefined
 *                      if wcsBuf is NULL.
 *
 * @return Number of the successfully converted unicode characters if wcsBuf
 *         is not NULL. If wcsBuf is NULL, returns required unicode buffer
 *         size. -1 for unrecoverable errors.
 */
int32_t DRM_i18n_mbsToWcs(DRM_Charset_t charset,
        const uint8_t *mbs, int32_t mbsLen,
        uint16_t *wcsBuf, int32_t bufSizeInWideChar,
        int32_t *bytesConsumed);

/**
 * Convert unicode string to multibyte string with specified charset.
 * Note NO terminating '\0' will be appended to the output multibyte string.
 *
 * @param charset Charset of the multibyte string to be converted to.
 * @param wcs     Unicode string to be converted.
 * @param wcsLen  Number of the unicode characters (in wcs) to be converted.
 * @param mbsBuf  Buffer for converted multibyte characters.
 *                If mbsBuf is NULL, the function returns the number of bytes
 *                required for the buffer.
 * @param bufSizeInByte The size (in byte) of mbsBuf.
 *
 * @return Number of the successfully converted bytes.
 */
int32_t DRM_i18n_wcsToMbs(DRM_Charset_t charset,
        const uint16_t *wcs, int32_t wcsLen,
        uint8_t *mbsBuf, int32_t bufSizeInByte);

#ifdef __cplusplus
}
#endif

#endif

