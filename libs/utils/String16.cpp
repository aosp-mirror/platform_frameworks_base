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
#include <utils/String8.h>
#include <utils/TextOutput.h>
#include <utils/threads.h>

#include <private/utils/Static.h>

#ifdef HAVE_WINSOCK
# undef  nhtol
# undef  htonl
# undef  nhtos
# undef  htons

# ifdef HAVE_LITTLE_ENDIAN
#  define ntohl(x)    ( ((x) << 24) | (((x) >> 24) & 255) | (((x) << 8) & 0xff0000) | (((x) >> 8) & 0xff00) )
#  define htonl(x)    ntohl(x)
#  define ntohs(x)    ( (((x) << 8) & 0xff00) | (((x) >> 8) & 255) )
#  define htons(x)    ntohs(x)
# else
#  define ntohl(x)    (x)
#  define htonl(x)    (x)
#  define ntohs(x)    (x)
#  define htons(x)    (x)
# endif
#else
# include <netinet/in.h>
#endif

#include <memory.h>
#include <stdio.h>
#include <ctype.h>

// ---------------------------------------------------------------------------

int strcmp16(const char16_t *s1, const char16_t *s2)
{
  char16_t ch;
  int d = 0;

  while ( 1 ) {
    d = (int)(ch = *s1++) - (int)*s2++;
    if ( d || !ch )
      break;
  }

  return d;
}

int strncmp16(const char16_t *s1, const char16_t *s2, size_t n)
{
  char16_t ch;
  int d = 0;

  while ( n-- ) {
    d = (int)(ch = *s1++) - (int)*s2++;
    if ( d || !ch )
      break;
  }

  return d;
}

char16_t *strcpy16(char16_t *dst, const char16_t *src)
{
  char16_t *q = dst;
  const char16_t *p = src;
  char16_t ch;

  do {
    *q++ = ch = *p++;
  } while ( ch );

  return dst;
}

size_t strlen16(const char16_t *s)
{
  const char16_t *ss = s;
  while ( *ss )
    ss++;
  return ss-s;
}


char16_t *strncpy16(char16_t *dst, const char16_t *src, size_t n)
{
  char16_t *q = dst;
  const char16_t *p = src;
  char ch;

  while (n) {
    n--;
    *q++ = ch = *p++;
    if ( !ch )
      break;
  }

  *q = 0;

  return dst;
}

size_t strnlen16(const char16_t *s, size_t maxlen)
{
  const char16_t *ss = s;

  /* Important: the maxlen test must precede the reference through ss;
     since the byte beyond the maximum may segfault */
  while ((maxlen > 0) && *ss) {
    ss++;
    maxlen--;
  }
  return ss-s;
}

int strzcmp16(const char16_t *s1, size_t n1, const char16_t *s2, size_t n2)
{
    const char16_t* e1 = s1+n1;
    const char16_t* e2 = s2+n2;

    while (s1 < e1 && s2 < e2) {
        const int d = (int)*s1++ - (int)*s2++;
        if (d) {
            return d;
        }
    }

    return n1 < n2
        ? (0 - (int)*s2)
        : (n1 > n2
           ? ((int)*s1 - 0)
           : 0);
}

int strzcmp16_h_n(const char16_t *s1H, size_t n1, const char16_t *s2N, size_t n2)
{
    const char16_t* e1 = s1H+n1;
    const char16_t* e2 = s2N+n2;

    while (s1H < e1 && s2N < e2) {
        const char16_t c2 = ntohs(*s2N);
        const int d = (int)*s1H++ - (int)c2;
        s2N++;
        if (d) {
            return d;
        }
    }

    return n1 < n2
        ? (0 - (int)ntohs(*s2N))
        : (n1 > n2
           ? ((int)*s1H - 0)
           : 0);
}

static inline size_t
utf8_char_len(uint8_t ch)
{
    return ((0xe5000000 >> ((ch >> 3) & 0x1e)) & 3) + 1;
}

#define UTF8_SHIFT_AND_MASK(unicode, byte)  (unicode)<<=6; (unicode) |= (0x3f & (byte));

static inline uint32_t
utf8_to_utf32(const uint8_t *src, size_t length)
{
    uint32_t unicode;

    switch (length)
    {
        case 1:
            return src[0];
        case 2:
            unicode = src[0] & 0x1f;
            UTF8_SHIFT_AND_MASK(unicode, src[1])
            return unicode;
        case 3:
            unicode = src[0] & 0x0f;
            UTF8_SHIFT_AND_MASK(unicode, src[1])
            UTF8_SHIFT_AND_MASK(unicode, src[2])
            return unicode;
        case 4:
            unicode = src[0] & 0x07;
            UTF8_SHIFT_AND_MASK(unicode, src[1])
            UTF8_SHIFT_AND_MASK(unicode, src[2])
            UTF8_SHIFT_AND_MASK(unicode, src[3])
            return unicode;
        default:
            return 0xffff;
    }
    
    //printf("Char at %p: len=%d, utf-16=%p\n", src, length, (void*)result);
}

void
utf8_to_utf16(const uint8_t *src, size_t srcLen,
        char16_t* dst, const size_t dstLen)
{
    const uint8_t* const end = src + srcLen;
    const char16_t* const dstEnd = dst + dstLen;
    while (src < end && dst < dstEnd) {
        size_t len = utf8_char_len(*src);
        uint32_t codepoint = utf8_to_utf32((const uint8_t*)src, len);

        // Convert the UTF32 codepoint to one or more UTF16 codepoints
        if (codepoint <= 0xFFFF) {
            // Single UTF16 character
            *dst++ = (char16_t) codepoint;
        } else {
            // Multiple UTF16 characters with surrogates
            codepoint = codepoint - 0x10000;
            *dst++ = (char16_t) ((codepoint >> 10) + 0xD800);
            *dst++ = (char16_t) ((codepoint & 0x3FF) + 0xDC00);
        }

        src += len;
    }
    if (dst < dstEnd) {
        *dst = 0;
    }
}

// ---------------------------------------------------------------------------

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

static char16_t* allocFromUTF8(const char* in, size_t len)
{
    if (len == 0) return getEmptyString();
    
    size_t chars = 0;
    const char* end = in+len;
    const char* p = in;
    
    while (p < end) {
        chars++;
        int utf8len = utf8_char_len(*p);
        uint32_t codepoint = utf8_to_utf32((const uint8_t*)p, utf8len);
        if (codepoint > 0xFFFF) chars++; // this will be a surrogate pair in utf16
        p += utf8len;
    }
    
    size_t bufSize = (chars+1)*sizeof(char16_t);
    SharedBuffer* buf = SharedBuffer::alloc(bufSize);
    if (buf) {
        p = in;
        char16_t* str = (char16_t*)buf->data();
        
        utf8_to_utf16((const uint8_t*)p, len, str, bufSize);

        //printf("Created UTF-16 string from UTF-8 \"%s\":", in);
        //printHexData(1, str, buf->size(), 16, 1);
        //printf("\n");
        
        return str;
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
    LOG_ASSERT(buf, "Unable to allocate shared buffer");
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
    LOG_ASSERT(buf, "Unable to allocate shared buffer");
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
