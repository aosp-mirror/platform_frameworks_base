/*
 * Copyright 2015 The Android Open Source Project
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

//#define LOG_NDEBUG 0
#define LOG_TAG "ImageWriter_JNI"
#include <utils/Log.h>
#include <utils/String8.h>

#include <gui/IProducerListener.h>
#include <gui/Surface.h>
#include <gui/CpuConsumer.h>
#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/android_view_Surface.h>
#include <camera3.h>

#include <jni.h>
#include <JNIHelp.h>

#include <stdint.h>
#include <inttypes.h>

#define ALIGN(x, mask) ( ((x) + (mask) - 1) & ~((mask) - 1) )

#define IMAGE_BUFFER_JNI_ID           "mNativeBuffer"

// ----------------------------------------------------------------------------

using namespace android;

enum {
    IMAGE_WRITER_MAX_NUM_PLANES = 3,
};

static struct {
    jmethodID postEventFromNative;
    jfieldID mWriterFormat;
} gImageWriterClassInfo;

static struct {
    jfieldID mNativeBuffer;
    jfieldID mNativeFenceFd;
    jfieldID mPlanes;
} gSurfaceImageClassInfo;

static struct {
    jclass clazz;
    jmethodID ctor;
} gSurfacePlaneClassInfo;

typedef CpuConsumer::LockedBuffer LockedImage;

// ----------------------------------------------------------------------------

class JNIImageWriterContext : public BnProducerListener {
public:
    JNIImageWriterContext(JNIEnv* env, jobject weakThiz, jclass clazz);

    virtual ~JNIImageWriterContext();

    // Implementation of IProducerListener, used to notify the ImageWriter that the consumer
    // has returned a buffer and it is ready for ImageWriter to dequeue.
    virtual void onBufferReleased();

    void setProducer(const sp<Surface>& producer) { mProducer = producer; }
    Surface* getProducer() { return mProducer.get(); }

    void setBufferFormat(int format) { mFormat = format; }
    int getBufferFormat() { return mFormat; }

    void setBufferWidth(int width) { mWidth = width; }
    int getBufferWidth() { return mWidth; }

    void setBufferHeight(int height) { mHeight = height; }
    int getBufferHeight() { return mHeight; }

private:
    static JNIEnv* getJNIEnv(bool* needsDetach);
    static void detachJNI();

    sp<Surface> mProducer;
    jobject mWeakThiz;
    jclass mClazz;
    int mFormat;
    int mWidth;
    int mHeight;
};

JNIImageWriterContext::JNIImageWriterContext(JNIEnv* env, jobject weakThiz, jclass clazz) :
    mWeakThiz(env->NewGlobalRef(weakThiz)),
    mClazz((jclass)env->NewGlobalRef(clazz)),
    mFormat(0),
    mWidth(-1),
    mHeight(-1) {
}

JNIImageWriterContext::~JNIImageWriterContext() {
    ALOGV("%s", __FUNCTION__);
    bool needsDetach = false;
    JNIEnv* env = getJNIEnv(&needsDetach);
    if (env != NULL) {
        env->DeleteGlobalRef(mWeakThiz);
        env->DeleteGlobalRef(mClazz);
    } else {
        ALOGW("leaking JNI object references");
    }
    if (needsDetach) {
        detachJNI();
    }

    mProducer.clear();
}

JNIEnv* JNIImageWriterContext::getJNIEnv(bool* needsDetach) {
    ALOGV("%s", __FUNCTION__);
    LOG_ALWAYS_FATAL_IF(needsDetach == NULL, "needsDetach is null!!!");
    *needsDetach = false;
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    if (env == NULL) {
        JavaVMAttachArgs args = {JNI_VERSION_1_4, NULL, NULL};
        JavaVM* vm = AndroidRuntime::getJavaVM();
        int result = vm->AttachCurrentThread(&env, (void*) &args);
        if (result != JNI_OK) {
            ALOGE("thread attach failed: %#x", result);
            return NULL;
        }
        *needsDetach = true;
    }
    return env;
}

void JNIImageWriterContext::detachJNI() {
    ALOGV("%s", __FUNCTION__);
    JavaVM* vm = AndroidRuntime::getJavaVM();
    int result = vm->DetachCurrentThread();
    if (result != JNI_OK) {
        ALOGE("thread detach failed: %#x", result);
    }
}

void JNIImageWriterContext::onBufferReleased() {
    ALOGV("%s: buffer released", __FUNCTION__);
    bool needsDetach = false;
    JNIEnv* env = getJNIEnv(&needsDetach);
    if (env != NULL) {
        // Detach the buffer every time when a buffer consumption is done,
        // need let this callback give a BufferItem, then only detach if it was attached to this
        // Writer. Do the detach unconditionally for opaque format now. see b/19977520
        if (mFormat == HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED) {
            sp<Fence> fence;
            sp<GraphicBuffer> buffer;
            ALOGV("%s: One buffer is detached", __FUNCTION__);
            mProducer->detachNextBuffer(&buffer, &fence);
        }

        env->CallStaticVoidMethod(mClazz, gImageWriterClassInfo.postEventFromNative, mWeakThiz);
    } else {
        ALOGW("onBufferReleased event will not posted");
    }

    if (needsDetach) {
        detachJNI();
    }
}

// ----------------------------------------------------------------------------

extern "C" {

// -------------------------------Private method declarations--------------

static bool isPossiblyYUV(PixelFormat format);
static void Image_setNativeContext(JNIEnv* env, jobject thiz,
        sp<GraphicBuffer> buffer, int fenceFd);
static void Image_getNativeContext(JNIEnv* env, jobject thiz,
        GraphicBuffer** buffer, int* fenceFd);
static void Image_unlockIfLocked(JNIEnv* env, jobject thiz);
static bool isFormatOpaque(int format);

// --------------------------ImageWriter methods---------------------------------------

static void ImageWriter_classInit(JNIEnv* env, jclass clazz) {
    ALOGV("%s:", __FUNCTION__);
    jclass imageClazz = env->FindClass("android/media/ImageWriter$WriterSurfaceImage");
    LOG_ALWAYS_FATAL_IF(imageClazz == NULL,
            "can't find android/media/ImageWriter$WriterSurfaceImage");
    gSurfaceImageClassInfo.mNativeBuffer = env->GetFieldID(
            imageClazz, IMAGE_BUFFER_JNI_ID, "J");
    LOG_ALWAYS_FATAL_IF(gSurfaceImageClassInfo.mNativeBuffer == NULL,
            "can't find android/media/ImageWriter$WriterSurfaceImage.%s", IMAGE_BUFFER_JNI_ID);

    gSurfaceImageClassInfo.mNativeFenceFd = env->GetFieldID(
            imageClazz, "mNativeFenceFd", "I");
    LOG_ALWAYS_FATAL_IF(gSurfaceImageClassInfo.mNativeFenceFd == NULL,
            "can't find android/media/ImageWriter$WriterSurfaceImage.mNativeFenceFd");

    gSurfaceImageClassInfo.mPlanes = env->GetFieldID(
            imageClazz, "mPlanes", "[Landroid/media/ImageWriter$WriterSurfaceImage$SurfacePlane;");
    LOG_ALWAYS_FATAL_IF(gSurfaceImageClassInfo.mPlanes == NULL,
            "can't find android/media/ImageWriter$WriterSurfaceImage.mPlanes");

    gImageWriterClassInfo.postEventFromNative = env->GetStaticMethodID(
            clazz, "postEventFromNative", "(Ljava/lang/Object;)V");
    LOG_ALWAYS_FATAL_IF(gImageWriterClassInfo.postEventFromNative == NULL,
                        "can't find android/media/ImageWriter.postEventFromNative");

    gImageWriterClassInfo.mWriterFormat = env->GetFieldID(
            clazz, "mWriterFormat", "I");
    LOG_ALWAYS_FATAL_IF(gImageWriterClassInfo.mWriterFormat == NULL,
                        "can't find android/media/ImageWriter.mWriterFormat");

    jclass planeClazz = env->FindClass("android/media/ImageWriter$WriterSurfaceImage$SurfacePlane");
    LOG_ALWAYS_FATAL_IF(planeClazz == NULL, "Can not find SurfacePlane class");
    // FindClass only gives a local reference of jclass object.
    gSurfacePlaneClassInfo.clazz = (jclass) env->NewGlobalRef(planeClazz);
    gSurfacePlaneClassInfo.ctor = env->GetMethodID(gSurfacePlaneClassInfo.clazz, "<init>",
            "(Landroid/media/ImageWriter$WriterSurfaceImage;IILjava/nio/ByteBuffer;)V");
    LOG_ALWAYS_FATAL_IF(gSurfacePlaneClassInfo.ctor == NULL,
            "Can not find SurfacePlane constructor");
}

static jlong ImageWriter_init(JNIEnv* env, jobject thiz, jobject weakThiz, jobject jsurface,
        jint maxImages) {
    status_t res;

    ALOGV("%s: maxImages:%d", __FUNCTION__, maxImages);

    sp<Surface> surface(android_view_Surface_getSurface(env, jsurface));
    if (surface == NULL) {
        jniThrowException(env,
                "java/lang/IllegalArgumentException",
                "The surface has been released");
        return 0;
     }
    sp<IGraphicBufferProducer> bufferProducer = surface->getIGraphicBufferProducer();

    jclass clazz = env->GetObjectClass(thiz);
    if (clazz == NULL) {
        jniThrowRuntimeException(env, "Can't find android/graphics/ImageWriter");
        return 0;
    }
    sp<JNIImageWriterContext> ctx(new JNIImageWriterContext(env, weakThiz, clazz));

    sp<Surface> producer = new Surface(bufferProducer, /*controlledByApp*/false);
    ctx->setProducer(producer);
    /**
     * NATIVE_WINDOW_API_CPU isn't a good choice here, as it makes the bufferQueue not connectable
     * after disconnect. MEDIA or CAMERA are treated the same internally. The producer listener
     * will be cleared after disconnect call.
     */
    producer->connect(/*api*/NATIVE_WINDOW_API_CAMERA, /*listener*/ctx);
    jlong nativeCtx = reinterpret_cast<jlong>(ctx.get());

    // Get the dimension and format of the producer.
    sp<ANativeWindow> anw = producer;
    int32_t width, height, format;
    if ((res = anw->query(anw.get(), NATIVE_WINDOW_WIDTH, &width)) != OK) {
        ALOGE("%s: Query Surface width failed: %s (%d)", __FUNCTION__, strerror(-res), res);
        jniThrowRuntimeException(env, "Failed to query Surface width");
        return 0;
    }
    ctx->setBufferWidth(width);

    if ((res = anw->query(anw.get(), NATIVE_WINDOW_HEIGHT, &height)) != OK) {
        ALOGE("%s: Query Surface height failed: %s (%d)", __FUNCTION__, strerror(-res), res);
        jniThrowRuntimeException(env, "Failed to query Surface height");
        return 0;
    }
    ctx->setBufferHeight(height);

    if ((res = anw->query(anw.get(), NATIVE_WINDOW_FORMAT, &format)) != OK) {
        ALOGE("%s: Query Surface format failed: %s (%d)", __FUNCTION__, strerror(-res), res);
        jniThrowRuntimeException(env, "Failed to query Surface format");
        return 0;
    }
    ctx->setBufferFormat(format);
    env->SetIntField(thiz, gImageWriterClassInfo.mWriterFormat, reinterpret_cast<jint>(format));


    if (!isFormatOpaque(format)) {
        res = native_window_set_usage(anw.get(), GRALLOC_USAGE_SW_WRITE_OFTEN);
        if (res != OK) {
            ALOGE("%s: Configure usage %08x for format %08x failed: %s (%d)",
                  __FUNCTION__, static_cast<unsigned int>(GRALLOC_USAGE_SW_WRITE_OFTEN),
                  format, strerror(-res), res);
            jniThrowRuntimeException(env, "Failed to SW_WRITE_OFTEN configure usage");
            return 0;
        }
    }

    int minUndequeuedBufferCount = 0;
    res = anw->query(anw.get(),
                NATIVE_WINDOW_MIN_UNDEQUEUED_BUFFERS, &minUndequeuedBufferCount);
    if (res != OK) {
        ALOGE("%s: Query producer undequeued buffer count failed: %s (%d)",
                __FUNCTION__, strerror(-res), res);
        jniThrowRuntimeException(env, "Query producer undequeued buffer count failed");
        return 0;
     }

    size_t totalBufferCount = maxImages + minUndequeuedBufferCount;
    res = native_window_set_buffer_count(anw.get(), totalBufferCount);
    if (res != OK) {
        ALOGE("%s: Set buffer count failed: %s (%d)", __FUNCTION__, strerror(-res), res);
        jniThrowRuntimeException(env, "Set buffer count failed");
        return 0;
    }

    if (ctx != 0) {
        ctx->incStrong((void*)ImageWriter_init);
    }
    return nativeCtx;
}

