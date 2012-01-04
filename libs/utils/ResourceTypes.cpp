/*
 * Copyright (C) 2008 The Android Open Source Project
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

#define LOG_TAG "ResourceType"
//#define LOG_NDEBUG 0

#include <utils/Atomic.h>
#include <utils/ByteOrder.h>
#include <utils/Debug.h>
#include <utils/ResourceTypes.h>
#include <utils/String16.h>
#include <utils/String8.h>
#include <utils/TextOutput.h>
#include <utils/Log.h>

#include <stdlib.h>
#include <string.h>
#include <memory.h>
#include <ctype.h>
#include <stdint.h>

#ifndef INT32_MAX
#define INT32_MAX ((int32_t)(2147483647))
#endif

#define POOL_NOISY(x) //x
#define XML_NOISY(x) //x
#define TABLE_NOISY(x) //x
#define TABLE_GETENTRY(x) //x
#define TABLE_SUPER_NOISY(x) //x
#define LOAD_TABLE_NOISY(x) //x
#define TABLE_THEME(x) //x

namespace android {

#ifdef HAVE_WINSOCK
#undef  nhtol
#undef  htonl

#ifdef HAVE_LITTLE_ENDIAN
#define ntohl(x)    ( ((x) << 24) | (((x) >> 24) & 255) | (((x) << 8) & 0xff0000) | (((x) >> 8) & 0xff00) )
#define htonl(x)    ntohl(x)
#define ntohs(x)    ( (((x) << 8) & 0xff00) | (((x) >> 8) & 255) )
#define htons(x)    ntohs(x)
#else
#define ntohl(x)    (x)
#define htonl(x)    (x)
#define ntohs(x)    (x)
#define htons(x)    (x)
#endif
#endif

#define IDMAP_MAGIC         0x706d6469
// size measured in sizeof(uint32_t)
#define IDMAP_HEADER_SIZE (ResTable::IDMAP_HEADER_SIZE_BYTES / sizeof(uint32_t))

static void printToLogFunc(void* cookie, const char* txt)
{
    ALOGV("%s", txt);
}

// Standard C isspace() is only required to look at the low byte of its input, so
// produces incorrect results for UTF-16 characters.  For safety's sake, assume that
// any high-byte UTF-16 code point is not whitespace.
inline int isspace16(char16_t c) {
    return (c < 0x0080 && isspace(c));
}

// range checked; guaranteed to NUL-terminate within the stated number of available slots
// NOTE: if this truncates the dst string due to running out of space, no attempt is
// made to avoid splitting surrogate pairs.
static void strcpy16_dtoh(uint16_t* dst, const uint16_t* src, size_t avail)
{
    uint16_t* last = dst + avail - 1;
    while (*src && (dst < last)) {
        char16_t s = dtohs(*src);
        *dst++ = s;
        src++;
    }
    *dst = 0;
}

static status_t validate_chunk(const ResChunk_header* chunk,
                               size_t minSize,
                               const uint8_t* dataEnd,
                               const char* name)
{
    const uint16_t headerSize = dtohs(chunk->headerSize);
    const uint32_t size = dtohl(chunk->size);

    if (headerSize >= minSize) {
        if (headerSize <= size) {
            if (((headerSize|size)&0x3) == 0) {
                if ((ssize_t)size <= (dataEnd-((const uint8_t*)chunk))) {
                    return NO_ERROR;
                }
                LOGW("%s data size %p extends beyond resource end %p.",
                     name, (void*)size,
                     (void*)(dataEnd-((const uint8_t*)chunk)));
                return BAD_TYPE;
            }
            LOGW("%s size 0x%x or headerSize 0x%x is not on an integer boundary.",
                 name, (int)size, (int)headerSize);
            return BAD_TYPE;
        }
        LOGW("%s size %p is smaller than header size %p.",
             name, (void*)size, (void*)(int)headerSize);
        return BAD_TYPE;
    }
    LOGW("%s header size %p is too small.",
         name, (void*)(int)headerSize);
    return BAD_TYPE;
}

inline void Res_value::copyFrom_dtoh(const Res_value& src)
{
    size = dtohs(src.size);
    res0 = src.res0;
    dataType = src.dataType;
    data = dtohl(src.data);
}

void Res_png_9patch::deviceToFile()
{
    for (int i = 0; i < numXDivs; i++) {
        xDivs[i] = htonl(xDivs[i]);
    }
    for (int i = 0; i < numYDivs; i++) {
        yDivs[i] = htonl(yDivs[i]);
    }
    paddingLeft = htonl(paddingLeft);
    paddingRight = htonl(paddingRight);
    paddingTop = htonl(paddingTop);
    paddingBottom = htonl(paddingBottom);
    for (int i=0; i<numColors; i++) {
        colors[i] = htonl(colors[i]);
    }
}

void Res_png_9patch::fileToDevice()
{
    for (int i = 0; i < numXDivs; i++) {
        xDivs[i] = ntohl(xDivs[i]);
    }
    for (int i = 0; i < numYDivs; i++) {
        yDivs[i] = ntohl(yDivs[i]);
    }
    paddingLeft = ntohl(paddingLeft);
    paddingRight = ntohl(paddingRight);
    paddingTop = ntohl(paddingTop);
    paddingBottom = ntohl(paddingBottom);
    for (int i=0; i<numColors; i++) {
        colors[i] = ntohl(colors[i]);
    }
}

size_t Res_png_9patch::serializedSize()
{
    // The size of this struct is 32 bytes on the 32-bit target system
    // 4 * int8_t
    // 4 * int32_t
    // 3 * pointer
    return 32
            + numXDivs * sizeof(int32_t)
            + numYDivs * sizeof(int32_t)
            + numColors * sizeof(uint32_t);
}

void* Res_png_9patch::serialize()
{
    // Use calloc since we're going to leave a few holes in the data
    // and want this to run cleanly under valgrind
    void* newData = calloc(1, serializedSize());
    serialize(newData);
    return newData;
}

void Res_png_9patch::serialize(void * outData)
{
    char* data = (char*) outData;
    memmove(data, &wasDeserialized, 4);     // copy  wasDeserialized, numXDivs, numYDivs, numColors
    memmove(data + 12, &paddingLeft, 16);   // copy paddingXXXX
    data += 32;

    memmove(data, this->xDivs, numXDivs * sizeof(int32_t));
    data +=  numXDivs * sizeof(int32_t);
    memmove(data, this->yDivs, numYDivs * sizeof(int32_t));
    data +=  numYDivs * sizeof(int32_t);
    memmove(data, this->colors, numColors * sizeof(uint32_t));
}

static void deserializeInternal(const void* inData, Res_png_9patch* outData) {
    char* patch = (char*) inData;
    if (inData != outData) {
        memmove(&outData->wasDeserialized, patch, 4);     // copy  wasDeserialized, numXDivs, numYDivs, numColors
        memmove(&outData->paddingLeft, patch + 12, 4);     // copy  wasDeserialized, numXDivs, numYDivs, numColors
    }
    outData->wasDeserialized = true;
    char* data = (char*)outData;
    data +=  sizeof(Res_png_9patch);
    outData->xDivs = (int32_t*) data;
    data +=  outData->numXDivs * sizeof(int32_t);
    outData->yDivs = (int32_t*) data;
    data +=  outData->numYDivs * sizeof(int32_t);
    outData->colors = (uint32_t*) data;
}

static bool assertIdmapHeader(const uint32_t* map, size_t sizeBytes)
{
    if (sizeBytes < ResTable::IDMAP_HEADER_SIZE_BYTES) {
        LOGW("idmap assertion failed: size=%d bytes\n", sizeBytes);
        return false;
    }
    if (*map != htodl(IDMAP_MAGIC)) { // htodl: map data expected to be in correct endianess
        LOGW("idmap assertion failed: invalid magic found (is 0x%08x, expected 0x%08x)\n",
             *map, htodl(IDMAP_MAGIC));
        return false;
    }
    return true;
}

static status_t idmapLookup(const uint32_t* map, size_t sizeBytes, uint32_t key, uint32_t* outValue)
{
    // see README for details on the format of map
    if (!assertIdmapHeader(map, sizeBytes)) {
        return UNKNOWN_ERROR;
    }
    map = map + IDMAP_HEADER_SIZE; // skip ahead to data segment
    // size of data block, in uint32_t
    const size_t size = (sizeBytes - ResTable::IDMAP_HEADER_SIZE_BYTES) / sizeof(uint32_t);
    const uint32_t type = Res_GETTYPE(key) + 1; // add one, idmap stores "public" type id
    const uint32_t entry = Res_GETENTRY(key);
    const uint32_t typeCount = *map;

    if (type > typeCount) {
        LOGW("Resource ID map: type=%d exceeds number of types=%d\n", type, typeCount);
        return UNKNOWN_ERROR;
    }
    if (typeCount > size) {
        LOGW("Resource ID map: number of types=%d exceeds size of map=%d\n", typeCount, size);
        return UNKNOWN_ERROR;
    }
    const uint32_t typeOffset = map[type];
    if (typeOffset == 0) {
        *outValue = 0;
        return NO_ERROR;
    }
    if (typeOffset + 1 > size) {
        LOGW("Resource ID map: type offset=%d exceeds reasonable value, size of map=%d\n",
             typeOffset, size);
        return UNKNOWN_ERROR;
    }
    const uint32_t entryCount = map[typeOffset];
    const uint32_t entryOffset = map[typeOffset + 1];
    if (entryCount == 0 || entry < entryOffset || entry - entryOffset > entryCount - 1) {
        *outValue = 0;
        return NO_ERROR;
    }
    const uint32_t index = typeOffset + 2 + entry - entryOffset;
    if (index > size) {
        LOGW("Resource ID map: entry index=%d exceeds size of map=%d\n", index, size);
        *outValue = 0;
        return NO_ERROR;
    }
    *outValue = map[index];

    return NO_ERROR;
}

static status_t getIdmapPackageId(const uint32_t* map, size_t mapSize, uint32_t *outId)
{
    if (!assertIdmapHeader(map, mapSize)) {
        return UNKNOWN_ERROR;
    }
    const uint32_t* p = map + IDMAP_HEADER_SIZE + 1;
    while (*p == 0) {
        ++p;
    }
    *outId = (map[*p + IDMAP_HEADER_SIZE + 2] >> 24) & 0x000000ff;
    return NO_ERROR;
}

Res_png_9patch* Res_png_9patch::deserialize(const void* inData)
{
    if (sizeof(void*) != sizeof(int32_t)) {
        LOGE("Cannot deserialize on non 32-bit system\n");
        return NULL;
    }
    deserializeInternal(inData, (Res_png_9patch*) inData);
    return (Res_png_9patch*) inData;
}

// --------------------------------------------------------------------
// --------------------------------------------------------------------
// --------------------------------------------------------------------

ResStringPool::ResStringPool()
    : mError(NO_INIT), mOwnedData(NULL), mHeader(NULL), mCache(NULL)
{
}

ResStringPool::ResStringPool(const void* data, size_t size, bool copyData)
    : mError(NO_INIT), mOwnedData(NULL), mHeader(NULL), mCache(NULL)
{
    setTo(data, size, copyData);
}

ResStringPool::~ResStringPool()
{
    uninit();
}

status_t ResStringPool::setTo(const void* data, size_t size, bool copyData)
{
    if (!data || !size) {
        return (mError=BAD_TYPE);
    }

    uninit();

    const bool notDeviceEndian = htods(0xf0) != 0xf0;

    if (copyData || notDeviceEndian) {
        mOwnedData = malloc(size);
        if (mOwnedData == NULL) {
            return (mError=NO_MEMORY);
        }
        memcpy(mOwnedData, data, size);
        data = mOwnedData;
    }

    mHeader = (const ResStringPool_header*)data;

    if (notDeviceEndian) {
        ResStringPool_header* h = const_cast<ResStringPool_header*>(mHeader);
        h->header.headerSize = dtohs(mHeader->header.headerSize);
        h->header.type = dtohs(mHeader->header.type);
        h->header.size = dtohl(mHeader->header.size);
        h->stringCount = dtohl(mHeader->stringCount);
        h->styleCount = dtohl(mHeader->styleCount);
        h->flags = dtohl(mHeader->flags);
        h->stringsStart = dtohl(mHeader->stringsStart);
        h->stylesStart = dtohl(mHeader->stylesStart);
    }

    if (mHeader->header.headerSize > mHeader->header.size
            || mHeader->header.size > size) {
        LOGW("Bad string block: header size %d or total size %d is larger than data size %d\n",
                (int)mHeader->header.headerSize, (int)mHeader->header.size, (int)size);
        return (mError=BAD_TYPE);
    }
    mSize = mHeader->header.size;
    mEntries = (const uint32_t*)
        (((const uint8_t*)data)+mHeader->header.headerSize);

    if (mHeader->stringCount > 0) {
        if ((mHeader->stringCount*sizeof(uint32_t) < mHeader->stringCount)  // uint32 overflow?
            || (mHeader->header.headerSize+(mHeader->stringCount*sizeof(uint32_t)))
                > size) {
            LOGW("Bad string block: entry of %d items extends past data size %d\n",
                    (int)(mHeader->header.headerSize+(mHeader->stringCount*sizeof(uint32_t))),
                    (int)size);
            return (mError=BAD_TYPE);
        }

        size_t charSize;
        if (mHeader->flags&ResStringPool_header::UTF8_FLAG) {
            charSize = sizeof(uint8_t);
            mCache = (char16_t**)malloc(sizeof(char16_t**)*mHeader->stringCount);
            memset(mCache, 0, sizeof(char16_t**)*mHeader->stringCount);
        } else {
            charSize = sizeof(char16_t);
        }

        mStrings = (const void*)
            (((const uint8_t*)data)+mHeader->stringsStart);
        if (mHeader->stringsStart >= (mHeader->header.size-sizeof(uint16_t))) {
            LOGW("Bad string block: string pool starts at %d, after total size %d\n",
                    (int)mHeader->stringsStart, (int)mHeader->header.size);
            return (mError=BAD_TYPE);
        }
        if (mHeader->styleCount == 0) {
            mStringPoolSize =
                (mHeader->header.size-mHeader->stringsStart)/charSize;
        } else {
            // check invariant: styles starts before end of data
            if (mHeader->stylesStart >= (mHeader->header.size-sizeof(uint16_t))) {
                LOGW("Bad style block: style block starts at %d past data size of %d\n",
                    (int)mHeader->stylesStart, (int)mHeader->header.size);
                return (mError=BAD_TYPE);
            }
            // check invariant: styles follow the strings
            if (mHeader->stylesStart <= mHeader->stringsStart) {
                LOGW("Bad style block: style block starts at %d, before strings at %d\n",
                    (int)mHeader->stylesStart, (int)mHeader->stringsStart);
                return (mError=BAD_TYPE);
            }
            mStringPoolSize =
                (mHeader->stylesStart-mHeader->stringsStart)/charSize;
        }

        // check invariant: stringCount > 0 requires a string pool to exist
        if (mStringPoolSize == 0) {
            LOGW("Bad string block: stringCount is %d but pool size is 0\n", (int)mHeader->stringCount);
            return (mError=BAD_TYPE);
        }

        if (notDeviceEndian) {
            size_t i;
            uint32_t* e = const_cast<uint32_t*>(mEntries);
            for (i=0; i<mHeader->stringCount; i++) {
                e[i] = dtohl(mEntries[i]);
            }
            if (!(mHeader->flags&ResStringPool_header::UTF8_FLAG)) {
                const char16_t* strings = (const char16_t*)mStrings;
                char16_t* s = const_cast<char16_t*>(strings);
                for (i=0; i<mStringPoolSize; i++) {
                    s[i] = dtohs(strings[i]);
                }
            }
        }

        if ((mHeader->flags&ResStringPool_header::UTF8_FLAG &&
                ((uint8_t*)mStrings)[mStringPoolSize-1] != 0) ||
                (!mHeader->flags&ResStringPool_header::UTF8_FLAG &&
                ((char16_t*)mStrings)[mStringPoolSize-1] != 0)) {
            LOGW("Bad string block: last string is not 0-terminated\n");
            return (mError=BAD_TYPE);
        }
    } else {
        mStrings = NULL;
        mStringPoolSize = 0;
    }

    if (mHeader->styleCount > 0) {
        mEntryStyles = mEntries + mHeader->stringCount;
        // invariant: integer overflow in calculating mEntryStyles
        if (mEntryStyles < mEntries) {
            LOGW("Bad string block: integer overflow finding styles\n");
            return (mError=BAD_TYPE);
        }

        if (((const uint8_t*)mEntryStyles-(const uint8_t*)mHeader) > (int)size) {
            LOGW("Bad string block: entry of %d styles extends past data size %d\n",
                    (int)((const uint8_t*)mEntryStyles-(const uint8_t*)mHeader),
                    (int)size);
            return (mError=BAD_TYPE);
        }
        mStyles = (const uint32_t*)
            (((const uint8_t*)data)+mHeader->stylesStart);
        if (mHeader->stylesStart >= mHeader->header.size) {
            LOGW("Bad string block: style pool starts %d, after total size %d\n",
                    (int)mHeader->stylesStart, (int)mHeader->header.size);
            return (mError=BAD_TYPE);
        }
        mStylePoolSize =
            (mHeader->header.size-mHeader->stylesStart)/sizeof(uint32_t);

        if (notDeviceEndian) {
            size_t i;
            uint32_t* e = const_cast<uint32_t*>(mEntryStyles);
            for (i=0; i<mHeader->styleCount; i++) {
                e[i] = dtohl(mEntryStyles[i]);
            }
            uint32_t* s = const_cast<uint32_t*>(mStyles);
            for (i=0; i<mStylePoolSize; i++) {
                s[i] = dtohl(mStyles[i]);
            }
        }

        const ResStringPool_span endSpan = {
            { htodl(ResStringPool_span::END) },
            htodl(ResStringPool_span::END), htodl(ResStringPool_span::END)
        };
        if (memcmp(&mStyles[mStylePoolSize-(sizeof(endSpan)/sizeof(uint32_t))],
                   &endSpan, sizeof(endSpan)) != 0) {
            LOGW("Bad string block: last style is not 0xFFFFFFFF-terminated\n");
            return (mError=BAD_TYPE);
        }
    } else {
        mEntryStyles = NULL;
        mStyles = NULL;
        mStylePoolSize = 0;
    }

    return (mError=NO_ERROR);
}

status_t ResStringPool::getError() const
{
    return mError;
}

void ResStringPool::uninit()
{
    mError = NO_INIT;
    if (mOwnedData) {
        free(mOwnedData);
        mOwnedData = NULL;
    }
    if (mHeader != NULL && mCache != NULL) {
        for (size_t x = 0; x < mHeader->stringCount; x++) {
            if (mCache[x] != NULL) {
                free(mCache[x]);
                mCache[x] = NULL;
            }
        }
        free(mCache);
        mCache = NULL;
    }
}

/**
 * Strings in UTF-16 format have length indicated by a length encoded in the
 * stored data. It is either 1 or 2 characters of length data. This allows a
 * maximum length of 0x7FFFFFF (2147483647 bytes), but if you're storing that
 * much data in a string, you're abusing them.
 *
 * If the high bit is set, then there are two characters or 4 bytes of length
 * data encoded. In that case, drop the high bit of the first character and
 * add it together with the next character.
 */
static inline size_t
decodeLength(const char16_t** str)
{
    size_t len = **str;
    if ((len & 0x8000) != 0) {
        (*str)++;
        len = ((len & 0x7FFF) << 16) | **str;
    }
    (*str)++;
    return len;
}

/**
 * Strings in UTF-8 format have length indicated by a length encoded in the
 * stored data. It is either 1 or 2 characters of length data. This allows a
 * maximum length of 0x7FFF (32767 bytes), but you should consider storing
 * text in another way if you're using that much data in a single string.
 *
 * If the high bit is set, then there are two characters or 2 bytes of length
 * data encoded. In that case, drop the high bit of the first character and
 * add it together with the next character.
 */
static inline size_t
decodeLength(const uint8_t** str)
{
    size_t len = **str;
    if ((len & 0x80) != 0) {
        (*str)++;
        len = ((len & 0x7F) << 8) | **str;
    }
    (*str)++;
    return len;
}

const uint16_t* ResStringPool::stringAt(size_t idx, size_t* u16len) const
{
    if (mError == NO_ERROR && idx < mHeader->stringCount) {
        const bool isUTF8 = (mHeader->flags&ResStringPool_header::UTF8_FLAG) != 0;
        const uint32_t off = mEntries[idx]/(isUTF8?sizeof(char):sizeof(char16_t));
        if (off < (mStringPoolSize-1)) {
            if (!isUTF8) {
                const char16_t* strings = (char16_t*)mStrings;
                const char16_t* str = strings+off;

                *u16len = decodeLength(&str);
                if ((uint32_t)(str+*u16len-strings) < mStringPoolSize) {
                    return str;
                } else {
                    LOGW("Bad string block: string #%d extends to %d, past end at %d\n",
                            (int)idx, (int)(str+*u16len-strings), (int)mStringPoolSize);
                }
            } else {
                const uint8_t* strings = (uint8_t*)mStrings;
                const uint8_t* u8str = strings+off;

                *u16len = decodeLength(&u8str);
                size_t u8len = decodeLength(&u8str);

                // encLen must be less than 0x7FFF due to encoding.
                if ((uint32_t)(u8str+u8len-strings) < mStringPoolSize) {
                    AutoMutex lock(mDecodeLock);

                    if (mCache[idx] != NULL) {
                        return mCache[idx];
                    }

                    ssize_t actualLen = utf8_to_utf16_length(u8str, u8len);
                    if (actualLen < 0 || (size_t)actualLen != *u16len) {
                        LOGW("Bad string block: string #%lld decoded length is not correct "
                                "%lld vs %llu\n",
                                (long long)idx, (long long)actualLen, (long long)*u16len);
                        return NULL;
                    }

                    char16_t *u16str = (char16_t *)calloc(*u16len+1, sizeof(char16_t));
                    if (!u16str) {
                        LOGW("No memory when trying to allocate decode cache for string #%d\n",
                                (int)idx);
                        return NULL;
                    }

                    utf8_to_utf16(u8str, u8len, u16str);
                    mCache[idx] = u16str;
                    return u16str;
                } else {
                    LOGW("Bad string block: string #%lld extends to %lld, past end at %lld\n",
                            (long long)idx, (long long)(u8str+u8len-strings),
                            (long long)mStringPoolSize);
                }
            }
        } else {
            LOGW("Bad string block: string #%d entry is at %d, past end at %d\n",
                    (int)idx, (int)(off*sizeof(uint16_t)),
                    (int)(mStringPoolSize*sizeof(uint16_t)));
        }
    }
    return NULL;
}

