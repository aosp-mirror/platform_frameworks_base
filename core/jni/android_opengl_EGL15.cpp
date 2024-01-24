/*
** Copyright 2018, The Android Open Source Project
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

#pragma GCC diagnostic ignored "-Wunused-variable"
#pragma GCC diagnostic ignored "-Wunused-but-set-variable"
#pragma GCC diagnostic ignored "-Wunused-function"

#include "jni.h"
#include <nativehelper/JNIPlatformHelp.h>
#include <android_runtime/AndroidRuntime.h>
#include <utils/misc.h>

#include <assert.h>
#include <vector>
#include <EGL/egl.h>

#include <ui/ANativeObjectBase.h>

// classes from EGL 1.4
static jclass egldisplayClass;
static jclass eglsurfaceClass;
static jclass eglconfigClass;
static jclass eglcontextClass;

static jmethodID egldisplayGetHandleID;
static jmethodID eglconfigGetHandleID;
static jmethodID eglcontextGetHandleID;
static jmethodID eglsurfaceGetHandleID;

static jmethodID egldisplayConstructor;
static jmethodID eglcontextConstructor;
static jmethodID eglsurfaceConstructor;
static jmethodID eglconfigConstructor;

static jobject eglNoContextObject;
static jobject eglNoDisplayObject;
static jobject eglNoSurfaceObject;

// classes from EGL 1.5
static jclass eglimageClass;
static jclass eglsyncClass;

static jmethodID eglimageGetHandleID;
static jmethodID eglsyncGetHandleID;

static jmethodID eglimageConstructor;
static jmethodID eglsyncConstructor;

static jobject eglNoImageObject;
static jobject eglNoSyncObject;

/* Cache method IDs each time the class is loaded. */

static void
nativeClassInit(JNIEnv *_env, jclass glImplClass)
{
    // EGL 1.4 Init
    jclass eglconfigClassLocal = _env->FindClass("android/opengl/EGLConfig");
    eglconfigClass = (jclass) _env->NewGlobalRef(eglconfigClassLocal);
    jclass eglcontextClassLocal = _env->FindClass("android/opengl/EGLContext");
    eglcontextClass = (jclass) _env->NewGlobalRef(eglcontextClassLocal);
    jclass egldisplayClassLocal = _env->FindClass("android/opengl/EGLDisplay");
    egldisplayClass = (jclass) _env->NewGlobalRef(egldisplayClassLocal);
    jclass eglsurfaceClassLocal = _env->FindClass("android/opengl/EGLSurface");
    eglsurfaceClass = (jclass) _env->NewGlobalRef(eglsurfaceClassLocal);

    eglconfigGetHandleID = _env->GetMethodID(eglconfigClass, "getNativeHandle", "()J");
    eglcontextGetHandleID = _env->GetMethodID(eglcontextClass, "getNativeHandle", "()J");
    egldisplayGetHandleID = _env->GetMethodID(egldisplayClass, "getNativeHandle", "()J");
    eglsurfaceGetHandleID = _env->GetMethodID(eglsurfaceClass, "getNativeHandle", "()J");

    eglconfigConstructor = _env->GetMethodID(eglconfigClass, "<init>", "(J)V");
    eglcontextConstructor = _env->GetMethodID(eglcontextClass, "<init>", "(J)V");
    egldisplayConstructor = _env->GetMethodID(egldisplayClass, "<init>", "(J)V");
    eglsurfaceConstructor = _env->GetMethodID(eglsurfaceClass, "<init>", "(J)V");

    jobject localeglNoContextObject = _env->NewObject(eglcontextClass, eglcontextConstructor, reinterpret_cast<jlong>(EGL_NO_CONTEXT));
    eglNoContextObject = _env->NewGlobalRef(localeglNoContextObject);
    jobject localeglNoDisplayObject = _env->NewObject(egldisplayClass, egldisplayConstructor, reinterpret_cast<jlong>(EGL_NO_DISPLAY));
    eglNoDisplayObject = _env->NewGlobalRef(localeglNoDisplayObject);
    jobject localeglNoSurfaceObject = _env->NewObject(eglsurfaceClass, eglsurfaceConstructor, reinterpret_cast<jlong>(EGL_NO_SURFACE));
    eglNoSurfaceObject = _env->NewGlobalRef(localeglNoSurfaceObject);

    jclass eglClass = _env->FindClass("android/opengl/EGL15");
    jfieldID noContextFieldID = _env->GetStaticFieldID(eglClass, "EGL_NO_CONTEXT", "Landroid/opengl/EGLContext;");
    _env->SetStaticObjectField(eglClass, noContextFieldID, eglNoContextObject);

    jfieldID noDisplayFieldID = _env->GetStaticFieldID(eglClass, "EGL_NO_DISPLAY", "Landroid/opengl/EGLDisplay;");
    _env->SetStaticObjectField(eglClass, noDisplayFieldID, eglNoDisplayObject);

    jfieldID noSurfaceFieldID = _env->GetStaticFieldID(eglClass, "EGL_NO_SURFACE", "Landroid/opengl/EGLSurface;");
    _env->SetStaticObjectField(eglClass, noSurfaceFieldID, eglNoSurfaceObject);

    // EGL 1.5 init
    jclass eglimageClassLocal = _env->FindClass("android/opengl/EGLImage");
    eglimageClass = (jclass) _env->NewGlobalRef(eglimageClassLocal);
    jclass eglsyncClassLocal = _env->FindClass("android/opengl/EGLSync");
    eglsyncClass = (jclass) _env->NewGlobalRef(eglsyncClassLocal);

    eglimageGetHandleID = _env->GetMethodID(eglimageClass, "getNativeHandle", "()J");
    eglsyncGetHandleID = _env->GetMethodID(eglsyncClass, "getNativeHandle", "()J");

    eglimageConstructor = _env->GetMethodID(eglimageClass, "<init>", "(J)V");
    eglsyncConstructor = _env->GetMethodID(eglsyncClass, "<init>", "(J)V");

    jfieldID noImageFieldID = _env->GetStaticFieldID(eglClass, "EGL_NO_IMAGE", "Landroid/opengl/EGLImage;");
    _env->SetStaticObjectField(eglClass, noImageFieldID, eglNoImageObject);

    jfieldID noSyncFieldID = _env->GetStaticFieldID(eglClass, "EGL_NO_SYNC", "Landroid/opengl/EGLSync;");
    _env->SetStaticObjectField(eglClass, noSyncFieldID, eglNoSyncObject);
}

