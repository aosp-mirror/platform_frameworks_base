/*
 * Copyright (C) 2007 The Android Open Source Project
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

#define LOG_TAG "AWT"

#include "jni.h"
#include "JNIHelp.h"
#include "GraphicsJNI.h"
#include <android_runtime/AndroidRuntime.h>

#include "SkCanvas.h"
#include "SkDevice.h"
#include "SkPicture.h"
#include "SkTemplates.h"

namespace android
{

static jclass class_fileDescriptor;
static jfieldID field_fileDescriptor_descriptor;
static jmethodID method_fileDescriptor_init;
 

static jboolean scrollRect(JNIEnv* env, jobject graphics2D, jobject canvas, jobject rect, int dx, int dy) {
    if (canvas == NULL) {
        jniThrowException(env, "java/lang/NullPointerException", NULL);
        return false;
    }
  
    SkIRect src, *srcPtr = NULL;
    if (NULL != rect) {
        GraphicsJNI::jrect_to_irect(env, rect, &src);
        srcPtr = &src;
    }
    SkCanvas* c = GraphicsJNI::getNativeCanvas(env, canvas);
    const SkBitmap& bitmap = c->getDevice()->accessBitmap(true);
    return bitmap.scrollRect(srcPtr, dx, dy, NULL);
}

static JNINativeMethod method_table[] = {
    { "nativeScrollRect",
      "(Landroid/graphics/Canvas;Landroid/graphics/Rect;II)Z",
      (void*)scrollRect}
};

int register_com_android_internal_graphics_NativeUtils(JNIEnv *env) {
    return AndroidRuntime::registerNativeMethods(
        env, "com/android/internal/graphics/NativeUtils",
        method_table, NELEM(method_table));
}

}
