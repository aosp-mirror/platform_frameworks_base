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

#include <objmng/drm_i18n.h>

#define IS_GB2312_HIGH_BYTE(c)  ((c) >= 0xA1 && (c) <= 0xF7)
#define IS_GB2312_LOW_BYTE(c)   ((c) >= 0xA1 && (c) <= 0xFE)
#define IS_GBK_HIGH_BYTE(c)     ((c) >= 0x81 && (c) <= 0xFE)
#define IS_GBK_LOW_BYTE(c)      ((c) >= 0x40 && (c) <= 0xFE && (c) != 0x7F)
#define IS_BIG5_HIGH_BYTE(c)    ((c) >= 0xA1 && (c) <= 0xF9)
#define IS_BIG5_LOW_BYTE(c)     (((c) >= 0x40 && (c) <= 0x7E) \
                                 || ((c) >= 0xA1 && (c) <= 0xFE))
#define IS_ASCII(c)             ((c) <= 127)

#define INVALID_UNICODE         0xFFFD

#define I18N_LATIN1_SUPPORT
#define I18N_UTF8_UTF16_SUPPORT


/**
 * Simply convert ISO 8859-1 (latin1) to unicode
 */
static int32_t latin1ToWcs(const uint8_t *mbs, int32_t mbsLen,
        uint16_t *wcsBuf, int32_t bufSizeInWideChar,
        int32_t *bytesConsumed);

/**
 * Convert one unicode char to ISO 8859-1 (latin1) byte
 */
static int32_t wcToLatin1(uint16_t wc, uint8_t * mbs, int32_t bufSize);

/**
 * Convert UTF-8 to unicode
 */
static int32_t utf8ToWcs(const uint8_t *mbs, int32_t mbsLen,
        uint16_t *wcsBuf, int32_t bufSizeInWideChar,
        int32_t *bytesConsumed);

/**
 * Convert one unicode char to UTF-8 bytes
 */
static int32_t wcToUtf8(uint16_t wc, uint8_t * mbs, int32_t bufSize);

/**
 * Convert UTF-16 BE to unicode
 */
static int32_t utf16beToWcs(const uint8_t *mbs, int32_t mbsLen,
        uint16_t *wcsBuf, int32_t bufSizeInWideChar,
        int32_t *bytesConsumed);

/**
 * Convert one unicode char to UTF-16 BE bytes
 */
static int32_t wcToUtf16be(uint16_t wc, uint8_t * mbs, int32_t bufSize);

/**
 * Convert UTF-16 LE to unicode
 */
static int32_t utf16leToWcs(const uint8_t *mbs, int32_t mbsLen,
        uint16_t *wcsBuf, int32_t bufSizeInWideChar,
        int32_t *bytesConsumed);

/**
 * Convert one unicode char to UTF-16 LE bytes
 */
static int32_t wcToUtf16le(uint16_t wc, uint8_t * mbs, int32_t bufSize);

/*
 * see drm_i18n.h
 */
