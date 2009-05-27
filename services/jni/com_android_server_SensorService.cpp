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

#define LOG_TAG "SensorService"

#define LOG_NDEBUG 0
#include "utils/Log.h"

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

static struct bundle_descriptor_offsets_t
{
    jclass mClass;
    jmethodID mConstructor;
    jmethodID mPutIntArray;
    jmethodID mPutParcelableArray;
} gBundleOffsets;

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
    native_handle_t* handle = sSensorDevice->open_data_source(sSensorDevice);
    if (!handle) {
        return NULL;
    }

    // new Bundle()
    jobject bundle = env->NewObject(
            gBundleOffsets.mClass,
            gBundleOffsets.mConstructor);

    if (handle->numFds > 0) {
        jobjectArray fdArray = env->NewObjectArray(handle->numFds,
                gParcelFileDescriptorOffsets.mClass, NULL);
        for (int i = 0; i < handle->numFds; i++) {
            // new FileDescriptor()
            jobject fd = env->NewObject(gFileDescriptorOffsets.mClass,
                    gFileDescriptorOffsets.mConstructor);
            env->SetIntField(fd, gFileDescriptorOffsets.mDescriptor, handle->data[i]);
            // new ParcelFileDescriptor()
            jobject pfd = env->NewObject(gParcelFileDescriptorOffsets.mClass,
                    gParcelFileDescriptorOffsets.mConstructor, fd);
            env->SetObjectArrayElement(fdArray, i, pfd);
        }
        // bundle.putParcelableArray("fds", fdArray);
        env->CallVoidMethod(bundle, gBundleOffsets.mPutParcelableArray,
                env->NewStringUTF("fds"), fdArray);
    }

    if (handle->numInts > 0) {
        jintArray intArray = env->NewIntArray(handle->numInts);
        env->SetIntArrayRegion(intArray, 0, handle->numInts, &handle->data[handle->numInts]);
        // bundle.putIntArray("ints", intArray);
        env->CallVoidMethod(bundle, gBundleOffsets.mPutIntArray,
                env->NewStringUTF("ints"), intArray);
    }

    // delete the file handle, but don't close any file descriptors
    native_handle_delete(handle);
    return bundle;
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
    {"_sensors_control_open",     "()Landroid/os/Bundle;",  (void*) android_open },
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
    gParcelFileDescriptorOffsets.mConstructor = env->GetMethodID(clazz, "<init>",
            "(Ljava/io/FileDescriptor;)V");

    clazz = env->FindClass("android/os/Bundle");
    gBundleOffsets.mClass = (jclass) env->NewGlobalRef(clazz);
    gBundleOffsets.mConstructor = env->GetMethodID(clazz, "<init>", "()V");
    gBundleOffsets.mPutIntArray = env->GetMethodID(clazz, "putIntArray", "(Ljava/lang/String;[I)V");
    gBundleOffsets.mPutParcelableArray = env->GetMethodID(clazz, "putParcelableArray",
            "(Ljava/lang/String;[Landroid/os/Parcelable;)V");

    return jniRegisterNativeMethods(env, "com/android/server/SensorService",
            gMethods, NELEM(gMethods));
}

}; // namespace android
