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
#define LOG_TAG "AGnssJni"

#include "AGnss.h"

#include "Utils.h"

using android::hardware::gnss::IAGnss;
using IAGnss_V1_0 = android::hardware::gnss::V1_0::IAGnss;
using IAGnss_V2_0 = android::hardware::gnss::V2_0::IAGnss;
using IAGnssCallback_V1_0 = android::hardware::gnss::V1_0::IAGnssCallback;
using IAGnssCallback_V2_0 = android::hardware::gnss::V2_0::IAGnssCallback;
using AGnssType = android::hardware::gnss::IAGnssCallback::AGnssType;

namespace android::gnss {

// Implementation of AGnss (AIDL HAL)

AGnss::AGnss(const sp<IAGnss>& iAGnss) : mIAGnss(iAGnss) {
    assert(mIAGnss != nullptr);
}

jboolean AGnss::setCallback(const std::unique_ptr<AGnssCallback>& callback) {
    auto status = mIAGnss->setCallback(callback->getAidl());
    return checkAidlStatus(status, "IAGnssAidl setCallback() failed.");
}

jboolean AGnss::dataConnOpen(JNIEnv* env, jlong networkHandle, jstring apn, jint apnIpType) {
    ScopedJniString jniApn{env, apn};
    auto status = mIAGnss->dataConnOpen(networkHandle, std::string(jniApn.c_str()),
                                        static_cast<IAGnss::ApnIpType>(apnIpType));
    return checkAidlStatus(status,
                           "IAGnssAidl dataConnOpen() failed. APN and its IP type not set.");
}

jboolean AGnss::dataConnClosed() {
    auto status = mIAGnss->dataConnClosed();
    return checkAidlStatus(status, "IAGnssAidl dataConnClosed() failed.");
}

jboolean AGnss::dataConnFailed() {
    auto status = mIAGnss->dataConnFailed();
    return checkAidlStatus(status, "IAGnssAidl dataConnFailed() failed.");
}

jboolean AGnss::setServer(JNIEnv* env, jint type, jstring hostname, jint port) {
    ScopedJniString jniHostName{env, hostname};
    auto status = mIAGnss->setServer(static_cast<AGnssType>(type), std::string(jniHostName.c_str()),
                                     port);
    return checkAidlStatus(status, "IAGnssAidl setServer() failed. Host name and port not set.");
}

// Implementation of AGnss_V1_0

AGnss_V1_0::AGnss_V1_0(const sp<IAGnss_V1_0>& iAGnss) : mIAGnss_V1_0(iAGnss) {
    assert(mIAGnss_V1_0 != nullptr);
}

jboolean AGnss_V1_0::setCallback(const std::unique_ptr<AGnssCallback>& callback) {
    auto result = mIAGnss_V1_0->setCallback(callback->getV1_0());
    return checkHidlReturn(result, "IAGnss_V1_0 setCallback() failed.");
}

jboolean AGnss_V1_0::dataConnOpen(JNIEnv* env, jlong, jstring apn, jint apnIpType) {
    ScopedJniString jniApn{env, apn};
    auto result =
            mIAGnss_V1_0->dataConnOpen(jniApn, static_cast<IAGnss_V1_0::ApnIpType>(apnIpType));
    return checkHidlReturn(result,
                           "IAGnss_V1_0 dataConnOpen() failed. APN and its IP type not set.");
}

jboolean AGnss_V1_0::dataConnClosed() {
    auto result = mIAGnss_V1_0->dataConnClosed();
    return checkHidlReturn(result, "IAGnss_V1_0 dataConnClosed() failed.");
}

jboolean AGnss_V1_0::dataConnFailed() {
    auto result = mIAGnss_V1_0->dataConnFailed();
    return checkHidlReturn(result, "IAGnss_V1_0 dataConnFailed() failed.");
}

jboolean AGnss_V1_0::setServer(JNIEnv* env, jint type, jstring hostname, jint port) {
    ScopedJniString jniHostName{env, hostname};
    auto result = mIAGnss_V1_0->setServer(static_cast<IAGnssCallback_V1_0::AGnssType>(type),
                                          jniHostName, port);
    return checkHidlReturn(result, "IAGnss_V1_0 setServer() failed. Host name and port not set.");
}

// Implementation of AGnss_V2_0

AGnss_V2_0::AGnss_V2_0(const sp<IAGnss_V2_0>& iAGnss) : mIAGnss_V2_0(iAGnss) {
    assert(mIAGnss_V2_0 != nullptr);
}

jboolean AGnss_V2_0::setCallback(const std::unique_ptr<AGnssCallback>& callback) {
    auto result = mIAGnss_V2_0->setCallback(callback->getV2_0());
    return checkHidlReturn(result, "IAGnss_V2_0 setCallback() failed.");
}

jboolean AGnss_V2_0::dataConnOpen(JNIEnv* env, jlong networkHandle, jstring apn, jint apnIpType) {
    ScopedJniString jniApn{env, apn};
    auto result = mIAGnss_V2_0->dataConnOpen(static_cast<uint64_t>(networkHandle), jniApn,
                                             static_cast<IAGnss_V2_0::ApnIpType>(apnIpType));
    return checkHidlReturn(result,
                           "IAGnss_V2_0 dataConnOpen() failed. APN and its IP type not set.");
}

jboolean AGnss_V2_0::dataConnClosed() {
    auto result = mIAGnss_V2_0->dataConnClosed();
    return checkHidlReturn(result, "IAGnss_V2_0 dataConnClosed() failed.");
}

jboolean AGnss_V2_0::dataConnFailed() {
    auto result = mIAGnss_V2_0->dataConnFailed();
    return checkHidlReturn(result, "IAGnss_V2_0 dataConnFailed() failed.");
}

jboolean AGnss_V2_0::setServer(JNIEnv* env, jint type, jstring hostname, jint port) {
    ScopedJniString jniHostName{env, hostname};
    auto result = mIAGnss_V2_0->setServer(static_cast<IAGnssCallback_V2_0::AGnssType>(type),
                                          jniHostName, port);
    return checkHidlReturn(result, "IAGnss_V2_0 setServer() failed. Host name and port not set.");
}

} // namespace android::gnss