const char* ResStringPool::string8At(size_t idx, size_t* outLen) const
{
    if (mError == NO_ERROR && idx < mHeader->stringCount) {
        const bool isUTF8 = (mHeader->flags&ResStringPool_header::UTF8_FLAG) != 0;
        const uint32_t off = mEntries[idx]/(isUTF8?sizeof(char):sizeof(char16_t));
        if (off < (mStringPoolSize-1)) {
            if (isUTF8) {
                const uint8_t* strings = (uint8_t*)mStrings;
                const uint8_t* str = strings+off;
                *outLen = decodeLength(&str);
                size_t encLen = decodeLength(&str);
                if ((uint32_t)(str+encLen-strings) < mStringPoolSize) {
                    return (const char*)str;
                } else {
                    LOGW("Bad string block: string #%d extends to %d, past end at %d\n",
                            (int)idx, (int)(str+encLen-strings), (int)mStringPoolSize);
                }
            }
        } else {
            LOGW("Bad string block: string #%d entry is at %d, past end at %d\n",
                    (int)idx, (int)(off*sizeof(uint16_t)),
                    (int)(mStringPoolSize*sizeof(uint16_t)));
        }
    }
    return NULL;
}

const ResStringPool_span* ResStringPool::styleAt(const ResStringPool_ref& ref) const
{
    return styleAt(ref.index);
}

const ResStringPool_span* ResStringPool::styleAt(size_t idx) const
{
    if (mError == NO_ERROR && idx < mHeader->styleCount) {
        const uint32_t off = (mEntryStyles[idx]/sizeof(uint32_t));
        if (off < mStylePoolSize) {
            return (const ResStringPool_span*)(mStyles+off);
        } else {
            LOGW("Bad string block: style #%d entry is at %d, past end at %d\n",
                    (int)idx, (int)(off*sizeof(uint32_t)),
                    (int)(mStylePoolSize*sizeof(uint32_t)));
        }
    }
    return NULL;
}

ssize_t ResStringPool::indexOfString(const char16_t* str, size_t strLen) const
{
    if (mError != NO_ERROR) {
        return mError;
    }

    size_t len;

    // TODO optimize searching for UTF-8 strings taking into account
    // the cache fill to determine when to convert the searched-for
    // string key to UTF-8.

    if (mHeader->flags&ResStringPool_header::SORTED_FLAG) {
        // Do a binary search for the string...
        ssize_t l = 0;
        ssize_t h = mHeader->stringCount-1;

        ssize_t mid;
        while (l <= h) {
            mid = l + (h - l)/2;
            const char16_t* s = stringAt(mid, &len);
            int c = s ? strzcmp16(s, len, str, strLen) : -1;
            POOL_NOISY(printf("Looking for %s, at %s, cmp=%d, l/mid/h=%d/%d/%d\n",
                         String8(str).string(),
                         String8(s).string(),
                         c, (int)l, (int)mid, (int)h));
            if (c == 0) {
                return mid;
            } else if (c < 0) {
                l = mid + 1;
            } else {
                h = mid - 1;
            }
        }
    } else {
        // It is unusual to get the ID from an unsorted string block...
        // most often this happens because we want to get IDs for style
        // span tags; since those always appear at the end of the string
        // block, start searching at the back.
        for (int i=mHeader->stringCount-1; i>=0; i--) {
            const char16_t* s = stringAt(i, &len);
            POOL_NOISY(printf("Looking for %s, at %s, i=%d\n",
                         String8(str, strLen).string(),
                         String8(s).string(),
                         i));
            if (s && strzcmp16(s, len, str, strLen) == 0) {
                return i;
            }
        }
    }

    return NAME_NOT_FOUND;
}

size_t ResStringPool::size() const
{
    return (mError == NO_ERROR) ? mHeader->stringCount : 0;
}

#ifndef HAVE_ANDROID_OS
bool ResStringPool::isUTF8() const
{
    return (mHeader->flags&ResStringPool_header::UTF8_FLAG)!=0;
}
#endif

// --------------------------------------------------------------------
// --------------------------------------------------------------------
// --------------------------------------------------------------------

ResXMLParser::ResXMLParser(const ResXMLTree& tree)
    : mTree(tree), mEventCode(BAD_DOCUMENT)
{
}

void ResXMLParser::restart()
{
    mCurNode = NULL;
    mEventCode = mTree.mError == NO_ERROR ? START_DOCUMENT : BAD_DOCUMENT;
}
const ResStringPool& ResXMLParser::getStrings() const
{
    return mTree.mStrings;
}

ResXMLParser::event_code_t ResXMLParser::getEventType() const
{
    return mEventCode;
}

ResXMLParser::event_code_t ResXMLParser::next()
{
    if (mEventCode == START_DOCUMENT) {
        mCurNode = mTree.mRootNode;
        mCurExt = mTree.mRootExt;
        return (mEventCode=mTree.mRootCode);
    } else if (mEventCode >= FIRST_CHUNK_CODE) {
        return nextNode();
    }
    return mEventCode;
}

int32_t ResXMLParser::getCommentID() const
{
    return mCurNode != NULL ? dtohl(mCurNode->comment.index) : -1;
}

const uint16_t* ResXMLParser::getComment(size_t* outLen) const
{
    int32_t id = getCommentID();
    return id >= 0 ? mTree.mStrings.stringAt(id, outLen) : NULL;
}

uint32_t ResXMLParser::getLineNumber() const
{
    return mCurNode != NULL ? dtohl(mCurNode->lineNumber) : -1;
}

int32_t ResXMLParser::getTextID() const
{
    if (mEventCode == TEXT) {
        return dtohl(((const ResXMLTree_cdataExt*)mCurExt)->data.index);
    }
    return -1;
}

const uint16_t* ResXMLParser::getText(size_t* outLen) const
{
    int32_t id = getTextID();
    return id >= 0 ? mTree.mStrings.stringAt(id, outLen) : NULL;
}

ssize_t ResXMLParser::getTextValue(Res_value* outValue) const
{
    if (mEventCode == TEXT) {
        outValue->copyFrom_dtoh(((const ResXMLTree_cdataExt*)mCurExt)->typedData);
        return sizeof(Res_value);
    }
    return BAD_TYPE;
}

int32_t ResXMLParser::getNamespacePrefixID() const
{
    if (mEventCode == START_NAMESPACE || mEventCode == END_NAMESPACE) {
        return dtohl(((const ResXMLTree_namespaceExt*)mCurExt)->prefix.index);
    }
    return -1;
}

const uint16_t* ResXMLParser::getNamespacePrefix(size_t* outLen) const
{
    int32_t id = getNamespacePrefixID();
    //printf("prefix=%d  event=%p\n", id, mEventCode);
    return id >= 0 ? mTree.mStrings.stringAt(id, outLen) : NULL;
}

int32_t ResXMLParser::getNamespaceUriID() const
{
    if (mEventCode == START_NAMESPACE || mEventCode == END_NAMESPACE) {
        return dtohl(((const ResXMLTree_namespaceExt*)mCurExt)->uri.index);
    }
    return -1;
}

const uint16_t* ResXMLParser::getNamespaceUri(size_t* outLen) const
{
    int32_t id = getNamespaceUriID();
    //printf("uri=%d  event=%p\n", id, mEventCode);
    return id >= 0 ? mTree.mStrings.stringAt(id, outLen) : NULL;
}

int32_t ResXMLParser::getElementNamespaceID() const
{
    if (mEventCode == START_TAG) {
        return dtohl(((const ResXMLTree_attrExt*)mCurExt)->ns.index);
    }
    if (mEventCode == END_TAG) {
        return dtohl(((const ResXMLTree_endElementExt*)mCurExt)->ns.index);
    }
    return -1;
}

const uint16_t* ResXMLParser::getElementNamespace(size_t* outLen) const
{
    int32_t id = getElementNamespaceID();
    return id >= 0 ? mTree.mStrings.stringAt(id, outLen) : NULL;
}

int32_t ResXMLParser::getElementNameID() const
{
    if (mEventCode == START_TAG) {
        return dtohl(((const ResXMLTree_attrExt*)mCurExt)->name.index);
    }
    if (mEventCode == END_TAG) {
        return dtohl(((const ResXMLTree_endElementExt*)mCurExt)->name.index);
    }
    return -1;
}

const uint16_t* ResXMLParser::getElementName(size_t* outLen) const
{
    int32_t id = getElementNameID();
    return id >= 0 ? mTree.mStrings.stringAt(id, outLen) : NULL;
}

size_t ResXMLParser::getAttributeCount() const
{
    if (mEventCode == START_TAG) {
        return dtohs(((const ResXMLTree_attrExt*)mCurExt)->attributeCount);
    }
    return 0;
}

int32_t ResXMLParser::getAttributeNamespaceID(size_t idx) const
{
    if (mEventCode == START_TAG) {
        const ResXMLTree_attrExt* tag = (const ResXMLTree_attrExt*)mCurExt;
        if (idx < dtohs(tag->attributeCount)) {
            const ResXMLTree_attribute* attr = (const ResXMLTree_attribute*)
                (((const uint8_t*)tag)
                 + dtohs(tag->attributeStart)
                 + (dtohs(tag->attributeSize)*idx));
            return dtohl(attr->ns.index);
        }
    }
    return -2;
}

const uint16_t* ResXMLParser::getAttributeNamespace(size_t idx, size_t* outLen) const
{
    int32_t id = getAttributeNamespaceID(idx);
    //printf("attribute namespace=%d  idx=%d  event=%p\n", id, idx, mEventCode);
    //XML_NOISY(printf("getAttributeNamespace 0x%x=0x%x\n", idx, id));
    return id >= 0 ? mTree.mStrings.stringAt(id, outLen) : NULL;
}

int32_t ResXMLParser::getAttributeNameID(size_t idx) const
{
    if (mEventCode == START_TAG) {
        const ResXMLTree_attrExt* tag = (const ResXMLTree_attrExt*)mCurExt;
        if (idx < dtohs(tag->attributeCount)) {
            const ResXMLTree_attribute* attr = (const ResXMLTree_attribute*)
                (((const uint8_t*)tag)
                 + dtohs(tag->attributeStart)
                 + (dtohs(tag->attributeSize)*idx));
            return dtohl(attr->name.index);
        }
    }
    return -1;
}

const uint16_t* ResXMLParser::getAttributeName(size_t idx, size_t* outLen) const
{
    int32_t id = getAttributeNameID(idx);
    //printf("attribute name=%d  idx=%d  event=%p\n", id, idx, mEventCode);
    //XML_NOISY(printf("getAttributeName 0x%x=0x%x\n", idx, id));
    return id >= 0 ? mTree.mStrings.stringAt(id, outLen) : NULL;
}

uint32_t ResXMLParser::getAttributeNameResID(size_t idx) const
{
    int32_t id = getAttributeNameID(idx);
    if (id >= 0 && (size_t)id < mTree.mNumResIds) {
        return dtohl(mTree.mResIds[id]);
    }
    return 0;
}

int32_t ResXMLParser::getAttributeValueStringID(size_t idx) const
{
    if (mEventCode == START_TAG) {
        const ResXMLTree_attrExt* tag = (const ResXMLTree_attrExt*)mCurExt;
        if (idx < dtohs(tag->attributeCount)) {
            const ResXMLTree_attribute* attr = (const ResXMLTree_attribute*)
                (((const uint8_t*)tag)
                 + dtohs(tag->attributeStart)
                 + (dtohs(tag->attributeSize)*idx));
            return dtohl(attr->rawValue.index);
        }
    }
    return -1;
}

const uint16_t* ResXMLParser::getAttributeStringValue(size_t idx, size_t* outLen) const
{
    int32_t id = getAttributeValueStringID(idx);
    //XML_NOISY(printf("getAttributeValue 0x%x=0x%x\n", idx, id));
    return id >= 0 ? mTree.mStrings.stringAt(id, outLen) : NULL;
}

int32_t ResXMLParser::getAttributeDataType(size_t idx) const
{
    if (mEventCode == START_TAG) {
        const ResXMLTree_attrExt* tag = (const ResXMLTree_attrExt*)mCurExt;
        if (idx < dtohs(tag->attributeCount)) {
            const ResXMLTree_attribute* attr = (const ResXMLTree_attribute*)
                (((const uint8_t*)tag)
                 + dtohs(tag->attributeStart)
                 + (dtohs(tag->attributeSize)*idx));
            return attr->typedValue.dataType;
        }
    }
    return Res_value::TYPE_NULL;
}

int32_t ResXMLParser::getAttributeData(size_t idx) const
{
    if (mEventCode == START_TAG) {
        const ResXMLTree_attrExt* tag = (const ResXMLTree_attrExt*)mCurExt;
        if (idx < dtohs(tag->attributeCount)) {
            const ResXMLTree_attribute* attr = (const ResXMLTree_attribute*)
                (((const uint8_t*)tag)
                 + dtohs(tag->attributeStart)
                 + (dtohs(tag->attributeSize)*idx));
            return dtohl(attr->typedValue.data);
        }
    }
    return 0;
}

ssize_t ResXMLParser::getAttributeValue(size_t idx, Res_value* outValue) const
{
    if (mEventCode == START_TAG) {
        const ResXMLTree_attrExt* tag = (const ResXMLTree_attrExt*)mCurExt;
        if (idx < dtohs(tag->attributeCount)) {
            const ResXMLTree_attribute* attr = (const ResXMLTree_attribute*)
                (((const uint8_t*)tag)
                 + dtohs(tag->attributeStart)
                 + (dtohs(tag->attributeSize)*idx));
            outValue->copyFrom_dtoh(attr->typedValue);
            return sizeof(Res_value);
        }
    }
    return BAD_TYPE;
}

ssize_t ResXMLParser::indexOfAttribute(const char* ns, const char* attr) const
{
    String16 nsStr(ns != NULL ? ns : "");
    String16 attrStr(attr);
    return indexOfAttribute(ns ? nsStr.string() : NULL, ns ? nsStr.size() : 0,
                            attrStr.string(), attrStr.size());
}

ssize_t ResXMLParser::indexOfAttribute(const char16_t* ns, size_t nsLen,
                                       const char16_t* attr, size_t attrLen) const
{
    if (mEventCode == START_TAG) {
        const size_t N = getAttributeCount();
        for (size_t i=0; i<N; i++) {
            size_t curNsLen, curAttrLen;
            const char16_t* curNs = getAttributeNamespace(i, &curNsLen);
            const char16_t* curAttr = getAttributeName(i, &curAttrLen);
            //printf("%d: ns=%p attr=%p curNs=%p curAttr=%p\n",
            //       i, ns, attr, curNs, curAttr);
            //printf(" --> attr=%s, curAttr=%s\n",
            //       String8(attr).string(), String8(curAttr).string());
            if (attr && curAttr && (strzcmp16(attr, attrLen, curAttr, curAttrLen) == 0)) {
                if (ns == NULL) {
                    if (curNs == NULL) return i;
                } else if (curNs != NULL) {
                    //printf(" --> ns=%s, curNs=%s\n",
                    //       String8(ns).string(), String8(curNs).string());
                    if (strzcmp16(ns, nsLen, curNs, curNsLen) == 0) return i;
                }
            }
        }
    }

    return NAME_NOT_FOUND;
}

ssize_t ResXMLParser::indexOfID() const
{
    if (mEventCode == START_TAG) {
        const ssize_t idx = dtohs(((const ResXMLTree_attrExt*)mCurExt)->idIndex);
        if (idx > 0) return (idx-1);
    }
    return NAME_NOT_FOUND;
}

ssize_t ResXMLParser::indexOfClass() const
{
    if (mEventCode == START_TAG) {
        const ssize_t idx = dtohs(((const ResXMLTree_attrExt*)mCurExt)->classIndex);
        if (idx > 0) return (idx-1);
    }
    return NAME_NOT_FOUND;
}

ssize_t ResXMLParser::indexOfStyle() const
{
    if (mEventCode == START_TAG) {
        const ssize_t idx = dtohs(((const ResXMLTree_attrExt*)mCurExt)->styleIndex);
        if (idx > 0) return (idx-1);
    }
    return NAME_NOT_FOUND;
}

ResXMLParser::event_code_t ResXMLParser::nextNode()
{
    if (mEventCode < 0) {
        return mEventCode;
    }

    do {
        const ResXMLTree_node* next = (const ResXMLTree_node*)
            (((const uint8_t*)mCurNode) + dtohl(mCurNode->header.size));
        //LOGW("Next node: prev=%p, next=%p\n", mCurNode, next);
        
        if (((const uint8_t*)next) >= mTree.mDataEnd) {
            mCurNode = NULL;
            return (mEventCode=END_DOCUMENT);
        }

        if (mTree.validateNode(next) != NO_ERROR) {
            mCurNode = NULL;
            return (mEventCode=BAD_DOCUMENT);
        }

        mCurNode = next;
        const uint16_t headerSize = dtohs(next->header.headerSize);
        const uint32_t totalSize = dtohl(next->header.size);
        mCurExt = ((const uint8_t*)next) + headerSize;
        size_t minExtSize = 0;
        event_code_t eventCode = (event_code_t)dtohs(next->header.type);
        switch ((mEventCode=eventCode)) {
            case RES_XML_START_NAMESPACE_TYPE:
            case RES_XML_END_NAMESPACE_TYPE:
                minExtSize = sizeof(ResXMLTree_namespaceExt);
                break;
            case RES_XML_START_ELEMENT_TYPE:
                minExtSize = sizeof(ResXMLTree_attrExt);
                break;
            case RES_XML_END_ELEMENT_TYPE:
                minExtSize = sizeof(ResXMLTree_endElementExt);
                break;
            case RES_XML_CDATA_TYPE:
                minExtSize = sizeof(ResXMLTree_cdataExt);
                break;
            default:
                LOGW("Unknown XML block: header type %d in node at %d\n",
                     (int)dtohs(next->header.type),
                     (int)(((const uint8_t*)next)-((const uint8_t*)mTree.mHeader)));
                continue;
        }
        
        if ((totalSize-headerSize) < minExtSize) {
            LOGW("Bad XML block: header type 0x%x in node at 0x%x has size %d, need %d\n",
                 (int)dtohs(next->header.type),
                 (int)(((const uint8_t*)next)-((const uint8_t*)mTree.mHeader)),
                 (int)(totalSize-headerSize), (int)minExtSize);
            return (mEventCode=BAD_DOCUMENT);
        }
        
        //printf("CurNode=%p, CurExt=%p, headerSize=%d, minExtSize=%d\n",
        //       mCurNode, mCurExt, headerSize, minExtSize);
        
        return eventCode;
    } while (true);
}

void ResXMLParser::getPosition(ResXMLParser::ResXMLPosition* pos) const
{
    pos->eventCode = mEventCode;
    pos->curNode = mCurNode;
    pos->curExt = mCurExt;
}

void ResXMLParser::setPosition(const ResXMLParser::ResXMLPosition& pos)
{
    mEventCode = pos.eventCode;
    mCurNode = pos.curNode;
    mCurExt = pos.curExt;
}


// --------------------------------------------------------------------

static volatile int32_t gCount = 0;

ResXMLTree::ResXMLTree()
    : ResXMLParser(*this)
    , mError(NO_INIT), mOwnedData(NULL)
{
    //ALOGI("Creating ResXMLTree %p #%d\n", this, android_atomic_inc(&gCount)+1);
    restart();
}

ResXMLTree::ResXMLTree(const void* data, size_t size, bool copyData)
    : ResXMLParser(*this)
    , mError(NO_INIT), mOwnedData(NULL)
{
    //ALOGI("Creating ResXMLTree %p #%d\n", this, android_atomic_inc(&gCount)+1);
    setTo(data, size, copyData);
}

ResXMLTree::~ResXMLTree()
{
    //ALOGI("Destroying ResXMLTree in %p #%d\n", this, android_atomic_dec(&gCount)-1);
    uninit();
}

