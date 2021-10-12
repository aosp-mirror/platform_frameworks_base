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

#ifndef _ANDROID_SERVER_GNSS_GNSSMEASUREMENT_H
#define _ANDROID_SERVER_GNSS_GNSSMEASUREMENT_H

#pragma once

#ifndef LOG_TAG
#error LOG_TAG must be defined before including this file.
#endif

#include <android/hardware/gnss/1.0/IGnssMeasurement.h>
#include <android/hardware/gnss/1.1/IGnssMeasurement.h>
#include <android/hardware/gnss/2.0/IGnssMeasurement.h>
#include <android/hardware/gnss/2.1/IGnssMeasurement.h>
#include <android/hardware/gnss/BnGnssMeasurementInterface.h>
#include <log/log.h>
#include "GnssMeasurementCallback.h"
#include "jni.h"

namespace android::gnss {

class GnssMeasurementInterface {
public:
    virtual ~GnssMeasurementInterface() {}
    virtual jboolean setCallback(const std::unique_ptr<GnssMeasurementCallback>& callback,
                                 bool enableFullTracking, bool enableCorrVecOutputs) = 0;
    virtual jboolean close() = 0;
};

class GnssMeasurement : public GnssMeasurementInterface {
public:
    GnssMeasurement(const sp<android::hardware::gnss::IGnssMeasurementInterface>& iGnssMeasurement);
    jboolean setCallback(const std::unique_ptr<GnssMeasurementCallback>& callback,
                         bool enableFullTracking, bool enableCorrVecOutputs) override;
    jboolean close() override;

private:
    const sp<android::hardware::gnss::IGnssMeasurementInterface> mIGnssMeasurement;
};

class GnssMeasurement_V1_0 : public GnssMeasurementInterface {
public:
    GnssMeasurement_V1_0(
            const sp<android::hardware::gnss::V1_0::IGnssMeasurement>& iGnssMeasurement);
    jboolean setCallback(const std::unique_ptr<GnssMeasurementCallback>& callback,
                         bool enableFullTracking, bool enableCorrVecOutputs) override;
    jboolean close() override;

private:
    const sp<android::hardware::gnss::V1_0::IGnssMeasurement> mIGnssMeasurement_V1_0;
};

class GnssMeasurement_V1_1 : public GnssMeasurement_V1_0 {
public:
    GnssMeasurement_V1_1(
            const sp<android::hardware::gnss::V1_1::IGnssMeasurement>& iGnssMeasurement);
    jboolean setCallback(const std::unique_ptr<GnssMeasurementCallback>& callback,
                         bool enableFullTracking, bool enableCorrVecOutputs) override;

private:
    const sp<android::hardware::gnss::V1_1::IGnssMeasurement> mIGnssMeasurement_V1_1;
};

class GnssMeasurement_V2_0 : public GnssMeasurement_V1_1 {
public:
    GnssMeasurement_V2_0(
            const sp<android::hardware::gnss::V2_0::IGnssMeasurement>& iGnssMeasurement);
    jboolean setCallback(const std::unique_ptr<GnssMeasurementCallback>& callback,
                         bool enableFullTracking, bool enableCorrVecOutputs) override;

private:
    const sp<android::hardware::gnss::V2_0::IGnssMeasurement> mIGnssMeasurement_V2_0;
};

class GnssMeasurement_V2_1 : public GnssMeasurement_V2_0 {
public:
    GnssMeasurement_V2_1(
            const sp<android::hardware::gnss::V2_1::IGnssMeasurement>& iGnssMeasurement);
    jboolean setCallback(const std::unique_ptr<GnssMeasurementCallback>& callback,
                         bool enableFullTracking, bool enableCorrVecOutputs) override;

private:
    const sp<android::hardware::gnss::V2_1::IGnssMeasurement> mIGnssMeasurement_V2_1;
};

} // namespace android::gnss

#endif // _ANDROID_SERVER_GNSS_GNSSMEASUREMENT_H