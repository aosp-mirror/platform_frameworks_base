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

#define LOG_TAG "ISurface"

#include <stdio.h>
#include <stdint.h>
#include <sys/types.h>

#include <binder/Parcel.h>
#include <binder/IMemory.h>

#include <ui/ISurface.h>
#include <ui/Overlay.h>
#include <ui/Surface.h>

#include <ui/GraphicBuffer.h>

namespace android {

// ----------------------------------------------------------------------

ISurface::BufferHeap::BufferHeap() 
    : w(0), h(0), hor_stride(0), ver_stride(0), format(0),
    transform(0), flags(0) 
{     
}

ISurface::BufferHeap::BufferHeap(uint32_t w, uint32_t h,
        int32_t hor_stride, int32_t ver_stride,
        PixelFormat format, const sp<IMemoryHeap>& heap)
    : w(w), h(h), hor_stride(hor_stride), ver_stride(ver_stride),
      format(format), transform(0), flags(0), heap(heap) 
{
}

ISurface::BufferHeap::BufferHeap(uint32_t w, uint32_t h,
        int32_t hor_stride, int32_t ver_stride,
        PixelFormat format, uint32_t transform, uint32_t flags,
        const sp<IMemoryHeap>& heap)
        : w(w), h(h), hor_stride(hor_stride), ver_stride(ver_stride),
          format(format), transform(transform), flags(flags), heap(heap) 
{
}


ISurface::BufferHeap::~BufferHeap() 
{     
}

// ----------------------------------------------------------------------

class BpSurface : public BpInterface<ISurface>
{
public:
    BpSurface(const sp<IBinder>& impl)
        : BpInterface<ISurface>(impl)
    {
    }

    virtual sp<GraphicBuffer> requestBuffer(int bufferIdx, int usage)
    {
        Parcel data, reply;
        data.writeInterfaceToken(ISurface::getInterfaceDescriptor());
        data.writeInt32(bufferIdx);
        data.writeInt32(usage);
        remote()->transact(REQUEST_BUFFER, data, &reply);
        sp<GraphicBuffer> buffer = new GraphicBuffer(reply);
        return buffer;
    }

    virtual status_t registerBuffers(const BufferHeap& buffers)
    {
        Parcel data, reply;
        data.writeInterfaceToken(ISurface::getInterfaceDescriptor());
        data.writeInt32(buffers.w);
        data.writeInt32(buffers.h);
        data.writeInt32(buffers.hor_stride);
        data.writeInt32(buffers.ver_stride);
        data.writeInt32(buffers.format);
        data.writeInt32(buffers.transform);
        data.writeInt32(buffers.flags);
        data.writeStrongBinder(buffers.heap->asBinder());
        remote()->transact(REGISTER_BUFFERS, data, &reply);
        status_t result = reply.readInt32();
        return result;
    }

    virtual void postBuffer(ssize_t offset)
    {
        Parcel data, reply;
        data.writeInterfaceToken(ISurface::getInterfaceDescriptor());
        data.writeInt32(offset);
        remote()->transact(POST_BUFFER, data, &reply, IBinder::FLAG_ONEWAY);
    }

    virtual void unregisterBuffers()
    {
        Parcel data, reply;
        data.writeInterfaceToken(ISurface::getInterfaceDescriptor());
        remote()->transact(UNREGISTER_BUFFERS, data, &reply);
    }

    virtual sp<OverlayRef> createOverlay(
             uint32_t w, uint32_t h, int32_t format)
    {
        Parcel data, reply;
        data.writeInterfaceToken(ISurface::getInterfaceDescriptor());
        data.writeInt32(w);
        data.writeInt32(h);
        data.writeInt32(format);
        remote()->transact(CREATE_OVERLAY, data, &reply);
        return OverlayRef::readFromParcel(reply);
    }
};

IMPLEMENT_META_INTERFACE(Surface, "android.ui.ISurface");

// ----------------------------------------------------------------------

status_t BnSurface::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    switch(code) {
        case REQUEST_BUFFER: {
            CHECK_INTERFACE(ISurface, data, reply);
            int bufferIdx = data.readInt32();
            int usage = data.readInt32();
            sp<GraphicBuffer> buffer(requestBuffer(bufferIdx, usage));
            return GraphicBuffer::writeToParcel(reply, buffer.get());
        }
        case REGISTER_BUFFERS: {
            CHECK_INTERFACE(ISurface, data, reply);
            BufferHeap buffer;
            buffer.w = data.readInt32();
            buffer.h = data.readInt32();
            buffer.hor_stride = data.readInt32();
            buffer.ver_stride= data.readInt32();
            buffer.format = data.readInt32();
            buffer.transform = data.readInt32();
            buffer.flags = data.readInt32();
            buffer.heap = interface_cast<IMemoryHeap>(data.readStrongBinder());
            status_t err = registerBuffers(buffer);
            reply->writeInt32(err);
            return NO_ERROR;
        } break;
        case UNREGISTER_BUFFERS: {
            CHECK_INTERFACE(ISurface, data, reply);
            unregisterBuffers();
            return NO_ERROR;
        } break;
        case POST_BUFFER: {
            CHECK_INTERFACE(ISurface, data, reply);
            ssize_t offset = data.readInt32();
            postBuffer(offset);
            return NO_ERROR;
        } break;
        case CREATE_OVERLAY: {
            CHECK_INTERFACE(ISurface, data, reply);
            int w = data.readInt32();
            int h = data.readInt32();
            int f = data.readInt32();
            sp<OverlayRef> o = createOverlay(w, h, f);
            return OverlayRef::writeToParcel(reply, o);
        } break;
        default:
            return BBinder::onTransact(code, data, reply, flags);
    }
}

}; // namespace android
