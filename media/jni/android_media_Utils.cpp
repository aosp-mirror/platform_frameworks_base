/*
 * Copyright 2011, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// #define LOG_NDEBUG 0
#define LOG_TAG "AndroidMediaUtils"

#include <hardware/camera3.h>
#include <utils/Log.h>
#include "android_media_Utils.h"

#define ALIGN(x, mask) ( ((x) + (mask) - 1) & ~((mask) - 1) )

namespace android {

// -----------Utility functions used by ImageReader/Writer JNI-----------------

enum {
    IMAGE_MAX_NUM_PLANES = 3,
};

bool usingRGBAToJpegOverride(int32_t imageFormat,
        int32_t containerFormat) {
    return containerFormat == HAL_PIXEL_FORMAT_BLOB && imageFormat == HAL_PIXEL_FORMAT_RGBA_8888;
}

int32_t applyFormatOverrides(int32_t imageFormat, int32_t containerFormat) {
    // Using HAL_PIXEL_FORMAT_RGBA_8888 gralloc buffers containing JPEGs to get around SW
    // write limitations for some platforms (b/17379185).
    if (usingRGBAToJpegOverride(imageFormat, containerFormat)) {
        return HAL_PIXEL_FORMAT_BLOB;
    }
    return containerFormat;
}

bool isFormatOpaque(int format) {
    // This is the only opaque format exposed in the ImageFormat public API.
    // Note that we do support CPU access for HAL_PIXEL_FORMAT_RAW_OPAQUE
    // (ImageFormat#RAW_PRIVATE) so it doesn't count as opaque here.
    return format == HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED;
}

bool isPossiblyYUV(PixelFormat format) {
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
        case HAL_PIXEL_FORMAT_YCrCb_420_SP:
        default:
            return true;
    }
}

uint32_t Image_getJpegSize(LockedImage* buffer, bool usingRGBAOverride) {
    ALOGV("%s", __FUNCTION__);
    LOG_ALWAYS_FATAL_IF(buffer == NULL, "Input buffer is NULL!!!");
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
         * will be mis-identified as having a header, in which case
         * we will get a garbage size value.
         */
        ALOGW("%s: No JPEG header detected, defaulting to size=width=%d",
                __FUNCTION__, width);
        size = width;
    }

    return size;
}

