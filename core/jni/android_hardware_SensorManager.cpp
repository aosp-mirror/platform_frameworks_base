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

#include <utils/Log.h>
#include <utils/Looper.h>

#include <gui/Sensor.h>
#include <gui/SensorManager.h>
#include <gui/SensorEventQueue.h>

#include "jni.h"
#include "JNIHelp.h"
#include "android_os_MessageQueue.h"
#include <android_runtime/AndroidRuntime.h>

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
    jfieldID    type;
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
    sensorOffsets.type        = _env->GetFieldID(sensorClass, "mType",      "I");
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
}

static jint
nativeGetNextSensor(JNIEnv *env, jclass clazz, jobject sensor, jint next)
{
    SensorManager& mgr(SensorManager::getInstance());

    Sensor const* const* sensorList;
    size_t count = mgr.getSensorList(&sensorList);
    if (size_t(next) >= count)
        return -1;

    Sensor const* const list = sensorList[next];
    const SensorOffsets& sensorOffsets(gSensorOffsets);
    jstring name = env->NewStringUTF(list->getName().string());
    jstring vendor = env->NewStringUTF(list->getVendor().string());
    jstring stringType = env->NewStringUTF(list->getStringType().string());
    jstring requiredPermission = env->NewStringUTF(list->getRequiredPermission().string());
    env->SetObjectField(sensor, sensorOffsets.name,      name);
    env->SetObjectField(sensor, sensorOffsets.vendor,    vendor);
    env->SetIntField(sensor, sensorOffsets.version,      list->getVersion());
    env->SetIntField(sensor, sensorOffsets.handle,       list->getHandle());
    env->SetIntField(sensor, sensorOffsets.type,         list->getType());
    env->SetFloatField(sensor, sensorOffsets.range,      list->getMaxValue());
    env->SetFloatField(sensor, sensorOffsets.resolution, list->getResolution());
    env->SetFloatField(sensor, sensorOffsets.power,      list->getPowerUsage());
    env->SetIntField(sensor, sensorOffsets.minDelay,     list->getMinDelay());
    env->SetIntField(sensor, sensorOffsets.fifoReservedEventCount,
                     list->getFifoReservedEventCount());
    env->SetIntField(sensor, sensorOffsets.fifoMaxEventCount,
                     list->getFifoMaxEventCount());
    env->SetObjectField(sensor, sensorOffsets.stringType, stringType);
    env->SetObjectField(sensor, sensorOffsets.requiredPermission,
                        requiredPermission);
    env->SetIntField(sensor, sensorOffsets.maxDelay, list->getMaxDelay());
    env->SetIntField(sensor, sensorOffsets.flags, list->getFlags());
    next++;
    return size_t(next) < count ? next : 0;
}

//----------------------------------------------------------------------------

