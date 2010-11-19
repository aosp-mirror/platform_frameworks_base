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

#ifndef VBRI_SEEKER_H_

#define VBRI_SEEKER_H_

#include "include/MP3Seeker.h"

#include <utils/Vector.h>

namespace android {

struct DataSource;

struct VBRISeeker : public MP3Seeker {
    static sp<VBRISeeker> CreateFromSource(
            const sp<DataSource> &source, off64_t post_id3_pos);

    virtual bool getDuration(int64_t *durationUs);
    virtual bool getOffsetForTime(int64_t *timeUs, off64_t *pos);

private:
    off64_t mBasePos;
    int64_t mDurationUs;
    Vector<uint32_t> mSegments;

    VBRISeeker();

    DISALLOW_EVIL_CONSTRUCTORS(VBRISeeker);
};

}  // namespace android

#endif  // VBRI_SEEKER_H_