static void ImageWriter_dequeueImage(JNIEnv* env, jobject thiz, jlong nativeCtx, jobject image) {
    ALOGV("%s", __FUNCTION__);
    JNIImageWriterContext* const ctx = reinterpret_cast<JNIImageWriterContext *>(nativeCtx);
    if (ctx == NULL || thiz == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
                "ImageWriterContext is not initialized");
        return;
    }

    sp<ANativeWindow> anw = ctx->getProducer();
    android_native_buffer_t *anb = NULL;
    int fenceFd = -1;
    status_t res = anw->dequeueBuffer(anw.get(), &anb, &fenceFd);
    if (res != OK) {
        ALOGE("%s: Dequeue buffer failed: %s (%d)", __FUNCTION__, strerror(-res), res);
        switch (res) {
            case NO_INIT:
                jniThrowException(env, "java/lang/IllegalStateException",
                    "Surface has been abandoned");
                break;
            default:
                // TODO: handle other error cases here.
                jniThrowRuntimeException(env, "dequeue buffer failed");
        }
        return;
    }
    // New GraphicBuffer object doesn't own the handle, thus the native buffer
    // won't be freed when this object is destroyed.
    sp<GraphicBuffer> buffer(new GraphicBuffer(anb, /*keepOwnership*/false));

    // Note that:
    // 1. No need to lock buffer now, will only lock it when the first getPlanes() is called.
    // 2. Fence will be saved to mNativeFenceFd, and will consumed by lock/queue/cancel buffer
    //    later.
    // 3. need use lockAsync here, as it will handle the dequeued fence for us automatically.

    // Finally, set the native info into image object.
    Image_setNativeContext(env, image, buffer, fenceFd);
}

