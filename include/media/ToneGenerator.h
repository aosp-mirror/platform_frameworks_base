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

#ifndef ANDROID_TONEGENERATOR_H_
#define ANDROID_TONEGENERATOR_H_

#include <utils/RefBase.h>
#include <utils/KeyedVector.h>
#include <utils/threads.h>
#include <media/AudioSystem.h>
#include <media/AudioTrack.h>

namespace android {

class ToneGenerator {
public:

    // List of all available tones
    // This enum must be kept consistant with constants in ToneGenerator JAVA class
    enum tone_type {
        // DTMF tones  ITU-T Recommendation Q.23
        TONE_DTMF_0 = 0,  // 0 key: 1336Hz, 941Hz
        TONE_DTMF_1,  // 1 key: 1209Hz, 697Hz
        TONE_DTMF_2,  // 2 key: 1336Hz, 697Hz
        TONE_DTMF_3,  // 3 key: 1477Hz, 697Hz
        TONE_DTMF_4,  // 4 key: 1209Hz, 770Hz
        TONE_DTMF_5,  // 5 key: 1336Hz, 770Hz
        TONE_DTMF_6,  // 6 key: 1477Hz, 770Hz
        TONE_DTMF_7,  // 7 key: 1209Hz, 852Hz
        TONE_DTMF_8,  // 8 key: 1336Hz, 852Hz
        TONE_DTMF_9,  // 9 key: 1477Hz, 852Hz
        TONE_DTMF_S,  // * key: 1209Hz, 941Hz
        TONE_DTMF_P,  // # key: 1477Hz, 941Hz
        TONE_DTMF_A,  // A key: 1633Hz, 697Hz
        TONE_DTMF_B,  // B key: 1633Hz, 770Hz
        TONE_DTMF_C,  // C key: 1633Hz, 852Hz
        TONE_DTMF_D,  // D key: 1633Hz, 941Hz
        // Call supervisory tones:  3GPP TS 22.001 (CEPT)
        TONE_SUP_DIAL,  // Dial tone: CEPT: 425Hz, continuous
        FIRST_SUP_TONE = TONE_SUP_DIAL,
        TONE_SUP_BUSY,  // Busy tone, CEPT: 425Hz, 500ms ON, 500ms OFF...
        TONE_SUP_CONGESTION,  // Congestion tone CEPT, JAPAN: 425Hz, 200ms ON, 200ms OFF...
        TONE_SUP_RADIO_ACK,  // Radio path acknowlegment, CEPT, ANSI: 425Hz, 200ms ON
        TONE_SUP_RADIO_NOTAVAIL,  // Radio path not available: 425Hz, 200ms ON, 200 OFF 3 bursts
        TONE_SUP_ERROR,  // Error/Special info:  950Hz+1400Hz+1800Hz, 330ms ON, 1s OFF...
        TONE_SUP_CALL_WAITING,  // Call Waiting CEPT,JAPAN:  425Hz, 200ms ON, 600ms OFF, 200ms ON, 3s OFF...
        TONE_SUP_RINGTONE,  // Ring Tone CEPT, JAPAN:  425Hz, 1s ON, 4s OFF...
        LAST_SUP_TONE = TONE_SUP_RINGTONE,
        // Proprietary tones:  3GPP TS 31.111
        TONE_PROP_BEEP,  // General beep: 400Hz+1200Hz, 35ms ON
        TONE_PROP_ACK,  // Positive Acknowlgement: 1200Hz, 100ms ON, 100ms OFF 2 bursts
        TONE_PROP_NACK,  // Negative Acknowlgement: 300Hz+400Hz+500Hz, 400ms ON
        TONE_PROP_PROMPT,  // Prompt tone: 400Hz+1200Hz, 200ms ON
        TONE_PROP_BEEP2,  // General double beep: 400Hz+1200Hz, 35ms ON, 200ms OFF, 35ms on
        // Additional call supervisory tones: specified by IS-95 only
        TONE_SUP_INTERCEPT, // Intercept tone: alternating 440 Hz and 620 Hz tones, each on for 250 ms.
        TONE_SUP_INTERCEPT_ABBREV, // Abbreviated intercept: intercept tone limited to 4 seconds
        TONE_SUP_CONGESTION_ABBREV, // Abbreviated congestion: congestion tone limited to 4 seconds
        TONE_SUP_CONFIRM, // Confirm tone: a 350 Hz tone added to a 440 Hz tone repeated 3 times in a 100 ms on, 100 ms off cycle.
        TONE_SUP_PIP, // Pip tone: four bursts of 480 Hz tone (0.1 s on, 0.1 s off).

