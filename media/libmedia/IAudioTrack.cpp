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
    ATTACH_AUX_EFFECT,
    ALLOCATE_TIMED_BUFFER,
    QUEUE_TIMED_BUFFER,
    SET_MEDIA_TIME_TRANSFORM,
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
            LOGW("start() error: %s", strerror(-status));
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
            LOGW("attachAuxEffect() error: %s", strerror(-status));
        }
        return status;
    }

    virtual status_t allocateTimedBuffer(size_t size, sp<IMemory>* buffer) {
        Parcel data, reply;
        data.writeInterfaceToken(IAudioTrack::getInterfaceDescriptor());
        data.writeInt32(size);
        status_t status = remote()->transact(ALLOCATE_TIMED_BUFFER,
                                             data, &reply);
        if (status == NO_ERROR) {
            status = reply.readInt32();
            if (status == NO_ERROR) {
                *buffer = interface_cast<IMemory>(reply.readStrongBinder());
            }
        }
        return status;
    }

    virtual status_t queueTimedBuffer(const sp<IMemory>& buffer,
                                      int64_t pts) {
        Parcel data, reply;
        data.writeInterfaceToken(IAudioTrack::getInterfaceDescriptor());
        data.writeStrongBinder(buffer->asBinder());
        data.writeInt64(pts);
        status_t status = remote()->transact(QUEUE_TIMED_BUFFER,
                                             data, &reply);
        if (status == NO_ERROR) {
            status = reply.readInt32();
        }
        return status;
    }

    virtual status_t setMediaTimeTransform(const LinearTransform& xform,
                                           int target) {
        Parcel data, reply;
        data.writeInterfaceToken(IAudioTrack::getInterfaceDescriptor());
        data.writeInt64(xform.a_zero);
        data.writeInt64(xform.b_zero);
        data.writeInt32(xform.a_to_b_numer);
        data.writeInt32(xform.a_to_b_denom);
        data.writeInt32(target);
        status_t status = remote()->transact(SET_MEDIA_TIME_TRANSFORM,
                                             data, &reply);
        if (status == NO_ERROR) {
            status = reply.readInt32();
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
        case ALLOCATE_TIMED_BUFFER: {
            CHECK_INTERFACE(IAudioTrack, data, reply);
            sp<IMemory> buffer;
            status_t status = allocateTimedBuffer(data.readInt32(), &buffer);
            reply->writeInt32(status);
            if (status == NO_ERROR) {
                reply->writeStrongBinder(buffer->asBinder());
            }
            return NO_ERROR;
        } break;
        case QUEUE_TIMED_BUFFER: {
            CHECK_INTERFACE(IAudioTrack, data, reply);
            sp<IMemory> buffer = interface_cast<IMemory>(
                data.readStrongBinder());
            uint64_t pts = data.readInt64();
            reply->writeInt32(queueTimedBuffer(buffer, pts));
            return NO_ERROR;
        } break;
        case SET_MEDIA_TIME_TRANSFORM: {
            CHECK_INTERFACE(IAudioTrack, data, reply);
            LinearTransform xform;
            xform.a_zero = data.readInt64();
            xform.b_zero = data.readInt64();
            xform.a_to_b_numer = data.readInt32();
            xform.a_to_b_denom = data.readInt32();
            int target = data.readInt32();
            reply->writeInt32(setMediaTimeTransform(xform, target));
            return NO_ERROR;
        } break;
        default:
            return BBinder::onTransact(code, data, reply, flags);
    }
}

}; // namespace android
