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

    enum stream_type {
        DEFAULT         =-1,
        VOICE_CALL      = 0,
        SYSTEM          = 1,
        RING            = 2,
        MUSIC           = 3,
        ALARM           = 4,
        NOTIFICATION    = 5,
        BLUETOOTH_SCO   = 6,
        NUM_STREAM_TYPES
    };

    enum audio_output_type {
        AUDIO_OUTPUT_DEFAULT      =-1,
        AUDIO_OUTPUT_HARDWARE     = 0,
        AUDIO_OUTPUT_A2DP         = 1,
        NUM_AUDIO_OUTPUT_TYPES
    };

    enum audio_format {
        FORMAT_DEFAULT = 0,
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
        ROUTE_ALL            = -1UL,
    };

    enum audio_in_acoustics {
        AGC_ENABLE    = 0x0001,
        AGC_DISABLE   = 0,
        NS_ENABLE     = 0x0002,
        NS_DISABLE    = 0,
        TX_IIR_ENABLE = 0x0004,
        TX_DISABLE    = 0
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

    static status_t getOutputSamplingRate(int* samplingRate, int stream = DEFAULT);
    static status_t getOutputFrameCount(int* frameCount, int stream = DEFAULT);
    static status_t getOutputLatency(uint32_t* latency, int stream = DEFAULT);

    static bool routedToA2dpOutput(int streamType);
    
    static status_t getInputBufferSize(uint32_t sampleRate, int format, int channelCount, 
        size_t* buffSize);

    // ----------------------------------------------------------------------------

private:

    class AudioFlingerClient: public IBinder::DeathRecipient, public BnAudioFlingerClient
    {
    public:
        AudioFlingerClient() {      
        }
        
        // DeathRecipient
        virtual void binderDied(const wp<IBinder>& who);
        
        // IAudioFlingerClient
        virtual void a2dpEnabledChanged(bool enabled);
        
    };
    static int getOutput(int streamType);

    static sp<AudioFlingerClient> gAudioFlingerClient;

    friend class AudioFlingerClient;

    static Mutex gLock;
    static sp<IAudioFlinger> gAudioFlinger;
    static audio_error_callback gAudioErrorCallback;
    static int gOutSamplingRate[NUM_AUDIO_OUTPUT_TYPES];
    static int gOutFrameCount[NUM_AUDIO_OUTPUT_TYPES];
    static uint32_t gOutLatency[NUM_AUDIO_OUTPUT_TYPES];
    static bool gA2dpEnabled;
    
    static size_t gInBuffSize;
    // previous parameters for recording buffer size queries
    static uint32_t gPrevInSamplingRate;
    static int gPrevInFormat;
    static int gPrevInChannelCount;

};

};  // namespace android

#endif  /*ANDROID_AUDIOSYSTEM_H_*/
