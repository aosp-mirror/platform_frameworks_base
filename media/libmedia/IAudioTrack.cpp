/* //device/extlibs/pv/android/IAudioTrack.cpp
**
** Copyright 2007, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License"); 
** you may not use this file except in compliance with the License. 
** You may obtain a copy of the License at 
**
**     http://www.apache.org/licenses/LICENSE-2.0 
**
** Unless required by applicable law or agreed to in writing, software 
** distributed under the License is distributed on an "AS IS" BASIS, 
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
** See the License for the specific language governing permissions and 
** limitations under the License.
*/

#include <stdint.h>
#include <sys/types.h>

#include <binder/Parcel.h>

#include <media/IAudioTrack.h>

namespace android {

enum {
    GET_CBLK = IBinder::FIRST_CALL_TRANSACTION,
    START,
    STOP,
    FLUSH,
    MUTE,
    PAUSE
};

class BpAudioTrack : public BpInterface<IAudioTrack>
{
public:
    BpAudioTrack(const sp<IBinder>& impl)
        : BpInterface<IAudioTrack>(impl)
    {
    }
    
    virtual status_t start()
    {
        Parcel data, reply;
        data.writeInterfaceToken(IAudioTrack::getInterfaceDescriptor());
        remote()->transact(START, data, &reply);
        return reply.readInt32();
    }
    
    virtual void stop()
    {
        Parcel data, reply;
        data.writeInterfaceToken(IAudioTrack::getInterfaceDescriptor());
        remote()->transact(STOP, data, &reply);
    }
    
    virtual void flush()
    {
        Parcel data, reply;
        data.writeInterfaceToken(IAudioTrack::getInterfaceDescriptor());
        remote()->transact(FLUSH, data, &reply);
    }

    virtual void mute(bool e)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IAudioTrack::getInterfaceDescriptor());
        data.writeInt32(e);
        remote()->transact(MUTE, data, &reply);
    }
    
    virtual void pause()
    {
        Parcel data, reply;
        data.writeInterfaceToken(IAudioTrack::getInterfaceDescriptor());
        remote()->transact(PAUSE, data, &reply);
    }
    
    virtual sp<IMemory> getCblk() const
    {
        Parcel data, reply;
        data.writeInterfaceToken(IAudioTrack::getInterfaceDescriptor());
        remote()->transact(GET_CBLK, data, &reply);
        return interface_cast<IMemory>(reply.readStrongBinder());
    }    
};

IMPLEMENT_META_INTERFACE(AudioTrack, "android.media.IAudioTrack");

// ----------------------------------------------------------------------

#define CHECK_INTERFACE(interface, data, reply) \
        do { if (!data.enforceInterface(interface::getInterfaceDescriptor())) { \
            LOGW("Call incorrectly routed to " #interface); \
            return PERMISSION_DENIED; \
        } } while (0)

status_t BnAudioTrack::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    switch(code) {
       case GET_CBLK: {
            CHECK_INTERFACE(IAudioTrack, data, reply);
            reply->writeStrongBinder(getCblk()->asBinder());
            return NO_ERROR;
        } break;
        case START: {
            CHECK_INTERFACE(IAudioTrack, data, reply);
            reply->writeInt32(start());
            return NO_ERROR;
        } break;
        case STOP: {
            CHECK_INTERFACE(IAudioTrack, data, reply);
            stop();
            return NO_ERROR;
        } break;
        case FLUSH: {
            CHECK_INTERFACE(IAudioTrack, data, reply);
            flush();
            return NO_ERROR;
        } break;
        case MUTE: {
            CHECK_INTERFACE(IAudioTrack, data, reply);
            mute( data.readInt32() );
            return NO_ERROR;
        } break;
        case PAUSE: {
            CHECK_INTERFACE(IAudioTrack, data, reply);
            pause();
            return NO_ERROR;
        }
        default:
            return BBinder::onTransact(code, data, reply, flags);
    }
}

}; // namespace android

