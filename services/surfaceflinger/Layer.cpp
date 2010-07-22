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

#include <ui/GraphicBuffer.h>
#include <ui/PixelFormat.h>

#include <surfaceflinger/Surface.h>

#include "clz.h"
#include "Layer.h"
#include "SurfaceFlinger.h"
#include "DisplayHardware/DisplayHardware.h"


#define DEBUG_RESIZE    0


namespace android {

template <typename T> inline T min(T a, T b) {
    return a<b ? a : b;
}

// ---------------------------------------------------------------------------

const uint32_t Layer::typeInfo = LayerBaseClient::typeInfo | 4;
const char* const Layer::typeID = "Layer";

// ---------------------------------------------------------------------------

Layer::Layer(SurfaceFlinger* flinger, DisplayID display, 
        const sp<Client>& c, int32_t i)
    :   LayerBaseClient(flinger, display, c, i),
        mSecure(false),
        mNoEGLImageForSwBuffers(false),
        mNeedsBlending(true),
        mNeedsDithering(false)
{
    // no OpenGL operation is possible here, since we might not be
    // in the OpenGL thread.
    mFrontBufferIndex = lcblk->getFrontBuffer();
}

Layer::~Layer()
{
    destroy();
    // the actual buffers will be destroyed here
}

void Layer::destroy()
{
    for (size_t i=0 ; i<NUM_BUFFERS ; i++) {
        if (mTextures[i].name != -1U) {
            glDeleteTextures(1, &mTextures[i].name);
            mTextures[i].name = -1U;
        }
        if (mTextures[i].image != EGL_NO_IMAGE_KHR) {
            EGLDisplay dpy(mFlinger->graphicPlane(0).getEGLDisplay());
            eglDestroyImageKHR(dpy, mTextures[i].image);
            mTextures[i].image = EGL_NO_IMAGE_KHR;
        }
        Mutex::Autolock _l(mLock);
        mBuffers[i].clear();
        mWidth = mHeight = 0;
    }
    mSurface.clear();
}

sp<LayerBaseClient::Surface> Layer::createSurface() const
{
    return mSurface;
}

status_t Layer::ditch()
{
    // the layer is not on screen anymore. free as much resources as possible
    mFreezeLock.clear();
    destroy();
    return NO_ERROR;
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
    mWidth  = w;
    mHeight = h;
    mSecure = (flags & ISurfaceComposer::eSecure) ? true : false;
    mNeedsBlending = (info.h_alpha - info.l_alpha) > 0;
    mNoEGLImageForSwBuffers = !(hwFlags & DisplayHardware::CACHED_BUFFERS);

    // we use the red index
    int displayRedSize = displayInfo.getSize(PixelFormatInfo::INDEX_RED);
    int layerRedsize = info.getSize(PixelFormatInfo::INDEX_RED);
    mNeedsDithering = layerRedsize > displayRedSize;

    for (size_t i=0 ; i<NUM_BUFFERS ; i++) {
        mBuffers[i] = new GraphicBuffer();
    }
    mSurface = new SurfaceLayer(mFlinger, clientIndex(), this);
    return NO_ERROR;
}

void Layer::reloadTexture(const Region& dirty)
{
    Mutex::Autolock _l(mLock);
    sp<GraphicBuffer> buffer(getFrontBufferLocked());
    if (buffer == NULL) {
        // this situation can happen if we ran out of memory for instance.
        // not much we can do. continue to use whatever texture was bound
        // to this context.
        return;
    }

    const int index = mFrontBufferIndex;

    // create the new texture name if needed
    if (UNLIKELY(mTextures[index].name == -1U)) {
        mTextures[index].name = createTexture();
        mTextures[index].width = 0;
        mTextures[index].height = 0;
    }

#ifdef EGL_ANDROID_image_native_buffer
    if (mFlags & DisplayHardware::DIRECT_TEXTURE) {
        if (buffer->usage & GraphicBuffer::USAGE_HW_TEXTURE) {
            if (mTextures[index].dirty) {
                if (initializeEglImage(buffer, &mTextures[index]) != NO_ERROR) {
                    // not sure what we can do here...
                    mFlags &= ~DisplayHardware::DIRECT_TEXTURE;
                    goto slowpath;
                }
            }
        } else {
            if (mHybridBuffer==0 || (mHybridBuffer->width != buffer->width ||
                    mHybridBuffer->height != buffer->height)) {
                mHybridBuffer.clear();
                mHybridBuffer = new GraphicBuffer(
                        buffer->width, buffer->height, buffer->format,
                        GraphicBuffer::USAGE_SW_WRITE_OFTEN |
                        GraphicBuffer::USAGE_HW_TEXTURE);
                if (initializeEglImage(
                        mHybridBuffer, &mTextures[0]) != NO_ERROR) {
                    // not sure what we can do here...
                    mFlags &= ~DisplayHardware::DIRECT_TEXTURE;
                    mHybridBuffer.clear();
                    goto slowpath;
                }
            }

            GGLSurface t;
            status_t res = buffer->lock(&t, GRALLOC_USAGE_SW_READ_OFTEN);
            LOGE_IF(res, "error %d (%s) locking buffer %p",
                    res, strerror(res), buffer.get());
            if (res == NO_ERROR) {
                Texture* const texture(&mTextures[0]);

                glBindTexture(GL_TEXTURE_2D, texture->name);

                sp<GraphicBuffer> buf(mHybridBuffer);
                void* vaddr;
                res = buf->lock(GraphicBuffer::USAGE_SW_WRITE_OFTEN, &vaddr);
                if (res == NO_ERROR) {
                    int bpp = 0;
                    switch (t.format) {
                    case HAL_PIXEL_FORMAT_RGB_565:
                    case HAL_PIXEL_FORMAT_RGBA_4444:
                        bpp = 2;
                        break;
                    case HAL_PIXEL_FORMAT_RGBA_8888:
                    case HAL_PIXEL_FORMAT_RGBX_8888:
                        bpp = 4;
                        break;
                    default:
                        if (isSupportedYuvFormat(t.format)) {
                            // just show the Y plane of YUV buffers
                            bpp = 1;
                            break;
                        }
                        // oops, we don't handle this format!
                        LOGE("layer %p, texture=%d, using format %d, which is not "
                                "supported by the GL", this, texture->name, t.format);
                    }
                    if (bpp) {
                        const Rect bounds(dirty.getBounds());
                        size_t src_stride = t.stride;
                        size_t dst_stride = buf->stride;
                        if (src_stride == dst_stride &&
                            bounds.width() == t.width &&
                            bounds.height() == t.height)
                        {
                            memcpy(vaddr, t.data, t.height * t.stride * bpp);
                        } else {
                            GLubyte const * src = t.data +
                                (bounds.left + bounds.top * src_stride) * bpp;
                            GLubyte * dst = (GLubyte *)vaddr +
                                (bounds.left + bounds.top * dst_stride) * bpp;
                            const size_t length = bounds.width() * bpp;
                            size_t h = bounds.height();
                            src_stride *= bpp;
                            dst_stride *= bpp;
                            while (h--) {
                                memcpy(dst, src, length);
                                dst += dst_stride;
                                src += src_stride;
                            }
                        }
                    }
                    buf->unlock();
                }
                buffer->unlock();
            }
        }
    } else
#endif
    {
slowpath:
        for (size_t i=0 ; i<NUM_BUFFERS ; i++) {
            mTextures[i].image = EGL_NO_IMAGE_KHR;
        }
        GGLSurface t;
        status_t res = buffer->lock(&t, GRALLOC_USAGE_SW_READ_OFTEN);
        LOGE_IF(res, "error %d (%s) locking buffer %p",
                res, strerror(res), buffer.get());
        if (res == NO_ERROR) {
            loadTexture(&mTextures[0], dirty, t);
            buffer->unlock();
        }
    }
}

void Layer::onDraw(const Region& clip) const
{
    int index = mFrontBufferIndex;
    if (mTextures[index].image == EGL_NO_IMAGE_KHR)
        index = 0;
    GLuint textureName = mTextures[index].name;
    if (UNLIKELY(textureName == -1LU)) {
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
            clearWithOpenGL(holes);
        }
        return;
    }
    drawWithOpenGL(clip, mTextures[index]);
}

