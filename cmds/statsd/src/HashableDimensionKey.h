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

    inline DimensionsValue* getMutableDimensionsValue() {
        return &mDimensionsValue;
    }

    bool operator==(const HashableDimensionKey& that) const;

    bool operator<(const HashableDimensionKey& that) const;

    inline const char* c_str() const {
        return toString().c_str();
    }

private:
    DimensionsValue mDimensionsValue;
};

class MetricDimensionKey {
 public:
    explicit MetricDimensionKey(const HashableDimensionKey& dimensionKeyInWhat,
                                const HashableDimensionKey& dimensionKeyInCondition)
        : mDimensionKeyInWhat(dimensionKeyInWhat),
          mDimensionKeyInCondition(dimensionKeyInCondition) {};

    MetricDimensionKey(){};

    MetricDimensionKey(const MetricDimensionKey& that)
        : mDimensionKeyInWhat(that.getDimensionKeyInWhat()),
          mDimensionKeyInCondition(that.getDimensionKeyInCondition()) {};

    MetricDimensionKey& operator=(const MetricDimensionKey& from) = default;

    std::string toString() const;

    inline const HashableDimensionKey& getDimensionKeyInWhat() const {
        return mDimensionKeyInWhat;
    }

    inline const HashableDimensionKey& getDimensionKeyInCondition() const {
        return mDimensionKeyInCondition;
    }

    bool hasDimensionKeyInCondition() const {
        return mDimensionKeyInCondition.getDimensionsValue().has_field();
    }

    bool operator==(const MetricDimensionKey& that) const;

    bool operator<(const MetricDimensionKey& that) const;

    inline const char* c_str() const {
        return toString().c_str();
    }
  private:
      HashableDimensionKey mDimensionKeyInWhat;
      HashableDimensionKey mDimensionKeyInCondition;
};

bool compareDimensionsValue(const DimensionsValue& s1, const DimensionsValue& s2);

android::hash_t hashDimensionsValue(int64_t seed, const DimensionsValue& value);
android::hash_t hashDimensionsValue(const DimensionsValue& value);
android::hash_t hashMetricDimensionKey(int64_t see, const MetricDimensionKey& dimensionKey);

}  // namespace statsd
}  // namespace os
}  // namespace android

namespace std {

using android::os::statsd::HashableDimensionKey;
using android::os::statsd::MetricDimensionKey;

template <>
struct hash<HashableDimensionKey> {
    std::size_t operator()(const HashableDimensionKey& key) const {
        return hashDimensionsValue(key.getDimensionsValue());
    }
};

template <>
struct hash<MetricDimensionKey> {
    std::size_t operator()(const MetricDimensionKey& key) const {
        android::hash_t hash = hashDimensionsValue(
            key.getDimensionKeyInWhat().getDimensionsValue());
        hash = android::JenkinsHashMix(hash,
                    hashDimensionsValue(key.getDimensionKeyInCondition().getDimensionsValue()));
        return android::JenkinsHashWhiten(hash);
    }
};
}  // namespace std