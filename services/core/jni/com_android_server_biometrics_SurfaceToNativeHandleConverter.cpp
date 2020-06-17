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

#include <nativehelper/JNIHelp.h>
#include "jni.h"

#include <android/native_window_jni.h>
#include <android_os_NativeHandle.h>
#include <gui/IGraphicBufferProducer.h>
#include <gui/Surface.h>
#include <gui/bufferqueue/1.0/WGraphicBufferProducer.h>

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
} // namespace

using ::android::sp;

static jobject convertSurfaceToNativeHandle(JNIEnv* env, jobject /* clazz */,
                                            jobject previewSurface) {
    if (previewSurface == nullptr) {
        return nullptr;
    }
    ANativeWindow* previewAnw = ANativeWindow_fromSurface(env, previewSurface);
    sp<Surface> surface = static_cast<Surface*>(previewAnw);
    sp<IGraphicBufferProducer> igbp = surface->getIGraphicBufferProducer();
    sp<HGraphicBufferProducer> hgbp = new TWGraphicBufferProducer<HGraphicBufferProducer>(igbp);
    HalToken halToken;
    createHalToken(hgbp, &halToken);
    native_handle_t* native_handle = convertHalTokenToNativeHandle(halToken);
    return JNativeHandle::MakeJavaNativeHandleObj(env, native_handle);
}

static const JNINativeMethod method_table[] = {
        {"convertSurfaceToNativeHandle", "(Landroid/view/Surface;)Landroid/os/NativeHandle;",
         reinterpret_cast<void*>(convertSurfaceToNativeHandle)},
};

int register_android_server_FingerprintService(JNIEnv* env) {
    return jniRegisterNativeMethods(env,
                                    "com/android/server/biometrics/sensors/fingerprint/FingerprintService",
                                    method_table, NELEM(method_table));
}

int register_android_server_FaceService(JNIEnv* env) {
    return jniRegisterNativeMethods(env, "com/android/server/biometrics/sensors/face/FaceService",
                                    method_table, NELEM(method_table));
}

}; // namespace android
