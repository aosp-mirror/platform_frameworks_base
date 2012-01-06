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

#ifndef ANDROID_AUDIO_RESAMPLER_SINC_H
#define ANDROID_AUDIO_RESAMPLER_SINC_H

#include <stdint.h>
#include <sys/types.h>
#include <cutils/log.h>

#include "AudioResampler.h"

namespace android {

// ----------------------------------------------------------------------------

class AudioResamplerSinc : public AudioResampler {
public:
    AudioResamplerSinc(int bitDepth, int inChannelCount, int32_t sampleRate);

    ~AudioResamplerSinc();

    virtual void resample(int32_t* out, size_t outFrameCount,
            AudioBufferProvider* provider);
private:
    void init();

    template<int CHANNELS>
    void resample(int32_t* out, size_t outFrameCount,
            AudioBufferProvider* provider);

    template<int CHANNELS>
    inline void filterCoefficient(
            int32_t& l, int32_t& r, uint32_t phase, const int16_t *samples);

    template<int CHANNELS>
    inline void interpolate(
            int32_t& l, int32_t& r,
            const int32_t* coefs, int16_t lerp, const int16_t* samples);

    template<int CHANNELS>
    inline void read(int16_t*& impulse, uint32_t& phaseFraction,
            const int16_t* in, size_t inputIndex);

    int16_t *mState;
    int16_t *mImpulse;
    int16_t *mRingFull;

    const int32_t * mFirCoefs;
    static const int32_t mFirCoefsDown[];
    static const int32_t mFirCoefsUp[];

    // ----------------------------------------------------------------------------
    static const int32_t RESAMPLE_FIR_NUM_COEF       = 8;
    static const int32_t RESAMPLE_FIR_LERP_INT_BITS  = 4;

    // we have 16 coefs samples per zero-crossing
    static const int coefsBits = RESAMPLE_FIR_LERP_INT_BITS;        // 4
    static const int cShift = kNumPhaseBits - coefsBits;            // 26
    static const uint32_t cMask  = ((1<<coefsBits)-1) << cShift;    // 0xf<<26 = 3c00 0000

    // and we use 15 bits to interpolate between these samples
    // this cannot change because the mul below rely on it.
    static const int pLerpBits = 15;
    static const int pShift = kNumPhaseBits - coefsBits - pLerpBits;    // 11
    static const uint32_t pMask  = ((1<<pLerpBits)-1) << pShift;    // 0x7fff << 11

    // number of zero-crossing on each side
    static const unsigned int halfNumCoefs = RESAMPLE_FIR_NUM_COEF;
};

// ----------------------------------------------------------------------------
}; // namespace android

#endif /*ANDROID_AUDIO_RESAMPLER_SINC_H*/
