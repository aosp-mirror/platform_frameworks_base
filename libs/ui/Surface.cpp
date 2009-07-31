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
#include <binder/IPCThreadState.h>
#include <binder/IMemory.h>
#include <utils/Log.h>

#include <ui/DisplayInfo.h>
#include <ui/BufferMapper.h>
#include <ui/ISurface.h>
#include <ui/Surface.h>
#include <ui/SurfaceComposerClient.h>
#include <ui/Rect.h>

#include <pixelflinger/pixelflinger.h>

#include <private/ui/SharedState.h>
#include <private/ui/LayerState.h>
#include <private/ui/SurfaceBuffer.h>

namespace android {

// ============================================================================
//  SurfaceBuffer
// ============================================================================

SurfaceBuffer::SurfaceBuffer() 
    : BASE(), mOwner(false), mBufferMapper(BufferMapper::get())
{
    width  = 
    height = 
    stride = 
    format = 
    usage  = 0;
    handle = NULL;
}

SurfaceBuffer::SurfaceBuffer(const Parcel& data) 
    : BASE(), mOwner(true), mBufferMapper(BufferMapper::get())
{
    // we own the handle in this case
    width  = data.readInt32();
    height = data.readInt32();
    stride = data.readInt32();
    format = data.readInt32();
    usage  = data.readInt32();
    handle = data.readNativeHandle();
}

SurfaceBuffer::~SurfaceBuffer()
{
    if (handle && mOwner) {
        native_handle_close(handle);
        native_handle_delete(const_cast<native_handle*>(handle));
    }
}

status_t SurfaceBuffer::lock(uint32_t usage, void** vaddr)
{
    const Rect lockBounds(width, height);
    status_t res = lock(usage, lockBounds, vaddr);
    return res;
}

status_t SurfaceBuffer::lock(uint32_t usage, const Rect& rect, void** vaddr)
{
    if (rect.left < 0 || rect.right  > this->width || 
        rect.top  < 0 || rect.bottom > this->height) {
        LOGE("locking pixels (%d,%d,%d,%d) outside of buffer (w=%d, h=%d)",
                rect.left, rect.top, rect.right, rect.bottom, 
                this->width, this->height);
        return BAD_VALUE;
    }
    status_t res = getBufferMapper().lock(handle, usage, rect, vaddr);
    return res;
}

status_t SurfaceBuffer::unlock()
{
    status_t res = getBufferMapper().unlock(handle);
    return res;
}

status_t SurfaceBuffer::writeToParcel(Parcel* reply, 
        android_native_buffer_t const* buffer)
{
    reply->writeInt32(buffer->width);
    reply->writeInt32(buffer->height);
    reply->writeInt32(buffer->stride);
    reply->writeInt32(buffer->format);
    reply->writeInt32(buffer->usage);
    reply->writeNativeHandle(buffer->handle);
    return NO_ERROR;
}

// ----------------------------------------------------------------------

static status_t copyBlt(
        const sp<SurfaceBuffer>& dst, 
        const sp<SurfaceBuffer>& src, 
        const Region& reg)
{
    status_t err;
    uint8_t const * src_bits = NULL;
    err = src->lock(GRALLOC_USAGE_SW_READ_OFTEN, reg.bounds(), (void**)&src_bits);
    LOGE_IF(err, "error locking src buffer %s", strerror(-err));

    uint8_t* dst_bits = NULL;
    err = dst->lock(GRALLOC_USAGE_SW_WRITE_OFTEN, reg.bounds(), (void**)&dst_bits);
    LOGE_IF(err, "error locking dst buffer %s", strerror(-err));

    Region::const_iterator head(reg.begin());
    Region::const_iterator tail(reg.end());
    if (head != tail && src_bits && dst_bits) {
        // NOTE: dst and src must be the same format
        const size_t bpp = bytesPerPixel(src->format);
        const size_t dbpr = dst->stride * bpp;
        const size_t sbpr = src->stride * bpp;

        while (head != tail) {
            const Rect& r(*head++);
            ssize_t h = r.height();
            if (h <= 0) continue;
            size_t size = r.width() * bpp;
            uint8_t const * s = src_bits + (r.left + src->stride * r.top) * bpp;
            uint8_t       * d = dst_bits + (r.left + dst->stride * r.top) * bpp;
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
    
    if (src_bits)
        src->unlock();
    
    if (dst_bits)
        dst->unlock();
    
    return err;
}

// ============================================================================
//  SurfaceControl
// ============================================================================

SurfaceControl::SurfaceControl(
        const sp<SurfaceComposerClient>& client, 
        const sp<ISurface>& surface,
        const ISurfaceFlingerClient::surface_data_t& data,
        uint32_t w, uint32_t h, PixelFormat format, uint32_t flags)
    : mClient(client), mSurface(surface),
      mToken(data.token), mIdentity(data.identity),
      mWidth(w), mHeight(h), mFormat(format), mFlags(flags)
{
}
        
SurfaceControl::~SurfaceControl()
{
    destroy();
}

void SurfaceControl::destroy()
{
    if (isValid()) {
        mClient->destroySurface(mToken);
    }

    // clear all references and trigger an IPC now, to make sure things
    // happen without delay, since these resources are quite heavy.
    mClient.clear();
    mSurface.clear();
    IPCThreadState::self()->flushCommands();
}

void SurfaceControl::clear() 
{
    // here, the window manager tells us explicitly that we should destroy
    // the surface's resource. Soon after this call, it will also release
    // its last reference (which will call the dtor); however, it is possible
    // that a client living in the same process still holds references which
    // would delay the call to the dtor -- that is why we need this explicit
    // "clear()" call.
    destroy();
}

bool SurfaceControl::isSameSurface(
        const sp<SurfaceControl>& lhs, const sp<SurfaceControl>& rhs) 
{
    if (lhs == 0 || rhs == 0)
        return false;
    return lhs->mSurface->asBinder() == rhs->mSurface->asBinder();
}

status_t SurfaceControl::setLayer(int32_t layer) {
    const sp<SurfaceComposerClient>& client(mClient);
    if (client == 0) return NO_INIT;
    status_t err = validate(client->mControl);
    if (err < 0) return err;
    return client->setLayer(mToken, layer);
}
status_t SurfaceControl::setPosition(int32_t x, int32_t y) {
    const sp<SurfaceComposerClient>& client(mClient);
    if (client == 0) return NO_INIT;
    status_t err = validate(client->mControl);
    if (err < 0) return err;
    return client->setPosition(mToken, x, y);
}
status_t SurfaceControl::setSize(uint32_t w, uint32_t h) {
    const sp<SurfaceComposerClient>& client(mClient);
    if (client == 0) return NO_INIT;
    status_t err = validate(client->mControl);
    if (err < 0) return err;
    return client->setSize(mToken, w, h);
}
status_t SurfaceControl::hide() {
    const sp<SurfaceComposerClient>& client(mClient);
    if (client == 0) return NO_INIT;
    status_t err = validate(client->mControl);
    if (err < 0) return err;
    return client->hide(mToken);
}
status_t SurfaceControl::show(int32_t layer) {
    const sp<SurfaceComposerClient>& client(mClient);
    if (client == 0) return NO_INIT;
    status_t err = validate(client->mControl);
    if (err < 0) return err;
    return client->show(mToken, layer);
}
status_t SurfaceControl::freeze() {
    const sp<SurfaceComposerClient>& client(mClient);
    if (client == 0) return NO_INIT;
    status_t err = validate(client->mControl);
    if (err < 0) return err;
    return client->freeze(mToken);
}
status_t SurfaceControl::unfreeze() {
    const sp<SurfaceComposerClient>& client(mClient);
    if (client == 0) return NO_INIT;
    status_t err = validate(client->mControl);
    if (err < 0) return err;
    return client->unfreeze(mToken);
}
status_t SurfaceControl::setFlags(uint32_t flags, uint32_t mask) {
    const sp<SurfaceComposerClient>& client(mClient);
    if (client == 0) return NO_INIT;
    status_t err = validate(client->mControl);
    if (err < 0) return err;
    return client->setFlags(mToken, flags, mask);
}
status_t SurfaceControl::setTransparentRegionHint(const Region& transparent) {
    const sp<SurfaceComposerClient>& client(mClient);
    if (client == 0) return NO_INIT;
    status_t err = validate(client->mControl);
    if (err < 0) return err;
    return client->setTransparentRegionHint(mToken, transparent);
}
status_t SurfaceControl::setAlpha(float alpha) {
    const sp<SurfaceComposerClient>& client(mClient);
    if (client == 0) return NO_INIT;
    status_t err = validate(client->mControl);
    if (err < 0) return err;
    return client->setAlpha(mToken, alpha);
}
status_t SurfaceControl::setMatrix(float dsdx, float dtdx, float dsdy, float dtdy) {
    const sp<SurfaceComposerClient>& client(mClient);
    if (client == 0) return NO_INIT;
    status_t err = validate(client->mControl);
    if (err < 0) return err;
    return client->setMatrix(mToken, dsdx, dtdx, dsdy, dtdy);
}
status_t SurfaceControl::setFreezeTint(uint32_t tint) {
    const sp<SurfaceComposerClient>& client(mClient);
    if (client == 0) return NO_INIT;
    status_t err = validate(client->mControl);
    if (err < 0) return err;
    return client->setFreezeTint(mToken, tint);
}

status_t SurfaceControl::validate(per_client_cblk_t const* cblk) const
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

status_t SurfaceControl::writeSurfaceToParcel(
        const sp<SurfaceControl>& control, Parcel* parcel)
{
    uint32_t flags = 0;
    uint32_t format = 0;
    SurfaceID token = -1;
    uint32_t identity = 0;
    uint32_t width = 0;
    uint32_t height = 0;
    sp<SurfaceComposerClient> client;
    sp<ISurface> sur;
    if (SurfaceControl::isValid(control)) {
        token    = control->mToken;
        identity = control->mIdentity;
        client   = control->mClient;
        sur      = control->mSurface;
        width    = control->mWidth;
        height   = control->mHeight;
        format   = control->mFormat;
        flags    = control->mFlags;
    }
    parcel->writeStrongBinder(client!=0  ? client->connection() : NULL);
    parcel->writeStrongBinder(sur!=0     ? sur->asBinder()      : NULL);
    parcel->writeInt32(token);
    parcel->writeInt32(identity);
    parcel->writeInt32(width);
    parcel->writeInt32(height);
    parcel->writeInt32(format);
    parcel->writeInt32(flags);
    return NO_ERROR;
}

sp<Surface> SurfaceControl::getSurface() const
{
    Mutex::Autolock _l(mLock);
    if (mSurfaceData == 0) {
        mSurfaceData = new Surface(const_cast<SurfaceControl*>(this));
    }
    return mSurfaceData;
}

// ============================================================================
//  Surface
// ============================================================================

Surface::Surface(const sp<SurfaceControl>& surface)
    : mClient(surface->mClient), mSurface(surface->mSurface),
      mToken(surface->mToken), mIdentity(surface->mIdentity),
      mWidth(surface->mWidth), mHeight(surface->mHeight),
      mFormat(surface->mFormat), mFlags(surface->mFlags),
      mBufferMapper(BufferMapper::get())
{
    init();
}

Surface::Surface(const Parcel& parcel)
    :  mBufferMapper(BufferMapper::get())
{
    sp<IBinder> clientBinder = parcel.readStrongBinder();
    mSurface    = interface_cast<ISurface>(parcel.readStrongBinder());
    mToken      = parcel.readInt32();
    mIdentity   = parcel.readInt32();
    mWidth      = parcel.readInt32();
    mHeight     = parcel.readInt32();
    mFormat     = parcel.readInt32();
    mFlags      = parcel.readInt32();

    if (clientBinder != NULL)
        mClient = SurfaceComposerClient::clientForConnection(clientBinder);

    init();
}

void Surface::init()
{
    android_native_window_t::setSwapInterval  = setSwapInterval;
    android_native_window_t::dequeueBuffer    = dequeueBuffer;
    android_native_window_t::lockBuffer       = lockBuffer;
    android_native_window_t::queueBuffer      = queueBuffer;
    android_native_window_t::query            = query;
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
            getBufferMapper().unregisterBuffer(mBuffers[i]->handle);
        }
    }

    // clear all references and trigger an IPC now, to make sure things
    // happen without delay, since these resources are quite heavy.
    mClient.clear();
    mSurface.clear();
    IPCThreadState::self()->flushCommands();
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


bool Surface::isSameSurface(
        const sp<Surface>& lhs, const sp<Surface>& rhs) 
{
    if (lhs == 0 || rhs == 0)
        return false;
    return lhs->mSurface->asBinder() == rhs->mSurface->asBinder();
}

// ----------------------------------------------------------------------------

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

int Surface::query(android_native_window_t* window, 
        int what, int* value)
{
    Surface* self = getSelf(window);
    return self->query(what, value);
}

// ----------------------------------------------------------------------------

status_t Surface::dequeueBuffer(sp<SurfaceBuffer>* buffer)
{
    android_native_buffer_t* out;
    status_t err = dequeueBuffer(&out);
    *buffer = SurfaceBuffer::getSelf(out);
    // reset the width/height with the what we get from the buffer
    mWidth  = uint32_t(out->width);
    mHeight = uint32_t(out->height);
    return err;
}

status_t Surface::lockBuffer(const sp<SurfaceBuffer>& buffer)
{
    return lockBuffer(buffer.get());
}

status_t Surface::queueBuffer(const sp<SurfaceBuffer>& buffer)
{
    return queueBuffer(buffer.get());
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
        err = getBufferLocked(backIdx);
    }

    if (err == NO_ERROR) {
        const sp<SurfaceBuffer>& backBuffer(mBuffers[backIdx]);
        mDirtyRegion.set(backBuffer->width, backBuffer->height);
        *buffer = backBuffer.get();
    }
  
    return err;
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

    if (mSwapRectangle.isValid()) {
        mDirtyRegion.set(mSwapRectangle);
    }
    
    // transmit the dirty region
    SurfaceID index(mToken); 
    layer_cblk_t* const lcblk = &(cblk->layers[index]);
    _send_dirty_region(lcblk, mDirtyRegion);

    uint32_t newstate = cblk->unlock_layer_and_post(size_t(index));
    if (!(newstate & eNextFlipPending))
        mClient->signalServer();

    return NO_ERROR;
}

int Surface::query(int what, int* value)
{
    switch (what) {
        case NATIVE_WINDOW_WIDTH:
            *value = int(mWidth);
            return NO_ERROR;
        case NATIVE_WINDOW_HEIGHT:
            *value = int(mHeight);
            return NO_ERROR;
    }
    return BAD_VALUE;
}

// ----------------------------------------------------------------------------

status_t Surface::lock(SurfaceInfo* info, bool blocking) {
    return Surface::lock(info, NULL, blocking);
}

status_t Surface::lock(SurfaceInfo* other, Region* dirtyIn, bool blocking) 
{
    // FIXME: needs some locking here
    
    sp<SurfaceBuffer> backBuffer;
    status_t err = dequeueBuffer(&backBuffer);
    if (err == NO_ERROR) {
        err = lockBuffer(backBuffer);
        if (err == NO_ERROR) {
            // we handle copy-back here...
            
            const Rect bounds(backBuffer->width, backBuffer->height);
            Region scratch(bounds);
            Region& newDirtyRegion(dirtyIn ? *dirtyIn : scratch);

            per_client_cblk_t* const cblk = mClient->mControl;
            layer_cblk_t* const lcblk = &(cblk->layers[SurfaceID(mToken)]);
            volatile const surface_info_t* const back = lcblk->surface + mBackbufferIndex;
            if (back->flags & surface_info_t::eBufferDirty) {
                // content is meaningless in this case and the whole surface
                // needs to be redrawn.
                newDirtyRegion.set(bounds);
            } else {
                newDirtyRegion.andSelf(bounds);
                const sp<SurfaceBuffer>& frontBuffer(mBuffers[1-mBackbufferIndex]);
                if (backBuffer->width  == frontBuffer->width && 
                    backBuffer->height == frontBuffer->height &&
                    !(lcblk->flags & eNoCopyBack)) 
                {
                    const Region copyback(mOldDirtyRegion.subtract(newDirtyRegion));
                    if (!copyback.isEmpty() && frontBuffer!=0) {
                        // copy front to back
                        copyBlt(backBuffer, frontBuffer, copyback);
                    }
                }
            }
            mDirtyRegion = newDirtyRegion;
            mOldDirtyRegion = newDirtyRegion;

            void* vaddr;
            status_t res = backBuffer->lock(
                    GRALLOC_USAGE_SW_READ_OFTEN | GRALLOC_USAGE_SW_WRITE_OFTEN,
                    newDirtyRegion.bounds(), &vaddr);
            
            LOGW_IF(res, "failed locking buffer %d (%p)", 
                    mBackbufferIndex, backBuffer->handle);

            mLockedBuffer = backBuffer;
            other->w      = backBuffer->width;
            other->h      = backBuffer->height;
            other->s      = backBuffer->stride;
            other->usage  = backBuffer->usage;
            other->format = backBuffer->format;
            other->bits   = vaddr;
        }
    }
    return err;
}
    
status_t Surface::unlockAndPost() 
{
    // FIXME: needs some locking here

    if (mLockedBuffer == 0)
        return BAD_VALUE;

    status_t res = mLockedBuffer->unlock();
    LOGW_IF(res, "failed unlocking buffer %d (%p)",
            mBackbufferIndex, mLockedBuffer->handle);
    
    status_t err = queueBuffer(mLockedBuffer);
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

void Surface::setSwapRectangle(const Rect& r) {
    mSwapRectangle = r;
}

status_t Surface::getBufferLocked(int index)
{
    status_t err = NO_MEMORY;
    sp<SurfaceBuffer> buffer = mSurface->getBuffer();
    LOGE_IF(buffer==0, "ISurface::getBuffer() returned NULL");
    if (buffer != 0) {
        sp<SurfaceBuffer>& currentBuffer(mBuffers[index]);
        if (currentBuffer != 0) {
            getBufferMapper().unregisterBuffer(currentBuffer->handle);
            currentBuffer.clear();
        }
        err = getBufferMapper().registerBuffer(buffer->handle);
        LOGW_IF(err, "registerBuffer(...) failed %d (%s)", err, strerror(-err));
        if (err == NO_ERROR) {
            currentBuffer = buffer;
        }
    }
    return err; 
}

}; // namespace android

