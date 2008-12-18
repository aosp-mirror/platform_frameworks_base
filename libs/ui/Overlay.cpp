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

#include <utils/IMemory.h>
#include <utils/Parcel.h>

#include <ui/IOverlay.h>
#include <ui/Overlay.h>

namespace android {

Overlay::Overlay(overlay_handle_t* handle, 
        const sp<IOverlay>& o, const sp<IMemoryHeap>& heap, 
        uint32_t w, uint32_t h, int32_t f, uint32_t ws, uint32_t hs)
    : mOverlay(o), mHeap(heap), mCurrentBufferOffset(0), mOverlayHandle(handle),
      mWidth(w), mHeight(h), mFormat(f), mWidthStride(ws), mHeightStride(hs)
{
}

Overlay::Overlay(overlay_t* overlay, 
        const sp<IOverlay>& o, const sp<IMemoryHeap>& heap)
    : mOverlay(o), mHeap(heap) 
{
    mCurrentBufferOffset = 0; 
    mOverlayHandle = overlay->getHandleRef(overlay);
    mWidth = overlay->w;
    mHeight = overlay->h;
    mFormat = overlay->format; 
    mWidthStride = overlay->w_stride;
    mHeightStride = overlay->h_stride;
}


Overlay::~Overlay() {
}

void Overlay::destroy() {  
    mOverlay->destroy();
}

status_t Overlay::swapBuffers() {
    ssize_t result = mOverlay->swapBuffers();
    if (result < 0)
        return status_t(result);
    mCurrentBufferOffset = result;
    return NO_ERROR;
}

overlay_handle_t const* Overlay::getHandleRef() const {
    return mOverlayHandle;
}

size_t Overlay::getBufferOffset() const {
    return mCurrentBufferOffset;
}

sp<IMemoryHeap> Overlay::getHeap() const {
    return mHeap;
}

uint32_t Overlay::getWidth() const {
    return mWidth;
}

uint32_t Overlay::getHeight() const {
    return mHeight;
}

int32_t Overlay::getFormat() const {
    return mFormat;
}

int32_t Overlay::getWidthStride() const {
    return mWidthStride;
}

int32_t Overlay::getHeightStride() const {
    return mHeightStride;
}

sp<Overlay> Overlay::readFromParcel(const Parcel& data) {
    sp<Overlay> result;
    sp<IOverlay> overlay = IOverlay::asInterface(data.readStrongBinder());
    if (overlay != NULL) {
        sp<IMemoryHeap> heap = IMemoryHeap::asInterface(data.readStrongBinder());
        uint32_t w = data.readInt32();
        uint32_t h = data.readInt32();
        uint32_t f = data.readInt32();
        uint32_t ws = data.readInt32();
        uint32_t hs = data.readInt32();
        /* FIXME: handles should be promoted to "real" API and be handled by 
         * the framework */
        int numfd = data.readInt32();
        int numint = data.readInt32();
        overlay_handle_t* handle = (overlay_handle_t*)malloc(
                sizeof(overlay_handle_t) + numint*sizeof(int));
        for (int i=0 ; i<numfd ; i++)
            handle->fds[i] = data.readFileDescriptor();
        for (int i=0 ; i<numint ; i++)
            handle->data[i] = data.readInt32();
        result = new Overlay(handle, overlay, heap, w, h, f, ws, hs);
    }
    return result;
}

status_t Overlay::writeToParcel(Parcel* reply, const sp<Overlay>& o) {
    if (o != NULL) {
        reply->writeStrongBinder(o->mOverlay->asBinder());
        reply->writeStrongBinder(o->mHeap->asBinder());
        reply->writeInt32(o->mWidth);
        reply->writeInt32(o->mHeight);
        reply->writeInt32(o->mFormat);
        reply->writeInt32(o->mWidthStride);
        reply->writeInt32(o->mHeightStride);
        /* FIXME: handles should be promoted to "real" API and be handled by 
         * the framework */
        reply->writeInt32(o->mOverlayHandle->numFds);
        reply->writeInt32(o->mOverlayHandle->numInts);
        for (int i=0 ; i<o->mOverlayHandle->numFds ; i++)
            reply->writeFileDescriptor(o->mOverlayHandle->fds[i]);
        for (int i=0 ; i<o->mOverlayHandle->numInts ; i++)
            reply->writeInt32(o->mOverlayHandle->data[i]);
    } else {
        reply->writeStrongBinder(NULL);
    }
    return NO_ERROR;
}

// ----------------------------------------------------------------------------

}; // namespace android