status_t ResXMLTree::setTo(const void* data, size_t size, bool copyData)
{
    uninit();
    mEventCode = START_DOCUMENT;

    if (copyData) {
        mOwnedData = malloc(size);
        if (mOwnedData == NULL) {
            return (mError=NO_MEMORY);
        }
        memcpy(mOwnedData, data, size);
        data = mOwnedData;
    }

    mHeader = (const ResXMLTree_header*)data;
    mSize = dtohl(mHeader->header.size);
    if (dtohs(mHeader->header.headerSize) > mSize || mSize > size) {
        LOGW("Bad XML block: header size %d or total size %d is larger than data size %d\n",
             (int)dtohs(mHeader->header.headerSize),
             (int)dtohl(mHeader->header.size), (int)size);
        mError = BAD_TYPE;
        restart();
        return mError;
    }
    mDataEnd = ((const uint8_t*)mHeader) + mSize;

    mStrings.uninit();
    mRootNode = NULL;
    mResIds = NULL;
    mNumResIds = 0;

    // First look for a couple interesting chunks: the string block
    // and first XML node.
    const ResChunk_header* chunk =
        (const ResChunk_header*)(((const uint8_t*)mHeader) + dtohs(mHeader->header.headerSize));
    const ResChunk_header* lastChunk = chunk;
    while (((const uint8_t*)chunk) < (mDataEnd-sizeof(ResChunk_header)) &&
           ((const uint8_t*)chunk) < (mDataEnd-dtohl(chunk->size))) {
        status_t err = validate_chunk(chunk, sizeof(ResChunk_header), mDataEnd, "XML");
        if (err != NO_ERROR) {
            mError = err;
            goto done;
        }
        const uint16_t type = dtohs(chunk->type);
        const size_t size = dtohl(chunk->size);
        XML_NOISY(printf("Scanning @ %p: type=0x%x, size=0x%x\n",
                     (void*)(((uint32_t)chunk)-((uint32_t)mHeader)), type, size));
        if (type == RES_STRING_POOL_TYPE) {
            mStrings.setTo(chunk, size);
        } else if (type == RES_XML_RESOURCE_MAP_TYPE) {
            mResIds = (const uint32_t*)
                (((const uint8_t*)chunk)+dtohs(chunk->headerSize));
            mNumResIds = (dtohl(chunk->size)-dtohs(chunk->headerSize))/sizeof(uint32_t);
        } else if (type >= RES_XML_FIRST_CHUNK_TYPE
                   && type <= RES_XML_LAST_CHUNK_TYPE) {
            if (validateNode((const ResXMLTree_node*)chunk) != NO_ERROR) {
                mError = BAD_TYPE;
                goto done;
            }
            mCurNode = (const ResXMLTree_node*)lastChunk;
            if (nextNode() == BAD_DOCUMENT) {
                mError = BAD_TYPE;
                goto done;
            }
            mRootNode = mCurNode;
            mRootExt = mCurExt;
            mRootCode = mEventCode;
            break;
        } else {
            XML_NOISY(printf("Skipping unknown chunk!\n"));
        }
        lastChunk = chunk;
        chunk = (const ResChunk_header*)
            (((const uint8_t*)chunk) + size);
    }

    if (mRootNode == NULL) {
        LOGW("Bad XML block: no root element node found\n");
        mError = BAD_TYPE;
        goto done;
    }

    mError = mStrings.getError();

done:
    restart();
    return mError;
}

status_t ResXMLTree::getError() const
{
    return mError;
}

void ResXMLTree::uninit()
{
    mError = NO_INIT;
    mStrings.uninit();
    if (mOwnedData) {
        free(mOwnedData);
        mOwnedData = NULL;
    }
    restart();
}

status_t ResXMLTree::validateNode(const ResXMLTree_node* node) const
{
    const uint16_t eventCode = dtohs(node->header.type);

    status_t err = validate_chunk(
        &node->header, sizeof(ResXMLTree_node),
        mDataEnd, "ResXMLTree_node");

    if (err >= NO_ERROR) {
        // Only perform additional validation on START nodes
        if (eventCode != RES_XML_START_ELEMENT_TYPE) {
            return NO_ERROR;
        }

        const uint16_t headerSize = dtohs(node->header.headerSize);
        const uint32_t size = dtohl(node->header.size);
        const ResXMLTree_attrExt* attrExt = (const ResXMLTree_attrExt*)
            (((const uint8_t*)node) + headerSize);
        // check for sensical values pulled out of the stream so far...
        if ((size >= headerSize + sizeof(ResXMLTree_attrExt))
                && ((void*)attrExt > (void*)node)) {
            const size_t attrSize = ((size_t)dtohs(attrExt->attributeSize))
                * dtohs(attrExt->attributeCount);
            if ((dtohs(attrExt->attributeStart)+attrSize) <= (size-headerSize)) {
                return NO_ERROR;
            }
            LOGW("Bad XML block: node attributes use 0x%x bytes, only have 0x%x bytes\n",
                    (unsigned int)(dtohs(attrExt->attributeStart)+attrSize),
                    (unsigned int)(size-headerSize));
        }
        else {
            LOGW("Bad XML start block: node header size 0x%x, size 0x%x\n",
                (unsigned int)headerSize, (unsigned int)size);
        }
        return BAD_TYPE;
    }

    return err;

#if 0
    const bool isStart = dtohs(node->header.type) == RES_XML_START_ELEMENT_TYPE;

    const uint16_t headerSize = dtohs(node->header.headerSize);
    const uint32_t size = dtohl(node->header.size);

    if (headerSize >= (isStart ? sizeof(ResXMLTree_attrNode) : sizeof(ResXMLTree_node))) {
        if (size >= headerSize) {
            if (((const uint8_t*)node) <= (mDataEnd-size)) {
                if (!isStart) {
                    return NO_ERROR;
                }
                if ((((size_t)dtohs(node->attributeSize))*dtohs(node->attributeCount))
                        <= (size-headerSize)) {
                    return NO_ERROR;
                }
                LOGW("Bad XML block: node attributes use 0x%x bytes, only have 0x%x bytes\n",
                        ((int)dtohs(node->attributeSize))*dtohs(node->attributeCount),
                        (int)(size-headerSize));
                return BAD_TYPE;
            }
            LOGW("Bad XML block: node at 0x%x extends beyond data end 0x%x\n",
                    (int)(((const uint8_t*)node)-((const uint8_t*)mHeader)), (int)mSize);
            return BAD_TYPE;
        }
        LOGW("Bad XML block: node at 0x%x header size 0x%x smaller than total size 0x%x\n",
                (int)(((const uint8_t*)node)-((const uint8_t*)mHeader)),
                (int)headerSize, (int)size);
        return BAD_TYPE;
    }
    LOGW("Bad XML block: node at 0x%x header size 0x%x too small\n",
            (int)(((const uint8_t*)node)-((const uint8_t*)mHeader)),
            (int)headerSize);
    return BAD_TYPE;
#endif
}

// --------------------------------------------------------------------
// --------------------------------------------------------------------
// --------------------------------------------------------------------

struct ResTable::Header
{
    Header(ResTable* _owner) : owner(_owner), ownedData(NULL), header(NULL),
        resourceIDMap(NULL), resourceIDMapSize(0) { }

    ~Header()
    {
        free(resourceIDMap);
    }

    ResTable* const                 owner;
    void*                           ownedData;
    const ResTable_header*          header;
    size_t                          size;
    const uint8_t*                  dataEnd;
    size_t                          index;
    void*                           cookie;

    ResStringPool                   values;
    uint32_t*                       resourceIDMap;
    size_t                          resourceIDMapSize;
};

struct ResTable::Type
{
    Type(const Header* _header, const Package* _package, size_t count)
        : header(_header), package(_package), entryCount(count),
          typeSpec(NULL), typeSpecFlags(NULL) { }
    const Header* const             header;
    const Package* const            package;
    const size_t                    entryCount;
    const ResTable_typeSpec*        typeSpec;
    const uint32_t*                 typeSpecFlags;
    Vector<const ResTable_type*>    configs;
};

struct ResTable::Package
{
    Package(ResTable* _owner, const Header* _header, const ResTable_package* _package)
        : owner(_owner), header(_header), package(_package) { }
    ~Package()
    {
        size_t i = types.size();
        while (i > 0) {
            i--;
            delete types[i];
        }
    }
    
    ResTable* const                 owner;
    const Header* const             header;
    const ResTable_package* const   package;
    Vector<Type*>                   types;

    ResStringPool                   typeStrings;
    ResStringPool                   keyStrings;
    
    const Type* getType(size_t idx) const {
        return idx < types.size() ? types[idx] : NULL;
    }
};

// A group of objects describing a particular resource package.
// The first in 'package' is always the root object (from the resource
// table that defined the package); the ones after are skins on top of it.
struct ResTable::PackageGroup
{
    PackageGroup(ResTable* _owner, const String16& _name, uint32_t _id)
        : owner(_owner), name(_name), id(_id), typeCount(0), bags(NULL) { }
    ~PackageGroup() {
        clearBagCache();
        const size_t N = packages.size();
        for (size_t i=0; i<N; i++) {
            Package* pkg = packages[i];
            if (pkg->owner == owner) {
                delete pkg;
            }
        }
    }

    void clearBagCache() {
        if (bags) {
            TABLE_NOISY(printf("bags=%p\n", bags));
            Package* pkg = packages[0];
            TABLE_NOISY(printf("typeCount=%x\n", typeCount));
            for (size_t i=0; i<typeCount; i++) {
                TABLE_NOISY(printf("type=%d\n", i));
                const Type* type = pkg->getType(i);
                if (type != NULL) {
                    bag_set** typeBags = bags[i];
                    TABLE_NOISY(printf("typeBags=%p\n", typeBags));
                    if (typeBags) {
                        TABLE_NOISY(printf("type->entryCount=%x\n", type->entryCount));
                        const size_t N = type->entryCount;
                        for (size_t j=0; j<N; j++) {
                            if (typeBags[j] && typeBags[j] != (bag_set*)0xFFFFFFFF)
                                free(typeBags[j]);
                        }
                        free(typeBags);
                    }
                }
            }
            free(bags);
            bags = NULL;
        }
    }
    
    ResTable* const                 owner;
    String16 const                  name;
    uint32_t const                  id;
    Vector<Package*>                packages;
    
    // This is for finding typeStrings and other common package stuff.
    Package*                        basePackage;

    // For quick access.
    size_t                          typeCount;
    
    // Computed attribute bags, first indexed by the type and second
    // by the entry in that type.
    bag_set***                      bags;
};

struct ResTable::bag_set
{
    size_t numAttrs;    // number in array
    size_t availAttrs;  // total space in array
    uint32_t typeSpecFlags;
    // Followed by 'numAttr' bag_entry structures.
};

ResTable::Theme::Theme(const ResTable& table)
    : mTable(table)
{
    memset(mPackages, 0, sizeof(mPackages));
}

ResTable::Theme::~Theme()
{
    for (size_t i=0; i<Res_MAXPACKAGE; i++) {
        package_info* pi = mPackages[i];
        if (pi != NULL) {
            free_package(pi);
        }
    }
}

void ResTable::Theme::free_package(package_info* pi)
{
    for (size_t j=0; j<pi->numTypes; j++) {
        theme_entry* te = pi->types[j].entries;
        if (te != NULL) {
            free(te);
        }
    }
    free(pi);
}

ResTable::Theme::package_info* ResTable::Theme::copy_package(package_info* pi)
{
    package_info* newpi = (package_info*)malloc(
        sizeof(package_info) + (pi->numTypes*sizeof(type_info)));
    newpi->numTypes = pi->numTypes;
    for (size_t j=0; j<newpi->numTypes; j++) {
        size_t cnt = pi->types[j].numEntries;
        newpi->types[j].numEntries = cnt;
        theme_entry* te = pi->types[j].entries;
        if (te != NULL) {
            theme_entry* newte = (theme_entry*)malloc(cnt*sizeof(theme_entry));
            newpi->types[j].entries = newte;
            memcpy(newte, te, cnt*sizeof(theme_entry));
        } else {
            newpi->types[j].entries = NULL;
        }
    }
    return newpi;
}

status_t ResTable::Theme::applyStyle(uint32_t resID, bool force)
{
    const bag_entry* bag;
    uint32_t bagTypeSpecFlags = 0;
    mTable.lock();
    const ssize_t N = mTable.getBagLocked(resID, &bag, &bagTypeSpecFlags);
    TABLE_NOISY(LOGV("Applying style 0x%08x to theme %p, count=%d", resID, this, N));
    if (N < 0) {
        mTable.unlock();
        return N;
    }

    uint32_t curPackage = 0xffffffff;
    ssize_t curPackageIndex = 0;
    package_info* curPI = NULL;
    uint32_t curType = 0xffffffff;
    size_t numEntries = 0;
    theme_entry* curEntries = NULL;

    const bag_entry* end = bag + N;
    while (bag < end) {
        const uint32_t attrRes = bag->map.name.ident;
        const uint32_t p = Res_GETPACKAGE(attrRes);
        const uint32_t t = Res_GETTYPE(attrRes);
        const uint32_t e = Res_GETENTRY(attrRes);

        if (curPackage != p) {
            const ssize_t pidx = mTable.getResourcePackageIndex(attrRes);
            if (pidx < 0) {
                LOGE("Style contains key with bad package: 0x%08x\n", attrRes);
                bag++;
                continue;
            }
            curPackage = p;
            curPackageIndex = pidx;
            curPI = mPackages[pidx];
            if (curPI == NULL) {
                PackageGroup* const grp = mTable.mPackageGroups[pidx];
                int cnt = grp->typeCount;
                curPI = (package_info*)malloc(
                    sizeof(package_info) + (cnt*sizeof(type_info)));
                curPI->numTypes = cnt;
                memset(curPI->types, 0, cnt*sizeof(type_info));
                mPackages[pidx] = curPI;
            }
            curType = 0xffffffff;
        }
        if (curType != t) {
            if (t >= curPI->numTypes) {
                LOGE("Style contains key with bad type: 0x%08x\n", attrRes);
                bag++;
                continue;
            }
            curType = t;
            curEntries = curPI->types[t].entries;
            if (curEntries == NULL) {
                PackageGroup* const grp = mTable.mPackageGroups[curPackageIndex];
                const Type* type = grp->packages[0]->getType(t);
                int cnt = type != NULL ? type->entryCount : 0;
                curEntries = (theme_entry*)malloc(cnt*sizeof(theme_entry));
                memset(curEntries, Res_value::TYPE_NULL, cnt*sizeof(theme_entry));
                curPI->types[t].numEntries = cnt;
                curPI->types[t].entries = curEntries;
            }
            numEntries = curPI->types[t].numEntries;
        }
        if (e >= numEntries) {
            LOGE("Style contains key with bad entry: 0x%08x\n", attrRes);
            bag++;
            continue;
        }
        theme_entry* curEntry = curEntries + e;
        TABLE_NOISY(LOGV("Attr 0x%08x: type=0x%x, data=0x%08x; curType=0x%x",
                   attrRes, bag->map.value.dataType, bag->map.value.data,
             curEntry->value.dataType));
        if (force || curEntry->value.dataType == Res_value::TYPE_NULL) {
            curEntry->stringBlock = bag->stringBlock;
            curEntry->typeSpecFlags |= bagTypeSpecFlags;
            curEntry->value = bag->map.value;
        }

        bag++;
    }

    mTable.unlock();

    //ALOGI("Applying style 0x%08x (force=%d)  theme %p...\n", resID, force, this);
    //dumpToLog();
    
    return NO_ERROR;
}

status_t ResTable::Theme::setTo(const Theme& other)
{
    //ALOGI("Setting theme %p from theme %p...\n", this, &other);
    //dumpToLog();
    //other.dumpToLog();
    
    if (&mTable == &other.mTable) {
        for (size_t i=0; i<Res_MAXPACKAGE; i++) {
            if (mPackages[i] != NULL) {
                free_package(mPackages[i]);
            }
            if (other.mPackages[i] != NULL) {
                mPackages[i] = copy_package(other.mPackages[i]);
            } else {
                mPackages[i] = NULL;
            }
        }
    } else {
        // @todo: need to really implement this, not just copy
        // the system package (which is still wrong because it isn't
        // fixing up resource references).
        for (size_t i=0; i<Res_MAXPACKAGE; i++) {
            if (mPackages[i] != NULL) {
                free_package(mPackages[i]);
            }
            if (i == 0 && other.mPackages[i] != NULL) {
                mPackages[i] = copy_package(other.mPackages[i]);
            } else {
                mPackages[i] = NULL;
            }
        }
    }

    //ALOGI("Final theme:");
    //dumpToLog();
    
    return NO_ERROR;
}

ssize_t ResTable::Theme::getAttribute(uint32_t resID, Res_value* outValue,
        uint32_t* outTypeSpecFlags) const
{
    int cnt = 20;

    if (outTypeSpecFlags != NULL) *outTypeSpecFlags = 0;
    
    do {
        const ssize_t p = mTable.getResourcePackageIndex(resID);
        const uint32_t t = Res_GETTYPE(resID);
        const uint32_t e = Res_GETENTRY(resID);

        TABLE_THEME(LOGI("Looking up attr 0x%08x in theme %p", resID, this));

        if (p >= 0) {
            const package_info* const pi = mPackages[p];
            TABLE_THEME(LOGI("Found package: %p", pi));
            if (pi != NULL) {
                TABLE_THEME(LOGI("Desired type index is %ld in avail %d", t, pi->numTypes));
                if (t < pi->numTypes) {
                    const type_info& ti = pi->types[t];
                    TABLE_THEME(LOGI("Desired entry index is %ld in avail %d", e, ti.numEntries));
                    if (e < ti.numEntries) {
                        const theme_entry& te = ti.entries[e];
                        if (outTypeSpecFlags != NULL) {
                            *outTypeSpecFlags |= te.typeSpecFlags;
                        }
                        TABLE_THEME(LOGI("Theme value: type=0x%x, data=0x%08x",
                                te.value.dataType, te.value.data));
                        const uint8_t type = te.value.dataType;
                        if (type == Res_value::TYPE_ATTRIBUTE) {
                            if (cnt > 0) {
                                cnt--;
                                resID = te.value.data;
                                continue;
                            }
                            LOGW("Too many attribute references, stopped at: 0x%08x\n", resID);
                            return BAD_INDEX;
                        } else if (type != Res_value::TYPE_NULL) {
                            *outValue = te.value;
                            return te.stringBlock;
                        }
                        return BAD_INDEX;
                    }
                }
            }
        }
        break;

    } while (true);

    return BAD_INDEX;
}

ssize_t ResTable::Theme::resolveAttributeReference(Res_value* inOutValue,
        ssize_t blockIndex, uint32_t* outLastRef,
        uint32_t* inoutTypeSpecFlags, ResTable_config* inoutConfig) const
{
    //printf("Resolving type=0x%x\n", inOutValue->dataType);
    if (inOutValue->dataType == Res_value::TYPE_ATTRIBUTE) {
        uint32_t newTypeSpecFlags;
        blockIndex = getAttribute(inOutValue->data, inOutValue, &newTypeSpecFlags);
        TABLE_THEME(LOGI("Resolving attr reference: blockIndex=%d, type=0x%x, data=%p\n",
             (int)blockIndex, (int)inOutValue->dataType, (void*)inOutValue->data));
        if (inoutTypeSpecFlags != NULL) *inoutTypeSpecFlags |= newTypeSpecFlags;
        //printf("Retrieved attribute new type=0x%x\n", inOutValue->dataType);
        if (blockIndex < 0) {
            return blockIndex;
        }
    }
    return mTable.resolveReference(inOutValue, blockIndex, outLastRef,
            inoutTypeSpecFlags, inoutConfig);
}

void ResTable::Theme::dumpToLog() const
{
    ALOGI("Theme %p:\n", this);
    for (size_t i=0; i<Res_MAXPACKAGE; i++) {
        package_info* pi = mPackages[i];
        if (pi == NULL) continue;
        
        ALOGI("  Package #0x%02x:\n", (int)(i+1));
        for (size_t j=0; j<pi->numTypes; j++) {
            type_info& ti = pi->types[j];
            if (ti.numEntries == 0) continue;
            
            ALOGI("    Type #0x%02x:\n", (int)(j+1));
            for (size_t k=0; k<ti.numEntries; k++) {
                theme_entry& te = ti.entries[k];
                if (te.value.dataType == Res_value::TYPE_NULL) continue;
                ALOGI("      0x%08x: t=0x%x, d=0x%08x (block=%d)\n",
                     (int)Res_MAKEID(i, j, k),
                     te.value.dataType, (int)te.value.data, (int)te.stringBlock);
            }
        }
    }
}

ResTable::ResTable()
    : mError(NO_INIT)
{
    memset(&mParams, 0, sizeof(mParams));
    memset(mPackageMap, 0, sizeof(mPackageMap));
    //ALOGI("Creating ResTable %p\n", this);
}

ResTable::ResTable(const void* data, size_t size, void* cookie, bool copyData)
    : mError(NO_INIT)
{
    memset(&mParams, 0, sizeof(mParams));
    memset(mPackageMap, 0, sizeof(mPackageMap));
    add(data, size, cookie, copyData);
    LOG_FATAL_IF(mError != NO_ERROR, "Error parsing resource table");
    //ALOGI("Creating ResTable %p\n", this);
}

ResTable::~ResTable()
{
    //ALOGI("Destroying ResTable in %p\n", this);
    uninit();
}

inline ssize_t ResTable::getResourcePackageIndex(uint32_t resID) const
{
    return ((ssize_t)mPackageMap[Res_GETPACKAGE(resID)+1])-1;
}

status_t ResTable::add(const void* data, size_t size, void* cookie, bool copyData,
                       const void* idmap)
{
    return add(data, size, cookie, NULL, copyData, reinterpret_cast<const Asset*>(idmap));
}

status_t ResTable::add(Asset* asset, void* cookie, bool copyData, const void* idmap)
{
    const void* data = asset->getBuffer(true);
    if (data == NULL) {
        LOGW("Unable to get buffer of resource asset file");
        return UNKNOWN_ERROR;
    }
    size_t size = (size_t)asset->getLength();
    return add(data, size, cookie, asset, copyData, reinterpret_cast<const Asset*>(idmap));
}

status_t ResTable::add(ResTable* src)
{
    mError = src->mError;
    
    for (size_t i=0; i<src->mHeaders.size(); i++) {
        mHeaders.add(src->mHeaders[i]);
    }
    
    for (size_t i=0; i<src->mPackageGroups.size(); i++) {
        PackageGroup* srcPg = src->mPackageGroups[i];
        PackageGroup* pg = new PackageGroup(this, srcPg->name, srcPg->id);
        for (size_t j=0; j<srcPg->packages.size(); j++) {
            pg->packages.add(srcPg->packages[j]);
        }
        pg->basePackage = srcPg->basePackage;
        pg->typeCount = srcPg->typeCount;
        mPackageGroups.add(pg);
    }
    
    memcpy(mPackageMap, src->mPackageMap, sizeof(mPackageMap));
    
    return mError;
}

