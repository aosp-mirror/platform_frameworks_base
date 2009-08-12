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

#include <cutils/properties.h>
#include <cutils/native_handle.h>

#include <utils/Errors.h>
#include <utils/Log.h>
#include <utils/StopWatch.h>

#include <ui/PixelFormat.h>
#include <ui/Surface.h>

#include "clz.h"
#include "Layer.h"
#include "LayerBitmap.h"
#include "SurfaceFlinger.h"
#include "DisplayHardware/DisplayHardware.h"


#define DEBUG_RESIZE    0


namespace android {

// ---------------------------------------------------------------------------

const uint32_t Layer::typeInfo = LayerBaseClient::typeInfo | 4;
const char* const Layer::typeID = "Layer";

// ---------------------------------------------------------------------------

Layer::Layer(SurfaceFlinger* flinger, DisplayID display, const sp<Client>& c, int32_t i)
    :   LayerBaseClient(flinger, display, c, i),
        mSecure(false),
        mFrontBufferIndex(1),
        mNeedsBlending(true),
        mResizeTransactionDone(false)
{
    // no OpenGL operation is possible here, since we might not be
    // in the OpenGL thread.
}

Layer::~Layer()
{
    destroy();
    // the actual buffers will be destroyed here
}

void Layer::destroy()
{
    for (int i=0 ; i<NUM_BUFFERS ; i++) {
        if (mTextures[i].name != -1U) {
            glDeleteTextures(1, &mTextures[i].name);
            mTextures[i].name = -1U;
        }
        if (mTextures[i].image != EGL_NO_IMAGE_KHR) {
            EGLDisplay dpy(mFlinger->graphicPlane(0).getEGLDisplay());
            eglDestroyImageKHR(dpy, mTextures[i].image);
            mTextures[i].image = EGL_NO_IMAGE_KHR;
        }
        mBuffers[i].free();
    }
    mSurface.clear();
}

void Layer::initStates(uint32_t w, uint32_t h, uint32_t flags)
{
    LayerBase::initStates(w,h,flags);

    if (flags & ISurfaceComposer::eDestroyBackbuffer)
        lcblk->flags |= eNoCopyBack;
}

sp<LayerBaseClient::Surface> Layer::createSurface() const
{
    return mSurface;
}

status_t Layer::ditch()
{
    // the layer is not on screen anymore. free as much resources as possible
    destroy();
    return NO_ERROR;
}

status_t Layer::setBuffers( uint32_t w, uint32_t h,
                            PixelFormat format, uint32_t flags)
{
    PixelFormatInfo info;
    status_t err = getPixelFormatInfo(format, &info);
    if (err) return err;

    uint32_t bufferFlags = 0;
    if (flags & ISurfaceComposer::eGPU)
        bufferFlags |= Buffer::GPU;

    if (flags & ISurfaceComposer::eSecure)
        bufferFlags |= Buffer::SECURE;

    mSecure = (bufferFlags & Buffer::SECURE) ? true : false;
    mNeedsBlending = (info.h_alpha - info.l_alpha) > 0;
    for (int i=0 ; i<2 ; i++) {
        err = mBuffers[i].init(lcblk->surface + i, w, h, format, bufferFlags);
        if (err != NO_ERROR) {
            return err;
        }
    }
    mSurface = new SurfaceLayer(mFlinger, clientIndex(), this);
    return NO_ERROR;
}

void Layer::reloadTexture(const Region& dirty)
{
    const sp<Buffer>& buffer(frontBuffer().getBuffer());
    if (LIKELY(mFlags & DisplayHardware::DIRECT_TEXTURE)) {
        int index = mFrontBufferIndex;
        if (LIKELY(!mTextures[index].dirty)) {
            glBindTexture(GL_TEXTURE_2D, mTextures[index].name);
        } else {
            // we need to recreate the texture
            EGLDisplay dpy(mFlinger->graphicPlane(0).getEGLDisplay());
            
            // create the new texture name if needed
            if (UNLIKELY(mTextures[index].name == -1U)) {
                mTextures[index].name = createTexture();
            } else {
                glBindTexture(GL_TEXTURE_2D, mTextures[index].name);
            }

            // free the previous image
            if (mTextures[index].image != EGL_NO_IMAGE_KHR) {
                eglDestroyImageKHR(dpy, mTextures[index].image);
                mTextures[index].image = EGL_NO_IMAGE_KHR;
            }
            
            // construct an EGL_NATIVE_BUFFER_ANDROID
            android_native_buffer_t* clientBuf = buffer->getNativeBuffer();
            
            // create the new EGLImageKHR
            const EGLint attrs[] = { 
                    EGL_IMAGE_PRESERVED_KHR,    EGL_TRUE, 
                    EGL_NONE,                   EGL_NONE 
            };
            mTextures[index].image = eglCreateImageKHR(
                    dpy, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID,
                    (EGLClientBuffer)clientBuf, attrs);

            LOGE_IF(mTextures[index].image == EGL_NO_IMAGE_KHR,
                    "eglCreateImageKHR() failed. err=0x%4x",
                    eglGetError());
            
            if (mTextures[index].image != EGL_NO_IMAGE_KHR) {
                glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, 
                        (GLeglImageOES)mTextures[index].image);
                GLint error = glGetError();
                if (UNLIKELY(error != GL_NO_ERROR)) {
                    // this failed, for instance, because we don't support
                    // NPOT.
                    // FIXME: do something!
                    mFlags &= ~DisplayHardware::DIRECT_TEXTURE;
                } else {
                    // Everything went okay!
                    mTextures[index].dirty  = false;
                    mTextures[index].width  = clientBuf->width;
                    mTextures[index].height = clientBuf->height;
                }
            }                
        }
    } else {
        GGLSurface t;
        status_t res = buffer->lock(&t, GRALLOC_USAGE_SW_READ_RARELY);
        LOGE_IF(res, "error %d (%s) locking buffer %p",
                res, strerror(res), buffer.get());
        if (res == NO_ERROR) {
            if (UNLIKELY(mTextures[0].name == -1U)) {
                mTextures[0].name = createTexture();
            }
            loadTexture(&mTextures[0], mTextures[0].name, dirty, t);
            buffer->unlock();
        }
    }
}


