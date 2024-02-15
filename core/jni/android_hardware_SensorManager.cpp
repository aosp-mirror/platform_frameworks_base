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

#include <nativehelper/JNIHelp.h>
#include "android_os_MessageQueue.h"
#include "core_jni_helpers.h"
#include "jni.h"

#include <nativehelper/ScopedUtfChars.h>
#include <nativehelper/ScopedLocalRef.h>
#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/android_hardware_HardwareBuffer.h>
#include <vndk/hardware_buffer.h>
#include <sensor/Sensor.h>
#include <sensor/SensorEventQueue.h>
#include <sensor/SensorManager.h>
#include <cutils/native_handle.h>
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
    jmethodID   setId;
    jmethodID   setUuid;
    jmethodID   init;
} gSensorOffsets;

struct ListOffsets {
    jclass      clazz;
    jmethodID   add;
} gListOffsets;

struct StringOffsets {
    jclass      clazz;
    jmethodID   intern;
    jstring     emptyString;
} gStringOffsets;

/*
 * nativeClassInit is not inteneded to be thread-safe. It should be called before other native...
 * functions (except nativeCreate).
 */
static void
nativeClassInit (JNIEnv *_env, jclass _this)
{
    //android.hardware.Sensor
    SensorOffsets& sensorOffsets = gSensorOffsets;
    jclass sensorClass = (jclass)
            MakeGlobalRefOrDie(_env, FindClassOrDie(_env, "android/hardware/Sensor"));
    sensorOffsets.clazz = sensorClass;
    sensorOffsets.name = GetFieldIDOrDie(_env, sensorClass, "mName", "Ljava/lang/String;");
    sensorOffsets.vendor = GetFieldIDOrDie(_env, sensorClass, "mVendor", "Ljava/lang/String;");
    sensorOffsets.version = GetFieldIDOrDie(_env, sensorClass, "mVersion", "I");
    sensorOffsets.handle = GetFieldIDOrDie(_env, sensorClass, "mHandle", "I");
    sensorOffsets.range = GetFieldIDOrDie(_env, sensorClass, "mMaxRange", "F");
    sensorOffsets.resolution = GetFieldIDOrDie(_env, sensorClass, "mResolution","F");
    sensorOffsets.power = GetFieldIDOrDie(_env, sensorClass, "mPower", "F");
    sensorOffsets.minDelay = GetFieldIDOrDie(_env, sensorClass, "mMinDelay", "I");
    sensorOffsets.fifoReservedEventCount =
            GetFieldIDOrDie(_env,sensorClass, "mFifoReservedEventCount", "I");
    sensorOffsets.fifoMaxEventCount = GetFieldIDOrDie(_env,sensorClass, "mFifoMaxEventCount", "I");
    sensorOffsets.stringType =
            GetFieldIDOrDie(_env,sensorClass, "mStringType", "Ljava/lang/String;");
    sensorOffsets.requiredPermission =
            GetFieldIDOrDie(_env,sensorClass, "mRequiredPermission", "Ljava/lang/String;");
    sensorOffsets.maxDelay = GetFieldIDOrDie(_env,sensorClass, "mMaxDelay", "I");
    sensorOffsets.flags = GetFieldIDOrDie(_env,sensorClass, "mFlags", "I");

    sensorOffsets.setType = GetMethodIDOrDie(_env,sensorClass, "setType", "(I)Z");
    sensorOffsets.setId = GetMethodIDOrDie(_env,sensorClass, "setId", "(I)V");
    sensorOffsets.setUuid = GetMethodIDOrDie(_env,sensorClass, "setUuid", "(JJ)V");
    sensorOffsets.init = GetMethodIDOrDie(_env,sensorClass, "<init>", "()V");

    // java.util.List;
    ListOffsets& listOffsets = gListOffsets;
    jclass listClass = (jclass) MakeGlobalRefOrDie(_env, FindClassOrDie(_env, "java/util/List"));
    listOffsets.clazz = listClass;
    listOffsets.add = GetMethodIDOrDie(_env,listClass, "add", "(Ljava/lang/Object;)Z");

    // initialize java.lang.String and empty string intern
    StringOffsets& stringOffsets = gStringOffsets;
    stringOffsets.clazz = MakeGlobalRefOrDie(_env, FindClassOrDie(_env, "java/lang/String"));
    stringOffsets.intern =
            GetMethodIDOrDie(_env, stringOffsets.clazz, "intern", "()Ljava/lang/String;");
    ScopedLocalRef<jstring> empty(_env, _env->NewStringUTF(""));
    stringOffsets.emptyString = (jstring)
            MakeGlobalRefOrDie(_env, _env->CallObjectMethod(empty.get(), stringOffsets.intern));
}

uint64_t htonll(uint64_t ll) {
    constexpr uint32_t kBytesToTest = 0x12345678;
    constexpr uint8_t kFirstByte = (const uint8_t &)kBytesToTest;
    constexpr bool kIsLittleEndian = kFirstByte == 0x78;

    if constexpr (kIsLittleEndian) {
        return static_cast<uint64_t>(htonl(ll & 0xffffffff)) << 32 | htonl(ll >> 32);
    } else {
        return ll;
    }
}