status_t ResTable::add(const void* data, size_t size, void* cookie,
                       Asset* asset, bool copyData, const Asset* idmap)
{
    if (!data) return NO_ERROR;
    Header* header = new Header(this);
    header->index = mHeaders.size();
    header->cookie = cookie;
    if (idmap != NULL) {
        const size_t idmap_size = idmap->getLength();
        const void* idmap_data = const_cast<Asset*>(idmap)->getBuffer(true);
        header->resourceIDMap = (uint32_t*)malloc(idmap_size);
        if (header->resourceIDMap == NULL) {
            delete header;
            return (mError = NO_MEMORY);
        }
        memcpy((void*)header->resourceIDMap, idmap_data, idmap_size);
        header->resourceIDMapSize = idmap_size;
    }
    mHeaders.add(header);

    const bool notDeviceEndian = htods(0xf0) != 0xf0;

    LOAD_TABLE_NOISY(
        ALOGV("Adding resources to ResTable: data=%p, size=0x%x, cookie=%p, asset=%p, copy=%d "
             "idmap=%p\n", data, size, cookie, asset, copyData, idmap));
    
    if (copyData || notDeviceEndian) {
        header->ownedData = malloc(size);
        if (header->ownedData == NULL) {
            return (mError=NO_MEMORY);
        }
        memcpy(header->ownedData, data, size);
        data = header->ownedData;
    }

    header->header = (const ResTable_header*)data;
    header->size = dtohl(header->header->header.size);
    //ALOGI("Got size 0x%x, again size 0x%x, raw size 0x%x\n", header->size,
    //     dtohl(header->header->header.size), header->header->header.size);
    LOAD_TABLE_NOISY(LOGV("Loading ResTable @%p:\n", header->header));
    LOAD_TABLE_NOISY(printHexData(2, header->header, header->size < 256 ? header->size : 256,
                                  16, 16, 0, false, printToLogFunc));
    if (dtohs(header->header->header.headerSize) > header->size
            || header->size > size) {
        LOGW("Bad resource table: header size 0x%x or total size 0x%x is larger than data size 0x%x\n",
             (int)dtohs(header->header->header.headerSize),
             (int)header->size, (int)size);
        return (mError=BAD_TYPE);
    }
    if (((dtohs(header->header->header.headerSize)|header->size)&0x3) != 0) {
        LOGW("Bad resource table: header size 0x%x or total size 0x%x is not on an integer boundary\n",
             (int)dtohs(header->header->header.headerSize),
             (int)header->size);
        return (mError=BAD_TYPE);
    }
    header->dataEnd = ((const uint8_t*)header->header) + header->size;

    // Iterate through all chunks.
    size_t curPackage = 0;

    const ResChunk_header* chunk =
        (const ResChunk_header*)(((const uint8_t*)header->header)
                                 + dtohs(header->header->header.headerSize));
    while (((const uint8_t*)chunk) <= (header->dataEnd-sizeof(ResChunk_header)) &&
           ((const uint8_t*)chunk) <= (header->dataEnd-dtohl(chunk->size))) {
        status_t err = validate_chunk(chunk, sizeof(ResChunk_header), header->dataEnd, "ResTable");
        if (err != NO_ERROR) {
            return (mError=err);
        }
        TABLE_NOISY(LOGV("Chunk: type=0x%x, headerSize=0x%x, size=0x%x, pos=%p\n",
                     dtohs(chunk->type), dtohs(chunk->headerSize), dtohl(chunk->size),
                     (void*)(((const uint8_t*)chunk) - ((const uint8_t*)header->header))));
        const size_t csize = dtohl(chunk->size);
        const uint16_t ctype = dtohs(chunk->type);
        if (ctype == RES_STRING_POOL_TYPE) {
            if (header->values.getError() != NO_ERROR) {
                // Only use the first string chunk; ignore any others that
                // may appear.
                status_t err = header->values.setTo(chunk, csize);
                if (err != NO_ERROR) {
                    return (mError=err);
                }
            } else {
                LOGW("Multiple string chunks found in resource table.");
            }
        } else if (ctype == RES_TABLE_PACKAGE_TYPE) {
            if (curPackage >= dtohl(header->header->packageCount)) {
                LOGW("More package chunks were found than the %d declared in the header.",
                     dtohl(header->header->packageCount));
                return (mError=BAD_TYPE);
            }
            uint32_t idmap_id = 0;
            if (idmap != NULL) {
                uint32_t tmp;
                if (getIdmapPackageId(header->resourceIDMap,
                                      header->resourceIDMapSize,
                                      &tmp) == NO_ERROR) {
                    idmap_id = tmp;
                }
            }
            if (parsePackage((ResTable_package*)chunk, header, idmap_id) != NO_ERROR) {
                return mError;
            }
            curPackage++;
        } else {
            LOGW("Unknown chunk type %p in table at %p.\n",
                 (void*)(int)(ctype),
                 (void*)(((const uint8_t*)chunk) - ((const uint8_t*)header->header)));
        }
        chunk = (const ResChunk_header*)
            (((const uint8_t*)chunk) + csize);
    }

    if (curPackage < dtohl(header->header->packageCount)) {
        LOGW("Fewer package chunks (%d) were found than the %d declared in the header.",
             (int)curPackage, dtohl(header->header->packageCount));
        return (mError=BAD_TYPE);
    }
    mError = header->values.getError();
    if (mError != NO_ERROR) {
        LOGW("No string values found in resource table!");
    }

    TABLE_NOISY(LOGV("Returning from add with mError=%d\n", mError));
    return mError;
}

status_t ResTable::getError() const
{
    return mError;
}

void ResTable::uninit()
{
    mError = NO_INIT;
    size_t N = mPackageGroups.size();
    for (size_t i=0; i<N; i++) {
        PackageGroup* g = mPackageGroups[i];
        delete g;
    }
    N = mHeaders.size();
    for (size_t i=0; i<N; i++) {
        Header* header = mHeaders[i];
        if (header->owner == this) {
            if (header->ownedData) {
                free(header->ownedData);
            }
            delete header;
        }
    }

    mPackageGroups.clear();
    mHeaders.clear();
}

bool ResTable::getResourceName(uint32_t resID, resource_name* outName) const
{
    if (mError != NO_ERROR) {
        return false;
    }

    const ssize_t p = getResourcePackageIndex(resID);
    const int t = Res_GETTYPE(resID);
    const int e = Res_GETENTRY(resID);

    if (p < 0) {
        if (Res_GETPACKAGE(resID)+1 == 0) {
            LOGW("No package identifier when getting name for resource number 0x%08x", resID);
        } else {
            LOGW("No known package when getting name for resource number 0x%08x", resID);
        }
        return false;
    }
    if (t < 0) {
        LOGW("No type identifier when getting name for resource number 0x%08x", resID);
        return false;
    }

    const PackageGroup* const grp = mPackageGroups[p];
    if (grp == NULL) {
        LOGW("Bad identifier when getting name for resource number 0x%08x", resID);
        return false;
    }
    if (grp->packages.size() > 0) {
        const Package* const package = grp->packages[0];

        const ResTable_type* type;
        const ResTable_entry* entry;
        ssize_t offset = getEntry(package, t, e, NULL, &type, &entry, NULL);
        if (offset <= 0) {
            return false;
        }

        outName->package = grp->name.string();
        outName->packageLen = grp->name.size();
        outName->type = grp->basePackage->typeStrings.stringAt(t, &outName->typeLen);
        outName->name = grp->basePackage->keyStrings.stringAt(
            dtohl(entry->key.index), &outName->nameLen);

        // If we have a bad index for some reason, we should abort.
        if (outName->type == NULL || outName->name == NULL) {
            return false;
        }

        return true;
    }

    return false;
}

ssize_t ResTable::getResource(uint32_t resID, Res_value* outValue, bool mayBeBag, uint16_t density,
        uint32_t* outSpecFlags, ResTable_config* outConfig) const
{
    if (mError != NO_ERROR) {
        return mError;
    }

    const ssize_t p = getResourcePackageIndex(resID);
    const int t = Res_GETTYPE(resID);
    const int e = Res_GETENTRY(resID);

    if (p < 0) {
        if (Res_GETPACKAGE(resID)+1 == 0) {
            LOGW("No package identifier when getting value for resource number 0x%08x", resID);
        } else {
            LOGW("No known package when getting value for resource number 0x%08x", resID);
        }
        return BAD_INDEX;
    }
    if (t < 0) {
        LOGW("No type identifier when getting value for resource number 0x%08x", resID);
        return BAD_INDEX;
    }

    const Res_value* bestValue = NULL;
    const Package* bestPackage = NULL;
    ResTable_config bestItem;
    memset(&bestItem, 0, sizeof(bestItem)); // make the compiler shut up

    if (outSpecFlags != NULL) *outSpecFlags = 0;

    // Look through all resource packages, starting with the most
    // recently added.
    const PackageGroup* const grp = mPackageGroups[p];
    if (grp == NULL) {
        LOGW("Bad identifier when getting value for resource number 0x%08x", resID);
        return BAD_INDEX;
    }

    // Allow overriding density
    const ResTable_config* desiredConfig = &mParams;
    ResTable_config* overrideConfig = NULL;
    if (density > 0) {
        overrideConfig = (ResTable_config*) malloc(sizeof(ResTable_config));
        if (overrideConfig == NULL) {
            LOGE("Couldn't malloc ResTable_config for overrides: %s", strerror(errno));
            return BAD_INDEX;
        }
        memcpy(overrideConfig, &mParams, sizeof(ResTable_config));
        overrideConfig->density = density;
        desiredConfig = overrideConfig;
    }

    ssize_t rc = BAD_VALUE;
    size_t ip = grp->packages.size();
    while (ip > 0) {
        ip--;
        int T = t;
        int E = e;

        const Package* const package = grp->packages[ip];
        if (package->header->resourceIDMap) {
            uint32_t overlayResID = 0x0;
            status_t retval = idmapLookup(package->header->resourceIDMap,
                                          package->header->resourceIDMapSize,
                                          resID, &overlayResID);
            if (retval == NO_ERROR && overlayResID != 0x0) {
                // for this loop iteration, this is the type and entry we really want
                ALOGV("resource map 0x%08x -> 0x%08x\n", resID, overlayResID);
                T = Res_GETTYPE(overlayResID);
                E = Res_GETENTRY(overlayResID);
            } else {
                // resource not present in overlay package, continue with the next package
                continue;
            }
        }

        const ResTable_type* type;
        const ResTable_entry* entry;
        const Type* typeClass;
        ssize_t offset = getEntry(package, T, E, desiredConfig, &type, &entry, &typeClass);
        if (offset <= 0) {
            // No {entry, appropriate config} pair found in package. If this
            // package is an overlay package (ip != 0), this simply means the
            // overlay package did not specify a default.
            // Non-overlay packages are still required to provide a default.
            if (offset < 0 && ip == 0) {
                LOGW("Failure getting entry for 0x%08x (t=%d e=%d) in package %zd (error %d)\n",
                        resID, T, E, ip, (int)offset);
                rc = offset;
                goto out;
            }
            continue;
        }

        if ((dtohs(entry->flags)&entry->FLAG_COMPLEX) != 0) {
            if (!mayBeBag) {
                LOGW("Requesting resource %p failed because it is complex\n",
                     (void*)resID);
            }
            continue;
        }

        TABLE_NOISY(aout << "Resource type data: "
              << HexDump(type, dtohl(type->header.size)) << endl);

        if ((size_t)offset > (dtohl(type->header.size)-sizeof(Res_value))) {
            LOGW("ResTable_item at %d is beyond type chunk data %d",
                 (int)offset, dtohl(type->header.size));
            rc = BAD_TYPE;
            goto out;
        }

        const Res_value* item =
            (const Res_value*)(((const uint8_t*)type) + offset);
        ResTable_config thisConfig;
        thisConfig.copyFromDtoH(type->config);

        if (outSpecFlags != NULL) {
            if (typeClass->typeSpecFlags != NULL) {
                *outSpecFlags |= dtohl(typeClass->typeSpecFlags[E]);
            } else {
                *outSpecFlags = -1;
            }
        }

        if (bestPackage != NULL &&
            (bestItem.isMoreSpecificThan(thisConfig) || bestItem.diff(thisConfig) == 0)) {
            // Discard thisConfig not only if bestItem is more specific, but also if the two configs
            // are identical (diff == 0), or overlay packages will not take effect.
            continue;
        }
        
        bestItem = thisConfig;
        bestValue = item;
        bestPackage = package;
    }

    TABLE_NOISY(printf("Found result: package %p\n", bestPackage));

    if (bestValue) {
        outValue->size = dtohs(bestValue->size);
        outValue->res0 = bestValue->res0;
        outValue->dataType = bestValue->dataType;
        outValue->data = dtohl(bestValue->data);
        if (outConfig != NULL) {
            *outConfig = bestItem;
        }
        TABLE_NOISY(size_t len;
              printf("Found value: pkg=%d, type=%d, str=%s, int=%d\n",
                     bestPackage->header->index,
                     outValue->dataType,
                     outValue->dataType == bestValue->TYPE_STRING
                     ? String8(bestPackage->header->values.stringAt(
                         outValue->data, &len)).string()
                     : "",
                     outValue->data));
        rc = bestPackage->header->index;
        goto out;
    }

out:
    if (overrideConfig != NULL) {
        free(overrideConfig);
    }

    return rc;
}

ssize_t ResTable::resolveReference(Res_value* value, ssize_t blockIndex,
        uint32_t* outLastRef, uint32_t* inoutTypeSpecFlags,
        ResTable_config* outConfig) const
{
    int count=0;
    while (blockIndex >= 0 && value->dataType == value->TYPE_REFERENCE
           && value->data != 0 && count < 20) {
        if (outLastRef) *outLastRef = value->data;
        uint32_t lastRef = value->data;
        uint32_t newFlags = 0;
        const ssize_t newIndex = getResource(value->data, value, true, 0, &newFlags,
                outConfig);
        if (newIndex == BAD_INDEX) {
            return BAD_INDEX;
        }
        TABLE_THEME(LOGI("Resolving reference %p: newIndex=%d, type=0x%x, data=%p\n",
             (void*)lastRef, (int)newIndex, (int)value->dataType, (void*)value->data));
        //printf("Getting reference 0x%08x: newIndex=%d\n", value->data, newIndex);
        if (inoutTypeSpecFlags != NULL) *inoutTypeSpecFlags |= newFlags;
        if (newIndex < 0) {
            // This can fail if the resource being referenced is a style...
            // in this case, just return the reference, and expect the
            // caller to deal with.
            return blockIndex;
        }
        blockIndex = newIndex;
        count++;
    }
    return blockIndex;
}

const char16_t* ResTable::valueToString(
    const Res_value* value, size_t stringBlock,
    char16_t tmpBuffer[TMP_BUFFER_SIZE], size_t* outLen)
{
    if (!value) {
        return NULL;
    }
    if (value->dataType == value->TYPE_STRING) {
        return getTableStringBlock(stringBlock)->stringAt(value->data, outLen);
    }
    // XXX do int to string conversions.
    return NULL;
}

ssize_t ResTable::lockBag(uint32_t resID, const bag_entry** outBag) const
{
    mLock.lock();
    ssize_t err = getBagLocked(resID, outBag);
    if (err < NO_ERROR) {
        //printf("*** get failed!  unlocking\n");
        mLock.unlock();
    }
    return err;
}

void ResTable::unlockBag(const bag_entry* bag) const
{
    //printf("<<< unlockBag %p\n", this);
    mLock.unlock();
}

void ResTable::lock() const
{
    mLock.lock();
}

void ResTable::unlock() const
{
    mLock.unlock();
}

ssize_t ResTable::getBagLocked(uint32_t resID, const bag_entry** outBag,
        uint32_t* outTypeSpecFlags) const
{
    if (mError != NO_ERROR) {
        return mError;
    }

    const ssize_t p = getResourcePackageIndex(resID);
    const int t = Res_GETTYPE(resID);
    const int e = Res_GETENTRY(resID);

    if (p < 0) {
        LOGW("Invalid package identifier when getting bag for resource number 0x%08x", resID);
        return BAD_INDEX;
    }
    if (t < 0) {
        LOGW("No type identifier when getting bag for resource number 0x%08x", resID);
        return BAD_INDEX;
    }

    //printf("Get bag: id=0x%08x, p=%d, t=%d\n", resID, p, t);
    PackageGroup* const grp = mPackageGroups[p];
    if (grp == NULL) {
        LOGW("Bad identifier when getting bag for resource number 0x%08x", resID);
        return false;
    }

    if (t >= (int)grp->typeCount) {
        LOGW("Type identifier 0x%x is larger than type count 0x%x",
             t+1, (int)grp->typeCount);
        return BAD_INDEX;
    }

    const Package* const basePackage = grp->packages[0];

    const Type* const typeConfigs = basePackage->getType(t);

    const size_t NENTRY = typeConfigs->entryCount;
    if (e >= (int)NENTRY) {
        LOGW("Entry identifier 0x%x is larger than entry count 0x%x",
             e, (int)typeConfigs->entryCount);
        return BAD_INDEX;
    }

    // First see if we've already computed this bag...
    if (grp->bags) {
        bag_set** typeSet = grp->bags[t];
        if (typeSet) {
            bag_set* set = typeSet[e];
            if (set) {
                if (set != (bag_set*)0xFFFFFFFF) {
                    if (outTypeSpecFlags != NULL) {
                        *outTypeSpecFlags = set->typeSpecFlags;
                    }
                    *outBag = (bag_entry*)(set+1);
                    //ALOGI("Found existing bag for: %p\n", (void*)resID);
                    return set->numAttrs;
                }
                LOGW("Attempt to retrieve bag 0x%08x which is invalid or in a cycle.",
                     resID);
                return BAD_INDEX;
            }
        }
    }

    // Bag not found, we need to compute it!
    if (!grp->bags) {
        grp->bags = (bag_set***)malloc(sizeof(bag_set*)*grp->typeCount);
        if (!grp->bags) return NO_MEMORY;
        memset(grp->bags, 0, sizeof(bag_set*)*grp->typeCount);
    }

    bag_set** typeSet = grp->bags[t];
    if (!typeSet) {
        typeSet = (bag_set**)malloc(sizeof(bag_set*)*NENTRY);
        if (!typeSet) return NO_MEMORY;
        memset(typeSet, 0, sizeof(bag_set*)*NENTRY);
        grp->bags[t] = typeSet;
    }

    // Mark that we are currently working on this one.
    typeSet[e] = (bag_set*)0xFFFFFFFF;

    // This is what we are building.
    bag_set* set = NULL;

    TABLE_NOISY(LOGI("Building bag: %p\n", (void*)resID));
    
    ResTable_config bestConfig;
    memset(&bestConfig, 0, sizeof(bestConfig));

    // Now collect all bag attributes from all packages.
    size_t ip = grp->packages.size();
    while (ip > 0) {
        ip--;
        int T = t;
        int E = e;

        const Package* const package = grp->packages[ip];
        if (package->header->resourceIDMap) {
            uint32_t overlayResID = 0x0;
            status_t retval = idmapLookup(package->header->resourceIDMap,
                                          package->header->resourceIDMapSize,
                                          resID, &overlayResID);
            if (retval == NO_ERROR && overlayResID != 0x0) {
                // for this loop iteration, this is the type and entry we really want
                ALOGV("resource map 0x%08x -> 0x%08x\n", resID, overlayResID);
                T = Res_GETTYPE(overlayResID);
                E = Res_GETENTRY(overlayResID);
            } else {
                // resource not present in overlay package, continue with the next package
                continue;
            }
        }

        const ResTable_type* type;
        const ResTable_entry* entry;
        const Type* typeClass;
        ALOGV("Getting entry pkg=%p, t=%d, e=%d\n", package, T, E);
        ssize_t offset = getEntry(package, T, E, &mParams, &type, &entry, &typeClass);
        ALOGV("Resulting offset=%d\n", offset);
        if (offset <= 0) {
            // No {entry, appropriate config} pair found in package. If this
            // package is an overlay package (ip != 0), this simply means the
            // overlay package did not specify a default.
            // Non-overlay packages are still required to provide a default.
            if (offset < 0 && ip == 0) {
                if (set) free(set);
                return offset;
            }
            continue;
        }

        if ((dtohs(entry->flags)&entry->FLAG_COMPLEX) == 0) {
            LOGW("Skipping entry %p in package table %d because it is not complex!\n",
                 (void*)resID, (int)ip);
            continue;
        }

        if (set != NULL && !type->config.isBetterThan(bestConfig, NULL)) {
            continue;
        }
        bestConfig = type->config;
        if (set) {
            free(set);
            set = NULL;
        }

        const uint16_t entrySize = dtohs(entry->size);
        const uint32_t parent = entrySize >= sizeof(ResTable_map_entry)
            ? dtohl(((const ResTable_map_entry*)entry)->parent.ident) : 0;
        const uint32_t count = entrySize >= sizeof(ResTable_map_entry)
            ? dtohl(((const ResTable_map_entry*)entry)->count) : 0;
        
        size_t N = count;

        TABLE_NOISY(LOGI("Found map: size=%p parent=%p count=%d\n",
                         entrySize, parent, count));

        // If this map inherits from another, we need to start
        // with its parent's values.  Otherwise start out empty.
        TABLE_NOISY(printf("Creating new bag, entrySize=0x%08x, parent=0x%08x\n",
                           entrySize, parent));
        if (parent) {
            const bag_entry* parentBag;
            uint32_t parentTypeSpecFlags = 0;
            const ssize_t NP = getBagLocked(parent, &parentBag, &parentTypeSpecFlags);
            const size_t NT = ((NP >= 0) ? NP : 0) + N;
            set = (bag_set*)malloc(sizeof(bag_set)+sizeof(bag_entry)*NT);
            if (set == NULL) {
                return NO_MEMORY;
            }
            if (NP > 0) {
                memcpy(set+1, parentBag, NP*sizeof(bag_entry));
                set->numAttrs = NP;
                TABLE_NOISY(LOGI("Initialized new bag with %d inherited attributes.\n", NP));
            } else {
                TABLE_NOISY(LOGI("Initialized new bag with no inherited attributes.\n"));
                set->numAttrs = 0;
            }
            set->availAttrs = NT;
            set->typeSpecFlags = parentTypeSpecFlags;
        } else {
            set = (bag_set*)malloc(sizeof(bag_set)+sizeof(bag_entry)*N);
            if (set == NULL) {
                return NO_MEMORY;
            }
            set->numAttrs = 0;
            set->availAttrs = N;
            set->typeSpecFlags = 0;
        }

        if (typeClass->typeSpecFlags != NULL) {
            set->typeSpecFlags |= dtohl(typeClass->typeSpecFlags[E]);
        } else {
            set->typeSpecFlags = -1;
        }
        
        // Now merge in the new attributes...
        ssize_t curOff = offset;
        const ResTable_map* map;
        bag_entry* entries = (bag_entry*)(set+1);
        size_t curEntry = 0;
        uint32_t pos = 0;
        TABLE_NOISY(LOGI("Starting with set %p, entries=%p, avail=%d\n",
                     set, entries, set->availAttrs));
        while (pos < count) {
            TABLE_NOISY(printf("Now at %p\n", (void*)curOff));

            if ((size_t)curOff > (dtohl(type->header.size)-sizeof(ResTable_map))) {
                LOGW("ResTable_map at %d is beyond type chunk data %d",
                     (int)curOff, dtohl(type->header.size));
                return BAD_TYPE;
            }
            map = (const ResTable_map*)(((const uint8_t*)type) + curOff);
            N++;

            const uint32_t newName = htodl(map->name.ident);
            bool isInside;
            uint32_t oldName = 0;
            while ((isInside=(curEntry < set->numAttrs))
                    && (oldName=entries[curEntry].map.name.ident) < newName) {
                TABLE_NOISY(printf("#%d: Keeping existing attribute: 0x%08x\n",
                             curEntry, entries[curEntry].map.name.ident));
                curEntry++;
            }

            if ((!isInside) || oldName != newName) {
                // This is a new attribute...  figure out what to do with it.
                if (set->numAttrs >= set->availAttrs) {
                    // Need to alloc more memory...
                    const size_t newAvail = set->availAttrs+N;
                    set = (bag_set*)realloc(set,
                                            sizeof(bag_set)
                                            + sizeof(bag_entry)*newAvail);
                    if (set == NULL) {
                        return NO_MEMORY;
                    }
                    set->availAttrs = newAvail;
                    entries = (bag_entry*)(set+1);
                    TABLE_NOISY(printf("Reallocated set %p, entries=%p, avail=%d\n",
                                 set, entries, set->availAttrs));
                }
                if (isInside) {
                    // Going in the middle, need to make space.
                    memmove(entries+curEntry+1, entries+curEntry,
                            sizeof(bag_entry)*(set->numAttrs-curEntry));
                    set->numAttrs++;
                }
                TABLE_NOISY(printf("#%d: Inserting new attribute: 0x%08x\n",
                             curEntry, newName));
            } else {
                TABLE_NOISY(printf("#%d: Replacing existing attribute: 0x%08x\n",
                             curEntry, oldName));
            }

            bag_entry* cur = entries+curEntry;

            cur->stringBlock = package->header->index;
            cur->map.name.ident = newName;
            cur->map.value.copyFrom_dtoh(map->value);
            TABLE_NOISY(printf("Setting entry #%d %p: block=%d, name=0x%08x, type=%d, data=0x%08x\n",
                         curEntry, cur, cur->stringBlock, cur->map.name.ident,
                         cur->map.value.dataType, cur->map.value.data));

            // On to the next!
            curEntry++;
            pos++;
            const size_t size = dtohs(map->value.size);
            curOff += size + sizeof(*map)-sizeof(map->value);
        };
        if (curEntry > set->numAttrs) {
            set->numAttrs = curEntry;
        }
    }

    // And this is it...
    typeSet[e] = set;
    if (set) {
        if (outTypeSpecFlags != NULL) {
            *outTypeSpecFlags = set->typeSpecFlags;
        }
        *outBag = (bag_entry*)(set+1);
        TABLE_NOISY(LOGI("Returning %d attrs\n", set->numAttrs));
        return set->numAttrs;
    }
    return BAD_INDEX;
}

