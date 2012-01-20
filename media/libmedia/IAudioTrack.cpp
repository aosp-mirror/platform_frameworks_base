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

#define LOG_TAG "IAudioTrack"
//#define LOG_NDEBUG 0
#include <utils/Log.h>

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
    PAUSE,
    ATTACH_AUX_EFFECT
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
        status_t status = remote()->transact(START, data, &reply);
        if (status == NO_ERROR) {
            status = reply.readInt32();
        } else {
            ALOGW("start() error: %s", strerror(-status));
        }
        return status;
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
        sp<IMemory> cblk;
        data.writeInterfaceToken(IAudioTrack::getInterfaceDescriptor());
        status_t status = remote()->transact(GET_CBLK, data, &reply);
        if (status == NO_ERROR) {
            cblk = interface_cast<IMemory>(reply.readStrongBinder());
        }
        return cblk;
    }

    virtual status_t attachAuxEffect(int effectId)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IAudioTrack::getInterfaceDescriptor());
        data.writeInt32(effectId);
        status_t status = remote()->transact(ATTACH_AUX_EFFECT, data, &reply);
        if (status == NO_ERROR) {
            status = reply.readInt32();
        } else {
            ALOGW("attachAuxEffect() error: %s", strerror(-status));
        }
        return status;
    }
};

IMPLEMENT_META_INTERFACE(AudioTrack, "android.media.IAudioTrack");

// ----------------------------------------------------------------------

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
        case ATTACH_AUX_EFFECT: {
            CHECK_INTERFACE(IAudioTrack, data, reply);
            reply->writeInt32(attachAuxEffect(data.readInt32()));
            return NO_ERROR;
        } break;
        default:
            return BBinder::onTransact(code, data, reply, flags);
    }
}

}; // namespace android

