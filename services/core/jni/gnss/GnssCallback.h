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

#ifndef _ANDROID_SERVER_GNSS_GNSSCALLBACK_H
#define _ANDROID_SERVER_GNSS_GNSSCALLBACK_H

#pragma once

#ifndef LOG_TAG
#error LOG_TAG must be defined before including this file.
#endif

#include <android/hardware/gnss/2.1/IGnss.h>
#include <android/hardware/gnss/BnGnssCallback.h>
#include <log/log.h>

#include <vector>

#include "Utils.h"
#include "jni.h"

namespace android::gnss {

namespace {

extern jmethodID method_reportLocation;
extern jmethodID method_reportStatus;
extern jmethodID method_reportSvStatus;
extern jmethodID method_reportNmea;
extern jmethodID method_setTopHalCapabilities;
extern jmethodID method_setGnssYearOfHardware;
extern jmethodID method_setGnssHardwareModelName;
extern jmethodID method_requestLocation;
extern jmethodID method_requestUtcTime;

} // anonymous namespace

extern bool isSvStatusRegistered;
extern bool isNmeaRegistered;

extern jmethodID method_reportGnssServiceDied;

void Gnss_class_init_once(JNIEnv* env, jclass& clazz);

/*
 * GnssCallbackAidl class implements the callback methods for AIDL IGnssCallback interface.
 */
class GnssCallbackAidl : public hardware::gnss::BnGnssCallback {
public:
    GnssCallbackAidl(int version) : interfaceVersion(version){};
    binder::Status gnssSetCapabilitiesCb(const int capabilities) override;
    binder::Status gnssSetSignalTypeCapabilitiesCb(
            const std::vector<android::hardware::gnss::GnssSignalType>& signalTypes) override;
    binder::Status gnssStatusCb(const GnssStatusValue status) override;
    binder::Status gnssSvStatusCb(const std::vector<GnssSvInfo>& svInfoList) override;
    binder::Status gnssLocationCb(const hardware::gnss::GnssLocation& location) override;
    binder::Status gnssNmeaCb(const int64_t timestamp, const std::string& nmea) override;
    binder::Status gnssAcquireWakelockCb() override;
    binder::Status gnssReleaseWakelockCb() override;
    binder::Status gnssSetSystemInfoCb(const GnssSystemInfo& info) override;
    binder::Status gnssRequestTimeCb() override;
    binder::Status gnssRequestLocationCb(const bool independentFromGnss,
                                         const bool isUserEmergency) override;

private:
    const int interfaceVersion;
};

/*
 * GnssCallbackHidl class implements the callback methods for HIDL IGnssCallback interface.
 */
struct GnssCallbackHidl : public hardware::gnss::V2_1::IGnssCallback {
    hardware::Return<void> gnssLocationCb(
            const hardware::gnss::V1_0::GnssLocation& location) override;
    hardware::Return<void> gnssStatusCb(
            const hardware::gnss::V1_0::IGnssCallback::GnssStatusValue status) override;
    hardware::Return<void> gnssSvStatusCb(
            const hardware::gnss::V1_0::IGnssCallback::GnssSvStatus& svStatus) override {
        return gnssSvStatusCbImpl<hardware::gnss::V1_0::IGnssCallback::GnssSvStatus,
                                  hardware::gnss::V1_0::IGnssCallback::GnssSvInfo>(svStatus);
    }
    hardware::Return<void> gnssNmeaCb(int64_t timestamp,
                                      const hardware::hidl_string& nmea) override;
    hardware::Return<void> gnssSetCapabilitesCb(uint32_t capabilities) override;
    hardware::Return<void> gnssAcquireWakelockCb() override;
    hardware::Return<void> gnssReleaseWakelockCb() override;
    hardware::Return<void> gnssRequestTimeCb() override;
    hardware::Return<void> gnssRequestLocationCb(const bool independentFromGnss) override;

