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
#include <android/os/StatsLogEventWrapper.h>

#include <binder/Parcel.h>
#include <binder/Parcelable.h>
#include <binder/Status.h>
#include <utils/RefBase.h>
#include <vector>

using android::Parcel;
using android::Parcelable;
using android::status_t;
using std::vector;

namespace android {
namespace os {

StatsLogEventWrapper::StatsLogEventWrapper(){};

status_t StatsLogEventWrapper::writeToParcel(Parcel* out) const {
  // Implement me if desired. We don't currently use this.
  ALOGE(
      "Cannot do c++ StatsLogEventWrapper.writeToParcel(); it is not "
      "implemented.");
  (void)out;  // To prevent compile error of unused parameter 'in'
  return UNKNOWN_ERROR;
};

status_t StatsLogEventWrapper::readFromParcel(const Parcel* in) {
  status_t res = OK;
  if (in == NULL) {
    ALOGE("statsd received parcel argument was NULL.");
    return BAD_VALUE;
  }
  if ((res = in->readInt32(&mTagId)) != OK) {
    ALOGE("statsd could not read tagId from parcel");
    return res;
  }
  if ((res = in->readInt64(&mElapsedRealTimeNs)) != OK) {
    ALOGE("statsd could not read elapsed real time from parcel");
    return res;
  }
  if ((res = in->readInt64(&mWallClockTimeNs)) != OK) {
    ALOGE("statsd could not read wall clock time from parcel");
    return res;
  }
  int numWorkChain = 0;
  if ((res = in->readInt32(&numWorkChain)) != OK) {
    ALOGE("statsd could not read number of work chains from parcel");
    return res;
  }
  if (numWorkChain > 0) {
    for (int i = 0; i < numWorkChain; i++) {
      int numNodes = 0;
      if ((res = in->readInt32(&numNodes)) != OK) {
        ALOGE(
            "statsd could not read number of nodes in work chain from parcel");
        return res;
      }
      if (numNodes == 0) {
        ALOGE("empty work chain");
        return BAD_VALUE;
      }
      WorkChain wc;
      for (int j = 0; j < numNodes; j++) {
        wc.uids.push_back(in->readInt32());
        wc.tags.push_back(std::string(String8(in->readString16()).string()));
      }
      mWorkChains.push_back(wc);
    }
  }
  int dataSize = 0;
  if ((res = in->readInt32(&dataSize)) != OK) {
    ALOGE("statsd could not read data size from parcel");
    return res;
  }
  if (mTagId <= 0 || mElapsedRealTimeNs <= 0 || mWallClockTimeNs <= 0 ||
      dataSize <= 0) {
    ALOGE("statsd received invalid parcel");
    return BAD_VALUE;
  }

  for (int i = 0; i < dataSize; i++) {
    int type = in->readInt32();
    switch (type) {
      case StatsLogValue::INT:
        mElements.push_back(StatsLogValue(in->readInt32()));
        break;
      case StatsLogValue::LONG:
        mElements.push_back(StatsLogValue(in->readInt64()));
        break;
      case StatsLogValue::STRING:
        mElements.push_back(
            StatsLogValue(std::string(String8(in->readString16()).string())));
        break;
      case StatsLogValue::FLOAT:
        mElements.push_back(StatsLogValue(in->readFloat()));
        break;
      case StatsLogValue::STORAGE:
        mElements.push_back(StatsLogValue());
        mElements.back().setType(StatsLogValue::STORAGE);
        in->readByteVector(&(mElements.back().storage_value));
        break;
      default:
        ALOGE("unrecognized data type: %d", type);
        return BAD_TYPE;
    }
  }
  return NO_ERROR;
};

} // Namespace os
} // Namespace android
