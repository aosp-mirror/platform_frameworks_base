/*
 * Copyright (C) 2020 The Android Open Source Project
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

#define LOG_TAG "Camera-SurfaceUtils-JNI"
// #define LOG_NDEBUG 0
#include <camera/CameraUtils.h>
#include <utils/Errors.h>
#include <utils/Log.h>
#include <utils/Trace.h>

#include <nativehelper/JNIHelp.h>
#include "android_runtime/android_graphics_SurfaceTexture.h"
#include "android_runtime/android_view_Surface.h"
#include "core_jni_helpers.h"
#include "jni.h"

#include <gui/IGraphicBufferProducer.h>
#include <gui/IProducerListener.h>
#include <gui/Surface.h>
#include <system/window.h>
#include <ui/GraphicBuffer.h>

#include <inttypes.h>
#include <stdint.h>

using namespace android;

// fully-qualified class name
#define CAMERA_UTILS_CLASS_NAME "android/hardware/camera2/utils/SurfaceUtils"

#define ARRAY_SIZE(a) (sizeof(a) / sizeof(*(a)))

#define OVERRIDE_SURFACE_ERROR(err) \
    do {                            \
        if (err == -ENODEV) {       \
            err = BAD_VALUE;        \
        }                           \
    } while (0)

static sp<ANativeWindow> getNativeWindow(JNIEnv* env, jobject surface) {
    sp<ANativeWindow> anw;
    if (surface) {
        anw = android_view_Surface_getNativeWindow(env, surface);
        if (env->ExceptionCheck()) {
            return NULL;
        }
    } else {
        jniThrowNullPointerException(env, "surface");
        return NULL;
    }
    if (anw == NULL) {
        ALOGE("%s: Surface had no valid native window.", __FUNCTION__);
        return NULL;
    }
    return anw;
}

static sp<Surface> getSurface(JNIEnv* env, jobject surface) {
    sp<Surface> s;
    if (surface) {
        s = android_view_Surface_getSurface(env, surface);
        if (env->ExceptionCheck()) {
            return NULL;
        }
    } else {
        jniThrowNullPointerException(env, "surface");
        return NULL;
    }
    if (s == NULL) {
        jniThrowExceptionFmt(env, "java/lang/IllegalArgumentException",
                             "Surface had no valid native Surface.");
        return NULL;
    }
    return s;
}

extern "C" {

static jint SurfaceUtils_nativeDetectSurfaceType(JNIEnv* env, jobject thiz, jobject surface) {
    ALOGV("nativeDetectSurfaceType");
    sp<ANativeWindow> anw;
    if ((anw = getNativeWindow(env, surface)) == NULL) {
        ALOGE("%s: Could not retrieve native window from surface.", __FUNCTION__);
        return BAD_VALUE;
    }
    int32_t fmt = 0;
    status_t err = anw->query(anw.get(), NATIVE_WINDOW_FORMAT, &fmt);
    if (err != NO_ERROR) {
        ALOGE("%s: Error while querying surface pixel format %s (%d).", __FUNCTION__,
              strerror(-err), err);
        OVERRIDE_SURFACE_ERROR(err);
        return err;
    }
    return fmt;
}

static jint SurfaceUtils_nativeDetectSurfaceDataspace(JNIEnv* env, jobject thiz, jobject surface) {
    ALOGV("nativeDetectSurfaceDataspace");
    sp<ANativeWindow> anw;
    if ((anw = getNativeWindow(env, surface)) == NULL) {
        ALOGE("%s: Could not retrieve native window from surface.", __FUNCTION__);
        return BAD_VALUE;
    }
    int32_t fmt = 0;
    status_t err = anw->query(anw.get(), NATIVE_WINDOW_DEFAULT_DATASPACE, &fmt);
    if (err != NO_ERROR) {
        ALOGE("%s: Error while querying surface dataspace  %s (%d).", __FUNCTION__, strerror(-err),
              err);
        OVERRIDE_SURFACE_ERROR(err);
        return err;
    }
    return fmt;
}

static jint SurfaceUtils_nativeDetectSurfaceDimens(JNIEnv* env, jobject thiz, jobject surface,
                                                   jintArray dimens) {
    ALOGV("nativeGetSurfaceDimens");

    if (dimens == NULL) {
        ALOGE("%s: Null dimens argument passed to nativeDetectSurfaceDimens", __FUNCTION__);
        return BAD_VALUE;
    }

    if (env->GetArrayLength(dimens) < 2) {
        ALOGE("%s: Invalid length of dimens argument in nativeDetectSurfaceDimens", __FUNCTION__);
        return BAD_VALUE;
    }

    sp<ANativeWindow> anw;
    if ((anw = getNativeWindow(env, surface)) == NULL) {
        ALOGE("%s: Could not retrieve native window from surface.", __FUNCTION__);
        return BAD_VALUE;
    }
    int32_t dimenBuf[2];
    status_t err = anw->query(anw.get(), NATIVE_WINDOW_WIDTH, dimenBuf);
    if (err != NO_ERROR) {
        ALOGE("%s: Error while querying surface width %s (%d).", __FUNCTION__, strerror(-err), err);
        OVERRIDE_SURFACE_ERROR(err);
        return err;
    }
    err = anw->query(anw.get(), NATIVE_WINDOW_HEIGHT, dimenBuf + 1);
    if (err != NO_ERROR) {
        ALOGE("%s: Error while querying surface height %s (%d).", __FUNCTION__, strerror(-err),
              err);
        OVERRIDE_SURFACE_ERROR(err);
        return err;
    }
    env->SetIntArrayRegion(dimens, /*start*/ 0, /*length*/ ARRAY_SIZE(dimenBuf), dimenBuf);
    return NO_ERROR;
}

