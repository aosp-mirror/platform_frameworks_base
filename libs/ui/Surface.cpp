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

#include <utils/Atomic.h>
#include <utils/Errors.h>
#include <utils/threads.h>
#include <utils/IPCThreadState.h>
#include <utils/IMemory.h>
#include <utils/Log.h>

#include <ui/ISurface.h>
#include <ui/Surface.h>
#include <ui/SurfaceComposerClient.h>
#include <ui/Rect.h>

#include <private/ui/SharedState.h>
#include <private/ui/LayerState.h>

namespace android {

// ---------------------------------------------------------------------------

Surface::Surface(const sp<SurfaceComposerClient>& client, 
        const sp<ISurface>& surface,
        const ISurfaceFlingerClient::surface_data_t& data,
        uint32_t w, uint32_t h, PixelFormat format, uint32_t flags,
        bool owner)
    : mClient(client), mSurface(surface),
      mToken(data.token), mIdentity(data.identity),
      mFormat(format), mFlags(flags), mOwner(owner)
{
    mSwapRectangle.makeInvalid();
    mSurfaceHeapBase[0] = 0;
    mSurfaceHeapBase[1] = 0;
    mHeap[0] = data.heap[0]; 
    mHeap[1] = data.heap[1];
}

Surface::Surface(Surface const* rhs)
    : mOwner(false)
{
    mToken   = rhs->mToken;
    mIdentity= rhs->mIdentity;
    mClient  = rhs->mClient;
    mSurface = rhs->mSurface;
    mHeap[0] = rhs->mHeap[0];
    mHeap[1] = rhs->mHeap[1];
    mFormat  = rhs->mFormat;
    mFlags   = rhs->mFlags;
    mSurfaceHeapBase[0] = rhs->mSurfaceHeapBase[0];
    mSurfaceHeapBase[1] = rhs->mSurfaceHeapBase[1];
    mSwapRectangle.makeInvalid();
}

Surface::~Surface()
{
    if (mOwner && mToken>=0 && mClient!=0) {
        mClient->destroySurface(mToken);
    }
    mClient.clear();
    mSurface.clear();
    mHeap[0].clear();
    mHeap[1].clear();
    IPCThreadState::self()->flushCommands();
}

sp<Surface> Surface::dup() const
{
    Surface const * r = this;
    if (this && mOwner) {
        // the only reason we need to do this is because of Java's garbage
        // collector: because we're creating a copy of the Surface
        // instead of a reference, we can garantee that when our last
        // reference goes away, the real surface will be deleted.
        // Without this hack (the code is correct too), we'd have to
        // wait for a GC for the surface to go away.
        r = new Surface(this);        
    }
    return const_cast<Surface*>(r);
}

status_t Surface::nextBuffer(SurfaceInfo* info) {
    return mClient->nextBuffer(this, info);
}

status_t Surface::lock(SurfaceInfo* info, bool blocking) {
    return Surface::lock(info, NULL, blocking);
}

status_t Surface::lock(SurfaceInfo* info, Region* dirty, bool blocking) {
    if (heapBase(0) == 0) return INVALID_OPERATION;
    if (heapBase(1) == 0) return INVALID_OPERATION;
    return mClient->lockSurface(this, info, dirty, blocking);
}

status_t Surface::unlockAndPost() {
    if (heapBase(0) == 0) return INVALID_OPERATION;
    if (heapBase(1) == 0) return INVALID_OPERATION;
    return mClient->unlockAndPostSurface(this);
}

status_t Surface::unlock() {
    if (heapBase(0) == 0) return INVALID_OPERATION;
    if (heapBase(1) == 0) return INVALID_OPERATION;
    return mClient->unlockSurface(this);
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
    data.heap[0]            = interface_cast<IMemoryHeap>(parcel->readStrongBinder());
    data.heap[1]            = interface_cast<IMemoryHeap>(parcel->readStrongBinder());
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
    sp<IMemoryHeap> heap[2];
    if (surface->isValid()) {
        token = surface->mToken;
        identity = surface->mIdentity;
        client = surface->mClient;
        sur = surface->mSurface;
        heap[0] = surface->mHeap[0];
        heap[1] = surface->mHeap[1];
        format = surface->mFormat;
        flags = surface->mFlags;
    }
    parcel->writeStrongBinder(client!=0  ? client->connection() : NULL);
    parcel->writeStrongBinder(sur!=0     ? sur->asBinder()      : NULL);
    parcel->writeStrongBinder(heap[0]!=0 ? heap[0]->asBinder()  : NULL);
    parcel->writeStrongBinder(heap[1]!=0 ? heap[1]->asBinder()  : NULL);
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

void* Surface::heapBase(int i) const 
{
    void* heapBase = mSurfaceHeapBase[i];
    // map lazily so it doesn't get mapped in clients that don't need it
    if (heapBase == 0) {
        const sp<IMemoryHeap>& heap(mHeap[i]);
        if (heap != 0) {
            heapBase = static_cast<uint8_t*>(heap->base());
            if (heapBase == MAP_FAILED) {
                heapBase = NULL;
                LOGE("Couldn't map Surface's heap (binder=%p, heap=%p)",
                        heap->asBinder().get(), heap.get());
            }
            mSurfaceHeapBase[i] = heapBase;
        }
    }
    return heapBase;
}

}; // namespace android

