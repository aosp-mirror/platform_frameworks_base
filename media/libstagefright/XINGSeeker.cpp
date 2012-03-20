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

#define LOG_TAG "XINGSEEKER"
#include <utils/Log.h>

#include "include/XINGSeeker.h"
#include "include/avc_utils.h"

#include <media/stagefright/DataSource.h>
#include <media/stagefright/Utils.h>

namespace android {

XINGSeeker::XINGSeeker()
    : mDurationUs(-1),
      mSizeBytes(0),
      mEncoderDelay(0),
      mEncoderPadding(0) {
}

bool XINGSeeker::getDuration(int64_t *durationUs) {
    if (mDurationUs < 0) {
        return false;
    }

    *durationUs = mDurationUs;

    return true;
}

bool XINGSeeker::getOffsetForTime(int64_t *timeUs, off64_t *pos) {
    if (mSizeBytes == 0 || !mTOCValid || mDurationUs < 0) {
        return false;
    }

    float percent = (float)(*timeUs) * 100 / mDurationUs;
    float fx;
    if( percent <= 0.0f ) {
        fx = 0.0f;
    } else if( percent >= 100.0f ) {
        fx = 256.0f;
    } else {
        int a = (int)percent;
        float fa, fb;
        if ( a == 0 ) {
            fa = 0.0f;
        } else {
            fa = (float)mTOC[a-1];
        }
        if ( a < 99 ) {
            fb = (float)mTOC[a];
        } else {
            fb = 256.0f;
        }
        fx = fa + (fb-fa)*(percent-a);
    }

    *pos = (int)((1.0f/256.0f)*fx*mSizeBytes) + mFirstFramePos;

    return true;
}

// static
sp<XINGSeeker> XINGSeeker::CreateFromSource(
        const sp<DataSource> &source, off64_t first_frame_pos) {
    sp<XINGSeeker> seeker = new XINGSeeker;

    seeker->mFirstFramePos = first_frame_pos;

    seeker->mSizeBytes = 0;
    seeker->mTOCValid = false;
    seeker->mDurationUs = 0;

    uint8_t buffer[4];
    int offset = first_frame_pos;
    if (source->readAt(offset, &buffer, 4) < 4) { // get header
        return NULL;
    }
    offset += 4;

    int header = U32_AT(buffer);;
    size_t xingframesize = 0;
    int sampling_rate = 0;
    int num_channels;
    int samples_per_frame = 0;
    if (!GetMPEGAudioFrameSize(header, &xingframesize, &sampling_rate, &num_channels,
                               NULL, &samples_per_frame)) {
        return NULL;
    }
    seeker->mFirstFramePos += xingframesize;

    uint8_t version = (buffer[1] >> 3) & 3;

    // determine offset of XING header
    if(version & 1) { // mpeg1
        if (num_channels != 1) offset += 32;
        else offset += 17;
    } else { // mpeg 2 or 2.5
        if (num_channels != 1) offset += 17;
        else offset += 9;
    }

    int xingbase = offset;

    if (source->readAt(offset, &buffer, 4) < 4) { // XING header ID
        return NULL;
    }
    offset += 4;
    // Check XING ID
    if ((buffer[0] != 'X') || (buffer[1] != 'i')
                || (buffer[2] != 'n') || (buffer[3] != 'g')) {
        if ((buffer[0] != 'I') || (buffer[1] != 'n')
                    || (buffer[2] != 'f') || (buffer[3] != 'o')) {
            return NULL;
        }
    }

    if (source->readAt(offset, &buffer, 4) < 4) { // flags
        return NULL;
    }
    offset += 4;
    uint32_t flags = U32_AT(buffer);

    if (flags & 0x0001) {  // Frames field is present
        if (source->readAt(offset, buffer, 4) < 4) {
             return NULL;
        }
        int32_t frames = U32_AT(buffer);
        seeker->mDurationUs = (int64_t)frames * samples_per_frame * 1000000LL / sampling_rate;
        offset += 4;
    }
    if (flags & 0x0002) {  // Bytes field is present
        if (source->readAt(offset, buffer, 4) < 4) {
            return NULL;
        }
        seeker->mSizeBytes = U32_AT(buffer);
        offset += 4;
    }
    if (flags & 0x0004) {  // TOC field is present
        if (source->readAt(offset + 1, seeker->mTOC, 99) < 99) {
            return NULL;
        }
        seeker->mTOCValid = true;
        offset += 100;
    }

#if 0
    if (flags & 0x0008) {  // Quality indicator field is present
        if (source->readAt(offset, buffer, 4) < 4) {
            return NULL;
        }
        // do something with the quality indicator
        offset += 4;
    }

    if (source->readAt(xingbase + 0xaf - 0x24, &buffer, 1) < 1) { // encoding flags
        return false;
    }

    ALOGV("nogap preceding: %s, nogap continued in next: %s",
              (buffer[0] & 0x80) ? "true" : "false",
              (buffer[0] & 0x40) ? "true" : "false");
#endif

    if (source->readAt(xingbase + 0xb1 - 0x24, &buffer, 3) == 3) {
        seeker->mEncoderDelay = (buffer[0] << 4) + (buffer[1] >> 4);
        seeker->mEncoderPadding = ((buffer[1] & 0xf) << 8) + buffer[2];
    }

    return seeker;
}

int32_t XINGSeeker::getEncoderDelay() {
    return mEncoderDelay;
}

int32_t XINGSeeker::getEncoderPadding() {
    return mEncoderPadding;
}

}  // namespace android

