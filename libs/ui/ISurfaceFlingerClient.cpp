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

#include <stdio.h>
#include <stdint.h>
#include <sys/types.h>

#include <utils/Parcel.h>
#include <utils/IMemory.h>
#include <utils/IPCThreadState.h>
#include <utils/IServiceManager.h>

#include <ui/ISurface.h>
#include <ui/ISurfaceFlingerClient.h>
#include <ui/Point.h>
#include <ui/Rect.h>

#include <private/ui/LayerState.h>

// ---------------------------------------------------------------------------

#define LIKELY( exp )       (__builtin_expect( (exp) != 0, true  ))
#define UNLIKELY( exp )     (__builtin_expect( (exp) != 0, false ))

// ---------------------------------------------------------------------------

namespace android {

enum {
    GET_CBLK = IBinder::FIRST_CALL_TRANSACTION,
    CREATE_SURFACE,
    DESTROY_SURFACE,
    SET_STATE
};

class BpSurfaceFlingerClient : public BpInterface<ISurfaceFlingerClient>
{
public:
    BpSurfaceFlingerClient(const sp<IBinder>& impl)
        : BpInterface<ISurfaceFlingerClient>(impl)
    {
    }

    virtual void getControlBlocks(sp<IMemory>* ctl) const
    {
        Parcel data, reply;
        data.writeInterfaceToken(ISurfaceFlingerClient::getInterfaceDescriptor());
        remote()->transact(GET_CBLK, data, &reply);
        *ctl  = interface_cast<IMemory>(reply.readStrongBinder());
    }

    virtual sp<ISurface> createSurface( surface_data_t* params,
                                        int pid,
                                        DisplayID display,
                                        uint32_t w,
                                        uint32_t h,
                                        PixelFormat format,
                                        uint32_t flags)
    {
        Parcel data, reply;
        data.writeInterfaceToken(ISurfaceFlingerClient::getInterfaceDescriptor());
        data.writeInt32(pid);
        data.writeInt32(display);
        data.writeInt32(w);
        data.writeInt32(h);
        data.writeInt32(format);
        data.writeInt32(flags);
        remote()->transact(CREATE_SURFACE, data, &reply);
        params->readFromParcel(reply);
        return interface_cast<ISurface>(reply.readStrongBinder());
    }
                                    
    virtual status_t destroySurface(SurfaceID sid)
    {
        Parcel data, reply;
        data.writeInterfaceToken(ISurfaceFlingerClient::getInterfaceDescriptor());
        data.writeInt32(sid);
        remote()->transact(DESTROY_SURFACE, data, &reply);
        return reply.readInt32();
    }

    virtual status_t setState(int32_t count, const layer_state_t* states)
    {
        Parcel data, reply;
        data.writeInterfaceToken(ISurfaceFlingerClient::getInterfaceDescriptor());
        data.writeInt32(count);
        for (int i=0 ; i<count ; i++)
            states[i].write(data);
        remote()->transact(SET_STATE, data, &reply);
        return reply.readInt32();
    }
};

IMPLEMENT_META_INTERFACE(SurfaceFlingerClient, "android.ui.ISurfaceFlingerClient");

// ----------------------------------------------------------------------

#define CHECK_INTERFACE(interface, data, reply) \
        do { if (!data.enforceInterface(interface::getInterfaceDescriptor())) { \
            LOGW("Call incorrectly routed to " #interface); \
            return PERMISSION_DENIED; \
        } } while (0)

status_t BnSurfaceFlingerClient::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    // codes that don't require permission check

    switch(code) {
        case GET_CBLK: {
            CHECK_INTERFACE(ISurfaceFlingerClient, data, reply);
            sp<IMemory> ctl;
            getControlBlocks(&ctl);
            reply->writeStrongBinder(ctl->asBinder());
            return NO_ERROR;
        } break;
    }

    // these must be checked
     
     IPCThreadState* ipc = IPCThreadState::self();
     const int pid = ipc->getCallingPid();
     const int self_pid    = getpid();
     if (UNLIKELY(pid != self_pid)) {
         // we're called from a different process, do the real check
         if (!checkCallingPermission(
                 String16("android.permission.ACCESS_SURFACE_FLINGER")))
         {
             const int uid = ipc->getCallingUid();
             LOGE("Permission Denial: "
                     "can't openGlobalTransaction pid=%d, uid=%d", pid, uid);
             return PERMISSION_DENIED;
         }
     }
   
     switch(code) {
        case CREATE_SURFACE: {
            CHECK_INTERFACE(ISurfaceFlingerClient, data, reply);
            surface_data_t params;
            int32_t pid = data.readInt32();
            DisplayID display = data.readInt32();
            uint32_t w = data.readInt32();
            uint32_t h = data.readInt32();
            PixelFormat format = data.readInt32();
            uint32_t flags = data.readInt32();
            sp<ISurface> s = createSurface(&params, pid, display, w, h, format, flags);
            params.writeToParcel(reply);
            reply->writeStrongBinder(s->asBinder());
            return NO_ERROR;
        } break;
        case DESTROY_SURFACE: {
            CHECK_INTERFACE(ISurfaceFlingerClient, data, reply);
            reply->writeInt32( destroySurface( data.readInt32() ) );
            return NO_ERROR;
        } break;
        case SET_STATE: {
            CHECK_INTERFACE(ISurfaceFlingerClient, data, reply);
            int32_t count = data.readInt32();
            layer_state_t* states = new layer_state_t[count];
            for (int i=0 ; i<count ; i++)
                states[i].read(data);
            status_t err = setState(count, states);
            delete [] states;
            reply->writeInt32(err);
            return NO_ERROR;
        } break;
        default:
            return BBinder::onTransact(code, data, reply, flags);
    }
}

// ----------------------------------------------------------------------

status_t ISurfaceFlingerClient::surface_data_t::readFromParcel(const Parcel& parcel)
{
    token = parcel.readInt32();
    identity  = parcel.readInt32();
    heap[0] = interface_cast<IMemoryHeap>(parcel.readStrongBinder());
    heap[1] = interface_cast<IMemoryHeap>(parcel.readStrongBinder());
    return NO_ERROR;
}

status_t ISurfaceFlingerClient::surface_data_t::writeToParcel(Parcel* parcel) const
{
    parcel->writeInt32(token);
    parcel->writeInt32(identity);
    parcel->writeStrongBinder(heap[0]!=0 ? heap[0]->asBinder() : NULL);
    parcel->writeStrongBinder(heap[1]!=0 ? heap[1]->asBinder() : NULL);
    return NO_ERROR;
}

}; // namespace android
