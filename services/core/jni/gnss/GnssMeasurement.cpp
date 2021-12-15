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

using IGnssMeasurementInterface = android::hardware::gnss::IGnssMeasurementInterface;
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

// Implementation of GnssMeasurement

GnssMeasurement::GnssMeasurement(const sp<IGnssMeasurementInterface>& iGnssMeasurement)
      : mIGnssMeasurement(iGnssMeasurement) {}

jboolean GnssMeasurement::setCallback(const std::unique_ptr<GnssMeasurementCallback>& callback,
                                      const IGnssMeasurementInterface::Options& options) {
    if (mIGnssMeasurement->getInterfaceVersion() >= 2) {
        auto status = mIGnssMeasurement->setCallbackWithOptions(callback->getAidl(), options);
        if (checkAidlStatus(status, "IGnssMeasurement setCallbackWithOptions() failed.")) {
            return true;
        }
    }
    auto status = mIGnssMeasurement->setCallback(callback->getAidl(), options.enableFullTracking,
                                                 options.enableCorrVecOutputs);
    return checkAidlStatus(status, "IGnssMeasurement setCallback() failed.");
}

jboolean GnssMeasurement::close() {
    auto status = mIGnssMeasurement->close();
    return checkAidlStatus(status, "IGnssMeasurement close() failed.");
}

// Implementation of GnssMeasurement_V1_0

GnssMeasurement_V1_0::GnssMeasurement_V1_0(const sp<IGnssMeasurement_V1_0>& iGnssMeasurement)
      : mIGnssMeasurement_V1_0(iGnssMeasurement) {}

jboolean GnssMeasurement_V1_0::setCallback(const std::unique_ptr<GnssMeasurementCallback>& callback,
                                           const IGnssMeasurementInterface::Options& options) {
    if (options.enableFullTracking == true) {
        ALOGW("Full tracking mode is not supported in 1.0 GNSS HAL.");
    }
    if (options.enableCorrVecOutputs == true) {
        ALOGW("Correlation vector output is not supported in 1.0 GNSS HAL.");
    }
    if (options.intervalMs > 1000) {
        ALOGW("Measurement interval is not supported in 1.0 GNSS HAL.");
    }
    auto status = mIGnssMeasurement_V1_0->setCallback(callback->getHidl());
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

jboolean GnssMeasurement_V1_1::setCallback(const std::unique_ptr<GnssMeasurementCallback>& callback,
                                           const IGnssMeasurementInterface::Options& options) {
    if (options.enableCorrVecOutputs == true) {
        ALOGW("Correlation vector output is not supported in 1.1 GNSS HAL.");
    }
    if (options.intervalMs > 1000) {
        ALOGW("Measurement interval is not supported in 1.0 GNSS HAL.");
    }
    auto status = mIGnssMeasurement_V1_1->setCallback_1_1(callback->getHidl(),
                                                          options.enableFullTracking);
    if (!checkHidlReturn(status, "IGnssMeasurement setCallback_V1_1() failed.")) {
        return JNI_FALSE;
    }

    return checkGnssMeasurementStatus(status);
}

// Implementation of GnssMeasurement_V2_0

GnssMeasurement_V2_0::GnssMeasurement_V2_0(const sp<IGnssMeasurement_V2_0>& iGnssMeasurement)
      : GnssMeasurement_V1_1{iGnssMeasurement}, mIGnssMeasurement_V2_0(iGnssMeasurement) {}

jboolean GnssMeasurement_V2_0::setCallback(const std::unique_ptr<GnssMeasurementCallback>& callback,
                                           const IGnssMeasurementInterface::Options& options) {
    if (options.enableCorrVecOutputs == true) {
        ALOGW("Correlation vector output is not supported in 2.0 GNSS HAL.");
    }
    if (options.intervalMs > 1000) {
        ALOGW("Measurement interval is not supported in 1.0 GNSS HAL.");
    }
    auto status = mIGnssMeasurement_V2_0->setCallback_2_0(callback->getHidl(),
                                                          options.enableFullTracking);
    if (!checkHidlReturn(status, "IGnssMeasurement setCallback_2_0() failed.")) {
        return JNI_FALSE;
    }

    return checkGnssMeasurementStatus(status);
}

// Implementation of GnssMeasurement_V2_1

GnssMeasurement_V2_1::GnssMeasurement_V2_1(const sp<IGnssMeasurement_V2_1>& iGnssMeasurement)
      : GnssMeasurement_V2_0{iGnssMeasurement}, mIGnssMeasurement_V2_1(iGnssMeasurement) {}

jboolean GnssMeasurement_V2_1::setCallback(const std::unique_ptr<GnssMeasurementCallback>& callback,
                                           const IGnssMeasurementInterface::Options& options) {
    if (options.enableCorrVecOutputs == true) {
        ALOGW("Correlation vector output is not supported in 2.1 GNSS HAL.");
    }
    if (options.intervalMs > 1000) {
        ALOGW("Measurement interval is not supported in 1.0 GNSS HAL.");
    }
    auto status = mIGnssMeasurement_V2_1->setCallback_2_1(callback->getHidl(),
                                                          options.enableFullTracking);
    if (!checkHidlReturn(status, "IGnssMeasurement setCallback_2_1() failed.")) {
        return JNI_FALSE;
    }

    return checkGnssMeasurementStatus(status);
}

} // namespace android::gnss