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
#define LOG_TAG "ASessionDescription"
#include <utils/Log.h>

#include "ASessionDescription.h"

#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AString.h>

#include <stdlib.h>

namespace android {

ASessionDescription::ASessionDescription()
    : mIsValid(false) {
}

ASessionDescription::~ASessionDescription() {
}

bool ASessionDescription::setTo(const void *data, size_t size) {
    mIsValid = parse(data, size);

    if (!mIsValid) {
        mTracks.clear();
        mFormats.clear();
    }

    return mIsValid;
}

bool ASessionDescription::parse(const void *data, size_t size) {
    mTracks.clear();
    mFormats.clear();

    mTracks.push(Attribs());
    mFormats.push(AString("[root]"));

    AString desc((const char *)data, size);

    size_t i = 0;
    for (;;) {
        ssize_t eolPos = desc.find("\n", i);

        if (eolPos < 0) {
            break;
        }

        AString line;
        if ((size_t)eolPos > i && desc.c_str()[eolPos - 1] == '\r') {
            // We accept both '\n' and '\r\n' line endings, if it's
            // the latter, strip the '\r' as well.
            line.setTo(desc, i, eolPos - i - 1);
        } else {
            line.setTo(desc, i, eolPos - i);
        }

        if (line.empty()) {
            i = eolPos + 1;
            continue;
        }

        if (line.size() < 2 || line.c_str()[1] != '=') {
            return false;
        }

        LOGI("%s", line.c_str());

        switch (line.c_str()[0]) {
            case 'v':
            {
                if (strcmp(line.c_str(), "v=0")) {
                    return false;
                }
                break;
            }

            case 'a':
            case 'b':
            {
                AString key, value;

                ssize_t colonPos = line.find(":", 2);
                if (colonPos < 0) {
                    key = line;
                } else {
                    key.setTo(line, 0, colonPos);

                    if (key == "a=fmtp" || key == "a=rtpmap"
                            || key == "a=framesize") {
                        ssize_t spacePos = line.find(" ", colonPos + 1);
                        if (spacePos < 0) {
                            return false;
                        }

                        key.setTo(line, 0, spacePos);

                        colonPos = spacePos;
                    }

                    value.setTo(line, colonPos + 1, line.size() - colonPos - 1);
                }

                key.trim();
                value.trim();

                LOGV("adding '%s' => '%s'", key.c_str(), value.c_str());

                mTracks.editItemAt(mTracks.size() - 1).add(key, value);
                break;
            }

            case 'm':
            {
                LOGV("new section '%s'",
                     AString(line, 2, line.size() - 2).c_str());

                mTracks.push(Attribs());
                mFormats.push(AString(line, 2, line.size() - 2));
                break;
            }

            default:
            {
                AString key, value;

                ssize_t equalPos = line.find("=");

                key = AString(line, 0, equalPos + 1);
                value = AString(line, equalPos + 1, line.size() - equalPos - 1);

                key.trim();
                value.trim();

                LOGV("adding '%s' => '%s'", key.c_str(), value.c_str());

                mTracks.editItemAt(mTracks.size() - 1).add(key, value);
                break;
            }
        }

        i = eolPos + 1;
    }

    return true;
}

bool ASessionDescription::isValid() const {
    return mIsValid;
}

size_t ASessionDescription::countTracks() const {
    return mTracks.size();
}

void ASessionDescription::getFormat(size_t index, AString *value) const {
    CHECK_GE(index, 0u);
    CHECK_LT(index, mTracks.size());

    *value = mFormats.itemAt(index);
}

bool ASessionDescription::findAttribute(
        size_t index, const char *key, AString *value) const {
    CHECK_GE(index, 0u);
    CHECK_LT(index, mTracks.size());

    value->clear();

    const Attribs &track = mTracks.itemAt(index);
    ssize_t i = track.indexOfKey(AString(key));

    if (i < 0) {
        return false;
    }

    *value = track.valueAt(i);

    return true;
}

void ASessionDescription::getFormatType(
        size_t index, unsigned long *PT,
        AString *desc, AString *params) const {
    AString format;
    getFormat(index, &format);

    const char *lastSpacePos = strrchr(format.c_str(), ' ');
    CHECK(lastSpacePos != NULL);

    char *end;
    unsigned long x = strtoul(lastSpacePos + 1, &end, 10);
    CHECK_GT(end, lastSpacePos + 1);
    CHECK_EQ(*end, '\0');

    *PT = x;

    char key[20];
    sprintf(key, "a=rtpmap:%lu", x);

    CHECK(findAttribute(index, key, desc));

    sprintf(key, "a=fmtp:%lu", x);
    if (!findAttribute(index, key, params)) {
        params->clear();
    }
}

bool ASessionDescription::getDimensions(
        size_t index, unsigned long PT,
        int32_t *width, int32_t *height) const {
    *width = 0;
    *height = 0;

    char key[20];
    sprintf(key, "a=framesize:%lu", PT);
    AString value;
    if (!findAttribute(index, key, &value)) {
        return false;
    }

    const char *s = value.c_str();
    char *end;
    *width = strtoul(s, &end, 10);
    CHECK_GT(end, s);
    CHECK_EQ(*end, '-');

    s = end + 1;
    *height = strtoul(s, &end, 10);
    CHECK_GT(end, s);
    CHECK_EQ(*end, '\0');

    return true;
}

bool ASessionDescription::getDurationUs(int64_t *durationUs) const {
    *durationUs = 0;

    CHECK(mIsValid);

    AString value;
    if (!findAttribute(0, "a=range", &value)) {
        return false;
    }

    if (strncmp(value.c_str(), "npt=", 4)) {
        return false;
    }

    float from, to;
    if (!parseNTPRange(value.c_str() + 4, &from, &to)) {
        return false;
    }

    *durationUs = (int64_t)((to - from) * 1E6);

    return true;
}

// static
void ASessionDescription::ParseFormatDesc(
        const char *desc, int32_t *timescale, int32_t *numChannels) {
    const char *slash1 = strchr(desc, '/');
    CHECK(slash1 != NULL);

    const char *s = slash1 + 1;
    char *end;
    unsigned long x = strtoul(s, &end, 10);
    CHECK_GT(end, s);
    CHECK(*end == '\0' || *end == '/');

    *timescale = x;
    *numChannels = 1;

    if (*end == '/') {
        s = end + 1;
        unsigned long x = strtoul(s, &end, 10);
        CHECK_GT(end, s);
        CHECK_EQ(*end, '\0');

        *numChannels = x;
    }
}

// static
bool ASessionDescription::parseNTPRange(
        const char *s, float *npt1, float *npt2) {
    if (s[0] == '-') {
        return false;  // no start time available.
    }

    if (!strncmp("now", s, 3)) {
        return false;  // no absolute start time available
    }

    char *end;
    *npt1 = strtof(s, &end);

    if (end == s || *end != '-') {
        // Failed to parse float or trailing "dash".
        return false;
    }

    s = end + 1;  // skip the dash.

    if (!strncmp("now", s, 3)) {
        return false;  // no absolute end time available
    }

    *npt2 = strtof(s, &end);

    if (end == s || *end != '\0') {
        return false;
    }

    return *npt2 > *npt1;
}

}  // namespace android

