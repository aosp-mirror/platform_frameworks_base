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

#define LOG_TAG "AGnssCbJni"

#include "AGnssCallback.h"

namespace android::gnss {

using binder::Status;
using hardware::Return;
using hardware::Void;
using IAGnssCallback_V1_0 = android::hardware::gnss::V1_0::IAGnssCallback;
using IAGnssCallback_V2_0 = android::hardware::gnss::V2_0::IAGnssCallback;

namespace {

jmethodID method_reportAGpsStatus;

}

void AGnss_class_init_once(JNIEnv* env, jclass clazz) {
    method_reportAGpsStatus = env->GetMethodID(clazz, "reportAGpsStatus", "(II[B)V");
}

Status AGnssCallbackAidl::agnssStatusCb(AGnssType type, AGnssStatusValue status) {
    AGnssCallbackUtil::agnssStatusCbImpl(type, status);
    return Status::ok();
}

Return<void> AGnssCallback_V1_0::agnssStatusIpV6Cb(
        const IAGnssCallback_V1_0::AGnssStatusIpV6& agps_status) {
    JNIEnv* env = getJniEnv();
    jbyteArray byteArray = nullptr;

    byteArray = env->NewByteArray(16);
    if (byteArray != nullptr) {
        env->SetByteArrayRegion(byteArray, 0, 16, (const jbyte*)(agps_status.ipV6Addr.data()));
    } else {
        ALOGE("Unable to allocate byte array for IPv6 address.");
    }

    IF_ALOGD() {
        // log the IP for reference in case there is a bogus value pushed by HAL
        char str[INET6_ADDRSTRLEN];
        inet_ntop(AF_INET6, agps_status.ipV6Addr.data(), str, INET6_ADDRSTRLEN);
        ALOGD("AGPS IP is v6: %s", str);
    }

    jsize byteArrayLength = byteArray != nullptr ? env->GetArrayLength(byteArray) : 0;
    ALOGV("Passing AGPS IP addr: size %d", byteArrayLength);
    env->CallVoidMethod(mCallbacksObj, method_reportAGpsStatus, agps_status.type,
                        agps_status.status, byteArray);

    checkAndClearExceptionFromCallback(env, __FUNCTION__);

    if (byteArray) {
        env->DeleteLocalRef(byteArray);
    }

    return Void();
}

Return<void> AGnssCallback_V1_0::agnssStatusIpV4Cb(
        const IAGnssCallback_V1_0::AGnssStatusIpV4& agps_status) {
    JNIEnv* env = getJniEnv();
    jbyteArray byteArray = nullptr;

    uint32_t ipAddr = agps_status.ipV4Addr;
    byteArray = convertToIpV4(ipAddr);

    IF_ALOGD() {
        /*
         * log the IP for reference in case there is a bogus value pushed by
         * HAL.
         */
        char str[INET_ADDRSTRLEN];
        inet_ntop(AF_INET, &ipAddr, str, INET_ADDRSTRLEN);
        ALOGD("AGPS IP is v4: %s", str);
    }

    jsize byteArrayLength = byteArray != nullptr ? env->GetArrayLength(byteArray) : 0;
    ALOGV("Passing AGPS IP addr: size %d", byteArrayLength);
    env->CallVoidMethod(mCallbacksObj, method_reportAGpsStatus, agps_status.type,
                        agps_status.status, byteArray);

    checkAndClearExceptionFromCallback(env, __FUNCTION__);

    if (byteArray) {
        env->DeleteLocalRef(byteArray);
    }
    return Void();
}

jbyteArray AGnssCallback_V1_0::convertToIpV4(uint32_t ip) {
    if (INADDR_NONE == ip) {
        return nullptr;
    }

    JNIEnv* env = getJniEnv();
    jbyteArray byteArray = env->NewByteArray(4);
    if (byteArray == nullptr) {
        ALOGE("Unable to allocate byte array for IPv4 address");
        return nullptr;
    }

    jbyte ipv4[4];
    ALOGV("Converting IPv4 address byte array (net_order) %x", ip);
    memcpy(ipv4, &ip, sizeof(ipv4));
    env->SetByteArrayRegion(byteArray, 0, 4, (const jbyte*)ipv4);
    return byteArray;
}

Return<void> AGnssCallback_V2_0::agnssStatusCb(IAGnssCallback_V2_0::AGnssType type,
                                               IAGnssCallback_V2_0::AGnssStatusValue status) {
    AGnssCallbackUtil::agnssStatusCbImpl(type, status);
    return Void();
}

} // namespace android::gnss
