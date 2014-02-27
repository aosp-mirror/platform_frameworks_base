/*
** Copyright 2012, The Android Open Source Project
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

    jobject localeglNoContextObject = _env->NewObject(eglcontextClass, eglcontextConstructor, reinterpret_cast<jlong>(EGL_NO_CONTEXT));
    eglNoContextObject = _env->NewGlobalRef(localeglNoContextObject);
    jobject localeglNoDisplayObject = _env->NewObject(egldisplayClass, egldisplayConstructor, reinterpret_cast<jlong>(EGL_NO_DISPLAY));
    eglNoDisplayObject = _env->NewGlobalRef(localeglNoDisplayObject);
    jobject localeglNoSurfaceObject = _env->NewObject(eglsurfaceClass, eglsurfaceConstructor, reinterpret_cast<jlong>(EGL_NO_SURFACE));
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

    jlong handle = _env->CallLongMethod(obj, mid);
    return reinterpret_cast<void*>(handle);
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
/* EGLint eglGetError ( void ) */
static jint
android_eglGetError
  (JNIEnv *_env, jobject _this) {
    EGLint _returnValue = (EGLint) 0;
    _returnValue = eglGetError();
    return (jint)_returnValue;
}

/* EGLDisplay eglGetDisplay ( EGLNativeDisplayType display_id ) */
static jobject
android_eglGetDisplay
  (JNIEnv *_env, jobject _this, jlong display_id) {
    EGLDisplay _returnValue = (EGLDisplay) 0;
    _returnValue = eglGetDisplay(
        reinterpret_cast<EGLNativeDisplayType>(display_id)
    );
    return toEGLHandle(_env, egldisplayClass, egldisplayConstructor, _returnValue);
}

/* EGLDisplay eglGetDisplay ( EGLNativeDisplayType display_id ) */
static jobject
android_eglGetDisplayInt
  (JNIEnv *_env, jobject _this, jint display_id) {

    if ((EGLNativeDisplayType)display_id != EGL_DEFAULT_DISPLAY) {
        jniThrowException(_env, "java/lang/UnsupportedOperationException", "eglGetDisplay");
        return 0;
    }
    return android_eglGetDisplay(_env, _this, display_id);
}

/* EGLBoolean eglInitialize ( EGLDisplay dpy, EGLint *major, EGLint *minor ) */
static jboolean
android_eglInitialize
  (JNIEnv *_env, jobject _this, jobject dpy, jintArray major_ref, jint majorOffset, jintArray minor_ref, jint minorOffset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    EGLBoolean _returnValue = (EGLBoolean) 0;
    EGLDisplay dpy_native = (EGLDisplay) fromEGLHandle(_env, egldisplayGetHandleID, dpy);
    EGLint *major_base = (EGLint *) 0;
    jint _majorRemaining;
    EGLint *major = (EGLint *) 0;
    EGLint *minor_base = (EGLint *) 0;
    jint _minorRemaining;
    EGLint *minor = (EGLint *) 0;

    if (!major_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "major == null";
        goto exit;
    }
    if (majorOffset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "majorOffset < 0";
        goto exit;
    }
    _majorRemaining = _env->GetArrayLength(major_ref) - majorOffset;
    if (_majorRemaining < 1) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - majorOffset < 1 < needed";
        goto exit;
    }
    major_base = (EGLint *)
        _env->GetPrimitiveArrayCritical(major_ref, (jboolean *)0);
    major = major_base + majorOffset;

    if (!minor_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "minor == null";
        goto exit;
    }
    if (minorOffset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "minorOffset < 0";
        goto exit;
    }
    _minorRemaining = _env->GetArrayLength(minor_ref) - minorOffset;
    if (_minorRemaining < 1) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - minorOffset < 1 < needed";
        goto exit;
    }
    minor_base = (EGLint *)
        _env->GetPrimitiveArrayCritical(minor_ref, (jboolean *)0);
    minor = minor_base + minorOffset;

    _returnValue = eglInitialize(
        (EGLDisplay)dpy_native,
        (EGLint *)major,
        (EGLint *)minor
    );