        // CDMA Tones
        TONE_CDMA_DIAL_TONE_LITE,
        TONE_CDMA_NETWORK_USA_RINGBACK,
        TONE_CDMA_INTERCEPT,
        TONE_CDMA_ABBR_INTERCEPT,
        TONE_CDMA_REORDER,
        TONE_CDMA_ABBR_REORDER,
        TONE_CDMA_NETWORK_BUSY,
        TONE_CDMA_CONFIRM,
        TONE_CDMA_ANSWER,
        TONE_CDMA_NETWORK_CALLWAITING,
        TONE_CDMA_PIP,

        // ISDN
        TONE_CDMA_CALL_SIGNAL_ISDN_NORMAL,  // ISDN Alert Normal
        TONE_CDMA_CALL_SIGNAL_ISDN_INTERGROUP, // ISDN Intergroup
        TONE_CDMA_CALL_SIGNAL_ISDN_SP_PRI, // ISDN SP PRI
        TONE_CDMA_CALL_SIGNAL_ISDN_PAT3,  // ISDN Alert PAT3
        TONE_CDMA_CALL_SIGNAL_ISDN_PING_RING, // ISDN Alert PING RING
        TONE_CDMA_CALL_SIGNAL_ISDN_PAT5,  // ISDN Alert PAT5
        TONE_CDMA_CALL_SIGNAL_ISDN_PAT6,  // ISDN Alert PAT6
        TONE_CDMA_CALL_SIGNAL_ISDN_PAT7,  // ISDN Alert PAT7
        // ISDN end

        // IS54
        TONE_CDMA_HIGH_L,  // IS54 High Pitch Long
        TONE_CDMA_MED_L, // IS54 Med Pitch Long
        TONE_CDMA_LOW_L, // IS54 Low Pitch Long
        TONE_CDMA_HIGH_SS, // IS54 High Pitch Short Short
        TONE_CDMA_MED_SS, // IS54 Medium Pitch Short Short
        TONE_CDMA_LOW_SS, // IS54 Low Pitch Short Short
        TONE_CDMA_HIGH_SSL, // IS54 High Pitch Short Short Long
        TONE_CDMA_MED_SSL, // IS54 Medium  Pitch Short Short Long
        TONE_CDMA_LOW_SSL, // IS54 Low  Pitch Short Short Long
        TONE_CDMA_HIGH_SS_2, // IS54 High Pitch Short Short 2
        TONE_CDMA_MED_SS_2, // IS54 Med Pitch Short Short 2
        TONE_CDMA_LOW_SS_2, // IS54 Low  Pitch Short Short 2
        TONE_CDMA_HIGH_SLS, // IS54 High Pitch Short Long Short
        TONE_CDMA_MED_SLS, // IS54 Med Pitch Short Long Short
        TONE_CDMA_LOW_SLS, // IS54 Low Pitch Short Long Short
        TONE_CDMA_HIGH_S_X4, // IS54 High Pitch Short Short Short Short
        TONE_CDMA_MED_S_X4, // IS54 Med Pitch Short Short Short Short
        TONE_CDMA_LOW_S_X4, // IS54 Low Pitch Short Short Short Short
        TONE_CDMA_HIGH_PBX_L, // PBX High Pitch Long
        TONE_CDMA_MED_PBX_L, // PBX Med Pitch Long
        TONE_CDMA_LOW_PBX_L, // PBX Low  Pitch Long
        TONE_CDMA_HIGH_PBX_SS, // PBX High Short Short
        TONE_CDMA_MED_PBX_SS, // PBX Med Short Short
        TONE_CDMA_LOW_PBX_SS, // PBX Low  Short Short
        TONE_CDMA_HIGH_PBX_SSL, // PBX High Short Short Long
        TONE_CDMA_MED_PBX_SSL, // PBX Med Short Short Long
        TONE_CDMA_LOW_PBX_SSL,  // PBX Low Short Short Long
        TONE_CDMA_HIGH_PBX_SLS, // PBX High  SLS
        TONE_CDMA_MED_PBX_SLS,  // PBX Med SLS
        TONE_CDMA_LOW_PBX_SLS, // PBX Low SLS
        TONE_CDMA_HIGH_PBX_S_X4, // PBX High SSSS
        TONE_CDMA_MED_PBX_S_X4, // PBX Med SSSS
        TONE_CDMA_LOW_PBX_S_X4, // PBX LOW SSSS
        //IS54 end
        // proprietary
        TONE_CDMA_ALERT_NETWORK_LITE,
        TONE_CDMA_ALERT_AUTOREDIAL_LITE,
        TONE_CDMA_ONE_MIN_BEEP,
        TONE_CDMA_KEYPAD_VOLUME_KEY_LITE,
        TONE_CDMA_PRESSHOLDKEY_LITE,
        TONE_CDMA_ALERT_INCALL_LITE,
        TONE_CDMA_EMERGENCY_RINGBACK,
        TONE_CDMA_ALERT_CALL_GUARD,
        TONE_CDMA_SOFT_ERROR_LITE,
        TONE_CDMA_CALLDROP_LITE,
        // proprietary end
        TONE_CDMA_NETWORK_BUSY_ONE_SHOT,
        TONE_CDMA_ABBR_ALERT,
        TONE_CDMA_SIGNAL_OFF,
        //CDMA end
        NUM_TONES,
        NUM_SUP_TONES = LAST_SUP_TONE-FIRST_SUP_TONE+1
    };

