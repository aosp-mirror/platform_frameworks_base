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

#include "core_jni_helpers.h"

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
    SkColorType colorType = kUnknown_SkColorType;
    SkAlphaType alphaType = kOpaque_SkAlphaType;
    switch (buffer.format) {
        case WINDOW_FORMAT_RGBA_8888:
            colorType = kN32_SkColorType;
            alphaType = kPremul_SkAlphaType;
            break;
        case WINDOW_FORMAT_RGBX_8888:
            colorType = kN32_SkColorType;
            alphaType = kOpaque_SkAlphaType;
            break;
        case WINDOW_FORMAT_RGB_565:
            colorType = kRGB_565_SkColorType;
            alphaType = kOpaque_SkAlphaType;
            break;
        default:
            break;
    }
    return SkImageInfo::Make(buffer.width, buffer.height, colorType, alphaType);
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

    Rect rect(Rect::EMPTY_RECT);
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

    Canvas* nativeCanvas = GraphicsJNI::getNativeCanvas(env, canvas);
    nativeCanvas->setBitmap(bitmap);
    nativeCanvas->clipRect(rect.left, rect.top, rect.right, rect.bottom);

    if (dirtyRect) {
        INVOKEV(dirtyRect, gRectClassInfo.set,
                int(rect.left), int(rect.top), int(rect.right), int(rect.bottom));
    }

    return JNI_TRUE;
}

static void android_view_TextureView_unlockCanvasAndPost(JNIEnv* env, jobject,
        jlong nativeWindow, jobject canvas) {

    Canvas* nativeCanvas = GraphicsJNI::getNativeCanvas(env, canvas);
    nativeCanvas->setBitmap(SkBitmap());

    if (nativeWindow) {
        sp<ANativeWindow> window((ANativeWindow*) nativeWindow);
        native_window_unlockAndPost(window.get());
    }
}

// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

const char* const kClassPathName = "android/view/TextureView";

static const JNINativeMethod gMethods[] = {
    {   "nCreateNativeWindow", "(Landroid/graphics/SurfaceTexture;)V",
            (void*) android_view_TextureView_createNativeWindow },
    {   "nDestroyNativeWindow", "()V",
            (void*) android_view_TextureView_destroyNativeWindow },

    {   "nLockCanvas", "(JLandroid/graphics/Canvas;Landroid/graphics/Rect;)Z",
            (void*) android_view_TextureView_lockCanvas },
    {   "nUnlockCanvasAndPost", "(JLandroid/graphics/Canvas;)V",
            (void*) android_view_TextureView_unlockCanvasAndPost },
};

int register_android_view_TextureView(JNIEnv* env) {
    jclass clazz = FindClassOrDie(env, "android/graphics/Rect");
    gRectClassInfo.set = GetMethodIDOrDie(env, clazz, "set", "(IIII)V");
    gRectClassInfo.left = GetFieldIDOrDie(env, clazz, "left", "I");
    gRectClassInfo.top = GetFieldIDOrDie(env, clazz, "top", "I");
    gRectClassInfo.right = GetFieldIDOrDie(env, clazz, "right", "I");
    gRectClassInfo.bottom = GetFieldIDOrDie(env, clazz, "bottom", "I");

    clazz = FindClassOrDie(env, "android/view/TextureView");
    gTextureViewClassInfo.nativeWindow = GetFieldIDOrDie(env, clazz, "mNativeWindow", "J");

    return RegisterMethodsOrDie(env, kClassPathName, gMethods, NELEM(gMethods));
}

};
