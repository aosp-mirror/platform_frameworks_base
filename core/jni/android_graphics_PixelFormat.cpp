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

#include <stdio.h>
#include <assert.h>

#include <ui/PixelFormat.h>

#include "jni.h"
#include <android_runtime/AndroidRuntime.h>
#include <utils/misc.h>

// ----------------------------------------------------------------------------

namespace android {

// ----------------------------------------------------------------------------

struct offsets_t {
    jfieldID bytesPerPixel;
    jfieldID bitsPerPixel;
};

static offsets_t offsets;

static void doThrow(JNIEnv* env, const char* exc, const char* msg = NULL)
{
    jclass npeClazz = env->FindClass(exc);
    env->ThrowNew(npeClazz, msg);
}

// ----------------------------------------------------------------------------

static void android_graphics_getPixelFormatInfo(
        JNIEnv* env, jobject clazz, jint format, jobject pixelFormatObject)
{
    PixelFormatInfo info;
    status_t err;

    // we need this for backward compatibility with PixelFormat's
    // deprecated constants
    switch (format) {
    case HAL_PIXEL_FORMAT_YCbCr_422_SP:
        // defined as the bytes per pixel of the Y plane
        info.bytesPerPixel = 1;
        info.bitsPerPixel = 16;
        goto done;
    case HAL_PIXEL_FORMAT_YCrCb_420_SP:
        // defined as the bytes per pixel of the Y plane
        info.bytesPerPixel = 1;
        info.bitsPerPixel = 12;
        goto done;
    case HAL_PIXEL_FORMAT_YCbCr_422_I:
        // defined as the bytes per pixel of the Y plane
        info.bytesPerPixel = 1;
        info.bitsPerPixel = 16;
        goto done;
    }

    err = getPixelFormatInfo(format, &info);
    if (err < 0) {
        doThrow(env, "java/lang/IllegalArgumentException");
        return;
    }

done:
    env->SetIntField(pixelFormatObject, offsets.bytesPerPixel, info.bytesPerPixel);
    env->SetIntField(pixelFormatObject, offsets.bitsPerPixel,  info.bitsPerPixel);
}
// ----------------------------------------------------------------------------

const char* const kClassPathName = "android/graphics/PixelFormat";

static void nativeClassInit(JNIEnv* env, jclass clazz);

static JNINativeMethod gMethods[] = {
    {   "nativeClassInit", "()V",
        (void*)nativeClassInit },
	{   "getPixelFormatInfo", "(ILandroid/graphics/PixelFormat;)V",
        (void*)android_graphics_getPixelFormatInfo
    }
};

void nativeClassInit(JNIEnv* env, jclass clazz)
{
    offsets.bytesPerPixel = env->GetFieldID(clazz, "bytesPerPixel", "I");
    offsets.bitsPerPixel  = env->GetFieldID(clazz, "bitsPerPixel", "I");    
}

int register_android_graphics_PixelFormat(JNIEnv* env)
{
    return AndroidRuntime::registerNativeMethods(env,
            kClassPathName, gMethods, NELEM(gMethods));
}

};