void ResTable::setParameters(const ResTable_config* params)
{
    mLock.lock();
    TABLE_GETENTRY(LOGI("Setting parameters: imsi:%d/%d lang:%c%c cnt:%c%c "
                        "orien:%d touch:%d density:%d key:%d inp:%d nav:%d sz:%dx%d sw%ddp w%ddp h%ddp\n",
                       params->mcc, params->mnc,
                       params->language[0] ? params->language[0] : '-',
                       params->language[1] ? params->language[1] : '-',
                       params->country[0] ? params->country[0] : '-',
                       params->country[1] ? params->country[1] : '-',
                       params->orientation,
                       params->touchscreen,
                       params->density,
                       params->keyboard,
                       params->inputFlags,
                       params->navigation,
                       params->screenWidth,
                       params->screenHeight,
                       params->smallestScreenWidthDp,
                       params->screenWidthDp,
                       params->screenHeightDp));
    mParams = *params;
    for (size_t i=0; i<mPackageGroups.size(); i++) {
        TABLE_NOISY(LOGI("CLEARING BAGS FOR GROUP %d!", i));
        mPackageGroups[i]->clearBagCache();
    }
    mLock.unlock();
}

void ResTable::getParameters(ResTable_config* params) const
{
    mLock.lock();
    *params = mParams;
    mLock.unlock();
}

struct id_name_map {
    uint32_t id;
    size_t len;
    char16_t name[6];
};

const static id_name_map ID_NAMES[] = {
    { ResTable_map::ATTR_TYPE,  5, { '^', 't', 'y', 'p', 'e' } },
    { ResTable_map::ATTR_L10N,  5, { '^', 'l', '1', '0', 'n' } },
    { ResTable_map::ATTR_MIN,   4, { '^', 'm', 'i', 'n' } },
    { ResTable_map::ATTR_MAX,   4, { '^', 'm', 'a', 'x' } },
    { ResTable_map::ATTR_OTHER, 6, { '^', 'o', 't', 'h', 'e', 'r' } },
    { ResTable_map::ATTR_ZERO,  5, { '^', 'z', 'e', 'r', 'o' } },
    { ResTable_map::ATTR_ONE,   4, { '^', 'o', 'n', 'e' } },
    { ResTable_map::ATTR_TWO,   4, { '^', 't', 'w', 'o' } },
    { ResTable_map::ATTR_FEW,   4, { '^', 'f', 'e', 'w' } },
    { ResTable_map::ATTR_MANY,  5, { '^', 'm', 'a', 'n', 'y' } },
};

uint32_t ResTable::identifierForName(const char16_t* name, size_t nameLen,
                                     const char16_t* type, size_t typeLen,
                                     const char16_t* package,
                                     size_t packageLen,
                                     uint32_t* outTypeSpecFlags) const
{
    TABLE_SUPER_NOISY(printf("Identifier for name: error=%d\n", mError));

    // Check for internal resource identifier as the very first thing, so
    // that we will always find them even when there are no resources.
    if (name[0] == '^') {
        const int N = (sizeof(ID_NAMES)/sizeof(ID_NAMES[0]));
        size_t len;
        for (int i=0; i<N; i++) {
            const id_name_map* m = ID_NAMES + i;
            len = m->len;
            if (len != nameLen) {
                continue;
            }
            for (size_t j=1; j<len; j++) {
                if (m->name[j] != name[j]) {
                    goto nope;
                }
            }
            if (outTypeSpecFlags) {
                *outTypeSpecFlags = ResTable_typeSpec::SPEC_PUBLIC;
            }
            return m->id;
nope:
            ;
        }
        if (nameLen > 7) {
            if (name[1] == 'i' && name[2] == 'n'
                && name[3] == 'd' && name[4] == 'e' && name[5] == 'x'
                && name[6] == '_') {
                int index = atoi(String8(name + 7, nameLen - 7).string());
                if (Res_CHECKID(index)) {
                    LOGW("Array resource index: %d is too large.",
                         index);
                    return 0;
                }
                if (outTypeSpecFlags) {
                    *outTypeSpecFlags = ResTable_typeSpec::SPEC_PUBLIC;
                }
                return  Res_MAKEARRAY(index);
            }
        }
        return 0;
    }

    if (mError != NO_ERROR) {
        return 0;
    }

    bool fakePublic = false;

    // Figure out the package and type we are looking in...

    const char16_t* packageEnd = NULL;
    const char16_t* typeEnd = NULL;
    const char16_t* const nameEnd = name+nameLen;
    const char16_t* p = name;
    while (p < nameEnd) {
        if (*p == ':') packageEnd = p;
        else if (*p == '/') typeEnd = p;
        p++;
    }
    if (*name == '@') {
        name++;
        if (*name == '*') {
            fakePublic = true;
            name++;
        }
    }
    if (name >= nameEnd) {
        return 0;
    }

    if (packageEnd) {
        package = name;
        packageLen = packageEnd-name;
        name = packageEnd+1;
    } else if (!package) {
        return 0;
    }

    if (typeEnd) {
        type = name;
        typeLen = typeEnd-name;
        name = typeEnd+1;
    } else if (!type) {
        return 0;
    }

    if (name >= nameEnd) {
        return 0;
    }
    nameLen = nameEnd-name;

    TABLE_NOISY(printf("Looking for identifier: type=%s, name=%s, package=%s\n",
                 String8(type, typeLen).string(),
                 String8(name, nameLen).string(),
                 String8(package, packageLen).string()));

    const size_t NG = mPackageGroups.size();
    for (size_t ig=0; ig<NG; ig++) {
        const PackageGroup* group = mPackageGroups[ig];

        if (strzcmp16(package, packageLen,
                      group->name.string(), group->name.size())) {
            TABLE_NOISY(printf("Skipping package group: %s\n", String8(group->name).string()));
            continue;
        }

        const ssize_t ti = group->basePackage->typeStrings.indexOfString(type, typeLen);
        if (ti < 0) {
            TABLE_NOISY(printf("Type not found in package %s\n", String8(group->name).string()));
            continue;
        }

        const ssize_t ei = group->basePackage->keyStrings.indexOfString(name, nameLen);
        if (ei < 0) {
            TABLE_NOISY(printf("Name not found in package %s\n", String8(group->name).string()));
            continue;
        }

        TABLE_NOISY(printf("Search indices: type=%d, name=%d\n", ti, ei));

        const Type* const typeConfigs = group->packages[0]->getType(ti);
        if (typeConfigs == NULL || typeConfigs->configs.size() <= 0) {
            TABLE_NOISY(printf("Expected type structure not found in package %s for idnex %d\n",
                               String8(group->name).string(), ti));
        }
        
        size_t NTC = typeConfigs->configs.size();
        for (size_t tci=0; tci<NTC; tci++) {
            const ResTable_type* const ty = typeConfigs->configs[tci];
            const uint32_t typeOffset = dtohl(ty->entriesStart);

            const uint8_t* const end = ((const uint8_t*)ty) + dtohl(ty->header.size);
            const uint32_t* const eindex = (const uint32_t*)
                (((const uint8_t*)ty) + dtohs(ty->header.headerSize));

            const size_t NE = dtohl(ty->entryCount);
            for (size_t i=0; i<NE; i++) {
                uint32_t offset = dtohl(eindex[i]);
                if (offset == ResTable_type::NO_ENTRY) {
                    continue;
                }
                
                offset += typeOffset;
                
                if (offset > (dtohl(ty->header.size)-sizeof(ResTable_entry))) {
                    LOGW("ResTable_entry at %d is beyond type chunk data %d",
                         offset, dtohl(ty->header.size));
                    return 0;
                }
                if ((offset&0x3) != 0) {
                    LOGW("ResTable_entry at %d (pkg=%d type=%d ent=%d) is not on an integer boundary when looking for %s:%s/%s",
                         (int)offset, (int)group->id, (int)ti+1, (int)i,
                         String8(package, packageLen).string(),
                         String8(type, typeLen).string(),
                         String8(name, nameLen).string());
                    return 0;
                }
                
                const ResTable_entry* const entry = (const ResTable_entry*)
                    (((const uint8_t*)ty) + offset);
                if (dtohs(entry->size) < sizeof(*entry)) {
                    LOGW("ResTable_entry size %d is too small", dtohs(entry->size));
                    return BAD_TYPE;
                }

                TABLE_SUPER_NOISY(printf("Looking at entry #%d: want str %d, have %d\n",
                                         i, ei, dtohl(entry->key.index)));
                if (dtohl(entry->key.index) == (size_t)ei) {
                    if (outTypeSpecFlags) {
                        *outTypeSpecFlags = typeConfigs->typeSpecFlags[i];
                        if (fakePublic) {
                            *outTypeSpecFlags |= ResTable_typeSpec::SPEC_PUBLIC;
                        }
                    }
                    return Res_MAKEID(group->id-1, ti, i);
                }
            }
        }
    }

    return 0;
}

bool ResTable::expandResourceRef(const uint16_t* refStr, size_t refLen,
                                 String16* outPackage,
                                 String16* outType,
                                 String16* outName,
                                 const String16* defType,
                                 const String16* defPackage,
                                 const char** outErrorMsg,
                                 bool* outPublicOnly)
{
    const char16_t* packageEnd = NULL;
    const char16_t* typeEnd = NULL;
    const char16_t* p = refStr;
    const char16_t* const end = p + refLen;
    while (p < end) {
        if (*p == ':') packageEnd = p;
        else if (*p == '/') {
            typeEnd = p;
            break;
        }
        p++;
    }
    p = refStr;
    if (*p == '@') p++;

    if (outPublicOnly != NULL) {
        *outPublicOnly = true;
    }
    if (*p == '*') {
        p++;
        if (outPublicOnly != NULL) {
            *outPublicOnly = false;
        }
    }

    if (packageEnd) {
        *outPackage = String16(p, packageEnd-p);
        p = packageEnd+1;
    } else {
        if (!defPackage) {
            if (outErrorMsg) {
                *outErrorMsg = "No resource package specified";
            }
            return false;
        }
        *outPackage = *defPackage;
    }
    if (typeEnd) {
        *outType = String16(p, typeEnd-p);
        p = typeEnd+1;
    } else {
        if (!defType) {
            if (outErrorMsg) {
                *outErrorMsg = "No resource type specified";
            }
            return false;
        }
        *outType = *defType;
    }
    *outName = String16(p, end-p);
    if(**outPackage == 0) {
        if(outErrorMsg) {
            *outErrorMsg = "Resource package cannot be an empty string";
        }
        return false;
    }
    if(**outType == 0) {
        if(outErrorMsg) {
            *outErrorMsg = "Resource type cannot be an empty string";
        }
        return false;
    }
    if(**outName == 0) {
        if(outErrorMsg) {
            *outErrorMsg = "Resource id cannot be an empty string";
        }
        return false;
    }
    return true;
}

static uint32_t get_hex(char c, bool* outError)
{
    if (c >= '0' && c <= '9') {
        return c - '0';
    } else if (c >= 'a' && c <= 'f') {
        return c - 'a' + 0xa;
    } else if (c >= 'A' && c <= 'F') {
        return c - 'A' + 0xa;
    }
    *outError = true;
    return 0;
}

struct unit_entry
{
    const char* name;
    size_t len;
    uint8_t type;
    uint32_t unit;
    float scale;
};

static const unit_entry unitNames[] = {
    { "px", strlen("px"), Res_value::TYPE_DIMENSION, Res_value::COMPLEX_UNIT_PX, 1.0f },
    { "dip", strlen("dip"), Res_value::TYPE_DIMENSION, Res_value::COMPLEX_UNIT_DIP, 1.0f },
    { "dp", strlen("dp"), Res_value::TYPE_DIMENSION, Res_value::COMPLEX_UNIT_DIP, 1.0f },
    { "sp", strlen("sp"), Res_value::TYPE_DIMENSION, Res_value::COMPLEX_UNIT_SP, 1.0f },
    { "pt", strlen("pt"), Res_value::TYPE_DIMENSION, Res_value::COMPLEX_UNIT_PT, 1.0f },
    { "in", strlen("in"), Res_value::TYPE_DIMENSION, Res_value::COMPLEX_UNIT_IN, 1.0f },
    { "mm", strlen("mm"), Res_value::TYPE_DIMENSION, Res_value::COMPLEX_UNIT_MM, 1.0f },
    { "%", strlen("%"), Res_value::TYPE_FRACTION, Res_value::COMPLEX_UNIT_FRACTION, 1.0f/100 },
    { "%p", strlen("%p"), Res_value::TYPE_FRACTION, Res_value::COMPLEX_UNIT_FRACTION_PARENT, 1.0f/100 },
    { NULL, 0, 0, 0, 0 }
};

static bool parse_unit(const char* str, Res_value* outValue,
                       float* outScale, const char** outEnd)
{
    const char* end = str;
    while (*end != 0 && !isspace((unsigned char)*end)) {
        end++;
    }
    const size_t len = end-str;

    const char* realEnd = end;
    while (*realEnd != 0 && isspace((unsigned char)*realEnd)) {
        realEnd++;
    }
    if (*realEnd != 0) {
        return false;
    }
    
    const unit_entry* cur = unitNames;
    while (cur->name) {
        if (len == cur->len && strncmp(cur->name, str, len) == 0) {
            outValue->dataType = cur->type;
            outValue->data = cur->unit << Res_value::COMPLEX_UNIT_SHIFT;
            *outScale = cur->scale;
            *outEnd = end;
            //printf("Found unit %s for %s\n", cur->name, str);
            return true;
        }
        cur++;
    }

    return false;
}


bool ResTable::stringToInt(const char16_t* s, size_t len, Res_value* outValue)
{
    while (len > 0 && isspace16(*s)) {
        s++;
        len--;
    }

    if (len <= 0) {
        return false;
    }

    size_t i = 0;
    int32_t val = 0;
    bool neg = false;

    if (*s == '-') {
        neg = true;
        i++;
    }

    if (s[i] < '0' || s[i] > '9') {
        return false;
    }

    // Decimal or hex?
    if (s[i] == '0' && s[i+1] == 'x') {
        if (outValue)
            outValue->dataType = outValue->TYPE_INT_HEX;
        i += 2;
        bool error = false;
        while (i < len && !error) {
            val = (val*16) + get_hex(s[i], &error);
            i++;
        }
        if (error) {
            return false;
        }
    } else {
        if (outValue)
            outValue->dataType = outValue->TYPE_INT_DEC;
        while (i < len) {
            if (s[i] < '0' || s[i] > '9') {
                return false;
            }
            val = (val*10) + s[i]-'0';
            i++;
        }
    }

    if (neg) val = -val;

    while (i < len && isspace16(s[i])) {
        i++;
    }

    if (i == len) {
        if (outValue)
            outValue->data = val;
        return true;
    }

    return false;
}

bool ResTable::stringToFloat(const char16_t* s, size_t len, Res_value* outValue)
{
    while (len > 0 && isspace16(*s)) {
        s++;
        len--;
    }

    if (len <= 0) {
        return false;
    }

    char buf[128];
    int i=0;
    while (len > 0 && *s != 0 && i < 126) {
        if (*s > 255) {
            return false;
        }
        buf[i++] = *s++;
        len--;
    }

    if (len > 0) {
        return false;
    }
    if (buf[0] < '0' && buf[0] > '9' && buf[0] != '.') {
        return false;
    }

    buf[i] = 0;
    const char* end;
    float f = strtof(buf, (char**)&end);

    if (*end != 0 && !isspace((unsigned char)*end)) {
        // Might be a unit...
        float scale;
        if (parse_unit(end, outValue, &scale, &end)) {
            f *= scale;
            const bool neg = f < 0;
            if (neg) f = -f;
            uint64_t bits = (uint64_t)(f*(1<<23)+.5f);
            uint32_t radix;
            uint32_t shift;
            if ((bits&0x7fffff) == 0) {
                // Always use 23p0 if there is no fraction, just to make
                // things easier to read.
                radix = Res_value::COMPLEX_RADIX_23p0;
                shift = 23;
            } else if ((bits&0xffffffffff800000LL) == 0) {
                // Magnitude is zero -- can fit in 0 bits of precision.
                radix = Res_value::COMPLEX_RADIX_0p23;
                shift = 0;
            } else if ((bits&0xffffffff80000000LL) == 0) {
                // Magnitude can fit in 8 bits of precision.
                radix = Res_value::COMPLEX_RADIX_8p15;
                shift = 8;
            } else if ((bits&0xffffff8000000000LL) == 0) {
                // Magnitude can fit in 16 bits of precision.
                radix = Res_value::COMPLEX_RADIX_16p7;
                shift = 16;
            } else {
                // Magnitude needs entire range, so no fractional part.
                radix = Res_value::COMPLEX_RADIX_23p0;
                shift = 23;
            }
            int32_t mantissa = (int32_t)(
                (bits>>shift) & Res_value::COMPLEX_MANTISSA_MASK);
            if (neg) {
                mantissa = (-mantissa) & Res_value::COMPLEX_MANTISSA_MASK;
            }
            outValue->data |= 
                (radix<<Res_value::COMPLEX_RADIX_SHIFT)
                | (mantissa<<Res_value::COMPLEX_MANTISSA_SHIFT);
            //printf("Input value: %f 0x%016Lx, mult: %f, radix: %d, shift: %d, final: 0x%08x\n",
            //       f * (neg ? -1 : 1), bits, f*(1<<23),
            //       radix, shift, outValue->data);
            return true;
        }
        return false;
    }

    while (*end != 0 && isspace((unsigned char)*end)) {
        end++;
    }

    if (*end == 0) {
        if (outValue) {
            outValue->dataType = outValue->TYPE_FLOAT;
            *(float*)(&outValue->data) = f;
            return true;
        }
    }

    return false;
}

