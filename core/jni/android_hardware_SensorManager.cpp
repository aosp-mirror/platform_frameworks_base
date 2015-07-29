/*
 * Copyright 2008, The Android Open Source Project
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

#define LOG_TAG "SensorManager"

#include <map>

#include <ScopedUtfChars.h>
#include <ScopedLocalRef.h>

#include <utils/Log.h>
#include <utils/Looper.h>

#include <gui/Sensor.h>
#include <gui/SensorManager.h>
#include <gui/SensorEventQueue.h>

#include "jni.h"
#include "JNIHelp.h"
#include "android_os_MessageQueue.h"
#include <android_runtime/AndroidRuntime.h>

#include "core_jni_helpers.h"

static struct {
    jclass clazz;
    jmethodID dispatchSensorEvent;
    jmethodID dispatchFlushCompleteEvent;
} gBaseEventQueueClassInfo;

namespace android {

struct SensorOffsets
{
    jfieldID    name;
    jfieldID    vendor;
    jfieldID    version;
    jfieldID    handle;
    jfieldID    range;
    jfieldID    resolution;
    jfieldID    power;
    jfieldID    minDelay;
    jfieldID    fifoReservedEventCount;
    jfieldID    fifoMaxEventCount;
    jfieldID    stringType;
    jfieldID    requiredPermission;
    jfieldID    maxDelay;
    jfieldID    flags;
    jmethodID   setType;
} gSensorOffsets;


/*
 * The method below are not thread-safe and not intended to be
 */

static void
nativeClassInit (JNIEnv *_env, jclass _this)
{
    jclass sensorClass = _env->FindClass("android/hardware/Sensor");
    SensorOffsets& sensorOffsets = gSensorOffsets;
    sensorOffsets.name        = _env->GetFieldID(sensorClass, "mName",      "Ljava/lang/String;");
    sensorOffsets.vendor      = _env->GetFieldID(sensorClass, "mVendor",    "Ljava/lang/String;");
    sensorOffsets.version     = _env->GetFieldID(sensorClass, "mVersion",   "I");
    sensorOffsets.handle      = _env->GetFieldID(sensorClass, "mHandle",    "I");
    sensorOffsets.range       = _env->GetFieldID(sensorClass, "mMaxRange",  "F");
    sensorOffsets.resolution  = _env->GetFieldID(sensorClass, "mResolution","F");
    sensorOffsets.power       = _env->GetFieldID(sensorClass, "mPower",     "F");
    sensorOffsets.minDelay    = _env->GetFieldID(sensorClass, "mMinDelay",  "I");
    sensorOffsets.fifoReservedEventCount =
            _env->GetFieldID(sensorClass, "mFifoReservedEventCount",  "I");
    sensorOffsets.fifoMaxEventCount = _env->GetFieldID(sensorClass, "mFifoMaxEventCount",  "I");
    sensorOffsets.stringType = _env->GetFieldID(sensorClass, "mStringType", "Ljava/lang/String;");
    sensorOffsets.requiredPermission = _env->GetFieldID(sensorClass, "mRequiredPermission",
                                                        "Ljava/lang/String;");
    sensorOffsets.maxDelay    = _env->GetFieldID(sensorClass, "mMaxDelay",  "I");
    sensorOffsets.flags = _env->GetFieldID(sensorClass, "mFlags",  "I");
    sensorOffsets.setType = _env->GetMethodID(sensorClass, "setType", "(I)Z");
}

/**
 * A key comparator predicate.
 * It is used to intern strings associated with Sensor data.
 * It defines a 'Strict weak ordering' for the interned strings.
 */
class InternedStringCompare {
public:
    bool operator()(const String8* string1, const String8* string2) const {
        if (string1 == NULL) {
            return string2 != NULL;
        }
        if (string2 == NULL) {
            return false;
        }
        return string1->compare(*string2) < 0;
    }
};

/**
 * A localized interning mechanism for Sensor strings.
 * We implement our own interning to avoid the overhead of using java.lang.String#intern().
 * It is common that Vendor, StringType, and RequirePermission data is common between many of the
 * Sensors, by interning the memory usage to represent Sensors is optimized.
 */
