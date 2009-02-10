/*
 * Copyright (C) 2005 The Android Open Source Project
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

#include <utils/String8.h>

#include <utils/Log.h>
#include <utils/String16.h>
#include <utils/TextOutput.h>
#include <utils/threads.h>

#include <private/utils/Static.h>

#include <ctype.h>

namespace android {

// ---------------------------------------------------------------------------

static const uint32_t kByteMask = 0x000000BF;
static const uint32_t kByteMark = 0x00000080;

// Surrogates aren't valid for UTF-32 characters, so define some
// constants that will let us screen them out.
static const uint32_t kUnicodeSurrogateHighStart  = 0x0000D800;
static const uint32_t kUnicodeSurrogateHighEnd    = 0x0000DBFF;
static const uint32_t kUnicodeSurrogateLowStart   = 0x0000DC00;
static const uint32_t kUnicodeSurrogateLowEnd     = 0x0000DFFF;
static const uint32_t kUnicodeSurrogateStart      = kUnicodeSurrogateHighStart;
static const uint32_t kUnicodeSurrogateEnd        = kUnicodeSurrogateLowEnd;

// Mask used to set appropriate bits in first byte of UTF-8 sequence,
// indexed by number of bytes in the sequence.
static const uint32_t kFirstByteMark[] = {
    0x00000000, 0x00000000, 0x000000C0, 0x000000E0, 0x000000F0
};

// Separator used by resource paths. This is not platform dependent contrary
// to OS_PATH_SEPARATOR.
#define RES_PATH_SEPARATOR '/'

// Return number of utf8 bytes required for the character.
static size_t utf32_to_utf8_bytes(uint32_t srcChar)
{
    size_t bytesToWrite;

    // Figure out how many bytes the result will require.
    if (srcChar < 0x00000080)
    {
        bytesToWrite = 1;
    }
    else if (srcChar < 0x00000800)
    {
        bytesToWrite = 2;
    }
    else if (srcChar < 0x00010000)
    {
        if ((srcChar < kUnicodeSurrogateStart)
         || (srcChar > kUnicodeSurrogateEnd))
        {
            bytesToWrite = 3;
        }
        else
        {
            // Surrogates are invalid UTF-32 characters.
            return 0;
        }
    }
    // Max code point for Unicode is 0x0010FFFF.
    else if (srcChar < 0x00110000)
    {
        bytesToWrite = 4;
    }
    else
    {
        // Invalid UTF-32 character.
        return 0;
    }

    return bytesToWrite;
}

// Write out the source character to <dstP>.

static void utf32_to_utf8(uint8_t* dstP, uint32_t srcChar, size_t bytes)
{
    dstP += bytes;
    switch (bytes)
    {   /* note: everything falls through. */
        case 4: *--dstP = (uint8_t)((srcChar | kByteMark) & kByteMask); srcChar >>= 6;
        case 3: *--dstP = (uint8_t)((srcChar | kByteMark) & kByteMask); srcChar >>= 6;
        case 2: *--dstP = (uint8_t)((srcChar | kByteMark) & kByteMask); srcChar >>= 6;
        case 1: *--dstP = (uint8_t)(srcChar | kFirstByteMark[bytes]);
    }
}

// ---------------------------------------------------------------------------

static SharedBuffer* gEmptyStringBuf = NULL;
static char* gEmptyString = NULL;

extern int gDarwinCantLoadAllObjects;
int gDarwinIsReallyAnnoying;

static inline char* getEmptyString()
{
    gEmptyStringBuf->acquire();
    return gEmptyString;
}

void initialize_string8()
{
#ifdef LIBUTILS_NATIVE
	  // Bite me, Darwin!
		gDarwinIsReallyAnnoying = gDarwinCantLoadAllObjects;
#endif
			
    SharedBuffer* buf = SharedBuffer::alloc(1);
    char* str = (char*)buf->data();
    *str = 0;
    gEmptyStringBuf = buf;
    gEmptyString = str;
}

void terminate_string8()
{
    SharedBuffer::bufferFromData(gEmptyString)->release();
    gEmptyStringBuf = NULL;
    gEmptyString = NULL;
}

// ---------------------------------------------------------------------------

static char* allocFromUTF8(const char* in, size_t len)
{
    if (len > 0) {
        SharedBuffer* buf = SharedBuffer::alloc(len+1);
        LOG_ASSERT(buf, "Unable to allocate shared buffer");
        if (buf) {
            char* str = (char*)buf->data();
            memcpy(str, in, len);
            str[len] = 0;
            return str;
        }
        return NULL;
    }

    return getEmptyString();
}

