/*
 * Copyright (C) 2017 The Android Open Source Project
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

#pragma once

#include <utils/JenkinsHash.h>
#include "frameworks/base/cmds/statsd/src/stats_log.pb.h"

namespace android {
namespace os {
namespace statsd {

class HashableDimensionKey {
public:
    explicit HashableDimensionKey(const DimensionsValue& dimensionsValue)
        : mDimensionsValue(dimensionsValue){};

    HashableDimensionKey(){};

    HashableDimensionKey(const HashableDimensionKey& that)
        : mDimensionsValue(that.getDimensionsValue()){};

    HashableDimensionKey& operator=(const HashableDimensionKey& from) = default;

    std::string toString() const;

    inline const DimensionsValue& getDimensionsValue() const {
        return mDimensionsValue;
    }

    bool operator==(const HashableDimensionKey& that) const;

    bool operator<(const HashableDimensionKey& that) const;

    inline const char* c_str() const {
        return toString().c_str();
    }

private:
    DimensionsValue mDimensionsValue;
};

android::hash_t hashDimensionsValue(const DimensionsValue& value);

}  // namespace statsd
}  // namespace os
}  // namespace android

namespace std {

using android::os::statsd::HashableDimensionKey;

template <>
struct hash<HashableDimensionKey> {
    std::size_t operator()(const HashableDimensionKey& key) const {
        return hashDimensionsValue(key.getDimensionsValue());
    }
};

}  // namespace std
