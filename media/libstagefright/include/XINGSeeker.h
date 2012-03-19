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

#ifndef XING_SEEKER_H_

#define XING_SEEKER_H_

#include "include/MP3Seeker.h"

namespace android {

struct DataSource;

struct XINGSeeker : public MP3Seeker {
    static sp<XINGSeeker> CreateFromSource(
            const sp<DataSource> &source, off64_t first_frame_pos);

    virtual bool getDuration(int64_t *durationUs);
    virtual bool getOffsetForTime(int64_t *timeUs, off64_t *pos);

    virtual int32_t getEncoderDelay();
    virtual int32_t getEncoderPadding();

private:
    int64_t mFirstFramePos;
    int64_t mDurationUs;
    int32_t mSizeBytes;
    int32_t mEncoderDelay;
    int32_t mEncoderPadding;

    // TOC entries in XING header. Skip the first one since it's always 0.
    unsigned char mTOC[99];
    bool mTOCValid;

    XINGSeeker();

    DISALLOW_EVIL_CONSTRUCTORS(XINGSeeker);
};

}  // namespace android

#endif  // XING_SEEKER_H_

