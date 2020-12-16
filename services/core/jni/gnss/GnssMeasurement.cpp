/*
 * Copyright (C) 2020 The Android Open Source Project
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
#define LOG_TAG "GnssMeasurementJni"

#include "GnssMeasurement.h"
#include "Utils.h"

using android::hardware::hidl_vec;
using android::hardware::Return;

using IGnssMeasurement_V1_0 = android::hardware::gnss::V1_0::IGnssMeasurement;
using IGnssMeasurement_V1_1 = android::hardware::gnss::V1_1::IGnssMeasurement;
using IGnssMeasurement_V2_0 = android::hardware::gnss::V2_0::IGnssMeasurement;
using IGnssMeasurement_V2_1 = android::hardware::gnss::V2_1::IGnssMeasurement;

namespace {
jboolean checkGnssMeasurementStatus(const IGnssMeasurement_V1_0::GnssMeasurementStatus& status) {
    if (status != IGnssMeasurement_V1_0::GnssMeasurementStatus::SUCCESS) {
        ALOGE("An error has been found on GnssMeasurementInterface::init, status=%d",
              static_cast<int32_t>(status));
        return JNI_FALSE;
    } else {
        ALOGD("gnss measurement infc has been enabled");
        return JNI_TRUE;
    }
}
} // anonymous namespace

namespace android::gnss {

// Implementation of GnssMeasurement_V1_0

GnssMeasurement_V1_0::GnssMeasurement_V1_0(const sp<IGnssMeasurement_V1_0>& iGnssMeasurement)
      : mIGnssMeasurement_V1_0(iGnssMeasurement) {}

jboolean GnssMeasurement_V1_0::setCallback(const sp<GnssMeasurementCallback>& callback,
                                           bool enableFullTracking) {
    if (enableFullTracking == true) {
        ALOGW("Full tracking is mode not supported in 1.0 GNSS HAL.");
    }
    auto status = mIGnssMeasurement_V1_0->setCallback(callback);
    if (!checkHidlReturn(status, "IGnssMeasurement setCallback() failed.")) {
        return JNI_FALSE;
    }

    return checkGnssMeasurementStatus(status);
}

jboolean GnssMeasurement_V1_0::close() {
    auto result = mIGnssMeasurement_V1_0->close();
    return checkHidlReturn(result, "IGnssMeasurement close() failed.");
}

// Implementation of GnssMeasurement_V1_1

GnssMeasurement_V1_1::GnssMeasurement_V1_1(const sp<IGnssMeasurement_V1_1>& iGnssMeasurement)
      : GnssMeasurement_V1_0{iGnssMeasurement}, mIGnssMeasurement_V1_1(iGnssMeasurement) {}

jboolean GnssMeasurement_V1_1::setCallback(const sp<GnssMeasurementCallback>& callback,
                                           bool enableFullTracking) {
    auto status = mIGnssMeasurement_V1_1->setCallback_1_1(callback, enableFullTracking);
    if (!checkHidlReturn(status, "IGnssMeasurement setCallback_V1_1() failed.")) {
        return JNI_FALSE;
    }

    return checkGnssMeasurementStatus(status);
}

// Implementation of GnssMeasurement_V2_0

GnssMeasurement_V2_0::GnssMeasurement_V2_0(const sp<IGnssMeasurement_V2_0>& iGnssMeasurement)
      : GnssMeasurement_V1_1{iGnssMeasurement}, mIGnssMeasurement_V2_0(iGnssMeasurement) {}

jboolean GnssMeasurement_V2_0::setCallback(const sp<GnssMeasurementCallback>& callback,
                                           bool enableFullTracking) {
    auto status = mIGnssMeasurement_V2_0->setCallback_2_0(callback, enableFullTracking);
    if (!checkHidlReturn(status, "IGnssMeasurement setCallback_2_0() failed.")) {
        return JNI_FALSE;
    }

    return checkGnssMeasurementStatus(status);
}

// Implementation of GnssMeasurement_V2_1

GnssMeasurement_V2_1::GnssMeasurement_V2_1(const sp<IGnssMeasurement_V2_1>& iGnssMeasurement)
      : GnssMeasurement_V2_0{iGnssMeasurement}, mIGnssMeasurement_V2_1(iGnssMeasurement) {}

jboolean GnssMeasurement_V2_1::setCallback(const sp<GnssMeasurementCallback>& callback,
                                           bool enableFullTracking) {
    auto status = mIGnssMeasurement_V2_1->setCallback_2_1(callback, enableFullTracking);
    if (!checkHidlReturn(status, "IGnssMeasurement setCallback_2_1() failed.")) {
        return JNI_FALSE;
    }

    return checkGnssMeasurementStatus(status);
}

} // namespace android::gnss