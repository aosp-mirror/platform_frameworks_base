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

#include <android/gui/IHdrConversionConstants.h>
#include <android_util_Binder.h>
#include <gui/SurfaceComposerClient.h>
#include <jni.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <nativehelper/ScopedUtfChars.h>

namespace android {

static jobject nativeCreateVirtualDisplay(JNIEnv* env, jclass clazz, jstring nameObj,
                                          jboolean secure, jstring uniqueIdStr,
                                          jfloat requestedRefreshRate) {
    const ScopedUtfChars name(env, nameObj);
    const ScopedUtfChars uniqueId(env, uniqueIdStr);
    sp<IBinder> token(SurfaceComposerClient::createVirtualDisplay(std::string(name.c_str()),
                                                                  bool(secure),
                                                                  std::string(uniqueId.c_str()),
                                                                  requestedRefreshRate));
    return javaObjectForIBinder(env, token);
}

static void nativeDestroyVirtualDisplay(JNIEnv* env, jclass clazz, jobject tokenObj) {
    sp<IBinder> token(ibinderForJavaObject(env, tokenObj));
    if (token == NULL) return;
    SurfaceComposerClient::destroyVirtualDisplay(token);
}

static void nativeOverrideHdrTypes(JNIEnv* env, jclass clazz, jobject tokenObject,
                                   jintArray jHdrTypes) {
    sp<IBinder> token(ibinderForJavaObject(env, tokenObject));
    if (token == nullptr || jHdrTypes == nullptr) return;

    ScopedIntArrayRO hdrTypes(env, jHdrTypes);
    size_t numHdrTypes = hdrTypes.size();

    std::vector<ui::Hdr> hdrTypesVector;
    hdrTypesVector.reserve(numHdrTypes);
    for (int i = 0; i < numHdrTypes; i++) {
        hdrTypesVector.push_back(static_cast<ui::Hdr>(hdrTypes[i]));
    }

    status_t error = SurfaceComposerClient::overrideHdrTypes(token, hdrTypesVector);
    if (error != NO_ERROR) {
        jniThrowExceptionFmt(env, "java/lang/SecurityException",
                             "ACCESS_SURFACE_FLINGER is missing");
    }
}

static int nativeSetHdrConversionMode(JNIEnv* env, jclass clazz, jint hdrConversionMode,
                                      jint preferredHdrOutputType, jintArray autoHdrOutputTypes,
                                      jint autoHdrOutputTypesLength) {
    gui::HdrConversionStrategy hdrConversionStrategy;
    switch (hdrConversionMode) {
        case gui::IHdrConversionConstants::HdrConversionModePassthrough: {
            hdrConversionStrategy.set<gui::HdrConversionStrategy::Tag::passthrough>(true);
            break;
        }
        case gui::IHdrConversionConstants::HdrConversionModeAuto: {
            jint* autoHdrOutputTypesArray = env->GetIntArrayElements(autoHdrOutputTypes, 0);
            std::vector<int> autoHdrOutputTypesVector(autoHdrOutputTypesLength);
            for (int i = 0; i < autoHdrOutputTypesLength; i++) {
                autoHdrOutputTypesVector[i] = autoHdrOutputTypesArray[i];
            }
            hdrConversionStrategy.set<gui::HdrConversionStrategy::Tag::autoAllowedHdrTypes>(
                    autoHdrOutputTypesVector);
            break;
        }
        case gui::IHdrConversionConstants::HdrConversionModeForce: {
            hdrConversionStrategy.set<gui::HdrConversionStrategy::Tag::forceHdrConversion>(
                    preferredHdrOutputType);
            break;
        }
    }
    ui::Hdr prefHdrType;
    SurfaceComposerClient::setHdrConversionStrategy(hdrConversionStrategy, &prefHdrType);
    if (static_cast<jint>(prefHdrType) == 0) {
        return -1;
    } else {
        return static_cast<jint>(prefHdrType);
    }
}

static jintArray nativeGetSupportedHdrOutputTypes(JNIEnv* env, jclass clazz) {
    std::vector<gui::HdrConversionCapability> hdrConversionCapabilities;
    SurfaceComposerClient::getHdrConversionCapabilities(&hdrConversionCapabilities);

    // Extract unique HDR output types.
    std::set<int> hdrOutputTypes;
    for (const auto& hdrConversionCapability : hdrConversionCapabilities) {
        // Filter out the value for SDR which is 0.
        if (hdrConversionCapability.outputType > 0) {
            hdrOutputTypes.insert(hdrConversionCapability.outputType);
        }
    }
    jintArray array = env->NewIntArray(hdrOutputTypes.size());
    if (array == nullptr) {
        jniThrowException(env, "java/lang/OutOfMemoryError", nullptr);
        return nullptr;
    }
    jint* arrayValues = env->GetIntArrayElements(array, 0);
    size_t index = 0;
    for (auto hdrOutputType : hdrOutputTypes) {
        arrayValues[index++] = static_cast<jint>(hdrOutputType);
    }
    env->ReleaseIntArrayElements(array, arrayValues, 0);
    return array;
}

static jintArray nativeGetHdrOutputTypesWithLatency(JNIEnv* env, jclass clazz) {
    std::vector<gui::HdrConversionCapability> hdrConversionCapabilities;
    SurfaceComposerClient::getHdrConversionCapabilities(&hdrConversionCapabilities);

    // Extract unique HDR output types with latency.
    std::set<int> hdrOutputTypes;
    for (const auto& hdrConversionCapability : hdrConversionCapabilities) {
        if (hdrConversionCapability.outputType > 0 && hdrConversionCapability.addsLatency) {
            hdrOutputTypes.insert(hdrConversionCapability.outputType);
        }
    }
    jintArray array = env->NewIntArray(hdrOutputTypes.size());
    if (array == nullptr) {
        jniThrowException(env, "java/lang/OutOfMemoryError", nullptr);
        return nullptr;
    }
    jint* arrayValues = env->GetIntArrayElements(array, 0);
    size_t index = 0;
    for (auto hdrOutputType : hdrOutputTypes) {
        arrayValues[index++] = static_cast<jint>(hdrOutputType);
    }
    env->ReleaseIntArrayElements(array, arrayValues, 0);
    return array;
}

static jboolean nativeGetHdrOutputConversionSupport(JNIEnv* env, jclass clazz) {
    bool isSupported;
    status_t err = SurfaceComposerClient::getHdrOutputConversionSupport(&isSupported);
    if (err == OK) {
        return isSupported;
    }
    return JNI_FALSE;
}

static jlongArray nativeGetPhysicalDisplayIds(JNIEnv* env, jclass clazz) {
    const auto displayIds = SurfaceComposerClient::getPhysicalDisplayIds();
    ScopedLongArrayRW values(env, env->NewLongArray(displayIds.size()));
    if (values.get() == nullptr) {
        jniThrowException(env, "java/lang/OutOfMemoryError", nullptr);
        return nullptr;
    }

    for (size_t i = 0; i < displayIds.size(); ++i) {
        values[i] = static_cast<jlong>(displayIds[i].value);
    }

    return values.getJavaArray();
}

static jobject nativeGetPhysicalDisplayToken(JNIEnv* env, jclass clazz, jlong physicalDisplayId) {
    const auto id = DisplayId::fromValue<PhysicalDisplayId>(physicalDisplayId);
    if (!id) return nullptr;
    sp<IBinder> token = SurfaceComposerClient::getPhysicalDisplayToken(*id);
    return javaObjectForIBinder(env, token);
}

// ----------------------------------------------------------------------------

static const JNINativeMethod sDisplayMethods[] = {
        // clang-format off
    {"nativeCreateVirtualDisplay", "(Ljava/lang/String;ZLjava/lang/String;F)Landroid/os/IBinder;",
            (void*)nativeCreateVirtualDisplay },
    {"nativeDestroyVirtualDisplay", "(Landroid/os/IBinder;)V",
            (void*)nativeDestroyVirtualDisplay },
    {"nativeOverrideHdrTypes", "(Landroid/os/IBinder;[I)V",
                (void*)nativeOverrideHdrTypes },
    {"nativeGetPhysicalDisplayIds", "()[J",
            (void*)nativeGetPhysicalDisplayIds },
    {"nativeGetPhysicalDisplayToken", "(J)Landroid/os/IBinder;",
            (void*)nativeGetPhysicalDisplayToken },
    {"nativeSetHdrConversionMode", "(II[II)I",
            (void*)nativeSetHdrConversionMode },
    {"nativeGetSupportedHdrOutputTypes", "()[I",
            (void*)nativeGetSupportedHdrOutputTypes },
    {"nativeGetHdrOutputTypesWithLatency", "()[I",
            (void*)nativeGetHdrOutputTypesWithLatency },
    {"nativeGetHdrOutputConversionSupport", "()Z",
            (void*) nativeGetHdrOutputConversionSupport },
        // clang-format on
};

int register_com_android_server_display_DisplayControl(JNIEnv* env) {
    return jniRegisterNativeMethods(env, "com/android/server/display/DisplayControl",
                                    sDisplayMethods, NELEM(sDisplayMethods));
}

} // namespace android
