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

#include <gui/SurfaceTexture.h>
#include <gui/SurfaceTextureClient.h>

#include <SkBitmap.h>
#include <SkCanvas.h>

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
    jfieldID nativeCanvas;
    jfieldID surfaceFormat;
} gCanvasClassInfo;

static struct {
    jfieldID nativeWindow;
} gTextureViewClassInfo;

#define GET_INT(object, field) \
    env->GetIntField(object, field)

#define SET_INT(object, field, value) \
    env->SetIntField(object, field, value)

#define INVOKEV(object, method, ...) \
    env->CallVoidMethod(object, method, __VA_ARGS__)

// ----------------------------------------------------------------------------
// Native layer
// ----------------------------------------------------------------------------

static void android_view_TextureView_setDefaultBufferSize(JNIEnv* env, jobject,
    jobject surface, jint width, jint height) {

    sp<SurfaceTexture> surfaceTexture(SurfaceTexture_getSurfaceTexture(env, surface));
    surfaceTexture->setDefaultBufferSize(width, height);
}

static inline SkBitmap::Config convertPixelFormat(int32_t format) {
    switch (format) {
        case WINDOW_FORMAT_RGBA_8888:
            return SkBitmap::kARGB_8888_Config;
        case WINDOW_FORMAT_RGBX_8888:
            return SkBitmap::kARGB_8888_Config;
        case WINDOW_FORMAT_RGB_565:
            return SkBitmap::kRGB_565_Config;
        default:
            return SkBitmap::kNo_Config;
    }
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

    sp<SurfaceTexture> surfaceTexture(SurfaceTexture_getSurfaceTexture(env, surface));
    sp<ANativeWindow> window = new SurfaceTextureClient(surfaceTexture);

    window->incStrong(0);
    SET_INT(textureView, gTextureViewClassInfo.nativeWindow, jint(window.get()));
}

static void android_view_TextureView_destroyNativeWindow(JNIEnv* env, jobject textureView) {

    ANativeWindow* nativeWindow = (ANativeWindow*)
            GET_INT(textureView, gTextureViewClassInfo.nativeWindow);

    if (nativeWindow) {
        sp<ANativeWindow> window(nativeWindow);
            window->decStrong(0);
        SET_INT(textureView, gTextureViewClassInfo.nativeWindow, 0);
    }
}

static void android_view_TextureView_lockCanvas(JNIEnv* env, jobject,
        jint nativeWindow, jobject canvas, jobject dirtyRect) {

    if (!nativeWindow) {
        return;
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
    native_window_lock(window.get(), &buffer, &rect);

    ssize_t bytesCount = buffer.stride * bytesPerPixel(buffer.format);

    SkBitmap bitmap;
    bitmap.setConfig(convertPixelFormat(buffer.format), buffer.width, buffer.height, bytesCount);

    if (buffer.format == WINDOW_FORMAT_RGBX_8888) {
        bitmap.setIsOpaque(true);
    }

    if (buffer.width > 0 && buffer.height > 0) {
        bitmap.setPixels(buffer.bits);
    } else {
        bitmap.setPixels(NULL);
    }

    SET_INT(canvas, gCanvasClassInfo.surfaceFormat, buffer.format);
    SkCanvas* nativeCanvas = (SkCanvas*) GET_INT(canvas, gCanvasClassInfo.nativeCanvas);
    nativeCanvas->setBitmapDevice(bitmap);

    SkRect clipRect;
    clipRect.set(rect.left, rect.top, rect.right, rect.bottom);
    nativeCanvas->clipRect(clipRect);

    if (dirtyRect) {
        INVOKEV(dirtyRect, gRectClassInfo.set,
                int(rect.left), int(rect.top), int(rect.right), int(rect.bottom));
    }
}

static void android_view_TextureView_unlockCanvasAndPost(JNIEnv* env, jobject,
        jint nativeWindow, jobject canvas) {

    SkCanvas* nativeCanvas = (SkCanvas*) GET_INT(canvas, gCanvasClassInfo.nativeCanvas);
    nativeCanvas->setBitmapDevice(SkBitmap());

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
    {   "nSetDefaultBufferSize", "(Landroid/graphics/SurfaceTexture;II)V",
            (void*) android_view_TextureView_setDefaultBufferSize },

    {   "nCreateNativeWindow", "(Landroid/graphics/SurfaceTexture;)V",
            (void*) android_view_TextureView_createNativeWindow },
    {   "nDestroyNativeWindow", "()V",
            (void*) android_view_TextureView_destroyNativeWindow },

    {   "nLockCanvas", "(ILandroid/graphics/Canvas;Landroid/graphics/Rect;)V",
            (void*) android_view_TextureView_lockCanvas },
    {   "nUnlockCanvasAndPost", "(ILandroid/graphics/Canvas;)V",
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
    GET_FIELD_ID(gCanvasClassInfo.nativeCanvas, clazz, "mNativeCanvas", "I");
    GET_FIELD_ID(gCanvasClassInfo.surfaceFormat, clazz, "mSurfaceFormat", "I");

    FIND_CLASS(clazz, "android/view/TextureView");
    GET_FIELD_ID(gTextureViewClassInfo.nativeWindow, clazz, "mNativeWindow", "I");

    return AndroidRuntime::registerNativeMethods(env, kClassPathName, gMethods, NELEM(gMethods));
}

};