static void ImageWriter_close(JNIEnv* env, jobject thiz, jlong nativeCtx) {
    ALOGV("%s:", __FUNCTION__);
    JNIImageWriterContext* const ctx = reinterpret_cast<JNIImageWriterContext *>(nativeCtx);
    if (ctx == NULL || thiz == NULL) {
        // ImageWriter is already closed.
        return;
    }

    ANativeWindow* producer = ctx->getProducer();
    if (producer != NULL) {
        /**
         * NATIVE_WINDOW_API_CPU isn't a good choice here, as it makes the bufferQueue not
         * connectable after disconnect. MEDIA or CAMERA are treated the same internally.
         * The producer listener will be cleared after disconnect call.
         */
        status_t res = native_window_api_disconnect(producer, /*api*/NATIVE_WINDOW_API_CAMERA);
        /**
         * This is not an error. if client calling process dies, the window will
         * also die and all calls to it will return DEAD_OBJECT, thus it's already
         * "disconnected"
         */
        if (res == DEAD_OBJECT) {
            ALOGW("%s: While disconnecting ImageWriter from native window, the"
                    " native window died already", __FUNCTION__);
        } else if (res != OK) {
            ALOGE("%s: native window disconnect failed: %s (%d)",
                    __FUNCTION__, strerror(-res), res);
            jniThrowRuntimeException(env, "Native window disconnect failed");
            return;
        }
    }

    ctx->decStrong((void*)ImageWriter_init);
}

static void ImageWriter_cancelImage(JNIEnv* env, jobject thiz, jlong nativeCtx, jobject image) {
    ALOGV("%s", __FUNCTION__);
    JNIImageWriterContext* const ctx = reinterpret_cast<JNIImageWriterContext *>(nativeCtx);
    if (ctx == NULL || thiz == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
                "ImageWriterContext is not initialized");
        return;
    }

    sp<ANativeWindow> anw = ctx->getProducer();

    GraphicBuffer *buffer = NULL;
    int fenceFd = -1;
    Image_getNativeContext(env, image, &buffer, &fenceFd);
    if (buffer == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
                "Image is not initialized");
        return;
    }

    // Unlock the image if it was locked
    Image_unlockIfLocked(env, image);

    anw->cancelBuffer(anw.get(), buffer, fenceFd);

    Image_setNativeContext(env, image, NULL, -1);
}