bool ResTable::stringToValue(Res_value* outValue, String16* outString,
                             const char16_t* s, size_t len,
                             bool preserveSpaces, bool coerceType,
                             uint32_t attrID,
                             const String16* defType,
                             const String16* defPackage,
                             Accessor* accessor,
                             void* accessorCookie,
                             uint32_t attrType,
                             bool enforcePrivate) const
{
    bool localizationSetting = accessor != NULL && accessor->getLocalizationSetting();
    const char* errorMsg = NULL;

    outValue->size = sizeof(Res_value);
    outValue->res0 = 0;

    // First strip leading/trailing whitespace.  Do this before handling
    // escapes, so they can be used to force whitespace into the string.
    if (!preserveSpaces) {
        while (len > 0 && isspace16(*s)) {
            s++;
            len--;
        }
        while (len > 0 && isspace16(s[len-1])) {
            len--;
        }
        // If the string ends with '\', then we keep the space after it.
        if (len > 0 && s[len-1] == '\\' && s[len] != 0) {
            len++;
        }
    }

    //printf("Value for: %s\n", String8(s, len).string());

    uint32_t l10nReq = ResTable_map::L10N_NOT_REQUIRED;
    uint32_t attrMin = 0x80000000, attrMax = 0x7fffffff;
    bool fromAccessor = false;
    if (attrID != 0 && !Res_INTERNALID(attrID)) {
        const ssize_t p = getResourcePackageIndex(attrID);
        const bag_entry* bag;
        ssize_t cnt = p >= 0 ? lockBag(attrID, &bag) : -1;
        //printf("For attr 0x%08x got bag of %d\n", attrID, cnt);
        if (cnt >= 0) {
            while (cnt > 0) {
                //printf("Entry 0x%08x = 0x%08x\n", bag->map.name.ident, bag->map.value.data);
                switch (bag->map.name.ident) {
                case ResTable_map::ATTR_TYPE:
                    attrType = bag->map.value.data;
                    break;
                case ResTable_map::ATTR_MIN:
                    attrMin = bag->map.value.data;
                    break;
                case ResTable_map::ATTR_MAX:
                    attrMax = bag->map.value.data;
                    break;
                case ResTable_map::ATTR_L10N:
                    l10nReq = bag->map.value.data;
                    break;
                }
                bag++;
                cnt--;
            }
            unlockBag(bag);
        } else if (accessor && accessor->getAttributeType(attrID, &attrType)) {
            fromAccessor = true;
            if (attrType == ResTable_map::TYPE_ENUM
                    || attrType == ResTable_map::TYPE_FLAGS
                    || attrType == ResTable_map::TYPE_INTEGER) {
                accessor->getAttributeMin(attrID, &attrMin);
                accessor->getAttributeMax(attrID, &attrMax);
            }
            if (localizationSetting) {
                l10nReq = accessor->getAttributeL10N(attrID);
            }
        }
    }

    const bool canStringCoerce =
        coerceType && (attrType&ResTable_map::TYPE_STRING) != 0;

    if (*s == '@') {
        outValue->dataType = outValue->TYPE_REFERENCE;

        // Note: we don't check attrType here because the reference can
        // be to any other type; we just need to count on the client making
        // sure the referenced type is correct.
        
        //printf("Looking up ref: %s\n", String8(s, len).string());

        // It's a reference!
        if (len == 5 && s[1]=='n' && s[2]=='u' && s[3]=='l' && s[4]=='l') {
            outValue->data = 0;
            return true;
        } else {
            bool createIfNotFound = false;
            const char16_t* resourceRefName;
            int resourceNameLen;
            if (len > 2 && s[1] == '+') {
                createIfNotFound = true;
                resourceRefName = s + 2;
                resourceNameLen = len - 2;
            } else if (len > 2 && s[1] == '*') {
                enforcePrivate = false;
                resourceRefName = s + 2;
                resourceNameLen = len - 2;
            } else {
                createIfNotFound = false;
                resourceRefName = s + 1;
                resourceNameLen = len - 1;
            }
            String16 package, type, name;
            if (!expandResourceRef(resourceRefName,resourceNameLen, &package, &type, &name,
                                   defType, defPackage, &errorMsg)) {
                if (accessor != NULL) {
                    accessor->reportError(accessorCookie, errorMsg);
                }
                return false;
            }

            uint32_t specFlags = 0;
            uint32_t rid = identifierForName(name.string(), name.size(), type.string(),
                    type.size(), package.string(), package.size(), &specFlags);
            if (rid != 0) {
                if (enforcePrivate) {
                    if ((specFlags&ResTable_typeSpec::SPEC_PUBLIC) == 0) {
                        if (accessor != NULL) {
                            accessor->reportError(accessorCookie, "Resource is not public.");
                        }
                        return false;
                    }
                }
                if (!accessor) {
                    outValue->data = rid;
                    return true;
                }
                rid = Res_MAKEID(
                    accessor->getRemappedPackage(Res_GETPACKAGE(rid)),
                    Res_GETTYPE(rid), Res_GETENTRY(rid));
                TABLE_NOISY(printf("Incl %s:%s/%s: 0x%08x\n",
                       String8(package).string(), String8(type).string(),
                       String8(name).string(), rid));
                outValue->data = rid;
                return true;
            }

            if (accessor) {
                uint32_t rid = accessor->getCustomResourceWithCreation(package, type, name,
                                                                       createIfNotFound);
                if (rid != 0) {
                    TABLE_NOISY(printf("Pckg %s:%s/%s: 0x%08x\n",
                           String8(package).string(), String8(type).string(),
                           String8(name).string(), rid));
                    outValue->data = rid;
                    return true;
                }
            }
        }

        if (accessor != NULL) {
            accessor->reportError(accessorCookie, "No resource found that matches the given name");
        }
        return false;
    }

    // if we got to here, and localization is required and it's not a reference,
    // complain and bail.
    if (l10nReq == ResTable_map::L10N_SUGGESTED) {
        if (localizationSetting) {
            if (accessor != NULL) {
                accessor->reportError(accessorCookie, "This attribute must be localized.");
            }
        }
    }
    
    if (*s == '#') {
        // It's a color!  Convert to an integer of the form 0xaarrggbb.
        uint32_t color = 0;
        bool error = false;
        if (len == 4) {
            outValue->dataType = outValue->TYPE_INT_COLOR_RGB4;
            color |= 0xFF000000;
            color |= get_hex(s[1], &error) << 20;
            color |= get_hex(s[1], &error) << 16;
            color |= get_hex(s[2], &error) << 12;
            color |= get_hex(s[2], &error) << 8;
            color |= get_hex(s[3], &error) << 4;
            color |= get_hex(s[3], &error);
        } else if (len == 5) {
            outValue->dataType = outValue->TYPE_INT_COLOR_ARGB4;
            color |= get_hex(s[1], &error) << 28;
            color |= get_hex(s[1], &error) << 24;
            color |= get_hex(s[2], &error) << 20;
            color |= get_hex(s[2], &error) << 16;
            color |= get_hex(s[3], &error) << 12;
            color |= get_hex(s[3], &error) << 8;
            color |= get_hex(s[4], &error) << 4;
            color |= get_hex(s[4], &error);
        } else if (len == 7) {
            outValue->dataType = outValue->TYPE_INT_COLOR_RGB8;
            color |= 0xFF000000;
            color |= get_hex(s[1], &error) << 20;
            color |= get_hex(s[2], &error) << 16;
            color |= get_hex(s[3], &error) << 12;
            color |= get_hex(s[4], &error) << 8;
            color |= get_hex(s[5], &error) << 4;
            color |= get_hex(s[6], &error);
        } else if (len == 9) {
            outValue->dataType = outValue->TYPE_INT_COLOR_ARGB8;
            color |= get_hex(s[1], &error) << 28;
            color |= get_hex(s[2], &error) << 24;
            color |= get_hex(s[3], &error) << 20;
            color |= get_hex(s[4], &error) << 16;
            color |= get_hex(s[5], &error) << 12;
            color |= get_hex(s[6], &error) << 8;
            color |= get_hex(s[7], &error) << 4;
            color |= get_hex(s[8], &error);
        } else {
            error = true;
        }
        if (!error) {
            if ((attrType&ResTable_map::TYPE_COLOR) == 0) {
                if (!canStringCoerce) {
                    if (accessor != NULL) {
                        accessor->reportError(accessorCookie,
                                "Color types not allowed");
                    }
                    return false;
                }
            } else {
                outValue->data = color;
                //printf("Color input=%s, output=0x%x\n", String8(s, len).string(), color);
                return true;
            }
        } else {
            if ((attrType&ResTable_map::TYPE_COLOR) != 0) {
                if (accessor != NULL) {
                    accessor->reportError(accessorCookie, "Color value not valid --"
                            " must be #rgb, #argb, #rrggbb, or #aarrggbb");
                }
                #if 0
                fprintf(stderr, "%s: Color ID %s value %s is not valid\n",
                        "Resource File", //(const char*)in->getPrintableSource(),
                        String8(*curTag).string(),
                        String8(s, len).string());
                #endif
                return false;
            }
        }
    }

    if (*s == '?') {
        outValue->dataType = outValue->TYPE_ATTRIBUTE;

        // Note: we don't check attrType here because the reference can
        // be to any other type; we just need to count on the client making
        // sure the referenced type is correct.

        //printf("Looking up attr: %s\n", String8(s, len).string());

        static const String16 attr16("attr");
        String16 package, type, name;
        if (!expandResourceRef(s+1, len-1, &package, &type, &name,
                               &attr16, defPackage, &errorMsg)) {
            if (accessor != NULL) {
                accessor->reportError(accessorCookie, errorMsg);
            }
            return false;
        }

        //printf("Pkg: %s, Type: %s, Name: %s\n",
        //       String8(package).string(), String8(type).string(),
        //       String8(name).string());
        uint32_t specFlags = 0;
        uint32_t rid = 
            identifierForName(name.string(), name.size(),
                              type.string(), type.size(),
                              package.string(), package.size(), &specFlags);
        if (rid != 0) {
            if (enforcePrivate) {
                if ((specFlags&ResTable_typeSpec::SPEC_PUBLIC) == 0) {
                    if (accessor != NULL) {
                        accessor->reportError(accessorCookie, "Attribute is not public.");
                    }
                    return false;
                }
            }
            if (!accessor) {
                outValue->data = rid;
                return true;
            }
            rid = Res_MAKEID(
                accessor->getRemappedPackage(Res_GETPACKAGE(rid)),
                Res_GETTYPE(rid), Res_GETENTRY(rid));
            //printf("Incl %s:%s/%s: 0x%08x\n",
            //       String8(package).string(), String8(type).string(),
            //       String8(name).string(), rid);
            outValue->data = rid;
            return true;
        }

        if (accessor) {
            uint32_t rid = accessor->getCustomResource(package, type, name);
            if (rid != 0) {
                //printf("Mine %s:%s/%s: 0x%08x\n",
                //       String8(package).string(), String8(type).string(),
                //       String8(name).string(), rid);
                outValue->data = rid;
                return true;
            }
        }

        if (accessor != NULL) {
            accessor->reportError(accessorCookie, "No resource found that matches the given name");
        }
        return false;
    }

    if (stringToInt(s, len, outValue)) {
        if ((attrType&ResTable_map::TYPE_INTEGER) == 0) {
            // If this type does not allow integers, but does allow floats,
            // fall through on this error case because the float type should
            // be able to accept any integer value.
            if (!canStringCoerce && (attrType&ResTable_map::TYPE_FLOAT) == 0) {
                if (accessor != NULL) {
                    accessor->reportError(accessorCookie, "Integer types not allowed");
                }
                return false;
            }
        } else {
            if (((int32_t)outValue->data) < ((int32_t)attrMin)
                    || ((int32_t)outValue->data) > ((int32_t)attrMax)) {
                if (accessor != NULL) {
                    accessor->reportError(accessorCookie, "Integer value out of range");
                }
                return false;
            }
            return true;
        }
    }

    if (stringToFloat(s, len, outValue)) {
        if (outValue->dataType == Res_value::TYPE_DIMENSION) {
            if ((attrType&ResTable_map::TYPE_DIMENSION) != 0) {
                return true;
            }
            if (!canStringCoerce) {
                if (accessor != NULL) {
                    accessor->reportError(accessorCookie, "Dimension types not allowed");
                }
                return false;
            }
        } else if (outValue->dataType == Res_value::TYPE_FRACTION) {
            if ((attrType&ResTable_map::TYPE_FRACTION) != 0) {
                return true;
            }
            if (!canStringCoerce) {
                if (accessor != NULL) {
                    accessor->reportError(accessorCookie, "Fraction types not allowed");
                }
                return false;
            }
        } else if ((attrType&ResTable_map::TYPE_FLOAT) == 0) {
            if (!canStringCoerce) {
                if (accessor != NULL) {
                    accessor->reportError(accessorCookie, "Float types not allowed");
                }
                return false;
            }
        } else {
            return true;
        }
    }

    if (len == 4) {
        if ((s[0] == 't' || s[0] == 'T') &&
            (s[1] == 'r' || s[1] == 'R') &&
            (s[2] == 'u' || s[2] == 'U') &&
            (s[3] == 'e' || s[3] == 'E')) {
            if ((attrType&ResTable_map::TYPE_BOOLEAN) == 0) {
                if (!canStringCoerce) {
                    if (accessor != NULL) {
                        accessor->reportError(accessorCookie, "Boolean types not allowed");
                    }
                    return false;
                }
            } else {
                outValue->dataType = outValue->TYPE_INT_BOOLEAN;
                outValue->data = (uint32_t)-1;
                return true;
            }
        }
    }

    if (len == 5) {
        if ((s[0] == 'f' || s[0] == 'F') &&
            (s[1] == 'a' || s[1] == 'A') &&
            (s[2] == 'l' || s[2] == 'L') &&
            (s[3] == 's' || s[3] == 'S') &&
            (s[4] == 'e' || s[4] == 'E')) {
            if ((attrType&ResTable_map::TYPE_BOOLEAN) == 0) {
                if (!canStringCoerce) {
                    if (accessor != NULL) {
                        accessor->reportError(accessorCookie, "Boolean types not allowed");
                    }
                    return false;
                }
            } else {
                outValue->dataType = outValue->TYPE_INT_BOOLEAN;
                outValue->data = 0;
                return true;
            }
        }
    }

    if ((attrType&ResTable_map::TYPE_ENUM) != 0) {
        const ssize_t p = getResourcePackageIndex(attrID);
        const bag_entry* bag;
        ssize_t cnt = p >= 0 ? lockBag(attrID, &bag) : -1;
        //printf("Got %d for enum\n", cnt);
        if (cnt >= 0) {
            resource_name rname;
            while (cnt > 0) {
                if (!Res_INTERNALID(bag->map.name.ident)) {
                    //printf("Trying attr #%08x\n", bag->map.name.ident);
                    if (getResourceName(bag->map.name.ident, &rname)) {
                        #if 0
                        printf("Matching %s against %s (0x%08x)\n",
                               String8(s, len).string(),
                               String8(rname.name, rname.nameLen).string(),
                               bag->map.name.ident);
                        #endif
                        if (strzcmp16(s, len, rname.name, rname.nameLen) == 0) {
                            outValue->dataType = bag->map.value.dataType;
                            outValue->data = bag->map.value.data;
                            unlockBag(bag);
                            return true;
                        }
                    }
    
                }
                bag++;
                cnt--;
            }
            unlockBag(bag);
        }

        if (fromAccessor) {
            if (accessor->getAttributeEnum(attrID, s, len, outValue)) {
                return true;
            }
        }
    }

    if ((attrType&ResTable_map::TYPE_FLAGS) != 0) {
        const ssize_t p = getResourcePackageIndex(attrID);
        const bag_entry* bag;
        ssize_t cnt = p >= 0 ? lockBag(attrID, &bag) : -1;
        //printf("Got %d for flags\n", cnt);
        if (cnt >= 0) {
            bool failed = false;
            resource_name rname;
            outValue->dataType = Res_value::TYPE_INT_HEX;
            outValue->data = 0;
            const char16_t* end = s + len;
            const char16_t* pos = s;
            while (pos < end && !failed) {
                const char16_t* start = pos;
                pos++;
                while (pos < end && *pos != '|') {
                    pos++;
                }
                //printf("Looking for: %s\n", String8(start, pos-start).string());
                const bag_entry* bagi = bag;
                ssize_t i;
                for (i=0; i<cnt; i++, bagi++) {
                    if (!Res_INTERNALID(bagi->map.name.ident)) {
                        //printf("Trying attr #%08x\n", bagi->map.name.ident);
                        if (getResourceName(bagi->map.name.ident, &rname)) {
                            #if 0
                            printf("Matching %s against %s (0x%08x)\n",
                                   String8(start,pos-start).string(),
                                   String8(rname.name, rname.nameLen).string(),
                                   bagi->map.name.ident);
                            #endif
                            if (strzcmp16(start, pos-start, rname.name, rname.nameLen) == 0) {
                                outValue->data |= bagi->map.value.data;
                                break;
                            }
                        }
                    }
                }
                if (i >= cnt) {
                    // Didn't find this flag identifier.
                    failed = true;
                }
                if (pos < end) {
                    pos++;
                }
            }
            unlockBag(bag);
            if (!failed) {
                //printf("Final flag value: 0x%lx\n", outValue->data);
                return true;
            }
        }


        if (fromAccessor) {
            if (accessor->getAttributeFlags(attrID, s, len, outValue)) {
                //printf("Final flag value: 0x%lx\n", outValue->data);
                return true;
            }
        }
    }

    if ((attrType&ResTable_map::TYPE_STRING) == 0) {
        if (accessor != NULL) {
            accessor->reportError(accessorCookie, "String types not allowed");
        }
        return false;
    }

    // Generic string handling...
    outValue->dataType = outValue->TYPE_STRING;
    if (outString) {
        bool failed = collectString(outString, s, len, preserveSpaces, &errorMsg);
        if (accessor != NULL) {
            accessor->reportError(accessorCookie, errorMsg);
        }
        return failed;
    }

    return true;
}

bool ResTable::collectString(String16* outString,
                             const char16_t* s, size_t len,
                             bool preserveSpaces,
                             const char** outErrorMsg,
                             bool append)
{
    String16 tmp;

    char quoted = 0;
    const char16_t* p = s;
    while (p < (s+len)) {
        while (p < (s+len)) {
            const char16_t c = *p;
            if (c == '\\') {
                break;
            }
            if (!preserveSpaces) {
                if (quoted == 0 && isspace16(c)
                    && (c != ' ' || isspace16(*(p+1)))) {
                    break;
                }
                if (c == '"' && (quoted == 0 || quoted == '"')) {
                    break;
                }
                if (c == '\'' && (quoted == 0 || quoted == '\'')) {
                    /*
                     * In practice, when people write ' instead of \'
                     * in a string, they are doing it by accident
                     * instead of really meaning to use ' as a quoting
                     * character.  Warn them so they don't lose it.
                     */
                    if (outErrorMsg) {
                        *outErrorMsg = "Apostrophe not preceded by \\";
                    }
                    return false;
                }
            }
            p++;
        }
        if (p < (s+len)) {
            if (p > s) {
                tmp.append(String16(s, p-s));
            }
            if (!preserveSpaces && (*p == '"' || *p == '\'')) {
                if (quoted == 0) {
                    quoted = *p;
                } else {
                    quoted = 0;
                }
                p++;
            } else if (!preserveSpaces && isspace16(*p)) {
                // Space outside of a quote -- consume all spaces and
                // leave a single plain space char.
                tmp.append(String16(" "));
                p++;
                while (p < (s+len) && isspace16(*p)) {
                    p++;
                }
            } else if (*p == '\\') {
                p++;
                if (p < (s+len)) {
                    switch (*p) {
                    case 't':
                        tmp.append(String16("\t"));
                        break;
                    case 'n':
                        tmp.append(String16("\n"));
                        break;
                    case '#':
                        tmp.append(String16("#"));
                        break;
                    case '@':
                        tmp.append(String16("@"));
                        break;
                    case '?':
                        tmp.append(String16("?"));
                        break;
                    case '"':
                        tmp.append(String16("\""));
                        break;
                    case '\'':
                        tmp.append(String16("'"));
                        break;
                    case '\\':
                        tmp.append(String16("\\"));
                        break;
                    case 'u':
                    {
                        char16_t chr = 0;
                        int i = 0;
                        while (i < 4 && p[1] != 0) {
                            p++;
                            i++;
                            int c;
                            if (*p >= '0' && *p <= '9') {
                                c = *p - '0';
                            } else if (*p >= 'a' && *p <= 'f') {
                                c = *p - 'a' + 10;
                            } else if (*p >= 'A' && *p <= 'F') {
                                c = *p - 'A' + 10;
                            } else {
                                if (outErrorMsg) {
                                    *outErrorMsg = "Bad character in \\u unicode escape sequence";
                                }
                                return false;
                            }
                            chr = (chr<<4) | c;
                        }
                        tmp.append(String16(&chr, 1));
                    } break;
                    default:
                        // ignore unknown escape chars.
                        break;
                    }
                    p++;
                }
            }
            len -= (p-s);
            s = p;
        }
    }

    if (tmp.size() != 0) {
        if (len > 0) {
            tmp.append(String16(s, len));
        }
        if (append) {
            outString->append(tmp);
        } else {
            outString->setTo(tmp);
        }
    } else {
        if (append) {
            outString->append(String16(s, len));
        } else {
            outString->setTo(s, len);
        }
    }

    return true;
}

size_t ResTable::getBasePackageCount() const
{
    if (mError != NO_ERROR) {
        return 0;
    }
    return mPackageGroups.size();
}

