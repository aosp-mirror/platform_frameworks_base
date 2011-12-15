/* /android/src/frameworks/base/libs/audioflinger/AudioShelvingFilter.cpp
**
** Copyright 2009, The Android Open Source Project
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

#include "AudioShelvingFilter.h"
#include "AudioCommon.h"
#include "EffectsMath.h"

#include <new>
#include <assert.h>
#include <cutils/compiler.h>

namespace android {
// Format of the coefficient tables:
// kCoefTable[freq][gain][coef]
// freq  - cutoff frequency, in octaves below Nyquist,from -10 to -6 in low
//         shelf, -2 to 0 in high shelf.
// gain  - gain, in millibel, starting at -9600, jumps of 1024, to 4736 millibel.
// coef - 0: b0
//        1: b1
//        2: b2
//        3: -a1
//        4: -a2
static const size_t kHiInDims[2] = {3, 15};
static const audio_coef_t kHiCoefTable[3*15*5] = {
#include "AudioHighShelfFilterCoef.inl"
};
static const size_t kLoInDims[2] = {5, 15};
static const audio_coef_t kLoCoefTable[5*15*5] = {
#include "AudioLowShelfFilterCoef.inl"
};

AudioCoefInterpolator AudioShelvingFilter::mHiCoefInterp(2, kHiInDims, 5, (const audio_coef_t*) kHiCoefTable);
AudioCoefInterpolator AudioShelvingFilter::mLoCoefInterp(2, kLoInDims, 5, (const audio_coef_t*) kLoCoefTable);

AudioShelvingFilter::AudioShelvingFilter(ShelfType type, int nChannels,
                                         int sampleRate)
        : mType(type),
          mBiquad(nChannels, sampleRate)  {
    configure(nChannels, sampleRate);
}

void AudioShelvingFilter::configure(int nChannels, int sampleRate) {
    mNiquistFreq = sampleRate * 500;
    mFrequencyFactor = ((1ull) << 42) / mNiquistFreq;
    mBiquad.configure(nChannels, sampleRate);
    setFrequency(mNominalFrequency);
    commit(true);
}

void AudioShelvingFilter::reset() {
    setGain(0);
    setFrequency(mType == kLowShelf ? 0 : mNiquistFreq);
    commit(true);
}

void AudioShelvingFilter::setFrequency(uint32_t millihertz) {
    mNominalFrequency = millihertz;
    if (CC_UNLIKELY(millihertz > mNiquistFreq / 2)) {
        millihertz = mNiquistFreq / 2;
    }
    uint32_t normFreq = static_cast<uint32_t>(
            (static_cast<uint64_t>(millihertz) * mFrequencyFactor) >> 10);
    uint32_t log2minFreq = (mType == kLowShelf ? (32-10) : (32-2));
    if (CC_LIKELY(normFreq > (1U << log2minFreq))) {
        mFrequency = (Effects_log2(normFreq) - (log2minFreq << 15)) << (FREQ_PRECISION_BITS - 15);
    } else {
        mFrequency = 0;
    }
}

void AudioShelvingFilter::setGain(int32_t millibel) {
    mGain = millibel + 9600;
}

void AudioShelvingFilter::commit(bool immediate) {
    audio_coef_t coefs[5];
    int intCoord[2] = {
        mFrequency >> FREQ_PRECISION_BITS,
        mGain >> GAIN_PRECISION_BITS
    };
    uint32_t fracCoord[2] = {
        mFrequency << (32 - FREQ_PRECISION_BITS),
        static_cast<uint32_t>(mGain) << (32 - GAIN_PRECISION_BITS)
    };
    if (mType == kHighShelf) {
        mHiCoefInterp.getCoef(intCoord, fracCoord, coefs);
    } else {
        mLoCoefInterp.getCoef(intCoord, fracCoord, coefs);
    }
    mBiquad.setCoefs(coefs, immediate);
}

}
