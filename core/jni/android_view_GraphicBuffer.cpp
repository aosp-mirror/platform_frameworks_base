/*
 * Copyright (C) 2013 The Android Open Source Project
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

#define LOG_TAG "GraphicBuffer"

#include "jni.h"
#include "JNIHelp.h"

#include "android_os_Parcel.h"
#include "android_view_GraphicBuffer.h"
#include "android/graphics/GraphicsJNI.h"

#include <android_runtime/AndroidRuntime.h>

#include <binder/Parcel.h>

#include <ui/GraphicBuffer.h>
#include <ui/PixelFormat.h>

#include <gui/IGraphicBufferAlloc.h>
#include <gui/ISurfaceComposer.h>

#include <SkCanvas.h>
#include <SkBitmap.h>

#include <private/gui/ComposerService.h>

#include "core_jni_helpers.h"

namespace android {

// ----------------------------------------------------------------------------
// Defines
// ----------------------------------------------------------------------------

// Debug
static const bool kDebugGraphicBuffer = false;

#define LOCK_CANVAS_USAGE GraphicBuffer::USAGE_SW_READ_OFTEN | GraphicBuffer::USAGE_SW_WRITE_OFTEN

// ----------------------------------------------------------------------------
// JNI Helpers
// ----------------------------------------------------------------------------

static struct {
    jfieldID mNativeObject;
} gGraphicBufferClassInfo;

static struct {
    jmethodID set;
    jfieldID left;
    jfieldID top;
    jfieldID right;
    jfieldID bottom;
} gRectClassInfo;

#define GET_INT(object, field) \
    env->GetIntField(object, field)

#define SET_INT(object, field, value) \
    env->SetIntField(object, field, value)

#define GET_LONG(object, field) \
    env->GetLongField(object, field)

#define SET_LONG(object, field, value) \
    env->SetLongField(object, field, value)

#define INVOKEV(object, method, ...) \
    env->CallVoidMethod(object, method, __VA_ARGS__)

// ----------------------------------------------------------------------------
// Types
// ----------------------------------------------------------------------------

class GraphicBufferWrapper {
public:
    GraphicBufferWrapper(const sp<GraphicBuffer>& buffer): buffer(buffer) {
    }

    sp<GraphicBuffer> buffer;
};

// ----------------------------------------------------------------------------
// GraphicBuffer lifecycle
// ----------------------------------------------------------------------------

static jlong android_view_GraphiceBuffer_create(JNIEnv* env, jobject clazz,
        jint width, jint height, jint format, jint usage) {

    sp<ISurfaceComposer> composer(ComposerService::getComposerService());
    sp<IGraphicBufferAlloc> alloc(composer->createGraphicBufferAlloc());
    if (alloc == NULL) {
        if (kDebugGraphicBuffer) {
            ALOGW("createGraphicBufferAlloc() failed in GraphicBuffer.create()");
        }
        return NULL;
    }

    status_t error;
    sp<GraphicBuffer> buffer(alloc->createGraphicBuffer(width, height, format, usage, &error));
    if (buffer == NULL) {
        if (kDebugGraphicBuffer) {
            ALOGW("createGraphicBuffer() failed in GraphicBuffer.create()");
        }
        return NULL;
    }

    GraphicBufferWrapper* wrapper = new GraphicBufferWrapper(buffer);
    return reinterpret_cast<jlong>(wrapper);
}

static void android_view_GraphiceBuffer_destroy(JNIEnv* env, jobject clazz,
        jlong wrapperHandle) {
    GraphicBufferWrapper* wrapper =
                reinterpret_cast<GraphicBufferWrapper*>(wrapperHandle);
    delete wrapper;
}

// ----------------------------------------------------------------------------
// Canvas management
// ----------------------------------------------------------------------------

static inline SkColorType convertPixelFormat(int32_t format) {
    switch (format) {
        case PIXEL_FORMAT_RGBA_8888:
            return kN32_SkColorType;
        case PIXEL_FORMAT_RGBX_8888:
            return kN32_SkColorType;
        case PIXEL_FORMAT_RGB_565:
            return kRGB_565_SkColorType;
        default:
            return kUnknown_SkColorType;
    }
}

static jboolean android_view_GraphicBuffer_lockCanvas(JNIEnv* env, jobject,
        jlong wrapperHandle, jobject canvas, jobject dirtyRect) {

    GraphicBufferWrapper* wrapper =
                reinterpret_cast<GraphicBufferWrapper*>(wrapperHandle);
    if (!wrapper) {
        return JNI_FALSE;
    }

    sp<GraphicBuffer> buffer(wrapper->buffer);

    Rect rect(Rect::EMPTY_RECT);
    if (dirtyRect) {
        rect.left = GET_INT(dirtyRect, gRectClassInfo.left);
        rect.top = GET_INT(dirtyRect, gRectClassInfo.top);
        rect.right = GET_INT(dirtyRect, gRectClassInfo.right);
        rect.bottom = GET_INT(dirtyRect, gRectClassInfo.bottom);
    } else {
        rect.set(Rect(buffer->getWidth(), buffer->getHeight()));
    }

    void* bits = NULL;
    status_t status = buffer->lock(LOCK_CANVAS_USAGE, rect, &bits);

    if (status) return JNI_FALSE;
    if (!bits) {
        buffer->unlock();
        return JNI_FALSE;
    }

    ssize_t bytesCount = buffer->getStride() * bytesPerPixel(buffer->getPixelFormat());

    SkBitmap bitmap;
    bitmap.setInfo(SkImageInfo::Make(buffer->getWidth(), buffer->getHeight(),
                                     convertPixelFormat(buffer->getPixelFormat()),
                                     kPremul_SkAlphaType),
                   bytesCount);

    if (buffer->getWidth() > 0 && buffer->getHeight() > 0) {
        bitmap.setPixels(bits);
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

static jboolean android_view_GraphicBuffer_unlockCanvasAndPost(JNIEnv* env, jobject,
        jlong wrapperHandle, jobject canvas) {

    GraphicBufferWrapper* wrapper =
                reinterpret_cast<GraphicBufferWrapper*>(wrapperHandle);
    Canvas* nativeCanvas = GraphicsJNI::getNativeCanvas(env, canvas);
    nativeCanvas->setBitmap(SkBitmap());

    if (wrapper) {
        status_t status = wrapper->buffer->unlock();
        return status == 0 ? JNI_TRUE : JNI_FALSE;
    }

    return JNI_FALSE;
}

// ----------------------------------------------------------------------------
// Serialization
// ----------------------------------------------------------------------------

static void android_view_GraphiceBuffer_write(JNIEnv* env, jobject clazz,
        jlong wrapperHandle, jobject dest) {
    GraphicBufferWrapper* wrapper =
                reinterpret_cast<GraphicBufferWrapper*>(wrapperHandle);
    Parcel* parcel = parcelForJavaObject(env, dest);
    if (parcel) {
        parcel->write(*wrapper->buffer);
    }
}

static jlong android_view_GraphiceBuffer_read(JNIEnv* env, jobject clazz,
        jobject in) {

    Parcel* parcel = parcelForJavaObject(env, in);
    if (parcel) {
        sp<GraphicBuffer> buffer = new GraphicBuffer();
        parcel->read(*buffer);
        return reinterpret_cast<jlong>(new GraphicBufferWrapper(buffer));
    }

    return NULL;
}

// ----------------------------------------------------------------------------
// External helpers
// ----------------------------------------------------------------------------

sp<GraphicBuffer> graphicBufferForJavaObject(JNIEnv* env, jobject obj) {
    if (obj) {
        jlong nativeObject = env->GetLongField(obj, gGraphicBufferClassInfo.mNativeObject);
        GraphicBufferWrapper* wrapper = (GraphicBufferWrapper*) nativeObject;
        if (wrapper != NULL) {
            sp<GraphicBuffer> buffer(wrapper->buffer);
            return buffer;
        }
    }
    return NULL;
}

// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

const char* const kClassPathName = "android/view/GraphicBuffer";

static const JNINativeMethod gMethods[] = {
    { "nCreateGraphicBuffer",  "(IIII)J", (void*) android_view_GraphiceBuffer_create },
    { "nDestroyGraphicBuffer", "(J)V",    (void*) android_view_GraphiceBuffer_destroy },

    { "nWriteGraphicBufferToParcel",  "(JLandroid/os/Parcel;)V",
            (void*) android_view_GraphiceBuffer_write },
    { "nReadGraphicBufferFromParcel", "(Landroid/os/Parcel;)J",
            (void*) android_view_GraphiceBuffer_read },

    { "nLockCanvas", "(JLandroid/graphics/Canvas;Landroid/graphics/Rect;)Z",
            (void*) android_view_GraphicBuffer_lockCanvas },
    { "nUnlockCanvasAndPost", "(JLandroid/graphics/Canvas;)Z",
            (void*) android_view_GraphicBuffer_unlockCanvasAndPost },
};

int register_android_view_GraphicBuffer(JNIEnv* env) {
    jclass clazz = FindClassOrDie(env, "android/view/GraphicBuffer");
    gGraphicBufferClassInfo.mNativeObject = GetFieldIDOrDie(env, clazz, "mNativeObject", "J");

    clazz = FindClassOrDie(env, "android/graphics/Rect");
    gRectClassInfo.set = GetMethodIDOrDie(env, clazz, "set", "(IIII)V");
    gRectClassInfo.left = GetFieldIDOrDie(env, clazz, "left", "I");
    gRectClassInfo.top = GetFieldIDOrDie(env, clazz, "top", "I");
    gRectClassInfo.right = GetFieldIDOrDie(env, clazz, "right", "I");
    gRectClassInfo.bottom = GetFieldIDOrDie(env, clazz, "bottom", "I");

    return RegisterMethodsOrDie(env, kClassPathName, gMethods, NELEM(gMethods));
}

};
