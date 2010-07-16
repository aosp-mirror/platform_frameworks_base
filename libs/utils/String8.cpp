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

/*
 * Functions outside android is below the namespace android, since they use
 * functions and constants in android namespace.
 */

// ---------------------------------------------------------------------------

namespace android {

static const char32_t kByteMask = 0x000000BF;
static const char32_t kByteMark = 0x00000080;

// Surrogates aren't valid for UTF-32 characters, so define some
// constants that will let us screen them out.
static const char32_t kUnicodeSurrogateHighStart  = 0x0000D800;
static const char32_t kUnicodeSurrogateHighEnd    = 0x0000DBFF;
static const char32_t kUnicodeSurrogateLowStart   = 0x0000DC00;
static const char32_t kUnicodeSurrogateLowEnd     = 0x0000DFFF;
static const char32_t kUnicodeSurrogateStart      = kUnicodeSurrogateHighStart;
static const char32_t kUnicodeSurrogateEnd        = kUnicodeSurrogateLowEnd;
static const char32_t kUnicodeMaxCodepoint        = 0x0010FFFF;

// Mask used to set appropriate bits in first byte of UTF-8 sequence,
// indexed by number of bytes in the sequence.
// 0xxxxxxx
// -> (00-7f) 7bit. Bit mask for the first byte is 0x00000000
// 110yyyyx 10xxxxxx
// -> (c0-df)(80-bf) 11bit. Bit mask is 0x000000C0
// 1110yyyy 10yxxxxx 10xxxxxx
// -> (e0-ef)(80-bf)(80-bf) 16bit. Bit mask is 0x000000E0
// 11110yyy 10yyxxxx 10xxxxxx 10xxxxxx
// -> (f0-f7)(80-bf)(80-bf)(80-bf) 21bit. Bit mask is 0x000000F0
static const char32_t kFirstByteMark[] = {
    0x00000000, 0x00000000, 0x000000C0, 0x000000E0, 0x000000F0
};

// Separator used by resource paths. This is not platform dependent contrary
// to OS_PATH_SEPARATOR.
#define RES_PATH_SEPARATOR '/'

// Return number of utf8 bytes required for the character.
static size_t utf32_to_utf8_bytes(char32_t srcChar)
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
    else if (srcChar <= kUnicodeMaxCodepoint)
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

static void utf32_to_utf8(uint8_t* dstP, char32_t srcChar, size_t bytes)
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
    // HACK: This dummy dependency forces linking libutils Static.cpp,
    // which is needed to initialize String8/String16 classes.
    // These variables are named for Darwin, but are needed elsewhere too,
    // including static linking on any platform.
    gDarwinIsReallyAnnoying = gDarwinCantLoadAllObjects;

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

template<typename T, typename L>
static char* allocFromUTF16OrUTF32(const T* in, L len)
{
    if (len == 0) return getEmptyString();

    size_t bytes = 0;
    const T* end = in+len;
    const T* p = in;

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
            const T c = *p++;
            size_t len = utf32_to_utf8_bytes(c);
            utf32_to_utf8((uint8_t*)d, c, len);
            d += len;
        }
        *d = 0;

        return str;
    }

    return getEmptyString();
}

static char* allocFromUTF16(const char16_t* in, size_t len)
{
    if (len == 0) return getEmptyString();

    const size_t bytes = utf8_length_from_utf16(in, len);

    SharedBuffer* buf = SharedBuffer::alloc(bytes+1);
    LOG_ASSERT(buf, "Unable to allocate shared buffer");
    if (buf) {
        char* str = (char*)buf->data();

        utf16_to_utf8(in, len, str, bytes+1);

        return str;
    }

    return getEmptyString();
}

static char* allocFromUTF32(const char32_t* in, size_t len)
{
    return allocFromUTF16OrUTF32<char32_t, size_t>(in, len);
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

String8::String8(const char32_t* o)
    : mString(allocFromUTF32(o, strlen32(o)))
{
}

String8::String8(const char32_t* o, size_t len)
    : mString(allocFromUTF32(o, len))
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
    const char *newString = allocFromUTF8(other, strlen(other));
    SharedBuffer::bufferFromData(mString)->release();
    mString = newString;
    if (mString) return NO_ERROR;

    mString = getEmptyString();
    return NO_MEMORY;
}

status_t String8::setTo(const char* other, size_t len)
{
    const char *newString = allocFromUTF8(other, len);
    SharedBuffer::bufferFromData(mString)->release();
    mString = newString;
    if (mString) return NO_ERROR;

    mString = getEmptyString();
    return NO_MEMORY;
}

