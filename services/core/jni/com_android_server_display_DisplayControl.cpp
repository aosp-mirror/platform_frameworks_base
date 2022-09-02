/*
 * Copyright (C) 2022 The Android Open Source Project
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

#include <android_util_Binder.h>
#include <gui/SurfaceComposerClient.h>
#include <jni.h>
#include <nativehelper/ScopedUtfChars.h>

namespace android {

static jobject nativeCreateDisplay(JNIEnv* env, jclass clazz, jstring nameObj, jboolean secure) {
    ScopedUtfChars name(env, nameObj);
    sp<IBinder> token(SurfaceComposerClient::createDisplay(String8(name.c_str()), bool(secure)));
    return javaObjectForIBinder(env, token);
}

static void nativeDestroyDisplay(JNIEnv* env, jclass clazz, jobject tokenObj) {
    sp<IBinder> token(ibinderForJavaObject(env, tokenObj));
    if (token == NULL) return;
    SurfaceComposerClient::destroyDisplay(token);
}

// ----------------------------------------------------------------------------

static const JNINativeMethod sDisplayMethods[] = {
        // clang-format off
    {"nativeCreateDisplay", "(Ljava/lang/String;Z)Landroid/os/IBinder;",
            (void*)nativeCreateDisplay },
    {"nativeDestroyDisplay", "(Landroid/os/IBinder;)V",
            (void*)nativeDestroyDisplay },
        // clang-format on
};

int register_com_android_server_display_DisplayControl(JNIEnv* env) {
    return jniRegisterNativeMethods(env, "com/android/server/display/DisplayControl",
                                    sDisplayMethods, NELEM(sDisplayMethods));
}

} // namespace android
