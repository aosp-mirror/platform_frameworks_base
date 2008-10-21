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

#define LOG_TAG "Sensors"

#include <hardware/sensors.h>

#include "jni.h"
#include "JNIHelp.h"


namespace android {

/*
 * The method below are not thread-safe and not intended to be
 */

static jint
android_data_open(JNIEnv *env, jclass clazz, jobject fdo)
{
    jclass FileDescriptor = env->FindClass("java/io/FileDescriptor");
    jfieldID offset = env->GetFieldID(FileDescriptor, "descriptor", "I");
    int fd = env->GetIntField(fdo, offset);
    return sensors_data_open(fd); // doesn't take ownership of fd
}

static jint
android_data_close(JNIEnv *env, jclass clazz)
{
    return sensors_data_close();
}

static jint
android_data_poll(JNIEnv *env, jclass clazz, jfloatArray values, jint sensors)
{
    sensors_data_t data;
    int res = sensors_data_poll(&data, sensors);
    if (res) {
        env->SetFloatArrayRegion(values, 0, 3, data.vector.v);
        // return the sensor's number
        res = 31 - __builtin_clz(res);
        // and its status in the top 4 bits
        res |= data.vector.status << 28;
    }
    return res;
}

static jint
android_data_get_sensors(JNIEnv *env, jclass clazz)
{
    return sensors_data_get_sensors();
}

static JNINativeMethod gMethods[] = {
    {"_sensors_data_open",  "(Ljava/io/FileDescriptor;)I",  (void*) android_data_open },
    {"_sensors_data_close", "()I",   (void*) android_data_close },
    {"_sensors_data_poll",  "([FI)I", (void*) android_data_poll },
    {"_sensors_data_get_sensors","()I",   (void*) android_data_get_sensors },
};

}; // namespace android

using namespace android;

int register_android_hardware_SensorManager(JNIEnv *env)
{
    return jniRegisterNativeMethods(env, "android/hardware/SensorManager",
            gMethods, NELEM(gMethods));
}
