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
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"
#include "android_runtime/Log.h"

#include <stdio.h>
#include <errno.h>
#include <asm/byteorder.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <sound/asound.h>

namespace android
{

static jclass sFileDescriptorClass;
static jfieldID sPipeFDField;

static jint
android_server_UsbMidiDevice_get_subdevice_count(JNIEnv *env, jobject /* thiz */,
        jint card, jint device)
{
    char    path[100];
    int     fd;
    const   int kMaxRetries = 10;
    const   int kSleepMicroseconds = 2000;

    snprintf(path, sizeof(path), "/dev/snd/controlC%d", card);
    // This control device may not have been created yet. So we should
    // try to open it several times to prevent intermittent failure
    // from a race condition.
    int retryCounter = 0;
    while ((fd = open(path, O_RDWR)) < 0) {
        if (++retryCounter > kMaxRetries) {
            ALOGE("timed out after %d tries, could not open %s", retryCounter, path);
            return 0;
        } else {
            ALOGW("attempt #%d, could not open %s", retryCounter, path);
            // Increase the sleep interval each time.
            // 10 retries will total 2 * sum(1..10) = 110 milliseconds.
            // Typically the device should be ready in 5-10 milliseconds.
            usleep(kSleepMicroseconds * retryCounter);
        }
    }

    struct snd_rawmidi_info info;
    memset(&info, 0, sizeof(info));
    info.device = device;
    int ret = ioctl(fd, SNDRV_CTL_IOCTL_RAWMIDI_INFO, &info);
    close(fd);

    if (ret < 0) {
        ALOGE("SNDRV_CTL_IOCTL_RAWMIDI_INFO failed, errno: %d path: %s", errno, path);
        return -1;
    }

    ALOGD("subdevices_count: %d", info.subdevices_count);
    return info.subdevices_count;
}

static jobjectArray
android_server_UsbMidiDevice_open(JNIEnv *env, jobject thiz, jint card, jint device,
        jint subdevice_count)
{
    char    path[100];

    snprintf(path, sizeof(path), "/dev/snd/midiC%dD%d", card, device);

    // allocate one extra file descriptor for close pipe
    jobjectArray fds = env->NewObjectArray(subdevice_count + 1, sFileDescriptorClass, NULL);
    if (!fds) {
        return NULL;
    }

    // to support multiple subdevices we open the same file multiple times
    for (int i = 0; i < subdevice_count; i++) {
        int fd = open(path, O_RDWR);
        if (fd < 0) {
            ALOGE("open failed on %s for index %d", path, i);
            return NULL;
        }

        jobject fileDescriptor = jniCreateFileDescriptor(env, fd);
        env->SetObjectArrayElement(fds, i, fileDescriptor);
        env->DeleteLocalRef(fileDescriptor);
    }

    // create a pipe to use for unblocking our input thread
    int pipeFD[2];
    pipe(pipeFD);
    jobject fileDescriptor = jniCreateFileDescriptor(env, pipeFD[0]);
    env->SetObjectArrayElement(fds, subdevice_count, fileDescriptor);
    env->DeleteLocalRef(fileDescriptor);
    // store our end of the pipe in mPipeFD
    env->SetIntField(thiz, sPipeFDField, pipeFD[1]);

    return fds;
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
    { "nativeGetSubdeviceCount", "(II)I", (void*)android_server_UsbMidiDevice_get_subdevice_count },
    { "nativeOpen", "(III)[Ljava/io/FileDescriptor;", (void*)android_server_UsbMidiDevice_open },
    { "nativeClose", "([Ljava/io/FileDescriptor;)V", (void*)android_server_UsbMidiDevice_close },
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
