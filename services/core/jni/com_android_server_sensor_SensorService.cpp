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
#include <string.h>
#include <utils/Log.h>
#include <utils/misc.h>

#include <mutex>

#define PROXIMITY_ACTIVE_CLASS \
    "com/android/server/sensors/SensorManagerInternal$ProximityActiveListener"

#define RUNTIME_SENSOR_CALLBACK_CLASS \
    "com/android/server/sensors/SensorManagerInternal$RuntimeSensorStateChangeCallback"

namespace android {

static JavaVM* sJvm = nullptr;
static jmethodID sMethodIdOnProximityActive;
static jmethodID sMethodIdOnStateChanged;

class NativeSensorService {
public:
    NativeSensorService(JNIEnv* env, jobject listener);

    void registerProximityActiveListener();
    void unregisterProximityActiveListener();
    jint registerRuntimeSensor(JNIEnv* env, jint deviceId, jint type, jstring name, jstring vendor,
                               jobject callback);
    void unregisterRuntimeSensor(jint handle);
    jboolean sendRuntimeSensorEvent(JNIEnv* env, jint handle, jint type, jlong timestamp,
                                    jfloatArray values);

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

    class RuntimeSensorCallbackDelegate : public SensorService::RuntimeSensorStateChangeCallback {
    public:
        RuntimeSensorCallbackDelegate(JNIEnv* env, jobject callback);
        ~RuntimeSensorCallbackDelegate();

        void onStateChanged(bool enabled, int64_t samplingPeriodNs,
                            int64_t batchReportLatencyNs) override;