status_t getLockedImageInfo(LockedImage* buffer, int idx,
        int32_t containerFormat, uint8_t **base, uint32_t *size, int *pixelStride, int *rowStride) {
    ALOGV("%s", __FUNCTION__);
    LOG_ALWAYS_FATAL_IF(buffer == NULL, "Input buffer is NULL!!!");
    LOG_ALWAYS_FATAL_IF(base == NULL, "base is NULL!!!");
    LOG_ALWAYS_FATAL_IF(size == NULL, "size is NULL!!!");
    LOG_ALWAYS_FATAL_IF(pixelStride == NULL, "pixelStride is NULL!!!");
    LOG_ALWAYS_FATAL_IF(rowStride == NULL, "rowStride is NULL!!!");
    LOG_ALWAYS_FATAL_IF((idx >= IMAGE_MAX_NUM_PLANES) || (idx < 0), "idx (%d) is illegal", idx);

    ALOGV("%s: buffer: %p", __FUNCTION__, buffer);

    uint32_t dataSize, ySize, cSize, cStride;
    uint32_t pStride = 0, rStride = 0;
    uint8_t *cb, *cr;
    uint8_t *pData = NULL;
    int bytesPerPixel = 0;

    dataSize = ySize = cSize = cStride = 0;
    int32_t fmt = buffer->flexFormat;

    bool usingRGBAOverride = usingRGBAToJpegOverride(fmt, containerFormat);
    fmt = applyFormatOverrides(fmt, containerFormat);
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
            LOG_ALWAYS_FATAL_IF(idx != 0, "Wrong index: %d", idx);

            pData = buffer->data;
            dataSize = buffer->stride * buffer->height;
            pStride = 1;
            rStride = buffer->stride;
            break;
        case HAL_PIXEL_FORMAT_Y16:
            bytesPerPixel = 2;
            // Single plane, 16bpp, strides are specified in pixels, not in bytes
            LOG_ALWAYS_FATAL_IF(idx != 0, "Wrong index: %d", idx);

            pData = buffer->data;
            dataSize = buffer->stride * buffer->height * bytesPerPixel;
            pStride = bytesPerPixel;
            rStride = buffer->stride * 2;
            break;
        case HAL_PIXEL_FORMAT_BLOB:
            // Used for JPEG data, height must be 1, width == size, single plane.
            LOG_ALWAYS_FATAL_IF(idx != 0, "Wrong index: %d", idx);
            // When RGBA override is being used, buffer height will be equal to width
            if (usingRGBAOverride) {
                LOG_ALWAYS_FATAL_IF(buffer->height != buffer->width,
                        "RGBA override BLOB format buffer should have height == width");
            } else {
                LOG_ALWAYS_FATAL_IF(buffer->height != 1,
                        "BLOB format buffer should have height value 1");
            }


            pData = buffer->data;
            dataSize = Image_getJpegSize(buffer, usingRGBAOverride);
            pStride = 0;
            rStride = 0;
            break;
        case HAL_PIXEL_FORMAT_RAW16:
            // Single plane 16bpp bayer data.
            bytesPerPixel = 2;
            LOG_ALWAYS_FATAL_IF(idx != 0, "Wrong index: %d", idx);
            pData = buffer->data;
            dataSize = buffer->stride * buffer->height * bytesPerPixel;
            pStride = bytesPerPixel;
            rStride = buffer->stride * 2;
            break;
        case HAL_PIXEL_FORMAT_RAW_OPAQUE:
            // Used for RAW_OPAQUE data, height must be 1, width == size, single plane.
            LOG_ALWAYS_FATAL_IF(idx != 0, "Wrong index: %d", idx);
            LOG_ALWAYS_FATAL_IF(buffer->height != 1,
                    "RAW_PRIVATE should has height value one but got %d", buffer->height);
            pData = buffer->data;
            dataSize = buffer->width;
            pStride = 0; // RAW OPAQUE doesn't have pixel stride
            rStride = 0; // RAW OPAQUE doesn't have row stride
            break;
        case HAL_PIXEL_FORMAT_RAW10:
            // Single plane 10bpp bayer data.
            LOG_ALWAYS_FATAL_IF(idx != 0, "Wrong index: %d", idx);
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
        case HAL_PIXEL_FORMAT_RAW12:
            // Single plane 10bpp bayer data.
            LOG_ALWAYS_FATAL_IF(idx != 0, "Wrong index: %d", idx);
            LOG_ALWAYS_FATAL_IF(buffer->width % 4,
                                "Width is not multiple of 4 %d", buffer->width);
            LOG_ALWAYS_FATAL_IF(buffer->height % 2,
                                "Height is not even %d", buffer->height);
            LOG_ALWAYS_FATAL_IF(buffer->stride < (buffer->width * 12 / 8),
                                "stride (%d) should be at least %d",
                                buffer->stride, buffer->width * 12 / 8);
            pData = buffer->data;
            dataSize = buffer->stride * buffer->height;
            pStride = 0;
            rStride = buffer->stride;
            break;
        case HAL_PIXEL_FORMAT_RGBA_8888:
        case HAL_PIXEL_FORMAT_RGBX_8888:
            // Single plane, 32bpp.
            bytesPerPixel = 4;
            LOG_ALWAYS_FATAL_IF(idx != 0, "Wrong index: %d", idx);
            pData = buffer->data;
            dataSize = buffer->stride * buffer->height * bytesPerPixel;
            pStride = bytesPerPixel;
            rStride = buffer->stride * 4;
            break;
        case HAL_PIXEL_FORMAT_RGB_565:
            // Single plane, 16bpp.
            bytesPerPixel = 2;
            LOG_ALWAYS_FATAL_IF(idx != 0, "Wrong index: %d", idx);
            pData = buffer->data;
            dataSize = buffer->stride * buffer->height * bytesPerPixel;
            pStride = bytesPerPixel;
            rStride = buffer->stride * 2;
            break;
        case HAL_PIXEL_FORMAT_RGB_888:
            // Single plane, 24bpp.
            bytesPerPixel = 3;
            LOG_ALWAYS_FATAL_IF(idx != 0, "Wrong index: %d", idx);
            pData = buffer->data;
            dataSize = buffer->stride * buffer->height * bytesPerPixel;
            pStride = bytesPerPixel;
            rStride = buffer->stride * 3;
            break;
        default:
            return BAD_VALUE;
    }

    *base = pData;
    *size = dataSize;
    *pixelStride = pStride;
    *rowStride = rStride;

    return OK;
}

