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
#include "jni_common.h"

#include <jni.h>
#include <ui/GraphicTypes.h>
#include <ui/Rect.h>

#include "core_jni_helpers.h"

// ----------------------------------------------------------------------------

namespace android {

static struct {
    jclass clazz;
    jmethodID ctor;
    jfieldID bottom;
    jfieldID left;
    jfieldID right;
    jfieldID top;
} gRectClassInfo;

Rect JNICommon::rectFromObj(JNIEnv* env, jobject rectObj) {
    int left = env->GetIntField(rectObj, gRectClassInfo.left);
    int top = env->GetIntField(rectObj, gRectClassInfo.top);
    int right = env->GetIntField(rectObj, gRectClassInfo.right);
    int bottom = env->GetIntField(rectObj, gRectClassInfo.bottom);
    return Rect(left, top, right, bottom);
}

jobject JNICommon::objFromRect(JNIEnv* env, Rect rect) {
    return env->NewObject(gRectClassInfo.clazz, gRectClassInfo.ctor, rect.left, rect.top,
                          rect.right, rect.bottom);
}

int register_jni_common(JNIEnv* env) {
    jclass rectClazz = FindClassOrDie(env, "android/graphics/Rect");
    gRectClassInfo.clazz = MakeGlobalRefOrDie(env, rectClazz);
    gRectClassInfo.ctor = GetMethodIDOrDie(env, rectClazz, "<init>", "(IIII)V");
    gRectClassInfo.bottom = GetFieldIDOrDie(env, rectClazz, "bottom", "I");
    gRectClassInfo.left = GetFieldIDOrDie(env, rectClazz, "left", "I");
    gRectClassInfo.right = GetFieldIDOrDie(env, rectClazz, "right", "I");
    gRectClassInfo.top = GetFieldIDOrDie(env, rectClazz, "top", "I");
    return 0;
}

} // namespace android
