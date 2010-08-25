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
#include "GLExtensions.h"
#include "Layer.h"
#include "SurfaceFlinger.h"
#include "DisplayHardware/DisplayHardware.h"
#include "DisplayHardware/HWComposer.h"


#define DEBUG_RESIZE    0


namespace android {

template <typename T> inline T min(T a, T b) {
    return a<b ? a : b;
}

// ---------------------------------------------------------------------------

Layer::Layer(SurfaceFlinger* flinger,
        DisplayID display, const sp<Client>& client)
    :   LayerBaseClient(flinger, display, client),
        mGLExtensions(GLExtensions::getInstance()),
        mNeedsBlending(true),
        mNeedsDithering(false),
        mSecure(false),
        mTextureManager(),
        mBufferManager(mTextureManager),
        mWidth(0), mHeight(0), mFixedSize(false)
{
}

Layer::~Layer()
{
    // FIXME: must be called from the main UI thread
    EGLDisplay dpy(mFlinger->graphicPlane(0).getEGLDisplay());
    mBufferManager.destroy(dpy);

    // we can use getUserClientUnsafe here because we know we're
    // single-threaded at that point.
    sp<UserClient> ourClient(mUserClientRef.getUserClientUnsafe());
    if (ourClient != 0) {
        ourClient->detachLayer(this);
    }
}

status_t Layer::setToken(const sp<UserClient>& userClient,
        SharedClient* sharedClient, int32_t token)
{
    sp<SharedBufferServer> lcblk = new SharedBufferServer(
            sharedClient, token, mBufferManager.getDefaultBufferCount(),
            getIdentity());

    status_t err = mUserClientRef.setToken(userClient, lcblk, token);

    LOGE_IF(err != NO_ERROR,
            "ClientRef::setToken(%p, %p, %u) failed",
            userClient.get(), lcblk.get(), token);

    if (err == NO_ERROR) {
        // we need to free the buffers associated with this surface
    }

    return err;
}

int32_t Layer::getToken() const
{
    return mUserClientRef.getToken();
}

sp<UserClient> Layer::getClient() const
{
    return mUserClientRef.getClient();
}

// called with SurfaceFlinger::mStateLock as soon as the layer is entered
// in the purgatory list
void Layer::onRemoved()
{
    ClientRef::Access sharedClient(mUserClientRef);
    SharedBufferServer* lcblk(sharedClient.get());
    if (lcblk) {
        // wake up the condition
        lcblk->setStatus(NO_INIT);
    }
}

sp<LayerBaseClient::Surface> Layer::createSurface() const
{
    return mSurface;
}

status_t Layer::ditch()
{
    // NOTE: Called from the main UI thread

    // the layer is not on screen anymore. free as much resources as possible
    mFreezeLock.clear();

    EGLDisplay dpy(mFlinger->graphicPlane(0).getEGLDisplay());
    mBufferManager.destroy(dpy);
    mSurface.clear();

    Mutex::Autolock _l(mLock);
    mWidth = mHeight = 0;
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
    mReqFormat = format;
    mWidth  = w;
    mHeight = h;
    mSecure = (flags & ISurfaceComposer::eSecure) ? true : false;
    mNeedsBlending = (info.h_alpha - info.l_alpha) > 0;

    // we use the red index
    int displayRedSize = displayInfo.getSize(PixelFormatInfo::INDEX_RED);
    int layerRedsize = info.getSize(PixelFormatInfo::INDEX_RED);
    mNeedsDithering = layerRedsize > displayRedSize;

    mSurface = new SurfaceLayer(mFlinger, this);
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

    // we can only handle simple transformation
    if (mOrientation & Transform::ROT_INVALID) {
        hwcl->flags = HWC_SKIP_LAYER;
        return;
    }

    hwcl->transform = mOrientation;

    if (needsBlending()) {
        hwcl->blending = mPremultipliedAlpha ?
                HWC_BLENDING_PREMULT : HWC_BLENDING_COVERAGE;
    }

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
    sp<GraphicBuffer> buffer(mBufferManager.getActiveBuffer());
    if (buffer == NULL) {
        // this situation can happen if we ran out of memory for instance.
        // not much we can do. continue to use whatever texture was bound
        // to this context.
        hwcl->handle = NULL;
        return;
    }
    hwcl->handle = const_cast<native_handle_t*>(buffer->handle);
    // TODO: set the crop value properly
    hwcl->sourceCrop.left   = 0;
    hwcl->sourceCrop.top    = 0;
    hwcl->sourceCrop.right  = buffer->width;
    hwcl->sourceCrop.bottom = buffer->height;
}

void Layer::reloadTexture(const Region& dirty)
{
    sp<GraphicBuffer> buffer(mBufferManager.getActiveBuffer());
    if (buffer == NULL) {
        // this situation can happen if we ran out of memory for instance.
        // not much we can do. continue to use whatever texture was bound
        // to this context.
        return;
    }

    if (mGLExtensions.haveDirectTexture()) {
        EGLDisplay dpy(mFlinger->graphicPlane(0).getEGLDisplay());
        if (mBufferManager.initEglImage(dpy, buffer) != NO_ERROR) {
            // not sure what we can do here...
            goto slowpath;
        }
    } else {
slowpath:
        GGLSurface t;
        if (buffer->usage & GRALLOC_USAGE_SW_READ_MASK) {
            status_t res = buffer->lock(&t, GRALLOC_USAGE_SW_READ_OFTEN);
            LOGE_IF(res, "error %d (%s) locking buffer %p",
                    res, strerror(res), buffer.get());
            if (res == NO_ERROR) {
                mBufferManager.loadTexture(dirty, t);
                buffer->unlock();
            }
        } else {
            // we can't do anything
        }
    }
}

void Layer::onDraw(const Region& clip) const
{
    Texture tex(mBufferManager.getActiveTexture());
    if (tex.name == -1LU) {
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
    drawWithOpenGL(clip, tex);
}

bool Layer::needsFiltering() const
{
    if (!(mFlags & DisplayHardware::SLOW_CONFIG)) {
        // NOTE: there is a race here, because mFixedSize is updated in a
        // binder transaction. however, it doesn't really matter since it is
        // evaluated each time we draw. To be perfectly correct, this flag
        // would have to be associated with a buffer.
        if (mFixedSize)
            return true;
    }
    return LayerBase::needsFiltering();
}


status_t Layer::setBufferCount(int bufferCount)
{
    ClientRef::Access sharedClient(mUserClientRef);
    SharedBufferServer* lcblk(sharedClient.get());
    if (!lcblk) {
        // oops, the client is already gone
        return DEAD_OBJECT;
    }

    // NOTE: lcblk->resize() is protected by an internal lock
    status_t err = lcblk->resize(bufferCount);
    if (err == NO_ERROR)
        mBufferManager.resize(bufferCount);

    return err;
}

sp<GraphicBuffer> Layer::requestBuffer(int index,
        uint32_t reqWidth, uint32_t reqHeight, uint32_t reqFormat,
        uint32_t usage)
{
    sp<GraphicBuffer> buffer;

    if (int32_t(reqWidth | reqHeight | reqFormat) < 0)
        return buffer;

    if ((!reqWidth && reqHeight) || (reqWidth && !reqHeight))
        return buffer;

    // this ensures our client doesn't go away while we're accessing
    // the shared area.
    ClientRef::Access sharedClient(mUserClientRef);
    SharedBufferServer* lcblk(sharedClient.get());
    if (!lcblk) {
        // oops, the client is already gone
        return buffer;
    }

    /*
     * This is called from the client's Surface::dequeue(). This can happen
     * at any time, especially while we're in the middle of using the
     * buffer 'index' as our front buffer.
     */

    status_t err = NO_ERROR;
    uint32_t w, h, f;
    { // scope for the lock
        Mutex::Autolock _l(mLock);
        const bool fixedSizeChanged = mFixedSize != (reqWidth && reqHeight);
        const bool formatChanged    = mReqFormat != reqFormat;
        mReqWidth  = reqWidth;
        mReqHeight = reqHeight;
        mReqFormat = reqFormat;
        mFixedSize = reqWidth && reqHeight;
        w = reqWidth  ? reqWidth  : mWidth;
        h = reqHeight ? reqHeight : mHeight;
        f = reqFormat ? reqFormat : mFormat;
        if (fixedSizeChanged || formatChanged) {
            lcblk->reallocateAllExcept(index);
        }
    }

    // here we have to reallocate a new buffer because the buffer could be
    // used as the front buffer, or by a client in our process
    // (eg: status bar), and we can't release the handle under its feet.
    const uint32_t effectiveUsage = getEffectiveUsage(usage);
    buffer = new GraphicBuffer(w, h, f, effectiveUsage);
    err = buffer->initCheck();

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
        mBufferManager.attachBuffer(index, buffer);
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
        // request EGLImage for all buffers
        usage |= GraphicBuffer::USAGE_HW_TEXTURE;
    }
    return usage;
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
                "resize (layer=%p), requested (%dx%d), drawing (%d,%d)",
                this,
                int(temp.requested_w), int(temp.requested_h),
                int(front.requested_w), int(front.requested_h));

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
            setBufferSize(temp.requested_w, temp.requested_h);

