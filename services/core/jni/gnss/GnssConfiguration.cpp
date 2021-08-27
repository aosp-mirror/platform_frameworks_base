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

// Define LOG_TAG before <log/log.h> to overwrite the default value.
#define LOG_TAG "GnssConfigurationJni"

#include "GnssConfiguration.h"
#include "Utils.h"

using android::hardware::gnss::GnssConstellationType;
using GnssConstellationType_V1_0 = android::hardware::gnss::V1_0::GnssConstellationType;
using GnssConstellationType_V2_0 = android::hardware::gnss::V2_0::GnssConstellationType;

using android::binder::Status;
using android::hardware::hidl_vec;
using android::hardware::Return;

using android::hardware::gnss::IGnssConfiguration;
using IGnssConfiguration_V1_0 = android::hardware::gnss::V1_0::IGnssConfiguration;
using IGnssConfiguration_V1_1 = android::hardware::gnss::V1_1::IGnssConfiguration;
using IGnssConfiguration_V2_0 = android::hardware::gnss::V2_0::IGnssConfiguration;
using IGnssConfiguration_V2_1 = android::hardware::gnss::V2_1::IGnssConfiguration;

using android::hardware::gnss::BlocklistedSource;
using BlocklistedSource_V1_1 = IGnssConfiguration_V1_1::BlacklistedSource;
using BlocklistedSource_V2_1 = IGnssConfiguration_V2_1::BlacklistedSource;

namespace {

jclass class_gnssConfiguration_halInterfaceVersion;
jmethodID method_halInterfaceVersionCtor;

jobject createHalInterfaceVersionJavaObject(JNIEnv* env, jint major, jint minor) {
    return env->NewObject(class_gnssConfiguration_halInterfaceVersion,
                          method_halInterfaceVersionCtor, major, minor);
}

} // anonymous namespace