int32_t DRM_i18n_mbsToWcs(DRM_Charset_t charset,
        const uint8_t *mbs, int32_t mbsLen,
        uint16_t *wcsBuf, int32_t bufSizeInWideChar,
        int32_t *bytesConsumed)
{
    switch (charset)
    {
#ifdef I18N_GB2312_SUPPORT
        case DRM_CHARSET_GB2312:
            return gb2312ToWcs(mbs, mbsLen, wcsBuf, bufSizeInWideChar, bytesConsumed);
#endif
#ifdef I18N_GBK_SUPPORT
        case DRM_CHARSET_GBK:
            return gbkToWcs(mbs, mbsLen, wcsBuf, bufSizeInWideChar, bytesConsumed);
#endif
#ifdef I18N_BIG5_SUPPORT
        case DRM_CHARSET_BIG5:
            return big5ToWcs(mbs, mbsLen, wcsBuf, bufSizeInWideChar, bytesConsumed);
#endif
#ifdef I18N_LATIN1_SUPPORT
        case DRM_CHARSET_LATIN1:
            return latin1ToWcs(mbs, mbsLen, wcsBuf, bufSizeInWideChar, bytesConsumed);
#endif
#ifdef I18N_ISO8859X_SUPPORT
        case DRM_CHARSET_LATIN2:
        case DRM_CHARSET_LATIN3:
        case DRM_CHARSET_LATIN4:
        case DRM_CHARSET_CYRILLIC:
        case DRM_CHARSET_ARABIC:
        case DRM_CHARSET_GREEK:
        case DRM_CHARSET_HEBREW:
        case DRM_CHARSET_LATIN5:
        case DRM_CHARSET_LATIN6:
        case DRM_CHARSET_THAI:
        case DRM_CHARSET_LATIN7:
        case DRM_CHARSET_LATIN8:
        case DRM_CHARSET_LATIN9:
        case DRM_CHARSET_LATIN10:
            return iso8859xToWcs(charset, mbs, mbsLen, wcsBuf, bufSizeInWideChar, bytesConsumed);
#endif
#ifdef I18N_UTF8_UTF16_SUPPORT
        case DRM_CHARSET_UTF8:
            return utf8ToWcs(mbs, mbsLen, wcsBuf, bufSizeInWideChar, bytesConsumed);
        case DRM_CHARSET_UTF16BE:
            return utf16beToWcs(mbs, mbsLen, wcsBuf, bufSizeInWideChar, bytesConsumed);
        case DRM_CHARSET_UTF16LE:
            return utf16leToWcs(mbs, mbsLen, wcsBuf, bufSizeInWideChar, bytesConsumed);
#endif
        default:
            return -1;
    }
}

/*
 * see drm_i18n.h
 */
int32_t DRM_i18n_wcsToMbs(DRM_Charset_t charset,
        const uint16_t *wcs, int32_t wcsLen,
        uint8_t *mbsBuf, int32_t bufSizeInByte)
{
    int32_t (* wcToMbFunc)(uint16_t, uint8_t *, int32_t);
    int32_t charIndex = 0;
    int32_t numMultiBytes = 0;

    switch (charset)
    {
#ifdef I18N_LATIN1_SUPPORT
        case DRM_CHARSET_LATIN1:
            wcToMbFunc = wcToLatin1;
            break;
#endif
#ifdef I18N_UTF8_UTF16_SUPPORT
        case DRM_CHARSET_UTF8:
            wcToMbFunc = wcToUtf8;
            break;
        case DRM_CHARSET_UTF16BE:
            wcToMbFunc = wcToUtf16be;
            break;
        case DRM_CHARSET_UTF16LE:
            wcToMbFunc = wcToUtf16le;
            break;
#endif
#ifdef I18N_ISO8859X_SUPPORT
        case DRM_CHARSET_LATIN2:
        case DRM_CHARSET_LATIN3:
        case DRM_CHARSET_LATIN4:
        case DRM_CHARSET_CYRILLIC:
        case DRM_CHARSET_ARABIC:
        case DRM_CHARSET_GREEK:
        case DRM_CHARSET_HEBREW:
        case DRM_CHARSET_LATIN5:
        case DRM_CHARSET_LATIN6:
        case DRM_CHARSET_THAI:
        case DRM_CHARSET_LATIN7:
        case DRM_CHARSET_LATIN8:
        case DRM_CHARSET_LATIN9:
        case DRM_CHARSET_LATIN10:
            return wcsToIso8859x(charset, wcs, wcsLen, mbsBuf, bufSizeInByte);
#endif
        default:
            return -1;
    }

    if (mbsBuf) {
        while (numMultiBytes < bufSizeInByte && charIndex < wcsLen) {
            /* TODO: handle surrogate pair values here */
            int32_t mbLen = wcToMbFunc(wcs[charIndex],
                    &mbsBuf[numMultiBytes], bufSizeInByte - numMultiBytes);

            if (numMultiBytes + mbLen > bufSizeInByte) {
                /* Insufficient buffer. Don't update numMultiBytes */
                break;
            }
            charIndex++;
            numMultiBytes += mbLen;
        }
    } else {
        while (charIndex < wcsLen) {
            /* TODO: handle surrogate pair values here */
            numMultiBytes += wcToMbFunc(wcs[charIndex], NULL, 0);
            charIndex++;
        }
    }

    return numMultiBytes;
}


