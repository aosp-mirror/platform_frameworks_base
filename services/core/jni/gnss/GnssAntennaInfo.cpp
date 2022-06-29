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
#define LOG_TAG "GnssAntennaInfoJni"

#include "GnssAntennaInfo.h"

#include "Utils.h"

using IGnssAntennaInfoAidl = android::hardware::gnss::IGnssAntennaInfo;
using IGnssAntennaInfo_V2_1 = android::hardware::gnss::V2_1::IGnssAntennaInfo;

namespace android::gnss {

// Implementation of GnssAntennaInfo (AIDL HAL)

GnssAntennaInfoAidl::GnssAntennaInfoAidl(const sp<IGnssAntennaInfoAidl>& iGnssAntennaInfo)
      : mIGnssAntennaInfoAidl(iGnssAntennaInfo) {
    assert(mIGnssAntennaInfoAidl != nullptr);
}

jboolean GnssAntennaInfoAidl::setCallback(
        const std::unique_ptr<GnssAntennaInfoCallback>& callback) {
    auto status = mIGnssAntennaInfoAidl->setCallback(callback->getAidl());
    return checkAidlStatus(status, "IGnssAntennaInfoAidl setCallback() failed.");
}

jboolean GnssAntennaInfoAidl::close() {
    auto status = mIGnssAntennaInfoAidl->close();
    return checkAidlStatus(status, "IGnssAntennaInfoAidl close() failed");
}

// Implementation of GnssAntennaInfo_V2_1

GnssAntennaInfo_V2_1::GnssAntennaInfo_V2_1(const sp<IGnssAntennaInfo_V2_1>& iGnssAntennaInfo)
      : mIGnssAntennaInfo_V2_1(iGnssAntennaInfo) {
    assert(mIGnssAntennaInfo_V2_1 != nullptr);
}

jboolean GnssAntennaInfo_V2_1::setCallback(
        const std::unique_ptr<GnssAntennaInfoCallback>& callback) {
    auto result = mIGnssAntennaInfo_V2_1->setCallback(callback->getV2_1());
    if (!checkHidlReturn(result, "IGnssAntennaInfo_V2_1 setCallback() failed.")) {
        return JNI_FALSE;
    }

    IGnssAntennaInfo_V2_1::GnssAntennaInfoStatus initRet = result;
    if (initRet != IGnssAntennaInfo_V2_1::GnssAntennaInfoStatus::SUCCESS) {
        ALOGE("An error has been found on GnssAntennaInfoInterface::init, status=%d",
              static_cast<int32_t>(initRet));
        return JNI_FALSE;
    } else {
        ALOGD("gnss antenna info v2_1 has been enabled");
    }
    return JNI_TRUE;
}

jboolean GnssAntennaInfo_V2_1::close() {
    auto result = mIGnssAntennaInfo_V2_1->close();
    return checkHidlReturn(result, "IGnssAntennaInfo_V2_1 close() failed.");
}

} // namespace android::gnss
