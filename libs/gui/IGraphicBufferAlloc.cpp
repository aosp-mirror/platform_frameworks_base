/*
 * Copyright (C) 2011 The Android Open Source Project
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

// tag as surfaceflinger
#define LOG_TAG "SurfaceFlinger"

#include <stdint.h>
#include <sys/types.h>

#include <binder/Parcel.h>

#include <ui/GraphicBuffer.h>

#include <surfaceflinger/IGraphicBufferAlloc.h>

// ---------------------------------------------------------------------------

namespace android {

enum {
    CREATE_GRAPHIC_BUFFER = IBinder::FIRST_CALL_TRANSACTION,
};

class BpGraphicBufferAlloc : public BpInterface<IGraphicBufferAlloc>
{
public:
    BpGraphicBufferAlloc(const sp<IBinder>& impl)
        : BpInterface<IGraphicBufferAlloc>(impl)
    {
    }

    virtual sp<GraphicBuffer> createGraphicBuffer(uint32_t w, uint32_t h,
            PixelFormat format, uint32_t usage, status_t* error) {
        Parcel data, reply;
        data.writeInterfaceToken(IGraphicBufferAlloc::getInterfaceDescriptor());
        data.writeInt32(w);
        data.writeInt32(h);
        data.writeInt32(format);
        data.writeInt32(usage);
        remote()->transact(CREATE_GRAPHIC_BUFFER, data, &reply);
        sp<GraphicBuffer> graphicBuffer;
        status_t result = reply.readInt32();
        if (result == NO_ERROR) {
            graphicBuffer = new GraphicBuffer();
            reply.read(*graphicBuffer);
            // reply.readStrongBinder();
            // here we don't even have to read the BufferReference from
            // the parcel, it'll die with the parcel.
        }
        *error = result;
        return graphicBuffer;
    }
};

IMPLEMENT_META_INTERFACE(GraphicBufferAlloc, "android.ui.IGraphicBufferAlloc");

// ----------------------------------------------------------------------

status_t BnGraphicBufferAlloc::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    // codes that don't require permission check

    /* BufferReference just keeps a strong reference to a
     * GraphicBuffer until it is destroyed (that is, until
     * no local or remote process have a reference to it).
     */
    class BufferReference : public BBinder {
        sp<GraphicBuffer> buffer;
    public:
        BufferReference(const sp<GraphicBuffer>& buffer) : buffer(buffer) { }
    };


    switch(code) {
        case CREATE_GRAPHIC_BUFFER: {
            CHECK_INTERFACE(IGraphicBufferAlloc, data, reply);
            uint32_t w = data.readInt32();
            uint32_t h = data.readInt32();
            PixelFormat format = data.readInt32();
            uint32_t usage = data.readInt32();
            status_t error;
            sp<GraphicBuffer> result =
                    createGraphicBuffer(w, h, format, usage, &error);
            reply->writeInt32(error);
            if (result != 0) {
                reply->write(*result);
                // We add a BufferReference to this parcel to make sure the
                // buffer stays alive until the GraphicBuffer object on
                // the other side has been created.
                // This is needed so that the buffer handle can be
                // registered before the buffer is destroyed on implementations
                // that do not use file-descriptors to track their buffers.
                reply->writeStrongBinder( new BufferReference(result) );
            }
            return NO_ERROR;
        } break;
        default:
            return BBinder::onTransact(code, data, reply, flags);
    }
}

}; // namespace android
