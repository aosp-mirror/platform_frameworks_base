/*
 * Copyright (C) 2021 The Android Open Source Project
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

// Define LOG_TAG before <log/log.h> to overwrite the default value.
#define LOG_TAG "GnssDebugJni"

#include "GnssDebug.h"

#include "Utils.h"

using android::hardware::gnss::IGnssDebug;
using IGnssDebug_V1_0 = android::hardware::gnss::V1_0::IGnssDebug;
using IGnssDebug_V2_0 = android::hardware::gnss::V2_0::IGnssDebug;

namespace android::gnss {

// Implementation of GnssDebug (AIDL HAL)

GnssDebug::GnssDebug(const sp<IGnssDebug>& iGnssDebug) : mIGnssDebug(iGnssDebug) {
    assert(mIGnssDebug != nullptr);
}

jstring GnssDebug::getDebugData(JNIEnv* env) {
    std::stringstream internalState;
    IGnssDebug::DebugData data;
    auto status = mIGnssDebug->getDebugData(&data);
    if (checkAidlStatus(status, "IGnssDebug getDebugData() failed.")) {
        return GnssDebugUtil::parseDebugData<IGnssDebug::DebugData,
                                             IGnssDebug::SatelliteData>(env, internalState, data);
    }
    return nullptr;
}

// Implementation of GnssDebug_V1_0

GnssDebug_V1_0::GnssDebug_V1_0(const sp<IGnssDebug_V1_0>& iGnssDebug)
      : mIGnssDebug_V1_0(iGnssDebug) {
    assert(mIGnssDebug_V1_0 != nullptr);
}

jstring GnssDebug_V1_0::getDebugData(JNIEnv* env) {
    std::stringstream internalState;
    IGnssDebug_V1_0::DebugData data;
    auto result = mIGnssDebug_V1_0->getDebugData(
            [&data](const IGnssDebug_V1_0::DebugData& debugData) { data = debugData; });
    if (checkHidlReturn(result, "IGnssDebug getDebugData_1_0() failed.")) {
        return GnssDebugUtil::parseDebugData<IGnssDebug_V1_0::DebugData,
                                             IGnssDebug_V1_0::SatelliteData>(env, internalState,
                                                                             data);
    }
    return nullptr;
}

// Implementation of GnssDebug_V2_0

GnssDebug_V2_0::GnssDebug_V2_0(const sp<IGnssDebug_V2_0>& iGnssDebug)
      : mIGnssDebug_V2_0{iGnssDebug} {
    assert(mIGnssDebug_V2_0 != nullptr);
}

jstring GnssDebug_V2_0::getDebugData(JNIEnv* env) {
    std::stringstream internalState;
    IGnssDebug_V2_0::DebugData data;
    auto result = mIGnssDebug_V2_0->getDebugData_2_0(
            [&data](const IGnssDebug_V2_0::DebugData& debugData) { data = debugData; });
    if (checkHidlReturn(result, "IGnssDebug getDebugData_2_0() failed.")) {
        return GnssDebugUtil::parseDebugData<IGnssDebug_V2_0::DebugData,
                                             IGnssDebug_V1_0::SatelliteData>(env, internalState,
                                                                             data);
    }
    return nullptr;
}

const android::hardware::gnss::V1_0::IGnssDebug::SatelliteData& GnssDebugUtil::getSatelliteData(
        const hardware::hidl_vec<android::hardware::gnss::V1_0::IGnssDebug::SatelliteData>&
                satelliteDataArray,
        size_t i) {
    return satelliteDataArray[i];
}

const android::hardware::gnss::V1_0::IGnssDebug::SatelliteData& GnssDebugUtil::getSatelliteData(
        const hardware::hidl_vec<android::hardware::gnss::V2_0::IGnssDebug::SatelliteData>&
                satelliteDataArray,
        size_t i) {
    return satelliteDataArray[i].v1_0;
}

const android::hardware::gnss::IGnssDebug::SatelliteData& GnssDebugUtil::getSatelliteData(
        const std::vector<android::hardware::gnss::IGnssDebug::SatelliteData>& satelliteDataArray,
        size_t i) {
    return satelliteDataArray[i];
}

template <>
int64_t GnssDebugUtil::getTimeEstimateMs(
        const android::hardware::gnss::IGnssDebug::DebugData& data) {
    return data.time.timeEstimateMs;
}

} // namespace android::gnss
