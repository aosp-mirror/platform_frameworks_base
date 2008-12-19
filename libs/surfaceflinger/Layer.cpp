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
#include <sys/types.h>

#include <cutils/properties.h>

#include <utils/Errors.h>
#include <utils/Log.h>
#include <utils/StopWatch.h>

#include <ui/PixelFormat.h>
#include <ui/EGLDisplaySurface.h>

#include "clz.h"
#include "Layer.h"
#include "LayerBitmap.h"
#include "SurfaceFlinger.h"
#include "VRamHeap.h"
#include "DisplayHardware/DisplayHardware.h"


#define DEBUG_RESIZE    0


namespace android {

// ---------------------------------------------------------------------------

const uint32_t Layer::typeInfo = LayerBaseClient::typeInfo | 4;
const char* const Layer::typeID = "Layer";

// ---------------------------------------------------------------------------

Layer::Layer(SurfaceFlinger* flinger, DisplayID display, Client* c, int32_t i)
    :   LayerBaseClient(flinger, display, c, i),
        mSecure(false),
        mFrontBufferIndex(1),
        mNeedsBlending(true),
        mResizeTransactionDone(false),
        mTextureName(-1U), mTextureWidth(0), mTextureHeight(0)
{
    // no OpenGL operation is possible here, since we might not be
    // in the OpenGL thread.
}

Layer::~Layer()
{
    client->free(clientIndex());
    // this should always be called from the OpenGL thread
    if (mTextureName != -1U) {
        //glDeleteTextures(1, &mTextureName);
        deletedTextures.add(mTextureName);
    }
}

void Layer::initStates(uint32_t w, uint32_t h, uint32_t flags)
{
    LayerBase::initStates(w,h,flags);

    if (flags & ISurfaceComposer::eDestroyBackbuffer)
        lcblk->flags |= eNoCopyBack;
}

sp<LayerBaseClient::Surface> Layer::getSurface() const
{
    return mSurface;
}

status_t Layer::setBuffers( Client* client,
                            uint32_t w, uint32_t h,
                            PixelFormat format, uint32_t flags)
{
    PixelFormatInfo info;
    status_t err = getPixelFormatInfo(format, &info);
    if (err) return err;

    // TODO: if eHardware is explicitly requested, we should fail
    // on systems where we can't allocate memory that can be used with
    // DMA engines for instance.
    
    // FIXME: we always ask for hardware for now (this should come from copybit)
    flags |= ISurfaceComposer::eHardware;

    const uint32_t memory_flags = flags & 
            (ISurfaceComposer::eGPU | 
             ISurfaceComposer::eHardware | 
             ISurfaceComposer::eSecure);
    
    // pixel-alignment. the final alignment may be bigger because
    // we always force a 4-byte aligned bpr.
    uint32_t alignment = 1;

    if (flags & ISurfaceComposer::eGPU) {
        // FIXME: this value should come from the h/w
        alignment = 8; 
        // FIXME: this is msm7201A specific, as its GPU only supports
        // BGRA_8888.
        if (format == PIXEL_FORMAT_RGBA_8888) {
            format = PIXEL_FORMAT_BGRA_8888;
        }
    }

    mSecure = (flags & ISurfaceComposer::eSecure) ? true : false;
    mNeedsBlending = (info.h_alpha - info.l_alpha) > 0;
    sp<MemoryDealer> allocators[2];
    for (int i=0 ; i<2 ; i++) {
        allocators[i] = client->createAllocator(memory_flags);
        if (allocators[i] == 0)
            return NO_MEMORY;
        mBuffers[i].init(allocators[i]);
        int err = mBuffers[i].setBits(w, h, alignment, format, LayerBitmap::SECURE_BITS);
        if (err != NO_ERROR)
            return err;
        mBuffers[i].clear(); // clear the bits for security
        mBuffers[i].getInfo(lcblk->surface + i);
    }

    mSurface = new Surface(clientIndex(),
            allocators[0]->getMemoryHeap(),
            allocators[1]->getMemoryHeap(),
            mIdentity);

    return NO_ERROR;
}

void Layer::reloadTexture(const Region& dirty)
{
    if (UNLIKELY(mTextureName == -1U)) {
        // create the texture name the first time
        // can't do that in the ctor, because it runs in another thread.
        mTextureName = createTexture();
    }
    const GGLSurface& t(frontBuffer().surface());
    loadTexture(dirty, mTextureName, t, mTextureWidth, mTextureHeight);
}


void Layer::onDraw(const Region& clip) const
{
    if (UNLIKELY(mTextureName == -1LU)) {
        //LOGW("Layer %p doesn't have a texture", this);
        // the texture has not been created yet, this Layer has
        // in fact never been drawn into. this happens frequently with
        // SurfaceView.
        clearWithOpenGL(clip);
        return;
    }

    const DisplayHardware& hw(graphicPlane(0).displayHardware());
    const LayerBitmap& front(frontBuffer());
    const GGLSurface& t(front.surface());

    status_t err = NO_ERROR;
    const int can_use_copybit = canUseCopybit();
    if (can_use_copybit)  {
        // StopWatch watch("copybit");
        const State& s(drawingState());

        copybit_image_t dst;
        hw.getDisplaySurface(&dst);
        const copybit_rect_t& drect
            = reinterpret_cast<const copybit_rect_t&>(mTransformedBounds);

        copybit_image_t src;
        front.getBitmapSurface(&src);
        copybit_rect_t srect = { 0, 0, t.width, t.height };

        copybit_device_t* copybit = mFlinger->getBlitEngine();
        copybit->set_parameter(copybit, COPYBIT_TRANSFORM, getOrientation());
        copybit->set_parameter(copybit, COPYBIT_PLANE_ALPHA, s.alpha);
        copybit->set_parameter(copybit, COPYBIT_DITHER,
                s.flags & ISurfaceComposer::eLayerDither ?
                        COPYBIT_ENABLE : COPYBIT_DISABLE);

        region_iterator it(clip);
        err = copybit->stretch(copybit, &dst, &src, &drect, &srect, &it);
    }

    if (!can_use_copybit || err) {
        drawWithOpenGL(clip, mTextureName, t);
    }
}

status_t Layer::reallocateBuffer(int32_t index, uint32_t w, uint32_t h)
{
    LOGD_IF(DEBUG_RESIZE,
                "reallocateBuffer (layer=%p), "
                "requested (%dx%d), "
                "index=%d, (%dx%d), (%dx%d)",
                this,
                int(w), int(h),
                int(index),
                int(mBuffers[0].width()), int(mBuffers[0].height()),
                int(mBuffers[1].width()), int(mBuffers[1].height()));

    status_t err = mBuffers[index].resize(w, h);
    if (err == NO_ERROR) {
        mBuffers[index].getInfo(lcblk->surface + index);
    } else {
        LOGE("resizing buffer %d to (%u,%u) failed [%08x] %s",
            index, w, h, err, strerror(err));
        // XXX: what to do, what to do? We could try to free some
        // hidden surfaces, instead of killing this one?
    }
    return err;
}

uint32_t Layer::doTransaction(uint32_t flags)
{
    const Layer::State& front(drawingState());
    const Layer::State& temp(currentState());

    // the test front.{w|h} != temp.{w|h} is not enough because it is possible
    // that the size changed back to its previous value before the buffer
    // was resized (in the eLocked case below), in which case, we still
    // need to execute the code below so the clients have a chance to be
    // release. resze() deals with the fact that the size can be the same.

    /*
     *  Various states we could be in...

         resize = state & eResizeRequested;
         if (backbufferChanged) {
             if (resize == 0) {
                 // ERROR, the resized buffer doesn't have its resize flag set
             } else if (resize == mask) {
                 // ERROR one of the buffer has already been resized
             } else if (resize == mask ^ eResizeRequested) {
                 // ERROR, the resized buffer doesn't have its resize flag set
             } else if (resize == eResizeRequested) {
                 // OK, Normal case, proceed with resize
             }
         } else {
             if (resize == 0) {
                 // OK, nothing special, do nothing
             } else if (resize == mask) {
                 // restarted transaction, do nothing
             } else if (resize == mask ^ eResizeRequested) {
                 // restarted transaction, do nothing
             } else if (resize == eResizeRequested) {
                 // OK, size reset to previous value, proceed with resize
             }
         }
     */

    // Index of the back buffer
    const bool backbufferChanged = (front.w != temp.w) || (front.h != temp.h);
    const uint32_t state = lcblk->swapState;
    const int32_t clientBackBufferIndex = layer_cblk_t::backBuffer(state);
    const uint32_t mask = clientBackBufferIndex ? eResizeBuffer1 : eResizeBuffer0;
    uint32_t resizeFlags = state & eResizeRequested;

    if (UNLIKELY(backbufferChanged && (resizeFlags != eResizeRequested))) {
        LOGE(   "backbuffer size changed, but both resize flags are not set! "
                "(layer=%p), state=%08x, requested (%dx%d), drawing (%d,%d), "
                "index=%d, (%dx%d), (%dx%d)",
                this,  state,
                int(temp.w), int(temp.h),
                int(drawingState().w), int(drawingState().h),
                int(clientBackBufferIndex),
                int(mBuffers[0].width()), int(mBuffers[0].height()),
                int(mBuffers[1].width()), int(mBuffers[1].height()));
        // if we get there we're pretty screwed. the only reasonable
        // thing to do is to pretend we should do the resize since
        // backbufferChanged is set (this also will give a chance to
        // client to get unblocked)
        resizeFlags = eResizeRequested;
    }

    if (resizeFlags == eResizeRequested)  {
        // NOTE: asserting that clientBackBufferIndex!=mFrontBufferIndex
        // here, would be wrong and misleading because by this point
        // mFrontBufferIndex has not been updated yet.

        LOGD_IF(DEBUG_RESIZE,
                    "resize (layer=%p), state=%08x, "
                    "requested (%dx%d), "
                    "drawing (%d,%d), "
                    "index=%d, (%dx%d), (%dx%d)",
                    this,  state,
                    int(temp.w), int(temp.h),
                    int(drawingState().w), int(drawingState().h),
                    int(clientBackBufferIndex),
                    int(mBuffers[0].width()), int(mBuffers[0].height()),
                    int(mBuffers[1].width()), int(mBuffers[1].height()));

        if (state & eLocked) {
            // if the buffer is locked, we can't resize anything because
            // - the backbuffer is currently in use by the user
            // - the front buffer is being shown
            // We just act as if the transaction didn't happen and we
            // reschedule it later...
            flags |= eRestartTransaction;
        } else {
            // This buffer needs to be resized
            status_t err =
                resize(clientBackBufferIndex, temp.w, temp.h, "transaction");
            if (err == NO_ERROR) {
                const uint32_t mask = clientBackBufferIndex ? eResizeBuffer1 : eResizeBuffer0;
                android_atomic_and(~mask, &(lcblk->swapState));
                // since a buffer became availlable, we can let the client go...
                mFlinger->scheduleBroadcast(client);
                mResizeTransactionDone = true;

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
            }
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

status_t Layer::resize(
        int32_t clientBackBufferIndex,
        uint32_t width, uint32_t height,
        const char* what)
{
    /*
     * handle resize (backbuffer and frontbuffer reallocation)
     */

    const LayerBitmap& clientBackBuffer(mBuffers[clientBackBufferIndex]);

    // if the new (transaction) size is != from the the backbuffer
    // then we need to reallocate the backbuffer
    bool backbufferChanged = (clientBackBuffer.width()  != width) ||
                             (clientBackBuffer.height() != height);

    LOGD_IF(!backbufferChanged,
            "(%s) eResizeRequested (layer=%p), but size not changed: "
            "requested (%dx%d), drawing (%d,%d), current (%d,%d),"
            "state=%08lx, index=%d, (%dx%d), (%dx%d)",
            what, this,
            int(width), int(height),
            int(drawingState().w), int(drawingState().h),
            int(currentState().w), int(currentState().h),
            long(lcblk->swapState),
            int(clientBackBufferIndex),
            int(mBuffers[0].width()), int(mBuffers[0].height()),
            int(mBuffers[1].width()), int(mBuffers[1].height()));

    // this can happen when changing the size back and forth quickly
    status_t err = NO_ERROR;
    if (backbufferChanged) {
        err = reallocateBuffer(clientBackBufferIndex, width, height);
    }
    if (UNLIKELY(err != NO_ERROR)) {
        // couldn't reallocate the surface
        android_atomic_write(eInvalidSurface, &lcblk->swapState);
        memset(lcblk->surface+clientBackBufferIndex, 0, sizeof(surface_info_t));
    }
    return err;
}

void Layer::setSizeChanged(uint32_t w, uint32_t h)
{
    LOGD_IF(DEBUG_RESIZE,
            "setSizeChanged w=%d, h=%d (old: w=%d, h=%d)",
            w, h, mCurrentState.w, mCurrentState.h);
    android_atomic_or(eResizeRequested, &(lcblk->swapState));
}

// ----------------------------------------------------------------------------
// pageflip handling...
// ----------------------------------------------------------------------------

void Layer::lockPageFlip(bool& recomputeVisibleRegions)
{
    uint32_t state = android_atomic_or(eBusy, &(lcblk->swapState));
    // preemptively block the client, because he might set
    // eFlipRequested at any time and want to use this buffer
    // for the next frame. This will be unset below if it
    // turns out we didn't need it.

    uint32_t mask = eInvalidSurface | eFlipRequested | eResizeRequested;
    if (!(state & mask))
        return;

    if (UNLIKELY(state & eInvalidSurface)) {
        // if eInvalidSurface is set, this means the surface
        // became invalid during a transaction (NO_MEMORY for instance)
        mFlinger->scheduleBroadcast(client);
        return;
    }

    if (UNLIKELY(state & eFlipRequested)) {
        uint32_t oldState;
        mPostedDirtyRegion = post(&oldState, recomputeVisibleRegions);
        if (oldState & eNextFlipPending) {
            // Process another round (we know at least a buffer
            // is ready for that client).
            mFlinger->signalEvent();
        }
    }
}

Region Layer::post(uint32_t* previousSate, bool& recomputeVisibleRegions)
{
    // atomically swap buffers and (re)set eFlipRequested
    int32_t oldValue, newValue;
    layer_cblk_t * const lcblk = this->lcblk;
    do {
        oldValue = lcblk->swapState;
            // get the current value

        LOG_ASSERT(oldValue&eFlipRequested,
            "eFlipRequested not set, yet we're flipping! (state=0x%08lx)",
            long(oldValue));

        newValue = (oldValue ^ eIndex);
            // swap buffers

        newValue &= ~(eFlipRequested | eNextFlipPending);
            // clear eFlipRequested and eNextFlipPending

        if (oldValue & eNextFlipPending)
            newValue |= eFlipRequested;
            // if eNextFlipPending is set (second buffer already has something
            // in it) we need to reset eFlipRequested because the client
            // might never do it

    } while(android_atomic_cmpxchg(oldValue, newValue, &(lcblk->swapState)));
    *previousSate = oldValue;

    const int32_t index = (newValue & eIndex) ^ 1;
    mFrontBufferIndex = index;

    // ... post the new front-buffer
    Region dirty(lcblk->region + index);
    dirty.andSelf(frontBuffer().bounds());

    //LOGI("Did post oldValue=%08lx, newValue=%08lx, mFrontBufferIndex=%u\n",
    //    oldValue, newValue, mFrontBufferIndex);
    //dirty.dump("dirty");

    if (UNLIKELY(oldValue & eResizeRequested)) {

        LOGD_IF(DEBUG_RESIZE,
                     "post (layer=%p), state=%08x, "
                     "index=%d, (%dx%d), (%dx%d)",
                     this,  newValue,
                     int(1-index),
                     int(mBuffers[0].width()), int(mBuffers[0].height()),
                     int(mBuffers[1].width()), int(mBuffers[1].height()));

        // here, we just posted the surface and we have resolved
        // the front/back buffer indices. The client is blocked, so
        // it cannot start using the new backbuffer.

        // If the backbuffer was resized in THIS round, we actually cannot
        // resize the frontbuffer because it has *just* been drawn (and we
        // would have nothing to draw). In this case we just skip the resize
        // it'll happen after the next page flip or during the next
        // transaction.

        const uint32_t mask = (1-index) ? eResizeBuffer1 : eResizeBuffer0;
        if (mResizeTransactionDone && (newValue & mask)) {
            // Resize the layer's second buffer only if the transaction
            // happened. It may not have happened yet if eResizeRequested
            // was set immediately after the "transactionRequested" test,
            // in which case the drawing state's size would be wrong.
            mFreezeLock.clear();
            const Layer::State& s(drawingState());
            if (resize(1-index, s.w, s.h, "post") == NO_ERROR) {
                do {
                    oldValue = lcblk->swapState;
                    if ((oldValue & eResizeRequested) == eResizeRequested) {
                        // ugh, another resize was requested since we processed
                        // the first buffer, don't free the client, and let
                        // the next transaction handle everything.
                        break;
                    }
                    newValue = oldValue & ~mask;
                } while(android_atomic_cmpxchg(oldValue, newValue, &(lcblk->swapState)));
            }
            mResizeTransactionDone = false;
            recomputeVisibleRegions = true;
            invalidate = true;
        }
    }

    reloadTexture(dirty);

    return dirty;
}

Point Layer::getPhysicalSize() const
{
    const LayerBitmap& front(frontBuffer());
    return Point(front.width(), front.height());
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

        // client could be blocked, so signal them so they get a
        // chance to reevaluate their condition.
        mFlinger->scheduleBroadcast(client);
    }
}

void Layer::finishPageFlip()
{
    if (LIKELY(!(lcblk->swapState & eInvalidSurface))) {
        LOGE_IF(!(lcblk->swapState & eBusy),
                "layer %p wasn't locked!", this);
        android_atomic_and(~eBusy, &(lcblk->swapState));
    }
    mFlinger->scheduleBroadcast(client);
}


// ---------------------------------------------------------------------------


}; // namespace android
