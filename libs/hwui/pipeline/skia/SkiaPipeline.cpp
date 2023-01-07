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

#include <SkCanvas.h>
#include <SkColor.h>
#include <SkColorSpace.h>
#include <SkData.h>
#include <SkImage.h>
#include <SkImageEncoder.h>
#include <SkImageInfo.h>
#include <SkImagePriv.h>
#include <SkMatrix.h>
#include <SkMultiPictureDocument.h>
#include <SkOverdrawCanvas.h>
#include <SkOverdrawColorFilter.h>
#include <SkPicture.h>
#include <SkPictureRecorder.h>
#include <SkRect.h>
#include <SkRefCnt.h>
#include <SkSerialProcs.h>
#include <SkStream.h>
#include <SkString.h>
#include <SkTypeface.h>
#include <android-base/properties.h>
#include <unistd.h>

#include <sstream>

#include <gui/TraceUtils.h>
#include "LightingInfo.h"
#include "VectorDrawable.h"
#include "thread/CommonPool.h"
#include "tools/SkSharingProc.h"
#include "utils/Color.h"
#include "utils/String8.h"

using namespace android::uirenderer::renderthread;

namespace android {
namespace uirenderer {
namespace skiapipeline {

SkiaPipeline::SkiaPipeline(RenderThread& thread) : mRenderThread(thread) {
    setSurfaceColorProperties(mColorMode);
}

SkiaPipeline::~SkiaPipeline() {
    unpinImages();
}

void SkiaPipeline::onDestroyHardwareResources() {
    unpinImages();
    mRenderThread.cacheManager().trimStaleResources();
}

bool SkiaPipeline::pinImages(std::vector<SkImage*>& mutableImages) {
    if (!mRenderThread.getGrContext()) {
        ALOGD("Trying to pin an image with an invalid GrContext");
        return false;
    }
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

void SkiaPipeline::renderLayers(const LightGeometry& lightGeometry,
                                LayerUpdateQueue* layerUpdateQueue, bool opaque,
                                const LightInfo& lightInfo) {
    LightingInfo::updateLighting(lightGeometry, lightInfo);
    ATRACE_NAME("draw layers");
    renderLayersImpl(*layerUpdateQueue, opaque);
    layerUpdateQueue->clear();
}

void SkiaPipeline::renderLayersImpl(const LayerUpdateQueue& layers, bool opaque) {
    sk_sp<GrDirectContext> cachedContext;

    // Render all layers that need to be updated, in order.
    for (size_t i = 0; i < layers.entries().size(); i++) {
        RenderNode* layerNode = layers.entries()[i].renderNode.get();
        // only schedule repaint if node still on layer - possible it may have been
        // removed during a dropped frame, but layers may still remain scheduled so
        // as not to lose info on what portion is damaged
        if (CC_UNLIKELY(layerNode->getLayerSurface() == nullptr)) {
            continue;
        }
        SkASSERT(layerNode->getLayerSurface());
        SkiaDisplayList* displayList = layerNode->getDisplayList().asSkiaDl();
        if (!displayList || displayList->isEmpty()) {
            ALOGE("%p drawLayers(%s) : missing drawable", layerNode, layerNode->getName());
            return;
        }

        const Rect& layerDamage = layers.entries()[i].damage;

        SkCanvas* layerCanvas = layerNode->getLayerSurface()->getCanvas();

        int saveCount = layerCanvas->save();
        SkASSERT(saveCount == 1);

        layerCanvas->androidFramework_setDeviceClipRestriction(layerDamage.toSkIRect());

        // TODO: put localized light center calculation and storage to a drawable related code.
        // It does not seem right to store something localized in a global state
        // fix here and in recordLayers
        const Vector3 savedLightCenter(LightingInfo::getLightCenterRaw());
        Vector3 transformedLightCenter(savedLightCenter);
        // map current light center into RenderNode's coordinate space
        layerNode->getSkiaLayer()->inverseTransformInWindow.mapPoint3d(transformedLightCenter);
        LightingInfo::setLightCenterRaw(transformedLightCenter);

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

        LightingInfo::setLightCenterRaw(savedLightCenter);

        // cache the current context so that we can defer flushing it until
        // either all the layers have been rendered or the context changes
        GrDirectContext* currentContext =
            GrAsDirectContext(layerNode->getLayerSurface()->getCanvas()->recordingContext());
        if (cachedContext.get() != currentContext) {
            if (cachedContext.get()) {
                ATRACE_NAME("flush layers (context changed)");
                cachedContext->flushAndSubmit();
            }
            cachedContext.reset(SkSafeRef(currentContext));
        }
    }

    if (cachedContext.get()) {
        ATRACE_NAME("flush layers");
        cachedContext->flushAndSubmit();
    }
}

bool SkiaPipeline::createOrUpdateLayer(RenderNode* node, const DamageAccumulator& damageAccumulator,
                                       ErrorHandler* errorHandler) {
    // compute the size of the surface (i.e. texture) to be allocated for this layer
    const int surfaceWidth = ceilf(node->getWidth() / float(LAYER_SIZE)) * LAYER_SIZE;
    const int surfaceHeight = ceilf(node->getHeight() / float(LAYER_SIZE)) * LAYER_SIZE;

    SkSurface* layer = node->getLayerSurface();
    if (!layer || layer->width() != surfaceWidth || layer->height() != surfaceHeight) {
        SkImageInfo info;
        info = SkImageInfo::Make(surfaceWidth, surfaceHeight, getSurfaceColorType(),
                                 kPremul_SkAlphaType, getSurfaceColorSpace());
        SkSurfaceProps props(0, kUnknown_SkPixelGeometry);
        SkASSERT(mRenderThread.getGrContext() != nullptr);
        node->setLayerSurface(SkSurface::MakeRenderTarget(mRenderThread.getGrContext(),
                                                          SkBudgeted::kYes, info, 0,
                                                          this->getSurfaceOrigin(), &props));
        if (node->getLayerSurface()) {
            // update the transform in window of the layer to reset its origin wrt light source
            // position
            Matrix4 windowTransform;
            damageAccumulator.computeCurrentTransform(&windowTransform);
            node->getSkiaLayer()->inverseTransformInWindow.loadInverse(windowTransform);
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
                    << maxTextureSize << " color type " << (int)info.colorType() << " has context "
                    << (int)(mRenderThread.getGrContext() != nullptr);
                errorHandler->onError(err.str());
            }
        }
        return true;
    }
    return false;
}

void SkiaPipeline::prepareToDraw(const RenderThread& thread, Bitmap* bitmap) {
    GrDirectContext* context = thread.getGrContext();
    if (context && !bitmap->isHardware()) {
        ATRACE_FORMAT("Bitmap#prepareToDraw %dx%d", bitmap->width(), bitmap->height());
        auto image = bitmap->makeImage();
        if (image.get()) {
            SkImage_pinAsTexture(image.get(), context);
            SkImage_unpinAsTexture(image.get(), context);
            // A submit is necessary as there may not be a frame coming soon, so without a call
            // to submit these texture uploads can just sit in the queue building up until
            // we run out of RAM
            context->flushAndSubmit();
        }
    }
}

static void savePictureAsync(const sk_sp<SkData>& data, const std::string& filename) {
    CommonPool::post([data, filename] {
        if (0 == access(filename.c_str(), F_OK)) {
            return;
        }

        SkFILEWStream stream(filename.c_str());
        if (stream.isValid()) {
            stream.write(data->data(), data->size());
            stream.flush();
            ALOGD("SKP Captured Drawing Output (%zu bytes) for frame. %s", stream.bytesWritten(),
                     filename.c_str());
        }
    });
}

// Note multiple SkiaPipeline instances may be loaded if more than one app is visible.
// Each instance may observe the filename changing and try to record to a file of the same name.
// Only the first one will succeed. There is no scope available here where we could coordinate
// to cause this function to return true for only one of the instances.
bool SkiaPipeline::shouldStartNewFileCapture() {
    // Don't start a new file based capture if one is currently ongoing.
    if (mCaptureMode != CaptureMode::None) { return false; }

    // A new capture is started when the filename property changes.
    // Read the filename property.
    std::string prop = base::GetProperty(PROPERTY_CAPTURE_SKP_FILENAME, "0");
    // if the filename property changed to a valid value
    if (prop[0] != '0' && mCapturedFile != prop) {
        // remember this new filename
        mCapturedFile = prop;
        // and get a property indicating how many frames to capture.
        mCaptureSequence = base::GetIntProperty(PROPERTY_CAPTURE_SKP_FRAMES, 1);
        if (mCaptureSequence <= 0) {
            return false;
        } else if (mCaptureSequence == 1) {
            mCaptureMode = CaptureMode::SingleFrameSKP;
        } else {
            mCaptureMode = CaptureMode::MultiFrameSKP;
        }
        return true;
    }
    return false;
}

// performs the first-frame work of a multi frame SKP capture. Returns true if successful.
bool SkiaPipeline::setupMultiFrameCapture() {
    ALOGD("Set up multi-frame capture, frames = %d", mCaptureSequence);
    // We own this stream and need to hold it until close() finishes.
    auto stream = std::make_unique<SkFILEWStream>(mCapturedFile.c_str());
    if (stream->isValid()) {
        mOpenMultiPicStream = std::move(stream);
        mSerialContext.reset(new SkSharingSerialContext());
        SkSerialProcs procs;
        procs.fImageProc = SkSharingSerialContext::serializeImage;
        procs.fImageCtx = mSerialContext.get();
        procs.fTypefaceProc = [](SkTypeface* tf, void* ctx){
            return tf->serialize(SkTypeface::SerializeBehavior::kDoIncludeData);
        };
        // SkDocuments don't take owership of the streams they write.
        // we need to keep it until after mMultiPic.close()
        // procs is passed as a pointer, but just as a method of having an optional default.
        // procs doesn't need to outlive this Make call.
        mMultiPic = SkMakeMultiPictureDocument(mOpenMultiPicStream.get(), &procs,
            [sharingCtx = mSerialContext.get()](const SkPicture* pic) {
                    SkSharingSerialContext::collectNonTextureImagesFromPicture(pic, sharingCtx);
            });
        return true;
    } else {
        ALOGE("Could not open \"%s\" for writing.", mCapturedFile.c_str());
        mCaptureSequence = 0;
        mCaptureMode = CaptureMode::None;
        return false;
    }
}

// recurse through the rendernode's children, add any nodes which are layers to the queue.
static void collectLayers(RenderNode* node, LayerUpdateQueue* layers) {
    SkiaDisplayList* dl = node->getDisplayList().asSkiaDl();
    if (dl) {
        const auto& prop = node->properties();
        if (node->hasLayer()) {
            layers->enqueueLayerWithDamage(node, Rect(prop.getWidth(), prop.getHeight()));
        }
        // The way to recurse through rendernodes is to call this with a lambda.
        dl->updateChildren([&](RenderNode* child) { collectLayers(child, layers); });
    }
}

// record the provided layers to the provided canvas as self-contained skpictures.
static void recordLayers(const LayerUpdateQueue& layers,
    SkCanvas* mskpCanvas) {
    const Vector3 savedLightCenter(LightingInfo::getLightCenterRaw());
    // Record the commands to re-draw each dirty layer into an SkPicture
    for (size_t i = 0; i < layers.entries().size(); i++) {
        RenderNode* layerNode = layers.entries()[i].renderNode.get();
        const Rect& layerDamage = layers.entries()[i].damage;
        const RenderProperties& properties = layerNode->properties();

        // Temporarily map current light center into RenderNode's coordinate space
        Vector3 transformedLightCenter(savedLightCenter);
        layerNode->getSkiaLayer()->inverseTransformInWindow.mapPoint3d(transformedLightCenter);
        LightingInfo::setLightCenterRaw(transformedLightCenter);

        SkPictureRecorder layerRec;
        auto* recCanvas = layerRec.beginRecording(properties.getWidth(),
            properties.getHeight());
        // This is not recorded but still causes clipping.
        recCanvas->androidFramework_setDeviceClipRestriction(layerDamage.toSkIRect());
        RenderNodeDrawable root(layerNode, recCanvas, false);
        root.forceDraw(recCanvas);
        // Now write this picture into the SKP canvas with an annotation indicating what it is
        mskpCanvas->drawAnnotation(layerDamage.toSkRect(), String8::format(
            "OffscreenLayerDraw|%" PRId64, layerNode->uniqueId()).c_str(), nullptr);
        mskpCanvas->drawPicture(layerRec.finishRecordingAsPicture());
    }
    LightingInfo::setLightCenterRaw(savedLightCenter);
}

SkCanvas* SkiaPipeline::tryCapture(SkSurface* surface, RenderNode* root,
    const LayerUpdateQueue& dirtyLayers) {
    if (CC_LIKELY(!Properties::skpCaptureEnabled)) {
        return surface->getCanvas(); // Bail out early when capture is not turned on.
    }
    // Note that shouldStartNewFileCapture tells us if this is the *first* frame of a capture.
    bool firstFrameOfAnim = false;
    if (shouldStartNewFileCapture() && mCaptureMode == CaptureMode::MultiFrameSKP) {
        // set a reminder to record every layer near the end of this method, after we have set up
        // the nway canvas.
        firstFrameOfAnim = true;
        if (!setupMultiFrameCapture()) {
            return surface->getCanvas();
        }
    }

    // Create a canvas pointer, fill it depending on what kind of capture is requested (if any)
    SkCanvas* pictureCanvas = nullptr;
    switch (mCaptureMode) {
        case CaptureMode::CallbackAPI:
        case CaptureMode::SingleFrameSKP:
            mRecorder.reset(new SkPictureRecorder());
            pictureCanvas = mRecorder->beginRecording(surface->width(), surface->height());
            break;
        case CaptureMode::MultiFrameSKP:
            // If a multi frame recording is active, initialize recording for a single frame of a
            // multi frame file.
            pictureCanvas = mMultiPic->beginPage(surface->width(), surface->height());
            break;
        case CaptureMode::None:
            // Returning here in the non-capture case means we can count on pictureCanvas being
            // non-null below.
            return surface->getCanvas();
    }

    // Setting up an nway canvas is common to any kind of capture.
    mNwayCanvas = std::make_unique<SkNWayCanvas>(surface->width(), surface->height());
    mNwayCanvas->addCanvas(surface->getCanvas());
    mNwayCanvas->addCanvas(pictureCanvas);

    if (firstFrameOfAnim) {
        // On the first frame of any mskp capture we want to record any layers that are needed in
        // frame but may have been rendered offscreen before recording began.
        // We do not maintain a list of all layers, since it isn't needed outside this rare,
        // recording use case. Traverse the tree to find them and put them in this LayerUpdateQueue.
        LayerUpdateQueue luq;
        collectLayers(root, &luq);
        recordLayers(luq, mNwayCanvas.get());
    } else {
        // on non-first frames, we record any normal layer draws (dirty regions)
        recordLayers(dirtyLayers, mNwayCanvas.get());
    }

    return mNwayCanvas.get();
}

void SkiaPipeline::endCapture(SkSurface* surface) {
    if (CC_LIKELY(mCaptureMode == CaptureMode::None)) { return; }
    mNwayCanvas.reset();
    ATRACE_CALL();
    if (mCaptureSequence > 0 && mCaptureMode == CaptureMode::MultiFrameSKP) {
        mMultiPic->endPage();
        mCaptureSequence--;
        if (mCaptureSequence == 0) {
            mCaptureMode = CaptureMode::None;
            // Pass mMultiPic and mOpenMultiPicStream to a background thread, which will handle
            // the heavyweight serialization work and destroy them. mOpenMultiPicStream is released
            // to a bare pointer because keeping it in a smart pointer makes the lambda
            // non-copyable. The lambda is only called once, so this is safe.
            SkFILEWStream* stream = mOpenMultiPicStream.release();
            CommonPool::post([doc = std::move(mMultiPic), stream]{
                ALOGD("Finalizing multi frame SKP");
                doc->close();
                delete stream;
                ALOGD("Multi frame SKP complete.");
            });
        }
    } else {
        sk_sp<SkPicture> picture = mRecorder->finishRecordingAsPicture();
        if (picture->approximateOpCount() > 0) {
            if (mPictureCapturedCallback) {
                std::invoke(mPictureCapturedCallback, std::move(picture));
            } else {
                // single frame skp to file
                SkSerialProcs procs;
                procs.fTypefaceProc = [](SkTypeface* tf, void* ctx){
                    return tf->serialize(SkTypeface::SerializeBehavior::kDoIncludeData);
                };
                auto data = picture->serialize(&procs);
                savePictureAsync(data, mCapturedFile);
                mCaptureSequence = 0;
                mCaptureMode = CaptureMode::None;
            }
        }
        mRecorder.reset();
    }
}

void SkiaPipeline::renderFrame(const LayerUpdateQueue& layers, const SkRect& clip,
                               const std::vector<sp<RenderNode>>& nodes, bool opaque,
                               const Rect& contentDrawBounds, sk_sp<SkSurface> surface,
                               const SkMatrix& preTransform) {
    bool previousSkpEnabled = Properties::skpCaptureEnabled;
    if (mPictureCapturedCallback) {
        Properties::skpCaptureEnabled = true;
    }

    // Initialize the canvas for the current frame, that might be a recording canvas if SKP
    // capture is enabled.
    SkCanvas* canvas = tryCapture(surface.get(), nodes[0].get(), layers);

    // draw all layers up front
    renderLayersImpl(layers, opaque);

    renderFrameImpl(clip, nodes, opaque, contentDrawBounds, canvas, preTransform);

    endCapture(surface.get());

    if (CC_UNLIKELY(Properties::debugOverdraw)) {
        renderOverdraw(clip, nodes, contentDrawBounds, surface, preTransform);
    }

    Properties::skpCaptureEnabled = previousSkpEnabled;
}

namespace {
static Rect nodeBounds(RenderNode& node) {
    auto& props = node.properties();
    return Rect(props.getLeft(), props.getTop(), props.getRight(), props.getBottom());
}
}  // namespace

void SkiaPipeline::renderFrameImpl(const SkRect& clip,
                                   const std::vector<sp<RenderNode>>& nodes, bool opaque,
                                   const Rect& contentDrawBounds, SkCanvas* canvas,
                                   const SkMatrix& preTransform) {
    SkAutoCanvasRestore saver(canvas, true);
    auto clipRestriction = preTransform.mapRect(clip).roundOut();
    if (CC_UNLIKELY(isCapturingSkp())) {
        canvas->drawAnnotation(SkRect::Make(clipRestriction), "AndroidDeviceClipRestriction",
            nullptr);
    } else {
        // clip drawing to dirty region only when not recording SKP files (which should contain all
        // draw ops on every frame)
        canvas->androidFramework_setDeviceClipRestriction(clipRestriction);
    }
    canvas->concat(preTransform);

    // STOPSHIP: Revert, temporary workaround to clear always F16 frame buffer for b/74976293
    if (!opaque || getSurfaceColorType() == kRGBA_F16_SkColorType) {
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
    int resources;
    size_t bytes;
    mRenderThread.getGrContext()->getResourceCacheUsage(&resources, &bytes);
    size_t maxBytes = mRenderThread.getGrContext()->getResourceCacheLimit();

    SkString log("Resource Cache Usage:\n");
    log.appendf("%8d items\n", resources);
    log.appendf("%8zu bytes (%.2f MB) out of %.2f MB maximum\n", bytes,
                bytes * (1.0f / (1024.0f * 1024.0f)), maxBytes * (1.0f / (1024.0f * 1024.0f)));

    ALOGD("%s", log.c_str());
}

void SkiaPipeline::setHardwareBuffer(AHardwareBuffer* buffer) {
    if (mHardwareBuffer) {
        AHardwareBuffer_release(mHardwareBuffer);
        mHardwareBuffer = nullptr;
    }

    if (buffer) {
        AHardwareBuffer_acquire(buffer);
        mHardwareBuffer = buffer;
    }
}

sk_sp<SkSurface> SkiaPipeline::getBufferSkSurface(
        const renderthread::HardwareBufferRenderParams& bufferParams) {
    auto bufferColorSpace = bufferParams.getColorSpace();
    if (mBufferSurface == nullptr || mBufferColorSpace == nullptr ||
        !SkColorSpace::Equals(mBufferColorSpace.get(), bufferColorSpace.get())) {
        mBufferSurface = SkSurface::MakeFromAHardwareBuffer(
                mRenderThread.getGrContext(), mHardwareBuffer, kTopLeft_GrSurfaceOrigin,
                bufferColorSpace, nullptr, true);
        mBufferColorSpace = bufferColorSpace;
    }
    return mBufferSurface;
}

void SkiaPipeline::setSurfaceColorProperties(ColorMode colorMode) {
    mColorMode = colorMode;
    switch (colorMode) {
        case ColorMode::Default:
            mSurfaceColorType = SkColorType::kN32_SkColorType;
            mSurfaceColorSpace = SkColorSpace::MakeSRGB();
            break;
        case ColorMode::WideColorGamut:
            mSurfaceColorType = DeviceInfo::get()->getWideColorType();
            mSurfaceColorSpace = DeviceInfo::get()->getWideColorSpace();
            break;
        case ColorMode::Hdr:
            mSurfaceColorType = SkColorType::kRGBA_F16_SkColorType;
            mSurfaceColorSpace = SkColorSpace::MakeRGB(GetPQSkTransferFunction(), SkNamedGamut::kRec2020);
            break;
        case ColorMode::Hdr10:
            mSurfaceColorType = SkColorType::kRGBA_1010102_SkColorType;
            mSurfaceColorSpace = SkColorSpace::MakeRGB(GetPQSkTransferFunction(), SkNamedGamut::kRec2020);
            break;
        case ColorMode::A8:
            mSurfaceColorType = SkColorType::kAlpha_8_SkColorType;
            mSurfaceColorSpace = nullptr;
            break;
    }
}

// Overdraw debugging

// These colors should be kept in sync with Caches::getOverdrawColor() with a few differences.
// This implementation requires transparent entries for "no overdraw" and "single draws".
static const SkColor kOverdrawColors[2][6] = {
    {
        0x00000000,
        0x00000000,
        0x2f0000ff,
        0x2f00ff00,
        0x3fff0000,
        0x7fff0000,
    },
    {
        0x00000000,
        0x00000000,
        0x2f0000ff,
        0x4fffff00,
        0x5fff89d7,
        0x7fff0000,
    },
};

void SkiaPipeline::renderOverdraw(const SkRect& clip,
                                  const std::vector<sp<RenderNode>>& nodes,
                                  const Rect& contentDrawBounds, sk_sp<SkSurface> surface,
                                  const SkMatrix& preTransform) {
    // Set up the overdraw canvas.
    SkImageInfo offscreenInfo = SkImageInfo::MakeA8(surface->width(), surface->height());
    sk_sp<SkSurface> offscreen = surface->makeSurface(offscreenInfo);
    LOG_ALWAYS_FATAL_IF(!offscreen, "Failed to create offscreen SkSurface for overdraw viz.");
    SkOverdrawCanvas overdrawCanvas(offscreen->getCanvas());

    // Fake a redraw to replay the draw commands.  This will increment the alpha channel
    // each time a pixel would have been drawn.
    // Pass true for opaque so we skip the clear - the overdrawCanvas is already zero
    // initialized.
    renderFrameImpl(clip, nodes, true, contentDrawBounds, &overdrawCanvas, preTransform);
    sk_sp<SkImage> counts = offscreen->makeImageSnapshot();

    // Draw overdraw colors to the canvas.  The color filter will convert counts to colors.
    SkPaint paint;
    const SkColor* colors = kOverdrawColors[static_cast<int>(Properties::overdrawColorSet)];
    paint.setColorFilter(SkOverdrawColorFilter::MakeWithSkColors(colors));
    surface->getCanvas()->drawImage(counts.get(), 0.0f, 0.0f, SkSamplingOptions(), &paint);
}

} /* namespace skiapipeline */
} /* namespace uirenderer */
} /* namespace android */
