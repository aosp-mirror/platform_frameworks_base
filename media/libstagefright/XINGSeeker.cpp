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

#include "include/XINGSeeker.h"

#include <media/stagefright/DataSource.h>
#include <media/stagefright/Utils.h>

namespace android {

static bool parse_xing_header(
        const sp<DataSource> &source, off64_t first_frame_pos,
        int32_t *frame_number = NULL, int32_t *byte_number = NULL,
        unsigned char *table_of_contents = NULL,
        int32_t *quality_indicator = NULL, int64_t *duration = NULL);

// static
sp<XINGSeeker> XINGSeeker::CreateFromSource(
        const sp<DataSource> &source, off64_t first_frame_pos) {
    sp<XINGSeeker> seeker = new XINGSeeker;

    seeker->mFirstFramePos = first_frame_pos;

    if (!parse_xing_header(
                source, first_frame_pos,
                NULL, &seeker->mSizeBytes, seeker->mTableOfContents,
                NULL, &seeker->mDurationUs)) {
        return NULL;
    }

    return seeker;
}

XINGSeeker::XINGSeeker()
    : mDurationUs(-1),
      mSizeBytes(0) {
}

bool XINGSeeker::getDuration(int64_t *durationUs) {
    if (mDurationUs < 0) {
        return false;
    }

    *durationUs = mDurationUs;

    return true;
}

bool XINGSeeker::getOffsetForTime(int64_t *timeUs, off64_t *pos) {
    if (mSizeBytes == 0 || mTableOfContents[0] <= 0 || mDurationUs < 0) {
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
            fa = (float)mTableOfContents[a-1];
        }
        if ( a < 99 ) {
            fb = (float)mTableOfContents[a];
        } else {
            fb = 256.0f;
        }
        fx = fa + (fb-fa)*(percent-a);
    }

    *pos = (int)((1.0f/256.0f)*fx*mSizeBytes) + mFirstFramePos;

    return true;
}

static bool parse_xing_header(
        const sp<DataSource> &source, off64_t first_frame_pos,
        int32_t *frame_number, int32_t *byte_number,
        unsigned char *table_of_contents, int32_t *quality_indicator,
        int64_t *duration) {
    if (frame_number) {
        *frame_number = 0;
    }
    if (byte_number) {
        *byte_number = 0;
    }
    if (table_of_contents) {
        table_of_contents[0] = 0;
    }
    if (quality_indicator) {
        *quality_indicator = 0;
    }
    if (duration) {
        *duration = 0;
    }

    uint8_t buffer[4];
    int offset = first_frame_pos;
    if (source->readAt(offset, &buffer, 4) < 4) { // get header
        return false;
    }
    offset += 4;

    uint8_t id, layer, sr_index, mode;
    layer = (buffer[1] >> 1) & 3;
    id = (buffer[1] >> 3) & 3;
    sr_index = (buffer[2] >> 2) & 3;
    mode = (buffer[3] >> 6) & 3;
    if (layer == 0) {
        return false;
    }
    if (id == 1) {
        return false;
    }
    if (sr_index == 3) {
        return false;
    }
    // determine offset of XING header
    if(id&1) { // mpeg1
        if (mode != 3) offset += 32;
        else offset += 17;
    } else { // mpeg2
        if (mode != 3) offset += 17;
        else offset += 9;
    }

    if (source->readAt(offset, &buffer, 4) < 4) { // XING header ID
        return false;
    }
    offset += 4;
    // Check XING ID
    if ((buffer[0] != 'X') || (buffer[1] != 'i')
                || (buffer[2] != 'n') || (buffer[3] != 'g')) {
        if ((buffer[0] != 'I') || (buffer[1] != 'n')
                    || (buffer[2] != 'f') || (buffer[3] != 'o')) {
            return false;
        }
    }

    if (source->readAt(offset, &buffer, 4) < 4) { // flags
        return false;
    }
    offset += 4;
    uint32_t flags = U32_AT(buffer);

    if (flags & 0x0001) {  // Frames field is present
        if (source->readAt(offset, buffer, 4) < 4) {
             return false;
        }
        if (frame_number) {
           *frame_number = U32_AT(buffer);
        }
        int32_t frame = U32_AT(buffer);
        // Samples per Frame: 1. index = MPEG Version ID, 2. index = Layer
        const int samplesPerFrames[2][3] =
        {
            { 384, 1152, 576  }, // MPEG 2, 2.5: layer1, layer2, layer3
            { 384, 1152, 1152 }, // MPEG 1: layer1, layer2, layer3
        };
        // sampling rates in hertz: 1. index = MPEG Version ID, 2. index = sampling rate index
        const int samplingRates[4][3] =
        {
            { 11025, 12000, 8000,  },    // MPEG 2.5
            { 0,     0,     0,     },    // reserved
            { 22050, 24000, 16000, },    // MPEG 2
            { 44100, 48000, 32000, }     // MPEG 1
        };
        if (duration) {
            *duration = (int64_t)frame * samplesPerFrames[id&1][3-layer] * 1000000LL
                / samplingRates[id][sr_index];
        }
        offset += 4;
    }
    if (flags & 0x0002) {  // Bytes field is present
        if (byte_number) {
            if (source->readAt(offset, buffer, 4) < 4) {
                return false;
            }
            *byte_number = U32_AT(buffer);
        }
        offset += 4;
    }
    if (flags & 0x0004) {  // TOC field is present
       if (table_of_contents) {
            if (source->readAt(offset + 1, table_of_contents, 99) < 99) {
                return false;
            }
        }
        offset += 100;
    }
    if (flags & 0x0008) {  // Quality indicator field is present
        if (quality_indicator) {
            if (source->readAt(offset, buffer, 4) < 4) {
                return false;
            }
            *quality_indicator = U32_AT(buffer);
        }
    }
    return true;
}

}  // namespace android

