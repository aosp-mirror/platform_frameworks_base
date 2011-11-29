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

#include "utils/Log.h"

#include <gui/Sensor.h>
#include <gui/SensorManager.h>
#include <gui/SensorEventQueue.h>

#include "jni.h"
#include "JNIHelp.h"


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
} gSensorOffsets;

/*
 * The method below are not thread-safe and not intended to be
 */


static jint
sensors_module_init(JNIEnv *env, jclass clazz)
{
    SensorManager::getInstance();
    return 0;
}

static jint
sensors_module_get_next_sensor(JNIEnv *env, jobject clazz, jobject sensor, jint next)
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
    env->SetObjectField(sensor, sensorOffsets.name,      name);
    env->SetObjectField(sensor, sensorOffsets.vendor,    vendor);
    env->SetIntField(sensor, sensorOffsets.version,      1);
    env->SetIntField(sensor, sensorOffsets.handle,       list->getHandle());
    env->SetIntField(sensor, sensorOffsets.type,         list->getType());
    env->SetFloatField(sensor, sensorOffsets.range,      list->getMaxValue());
    env->SetFloatField(sensor, sensorOffsets.resolution, list->getResolution());
    env->SetFloatField(sensor, sensorOffsets.power,      list->getPowerUsage());
    env->SetIntField(sensor, sensorOffsets.minDelay,     list->getMinDelay());
    
    next++;
    return size_t(next) < count ? next : 0;
}

//----------------------------------------------------------------------------
static jint
sensors_create_queue(JNIEnv *env, jclass clazz)
{
    SensorManager& mgr(SensorManager::getInstance());
    sp<SensorEventQueue> queue(mgr.createEventQueue());
    queue->incStrong(clazz);
    return reinterpret_cast<int>(queue.get());
}

static void
sensors_destroy_queue(JNIEnv *env, jclass clazz, jint nativeQueue)
{
    sp<SensorEventQueue> queue(reinterpret_cast<SensorEventQueue *>(nativeQueue));
    if (queue != 0) {
        queue->decStrong(clazz);
    }
}

static jboolean
sensors_enable_sensor(JNIEnv *env, jclass clazz,
        jint nativeQueue, jstring name, jint sensor, jint delay)
{
    sp<SensorEventQueue> queue(reinterpret_cast<SensorEventQueue *>(nativeQueue));
    if (queue == 0) return JNI_FALSE;
    status_t res;
    if (delay >= 0) {
        res = queue->enableSensor(sensor, delay);
    } else {
        res = queue->disableSensor(sensor);
    }
    return res == NO_ERROR ? true : false;
}

static jint
sensors_data_poll(JNIEnv *env, jclass clazz, jint nativeQueue,
        jfloatArray values, jintArray status, jlongArray timestamp)
{
    sp<SensorEventQueue> queue(reinterpret_cast<SensorEventQueue *>(nativeQueue));
    if (queue == 0) return -1;

    status_t res;
    ASensorEvent event;

    res = queue->read(&event, 1);
    if (res == 0) {
        res = queue->waitForEvent();
        if (res != NO_ERROR)
            return -1;
        res = queue->read(&event, 1);
    }
    if (res < 0)
        return -1;

    jint accuracy = event.vector.status;
    env->SetFloatArrayRegion(values, 0, 3, event.vector.v);
    env->SetIntArrayRegion(status, 0, 1, &accuracy);
    env->SetLongArrayRegion(timestamp, 0, 1, &event.timestamp);

    return event.sensor;
}

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
}

static JNINativeMethod gMethods[] = {
    {"nativeClassInit", "()V",              (void*)nativeClassInit },
    {"sensors_module_init","()I",           (void*)sensors_module_init },
    {"sensors_module_get_next_sensor","(Landroid/hardware/Sensor;I)I",
                                            (void*)sensors_module_get_next_sensor },

    {"sensors_create_queue",  "()I",        (void*)sensors_create_queue },
    {"sensors_destroy_queue", "(I)V",       (void*)sensors_destroy_queue },
    {"sensors_enable_sensor", "(ILjava/lang/String;II)Z",
                                            (void*)sensors_enable_sensor },

    {"sensors_data_poll",  "(I[F[I[J)I",     (void*)sensors_data_poll },
};

}; // namespace android

using namespace android;

int register_android_hardware_SensorManager(JNIEnv *env)
{
    return jniRegisterNativeMethods(env, "android/hardware/SensorManager",
            gMethods, NELEM(gMethods));
}
