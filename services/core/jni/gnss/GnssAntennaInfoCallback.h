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

#ifndef _ANDROID_SERVER_GNSS_GNSSANTENNAINFOCALLBACK_H
#define _ANDROID_SERVER_GNSS_GNSSANTENNAINFOCALLBACK_H

#pragma once

#ifndef LOG_TAG
#error LOG_TAG must be defined before including this file.
#endif

#include <android/hardware/gnss/2.1/IGnssAntennaInfo.h>
#include <android/hardware/gnss/BnGnssAntennaInfoCallback.h>
#include <log/log.h>

#include "Utils.h"
#include "jni.h"

namespace android::gnss {

void GnssAntennaInfo_class_init_once(JNIEnv* env, jclass& clazz);

/*
 * GnssAntennaInfoCallbackAidl implements the callback methods required for the
 * android::hardware::gnss::IGnssAntennaInfo interface.
 */
class GnssAntennaInfoCallbackAidl : public android::hardware::gnss::BnGnssAntennaInfoCallback {
public:
    binder::Status gnssAntennaInfoCb(const std::vector<GnssAntennaInfo>& gnssAntennaInfos) override;
};

/*
 * GnssAntennaInfoCallback implements the callback methods required for the
 * V2_1::GnssAntennaInfo interface.
 */
class GnssAntennaInfoCallback_V2_1
      : public android::hardware::gnss::V2_1::IGnssAntennaInfoCallback {
public:
    // Methods from V2_1::GnssAntennaInfoCallback follow.
    hardware::Return<void> gnssAntennaInfoCb(
            const hardware::hidl_vec<
                    android::hardware::gnss::V2_1::IGnssAntennaInfoCallback::GnssAntennaInfo>&
                    gnssAntennaInfos) override;
};

class GnssAntennaInfoCallback {
public:
    GnssAntennaInfoCallback() {}
    sp<GnssAntennaInfoCallbackAidl> getAidl() {
        if (callbackAidl == nullptr) {
            callbackAidl = sp<GnssAntennaInfoCallbackAidl>::make();
        }
        return callbackAidl;
    }

    sp<GnssAntennaInfoCallback_V2_1> getV2_1() {
        if (callbackV2_1 == nullptr) {
            callbackV2_1 = sp<GnssAntennaInfoCallback_V2_1>::make();
        }
        return callbackV2_1;
    }

private:
    sp<GnssAntennaInfoCallbackAidl> callbackAidl;
    sp<GnssAntennaInfoCallback_V2_1> callbackV2_1;
};

struct GnssAntennaInfoCallbackUtil {
    template <template <class...> class T_vector, class T_info>
    static jobject translateAllGnssAntennaInfos(JNIEnv* env,
                                                const T_vector<T_info>& gnssAntennaInfos);

    template <class T>
    static jobject translateSingleGnssAntennaInfo(JNIEnv* env, const T& gnssAntennaInfo);

    template <class T>
    static jobject translatePhaseCenterOffset(JNIEnv* env, const T& gnssAntennaInfo);

    template <class T>
    static jobject translatePhaseCenterVariationCorrections(JNIEnv* env, const T& gnssAntennaInfo);

    template <class T>
    static jobject translateSignalGainCorrections(JNIEnv* env, const T& gnssAntennaInfo);

    template <template <class...> class T_vector, class T_info>
    static jobjectArray translate2dDoubleArray(JNIEnv* env, const T_vector<T_info>& array);

    template <template <class...> class T_vector, class T_info>
    static void translateAndReportGnssAntennaInfo(const T_vector<T_info>& gnssAntennaInfos);

    static void reportAntennaInfo(JNIEnv* env, const jobject antennaInfosArray);

    static double getCarrierFrequencyMHz(
            const android::hardware::gnss::IGnssAntennaInfoCallback::GnssAntennaInfo&
                    gnssAntennaInfo) {
        return gnssAntennaInfo.carrierFrequencyHz * 1e-6;
    };

    static double getCarrierFrequencyMHz(
            const android::hardware::gnss::V2_1::IGnssAntennaInfoCallback::GnssAntennaInfo&
                    gnssAntennaInfo) {
        return gnssAntennaInfo.carrierFrequencyMHz;
    };
};

} // namespace android::gnss

#endif // _ANDROID_SERVER_GNSS_GNSSANTENNAINFOCALLBACK_H
