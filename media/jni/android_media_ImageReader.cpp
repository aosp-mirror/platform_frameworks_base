/*
 * Copyright 2013 The Android Open Source Project
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
#define LOG_TAG "ImageReader_JNI"
#include "android_media_Utils.h"
#include <utils/Log.h>
#include <utils/misc.h>
#include <utils/List.h>
#include <utils/String8.h>

#include <cstdio>

#include <gui/BufferItemConsumer.h>
#include <gui/Surface.h>
#include <camera3.h>

#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/android_view_Surface.h>

#include <jni.h>
#include <JNIHelp.h>

#include <stdint.h>
#include <inttypes.h>

#define ANDROID_MEDIA_IMAGEREADER_CTX_JNI_ID       "mNativeContext"
#define ANDROID_MEDIA_SURFACEIMAGE_BUFFER_JNI_ID   "mNativeBuffer"
#define ANDROID_MEDIA_SURFACEIMAGE_TS_JNI_ID       "mTimestamp"

// ----------------------------------------------------------------------------

using namespace android;


enum {
    ACQUIRE_SUCCESS = 0,
    ACQUIRE_NO_BUFFERS = 1,
    ACQUIRE_MAX_IMAGES = 2,
};

static struct {
    jfieldID mNativeContext;
    jmethodID postEventFromNative;
} gImageReaderClassInfo;

static struct {
    jfieldID mNativeBuffer;
    jfieldID mTimestamp;
    jfieldID mPlanes;
} gSurfaceImageClassInfo;

static struct {
    jclass clazz;
    jmethodID ctor;
} gSurfacePlaneClassInfo;

// Get an ID that's unique within this process.
static int32_t createProcessUniqueId() {
    static volatile int32_t globalCounter = 0;
    return android_atomic_inc(&globalCounter);
}

// ----------------------------------------------------------------------------

class JNIImageReaderContext : public ConsumerBase::FrameAvailableListener
{
public:
    JNIImageReaderContext(JNIEnv* env, jobject weakThiz, jclass clazz, int maxImages);

    virtual ~JNIImageReaderContext();

    virtual void onFrameAvailable(const BufferItem& item);

    BufferItem* getBufferItem();
    void returnBufferItem(BufferItem* buffer);


    void setBufferConsumer(const sp<BufferItemConsumer>& consumer) { mConsumer = consumer; }
    BufferItemConsumer* getBufferConsumer() { return mConsumer.get(); }

    void setProducer(const sp<IGraphicBufferProducer>& producer) { mProducer = producer; }
    IGraphicBufferProducer* getProducer() { return mProducer.get(); }

    void setBufferFormat(int format) { mFormat = format; }
    int getBufferFormat() { return mFormat; }

    void setBufferDataspace(android_dataspace dataSpace) { mDataSpace = dataSpace; }
    android_dataspace getBufferDataspace() { return mDataSpace; }

    void setBufferWidth(int width) { mWidth = width; }
    int getBufferWidth() { return mWidth; }

    void setBufferHeight(int height) { mHeight = height; }
    int getBufferHeight() { return mHeight; }

private:
    static JNIEnv* getJNIEnv(bool* needsDetach);
    static void detachJNI();

    List<BufferItem*> mBuffers;
    sp<BufferItemConsumer> mConsumer;
    sp<IGraphicBufferProducer> mProducer;
    jobject mWeakThiz;
    jclass mClazz;
    int mFormat;
    android_dataspace mDataSpace;
    int mWidth;
    int mHeight;
};

JNIImageReaderContext::JNIImageReaderContext(JNIEnv* env,
        jobject weakThiz, jclass clazz, int maxImages) :
    mWeakThiz(env->NewGlobalRef(weakThiz)),
    mClazz((jclass)env->NewGlobalRef(clazz)),
    mFormat(0),
    mDataSpace(HAL_DATASPACE_UNKNOWN),
    mWidth(-1),
    mHeight(-1) {
    for (int i = 0; i < maxImages; i++) {
        BufferItem* buffer = new BufferItem;
        mBuffers.push_back(buffer);
    }
}

JNIEnv* JNIImageReaderContext::getJNIEnv(bool* needsDetach) {
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

void JNIImageReaderContext::detachJNI() {
    JavaVM* vm = AndroidRuntime::getJavaVM();
    int result = vm->DetachCurrentThread();
    if (result != JNI_OK) {
        ALOGE("thread detach failed: %#x", result);
    }
}

BufferItem* JNIImageReaderContext::getBufferItem() {
    if (mBuffers.empty()) {
        return NULL;
    }
    // Return a BufferItem pointer and remove it from the list
    List<BufferItem*>::iterator it = mBuffers.begin();
    BufferItem* buffer = *it;
    mBuffers.erase(it);
    return buffer;
}

void JNIImageReaderContext::returnBufferItem(BufferItem* buffer) {
    mBuffers.push_back(buffer);
}

JNIImageReaderContext::~JNIImageReaderContext() {
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

    // Delete buffer items.
    for (List<BufferItem *>::iterator it = mBuffers.begin();
            it != mBuffers.end(); it++) {
        delete *it;
    }

    if (mConsumer != 0) {
        mConsumer.clear();
    }
}

void JNIImageReaderContext::onFrameAvailable(const BufferItem& /*item*/)
{
    ALOGV("%s: frame available", __FUNCTION__);
    bool needsDetach = false;
    JNIEnv* env = getJNIEnv(&needsDetach);
    if (env != NULL) {
        env->CallStaticVoidMethod(mClazz, gImageReaderClassInfo.postEventFromNative, mWeakThiz);
    } else {
        ALOGW("onFrameAvailable event will not posted");
    }
    if (needsDetach) {
        detachJNI();
    }
}