    hardware::Return<void> gnssSetSystemInfoCb(
            const hardware::gnss::V1_0::IGnssCallback::GnssSystemInfo& info) override;

    // New in 1.1
    hardware::Return<void> gnssNameCb(const hardware::hidl_string& name) override;

    // New in 2.0
    hardware::Return<void> gnssRequestLocationCb_2_0(const bool independentFromGnss,
                                                     const bool isUserEmergency) override;
    hardware::Return<void> gnssSetCapabilitiesCb_2_0(uint32_t capabilities) override;
    hardware::Return<void> gnssLocationCb_2_0(
            const hardware::gnss::V2_0::GnssLocation& location) override;
    hardware::Return<void> gnssSvStatusCb_2_0(
            const hardware::hidl_vec<hardware::gnss::V2_0::IGnssCallback::GnssSvInfo>& svInfoList)
            override {
        return gnssSvStatusCbImpl<
                hardware::hidl_vec<hardware::gnss::V2_0::IGnssCallback::GnssSvInfo>,
                hardware::gnss::V1_0::IGnssCallback::GnssSvInfo>(svInfoList);
    }

    // New in 2.1
    hardware::Return<void> gnssSvStatusCb_2_1(
            const hardware::hidl_vec<hardware::gnss::V2_1::IGnssCallback::GnssSvInfo>& svInfoList)
            override {
        return gnssSvStatusCbImpl<
                hardware::hidl_vec<hardware::gnss::V2_1::IGnssCallback::GnssSvInfo>,
                hardware::gnss::V1_0::IGnssCallback::GnssSvInfo>(svInfoList);
    }
    hardware::Return<void> gnssSetCapabilitiesCb_2_1(uint32_t capabilities) override;

    // TODO: Reconsider allocation cost vs threadsafety on these statics
    static const char* sNmeaString;
    static size_t sNmeaStringLength;

    template <class T>
    static hardware::Return<void> gnssLocationCbImpl(const T& location);

    template <class T_list, class T_sv_info>
    static hardware::Return<void> gnssSvStatusCbImpl(const T_list& svStatus);

private:
    template <class T>
    static uint32_t getHasBasebandCn0DbHzFlag(const T& svStatus) {
        return 0;
    }

    template <class T>
    static double getBasebandCn0DbHz(const T& svStatus, size_t i) {
        return 0.0;
    }

    template <class T>
    static uint32_t getGnssSvInfoListSize(const T& svInfoList) {
        return svInfoList.size();
    }

    static const hardware::gnss::IGnssCallback::GnssSvInfo& getGnssSvInfoOfIndex(
            const std::vector<hardware::gnss::IGnssCallback::GnssSvInfo>& svInfoList, size_t i) {
        return svInfoList[i];
    }

    static const hardware::gnss::V1_0::IGnssCallback::GnssSvInfo& getGnssSvInfoOfIndex(
            const hardware::gnss::V1_0::IGnssCallback::GnssSvStatus& svStatus, size_t i) {
        return svStatus.gnssSvList.data()[i];
    }

    static const hardware::gnss::V1_0::IGnssCallback::GnssSvInfo& getGnssSvInfoOfIndex(
            const hardware::hidl_vec<hardware::gnss::V2_0::IGnssCallback::GnssSvInfo>& svInfoList,
            size_t i) {
        return svInfoList[i].v1_0;
    }

    static const hardware::gnss::V1_0::IGnssCallback::GnssSvInfo& getGnssSvInfoOfIndex(
            const hardware::hidl_vec<hardware::gnss::V2_1::IGnssCallback::GnssSvInfo>& svInfoList,
            size_t i) {
        return svInfoList[i].v2_0.v1_0;
    }

    template <class T>
    static uint32_t getConstellationType(const T& svInfoList, size_t i) {
        return static_cast<uint32_t>(svInfoList[i].constellation);
    }
};

} // namespace android::gnss

#endif // _ANDROID_SERVER_GNSS_GNSSCALLBACK_H