// Note: not dealing with expanding surrogate pairs.
static char* allocFromUTF16(const char16_t* in, size_t len)
{
    if (len == 0) return getEmptyString();
    
    size_t bytes = 0;
    const char16_t* end = in+len;
    const char16_t* p = in;
    
    while (p < end) {
        bytes += utf32_to_utf8_bytes(*p);
        p++;
    }
    
    SharedBuffer* buf = SharedBuffer::alloc(bytes+1);
    LOG_ASSERT(buf, "Unable to allocate shared buffer");
    if (buf) {
        p = in;
        char* str = (char*)buf->data();
        char* d = str;
        while (p < end) {
            uint32_t c = *p++;
            size_t len = utf32_to_utf8_bytes(c);
            utf32_to_utf8((uint8_t*)d, c, len);
            d += len;
        }
        *d = 0;
        
        return str;
    }
    
    return getEmptyString();
}

// ---------------------------------------------------------------------------

String8::String8()
    : mString(getEmptyString())
{
}

String8::String8(const String8& o)
    : mString(o.mString)
{
    SharedBuffer::bufferFromData(mString)->acquire();
}

String8::String8(const char* o)
    : mString(allocFromUTF8(o, strlen(o)))
{
    if (mString == NULL) {
        mString = getEmptyString();
    }
}

String8::String8(const char* o, size_t len)
    : mString(allocFromUTF8(o, len))
{
    if (mString == NULL) {
        mString = getEmptyString();
    }
}

String8::String8(const String16& o)
    : mString(allocFromUTF16(o.string(), o.size()))
{
}

String8::String8(const char16_t* o)
    : mString(allocFromUTF16(o, strlen16(o)))
{
}

String8::String8(const char16_t* o, size_t len)
    : mString(allocFromUTF16(o, len))
{
}

String8::~String8()
{
    SharedBuffer::bufferFromData(mString)->release();
}

void String8::setTo(const String8& other)
{
    SharedBuffer::bufferFromData(other.mString)->acquire();
    SharedBuffer::bufferFromData(mString)->release();
    mString = other.mString;
}

status_t String8::setTo(const char* other)
{
    SharedBuffer::bufferFromData(mString)->release();
    mString = allocFromUTF8(other, strlen(other));
    if (mString) return NO_ERROR;

    mString = getEmptyString();
    return NO_MEMORY;
}

status_t String8::setTo(const char* other, size_t len)
{
    SharedBuffer::bufferFromData(mString)->release();
    mString = allocFromUTF8(other, len);
    if (mString) return NO_ERROR;

    mString = getEmptyString();
    return NO_MEMORY;
}

status_t String8::setTo(const char16_t* other, size_t len)
{
    SharedBuffer::bufferFromData(mString)->release();
    mString = allocFromUTF16(other, len);
    if (mString) return NO_ERROR;

    mString = getEmptyString();
    return NO_MEMORY;
}

status_t String8::append(const String8& other)
{
    const size_t otherLen = other.bytes();
    if (bytes() == 0) {
        setTo(other);
        return NO_ERROR;
    } else if (otherLen == 0) {
        return NO_ERROR;
    }

    return real_append(other.string(), otherLen);
}

status_t String8::append(const char* other)
{
    return append(other, strlen(other));
}

status_t String8::append(const char* other, size_t otherLen)
{
    if (bytes() == 0) {
        return setTo(other, otherLen);
    } else if (otherLen == 0) {
        return NO_ERROR;
    }

    return real_append(other, otherLen);
}

status_t String8::real_append(const char* other, size_t otherLen)
{
    const size_t myLen = bytes();
    
    SharedBuffer* buf = SharedBuffer::bufferFromData(mString)
        ->editResize(myLen+otherLen+1);
    if (buf) {
        char* str = (char*)buf->data();
        mString = str;
        str += myLen;
        memcpy(str, other, otherLen);
        str[otherLen] = '\0';
        return NO_ERROR;
    }
    return NO_MEMORY;
}

char* String8::lockBuffer(size_t size)
{
    SharedBuffer* buf = SharedBuffer::bufferFromData(mString)
        ->editResize(size+1);
    if (buf) {
        char* str = (char*)buf->data();
        mString = str;
        return str;
    }
    return NULL;
}

void String8::unlockBuffer()
{
    unlockBuffer(strlen(mString));
}

status_t String8::unlockBuffer(size_t size)
{
    if (size != this->size()) {
        SharedBuffer* buf = SharedBuffer::bufferFromData(mString)
            ->editResize(size+1);
        if (buf) {
            char* str = (char*)buf->data();
            str[size] = 0;
            mString = str;
            return NO_ERROR;
        }
    }
    
    return NO_MEMORY;
}

ssize_t String8::find(const char* other, size_t start) const
{
    size_t len = size();
    if (start >= len) {
        return -1;
    }
    const char* s = mString+start;
    const char* p = strstr(s, other);
    return p ? p-mString : -1;
}

void String8::toLower()
{
    toLower(0, size());
}