static void ImageWriter_queueImage(JNIEnv* env, jobject thiz, jlong nativeCtx, jobject image,
        jlong timestampNs, jint left, jint top, jint right, jint bottom) {
    ALOGV("%s", __FUNCTION__);
    JNIImageWriterContext* const ctx = reinterpret_cast<JNIImageWriterContext *>(nativeCtx);
    if (ctx == NULL || thiz == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
                "ImageWriterContext is not initialized");
        return;
    }

    status_t res = OK;
    sp<ANativeWindow> anw = ctx->getProducer();

    GraphicBuffer *buffer = NULL;
    int fenceFd = -1;
    Image_getNativeContext(env, image, &buffer, &fenceFd);
    if (buffer == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
                "Image is not initialized");
        return;
    }

    // Unlock image if it was locked.
    Image_unlockIfLocked(env, image);

    // Set timestamp
    ALOGV("timestamp to be queued: %" PRId64, timestampNs);
    res = native_window_set_buffers_timestamp(anw.get(), timestampNs);
    if (res != OK) {
        jniThrowRuntimeException(env, "Set timestamp failed");
        return;
    }

    // Set crop
    android_native_rect_t cropRect;
    cropRect.left = left;
    cropRect.top = top;
    cropRect.right = right;
    cropRect.bottom = bottom;
    res = native_window_set_crop(anw.get(), &cropRect);
    if (res != OK) {
        jniThrowRuntimeException(env, "Set crop rect failed");
        return;
    }

    // Finally, queue input buffer
    res = anw->queueBuffer(anw.get(), buffer, fenceFd);
    if (res != OK) {
        ALOGE("%s: Queue buffer failed: %s (%d)", __FUNCTION__, strerror(-res), res);
        switch (res) {
            case NO_INIT:
                jniThrowException(env, "java/lang/IllegalStateException",
                    "Surface has been abandoned");
                break;
            default:
                // TODO: handle other error cases here.
                jniThrowRuntimeException(env, "Queue input buffer failed");
        }
        return;
    }

    // Clear the image native context: end of this image's lifecycle in public API.
    Image_setNativeContext(env, image, NULL, -1);
}

static jint ImageWriter_attachAndQueueImage(JNIEnv* env, jobject thiz, jlong nativeCtx,
        jlong nativeBuffer, jint imageFormat, jlong timestampNs, jint left, jint top,
        jint right, jint bottom) {
    ALOGV("%s", __FUNCTION__);
    JNIImageWriterContext* const ctx = reinterpret_cast<JNIImageWriterContext *>(nativeCtx);
    if (ctx == NULL || thiz == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
                "ImageWriterContext is not initialized");
        return -1;
    }

    sp<Surface> surface = ctx->getProducer();
    status_t res = OK;
    if (!isFormatOpaque(imageFormat)) {
        // TODO: need implement, see b/19962027
        jniThrowRuntimeException(env,
                "nativeAttachImage for non-opaque image is not implement yet!!!");
        return -1;
    }

    if (!isFormatOpaque(ctx->getBufferFormat())) {
        jniThrowException(env, "java/lang/IllegalStateException",
                "Trying to attach an opaque image into a non-opaque ImageWriter");
        return -1;
    }

    // Image is guaranteed to be from ImageReader at this point, so it is safe to
    // cast to BufferItem pointer.
    BufferItem* opaqueBuffer = reinterpret_cast<BufferItem*>(nativeBuffer);
    if (opaqueBuffer == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
                "Image is not initialized or already closed");
        return -1;
    }

    // Step 1. Attach Image
    res = surface->attachBuffer(opaqueBuffer->mGraphicBuffer.get());
    if (res != OK) {
        ALOGE("Attach image failed: %s (%d)", strerror(-res), res);
        switch (res) {
            case NO_INIT:
                jniThrowException(env, "java/lang/IllegalStateException",
                    "Surface has been abandoned");
                break;
            default:
                // TODO: handle other error cases here.
                jniThrowRuntimeException(env, "nativeAttachImage failed!!!");
        }
        return res;
    }
    sp < ANativeWindow > anw = surface;

    // Step 2. Set timestamp and crop. Note that we do not need unlock the image because
    // it was not locked.
    ALOGV("timestamp to be queued: %" PRId64, timestampNs);
    res = native_window_set_buffers_timestamp(anw.get(), timestampNs);
    if (res != OK) {
        jniThrowRuntimeException(env, "Set timestamp failed");
        return res;
    }

    android_native_rect_t cropRect;
    cropRect.left = left;
    cropRect.top = top;
    cropRect.right = right;
    cropRect.bottom = bottom;
    res = native_window_set_crop(anw.get(), &cropRect);
    if (res != OK) {
        jniThrowRuntimeException(env, "Set crop rect failed");
        return res;
    }

    // Step 3. Queue Image.
    res = anw->queueBuffer(anw.get(), opaqueBuffer->mGraphicBuffer.get(), /*fenceFd*/
            -1);
    if (res != OK) {
        ALOGE("%s: Queue buffer failed: %s (%d)", __FUNCTION__, strerror(-res), res);
        switch (res) {
            case NO_INIT:
                jniThrowException(env, "java/lang/IllegalStateException",
                    "Surface has been abandoned");
                break;
            default:
                // TODO: handle other error cases here.
                jniThrowRuntimeException(env, "Queue input buffer failed");
        }
        return res;
    }

    // Do not set the image native context. Since it would overwrite the existing native context
    // of the image that is from ImageReader, the subsequent image close will run into issues.

    return res;
}

