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

#ifndef _ANDROID_SERVER_GNSS_GNSSANTENNAINFO_H
#define _ANDROID_SERVER_GNSS_GNSSANTENNAINFO_H

#pragma once

#ifndef LOG_TAG
#error LOG_TAG must be defined before including this file.
#endif

#include <android/hardware/gnss/2.1/IGnssAntennaInfo.h>
#include <android/hardware/gnss/BnGnssAntennaInfo.h>
#include <log/log.h>

#include "GnssAntennaInfoCallback.h"
#include "jni.h"

namespace android::gnss {

class GnssAntennaInfoInterface {
public:
    virtual ~GnssAntennaInfoInterface() {}
    virtual jboolean setCallback(const std::unique_ptr<GnssAntennaInfoCallback>& callback) = 0;
    virtual jboolean close() = 0;
};

class GnssAntennaInfoAidl : public GnssAntennaInfoInterface {
public:
    GnssAntennaInfoAidl(const sp<android::hardware::gnss::IGnssAntennaInfo>& iGnssAntennaInfo);
    jboolean setCallback(const std::unique_ptr<GnssAntennaInfoCallback>& callback) override;
    jboolean close() override;

private:
    const sp<android::hardware::gnss::IGnssAntennaInfo> mIGnssAntennaInfoAidl;
};

class GnssAntennaInfo_V2_1 : public GnssAntennaInfoInterface {
public:
    GnssAntennaInfo_V2_1(
            const sp<android::hardware::gnss::V2_1::IGnssAntennaInfo>& iGnssAntennaInfo);
    jboolean setCallback(const std::unique_ptr<GnssAntennaInfoCallback>& callback) override;
    jboolean close() override;

private:
    const sp<android::hardware::gnss::V2_1::IGnssAntennaInfo> mIGnssAntennaInfo_V2_1;
};

} // namespace android::gnss

#endif // _ANDROID_SERVER_GNSS_GNSSANTENNAINFO_H