// ----------------------------------------------------------------------------

extern "C" {

static JNIImageReaderContext* ImageReader_getContext(JNIEnv* env, jobject thiz)
{
    JNIImageReaderContext *ctx;
    ctx = reinterpret_cast<JNIImageReaderContext *>
              (env->GetLongField(thiz, gImageReaderClassInfo.mNativeContext));
    return ctx;
}

static IGraphicBufferProducer* ImageReader_getProducer(JNIEnv* env, jobject thiz)
{
    ALOGV("%s:", __FUNCTION__);
    JNIImageReaderContext* const ctx = ImageReader_getContext(env, thiz);
    if (ctx == NULL) {
        jniThrowRuntimeException(env, "ImageReaderContext is not initialized");
        return NULL;
    }

    return ctx->getProducer();
}

static void ImageReader_setNativeContext(JNIEnv* env,
        jobject thiz, sp<JNIImageReaderContext> ctx)
{
    ALOGV("%s:", __FUNCTION__);
    JNIImageReaderContext* const p = ImageReader_getContext(env, thiz);
    if (ctx != 0) {
        ctx->incStrong((void*)ImageReader_setNativeContext);
    }
    if (p) {
        p->decStrong((void*)ImageReader_setNativeContext);
    }
    env->SetLongField(thiz, gImageReaderClassInfo.mNativeContext,
            reinterpret_cast<jlong>(ctx.get()));
}

static BufferItemConsumer* ImageReader_getBufferConsumer(JNIEnv* env, jobject thiz)
{
    ALOGV("%s:", __FUNCTION__);
    JNIImageReaderContext* const ctx = ImageReader_getContext(env, thiz);
    if (ctx == NULL) {
        jniThrowRuntimeException(env, "ImageReaderContext is not initialized");
        return NULL;
    }

    return ctx->getBufferConsumer();
}

static void Image_setBufferItem(JNIEnv* env, jobject thiz,
        const BufferItem* buffer)
{
    env->SetLongField(thiz, gSurfaceImageClassInfo.mNativeBuffer, reinterpret_cast<jlong>(buffer));
}

static BufferItem* Image_getBufferItem(JNIEnv* env, jobject image)
{
    return reinterpret_cast<BufferItem*>(
            env->GetLongField(image, gSurfaceImageClassInfo.mNativeBuffer));
}


// ----------------------------------------------------------------------------

static void ImageReader_classInit(JNIEnv* env, jclass clazz)
{
    ALOGV("%s:", __FUNCTION__);

    jclass imageClazz = env->FindClass("android/media/ImageReader$SurfaceImage");
    LOG_ALWAYS_FATAL_IF(imageClazz == NULL,
                        "can't find android/graphics/ImageReader$SurfaceImage");
    gSurfaceImageClassInfo.mNativeBuffer = env->GetFieldID(
            imageClazz, ANDROID_MEDIA_SURFACEIMAGE_BUFFER_JNI_ID, "J");
    LOG_ALWAYS_FATAL_IF(gSurfaceImageClassInfo.mNativeBuffer == NULL,
                        "can't find android/graphics/ImageReader.%s",
                        ANDROID_MEDIA_SURFACEIMAGE_BUFFER_JNI_ID);

    gSurfaceImageClassInfo.mTimestamp = env->GetFieldID(
            imageClazz, ANDROID_MEDIA_SURFACEIMAGE_TS_JNI_ID, "J");
    LOG_ALWAYS_FATAL_IF(gSurfaceImageClassInfo.mTimestamp == NULL,
                        "can't find android/graphics/ImageReader.%s",
                        ANDROID_MEDIA_SURFACEIMAGE_TS_JNI_ID);

    gSurfaceImageClassInfo.mPlanes = env->GetFieldID(
            imageClazz, "mPlanes", "[Landroid/media/ImageReader$SurfaceImage$SurfacePlane;");
    LOG_ALWAYS_FATAL_IF(gSurfaceImageClassInfo.mPlanes == NULL,
            "can't find android/media/ImageReader$ReaderSurfaceImage.mPlanes");

    gImageReaderClassInfo.mNativeContext = env->GetFieldID(
            clazz, ANDROID_MEDIA_IMAGEREADER_CTX_JNI_ID, "J");
    LOG_ALWAYS_FATAL_IF(gImageReaderClassInfo.mNativeContext == NULL,
                        "can't find android/graphics/ImageReader.%s",
                          ANDROID_MEDIA_IMAGEREADER_CTX_JNI_ID);

    gImageReaderClassInfo.postEventFromNative = env->GetStaticMethodID(
            clazz, "postEventFromNative", "(Ljava/lang/Object;)V");
    LOG_ALWAYS_FATAL_IF(gImageReaderClassInfo.postEventFromNative == NULL,
                        "can't find android/graphics/ImageReader.postEventFromNative");

    jclass planeClazz = env->FindClass("android/media/ImageReader$SurfaceImage$SurfacePlane");
    LOG_ALWAYS_FATAL_IF(planeClazz == NULL, "Can not find SurfacePlane class");
    // FindClass only gives a local reference of jclass object.
    gSurfacePlaneClassInfo.clazz = (jclass) env->NewGlobalRef(planeClazz);
    gSurfacePlaneClassInfo.ctor = env->GetMethodID(gSurfacePlaneClassInfo.clazz, "<init>",
            "(Landroid/media/ImageReader$SurfaceImage;IILjava/nio/ByteBuffer;)V");
    LOG_ALWAYS_FATAL_IF(gSurfacePlaneClassInfo.ctor == NULL,
            "Can not find SurfacePlane constructor");
}

static void ImageReader_init(JNIEnv* env, jobject thiz, jobject weakThiz,
                             jint width, jint height, jint format, jint maxImages)
{
    status_t res;
    int nativeFormat;
    android_dataspace nativeDataspace;

    ALOGV("%s: width:%d, height: %d, format: 0x%x, maxImages:%d",
          __FUNCTION__, width, height, format, maxImages);

    PublicFormat publicFormat = static_cast<PublicFormat>(format);
    nativeFormat = android_view_Surface_mapPublicFormatToHalFormat(
        publicFormat);
    nativeDataspace = android_view_Surface_mapPublicFormatToHalDataspace(
        publicFormat);

    jclass clazz = env->GetObjectClass(thiz);
    if (clazz == NULL) {
        jniThrowRuntimeException(env, "Can't find android/graphics/ImageReader");
        return;
    }
    sp<JNIImageReaderContext> ctx(new JNIImageReaderContext(env, weakThiz, clazz, maxImages));

    sp<IGraphicBufferProducer> gbProducer;
    sp<IGraphicBufferConsumer> gbConsumer;
    BufferQueue::createBufferQueue(&gbProducer, &gbConsumer);
    sp<BufferItemConsumer> bufferConsumer;
    String8 consumerName = String8::format("ImageReader-%dx%df%xm%d-%d-%d",
            width, height, format, maxImages, getpid(),
            createProcessUniqueId());
    uint32_t consumerUsage = GRALLOC_USAGE_SW_READ_OFTEN;

    if (isFormatOpaque(nativeFormat)) {
        // Use the SW_READ_NEVER usage to tell producer that this format is not for preview or video
        // encoding. The only possibility will be ZSL output.
        consumerUsage = GRALLOC_USAGE_SW_READ_NEVER;
    }
    bufferConsumer = new BufferItemConsumer(gbConsumer, consumerUsage, maxImages,
            /*controlledByApp*/true);
    if (bufferConsumer == nullptr) {
        jniThrowExceptionFmt(env, "java/lang/RuntimeException",
                "Failed to allocate native buffer consumer for format 0x%x", nativeFormat);
        return;
    }
    ctx->setBufferConsumer(bufferConsumer);
    bufferConsumer->setName(consumerName);

    ctx->setProducer(gbProducer);
    bufferConsumer->setFrameAvailableListener(ctx);
    ImageReader_setNativeContext(env, thiz, ctx);
    ctx->setBufferFormat(nativeFormat);
    ctx->setBufferDataspace(nativeDataspace);
    ctx->setBufferWidth(width);
    ctx->setBufferHeight(height);

    // Set the width/height/format/dataspace to the bufferConsumer.
    res = bufferConsumer->setDefaultBufferSize(width, height);
    if (res != OK) {
        jniThrowExceptionFmt(env, "java/lang/IllegalStateException",
                          "Failed to set buffer consumer default size (%dx%d) for format 0x%x",
                          width, height, nativeFormat);
        return;
    }
    res = bufferConsumer->setDefaultBufferFormat(nativeFormat);
    if (res != OK) {
        jniThrowExceptionFmt(env, "java/lang/IllegalStateException",
                          "Failed to set buffer consumer default format 0x%x", nativeFormat);
    }
    res = bufferConsumer->setDefaultBufferDataSpace(nativeDataspace);
    if (res != OK) {
        jniThrowExceptionFmt(env, "java/lang/IllegalStateException",
                          "Failed to set buffer consumer default dataSpace 0x%x", nativeDataspace);
    }
}

static void ImageReader_close(JNIEnv* env, jobject thiz)
{
    ALOGV("%s:", __FUNCTION__);

    JNIImageReaderContext* const ctx = ImageReader_getContext(env, thiz);
    if (ctx == NULL) {
        // ImageReader is already closed.
        return;
    }

    BufferItemConsumer* consumer = NULL;
    consumer = ImageReader_getBufferConsumer(env, thiz);

    if (consumer != NULL) {
        consumer->abandon();
        consumer->setFrameAvailableListener(NULL);
    }
    ImageReader_setNativeContext(env, thiz, NULL);
}

static sp<Fence> Image_unlockIfLocked(JNIEnv* env, jobject image) {
    ALOGV("%s", __FUNCTION__);
    BufferItem* buffer = Image_getBufferItem(env, image);
    if (buffer == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
                "Image is not initialized");
        return Fence::NO_FENCE;
    }

    // Is locked?
    bool wasBufferLocked = false;
    jobject planes = NULL;
    if (!isFormatOpaque(buffer->mGraphicBuffer->getPixelFormat())) {
        planes = env->GetObjectField(image, gSurfaceImageClassInfo.mPlanes);
    }
    wasBufferLocked = (planes != NULL);
    if (wasBufferLocked) {
        status_t res = OK;
        int fenceFd = -1;
        if (wasBufferLocked) {
            res = buffer->mGraphicBuffer->unlockAsync(&fenceFd);
            if (res != OK) {
                jniThrowRuntimeException(env, "unlock buffer failed");
                return Fence::NO_FENCE;
            }
        }
        sp<Fence> releaseFence = new Fence(fenceFd);
        return releaseFence;
        ALOGV("Successfully unlocked the image");
    }
    return Fence::NO_FENCE;
}