            ClientRef::Access sharedClient(mUserClientRef);
            SharedBufferServer* lcblk(sharedClient.get());
            if (lcblk) {
                // all buffers need reallocation
                lcblk->reallocateAll();
            }
        } else {
            // record the new size
            setBufferSize(temp.requested_w, temp.requested_h);
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

void Layer::setBufferSize(uint32_t w, uint32_t h) {
    Mutex::Autolock _l(mLock);
    mWidth = w;
    mHeight = h;
}

bool Layer::isFixedSize() const {
    Mutex::Autolock _l(mLock);
    return mFixedSize;
}

// ----------------------------------------------------------------------------
// pageflip handling...
// ----------------------------------------------------------------------------

void Layer::lockPageFlip(bool& recomputeVisibleRegions)
{
    ClientRef::Access sharedClient(mUserClientRef);
    SharedBufferServer* lcblk(sharedClient.get());
    if (!lcblk) {
        // client died
        recomputeVisibleRegions = true;
        return;
    }

    ssize_t buf = lcblk->retireAndLock();
    if (buf == NOT_ENOUGH_DATA) {
        // NOTE: This is not an error, it simply means there is nothing to
        // retire. The buffer is locked because we will use it
        // for composition later in the loop
        return;
    }

    if (buf < NO_ERROR) {
        LOGE("retireAndLock() buffer index (%d) out of range", int(buf));
        mPostedDirtyRegion.clear();
        return;
    }

    // we retired a buffer, which becomes the new front buffer
    if (mBufferManager.setActiveBufferIndex(buf) < NO_ERROR) {
        LOGE("retireAndLock() buffer index (%d) out of range", int(buf));
        mPostedDirtyRegion.clear();
        return;
    }

    sp<GraphicBuffer> newFrontBuffer(getBuffer(buf));
    if (newFrontBuffer != NULL) {
        // get the dirty region
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

        // get the crop region
        setBufferCrop( lcblk->getCrop(buf) );

        // get the transformation
        setBufferTransform( lcblk->getTransform(buf) );

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

    /* a buffer was posted, so we need to call reloadTexture(), which
     * will update our internal data structures (eg: EGLImageKHR or
     * texture names). we need to do this even if mPostedDirtyRegion is
     * empty -- it's orthogonal to the fact that a new buffer was posted,
     * for instance, a degenerate case could be that the user did an empty
     * update but repainted the buffer with appropriate content (after a
     * resize for instance).
     */
    reloadTexture( mPostedDirtyRegion );
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

void Layer::finishPageFlip()
{
    ClientRef::Access sharedClient(mUserClientRef);
    SharedBufferServer* lcblk(sharedClient.get());
    if (lcblk) {
        int buf = mBufferManager.getActiveBufferIndex();
        if (buf >= 0) {
            status_t err = lcblk->unlock( buf );
            LOGE_IF(err!=NO_ERROR,
                    "layer %p, buffer=%d wasn't locked!",
                    this, buf);
        }
    }
}


void Layer::dump(String8& result, char* buffer, size_t SIZE) const
{
    LayerBaseClient::dump(result, buffer, SIZE);

    ClientRef::Access sharedClient(mUserClientRef);
    SharedBufferServer* lcblk(sharedClient.get());
    uint32_t totalTime = 0;
    if (lcblk) {
        SharedBufferStack::Statistics stats = lcblk->getStats();
        totalTime= stats.totalTime;
        result.append( lcblk->dump("      ") );
    }

    sp<const GraphicBuffer> buf0(getBuffer(0));
    sp<const GraphicBuffer> buf1(getBuffer(1));
    uint32_t w0=0, h0=0, s0=0;
    uint32_t w1=0, h1=0, s1=0;
    if (buf0 != 0) {
        w0 = buf0->getWidth();
        h0 = buf0->getHeight();
        s0 = buf0->getStride();
    }
    if (buf1 != 0) {
        w1 = buf1->getWidth();
        h1 = buf1->getHeight();
        s1 = buf1->getStride();
    }
    snprintf(buffer, SIZE,
            "      "
            "format=%2d, [%3ux%3u:%3u] [%3ux%3u:%3u],"
            " freezeLock=%p, dq-q-time=%u us\n",
            mFormat, w0, h0, s0, w1, h1, s1,
            getFreezeLock().get(), totalTime);

    result.append(buffer);
}

// ---------------------------------------------------------------------------

Layer::ClientRef::ClientRef()
    : mControlBlock(0), mToken(-1) {
}

Layer::ClientRef::~ClientRef() {
}

int32_t Layer::ClientRef::getToken() const {
    Mutex::Autolock _l(mLock);
    return mToken;
}

sp<UserClient> Layer::ClientRef::getClient() const {
    Mutex::Autolock _l(mLock);
    return mUserClient.promote();
}

status_t Layer::ClientRef::setToken(const sp<UserClient>& uc,
        const sp<SharedBufferServer>& sharedClient, int32_t token) {
    Mutex::Autolock _l(mLock);

    { // scope for strong mUserClient reference
        sp<UserClient> userClient(mUserClient.promote());
        if (mUserClient != 0 && mControlBlock != 0) {
            mControlBlock->setStatus(NO_INIT);
        }
    }

    mUserClient = uc;
    mToken = token;
    mControlBlock = sharedClient;
    return NO_ERROR;
}

sp<UserClient> Layer::ClientRef::getUserClientUnsafe() const {
    return mUserClient.promote();
}

// this class gives us access to SharedBufferServer safely
// it makes sure the UserClient (and its associated shared memory)
// won't go away while we're accessing it.
Layer::ClientRef::Access::Access(const ClientRef& ref)
    : mControlBlock(0)
{
    Mutex::Autolock _l(ref.mLock);
    mUserClientStrongRef = ref.mUserClient.promote();
    if (mUserClientStrongRef != 0)
        mControlBlock = ref.mControlBlock;
}

Layer::ClientRef::Access::~Access()
{
}

// ---------------------------------------------------------------------------

Layer::BufferManager::BufferManager(TextureManager& tm)
    : mNumBuffers(NUM_BUFFERS), mTextureManager(tm),
      mActiveBuffer(-1), mFailover(false)
{
}

Layer::BufferManager::~BufferManager()
{
}

status_t Layer::BufferManager::resize(size_t size)
{
    Mutex::Autolock _l(mLock);
    mNumBuffers = size;
    return NO_ERROR;
}

// only for debugging
sp<GraphicBuffer> Layer::BufferManager::getBuffer(size_t index) const {
    return mBufferData[index].buffer;
}

status_t Layer::BufferManager::setActiveBufferIndex(size_t index) {
    mActiveBuffer = index;
    return NO_ERROR;
}

size_t Layer::BufferManager::getActiveBufferIndex() const {
    return mActiveBuffer;
}

Texture Layer::BufferManager::getActiveTexture() const {
    Texture res;
    if (mFailover || mActiveBuffer<0) {
        res = mFailoverTexture;
    } else {
        static_cast<Image&>(res) = mBufferData[mActiveBuffer].texture;
    }
    return res;
}

sp<GraphicBuffer> Layer::BufferManager::getActiveBuffer() const {
    sp<GraphicBuffer> result;
    const ssize_t activeBuffer = mActiveBuffer;
    if (activeBuffer >= 0) {
        BufferData const * const buffers = mBufferData;
        Mutex::Autolock _l(mLock);
        result = buffers[activeBuffer].buffer;
    }
    return result;
}

sp<GraphicBuffer> Layer::BufferManager::detachBuffer(size_t index)
{
    BufferData* const buffers = mBufferData;
    sp<GraphicBuffer> buffer;
    Mutex::Autolock _l(mLock);
    buffer = buffers[index].buffer;
    buffers[index].buffer = 0;
    return buffer;
}

status_t Layer::BufferManager::attachBuffer(size_t index,
        const sp<GraphicBuffer>& buffer)
{
    BufferData* const buffers = mBufferData;
    Mutex::Autolock _l(mLock);
    buffers[index].buffer = buffer;
    buffers[index].texture.dirty = true;
    return NO_ERROR;
}

status_t Layer::BufferManager::destroy(EGLDisplay dpy)
{
    BufferData* const buffers = mBufferData;
    size_t num;
    { // scope for the lock
        Mutex::Autolock _l(mLock);
        num = mNumBuffers;
        for (size_t i=0 ; i<num ; i++) {
            buffers[i].buffer = 0;
        }
    }
    for (size_t i=0 ; i<num ; i++) {
        destroyTexture(&buffers[i].texture, dpy);
    }
    destroyTexture(&mFailoverTexture, dpy);
    return NO_ERROR;
}

status_t Layer::BufferManager::initEglImage(EGLDisplay dpy,
        const sp<GraphicBuffer>& buffer)
{
    status_t err = NO_INIT;
    ssize_t index = mActiveBuffer;
    if (index >= 0) {
        if (!mFailover) {
            Image& texture(mBufferData[index].texture);
            err = mTextureManager.initEglImage(&texture, dpy, buffer);
            // if EGLImage fails, we switch to regular texture mode, and we
            // free all resources associated with using EGLImages.
            if (err == NO_ERROR) {
                mFailover = false;
                destroyTexture(&mFailoverTexture, dpy);
            } else {
                mFailover = true;
                const size_t num = mNumBuffers;
                for (size_t i=0 ; i<num ; i++) {
                    destroyTexture(&mBufferData[i].texture, dpy);
                }
            }
        } else {
            // we failed once, don't try again
            err = BAD_VALUE;
        }
    }
    return err;
}

status_t Layer::BufferManager::loadTexture(
        const Region& dirty, const GGLSurface& t)
{
    return mTextureManager.loadTexture(&mFailoverTexture, dirty, t);
}

status_t Layer::BufferManager::destroyTexture(Image* tex, EGLDisplay dpy)
{
    if (tex->name != -1U) {
        glDeleteTextures(1, &tex->name);
        tex->name = -1U;
    }
    if (tex->image != EGL_NO_IMAGE_KHR) {
        eglDestroyImageKHR(dpy, tex->image);
        tex->image = EGL_NO_IMAGE_KHR;
    }
    return NO_ERROR;
}

// ---------------------------------------------------------------------------

Layer::SurfaceLayer::SurfaceLayer(const sp<SurfaceFlinger>& flinger,
        const sp<Layer>& owner)
    : Surface(flinger, owner->getIdentity(), owner)
{
}

Layer::SurfaceLayer::~SurfaceLayer()
{
}

sp<GraphicBuffer> Layer::SurfaceLayer::requestBuffer(int index,
        uint32_t w, uint32_t h, uint32_t format, uint32_t usage)
{
    sp<GraphicBuffer> buffer;
    sp<Layer> owner(getOwner());
    if (owner != 0) {
        /*
         * requestBuffer() cannot be called from the main thread
         * as it could cause a dead-lock, since it may have to wait
         * on conditions updated my the main thread.
         */
        buffer = owner->requestBuffer(index, w, h, format, usage);
    }
    return buffer;
}

status_t Layer::SurfaceLayer::setBufferCount(int bufferCount)
{
    status_t err = DEAD_OBJECT;
    sp<Layer> owner(getOwner());
    if (owner != 0) {
        /*
         * setBufferCount() cannot be called from the main thread
         * as it could cause a dead-lock, since it may have to wait
         * on conditions updated my the main thread.
         */
        err = owner->setBufferCount(bufferCount);
    }
    return err;
}

// ---------------------------------------------------------------------------


}; // namespace android
