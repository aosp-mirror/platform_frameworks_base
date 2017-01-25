/*
 * Copyright (C) 2017 The Android Open Source Project
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

#define LOG_TAG "HardwareBuffer"

#include "jni.h"
#include "JNIHelp.h"

#include "android_os_Parcel.h"
#include "android/graphics/GraphicsJNI.h"

#include <android/hardware_buffer.h>
#include <android_runtime/android_hardware_HardwareBuffer.h>
#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/Log.h>

#include <binder/Parcel.h>
#include <gui/IGraphicBufferAlloc.h>
#include <gui/ISurfaceComposer.h>
#include <ui/GraphicBuffer.h>

#include <private/gui/ComposerService.h>

#include "core_jni_helpers.h"

using namespace android;

// ----------------------------------------------------------------------------
// Defines
// ----------------------------------------------------------------------------

// Debug
static const bool kDebugGraphicBuffer = false;

// ----------------------------------------------------------------------------
// Types
// ----------------------------------------------------------------------------

static struct {
    jclass clazz;
    jfieldID mNativeObject;
    jmethodID ctor;
} gHardwareBufferClassInfo;

class GraphicBufferWrapper {
public:
    explicit GraphicBufferWrapper(const sp<GraphicBuffer>& buffer)
            : buffer(buffer) {}

    sp<GraphicBuffer> buffer;
};


// ----------------------------------------------------------------------------
// Helper functions
// ----------------------------------------------------------------------------

static inline bool containsBits(uint64_t mask, uint64_t bitsToCheck) {
    return (mask & bitsToCheck) == bitsToCheck;
}

// ----------------------------------------------------------------------------
// HardwareBuffer lifecycle
// ----------------------------------------------------------------------------

static jlong android_hardware_HardwareBuffer_create(JNIEnv* env, jobject clazz,
        jint width, jint height, jint format, jint layers, jlong usage) {

    sp<ISurfaceComposer> composer(ComposerService::getComposerService());
    sp<IGraphicBufferAlloc> alloc(composer->createGraphicBufferAlloc());
    if (alloc == NULL) {
        if (kDebugGraphicBuffer) {
            ALOGW("createGraphicBufferAlloc() failed in HardwareBuffer.create()");
        }
        return NULL;
    }

    // TODO: update createGraphicBuffer to take two 64-bit values.
    int pixelFormat = android_hardware_HardwareBuffer_convertToPixelFormat(format);
    if (pixelFormat == 0) {
        if (kDebugGraphicBuffer) {
            ALOGW("createGraphicBufferAlloc() invalid pixel format in HardwareBuffer.create()");
        }
        return NULL;
    }
    uint32_t grallocUsage = android_hardware_HardwareBuffer_convertToGrallocUsageBits(usage, 0);
    status_t error;
    sp<GraphicBuffer> buffer(alloc->createGraphicBuffer(width, height, pixelFormat,
            layers, grallocUsage, &error));
    if (buffer == NULL) {
        if (kDebugGraphicBuffer) {
            ALOGW("createGraphicBuffer() failed in HardwareBuffer.create()");
        }
        return NULL;
    }

    GraphicBufferWrapper* wrapper = new GraphicBufferWrapper(buffer);
    return reinterpret_cast<jlong>(wrapper);
}

static void destroyWrapper(GraphicBufferWrapper* wrapper) {
    delete wrapper;
}

static jlong android_hardware_HardwareBuffer_getNativeFinalizer(JNIEnv* env,
        jobject clazz) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(&destroyWrapper));
}

//----------------------------------------------------------------------------
// Accessors
// ----------------------------------------------------------------------------

static inline GraphicBuffer* GraphicBufferWrapper_to_GraphicBuffer(
        jlong nativeObject) {
    return reinterpret_cast<GraphicBufferWrapper*>(nativeObject)->buffer.get();
}

static jint android_hardware_HardwareBuffer_getWidth(JNIEnv* env, jobject clazz,
    jlong nativeObject) {
    GraphicBuffer* buffer = GraphicBufferWrapper_to_GraphicBuffer(nativeObject);
    return static_cast<jint>(buffer->getWidth());
}

static jint android_hardware_HardwareBuffer_getHeight(JNIEnv* env,
    jobject clazz, jlong nativeObject) {
    GraphicBuffer* buffer = GraphicBufferWrapper_to_GraphicBuffer(nativeObject);
    return static_cast<jint>(buffer->getHeight());
}

static jint android_hardware_HardwareBuffer_getFormat(JNIEnv* env,
    jobject clazz, jlong nativeObject) {
    GraphicBuffer* buffer = GraphicBufferWrapper_to_GraphicBuffer(nativeObject);
    return static_cast<jint>(android_hardware_HardwareBuffer_convertFromPixelFormat(
            buffer->getPixelFormat()));
}

static jint android_hardware_HardwareBuffer_getLayers(JNIEnv* env,
    jobject clazz, jlong nativeObject) {
    GraphicBuffer* buffer = GraphicBufferWrapper_to_GraphicBuffer(nativeObject);
    return static_cast<jint>(buffer->getLayerCount());
}

static jlong android_hardware_HardwareBuffer_getUsage(JNIEnv* env,
    jobject clazz, jlong nativeObject) {
    GraphicBuffer* buffer = GraphicBufferWrapper_to_GraphicBuffer(nativeObject);
    return android_hardware_HardwareBuffer_convertFromGrallocUsageBits(
            buffer->getUsage());
}

// ----------------------------------------------------------------------------
// Serialization
// ----------------------------------------------------------------------------

static void android_hardware_HardwareBuffer_write(JNIEnv* env, jobject clazz,
        jlong nativeObject, jobject dest) {
    GraphicBuffer* buffer = GraphicBufferWrapper_to_GraphicBuffer(nativeObject);
    Parcel* parcel = parcelForJavaObject(env, dest);
    if (parcel) {
        parcel->write(*buffer);
    }
}

static jlong android_hardware_HardwareBuffer_read(JNIEnv* env, jobject clazz,
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
// Public functions
// ----------------------------------------------------------------------------

namespace android {

AHardwareBuffer* android_hardware_HardwareBuffer_getNativeHardwareBuffer(
        JNIEnv* env, jobject hardwareBufferObj) {
    if (env->IsInstanceOf(hardwareBufferObj, gHardwareBufferClassInfo.clazz)) {
        GraphicBuffer* buffer = GraphicBufferWrapper_to_GraphicBuffer(
                env->GetLongField(hardwareBufferObj, gHardwareBufferClassInfo.mNativeObject));
        return reinterpret_cast<AHardwareBuffer*>(buffer);
    } else {
        return nullptr;
    }
}

jobject android_hardware_HardwareBuffer_createFromAHardwareBuffer(
        JNIEnv* env, AHardwareBuffer* hardwareBuffer) {
    GraphicBuffer* buffer = reinterpret_cast<GraphicBuffer*>(hardwareBuffer);
    GraphicBufferWrapper* wrapper = new GraphicBufferWrapper(buffer);
    jobject hardwareBufferObj = env->NewObject(gHardwareBufferClassInfo.clazz,
            gHardwareBufferClassInfo.ctor, reinterpret_cast<jlong>(wrapper));
    if (hardwareBufferObj == NULL) {
        delete wrapper;
        if (env->ExceptionCheck()) {
            ALOGE("Could not create instance of HardwareBuffer from AHardwareBuffer.");
            LOGE_EX(env);
            env->ExceptionClear();
        }
        return nullptr;
    }
    return hardwareBufferObj;
}

uint32_t android_hardware_HardwareBuffer_convertFromPixelFormat(uint32_t format) {
    switch (format) {
        case HAL_PIXEL_FORMAT_RGBA_8888:
            return AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
        case HAL_PIXEL_FORMAT_RGBX_8888:
            return AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM;
        case HAL_PIXEL_FORMAT_RGB_565:
            return AHARDWAREBUFFER_FORMAT_R5G6B5_UNORM;
        case HAL_PIXEL_FORMAT_RGB_888:
            return AHARDWAREBUFFER_FORMAT_R8G8B8_UNORM;
        case HAL_PIXEL_FORMAT_RGBA_FP16:
            return AHARDWAREBUFFER_FORMAT_R16G16B16A16_SFLOAT;
        case HAL_PIXEL_FORMAT_BLOB:
            return AHARDWAREBUFFER_FORMAT_BLOB;
        default:
            ALOGE("Unknown pixel format %u", format);
            return 0;
    }
}

uint32_t android_hardware_HardwareBuffer_convertToPixelFormat(uint32_t format) {
    switch (format) {
        case AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM:
            return HAL_PIXEL_FORMAT_RGBA_8888;
        case AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM:
            return HAL_PIXEL_FORMAT_RGBX_8888;
        case AHARDWAREBUFFER_FORMAT_R5G6B5_UNORM:
            return HAL_PIXEL_FORMAT_RGB_565;
        case AHARDWAREBUFFER_FORMAT_R8G8B8_UNORM:
            return HAL_PIXEL_FORMAT_RGB_888;
        case AHARDWAREBUFFER_FORMAT_R16G16B16A16_SFLOAT:
            return HAL_PIXEL_FORMAT_RGBA_FP16;
        case AHARDWAREBUFFER_FORMAT_BLOB:
            return HAL_PIXEL_FORMAT_BLOB;
        default:
            ALOGE("Unknown AHardwareBuffer format %u", format);
            return 0;
    }
}

uint32_t android_hardware_HardwareBuffer_convertToGrallocUsageBits(uint64_t usage0,
        uint64_t usage1) {
    uint32_t bits = 0;
    if (containsBits(usage0, AHARDWAREBUFFER_USAGE0_CPU_READ))
        bits |= GRALLOC_USAGE_SW_READ_RARELY;
    if (containsBits(usage0, AHARDWAREBUFFER_USAGE0_CPU_READ_OFTEN))
        bits |= GRALLOC_USAGE_SW_READ_OFTEN;
    if (containsBits(usage0, AHARDWAREBUFFER_USAGE0_CPU_WRITE))
        bits |= GRALLOC_USAGE_SW_WRITE_RARELY;
    if (containsBits(usage0, AHARDWAREBUFFER_USAGE0_CPU_WRITE_OFTEN))
        bits |= GRALLOC_USAGE_SW_WRITE_OFTEN;
    if (containsBits(usage0, AHARDWAREBUFFER_USAGE0_GPU_SAMPLED_IMAGE))
        bits |= GRALLOC_USAGE_HW_TEXTURE;
    if (containsBits(usage0, AHARDWAREBUFFER_USAGE0_GPU_COLOR_OUTPUT))
        bits |= GRALLOC_USAGE_HW_RENDER;
    // Not sure what this should be.
    if (containsBits(usage0, AHARDWAREBUFFER_USAGE0_GPU_CUBEMAP)) bits |= 0;
    //if (containsBits(usage0, AHARDWAREBUFFER_USAGE0_GPU_DATA_BUFFER) bits |= 0;
    if (containsBits(usage0, AHARDWAREBUFFER_USAGE0_VIDEO_ENCODE))
        bits |= GRALLOC_USAGE_HW_VIDEO_ENCODER;
    if (containsBits(usage0, AHARDWAREBUFFER_USAGE0_PROTECTED_CONTENT))
        bits |= GRALLOC_USAGE_PROTECTED;

    (void)usage1;

    return bits;
}

uint64_t android_hardware_HardwareBuffer_convertFromGrallocUsageBits(uint64_t usage0) {
    uint64_t bits = 0;
    if (containsBits(usage0, GRALLOC_USAGE_SW_READ_RARELY))
        bits |= AHARDWAREBUFFER_USAGE0_CPU_READ;
    if (containsBits(usage0, GRALLOC_USAGE_SW_READ_OFTEN))
        bits |= AHARDWAREBUFFER_USAGE0_CPU_READ_OFTEN;
    if (containsBits(usage0, GRALLOC_USAGE_SW_WRITE_RARELY))
        bits |= AHARDWAREBUFFER_USAGE0_CPU_WRITE;
    if (containsBits(usage0, GRALLOC_USAGE_SW_WRITE_OFTEN))
        bits |= AHARDWAREBUFFER_USAGE0_CPU_WRITE_OFTEN;
    if (containsBits(usage0, GRALLOC_USAGE_HW_TEXTURE))
        bits |= AHARDWAREBUFFER_USAGE0_GPU_SAMPLED_IMAGE;
    if (containsBits(usage0, GRALLOC_USAGE_HW_RENDER))
        bits |= AHARDWAREBUFFER_USAGE0_GPU_COLOR_OUTPUT;
    if (containsBits(usage0, GRALLOC_USAGE_HW_VIDEO_ENCODER))
        bits |= AHARDWAREBUFFER_USAGE0_VIDEO_ENCODE;
    if (containsBits(usage0, GRALLOC_USAGE_PROTECTED))
        bits |= AHARDWAREBUFFER_USAGE0_PROTECTED_CONTENT;

    return bits;
}

}  // namespace android

// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

const char* const kClassPathName = "android/hardware/HardwareBuffer";

static const JNINativeMethod gMethods[] = {
    { "nCreateHardwareBuffer",  "(IIIIJ)J", (void*) android_hardware_HardwareBuffer_create },
    { "nGetNativeFinalizer", "()J",          (void*) android_hardware_HardwareBuffer_getNativeFinalizer },
    { "nWriteHardwareBufferToParcel",  "(JLandroid/os/Parcel;)V",
            (void*) android_hardware_HardwareBuffer_write },
    { "nReadHardwareBufferFromParcel", "(Landroid/os/Parcel;)J",
            (void*) android_hardware_HardwareBuffer_read },

    // --------------- @FastNative ----------------------
    { "nGetWidth", "(J)I",                   (void*) android_hardware_HardwareBuffer_getWidth },
    { "nGetHeight", "(J)I",                  (void*) android_hardware_HardwareBuffer_getHeight },
    { "nGetFormat", "(J)I",                  (void*) android_hardware_HardwareBuffer_getFormat },
    { "nGetLayers", "(J)I",                  (void*) android_hardware_HardwareBuffer_getLayers },
    { "nGetUsage", "(J)J",                  (void*) android_hardware_HardwareBuffer_getUsage },
};

int register_android_hardware_HardwareBuffer(JNIEnv* env) {
    int err = RegisterMethodsOrDie(env, kClassPathName, gMethods,
            NELEM(gMethods));

    jclass clazz = FindClassOrDie(env, "android/hardware/HardwareBuffer");
    gHardwareBufferClassInfo.clazz = MakeGlobalRefOrDie(env, clazz);
    gHardwareBufferClassInfo.mNativeObject = GetFieldIDOrDie(env,
            gHardwareBufferClassInfo.clazz, "mNativeObject", "J");
    gHardwareBufferClassInfo.ctor = GetMethodIDOrDie(env,
            gHardwareBufferClassInfo.clazz, "<init>", "(J)V");

    return err;
}
