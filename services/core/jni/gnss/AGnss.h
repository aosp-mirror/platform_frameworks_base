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

#ifndef _ANDROID_SERVER_GNSS_AGNSS_H
#define _ANDROID_SERVER_GNSS_AGNSS_H

#pragma once

#ifndef LOG_TAG
#error LOG_TAG must be defined before including this file.
#endif

#include <android/hardware/gnss/BnAGnss.h>
#include <log/log.h>

#include "AGnssCallback.h"
#include "jni.h"

namespace android::gnss {

class AGnssInterface {
public:
    virtual ~AGnssInterface() {}
    virtual jboolean setCallback(const std::unique_ptr<AGnssCallback>& callback) = 0;
    virtual jboolean dataConnOpen(JNIEnv* env, jlong networkHandle, jstring apn,
                                  jint apnIpType) = 0;
    virtual jboolean dataConnClosed() = 0;
    virtual jboolean dataConnFailed() = 0;
    virtual jboolean setServer(JNIEnv* env, jint type, jstring hostname, jint port) = 0;
};

class AGnss : public AGnssInterface {
public:
    AGnss(const sp<android::hardware::gnss::IAGnss>& iAGnss);
    jboolean setCallback(const std::unique_ptr<AGnssCallback>& callback) override;
    jboolean dataConnOpen(JNIEnv* env, jlong networkHandle, jstring apn, jint apnIpType) override;
    jboolean dataConnClosed() override;
    jboolean dataConnFailed() override;
    jboolean setServer(JNIEnv* env, jint type, jstring hostname, jint port) override;

private:
    const sp<android::hardware::gnss::IAGnss> mIAGnss;
};

class AGnss_V1_0 : public AGnssInterface {
public:
    AGnss_V1_0(const sp<android::hardware::gnss::V1_0::IAGnss>& iAGnss);
    jboolean setCallback(const std::unique_ptr<AGnssCallback>& callback) override;
    jboolean dataConnOpen(JNIEnv* env, jlong, jstring apn, jint apnIpType) override;
    jboolean dataConnClosed() override;
    jboolean dataConnFailed() override;
    jboolean setServer(JNIEnv* env, jint type, jstring hostname, jint port) override;

private:
    const sp<android::hardware::gnss::V1_0::IAGnss> mIAGnss_V1_0;
};

class AGnss_V2_0 : public AGnssInterface {
public:
    AGnss_V2_0(const sp<android::hardware::gnss::V2_0::IAGnss>& iAGnss);
    jboolean setCallback(const std::unique_ptr<AGnssCallback>& callback) override;
    jboolean dataConnOpen(JNIEnv* env, jlong networkHandle, jstring apn, jint apnIpType) override;
    jboolean dataConnClosed() override;
    jboolean dataConnFailed() override;
    jboolean setServer(JNIEnv* env, jint type, jstring hostname, jint port) override;

private:
    const sp<android::hardware::gnss::V2_0::IAGnss> mIAGnss_V2_0;
};

} // namespace android::gnss

#endif // _ANDROID_SERVER_GNSS_AGNSS_H
