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

#define LOG_TAG "SurfaceFlingerDisplayAdapter"

#include "JNIHelp.h"
#include "jni.h"
#include <android_runtime/AndroidRuntime.h>

#include <gui/SurfaceComposerClient.h>
#include <ui/DisplayInfo.h>

#include <utils/Log.h>

namespace android {

static struct {
    jfieldID width;
    jfieldID height;
    jfieldID refreshRate;
    jfieldID densityDpi;
    jfieldID xDpi;
    jfieldID yDpi;
} gDisplayDeviceInfoClassInfo;


static void nativeGetDefaultDisplayDeviceInfo(JNIEnv* env, jclass clazz, jobject infoObj) {
    DisplayInfo info;
    status_t err = SurfaceComposerClient::getDisplayInfo(0, &info);
    if (err < 0) {
        jniThrowExceptionFmt(env, "java/lang/RuntimeException",
                "Could not get display info.  err=%d", err);
        return;
    }

    env->SetIntField(infoObj, gDisplayDeviceInfoClassInfo.width, info.w);
    env->SetIntField(infoObj, gDisplayDeviceInfoClassInfo.height, info.h);
    env->SetFloatField(infoObj, gDisplayDeviceInfoClassInfo.refreshRate, info.fps);
    env->SetIntField(infoObj, gDisplayDeviceInfoClassInfo.densityDpi,
            (jint)((info.density*160) + .5f));
    env->SetFloatField(infoObj, gDisplayDeviceInfoClassInfo.xDpi, info.xdpi);
    env->SetFloatField(infoObj, gDisplayDeviceInfoClassInfo.yDpi, info.ydpi);
}


static JNINativeMethod gSurfaceFlingerDisplayAdapterMethods[] = {
    /* name, signature, funcPtr */
    { "nativeGetDefaultDisplayDeviceInfo",
            "(Lcom/android/server/display/DisplayDeviceInfo;)V",
            (void*) nativeGetDefaultDisplayDeviceInfo },
};

#define FIND_CLASS(var, className) \
        var = env->FindClass(className); \
        LOG_FATAL_IF(! var, "Unable to find class " className);

#define GET_FIELD_ID(var, clazz, fieldName, fieldDescriptor) \
        var = env->GetFieldID(clazz, fieldName, fieldDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find field " fieldName);

int register_android_server_display_SurfaceFlingerDisplayAdapter(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env,
            "com/android/server/display/SurfaceFlingerDisplayAdapter",
            gSurfaceFlingerDisplayAdapterMethods, NELEM(gSurfaceFlingerDisplayAdapterMethods));
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");

    jclass clazz;
    FIND_CLASS(clazz, "com/android/server/display/DisplayDeviceInfo");
    GET_FIELD_ID(gDisplayDeviceInfoClassInfo.width, clazz, "width", "I");
    GET_FIELD_ID(gDisplayDeviceInfoClassInfo.height, clazz, "height", "I");
    GET_FIELD_ID(gDisplayDeviceInfoClassInfo.refreshRate, clazz, "refreshRate", "F");
    GET_FIELD_ID(gDisplayDeviceInfoClassInfo.densityDpi, clazz, "densityDpi", "I");
    GET_FIELD_ID(gDisplayDeviceInfoClassInfo.xDpi, clazz, "xDpi", "F");
    GET_FIELD_ID(gDisplayDeviceInfoClassInfo.yDpi, clazz, "yDpi", "F");
    return 0;
}

} /* namespace android */