    ToneGenerator(audio_stream_type_t streamType, float volume, bool threadCanCallJava = false);
    ~ToneGenerator();

    bool startTone(int toneType, int durationMs = -1);
    void stopTone();

    bool isInited() { return (mState == TONE_IDLE)?false:true;}

private:

    enum tone_state {
        TONE_IDLE,  // ToneGenerator is being initialized or initialization failed
        TONE_INIT,  // ToneGenerator has been successfully initialized and is not playing
        TONE_STARTING,  // ToneGenerator is starting playing
        TONE_PLAYING,  // ToneGenerator is playing
        TONE_STOPPING,  // ToneGenerator is stoping
        TONE_STOPPED,  // ToneGenerator is stopped: the AudioTrack will be stopped
        TONE_RESTARTING  // A start request was received in active state (playing or stopping)
    };


    // Region specific tones.
    // These supervisory tones are different depending on the region (USA/CANADA, JAPAN, rest of the world).
    // When a tone in the range [FIRST_SUP_TONE, LAST_SUP_TONE] is requested, the region is determined
    // from system property gsm.operator.iso-country and the proper tone descriptor is selected with the
    // help of sToneMappingTable[]
    enum regional_tone_type {
        // ANSI supervisory tones
        TONE_ANSI_DIAL = NUM_TONES, // Dial tone: a continuous 350 Hz + 440 Hz tone.
        TONE_ANSI_BUSY,             // Busy tone on:  a 480 Hz + 620 Hz tone repeated in a 500 ms on, 500 ms off cycle.
        TONE_ANSI_CONGESTION,       // Network congestion (reorder) tone on:  a 480 Hz + 620 Hz tone repeated in a 250 ms on, 250 ms off cycle.
        TONE_ANSI_CALL_WAITING,     // Call waiting tone on: 440 Hz, on for 300 ms, 9,7 s off followed by
                                    // (440 Hz, on for 100 ms off for 100 ms, on for 100 ms, 9,7s off and repeated as necessary).
        TONE_ANSI_RINGTONE,         // Ring Tone:  a 440 Hz + 480 Hz tone repeated in a 2 s on, 4 s off pattern.
        // JAPAN Supervisory tones
        TONE_JAPAN_DIAL,            // Dial tone: 400Hz, continuous
        TONE_JAPAN_BUSY,            // Busy tone: 400Hz, 500ms ON, 500ms OFF...
        TONE_JAPAN_RADIO_ACK,       // Radio path acknowlegment: 400Hz, 1s ON, 2s OFF...
        NUM_ALTERNATE_TONES
    };

    enum region {
        ANSI,
        JAPAN,
        CEPT,
        NUM_REGIONS
    };

    static const unsigned char sToneMappingTable[NUM_REGIONS-1][NUM_SUP_TONES];

    static const unsigned int TONEGEN_MAX_WAVES = 3;     // Maximun number of sine waves in a tone segment
    static const unsigned int TONEGEN_MAX_SEGMENTS = 12;  // Maximun number of segments in a tone descriptor
    static const unsigned int TONEGEN_INF = 0xFFFFFFFF;  // Represents infinite time duration
    static const float TONEGEN_GAIN = 0.9;  // Default gain passed to  WaveGenerator().

