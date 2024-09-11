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

#pragma once

#include "pipeline/skia/SkiaPipeline.h"

namespace android {
namespace uirenderer {
namespace skiapipeline {

class SkiaGpuPipeline : public SkiaPipeline {
public:
    SkiaGpuPipeline(renderthread::RenderThread& thread);
    virtual ~SkiaGpuPipeline();

    virtual GrSurfaceOrigin getSurfaceOrigin() = 0;

    // If the given node didn't have a layer surface, or had one of the wrong size, this method
    // creates a new one and returns true. Otherwise does nothing and returns false.
    bool createOrUpdateLayer(RenderNode* node, const DamageAccumulator& damageAccumulator,
                             ErrorHandler* errorHandler) override;

    bool pinImages(std::vector<SkImage*>& mutableImages) override;
    bool pinImages(LsaVector<sk_sp<Bitmap>>& images) override { return false; }
    void unpinImages() override;
    void renderLayersImpl(const LayerUpdateQueue& layers, bool opaque) override;
    void setHardwareBuffer(AHardwareBuffer* hardwareBuffer) override;
    bool hasHardwareBuffer() override { return mHardwareBuffer != nullptr; }

    static void prepareToDraw(const renderthread::RenderThread& thread, Bitmap* bitmap);

protected:
    sk_sp<SkSurface> getBufferSkSurface(
            const renderthread::HardwareBufferRenderParams& bufferParams);
    void dumpResourceCacheUsage() const;

    AHardwareBuffer* mHardwareBuffer = nullptr;

private:
    std::vector<sk_sp<SkImage>> mPinnedImages;
};

} /* namespace skiapipeline */
} /* namespace uirenderer */
} /* namespace android */