static jlong SurfaceUtils_nativeDetectSurfaceUsageFlags(JNIEnv* env, jobject thiz,
                                                        jobject surface) {
    ALOGV("nativeDetectSurfaceUsageFlags");

    sp<ANativeWindow> anw;
    if ((anw = getNativeWindow(env, surface)) == NULL) {
        jniThrowException(env, "java/lang/UnsupportedOperationException",
                          "Could not retrieve native window from surface.");
        return BAD_VALUE;
    }
    uint64_t usage = 0;
    status_t err = native_window_get_consumer_usage(anw.get(), &usage);
    if (err != NO_ERROR) {
        jniThrowException(env, "java/lang/UnsupportedOperationException",
                          "Error while querying surface usage bits");
        OVERRIDE_SURFACE_ERROR(err);
        return err;
    }
    return usage;
}

static jlong SurfaceUtils_nativeGetSurfaceId(JNIEnv* env, jobject thiz, jobject surface) {
    ALOGV("nativeGetSurfaceId");
    sp<Surface> s;
    if ((s = getSurface(env, surface)) == NULL) {
        ALOGE("%s: Could not retrieve native Surface from surface.", __FUNCTION__);
        return 0;
    }
    sp<IGraphicBufferProducer> gbp = s->getIGraphicBufferProducer();
    if (gbp == NULL) {
        ALOGE("%s: Could not retrieve IGraphicBufferProducer from surface.", __FUNCTION__);
        return 0;
    }
    sp<IBinder> b = IInterface::asBinder(gbp);
    if (b == NULL) {
        ALOGE("%s: Could not retrieve IBinder from surface.", __FUNCTION__);
        return 0;
    }
    /*
     * FIXME: Use better unique ID for surfaces than native IBinder pointer.  Fix also in the camera
     * service (CameraDeviceClient.h).
     */
    return reinterpret_cast<jlong>(b.get());
}

} // extern "C"

static const JNINativeMethod gCameraSurfaceUtilsMethods[] = {
        {"nativeDetectSurfaceType", "(Landroid/view/Surface;)I",
         (void*)SurfaceUtils_nativeDetectSurfaceType},
        {"nativeDetectSurfaceDataspace", "(Landroid/view/Surface;)I",
         (void*)SurfaceUtils_nativeDetectSurfaceDataspace},
        {"nativeDetectSurfaceDimens", "(Landroid/view/Surface;[I)I",
         (void*)SurfaceUtils_nativeDetectSurfaceDimens},
        {"nativeDetectSurfaceUsageFlags", "(Landroid/view/Surface;)J",
         (void*)SurfaceUtils_nativeDetectSurfaceUsageFlags},
        {"nativeGetSurfaceId", "(Landroid/view/Surface;)J", (void*)SurfaceUtils_nativeGetSurfaceId},
};

// Get all the required offsets in java class and register native functions
int register_android_hardware_camera2_utils_SurfaceUtils(JNIEnv* env) {
    // Register native functions
    return RegisterMethodsOrDie(env, CAMERA_UTILS_CLASS_NAME, gCameraSurfaceUtilsMethods,
                                NELEM(gCameraSurfaceUtilsMethods));
}
