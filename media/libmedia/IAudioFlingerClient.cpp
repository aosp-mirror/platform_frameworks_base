/*
 * Copyright (C) 2009 The Android Open Source Project
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

#define LOG_TAG "IAudioFlingerClient"
#include <utils/Log.h>

#include <stdint.h>
#include <sys/types.h>

#include <binder/Parcel.h>

#include <media/IAudioFlingerClient.h>
#include <media/AudioSystem.h>

namespace android {

enum {
    IO_CONFIG_CHANGED = IBinder::FIRST_CALL_TRANSACTION
};

class BpAudioFlingerClient : public BpInterface<IAudioFlingerClient>
{
public:
    BpAudioFlingerClient(const sp<IBinder>& impl)
        : BpInterface<IAudioFlingerClient>(impl)
    {
    }

    void ioConfigChanged(int event, int ioHandle, void *param2)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IAudioFlingerClient::getInterfaceDescriptor());
        data.writeInt32(event);
        data.writeInt32(ioHandle);
        if (event == AudioSystem::STREAM_CONFIG_CHANGED) {
            uint32_t stream = *(uint32_t *)param2;
            ALOGV("ioConfigChanged stream %d", stream);
            data.writeInt32(stream);
        } else if (event != AudioSystem::OUTPUT_CLOSED && event != AudioSystem::INPUT_CLOSED) {
            AudioSystem::OutputDescriptor *desc = (AudioSystem::OutputDescriptor *)param2;
            data.writeInt32(desc->samplingRate);
            data.writeInt32(desc->format);
            data.writeInt32(desc->channels);
            data.writeInt32(desc->frameCount);
            data.writeInt32(desc->latency);
        }
        remote()->transact(IO_CONFIG_CHANGED, data, &reply, IBinder::FLAG_ONEWAY);
    }
};

IMPLEMENT_META_INTERFACE(AudioFlingerClient, "android.media.IAudioFlingerClient");

// ----------------------------------------------------------------------

status_t BnAudioFlingerClient::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    switch(code) {
    case IO_CONFIG_CHANGED: {
            CHECK_INTERFACE(IAudioFlingerClient, data, reply);
            int event = data.readInt32();
            int ioHandle = data.readInt32();
            void *param2 = 0;
            AudioSystem::OutputDescriptor desc;
            uint32_t stream;
            if (event == AudioSystem::STREAM_CONFIG_CHANGED) {
                stream = data.readInt32();
                param2 = &stream;
                ALOGV("STREAM_CONFIG_CHANGED stream %d", stream);
            } else if (event != AudioSystem::OUTPUT_CLOSED && event != AudioSystem::INPUT_CLOSED) {
                desc.samplingRate = data.readInt32();
                desc.format = data.readInt32();
                desc.channels = data.readInt32();
                desc.frameCount = data.readInt32();
                desc.latency = data.readInt32();
                param2 = &desc;
            }
            ioConfigChanged(event, ioHandle, param2);
            return NO_ERROR;
        } break;
        default:
            return BBinder::onTransact(code, data, reply, flags);
    }
}

// ----------------------------------------------------------------------------

}; // namespace android