exit:
    if (minor_base) {
        _env->ReleasePrimitiveArrayCritical(minor_ref, minor_base,
            _exception ? JNI_ABORT: 0);
    }
    if (major_base) {
        _env->ReleasePrimitiveArrayCritical(major_ref, major_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
    return (jboolean)_returnValue;
}

/* EGLBoolean eglTerminate ( EGLDisplay dpy ) */
static jboolean
android_eglTerminate
  (JNIEnv *_env, jobject _this, jobject dpy) {
    EGLBoolean _returnValue = (EGLBoolean) 0;
    EGLDisplay dpy_native = (EGLDisplay) fromEGLHandle(_env, egldisplayGetHandleID, dpy);

    _returnValue = eglTerminate(
        (EGLDisplay)dpy_native
    );
    return (jboolean)_returnValue;
}

/* const char * eglQueryString ( EGLDisplay dpy, EGLint name ) */
static jstring
android_eglQueryString__Landroind_opengl_EGLDisplay_2I
  (JNIEnv *_env, jobject _this, jobject dpy, jint name) {
    const char* chars = (const char*) eglQueryString(
        (EGLDisplay)fromEGLHandle(_env, egldisplayGetHandleID, dpy),
        (EGLint)name
    );
    return _env->NewStringUTF(chars);
}
/* EGLBoolean eglGetConfigs ( EGLDisplay dpy, EGLConfig *configs, EGLint config_size, EGLint *num_config ) */
static jboolean
android_eglGetConfigs
  (JNIEnv *_env, jobject _this, jobject dpy, jobjectArray configs_ref, jint configsOffset, jint config_size, jintArray num_config_ref, jint num_configOffset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    EGLBoolean _returnValue = (EGLBoolean) 0;
    EGLDisplay dpy_native = (EGLDisplay) fromEGLHandle(_env, egldisplayGetHandleID, dpy);
    jint _configsRemaining;
    EGLConfig *configs = (EGLConfig *) 0;
    EGLint *num_config_base = (EGLint *) 0;
    jint _num_configRemaining;
    EGLint *num_config = (EGLint *) 0;

    if (!configs_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "configs == null";
        goto exit;
    }
    if (configsOffset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "configsOffset < 0";
        goto exit;
    }
    _configsRemaining = _env->GetArrayLength(configs_ref) - configsOffset;
    if (_configsRemaining < config_size) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - configsOffset < config_size < needed";
        goto exit;
    }
    configs = new EGLConfig[_configsRemaining];

    if (!num_config_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "num_config == null";
        goto exit;
    }
    if (num_configOffset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "num_configOffset < 0";
        goto exit;
    }
    _num_configRemaining = _env->GetArrayLength(num_config_ref) - num_configOffset;
    num_config_base = (EGLint *)
        _env->GetPrimitiveArrayCritical(num_config_ref, (jboolean *)0);
    num_config = num_config_base + num_configOffset;

    _returnValue = eglGetConfigs(
        (EGLDisplay)dpy_native,
        (EGLConfig *)configs,
        (EGLint)config_size,
        (EGLint *)num_config
    );

exit:
    if (num_config_base) {
        _env->ReleasePrimitiveArrayCritical(num_config_ref, num_config_base,
            _exception ? JNI_ABORT: 0);
    }
    if (configs) {
        for (int i = 0; i < _configsRemaining; i++) {
            jobject configs_new = toEGLHandle(_env, eglconfigClass, eglconfigConstructor, configs[i]);
            _env->SetObjectArrayElement(configs_ref, i + configsOffset, configs_new);
        }
        delete[] configs;
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
    return (jboolean)_returnValue;
}

/* EGLBoolean eglChooseConfig ( EGLDisplay dpy, const EGLint *attrib_list, EGLConfig *configs, EGLint config_size, EGLint *num_config ) */
static jboolean
android_eglChooseConfig
  (JNIEnv *_env, jobject _this, jobject dpy, jintArray attrib_list_ref, jint attrib_listOffset, jobjectArray configs_ref, jint configsOffset, jint config_size, jintArray num_config_ref, jint num_configOffset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    EGLBoolean _returnValue = (EGLBoolean) 0;
    EGLDisplay dpy_native = (EGLDisplay) fromEGLHandle(_env, egldisplayGetHandleID, dpy);
    bool attrib_list_sentinel = false;
    EGLint *attrib_list_base = (EGLint *) 0;
    jint _attrib_listRemaining;
    EGLint *attrib_list = (EGLint *) 0;
    jint _configsRemaining;
    EGLConfig *configs = (EGLConfig *) 0;
    EGLint *num_config_base = (EGLint *) 0;
    jint _num_configRemaining;
    EGLint *num_config = (EGLint *) 0;

    if (!attrib_list_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "attrib_list == null";
        goto exit;
    }
    if (attrib_listOffset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "attrib_listOffset < 0";
        goto exit;
    }
    _attrib_listRemaining = _env->GetArrayLength(attrib_list_ref) - attrib_listOffset;
    attrib_list_base = (EGLint *)
        _env->GetPrimitiveArrayCritical(attrib_list_ref, (jboolean *)0);
    attrib_list = attrib_list_base + attrib_listOffset;
    attrib_list_sentinel = false;
    for (int i = _attrib_listRemaining - 1; i >= 0; i--)  {
        if (attrib_list[i] == EGL_NONE){
            attrib_list_sentinel = true;
            break;
        }
    }
    if (attrib_list_sentinel == false) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "attrib_list must contain EGL_NONE!";
        goto exit;
    }

    if (!configs_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "configs == null";
        goto exit;
    }
    if (configsOffset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "configsOffset < 0";
        goto exit;
    }
    _configsRemaining = _env->GetArrayLength(configs_ref) - configsOffset;
    if (_configsRemaining < config_size) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - configsOffset < config_size < needed";
        goto exit;
    }
    configs = new EGLConfig[_configsRemaining];

    if (!num_config_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "num_config == null";
        goto exit;
    }
    if (num_configOffset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "num_configOffset < 0";
        goto exit;
    }
    _num_configRemaining = _env->GetArrayLength(num_config_ref) - num_configOffset;
    if (_num_configRemaining < 1) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - num_configOffset < 1 < needed";
        goto exit;
    }
    num_config_base = (EGLint *)
        _env->GetPrimitiveArrayCritical(num_config_ref, (jboolean *)0);
    num_config = num_config_base + num_configOffset;

    _returnValue = eglChooseConfig(
        (EGLDisplay)dpy_native,
        (EGLint *)attrib_list,
        (EGLConfig *)configs,
        (EGLint)config_size,
        (EGLint *)num_config
    );

