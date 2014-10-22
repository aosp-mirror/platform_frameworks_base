/*
 * Copyright (C) 2011 The Android Open Source Project
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

#include "jni.h"
#include <nativehelper/JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/android_graphics_SurfaceTexture.h>

#include <ui/Region.h>
#include <ui/Rect.h>

#include <gui/GLConsumer.h>
#include <gui/Surface.h>

#include <SkBitmap.h>
#include <SkCanvas.h>
#include <SkImage.h>

#include "android/graphics/GraphicsJNI.h"

namespace android {

// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

static struct {
    jmethodID set;
    jfieldID left;
    jfieldID top;
    jfieldID right;
    jfieldID bottom;
} gRectClassInfo;

static struct {
    jfieldID mSurfaceFormat;
    jmethodID setNativeBitmap;
} gCanvasClassInfo;

static struct {
    jfieldID nativeWindow;
} gTextureViewClassInfo;

#define GET_INT(object, field) \
    env->GetIntField(object, field)

#define GET_LONG(object, field) \
    env->GetLongField(object, field)

#define SET_INT(object, field, value) \
    env->SetIntField(object, field, value)

#define SET_LONG(object, field, value) \
    env->SetLongField(object, field, value)

#define INVOKEV(object, method, ...) \
    env->CallVoidMethod(object, method, __VA_ARGS__)

// ----------------------------------------------------------------------------
// Native layer
// ----------------------------------------------------------------------------

// FIXME: consider exporting this to share (e.g. android_view_Surface.cpp)
static inline SkImageInfo convertPixelFormat(const ANativeWindow_Buffer& buffer) {
    SkImageInfo info;
    info.fWidth = buffer.width;
    info.fHeight = buffer.height;
    switch (buffer.format) {
        case WINDOW_FORMAT_RGBA_8888:
            info.fColorType = kN32_SkColorType;
            info.fAlphaType = kPremul_SkAlphaType;
            break;
        case WINDOW_FORMAT_RGBX_8888:
            info.fColorType = kN32_SkColorType;
            info.fAlphaType = kOpaque_SkAlphaType;
        case WINDOW_FORMAT_RGB_565:
            info.fColorType = kRGB_565_SkColorType;
            info.fAlphaType = kOpaque_SkAlphaType;
        default:
            info.fColorType = kUnknown_SkColorType;
            info.fAlphaType = kIgnore_SkAlphaType;
            break;
    }
    return info;
}

/**
 * This is a private API, and this implementation is also provided in the NDK.
 * However, the NDK links against android_runtime, which means that using the
 * NDK implementation would create a circular dependency between the libraries.
 */
static int32_t native_window_lock(ANativeWindow* window, ANativeWindow_Buffer* outBuffer,
        Rect* inOutDirtyBounds) {
    return window->perform(window, NATIVE_WINDOW_LOCK, outBuffer, inOutDirtyBounds);
}

static int32_t native_window_unlockAndPost(ANativeWindow* window) {
    return window->perform(window, NATIVE_WINDOW_UNLOCK_AND_POST);
}

static void android_view_TextureView_createNativeWindow(JNIEnv* env, jobject textureView,
        jobject surface) {

    sp<IGraphicBufferProducer> producer(SurfaceTexture_getProducer(env, surface));
    sp<ANativeWindow> window = new Surface(producer, true);

    window->incStrong((void*)android_view_TextureView_createNativeWindow);
    SET_LONG(textureView, gTextureViewClassInfo.nativeWindow, jlong(window.get()));
}

static void android_view_TextureView_destroyNativeWindow(JNIEnv* env, jobject textureView) {

    ANativeWindow* nativeWindow = (ANativeWindow*)
            GET_LONG(textureView, gTextureViewClassInfo.nativeWindow);

    if (nativeWindow) {
        sp<ANativeWindow> window(nativeWindow);
            window->decStrong((void*)android_view_TextureView_createNativeWindow);
        SET_LONG(textureView, gTextureViewClassInfo.nativeWindow, 0);
    }
}