void String8::toLower(size_t start, size_t length)
{
    const size_t len = size();
    if (start >= len) {
        return;
    }
    if (start+length > len) {
        length = len-start;
    }
    char* buf = lockBuffer(len);
    buf += start;
    while (length > 0) {
        *buf = tolower(*buf);
        buf++;
        length--;
    }
    unlockBuffer(len);
}

void String8::toUpper()
{
    toUpper(0, size());
}

void String8::toUpper(size_t start, size_t length)
{
    const size_t len = size();
    if (start >= len) {
        return;
    }
    if (start+length > len) {
        length = len-start;
    }
    char* buf = lockBuffer(len);
    buf += start;
    while (length > 0) {
        *buf = toupper(*buf);
        buf++;
        length--;
    }
    unlockBuffer(len);
}

TextOutput& operator<<(TextOutput& to, const String8& val)
{
    to << val.string();
    return to;
}

// ---------------------------------------------------------------------------
// Path functions


void String8::setPathName(const char* name)
{
    setPathName(name, strlen(name));
}

void String8::setPathName(const char* name, size_t len)
{
    char* buf = lockBuffer(len);

    memcpy(buf, name, len);

    // remove trailing path separator, if present
    if (len > 0 && buf[len-1] == OS_PATH_SEPARATOR)
        len--;

    buf[len] = '\0';

    unlockBuffer(len);
}

String8 String8::getPathLeaf(void) const
{
    const char* cp;
    const char*const buf = mString;

    cp = strrchr(buf, OS_PATH_SEPARATOR);
    if (cp == NULL)
        return String8(*this);
    else
        return String8(cp+1);
}

String8 String8::getPathDir(void) const
{
    const char* cp;
    const char*const str = mString;

    cp = strrchr(str, OS_PATH_SEPARATOR);
    if (cp == NULL)
        return String8("");
    else
        return String8(str, cp - str);
}

String8 String8::walkPath(String8* outRemains) const
{
    const char* cp;
    const char*const str = mString;
    const char* buf = str;

    cp = strchr(buf, OS_PATH_SEPARATOR);
    if (cp == buf) {
        // don't include a leading '/'.
        buf = buf+1;
        cp = strchr(buf, OS_PATH_SEPARATOR);
    }

    if (cp == NULL) {
        String8 res = buf != str ? String8(buf) : *this;
        if (outRemains) *outRemains = String8("");
        return res;
    }

    String8 res(buf, cp-buf);
    if (outRemains) *outRemains = String8(cp+1);
    return res;
}

/*
 * Helper function for finding the start of an extension in a pathname.
 *
 * Returns a pointer inside mString, or NULL if no extension was found.
 */
char* String8::find_extension(void) const
{
    const char* lastSlash;
    const char* lastDot;
    int extLen;
    const char* const str = mString;

    // only look at the filename
    lastSlash = strrchr(str, OS_PATH_SEPARATOR);
    if (lastSlash == NULL)
        lastSlash = str;
    else
        lastSlash++;

    // find the last dot
    lastDot = strrchr(lastSlash, '.');
    if (lastDot == NULL)
        return NULL;

    // looks good, ship it
    return const_cast<char*>(lastDot);
}

String8 String8::getPathExtension(void) const
{
    char* ext;

    ext = find_extension();
    if (ext != NULL)
        return String8(ext);
    else
        return String8("");
}

String8 String8::getBasePath(void) const
{
    char* ext;
    const char* const str = mString;

    ext = find_extension();
    if (ext == NULL)
        return String8(*this);
    else
        return String8(str, ext - str);
}

String8& String8::appendPath(const char* name)
{
    // TODO: The test below will fail for Win32 paths. Fix later or ignore.
    if (name[0] != OS_PATH_SEPARATOR) {
        if (*name == '\0') {
            // nothing to do
            return *this;
        }

        size_t len = length();
        if (len == 0) {
            // no existing filename, just use the new one
            setPathName(name);
            return *this;
        }

        // make room for oldPath + '/' + newPath
        int newlen = strlen(name);

        char* buf = lockBuffer(len+1+newlen);

        // insert a '/' if needed
        if (buf[len-1] != OS_PATH_SEPARATOR)
            buf[len++] = OS_PATH_SEPARATOR;

        memcpy(buf+len, name, newlen+1);
        len += newlen;

        unlockBuffer(len);

        return *this;
    } else {
        setPathName(name);
        return *this;
    }
}

String8& String8::convertToResPath()
{
#if OS_PATH_SEPARATOR != RES_PATH_SEPARATOR
    size_t len = length();
    if (len > 0) {
        char * buf = lockBuffer(len);
        for (char * end = buf + len; buf < end; ++buf) {
            if (*buf == OS_PATH_SEPARATOR)
                *buf = RES_PATH_SEPARATOR;
        }
        unlockBuffer(len);
    }
#endif
    return *this;
}


}; // namespace android
