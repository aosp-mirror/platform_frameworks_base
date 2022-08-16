/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "UsbMidiDeviceJNI"
#define LOG_NDEBUG 0
#include "utils/Log.h"

#include "jni.h"
#include <nativehelper/JNIPlatformHelp.h>
#include <nativehelper/ScopedLocalRef.h>
#include "android_runtime/AndroidRuntime.h"
#include "android_runtime/Log.h"

#include <asm/byteorder.h>
#include <errno.h>
#include <fcntl.h>
#include <sound/asound.h>
#include <stdio.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/types.h>

namespace android
{

static jclass sFileDescriptorClass;
static jfieldID sPipeFDField;

// This function returns an array of integers, each representing a file descriptor.
// The will be in the order of inputs then outputs.
// The last input fd will be for a file descriptor that simply allows Os.poll() to keep working.
// For example, if numInputs is 2 and numOutputs is 1, the resulting fds are as follows:
// 1. Input O_RDONLY file descriptor
// 2. Special input file descriptor to block the input thread
// 3. Output O_WRONLY file descriptor
static jobjectArray android_server_UsbMidiDevice_open(JNIEnv *env, jobject thiz, jint card,
                                                      jint device, jint numInputs,
                                                      jint numOutputs) {
    char    path[100];
    int fd;

    snprintf(path, sizeof(path), "/dev/snd/midiC%dD%d", card, device);

    ALOGD("Opening %d inputs and %d outputs", numInputs, numOutputs);

    jobjectArray fds = env->NewObjectArray(numInputs + numOutputs, sFileDescriptorClass, NULL);
    if (!fds) {
        return NULL;
    }

    // open the path for the read pipes. The last one is special and used to
    // unblock Os.poll()
    for (int i = 0; i < numInputs - 1; i++) {
        fd = open(path, O_RDONLY);
        if (fd < 0) {
            ALOGE("open failed on %s for index %d", path, i);
            goto release_fds;
        }
        ScopedLocalRef<jobject> jifd(env, jniCreateFileDescriptor(env, fd));
        if (jifd.get() == NULL) {
            close(fd);
            goto release_fds;
        }
        env->SetObjectArrayElement(fds, i, jifd.get());
    }

    // open the path for the write pipes
    for (int i = 0; i < numOutputs; i++) {
        fd = open(path, O_WRONLY);
        if (fd < 0) {
            ALOGE("open failed on %s for index %d", path, i);
            goto release_fds;
        }
        ScopedLocalRef<jobject> jifd(env, jniCreateFileDescriptor(env, fd));
        if (jifd.get() == NULL) {
            close(fd);
            goto release_fds;
        }
        env->SetObjectArrayElement(fds, i + numInputs, jifd.get());
    }

    // create a pipe to use for unblocking our input thread. The caller should
    // set numInputs as 0 when there are zero real input threads.
    if (numInputs > 0) {
        int pipeFD[2];
        if (pipe(pipeFD) == -1) {
            ALOGE("pipe() failed, errno = %d", errno);
            goto release_fds;
        }

        ScopedLocalRef<jobject> jifd(env, jniCreateFileDescriptor(env, pipeFD[0]));
        if (jifd.get() == NULL) {
            close(pipeFD[0]);
            close(pipeFD[1]);
            goto release_fds;
        }

        // store as last input file descriptor
        env->SetObjectArrayElement(fds, numInputs - 1, jifd.get());
        // store our end of the pipe in mPipeFD
        env->SetIntField(thiz, sPipeFDField, pipeFD[1]);
    }
    return fds;

release_fds:
    for (int i = 0; i < numInputs + numOutputs; ++i) {
        ScopedLocalRef<jobject> jifd(env, env->GetObjectArrayElement(fds, i));
        if (jifd.get() != NULL) {
            int fd = jniGetFDFromFileDescriptor(env, jifd.get());
            close(fd);
        }
    }
    return NULL;
}

static void
android_server_UsbMidiDevice_close(JNIEnv *env, jobject thiz, jobjectArray fds)
{
    // write to mPipeFD to unblock input thread
    jint pipeFD = env->GetIntField(thiz, sPipeFDField);
    write(pipeFD, &pipeFD, sizeof(pipeFD));
    close(pipeFD);
    env->SetIntField(thiz, sPipeFDField, -1);

    int count = env->GetArrayLength(fds);
    for (int i = 0; i < count; i++) {
        jobject fd = env->GetObjectArrayElement(fds, i);
        close(jniGetFDFromFileDescriptor(env, fd));
    }
}

static JNINativeMethod method_table[] = {
        {"nativeOpen", "(IIII)[Ljava/io/FileDescriptor;",
         (void *)android_server_UsbMidiDevice_open},
        {"nativeClose", "([Ljava/io/FileDescriptor;)V", (void *)android_server_UsbMidiDevice_close},
};

int register_android_server_UsbMidiDevice(JNIEnv *env)
{
    jclass clazz = env->FindClass("java/io/FileDescriptor");
    if (clazz == NULL) {
        ALOGE("Can't find java/io/FileDescriptor");
        return -1;
    }
    sFileDescriptorClass = (jclass)env->NewGlobalRef(clazz);

    clazz = env->FindClass("com/android/server/usb/UsbMidiDevice");
    if (clazz == NULL) {
        ALOGE("Can't find com/android/server/usb/UsbMidiDevice");
        return -1;
    }
    sPipeFDField = env->GetFieldID(clazz, "mPipeFD", "I");
    if (sPipeFDField == NULL) {
        ALOGE("Can't find UsbMidiDevice.mPipeFD");
        return -1;
    }

    return jniRegisterNativeMethods(env, "com/android/server/usb/UsbMidiDevice",
            method_table, NELEM(method_table));
}

};
