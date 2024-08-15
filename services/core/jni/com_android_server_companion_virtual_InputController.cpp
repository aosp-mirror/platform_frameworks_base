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
#include <input/Input.h>
#include <input/VirtualInputDevice.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedUtfChars.h>

#include <string>

using android::base::unique_fd;

namespace android {

static constexpr jlong INVALID_PTR = 0;

static unique_fd openUinputJni(JNIEnv* env, jstring name, jint vendorId, jint productId,
                               jstring phys, DeviceType deviceType, jint screenHeight,
                               jint screenWidth) {
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

static jlong nativeOpenUinputStylus(JNIEnv* env, jobject thiz, jstring name, jint vendorId,
                                    jint productId, jstring phys, jint height, jint width) {
    auto fd =
            openUinputJni(env, name, vendorId, productId, phys, DeviceType::STYLUS, height, width);
    return fd.ok() ? reinterpret_cast<jlong>(new VirtualStylus(std::move(fd))) : INVALID_PTR;
}

static jlong nativeOpenUinputRotaryEncoder(JNIEnv* env, jobject thiz, jstring name, jint vendorId,
                                           jint productId, jstring phys, jint height, jint width) {
    auto fd = openUinputJni(env, name, vendorId, productId, phys, DeviceType::ROTARY_ENCODER,
                            /* screenHeight= */ 0, /* screenWidth= */ 0);
    return fd.ok() ? reinterpret_cast<jlong>(new VirtualRotaryEncoder(std::move(fd))) : INVALID_PTR;
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

// Native methods for VirtualStylus
static bool nativeWriteStylusMotionEvent(JNIEnv* env, jobject thiz, jlong ptr, jint toolType,
                                         jint action, jint locationX, jint locationY, jint pressure,
                                         jint tiltX, jint tiltY, jlong eventTimeNanos) {
    VirtualStylus* virtualStylus = reinterpret_cast<VirtualStylus*>(ptr);
    return virtualStylus->writeMotionEvent(toolType, action, locationX, locationY, pressure, tiltX,
                                           tiltY, std::chrono::nanoseconds(eventTimeNanos));
}

static bool nativeWriteStylusButtonEvent(JNIEnv* env, jobject thiz, jlong ptr, jint buttonCode,
                                         jint action, jlong eventTimeNanos) {
    VirtualStylus* virtualStylus = reinterpret_cast<VirtualStylus*>(ptr);
    return virtualStylus->writeButtonEvent(buttonCode, action,
                                           std::chrono::nanoseconds(eventTimeNanos));
}

static bool nativeWriteRotaryEncoderScrollEvent(JNIEnv* env, jobject thiz, jlong ptr,
                                                jfloat scrollAmount, jlong eventTimeNanos) {
    VirtualRotaryEncoder* virtualRotaryEncoder = reinterpret_cast<VirtualRotaryEncoder*>(ptr);
    return virtualRotaryEncoder->writeScrollEvent(scrollAmount,
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
        {"nativeOpenUinputStylus", "(Ljava/lang/String;IILjava/lang/String;II)J",
         (void*)nativeOpenUinputStylus},
        {"nativeOpenUinputRotaryEncoder", "(Ljava/lang/String;IILjava/lang/String;)J",
         (void*)nativeOpenUinputRotaryEncoder},
        {"nativeCloseUinput", "(J)V", (void*)nativeCloseUinput},
        {"nativeWriteDpadKeyEvent", "(JIIJ)Z", (void*)nativeWriteDpadKeyEvent},
        {"nativeWriteKeyEvent", "(JIIJ)Z", (void*)nativeWriteKeyEvent},
        {"nativeWriteButtonEvent", "(JIIJ)Z", (void*)nativeWriteButtonEvent},
        {"nativeWriteTouchEvent", "(JIIIFFFFJ)Z", (void*)nativeWriteTouchEvent},
        {"nativeWriteRelativeEvent", "(JFFJ)Z", (void*)nativeWriteRelativeEvent},
        {"nativeWriteScrollEvent", "(JFFJ)Z", (void*)nativeWriteScrollEvent},
        {"nativeWriteStylusMotionEvent", "(JIIIIIIIJ)Z", (void*)nativeWriteStylusMotionEvent},
        {"nativeWriteStylusButtonEvent", "(JIIJ)Z", (void*)nativeWriteStylusButtonEvent},
        {"nativeWriteRotaryEncoderScrollEvent", "(JFJ)Z",
         (void*)nativeWriteRotaryEncoderScrollEvent},
};

int register_android_server_companion_virtual_InputController(JNIEnv* env) {
    return jniRegisterNativeMethods(env, "com/android/server/companion/virtual/InputController",
                                    methods, NELEM(methods));
}

} // namespace android
