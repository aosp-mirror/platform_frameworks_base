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

#include <nativehelper/JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/android_view_Surface.h>
#include <android_runtime/android_graphics_SurfaceTexture.h>
#include <utils/misc.h>


#include <EGL/egl.h>
#include <GLES/gl.h>
#include <private/EGL/display.h>

#include <gui/Surface.h>
#include <gui/GLConsumer.h>
#include <gui/Surface.h>

#include <GraphicsJNI.h>
#include <SkBitmap.h>
#include <SkPixelRef.h>

#include <ui/ANativeObjectBase.h>

namespace android {

static jclass gConfig_class;

static jmethodID gConfig_ctorID;

static jfieldID gDisplay_EGLDisplayFieldID;
static jfieldID gContext_EGLContextFieldID;
static jfieldID gSurface_EGLSurfaceFieldID;
static jfieldID gConfig_EGLConfigFieldID;

static inline EGLDisplay getDisplay(JNIEnv* env, jobject o) {
    if (!o) return EGL_NO_DISPLAY;
    return (EGLDisplay)env->GetLongField(o, gDisplay_EGLDisplayFieldID);
}
static inline EGLSurface getSurface(JNIEnv* env, jobject o) {
    if (!o) return EGL_NO_SURFACE;
    return (EGLSurface)env->GetLongField(o, gSurface_EGLSurfaceFieldID);
}
static inline EGLContext getContext(JNIEnv* env, jobject o) {
    if (!o) return EGL_NO_CONTEXT;
    return (EGLContext)env->GetLongField(o, gContext_EGLContextFieldID);
}
static inline EGLConfig getConfig(JNIEnv* env, jobject o) {
    if (!o) return 0;
    return (EGLConfig)env->GetLongField(o, gConfig_EGLConfigFieldID);
}

static inline jboolean EglBoolToJBool(EGLBoolean eglBool) {
    return eglBool == EGL_TRUE ? JNI_TRUE : JNI_FALSE;
}

static void nativeClassInit(JNIEnv *_env, jclass eglImplClass)
{
    jclass config_class = _env->FindClass("com/google/android/gles_jni/EGLConfigImpl");
    gConfig_class = (jclass) _env->NewGlobalRef(config_class);
    gConfig_ctorID = _env->GetMethodID(gConfig_class,  "<init>", "(J)V");
    gConfig_EGLConfigFieldID = _env->GetFieldID(gConfig_class,  "mEGLConfig",  "J");

    jclass display_class = _env->FindClass("com/google/android/gles_jni/EGLDisplayImpl");
    gDisplay_EGLDisplayFieldID = _env->GetFieldID(display_class, "mEGLDisplay", "J");

    jclass context_class = _env->FindClass("com/google/android/gles_jni/EGLContextImpl");
    gContext_EGLContextFieldID = _env->GetFieldID(context_class, "mEGLContext", "J");

    jclass surface_class = _env->FindClass("com/google/android/gles_jni/EGLSurfaceImpl");
    gSurface_EGLSurfaceFieldID = _env->GetFieldID(surface_class, "mEGLSurface", "J");
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
        return _env->GetIntArrayElements(attrib_list, (jboolean *)0);
    } else {
        return(jint*) gNull_attrib_base;
    }
}

static void endNativeAttributeList(JNIEnv *_env, jintArray attrib_list, jint* attrib_base) {
    if (attrib_list != NULL) {
        _env->ReleaseIntArrayElements(attrib_list, attrib_base, 0);
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
    EGLBoolean success = eglInitialize(dpy, NULL, NULL);
    if (success && major_minor) {
        int len = _env->GetArrayLength(major_minor);
        if (len) {
            // we're exposing only EGL 1.0
            jint* base = (jint *)_env->GetPrimitiveArrayCritical(major_minor, (jboolean *)0);
            if (len >= 1) base[0] = 1;
            if (len >= 2) base[1] = 0;
            _env->ReleasePrimitiveArrayCritical(major_minor, base, 0);
        }
    }
    return EglBoolToJBool(success);
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
    EGLBoolean success = EGL_FALSE;
    int len = _env->GetArrayLength(value);
    if (len) {
        jint* base = _env->GetIntArrayElements(value, (jboolean *)0);
        success = eglQueryContext(dpy, ctx, attribute, base);
        _env->ReleaseIntArrayElements(value, base, 0);
    }
    return EglBoolToJBool(success);
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

    EGLBoolean success = EGL_FALSE;
    int len = _env->GetArrayLength(value);
    if (len) {
        jint* base = _env->GetIntArrayElements(value, (jboolean *)0);
        success = eglQuerySurface(dpy, sur, attribute, base);
        _env->ReleaseIntArrayElements(value, base, 0);
    }
    return EglBoolToJBool(success);
}

static jint jni_getInitCount(JNIEnv *_env, jobject _clazz, jobject display) {
    EGLDisplay dpy = getDisplay(_env, display);
    return android::egl_get_init_count(dpy);
}

static jboolean jni_eglReleaseThread(JNIEnv *_env, jobject _this) {
    return EglBoolToJBool(eglReleaseThread());
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
    EGLBoolean success = EGL_FALSE;

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
            jobject obj = _env->NewObject(gConfig_class, gConfig_ctorID, reinterpret_cast<jlong>(nativeConfigs[i]));
            _env->SetObjectArrayElement(configs, i, obj);
        }
    }
    return EglBoolToJBool(success);
}

