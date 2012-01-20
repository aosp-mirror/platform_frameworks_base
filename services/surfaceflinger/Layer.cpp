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

void Layer::onFirstRef()
{
    LayerBaseClient::onFirstRef();

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
    mFlinger->postMessageAsync(
            new SurfaceFlinger::MessageDestroyGLTexture(mTextureName) );
}

void Layer::onFrameQueued() {
    android_atomic_inc(&mQueuedFrames);
    mFlinger->signalEvent();
}

// called with SurfaceFlinger::mStateLock as soon as the layer is entered
// in the purgatory list
void Layer::onRemoved()
{
    mSurfaceTexture->abandon();
}

void Layer::setName(const String8& name) {
    LayerBase::setName(name);
    mSurfaceTexture->setName(name);
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

wp<IBinder> Layer::getSurfaceTextureBinder() const
{
    return mSurfaceTexture->asBinder();
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
    LayerBaseClient::setGeometry(hwcl);

    hwcl->flags &= ~HWC_SKIP_LAYER;

    // we can't do alpha-fade with the hwc HAL
    const State& s(drawingState());
    if (s.alpha < 0xFF) {
        hwcl->flags = HWC_SKIP_LAYER;
    }

    /*
     * Transformations are applied in this order:
     * 1) buffer orientation/flip/mirror
     * 2) state transformation (window manager)
     * 3) layer orientation (screen orientation)
     * mTransform is already the composition of (2) and (3)
     * (NOTE: the matrices are multiplied in reverse order)
     */

    const Transform bufferOrientation(mCurrentTransform);
    const Transform tr(mTransform * bufferOrientation);

    // this gives us only the "orientation" component of the transform
    const uint32_t finalTransform = tr.getOrientation();

    // we can only handle simple transformation
    if (finalTransform & Transform::ROT_INVALID) {
        hwcl->flags = HWC_SKIP_LAYER;
    } else {
        hwcl->transform = finalTransform;
    }

    if (isCropped()) {
        hwcl->sourceCrop.left   = mCurrentCrop.left;
        hwcl->sourceCrop.top    = mCurrentCrop.top;
        hwcl->sourceCrop.right  = mCurrentCrop.right;
        hwcl->sourceCrop.bottom = mCurrentCrop.bottom;
    } else {
        const sp<GraphicBuffer>& buffer(mActiveBuffer);
        hwcl->sourceCrop.left   = 0;
        hwcl->sourceCrop.top    = 0;
        if (buffer != NULL) {
            hwcl->sourceCrop.right  = buffer->width;
            hwcl->sourceCrop.bottom = buffer->height;
        } else {
            hwcl->sourceCrop.right  = mTransformedBounds.width();
            hwcl->sourceCrop.bottom = mTransformedBounds.height();
        }
    }
}

void Layer::setPerFrameData(hwc_layer_t* hwcl) {
    const sp<GraphicBuffer>& buffer(mActiveBuffer);
    if (buffer == NULL) {
        // this can happen if the client never drew into this layer yet,
        // or if we ran out of memory. In that case, don't let
        // HWC handle it.
        hwcl->flags |= HWC_SKIP_LAYER;
        hwcl->handle = NULL;
    } else {
        hwcl->handle = buffer->handle;
    }
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
        const SurfaceFlinger::LayerVector& drawingLayers(
                mFlinger->mDrawingState.layersSortedByZ);
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

    if (!isProtected()) {
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, mTextureName);
        GLenum filter = GL_NEAREST;
        if (getFiltering() || needsFiltering() || isFixedSize() || isCropped()) {
            // TODO: we could be more subtle with isFixedSize()
            filter = GL_LINEAR;
        }
        glTexParameterx(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, filter);
        glTexParameterx(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, filter);
        glMatrixMode(GL_TEXTURE);
        glLoadMatrixf(mTextureMatrix);
        glMatrixMode(GL_MODELVIEW);
        glDisable(GL_TEXTURE_2D);
        glEnable(GL_TEXTURE_EXTERNAL_OES);
    } else {
        glBindTexture(GL_TEXTURE_2D, mFlinger->getProtectedTexName());
        glMatrixMode(GL_TEXTURE);
        glLoadIdentity();
        glMatrixMode(GL_MODELVIEW);
        glDisable(GL_TEXTURE_EXTERNAL_OES);
        glEnable(GL_TEXTURE_2D);
    }

    drawWithOpenGL(clip);

    glDisable(GL_TEXTURE_EXTERNAL_OES);
    glDisable(GL_TEXTURE_2D);
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
    if (mActiveBuffer == 0) {
        return false;
    }

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
        ALOGD_IF(DEBUG_RESIZE,
                "doTransaction: "
                "resize (layer=%p), requested (%dx%d), drawing (%d,%d), "
                "scalingMode=%d",
                this,
                int(temp.requested_w), int(temp.requested_h),
                int(front.requested_w), int(front.requested_h),
                mCurrentScalingMode);

        if (!isFixedSize()) {
            // this will make sure LayerBase::doTransaction doesn't update
            // the drawing state's size
            Layer::State& editDraw(mDrawingState);
            editDraw.requested_w = temp.requested_w;
            editDraw.requested_h = temp.requested_h;
        }

        // record the new size, form this point on, when the client request
        // a buffer, it'll get the new size.
        mSurfaceTexture->setDefaultBufferSize(temp.requested_w,
                temp.requested_h);
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
        // Capture the old state of the layer for comparisons later
        const bool oldOpacity = isOpaque();
        sp<GraphicBuffer> oldActiveBuffer = mActiveBuffer;

        // signal another event if we have more frames pending
        if (android_atomic_dec(&mQueuedFrames) > 1) {
            mFlinger->signalEvent();
        }

        if (mSurfaceTexture->updateTexImage() < NO_ERROR) {
            // something happened!
            recomputeVisibleRegions = true;
            return;
        }

        // update the active buffer
        mActiveBuffer = mSurfaceTexture->getCurrentBuffer();

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

        GLfloat textureMatrix[16];
        mSurfaceTexture->getTransformMatrix(textureMatrix);
        if (memcmp(textureMatrix, mTextureMatrix, sizeof(textureMatrix))) {
            memcpy(mTextureMatrix, textureMatrix, sizeof(textureMatrix));
            mFlinger->invalidateHwcGeometry();
        }

        uint32_t bufWidth  = mActiveBuffer->getWidth();
        uint32_t bufHeight = mActiveBuffer->getHeight();
        if (oldActiveBuffer != NULL) {
            if (bufWidth != uint32_t(oldActiveBuffer->width) ||
                bufHeight != uint32_t(oldActiveBuffer->height)) {
                mFlinger->invalidateHwcGeometry();
            }
        }

        mCurrentOpacity = getOpacityForFormat(mActiveBuffer->format);
        if (oldOpacity != isOpaque()) {
            recomputeVisibleRegions = true;
        }

        glTexParameterx(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameterx(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        // update the layer size if needed
        const Layer::State& front(drawingState());

        // FIXME: mPostedDirtyRegion = dirty & bounds
        mPostedDirtyRegion.set(front.w, front.h);

        if ((front.w != front.requested_w) ||
            (front.h != front.requested_h))
        {
            // check that we received a buffer of the right size
            // (Take the buffer's orientation into account)
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
            }

            ALOGD_IF(DEBUG_RESIZE,
                    "lockPageFlip : "
                    "       (layer=%p), buffer (%ux%u, tr=%02x), "
                    "requested (%dx%d)",
                    this,
                    bufWidth, bufHeight, mCurrentTransform,
                    front.requested_w, front.requested_h);
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
            "format=%2d, activeBuffer=[%4ux%4u:%4u,%3X],"
            " transform-hint=0x%02x, queued-frames=%d\n",
            mFormat, w0, h0, s0,f0,
            getTransformHint(), mQueuedFrames);

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
    usage |= GraphicBuffer::USAGE_HW_COMPOSER;
    return usage;
}

uint32_t Layer::getTransformHint() const {
    uint32_t orientation = 0;
    if (!mFlinger->mDebugDisableTransformHint) {
        orientation = getPlaneOrientation();
        if (orientation & Transform::ROT_INVALID) {
            orientation = 0;
        }
    }
    return orientation;
}

// ---------------------------------------------------------------------------


}; // namespace android
