/*
 * Copyright (C) 2010 The Android Open Source Project
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

//#define LOG_NDEBUG 0
#define LOG_TAG "ID3"
#include <utils/Log.h>

#include "../include/ID3.h"

#include <media/stagefright/DataSource.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/Utils.h>
#include <utils/String8.h>
#include <byteswap.h>

namespace android {

static const size_t kMaxMetadataSize = 3 * 1024 * 1024;

ID3::ID3(const sp<DataSource> &source)
    : mIsValid(false),
      mData(NULL),
      mSize(0),
      mFirstFrameOffset(0),
      mVersion(ID3_UNKNOWN) {
    mIsValid = parseV2(source);

    if (!mIsValid) {
        mIsValid = parseV1(source);
    }
}

ID3::~ID3() {
    if (mData) {
        free(mData);
        mData = NULL;
    }
}

bool ID3::isValid() const {
    return mIsValid;
}

ID3::Version ID3::version() const {
    return mVersion;
}

// static
bool ID3::ParseSyncsafeInteger(const uint8_t encoded[4], size_t *x) {
    *x = 0;
    for (int32_t i = 0; i < 4; ++i) {
        if (encoded[i] & 0x80) {
            return false;
        }

        *x = ((*x) << 7) | encoded[i];
    }

    return true;
}

bool ID3::parseV2(const sp<DataSource> &source) {
struct id3_header {
    char id[3];
    uint8_t version_major;
    uint8_t version_minor;
    uint8_t flags;
    uint8_t enc_size[4];
    };

    id3_header header;
    if (source->readAt(
                0, &header, sizeof(header)) != (ssize_t)sizeof(header)) {
        return false;
    }

    if (memcmp(header.id, "ID3", 3)) {
        return false;
    }

    if (header.version_major == 0xff || header.version_minor == 0xff) {
        return false;
    }

    if (header.version_major == 2) {
        if (header.flags & 0x3f) {
            // We only support the 2 high bits, if any of the lower bits are
            // set, we cannot guarantee to understand the tag format.
            return false;
        }

        if (header.flags & 0x40) {
            // No compression scheme has been decided yet, ignore the
            // tag if compression is indicated.

            return false;
        }
    } else if (header.version_major == 3) {
        if (header.flags & 0x1f) {
            // We only support the 3 high bits, if any of the lower bits are
            // set, we cannot guarantee to understand the tag format.
            return false;
        }
    } else if (header.version_major == 4) {
        if (header.flags & 0x0f) {
            // The lower 4 bits are undefined in this spec.
            return false;
        }
    } else {
        return false;
    }

    size_t size;
    if (!ParseSyncsafeInteger(header.enc_size, &size)) {
        return false;
    }

    if (size > kMaxMetadataSize) {
        ALOGE("skipping huge ID3 metadata of size %d", size);
        return false;
    }

    mData = (uint8_t *)malloc(size);

    if (mData == NULL) {
        return false;
    }

    mSize = size;

    if (source->readAt(sizeof(header), mData, mSize) != (ssize_t)mSize) {
        free(mData);
        mData = NULL;

        return false;
    }

    if (header.version_major == 4) {
        void *copy = malloc(size);
        memcpy(copy, mData, size);

        bool success = removeUnsynchronizationV2_4(false /* iTunesHack */);
        if (!success) {
            memcpy(mData, copy, size);
            mSize = size;

            success = removeUnsynchronizationV2_4(true /* iTunesHack */);

            if (success) {
                ALOGV("Had to apply the iTunes hack to parse this ID3 tag");
            }
        }

        free(copy);
        copy = NULL;

        if (!success) {
            free(mData);
            mData = NULL;

            return false;
        }
    } else if (header.flags & 0x80) {
        ALOGV("removing unsynchronization");

        removeUnsynchronization();
    }

    mFirstFrameOffset = 0;
    if (header.version_major == 3 && (header.flags & 0x40)) {
        // Version 2.3 has an optional extended header.

        if (mSize < 4) {
            free(mData);
            mData = NULL;

            return false;
        }

        size_t extendedHeaderSize = U32_AT(&mData[0]) + 4;

        if (extendedHeaderSize > mSize) {
            free(mData);
            mData = NULL;

            return false;
        }

        mFirstFrameOffset = extendedHeaderSize;

        uint16_t extendedFlags = 0;
        if (extendedHeaderSize >= 6) {
            extendedFlags = U16_AT(&mData[4]);

            if (extendedHeaderSize >= 10) {
                size_t paddingSize = U32_AT(&mData[6]);

                if (mFirstFrameOffset + paddingSize > mSize) {
                    free(mData);
                    mData = NULL;

                    return false;
                }

                mSize -= paddingSize;
            }

            if (extendedFlags & 0x8000) {
                ALOGV("have crc");
            }
        }
    } else if (header.version_major == 4 && (header.flags & 0x40)) {
        // Version 2.4 has an optional extended header, that's different
        // from Version 2.3's...

        if (mSize < 4) {
            free(mData);
            mData = NULL;

            return false;
        }

        size_t ext_size;
        if (!ParseSyncsafeInteger(mData, &ext_size)) {
            free(mData);
            mData = NULL;

            return false;
        }

        if (ext_size < 6 || ext_size > mSize) {
            free(mData);
            mData = NULL;

            return false;
        }

        mFirstFrameOffset = ext_size;
    }

    if (header.version_major == 2) {
        mVersion = ID3_V2_2;
    } else if (header.version_major == 3) {
        mVersion = ID3_V2_3;
    } else {
        CHECK_EQ(header.version_major, 4);
        mVersion = ID3_V2_4;
    }

    return true;
}

