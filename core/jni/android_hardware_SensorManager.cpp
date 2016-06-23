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

#include "JNIHelp.h"
#include "android_os_MessageQueue.h"
#include "core_jni_helpers.h"
#include "jni.h"

#include <ScopedUtfChars.h>
#include <ScopedLocalRef.h>
#include <android_runtime/AndroidRuntime.h>
#include <gui/Sensor.h>
#include <gui/SensorEventQueue.h>
#include <gui/SensorManager.h>
#include <utils/Log.h>
#include <utils/Looper.h>
#include <utils/Vector.h>

#include <map>

namespace {

using namespace android;

struct {
    jclass clazz;
    jmethodID dispatchSensorEvent;
    jmethodID dispatchFlushCompleteEvent;
    jmethodID dispatchAdditionalInfoEvent;
} gBaseEventQueueClassInfo;

struct SensorOffsets
{
    jclass      clazz;
    //fields
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
    //methods
    jmethodID   setType;
    jmethodID   setUuid;
    jmethodID   init;
} gSensorOffsets;

struct ListOffsets {
    jclass      clazz;
    jmethodID   add;
} gListOffsets;

/*
 * nativeClassInit is not inteneded to be thread-safe. It should be called before other native...
 * functions (except nativeCreate).
 */
static void
nativeClassInit (JNIEnv *_env, jclass _this)
{
    //android.hardware.Sensor
    SensorOffsets& sensorOffsets = gSensorOffsets;
    jclass sensorClass = (jclass) _env->NewGlobalRef(_env->FindClass("android/hardware/Sensor"));
    sensorOffsets.clazz       = sensorClass;
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
    sensorOffsets.setUuid = _env->GetMethodID(sensorClass, "setUuid", "(JJ)V");
    sensorOffsets.init = _env->GetMethodID(sensorClass, "<init>", "()V");

    // java.util.List;
    ListOffsets& listOffsets = gListOffsets;
    jclass listClass = (jclass) _env->NewGlobalRef(_env->FindClass("java/util/List"));
    listOffsets.clazz = listClass;
    listOffsets.add = _env->GetMethodID(listClass, "add", "(Ljava/lang/Object;)Z");
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

static jobject
translateNativeSensorToJavaSensor(JNIEnv *env, jobject sensor, const Sensor& nativeSensor) {
    const SensorOffsets& sensorOffsets(gSensorOffsets);

    if (sensor == NULL) {
        // Sensor sensor = new Sensor();
        sensor = env->NewObject(sensorOffsets.clazz, sensorOffsets.init, "");
    }

    if (sensor != NULL) {
        jstring name = env->NewStringUTF(nativeSensor.getName().string());
        jstring vendor = env->NewStringUTF(nativeSensor.getVendor().string());
        jstring requiredPermission =
                env->NewStringUTF(nativeSensor.getRequiredPermission().string());

        env->SetObjectField(sensor, sensorOffsets.name,      name);
        env->SetObjectField(sensor, sensorOffsets.vendor,    vendor);
        env->SetIntField(sensor, sensorOffsets.version,      nativeSensor.getVersion());
        env->SetIntField(sensor, sensorOffsets.handle,       nativeSensor.getHandle());
        env->SetFloatField(sensor, sensorOffsets.range,      nativeSensor.getMaxValue());
        env->SetFloatField(sensor, sensorOffsets.resolution, nativeSensor.getResolution());
        env->SetFloatField(sensor, sensorOffsets.power,      nativeSensor.getPowerUsage());
        env->SetIntField(sensor, sensorOffsets.minDelay,     nativeSensor.getMinDelay());
        env->SetIntField(sensor, sensorOffsets.fifoReservedEventCount,
                         nativeSensor.getFifoReservedEventCount());
        env->SetIntField(sensor, sensorOffsets.fifoMaxEventCount,
                         nativeSensor.getFifoMaxEventCount());
        env->SetObjectField(sensor, sensorOffsets.requiredPermission,
                            requiredPermission);
        env->SetIntField(sensor, sensorOffsets.maxDelay, nativeSensor.getMaxDelay());
        env->SetIntField(sensor, sensorOffsets.flags, nativeSensor.getFlags());

        if (env->CallBooleanMethod(sensor, sensorOffsets.setType, nativeSensor.getType())
                == JNI_FALSE) {
            jstring stringType = getInternedString(env, &nativeSensor.getStringType());
            env->SetObjectField(sensor, sensorOffsets.stringType, stringType);
        }

        // TODO(b/29547335): Rename "setUuid" method to "setId".
        int64_t id = nativeSensor.getId();
        env->CallVoidMethod(sensor, sensorOffsets.setUuid, id, 0);
    }
    return sensor;
}

static jboolean
nativeGetSensorAtIndex(JNIEnv *env, jclass clazz, jlong sensorManager, jobject sensor, jint index)
{
    SensorManager* mgr = reinterpret_cast<SensorManager*>(sensorManager);

    Sensor const* const* sensorList;
    ssize_t count = mgr->getSensorList(&sensorList);
    if (ssize_t(index) >= count) {
        return false;
    }

    return translateNativeSensorToJavaSensor(env, sensor, *sensorList[index]) != NULL;
}

static void
nativeGetDynamicSensors(JNIEnv *env, jclass clazz, jlong sensorManager, jobject sensorList) {

    SensorManager* mgr = reinterpret_cast<SensorManager*>(sensorManager);
    const ListOffsets& listOffsets(gListOffsets);

    Vector<Sensor> nativeList;

    mgr->getDynamicSensorList(nativeList);

    ALOGI("DYNS native SensorManager.getDynamicSensorList return %d sensors", nativeList.size());
    for (size_t i = 0; i < nativeList.size(); ++i) {
        jobject sensor = translateNativeSensorToJavaSensor(env, NULL, nativeList[i]);
        // add to list
        env->CallBooleanMethod(sensorList, listOffsets.add, sensor);
    }
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
    jfloatArray mFloatScratch;
    jintArray   mIntScratch;
public:
    Receiver(const sp<SensorEventQueue>& sensorQueue,
            const sp<MessageQueue>& messageQueue,
            jobject receiverWeak) {
        JNIEnv* env = AndroidRuntime::getJNIEnv();
        mSensorQueue = sensorQueue;
        mMessageQueue = messageQueue;
        mReceiverWeakGlobal = env->NewGlobalRef(receiverWeak);

        mIntScratch = (jintArray) env->NewGlobalRef(env->NewIntArray(16));
        mFloatScratch = (jfloatArray) env->NewGlobalRef(env->NewFloatArray(16));
    }
    ~Receiver() {
        JNIEnv* env = AndroidRuntime::getJNIEnv();
        env->DeleteGlobalRef(mReceiverWeakGlobal);
        env->DeleteGlobalRef(mFloatScratch);
        env->DeleteGlobalRef(mIntScratch);
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
                    env->SetFloatArrayRegion(mFloatScratch, 0, 1, &value);
                } else if (buffer[i].type == SENSOR_TYPE_DYNAMIC_SENSOR_META) {
                    float value[2];
                    value[0] = buffer[i].dynamic_sensor_meta.connected ? 1.f: 0.f;
                    value[1] = float(buffer[i].dynamic_sensor_meta.handle);
                    env->SetFloatArrayRegion(mFloatScratch, 0, 2, value);
                } else if (buffer[i].type == SENSOR_TYPE_ADDITIONAL_INFO) {
                    env->SetIntArrayRegion(mIntScratch, 0, 14,
                                           buffer[i].additional_info.data_int32);
                    env->SetFloatArrayRegion(mFloatScratch, 0, 14,
                                             buffer[i].additional_info.data_float);
                } else {
                    env->SetFloatArrayRegion(mFloatScratch, 0, 16, buffer[i].data);
                }

                if (buffer[i].type == SENSOR_TYPE_META_DATA) {
                    // This is a flush complete sensor event. Call dispatchFlushCompleteEvent
                    // method.
                    if (receiverObj.get()) {
                        env->CallVoidMethod(receiverObj.get(),
                                            gBaseEventQueueClassInfo.dispatchFlushCompleteEvent,
                                            buffer[i].meta_data.sensor);
                    }
                } else if (buffer[i].type == SENSOR_TYPE_ADDITIONAL_INFO) {
                    // This is a flush complete sensor event. Call dispatchAdditionalInfoEvent
                    // method.
                    if (receiverObj.get()) {
                        int type = buffer[i].additional_info.type;
                        int serial = buffer[i].additional_info.serial;
                        env->CallVoidMethod(receiverObj.get(),
                                            gBaseEventQueueClassInfo.dispatchAdditionalInfoEvent,
                                            buffer[i].sensor,
                                            type, serial,
                                            mFloatScratch,
                                            mIntScratch,
                                            buffer[i].timestamp);
                    }
                }else {
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
                                            mFloatScratch,
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
        jobject eventQWeak, jobject msgQ, jstring packageName, jint mode) {
    SensorManager* mgr = reinterpret_cast<SensorManager*>(sensorManager);
    ScopedUtfChars packageUtf(env, packageName);
    String8 clientName(packageUtf.c_str());
    sp<SensorEventQueue> queue(mgr->createEventQueue(clientName, mode));

    sp<MessageQueue> messageQueue = android_os_MessageQueue_getMessageQueue(env, msgQ);
    if (messageQueue == NULL) {
        jniThrowRuntimeException(env, "MessageQueue is not initialized.");
        return 0;
    }

    sp<Receiver> receiver = new Receiver(queue, messageQueue, eventQWeak);
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

static const JNINativeMethod gSystemSensorManagerMethods[] = {
    {"nativeClassInit",
            "()V",
            (void*)nativeClassInit },
    {"nativeCreate",
             "(Ljava/lang/String;)J",
             (void*)nativeCreate },

    {"nativeGetSensorAtIndex",
            "(JLandroid/hardware/Sensor;I)Z",
            (void*)nativeGetSensorAtIndex },

    {"nativeGetDynamicSensors",
            "(JLjava/util/List;)V",
            (void*)nativeGetDynamicSensors },

    {"nativeIsDataInjectionEnabled",
            "(J)Z",
            (void*)nativeIsDataInjectionEnabled},
};

static const JNINativeMethod gBaseEventQueueMethods[] = {
    {"nativeInitBaseEventQueue",
             "(JLjava/lang/ref/WeakReference;Landroid/os/MessageQueue;Ljava/lang/String;ILjava/lang/String;)J",
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

} //unnamed namespace

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

    gBaseEventQueueClassInfo.dispatchAdditionalInfoEvent = GetMethodIDOrDie(env,
            gBaseEventQueueClassInfo.clazz, "dispatchAdditionalInfoEvent", "(III[F[I)V");

    return 0;
}
