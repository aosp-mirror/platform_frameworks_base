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

#ifndef MP3_SEEKER_H_

#define MP3_SEEKER_H_

#include <media/stagefright/foundation/ABase.h>
#include <utils/RefBase.h>

namespace android {

struct MP3Seeker : public RefBase {
    MP3Seeker() {}

    virtual bool getDuration(int64_t *durationUs) = 0;

    // Given a request seek time in "*timeUs", find the byte offset closest
    // to that position and return it in "*pos". Update "*timeUs" to reflect
    // the actual time that seekpoint represents.
    virtual bool getOffsetForTime(int64_t *timeUs, off64_t *pos) = 0;

protected:
    virtual ~MP3Seeker() {}

private:
    DISALLOW_EVIL_CONSTRUCTORS(MP3Seeker);
};

}  // namespace android

#endif  // MP3_SEEKER_H_

