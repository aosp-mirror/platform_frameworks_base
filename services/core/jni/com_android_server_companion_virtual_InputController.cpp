/*
 * Copyright (C) 2021 The Android Open Source Project
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

#define LOG_TAG "InputController"

#include <android-base/unique_fd.h>
#include <android/input.h>
#include <android/keycodes.h>
#include <errno.h>
#include <fcntl.h>
#include <input/Input.h>
#include <input/VirtualInputDevice.h>
#include <linux/uinput.h>
#include <math.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedUtfChars.h>
#include <utils/Log.h>

#include <map>
#include <set>
#include <string>

using android::base::unique_fd;

namespace android {

static constexpr jlong INVALID_PTR = 0;

enum class DeviceType {
    KEYBOARD,
    MOUSE,
    TOUCHSCREEN,
    DPAD,
};

static unique_fd invalidFd() {
    return unique_fd(-1);
}

/** Creates a new uinput device and assigns a file descriptor. */
static unique_fd openUinput(const char* readableName, jint vendorId, jint productId,
                            const char* phys, DeviceType deviceType, jint screenHeight,
                            jint screenWidth) {
    unique_fd fd(TEMP_FAILURE_RETRY(::open("/dev/uinput", O_WRONLY | O_NONBLOCK)));
    if (fd < 0) {
        ALOGE("Error creating uinput device: %s", strerror(errno));
        return invalidFd();
    }

    ioctl(fd, UI_SET_PHYS, phys);

    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_EVBIT, EV_SYN);
    switch (deviceType) {
        case DeviceType::DPAD:
            for (const auto& [_, keyCode] : VirtualDpad::DPAD_KEY_CODE_MAPPING) {
                ioctl(fd, UI_SET_KEYBIT, keyCode);
            }
            break;
        case DeviceType::KEYBOARD:
            for (const auto& [_, keyCode] : VirtualKeyboard::KEY_CODE_MAPPING) {
                ioctl(fd, UI_SET_KEYBIT, keyCode);
            }
            break;
        case DeviceType::MOUSE:
            ioctl(fd, UI_SET_EVBIT, EV_REL);
            ioctl(fd, UI_SET_KEYBIT, BTN_LEFT);
            ioctl(fd, UI_SET_KEYBIT, BTN_RIGHT);
            ioctl(fd, UI_SET_KEYBIT, BTN_MIDDLE);
            ioctl(fd, UI_SET_KEYBIT, BTN_BACK);
            ioctl(fd, UI_SET_KEYBIT, BTN_FORWARD);
            ioctl(fd, UI_SET_RELBIT, REL_X);
            ioctl(fd, UI_SET_RELBIT, REL_Y);
            ioctl(fd, UI_SET_RELBIT, REL_WHEEL);
            ioctl(fd, UI_SET_RELBIT, REL_HWHEEL);
            break;
        case DeviceType::TOUCHSCREEN:
            ioctl(fd, UI_SET_EVBIT, EV_ABS);
            ioctl(fd, UI_SET_KEYBIT, BTN_TOUCH);
            ioctl(fd, UI_SET_ABSBIT, ABS_MT_SLOT);
            ioctl(fd, UI_SET_ABSBIT, ABS_MT_POSITION_X);
            ioctl(fd, UI_SET_ABSBIT, ABS_MT_POSITION_Y);
            ioctl(fd, UI_SET_ABSBIT, ABS_MT_TRACKING_ID);
            ioctl(fd, UI_SET_ABSBIT, ABS_MT_TOOL_TYPE);
            ioctl(fd, UI_SET_ABSBIT, ABS_MT_TOUCH_MAJOR);
            ioctl(fd, UI_SET_ABSBIT, ABS_MT_PRESSURE);
            ioctl(fd, UI_SET_PROPBIT, INPUT_PROP_DIRECT);
    }

    int version;
    if (ioctl(fd, UI_GET_VERSION, &version) == 0 && version >= 5) {
        uinput_setup setup;
        memset(&setup, 0, sizeof(setup));
        strlcpy(setup.name, readableName, UINPUT_MAX_NAME_SIZE);
        setup.id.version = 1;
        setup.id.bustype = BUS_VIRTUAL;
        setup.id.vendor = vendorId;
        setup.id.product = productId;
        if (deviceType == DeviceType::TOUCHSCREEN) {
            uinput_abs_setup xAbsSetup;
            xAbsSetup.code = ABS_MT_POSITION_X;
            xAbsSetup.absinfo.maximum = screenWidth - 1;
            xAbsSetup.absinfo.minimum = 0;
            if (ioctl(fd, UI_ABS_SETUP, &xAbsSetup) != 0) {
                ALOGE("Error creating touchscreen uinput x axis: %s", strerror(errno));
                return invalidFd();
            }
            uinput_abs_setup yAbsSetup;
            yAbsSetup.code = ABS_MT_POSITION_Y;
            yAbsSetup.absinfo.maximum = screenHeight - 1;
            yAbsSetup.absinfo.minimum = 0;
            if (ioctl(fd, UI_ABS_SETUP, &yAbsSetup) != 0) {
                ALOGE("Error creating touchscreen uinput y axis: %s", strerror(errno));
                return invalidFd();
            }
            uinput_abs_setup majorAbsSetup;
            majorAbsSetup.code = ABS_MT_TOUCH_MAJOR;
            majorAbsSetup.absinfo.maximum = screenWidth - 1;
            majorAbsSetup.absinfo.minimum = 0;
            if (ioctl(fd, UI_ABS_SETUP, &majorAbsSetup) != 0) {
                ALOGE("Error creating touchscreen uinput major axis: %s", strerror(errno));
                return invalidFd();
            }
            uinput_abs_setup pressureAbsSetup;
            pressureAbsSetup.code = ABS_MT_PRESSURE;
            pressureAbsSetup.absinfo.maximum = 255;
            pressureAbsSetup.absinfo.minimum = 0;
            if (ioctl(fd, UI_ABS_SETUP, &pressureAbsSetup) != 0) {
                ALOGE("Error creating touchscreen uinput pressure axis: %s", strerror(errno));
                return invalidFd();
            }
            uinput_abs_setup slotAbsSetup;
            slotAbsSetup.code = ABS_MT_SLOT;
            slotAbsSetup.absinfo.maximum = MAX_POINTERS - 1;
            slotAbsSetup.absinfo.minimum = 0;
            if (ioctl(fd, UI_ABS_SETUP, &slotAbsSetup) != 0) {
                ALOGE("Error creating touchscreen uinput slots: %s", strerror(errno));
                return invalidFd();
            }
            uinput_abs_setup trackingIdAbsSetup;
            trackingIdAbsSetup.code = ABS_MT_TRACKING_ID;
            trackingIdAbsSetup.absinfo.maximum = MAX_POINTERS - 1;
            trackingIdAbsSetup.absinfo.minimum = 0;
            if (ioctl(fd, UI_ABS_SETUP, &trackingIdAbsSetup) != 0) {
                ALOGE("Error creating touchscreen uinput tracking ids: %s", strerror(errno));
                return invalidFd();
            }
        }
        if (ioctl(fd, UI_DEV_SETUP, &setup) != 0) {
            ALOGE("Error creating uinput device: %s", strerror(errno));
            return invalidFd();
        }
    } else {
        // UI_DEV_SETUP was not introduced until version 5. Try setting up manually.
        ALOGI("Falling back to version %d manual setup", version);
        uinput_user_dev fallback;
        memset(&fallback, 0, sizeof(fallback));
        strlcpy(fallback.name, readableName, UINPUT_MAX_NAME_SIZE);
        fallback.id.version = 1;
        fallback.id.bustype = BUS_VIRTUAL;
        fallback.id.vendor = vendorId;
        fallback.id.product = productId;
        if (deviceType == DeviceType::TOUCHSCREEN) {
            fallback.absmin[ABS_MT_POSITION_X] = 0;
            fallback.absmax[ABS_MT_POSITION_X] = screenWidth - 1;
            fallback.absmin[ABS_MT_POSITION_Y] = 0;
            fallback.absmax[ABS_MT_POSITION_Y] = screenHeight - 1;
            fallback.absmin[ABS_MT_TOUCH_MAJOR] = 0;
            fallback.absmax[ABS_MT_TOUCH_MAJOR] = screenWidth - 1;
            fallback.absmin[ABS_MT_PRESSURE] = 0;
            fallback.absmax[ABS_MT_PRESSURE] = 255;
        }
        if (TEMP_FAILURE_RETRY(write(fd, &fallback, sizeof(fallback))) != sizeof(fallback)) {
            ALOGE("Error creating uinput device: %s", strerror(errno));
            return invalidFd();
        }
    }

    if (ioctl(fd, UI_DEV_CREATE) != 0) {
        ALOGE("Error creating uinput device: %s", strerror(errno));
        return invalidFd();
    }

    return fd;
}

