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

#include "android_media_Utils.h"

#include <aidl/android/hardware/graphics/common/PixelFormat.h>
#include <aidl/android/hardware/graphics/common/PlaneLayoutComponentType.h>
#include <ui/GraphicBufferMapper.h>
#include <ui/GraphicTypes.h>
#include <utils/Log.h>

#define ALIGN(x, mask) ( ((x) + (mask) - 1) & ~((mask) - 1) )

// Must be in sync with the value in HeicCompositeStream.cpp
#define CAMERA3_HEIC_BLOB_ID 0x00FE

namespace android {

// -----------Utility functions used by ImageReader/Writer JNI-----------------

using AidlPixelFormat = aidl::android::hardware::graphics::common::PixelFormat;

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
        case HAL_PIXEL_FORMAT_RAW12:
        case HAL_PIXEL_FORMAT_RAW10:
        case HAL_PIXEL_FORMAT_RAW_OPAQUE:
        case HAL_PIXEL_FORMAT_BLOB:
        case HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED:
        case HAL_PIXEL_FORMAT_YCBCR_P010:
        case static_cast<int>(AidlPixelFormat::YCBCR_P210):
            return false;

        case HAL_PIXEL_FORMAT_YV12:
        case HAL_PIXEL_FORMAT_YCbCr_420_888:
        case HAL_PIXEL_FORMAT_YCrCb_420_SP:
        default:
            return true;
    }
}

bool isPossibly10BitYUV(PixelFormat format) {
    switch (static_cast<int>(format)) {
        case HAL_PIXEL_FORMAT_RGBA_8888:
        case HAL_PIXEL_FORMAT_RGBX_8888:
        case HAL_PIXEL_FORMAT_RGB_888:
        case HAL_PIXEL_FORMAT_RGB_565:
        case HAL_PIXEL_FORMAT_BGRA_8888:
        case HAL_PIXEL_FORMAT_Y8:
        case HAL_PIXEL_FORMAT_Y16:
        case HAL_PIXEL_FORMAT_RAW16:
        case HAL_PIXEL_FORMAT_RAW12:
        case HAL_PIXEL_FORMAT_RAW10:
        case HAL_PIXEL_FORMAT_RAW_OPAQUE:
        case HAL_PIXEL_FORMAT_BLOB:
        case HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED:
        case HAL_PIXEL_FORMAT_YV12:
        case HAL_PIXEL_FORMAT_YCbCr_420_888:
        case HAL_PIXEL_FORMAT_YCrCb_420_SP:
            return false;

        case HAL_PIXEL_FORMAT_YCBCR_P010:
        case static_cast<int>(AidlPixelFormat::YCBCR_P210):
        default:
            return true;
    }
}

