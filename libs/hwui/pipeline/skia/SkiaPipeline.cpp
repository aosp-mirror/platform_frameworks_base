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

#include "SkiaPipeline.h"

#include <SkImageEncoder.h>
#include <SkImagePriv.h>
#include <SkOverdrawCanvas.h>
#include <SkOverdrawColorFilter.h>
#include <SkPicture.h>
#include <SkPictureRecorder.h>
#include "TreeInfo.h"
#include "VectorDrawable.h"
#include "utils/TraceUtils.h"

#include <unistd.h>

using namespace android::uirenderer::renderthread;

namespace android {
namespace uirenderer {
namespace skiapipeline {

float SkiaPipeline::mLightRadius = 0;
uint8_t SkiaPipeline::mAmbientShadowAlpha = 0;
uint8_t SkiaPipeline::mSpotShadowAlpha = 0;

Vector3 SkiaPipeline::mLightCenter = {FLT_MIN, FLT_MIN, FLT_MIN};

SkiaPipeline::SkiaPipeline(RenderThread& thread) : mRenderThread(thread) {
    mVectorDrawables.reserve(30);
}

SkiaPipeline::~SkiaPipeline() {
    unpinImages();
}

TaskManager* SkiaPipeline::getTaskManager() {
    return mRenderThread.cacheManager().getTaskManager();
}

void SkiaPipeline::onDestroyHardwareResources() {
    unpinImages();
    mRenderThread.cacheManager().trimStaleResources();
}

bool SkiaPipeline::pinImages(std::vector<SkImage*>& mutableImages) {
    for (SkImage* image : mutableImages) {
        if (SkImage_pinAsTexture(image, mRenderThread.getGrContext())) {
            mPinnedImages.emplace_back(sk_ref_sp(image));
        } else {
            return false;
        }
    }
    return true;
}

void SkiaPipeline::unpinImages() {
    for (auto& image : mPinnedImages) {
        SkImage_unpinAsTexture(image.get(), mRenderThread.getGrContext());
    }
    mPinnedImages.clear();
}

void SkiaPipeline::onPrepareTree() {
    // The only time mVectorDrawables is not empty is if prepare tree was called 2 times without
    // a renderFrame in the middle.
    mVectorDrawables.clear();
}

void SkiaPipeline::renderLayers(const FrameBuilder::LightGeometry& lightGeometry,
                                LayerUpdateQueue* layerUpdateQueue, bool opaque,
                                bool wideColorGamut, const BakedOpRenderer::LightInfo& lightInfo) {
    updateLighting(lightGeometry, lightInfo);
    ATRACE_NAME("draw layers");
    renderVectorDrawableCache();
    renderLayersImpl(*layerUpdateQueue, opaque, wideColorGamut);
    layerUpdateQueue->clear();
}

void SkiaPipeline::renderLayersImpl(const LayerUpdateQueue& layers, bool opaque,
                                    bool wideColorGamut) {
    sk_sp<GrContext> cachedContext;

    // Render all layers that need to be updated, in order.
    for (size_t i = 0; i < layers.entries().size(); i++) {
        RenderNode* layerNode = layers.entries()[i].renderNode.get();
        // only schedule repaint if node still on layer - possible it may have been
        // removed during a dropped frame, but layers may still remain scheduled so
        // as not to lose info on what portion is damaged
        if (CC_LIKELY(layerNode->getLayerSurface() != nullptr)) {
            SkASSERT(layerNode->getLayerSurface());
            SkASSERT(layerNode->getDisplayList()->isSkiaDL());
            SkiaDisplayList* displayList = (SkiaDisplayList*)layerNode->getDisplayList();
            if (!displayList || displayList->isEmpty()) {
                SkDEBUGF(("%p drawLayers(%s) : missing drawable", layerNode, layerNode->getName()));
                return;
            }

            const Rect& layerDamage = layers.entries()[i].damage;

            SkCanvas* layerCanvas = tryCapture(layerNode->getLayerSurface());

            int saveCount = layerCanvas->save();
            SkASSERT(saveCount == 1);

            layerCanvas->androidFramework_setDeviceClipRestriction(layerDamage.toSkIRect());

            auto savedLightCenter = mLightCenter;
            // map current light center into RenderNode's coordinate space
            layerNode->getSkiaLayer()->inverseTransformInWindow.mapPoint3d(mLightCenter);

            const RenderProperties& properties = layerNode->properties();
            const SkRect bounds = SkRect::MakeWH(properties.getWidth(), properties.getHeight());
            if (properties.getClipToBounds() && layerCanvas->quickReject(bounds)) {
                return;
            }

            ATRACE_FORMAT("drawLayer [%s] %.1f x %.1f", layerNode->getName(), bounds.width(),
                          bounds.height());

            layerNode->getSkiaLayer()->hasRenderedSinceRepaint = false;
            layerCanvas->clear(SK_ColorTRANSPARENT);

            RenderNodeDrawable root(layerNode, layerCanvas, false);
            root.forceDraw(layerCanvas);
            layerCanvas->restoreToCount(saveCount);
            mLightCenter = savedLightCenter;

            endCapture(layerNode->getLayerSurface());

            // cache the current context so that we can defer flushing it until
            // either all the layers have been rendered or the context changes
            GrContext* currentContext = layerNode->getLayerSurface()->getCanvas()->getGrContext();
            if (cachedContext.get() != currentContext) {
                if (cachedContext.get()) {
                    cachedContext->flush();
                }
                cachedContext.reset(SkSafeRef(currentContext));
            }
        }
    }

    if (cachedContext.get()) {
        cachedContext->flush();
    }
}

bool SkiaPipeline::createOrUpdateLayer(RenderNode* node, const DamageAccumulator& damageAccumulator,
                                       bool wideColorGamut, ErrorHandler* errorHandler) {
    // compute the size of the surface (i.e. texture) to be allocated for this layer
    const int surfaceWidth = ceilf(node->getWidth() / float(LAYER_SIZE)) * LAYER_SIZE;
    const int surfaceHeight = ceilf(node->getHeight() / float(LAYER_SIZE)) * LAYER_SIZE;

    SkSurface* layer = node->getLayerSurface();
    if (!layer || layer->width() != surfaceWidth || layer->height() != surfaceHeight) {
        SkImageInfo info;
        if (wideColorGamut) {
            info = SkImageInfo::Make(surfaceWidth, surfaceHeight, kRGBA_F16_SkColorType,
                                     kPremul_SkAlphaType);
        } else {
            info = SkImageInfo::MakeN32Premul(surfaceWidth, surfaceHeight);
        }
        SkSurfaceProps props(0, kUnknown_SkPixelGeometry);
        SkASSERT(mRenderThread.getGrContext() != nullptr);
        node->setLayerSurface(SkSurface::MakeRenderTarget(mRenderThread.getGrContext(),
                                                          SkBudgeted::kYes, info, 0, &props));
        if (node->getLayerSurface()) {
            // update the transform in window of the layer to reset its origin wrt light source
            // position
            Matrix4 windowTransform;
            damageAccumulator.computeCurrentTransform(&windowTransform);
            node->getSkiaLayer()->inverseTransformInWindow = windowTransform;
        } else {
            String8 cachesOutput;
            mRenderThread.cacheManager().dumpMemoryUsage(cachesOutput,
                    &mRenderThread.renderState());
            ALOGE("%s", cachesOutput.string());
            if (errorHandler) {
                std::ostringstream err;
                err << "Unable to create layer for " << node->getName();
                const int maxTextureSize = DeviceInfo::get()->maxTextureSize();
                err << ", size " << info.width() << "x" << info.height() << " max size "
                    << maxTextureSize << " color type " << (int)info.colorType()
                    << " has context " << (int)(mRenderThread.getGrContext() != nullptr);
                errorHandler->onError(err.str());
            }
        }
        return true;
    }
    return false;
}

void SkiaPipeline::destroyLayer(RenderNode* node) {
    node->setLayerSurface(nullptr);
}

void SkiaPipeline::prepareToDraw(const RenderThread& thread, Bitmap* bitmap) {
    GrContext* context = thread.getGrContext();
    if (context) {
        ATRACE_FORMAT("Bitmap#prepareToDraw %dx%d", bitmap->width(), bitmap->height());
        sk_sp<SkColorFilter> colorFilter;
        auto image = bitmap->makeImage(&colorFilter);
        if (image.get() && !bitmap->isHardware()) {
            SkImage_pinAsTexture(image.get(), context);
            SkImage_unpinAsTexture(image.get(), context);
        }
    }
}

void SkiaPipeline::renderVectorDrawableCache() {
    if (!mVectorDrawables.empty()) {
        sp<VectorDrawableAtlas> atlas = mRenderThread.cacheManager().acquireVectorDrawableAtlas();
        auto grContext = mRenderThread.getGrContext();
        atlas->prepareForDraw(grContext);
        ATRACE_NAME("Update VectorDrawables");
        for (auto vd : mVectorDrawables) {
            vd->updateCache(atlas, grContext);
        }
        mVectorDrawables.clear();
    }
}

class SkiaPipeline::SavePictureProcessor : public TaskProcessor<bool> {
public:
    explicit SavePictureProcessor(TaskManager* taskManager) : TaskProcessor<bool>(taskManager) {}