sp<GraphicBuffer> Layer::requestBuffer(int index, int usage)
{
    sp<GraphicBuffer> buffer;

    // this ensures our client doesn't go away while we're accessing
    // the shared area.
    sp<Client> ourClient(client.promote());
    if (ourClient == 0) {
        // oops, the client is already gone
        return buffer;
    }

    /*
     * This is called from the client's Surface::dequeue(). This can happen
     * at any time, especially while we're in the middle of using the
     * buffer 'index' as our front buffer.
     * 
     * Make sure the buffer we're resizing is not the front buffer and has been
     * dequeued. Once this condition is asserted, we are guaranteed that this
     * buffer cannot become the front buffer under our feet, since we're called
     * from Surface::dequeue()
     */
    status_t err = lcblk->assertReallocate(index);
    LOGE_IF(err, "assertReallocate(%d) failed (%s)", index, strerror(-err));
    if (err != NO_ERROR) {
        // the surface may have died
        return buffer;
    }

    uint32_t w, h;
    { // scope for the lock
        Mutex::Autolock _l(mLock);
        w = mWidth;
        h = mHeight;
        buffer = mBuffers[index];
        
        // destroy() could have been called before we get here, we log it
        // because it's uncommon, and the code below should handle it
        LOGW_IF(buffer==0, 
                "mBuffers[%d] is null (mWidth=%d, mHeight=%d)",
                index, w, h);
        
        mBuffers[index].clear();
    }

    const uint32_t effectiveUsage = getEffectiveUsage(usage);
    if (buffer!=0 && buffer->getStrongCount() == 1) {
        err = buffer->reallocate(w, h, mFormat, effectiveUsage);
    } else {
        // here we have to reallocate a new buffer because we could have a
        // client in our process with a reference to it (eg: status bar),
        // and we can't release the handle under its feet.
        buffer.clear();
        buffer = new GraphicBuffer(w, h, mFormat, effectiveUsage);
        err = buffer->initCheck();
    }

    if (err || buffer->handle == 0) {
        LOGE_IF(err || buffer->handle == 0,
                "Layer::requestBuffer(this=%p), index=%d, w=%d, h=%d failed (%s)",
                this, index, w, h, strerror(-err));
    } else {
        LOGD_IF(DEBUG_RESIZE,
                "Layer::requestBuffer(this=%p), index=%d, w=%d, h=%d, handle=%p",
                this, index, w, h, buffer->handle);
    }

    if (err == NO_ERROR && buffer->handle != 0) {
        Mutex::Autolock _l(mLock);
        if (mWidth && mHeight) {
            // and we have new buffer
            mBuffers[index] = buffer;
            // texture is now dirty...
            mTextures[index].dirty = true;
        } else {
            // oops we got killed while we were allocating the buffer
            buffer.clear();
        }
    }
    return buffer;
}

