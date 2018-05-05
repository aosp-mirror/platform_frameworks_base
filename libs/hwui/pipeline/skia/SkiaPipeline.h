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

#include <SkSurface.h>
#include "Lighting.h"
#include "hwui/AnimatedImageDrawable.h"
#include "renderthread/CanvasContext.h"
#include "renderthread/IRenderPipeline.h"

class SkPictureRecorder;

namespace android {
namespace uirenderer {
namespace skiapipeline {

class SkiaPipeline : public renderthread::IRenderPipeline {
public:
    SkiaPipeline(renderthread::RenderThread& thread);
    virtual ~SkiaPipeline();

    TaskManager* getTaskManager() override;

    void onDestroyHardwareResources() override;

    bool pinImages(std::vector<SkImage*>& mutableImages) override;
    bool pinImages(LsaVector<sk_sp<Bitmap>>& images) override { return false; }
    void unpinImages() override;
    void onPrepareTree() override;

    void renderLayers(const LightGeometry& lightGeometry,
                      LayerUpdateQueue* layerUpdateQueue, bool opaque, bool wideColorGamut,
                      const LightInfo& lightInfo) override;

    bool createOrUpdateLayer(RenderNode* node, const DamageAccumulator& damageAccumulator,
                             bool wideColorGamut, ErrorHandler* errorHandler) override;

    void renderFrame(const LayerUpdateQueue& layers, const SkRect& clip,
                     const std::vector<sp<RenderNode>>& nodes, bool opaque, bool wideColorGamut,
                     const Rect& contentDrawBounds, sk_sp<SkSurface> surface);

    std::vector<VectorDrawableRoot*>* getVectorDrawables() { return &mVectorDrawables; }

    static void destroyLayer(RenderNode* node);

    static void prepareToDraw(const renderthread::RenderThread& thread, Bitmap* bitmap);

    void renderLayersImpl(const LayerUpdateQueue& layers, bool opaque, bool wideColorGamut);

    static float getLightRadius() {
        if (CC_UNLIKELY(Properties::overrideLightRadius > 0)) {
            return Properties::overrideLightRadius;
        }
        return mLightRadius;
    }

    static uint8_t getAmbientShadowAlpha() {
        if (CC_UNLIKELY(Properties::overrideAmbientShadowStrength >= 0)) {
            return Properties::overrideAmbientShadowStrength;
        }
        return mAmbientShadowAlpha;
    }

    static uint8_t getSpotShadowAlpha() {
        if (CC_UNLIKELY(Properties::overrideSpotShadowStrength >= 0)) {
            return Properties::overrideSpotShadowStrength;
        }
        return mSpotShadowAlpha;
    }

    static Vector3 getLightCenter() {
        if (CC_UNLIKELY(Properties::overrideLightPosY > 0 || Properties::overrideLightPosZ > 0)) {
            Vector3 adjustedLightCenter = mLightCenter;
            if (CC_UNLIKELY(Properties::overrideLightPosY > 0)) {
                // negated since this shifts up
                adjustedLightCenter.y = -Properties::overrideLightPosY;
            }
            if (CC_UNLIKELY(Properties::overrideLightPosZ > 0)) {
                adjustedLightCenter.z = Properties::overrideLightPosZ;
            }
            return adjustedLightCenter;
        }
        return mLightCenter;
    }

    static void updateLighting(const LightGeometry& lightGeometry,
                               const LightInfo& lightInfo) {
        mLightRadius = lightGeometry.radius;
        mAmbientShadowAlpha = lightInfo.ambientShadowAlpha;
        mSpotShadowAlpha = lightInfo.spotShadowAlpha;
        mLightCenter = lightGeometry.center;
    }

protected:
    void dumpResourceCacheUsage() const;

    renderthread::RenderThread& mRenderThread;

private:
    void renderFrameImpl(const LayerUpdateQueue& layers, const SkRect& clip,
                         const std::vector<sp<RenderNode>>& nodes, bool opaque, bool wideColorGamut,
                         const Rect& contentDrawBounds, SkCanvas* canvas);

    /**
     *  Debugging feature.  Draws a semi-transparent overlay on each pixel, indicating
     *  how many times it has been drawn.
     */
    void renderOverdraw(const LayerUpdateQueue& layers, const SkRect& clip,
                        const std::vector<sp<RenderNode>>& nodes, const Rect& contentDrawBounds,
                        sk_sp<SkSurface>);

    /**
     *  Render mVectorDrawables into offscreen buffers.
     */
    void renderVectorDrawableCache();

    SkCanvas* tryCapture(SkSurface* surface);
    void endCapture(SkSurface* surface);

    std::vector<sk_sp<SkImage>> mPinnedImages;

    /**
     *  populated by prepareTree with dirty VDs
     */
    std::vector<VectorDrawableRoot*> mVectorDrawables;

    // Block of properties used only for debugging to record a SkPicture and save it in a file.
    /**
     * mCapturedFile is used to enforce we don't capture more than once for a given name (cause
     * permissions don't allow to reset a property from render thread).
     */
    std::string mCapturedFile;
    /**
     *  mCaptureSequence counts how many frames are left to take in the sequence.
     */
    int mCaptureSequence = 0;
    /**
     *  mSavePictureProcessor is used to run the file saving code in a separate thread.
     */
    class SavePictureProcessor;
    sp<SavePictureProcessor> mSavePictureProcessor;
    /**
     *  mRecorder holds the current picture recorder. We could store it on the stack to support
     *  parallel tryCapture calls (not really needed).
     */
    std::unique_ptr<SkPictureRecorder> mRecorder;

    static float mLightRadius;
    static uint8_t mAmbientShadowAlpha;
    static uint8_t mSpotShadowAlpha;
    static Vector3 mLightCenter;
};

} /* namespace skiapipeline */
} /* namespace uirenderer */
} /* namespace android */