static unique_fd openUinputJni(JNIEnv* env, jstring name, jint vendorId, jint productId,
                               jstring phys, DeviceType deviceType, int screenHeight,
                               int screenWidth) {
    ScopedUtfChars readableName(env, name);
    ScopedUtfChars readablePhys(env, phys);
    return openUinput(readableName.c_str(), vendorId, productId, readablePhys.c_str(), deviceType,
                      screenHeight, screenWidth);
}

static jlong nativeOpenUinputDpad(JNIEnv* env, jobject thiz, jstring name, jint vendorId,
                                  jint productId, jstring phys) {
    auto fd = openUinputJni(env, name, vendorId, productId, phys, DeviceType::DPAD,
                            /* screenHeight= */ 0, /* screenWidth= */ 0);
    return fd.ok() ? reinterpret_cast<jlong>(new VirtualDpad(std::move(fd))) : INVALID_PTR;
}

static jlong nativeOpenUinputKeyboard(JNIEnv* env, jobject thiz, jstring name, jint vendorId,
                                      jint productId, jstring phys) {
    auto fd = openUinputJni(env, name, vendorId, productId, phys, DeviceType::KEYBOARD,
                            /* screenHeight= */ 0, /* screenWidth= */ 0);
    return fd.ok() ? reinterpret_cast<jlong>(new VirtualKeyboard(std::move(fd))) : INVALID_PTR;
}