static void ImageReader_imageRelease(JNIEnv* env, jobject thiz, jobject image)
{
    ALOGV("%s:", __FUNCTION__);
    JNIImageReaderContext* ctx = ImageReader_getContext(env, thiz);
    if (ctx == NULL) {
        ALOGW("ImageReader#close called before Image#close, consider calling Image#close first");
        return;
    }

    BufferItemConsumer* bufferConsumer = ctx->getBufferConsumer();
    BufferItem* buffer = Image_getBufferItem(env, image);
    if (buffer == nullptr) {
        // Release an already closed image is harmless.
        return;
    }

    sp<Fence> releaseFence = Image_unlockIfLocked(env, image);
    bufferConsumer->releaseBuffer(*buffer, releaseFence);
    Image_setBufferItem(env, image, NULL);
    ctx->returnBufferItem(buffer);
    ALOGV("%s: Image (format: 0x%x) has been released", __FUNCTION__, ctx->getBufferFormat());
}

static jint ImageReader_imageSetup(JNIEnv* env, jobject thiz, jobject image) {
    ALOGV("%s:", __FUNCTION__);
    JNIImageReaderContext* ctx = ImageReader_getContext(env, thiz);
    if (ctx == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
                "ImageReader is not initialized or was already closed");
        return -1;
    }

    BufferItemConsumer* bufferConsumer = ctx->getBufferConsumer();
    BufferItem* buffer = ctx->getBufferItem();
    if (buffer == NULL) {
        ALOGW("Unable to acquire a buffer item, very likely client tried to acquire more than"
            " maxImages buffers");
        return ACQUIRE_MAX_IMAGES;
    }

    status_t res = bufferConsumer->acquireBuffer(buffer, 0);
    if (res != OK) {
        ctx->returnBufferItem(buffer);
        if (res != BufferQueue::NO_BUFFER_AVAILABLE) {
            if (res == INVALID_OPERATION) {
                // Max number of images were already acquired.
                ALOGE("%s: Max number of buffers allowed are already acquired : %s (%d)",
                        __FUNCTION__, strerror(-res), res);
                return ACQUIRE_MAX_IMAGES;
            } else {
                ALOGE("%s: Acquire image failed with some unknown error: %s (%d)",
                        __FUNCTION__, strerror(-res), res);
                jniThrowExceptionFmt(env, "java/lang/IllegalStateException",
                        "Unknown error (%d) when we tried to acquire an image.",
                                          res);
                return ACQUIRE_NO_BUFFERS;
            }
        }
        // This isn't really an error case, as the application may acquire buffer at any time.
        return ACQUIRE_NO_BUFFERS;
    }

    // Add some extra checks for non-opaque formats.
    if (!isFormatOpaque(ctx->getBufferFormat())) {
        // Check if the left-top corner of the crop rect is origin, we currently assume this point is
        // zero, will revisit this once this assumption turns out problematic.
        Point lt = buffer->mCrop.leftTop();
        if (lt.x != 0 || lt.y != 0) {
            jniThrowExceptionFmt(env, "java/lang/UnsupportedOperationException",
                    "crop left top corner [%d, %d] need to be at origin", lt.x, lt.y);
            return -1;
        }

        // Check if the producer buffer configurations match what ImageReader configured.
        int outputWidth = getBufferWidth(buffer);
        int outputHeight = getBufferHeight(buffer);

        int imgReaderFmt = ctx->getBufferFormat();
        int imageReaderWidth = ctx->getBufferWidth();
        int imageReaderHeight = ctx->getBufferHeight();
        int bufferFormat = buffer->mGraphicBuffer->getPixelFormat();
        if ((bufferFormat != HAL_PIXEL_FORMAT_BLOB) && (imgReaderFmt != HAL_PIXEL_FORMAT_BLOB) &&
                (imageReaderWidth != outputWidth || imageReaderHeight != outputHeight)) {
            ALOGV("%s: Producer buffer size: %dx%d, doesn't match ImageReader configured size: %dx%d",
                    __FUNCTION__, outputWidth, outputHeight, imageReaderWidth, imageReaderHeight);
        }
        if (imgReaderFmt != bufferFormat) {
            if (imgReaderFmt == HAL_PIXEL_FORMAT_YCbCr_420_888 &&
                    isPossiblyYUV(bufferFormat)) {
                // Treat formats that are compatible with flexible YUV
                // (HAL_PIXEL_FORMAT_YCbCr_420_888) as HAL_PIXEL_FORMAT_YCbCr_420_888.
                ALOGV("%s: Treat buffer format to 0x%x as HAL_PIXEL_FORMAT_YCbCr_420_888",
                        __FUNCTION__, bufferFormat);
            } else if (imgReaderFmt == HAL_PIXEL_FORMAT_BLOB &&
                    bufferFormat == HAL_PIXEL_FORMAT_RGBA_8888) {
                // Using HAL_PIXEL_FORMAT_RGBA_8888 Gralloc buffers containing JPEGs to get around
                // SW write limitations for (b/17379185).
                ALOGV("%s: Receiving JPEG in HAL_PIXEL_FORMAT_RGBA_8888 buffer.", __FUNCTION__);
            } else {
                // Return the buffer to the queue. No need to provide fence, as this buffer wasn't
                // used anywhere yet.
                bufferConsumer->releaseBuffer(*buffer);
                ctx->returnBufferItem(buffer);

                // Throw exception
                ALOGE("Producer output buffer format: 0x%x, ImageReader configured format: 0x%x",
                        bufferFormat, ctx->getBufferFormat());
                String8 msg;
                msg.appendFormat("The producer output buffer format 0x%x doesn't "
                        "match the ImageReader's configured buffer format 0x%x.",
                        bufferFormat, ctx->getBufferFormat());
                jniThrowException(env, "java/lang/UnsupportedOperationException",
                        msg.string());
                return -1;
            }
        }

    }

    // Set SurfaceImage instance member variables
    Image_setBufferItem(env, image, buffer);
    env->SetLongField(image, gSurfaceImageClassInfo.mTimestamp,
            static_cast<jlong>(buffer->mTimestamp));

    return ACQUIRE_SUCCESS;
}

