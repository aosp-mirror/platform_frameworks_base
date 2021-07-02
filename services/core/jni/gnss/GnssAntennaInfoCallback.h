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
#include <log/log.h>
#include "Utils.h"
#include "jni.h"

namespace android::gnss {

void GnssAntennaInfo_class_init_once(JNIEnv* env, jclass& clazz);

/*
 * GnssAntennaInfoCallback implements the callback methods required for the
 * GnssAntennaInfo interface.
 */
struct GnssAntennaInfoCallback : public android::hardware::gnss::V2_1::IGnssAntennaInfoCallback {
    GnssAntennaInfoCallback(jobject& callbacksObj) : mCallbacksObj(callbacksObj) {}
    // Methods from V2_1::GnssAntennaInfoCallback follow.
    hardware::Return<void> gnssAntennaInfoCb(
            const hardware::hidl_vec<
                    android::hardware::gnss::V2_1::IGnssAntennaInfoCallback::GnssAntennaInfo>&
                    gnssAntennaInfos);

private:
    jobject translateAllGnssAntennaInfos(
            JNIEnv* env,
            const hardware::hidl_vec<
                    android::hardware::gnss::V2_1::IGnssAntennaInfoCallback::GnssAntennaInfo>&
                    gnssAntennaInfos);
    jobject translateSingleGnssAntennaInfo(
            JNIEnv* env,
            const android::hardware::gnss::V2_1::IGnssAntennaInfoCallback::GnssAntennaInfo&
                    gnssAntennaInfo);
    jobject translatePhaseCenterOffset(
            JNIEnv* env,
            const android::hardware::gnss::V2_1::IGnssAntennaInfoCallback::GnssAntennaInfo&
                    gnssAntennaInfo);
    jobject translatePhaseCenterVariationCorrections(
            JNIEnv* env,
            const android::hardware::gnss::V2_1::IGnssAntennaInfoCallback::GnssAntennaInfo&
                    gnssAntennaInfo);
    jobject translateSignalGainCorrections(
            JNIEnv* env,
            const android::hardware::gnss::V2_1::IGnssAntennaInfoCallback::GnssAntennaInfo&
                    gnssAntennaInfo);
    jobjectArray translate2dDoubleArray(
            JNIEnv* env,
            const hardware::hidl_vec<android::hardware::gnss::V2_1::IGnssAntennaInfoCallback::Row>&
                    array);
    void translateAndReportGnssAntennaInfo(
            const hardware::hidl_vec<
                    android::hardware::gnss::V2_1::IGnssAntennaInfoCallback::GnssAntennaInfo>&
                    gnssAntennaInfos);
    void reportAntennaInfo(JNIEnv* env, const jobject antennaInfosArray);

    jobject& mCallbacksObj;
};

} // namespace android::gnss

#endif // _ANDROID_SERVER_GNSS_GNSSANTENNAINFOCALLBACK_H
