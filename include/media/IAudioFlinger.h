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

#ifndef ANDROID_IAUDIOFLINGER_H
#define ANDROID_IAUDIOFLINGER_H

#include <stdint.h>
#include <sys/types.h>
#include <unistd.h>

#include <utils/RefBase.h>
#include <utils/Errors.h>
#include <utils/IInterface.h>
#include <media/IAudioTrack.h>
#include <media/IAudioRecord.h>
#include <media/IAudioFlingerClient.h>


namespace android {

// ----------------------------------------------------------------------------

class IAudioFlinger : public IInterface
{
public:
    DECLARE_META_INTERFACE(AudioFlinger);

    /* create an audio track and registers it with AudioFlinger.
     * return null if the track cannot be created.
     */
    virtual sp<IAudioTrack> createTrack(
                                pid_t pid,
                                int streamType,
                                uint32_t sampleRate,
                                int format,
                                int channelCount,
                                int frameCount,
                                uint32_t flags,
                                const sp<IMemory>& sharedBuffer,
                                status_t *status) = 0;

    virtual sp<IAudioRecord> openRecord(
                                pid_t pid,
                                int streamType,
                                uint32_t sampleRate,
                                int format,
                                int channelCount,
                                int frameCount,
                                uint32_t flags,
                                status_t *status) = 0;

    /* query the audio hardware state. This state never changes,
     * and therefore can be cached.
     */
    virtual     uint32_t    sampleRate(int output) const = 0;
    virtual     int         channelCount(int output) const = 0;
    virtual     int         format(int output) const = 0;
    virtual     size_t      frameCount(int output) const = 0;
    virtual     uint32_t    latency(int output) const = 0;

    /* set/get the audio hardware state. This will probably be used by
     * the preference panel, mostly.
     */
    virtual     status_t    setMasterVolume(float value) = 0;
    virtual     status_t    setMasterMute(bool muted) = 0;

    virtual     float       masterVolume() const = 0;
    virtual     bool        masterMute() const = 0;

    /* set/get stream type state. This will probably be used by
     * the preference panel, mostly.
     */
    virtual     status_t    setStreamVolume(int stream, float value) = 0;
    virtual     status_t    setStreamMute(int stream, bool muted) = 0;

    virtual     float       streamVolume(int stream) const = 0;
    virtual     bool        streamMute(int stream) const = 0;

    // set/get audio routing
    virtual     status_t    setRouting(int mode, uint32_t routes, uint32_t mask) = 0;
    virtual     uint32_t    getRouting(int mode) const = 0;

    // set/get audio mode
    virtual     status_t    setMode(int mode) = 0;
    virtual     int         getMode() const = 0;

    // mic mute/state
    virtual     status_t    setMicMute(bool state) = 0;
    virtual     bool        getMicMute() const = 0;

    // is a music stream active?
    virtual     bool        isMusicActive() const = 0;

    // pass a generic configuration parameter to libaudio
    // Temporary interface, do not use
    // TODO: Replace with a more generic key:value get/set mechanism
    virtual     status_t  setParameter(const char* key, const char* value) = 0;
    
    // register a current process for audio output change notifications
    virtual void registerClient(const sp<IAudioFlingerClient>& client) = 0;
    
    // retrieve the audio recording buffer size
    virtual size_t getInputBufferSize(uint32_t sampleRate, int format, int channelCount) = 0;
    
    // force AudioFlinger thread out of standby
    virtual     void        wakeUp() = 0;

    // is A2DP output enabled
    virtual     bool        isA2dpEnabled() const = 0;
};


// ----------------------------------------------------------------------------

class BnAudioFlinger : public BnInterface<IAudioFlinger>
{
public:
    virtual status_t    onTransact( uint32_t code,
                                    const Parcel& data,
                                    Parcel* reply,
                                    uint32_t flags = 0);
};

// ----------------------------------------------------------------------------

}; // namespace android

#endif // ANDROID_IAUDIOFLINGER_H
