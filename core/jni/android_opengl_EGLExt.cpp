/*
** Copyright 2013, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

// This source file is automatically generated

#include "jni.h"
#include "JNIHelp.h"
#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/android_view_Surface.h>
#include <android_runtime/android_graphics_SurfaceTexture.h>
#include <utils/misc.h>

#include <assert.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>

#include <gui/Surface.h>
#include <gui/GLConsumer.h>
#include <gui/Surface.h>

#include <ui/ANativeObjectBase.h>

static int initialized = 0;

static jclass egldisplayClass;
static jclass eglcontextClass;
static jclass eglsurfaceClass;
static jclass eglconfigClass;

static jmethodID egldisplayGetHandleID;
static jmethodID eglcontextGetHandleID;
static jmethodID eglsurfaceGetHandleID;
static jmethodID eglconfigGetHandleID;

static jmethodID egldisplayConstructor;
static jmethodID eglcontextConstructor;
static jmethodID eglsurfaceConstructor;
static jmethodID eglconfigConstructor;

static jobject eglNoContextObject;
static jobject eglNoDisplayObject;
static jobject eglNoSurfaceObject;



/* Cache method IDs each time the class is loaded. */

static void
nativeClassInit(JNIEnv *_env, jclass glImplClass)
{
    jclass egldisplayClassLocal = _env->FindClass("android/opengl/EGLDisplay");
    egldisplayClass = (jclass) _env->NewGlobalRef(egldisplayClassLocal);
    jclass eglcontextClassLocal = _env->FindClass("android/opengl/EGLContext");
    eglcontextClass = (jclass) _env->NewGlobalRef(eglcontextClassLocal);
    jclass eglsurfaceClassLocal = _env->FindClass("android/opengl/EGLSurface");
    eglsurfaceClass = (jclass) _env->NewGlobalRef(eglsurfaceClassLocal);
    jclass eglconfigClassLocal = _env->FindClass("android/opengl/EGLConfig");
    eglconfigClass = (jclass) _env->NewGlobalRef(eglconfigClassLocal);

    egldisplayGetHandleID = _env->GetMethodID(egldisplayClass, "getNativeHandle", "()J");
    eglcontextGetHandleID = _env->GetMethodID(eglcontextClass, "getNativeHandle", "()J");
    eglsurfaceGetHandleID = _env->GetMethodID(eglsurfaceClass, "getNativeHandle", "()J");
    eglconfigGetHandleID = _env->GetMethodID(eglconfigClass, "getNativeHandle", "()J");


    egldisplayConstructor = _env->GetMethodID(egldisplayClass, "<init>", "(J)V");
    eglcontextConstructor = _env->GetMethodID(eglcontextClass, "<init>", "(J)V");
    eglsurfaceConstructor = _env->GetMethodID(eglsurfaceClass, "<init>", "(J)V");
    eglconfigConstructor = _env->GetMethodID(eglconfigClass, "<init>", "(J)V");


    jclass eglClass = _env->FindClass("android/opengl/EGL14");
    jfieldID noContextFieldID = _env->GetStaticFieldID(eglClass, "EGL_NO_CONTEXT", "Landroid/opengl/EGLContext;");
    jobject localeglNoContextObject = _env->GetStaticObjectField(eglClass, noContextFieldID);
    eglNoContextObject = _env->NewGlobalRef(localeglNoContextObject);

    jfieldID noDisplayFieldID = _env->GetStaticFieldID(eglClass, "EGL_NO_DISPLAY", "Landroid/opengl/EGLDisplay;");
    jobject localeglNoDisplayObject = _env->GetStaticObjectField(eglClass, noDisplayFieldID);
    eglNoDisplayObject = _env->NewGlobalRef(localeglNoDisplayObject);

    jfieldID noSurfaceFieldID = _env->GetStaticFieldID(eglClass, "EGL_NO_SURFACE", "Landroid/opengl/EGLSurface;");
    jobject localeglNoSurfaceObject = _env->GetStaticObjectField(eglClass, noSurfaceFieldID);
    eglNoSurfaceObject = _env->NewGlobalRef(localeglNoSurfaceObject);
}

static void *
fromEGLHandle(JNIEnv *_env, jmethodID mid, jobject obj) {
    if (obj == NULL){
        jniThrowException(_env, "java/lang/IllegalArgumentException",
                          "Object is set to null.");
    }

    return reinterpret_cast<void*>(_env->CallLongMethod(obj, mid));
}

static jobject
toEGLHandle(JNIEnv *_env, jclass cls, jmethodID con, void * handle) {
    if (cls == eglcontextClass &&
       (EGLContext)handle == EGL_NO_CONTEXT) {
           return eglNoContextObject;
    }

    if (cls == egldisplayClass &&
       (EGLDisplay)handle == EGL_NO_DISPLAY) {
           return eglNoDisplayObject;
    }

    if (cls == eglsurfaceClass &&
       (EGLSurface)handle == EGL_NO_SURFACE) {
           return eglNoSurfaceObject;
    }

    return _env->NewObject(cls, con, reinterpret_cast<jlong>(handle));
}

// --------------------------------------------------------------------------
/* EGLBoolean eglPresentationTimeANDROID ( EGLDisplay dpy, EGLSurface sur, EGLnsecsANDROID time ) */
static jboolean
android_eglPresentationTimeANDROID
  (JNIEnv *_env, jobject _this, jobject dpy, jobject sur, jlong time) {
    EGLBoolean _returnValue = (EGLBoolean) 0;
    EGLDisplay dpy_native = (EGLDisplay) fromEGLHandle(_env, egldisplayGetHandleID, dpy);
    EGLSurface sur_native = (EGLSurface) fromEGLHandle(_env, eglsurfaceGetHandleID, sur);

    _returnValue = eglPresentationTimeANDROID(
        (EGLDisplay)dpy_native,
        (EGLSurface)sur_native,
        (EGLnsecsANDROID)time
    );
    return (jboolean)_returnValue;
}

static const char *classPathName = "android/opengl/EGLExt";

static JNINativeMethod methods[] = {
{"_nativeClassInit", "()V", (void*)nativeClassInit },
{"eglPresentationTimeANDROID", "(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLSurface;J)Z", (void *) android_eglPresentationTimeANDROID },
};

int register_android_opengl_jni_EGLExt(JNIEnv *_env)
{
    int err;
    err = android::AndroidRuntime::registerNativeMethods(_env, classPathName, methods, NELEM(methods));
    return err;
}
