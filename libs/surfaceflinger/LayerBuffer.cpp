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

#define LOG_TAG "SurfaceFlinger"

#include <stdlib.h>
#include <stdint.h>
#include <math.h>
#include <sys/types.h>

#include <utils/Errors.h>
#include <utils/Log.h>
#include <utils/StopWatch.h>

#include <utils/IPCThreadState.h>
#include <utils/IServiceManager.h>

#include <ui/PixelFormat.h>
#include <ui/EGLDisplaySurface.h>

#include "LayerBuffer.h"
#include "SurfaceFlinger.h"
#include "VRamHeap.h"
#include "DisplayHardware/DisplayHardware.h"


namespace android {

// ---------------------------------------------------------------------------

const uint32_t LayerBuffer::typeInfo = LayerBaseClient::typeInfo | 0x20;
const char* const LayerBuffer::typeID = "LayerBuffer";

// ---------------------------------------------------------------------------

LayerBuffer::LayerBuffer(SurfaceFlinger* flinger, DisplayID display,
        Client* client, int32_t i)
    : LayerBaseClient(flinger, display, client, i),
      mNeedsBlending(false)
{
}

LayerBuffer::~LayerBuffer()
{
    sp<SurfaceBuffer> s(getClientSurface());
    if (s != 0) {
        s->disown();
        mClientSurface.clear();
    }
}

sp<LayerBuffer::SurfaceBuffer> LayerBuffer::getClientSurface() const
{
    Mutex::Autolock _l(mLock);
    return mClientSurface.promote();
}

sp<LayerBaseClient::Surface> LayerBuffer::getSurface() const
{
    sp<SurfaceBuffer> s;
    Mutex::Autolock _l(mLock);
    s = mClientSurface.promote();
    if (s == 0) {
        s = new SurfaceBuffer(clientIndex(),
                const_cast<LayerBuffer *>(this));
        mClientSurface = s;
    }
    return s;
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
// LayerBuffer::SurfaceBuffer
// ============================================================================

LayerBuffer::SurfaceBuffer::SurfaceBuffer(SurfaceID id, LayerBuffer* owner)
: LayerBaseClient::Surface(id, owner->getIdentity()), mOwner(owner)
{
}

LayerBuffer::SurfaceBuffer::~SurfaceBuffer()
{
    unregisterBuffers();
    mOwner = 0;
}

status_t LayerBuffer::SurfaceBuffer::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    switch (code) {
        case REGISTER_BUFFERS:
        case UNREGISTER_BUFFERS:
        case CREATE_OVERLAY:
        {
            // codes that require permission check
            IPCThreadState* ipc = IPCThreadState::self();
            const int pid = ipc->getCallingPid();
            const int self_pid = getpid();
            if (LIKELY(pid != self_pid)) {
                // we're called from a different process, do the real check
                if (!checkCallingPermission(
                        String16("android.permission.ACCESS_SURFACE_FLINGER")))
                {
                    const int uid = ipc->getCallingUid();
                    LOGE("Permission Denial: "
                            "can't access SurfaceFlinger pid=%d, uid=%d", pid, uid);
                    return PERMISSION_DENIED;
                }
            }
        }
    }
    return LayerBaseClient::Surface::onTransact(code, data, reply, flags);
}

status_t LayerBuffer::SurfaceBuffer::registerBuffers(const ISurface::BufferHeap& buffers)
{
    LayerBuffer* owner(getOwner());
    if (owner)
        return owner->registerBuffers(buffers);
    return NO_INIT;
}

void LayerBuffer::SurfaceBuffer::postBuffer(ssize_t offset)
{
    LayerBuffer* owner(getOwner());
    if (owner)
        owner->postBuffer(offset);
}

void LayerBuffer::SurfaceBuffer::unregisterBuffers()
{
    LayerBuffer* owner(getOwner());
    if (owner)
        owner->unregisterBuffers();
}

sp<OverlayRef> LayerBuffer::SurfaceBuffer::createOverlay(
        uint32_t w, uint32_t h, int32_t format) {
    sp<OverlayRef> result;
    LayerBuffer* owner(getOwner());
    if (owner)
        result = owner->createOverlay(w, h, format);
    return result;
}

void LayerBuffer::SurfaceBuffer::disown()
{
    Mutex::Autolock _l(mLock);
    mOwner = 0;
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
    src.img.w = buffers.hor_stride ?: buffers.w;
    src.img.h = buffers.ver_stride ?: buffers.h;
    src.img.format = buffers.format;
    src.img.offset = offset;
    src.img.base   = buffers.heap->base();
    src.img.fd     = buffers.heap->heapID();
}

LayerBuffer::Buffer::~Buffer()
{
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
    : Source(layer), mStatus(NO_ERROR), 
      mBufferSize(0), mTextureName(-1U)
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
    
}

LayerBuffer::BufferSource::~BufferSource()
{    
    if (mTextureName != -1U) {
        LayerBase::deletedTextures.add(mTextureName);
    }
}

void LayerBuffer::BufferSource::postBuffer(ssize_t offset)
{    
    ISurface::BufferHeap buffers;
    { // scope for the lock
        Mutex::Autolock _l(mLock);
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
    Mutex::Autolock _l(mLock);
    mBufferHeap.heap.clear();
    mBuffer.clear();
    mLayer.invalidate();
}

sp<LayerBuffer::Buffer> LayerBuffer::BufferSource::getBuffer() const
{
    Mutex::Autolock _l(mLock);
    return mBuffer;
}

void LayerBuffer::BufferSource::setBuffer(const sp<LayerBuffer::Buffer>& buffer)
{
    Mutex::Autolock _l(mLock);
    mBuffer = buffer;
}

bool LayerBuffer::BufferSource::transformed() const
{
    return mBufferHeap.transform ? true : Source::transformed(); 
}

void LayerBuffer::BufferSource::onDraw(const Region& clip) const 
{
    sp<Buffer> buffer(getBuffer());
    if (UNLIKELY(buffer == 0))  {
        // nothing to do, we don't have a buffer
        mLayer.clearWithOpenGL(clip);
        return;
    }

    status_t err = NO_ERROR;
    NativeBuffer src(buffer->getBuffer());
    const Rect& transformedBounds = mLayer.getTransformedBounds();
    const int can_use_copybit = mLayer.canUseCopybit();

    if (can_use_copybit)  {
        const int src_width  = src.crop.r - src.crop.l;
        const int src_height = src.crop.b - src.crop.t;
        int W = transformedBounds.width();
        int H = transformedBounds.height();
        if (mLayer.getOrientation() & Transform::ROT_90) {
            int t(W); W=H; H=t;
        }

        /* With LayerBuffer, it is likely that we'll have to rescale the
         * surface, because this is often used for video playback or
         * camera-preview. Since we want these operation as fast as possible
         * we make sure we can use the 2D H/W even if it doesn't support
         * the requested scale factor, in which case we perform the scaling
         * in several passes. */

        copybit_device_t* copybit = mLayer.mFlinger->getBlitEngine();
        const float min = copybit->get(copybit, COPYBIT_MINIFICATION_LIMIT);
        const float mag = copybit->get(copybit, COPYBIT_MAGNIFICATION_LIMIT);

        float xscale = 1.0f;
        if (src_width > W*min)          xscale = 1.0f / min;
        else if (src_width*mag < W)     xscale = mag;

        float yscale = 1.0f;
        if (src_height > H*min)         yscale = 1.0f / min;
        else if (src_height*mag < H)    yscale = mag;

        if (UNLIKELY(xscale!=1.0f || yscale!=1.0f)) {
            if (UNLIKELY(mTemporaryDealer == 0)) {
                // allocate a memory-dealer for this the first time
                mTemporaryDealer = mLayer.mFlinger->getSurfaceHeapManager()
                    ->createHeap(ISurfaceComposer::eHardware);
                mTempBitmap.init(mTemporaryDealer);
            }

            const int tmp_w = floorf(src_width  * xscale);
            const int tmp_h = floorf(src_height * yscale);
            err = mTempBitmap.setBits(tmp_w, tmp_h, 1, src.img.format);

            if (LIKELY(err == NO_ERROR)) {
                NativeBuffer tmp;
                mTempBitmap.getBitmapSurface(&tmp.img);
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
                if (err != NO_ERROR) {
                    LOGE("copybit failed (%s)", strerror(err));
                } else {
                    src = tmp;
                }
            }
        }

        if (err == NO_ERROR) {
            const DisplayHardware& hw(mLayer.graphicPlane(0).displayHardware());
            copybit_image_t dst;
            hw.getDisplaySurface(&dst);
            const copybit_rect_t& drect
                = reinterpret_cast<const copybit_rect_t&>(transformedBounds);
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

    if (!can_use_copybit || err) {
        if (UNLIKELY(mTextureName == -1LU)) {
            mTextureName = mLayer.createTexture();
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
        t.data = (GGLubyte*)(intptr_t(src.img.base) + src.img.offset);
        const Region dirty(Rect(t.width, t.height));
        mLayer.loadTexture(dirty, mTextureName, t, w, h);
        mLayer.drawWithOpenGL(clip, mTextureName, t, mBufferHeap.transform);
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

    mOverlayHandle = overlay->getHandleRef(overlay);
    
    // NOTE: here it's okay to acquire a reference to "this"m as long as
    // the reference is not released before we leave the ctor.
    sp<OverlayChannel> channel = new OverlayChannel(this);

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
        if (mVisibilityChanged) {
            mVisibilityChanged = false;
            const Rect& bounds = mLayer.getTransformedBounds();
            int x = bounds.left;
            int y = bounds.top;
            int w = bounds.width();
            int h = bounds.height();
            
            // we need a lock here to protect "destroy"
            Mutex::Autolock _l(mLock);
            if (mOverlay) {
                overlay_control_device_t* overlay_dev = mOverlayDevice;
                overlay_dev->setPosition(overlay_dev, mOverlay, x,y,w,h);
                overlay_dev->setParameter(overlay_dev, mOverlay, 
                        OVERLAY_TRANSFORM, mLayer.getOrientation());
            }
        }
    }
}

void LayerBuffer::OverlaySource::serverDestroy() 
{
    mLayer.clearSource();
    destroyOverlay();
}

void LayerBuffer::OverlaySource::destroyOverlay() 
{
    // we need a lock here to protect "onVisibilityResolved"
    Mutex::Autolock _l(mLock);
    if (mOverlay) {
        overlay_control_device_t* overlay_dev = mOverlayDevice;
        overlay_dev->destroyOverlay(overlay_dev, mOverlay);
        mOverlay = 0;
    }
}

// ---------------------------------------------------------------------------
}; // namespace android
