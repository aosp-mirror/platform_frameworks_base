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

#include "jni.h"
#include <nativehelper/JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>

#include <SkXfermode.h>

#include <OpenGLRenderer.h>

#define UI ((OpenGLRenderer*) renderer)

namespace android {

// ----------------------------------------------------------------------------
// Constructors
// ----------------------------------------------------------------------------

static OpenGLRenderer* android_view_GLES20Renderer_createRenderer(JNIEnv* env, jobject canvas) {
    return new OpenGLRenderer;
}

static void android_view_GLES20Renderer_destroyRenderer(JNIEnv* env, jobject canvas, jint renderer) {
    delete UI;
}

// ----------------------------------------------------------------------------
// Setup
// ----------------------------------------------------------------------------

static void android_view_GLES20Renderer_setViewport(JNIEnv* env, jobject canvas, jint renderer,
        jint width, jint height) {

    UI->setViewport(width, height);
}

static void android_view_GLES20Renderer_prepare(JNIEnv* env, jobject canvas, jint renderer) {

    UI->prepare();
}

// ----------------------------------------------------------------------------
// Draw color
// ----------------------------------------------------------------------------

static void android_view_GLES20Renderer_drawColor(JNIEnv* env, jobject canvas, jint renderer,
        jint color, jint mode) {

    UI->drawColor(color, (SkXfermode::Mode) mode);
}

// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

const char* const kClassPathName = "android/view/GLES20Canvas";

static JNINativeMethod gMethods[] = {
    {   "nCreateRenderer",    "()I",     (void*) android_view_GLES20Renderer_createRenderer },
    {   "nDestroyRenderer",   "(I)V",    (void*) android_view_GLES20Renderer_destroyRenderer },
    {   "nSetViewport",       "(III)V",  (void*) android_view_GLES20Renderer_setViewport },
    {   "nPrepare",           "(I)V",    (void*) android_view_GLES20Renderer_prepare },

    {   "nDrawColor",         "(III)V",  (void*) android_view_GLES20Renderer_drawColor },
};

int register_android_view_GLES20Canvas(JNIEnv* env) {
    return AndroidRuntime::registerNativeMethods(env, kClassPathName, gMethods, NELEM(gMethods));
}

};
