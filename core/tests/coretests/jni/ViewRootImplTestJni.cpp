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

//#define LOG_NDEBUG 0
#define LOG_TAG "ViewRootImplTest"

#include "jni.h"

#include <android_util_Binder.h>
#include <binder/IServiceManager.h>
#include <nativehelper/JNIHelp.h>
#include <utils/Log.h>
#include <android/native_window_jni.h>
#include <android/surface_control.h>
#include <android/surface_control_jni.h>

#include <android-base/stringprintf.h>
#include <android-base/strings.h>

namespace android {

static bool nativeCreateASurfaceControlFromSurface(JNIEnv* env, jclass /* obj */,
        jobject jSurface) {
    if (!jSurface) {
        ALOGE("Surface object is null\n");
        return false;
    }

    ANativeWindow* window = ANativeWindow_fromSurface(env, jSurface);
    if (!window) {
        ALOGE("Could not create ANW from jSurface\n");
        return false;
    }

    ASurfaceControl* surfaceControl =
            ASurfaceControl_createFromWindow(window, "ViewRootImplTestLayer");
    if (!surfaceControl) {
        ALOGE("Could not create SC from ANW\n");
        return false;
    }

    ANativeWindow_release(window);
    ASurfaceControl_release(surfaceControl);
    return true;
}

extern "C" jint JNI_OnLoad(JavaVM* vm, void* /* reserved */)
{
    JNIEnv* env;
    const JNINativeMethod methodTable[] = {
        /* name, signature, funcPtr */
        { "nativeCreateASurfaceControlFromSurface", "(Landroid/view/Surface;)Z",
                (void*) nativeCreateASurfaceControlFromSurface },
    };

    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    jniRegisterNativeMethods(env, "android/view/ViewRootImplTest", methodTable,
                sizeof(methodTable) / sizeof(JNINativeMethod));

    return JNI_VERSION_1_6;
}

} /* namespace android */
