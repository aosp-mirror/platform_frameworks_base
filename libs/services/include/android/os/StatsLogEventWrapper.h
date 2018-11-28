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
#ifndef STATS_LOG_EVENT_WRAPPER_H
#define STATS_LOG_EVENT_WRAPPER_H

#include <binder/Parcel.h>
#include <binder/Parcelable.h>
#include <binder/Status.h>
#include <utils/RefBase.h>
#include <vector>

namespace android {
namespace os {

/**
 * A wrapper for a union type to contain multiple types of values.
 *
 */
struct StatsLogValue {
  // Keep in sync with FieldValue.h
  enum STATS_LOG_VALUE_TYPE {
    UNKNOWN = 0,
    INT = 1,
    LONG = 2,
    FLOAT = 3,
    DOUBLE = 4,
    STRING = 5,
    STORAGE = 6
  };

  StatsLogValue() : type(UNKNOWN) {}

  StatsLogValue(int32_t v) {
    int_value = v;
    type = INT;
  }

  StatsLogValue(int64_t v) {
    long_value = v;
    type = LONG;
  }

  StatsLogValue(float v) {
    float_value = v;
    type = FLOAT;
  }

  StatsLogValue(double v) {
    double_value = v;
    type = DOUBLE;
  }

  StatsLogValue(const std::string& v) {
    str_value = v;
    type = STRING;
  }

  void setType(STATS_LOG_VALUE_TYPE t) { type = t; }

  union {
    int32_t int_value;
    int64_t long_value;
    float float_value;
    double double_value;
  };
  std::string str_value;
  std::vector<uint8_t> storage_value;

  STATS_LOG_VALUE_TYPE type;
};

struct WorkChain {
  std::vector<int32_t> uids;
  std::vector<std::string> tags;
};

// Represents a parcelable object. Only used to send data from Android OS to statsd.
class StatsLogEventWrapper : public android::Parcelable {
 public:
  StatsLogEventWrapper();

  StatsLogEventWrapper(StatsLogEventWrapper&& in) = default;

  android::status_t writeToParcel(android::Parcel* out) const;

  android::status_t readFromParcel(const android::Parcel* in);

  int getTagId() const { return mTagId; }

  int64_t getElapsedRealTimeNs() const { return mElapsedRealTimeNs; }

  int64_t getWallClockTimeNs() const { return mWallClockTimeNs; }

  const std::vector<StatsLogValue>& getElements() const { return mElements; }

  const std::vector<WorkChain>& getWorkChains() const { return mWorkChains; }

 private:
  int mTagId;

  int64_t mElapsedRealTimeNs;

  int64_t mWallClockTimeNs;

  std::vector<StatsLogValue> mElements;

  std::vector<WorkChain> mWorkChains;
};
} // Namespace os
} // Namespace android


#endif  // STATS_LOG_EVENT_WRAPPER_H

