/*
 * Copyright (C) 2007 The Android Open Source Project
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

#include <stdlib.h>
#include <stdint.h>
#include <sys/types.h>

#include <cutils/compiler.h>
#include <cutils/native_handle.h>
#include <cutils/properties.h>

#include <utils/Errors.h>
#include <utils/Log.h>
#include <utils/StopWatch.h>

#include <ui/GraphicBuffer.h>
#include <ui/PixelFormat.h>

#include <surfaceflinger/Surface.h>

#include "clz.h"
#include "DisplayHardware/DisplayHardware.h"
#include "DisplayHardware/HWComposer.h"
#include "GLExtensions.h"
#include "Layer.h"
#include "SurfaceFlinger.h"
#include "SurfaceTextureLayer.h"

#define DEBUG_RESIZE    0


namespace android {

// ---------------------------------------------------------------------------

Layer::Layer(SurfaceFlinger* flinger,
        DisplayID display, const sp<Client>& client)
    :   LayerBaseClient(flinger, display, client),
        mTextureName(-1U),
        mQueuedFrames(0),
        mCurrentTransform(0),
        mCurrentScalingMode(NATIVE_WINDOW_SCALING_MODE_FREEZE),
        mCurrentOpacity(true),
        mFormat(PIXEL_FORMAT_NONE),
        mGLExtensions(GLExtensions::getInstance()),
        mOpaqueLayer(true),
        mNeedsDithering(false),
        mSecure(false),
        mProtectedByApp(false)
{
    mCurrentCrop.makeInvalid();
    glGenTextures(1, &mTextureName);
}

void Layer::destroy(RefBase const* base) {
    mFlinger->destroyLayer(static_cast<LayerBase const*>(base));
}

void Layer::onFirstRef()
{
    LayerBaseClient::onFirstRef();
    setDestroyer(this);

    struct FrameQueuedListener : public SurfaceTexture::FrameAvailableListener {
        FrameQueuedListener(Layer* layer) : mLayer(layer) { }
    private:
        wp<Layer> mLayer;
        virtual void onFrameAvailable() {
            sp<Layer> that(mLayer.promote());
            if (that != 0) {
                that->onFrameQueued();
            }
        }
    };
    mSurfaceTexture = new SurfaceTextureLayer(mTextureName, this);
    mSurfaceTexture->setFrameAvailableListener(new FrameQueuedListener(this));
    mSurfaceTexture->setSynchronousMode(true);
    mSurfaceTexture->setBufferCountServer(2);
}

Layer::~Layer()
{
    glDeleteTextures(1, &mTextureName);
}

void Layer::onFrameQueued() {
    android_atomic_inc(&mQueuedFrames);
    mFlinger->signalEvent();
}

// called with SurfaceFlinger::mStateLock as soon as the layer is entered
// in the purgatory list
void Layer::onRemoved()
{
}

sp<ISurface> Layer::createSurface()
{
    class BSurface : public BnSurface, public LayerCleaner {
        wp<const Layer> mOwner;
        virtual sp<ISurfaceTexture> getSurfaceTexture() const {
            sp<ISurfaceTexture> res;
            sp<const Layer> that( mOwner.promote() );
            if (that != NULL) {
                res = that->mSurfaceTexture;
            }
            return res;
        }
    public:
        BSurface(const sp<SurfaceFlinger>& flinger,
                const sp<Layer>& layer)
            : LayerCleaner(flinger, layer), mOwner(layer) { }
    };
    sp<ISurface> sur(new BSurface(mFlinger, this));
    return sur;
}

status_t Layer::setBuffers( uint32_t w, uint32_t h,
                            PixelFormat format, uint32_t flags)
{
    // this surfaces pixel format
    PixelFormatInfo info;
    status_t err = getPixelFormatInfo(format, &info);
    if (err) return err;

    // the display's pixel format
    const DisplayHardware& hw(graphicPlane(0).displayHardware());
    uint32_t const maxSurfaceDims = min(
            hw.getMaxTextureSize(), hw.getMaxViewportDims());

    // never allow a surface larger than what our underlying GL implementation
    // can handle.
    if ((uint32_t(w)>maxSurfaceDims) || (uint32_t(h)>maxSurfaceDims)) {
        return BAD_VALUE;
    }

    PixelFormatInfo displayInfo;
    getPixelFormatInfo(hw.getFormat(), &displayInfo);
    const uint32_t hwFlags = hw.getFlags();
    
    mFormat = format;

    mSecure = (flags & ISurfaceComposer::eSecure) ? true : false;
    mProtectedByApp = (flags & ISurfaceComposer::eProtectedByApp) ? true : false;
    mOpaqueLayer = (flags & ISurfaceComposer::eOpaque);
    mCurrentOpacity = getOpacityForFormat(format);

    mSurfaceTexture->setDefaultBufferSize(w, h);
    mSurfaceTexture->setDefaultBufferFormat(format);

    // we use the red index
    int displayRedSize = displayInfo.getSize(PixelFormatInfo::INDEX_RED);
    int layerRedsize = info.getSize(PixelFormatInfo::INDEX_RED);
    mNeedsDithering = layerRedsize > displayRedSize;

    return NO_ERROR;
}

void Layer::setGeometry(hwc_layer_t* hwcl)
{
    hwcl->compositionType = HWC_FRAMEBUFFER;
    hwcl->hints = 0;
    hwcl->flags = 0;
    hwcl->transform = 0;
    hwcl->blending = HWC_BLENDING_NONE;

    // we can't do alpha-fade with the hwc HAL
    const State& s(drawingState());
    if (s.alpha < 0xFF) {
        hwcl->flags = HWC_SKIP_LAYER;
        return;
    }

    /*
     * Transformations are applied in this order:
     * 1) buffer orientation/flip/mirror
     * 2) state transformation (window manager)
     * 3) layer orientation (screen orientation)
     * (NOTE: the matrices are multiplied in reverse order)
     */

    const Transform bufferOrientation(mCurrentTransform);
    const Transform& stateTransform(s.transform);
    const Transform layerOrientation(mOrientation);

    const Transform tr(layerOrientation * stateTransform * bufferOrientation);

    // this gives us only the "orientation" component of the transform
    const uint32_t finalTransform = tr.getOrientation();

    // we can only handle simple transformation
    if (finalTransform & Transform::ROT_INVALID) {
        hwcl->flags = HWC_SKIP_LAYER;
        return;
    }

    hwcl->transform = finalTransform;

    if (!isOpaque()) {
        hwcl->blending = mPremultipliedAlpha ?
                HWC_BLENDING_PREMULT : HWC_BLENDING_COVERAGE;
    }

    // scaling is already applied in mTransformedBounds
    hwcl->displayFrame.left   = mTransformedBounds.left;
    hwcl->displayFrame.top    = mTransformedBounds.top;
    hwcl->displayFrame.right  = mTransformedBounds.right;
    hwcl->displayFrame.bottom = mTransformedBounds.bottom;

    hwcl->visibleRegionScreen.rects =
            reinterpret_cast<hwc_rect_t const *>(
                    visibleRegionScreen.getArray(
                            &hwcl->visibleRegionScreen.numRects));
}

