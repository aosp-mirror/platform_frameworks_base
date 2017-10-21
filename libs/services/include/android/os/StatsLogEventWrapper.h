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

// Represents a parcelable object. Only used to send data from Android OS to statsd.
class StatsLogEventWrapper : public android::Parcelable {
 public:
  StatsLogEventWrapper();

  StatsLogEventWrapper(StatsLogEventWrapper&& in) = default;

  android::status_t writeToParcel(android::Parcel* out) const;

  android::status_t readFromParcel(const android::Parcel* in);

  // These are public for ease of conversion.
  std::vector<uint8_t> bytes;
};
} // Namespace os
} // Namespace android


#endif  // STATS_LOG_EVENT_WRAPPER_H