// --------------------------Image methods---------------------------------------

static void Image_getNativeContext(JNIEnv* env, jobject thiz,
        GraphicBuffer** buffer, int* fenceFd) {
    ALOGV("%s", __FUNCTION__);
    if (buffer != NULL) {
        GraphicBuffer *gb = reinterpret_cast<GraphicBuffer *>
                  (env->GetLongField(thiz, gSurfaceImageClassInfo.mNativeBuffer));
        *buffer = gb;
    }

    if (fenceFd != NULL) {
        *fenceFd = reinterpret_cast<jint>(env->GetIntField(
                thiz, gSurfaceImageClassInfo.mNativeFenceFd));
    }
}

static void Image_setNativeContext(JNIEnv* env, jobject thiz,
        sp<GraphicBuffer> buffer, int fenceFd) {
    ALOGV("%s:", __FUNCTION__);
    GraphicBuffer* p = NULL;
    Image_getNativeContext(env, thiz, &p, /*fenceFd*/NULL);
    if (buffer != 0) {
        buffer->incStrong((void*)Image_setNativeContext);
    }
    if (p) {
        p->decStrong((void*)Image_setNativeContext);
    }
    env->SetLongField(thiz, gSurfaceImageClassInfo.mNativeBuffer,
            reinterpret_cast<jlong>(buffer.get()));

    env->SetIntField(thiz, gSurfaceImageClassInfo.mNativeFenceFd, reinterpret_cast<jint>(fenceFd));
}

static void Image_unlockIfLocked(JNIEnv* env, jobject thiz) {
    ALOGV("%s", __FUNCTION__);
    GraphicBuffer* buffer;
    Image_getNativeContext(env, thiz, &buffer, NULL);
    if (buffer == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
                "Image is not initialized");
        return;
    }

    // Is locked?
    bool isLocked = false;
    jobject planes = NULL;
    if (!isFormatOpaque(buffer->getPixelFormat())) {
        planes = env->GetObjectField(thiz, gSurfaceImageClassInfo.mPlanes);
    }
    isLocked = (planes != NULL);
    if (isLocked) {
        // no need to use fence here, as we it will be consumed by either cancel or queue buffer.
        status_t res = buffer->unlock();
        if (res != OK) {
            jniThrowRuntimeException(env, "unlock buffer failed");
        }
        ALOGV("Successfully unlocked the image");
    }
}

static jint Image_getWidth(JNIEnv* env, jobject thiz) {
    ALOGV("%s", __FUNCTION__);
    GraphicBuffer* buffer;
    Image_getNativeContext(env, thiz, &buffer, NULL);
    if (buffer == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
                "Image is not initialized");
        return -1;
    }

    return buffer->getWidth();
}

static jint Image_getHeight(JNIEnv* env, jobject thiz) {
    ALOGV("%s", __FUNCTION__);
    GraphicBuffer* buffer;
    Image_getNativeContext(env, thiz, &buffer, NULL);
    if (buffer == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
                "Image is not initialized");
        return -1;
    }

    return buffer->getHeight();
}

// Some formats like JPEG defined with different values between android.graphics.ImageFormat and
// graphics.h, need convert to the one defined in graphics.h here.
static int Image_getPixelFormat(JNIEnv* env, int format) {
    int jpegFormat;
    jfieldID fid;

    ALOGV("%s: format = 0x%x", __FUNCTION__, format);

    jclass imageFormatClazz = env->FindClass("android/graphics/ImageFormat");
    ALOG_ASSERT(imageFormatClazz != NULL);

    fid = env->GetStaticFieldID(imageFormatClazz, "JPEG", "I");
    jpegFormat = env->GetStaticIntField(imageFormatClazz, fid);

    // Translate the JPEG to BLOB for camera purpose.
    if (format == jpegFormat) {
        format = HAL_PIXEL_FORMAT_BLOB;
    }

    return format;
}

static jint Image_getFormat(JNIEnv* env, jobject thiz) {
    ALOGV("%s", __FUNCTION__);
    GraphicBuffer* buffer;
    Image_getNativeContext(env, thiz, &buffer, NULL);
    if (buffer == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
                "Image is not initialized");
        return 0;
    }

    return Image_getPixelFormat(env, buffer->getPixelFormat());
}

static void Image_setFenceFd(JNIEnv* env, jobject thiz, int fenceFd) {
    ALOGV("%s:", __FUNCTION__);
    env->SetIntField(thiz, gSurfaceImageClassInfo.mNativeFenceFd, reinterpret_cast<jint>(fenceFd));
}