static jstring
getInternedString(JNIEnv *env, const String8* string) {
    static std::map<const String8*, jstring, InternedStringCompare> internedStrings;

    jstring internedString;
    std::map<const String8*, jstring>::iterator iterator = internedStrings.find(string);
    if (iterator != internedStrings.end()) {
        internedString = iterator->second;
    } else {
        jstring localString = env->NewStringUTF(string->string());
        // we are implementing our own interning so expect these strings to be backed by global refs
        internedString = (jstring) env->NewGlobalRef(localString);
        internedStrings.insert(std::make_pair(string, internedString));
        env->DeleteLocalRef(localString);
    }
    return internedString;
}

static jlong
nativeCreate
(JNIEnv *env, jclass clazz, jstring opPackageName)
{
    ScopedUtfChars opPackageNameUtf(env, opPackageName);
    return (jlong) &SensorManager::getInstanceForPackage(String16(opPackageNameUtf.c_str()));
}

static jboolean
nativeGetSensorAtIndex(JNIEnv *env, jclass clazz, jlong sensorManager, jobject sensor, jint index)
{
    SensorManager* mgr = reinterpret_cast<SensorManager*>(sensorManager);

    Sensor const* const* sensorList;
    size_t count = mgr->getSensorList(&sensorList);
    if (size_t(index) >= count) {
        return false;
    }

    Sensor const* const list = sensorList[index];
    const SensorOffsets& sensorOffsets(gSensorOffsets);
    jstring name = getInternedString(env, &list->getName());
    jstring vendor = getInternedString(env, &list->getVendor());
    jstring requiredPermission = getInternedString(env, &list->getRequiredPermission());
    env->SetObjectField(sensor, sensorOffsets.name,      name);
    env->SetObjectField(sensor, sensorOffsets.vendor,    vendor);
    env->SetIntField(sensor, sensorOffsets.version,      list->getVersion());
    env->SetIntField(sensor, sensorOffsets.handle,       list->getHandle());
    env->SetFloatField(sensor, sensorOffsets.range,      list->getMaxValue());
    env->SetFloatField(sensor, sensorOffsets.resolution, list->getResolution());
    env->SetFloatField(sensor, sensorOffsets.power,      list->getPowerUsage());
    env->SetIntField(sensor, sensorOffsets.minDelay,     list->getMinDelay());
    env->SetIntField(sensor, sensorOffsets.fifoReservedEventCount,
                     list->getFifoReservedEventCount());
    env->SetIntField(sensor, sensorOffsets.fifoMaxEventCount,
                     list->getFifoMaxEventCount());
    env->SetObjectField(sensor, sensorOffsets.requiredPermission,
                        requiredPermission);
    env->SetIntField(sensor, sensorOffsets.maxDelay, list->getMaxDelay());
    env->SetIntField(sensor, sensorOffsets.flags, list->getFlags());
    if (env->CallBooleanMethod(sensor, sensorOffsets.setType, list->getType()) == JNI_FALSE) {
        jstring stringType = getInternedString(env, &list->getStringType());
        env->SetObjectField(sensor, sensorOffsets.stringType, stringType);
    }
    return true;
}

static jboolean nativeIsDataInjectionEnabled(JNIEnv *_env, jclass _this, jlong sensorManager) {
    SensorManager* mgr = reinterpret_cast<SensorManager*>(sensorManager);
    return mgr->isDataInjectionEnabled();
}

//----------------------------------------------------------------------------

class Receiver : public LooperCallback {
    sp<SensorEventQueue> mSensorQueue;
    sp<MessageQueue> mMessageQueue;
    jobject mReceiverWeakGlobal;
    jfloatArray mScratch;
public:
    Receiver(const sp<SensorEventQueue>& sensorQueue,
            const sp<MessageQueue>& messageQueue,
            jobject receiverWeak, jfloatArray scratch) {
        JNIEnv* env = AndroidRuntime::getJNIEnv();
        mSensorQueue = sensorQueue;
        mMessageQueue = messageQueue;
        mReceiverWeakGlobal = env->NewGlobalRef(receiverWeak);
        mScratch = (jfloatArray)env->NewGlobalRef(scratch);
    }
    ~Receiver() {
        JNIEnv* env = AndroidRuntime::getJNIEnv();
        env->DeleteGlobalRef(mReceiverWeakGlobal);
        env->DeleteGlobalRef(mScratch);
    }
    sp<SensorEventQueue> getSensorEventQueue() const {
        return mSensorQueue;
    }