static jstring getJavaInternedString(JNIEnv *env, const String8 &string) {
    if (string == "") {
        return gStringOffsets.emptyString;
    }

    ScopedLocalRef<jstring> javaString(env, env->NewStringUTF(string.c_str()));
    jstring internedString = (jstring)
            env->CallObjectMethod(javaString.get(), gStringOffsets.intern);
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
        jstring name = getJavaInternedString(env, nativeSensor.getName());
        jstring vendor = getJavaInternedString(env, nativeSensor.getVendor());
        jstring requiredPermission =
                getJavaInternedString(env, nativeSensor.getRequiredPermission());

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
            jstring stringType = getJavaInternedString(env, nativeSensor.getStringType());
            env->SetObjectField(sensor, sensorOffsets.stringType, stringType);
        }

        int32_t id = nativeSensor.getId();
        env->CallVoidMethod(sensor, sensorOffsets.setId, id);
        Sensor::uuid_t uuid = nativeSensor.getUuid();
        env->CallVoidMethod(sensor, sensorOffsets.setUuid, htonll(uuid.i64[0]),
                            htonll(uuid.i64[1]));
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

static jboolean nativeGetDefaultDeviceSensorAtIndex(JNIEnv *env, jclass clazz, jlong sensorManager,
                                                    jobject sensor, jint index) {
    SensorManager *mgr = reinterpret_cast<SensorManager *>(sensorManager);

    Vector<Sensor> sensorList;
    ssize_t count = mgr->getDefaultDeviceSensorList(sensorList);
    if (ssize_t(index) >= count) {
        return false;
    }

    return translateNativeSensorToJavaSensor(env, sensor, sensorList[index]) != NULL;
}

static void
nativeGetDynamicSensors(JNIEnv *env, jclass clazz, jlong sensorManager, jobject sensorList) {

    SensorManager* mgr = reinterpret_cast<SensorManager*>(sensorManager);
    const ListOffsets& listOffsets(gListOffsets);

    Vector<Sensor> nativeList;

    mgr->getDynamicSensorList(nativeList);

    ALOGI("DYNS native SensorManager.getDynamicSensorList return %zu sensors", nativeList.size());
    for (size_t i = 0; i < nativeList.size(); ++i) {
        jobject sensor = translateNativeSensorToJavaSensor(env, NULL, nativeList[i]);
        // add to list
        env->CallBooleanMethod(sensorList, listOffsets.add, sensor);
    }
}