uint32_t Image_getBlobSize(LockedImage* buffer, bool usingRGBAOverride) {
    ALOGV("%s", __FUNCTION__);
    LOG_ALWAYS_FATAL_IF(buffer == NULL, "Input buffer is NULL!!!");
    uint32_t size = 0;
    uint32_t width = buffer->width;
    uint8_t* blobBuffer = buffer->data;

    if (usingRGBAOverride) {
        width = (buffer->width + buffer->stride * (buffer->height - 1)) * 4;
    }

    // First check for BLOB transport header at the end of the buffer
    uint8_t* header = blobBuffer + (width - sizeof(struct camera3_jpeg_blob_v2));

    // read camera3_jpeg_blob_v2 from the end of the passed buffer.
    // requires memcpy because 'header' might not be properly aligned.
    struct camera3_jpeg_blob_v2 blob;
    memcpy(&blob, header, sizeof(struct camera3_jpeg_blob_v2));

    if (blob.jpeg_blob_id == CAMERA3_JPEG_BLOB_ID ||
            blob.jpeg_blob_id == CAMERA3_HEIC_BLOB_ID) {
        size = blob.jpeg_size;
        ALOGV("%s: Jpeg/Heic size = %d", __FUNCTION__, size);
    }

    // failed to find size, default to whole buffer
    if (size == 0) {
        /*
         * This is a problem because not including the JPEG/BLOB header
         * means that in certain rare situations a regular JPEG/HEIC blob
         * will be mis-identified as having a header, in which case
         * we will get a garbage size value.
         */
        ALOGW("%s: No JPEG/HEIC header detected, defaulting to size=width=%d",
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
            // Width and height should be multiple of 2. Wrong dataSize would be returned otherwise.
            if (buffer->width % 2 != 0) {
                ALOGE("YCbCr_420_888: width (%d) should be a multiple of 2", buffer->width);
                return BAD_VALUE;
            }

            if (buffer->height % 2 != 0) {
                ALOGE("YCbCr_420_888: height (%d) should be a multiple of 2", buffer->height);
                return BAD_VALUE;
            }

            if (buffer->width <= 0) {
                ALOGE("YCbCr_420_888: width (%d) should be a > 0", buffer->width);
                return BAD_VALUE;
            }

            if (buffer->height <= 0) {
                ALOGE("YCbCr_420_888: height (%d) should be a > 0", buffer->height);
                return BAD_VALUE;
            }

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
            // Width and height should be multiple of 2. Wrong dataSize would be returned otherwise.
            if (buffer->width % 2 != 0) {
                ALOGE("YCrCb_420_SP: width (%d) should be a multiple of 2", buffer->width);
                return BAD_VALUE;
            }

            if (buffer->height % 2 != 0) {
                ALOGE("YCrCb_420_SP: height (%d) should be a multiple of 2", buffer->height);
                return BAD_VALUE;
            }

            if (buffer->width <= 0) {
                ALOGE("YCrCb_420_SP: width (%d) should be a > 0", buffer->width);
                return BAD_VALUE;
            }

            if (buffer->height <= 0) {
                ALOGE("YCrCb_420_SP: height (%d) should be a > 0", buffer->height);
                return BAD_VALUE;
            }

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
            // Width and height should be multiple of 2. Wrong dataSize would be returned otherwise.
            if (buffer->width % 2 != 0) {
                ALOGE("YV12: width (%d) should be a multiple of 2", buffer->width);
                return BAD_VALUE;
            }

            if (buffer->height % 2 != 0) {
                ALOGE("YV12: height (%d) should be a multiple of 2", buffer->height);
                return BAD_VALUE;
            }

            if (buffer->width <= 0) {
                ALOGE("YV12: width (%d) should be a > 0", buffer->width);
                return BAD_VALUE;
            }

            if (buffer->height <= 0) {
                ALOGE("YV12: height (%d) should be a > 0", buffer->height);
                return BAD_VALUE;
            }

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
        case HAL_PIXEL_FORMAT_YCBCR_P010:
            if (buffer->height % 2 != 0) {
                ALOGE("YCBCR_P010: height (%d) should be a multiple of 2", buffer->height);
                return BAD_VALUE;
            }

            if (buffer->width <= 0) {
                ALOGE("YCBCR_P010: width (%d) should be a > 0", buffer->width);
                return BAD_VALUE;
            }

            if (buffer->height <= 0) {
                ALOGE("YCBCR_P010: height (%d) should be a > 0", buffer->height);
                return BAD_VALUE;
            }

            if (buffer->dataCb && buffer->dataCr) {
                pData =
                    (idx == 0) ?
                        buffer->data :
                    (idx == 1) ?
                        buffer->dataCb :
                    buffer->dataCr;
                // only map until last pixel
                if (idx == 0) {
                    pStride = 2;
                    rStride = buffer->stride;
                    dataSize = buffer->stride * (buffer->height - 1) + buffer->width * 2;
                } else {
                    pStride = buffer->chromaStep;
                    rStride = buffer->chromaStride;
                    dataSize = buffer->chromaStride * (buffer->height / 2 - 1) +
                            buffer->chromaStep * (buffer->width / 2);
                }
                break;
            }

            ySize = (buffer->stride * 2) * buffer->height;
            cSize = ySize / 2;
            pStride = (idx == 0) ? 2 : 4;
            cb = buffer->data + ySize;
            cr = cb + 2;

            pData = (idx == 0) ? buffer->data : (idx == 1) ? cb : cr;
            dataSize = (idx == 0) ? ySize : cSize;
            rStride = buffer->stride * 2;
            break;
        case static_cast<int>(AidlPixelFormat::YCBCR_P210):
            if (buffer->height % 2 != 0) {
                ALOGE("YCBCR_P210: height (%d) should be a multiple of 2", buffer->height);
                return BAD_VALUE;
            }

            if (buffer->width <= 0) {
                ALOGE("YCBCR_P210: width (%d) should be a > 0", buffer->width);
                return BAD_VALUE;
            }

            if (buffer->height <= 0) {
                ALOGE("YCBCR_210: height (%d) should be a > 0", buffer->height);
                return BAD_VALUE;
            }
            if (buffer->dataCb && buffer->dataCr) {
                pData = (idx == 0) ? buffer->data : (idx == 1) ? buffer->dataCb : buffer->dataCr;
                // only map until last pixel
                if (idx == 0) {
                    pStride = 2;
                    rStride = buffer->stride;
                    dataSize = buffer->stride * (buffer->height - 1) + buffer->width * 2;
                } else {
                    pStride = buffer->chromaStep;
                    rStride = buffer->chromaStride;
                    dataSize = buffer->chromaStride * (buffer->height - 1) +
                            buffer->chromaStep * (buffer->width / 2);
                }
                break;
            }

            ySize = (buffer->stride * 2) * buffer->height;
            cSize = ySize;
            pStride = (idx == 0) ? 2 : 4;
            cb = buffer->data + ySize;
            cr = cb + 2;

            pData = (idx == 0) ?  buffer->data : (idx == 1) ?  cb : cr;
            dataSize = (idx == 0) ? ySize : cSize;
            rStride = buffer->stride * 2;
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
            dataSize = Image_getBlobSize(buffer, usingRGBAOverride);
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
            ALOGV("%s: unrecognized format 0x%x", __FUNCTION__, fmt);
            return BAD_VALUE;
    }

    *base = pData;
    *size = dataSize;
    *pixelStride = pStride;
    *rowStride = rStride;

    return OK;
}

static status_t extractP010Gralloc4PlaneLayout(
        sp<GraphicBuffer> buffer, void *pData, int format, LockedImage *outputImage) {
    using aidl::android::hardware::graphics::common::PlaneLayoutComponent;
    using aidl::android::hardware::graphics::common::PlaneLayoutComponentType;

    GraphicBufferMapper& mapper = GraphicBufferMapper::get();
    std::vector<ui::PlaneLayout> planeLayouts;
    status_t res = mapper.getPlaneLayouts(buffer->handle, &planeLayouts);
    if (res != OK) {
        return res;
    }
    constexpr int64_t Y_PLANE_COMPONENTS = int64_t(PlaneLayoutComponentType::Y);
    constexpr int64_t CBCR_PLANE_COMPONENTS =
        int64_t(PlaneLayoutComponentType::CB) | int64_t(PlaneLayoutComponentType::CR);
    uint8_t *dataY = nullptr;
    uint8_t *dataCb = nullptr;
    uint8_t *dataCr = nullptr;
    uint32_t strideY = 0;
    uint32_t strideCbCr = 0;
    for (const ui::PlaneLayout &layout : planeLayouts) {
        ALOGV("gralloc4 plane: %s", layout.toString().c_str());
        int64_t components = 0;
        for (const PlaneLayoutComponent &component : layout.components) {
            if (component.sizeInBits != 10) {
                return BAD_VALUE;
            }
            components |= component.type.value;
        }
        if (components == Y_PLANE_COMPONENTS) {
            if (layout.sampleIncrementInBits != 16) {
                return BAD_VALUE;
            }
            if (layout.components[0].offsetInBits != 6) {
                return BAD_VALUE;
            }
            dataY = (uint8_t *)pData + layout.offsetInBytes;
            strideY = layout.strideInBytes;
        } else if (components == CBCR_PLANE_COMPONENTS) {
            if (layout.sampleIncrementInBits != 32) {
                return BAD_VALUE;
            }
            for (const PlaneLayoutComponent &component : layout.components) {
                if (component.type.value == int64_t(PlaneLayoutComponentType::CB)
                        && component.offsetInBits != 6) {
                    return BAD_VALUE;
                }
                if (component.type.value == int64_t(PlaneLayoutComponentType::CR)
                        && component.offsetInBits != 22) {
                    return BAD_VALUE;
                }
            }
            dataCb = (uint8_t *)pData + layout.offsetInBytes;
            dataCr = (uint8_t *)pData + layout.offsetInBytes + 2;
            strideCbCr = layout.strideInBytes;
        } else {
            return BAD_VALUE;
        }
    }

    outputImage->data = dataY;
    outputImage->width = buffer->getWidth();
    outputImage->height = buffer->getHeight();
    outputImage->format = format;
    outputImage->flexFormat = HAL_PIXEL_FORMAT_YCBCR_P010;
    outputImage->stride = strideY;

    outputImage->dataCb = dataCb;
    outputImage->dataCr = dataCr;
    outputImage->chromaStride = strideCbCr;
    outputImage->chromaStep = 4;
    return OK;
}

static status_t extractP210Gralloc4PlaneLayout(sp<GraphicBuffer> buffer, void *pData, int format,
                                               LockedImage *outputImage) {
    using aidl::android::hardware::graphics::common::PlaneLayoutComponent;
    using aidl::android::hardware::graphics::common::PlaneLayoutComponentType;

    GraphicBufferMapper &mapper = GraphicBufferMapper::get();
    std::vector<ui::PlaneLayout> planeLayouts;
    status_t res = mapper.getPlaneLayouts(buffer->handle, &planeLayouts);
    if (res != OK) {
        return res;
    }
    constexpr int64_t Y_PLANE_COMPONENTS = int64_t(PlaneLayoutComponentType::Y);
    constexpr int64_t CBCR_PLANE_COMPONENTS =
            int64_t(PlaneLayoutComponentType::CB) | int64_t(PlaneLayoutComponentType::CR);
    uint8_t *dataY = nullptr;
    uint8_t *dataCb = nullptr;
    uint8_t *dataCr = nullptr;
    uint32_t strideY = 0;
    uint32_t strideCbCr = 0;
    for (const ui::PlaneLayout &layout : planeLayouts) {
        ALOGV("gralloc4 plane: %s", layout.toString().c_str());
        int64_t components = 0;
        for (const PlaneLayoutComponent &component : layout.components) {
            if (component.sizeInBits != 10) {
                return BAD_VALUE;
            }
            components |= component.type.value;
        }
        if (components == Y_PLANE_COMPONENTS) {
            if (layout.sampleIncrementInBits != 16) {
                return BAD_VALUE;
            }
            if (layout.components[0].offsetInBits != 6) {
                return BAD_VALUE;
            }
            dataY = (uint8_t *)pData + layout.offsetInBytes;
            strideY = layout.strideInBytes;
        } else if (components == CBCR_PLANE_COMPONENTS) {
            if (layout.sampleIncrementInBits != 32) {
                return BAD_VALUE;
            }
            for (const PlaneLayoutComponent &component : layout.components) {
                if (component.type.value == int64_t(PlaneLayoutComponentType::CB) &&
                    component.offsetInBits != 6) {
                    return BAD_VALUE;
                }
                if (component.type.value == int64_t(PlaneLayoutComponentType::CR) &&
                    component.offsetInBits != 22) {
                    return BAD_VALUE;
                }
            }
            dataCb = (uint8_t *)pData + layout.offsetInBytes;
            dataCr = (uint8_t *)pData + layout.offsetInBytes + 2;
            strideCbCr = layout.strideInBytes;
        } else {
            return BAD_VALUE;
        }
    }

    outputImage->data = dataY;
    outputImage->width = buffer->getWidth();
    outputImage->height = buffer->getHeight();
    outputImage->format = format;
    outputImage->flexFormat =
            static_cast<int>(AidlPixelFormat::YCBCR_P210);
    outputImage->stride = strideY;

    outputImage->dataCb = dataCb;
    outputImage->dataCr = dataCr;
    outputImage->chromaStride = strideCbCr;
    outputImage->chromaStep = 4;
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

        if (res != OK) {
            ALOGW("lockAsyncYCbCr failed with error %d (format = 0x%x)", res, format);
        }

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
        if (isPossibly10BitYUV(format)) {
            if (format == HAL_PIXEL_FORMAT_YCBCR_P010 &&
                OK == extractP010Gralloc4PlaneLayout(buffer, pData, format, outputImage)) {
                ALOGV("%s: Successfully locked the P010 image", __FUNCTION__);
                return OK;
            } else if ((format ==
                        static_cast<int>(AidlPixelFormat::YCBCR_P210)) &&
                       OK == extractP210Gralloc4PlaneLayout(buffer, pData, format, outputImage)) {
                ALOGV("%s: Successfully locked the P210 image", __FUNCTION__);
                return OK;
            }
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

