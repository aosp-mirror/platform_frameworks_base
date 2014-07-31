/*
 * Copyright (C) 2014 The Android Open Source Project
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

#define LOG_TAG "Legacy-CameraDevice-JNI"
// #define LOG_NDEBUG 0
#include <utils/Log.h>
#include <utils/Errors.h>
#include <utils/Trace.h>
#include <camera/CameraUtils.h>

#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"
#include "android_runtime/android_view_Surface.h"
#include "android_runtime/android_graphics_SurfaceTexture.h"

#include <gui/Surface.h>
#include <gui/IGraphicBufferProducer.h>
#include <ui/GraphicBuffer.h>
#include <system/window.h>
#include <hardware/camera3.h>
#include <system/camera_metadata.h>

#include <stdint.h>
#include <inttypes.h>

using namespace android;

// fully-qualified class name
#define CAMERA_DEVICE_CLASS_NAME "android/hardware/camera2/legacy/LegacyCameraDevice"
#define CAMERA_DEVICE_BUFFER_SLACK  3
#define DONT_CARE 0

#define ARRAY_SIZE(a) (sizeof(a)/sizeof(*(a)))

#define ALIGN(x, mask) ( ((x) + (mask) - 1) & ~((mask) - 1) )

/**
 * Convert from RGB 888 to Y'CbCr using the conversion specified in ITU-R BT.601 for
 * digital RGB with K_b = 0.114, and K_r = 0.299.
 */
static void rgbToYuv420(uint8_t* rgbBuf, int32_t width, int32_t height, uint8_t* yPlane,
        uint8_t* uPlane, uint8_t* vPlane, size_t chromaStep, size_t yStride, size_t chromaStride) {
    uint8_t R, G, B;
    size_t index = 0;

    int32_t cStrideDiff = chromaStride - width;

    for (int32_t j = 0; j < height; j++) {
        for (int32_t i = 0; i < width; i++) {
            R = rgbBuf[index++];
            G = rgbBuf[index++];
            B = rgbBuf[index++];
            *(yPlane + i) = ((66 * R + 129 * G +  25 * B + 128) >> 8) +  16;

            if (j % 2 == 0 && i % 2 == 0){
                *uPlane = (( -38 * R -  74 * G + 112 * B + 128) >> 8) + 128;
                *vPlane = (( 112 * R -  94 * G -  18 * B + 128) >> 8) + 128;
                uPlane += chromaStep;
                vPlane += chromaStep;
            }
            // Skip alpha
            index++;
        }
        yPlane += yStride;
        if (j % 2 == 0) {
            uPlane += cStrideDiff;
            vPlane += cStrideDiff;
        }
    }
}

static void rgbToYuv420(uint8_t* rgbBuf, int32_t width, int32_t height, android_ycbcr* ycbcr) {
    size_t cStep = ycbcr->chroma_step;
    size_t cStride = ycbcr->cstride;
    size_t yStride = ycbcr->ystride;
    rgbToYuv420(rgbBuf, width, height, reinterpret_cast<uint8_t*>(ycbcr->y),
            reinterpret_cast<uint8_t*>(ycbcr->cb), reinterpret_cast<uint8_t*>(ycbcr->cr),
            cStep, yStride, cStride);
}

static status_t configureSurface(const sp<ANativeWindow>& anw,
                                 int32_t width,
                                 int32_t height,
                                 int32_t pixelFmt,
                                 int32_t maxBufferSlack) {
    status_t err = NO_ERROR;
    err = native_window_set_buffers_dimensions(anw.get(), width, height);
    if (err != NO_ERROR) {
        ALOGE("%s: Failed to set native window buffer dimensions, error %s (%d).", __FUNCTION__,
                strerror(-err), err);
        return err;
    }

    err = native_window_set_buffers_format(anw.get(), pixelFmt);
    if (err != NO_ERROR) {
        ALOGE("%s: Failed to set native window buffer format, error %s (%d).", __FUNCTION__,
                strerror(-err), err);
        return err;
    }

    err = native_window_set_usage(anw.get(), GRALLOC_USAGE_SW_WRITE_OFTEN);
    if (err != NO_ERROR) {
        ALOGE("%s: Failed to set native window usage flag, error %s (%d).", __FUNCTION__,
                strerror(-err), err);
        return err;
    }

    int minUndequeuedBuffers;
    err = anw.get()->query(anw.get(),
            NATIVE_WINDOW_MIN_UNDEQUEUED_BUFFERS,
            &minUndequeuedBuffers);
    if (err != NO_ERROR) {
        ALOGE("%s: Failed to get native window min undequeued buffers, error %s (%d).",
                __FUNCTION__, strerror(-err), err);
        return err;
    }

    ALOGV("%s: Setting buffer count to %d, size to (%dx%d), fmt (0x%x)", __FUNCTION__,
          maxBufferSlack + 1 + minUndequeuedBuffers,
          width, height, pixelFmt);
    err = native_window_set_buffer_count(anw.get(), maxBufferSlack + 1 + minUndequeuedBuffers);
    if (err != NO_ERROR) {
        ALOGE("%s: Failed to set native window buffer count, error %s (%d).", __FUNCTION__,
                strerror(-err), err);
        return err;
    }
    return NO_ERROR;
}