static void *
getPointer(JNIEnv *_env, jobject buffer, jarray *array, jint *remaining, jint *offset)
{
    jint position;
    jint limit;
    jint elementSizeShift;
    jlong pointer;

    pointer = jniGetNioBufferFields(_env, buffer, &position, &limit, &elementSizeShift);
    *remaining = (limit - position) << elementSizeShift;
    if (pointer != 0L) {
        *array = nullptr;
        pointer += position << elementSizeShift;
        return reinterpret_cast<void*>(pointer);
    }

    *array = jniGetNioBufferBaseArray(_env, buffer);
    *offset = jniGetNioBufferBaseArrayOffset(_env, buffer);
    return nullptr;
}

static void
releasePointer(JNIEnv *_env, jarray array, void *data, jboolean commit)
{
    _env->ReleasePrimitiveArrayCritical(array, data,
                       commit ? 0 : JNI_ABORT);
}

static void *
fromEGLHandle(JNIEnv *_env, jmethodID mid, jobject obj) {
    if (obj == NULL) {
        jniThrowException(_env, "java/lang/IllegalArgumentException",
                          "Object is set to null.");
        return nullptr;
    }

    jlong handle = _env->CallLongMethod(obj, mid);
    return reinterpret_cast<void*>(handle);
}

static jobject
toEGLHandle(JNIEnv *_env, jclass cls, jmethodID con, void *handle) {
    if (cls == eglimageClass && (EGLImage)handle == EGL_NO_IMAGE) {
        return eglNoImageObject;
    }

    return _env->NewObject(cls, con, reinterpret_cast<jlong>(handle));
}

struct WrappedEGLAttribs {
private:
    std::vector<EGLAttrib> backing; // only for 32-bit
public:
    EGLAttrib *attribs;
    WrappedEGLAttribs(): attribs(nullptr) { };
    void init(jlong *array, jint size) {
        if (sizeof(EGLAttrib) != sizeof(jlong)) {
            for (jint i = 0; i < size; ++i) {
                backing.push_back(array[i]);
            }
            attribs = backing.data();
        } else {
            attribs = (EGLAttrib*)array;
        }
    }
};

