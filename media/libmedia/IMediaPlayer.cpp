/*
**
** Copyright 2008, The Android Open Source Project
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

#include <media/IMediaPlayer.h>
#include <surfaceflinger/ISurface.h>

namespace android {

enum {
    DISCONNECT = IBinder::FIRST_CALL_TRANSACTION,
    SET_VIDEO_SURFACE,
    PREPARE_ASYNC,
    START,
    STOP,
    IS_PLAYING,
    PAUSE,
    SEEK_TO,
    GET_CURRENT_POSITION,
    GET_DURATION,
    RESET,
    SET_AUDIO_STREAM_TYPE,
    SET_LOOPING,
    SET_VOLUME,
    INVOKE,
    SET_METADATA_FILTER,
    GET_METADATA,
    SUSPEND,
    RESUME,
    SET_AUX_EFFECT_SEND_LEVEL,
    ATTACH_AUX_EFFECT
};

class BpMediaPlayer: public BpInterface<IMediaPlayer>
{
public:
    BpMediaPlayer(const sp<IBinder>& impl)
        : BpInterface<IMediaPlayer>(impl)
    {
    }

    // disconnect from media player service
    void disconnect()
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMediaPlayer::getInterfaceDescriptor());
        remote()->transact(DISCONNECT, data, &reply);
    }

    status_t setVideoSurface(const sp<ISurface>& surface)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMediaPlayer::getInterfaceDescriptor());
        data.writeStrongBinder(surface->asBinder());
        remote()->transact(SET_VIDEO_SURFACE, data, &reply);
        return reply.readInt32();
    }

    status_t prepareAsync()
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMediaPlayer::getInterfaceDescriptor());
        remote()->transact(PREPARE_ASYNC, data, &reply);
        return reply.readInt32();
    }

    status_t start()
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMediaPlayer::getInterfaceDescriptor());
        remote()->transact(START, data, &reply);
        return reply.readInt32();
    }

    status_t stop()
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMediaPlayer::getInterfaceDescriptor());
        remote()->transact(STOP, data, &reply);
        return reply.readInt32();
    }

    status_t isPlaying(bool* state)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMediaPlayer::getInterfaceDescriptor());
        remote()->transact(IS_PLAYING, data, &reply);
        *state = reply.readInt32();
        return reply.readInt32();
    }

    status_t pause()
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMediaPlayer::getInterfaceDescriptor());
        remote()->transact(PAUSE, data, &reply);
        return reply.readInt32();
    }

    status_t seekTo(int msec)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMediaPlayer::getInterfaceDescriptor());
        data.writeInt32(msec);
        remote()->transact(SEEK_TO, data, &reply);
        return reply.readInt32();
    }

    status_t getCurrentPosition(int* msec)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMediaPlayer::getInterfaceDescriptor());
        remote()->transact(GET_CURRENT_POSITION, data, &reply);
        *msec = reply.readInt32();
        return reply.readInt32();
    }

    status_t getDuration(int* msec)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMediaPlayer::getInterfaceDescriptor());
        remote()->transact(GET_DURATION, data, &reply);
        *msec = reply.readInt32();
        return reply.readInt32();
    }

    status_t reset()
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMediaPlayer::getInterfaceDescriptor());
        remote()->transact(RESET, data, &reply);
        return reply.readInt32();
    }

    status_t setAudioStreamType(int type)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMediaPlayer::getInterfaceDescriptor());
        data.writeInt32(type);
        remote()->transact(SET_AUDIO_STREAM_TYPE, data, &reply);
        return reply.readInt32();
    }

    status_t setLooping(int loop)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMediaPlayer::getInterfaceDescriptor());
        data.writeInt32(loop);
        remote()->transact(SET_LOOPING, data, &reply);
        return reply.readInt32();
    }

    status_t setVolume(float leftVolume, float rightVolume)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMediaPlayer::getInterfaceDescriptor());
        data.writeFloat(leftVolume);
        data.writeFloat(rightVolume);
        remote()->transact(SET_VOLUME, data, &reply);
        return reply.readInt32();
    }

    status_t invoke(const Parcel& request, Parcel *reply)
    { // Avoid doing any extra copy. The interface descriptor should
      // have been set by MediaPlayer.java.
        return remote()->transact(INVOKE, request, reply);
    }

    status_t setMetadataFilter(const Parcel& request)
    {
        Parcel reply;
        // Avoid doing any extra copy of the request. The interface
        // descriptor should have been set by MediaPlayer.java.
        remote()->transact(SET_METADATA_FILTER, request, &reply);
        return reply.readInt32();
    }

    status_t getMetadata(bool update_only, bool apply_filter, Parcel *reply)
    {
        Parcel request;
        request.writeInterfaceToken(IMediaPlayer::getInterfaceDescriptor());
        // TODO: Burning 2 ints for 2 boolean. Should probably use flags in an int here.
        request.writeInt32(update_only);
        request.writeInt32(apply_filter);
        remote()->transact(GET_METADATA, request, reply);
        return reply->readInt32();
    }

    status_t suspend() {
        Parcel request;
        request.writeInterfaceToken(IMediaPlayer::getInterfaceDescriptor());

        Parcel reply;
        remote()->transact(SUSPEND, request, &reply);

        return reply.readInt32();
    }

    status_t resume() {
        Parcel request;
        request.writeInterfaceToken(IMediaPlayer::getInterfaceDescriptor());

        Parcel reply;
        remote()->transact(RESUME, request, &reply);

        return reply.readInt32();
    }

    status_t setAuxEffectSendLevel(float level)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMediaPlayer::getInterfaceDescriptor());
        data.writeFloat(level);
        remote()->transact(SET_AUX_EFFECT_SEND_LEVEL, data, &reply);
        return reply.readInt32();
    }

    status_t attachAuxEffect(int effectId)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMediaPlayer::getInterfaceDescriptor());
        data.writeInt32(effectId);
        remote()->transact(ATTACH_AUX_EFFECT, data, &reply);
        return reply.readInt32();
    }
};

IMPLEMENT_META_INTERFACE(MediaPlayer, "android.media.IMediaPlayer");

// ----------------------------------------------------------------------

status_t BnMediaPlayer::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    switch(code) {
        case DISCONNECT: {
            CHECK_INTERFACE(IMediaPlayer, data, reply);
            disconnect();
            return NO_ERROR;
        } break;
        case SET_VIDEO_SURFACE: {
            CHECK_INTERFACE(IMediaPlayer, data, reply);
            sp<ISurface> surface = interface_cast<ISurface>(data.readStrongBinder());
            reply->writeInt32(setVideoSurface(surface));
            return NO_ERROR;
        } break;
        case PREPARE_ASYNC: {
            CHECK_INTERFACE(IMediaPlayer, data, reply);
            reply->writeInt32(prepareAsync());
            return NO_ERROR;
        } break;
        case START: {
            CHECK_INTERFACE(IMediaPlayer, data, reply);
            reply->writeInt32(start());
            return NO_ERROR;
        } break;
        case STOP: {
            CHECK_INTERFACE(IMediaPlayer, data, reply);
            reply->writeInt32(stop());
            return NO_ERROR;
        } break;
        case IS_PLAYING: {
            CHECK_INTERFACE(IMediaPlayer, data, reply);
            bool state;
            status_t ret = isPlaying(&state);
            reply->writeInt32(state);
            reply->writeInt32(ret);
            return NO_ERROR;
        } break;
        case PAUSE: {
            CHECK_INTERFACE(IMediaPlayer, data, reply);
            reply->writeInt32(pause());
            return NO_ERROR;
        } break;
        case SEEK_TO: {
            CHECK_INTERFACE(IMediaPlayer, data, reply);
            reply->writeInt32(seekTo(data.readInt32()));
            return NO_ERROR;
        } break;
        case GET_CURRENT_POSITION: {
            CHECK_INTERFACE(IMediaPlayer, data, reply);
            int msec;
            status_t ret = getCurrentPosition(&msec);
            reply->writeInt32(msec);
            reply->writeInt32(ret);
            return NO_ERROR;
        } break;
        case GET_DURATION: {
            CHECK_INTERFACE(IMediaPlayer, data, reply);
            int msec;
            status_t ret = getDuration(&msec);
            reply->writeInt32(msec);
            reply->writeInt32(ret);
            return NO_ERROR;
        } break;
        case RESET: {
            CHECK_INTERFACE(IMediaPlayer, data, reply);
            reply->writeInt32(reset());
            return NO_ERROR;
        } break;
        case SET_AUDIO_STREAM_TYPE: {
            CHECK_INTERFACE(IMediaPlayer, data, reply);
            reply->writeInt32(setAudioStreamType(data.readInt32()));
            return NO_ERROR;
        } break;
        case SET_LOOPING: {
            CHECK_INTERFACE(IMediaPlayer, data, reply);
            reply->writeInt32(setLooping(data.readInt32()));
            return NO_ERROR;
        } break;
        case SET_VOLUME: {
            CHECK_INTERFACE(IMediaPlayer, data, reply);
            reply->writeInt32(setVolume(data.readFloat(), data.readFloat()));
            return NO_ERROR;
        } break;
        case INVOKE: {
            CHECK_INTERFACE(IMediaPlayer, data, reply);
            invoke(data, reply);
            return NO_ERROR;
        } break;
        case SET_METADATA_FILTER: {
            CHECK_INTERFACE(IMediaPlayer, data, reply);
            reply->writeInt32(setMetadataFilter(data));
            return NO_ERROR;
        } break;
        case SUSPEND: {
            CHECK_INTERFACE(IMediaPlayer, data, reply);
            reply->writeInt32(suspend());
            return NO_ERROR;
        } break;
        case RESUME: {
            CHECK_INTERFACE(IMediaPlayer, data, reply);
            reply->writeInt32(resume());
            return NO_ERROR;
        } break;
        case GET_METADATA: {
            CHECK_INTERFACE(IMediaPlayer, data, reply);
            const status_t retcode = getMetadata(data.readInt32(), data.readInt32(), reply);
            reply->setDataPosition(0);
            reply->writeInt32(retcode);
            reply->setDataPosition(0);
            return NO_ERROR;
        } break;
        case SET_AUX_EFFECT_SEND_LEVEL: {
            CHECK_INTERFACE(IMediaPlayer, data, reply);
            reply->writeInt32(setAuxEffectSendLevel(data.readFloat()));
            return NO_ERROR;
        } break;
        case ATTACH_AUX_EFFECT: {
            CHECK_INTERFACE(IMediaPlayer, data, reply);
            reply->writeInt32(attachAuxEffect(data.readInt32()));
            return NO_ERROR;
        } break;
        default:
            return BBinder::onTransact(code, data, reply, flags);
    }
}

// ----------------------------------------------------------------------------

}; // namespace android
