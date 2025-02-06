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
#undef ANDROID_UTILS_REF_BASE_DISABLE_IMPLICIT_CONSTRUCTION // TODO:remove this and fix code

#define LOG_TAG "GraphicBuffer"

#include "jni.h"
#include <nativehelper/JNIHelp.h>
#include <inttypes.h>

#include "android_os_Parcel.h"
#include <binder/Parcel.h>

#include <log/log.h>

#include <ui/GraphicBuffer.h>
#include <ui/PixelFormat.h>

#include <android/native_window.h>
#include <android/graphics/canvas.h>
#include <android_runtime/android_graphics_GraphicBuffer.h>
#include <android_runtime/android_hardware_HardwareBuffer.h>
#include <private/android/AHardwareBufferHelpers.h>

#include <private/gui/ComposerService.h>

#include "core_jni_helpers.h"

namespace android {

// ----------------------------------------------------------------------------
// Defines
// ----------------------------------------------------------------------------

// Debug
static const bool kDebugGraphicBuffer = false;

#define LOCK_CANVAS_USAGE (GraphicBuffer::USAGE_SW_READ_OFTEN | GraphicBuffer::USAGE_SW_WRITE_OFTEN)

// ----------------------------------------------------------------------------
// JNI Helpers
// ----------------------------------------------------------------------------

static struct {
    jfieldID mNativeObject;
    jclass mClass;
    jmethodID mConstructorMethodID;
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
    explicit GraphicBufferWrapper(const sp<GraphicBuffer>& buffer): buffer(buffer) {
        LOG_ALWAYS_FATAL_IF(buffer == nullptr, "creating a null GraphicBuffer");
    }
    const sp<GraphicBuffer>& get() const {
        return buffer;
    }

private:
    // make sure this is immutable
    sp<GraphicBuffer> const buffer;
};

// ----------------------------------------------------------------------------
// GraphicBuffer lifecycle
// ----------------------------------------------------------------------------

static jlong android_graphics_GraphicBuffer_create(JNIEnv* env, jobject clazz,
        jint width, jint height, jint format, jint usage) {

    sp<GraphicBuffer> buffer = new GraphicBuffer(
            uint32_t(width), uint32_t(height), PixelFormat(format), uint32_t(usage),
            std::string("android_graphics_GraphicBuffer_create pid [") +
                    std::to_string(getpid()) +"]");

    status_t error = buffer->initCheck();
    if (error < 0) {
        ALOGW_IF(kDebugGraphicBuffer, "createGraphicBuffer() failed in GraphicBuffer.create()");
        return NULL;
    }

    GraphicBufferWrapper* wrapper = new GraphicBufferWrapper(buffer);
    return reinterpret_cast<jlong>(wrapper);
}

static void android_graphics_GraphicBuffer_destroy(JNIEnv* env, jobject clazz,
        jlong wrapperHandle) {
    GraphicBufferWrapper* wrapper =
                reinterpret_cast<GraphicBufferWrapper*>(wrapperHandle);
    delete wrapper;
}

// ----------------------------------------------------------------------------
// Canvas management
// ----------------------------------------------------------------------------

static jboolean android_graphics_GraphicBuffer_lockCanvas(JNIEnv* env, jobject,
        jlong wrapperHandle, jobject canvasObj, jobject dirtyRect) {

    GraphicBufferWrapper* wrapper =
                reinterpret_cast<GraphicBufferWrapper*>(wrapperHandle);
    if (!wrapper) {
        return JNI_FALSE;
    }

    sp<GraphicBuffer> buffer(wrapper->get());

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

    ANativeWindow_Buffer nativeBuffer;
    nativeBuffer.width = buffer->getWidth();
    nativeBuffer.height = buffer->getHeight();
    nativeBuffer.stride = buffer->getStride();
    nativeBuffer.format = AHardwareBuffer_convertFromPixelFormat(buffer->getPixelFormat());
    nativeBuffer.bits = bits;

    graphics::Canvas canvas(env, canvasObj);
    canvas.setBuffer(&nativeBuffer, ADATASPACE_UNKNOWN);
    canvas.clipRect({rect.left, rect.top, rect.right, rect.bottom});

    if (dirtyRect) {
        INVOKEV(dirtyRect, gRectClassInfo.set,
                int(rect.left), int(rect.top), int(rect.right), int(rect.bottom));
    }

    return JNI_TRUE;
}

static jboolean android_graphics_GraphicBuffer_unlockCanvasAndPost(JNIEnv* env, jobject,
        jlong wrapperHandle, jobject canvasObj) {
    // release the buffer from the canvas
    graphics::Canvas canvas(env, canvasObj);
    canvas.setBuffer(nullptr, ADATASPACE_UNKNOWN);

    GraphicBufferWrapper* wrapper =
                reinterpret_cast<GraphicBufferWrapper*>(wrapperHandle);
    if (wrapper) {
        status_t status = wrapper->get()->unlock();
        return status == 0 ? JNI_TRUE : JNI_FALSE;
    }

    return JNI_FALSE;
}

// ----------------------------------------------------------------------------
// Serialization
// ----------------------------------------------------------------------------

static void android_graphics_GraphicBuffer_write(JNIEnv* env, jobject clazz,
        jlong wrapperHandle, jobject dest) {

    GraphicBufferWrapper* wrapper =
                reinterpret_cast<GraphicBufferWrapper*>(wrapperHandle);
    Parcel* parcel = parcelForJavaObject(env, dest);
    if (parcel) {
        parcel->write(*wrapper->get());
    }
}

static jlong android_graphics_GraphicBuffer_read(JNIEnv* env, jobject clazz,
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

sp<GraphicBuffer> android_graphics_GraphicBuffer_getNativeGraphicsBuffer(JNIEnv* env, jobject obj) {
    if (obj) {
        jlong nativeObject = env->GetLongField(obj, gGraphicBufferClassInfo.mNativeObject);
        GraphicBufferWrapper* wrapper = (GraphicBufferWrapper*) nativeObject;
        if (wrapper != NULL) {
            sp<GraphicBuffer> buffer(wrapper->get());
            return buffer;
        }
    }
    return NULL;
}

jobject android_graphics_GraphicBuffer_createFromAHardwareBuffer(JNIEnv* env,
                                                                 AHardwareBuffer* hardwareBuffer) {
    GraphicBuffer* buffer = GraphicBuffer::fromAHardwareBuffer(hardwareBuffer);
    GraphicBufferWrapper* wrapper = new GraphicBufferWrapper(buffer);
    jobject obj = env->NewObject(gGraphicBufferClassInfo.mClass,
            gGraphicBufferClassInfo.mConstructorMethodID, buffer->getWidth(), buffer->getHeight(),
            buffer->getPixelFormat(), (jint)buffer->getUsage(), reinterpret_cast<jlong>(wrapper));
    return obj;
}

// ----------------------------------------------------------------------------
// AHB to GraphicBuffer Converter
// ----------------------------------------------------------------------------

static jobject android_graphics_GraphicBuffer_createFromHardwareBuffer(JNIEnv* env, jobject clazz,
                                                                       jobject hb) {
    AHardwareBuffer* ahb = android_hardware_HardwareBuffer_getNativeHardwareBuffer(env, hb);
    return android_graphics_GraphicBuffer_createFromAHardwareBuffer(env, ahb);
}

};

using namespace android;
// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

const char* const kClassPathName = "android/graphics/GraphicBuffer";

static const JNINativeMethod gMethods[] = {
    { "nCreateGraphicBuffer",  "(IIII)J", (void*) android_graphics_GraphicBuffer_create },
    { "nDestroyGraphicBuffer", "(J)V",    (void*) android_graphics_GraphicBuffer_destroy },

    { "nWriteGraphicBufferToParcel",  "(JLandroid/os/Parcel;)V",
            (void*) android_graphics_GraphicBuffer_write },
    { "nReadGraphicBufferFromParcel", "(Landroid/os/Parcel;)J",
            (void*) android_graphics_GraphicBuffer_read },

    { "nLockCanvas", "(JLandroid/graphics/Canvas;Landroid/graphics/Rect;)Z",
            (void*) android_graphics_GraphicBuffer_lockCanvas },
    { "nUnlockCanvasAndPost", "(JLandroid/graphics/Canvas;)Z",
            (void*) android_graphics_GraphicBuffer_unlockCanvasAndPost },
    { "nCreateFromHardwareBuffer",
            "(Landroid/hardware/HardwareBuffer;)Landroid/graphics/GraphicBuffer;",
            (void*) android_graphics_GraphicBuffer_createFromHardwareBuffer }
};

int register_android_graphics_GraphicBuffer(JNIEnv* env) {
    gGraphicBufferClassInfo.mClass = MakeGlobalRefOrDie(env, FindClassOrDie(env, kClassPathName));
    gGraphicBufferClassInfo.mNativeObject = GetFieldIDOrDie(env, gGraphicBufferClassInfo.mClass,
            "mNativeObject", "J");
    gGraphicBufferClassInfo.mConstructorMethodID = env->GetMethodID(gGraphicBufferClassInfo.mClass,
            "<init>", "(IIIIJ)V");

    jclass clazz = FindClassOrDie(env, "android/graphics/Rect");
    gRectClassInfo.set = GetMethodIDOrDie(env, clazz, "set", "(IIII)V");
    gRectClassInfo.left = GetFieldIDOrDie(env, clazz, "left", "I");
    gRectClassInfo.top = GetFieldIDOrDie(env, clazz, "top", "I");
    gRectClassInfo.right = GetFieldIDOrDie(env, clazz, "right", "I");
    gRectClassInfo.bottom = GetFieldIDOrDie(env, clazz, "bottom", "I");

    return RegisterMethodsOrDie(env, kClassPathName, gMethods, NELEM(gMethods));
}
