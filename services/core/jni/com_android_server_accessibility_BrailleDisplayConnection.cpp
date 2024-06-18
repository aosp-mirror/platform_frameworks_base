/*
 * Copyright (C) 2024 The Android Open Source Project
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

#include <core_jni_helpers.h>
#include <jni.h>
#include <linux/hidraw.h>
#include <linux/input.h>
#include <nativehelper/JNIHelp.h>
#include <sys/ioctl.h>

/*
 * This file defines simple wrappers around the kernel UAPI HIDRAW driver's ioctl() commands.
 * See kernel example samples/hidraw/hid-example.c
 *
 * All methods expect an open file descriptor int from Java.
 */

namespace android {

namespace {

// Max size we allow for the result from HIDIOCGRAWUNIQ (Bluetooth address or USB serial number).
// Copied from linux/hid.h struct hid_device->uniq char array size; the ioctl implementation
// writes at most this many bytes to the provided buffer.
constexpr int UNIQ_SIZE_MAX = 64;

} // anonymous namespace

static jint com_android_server_accessibility_BrailleDisplayConnection_getHidrawDescSize(
        JNIEnv* env, jclass /*clazz*/, int fd) {
    int size = 0;
    if (ioctl(fd, HIDIOCGRDESCSIZE, &size) < 0) {
        return -1;
    }
    return size;
}

static jbyteArray com_android_server_accessibility_BrailleDisplayConnection_getHidrawDesc(
        JNIEnv* env, jclass /*clazz*/, int fd, int descSize) {
    struct hidraw_report_descriptor desc;
    desc.size = descSize;
    if (ioctl(fd, HIDIOCGRDESC, &desc) < 0) {
        return nullptr;
    }
    jbyteArray result = env->NewByteArray(descSize);
    if (result != nullptr) {
        env->SetByteArrayRegion(result, 0, descSize, (jbyte*)desc.value);
    }
    // Local ref is not deleted because it is returned to Java
    return result;
}

static jstring com_android_server_accessibility_BrailleDisplayConnection_getHidrawUniq(
        JNIEnv* env, jclass /*clazz*/, int fd) {
    char buf[UNIQ_SIZE_MAX];
    if (ioctl(fd, HIDIOCGRAWUNIQ(UNIQ_SIZE_MAX), buf) < 0) {
        return nullptr;
    }
    // Local ref is not deleted because it is returned to Java
    return env->NewStringUTF(buf);
}

static jint com_android_server_accessibility_BrailleDisplayConnection_getHidrawBusType(
        JNIEnv* env, jclass /*clazz*/, int fd) {
    struct hidraw_devinfo info;
    if (ioctl(fd, HIDIOCGRAWINFO, &info) < 0) {
        return -1;
    }
    return info.bustype;
}

static const JNINativeMethod gMethods[] = {
        {"nativeGetHidrawDescSize", "(I)I",
         (void*)com_android_server_accessibility_BrailleDisplayConnection_getHidrawDescSize},
        {"nativeGetHidrawDesc", "(II)[B",
         (void*)com_android_server_accessibility_BrailleDisplayConnection_getHidrawDesc},
        {"nativeGetHidrawUniq", "(I)Ljava/lang/String;",
         (void*)com_android_server_accessibility_BrailleDisplayConnection_getHidrawUniq},
        {"nativeGetHidrawBusType", "(I)I",
         (void*)com_android_server_accessibility_BrailleDisplayConnection_getHidrawBusType},
};

int register_com_android_server_accessibility_BrailleDisplayConnection(JNIEnv* env) {
    return RegisterMethodsOrDie(env, "com/android/server/accessibility/BrailleDisplayConnection",
                                gMethods, NELEM(gMethods));
}

}; // namespace android
