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

#include <SkColorSpace.h>
#include <SkDocument.h>
#include <SkSurface.h>

#include "Lighting.h"
#include "hwui/AnimatedImageDrawable.h"
#include "renderthread/CanvasContext.h"
#include "renderthread/HardwareBufferRenderParams.h"
#include "renderthread/IRenderPipeline.h"

class SkFILEWStream;
class SkPictureRecorder;
struct SkSharingSerialContext;

namespace android {
namespace uirenderer {
namespace skiapipeline {

class SkiaPipeline : public renderthread::IRenderPipeline {
public:
    explicit SkiaPipeline(renderthread::RenderThread& thread);
    virtual ~SkiaPipeline();

    void onDestroyHardwareResources() override;

    void renderLayers(const LightGeometry& lightGeometry, LayerUpdateQueue* layerUpdateQueue,
                      bool opaque, const LightInfo& lightInfo) override;

    void setSurfaceColorProperties(ColorMode colorMode) override;
    SkColorType getSurfaceColorType() const override { return mSurfaceColorType; }
    sk_sp<SkColorSpace> getSurfaceColorSpace() override { return mSurfaceColorSpace; }

    void renderFrame(const LayerUpdateQueue& layers, const SkRect& clip,
                     const std::vector<sp<RenderNode>>& nodes, bool opaque,
                     const Rect& contentDrawBounds, sk_sp<SkSurface> surface,
                     const SkMatrix& preTransform);

    bool renderLayerImpl(RenderNode* layerNode, const Rect& layerDamage);
    virtual void renderLayersImpl(const LayerUpdateQueue& layers, bool opaque) = 0;

    // Sets the recording callback to the provided function and the recording mode
    // to CallbackAPI
    void setPictureCapturedCallback(
            const std::function<void(sk_sp<SkPicture>&&)>& callback) override {
        mPictureCapturedCallback = callback;
        mCaptureMode = callback ? CaptureMode::CallbackAPI : CaptureMode::None;
    }

    void setTargetSdrHdrRatio(float ratio) override;

protected:
    renderthread::RenderThread& mRenderThread;

    sk_sp<SkSurface> mBufferSurface = nullptr;
    sk_sp<SkColorSpace> mBufferColorSpace = nullptr;

    ColorMode mColorMode = ColorMode::Default;
    SkColorType mSurfaceColorType;
    sk_sp<SkColorSpace> mSurfaceColorSpace;
    float mTargetSdrHdrRatio = 1.f;

    bool isCapturingSkp() const { return mCaptureMode != CaptureMode::None; }

private:
    void renderFrameImpl(const SkRect& clip,
                         const std::vector<sp<RenderNode>>& nodes, bool opaque,
                         const Rect& contentDrawBounds, SkCanvas* canvas,
                         const SkMatrix& preTransform);

    /**
     *  Debugging feature.  Draws a semi-transparent overlay on each pixel, indicating
     *  how many times it has been drawn.
     */
    void renderOverdraw(const SkRect& clip,
                        const std::vector<sp<RenderNode>>& nodes, const Rect& contentDrawBounds,
                        sk_sp<SkSurface> surface, const SkMatrix& preTransform);

    // Called every frame. Normally returns early with screen canvas.
    // But when capture is enabled, returns an nwaycanvas where commands are also recorded.
    SkCanvas* tryCapture(SkSurface* surface, RenderNode* root, const LayerUpdateQueue& dirtyLayers);
    // Called at the end of every frame, closes the recording if necessary.
    void endCapture(SkSurface* surface);
    // Determine if a new file-based capture should be started.
    // If so, sets mCapturedFile and mCaptureSequence and returns true.
    // Should be called every frame when capture is enabled.
    // sets mCaptureMode.
    bool shouldStartNewFileCapture();
    // Set up a multi frame capture.
    bool setupMultiFrameCapture();

    // Block of properties used only for debugging to record a SkPicture and save it in a file.
    // There are three possible ways of recording drawing commands.
    enum class CaptureMode {
        // return to this mode when capture stops.
        None,
        // A mode where every frame is recorded into an SkPicture and sent to a provided callback,
        // until that callback is cleared
        CallbackAPI,
        // A mode where a finite number of frames are recorded to a file with
        // SkMultiPictureDocument
        MultiFrameSKP,
        // A mode which records a single frame to a normal SKP file.
        SingleFrameSKP,
    };
  CaptureMode mCaptureMode = CaptureMode::None;

    /**
     * mCapturedFile - the filename to write a recorded SKP to in either MultiFrameSKP or
     * SingleFrameSKP mode.
     */
    std::string mCapturedFile;
    /**
     * mCaptureSequence counts down how many frames are left to take in the sequence. Applicable
     * only to MultiFrameSKP or SingleFrameSKP mode.
     */
    int mCaptureSequence = 0;

    // Multi frame serialization stream and writer used when serializing more than one frame.
    std::unique_ptr<SkSharingSerialContext> mSerialContext;  // Must be declared before any other
                                                             // serializing member
    std::unique_ptr<SkFILEWStream> mOpenMultiPicStream;
    sk_sp<SkDocument> mMultiPic;

    /**
     * mRecorder holds the current picture recorder when serializing in either SingleFrameSKP or
     * CallbackAPI modes.
     */
    std::unique_ptr<SkPictureRecorder> mRecorder;
    std::unique_ptr<SkNWayCanvas> mNwayCanvas;

    // Set by setPictureCapturedCallback and when set, CallbackAPI mode recording is ongoing.
    // Not used in other recording modes.
    std::function<void(sk_sp<SkPicture>&&)> mPictureCapturedCallback;
};

} /* namespace skiapipeline */
} /* namespace uirenderer */
} /* namespace android */
