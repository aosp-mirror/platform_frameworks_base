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

static jint
android_init(JNIEnv *env, jclass clazz)
{
    return sensors_control_init();
}

static jobject
android_open(JNIEnv *env, jclass clazz)
{
    int fd = sensors_control_open();
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
    uint32_t active = sensors_control_activate(activate ? sensor : 0, sensor);
    return (activate && !active) ? false : true;
}

static jint
android_set_delay(JNIEnv *env, jclass clazz, jint ms)
{
    return sensors_control_delay(ms);
}

static JNINativeMethod gMethods[] = {
    {"_sensors_control_init",     "()I",   (void*) android_init },
    {"_sensors_control_open",     "()Landroid/os/ParcelFileDescriptor;",  (void*) android_open },
    {"_sensors_control_activate", "(IZ)Z", (void*) android_activate },
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