#ifdef I18N_LATIN1_SUPPORT

int32_t latin1ToWcs(const uint8_t *mbs, int32_t mbsLen,
        uint16_t *wcsBuf, int32_t bufSizeInWideChar,
        int32_t *bytesConsumed)
{
    int32_t charsToConvert;
    int32_t len;

    if (wcsBuf == NULL) {
        return mbsLen;
    }

    len = charsToConvert = mbsLen > bufSizeInWideChar ? bufSizeInWideChar : mbsLen;
    if (len < 0)
        return 0;
    while (len--) {
        *wcsBuf++ = *mbs++;
    }

    if (bytesConsumed)
        *bytesConsumed = charsToConvert;

    return charsToConvert;
}

int32_t wcToLatin1(uint16_t wc, uint8_t * mbs, int32_t bufSize)
{
    uint8_t ch;

    if (wc < 0x100) {
        ch = (uint8_t)(wc & 0xff);
    } else {
        ch = '?';
    }
    if (mbs && bufSize > 0)
        *mbs = ch;
    return 1;
}

#endif /* I18N_LATIN1_SUPPORT */

#ifdef I18N_UTF8_UTF16_SUPPORT

int32_t utf8ToWcs(const uint8_t *mbs, int32_t mbsLen,
        uint16_t *wcsBuf, int32_t bufSizeInWideChar,
        int32_t *bytesConsumed)
{
    int32_t charsConverted = 0;
    int32_t i = 0;
    int32_t wideChar;

    if (wcsBuf == NULL) {
        /* No conversion but we're still going to calculate bytesConsumed */
        bufSizeInWideChar = mbsLen * 2;
    }

    while((i < mbsLen) && (charsConverted < bufSizeInWideChar)) {
        uint8_t ch = mbs[i];
        uint8_t ch2, ch3, ch4;

        wideChar = -1;

        if(IS_ASCII(ch)) {
            wideChar = ch;
            i++;
        } else if ((ch & 0xc0) == 0xc0) {
            int utfStart = i;
            if ((ch & 0xe0) == 0xc0) {
                /* 2 byte sequence */
                if (i + 1 < mbsLen && ((ch2 = mbs[i + 1]) & 0xc0) == 0x80) {
                    wideChar = (uint16_t)(((ch & 0x1F) << 6) | (ch2 & 0x3F));
                    i += 2;
                } else {
                    /* skip incomplete sequence */
                    i++;
                }
            } else if ((ch & 0xf0) == 0xe0) {
                /* 3 byte sequence */
                if (i + 2 < mbsLen
                        && ((ch2 = mbs[i + 1]) & 0xc0) == 0x80
                        && ((ch3 = mbs[i + 2]) & 0xc0) == 0x80) {
                    wideChar = (uint16_t)(((ch & 0x0F) << 12) | ((ch2 & 0x3F) << 6) | (ch3 & 0x3F));
                    i += 3;
                } else {
                    /* skip incomplete sequence (up to 2 bytes) */
                    i++;
                    if (i < mbsLen && (mbs[i] & 0xc0) == 0x80)
                        i++;
                }
            } else if ((ch & 0xf8) == 0xf0) {
                /* 4 byte sequence */
                if (i + 3 < mbsLen
                        && ((ch2 = mbs[i + 1]) & 0xc0) == 0x80
                        && ((ch3 = mbs[i + 2]) & 0xc0) == 0x80
                        && ((ch4 = mbs[i + 3]) & 0xc0) == 0x80) {
                    /* FIXME: we do NOT support U+10000 - U+10FFFF for now.
                     *        leave it as 0xFFFD. */
                    wideChar = INVALID_UNICODE;
                    i += 4;
                } else {
                    /* skip incomplete sequence (up to 3 bytes) */
                    i++;
                    if (i < mbsLen && (mbs[i] & 0xc0) == 0x80) {
                        i++;
                        if (i < mbsLen && (mbs[i] & 0xc0) == 0x80) {
                            i++;
                        }
                    }
                }
            } else {
                /* invalid */
                i++;
            }
            if (i >= mbsLen && wideChar == -1) {
                /* Possible incomplete UTF-8 sequence at the end of mbs.
                 * Leave it to the caller.
                 */
                i = utfStart;
                break;
            }
        } else {
            /* invalid */
            i++;
        }
        if(wcsBuf) {
            if (wideChar == -1)
                wideChar = INVALID_UNICODE;
            wcsBuf[charsConverted] = (uint16_t)wideChar;
        }
        charsConverted++;
    }

    if (bytesConsumed)
        *bytesConsumed = i;

    return charsConverted;
}