void ID3::removeUnsynchronization() {
    for (size_t i = 0; i + 1 < mSize; ++i) {
        if (mData[i] == 0xff && mData[i + 1] == 0x00) {
            memmove(&mData[i + 1], &mData[i + 2], mSize - i - 2);
            --mSize;
        }
    }
}

static void WriteSyncsafeInteger(uint8_t *dst, size_t x) {
    for (size_t i = 0; i < 4; ++i) {
        dst[3 - i] = (x & 0x7f);
        x >>= 7;
    }
}

bool ID3::removeUnsynchronizationV2_4(bool iTunesHack) {
    size_t oldSize = mSize;

    size_t offset = 0;
    while (offset + 10 <= mSize) {
        if (!memcmp(&mData[offset], "\0\0\0\0", 4)) {
            break;
        }

        size_t dataSize;
        if (iTunesHack) {
            dataSize = U32_AT(&mData[offset + 4]);
        } else if (!ParseSyncsafeInteger(&mData[offset + 4], &dataSize)) {
            return false;
        }

        if (offset + dataSize + 10 > mSize) {
            return false;
        }

        uint16_t flags = U16_AT(&mData[offset + 8]);
        uint16_t prevFlags = flags;

        if (flags & 1) {
            // Strip data length indicator

            memmove(&mData[offset + 10], &mData[offset + 14], mSize - offset - 14);
            mSize -= 4;
            dataSize -= 4;

            flags &= ~1;
        }

        if (flags & 2) {
            // Unsynchronization added.

            for (size_t i = 0; i + 1 < dataSize; ++i) {
                if (mData[offset + 10 + i] == 0xff
                        && mData[offset + 11 + i] == 0x00) {
                    memmove(&mData[offset + 11 + i], &mData[offset + 12 + i],
                            mSize - offset - 12 - i);
                    --mSize;
                    --dataSize;
                }
            }

            flags &= ~2;
        }

        if (flags != prevFlags || iTunesHack) {
            WriteSyncsafeInteger(&mData[offset + 4], dataSize);
            mData[offset + 8] = flags >> 8;
            mData[offset + 9] = flags & 0xff;
        }

        offset += 10 + dataSize;
    }

    memset(&mData[mSize], 0, oldSize - mSize);

    return true;
}

ID3::Iterator::Iterator(const ID3 &parent, const char *id)
    : mParent(parent),
      mID(NULL),
      mOffset(mParent.mFirstFrameOffset),
      mFrameData(NULL),
      mFrameSize(0) {
    if (id) {
        mID = strdup(id);
    }

    findFrame();
}

