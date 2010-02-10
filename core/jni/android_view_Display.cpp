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

#include <surfaceflinger/SurfaceComposerClient.h>
#include <ui/PixelFormat.h>
#include <ui/DisplayInfo.h>

#include "jni.h"
#include <android_runtime/AndroidRuntime.h>
#include <utils/misc.h>

// ----------------------------------------------------------------------------

namespace android {

// ----------------------------------------------------------------------------

struct offsets_t {
    jfieldID display;
    jfieldID pixelFormat;
    jfieldID fps;
    jfieldID density;
    jfieldID xdpi;
    jfieldID ydpi;
};
static offsets_t offsets;

static void doThrow(JNIEnv* env, const char* exc, const char* msg = NULL)
{
    jclass npeClazz = env->FindClass(exc);
    env->ThrowNew(npeClazz, msg);
}

// ----------------------------------------------------------------------------

static void android_view_Display_init(
        JNIEnv* env, jobject clazz, jint dpy)
{
    DisplayInfo info;
    status_t err = SurfaceComposerClient::getDisplayInfo(DisplayID(dpy), &info);
    if (err < 0) {
        doThrow(env, "java/lang/IllegalArgumentException");
        return;
    }
    env->SetIntField(clazz, offsets.pixelFormat,info.pixelFormatInfo.format);
    env->SetFloatField(clazz, offsets.fps,      info.fps);
    env->SetFloatField(clazz, offsets.density,  info.density);
    env->SetFloatField(clazz, offsets.xdpi,     info.xdpi);
    env->SetFloatField(clazz, offsets.ydpi,     info.ydpi);
}

static jint android_view_Display_getWidth(
        JNIEnv* env, jobject clazz)
{
    DisplayID dpy = env->GetIntField(clazz, offsets.display);
    return SurfaceComposerClient::getDisplayWidth(dpy);
}

static jint android_view_Display_getHeight(
        JNIEnv* env, jobject clazz)
{
    DisplayID dpy = env->GetIntField(clazz, offsets.display);
    return SurfaceComposerClient::getDisplayHeight(dpy);
}

static jint android_view_Display_getOrientation(
        JNIEnv* env, jobject clazz)
{
    DisplayID dpy = env->GetIntField(clazz, offsets.display);
    return SurfaceComposerClient::getDisplayOrientation(dpy);
}

static jint android_view_Display_getDisplayCount(
        JNIEnv* env, jclass clazz)
{
    return SurfaceComposerClient::getNumberOfDisplays();
}

// ----------------------------------------------------------------------------

const char* const kClassPathName = "android/view/Display";

static void nativeClassInit(JNIEnv* env, jclass clazz);

static JNINativeMethod gMethods[] = {
    {   "nativeClassInit", "()V",
            (void*)nativeClassInit },
    {   "getDisplayCount", "()I",
            (void*)android_view_Display_getDisplayCount },
	{   "init", "(I)V",
            (void*)android_view_Display_init },
    {   "getWidth", "()I",
            (void*)android_view_Display_getWidth },
    {   "getHeight", "()I",
            (void*)android_view_Display_getHeight },
    {   "getOrientation", "()I",
            (void*)android_view_Display_getOrientation }
};

void nativeClassInit(JNIEnv* env, jclass clazz)
{
    offsets.display     = env->GetFieldID(clazz, "mDisplay", "I");
    offsets.pixelFormat = env->GetFieldID(clazz, "mPixelFormat", "I");
    offsets.fps         = env->GetFieldID(clazz, "mRefreshRate", "F");
    offsets.density     = env->GetFieldID(clazz, "mDensity", "F");
    offsets.xdpi        = env->GetFieldID(clazz, "mDpiX", "F");
    offsets.ydpi        = env->GetFieldID(clazz, "mDpiY", "F");
}

int register_android_view_Display(JNIEnv* env)
{
    return AndroidRuntime::registerNativeMethods(env,
            kClassPathName, gMethods, NELEM(gMethods));
}

};

