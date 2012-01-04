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
#define LOG_TAG "VBRISeeker"
#include <utils/Log.h>

#include "include/VBRISeeker.h"

#include "include/avc_utils.h"
#include "include/MP3Extractor.h"

#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/DataSource.h>
#include <media/stagefright/Utils.h>

namespace android {

static uint32_t U24_AT(const uint8_t *ptr) {
    return ptr[0] << 16 | ptr[1] << 8 | ptr[2];
}

// static
sp<VBRISeeker> VBRISeeker::CreateFromSource(
        const sp<DataSource> &source, off64_t post_id3_pos) {
    off64_t pos = post_id3_pos;

    uint8_t header[4];
    ssize_t n = source->readAt(pos, header, sizeof(header));
    if (n < (ssize_t)sizeof(header)) {
        return NULL;
    }

    uint32_t tmp = U32_AT(&header[0]);
    size_t frameSize;
    int sampleRate;
    if (!GetMPEGAudioFrameSize(tmp, &frameSize, &sampleRate)) {
        return NULL;
    }

    // VBRI header follows 32 bytes after the header _ends_.
    pos += sizeof(header) + 32;

    uint8_t vbriHeader[26];
    n = source->readAt(pos, vbriHeader, sizeof(vbriHeader));
    if (n < (ssize_t)sizeof(vbriHeader)) {
        return NULL;
    }

    if (memcmp(vbriHeader, "VBRI", 4)) {
        return NULL;
    }

    size_t numFrames = U32_AT(&vbriHeader[14]);

    int64_t durationUs =
        numFrames * 1000000ll * (sampleRate >= 32000 ? 1152 : 576) / sampleRate;

    ALOGV("duration = %.2f secs", durationUs / 1E6);

    size_t numEntries = U16_AT(&vbriHeader[18]);
    size_t entrySize = U16_AT(&vbriHeader[22]);
    size_t scale = U16_AT(&vbriHeader[20]);

    ALOGV("%d entries, scale=%d, size_per_entry=%d",
         numEntries,
         scale,
         entrySize);

    size_t totalEntrySize = numEntries * entrySize;
    uint8_t *buffer = new uint8_t[totalEntrySize];

    n = source->readAt(pos + sizeof(vbriHeader), buffer, totalEntrySize);
    if (n < (ssize_t)totalEntrySize) {
        delete[] buffer;
        buffer = NULL;

        return NULL;
    }

    sp<VBRISeeker> seeker = new VBRISeeker;
    seeker->mBasePos = post_id3_pos;
    seeker->mDurationUs = durationUs;

    off64_t offset = post_id3_pos;
    for (size_t i = 0; i < numEntries; ++i) {
        uint32_t numBytes;
        switch (entrySize) {
            case 1: numBytes = buffer[i]; break;
            case 2: numBytes = U16_AT(buffer + 2 * i); break;
            case 3: numBytes = U24_AT(buffer + 3 * i); break;
            default:
            {
                CHECK_EQ(entrySize, 4u);
                numBytes = U32_AT(buffer + 4 * i); break;
            }
        }

        numBytes *= scale;

        seeker->mSegments.push(numBytes);

        ALOGV("entry #%d: %d offset 0x%08lx", i, numBytes, offset);
        offset += numBytes;
    }

    delete[] buffer;
    buffer = NULL;

    ALOGI("Found VBRI header.");

    return seeker;
}

VBRISeeker::VBRISeeker()
    : mDurationUs(-1) {
}

bool VBRISeeker::getDuration(int64_t *durationUs) {
    if (mDurationUs < 0) {
        return false;
    }

    *durationUs = mDurationUs;

    return true;
}

bool VBRISeeker::getOffsetForTime(int64_t *timeUs, off64_t *pos) {
    if (mDurationUs < 0) {
        return false;
    }

    int64_t segmentDurationUs = mDurationUs / mSegments.size();

    int64_t nowUs = 0;
    *pos = mBasePos;
    size_t segmentIndex = 0;
    while (segmentIndex < mSegments.size() && nowUs < *timeUs) {
        nowUs += segmentDurationUs;
        *pos += mSegments.itemAt(segmentIndex++);
    }

    ALOGV("getOffsetForTime %lld us => 0x%08lx", *timeUs, *pos);

    *timeUs = nowUs;

    return true;
}

}  // namespace android