status_t lockImageFromBuffer(sp<GraphicBuffer> buffer, uint32_t inUsage,
        const Rect& rect, int fenceFd, LockedImage* outputImage) {
    ALOGV("%s: Try to lock the GraphicBuffer", __FUNCTION__);

    if (buffer == nullptr || outputImage == nullptr) {
        ALOGE("Input BufferItem or output LockedImage is NULL!");
        return BAD_VALUE;
    }
    if (isFormatOpaque(buffer->getPixelFormat())) {
        ALOGE("Opaque format buffer is not lockable!");
        return BAD_VALUE;
    }

    void* pData = NULL;
    android_ycbcr ycbcr = android_ycbcr();
    status_t res;
    int format = buffer->getPixelFormat();
    int flexFormat = format;
    if (isPossiblyYUV(format)) {
        res = buffer->lockAsyncYCbCr(inUsage, rect, &ycbcr, fenceFd);
        pData = ycbcr.y;
        flexFormat = HAL_PIXEL_FORMAT_YCbCr_420_888;
    }

    // lockAsyncYCbCr for YUV is unsuccessful.
    if (pData == NULL) {
        res = buffer->lockAsync(inUsage, rect, &pData, fenceFd);
        if (res != OK) {
            ALOGE("Lock buffer failed!");
            return res;
        }
    }

    outputImage->data = reinterpret_cast<uint8_t*>(pData);
    outputImage->width = buffer->getWidth();
    outputImage->height = buffer->getHeight();
    outputImage->format = format;
    outputImage->flexFormat = flexFormat;
    outputImage->stride =
            (ycbcr.y != NULL) ? static_cast<uint32_t>(ycbcr.ystride) : buffer->getStride();

    outputImage->dataCb = reinterpret_cast<uint8_t*>(ycbcr.cb);
    outputImage->dataCr = reinterpret_cast<uint8_t*>(ycbcr.cr);
    outputImage->chromaStride = static_cast<uint32_t>(ycbcr.cstride);
    outputImage->chromaStep = static_cast<uint32_t>(ycbcr.chroma_step);
    ALOGV("%s: Successfully locked the image from the GraphicBuffer", __FUNCTION__);
    // Crop, transform, scalingMode, timestamp, and frameNumber should be set by caller,
    // and cann't be set them here.
    return OK;
}

status_t lockImageFromBuffer(BufferItem* bufferItem, uint32_t inUsage,
        int fenceFd, LockedImage* outputImage) {
    ALOGV("%s: Try to lock the BufferItem", __FUNCTION__);
    if (bufferItem == nullptr || outputImage == nullptr) {
        ALOGE("Input BufferItem or output LockedImage is NULL!");
        return BAD_VALUE;
    }

    status_t res = lockImageFromBuffer(bufferItem->mGraphicBuffer, inUsage, bufferItem->mCrop,
            fenceFd, outputImage);
    if (res != OK) {
        ALOGE("%s: lock graphic buffer failed", __FUNCTION__);
        return res;
    }

    outputImage->crop        = bufferItem->mCrop;
    outputImage->transform   = bufferItem->mTransform;
    outputImage->scalingMode = bufferItem->mScalingMode;
    outputImage->timestamp   = bufferItem->mTimestamp;
    outputImage->dataSpace   = bufferItem->mDataSpace;
    outputImage->frameNumber = bufferItem->mFrameNumber;
    ALOGV("%s: Successfully locked the image from the BufferItem", __FUNCTION__);
    return OK;
}

int getBufferWidth(BufferItem* buffer) {
    if (buffer == NULL) return -1;

    if (!buffer->mCrop.isEmpty()) {
        return buffer->mCrop.getWidth();
    }

    ALOGV("%s: buffer->mGraphicBuffer: %p", __FUNCTION__, buffer->mGraphicBuffer.get());
    return buffer->mGraphicBuffer->getWidth();
}

int getBufferHeight(BufferItem* buffer) {
    if (buffer == NULL) return -1;

    if (!buffer->mCrop.isEmpty()) {
        return buffer->mCrop.getHeight();
    }

    ALOGV("%s: buffer->mGraphicBuffer: %p", __FUNCTION__, buffer->mGraphicBuffer.get());
    return buffer->mGraphicBuffer->getHeight();
}

}  // namespace android

