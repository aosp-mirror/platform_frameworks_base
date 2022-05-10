/*
 * Copyright (C) 2022 The Android Open Source Project
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

#ifndef _ANDROID_SERVER_GNSS_GNSSPSDS_H
#define _ANDROID_SERVER_GNSS_GNSSPSDS_H

#pragma once

#ifndef LOG_TAG
#error LOG_TAG must be defined before including this file.
#endif

#include <android/hardware/gnss/1.0/IGnssXtra.h>
#include <android/hardware/gnss/BnGnssPsds.h>
#include <log/log.h>

#include "GnssPsdsCallback.h"
#include "jni.h"

namespace android::gnss {

class GnssPsdsInterface {
public:
    virtual ~GnssPsdsInterface() {}
    virtual jboolean setCallback(const std::unique_ptr<GnssPsdsCallback>& callback);
    virtual void injectPsdsData(const jbyteArray& data, const jint& length, const jint& psdsType);
};

class GnssPsdsAidl : public GnssPsdsInterface {
public:
    GnssPsdsAidl(const sp<android::hardware::gnss::IGnssPsds>& iGnssPsds);
    jboolean setCallback(const std::unique_ptr<GnssPsdsCallback>& callback) override;
    void injectPsdsData(const jbyteArray& data, const jint& length, const jint& psdsType) override;

private:
    const sp<android::hardware::gnss::IGnssPsds> mIGnssPsds;
};

class GnssPsdsHidl : public GnssPsdsInterface {
public:
    GnssPsdsHidl(const sp<android::hardware::gnss::V1_0::IGnssXtra>& iGnssXtra);
    jboolean setCallback(const std::unique_ptr<GnssPsdsCallback>& callback) override;
    void injectPsdsData(const jbyteArray& data, const jint& length, const jint& psdsType) override;

private:
    const sp<android::hardware::gnss::V1_0::IGnssXtra> mIGnssXtra;
};

} // namespace android::gnss

#endif // _ANDROID_SERVER_GNSS_GNSSPSDS_H