class Receiver : public LooperCallback {
    sp<SensorEventQueue> mSensorQueue;
    sp<MessageQueue> mMessageQueue;
    jobject mReceiverObject;
    jfloatArray mScratch;
public:
    Receiver(const sp<SensorEventQueue>& sensorQueue,
            const sp<MessageQueue>& messageQueue,
            jobject receiverObject, jfloatArray scratch) {
        JNIEnv* env = AndroidRuntime::getJNIEnv();
        mSensorQueue = sensorQueue;
        mMessageQueue = messageQueue;
        mReceiverObject = env->NewGlobalRef(receiverObject);
        mScratch = (jfloatArray)env->NewGlobalRef(scratch);
    }
    ~Receiver() {
        JNIEnv* env = AndroidRuntime::getJNIEnv();
        env->DeleteGlobalRef(mReceiverObject);
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
                    env->CallVoidMethod(mReceiverObject,
                                        gBaseEventQueueClassInfo.dispatchFlushCompleteEvent,
                                        buffer[i].meta_data.sensor);
                } else {
                    int8_t status;
                    switch (buffer[i].type) {
                    case SENSOR_TYPE_ORIENTATION:
                    case SENSOR_TYPE_MAGNETIC_FIELD:
                    case SENSOR_TYPE_ACCELEROMETER:
                    case SENSOR_TYPE_GYROSCOPE:
                        status = buffer[i].vector.status;
                        break;
                    case SENSOR_TYPE_HEART_RATE:
                        status = buffer[i].heart_rate.status;
                        break;
                    default:
                        status = SENSOR_STATUS_ACCURACY_HIGH;
                        break;
                    }
                    env->CallVoidMethod(mReceiverObject,
                                        gBaseEventQueueClassInfo.dispatchSensorEvent,
                                        buffer[i].sensor,
                                        mScratch,
                                        status,
                                        buffer[i].timestamp);
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

static jlong nativeInitSensorEventQueue(JNIEnv *env, jclass clazz, jobject eventQ, jobject msgQ, jfloatArray scratch) {
    SensorManager& mgr(SensorManager::getInstance());
    sp<SensorEventQueue> queue(mgr.createEventQueue());

    sp<MessageQueue> messageQueue = android_os_MessageQueue_getMessageQueue(env, msgQ);
    if (messageQueue == NULL) {
        jniThrowRuntimeException(env, "MessageQueue is not initialized.");
        return 0;
    }

    sp<Receiver> receiver = new Receiver(queue, messageQueue, eventQ, scratch);
    receiver->incStrong((void*)nativeInitSensorEventQueue);
    return jlong(receiver.get());
}

static jint nativeEnableSensor(JNIEnv *env, jclass clazz, jlong eventQ, jint handle, jint rate_us,
                               jint maxBatchReportLatency, jint reservedFlags) {
    sp<Receiver> receiver(reinterpret_cast<Receiver *>(eventQ));
    return receiver->getSensorEventQueue()->enableSensor(handle, rate_us, maxBatchReportLatency,
                                                         reservedFlags);
}

static jint nativeDisableSensor(JNIEnv *env, jclass clazz, jlong eventQ, jint handle) {
    sp<Receiver> receiver(reinterpret_cast<Receiver *>(eventQ));
    return receiver->getSensorEventQueue()->disableSensor(handle);
}

static void nativeDestroySensorEventQueue(JNIEnv *env, jclass clazz, jlong eventQ, jint handle) {
    sp<Receiver> receiver(reinterpret_cast<Receiver *>(eventQ));
    receiver->destroy();
    receiver->decStrong((void*)nativeInitSensorEventQueue);
}

static jint nativeFlushSensor(JNIEnv *env, jclass clazz, jlong eventQ) {
    sp<Receiver> receiver(reinterpret_cast<Receiver *>(eventQ));
    return receiver->getSensorEventQueue()->flush();
}

//----------------------------------------------------------------------------

static JNINativeMethod gSystemSensorManagerMethods[] = {
    {"nativeClassInit",
            "()V",
            (void*)nativeClassInit },

    {"nativeGetNextSensor",
            "(Landroid/hardware/Sensor;I)I",
            (void*)nativeGetNextSensor },
};

static JNINativeMethod gBaseEventQueueMethods[] = {
    {"nativeInitBaseEventQueue",
            "(Landroid/hardware/SystemSensorManager$BaseEventQueue;Landroid/os/MessageQueue;[F)J",
            (void*)nativeInitSensorEventQueue },

    {"nativeEnableSensor",
            "(JIIII)I",
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
};

}; // namespace android

using namespace android;

#define FIND_CLASS(var, className) \
        var = env->FindClass(className); \
        LOG_FATAL_IF(! var, "Unable to find class " className); \
        var = jclass(env->NewGlobalRef(var));

#define GET_METHOD_ID(var, clazz, methodName, methodDescriptor) \
        var = env->GetMethodID(clazz, methodName, methodDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find method " methodName);

int register_android_hardware_SensorManager(JNIEnv *env)
{
    jniRegisterNativeMethods(env, "android/hardware/SystemSensorManager",
            gSystemSensorManagerMethods, NELEM(gSystemSensorManagerMethods));

    jniRegisterNativeMethods(env, "android/hardware/SystemSensorManager$BaseEventQueue",
            gBaseEventQueueMethods, NELEM(gBaseEventQueueMethods));

    FIND_CLASS(gBaseEventQueueClassInfo.clazz, "android/hardware/SystemSensorManager$BaseEventQueue");

    GET_METHOD_ID(gBaseEventQueueClassInfo.dispatchSensorEvent,
            gBaseEventQueueClassInfo.clazz,
            "dispatchSensorEvent", "(I[FIJ)V");

    GET_METHOD_ID(gBaseEventQueueClassInfo.dispatchFlushCompleteEvent,
                  gBaseEventQueueClassInfo.clazz,
                  "dispatchFlushCompleteEvent", "(I)V");

    return 0;
}
