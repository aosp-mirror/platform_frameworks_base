/*
 * Copyright 2020 The Android Open Source Project
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

#define LOG_TAG "SurfaceToNativeHandleConverter"

#include <android_os_NativeHandle.h>
#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/android_view_Surface.h>
#include <gui/IGraphicBufferProducer.h>
#include <gui/Surface.h>
#include <gui/bufferqueue/1.0/WGraphicBufferProducer.h>
#include <utils/Log.h>

#include "jni.h"

namespace android {
namespace {

constexpr int WINDOW_HAL_TOKEN_SIZE_MAX = 256;

native_handle_t* convertHalTokenToNativeHandle(const HalToken& halToken) {
    // We attempt to store halToken in the ints of the native_handle_t after its
    // size. The first int stores the size of the token. We store this in an int
    // to avoid alignment issues where size_t and int do not have the same
    // alignment.
    size_t nhDataByteSize = halToken.size();
    if (nhDataByteSize > WINDOW_HAL_TOKEN_SIZE_MAX) {
        // The size of the token isn't reasonable..
        return nullptr;
    }
    size_t numInts = ceil(nhDataByteSize / sizeof(int)) + 1;

    // We don't check for overflow, whether numInts can fit in an int, since we
    // expect WINDOW_HAL_TOKEN_SIZE_MAX to be a reasonable limit.
    // create a native_handle_t with 0 numFds and numInts number of ints.
    native_handle_t* nh = native_handle_create(0, numInts);
    if (!nh) {
        return nullptr;
    }
    // Store the size of the token in the first int.
    nh->data[0] = nhDataByteSize;
    memcpy(&(nh->data[1]), halToken.data(), nhDataByteSize);
    return nh;
}

HalToken convertNativeHandleToHalToken(native_handle_t* handle) {
    int size = handle->data[0];
    auto data = reinterpret_cast<uint8_t*>(&handle->data[1]);
    HalToken halToken;
    halToken.setToExternal(data, size);
    return halToken;
}

jobject acquireSurfaceHandle(JNIEnv* env, jobject /* clazz */, jobject jSurface) {
    ALOGD("%s", __func__);
    if (jSurface == nullptr) {
        ALOGE("%s: jSurface is null", __func__);
        return nullptr;
    }

    sp<Surface> surface = android_view_Surface_getSurface(env, jSurface);
    if (surface == nullptr) {
        ALOGE("%s: surface is null", __func__);
        return nullptr;
    }

    sp<IGraphicBufferProducer> igbp = surface->getIGraphicBufferProducer();
    sp<HGraphicBufferProducer> hgbp = new TWGraphicBufferProducer<HGraphicBufferProducer>(igbp);
    // The HAL token will be closed in releaseSurfaceHandle.
    HalToken halToken;
    createHalToken(hgbp, &halToken);

    native_handle_t* native_handle = convertHalTokenToNativeHandle(halToken);
    if (native_handle == nullptr) {
        ALOGE("%s: native_handle is null", __func__);
        return nullptr;
    }
    jobject jHandle = JNativeHandle::MakeJavaNativeHandleObj(env, native_handle);
    native_handle_delete(native_handle);

    return jHandle;
}

void releaseSurfaceHandle(JNIEnv* env, jobject /* clazz */, jobject jHandle) {
    ALOGD("%s", __func__);
    // Creates a native handle from a Java handle. We must call native_handle_delete when we're done
    // with it because we created it, but we shouldn't call native_handle_close because we don't own
    // the underlying FDs.
    native_handle_t* handle =
            JNativeHandle::MakeCppNativeHandle(env, jHandle, nullptr /* storage */);
    if (handle == nullptr) {
        ALOGE("%s: handle is null", __func__);
        return;
    }

    HalToken token = convertNativeHandleToHalToken(handle);
    ALOGD("%s: deleteHalToken, success: %d", __func__, deleteHalToken(token));
    ALOGD("%s: native_handle_delete, success: %d", __func__, !native_handle_delete(handle));
}

const JNINativeMethod method_table[] = {
        {"acquireSurfaceHandle", "(Landroid/view/Surface;)Landroid/os/NativeHandle;",
         reinterpret_cast<void*>(acquireSurfaceHandle)},
        {"releaseSurfaceHandle", "(Landroid/os/NativeHandle;)V",
         reinterpret_cast<void*>(releaseSurfaceHandle)},
};
} // namespace

int register_android_server_FaceService(JNIEnv* env) {
    return AndroidRuntime::
            registerNativeMethods(env, "com/android/server/biometrics/sensors/face/FaceService",
                                  method_table, NELEM(method_table));
}
} // namespace android