status_t String8::setTo(const char16_t* other, size_t len)
{
    const char *newString = allocFromUTF16(other, len);
    SharedBuffer::bufferFromData(mString)->release();
    mString = newString;
    if (mString) return NO_ERROR;

    mString = getEmptyString();
    return NO_MEMORY;
}

status_t String8::setTo(const char32_t* other, size_t len)
{
    const char *newString = allocFromUTF32(other, len);
    SharedBuffer::bufferFromData(mString)->release();
    mString = newString;
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

status_t String8::appendFormat(const char* fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);

    int result = NO_ERROR;
    int n = vsnprintf(NULL, 0, fmt, ap);
    if (n != 0) {
        size_t oldLength = length();
        char* buf = lockBuffer(oldLength + n);
        if (buf) {
            vsnprintf(buf + oldLength, n + 1, fmt, ap);
        } else {
            result = NO_MEMORY;
        }
    }

    va_end(ap);
    return result;
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
        if (! buf) {
            return NO_MEMORY;
        }

        char* str = (char*)buf->data();
        str[size] = 0;
        mString = str;
    }

    return NO_ERROR;
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

size_t String8::getUtf32Length() const
{
    return utf32_length(mString, length());
}

int32_t String8::getUtf32At(size_t index, size_t *next_index) const
{
    return utf32_at(mString, length(), index, next_index);
}