const char16_t* ResTable::getBasePackageName(size_t idx) const
{
    if (mError != NO_ERROR) {
        return 0;
    }
    LOG_FATAL_IF(idx >= mPackageGroups.size(),
                 "Requested package index %d past package count %d",
                 (int)idx, (int)mPackageGroups.size());
    return mPackageGroups[idx]->name.string();
}

uint32_t ResTable::getBasePackageId(size_t idx) const
{
    if (mError != NO_ERROR) {
        return 0;
    }
    LOG_FATAL_IF(idx >= mPackageGroups.size(),
                 "Requested package index %d past package count %d",
                 (int)idx, (int)mPackageGroups.size());
    return mPackageGroups[idx]->id;
}

size_t ResTable::getTableCount() const
{
    return mHeaders.size();
}

const ResStringPool* ResTable::getTableStringBlock(size_t index) const
{
    return &mHeaders[index]->values;
}

void* ResTable::getTableCookie(size_t index) const
{
    return mHeaders[index]->cookie;
}

void ResTable::getConfigurations(Vector<ResTable_config>* configs) const
{
    const size_t I = mPackageGroups.size();
    for (size_t i=0; i<I; i++) {
        const PackageGroup* packageGroup = mPackageGroups[i];
        const size_t J = packageGroup->packages.size();
        for (size_t j=0; j<J; j++) {
            const Package* package = packageGroup->packages[j];
            const size_t K = package->types.size();
            for (size_t k=0; k<K; k++) {
                const Type* type = package->types[k];
                if (type == NULL) continue;
                const size_t L = type->configs.size();
                for (size_t l=0; l<L; l++) {
                    const ResTable_type* config = type->configs[l];
                    const ResTable_config* cfg = &config->config;
                    // only insert unique
                    const size_t M = configs->size();
                    size_t m;
                    for (m=0; m<M; m++) {
                        if (0 == (*configs)[m].compare(*cfg)) {
                            break;
                        }
                    }
                    // if we didn't find it
                    if (m == M) {
                        configs->add(*cfg);
                    }
                }
            }
        }
    }
}

void ResTable::getLocales(Vector<String8>* locales) const
{
    Vector<ResTable_config> configs;
    ALOGV("calling getConfigurations");
    getConfigurations(&configs);
    ALOGV("called getConfigurations size=%d", (int)configs.size());
    const size_t I = configs.size();
    for (size_t i=0; i<I; i++) {
        char locale[6];
        configs[i].getLocale(locale);
        const size_t J = locales->size();
        size_t j;
        for (j=0; j<J; j++) {
            if (0 == strcmp(locale, (*locales)[j].string())) {
                break;
            }
        }
        if (j == J) {
            locales->add(String8(locale));
        }
    }
}

ssize_t ResTable::getEntry(
    const Package* package, int typeIndex, int entryIndex,
    const ResTable_config* config,
    const ResTable_type** outType, const ResTable_entry** outEntry,
    const Type** outTypeClass) const
{
    ALOGV("Getting entry from package %p\n", package);
    const ResTable_package* const pkg = package->package;

    const Type* allTypes = package->getType(typeIndex);
    ALOGV("allTypes=%p\n", allTypes);
    if (allTypes == NULL) {
        ALOGV("Skipping entry type index 0x%02x because type is NULL!\n", typeIndex);
        return 0;
    }

    if ((size_t)entryIndex >= allTypes->entryCount) {
        LOGW("getEntry failing because entryIndex %d is beyond type entryCount %d",
            entryIndex, (int)allTypes->entryCount);
        return BAD_TYPE;
    }
        
    const ResTable_type* type = NULL;
    uint32_t offset = ResTable_type::NO_ENTRY;
    ResTable_config bestConfig;
    memset(&bestConfig, 0, sizeof(bestConfig)); // make the compiler shut up
    
    const size_t NT = allTypes->configs.size();
    for (size_t i=0; i<NT; i++) {
        const ResTable_type* const thisType = allTypes->configs[i];
        if (thisType == NULL) continue;
        
        ResTable_config thisConfig;
        thisConfig.copyFromDtoH(thisType->config);

        TABLE_GETENTRY(LOGI("Match entry 0x%x in type 0x%x (sz 0x%x): imsi:%d/%d=%d/%d "
                            "lang:%c%c=%c%c cnt:%c%c=%c%c orien:%d=%d touch:%d=%d "
                            "density:%d=%d key:%d=%d inp:%d=%d nav:%d=%d w:%d=%d h:%d=%d "
                            "swdp:%d=%d wdp:%d=%d hdp:%d=%d\n",
                           entryIndex, typeIndex+1, dtohl(thisType->config.size),
                           thisConfig.mcc, thisConfig.mnc,
                           config ? config->mcc : 0, config ? config->mnc : 0,
                           thisConfig.language[0] ? thisConfig.language[0] : '-',
                           thisConfig.language[1] ? thisConfig.language[1] : '-',
                           config && config->language[0] ? config->language[0] : '-',
                           config && config->language[1] ? config->language[1] : '-',
                           thisConfig.country[0] ? thisConfig.country[0] : '-',
                           thisConfig.country[1] ? thisConfig.country[1] : '-',
                           config && config->country[0] ? config->country[0] : '-',
                           config && config->country[1] ? config->country[1] : '-',
                           thisConfig.orientation,
                           config ? config->orientation : 0,
                           thisConfig.touchscreen,
                           config ? config->touchscreen : 0,
                           thisConfig.density,
                           config ? config->density : 0,
                           thisConfig.keyboard,
                           config ? config->keyboard : 0,
                           thisConfig.inputFlags,
                           config ? config->inputFlags : 0,
                           thisConfig.navigation,
                           config ? config->navigation : 0,
                           thisConfig.screenWidth,
                           config ? config->screenWidth : 0,
                           thisConfig.screenHeight,
                           config ? config->screenHeight : 0,
                           thisConfig.smallestScreenWidthDp,
                           config ? config->smallestScreenWidthDp : 0,
                           thisConfig.screenWidthDp,
                           config ? config->screenWidthDp : 0,
                           thisConfig.screenHeightDp,
                           config ? config->screenHeightDp : 0));
        
        // Check to make sure this one is valid for the current parameters.
        if (config && !thisConfig.match(*config)) {
            TABLE_GETENTRY(LOGI("Does not match config!\n"));
            continue;
        }
        
        // Check if there is the desired entry in this type.
        
        const uint8_t* const end = ((const uint8_t*)thisType)
            + dtohl(thisType->header.size);
        const uint32_t* const eindex = (const uint32_t*)
            (((const uint8_t*)thisType) + dtohs(thisType->header.headerSize));
        
        uint32_t thisOffset = dtohl(eindex[entryIndex]);
        if (thisOffset == ResTable_type::NO_ENTRY) {
            TABLE_GETENTRY(LOGI("Skipping because it is not defined!\n"));
            continue;
        }
        
        if (type != NULL) {
            // Check if this one is less specific than the last found.  If so,
            // we will skip it.  We check starting with things we most care
            // about to those we least care about.
            if (!thisConfig.isBetterThan(bestConfig, config)) {
                TABLE_GETENTRY(LOGI("This config is worse than last!\n"));
                continue;
            }
        }
        
        type = thisType;
        offset = thisOffset;
        bestConfig = thisConfig;
        TABLE_GETENTRY(LOGI("Best entry so far -- using it!\n"));
        if (!config) break;
    }
    
    if (type == NULL) {
        TABLE_GETENTRY(LOGI("No value found for requested entry!\n"));
        return BAD_INDEX;
    }
    
    offset += dtohl(type->entriesStart);
    TABLE_NOISY(aout << "Looking in resource table " << package->header->header
          << ", typeOff="
          << (void*)(((const char*)type)-((const char*)package->header->header))
          << ", offset=" << (void*)offset << endl);

    if (offset > (dtohl(type->header.size)-sizeof(ResTable_entry))) {
        LOGW("ResTable_entry at 0x%x is beyond type chunk data 0x%x",
             offset, dtohl(type->header.size));
        return BAD_TYPE;
    }
    if ((offset&0x3) != 0) {
        LOGW("ResTable_entry at 0x%x is not on an integer boundary",
             offset);
        return BAD_TYPE;
    }

    const ResTable_entry* const entry = (const ResTable_entry*)
        (((const uint8_t*)type) + offset);
    if (dtohs(entry->size) < sizeof(*entry)) {
        LOGW("ResTable_entry size 0x%x is too small", dtohs(entry->size));
        return BAD_TYPE;
    }

    *outType = type;
    *outEntry = entry;
    if (outTypeClass != NULL) {
        *outTypeClass = allTypes;
    }
    return offset + dtohs(entry->size);
}

status_t ResTable::parsePackage(const ResTable_package* const pkg,
                                const Header* const header, uint32_t idmap_id)
{
    const uint8_t* base = (const uint8_t*)pkg;
    status_t err = validate_chunk(&pkg->header, sizeof(*pkg),
                                  header->dataEnd, "ResTable_package");
    if (err != NO_ERROR) {
        return (mError=err);
    }

    const size_t pkgSize = dtohl(pkg->header.size);

    if (dtohl(pkg->typeStrings) >= pkgSize) {
        LOGW("ResTable_package type strings at %p are past chunk size %p.",
             (void*)dtohl(pkg->typeStrings), (void*)pkgSize);
        return (mError=BAD_TYPE);
    }
    if ((dtohl(pkg->typeStrings)&0x3) != 0) {
        LOGW("ResTable_package type strings at %p is not on an integer boundary.",
             (void*)dtohl(pkg->typeStrings));
        return (mError=BAD_TYPE);
    }
    if (dtohl(pkg->keyStrings) >= pkgSize) {
        LOGW("ResTable_package key strings at %p are past chunk size %p.",
             (void*)dtohl(pkg->keyStrings), (void*)pkgSize);
        return (mError=BAD_TYPE);
    }
    if ((dtohl(pkg->keyStrings)&0x3) != 0) {
        LOGW("ResTable_package key strings at %p is not on an integer boundary.",
             (void*)dtohl(pkg->keyStrings));
        return (mError=BAD_TYPE);
    }
    
    Package* package = NULL;
    PackageGroup* group = NULL;
    uint32_t id = idmap_id != 0 ? idmap_id : dtohl(pkg->id);
    // If at this point id == 0, pkg is an overlay package without a
    // corresponding idmap. During regular usage, overlay packages are
    // always loaded alongside their idmaps, but during idmap creation
    // the package is temporarily loaded by itself.
    if (id < 256) {
    
        package = new Package(this, header, pkg);
        if (package == NULL) {
            return (mError=NO_MEMORY);
        }
        
        size_t idx = mPackageMap[id];
        if (idx == 0) {
            idx = mPackageGroups.size()+1;

            char16_t tmpName[sizeof(pkg->name)/sizeof(char16_t)];
            strcpy16_dtoh(tmpName, pkg->name, sizeof(pkg->name)/sizeof(char16_t));
            group = new PackageGroup(this, String16(tmpName), id);
            if (group == NULL) {
                delete package;
                return (mError=NO_MEMORY);
            }

            err = package->typeStrings.setTo(base+dtohl(pkg->typeStrings),
                                           header->dataEnd-(base+dtohl(pkg->typeStrings)));
            if (err != NO_ERROR) {
                delete group;
                delete package;
                return (mError=err);
            }
            err = package->keyStrings.setTo(base+dtohl(pkg->keyStrings),
                                          header->dataEnd-(base+dtohl(pkg->keyStrings)));
            if (err != NO_ERROR) {
                delete group;
                delete package;
                return (mError=err);
            }

            //printf("Adding new package id %d at index %d\n", id, idx);
            err = mPackageGroups.add(group);
            if (err < NO_ERROR) {
                return (mError=err);
            }
            group->basePackage = package;
            
            mPackageMap[id] = (uint8_t)idx;
        } else {
            group = mPackageGroups.itemAt(idx-1);
            if (group == NULL) {
                return (mError=UNKNOWN_ERROR);
            }
        }
        err = group->packages.add(package);
        if (err < NO_ERROR) {
            return (mError=err);
        }
    } else {
        LOG_ALWAYS_FATAL("Package id out of range");
        return NO_ERROR;
    }

    
    // Iterate through all chunks.
    size_t curPackage = 0;
    
    const ResChunk_header* chunk =
        (const ResChunk_header*)(((const uint8_t*)pkg)
                                 + dtohs(pkg->header.headerSize));
    const uint8_t* endPos = ((const uint8_t*)pkg) + dtohs(pkg->header.size);
    while (((const uint8_t*)chunk) <= (endPos-sizeof(ResChunk_header)) &&
           ((const uint8_t*)chunk) <= (endPos-dtohl(chunk->size))) {
        TABLE_NOISY(LOGV("PackageChunk: type=0x%x, headerSize=0x%x, size=0x%x, pos=%p\n",
                         dtohs(chunk->type), dtohs(chunk->headerSize), dtohl(chunk->size),
                         (void*)(((const uint8_t*)chunk) - ((const uint8_t*)header->header))));
        const size_t csize = dtohl(chunk->size);
        const uint16_t ctype = dtohs(chunk->type);
        if (ctype == RES_TABLE_TYPE_SPEC_TYPE) {
            const ResTable_typeSpec* typeSpec = (const ResTable_typeSpec*)(chunk);
            err = validate_chunk(&typeSpec->header, sizeof(*typeSpec),
                                 endPos, "ResTable_typeSpec");
            if (err != NO_ERROR) {
                return (mError=err);
            }
            
            const size_t typeSpecSize = dtohl(typeSpec->header.size);
            
            LOAD_TABLE_NOISY(printf("TypeSpec off %p: type=0x%x, headerSize=0x%x, size=%p\n",
                                    (void*)(base-(const uint8_t*)chunk),
                                    dtohs(typeSpec->header.type),
                                    dtohs(typeSpec->header.headerSize),
                                    (void*)typeSize));
            // look for block overrun or int overflow when multiplying by 4
            if ((dtohl(typeSpec->entryCount) > (INT32_MAX/sizeof(uint32_t))
                    || dtohs(typeSpec->header.headerSize)+(sizeof(uint32_t)*dtohl(typeSpec->entryCount))
                    > typeSpecSize)) {
                LOGW("ResTable_typeSpec entry index to %p extends beyond chunk end %p.",
                     (void*)(dtohs(typeSpec->header.headerSize)
                             +(sizeof(uint32_t)*dtohl(typeSpec->entryCount))),
                     (void*)typeSpecSize);
                return (mError=BAD_TYPE);
            }
            
            if (typeSpec->id == 0) {
                LOGW("ResTable_type has an id of 0.");
                return (mError=BAD_TYPE);
            }
            
            while (package->types.size() < typeSpec->id) {
                package->types.add(NULL);
            }
            Type* t = package->types[typeSpec->id-1];
            if (t == NULL) {
                t = new Type(header, package, dtohl(typeSpec->entryCount));
                package->types.editItemAt(typeSpec->id-1) = t;
            } else if (dtohl(typeSpec->entryCount) != t->entryCount) {
                LOGW("ResTable_typeSpec entry count inconsistent: given %d, previously %d",
                    (int)dtohl(typeSpec->entryCount), (int)t->entryCount);
                return (mError=BAD_TYPE);
            }
            t->typeSpecFlags = (const uint32_t*)(
                    ((const uint8_t*)typeSpec) + dtohs(typeSpec->header.headerSize));
            t->typeSpec = typeSpec;
            
        } else if (ctype == RES_TABLE_TYPE_TYPE) {
            const ResTable_type* type = (const ResTable_type*)(chunk);
            err = validate_chunk(&type->header, sizeof(*type)-sizeof(ResTable_config)+4,
                                 endPos, "ResTable_type");
            if (err != NO_ERROR) {
                return (mError=err);
            }
            
            const size_t typeSize = dtohl(type->header.size);
            
            LOAD_TABLE_NOISY(printf("Type off %p: type=0x%x, headerSize=0x%x, size=%p\n",
                                    (void*)(base-(const uint8_t*)chunk),
                                    dtohs(type->header.type),
                                    dtohs(type->header.headerSize),
                                    (void*)typeSize));
            if (dtohs(type->header.headerSize)+(sizeof(uint32_t)*dtohl(type->entryCount))
                > typeSize) {
                LOGW("ResTable_type entry index to %p extends beyond chunk end %p.",
                     (void*)(dtohs(type->header.headerSize)
                             +(sizeof(uint32_t)*dtohl(type->entryCount))),
                     (void*)typeSize);
                return (mError=BAD_TYPE);
            }
            if (dtohl(type->entryCount) != 0
                && dtohl(type->entriesStart) > (typeSize-sizeof(ResTable_entry))) {
                LOGW("ResTable_type entriesStart at %p extends beyond chunk end %p.",
                     (void*)dtohl(type->entriesStart), (void*)typeSize);
                return (mError=BAD_TYPE);
            }
            if (type->id == 0) {
                LOGW("ResTable_type has an id of 0.");
                return (mError=BAD_TYPE);
            }
            
            while (package->types.size() < type->id) {
                package->types.add(NULL);
            }
            Type* t = package->types[type->id-1];
            if (t == NULL) {
                t = new Type(header, package, dtohl(type->entryCount));
                package->types.editItemAt(type->id-1) = t;
            } else if (dtohl(type->entryCount) != t->entryCount) {
                LOGW("ResTable_type entry count inconsistent: given %d, previously %d",
                    (int)dtohl(type->entryCount), (int)t->entryCount);
                return (mError=BAD_TYPE);
            }
            
            TABLE_GETENTRY(
                ResTable_config thisConfig;
                thisConfig.copyFromDtoH(type->config);
                ALOGI("Adding config to type %d: imsi:%d/%d lang:%c%c cnt:%c%c "
                     "orien:%d touch:%d density:%d key:%d inp:%d nav:%d w:%d h:%d "
                     "swdp:%d wdp:%d hdp:%d\n",
                      type->id,
                      thisConfig.mcc, thisConfig.mnc,
                      thisConfig.language[0] ? thisConfig.language[0] : '-',
                      thisConfig.language[1] ? thisConfig.language[1] : '-',
                      thisConfig.country[0] ? thisConfig.country[0] : '-',
                      thisConfig.country[1] ? thisConfig.country[1] : '-',
                      thisConfig.orientation,
                      thisConfig.touchscreen,
                      thisConfig.density,
                      thisConfig.keyboard,
                      thisConfig.inputFlags,
                      thisConfig.navigation,
                      thisConfig.screenWidth,
                      thisConfig.screenHeight,
                      thisConfig.smallestScreenWidthDp,
                      thisConfig.screenWidthDp,
                      thisConfig.screenHeightDp));
            t->configs.add(type);
        } else {
            status_t err = validate_chunk(chunk, sizeof(ResChunk_header),
                                          endPos, "ResTable_package:unknown");
            if (err != NO_ERROR) {
                return (mError=err);
            }
        }
        chunk = (const ResChunk_header*)
            (((const uint8_t*)chunk) + csize);
    }

    if (group->typeCount == 0) {
        group->typeCount = package->types.size();
    }
    
    return NO_ERROR;
}

status_t ResTable::createIdmap(const ResTable& overlay, uint32_t originalCrc, uint32_t overlayCrc,
                               void** outData, size_t* outSize) const
{
    // see README for details on the format of map
    if (mPackageGroups.size() == 0) {
        return UNKNOWN_ERROR;
    }
    if (mPackageGroups[0]->packages.size() == 0) {
        return UNKNOWN_ERROR;
    }

    Vector<Vector<uint32_t> > map;
    const PackageGroup* pg = mPackageGroups[0];
    const Package* pkg = pg->packages[0];
    size_t typeCount = pkg->types.size();
    // starting size is header + first item (number of types in map)
    *outSize = (IDMAP_HEADER_SIZE + 1) * sizeof(uint32_t);
    const String16 overlayPackage(overlay.mPackageGroups[0]->packages[0]->package->name);
    const uint32_t pkg_id = pkg->package->id << 24;

    for (size_t typeIndex = 0; typeIndex < typeCount; ++typeIndex) {
        ssize_t offset = -1;
        const Type* typeConfigs = pkg->getType(typeIndex);
        ssize_t mapIndex = map.add();
        if (mapIndex < 0) {
            return NO_MEMORY;
        }
        Vector<uint32_t>& vector = map.editItemAt(mapIndex);
        for (size_t entryIndex = 0; entryIndex < typeConfigs->entryCount; ++entryIndex) {
            uint32_t resID = (0xff000000 & ((pkg->package->id)<<24))
                | (0x00ff0000 & ((typeIndex+1)<<16))
                | (0x0000ffff & (entryIndex));
            resource_name resName;
            if (!this->getResourceName(resID, &resName)) {
                LOGW("idmap: resource 0x%08x has spec but lacks values, skipping\n", resID);
                continue;
            }

            const String16 overlayType(resName.type, resName.typeLen);
            const String16 overlayName(resName.name, resName.nameLen);
            uint32_t overlayResID = overlay.identifierForName(overlayName.string(),
                                                              overlayName.size(),
                                                              overlayType.string(),
                                                              overlayType.size(),
                                                              overlayPackage.string(),
                                                              overlayPackage.size());
            if (overlayResID != 0) {
                // overlay package has package ID == 0, use original package's ID instead
                overlayResID |= pkg_id;
            }
            vector.push(overlayResID);
            if (overlayResID != 0 && offset == -1) {
                offset = Res_GETENTRY(resID);
            }
#if 0
            if (overlayResID != 0) {
                ALOGD("%s/%s 0x%08x -> 0x%08x\n",
                     String8(String16(resName.type)).string(),
                     String8(String16(resName.name)).string(),
                     resID, overlayResID);
            }
#endif
        }

        if (offset != -1) {
            // shave off leading and trailing entries which lack overlay values
            vector.removeItemsAt(0, offset);
            vector.insertAt((uint32_t)offset, 0, 1);
            while (vector.top() == 0) {
                vector.pop();
            }
            // reserve space for number and offset of entries, and the actual entries
            *outSize += (2 + vector.size()) * sizeof(uint32_t);
        } else {
            // no entries of current type defined in overlay package
            vector.clear();
            // reserve space for type offset
            *outSize += 1 * sizeof(uint32_t);
        }
    }

    if ((*outData = malloc(*outSize)) == NULL) {
        return NO_MEMORY;
    }
    uint32_t* data = (uint32_t*)*outData;
    *data++ = htodl(IDMAP_MAGIC);
    *data++ = htodl(originalCrc);
    *data++ = htodl(overlayCrc);
    const size_t mapSize = map.size();
    *data++ = htodl(mapSize);
    size_t offset = mapSize;
    for (size_t i = 0; i < mapSize; ++i) {
        const Vector<uint32_t>& vector = map.itemAt(i);
        const size_t N = vector.size();
        if (N == 0) {
            *data++ = htodl(0);
        } else {
            offset++;
            *data++ = htodl(offset);
            offset += N;
        }
    }
    for (size_t i = 0; i < mapSize; ++i) {
        const Vector<uint32_t>& vector = map.itemAt(i);
        const size_t N = vector.size();
        if (N == 0) {
            continue;
        }
        *data++ = htodl(N - 1); // do not count the offset (which is vector's first element)
        for (size_t j = 0; j < N; ++j) {
            const uint32_t& overlayResID = vector.itemAt(j);
            *data++ = htodl(overlayResID);
        }
    }

    return NO_ERROR;
}

