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

#define LOG_TAG "Surface"

#include <stdint.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/types.h>
#include <sys/stat.h>

#include <utils/Errors.h>
#include <utils/threads.h>
#include <utils/IPCThreadState.h>
#include <utils/IMemory.h>
#include <utils/Log.h>

#include <ui/DisplayInfo.h>
#include <ui/BufferMapper.h>
#include <ui/EGLNativeWindowSurface.h>
#include <ui/ISurface.h>
#include <ui/Surface.h>
#include <ui/SurfaceComposerClient.h>
#include <ui/Rect.h>

#include <EGL/android_natives.h>

#include <private/ui/SharedState.h>
#include <private/ui/LayerState.h>

#include <pixelflinger/pixelflinger.h>

namespace android {

// ============================================================================
//  SurfaceBuffer
// ============================================================================

template<class SurfaceBuffer> Mutex Singleton<SurfaceBuffer>::sLock; 
template<> SurfaceBuffer* Singleton<SurfaceBuffer>::sInstance(0); 

SurfaceBuffer::SurfaceBuffer() 
    : BASE(), handle(0), mOwner(false)
{
    width  = 
    height = 
    stride = 
    format = 
    usage  = 0;
    android_native_buffer_t::getHandle = getHandle;
}

SurfaceBuffer::SurfaceBuffer(const Parcel& data) 
    : BASE(), handle(0), mOwner(true)
{
    // we own the handle in this case
    width  = data.readInt32();
    height = data.readInt32();
    stride = data.readInt32();
    format = data.readInt32();
    usage  = data.readInt32();
    handle = data.readNativeHandle();
    android_native_buffer_t::getHandle = getHandle;
}

SurfaceBuffer::~SurfaceBuffer()
{
    if (handle && mOwner) {
        native_handle_close(handle);
        native_handle_delete(const_cast<native_handle*>(handle));
    }
}

int SurfaceBuffer::getHandle(android_native_buffer_t const * base, 
        buffer_handle_t* handle)
{
    *handle = getSelf(base)->handle;
    return 0;
}

status_t SurfaceBuffer::writeToParcel(Parcel* reply, 
        android_native_buffer_t const* buffer)
{
    buffer_handle_t handle;
    status_t err = buffer->getHandle(buffer, &handle);
    if (err < 0) {
        return err;
    }
    reply->writeInt32(buffer->width);
    reply->writeInt32(buffer->height);
    reply->writeInt32(buffer->stride);
    reply->writeInt32(buffer->format);
    reply->writeInt32(buffer->usage);
    reply->writeNativeHandle(handle);
    return NO_ERROR;
}

// ----------------------------------------------------------------------

static void copyBlt(const android_native_buffer_t* dst,
        const android_native_buffer_t* src, const Region& reg)
{
    Region::iterator iterator(reg);
    if (iterator) {
        // NOTE: dst and src must be the same format
        Rect r;
        const size_t bpp = bytesPerPixel(src->format);
        const size_t dbpr = dst->stride * bpp;
        const size_t sbpr = src->stride * bpp;
        while (iterator.iterate(&r)) {
            ssize_t h = r.bottom - r.top;
            if (h) {
                size_t size = (r.right - r.left) * bpp;
                uint8_t* s = (GGLubyte*)src->bits + 
                        (r.left + src->stride * r.top) * bpp;
                uint8_t* d = (GGLubyte*)dst->bits +
                        (r.left + dst->stride * r.top) * bpp;
                if (dbpr==sbpr && size==sbpr) {
                    size *= h;
                    h = 1;
                }
                do {
                    memcpy(d, s, size);
                    d += dbpr;
                    s += sbpr;
                } while (--h > 0);
            }
        }
    }
}

// ============================================================================
//  Surface
// ============================================================================

Surface::Surface(const sp<SurfaceComposerClient>& client, 
        const sp<ISurface>& surface,
        const ISurfaceFlingerClient::surface_data_t& data,
        uint32_t w, uint32_t h, PixelFormat format, uint32_t flags,
        bool owner)
    : mClient(client), mSurface(surface),
      mToken(data.token), mIdentity(data.identity),
      mFormat(format), mFlags(flags), mOwner(owner)
{
    android_native_window_t::connect          = connect;
    android_native_window_t::disconnect       = disconnect;
    android_native_window_t::setSwapInterval  = setSwapInterval;
    android_native_window_t::setSwapRectangle = setSwapRectangle;
    android_native_window_t::dequeueBuffer    = dequeueBuffer;
    android_native_window_t::lockBuffer       = lockBuffer;
    android_native_window_t::queueBuffer      = queueBuffer;

    mSwapRectangle.makeInvalid();

    DisplayInfo dinfo;
    SurfaceComposerClient::getDisplayInfo(0, &dinfo);
    const_cast<float&>(android_native_window_t::xdpi) = dinfo.xdpi;
    const_cast<float&>(android_native_window_t::ydpi) = dinfo.ydpi;
    // FIXME: set real values here
    const_cast<int&>(android_native_window_t::minSwapInterval) = 1;
    const_cast<int&>(android_native_window_t::maxSwapInterval) = 1;
    const_cast<uint32_t&>(android_native_window_t::flags) = 0;
}

Surface::~Surface()
{
    // this is a client-side operation, the surface is destroyed, unmap
    // its buffers in this process.
    for (int i=0 ; i<2 ; i++) {
        if (mBuffers[i] != 0) {
            BufferMapper::get().unmap(mBuffers[i]->getHandle(), this);
        }
    }

    destroy();
}

void Surface::destroy()
{
    // Destroy the surface in SurfaceFlinger if we were the owner
    // (in any case, a client won't be able to, because it won't have the
    // right permission).
    if (mOwner && mToken>=0 && mClient!=0) {
        mClient->destroySurface(mToken);
    }

    // clear all references and trigger an IPC now, to make sure things
    // happen without delay, since these resources are quite heavy.
    mClient.clear();
    mSurface.clear();
    IPCThreadState::self()->flushCommands();
}

void Surface::clear() 
{
    // here, the window manager tells us explicitly that we should destroy
    // the surface's resource. Soon after this call, it will also release
    // its last reference (which will call the dtor); however, it is possible
    // that a client living in the same process still holds references which
    // would delay the call to the dtor -- that is why we need this explicit
    // "clear()" call.

    // FIXME: we should probably unmap the buffers here. The problem is that
    // the app could be in the middle of using them, and if we don't unmap now
    // and we're in the system process, the mapping will be lost (because
    // the buffer will be freed, and the handles destroyed)
    
    Mutex::Autolock _l(mSurfaceLock);
    destroy();
}

status_t Surface::validate(per_client_cblk_t const* cblk) const
{
    if (mToken<0 || mClient==0) {
        LOGE("invalid token (%d, identity=%u) or client (%p)", 
                mToken, mIdentity, mClient.get());
        return NO_INIT;
    }
    if (cblk == 0) {
        LOGE("cblk is null (surface id=%d, identity=%u)", mToken, mIdentity);
        return NO_INIT;
    }
    status_t err = cblk->validate(mToken);
    if (err != NO_ERROR) {
        LOGE("surface (id=%d, identity=%u) is invalid, err=%d (%s)",
                mToken, mIdentity, err, strerror(-err));
        return err;
    }
    if (mIdentity != uint32_t(cblk->layers[mToken].identity)) {
        LOGE("using an invalid surface id=%d, identity=%u should be %d",
                mToken, mIdentity, cblk->layers[mToken].identity);
        return NO_INIT;
    }
    return NO_ERROR;
}

// ----------------------------------------------------------------------------

int Surface::setSwapRectangle(android_native_window_t* window,
        int l, int t, int w, int h)
{
    Surface* self = getSelf(window);
    self->setSwapRectangle(Rect(l, t, l+w, t+h));
    return 0;
}

void Surface::connect(android_native_window_t* window)
{
}

void Surface::disconnect(android_native_window_t* window)
{
}

int Surface::setSwapInterval(android_native_window_t* window, int interval)
{
    return 0;
}

int Surface::dequeueBuffer(android_native_window_t* window, 
        android_native_buffer_t** buffer)
{
    Surface* self = getSelf(window);
    return self->dequeueBuffer(buffer);
}

int Surface::lockBuffer(android_native_window_t* window, 
        android_native_buffer_t* buffer)
{
    Surface* self = getSelf(window);
    return self->lockBuffer(buffer);
}

int Surface::queueBuffer(android_native_window_t* window, 
        android_native_buffer_t* buffer)
{
    Surface* self = getSelf(window);
    return self->queueBuffer(buffer);
}

// ----------------------------------------------------------------------------

int Surface::dequeueBuffer(android_native_buffer_t** buffer)
{
    // FIXME: dequeueBuffer() needs proper implementation

    Mutex::Autolock _l(mSurfaceLock);

    per_client_cblk_t* const cblk = mClient->mControl;
    status_t err = validate(cblk);
    if (err != NO_ERROR)
        return err;

    SurfaceID index(mToken); 
    
    int32_t backIdx = cblk->lock_layer(size_t(index),
            per_client_cblk_t::BLOCKING);

    if (backIdx < 0)
        return status_t(backIdx); 

    mBackbufferIndex = backIdx;
    layer_cblk_t* const lcblk = &(cblk->layers[index]);

    volatile const surface_info_t* const back = lcblk->surface + backIdx;
    if (back->flags & surface_info_t::eNeedNewBuffer) {
        getBufferLocked(backIdx);
    }

    const sp<SurfaceBuffer>& backBuffer(mBuffers[backIdx]);
    *buffer = backBuffer.get();

    return NO_ERROR;
}

int Surface::lockBuffer(android_native_buffer_t* buffer)
{
    Mutex::Autolock _l(mSurfaceLock);

    per_client_cblk_t* const cblk = mClient->mControl;
    status_t err = validate(cblk);
    if (err != NO_ERROR)
        return err;

    // FIXME: lockBuffer() needs proper implementation
    return 0;
}

int Surface::queueBuffer(android_native_buffer_t* buffer)
{   
    Mutex::Autolock _l(mSurfaceLock);

    per_client_cblk_t* const cblk = mClient->mControl;
    status_t err = validate(cblk);
    if (err != NO_ERROR)
        return err;

    // transmit the dirty region
    const Region dirty(swapRectangle());
    SurfaceID index(mToken); 
    layer_cblk_t* const lcblk = &(cblk->layers[index]);
    _send_dirty_region(lcblk, dirty);

    uint32_t newstate = cblk->unlock_layer_and_post(size_t(index));
    if (!(newstate & eNextFlipPending))
        mClient->signalServer();

    return NO_ERROR;
}

// ----------------------------------------------------------------------------

status_t Surface::lock(SurfaceInfo* info, bool blocking) {
    return Surface::lock(info, NULL, blocking);
}

status_t Surface::lock(SurfaceInfo* other, Region* dirty, bool blocking) 
{
    // FIXME: needs some locking here
    android_native_buffer_t* backBuffer;
    status_t err = dequeueBuffer(&backBuffer);
    if (err == NO_ERROR) {
        err = lockBuffer(backBuffer);
        if (err == NO_ERROR) {
            backBuffer->common.incRef(&backBuffer->common);
            mLockedBuffer = backBuffer;
            other->w      = backBuffer->width;
            other->h      = backBuffer->height;
            other->s      = backBuffer->stride;
            other->usage  = backBuffer->usage;
            other->format = backBuffer->format;
            other->bits   = backBuffer->bits;

            // we handle copy-back here...
            
            const Rect bounds(backBuffer->width, backBuffer->height);
            Region newDirtyRegion;

            per_client_cblk_t* const cblk = mClient->mControl;
            layer_cblk_t* const lcblk = &(cblk->layers[SurfaceID(mToken)]);
            volatile const surface_info_t* const back = lcblk->surface + mBackbufferIndex;
            if (back->flags & surface_info_t::eBufferDirty) {
                // content is meaningless in this case and the whole surface
                // needs to be redrawn.
                newDirtyRegion.set(bounds);
                if (dirty) {
                    *dirty = newDirtyRegion;
                }
            } else 
            {
                if (dirty) {
                    dirty->andSelf(Region(bounds));
                    newDirtyRegion = *dirty;
                } else {
                    newDirtyRegion.set(bounds);
                }
                Region copyback;
                if (!(lcblk->flags & eNoCopyBack)) {
                    const Region previousDirtyRegion(dirtyRegion());
                    copyback = previousDirtyRegion.subtract(newDirtyRegion);
                }
                const sp<SurfaceBuffer>& frontBuffer(mBuffers[1-mBackbufferIndex]);
                if (!copyback.isEmpty() && frontBuffer!=0) {
                    // copy front to back
                    copyBlt(backBuffer, frontBuffer.get(), copyback);
                }
            }
            setDirtyRegion(newDirtyRegion);


            Rect lockBounds(backBuffer->width, backBuffer->height);
            if (dirty) {
                lockBounds = dirty->bounds();
            }
            buffer_handle_t handle;
            backBuffer->getHandle(backBuffer, &handle);
            status_t res = BufferMapper::get().lock(handle,
                    GRALLOC_USAGE_SW_READ_OFTEN | GRALLOC_USAGE_SW_WRITE_OFTEN, 
                    lockBounds);
            LOGW_IF(res, "failed locking buffer %d (%p)", 
                    mBackbufferIndex, handle);
            setSwapRectangle(lockBounds);
        }
    }
    return err;
}
    
status_t Surface::unlockAndPost() 
{
    // FIXME: needs some locking here

    if (mLockedBuffer == 0)
        return BAD_VALUE;

    buffer_handle_t handle;
    mLockedBuffer->getHandle(mLockedBuffer, &handle);
    status_t res = BufferMapper::get().unlock(handle);
    LOGW_IF(res, "failed unlocking buffer %d (%p)",
            mBackbufferIndex, handle);

    const Rect dirty(dirtyRegion().bounds());
    setSwapRectangle(dirty);
    status_t err = queueBuffer(mLockedBuffer);
    mLockedBuffer->common.decRef(&mLockedBuffer->common);
    mLockedBuffer = 0;
    return err;
}

void Surface::_send_dirty_region(
        layer_cblk_t* lcblk, const Region& dirty)
{
    const int32_t index = (lcblk->flags & eBufferIndex) >> eBufferIndexShift;
    flat_region_t* flat_region = lcblk->region + index;
    status_t err = dirty.write(flat_region, sizeof(flat_region_t));
    if (err < NO_ERROR) {
        // region doesn't fit, use the bounds
        const Region reg(dirty.bounds());
        reg.write(flat_region, sizeof(flat_region_t));
    }
}


status_t Surface::setLayer(int32_t layer) {
    return mClient->setLayer(this, layer);
}
status_t Surface::setPosition(int32_t x, int32_t y) {
    return mClient->setPosition(this, x, y);
}
status_t Surface::setSize(uint32_t w, uint32_t h) {
    return mClient->setSize(this, w, h);
}
status_t Surface::hide() {
    return mClient->hide(this);
}
status_t Surface::show(int32_t layer) {
    return mClient->show(this, layer);
}
status_t Surface::freeze() {
    return mClient->freeze(this);
}
status_t Surface::unfreeze() {
    return mClient->unfreeze(this);
}
status_t Surface::setFlags(uint32_t flags, uint32_t mask) {
    return mClient->setFlags(this, flags, mask);
}
status_t Surface::setTransparentRegionHint(const Region& transparent) {
    return mClient->setTransparentRegionHint(this, transparent);
}
status_t Surface::setAlpha(float alpha) {
    return mClient->setAlpha(this, alpha);
}
status_t Surface::setMatrix(float dsdx, float dtdx, float dsdy, float dtdy) {
    return mClient->setMatrix(this, dsdx, dtdx, dsdy, dtdy);
}
status_t Surface::setFreezeTint(uint32_t tint) {
    return mClient->setFreezeTint(this, tint);
}

Region Surface::dirtyRegion() const  {
    return mDirtyRegion; 
}
void Surface::setDirtyRegion(const Region& region) const {
    mDirtyRegion = region;
}
const Rect& Surface::swapRectangle() const {
    return mSwapRectangle;
}
void Surface::setSwapRectangle(const Rect& r) {
    mSwapRectangle = r;
}

sp<Surface> Surface::readFromParcel(Parcel* parcel)
{
    sp<SurfaceComposerClient> client;
    ISurfaceFlingerClient::surface_data_t data;
    sp<IBinder> clientBinder= parcel->readStrongBinder();
    sp<ISurface> surface    = interface_cast<ISurface>(parcel->readStrongBinder());
    data.token              = parcel->readInt32();
    data.identity           = parcel->readInt32();
    PixelFormat format      = parcel->readInt32();
    uint32_t flags          = parcel->readInt32();

    if (clientBinder != NULL)
        client = SurfaceComposerClient::clientForConnection(clientBinder);

    return new Surface(client, surface, data, 0, 0, format, flags, false);
}

status_t Surface::writeToParcel(const sp<Surface>& surface, Parcel* parcel)
{
    uint32_t flags=0;
    uint32_t format=0;
    SurfaceID token = -1;
    uint32_t identity = 0;
    sp<SurfaceComposerClient> client;
    sp<ISurface> sur;
    if (Surface::isValid(surface)) {
        token = surface->mToken;
        identity = surface->mIdentity;
        client = surface->mClient;
        sur = surface->mSurface;
        format = surface->mFormat;
        flags = surface->mFlags;
    }
    parcel->writeStrongBinder(client!=0  ? client->connection() : NULL);
    parcel->writeStrongBinder(sur!=0     ? sur->asBinder()      : NULL);
    parcel->writeInt32(token);
    parcel->writeInt32(identity);
    parcel->writeInt32(format);
    parcel->writeInt32(flags);
    return NO_ERROR;
}

bool Surface::isSameSurface(const sp<Surface>& lhs, const sp<Surface>& rhs) 
{
    if (lhs == 0 || rhs == 0)
        return false;
    return lhs->mSurface->asBinder() == rhs->mSurface->asBinder();
}

status_t Surface::getBufferLocked(int index)
{
    status_t err = NO_MEMORY;
    sp<SurfaceBuffer> buffer = mSurface->getBuffer();
    LOGE_IF(buffer==0, "ISurface::getBuffer() returned NULL");
    if (buffer != 0) {
        sp<SurfaceBuffer>& currentBuffer(mBuffers[index]);
        if (currentBuffer != 0) {
            BufferMapper::get().unmap(currentBuffer->getHandle(), this);
            currentBuffer.clear();
        }
        err = BufferMapper::get().map(buffer->getHandle(), &buffer->bits, this);
        LOGW_IF(err, "map(...) failed %d (%s)", err, strerror(-err));
        if (err == NO_ERROR) {
            currentBuffer = buffer;
        }
    }
    return err; 
}

}; // namespace android