    void destroy() {
        mMessageQueue->getLooper()->removeFd( mSensorQueue->getFd() );
    }

private:
    virtual void onFirstRef() {
        LooperCallback::onFirstRef();
        mMessageQueue->getLooper()->addFd(mSensorQueue->getFd(), 0,
                ALOOPER_EVENT_INPUT, this, mSensorQueue.get());
    }

    virtual int handleEvent(int fd, int events, void* data) {
        JNIEnv* env = AndroidRuntime::getJNIEnv();
        sp<SensorEventQueue> q = reinterpret_cast<SensorEventQueue *>(data);
        ScopedLocalRef<jobject> receiverObj(env, jniGetReferent(env, mReceiverWeakGlobal));

        ssize_t n;
        ASensorEvent buffer[16];
        while ((n = q->read(buffer, 16)) > 0) {
            for (int i=0 ; i<n ; i++) {
                if (buffer[i].type == SENSOR_TYPE_STEP_COUNTER) {
                    // step-counter returns a uint64, but the java API only deals with floats
                    float value = float(buffer[i].u64.step_counter);
                    env->SetFloatArrayRegion(mScratch, 0, 1, &value);
                } else {
                    env->SetFloatArrayRegion(mScratch, 0, 16, buffer[i].data);
                }

                if (buffer[i].type == SENSOR_TYPE_META_DATA) {
                    // This is a flush complete sensor event. Call dispatchFlushCompleteEvent
                    // method.
                    if (receiverObj.get()) {
                        env->CallVoidMethod(receiverObj.get(),
                                            gBaseEventQueueClassInfo.dispatchFlushCompleteEvent,
                                            buffer[i].meta_data.sensor);
                    }
                } else {
                    int8_t status;
                    switch (buffer[i].type) {
                    case SENSOR_TYPE_ORIENTATION:
                    case SENSOR_TYPE_MAGNETIC_FIELD:
                    case SENSOR_TYPE_ACCELEROMETER:
                    case SENSOR_TYPE_GYROSCOPE:
                    case SENSOR_TYPE_GRAVITY:
                    case SENSOR_TYPE_LINEAR_ACCELERATION:
                        status = buffer[i].vector.status;
                        break;
                    case SENSOR_TYPE_HEART_RATE:
                        status = buffer[i].heart_rate.status;
                        break;
                    default:
                        status = SENSOR_STATUS_ACCURACY_HIGH;
                        break;
                    }
                    if (receiverObj.get()) {
                        env->CallVoidMethod(receiverObj.get(),
                                            gBaseEventQueueClassInfo.dispatchSensorEvent,
                                            buffer[i].sensor,
                                            mScratch,
                                            status,
                                            buffer[i].timestamp);
                    }
                }
                if (env->ExceptionCheck()) {
                    mSensorQueue->sendAck(buffer, n);
                    ALOGE("Exception dispatching input event.");
                    return 1;
                }
            }
            mSensorQueue->sendAck(buffer, n);
        }
        if (n<0 && n != -EAGAIN) {
            // FIXME: error receiving events, what to do in this case?
        }
        return 1;
    }
};

static jlong nativeInitSensorEventQueue(JNIEnv *env, jclass clazz, jlong sensorManager,
        jobject eventQWeak, jobject msgQ, jfloatArray scratch, jstring packageName, jint mode) {
    SensorManager* mgr = reinterpret_cast<SensorManager*>(sensorManager);
    ScopedUtfChars packageUtf(env, packageName);
    String8 clientName(packageUtf.c_str());
    sp<SensorEventQueue> queue(mgr->createEventQueue(clientName, mode));

    sp<MessageQueue> messageQueue = android_os_MessageQueue_getMessageQueue(env, msgQ);
    if (messageQueue == NULL) {
        jniThrowRuntimeException(env, "MessageQueue is not initialized.");
        return 0;
    }

    sp<Receiver> receiver = new Receiver(queue, messageQueue, eventQWeak, scratch);
    receiver->incStrong((void*)nativeInitSensorEventQueue);
    return jlong(receiver.get());
}

