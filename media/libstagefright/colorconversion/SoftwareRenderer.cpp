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
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/MetaData.h>
#include <surfaceflinger/Surface.h>
#include <ui/android_native_buffer.h>
#include <ui/GraphicBufferMapper.h>
#include <gui/ISurfaceTexture.h>

namespace android {

SoftwareRenderer::SoftwareRenderer(
        const sp<ANativeWindow> &nativeWindow, const sp<MetaData> &meta)
    : mConverter(NULL),
      mYUVMode(None),
      mNativeWindow(nativeWindow) {
    int32_t tmp;
    CHECK(meta->findInt32(kKeyColorFormat, &tmp));
    mColorFormat = (OMX_COLOR_FORMATTYPE)tmp;

    CHECK(meta->findInt32(kKeyWidth, &mWidth));
    CHECK(meta->findInt32(kKeyHeight, &mHeight));

    if (!meta->findRect(
                kKeyCropRect,
                &mCropLeft, &mCropTop, &mCropRight, &mCropBottom)) {
        mCropLeft = mCropTop = 0;
        mCropRight = mWidth - 1;
        mCropBottom = mHeight - 1;
    }

    mCropWidth = mCropRight - mCropLeft + 1;
    mCropHeight = mCropBottom - mCropTop + 1;

    int32_t rotationDegrees;
    if (!meta->findInt32(kKeyRotation, &rotationDegrees)) {
        rotationDegrees = 0;
    }

    int halFormat;
    size_t bufWidth, bufHeight;

    switch (mColorFormat) {
        case OMX_COLOR_FormatYUV420Planar:
        case OMX_TI_COLOR_FormatYUV420PackedSemiPlanar:
        {
            halFormat = HAL_PIXEL_FORMAT_YV12;
            bufWidth = (mCropWidth + 1) & ~1;
            bufHeight = (mCropHeight + 1) & ~1;
            break;
        }

        default:
            halFormat = HAL_PIXEL_FORMAT_RGB_565;
            bufWidth = mCropWidth;
            bufHeight = mCropHeight;

            mConverter = new ColorConverter(
                    mColorFormat, OMX_COLOR_Format16bitRGB565);
            CHECK(mConverter->isValid());
            break;
    }

    CHECK(mNativeWindow != NULL);
    CHECK(mCropWidth > 0);
    CHECK(mCropHeight > 0);
    CHECK(mConverter == NULL || mConverter->isValid());

    CHECK_EQ(0,
            native_window_set_usage(
            mNativeWindow.get(),
            GRALLOC_USAGE_SW_READ_NEVER | GRALLOC_USAGE_SW_WRITE_OFTEN
            | GRALLOC_USAGE_HW_TEXTURE | GRALLOC_USAGE_EXTERNAL_DISP));

    CHECK_EQ(0,
            native_window_set_scaling_mode(
            mNativeWindow.get(),
            NATIVE_WINDOW_SCALING_MODE_SCALE_TO_WINDOW));

    // Width must be multiple of 32???
    CHECK_EQ(0, native_window_set_buffers_geometry(
                mNativeWindow.get(),
                bufWidth,
                bufHeight,
                halFormat));

    uint32_t transform;
    switch (rotationDegrees) {
        case 0: transform = 0; break;
        case 90: transform = HAL_TRANSFORM_ROT_90; break;
        case 180: transform = HAL_TRANSFORM_ROT_180; break;
        case 270: transform = HAL_TRANSFORM_ROT_270; break;
        default: transform = 0; break;
    }

    if (transform) {
        CHECK_EQ(0, native_window_set_buffers_transform(
                    mNativeWindow.get(), transform));
    }
}

SoftwareRenderer::~SoftwareRenderer() {
    delete mConverter;
    mConverter = NULL;
}

static int ALIGN(int x, int y) {
    // y must be a power of 2.
    return (x + y - 1) & ~(y - 1);
}

void SoftwareRenderer::render(
        const void *data, size_t size, void *platformPrivate) {
    ANativeWindowBuffer *buf;
    int err;
    if ((err = mNativeWindow->dequeueBuffer(mNativeWindow.get(), &buf)) != 0) {
        LOGW("Surface::dequeueBuffer returned error %d", err);
        return;
    }

    CHECK_EQ(0, mNativeWindow->lockBuffer(mNativeWindow.get(), buf));

    GraphicBufferMapper &mapper = GraphicBufferMapper::get();

    Rect bounds(mCropWidth, mCropHeight);

    void *dst;
    CHECK_EQ(0, mapper.lock(
                buf->handle, GRALLOC_USAGE_SW_WRITE_OFTEN, bounds, &dst));

    if (mConverter) {
        mConverter->convert(
                data,
                mWidth, mHeight,
                mCropLeft, mCropTop, mCropRight, mCropBottom,
                dst,
                buf->stride, buf->height,
                0, 0, mCropWidth - 1, mCropHeight - 1);
    } else if (mColorFormat == OMX_COLOR_FormatYUV420Planar) {
        const uint8_t *src_y = (const uint8_t *)data;
        const uint8_t *src_u = (const uint8_t *)data + mWidth * mHeight;
        const uint8_t *src_v = src_u + (mWidth / 2 * mHeight / 2);

        uint8_t *dst_y = (uint8_t *)dst;
        size_t dst_y_size = buf->stride * buf->height;
        size_t dst_c_stride = ALIGN(buf->stride / 2, 16);
        size_t dst_c_size = dst_c_stride * buf->height / 2;
        uint8_t *dst_v = dst_y + dst_y_size;
        uint8_t *dst_u = dst_v + dst_c_size;

        for (int y = 0; y < mCropHeight; ++y) {
            memcpy(dst_y, src_y, mCropWidth);

            src_y += mWidth;
            dst_y += buf->stride;
        }

        for (int y = 0; y < (mCropHeight + 1) / 2; ++y) {
            memcpy(dst_u, src_u, (mCropWidth + 1) / 2);
            memcpy(dst_v, src_v, (mCropWidth + 1) / 2);

            src_u += mWidth / 2;
            src_v += mWidth / 2;
            dst_u += dst_c_stride;
            dst_v += dst_c_stride;
        }
    } else {
        CHECK_EQ(mColorFormat, OMX_TI_COLOR_FormatYUV420PackedSemiPlanar);

        const uint8_t *src_y =
            (const uint8_t *)data;

        const uint8_t *src_uv =
            (const uint8_t *)data + mWidth * (mHeight - mCropTop / 2);

        uint8_t *dst_y = (uint8_t *)dst;

        size_t dst_y_size = buf->stride * buf->height;
        size_t dst_c_stride = ALIGN(buf->stride / 2, 16);
        size_t dst_c_size = dst_c_stride * buf->height / 2;
        uint8_t *dst_v = dst_y + dst_y_size;
        uint8_t *dst_u = dst_v + dst_c_size;

        for (int y = 0; y < mCropHeight; ++y) {
            memcpy(dst_y, src_y, mCropWidth);

            src_y += mWidth;
            dst_y += buf->stride;
        }

        for (int y = 0; y < (mCropHeight + 1) / 2; ++y) {
            size_t tmp = (mCropWidth + 1) / 2;
            for (size_t x = 0; x < tmp; ++x) {
                dst_u[x] = src_uv[2 * x];
                dst_v[x] = src_uv[2 * x + 1];
            }

            src_uv += mWidth;
            dst_u += dst_c_stride;
            dst_v += dst_c_stride;
        }
    }

    CHECK_EQ(0, mapper.unlock(buf->handle));

    if ((err = mNativeWindow->queueBuffer(mNativeWindow.get(), buf)) != 0) {
        LOGW("Surface::queueBuffer returned error %d", err);
    }
    buf = NULL;
}

}  // namespace android
