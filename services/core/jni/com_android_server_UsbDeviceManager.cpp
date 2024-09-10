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

#define LOG_TAG "UsbDeviceManagerJNI"
#include <android-base/properties.h>
#include <android-base/unique_fd.h>
#include <core_jni_helpers.h>
#include <fcntl.h>
#include <linux/usb/f_accessory.h>
#include <nativehelper/JNIPlatformHelp.h>
#include <nativehelper/ScopedUtfChars.h>
#include <stdio.h>
#include <sys/epoll.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/types.h>

#include <thread>

#include "MtpDescriptors.h"
#include "android_runtime/AndroidRuntime.h"
#include "android_runtime/Log.h"
#include "jni.h"
#include "utils/Log.h"

#define DRIVER_NAME "/dev/usb_accessory"
#define EPOLL_MAX_EVENTS 4
#define USB_STATE_MAX_LEN 20

namespace android
{

static JavaVM *gvm = nullptr;
static jmethodID gUpdateGadgetStateMethod;

static struct parcel_file_descriptor_offsets_t
{
    jclass mClass;
    jmethodID mConstructor;
} gParcelFileDescriptorOffsets;

/*
 * NativeGadgetMonitorThread starts a new thread to monitor udc state by epoll,
 * convert and update the state to UsbDeviceManager.
 */
class NativeGadgetMonitorThread {
    android::base::unique_fd mMonitorFd;
    int mPipefd[2];
    std::thread mThread;
    jobject mCallbackObj;
    std::string mGadgetState;

    void handleStateUpdate(const char *state) {
        JNIEnv *env = AndroidRuntime::getJNIEnv();
        std::string gadgetState;

        if (!std::strcmp(state, "not attached\n")) {
            gadgetState = "DISCONNECTED";
        } else if (!std::strcmp(state, "attached\n") || !std::strcmp(state, "powered\n") ||
                   !std::strcmp(state, "default\n") || !std::strcmp(state, "addressed\n")) {
            gadgetState = "CONNECTED";
        } else if (!std::strcmp(state, "configured\n")) {
            gadgetState = "CONFIGURED";
        } else if (!std::strcmp(state, "suspended\n")) {
            return;
        } else {
            ALOGE("Unknown gadget state %s", state);
            return;
        }

        if (mGadgetState.compare(gadgetState)) {
            mGadgetState = gadgetState;
            jstring obj = env->NewStringUTF(gadgetState.c_str());
            env->CallVoidMethod(mCallbackObj, gUpdateGadgetStateMethod, obj);
        }
    }

    int setupEpoll(android::base::unique_fd &epollFd) {
        struct epoll_event ev;

        ev.data.fd = mMonitorFd.get();
        ev.events = EPOLLPRI;
        if (epoll_ctl(epollFd.get(), EPOLL_CTL_ADD, mMonitorFd.get(), &ev) != 0) {
            ALOGE("epoll_ctl failed for monitor fd; errno=%d", errno);
            return errno;
        }

        ev.data.fd = mPipefd[0];
        ev.events = EPOLLIN;
        if (epoll_ctl(epollFd.get(), EPOLL_CTL_ADD, mPipefd[0], &ev) != 0) {
            ALOGE("epoll_ctl failed for pipe fd; errno=%d", errno);
            return errno;
        }

        return 0;
    }

    void monitorLoop() {
        android::base::unique_fd epollFd(epoll_create(EPOLL_MAX_EVENTS));
        if (epollFd.get() == -1) {
            ALOGE("epoll_create failed; errno=%d", errno);
            return;
        }
        if (setupEpoll(epollFd) != 0) return;

        JNIEnv *env = nullptr;
        JavaVMAttachArgs aargs = {JNI_VERSION_1_4, "NativeGadgetMonitorThread", nullptr};
        if (gvm->AttachCurrentThread(&env, &aargs) != JNI_OK || env == nullptr) {
            ALOGE("Couldn't attach thread");
            return;
        }

        struct epoll_event events[EPOLL_MAX_EVENTS];
        int nevents = 0;
        while (true) {
            nevents = epoll_wait(epollFd.get(), events, EPOLL_MAX_EVENTS, -1);
            if (nevents < 0) {
                ALOGE("usb epoll_wait failed; errno=%d", errno);
                continue;
            }
            for (int i = 0; i < nevents; ++i) {
                int fd = events[i].data.fd;
                if (fd == mPipefd[0]) {
                    goto exit;
                } else if (fd == mMonitorFd.get()) {
                    char state[USB_STATE_MAX_LEN] = {0};
                    lseek(fd, 0, SEEK_SET);
                    read(fd, &state, USB_STATE_MAX_LEN);
                    handleStateUpdate(state);
                }
            }
        }

    exit:
        auto res = gvm->DetachCurrentThread();
        ALOGE_IF(res != JNI_OK, "Couldn't detach thread");
        return;
    }

