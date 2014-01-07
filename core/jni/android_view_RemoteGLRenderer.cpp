/*
 * Copyright (C) 2014 The Android Open Source Project
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

#define LOG_TAG "RemoteGLRenderer"

#include "jni.h"
#include <nativehelper/JNIHelp.h>

#include <utils/StrongPointer.h>
#include <android_runtime/android_view_Surface.h>
#include <android_runtime/AndroidRuntime.h>
#include <renderthread/CanvasContext.h>
#include <system/window.h>

namespace android {

#ifdef USE_OPENGL_RENDERER

#define CHECK_CONTEXT(c) if (!c) ALOGE("Null context passed to %s!", __func__ )

namespace RT = android::uirenderer::renderthread;

static jlong android_view_RemoteGLRenderer_createContext(JNIEnv* env, jobject clazz) {
    RT::CanvasContext* context = new RT::CanvasContext();
    return reinterpret_cast<jlong>(context);
}

static jboolean android_view_RemoteGLRenderer_usePBufferSurface(JNIEnv* env, jobject clazz) {
    return RT::CanvasContext::useGlobalPBufferSurface();
}

static jboolean android_view_RemoteGLRenderer_setSurface(JNIEnv* env, jobject clazz,
        jlong jcontextptr, jobject jsurface) {
    RT::CanvasContext* context = reinterpret_cast<RT::CanvasContext*>(jcontextptr);
    CHECK_CONTEXT(context);
    sp<ANativeWindow> window;
    if (jsurface) {
        window = android_view_Surface_getNativeWindow(env, jsurface);
    }
    return context->setSurface(window.get());
}

static jboolean android_view_RemoteGLRenderer_swapBuffers(JNIEnv* env, jobject clazz,
        jlong jcontextptr) {
    RT::CanvasContext* context = reinterpret_cast<RT::CanvasContext*>(jcontextptr);
    CHECK_CONTEXT(context);
    return context->swapBuffers();
}

static jboolean android_view_RemoteGLRenderer_makeCurrent(JNIEnv* env, jobject clazz,
        jlong jcontextptr) {
    RT::CanvasContext* context = reinterpret_cast<RT::CanvasContext*>(jcontextptr);
    CHECK_CONTEXT(context);
    return context->makeCurrent();
}

static void android_view_RemoteGLRenderer_destroyContext(JNIEnv* env, jobject clazz,
        jlong jcontextptr) {
    RT::CanvasContext* context = reinterpret_cast<RT::CanvasContext*>(jcontextptr);
    CHECK_CONTEXT(context);
    delete context;
}
#endif

// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

const char* const kClassPathName = "android/view/RemoteGLRenderer";

static JNINativeMethod gMethods[] = {
#ifdef USE_OPENGL_RENDERER
    { "createContext", "()J",   (void*) android_view_RemoteGLRenderer_createContext },
    { "usePBufferSurface", "()Z",   (void*) android_view_RemoteGLRenderer_usePBufferSurface },
    { "setSurface", "(JLandroid/view/Surface;)Z",   (void*) android_view_RemoteGLRenderer_setSurface },
    { "swapBuffers", "(J)Z",   (void*) android_view_RemoteGLRenderer_swapBuffers },
    { "makeCurrent", "(J)Z",   (void*) android_view_RemoteGLRenderer_makeCurrent },
    { "destroyContext", "(J)V",   (void*) android_view_RemoteGLRenderer_destroyContext },
#endif
};

int register_android_view_RemoteGLRenderer(JNIEnv* env) {
    return AndroidRuntime::registerNativeMethods(env, kClassPathName, gMethods, NELEM(gMethods));
}

}; // namespace android