void Layer::onDraw(const Region& clip) const
{
    const int index = (mFlags & DisplayHardware::DIRECT_TEXTURE) ? 
            mFrontBufferIndex : 0;
    GLuint textureName = mTextures[index].name;

    if (UNLIKELY(textureName == -1LU)) {
        LOGW("Layer %p doesn't have a texture", this);
        // the texture has not been created yet, this Layer has
        // in fact never been drawn into. this happens frequently with
        // SurfaceView.
        clearWithOpenGL(clip);
        return;
    }
    drawWithOpenGL(clip, mTextures[index]);
}

sp<SurfaceBuffer> Layer::peekBuffer(int usage)
{
    /*
     * This is called from the client's Surface::lock(), after it locked
     * the surface successfully. We're therefore guaranteed that the
     * back-buffer is not in use by ourselves.
     * Of course, we need to validate all this, which is not trivial.
     *
     * FIXME: A resize could happen at any time here. What to do about this?
     *  - resize() form post()
     *  - resize() from doTransaction()
     *  
     *  We'll probably need an internal lock for this.
     *     
     * 
     * TODO: We need to make sure that post() doesn't swap
     *       the buffers under us.
     */

    // it's okay to read swapState for the purpose of figuring out the 
    // backbuffer index, which cannot change (since the app has locked it).
    const uint32_t state = lcblk->swapState;
    const int32_t backBufferIndex = layer_cblk_t::backBuffer(state);
    
    // get rid of the EGL image, since we shouldn't need it anymore
    // (note that we're in a different thread than where it is being used)
    if (mTextures[backBufferIndex].image != EGL_NO_IMAGE_KHR) {
        EGLDisplay dpy(mFlinger->graphicPlane(0).getEGLDisplay());
        eglDestroyImageKHR(dpy, mTextures[backBufferIndex].image);
        mTextures[backBufferIndex].image = EGL_NO_IMAGE_KHR;
    }
    
    LayerBitmap& layerBitmap(mBuffers[backBufferIndex]);
    sp<SurfaceBuffer> buffer = layerBitmap.allocate(usage);
    
    LOGD_IF(DEBUG_RESIZE,
            "Layer::getBuffer(this=%p), index=%d, (%d,%d), (%d,%d)",
            this, backBufferIndex,
            layerBitmap.getWidth(),
            layerBitmap.getHeight(),
            layerBitmap.getBuffer()->getWidth(),
            layerBitmap.getBuffer()->getHeight());

    if (UNLIKELY(buffer == 0)) {
        // XXX: what to do, what to do?
    } else {
        // texture is now dirty...
        mTextures[backBufferIndex].dirty = true;
        // ... so it the visible region (because we consider the surface's
        // buffer size for visibility calculations)
        forceVisibilityTransaction();
        mFlinger->setTransactionFlags(eTraversalNeeded);
    }
    return buffer;
}

void Layer::scheduleBroadcast()
{
    sp<Client> ourClient(client.promote());
    if (ourClient != 0) {
        mFlinger->scheduleBroadcast(ourClient);
    }
}

