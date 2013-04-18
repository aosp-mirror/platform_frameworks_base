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

#include <android_runtime/AndroidRuntime.h>

#include <binder/Parcel.h>

#include <ui/GraphicBuffer.h>
#include <ui/PixelFormat.h>

#include <gui/IGraphicBufferAlloc.h>
#include <gui/ISurfaceComposer.h>

#include <SkCanvas.h>
#include <SkBitmap.h>

#include <private/gui/ComposerService.h>

namespace android {

// ----------------------------------------------------------------------------
// Defines
// ----------------------------------------------------------------------------

// Debug
#define DEBUG_GRAPHIC_BUFFER 0

// Debug
#if DEBUG_GRAPHIC_BUFFER
    #define GB_LOGD(...) ALOGD(__VA_ARGS__)
    #define GB_LOGW(...) ALOGW(__VA_ARGS__)
#else
    #define GB_LOGD(...)
    #define GB_LOGW(...)
#endif

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

static struct {
    jfieldID mFinalizer;
    jfieldID mNativeCanvas;
    jfieldID mSurfaceFormat;
} gCanvasClassInfo;

static struct {
    jfieldID mNativeCanvas;
} gCanvasFinalizerClassInfo;

#define GET_INT(object, field) \
    env->GetIntField(object, field)

#define SET_INT(object, field, value) \
    env->SetIntField(object, field, value)

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

static GraphicBufferWrapper* android_view_GraphiceBuffer_create(JNIEnv* env, jobject clazz,
        jint width, jint height, jint format, jint usage) {

    sp<ISurfaceComposer> composer(ComposerService::getComposerService());
    sp<IGraphicBufferAlloc> alloc(composer->createGraphicBufferAlloc());
    if (alloc == NULL) {
        GB_LOGW("createGraphicBufferAlloc() failed in GraphicBuffer.create()");
        return NULL;
    }

    status_t error;
    sp<GraphicBuffer> buffer(alloc->createGraphicBuffer(width, height, format, usage, &error));
    if (buffer == NULL) {
        GB_LOGW("createGraphicBuffer() failed in GraphicBuffer.create()");
        return NULL;
    }

    return new GraphicBufferWrapper(buffer);
}

static void android_view_GraphiceBuffer_destroy(JNIEnv* env, jobject clazz,
        GraphicBufferWrapper* wrapper) {
    delete wrapper;
}

// ----------------------------------------------------------------------------
// Canvas management
// ----------------------------------------------------------------------------

static inline void swapCanvasPtr(JNIEnv* env, jobject canvasObj, SkCanvas* newCanvas) {
    jobject canvasFinalizerObj = env->GetObjectField(canvasObj, gCanvasClassInfo.mFinalizer);
    SkCanvas* previousCanvas = reinterpret_cast<SkCanvas*>(
            GET_INT(canvasObj, gCanvasClassInfo.mNativeCanvas));
    SET_INT(canvasObj, gCanvasClassInfo.mNativeCanvas, (int) newCanvas);
    SET_INT(canvasFinalizerObj, gCanvasFinalizerClassInfo.mNativeCanvas, (int) newCanvas);
    SkSafeUnref(previousCanvas);
}

static inline SkBitmap::Config convertPixelFormat(int32_t format) {
    switch (format) {
        case PIXEL_FORMAT_RGBA_8888:
            return SkBitmap::kARGB_8888_Config;
        case PIXEL_FORMAT_RGBX_8888:
            return SkBitmap::kARGB_8888_Config;
        case PIXEL_FORMAT_RGB_565:
            return SkBitmap::kRGB_565_Config;
        default:
            return SkBitmap::kNo_Config;
    }
}

static jboolean android_view_GraphicBuffer_lockCanvas(JNIEnv* env, jobject,
        GraphicBufferWrapper* wrapper, jobject canvas, jobject dirtyRect) {

    if (!wrapper) {
        return false;
    }

    sp<GraphicBuffer> buffer(wrapper->buffer);

    Rect rect;
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

    if (status) return false;
    if (!bits) {
        buffer->unlock();
        return false;
    }

    ssize_t bytesCount = buffer->getStride() * bytesPerPixel(buffer->getPixelFormat());

    SkBitmap bitmap;
    bitmap.setConfig(convertPixelFormat(buffer->getPixelFormat()),
            buffer->getWidth(), buffer->getHeight(), bytesCount);

    if (buffer->getWidth() > 0 && buffer->getHeight() > 0) {
        bitmap.setPixels(bits);
    } else {
        bitmap.setPixels(NULL);
    }

    SET_INT(canvas, gCanvasClassInfo.mSurfaceFormat, buffer->getPixelFormat());

    SkCanvas* nativeCanvas = SkNEW_ARGS(SkCanvas, (bitmap));
    swapCanvasPtr(env, canvas, nativeCanvas);

    SkRect clipRect;
    clipRect.set(rect.left, rect.top, rect.right, rect.bottom);
    nativeCanvas->clipRect(clipRect);

    if (dirtyRect) {
        INVOKEV(dirtyRect, gRectClassInfo.set,
                int(rect.left), int(rect.top), int(rect.right), int(rect.bottom));
    }

    return true;
}

static jboolean android_view_GraphicBuffer_unlockCanvasAndPost(JNIEnv* env, jobject,
        GraphicBufferWrapper* wrapper, jobject canvas) {

    SkCanvas* nativeCanvas = SkNEW(SkCanvas);
    swapCanvasPtr(env, canvas, nativeCanvas);

    if (wrapper) {
        status_t status = wrapper->buffer->unlock();
        return status == 0;
    }

    return false;
}

// ----------------------------------------------------------------------------
// Serialization
// ----------------------------------------------------------------------------

static void android_view_GraphiceBuffer_write(JNIEnv* env, jobject clazz,
        GraphicBufferWrapper* wrapper, jobject dest) {
    Parcel* parcel = parcelForJavaObject(env, dest);
    if (parcel) {
        parcel->write(*wrapper->buffer);
    }
}

static GraphicBufferWrapper* android_view_GraphiceBuffer_read(JNIEnv* env, jobject clazz,
        jobject in) {

    Parcel* parcel = parcelForJavaObject(env, in);
    if (parcel) {
        sp<GraphicBuffer> buffer = new GraphicBuffer();
        parcel->read(*buffer);
        return new GraphicBufferWrapper(buffer);
    }

    return NULL;
}

// ----------------------------------------------------------------------------
// External helpers
// ----------------------------------------------------------------------------

sp<GraphicBuffer> graphicBufferForJavaObject(JNIEnv* env, jobject obj) {
    if (obj) {
        jint nativeObject = env->GetIntField(obj, gGraphicBufferClassInfo.mNativeObject);
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

#define FIND_CLASS(var, className) \
        var = env->FindClass(className); \
        LOG_FATAL_IF(! var, "Unable to find class " className);

#define GET_FIELD_ID(var, clazz, fieldName, fieldDescriptor) \
        var = env->GetFieldID(clazz, fieldName, fieldDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find field " fieldName);

#define GET_METHOD_ID(var, clazz, methodName, methodDescriptor) \
        var = env->GetMethodID(clazz, methodName, methodDescriptor); \
        LOG_FATAL_IF(!var, "Unable to find method " methodName);

const char* const kClassPathName = "android/view/GraphicBuffer";

static JNINativeMethod gMethods[] = {
    { "nCreateGraphicBuffer",  "(IIII)I", (void*) android_view_GraphiceBuffer_create },
    { "nDestroyGraphicBuffer", "(I)V",    (void*) android_view_GraphiceBuffer_destroy },

    { "nWriteGraphicBufferToParcel",  "(ILandroid/os/Parcel;)V",
            (void*) android_view_GraphiceBuffer_write },
    { "nReadGraphicBufferFromParcel", "(Landroid/os/Parcel;)I",
            (void*) android_view_GraphiceBuffer_read },

    { "nLockCanvas", "(ILandroid/graphics/Canvas;Landroid/graphics/Rect;)Z",
            (void*) android_view_GraphicBuffer_lockCanvas },
    { "nUnlockCanvasAndPost", "(ILandroid/graphics/Canvas;)Z",
            (void*) android_view_GraphicBuffer_unlockCanvasAndPost },
};

int register_android_view_GraphicBuffer(JNIEnv* env) {
    jclass clazz;
    FIND_CLASS(clazz, "android/view/GraphicBuffer");
    GET_FIELD_ID(gGraphicBufferClassInfo.mNativeObject, clazz, "mNativeObject", "I");

    FIND_CLASS(clazz, "android/graphics/Rect");
    GET_METHOD_ID(gRectClassInfo.set, clazz, "set", "(IIII)V");
    GET_FIELD_ID(gRectClassInfo.left, clazz, "left", "I");
    GET_FIELD_ID(gRectClassInfo.top, clazz, "top", "I");
    GET_FIELD_ID(gRectClassInfo.right, clazz, "right", "I");
    GET_FIELD_ID(gRectClassInfo.bottom, clazz, "bottom", "I");

    FIND_CLASS(clazz, "android/graphics/Canvas");
    GET_FIELD_ID(gCanvasClassInfo.mFinalizer, clazz, "mFinalizer",
            "Landroid/graphics/Canvas$CanvasFinalizer;");
    GET_FIELD_ID(gCanvasClassInfo.mNativeCanvas, clazz, "mNativeCanvas", "I");
    GET_FIELD_ID(gCanvasClassInfo.mSurfaceFormat, clazz, "mSurfaceFormat", "I");

    FIND_CLASS(clazz, "android/graphics/Canvas$CanvasFinalizer");
    GET_FIELD_ID(gCanvasFinalizerClassInfo.mNativeCanvas, clazz, "mNativeCanvas", "I");

    return AndroidRuntime::registerNativeMethods(env, kClassPathName, gMethods, NELEM(gMethods));
}

};
