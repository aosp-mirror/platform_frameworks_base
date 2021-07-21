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

#ifndef _ANDROID_SERVER_GNSS_GNSSCONFIGURATION_H
#define _ANDROID_SERVER_GNSS_GNSSCONFIGURATION_H

#pragma once

#ifndef LOG_TAG
#error LOG_TAG must be defined before including this file.
#endif

#include <android/hardware/gnss/1.0/IGnssConfiguration.h>
#include <android/hardware/gnss/1.1/IGnssConfiguration.h>
#include <android/hardware/gnss/2.0/IGnssConfiguration.h>
#include <android/hardware/gnss/2.1/IGnssConfiguration.h>
#include <android/hardware/gnss/BnGnssConfiguration.h>
#include <log/log.h>

#include "jni.h"

namespace android::gnss {

void GnssConfiguration_class_init_once(JNIEnv* env);

class GnssConfigurationInterface {
public:
    virtual ~GnssConfigurationInterface() {}
    virtual jobject getVersion(JNIEnv* env) = 0;
    virtual jboolean setEmergencySuplPdn(jint enable) = 0;
    virtual jboolean setSuplVersion(jint version) = 0;
    virtual jboolean setSuplEs(jint enable) = 0;
    virtual jboolean setSuplMode(jint mode) = 0;
    virtual jboolean setGpsLock(jint gpsLock) = 0;
    virtual jboolean setLppProfile(jint lppProfile) = 0;
    virtual jboolean setGlonassPositioningProtocol(jint gnssPosProtocol) = 0;
    virtual jboolean setEsExtensionSec(jint emergencyExtensionSeconds) = 0;
    virtual jboolean setBlocklist(JNIEnv* env, jintArray& constellations, jintArray& sv_ids) = 0;

protected:
    template <class T_BlocklistSource, class T_ConstellationType>
    hardware::hidl_vec<T_BlocklistSource> getBlocklistedSources(JNIEnv* env,
                                                                jintArray& constellations,
                                                                jintArray& sv_ids) {
        jint* constellation_array = env->GetIntArrayElements(constellations, 0);
        if (nullptr == constellation_array) {
            ALOGI("GetIntArrayElements returns nullptr.");
            return JNI_FALSE;
        }

        jsize length = env->GetArrayLength(constellations);

        jint* sv_id_array = env->GetIntArrayElements(sv_ids, 0);
        if (nullptr == sv_id_array) {
            ALOGI("GetIntArrayElements returns nullptr.");
            return JNI_FALSE;
        }

        if (length != env->GetArrayLength(sv_ids)) {
            ALOGI("Lengths of constellations and sv_ids are inconsistent.");
            return JNI_FALSE;
        }

        hardware::hidl_vec<T_BlocklistSource> sources;
        sources.resize(length);

        for (int i = 0; i < length; i++) {
            sources[i].constellation = static_cast<T_ConstellationType>(constellation_array[i]);
            sources[i].svid = sv_id_array[i];
        }

        env->ReleaseIntArrayElements(constellations, constellation_array, 0);
        env->ReleaseIntArrayElements(sv_ids, sv_id_array, 0);

        return sources;
    }
};

class GnssConfiguration : public GnssConfigurationInterface {
public:
    GnssConfiguration(const sp<android::hardware::gnss::IGnssConfiguration>& iGnssConfiguration);
    jobject getVersion(JNIEnv* env) override;
    jboolean setEmergencySuplPdn(jint enable) override;
    jboolean setSuplVersion(jint version) override;
    jboolean setSuplEs(jint enable) override;
    jboolean setSuplMode(jint mode) override;
    jboolean setGpsLock(jint gpsLock) override;
    jboolean setLppProfile(jint lppProfile) override;
    jboolean setGlonassPositioningProtocol(jint gnssPosProtocol) override;
    jboolean setEsExtensionSec(jint emergencyExtensionSeconds) override;
    jboolean setBlocklist(JNIEnv* env, jintArray& constellations, jintArray& sv_ids) override;

private:
    const sp<android::hardware::gnss::IGnssConfiguration> mIGnssConfiguration;
};

class GnssConfiguration_V1_0 : public GnssConfigurationInterface {
public:
    GnssConfiguration_V1_0(
            const sp<android::hardware::gnss::V1_0::IGnssConfiguration>& iGnssConfiguration);
    jobject getVersion(JNIEnv* env) override;
    jboolean setEmergencySuplPdn(jint enable);
    jboolean setSuplVersion(jint version) override;
    jboolean setSuplEs(jint enable) override;
    jboolean setSuplMode(jint mode) override;
    jboolean setGpsLock(jint gpsLock) override;
    jboolean setLppProfile(jint lppProfile);
    jboolean setGlonassPositioningProtocol(jint gnssPosProtocol) override;
    jboolean setEsExtensionSec(jint emergencyExtensionSeconds) override;
    jboolean setBlocklist(JNIEnv* env, jintArray& constellations, jintArray& sv_ids) override;

private:
    const sp<android::hardware::gnss::V1_0::IGnssConfiguration> mIGnssConfiguration_V1_0;
};

class GnssConfiguration_V1_1 : public GnssConfiguration_V1_0 {
public:
    GnssConfiguration_V1_1(
            const sp<android::hardware::gnss::V1_1::IGnssConfiguration>& iGnssConfiguration);

    jobject getVersion(JNIEnv* env) override;

    jboolean setBlocklist(JNIEnv* env, jintArray& constellations, jintArray& sv_ids) override;

private:
    const sp<android::hardware::gnss::V1_1::IGnssConfiguration> mIGnssConfiguration_V1_1;
};

class GnssConfiguration_V2_0 : public GnssConfiguration_V1_1 {
public:
    GnssConfiguration_V2_0(
            const sp<android::hardware::gnss::V2_0::IGnssConfiguration>& iGnssConfiguration);
    jobject getVersion(JNIEnv* env) override;
    jboolean setSuplEs(jint enable) override;
    jboolean setGpsLock(jint enable) override;
    jboolean setEsExtensionSec(jint emergencyExtensionSeconds) override;

private:
    const sp<android::hardware::gnss::V2_0::IGnssConfiguration> mIGnssConfiguration_V2_0;
};

class GnssConfiguration_V2_1 : public GnssConfiguration_V2_0 {
public:
    GnssConfiguration_V2_1(
            const sp<android::hardware::gnss::V2_1::IGnssConfiguration>& iGnssConfiguration);
    jobject getVersion(JNIEnv* env) override;
    jboolean setBlocklist(JNIEnv* env, jintArray& constellations, jintArray& sv_ids) override;

private:
    const sp<android::hardware::gnss::V2_1::IGnssConfiguration> mIGnssConfiguration_V2_1;
};

} // namespace android::gnss

#endif // _ANDROID_SERVER_GNSS_GNSSCONFIGURATION_H