static jint nativeEnableSensor(JNIEnv *env, jclass clazz, jlong eventQ, jint handle, jint rate_us,
                               jint maxBatchReportLatency) {
    sp<Receiver> receiver(reinterpret_cast<Receiver *>(eventQ));
    return receiver->getSensorEventQueue()->enableSensor(handle, rate_us, maxBatchReportLatency,
                                                         0);
}

static jint nativeDisableSensor(JNIEnv *env, jclass clazz, jlong eventQ, jint handle) {
    sp<Receiver> receiver(reinterpret_cast<Receiver *>(eventQ));
    return receiver->getSensorEventQueue()->disableSensor(handle);
}

static void nativeDestroySensorEventQueue(JNIEnv *env, jclass clazz, jlong eventQ) {
    sp<Receiver> receiver(reinterpret_cast<Receiver *>(eventQ));
    receiver->destroy();
    receiver->decStrong((void*)nativeInitSensorEventQueue);
}

static jint nativeFlushSensor(JNIEnv *env, jclass clazz, jlong eventQ) {
    sp<Receiver> receiver(reinterpret_cast<Receiver *>(eventQ));
    return receiver->getSensorEventQueue()->flush();
}

static jint nativeInjectSensorData(JNIEnv *env, jclass clazz, jlong eventQ, jint handle,
        jfloatArray values, jint accuracy, jlong timestamp) {
    sp<Receiver> receiver(reinterpret_cast<Receiver *>(eventQ));
    // Create a sensor_event from the above data which can be injected into the HAL.
    ASensorEvent sensor_event;
    memset(&sensor_event, 0, sizeof(sensor_event));
    sensor_event.sensor = handle;
    sensor_event.timestamp = timestamp;
    env->GetFloatArrayRegion(values, 0, env->GetArrayLength(values), sensor_event.data);
    return receiver->getSensorEventQueue()->injectSensorEvent(sensor_event);
}
//----------------------------------------------------------------------------

static JNINativeMethod gSystemSensorManagerMethods[] = {
    {"nativeClassInit",
            "()V",
            (void*)nativeClassInit },
    {"nativeCreate",
             "(Ljava/lang/String;)J",
             (void*)nativeCreate },

    {"nativeGetSensorAtIndex",
            "(JLandroid/hardware/Sensor;I)Z",
            (void*)nativeGetSensorAtIndex },

    {"nativeIsDataInjectionEnabled",
            "(J)Z",
            (void*)nativeIsDataInjectionEnabled},
};

static JNINativeMethod gBaseEventQueueMethods[] = {
    {"nativeInitBaseEventQueue",
             "(JLjava/lang/ref/WeakReference;Landroid/os/MessageQueue;[FLjava/lang/String;ILjava/lang/String;)J",
             (void*)nativeInitSensorEventQueue },

    {"nativeEnableSensor",
            "(JIII)I",
            (void*)nativeEnableSensor },

    {"nativeDisableSensor",
            "(JI)I",
            (void*)nativeDisableSensor },

    {"nativeDestroySensorEventQueue",
            "(J)V",
            (void*)nativeDestroySensorEventQueue },

    {"nativeFlushSensor",
            "(J)I",
            (void*)nativeFlushSensor },

    {"nativeInjectSensorData",
            "(JI[FIJ)I",
            (void*)nativeInjectSensorData },
};

}; // namespace android

using namespace android;

int register_android_hardware_SensorManager(JNIEnv *env)
{
    RegisterMethodsOrDie(env, "android/hardware/SystemSensorManager",
            gSystemSensorManagerMethods, NELEM(gSystemSensorManagerMethods));

    RegisterMethodsOrDie(env, "android/hardware/SystemSensorManager$BaseEventQueue",
            gBaseEventQueueMethods, NELEM(gBaseEventQueueMethods));

    gBaseEventQueueClassInfo.clazz = FindClassOrDie(env,
            "android/hardware/SystemSensorManager$BaseEventQueue");

    gBaseEventQueueClassInfo.dispatchSensorEvent = GetMethodIDOrDie(env,
            gBaseEventQueueClassInfo.clazz, "dispatchSensorEvent", "(I[FIJ)V");

    gBaseEventQueueClassInfo.dispatchFlushCompleteEvent = GetMethodIDOrDie(env,
            gBaseEventQueueClassInfo.clazz, "dispatchFlushCompleteEvent", "(I)V");

    return 0;
}
