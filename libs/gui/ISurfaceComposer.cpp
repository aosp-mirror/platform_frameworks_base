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

// tag as surfaceflinger
#define LOG_TAG "SurfaceFlinger"

#include <stdint.h>
#include <sys/types.h>

#include <binder/Parcel.h>
#include <binder/IMemory.h>
#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>

#include <private/surfaceflinger/LayerState.h>

#include <surfaceflinger/ISurfaceComposer.h>

#include <ui/DisplayInfo.h>

#include <gui/ISurfaceTexture.h>

#include <utils/Log.h>

// ---------------------------------------------------------------------------

#define LIKELY( exp )       (__builtin_expect( (exp) != 0, true  ))
#define UNLIKELY( exp )     (__builtin_expect( (exp) != 0, false ))

// ---------------------------------------------------------------------------

namespace android {

class BpSurfaceComposer : public BpInterface<ISurfaceComposer>
{
public:
    BpSurfaceComposer(const sp<IBinder>& impl)
        : BpInterface<ISurfaceComposer>(impl)
    {
    }

    virtual sp<ISurfaceComposerClient> createConnection()
    {
        uint32_t n;
        Parcel data, reply;
        data.writeInterfaceToken(ISurfaceComposer::getInterfaceDescriptor());
        remote()->transact(BnSurfaceComposer::CREATE_CONNECTION, data, &reply);
        return interface_cast<ISurfaceComposerClient>(reply.readStrongBinder());
    }

    virtual sp<IGraphicBufferAlloc> createGraphicBufferAlloc()
    {
        uint32_t n;
        Parcel data, reply;
        data.writeInterfaceToken(ISurfaceComposer::getInterfaceDescriptor());
        remote()->transact(BnSurfaceComposer::CREATE_GRAPHIC_BUFFER_ALLOC, data, &reply);
        return interface_cast<IGraphicBufferAlloc>(reply.readStrongBinder());
    }

    virtual sp<IMemoryHeap> getCblk() const
    {
        Parcel data, reply;
        data.writeInterfaceToken(ISurfaceComposer::getInterfaceDescriptor());
        remote()->transact(BnSurfaceComposer::GET_CBLK, data, &reply);
        return interface_cast<IMemoryHeap>(reply.readStrongBinder());
    }

    virtual void setTransactionState(const Vector<ComposerState>& state,
            int orientation, uint32_t flags)
    {
        Parcel data, reply;
        data.writeInterfaceToken(ISurfaceComposer::getInterfaceDescriptor());
        Vector<ComposerState>::const_iterator b(state.begin());
        Vector<ComposerState>::const_iterator e(state.end());
        data.writeInt32(state.size());
        for ( ; b != e ; ++b ) {
            b->write(data);
        }
        data.writeInt32(orientation);
        data.writeInt32(flags);
        remote()->transact(BnSurfaceComposer::SET_TRANSACTION_STATE, data, &reply);
    }

    virtual void bootFinished()
    {
        Parcel data, reply;
        data.writeInterfaceToken(ISurfaceComposer::getInterfaceDescriptor());
        remote()->transact(BnSurfaceComposer::BOOT_FINISHED, data, &reply);
    }

    virtual status_t captureScreen(DisplayID dpy,
            sp<IMemoryHeap>* heap,
            uint32_t* width, uint32_t* height, PixelFormat* format,
            uint32_t reqWidth, uint32_t reqHeight,
            uint32_t minLayerZ, uint32_t maxLayerZ)
    {
        Parcel data, reply;
        data.writeInterfaceToken(ISurfaceComposer::getInterfaceDescriptor());
        data.writeInt32(dpy);
        data.writeInt32(reqWidth);
        data.writeInt32(reqHeight);
        data.writeInt32(minLayerZ);
        data.writeInt32(maxLayerZ);
        remote()->transact(BnSurfaceComposer::CAPTURE_SCREEN, data, &reply);
        *heap = interface_cast<IMemoryHeap>(reply.readStrongBinder());
        *width = reply.readInt32();
        *height = reply.readInt32();
        *format = reply.readInt32();
        return reply.readInt32();
    }

    virtual status_t turnElectronBeamOff(int32_t mode)
    {
        Parcel data, reply;
        data.writeInterfaceToken(ISurfaceComposer::getInterfaceDescriptor());
        data.writeInt32(mode);
        remote()->transact(BnSurfaceComposer::TURN_ELECTRON_BEAM_OFF, data, &reply);
        return reply.readInt32();
    }

    virtual status_t turnElectronBeamOn(int32_t mode)
    {
        Parcel data, reply;
        data.writeInterfaceToken(ISurfaceComposer::getInterfaceDescriptor());
        data.writeInt32(mode);
        remote()->transact(BnSurfaceComposer::TURN_ELECTRON_BEAM_ON, data, &reply);
        return reply.readInt32();
    }

