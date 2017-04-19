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

#include <android/hardware/broadcastradio/1.1/IBroadcastRadioFactory.h>
#include <core_jni_helpers.h>
#include <utils/Log.h>
#include <JNIHelp.h>

namespace android {

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

private:
    DISALLOW_COPY_AND_ASSIGN(TunerContext);
};

// TODO(b/36863239): implement actual callback class which forwards calls to Java code.
class DummyTunerCallback : public ITunerCallback {
    virtual Return<void> hardwareFailure() { return Return<void>(); }
    virtual Return<void> configChange(Result result, const BandConfig& config) {
        return Return<void>();
    }
    virtual Return<void> tuneComplete(Result result, const V1_0::ProgramInfo& info) {
        return Return<void>();
    }
    virtual Return<void> afSwitch(const V1_0::ProgramInfo& info) { return Return<void>(); }
    virtual Return<void> antennaStateChange(bool connected) { return Return<void>(); }
    virtual Return<void> trafficAnnouncement(bool active) { return Return<void>(); }
    virtual Return<void> emergencyAnnouncement(bool active) { return Return<void>(); }
    virtual Return<void> newMetadata(uint32_t channel, uint32_t subChannel,
            const hidl_vec<MetaData>& metadata) { return Return<void>(); }
    virtual Return<void> tuneComplete_1_1(Result result, const V1_1::ProgramInfo& info) {
        return Return<void>();
    }
    virtual Return<void> afSwitch_1_1(const V1_1::ProgramInfo& info) { return Return<void>(); }
    virtual Return<void> backgroundScanAvailable(bool isAvailable) { return Return<void>(); }
    virtual Return<void> backgroundScanComplete(ProgramListResult result) { return Return<void>(); }
    virtual Return<void> programListChanged() { return Return<void>(); }
};

/**
 * Always lock gContextMutex when using native context.
 */
static TunerContext& getNativeContext(JNIEnv *env, jobject obj) {
    auto nativeContext = reinterpret_cast<TunerContext*>(env->GetLongField(obj, gNativeContextId));
    LOG_ALWAYS_FATAL_IF(nativeContext == nullptr, "Native context not initialized");
    return *nativeContext;
}

static jlong nativeInit(JNIEnv *env, jobject obj) {
    ALOGV("nativeInit()");
    AutoMutex _l(gContextMutex);

    auto nativeContext = new TunerContext();
    static_assert(sizeof(jlong) >= sizeof(nativeContext), "jlong is smaller than a pointer");
    return reinterpret_cast<jlong>(nativeContext);
}

static void nativeFinalize(JNIEnv *env, jobject obj, jlong nativeContext) {
    ALOGV("nativeFinalize()");
    AutoMutex _l(gContextMutex);

    auto ctx = reinterpret_cast<TunerContext*>(nativeContext);
    delete ctx;
}

void android_server_radio_Tuner_setHalTuner(JNIEnv *env, jobject obj, sp<ITuner> halTuner) {
    ALOGV("setHalTuner(%p)", halTuner.get());
    AutoMutex _l(gContextMutex);

    auto& ctx = getNativeContext(env, obj);
    ctx.mHalTuner = halTuner;
}

sp<ITunerCallback> android_server_radio_Tuner_getCallback(JNIEnv *env, jobject obj) {
    return new DummyTunerCallback();
}

static void close(JNIEnv *env, jobject obj) {
    android_server_radio_Tuner_setHalTuner(env, obj, nullptr);
}

static const JNINativeMethod gTunerMethods[] = {
    { "nativeInit", "()J", (void*)nativeInit },
    { "nativeFinalize", "(J)V", (void*)nativeFinalize },
    { "close", "()V", (void*)close },
};

void register_android_server_radio_Tuner(JNIEnv *env) {
    auto tunerClass = FindClassOrDie(env, "com/android/server/radio/Tuner");
    gTunerClass = MakeGlobalRefOrDie(env, tunerClass);
    gNativeContextId = GetFieldIDOrDie(env, gTunerClass, "mNativeContext", "J");

    auto res = jniRegisterNativeMethods(env, "com/android/server/radio/Tuner",
            gTunerMethods, NELEM(gTunerMethods));
    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register native methods.");
}

} /* namespace android */
