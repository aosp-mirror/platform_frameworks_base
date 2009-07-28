/* //device/extlibs/pv/android/IAudioflinger.cpp
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

#define LOG_TAG "IAudioFlinger"
//#define LOG_NDEBUG 0
#include <utils/Log.h>

#include <stdint.h>
#include <sys/types.h>

#include <binder/Parcel.h>

#include <media/IAudioFlinger.h>

namespace android {

enum {
    CREATE_TRACK = IBinder::FIRST_CALL_TRANSACTION,
    OPEN_RECORD,
    SAMPLE_RATE,
    CHANNEL_COUNT,
    FORMAT,
    FRAME_COUNT,
    LATENCY,
    SET_MASTER_VOLUME,
    SET_MASTER_MUTE,
    MASTER_VOLUME,
    MASTER_MUTE,
    SET_STREAM_VOLUME,
    SET_STREAM_MUTE,
    STREAM_VOLUME,
    STREAM_MUTE,
    SET_MODE,
    SET_MIC_MUTE,
    GET_MIC_MUTE,
    IS_MUSIC_ACTIVE,
    SET_PARAMETERS,
    GET_PARAMETERS,
    REGISTER_CLIENT,
    GET_INPUTBUFFERSIZE,
    OPEN_OUTPUT,
    OPEN_DUPLICATE_OUTPUT,
    CLOSE_OUTPUT,
    SUSPEND_OUTPUT,
    RESTORE_OUTPUT,
    OPEN_INPUT,
    CLOSE_INPUT,
    SET_STREAM_OUTPUT
};

class BpAudioFlinger : public BpInterface<IAudioFlinger>
{
public:
    BpAudioFlinger(const sp<IBinder>& impl)
        : BpInterface<IAudioFlinger>(impl)
    {
    }

    virtual sp<IAudioTrack> createTrack(
                                pid_t pid,
                                int streamType,
                                uint32_t sampleRate,
                                int format,
                                int channelCount,
                                int frameCount,
                                uint32_t flags,
                                const sp<IMemory>& sharedBuffer,
                                int output,
                                status_t *status)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IAudioFlinger::getInterfaceDescriptor());
        data.writeInt32(pid);
        data.writeInt32(streamType);
        data.writeInt32(sampleRate);
        data.writeInt32(format);
        data.writeInt32(channelCount);
        data.writeInt32(frameCount);
        data.writeInt32(flags);
        data.writeStrongBinder(sharedBuffer->asBinder());
        data.writeInt32(output);
        status_t lStatus = remote()->transact(CREATE_TRACK, data, &reply);
        if (lStatus != NO_ERROR) {
            LOGE("createTrack error: %s", strerror(-lStatus));
        }
        lStatus = reply.readInt32();
        if (status) {
            *status = lStatus;
        }
        return interface_cast<IAudioTrack>(reply.readStrongBinder());
    }

    virtual sp<IAudioRecord> openRecord(
                                pid_t pid,
                                int input,
                                uint32_t sampleRate,
                                int format,
                                int channelCount,
                                int frameCount,
                                uint32_t flags,
                                status_t *status)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IAudioFlinger::getInterfaceDescriptor());
        data.writeInt32(pid);
        data.writeInt32(input);
        data.writeInt32(sampleRate);
        data.writeInt32(format);
        data.writeInt32(channelCount);
        data.writeInt32(frameCount);
        data.writeInt32(flags);
        remote()->transact(OPEN_RECORD, data, &reply);
        status_t lStatus = reply.readInt32();
        if (status) {
            *status = lStatus;
        }
        return interface_cast<IAudioRecord>(reply.readStrongBinder());
    }

    virtual uint32_t sampleRate(int output) const
    {
        Parcel data, reply;
        data.writeInterfaceToken(IAudioFlinger::getInterfaceDescriptor());
        data.writeInt32(output);
        remote()->transact(SAMPLE_RATE, data, &reply);
        return reply.readInt32();
    }

    virtual int channelCount(int output) const
    {
        Parcel data, reply;
        data.writeInterfaceToken(IAudioFlinger::getInterfaceDescriptor());
        data.writeInt32(output);
        remote()->transact(CHANNEL_COUNT, data, &reply);
        return reply.readInt32();
    }

    virtual int format(int output) const
    {
        Parcel data, reply;
        data.writeInterfaceToken(IAudioFlinger::getInterfaceDescriptor());
        data.writeInt32(output);
        remote()->transact(FORMAT, data, &reply);
        return reply.readInt32();
    }

    virtual size_t frameCount(int output) const
    {
        Parcel data, reply;
        data.writeInterfaceToken(IAudioFlinger::getInterfaceDescriptor());
        data.writeInt32(output);
        remote()->transact(FRAME_COUNT, data, &reply);
        return reply.readInt32();
    }

    virtual uint32_t latency(int output) const
    {
        Parcel data, reply;
        data.writeInterfaceToken(IAudioFlinger::getInterfaceDescriptor());
        data.writeInt32(output);
        remote()->transact(LATENCY, data, &reply);
        return reply.readInt32();
    }

    virtual status_t setMasterVolume(float value)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IAudioFlinger::getInterfaceDescriptor());
        data.writeFloat(value);
        remote()->transact(SET_MASTER_VOLUME, data, &reply);
        return reply.readInt32();
    }

    virtual status_t setMasterMute(bool muted)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IAudioFlinger::getInterfaceDescriptor());
        data.writeInt32(muted);
        remote()->transact(SET_MASTER_MUTE, data, &reply);
        return reply.readInt32();
    }

    virtual float masterVolume() const
    {
        Parcel data, reply;
        data.writeInterfaceToken(IAudioFlinger::getInterfaceDescriptor());
        remote()->transact(MASTER_VOLUME, data, &reply);
        return reply.readFloat();
    }

    virtual bool masterMute() const
    {
        Parcel data, reply;
        data.writeInterfaceToken(IAudioFlinger::getInterfaceDescriptor());
        remote()->transact(MASTER_MUTE, data, &reply);
        return reply.readInt32();
    }

    virtual status_t setStreamVolume(int stream, float value, int output)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IAudioFlinger::getInterfaceDescriptor());
        data.writeInt32(stream);
        data.writeFloat(value);
        data.writeInt32(output);
        remote()->transact(SET_STREAM_VOLUME, data, &reply);
        return reply.readInt32();
    }

    virtual status_t setStreamMute(int stream, bool muted)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IAudioFlinger::getInterfaceDescriptor());
        data.writeInt32(stream);
        data.writeInt32(muted);
        remote()->transact(SET_STREAM_MUTE, data, &reply);
        return reply.readInt32();
    }

    virtual float streamVolume(int stream, int output) const
    {
        Parcel data, reply;
        data.writeInterfaceToken(IAudioFlinger::getInterfaceDescriptor());
        data.writeInt32(stream);
        data.writeInt32(output);
        remote()->transact(STREAM_VOLUME, data, &reply);
        return reply.readFloat();
    }

    virtual bool streamMute(int stream) const
    {
        Parcel data, reply;
        data.writeInterfaceToken(IAudioFlinger::getInterfaceDescriptor());
        data.writeInt32(stream);
        remote()->transact(STREAM_MUTE, data, &reply);
        return reply.readInt32();
    }

    virtual status_t setMode(int mode)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IAudioFlinger::getInterfaceDescriptor());
        data.writeInt32(mode);
        remote()->transact(SET_MODE, data, &reply);
        return reply.readInt32();
    }

    virtual status_t setMicMute(bool state)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IAudioFlinger::getInterfaceDescriptor());
        data.writeInt32(state);
        remote()->transact(SET_MIC_MUTE, data, &reply);
        return reply.readInt32();
    }

    virtual bool getMicMute() const
    {
        Parcel data, reply;
        data.writeInterfaceToken(IAudioFlinger::getInterfaceDescriptor());
        remote()->transact(GET_MIC_MUTE, data, &reply);
        return reply.readInt32();
    }

    virtual bool isMusicActive() const
    {
        Parcel data, reply;
        data.writeInterfaceToken(IAudioFlinger::getInterfaceDescriptor());
        remote()->transact(IS_MUSIC_ACTIVE, data, &reply);
        return reply.readInt32();
    }

    virtual status_t setParameters(int ioHandle, const String8& keyValuePairs)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IAudioFlinger::getInterfaceDescriptor());
        data.writeInt32(ioHandle);
        data.writeString8(keyValuePairs);
        remote()->transact(SET_PARAMETERS, data, &reply);
        return reply.readInt32();
    }

    virtual String8 getParameters(int ioHandle, const String8& keys)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IAudioFlinger::getInterfaceDescriptor());
        data.writeInt32(ioHandle);
        data.writeString8(keys);
        remote()->transact(GET_PARAMETERS, data, &reply);
        return reply.readString8();
    }

    virtual void registerClient(const sp<IAudioFlingerClient>& client)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IAudioFlinger::getInterfaceDescriptor());
        data.writeStrongBinder(client->asBinder());
        remote()->transact(REGISTER_CLIENT, data, &reply);
    }

    virtual size_t getInputBufferSize(uint32_t sampleRate, int format, int channelCount)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IAudioFlinger::getInterfaceDescriptor());
        data.writeInt32(sampleRate);
        data.writeInt32(format);
        data.writeInt32(channelCount);
        remote()->transact(GET_INPUTBUFFERSIZE, data, &reply);
        return reply.readInt32();
    }

    virtual int openOutput(uint32_t *pDevices,
                            uint32_t *pSamplingRate,
                            uint32_t *pFormat,
                            uint32_t *pChannels,
                            uint32_t *pLatencyMs,
                            uint32_t flags)
    {
        Parcel data, reply;
        uint32_t devices = pDevices ? *pDevices : 0;
        uint32_t samplingRate = pSamplingRate ? *pSamplingRate : 0;
        uint32_t format = pFormat ? *pFormat : 0;
        uint32_t channels = pChannels ? *pChannels : 0;
        uint32_t latency = pLatencyMs ? *pLatencyMs : 0;

        data.writeInterfaceToken(IAudioFlinger::getInterfaceDescriptor());
        data.writeInt32(devices);
        data.writeInt32(samplingRate);
        data.writeInt32(format);
        data.writeInt32(channels);
        data.writeInt32(latency);
        data.writeInt32(flags);
        remote()->transact(OPEN_OUTPUT, data, &reply);
        int  output = reply.readInt32();
        LOGV("openOutput() returned output, %p", output);
        devices = reply.readInt32();
        if (pDevices) *pDevices = devices;
        samplingRate = reply.readInt32();
        if (pSamplingRate) *pSamplingRate = samplingRate;
        format = reply.readInt32();
        if (pFormat) *pFormat = format;
        channels = reply.readInt32();
        if (pChannels) *pChannels = channels;
        latency = reply.readInt32();
        if (pLatencyMs) *pLatencyMs = latency;
        return output;
    }

    virtual int openDuplicateOutput(int output1, int output2)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IAudioFlinger::getInterfaceDescriptor());
        data.writeInt32(output1);
        data.writeInt32(output2);
        remote()->transact(OPEN_DUPLICATE_OUTPUT, data, &reply);
        return reply.readInt32();
    }

    virtual status_t closeOutput(int output)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IAudioFlinger::getInterfaceDescriptor());
        data.writeInt32(output);
        remote()->transact(CLOSE_OUTPUT, data, &reply);
        return reply.readInt32();
    }

    virtual status_t suspendOutput(int output)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IAudioFlinger::getInterfaceDescriptor());
        data.writeInt32(output);
        remote()->transact(SUSPEND_OUTPUT, data, &reply);
        return reply.readInt32();
    }

    virtual status_t restoreOutput(int output)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IAudioFlinger::getInterfaceDescriptor());
        data.writeInt32(output);
        remote()->transact(RESTORE_OUTPUT, data, &reply);
        return reply.readInt32();
    }

    virtual int openInput(uint32_t *pDevices,
                            uint32_t *pSamplingRate,
                            uint32_t *pFormat,
                            uint32_t *pChannels,
                            uint32_t acoustics)
    {
        Parcel data, reply;
        uint32_t devices = pDevices ? *pDevices : 0;
        uint32_t samplingRate = pSamplingRate ? *pSamplingRate : 0;
        uint32_t format = pFormat ? *pFormat : 0;
        uint32_t channels = pChannels ? *pChannels : 0;

        data.writeInterfaceToken(IAudioFlinger::getInterfaceDescriptor());
        data.writeInt32(devices);
        data.writeInt32(samplingRate);
        data.writeInt32(format);
        data.writeInt32(channels);
        data.writeInt32(acoustics);
        remote()->transact(OPEN_INPUT, data, &reply);
        int input = reply.readInt32();
        devices = reply.readInt32();
        if (pDevices) *pDevices = devices;
        samplingRate = reply.readInt32();
        if (pSamplingRate) *pSamplingRate = samplingRate;
        format = reply.readInt32();
        if (pFormat) *pFormat = format;
        channels = reply.readInt32();
        if (pChannels) *pChannels = channels;
        return input;
    }

    virtual status_t closeInput(int input)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IAudioFlinger::getInterfaceDescriptor());
        data.writeInt32(input);
        remote()->transact(CLOSE_INPUT, data, &reply);
        return reply.readInt32();
    }

    virtual status_t setStreamOutput(uint32_t stream, int output)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IAudioFlinger::getInterfaceDescriptor());
        data.writeInt32(stream);
        data.writeInt32(output);
        remote()->transact(SET_STREAM_OUTPUT, data, &reply);
        return reply.readInt32();
    }
};

IMPLEMENT_META_INTERFACE(AudioFlinger, "android.media.IAudioFlinger");

// ----------------------------------------------------------------------

status_t BnAudioFlinger::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    switch(code) {
        case CREATE_TRACK: {
            CHECK_INTERFACE(IAudioFlinger, data, reply);
            pid_t pid = data.readInt32();
            int streamType = data.readInt32();
            uint32_t sampleRate = data.readInt32();
            int format = data.readInt32();
            int channelCount = data.readInt32();
            size_t bufferCount = data.readInt32();
            uint32_t flags = data.readInt32();
            sp<IMemory> buffer = interface_cast<IMemory>(data.readStrongBinder());
            int output = data.readInt32();
            status_t status;
            sp<IAudioTrack> track = createTrack(pid,
                    streamType, sampleRate, format,
                    channelCount, bufferCount, flags, buffer, output, &status);
            reply->writeInt32(status);
            reply->writeStrongBinder(track->asBinder());
            return NO_ERROR;
        } break;
        case OPEN_RECORD: {
            CHECK_INTERFACE(IAudioFlinger, data, reply);
            pid_t pid = data.readInt32();
            int input = data.readInt32();
            uint32_t sampleRate = data.readInt32();
            int format = data.readInt32();
            int channelCount = data.readInt32();
            size_t bufferCount = data.readInt32();
            uint32_t flags = data.readInt32();
            status_t status;
            sp<IAudioRecord> record = openRecord(pid, input,
                    sampleRate, format, channelCount, bufferCount, flags, &status);
            reply->writeInt32(status);
            reply->writeStrongBinder(record->asBinder());
            return NO_ERROR;
        } break;
        case SAMPLE_RATE: {
            CHECK_INTERFACE(IAudioFlinger, data, reply);
            reply->writeInt32( sampleRate(data.readInt32()) );
            return NO_ERROR;
        } break;
        case CHANNEL_COUNT: {
            CHECK_INTERFACE(IAudioFlinger, data, reply);
            reply->writeInt32( channelCount(data.readInt32()) );
            return NO_ERROR;
        } break;
        case FORMAT: {
            CHECK_INTERFACE(IAudioFlinger, data, reply);
            reply->writeInt32( format(data.readInt32()) );
            return NO_ERROR;
        } break;
        case FRAME_COUNT: {
            CHECK_INTERFACE(IAudioFlinger, data, reply);
            reply->writeInt32( frameCount(data.readInt32()) );
            return NO_ERROR;
        } break;
        case LATENCY: {
            CHECK_INTERFACE(IAudioFlinger, data, reply);
            reply->writeInt32( latency(data.readInt32()) );
            return NO_ERROR;
        } break;
         case SET_MASTER_VOLUME: {
            CHECK_INTERFACE(IAudioFlinger, data, reply);
            reply->writeInt32( setMasterVolume(data.readFloat()) );
            return NO_ERROR;
        } break;
        case SET_MASTER_MUTE: {
            CHECK_INTERFACE(IAudioFlinger, data, reply);
            reply->writeInt32( setMasterMute(data.readInt32()) );
            return NO_ERROR;
        } break;
        case MASTER_VOLUME: {
            CHECK_INTERFACE(IAudioFlinger, data, reply);
            reply->writeFloat( masterVolume() );
            return NO_ERROR;
        } break;
        case MASTER_MUTE: {
            CHECK_INTERFACE(IAudioFlinger, data, reply);
            reply->writeInt32( masterMute() );
            return NO_ERROR;
        } break;
        case SET_STREAM_VOLUME: {
            CHECK_INTERFACE(IAudioFlinger, data, reply);
            int stream = data.readInt32();
            float volume = data.readFloat();
            int output = data.readInt32();
            reply->writeInt32( setStreamVolume(stream, volume, output) );
            return NO_ERROR;
        } break;
        case SET_STREAM_MUTE: {
            CHECK_INTERFACE(IAudioFlinger, data, reply);
            int stream = data.readInt32();
            reply->writeInt32( setStreamMute(stream, data.readInt32()) );
            return NO_ERROR;
        } break;
        case STREAM_VOLUME: {
            CHECK_INTERFACE(IAudioFlinger, data, reply);
            int stream = data.readInt32();
            int output = data.readInt32();
            reply->writeFloat( streamVolume(stream, output) );
            return NO_ERROR;
        } break;
        case STREAM_MUTE: {
            CHECK_INTERFACE(IAudioFlinger, data, reply);
            int stream = data.readInt32();
            reply->writeInt32( streamMute(stream) );
            return NO_ERROR;
        } break;
        case SET_MODE: {
            CHECK_INTERFACE(IAudioFlinger, data, reply);
            int mode = data.readInt32();
            reply->writeInt32( setMode(mode) );
            return NO_ERROR;
        } break;
        case SET_MIC_MUTE: {
            CHECK_INTERFACE(IAudioFlinger, data, reply);
            int state = data.readInt32();
            reply->writeInt32( setMicMute(state) );
            return NO_ERROR;
        } break;
        case GET_MIC_MUTE: {
            CHECK_INTERFACE(IAudioFlinger, data, reply);
            reply->writeInt32( getMicMute() );
            return NO_ERROR;
        } break;
        case IS_MUSIC_ACTIVE: {
            CHECK_INTERFACE(IAudioFlinger, data, reply);
            reply->writeInt32( isMusicActive() );
            return NO_ERROR;
        } break;
        case SET_PARAMETERS: {
            CHECK_INTERFACE(IAudioFlinger, data, reply);
            int ioHandle = data.readInt32();
            String8 keyValuePairs(data.readString8());
            reply->writeInt32(setParameters(ioHandle, keyValuePairs));
            return NO_ERROR;
         } break;
        case GET_PARAMETERS: {
            CHECK_INTERFACE(IAudioFlinger, data, reply);
            int ioHandle = data.readInt32();
            String8 keys(data.readString8());
            reply->writeString8(getParameters(ioHandle, keys));
            return NO_ERROR;
         } break;

        case REGISTER_CLIENT: {
            CHECK_INTERFACE(IAudioFlinger, data, reply);
            sp<IAudioFlingerClient> client = interface_cast<IAudioFlingerClient>(data.readStrongBinder());
            registerClient(client);
            return NO_ERROR;
        } break;
        case GET_INPUTBUFFERSIZE: {
            CHECK_INTERFACE(IAudioFlinger, data, reply);
            uint32_t sampleRate = data.readInt32();
            int format = data.readInt32();
            int channelCount = data.readInt32();
            reply->writeInt32( getInputBufferSize(sampleRate, format, channelCount) );
            return NO_ERROR;
        } break;
        case OPEN_OUTPUT: {
            CHECK_INTERFACE(IAudioFlinger, data, reply);
            uint32_t devices = data.readInt32();
            uint32_t samplingRate = data.readInt32();
            uint32_t format = data.readInt32();
            uint32_t channels = data.readInt32();
            uint32_t latency = data.readInt32();
            uint32_t flags = data.readInt32();
            int output = openOutput(&devices,
                                     &samplingRate,
                                     &format,
                                     &channels,
                                     &latency,
                                     flags);
            LOGV("OPEN_OUTPUT output, %p", output);
            reply->writeInt32(output);
            reply->writeInt32(devices);
            reply->writeInt32(samplingRate);
            reply->writeInt32(format);
            reply->writeInt32(channels);
            reply->writeInt32(latency);
            return NO_ERROR;
        } break;
        case OPEN_DUPLICATE_OUTPUT: {
            CHECK_INTERFACE(IAudioFlinger, data, reply);
            int output1 = data.readInt32();
            int output2 = data.readInt32();
            reply->writeInt32(openDuplicateOutput(output1, output2));
            return NO_ERROR;
        } break;
        case CLOSE_OUTPUT: {
            CHECK_INTERFACE(IAudioFlinger, data, reply);
            reply->writeInt32(closeOutput(data.readInt32()));
            return NO_ERROR;
        } break;
        case SUSPEND_OUTPUT: {
            CHECK_INTERFACE(IAudioFlinger, data, reply);
            reply->writeInt32(suspendOutput(data.readInt32()));
            return NO_ERROR;
        } break;
        case RESTORE_OUTPUT: {
            CHECK_INTERFACE(IAudioFlinger, data, reply);
            reply->writeInt32(restoreOutput(data.readInt32()));
            return NO_ERROR;
        } break;
        case OPEN_INPUT: {
            CHECK_INTERFACE(IAudioFlinger, data, reply);
            uint32_t devices = data.readInt32();
            uint32_t samplingRate = data.readInt32();
            uint32_t format = data.readInt32();
            uint32_t channels = data.readInt32();
            uint32_t acoutics = data.readInt32();

            int input = openInput(&devices,
                                     &samplingRate,
                                     &format,
                                     &channels,
                                     acoutics);
            reply->writeInt32(input);
            reply->writeInt32(devices);
            reply->writeInt32(samplingRate);
            reply->writeInt32(format);
            reply->writeInt32(channels);
            return NO_ERROR;
        } break;
        case CLOSE_INPUT: {
            CHECK_INTERFACE(IAudioFlinger, data, reply);
            reply->writeInt32(closeInput(data.readInt32()));
            return NO_ERROR;
        } break;
        case SET_STREAM_OUTPUT: {
            CHECK_INTERFACE(IAudioFlinger, data, reply);
            uint32_t stream = data.readInt32();
            int output = data.readInt32();
            reply->writeInt32(setStreamOutput(stream, output));
            return NO_ERROR;
        } break;
        default:
            return BBinder::onTransact(code, data, reply, flags);
    }
}

// ----------------------------------------------------------------------------

}; // namespace android
