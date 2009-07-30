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

#include <binder/IMemory.h>
#include <binder/Parcel.h>
#include <utils/Errors.h>
#include <binder/MemoryHeapBase.h>

#include <ui/IOverlay.h>
#include <ui/Overlay.h>

#include <hardware/overlay.h>

namespace android {

Overlay::Overlay(const sp<OverlayRef>& overlayRef)
    : mOverlayRef(overlayRef), mOverlayData(0), mStatus(NO_INIT)
{
    mOverlayData = NULL;
    hw_module_t const* module;
    if (overlayRef != 0) {
        if (hw_get_module(OVERLAY_HARDWARE_MODULE_ID, &module) == 0) {
            if (overlay_data_open(module, &mOverlayData) == NO_ERROR) {
                mStatus = mOverlayData->initialize(mOverlayData,
                        overlayRef->mOverlayHandle);
            }
        }
    }
}

Overlay::~Overlay() {
    if (mOverlayData) {
        overlay_data_close(mOverlayData);
    }
}

status_t Overlay::dequeueBuffer(overlay_buffer_t* buffer)
{
    if (mStatus != NO_ERROR) return mStatus;
    return  mOverlayData->dequeueBuffer(mOverlayData, buffer);
}

status_t Overlay::queueBuffer(overlay_buffer_t buffer)
{
    if (mStatus != NO_ERROR) return mStatus;
    return mOverlayData->queueBuffer(mOverlayData, buffer);
}

status_t Overlay::resizeInput(uint32_t width, uint32_t height)
{
    if (mStatus != NO_ERROR) return mStatus;
    return mOverlayData->resizeInput(mOverlayData, width, height);
}

status_t Overlay::setParameter(int param, int value)
{
    if (mStatus != NO_ERROR) return mStatus;
    return mOverlayData->setParameter(mOverlayData, param, value);
}

status_t Overlay::setCrop(uint32_t x, uint32_t y, uint32_t w, uint32_t h)
{
    if (mStatus != NO_ERROR) return mStatus;
    return mOverlayData->setCrop(mOverlayData, x, y, w, h);
}

status_t Overlay::getCrop(uint32_t* x, uint32_t* y, uint32_t* w, uint32_t* h)
{
    if (mStatus != NO_ERROR) return mStatus;
    return mOverlayData->getCrop(mOverlayData, x, y, w, h);
}

int32_t Overlay::getBufferCount() const
{
    if (mStatus != NO_ERROR) return mStatus;
    return mOverlayData->getBufferCount(mOverlayData);
}

void* Overlay::getBufferAddress(overlay_buffer_t buffer)
{
    if (mStatus != NO_ERROR) return NULL;
    return mOverlayData->getBufferAddress(mOverlayData, buffer);
}

void Overlay::destroy() {  
    if (mStatus != NO_ERROR) return;

    // Must delete the objects in reverse creation order, thus the
    //  data side must be closed first and then the destroy send to
    //  the control side.
    if (mOverlayData) {
        overlay_data_close(mOverlayData);
        mOverlayData = NULL;
    }

    mOverlayRef->mOverlayChannel->destroy();
}

status_t Overlay::getStatus() const {
    return mStatus;
}

overlay_handle_t Overlay::getHandleRef() const {
    if (mStatus != NO_ERROR) return NULL;
    return mOverlayRef->mOverlayHandle;
}

uint32_t Overlay::getWidth() const {
    if (mStatus != NO_ERROR) return 0;
    return mOverlayRef->mWidth;
}

uint32_t Overlay::getHeight() const {
    if (mStatus != NO_ERROR) return 0;
    return mOverlayRef->mHeight;
}

int32_t Overlay::getFormat() const {
    if (mStatus != NO_ERROR) return -1;
    return mOverlayRef->mFormat;
}

int32_t Overlay::getWidthStride() const {
    if (mStatus != NO_ERROR) return 0;
    return mOverlayRef->mWidthStride;
}

int32_t Overlay::getHeightStride() const {
    if (mStatus != NO_ERROR) return 0;
    return mOverlayRef->mHeightStride;
}
// ----------------------------------------------------------------------------

OverlayRef::OverlayRef() 
 : mOverlayHandle(0),
    mWidth(0), mHeight(0), mFormat(0), mWidthStride(0), mHeightStride(0),
    mOwnHandle(true)
{    
}

OverlayRef::OverlayRef(overlay_handle_t handle, const sp<IOverlay>& channel,
         uint32_t w, uint32_t h, int32_t f, uint32_t ws, uint32_t hs)
    : mOverlayHandle(handle), mOverlayChannel(channel),
    mWidth(w), mHeight(h), mFormat(f), mWidthStride(ws), mHeightStride(hs),
    mOwnHandle(false)
{
}

OverlayRef::~OverlayRef()
{
    if (mOwnHandle) {
        native_handle_close(mOverlayHandle);
        native_handle_delete(const_cast<native_handle*>(mOverlayHandle));
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
        native_handle* handle = data.readNativeHandle();

        result = new OverlayRef();
        result->mOverlayHandle = handle;
        result->mOverlayChannel = overlay;
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
        reply->writeStrongBinder(o->mOverlayChannel->asBinder());
        reply->writeInt32(o->mWidth);
        reply->writeInt32(o->mHeight);
        reply->writeInt32(o->mFormat);
        reply->writeInt32(o->mWidthStride);
        reply->writeInt32(o->mHeightStride);
        reply->writeNativeHandle(o->mOverlayHandle);
    } else {
        reply->writeStrongBinder(NULL);
    }
    return NO_ERROR;
}

// ----------------------------------------------------------------------------

}; // namespace android
