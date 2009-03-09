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

static struct file_descriptor_offsets_t
{
    jclass mClass;
    jmethodID mConstructor;
    jfieldID mDescriptor;
} gFileDescriptorOffsets;

static struct parcel_file_descriptor_offsets_t
{
    jclass mClass;
    jmethodID mConstructor;
} gParcelFileDescriptorOffsets;

/*
 * The method below are not thread-safe and not intended to be 
 */

static sensors_control_device_t* sSensorDevice = 0;

static jint
android_init(JNIEnv *env, jclass clazz)
{
    sensors_module_t* module;
    if (hw_get_module(SENSORS_HARDWARE_MODULE_ID, (const hw_module_t**)&module) == 0) {
        if (sensors_control_open(&module->common, &sSensorDevice) == 0) {
            const struct sensor_t* list;
            int count = module->get_sensors_list(module, &list);
            return count;
        }
    }
    return 0;
}

static jobject
android_open(JNIEnv *env, jclass clazz)
{
    int fd = sSensorDevice->open_data_source(sSensorDevice);
    // new FileDescriptor()
    jobject filedescriptor = env->NewObject(
            gFileDescriptorOffsets.mClass, 
            gFileDescriptorOffsets.mConstructor);
    
    if (filedescriptor != NULL) {
        env->SetIntField(filedescriptor, gFileDescriptorOffsets.mDescriptor, fd);
        // new ParcelFileDescriptor()
        return env->NewObject(gParcelFileDescriptorOffsets.mClass,
                gParcelFileDescriptorOffsets.mConstructor, 
                filedescriptor);
    }
    close(fd);
    return NULL;
}

static jboolean
android_activate(JNIEnv *env, jclass clazz, jint sensor, jboolean activate)
{
    int active = sSensorDevice->activate(sSensorDevice, sensor, activate);
    return (active<0) ? false : true;
}

static jint
android_set_delay(JNIEnv *env, jclass clazz, jint ms)
{
    return sSensorDevice->set_delay(sSensorDevice, ms);
}

static jint
android_data_wake(JNIEnv *env, jclass clazz)
{
    int res = sSensorDevice->wake(sSensorDevice);
    return res;
}


static JNINativeMethod gMethods[] = {
    {"_sensors_control_init",     "()I",   (void*) android_init },
    {"_sensors_control_open",     "()Landroid/os/ParcelFileDescriptor;",  (void*) android_open },
    {"_sensors_control_activate", "(IZ)Z", (void*) android_activate },
    {"_sensors_control_wake",     "()I", (void*) android_data_wake },
    {"_sensors_control_set_delay","(I)I", (void*) android_set_delay },
};

int register_android_server_SensorService(JNIEnv *env)
{
    jclass clazz;

    clazz = env->FindClass("java/io/FileDescriptor");
    gFileDescriptorOffsets.mClass = (jclass)env->NewGlobalRef(clazz);
    gFileDescriptorOffsets.mConstructor = env->GetMethodID(clazz, "<init>", "()V");
    gFileDescriptorOffsets.mDescriptor = env->GetFieldID(clazz, "descriptor", "I");

    clazz = env->FindClass("android/os/ParcelFileDescriptor");
    gParcelFileDescriptorOffsets.mClass = (jclass) env->NewGlobalRef(clazz);
    gParcelFileDescriptorOffsets.mConstructor = env->GetMethodID(clazz, "<init>", "(Ljava/io/FileDescriptor;)V");

    return jniRegisterNativeMethods(env, "com/android/server/SensorService",
            gMethods, NELEM(gMethods));
}

}; // namespace android