static void Image_getLockedImage(JNIEnv* env, jobject thiz, LockedImage *image) {
    ALOGV("%s", __FUNCTION__);
    GraphicBuffer* buffer;
    int fenceFd = -1;
    Image_getNativeContext(env, thiz, &buffer, &fenceFd);
    if (buffer == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
                "Image is not initialized");
        return;
    }

    void* pData = NULL;
    android_ycbcr ycbcr = android_ycbcr();
    status_t res;
    int format = Image_getFormat(env, thiz);
    int flexFormat = format;
    if (isPossiblyYUV(format)) {
        // ImageWriter doesn't use crop by itself, app sets it, use the no crop version.
        res = buffer->lockAsyncYCbCr(GRALLOC_USAGE_SW_WRITE_OFTEN, &ycbcr, fenceFd);
        // Clear the fenceFd as it is already consumed by lock call.
        Image_setFenceFd(env, thiz, /*fenceFd*/-1);
        if (res != OK) {
            jniThrowRuntimeException(env, "lockAsyncYCbCr failed for YUV buffer");
            return;
        }
        pData = ycbcr.y;
        flexFormat = HAL_PIXEL_FORMAT_YCbCr_420_888;
    }

    // lockAsyncYCbCr for YUV is unsuccessful.
    if (pData == NULL) {
        res = buffer->lockAsync(GRALLOC_USAGE_SW_WRITE_OFTEN, &pData, fenceFd);
        if (res != OK) {
            jniThrowRuntimeException(env, "lockAsync failed");
            return;
        }
    }

    image->data = reinterpret_cast<uint8_t*>(pData);
    image->width = buffer->getWidth();
    image->height = buffer->getHeight();
    image->format = format;
    image->flexFormat = flexFormat;
    image->stride = (ycbcr.y != NULL) ? static_cast<uint32_t>(ycbcr.ystride) : buffer->getStride();

    image->dataCb = reinterpret_cast<uint8_t*>(ycbcr.cb);
    image->dataCr = reinterpret_cast<uint8_t*>(ycbcr.cr);
    image->chromaStride = static_cast<uint32_t>(ycbcr.cstride);
    image->chromaStep = static_cast<uint32_t>(ycbcr.chroma_step);
    ALOGV("Successfully locked the image");
    // crop, transform, scalingMode, timestamp, and frameNumber should be set by producer,
    // and we don't set them here.
}

static bool usingRGBAToJpegOverride(int32_t bufferFormat, int32_t writerCtxFormat) {
    return writerCtxFormat == HAL_PIXEL_FORMAT_BLOB && bufferFormat == HAL_PIXEL_FORMAT_RGBA_8888;
}

static int32_t applyFormatOverrides(int32_t bufferFormat, int32_t writerCtxFormat)
{
    // Using HAL_PIXEL_FORMAT_RGBA_8888 gralloc buffers containing JPEGs to get around SW
    // write limitations for some platforms (b/17379185).
    if (usingRGBAToJpegOverride(bufferFormat, writerCtxFormat)) {
        return HAL_PIXEL_FORMAT_BLOB;
    }
    return bufferFormat;
}

static uint32_t Image_getJpegSize(LockedImage* buffer, bool usingRGBAOverride) {
    ALOGV("%s", __FUNCTION__);
    ALOG_ASSERT(buffer != NULL, "Input buffer is NULL!!!");
    uint32_t size = 0;
    uint32_t width = buffer->width;
    uint8_t* jpegBuffer = buffer->data;

    if (usingRGBAOverride) {
        width = (buffer->width + buffer->stride * (buffer->height - 1)) * 4;
    }

    // First check for JPEG transport header at the end of the buffer
    uint8_t* header = jpegBuffer + (width - sizeof(struct camera3_jpeg_blob));
    struct camera3_jpeg_blob *blob = (struct camera3_jpeg_blob*)(header);
    if (blob->jpeg_blob_id == CAMERA3_JPEG_BLOB_ID) {
        size = blob->jpeg_size;
        ALOGV("%s: Jpeg size = %d", __FUNCTION__, size);
    }

    // failed to find size, default to whole buffer
    if (size == 0) {
        /*
         * This is a problem because not including the JPEG header
         * means that in certain rare situations a regular JPEG blob
         * will be misidentified as having a header, in which case
         * we will get a garbage size value.
         */
        ALOGW("%s: No JPEG header detected, defaulting to size=width=%d",
                __FUNCTION__, width);
        size = width;
    }

    return size;
}