uint32_t Layer::getEffectiveUsage(uint32_t usage) const
{
    /*
     *  buffers used for software rendering, but h/w composition
     *  are allocated with SW_READ_OFTEN | SW_WRITE_OFTEN | HW_TEXTURE
     *
     *  buffers used for h/w rendering and h/w composition
     *  are allocated with  HW_RENDER | HW_TEXTURE
     *
     *  buffers used with h/w rendering and either NPOT or no egl_image_ext
     *  are allocated with SW_READ_RARELY | HW_RENDER
     *
     */

    if (mSecure) {
        // secure buffer, don't store it into the GPU
        usage = GraphicBuffer::USAGE_SW_READ_OFTEN |
                GraphicBuffer::USAGE_SW_WRITE_OFTEN;
    } else {
        // it's allowed to modify the usage flags here, but generally
        // the requested flags should be honored.
        if (mNoEGLImageForSwBuffers) {
            if (usage & GraphicBuffer::USAGE_HW_MASK) {
                // request EGLImage for h/w buffers only
                usage |= GraphicBuffer::USAGE_HW_TEXTURE;
            }
        } else {
            // request EGLImage for all buffers
            usage |= GraphicBuffer::USAGE_HW_TEXTURE;
        }
    }
    return usage;
}

uint32_t Layer::doTransaction(uint32_t flags)
{
    const Layer::State& front(drawingState());
    const Layer::State& temp(currentState());

    if ((front.requested_w != temp.requested_w) || 
        (front.requested_h != temp.requested_h)) {
        // the size changed, we need to ask our client to request a new buffer
        LOGD_IF(DEBUG_RESIZE,
                    "resize (layer=%p), requested (%dx%d), "
                    "drawing (%d,%d), (%dx%d), (%dx%d)",
                    this, 
                    int(temp.requested_w), int(temp.requested_h),
                    int(front.requested_w), int(front.requested_h),
                    int(mBuffers[0]->getWidth()), int(mBuffers[0]->getHeight()),
                    int(mBuffers[1]->getWidth()), int(mBuffers[1]->getHeight()));

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

        // record the new size, form this point on, when the client request a
        // buffer, it'll get the new size.
        setDrawingSize(temp.requested_w, temp.requested_h);

        // all buffers need reallocation
        lcblk->reallocate();
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

void Layer::setDrawingSize(uint32_t w, uint32_t h) {
    Mutex::Autolock _l(mLock);
    mWidth = w;
    mHeight = h;
}

// ----------------------------------------------------------------------------
// pageflip handling...
// ----------------------------------------------------------------------------

void Layer::lockPageFlip(bool& recomputeVisibleRegions)
{
    ssize_t buf = lcblk->retireAndLock();
    if (buf < NO_ERROR) {
        //LOGW("nothing to retire (%s)", strerror(-buf));
        // NOTE: here the buffer is locked because we will used 
        // for composition later in the loop
        return;
    }

    // ouch, this really should never happen
    if (uint32_t(buf)>=NUM_BUFFERS) {
        LOGE("retireAndLock() buffer index (%d) out of range", buf);
        mPostedDirtyRegion.clear();
        return;
    }

    // we retired a buffer, which becomes the new front buffer
    mFrontBufferIndex = buf;

    // get the dirty region
    sp<GraphicBuffer> newFrontBuffer(getBuffer(buf));
    if (newFrontBuffer != NULL) {
        // compute the posted region
        const Region dirty(lcblk->getDirtyRegion(buf));
        mPostedDirtyRegion = dirty.intersect( newFrontBuffer->getBounds() );

        // update the layer size and release freeze-lock
        const Layer::State& front(drawingState());
        if (newFrontBuffer->getWidth()  == front.requested_w &&
            newFrontBuffer->getHeight() == front.requested_h)
        {
            if ((front.w != front.requested_w) ||
                (front.h != front.requested_h))
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

            // we now have the correct size, unfreeze the screen
            mFreezeLock.clear();
        }
    } else {
        // this should not happen unless we ran out of memory while
        // allocating the buffer. we're hoping that things will get back
        // to normal the next time the app tries to draw into this buffer.
        // meanwhile, pretend the screen didn't update.
        mPostedDirtyRegion.clear();
    }

    if (lcblk->getQueuedCount()) {
        // signal an event if we have more buffers waiting
        mFlinger->signalEvent();
    }

    if (!mPostedDirtyRegion.isEmpty()) {
        reloadTexture( mPostedDirtyRegion );
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
        // (because it may never be updated and thereore never release it)
        mFreezeLock.clear();
    }
}

void Layer::finishPageFlip()
{
    status_t err = lcblk->unlock( mFrontBufferIndex );
    LOGE_IF(err!=NO_ERROR, 
            "layer %p, buffer=%d wasn't locked!",
            this, mFrontBufferIndex);
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

sp<GraphicBuffer> Layer::SurfaceLayer::requestBuffer(int index, int usage)
{
    sp<GraphicBuffer> buffer;
    sp<Layer> owner(getOwner());
    if (owner != 0) {
        LOGE_IF(uint32_t(index)>=NUM_BUFFERS,
                "getBuffer() index (%d) out of range", index);
        if (uint32_t(index) < NUM_BUFFERS) {
            buffer = owner->requestBuffer(index, usage);
        }
    }
    return buffer;
}

// ---------------------------------------------------------------------------


}; // namespace android
