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

#include <stdio.h>
#include <stdint.h>
#include <sys/types.h>

#include <utils/Parcel.h>
#include <utils/IMemory.h>

#include <ui/ISurface.h>
#include <ui/Overlay.h>


namespace android {

enum {
    REGISTER_BUFFERS = IBinder::FIRST_CALL_TRANSACTION,
    UNREGISTER_BUFFERS,
    POST_BUFFER, // one-way transaction
    CREATE_OVERLAY,
};

class BpSurface : public BpInterface<ISurface>
{
public:
    BpSurface(const sp<IBinder>& impl)
        : BpInterface<ISurface>(impl)
    {
    }

    virtual status_t registerBuffers(int w, int h, int hstride, int vstride,
            PixelFormat format, const sp<IMemoryHeap>& heap)
    {
        Parcel data, reply;
        data.writeInterfaceToken(ISurface::getInterfaceDescriptor());
        data.writeInt32(w);
        data.writeInt32(h);
        data.writeInt32(hstride);
        data.writeInt32(vstride);
        data.writeInt32(format);
        data.writeStrongBinder(heap->asBinder());
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

#define CHECK_INTERFACE(interface, data, reply) \
        do { if (!data.enforceInterface(interface::getInterfaceDescriptor())) { \
            LOGW("Call incorrectly routed to " #interface); \
            return PERMISSION_DENIED; \
        } } while (0)

status_t BnSurface::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    switch(code) {
        case REGISTER_BUFFERS: {
            CHECK_INTERFACE(ISurface, data, reply);
            int w = data.readInt32();
            int h = data.readInt32();
            int hs= data.readInt32();
            int vs= data.readInt32();
            PixelFormat f = data.readInt32();
            sp<IMemoryHeap> heap(interface_cast<IMemoryHeap>(data.readStrongBinder()));
            status_t err = registerBuffers(w,h,hs,vs,f,heap);
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
