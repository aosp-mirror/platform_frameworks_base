/**
 * Copyright (C) 2017 The Android Open Source Project
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

#define LOG_TAG "radio.Tuner.jni"
#define LOG_NDEBUG 0

#include "com_android_server_radio_Tuner.h"

#include "com_android_server_radio_Tuner_TunerCallback.h"

#include <android/hardware/broadcastradio/1.1/IBroadcastRadioFactory.h>
#include <core_jni_helpers.h>
#include <utils/Log.h>
#include <JNIHelp.h>

namespace android {
namespace server {
namespace radio {
namespace Tuner {

using hardware::Return;
using hardware::hidl_vec;

namespace V1_0 = hardware::broadcastradio::V1_0;
namespace V1_1 = hardware::broadcastradio::V1_1;

using V1_0::BandConfig;
using V1_0::ITuner;
using V1_0::MetaData;
using V1_0::Result;
using V1_1::ITunerCallback;
using V1_1::ProgramListResult;

static Mutex gContextMutex;

static jclass gTunerClass;
static jfieldID gNativeContextId;

struct TunerContext {
    TunerContext() {}

    sp<ITuner> mHalTuner;
    sp<TunerCallback> mNativeCallback;

private:
    DISALLOW_COPY_AND_ASSIGN(TunerContext);
};

static TunerContext& getNativeContext(jlong nativeContextHandle) {
    auto nativeContext = reinterpret_cast<TunerContext*>(nativeContextHandle);
    LOG_ALWAYS_FATAL_IF(nativeContext == nullptr, "Native context not initialized");
    return *nativeContext;
}

/**
 * Always lock gContextMutex when using native context.
 */
static TunerContext& getNativeContext(JNIEnv *env, jobject obj) {
    return getNativeContext(env->GetLongField(obj, gNativeContextId));
}

static jlong nativeInit(JNIEnv *env, jobject obj, jobject clientCallback) {
    ALOGV("nativeInit()");
    AutoMutex _l(gContextMutex);

    auto ctx = new TunerContext();
    ctx->mNativeCallback = new TunerCallback(env, obj, clientCallback);

    static_assert(sizeof(jlong) >= sizeof(ctx), "jlong is smaller than a pointer");
    return reinterpret_cast<jlong>(ctx);
}

static void nativeFinalize(JNIEnv *env, jobject obj, jlong nativeContext) {
    ALOGV("nativeFinalize()");
    AutoMutex _l(gContextMutex);

    auto ctx = reinterpret_cast<TunerContext*>(nativeContext);
    delete ctx;
}

void setHalTuner(JNIEnv *env, jobject obj, sp<ITuner> halTuner) {
    ALOGV("setHalTuner(%p)", halTuner.get());
    ALOGE_IF(halTuner == nullptr, "HAL tuner is a nullptr");

    AutoMutex _l(gContextMutex);
    auto& ctx = getNativeContext(env, obj);
    ctx.mHalTuner = halTuner;
}

sp<ITunerCallback> getNativeCallback(JNIEnv *env, jobject obj) {
    AutoMutex _l(gContextMutex);
    auto& ctx = getNativeContext(env, obj);
    return ctx.mNativeCallback;
}

static void close(JNIEnv *env, jobject obj, jlong nativeContext) {
    AutoMutex _l(gContextMutex);
    auto& ctx = getNativeContext(nativeContext);
    ALOGI("Closing tuner %p", ctx.mHalTuner.get());
    ctx.mNativeCallback->detach();
    ctx.mHalTuner = nullptr;
    ctx.mNativeCallback = nullptr;
}

static const JNINativeMethod gTunerMethods[] = {
    { "nativeInit", "(Landroid/hardware/radio/ITunerCallback;)J", (void*)nativeInit },
    { "nativeFinalize", "(J)V", (void*)nativeFinalize },
    { "nativeClose", "(J)V", (void*)close },
};

} // namespace Tuner
} // namespace radio
} // namespace server

void register_android_server_radio_Tuner(JNIEnv *env) {
    using namespace server::radio::Tuner;

    auto tunerClass = FindClassOrDie(env, "com/android/server/radio/Tuner");
    gTunerClass = MakeGlobalRefOrDie(env, tunerClass);
    gNativeContextId = GetFieldIDOrDie(env, gTunerClass, "mNativeContext", "J");

    auto res = jniRegisterNativeMethods(env, "com/android/server/radio/Tuner",
            gTunerMethods, NELEM(gTunerMethods));
    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register native methods.");
}

} // namespace android
