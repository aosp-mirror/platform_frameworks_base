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

#include <android/gui/LutProperties.h>
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

static struct {
    jclass clazz;
    jmethodID ctor;
} gLutPropertiesClassInfo;

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

static jboolean android_hardware_OverlayProperties_isCombinationSupported(JNIEnv* env, jobject thiz,
                                                                          jlong nativeObject,
                                                                          jint dataspace,
                                                                          jint format) {
    gui::OverlayProperties* properties = reinterpret_cast<gui::OverlayProperties*>(nativeObject);
    if (properties != nullptr) {
        for (const auto& i : properties->combinations) {
            if (std::find(i.pixelFormats.begin(), i.pixelFormats.end(), format) !=
                        i.pixelFormats.end() &&
                std::find(i.standards.begin(), i.standards.end(),
                          dataspace & HAL_DATASPACE_STANDARD_MASK) != i.standards.end() &&
                std::find(i.transfers.begin(), i.transfers.end(),
                          dataspace & HAL_DATASPACE_TRANSFER_MASK) != i.transfers.end() &&
                std::find(i.ranges.begin(), i.ranges.end(), dataspace & HAL_DATASPACE_RANGE_MASK) !=
                        i.ranges.end()) {
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
    combination.standards = {HAL_DATASPACE_STANDARD_BT709};
    combination.transfers = {HAL_DATASPACE_TRANSFER_SRGB};
    combination.ranges = {HAL_DATASPACE_RANGE_FULL};
    overlayProperties->combinations.emplace_back(combination);
    overlayProperties->supportMixedColorSpaces = true;
    return reinterpret_cast<jlong>(overlayProperties);
}

static jobjectArray android_hardware_OverlayProperties_getLutProperties(JNIEnv* env, jobject thiz,
                                                                        jlong nativeObject) {
    gui::OverlayProperties* overlayProperties =
            reinterpret_cast<gui::OverlayProperties*>(nativeObject);
    if (overlayProperties->lutProperties.has_value()) {
        return NULL;
    }
    auto& lutProperties = overlayProperties->lutProperties.value();
    if (lutProperties.empty()) {
        return NULL;
    }
    int32_t size = static_cast<int32_t>(lutProperties.size());
    jobjectArray nativeLutProperties =
            env->NewObjectArray(size, gLutPropertiesClassInfo.clazz, NULL);
    if (nativeLutProperties == NULL) {
        return NULL;
    }
    for (int32_t i = 0; i < size; i++) {
        if (lutProperties[i].has_value()) {
            auto& item = lutProperties[i].value();
            jobject properties =
                    env->NewObject(gLutPropertiesClassInfo.clazz, gLutPropertiesClassInfo.ctor,
                                   static_cast<int32_t>(item.dimension), item.size,
                                   item.samplingKeys.data());
            env->SetObjectArrayElement(nativeLutProperties, i, properties);
        }
    }
    return nativeLutProperties;
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
    { "nIsCombinationSupported",  "(JII)Z",
            (void*)  android_hardware_OverlayProperties_isCombinationSupported },
    { "nSupportMixedColorSpaces", "(J)Z",
            (void*) android_hardware_OverlayProperties_supportMixedColorSpaces },
    { "nWriteOverlayPropertiesToParcel", "(JLandroid/os/Parcel;)V",
            (void*) android_hardware_OverlayProperties_write },
    { "nReadOverlayPropertiesFromParcel", "(Landroid/os/Parcel;)J",
            (void*) android_hardware_OverlayProperties_read },
    {"nCreateDefault", "()J", (void*) android_hardware_OverlayProperties_createDefault },
    {"nGetLutProperties", "(J)[Landroid/hardware/LutProperties;",
            (void*) android_hardware_OverlayProperties_getLutProperties },
};
// clang-format on

int register_android_hardware_OverlayProperties(JNIEnv* env) {
    int err = RegisterMethodsOrDie(env, kClassPathName, gMethods, NELEM(gMethods));

    jclass clazz = FindClassOrDie(env, "android/hardware/OverlayProperties");
    gOverlayPropertiesClassInfo.clazz = MakeGlobalRefOrDie(env, clazz);
    gOverlayPropertiesClassInfo.ctor =
            GetMethodIDOrDie(env, gOverlayPropertiesClassInfo.clazz, "<init>", "(J)V");
    clazz = FindClassOrDie(env, "android/hardware/LutProperties");
    gLutPropertiesClassInfo.clazz = MakeGlobalRefOrDie(env, clazz);
    gLutPropertiesClassInfo.ctor =
            GetMethodIDOrDie(env, gLutPropertiesClassInfo.clazz, "<init>", "(II[I)V");
    return err;
}