    // ToneDescriptor class contains all parameters needed to generate a tone:
    //    - The array waveFreq[]:
    //         1 for static tone descriptors: contains the frequencies of all individual waves making the multi-tone.
    //         2 for active tone descritors: contains the indexes of the WaveGenerator objects in mWaveGens
    //        The number of sine waves varies from 1 to TONEGEN_MAX_WAVES.
    //        The first null value indicates that no more waves are needed.
    //    - The array segments[] is used to generate the tone pulses. A segment is a period of time
    //        during which the tone is ON or OFF.    Segments with even index (starting from 0)
    //        correspond to tone ON state and segments with odd index to OFF state.
    //        The data stored in segments[] is the duration of the corresponding period in ms.
    //        The first segment encountered with a 0 duration    indicates that no more segment follows.
    //    - loopCnt - Number of times to repeat a sequence of seqments after playing this
    //    - loopIndx - The segment index to go back and play is loopcnt > 0
    //    - repeatCnt indicates the number of times the sequence described by segments[] array must be repeated.
    //        When the tone generator encounters the first 0 duration segment, it will compare repeatCnt to mCurCount.
    //        If mCurCount > repeatCnt, the tone is stopped automatically. Otherwise, tone sequence will be
    //        restarted from segment repeatSegment.
    //    - repeatSegment number of the first repeated segment when repeatCnt is not null

    class ToneSegment {
    public:
        unsigned int duration;
        unsigned short waveFreq[TONEGEN_MAX_WAVES+1];
        unsigned short loopCnt;
        unsigned short loopIndx;
    };

    class ToneDescriptor {
    public:
        ToneSegment segments[TONEGEN_MAX_SEGMENTS+1];
        unsigned long repeatCnt;
        unsigned long repeatSegment;
    };

    static const ToneDescriptor sToneDescriptors[];

    bool mThreadCanCallJava;
    unsigned int mTotalSmp;  // Total number of audio samples played (gives current time)
    unsigned int mNextSegSmp;  // Position of next segment transition expressed in samples
    // NOTE: because mTotalSmp, mNextSegSmp are stored on 32 bit, current design will operate properly
    // only if tone duration is less than about 27 Hours(@44100Hz sampling rate). If this time is exceeded,
    // no crash will occur but tone sequence will show a glitch.
    unsigned int mMaxSmp;  // Maximum number of audio samples played (maximun tone duration)
    int mDurationMs;  // Maximum tone duration in ms

    unsigned short mCurSegment;  // Current segment index in ToneDescriptor segments[]
    unsigned short mCurCount;  // Current sequence repeat count
    volatile unsigned short mState;  // ToneGenerator state (tone_state)
    unsigned short mRegion;
    const ToneDescriptor *mpToneDesc;  // pointer to active tone descriptor
    const ToneDescriptor *mpNewToneDesc;  // pointer to next active tone descriptor

    unsigned short mLoopCounter; // Current tone loopback count

    int mSamplingRate;  // AudioFlinger Sampling rate
    AudioTrack *mpAudioTrack;  // Pointer to audio track used for playback
    Mutex mLock;  // Mutex to control concurent access to ToneGenerator object from audio callback and application API
    Mutex mCbkCondLock; // Mutex associated to mWaitCbkCond
    Condition mWaitCbkCond; // condition enabling interface to wait for audio callback completion after a change is requested
    float mVolume;  // Volume applied to audio track
    audio_stream_type_t mStreamType; // Audio stream used for output
    unsigned int mProcessSize;  // Size of audio blocks generated at a time by audioCallback() (in PCM frames).

    bool initAudioTrack();
    static void audioCallback(int event, void* user, void *info);
    bool prepareWave();
    unsigned int numWaves(unsigned int segmentIdx);
    void clearWaveGens();
    int getToneForRegion(int toneType);

    // WaveGenerator generates a single sine wave
    class WaveGenerator {
    public:
        enum gen_command {
            WAVEGEN_START,  // Start/restart wave from phase 0
            WAVEGEN_CONT,  // Continue wave from current phase
            WAVEGEN_STOP  // Stop wave on zero crossing
        };

        WaveGenerator(unsigned short samplingRate, unsigned short frequency,
                float volume);
        ~WaveGenerator();

        void getSamples(short *outBuffer, unsigned int count,
                unsigned int command);

    private:
        static const short GEN_AMP = 32000;  // amplitude of generator
        static const short S_Q14 = 14;  // shift for Q14
        static const short S_Q15 = 15;  // shift for Q15

        short mA1_Q14;  // Q14 coefficient
        // delay line of full amplitude generator
        short mS1, mS2;  // delay line S2 oldest
        short mS2_0;  // saved value for reinitialisation
        short mAmplitude_Q15;  // Q15 amplitude
    };

    KeyedVector<unsigned short, WaveGenerator *> mWaveGens;  // list of active wave generators.
};

}
;  // namespace android

#endif /*ANDROID_TONEGENERATOR_H_*/
