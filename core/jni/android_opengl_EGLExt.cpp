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

    egldisplayGetHandleID = _env->GetMethodID(egldisplayClass, "getHandle", "()I");
    eglcontextGetHandleID = _env->GetMethodID(eglcontextClass, "getHandle", "()I");
    eglsurfaceGetHandleID = _env->GetMethodID(eglsurfaceClass, "getHandle", "()I");
    eglconfigGetHandleID = _env->GetMethodID(eglconfigClass, "getHandle", "()I");


    egldisplayConstructor = _env->GetMethodID(egldisplayClass, "<init>", "(I)V");
    eglcontextConstructor = _env->GetMethodID(eglcontextClass, "<init>", "(I)V");
    eglsurfaceConstructor = _env->GetMethodID(eglsurfaceClass, "<init>", "(I)V");
    eglconfigConstructor = _env->GetMethodID(eglconfigClass, "<init>", "(I)V");

    jobject localeglNoContextObject = _env->NewObject(eglcontextClass, eglcontextConstructor, (jint)EGL_NO_CONTEXT);
    eglNoContextObject = _env->NewGlobalRef(localeglNoContextObject);
    jobject localeglNoDisplayObject = _env->NewObject(egldisplayClass, egldisplayConstructor, (jint)EGL_NO_DISPLAY);
    eglNoDisplayObject = _env->NewGlobalRef(localeglNoDisplayObject);
    jobject localeglNoSurfaceObject = _env->NewObject(eglsurfaceClass, eglsurfaceConstructor, (jint)EGL_NO_SURFACE);
    eglNoSurfaceObject = _env->NewGlobalRef(localeglNoSurfaceObject);


    jclass eglClass = _env->FindClass("android/opengl/EGL14");
    jfieldID noContextFieldID = _env->GetStaticFieldID(eglClass, "EGL_NO_CONTEXT", "Landroid/opengl/EGLContext;");
    _env->SetStaticObjectField(eglClass, noContextFieldID, eglNoContextObject);

    jfieldID noDisplayFieldID = _env->GetStaticFieldID(eglClass, "EGL_NO_DISPLAY", "Landroid/opengl/EGLDisplay;");
    _env->SetStaticObjectField(eglClass, noDisplayFieldID, eglNoDisplayObject);

    jfieldID noSurfaceFieldID = _env->GetStaticFieldID(eglClass, "EGL_NO_SURFACE", "Landroid/opengl/EGLSurface;");
    _env->SetStaticObjectField(eglClass, noSurfaceFieldID, eglNoSurfaceObject);
}

static void *
fromEGLHandle(JNIEnv *_env, jmethodID mid, jobject obj) {
    if (obj == NULL){
        jniThrowException(_env, "java/lang/IllegalArgumentException",
                          "Object is set to null.");
    }

    return (void*) (_env->CallIntMethod(obj, mid));
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

    return _env->NewObject(cls, con, (jint)handle);
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
