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

// YUVCanvas holds a reference to a YUVImage on which it can do various
// drawing operations. It provides various utility functions for filling,
// cropping, etc.


#ifndef YUV_CANVAS_H_

#define YUV_CANVAS_H_

#include <stdint.h>

namespace android {

class YUVImage;
class Rect;

class YUVCanvas {
public:

    // Constructor takes in reference to a yuvImage on which it can do
    // various drawing opreations.
    YUVCanvas(YUVImage &yuvImage);
    ~YUVCanvas();

    // Fills the entire image with the given YUV values.
    void FillYUV(uint8_t yValue, uint8_t uValue, uint8_t vValue);

    // Fills the rectangular region [startX,endX]x[startY,endY] with the given YUV values.
    void FillYUVRectangle(const Rect& rect,
            uint8_t yValue, uint8_t uValue, uint8_t vValue);

    // Copies the region [startX,endX]x[startY,endY] from srcImage into the
    // canvas' target image (mYUVImage) starting at
    // (destinationStartX,destinationStartY).
    // Note that undefined behavior may occur if srcImage is same as the canvas'
    // target image.
    void CopyImageRect(
            const Rect& srcRect,
            int32_t destStartX, int32_t destStartY,
            const YUVImage &srcImage);

    // Downsamples the srcImage into the canvas' target image (mYUVImage)
    // The downsampling copies pixels from the source image starting at
    // (srcOffsetX, srcOffsetY) to the target image, starting at (0, 0).
    // For each X increment in the target image, skipX pixels are skipped
    // in the source image.
    // Similarly for each Y increment in the target image, skipY pixels
    // are skipped in the source image.
    void downsample(
            int32_t srcOffsetX, int32_t srcOffsetY,
            int32_t skipX, int32_t skipY,
            const YUVImage &srcImage);

private:
    YUVImage& mYUVImage;

    YUVCanvas(const YUVCanvas &);
    YUVCanvas &operator=(const YUVCanvas &);
};

}  // namespace android

#endif  // YUV_CANVAS_H_
