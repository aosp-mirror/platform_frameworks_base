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

#define FIND_CLASS(var, className) \
    var = env->FindClass(className); \
    LOG_FATAL_IF(!(var), "Unable to find class %s", className);

#define GET_FIELD_ID(var, clazz, fieldName, fieldDescriptor) \
    var = env->GetFieldID(clazz, fieldName, fieldDescriptor); \
    LOG_FATAL_IF(!(var), "Unable to find field %s", fieldName);

#define GET_STATIC_METHOD_ID(var, clazz, fieldName, fieldDescriptor) \
    var = env->GetStaticMethodID(clazz, fieldName, fieldDescriptor); \
    LOG_FATAL_IF(!(var), "Unable to find method %s", fieldName);

static jclass gParcelClazz;
static jfieldID gParcelDataFieldID;
static jmethodID gParcelObtainMethodID;
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

static jobject nativeObtainParcel(JNIEnv* env) {
    jobject parcel = env->CallStaticObjectMethod(gParcelClazz, gParcelObtainMethodID);
    if (parcel == nullptr) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "Obtain parcel failed.");
    }
    return parcel;
}

static Parcel* nativeGetParcelData(JNIEnv* env, jobject obj) {
    Parcel* parcel = reinterpret_cast<Parcel*>(env->GetLongField(obj, gParcelDataFieldID));
    if (parcel && parcel->objectsCount() != 0) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "Invalid parcel object.");
    }
    parcel->setDataPosition(0);
    return parcel;
}

static jobject nativeObtainWorkSourceParcel(JNIEnv* env, jobject /* obj */, jintArray uidArray,
            jobjectArray nameArray) {
    std::vector<int32_t> uids;
    std::optional<std::vector<std::optional<String16>>> names = std::nullopt;

    if (uidArray != nullptr) {
        jint *ptr = env->GetIntArrayElements(uidArray, 0);
        for (jint i = 0; i < env->GetArrayLength(uidArray); i++) {
            uids.push_back(static_cast<int32_t>(ptr[i]));
        }
    }

    if (nameArray != nullptr) {
        std::vector<std::optional<String16>> namesVec;
        for (jint i = 0; i < env->GetArrayLength(nameArray); i++) {
            jstring string = (jstring) (env->GetObjectArrayElement(nameArray, i));
            const char *rawString = env->GetStringUTFChars(string, 0);
            namesVec.push_back(std::make_optional<String16>(String16(rawString)));
        }
        names = std::make_optional(std::move(namesVec));
    }

    WorkSource ws = WorkSource(uids, names);
    jobject wsParcel = nativeObtainParcel(env);
    Parcel* parcel = nativeGetParcelData(env, wsParcel);
    status_t err = ws.writeToParcel(parcel);
    if (err != OK) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                            StringPrintf("WorkSource writeToParcel failed %d", err).c_str());
    }
    parcel->setDataPosition(0);
    return wsParcel;
}

