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

#include <ctype.h>
#include <memory.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include <algorithm>
#include <limits>
#include <memory>
#include <type_traits>

#include <androidfw/ByteBucketArray.h>
#include <androidfw/ResourceTypes.h>
#include <androidfw/TypeWrappers.h>
#include <utils/Atomic.h>
#include <utils/ByteOrder.h>
#include <utils/Debug.h>
#include <utils/Log.h>
#include <utils/String16.h>
#include <utils/String8.h>

#ifdef __ANDROID__
#include <binder/TextOutput.h>
#endif

#ifndef INT32_MAX
#define INT32_MAX ((int32_t)(2147483647))
#endif

namespace android {

#if defined(_WIN32)
#undef  nhtol
#undef  htonl
#define ntohl(x)    ( ((x) << 24) | (((x) >> 24) & 255) | (((x) << 8) & 0xff0000) | (((x) >> 8) & 0xff00) )
#define htonl(x)    ntohl(x)
#define ntohs(x)    ( (((x) << 8) & 0xff00) | (((x) >> 8) & 255) )
#define htons(x)    ntohs(x)
#endif

#define IDMAP_MAGIC             0x504D4449
#define IDMAP_CURRENT_VERSION   0x00000001

#define APP_PACKAGE_ID      0x7f
#define SYS_PACKAGE_ID      0x01

static const bool kDebugStringPoolNoisy = false;
static const bool kDebugXMLNoisy = false;
static const bool kDebugTableNoisy = false;
static const bool kDebugTableGetEntry = false;
static const bool kDebugTableSuperNoisy = false;
static const bool kDebugLoadTableNoisy = false;
static const bool kDebugLoadTableSuperNoisy = false;
static const bool kDebugTableTheme = false;
static const bool kDebugResXMLTree = false;
static const bool kDebugLibNoisy = false;

// TODO: This code uses 0xFFFFFFFF converted to bag_set* as a sentinel value. This is bad practice.

// Standard C isspace() is only required to look at the low byte of its input, so
// produces incorrect results for UTF-16 characters.  For safety's sake, assume that
// any high-byte UTF-16 code point is not whitespace.
inline int isspace16(char16_t c) {
    return (c < 0x0080 && isspace(c));
}

template<typename T>
inline static T max(T a, T b) {
    return a > b ? a : b;
}

// range checked; guaranteed to NUL-terminate within the stated number of available slots
// NOTE: if this truncates the dst string due to running out of space, no attempt is
// made to avoid splitting surrogate pairs.
static void strcpy16_dtoh(char16_t* dst, const uint16_t* src, size_t avail)
{
    char16_t* last = dst + avail - 1;
    while (*src && (dst < last)) {
        char16_t s = dtohs(static_cast<char16_t>(*src));
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
                if ((size_t)size <= (size_t)(dataEnd-((const uint8_t*)chunk))) {
                    return NO_ERROR;
                }
                ALOGW("%s data size 0x%x extends beyond resource end %p.",
                     name, size, (void*)(dataEnd-((const uint8_t*)chunk)));
                return BAD_TYPE;
            }
            ALOGW("%s size 0x%x or headerSize 0x%x is not on an integer boundary.",
                 name, (int)size, (int)headerSize);
            return BAD_TYPE;
        }
        ALOGW("%s size 0x%x is smaller than header size 0x%x.",
             name, size, headerSize);
        return BAD_TYPE;
    }
    ALOGW("%s header size 0x%04x is too small.",
         name, headerSize);
    return BAD_TYPE;
}

static void fill9patchOffsets(Res_png_9patch* patch) {
    patch->xDivsOffset = sizeof(Res_png_9patch);
    patch->yDivsOffset = patch->xDivsOffset + (patch->numXDivs * sizeof(int32_t));
    patch->colorsOffset = patch->yDivsOffset + (patch->numYDivs * sizeof(int32_t));
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
    int32_t* xDivs = getXDivs();
    for (int i = 0; i < numXDivs; i++) {
        xDivs[i] = htonl(xDivs[i]);
    }
    int32_t* yDivs = getYDivs();
    for (int i = 0; i < numYDivs; i++) {
        yDivs[i] = htonl(yDivs[i]);
    }
    paddingLeft = htonl(paddingLeft);
    paddingRight = htonl(paddingRight);
    paddingTop = htonl(paddingTop);
    paddingBottom = htonl(paddingBottom);
    uint32_t* colors = getColors();
    for (int i=0; i<numColors; i++) {
        colors[i] = htonl(colors[i]);
    }
}

void Res_png_9patch::fileToDevice()
{
    int32_t* xDivs = getXDivs();
    for (int i = 0; i < numXDivs; i++) {
        xDivs[i] = ntohl(xDivs[i]);
    }
    int32_t* yDivs = getYDivs();
    for (int i = 0; i < numYDivs; i++) {
        yDivs[i] = ntohl(yDivs[i]);
    }
    paddingLeft = ntohl(paddingLeft);
    paddingRight = ntohl(paddingRight);
    paddingTop = ntohl(paddingTop);
    paddingBottom = ntohl(paddingBottom);
    uint32_t* colors = getColors();
    for (int i=0; i<numColors; i++) {
        colors[i] = ntohl(colors[i]);
    }
}

size_t Res_png_9patch::serializedSize() const
{
    // The size of this struct is 32 bytes on the 32-bit target system
    // 4 * int8_t
    // 4 * int32_t
    // 3 * uint32_t
    return 32
            + numXDivs * sizeof(int32_t)
            + numYDivs * sizeof(int32_t)
            + numColors * sizeof(uint32_t);
}

void* Res_png_9patch::serialize(const Res_png_9patch& patch, const int32_t* xDivs,
                                const int32_t* yDivs, const uint32_t* colors)
{
    // Use calloc since we're going to leave a few holes in the data
    // and want this to run cleanly under valgrind
    void* newData = calloc(1, patch.serializedSize());
    serialize(patch, xDivs, yDivs, colors, newData);
    return newData;
}

void Res_png_9patch::serialize(const Res_png_9patch& patch, const int32_t* xDivs,
                               const int32_t* yDivs, const uint32_t* colors, void* outData)
{
    uint8_t* data = (uint8_t*) outData;
    memcpy(data, &patch.wasDeserialized, 4);     // copy  wasDeserialized, numXDivs, numYDivs, numColors
    memcpy(data + 12, &patch.paddingLeft, 16);   // copy paddingXXXX
    data += 32;

    memcpy(data, xDivs, patch.numXDivs * sizeof(int32_t));
    data +=  patch.numXDivs * sizeof(int32_t);
    memcpy(data, yDivs, patch.numYDivs * sizeof(int32_t));
    data +=  patch.numYDivs * sizeof(int32_t);
    memcpy(data, colors, patch.numColors * sizeof(uint32_t));

    fill9patchOffsets(reinterpret_cast<Res_png_9patch*>(outData));
}

static bool assertIdmapHeader(const void* idmap, size_t size) {
    if (reinterpret_cast<uintptr_t>(idmap) & 0x03) {
        ALOGE("idmap: header is not word aligned");
        return false;
    }

    if (size < ResTable::IDMAP_HEADER_SIZE_BYTES) {
        ALOGW("idmap: header too small (%d bytes)", (uint32_t) size);
        return false;
    }

    const uint32_t magic = htodl(*reinterpret_cast<const uint32_t*>(idmap));
    if (magic != IDMAP_MAGIC) {
        ALOGW("idmap: no magic found in header (is 0x%08x, expected 0x%08x)",
             magic, IDMAP_MAGIC);
        return false;
    }

    const uint32_t version = htodl(*(reinterpret_cast<const uint32_t*>(idmap) + 1));
    if (version != IDMAP_CURRENT_VERSION) {
        // We are strict about versions because files with this format are
        // auto-generated and don't need backwards compatibility.
        ALOGW("idmap: version mismatch in header (is 0x%08x, expected 0x%08x)",
                version, IDMAP_CURRENT_VERSION);
        return false;
    }
    return true;
}

class IdmapEntries {
public:
    IdmapEntries() : mData(NULL) {}

    bool hasEntries() const {
        if (mData == NULL) {
            return false;
        }

        return (dtohs(*mData) > 0);
    }

    size_t byteSize() const {
        if (mData == NULL) {
            return 0;
        }
        uint16_t entryCount = dtohs(mData[2]);
        return (sizeof(uint16_t) * 4) + (sizeof(uint32_t) * static_cast<size_t>(entryCount));
    }

    uint8_t targetTypeId() const {
        if (mData == NULL) {
            return 0;
        }
        return dtohs(mData[0]);
    }

    uint8_t overlayTypeId() const {
        if (mData == NULL) {
            return 0;
        }
        return dtohs(mData[1]);
    }

    status_t setTo(const void* entryHeader, size_t size) {
        if (reinterpret_cast<uintptr_t>(entryHeader) & 0x03) {
            ALOGE("idmap: entry header is not word aligned");
            return UNKNOWN_ERROR;
        }

        if (size < sizeof(uint16_t) * 4) {
            ALOGE("idmap: entry header is too small (%u bytes)", (uint32_t) size);
            return UNKNOWN_ERROR;
        }

        const uint16_t* header = reinterpret_cast<const uint16_t*>(entryHeader);
        const uint16_t targetTypeId = dtohs(header[0]);
        const uint16_t overlayTypeId = dtohs(header[1]);
        if (targetTypeId == 0 || overlayTypeId == 0 || targetTypeId > 255 || overlayTypeId > 255) {
            ALOGE("idmap: invalid type map (%u -> %u)", targetTypeId, overlayTypeId);
            return UNKNOWN_ERROR;
        }

        uint16_t entryCount = dtohs(header[2]);
        if (size < sizeof(uint32_t) * (entryCount + 2)) {
            ALOGE("idmap: too small (%u bytes) for the number of entries (%u)",
                    (uint32_t) size, (uint32_t) entryCount);
            return UNKNOWN_ERROR;
        }
        mData = header;
        return NO_ERROR;
    }

    status_t lookup(uint16_t entryId, uint16_t* outEntryId) const {
        uint16_t entryCount = dtohs(mData[2]);
        uint16_t offset = dtohs(mData[3]);

        if (entryId < offset) {
            // The entry is not present in this idmap
            return BAD_INDEX;
        }

        entryId -= offset;

        if (entryId >= entryCount) {
            // The entry is not present in this idmap
            return BAD_INDEX;
        }

        // It is safe to access the type here without checking the size because
        // we have checked this when it was first loaded.
        const uint32_t* entries = reinterpret_cast<const uint32_t*>(mData) + 2;
        uint32_t mappedEntry = dtohl(entries[entryId]);
        if (mappedEntry == 0xffffffff) {
            // This entry is not present in this idmap
            return BAD_INDEX;
        }
        *outEntryId = static_cast<uint16_t>(mappedEntry);
        return NO_ERROR;
    }

private:
    const uint16_t* mData;
};

status_t parseIdmap(const void* idmap, size_t size, uint8_t* outPackageId, KeyedVector<uint8_t, IdmapEntries>* outMap) {
    if (!assertIdmapHeader(idmap, size)) {
        return UNKNOWN_ERROR;
    }

    size -= ResTable::IDMAP_HEADER_SIZE_BYTES;
    if (size < sizeof(uint16_t) * 2) {
        ALOGE("idmap: too small to contain any mapping");
        return UNKNOWN_ERROR;
    }

    const uint16_t* data = reinterpret_cast<const uint16_t*>(
            reinterpret_cast<const uint8_t*>(idmap) + ResTable::IDMAP_HEADER_SIZE_BYTES);

    uint16_t targetPackageId = dtohs(*(data++));
    if (targetPackageId == 0 || targetPackageId > 255) {
        ALOGE("idmap: target package ID is invalid (%02x)", targetPackageId);
        return UNKNOWN_ERROR;
    }

    uint16_t mapCount = dtohs(*(data++));
    if (mapCount == 0) {
        ALOGE("idmap: no mappings");
        return UNKNOWN_ERROR;
    }

    if (mapCount > 255) {
        ALOGW("idmap: too many mappings. Only 255 are possible but %u are present", (uint32_t) mapCount);
    }

    while (size > sizeof(uint16_t) * 4) {
        IdmapEntries entries;
        status_t err = entries.setTo(data, size);
        if (err != NO_ERROR) {
            return err;
        }

        ssize_t index = outMap->add(entries.overlayTypeId(), entries);
        if (index < 0) {
            return NO_MEMORY;
        }

        data += entries.byteSize() / sizeof(uint16_t);
        size -= entries.byteSize();
    }

    if (outPackageId != NULL) {
        *outPackageId = static_cast<uint8_t>(targetPackageId);
    }
    return NO_ERROR;
}

