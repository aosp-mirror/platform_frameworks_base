/**
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

#define LOG_TAG "OverlayProperties"
// #define LOG_NDEBUG 0

#include <android/gui/OverlayProperties.h>
#include <binder/Parcel.h>
#include <gui/SurfaceComposerClient.h>
#include <nativehelper/JNIHelp.h>

#include "android_os_Parcel.h"
#include "core_jni_helpers.h"
#include "jni.h"

using namespace android;

// ----------------------------------------------------------------------------
// Types
// ----------------------------------------------------------------------------
static struct {
    jclass clazz;
    jmethodID ctor;
} gOverlayPropertiesClassInfo;

// ----------------------------------------------------------------------------
// OverlayProperties lifecycle
// ----------------------------------------------------------------------------

static void destroyOverlayProperties(gui::OverlayProperties* overlayProperties) {
    delete overlayProperties;
}

static jlong android_hardware_OverlayProperties_getDestructor(JNIEnv*, jclass) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(&destroyOverlayProperties));
}

//----------------------------------------------------------------------------
// Accessors
// ----------------------------------------------------------------------------

static jboolean android_hardware_OverlayProperties_supportFp16ForHdr(JNIEnv* env, jobject thiz,
                                                                     jlong nativeObject) {
    gui::OverlayProperties* properties = reinterpret_cast<gui::OverlayProperties*>(nativeObject);
    if (properties != nullptr) {
        for (const auto& i : properties->combinations) {
            if (std::find(i.pixelFormats.begin(), i.pixelFormats.end(),
                          static_cast<int32_t>(HAL_PIXEL_FORMAT_RGBA_FP16)) !=
                        i.pixelFormats.end() &&
                std::find(i.standards.begin(), i.standards.end(),
                          static_cast<int32_t>(HAL_DATASPACE_STANDARD_BT2020)) !=
                        i.standards.end() &&
                std::find(i.transfers.begin(), i.transfers.end(),
                          static_cast<int32_t>(HAL_DATASPACE_TRANSFER_ST2084)) !=
                        i.transfers.end() &&
                std::find(i.ranges.begin(), i.ranges.end(),
                          static_cast<int32_t>(HAL_DATASPACE_RANGE_FULL)) != i.ranges.end()) {
                return true;
            }
        }
    }
    return false;
}

static jboolean android_hardware_OverlayProperties_supportMixedColorSpaces(JNIEnv* env,
                                                                           jobject thiz,
                                                                           jlong nativeObject) {
    gui::OverlayProperties* properties = reinterpret_cast<gui::OverlayProperties*>(nativeObject);
    if (properties != nullptr && properties->supportMixedColorSpaces) {
        return true;
    }
    return false;
}

static jlong android_hardware_OverlayProperties_createDefault(JNIEnv* env, jobject thiz) {
    gui::OverlayProperties* overlayProperties = new gui::OverlayProperties;
    gui::OverlayProperties::SupportedBufferCombinations combination;
    combination.pixelFormats = {HAL_PIXEL_FORMAT_RGBA_8888};
    combination.standards = {HAL_DATASPACE_BT709};
    combination.transfers = {HAL_DATASPACE_TRANSFER_SRGB};
    combination.ranges = {HAL_DATASPACE_RANGE_FULL};
    overlayProperties->combinations.emplace_back(combination);
    overlayProperties->supportMixedColorSpaces = true;
    return reinterpret_cast<jlong>(overlayProperties);
}

// ----------------------------------------------------------------------------
// Serialization
// ----------------------------------------------------------------------------

static void android_hardware_OverlayProperties_write(JNIEnv* env, jclass, jlong nativeObject,
                                                     jobject dest) {
    Parcel* parcel = parcelForJavaObject(env, dest);
    if (parcel == nullptr) {
        jniThrowNullPointerException(env, nullptr);
        return;
    }
    gui::OverlayProperties* overlayProperties =
            reinterpret_cast<gui::OverlayProperties*>(nativeObject);
    if (overlayProperties != nullptr) {
        overlayProperties->writeToParcel(parcel);
    }
}

static long android_hardware_OverlayProperties_read(JNIEnv* env, jclass, jobject in) {
    Parcel* parcel = parcelForJavaObject(env, in);
    if (parcel == nullptr) {
        jniThrowNullPointerException(env, nullptr);
        return 0;
    }
    gui::OverlayProperties* overlayProperties = new gui::OverlayProperties;
    if (overlayProperties->readFromParcel(parcel) != NO_ERROR) {
        delete overlayProperties;
        return 0;
    }
    return reinterpret_cast<jlong>(overlayProperties);
}

// ----------------------------------------------------------------------------
// Public functions
// ----------------------------------------------------------------------------

namespace android {

jobject android_hardware_OverlayProperties_convertToJavaObject(
        JNIEnv* env, gui::OverlayProperties* overlayProperties) {
    jobject overlayPropertiesObj =
            env->NewObject(gOverlayPropertiesClassInfo.clazz, gOverlayPropertiesClassInfo.ctor,
                           reinterpret_cast<jlong>(overlayProperties));
    return overlayPropertiesObj;
}

}; // namespace android

// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

const char* const kClassPathName = "android/hardware/OverlayProperties";

// clang-format off
static const JNINativeMethod gMethods[] = {
    { "nGetDestructor", "()J", (void*) android_hardware_OverlayProperties_getDestructor },
    { "nSupportFp16ForHdr",  "(J)Z",
            (void*)  android_hardware_OverlayProperties_supportFp16ForHdr },
    { "nSupportMixedColorSpaces", "(J)Z",
            (void*) android_hardware_OverlayProperties_supportMixedColorSpaces },
    { "nWriteOverlayPropertiesToParcel", "(JLandroid/os/Parcel;)V",
            (void*) android_hardware_OverlayProperties_write },
    { "nReadOverlayPropertiesFromParcel", "(Landroid/os/Parcel;)J",
            (void*) android_hardware_OverlayProperties_read },
    {"nCreateDefault", "()J", (void*) android_hardware_OverlayProperties_createDefault },
};
// clang-format on

int register_android_hardware_OverlayProperties(JNIEnv* env) {
    int err = RegisterMethodsOrDie(env, kClassPathName, gMethods, NELEM(gMethods));

    jclass clazz = FindClassOrDie(env, "android/hardware/OverlayProperties");
    gOverlayPropertiesClassInfo.clazz = MakeGlobalRefOrDie(env, clazz);
    gOverlayPropertiesClassInfo.ctor =
            GetMethodIDOrDie(env, gOverlayPropertiesClassInfo.clazz, "<init>", "(J)V");

    return err;
}