size_t String8::getUtf32(char32_t* dst, size_t dst_len) const
{
    return utf8_to_utf32(mString, length(), dst, dst_len);
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

// ---------------------------------------------------------------------------

size_t strlen32(const char32_t *s)
{
  const char32_t *ss = s;
  while ( *ss )
    ss++;
  return ss-s;
}

size_t strnlen32(const char32_t *s, size_t maxlen)
{
  const char32_t *ss = s;
  while ((maxlen > 0) && *ss) {
    ss++;
    maxlen--;
  }
  return ss-s;
}

size_t utf8_length(const char *src)
{
    const char *cur = src;
    size_t ret = 0;
    while (*cur != '\0') {
        const char first_char = *cur++;
        if ((first_char & 0x80) == 0) { // ASCII
            ret += 1;
            continue;
        }
        // (UTF-8's character must not be like 10xxxxxx,
        //  but 110xxxxx, 1110xxxx, ... or 1111110x)
        if ((first_char & 0x40) == 0) {
            return 0;
        }

        int32_t mask, to_ignore_mask;
        size_t num_to_read = 0;
        char32_t utf32 = 0;
        for (num_to_read = 1, mask = 0x40, to_ignore_mask = 0x80;
             num_to_read < 5 && (first_char & mask);
             num_to_read++, to_ignore_mask |= mask, mask >>= 1) {
            if ((*cur & 0xC0) != 0x80) { // must be 10xxxxxx
                return 0;
            }
            // 0x3F == 00111111
            utf32 = (utf32 << 6) + (*cur++ & 0x3F);
        }
        // "first_char" must be (110xxxxx - 11110xxx)
        if (num_to_read == 5) {
            return 0;
        }
        to_ignore_mask |= mask;
        utf32 |= ((~to_ignore_mask) & first_char) << (6 * (num_to_read - 1));
        if (utf32 > android::kUnicodeMaxCodepoint) {
            return 0;
        }

        ret += num_to_read;
    }
    return ret;
}

size_t utf32_length(const char *src, size_t src_len)
{
    if (src == NULL || src_len == 0) {
        return 0;
    }
    size_t ret = 0;
    const char* cur;
    const char* end;
    size_t num_to_skip;
    for (cur = src, end = src + src_len, num_to_skip = 1;
         cur < end;
         cur += num_to_skip, ret++) {
        const char first_char = *cur;
        num_to_skip = 1;
        if ((first_char & 0x80) == 0) {  // ASCII
            continue;
        }
        int32_t mask;

        for (mask = 0x40; (first_char & mask); num_to_skip++, mask >>= 1) {
        }
    }
    return ret;
}

size_t utf8_length_from_utf32(const char32_t *src, size_t src_len)
{
    if (src == NULL || src_len == 0) {
        return 0;
    }
    size_t ret = 0;
    const char32_t *end = src + src_len;
    while (src < end) {
        ret += android::utf32_to_utf8_bytes(*src++);
    }
    return ret;
}

size_t utf8_length_from_utf16(const char16_t *src, size_t src_len)
{
    if (src == NULL || src_len == 0) {
        return 0;
    }
    size_t ret = 0;
    const char16_t* const end = src + src_len;
    while (src < end) {
        if ((*src & 0xFC00) == 0xD800 && (src + 1) < end
                && (*++src & 0xFC00) == 0xDC00) {
            // surrogate pairs are always 4 bytes.
            ret += 4;
            src++;
        } else {
            ret += android::utf32_to_utf8_bytes((char32_t) *src++);
        }
    }
    return ret;
}

static int32_t utf32_at_internal(const char* cur, size_t *num_read)
{
    const char first_char = *cur;
    if ((first_char & 0x80) == 0) { // ASCII
        *num_read = 1;
        return *cur;
    }
    cur++;
    char32_t mask, to_ignore_mask;
    size_t num_to_read = 0;
    char32_t utf32 = first_char;
    for (num_to_read = 1, mask = 0x40, to_ignore_mask = 0xFFFFFF80;
         (first_char & mask);
         num_to_read++, to_ignore_mask |= mask, mask >>= 1) {
        // 0x3F == 00111111
        utf32 = (utf32 << 6) + (*cur++ & 0x3F);
    }
    to_ignore_mask |= mask;
    utf32 &= ~(to_ignore_mask << (6 * (num_to_read - 1)));

    *num_read = num_to_read;
    return static_cast<int32_t>(utf32);
}

int32_t utf32_at(const char *src, size_t src_len,
                 size_t index, size_t *next_index)
{
    if (index >= src_len) {
        return -1;
    }
    size_t dummy_index;
    if (next_index == NULL) {
        next_index = &dummy_index;
    }
    size_t num_read;
    int32_t ret = utf32_at_internal(src + index, &num_read);
    if (ret >= 0) {
        *next_index = index + num_read;
    }

    return ret;
}

size_t utf8_to_utf32(const char* src, size_t src_len,
                     char32_t* dst, size_t dst_len)
{
    if (src == NULL || src_len == 0 || dst == NULL || dst_len == 0) {
        return 0;
    }

    const char* cur = src;
    const char* end = src + src_len;
    char32_t* cur_utf32 = dst;
    const char32_t* end_utf32 = dst + dst_len;
    while (cur_utf32 < end_utf32 && cur < end) {
        size_t num_read;
        *cur_utf32++ =
                static_cast<char32_t>(utf32_at_internal(cur, &num_read));
        cur += num_read;
    }
    if (cur_utf32 < end_utf32) {
        *cur_utf32 = 0;
    }
    return static_cast<size_t>(cur_utf32 - dst);
}

size_t utf32_to_utf8(const char32_t* src, size_t src_len,
                     char* dst, size_t dst_len)
{
    if (src == NULL || src_len == 0 || dst == NULL || dst_len == 0) {
        return 0;
    }
    const char32_t *cur_utf32 = src;
    const char32_t *end_utf32 = src + src_len;
    char *cur = dst;
    const char *end = dst + dst_len;
    while (cur_utf32 < end_utf32 && cur < end) {
        size_t len = android::utf32_to_utf8_bytes(*cur_utf32);
        android::utf32_to_utf8((uint8_t *)cur, *cur_utf32++, len);
        cur += len;
    }
    if (cur < end) {
        *cur = '\0';
    }
    return cur - dst;
}

size_t utf16_to_utf8(const char16_t* src, size_t src_len,
                     char* dst, size_t dst_len)
{
    if (src == NULL || src_len == 0 || dst == NULL || dst_len == 0) {
        return 0;
    }
    const char16_t* cur_utf16 = src;
    const char16_t* const end_utf16 = src + src_len;
    char *cur = dst;
    const char* const end = dst + dst_len;
    while (cur_utf16 < end_utf16 && cur < end) {
        char32_t utf32;
        // surrogate pairs
        if ((*cur_utf16 & 0xFC00) == 0xD800 && (cur_utf16 + 1) < end_utf16) {
            utf32 = (*cur_utf16++ - 0xD800) << 10;
            utf32 |= *cur_utf16++ - 0xDC00;
            utf32 += 0x10000;
        } else {
            utf32 = (char32_t) *cur_utf16++;
        }
        size_t len = android::utf32_to_utf8_bytes(utf32);
        android::utf32_to_utf8((uint8_t*)cur, utf32, len);
        cur += len;
    }
    if (cur < end) {
        *cur = '\0';
    }
    return cur - dst;
}