static void nativeGetRuntimeSensors(JNIEnv *env, jclass clazz, jlong sensorManager, jint deviceId,
                                    jobject sensorList) {
    SensorManager *mgr = reinterpret_cast<SensorManager *>(sensorManager);
    const ListOffsets &listOffsets(gListOffsets);

    Vector<Sensor> nativeList;

    mgr->getRuntimeSensorList(deviceId, nativeList);

    ALOGI("DYNS native SensorManager.getRuntimeSensorList return %zu sensors", nativeList.size());
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

static jboolean nativeIsReplayDataInjectionEnabled(JNIEnv *_env, jclass _this,
                                                   jlong sensorManager) {
    SensorManager *mgr = reinterpret_cast<SensorManager *>(sensorManager);
    return mgr->isReplayDataInjectionEnabled();
}

static jboolean nativeIsHalBypassReplayDataInjectionEnabled(JNIEnv *_env, jclass _this,
                                                            jlong sensorManager) {
    SensorManager *mgr = reinterpret_cast<SensorManager *>(sensorManager);
    return mgr->isHalBypassReplayDataInjectionEnabled();
}

static jint nativeCreateDirectChannel(JNIEnv *_env, jclass _this, jlong sensorManager,
                                      jint deviceId, jlong size, jint channelType, jint fd,
                                      jobject hardwareBufferObj) {
    const native_handle_t *nativeHandle = nullptr;
    NATIVE_HANDLE_DECLARE_STORAGE(ashmemHandle, 1, 0);

    if (channelType == SENSOR_DIRECT_MEM_TYPE_ASHMEM) {
        native_handle_t *handle = native_handle_init(ashmemHandle, 1, 0);
        handle->data[0] = fd;
        nativeHandle = handle;
    } else if (channelType == SENSOR_DIRECT_MEM_TYPE_GRALLOC) {
        AHardwareBuffer *hardwareBuffer =
                android_hardware_HardwareBuffer_getNativeHardwareBuffer(_env, hardwareBufferObj);
        if (hardwareBuffer != nullptr) {
            nativeHandle = AHardwareBuffer_getNativeHandle(hardwareBuffer);
        }
    }

    if (nativeHandle == nullptr) {
        return BAD_VALUE;
    }

    SensorManager* mgr = reinterpret_cast<SensorManager*>(sensorManager);
    return mgr->createDirectChannel(deviceId, size, channelType, nativeHandle);
}

static void nativeDestroyDirectChannel(JNIEnv *_env, jclass _this, jlong sensorManager,
        jint channelHandle) {
    SensorManager* mgr = reinterpret_cast<SensorManager*>(sensorManager);
    mgr->destroyDirectChannel(channelHandle);
}

static jint nativeConfigDirectChannel(JNIEnv *_env, jclass _this, jlong sensorManager,
        jint channelHandle, jint sensorHandle, jint rate) {
    SensorManager* mgr = reinterpret_cast<SensorManager*>(sensorManager);
    return mgr->configureDirectChannel(channelHandle, sensorHandle, rate);
}

static jint nativeSetOperationParameter(JNIEnv *_env, jclass _this, jlong sensorManager,
        jint handle, jint type, jfloatArray floats, jintArray ints) {
    SensorManager* mgr = reinterpret_cast<SensorManager*>(sensorManager);
    Vector<float> floatVector;
    Vector<int32_t> int32Vector;

    if (floats != nullptr) {
        floatVector.resize(_env->GetArrayLength(floats));
        _env->GetFloatArrayRegion(floats, 0, _env->GetArrayLength(floats), floatVector.editArray());
    }

    if (ints != nullptr) {
        int32Vector.resize(_env->GetArrayLength(ints));
        _env->GetIntArrayRegion(ints, 0, _env->GetArrayLength(ints), int32Vector.editArray());
    }

    return mgr->setOperationParameter(handle, type, floatVector, int32Vector);
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
        ScopedLocalRef<jobject> receiverObj(env, GetReferent(env, mReceiverWeakGlobal));

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
                                        jobject eventQWeak, jobject msgQ, jstring packageName,
                                        jint mode, jstring opPackageName, jstring attributionTag) {
    SensorManager* mgr = reinterpret_cast<SensorManager*>(sensorManager);
    ScopedUtfChars packageUtf(env, packageName);
    String8 clientName(packageUtf.c_str());

    String16 attributionTagName("");
    if (attributionTag != nullptr) {
        ScopedUtfChars attrUtf(env, attributionTag);
        attributionTagName = String16(attrUtf.c_str());
    }
    sp<SensorEventQueue> queue(mgr->createEventQueue(clientName, mode, attributionTagName));

    if (queue == NULL) {
        jniThrowRuntimeException(env, "Cannot construct native SensorEventQueue.");
        return 0;
    }

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
        {"nativeClassInit", "()V", (void *)nativeClassInit},
        {"nativeCreate", "(Ljava/lang/String;)J", (void *)nativeCreate},

        {"nativeGetSensorAtIndex", "(JLandroid/hardware/Sensor;I)Z",
         (void *)nativeGetSensorAtIndex},

        {"nativeGetDefaultDeviceSensorAtIndex", "(JLandroid/hardware/Sensor;I)Z",
         (void *)nativeGetDefaultDeviceSensorAtIndex},

        {"nativeGetDynamicSensors", "(JLjava/util/List;)V", (void *)nativeGetDynamicSensors},

        {"nativeGetRuntimeSensors", "(JILjava/util/List;)V", (void *)nativeGetRuntimeSensors},

        {"nativeIsDataInjectionEnabled", "(J)Z", (void *)nativeIsDataInjectionEnabled},

        {"nativeIsReplayDataInjectionEnabled", "(J)Z", (void *)nativeIsReplayDataInjectionEnabled},

        {"nativeIsHalBypassReplayDataInjectionEnabled", "(J)Z",
         (void *)nativeIsHalBypassReplayDataInjectionEnabled},

        {"nativeCreateDirectChannel", "(JIJIILandroid/hardware/HardwareBuffer;)I",
         (void *)nativeCreateDirectChannel},

        {"nativeDestroyDirectChannel", "(JI)V", (void *)nativeDestroyDirectChannel},

        {"nativeConfigDirectChannel", "(JIII)I", (void *)nativeConfigDirectChannel},

        {"nativeSetOperationParameter", "(JII[F[I)I", (void *)nativeSetOperationParameter},
};

static const JNINativeMethod gBaseEventQueueMethods[] = {
        {"nativeInitBaseEventQueue",
         "(JLjava/lang/ref/WeakReference;Landroid/os/MessageQueue;Ljava/lang/String;ILjava/lang/"
         "String;Ljava/lang/String;)J",
         (void *)nativeInitSensorEventQueue},

        {"nativeEnableSensor", "(JIII)I", (void *)nativeEnableSensor},

        {"nativeDisableSensor", "(JI)I", (void *)nativeDisableSensor},

        {"nativeDestroySensorEventQueue", "(J)V", (void *)nativeDestroySensorEventQueue},

        {"nativeFlushSensor", "(J)I", (void *)nativeFlushSensor},

        {"nativeInjectSensorData", "(JI[FIJ)I", (void *)nativeInjectSensorData},
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
