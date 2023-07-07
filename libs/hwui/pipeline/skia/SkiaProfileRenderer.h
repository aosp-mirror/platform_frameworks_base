/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "IProfileRenderer.h"

#include "SkCanvas.h"

namespace android {
namespace uirenderer {

class SkiaProfileRenderer : public IProfileRenderer {
public:
    explicit SkiaProfileRenderer(SkCanvas* canvas, uint32_t width, uint32_t height)
            : mCanvas(canvas), mWidth(width), mHeight(height) {}

    void drawRect(float left, float top, float right, float bottom, const SkPaint& paint) override;
    void drawRects(const float* rects, int count, const SkPaint& paint) override;
    uint32_t getViewportWidth() override { return mWidth; }
    uint32_t getViewportHeight() override { return mHeight; }

    virtual ~SkiaProfileRenderer() {}

private:
    // Does not have ownership.
    SkCanvas* mCanvas;
    uint32_t mWidth;
    uint32_t mHeight;
};

} /* namespace uirenderer */
} /* namespace android */