    void stop() {
        if (mThread.joinable()) {
            int c = 'q';
            write(mPipefd[1], &c, 1);
            mThread.join();
        }
    }

    DISALLOW_COPY_AND_ASSIGN(NativeGadgetMonitorThread);

public:
    explicit NativeGadgetMonitorThread(jobject obj, android::base::unique_fd monitorFd)
          : mMonitorFd(std::move(monitorFd)), mGadgetState("") {
        mCallbackObj = AndroidRuntime::getJNIEnv()->NewGlobalRef(obj);
        pipe(mPipefd);
        mThread = std::thread(&NativeGadgetMonitorThread::monitorLoop, this);
    }

    ~NativeGadgetMonitorThread() {
        stop();
        close(mPipefd[0]);
        close(mPipefd[1]);
        AndroidRuntime::getJNIEnv()->DeleteGlobalRef(mCallbackObj);
    }
};
static std::unique_ptr<NativeGadgetMonitorThread> sGadgetMonitorThread;

static void set_accessory_string(JNIEnv *env, int fd, int cmd, jobjectArray strArray, int index)
{
    char buffer[256];

    buffer[0] = 0;
    ioctl(fd, cmd, buffer);
    if (buffer[0]) {
        jstring obj = env->NewStringUTF(buffer);
        env->SetObjectArrayElement(strArray, index, obj);
        env->DeleteLocalRef(obj);
    }
}


static jobjectArray android_server_UsbDeviceManager_getAccessoryStrings(JNIEnv *env,
                                                                        jobject /* thiz */)
{
    int fd = open(DRIVER_NAME, O_RDWR);
    if (fd < 0) {
        ALOGE("could not open %s", DRIVER_NAME);
        return NULL;
    }
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray strArray = env->NewObjectArray(6, stringClass, NULL);
    if (!strArray) goto out;
    set_accessory_string(env, fd, ACCESSORY_GET_STRING_MANUFACTURER, strArray, 0);
    set_accessory_string(env, fd, ACCESSORY_GET_STRING_MODEL, strArray, 1);
    set_accessory_string(env, fd, ACCESSORY_GET_STRING_DESCRIPTION, strArray, 2);
    set_accessory_string(env, fd, ACCESSORY_GET_STRING_VERSION, strArray, 3);
    set_accessory_string(env, fd, ACCESSORY_GET_STRING_URI, strArray, 4);
    set_accessory_string(env, fd, ACCESSORY_GET_STRING_SERIAL, strArray, 5);

out:
    close(fd);
    return strArray;
}

static jobject android_server_UsbDeviceManager_openAccessory(JNIEnv *env, jobject /* thiz */)
{
    int fd = open(DRIVER_NAME, O_RDWR);
    if (fd < 0) {
        ALOGE("could not open %s", DRIVER_NAME);
        return NULL;
    }
    jobject fileDescriptor = jniCreateFileDescriptor(env, fd);
    if (fileDescriptor == NULL) {
        close(fd);
        return NULL;
    }
    return env->NewObject(gParcelFileDescriptorOffsets.mClass,
        gParcelFileDescriptorOffsets.mConstructor, fileDescriptor);
}

static jboolean android_server_UsbDeviceManager_isStartRequested(JNIEnv* /* env */,
                                                                 jobject /* thiz */)
{
    int fd = open(DRIVER_NAME, O_RDWR);
    if (fd < 0) {
        ALOGE("could not open %s", DRIVER_NAME);
        return false;
    }
    int result = ioctl(fd, ACCESSORY_IS_START_REQUESTED);
    close(fd);
    return (result == 1);
}

static jobject android_server_UsbDeviceManager_openControl(JNIEnv *env, jobject /* thiz */, jstring jFunction) {
    ScopedUtfChars function(env, jFunction);
    bool ptp = false;
    int fd = -1;
    if (!strcmp(function.c_str(), "ptp")) {
        ptp = true;
    }
    if (!strcmp(function.c_str(), "mtp") || ptp) {
        fd = TEMP_FAILURE_RETRY(open(ptp ? FFS_PTP_EP0 : FFS_MTP_EP0, O_RDWR));
        if (fd < 0) {
            ALOGE("could not open control for %s %s", function.c_str(), strerror(errno));
            return NULL;
        }
        if (!writeDescriptors(fd, ptp)) {
            close(fd);
            return NULL;
        }
    }

    jobject jifd = jniCreateFileDescriptor(env, fd);
    if (jifd == NULL) {
        // OutOfMemoryError will be pending.
        close(fd);
    }
    return jifd;
}

static jboolean android_server_UsbDeviceManager_startGadgetMonitor(JNIEnv *env, jobject thiz,
                                                                   jstring jUdcName) {
    std::string filePath;
    ScopedUtfChars udcName(env, jUdcName);

    filePath = "/sys/class/udc/" + std::string(udcName.c_str()) + "/state";
    android::base::unique_fd fd(open(filePath.c_str(), O_RDONLY));

    if (fd.get() == -1) {
        ALOGE("Cannot open %s", filePath.c_str());
        return JNI_FALSE;
    }

    ALOGI("Start monitoring %s", filePath.c_str());
    sGadgetMonitorThread.reset(new NativeGadgetMonitorThread(thiz, std::move(fd)));

    return JNI_TRUE;
}

static void android_server_UsbDeviceManager_stopGadgetMonitor(JNIEnv *env, jobject /* thiz */) {
    sGadgetMonitorThread.reset();
    return;
}

static jstring android_server_UsbDeviceManager_waitAndGetProperty(JNIEnv *env, jobject thiz,
                                                                  jstring jPropName) {
    ScopedUtfChars propName(env, jPropName);
    std::string propValue;

    while (!android::base::WaitForPropertyCreation(propName.c_str()));
    propValue = android::base::GetProperty(propName.c_str(), "" /* default */);

    return env->NewStringUTF(propValue.c_str());
}

static const JNINativeMethod method_table[] = {
        {"nativeGetAccessoryStrings", "()[Ljava/lang/String;",
         (void *)android_server_UsbDeviceManager_getAccessoryStrings},
        {"nativeOpenAccessory", "()Landroid/os/ParcelFileDescriptor;",
         (void *)android_server_UsbDeviceManager_openAccessory},
        {"nativeIsStartRequested", "()Z", (void *)android_server_UsbDeviceManager_isStartRequested},
        {"nativeOpenControl", "(Ljava/lang/String;)Ljava/io/FileDescriptor;",
         (void *)android_server_UsbDeviceManager_openControl},
        {"nativeStartGadgetMonitor", "(Ljava/lang/String;)Z",
         (void *)android_server_UsbDeviceManager_startGadgetMonitor},
        {"nativeStopGadgetMonitor", "()V",
         (void *)android_server_UsbDeviceManager_stopGadgetMonitor},
        {"nativeWaitAndGetProperty", "(Ljava/lang/String;)Ljava/lang/String;",
         (void *)android_server_UsbDeviceManager_waitAndGetProperty},
};

int register_android_server_UsbDeviceManager(JavaVM *vm, JNIEnv *env) {
    gvm = vm;

    jclass clazz = env->FindClass("com/android/server/usb/UsbDeviceManager");
    if (clazz == NULL) {
        ALOGE("Can't find com/android/server/usb/UsbDeviceManager");
        return -1;
    }

    gUpdateGadgetStateMethod =
            GetMethodIDOrDie(env, clazz, "updateGadgetState", "(Ljava/lang/String;)V");

    clazz = env->FindClass("android/os/ParcelFileDescriptor");
    LOG_FATAL_IF(clazz == NULL, "Unable to find class android.os.ParcelFileDescriptor");
    gParcelFileDescriptorOffsets.mClass = (jclass) env->NewGlobalRef(clazz);
    gParcelFileDescriptorOffsets.mConstructor = env->GetMethodID(clazz, "<init>", "(Ljava/io/FileDescriptor;)V");
    LOG_FATAL_IF(gParcelFileDescriptorOffsets.mConstructor == NULL,
                 "Unable to find constructor for android.os.ParcelFileDescriptor");

    return jniRegisterNativeMethods(env, "com/android/server/usb/UsbDeviceManager",
            method_table, NELEM(method_table));
}
};