ID3::Iterator::~Iterator() {
    if (mID) {
        free(mID);
        mID = NULL;
    }
}

bool ID3::Iterator::done() const {
    return mFrameData == NULL;
}

void ID3::Iterator::next() {
    if (mFrameData == NULL) {
        return;
    }

    mOffset += mFrameSize;

    findFrame();
}

void ID3::Iterator::getID(String8 *id) const {
    id->setTo("");

    if (mFrameData == NULL) {
        return;
    }

    if (mParent.mVersion == ID3_V2_2) {
        id->setTo((const char *)&mParent.mData[mOffset], 3);
    } else if (mParent.mVersion == ID3_V2_3 || mParent.mVersion == ID3_V2_4) {
        id->setTo((const char *)&mParent.mData[mOffset], 4);
    } else {
        CHECK(mParent.mVersion == ID3_V1 || mParent.mVersion == ID3_V1_1);

        switch (mOffset) {
            case 3:
                id->setTo("TT2");
                break;
            case 33:
                id->setTo("TP1");
                break;
            case 63:
                id->setTo("TAL");
                break;
            case 93:
                id->setTo("TYE");
                break;
            case 97:
                id->setTo("COM");
                break;
            case 126:
                id->setTo("TRK");
                break;
            case 127:
                id->setTo("TCO");
                break;
            default:
                CHECK(!"should not be here.");
                break;
        }
    }
}

static void convertISO8859ToString8(
        const uint8_t *data, size_t size,
        String8 *s) {
    size_t utf8len = 0;
    for (size_t i = 0; i < size; ++i) {
        if (data[i] == '\0') {
            size = i;
            break;
        } else if (data[i] < 0x80) {
            ++utf8len;
        } else {
            utf8len += 2;
        }
    }

    if (utf8len == size) {
        // Only ASCII characters present.

        s->setTo((const char *)data, size);
        return;
    }

    char *tmp = new char[utf8len];
    char *ptr = tmp;
    for (size_t i = 0; i < size; ++i) {
        if (data[i] == '\0') {
            break;
        } else if (data[i] < 0x80) {
            *ptr++ = data[i];
        } else if (data[i] < 0xc0) {
            *ptr++ = 0xc2;
            *ptr++ = data[i];
        } else {
            *ptr++ = 0xc3;
            *ptr++ = data[i] - 64;
        }
    }

    s->setTo(tmp, utf8len);

    delete[] tmp;
    tmp = NULL;
}

void ID3::Iterator::getString(String8 *id) const {
    id->setTo("");

    if (mFrameData == NULL) {
        return;
    }

    if (mParent.mVersion == ID3_V1 || mParent.mVersion == ID3_V1_1) {
        if (mOffset == 126 || mOffset == 127) {
            // Special treatment for the track number and genre.
            char tmp[16];
            sprintf(tmp, "%d", (int)*mFrameData);

            id->setTo(tmp);
            return;
        }

        convertISO8859ToString8(mFrameData, mFrameSize, id);
        return;
    }

    size_t n = mFrameSize - getHeaderLength() - 1;

    if (*mFrameData == 0x00) {
        // ISO 8859-1
        convertISO8859ToString8(mFrameData + 1, n, id);
    } else if (*mFrameData == 0x03) {
        // UTF-8
        id->setTo((const char *)(mFrameData + 1), n);
    } else if (*mFrameData == 0x02) {
        // UTF-16 BE, no byte order mark.
        // API wants number of characters, not number of bytes...
        int len = n / 2;
        const char16_t *framedata = (const char16_t *) (mFrameData + 1);
        char16_t *framedatacopy = NULL;
#if BYTE_ORDER == LITTLE_ENDIAN
        framedatacopy = new char16_t[len];
        for (int i = 0; i < len; i++) {
            framedatacopy[i] = bswap_16(framedata[i]);
        }
        framedata = framedatacopy;
#endif
        id->setTo(framedata, len);
        if (framedatacopy != NULL) {
            delete[] framedatacopy;
        }
    } else {
        // UCS-2
        // API wants number of characters, not number of bytes...
        int len = n / 2;
        const char16_t *framedata = (const char16_t *) (mFrameData + 1);
        char16_t *framedatacopy = NULL;
        if (*framedata == 0xfffe) {
            // endianness marker doesn't match host endianness, convert
            framedatacopy = new char16_t[len];
            for (int i = 0; i < len; i++) {
                framedatacopy[i] = bswap_16(framedata[i]);
            }
            framedata = framedatacopy;
        }
        // If the string starts with an endianness marker, skip it
        if (*framedata == 0xfeff) {
            framedata++;
            len--;
        }
        id->setTo(framedata, len);
        if (framedatacopy != NULL) {
            delete[] framedatacopy;
        }
    }
}