namespace android::gnss {

void GnssConfiguration_class_init_once(JNIEnv* env) {
    jclass gnssConfiguration_halInterfaceVersionClass = env->FindClass(
            "com/android/server/location/gnss/GnssConfiguration$HalInterfaceVersion");
    class_gnssConfiguration_halInterfaceVersion =
            (jclass)env->NewGlobalRef(gnssConfiguration_halInterfaceVersionClass);
    method_halInterfaceVersionCtor =
            env->GetMethodID(class_gnssConfiguration_halInterfaceVersion, "<init>", "(II)V");
}

// Implementation of GnssConfiguration (AIDL HAL)

GnssConfiguration::GnssConfiguration(const sp<IGnssConfiguration>& iGnssConfiguration)
      : mIGnssConfiguration(iGnssConfiguration) {}

jobject GnssConfiguration::getVersion(JNIEnv* env) {
    return createHalInterfaceVersionJavaObject(env, 3, 0);
}

jboolean GnssConfiguration::setEmergencySuplPdn(jint enable) {
    auto status = mIGnssConfiguration->setEmergencySuplPdn(enable);
    return checkAidlStatus(status, "IGnssConfiguration setEmergencySuplPdn() failed.");
}

jboolean GnssConfiguration::setSuplVersion(jint version) {
    auto status = mIGnssConfiguration->setSuplVersion(version);
    return checkAidlStatus(status, "IGnssConfiguration setSuplVersion() failed.");
}

jboolean GnssConfiguration::setSuplEs(jint enable) {
    ALOGI("Config parameter SUPL_ES is deprecated in IGnssConfiguration AIDL HAL.");
    return JNI_FALSE;
}

jboolean GnssConfiguration::setSuplMode(jint mode) {
    auto status = mIGnssConfiguration->setSuplMode(mode);
    return checkAidlStatus(status, "IGnssConfiguration setSuplMode() failed.");
}

jboolean GnssConfiguration::setGpsLock(jint gpsLock) {
    ALOGI("Config parameter GPS_LOCK is not supported in IGnssConfiguration AIDL HAL.");
    return JNI_FALSE;
}

jboolean GnssConfiguration::setLppProfile(jint lppProfile) {
    auto status = mIGnssConfiguration->setLppProfile(lppProfile);
    return checkAidlStatus(status, "IGnssConfiguration setLppProfile() failed.");
}

jboolean GnssConfiguration::setGlonassPositioningProtocol(jint gnssPosProtocol) {
    auto status = mIGnssConfiguration->setGlonassPositioningProtocol(gnssPosProtocol);
    return checkAidlStatus(status, "IGnssConfiguration setGlonassPositioningProtocol() failed.");
}

jboolean GnssConfiguration::setEsExtensionSec(jint emergencyExtensionSeconds) {
    auto status = mIGnssConfiguration->setEsExtensionSec(emergencyExtensionSeconds);
    return checkAidlStatus(status, "IGnssConfiguration setEsExtensionSec() failed.");
}

jboolean GnssConfiguration::setBlocklist(JNIEnv* env, jintArray& constellations,
                                         jintArray& sv_ids) {
    auto sources =
            getBlocklistedSources<BlocklistedSource, GnssConstellationType>(env, constellations,
                                                                            sv_ids);
    auto status = mIGnssConfiguration->setBlocklist(sources);
    return checkAidlStatus(status, "IGnssConfiguration setBlocklist() failed.");
}

// Implementation of GnssConfiguration_V1_0

GnssConfiguration_V1_0::GnssConfiguration_V1_0(
        const sp<IGnssConfiguration_V1_0>& iGnssConfiguration)
      : mIGnssConfiguration_V1_0(iGnssConfiguration) {}

jobject GnssConfiguration_V1_0::getVersion(JNIEnv* env) {
    return createHalInterfaceVersionJavaObject(env, 1, 0);
}

jboolean GnssConfiguration_V1_0::setEmergencySuplPdn(jint enable) {
    auto result = mIGnssConfiguration_V1_0->setEmergencySuplPdn(enable);
    return checkHidlReturn(result, "IGnssConfiguration setEmergencySuplPdn() failed.");
}

jboolean GnssConfiguration_V1_0::setSuplVersion(jint version) {
    auto result = mIGnssConfiguration_V1_0->setSuplVersion(version);
    return checkHidlReturn(result, "IGnssConfiguration setSuplVersion() failed.");
}

jboolean GnssConfiguration_V1_0::setSuplEs(jint enable) {
    auto result = mIGnssConfiguration_V1_0->setSuplEs(enable);
    return checkHidlReturn(result, "IGnssConfiguration setSuplEs() failed.");
}

jboolean GnssConfiguration_V1_0::setSuplMode(jint mode) {
    auto result = mIGnssConfiguration_V1_0->setSuplMode(mode);
    return checkHidlReturn(result, "IGnssConfiguration setSuplMode() failed.");
}

jboolean GnssConfiguration_V1_0::setGpsLock(jint gpsLock) {
    auto result = mIGnssConfiguration_V1_0->setGpsLock(gpsLock);
    return checkHidlReturn(result, "IGnssConfiguration setGpsLock() failed.");
}

jboolean GnssConfiguration_V1_0::setLppProfile(jint lppProfile) {
    auto result = mIGnssConfiguration_V1_0->setLppProfile(lppProfile);
    return checkHidlReturn(result, "IGnssConfiguration setLppProfile() failed.");
}

jboolean GnssConfiguration_V1_0::setGlonassPositioningProtocol(jint gnssPosProtocol) {
    auto result = mIGnssConfiguration_V1_0->setGlonassPositioningProtocol(gnssPosProtocol);
    return checkHidlReturn(result, "IGnssConfiguration setGlonassPositioningProtocol() failed.");
}

jboolean GnssConfiguration_V1_0::setEsExtensionSec(jint emergencyExtensionSeconds) {
    ALOGI("Config parameter ES_EXTENSION_SEC is not supported in IGnssConfiguration.hal"
          " versions earlier than 2.0.");
    return JNI_FALSE;
}

jboolean GnssConfiguration_V1_0::setBlocklist(JNIEnv* env, jintArray& constellations,
                                              jintArray& sv_ids) {
    ALOGI("IGnssConfiguration interface does not support satellite blocklist.");
    return JNI_FALSE;
}

// Implementation of GnssConfiguration_V1_1

GnssConfiguration_V1_1::GnssConfiguration_V1_1(
        const sp<IGnssConfiguration_V1_1>& iGnssConfiguration)
      : GnssConfiguration_V1_0{iGnssConfiguration}, mIGnssConfiguration_V1_1(iGnssConfiguration) {}

jobject GnssConfiguration_V1_1::getVersion(JNIEnv* env) {
    return createHalInterfaceVersionJavaObject(env, 1, 1);
}

jboolean GnssConfiguration_V1_1::setBlocklist(JNIEnv* env, jintArray& constellations,
                                              jintArray& sv_ids) {
    auto sources = getBlocklistedSources<BlocklistedSource_V1_1,
                                         GnssConstellationType_V1_0>(env, constellations, sv_ids);
    auto result = mIGnssConfiguration_V1_1->setBlacklist(sources);
    return checkHidlReturn(result, "IGnssConfiguration setBlocklist() failed.");
}

// Implementation of GnssConfiguration_V2_0

GnssConfiguration_V2_0::GnssConfiguration_V2_0(
        const sp<IGnssConfiguration_V2_0>& iGnssConfiguration)
      : GnssConfiguration_V1_1{iGnssConfiguration}, mIGnssConfiguration_V2_0(iGnssConfiguration) {}

jobject GnssConfiguration_V2_0::getVersion(JNIEnv* env) {
    return createHalInterfaceVersionJavaObject(env, 2, 0);
}

jboolean GnssConfiguration_V2_0::setSuplEs(jint enable) {
    ALOGI("Config parameter SUPL_ES is deprecated in IGnssConfiguration.hal version 2.0 and "
          "higher.");
    return JNI_FALSE;
}

jboolean GnssConfiguration_V2_0::setGpsLock(jint enable) {
    ALOGI("Config parameter GPS_LOCK is deprecated in IGnssConfiguration.hal version 2.0 and "
          "higher.");
    return JNI_FALSE;
}

jboolean GnssConfiguration_V2_0::setEsExtensionSec(jint emergencyExtensionSeconds) {
    auto result = mIGnssConfiguration_V2_0->setEsExtensionSec(emergencyExtensionSeconds);
    return checkHidlReturn(result, "IGnssConfiguration setEsExtensionSec() failed.");
}

// Implementation of GnssConfiguration_V2_1

GnssConfiguration_V2_1::GnssConfiguration_V2_1(
        const sp<IGnssConfiguration_V2_1>& iGnssConfiguration)
      : GnssConfiguration_V2_0{iGnssConfiguration}, mIGnssConfiguration_V2_1(iGnssConfiguration) {}

jobject GnssConfiguration_V2_1::getVersion(JNIEnv* env) {
    return createHalInterfaceVersionJavaObject(env, 2, 1);
}

jboolean GnssConfiguration_V2_1::setBlocklist(JNIEnv* env, jintArray& constellations,
                                              jintArray& sv_ids) {
    auto sources = getBlocklistedSources<BlocklistedSource_V2_1,
                                         GnssConstellationType_V2_0>(env, constellations, sv_ids);
    auto result = mIGnssConfiguration_V2_1->setBlacklist_2_1(sources);
    return checkHidlReturn(result, "IGnssConfiguration setBlocklist() failed.");
}

} // namespace android::gnss