static jlong nativeOpenUinputMouse(JNIEnv* env, jobject thiz, jstring name, jint vendorId,
                                   jint productId, jstring phys) {
    auto fd = openUinputJni(env, name, vendorId, productId, phys, DeviceType::MOUSE,
                            /* screenHeight= */ 0, /* screenWidth= */ 0);
    return fd.ok() ? reinterpret_cast<jlong>(new VirtualMouse(std::move(fd))) : INVALID_PTR;
}

static jlong nativeOpenUinputTouchscreen(JNIEnv* env, jobject thiz, jstring name, jint vendorId,
                                         jint productId, jstring phys, jint height, jint width) {
    auto fd = openUinputJni(env, name, vendorId, productId, phys, DeviceType::TOUCHSCREEN, height,
                            width);
    return fd.ok() ? reinterpret_cast<jlong>(new VirtualTouchscreen(std::move(fd))) : INVALID_PTR;
}

static void nativeCloseUinput(JNIEnv* env, jobject thiz, jlong ptr) {
    VirtualInputDevice* virtualInputDevice = reinterpret_cast<VirtualInputDevice*>(ptr);
    delete virtualInputDevice;
}

// Native methods for VirtualDpad
static bool nativeWriteDpadKeyEvent(JNIEnv* env, jobject thiz, jlong ptr, jint androidKeyCode,
                                    jint action, jlong eventTimeNanos) {
    VirtualDpad* virtualDpad = reinterpret_cast<VirtualDpad*>(ptr);
    return virtualDpad->writeDpadKeyEvent(androidKeyCode, action,
                                          std::chrono::nanoseconds(eventTimeNanos));
}

