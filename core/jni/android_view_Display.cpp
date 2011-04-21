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

#include <cutils/properties.h>

#include <surfaceflinger/SurfaceComposerClient.h>
#include <ui/PixelFormat.h>
#include <ui/DisplayInfo.h>

#include "jni.h"
#include "JNIHelp.h"
#include <android_runtime/AndroidRuntime.h>
#include <utils/misc.h>
#include <utils/Log.h>

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

static int gShortSize = -1;
static int gLongSize = -1;
static int gOldSize = -1;
static int gNewSize = -1;

// ----------------------------------------------------------------------------

static void android_view_Display_init(
        JNIEnv* env, jobject clazz, jint dpy)
{
    DisplayInfo info;
    status_t err = SurfaceComposerClient::getDisplayInfo(DisplayID(dpy), &info);
    if (err < 0) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
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
    jint w = SurfaceComposerClient::getDisplayWidth(dpy);
    if (gShortSize > 0) {
        jint h = SurfaceComposerClient::getDisplayHeight(dpy);
        return w < h ? gShortSize : gLongSize;
    }
    return w == gOldSize ? gNewSize : w;
}

static jint android_view_Display_getHeight(
        JNIEnv* env, jobject clazz)
{
    DisplayID dpy = env->GetIntField(clazz, offsets.display);
    int h = SurfaceComposerClient::getDisplayHeight(dpy);
    if (gShortSize > 0) {
        jint w = SurfaceComposerClient::getDisplayWidth(dpy);
        return h < w ? gShortSize : gLongSize;
    }
    return h == gOldSize ? gNewSize : h;
}

static jint android_view_Display_getRawWidth(
        JNIEnv* env, jobject clazz)
{
    DisplayID dpy = env->GetIntField(clazz, offsets.display);
    return SurfaceComposerClient::getDisplayWidth(dpy);
}

static jint android_view_Display_getRawHeight(
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
    {   "getRealWidth", "()I",
            (void*)android_view_Display_getWidth },
    {   "getRealHeight", "()I",
            (void*)android_view_Display_getHeight },
    {   "getRawWidth", "()I",
            (void*)android_view_Display_getRawWidth },
    {   "getRawHeight", "()I",
            (void*)android_view_Display_getRawHeight },
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
    char buf[PROPERTY_VALUE_MAX];
    int len = property_get("persist.demo.screensizehack", buf, "");
    if (len > 0) {
        int temp1, temp2;
        if (sscanf(buf, "%dx%d", &temp1, &temp2) == 2) {
            if (temp1 < temp2) {
                gShortSize = temp1;
                gLongSize = temp2;
            } else {
                gShortSize = temp2;
                gLongSize = temp1;
            }
        } else if (sscanf(buf, "%d=%d", &temp1, &temp2) == 2) {
            gOldSize = temp1;
            gNewSize = temp2;
        }
    }

    return AndroidRuntime::registerNativeMethods(env,
            kClassPathName, gMethods, NELEM(gMethods));
}

};
