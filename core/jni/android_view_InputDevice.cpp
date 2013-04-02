/*
 * Copyright (C) 2012 The Android Open Source Project
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

#include <androidfw/Input.h>

#include <android_runtime/AndroidRuntime.h>
#include <nativehelper/jni.h>
#include <nativehelper/JNIHelp.h>

#include <ScopedLocalRef.h>

#include "android_view_InputDevice.h"
#include "android_view_KeyCharacterMap.h"

namespace android {

static struct {
    jclass clazz;

    jmethodID ctor;
    jmethodID addMotionRange;
} gInputDeviceClassInfo;

jobject android_view_InputDevice_create(JNIEnv* env, const InputDeviceInfo& deviceInfo) {
    ScopedLocalRef<jstring> nameObj(env, env->NewStringUTF(deviceInfo.getDisplayName().string()));
    if (!nameObj.get()) {
        return NULL;
    }

    ScopedLocalRef<jstring> descriptorObj(env,
            env->NewStringUTF(deviceInfo.getIdentifier().descriptor.string()));
    if (!descriptorObj.get()) {
        return NULL;
    }

    ScopedLocalRef<jobject> kcmObj(env,
            android_view_KeyCharacterMap_create(env, deviceInfo.getId(),
            deviceInfo.getKeyCharacterMap()));
    if (!kcmObj.get()) {
        return NULL;
    }

    ScopedLocalRef<jobject> inputDeviceObj(env, env->NewObject(gInputDeviceClassInfo.clazz,
            gInputDeviceClassInfo.ctor, deviceInfo.getId(), deviceInfo.getGeneration(),
            nameObj.get(), descriptorObj.get(), deviceInfo.isExternal(),
            deviceInfo.getSources(), deviceInfo.getKeyboardType(),
            kcmObj.get(), deviceInfo.hasVibrator()));

    const Vector<InputDeviceInfo::MotionRange>& ranges = deviceInfo.getMotionRanges();
    for (size_t i = 0; i < ranges.size(); i++) {
        const InputDeviceInfo::MotionRange& range = ranges.itemAt(i);
        env->CallVoidMethod(inputDeviceObj.get(), gInputDeviceClassInfo.addMotionRange, range.axis,
                range.source, range.min, range.max, range.flat, range.fuzz, range.resolution);
        if (env->ExceptionCheck()) {
            return NULL;
        }
    }

    return env->NewLocalRef(inputDeviceObj.get());
}


#define FIND_CLASS(var, className) \
        var = env->FindClass(className); \
        LOG_FATAL_IF(! var, "Unable to find class " className);

#define GET_METHOD_ID(var, clazz, methodName, methodDescriptor) \
        var = env->GetMethodID(clazz, methodName, methodDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find method " methodName);

int register_android_view_InputDevice(JNIEnv* env)
{
    FIND_CLASS(gInputDeviceClassInfo.clazz, "android/view/InputDevice");
    gInputDeviceClassInfo.clazz = jclass(env->NewGlobalRef(gInputDeviceClassInfo.clazz));

    GET_METHOD_ID(gInputDeviceClassInfo.ctor, gInputDeviceClassInfo.clazz,
            "<init>", "(IILjava/lang/String;Ljava/lang/String;ZIILandroid/view/KeyCharacterMap;Z)V");

    GET_METHOD_ID(gInputDeviceClassInfo.addMotionRange, gInputDeviceClassInfo.clazz,
            "addMotionRange", "(IIFFFFF)V");

    return 0;
}

}; // namespace android
