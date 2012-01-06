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

#include <binder/IPCThreadState.h>

#include <gui/SurfaceTextureClient.h>

#include <ui/DisplayInfo.h>
#include <ui/GraphicBuffer.h>
#include <ui/Rect.h>

#include <surfaceflinger/ISurface.h>
#include <surfaceflinger/ISurfaceComposer.h>
#include <surfaceflinger/Surface.h>
#include <surfaceflinger/SurfaceComposerClient.h>

namespace android {

// ============================================================================
//  SurfaceControl
// ============================================================================

SurfaceControl::SurfaceControl(
        const sp<SurfaceComposerClient>& client, 
        const sp<ISurface>& surface,
        const ISurfaceComposerClient::surface_data_t& data)
    : mClient(client), mSurface(surface),
      mToken(data.token), mIdentity(data.identity)
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
        ALOGE("invalid token (%d, identity=%u) or client (%p)", 
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
    if (SurfaceControl::isValid(control)) {
        sur      = control->mSurface;
        identity = control->mIdentity;
    }
    parcel->writeStrongBinder(sur!=0 ? sur->asBinder() : NULL);
    parcel->writeStrongBinder(NULL);  // NULL ISurfaceTexture in this case.
    parcel->writeInt32(identity);
    return NO_ERROR;
}

sp<Surface> SurfaceControl::getSurface() const
{
    Mutex::Autolock _l(mLock);
    if (mSurfaceData == 0) {
        sp<SurfaceControl> surface_control(const_cast<SurfaceControl*>(this));
        mSurfaceData = new Surface(surface_control);
    }
    return mSurfaceData;
}

// ============================================================================
//  Surface
// ============================================================================

// ---------------------------------------------------------------------------

Surface::Surface(const sp<SurfaceControl>& surface)
    : SurfaceTextureClient(),
      mSurface(surface->mSurface),
      mIdentity(surface->mIdentity)
{
    sp<ISurfaceTexture> st;
    if (mSurface != NULL) {
        st = mSurface->getSurfaceTexture();
    }
    init(st);
}

Surface::Surface(const Parcel& parcel, const sp<IBinder>& ref)
    : SurfaceTextureClient()
{
    mSurface = interface_cast<ISurface>(ref);
    sp<IBinder> st_binder(parcel.readStrongBinder());
    sp<ISurfaceTexture> st;
    if (st_binder != NULL) {
        st = interface_cast<ISurfaceTexture>(st_binder);
    } else if (mSurface != NULL) {
        st = mSurface->getSurfaceTexture();
    }

    mIdentity   = parcel.readInt32();
    init(st);
}

Surface::Surface(const sp<ISurfaceTexture>& st)
    : SurfaceTextureClient(),
      mSurface(NULL),
      mIdentity(0)
{
    init(st);
}

status_t Surface::writeToParcel(
        const sp<Surface>& surface, Parcel* parcel)
{
    sp<ISurface> sur;
    sp<ISurfaceTexture> st;
    uint32_t identity = 0;
    if (Surface::isValid(surface)) {
        sur      = surface->mSurface;
        st       = surface->getISurfaceTexture();
        identity = surface->mIdentity;
    } else if (surface != 0 &&
            (surface->mSurface != NULL ||
             surface->getISurfaceTexture() != NULL)) {
        ALOGE("Parceling invalid surface with non-NULL ISurface/ISurfaceTexture as NULL: "
             "mSurface = %p, surfaceTexture = %p, mIdentity = %d, ",
             surface->mSurface.get(), surface->getISurfaceTexture().get(),
             surface->mIdentity);
    }

    parcel->writeStrongBinder(sur != NULL ? sur->asBinder() : NULL);
    parcel->writeStrongBinder(st != NULL ? st->asBinder() : NULL);
    parcel->writeInt32(identity);
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
    } else {
        // The Surface was found in the cache, but we still should clear any
        // remaining data from the parcel.
        data.readStrongBinder();  // ISurfaceTexture
        data.readInt32();         // identity
    }
    if (surface->mSurface == NULL && surface->getISurfaceTexture() == NULL) {
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

void Surface::init(const sp<ISurfaceTexture>& surfaceTexture)
{
    if (mSurface != NULL || surfaceTexture != NULL) {
        ALOGE_IF(surfaceTexture==0, "got a NULL ISurfaceTexture from ISurface");
        if (surfaceTexture != NULL) {
            setISurfaceTexture(surfaceTexture);
            setUsage(GraphicBuffer::USAGE_HW_RENDER);
        }

        DisplayInfo dinfo;
        SurfaceComposerClient::getDisplayInfo(0, &dinfo);
        const_cast<float&>(ANativeWindow::xdpi) = dinfo.xdpi;
        const_cast<float&>(ANativeWindow::ydpi) = dinfo.ydpi;
        const_cast<uint32_t&>(ANativeWindow::flags) = 0;
    }
}

Surface::~Surface()
{
    // clear all references and trigger an IPC now, to make sure things
    // happen without delay, since these resources are quite heavy.
    mSurface.clear();
    IPCThreadState::self()->flushCommands();
}

bool Surface::isValid() {
    return getISurfaceTexture() != NULL;
}

sp<ISurfaceTexture> Surface::getSurfaceTexture() {
    return getISurfaceTexture();
}

sp<IBinder> Surface::asBinder() const {
    return mSurface!=0 ? mSurface->asBinder() : 0;
}

// ----------------------------------------------------------------------------

int Surface::query(int what, int* value) const {
    switch (what) {
    case NATIVE_WINDOW_CONCRETE_TYPE:
        *value = NATIVE_WINDOW_SURFACE;
        return NO_ERROR;
    }
    return SurfaceTextureClient::query(what, value);
}

// ----------------------------------------------------------------------------

status_t Surface::lock(SurfaceInfo* other, Region* inOutDirtyRegion) {
    ANativeWindow_Buffer outBuffer;

    ARect temp;
    ARect* inOutDirtyBounds = NULL;
    if (inOutDirtyRegion) {
        temp = inOutDirtyRegion->getBounds();
        inOutDirtyBounds = &temp;
    }

    status_t err = SurfaceTextureClient::lock(&outBuffer, inOutDirtyBounds);

    if (err == NO_ERROR) {
        other->w = uint32_t(outBuffer.width);
        other->h = uint32_t(outBuffer.height);
        other->s = uint32_t(outBuffer.stride);
        other->usage = GRALLOC_USAGE_SW_READ_OFTEN | GRALLOC_USAGE_SW_WRITE_OFTEN;
        other->format = uint32_t(outBuffer.format);
        other->bits = outBuffer.bits;
    }

    if (inOutDirtyRegion) {
        inOutDirtyRegion->set( static_cast<Rect const&>(temp) );
    }

    return err;
}

status_t Surface::unlockAndPost() {
    return SurfaceTextureClient::unlockAndPost();
}

// ----------------------------------------------------------------------------
}; // namespace android
