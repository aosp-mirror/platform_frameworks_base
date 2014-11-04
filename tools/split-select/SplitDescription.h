/*
 * Copyright (C) 2014 The Android Open Source Project
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

#ifndef H_ANDROID_SPLIT_SPLIT_DESCRIPTION
#define H_ANDROID_SPLIT_SPLIT_DESCRIPTION

#include "aapt/ConfigDescription.h"
#include "Abi.h"

#include <utils/String8.h>
#include <utils/Vector.h>

namespace split {

struct SplitDescription {
    SplitDescription();

    ConfigDescription config;
    abi::Variant abi;

    int compare(const SplitDescription& rhs) const;
    inline bool operator<(const SplitDescription& rhs) const;
    inline bool operator==(const SplitDescription& rhs) const;
    inline bool operator!=(const SplitDescription& rhs) const;

    bool match(const SplitDescription& o) const;
    bool isBetterThan(const SplitDescription& o, const SplitDescription& target) const;

    android::String8 toString() const;

    static bool parse(const android::String8& str, SplitDescription* outSplit);
};

ssize_t parseAbi(const android::Vector<android::String8>& parts, const ssize_t index,
        SplitDescription* outSplit);

bool SplitDescription::operator<(const SplitDescription& rhs) const {
    return compare(rhs) < 0;
}

bool SplitDescription::operator==(const SplitDescription& rhs) const {
    return compare(rhs) == 0;
}

bool SplitDescription::operator!=(const SplitDescription& rhs) const {
    return compare(rhs) != 0;
}

} // namespace split

#endif // H_ANDROID_SPLIT_SPLIT_DESCRIPTION