    virtual bool authenticateSurfaceTexture(
            const sp<ISurfaceTexture>& surfaceTexture) const
    {
        Parcel data, reply;
        int err = NO_ERROR;
        err = data.writeInterfaceToken(
                ISurfaceComposer::getInterfaceDescriptor());
        if (err != NO_ERROR) {
            LOGE("ISurfaceComposer::authenticateSurfaceTexture: error writing "
                    "interface descriptor: %s (%d)", strerror(-err), -err);
            return false;
        }
        err = data.writeStrongBinder(surfaceTexture->asBinder());
        if (err != NO_ERROR) {
            LOGE("ISurfaceComposer::authenticateSurfaceTexture: error writing "
                    "strong binder to parcel: %s (%d)", strerror(-err), -err);
            return false;
        }
        err = remote()->transact(BnSurfaceComposer::AUTHENTICATE_SURFACE, data,
                &reply);
        if (err != NO_ERROR) {
            LOGE("ISurfaceComposer::authenticateSurfaceTexture: error "
                    "performing transaction: %s (%d)", strerror(-err), -err);
            return false;
        }
        int32_t result = 0;
        err = reply.readInt32(&result);
        if (err != NO_ERROR) {
            LOGE("ISurfaceComposer::authenticateSurfaceTexture: error "
                    "retrieving result: %s (%d)", strerror(-err), -err);
            return false;
        }
        return result != 0;
    }
};

IMPLEMENT_META_INTERFACE(SurfaceComposer, "android.ui.ISurfaceComposer");

// ----------------------------------------------------------------------

status_t BnSurfaceComposer::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    switch(code) {
        case CREATE_CONNECTION: {
            CHECK_INTERFACE(ISurfaceComposer, data, reply);
            sp<IBinder> b = createConnection()->asBinder();
            reply->writeStrongBinder(b);
        } break;
        case CREATE_GRAPHIC_BUFFER_ALLOC: {
            CHECK_INTERFACE(ISurfaceComposer, data, reply);
            sp<IBinder> b = createGraphicBufferAlloc()->asBinder();
            reply->writeStrongBinder(b);
        } break;
        case SET_TRANSACTION_STATE: {
            CHECK_INTERFACE(ISurfaceComposer, data, reply);
            size_t count = data.readInt32();
            ComposerState s;
            Vector<ComposerState> state;
            state.setCapacity(count);
            for (size_t i=0 ; i<count ; i++) {
                s.read(data);
                state.add(s);
            }
            int orientation = data.readInt32();
            uint32_t flags = data.readInt32();
            setTransactionState(state, orientation, flags);
        } break;
        case BOOT_FINISHED: {
            CHECK_INTERFACE(ISurfaceComposer, data, reply);
            bootFinished();
        } break;
        case GET_CBLK: {
            CHECK_INTERFACE(ISurfaceComposer, data, reply);
            sp<IBinder> b = getCblk()->asBinder();
            reply->writeStrongBinder(b);
        } break;
        case CAPTURE_SCREEN: {
            CHECK_INTERFACE(ISurfaceComposer, data, reply);
            DisplayID dpy = data.readInt32();
            uint32_t reqWidth = data.readInt32();
            uint32_t reqHeight = data.readInt32();
            uint32_t minLayerZ = data.readInt32();
            uint32_t maxLayerZ = data.readInt32();
            sp<IMemoryHeap> heap;
            uint32_t w, h;
            PixelFormat f;
            status_t res = captureScreen(dpy, &heap, &w, &h, &f,
                    reqWidth, reqHeight, minLayerZ, maxLayerZ);
            reply->writeStrongBinder(heap->asBinder());
            reply->writeInt32(w);
            reply->writeInt32(h);
            reply->writeInt32(f);
            reply->writeInt32(res);
        } break;
        case TURN_ELECTRON_BEAM_OFF: {
            CHECK_INTERFACE(ISurfaceComposer, data, reply);
            int32_t mode = data.readInt32();
            status_t res = turnElectronBeamOff(mode);
            reply->writeInt32(res);
        } break;
        case TURN_ELECTRON_BEAM_ON: {
            CHECK_INTERFACE(ISurfaceComposer, data, reply);
            int32_t mode = data.readInt32();
            status_t res = turnElectronBeamOn(mode);
            reply->writeInt32(res);
        } break;
        case AUTHENTICATE_SURFACE: {
            CHECK_INTERFACE(ISurfaceComposer, data, reply);
            sp<ISurfaceTexture> surfaceTexture =
                    interface_cast<ISurfaceTexture>(data.readStrongBinder());
            int32_t result = authenticateSurfaceTexture(surfaceTexture) ? 1 : 0;
            reply->writeInt32(result);
        } break;
        default:
            return BBinder::onTransact(code, data, reply, flags);
    }
    return NO_ERROR;
}

// ----------------------------------------------------------------------------

};