exit:
    if (num_config_base) {
        _env->ReleasePrimitiveArrayCritical(num_config_ref, num_config_base,
            _exception ? JNI_ABORT: 0);
    }
    if (attrib_list_base) {
        _env->ReleasePrimitiveArrayCritical(attrib_list_ref, attrib_list_base,
            JNI_ABORT);
    }
    if (configs) {
        for (int i = 0; i < _configsRemaining; i++) {
            jobject configs_new = toEGLHandle(_env, eglconfigClass, eglconfigConstructor, configs[i]);
            _env->SetObjectArrayElement(configs_ref, i + configsOffset, configs_new);
        }
        delete[] configs;
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
    return (jboolean)_returnValue;
}

/* EGLBoolean eglGetConfigAttrib ( EGLDisplay dpy, EGLConfig config, EGLint attribute, EGLint *value ) */
static jboolean
android_eglGetConfigAttrib
  (JNIEnv *_env, jobject _this, jobject dpy, jobject config, jint attribute, jintArray value_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    EGLBoolean _returnValue = (EGLBoolean) 0;
    EGLDisplay dpy_native = (EGLDisplay) fromEGLHandle(_env, egldisplayGetHandleID, dpy);
    EGLConfig config_native = (EGLConfig) fromEGLHandle(_env, eglconfigGetHandleID, config);
    EGLint *value_base = (EGLint *) 0;
    jint _remaining;
    EGLint *value = (EGLint *) 0;

    if (!value_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "value == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(value_ref) - offset;
    if (_remaining < 1) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < 1 < needed";
        goto exit;
    }
    value_base = (EGLint *)
        _env->GetPrimitiveArrayCritical(value_ref, (jboolean *)0);
    value = value_base + offset;

    _returnValue = eglGetConfigAttrib(
        (EGLDisplay)dpy_native,
        (EGLConfig)config_native,
        (EGLint)attribute,
        (EGLint *)value
    );

exit:
    if (value_base) {
        _env->ReleasePrimitiveArrayCritical(value_ref, value_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
    return (jboolean)_returnValue;
}

/* EGLSurface eglCreateWindowSurface ( EGLDisplay dpy, EGLConfig config, EGLNativeWindowType win, const EGLint *attrib_list ) */
static jobject
android_eglCreateWindowSurface
  (JNIEnv *_env, jobject _this, jobject dpy, jobject config, jobject win, jintArray attrib_list_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = "";
    const char * _exceptionMessage = "";
    EGLSurface _returnValue = (EGLSurface) 0;
    EGLDisplay dpy_native = (EGLDisplay) fromEGLHandle(_env, egldisplayGetHandleID, dpy);
    EGLConfig config_native = (EGLConfig) fromEGLHandle(_env, eglconfigGetHandleID, config);
    int attrib_list_sentinel = 0;
    EGLint *attrib_list_base = (EGLint *) 0;
    jint _remaining;
    EGLint *attrib_list = (EGLint *) 0;
    android::sp<ANativeWindow> window;

    if (!attrib_list_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "attrib_list == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    if (win == NULL) {
not_valid_surface:
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "Make sure the SurfaceView or associated SurfaceHolder has a valid Surface";
        goto exit;
    }

    window = android::android_view_Surface_getNativeWindow(_env, win);

    if (window == NULL)
        goto not_valid_surface;

    _remaining = _env->GetArrayLength(attrib_list_ref) - offset;
    attrib_list_base = (EGLint *)
        _env->GetPrimitiveArrayCritical(attrib_list_ref, (jboolean *)0);
    attrib_list = attrib_list_base + offset;
    attrib_list_sentinel = 0;
    for (int i = _remaining - 1; i >= 0; i--)  {
        if (*((EGLint*)(attrib_list + i)) == EGL_NONE){
            attrib_list_sentinel = 1;
            break;
        }
    }
    if (attrib_list_sentinel == 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "attrib_list must contain EGL_NONE!";
        goto exit;
    }

    _returnValue = eglCreateWindowSurface(
        (EGLDisplay)dpy_native,
        (EGLConfig)config_native,
        (EGLNativeWindowType)window.get(),
        (EGLint *)attrib_list
    );

exit:
    if (attrib_list_base) {
        _env->ReleasePrimitiveArrayCritical(attrib_list_ref, attrib_list_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
    return toEGLHandle(_env, eglsurfaceClass, eglsurfaceConstructor, _returnValue);
}

/* EGLSurface eglCreateWindowSurface ( EGLDisplay dpy, EGLConfig config, EGLNativeWindowType win, const EGLint *attrib_list ) */
static jobject
android_eglCreateWindowSurfaceTexture
  (JNIEnv *_env, jobject _this, jobject dpy, jobject config, jobject win, jintArray attrib_list_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = "";
    const char * _exceptionMessage = "";
    EGLSurface _returnValue = (EGLSurface) 0;
    EGLDisplay dpy_native = (EGLDisplay) fromEGLHandle(_env, egldisplayGetHandleID, dpy);
    EGLConfig config_native = (EGLConfig) fromEGLHandle(_env, eglconfigGetHandleID, config);
    int attrib_list_sentinel = 0;
    EGLint *attrib_list_base = (EGLint *) 0;
    jint _remaining;
    EGLint *attrib_list = (EGLint *) 0;
    android::sp<ANativeWindow> window;
    android::sp<android::IGraphicBufferProducer> producer;

    if (!attrib_list_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "attrib_list == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    if (win == NULL) {
not_valid_surface:
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "Make sure the SurfaceView or associated SurfaceHolder has a valid Surface";
        goto exit;
    }
    producer = android::SurfaceTexture_getProducer(_env, win);

    if (producer == NULL)
        goto not_valid_surface;

    window = new android::Surface(producer, true);

    if (window == NULL)
        goto not_valid_surface;

    _remaining = _env->GetArrayLength(attrib_list_ref) - offset;
    attrib_list_base = (EGLint *)
        _env->GetPrimitiveArrayCritical(attrib_list_ref, (jboolean *)0);
    attrib_list = attrib_list_base + offset;
    attrib_list_sentinel = 0;
    for (int i = _remaining - 1; i >= 0; i--)  {
        if (*((EGLint*)(attrib_list + i)) == EGL_NONE){
            attrib_list_sentinel = 1;
            break;
        }
    }
    if (attrib_list_sentinel == 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "attrib_list must contain EGL_NONE!";
        goto exit;
    }

    _returnValue = eglCreateWindowSurface(
        (EGLDisplay)dpy_native,
        (EGLConfig)config_native,
        (EGLNativeWindowType)window.get(),
        (EGLint *)attrib_list
    );

exit:
    if (attrib_list_base) {
        _env->ReleasePrimitiveArrayCritical(attrib_list_ref, attrib_list_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
    return toEGLHandle(_env, eglsurfaceClass, eglsurfaceConstructor, _returnValue);
}
/* EGLSurface eglCreatePbufferSurface ( EGLDisplay dpy, EGLConfig config, const EGLint *attrib_list ) */
static jobject
android_eglCreatePbufferSurface
  (JNIEnv *_env, jobject _this, jobject dpy, jobject config, jintArray attrib_list_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    EGLSurface _returnValue = (EGLSurface) 0;
    EGLDisplay dpy_native = (EGLDisplay) fromEGLHandle(_env, egldisplayGetHandleID, dpy);
    EGLConfig config_native = (EGLConfig) fromEGLHandle(_env, eglconfigGetHandleID, config);
    bool attrib_list_sentinel = false;
    EGLint *attrib_list_base = (EGLint *) 0;
    jint _remaining;
    EGLint *attrib_list = (EGLint *) 0;

    if (!attrib_list_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "attrib_list == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(attrib_list_ref) - offset;
    attrib_list_base = (EGLint *)
        _env->GetPrimitiveArrayCritical(attrib_list_ref, (jboolean *)0);
    attrib_list = attrib_list_base + offset;
    attrib_list_sentinel = false;
    for (int i = _remaining - 1; i >= 0; i--)  {
        if (attrib_list[i] == EGL_NONE){
            attrib_list_sentinel = true;
            break;
        }
    }
    if (attrib_list_sentinel == false) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "attrib_list must contain EGL_NONE!";
        goto exit;
    }

    _returnValue = eglCreatePbufferSurface(
        (EGLDisplay)dpy_native,
        (EGLConfig)config_native,
        (EGLint *)attrib_list
    );

exit:
    if (attrib_list_base) {
        _env->ReleasePrimitiveArrayCritical(attrib_list_ref, attrib_list_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
    return toEGLHandle(_env, eglsurfaceClass, eglsurfaceConstructor, _returnValue);
}

/* EGLSurface eglCreatePixmapSurface ( EGLDisplay dpy, EGLConfig config, EGLNativePixmapType pixmap, const EGLint *attrib_list ) */
static jobject
android_eglCreatePixmapSurface
  (JNIEnv *_env, jobject _this, jobject dpy, jobject config, jint pixmap, jintArray attrib_list_ref, jint offset) {
    jniThrowException(_env, "java/lang/UnsupportedOperationException",
        "eglCreatePixmapSurface");
    return toEGLHandle(_env, eglsurfaceClass, eglsurfaceConstructor, (EGLSurface) 0);
}

/* EGLBoolean eglDestroySurface ( EGLDisplay dpy, EGLSurface surface ) */
static jboolean
android_eglDestroySurface
  (JNIEnv *_env, jobject _this, jobject dpy, jobject surface) {
    EGLBoolean _returnValue = (EGLBoolean) 0;
    EGLDisplay dpy_native = (EGLDisplay) fromEGLHandle(_env, egldisplayGetHandleID, dpy);
    EGLSurface surface_native = (EGLSurface) fromEGLHandle(_env, eglsurfaceGetHandleID, surface);

    _returnValue = eglDestroySurface(
        (EGLDisplay)dpy_native,
        (EGLSurface)surface_native
    );
    return (jboolean)_returnValue;
}

/* EGLBoolean eglQuerySurface ( EGLDisplay dpy, EGLSurface surface, EGLint attribute, EGLint *value ) */
static jboolean
android_eglQuerySurface
  (JNIEnv *_env, jobject _this, jobject dpy, jobject surface, jint attribute, jintArray value_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    EGLBoolean _returnValue = (EGLBoolean) 0;
    EGLDisplay dpy_native = (EGLDisplay) fromEGLHandle(_env, egldisplayGetHandleID, dpy);
    EGLSurface surface_native = (EGLSurface) fromEGLHandle(_env, eglsurfaceGetHandleID, surface);
    EGLint *value_base = (EGLint *) 0;
    jint _remaining;
    EGLint *value = (EGLint *) 0;

    if (!value_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "value == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(value_ref) - offset;
    if (_remaining < 1) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < 1 < needed";
        goto exit;
    }
    value_base = (EGLint *)
        _env->GetPrimitiveArrayCritical(value_ref, (jboolean *)0);
    value = value_base + offset;

    _returnValue = eglQuerySurface(
        (EGLDisplay)dpy_native,
        (EGLSurface)surface_native,
        (EGLint)attribute,
        (EGLint *)value
    );

exit:
    if (value_base) {
        _env->ReleasePrimitiveArrayCritical(value_ref, value_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
    return (jboolean)_returnValue;
}

/* EGLBoolean eglBindAPI ( EGLenum api ) */
static jboolean
android_eglBindAPI
  (JNIEnv *_env, jobject _this, jint api) {
    EGLBoolean _returnValue = (EGLBoolean) 0;
    _returnValue = eglBindAPI(
        (EGLenum)api
    );
    return (jboolean)_returnValue;
}

/* EGLenum eglQueryAPI ( void ) */
static jint
android_eglQueryAPI
  (JNIEnv *_env, jobject _this) {
    EGLenum _returnValue = (EGLenum) 0;
    _returnValue = eglQueryAPI();
    return (jint)_returnValue;
}

/* EGLBoolean eglWaitClient ( void ) */
static jboolean
android_eglWaitClient
  (JNIEnv *_env, jobject _this) {
    EGLBoolean _returnValue = (EGLBoolean) 0;
    _returnValue = eglWaitClient();
    return (jboolean)_returnValue;
}

/* EGLBoolean eglReleaseThread ( void ) */
static jboolean
android_eglReleaseThread
  (JNIEnv *_env, jobject _this) {
    EGLBoolean _returnValue = (EGLBoolean) 0;
    _returnValue = eglReleaseThread();
    return (jboolean)_returnValue;
}

/* EGLSurface eglCreatePbufferFromClientBuffer ( EGLDisplay dpy, EGLenum buftype, EGLClientBuffer buffer, EGLConfig config, const EGLint *attrib_list ) */
static jobject
android_eglCreatePbufferFromClientBuffer
  (JNIEnv *_env, jobject _this, jobject dpy, jint buftype, jlong buffer, jobject config, jintArray attrib_list_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    EGLSurface _returnValue = (EGLSurface) 0;
    EGLDisplay dpy_native = (EGLDisplay) fromEGLHandle(_env, egldisplayGetHandleID, dpy);
    EGLConfig config_native = (EGLConfig) fromEGLHandle(_env, eglconfigGetHandleID, config);
    bool attrib_list_sentinel = false;
    EGLint *attrib_list_base = (EGLint *) 0;
    jint _remaining;
    EGLint *attrib_list = (EGLint *) 0;

    if (!attrib_list_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "attrib_list == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(attrib_list_ref) - offset;
    attrib_list_base = (EGLint *)
        _env->GetPrimitiveArrayCritical(attrib_list_ref, (jboolean *)0);
    attrib_list = attrib_list_base + offset;
    attrib_list_sentinel = false;
    for (int i = _remaining - 1; i >= 0; i--)  {
        if (attrib_list[i] == EGL_NONE){
            attrib_list_sentinel = true;
            break;
        }
    }
    if (attrib_list_sentinel == false) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "attrib_list must contain EGL_NONE!";
        goto exit;
    }

    _returnValue = eglCreatePbufferFromClientBuffer(
        (EGLDisplay)dpy_native,
        (EGLenum)buftype,
        reinterpret_cast<EGLClientBuffer>(buffer),
        (EGLConfig)config_native,
        (EGLint *)attrib_list
    );

exit:
    if (attrib_list_base) {
        _env->ReleasePrimitiveArrayCritical(attrib_list_ref, attrib_list_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
    return toEGLHandle(_env, eglsurfaceClass, eglsurfaceConstructor, _returnValue);
}

static jobject
android_eglCreatePbufferFromClientBufferInt
  (JNIEnv *_env, jobject _this, jobject dpy, jint buftype, jint buffer, jobject config, jintArray attrib_list_ref, jint offset) {
    if(sizeof(void*) != sizeof(uint32_t)) {
        jniThrowException(_env, "java/lang/UnsupportedOperationException", "eglCreatePbufferFromClientBuffer");
        return 0;
    }
    return android_eglCreatePbufferFromClientBuffer(_env, _this, dpy, buftype, buffer, config, attrib_list_ref, offset);
}

/* EGLBoolean eglSurfaceAttrib ( EGLDisplay dpy, EGLSurface surface, EGLint attribute, EGLint value ) */
static jboolean
android_eglSurfaceAttrib
  (JNIEnv *_env, jobject _this, jobject dpy, jobject surface, jint attribute, jint value) {
    EGLBoolean _returnValue = (EGLBoolean) 0;
    EGLDisplay dpy_native = (EGLDisplay) fromEGLHandle(_env, egldisplayGetHandleID, dpy);
    EGLSurface surface_native = (EGLSurface) fromEGLHandle(_env, eglsurfaceGetHandleID, surface);

    _returnValue = eglSurfaceAttrib(
        (EGLDisplay)dpy_native,
        (EGLSurface)surface_native,
        (EGLint)attribute,
        (EGLint)value
    );
    return (jboolean)_returnValue;
}

/* EGLBoolean eglBindTexImage ( EGLDisplay dpy, EGLSurface surface, EGLint buffer ) */
static jboolean
android_eglBindTexImage
  (JNIEnv *_env, jobject _this, jobject dpy, jobject surface, jint buffer) {
    EGLBoolean _returnValue = (EGLBoolean) 0;
    EGLDisplay dpy_native = (EGLDisplay) fromEGLHandle(_env, egldisplayGetHandleID, dpy);
    EGLSurface surface_native = (EGLSurface) fromEGLHandle(_env, eglsurfaceGetHandleID, surface);

    _returnValue = eglBindTexImage(
        (EGLDisplay)dpy_native,
        (EGLSurface)surface_native,
        (EGLint)buffer
    );
    return (jboolean)_returnValue;
}

/* EGLBoolean eglReleaseTexImage ( EGLDisplay dpy, EGLSurface surface, EGLint buffer ) */
static jboolean
android_eglReleaseTexImage
  (JNIEnv *_env, jobject _this, jobject dpy, jobject surface, jint buffer) {
    EGLBoolean _returnValue = (EGLBoolean) 0;
    EGLDisplay dpy_native = (EGLDisplay) fromEGLHandle(_env, egldisplayGetHandleID, dpy);
    EGLSurface surface_native = (EGLSurface) fromEGLHandle(_env, eglsurfaceGetHandleID, surface);

    _returnValue = eglReleaseTexImage(
        (EGLDisplay)dpy_native,
        (EGLSurface)surface_native,
        (EGLint)buffer
    );
    return (jboolean)_returnValue;
}

/* EGLBoolean eglSwapInterval ( EGLDisplay dpy, EGLint interval ) */
static jboolean
android_eglSwapInterval
  (JNIEnv *_env, jobject _this, jobject dpy, jint interval) {
    EGLBoolean _returnValue = (EGLBoolean) 0;
    EGLDisplay dpy_native = (EGLDisplay) fromEGLHandle(_env, egldisplayGetHandleID, dpy);

    _returnValue = eglSwapInterval(
        (EGLDisplay)dpy_native,
        (EGLint)interval
    );
    return (jboolean)_returnValue;
}

/* EGLContext eglCreateContext ( EGLDisplay dpy, EGLConfig config, EGLContext share_context, const EGLint *attrib_list ) */
static jobject
android_eglCreateContext
  (JNIEnv *_env, jobject _this, jobject dpy, jobject config, jobject share_context, jintArray attrib_list_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    EGLContext _returnValue = (EGLContext) 0;
    EGLDisplay dpy_native = (EGLDisplay) fromEGLHandle(_env, egldisplayGetHandleID, dpy);
    EGLConfig config_native = (EGLConfig) fromEGLHandle(_env, eglconfigGetHandleID, config);
    EGLContext share_context_native = (EGLContext) fromEGLHandle(_env, eglcontextGetHandleID, share_context);
    bool attrib_list_sentinel = false;
    EGLint *attrib_list_base = (EGLint *) 0;
    jint _remaining;
    EGLint *attrib_list = (EGLint *) 0;

    if (!attrib_list_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "attrib_list == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(attrib_list_ref) - offset;
    attrib_list_base = (EGLint *)
        _env->GetPrimitiveArrayCritical(attrib_list_ref, (jboolean *)0);
    attrib_list = attrib_list_base + offset;
    attrib_list_sentinel = false;
    for (int i = _remaining - 1; i >= 0; i--)  {
        if (attrib_list[i] == EGL_NONE){
            attrib_list_sentinel = true;
            break;
        }
    }
    if (attrib_list_sentinel == false) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "attrib_list must contain EGL_NONE!";
        goto exit;
    }

    _returnValue = eglCreateContext(
        (EGLDisplay)dpy_native,
        (EGLConfig)config_native,
        (EGLContext)share_context_native,
        (EGLint *)attrib_list
    );

exit:
    if (attrib_list_base) {
        _env->ReleasePrimitiveArrayCritical(attrib_list_ref, attrib_list_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
    return toEGLHandle(_env, eglcontextClass, eglcontextConstructor, _returnValue);
}

/* EGLBoolean eglDestroyContext ( EGLDisplay dpy, EGLContext ctx ) */
static jboolean
android_eglDestroyContext
  (JNIEnv *_env, jobject _this, jobject dpy, jobject ctx) {
    EGLBoolean _returnValue = (EGLBoolean) 0;
    EGLDisplay dpy_native = (EGLDisplay) fromEGLHandle(_env, egldisplayGetHandleID, dpy);
    EGLContext ctx_native = (EGLContext) fromEGLHandle(_env, eglcontextGetHandleID, ctx);

    _returnValue = eglDestroyContext(
        (EGLDisplay)dpy_native,
        (EGLContext)ctx_native
    );
    return (jboolean)_returnValue;
}

/* EGLBoolean eglMakeCurrent ( EGLDisplay dpy, EGLSurface draw, EGLSurface read, EGLContext ctx ) */
static jboolean
android_eglMakeCurrent
  (JNIEnv *_env, jobject _this, jobject dpy, jobject draw, jobject read, jobject ctx) {
    EGLBoolean _returnValue = (EGLBoolean) 0;
    EGLDisplay dpy_native = (EGLDisplay) fromEGLHandle(_env, egldisplayGetHandleID, dpy);
    EGLSurface draw_native = (EGLSurface) fromEGLHandle(_env, eglsurfaceGetHandleID, draw);
    EGLSurface read_native = (EGLSurface) fromEGLHandle(_env, eglsurfaceGetHandleID, read);
    EGLContext ctx_native = (EGLContext) fromEGLHandle(_env, eglcontextGetHandleID, ctx);

    _returnValue = eglMakeCurrent(
        (EGLDisplay)dpy_native,
        (EGLSurface)draw_native,
        (EGLSurface)read_native,
        (EGLContext)ctx_native
    );
    return (jboolean)_returnValue;
}

/* EGLContext eglGetCurrentContext ( void ) */
static jobject
android_eglGetCurrentContext
  (JNIEnv *_env, jobject _this) {
    EGLContext _returnValue = (EGLContext) 0;
    _returnValue = eglGetCurrentContext();
    return toEGLHandle(_env, eglcontextClass, eglcontextConstructor, _returnValue);
}

/* EGLSurface eglGetCurrentSurface ( EGLint readdraw ) */
static jobject
android_eglGetCurrentSurface
  (JNIEnv *_env, jobject _this, jint readdraw) {
    EGLSurface _returnValue = (EGLSurface) 0;
    _returnValue = eglGetCurrentSurface(
        (EGLint)readdraw
    );
    return toEGLHandle(_env, eglsurfaceClass, eglsurfaceConstructor, _returnValue);
}

/* EGLDisplay eglGetCurrentDisplay ( void ) */
static jobject
android_eglGetCurrentDisplay
  (JNIEnv *_env, jobject _this) {
    EGLDisplay _returnValue = (EGLDisplay) 0;
    _returnValue = eglGetCurrentDisplay();
    return toEGLHandle(_env, egldisplayClass, egldisplayConstructor, _returnValue);
}

/* EGLBoolean eglQueryContext ( EGLDisplay dpy, EGLContext ctx, EGLint attribute, EGLint *value ) */
static jboolean
android_eglQueryContext
  (JNIEnv *_env, jobject _this, jobject dpy, jobject ctx, jint attribute, jintArray value_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    EGLBoolean _returnValue = (EGLBoolean) 0;
    EGLDisplay dpy_native = (EGLDisplay) fromEGLHandle(_env, egldisplayGetHandleID, dpy);
    EGLContext ctx_native = (EGLContext) fromEGLHandle(_env, eglcontextGetHandleID, ctx);
    EGLint *value_base = (EGLint *) 0;
    jint _remaining;
    EGLint *value = (EGLint *) 0;

    if (!value_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "value == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(value_ref) - offset;
    if (_remaining < 1) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < 1 < needed";
        goto exit;
    }
    value_base = (EGLint *)
        _env->GetPrimitiveArrayCritical(value_ref, (jboolean *)0);
    value = value_base + offset;

    _returnValue = eglQueryContext(
        (EGLDisplay)dpy_native,
        (EGLContext)ctx_native,
        (EGLint)attribute,
        (EGLint *)value
    );

exit:
    if (value_base) {
        _env->ReleasePrimitiveArrayCritical(value_ref, value_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
    return (jboolean)_returnValue;
}

/* EGLBoolean eglWaitGL ( void ) */
static jboolean
android_eglWaitGL
  (JNIEnv *_env, jobject _this) {
    EGLBoolean _returnValue = (EGLBoolean) 0;
    _returnValue = eglWaitGL();
    return (jboolean)_returnValue;
}

/* EGLBoolean eglWaitNative ( EGLint engine ) */
static jboolean
android_eglWaitNative
  (JNIEnv *_env, jobject _this, jint engine) {
    EGLBoolean _returnValue = (EGLBoolean) 0;
    _returnValue = eglWaitNative(
        (EGLint)engine
    );
    return (jboolean)_returnValue;
}

/* EGLBoolean eglSwapBuffers ( EGLDisplay dpy, EGLSurface surface ) */
static jboolean
android_eglSwapBuffers
  (JNIEnv *_env, jobject _this, jobject dpy, jobject surface) {
    EGLBoolean _returnValue = (EGLBoolean) 0;
    EGLDisplay dpy_native = (EGLDisplay) fromEGLHandle(_env, egldisplayGetHandleID, dpy);
    EGLSurface surface_native = (EGLSurface) fromEGLHandle(_env, eglsurfaceGetHandleID, surface);

    _returnValue = eglSwapBuffers(
        (EGLDisplay)dpy_native,
        (EGLSurface)surface_native
    );
    return (jboolean)_returnValue;
}

/* EGLBoolean eglCopyBuffers ( EGLDisplay dpy, EGLSurface surface, EGLNativePixmapType target ) */
static jboolean
android_eglCopyBuffers
  (JNIEnv *_env, jobject _this, jobject dpy, jobject surface, jint target) {
    jniThrowException(_env, "java/lang/UnsupportedOperationException",
        "eglCopyBuffers");
    return (EGLBoolean) 0;
}

static const char *classPathName = "android/opengl/EGL14";

static JNINativeMethod methods[] = {
{"_nativeClassInit", "()V", (void*)nativeClassInit },
{"eglGetError", "()I", (void *) android_eglGetError },
{"eglGetDisplay", "(I)Landroid/opengl/EGLDisplay;", (void *) android_eglGetDisplayInt },
{"eglGetDisplay", "(J)Landroid/opengl/EGLDisplay;", (void *) android_eglGetDisplay },
{"eglInitialize", "(Landroid/opengl/EGLDisplay;[II[II)Z", (void *) android_eglInitialize },
{"eglTerminate", "(Landroid/opengl/EGLDisplay;)Z", (void *) android_eglTerminate },
{"eglQueryString", "(Landroid/opengl/EGLDisplay;I)Ljava/lang/String;", (void *) android_eglQueryString__Landroind_opengl_EGLDisplay_2I },
{"eglGetConfigs", "(Landroid/opengl/EGLDisplay;[Landroid/opengl/EGLConfig;II[II)Z", (void *) android_eglGetConfigs },
{"eglChooseConfig", "(Landroid/opengl/EGLDisplay;[II[Landroid/opengl/EGLConfig;II[II)Z", (void *) android_eglChooseConfig },
{"eglGetConfigAttrib", "(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLConfig;I[II)Z", (void *) android_eglGetConfigAttrib },
{"_eglCreateWindowSurface", "(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLConfig;Ljava/lang/Object;[II)Landroid/opengl/EGLSurface;", (void *) android_eglCreateWindowSurface },
{"_eglCreateWindowSurfaceTexture", "(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLConfig;Ljava/lang/Object;[II)Landroid/opengl/EGLSurface;", (void *) android_eglCreateWindowSurfaceTexture },
{"eglCreatePbufferSurface", "(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLConfig;[II)Landroid/opengl/EGLSurface;", (void *) android_eglCreatePbufferSurface },
{"eglCreatePixmapSurface", "(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLConfig;I[II)Landroid/opengl/EGLSurface;", (void *) android_eglCreatePixmapSurface },
{"eglDestroySurface", "(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLSurface;)Z", (void *) android_eglDestroySurface },
{"eglQuerySurface", "(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLSurface;I[II)Z", (void *) android_eglQuerySurface },
{"eglBindAPI", "(I)Z", (void *) android_eglBindAPI },
{"eglQueryAPI", "()I", (void *) android_eglQueryAPI },
{"eglWaitClient", "()Z", (void *) android_eglWaitClient },
{"eglReleaseThread", "()Z", (void *) android_eglReleaseThread },
{"eglCreatePbufferFromClientBuffer", "(Landroid/opengl/EGLDisplay;IILandroid/opengl/EGLConfig;[II)Landroid/opengl/EGLSurface;", (void *) android_eglCreatePbufferFromClientBufferInt },
{"eglCreatePbufferFromClientBuffer", "(Landroid/opengl/EGLDisplay;IJLandroid/opengl/EGLConfig;[II)Landroid/opengl/EGLSurface;", (void *) android_eglCreatePbufferFromClientBuffer },
{"eglSurfaceAttrib", "(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLSurface;II)Z", (void *) android_eglSurfaceAttrib },
{"eglBindTexImage", "(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLSurface;I)Z", (void *) android_eglBindTexImage },
{"eglReleaseTexImage", "(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLSurface;I)Z", (void *) android_eglReleaseTexImage },
{"eglSwapInterval", "(Landroid/opengl/EGLDisplay;I)Z", (void *) android_eglSwapInterval },
{"eglCreateContext", "(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLConfig;Landroid/opengl/EGLContext;[II)Landroid/opengl/EGLContext;", (void *) android_eglCreateContext },
{"eglDestroyContext", "(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLContext;)Z", (void *) android_eglDestroyContext },
{"eglMakeCurrent", "(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLSurface;Landroid/opengl/EGLSurface;Landroid/opengl/EGLContext;)Z", (void *) android_eglMakeCurrent },
{"eglGetCurrentContext", "()Landroid/opengl/EGLContext;", (void *) android_eglGetCurrentContext },
{"eglGetCurrentSurface", "(I)Landroid/opengl/EGLSurface;", (void *) android_eglGetCurrentSurface },
{"eglGetCurrentDisplay", "()Landroid/opengl/EGLDisplay;", (void *) android_eglGetCurrentDisplay },
{"eglQueryContext", "(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLContext;I[II)Z", (void *) android_eglQueryContext },
{"eglWaitGL", "()Z", (void *) android_eglWaitGL },
{"eglWaitNative", "(I)Z", (void *) android_eglWaitNative },
{"eglSwapBuffers", "(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLSurface;)Z", (void *) android_eglSwapBuffers },
{"eglCopyBuffers", "(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLSurface;I)Z", (void *) android_eglCopyBuffers },
};

int register_android_opengl_jni_EGL14(JNIEnv *_env)
{
    int err;
    err = android::AndroidRuntime::registerNativeMethods(_env, classPathName, methods, NELEM(methods));
    return err;
}