Res_png_9patch* Res_png_9patch::deserialize(void* inData)
{

    Res_png_9patch* patch = reinterpret_cast<Res_png_9patch*>(inData);
    patch->wasDeserialized = true;
    fill9patchOffsets(patch);

    return patch;
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

void ResStringPool::setToEmpty()
{
    uninit();

    mOwnedData = calloc(1, sizeof(ResStringPool_header));
    ResStringPool_header* header = (ResStringPool_header*) mOwnedData;
    mSize = 0;
    mEntries = NULL;
    mStrings = NULL;
    mStringPoolSize = 0;
    mEntryStyles = NULL;
    mStyles = NULL;
    mStylePoolSize = 0;
    mHeader = (const ResStringPool_header*) header;
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
        ALOGW("Bad string block: header size %d or total size %d is larger than data size %d\n",
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
            ALOGW("Bad string block: entry of %d items extends past data size %d\n",
                    (int)(mHeader->header.headerSize+(mHeader->stringCount*sizeof(uint32_t))),
                    (int)size);
            return (mError=BAD_TYPE);
        }

        size_t charSize;
        if (mHeader->flags&ResStringPool_header::UTF8_FLAG) {
            charSize = sizeof(uint8_t);
        } else {
            charSize = sizeof(uint16_t);
        }

        // There should be at least space for the smallest string
        // (2 bytes length, null terminator).
        if (mHeader->stringsStart >= (mSize - sizeof(uint16_t))) {
            ALOGW("Bad string block: string pool starts at %d, after total size %d\n",
                    (int)mHeader->stringsStart, (int)mHeader->header.size);
            return (mError=BAD_TYPE);
        }

        mStrings = (const void*)
            (((const uint8_t*)data) + mHeader->stringsStart);

        if (mHeader->styleCount == 0) {
            mStringPoolSize = (mSize - mHeader->stringsStart) / charSize;
        } else {
            // check invariant: styles starts before end of data
            if (mHeader->stylesStart >= (mSize - sizeof(uint16_t))) {
                ALOGW("Bad style block: style block starts at %d past data size of %d\n",
                    (int)mHeader->stylesStart, (int)mHeader->header.size);
                return (mError=BAD_TYPE);
            }
            // check invariant: styles follow the strings
            if (mHeader->stylesStart <= mHeader->stringsStart) {
                ALOGW("Bad style block: style block starts at %d, before strings at %d\n",
                    (int)mHeader->stylesStart, (int)mHeader->stringsStart);
                return (mError=BAD_TYPE);
            }
            mStringPoolSize =
                (mHeader->stylesStart-mHeader->stringsStart)/charSize;
        }

        // check invariant: stringCount > 0 requires a string pool to exist
        if (mStringPoolSize == 0) {
            ALOGW("Bad string block: stringCount is %d but pool size is 0\n", (int)mHeader->stringCount);
            return (mError=BAD_TYPE);
        }

        if (notDeviceEndian) {
            size_t i;
            uint32_t* e = const_cast<uint32_t*>(mEntries);
            for (i=0; i<mHeader->stringCount; i++) {
                e[i] = dtohl(mEntries[i]);
            }
            if (!(mHeader->flags&ResStringPool_header::UTF8_FLAG)) {
                const uint16_t* strings = (const uint16_t*)mStrings;
                uint16_t* s = const_cast<uint16_t*>(strings);
                for (i=0; i<mStringPoolSize; i++) {
                    s[i] = dtohs(strings[i]);
                }
            }
        }

        if ((mHeader->flags&ResStringPool_header::UTF8_FLAG &&
                ((uint8_t*)mStrings)[mStringPoolSize-1] != 0) ||
                (!(mHeader->flags&ResStringPool_header::UTF8_FLAG) &&
                ((uint16_t*)mStrings)[mStringPoolSize-1] != 0)) {
            ALOGW("Bad string block: last string is not 0-terminated\n");
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
            ALOGW("Bad string block: integer overflow finding styles\n");
            return (mError=BAD_TYPE);
        }

        if (((const uint8_t*)mEntryStyles-(const uint8_t*)mHeader) > (int)size) {
            ALOGW("Bad string block: entry of %d styles extends past data size %d\n",
                    (int)((const uint8_t*)mEntryStyles-(const uint8_t*)mHeader),
                    (int)size);
            return (mError=BAD_TYPE);
        }
        mStyles = (const uint32_t*)
            (((const uint8_t*)data)+mHeader->stylesStart);
        if (mHeader->stylesStart >= mHeader->header.size) {
            ALOGW("Bad string block: style pool starts %d, after total size %d\n",
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
            ALOGW("Bad string block: last style is not 0xFFFFFFFF-terminated\n");
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
    if (mOwnedData) {
        free(mOwnedData);
        mOwnedData = NULL;
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
decodeLength(const uint16_t** str)
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

const char16_t* ResStringPool::stringAt(size_t idx, size_t* u16len) const
{
    if (mError == NO_ERROR && idx < mHeader->stringCount) {
        const bool isUTF8 = (mHeader->flags&ResStringPool_header::UTF8_FLAG) != 0;
        const uint32_t off = mEntries[idx]/(isUTF8?sizeof(uint8_t):sizeof(uint16_t));
        if (off < (mStringPoolSize-1)) {
            if (!isUTF8) {
                const uint16_t* strings = (uint16_t*)mStrings;
                const uint16_t* str = strings+off;

                *u16len = decodeLength(&str);
                if ((uint32_t)(str+*u16len-strings) < mStringPoolSize) {
                    // Reject malformed (non null-terminated) strings
                    if (str[*u16len] != 0x0000) {
                        ALOGW("Bad string block: string #%d is not null-terminated",
                              (int)idx);
                        return NULL;
                    }
                    return reinterpret_cast<const char16_t*>(str);
                } else {
                    ALOGW("Bad string block: string #%d extends to %d, past end at %d\n",
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

                    if (mCache == NULL) {
#ifndef __ANDROID__
                        if (kDebugStringPoolNoisy) {
                            ALOGI("CREATING STRING CACHE OF %zu bytes",
                                    mHeader->stringCount*sizeof(char16_t**));
                        }
#else
                        // We do not want to be in this case when actually running Android.
                        ALOGW("CREATING STRING CACHE OF %zu bytes",
                                static_cast<size_t>(mHeader->stringCount*sizeof(char16_t**)));
#endif
                        mCache = (char16_t**)calloc(mHeader->stringCount, sizeof(char16_t**));
                        if (mCache == NULL) {
                            ALOGW("No memory trying to allocate decode cache table of %d bytes\n",
                                    (int)(mHeader->stringCount*sizeof(char16_t**)));
                            return NULL;
                        }
                    }

                    if (mCache[idx] != NULL) {
                        return mCache[idx];
                    }

                    ssize_t actualLen = utf8_to_utf16_length(u8str, u8len);
                    if (actualLen < 0 || (size_t)actualLen != *u16len) {
                        ALOGW("Bad string block: string #%lld decoded length is not correct "
                                "%lld vs %llu\n",
                                (long long)idx, (long long)actualLen, (long long)*u16len);
                        return NULL;
                    }

                    // Reject malformed (non null-terminated) strings
                    if (u8str[u8len] != 0x00) {
                        ALOGW("Bad string block: string #%d is not null-terminated",
                              (int)idx);
                        return NULL;
                    }

                    char16_t *u16str = (char16_t *)calloc(*u16len+1, sizeof(char16_t));
                    if (!u16str) {
                        ALOGW("No memory when trying to allocate decode cache for string #%d\n",
                                (int)idx);
                        return NULL;
                    }

                    if (kDebugStringPoolNoisy) {
                        ALOGI("Caching UTF8 string: %s", u8str);
                    }
                    utf8_to_utf16(u8str, u8len, u16str);
                    mCache[idx] = u16str;
                    return u16str;
                } else {
                    ALOGW("Bad string block: string #%lld extends to %lld, past end at %lld\n",
                            (long long)idx, (long long)(u8str+u8len-strings),
                            (long long)mStringPoolSize);
                }
            }
        } else {
            ALOGW("Bad string block: string #%d entry is at %d, past end at %d\n",
                    (int)idx, (int)(off*sizeof(uint16_t)),
                    (int)(mStringPoolSize*sizeof(uint16_t)));
        }
    }
    return NULL;
}

const char* ResStringPool::string8At(size_t idx, size_t* outLen) const
{
    if (mError == NO_ERROR && idx < mHeader->stringCount) {
        if ((mHeader->flags&ResStringPool_header::UTF8_FLAG) == 0) {
            return NULL;
        }
        const uint32_t off = mEntries[idx]/sizeof(char);
        if (off < (mStringPoolSize-1)) {
            const uint8_t* strings = (uint8_t*)mStrings;
            const uint8_t* str = strings+off;
            *outLen = decodeLength(&str);
            size_t encLen = decodeLength(&str);
            if ((uint32_t)(str+encLen-strings) < mStringPoolSize) {
                return (const char*)str;
            } else {
                ALOGW("Bad string block: string #%d extends to %d, past end at %d\n",
                        (int)idx, (int)(str+encLen-strings), (int)mStringPoolSize);
            }
        } else {
            ALOGW("Bad string block: string #%d entry is at %d, past end at %d\n",
                    (int)idx, (int)(off*sizeof(uint16_t)),
                    (int)(mStringPoolSize*sizeof(uint16_t)));
        }
    }
    return NULL;
}

const String8 ResStringPool::string8ObjectAt(size_t idx) const
{
    size_t len;
    const char *str = string8At(idx, &len);
    if (str != NULL) {
        return String8(str, len);
    }

    const char16_t *str16 = stringAt(idx, &len);
    if (str16 != NULL) {
        return String8(str16, len);
    }
    return String8();
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
            ALOGW("Bad string block: style #%d entry is at %d, past end at %d\n",
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

    if ((mHeader->flags&ResStringPool_header::UTF8_FLAG) != 0) {
        if (kDebugStringPoolNoisy) {
            ALOGI("indexOfString UTF-8: %s", String8(str, strLen).string());
        }

        // The string pool contains UTF 8 strings; we don't want to cause
        // temporary UTF-16 strings to be created as we search.
        if (mHeader->flags&ResStringPool_header::SORTED_FLAG) {
            // Do a binary search for the string...  this is a little tricky,
            // because the strings are sorted with strzcmp16().  So to match
            // the ordering, we need to convert strings in the pool to UTF-16.
            // But we don't want to hit the cache, so instead we will have a
            // local temporary allocation for the conversions.
            char16_t* convBuffer = (char16_t*)malloc(strLen+4);
            ssize_t l = 0;
            ssize_t h = mHeader->stringCount-1;

            ssize_t mid;
            while (l <= h) {
                mid = l + (h - l)/2;
                const uint8_t* s = (const uint8_t*)string8At(mid, &len);
                int c;
                if (s != NULL) {
                    char16_t* end = utf8_to_utf16_n(s, len, convBuffer, strLen+3);
                    *end = 0;
                    c = strzcmp16(convBuffer, end-convBuffer, str, strLen);
                } else {
                    c = -1;
                }
                if (kDebugStringPoolNoisy) {
                    ALOGI("Looking at %s, cmp=%d, l/mid/h=%d/%d/%d\n",
                            (const char*)s, c, (int)l, (int)mid, (int)h);
                }
                if (c == 0) {
                    if (kDebugStringPoolNoisy) {
                        ALOGI("MATCH!");
                    }
                    free(convBuffer);
                    return mid;
                } else if (c < 0) {
                    l = mid + 1;
                } else {
                    h = mid - 1;
                }
            }
            free(convBuffer);
        } else {
            // It is unusual to get the ID from an unsorted string block...
            // most often this happens because we want to get IDs for style
            // span tags; since those always appear at the end of the string
            // block, start searching at the back.
            String8 str8(str, strLen);
            const size_t str8Len = str8.size();
            for (int i=mHeader->stringCount-1; i>=0; i--) {
                const char* s = string8At(i, &len);
                if (kDebugStringPoolNoisy) {
                    ALOGI("Looking at %s, i=%d\n", String8(s).string(), i);
                }
                if (s && str8Len == len && memcmp(s, str8.string(), str8Len) == 0) {
                    if (kDebugStringPoolNoisy) {
                        ALOGI("MATCH!");
                    }
                    return i;
                }
            }
        }

    } else {
        if (kDebugStringPoolNoisy) {
            ALOGI("indexOfString UTF-16: %s", String8(str, strLen).string());
        }

        if (mHeader->flags&ResStringPool_header::SORTED_FLAG) {
            // Do a binary search for the string...
            ssize_t l = 0;
            ssize_t h = mHeader->stringCount-1;

            ssize_t mid;
            while (l <= h) {
                mid = l + (h - l)/2;
                const char16_t* s = stringAt(mid, &len);
                int c = s ? strzcmp16(s, len, str, strLen) : -1;
                if (kDebugStringPoolNoisy) {
                    ALOGI("Looking at %s, cmp=%d, l/mid/h=%d/%d/%d\n",
                            String8(s).string(), c, (int)l, (int)mid, (int)h);
                }
                if (c == 0) {
                    if (kDebugStringPoolNoisy) {
                        ALOGI("MATCH!");
                    }
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
                if (kDebugStringPoolNoisy) {
                    ALOGI("Looking at %s, i=%d\n", String8(s).string(), i);
                }
                if (s && strLen == len && strzcmp16(s, len, str, strLen) == 0) {
                    if (kDebugStringPoolNoisy) {
                        ALOGI("MATCH!");
                    }
                    return i;
                }
            }
        }
    }

    return NAME_NOT_FOUND;
}

size_t ResStringPool::size() const
{
    return (mError == NO_ERROR) ? mHeader->stringCount : 0;
}

size_t ResStringPool::styleCount() const
{
    return (mError == NO_ERROR) ? mHeader->styleCount : 0;
}

size_t ResStringPool::bytes() const
{
    return (mError == NO_ERROR) ? mHeader->header.size : 0;
}

bool ResStringPool::isSorted() const
{
    return (mHeader->flags&ResStringPool_header::SORTED_FLAG)!=0;
}

bool ResStringPool::isUTF8() const
{
    return (mHeader->flags&ResStringPool_header::UTF8_FLAG)!=0;
}

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

const char16_t* ResXMLParser::getComment(size_t* outLen) const
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

const char16_t* ResXMLParser::getText(size_t* outLen) const
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

const char16_t* ResXMLParser::getNamespacePrefix(size_t* outLen) const
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

const char16_t* ResXMLParser::getNamespaceUri(size_t* outLen) const
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

const char16_t* ResXMLParser::getElementNamespace(size_t* outLen) const
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

const char16_t* ResXMLParser::getElementName(size_t* outLen) const
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

const char16_t* ResXMLParser::getAttributeNamespace(size_t idx, size_t* outLen) const
{
    int32_t id = getAttributeNamespaceID(idx);
    //printf("attribute namespace=%d  idx=%d  event=%p\n", id, idx, mEventCode);
    if (kDebugXMLNoisy) {
        printf("getAttributeNamespace 0x%zx=0x%x\n", idx, id);
    }
    return id >= 0 ? mTree.mStrings.stringAt(id, outLen) : NULL;
}

const char* ResXMLParser::getAttributeNamespace8(size_t idx, size_t* outLen) const
{
    int32_t id = getAttributeNamespaceID(idx);
    //printf("attribute namespace=%d  idx=%d  event=%p\n", id, idx, mEventCode);
    if (kDebugXMLNoisy) {
        printf("getAttributeNamespace 0x%zx=0x%x\n", idx, id);
    }
    return id >= 0 ? mTree.mStrings.string8At(id, outLen) : NULL;
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

const char16_t* ResXMLParser::getAttributeName(size_t idx, size_t* outLen) const
{
    int32_t id = getAttributeNameID(idx);
    //printf("attribute name=%d  idx=%d  event=%p\n", id, idx, mEventCode);
    if (kDebugXMLNoisy) {
        printf("getAttributeName 0x%zx=0x%x\n", idx, id);
    }
    return id >= 0 ? mTree.mStrings.stringAt(id, outLen) : NULL;
}

const char* ResXMLParser::getAttributeName8(size_t idx, size_t* outLen) const
{
    int32_t id = getAttributeNameID(idx);
    //printf("attribute name=%d  idx=%d  event=%p\n", id, idx, mEventCode);
    if (kDebugXMLNoisy) {
        printf("getAttributeName 0x%zx=0x%x\n", idx, id);
    }
    return id >= 0 ? mTree.mStrings.string8At(id, outLen) : NULL;
}

uint32_t ResXMLParser::getAttributeNameResID(size_t idx) const
{
    int32_t id = getAttributeNameID(idx);
    if (id >= 0 && (size_t)id < mTree.mNumResIds) {
        uint32_t resId = dtohl(mTree.mResIds[id]);
        if (mTree.mDynamicRefTable != NULL) {
            mTree.mDynamicRefTable->lookupResourceId(&resId);
        }
        return resId;
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

const char16_t* ResXMLParser::getAttributeStringValue(size_t idx, size_t* outLen) const
{
    int32_t id = getAttributeValueStringID(idx);
    if (kDebugXMLNoisy) {
        printf("getAttributeValue 0x%zx=0x%x\n", idx, id);
    }
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
            uint8_t type = attr->typedValue.dataType;
            if (type != Res_value::TYPE_DYNAMIC_REFERENCE) {
                return type;
            }

            // This is a dynamic reference. We adjust those references
            // to regular references at this level, so lie to the caller.
            return Res_value::TYPE_REFERENCE;
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
            if (attr->typedValue.dataType != Res_value::TYPE_DYNAMIC_REFERENCE ||
                    mTree.mDynamicRefTable == NULL) {
                return dtohl(attr->typedValue.data);
            }

            uint32_t data = dtohl(attr->typedValue.data);
            if (mTree.mDynamicRefTable->lookupResourceId(&data) == NO_ERROR) {
                return data;
            }
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
            if (mTree.mDynamicRefTable != NULL &&
                    mTree.mDynamicRefTable->lookupResourceValue(outValue) != NO_ERROR) {
                return BAD_TYPE;
            }
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
        if (attr == NULL) {
            return NAME_NOT_FOUND;
        }
        const size_t N = getAttributeCount();
        if (mTree.mStrings.isUTF8()) {
            String8 ns8, attr8;
            if (ns != NULL) {
                ns8 = String8(ns, nsLen);
            }
            attr8 = String8(attr, attrLen);
            if (kDebugStringPoolNoisy) {
                ALOGI("indexOfAttribute UTF8 %s (%zu) / %s (%zu)", ns8.string(), nsLen,
                        attr8.string(), attrLen);
            }
            for (size_t i=0; i<N; i++) {
                size_t curNsLen = 0, curAttrLen = 0;
                const char* curNs = getAttributeNamespace8(i, &curNsLen);
                const char* curAttr = getAttributeName8(i, &curAttrLen);
                if (kDebugStringPoolNoisy) {
                    ALOGI("  curNs=%s (%zu), curAttr=%s (%zu)", curNs, curNsLen, curAttr, curAttrLen);
                }
                if (curAttr != NULL && curNsLen == nsLen && curAttrLen == attrLen
                        && memcmp(attr8.string(), curAttr, attrLen) == 0) {
                    if (ns == NULL) {
                        if (curNs == NULL) {
                            if (kDebugStringPoolNoisy) {
                                ALOGI("  FOUND!");
                            }
                            return i;
                        }
                    } else if (curNs != NULL) {
                        //printf(" --> ns=%s, curNs=%s\n",
                        //       String8(ns).string(), String8(curNs).string());
                        if (memcmp(ns8.string(), curNs, nsLen) == 0) {
                            if (kDebugStringPoolNoisy) {
                                ALOGI("  FOUND!");
                            }
                            return i;
                        }
                    }
                }
            }
        } else {
            if (kDebugStringPoolNoisy) {
                ALOGI("indexOfAttribute UTF16 %s (%zu) / %s (%zu)",
                        String8(ns, nsLen).string(), nsLen,
                        String8(attr, attrLen).string(), attrLen);
            }
            for (size_t i=0; i<N; i++) {
                size_t curNsLen = 0, curAttrLen = 0;
                const char16_t* curNs = getAttributeNamespace(i, &curNsLen);
                const char16_t* curAttr = getAttributeName(i, &curAttrLen);
                if (kDebugStringPoolNoisy) {
                    ALOGI("  curNs=%s (%zu), curAttr=%s (%zu)",
                            String8(curNs, curNsLen).string(), curNsLen,
                            String8(curAttr, curAttrLen).string(), curAttrLen);
                }
                if (curAttr != NULL && curNsLen == nsLen && curAttrLen == attrLen
                        && (memcmp(attr, curAttr, attrLen*sizeof(char16_t)) == 0)) {
                    if (ns == NULL) {
                        if (curNs == NULL) {
                            if (kDebugStringPoolNoisy) {
                                ALOGI("  FOUND!");
                            }
                            return i;
                        }
                    } else if (curNs != NULL) {
                        //printf(" --> ns=%s, curNs=%s\n",
                        //       String8(ns).string(), String8(curNs).string());
                        if (memcmp(ns, curNs, nsLen*sizeof(char16_t)) == 0) {
                            if (kDebugStringPoolNoisy) {
                                ALOGI("  FOUND!");
                            }
                            return i;
                        }
                    }
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
        if (kDebugXMLNoisy) {
            ALOGI("Next node: prev=%p, next=%p\n", mCurNode, next);
        }

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
                ALOGW("Unknown XML block: header type %d in node at %d\n",
                     (int)dtohs(next->header.type),
                     (int)(((const uint8_t*)next)-((const uint8_t*)mTree.mHeader)));
                continue;
        }

        if ((totalSize-headerSize) < minExtSize) {
            ALOGW("Bad XML block: header type 0x%x in node at 0x%x has size %d, need %d\n",
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

ResXMLTree::ResXMLTree(const DynamicRefTable* dynamicRefTable)
    : ResXMLParser(*this)
    , mDynamicRefTable(dynamicRefTable)
    , mError(NO_INIT), mOwnedData(NULL)
{
    if (kDebugResXMLTree) {
        ALOGI("Creating ResXMLTree %p #%d\n", this, android_atomic_inc(&gCount)+1);
    }
    restart();
}

ResXMLTree::ResXMLTree()
    : ResXMLParser(*this)
    , mDynamicRefTable(NULL)
    , mError(NO_INIT), mOwnedData(NULL)
{
    if (kDebugResXMLTree) {
        ALOGI("Creating ResXMLTree %p #%d\n", this, android_atomic_inc(&gCount)+1);
    }
    restart();
}

ResXMLTree::~ResXMLTree()
{
    if (kDebugResXMLTree) {
        ALOGI("Destroying ResXMLTree in %p #%d\n", this, android_atomic_dec(&gCount)-1);
    }
    uninit();
}

status_t ResXMLTree::setTo(const void* data, size_t size, bool copyData)
{
    uninit();
    mEventCode = START_DOCUMENT;

    if (!data || !size) {
        return (mError=BAD_TYPE);
    }

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
        ALOGW("Bad XML block: header size %d or total size %d is larger than data size %d\n",
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
        if (kDebugXMLNoisy) {
            printf("Scanning @ %p: type=0x%x, size=0x%zx\n",
                    (void*)(((uintptr_t)chunk)-((uintptr_t)mHeader)), type, size);
        }
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
            if (kDebugXMLNoisy) {
                printf("Skipping unknown chunk!\n");
            }
        }
        lastChunk = chunk;
        chunk = (const ResChunk_header*)
            (((const uint8_t*)chunk) + size);
    }

    if (mRootNode == NULL) {
        ALOGW("Bad XML block: no root element node found\n");
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
            ALOGW("Bad XML block: node attributes use 0x%x bytes, only have 0x%x bytes\n",
                    (unsigned int)(dtohs(attrExt->attributeStart)+attrSize),
                    (unsigned int)(size-headerSize));
        }
        else {
            ALOGW("Bad XML start block: node header size 0x%x, size 0x%x\n",
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
                ALOGW("Bad XML block: node attributes use 0x%x bytes, only have 0x%x bytes\n",
                        ((int)dtohs(node->attributeSize))*dtohs(node->attributeCount),
                        (int)(size-headerSize));
                return BAD_TYPE;
            }
            ALOGW("Bad XML block: node at 0x%x extends beyond data end 0x%x\n",
                    (int)(((const uint8_t*)node)-((const uint8_t*)mHeader)), (int)mSize);
            return BAD_TYPE;
        }
        ALOGW("Bad XML block: node at 0x%x header size 0x%x smaller than total size 0x%x\n",
                (int)(((const uint8_t*)node)-((const uint8_t*)mHeader)),
                (int)headerSize, (int)size);
        return BAD_TYPE;
    }
    ALOGW("Bad XML block: node at 0x%x header size 0x%x too small\n",
            (int)(((const uint8_t*)node)-((const uint8_t*)mHeader)),
            (int)headerSize);
    return BAD_TYPE;
#endif
}

// --------------------------------------------------------------------
// --------------------------------------------------------------------
// --------------------------------------------------------------------

void ResTable_config::copyFromDeviceNoSwap(const ResTable_config& o) {
    const size_t size = dtohl(o.size);
    if (size >= sizeof(ResTable_config)) {
        *this = o;
    } else {
        memcpy(this, &o, size);
        memset(((uint8_t*)this)+size, 0, sizeof(ResTable_config)-size);
    }
}

/* static */ size_t unpackLanguageOrRegion(const char in[2], const char base,
        char out[4]) {
  if (in[0] & 0x80) {
      // The high bit is "1", which means this is a packed three letter
      // language code.

      // The smallest 5 bits of the second char are the first alphabet.
      const uint8_t first = in[1] & 0x1f;
      // The last three bits of the second char and the first two bits
      // of the first char are the second alphabet.
      const uint8_t second = ((in[1] & 0xe0) >> 5) + ((in[0] & 0x03) << 3);
      // Bits 3 to 7 (inclusive) of the first char are the third alphabet.
      const uint8_t third = (in[0] & 0x7c) >> 2;

      out[0] = first + base;
      out[1] = second + base;
      out[2] = third + base;
      out[3] = 0;

      return 3;
  }

  if (in[0]) {
      memcpy(out, in, 2);
      memset(out + 2, 0, 2);
      return 2;
  }

  memset(out, 0, 4);
  return 0;
}

/* static */ void packLanguageOrRegion(const char* in, const char base,
        char out[2]) {
  if (in[2] == 0 || in[2] == '-') {
      out[0] = in[0];
      out[1] = in[1];
  } else {
      uint8_t first = (in[0] - base) & 0x007f;
      uint8_t second = (in[1] - base) & 0x007f;
      uint8_t third = (in[2] - base) & 0x007f;

      out[0] = (0x80 | (third << 2) | (second >> 3));
      out[1] = ((second << 5) | first);
  }
}


void ResTable_config::packLanguage(const char* language) {
    packLanguageOrRegion(language, 'a', this->language);
}

void ResTable_config::packRegion(const char* region) {
    packLanguageOrRegion(region, '0', this->country);
}

size_t ResTable_config::unpackLanguage(char language[4]) const {
    return unpackLanguageOrRegion(this->language, 'a', language);
}

size_t ResTable_config::unpackRegion(char region[4]) const {
    return unpackLanguageOrRegion(this->country, '0', region);
}


void ResTable_config::copyFromDtoH(const ResTable_config& o) {
    copyFromDeviceNoSwap(o);
    size = sizeof(ResTable_config);
    mcc = dtohs(mcc);
    mnc = dtohs(mnc);
    density = dtohs(density);
    screenWidth = dtohs(screenWidth);
    screenHeight = dtohs(screenHeight);
    sdkVersion = dtohs(sdkVersion);
    minorVersion = dtohs(minorVersion);
    smallestScreenWidthDp = dtohs(smallestScreenWidthDp);
    screenWidthDp = dtohs(screenWidthDp);
    screenHeightDp = dtohs(screenHeightDp);
}

void ResTable_config::swapHtoD() {
    size = htodl(size);
    mcc = htods(mcc);
    mnc = htods(mnc);
    density = htods(density);
    screenWidth = htods(screenWidth);
    screenHeight = htods(screenHeight);
    sdkVersion = htods(sdkVersion);
    minorVersion = htods(minorVersion);
    smallestScreenWidthDp = htods(smallestScreenWidthDp);
    screenWidthDp = htods(screenWidthDp);
    screenHeightDp = htods(screenHeightDp);
}

/* static */ inline int compareLocales(const ResTable_config &l, const ResTable_config &r) {
    if (l.locale != r.locale) {
        // NOTE: This is the old behaviour with respect to comparison orders.
        // The diff value here doesn't make much sense (given our bit packing scheme)
        // but it's stable, and that's all we need.
        return l.locale - r.locale;
    }

    // The language & region are equal, so compare the scripts and variants.
    const char emptyScript[sizeof(l.localeScript)] = {'\0', '\0', '\0', '\0'};
    const char *lScript = l.localeScriptWasComputed ? emptyScript : l.localeScript;
    const char *rScript = r.localeScriptWasComputed ? emptyScript : r.localeScript;
    int script = memcmp(lScript, rScript, sizeof(l.localeScript));
    if (script) {
        return script;
    }

    // The language, region and script are equal, so compare variants.
    //
    // This should happen very infrequently (if at all.)
    return memcmp(l.localeVariant, r.localeVariant, sizeof(l.localeVariant));
}

int ResTable_config::compare(const ResTable_config& o) const {
    int32_t diff = (int32_t)(imsi - o.imsi);
    if (diff != 0) return diff;
    diff = compareLocales(*this, o);
    if (diff != 0) return diff;
    diff = (int32_t)(screenType - o.screenType);
    if (diff != 0) return diff;
    diff = (int32_t)(input - o.input);
    if (diff != 0) return diff;
    diff = (int32_t)(screenSize - o.screenSize);
    if (diff != 0) return diff;
    diff = (int32_t)(version - o.version);
    if (diff != 0) return diff;
    diff = (int32_t)(screenLayout - o.screenLayout);
    if (diff != 0) return diff;
    diff = (int32_t)(screenLayout2 - o.screenLayout2);
    if (diff != 0) return diff;
    diff = (int32_t)(uiMode - o.uiMode);
    if (diff != 0) return diff;
    diff = (int32_t)(smallestScreenWidthDp - o.smallestScreenWidthDp);
    if (diff != 0) return diff;
    diff = (int32_t)(screenSizeDp - o.screenSizeDp);
    return (int)diff;
}

int ResTable_config::compareLogical(const ResTable_config& o) const {
    if (mcc != o.mcc) {
        return mcc < o.mcc ? -1 : 1;
    }
    if (mnc != o.mnc) {
        return mnc < o.mnc ? -1 : 1;
    }

    int diff = compareLocales(*this, o);
    if (diff < 0) {
        return -1;
    }
    if (diff > 0) {
        return 1;
    }

    if ((screenLayout & MASK_LAYOUTDIR) != (o.screenLayout & MASK_LAYOUTDIR)) {
        return (screenLayout & MASK_LAYOUTDIR) < (o.screenLayout & MASK_LAYOUTDIR) ? -1 : 1;
    }
    if (smallestScreenWidthDp != o.smallestScreenWidthDp) {
        return smallestScreenWidthDp < o.smallestScreenWidthDp ? -1 : 1;
    }
    if (screenWidthDp != o.screenWidthDp) {
        return screenWidthDp < o.screenWidthDp ? -1 : 1;
    }
    if (screenHeightDp != o.screenHeightDp) {
        return screenHeightDp < o.screenHeightDp ? -1 : 1;
    }
    if (screenWidth != o.screenWidth) {
        return screenWidth < o.screenWidth ? -1 : 1;
    }
    if (screenHeight != o.screenHeight) {
        return screenHeight < o.screenHeight ? -1 : 1;
    }
    if (density != o.density) {
        return density < o.density ? -1 : 1;
    }
    if (orientation != o.orientation) {
        return orientation < o.orientation ? -1 : 1;
    }
    if (touchscreen != o.touchscreen) {
        return touchscreen < o.touchscreen ? -1 : 1;
    }
    if (input != o.input) {
        return input < o.input ? -1 : 1;
    }
    if (screenLayout != o.screenLayout) {
        return screenLayout < o.screenLayout ? -1 : 1;
    }
    if (screenLayout2 != o.screenLayout2) {
        return screenLayout2 < o.screenLayout2 ? -1 : 1;
    }
    if (uiMode != o.uiMode) {
        return uiMode < o.uiMode ? -1 : 1;
    }
    if (version != o.version) {
        return version < o.version ? -1 : 1;
    }
    return 0;
}

int ResTable_config::diff(const ResTable_config& o) const {
    int diffs = 0;
    if (mcc != o.mcc) diffs |= CONFIG_MCC;
    if (mnc != o.mnc) diffs |= CONFIG_MNC;
    if (orientation != o.orientation) diffs |= CONFIG_ORIENTATION;
    if (density != o.density) diffs |= CONFIG_DENSITY;
    if (touchscreen != o.touchscreen) diffs |= CONFIG_TOUCHSCREEN;
    if (((inputFlags^o.inputFlags)&(MASK_KEYSHIDDEN|MASK_NAVHIDDEN)) != 0)
            diffs |= CONFIG_KEYBOARD_HIDDEN;
    if (keyboard != o.keyboard) diffs |= CONFIG_KEYBOARD;
    if (navigation != o.navigation) diffs |= CONFIG_NAVIGATION;
    if (screenSize != o.screenSize) diffs |= CONFIG_SCREEN_SIZE;
    if (version != o.version) diffs |= CONFIG_VERSION;
    if ((screenLayout & MASK_LAYOUTDIR) != (o.screenLayout & MASK_LAYOUTDIR)) diffs |= CONFIG_LAYOUTDIR;
    if ((screenLayout & ~MASK_LAYOUTDIR) != (o.screenLayout & ~MASK_LAYOUTDIR)) diffs |= CONFIG_SCREEN_LAYOUT;
    if ((screenLayout2 & MASK_SCREENROUND) != (o.screenLayout2 & MASK_SCREENROUND)) diffs |= CONFIG_SCREEN_ROUND;
    if (uiMode != o.uiMode) diffs |= CONFIG_UI_MODE;
    if (smallestScreenWidthDp != o.smallestScreenWidthDp) diffs |= CONFIG_SMALLEST_SCREEN_SIZE;
    if (screenSizeDp != o.screenSizeDp) diffs |= CONFIG_SCREEN_SIZE;

    const int diff = compareLocales(*this, o);
    if (diff) diffs |= CONFIG_LOCALE;

    return diffs;
}

int ResTable_config::isLocaleMoreSpecificThan(const ResTable_config& o) const {
    if (locale || o.locale) {
        if (language[0] != o.language[0]) {
            if (!language[0]) return -1;
            if (!o.language[0]) return 1;
        }

        if (country[0] != o.country[0]) {
            if (!country[0]) return -1;
            if (!o.country[0]) return 1;
        }
    }

    // There isn't a well specified "importance" order between variants and
    // scripts. We can't easily tell whether, say "en-Latn-US" is more or less
    // specific than "en-US-POSIX".
    //
    // We therefore arbitrarily decide to give priority to variants over
    // scripts since it seems more useful to do so. We will consider
    // "en-US-POSIX" to be more specific than "en-Latn-US".

    const int score = ((localeScript[0] != '\0' && !localeScriptWasComputed) ? 1 : 0) +
        ((localeVariant[0] != '\0') ? 2 : 0);

    const int oScore = (o.localeScript[0] != '\0' && !o.localeScriptWasComputed ? 1 : 0) +
        ((o.localeVariant[0] != '\0') ? 2 : 0);

    return score - oScore;

}

bool ResTable_config::isMoreSpecificThan(const ResTable_config& o) const {
    // The order of the following tests defines the importance of one
    // configuration parameter over another.  Those tests first are more
    // important, trumping any values in those following them.
    if (imsi || o.imsi) {
        if (mcc != o.mcc) {
            if (!mcc) return false;
            if (!o.mcc) return true;
        }

        if (mnc != o.mnc) {
            if (!mnc) return false;
            if (!o.mnc) return true;
        }
    }

    if (locale || o.locale) {
        const int diff = isLocaleMoreSpecificThan(o);
        if (diff < 0) {
            return false;
        }

        if (diff > 0) {
            return true;
        }
    }

    if (screenLayout || o.screenLayout) {
        if (((screenLayout^o.screenLayout) & MASK_LAYOUTDIR) != 0) {
            if (!(screenLayout & MASK_LAYOUTDIR)) return false;
            if (!(o.screenLayout & MASK_LAYOUTDIR)) return true;
        }
    }

    if (smallestScreenWidthDp || o.smallestScreenWidthDp) {
        if (smallestScreenWidthDp != o.smallestScreenWidthDp) {
            if (!smallestScreenWidthDp) return false;
            if (!o.smallestScreenWidthDp) return true;
        }
    }

    if (screenSizeDp || o.screenSizeDp) {
        if (screenWidthDp != o.screenWidthDp) {
            if (!screenWidthDp) return false;
            if (!o.screenWidthDp) return true;
        }

        if (screenHeightDp != o.screenHeightDp) {
            if (!screenHeightDp) return false;
            if (!o.screenHeightDp) return true;
        }
    }

    if (screenLayout || o.screenLayout) {
        if (((screenLayout^o.screenLayout) & MASK_SCREENSIZE) != 0) {
            if (!(screenLayout & MASK_SCREENSIZE)) return false;
            if (!(o.screenLayout & MASK_SCREENSIZE)) return true;
        }
        if (((screenLayout^o.screenLayout) & MASK_SCREENLONG) != 0) {
            if (!(screenLayout & MASK_SCREENLONG)) return false;
            if (!(o.screenLayout & MASK_SCREENLONG)) return true;
        }
    }

    if (screenLayout2 || o.screenLayout2) {
        if (((screenLayout2^o.screenLayout2) & MASK_SCREENROUND) != 0) {
            if (!(screenLayout2 & MASK_SCREENROUND)) return false;
            if (!(o.screenLayout2 & MASK_SCREENROUND)) return true;
        }
    }

    if (orientation != o.orientation) {
        if (!orientation) return false;
        if (!o.orientation) return true;
    }

    if (uiMode || o.uiMode) {
        if (((uiMode^o.uiMode) & MASK_UI_MODE_TYPE) != 0) {
            if (!(uiMode & MASK_UI_MODE_TYPE)) return false;
            if (!(o.uiMode & MASK_UI_MODE_TYPE)) return true;
        }
        if (((uiMode^o.uiMode) & MASK_UI_MODE_NIGHT) != 0) {
            if (!(uiMode & MASK_UI_MODE_NIGHT)) return false;
            if (!(o.uiMode & MASK_UI_MODE_NIGHT)) return true;
        }
    }

    // density is never 'more specific'
    // as the default just equals 160

    if (touchscreen != o.touchscreen) {
        if (!touchscreen) return false;
        if (!o.touchscreen) return true;
    }

    if (input || o.input) {
        if (((inputFlags^o.inputFlags) & MASK_KEYSHIDDEN) != 0) {
            if (!(inputFlags & MASK_KEYSHIDDEN)) return false;
            if (!(o.inputFlags & MASK_KEYSHIDDEN)) return true;
        }

        if (((inputFlags^o.inputFlags) & MASK_NAVHIDDEN) != 0) {
            if (!(inputFlags & MASK_NAVHIDDEN)) return false;
            if (!(o.inputFlags & MASK_NAVHIDDEN)) return true;
        }

        if (keyboard != o.keyboard) {
            if (!keyboard) return false;
            if (!o.keyboard) return true;
        }

        if (navigation != o.navigation) {
            if (!navigation) return false;
            if (!o.navigation) return true;
        }
    }

    if (screenSize || o.screenSize) {
        if (screenWidth != o.screenWidth) {
            if (!screenWidth) return false;
            if (!o.screenWidth) return true;
        }

        if (screenHeight != o.screenHeight) {
            if (!screenHeight) return false;
            if (!o.screenHeight) return true;
        }
    }

    if (version || o.version) {
        if (sdkVersion != o.sdkVersion) {
            if (!sdkVersion) return false;
            if (!o.sdkVersion) return true;
        }

        if (minorVersion != o.minorVersion) {
            if (!minorVersion) return false;
            if (!o.minorVersion) return true;
        }
    }
    return false;
}

bool ResTable_config::isLocaleBetterThan(const ResTable_config& o,
        const ResTable_config* requested) const {
    if (requested->locale == 0) {
        // The request doesn't have a locale, so no resource is better
        // than the other.
        return false;
    }

    if (locale == 0 && o.locale == 0) {
        // The locales parts of both resources are empty, so no one is better
        // than the other.
        return false;
    }

    // Non-matching locales have been filtered out, so both resources
    // match the requested locale.
    //
    // Because of the locale-related checks in match() and the checks, we know
    // that:
    // 1) The resource languages are either empty or match the request;
    // and
    // 2) If the request's script is known, the resource scripts are either
    //    unknown or match the request.

    if (language[0] != o.language[0]) {
        // The languages of the two resources are not the same. We can only
        // assume that one of the two resources matched the request because one
        // doesn't have a language and the other has a matching language.
        //
        // We consider the one that has the language specified a better match.
        //
        // The exception is that we consider no-language resources a better match
        // for US English and similar locales than locales that are a descendant
        // of Internatinal English (en-001), since no-language resources are
        // where the US English resource have traditionally lived for most apps.
        if (requested->language[0] == 'e' && requested->language[1] == 'n') {
            if (requested->country[0] == 'U' && requested->country[1] == 'S') {
                // For US English itself, we consider a no-locale resource a
                // better match if the other resource has a country other than
                // US specified.
                if (language[0] != '\0') {
                    return country[0] == '\0' || (country[0] == 'U' && country[1] == 'S');
                } else {
                    return !(o.country[0] == '\0' || (o.country[0] == 'U' && o.country[1] == 'S'));
                }
            } else if (localeDataIsCloseToUsEnglish(requested->country)) {
                if (language[0] != '\0') {
                    return localeDataIsCloseToUsEnglish(country);
                } else {
                    return !localeDataIsCloseToUsEnglish(o.country);
                }
            }
        }
        return (language[0] != '\0');
    }

    // If we are here, both the resources have the same non-empty language as
    // the request.
    //
    // Because the languages are the same, computeScript() always
    // returns a non-empty script for languages it knows about, and we have passed
    // the script checks in match(), the scripts are either all unknown or are
    // all the same. So we can't gain anything by checking the scripts. We need
    // to check the region and variant.

    // See if any of the regions is better than the other
    const int region_comparison = localeDataCompareRegions(
            country, o.country,
            language, requested->localeScript, requested->country);
    if (region_comparison != 0) {
        return (region_comparison > 0);
    }

    // The regions are the same. Try the variant.
    if (requested->localeVariant[0] != '\0'
            && strncmp(localeVariant, requested->localeVariant, sizeof(localeVariant)) == 0) {
        return (strncmp(o.localeVariant, requested->localeVariant, sizeof(localeVariant)) != 0);
    }

    return false;
}

bool ResTable_config::isBetterThan(const ResTable_config& o,
        const ResTable_config* requested) const {
    if (requested) {
        if (imsi || o.imsi) {
            if ((mcc != o.mcc) && requested->mcc) {
                return (mcc);
            }

            if ((mnc != o.mnc) && requested->mnc) {
                return (mnc);
            }
        }

        if (isLocaleBetterThan(o, requested)) {
            return true;
        }

        if (screenLayout || o.screenLayout) {
            if (((screenLayout^o.screenLayout) & MASK_LAYOUTDIR) != 0
                    && (requested->screenLayout & MASK_LAYOUTDIR)) {
                int myLayoutDir = screenLayout & MASK_LAYOUTDIR;
                int oLayoutDir = o.screenLayout & MASK_LAYOUTDIR;
                return (myLayoutDir > oLayoutDir);
            }
        }

        if (smallestScreenWidthDp || o.smallestScreenWidthDp) {
            // The configuration closest to the actual size is best.
            // We assume that larger configs have already been filtered
            // out at this point.  That means we just want the largest one.
            if (smallestScreenWidthDp != o.smallestScreenWidthDp) {
                return smallestScreenWidthDp > o.smallestScreenWidthDp;
            }
        }

        if (screenSizeDp || o.screenSizeDp) {
            // "Better" is based on the sum of the difference between both
            // width and height from the requested dimensions.  We are
            // assuming the invalid configs (with smaller dimens) have
            // already been filtered.  Note that if a particular dimension
            // is unspecified, we will end up with a large value (the
            // difference between 0 and the requested dimension), which is
            // good since we will prefer a config that has specified a
            // dimension value.
            int myDelta = 0, otherDelta = 0;
            if (requested->screenWidthDp) {
                myDelta += requested->screenWidthDp - screenWidthDp;
                otherDelta += requested->screenWidthDp - o.screenWidthDp;
            }
            if (requested->screenHeightDp) {
                myDelta += requested->screenHeightDp - screenHeightDp;
                otherDelta += requested->screenHeightDp - o.screenHeightDp;
            }
            if (kDebugTableSuperNoisy) {
                ALOGI("Comparing this %dx%d to other %dx%d in %dx%d: myDelta=%d otherDelta=%d",
                        screenWidthDp, screenHeightDp, o.screenWidthDp, o.screenHeightDp,
                        requested->screenWidthDp, requested->screenHeightDp, myDelta, otherDelta);
            }
            if (myDelta != otherDelta) {
                return myDelta < otherDelta;
            }
        }

        if (screenLayout || o.screenLayout) {
            if (((screenLayout^o.screenLayout) & MASK_SCREENSIZE) != 0
                    && (requested->screenLayout & MASK_SCREENSIZE)) {
                // A little backwards compatibility here: undefined is
                // considered equivalent to normal.  But only if the
                // requested size is at least normal; otherwise, small
                // is better than the default.
                int mySL = (screenLayout & MASK_SCREENSIZE);
                int oSL = (o.screenLayout & MASK_SCREENSIZE);
                int fixedMySL = mySL;
                int fixedOSL = oSL;
                if ((requested->screenLayout & MASK_SCREENSIZE) >= SCREENSIZE_NORMAL) {
                    if (fixedMySL == 0) fixedMySL = SCREENSIZE_NORMAL;
                    if (fixedOSL == 0) fixedOSL = SCREENSIZE_NORMAL;
                }
                // For screen size, the best match is the one that is
                // closest to the requested screen size, but not over
                // (the not over part is dealt with in match() below).
                if (fixedMySL == fixedOSL) {
                    // If the two are the same, but 'this' is actually
                    // undefined, then the other is really a better match.
                    if (mySL == 0) return false;
                    return true;
                }
                if (fixedMySL != fixedOSL) {
                    return fixedMySL > fixedOSL;
                }
            }
            if (((screenLayout^o.screenLayout) & MASK_SCREENLONG) != 0
                    && (requested->screenLayout & MASK_SCREENLONG)) {
                return (screenLayout & MASK_SCREENLONG);
            }
        }

        if (screenLayout2 || o.screenLayout2) {
            if (((screenLayout2^o.screenLayout2) & MASK_SCREENROUND) != 0 &&
                    (requested->screenLayout2 & MASK_SCREENROUND)) {
                return screenLayout2 & MASK_SCREENROUND;
            }
        }

        if ((orientation != o.orientation) && requested->orientation) {
            return (orientation);
        }

        if (uiMode || o.uiMode) {
            if (((uiMode^o.uiMode) & MASK_UI_MODE_TYPE) != 0
                    && (requested->uiMode & MASK_UI_MODE_TYPE)) {
                return (uiMode & MASK_UI_MODE_TYPE);
            }
            if (((uiMode^o.uiMode) & MASK_UI_MODE_NIGHT) != 0
                    && (requested->uiMode & MASK_UI_MODE_NIGHT)) {
                return (uiMode & MASK_UI_MODE_NIGHT);
            }
        }

        if (screenType || o.screenType) {
            if (density != o.density) {
                // Use the system default density (DENSITY_MEDIUM, 160dpi) if none specified.
                const int thisDensity = density ? density : int(ResTable_config::DENSITY_MEDIUM);
                const int otherDensity = o.density ? o.density : int(ResTable_config::DENSITY_MEDIUM);

                // We always prefer DENSITY_ANY over scaling a density bucket.
                if (thisDensity == ResTable_config::DENSITY_ANY) {
                    return true;
                } else if (otherDensity == ResTable_config::DENSITY_ANY) {
                    return false;
                }

                int requestedDensity = requested->density;
                if (requested->density == 0 ||
                        requested->density == ResTable_config::DENSITY_ANY) {
                    requestedDensity = ResTable_config::DENSITY_MEDIUM;
                }

                // DENSITY_ANY is now dealt with. We should look to
                // pick a density bucket and potentially scale it.
                // Any density is potentially useful
                // because the system will scale it.  Scaling down
                // is generally better than scaling up.
                int h = thisDensity;
                int l = otherDensity;
                bool bImBigger = true;
                if (l > h) {
                    int t = h;
                    h = l;
                    l = t;
                    bImBigger = false;
                }

                if (requestedDensity >= h) {
                    // requested value higher than both l and h, give h
                    return bImBigger;
                }
                if (l >= requestedDensity) {
                    // requested value lower than both l and h, give l
                    return !bImBigger;
                }
                // saying that scaling down is 2x better than up
                if (((2 * l) - requestedDensity) * h > requestedDensity * requestedDensity) {
                    return !bImBigger;
                } else {
                    return bImBigger;
                }
            }

            if ((touchscreen != o.touchscreen) && requested->touchscreen) {
                return (touchscreen);
            }
        }

        if (input || o.input) {
            const int keysHidden = inputFlags & MASK_KEYSHIDDEN;
            const int oKeysHidden = o.inputFlags & MASK_KEYSHIDDEN;
            if (keysHidden != oKeysHidden) {
                const int reqKeysHidden =
                        requested->inputFlags & MASK_KEYSHIDDEN;
                if (reqKeysHidden) {

                    if (!keysHidden) return false;
                    if (!oKeysHidden) return true;
                    // For compatibility, we count KEYSHIDDEN_NO as being
                    // the same as KEYSHIDDEN_SOFT.  Here we disambiguate
                    // these by making an exact match more specific.
                    if (reqKeysHidden == keysHidden) return true;
                    if (reqKeysHidden == oKeysHidden) return false;
                }
            }

            const int navHidden = inputFlags & MASK_NAVHIDDEN;
            const int oNavHidden = o.inputFlags & MASK_NAVHIDDEN;
            if (navHidden != oNavHidden) {
                const int reqNavHidden =
                        requested->inputFlags & MASK_NAVHIDDEN;
                if (reqNavHidden) {

                    if (!navHidden) return false;
                    if (!oNavHidden) return true;
                }
            }

            if ((keyboard != o.keyboard) && requested->keyboard) {
                return (keyboard);
            }

            if ((navigation != o.navigation) && requested->navigation) {
                return (navigation);
            }
        }

        if (screenSize || o.screenSize) {
            // "Better" is based on the sum of the difference between both
            // width and height from the requested dimensions.  We are
            // assuming the invalid configs (with smaller sizes) have
            // already been filtered.  Note that if a particular dimension
            // is unspecified, we will end up with a large value (the
            // difference between 0 and the requested dimension), which is
            // good since we will prefer a config that has specified a
            // size value.
            int myDelta = 0, otherDelta = 0;
            if (requested->screenWidth) {
                myDelta += requested->screenWidth - screenWidth;
                otherDelta += requested->screenWidth - o.screenWidth;
            }
            if (requested->screenHeight) {
                myDelta += requested->screenHeight - screenHeight;
                otherDelta += requested->screenHeight - o.screenHeight;
            }
            if (myDelta != otherDelta) {
                return myDelta < otherDelta;
            }
        }

        if (version || o.version) {
            if ((sdkVersion != o.sdkVersion) && requested->sdkVersion) {
                return (sdkVersion > o.sdkVersion);
            }

            if ((minorVersion != o.minorVersion) &&
                    requested->minorVersion) {
                return (minorVersion);
            }
        }

        return false;
    }
    return isMoreSpecificThan(o);
}

bool ResTable_config::match(const ResTable_config& settings) const {
    if (imsi != 0) {
        if (mcc != 0 && mcc != settings.mcc) {
            return false;
        }
        if (mnc != 0 && mnc != settings.mnc) {
            return false;
        }
    }
    if (locale != 0) {
        // Don't consider country and variants when deciding matches.
        // (Theoretically, the variant can also affect the script. For
        // example, "ar-alalc97" probably implies the Latin script, but since
        // CLDR doesn't support getting likely scripts for that, we'll assume
        // the variant doesn't change the script.)
        //
        // If two configs differ only in their country and variant,
        // they can be weeded out in the isMoreSpecificThan test.
        if (language[0] != settings.language[0] || language[1] != settings.language[1]) {
            return false;
        }

        // For backward compatibility and supporting private-use locales, we
        // fall back to old behavior if we couldn't determine the script for
        // either of the desired locale or the provided locale. But if we could determine
        // the scripts, they should be the same for the locales to match.
        bool countriesMustMatch = false;
        char computed_script[4];
        const char* script;
        if (settings.localeScript[0] == '\0') { // could not determine the request's script
            countriesMustMatch = true;
        } else {
            if (localeScript[0] == '\0' && !localeScriptWasComputed) {
                // script was not provided or computed, so we try to compute it
                localeDataComputeScript(computed_script, language, country);
                if (computed_script[0] == '\0') { // we could not compute the script
                    countriesMustMatch = true;
                } else {
                    script = computed_script;
                }
            } else { // script was provided, so just use it
                script = localeScript;
            }
        }

        if (countriesMustMatch) {
            if (country[0] != '\0'
                && (country[0] != settings.country[0]
                    || country[1] != settings.country[1])) {
                return false;
            }
        } else {
            if (memcmp(script, settings.localeScript, sizeof(settings.localeScript)) != 0) {
                return false;
            }
        }
    }

    if (screenConfig != 0) {
        const int layoutDir = screenLayout&MASK_LAYOUTDIR;
        const int setLayoutDir = settings.screenLayout&MASK_LAYOUTDIR;
        if (layoutDir != 0 && layoutDir != setLayoutDir) {
            return false;
        }

        const int screenSize = screenLayout&MASK_SCREENSIZE;
        const int setScreenSize = settings.screenLayout&MASK_SCREENSIZE;
        // Any screen sizes for larger screens than the setting do not
        // match.
        if (screenSize != 0 && screenSize > setScreenSize) {
            return false;
        }

        const int screenLong = screenLayout&MASK_SCREENLONG;
        const int setScreenLong = settings.screenLayout&MASK_SCREENLONG;
        if (screenLong != 0 && screenLong != setScreenLong) {
            return false;
        }

        const int uiModeType = uiMode&MASK_UI_MODE_TYPE;
        const int setUiModeType = settings.uiMode&MASK_UI_MODE_TYPE;
        if (uiModeType != 0 && uiModeType != setUiModeType) {
            return false;
        }

        const int uiModeNight = uiMode&MASK_UI_MODE_NIGHT;
        const int setUiModeNight = settings.uiMode&MASK_UI_MODE_NIGHT;
        if (uiModeNight != 0 && uiModeNight != setUiModeNight) {
            return false;
        }

        if (smallestScreenWidthDp != 0
                && smallestScreenWidthDp > settings.smallestScreenWidthDp) {
            return false;
        }
    }

    if (screenConfig2 != 0) {
        const int screenRound = screenLayout2 & MASK_SCREENROUND;
        const int setScreenRound = settings.screenLayout2 & MASK_SCREENROUND;
        if (screenRound != 0 && screenRound != setScreenRound) {
            return false;
        }
    }

    if (screenSizeDp != 0) {
        if (screenWidthDp != 0 && screenWidthDp > settings.screenWidthDp) {
            if (kDebugTableSuperNoisy) {
                ALOGI("Filtering out width %d in requested %d", screenWidthDp,
                        settings.screenWidthDp);
            }
            return false;
        }
        if (screenHeightDp != 0 && screenHeightDp > settings.screenHeightDp) {
            if (kDebugTableSuperNoisy) {
                ALOGI("Filtering out height %d in requested %d", screenHeightDp,
                        settings.screenHeightDp);
            }
            return false;
        }
    }
    if (screenType != 0) {
        if (orientation != 0 && orientation != settings.orientation) {
            return false;
        }
        // density always matches - we can scale it.  See isBetterThan
        if (touchscreen != 0 && touchscreen != settings.touchscreen) {
            return false;
        }
    }
    if (input != 0) {
        const int keysHidden = inputFlags&MASK_KEYSHIDDEN;
        const int setKeysHidden = settings.inputFlags&MASK_KEYSHIDDEN;
        if (keysHidden != 0 && keysHidden != setKeysHidden) {
            // For compatibility, we count a request for KEYSHIDDEN_NO as also
            // matching the more recent KEYSHIDDEN_SOFT.  Basically
            // KEYSHIDDEN_NO means there is some kind of keyboard available.
            if (kDebugTableSuperNoisy) {
                ALOGI("Matching keysHidden: have=%d, config=%d\n", keysHidden, setKeysHidden);
            }
            if (keysHidden != KEYSHIDDEN_NO || setKeysHidden != KEYSHIDDEN_SOFT) {
                if (kDebugTableSuperNoisy) {
                    ALOGI("No match!");
                }
                return false;
            }
        }
        const int navHidden = inputFlags&MASK_NAVHIDDEN;
        const int setNavHidden = settings.inputFlags&MASK_NAVHIDDEN;
        if (navHidden != 0 && navHidden != setNavHidden) {
            return false;
        }
        if (keyboard != 0 && keyboard != settings.keyboard) {
            return false;
        }
        if (navigation != 0 && navigation != settings.navigation) {
            return false;
        }
    }
    if (screenSize != 0) {
        if (screenWidth != 0 && screenWidth > settings.screenWidth) {
            return false;
        }
        if (screenHeight != 0 && screenHeight > settings.screenHeight) {
            return false;
        }
    }
    if (version != 0) {
        if (sdkVersion != 0 && sdkVersion > settings.sdkVersion) {
            return false;
        }
        if (minorVersion != 0 && minorVersion != settings.minorVersion) {
            return false;
        }
    }
    return true;
}

void ResTable_config::appendDirLocale(String8& out) const {
    if (!language[0]) {
        return;
    }
    const bool scriptWasProvided = localeScript[0] != '\0' && !localeScriptWasComputed;
    if (!scriptWasProvided && !localeVariant[0]) {
        // Legacy format.
        if (out.size() > 0) {
            out.append("-");
        }

        char buf[4];
        size_t len = unpackLanguage(buf);
        out.append(buf, len);

        if (country[0]) {
            out.append("-r");
            len = unpackRegion(buf);
            out.append(buf, len);
        }
        return;
    }

    // We are writing the modified BCP 47 tag.
    // It starts with 'b+' and uses '+' as a separator.

    if (out.size() > 0) {
        out.append("-");
    }
    out.append("b+");

    char buf[4];
    size_t len = unpackLanguage(buf);
    out.append(buf, len);

    if (scriptWasProvided) {
        out.append("+");
        out.append(localeScript, sizeof(localeScript));
    }

    if (country[0]) {
        out.append("+");
        len = unpackRegion(buf);
        out.append(buf, len);
    }

    if (localeVariant[0]) {
        out.append("+");
        out.append(localeVariant, strnlen(localeVariant, sizeof(localeVariant)));
    }
}

void ResTable_config::getBcp47Locale(char str[RESTABLE_MAX_LOCALE_LEN]) const {
    memset(str, 0, RESTABLE_MAX_LOCALE_LEN);

    // This represents the "any" locale value, which has traditionally been
    // represented by the empty string.
    if (!language[0] && !country[0]) {
        return;
    }

    size_t charsWritten = 0;
    if (language[0]) {
        charsWritten += unpackLanguage(str);
    }

    if (localeScript[0] && !localeScriptWasComputed) {
        if (charsWritten) {
            str[charsWritten++] = '-';
        }
        memcpy(str + charsWritten, localeScript, sizeof(localeScript));
        charsWritten += sizeof(localeScript);
    }

    if (country[0]) {
        if (charsWritten) {
            str[charsWritten++] = '-';
        }
        charsWritten += unpackRegion(str + charsWritten);
    }

    if (localeVariant[0]) {
        if (charsWritten) {
            str[charsWritten++] = '-';
        }
        memcpy(str + charsWritten, localeVariant, sizeof(localeVariant));
    }
}

/* static */ inline bool assignLocaleComponent(ResTable_config* config,
        const char* start, size_t size) {

  switch (size) {
       case 0:
           return false;
       case 2:
       case 3:
           config->language[0] ? config->packRegion(start) : config->packLanguage(start);
           break;
       case 4:
           if ('0' <= start[0] && start[0] <= '9') {
               // this is a variant, so fall through
           } else {
               config->localeScript[0] = toupper(start[0]);
               for (size_t i = 1; i < 4; ++i) {
                   config->localeScript[i] = tolower(start[i]);
               }
               break;
           }
       case 5:
       case 6:
       case 7:
       case 8:
           for (size_t i = 0; i < size; ++i) {
               config->localeVariant[i] = tolower(start[i]);
           }
           break;
       default:
           return false;
  }

  return true;
}

void ResTable_config::setBcp47Locale(const char* in) {
    locale = 0;
    memset(localeScript, 0, sizeof(localeScript));
    memset(localeVariant, 0, sizeof(localeVariant));

    const char* separator = in;
    const char* start = in;
    while ((separator = strchr(start, '-')) != NULL) {
        const size_t size = separator - start;
        if (!assignLocaleComponent(this, start, size)) {
            fprintf(stderr, "Invalid BCP-47 locale string: %s", in);
        }

        start = (separator + 1);
    }

    const size_t size = in + strlen(in) - start;
    assignLocaleComponent(this, start, size);
    localeScriptWasComputed = (localeScript[0] == '\0');
    if (localeScriptWasComputed) {
        computeScript();
    }
}

String8 ResTable_config::toString() const {
    String8 res;

    if (mcc != 0) {
        if (res.size() > 0) res.append("-");
        res.appendFormat("mcc%d", dtohs(mcc));
    }
    if (mnc != 0) {
        if (res.size() > 0) res.append("-");
        res.appendFormat("mnc%d", dtohs(mnc));
    }

    appendDirLocale(res);

    if ((screenLayout&MASK_LAYOUTDIR) != 0) {
        if (res.size() > 0) res.append("-");
        switch (screenLayout&ResTable_config::MASK_LAYOUTDIR) {
            case ResTable_config::LAYOUTDIR_LTR:
                res.append("ldltr");
                break;
            case ResTable_config::LAYOUTDIR_RTL:
                res.append("ldrtl");
                break;
            default:
                res.appendFormat("layoutDir=%d",
                        dtohs(screenLayout&ResTable_config::MASK_LAYOUTDIR));
                break;
        }
    }
    if (smallestScreenWidthDp != 0) {
        if (res.size() > 0) res.append("-");
        res.appendFormat("sw%ddp", dtohs(smallestScreenWidthDp));
    }
    if (screenWidthDp != 0) {
        if (res.size() > 0) res.append("-");
        res.appendFormat("w%ddp", dtohs(screenWidthDp));
    }
    if (screenHeightDp != 0) {
        if (res.size() > 0) res.append("-");
        res.appendFormat("h%ddp", dtohs(screenHeightDp));
    }
    if ((screenLayout&MASK_SCREENSIZE) != SCREENSIZE_ANY) {
        if (res.size() > 0) res.append("-");
        switch (screenLayout&ResTable_config::MASK_SCREENSIZE) {
            case ResTable_config::SCREENSIZE_SMALL:
                res.append("small");
                break;
            case ResTable_config::SCREENSIZE_NORMAL:
                res.append("normal");
                break;
            case ResTable_config::SCREENSIZE_LARGE:
                res.append("large");
                break;
            case ResTable_config::SCREENSIZE_XLARGE:
                res.append("xlarge");
                break;
            default:
                res.appendFormat("screenLayoutSize=%d",
                        dtohs(screenLayout&ResTable_config::MASK_SCREENSIZE));
                break;
        }
    }
    if ((screenLayout&MASK_SCREENLONG) != 0) {
        if (res.size() > 0) res.append("-");
        switch (screenLayout&ResTable_config::MASK_SCREENLONG) {
            case ResTable_config::SCREENLONG_NO:
                res.append("notlong");
                break;
            case ResTable_config::SCREENLONG_YES:
                res.append("long");
                break;
            default:
                res.appendFormat("screenLayoutLong=%d",
                        dtohs(screenLayout&ResTable_config::MASK_SCREENLONG));
                break;
        }
    }
    if ((screenLayout2&MASK_SCREENROUND) != 0) {
        if (res.size() > 0) res.append("-");
        switch (screenLayout2&MASK_SCREENROUND) {
            case SCREENROUND_NO:
                res.append("notround");
                break;
            case SCREENROUND_YES:
                res.append("round");
                break;
            default:
                res.appendFormat("screenRound=%d", dtohs(screenLayout2&MASK_SCREENROUND));
                break;
        }
    }
    if (orientation != ORIENTATION_ANY) {
        if (res.size() > 0) res.append("-");
        switch (orientation) {
            case ResTable_config::ORIENTATION_PORT:
                res.append("port");
                break;
            case ResTable_config::ORIENTATION_LAND:
                res.append("land");
                break;
            case ResTable_config::ORIENTATION_SQUARE:
                res.append("square");
                break;
            default:
                res.appendFormat("orientation=%d", dtohs(orientation));
                break;
        }
    }
    if ((uiMode&MASK_UI_MODE_TYPE) != UI_MODE_TYPE_ANY) {
        if (res.size() > 0) res.append("-");
        switch (uiMode&ResTable_config::MASK_UI_MODE_TYPE) {
            case ResTable_config::UI_MODE_TYPE_DESK:
                res.append("desk");
                break;
            case ResTable_config::UI_MODE_TYPE_CAR:
                res.append("car");
                break;
            case ResTable_config::UI_MODE_TYPE_TELEVISION:
                res.append("television");
                break;
            case ResTable_config::UI_MODE_TYPE_APPLIANCE:
                res.append("appliance");
                break;
            case ResTable_config::UI_MODE_TYPE_WATCH:
                res.append("watch");
                break;
            default:
                res.appendFormat("uiModeType=%d",
                        dtohs(screenLayout&ResTable_config::MASK_UI_MODE_TYPE));
                break;
        }
    }
    if ((uiMode&MASK_UI_MODE_NIGHT) != 0) {
        if (res.size() > 0) res.append("-");
        switch (uiMode&ResTable_config::MASK_UI_MODE_NIGHT) {
            case ResTable_config::UI_MODE_NIGHT_NO:
                res.append("notnight");
                break;
            case ResTable_config::UI_MODE_NIGHT_YES:
                res.append("night");
                break;
            default:
                res.appendFormat("uiModeNight=%d",
                        dtohs(uiMode&MASK_UI_MODE_NIGHT));
                break;
        }
    }
    if (density != DENSITY_DEFAULT) {
        if (res.size() > 0) res.append("-");
        switch (density) {
            case ResTable_config::DENSITY_LOW:
                res.append("ldpi");
                break;
            case ResTable_config::DENSITY_MEDIUM:
                res.append("mdpi");
                break;
            case ResTable_config::DENSITY_TV:
                res.append("tvdpi");
                break;
            case ResTable_config::DENSITY_HIGH:
                res.append("hdpi");
                break;
            case ResTable_config::DENSITY_XHIGH:
                res.append("xhdpi");
                break;
            case ResTable_config::DENSITY_XXHIGH:
                res.append("xxhdpi");
                break;
            case ResTable_config::DENSITY_XXXHIGH:
                res.append("xxxhdpi");
                break;
            case ResTable_config::DENSITY_NONE:
                res.append("nodpi");
                break;
            case ResTable_config::DENSITY_ANY:
                res.append("anydpi");
                break;
            default:
                res.appendFormat("%ddpi", dtohs(density));
                break;
        }
    }
    if (touchscreen != TOUCHSCREEN_ANY) {
        if (res.size() > 0) res.append("-");
        switch (touchscreen) {
            case ResTable_config::TOUCHSCREEN_NOTOUCH:
                res.append("notouch");
                break;
            case ResTable_config::TOUCHSCREEN_FINGER:
                res.append("finger");
                break;
            case ResTable_config::TOUCHSCREEN_STYLUS:
                res.append("stylus");
                break;
            default:
                res.appendFormat("touchscreen=%d", dtohs(touchscreen));
                break;
        }
    }
    if ((inputFlags&MASK_KEYSHIDDEN) != 0) {
        if (res.size() > 0) res.append("-");
        switch (inputFlags&MASK_KEYSHIDDEN) {
            case ResTable_config::KEYSHIDDEN_NO:
                res.append("keysexposed");
                break;
            case ResTable_config::KEYSHIDDEN_YES:
                res.append("keyshidden");
                break;
            case ResTable_config::KEYSHIDDEN_SOFT:
                res.append("keyssoft");
                break;
        }
    }
    if (keyboard != KEYBOARD_ANY) {
        if (res.size() > 0) res.append("-");
        switch (keyboard) {
            case ResTable_config::KEYBOARD_NOKEYS:
                res.append("nokeys");
                break;
            case ResTable_config::KEYBOARD_QWERTY:
                res.append("qwerty");
                break;
            case ResTable_config::KEYBOARD_12KEY:
                res.append("12key");
                break;
            default:
                res.appendFormat("keyboard=%d", dtohs(keyboard));
                break;
        }
    }
    if ((inputFlags&MASK_NAVHIDDEN) != 0) {
        if (res.size() > 0) res.append("-");
        switch (inputFlags&MASK_NAVHIDDEN) {
            case ResTable_config::NAVHIDDEN_NO:
                res.append("navexposed");
                break;
            case ResTable_config::NAVHIDDEN_YES:
                res.append("navhidden");
                break;
            default:
                res.appendFormat("inputFlagsNavHidden=%d",
                        dtohs(inputFlags&MASK_NAVHIDDEN));
                break;
        }
    }
    if (navigation != NAVIGATION_ANY) {
        if (res.size() > 0) res.append("-");
        switch (navigation) {
            case ResTable_config::NAVIGATION_NONAV:
                res.append("nonav");
                break;
            case ResTable_config::NAVIGATION_DPAD:
                res.append("dpad");
                break;
            case ResTable_config::NAVIGATION_TRACKBALL:
                res.append("trackball");
                break;
            case ResTable_config::NAVIGATION_WHEEL:
                res.append("wheel");
                break;
            default:
                res.appendFormat("navigation=%d", dtohs(navigation));
                break;
        }
    }
    if (screenSize != 0) {
        if (res.size() > 0) res.append("-");
        res.appendFormat("%dx%d", dtohs(screenWidth), dtohs(screenHeight));
    }
    if (version != 0) {
        if (res.size() > 0) res.append("-");
        res.appendFormat("v%d", dtohs(sdkVersion));
        if (minorVersion != 0) {
            res.appendFormat(".%d", dtohs(minorVersion));
        }
    }

    return res;
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

    const ResTable* const           owner;
    void*                           ownedData;
    const ResTable_header*          header;
    size_t                          size;
    const uint8_t*                  dataEnd;
    size_t                          index;
    int32_t                         cookie;

    ResStringPool                   values;
    uint32_t*                       resourceIDMap;
    size_t                          resourceIDMapSize;
};

struct ResTable::Entry {
    ResTable_config config;
    const ResTable_entry* entry;
    const ResTable_type* type;
    uint32_t specFlags;
    const Package* package;

    StringPoolRef typeStr;
    StringPoolRef keyStr;
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
    IdmapEntries                    idmapEntries;
    Vector<const ResTable_type*>    configs;
};

struct ResTable::Package
{
    Package(ResTable* _owner, const Header* _header, const ResTable_package* _package)
        : owner(_owner), header(_header), package(_package), typeIdOffset(0) {
        if (dtohs(package->header.headerSize) == sizeof(package)) {
            // The package structure is the same size as the definition.
            // This means it contains the typeIdOffset field.
            typeIdOffset = package->typeIdOffset;
        }
    }

    const ResTable* const           owner;
    const Header* const             header;
    const ResTable_package* const   package;

    ResStringPool                   typeStrings;
    ResStringPool                   keyStrings;

    size_t                          typeIdOffset;
};

// A group of objects describing a particular resource package.
// The first in 'package' is always the root object (from the resource
// table that defined the package); the ones after are skins on top of it.
struct ResTable::PackageGroup
{
    PackageGroup(
            ResTable* _owner, const String16& _name, uint32_t _id,
            bool appAsLib, bool _isSystemAsset)
        : owner(_owner)
        , name(_name)
        , id(_id)
        , largestTypeId(0)
        , dynamicRefTable(static_cast<uint8_t>(_id), appAsLib)
        , isSystemAsset(_isSystemAsset)
    { }

    ~PackageGroup() {
        clearBagCache();
        const size_t numTypes = types.size();
        for (size_t i = 0; i < numTypes; i++) {
            const TypeList& typeList = types[i];
            const size_t numInnerTypes = typeList.size();
            for (size_t j = 0; j < numInnerTypes; j++) {
                if (typeList[j]->package->owner == owner) {
                    delete typeList[j];
                }
            }
        }

        const size_t N = packages.size();
        for (size_t i=0; i<N; i++) {
            Package* pkg = packages[i];
            if (pkg->owner == owner) {
                delete pkg;
            }
        }
    }

    /**
     * Clear all cache related data that depends on parameters/configuration.
     * This includes the bag caches and filtered types.
     */
    void clearBagCache() {
        for (size_t i = 0; i < typeCacheEntries.size(); i++) {
            if (kDebugTableNoisy) {
                printf("type=%zu\n", i);
            }
            const TypeList& typeList = types[i];
            if (!typeList.isEmpty()) {
                TypeCacheEntry& cacheEntry = typeCacheEntries.editItemAt(i);

                // Reset the filtered configurations.
                cacheEntry.filteredConfigs.clear();

                bag_set** typeBags = cacheEntry.cachedBags;
                if (kDebugTableNoisy) {
                    printf("typeBags=%p\n", typeBags);
                }

                if (typeBags) {
                    const size_t N = typeList[0]->entryCount;
                    if (kDebugTableNoisy) {
                        printf("type->entryCount=%zu\n", N);
                    }
                    for (size_t j = 0; j < N; j++) {
                        if (typeBags[j] && typeBags[j] != (bag_set*)0xFFFFFFFF) {
                            free(typeBags[j]);
                        }
                    }
                    free(typeBags);
                    cacheEntry.cachedBags = NULL;
                }
            }
        }
    }

    ssize_t findType16(const char16_t* type, size_t len) const {
        const size_t N = packages.size();
        for (size_t i = 0; i < N; i++) {
            ssize_t index = packages[i]->typeStrings.indexOfString(type, len);
            if (index >= 0) {
                return index + packages[i]->typeIdOffset;
            }
        }
        return -1;
    }

    const ResTable* const           owner;
    String16 const                  name;
    uint32_t const                  id;

    // This is mainly used to keep track of the loaded packages
    // and to clean them up properly. Accessing resources happens from
    // the 'types' array.
    Vector<Package*>                packages;

    ByteBucketArray<TypeList>       types;

    uint8_t                         largestTypeId;

    // Cached objects dependent on the parameters/configuration of this ResTable.
    // Gets cleared whenever the parameters/configuration changes.
    // These are stored here in a parallel structure because the data in `types` may
    // be shared by other ResTable's (framework resources are shared this way).
    ByteBucketArray<TypeCacheEntry> typeCacheEntries;

    // The table mapping dynamic references to resolved references for
    // this package group.
    // TODO: We may be able to support dynamic references in overlays
    // by having these tables in a per-package scope rather than
    // per-package-group.
    DynamicRefTable                 dynamicRefTable;

    // If the package group comes from a system asset. Used in
    // determining non-system locales.
    const bool                      isSystemAsset;
};

ResTable::Theme::Theme(const ResTable& table)
    : mTable(table)
    , mTypeSpecFlags(0)
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
    for (size_t j = 0; j <= Res_MAXTYPE; j++) {
        theme_entry* te = pi->types[j].entries;
        if (te != NULL) {
            free(te);
        }
    }
    free(pi);
}

ResTable::Theme::package_info* ResTable::Theme::copy_package(package_info* pi)
{
    package_info* newpi = (package_info*)malloc(sizeof(package_info));
    for (size_t j = 0; j <= Res_MAXTYPE; j++) {
        size_t cnt = pi->types[j].numEntries;
        newpi->types[j].numEntries = cnt;
        theme_entry* te = pi->types[j].entries;
        size_t cnt_max = SIZE_MAX / sizeof(theme_entry);
        if (te != NULL && (cnt < 0xFFFFFFFF-1) && (cnt < cnt_max)) {
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
    if (kDebugTableNoisy) {
        ALOGV("Applying style 0x%08x to theme %p, count=%zu", resID, this, N);
    }
    if (N < 0) {
        mTable.unlock();
        return N;
    }

    mTypeSpecFlags |= bagTypeSpecFlags;

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
                ALOGE("Style contains key with bad package: 0x%08x\n", attrRes);
                bag++;
                continue;
            }
            curPackage = p;
            curPackageIndex = pidx;
            curPI = mPackages[pidx];
            if (curPI == NULL) {
                curPI = (package_info*)malloc(sizeof(package_info));
                memset(curPI, 0, sizeof(*curPI));
                mPackages[pidx] = curPI;
            }
            curType = 0xffffffff;
        }
        if (curType != t) {
            if (t > Res_MAXTYPE) {
                ALOGE("Style contains key with bad type: 0x%08x\n", attrRes);
                bag++;
                continue;
            }
            curType = t;
            curEntries = curPI->types[t].entries;
            if (curEntries == NULL) {
                PackageGroup* const grp = mTable.mPackageGroups[curPackageIndex];
                const TypeList& typeList = grp->types[t];
                size_t cnt = typeList.isEmpty() ? 0 : typeList[0]->entryCount;
                size_t cnt_max = SIZE_MAX / sizeof(theme_entry);
                size_t buff_size = (cnt < cnt_max && cnt < 0xFFFFFFFF-1) ?
                                          cnt*sizeof(theme_entry) : 0;
                curEntries = (theme_entry*)malloc(buff_size);
                memset(curEntries, Res_value::TYPE_NULL, buff_size);
                curPI->types[t].numEntries = cnt;
                curPI->types[t].entries = curEntries;
            }
            numEntries = curPI->types[t].numEntries;
        }
        if (e >= numEntries) {
            ALOGE("Style contains key with bad entry: 0x%08x\n", attrRes);
            bag++;
            continue;
        }
        theme_entry* curEntry = curEntries + e;
        if (kDebugTableNoisy) {
            ALOGV("Attr 0x%08x: type=0x%x, data=0x%08x; curType=0x%x",
                    attrRes, bag->map.value.dataType, bag->map.value.data,
                    curEntry->value.dataType);
        }
        if (force || curEntry->value.dataType == Res_value::TYPE_NULL) {
            curEntry->stringBlock = bag->stringBlock;
            curEntry->typeSpecFlags |= bagTypeSpecFlags;
            curEntry->value = bag->map.value;
        }

        bag++;
    }

    mTable.unlock();

    if (kDebugTableTheme) {
        ALOGI("Applying style 0x%08x (force=%d)  theme %p...\n", resID, force, this);
        dumpToLog();
    }

    return NO_ERROR;
}

status_t ResTable::Theme::setTo(const Theme& other)
{
    if (kDebugTableTheme) {
        ALOGI("Setting theme %p from theme %p...\n", this, &other);
        dumpToLog();
        other.dumpToLog();
    }

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

    mTypeSpecFlags = other.mTypeSpecFlags;

    if (kDebugTableTheme) {
        ALOGI("Final theme:");
        dumpToLog();
    }

    return NO_ERROR;
}

status_t ResTable::Theme::clear()
{
    if (kDebugTableTheme) {
        ALOGI("Clearing theme %p...\n", this);
        dumpToLog();
    }

    for (size_t i = 0; i < Res_MAXPACKAGE; i++) {
        if (mPackages[i] != NULL) {
            free_package(mPackages[i]);
            mPackages[i] = NULL;
        }
    }

    mTypeSpecFlags = 0;

    if (kDebugTableTheme) {
        ALOGI("Final theme:");
        dumpToLog();
    }

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

        if (kDebugTableTheme) {
            ALOGI("Looking up attr 0x%08x in theme %p", resID, this);
        }

        if (p >= 0) {
            const package_info* const pi = mPackages[p];
            if (kDebugTableTheme) {
                ALOGI("Found package: %p", pi);
            }
            if (pi != NULL) {
                if (kDebugTableTheme) {
                    ALOGI("Desired type index is %zd in avail %zu", t, Res_MAXTYPE + 1);
                }
                if (t <= Res_MAXTYPE) {
                    const type_info& ti = pi->types[t];
                    if (kDebugTableTheme) {
                        ALOGI("Desired entry index is %u in avail %zu", e, ti.numEntries);
                    }
                    if (e < ti.numEntries) {
                        const theme_entry& te = ti.entries[e];
                        if (outTypeSpecFlags != NULL) {
                            *outTypeSpecFlags |= te.typeSpecFlags;
                        }
                        if (kDebugTableTheme) {
                            ALOGI("Theme value: type=0x%x, data=0x%08x",
                                    te.value.dataType, te.value.data);
                        }
                        const uint8_t type = te.value.dataType;
                        if (type == Res_value::TYPE_ATTRIBUTE) {
                            if (cnt > 0) {
                                cnt--;
                                resID = te.value.data;
                                continue;
                            }
                            ALOGW("Too many attribute references, stopped at: 0x%08x\n", resID);
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
        if (kDebugTableTheme) {
            ALOGI("Resolving attr reference: blockIndex=%d, type=0x%x, data=0x%x\n",
                    (int)blockIndex, (int)inOutValue->dataType, inOutValue->data);
        }
        if (inoutTypeSpecFlags != NULL) *inoutTypeSpecFlags |= newTypeSpecFlags;
        //printf("Retrieved attribute new type=0x%x\n", inOutValue->dataType);
        if (blockIndex < 0) {
            return blockIndex;
        }
    }
    return mTable.resolveReference(inOutValue, blockIndex, outLastRef,
            inoutTypeSpecFlags, inoutConfig);
}

uint32_t ResTable::Theme::getChangingConfigurations() const
{
    return mTypeSpecFlags;
}

void ResTable::Theme::dumpToLog() const
{
    ALOGI("Theme %p:\n", this);
    for (size_t i=0; i<Res_MAXPACKAGE; i++) {
        package_info* pi = mPackages[i];
        if (pi == NULL) continue;

        ALOGI("  Package #0x%02x:\n", (int)(i + 1));
        for (size_t j = 0; j <= Res_MAXTYPE; j++) {
            type_info& ti = pi->types[j];
            if (ti.numEntries == 0) continue;
            ALOGI("    Type #0x%02x:\n", (int)(j + 1));
            for (size_t k = 0; k < ti.numEntries; k++) {
                const theme_entry& te = ti.entries[k];
                if (te.value.dataType == Res_value::TYPE_NULL) continue;
                ALOGI("      0x%08x: t=0x%x, d=0x%08x (block=%d)\n",
                     (int)Res_MAKEID(i, j, k),
                     te.value.dataType, (int)te.value.data, (int)te.stringBlock);
            }
        }
    }
}

ResTable::ResTable()
    : mError(NO_INIT), mNextPackageId(2)
{
    memset(&mParams, 0, sizeof(mParams));
    memset(mPackageMap, 0, sizeof(mPackageMap));
    if (kDebugTableSuperNoisy) {
        ALOGI("Creating ResTable %p\n", this);
    }
}

ResTable::ResTable(const void* data, size_t size, const int32_t cookie, bool copyData)
    : mError(NO_INIT), mNextPackageId(2)
{
    memset(&mParams, 0, sizeof(mParams));
    memset(mPackageMap, 0, sizeof(mPackageMap));
    addInternal(data, size, NULL, 0, false, cookie, copyData);
    LOG_FATAL_IF(mError != NO_ERROR, "Error parsing resource table");
    if (kDebugTableSuperNoisy) {
        ALOGI("Creating ResTable %p\n", this);
    }
}

ResTable::~ResTable()
{
    if (kDebugTableSuperNoisy) {
        ALOGI("Destroying ResTable in %p\n", this);
    }
    uninit();
}

inline ssize_t ResTable::getResourcePackageIndex(uint32_t resID) const
{
    return ((ssize_t)mPackageMap[Res_GETPACKAGE(resID)+1])-1;
}

status_t ResTable::add(const void* data, size_t size, const int32_t cookie, bool copyData) {
    return addInternal(data, size, NULL, 0, false, cookie, copyData);
}

status_t ResTable::add(const void* data, size_t size, const void* idmapData, size_t idmapDataSize,
        const int32_t cookie, bool copyData, bool appAsLib) {
    return addInternal(data, size, idmapData, idmapDataSize, appAsLib, cookie, copyData);
}

status_t ResTable::add(Asset* asset, const int32_t cookie, bool copyData) {
    const void* data = asset->getBuffer(true);
    if (data == NULL) {
        ALOGW("Unable to get buffer of resource asset file");
        return UNKNOWN_ERROR;
    }

    return addInternal(data, static_cast<size_t>(asset->getLength()), NULL, false, 0, cookie,
            copyData);
}

status_t ResTable::add(
        Asset* asset, Asset* idmapAsset, const int32_t cookie, bool copyData,
        bool appAsLib, bool isSystemAsset) {
    const void* data = asset->getBuffer(true);
    if (data == NULL) {
        ALOGW("Unable to get buffer of resource asset file");
        return UNKNOWN_ERROR;
    }

    size_t idmapSize = 0;
    const void* idmapData = NULL;
    if (idmapAsset != NULL) {
        idmapData = idmapAsset->getBuffer(true);
        if (idmapData == NULL) {
            ALOGW("Unable to get buffer of idmap asset file");
            return UNKNOWN_ERROR;
        }
        idmapSize = static_cast<size_t>(idmapAsset->getLength());
    }

    return addInternal(data, static_cast<size_t>(asset->getLength()),
            idmapData, idmapSize, appAsLib, cookie, copyData, isSystemAsset);
}

status_t ResTable::add(ResTable* src, bool isSystemAsset)
{
    mError = src->mError;

    for (size_t i=0; i < src->mHeaders.size(); i++) {
        mHeaders.add(src->mHeaders[i]);
    }

    for (size_t i=0; i < src->mPackageGroups.size(); i++) {
        PackageGroup* srcPg = src->mPackageGroups[i];
        PackageGroup* pg = new PackageGroup(this, srcPg->name, srcPg->id,
                false /* appAsLib */, isSystemAsset || srcPg->isSystemAsset);
        for (size_t j=0; j<srcPg->packages.size(); j++) {
            pg->packages.add(srcPg->packages[j]);
        }

        for (size_t j = 0; j < srcPg->types.size(); j++) {
            if (srcPg->types[j].isEmpty()) {
                continue;
            }

            TypeList& typeList = pg->types.editItemAt(j);
            typeList.appendVector(srcPg->types[j]);
        }
        pg->dynamicRefTable.addMappings(srcPg->dynamicRefTable);
        pg->largestTypeId = max(pg->largestTypeId, srcPg->largestTypeId);
        mPackageGroups.add(pg);
    }

    memcpy(mPackageMap, src->mPackageMap, sizeof(mPackageMap));

    return mError;
}

status_t ResTable::addEmpty(const int32_t cookie) {
    Header* header = new Header(this);
    header->index = mHeaders.size();
    header->cookie = cookie;
    header->values.setToEmpty();
    header->ownedData = calloc(1, sizeof(ResTable_header));

    ResTable_header* resHeader = (ResTable_header*) header->ownedData;
    resHeader->header.type = RES_TABLE_TYPE;
    resHeader->header.headerSize = sizeof(ResTable_header);
    resHeader->header.size = sizeof(ResTable_header);

    header->header = (const ResTable_header*) resHeader;
    mHeaders.add(header);
    return (mError=NO_ERROR);
}

status_t ResTable::addInternal(const void* data, size_t dataSize, const void* idmapData, size_t idmapDataSize,
        bool appAsLib, const int32_t cookie, bool copyData, bool isSystemAsset)
{
    if (!data) {
        return NO_ERROR;
    }

    if (dataSize < sizeof(ResTable_header)) {
        ALOGE("Invalid data. Size(%d) is smaller than a ResTable_header(%d).",
                (int) dataSize, (int) sizeof(ResTable_header));
        return UNKNOWN_ERROR;
    }

    Header* header = new Header(this);
    header->index = mHeaders.size();
    header->cookie = cookie;
    if (idmapData != NULL) {
        header->resourceIDMap = (uint32_t*) malloc(idmapDataSize);
        if (header->resourceIDMap == NULL) {
            delete header;
            return (mError = NO_MEMORY);
        }
        memcpy(header->resourceIDMap, idmapData, idmapDataSize);
        header->resourceIDMapSize = idmapDataSize;
    }
    mHeaders.add(header);

    const bool notDeviceEndian = htods(0xf0) != 0xf0;

    if (kDebugLoadTableNoisy) {
        ALOGV("Adding resources to ResTable: data=%p, size=%zu, cookie=%d, copy=%d "
                "idmap=%p\n", data, dataSize, cookie, copyData, idmapData);
    }

    if (copyData || notDeviceEndian) {
        header->ownedData = malloc(dataSize);
        if (header->ownedData == NULL) {
            return (mError=NO_MEMORY);
        }
        memcpy(header->ownedData, data, dataSize);
        data = header->ownedData;
    }

    header->header = (const ResTable_header*)data;
    header->size = dtohl(header->header->header.size);
    if (kDebugLoadTableSuperNoisy) {
        ALOGI("Got size %zu, again size 0x%x, raw size 0x%x\n", header->size,
                dtohl(header->header->header.size), header->header->header.size);
    }
    if (kDebugLoadTableNoisy) {
        ALOGV("Loading ResTable @%p:\n", header->header);
    }
    if (dtohs(header->header->header.headerSize) > header->size
            || header->size > dataSize) {
        ALOGW("Bad resource table: header size 0x%x or total size 0x%x is larger than data size 0x%x\n",
             (int)dtohs(header->header->header.headerSize),
             (int)header->size, (int)dataSize);
        return (mError=BAD_TYPE);
    }
    if (((dtohs(header->header->header.headerSize)|header->size)&0x3) != 0) {
        ALOGW("Bad resource table: header size 0x%x or total size 0x%x is not on an integer boundary\n",
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
        if (kDebugTableNoisy) {
            ALOGV("Chunk: type=0x%x, headerSize=0x%x, size=0x%x, pos=%p\n",
                    dtohs(chunk->type), dtohs(chunk->headerSize), dtohl(chunk->size),
                    (void*)(((const uint8_t*)chunk) - ((const uint8_t*)header->header)));
        }
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
                ALOGW("Multiple string chunks found in resource table.");
            }
        } else if (ctype == RES_TABLE_PACKAGE_TYPE) {
            if (curPackage >= dtohl(header->header->packageCount)) {
                ALOGW("More package chunks were found than the %d declared in the header.",
                     dtohl(header->header->packageCount));
                return (mError=BAD_TYPE);
            }

            if (parsePackage(
                    (ResTable_package*)chunk, header, appAsLib, isSystemAsset) != NO_ERROR) {
                return mError;
            }
            curPackage++;
        } else {
            ALOGW("Unknown chunk type 0x%x in table at %p.\n",
                 ctype,
                 (void*)(((const uint8_t*)chunk) - ((const uint8_t*)header->header)));
        }
        chunk = (const ResChunk_header*)
            (((const uint8_t*)chunk) + csize);
    }

    if (curPackage < dtohl(header->header->packageCount)) {
        ALOGW("Fewer package chunks (%d) were found than the %d declared in the header.",
             (int)curPackage, dtohl(header->header->packageCount));
        return (mError=BAD_TYPE);
    }
    mError = header->values.getError();
    if (mError != NO_ERROR) {
        ALOGW("No string values found in resource table!");
    }

    if (kDebugTableNoisy) {
        ALOGV("Returning from add with mError=%d\n", mError);
    }
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

bool ResTable::getResourceName(uint32_t resID, bool allowUtf8, resource_name* outName) const
{
    if (mError != NO_ERROR) {
        return false;
    }

    const ssize_t p = getResourcePackageIndex(resID);
    const int t = Res_GETTYPE(resID);
    const int e = Res_GETENTRY(resID);

    if (p < 0) {
        if (Res_GETPACKAGE(resID)+1 == 0) {
            ALOGW("No package identifier when getting name for resource number 0x%08x", resID);
        } else {
#ifndef STATIC_ANDROIDFW_FOR_TOOLS
            ALOGW("No known package when getting name for resource number 0x%08x", resID);
#endif
        }
        return false;
    }
    if (t < 0) {
        ALOGW("No type identifier when getting name for resource number 0x%08x", resID);
        return false;
    }

    const PackageGroup* const grp = mPackageGroups[p];
    if (grp == NULL) {
        ALOGW("Bad identifier when getting name for resource number 0x%08x", resID);
        return false;
    }

    Entry entry;
    status_t err = getEntry(grp, t, e, NULL, &entry);
    if (err != NO_ERROR) {
        return false;
    }

    outName->package = grp->name.string();
    outName->packageLen = grp->name.size();
    if (allowUtf8) {
        outName->type8 = entry.typeStr.string8(&outName->typeLen);
        outName->name8 = entry.keyStr.string8(&outName->nameLen);
    } else {
        outName->type8 = NULL;
        outName->name8 = NULL;
    }
    if (outName->type8 == NULL) {
        outName->type = entry.typeStr.string16(&outName->typeLen);
        // If we have a bad index for some reason, we should abort.
        if (outName->type == NULL) {
            return false;
        }
    }
    if (outName->name8 == NULL) {
        outName->name = entry.keyStr.string16(&outName->nameLen);
        // If we have a bad index for some reason, we should abort.
        if (outName->name == NULL) {
            return false;
        }
    }

    return true;
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
            ALOGW("No package identifier when getting value for resource number 0x%08x", resID);
        } else {
            ALOGW("No known package when getting value for resource number 0x%08x", resID);
        }
        return BAD_INDEX;
    }
    if (t < 0) {
        ALOGW("No type identifier when getting value for resource number 0x%08x", resID);
        return BAD_INDEX;
    }

    const PackageGroup* const grp = mPackageGroups[p];
    if (grp == NULL) {
        ALOGW("Bad identifier when getting value for resource number 0x%08x", resID);
        return BAD_INDEX;
    }

    // Allow overriding density
    ResTable_config desiredConfig = mParams;
    if (density > 0) {
        desiredConfig.density = density;
    }

    Entry entry;
    status_t err = getEntry(grp, t, e, &desiredConfig, &entry);
    if (err != NO_ERROR) {
        // Only log the failure when we're not running on the host as
        // part of a tool. The caller will do its own logging.
#ifndef STATIC_ANDROIDFW_FOR_TOOLS
        ALOGW("Failure getting entry for 0x%08x (t=%d e=%d) (error %d)\n",
                resID, t, e, err);
#endif
        return err;
    }

    if ((dtohs(entry.entry->flags) & ResTable_entry::FLAG_COMPLEX) != 0) {
        if (!mayBeBag) {
            ALOGW("Requesting resource 0x%08x failed because it is complex\n", resID);
        }
        return BAD_VALUE;
    }

    const Res_value* value = reinterpret_cast<const Res_value*>(
            reinterpret_cast<const uint8_t*>(entry.entry) + entry.entry->size);

    outValue->size = dtohs(value->size);
    outValue->res0 = value->res0;
    outValue->dataType = value->dataType;
    outValue->data = dtohl(value->data);

    // The reference may be pointing to a resource in a shared library. These
    // references have build-time generated package IDs. These ids may not match
    // the actual package IDs of the corresponding packages in this ResTable.
    // We need to fix the package ID based on a mapping.
    if (grp->dynamicRefTable.lookupResourceValue(outValue) != NO_ERROR) {
        ALOGW("Failed to resolve referenced package: 0x%08x", outValue->data);
        return BAD_VALUE;
    }

    if (kDebugTableNoisy) {
        size_t len;
        printf("Found value: pkg=%zu, type=%d, str=%s, int=%d\n",
                entry.package->header->index,
                outValue->dataType,
                outValue->dataType == Res_value::TYPE_STRING ?
                    String8(entry.package->header->values.stringAt(outValue->data, &len)).string() :
                    "",
                outValue->data);
    }

    if (outSpecFlags != NULL) {
        *outSpecFlags = entry.specFlags;
    }

    if (outConfig != NULL) {
        *outConfig = entry.config;
    }

    return entry.package->header->index;
}

ssize_t ResTable::resolveReference(Res_value* value, ssize_t blockIndex,
        uint32_t* outLastRef, uint32_t* inoutTypeSpecFlags,
        ResTable_config* outConfig) const
{
    int count=0;
    while (blockIndex >= 0 && value->dataType == Res_value::TYPE_REFERENCE
            && value->data != 0 && count < 20) {
        if (outLastRef) *outLastRef = value->data;
        uint32_t newFlags = 0;
        const ssize_t newIndex = getResource(value->data, value, true, 0, &newFlags,
                outConfig);
        if (newIndex == BAD_INDEX) {
            return BAD_INDEX;
        }
        if (kDebugTableTheme) {
            ALOGI("Resolving reference 0x%x: newIndex=%d, type=0x%x, data=0x%x\n",
                    value->data, (int)newIndex, (int)value->dataType, value->data);
        }
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
    char16_t /*tmpBuffer*/ [TMP_BUFFER_SIZE], size_t* outLen) const
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

void ResTable::unlockBag(const bag_entry* /*bag*/) const
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
        ALOGW("Invalid package identifier when getting bag for resource number 0x%08x", resID);
        return BAD_INDEX;
    }
    if (t < 0) {
        ALOGW("No type identifier when getting bag for resource number 0x%08x", resID);
        return BAD_INDEX;
    }

    //printf("Get bag: id=0x%08x, p=%d, t=%d\n", resID, p, t);
    PackageGroup* const grp = mPackageGroups[p];
    if (grp == NULL) {
        ALOGW("Bad identifier when getting bag for resource number 0x%08x", resID);
        return BAD_INDEX;
    }

    const TypeList& typeConfigs = grp->types[t];
    if (typeConfigs.isEmpty()) {
        ALOGW("Type identifier 0x%x does not exist.", t+1);
        return BAD_INDEX;
    }

    const size_t NENTRY = typeConfigs[0]->entryCount;
    if (e >= (int)NENTRY) {
        ALOGW("Entry identifier 0x%x is larger than entry count 0x%x",
             e, (int)typeConfigs[0]->entryCount);
        return BAD_INDEX;
    }

    // First see if we've already computed this bag...
    TypeCacheEntry& cacheEntry = grp->typeCacheEntries.editItemAt(t);
    bag_set** typeSet = cacheEntry.cachedBags;
    if (typeSet) {
        bag_set* set = typeSet[e];
        if (set) {
            if (set != (bag_set*)0xFFFFFFFF) {
                if (outTypeSpecFlags != NULL) {
                    *outTypeSpecFlags = set->typeSpecFlags;
                }
                *outBag = (bag_entry*)(set+1);
                if (kDebugTableSuperNoisy) {
                    ALOGI("Found existing bag for: 0x%x\n", resID);
                }
                return set->numAttrs;
            }
            ALOGW("Attempt to retrieve bag 0x%08x which is invalid or in a cycle.",
                 resID);
            return BAD_INDEX;
        }
    }

    // Bag not found, we need to compute it!
    if (!typeSet) {
        typeSet = (bag_set**)calloc(NENTRY, sizeof(bag_set*));
        if (!typeSet) return NO_MEMORY;
        cacheEntry.cachedBags = typeSet;
    }

    // Mark that we are currently working on this one.
    typeSet[e] = (bag_set*)0xFFFFFFFF;

    if (kDebugTableNoisy) {
        ALOGI("Building bag: %x\n", resID);
    }

    // Now collect all bag attributes
    Entry entry;
    status_t err = getEntry(grp, t, e, &mParams, &entry);
    if (err != NO_ERROR) {
        return err;
    }

    const uint16_t entrySize = dtohs(entry.entry->size);
    const uint32_t parent = entrySize >= sizeof(ResTable_map_entry)
        ? dtohl(((const ResTable_map_entry*)entry.entry)->parent.ident) : 0;
    const uint32_t count = entrySize >= sizeof(ResTable_map_entry)
        ? dtohl(((const ResTable_map_entry*)entry.entry)->count) : 0;

    size_t N = count;

    if (kDebugTableNoisy) {
        ALOGI("Found map: size=%x parent=%x count=%d\n", entrySize, parent, count);

    // If this map inherits from another, we need to start
    // with its parent's values.  Otherwise start out empty.
        ALOGI("Creating new bag, entrySize=0x%08x, parent=0x%08x\n", entrySize, parent);
    }

    // This is what we are building.
    bag_set* set = NULL;

    if (parent) {
        uint32_t resolvedParent = parent;

        // Bags encode a parent reference without using the standard
        // Res_value structure. That means we must always try to
        // resolve a parent reference in case it is actually a
        // TYPE_DYNAMIC_REFERENCE.
        status_t err = grp->dynamicRefTable.lookupResourceId(&resolvedParent);
        if (err != NO_ERROR) {
            ALOGE("Failed resolving bag parent id 0x%08x", parent);
            return UNKNOWN_ERROR;
        }

        const bag_entry* parentBag;
        uint32_t parentTypeSpecFlags = 0;
        const ssize_t NP = getBagLocked(resolvedParent, &parentBag, &parentTypeSpecFlags);
        const size_t NT = ((NP >= 0) ? NP : 0) + N;
        set = (bag_set*)malloc(sizeof(bag_set)+sizeof(bag_entry)*NT);
        if (set == NULL) {
            return NO_MEMORY;
        }
        if (NP > 0) {
            memcpy(set+1, parentBag, NP*sizeof(bag_entry));
            set->numAttrs = NP;
            if (kDebugTableNoisy) {
                ALOGI("Initialized new bag with %zd inherited attributes.\n", NP);
            }
        } else {
            if (kDebugTableNoisy) {
                ALOGI("Initialized new bag with no inherited attributes.\n");
            }
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

    set->typeSpecFlags |= entry.specFlags;

    // Now merge in the new attributes...
    size_t curOff = (reinterpret_cast<uintptr_t>(entry.entry) - reinterpret_cast<uintptr_t>(entry.type))
        + dtohs(entry.entry->size);
    const ResTable_map* map;
    bag_entry* entries = (bag_entry*)(set+1);
    size_t curEntry = 0;
    uint32_t pos = 0;
    if (kDebugTableNoisy) {
        ALOGI("Starting with set %p, entries=%p, avail=%zu\n", set, entries, set->availAttrs);
    }
    while (pos < count) {
        if (kDebugTableNoisy) {
            ALOGI("Now at %p\n", (void*)curOff);
        }

        if (curOff > (dtohl(entry.type->header.size)-sizeof(ResTable_map))) {
            ALOGW("ResTable_map at %d is beyond type chunk data %d",
                 (int)curOff, dtohl(entry.type->header.size));
            return BAD_TYPE;
        }
        map = (const ResTable_map*)(((const uint8_t*)entry.type) + curOff);
        N++;

        uint32_t newName = htodl(map->name.ident);
        if (!Res_INTERNALID(newName)) {
            // Attributes don't have a resource id as the name. They specify
            // other data, which would be wrong to change via a lookup.
            if (grp->dynamicRefTable.lookupResourceId(&newName) != NO_ERROR) {
                ALOGE("Failed resolving ResTable_map name at %d with ident 0x%08x",
                        (int) curOff, (int) newName);
                return UNKNOWN_ERROR;
            }
        }

        bool isInside;
        uint32_t oldName = 0;
        while ((isInside=(curEntry < set->numAttrs))
                && (oldName=entries[curEntry].map.name.ident) < newName) {
            if (kDebugTableNoisy) {
                ALOGI("#%zu: Keeping existing attribute: 0x%08x\n",
                        curEntry, entries[curEntry].map.name.ident);
            }
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
                if (kDebugTableNoisy) {
                    ALOGI("Reallocated set %p, entries=%p, avail=%zu\n",
                            set, entries, set->availAttrs);
                }
            }
            if (isInside) {
                // Going in the middle, need to make space.
                memmove(entries+curEntry+1, entries+curEntry,
                        sizeof(bag_entry)*(set->numAttrs-curEntry));
                set->numAttrs++;
            }
            if (kDebugTableNoisy) {
                ALOGI("#%zu: Inserting new attribute: 0x%08x\n", curEntry, newName);
            }
        } else {
            if (kDebugTableNoisy) {
                ALOGI("#%zu: Replacing existing attribute: 0x%08x\n", curEntry, oldName);
            }
        }

        bag_entry* cur = entries+curEntry;

        cur->stringBlock = entry.package->header->index;
        cur->map.name.ident = newName;
        cur->map.value.copyFrom_dtoh(map->value);
        status_t err = grp->dynamicRefTable.lookupResourceValue(&cur->map.value);
        if (err != NO_ERROR) {
            ALOGE("Reference item(0x%08x) in bag could not be resolved.", cur->map.value.data);
            return UNKNOWN_ERROR;
        }

        if (kDebugTableNoisy) {
            ALOGI("Setting entry #%zu %p: block=%zd, name=0x%08d, type=%d, data=0x%08x\n",
                    curEntry, cur, cur->stringBlock, cur->map.name.ident,
                    cur->map.value.dataType, cur->map.value.data);
        }

        // On to the next!
        curEntry++;
        pos++;
        const size_t size = dtohs(map->value.size);
        curOff += size + sizeof(*map)-sizeof(map->value);
    };

    if (curEntry > set->numAttrs) {
        set->numAttrs = curEntry;
    }

    // And this is it...
    typeSet[e] = set;
    if (set) {
        if (outTypeSpecFlags != NULL) {
            *outTypeSpecFlags = set->typeSpecFlags;
        }
        *outBag = (bag_entry*)(set+1);
        if (kDebugTableNoisy) {
            ALOGI("Returning %zu attrs\n", set->numAttrs);
        }
        return set->numAttrs;
    }
    return BAD_INDEX;
}

void ResTable::setParameters(const ResTable_config* params)
{
    AutoMutex _lock(mLock);
    AutoMutex _lock2(mFilteredConfigLock);

    if (kDebugTableGetEntry) {
        ALOGI("Setting parameters: %s\n", params->toString().string());
    }
    mParams = *params;
    for (size_t p = 0; p < mPackageGroups.size(); p++) {
        PackageGroup* packageGroup = mPackageGroups.editItemAt(p);
        if (kDebugTableNoisy) {
            ALOGI("CLEARING BAGS FOR GROUP %zu!", p);
        }
        packageGroup->clearBagCache();

        // Find which configurations match the set of parameters. This allows for a much
        // faster lookup in getEntry() if the set of values is narrowed down.
        for (size_t t = 0; t < packageGroup->types.size(); t++) {
            if (packageGroup->types[t].isEmpty()) {
                continue;
            }

            TypeList& typeList = packageGroup->types.editItemAt(t);

            // Retrieve the cache entry for this type.
            TypeCacheEntry& cacheEntry = packageGroup->typeCacheEntries.editItemAt(t);

            for (size_t ts = 0; ts < typeList.size(); ts++) {
                Type* type = typeList.editItemAt(ts);

                std::shared_ptr<Vector<const ResTable_type*>> newFilteredConfigs =
                        std::make_shared<Vector<const ResTable_type*>>();

                for (size_t ti = 0; ti < type->configs.size(); ti++) {
                    ResTable_config config;
                    config.copyFromDtoH(type->configs[ti]->config);

                    if (config.match(mParams)) {
                        newFilteredConfigs->add(type->configs[ti]);
                    }
                }

                if (kDebugTableNoisy) {
                    ALOGD("Updating pkg=%zu type=%zu with %zu filtered configs",
                          p, t, newFilteredConfigs->size());
                }

                cacheEntry.filteredConfigs.add(newFilteredConfigs);
            }
        }
    }
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
    if (kDebugTableSuperNoisy) {
        printf("Identifier for name: error=%d\n", mError);
    }

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
                    ALOGW("Array resource index: %d is too large.",
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

    if (kDebugTableNoisy) {
        printf("Looking for identifier: type=%s, name=%s, package=%s\n",
                String8(type, typeLen).string(),
                String8(name, nameLen).string(),
                String8(package, packageLen).string());
    }

    const String16 attr("attr");
    const String16 attrPrivate("^attr-private");

    const size_t NG = mPackageGroups.size();
    for (size_t ig=0; ig<NG; ig++) {
        const PackageGroup* group = mPackageGroups[ig];

        if (strzcmp16(package, packageLen,
                      group->name.string(), group->name.size())) {
            if (kDebugTableNoisy) {
                printf("Skipping package group: %s\n", String8(group->name).string());
            }
            continue;
        }

        const size_t packageCount = group->packages.size();
        for (size_t pi = 0; pi < packageCount; pi++) {
            const char16_t* targetType = type;
            size_t targetTypeLen = typeLen;

            do {
                ssize_t ti = group->packages[pi]->typeStrings.indexOfString(
                        targetType, targetTypeLen);
                if (ti < 0) {
                    continue;
                }

                ti += group->packages[pi]->typeIdOffset;

                const uint32_t identifier = findEntry(group, ti, name, nameLen,
                        outTypeSpecFlags);
                if (identifier != 0) {
                    if (fakePublic && outTypeSpecFlags) {
                        *outTypeSpecFlags |= ResTable_typeSpec::SPEC_PUBLIC;
                    }
                    return identifier;
                }
            } while (strzcmp16(attr.string(), attr.size(), targetType, targetTypeLen) == 0
                    && (targetType = attrPrivate.string())
                    && (targetTypeLen = attrPrivate.size())
            );
        }
        break;
    }
    return 0;
}

uint32_t ResTable::findEntry(const PackageGroup* group, ssize_t typeIndex, const char16_t* name,
        size_t nameLen, uint32_t* outTypeSpecFlags) const {
    const TypeList& typeList = group->types[typeIndex];
    const size_t typeCount = typeList.size();
    for (size_t i = 0; i < typeCount; i++) {
        const Type* t = typeList[i];
        const ssize_t ei = t->package->keyStrings.indexOfString(name, nameLen);
        if (ei < 0) {
            continue;
        }

        const size_t configCount = t->configs.size();
        for (size_t j = 0; j < configCount; j++) {
            const TypeVariant tv(t->configs[j]);
            for (TypeVariant::iterator iter = tv.beginEntries();
                 iter != tv.endEntries();
                 iter++) {
                const ResTable_entry* entry = *iter;
                if (entry == NULL) {
                    continue;
                }

                if (dtohl(entry->key.index) == (size_t) ei) {
                    uint32_t resId = Res_MAKEID(group->id - 1, typeIndex, iter.index());
                    if (outTypeSpecFlags) {
                        Entry result;
                        if (getEntry(group, typeIndex, iter.index(), NULL, &result) != NO_ERROR) {
                            ALOGW("Failed to find spec flags for 0x%08x", resId);
                            return 0;
                        }
                        *outTypeSpecFlags = result.specFlags;
                    }
                    return resId;
                }
            }
        }
    }
    return 0;
}

bool ResTable::expandResourceRef(const char16_t* refStr, size_t refLen,
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

bool U16StringToInt(const char16_t* s, size_t len, Res_value* outValue)
{
    while (len > 0 && isspace16(*s)) {
        s++;
        len--;
    }

    if (len <= 0) {
        return false;
    }

    size_t i = 0;
    int64_t val = 0;
    bool neg = false;

    if (*s == '-') {
        neg = true;
        i++;
    }

    if (s[i] < '0' || s[i] > '9') {
        return false;
    }

    static_assert(std::is_same<uint32_t, Res_value::data_type>::value,
                  "Res_value::data_type has changed. The range checks in this "
                  "function are no longer correct.");

    // Decimal or hex?
    bool isHex;
    if (len > 1 && s[i] == '0' && s[i+1] == 'x') {
        isHex = true;
        i += 2;

        if (neg) {
            return false;
        }

        if (i == len) {
            // Just u"0x"
            return false;
        }

        bool error = false;
        while (i < len && !error) {
            val = (val*16) + get_hex(s[i], &error);
            i++;

            if (val > std::numeric_limits<uint32_t>::max()) {
                return false;
            }
        }
        if (error) {
            return false;
        }
    } else {
        isHex = false;
        while (i < len) {
            if (s[i] < '0' || s[i] > '9') {
                return false;
            }
            val = (val*10) + s[i]-'0';
            i++;

            if ((neg && -val < std::numeric_limits<int32_t>::min()) ||
                (!neg && val > std::numeric_limits<int32_t>::max())) {
                return false;
            }
        }
    }

    if (neg) val = -val;

    while (i < len && isspace16(s[i])) {
        i++;
    }

    if (i != len) {
        return false;
    }

    if (outValue) {
        outValue->dataType =
            isHex ? outValue->TYPE_INT_HEX : outValue->TYPE_INT_DEC;
        outValue->data = static_cast<Res_value::data_type>(val);
    }
    return true;
}

bool ResTable::stringToInt(const char16_t* s, size_t len, Res_value* outValue)
{
    return U16StringToInt(s, len, outValue);
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
    if ((buf[0] < '0' || buf[0] > '9') && buf[0] != '.' && buf[0] != '-' && buf[0] != '+') {
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
            // Special case @null as undefined. This will be converted by
            // AssetManager to TYPE_NULL with data DATA_NULL_UNDEFINED.
            outValue->data = 0;
            return true;
        } else if (len == 6 && s[1]=='e' && s[2]=='m' && s[3]=='p' && s[4]=='t' && s[5]=='y') {
            // Special case @empty as explicitly defined empty value.
            outValue->dataType = Res_value::TYPE_NULL;
            outValue->data = Res_value::DATA_NULL_EMPTY;
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
                    if (accessor == NULL || accessor->getAssetsPackage() != package) {
                        if ((specFlags&ResTable_typeSpec::SPEC_PUBLIC) == 0) {
                            if (accessor != NULL) {
                                accessor->reportError(accessorCookie, "Resource is not public.");
                            }
                            return false;
                        }
                    }
                }

                if (accessor) {
                    rid = Res_MAKEID(
                        accessor->getRemappedPackage(Res_GETPACKAGE(rid)),
                        Res_GETTYPE(rid), Res_GETENTRY(rid));
                    if (kDebugTableNoisy) {
                        ALOGI("Incl %s:%s/%s: 0x%08x\n",
                                String8(package).string(), String8(type).string(),
                                String8(name).string(), rid);
                    }
                }

                uint32_t packageId = Res_GETPACKAGE(rid) + 1;
                if (packageId != APP_PACKAGE_ID && packageId != SYS_PACKAGE_ID) {
                    outValue->dataType = Res_value::TYPE_DYNAMIC_REFERENCE;
                }
                outValue->data = rid;
                return true;
            }

            if (accessor) {
                uint32_t rid = accessor->getCustomResourceWithCreation(package, type, name,
                                                                       createIfNotFound);
                if (rid != 0) {
                    if (kDebugTableNoisy) {
                        ALOGI("Pckg %s:%s/%s: 0x%08x\n",
                                String8(package).string(), String8(type).string(),
                                String8(name).string(), rid);
                    }
                    uint32_t packageId = Res_GETPACKAGE(rid) + 1;
                    if (packageId == 0x00) {
                        outValue->data = rid;
                        outValue->dataType = Res_value::TYPE_DYNAMIC_REFERENCE;
                        return true;
                    } else if (packageId == APP_PACKAGE_ID || packageId == SYS_PACKAGE_ID) {
                        // We accept packageId's generated as 0x01 in order to support
                        // building the android system resources
                        outValue->data = rid;
                        return true;
                    }
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

            if (accessor) {
                rid = Res_MAKEID(
                    accessor->getRemappedPackage(Res_GETPACKAGE(rid)),
                    Res_GETTYPE(rid), Res_GETENTRY(rid));
            }

            uint32_t packageId = Res_GETPACKAGE(rid) + 1;
            if (packageId != APP_PACKAGE_ID && packageId != SYS_PACKAGE_ID) {
                outValue->dataType = Res_value::TYPE_DYNAMIC_ATTRIBUTE;
            }
            outValue->data = rid;
            return true;
        }

        if (accessor) {
            uint32_t rid = accessor->getCustomResource(package, type, name);
            if (rid != 0) {
                uint32_t packageId = Res_GETPACKAGE(rid) + 1;
                if (packageId == 0x00) {
                    outValue->data = rid;
                    outValue->dataType = Res_value::TYPE_DYNAMIC_ATTRIBUTE;
                    return true;
                } else if (packageId == APP_PACKAGE_ID || packageId == SYS_PACKAGE_ID) {
                    // We accept packageId's generated as 0x01 in order to support
                    // building the android system resources
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
                    if (getResourceName(bag->map.name.ident, false, &rname)) {
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
                        if (getResourceName(bagi->map.name.ident, false, &rname)) {
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

const String16 ResTable::getBasePackageName(size_t idx) const
{
    if (mError != NO_ERROR) {
        return String16();
    }
    LOG_FATAL_IF(idx >= mPackageGroups.size(),
                 "Requested package index %d past package count %d",
                 (int)idx, (int)mPackageGroups.size());
    return mPackageGroups[idx]->name;
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

uint32_t ResTable::getLastTypeIdForPackage(size_t idx) const
{
    if (mError != NO_ERROR) {
        return 0;
    }
    LOG_FATAL_IF(idx >= mPackageGroups.size(),
            "Requested package index %d past package count %d",
            (int)idx, (int)mPackageGroups.size());
    const PackageGroup* const group = mPackageGroups[idx];
    return group->largestTypeId;
}

size_t ResTable::getTableCount() const
{
    return mHeaders.size();
}

const ResStringPool* ResTable::getTableStringBlock(size_t index) const
{
    return &mHeaders[index]->values;
}

int32_t ResTable::getTableCookie(size_t index) const
{
    return mHeaders[index]->cookie;
}

const DynamicRefTable* ResTable::getDynamicRefTableForCookie(int32_t cookie) const
{
    const size_t N = mPackageGroups.size();
    for (size_t i = 0; i < N; i++) {
        const PackageGroup* pg = mPackageGroups[i];
        size_t M = pg->packages.size();
        for (size_t j = 0; j < M; j++) {
            if (pg->packages[j]->header->cookie == cookie) {
                return &pg->dynamicRefTable;
            }
        }
    }
    return NULL;
}

static bool compareResTableConfig(const ResTable_config& a, const ResTable_config& b) {
    return a.compare(b) < 0;
}

template <typename Func>
void ResTable::forEachConfiguration(bool ignoreMipmap, bool ignoreAndroidPackage,
                                    bool includeSystemConfigs, const Func& f) const {
    const size_t packageCount = mPackageGroups.size();
    const String16 android("android");
    for (size_t i = 0; i < packageCount; i++) {
        const PackageGroup* packageGroup = mPackageGroups[i];
        if (ignoreAndroidPackage && android == packageGroup->name) {
            continue;
        }
        if (!includeSystemConfigs && packageGroup->isSystemAsset) {
            continue;
        }
        const size_t typeCount = packageGroup->types.size();
        for (size_t j = 0; j < typeCount; j++) {
            const TypeList& typeList = packageGroup->types[j];
            const size_t numTypes = typeList.size();
            for (size_t k = 0; k < numTypes; k++) {
                const Type* type = typeList[k];
                const ResStringPool& typeStrings = type->package->typeStrings;
                if (ignoreMipmap && typeStrings.string8ObjectAt(
                            type->typeSpec->id - 1) == "mipmap") {
                    continue;
                }

                const size_t numConfigs = type->configs.size();
                for (size_t m = 0; m < numConfigs; m++) {
                    const ResTable_type* config = type->configs[m];
                    ResTable_config cfg;
                    memset(&cfg, 0, sizeof(ResTable_config));
                    cfg.copyFromDtoH(config->config);

                    f(cfg);
                }
            }
        }
    }
}

void ResTable::getConfigurations(Vector<ResTable_config>* configs, bool ignoreMipmap,
                                 bool ignoreAndroidPackage, bool includeSystemConfigs) const {
    auto func = [&](const ResTable_config& cfg) {
        const auto beginIter = configs->begin();
        const auto endIter = configs->end();

        auto iter = std::lower_bound(beginIter, endIter, cfg, compareResTableConfig);
        if (iter == endIter || iter->compare(cfg) != 0) {
            configs->insertAt(cfg, std::distance(beginIter, iter));
        }
    };
    forEachConfiguration(ignoreMipmap, ignoreAndroidPackage, includeSystemConfigs, func);
}

static bool compareString8AndCString(const String8& str, const char* cStr) {
    return strcmp(str.string(), cStr) < 0;
}

void ResTable::getLocales(Vector<String8>* locales, bool includeSystemLocales) const {
    char locale[RESTABLE_MAX_LOCALE_LEN];

    forEachConfiguration(false, false, includeSystemLocales, [&](const ResTable_config& cfg) {
        if (cfg.locale != 0) {
            cfg.getBcp47Locale(locale);

            const auto beginIter = locales->begin();
            const auto endIter = locales->end();

            auto iter = std::lower_bound(beginIter, endIter, locale, compareString8AndCString);
            if (iter == endIter || strcmp(iter->string(), locale) != 0) {
                locales->insertAt(String8(locale), std::distance(beginIter, iter));
            }
        }
    });
}

StringPoolRef::StringPoolRef(const ResStringPool* pool, uint32_t index)
    : mPool(pool), mIndex(index) {}

StringPoolRef::StringPoolRef()
    : mPool(NULL), mIndex(0) {}

const char* StringPoolRef::string8(size_t* outLen) const {
    if (mPool != NULL) {
        return mPool->string8At(mIndex, outLen);
    }
    if (outLen != NULL) {
        *outLen = 0;
    }
    return NULL;
}

const char16_t* StringPoolRef::string16(size_t* outLen) const {
    if (mPool != NULL) {
        return mPool->stringAt(mIndex, outLen);
    }
    if (outLen != NULL) {
        *outLen = 0;
    }
    return NULL;
}

bool ResTable::getResourceFlags(uint32_t resID, uint32_t* outFlags) const {
    if (mError != NO_ERROR) {
        return false;
    }

    const ssize_t p = getResourcePackageIndex(resID);
    const int t = Res_GETTYPE(resID);
    const int e = Res_GETENTRY(resID);

    if (p < 0) {
        if (Res_GETPACKAGE(resID)+1 == 0) {
            ALOGW("No package identifier when getting flags for resource number 0x%08x", resID);
        } else {
            ALOGW("No known package when getting flags for resource number 0x%08x", resID);
        }
        return false;
    }
    if (t < 0) {
        ALOGW("No type identifier when getting flags for resource number 0x%08x", resID);
        return false;
    }

    const PackageGroup* const grp = mPackageGroups[p];
    if (grp == NULL) {
        ALOGW("Bad identifier when getting flags for resource number 0x%08x", resID);
        return false;
    }

    Entry entry;
    status_t err = getEntry(grp, t, e, NULL, &entry);
    if (err != NO_ERROR) {
        return false;
    }

    *outFlags = entry.specFlags;
    return true;
}

status_t ResTable::getEntry(
        const PackageGroup* packageGroup, int typeIndex, int entryIndex,
        const ResTable_config* config,
        Entry* outEntry) const
{
    const TypeList& typeList = packageGroup->types[typeIndex];
    if (typeList.isEmpty()) {
        ALOGV("Skipping entry type index 0x%02x because type is NULL!\n", typeIndex);
        return BAD_TYPE;
    }

    const ResTable_type* bestType = NULL;
    uint32_t bestOffset = ResTable_type::NO_ENTRY;
    const Package* bestPackage = NULL;
    uint32_t specFlags = 0;
    uint8_t actualTypeIndex = typeIndex;
    ResTable_config bestConfig;
    memset(&bestConfig, 0, sizeof(bestConfig));

    // Iterate over the Types of each package.
    const size_t typeCount = typeList.size();
    for (size_t i = 0; i < typeCount; i++) {
        const Type* const typeSpec = typeList[i];

        int realEntryIndex = entryIndex;
        int realTypeIndex = typeIndex;
        bool currentTypeIsOverlay = false;

        // Runtime overlay packages provide a mapping of app resource
        // ID to package resource ID.
        if (typeSpec->idmapEntries.hasEntries()) {
            uint16_t overlayEntryIndex;
            if (typeSpec->idmapEntries.lookup(entryIndex, &overlayEntryIndex) != NO_ERROR) {
                // No such mapping exists
                continue;
            }
            realEntryIndex = overlayEntryIndex;
            realTypeIndex = typeSpec->idmapEntries.overlayTypeId() - 1;
            currentTypeIsOverlay = true;
        }

        if (static_cast<size_t>(realEntryIndex) >= typeSpec->entryCount) {
            ALOGV("For resource 0x%08x, entry index(%d) is beyond type entryCount(%d)",
                    Res_MAKEID(packageGroup->id - 1, typeIndex, entryIndex),
                    entryIndex, static_cast<int>(typeSpec->entryCount));
            // We should normally abort here, but some legacy apps declare
            // resources in the 'android' package (old bug in AAPT).
            continue;
        }

        // Aggregate all the flags for each package that defines this entry.
        if (typeSpec->typeSpecFlags != NULL) {
            specFlags |= dtohl(typeSpec->typeSpecFlags[realEntryIndex]);
        } else {
            specFlags = -1;
        }

        const Vector<const ResTable_type*>* candidateConfigs = &typeSpec->configs;

        std::shared_ptr<Vector<const ResTable_type*>> filteredConfigs;
        if (config && memcmp(&mParams, config, sizeof(mParams)) == 0) {
            // Grab the lock first so we can safely get the current filtered list.
            AutoMutex _lock(mFilteredConfigLock);

            // This configuration is equal to the one we have previously cached for,
            // so use the filtered configs.

            const TypeCacheEntry& cacheEntry = packageGroup->typeCacheEntries[typeIndex];
            if (i < cacheEntry.filteredConfigs.size()) {
                if (cacheEntry.filteredConfigs[i]) {
                    // Grab a reference to the shared_ptr so it doesn't get destroyed while
                    // going through this list.
                    filteredConfigs = cacheEntry.filteredConfigs[i];

                    // Use this filtered list.
                    candidateConfigs = filteredConfigs.get();
                }
            }
        }

        const size_t numConfigs = candidateConfigs->size();
        for (size_t c = 0; c < numConfigs; c++) {
            const ResTable_type* const thisType = candidateConfigs->itemAt(c);
            if (thisType == NULL) {
                continue;
            }

            ResTable_config thisConfig;
            thisConfig.copyFromDtoH(thisType->config);

            // Check to make sure this one is valid for the current parameters.
            if (config != NULL && !thisConfig.match(*config)) {
                continue;
            }

            // Check if there is the desired entry in this type.
            const uint32_t* const eindex = reinterpret_cast<const uint32_t*>(
                    reinterpret_cast<const uint8_t*>(thisType) + dtohs(thisType->header.headerSize));

            uint32_t thisOffset = dtohl(eindex[realEntryIndex]);
            if (thisOffset == ResTable_type::NO_ENTRY) {
                // There is no entry for this index and configuration.
                continue;
            }

            if (bestType != NULL) {
                // Check if this one is less specific than the last found.  If so,
                // we will skip it.  We check starting with things we most care
                // about to those we least care about.
                if (!thisConfig.isBetterThan(bestConfig, config)) {
                    if (!currentTypeIsOverlay || thisConfig.compare(bestConfig) != 0) {
                        continue;
                    }
                }
            }

            bestType = thisType;
            bestOffset = thisOffset;
            bestConfig = thisConfig;
            bestPackage = typeSpec->package;
            actualTypeIndex = realTypeIndex;

            // If no config was specified, any type will do, so skip
            if (config == NULL) {
                break;
            }
        }
    }

    if (bestType == NULL) {
        return BAD_INDEX;
    }

    bestOffset += dtohl(bestType->entriesStart);

    if (bestOffset > (dtohl(bestType->header.size)-sizeof(ResTable_entry))) {
        ALOGW("ResTable_entry at 0x%x is beyond type chunk data 0x%x",
                bestOffset, dtohl(bestType->header.size));
        return BAD_TYPE;
    }
    if ((bestOffset & 0x3) != 0) {
        ALOGW("ResTable_entry at 0x%x is not on an integer boundary", bestOffset);
        return BAD_TYPE;
    }

    const ResTable_entry* const entry = reinterpret_cast<const ResTable_entry*>(
            reinterpret_cast<const uint8_t*>(bestType) + bestOffset);
    if (dtohs(entry->size) < sizeof(*entry)) {
        ALOGW("ResTable_entry size 0x%x is too small", dtohs(entry->size));
        return BAD_TYPE;
    }

    if (outEntry != NULL) {
        outEntry->entry = entry;
        outEntry->config = bestConfig;
        outEntry->type = bestType;
        outEntry->specFlags = specFlags;
        outEntry->package = bestPackage;
        outEntry->typeStr = StringPoolRef(&bestPackage->typeStrings, actualTypeIndex - bestPackage->typeIdOffset);
        outEntry->keyStr = StringPoolRef(&bestPackage->keyStrings, dtohl(entry->key.index));
    }
    return NO_ERROR;
}

status_t ResTable::parsePackage(const ResTable_package* const pkg,
                                const Header* const header, bool appAsLib, bool isSystemAsset)
{
    const uint8_t* base = (const uint8_t*)pkg;
    status_t err = validate_chunk(&pkg->header, sizeof(*pkg) - sizeof(pkg->typeIdOffset),
                                  header->dataEnd, "ResTable_package");
    if (err != NO_ERROR) {
        return (mError=err);
    }

    const uint32_t pkgSize = dtohl(pkg->header.size);

    if (dtohl(pkg->typeStrings) >= pkgSize) {
        ALOGW("ResTable_package type strings at 0x%x are past chunk size 0x%x.",
             dtohl(pkg->typeStrings), pkgSize);
        return (mError=BAD_TYPE);
    }
    if ((dtohl(pkg->typeStrings)&0x3) != 0) {
        ALOGW("ResTable_package type strings at 0x%x is not on an integer boundary.",
             dtohl(pkg->typeStrings));
        return (mError=BAD_TYPE);
    }
    if (dtohl(pkg->keyStrings) >= pkgSize) {
        ALOGW("ResTable_package key strings at 0x%x are past chunk size 0x%x.",
             dtohl(pkg->keyStrings), pkgSize);
        return (mError=BAD_TYPE);
    }
    if ((dtohl(pkg->keyStrings)&0x3) != 0) {
        ALOGW("ResTable_package key strings at 0x%x is not on an integer boundary.",
             dtohl(pkg->keyStrings));
        return (mError=BAD_TYPE);
    }

    uint32_t id = dtohl(pkg->id);
    KeyedVector<uint8_t, IdmapEntries> idmapEntries;

    if (header->resourceIDMap != NULL) {
        uint8_t targetPackageId = 0;
        status_t err = parseIdmap(header->resourceIDMap, header->resourceIDMapSize, &targetPackageId, &idmapEntries);
        if (err != NO_ERROR) {
            ALOGW("Overlay is broken");
            return (mError=err);
        }
        id = targetPackageId;
    }

    if (id >= 256) {
        LOG_ALWAYS_FATAL("Package id out of range");
        return NO_ERROR;
    } else if (id == 0 || (id == 0x7f && appAsLib) || isSystemAsset) {
        // This is a library or a system asset, so assign an ID
        id = mNextPackageId++;
    }

    PackageGroup* group = NULL;
    Package* package = new Package(this, header, pkg);
    if (package == NULL) {
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

    size_t idx = mPackageMap[id];
    if (idx == 0) {
        idx = mPackageGroups.size() + 1;

        char16_t tmpName[sizeof(pkg->name)/sizeof(pkg->name[0])];
        strcpy16_dtoh(tmpName, pkg->name, sizeof(pkg->name)/sizeof(pkg->name[0]));
        group = new PackageGroup(this, String16(tmpName), id, appAsLib, isSystemAsset);
        if (group == NULL) {
            delete package;
            return (mError=NO_MEMORY);
        }

        err = mPackageGroups.add(group);
        if (err < NO_ERROR) {
            return (mError=err);
        }

        mPackageMap[id] = static_cast<uint8_t>(idx);

        // Find all packages that reference this package
        size_t N = mPackageGroups.size();
        for (size_t i = 0; i < N; i++) {
            mPackageGroups[i]->dynamicRefTable.addMapping(
                    group->name, static_cast<uint8_t>(group->id));
        }
    } else {
        group = mPackageGroups.itemAt(idx - 1);
        if (group == NULL) {
            return (mError=UNKNOWN_ERROR);
        }
    }

    err = group->packages.add(package);
    if (err < NO_ERROR) {
        return (mError=err);
    }

    // Iterate through all chunks.
    const ResChunk_header* chunk =
        (const ResChunk_header*)(((const uint8_t*)pkg)
                                 + dtohs(pkg->header.headerSize));
    const uint8_t* endPos = ((const uint8_t*)pkg) + dtohs(pkg->header.size);
    while (((const uint8_t*)chunk) <= (endPos-sizeof(ResChunk_header)) &&
           ((const uint8_t*)chunk) <= (endPos-dtohl(chunk->size))) {
        if (kDebugTableNoisy) {
            ALOGV("PackageChunk: type=0x%x, headerSize=0x%x, size=0x%x, pos=%p\n",
                    dtohs(chunk->type), dtohs(chunk->headerSize), dtohl(chunk->size),
                    (void*)(((const uint8_t*)chunk) - ((const uint8_t*)header->header)));
        }
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
            const size_t newEntryCount = dtohl(typeSpec->entryCount);

            if (kDebugLoadTableNoisy) {
                ALOGI("TypeSpec off %p: type=0x%x, headerSize=0x%x, size=%p\n",
                        (void*)(base-(const uint8_t*)chunk),
                        dtohs(typeSpec->header.type),
                        dtohs(typeSpec->header.headerSize),
                        (void*)typeSpecSize);
            }
            // look for block overrun or int overflow when multiplying by 4
            if ((dtohl(typeSpec->entryCount) > (INT32_MAX/sizeof(uint32_t))
                    || dtohs(typeSpec->header.headerSize)+(sizeof(uint32_t)*newEntryCount)
                    > typeSpecSize)) {
                ALOGW("ResTable_typeSpec entry index to %p extends beyond chunk end %p.",
                        (void*)(dtohs(typeSpec->header.headerSize) + (sizeof(uint32_t)*newEntryCount)),
                        (void*)typeSpecSize);
                return (mError=BAD_TYPE);
            }

            if (typeSpec->id == 0) {
                ALOGW("ResTable_type has an id of 0.");
                return (mError=BAD_TYPE);
            }

            if (newEntryCount > 0) {
                uint8_t typeIndex = typeSpec->id - 1;
                ssize_t idmapIndex = idmapEntries.indexOfKey(typeSpec->id);
                if (idmapIndex >= 0) {
                    typeIndex = idmapEntries[idmapIndex].targetTypeId() - 1;
                }

                TypeList& typeList = group->types.editItemAt(typeIndex);
                if (!typeList.isEmpty()) {
                    const Type* existingType = typeList[0];
                    if (existingType->entryCount != newEntryCount && idmapIndex < 0) {
                        ALOGV("ResTable_typeSpec entry count inconsistent: given %d, previously %d",
                                (int) newEntryCount, (int) existingType->entryCount);
                        // We should normally abort here, but some legacy apps declare
                        // resources in the 'android' package (old bug in AAPT).
                    }
                }

                Type* t = new Type(header, package, newEntryCount);
                t->typeSpec = typeSpec;
                t->typeSpecFlags = (const uint32_t*)(
                        ((const uint8_t*)typeSpec) + dtohs(typeSpec->header.headerSize));
                if (idmapIndex >= 0) {
                    t->idmapEntries = idmapEntries[idmapIndex];
                }
                typeList.add(t);
                group->largestTypeId = max(group->largestTypeId, typeSpec->id);
            } else {
                ALOGV("Skipping empty ResTable_typeSpec for type %d", typeSpec->id);
            }

        } else if (ctype == RES_TABLE_TYPE_TYPE) {
            const ResTable_type* type = (const ResTable_type*)(chunk);
            err = validate_chunk(&type->header, sizeof(*type)-sizeof(ResTable_config)+4,
                                 endPos, "ResTable_type");
            if (err != NO_ERROR) {
                return (mError=err);
            }

            const uint32_t typeSize = dtohl(type->header.size);
            const size_t newEntryCount = dtohl(type->entryCount);

            if (kDebugLoadTableNoisy) {
                printf("Type off %p: type=0x%x, headerSize=0x%x, size=%u\n",
                        (void*)(base-(const uint8_t*)chunk),
                        dtohs(type->header.type),
                        dtohs(type->header.headerSize),
                        typeSize);
            }
            if (dtohs(type->header.headerSize)+(sizeof(uint32_t)*newEntryCount) > typeSize) {
                ALOGW("ResTable_type entry index to %p extends beyond chunk end 0x%x.",
                        (void*)(dtohs(type->header.headerSize) + (sizeof(uint32_t)*newEntryCount)),
                        typeSize);
                return (mError=BAD_TYPE);
            }

            if (newEntryCount != 0
                && dtohl(type->entriesStart) > (typeSize-sizeof(ResTable_entry))) {
                ALOGW("ResTable_type entriesStart at 0x%x extends beyond chunk end 0x%x.",
                     dtohl(type->entriesStart), typeSize);
                return (mError=BAD_TYPE);
            }

            if (type->id == 0) {
                ALOGW("ResTable_type has an id of 0.");
                return (mError=BAD_TYPE);
            }

            if (newEntryCount > 0) {
                uint8_t typeIndex = type->id - 1;
                ssize_t idmapIndex = idmapEntries.indexOfKey(type->id);
                if (idmapIndex >= 0) {
                    typeIndex = idmapEntries[idmapIndex].targetTypeId() - 1;
                }

                TypeList& typeList = group->types.editItemAt(typeIndex);
                if (typeList.isEmpty()) {
                    ALOGE("No TypeSpec for type %d", type->id);
                    return (mError=BAD_TYPE);
                }

                Type* t = typeList.editItemAt(typeList.size() - 1);
                if (newEntryCount != t->entryCount) {
                    ALOGE("ResTable_type entry count inconsistent: given %d, previously %d",
                        (int)newEntryCount, (int)t->entryCount);
                    return (mError=BAD_TYPE);
                }

                if (t->package != package) {
                    ALOGE("No TypeSpec for type %d", type->id);
                    return (mError=BAD_TYPE);
                }

                t->configs.add(type);

                if (kDebugTableGetEntry) {
                    ResTable_config thisConfig;
                    thisConfig.copyFromDtoH(type->config);
                    ALOGI("Adding config to type %d: %s\n", type->id,
                            thisConfig.toString().string());
                }
            } else {
                ALOGV("Skipping empty ResTable_type for type %d", type->id);
            }

        } else if (ctype == RES_TABLE_LIBRARY_TYPE) {
            if (group->dynamicRefTable.entries().size() == 0) {
                status_t err = group->dynamicRefTable.load((const ResTable_lib_header*) chunk);
                if (err != NO_ERROR) {
                    return (mError=err);
                }

                // Fill in the reference table with the entries we already know about.
                size_t N = mPackageGroups.size();
                for (size_t i = 0; i < N; i++) {
                    group->dynamicRefTable.addMapping(mPackageGroups[i]->name, mPackageGroups[i]->id);
                }
            } else {
                ALOGW("Found multiple library tables, ignoring...");
            }
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

    return NO_ERROR;
}

DynamicRefTable::DynamicRefTable(uint8_t packageId, bool appAsLib)
    : mAssignedPackageId(packageId)
    , mAppAsLib(appAsLib)
{
    memset(mLookupTable, 0, sizeof(mLookupTable));

    // Reserved package ids
    mLookupTable[APP_PACKAGE_ID] = APP_PACKAGE_ID;
    mLookupTable[SYS_PACKAGE_ID] = SYS_PACKAGE_ID;
}

status_t DynamicRefTable::load(const ResTable_lib_header* const header)
{
    const uint32_t entryCount = dtohl(header->count);
    const uint32_t sizeOfEntries = sizeof(ResTable_lib_entry) * entryCount;
    const uint32_t expectedSize = dtohl(header->header.size) - dtohl(header->header.headerSize);
    if (sizeOfEntries > expectedSize) {
        ALOGE("ResTable_lib_header size %u is too small to fit %u entries (x %u).",
                expectedSize, entryCount, (uint32_t)sizeof(ResTable_lib_entry));
        return UNKNOWN_ERROR;
    }

    const ResTable_lib_entry* entry = (const ResTable_lib_entry*)(((uint8_t*) header) +
            dtohl(header->header.headerSize));
    for (uint32_t entryIndex = 0; entryIndex < entryCount; entryIndex++) {
        uint32_t packageId = dtohl(entry->packageId);
        char16_t tmpName[sizeof(entry->packageName) / sizeof(char16_t)];
        strcpy16_dtoh(tmpName, entry->packageName, sizeof(entry->packageName) / sizeof(char16_t));
        if (kDebugLibNoisy) {
            ALOGV("Found lib entry %s with id %d\n", String8(tmpName).string(),
                    dtohl(entry->packageId));
        }
        if (packageId >= 256) {
            ALOGE("Bad package id 0x%08x", packageId);
            return UNKNOWN_ERROR;
        }
        mEntries.replaceValueFor(String16(tmpName), (uint8_t) packageId);
        entry = entry + 1;
    }
    return NO_ERROR;
}

status_t DynamicRefTable::addMappings(const DynamicRefTable& other) {
    if (mAssignedPackageId != other.mAssignedPackageId) {
        return UNKNOWN_ERROR;
    }

    const size_t entryCount = other.mEntries.size();
    for (size_t i = 0; i < entryCount; i++) {
        ssize_t index = mEntries.indexOfKey(other.mEntries.keyAt(i));
        if (index < 0) {
            mEntries.add(other.mEntries.keyAt(i), other.mEntries[i]);
        } else {
            if (other.mEntries[i] != mEntries[index]) {
                return UNKNOWN_ERROR;
            }
        }
    }

    // Merge the lookup table. No entry can conflict
    // (value of 0 means not set).
    for (size_t i = 0; i < 256; i++) {
        if (mLookupTable[i] != other.mLookupTable[i]) {
            if (mLookupTable[i] == 0) {
                mLookupTable[i] = other.mLookupTable[i];
            } else if (other.mLookupTable[i] != 0) {
                return UNKNOWN_ERROR;
            }
        }
    }
    return NO_ERROR;
}

status_t DynamicRefTable::addMapping(const String16& packageName, uint8_t packageId)
{
    ssize_t index = mEntries.indexOfKey(packageName);
    if (index < 0) {
        return UNKNOWN_ERROR;
    }
    mLookupTable[mEntries.valueAt(index)] = packageId;
    return NO_ERROR;
}

status_t DynamicRefTable::lookupResourceId(uint32_t* resId) const {
    uint32_t res = *resId;
    size_t packageId = Res_GETPACKAGE(res) + 1;

    if (packageId == APP_PACKAGE_ID && !mAppAsLib) {
        // No lookup needs to be done, app package IDs are absolute.
        return NO_ERROR;
    }

    if (packageId == 0 || (packageId == APP_PACKAGE_ID && mAppAsLib)) {
        // The package ID is 0x00. That means that a shared library is accessing
        // its own local resource.
        // Or if app resource is loaded as shared library, the resource which has
        // app package Id is local resources.
        // so we fix up those resources with the calling package ID.
        *resId = (0xFFFFFF & (*resId)) | (((uint32_t) mAssignedPackageId) << 24);
        return NO_ERROR;
    }

    // Do a proper lookup.
    uint8_t translatedId = mLookupTable[packageId];
    if (translatedId == 0) {
        ALOGV("DynamicRefTable(0x%02x): No mapping for build-time package ID 0x%02x.",
                (uint8_t)mAssignedPackageId, (uint8_t)packageId);
        for (size_t i = 0; i < 256; i++) {
            if (mLookupTable[i] != 0) {
                ALOGV("e[0x%02x] -> 0x%02x", (uint8_t)i, mLookupTable[i]);
            }
        }
        return UNKNOWN_ERROR;
    }

    *resId = (res & 0x00ffffff) | (((uint32_t) translatedId) << 24);
    return NO_ERROR;
}

status_t DynamicRefTable::lookupResourceValue(Res_value* value) const {
    uint8_t resolvedType = Res_value::TYPE_REFERENCE;
    switch (value->dataType) {
    case Res_value::TYPE_ATTRIBUTE:
        resolvedType = Res_value::TYPE_ATTRIBUTE;
        // fallthrough
    case Res_value::TYPE_REFERENCE:
        if (!mAppAsLib) {
            return NO_ERROR;
        }

        // If the package is loaded as shared library, the resource reference
        // also need to be fixed.
        break;
    case Res_value::TYPE_DYNAMIC_ATTRIBUTE:
        resolvedType = Res_value::TYPE_ATTRIBUTE;
        // fallthrough
    case Res_value::TYPE_DYNAMIC_REFERENCE:
        break;
    default:
        return NO_ERROR;
    }

    status_t err = lookupResourceId(&value->data);
    if (err != NO_ERROR) {
        return err;
    }

    value->dataType = resolvedType;
    return NO_ERROR;
}

struct IdmapTypeMap {
    ssize_t overlayTypeId;
    size_t entryOffset;
    Vector<uint32_t> entryMap;
};

status_t ResTable::createIdmap(const ResTable& overlay,
        uint32_t targetCrc, uint32_t overlayCrc,
        const char* targetPath, const char* overlayPath,
        void** outData, size_t* outSize) const
{
    // see README for details on the format of map
    if (mPackageGroups.size() == 0) {
        ALOGW("idmap: target package has no package groups, cannot create idmap\n");
        return UNKNOWN_ERROR;
    }

    if (mPackageGroups[0]->packages.size() == 0) {
        ALOGW("idmap: target package has no packages in its first package group, "
                "cannot create idmap\n");
        return UNKNOWN_ERROR;
    }

    KeyedVector<uint8_t, IdmapTypeMap> map;

    // overlaid packages are assumed to contain only one package group
    const PackageGroup* pg = mPackageGroups[0];

    // starting size is header
    *outSize = ResTable::IDMAP_HEADER_SIZE_BYTES;

    // target package id and number of types in map
    *outSize += 2 * sizeof(uint16_t);

    // overlay packages are assumed to contain only one package group
    const ResTable_package* overlayPackageStruct = overlay.mPackageGroups[0]->packages[0]->package;
    char16_t tmpName[sizeof(overlayPackageStruct->name)/sizeof(overlayPackageStruct->name[0])];
    strcpy16_dtoh(tmpName, overlayPackageStruct->name, sizeof(overlayPackageStruct->name)/sizeof(overlayPackageStruct->name[0]));
    const String16 overlayPackage(tmpName);

    for (size_t typeIndex = 0; typeIndex < pg->types.size(); ++typeIndex) {
        const TypeList& typeList = pg->types[typeIndex];
        if (typeList.isEmpty()) {
            continue;
        }

        const Type* typeConfigs = typeList[0];

        IdmapTypeMap typeMap;
        typeMap.overlayTypeId = -1;
        typeMap.entryOffset = 0;

        for (size_t entryIndex = 0; entryIndex < typeConfigs->entryCount; ++entryIndex) {
            uint32_t resID = Res_MAKEID(pg->id - 1, typeIndex, entryIndex);
            resource_name resName;
            if (!this->getResourceName(resID, false, &resName)) {
                if (typeMap.entryMap.isEmpty()) {
                    typeMap.entryOffset++;
                }
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
            if (overlayResID == 0) {
                if (typeMap.entryMap.isEmpty()) {
                    typeMap.entryOffset++;
                }
                continue;
            }

            if (typeMap.overlayTypeId == -1) {
                typeMap.overlayTypeId = Res_GETTYPE(overlayResID) + 1;
            }

            if (Res_GETTYPE(overlayResID) + 1 != static_cast<size_t>(typeMap.overlayTypeId)) {
                ALOGE("idmap: can't mix type ids in entry map. Resource 0x%08x maps to 0x%08x"
                        " but entries should map to resources of type %02zx",
                        resID, overlayResID, typeMap.overlayTypeId);
                return BAD_TYPE;
            }

            if (typeMap.entryOffset + typeMap.entryMap.size() < entryIndex) {
                // pad with 0xffffffff's (indicating non-existing entries) before adding this entry
                size_t index = typeMap.entryMap.size();
                size_t numItems = entryIndex - (typeMap.entryOffset + index);
                if (typeMap.entryMap.insertAt(0xffffffff, index, numItems) < 0) {
                    return NO_MEMORY;
                }
            }
            typeMap.entryMap.add(Res_GETENTRY(overlayResID));
        }

        if (!typeMap.entryMap.isEmpty()) {
            if (map.add(static_cast<uint8_t>(typeIndex), typeMap) < 0) {
                return NO_MEMORY;
            }
            *outSize += (4 * sizeof(uint16_t)) + (typeMap.entryMap.size() * sizeof(uint32_t));
        }
    }

    if (map.isEmpty()) {
        ALOGW("idmap: no resources in overlay package present in base package");
        return UNKNOWN_ERROR;
    }

    if ((*outData = malloc(*outSize)) == NULL) {
        return NO_MEMORY;
    }

    uint32_t* data = (uint32_t*)*outData;
    *data++ = htodl(IDMAP_MAGIC);
    *data++ = htodl(IDMAP_CURRENT_VERSION);
    *data++ = htodl(targetCrc);
    *data++ = htodl(overlayCrc);
    const char* paths[] = { targetPath, overlayPath };
    for (int j = 0; j < 2; ++j) {
        char* p = (char*)data;
        const char* path = paths[j];
        const size_t I = strlen(path);
        if (I > 255) {
            ALOGV("path exceeds expected 255 characters: %s\n", path);
            return UNKNOWN_ERROR;
        }
        for (size_t i = 0; i < 256; ++i) {
            *p++ = i < I ? path[i] : '\0';
        }
        data += 256 / sizeof(uint32_t);
    }
    const size_t mapSize = map.size();
    uint16_t* typeData = reinterpret_cast<uint16_t*>(data);
    *typeData++ = htods(pg->id);
    *typeData++ = htods(mapSize);
    for (size_t i = 0; i < mapSize; ++i) {
        uint8_t targetTypeId = map.keyAt(i);
        const IdmapTypeMap& typeMap = map[i];
        *typeData++ = htods(targetTypeId + 1);
        *typeData++ = htods(typeMap.overlayTypeId);
        *typeData++ = htods(typeMap.entryMap.size());
        *typeData++ = htods(typeMap.entryOffset);

        const size_t entryCount = typeMap.entryMap.size();
        uint32_t* entries = reinterpret_cast<uint32_t*>(typeData);
        for (size_t j = 0; j < entryCount; j++) {
            entries[j] = htodl(typeMap.entryMap[j]);
        }
        typeData += entryCount * 2;
    }

    return NO_ERROR;
}

bool ResTable::getIdmapInfo(const void* idmap, size_t sizeBytes,
                            uint32_t* pVersion,
                            uint32_t* pTargetCrc, uint32_t* pOverlayCrc,
                            String8* pTargetPath, String8* pOverlayPath)
{
    const uint32_t* map = (const uint32_t*)idmap;
    if (!assertIdmapHeader(map, sizeBytes)) {
        return false;
    }
    if (pVersion) {
        *pVersion = dtohl(map[1]);
    }
    if (pTargetCrc) {
        *pTargetCrc = dtohl(map[2]);
    }
    if (pOverlayCrc) {
        *pOverlayCrc = dtohl(map[3]);
    }
    if (pTargetPath) {
        pTargetPath->setTo(reinterpret_cast<const char*>(map + 4));
    }
    if (pOverlayPath) {
        pOverlayPath->setTo(reinterpret_cast<const char*>(map + 4 + 256 / sizeof(uint32_t)));
    }
    return true;
}


#define CHAR16_TO_CSTR(c16, len) (String8(String16(c16,len)).string())

#define CHAR16_ARRAY_EQ(constant, var, len) \
        ((len == (sizeof(constant)/sizeof(constant[0]))) && (0 == memcmp((var), (constant), (len))))

static void print_complex(uint32_t complex, bool isFraction)
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
        if (value.data == Res_value::DATA_NULL_UNDEFINED) {
            printf("(null)\n");
        } else if (value.data == Res_value::DATA_NULL_EMPTY) {
            printf("(null empty)\n");
        } else {
            // This should never happen.
            printf("(null) 0x%08x\n", value.data);
        }
    } else if (value.dataType == Res_value::TYPE_REFERENCE) {
        printf("(reference) 0x%08x\n", value.data);
    } else if (value.dataType == Res_value::TYPE_DYNAMIC_REFERENCE) {
        printf("(dynamic reference) 0x%08x\n", value.data);
    } else if (value.dataType == Res_value::TYPE_ATTRIBUTE) {
        printf("(attribute) 0x%08x\n", value.data);
    } else if (value.dataType == Res_value::TYPE_DYNAMIC_ATTRIBUTE) {
        printf("(dynamic attribute) 0x%08x\n", value.data);
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
    size_t pgCount = mPackageGroups.size();
    printf("Package Groups (%d)\n", (int)pgCount);
    for (size_t pgIndex=0; pgIndex<pgCount; pgIndex++) {
        const PackageGroup* pg = mPackageGroups[pgIndex];
        printf("Package Group %d id=0x%02x packageCount=%d name=%s\n",
                (int)pgIndex, pg->id, (int)pg->packages.size(),
                String8(pg->name).string());

        const KeyedVector<String16, uint8_t>& refEntries = pg->dynamicRefTable.entries();
        const size_t refEntryCount = refEntries.size();
        if (refEntryCount > 0) {
            printf("  DynamicRefTable entryCount=%d:\n", (int) refEntryCount);
            for (size_t refIndex = 0; refIndex < refEntryCount; refIndex++) {
                printf("    0x%02x -> %s\n",
                        refEntries.valueAt(refIndex),
                        String8(refEntries.keyAt(refIndex)).string());
            }
            printf("\n");
        }

        int packageId = pg->id;
        size_t pkgCount = pg->packages.size();
        for (size_t pkgIndex=0; pkgIndex<pkgCount; pkgIndex++) {
            const Package* pkg = pg->packages[pkgIndex];
            // Use a package's real ID, since the ID may have been assigned
            // if this package is a shared library.
            packageId = pkg->package->id;
            char16_t tmpName[sizeof(pkg->package->name)/sizeof(pkg->package->name[0])];
            strcpy16_dtoh(tmpName, pkg->package->name, sizeof(pkg->package->name)/sizeof(pkg->package->name[0]));
            printf("  Package %d id=0x%02x name=%s\n", (int)pkgIndex,
                    pkg->package->id, String8(tmpName).string());
        }

        for (size_t typeIndex=0; typeIndex < pg->types.size(); typeIndex++) {
            const TypeList& typeList = pg->types[typeIndex];
            if (typeList.isEmpty()) {
                continue;
            }
            const Type* typeConfigs = typeList[0];
            const size_t NTC = typeConfigs->configs.size();
            printf("    type %d configCount=%d entryCount=%d\n",
                   (int)typeIndex, (int)NTC, (int)typeConfigs->entryCount);
            if (typeConfigs->typeSpecFlags != NULL) {
                for (size_t entryIndex=0; entryIndex<typeConfigs->entryCount; entryIndex++) {
                    uint32_t resID = (0xff000000 & ((packageId)<<24))
                                | (0x00ff0000 & ((typeIndex+1)<<16))
                                | (0x0000ffff & (entryIndex));
                    // Since we are creating resID without actually
                    // iterating over them, we have no idea which is a
                    // dynamic reference. We must check.
                    if (packageId == 0) {
                        pg->dynamicRefTable.lookupResourceId(&resID);
                    }

                    resource_name resName;
                    if (this->getResourceName(resID, true, &resName)) {
                        String8 type8;
                        String8 name8;
                        if (resName.type8 != NULL) {
                            type8 = String8(resName.type8, resName.typeLen);
                        } else {
                            type8 = String8(resName.type, resName.typeLen);
                        }
                        if (resName.name8 != NULL) {
                            name8 = String8(resName.name8, resName.nameLen);
                        } else {
                            name8 = String8(resName.name, resName.nameLen);
                        }
                        printf("      spec resource 0x%08x %s:%s/%s: flags=0x%08x\n",
                            resID,
                            CHAR16_TO_CSTR(resName.package, resName.packageLen),
                            type8.string(), name8.string(),
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

                // Always copy the config, as fields get added and we need to
                // set the defaults.
                ResTable_config thisConfig;
                thisConfig.copyFromDtoH(type->config);

                String8 configStr = thisConfig.toString();
                printf("      config %s:\n", configStr.size() > 0
                        ? configStr.string() : "(default)");
                size_t entryCount = dtohl(type->entryCount);
                uint32_t entriesStart = dtohl(type->entriesStart);
                if ((entriesStart&0x3) != 0) {
                    printf("      NON-INTEGER ResTable_type entriesStart OFFSET: 0x%x\n", entriesStart);
                    continue;
                }
                uint32_t typeSize = dtohl(type->header.size);
                if ((typeSize&0x3) != 0) {
                    printf("      NON-INTEGER ResTable_type header.size: 0x%x\n", typeSize);
                    continue;
                }
                for (size_t entryIndex=0; entryIndex<entryCount; entryIndex++) {
                    const uint32_t* const eindex = (const uint32_t*)
                        (((const uint8_t*)type) + dtohs(type->header.headerSize));

                    uint32_t thisOffset = dtohl(eindex[entryIndex]);
                    if (thisOffset == ResTable_type::NO_ENTRY) {
                        continue;
                    }

                    uint32_t resID = (0xff000000 & ((packageId)<<24))
                                | (0x00ff0000 & ((typeIndex+1)<<16))
                                | (0x0000ffff & (entryIndex));
                    if (packageId == 0) {
                        pg->dynamicRefTable.lookupResourceId(&resID);
                    }
                    resource_name resName;
                    if (this->getResourceName(resID, true, &resName)) {
                        String8 type8;
                        String8 name8;
                        if (resName.type8 != NULL) {
                            type8 = String8(resName.type8, resName.typeLen);
                        } else {
                            type8 = String8(resName.type, resName.typeLen);
                        }
                        if (resName.name8 != NULL) {
                            name8 = String8(resName.name8, resName.nameLen);
                        } else {
                            name8 = String8(resName.name, resName.nameLen);
                        }
                        printf("        resource 0x%08x %s:%s/%s: ", resID,
                                CHAR16_TO_CSTR(resName.package, resName.packageLen),
                                type8.string(), name8.string());
                    } else {
                        printf("        INVALID RESOURCE 0x%08x: ", resID);
                    }
                    if ((thisOffset&0x3) != 0) {
                        printf("NON-INTEGER OFFSET: 0x%x\n", thisOffset);
                        continue;
                    }
                    if ((thisOffset+sizeof(ResTable_entry)) > typeSize) {
                        printf("OFFSET OUT OF BOUNDS: 0x%x+0x%x (size is 0x%x)\n",
                               entriesStart, thisOffset, typeSize);
                        continue;
                    }

                    const ResTable_entry* ent = (const ResTable_entry*)
                        (((const uint8_t*)type) + entriesStart + thisOffset);
                    if (((entriesStart + thisOffset)&0x3) != 0) {
                        printf("NON-INTEGER ResTable_entry OFFSET: 0x%x\n",
                             (entriesStart + thisOffset));
                        continue;
                    }

                    uintptr_t esize = dtohs(ent->size);
                    if ((esize&0x3) != 0) {
                        printf("NON-INTEGER ResTable_entry SIZE: %p\n", (void *)esize);
                        continue;
                    }
                    if ((thisOffset+esize) > typeSize) {
                        printf("ResTable_entry OUT OF BOUNDS: 0x%x+0x%x+%p (size is 0x%x)\n",
                               entriesStart, thisOffset, (void *)esize, typeSize);
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
                            print_value(typeConfigs->package, value);
                        } else if (bagPtr != NULL) {
                            const int N = dtohl(bagPtr->count);
                            const uint8_t* baseMapPtr = (const uint8_t*)ent;
                            size_t mapOffset = esize;
                            const ResTable_map* mapPtr = (ResTable_map*)(baseMapPtr+mapOffset);
                            const uint32_t parent = dtohl(bagPtr->parent.ident);
                            uint32_t resolvedParent = parent;
                            if (Res_GETPACKAGE(resolvedParent) + 1 == 0) {
                                status_t err = pg->dynamicRefTable.lookupResourceId(&resolvedParent);
                                if (err != NO_ERROR) {
                                    resolvedParent = 0;
                                }
                            }
                            printf("          Parent=0x%08x(Resolved=0x%08x), Count=%d\n",
                                    parent, resolvedParent, N);
                            for (int i=0; i<N && mapOffset < (typeSize-sizeof(ResTable_map)); i++) {
                                printf("          #%i (Key=0x%08x): ",
                                    i, dtohl(mapPtr->name.ident));
                                value.copyFrom_dtoh(mapPtr->value);
                                print_value(typeConfigs->package, value);
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

}   // namespace android
