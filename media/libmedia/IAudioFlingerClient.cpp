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

#include <utils/Parcel.h>

#include <media/IAudioFlingerClient.h>

namespace android {

enum {
    AUDIO_OUTPUT_CHANGED = IBinder::FIRST_CALL_TRANSACTION
};

class BpAudioFlingerClient : public BpInterface<IAudioFlingerClient>
{
public:
    BpAudioFlingerClient(const sp<IBinder>& impl)
        : BpInterface<IAudioFlingerClient>(impl)
    {
    }

    void audioOutputChanged(uint32_t frameCount, uint32_t samplingRate, uint32_t latency)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IAudioFlingerClient::getInterfaceDescriptor());
        data.writeInt32(frameCount);
        data.writeInt32(samplingRate);
        data.writeInt32(latency);
        remote()->transact(AUDIO_OUTPUT_CHANGED, data, &reply);
    }
};

IMPLEMENT_META_INTERFACE(AudioFlingerClient, "android.media.IAudioFlingerClient");

// ----------------------------------------------------------------------

#define CHECK_INTERFACE(interface, data, reply) \
        do { if (!data.enforceInterface(interface::getInterfaceDescriptor())) { \
            LOGW("Call incorrectly routed to " #interface); \
            return PERMISSION_DENIED; \
        } } while (0)

status_t BnAudioFlingerClient::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    switch(code) {
        case AUDIO_OUTPUT_CHANGED: {
            CHECK_INTERFACE(IAudioFlingerClient, data, reply);
            uint32_t frameCount = data.readInt32();
            uint32_t samplingRate = data.readInt32();
            uint32_t latency = data.readInt32();
            audioOutputChanged(frameCount, samplingRate, latency);
            return NO_ERROR;
        } break;
        default:
            return BBinder::onTransact(code, data, reply, flags);
    }
}

// ----------------------------------------------------------------------------

}; // namespace android
