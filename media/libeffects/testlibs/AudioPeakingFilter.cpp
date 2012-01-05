/* //device/include/server/AudioFlinger/AudioPeakingFilter.cpp
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

#include "AudioPeakingFilter.h"
#include "AudioCommon.h"
#include "EffectsMath.h"

#include <new>
#include <assert.h>
#include <cutils/compiler.h>

namespace android {
// Format of the coefficient table:
// kCoefTable[freq][gain][bw][coef]
// freq - peak frequency, in octaves below Nyquist,from -9 to -1.
// gain - gain, in millibel, starting at -9600, jumps of 1024, to 4736 millibel.
// bw   - bandwidth, starting at 1 cent, jumps of 1024, to 3073 cents.
// coef - 0: b0
//        1: b1
//        2: b2
//        3: -a1
//        4: -a2
static const size_t kInDims[3] = {9, 15, 4};
static const audio_coef_t kCoefTable[9*15*4*5] = {
#include "AudioPeakingFilterCoef.inl"
};

AudioCoefInterpolator AudioPeakingFilter::mCoefInterp(3, kInDims, 5, (const audio_coef_t*) kCoefTable);

AudioPeakingFilter::AudioPeakingFilter(int nChannels, int sampleRate)
        : mBiquad(nChannels, sampleRate) {
    configure(nChannels, sampleRate);
    reset();
}

void AudioPeakingFilter::configure(int nChannels, int sampleRate) {
    mNiquistFreq = sampleRate * 500;
    mFrequencyFactor = ((1ull) << 42) / mNiquistFreq;
    mBiquad.configure(nChannels, sampleRate);
    setFrequency(mNominalFrequency);
    commit(true);
}

void AudioPeakingFilter::reset() {
    setGain(0);
    setFrequency(0);
    setBandwidth(2400);
    commit(true);
}

void AudioPeakingFilter::setFrequency(uint32_t millihertz) {
    mNominalFrequency = millihertz;
    if (CC_UNLIKELY(millihertz > mNiquistFreq / 2)) {
        millihertz = mNiquistFreq / 2;
    }
    uint32_t normFreq = static_cast<uint32_t>(
            (static_cast<uint64_t>(millihertz) * mFrequencyFactor) >> 10);
    if (CC_LIKELY(normFreq > (1 << 23))) {
        mFrequency = (Effects_log2(normFreq) - ((32-9) << 15)) << (FREQ_PRECISION_BITS - 15);
    } else {
        mFrequency = 0;
    }
}

void AudioPeakingFilter::setGain(int32_t millibel) {
    mGain = millibel + 9600;
}

void AudioPeakingFilter::setBandwidth(uint32_t cents) {
    mBandwidth = cents - 1;
}

void AudioPeakingFilter::commit(bool immediate) {
    audio_coef_t coefs[5];
    int intCoord[3] = {
        mFrequency >> FREQ_PRECISION_BITS,
        mGain >> GAIN_PRECISION_BITS,
        mBandwidth >> BANDWIDTH_PRECISION_BITS
    };
    uint32_t fracCoord[3] = {
        mFrequency << (32 - FREQ_PRECISION_BITS),
        static_cast<uint32_t>(mGain) << (32 - GAIN_PRECISION_BITS),
        mBandwidth << (32 - BANDWIDTH_PRECISION_BITS)
    };
    mCoefInterp.getCoef(intCoord, fracCoord, coefs);
    mBiquad.setCoefs(coefs, immediate);
}

void AudioPeakingFilter::getBandRange(uint32_t & low, uint32_t & high) const {
    // Half bandwidth, in octaves, 15-bit precision
    int32_t halfBW = (((mBandwidth + 1) / 2) << 15) / 1200;

    low = static_cast<uint32_t>((static_cast<uint64_t>(mNominalFrequency) * Effects_exp2(-halfBW + (16 << 15))) >> 16);
    if (CC_UNLIKELY(halfBW >= (16 << 15))) {
        high = mNiquistFreq;
    } else {
        high = static_cast<uint32_t>((static_cast<uint64_t>(mNominalFrequency) * Effects_exp2(halfBW + (16 << 15))) >> 16);
        if (CC_UNLIKELY(high > mNiquistFreq)) {
            high = mNiquistFreq;
        }
    }
}

}