static void nativeUnparcelAndVerifyWorkSource(JNIEnv* env, jobject /* obj */, jobject wsParcel,
        jintArray uidArray, jobjectArray nameArray) {
    WorkSource ws = {};
    Parcel* parcel = nativeGetParcelData(env, wsParcel);

    status_t err = ws.readFromParcel(parcel);
    if (err != OK) {
        ALOGE("WorkSource writeToParcel failed %d", err);
    }

    // Now we have a native WorkSource object, verify it.
    if (uidArray != nullptr) {
        jint *ptr = env->GetIntArrayElements(uidArray, 0);
        for (jint i = 0; i < env->GetArrayLength(uidArray); i++) {
            if (ws.getUids().at(i) != static_cast<int32_t>(ptr[i])) {
                jniThrowException(env, "java/lang/IllegalArgumentException",
                            StringPrintf("WorkSource uid not equal %d %d",
                            ws.getUids().at(i), static_cast<int32_t>(ptr[i])).c_str());
            }
        }
    } else {
        if (ws.getUids().size() != 0) {
            jniThrowException(env, "java/lang/IllegalArgumentException",
                    StringPrintf("WorkSource parcel size not 0").c_str());
        }
    }

    if (nameArray != nullptr) {
        std::vector<std::optional<String16>> namesVec;
        for (jint i = 0; i < env->GetArrayLength(nameArray); i++) {
            jstring string = (jstring) (env->GetObjectArrayElement(nameArray, i));
            const char *rawString = env->GetStringUTFChars(string, 0);
            if (String16(rawString) != ws.getNames()->at(i)) {
                jniThrowException(env, "java/lang/IllegalArgumentException",
                            StringPrintf("WorkSource uid not equal %s", rawString).c_str());
            }
        }
    } else {
        if (ws.getNames() != std::nullopt) {
            jniThrowException(env, "java/lang/IllegalArgumentException",
                    StringPrintf("WorkSource parcel name not empty").c_str());
        }
    }
}

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
        { "nativeObtainWorkSourceParcel", "([I[Ljava/lang/String;)Landroid/os/Parcel;",
                (void*) nativeObtainWorkSourceParcel },
        { "nativeUnparcelAndVerifyWorkSource", "(Landroid/os/Parcel;[I[Ljava/lang/String;)V",
                (void*) nativeUnparcelAndVerifyWorkSource },
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

    jclass bspcClazz;
    FIND_CLASS(gParcelClazz, "android/os/Parcel");
    GET_FIELD_ID(gParcelDataFieldID, gParcelClazz, "mNativePtr", "J");
    GET_STATIC_METHOD_ID(gParcelObtainMethodID, gParcelClazz, "obtain", "()Landroid/os/Parcel;");
    FIND_CLASS(bspcClazz, "android/os/BatterySaverPolicyConfig");
    GET_FIELD_ID(gBSPCFieldIds.adjustBrightnessFactor, bspcClazz, "mAdjustBrightnessFactor", "F");
    GET_FIELD_ID(gBSPCFieldIds.advertiseIsEnabled, bspcClazz, "mAdvertiseIsEnabled", "Z");
    GET_FIELD_ID(gBSPCFieldIds.deferFullBackup, bspcClazz, "mDeferFullBackup", "Z");
    GET_FIELD_ID(gBSPCFieldIds.deferKeyValueBackup, bspcClazz, "mDeferKeyValueBackup", "Z");
    GET_FIELD_ID(gBSPCFieldIds.deviceSpecificSettings, bspcClazz, "mDeviceSpecificSettings",
                    "Ljava/util/Map;");
    GET_FIELD_ID(gBSPCFieldIds.disableAnimation, bspcClazz, "mDisableAnimation", "Z");
    GET_FIELD_ID(gBSPCFieldIds.disableAod, bspcClazz, "mDisableAod", "Z");
    GET_FIELD_ID(gBSPCFieldIds.disableLaunchBoost, bspcClazz, "mDisableLaunchBoost", "Z");
    GET_FIELD_ID(gBSPCFieldIds.disableOptionalSensors, bspcClazz, "mDisableOptionalSensors", "Z");
    GET_FIELD_ID(gBSPCFieldIds.disableVibration, bspcClazz, "mDisableVibration", "Z");
    GET_FIELD_ID(gBSPCFieldIds.enableAdjustBrightness, bspcClazz, "mEnableAdjustBrightness", "Z");
    GET_FIELD_ID(gBSPCFieldIds.enableDataSaver, bspcClazz, "mEnableDataSaver", "Z");
    GET_FIELD_ID(gBSPCFieldIds.enableFirewall, bspcClazz, "mEnableFirewall", "Z");
    GET_FIELD_ID(gBSPCFieldIds.enableNightMode, bspcClazz, "mEnableNightMode", "Z");
    GET_FIELD_ID(gBSPCFieldIds.enableQuickDoze, bspcClazz, "mEnableQuickDoze", "Z");
    GET_FIELD_ID(gBSPCFieldIds.forceAllAppsStandby, bspcClazz, "mForceAllAppsStandby", "Z");
    GET_FIELD_ID(gBSPCFieldIds.forceBackgroundCheck, bspcClazz, "mForceBackgroundCheck", "Z");
    GET_FIELD_ID(gBSPCFieldIds.locationMode, bspcClazz, "mLocationMode", "I");
    GET_FIELD_ID(gBSPCFieldIds.soundTriggerMode, bspcClazz, "mSoundTriggerMode", "I");

    jniRegisterNativeMethods(env, "android/os/PowerManagerTest", methodTable,
                sizeof(methodTable) / sizeof(JNINativeMethod));
    return JNI_VERSION_1_6;
}

} /* namespace android */
