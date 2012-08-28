/*
**
** Copyright 2006, The Android Open Source Project
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

#include "JNIHelp.h"
#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/android_view_Surface.h>
#include <android_runtime/android_graphics_SurfaceTexture.h>
#include <utils/misc.h>


#include <EGL/egl_display.h>
#include <EGL/egl.h>
#include <GLES/gl.h>

#include <gui/Surface.h>
#include <gui/SurfaceTexture.h>
#include <gui/SurfaceTextureClient.h>

#include <SkBitmap.h>
#include <SkPixelRef.h>

#include <ui/ANativeObjectBase.h>

namespace android {

static jclass gConfig_class;

static jmethodID gConfig_ctorID;

static jfieldID gDisplay_EGLDisplayFieldID;
static jfieldID gContext_EGLContextFieldID;
static jfieldID gSurface_EGLSurfaceFieldID;
static jfieldID gSurface_NativePixelRefFieldID;
static jfieldID gConfig_EGLConfigFieldID;
static jfieldID gBitmap_NativeBitmapFieldID;

static inline EGLDisplay getDisplay(JNIEnv* env, jobject o) {
    if (!o) return EGL_NO_DISPLAY;
    return (EGLDisplay)env->GetIntField(o, gDisplay_EGLDisplayFieldID);
}
static inline EGLSurface getSurface(JNIEnv* env, jobject o) {
    if (!o) return EGL_NO_SURFACE;
    return (EGLSurface)env->GetIntField(o, gSurface_EGLSurfaceFieldID);
}
static inline EGLContext getContext(JNIEnv* env, jobject o) {
    if (!o) return EGL_NO_CONTEXT;
    return (EGLContext)env->GetIntField(o, gContext_EGLContextFieldID);
}
static inline EGLConfig getConfig(JNIEnv* env, jobject o) {
    if (!o) return 0;
    return (EGLConfig)env->GetIntField(o, gConfig_EGLConfigFieldID);
}
static void nativeClassInit(JNIEnv *_env, jclass eglImplClass)
{
    jclass config_class = _env->FindClass("com/google/android/gles_jni/EGLConfigImpl");
    gConfig_class = (jclass) _env->NewGlobalRef(config_class);
    gConfig_ctorID = _env->GetMethodID(gConfig_class,  "<init>", "(I)V");
    gConfig_EGLConfigFieldID = _env->GetFieldID(gConfig_class,  "mEGLConfig",  "I");

    jclass display_class = _env->FindClass("com/google/android/gles_jni/EGLDisplayImpl");
    gDisplay_EGLDisplayFieldID = _env->GetFieldID(display_class, "mEGLDisplay", "I");

    jclass context_class = _env->FindClass("com/google/android/gles_jni/EGLContextImpl");
    gContext_EGLContextFieldID = _env->GetFieldID(context_class, "mEGLContext", "I");

    jclass surface_class = _env->FindClass("com/google/android/gles_jni/EGLSurfaceImpl");
    gSurface_EGLSurfaceFieldID = _env->GetFieldID(surface_class, "mEGLSurface", "I");
    gSurface_NativePixelRefFieldID = _env->GetFieldID(surface_class, "mNativePixelRef", "I");

    jclass bitmap_class = _env->FindClass("android/graphics/Bitmap");
    gBitmap_NativeBitmapFieldID = _env->GetFieldID(bitmap_class, "mNativeBitmap", "I");
}

static const jint gNull_attrib_base[] = {EGL_NONE};

static bool validAttribList(JNIEnv *_env, jintArray attrib_list) {
    if (attrib_list == NULL) {
        return true;
    }
    jsize len = _env->GetArrayLength(attrib_list);
    if (len < 1) {
        return false;
    }
    jint item = 0;
    _env->GetIntArrayRegion(attrib_list, len-1, 1, &item);
    return item == EGL_NONE;
}

static jint* beginNativeAttribList(JNIEnv *_env, jintArray attrib_list) {
    if (attrib_list != NULL) {
        return (jint *)_env->GetPrimitiveArrayCritical(attrib_list, (jboolean *)0);
    } else {
        return(jint*) gNull_attrib_base;
    }
}

static void endNativeAttributeList(JNIEnv *_env, jintArray attrib_list, jint* attrib_base) {
    if (attrib_list != NULL) {
        _env->ReleasePrimitiveArrayCritical(attrib_list, attrib_base, JNI_ABORT);
    }
}

static jboolean jni_eglInitialize(JNIEnv *_env, jobject _this, jobject display,
        jintArray major_minor) {
    if (display == NULL || (major_minor != NULL &&
            _env->GetArrayLength(major_minor) < 2)) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", NULL);
        return JNI_FALSE;
    }

    EGLDisplay dpy = getDisplay(_env, display);
    jboolean success = eglInitialize(dpy, NULL, NULL);
    if (success && major_minor) {
        int len = _env->GetArrayLength(major_minor);
        if (len) {
            // we're exposing only EGL 1.0
            jint* base = (jint *)_env->GetPrimitiveArrayCritical(major_minor, (jboolean *)0);
            if (len >= 1) base[0] = 1;
            if (len >= 2) base[1] = 0;
            _env->ReleasePrimitiveArrayCritical(major_minor, base, JNI_ABORT);
        }
    }
    return success;
}

static jboolean jni_eglQueryContext(JNIEnv *_env, jobject _this, jobject display,
        jobject context, jint attribute, jintArray value) {
    if (display == NULL || context == NULL || value == NULL
        || _env->GetArrayLength(value) < 1) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", NULL);
        return JNI_FALSE;
    }
    EGLDisplay dpy = getDisplay(_env, display);
    EGLContext ctx = getContext(_env, context);
    jboolean success = JNI_FALSE;
    int len = _env->GetArrayLength(value);
    if (len) {
        jint* base = (jint *)_env->GetPrimitiveArrayCritical(value, (jboolean *)0);
        success = eglQueryContext(dpy, ctx, attribute, base);
        _env->ReleasePrimitiveArrayCritical(value, base, JNI_ABORT);
    }
    return success;
}

static jboolean jni_eglQuerySurface(JNIEnv *_env, jobject _this, jobject display,
        jobject surface, jint attribute, jintArray value) {
    if (display == NULL || surface == NULL || value == NULL
        || _env->GetArrayLength(value) < 1) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", NULL);
        return JNI_FALSE;
    }
    EGLDisplay dpy = getDisplay(_env, display);
    EGLContext sur = getSurface(_env, surface);

    jboolean success = JNI_FALSE;
    int len = _env->GetArrayLength(value);
    if (len) {
        jint* base = (jint *)_env->GetPrimitiveArrayCritical(value, (jboolean *)0);
        success = eglQuerySurface(dpy, sur, attribute, base);
        _env->ReleasePrimitiveArrayCritical(value, base, JNI_ABORT);
    }
    return success;
}

static jint jni_getInitCount(JNIEnv *_env, jobject _clazz, jobject display) {
    EGLDisplay dpy = getDisplay(_env, display);
    egl_display_t* eglDisplay = get_display_nowake(dpy);
    return eglDisplay ? eglDisplay->getRefsCount() : 0;
}

static jboolean jni_eglReleaseThread(JNIEnv *_env, jobject _this) {
    return eglReleaseThread();
}

static jboolean jni_eglChooseConfig(JNIEnv *_env, jobject _this, jobject display,
        jintArray attrib_list, jobjectArray configs, jint config_size, jintArray num_config) {
    if (display == NULL
        || !validAttribList(_env, attrib_list)
        || (configs != NULL && _env->GetArrayLength(configs) < config_size)
        || (num_config != NULL && _env->GetArrayLength(num_config) < 1)) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", NULL);
        return JNI_FALSE;
    }
    EGLDisplay dpy = getDisplay(_env, display);
    jboolean success = JNI_FALSE;

    if (configs == NULL) {
        config_size = 0;
    }
    EGLConfig nativeConfigs[config_size];

    int num = 0;
    jint* attrib_base = beginNativeAttribList(_env, attrib_list);
    success = eglChooseConfig(dpy, attrib_base, configs ? nativeConfigs : 0, config_size, &num);
    endNativeAttributeList(_env, attrib_list, attrib_base);

    if (num_config != NULL) {
        _env->SetIntArrayRegion(num_config, 0, 1, (jint*) &num);
    }

    if (success && configs!=NULL) {
        for (int i=0 ; i<num ; i++) {
            jobject obj = _env->NewObject(gConfig_class, gConfig_ctorID, (jint)nativeConfigs[i]);
            _env->SetObjectArrayElement(configs, i, obj);
        }
    }
    return success;
}

static jint jni_eglCreateContext(JNIEnv *_env, jobject _this, jobject display,
        jobject config, jobject share_context, jintArray attrib_list) {
    if (display == NULL || config == NULL || share_context == NULL
        || !validAttribList(_env, attrib_list)) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", NULL);
        return JNI_FALSE;
    }
    EGLDisplay dpy = getDisplay(_env, display);
    EGLConfig  cnf = getConfig(_env, config);
    EGLContext shr = getContext(_env, share_context);
    jint* base = beginNativeAttribList(_env, attrib_list);
    EGLContext ctx = eglCreateContext(dpy, cnf, shr, base);
    endNativeAttributeList(_env, attrib_list, base);
    return (jint)ctx;
}

static jint jni_eglCreatePbufferSurface(JNIEnv *_env, jobject _this, jobject display,
        jobject config, jintArray attrib_list) {
    if (display == NULL || config == NULL
        || !validAttribList(_env, attrib_list)) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", NULL);
        return JNI_FALSE;
    }
    EGLDisplay dpy = getDisplay(_env, display);
    EGLConfig  cnf = getConfig(_env, config);
    jint* base = beginNativeAttribList(_env, attrib_list);
    EGLSurface sur = eglCreatePbufferSurface(dpy, cnf, base);
    endNativeAttributeList(_env, attrib_list, base);
    return (jint)sur;
}

static PixelFormat convertPixelFormat(SkBitmap::Config format)
{
    switch (format) {
    case SkBitmap::kARGB_8888_Config:   return PIXEL_FORMAT_RGBA_8888;
    case SkBitmap::kARGB_4444_Config:   return PIXEL_FORMAT_RGBA_4444;
    case SkBitmap::kRGB_565_Config:     return PIXEL_FORMAT_RGB_565;
    case SkBitmap::kA8_Config:          return PIXEL_FORMAT_A_8;
    default:                            return PIXEL_FORMAT_NONE;
    }
}

static void jni_eglCreatePixmapSurface(JNIEnv *_env, jobject _this, jobject out_sur,
        jobject display, jobject config, jobject native_pixmap,
        jintArray attrib_list)
{
    if (display == NULL || config == NULL || native_pixmap == NULL
        || !validAttribList(_env, attrib_list)) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", NULL);
        return;
    }
    EGLDisplay dpy = getDisplay(_env, display);
    EGLConfig  cnf = getConfig(_env, config);
    jint* base = 0;

    SkBitmap const * nativeBitmap =
            (SkBitmap const *)_env->GetIntField(native_pixmap,
                    gBitmap_NativeBitmapFieldID);
    SkPixelRef* ref = nativeBitmap ? nativeBitmap->pixelRef() : 0;
    if (ref == NULL) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", "Bitmap has no PixelRef");
        return;
    }

    SkSafeRef(ref);
    ref->lockPixels();

    egl_native_pixmap_t pixmap;
    pixmap.version = sizeof(pixmap);
    pixmap.width  = nativeBitmap->width();
    pixmap.height = nativeBitmap->height();
    pixmap.stride = nativeBitmap->rowBytes() / nativeBitmap->bytesPerPixel();
    pixmap.format = convertPixelFormat(nativeBitmap->config());
    pixmap.data   = (uint8_t*)ref->pixels();

    base = beginNativeAttribList(_env, attrib_list);
    EGLSurface sur = eglCreatePixmapSurface(dpy, cnf, &pixmap, base);
    endNativeAttributeList(_env, attrib_list, base);

    if (sur != EGL_NO_SURFACE) {
        _env->SetIntField(out_sur, gSurface_EGLSurfaceFieldID, (int)sur);
        _env->SetIntField(out_sur, gSurface_NativePixelRefFieldID, (int)ref);
    } else {
        ref->unlockPixels();
        SkSafeUnref(ref);
    }
}

static jint jni_eglCreateWindowSurface(JNIEnv *_env, jobject _this, jobject display,
        jobject config, jobject native_window, jintArray attrib_list) {
    if (display == NULL || config == NULL
        || !validAttribList(_env, attrib_list)) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", NULL);
        return JNI_FALSE;
    }
    EGLDisplay dpy = getDisplay(_env, display);
    EGLContext cnf = getConfig(_env, config);
    sp<ANativeWindow> window;
    if (native_window == NULL) {
not_valid_surface:
        jniThrowException(_env, "java/lang/IllegalArgumentException",
                "Make sure the SurfaceView or associated SurfaceHolder has a valid Surface");
        return 0;
    }

    window = android_view_Surface_getNativeWindow(_env, native_window);
    if (window == NULL)
        goto not_valid_surface;

    jint* base = beginNativeAttribList(_env, attrib_list);
    EGLSurface sur = eglCreateWindowSurface(dpy, cnf, window.get(), base);
    endNativeAttributeList(_env, attrib_list, base);
    return (jint)sur;
}

static jint jni_eglCreateWindowSurfaceTexture(JNIEnv *_env, jobject _this, jobject display,
        jobject config, jobject native_window, jintArray attrib_list) {
    if (display == NULL || config == NULL
        || !validAttribList(_env, attrib_list)) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", NULL);
        return JNI_FALSE;
    }
    EGLDisplay dpy = getDisplay(_env, display);
    EGLContext cnf = getConfig(_env, config);
    sp<ANativeWindow> window;
    if (native_window == 0) {
not_valid_surface:
        jniThrowException(_env, "java/lang/IllegalArgumentException",
                "Make sure the SurfaceTexture is valid");
        return 0;
    }
    
    sp<SurfaceTexture> surfaceTexture(SurfaceTexture_getSurfaceTexture(_env, native_window));

    window = new SurfaceTextureClient(surfaceTexture);
    if (window == NULL)
        goto not_valid_surface;

    jint* base = beginNativeAttribList(_env, attrib_list);
    EGLSurface sur = eglCreateWindowSurface(dpy, cnf, window.get(), base);
    endNativeAttributeList(_env, attrib_list, base);
    return (jint)sur;
}

static jboolean jni_eglGetConfigAttrib(JNIEnv *_env, jobject _this, jobject display,
        jobject config, jint attribute, jintArray value) {
    if (display == NULL || config == NULL
        || (value == NULL || _env->GetArrayLength(value) < 1)) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", NULL);
        return JNI_FALSE;
    }
    EGLDisplay dpy = getDisplay(_env, display);
    EGLContext cnf = getConfig(_env, config);
    jboolean success = JNI_FALSE;
    jint localValue;
    success = eglGetConfigAttrib(dpy, cnf, attribute, &localValue);
    if (success) {
        _env->SetIntArrayRegion(value, 0, 1, &localValue);
    }
    return success;
}

static jboolean jni_eglGetConfigs(JNIEnv *_env, jobject _this, jobject display,
        jobjectArray configs, jint config_size, jintArray num_config) {
    if (display == NULL || (configs != NULL && _env->GetArrayLength(configs) < config_size)
        || (num_config != NULL && _env->GetArrayLength(num_config) < 1)) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", NULL);
        return JNI_FALSE;
    }
    EGLDisplay dpy = getDisplay(_env, display);
    jboolean success = JNI_FALSE;
    if (configs == NULL) {
        config_size = 0;
    }
    EGLConfig nativeConfigs[config_size];
    int num;
    success = eglGetConfigs(dpy, configs ? nativeConfigs : 0, config_size, &num);
    if (num_config != NULL) {
        _env->SetIntArrayRegion(num_config, 0, 1, (jint*) &num);
    }
    if (success && configs) {
        for (int i=0 ; i<num ; i++) {
            jobject obj = _env->NewObject(gConfig_class, gConfig_ctorID, (jint)nativeConfigs[i]);
            _env->SetObjectArrayElement(configs, i, obj);
        }
    }
    return success;
}

static jint jni_eglGetError(JNIEnv *_env, jobject _this) {
    EGLint error = eglGetError();
    return error;
}

static jint jni_eglGetCurrentContext(JNIEnv *_env, jobject _this) {
    return (jint)eglGetCurrentContext();
}

static jint jni_eglGetCurrentDisplay(JNIEnv *_env, jobject _this) {
    return (jint)eglGetCurrentDisplay();
}

static jint jni_eglGetCurrentSurface(JNIEnv *_env, jobject _this, jint readdraw) {
    if ((readdraw != EGL_READ) && (readdraw != EGL_DRAW)) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", NULL);
        return 0;
    }
    return (jint)eglGetCurrentSurface(readdraw);
}

static jboolean jni_eglDestroyContext(JNIEnv *_env, jobject _this, jobject display, jobject context) {
    if (display == NULL || context == NULL) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", NULL);
        return JNI_FALSE;
    }
    EGLDisplay dpy = getDisplay(_env, display);
    EGLContext ctx = getContext(_env, context);
    return eglDestroyContext(dpy, ctx);
}

static jboolean jni_eglDestroySurface(JNIEnv *_env, jobject _this, jobject display, jobject surface) {
    if (display == NULL || surface == NULL) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", NULL);
        return JNI_FALSE;
    }
    EGLDisplay dpy = getDisplay(_env, display);
    EGLSurface sur = getSurface(_env, surface);

    if (sur) {
        SkPixelRef* ref = (SkPixelRef*)(_env->GetIntField(surface,
                gSurface_NativePixelRefFieldID));
        if (ref) {
            ref->unlockPixels();
            SkSafeUnref(ref);
        }
    }
    return eglDestroySurface(dpy, sur);
}

static jint jni_eglGetDisplay(JNIEnv *_env, jobject _this, jobject native_display) {
    return (jint)eglGetDisplay(EGL_DEFAULT_DISPLAY);
}

static jboolean jni_eglMakeCurrent(JNIEnv *_env, jobject _this, jobject display, jobject draw, jobject read, jobject context) {
    if (display == NULL || draw == NULL || read == NULL || context == NULL) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", NULL);
        return JNI_FALSE;
    }
    EGLDisplay dpy = getDisplay(_env, display);
    EGLSurface sdr = getSurface(_env, draw);
    EGLSurface srd = getSurface(_env, read);
    EGLContext ctx = getContext(_env, context);
    return eglMakeCurrent(dpy, sdr, srd, ctx);
}

static jstring jni_eglQueryString(JNIEnv *_env, jobject _this, jobject display, jint name) {
    if (display == NULL) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", NULL);
        return NULL;
    }
    EGLDisplay dpy = getDisplay(_env, display);
    const char* chars = eglQueryString(dpy, name);
    return _env->NewStringUTF(chars);
}

static jboolean jni_eglSwapBuffers(JNIEnv *_env, jobject _this, jobject display, jobject surface) {
    if (display == NULL || surface == NULL) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", NULL);
        return JNI_FALSE;
    }
    EGLDisplay dpy = getDisplay(_env, display);
    EGLSurface sur = getSurface(_env, surface);
    return eglSwapBuffers(dpy, sur);
}

static jboolean jni_eglTerminate(JNIEnv *_env, jobject _this, jobject display) {
    if (display == NULL) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", NULL);
        return JNI_FALSE;
    }
    EGLDisplay dpy = getDisplay(_env, display);
    return eglTerminate(dpy);
}

static jboolean jni_eglCopyBuffers(JNIEnv *_env, jobject _this, jobject display,
        jobject surface, jobject native_pixmap) {
    if (display == NULL || surface == NULL || native_pixmap == NULL) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", NULL);
        return JNI_FALSE;
    }
    // TODO: Implement this
    return JNI_FALSE;
}

static jboolean jni_eglWaitGL(JNIEnv *_env, jobject _this) {
    return eglWaitGL();
}

static jboolean jni_eglWaitNative(JNIEnv *_env, jobject _this, jint engine, jobject bindTarget) {
    return eglWaitNative(engine);
}


static const char *classPathName = "com/google/android/gles_jni/EGLImpl";

#define DISPLAY "Ljavax/microedition/khronos/egl/EGLDisplay;"
#define CONTEXT "Ljavax/microedition/khronos/egl/EGLContext;"
#define CONFIG  "Ljavax/microedition/khronos/egl/EGLConfig;"
#define SURFACE "Ljavax/microedition/khronos/egl/EGLSurface;"
#define OBJECT  "Ljava/lang/Object;"
#define STRING  "Ljava/lang/String;"

static JNINativeMethod methods[] = {
{"_nativeClassInit","()V", (void*)nativeClassInit },
{"eglWaitGL",       "()Z", (void*)jni_eglWaitGL },
{"eglInitialize",   "(" DISPLAY "[I)Z", (void*)jni_eglInitialize },
{"eglQueryContext", "(" DISPLAY CONTEXT "I[I)Z", (void*)jni_eglQueryContext },
{"eglQuerySurface", "(" DISPLAY SURFACE "I[I)Z", (void*)jni_eglQuerySurface },
{"eglReleaseThread","()Z", (void*)jni_eglReleaseThread },
{"getInitCount",    "(" DISPLAY ")I", (void*)jni_getInitCount },
{"eglChooseConfig", "(" DISPLAY "[I[" CONFIG "I[I)Z", (void*)jni_eglChooseConfig },
{"_eglCreateContext","(" DISPLAY CONFIG CONTEXT "[I)I", (void*)jni_eglCreateContext },
{"eglGetConfigs",   "(" DISPLAY "[" CONFIG "I[I)Z", (void*)jni_eglGetConfigs },
{"eglTerminate",    "(" DISPLAY ")Z", (void*)jni_eglTerminate },
{"eglCopyBuffers",  "(" DISPLAY SURFACE OBJECT ")Z", (void*)jni_eglCopyBuffers },
{"eglWaitNative",   "(I" OBJECT ")Z", (void*)jni_eglWaitNative },
{"eglGetError",     "()I", (void*)jni_eglGetError },
{"eglGetConfigAttrib", "(" DISPLAY CONFIG "I[I)Z", (void*)jni_eglGetConfigAttrib },
{"_eglGetDisplay",   "(" OBJECT ")I", (void*)jni_eglGetDisplay },
{"_eglGetCurrentContext",  "()I", (void*)jni_eglGetCurrentContext },
{"_eglGetCurrentDisplay",  "()I", (void*)jni_eglGetCurrentDisplay },
{"_eglGetCurrentSurface",  "(I)I", (void*)jni_eglGetCurrentSurface },
{"_eglCreatePbufferSurface","(" DISPLAY CONFIG "[I)I", (void*)jni_eglCreatePbufferSurface },
{"_eglCreatePixmapSurface", "(" SURFACE DISPLAY CONFIG OBJECT "[I)V", (void*)jni_eglCreatePixmapSurface },
{"_eglCreateWindowSurface", "(" DISPLAY CONFIG OBJECT "[I)I", (void*)jni_eglCreateWindowSurface },
{"_eglCreateWindowSurfaceTexture", "(" DISPLAY CONFIG OBJECT "[I)I", (void*)jni_eglCreateWindowSurfaceTexture },
{"eglDestroyContext",      "(" DISPLAY CONTEXT ")Z", (void*)jni_eglDestroyContext },
{"eglDestroySurface",      "(" DISPLAY SURFACE ")Z", (void*)jni_eglDestroySurface },
{"eglMakeCurrent",         "(" DISPLAY SURFACE SURFACE CONTEXT")Z", (void*)jni_eglMakeCurrent },
{"eglQueryString",         "(" DISPLAY "I)" STRING, (void*)jni_eglQueryString },
{"eglSwapBuffers",         "(" DISPLAY SURFACE ")Z", (void*)jni_eglSwapBuffers },
};

} // namespace android

int register_com_google_android_gles_jni_EGLImpl(JNIEnv *_env)
{
    int err;
    err = android::AndroidRuntime::registerNativeMethods(_env,
            android::classPathName, android::methods, NELEM(android::methods));
    return err;
}
