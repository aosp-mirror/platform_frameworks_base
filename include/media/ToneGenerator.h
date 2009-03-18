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
#include <utils/Vector.h>
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
        TONE_SUP_DIAL,  // Dial tone: 425Hz, continuous
        TONE_SUP_BUSY,  // Busy tone: 425Hz, 500ms ON, 500ms OFF...
        TONE_SUP_CONGESTION,  // Congestion tone: 425Hz, 200ms ON, 200ms OFF...
        TONE_SUP_RADIO_ACK,  // Radio path acknowlegment: 425Hz, 200ms ON
        TONE_SUP_RADIO_NOTAVAIL,  // Radio path not available: 425Hz, 200ms ON, 200 OFF 3 bursts
        TONE_SUP_ERROR,  // Error/Special info:  950Hz+1400Hz+1800Hz, 330ms ON, 1s OFF...
        TONE_SUP_CALL_WAITING,  // Call Waiting:  425Hz, 200ms ON, 600ms OFF, 200ms ON, 3s OFF...
        TONE_SUP_RINGTONE,  // Ring Tone:  425Hz, 1s ON, 4s OFF...
        // Proprietary tones:  3GPP TS 31.111
        TONE_PROP_BEEP,  // General beep: 400Hz+1200Hz, 35ms ON
        TONE_PROP_ACK,  // Positive Acknowlgement: 1200Hz, 100ms ON, 100ms OFF 2 bursts
        TONE_PROP_NACK,  // Negative Acknowlgement: 300Hz+400Hz+500Hz, 400ms ON 
        TONE_PROP_PROMPT,  // Prompt tone: 400Hz+1200Hz, 200ms ON
        TONE_PROP_BEEP2,  // General double beep: 400Hz+1200Hz, 35ms ON, 200ms OFF, 35ms on
        NUM_TONES
    };

    ToneGenerator(int streamType, float volume);
    ~ToneGenerator();

    bool startTone(int toneType);
    void stopTone();

    bool isInited() { return (mState == TONE_IDLE)?false:true;}

private:

    enum tone_state {
        TONE_IDLE,  // ToneGenerator is being initialized or initialization failed
        TONE_INIT,  // ToneGenerator has been successfully initialized and is not playing
        TONE_STARTING,  // ToneGenerator is starting playing
        TONE_PLAYING,  // ToneGenerator is playing
        TONE_STOPPING,  // ToneGenerator is stoping
        TONE_RESTARTING  //
    };

    static const unsigned int TONEGEN_MAX_WAVES = 3;
    static const unsigned int TONEGEN_MAX_SEGMENTS = 4;  // Maximun number of elenemts in
    static const unsigned int TONEGEN_INF = 0xFFFFFFFF;  // Represents infinite time duration
    static const float TONEGEN_GAIN = 0.9;  // Default gain passed to  WaveGenerator().

    // ToneDescriptor class contains all parameters needed to generate a tone:
    //    - The array waveFreq[] contains the frequencies of all individual waves making the multi-tone.
    //        The number of sine waves varies from 1 to TONEGEN_MAX_WAVES.
    //        The first null value indicates that no more waves are needed.
    //    - The array segments[] is used to generate the tone pulses. A segment is a period of time
    //        during which the tone is ON or OFF.    Segments with even index (starting from 0)
    //        correspond to tone ON state and segments with odd index to OFF state.
    //        The data stored in segments[] is the duration of the corresponding period in ms.
    //        The first segment encountered with a 0 duration    indicates that no more segment follows.
    //    - repeatCnt indicates the number of times the sequence described by segments[] array must be repeated.
    //        When the tone generator    encounters the first 0 duration segment, it will compare repeatCnt to mCurCount.
    //        If mCurCount > repeatCnt, the tone is stopped automatically.

    class ToneDescriptor {
    public:
        unsigned short waveFreq[TONEGEN_MAX_WAVES+1];
        unsigned long segments[TONEGEN_MAX_SEGMENTS+1];
        unsigned long repeatCnt;
    };

    static const ToneDescriptor toneDescriptors[NUM_TONES];

    unsigned int mTotalSmp;  // Total number of audio samples played (gives current time)
    unsigned int mNextSegSmp;  // Position of next segment transition expressed in samples
    // NOTE: because mTotalSmp, mNextSegSmp are stored on 32 bit, current design will operate properly
    // only if tone duration is less than about 27 Hours(@44100Hz sampling rate). If this time is exceeded,
    // no crash will occur but tone sequence will show a glitch.

    unsigned short mCurSegment;  // Current segment index in ToneDescriptor segments[]
    unsigned short mCurCount;  // Current sequence repeat count
    volatile unsigned short mState;  // ToneGenerator state (tone_state)
    const ToneDescriptor *mpToneDesc;  // pointer to active tone descriptor
    const ToneDescriptor *mpNewToneDesc;  // pointer to next active tone descriptor

    int mSamplingRate;  // AudioFlinger Sampling rate
    AudioTrack *mpAudioTrack;  // Pointer to audio track used for playback
    Mutex mLock;  // Mutex to control concurent access to ToneGenerator object from audio callback and application API
    Mutex mCbkCondLock; // Mutex associated to mWaitCbkCond
    Condition mWaitCbkCond; // condition enabling interface to wait for audio callback completion after a change is requested
    float mVolume;  // Volume applied to audio track
    int mStreamType; // Audio stream used for output
    unsigned int mProcessSize;  // Size of audio blocks generated at a time by audioCallback() (in PCM frames).

    bool initAudioTrack();
    static void audioCallback(int event, void* user, void *info);
    bool prepareWave();
    unsigned int numWaves();
    void clearWaveGens();

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

    Vector<WaveGenerator *> mWaveGens;  // list of active wave generators.
};

}
;  // namespace android

#endif /*ANDROID_TONEGENERATOR_H_*/