static jint ImageReader_detachImage(JNIEnv* env, jobject thiz, jobject image) {
    ALOGV("%s:", __FUNCTION__);
    JNIImageReaderContext* ctx = ImageReader_getContext(env, thiz);
    if (ctx == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException", "ImageReader was already closed");
        return -1;
    }

    BufferItemConsumer* bufferConsumer = ctx->getBufferConsumer();
    BufferItem* buffer = Image_getBufferItem(env, image);
    if (!buffer) {
        ALOGE(
                "Image already released and can not be detached from ImageReader!!!");
        jniThrowException(env, "java/lang/IllegalStateException",
                "Image detach from ImageReader failed: buffer was already released");
        return -1;
    }

    status_t res = OK;
    Image_unlockIfLocked(env, image);
    res = bufferConsumer->detachBuffer(buffer->mSlot);
    if (res != OK) {
        ALOGE("Image detach failed: %s (%d)!!!", strerror(-res), res);
        jniThrowRuntimeException(env,
                "nativeDetachImage failed for image!!!");
        return res;
    }
    return OK;
}

static void ImageReader_discardFreeBuffers(JNIEnv* env, jobject thiz) {
    ALOGV("%s:", __FUNCTION__);
    JNIImageReaderContext* ctx = ImageReader_getContext(env, thiz);
    if (ctx == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException", "ImageReader was already closed");
        return;
    }

    BufferItemConsumer* bufferConsumer = ctx->getBufferConsumer();
    status_t res = bufferConsumer->discardFreeBuffers();
    if (res != OK) {
        ALOGE("Buffer discard failed: %s (%d)", strerror(-res), res);
        jniThrowRuntimeException(env,
                "nativeDicardFreebuffers failed");
    }
}