static jlong jni_eglCreateContext(JNIEnv *_env, jobject _this, jobject display,
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
    return reinterpret_cast<jlong>(ctx);
}

static jlong jni_eglCreatePbufferSurface(JNIEnv *_env, jobject _this, jobject display,
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
    return reinterpret_cast<jlong>(sur);
}

static void jni_eglCreatePixmapSurface(JNIEnv *_env, jobject _this, jobject out_sur,
        jobject display, jobject config, jobject native_pixmap,
        jintArray attrib_list)
{
    jniThrowException(_env, "java/lang/UnsupportedOperationException", "eglCreatePixmapSurface");
}

static jlong jni_eglCreateWindowSurface(JNIEnv *_env, jobject _this, jobject display,
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
    return reinterpret_cast<jlong>(sur);
}

static jlong jni_eglCreateWindowSurfaceTexture(JNIEnv *_env, jobject _this, jobject display,
        jobject config, jobject native_window, jintArray attrib_list) {
    if (display == NULL || config == NULL
        || !validAttribList(_env, attrib_list)) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", NULL);
        return 0;
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

    sp<IGraphicBufferProducer> producer(SurfaceTexture_getProducer(_env, native_window));
    window = new Surface(producer, true);
    if (window == NULL)
        goto not_valid_surface;

    jint* base = beginNativeAttribList(_env, attrib_list);
    EGLSurface sur = eglCreateWindowSurface(dpy, cnf, window.get(), base);
    endNativeAttributeList(_env, attrib_list, base);
    return reinterpret_cast<jlong>(sur);
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
    EGLBoolean success = EGL_FALSE;
    jint localValue;
    success = eglGetConfigAttrib(dpy, cnf, attribute, &localValue);
    if (success) {
        _env->SetIntArrayRegion(value, 0, 1, &localValue);
    }
    return EglBoolToJBool(success);
}

static jboolean jni_eglGetConfigs(JNIEnv *_env, jobject _this, jobject display,
        jobjectArray configs, jint config_size, jintArray num_config) {
    if (display == NULL || (configs != NULL && _env->GetArrayLength(configs) < config_size)
        || (num_config != NULL && _env->GetArrayLength(num_config) < 1)) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", NULL);
        return JNI_FALSE;
    }
    EGLDisplay dpy = getDisplay(_env, display);
    EGLBoolean success = EGL_FALSE;
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
            jobject obj = _env->NewObject(gConfig_class, gConfig_ctorID, reinterpret_cast<jlong>(nativeConfigs[i]));
            _env->SetObjectArrayElement(configs, i, obj);
        }
    }
    return EglBoolToJBool(success);
}

static jint jni_eglGetError(JNIEnv *_env, jobject _this) {
    EGLint error = eglGetError();
    return error;
}

static jlong jni_eglGetCurrentContext(JNIEnv *_env, jobject _this) {
    return reinterpret_cast<jlong>(eglGetCurrentContext());
}

static jlong jni_eglGetCurrentDisplay(JNIEnv *_env, jobject _this) {
    return reinterpret_cast<jlong>(eglGetCurrentDisplay());
}

static jlong jni_eglGetCurrentSurface(JNIEnv *_env, jobject _this, jint readdraw) {
    if ((readdraw != EGL_READ) && (readdraw != EGL_DRAW)) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", NULL);
        return 0;
    }
    return reinterpret_cast<jlong>(eglGetCurrentSurface(readdraw));
}

static jboolean jni_eglDestroyContext(JNIEnv *_env, jobject _this, jobject display, jobject context) {
    if (display == NULL || context == NULL) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", NULL);
        return JNI_FALSE;
    }
    EGLDisplay dpy = getDisplay(_env, display);
    EGLContext ctx = getContext(_env, context);
    return EglBoolToJBool(eglDestroyContext(dpy, ctx));
}

