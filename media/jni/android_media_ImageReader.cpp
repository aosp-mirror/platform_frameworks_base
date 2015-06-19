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
#include <utils/Log.h>
#include <utils/misc.h>
#include <utils/List.h>
#include <utils/String8.h>

#include <cstdio>

#include <gui/CpuConsumer.h>
#include <gui/BufferItemConsumer.h>
#include <gui/Surface.h>
#include <camera3.h>

#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/android_view_Surface.h>

#include <jni.h>
#include <JNIHelp.h>

#include <stdint.h>
#include <inttypes.h>

#define ALIGN(x, mask) ( ((x) + (mask) - 1) & ~((mask) - 1) )

#define ANDROID_MEDIA_IMAGEREADER_CTX_JNI_ID       "mNativeContext"
#define ANDROID_MEDIA_SURFACEIMAGE_BUFFER_JNI_ID   "mNativeBuffer"
#define ANDROID_MEDIA_SURFACEIMAGE_TS_JNI_ID       "mTimestamp"

// ----------------------------------------------------------------------------

using namespace android;

enum {
    IMAGE_READER_MAX_NUM_PLANES = 3,
};

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

    CpuConsumer::LockedBuffer* getLockedBuffer();
    void returnLockedBuffer(CpuConsumer::LockedBuffer* buffer);

    BufferItem* getOpaqueBuffer();
    void returnOpaqueBuffer(BufferItem* buffer);

    void setCpuConsumer(const sp<CpuConsumer>& consumer) { mConsumer = consumer; }
    CpuConsumer* getCpuConsumer() { return mConsumer.get(); }

    void setOpaqueConsumer(const sp<BufferItemConsumer>& consumer) { mOpaqueConsumer = consumer; }
    BufferItemConsumer* getOpaqueConsumer() { return mOpaqueConsumer.get(); }
    // This is the only opaque format exposed in the ImageFormat public API.
    bool isOpaque() { return mFormat == HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED; }

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

    List<CpuConsumer::LockedBuffer*> mBuffers;
    List<BufferItem*> mOpaqueBuffers;
    sp<CpuConsumer> mConsumer;
    sp<BufferItemConsumer> mOpaqueConsumer;
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
    mClazz((jclass)env->NewGlobalRef(clazz)) {
    for (int i = 0; i < maxImages; i++) {
        CpuConsumer::LockedBuffer *buffer = new CpuConsumer::LockedBuffer;
        BufferItem* opaqueBuffer = new BufferItem;
        mBuffers.push_back(buffer);
        mOpaqueBuffers.push_back(opaqueBuffer);
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

CpuConsumer::LockedBuffer* JNIImageReaderContext::getLockedBuffer() {
    if (mBuffers.empty()) {
        return NULL;
    }
    // Return a LockedBuffer pointer and remove it from the list
    List<CpuConsumer::LockedBuffer*>::iterator it = mBuffers.begin();
    CpuConsumer::LockedBuffer* buffer = *it;
    mBuffers.erase(it);
    return buffer;
}

void JNIImageReaderContext::returnLockedBuffer(CpuConsumer::LockedBuffer* buffer) {
    mBuffers.push_back(buffer);
}

BufferItem* JNIImageReaderContext::getOpaqueBuffer() {
    if (mOpaqueBuffers.empty()) {
        return NULL;
    }
    // Return an opaque buffer pointer and remove it from the list
    List<BufferItem*>::iterator it = mOpaqueBuffers.begin();
    BufferItem* buffer = *it;
    mOpaqueBuffers.erase(it);
    return buffer;
}

void JNIImageReaderContext::returnOpaqueBuffer(BufferItem* buffer) {
    mOpaqueBuffers.push_back(buffer);
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

    // Delete LockedBuffers
    for (List<CpuConsumer::LockedBuffer *>::iterator it = mBuffers.begin();
            it != mBuffers.end(); it++) {
        delete *it;
    }

    // Delete opaque buffers
    for (List<BufferItem *>::iterator it = mOpaqueBuffers.begin();
            it != mOpaqueBuffers.end(); it++) {
        delete *it;
    }

    mBuffers.clear();
    if (mConsumer != 0) {
        mConsumer.clear();
    }
    if (mOpaqueConsumer != 0) {
        mOpaqueConsumer.clear();
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

static bool isFormatOpaque(int format) {
    // Only treat IMPLEMENTATION_DEFINED as an opaque format for now.
    return format == HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED;
}

static JNIImageReaderContext* ImageReader_getContext(JNIEnv* env, jobject thiz)
{
    JNIImageReaderContext *ctx;
    ctx = reinterpret_cast<JNIImageReaderContext *>
              (env->GetLongField(thiz, gImageReaderClassInfo.mNativeContext));
    return ctx;
}

static CpuConsumer* ImageReader_getCpuConsumer(JNIEnv* env, jobject thiz)
{
    ALOGV("%s:", __FUNCTION__);
    JNIImageReaderContext* const ctx = ImageReader_getContext(env, thiz);
    if (ctx == NULL) {
        jniThrowRuntimeException(env, "ImageReaderContext is not initialized");
        return NULL;
    }

    if (ctx->isOpaque()) {
        jniThrowException(env, "java/lang/IllegalStateException",
                "Opaque ImageReader doesn't support this method");
        return NULL;
    }

    return ctx->getCpuConsumer();
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

static CpuConsumer::LockedBuffer* Image_getLockedBuffer(JNIEnv* env, jobject image)
{
    return reinterpret_cast<CpuConsumer::LockedBuffer*>(
            env->GetLongField(image, gSurfaceImageClassInfo.mNativeBuffer));
}

static void Image_setBuffer(JNIEnv* env, jobject thiz,
        const CpuConsumer::LockedBuffer* buffer)
{
    env->SetLongField(thiz, gSurfaceImageClassInfo.mNativeBuffer, reinterpret_cast<jlong>(buffer));
}

static void Image_setOpaqueBuffer(JNIEnv* env, jobject thiz,
        const BufferItem* buffer)
{
    env->SetLongField(thiz, gSurfaceImageClassInfo.mNativeBuffer, reinterpret_cast<jlong>(buffer));
}

static uint32_t Image_getJpegSize(CpuConsumer::LockedBuffer* buffer, bool usingRGBAOverride)
{
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

static bool usingRGBAToJpegOverride(int32_t bufferFormat, int32_t readerCtxFormat) {
    return readerCtxFormat == HAL_PIXEL_FORMAT_BLOB && bufferFormat == HAL_PIXEL_FORMAT_RGBA_8888;
}

static int32_t applyFormatOverrides(int32_t bufferFormat, int32_t readerCtxFormat)
{
    // Using HAL_PIXEL_FORMAT_RGBA_8888 gralloc buffers containing JPEGs to get around SW
    // write limitations for some platforms (b/17379185).
    if (usingRGBAToJpegOverride(bufferFormat, readerCtxFormat)) {
        return HAL_PIXEL_FORMAT_BLOB;
    }
    return bufferFormat;
}

static void Image_getLockedBufferInfo(JNIEnv* env, CpuConsumer::LockedBuffer* buffer, int idx,
                                uint8_t **base, uint32_t *size, int32_t readerFormat)
{
    ALOG_ASSERT(buffer != NULL, "Input buffer is NULL!!!");
    ALOG_ASSERT(base != NULL, "base is NULL!!!");
    ALOG_ASSERT(size != NULL, "size is NULL!!!");
    ALOG_ASSERT((idx < IMAGE_READER_MAX_NUM_PLANES) && (idx >= 0));

    ALOGV("%s: buffer: %p", __FUNCTION__, buffer);

    uint32_t dataSize, ySize, cSize, cStride;
    uint8_t *cb, *cr;
    uint8_t *pData = NULL;
    int bytesPerPixel = 0;

    dataSize = ySize = cSize = cStride = 0;
    int32_t fmt = buffer->flexFormat;

    bool usingRGBAOverride = usingRGBAToJpegOverride(fmt, readerFormat);
    fmt = applyFormatOverrides(fmt, readerFormat);
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
                dataSize = buffer->stride * (buffer->height - 1) + buffer->width;
            } else {
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
            break;
        case HAL_PIXEL_FORMAT_Y8:
            // Single plane, 8bpp.
            ALOG_ASSERT(idx == 0, "Wrong index: %d", idx);

            pData = buffer->data;
            dataSize = buffer->stride * buffer->height;
            break;
        case HAL_PIXEL_FORMAT_Y16:
            bytesPerPixel = 2;
            // Single plane, 16bpp, strides are specified in pixels, not in bytes
            ALOG_ASSERT(idx == 0, "Wrong index: %d", idx);

            pData = buffer->data;
            dataSize = buffer->stride * buffer->height * bytesPerPixel;
            break;
        case HAL_PIXEL_FORMAT_BLOB:
            // Used for JPEG data, height must be 1, width == size, single plane.
            ALOG_ASSERT(idx == 0, "Wrong index: %d", idx);
            ALOG_ASSERT(buffer->height == 1, "JPEG should has height value %d", buffer->height);

            pData = buffer->data;
            dataSize = Image_getJpegSize(buffer, usingRGBAOverride);
            break;
        case HAL_PIXEL_FORMAT_RAW16:
            // Single plane 16bpp bayer data.
            bytesPerPixel = 2;
            ALOG_ASSERT(idx == 0, "Wrong index: %d", idx);
            pData = buffer->data;
            dataSize = buffer->stride * buffer->height * bytesPerPixel;
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
            break;
        case HAL_PIXEL_FORMAT_RAW12:
            // Single plane 10bpp bayer data.
            ALOG_ASSERT(idx == 0, "Wrong index: %d", idx);
            LOG_ALWAYS_FATAL_IF(buffer->width % 4,
                                "Width is not multiple of 4 %d", buffer->width);
            LOG_ALWAYS_FATAL_IF(buffer->height % 2,
                                "Height is not even %d", buffer->height);
            LOG_ALWAYS_FATAL_IF(buffer->stride < (buffer->width * 12 / 8),
                                "stride (%d) should be at least %d",
                                buffer->stride, buffer->width * 12 / 8);
            pData = buffer->data;
            dataSize = buffer->stride * buffer->height;
            break;
        case HAL_PIXEL_FORMAT_RGBA_8888:
        case HAL_PIXEL_FORMAT_RGBX_8888:
            // Single plane, 32bpp.
            bytesPerPixel = 4;
            ALOG_ASSERT(idx == 0, "Wrong index: %d", idx);
            pData = buffer->data;
            dataSize = buffer->stride * buffer->height * bytesPerPixel;
            break;
        case HAL_PIXEL_FORMAT_RGB_565:
            // Single plane, 16bpp.
            bytesPerPixel = 2;
            ALOG_ASSERT(idx == 0, "Wrong index: %d", idx);
            pData = buffer->data;
            dataSize = buffer->stride * buffer->height * bytesPerPixel;
            break;
        case HAL_PIXEL_FORMAT_RGB_888:
            // Single plane, 24bpp.
            bytesPerPixel = 3;
            ALOG_ASSERT(idx == 0, "Wrong index: %d", idx);
            pData = buffer->data;
            dataSize = buffer->stride * buffer->height * bytesPerPixel;
            break;
        default:
            jniThrowExceptionFmt(env, "java/lang/UnsupportedOperationException",
                                 "Pixel format: 0x%x is unsupported", fmt);
            break;
    }

    *base = pData;
    *size = dataSize;
}

static jint Image_imageGetPixelStride(JNIEnv* env, CpuConsumer::LockedBuffer* buffer, int idx,
        int32_t halReaderFormat)
{
    ALOGV("%s: buffer index: %d", __FUNCTION__, idx);
    ALOG_ASSERT((idx < IMAGE_READER_MAX_NUM_PLANES) && (idx >= 0), "Index is out of range:%d", idx);

    int pixelStride = 0;
    ALOG_ASSERT(buffer != NULL, "buffer is NULL");

    int32_t fmt = buffer->flexFormat;

    fmt = applyFormatOverrides(fmt, halReaderFormat);

    switch (fmt) {
        case HAL_PIXEL_FORMAT_YCbCr_420_888:
            pixelStride = (idx == 0) ? 1 : buffer->chromaStep;
            break;
        case HAL_PIXEL_FORMAT_YCrCb_420_SP:
            pixelStride = (idx == 0) ? 1 : 2;
            break;
        case HAL_PIXEL_FORMAT_Y8:
            // Single plane 8bpp data.
            ALOG_ASSERT(idx == 0, "Wrong index: %d", idx);
            break;
        case HAL_PIXEL_FORMAT_YV12:
            pixelStride = 1;
            break;
        case HAL_PIXEL_FORMAT_BLOB:
        case HAL_PIXEL_FORMAT_RAW10:
        case HAL_PIXEL_FORMAT_RAW12:
            // Blob is used for JPEG data, RAW10 and RAW12 is used for 10-bit and 12-bit raw data,
            // those are single plane data with pixel stride 0 since they don't really have a
            // well defined pixel stride
            ALOG_ASSERT(idx == 0, "Wrong index: %d", idx);
            pixelStride = 0;
            break;
        case HAL_PIXEL_FORMAT_Y16:
        case HAL_PIXEL_FORMAT_RAW16:
        case HAL_PIXEL_FORMAT_RGB_565:
            // Single plane 16bpp data.
            ALOG_ASSERT(idx == 0, "Wrong index: %d", idx);
            pixelStride = 2;
            break;
        case HAL_PIXEL_FORMAT_RGBA_8888:
        case HAL_PIXEL_FORMAT_RGBX_8888:
            ALOG_ASSERT(idx == 0, "Wrong index: %d", idx);
            pixelStride = 4;
            break;
        case HAL_PIXEL_FORMAT_RGB_888:
            // Single plane, 24bpp.
            ALOG_ASSERT(idx == 0, "Wrong index: %d", idx);
            pixelStride = 3;
            break;
        default:
            jniThrowExceptionFmt(env, "java/lang/UnsupportedOperationException",
                                 "Pixel format: 0x%x is unsupported", fmt);
            break;
    }

    return pixelStride;
}

static jint Image_imageGetRowStride(JNIEnv* env, CpuConsumer::LockedBuffer* buffer, int idx,
        int32_t halReaderFormat)
{
    ALOGV("%s: buffer index: %d", __FUNCTION__, idx);
    ALOG_ASSERT((idx < IMAGE_READER_MAX_NUM_PLANES) && (idx >= 0));

    int rowStride = 0;
    ALOG_ASSERT(buffer != NULL, "buffer is NULL");

    int32_t fmt = buffer->flexFormat;

    fmt = applyFormatOverrides(fmt, halReaderFormat);

    switch (fmt) {
        case HAL_PIXEL_FORMAT_YCbCr_420_888:
            rowStride = (idx == 0) ? buffer->stride : buffer->chromaStride;
            break;
        case HAL_PIXEL_FORMAT_YCrCb_420_SP:
            rowStride = buffer->width;
            break;
        case HAL_PIXEL_FORMAT_YV12:
            LOG_ALWAYS_FATAL_IF(buffer->stride % 16,
                                "Stride is not 16 pixel aligned %d", buffer->stride);
            rowStride = (idx == 0) ? buffer->stride : ALIGN(buffer->stride / 2, 16);
            break;
        case HAL_PIXEL_FORMAT_BLOB:
            // Blob is used for JPEG data. It is single plane and has 0 row stride and
            // 0 pixel stride
            ALOG_ASSERT(idx == 0, "Wrong index: %d", idx);
            rowStride = 0;
            break;
        case HAL_PIXEL_FORMAT_RAW10:
        case HAL_PIXEL_FORMAT_RAW12:
            // RAW10 and RAW12 are used for 10-bit and 12-bit raw data, they are single plane
            ALOG_ASSERT(idx == 0, "Wrong index: %d", idx);
            rowStride = buffer->stride;
            break;
        case HAL_PIXEL_FORMAT_Y8:
            ALOG_ASSERT(idx == 0, "Wrong index: %d", idx);
            LOG_ALWAYS_FATAL_IF(buffer->stride % 16,
                                "Stride is not 16 pixel aligned %d", buffer->stride);
            rowStride = buffer->stride;
            break;
        case HAL_PIXEL_FORMAT_Y16:
        case HAL_PIXEL_FORMAT_RAW16:
            // In native side, strides are specified in pixels, not in bytes.
            // Single plane 16bpp bayer data. even width/height,
            // row stride multiple of 16 pixels (32 bytes)
            ALOG_ASSERT(idx == 0, "Wrong index: %d", idx);
            LOG_ALWAYS_FATAL_IF(buffer->stride % 16,
                                "Stride is not 16 pixel aligned %d", buffer->stride);
            rowStride = buffer->stride * 2;
            break;
        case HAL_PIXEL_FORMAT_RGB_565:
            ALOG_ASSERT(idx == 0, "Wrong index: %d", idx);
            rowStride = buffer->stride * 2;
            break;
        case HAL_PIXEL_FORMAT_RGBA_8888:
        case HAL_PIXEL_FORMAT_RGBX_8888:
            ALOG_ASSERT(idx == 0, "Wrong index: %d", idx);
            rowStride = buffer->stride * 4;
            break;
        case HAL_PIXEL_FORMAT_RGB_888:
            // Single plane, 24bpp.
            ALOG_ASSERT(idx == 0, "Wrong index: %d", idx);
            rowStride = buffer->stride * 3;
            break;
        default:
            ALOGE("%s Pixel format: 0x%x is unsupported", __FUNCTION__, fmt);
            jniThrowException(env, "java/lang/UnsupportedOperationException",
                              "unsupported buffer format");
          break;
    }

    return rowStride;
}

static int Image_getBufferWidth(CpuConsumer::LockedBuffer* buffer) {
    if (buffer == NULL) return -1;

    if (!buffer->crop.isEmpty()) {
        return buffer->crop.getWidth();
    }
    return buffer->width;
}

static int Image_getBufferHeight(CpuConsumer::LockedBuffer* buffer) {
    if (buffer == NULL) return -1;

    if (!buffer->crop.isEmpty()) {
        return buffer->crop.getHeight();
    }
    return buffer->height;
}

// --------------------------Methods for opaque Image and ImageReader----------

static BufferItemConsumer* ImageReader_getOpaqueConsumer(JNIEnv* env, jobject thiz)
{
    ALOGV("%s:", __FUNCTION__);
    JNIImageReaderContext* const ctx = ImageReader_getContext(env, thiz);
    if (ctx == NULL) {
        jniThrowRuntimeException(env, "ImageReaderContext is not initialized");
        return NULL;
    }

    if (!ctx->isOpaque()) {
        jniThrowException(env, "java/lang/IllegalStateException",
                "Non-opaque ImageReader doesn't support this method");
    }

    return ctx->getOpaqueConsumer();
}

static BufferItem* Image_getOpaqueBuffer(JNIEnv* env, jobject image)
{
    return reinterpret_cast<BufferItem*>(
            env->GetLongField(image, gSurfaceImageClassInfo.mNativeBuffer));
}

static int Image_getOpaqueBufferWidth(BufferItem* buffer) {
    if (buffer == NULL) return -1;

    if (!buffer->mCrop.isEmpty()) {
        return buffer->mCrop.getWidth();
    }
    return buffer->mGraphicBuffer->getWidth();
}

static int Image_getOpaqueBufferHeight(BufferItem* buffer) {
    if (buffer == NULL) return -1;

    if (!buffer->mCrop.isEmpty()) {
        return buffer->mCrop.getHeight();
    }

    return buffer->mGraphicBuffer->getHeight();
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
            "(Landroid/media/ImageReader$SurfaceImage;III)V");
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
    sp<ConsumerBase> consumer;
    sp<CpuConsumer> cpuConsumer;
    sp<BufferItemConsumer> opaqueConsumer;
    String8 consumerName = String8::format("ImageReader-%dx%df%xm%d-%d-%d",
            width, height, format, maxImages, getpid(),
            createProcessUniqueId());
    if (isFormatOpaque(nativeFormat)) {
        // Use the SW_READ_NEVER usage to tell producer that this format is not for preview or video
        // encoding. The only possibility will be ZSL output.
        opaqueConsumer =
                new BufferItemConsumer(gbConsumer, GRALLOC_USAGE_SW_READ_NEVER, maxImages,
                        /*controlledByApp*/true);
        if (opaqueConsumer == NULL) {
            jniThrowRuntimeException(env, "Failed to allocate native opaque consumer");
            return;
        }
        ctx->setOpaqueConsumer(opaqueConsumer);
        opaqueConsumer->setName(consumerName);
        consumer = opaqueConsumer;
    } else {
        cpuConsumer = new CpuConsumer(gbConsumer, maxImages, /*controlledByApp*/true);
        // TODO: throw dvm exOutOfMemoryError?
        if (cpuConsumer == NULL) {
            jniThrowRuntimeException(env, "Failed to allocate native CpuConsumer");
            return;
        }
        ctx->setCpuConsumer(cpuConsumer);
        cpuConsumer->setName(consumerName);
        consumer = cpuConsumer;
    }

    ctx->setProducer(gbProducer);
    consumer->setFrameAvailableListener(ctx);
    ImageReader_setNativeContext(env, thiz, ctx);
    ctx->setBufferFormat(nativeFormat);
    ctx->setBufferDataspace(nativeDataspace);
    ctx->setBufferWidth(width);
    ctx->setBufferHeight(height);

    // Set the width/height/format/dataspace to the CpuConsumer
    // TODO: below code can be simplified once b/19977701 is fixed.
    if (isFormatOpaque(nativeFormat)) {
        res = opaqueConsumer->setDefaultBufferSize(width, height);
        if (res != OK) {
            jniThrowException(env, "java/lang/IllegalStateException",
                              "Failed to set opaque consumer buffer size");
            return;
        }
        res = opaqueConsumer->setDefaultBufferFormat(nativeFormat);
        if (res != OK) {
            jniThrowException(env, "java/lang/IllegalStateException",
                              "Failed to set opaque consumer buffer format");
        }
        res = opaqueConsumer->setDefaultBufferDataSpace(nativeDataspace);
        if (res != OK) {
            jniThrowException(env, "java/lang/IllegalStateException",
                              "Failed to set opaque consumer buffer dataSpace");
        }
    } else {
        res = cpuConsumer->setDefaultBufferSize(width, height);
        if (res != OK) {
            jniThrowException(env, "java/lang/IllegalStateException",
                              "Failed to set CpuConsumer buffer size");
            return;
        }
        res = cpuConsumer->setDefaultBufferFormat(nativeFormat);
        if (res != OK) {
            jniThrowException(env, "java/lang/IllegalStateException",
                              "Failed to set CpuConsumer buffer format");
        }
        res = cpuConsumer->setDefaultBufferDataSpace(nativeDataspace);
        if (res != OK) {
            jniThrowException(env, "java/lang/IllegalStateException",
                              "Failed to set CpuConsumer buffer dataSpace");
        }
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

    ConsumerBase* consumer = NULL;
    if (ctx->isOpaque()) {
        consumer = ImageReader_getOpaqueConsumer(env, thiz);
    } else {
        consumer = ImageReader_getCpuConsumer(env, thiz);
    }

    if (consumer != NULL) {
        consumer->abandon();
        consumer->setFrameAvailableListener(NULL);
    }
    ImageReader_setNativeContext(env, thiz, NULL);
}

static void ImageReader_imageRelease(JNIEnv* env, jobject thiz, jobject image)
{
    ALOGV("%s:", __FUNCTION__);
    JNIImageReaderContext* ctx = ImageReader_getContext(env, thiz);
    if (ctx == NULL) {
        ALOGW("ImageReader#close called before Image#close, consider calling Image#close first");
        return;
    }

    if (ctx->isOpaque()) {
        BufferItemConsumer* opaqueConsumer = ctx->getOpaqueConsumer();
        BufferItem* opaqueBuffer = Image_getOpaqueBuffer(env, image);
        opaqueConsumer->releaseBuffer(*opaqueBuffer); // Not using fence for now.
        Image_setOpaqueBuffer(env, image, NULL);
        ctx->returnOpaqueBuffer(opaqueBuffer);
        ALOGV("%s: Opaque Image has been released", __FUNCTION__);
    } else {
        CpuConsumer* consumer = ctx->getCpuConsumer();
        CpuConsumer::LockedBuffer* buffer = Image_getLockedBuffer(env, image);
        if (!buffer) {
            ALOGW("Image already released!!!");
            return;
        }
        consumer->unlockBuffer(*buffer);
        Image_setBuffer(env, image, NULL);
        ctx->returnLockedBuffer(buffer);
        ALOGV("%s: Image (format: 0x%x) has been released", __FUNCTION__, ctx->getBufferFormat());
    }
}

static jint ImageReader_opaqueImageSetup(JNIEnv* env, JNIImageReaderContext* ctx, jobject image) {
    ALOGV("%s:", __FUNCTION__);
    if (ctx == NULL || !ctx->isOpaque()) {
        jniThrowRuntimeException(env, "ImageReaderContext is not initialized");
        return -1;
    }

    BufferItemConsumer* opaqueConsumer = ctx->getOpaqueConsumer();
    BufferItem* buffer = ctx->getOpaqueBuffer();
    if (buffer == NULL) {
        ALOGW("Unable to acquire a buffer item, very likely client tried to acquire more than"
            " maxImages buffers");
        return ACQUIRE_MAX_IMAGES;
    }

    status_t res = opaqueConsumer->acquireBuffer(buffer, 0);
    if (res != OK) {
        ctx->returnOpaqueBuffer(buffer);
        if (res == INVALID_OPERATION) {
            // Max number of images were already acquired.
            ALOGE("%s: Max number of buffers allowed are already acquired : %s (%d)",
                    __FUNCTION__, strerror(-res), res);
            return ACQUIRE_MAX_IMAGES;
        } else {
            ALOGE("%s: Acquire image failed with error: %s (%d)",
                    __FUNCTION__, strerror(-res), res);
            return ACQUIRE_NO_BUFFERS;
        }
    }

    // Set SurfaceImage instance member variables
    Image_setOpaqueBuffer(env, image, buffer);
    env->SetLongField(image, gSurfaceImageClassInfo.mTimestamp,
            static_cast<jlong>(buffer->mTimestamp));

    return ACQUIRE_SUCCESS;
}

static jint ImageReader_lockedImageSetup(JNIEnv* env, JNIImageReaderContext* ctx, jobject image) {
    CpuConsumer* consumer = ctx->getCpuConsumer();
    CpuConsumer::LockedBuffer* buffer = ctx->getLockedBuffer();
    if (buffer == NULL) {
        ALOGW("Unable to acquire a lockedBuffer, very likely client tries to lock more than"
            " maxImages buffers");
        return ACQUIRE_MAX_IMAGES;
    }
    status_t res = consumer->lockNextBuffer(buffer);
    if (res != NO_ERROR) {
        ctx->returnLockedBuffer(buffer);
        if (res != BAD_VALUE /*no buffers*/) {
            if (res == NOT_ENOUGH_DATA) {
                return ACQUIRE_MAX_IMAGES;
            } else {
                ALOGE("%s Fail to lockNextBuffer with error: %d ",
                      __FUNCTION__, res);
                jniThrowExceptionFmt(env, "java/lang/AssertionError",
                          "Unknown error (%d) when we tried to lock buffer.",
                          res);
            }
        }
        return ACQUIRE_NO_BUFFERS;
    }

    if (buffer->flexFormat == HAL_PIXEL_FORMAT_YCrCb_420_SP) {
        jniThrowException(env, "java/lang/UnsupportedOperationException",
                "NV21 format is not supported by ImageReader");
        return -1;
    }

    // Check if the left-top corner of the crop rect is origin, we currently assume this point is
    // zero, will revist this once this assumption turns out problematic.
    Point lt = buffer->crop.leftTop();
    if (lt.x != 0 || lt.y != 0) {
        jniThrowExceptionFmt(env, "java/lang/UnsupportedOperationException",
                "crop left top corner [%d, %d] need to be at origin", lt.x, lt.y);
        return -1;
    }

    // Check if the producer buffer configurations match what ImageReader configured.
    int outputWidth = Image_getBufferWidth(buffer);
    int outputHeight = Image_getBufferHeight(buffer);

    int imgReaderFmt = ctx->getBufferFormat();
    int imageReaderWidth = ctx->getBufferWidth();
    int imageReaderHeight = ctx->getBufferHeight();
    if ((buffer->format != HAL_PIXEL_FORMAT_BLOB) && (imgReaderFmt != HAL_PIXEL_FORMAT_BLOB) &&
            (imageReaderWidth != outputWidth || imageReaderHeight != outputHeight)) {
        ALOGV("%s: Producer buffer size: %dx%d, doesn't match ImageReader configured size: %dx%d",
                __FUNCTION__, outputWidth, outputHeight, imageReaderWidth, imageReaderHeight);
    }

    int bufFmt = buffer->format;
    if (imgReaderFmt == HAL_PIXEL_FORMAT_YCbCr_420_888) {
        bufFmt = buffer->flexFormat;
    }
    if (imgReaderFmt != bufFmt) {
        if (imgReaderFmt == HAL_PIXEL_FORMAT_YCbCr_420_888 && (bufFmt ==
                HAL_PIXEL_FORMAT_YCrCb_420_SP || bufFmt == HAL_PIXEL_FORMAT_YV12)) {
            // Special casing for when producer switches to a format compatible with flexible YUV
            // (HAL_PIXEL_FORMAT_YCbCr_420_888).
            ctx->setBufferFormat(bufFmt);
            ALOGD("%s: Overriding buffer format YUV_420_888 to %x.", __FUNCTION__, bufFmt);
        } else if (imgReaderFmt == HAL_PIXEL_FORMAT_BLOB && bufFmt == HAL_PIXEL_FORMAT_RGBA_8888) {
            // Using HAL_PIXEL_FORMAT_RGBA_8888 gralloc buffers containing JPEGs to get around SW
            // write limitations for (b/17379185).
            ALOGD("%s: Receiving JPEG in HAL_PIXEL_FORMAT_RGBA_8888 buffer.", __FUNCTION__);
        } else {
            // Return the buffer to the queue.
            consumer->unlockBuffer(*buffer);
            ctx->returnLockedBuffer(buffer);

            // Throw exception
            ALOGE("Producer output buffer format: 0x%x, ImageReader configured format: 0x%x",
                    buffer->format, ctx->getBufferFormat());
            String8 msg;
            msg.appendFormat("The producer output buffer format 0x%x doesn't "
                    "match the ImageReader's configured buffer format 0x%x.",
                    bufFmt, ctx->getBufferFormat());
            jniThrowException(env, "java/lang/UnsupportedOperationException",
                    msg.string());
            return -1;
        }
    }
    // Set SurfaceImage instance member variables
    Image_setBuffer(env, image, buffer);
    env->SetLongField(image, gSurfaceImageClassInfo.mTimestamp,
            static_cast<jlong>(buffer->timestamp));

    return ACQUIRE_SUCCESS;
}

static jint ImageReader_imageSetup(JNIEnv* env, jobject thiz, jobject image) {
    ALOGV("%s:", __FUNCTION__);
    JNIImageReaderContext* ctx = ImageReader_getContext(env, thiz);
    if (ctx == NULL) {
        jniThrowRuntimeException(env, "ImageReaderContext is not initialized");
        return -1;
    }

    if (ctx->isOpaque()) {
        return ImageReader_opaqueImageSetup(env, ctx, image);
    } else {
        return ImageReader_lockedImageSetup(env, ctx, image);
    }
}

static jint ImageReader_detachImage(JNIEnv* env, jobject thiz, jobject image) {
    ALOGV("%s:", __FUNCTION__);
    JNIImageReaderContext* ctx = ImageReader_getContext(env, thiz);
    if (ctx == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException", "ImageReader was already closed");
        return -1;
    }

    status_t res = OK;
    if (!ctx->isOpaque()) {
        // TODO: Non-Opaque format detach is not implemented yet.
        jniThrowRuntimeException(env,
                "nativeDetachImage is not implemented yet for non-opaque format !!!");
        return -1;
    }

    BufferItemConsumer* opaqueConsumer = ctx->getOpaqueConsumer();
    BufferItem* opaqueBuffer = Image_getOpaqueBuffer(env, image);
    if (!opaqueBuffer) {
        ALOGE(
                "Opaque Image already released and can not be detached from ImageReader!!!");
        jniThrowException(env, "java/lang/IllegalStateException",
                "Opaque Image detach from ImageReader failed: buffer was already released");
        return -1;
    }

    res = opaqueConsumer->detachBuffer(opaqueBuffer->mSlot);
    if (res != OK) {
        ALOGE("Opaque Image detach failed: %s (%d)!!!", strerror(-res), res);
        jniThrowRuntimeException(env,
                "nativeDetachImage failed for opaque image!!!");
        return res;
    }
    return OK;
}

static jobject ImageReader_getSurface(JNIEnv* env, jobject thiz)
{
    ALOGV("%s: ", __FUNCTION__);

    IGraphicBufferProducer* gbp = ImageReader_getProducer(env, thiz);
    if (gbp == NULL) {
        jniThrowRuntimeException(env, "CpuConsumer is uninitialized");
        return NULL;
    }

    // Wrap the IGBP in a Java-language Surface.
    return android_view_Surface_createFromIGraphicBufferProducer(env, gbp);
}

static jobject Image_createSurfacePlane(JNIEnv* env, jobject thiz, int idx, int readerFormat)
{
    int rowStride, pixelStride;
    PublicFormat publicReaderFormat = static_cast<PublicFormat>(readerFormat);
    int halReaderFormat = android_view_Surface_mapPublicFormatToHalFormat(
        publicReaderFormat);

    ALOGV("%s: buffer index: %d", __FUNCTION__, idx);
    if (isFormatOpaque(halReaderFormat)) {
        jniThrowException(env, "java/lang/IllegalStateException",
                "Opaque images from Opaque ImageReader do not have any planes");
        return NULL;
    }

    CpuConsumer::LockedBuffer* buffer = Image_getLockedBuffer(env, thiz);

    ALOG_ASSERT(buffer != NULL);
    if (buffer == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException", "Image was released");
    }

    rowStride = Image_imageGetRowStride(env, buffer, idx, halReaderFormat);
    pixelStride = Image_imageGetPixelStride(env, buffer, idx, halReaderFormat);

    jobject surfPlaneObj = env->NewObject(gSurfacePlaneClassInfo.clazz,
            gSurfacePlaneClassInfo.ctor, thiz, idx, rowStride, pixelStride);

    return surfPlaneObj;
}

static jobject Image_getByteBuffer(JNIEnv* env, jobject thiz, int idx, int readerFormat)
{
    uint8_t *base = NULL;
    uint32_t size = 0;
    jobject byteBuffer;
    PublicFormat readerPublicFormat = static_cast<PublicFormat>(readerFormat);
    int readerHalFormat = android_view_Surface_mapPublicFormatToHalFormat(
            readerPublicFormat);

    ALOGV("%s: buffer index: %d", __FUNCTION__, idx);

    if (isFormatOpaque(readerHalFormat)) {
        jniThrowException(env, "java/lang/IllegalStateException",
                "Opaque images from Opaque ImageReader do not have any plane");
        return NULL;
    }

    CpuConsumer::LockedBuffer* buffer = Image_getLockedBuffer(env, thiz);

    if (buffer == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException", "Image was released");
    }

    // Create byteBuffer from native buffer
    Image_getLockedBufferInfo(env, buffer, idx, &base, &size, readerHalFormat);

    if (size > static_cast<uint32_t>(INT32_MAX)) {
        // Byte buffer have 'int capacity', so check the range
        jniThrowExceptionFmt(env, "java/lang/IllegalStateException",
                "Size too large for bytebuffer capacity %" PRIu32, size);
        return NULL;
    }

    byteBuffer = env->NewDirectByteBuffer(base, size);
    // TODO: throw dvm exOutOfMemoryError?
    if ((byteBuffer == NULL) && (env->ExceptionCheck() == false)) {
        jniThrowException(env, "java/lang/IllegalStateException", "Failed to allocate ByteBuffer");
    }

    return byteBuffer;
}

static jint Image_getWidth(JNIEnv* env, jobject thiz, jint format)
{
    if (isFormatOpaque(format)) {
        BufferItem* opaqueBuffer = Image_getOpaqueBuffer(env, thiz);
        return Image_getOpaqueBufferWidth(opaqueBuffer);
    } else {
        CpuConsumer::LockedBuffer* buffer = Image_getLockedBuffer(env, thiz);
        return Image_getBufferWidth(buffer);
    }
}

static jint Image_getHeight(JNIEnv* env, jobject thiz, jint format)
{
    if (isFormatOpaque(format)) {
        BufferItem* opaqueBuffer = Image_getOpaqueBuffer(env, thiz);
        return Image_getOpaqueBufferHeight(opaqueBuffer);
    } else {
        CpuConsumer::LockedBuffer* buffer = Image_getLockedBuffer(env, thiz);
        return Image_getBufferHeight(buffer);
    }
}

static jint Image_getFormat(JNIEnv* env, jobject thiz, jint readerFormat)
{
    if (isFormatOpaque(readerFormat)) {
        // Assuming opaque reader produce opaque images.
        return static_cast<jint>(PublicFormat::PRIVATE);
    } else {
        CpuConsumer::LockedBuffer* buffer = Image_getLockedBuffer(env, thiz);
        int readerHalFormat = android_view_Surface_mapPublicFormatToHalFormat(
                static_cast<PublicFormat>(readerFormat));
        int32_t fmt = applyFormatOverrides(buffer->flexFormat, readerHalFormat);
        PublicFormat publicFmt = android_view_Surface_mapHalFormatDataspaceToPublicFormat(
                fmt, buffer->dataSpace);
        return static_cast<jint>(publicFmt);
    }
}

} // extern "C"

// ----------------------------------------------------------------------------

static JNINativeMethod gImageReaderMethods[] = {
    {"nativeClassInit",        "()V",                        (void*)ImageReader_classInit },
    {"nativeInit",             "(Ljava/lang/Object;IIII)V",  (void*)ImageReader_init },
    {"nativeClose",            "()V",                        (void*)ImageReader_close },
    {"nativeReleaseImage",     "(Landroid/media/Image;)V",   (void*)ImageReader_imageRelease },
    {"nativeImageSetup",       "(Landroid/media/Image;)I",   (void*)ImageReader_imageSetup },
    {"nativeGetSurface",       "()Landroid/view/Surface;",   (void*)ImageReader_getSurface },
    {"nativeDetachImage",      "(Landroid/media/Image;)I",   (void*)ImageReader_detachImage },
};

static JNINativeMethod gImageMethods[] = {
    {"nativeImageGetBuffer",   "(II)Ljava/nio/ByteBuffer;",   (void*)Image_getByteBuffer },
    {"nativeCreatePlane",      "(II)Landroid/media/ImageReader$SurfaceImage$SurfacePlane;",
                                                              (void*)Image_createSurfacePlane },
    {"nativeGetWidth",         "(I)I",                        (void*)Image_getWidth },
    {"nativeGetHeight",        "(I)I",                        (void*)Image_getHeight },
    {"nativeGetFormat",        "(I)I",                        (void*)Image_getFormat },
};

int register_android_media_ImageReader(JNIEnv *env) {

    int ret1 = AndroidRuntime::registerNativeMethods(env,
                   "android/media/ImageReader", gImageReaderMethods, NELEM(gImageReaderMethods));

    int ret2 = AndroidRuntime::registerNativeMethods(env,
                   "android/media/ImageReader$SurfaceImage", gImageMethods, NELEM(gImageMethods));

    return (ret1 || ret2);
}