static jobject ImageReader_getSurface(JNIEnv* env, jobject thiz)
{
    ALOGV("%s: ", __FUNCTION__);

    IGraphicBufferProducer* gbp = ImageReader_getProducer(env, thiz);
    if (gbp == NULL) {
        jniThrowRuntimeException(env, "Buffer consumer is uninitialized");
        return NULL;
    }

    // Wrap the IGBP in a Java-language Surface.
    return android_view_Surface_createFromIGraphicBufferProducer(env, gbp);
}

static void Image_getLockedImage(JNIEnv* env, jobject thiz, LockedImage *image) {
    ALOGV("%s", __FUNCTION__);
    BufferItem* buffer = Image_getBufferItem(env, thiz);
    if (buffer == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
                "Image is not initialized");
        return;
    }

    status_t res = lockImageFromBuffer(buffer,
            GRALLOC_USAGE_SW_READ_OFTEN, buffer->mFence->dup(), image);
    if (res != OK) {
        jniThrowExceptionFmt(env, "java/lang/RuntimeException",
                "lock buffer failed for format 0x%x",
                buffer->mGraphicBuffer->getPixelFormat());
        return;
    }

    // Carry over some fields from BufferItem.
    image->crop        = buffer->mCrop;
    image->transform   = buffer->mTransform;
    image->scalingMode = buffer->mScalingMode;
    image->timestamp   = buffer->mTimestamp;
    image->dataSpace   = buffer->mDataSpace;
    image->frameNumber = buffer->mFrameNumber;

    ALOGV("%s: Successfully locked the image", __FUNCTION__);
    // crop, transform, scalingMode, timestamp, and frameNumber should be set by producer,
    // and we don't set them here.
}

