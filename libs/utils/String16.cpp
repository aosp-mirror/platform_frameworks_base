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

#include <utils/String16.h>

#include <utils/Debug.h>
#include <utils/Log.h>
#include <utils/Unicode.h>
#include <utils/String8.h>
#include <utils/TextOutput.h>
#include <utils/threads.h>

#include <private/utils/Static.h>

#include <memory.h>
#include <stdio.h>
#include <ctype.h>


namespace android {

static SharedBuffer* gEmptyStringBuf = NULL;
static char16_t* gEmptyString = NULL;

static inline char16_t* getEmptyString()
{
    gEmptyStringBuf->acquire();
   return gEmptyString;
}

void initialize_string16()
{
    SharedBuffer* buf = SharedBuffer::alloc(sizeof(char16_t));
    char16_t* str = (char16_t*)buf->data();
    *str = 0;
    gEmptyStringBuf = buf;
    gEmptyString = str;
}

void terminate_string16()
{
    SharedBuffer::bufferFromData(gEmptyString)->release();
    gEmptyStringBuf = NULL;
    gEmptyString = NULL;
}

// ---------------------------------------------------------------------------

static char16_t* allocFromUTF8(const char* u8str, size_t u8len)
{
    if (u8len == 0) return getEmptyString();

    const uint8_t* u8cur = (const uint8_t*) u8str;

    const ssize_t u16len = utf8_to_utf16_length(u8cur, u8len);
    if (u16len < 0) {
        return getEmptyString();
    }

    const uint8_t* const u8end = u8cur + u8len;

    SharedBuffer* buf = SharedBuffer::alloc(sizeof(char16_t)*(u16len+1));
    if (buf) {
        u8cur = (const uint8_t*) u8str;
        char16_t* u16str = (char16_t*)buf->data();

        utf8_to_utf16(u8cur, u8len, u16str);

        //printf("Created UTF-16 string from UTF-8 \"%s\":", in);
        //printHexData(1, str, buf->size(), 16, 1);
        //printf("\n");
        
        return u16str;
    }

    return getEmptyString();
}

// ---------------------------------------------------------------------------

String16::String16()
    : mString(getEmptyString())
{
}

String16::String16(const String16& o)
    : mString(o.mString)
{
    SharedBuffer::bufferFromData(mString)->acquire();
}

String16::String16(const String16& o, size_t len, size_t begin)
    : mString(getEmptyString())
{
    setTo(o, len, begin);
}

String16::String16(const char16_t* o)
{
    size_t len = strlen16(o);
    SharedBuffer* buf = SharedBuffer::alloc((len+1)*sizeof(char16_t));
    ALOG_ASSERT(buf, "Unable to allocate shared buffer");
    if (buf) {
        char16_t* str = (char16_t*)buf->data();
        strcpy16(str, o);
        mString = str;
        return;
    }
    
    mString = getEmptyString();
}

String16::String16(const char16_t* o, size_t len)
{
    SharedBuffer* buf = SharedBuffer::alloc((len+1)*sizeof(char16_t));
    ALOG_ASSERT(buf, "Unable to allocate shared buffer");
    if (buf) {
        char16_t* str = (char16_t*)buf->data();
        memcpy(str, o, len*sizeof(char16_t));
        str[len] = 0;
        mString = str;
        return;
    }
    
    mString = getEmptyString();
}

String16::String16(const String8& o)
    : mString(allocFromUTF8(o.string(), o.size()))
{
}

String16::String16(const char* o)
    : mString(allocFromUTF8(o, strlen(o)))
{
}

String16::String16(const char* o, size_t len)
    : mString(allocFromUTF8(o, len))
{
}

String16::~String16()
{
    SharedBuffer::bufferFromData(mString)->release();
}

void String16::setTo(const String16& other)
{
    SharedBuffer::bufferFromData(other.mString)->acquire();
    SharedBuffer::bufferFromData(mString)->release();
    mString = other.mString;
}

status_t String16::setTo(const String16& other, size_t len, size_t begin)
{
    const size_t N = other.size();
    if (begin >= N) {
        SharedBuffer::bufferFromData(mString)->release();
        mString = getEmptyString();
        return NO_ERROR;
    }
    if ((begin+len) > N) len = N-begin;
    if (begin == 0 && len == N) {
        setTo(other);
        return NO_ERROR;
    }

    if (&other == this) {
        LOG_ALWAYS_FATAL("Not implemented");
    }

    return setTo(other.string()+begin, len);
}

status_t String16::setTo(const char16_t* other)
{
    return setTo(other, strlen16(other));
}

status_t String16::setTo(const char16_t* other, size_t len)
{
    SharedBuffer* buf = SharedBuffer::bufferFromData(mString)
        ->editResize((len+1)*sizeof(char16_t));
    if (buf) {
        char16_t* str = (char16_t*)buf->data();
        memmove(str, other, len*sizeof(char16_t));
        str[len] = 0;
        mString = str;
        return NO_ERROR;
    }
    return NO_MEMORY;
}

status_t String16::append(const String16& other)
{
    const size_t myLen = size();
    const size_t otherLen = other.size();
    if (myLen == 0) {
        setTo(other);
        return NO_ERROR;
    } else if (otherLen == 0) {
        return NO_ERROR;
    }
    
    SharedBuffer* buf = SharedBuffer::bufferFromData(mString)
        ->editResize((myLen+otherLen+1)*sizeof(char16_t));
    if (buf) {
        char16_t* str = (char16_t*)buf->data();
        memcpy(str+myLen, other, (otherLen+1)*sizeof(char16_t));
        mString = str;
        return NO_ERROR;
    }
    return NO_MEMORY;
}

status_t String16::append(const char16_t* chrs, size_t otherLen)
{
    const size_t myLen = size();
    if (myLen == 0) {
        setTo(chrs, otherLen);
        return NO_ERROR;
    } else if (otherLen == 0) {
        return NO_ERROR;
    }
    
    SharedBuffer* buf = SharedBuffer::bufferFromData(mString)
        ->editResize((myLen+otherLen+1)*sizeof(char16_t));
    if (buf) {
        char16_t* str = (char16_t*)buf->data();
        memcpy(str+myLen, chrs, otherLen*sizeof(char16_t));
        str[myLen+otherLen] = 0;
        mString = str;
        return NO_ERROR;
    }
    return NO_MEMORY;
}

status_t String16::insert(size_t pos, const char16_t* chrs)
{
    return insert(pos, chrs, strlen16(chrs));
}

status_t String16::insert(size_t pos, const char16_t* chrs, size_t len)
{
    const size_t myLen = size();
    if (myLen == 0) {
        return setTo(chrs, len);
        return NO_ERROR;
    } else if (len == 0) {
        return NO_ERROR;
    }

    if (pos > myLen) pos = myLen;

    #if 0
    printf("Insert in to %s: pos=%d, len=%d, myLen=%d, chrs=%s\n",
           String8(*this).string(), pos,
           len, myLen, String8(chrs, len).string());
    #endif

    SharedBuffer* buf = SharedBuffer::bufferFromData(mString)
        ->editResize((myLen+len+1)*sizeof(char16_t));
    if (buf) {
        char16_t* str = (char16_t*)buf->data();
        if (pos < myLen) {
            memmove(str+pos+len, str+pos, (myLen-pos)*sizeof(char16_t));
        }
        memcpy(str+pos, chrs, len*sizeof(char16_t));
        str[myLen+len] = 0;
        mString = str;
        #if 0
        printf("Result (%d chrs): %s\n", size(), String8(*this).string());
        #endif
        return NO_ERROR;
    }
    return NO_MEMORY;
}

ssize_t String16::findFirst(char16_t c) const
{
    const char16_t* str = string();
    const char16_t* p = str;
    const char16_t* e = p + size();
    while (p < e) {
        if (*p == c) {
            return p-str;
        }
        p++;
    }
    return -1;
}

ssize_t String16::findLast(char16_t c) const
{
    const char16_t* str = string();
    const char16_t* p = str;
    const char16_t* e = p + size();
    while (p < e) {
        e--;
        if (*e == c) {
            return e-str;
        }
    }
    return -1;
}

bool String16::startsWith(const String16& prefix) const
{
    const size_t ps = prefix.size();
    if (ps > size()) return false;
    return strzcmp16(mString, ps, prefix.string(), ps) == 0;
}

bool String16::startsWith(const char16_t* prefix) const
{
    const size_t ps = strlen16(prefix);
    if (ps > size()) return false;
    return strncmp16(mString, prefix, ps) == 0;
}

status_t String16::makeLower()
{
    const size_t N = size();
    const char16_t* str = string();
    char16_t* edit = NULL;
    for (size_t i=0; i<N; i++) {
        const char16_t v = str[i];
        if (v >= 'A' && v <= 'Z') {
            if (!edit) {
                SharedBuffer* buf = SharedBuffer::bufferFromData(mString)->edit();
                if (!buf) {
                    return NO_MEMORY;
                }
                edit = (char16_t*)buf->data();
                mString = str = edit;
            }
            edit[i] = tolower((char)v);
        }
    }
    return NO_ERROR;
}

status_t String16::replaceAll(char16_t replaceThis, char16_t withThis)
{
    const size_t N = size();
    const char16_t* str = string();
    char16_t* edit = NULL;
    for (size_t i=0; i<N; i++) {
        if (str[i] == replaceThis) {
            if (!edit) {
                SharedBuffer* buf = SharedBuffer::bufferFromData(mString)->edit();
                if (!buf) {
                    return NO_MEMORY;
                }
                edit = (char16_t*)buf->data();
                mString = str = edit;
            }
            edit[i] = withThis;
        }
    }
    return NO_ERROR;
}

status_t String16::remove(size_t len, size_t begin)
{
    const size_t N = size();
    if (begin >= N) {
        SharedBuffer::bufferFromData(mString)->release();
        mString = getEmptyString();
        return NO_ERROR;
    }
    if ((begin+len) > N) len = N-begin;
    if (begin == 0 && len == N) {
        return NO_ERROR;
    }

    if (begin > 0) {
        SharedBuffer* buf = SharedBuffer::bufferFromData(mString)
            ->editResize((N+1)*sizeof(char16_t));
        if (!buf) {
            return NO_MEMORY;
        }
        char16_t* str = (char16_t*)buf->data();
        memmove(str, str+begin, (N-begin+1)*sizeof(char16_t));
        mString = str;
    }
    SharedBuffer* buf = SharedBuffer::bufferFromData(mString)
        ->editResize((len+1)*sizeof(char16_t));
    if (buf) {
        char16_t* str = (char16_t*)buf->data();
        str[len] = 0;
        mString = str;
        return NO_ERROR;
    }
    return NO_MEMORY;
}

TextOutput& operator<<(TextOutput& to, const String16& val)
{
    to << String8(val).string();
    return to;
}

}; // namespace android