bool ResTable::getIdmapInfo(const void* idmap, size_t sizeBytes,
                            uint32_t* pOriginalCrc, uint32_t* pOverlayCrc)
{
    const uint32_t* map = (const uint32_t*)idmap;
    if (!assertIdmapHeader(map, sizeBytes)) {
        return false;
    }
    *pOriginalCrc = map[1];
    *pOverlayCrc = map[2];
    return true;
}


#ifndef HAVE_ANDROID_OS
#define CHAR16_TO_CSTR(c16, len) (String8(String16(c16,len)).string())

#define CHAR16_ARRAY_EQ(constant, var, len) \
        ((len == (sizeof(constant)/sizeof(constant[0]))) && (0 == memcmp((var), (constant), (len))))

void print_complex(uint32_t complex, bool isFraction)
{
    const float MANTISSA_MULT =
        1.0f / (1<<Res_value::COMPLEX_MANTISSA_SHIFT);
    const float RADIX_MULTS[] = {
        1.0f*MANTISSA_MULT, 1.0f/(1<<7)*MANTISSA_MULT,
        1.0f/(1<<15)*MANTISSA_MULT, 1.0f/(1<<23)*MANTISSA_MULT
    };

    float value = (complex&(Res_value::COMPLEX_MANTISSA_MASK
                   <<Res_value::COMPLEX_MANTISSA_SHIFT))
            * RADIX_MULTS[(complex>>Res_value::COMPLEX_RADIX_SHIFT)
                            & Res_value::COMPLEX_RADIX_MASK];
    printf("%f", value);
    
    if (!isFraction) {
        switch ((complex>>Res_value::COMPLEX_UNIT_SHIFT)&Res_value::COMPLEX_UNIT_MASK) {
            case Res_value::COMPLEX_UNIT_PX: printf("px"); break;
            case Res_value::COMPLEX_UNIT_DIP: printf("dp"); break;
            case Res_value::COMPLEX_UNIT_SP: printf("sp"); break;
            case Res_value::COMPLEX_UNIT_PT: printf("pt"); break;
            case Res_value::COMPLEX_UNIT_IN: printf("in"); break;
            case Res_value::COMPLEX_UNIT_MM: printf("mm"); break;
            default: printf(" (unknown unit)"); break;
        }
    } else {
        switch ((complex>>Res_value::COMPLEX_UNIT_SHIFT)&Res_value::COMPLEX_UNIT_MASK) {
            case Res_value::COMPLEX_UNIT_FRACTION: printf("%%"); break;
            case Res_value::COMPLEX_UNIT_FRACTION_PARENT: printf("%%p"); break;
            default: printf(" (unknown unit)"); break;
        }
    }
}

// Normalize a string for output
String8 ResTable::normalizeForOutput( const char *input )
{
    String8 ret;
    char buff[2];
    buff[1] = '\0';

    while (*input != '\0') {
        switch (*input) {
            // All interesting characters are in the ASCII zone, so we are making our own lives
            // easier by scanning the string one byte at a time.
        case '\\':
            ret += "\\\\";
            break;
        case '\n':
            ret += "\\n";
            break;
        case '"':
            ret += "\\\"";
            break;
        default:
            buff[0] = *input;
            ret += buff;
            break;
        }

        input++;
    }

    return ret;
}

void ResTable::print_value(const Package* pkg, const Res_value& value) const
{
    if (value.dataType == Res_value::TYPE_NULL) {
        printf("(null)\n");
    } else if (value.dataType == Res_value::TYPE_REFERENCE) {
        printf("(reference) 0x%08x\n", value.data);
    } else if (value.dataType == Res_value::TYPE_ATTRIBUTE) {
        printf("(attribute) 0x%08x\n", value.data);
    } else if (value.dataType == Res_value::TYPE_STRING) {
        size_t len;
        const char* str8 = pkg->header->values.string8At(
                value.data, &len);
        if (str8 != NULL) {
            printf("(string8) \"%s\"\n", normalizeForOutput(str8).string());
        } else {
            const char16_t* str16 = pkg->header->values.stringAt(
                    value.data, &len);
            if (str16 != NULL) {
                printf("(string16) \"%s\"\n",
                    normalizeForOutput(String8(str16, len).string()).string());
            } else {
                printf("(string) null\n");
            }
        } 
    } else if (value.dataType == Res_value::TYPE_FLOAT) {
        printf("(float) %g\n", *(const float*)&value.data);
    } else if (value.dataType == Res_value::TYPE_DIMENSION) {
        printf("(dimension) ");
        print_complex(value.data, false);
        printf("\n");
    } else if (value.dataType == Res_value::TYPE_FRACTION) {
        printf("(fraction) ");
        print_complex(value.data, true);
        printf("\n");
    } else if (value.dataType >= Res_value::TYPE_FIRST_COLOR_INT
            || value.dataType <= Res_value::TYPE_LAST_COLOR_INT) {
        printf("(color) #%08x\n", value.data);
    } else if (value.dataType == Res_value::TYPE_INT_BOOLEAN) {
        printf("(boolean) %s\n", value.data ? "true" : "false");
    } else if (value.dataType >= Res_value::TYPE_FIRST_INT
            || value.dataType <= Res_value::TYPE_LAST_INT) {
        printf("(int) 0x%08x or %d\n", value.data, value.data);
    } else {
        printf("(unknown type) t=0x%02x d=0x%08x (s=0x%04x r=0x%02x)\n",
               (int)value.dataType, (int)value.data,
               (int)value.size, (int)value.res0);
    }
}

void ResTable::print(bool inclValues) const
{
    if (mError != 0) {
        printf("mError=0x%x (%s)\n", mError, strerror(mError));
    }
#if 0
    printf("mParams=%c%c-%c%c,\n",
            mParams.language[0], mParams.language[1],
            mParams.country[0], mParams.country[1]);
#endif
    size_t pgCount = mPackageGroups.size();
    printf("Package Groups (%d)\n", (int)pgCount);
    for (size_t pgIndex=0; pgIndex<pgCount; pgIndex++) {
        const PackageGroup* pg = mPackageGroups[pgIndex];
        printf("Package Group %d id=%d packageCount=%d name=%s\n",
                (int)pgIndex, pg->id, (int)pg->packages.size(),
                String8(pg->name).string());
        
        size_t pkgCount = pg->packages.size();
        for (size_t pkgIndex=0; pkgIndex<pkgCount; pkgIndex++) {
            const Package* pkg = pg->packages[pkgIndex];
            size_t typeCount = pkg->types.size();
            printf("  Package %d id=%d name=%s typeCount=%d\n", (int)pkgIndex,
                    pkg->package->id, String8(String16(pkg->package->name)).string(),
                    (int)typeCount);
            for (size_t typeIndex=0; typeIndex<typeCount; typeIndex++) {
                const Type* typeConfigs = pkg->getType(typeIndex);
                if (typeConfigs == NULL) {
                    printf("    type %d NULL\n", (int)typeIndex);
                    continue;
                }
                const size_t NTC = typeConfigs->configs.size();
                printf("    type %d configCount=%d entryCount=%d\n",
                       (int)typeIndex, (int)NTC, (int)typeConfigs->entryCount);
                if (typeConfigs->typeSpecFlags != NULL) {
                    for (size_t entryIndex=0; entryIndex<typeConfigs->entryCount; entryIndex++) {
                        uint32_t resID = (0xff000000 & ((pkg->package->id)<<24))
                                    | (0x00ff0000 & ((typeIndex+1)<<16))
                                    | (0x0000ffff & (entryIndex));
                        resource_name resName;
                        if (this->getResourceName(resID, &resName)) {
                            printf("      spec resource 0x%08x %s:%s/%s: flags=0x%08x\n",
                                resID,
                                CHAR16_TO_CSTR(resName.package, resName.packageLen),
                                CHAR16_TO_CSTR(resName.type, resName.typeLen),
                                CHAR16_TO_CSTR(resName.name, resName.nameLen),
                                dtohl(typeConfigs->typeSpecFlags[entryIndex]));
                        } else {
                            printf("      INVALID TYPE CONFIG FOR RESOURCE 0x%08x\n", resID);
                        }
                    }
                }
                for (size_t configIndex=0; configIndex<NTC; configIndex++) {
                    const ResTable_type* type = typeConfigs->configs[configIndex];
                    if ((((uint64_t)type)&0x3) != 0) {
                        printf("      NON-INTEGER ResTable_type ADDRESS: %p\n", type);
                        continue;
                    }
                    char density[16];
                    uint16_t dval = dtohs(type->config.density);
                    if (dval == ResTable_config::DENSITY_DEFAULT) {
                        strcpy(density, "def");
                    } else if (dval == ResTable_config::DENSITY_NONE) {
                        strcpy(density, "no");
                    } else {
                        sprintf(density, "%d", (int)dval);
                    }
                    printf("      config %d", (int)configIndex);
                    if (type->config.mcc != 0) {
                        printf(" mcc=%d", dtohs(type->config.mcc));
                    }
                    if (type->config.mnc != 0) {
                        printf(" mnc=%d", dtohs(type->config.mnc));
                    }
                    if (type->config.locale != 0) {
                        printf(" lang=%c%c cnt=%c%c",
                               type->config.language[0] ? type->config.language[0] : '-',
                               type->config.language[1] ? type->config.language[1] : '-',
                               type->config.country[0] ? type->config.country[0] : '-',
                               type->config.country[1] ? type->config.country[1] : '-');
                    }
                    if (type->config.screenLayout != 0) {
                        printf(" sz=%d",
                                type->config.screenLayout&ResTable_config::MASK_SCREENSIZE);
                        switch (type->config.screenLayout&ResTable_config::MASK_SCREENSIZE) {
                            case ResTable_config::SCREENSIZE_SMALL:
                                printf(" (small)");
                                break;
                            case ResTable_config::SCREENSIZE_NORMAL:
                                printf(" (normal)");
                                break;
                            case ResTable_config::SCREENSIZE_LARGE:
                                printf(" (large)");
                                break;
                            case ResTable_config::SCREENSIZE_XLARGE:
                                printf(" (xlarge)");
                                break;
                        }
                        printf(" lng=%d",
                                type->config.screenLayout&ResTable_config::MASK_SCREENLONG);
                        switch (type->config.screenLayout&ResTable_config::MASK_SCREENLONG) {
                            case ResTable_config::SCREENLONG_NO:
                                printf(" (notlong)");
                                break;
                            case ResTable_config::SCREENLONG_YES:
                                printf(" (long)");
                                break;
                        }
                    }
                    if (type->config.orientation != 0) {
                        printf(" orient=%d", type->config.orientation);
                        switch (type->config.orientation) {
                            case ResTable_config::ORIENTATION_PORT:
                                printf(" (port)");
                                break;
                            case ResTable_config::ORIENTATION_LAND:
                                printf(" (land)");
                                break;
                            case ResTable_config::ORIENTATION_SQUARE:
                                printf(" (square)");
                                break;
                        }
                    }
                    if (type->config.uiMode != 0) {
                        printf(" type=%d",
                                type->config.uiMode&ResTable_config::MASK_UI_MODE_TYPE);
                        switch (type->config.uiMode&ResTable_config::MASK_UI_MODE_TYPE) {
                            case ResTable_config::UI_MODE_TYPE_NORMAL:
                                printf(" (normal)");
                                break;
                            case ResTable_config::UI_MODE_TYPE_CAR:
                                printf(" (car)");
                                break;
                        }
                        printf(" night=%d",
                                type->config.uiMode&ResTable_config::MASK_UI_MODE_NIGHT);
                        switch (type->config.uiMode&ResTable_config::MASK_UI_MODE_NIGHT) {
                            case ResTable_config::UI_MODE_NIGHT_NO:
                                printf(" (no)");
                                break;
                            case ResTable_config::UI_MODE_NIGHT_YES:
                                printf(" (yes)");
                                break;
                        }
                    }
                    if (dval != 0) {
                        printf(" density=%s", density);
                    }
                    if (type->config.touchscreen != 0) {
                        printf(" touch=%d", type->config.touchscreen);
                        switch (type->config.touchscreen) {
                            case ResTable_config::TOUCHSCREEN_NOTOUCH:
                                printf(" (notouch)");
                                break;
                            case ResTable_config::TOUCHSCREEN_STYLUS:
                                printf(" (stylus)");
                                break;
                            case ResTable_config::TOUCHSCREEN_FINGER:
                                printf(" (finger)");
                                break;
                        }
                    }
                    if (type->config.inputFlags != 0) {
                        printf(" keyhid=%d", type->config.inputFlags&ResTable_config::MASK_KEYSHIDDEN);
                        switch (type->config.inputFlags&ResTable_config::MASK_KEYSHIDDEN) {
                            case ResTable_config::KEYSHIDDEN_NO:
                                printf(" (no)");
                                break;
                            case ResTable_config::KEYSHIDDEN_YES:
                                printf(" (yes)");
                                break;
                            case ResTable_config::KEYSHIDDEN_SOFT:
                                printf(" (soft)");
                                break;
                        }
                        printf(" navhid=%d", type->config.inputFlags&ResTable_config::MASK_NAVHIDDEN);
                        switch (type->config.inputFlags&ResTable_config::MASK_NAVHIDDEN) {
                            case ResTable_config::NAVHIDDEN_NO:
                                printf(" (no)");
                                break;
                            case ResTable_config::NAVHIDDEN_YES:
                                printf(" (yes)");
                                break;
                        }
                    }
                    if (type->config.keyboard != 0) {
                        printf(" kbd=%d", type->config.keyboard);
                        switch (type->config.keyboard) {
                            case ResTable_config::KEYBOARD_NOKEYS:
                                printf(" (nokeys)");
                                break;
                            case ResTable_config::KEYBOARD_QWERTY:
                                printf(" (qwerty)");
                                break;
                            case ResTable_config::KEYBOARD_12KEY:
                                printf(" (12key)");
                                break;
                        }
                    }
                    if (type->config.navigation != 0) {
                        printf(" nav=%d", type->config.navigation);
                        switch (type->config.navigation) {
                            case ResTable_config::NAVIGATION_NONAV:
                                printf(" (nonav)");
                                break;
                            case ResTable_config::NAVIGATION_DPAD:
                                printf(" (dpad)");
                                break;
                            case ResTable_config::NAVIGATION_TRACKBALL:
                                printf(" (trackball)");
                                break;
                            case ResTable_config::NAVIGATION_WHEEL:
                                printf(" (wheel)");
                                break;
                        }
                    }
                    if (type->config.screenWidth != 0) {
                        printf(" w=%d", dtohs(type->config.screenWidth));
                    }
                    if (type->config.screenHeight != 0) {
                        printf(" h=%d", dtohs(type->config.screenHeight));
                    }
                    if (type->config.smallestScreenWidthDp != 0) {
                        printf(" swdp=%d", dtohs(type->config.smallestScreenWidthDp));
                    }
                    if (type->config.screenWidthDp != 0) {
                        printf(" wdp=%d", dtohs(type->config.screenWidthDp));
                    }
                    if (type->config.screenHeightDp != 0) {
                        printf(" hdp=%d", dtohs(type->config.screenHeightDp));
                    }
                    if (type->config.sdkVersion != 0) {
                        printf(" sdk=%d", dtohs(type->config.sdkVersion));
                    }
                    if (type->config.minorVersion != 0) {
                        printf(" mver=%d", dtohs(type->config.minorVersion));
                    }
                    printf("\n");
                    size_t entryCount = dtohl(type->entryCount);
                    uint32_t entriesStart = dtohl(type->entriesStart);
                    if ((entriesStart&0x3) != 0) {
                        printf("      NON-INTEGER ResTable_type entriesStart OFFSET: %p\n", (void*)entriesStart);
                        continue;
                    }
                    uint32_t typeSize = dtohl(type->header.size);
                    if ((typeSize&0x3) != 0) {
                        printf("      NON-INTEGER ResTable_type header.size: %p\n", (void*)typeSize);
                        continue;
                    }
                    for (size_t entryIndex=0; entryIndex<entryCount; entryIndex++) {
                        
                        const uint8_t* const end = ((const uint8_t*)type)
                            + dtohl(type->header.size);
                        const uint32_t* const eindex = (const uint32_t*)
                            (((const uint8_t*)type) + dtohs(type->header.headerSize));
                        
                        uint32_t thisOffset = dtohl(eindex[entryIndex]);
                        if (thisOffset == ResTable_type::NO_ENTRY) {
                            continue;
                        }
                        
                        uint32_t resID = (0xff000000 & ((pkg->package->id)<<24))
                                    | (0x00ff0000 & ((typeIndex+1)<<16))
                                    | (0x0000ffff & (entryIndex));
                        resource_name resName;
                        if (this->getResourceName(resID, &resName)) {
                            printf("        resource 0x%08x %s:%s/%s: ", resID,
                                    CHAR16_TO_CSTR(resName.package, resName.packageLen),
                                    CHAR16_TO_CSTR(resName.type, resName.typeLen),
                                    CHAR16_TO_CSTR(resName.name, resName.nameLen));
                        } else {
                            printf("        INVALID RESOURCE 0x%08x: ", resID);
                        }
                        if ((thisOffset&0x3) != 0) {
                            printf("NON-INTEGER OFFSET: %p\n", (void*)thisOffset);
                            continue;
                        }
                        if ((thisOffset+sizeof(ResTable_entry)) > typeSize) {
                            printf("OFFSET OUT OF BOUNDS: %p+%p (size is %p)\n",
                                   (void*)entriesStart, (void*)thisOffset,
                                   (void*)typeSize);
                            continue;
                        }
                        
                        const ResTable_entry* ent = (const ResTable_entry*)
                            (((const uint8_t*)type) + entriesStart + thisOffset);
                        if (((entriesStart + thisOffset)&0x3) != 0) {
                            printf("NON-INTEGER ResTable_entry OFFSET: %p\n",
                                 (void*)(entriesStart + thisOffset));
                            continue;
                        }
                        
                        uint16_t esize = dtohs(ent->size);
                        if ((esize&0x3) != 0) {
                            printf("NON-INTEGER ResTable_entry SIZE: %p\n", (void*)esize);
                            continue;
                        }
                        if ((thisOffset+esize) > typeSize) {
                            printf("ResTable_entry OUT OF BOUNDS: %p+%p+%p (size is %p)\n",
                                   (void*)entriesStart, (void*)thisOffset,
                                   (void*)esize, (void*)typeSize);
                            continue;
                        }
                            
                        const Res_value* valuePtr = NULL;
                        const ResTable_map_entry* bagPtr = NULL;
                        Res_value value;
                        if ((dtohs(ent->flags)&ResTable_entry::FLAG_COMPLEX) != 0) {
                            printf("<bag>");
                            bagPtr = (const ResTable_map_entry*)ent;
                        } else {
                            valuePtr = (const Res_value*)
                                (((const uint8_t*)ent) + esize);
                            value.copyFrom_dtoh(*valuePtr);
                            printf("t=0x%02x d=0x%08x (s=0x%04x r=0x%02x)",
                                   (int)value.dataType, (int)value.data,
                                   (int)value.size, (int)value.res0);
                        }
                        
                        if ((dtohs(ent->flags)&ResTable_entry::FLAG_PUBLIC) != 0) {
                            printf(" (PUBLIC)");
                        }
                        printf("\n");
                        
                        if (inclValues) {
                            if (valuePtr != NULL) {
                                printf("          ");
                                print_value(pkg, value);
                            } else if (bagPtr != NULL) {
                                const int N = dtohl(bagPtr->count);
                                const uint8_t* baseMapPtr = (const uint8_t*)ent;
                                size_t mapOffset = esize;
                                const ResTable_map* mapPtr = (ResTable_map*)(baseMapPtr+mapOffset);
                                printf("          Parent=0x%08x, Count=%d\n",
                                    dtohl(bagPtr->parent.ident), N);
                                for (int i=0; i<N && mapOffset < (typeSize-sizeof(ResTable_map)); i++) {
                                    printf("          #%i (Key=0x%08x): ",
                                        i, dtohl(mapPtr->name.ident));
                                    value.copyFrom_dtoh(mapPtr->value);
                                    print_value(pkg, value);
                                    const size_t size = dtohs(mapPtr->value.size);
                                    mapOffset += size + sizeof(*mapPtr)-sizeof(mapPtr->value);
                                    mapPtr = (ResTable_map*)(baseMapPtr+mapOffset);
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

#endif // HAVE_ANDROID_OS

}   // namespace android