int32_t wcToUtf8(uint16_t wc, uint8_t * mbs, int32_t bufSize)
{
    if (wc <= 0x7f) {
        if (mbs && (bufSize >= 1)) {
            *mbs = (uint8_t)wc;
        }
        return 1;
    } else if (wc <= 0x7ff) {
        if (mbs && (bufSize >= 2)) {
            *mbs++ = (uint8_t)((wc >> 6) | 0xc0);
            *mbs = (uint8_t)((wc & 0x3f) | 0x80);
        }
        return 2;
    } else {
        if (mbs && (bufSize >= 3)) {
            *mbs++ = (uint8_t)((wc >> 12) | 0xe0);
            *mbs++ = (uint8_t)(((wc >> 6) & 0x3f)| 0x80);
            *mbs = (uint8_t)((wc & 0x3f) | 0x80);
        }
        return 3;
    }
}

int32_t utf16beToWcs(const uint8_t *mbs, int32_t mbsLen,
        uint16_t *wcsBuf, int32_t bufSizeInWideChar,
        int32_t *bytesConsumed)
{
    int32_t charsToConvert;
    int32_t len;

    if (wcsBuf == NULL) {
        return mbsLen / 2;
    }

    len = charsToConvert = (mbsLen / 2) > bufSizeInWideChar ? bufSizeInWideChar : (mbsLen / 2);
    while (len--) {
        /* TODO: handle surrogate pair values */
        *wcsBuf++ = (uint16_t)((*mbs << 8) | *(mbs + 1));
        mbs += 2;
    }

    if (bytesConsumed)
        *bytesConsumed = charsToConvert * 2;

    return charsToConvert;
}

int32_t wcToUtf16be(uint16_t wc, uint8_t * mbs, int32_t bufSize)
{
    if (mbs && bufSize >= 2) {
        /* TODO: handle surrogate pair values */
        *mbs = (uint8_t)(wc >> 8);
        *(mbs + 1) = (uint8_t)(wc & 0xff);
    }
    return 2;
}

int32_t utf16leToWcs(const uint8_t *mbs, int32_t mbsLen,
        uint16_t *wcsBuf, int32_t bufSizeInWideChar,
        int32_t *bytesConsumed)
{
    int32_t charsToConvert;
    int32_t len;

    if (wcsBuf == NULL) {
        return mbsLen / 2;
    }

    len = charsToConvert = (mbsLen / 2) > bufSizeInWideChar ? bufSizeInWideChar : (mbsLen / 2);
    while (len--) {
        /* TODO: handle surrogate pair values */
        *wcsBuf++ = (uint16_t)(*mbs | (*(mbs + 1) << 8));
        mbs += 2;
    }

    if (bytesConsumed)
        *bytesConsumed = charsToConvert * 2;

    return charsToConvert;
}

int32_t wcToUtf16le(uint16_t wc, uint8_t * mbs, int32_t bufSize)
{
    if (mbs && bufSize >= 2) {
        /* TODO: handle surrogate pair values */
        *mbs = (uint8_t)(wc & 0xff);
        *(mbs + 1) = (uint8_t)(wc >> 8);
    }
    return 2;
}

#endif /* I18N_UTF8_UTF16_SUPPORT */

