/*
 * Copyright (C) 2021 The Android Open Source Project
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

#define LOG_TAG "NativeSensorService"

#include <android-base/properties.h>
#include <android_runtime/AndroidRuntime.h>
#include <core_jni_helpers.h>
#include <cutils/properties.h>
#include <jni.h>
#include <sensorservice/SensorService.h>
#include <utils/Log.h>
#include <utils/misc.h>

#include <mutex>

#define PROXIMITY_ACTIVE_CLASS \
    "com/android/server/sensors/SensorManagerInternal$ProximityActiveListener"

namespace android {

static JavaVM* sJvm = nullptr;
static jmethodID sMethodIdOnProximityActive;

class NativeSensorService {
public:
    NativeSensorService(JNIEnv* env, jobject listener);

    void registerProximityActiveListener();
    void unregisterProximityActiveListener();

private:
    sp<SensorService> mService;

    class ProximityActiveListenerDelegate : public SensorService::ProximityActiveListener {
    public:
        ProximityActiveListenerDelegate(JNIEnv* env, jobject listener);
        ~ProximityActiveListenerDelegate();

        void onProximityActive(bool isActive) override;

    private:
        jobject mListener;
    };
    sp<ProximityActiveListenerDelegate> mProximityActiveListenerDelegate;
};

NativeSensorService::NativeSensorService(JNIEnv* env, jobject listener)
      : mProximityActiveListenerDelegate(new ProximityActiveListenerDelegate(env, listener)) {
    if (base::GetBoolProperty("system_init.startsensorservice", true)) {
        sp<IServiceManager> sm(defaultServiceManager());
        mService = new SensorService();
        sm->addService(String16(SensorService::getServiceName()), mService,
                       false /* allowIsolated */, IServiceManager::DUMP_FLAG_PRIORITY_CRITICAL);
    }
}

void NativeSensorService::registerProximityActiveListener() {
    if (mService == nullptr) {
        ALOGD("Dropping registerProximityActiveListener, sensor service not available.");
        return;
    }
    mService->addProximityActiveListener(mProximityActiveListenerDelegate);
}

void NativeSensorService::unregisterProximityActiveListener() {
    if (mService == nullptr) {
        ALOGD("Dropping unregisterProximityActiveListener, sensor service not available.");
        return;
    }

    mService->removeProximityActiveListener(mProximityActiveListenerDelegate);
}

NativeSensorService::ProximityActiveListenerDelegate::ProximityActiveListenerDelegate(
        JNIEnv* env, jobject listener)
      : mListener(env->NewGlobalRef(listener)) {}

NativeSensorService::ProximityActiveListenerDelegate::~ProximityActiveListenerDelegate() {
    AndroidRuntime::getJNIEnv()->DeleteGlobalRef(mListener);
}

void NativeSensorService::ProximityActiveListenerDelegate::onProximityActive(bool isActive) {
    auto jniEnv = GetOrAttachJNIEnvironment(sJvm);
    jniEnv->CallVoidMethod(mListener, sMethodIdOnProximityActive, static_cast<jboolean>(isActive));
}

static jlong startSensorServiceNative(JNIEnv* env, jclass, jobject listener) {
    NativeSensorService* service = new NativeSensorService(env, listener);
    return reinterpret_cast<jlong>(service);
}

static void registerProximityActiveListenerNative(JNIEnv* env, jclass, jlong ptr) {
    auto* service = reinterpret_cast<NativeSensorService*>(ptr);
    service->registerProximityActiveListener();
}

static void unregisterProximityActiveListenerNative(JNIEnv* env, jclass, jlong ptr) {
    auto* service = reinterpret_cast<NativeSensorService*>(ptr);
    service->unregisterProximityActiveListener();
}

static const JNINativeMethod methods[] = {
        {
                "startSensorServiceNative", "(L" PROXIMITY_ACTIVE_CLASS ";)J",
                reinterpret_cast<void*>(startSensorServiceNative)
        },
        {
                "registerProximityActiveListenerNative", "(J)V",
                reinterpret_cast<void*>(registerProximityActiveListenerNative)
        },
        {
                "unregisterProximityActiveListenerNative", "(J)V",
                reinterpret_cast<void*>(unregisterProximityActiveListenerNative)
         },

};

int register_android_server_sensor_SensorService(JavaVM* vm, JNIEnv* env) {
    sJvm = vm;
    jclass listenerClass = FindClassOrDie(env, PROXIMITY_ACTIVE_CLASS);
    sMethodIdOnProximityActive = GetMethodIDOrDie(env, listenerClass, "onProximityActive", "(Z)V");
    return jniRegisterNativeMethods(env, "com/android/server/sensors/SensorService", methods,
                                    NELEM(methods));
}

}; // namespace android