static void Image_getLockedImageInfo(JNIEnv* env, LockedImage* buffer, int idx,
        int32_t writerFormat, uint8_t **base, uint32_t *size, int *pixelStride, int *rowStride) {
    ALOGV("%s", __FUNCTION__);
    ALOG_ASSERT(buffer != NULL, "Input buffer is NULL!!!");
    ALOG_ASSERT(base != NULL, "base is NULL!!!");
    ALOG_ASSERT(size != NULL, "size is NULL!!!");
    ALOG_ASSERT(pixelStride != NULL, "pixelStride is NULL!!!");
    ALOG_ASSERT(rowStride != NULL, "rowStride is NULL!!!");
    ALOG_ASSERT((idx < IMAGE_WRITER_MAX_NUM_PLANES) && (idx >= 0));

    ALOGV("%s: buffer: %p", __FUNCTION__, buffer);

    uint32_t dataSize, ySize, cSize, cStride;
    uint32_t pStride = 0, rStride = 0;
    uint8_t *cb, *cr;
    uint8_t *pData = NULL;
    int bytesPerPixel = 0;

    dataSize = ySize = cSize = cStride = 0;
    int32_t fmt = buffer->flexFormat;

    bool usingRGBAOverride = usingRGBAToJpegOverride(fmt, writerFormat);
    fmt = applyFormatOverrides(fmt, writerFormat);
    switch (fmt) {
        case HAL_PIXEL_FORMAT_YCbCr_420_888:
            pData =
                (idx == 0) ?
                    buffer->data :
                (idx == 1) ?
                    buffer->dataCb :
                buffer->dataCr;
            // only map until last pixel
            if (idx == 0) {
                pStride = 1;
                rStride = buffer->stride;
                dataSize = buffer->stride * (buffer->height - 1) + buffer->width;
            } else {
                pStride = buffer->chromaStep;
                rStride = buffer->chromaStride;
                dataSize = buffer->chromaStride * (buffer->height / 2 - 1) +
                        buffer->chromaStep * (buffer->width / 2 - 1) + 1;
            }
            break;
        // NV21
        case HAL_PIXEL_FORMAT_YCrCb_420_SP:
            cr = buffer->data + (buffer->stride * buffer->height);
            cb = cr + 1;
            // only map until last pixel
            ySize = buffer->width * (buffer->height - 1) + buffer->width;
            cSize = buffer->width * (buffer->height / 2 - 1) + buffer->width - 1;

            pData =
                (idx == 0) ?
                    buffer->data :
                (idx == 1) ?
                    cb:
                cr;

            dataSize = (idx == 0) ? ySize : cSize;
            pStride = (idx == 0) ? 1 : 2;
            rStride = buffer->width;
            break;
        case HAL_PIXEL_FORMAT_YV12:
            // Y and C stride need to be 16 pixel aligned.
            LOG_ALWAYS_FATAL_IF(buffer->stride % 16,
                                "Stride is not 16 pixel aligned %d", buffer->stride);

            ySize = buffer->stride * buffer->height;
            cStride = ALIGN(buffer->stride / 2, 16);
            cr = buffer->data + ySize;
            cSize = cStride * buffer->height / 2;
            cb = cr + cSize;

            pData =
                (idx == 0) ?
                    buffer->data :
                (idx == 1) ?
                    cb :
                cr;
            dataSize = (idx == 0) ? ySize : cSize;
            pStride = 1;
            rStride = (idx == 0) ? buffer->stride : ALIGN(buffer->stride / 2, 16);
            break;
        case HAL_PIXEL_FORMAT_Y8:
            // Single plane, 8bpp.
            ALOG_ASSERT(idx == 0, "Wrong index: %d", idx);

            pData = buffer->data;
            dataSize = buffer->stride * buffer->height;
            pStride = 1;
            rStride = buffer->stride;
            break;
        case HAL_PIXEL_FORMAT_Y16:
            bytesPerPixel = 2;
            // Single plane, 16bpp, strides are specified in pixels, not in bytes
            ALOG_ASSERT(idx == 0, "Wrong index: %d", idx);

            pData = buffer->data;
            dataSize = buffer->stride * buffer->height * bytesPerPixel;
            pStride = bytesPerPixel;
            rStride = buffer->stride * 2;
            break;
        case HAL_PIXEL_FORMAT_BLOB:
            // Used for JPEG data, height must be 1, width == size, single plane.
            ALOG_ASSERT(idx == 0, "Wrong index: %d", idx);
            ALOG_ASSERT(buffer->height == 1, "JPEG should has height value %d", buffer->height);

            pData = buffer->data;
            dataSize = Image_getJpegSize(buffer, usingRGBAOverride);
            pStride = bytesPerPixel;
            rowStride = 0;
            break;
        case HAL_PIXEL_FORMAT_RAW16:
            // Single plane 16bpp bayer data.
            bytesPerPixel = 2;
            ALOG_ASSERT(idx == 0, "Wrong index: %d", idx);
            pData = buffer->data;
            dataSize = buffer->stride * buffer->height * bytesPerPixel;
            pStride = bytesPerPixel;
            rStride = buffer->stride * 2;
            break;
        case HAL_PIXEL_FORMAT_RAW10:
            // Single plane 10bpp bayer data.
            ALOG_ASSERT(idx == 0, "Wrong index: %d", idx);
            LOG_ALWAYS_FATAL_IF(buffer->width % 4,
                                "Width is not multiple of 4 %d", buffer->width);
            LOG_ALWAYS_FATAL_IF(buffer->height % 2,
                                "Height is not even %d", buffer->height);
            LOG_ALWAYS_FATAL_IF(buffer->stride < (buffer->width * 10 / 8),
                                "stride (%d) should be at least %d",
                                buffer->stride, buffer->width * 10 / 8);
            pData = buffer->data;
            dataSize = buffer->stride * buffer->height;
            pStride = 0;
            rStride = buffer->stride;
            break;
        case HAL_PIXEL_FORMAT_RGBA_8888:
        case HAL_PIXEL_FORMAT_RGBX_8888:
            // Single plane, 32bpp.
            bytesPerPixel = 4;
            ALOG_ASSERT(idx == 0, "Wrong index: %d", idx);
            pData = buffer->data;
            dataSize = buffer->stride * buffer->height * bytesPerPixel;
            pStride = bytesPerPixel;
            rStride = buffer->stride * 4;
            break;
        case HAL_PIXEL_FORMAT_RGB_565:
            // Single plane, 16bpp.
            bytesPerPixel = 2;
            ALOG_ASSERT(idx == 0, "Wrong index: %d", idx);
            pData = buffer->data;
            dataSize = buffer->stride * buffer->height * bytesPerPixel;
            pStride = bytesPerPixel;
            rStride = buffer->stride * 2;
            break;
        case HAL_PIXEL_FORMAT_RGB_888:
            // Single plane, 24bpp.
            bytesPerPixel = 3;
            ALOG_ASSERT(idx == 0, "Wrong index: %d", idx);
            pData = buffer->data;
            dataSize = buffer->stride * buffer->height * bytesPerPixel;
            pStride = bytesPerPixel;
            rStride = buffer->stride * 3;
            break;
        default:
            jniThrowExceptionFmt(env, "java/lang/UnsupportedOperationException",
                                 "Pixel format: 0x%x is unsupported", fmt);
            break;
    }

    *base = pData;
    *size = dataSize;
    *pixelStride = pStride;
    *rowStride = rStride;
}

