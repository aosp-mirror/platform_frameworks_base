/* //device/include/server/AudioFlinger/AudioMixer.cpp
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

#define LOG_TAG "AudioMixer"
//#define LOG_NDEBUG 0

#include <assert.h>
#include <stdint.h>
#include <string.h>
#include <stdlib.h>
#include <sys/types.h>

#include <utils/Errors.h>
#include <utils/Log.h>

#include <cutils/bitops.h>

#include <system/audio.h>

#include <audio_utils/primitives.h>

#include "AudioMixer.h"

namespace android {

// ----------------------------------------------------------------------------

AudioMixer::AudioMixer(size_t frameCount, uint32_t sampleRate)
    :   mActiveTrack(0), mTrackNames(0), mSampleRate(sampleRate)
{
    // AudioMixer is not yet capable of multi-channel beyond stereo
    assert(2 == MAX_NUM_CHANNELS);
    mState.enabledTracks= 0;
    mState.needsChanged = 0;
    mState.frameCount   = frameCount;
    mState.outputTemp   = NULL;
    mState.resampleTemp = NULL;
    mState.hook         = process__nop;
    track_t* t = mState.tracks;
    for (unsigned i=0 ; i < MAX_NUM_TRACKS ; i++) {
        t->needs = 0;
        t->volume[0] = UNITY_GAIN;
        t->volume[1] = UNITY_GAIN;
        // no initialization needed
        // t->prevVolume[0]
        // t->prevVolume[1]
        t->volumeInc[0] = 0;
        t->volumeInc[1] = 0;
        t->auxLevel = 0;
        t->auxInc = 0;
        // no initialization needed
        // t->prevAuxLevel
        // t->frameCount
        t->channelCount = 2;
        t->enabled = 0;
        t->format = 16;
        t->channelMask = AUDIO_CHANNEL_OUT_STEREO;
        t->buffer.raw = 0;
        t->bufferProvider = NULL;
        t->hook = NULL;
        t->resampler = NULL;
        t->sampleRate = mSampleRate;
        t->in = NULL;
        t->mainBuffer = NULL;
        t->auxBuffer = NULL;
        t++;
    }
}

AudioMixer::~AudioMixer()
{
    track_t* t = mState.tracks;
    for (unsigned i=0 ; i < MAX_NUM_TRACKS ; i++) {
        delete t->resampler;
        t++;
    }
    delete [] mState.outputTemp;
    delete [] mState.resampleTemp;
}

int AudioMixer::getTrackName()
{
    uint32_t names = mTrackNames;
    uint32_t mask = 1;
    int n = 0;
    while (names & mask) {
        mask <<= 1;
        n++;
    }
    if (mask) {
        ALOGV("add track (%d)", n);
        mTrackNames |= mask;
        return TRACK0 + n;
    }
    return -1;
}

void AudioMixer::invalidateState(uint32_t mask)
{
    if (mask) {
        mState.needsChanged |= mask;
        mState.hook = process__validate;
    }
 }

void AudioMixer::deleteTrackName(int name)
{
    name -= TRACK0;
    assert(uint32_t(name) < MAX_NUM_TRACKS);
    ALOGV("deleteTrackName(%d)", name);
    track_t& track(mState.tracks[ name ]);
    if (track.enabled != 0) {
        track.enabled = 0;
        invalidateState(1<<name);
    }
    if (track.resampler) {
        // delete  the resampler
        delete track.resampler;
        track.resampler = NULL;
        track.sampleRate = mSampleRate;
        invalidateState(1<<name);
    }
    track.volumeInc[0] = 0;
    track.volumeInc[1] = 0;
    mTrackNames &= ~(1<<name);
}

void AudioMixer::enable()
{
    if (mState.tracks[ mActiveTrack ].enabled != 1) {
        mState.tracks[ mActiveTrack ].enabled = 1;
        ALOGV("enable(%d)", mActiveTrack);
        invalidateState(1<<mActiveTrack);
    }
}

void AudioMixer::disable()
{
    if (mState.tracks[ mActiveTrack ].enabled != 0) {
        mState.tracks[ mActiveTrack ].enabled = 0;
        ALOGV("disable(%d)", mActiveTrack);
        invalidateState(1<<mActiveTrack);
    }
}

void AudioMixer::setActiveTrack(int track)
{
    // this also catches track < TRACK0
    track -= TRACK0;
    assert(uint32_t(track) < MAX_NUM_TRACKS);
    mActiveTrack = track;
}

void AudioMixer::setParameter(int target, int name, void *value)
{
    int valueInt = (int)value;
    int32_t *valueBuf = (int32_t *)value;

    switch (target) {

    case TRACK:
        switch (name) {
        case CHANNEL_MASK: {
            uint32_t mask = (uint32_t)value;
            if (mState.tracks[ mActiveTrack ].channelMask != mask) {
                uint8_t channelCount = popcount(mask);
                assert((channelCount <= MAX_NUM_CHANNELS) && (channelCount));
                mState.tracks[ mActiveTrack ].channelMask = mask;
                mState.tracks[ mActiveTrack ].channelCount = channelCount;
                ALOGV("setParameter(TRACK, CHANNEL_MASK, %x)", mask);
                invalidateState(1<<mActiveTrack);
            }
            } break;
        case MAIN_BUFFER:
            if (mState.tracks[ mActiveTrack ].mainBuffer != valueBuf) {
                mState.tracks[ mActiveTrack ].mainBuffer = valueBuf;
                ALOGV("setParameter(TRACK, MAIN_BUFFER, %p)", valueBuf);
                invalidateState(1<<mActiveTrack);
            }
            break;
        case AUX_BUFFER:
            if (mState.tracks[ mActiveTrack ].auxBuffer != valueBuf) {
                mState.tracks[ mActiveTrack ].auxBuffer = valueBuf;
                ALOGV("setParameter(TRACK, AUX_BUFFER, %p)", valueBuf);
                invalidateState(1<<mActiveTrack);
            }
            break;
        default:
            // bad name
            assert(false);
        }
        break;

    case RESAMPLE:
        switch (name) {
        case SAMPLE_RATE: {
            assert(valueInt > 0);
            track_t& track = mState.tracks[ mActiveTrack ];
            if (track.setResampler(uint32_t(valueInt), mSampleRate)) {
                ALOGV("setParameter(RESAMPLE, SAMPLE_RATE, %u)",
                        uint32_t(valueInt));
                invalidateState(1<<mActiveTrack);
            }
            } break;
        case RESET: {
            track_t& track = mState.tracks[ mActiveTrack ];
            track.resetResampler();
            invalidateState(1<<mActiveTrack);
            } break;
        default:
            // bad name
            assert(false);
        }
        break;

    case RAMP_VOLUME:
    case VOLUME:
        switch (name) {
        case VOLUME0:
        case VOLUME1: {
            track_t& track = mState.tracks[ mActiveTrack ];
            if (track.volume[name-VOLUME0] != valueInt) {
                ALOGV("setParameter(VOLUME, VOLUME0/1: %04x)", valueInt);
                track.prevVolume[name-VOLUME0] = track.volume[name-VOLUME0] << 16;
                track.volume[name-VOLUME0] = valueInt;
                if (target == VOLUME) {
                    track.prevVolume[name-VOLUME0] = valueInt << 16;
                    track.volumeInc[name-VOLUME0] = 0;
                } else {
                    int32_t d = (valueInt<<16) - track.prevVolume[name-VOLUME0];
                    int32_t volInc = d / int32_t(mState.frameCount);
                    track.volumeInc[name-VOLUME0] = volInc;
                    if (volInc == 0) {
                        track.prevVolume[name-VOLUME0] = valueInt << 16;
                    }
                }
                invalidateState(1<<mActiveTrack);
            }
            } break;
        case AUXLEVEL: {
            track_t& track = mState.tracks[ mActiveTrack ];
            if (track.auxLevel != valueInt) {
                ALOGV("setParameter(VOLUME, AUXLEVEL: %04x)", valueInt);
                track.prevAuxLevel = track.auxLevel << 16;
                track.auxLevel = valueInt;
                if (target == VOLUME) {
                    track.prevAuxLevel = valueInt << 16;
                    track.auxInc = 0;
                } else {
                    int32_t d = (valueInt<<16) - track.prevAuxLevel;
                    int32_t volInc = d / int32_t(mState.frameCount);
                    track.auxInc = volInc;
                    if (volInc == 0) {
                        track.prevAuxLevel = valueInt << 16;
                    }
                }
                invalidateState(1<<mActiveTrack);
            }
            } break;
        default:
            // bad name
            assert(false);
        }
        break;

    default:
        // bad target
        assert(false);
    }
}

bool AudioMixer::track_t::setResampler(uint32_t value, uint32_t devSampleRate)
{
    if (value!=devSampleRate || resampler) {
        if (sampleRate != value) {
            sampleRate = value;
            if (resampler == NULL) {
                resampler = AudioResampler::create(
                        format, channelCount, devSampleRate);
            }
            return true;
        }
    }
    return false;
}

bool AudioMixer::track_t::doesResample() const
{
    return resampler != NULL;
}

void AudioMixer::track_t::resetResampler()
{
    if (resampler != NULL) {
        resampler->reset();
    }
}

inline
void AudioMixer::track_t::adjustVolumeRamp(bool aux)
{
    for (int i=0 ; i<MAX_NUM_CHANNELS ; i++) {
        if (((volumeInc[i]>0) && (((prevVolume[i]+volumeInc[i])>>16) >= volume[i])) ||
            ((volumeInc[i]<0) && (((prevVolume[i]+volumeInc[i])>>16) <= volume[i]))) {
            volumeInc[i] = 0;
            prevVolume[i] = volume[i]<<16;
        }
    }
    if (aux) {
        if (((auxInc>0) && (((prevAuxLevel+auxInc)>>16) >= auxLevel)) ||
            ((auxInc<0) && (((prevAuxLevel+auxInc)>>16) <= auxLevel))) {
            auxInc = 0;
            prevAuxLevel = auxLevel<<16;
        }
    }
}


void AudioMixer::setBufferProvider(AudioBufferProvider* buffer)
{
    mState.tracks[ mActiveTrack ].bufferProvider = buffer;
}



void AudioMixer::process()
{
    mState.hook(&mState);
}


void AudioMixer::process__validate(state_t* state)
{
    LOGW_IF(!state->needsChanged,
        "in process__validate() but nothing's invalid");

    uint32_t changed = state->needsChanged;
    state->needsChanged = 0; // clear the validation flag

    // recompute which tracks are enabled / disabled
    uint32_t enabled = 0;
    uint32_t disabled = 0;
    while (changed) {
        const int i = 31 - __builtin_clz(changed);
        const uint32_t mask = 1<<i;
        changed &= ~mask;
        track_t& t = state->tracks[i];
        (t.enabled ? enabled : disabled) |= mask;
    }
    state->enabledTracks &= ~disabled;
    state->enabledTracks |=  enabled;

    // compute everything we need...
    int countActiveTracks = 0;
    int all16BitsStereoNoResample = 1;
    int resampling = 0;
    int volumeRamp = 0;
    uint32_t en = state->enabledTracks;
    while (en) {
        const int i = 31 - __builtin_clz(en);
        en &= ~(1<<i);

        countActiveTracks++;
        track_t& t = state->tracks[i];
        uint32_t n = 0;
        n |= NEEDS_CHANNEL_1 + t.channelCount - 1;
        n |= NEEDS_FORMAT_16;
        n |= t.doesResample() ? NEEDS_RESAMPLE_ENABLED : NEEDS_RESAMPLE_DISABLED;
        if (t.auxLevel != 0 && t.auxBuffer != NULL) {
            n |= NEEDS_AUX_ENABLED;
        }

        if (t.volumeInc[0]|t.volumeInc[1]) {
            volumeRamp = 1;
        } else if (!t.doesResample() && t.volumeRL == 0) {
            n |= NEEDS_MUTE_ENABLED;
        }
        t.needs = n;

        if ((n & NEEDS_MUTE__MASK) == NEEDS_MUTE_ENABLED) {
            t.hook = track__nop;
        } else {
            if ((n & NEEDS_AUX__MASK) == NEEDS_AUX_ENABLED) {
                all16BitsStereoNoResample = 0;
            }
            if ((n & NEEDS_RESAMPLE__MASK) == NEEDS_RESAMPLE_ENABLED) {
                all16BitsStereoNoResample = 0;
                resampling = 1;
                t.hook = track__genericResample;
            } else {
                if ((n & NEEDS_CHANNEL_COUNT__MASK) == NEEDS_CHANNEL_1){
                    t.hook = track__16BitsMono;
                    all16BitsStereoNoResample = 0;
                }
                if ((n & NEEDS_CHANNEL_COUNT__MASK) == NEEDS_CHANNEL_2){
                    t.hook = track__16BitsStereo;
                }
            }
        }
    }

    // select the processing hooks
    state->hook = process__nop;
    if (countActiveTracks) {
        if (resampling) {
            if (!state->outputTemp) {
                state->outputTemp = new int32_t[MAX_NUM_CHANNELS * state->frameCount];
            }
            if (!state->resampleTemp) {
                state->resampleTemp = new int32_t[MAX_NUM_CHANNELS * state->frameCount];
            }
            state->hook = process__genericResampling;
        } else {
            if (state->outputTemp) {
                delete [] state->outputTemp;
                state->outputTemp = NULL;
            }
            if (state->resampleTemp) {
                delete [] state->resampleTemp;
                state->resampleTemp = NULL;
            }
            state->hook = process__genericNoResampling;
            if (all16BitsStereoNoResample && !volumeRamp) {
                if (countActiveTracks == 1) {
                    state->hook = process__OneTrack16BitsStereoNoResampling;
                }
            }
        }
    }

    ALOGV("mixer configuration change: %d activeTracks (%08x) "
        "all16BitsStereoNoResample=%d, resampling=%d, volumeRamp=%d",
        countActiveTracks, state->enabledTracks,
        all16BitsStereoNoResample, resampling, volumeRamp);

    state->hook(state);

    // Now that the volume ramp has been done, set optimal state and
    // track hooks for subsequent mixer process
    if (countActiveTracks) {
        int allMuted = 1;
        uint32_t en = state->enabledTracks;
        while (en) {
            const int i = 31 - __builtin_clz(en);
            en &= ~(1<<i);
            track_t& t = state->tracks[i];
            if (!t.doesResample() && t.volumeRL == 0)
            {
                t.needs |= NEEDS_MUTE_ENABLED;
                t.hook = track__nop;
            } else {
                allMuted = 0;
            }
        }
        if (allMuted) {
            state->hook = process__nop;
        } else if (all16BitsStereoNoResample) {
            if (countActiveTracks == 1) {
                state->hook = process__OneTrack16BitsStereoNoResampling;
            }
        }
    }
}


void AudioMixer::track__genericResample(track_t* t, int32_t* out, size_t outFrameCount, int32_t* temp, int32_t* aux)
{
    t->resampler->setSampleRate(t->sampleRate);

    // ramp gain - resample to temp buffer and scale/mix in 2nd step
    if (aux != NULL) {
        // always resample with unity gain when sending to auxiliary buffer to be able
        // to apply send level after resampling
        // TODO: modify each resampler to support aux channel?
        t->resampler->setVolume(UNITY_GAIN, UNITY_GAIN);
        memset(temp, 0, outFrameCount * MAX_NUM_CHANNELS * sizeof(int32_t));
        t->resampler->resample(temp, outFrameCount, t->bufferProvider);
        if UNLIKELY(t->volumeInc[0]|t->volumeInc[1]|t->auxInc) {
            volumeRampStereo(t, out, outFrameCount, temp, aux);
        } else {
            volumeStereo(t, out, outFrameCount, temp, aux);
        }
    } else {
        if UNLIKELY(t->volumeInc[0]|t->volumeInc[1]) {
            t->resampler->setVolume(UNITY_GAIN, UNITY_GAIN);
            memset(temp, 0, outFrameCount * MAX_NUM_CHANNELS * sizeof(int32_t));
            t->resampler->resample(temp, outFrameCount, t->bufferProvider);
            volumeRampStereo(t, out, outFrameCount, temp, aux);
        }

        // constant gain
        else {
            t->resampler->setVolume(t->volume[0], t->volume[1]);
            t->resampler->resample(out, outFrameCount, t->bufferProvider);
        }
    }
}

void AudioMixer::track__nop(track_t* t, int32_t* out, size_t outFrameCount, int32_t* temp, int32_t* aux)
{
}

void AudioMixer::volumeRampStereo(track_t* t, int32_t* out, size_t frameCount, int32_t* temp, int32_t* aux)
{
    int32_t vl = t->prevVolume[0];
    int32_t vr = t->prevVolume[1];
    const int32_t vlInc = t->volumeInc[0];
    const int32_t vrInc = t->volumeInc[1];

    //LOGD("[0] %p: inc=%f, v0=%f, v1=%d, final=%f, count=%d",
    //        t, vlInc/65536.0f, vl/65536.0f, t->volume[0],
    //       (vl + vlInc*frameCount)/65536.0f, frameCount);

    // ramp volume
    if UNLIKELY(aux != NULL) {
        int32_t va = t->prevAuxLevel;
        const int32_t vaInc = t->auxInc;
        int32_t l;
        int32_t r;

        do {
            l = (*temp++ >> 12);
            r = (*temp++ >> 12);
            *out++ += (vl >> 16) * l;
            *out++ += (vr >> 16) * r;
            *aux++ += (va >> 17) * (l + r);
            vl += vlInc;
            vr += vrInc;
            va += vaInc;
        } while (--frameCount);
        t->prevAuxLevel = va;
    } else {
        do {
            *out++ += (vl >> 16) * (*temp++ >> 12);
            *out++ += (vr >> 16) * (*temp++ >> 12);
            vl += vlInc;
            vr += vrInc;
        } while (--frameCount);
    }
    t->prevVolume[0] = vl;
    t->prevVolume[1] = vr;
    t->adjustVolumeRamp((aux != NULL));
}

void AudioMixer::volumeStereo(track_t* t, int32_t* out, size_t frameCount, int32_t* temp, int32_t* aux)
{
    const int16_t vl = t->volume[0];
    const int16_t vr = t->volume[1];

    if UNLIKELY(aux != NULL) {
        const int16_t va = (int16_t)t->auxLevel;
        do {
            int16_t l = (int16_t)(*temp++ >> 12);
            int16_t r = (int16_t)(*temp++ >> 12);
            out[0] = mulAdd(l, vl, out[0]);
            int16_t a = (int16_t)(((int32_t)l + r) >> 1);
            out[1] = mulAdd(r, vr, out[1]);
            out += 2;
            aux[0] = mulAdd(a, va, aux[0]);
            aux++;
        } while (--frameCount);
    } else {
        do {
            int16_t l = (int16_t)(*temp++ >> 12);
            int16_t r = (int16_t)(*temp++ >> 12);
            out[0] = mulAdd(l, vl, out[0]);
            out[1] = mulAdd(r, vr, out[1]);
            out += 2;
        } while (--frameCount);
    }
}

void AudioMixer::track__16BitsStereo(track_t* t, int32_t* out, size_t frameCount, int32_t* temp, int32_t* aux)
{
    int16_t const *in = static_cast<int16_t const *>(t->in);

    if UNLIKELY(aux != NULL) {
        int32_t l;
        int32_t r;
        // ramp gain
        if UNLIKELY(t->volumeInc[0]|t->volumeInc[1]|t->auxInc) {
            int32_t vl = t->prevVolume[0];
            int32_t vr = t->prevVolume[1];
            int32_t va = t->prevAuxLevel;
            const int32_t vlInc = t->volumeInc[0];
            const int32_t vrInc = t->volumeInc[1];
            const int32_t vaInc = t->auxInc;
            // LOGD("[1] %p: inc=%f, v0=%f, v1=%d, final=%f, count=%d",
            //        t, vlInc/65536.0f, vl/65536.0f, t->volume[0],
            //        (vl + vlInc*frameCount)/65536.0f, frameCount);

            do {
                l = (int32_t)*in++;
                r = (int32_t)*in++;
                *out++ += (vl >> 16) * l;
                *out++ += (vr >> 16) * r;
                *aux++ += (va >> 17) * (l + r);
                vl += vlInc;
                vr += vrInc;
                va += vaInc;
            } while (--frameCount);

            t->prevVolume[0] = vl;
            t->prevVolume[1] = vr;
            t->prevAuxLevel = va;
            t->adjustVolumeRamp(true);
        }

        // constant gain
        else {
            const uint32_t vrl = t->volumeRL;
            const int16_t va = (int16_t)t->auxLevel;
            do {
                uint32_t rl = *reinterpret_cast<uint32_t const *>(in);
                int16_t a = (int16_t)(((int32_t)in[0] + in[1]) >> 1);
                in += 2;
                out[0] = mulAddRL(1, rl, vrl, out[0]);
                out[1] = mulAddRL(0, rl, vrl, out[1]);
                out += 2;
                aux[0] = mulAdd(a, va, aux[0]);
                aux++;
            } while (--frameCount);
        }
    } else {
        // ramp gain
        if UNLIKELY(t->volumeInc[0]|t->volumeInc[1]) {
            int32_t vl = t->prevVolume[0];
            int32_t vr = t->prevVolume[1];
            const int32_t vlInc = t->volumeInc[0];
            const int32_t vrInc = t->volumeInc[1];

            // LOGD("[1] %p: inc=%f, v0=%f, v1=%d, final=%f, count=%d",
            //        t, vlInc/65536.0f, vl/65536.0f, t->volume[0],
            //        (vl + vlInc*frameCount)/65536.0f, frameCount);

            do {
                *out++ += (vl >> 16) * (int32_t) *in++;
                *out++ += (vr >> 16) * (int32_t) *in++;
                vl += vlInc;
                vr += vrInc;
            } while (--frameCount);

            t->prevVolume[0] = vl;
            t->prevVolume[1] = vr;
            t->adjustVolumeRamp(false);
        }

        // constant gain
        else {
            const uint32_t vrl = t->volumeRL;
            do {
                uint32_t rl = *reinterpret_cast<uint32_t const *>(in);
                in += 2;
                out[0] = mulAddRL(1, rl, vrl, out[0]);
                out[1] = mulAddRL(0, rl, vrl, out[1]);
                out += 2;
            } while (--frameCount);
        }
    }
    t->in = in;
}

void AudioMixer::track__16BitsMono(track_t* t, int32_t* out, size_t frameCount, int32_t* temp, int32_t* aux)
{
    int16_t const *in = static_cast<int16_t const *>(t->in);

    if UNLIKELY(aux != NULL) {
        // ramp gain
        if UNLIKELY(t->volumeInc[0]|t->volumeInc[1]|t->auxInc) {
            int32_t vl = t->prevVolume[0];
            int32_t vr = t->prevVolume[1];
            int32_t va = t->prevAuxLevel;
            const int32_t vlInc = t->volumeInc[0];
            const int32_t vrInc = t->volumeInc[1];
            const int32_t vaInc = t->auxInc;

            // LOGD("[2] %p: inc=%f, v0=%f, v1=%d, final=%f, count=%d",
            //         t, vlInc/65536.0f, vl/65536.0f, t->volume[0],
            //         (vl + vlInc*frameCount)/65536.0f, frameCount);

            do {
                int32_t l = *in++;
                *out++ += (vl >> 16) * l;
                *out++ += (vr >> 16) * l;
                *aux++ += (va >> 16) * l;
                vl += vlInc;
                vr += vrInc;
                va += vaInc;
            } while (--frameCount);

            t->prevVolume[0] = vl;
            t->prevVolume[1] = vr;
            t->prevAuxLevel = va;
            t->adjustVolumeRamp(true);
        }
        // constant gain
        else {
            const int16_t vl = t->volume[0];
            const int16_t vr = t->volume[1];
            const int16_t va = (int16_t)t->auxLevel;
            do {
                int16_t l = *in++;
                out[0] = mulAdd(l, vl, out[0]);
                out[1] = mulAdd(l, vr, out[1]);
                out += 2;
                aux[0] = mulAdd(l, va, aux[0]);
                aux++;
            } while (--frameCount);
        }
    } else {
        // ramp gain
        if UNLIKELY(t->volumeInc[0]|t->volumeInc[1]) {
            int32_t vl = t->prevVolume[0];
            int32_t vr = t->prevVolume[1];
            const int32_t vlInc = t->volumeInc[0];
            const int32_t vrInc = t->volumeInc[1];

            // LOGD("[2] %p: inc=%f, v0=%f, v1=%d, final=%f, count=%d",
            //         t, vlInc/65536.0f, vl/65536.0f, t->volume[0],
            //         (vl + vlInc*frameCount)/65536.0f, frameCount);

            do {
                int32_t l = *in++;
                *out++ += (vl >> 16) * l;
                *out++ += (vr >> 16) * l;
                vl += vlInc;
                vr += vrInc;
            } while (--frameCount);

            t->prevVolume[0] = vl;
            t->prevVolume[1] = vr;
            t->adjustVolumeRamp(false);
        }
        // constant gain
        else {
            const int16_t vl = t->volume[0];
            const int16_t vr = t->volume[1];
            do {
                int16_t l = *in++;
                out[0] = mulAdd(l, vl, out[0]);
                out[1] = mulAdd(l, vr, out[1]);
                out += 2;
            } while (--frameCount);
        }
    }
    t->in = in;
}

// no-op case
void AudioMixer::process__nop(state_t* state)
{
    uint32_t e0 = state->enabledTracks;
    size_t bufSize = state->frameCount * sizeof(int16_t) * MAX_NUM_CHANNELS;
    while (e0) {
        // process by group of tracks with same output buffer to
        // avoid multiple memset() on same buffer
        uint32_t e1 = e0, e2 = e0;
        int i = 31 - __builtin_clz(e1);
        track_t& t1 = state->tracks[i];
        e2 &= ~(1<<i);
        while (e2) {
            i = 31 - __builtin_clz(e2);
            e2 &= ~(1<<i);
            track_t& t2 = state->tracks[i];
            if UNLIKELY(t2.mainBuffer != t1.mainBuffer) {
                e1 &= ~(1<<i);
            }
        }
        e0 &= ~(e1);

        memset(t1.mainBuffer, 0, bufSize);

        while (e1) {
            i = 31 - __builtin_clz(e1);
            e1 &= ~(1<<i);
            t1 = state->tracks[i];
            size_t outFrames = state->frameCount;
            while (outFrames) {
                t1.buffer.frameCount = outFrames;
                t1.bufferProvider->getNextBuffer(&t1.buffer);
                if (!t1.buffer.raw) break;
                outFrames -= t1.buffer.frameCount;
                t1.bufferProvider->releaseBuffer(&t1.buffer);
            }
        }
    }
}

// generic code without resampling
void AudioMixer::process__genericNoResampling(state_t* state)
{
    int32_t outTemp[BLOCKSIZE * MAX_NUM_CHANNELS] __attribute__((aligned(32)));

    // acquire each track's buffer
    uint32_t enabledTracks = state->enabledTracks;
    uint32_t e0 = enabledTracks;
    while (e0) {
        const int i = 31 - __builtin_clz(e0);
        e0 &= ~(1<<i);
        track_t& t = state->tracks[i];
        t.buffer.frameCount = state->frameCount;
        t.bufferProvider->getNextBuffer(&t.buffer);
        t.frameCount = t.buffer.frameCount;
        t.in = t.buffer.raw;
        // t.in == NULL can happen if the track was flushed just after having
        // been enabled for mixing.
        if (t.in == NULL)
            enabledTracks &= ~(1<<i);
    }

    e0 = enabledTracks;
    while (e0) {
        // process by group of tracks with same output buffer to
        // optimize cache use
        uint32_t e1 = e0, e2 = e0;
        int j = 31 - __builtin_clz(e1);
        track_t& t1 = state->tracks[j];
        e2 &= ~(1<<j);
        while (e2) {
            j = 31 - __builtin_clz(e2);
            e2 &= ~(1<<j);
            track_t& t2 = state->tracks[j];
            if UNLIKELY(t2.mainBuffer != t1.mainBuffer) {
                e1 &= ~(1<<j);
            }
        }
        e0 &= ~(e1);
        // this assumes output 16 bits stereo, no resampling
        int32_t *out = t1.mainBuffer;
        size_t numFrames = 0;
        do {
            memset(outTemp, 0, sizeof(outTemp));
            e2 = e1;
            while (e2) {
                const int i = 31 - __builtin_clz(e2);
                e2 &= ~(1<<i);
                track_t& t = state->tracks[i];
                size_t outFrames = BLOCKSIZE;
                int32_t *aux = NULL;
                if UNLIKELY((t.needs & NEEDS_AUX__MASK) == NEEDS_AUX_ENABLED) {
                    aux = t.auxBuffer + numFrames;
                }
                while (outFrames) {
                    size_t inFrames = (t.frameCount > outFrames)?outFrames:t.frameCount;
                    if (inFrames) {
                        (t.hook)(&t, outTemp + (BLOCKSIZE-outFrames)*MAX_NUM_CHANNELS, inFrames, state->resampleTemp, aux);
                        t.frameCount -= inFrames;
                        outFrames -= inFrames;
                        if UNLIKELY(aux != NULL) {
                            aux += inFrames;
                        }
                    }
                    if (t.frameCount == 0 && outFrames) {
                        t.bufferProvider->releaseBuffer(&t.buffer);
                        t.buffer.frameCount = (state->frameCount - numFrames) - (BLOCKSIZE - outFrames);
                        t.bufferProvider->getNextBuffer(&t.buffer);
                        t.in = t.buffer.raw;
                        if (t.in == NULL) {
                            enabledTracks &= ~(1<<i);
                            e1 &= ~(1<<i);
                            break;
                        }
                        t.frameCount = t.buffer.frameCount;
                    }
                }
            }
            ditherAndClamp(out, outTemp, BLOCKSIZE);
            out += BLOCKSIZE;
            numFrames += BLOCKSIZE;
        } while (numFrames < state->frameCount);
    }

    // release each track's buffer
    e0 = enabledTracks;
    while (e0) {
        const int i = 31 - __builtin_clz(e0);
        e0 &= ~(1<<i);
        track_t& t = state->tracks[i];
        t.bufferProvider->releaseBuffer(&t.buffer);
    }
}


// generic code with resampling
void AudioMixer::process__genericResampling(state_t* state)
{
    int32_t* const outTemp = state->outputTemp;
    const size_t size = sizeof(int32_t) * MAX_NUM_CHANNELS * state->frameCount;

    size_t numFrames = state->frameCount;

    uint32_t e0 = state->enabledTracks;
    while (e0) {
        // process by group of tracks with same output buffer
        // to optimize cache use
        uint32_t e1 = e0, e2 = e0;
        int j = 31 - __builtin_clz(e1);
        track_t& t1 = state->tracks[j];
        e2 &= ~(1<<j);
        while (e2) {
            j = 31 - __builtin_clz(e2);
            e2 &= ~(1<<j);
            track_t& t2 = state->tracks[j];
            if UNLIKELY(t2.mainBuffer != t1.mainBuffer) {
                e1 &= ~(1<<j);
            }
        }
        e0 &= ~(e1);
        int32_t *out = t1.mainBuffer;
        memset(outTemp, 0, size);
        while (e1) {
            const int i = 31 - __builtin_clz(e1);
            e1 &= ~(1<<i);
            track_t& t = state->tracks[i];
            int32_t *aux = NULL;
            if UNLIKELY((t.needs & NEEDS_AUX__MASK) == NEEDS_AUX_ENABLED) {
                aux = t.auxBuffer;
            }

            // this is a little goofy, on the resampling case we don't
            // acquire/release the buffers because it's done by
            // the resampler.
            if ((t.needs & NEEDS_RESAMPLE__MASK) == NEEDS_RESAMPLE_ENABLED) {
                (t.hook)(&t, outTemp, numFrames, state->resampleTemp, aux);
            } else {

                size_t outFrames = 0;

                while (outFrames < numFrames) {
                    t.buffer.frameCount = numFrames - outFrames;
                    t.bufferProvider->getNextBuffer(&t.buffer);
                    t.in = t.buffer.raw;
                    // t.in == NULL can happen if the track was flushed just after having
                    // been enabled for mixing.
                    if (t.in == NULL) break;

                    if UNLIKELY(aux != NULL) {
                        aux += outFrames;
                    }
                    (t.hook)(&t, outTemp + outFrames*MAX_NUM_CHANNELS, t.buffer.frameCount, state->resampleTemp, aux);
                    outFrames += t.buffer.frameCount;
                    t.bufferProvider->releaseBuffer(&t.buffer);
                }
            }
        }
        ditherAndClamp(out, outTemp, numFrames);
    }
}

// one track, 16 bits stereo without resampling is the most common case
void AudioMixer::process__OneTrack16BitsStereoNoResampling(state_t* state)
{
    const int i = 31 - __builtin_clz(state->enabledTracks);
    const track_t& t = state->tracks[i];

    AudioBufferProvider::Buffer& b(t.buffer);

    int32_t* out = t.mainBuffer;
    size_t numFrames = state->frameCount;

    const int16_t vl = t.volume[0];
    const int16_t vr = t.volume[1];
    const uint32_t vrl = t.volumeRL;
    while (numFrames) {
        b.frameCount = numFrames;
        t.bufferProvider->getNextBuffer(&b);
        int16_t const *in = b.i16;

        // in == NULL can happen if the track was flushed just after having
        // been enabled for mixing.
        if (in == NULL || ((unsigned long)in & 3)) {
            memset(out, 0, numFrames*MAX_NUM_CHANNELS*sizeof(int16_t));
            LOGE_IF(((unsigned long)in & 3), "process stereo track: input buffer alignment pb: buffer %p track %d, channels %d, needs %08x",
                    in, i, t.channelCount, t.needs);
            return;
        }
        size_t outFrames = b.frameCount;

        if (UNLIKELY(uint32_t(vl) > UNITY_GAIN || uint32_t(vr) > UNITY_GAIN)) {
            // volume is boosted, so we might need to clamp even though
            // we process only one track.
            do {
                uint32_t rl = *reinterpret_cast<uint32_t const *>(in);
                in += 2;
                int32_t l = mulRL(1, rl, vrl) >> 12;
                int32_t r = mulRL(0, rl, vrl) >> 12;
                // clamping...
                l = clamp16(l);
                r = clamp16(r);
                *out++ = (r<<16) | (l & 0xFFFF);
            } while (--outFrames);
        } else {
            do {
                uint32_t rl = *reinterpret_cast<uint32_t const *>(in);
                in += 2;
                int32_t l = mulRL(1, rl, vrl) >> 12;
                int32_t r = mulRL(0, rl, vrl) >> 12;
                *out++ = (r<<16) | (l & 0xFFFF);
            } while (--outFrames);
        }
        numFrames -= b.frameCount;
        t.bufferProvider->releaseBuffer(&b);
    }
}

#if 0
// 2 tracks is also a common case
// NEVER used in current implementation of process__validate()
// only use if the 2 tracks have the same output buffer
void AudioMixer::process__TwoTracks16BitsStereoNoResampling(state_t* state)
{
    int i;
    uint32_t en = state->enabledTracks;

    i = 31 - __builtin_clz(en);
    const track_t& t0 = state->tracks[i];
    AudioBufferProvider::Buffer& b0(t0.buffer);

    en &= ~(1<<i);
    i = 31 - __builtin_clz(en);
    const track_t& t1 = state->tracks[i];
    AudioBufferProvider::Buffer& b1(t1.buffer);

    int16_t const *in0;
    const int16_t vl0 = t0.volume[0];
    const int16_t vr0 = t0.volume[1];
    size_t frameCount0 = 0;

    int16_t const *in1;
    const int16_t vl1 = t1.volume[0];
    const int16_t vr1 = t1.volume[1];
    size_t frameCount1 = 0;

    //FIXME: only works if two tracks use same buffer
    int32_t* out = t0.mainBuffer;
    size_t numFrames = state->frameCount;
    int16_t const *buff = NULL;


    while (numFrames) {

        if (frameCount0 == 0) {
            b0.frameCount = numFrames;
            t0.bufferProvider->getNextBuffer(&b0);
            if (b0.i16 == NULL) {
                if (buff == NULL) {
                    buff = new int16_t[MAX_NUM_CHANNELS * state->frameCount];
                }
                in0 = buff;
                b0.frameCount = numFrames;
            } else {
                in0 = b0.i16;
            }
            frameCount0 = b0.frameCount;
        }
        if (frameCount1 == 0) {
            b1.frameCount = numFrames;
            t1.bufferProvider->getNextBuffer(&b1);
            if (b1.i16 == NULL) {
                if (buff == NULL) {
                    buff = new int16_t[MAX_NUM_CHANNELS * state->frameCount];
                }
                in1 = buff;
                b1.frameCount = numFrames;
            } else {
                in1 = b1.i16;
            }
            frameCount1 = b1.frameCount;
        }

        size_t outFrames = frameCount0 < frameCount1?frameCount0:frameCount1;

        numFrames -= outFrames;
        frameCount0 -= outFrames;
        frameCount1 -= outFrames;

        do {
            int32_t l0 = *in0++;
            int32_t r0 = *in0++;
            l0 = mul(l0, vl0);
            r0 = mul(r0, vr0);
            int32_t l = *in1++;
            int32_t r = *in1++;
            l = mulAdd(l, vl1, l0) >> 12;
            r = mulAdd(r, vr1, r0) >> 12;
            // clamping...
            l = clamp16(l);
            r = clamp16(r);
            *out++ = (r<<16) | (l & 0xFFFF);
        } while (--outFrames);

        if (frameCount0 == 0) {
            t0.bufferProvider->releaseBuffer(&b0);
        }
        if (frameCount1 == 0) {
            t1.bufferProvider->releaseBuffer(&b1);
        }
    }

    if (buff != NULL) {
        delete [] buff;
    }
}
#endif

// ----------------------------------------------------------------------------
}; // namespace android