const uint8_t *ID3::Iterator::getData(size_t *length) const {
    *length = 0;

    if (mFrameData == NULL) {
        return NULL;
    }

    *length = mFrameSize - getHeaderLength();

    return mFrameData;
}

size_t ID3::Iterator::getHeaderLength() const {
    if (mParent.mVersion == ID3_V2_2) {
        return 6;
    } else if (mParent.mVersion == ID3_V2_3 || mParent.mVersion == ID3_V2_4) {
        return 10;
    } else {
        CHECK(mParent.mVersion == ID3_V1 || mParent.mVersion == ID3_V1_1);
        return 0;
    }
}

void ID3::Iterator::findFrame() {
    for (;;) {
        mFrameData = NULL;
        mFrameSize = 0;

        if (mParent.mVersion == ID3_V2_2) {
            if (mOffset + 6 > mParent.mSize) {
                return;
            }

            if (!memcmp(&mParent.mData[mOffset], "\0\0\0", 3)) {
                return;
            }

            mFrameSize =
                (mParent.mData[mOffset + 3] << 16)
                | (mParent.mData[mOffset + 4] << 8)
                | mParent.mData[mOffset + 5];

            mFrameSize += 6;

            if (mOffset + mFrameSize > mParent.mSize) {
                ALOGV("partial frame at offset %d (size = %d, bytes-remaining = %d)",
                     mOffset, mFrameSize, mParent.mSize - mOffset - 6);
                return;
            }

            mFrameData = &mParent.mData[mOffset + 6];

            if (!mID) {
                break;
            }

            char id[4];
            memcpy(id, &mParent.mData[mOffset], 3);
            id[3] = '\0';

            if (!strcmp(id, mID)) {
                break;
            }
        } else if (mParent.mVersion == ID3_V2_3
                || mParent.mVersion == ID3_V2_4) {
            if (mOffset + 10 > mParent.mSize) {
                return;
            }

            if (!memcmp(&mParent.mData[mOffset], "\0\0\0\0", 4)) {
                return;
            }

            size_t baseSize;
            if (mParent.mVersion == ID3_V2_4) {
                if (!ParseSyncsafeInteger(
                            &mParent.mData[mOffset + 4], &baseSize)) {
                    return;
                }
            } else {
                baseSize = U32_AT(&mParent.mData[mOffset + 4]);
            }

            mFrameSize = 10 + baseSize;

            if (mOffset + mFrameSize > mParent.mSize) {
                ALOGV("partial frame at offset %d (size = %d, bytes-remaining = %d)",
                     mOffset, mFrameSize, mParent.mSize - mOffset - 10);
                return;
            }

            uint16_t flags = U16_AT(&mParent.mData[mOffset + 8]);

            if ((mParent.mVersion == ID3_V2_4 && (flags & 0x000c))
                || (mParent.mVersion == ID3_V2_3 && (flags & 0x00c0))) {
                // Compression or encryption are not supported at this time.
                // Per-frame unsynchronization and data-length indicator
                // have already been taken care of.

                ALOGV("Skipping unsupported frame (compression, encryption "
                     "or per-frame unsynchronization flagged");

                mOffset += mFrameSize;
                continue;
            }

            mFrameData = &mParent.mData[mOffset + 10];

            if (!mID) {
                break;
            }

            char id[5];
            memcpy(id, &mParent.mData[mOffset], 4);
            id[4] = '\0';

            if (!strcmp(id, mID)) {
                break;
            }
        } else {
            CHECK(mParent.mVersion == ID3_V1 || mParent.mVersion == ID3_V1_1);

            if (mOffset >= mParent.mSize) {
                return;
            }

            mFrameData = &mParent.mData[mOffset];

            switch (mOffset) {
                case 3:
                case 33:
                case 63:
                    mFrameSize = 30;
                    break;
                case 93:
                    mFrameSize = 4;
                    break;
                case 97:
                    if (mParent.mVersion == ID3_V1) {
                        mFrameSize = 30;
                    } else {
                        mFrameSize = 29;
                    }
                    break;
                case 126:
                    mFrameSize = 1;
                    break;
                case 127:
                    mFrameSize = 1;
                    break;
                default:
                    CHECK(!"Should not be here, invalid offset.");
                    break;
            }

            if (!mID) {
                break;
            }

            String8 id;
            getID(&id);

            if (id == mID) {
                break;
            }
        }

        mOffset += mFrameSize;
    }
}