    private:
        jobject mCallback;
    };
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

jint NativeSensorService::registerRuntimeSensor(JNIEnv* env, jint deviceId, jint type, jstring name,
                                                jstring vendor, jobject callback) {
    if (mService == nullptr) {
        ALOGD("Dropping registerRuntimeSensor, sensor service not available.");
        return -1;
    }

    sensor_t sensor{
            .name = env->GetStringUTFChars(name, 0),
            .vendor = env->GetStringUTFChars(vendor, 0),
            .version = sizeof(sensor_t),
            .type = type,
    };

    sp<RuntimeSensorCallbackDelegate> callbackDelegate(
            new RuntimeSensorCallbackDelegate(env, callback));
    return mService->registerRuntimeSensor(sensor, deviceId, callbackDelegate);
}

void NativeSensorService::unregisterRuntimeSensor(jint handle) {
    if (mService == nullptr) {
        ALOGD("Dropping unregisterProximityActiveListener, sensor service not available.");
        return;
    }

    mService->unregisterRuntimeSensor(handle);
}

jboolean NativeSensorService::sendRuntimeSensorEvent(JNIEnv* env, jint handle, jint type,
                                                     jlong timestamp, jfloatArray values) {
    if (mService == nullptr) {
        ALOGD("Dropping sendRuntimeSensorEvent, sensor service not available.");
        return false;
    }
    if (values == nullptr) {
        ALOGD("Dropping sendRuntimeSensorEvent, no values.");
        return false;
    }

    sensors_event_t event{
            .version = sizeof(sensors_event_t),
            .timestamp = timestamp,
            .sensor = handle,
            .type = type,
    };

    int valuesLength = env->GetArrayLength(values);
    jfloat* sensorValues = env->GetFloatArrayElements(values, nullptr);

    switch (type) {
        case SENSOR_TYPE_ACCELEROMETER:
        case SENSOR_TYPE_MAGNETIC_FIELD:
        case SENSOR_TYPE_ORIENTATION:
        case SENSOR_TYPE_GYROSCOPE:
        case SENSOR_TYPE_GRAVITY:
        case SENSOR_TYPE_LINEAR_ACCELERATION: {
            if (valuesLength != 3) {
                ALOGD("Dropping sendRuntimeSensorEvent, wrong number of values.");
                return false;
            }
            event.acceleration.x = sensorValues[0];
            event.acceleration.y = sensorValues[1];
            event.acceleration.z = sensorValues[2];
            break;
        }
        case SENSOR_TYPE_DEVICE_ORIENTATION:
        case SENSOR_TYPE_LIGHT:
        case SENSOR_TYPE_PRESSURE:
        case SENSOR_TYPE_TEMPERATURE:
        case SENSOR_TYPE_PROXIMITY:
        case SENSOR_TYPE_RELATIVE_HUMIDITY:
        case SENSOR_TYPE_AMBIENT_TEMPERATURE:
        case SENSOR_TYPE_SIGNIFICANT_MOTION:
        case SENSOR_TYPE_STEP_DETECTOR:
        case SENSOR_TYPE_TILT_DETECTOR:
        case SENSOR_TYPE_WAKE_GESTURE:
        case SENSOR_TYPE_GLANCE_GESTURE:
        case SENSOR_TYPE_PICK_UP_GESTURE:
        case SENSOR_TYPE_WRIST_TILT_GESTURE:
        case SENSOR_TYPE_STATIONARY_DETECT:
        case SENSOR_TYPE_MOTION_DETECT:
        case SENSOR_TYPE_HEART_BEAT:
        case SENSOR_TYPE_LOW_LATENCY_OFFBODY_DETECT: {
            if (valuesLength != 1) {
                ALOGD("Dropping sendRuntimeSensorEvent, wrong number of values.");
                return false;
            }
            event.data[0] = sensorValues[0];
            break;
        }
        default: {
            if (valuesLength > 16) {
                ALOGD("Dropping sendRuntimeSensorEvent, number of values exceeds the maximum.");
                return false;
            }
            memcpy(event.data, sensorValues, valuesLength * sizeof(float));
        }
    }

    status_t err = mService->sendRuntimeSensorEvent(event);
    return err == OK;
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

NativeSensorService::RuntimeSensorCallbackDelegate::RuntimeSensorCallbackDelegate(JNIEnv* env,
                                                                                  jobject callback)
      : mCallback(env->NewGlobalRef(callback)) {}

NativeSensorService::RuntimeSensorCallbackDelegate::~RuntimeSensorCallbackDelegate() {
    AndroidRuntime::getJNIEnv()->DeleteGlobalRef(mCallback);
}

void NativeSensorService::RuntimeSensorCallbackDelegate::onStateChanged(
        bool enabled, int64_t samplingPeriodNs, int64_t batchReportLatencyNs) {
    auto jniEnv = GetOrAttachJNIEnvironment(sJvm);
    jniEnv->CallVoidMethod(mCallback, sMethodIdOnStateChanged, static_cast<jboolean>(enabled),
                           static_cast<jint>(ns2us(samplingPeriodNs)),
                           static_cast<jint>(ns2us(batchReportLatencyNs)));
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

static jint registerRuntimeSensorNative(JNIEnv* env, jclass, jlong ptr, jint deviceId, jint type,
                                        jstring name, jstring vendor, jobject callback) {
    auto* service = reinterpret_cast<NativeSensorService*>(ptr);
    return service->registerRuntimeSensor(env, deviceId, type, name, vendor, callback);
}

static void unregisterRuntimeSensorNative(JNIEnv* env, jclass, jlong ptr, jint handle) {
    auto* service = reinterpret_cast<NativeSensorService*>(ptr);
    service->unregisterRuntimeSensor(handle);
}

static jboolean sendRuntimeSensorEventNative(JNIEnv* env, jclass, jlong ptr, jint handle, jint type,
                                             jlong timestamp, jfloatArray values) {
    auto* service = reinterpret_cast<NativeSensorService*>(ptr);
    return service->sendRuntimeSensorEvent(env, handle, type, timestamp, values);
}

static const JNINativeMethod methods[] = {
        {"startSensorServiceNative", "(L" PROXIMITY_ACTIVE_CLASS ";)J",
         reinterpret_cast<void*>(startSensorServiceNative)},
        {"registerProximityActiveListenerNative", "(J)V",
         reinterpret_cast<void*>(registerProximityActiveListenerNative)},
        {"unregisterProximityActiveListenerNative", "(J)V",
         reinterpret_cast<void*>(unregisterProximityActiveListenerNative)},
        {"registerRuntimeSensorNative",
         "(JIILjava/lang/String;Ljava/lang/String;L" RUNTIME_SENSOR_CALLBACK_CLASS ";)I",
         reinterpret_cast<void*>(registerRuntimeSensorNative)},
        {"unregisterRuntimeSensorNative", "(JI)V",
         reinterpret_cast<void*>(unregisterRuntimeSensorNative)},
        {"sendRuntimeSensorEventNative", "(JIIJ[F)Z",
         reinterpret_cast<void*>(sendRuntimeSensorEventNative)},
};

int register_android_server_sensor_SensorService(JavaVM* vm, JNIEnv* env) {
    sJvm = vm;
    jclass listenerClass = FindClassOrDie(env, PROXIMITY_ACTIVE_CLASS);
    sMethodIdOnProximityActive = GetMethodIDOrDie(env, listenerClass, "onProximityActive", "(Z)V");
    jclass runtimeSensorCallbackClass = FindClassOrDie(env, RUNTIME_SENSOR_CALLBACK_CLASS);
    sMethodIdOnStateChanged =
            GetMethodIDOrDie(env, runtimeSensorCallbackClass, "onStateChanged", "(ZII)V");
    return jniRegisterNativeMethods(env, "com/android/server/sensors/SensorService", methods,
                                    NELEM(methods));
}

}; // namespace android
