/*
 * Copyright (C) 2018 The Android Open Source Project
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
#ifndef STATS_DIMENSIONS_VALUE_H
#define STATS_DIMENSIONS_VALUE_H

#include <binder/Parcel.h>
#include <binder/Parcelable.h>
#include <binder/Status.h>
#include <utils/String16.h>
#include <vector>

namespace android {
namespace os {

// Represents a parcelable object. Used to send data from statsd to StatsCompanionService.java.
class StatsDimensionsValue : public android::Parcelable {
public:
    StatsDimensionsValue();

    StatsDimensionsValue(int32_t field, String16 value);
    StatsDimensionsValue(int32_t field, int32_t value);
    StatsDimensionsValue(int32_t field, int64_t value);
    StatsDimensionsValue(int32_t field, bool value);
    StatsDimensionsValue(int32_t field, float value);
    StatsDimensionsValue(int32_t field, std::vector<StatsDimensionsValue> value);

    virtual ~StatsDimensionsValue();

    virtual android::status_t writeToParcel(android::Parcel* out) const override;
    virtual android::status_t readFromParcel(const android::Parcel* in) override;

private:
    // Keep constants in sync with android/os/StatsDimensionsValue.java
    // and stats_log.proto's DimensionValue.
    static const int kStrValueType = 2;
    static const int kIntValueType = 3;
    static const int kLongValueType = 4;
    static const int kBoolValueType = 5;
    static const int kFloatValueType = 6;
    static const int kTupleValueType = 7;

    int32_t mField;
    int32_t mValueType;

    // This isn't very clever, but it isn't used for long-term storage, so it'll do.
    String16 mStrValue;
    int32_t mIntValue;
    int64_t mLongValue;
    bool mBoolValue;
    float mFloatValue;
    std::vector<StatsDimensionsValue> mTupleValue;
};

}  // namespace os
}  // namespace android

#endif // STATS_DIMENSIONS_VALUE_H