static jboolean jni_eglDestroySurface(JNIEnv *_env, jobject _this, jobject display, jobject surface) {
    if (display == NULL || surface == NULL) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", NULL);
        return JNI_FALSE;
    }
    EGLDisplay dpy = getDisplay(_env, display);
    EGLSurface sur = getSurface(_env, surface);
    return EglBoolToJBool(eglDestroySurface(dpy, sur));
}

static jlong jni_eglGetDisplay(JNIEnv *_env, jobject _this, jobject native_display) {
    return reinterpret_cast<jlong>(eglGetDisplay(EGL_DEFAULT_DISPLAY));
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
    return EglBoolToJBool(eglMakeCurrent(dpy, sdr, srd, ctx));
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
    return EglBoolToJBool(eglSwapBuffers(dpy, sur));
}

static jboolean jni_eglTerminate(JNIEnv *_env, jobject _this, jobject display) {
    if (display == NULL) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", NULL);
        return JNI_FALSE;
    }
    EGLDisplay dpy = getDisplay(_env, display);
    return EglBoolToJBool(eglTerminate(dpy));
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
    return EglBoolToJBool(eglWaitGL());
}

static jboolean jni_eglWaitNative(JNIEnv *_env, jobject _this, jint engine, jobject bindTarget) {
    return EglBoolToJBool(eglWaitNative(engine));
}


static const char *classPathName = "com/google/android/gles_jni/EGLImpl";

#define DISPLAY "Ljavax/microedition/khronos/egl/EGLDisplay;"
#define CONTEXT "Ljavax/microedition/khronos/egl/EGLContext;"
#define CONFIG  "Ljavax/microedition/khronos/egl/EGLConfig;"
#define SURFACE "Ljavax/microedition/khronos/egl/EGLSurface;"
#define OBJECT  "Ljava/lang/Object;"
#define STRING  "Ljava/lang/String;"

static const JNINativeMethod methods[] = {
{"_nativeClassInit","()V", (void*)nativeClassInit },
{"eglWaitGL",       "()Z", (void*)jni_eglWaitGL },
{"eglInitialize",   "(" DISPLAY "[I)Z", (void*)jni_eglInitialize },
{"eglQueryContext", "(" DISPLAY CONTEXT "I[I)Z", (void*)jni_eglQueryContext },
{"eglQuerySurface", "(" DISPLAY SURFACE "I[I)Z", (void*)jni_eglQuerySurface },
{"eglReleaseThread","()Z", (void*)jni_eglReleaseThread },
{"getInitCount",    "(" DISPLAY ")I", (void*)jni_getInitCount },
{"eglChooseConfig", "(" DISPLAY "[I[" CONFIG "I[I)Z", (void*)jni_eglChooseConfig },
{"_eglCreateContext","(" DISPLAY CONFIG CONTEXT "[I)J", (void*)jni_eglCreateContext },
{"eglGetConfigs",   "(" DISPLAY "[" CONFIG "I[I)Z", (void*)jni_eglGetConfigs },
{"eglTerminate",    "(" DISPLAY ")Z", (void*)jni_eglTerminate },
{"eglCopyBuffers",  "(" DISPLAY SURFACE OBJECT ")Z", (void*)jni_eglCopyBuffers },
{"eglWaitNative",   "(I" OBJECT ")Z", (void*)jni_eglWaitNative },
{"eglGetError",     "()I", (void*)jni_eglGetError },
{"eglGetConfigAttrib", "(" DISPLAY CONFIG "I[I)Z", (void*)jni_eglGetConfigAttrib },
{"_eglGetDisplay",   "(" OBJECT ")J", (void*)jni_eglGetDisplay },
{"_eglGetCurrentContext",  "()J", (void*)jni_eglGetCurrentContext },
{"_eglGetCurrentDisplay",  "()J", (void*)jni_eglGetCurrentDisplay },
{"_eglGetCurrentSurface",  "(I)J", (void*)jni_eglGetCurrentSurface },
{"_eglCreatePbufferSurface","(" DISPLAY CONFIG "[I)J", (void*)jni_eglCreatePbufferSurface },
{"_eglCreatePixmapSurface", "(" SURFACE DISPLAY CONFIG OBJECT "[I)V", (void*)jni_eglCreatePixmapSurface },
{"_eglCreateWindowSurface", "(" DISPLAY CONFIG OBJECT "[I)J", (void*)jni_eglCreateWindowSurface },
{"_eglCreateWindowSurfaceTexture", "(" DISPLAY CONFIG OBJECT "[I)J", (void*)jni_eglCreateWindowSurfaceTexture },
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