void Layer::setPerFrameData(hwc_layer_t* hwcl) {
    const sp<GraphicBuffer>& buffer(mActiveBuffer);
    if (buffer == NULL) {
        // this can happen if the client never drew into this layer yet,
        // or if we ran out of memory. In that case, don't let
        // HWC handle it.
        hwcl->flags |= HWC_SKIP_LAYER;
        hwcl->handle = NULL;
        return;
    }
    hwcl->handle = buffer->handle;

    if (isCropped()) {
        hwcl->sourceCrop.left   = mCurrentCrop.left;
        hwcl->sourceCrop.top    = mCurrentCrop.top;
        hwcl->sourceCrop.right  = mCurrentCrop.right;
        hwcl->sourceCrop.bottom = mCurrentCrop.bottom;
    } else {
        hwcl->sourceCrop.left   = 0;
        hwcl->sourceCrop.top    = 0;
        hwcl->sourceCrop.right  = buffer->width;
        hwcl->sourceCrop.bottom = buffer->height;
    }
}

static inline uint16_t pack565(int r, int g, int b) {
    return (r<<11)|(g<<5)|b;
}
void Layer::onDraw(const Region& clip) const
{
    if (CC_UNLIKELY(mActiveBuffer == 0)) {
        // the texture has not been created yet, this Layer has
        // in fact never been drawn into. This happens frequently with
        // SurfaceView because the WindowManager can't know when the client
        // has drawn the first time.

        // If there is nothing under us, we paint the screen in black, otherwise
        // we just skip this update.

        // figure out if there is something below us
        Region under;
        const SurfaceFlinger::LayerVector& drawingLayers(mFlinger->mDrawingState.layersSortedByZ);
        const size_t count = drawingLayers.size();
        for (size_t i=0 ; i<count ; ++i) {
            const sp<LayerBase>& layer(drawingLayers[i]);
            if (layer.get() == static_cast<LayerBase const*>(this))
                break;
            under.orSelf(layer->visibleRegionScreen);
        }
        // if not everything below us is covered, we plug the holes!
        Region holes(clip.subtract(under));
        if (!holes.isEmpty()) {
            clearWithOpenGL(holes, 0, 0, 0, 1);
        }
        return;
    }

    GLenum target = mSurfaceTexture->getCurrentTextureTarget();
    glBindTexture(target, mTextureName);
    if (getFiltering() || needsFiltering() || isFixedSize() || isCropped()) {
        // TODO: we could be more subtle with isFixedSize()
        glTexParameterx(target, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameterx(target, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    } else {
        glTexParameterx(target, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameterx(target, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    }
    glEnable(target);
    glMatrixMode(GL_TEXTURE);
    glLoadMatrixf(mTextureMatrix);
    glMatrixMode(GL_MODELVIEW);

    drawWithOpenGL(clip);

    glDisable(target);
}

// As documented in libhardware header, formats in the range
// 0x100 - 0x1FF are specific to the HAL implementation, and
// are known to have no alpha channel
// TODO: move definition for device-specific range into
// hardware.h, instead of using hard-coded values here.
#define HARDWARE_IS_DEVICE_FORMAT(f) ((f) >= 0x100 && (f) <= 0x1FF)

bool Layer::getOpacityForFormat(uint32_t format)
{
    if (HARDWARE_IS_DEVICE_FORMAT(format)) {
        return true;
    }
    PixelFormatInfo info;
    status_t err = getPixelFormatInfo(PixelFormat(format), &info);
    // in case of error (unknown format), we assume no blending
    return (err || info.h_alpha <= info.l_alpha);
}


bool Layer::isOpaque() const
{
    // if we don't have a buffer yet, we're translucent regardless of the
    // layer's opaque flag.
    if (mActiveBuffer == 0)
        return false;

    // if the layer has the opaque flag, then we're always opaque,
    // otherwise we use the current buffer's format.
    return mOpaqueLayer || mCurrentOpacity;
}

bool Layer::isProtected() const
{
    const sp<GraphicBuffer>& activeBuffer(mActiveBuffer);
    return (activeBuffer != 0) &&
            (activeBuffer->getUsage() & GRALLOC_USAGE_PROTECTED);
}

uint32_t Layer::doTransaction(uint32_t flags)
{
    const Layer::State& front(drawingState());
    const Layer::State& temp(currentState());

    const bool sizeChanged = (front.requested_w != temp.requested_w) ||
            (front.requested_h != temp.requested_h);

    if (sizeChanged) {
        // the size changed, we need to ask our client to request a new buffer
        LOGD_IF(DEBUG_RESIZE,
                "resize (layer=%p), requested (%dx%d), drawing (%d,%d), "
                "fixedSize=%d",
                this,
                int(temp.requested_w), int(temp.requested_h),
                int(front.requested_w), int(front.requested_h),
                isFixedSize());

        if (!isFixedSize()) {
            // we're being resized and there is a freeze display request,
            // acquire a freeze lock, so that the screen stays put
            // until we've redrawn at the new size; this is to avoid
            // glitches upon orientation changes.
            if (mFlinger->hasFreezeRequest()) {
                // if the surface is hidden, don't try to acquire the
                // freeze lock, since hidden surfaces may never redraw
                if (!(front.flags & ISurfaceComposer::eLayerHidden)) {
                    mFreezeLock = mFlinger->getFreezeLock();
                }
            }

            // this will make sure LayerBase::doTransaction doesn't update
            // the drawing state's size
            Layer::State& editDraw(mDrawingState);
            editDraw.requested_w = temp.requested_w;
            editDraw.requested_h = temp.requested_h;

            // record the new size, form this point on, when the client request
            // a buffer, it'll get the new size.
            mSurfaceTexture->setDefaultBufferSize(temp.requested_w, temp.requested_h);
        }
    }

    if (temp.sequence != front.sequence) {
        if (temp.flags & ISurfaceComposer::eLayerHidden || temp.alpha == 0) {
            // this surface is now hidden, so it shouldn't hold a freeze lock
            // (it may never redraw, which is fine if it is hidden)
            mFreezeLock.clear();
        }
    }
        
    return LayerBase::doTransaction(flags);
}

bool Layer::isFixedSize() const {
    return mCurrentScalingMode != NATIVE_WINDOW_SCALING_MODE_FREEZE;
}

bool Layer::isCropped() const {
    return !mCurrentCrop.isEmpty();
}

// ----------------------------------------------------------------------------
// pageflip handling...
// ----------------------------------------------------------------------------

void Layer::lockPageFlip(bool& recomputeVisibleRegions)
{
    if (mQueuedFrames > 0) {
        // signal another event if we have more frames pending
        if (android_atomic_dec(&mQueuedFrames) > 1) {
            mFlinger->signalEvent();
        }

        if (mSurfaceTexture->updateTexImage() < NO_ERROR) {
            // something happened!
            recomputeVisibleRegions = true;
            return;
        }

        mActiveBuffer = mSurfaceTexture->getCurrentBuffer();
        mSurfaceTexture->getTransformMatrix(mTextureMatrix);

        const Rect crop(mSurfaceTexture->getCurrentCrop());
        const uint32_t transform(mSurfaceTexture->getCurrentTransform());
        const uint32_t scalingMode(mSurfaceTexture->getCurrentScalingMode());
        if ((crop != mCurrentCrop) ||
            (transform != mCurrentTransform) ||
            (scalingMode != mCurrentScalingMode))
        {
            mCurrentCrop = crop;
            mCurrentTransform = transform;
            mCurrentScalingMode = scalingMode;
            mFlinger->invalidateHwcGeometry();
        }

        const bool opacity(getOpacityForFormat(mActiveBuffer->format));
        if (opacity != mCurrentOpacity) {
            mCurrentOpacity = opacity;
            recomputeVisibleRegions = true;
        }

        const GLenum target(mSurfaceTexture->getCurrentTextureTarget());
        glTexParameterx(target, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameterx(target, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        // update the layer size and release freeze-lock
        const Layer::State& front(drawingState());

        // FIXME: mPostedDirtyRegion = dirty & bounds
        mPostedDirtyRegion.set(front.w, front.h);


        if ((front.w != front.requested_w) ||
            (front.h != front.requested_h))
        {
            // check that we received a buffer of the right size
            // (Take the buffer's orientation into account)
            sp<GraphicBuffer> newFrontBuffer(mActiveBuffer);
            uint32_t bufWidth  = newFrontBuffer->getWidth();
            uint32_t bufHeight = newFrontBuffer->getHeight();
            if (mCurrentTransform & Transform::ROT_90) {
                swap(bufWidth, bufHeight);
            }

            if (isFixedSize() ||
                    (bufWidth == front.requested_w &&
                    bufHeight == front.requested_h))
            {
                // Here we pretend the transaction happened by updating the
                // current and drawing states. Drawing state is only accessed
                // in this thread, no need to have it locked
                Layer::State& editDraw(mDrawingState);
                editDraw.w = editDraw.requested_w;
                editDraw.h = editDraw.requested_h;

                // We also need to update the current state so that we don't
                // end-up doing too much work during the next transaction.
                // NOTE: We actually don't need hold the transaction lock here
                // because State::w and State::h are only accessed from
                // this thread
                Layer::State& editTemp(currentState());
                editTemp.w = editDraw.w;
                editTemp.h = editDraw.h;

                // recompute visible region
                recomputeVisibleRegions = true;

                // we now have the correct size, unfreeze the screen
                mFreezeLock.clear();
            }
        }
    }
}

void Layer::unlockPageFlip(
        const Transform& planeTransform, Region& outDirtyRegion)
{
    Region dirtyRegion(mPostedDirtyRegion);
    if (!dirtyRegion.isEmpty()) {
        mPostedDirtyRegion.clear();
        // The dirty region is given in the layer's coordinate space
        // transform the dirty region by the surface's transformation
        // and the global transformation.
        const Layer::State& s(drawingState());
        const Transform tr(planeTransform * s.transform);
        dirtyRegion = tr.transform(dirtyRegion);

        // At this point, the dirty region is in screen space.
        // Make sure it's constrained by the visible region (which
        // is in screen space as well).
        dirtyRegion.andSelf(visibleRegionScreen);
        outDirtyRegion.orSelf(dirtyRegion);
    }
    if (visibleRegionScreen.isEmpty()) {
        // an invisible layer should not hold a freeze-lock
        // (because it may never be updated and therefore never release it)
        mFreezeLock.clear();
    }
}

void Layer::dump(String8& result, char* buffer, size_t SIZE) const
{
    LayerBaseClient::dump(result, buffer, SIZE);

    sp<const GraphicBuffer> buf0(mActiveBuffer);
    uint32_t w0=0, h0=0, s0=0, f0=0;
    if (buf0 != 0) {
        w0 = buf0->getWidth();
        h0 = buf0->getHeight();
        s0 = buf0->getStride();
        f0 = buf0->format;
    }
    snprintf(buffer, SIZE,
            "      "
            "format=%2d, activeBuffer=[%3ux%3u:%3u,%3u],"
            " freezeLock=%p, queued-frames=%d\n",
            mFormat, w0, h0, s0,f0,
            getFreezeLock().get(), mQueuedFrames);

    result.append(buffer);

    if (mSurfaceTexture != 0) {
        mSurfaceTexture->dump(result, "            ", buffer, SIZE);
    }
}

uint32_t Layer::getEffectiveUsage(uint32_t usage) const
{
    // TODO: should we do something special if mSecure is set?
    if (mProtectedByApp) {
        // need a hardware-protected path to external video sink
        usage |= GraphicBuffer::USAGE_PROTECTED;
    }
    return usage;
}

// ---------------------------------------------------------------------------


}; // namespace android
