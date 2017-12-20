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
    explicit HashableDimensionKey(const std::vector<KeyValuePair>& keyValuePairs)
        : mKeyValuePairs(keyValuePairs){};

    HashableDimensionKey(){};

    HashableDimensionKey(const HashableDimensionKey& that)
        : mKeyValuePairs(that.getKeyValuePairs()){};

    HashableDimensionKey& operator=(const HashableDimensionKey& from) = default;

    std::string toString() const;

    inline const std::vector<KeyValuePair>& getKeyValuePairs() const {
        return mKeyValuePairs;
    }

    bool operator==(const HashableDimensionKey& that) const;

    bool operator<(const HashableDimensionKey& that) const;

    inline const char* c_str() const {
        return toString().c_str();
    }

private:
    std::vector<KeyValuePair> mKeyValuePairs;
};

}  // namespace statsd
}  // namespace os
}  // namespace android

namespace std {

using android::os::statsd::HashableDimensionKey;
using android::os::statsd::KeyValuePair;

template <>
struct hash<HashableDimensionKey> {
    std::size_t operator()(const HashableDimensionKey& key) const {
        android::hash_t hash = 0;
        for (const auto& pair : key.getKeyValuePairs()) {
            hash = android::JenkinsHashMix(hash, android::hash_type(pair.key()));
            hash = android::JenkinsHashMix(
                    hash, android::hash_type(static_cast<int32_t>(pair.value_case())));
            switch (pair.value_case()) {
                case KeyValuePair::ValueCase::kValueStr:
                    hash = android::JenkinsHashMix(
                            hash,
                            static_cast<uint32_t>(std::hash<std::string>()(pair.value_str())));
                    break;
                case KeyValuePair::ValueCase::kValueInt:
                    hash = android::JenkinsHashMix(hash, android::hash_type(pair.value_int()));
                    break;
                case KeyValuePair::ValueCase::kValueLong:
                    hash = android::JenkinsHashMix(
                            hash, android::hash_type(static_cast<int64_t>(pair.value_long())));
                    break;
                case KeyValuePair::ValueCase::kValueBool:
                    hash = android::JenkinsHashMix(hash, android::hash_type(pair.value_bool()));
                    break;
                case KeyValuePair::ValueCase::kValueFloat: {
                    float floatVal = pair.value_float();
                    hash = android::JenkinsHashMixBytes(hash, (uint8_t*)&floatVal, sizeof(float));
                    break;
                }
                case KeyValuePair::ValueCase::VALUE_NOT_SET:
                    break;
            }
        }
        return hash;
    }
};

}  // namespace std