static jboolean android_view_TextureView_lockCanvas(JNIEnv* env, jobject,
        jlong nativeWindow, jobject canvas, jobject dirtyRect) {

    if (!nativeWindow) {
        return JNI_FALSE;
    }

    ANativeWindow_Buffer buffer;

    Rect rect;
    if (dirtyRect) {
        rect.left = GET_INT(dirtyRect, gRectClassInfo.left);
        rect.top = GET_INT(dirtyRect, gRectClassInfo.top);
        rect.right = GET_INT(dirtyRect, gRectClassInfo.right);
        rect.bottom = GET_INT(dirtyRect, gRectClassInfo.bottom);
    } else {
        rect.set(Rect(0x3FFF, 0x3FFF));
    }

    sp<ANativeWindow> window((ANativeWindow*) nativeWindow);
    int32_t status = native_window_lock(window.get(), &buffer, &rect);
    if (status) return JNI_FALSE;

    ssize_t bytesCount = buffer.stride * bytesPerPixel(buffer.format);

    SkBitmap bitmap;
    bitmap.setInfo(convertPixelFormat(buffer), bytesCount);

    if (buffer.width > 0 && buffer.height > 0) {
        bitmap.setPixels(buffer.bits);
    } else {
        bitmap.setPixels(NULL);
    }

    SET_INT(canvas, gCanvasClassInfo.mSurfaceFormat, buffer.format);
    INVOKEV(canvas, gCanvasClassInfo.setNativeBitmap, reinterpret_cast<jlong>(&bitmap));

    SkRect clipRect;
    clipRect.set(rect.left, rect.top, rect.right, rect.bottom);
    SkCanvas* nativeCanvas = GraphicsJNI::getNativeCanvas(env, canvas);
    nativeCanvas->clipRect(clipRect);

    if (dirtyRect) {
        INVOKEV(dirtyRect, gRectClassInfo.set,
                int(rect.left), int(rect.top), int(rect.right), int(rect.bottom));
    }

    return JNI_TRUE;
}

static void android_view_TextureView_unlockCanvasAndPost(JNIEnv* env, jobject,
        jlong nativeWindow, jobject canvas) {

    INVOKEV(canvas, gCanvasClassInfo.setNativeBitmap, (jlong)0);

    if (nativeWindow) {
        sp<ANativeWindow> window((ANativeWindow*) nativeWindow);
        native_window_unlockAndPost(window.get());
    }
}

// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

const char* const kClassPathName = "android/view/TextureView";

static JNINativeMethod gMethods[] = {
    {   "nCreateNativeWindow", "(Landroid/graphics/SurfaceTexture;)V",
            (void*) android_view_TextureView_createNativeWindow },
    {   "nDestroyNativeWindow", "()V",
            (void*) android_view_TextureView_destroyNativeWindow },

    {   "nLockCanvas", "(JLandroid/graphics/Canvas;Landroid/graphics/Rect;)Z",
            (void*) android_view_TextureView_lockCanvas },
    {   "nUnlockCanvasAndPost", "(JLandroid/graphics/Canvas;)V",
            (void*) android_view_TextureView_unlockCanvasAndPost },
};

#define FIND_CLASS(var, className) \
        var = env->FindClass(className); \
        LOG_FATAL_IF(!var, "Unable to find class " className);

#define GET_METHOD_ID(var, clazz, methodName, methodDescriptor) \
        var = env->GetMethodID(clazz, methodName, methodDescriptor); \
        LOG_FATAL_IF(!var, "Unable to find method " methodName);

#define GET_FIELD_ID(var, clazz, fieldName, fieldDescriptor) \
        var = env->GetFieldID(clazz, fieldName, fieldDescriptor); \
        LOG_FATAL_IF(!var, "Unable to find field" fieldName);

int register_android_view_TextureView(JNIEnv* env) {
    jclass clazz;
    FIND_CLASS(clazz, "android/graphics/Rect");
    GET_METHOD_ID(gRectClassInfo.set, clazz, "set", "(IIII)V");
    GET_FIELD_ID(gRectClassInfo.left, clazz, "left", "I");
    GET_FIELD_ID(gRectClassInfo.top, clazz, "top", "I");
    GET_FIELD_ID(gRectClassInfo.right, clazz, "right", "I");
    GET_FIELD_ID(gRectClassInfo.bottom, clazz, "bottom", "I");

    FIND_CLASS(clazz, "android/graphics/Canvas");
    GET_FIELD_ID(gCanvasClassInfo.mSurfaceFormat, clazz, "mSurfaceFormat", "I");
    GET_METHOD_ID(gCanvasClassInfo.setNativeBitmap, clazz, "setNativeBitmap", "(J)V");

    FIND_CLASS(clazz, "android/view/TextureView");
    GET_FIELD_ID(gTextureViewClassInfo.nativeWindow, clazz, "mNativeWindow", "J");

    return AndroidRuntime::registerNativeMethods(env, kClassPathName, gMethods, NELEM(gMethods));
}

};
