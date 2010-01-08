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

#include <hardware/sensors.h>
#include <cutils/native_handle.h>

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
} gSensorOffsets;

/*
 * The method below are not thread-safe and not intended to be
 */

static sensors_module_t* sSensorModule = 0;
static sensors_data_device_t* sSensorDevice = 0;

static jint
sensors_module_init(JNIEnv *env, jclass clazz)
{
    int err = 0;
    sensors_module_t const* module;
    err = hw_get_module(SENSORS_HARDWARE_MODULE_ID, (const hw_module_t **)&module);
    if (err == 0)
        sSensorModule = (sensors_module_t*)module;
    return err;
}

static jint
sensors_module_get_next_sensor(JNIEnv *env, jobject clazz, jobject sensor, jint next)
{
    if (sSensorModule == NULL)
        return 0;

    SensorOffsets& sensorOffsets = gSensorOffsets;
    const struct sensor_t* list;
    int count = sSensorModule->get_sensors_list(sSensorModule, &list);
    if (next >= count)
        return -1;
    
    list += next;

    jstring name = env->NewStringUTF(list->name);
    jstring vendor = env->NewStringUTF(list->vendor);
    env->SetObjectField(sensor, sensorOffsets.name,      name);
    env->SetObjectField(sensor, sensorOffsets.vendor,    vendor);
    env->SetIntField(sensor, sensorOffsets.version,      list->version);
    env->SetIntField(sensor, sensorOffsets.handle,       list->handle);
    env->SetIntField(sensor, sensorOffsets.type,         list->type);
    env->SetFloatField(sensor, sensorOffsets.range,      list->maxRange);
    env->SetFloatField(sensor, sensorOffsets.resolution, list->resolution);
    env->SetFloatField(sensor, sensorOffsets.power,      list->power);
    
    next++;
    return next<count ? next : 0;
}

//----------------------------------------------------------------------------
static jint
sensors_data_init(JNIEnv *env, jclass clazz)
{
    if (sSensorModule == NULL)
        return -1;
    int err = sensors_data_open(&sSensorModule->common, &sSensorDevice);
    return err;
}

static jint
sensors_data_uninit(JNIEnv *env, jclass clazz)
{
    int err = 0;
    if (sSensorDevice) {
        err = sensors_data_close(sSensorDevice);
        if (err == 0)
            sSensorDevice = 0;
    }
    return err;
}

static jint
sensors_data_open(JNIEnv *env, jclass clazz, jobjectArray fdArray, jintArray intArray)
{
    jclass FileDescriptor = env->FindClass("java/io/FileDescriptor");
    jfieldID fieldOffset = env->GetFieldID(FileDescriptor, "descriptor", "I");
    int numFds = (fdArray ? env->GetArrayLength(fdArray) : 0);
    int numInts = (intArray ? env->GetArrayLength(intArray) : 0);
    native_handle_t* handle = native_handle_create(numFds, numInts);
    int offset = 0;

    for (int i = 0; i < numFds; i++) {
        jobject fdo = env->GetObjectArrayElement(fdArray, i);
        if (fdo) {
            handle->data[offset++] = env->GetIntField(fdo, fieldOffset);
        } else {
            handle->data[offset++] = -1;
        }
    }
    if (numInts > 0) {
        jint* ints = env->GetIntArrayElements(intArray, 0);
        for (int i = 0; i < numInts; i++) {
            handle->data[offset++] = ints[i];
        }
        env->ReleaseIntArrayElements(intArray, ints, 0);
    }

    // doesn't take ownership of the native handle
    return sSensorDevice->data_open(sSensorDevice, handle);
}

static jint
sensors_data_close(JNIEnv *env, jclass clazz)
{
    return sSensorDevice->data_close(sSensorDevice);
}

static jint
sensors_data_poll(JNIEnv *env, jclass clazz, 
        jfloatArray values, jintArray status, jlongArray timestamp)
{
    sensors_data_t data;
    int res = sSensorDevice->poll(sSensorDevice, &data);
    if (res >= 0) {
        jint accuracy = data.vector.status;
        env->SetFloatArrayRegion(values, 0, 3, data.vector.v);
        env->SetIntArrayRegion(status, 0, 1, &accuracy);
        env->SetLongArrayRegion(timestamp, 0, 1, &data.time);
    }
    return res;
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
}

static JNINativeMethod gMethods[] = {
    {"nativeClassInit", "()V",              (void*)nativeClassInit },
    {"sensors_module_init","()I",           (void*)sensors_module_init },
    {"sensors_module_get_next_sensor","(Landroid/hardware/Sensor;I)I",
                                            (void*)sensors_module_get_next_sensor },
    {"sensors_data_init", "()I",            (void*)sensors_data_init },
    {"sensors_data_uninit", "()I",          (void*)sensors_data_uninit },
    {"sensors_data_open",  "([Ljava/io/FileDescriptor;[I)I",  (void*)sensors_data_open },
    {"sensors_data_close", "()I",           (void*)sensors_data_close },
    {"sensors_data_poll",  "([F[I[J)I",     (void*)sensors_data_poll },
};

}; // namespace android

using namespace android;

int register_android_hardware_SensorManager(JNIEnv *env)
{
    return jniRegisterNativeMethods(env, "android/hardware/SensorManager",
            gMethods, NELEM(gMethods));
}
