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
#include <utils/Errors.h>
#include <utils/MemoryHeapBase.h>

#include <ui/IOverlay.h>
#include <ui/Overlay.h>

#include <hardware/overlay.h>

namespace android {

Overlay::Overlay(const sp<OverlayRef>& overlayRef)
    : mOverlayRef(overlayRef), mOverlayData(0), mStatus(NO_INIT)
{
    mOverlayData = NULL;
    hw_module_t const* module;
    if (hw_get_module(OVERLAY_HARDWARE_MODULE_ID, &module) == 0) {
        if (overlay_data_open(module, &mOverlayData) == NO_ERROR) {
            mStatus = mOverlayData->initialize(mOverlayData,
                    overlayRef->mOverlayHandle);
        }
    }
}

Overlay::~Overlay() {
    if (mOverlayData) {
        overlay_data_close(mOverlayData);
    }
}

overlay_buffer_t Overlay::dequeueBuffer()
{
    return mOverlayData->dequeueBuffer(mOverlayData);
}

int Overlay::queueBuffer(overlay_buffer_t buffer)
{
    return mOverlayData->queueBuffer(mOverlayData, buffer);
}

void* Overlay::getBufferAddress(overlay_buffer_t buffer)
{
    return mOverlayData->getBufferAddress(mOverlayData, buffer);
}

void Overlay::destroy() {  
    mOverlayRef->mOverlayChanel->destroy();
}

status_t Overlay::getStatus() const {
    return mStatus;
}

overlay_handle_t const* Overlay::getHandleRef() const {
    return mOverlayRef->mOverlayHandle;
}

uint32_t Overlay::getWidth() const {
    return mOverlayRef->mWidth;
}

uint32_t Overlay::getHeight() const {
    return mOverlayRef->mHeight;
}

int32_t Overlay::getFormat() const {
    return mOverlayRef->mFormat;
}

int32_t Overlay::getWidthStride() const {
    return mOverlayRef->mWidthStride;
}

int32_t Overlay::getHeightStride() const {
    return mOverlayRef->mHeightStride;
}
// ----------------------------------------------------------------------------

OverlayRef::OverlayRef() 
 : mOverlayHandle(0),
    mWidth(0), mHeight(0), mFormat(0), mWidthStride(0), mHeightStride(0),
    mOwnHandle(true)
{    
}

OverlayRef::OverlayRef(overlay_handle_t const* handle, const sp<IOverlay>& chanel,
         uint32_t w, uint32_t h, int32_t f, uint32_t ws, uint32_t hs)
    : mOverlayHandle(handle), mOverlayChanel(chanel),
    mWidth(w), mHeight(h), mFormat(f), mWidthStride(ws), mHeightStride(hs),
    mOwnHandle(false)
{
}

OverlayRef::~OverlayRef()
{
    if (mOwnHandle) {
        /* FIXME: handles should be promoted to "real" API and be handled by 
         * the framework */
        for (int i=0 ; i<mOverlayHandle->numFds ; i++) {
            close(mOverlayHandle->fds[i]);
        }
        free((void*)mOverlayHandle);
    }
}

sp<OverlayRef> OverlayRef::readFromParcel(const Parcel& data) {
    sp<OverlayRef> result;
    sp<IOverlay> overlay = IOverlay::asInterface(data.readStrongBinder());
    if (overlay != NULL) {
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
        result = new OverlayRef();
        result->mOverlayHandle = handle;
        result->mOverlayChanel = overlay;
        result->mWidth = w;
        result->mHeight = h;
        result->mFormat = f;
        result->mWidthStride = ws;
        result->mHeightStride = hs;
    }
    return result;
}

status_t OverlayRef::writeToParcel(Parcel* reply, const sp<OverlayRef>& o) {
    if (o != NULL) {
        reply->writeStrongBinder(o->mOverlayChanel->asBinder());
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

