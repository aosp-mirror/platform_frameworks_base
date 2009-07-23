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

#ifndef ANDROID_IAUDIOPOLICYSERVICE_H
#define ANDROID_IAUDIOPOLICYSERVICE_H

#include <stdint.h>
#include <sys/types.h>
#include <unistd.h>

#include <utils/RefBase.h>
#include <utils/Errors.h>
#include <binder/IInterface.h>
#include <media/AudioSystem.h>


namespace android {

// ----------------------------------------------------------------------------

class IAudioPolicyService : public IInterface
{
public:
    DECLARE_META_INTERFACE(AudioPolicyService);

    //
    // IAudioPolicyService interface (see AudioPolicyInterface for method descriptions)
    //
    virtual status_t setDeviceConnectionState(AudioSystem::audio_devices device,
                                              AudioSystem::device_connection_state state,
                                              const char *device_address) = 0;
    virtual AudioSystem::device_connection_state getDeviceConnectionState(AudioSystem::audio_devices device,
                                                                          const char *device_address) = 0;
    virtual status_t setPhoneState(int state) = 0;
    virtual status_t setRingerMode(uint32_t mode, uint32_t mask) = 0;
    virtual status_t setForceUse(AudioSystem::force_use usage, AudioSystem::forced_config config) = 0;
    virtual AudioSystem::forced_config getForceUse(AudioSystem::force_use usage) = 0;
    virtual audio_io_handle_t getOutput(AudioSystem::stream_type stream,
                                        uint32_t samplingRate = 0,
                                        uint32_t format = AudioSystem::FORMAT_DEFAULT,
                                        uint32_t channels = 0,
                                        AudioSystem::output_flags flags = AudioSystem::OUTPUT_FLAG_INDIRECT) = 0;
    virtual status_t startOutput(audio_io_handle_t output, AudioSystem::stream_type stream) = 0;
    virtual status_t stopOutput(audio_io_handle_t output, AudioSystem::stream_type stream) = 0;
    virtual void releaseOutput(audio_io_handle_t output) = 0;
    virtual audio_io_handle_t getInput(int inputSource,
                                    uint32_t samplingRate = 0,
                                    uint32_t format = AudioSystem::FORMAT_DEFAULT,
                                    uint32_t channels = 0,
                                    AudioSystem::audio_in_acoustics acoustics = (AudioSystem::audio_in_acoustics)0) = 0;
    virtual status_t startInput(audio_io_handle_t input) = 0;
    virtual status_t stopInput(audio_io_handle_t input) = 0;
    virtual void releaseInput(audio_io_handle_t input) = 0;
    virtual status_t initStreamVolume(AudioSystem::stream_type stream,
                                      int indexMin,
                                      int indexMax) = 0;
    virtual status_t setStreamVolumeIndex(AudioSystem::stream_type stream, int index) = 0;
    virtual status_t getStreamVolumeIndex(AudioSystem::stream_type stream, int *index) = 0;
};


// ----------------------------------------------------------------------------

class BnAudioPolicyService : public BnInterface<IAudioPolicyService>
{
public:
    virtual status_t    onTransact( uint32_t code,
                                    const Parcel& data,
                                    Parcel* reply,
                                    uint32_t flags = 0);
};

// ----------------------------------------------------------------------------

}; // namespace android

#endif // ANDROID_IAUDIOPOLICYSERVICE_H
