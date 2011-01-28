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

#ifndef A_SESSION_DESCRIPTION_H_

#define A_SESSION_DESCRIPTION_H_

#include <sys/types.h>

#include <media/stagefright/foundation/ABase.h>
#include <utils/KeyedVector.h>
#include <utils/RefBase.h>
#include <utils/Vector.h>

namespace android {

struct AString;

struct ASessionDescription : public RefBase {
    ASessionDescription();

    bool setTo(const void *data, size_t size);
    bool isValid() const;

    // Actually, 1 + number of tracks, as index 0 is reserved for the
    // session description root-level attributes.
    size_t countTracks() const;
    void getFormat(size_t index, AString *value) const;

    void getFormatType(
            size_t index, unsigned long *PT,
            AString *desc, AString *params) const;

    bool getDimensions(
            size_t index, unsigned long PT,
            int32_t *width, int32_t *height) const;

    bool getDurationUs(int64_t *durationUs) const;

    static void ParseFormatDesc(
            const char *desc, int32_t *timescale, int32_t *numChannels);

    bool findAttribute(size_t index, const char *key, AString *value) const;

    // parses strings of the form
    //   npt      := npt-time "-" npt-time? | "-" npt-time
    //   npt-time := "now" | [0-9]+("." [0-9]*)?
    //
    // Returns true iff both "npt1" and "npt2" times were available,
    // i.e. we have a fixed duration, otherwise this is live streaming.
    static bool parseNTPRange(const char *s, float *npt1, float *npt2);

protected:
    virtual ~ASessionDescription();

private:
    typedef KeyedVector<AString,AString> Attribs;

    bool mIsValid;
    Vector<Attribs> mTracks;
    Vector<AString> mFormats;

    bool parse(const void *data, size_t size);

    DISALLOW_EVIL_CONSTRUCTORS(ASessionDescription);
};

}  // namespace android

#endif  // A_SESSION_DESCRIPTION_H_
