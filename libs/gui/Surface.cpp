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
#include <errno.h>
#include <sys/types.h>
#include <sys/stat.h>

#include <utils/CallStack.h>
#include <utils/Errors.h>
#include <utils/Log.h>
#include <utils/threads.h>

#include <binder/IMemory.h>
#include <binder/IPCThreadState.h>

#include <gui/SurfaceTextureClient.h>

#include <ui/DisplayInfo.h>
#include <ui/GraphicBuffer.h>
#include <ui/GraphicBufferMapper.h>
#include <ui/GraphicLog.h>
#include <ui/Rect.h>

#include <surfaceflinger/ISurface.h>
#include <surfaceflinger/ISurfaceComposer.h>
#include <surfaceflinger/Surface.h>
#include <surfaceflinger/SurfaceComposerClient.h>

#include <private/surfaceflinger/LayerState.h>

namespace android {

// ----------------------------------------------------------------------

static status_t copyBlt(
        const sp<GraphicBuffer>& dst, 
        const sp<GraphicBuffer>& src, 
        const Region& reg)
{
    // src and dst with, height and format must be identical. no verification
    // is done here.
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
        const ISurfaceComposerClient::surface_data_t& data,
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
    status_t err = validate();
    if (err < 0) return err;
    const sp<SurfaceComposerClient>& client(mClient);
    return client->setLayer(mToken, layer);
}
status_t SurfaceControl::setPosition(int32_t x, int32_t y) {
    status_t err = validate();
    if (err < 0) return err;
    const sp<SurfaceComposerClient>& client(mClient);
    return client->setPosition(mToken, x, y);
}
status_t SurfaceControl::setSize(uint32_t w, uint32_t h) {
    status_t err = validate();
    if (err < 0) return err;
    const sp<SurfaceComposerClient>& client(mClient);
    return client->setSize(mToken, w, h);
}
status_t SurfaceControl::hide() {
    status_t err = validate();
    if (err < 0) return err;
    const sp<SurfaceComposerClient>& client(mClient);
    return client->hide(mToken);
}
status_t SurfaceControl::show(int32_t layer) {
    status_t err = validate();
    if (err < 0) return err;
    const sp<SurfaceComposerClient>& client(mClient);
    return client->show(mToken, layer);
}
status_t SurfaceControl::freeze() {
    status_t err = validate();
    if (err < 0) return err;
    const sp<SurfaceComposerClient>& client(mClient);
    return client->freeze(mToken);
}
status_t SurfaceControl::unfreeze() {
    status_t err = validate();
    if (err < 0) return err;
    const sp<SurfaceComposerClient>& client(mClient);
    return client->unfreeze(mToken);
}
status_t SurfaceControl::setFlags(uint32_t flags, uint32_t mask) {
    status_t err = validate();
    if (err < 0) return err;
    const sp<SurfaceComposerClient>& client(mClient);
    return client->setFlags(mToken, flags, mask);
}
status_t SurfaceControl::setTransparentRegionHint(const Region& transparent) {
    status_t err = validate();
    if (err < 0) return err;
    const sp<SurfaceComposerClient>& client(mClient);
    return client->setTransparentRegionHint(mToken, transparent);
}
status_t SurfaceControl::setAlpha(float alpha) {
    status_t err = validate();
    if (err < 0) return err;
    const sp<SurfaceComposerClient>& client(mClient);
    return client->setAlpha(mToken, alpha);
}
status_t SurfaceControl::setMatrix(float dsdx, float dtdx, float dsdy, float dtdy) {
    status_t err = validate();
    if (err < 0) return err;
    const sp<SurfaceComposerClient>& client(mClient);
    return client->setMatrix(mToken, dsdx, dtdx, dsdy, dtdy);
}
status_t SurfaceControl::setFreezeTint(uint32_t tint) {
    status_t err = validate();
    if (err < 0) return err;
    const sp<SurfaceComposerClient>& client(mClient);
    return client->setFreezeTint(mToken, tint);
}

status_t SurfaceControl::validate() const
{
    if (mToken<0 || mClient==0) {
        LOGE("invalid token (%d, identity=%u) or client (%p)", 
                mToken, mIdentity, mClient.get());
        return NO_INIT;
    }
    return NO_ERROR;
}

status_t SurfaceControl::writeSurfaceToParcel(
        const sp<SurfaceControl>& control, Parcel* parcel)
{
    sp<ISurface> sur;
    uint32_t identity = 0;
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t format = 0;
    uint32_t flags = 0;
    if (SurfaceControl::isValid(control)) {
        sur      = control->mSurface;
        identity = control->mIdentity;
        width    = control->mWidth;
        height   = control->mHeight;
        format   = control->mFormat;
        flags    = control->mFlags;
    }
    parcel->writeStrongBinder(sur!=0 ? sur->asBinder() : NULL);
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

// ---------------------------------------------------------------------------

Surface::Surface(const sp<SurfaceControl>& surface)
    : mInitCheck(NO_INIT),
      mSurface(surface->mSurface),
      mIdentity(surface->mIdentity),
      mFormat(surface->mFormat), mFlags(surface->mFlags),
      mWidth(surface->mWidth), mHeight(surface->mHeight)
{
    init();
}

Surface::Surface(const Parcel& parcel, const sp<IBinder>& ref)
    : mInitCheck(NO_INIT)
{
    mSurface    = interface_cast<ISurface>(ref);
    mIdentity   = parcel.readInt32();
    mWidth      = parcel.readInt32();
    mHeight     = parcel.readInt32();
    mFormat     = parcel.readInt32();
    mFlags      = parcel.readInt32();
    init();
}

status_t Surface::writeToParcel(
        const sp<Surface>& surface, Parcel* parcel)
{
    sp<ISurface> sur;
    uint32_t identity = 0;
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t format = 0;
    uint32_t flags = 0;
    if (Surface::isValid(surface)) {
        sur      = surface->mSurface;
        identity = surface->mIdentity;
        width    = surface->mWidth;
        height   = surface->mHeight;
        format   = surface->mFormat;
        flags    = surface->mFlags;
    } else if (surface != 0 && surface->mSurface != 0) {
        LOGW("Parceling invalid surface with non-NULL ISurface as NULL: "
             "mSurface = %p, mIdentity = %d, mWidth = %d, mHeight = %d, "
             "mFormat = %d, mFlags = 0x%08x, mInitCheck = %d",
             surface->mSurface.get(), surface->mIdentity, surface->mWidth,
             surface->mHeight, surface->mFormat, surface->mFlags,
             surface->mInitCheck);
    }
    parcel->writeStrongBinder(sur!=0 ? sur->asBinder() : NULL);
    parcel->writeInt32(identity);
    parcel->writeInt32(width);
    parcel->writeInt32(height);
    parcel->writeInt32(format);
    parcel->writeInt32(flags);
    return NO_ERROR;

}

Mutex Surface::sCachedSurfacesLock;
DefaultKeyedVector<wp<IBinder>, wp<Surface> > Surface::sCachedSurfaces;

sp<Surface> Surface::readFromParcel(const Parcel& data) {
    Mutex::Autolock _l(sCachedSurfacesLock);
    sp<IBinder> binder(data.readStrongBinder());
    sp<Surface> surface = sCachedSurfaces.valueFor(binder).promote();
    if (surface == 0) {
       surface = new Surface(data, binder);
       sCachedSurfaces.add(binder, surface);
    }
    if (surface->mSurface == 0) {
      surface = 0;
    }
    cleanCachedSurfacesLocked();
    return surface;
}

// Remove the stale entries from the surface cache.  This should only be called
// with sCachedSurfacesLock held.
void Surface::cleanCachedSurfacesLocked() {
    for (int i = sCachedSurfaces.size()-1; i >= 0; --i) {
        wp<Surface> s(sCachedSurfaces.valueAt(i));
        if (s == 0 || s.promote() == 0) {
            sCachedSurfaces.removeItemsAt(i);
        }
    }
}

void Surface::init()
{
    ANativeWindow::setSwapInterval  = setSwapInterval;
    ANativeWindow::dequeueBuffer    = dequeueBuffer;
    ANativeWindow::cancelBuffer     = cancelBuffer;
    ANativeWindow::lockBuffer       = lockBuffer;
    ANativeWindow::queueBuffer      = queueBuffer;
    ANativeWindow::query            = query;
    ANativeWindow::perform          = perform;

    if (mSurface != NULL) {
        sp<ISurfaceTexture> surfaceTexture(mSurface->getSurfaceTexture());
        LOGE_IF(surfaceTexture==0, "got a NULL ISurfaceTexture from ISurface");
        if (surfaceTexture != NULL) {
            mSurfaceTextureClient = new SurfaceTextureClient(surfaceTexture);
            mSurfaceTextureClient->setUsage(GraphicBuffer::USAGE_HW_RENDER);
        }

        DisplayInfo dinfo;
        SurfaceComposerClient::getDisplayInfo(0, &dinfo);
        const_cast<float&>(ANativeWindow::xdpi) = dinfo.xdpi;
        const_cast<float&>(ANativeWindow::ydpi) = dinfo.ydpi;

        const_cast<int&>(ANativeWindow::minSwapInterval) =
                mSurfaceTextureClient->minSwapInterval;

        const_cast<int&>(ANativeWindow::maxSwapInterval) =
                mSurfaceTextureClient->maxSwapInterval;

        const_cast<uint32_t&>(ANativeWindow::flags) = 0;

        if (mSurfaceTextureClient != 0) {
            mInitCheck = NO_ERROR;
        }
    }
}

Surface::~Surface()
{
    // clear all references and trigger an IPC now, to make sure things
    // happen without delay, since these resources are quite heavy.
    mSurfaceTextureClient.clear();
    mSurface.clear();
    IPCThreadState::self()->flushCommands();
}

bool Surface::isValid() {
    return mInitCheck == NO_ERROR;
}

status_t Surface::validate(bool inCancelBuffer) const
{
    // check that we initialized ourself properly
    if (mInitCheck != NO_ERROR) {
        LOGE("invalid token (identity=%u)", mIdentity);
        return mInitCheck;
    }
    return NO_ERROR;
}

sp<ISurfaceTexture> Surface::getSurfaceTexture() {
    return mSurface != NULL ? mSurface->getSurfaceTexture() : NULL;
}

sp<IBinder> Surface::asBinder() const {
    return mSurface!=0 ? mSurface->asBinder() : 0;
}

// ----------------------------------------------------------------------------

int Surface::setSwapInterval(ANativeWindow* window, int interval) {
    Surface* self = getSelf(window);
    return self->setSwapInterval(interval);
}

int Surface::dequeueBuffer(ANativeWindow* window, 
        ANativeWindowBuffer** buffer) {
    Surface* self = getSelf(window);
    return self->dequeueBuffer(buffer);
}

int Surface::cancelBuffer(ANativeWindow* window,
        ANativeWindowBuffer* buffer) {
    Surface* self = getSelf(window);
    return self->cancelBuffer(buffer);
}

int Surface::lockBuffer(ANativeWindow* window, 
        ANativeWindowBuffer* buffer) {
    Surface* self = getSelf(window);
    return self->lockBuffer(buffer);
}

int Surface::queueBuffer(ANativeWindow* window, 
        ANativeWindowBuffer* buffer) {
    Surface* self = getSelf(window);
    return self->queueBuffer(buffer);
}

int Surface::query(const ANativeWindow* window,
        int what, int* value) {
    const Surface* self = getSelf(window);
    return self->query(what, value);
}

int Surface::perform(ANativeWindow* window, 
        int operation, ...) {
    va_list args;
    va_start(args, operation);
    Surface* self = getSelf(window);
    int res = self->perform(operation, args);
    va_end(args);
    return res;
}

// ----------------------------------------------------------------------------

int Surface::setSwapInterval(int interval) {
    return mSurfaceTextureClient->setSwapInterval(interval);
}

int Surface::dequeueBuffer(ANativeWindowBuffer** buffer) {
    status_t err = mSurfaceTextureClient->dequeueBuffer(buffer);
    if (err == NO_ERROR) {
        mDirtyRegion.set(buffer[0]->width, buffer[0]->height);
    }
    return err;
}

int Surface::cancelBuffer(ANativeWindowBuffer* buffer) {
    return mSurfaceTextureClient->cancelBuffer(buffer);
}

int Surface::lockBuffer(ANativeWindowBuffer* buffer) {
    return mSurfaceTextureClient->lockBuffer(buffer);
}

int Surface::queueBuffer(ANativeWindowBuffer* buffer) {
    return mSurfaceTextureClient->queueBuffer(buffer);
}

int Surface::query(int what, int* value) const {
    switch (what) {
    case NATIVE_WINDOW_QUEUES_TO_WINDOW_COMPOSER:
        // TODO: this is not needed anymore
        *value = 1;
        return NO_ERROR;
    case NATIVE_WINDOW_CONCRETE_TYPE:
        // TODO: this is not needed anymore
        *value = NATIVE_WINDOW_SURFACE;
        return NO_ERROR;
    }
    return mSurfaceTextureClient->query(what, value);
}

int Surface::perform(int operation, va_list args) {
    return mSurfaceTextureClient->perform(operation, args);
}

// ----------------------------------------------------------------------------

int Surface::getConnectedApi() const {
    return mSurfaceTextureClient->getConnectedApi();
}

// ----------------------------------------------------------------------------

status_t Surface::lock(SurfaceInfo* info, bool blocking) {
    return Surface::lock(info, NULL, blocking);
}

status_t Surface::lock(SurfaceInfo* other, Region* dirtyIn, bool blocking) 
{
    if (getConnectedApi()) {
        LOGE("Surface::lock(%p) failed. Already connected to another API",
                (ANativeWindow*)this);
        CallStack stack;
        stack.update();
        stack.dump("");
        return INVALID_OPERATION;
    }

    if (mApiLock.tryLock() != NO_ERROR) {
        LOGE("calling Surface::lock from different threads!");
        CallStack stack;
        stack.update();
        stack.dump("");
        return WOULD_BLOCK;
    }

    /* Here we're holding mApiLock */
    
    if (mLockedBuffer != 0) {
        LOGE("Surface::lock failed, already locked");
        mApiLock.unlock();
        return INVALID_OPERATION;
    }

    // we're intending to do software rendering from this point
    mSurfaceTextureClient->setUsage(
            GRALLOC_USAGE_SW_READ_OFTEN | GRALLOC_USAGE_SW_WRITE_OFTEN);

    ANativeWindowBuffer* out;
    status_t err = mSurfaceTextureClient->dequeueBuffer(&out);
    LOGE_IF(err, "dequeueBuffer failed (%s)", strerror(-err));
    if (err == NO_ERROR) {
        sp<GraphicBuffer> backBuffer(GraphicBuffer::getSelf(out));
        err = mSurfaceTextureClient->lockBuffer(backBuffer.get());
        LOGE_IF(err, "lockBuffer (handle=%p) failed (%s)",
                backBuffer->handle, strerror(-err));
        if (err == NO_ERROR) {
            const Rect bounds(backBuffer->width, backBuffer->height);
            const Region boundsRegion(bounds);
            Region scratch(boundsRegion);
            Region& newDirtyRegion(dirtyIn ? *dirtyIn : scratch);
            newDirtyRegion &= boundsRegion;

            // figure out if we can copy the frontbuffer back
            const sp<GraphicBuffer>& frontBuffer(mPostedBuffer);
            const bool canCopyBack = (frontBuffer != 0 &&
                    backBuffer->width  == frontBuffer->width &&
                    backBuffer->height == frontBuffer->height &&
                    backBuffer->format == frontBuffer->format &&
                    !(mFlags & ISurfaceComposer::eDestroyBackbuffer));

            // the dirty region we report to surfaceflinger is the one
            // given by the user (as opposed to the one *we* return to the
            // user).
            mDirtyRegion = newDirtyRegion;

            if (canCopyBack) {
                // copy the area that is invalid and not repainted this round
                const Region copyback(mOldDirtyRegion.subtract(newDirtyRegion));
                if (!copyback.isEmpty())
                    copyBlt(backBuffer, frontBuffer, copyback);
            } else {
                // if we can't copy-back anything, modify the user's dirty
                // region to make sure they redraw the whole buffer
                newDirtyRegion = boundsRegion;
            }

            // keep track of the are of the buffer that is "clean"
            // (ie: that will be redrawn)
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
        LOGE("Surface::unlockAndPost failed, no locked buffer");
        return INVALID_OPERATION;
    }

    status_t err = mLockedBuffer->unlock();
    LOGE_IF(err, "failed unlocking buffer (%p)", mLockedBuffer->handle);
    
    err = mSurfaceTextureClient->queueBuffer(mLockedBuffer.get());
    LOGE_IF(err, "queueBuffer (handle=%p) failed (%s)",
            mLockedBuffer->handle, strerror(-err));

    mPostedBuffer = mLockedBuffer;
    mLockedBuffer = 0;
    return err;
}

// ----------------------------------------------------------------------------
}; // namespace android
