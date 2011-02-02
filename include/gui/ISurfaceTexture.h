/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef ANDROID_GUI_ISURFACETEXTURE_H
#define ANDROID_GUI_ISURFACETEXTURE_H

#include <stdint.h>
#include <sys/types.h>

#include <utils/Errors.h>
#include <utils/RefBase.h>

#include <binder/IInterface.h>

#include <ui/GraphicBuffer.h>
#include <ui/Rect.h>

namespace android {
// ----------------------------------------------------------------------------

class ISurfaceTexture : public IInterface
{
public:
    DECLARE_META_INTERFACE(SurfaceTexture);

    // requestBuffer requests a new buffer for the given index. The server (i.e.
    // the ISurfaceTexture implementation) assigns the newly created buffer to
    // the given slot index, and the client is expected to mirror the
    // slot->buffer mapping so that it's not necessary to transfer a
    // GraphicBuffer for every dequeue operation.
    virtual sp<GraphicBuffer> requestBuffer(int slot, uint32_t w, uint32_t h,
            uint32_t format, uint32_t usage) = 0;

    // setBufferCount sets the number of buffer slots available. Calling this
    // will also cause all buffer slots to be emptied. The caller should empty
    // its mirrored copy of the buffer slots when calling this method.
    virtual status_t setBufferCount(int bufferCount) = 0;

    // dequeueBuffer requests a new buffer slot for the client to use. Ownership
    // of the slot is transfered to the client, meaning that the server will not
    // use the contents of the buffer associated with that slot. The slot index
    // returned may or may not contain a buffer. If the slot is empty the client
    // should call requestBuffer to assign a new buffer to that slot. The client
    // is expected to either call cancelBuffer on the dequeued slot or to fill
    // in the contents of its associated buffer contents and call queueBuffer.
    virtual status_t dequeueBuffer(int *slot) = 0;

    // queueBuffer indicates that the client has finished filling in the
    // contents of the buffer associated with slot and transfers ownership of
    // that slot back to the server. It is not valid to call queueBuffer on a
    // slot that is not owned by the client or one for which a buffer associated
    // via requestBuffer.
    virtual status_t queueBuffer(int slot) = 0;

    // cancelBuffer indicates that the client does not wish to fill in the
    // buffer associated with slot and transfers ownership of the slot back to
    // the server.
    virtual void cancelBuffer(int slot) = 0;

    virtual status_t setCrop(const Rect& reg) = 0;
    virtual status_t setTransform(uint32_t transform) = 0;

    // getAllocator retrieves the binder object that must be referenced as long
    // as the GraphicBuffers dequeued from this ISurfaceTexture are referenced.
    // Holding this binder reference prevents SurfaceFlinger from freeing the
    // buffers before the client is done with them.
    virtual sp<IBinder> getAllocator() = 0;
};

// ----------------------------------------------------------------------------

class BnSurfaceTexture : public BnInterface<ISurfaceTexture>
{
public:
    virtual status_t    onTransact( uint32_t code,
                                    const Parcel& data,
                                    Parcel* reply,
                                    uint32_t flags = 0);
};

// ----------------------------------------------------------------------------
}; // namespace android

#endif // ANDROID_GUI_ISURFACETEXTURE_H
