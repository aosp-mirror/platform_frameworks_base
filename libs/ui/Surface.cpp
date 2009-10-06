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
#include <utils/CallStack.h>
#include <binder/IPCThreadState.h>
#include <binder/IMemory.h>
#include <utils/Log.h>

#include <ui/DisplayInfo.h>
#include <ui/GraphicBuffer.h>
#include <ui/GraphicBufferMapper.h>
#include <ui/ISurface.h>
#include <ui/Surface.h>
#include <ui/SurfaceComposerClient.h>
#include <ui/Rect.h>

#include <pixelflinger/pixelflinger.h>

#include <private/ui/SharedBufferStack.h>
#include <private/ui/LayerState.h>

namespace android {

// ----------------------------------------------------------------------

static status_t copyBlt(
        const sp<GraphicBuffer>& dst, 
        const sp<GraphicBuffer>& src, 
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
      mWidth(data.width), mHeight(data.height), mFormat(data.format),
      mFlags(flags)
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

status_t SurfaceControl::validate(SharedClient const* cblk) const
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
    uint32_t identity = cblk->getIdentity(mToken);
    if (mIdentity != identity) {
        LOGE("using an invalid surface id=%d, identity=%u should be %d",
                mToken, mIdentity, identity);
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
      mFormat(surface->mFormat), mFlags(surface->mFlags),
      mBufferMapper(GraphicBufferMapper::get()), mSharedBufferClient(NULL),
      mWidth(surface->mWidth), mHeight(surface->mHeight)
{
    mSharedBufferClient = new SharedBufferClient(
            mClient->mControl, mToken, 2, mIdentity);

    init();
}

Surface::Surface(const Parcel& parcel)
    :  mBufferMapper(GraphicBufferMapper::get()), mSharedBufferClient(NULL)
{
    sp<IBinder> clientBinder = parcel.readStrongBinder();
    mSurface    = interface_cast<ISurface>(parcel.readStrongBinder());
    mToken      = parcel.readInt32();
    mIdentity   = parcel.readInt32();
    mWidth      = parcel.readInt32();
    mHeight     = parcel.readInt32();
    mFormat     = parcel.readInt32();
    mFlags      = parcel.readInt32();

    // FIXME: what does that mean if clientBinder is NULL here?
    if (clientBinder != NULL) {
        mClient = SurfaceComposerClient::clientForConnection(clientBinder);

        mSharedBufferClient = new SharedBufferClient(
                mClient->mControl, mToken, 2, mIdentity);
    }

    init();
}

void Surface::init()
{
    android_native_window_t::setSwapInterval  = setSwapInterval;
    android_native_window_t::dequeueBuffer    = dequeueBuffer;
    android_native_window_t::lockBuffer       = lockBuffer;
    android_native_window_t::queueBuffer      = queueBuffer;
    android_native_window_t::query            = query;
    android_native_window_t::perform          = perform;
    mSwapRectangle.makeInvalid();
    DisplayInfo dinfo;
    SurfaceComposerClient::getDisplayInfo(0, &dinfo);
    const_cast<float&>(android_native_window_t::xdpi) = dinfo.xdpi;
    const_cast<float&>(android_native_window_t::ydpi) = dinfo.ydpi;
    // FIXME: set real values here
    const_cast<int&>(android_native_window_t::minSwapInterval) = 1;
    const_cast<int&>(android_native_window_t::maxSwapInterval) = 1;
    const_cast<uint32_t&>(android_native_window_t::flags) = 0;
    // be default we request a hardware surface
    mUsage = GRALLOC_USAGE_HW_RENDER;
    mNeedFullUpdate = false;
}

Surface::~Surface()
{
    // this is a client-side operation, the surface is destroyed, unmap
    // its buffers in this process.
    for (int i=0 ; i<2 ; i++) {
        if (mBuffers[i] != 0 && mBuffers[i]->handle != 0) {
            getBufferMapper().unregisterBuffer(mBuffers[i]->handle);
        }
    }

    // clear all references and trigger an IPC now, to make sure things
    // happen without delay, since these resources are quite heavy.
    mClient.clear();
    mSurface.clear();
    delete mSharedBufferClient;
    IPCThreadState::self()->flushCommands();
}

sp<SurfaceComposerClient> Surface::getClient() const {
    return mClient;
}

sp<ISurface> Surface::getISurface() const {
    return mSurface;
}

bool Surface::isValid() {
    return mToken>=0 && mClient!=0;
}

status_t Surface::validate(SharedClient const* cblk) const
{
    sp<SurfaceComposerClient> client(getClient());
    if (mToken<0 || mClient==0) {
        LOGE("invalid token (%d, identity=%u) or client (%p)", 
                mToken, mIdentity, client.get());
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
    uint32_t identity = cblk->getIdentity(mToken);
    if (mIdentity != identity) {
        LOGE("using an invalid surface id=%d, identity=%u should be %d",
                mToken, mIdentity, identity);
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

int Surface::setSwapInterval(android_native_window_t* window, int interval) {
    return 0;
}

int Surface::dequeueBuffer(android_native_window_t* window, 
        android_native_buffer_t** buffer) {
    Surface* self = getSelf(window);
    return self->dequeueBuffer(buffer);
}

int Surface::lockBuffer(android_native_window_t* window, 
        android_native_buffer_t* buffer) {
    Surface* self = getSelf(window);
    return self->lockBuffer(buffer);
}

int Surface::queueBuffer(android_native_window_t* window, 
        android_native_buffer_t* buffer) {
    Surface* self = getSelf(window);
    return self->queueBuffer(buffer);
}

int Surface::query(android_native_window_t* window, 
        int what, int* value) {
    Surface* self = getSelf(window);
    return self->query(what, value);
}

int Surface::perform(android_native_window_t* window, 
        int operation, ...) {
    va_list args;
    va_start(args, operation);
    Surface* self = getSelf(window);
    int res = self->perform(operation, args);
    va_end(args);
    return res;
}

// ----------------------------------------------------------------------------

status_t Surface::dequeueBuffer(sp<GraphicBuffer>* buffer) {
    android_native_buffer_t* out;
    status_t err = dequeueBuffer(&out);
    if (err == NO_ERROR) {
        *buffer = GraphicBuffer::getSelf(out);
    }
    return err;
}

// ----------------------------------------------------------------------------


int Surface::dequeueBuffer(android_native_buffer_t** buffer)
{
    sp<SurfaceComposerClient> client(getClient());
    status_t err = validate(client->mControl);
    if (err != NO_ERROR)
        return err;

    ssize_t bufIdx = mSharedBufferClient->dequeue();
    if (bufIdx < 0) {
        LOGE("error dequeuing a buffer (%s)", strerror(bufIdx));
        return bufIdx;
    }

    // below we make sure we AT LEAST have the usage flags we want
    const uint32_t usage(getUsage());
    const sp<GraphicBuffer>& backBuffer(mBuffers[bufIdx]);
    if (backBuffer == 0 || 
        ((uint32_t(backBuffer->usage) & usage) != usage) ||
        mSharedBufferClient->needNewBuffer(bufIdx)) 
    {
        err = getBufferLocked(bufIdx, usage);
        LOGE_IF(err, "getBufferLocked(%ld, %08x) failed (%s)",
                bufIdx, usage, strerror(-err));
        if (err == NO_ERROR) {
            // reset the width/height with the what we get from the buffer
            mWidth  = uint32_t(backBuffer->width);
            mHeight = uint32_t(backBuffer->height);
        }
    }

    // if we still don't have a buffer here, we probably ran out of memory
    if (!err && backBuffer==0) {
        err = NO_MEMORY;
    }

    if (err == NO_ERROR) {
        mDirtyRegion.set(backBuffer->width, backBuffer->height);
        *buffer = backBuffer.get();
    } else {
        mSharedBufferClient->undoDequeue(bufIdx);
    }

    return err;
}

int Surface::lockBuffer(android_native_buffer_t* buffer)
{
    sp<SurfaceComposerClient> client(getClient());
    status_t err = validate(client->mControl);
    if (err != NO_ERROR)
        return err;

    int32_t bufIdx = GraphicBuffer::getSelf(buffer)->getIndex();
    err = mSharedBufferClient->lock(bufIdx);
    LOGE_IF(err, "error locking buffer %d (%s)", bufIdx, strerror(-err));
    return err;
}

int Surface::queueBuffer(android_native_buffer_t* buffer)
{   
    sp<SurfaceComposerClient> client(getClient());
    status_t err = validate(client->mControl);
    if (err != NO_ERROR)
        return err;

    if (mSwapRectangle.isValid()) {
        mDirtyRegion.set(mSwapRectangle);
    }
    
    int32_t bufIdx = GraphicBuffer::getSelf(buffer)->getIndex();
    mSharedBufferClient->setDirtyRegion(bufIdx, mDirtyRegion);
    err = mSharedBufferClient->queue(bufIdx);
    LOGE_IF(err, "error queuing buffer %d (%s)", bufIdx, strerror(-err));

    if (err == NO_ERROR) {
        // FIXME: can we avoid this IPC if we know there is one pending?
        client->signalServer();
    }
    return err;
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
    case NATIVE_WINDOW_FORMAT:
        *value = int(mFormat);
        return NO_ERROR;
    }
    return BAD_VALUE;
}

int Surface::perform(int operation, va_list args)
{
    int res = NO_ERROR;
    switch (operation) {
        case NATIVE_WINDOW_SET_USAGE:
            setUsage( va_arg(args, int) );
            break;
        default:
            res = NAME_NOT_FOUND;
            break;
    }
    return res;
}

void Surface::setUsage(uint32_t reqUsage)
{
    Mutex::Autolock _l(mSurfaceLock);
    mUsage = reqUsage;
}

uint32_t Surface::getUsage() const
{
    Mutex::Autolock _l(mSurfaceLock);
    return mUsage;
}

// ----------------------------------------------------------------------------

status_t Surface::lock(SurfaceInfo* info, bool blocking) {
    return Surface::lock(info, NULL, blocking);
}

status_t Surface::lock(SurfaceInfo* other, Region* dirtyIn, bool blocking) 
{
    if (mApiLock.tryLock() != NO_ERROR) {
        LOGE("calling Surface::lock() from different threads!");
        CallStack stack;
        stack.update();
        stack.dump("Surface::lock called from different threads");
        return WOULD_BLOCK;
    }
    
    // we're intending to do software rendering from this point
    setUsage(GRALLOC_USAGE_SW_READ_OFTEN | GRALLOC_USAGE_SW_WRITE_OFTEN);

    sp<GraphicBuffer> backBuffer;
    status_t err = dequeueBuffer(&backBuffer);
    LOGE_IF(err, "dequeueBuffer failed (%s)", strerror(-err));
    if (err == NO_ERROR) {
        err = lockBuffer(backBuffer.get());
        LOGE_IF(err, "lockBuffer (idx=%d) failed (%s)",
                backBuffer->getIndex(), strerror(-err));
        if (err == NO_ERROR) {
            // we handle copy-back here...

            const Rect bounds(backBuffer->width, backBuffer->height);
            Region scratch(bounds);
            Region& newDirtyRegion(dirtyIn ? *dirtyIn : scratch);

            if (mNeedFullUpdate) {
                // reset newDirtyRegion to bounds when a buffer is reallocated
                // it would be better if this information was associated with
                // the buffer and made available to outside of Surface.
                // This will do for now though.
                mNeedFullUpdate = false;
                newDirtyRegion.set(bounds);
            } else {
                newDirtyRegion.andSelf(bounds);
            }

            const sp<GraphicBuffer>& frontBuffer(mPostedBuffer);
            if (frontBuffer !=0 &&
                backBuffer->width  == frontBuffer->width && 
                backBuffer->height == frontBuffer->height &&
                !(mFlags & ISurfaceComposer::eDestroyBackbuffer)) 
            {
                const Region copyback(mOldDirtyRegion.subtract(newDirtyRegion));
                if (!copyback.isEmpty() && frontBuffer!=0) {
                    // copy front to back
                    copyBlt(backBuffer, frontBuffer, copyback);
                }
            }

            mDirtyRegion = newDirtyRegion;
            mOldDirtyRegion = newDirtyRegion;

            void* vaddr;
            status_t res = backBuffer->lock(
                    GRALLOC_USAGE_SW_READ_OFTEN | GRALLOC_USAGE_SW_WRITE_OFTEN,
                    newDirtyRegion.bounds(), &vaddr);
            
            LOGW_IF(res, "failed locking buffer (handle = %p)", 
                    backBuffer->handle);

            mLockedBuffer = backBuffer;
            other->w      = backBuffer->width;
            other->h      = backBuffer->height;
            other->s      = backBuffer->stride;
            other->usage  = backBuffer->usage;
            other->format = backBuffer->format;
            other->bits   = vaddr;
        }
    }
    mApiLock.unlock();
    return err;
}
    
status_t Surface::unlockAndPost() 
{
    if (mLockedBuffer == 0) {
        LOGE("unlockAndPost failed, no locked buffer");
        return BAD_VALUE;
    }

    status_t err = mLockedBuffer->unlock();
    LOGE_IF(err, "failed unlocking buffer (%p)", mLockedBuffer->handle);
    
    err = queueBuffer(mLockedBuffer.get());
    LOGE_IF(err, "queueBuffer (idx=%d) failed (%s)",
            mLockedBuffer->getIndex(), strerror(-err));

    mPostedBuffer = mLockedBuffer;
    mLockedBuffer = 0;
    return err;
}

void Surface::setSwapRectangle(const Rect& r) {
    Mutex::Autolock _l(mSurfaceLock);
    mSwapRectangle = r;
}

status_t Surface::getBufferLocked(int index, int usage)
{
    sp<ISurface> s(mSurface);
    if (s == 0) return NO_INIT;

    status_t err = NO_MEMORY;

    // free the current buffer
    sp<GraphicBuffer>& currentBuffer(mBuffers[index]);
    if (currentBuffer != 0) {
        getBufferMapper().unregisterBuffer(currentBuffer->handle);
        currentBuffer.clear();
    }

    sp<GraphicBuffer> buffer = s->requestBuffer(index, usage);
    LOGE_IF(buffer==0,
            "ISurface::getBuffer(%d, %08x) returned NULL",
            index, usage);
    if (buffer != 0) { // this should never happen by construction
        LOGE_IF(buffer->handle == NULL, 
                "Surface (identity=%d) requestBuffer(%d, %08x) returned"
                "a buffer with a null handle", mIdentity, index, usage);
        err = mSharedBufferClient->getStatus();
        LOGE_IF(err,  "Surface (identity=%d) state = %d", mIdentity, err);
        if (!err && buffer->handle != NULL) {
            err = getBufferMapper().registerBuffer(buffer->handle);
            LOGW_IF(err, "registerBuffer(...) failed %d (%s)",
                    err, strerror(-err));
            if (err == NO_ERROR) {
                currentBuffer = buffer;
                currentBuffer->setIndex(index);
                mNeedFullUpdate = true;
            }
        } else {
            err = err<0 ? err : NO_MEMORY;
        }
    }
    return err; 
}

}; // namespace android