// --------------------------------------------------------------------------
/* EGLSync eglCreateSync ( EGLDisplay dpy, EGLenum type, const EGLAttrib *attrib_list ) */
static jobject
android_eglCreateSync
  (JNIEnv *_env, jobject _this, jobject dpy, jint type, jlongArray attrib_list_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    EGLSync _returnValue = (EGLSync) 0;
    EGLDisplay dpy_native = (EGLDisplay) fromEGLHandle(_env, egldisplayGetHandleID, dpy);
    jlong *attrib_list_base = (jlong *) 0;
    jint _remaining;
    WrappedEGLAttribs attrib_list;

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
    attrib_list_base = (jlong *)
        _env->GetLongArrayElements(attrib_list_ref, (jboolean *)0);
    attrib_list.init(attrib_list_base + offset, _remaining);

    _returnValue = eglCreateSync(
        (EGLDisplay)dpy_native,
        (EGLenum)type,
        attrib_list.attribs
    );

exit:
    if (attrib_list_base) {
        _env->ReleaseLongArrayElements(attrib_list_ref, (jlong*)attrib_list_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
        return nullptr;
    }
    return toEGLHandle(_env, eglsyncClass, eglsyncConstructor, _returnValue);
}

/* EGLBoolean eglGetSyncAttrib ( EGLDisplay dpy, EGLSync sync, EGLint attribute, EGLAttrib *value ) */
static jboolean
android_eglGetSyncAttrib
  (JNIEnv *_env, jobject _this, jobject dpy, jobject sync, jint attribute, jlongArray value_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    EGLBoolean _returnValue = (EGLBoolean) 0;
    EGLDisplay dpy_native = (EGLDisplay) fromEGLHandle(_env, egldisplayGetHandleID, dpy);
    EGLSync sync_native = (EGLSync) fromEGLHandle(_env, eglsyncGetHandleID, sync);
    jlong *value_base = (jlong *) 0;
    jint _remaining;
    EGLAttrib value;

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
    value_base = (jlong *)
        _env->GetLongArrayElements(value_ref, (jboolean *)0);

    _returnValue = eglGetSyncAttrib(
        (EGLDisplay)dpy_native,
        (EGLSync)sync_native,
        (EGLint)attribute,
        &value
    );

    if (value_base && _returnValue == EGL_TRUE) {
        *(value_base + offset) = (jlong) value;
    }

exit:
    if (value_base) {
        _env->ReleaseLongArrayElements(value_ref, (jlong*)value_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
        return JNI_FALSE;
    }
    return (jboolean)_returnValue;
}

/* EGLBoolean eglDestroySync ( EGLDisplay dpy, EGLSync sync ) */
static jboolean
android_eglDestroySync
  (JNIEnv *_env, jobject _this, jobject dpy, jobject sync) {
    EGLBoolean _returnValue = (EGLBoolean) 0;
    EGLDisplay dpy_native = (EGLDisplay) fromEGLHandle(_env, egldisplayGetHandleID, dpy);
    EGLSync sync_native = (EGLSync) fromEGLHandle(_env, eglsyncGetHandleID, sync);

    _returnValue = eglDestroySync(
        (EGLDisplay)dpy_native,
        (EGLSync)sync_native
    );
    return (jboolean)_returnValue;
}

/* EGLint eglClientWaitSync ( EGLDisplay dpy, EGLSync sync, EGLint flags, EGLTime timeout ) */
static jint
android_eglClientWaitSync
  (JNIEnv *_env, jobject _this, jobject dpy, jobject sync, jint flags, jlong timeout) {
    EGLint _returnValue = (EGLint) 0;
    EGLDisplay dpy_native = (EGLDisplay) fromEGLHandle(_env, egldisplayGetHandleID, dpy);
    EGLSync sync_native = (EGLSync) fromEGLHandle(_env, eglsyncGetHandleID, sync);

    _returnValue = eglClientWaitSync(
        (EGLDisplay)dpy_native,
        (EGLSync)sync_native,
        (EGLint)flags,
        (EGLTime)timeout
    );
    return (jint)_returnValue;
}

/* EGLDisplay eglGetPlatformDisplay ( EGLenum platform, EGLAttrib native_display, const EGLAttrib *attrib_list ) */
static jobject
android_eglGetPlatformDisplay
  (JNIEnv *_env, jobject _this, jint platform, jlong native_display, jlongArray attrib_list_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    EGLDisplay _returnValue = (EGLDisplay) 0;
    jlong *attrib_list_base = (jlong *) 0;
    jint _remaining;
    WrappedEGLAttribs attrib_list;

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
    attrib_list_base = (jlong *)
        _env->GetLongArrayElements(attrib_list_ref, (jboolean *)0);
    attrib_list.init(attrib_list_base + offset, _remaining);

    _returnValue = eglGetPlatformDisplay(
        (EGLenum)platform,
        (void *)native_display,
        attrib_list.attribs
    );

exit:
    if (attrib_list_base) {
        _env->ReleaseLongArrayElements(attrib_list_ref, (jlong*)attrib_list_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
        return nullptr;
    }
    return toEGLHandle(_env, egldisplayClass, egldisplayConstructor, _returnValue);
}

/* EGLSurface eglCreatePlatformWindowSurface ( EGLDisplay dpy, EGLConfig config, void *native_window, const EGLAttrib *attrib_list ) */
static jobject
android_eglCreatePlatformWindowSurface
  (JNIEnv *_env, jobject _this, jobject dpy, jobject config, jobject native_window_buf, jlongArray attrib_list_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    EGLSurface _returnValue = (EGLSurface) 0;
    EGLDisplay dpy_native = (EGLDisplay) fromEGLHandle(_env, egldisplayGetHandleID, dpy);
    EGLConfig config_native = (EGLConfig) fromEGLHandle(_env, eglconfigGetHandleID, config);
    jint _native_windowRemaining;
    void *native_window = (void *) 0;
    jlong *attrib_list_base = (jlong *) 0;
    jint _attrib_listRemaining;
    WrappedEGLAttribs attrib_list;

    if (!native_window_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "native_window == null";
        goto exit;
    }
    native_window = (void *)getPointer(_env, native_window_buf, (jarray*)&_array, &_native_windowRemaining, &_bufferOffset);
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
    _attrib_listRemaining = _env->GetArrayLength(attrib_list_ref) - offset;
    attrib_list_base = (jlong *)
        _env->GetLongArrayElements(attrib_list_ref, (jboolean *)0);
    attrib_list.init(attrib_list_base + offset, _attrib_listRemaining);

    if (native_window == NULL) {
        char * _native_windowBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        native_window = (void *) (_native_windowBase + _bufferOffset);
    }
    _returnValue = eglCreatePlatformWindowSurface(
        (EGLDisplay)dpy_native,
        (EGLConfig)config_native,
        (void *)native_window,
        attrib_list.attribs
    );

exit:
    if (attrib_list_base) {
        _env->ReleaseLongArrayElements(attrib_list_ref, (jlong*)attrib_list_base,
            JNI_ABORT);
    }
    if (_array) {
        releasePointer(_env, _array, native_window, _exception ? JNI_FALSE : JNI_TRUE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
        return nullptr;
    }
    return toEGLHandle(_env, eglsurfaceClass, eglsurfaceConstructor, _returnValue);
}

/* EGLSurface eglCreatePlatformPixmapSurface ( EGLDisplay dpy, EGLConfig config, void *native_pixmap, const EGLAttrib *attrib_list ) */
static jobject
android_eglCreatePlatformPixmapSurface
  (JNIEnv *_env, jobject _this, jobject dpy, jobject config, jobject native_pixmap_buf, jlongArray attrib_list_ref, jint offset) {
    jniThrowException(_env, "java/lang/UnsupportedOperationException",
        "eglCreatePlatformPixmapSurface");
        return nullptr;
}

/* EGLBoolean eglWaitSync ( EGLDisplay dpy, EGLSync sync, EGLint flags ) */
static jboolean
android_eglWaitSync
  (JNIEnv *_env, jobject _this, jobject dpy, jobject sync, jint flags) {
    EGLBoolean _returnValue = (EGLBoolean) 0;
    EGLDisplay dpy_native = (EGLDisplay) fromEGLHandle(_env, egldisplayGetHandleID, dpy);
    EGLSync sync_native = (EGLSync) fromEGLHandle(_env, eglsyncGetHandleID, sync);

    _returnValue = eglWaitSync(
        (EGLDisplay)dpy_native,
        (EGLSync)sync_native,
        (EGLint)flags
    );
    return (jboolean)_returnValue;
}

/* EGLImage eglCreateImage ( EGLDisplay dpy, EGLContext context, EGLenum target, EGLClientBuffer buffer, const EGLAttrib *attrib_list ) */
static jobject
android_eglCreateImage
  (JNIEnv *_env, jobject _this, jobject dpy, jobject context, jint target, jlong buffer, jlongArray attrib_list_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    EGLImage _returnValue = (EGLImage) 0;
    EGLDisplay dpy_native = (EGLDisplay) fromEGLHandle(_env, egldisplayGetHandleID, dpy);
    EGLContext context_native = (EGLContext) fromEGLHandle(_env, eglcontextGetHandleID, context);
    jlong *attrib_list_base = (jlong *) 0;
    jint _remaining;
    WrappedEGLAttribs attrib_list;

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
    attrib_list_base = (jlong *)
        _env->GetLongArrayElements(attrib_list_ref, (jboolean *)0);
    attrib_list.init(attrib_list_base + offset, _remaining);

    _returnValue = eglCreateImage(
        (EGLDisplay)dpy_native,
        (EGLContext)context_native,
        (EGLenum)target,
        (EGLClientBuffer)buffer,
        attrib_list.attribs
    );

exit:
    if (attrib_list_base) {
        _env->ReleaseLongArrayElements(attrib_list_ref, (jlong*)attrib_list_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
        return nullptr;
    }
    return toEGLHandle(_env, eglimageClass, eglimageConstructor, _returnValue);
}
/* EGLBoolean eglDestroyImage ( EGLDisplay dpy, EGLImage image ) */
static jboolean
android_eglDestroyImage
  (JNIEnv *_env, jobject _this, jobject dpy, jobject image) {
    EGLBoolean _returnValue = (EGLBoolean) 0;
    EGLDisplay dpy_native = (EGLDisplay) fromEGLHandle(_env, egldisplayGetHandleID, dpy);
    EGLImage image_native = (EGLImage) fromEGLHandle(_env, eglimageGetHandleID, image);

    _returnValue = eglDestroyImage(
        (EGLDisplay)dpy_native,
        (EGLImage)image_native
    );
    return (jboolean)_returnValue;
}

static const char *classPathName = "android/opengl/EGL15";

static const JNINativeMethod methods[] = {
{"_nativeClassInit", "()V", (void*)nativeClassInit },
{"eglCreateSync", "(Landroid/opengl/EGLDisplay;I[JI)Landroid/opengl/EGLSync;", (void *) android_eglCreateSync },
{"eglGetSyncAttrib", "(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLSync;I[JI)Z", (void *) android_eglGetSyncAttrib },
{"eglDestroySync", "(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLSync;)Z", (void *) android_eglDestroySync },
{"eglClientWaitSync", "(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLSync;IJ)I", (void *) android_eglClientWaitSync },
{"eglGetPlatformDisplay", "(IJ[JI)Landroid/opengl/EGLDisplay;", (void *) android_eglGetPlatformDisplay },
{"eglCreatePlatformWindowSurface", "(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLConfig;Ljava/nio/Buffer;[JI)Landroid/opengl/EGLSurface;", (void *) android_eglCreatePlatformWindowSurface },
{"eglCreatePlatformPixmapSurface", "(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLConfig;Ljava/nio/Buffer;[JI)Landroid/opengl/EGLSurface;", (void *) android_eglCreatePlatformPixmapSurface },
{"eglWaitSync", "(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLSync;I)Z", (void *) android_eglWaitSync },
{"eglCreateImage", "(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLContext;IJ[JI)Landroid/opengl/EGLImage;", (void *) android_eglCreateImage },
{"eglDestroyImage", "(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLImage;)Z", (void *) android_eglDestroyImage },
};

int register_android_opengl_jni_EGL15(JNIEnv *_env)
{
    int err;
    err = android::AndroidRuntime::registerNativeMethods(_env, classPathName, methods, NELEM(methods));
    return err;
}
