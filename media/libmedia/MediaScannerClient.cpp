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

#include <media/mediascanner.h>

#include <utils/StringArray.h>

#include "autodetect.h"
#include "unicode/ucnv.h"
#include "unicode/ustring.h"

namespace android {

MediaScannerClient::MediaScannerClient()
    :   mNames(NULL),
        mValues(NULL),
        mLocaleEncoding(kEncodingNone)
{
}

MediaScannerClient::~MediaScannerClient()
{
    delete mNames;
    delete mValues;
}

void MediaScannerClient::setLocale(const char* locale)
{
    if (!locale) return;

    if (!strncmp(locale, "ja", 2))
        mLocaleEncoding = kEncodingShiftJIS;
    else if (!strncmp(locale, "ko", 2))
        mLocaleEncoding = kEncodingEUCKR;
    else if (!strncmp(locale, "zh", 2)) {
        if (!strcmp(locale, "zh_CN")) {
            // simplified chinese for mainland China
            mLocaleEncoding = kEncodingGBK;
        } else {
            // assume traditional for non-mainland Chinese locales (Taiwan, Hong Kong, Singapore)
            mLocaleEncoding = kEncodingBig5;
        }
    }
}

void MediaScannerClient::beginFile()
{
    mNames = new StringArray;
    mValues = new StringArray;
}

status_t MediaScannerClient::addStringTag(const char* name, const char* value)
{
    if (mLocaleEncoding != kEncodingNone) {
        // don't bother caching strings that are all ASCII.
        // call handleStringTag directly instead.
        // check to see if value (which should be utf8) has any non-ASCII characters
        bool nonAscii = false;
        const char* chp = value;
        char ch;
        while ((ch = *chp++)) {
            if (ch & 0x80) {
                nonAscii = true;
                break;
            }
        }

        if (nonAscii) {
            // save the strings for later so they can be used for native encoding detection
            mNames->push_back(name);
            mValues->push_back(value);
            return true;
        }
        // else fall through
    }

    // autodetection is not necessary, so no need to cache the values
    // pass directly to the client instead
    return handleStringTag(name, value);
}

static uint32_t possibleEncodings(const char* s)
{
    uint32_t result = kEncodingAll;
    // if s contains a native encoding, then it was mistakenly encoded in utf8 as if it were latin-1
    // so we need to reverse the latin-1 -> utf8 conversion to get the native chars back
    uint8_t ch1, ch2;
    uint8_t* chp = (uint8_t *)s;

    while ((ch1 = *chp++)) {
        if (ch1 & 0x80) {
            ch2 = *chp++;
            ch1 = ((ch1 << 6) & 0xC0) | (ch2 & 0x3F);
            // ch1 is now the first byte of the potential native char

            ch2 = *chp++;
            if (ch2 & 0x80)
                ch2 = ((ch2 << 6) & 0xC0) | (*chp++ & 0x3F);
            // ch2 is now the second byte of the potential native char
            int ch = (int)ch1 << 8 | (int)ch2;
            result &= findPossibleEncodings(ch);
        }
        // else ASCII character, which could be anything
    }

    return result;
}

void MediaScannerClient::convertValues(uint32_t encoding)
{
    const char* enc = NULL;
    switch (encoding) {
        case kEncodingShiftJIS:
            enc = "shift-jis";
            break;
        case kEncodingGBK:
            enc = "gbk";
            break;
        case kEncodingBig5:
            enc = "Big5";
            break;
        case kEncodingEUCKR:
            enc = "EUC-KR";
            break;
    }

    if (enc) {
        UErrorCode status = U_ZERO_ERROR;

        UConverter *conv = ucnv_open(enc, &status);
        if (U_FAILURE(status)) {
            LOGE("could not create UConverter for %s\n", enc);
            return;
        }
        UConverter *utf8Conv = ucnv_open("UTF-8", &status);
        if (U_FAILURE(status)) {
            LOGE("could not create UConverter for UTF-8\n");
            ucnv_close(conv);
            return;
        }

        // for each value string, convert from native encoding to UTF-8
        for (int i = 0; i < mNames->size(); i++) {
            // first we need to untangle the utf8 and convert it back to the original bytes
            // since we are reducing the length of the string, we can do this in place
            uint8_t* src = (uint8_t *)mValues->getEntry(i);
            int len = strlen((char *)src);
            uint8_t* dest = src;

            uint8_t uch;
            while ((uch = *src++)) {
                if (uch & 0x80)
                    *dest++ = ((uch << 6) & 0xC0) | (*src++ & 0x3F);
                else
                    *dest++ = uch;
            }
            *dest = 0;

            // now convert from native encoding to UTF-8
            const char* source = mValues->getEntry(i);
            int targetLength = len * 3 + 1;
            char* buffer = new char[targetLength];
            if (!buffer)
                break;
            char* target = buffer;

            ucnv_convertEx(utf8Conv, conv, &target, target + targetLength,
                    &source, (const char *)dest, NULL, NULL, NULL, NULL, TRUE, TRUE, &status);
            if (U_FAILURE(status)) {
                LOGE("ucnv_convertEx failed: %d\n", status);
                mValues->setEntry(i, "???");
            } else {
                // zero terminate
                *target = 0;
                mValues->setEntry(i, buffer);
            }

            delete[] buffer;
        }

        ucnv_close(conv);
        ucnv_close(utf8Conv);
    }
}

void MediaScannerClient::endFile()
{
    if (mLocaleEncoding != kEncodingNone) {
        int size = mNames->size();
        uint32_t encoding = kEncodingAll;

        // compute a bit mask containing all possible encodings
        for (int i = 0; i < mNames->size(); i++)
            encoding &= possibleEncodings(mValues->getEntry(i));

        // if the locale encoding matches, then assume we have a native encoding.
        if (encoding & mLocaleEncoding)
            convertValues(mLocaleEncoding);

        // finally, push all name/value pairs to the client
        for (int i = 0; i < mNames->size(); i++) {
            status_t status = handleStringTag(mNames->getEntry(i), mValues->getEntry(i));
            if (status) {
                break;
            }
        }
    }
    // else addStringTag() has done all the work so we have nothing to do

    delete mNames;
    delete mValues;
    mNames = NULL;
    mValues = NULL;
}

}  // namespace android

