/*
 * Copyright (C) 2009 The Android Open Source Project
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

#define LOG_TAG "SoftwareRenderer"
#include <utils/Log.h>

#include "../include/SoftwareRenderer.h"

#include <binder/MemoryHeapBase.h>
#include <binder/MemoryHeapPmem.h>
#include <media/stagefright/MediaDebug.h>
#include <surfaceflinger/Surface.h>
#include <ui/android_native_buffer.h>
#include <ui/GraphicBufferMapper.h>

namespace android {

SoftwareRenderer::SoftwareRenderer(
        OMX_COLOR_FORMATTYPE colorFormat,
        const sp<Surface> &surface,
        size_t displayWidth, size_t displayHeight,
        size_t decodedWidth, size_t decodedHeight)
    : mColorFormat(colorFormat),
      mConverter(NULL),
      mYUVMode(None),
      mSurface(surface),
      mDisplayWidth(displayWidth),
      mDisplayHeight(displayHeight),
      mDecodedWidth(decodedWidth),
      mDecodedHeight(decodedHeight) {
    LOGI("input format = %d", mColorFormat);
    LOGI("display = %d x %d, decoded = %d x %d",
            mDisplayWidth, mDisplayHeight, mDecodedWidth, mDecodedHeight);

    int halFormat;
    switch (mColorFormat) {
#if HAS_YCBCR420_SP_ADRENO
        case OMX_COLOR_FormatYUV420Planar:
        {
            halFormat = HAL_PIXEL_FORMAT_YCrCb_420_SP_ADRENO;
            mYUVMode = YUV420ToYUV420sp;
            break;
        }

        case 0x7fa30c00:
        {
            halFormat = HAL_PIXEL_FORMAT_YCrCb_420_SP_ADRENO;
            mYUVMode = YUV420spToYUV420sp;
            break;
        }
#endif

        default:
            halFormat = HAL_PIXEL_FORMAT_RGB_565;

            mConverter = new ColorConverter(
                    mColorFormat, OMX_COLOR_Format16bitRGB565);
            CHECK(mConverter->isValid());
            break;
    }

    CHECK(mSurface.get() != NULL);
    CHECK(mDecodedWidth > 0);
    CHECK(mDecodedHeight > 0);
    CHECK(mConverter == NULL || mConverter->isValid());

    CHECK_EQ(0,
            native_window_set_usage(
            mSurface.get(),
            GRALLOC_USAGE_SW_READ_NEVER | GRALLOC_USAGE_SW_WRITE_OFTEN
            | GRALLOC_USAGE_HW_TEXTURE));

    CHECK_EQ(0, native_window_set_buffer_count(mSurface.get(), 2));

    // Width must be multiple of 32???
    CHECK_EQ(0, native_window_set_buffers_geometry(
                mSurface.get(), mDecodedWidth, mDecodedHeight,
                halFormat));
}

SoftwareRenderer::~SoftwareRenderer() {
    delete mConverter;
    mConverter = NULL;
}

static inline size_t ALIGN(size_t x, size_t alignment) {
    return (x + alignment - 1) & ~(alignment - 1);
}

void SoftwareRenderer::render(
        const void *data, size_t size, void *platformPrivate) {
    android_native_buffer_t *buf;
    CHECK_EQ(0, mSurface->dequeueBuffer(mSurface.get(), &buf));
    CHECK_EQ(0, mSurface->lockBuffer(mSurface.get(), buf));

    GraphicBufferMapper &mapper = GraphicBufferMapper::get();

    Rect bounds(mDecodedWidth, mDecodedHeight);

    void *dst;
    CHECK_EQ(0, mapper.lock(
                buf->handle, GRALLOC_USAGE_SW_WRITE_OFTEN, bounds, &dst));

    if (mConverter) {
        mConverter->convert(
                mDecodedWidth, mDecodedHeight,
                data, 0, dst, buf->stride * 2);
    } else if (mYUVMode == YUV420spToYUV420sp) {
        // Input and output are both YUV420sp, but the alignment requirements
        // are different.
        size_t srcYStride = mDecodedWidth;
        const uint8_t *srcY = (const uint8_t *)data;
        uint8_t *dstY = (uint8_t *)dst;
        for (size_t i = 0; i < mDecodedHeight; ++i) {
            memcpy(dstY, srcY, mDecodedWidth);
            srcY += srcYStride;
            dstY += buf->stride;
        }

        size_t srcUVStride = (mDecodedWidth + 1) & ~1;
        size_t dstUVStride = ALIGN(mDecodedWidth / 2, 32) * 2;

        const uint8_t *srcUV = (const uint8_t *)data
            + mDecodedHeight * mDecodedWidth;

        size_t dstUVOffset = ALIGN(ALIGN(mDecodedHeight, 32) * buf->stride, 4096);
        uint8_t *dstUV = (uint8_t *)dst + dstUVOffset;

        for (size_t i = 0; i < (mDecodedHeight + 1) / 2; ++i) {
            memcpy(dstUV, srcUV, (mDecodedWidth + 1) & ~1);
            srcUV += srcUVStride;
            dstUV += dstUVStride;
        }
    } else if (mYUVMode == YUV420ToYUV420sp) {
        // Input is YUV420 planar, output is YUV420sp, adhere to proper
        // alignment requirements.
        size_t srcYStride = mDecodedWidth;
        const uint8_t *srcY = (const uint8_t *)data;
        uint8_t *dstY = (uint8_t *)dst;
        for (size_t i = 0; i < mDecodedHeight; ++i) {
            memcpy(dstY, srcY, mDecodedWidth);
            srcY += srcYStride;
            dstY += buf->stride;
        }

        size_t srcUVStride = (mDecodedWidth + 1) / 2;
        size_t dstUVStride = ALIGN(mDecodedWidth / 2, 32) * 2;

        const uint8_t *srcU = (const uint8_t *)data
            + mDecodedHeight * mDecodedWidth;

        const uint8_t *srcV =
            srcU + ((mDecodedWidth + 1) / 2) * ((mDecodedHeight + 1) / 2);

        size_t dstUVOffset = ALIGN(ALIGN(mDecodedHeight, 32) * buf->stride, 4096);
        uint8_t *dstUV = (uint8_t *)dst + dstUVOffset;

        for (size_t i = 0; i < (mDecodedHeight + 1) / 2; ++i) {
            for (size_t j = 0; j < (mDecodedWidth + 1) / 2; ++j) {
                dstUV[2 * j + 1] = srcU[j];
                dstUV[2 * j] = srcV[j];
            }
            srcU += srcUVStride;
            srcV += srcUVStride;
            dstUV += dstUVStride;
        }
    } else {
        memcpy(dst, data, size);
    }

    CHECK_EQ(0, mapper.unlock(buf->handle));

    CHECK_EQ(0, mSurface->queueBuffer(mSurface.get(), buf));
    buf = NULL;
}

}  // namespace android