    struct SavePictureTask : public Task<bool> {
        sk_sp<SkData> data;
        std::string filename;
    };

    void savePicture(const sk_sp<SkData>& data, const std::string& filename) {
        sp<SavePictureTask> task(new SavePictureTask());
        task->data = data;
        task->filename = filename;
        TaskProcessor<bool>::add(task);
    }

    virtual void onProcess(const sp<Task<bool>>& task) override {
        SavePictureTask* t = static_cast<SavePictureTask*>(task.get());

        if (0 == access(t->filename.c_str(), F_OK)) {
            task->setResult(false);
            return;
        }

        SkFILEWStream stream(t->filename.c_str());
        if (stream.isValid()) {
            stream.write(t->data->data(), t->data->size());
            stream.flush();
            SkDebugf("SKP Captured Drawing Output (%d bytes) for frame. %s", stream.bytesWritten(),
                     t->filename.c_str());
        }

        task->setResult(true);
    }
};

SkCanvas* SkiaPipeline::tryCapture(SkSurface* surface) {
    if (CC_UNLIKELY(Properties::skpCaptureEnabled)) {
        bool recordingPicture = mCaptureSequence > 0;
        char prop[PROPERTY_VALUE_MAX] = {'\0'};
        if (!recordingPicture) {
            property_get(PROPERTY_CAPTURE_SKP_FILENAME, prop, "0");
            recordingPicture = prop[0] != '0' &&
                               mCapturedFile != prop;  // ensure we capture only once per filename
            if (recordingPicture) {
                mCapturedFile = prop;
                mCaptureSequence = property_get_int32(PROPERTY_CAPTURE_SKP_FRAMES, 1);
            }
        }
        if (recordingPicture) {
            mRecorder.reset(new SkPictureRecorder());
            return mRecorder->beginRecording(surface->width(), surface->height(), nullptr,
                                             SkPictureRecorder::kPlaybackDrawPicture_RecordFlag);
        }
    }
    return surface->getCanvas();
}

void SkiaPipeline::endCapture(SkSurface* surface) {
    if (CC_UNLIKELY(mRecorder.get())) {
        sk_sp<SkPicture> picture = mRecorder->finishRecordingAsPicture();
        surface->getCanvas()->drawPicture(picture);
        if (picture->approximateOpCount() > 0) {
            auto data = picture->serialize();

            // offload saving to file in a different thread
            if (!mSavePictureProcessor.get()) {
                TaskManager* taskManager = getTaskManager();
                mSavePictureProcessor = new SavePictureProcessor(
                        taskManager->canRunTasks() ? taskManager : nullptr);
            }
            if (1 == mCaptureSequence) {
                mSavePictureProcessor->savePicture(data, mCapturedFile);
            } else {
                mSavePictureProcessor->savePicture(
                        data,
                        mCapturedFile + "_" + std::to_string(mCaptureSequence));
            }
            mCaptureSequence--;
        }
        mRecorder.reset();
    }
}

void SkiaPipeline::renderFrame(const LayerUpdateQueue& layers, const SkRect& clip,
                               const std::vector<sp<RenderNode>>& nodes, bool opaque,
                               bool wideColorGamut, const Rect& contentDrawBounds,
                               sk_sp<SkSurface> surface) {
    renderVectorDrawableCache();

    // draw all layers up front
    renderLayersImpl(layers, opaque, wideColorGamut);

    // initialize the canvas for the current frame, that might be a recording canvas if SKP
    // capture is enabled.
    std::unique_ptr<SkPictureRecorder> recorder;
    SkCanvas* canvas = tryCapture(surface.get());

    renderFrameImpl(layers, clip, nodes, opaque, wideColorGamut, contentDrawBounds, canvas);

    endCapture(surface.get());

    if (CC_UNLIKELY(Properties::debugOverdraw)) {
        renderOverdraw(layers, clip, nodes, contentDrawBounds, surface);
    }

    ATRACE_NAME("flush commands");
    surface->getCanvas()->flush();
}

namespace {
static Rect nodeBounds(RenderNode& node) {
    auto& props = node.properties();
    return Rect(props.getLeft(), props.getTop(), props.getRight(), props.getBottom());
}
}

void SkiaPipeline::renderFrameImpl(const LayerUpdateQueue& layers, const SkRect& clip,
                                   const std::vector<sp<RenderNode>>& nodes, bool opaque,
                                   bool wideColorGamut, const Rect& contentDrawBounds,
                                   SkCanvas* canvas) {
    SkAutoCanvasRestore saver(canvas, true);
    canvas->androidFramework_setDeviceClipRestriction(clip.roundOut());

    // STOPSHIP: Revert, temporary workaround to clear always F16 frame buffer for b/74976293
    if (!opaque || wideColorGamut) {
        canvas->clear(SK_ColorTRANSPARENT);
    }

    if (1 == nodes.size()) {
        if (!nodes[0]->nothingToDraw()) {
            RenderNodeDrawable root(nodes[0].get(), canvas);
            root.draw(canvas);
        }
    } else if (0 == nodes.size()) {
        // nothing to draw
    } else {
        // It there are multiple render nodes, they are laid out as follows:
        // #0 - backdrop (content + caption)
        // #1 - content (local bounds are at (0,0), will be translated and clipped to backdrop)
        // #2 - additional overlay nodes
        // Usually the backdrop cannot be seen since it will be entirely covered by the content.
        // While
        // resizing however it might become partially visible. The following render loop will crop
        // the
        // backdrop against the content and draw the remaining part of it. It will then draw the
        // content
        // cropped to the backdrop (since that indicates a shrinking of the window).
        //
        // Additional nodes will be drawn on top with no particular clipping semantics.

        // Usually the contents bounds should be mContentDrawBounds - however - we will
        // move it towards the fixed edge to give it a more stable appearance (for the moment).
        // If there is no content bounds we ignore the layering as stated above and start with 2.

        // Backdrop bounds in render target space
        const Rect backdrop = nodeBounds(*nodes[0]);

        // Bounds that content will fill in render target space (note content node bounds may be
        // bigger)
        Rect content(contentDrawBounds.getWidth(), contentDrawBounds.getHeight());
        content.translate(backdrop.left, backdrop.top);
        if (!content.contains(backdrop) && !nodes[0]->nothingToDraw()) {
            // Content doesn't entirely overlap backdrop, so fill around content (right/bottom)

            // Note: in the future, if content doesn't snap to backdrop's left/top, this may need to
            // also fill left/top. Currently, both 2up and freeform position content at the top/left
            // of
            // the backdrop, so this isn't necessary.
            RenderNodeDrawable backdropNode(nodes[0].get(), canvas);
            if (content.right < backdrop.right) {
                // draw backdrop to right side of content
                SkAutoCanvasRestore acr(canvas, true);
                canvas->clipRect(SkRect::MakeLTRB(content.right, backdrop.top, backdrop.right,
                                                  backdrop.bottom));
                backdropNode.draw(canvas);
            }
            if (content.bottom < backdrop.bottom) {
                // draw backdrop to bottom of content
                // Note: bottom fill uses content left/right, to avoid overdrawing left/right fill
                SkAutoCanvasRestore acr(canvas, true);
                canvas->clipRect(SkRect::MakeLTRB(content.left, content.bottom, content.right,
                                                  backdrop.bottom));
                backdropNode.draw(canvas);
            }
        }

        RenderNodeDrawable contentNode(nodes[1].get(), canvas);
        if (!backdrop.isEmpty()) {
            // content node translation to catch up with backdrop
            float dx = backdrop.left - contentDrawBounds.left;
            float dy = backdrop.top - contentDrawBounds.top;

            SkAutoCanvasRestore acr(canvas, true);
            canvas->translate(dx, dy);
            const SkRect contentLocalClip =
                    SkRect::MakeXYWH(contentDrawBounds.left, contentDrawBounds.top,
                                     backdrop.getWidth(), backdrop.getHeight());
            canvas->clipRect(contentLocalClip);
            contentNode.draw(canvas);
        } else {
            SkAutoCanvasRestore acr(canvas, true);
            contentNode.draw(canvas);
        }

        // remaining overlay nodes, simply defer
        for (size_t index = 2; index < nodes.size(); index++) {
            if (!nodes[index]->nothingToDraw()) {
                SkAutoCanvasRestore acr(canvas, true);
                RenderNodeDrawable overlayNode(nodes[index].get(), canvas);
                overlayNode.draw(canvas);
            }
        }
    }
}

void SkiaPipeline::dumpResourceCacheUsage() const {
    int resources, maxResources;
    size_t bytes, maxBytes;
    mRenderThread.getGrContext()->getResourceCacheUsage(&resources, &bytes);
    mRenderThread.getGrContext()->getResourceCacheLimits(&maxResources, &maxBytes);

    SkString log("Resource Cache Usage:\n");
    log.appendf("%8d items out of %d maximum items\n", resources, maxResources);
    log.appendf("%8zu bytes (%.2f MB) out of %.2f MB maximum\n", bytes,
                bytes * (1.0f / (1024.0f * 1024.0f)), maxBytes * (1.0f / (1024.0f * 1024.0f)));

    ALOGD("%s", log.c_str());
}

// Overdraw debugging

// These colors should be kept in sync with Caches::getOverdrawColor() with a few differences.
// This implementation:
// (1) Requires transparent entries for "no overdraw" and "single draws".
// (2) Requires premul colors (instead of unpremul).
// (3) Requires RGBA colors (instead of BGRA).
static const uint32_t kOverdrawColors[2][6] = {
        {
                0x00000000, 0x00000000, 0x2f2f0000, 0x2f002f00, 0x3f00003f, 0x7f00007f,
        },
        {
                0x00000000, 0x00000000, 0x2f2f0000, 0x4f004f4f, 0x5f50335f, 0x7f00007f,
        },
};

void SkiaPipeline::renderOverdraw(const LayerUpdateQueue& layers, const SkRect& clip,
                                  const std::vector<sp<RenderNode>>& nodes,
                                  const Rect& contentDrawBounds, sk_sp<SkSurface> surface) {
    // Set up the overdraw canvas.
    SkImageInfo offscreenInfo = SkImageInfo::MakeA8(surface->width(), surface->height());
    sk_sp<SkSurface> offscreen = surface->makeSurface(offscreenInfo);
    SkOverdrawCanvas overdrawCanvas(offscreen->getCanvas());

    // Fake a redraw to replay the draw commands.  This will increment the alpha channel
    // each time a pixel would have been drawn.
    // Pass true for opaque so we skip the clear - the overdrawCanvas is already zero
    // initialized.
    renderFrameImpl(layers, clip, nodes, true, false, contentDrawBounds, &overdrawCanvas);
    sk_sp<SkImage> counts = offscreen->makeImageSnapshot();

    // Draw overdraw colors to the canvas.  The color filter will convert counts to colors.
    SkPaint paint;
    const SkPMColor* colors = kOverdrawColors[static_cast<int>(Properties::overdrawColorSet)];
    paint.setColorFilter(SkOverdrawColorFilter::Make(colors));
    surface->getCanvas()->drawImage(counts.get(), 0.0f, 0.0f, &paint);
}

} /* namespace skiapipeline */
} /* namespace uirenderer */
} /* namespace android */
