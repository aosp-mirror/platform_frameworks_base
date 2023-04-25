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

//#define LOG_NDEBUG 0
#define LOG_TAG "NativePowerManagerTest"

#include "jni.h"
#include "ParcelHelper.h"

#include <android_util_Binder.h>
#include <binder/IServiceManager.h>
#include <nativehelper/JNIHelp.h>
#include <utils/Log.h>

#include <android/os/IPowerManager.h>
#include <android/WorkSource.h>
#include <android/PowerSaveState.h>
#include <android/BatterySaverPolicyConfig.h>
#include <android-base/stringprintf.h>
#include <android-base/strings.h>

using namespace android::os;
using android::base::StringPrintf;

namespace android {

static struct BatterySaverPolicyConfigFieldId {
    jfieldID adjustBrightnessFactor;
    jfieldID advertiseIsEnabled;
    jfieldID deferFullBackup;
    jfieldID deferKeyValueBackup;
    jfieldID deviceSpecificSettings;
    jfieldID disableAnimation;
    jfieldID disableAod;
    jfieldID disableLaunchBoost;
    jfieldID disableOptionalSensors;
    jfieldID disableVibration;
    jfieldID enableAdjustBrightness;
    jfieldID enableDataSaver;
    jfieldID enableFirewall;
    jfieldID enableNightMode;
    jfieldID enableQuickDoze;
    jfieldID forceAllAppsStandby;
    jfieldID forceBackgroundCheck;
    jfieldID locationMode;
    jfieldID soundTriggerMode;
} gBSPCFieldIds;

static jobject nativeObtainPowerSaveStateParcel(JNIEnv* env, jobject /* obj */,
        jboolean batterySaverEnabled, jboolean globalBatterySaverEnabled,
        jint locationMode, jint soundTriggerMode, jfloat brightnessFactor) {
    PowerSaveState ps = PowerSaveState(static_cast<bool>(batterySaverEnabled),
            static_cast<bool>(globalBatterySaverEnabled),
            static_cast<LocationMode>(locationMode),
            static_cast<SoundTriggerMode>(soundTriggerMode),
            static_cast<float>(brightnessFactor));
    jobject psParcel = nativeObtainParcel(env);
    Parcel* parcel = nativeGetParcelData(env, psParcel);
    status_t err = ps.writeToParcel(parcel);
    if (err != OK) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                            StringPrintf("WorkSource writeToParcel failed %d", err).c_str());
    }
    parcel->setDataPosition(0);
    return psParcel;
}

static void nativeUnparcelAndVerifyPowerSaveState(JNIEnv* env, jobject /* obj */, jobject psParcel,
        jboolean batterySaverEnabled, jboolean globalBatterySaverEnabled,
        jint locationMode, jint soundTriggerMode, jfloat brightnessFactor) {
    PowerSaveState ps = {};
    Parcel* parcel = nativeGetParcelData(env, psParcel);
    status_t err = ps.readFromParcel(parcel);
    if (err != OK) {
        ALOGE("WorkSource writeToParcel failed %d", err);
    }
    // Now we have a native PowerSaveState object, verify it.
    PowerSaveState psOrig = PowerSaveState(static_cast<bool>(batterySaverEnabled),
            static_cast<bool>(globalBatterySaverEnabled),
            static_cast<LocationMode>(locationMode),
            static_cast<SoundTriggerMode>(soundTriggerMode),
            static_cast<float>(brightnessFactor));
    if (ps == psOrig) {
        return;
    } else {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                            "PowerSaveState not equal with origin");
    }
}

