/* //device/include/server/AudioFlinger/AudioMixer.h
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

#ifndef ANDROID_AUDIO_MIXER_H
#define ANDROID_AUDIO_MIXER_H

#include <stdint.h>
#include <sys/types.h>

#include "AudioBufferProvider.h"
#include "AudioResampler.h"

namespace android {

// ----------------------------------------------------------------------------

#define LIKELY( exp )       (__builtin_expect( (exp) != 0, true  ))
#define UNLIKELY( exp )     (__builtin_expect( (exp) != 0, false ))

// ----------------------------------------------------------------------------

class AudioMixer
{
public:
                            AudioMixer(size_t frameCount, uint32_t sampleRate);

                            ~AudioMixer();

    static const uint32_t MAX_NUM_TRACKS = 32;
    static const uint32_t MAX_NUM_CHANNELS = 2;

    static const uint16_t UNITY_GAIN = 0x1000;

    enum { // names

        // track units (MAX_NUM_TRACKS units)
        TRACK0          = 0x1000,

        // 0x2000 is unused

        // setParameter targets
        TRACK           = 0x3000,
        RESAMPLE        = 0x3001,
        RAMP_VOLUME     = 0x3002, // ramp to new volume
        VOLUME          = 0x3003, // don't ramp

        // set Parameter names
        // for target TRACK
        CHANNEL_MASK    = 0x4000,
        FORMAT          = 0x4001,
        MAIN_BUFFER     = 0x4002,
        AUX_BUFFER      = 0x4003,
        // for target RESAMPLE
        SAMPLE_RATE     = 0x4100,
        RESET           = 0x4101,
        // for target RAMP_VOLUME and VOLUME (8 channels max)
        VOLUME0         = 0x4200,
        VOLUME1         = 0x4201,
        AUXLEVEL        = 0x4210,
    };


    int         getTrackName();
    void        deleteTrackName(int name);

    void        enable();
    void        disable();

    void        setActiveTrack(int track);
    void        setParameter(int target, int name, void *value);

    void        setBufferProvider(AudioBufferProvider* bufferProvider);
    void        process();

    uint32_t    trackNames() const { return mTrackNames; }

    static void ditherAndClamp(int32_t* out, int32_t const *sums, size_t c);

private:

    enum {
        NEEDS_CHANNEL_COUNT__MASK   = 0x00000003,
        NEEDS_FORMAT__MASK          = 0x000000F0,
        NEEDS_MUTE__MASK            = 0x00000100,
        NEEDS_RESAMPLE__MASK        = 0x00001000,
        NEEDS_AUX__MASK             = 0x00010000,
    };

    enum {
        NEEDS_CHANNEL_1             = 0x00000000,
        NEEDS_CHANNEL_2             = 0x00000001,

        NEEDS_FORMAT_16             = 0x00000010,

        NEEDS_MUTE_DISABLED         = 0x00000000,
        NEEDS_MUTE_ENABLED          = 0x00000100,

        NEEDS_RESAMPLE_DISABLED     = 0x00000000,
        NEEDS_RESAMPLE_ENABLED      = 0x00001000,

        NEEDS_AUX_DISABLED     = 0x00000000,
        NEEDS_AUX_ENABLED      = 0x00010000,
    };

    static inline int32_t applyVolume(int32_t in, int32_t v) {
        return in * v;
    }


    struct state_t;
    struct track_t;

    typedef void (*mix_t)(state_t* state);
    typedef void (*hook_t)(track_t* t, int32_t* output, size_t numOutFrames, int32_t* temp, int32_t* aux);
    static const int BLOCKSIZE = 16; // 4 cache lines

    struct track_t {
        uint32_t    needs;

        union {
        int16_t     volume[MAX_NUM_CHANNELS]; // [0]3.12 fixed point
        int32_t     volumeRL;
        };

        int32_t     prevVolume[MAX_NUM_CHANNELS];

        int32_t     volumeInc[MAX_NUM_CHANNELS];
        int32_t     auxLevel;
        int32_t     auxInc;
        int32_t     prevAuxLevel;

        uint16_t    frameCount;

        uint8_t     channelCount : 4;
        uint8_t     enabled      : 1;
        uint8_t     reserved0    : 3;
        uint8_t     format;
        uint32_t    channelMask;

        AudioBufferProvider*                bufferProvider;
        mutable AudioBufferProvider::Buffer buffer;

        hook_t      hook;
        void const* in;             // current location in buffer

        AudioResampler*     resampler;
        uint32_t            sampleRate;
        int32_t*           mainBuffer;
        int32_t*           auxBuffer;

        bool        setResampler(uint32_t sampleRate, uint32_t devSampleRate);
        bool        doesResample() const;
        void        resetResampler();
        void        adjustVolumeRamp(bool aux);
    };

    // pad to 32-bytes to fill cache line
    struct state_t {
        uint32_t        enabledTracks;
        uint32_t        needsChanged;
        size_t          frameCount;
        mix_t           hook;
        int32_t         *outputTemp;
        int32_t         *resampleTemp;
        int32_t         reserved[2];
        track_t         tracks[MAX_NUM_TRACKS]; __attribute__((aligned(32)));
    };

    int             mActiveTrack;
    uint32_t        mTrackNames;
    const uint32_t  mSampleRate;

    state_t         mState __attribute__((aligned(32)));

    void invalidateState(uint32_t mask);

    static void track__genericResample(track_t* t, int32_t* out, size_t numFrames, int32_t* temp, int32_t* aux);
    static void track__nop(track_t* t, int32_t* out, size_t numFrames, int32_t* temp, int32_t* aux);
    static void track__16BitsStereo(track_t* t, int32_t* out, size_t numFrames, int32_t* temp, int32_t* aux);
    static void track__16BitsMono(track_t* t, int32_t* out, size_t numFrames, int32_t* temp, int32_t* aux);
    static void volumeRampStereo(track_t* t, int32_t* out, size_t frameCount, int32_t* temp, int32_t* aux);
    static void volumeStereo(track_t* t, int32_t* out, size_t frameCount, int32_t* temp, int32_t* aux);

    static void process__validate(state_t* state);
    static void process__nop(state_t* state);
    static void process__genericNoResampling(state_t* state);
    static void process__genericResampling(state_t* state);
    static void process__OneTrack16BitsStereoNoResampling(state_t* state);
    static void process__TwoTracks16BitsStereoNoResampling(state_t* state);
};

// ----------------------------------------------------------------------------
}; // namespace android

#endif // ANDROID_AUDIO_MIXER_H
