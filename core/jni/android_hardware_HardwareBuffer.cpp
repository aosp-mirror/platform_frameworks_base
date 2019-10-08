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
#include <nativehelper/JNIHelp.h>

#include "android_os_Parcel.h"
#include "android/graphics/GraphicsJNI.h"
#include "android/graphics/GraphicBuffer.h"

#include <android/hardware_buffer.h>
#include <android_runtime/android_hardware_HardwareBuffer.h>
#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/Log.h>
#include <private/android/AHardwareBufferHelpers.h>

#include <binder/Parcel.h>

#include <ui/GraphicBuffer.h>
#include <private/gui/ComposerService.h>

#include <hardware/gralloc1.h>
#include <grallocusage/GrallocUsageConversion.h>

#include "core_jni_helpers.h"

using namespace android;

// ----------------------------------------------------------------------------
// Defines
// ----------------------------------------------------------------------------

// Debug
static constexpr bool kDebugGraphicBuffer = false;

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
// HardwareBuffer lifecycle
// ----------------------------------------------------------------------------

static jlong android_hardware_HardwareBuffer_create(JNIEnv* env, jobject clazz,
        jint width, jint height, jint format, jint layers, jlong usage) {

    // TODO: update createGraphicBuffer to take two 64-bit values.
    int pixelFormat = android_hardware_HardwareBuffer_convertToPixelFormat(format);
    if (pixelFormat == 0) {
        if (kDebugGraphicBuffer) {
            ALOGW("createGraphicBufferAlloc() invalid pixel format in HardwareBuffer.create()");
        }
        return NULL;
    }

    uint64_t grallocUsage = AHardwareBuffer_convertToGrallocUsageBits(usage);
    sp<GraphicBuffer> buffer = new GraphicBuffer(width, height, pixelFormat, layers,
            grallocUsage, std::string("HardwareBuffer pid [") + std::to_string(getpid()) +"]");
    status_t error = buffer->initCheck();
    if (error < 0) {
        if (kDebugGraphicBuffer) {
            ALOGW("createGraphicBuffer() failed in HardwareBuffer.create()");
        }
        return NULL;
    }

    GraphicBufferWrapper* wrapper = new GraphicBufferWrapper(buffer);
    return reinterpret_cast<jlong>(wrapper);
}

static jlong android_hardware_HardwareBuffer_createFromGraphicBuffer(JNIEnv* env, jobject clazz, jobject graphicBuffer) {
    sp<GraphicBuffer> buffer(graphicBufferForJavaObject(env, graphicBuffer));
    GraphicBufferWrapper* wrapper = new GraphicBufferWrapper(buffer);
    return reinterpret_cast<jlong>(wrapper);
}

static void destroyWrapper(GraphicBufferWrapper* wrapper) {
    delete wrapper;
}

static jlong android_hardware_HardwareBuffer_getNativeFinalizer(JNIEnv* env, jobject clazz) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(&destroyWrapper));
}

static jboolean android_hardware_HardwareBuffer_isSupported(JNIEnv* env, jobject clazz,
        jint width, jint height, jint format, jint layers, jlong usage) {

    AHardwareBuffer_Desc desc;
    desc.width = width;
    desc.height = height;
    desc.format = format;
    desc.layers = layers;
    desc.usage = usage;
    desc.stride = 0;
    desc.rfu0 = 0;
    desc.rfu1 = 0;
    return AHardwareBuffer_isSupported(&desc);
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
    return AHardwareBuffer_convertFromGrallocUsageBits(buffer->getUsage());
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
        return AHardwareBuffer_from_GraphicBuffer(buffer);

    } else {
        return nullptr;
    }
}

jobject android_hardware_HardwareBuffer_createFromAHardwareBuffer(
        JNIEnv* env, AHardwareBuffer* hardwareBuffer) {
    GraphicBuffer* buffer = AHardwareBuffer_to_GraphicBuffer(hardwareBuffer);
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
    return AHardwareBuffer_convertFromPixelFormat(format);
}

uint32_t android_hardware_HardwareBuffer_convertToPixelFormat(uint32_t format) {
    return AHardwareBuffer_convertToPixelFormat(format);
}

uint64_t android_hardware_HardwareBuffer_convertToGrallocUsageBits(uint64_t usage) {
    return AHardwareBuffer_convertToGrallocUsageBits(usage);
}

}  // namespace android

// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

const char* const kClassPathName = "android/hardware/HardwareBuffer";

static const JNINativeMethod gMethods[] = {
    { "nCreateHardwareBuffer",  "(IIIIJ)J",
            (void*) android_hardware_HardwareBuffer_create },
    { "nCreateFromGraphicBuffer", "(Landroid/graphics/GraphicBuffer;)J",
            (void*) android_hardware_HardwareBuffer_createFromGraphicBuffer },
    { "nGetNativeFinalizer", "()J",
            (void*) android_hardware_HardwareBuffer_getNativeFinalizer },
    { "nWriteHardwareBufferToParcel",  "(JLandroid/os/Parcel;)V",
            (void*) android_hardware_HardwareBuffer_write },
    { "nReadHardwareBufferFromParcel", "(Landroid/os/Parcel;)J",
            (void*) android_hardware_HardwareBuffer_read },
    { "nIsSupported",  "(IIIIJ)Z",
            (void*) android_hardware_HardwareBuffer_isSupported },

    // --------------- @FastNative ----------------------
    { "nGetWidth", "(J)I",      (void*) android_hardware_HardwareBuffer_getWidth },
    { "nGetHeight", "(J)I",     (void*) android_hardware_HardwareBuffer_getHeight },
    { "nGetFormat", "(J)I",     (void*) android_hardware_HardwareBuffer_getFormat },
    { "nGetLayers", "(J)I",     (void*) android_hardware_HardwareBuffer_getLayers },
    { "nGetUsage", "(J)J",      (void*) android_hardware_HardwareBuffer_getUsage },
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