static jobject nativeObtainBSPConfigParcel(JNIEnv* env, jobject /* obj */,
        jobject bsObj, jobjectArray keyArray, jobjectArray valueArray) {
    std::vector<std::pair<String16, String16>> deviceSpecificSettings;
    for (jint i = 0; i < env->GetArrayLength(keyArray); i++) {
        jstring keyString = (jstring) (env->GetObjectArrayElement(keyArray, i));
        jstring valueString = (jstring) (env->GetObjectArrayElement(valueArray, i));
        deviceSpecificSettings.push_back({String16(env->GetStringUTFChars(keyString, 0)),
                        String16(env->GetStringUTFChars(valueString, 0))});
    }

    BatterySaverPolicyConfig bs = BatterySaverPolicyConfig(
        env->GetFloatField(bsObj, gBSPCFieldIds.adjustBrightnessFactor),
        env->GetBooleanField(bsObj, gBSPCFieldIds.advertiseIsEnabled),
        env->GetBooleanField(bsObj, gBSPCFieldIds.deferFullBackup),
        env->GetBooleanField(bsObj, gBSPCFieldIds.deferKeyValueBackup),
        deviceSpecificSettings,
        env->GetBooleanField(bsObj, gBSPCFieldIds.disableAnimation),
        env->GetBooleanField(bsObj, gBSPCFieldIds.disableAod),
        env->GetBooleanField(bsObj, gBSPCFieldIds.disableLaunchBoost),
        env->GetBooleanField(bsObj, gBSPCFieldIds.disableOptionalSensors),
        env->GetBooleanField(bsObj, gBSPCFieldIds.disableVibration),
        env->GetBooleanField(bsObj, gBSPCFieldIds.enableAdjustBrightness),
        env->GetBooleanField(bsObj, gBSPCFieldIds.enableDataSaver),
        env->GetBooleanField(bsObj, gBSPCFieldIds.enableFirewall),
        env->GetBooleanField(bsObj, gBSPCFieldIds.enableNightMode),
        env->GetBooleanField(bsObj, gBSPCFieldIds.enableQuickDoze),
        env->GetBooleanField(bsObj, gBSPCFieldIds.forceAllAppsStandby),
        env->GetBooleanField(bsObj, gBSPCFieldIds.forceBackgroundCheck),
        static_cast<LocationMode>(env->GetIntField(bsObj, gBSPCFieldIds.locationMode)),
        static_cast<SoundTriggerMode>(env->GetIntField(bsObj, gBSPCFieldIds.soundTriggerMode)));

    jobject bsParcel = nativeObtainParcel(env);
    Parcel* parcel = nativeGetParcelData(env, bsParcel);
    status_t err = bs.writeToParcel(parcel);
    if (err != OK) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                        StringPrintf("WorkSource writeToParcel failed %d", err).c_str());
    }
    parcel->setDataPosition(0);
    return bsParcel;
}

static void nativeUnparcelAndVerifyBSPConfig(JNIEnv* env, jobject /* obj */,
        jobject bsParcel, jobject bsObj, jobjectArray keyArray, jobjectArray valueArray) {
    BatterySaverPolicyConfig bs = {};
    Parcel* parcel = nativeGetParcelData(env, bsParcel);
    status_t err = bs.readFromParcel(parcel);
    if (err != OK) {
        ALOGE("WorkSource writeToParcel failed %d", err);
    }

    // Get the device settings from Java
    std::vector<std::pair<String16, String16>> deviceSpecificSettings;
    for (jint i = 0; i < env->GetArrayLength(keyArray); i++) {
        jstring keyString = (jstring) (env->GetObjectArrayElement(keyArray, i));
        jstring valueString = (jstring) (env->GetObjectArrayElement(valueArray, i));
        deviceSpecificSettings.push_back({String16(env->GetStringUTFChars(keyString, 0)),
                        String16(env->GetStringUTFChars(valueString, 0))});
    }
    // Now we have a native BatterySaverPolicyConfig object, verify it.
    BatterySaverPolicyConfig bsOrig = BatterySaverPolicyConfig(
        env->GetFloatField(bsObj, gBSPCFieldIds.adjustBrightnessFactor),
        env->GetBooleanField(bsObj, gBSPCFieldIds.advertiseIsEnabled),
        env->GetBooleanField(bsObj, gBSPCFieldIds.deferFullBackup),
        env->GetBooleanField(bsObj, gBSPCFieldIds.deferKeyValueBackup),
        deviceSpecificSettings,
        env->GetBooleanField(bsObj, gBSPCFieldIds.disableAnimation),
        env->GetBooleanField(bsObj, gBSPCFieldIds.disableAod),
        env->GetBooleanField(bsObj, gBSPCFieldIds.disableLaunchBoost),
        env->GetBooleanField(bsObj, gBSPCFieldIds.disableOptionalSensors),
        env->GetBooleanField(bsObj, gBSPCFieldIds.disableVibration),
        env->GetBooleanField(bsObj, gBSPCFieldIds.enableAdjustBrightness),
        env->GetBooleanField(bsObj, gBSPCFieldIds.enableDataSaver),
        env->GetBooleanField(bsObj, gBSPCFieldIds.enableFirewall),
        env->GetBooleanField(bsObj, gBSPCFieldIds.enableNightMode),
        env->GetBooleanField(bsObj, gBSPCFieldIds.enableQuickDoze),
        env->GetBooleanField(bsObj, gBSPCFieldIds.forceAllAppsStandby),
        env->GetBooleanField(bsObj, gBSPCFieldIds.forceBackgroundCheck),
        static_cast<LocationMode>(env->GetIntField(bsObj, gBSPCFieldIds.locationMode)),
        static_cast<SoundTriggerMode>(env->GetIntField(bsObj, gBSPCFieldIds.soundTriggerMode)));

    if (bs == bsOrig) {
        return;
    } else {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                            "BatterySaverPolicyConfig not equal with origin");
    }
}