// Native methods for VirtualKeyboard
static bool nativeWriteKeyEvent(JNIEnv* env, jobject thiz, jlong ptr, jint androidKeyCode,
                                jint action, jlong eventTimeNanos) {
    VirtualKeyboard* virtualKeyboard = reinterpret_cast<VirtualKeyboard*>(ptr);
    return virtualKeyboard->writeKeyEvent(androidKeyCode, action,
                                          std::chrono::nanoseconds(eventTimeNanos));
}

// Native methods for VirtualTouchscreen
static bool nativeWriteTouchEvent(JNIEnv* env, jobject thiz, jlong ptr, jint pointerId,
                                  jint toolType, jint action, jfloat locationX, jfloat locationY,
                                  jfloat pressure, jfloat majorAxisSize, jlong eventTimeNanos) {
    VirtualTouchscreen* virtualTouchscreen = reinterpret_cast<VirtualTouchscreen*>(ptr);
    return virtualTouchscreen->writeTouchEvent(pointerId, toolType, action, locationX, locationY,
                                               pressure, majorAxisSize,
                                               std::chrono::nanoseconds(eventTimeNanos));
}

// Native methods for VirtualMouse
static bool nativeWriteButtonEvent(JNIEnv* env, jobject thiz, jlong ptr, jint buttonCode,
                                   jint action, jlong eventTimeNanos) {
    VirtualMouse* virtualMouse = reinterpret_cast<VirtualMouse*>(ptr);
    return virtualMouse->writeButtonEvent(buttonCode, action,
                                          std::chrono::nanoseconds(eventTimeNanos));
}

static bool nativeWriteRelativeEvent(JNIEnv* env, jobject thiz, jlong ptr, jfloat relativeX,
                                     jfloat relativeY, jlong eventTimeNanos) {
    VirtualMouse* virtualMouse = reinterpret_cast<VirtualMouse*>(ptr);
    return virtualMouse->writeRelativeEvent(relativeX, relativeY,
                                            std::chrono::nanoseconds(eventTimeNanos));
}

static bool nativeWriteScrollEvent(JNIEnv* env, jobject thiz, jlong ptr, jfloat xAxisMovement,
                                   jfloat yAxisMovement, jlong eventTimeNanos) {
    VirtualMouse* virtualMouse = reinterpret_cast<VirtualMouse*>(ptr);
    return virtualMouse->writeScrollEvent(xAxisMovement, yAxisMovement,
                                          std::chrono::nanoseconds(eventTimeNanos));
}

static JNINativeMethod methods[] = {
        {"nativeOpenUinputDpad", "(Ljava/lang/String;IILjava/lang/String;)J",
         (void*)nativeOpenUinputDpad},
        {"nativeOpenUinputKeyboard", "(Ljava/lang/String;IILjava/lang/String;)J",
         (void*)nativeOpenUinputKeyboard},
        {"nativeOpenUinputMouse", "(Ljava/lang/String;IILjava/lang/String;)J",
         (void*)nativeOpenUinputMouse},
        {"nativeOpenUinputTouchscreen", "(Ljava/lang/String;IILjava/lang/String;II)J",
         (void*)nativeOpenUinputTouchscreen},
        {"nativeCloseUinput", "(J)V", (void*)nativeCloseUinput},
        {"nativeWriteDpadKeyEvent", "(JIIJ)Z", (void*)nativeWriteDpadKeyEvent},
        {"nativeWriteKeyEvent", "(JIIJ)Z", (void*)nativeWriteKeyEvent},
        {"nativeWriteButtonEvent", "(JIIJ)Z", (void*)nativeWriteButtonEvent},
        {"nativeWriteTouchEvent", "(JIIIFFFFJ)Z", (void*)nativeWriteTouchEvent},
        {"nativeWriteRelativeEvent", "(JFFJ)Z", (void*)nativeWriteRelativeEvent},
        {"nativeWriteScrollEvent", "(JFFJ)Z", (void*)nativeWriteScrollEvent},
};

int register_android_server_companion_virtual_InputController(JNIEnv* env) {
    return jniRegisterNativeMethods(env, "com/android/server/companion/virtual/InputController",
                                    methods, NELEM(methods));
}

} // namespace android
