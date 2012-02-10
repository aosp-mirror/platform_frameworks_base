/*
 * Copyright (C) 2010 The Android Open Source Project
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

#define LOG_NDEBUG 0
#define LOG_TAG "YUVCanvas"

#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/YUVCanvas.h>
#include <media/stagefright/YUVImage.h>
#include <ui/Rect.h>

namespace android {

YUVCanvas::YUVCanvas(YUVImage &yuvImage)
    : mYUVImage(yuvImage) {
}

YUVCanvas::~YUVCanvas() {
}

void YUVCanvas::FillYUV(uint8_t yValue, uint8_t uValue, uint8_t vValue) {
    for (int32_t y = 0; y < mYUVImage.height(); ++y) {
        for (int32_t x = 0; x < mYUVImage.width(); ++x) {
            mYUVImage.setPixelValue(x, y, yValue, uValue, vValue);
        }
    }
}

void YUVCanvas::FillYUVRectangle(const Rect& rect,
        uint8_t yValue, uint8_t uValue, uint8_t vValue) {
    for (int32_t y = rect.top; y < rect.bottom; ++y) {
        for (int32_t x = rect.left; x < rect.right; ++x) {
            mYUVImage.setPixelValue(x, y, yValue, uValue, vValue);
        }
    }
}

void YUVCanvas::CopyImageRect(
        const Rect& srcRect,
        int32_t destStartX, int32_t destStartY,
        const YUVImage &srcImage) {

    // Try fast copy first
    if (YUVImage::fastCopyRectangle(
                srcRect,
                destStartX, destStartY,
                srcImage, mYUVImage)) {
        return;
    }

    int32_t srcStartX = srcRect.left;
    int32_t srcStartY = srcRect.top;
    for (int32_t offsetY = 0; offsetY < srcRect.height(); ++offsetY) {
        for (int32_t offsetX = 0; offsetX < srcRect.width(); ++offsetX) {
            int32_t srcX = srcStartX + offsetX;
            int32_t srcY = srcStartY + offsetY;

            int32_t destX = destStartX + offsetX;
            int32_t destY = destStartY + offsetY;

            uint8_t yValue;
            uint8_t uValue;
            uint8_t vValue;

            srcImage.getPixelValue(srcX, srcY, &yValue, &uValue, &vValue);
            mYUVImage.setPixelValue(destX, destY, yValue, uValue, vValue);
        }
    }
}

void YUVCanvas::downsample(
        int32_t srcOffsetX, int32_t srcOffsetY,
        int32_t skipX, int32_t skipY,
        const YUVImage &srcImage) {
    // TODO: Add a low pass filter for downsampling.

    // Check that srcImage is big enough to fill mYUVImage.
    CHECK((srcOffsetX + (mYUVImage.width() - 1) * skipX) < srcImage.width());
    CHECK((srcOffsetY + (mYUVImage.height() - 1) * skipY) < srcImage.height());

    uint8_t yValue;
    uint8_t uValue;
    uint8_t vValue;

    int32_t srcY = srcOffsetY;
    for (int32_t y = 0; y < mYUVImage.height(); ++y) {
        int32_t srcX = srcOffsetX;
        for (int32_t x = 0; x < mYUVImage.width(); ++x) {
            srcImage.getPixelValue(srcX, srcY, &yValue, &uValue, &vValue);
            mYUVImage.setPixelValue(x, y, yValue, uValue, vValue);

            srcX += skipX;
        }
        srcY += skipY;
    }
}

}  // namespace android
