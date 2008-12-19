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

#ifndef ANDROID_OVERLAY_H
#define ANDROID_OVERLAY_H

#include <stdint.h>
#include <sys/types.h>

#include <utils/Errors.h>
#include <utils/IInterface.h>
#include <utils/RefBase.h>
#include <ui/PixelFormat.h>

#include <hardware/overlay.h>

namespace android {

class IOverlay;
class IMemory;
class IMemoryHeap;

class Overlay : public virtual RefBase
{
public:
    Overlay(overlay_t* overlay, 
            const sp<IOverlay>& o, const sp<IMemoryHeap>& heap);

    /* destroys this overlay */
    void destroy();
    
    /* post/swaps buffers */
    status_t swapBuffers();
    
    /* get the HAL handle for this overlay */
    overlay_handle_t const* getHandleRef() const;
    
    /* returns the offset of the current buffer */
    size_t getBufferOffset() const;
    
    /* returns a heap to this overlay. this may not be supported. */
    sp<IMemoryHeap> getHeap() const;
    
    /* get physical informations about the overlay */
    uint32_t getWidth() const;
    uint32_t getHeight() const;
    int32_t getFormat() const;
    int32_t getWidthStride() const;
    int32_t getHeightStride() const;

    static sp<Overlay> readFromParcel(const Parcel& data);
    static status_t writeToParcel(Parcel* reply, const sp<Overlay>& o);

private:
    Overlay(overlay_handle_t*, const sp<IOverlay>&, const sp<IMemoryHeap>&,  
            uint32_t w, uint32_t h, int32_t f, uint32_t ws, uint32_t hs);

    virtual ~Overlay();

    sp<IOverlay>        mOverlay;
    sp<IMemoryHeap>     mHeap;
    size_t              mCurrentBufferOffset;
    overlay_handle_t const *mOverlayHandle;
    uint32_t            mWidth;
    uint32_t            mHeight;
    int32_t             mFormat;
    int32_t             mWidthStride;
    int32_t             mHeightStride;
};

// ----------------------------------------------------------------------------

}; // namespace android

#endif // ANDROID_OVERLAY_H