static jobjectArray Image_createSurfacePlanes(JNIEnv* env, jobject thiz,
        int numPlanes, int writerFormat) {
    ALOGV("%s: create SurfacePlane array with size %d", __FUNCTION__, numPlanes);
    int rowStride, pixelStride;
    uint8_t *pData;
    uint32_t dataSize;
    jobject byteBuffer;

    int format = Image_getFormat(env, thiz);
    if (isFormatOpaque(format) && numPlanes > 0) {
        String8 msg;
        msg.appendFormat("Format 0x%x is opaque, thus not writable, the number of planes (%d)"
                " must be 0", format, numPlanes);
        jniThrowException(env, "java/lang/IllegalArgumentException", msg.string());
        return NULL;
    }

    jobjectArray surfacePlanes = env->NewObjectArray(numPlanes, gSurfacePlaneClassInfo.clazz,
            /*initial_element*/NULL);
    if (surfacePlanes == NULL) {
        jniThrowRuntimeException(env, "Failed to create SurfacePlane arrays,"
                " probably out of memory");
        return NULL;
    }
    if (isFormatOpaque(format)) {
        return surfacePlanes;
    }

    // Buildup buffer info: rowStride, pixelStride and byteBuffers.
    LockedImage lockedImg = LockedImage();
    Image_getLockedImage(env, thiz, &lockedImg);

    // Create all SurfacePlanes
    writerFormat = Image_getPixelFormat(env, writerFormat);
    for (int i = 0; i < numPlanes; i++) {
        Image_getLockedImageInfo(env, &lockedImg, i, writerFormat,
                &pData, &dataSize, &pixelStride, &rowStride);
        byteBuffer = env->NewDirectByteBuffer(pData, dataSize);
        if ((byteBuffer == NULL) && (env->ExceptionCheck() == false)) {
            jniThrowException(env, "java/lang/IllegalStateException",
                    "Failed to allocate ByteBuffer");
            return NULL;
        }

        // Finally, create this SurfacePlane.
        jobject surfacePlane = env->NewObject(gSurfacePlaneClassInfo.clazz,
                    gSurfacePlaneClassInfo.ctor, thiz, rowStride, pixelStride, byteBuffer);
        env->SetObjectArrayElement(surfacePlanes, i, surfacePlane);
    }

    return surfacePlanes;
}

// -------------------------------Private convenience methods--------------------

static bool isFormatOpaque(int format) {
    // Only treat IMPLEMENTATION_DEFINED as an opaque format for now.
    return format == HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED;
}

static bool isPossiblyYUV(PixelFormat format) {
    switch (static_cast<int>(format)) {
        case HAL_PIXEL_FORMAT_RGBA_8888:
        case HAL_PIXEL_FORMAT_RGBX_8888:
        case HAL_PIXEL_FORMAT_RGB_888:
        case HAL_PIXEL_FORMAT_RGB_565:
        case HAL_PIXEL_FORMAT_BGRA_8888:
        case HAL_PIXEL_FORMAT_Y8:
        case HAL_PIXEL_FORMAT_Y16:
        case HAL_PIXEL_FORMAT_RAW16:
        case HAL_PIXEL_FORMAT_RAW10:
        case HAL_PIXEL_FORMAT_RAW_OPAQUE:
        case HAL_PIXEL_FORMAT_BLOB:
        case HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED:
            return false;

        case HAL_PIXEL_FORMAT_YV12:
        case HAL_PIXEL_FORMAT_YCbCr_420_888:
        case HAL_PIXEL_FORMAT_YCbCr_422_SP:
        case HAL_PIXEL_FORMAT_YCrCb_420_SP:
        case HAL_PIXEL_FORMAT_YCbCr_422_I:
        default:
            return true;
    }
}

} // extern "C"

// ----------------------------------------------------------------------------

static JNINativeMethod gImageWriterMethods[] = {
    {"nativeClassInit",         "()V",                        (void*)ImageWriter_classInit },
    {"nativeInit",              "(Ljava/lang/Object;Landroid/view/Surface;I)J",
                                                              (void*)ImageWriter_init },
    {"nativeClose",              "(J)V",                      (void*)ImageWriter_close },
    {"nativeAttachAndQueueImage", "(JJIJIIII)I",          (void*)ImageWriter_attachAndQueueImage },
    {"nativeDequeueInputImage", "(JLandroid/media/Image;)V",  (void*)ImageWriter_dequeueImage },
    {"nativeQueueInputImage",   "(JLandroid/media/Image;JIIII)V",  (void*)ImageWriter_queueImage },
    {"cancelImage",             "(JLandroid/media/Image;)V",   (void*)ImageWriter_cancelImage },
};

static JNINativeMethod gImageMethods[] = {
    {"nativeCreatePlanes",      "(II)[Landroid/media/ImageWriter$WriterSurfaceImage$SurfacePlane;",
                                                              (void*)Image_createSurfacePlanes },
    {"nativeGetWidth",         "()I",                         (void*)Image_getWidth },
    {"nativeGetHeight",        "()I",                         (void*)Image_getHeight },
    {"nativeGetFormat",        "()I",                         (void*)Image_getFormat },
};

int register_android_media_ImageWriter(JNIEnv *env) {

    int ret1 = AndroidRuntime::registerNativeMethods(env,
                   "android/media/ImageWriter", gImageWriterMethods, NELEM(gImageWriterMethods));

    int ret2 = AndroidRuntime::registerNativeMethods(env,
                   "android/media/ImageWriter$WriterSurfaceImage", gImageMethods, NELEM(gImageMethods));

    return (ret1 || ret2);
}

