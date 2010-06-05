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

#include <binder/Parcel.h>
#include <binder/IMemory.h>
#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>

#include <ui/Point.h>
#include <ui/Rect.h>

#include <surfaceflinger/ISurface.h>
#include <surfaceflinger/ISurfaceComposerClient.h>
#include <private/surfaceflinger/LayerState.h>

// ---------------------------------------------------------------------------

/* ideally AID_GRAPHICS would be in a semi-public header
 * or there would be a way to map a user/group name to its id
 */
#ifndef AID_GRAPHICS
#define AID_GRAPHICS 1003
#endif

#define LIKELY( exp )       (__builtin_expect( (exp) != 0, true  ))
#define UNLIKELY( exp )     (__builtin_expect( (exp) != 0, false ))

// ---------------------------------------------------------------------------

namespace android {

enum {
    GET_CBLK = IBinder::FIRST_CALL_TRANSACTION,
    GET_TOKEN,
    CREATE_SURFACE,
    DESTROY_SURFACE,
    SET_STATE
};

class BpSurfaceComposerClient : public BpInterface<ISurfaceComposerClient>
{
public:
    BpSurfaceComposerClient(const sp<IBinder>& impl)
        : BpInterface<ISurfaceComposerClient>(impl)
    {
    }

    virtual sp<IMemoryHeap> getControlBlock() const
    {
        Parcel data, reply;
        data.writeInterfaceToken(ISurfaceComposerClient::getInterfaceDescriptor());
        remote()->transact(GET_CBLK, data, &reply);
        return interface_cast<IMemoryHeap>(reply.readStrongBinder());
    }

    virtual ssize_t getTokenForSurface(const sp<ISurface>& sur) const
    {
        Parcel data, reply;
        data.writeInterfaceToken(ISurfaceComposerClient::getInterfaceDescriptor());
        data.writeStrongBinder(sur->asBinder());
        remote()->transact(GET_TOKEN, data, &reply);
        return reply.readInt32();
    }

    virtual sp<ISurface> createSurface( surface_data_t* params,
                                        int pid,
                                        const String8& name,
                                        DisplayID display,
                                        uint32_t w,
                                        uint32_t h,
                                        PixelFormat format,
                                        uint32_t flags)
    {
        Parcel data, reply;
        data.writeInterfaceToken(ISurfaceComposerClient::getInterfaceDescriptor());
        data.writeInt32(pid);
        data.writeString8(name);
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
        data.writeInterfaceToken(ISurfaceComposerClient::getInterfaceDescriptor());
        data.writeInt32(sid);
        remote()->transact(DESTROY_SURFACE, data, &reply);
        return reply.readInt32();
    }

    virtual status_t setState(int32_t count, const layer_state_t* states)
    {
        Parcel data, reply;
        data.writeInterfaceToken(ISurfaceComposerClient::getInterfaceDescriptor());
        data.writeInt32(count);
        for (int i=0 ; i<count ; i++)
            states[i].write(data);
        remote()->transact(SET_STATE, data, &reply);
        return reply.readInt32();
    }
};

IMPLEMENT_META_INTERFACE(SurfaceComposerClient, "android.ui.ISurfaceComposerClient");

// ----------------------------------------------------------------------

status_t BnSurfaceComposerClient::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    // codes that don't require permission check

    switch(code) {
        case GET_CBLK: {
            CHECK_INTERFACE(ISurfaceComposerClient, data, reply);
            sp<IMemoryHeap> ctl(getControlBlock());
            reply->writeStrongBinder(ctl->asBinder());
            return NO_ERROR;
        } break;
        case GET_TOKEN: {
            CHECK_INTERFACE(ISurfaceComposerClient, data, reply);
            sp<ISurface> sur = interface_cast<ISurface>(data.readStrongBinder());
            ssize_t token = getTokenForSurface(sur);
            reply->writeInt32(token);
            return NO_ERROR;
        } break;
    }

    // these must be checked

     IPCThreadState* ipc = IPCThreadState::self();
     const int pid = ipc->getCallingPid();
     const int uid = ipc->getCallingUid();
     const int self_pid = getpid();
     if (UNLIKELY(pid != self_pid && uid != AID_GRAPHICS)) {
         // we're called from a different process, do the real check
         if (!checkCallingPermission(
                 String16("android.permission.ACCESS_SURFACE_FLINGER")))
         {
             LOGE("Permission Denial: "
                     "can't openGlobalTransaction pid=%d, uid=%d", pid, uid);
             return PERMISSION_DENIED;
         }
     }

     switch(code) {
        case CREATE_SURFACE: {
            CHECK_INTERFACE(ISurfaceComposerClient, data, reply);
            surface_data_t params;
            int32_t pid = data.readInt32();
            String8 name = data.readString8();
            DisplayID display = data.readInt32();
            uint32_t w = data.readInt32();
            uint32_t h = data.readInt32();
            PixelFormat format = data.readInt32();
            uint32_t flags = data.readInt32();
            sp<ISurface> s = createSurface(&params, pid, name, display, w, h,
                    format, flags);
            params.writeToParcel(reply);
            reply->writeStrongBinder(s->asBinder());
            return NO_ERROR;
        } break;
        case DESTROY_SURFACE: {
            CHECK_INTERFACE(ISurfaceComposerClient, data, reply);
            reply->writeInt32( destroySurface( data.readInt32() ) );
            return NO_ERROR;
        } break;
        case SET_STATE: {
            CHECK_INTERFACE(ISurfaceComposerClient, data, reply);
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

status_t ISurfaceComposerClient::surface_data_t::readFromParcel(const Parcel& parcel)
{
    token    = parcel.readInt32();
    identity = parcel.readInt32();
    width    = parcel.readInt32();
    height   = parcel.readInt32();
    format   = parcel.readInt32();
    return NO_ERROR;
}

status_t ISurfaceComposerClient::surface_data_t::writeToParcel(Parcel* parcel) const
{
    parcel->writeInt32(token);
    parcel->writeInt32(identity);
    parcel->writeInt32(width);
    parcel->writeInt32(height);
    parcel->writeInt32(format);
    return NO_ERROR;
}

}; // namespace android
