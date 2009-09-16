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
#include <math.h>
#include <sys/types.h>

#include <utils/Errors.h>
#include <utils/Log.h>
#include <utils/StopWatch.h>

#include <ui/PixelFormat.h>
#include <ui/FramebufferNativeWindow.h>

#include <hardware/copybit.h>

#include "Buffer.h"
#include "BufferAllocator.h"
#include "LayerBuffer.h"
#include "SurfaceFlinger.h"
#include "DisplayHardware/DisplayHardware.h"

#include "gralloc_priv.h"   // needed for msm / copybit

namespace android {

// ---------------------------------------------------------------------------

const uint32_t LayerBuffer::typeInfo = LayerBaseClient::typeInfo | 0x20;
const char* const LayerBuffer::typeID = "LayerBuffer";

// ---------------------------------------------------------------------------

LayerBuffer::LayerBuffer(SurfaceFlinger* flinger, DisplayID display,
        const sp<Client>& client, int32_t i)
    : LayerBaseClient(flinger, display, client, i),
      mNeedsBlending(false)
{
}

LayerBuffer::~LayerBuffer()
{
}

void LayerBuffer::onFirstRef()
{
    LayerBaseClient::onFirstRef();
    mSurface = new SurfaceLayerBuffer(mFlinger, clientIndex(),
            const_cast<LayerBuffer *>(this));
}

sp<LayerBaseClient::Surface> LayerBuffer::createSurface() const
{
    return mSurface;
}

status_t LayerBuffer::ditch()
{
    mSurface.clear();
    return NO_ERROR;
}

bool LayerBuffer::needsBlending() const {
    return mNeedsBlending;
}

void LayerBuffer::setNeedsBlending(bool blending) {
    mNeedsBlending = blending;
}

void LayerBuffer::postBuffer(ssize_t offset)
{
    sp<Source> source(getSource());
    if (source != 0)
        source->postBuffer(offset);
}

void LayerBuffer::unregisterBuffers()
{
    sp<Source> source(clearSource());
    if (source != 0)
        source->unregisterBuffers();
}

uint32_t LayerBuffer::doTransaction(uint32_t flags)
{
    sp<Source> source(getSource());
    if (source != 0)
        source->onTransaction(flags);
    return LayerBase::doTransaction(flags);    
}

void LayerBuffer::unlockPageFlip(const Transform& planeTransform,
        Region& outDirtyRegion)
{
    // this code-path must be as tight as possible, it's called each time
    // the screen is composited.
    sp<Source> source(getSource());
    if (source != 0)
        source->onVisibilityResolved(planeTransform);
    LayerBase::unlockPageFlip(planeTransform, outDirtyRegion);    
}

void LayerBuffer::onDraw(const Region& clip) const
{
    sp<Source> source(getSource());
    if (LIKELY(source != 0)) {
        source->onDraw(clip);
    } else {
        clearWithOpenGL(clip);
    }
}

bool LayerBuffer::transformed() const
{
    sp<Source> source(getSource());
    if (LIKELY(source != 0))
        return source->transformed();
    return false;
}

void LayerBuffer::serverDestroy()
{
    sp<Source> source(clearSource());
    if (source != 0) {
        source->destroy();
    }
}

/**
 * This creates a "buffer" source for this surface
 */
status_t LayerBuffer::registerBuffers(const ISurface::BufferHeap& buffers)
{
    Mutex::Autolock _l(mLock);
    if (mSource != 0)
        return INVALID_OPERATION;

    sp<BufferSource> source = new BufferSource(*this, buffers);

    status_t result = source->getStatus();
    if (result == NO_ERROR) {
        mSource = source;
    }
    return result;
}    

/**
 * This creates an "overlay" source for this surface
 */
sp<OverlayRef> LayerBuffer::createOverlay(uint32_t w, uint32_t h, int32_t f)
{
    sp<OverlayRef> result;
    Mutex::Autolock _l(mLock);
    if (mSource != 0)
        return result;

    sp<OverlaySource> source = new OverlaySource(*this, &result, w, h, f);
    if (result != 0) {
        mSource = source;
    }
    return result;
}

sp<LayerBuffer::Source> LayerBuffer::getSource() const {
    Mutex::Autolock _l(mLock);
    return mSource;
}

sp<LayerBuffer::Source> LayerBuffer::clearSource() {
    sp<Source> source;
    Mutex::Autolock _l(mLock);
    source = mSource;
    mSource.clear();
    return source;
}

// ============================================================================
// LayerBuffer::SurfaceLayerBuffer
// ============================================================================

LayerBuffer::SurfaceLayerBuffer::SurfaceLayerBuffer(const sp<SurfaceFlinger>& flinger,
        SurfaceID id, const sp<LayerBuffer>& owner)
    : LayerBaseClient::Surface(flinger, id, owner->getIdentity(), owner)
{
}

LayerBuffer::SurfaceLayerBuffer::~SurfaceLayerBuffer()
{
    unregisterBuffers();
}

status_t LayerBuffer::SurfaceLayerBuffer::registerBuffers(
        const ISurface::BufferHeap& buffers)
{
    sp<LayerBuffer> owner(getOwner());
    if (owner != 0)
        return owner->registerBuffers(buffers);
    return NO_INIT;
}

void LayerBuffer::SurfaceLayerBuffer::postBuffer(ssize_t offset)
{
    sp<LayerBuffer> owner(getOwner());
    if (owner != 0)
        owner->postBuffer(offset);
}

void LayerBuffer::SurfaceLayerBuffer::unregisterBuffers()
{
    sp<LayerBuffer> owner(getOwner());
    if (owner != 0)
        owner->unregisterBuffers();
}

sp<OverlayRef> LayerBuffer::SurfaceLayerBuffer::createOverlay(
        uint32_t w, uint32_t h, int32_t format) {
    sp<OverlayRef> result;
    sp<LayerBuffer> owner(getOwner());
    if (owner != 0)
        result = owner->createOverlay(w, h, format);
    return result;
}

// ============================================================================
// LayerBuffer::Buffer
// ============================================================================

LayerBuffer::Buffer::Buffer(const ISurface::BufferHeap& buffers, ssize_t offset)
    : mBufferHeap(buffers)
{
    NativeBuffer& src(mNativeBuffer);
    
    src.crop.l = 0;
    src.crop.t = 0;
    src.crop.r = buffers.w;
    src.crop.b = buffers.h;
    
    src.img.w       = buffers.hor_stride ?: buffers.w;
    src.img.h       = buffers.ver_stride ?: buffers.h;
    src.img.format  = buffers.format;
    src.img.base    = (void*)(intptr_t(buffers.heap->base()) + offset);

    // FIXME: gross hack, we should never access private_handle_t from here,
    // but this is needed by msm drivers
    private_handle_t* hnd = new private_handle_t(
            buffers.heap->heapID(), buffers.heap->getSize(), 0);
    hnd->offset = offset;
    src.img.handle = hnd;
}

LayerBuffer::Buffer::~Buffer()
{
    NativeBuffer& src(mNativeBuffer);
    if (src.img.handle)
        delete (private_handle_t*)src.img.handle;
}

// ============================================================================
// LayerBuffer::Source
// LayerBuffer::BufferSource
// LayerBuffer::OverlaySource
// ============================================================================

LayerBuffer::Source::Source(LayerBuffer& layer)
    : mLayer(layer)
{    
}
LayerBuffer::Source::~Source() {    
}
void LayerBuffer::Source::onDraw(const Region& clip) const {
}
void LayerBuffer::Source::onTransaction(uint32_t flags) {
}
void LayerBuffer::Source::onVisibilityResolved(
        const Transform& planeTransform) {
}
void LayerBuffer::Source::postBuffer(ssize_t offset) {
}
void LayerBuffer::Source::unregisterBuffers() {
}
bool LayerBuffer::Source::transformed() const {
    return mLayer.mTransformed; 
}

// ---------------------------------------------------------------------------

LayerBuffer::BufferSource::BufferSource(LayerBuffer& layer,
        const ISurface::BufferHeap& buffers)
    : Source(layer), mStatus(NO_ERROR), mBufferSize(0)
{
    if (buffers.heap == NULL) {
        // this is allowed, but in this case, it is illegal to receive
        // postBuffer(). The surface just erases the framebuffer with
        // fully transparent pixels.
        mBufferHeap = buffers;
        mLayer.setNeedsBlending(false);
        return;
    }

    status_t err = (buffers.heap->heapID() >= 0) ? NO_ERROR : NO_INIT;
    if (err != NO_ERROR) {
        LOGE("LayerBuffer::BufferSource: invalid heap (%s)", strerror(err));
        mStatus = err;
        return;
    }
    
    PixelFormatInfo info;
    err = getPixelFormatInfo(buffers.format, &info);
    if (err != NO_ERROR) {
        LOGE("LayerBuffer::BufferSource: invalid format %d (%s)",
                buffers.format, strerror(err));
        mStatus = err;
        return;
    }

    if (buffers.hor_stride<0 || buffers.ver_stride<0) {
        LOGE("LayerBuffer::BufferSource: invalid parameters "
             "(w=%d, h=%d, xs=%d, ys=%d)", 
             buffers.w, buffers.h, buffers.hor_stride, buffers.ver_stride);
        mStatus = BAD_VALUE;
        return;
    }

    mBufferHeap = buffers;
    mLayer.setNeedsBlending((info.h_alpha - info.l_alpha) > 0);    
    mBufferSize = info.getScanlineSize(buffers.hor_stride)*buffers.ver_stride;
    mLayer.forceVisibilityTransaction();

    hw_module_t const* module;
    mBlitEngine = NULL;
    if (hw_get_module(COPYBIT_HARDWARE_MODULE_ID, &module) == 0) {
        copybit_open(module, &mBlitEngine);
    }
}

LayerBuffer::BufferSource::~BufferSource()
{    
    if (mTexture.name != -1U) {
        glDeleteTextures(1, &mTexture.name);
    }
    if (mBlitEngine) {
        copybit_close(mBlitEngine);
    }
}

void LayerBuffer::BufferSource::postBuffer(ssize_t offset)
{    
    ISurface::BufferHeap buffers;
    { // scope for the lock
        Mutex::Autolock _l(mBufferSourceLock);
        buffers = mBufferHeap;
        if (buffers.heap != 0) {
            const size_t memorySize = buffers.heap->getSize();
            if ((size_t(offset) + mBufferSize) > memorySize) {
                LOGE("LayerBuffer::BufferSource::postBuffer() "
                     "invalid buffer (offset=%d, size=%d, heap-size=%d",
                     int(offset), int(mBufferSize), int(memorySize));
                return;
            }
        }
    }

    sp<Buffer> buffer;
    if (buffers.heap != 0) {
        buffer = new LayerBuffer::Buffer(buffers, offset);
        if (buffer->getStatus() != NO_ERROR)
            buffer.clear();
        setBuffer(buffer);
        mLayer.invalidate();
    }
}

void LayerBuffer::BufferSource::unregisterBuffers()
{
    Mutex::Autolock _l(mBufferSourceLock);
    mBufferHeap.heap.clear();
    mBuffer.clear();
    mLayer.invalidate();
}

sp<LayerBuffer::Buffer> LayerBuffer::BufferSource::getBuffer() const
{
    Mutex::Autolock _l(mBufferSourceLock);
    return mBuffer;
}

void LayerBuffer::BufferSource::setBuffer(const sp<LayerBuffer::Buffer>& buffer)
{
    Mutex::Autolock _l(mBufferSourceLock);
    mBuffer = buffer;
}

bool LayerBuffer::BufferSource::transformed() const
{
    return mBufferHeap.transform ? true : Source::transformed(); 
}

void LayerBuffer::BufferSource::onDraw(const Region& clip) const 
{
    sp<Buffer> ourBuffer(getBuffer());
    if (UNLIKELY(ourBuffer == 0))  {
        // nothing to do, we don't have a buffer
        mLayer.clearWithOpenGL(clip);
        return;
    }

    status_t err = NO_ERROR;
    NativeBuffer src(ourBuffer->getBuffer());
    const Rect transformedBounds(mLayer.getTransformedBounds());
    copybit_device_t* copybit = mBlitEngine;

    if (copybit)  {
        const int src_width  = src.crop.r - src.crop.l;
        const int src_height = src.crop.b - src.crop.t;
        int W = transformedBounds.width();
        int H = transformedBounds.height();
        if (mLayer.getOrientation() & Transform::ROT_90) {
            int t(W); W=H; H=t;
        }

#ifdef EGL_ANDROID_get_render_buffer
        EGLDisplay dpy = eglGetCurrentDisplay();
        EGLSurface draw = eglGetCurrentSurface(EGL_DRAW); 
        EGLClientBuffer clientBuf = eglGetRenderBufferANDROID(dpy, draw);
        android_native_buffer_t* nb = (android_native_buffer_t*)clientBuf;
        if (nb == 0) {
            err = BAD_VALUE;
        } else {
            copybit_image_t dst;
            dst.w       = nb->width;
            dst.h       = nb->height;
            dst.format  = nb->format;
            dst.base    = NULL; // unused by copybit on msm7k
            dst.handle  = (native_handle_t *)nb->handle;

            /* With LayerBuffer, it is likely that we'll have to rescale the
             * surface, because this is often used for video playback or
             * camera-preview. Since we want these operation as fast as possible
             * we make sure we can use the 2D H/W even if it doesn't support
             * the requested scale factor, in which case we perform the scaling
             * in several passes. */

            const float min = copybit->get(copybit, COPYBIT_MINIFICATION_LIMIT);
            const float mag = copybit->get(copybit, COPYBIT_MAGNIFICATION_LIMIT);

            float xscale = 1.0f;
            if (src_width > W*min)          xscale = 1.0f / min;
            else if (src_width*mag < W)     xscale = mag;

            float yscale = 1.0f;
            if (src_height > H*min)         yscale = 1.0f / min;
            else if (src_height*mag < H)    yscale = mag;

            if (UNLIKELY(xscale!=1.0f || yscale!=1.0f)) {
                const int tmp_w = floorf(src_width  * xscale);
                const int tmp_h = floorf(src_height * yscale);
                
                if (mTempBitmap==0 || 
                        mTempBitmap->getWidth() < size_t(tmp_w) || 
                        mTempBitmap->getHeight() < size_t(tmp_h)) {
                    mTempBitmap.clear();
                    mTempBitmap = new android::Buffer(
                            tmp_w, tmp_h, src.img.format,
                            BufferAllocator::USAGE_HW_2D);
                    err = mTempBitmap->initCheck();
                }

                if (LIKELY(err == NO_ERROR)) {
                    NativeBuffer tmp;
                    tmp.img.w = tmp_w;
                    tmp.img.h = tmp_h;
                    tmp.img.format = src.img.format;
                    tmp.img.handle = (native_handle_t*)mTempBitmap->getNativeBuffer()->handle;
                    tmp.crop.l = 0;
                    tmp.crop.t = 0;
                    tmp.crop.r = tmp.img.w;
                    tmp.crop.b = tmp.img.h;

                    region_iterator tmp_it(Region(Rect(tmp.crop.r, tmp.crop.b)));
                    copybit->set_parameter(copybit, COPYBIT_TRANSFORM, 0);
                    copybit->set_parameter(copybit, COPYBIT_PLANE_ALPHA, 0xFF);
                    copybit->set_parameter(copybit, COPYBIT_DITHER, COPYBIT_DISABLE);
                    err = copybit->stretch(copybit,
                            &tmp.img, &src.img, &tmp.crop, &src.crop, &tmp_it);
                    src = tmp;
                }
            }

            const Rect transformedBounds(mLayer.getTransformedBounds());
            const copybit_rect_t& drect =
                reinterpret_cast<const copybit_rect_t&>(transformedBounds);
            const State& s(mLayer.drawingState());
            region_iterator it(clip);

            // pick the right orientation for this buffer
            int orientation = mLayer.getOrientation();
            if (UNLIKELY(mBufferHeap.transform)) {
                Transform rot90;
                GraphicPlane::orientationToTransfrom(
                        ISurfaceComposer::eOrientation90, 0, 0, &rot90);
                const Transform& planeTransform(mLayer.graphicPlane(0).transform());
                const Layer::State& s(mLayer.drawingState());
                Transform tr(planeTransform * s.transform * rot90);
                orientation = tr.getOrientation();
            }

            copybit->set_parameter(copybit, COPYBIT_TRANSFORM, orientation);
            copybit->set_parameter(copybit, COPYBIT_PLANE_ALPHA, s.alpha);
            copybit->set_parameter(copybit, COPYBIT_DITHER, COPYBIT_ENABLE);

            err = copybit->stretch(copybit,
                    &dst, &src.img, &drect, &src.crop, &it);
            if (err != NO_ERROR) {
                LOGE("copybit failed (%s)", strerror(err));
            }
        }
    }
#endif
    
    if (!copybit || err) 
    {
        // OpenGL fall-back
        if (UNLIKELY(mTexture.name == -1LU)) {
            mTexture.name = mLayer.createTexture();
        }
        GLuint w = 0;
        GLuint h = 0;
        GGLSurface t;
        t.version = sizeof(GGLSurface);
        t.width  = src.crop.r;
        t.height = src.crop.b;
        t.stride = src.img.w;
        t.vstride= src.img.h;
        t.format = src.img.format;
        t.data = (GGLubyte*)src.img.base;
        const Region dirty(Rect(t.width, t.height));
        mLayer.loadTexture(&mTexture, mTexture.name, dirty, t);
        mTexture.transform = mBufferHeap.transform;
        mLayer.drawWithOpenGL(clip, mTexture);
    }
}


// ---------------------------------------------------------------------------

LayerBuffer::OverlaySource::OverlaySource(LayerBuffer& layer,
        sp<OverlayRef>* overlayRef, 
        uint32_t w, uint32_t h, int32_t format)
    : Source(layer), mVisibilityChanged(false),
    mOverlay(0), mOverlayHandle(0), mOverlayDevice(0)
{
    overlay_control_device_t* overlay_dev = mLayer.mFlinger->getOverlayEngine();
    if (overlay_dev == NULL) {
        // overlays not supported
        return;
    }

    mOverlayDevice = overlay_dev;
    overlay_t* overlay = overlay_dev->createOverlay(overlay_dev, w, h, format);
    if (overlay == NULL) {
        // couldn't create the overlay (no memory? no more overlays?)
        return;
    }

    // enable dithering...
    overlay_dev->setParameter(overlay_dev, overlay, 
            OVERLAY_DITHER, OVERLAY_ENABLE);

    mOverlay = overlay;
    mWidth = overlay->w;
    mHeight = overlay->h;
    mFormat = overlay->format; 
    mWidthStride = overlay->w_stride;
    mHeightStride = overlay->h_stride;
    mInitialized = false;

    mOverlayHandle = overlay->getHandleRef(overlay);
    
    sp<OverlayChannel> channel = new OverlayChannel( &layer );

    *overlayRef = new OverlayRef(mOverlayHandle, channel,
            mWidth, mHeight, mFormat, mWidthStride, mHeightStride);
}

LayerBuffer::OverlaySource::~OverlaySource()
{
    if (mOverlay && mOverlayDevice) {
        overlay_control_device_t* overlay_dev = mOverlayDevice;
        overlay_dev->destroyOverlay(overlay_dev, mOverlay);
    }
}

void LayerBuffer::OverlaySource::onDraw(const Region& clip) const
{
    // this would be where the color-key would be set, should we need it.
    GLclampx red = 0;
    GLclampx green = 0;
    GLclampx blue = 0;
    mLayer.clearWithOpenGL(clip, red, green, blue, 0);
}

void LayerBuffer::OverlaySource::onTransaction(uint32_t flags)
{
    const Layer::State& front(mLayer.drawingState());
    const Layer::State& temp(mLayer.currentState());
    if (temp.sequence != front.sequence) {
        mVisibilityChanged = true;
    }
}

void LayerBuffer::OverlaySource::onVisibilityResolved(
        const Transform& planeTransform)
{
    // this code-path must be as tight as possible, it's called each time
    // the screen is composited.
    if (UNLIKELY(mOverlay != 0)) {
        if (mVisibilityChanged || !mInitialized) {
            mVisibilityChanged = false;
            mInitialized = true;
            const Rect bounds(mLayer.getTransformedBounds());
            int x = bounds.left;
            int y = bounds.top;
            int w = bounds.width();
            int h = bounds.height();
            
            // we need a lock here to protect "destroy"
            Mutex::Autolock _l(mOverlaySourceLock);
            if (mOverlay) {
                overlay_control_device_t* overlay_dev = mOverlayDevice;
                overlay_dev->setPosition(overlay_dev, mOverlay, x,y,w,h);
                overlay_dev->setParameter(overlay_dev, mOverlay,
                        OVERLAY_TRANSFORM, mLayer.getOrientation());
                overlay_dev->commit(overlay_dev, mOverlay);
            }
        }
    }
}

void LayerBuffer::OverlaySource::destroy()
{
    // we need a lock here to protect "onVisibilityResolved"
    Mutex::Autolock _l(mOverlaySourceLock);
    if (mOverlay && mOverlayDevice) {
        overlay_control_device_t* overlay_dev = mOverlayDevice;
        overlay_dev->destroyOverlay(overlay_dev, mOverlay);
        mOverlay = 0;
    }
}

// ---------------------------------------------------------------------------
}; // namespace android