static void Image_getLockedImageInfo(JNIEnv* env, LockedImage* buffer, int idx,
        int32_t writerFormat, uint8_t **base, uint32_t *size, int *pixelStride, int *rowStride) {
    ALOGV("%s", __FUNCTION__);

    status_t res = getLockedImageInfo(buffer, idx, writerFormat, base, size,
            pixelStride, rowStride);
    if (res != OK) {
        jniThrowExceptionFmt(env, "java/lang/UnsupportedOperationException",
                             "Pixel format: 0x%x is unsupported", buffer->flexFormat);
    }
}

static jobjectArray Image_createSurfacePlanes(JNIEnv* env, jobject thiz,
        int numPlanes, int readerFormat)
{
    ALOGV("%s: create SurfacePlane array with size %d", __FUNCTION__, numPlanes);
    int rowStride = 0;
    int pixelStride = 0;
    uint8_t *pData = NULL;
    uint32_t dataSize = 0;
    jobject byteBuffer = NULL;

    PublicFormat publicReaderFormat = static_cast<PublicFormat>(readerFormat);
    int halReaderFormat = android_view_Surface_mapPublicFormatToHalFormat(
        publicReaderFormat);

    if (isFormatOpaque(halReaderFormat) && numPlanes > 0) {
        String8 msg;
        msg.appendFormat("Format 0x%x is opaque, thus not writable, the number of planes (%d)"
                " must be 0", halReaderFormat, numPlanes);
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
    if (isFormatOpaque(halReaderFormat)) {
        // Return 0 element surface array.
        return surfacePlanes;
    }

    LockedImage lockedImg = LockedImage();
    Image_getLockedImage(env, thiz, &lockedImg);
    // Create all SurfacePlanes
    for (int i = 0; i < numPlanes; i++) {
        Image_getLockedImageInfo(env, &lockedImg, i, halReaderFormat,
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

static jint Image_getWidth(JNIEnv* env, jobject thiz)
{
    BufferItem* buffer = Image_getBufferItem(env, thiz);
    return getBufferWidth(buffer);
}

static jint Image_getHeight(JNIEnv* env, jobject thiz)
{
    BufferItem* buffer = Image_getBufferItem(env, thiz);
    return getBufferHeight(buffer);
}

static jint Image_getFormat(JNIEnv* env, jobject thiz, jint readerFormat)
{
    if (isFormatOpaque(readerFormat)) {
        // Assuming opaque reader produce opaque images.
        return static_cast<jint>(PublicFormat::PRIVATE);
    } else {
        BufferItem* buffer = Image_getBufferItem(env, thiz);
        int readerHalFormat = android_view_Surface_mapPublicFormatToHalFormat(
                static_cast<PublicFormat>(readerFormat));
        int32_t fmt = applyFormatOverrides(
                buffer->mGraphicBuffer->getPixelFormat(), readerHalFormat);
        // Override the image format to HAL_PIXEL_FORMAT_YCbCr_420_888 if the actual format is
        // NV21 or YV12. This could only happen when the Gralloc HAL version is v0.1 thus doesn't
        // support lockycbcr(), the CpuConsumer need to use the lock() method in the
        // lockNextBuffer() call. For Gralloc HAL v0.2 or newer, this format should already be
        // overridden to HAL_PIXEL_FORMAT_YCbCr_420_888 for the flexible YUV compatible formats.
        if (isPossiblyYUV(fmt)) {
            fmt = HAL_PIXEL_FORMAT_YCbCr_420_888;
        }
        PublicFormat publicFmt = android_view_Surface_mapHalFormatDataspaceToPublicFormat(
                fmt, buffer->mDataSpace);
        return static_cast<jint>(publicFmt);
    }
}

} // extern "C"

// ----------------------------------------------------------------------------

static const JNINativeMethod gImageReaderMethods[] = {
    {"nativeClassInit",        "()V",                        (void*)ImageReader_classInit },
    {"nativeInit",             "(Ljava/lang/Object;IIII)V",  (void*)ImageReader_init },
    {"nativeClose",            "()V",                        (void*)ImageReader_close },
    {"nativeReleaseImage",     "(Landroid/media/Image;)V",   (void*)ImageReader_imageRelease },
    {"nativeImageSetup",       "(Landroid/media/Image;)I",   (void*)ImageReader_imageSetup },
    {"nativeGetSurface",       "()Landroid/view/Surface;",   (void*)ImageReader_getSurface },
    {"nativeDetachImage",      "(Landroid/media/Image;)I",   (void*)ImageReader_detachImage },
    {"nativeDiscardFreeBuffers", "()V",                      (void*)ImageReader_discardFreeBuffers }
};

static const JNINativeMethod gImageMethods[] = {
    {"nativeCreatePlanes",      "(II)[Landroid/media/ImageReader$SurfaceImage$SurfacePlane;",
                                                              (void*)Image_createSurfacePlanes },
    {"nativeGetWidth",         "()I",                        (void*)Image_getWidth },
    {"nativeGetHeight",        "()I",                        (void*)Image_getHeight },
    {"nativeGetFormat",        "(I)I",                        (void*)Image_getFormat },
};

int register_android_media_ImageReader(JNIEnv *env) {

    int ret1 = AndroidRuntime::registerNativeMethods(env,
                   "android/media/ImageReader", gImageReaderMethods, NELEM(gImageReaderMethods));

    int ret2 = AndroidRuntime::registerNativeMethods(env,
                   "android/media/ImageReader$SurfaceImage", gImageMethods, NELEM(gImageMethods));

    return (ret1 || ret2);
}
