/*
 * Copyright 2017, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "MediaMetricsJNI"

#include <binder/Parcel.h>
#include <jni.h>
#include <media/IMediaMetricsService.h>
#include <media/MediaMetricsItem.h>
#include <nativehelper/JNIHelp.h>
#include <variant>

#include "android_media_MediaMetricsJNI.h"
#include "android_os_Parcel.h"
#include "android_runtime/AndroidRuntime.h"

// This source file is compiled and linked into:
// core/jni/ (libandroid_runtime.so)

namespace android {

namespace {
struct BundleHelper {
    BundleHelper(JNIEnv* _env, jobject _bundle)
        : env(_env)
        , clazzBundle(env->FindClass("android/os/PersistableBundle"))
        , putIntID(env->GetMethodID(clazzBundle, "putInt", "(Ljava/lang/String;I)V"))
        , putLongID(env->GetMethodID(clazzBundle, "putLong", "(Ljava/lang/String;J)V"))
        , putDoubleID(env->GetMethodID(clazzBundle, "putDouble", "(Ljava/lang/String;D)V"))
        , putStringID(env->GetMethodID(clazzBundle,
                      "putString", "(Ljava/lang/String;Ljava/lang/String;)V"))
        , constructID(env->GetMethodID(clazzBundle, "<init>", "()V"))
        , bundle(_bundle == nullptr ? env->NewObject(clazzBundle, constructID) : _bundle)
        { }

    JNIEnv* const env;
    const jclass clazzBundle;
    const jmethodID putIntID;
    const jmethodID putLongID;
    const jmethodID putDoubleID;
    const jmethodID putStringID;
    const jmethodID constructID;
    jobject const bundle;

    // We use templated put to access mediametrics::Item based on data type not type enum.
    // See std::variant and std::visit.
    template<typename T>
    void put(jstring keyName, const T& value) = delete;

    template<>
    void put(jstring keyName, const int32_t& value) {
        env->CallVoidMethod(bundle, putIntID, keyName, (jint)value);
    }

    template<>
    void put(jstring keyName, const int64_t& value) {
        env->CallVoidMethod(bundle, putLongID, keyName, (jlong)value);
    }

    template<>
    void put(jstring keyName, const double& value) {
        env->CallVoidMethod(bundle, putDoubleID, keyName, (jdouble)value);
    }

    template<>
    void put(jstring keyName, const std::string& value) {
        env->CallVoidMethod(bundle, putStringID, keyName, env->NewStringUTF(value.c_str()));
    }

    template<>
    void put(jstring keyName, const std::pair<int64_t, int64_t>& value) {
        ; // rate is currently ignored
    }

    template<>
    void put(jstring keyName, const std::monostate& value) {
        ; // none is currently ignored
    }

    // string char * helpers

    template<>
    void put(jstring keyName, const char * const& value) {
        env->CallVoidMethod(bundle, putStringID, keyName, env->NewStringUTF(value));
    }

    template<>
    void put(jstring keyName, char * const& value) {
        env->CallVoidMethod(bundle, putStringID, keyName, env->NewStringUTF(value));
    }

    // We allow both jstring and non-jstring variants.
    template<typename T>
    void put(const char *keyName, const T& value) {
        put(env->NewStringUTF(keyName), value);
    }
};
} // namespace

// place the attributes into a java PersistableBundle object
jobject MediaMetricsJNI::writeMetricsToBundle(
        JNIEnv* env, mediametrics::Item *item, jobject bundle)
{
    BundleHelper bh(env, bundle);

    if (bh.bundle == nullptr) {
        ALOGE("%s: unable to create Bundle", __func__);
        return nullptr;
    }

    bh.put(mediametrics::BUNDLE_KEY, item->getKey().c_str());
    if (item->getPid() != -1) {
        bh.put(mediametrics::BUNDLE_PID, (int32_t)item->getPid());
    }
    if (item->getTimestamp() > 0) {
        bh.put(mediametrics::BUNDLE_TIMESTAMP, (int64_t)item->getTimestamp());
    }
    if (item->getUid() != -1) {
        bh.put(mediametrics::BUNDLE_UID, (int32_t)item->getUid());
    }
    for (const auto &prop : *item) {
        const char *name = prop.getName();
        if (name == nullptr) continue;
        prop.visit([&] (auto &value) { bh.put(name, value); });
    }
    return bh.bundle;
}

// Implementation of MediaMetrics.native_submit_bytebuffer(),
// Delivers the byte buffer to the mediametrics service.
static jint android_media_MediaMetrics_submit_bytebuffer(
        JNIEnv* env, jobject thiz, jobject byteBuffer, jint length)
{
    const jbyte* buffer =
            reinterpret_cast<const jbyte*>(env->GetDirectBufferAddress(byteBuffer));
    if (buffer == nullptr) {
        ALOGE("Error retrieving source of audio data to play, can't play");
        return (jint)BAD_VALUE;
    }

    sp<IMediaMetricsService> service = mediametrics::BaseItem::getService();
    if (service == nullptr) {
        ALOGW("Cannot retrieve mediametrics service");
        return (jint)NO_INIT;
    }
    return (jint)service->submitBuffer((char *)buffer, length);
}

// Helper function to convert a native PersistableBundle to a Java
// PersistableBundle.
jobject MediaMetricsJNI::nativeToJavaPersistableBundle(JNIEnv *env,
                                                       os::PersistableBundle* nativeBundle) {
    if (env == NULL || nativeBundle == NULL) {
        ALOGE("Unexpected NULL parmeter");
        return NULL;
    }

    // Create a Java parcel with the native parcel data.
    // Then create a new PersistableBundle with that parcel as a parameter.
    jobject jParcel = android::createJavaParcelObject(env);
    if (jParcel == NULL) {
      ALOGE("Failed to create a Java Parcel.");
      return NULL;
    }

    android::Parcel* nativeParcel = android::parcelForJavaObject(env, jParcel);
    if (nativeParcel == NULL) {
      ALOGE("Failed to get the native Parcel.");
      return NULL;
    }

    android::status_t result = nativeBundle->writeToParcel(nativeParcel);
    nativeParcel->setDataPosition(0);
    if (result != android::OK) {
      ALOGE("Failed to write nativeBundle to Parcel: %d.", result);
      return NULL;
    }

#define STATIC_INIT_JNI(T, obj, method, globalref, ...) \
    static T obj{};\
    if (obj == NULL) { \
        obj = method(__VA_ARGS__); \
        if (obj == NULL) { \
            ALOGE("%s can't find " #obj, __func__); \
            return NULL; \
        } else { \
            obj = globalref; \
        }\
    } \

    STATIC_INIT_JNI(jclass, clazzBundle, env->FindClass,
            static_cast<jclass>(env->NewGlobalRef(clazzBundle)),
            "android/os/PersistableBundle");
    STATIC_INIT_JNI(jfieldID, bundleCreatorId, env->GetStaticFieldID,
            bundleCreatorId,
            clazzBundle, "CREATOR", "Landroid/os/Parcelable$Creator;");
    STATIC_INIT_JNI(jobject, bundleCreator, env->GetStaticObjectField,
            env->NewGlobalRef(bundleCreator),
            clazzBundle, bundleCreatorId);
    STATIC_INIT_JNI(jclass, clazzCreator, env->FindClass,
            static_cast<jclass>(env->NewGlobalRef(clazzCreator)),
            "android/os/Parcelable$Creator");
    STATIC_INIT_JNI(jmethodID, createFromParcelId, env->GetMethodID,
            createFromParcelId,
            clazzCreator, "createFromParcel", "(Landroid/os/Parcel;)Ljava/lang/Object;");

    jobject newBundle = env->CallObjectMethod(bundleCreator, createFromParcelId, jParcel);
    if (newBundle == NULL) {
        ALOGE("Failed to create a new PersistableBundle "
              "from the createFromParcel call.");
    }

    return newBundle;
}

// ----------------------------------------------------------------------------

static constexpr JNINativeMethod gMethods[] = {
    {"native_submit_bytebuffer", "(Ljava/nio/ByteBuffer;I)I",
            (void *)android_media_MediaMetrics_submit_bytebuffer},
};

// Registers the native methods, called from core/jni/AndroidRuntime.cpp
int register_android_media_MediaMetrics(JNIEnv *env)
{
    return AndroidRuntime::registerNativeMethods(
            env, "android/media/MediaMetrics", gMethods, std::size(gMethods));
}

};  // namespace android
