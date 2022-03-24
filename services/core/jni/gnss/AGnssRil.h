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

#ifndef _ANDROID_SERVER_GNSS_AGNSSRIL_H
#define _ANDROID_SERVER_GNSS_AGNSSRIL_H

#pragma once

#ifndef LOG_TAG
#error LOG_TAG must be defined before including this file.
#endif

#include <android/hardware/gnss/1.0/IAGnssRil.h>
#include <android/hardware/gnss/2.0/IAGnssRil.h>
#include <android/hardware/gnss/BnAGnssRil.h>
#include <log/log.h>

#include "AGnssRilCallback.h"
#include "jni.h"

namespace android::gnss {

class AGnssRilInterface {
public:
    virtual ~AGnssRilInterface() {}
    virtual jboolean setCallback(const std::unique_ptr<AGnssRilCallback>& callback) = 0;
    virtual jboolean setSetId(jint type, const jstring& setid_string) = 0;
    virtual jboolean setRefLocation(jint type, jint mcc, jint mnc, jint lac, jlong cid, jint tac,
                                    jint pcid, jint arfcn) = 0;
    virtual jboolean updateNetworkState(jboolean connected, jint type, jboolean roaming,
                                        jboolean available, const jstring& apn, jlong networkHandle,
                                        jshort capabilities) = 0;
};

class AGnssRil : public AGnssRilInterface {
public:
    AGnssRil(const sp<android::hardware::gnss::IAGnssRil>& iAGnssRil);
    jboolean setCallback(const std::unique_ptr<AGnssRilCallback>& callback) override;
    jboolean setSetId(jint type, const jstring& setid_string) override;
    jboolean setRefLocation(jint type, jint mcc, jint mnc, jint lac, jlong cid, jint tac, jint pcid,
                            jint arfcn) override;
    jboolean updateNetworkState(jboolean connected, jint type, jboolean roaming, jboolean available,
                                const jstring& apn, jlong networkHandle,
                                jshort capabilities) override;

private:
    const sp<android::hardware::gnss::IAGnssRil> mIAGnssRil;
};

class AGnssRil_V1_0 : public AGnssRilInterface {
public:
    AGnssRil_V1_0(const sp<android::hardware::gnss::V1_0::IAGnssRil>& iAGnssRil);
    jboolean setCallback(const std::unique_ptr<AGnssRilCallback>& callback) override;
    jboolean setSetId(jint type, const jstring& setid_string) override;
    jboolean setRefLocation(jint type, jint mcc, jint mnc, jint lac, jlong cid, jint, jint,
                            jint) override;
    jboolean updateNetworkState(jboolean connected, jint type, jboolean roaming, jboolean available,
                                const jstring& apn, jlong networkHandle,
                                jshort capabilities) override;

private:
    const sp<android::hardware::gnss::V1_0::IAGnssRil> mAGnssRil_V1_0;
};

class AGnssRil_V2_0 : public AGnssRil_V1_0 {
public:
    AGnssRil_V2_0(const sp<android::hardware::gnss::V2_0::IAGnssRil>& iAGnssRil);
    jboolean updateNetworkState(jboolean connected, jint type, jboolean roaming, jboolean available,
                                const jstring& apn, jlong networkHandle,
                                jshort capabilities) override;

private:
    const sp<android::hardware::gnss::V2_0::IAGnssRil> mAGnssRil_V2_0;
};

} // namespace android::gnss

#endif // _ANDROID_SERVER_GNSS_AGNSSRIL_H
