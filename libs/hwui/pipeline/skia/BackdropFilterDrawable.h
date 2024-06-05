/*
 * Copyright (C) 2022 The Android Open Source Project
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

#pragma once

#include <SkCanvas.h>
#include <SkDrawable.h>
#include <SkPaint.h>

namespace android {
namespace uirenderer {

class RenderNode;
class RenderProperties;

namespace skiapipeline {

/**
 * This drawable captures it's backdrop content and render it with a
 * image filter.
 */
class BackdropFilterDrawable : public SkDrawable {
public:
    BackdropFilterDrawable(RenderNode* renderNode, SkCanvas* canvas)
            : mTargetRenderNode(renderNode), mBounds(canvas->getLocalClipBounds()) {}

    ~BackdropFilterDrawable();

private:
    RenderNode* mTargetRenderNode;
    SkPaint mPaint;

    SkRect mDstBounds;
    SkRect mImageSubset;
    SkIRect mOutSubset;
    SkIPoint mOutOffset;

    /**
     * Check all necessary properties before actual drawing.
     * Return true if ready to draw.
     */
    bool prepareToDraw(SkCanvas* canvas, const RenderProperties& properties, int backdropImageWidth,
                       int backdropImageHeight);

protected:
    void onDraw(SkCanvas* canvas) override;

    virtual SkRect onGetBounds() override { return mBounds; }
    const SkRect mBounds;
};

}  // namespace skiapipeline
}  // namespace uirenderer
}  // namespace android
