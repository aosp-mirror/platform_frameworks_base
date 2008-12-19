/*
 * Copyright (C) 2008 The Android Open Source Project
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

#ifndef ANDROID_AUDIOSYSTEM_H_
#define ANDROID_AUDIOSYSTEM_H_

#include <utils/RefBase.h>
#include <utils/threads.h>
#include <media/IAudioFlinger.h>

namespace android {

typedef void (*audio_error_callback)(status_t err);

class AudioSystem
{
public:

    enum audio_format {
        DEFAULT = 0,
        PCM_16_BIT,
        PCM_8_BIT,
        INVALID_FORMAT
    };

    enum audio_mode {
        MODE_INVALID = -2,
        MODE_CURRENT = -1,
        MODE_NORMAL = 0,
        MODE_RINGTONE,
        MODE_IN_CALL,
        NUM_MODES  // not a valid entry, denotes end-of-list
    };

    enum audio_routes {
        ROUTE_EARPIECE       = (1 << 0),
        ROUTE_SPEAKER        = (1 << 1),
        ROUTE_BLUETOOTH_SCO  = (1 << 2),
        ROUTE_HEADSET        = (1 << 3),
        ROUTE_BLUETOOTH_A2DP = (1 << 4),
        ROUTE_ALL       = 0xFFFFFFFF
    };

    /* These are static methods to control the system-wide AudioFlinger
     * only privileged processes can have access to them
     */

    // routing helper functions
    static status_t speakerphone(bool state);
    static status_t isSpeakerphoneOn(bool* state);
    static status_t bluetoothSco(bool state);
    static status_t isBluetoothScoOn(bool* state);
    static status_t muteMicrophone(bool state);
    static status_t isMicrophoneMuted(bool *state);

    static status_t setMasterVolume(float value);
    static status_t setMasterMute(bool mute);
    static status_t getMasterVolume(float* volume);
    static status_t getMasterMute(bool* mute);

    static status_t setStreamVolume(int stream, float value);
    static status_t setStreamMute(int stream, bool mute);
    static status_t getStreamVolume(int stream, float* volume);
    static status_t getStreamMute(int stream, bool* mute);

    static status_t setMode(int mode);
    static status_t getMode(int* mode);

    static status_t setRouting(int mode, uint32_t routes, uint32_t mask);
    static status_t getRouting(int mode, uint32_t* routes);

    static status_t isMusicActive(bool *state);

    // Temporary interface, do not use
    // TODO: Replace with a more generic key:value get/set mechanism
    static status_t setParameter(const char* key, const char* value);
    
    static void setErrorCallback(audio_error_callback cb);

    // helper function to obtain AudioFlinger service handle
    static const sp<IAudioFlinger>& get_audio_flinger();

    static float linearToLog(int volume);
    static int logToLinear(float volume);

    static status_t getOutputSamplingRate(int* samplingRate);
    static status_t getOutputFrameCount(int* frameCount);
    static status_t getOutputLatency(uint32_t* latency);

    // ----------------------------------------------------------------------------

private:

    class DeathNotifier: public IBinder::DeathRecipient
    {
    public:
        DeathNotifier() {      
        }
        
        virtual void binderDied(const wp<IBinder>& who);
    };

    static sp<DeathNotifier> gDeathNotifier;

    friend class DeathNotifier;

    static Mutex gLock;
    static sp<IAudioFlinger> gAudioFlinger;
    static audio_error_callback gAudioErrorCallback;
    static int gOutSamplingRate;
    static int gOutFrameCount;
    static uint32_t gOutLatency;
};

};  // namespace android

#endif  /*ANDROID_AUDIOSYSTEM_H_*/
