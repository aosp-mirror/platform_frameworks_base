/*
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

class AudioMixer
{
public:
                            AudioMixer(size_t frameCount, uint32_t sampleRate,
                                       uint32_t maxNumTracks = MAX_NUM_TRACKS);

    /*virtual*/             ~AudioMixer();  // non-virtual saves a v-table, restore if sub-classed

    static const uint32_t MAX_NUM_TRACKS = 32;
    static const uint32_t MAX_NUM_CHANNELS = 2;

    static const uint16_t UNITY_GAIN = 0x1000;

    enum { // names

        // track names (MAX_NUM_TRACKS units)
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


    // For all APIs with "name": TRACK0 <= name < TRACK0 + MAX_NUM_TRACKS
    int         getTrackName();
    void        deleteTrackName(int name);

    void        enable(int name);
    void        disable(int name);

    void        setParameter(int name, int target, int param, void *value);

    void        setBufferProvider(int name, AudioBufferProvider* bufferProvider);
    void        process(int64_t pts);

    uint32_t    trackNames() const { return mTrackNames; }

    size_t      getUnreleasedFrames(int name) const;

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

    struct state_t;
    struct track_t;

    typedef void (*hook_t)(track_t* t, int32_t* output, size_t numOutFrames, int32_t* temp, int32_t* aux);
    static const int BLOCKSIZE = 16; // 4 cache lines

    struct track_t {
        uint32_t    needs;

        union {
        int16_t     volume[MAX_NUM_CHANNELS]; // [0]3.12 fixed point
        int32_t     volumeRL;
        };

        int32_t     prevVolume[MAX_NUM_CHANNELS];

        // 16-byte boundary

        int32_t     volumeInc[MAX_NUM_CHANNELS];
        int32_t     auxInc;
        int32_t     prevAuxLevel;

        // 16-byte boundary

        int16_t     auxLevel;       // 0 <= auxLevel <= MAX_GAIN_INT, but signed for mul performance
        uint16_t    frameCount;

        uint8_t     channelCount;   // 1 or 2, redundant with (needs & NEEDS_CHANNEL_COUNT__MASK)
        uint8_t     format;         // always 16
        uint16_t    enabled;        // actually bool
        uint32_t    channelMask;    // currently under-used

        AudioBufferProvider*                bufferProvider;

        // 16-byte boundary

        mutable AudioBufferProvider::Buffer buffer; // 8 bytes

        hook_t      hook;
        const void* in;             // current location in buffer

        // 16-byte boundary

        AudioResampler*     resampler;
        uint32_t            sampleRate;
        int32_t*           mainBuffer;
        int32_t*           auxBuffer;

        // 16-byte boundary

        uint64_t    localTimeFreq;

        int64_t     padding;

        // 16-byte boundary

        bool        setResampler(uint32_t sampleRate, uint32_t devSampleRate);
        bool        doesResample() const { return resampler != NULL; }
        void        resetResampler() { if (resampler != NULL) resampler->reset(); }
        void        adjustVolumeRamp(bool aux);
        size_t      getUnreleasedFrames() const { return resampler != NULL ?
                                                    resampler->getUnreleasedFrames() : 0; };
    };

    // pad to 32-bytes to fill cache line
    struct state_t {
        uint32_t        enabledTracks;
        uint32_t        needsChanged;
        size_t          frameCount;
        void            (*hook)(state_t* state, int64_t pts);   // one of process__*, never NULL
        int32_t         *outputTemp;
        int32_t         *resampleTemp;
        int32_t         reserved[2];
        // FIXME allocate dynamically to save some memory when maxNumTracks < MAX_NUM_TRACKS
        track_t         tracks[MAX_NUM_TRACKS]; __attribute__((aligned(32)));
    };

    // bitmask of allocated track names, where bit 0 corresponds to TRACK0 etc.
    uint32_t        mTrackNames;

    // bitmask of configured track names; ~0 if maxNumTracks == MAX_NUM_TRACKS,
    // but will have fewer bits set if maxNumTracks < MAX_NUM_TRACKS
    const uint32_t  mConfiguredNames;

    const uint32_t  mSampleRate;

    state_t         mState __attribute__((aligned(32)));

    void invalidateState(uint32_t mask);

    static void track__genericResample(track_t* t, int32_t* out, size_t numFrames, int32_t* temp, int32_t* aux);
    static void track__nop(track_t* t, int32_t* out, size_t numFrames, int32_t* temp, int32_t* aux);
    static void track__16BitsStereo(track_t* t, int32_t* out, size_t numFrames, int32_t* temp, int32_t* aux);
    static void track__16BitsMono(track_t* t, int32_t* out, size_t numFrames, int32_t* temp, int32_t* aux);
    static void volumeRampStereo(track_t* t, int32_t* out, size_t frameCount, int32_t* temp, int32_t* aux);
    static void volumeStereo(track_t* t, int32_t* out, size_t frameCount, int32_t* temp, int32_t* aux);

    static void process__validate(state_t* state, int64_t pts);
    static void process__nop(state_t* state, int64_t pts);
    static void process__genericNoResampling(state_t* state, int64_t pts);
    static void process__genericResampling(state_t* state, int64_t pts);
    static void process__OneTrack16BitsStereoNoResampling(state_t* state,
                                                          int64_t pts);
#if 0
    static void process__TwoTracks16BitsStereoNoResampling(state_t* state,
                                                           int64_t pts);
#endif

    static int64_t calculateOutputPTS(const track_t& t, int64_t basePTS,
                                      int outputFrameIndex);
};

// ----------------------------------------------------------------------------
}; // namespace android

#endif // ANDROID_AUDIO_MIXER_H