/**
 * Produce a frame in the given surface.
 *
 * Args:
 *    anw - a surface to produce a frame in.
 *    pixelBuffer - image buffer to generate a frame from.
 *    width - width of the pixelBuffer in pixels.
 *    height - height of the pixelBuffer in pixels.
 *    pixelFmt - format of the pixelBuffer, one of:
 *               HAL_PIXEL_FORMAT_YCrCb_420_SP,
 *               HAL_PIXEL_FORMAT_YCbCr_420_888,
 *               HAL_PIXEL_FORMAT_BLOB
 *    bufSize - the size of the pixelBuffer in bytes.
 */
static status_t produceFrame(const sp<ANativeWindow>& anw,
                             uint8_t* pixelBuffer,
                             int32_t width, // Width of the pixelBuffer
                             int32_t height, // Height of the pixelBuffer
                             int32_t pixelFmt, // Format of the pixelBuffer
                             int32_t bufSize) {
    ATRACE_CALL();
    status_t err = NO_ERROR;
    ANativeWindowBuffer* anb;
    ALOGV("%s: Dequeue buffer from %p %dx%d (fmt=%x, size=%x)",
            __FUNCTION__, anw.get(), width, height, pixelFmt, bufSize);

    if (anw == 0) {
        ALOGE("%s: anw must not be NULL", __FUNCTION__);
        return BAD_VALUE;
    } else if (pixelBuffer == NULL) {
        ALOGE("%s: pixelBuffer must not be NULL", __FUNCTION__);
        return BAD_VALUE;
    } else if (width < 0) {
        ALOGE("%s: width must be non-negative", __FUNCTION__);
        return BAD_VALUE;
    } else if (height < 0) {
        ALOGE("%s: height must be non-negative", __FUNCTION__);
        return BAD_VALUE;
    } else if (bufSize < 0) {
        ALOGE("%s: bufSize must be non-negative", __FUNCTION__);
        return BAD_VALUE;
    }

    if (width < 0 || height < 0 || bufSize < 0) {
        ALOGE("%s: Illegal argument, negative dimension passed to produceFrame", __FUNCTION__);
        return BAD_VALUE;
    }

    // TODO: Switch to using Surface::lock and Surface::unlockAndPost
    err = native_window_dequeue_buffer_and_wait(anw.get(), &anb);
    if (err != NO_ERROR) return err;

    // TODO: check anb is large enough to store the results

    sp<GraphicBuffer> buf(new GraphicBuffer(anb, /*keepOwnership*/false));

    switch(pixelFmt) {
        case HAL_PIXEL_FORMAT_YCrCb_420_SP: {
            if (bufSize < width * height * 4) {
                ALOGE("%s: PixelBuffer size %" PRId32 " to small for given dimensions",
                        __FUNCTION__, bufSize);
                return BAD_VALUE;
            }
            uint8_t* img = NULL;
            ALOGV("%s: Lock buffer from %p for write", __FUNCTION__, anw.get());
            err = buf->lock(GRALLOC_USAGE_SW_WRITE_OFTEN, (void**)(&img));
            if (err != NO_ERROR) return err;

            uint8_t* yPlane = img;
            uint8_t* uPlane = img + height * width;
            uint8_t* vPlane = uPlane + 1;
            size_t chromaStep = 2;
            size_t yStride = width;
            size_t chromaStride = width;

            rgbToYuv420(pixelBuffer, width, height, yPlane,
                    uPlane, vPlane, chromaStep, yStride, chromaStride);
            break;
        }
        case HAL_PIXEL_FORMAT_YV12: {
            if (bufSize < width * height * 4) {
                ALOGE("%s: PixelBuffer size %" PRId32 " to small for given dimensions",
                        __FUNCTION__, bufSize);
                return BAD_VALUE;
            }

            if ((width & 1) || (height & 1)) {
                ALOGE("%s: Dimens %dx%d are not divisible by 2.", __FUNCTION__, width, height);
                return BAD_VALUE;
            }

            uint8_t* img = NULL;
            ALOGV("%s: Lock buffer from %p for write", __FUNCTION__, anw.get());
            err = buf->lock(GRALLOC_USAGE_SW_WRITE_OFTEN, (void**)(&img));
            if (err != NO_ERROR) {
                ALOGE("%s: Error %s (%d) while locking gralloc buffer for write.", __FUNCTION__,
                        strerror(-err), err);
                return err;
            }

            uint32_t stride = buf->getStride();
            LOG_ALWAYS_FATAL_IF(stride % 16, "Stride is not 16 pixel aligned %d", stride);

            uint32_t cStride = ALIGN(stride / 2, 16);
            size_t chromaStep = 1;

            uint8_t* yPlane = img;
            uint8_t* crPlane = img + static_cast<uint32_t>(height) * stride;
            uint8_t* cbPlane = crPlane + cStride * static_cast<uint32_t>(height) / 2;

            rgbToYuv420(pixelBuffer, width, height, yPlane,
                    crPlane, cbPlane, chromaStep, stride, cStride);
            break;
        }
        case HAL_PIXEL_FORMAT_YCbCr_420_888: {
            // Software writes with YCbCr_420_888 format are unsupported
            // by the gralloc module for now
            if (bufSize < width * height * 4) {
                ALOGE("%s: PixelBuffer size %" PRId32 " to small for given dimensions",
                        __FUNCTION__, bufSize);
                return BAD_VALUE;
            }
            android_ycbcr ycbcr = android_ycbcr();
            ALOGV("%s: Lock buffer from %p for write", __FUNCTION__, anw.get());

            err = buf->lockYCbCr(GRALLOC_USAGE_SW_WRITE_OFTEN, &ycbcr);
            if (err != NO_ERROR) {
                ALOGE("%s: Failed to lock ycbcr buffer, error %s (%d).", __FUNCTION__,
                        strerror(-err), err);
                return err;
            }
            rgbToYuv420(pixelBuffer, width, height, &ycbcr);
            break;
        }
        case HAL_PIXEL_FORMAT_BLOB: {
            if (bufSize != width || height != 1) {
                ALOGE("%s: Incorrect pixelBuffer size: %" PRId32, __FUNCTION__, bufSize);
                return BAD_VALUE;
            }
            int8_t* img = NULL;

            ALOGV("%s: Lock buffer from %p for write", __FUNCTION__, anw.get());
            err = buf->lock(GRALLOC_USAGE_SW_WRITE_OFTEN, (void**)(&img));
            if (err != NO_ERROR) {
                ALOGE("%s: Failed to lock buffer, error %s (%d).", __FUNCTION__, strerror(-err),
                        err);
                return err;
            }
            struct camera3_jpeg_blob footer = {
                jpeg_blob_id: CAMERA3_JPEG_BLOB_ID,
                jpeg_size: (uint32_t)width
            };
            memcpy(img, pixelBuffer, width);
            memcpy(img + anb->width - sizeof(footer), &footer, sizeof(footer));
            break;
        }
        default: {
            ALOGE("%s: Invalid pixel format in produceFrame: %x", __FUNCTION__, pixelFmt);
            return BAD_VALUE;
        }
    }

    ALOGV("%s: Unlock buffer from %p", __FUNCTION__, anw.get());
    err = buf->unlock();
    if (err != NO_ERROR) {
        ALOGE("%s: Failed to unlock buffer, error %s (%d).", __FUNCTION__, strerror(-err), err);
        return err;
    }

    ALOGV("%s: Queue buffer to %p", __FUNCTION__, anw.get());
    err = anw->queueBuffer(anw.get(), buf->getNativeBuffer(), /*fenceFd*/-1);
    if (err != NO_ERROR) {
        ALOGE("%s: Failed to queue buffer, error %s (%d).", __FUNCTION__, strerror(-err), err);
        return err;
    }
    return NO_ERROR;
}

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
        jniThrowExceptionFmt(env, "java/lang/IllegalArgumentException",
                "Surface had no valid native window.");
        return NULL;
    }
    return anw;
}

