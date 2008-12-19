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
    mBuffer(0), mTextureName(-1U), mInvalidate(false), mNeedsBlending(false)
{
}

LayerBuffer::~LayerBuffer()
{
    sp<SurfaceBuffer> s(getClientSurface());
    if (s != 0) {
        s->disown();
        mClientSurface.clear();
    }

    // this should always be called from the OpenGL thread
    if (mTextureName != -1U) {
        //glDeleteTextures(1, &mTextureName);
        deletedTextures.add(mTextureName);
    }
    // to help debugging we set those to zero
    mWidth = mHeight = 0;
}

bool LayerBuffer::needsBlending() const
{
    Mutex::Autolock _l(mLock);
    return mNeedsBlending;
}

void LayerBuffer::onDraw(const Region& clip) const
{
    sp<Buffer> buffer(getBuffer());
    if (UNLIKELY(buffer == 0))  {
        // nothing to do, we don't have a buffer
        clearWithOpenGL(clip);
        return;
    }

    status_t err = NO_ERROR;
    NativeBuffer src(buffer->getBuffer());
    const int can_use_copybit = canUseCopybit();

    if (can_use_copybit)  {
        //StopWatch watch("MDP");

        const int src_width  = src.crop.r - src.crop.l;
        const int src_height = src.crop.b - src.crop.t;
        int W = mTransformedBounds.width();
        int H = mTransformedBounds.height();
        if (getOrientation() & Transform::ROT_90) {
            int t(W); W=H; H=t;
        }

        /* With LayerBuffer, it is likely that we'll have to rescale the
         * surface, because this is often used for video playback or
         * camera-preview. Since we want these operation as fast as possible
         * we make sure we can use the 2D H/W even if it doesn't support
         * the requested scale factor, in which case we perform the scaling
         * in several passes. */

        copybit_device_t* copybit = mFlinger->getBlitEngine();
        const float min = copybit->get(copybit, COPYBIT_MINIFICATION_LIMIT);
        const float mag = copybit->get(copybit, COPYBIT_MAGNIFICATION_LIMIT);

        float xscale = 1.0f;
        if (src_width > W*min)          xscale = 1.0f / min;
        else if (src_width*mag < W)     xscale = mag;

        float yscale = 1.0f;
        if (src_height > H*min)         yscale = 1.0f / min;
        else if (src_height*mag < H)    yscale = mag;

        if (UNLIKELY(xscale!=1.0f || yscale!=1.0f)) {
            //LOGD("MDP scaling hack w=%d, h=%d, ww=%d, wh=%d, xs=%f, ys=%f",
            //        src_width, src_height, W, H, xscale, yscale);

            if (UNLIKELY(mTemporaryDealer == 0)) {
                // allocate a memory-dealer for this the first time
                mTemporaryDealer = mFlinger->getSurfaceHeapManager()
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
                src = tmp;
            }
        }

        const DisplayHardware& hw(graphicPlane(0).displayHardware());
        copybit_image_t dst;
        hw.getDisplaySurface(&dst);
        const copybit_rect_t& drect
                = reinterpret_cast<const copybit_rect_t&>(mTransformedBounds);
        const State& s(drawingState());
        region_iterator it(clip);
        copybit->set_parameter(copybit, COPYBIT_TRANSFORM, getOrientation());
        copybit->set_parameter(copybit, COPYBIT_PLANE_ALPHA, s.alpha);
        copybit->set_parameter(copybit, COPYBIT_DITHER,
                s.flags & ISurfaceComposer::eLayerDither ?
                        COPYBIT_ENABLE : COPYBIT_DISABLE);
        err = copybit->stretch(copybit,
                &dst, &src.img, &drect, &src.crop, &it);
    }

    if (!can_use_copybit || err) {
        if (UNLIKELY(mTextureName == -1LU)) {
            mTextureName = createTexture();
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
        loadTexture(dirty, mTextureName, t, w, h);
        drawWithOpenGL(clip, mTextureName, t);
    }
}

void LayerBuffer::invalidateLocked()
{
    mInvalidate = true;
    mFlinger->signalEvent();
}

void LayerBuffer::invalidate()
{
    Mutex::Autolock _l(mLock);
    invalidateLocked();
}

void LayerBuffer::unlockPageFlip(const Transform& planeTransform,
        Region& outDirtyRegion)
{
    Mutex::Autolock _l(mLock);
    if (mInvalidate) {
        mInvalidate = false;
        outDirtyRegion.orSelf(visibleRegionScreen);
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


status_t LayerBuffer::registerBuffers(int w, int h, int hstride, int vstride,
            PixelFormat format, const sp<IMemoryHeap>& memoryHeap)
{
    if (memoryHeap == NULL) {
        // this is allowed, but in this case, it is illegal to receive
        // postBuffer(). The surface just erases the framebuffer with
        // fully transparent pixels.
        mHeap.clear();
        mWidth = w;
        mHeight = h;
        mNeedsBlending = false;
        return NO_ERROR;
    }
    
    status_t err = (memoryHeap->heapID() >= 0) ? NO_ERROR : NO_INIT;
    if (err != NO_ERROR)
        return err;

    // TODO: validate format/parameters

    Mutex::Autolock _l(mLock);
    mHeap = memoryHeap;
    mWidth = w;
    mHeight = h;
    mHStride = hstride;
    mVStride = vstride;
    mFormat = format;
    PixelFormatInfo info;
    getPixelFormatInfo(format, &info);
    mNeedsBlending = (info.h_alpha - info.l_alpha) > 0;
    return NO_ERROR;
}

void LayerBuffer::postBuffer(ssize_t offset)
{
    sp<IMemoryHeap> heap;
    int w, h, hs, vs, f;
    { // scope for the lock
        Mutex::Autolock _l(mLock);
        w = mWidth;
        h = mHeight;
        hs= mHStride;
        vs= mVStride;
        f = mFormat;
        heap = mHeap;
    }

    sp<Buffer> buffer;
    if (heap != 0) {
        buffer = new Buffer(heap, offset, w, h, hs, vs, f);
        if (buffer->getStatus() != NO_ERROR)
            buffer.clear();
        setBuffer(buffer);
        invalidate();
    }
}

void LayerBuffer::unregisterBuffers()
{
    Mutex::Autolock _l(mLock);
    mHeap.clear();
    mBuffer.clear();
    invalidateLocked();
}

sp<Overlay> LayerBuffer::createOverlay(uint32_t w, uint32_t h, int32_t format)
{
    sp<Overlay> result;
    Mutex::Autolock _l(mLock);
    if (mHeap != 0 || mBuffer != 0) {
        // we're a push surface. error.
        return result;
    }
    
    overlay_device_t* overlay_dev = mFlinger->getOverlayEngine();
    if (overlay_dev == NULL) {
        // overlays not supported
        return result;
    }

    overlay_t* overlay = overlay_dev->createOverlay(overlay_dev, w, h, format);
    if (overlay == NULL) {
        // couldn't create the overlay (no memory? no more overlays?)
        return result;
    }
    
    /* TODO: implement the real stuff here */
    
    return result;
}

sp<LayerBuffer::Buffer> LayerBuffer::getBuffer() const
{
    Mutex::Autolock _l(mLock);
    return mBuffer;
}

void LayerBuffer::setBuffer(const sp<LayerBuffer::Buffer>& buffer)
{
    Mutex::Autolock _l(mLock);
    mBuffer = buffer;
}

// ---------------------------------------------------------------------------

LayerBuffer::SurfaceBuffer::SurfaceBuffer(SurfaceID id, LayerBuffer* owner)
    : LayerBaseClient::Surface(id, owner->getIdentity()), mOwner(owner)
{
}

LayerBuffer::SurfaceBuffer::~SurfaceBuffer()
{
    unregisterBuffers();
    mOwner = 0;
}

status_t LayerBuffer::SurfaceBuffer::registerBuffers(
        int w, int h, int hs, int vs,
        PixelFormat format, const sp<IMemoryHeap>& heap)
{
    LayerBuffer* owner(getOwner());
    if (owner)
        return owner->registerBuffers(w, h, hs, vs, format, heap);
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

sp<Overlay> LayerBuffer::SurfaceBuffer::createOverlay(
        uint32_t w, uint32_t h, int32_t format) {
    sp<Overlay> result;
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


// ---------------------------------------------------------------------------

LayerBuffer::Buffer::Buffer(const sp<IMemoryHeap>& heap, ssize_t offset,
        int w, int h, int hs, int vs, int f)
    : mCount(0), mHeap(heap)
{
    NativeBuffer& src(mNativeBuffer);
    src.crop.l = 0;
    src.crop.t = 0;
    src.crop.r = w;
    src.crop.b = h;
    src.img.w = hs ?: w;
    src.img.h = vs ?: h;
    src.img.format = f;
    src.img.offset = offset;
    src.img.base   = heap->base();
    src.img.fd     = heap->heapID();
    // FIXME: make sure this buffer lies within the heap, in which case, set
    // mHeap to null
}

LayerBuffer::Buffer::~Buffer()
{
}

// ---------------------------------------------------------------------------
}; // namespace android