static size_t StringSize(const uint8_t *start, uint8_t encoding) {
    if (encoding == 0x00 || encoding == 0x03) {
        // ISO 8859-1 or UTF-8
        return strlen((const char *)start) + 1;
    }

    // UCS-2
    size_t n = 0;
    while (start[n] != '\0' || start[n + 1] != '\0') {
        n += 2;
    }

    return n;
}

const void *
ID3::getAlbumArt(size_t *length, String8 *mime) const {
    *length = 0;
    mime->setTo("");

    Iterator it(
            *this,
            (mVersion == ID3_V2_3 || mVersion == ID3_V2_4) ? "APIC" : "PIC");

    while (!it.done()) {
        size_t size;
        const uint8_t *data = it.getData(&size);

        if (mVersion == ID3_V2_3 || mVersion == ID3_V2_4) {
            uint8_t encoding = data[0];
            mime->setTo((const char *)&data[1]);
            size_t mimeLen = strlen((const char *)&data[1]) + 1;

            uint8_t picType = data[1 + mimeLen];
#if 0
            if (picType != 0x03) {
                // Front Cover Art
                it.next();
                continue;
            }
#endif

            size_t descLen = StringSize(&data[2 + mimeLen], encoding);

            *length = size - 2 - mimeLen - descLen;

            return &data[2 + mimeLen + descLen];
        } else {
            uint8_t encoding = data[0];

            if (!memcmp(&data[1], "PNG", 3)) {
                mime->setTo("image/png");
            } else if (!memcmp(&data[1], "JPG", 3)) {
                mime->setTo("image/jpeg");
            } else if (!memcmp(&data[1], "-->", 3)) {
                mime->setTo("text/plain");
            } else {
                return NULL;
            }

#if 0
            uint8_t picType = data[4];
            if (picType != 0x03) {
                // Front Cover Art
                it.next();
                continue;
            }
#endif

            size_t descLen = StringSize(&data[5], encoding);

            *length = size - 5 - descLen;

            return &data[5 + descLen];
        }
    }

    return NULL;
}

bool ID3::parseV1(const sp<DataSource> &source) {
    const size_t V1_TAG_SIZE = 128;

    off64_t size;
    if (source->getSize(&size) != OK || size < (off64_t)V1_TAG_SIZE) {
        return false;
    }

    mData = (uint8_t *)malloc(V1_TAG_SIZE);
    if (source->readAt(size - V1_TAG_SIZE, mData, V1_TAG_SIZE)
            != (ssize_t)V1_TAG_SIZE) {
        free(mData);
        mData = NULL;

        return false;
    }

    if (memcmp("TAG", mData, 3)) {
        free(mData);
        mData = NULL;

        return false;
    }

    mSize = V1_TAG_SIZE;
    mFirstFrameOffset = 3;

    if (mData[V1_TAG_SIZE - 3] != 0) {
        mVersion = ID3_V1;
    } else {
        mVersion = ID3_V1_1;
    }

    return true;
}

}  // namespace android