static sp<ANativeWindow> getNativeWindowFromTexture(JNIEnv* env, jobject surfaceTexture) {
    sp<ANativeWindow> anw;
    if (surfaceTexture) {
        anw = android_SurfaceTexture_getNativeWindow(env, surfaceTexture);
        if (env->ExceptionCheck()) {
            return NULL;
        }
    } else {
        jniThrowNullPointerException(env, "surfaceTexture");
        return NULL;
    }
    if (anw == NULL) {
        jniThrowExceptionFmt(env, "java/lang/IllegalArgumentException",
                "SurfaceTexture had no valid native window.");
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

static jint LegacyCameraDevice_nativeDetectSurfaceType(JNIEnv* env, jobject thiz, jobject surface) {
    ALOGV("nativeDetectSurfaceType");
    sp<ANativeWindow> anw;
    if ((anw = getNativeWindow(env, surface)) == NULL) {
        ALOGE("%s: Could not retrieve native window from surface.", __FUNCTION__);
        return BAD_VALUE;
    }
    int32_t fmt = 0;
    status_t err = anw->query(anw.get(), NATIVE_WINDOW_FORMAT, &fmt);
    if(err != NO_ERROR) {
        ALOGE("%s: Error while querying surface pixel format %s (%d).", __FUNCTION__, strerror(-err),
                err);
        return err;
    }
    return fmt;
}

static jint LegacyCameraDevice_nativeDetectSurfaceDimens(JNIEnv* env, jobject thiz,
          jobject surface, jintArray dimens) {
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
    if(err != NO_ERROR) {
        ALOGE("%s: Error while querying surface width %s (%d).", __FUNCTION__, strerror(-err),
                err);
        return err;
    }
    err = anw->query(anw.get(), NATIVE_WINDOW_HEIGHT, dimenBuf + 1);
    if(err != NO_ERROR) {
        ALOGE("%s: Error while querying surface height %s (%d).", __FUNCTION__, strerror(-err),
                err);
        return err;
    }
    env->SetIntArrayRegion(dimens, /*start*/0, /*length*/ARRAY_SIZE(dimenBuf), dimenBuf);
    return NO_ERROR;
}

static jint LegacyCameraDevice_nativeDetectTextureDimens(JNIEnv* env, jobject thiz,
        jobject surfaceTexture, jintArray dimens) {
    ALOGV("nativeDetectTextureDimens");
    sp<ANativeWindow> anw;
    if ((anw = getNativeWindowFromTexture(env, surfaceTexture)) == NULL) {
        ALOGE("%s: Could not retrieve native window from SurfaceTexture.", __FUNCTION__);
        return BAD_VALUE;
    }

    int32_t dimenBuf[2];
    status_t err = anw->query(anw.get(), NATIVE_WINDOW_WIDTH, dimenBuf);
    if(err != NO_ERROR) {
        ALOGE("%s: Error while querying SurfaceTexture width %s (%d)", __FUNCTION__,
                strerror(-err), err);
        return err;
    }

    err = anw->query(anw.get(), NATIVE_WINDOW_HEIGHT, dimenBuf + 1);
    if(err != NO_ERROR) {
        ALOGE("%s: Error while querying SurfaceTexture height %s (%d)", __FUNCTION__,
                strerror(-err), err);
        return err;
    }

    env->SetIntArrayRegion(dimens, /*start*/0, /*length*/ARRAY_SIZE(dimenBuf), dimenBuf);
    if (env->ExceptionCheck()) {
        return BAD_VALUE;
    }
    return NO_ERROR;
}

static jint LegacyCameraDevice_nativeConfigureSurface(JNIEnv* env, jobject thiz, jobject surface,
        jint width, jint height, jint pixelFormat) {
    ALOGV("nativeConfigureSurface");
    sp<ANativeWindow> anw;
    if ((anw = getNativeWindow(env, surface)) == NULL) {
        ALOGE("%s: Could not retrieve native window from surface.", __FUNCTION__);
        return BAD_VALUE;
    }
    status_t err = configureSurface(anw, width, height, pixelFormat, CAMERA_DEVICE_BUFFER_SLACK);
    if (err != NO_ERROR) {
        ALOGE("%s: Error while configuring surface %s (%d).", __FUNCTION__, strerror(-err), err);
        return err;
    }
    return NO_ERROR;
}

static jint LegacyCameraDevice_nativeProduceFrame(JNIEnv* env, jobject thiz, jobject surface,
        jbyteArray pixelBuffer, jint width, jint height, jint pixelFormat) {
    ALOGV("nativeProduceFrame");
    sp<ANativeWindow> anw;

    if ((anw = getNativeWindow(env, surface)) == NULL) {
        ALOGE("%s: Could not retrieve native window from surface.", __FUNCTION__);
        return BAD_VALUE;
    }

    if (pixelBuffer == NULL) {
        jniThrowNullPointerException(env, "pixelBuffer");
        return DONT_CARE;
    }

    int32_t bufSize = static_cast<int32_t>(env->GetArrayLength(pixelBuffer));
    jbyte* pixels = env->GetByteArrayElements(pixelBuffer, /*is_copy*/NULL);

    if (pixels == NULL) {
        jniThrowNullPointerException(env, "pixels");
        return DONT_CARE;
    }

    status_t err = produceFrame(anw, reinterpret_cast<uint8_t*>(pixels), width, height,
            pixelFormat, bufSize);
    env->ReleaseByteArrayElements(pixelBuffer, pixels, JNI_ABORT);

    if (err != NO_ERROR) {
        ALOGE("%s: Error while producing frame %s (%d).", __FUNCTION__, strerror(-err), err);
        return err;
    }
    return NO_ERROR;
}

static jint LegacyCameraDevice_nativeSetSurfaceFormat(JNIEnv* env, jobject thiz, jobject surface,
        jint pixelFormat) {
    ALOGV("nativeSetSurfaceType");
    sp<ANativeWindow> anw;
    if ((anw = getNativeWindow(env, surface)) == NULL) {
        ALOGE("%s: Could not retrieve native window from surface.", __FUNCTION__);
        return BAD_VALUE;
    }
    status_t err = native_window_set_buffers_format(anw.get(), pixelFormat);
    if (err != NO_ERROR) {
        ALOGE("%s: Error while setting surface format %s (%d).", __FUNCTION__, strerror(-err), err);
        return err;
    }
    return NO_ERROR;
}

static jint LegacyCameraDevice_nativeSetSurfaceDimens(JNIEnv* env, jobject thiz, jobject surface,
        jint width, jint height) {
    ALOGV("nativeSetSurfaceDimens");
    sp<ANativeWindow> anw;
    if ((anw = getNativeWindow(env, surface)) == NULL) {
        ALOGE("%s: Could not retrieve native window from surface.", __FUNCTION__);
        return BAD_VALUE;
    }
    status_t err = native_window_set_buffers_dimensions(anw.get(), width, height);
    if (err != NO_ERROR) {
        ALOGE("%s: Error while setting surface dimens %s (%d).", __FUNCTION__, strerror(-err), err);
        return err;
    }
    return NO_ERROR;
}

static jlong LegacyCameraDevice_nativeGetSurfaceId(JNIEnv* env, jobject thiz, jobject surface) {
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
    sp<IBinder> b = gbp->asBinder();
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

static jint LegacyCameraDevice_nativeSetSurfaceOrientation(JNIEnv* env, jobject thiz,
        jobject surface, jint facing, jint orientation) {
    ALOGV("nativeSetSurfaceOrientation");
    sp<ANativeWindow> anw;
    if ((anw = getNativeWindow(env, surface)) == NULL) {
        ALOGE("%s: Could not retrieve native window from surface.", __FUNCTION__);
        return BAD_VALUE;
    }

    status_t err = NO_ERROR;
    CameraMetadata staticMetadata;

    int32_t orientVal = static_cast<int32_t>(orientation);
    uint8_t facingVal = static_cast<uint8_t>(facing);
    staticMetadata.update(ANDROID_SENSOR_ORIENTATION, &orientVal, 1);
    staticMetadata.update(ANDROID_LENS_FACING, &facingVal, 1);

    int32_t transform = 0;

    if ((err = CameraUtils::getRotationTransform(staticMetadata, /*out*/&transform)) != NO_ERROR) {
        ALOGE("%s: Invalid rotation transform %s (%d)", __FUNCTION__, strerror(-err),
                err);
        return err;
    }

    ALOGV("%s: Setting buffer sticky transform to %d", __FUNCTION__, transform);

    if ((err = native_window_set_buffers_sticky_transform(anw.get(), transform)) != NO_ERROR) {
        ALOGE("%s: Unable to configure surface transform, error %s (%d)", __FUNCTION__,
                strerror(-err), err);
        return err;
    }

    return NO_ERROR;
}

static jint LegacyCameraDevice_nativeSetNextTimestamp(JNIEnv* env, jobject thiz, jobject surface,
        jlong timestamp) {
    ALOGV("nativeSetNextTimestamp");
    sp<ANativeWindow> anw;
    if ((anw = getNativeWindow(env, surface)) == NULL) {
        ALOGE("%s: Could not retrieve native window from surface.", __FUNCTION__);
        return BAD_VALUE;
    }

    status_t err = NO_ERROR;

    if ((err = native_window_set_buffers_timestamp(anw.get(), static_cast<int64_t>(timestamp))) !=
            NO_ERROR) {
        ALOGE("%s: Unable to set surface timestamp, error %s (%d)", __FUNCTION__, strerror(-err),
                err);
        return err;
    }
    return NO_ERROR;
}

static jint LegacyCameraDevice_nativeGetJpegFooterSize(JNIEnv* env, jobject thiz) {
    ALOGV("nativeGetJpegFooterSize");
    return static_cast<jint>(sizeof(struct camera3_jpeg_blob));
}

} // extern "C"

static JNINativeMethod gCameraDeviceMethods[] = {
    { "nativeDetectSurfaceType",
    "(Landroid/view/Surface;)I",
    (void *)LegacyCameraDevice_nativeDetectSurfaceType },
    { "nativeDetectSurfaceDimens",
    "(Landroid/view/Surface;[I)I",
    (void *)LegacyCameraDevice_nativeDetectSurfaceDimens },
    { "nativeConfigureSurface",
    "(Landroid/view/Surface;III)I",
    (void *)LegacyCameraDevice_nativeConfigureSurface },
    { "nativeProduceFrame",
    "(Landroid/view/Surface;[BIII)I",
    (void *)LegacyCameraDevice_nativeProduceFrame },
    { "nativeSetSurfaceFormat",
    "(Landroid/view/Surface;I)I",
    (void *)LegacyCameraDevice_nativeSetSurfaceFormat },
    { "nativeSetSurfaceDimens",
    "(Landroid/view/Surface;II)I",
    (void *)LegacyCameraDevice_nativeSetSurfaceDimens },
    { "nativeGetSurfaceId",
    "(Landroid/view/Surface;)J",
    (void *)LegacyCameraDevice_nativeGetSurfaceId },
    { "nativeDetectTextureDimens",
    "(Landroid/graphics/SurfaceTexture;[I)I",
    (void *)LegacyCameraDevice_nativeDetectTextureDimens },
    { "nativeSetSurfaceOrientation",
    "(Landroid/view/Surface;II)I",
    (void *)LegacyCameraDevice_nativeSetSurfaceOrientation },
    { "nativeSetNextTimestamp",
    "(Landroid/view/Surface;J)I",
    (void *)LegacyCameraDevice_nativeSetNextTimestamp },
    { "nativeGetJpegFooterSize",
    "()I",
    (void *)LegacyCameraDevice_nativeGetJpegFooterSize },
};

// Get all the required offsets in java class and register native functions
int register_android_hardware_camera2_legacy_LegacyCameraDevice(JNIEnv* env)
{
    // Register native functions
    return AndroidRuntime::registerNativeMethods(env,
            CAMERA_DEVICE_CLASS_NAME,
            gCameraDeviceMethods,
            NELEM(gCameraDeviceMethods));
}