extern "C" jint JNI_OnLoad(JavaVM* vm, void* /* reserved */)
{
    JNIEnv* env;
    const JNINativeMethod methodTable[] = {
        /* name, signature, funcPtr */
        { "nativeObtainPowerSaveStateParcel", "(ZZIIF)Landroid/os/Parcel;",
                (void*) nativeObtainPowerSaveStateParcel },
        { "nativeUnparcelAndVerifyPowerSaveState", "(Landroid/os/Parcel;ZZIIF)V",
                (void*) nativeUnparcelAndVerifyPowerSaveState },
        { "nativeObtainBSPConfigParcel",
                "(Landroid/os/BatterySaverPolicyConfig;"
                "[Ljava/lang/String;[Ljava/lang/String;)Landroid/os/Parcel;",
                (void*) nativeObtainBSPConfigParcel },
        { "nativeUnparcelAndVerifyBSPConfig",
                "(Landroid/os/Parcel;Landroid/os/BatterySaverPolicyConfig;"
                "[Ljava/lang/String;[Ljava/lang/String;)V",
                (void*) nativeUnparcelAndVerifyBSPConfig },
    };

    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    loadParcelClass(env);

    jclass bspcClazz = FindClassOrDie(env, "android/os/BatterySaverPolicyConfig");

    gBSPCFieldIds.adjustBrightnessFactor =
            GetFieldIDOrDie(env, bspcClazz, "mAdjustBrightnessFactor", "F");
    gBSPCFieldIds.advertiseIsEnabled = GetFieldIDOrDie(env, bspcClazz, "mAdvertiseIsEnabled", "Z");
    gBSPCFieldIds.deferFullBackup = GetFieldIDOrDie(env, bspcClazz, "mDeferFullBackup", "Z");
    gBSPCFieldIds.deferKeyValueBackup =
            GetFieldIDOrDie(env, bspcClazz, "mDeferKeyValueBackup", "Z");
    gBSPCFieldIds.deviceSpecificSettings =
            GetFieldIDOrDie(env, bspcClazz, "mDeviceSpecificSettings", "Ljava/util/Map;");
    gBSPCFieldIds.disableAnimation = GetFieldIDOrDie(env, bspcClazz, "mDisableAnimation", "Z");
    gBSPCFieldIds.disableAod = GetFieldIDOrDie(env, bspcClazz, "mDisableAod", "Z");
    gBSPCFieldIds.disableLaunchBoost = GetFieldIDOrDie(env, bspcClazz, "mDisableLaunchBoost", "Z");
    gBSPCFieldIds.disableOptionalSensors =
            GetFieldIDOrDie(env, bspcClazz, "mDisableOptionalSensors", "Z");
    gBSPCFieldIds.disableVibration = GetFieldIDOrDie(env, bspcClazz, "mDisableVibration", "Z");
    gBSPCFieldIds.enableAdjustBrightness =
            GetFieldIDOrDie(env, bspcClazz, "mEnableAdjustBrightness", "Z");
    gBSPCFieldIds.enableDataSaver = GetFieldIDOrDie(env, bspcClazz, "mEnableDataSaver", "Z");
    gBSPCFieldIds.enableFirewall = GetFieldIDOrDie(env, bspcClazz, "mEnableFirewall", "Z");
    gBSPCFieldIds.enableNightMode = GetFieldIDOrDie(env, bspcClazz, "mEnableNightMode", "Z");
    gBSPCFieldIds.enableQuickDoze = GetFieldIDOrDie(env, bspcClazz, "mEnableQuickDoze", "Z");
    gBSPCFieldIds.forceAllAppsStandby =
            GetFieldIDOrDie(env, bspcClazz, "mForceAllAppsStandby", "Z");
    gBSPCFieldIds.forceBackgroundCheck =
            GetFieldIDOrDie(env, bspcClazz, "mForceBackgroundCheck", "Z");
    gBSPCFieldIds.locationMode = GetFieldIDOrDie(env, bspcClazz, "mLocationMode", "I");
    gBSPCFieldIds.soundTriggerMode = GetFieldIDOrDie(env, bspcClazz, "mSoundTriggerMode", "I");

    jniRegisterNativeMethods(env, "android/os/PowerManagerTest", methodTable,
                sizeof(methodTable) / sizeof(JNINativeMethod));

    return JNI_VERSION_1_6;
}

} /* namespace android */