uint32_t Layer::doTransaction(uint32_t flags)
{
    const Layer::State& front(drawingState());
    const Layer::State& temp(currentState());

    // the test front.{w|h} != temp.{w|h} is not enough because it is possible
    // that the size changed back to its previous value before the buffer
    // was resized (in the eLocked case below), in which case, we still
    // need to execute the code below so the clients have a chance to be
    // release. resize() deals with the fact that the size can be the same.

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
                int(mBuffers[0].getWidth()), int(mBuffers[0].getHeight()),
                int(mBuffers[1].getWidth()), int(mBuffers[1].getHeight()));
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
                    int(mBuffers[0].getWidth()), int(mBuffers[0].getHeight()),
                    int(mBuffers[1].getWidth()), int(mBuffers[1].getHeight()));

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
                const uint32_t mask = clientBackBufferIndex ?
                        eResizeBuffer1 : eResizeBuffer0;
                android_atomic_and(~mask, &(lcblk->swapState));
                // since a buffer became available, we can let the client go...
                scheduleBroadcast();
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
     * this is called from post() or from doTransaction()
     */

    const LayerBitmap& clientBackBuffer(mBuffers[clientBackBufferIndex]);

    // if the new (transaction) size is != from the the backbuffer
    // then we need to reallocate the backbuffer
    bool backbufferChanged = (clientBackBuffer.getWidth()  != width) ||
                             (clientBackBuffer.getHeight() != height);

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
            int(mBuffers[0].getWidth()), int(mBuffers[0].getHeight()),
            int(mBuffers[1].getWidth()), int(mBuffers[1].getHeight()));

    // this can happen when changing the size back and forth quickly
    status_t err = NO_ERROR;
    if (backbufferChanged) {

        LOGD_IF(DEBUG_RESIZE,
                "resize (layer=%p), requested (%dx%d), "
                "index=%d, (%dx%d), (%dx%d)",
                this, int(width), int(height), int(clientBackBufferIndex),
                int(mBuffers[0].getWidth()), int(mBuffers[0].getHeight()),
                int(mBuffers[1].getWidth()), int(mBuffers[1].getHeight()));

        err = mBuffers[clientBackBufferIndex].setSize(width, height);
        if (UNLIKELY(err != NO_ERROR)) {
            // This really should never happen
            LOGE("resizing buffer %d to (%u,%u) failed [%08x] %s",
                    clientBackBufferIndex, width, height, err, strerror(err));
            // couldn't reallocate the surface
            android_atomic_write(eInvalidSurface, &lcblk->swapState);
        }
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
        scheduleBroadcast();
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

    /* NOTE: it's safe to set this flag here because this is only touched
     * from LayerBitmap::allocate(), which by construction cannot happen
     * while we're in post().
     */
    lcblk->surface[index].flags &= ~surface_info_t::eBufferDirty;

    // ... post the new front-buffer
    Region dirty(lcblk->region + index);
    dirty.andSelf(frontBuffer().getBounds());

    //LOGD("Did post oldValue=%08lx, newValue=%08lx, mFrontBufferIndex=%u\n",
    //    oldValue, newValue, mFrontBufferIndex);
    //dirty.dump("dirty");

    if (UNLIKELY(oldValue & eResizeRequested)) {

        LOGD_IF(DEBUG_RESIZE,
                     "post (layer=%p), state=%08x, "
                     "index=%d, (%dx%d), (%dx%d)",
                     this,  newValue,
                     int(1-index),
                     int(mBuffers[0].getWidth()), int(mBuffers[0].getHeight()),
                     int(mBuffers[1].getWidth()), int(mBuffers[1].getHeight()));

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
            this->contentDirty = true;
        }
    }

    reloadTexture(dirty);

    return dirty;
}

Point Layer::getPhysicalSize() const
{
    sp<const Buffer> front(frontBuffer().getBuffer());
    return Point(front->getWidth(), front->getHeight());
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
        scheduleBroadcast();
    }
}

void Layer::finishPageFlip()
{
    if (LIKELY(!(lcblk->swapState & eInvalidSurface))) {
        LOGE_IF(!(lcblk->swapState & eBusy),
                "layer %p wasn't locked!", this);
        android_atomic_and(~eBusy, &(lcblk->swapState));
    }
    scheduleBroadcast();
}

// ---------------------------------------------------------------------------

Layer::SurfaceLayer::SurfaceLayer(const sp<SurfaceFlinger>& flinger,
        SurfaceID id, const sp<Layer>& owner)
    : Surface(flinger, id, owner->getIdentity(), owner)
{
}

Layer::SurfaceLayer::~SurfaceLayer()
{
}

sp<SurfaceBuffer> Layer::SurfaceLayer::getBuffer(int usage)
{
    sp<SurfaceBuffer> buffer = 0;
    sp<Layer> owner(getOwner());
    if (owner != 0) {
        buffer = owner->peekBuffer(usage);
    }
    return buffer;
}

// ---------------------------------------------------------------------------


}; // namespace android
