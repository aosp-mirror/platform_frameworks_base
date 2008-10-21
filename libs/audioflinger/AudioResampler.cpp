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

#include <stdint.h>
#include <stdlib.h>
#include <sys/types.h>
#include <cutils/log.h>
#include <cutils/properties.h>

#include "AudioResampler.h"
#include "AudioResamplerSinc.h"
#include "AudioResamplerCubic.h"

namespace android {
// ----------------------------------------------------------------------------

class AudioResamplerOrder1 : public AudioResampler {
public:
    AudioResamplerOrder1(int bitDepth, int inChannelCount, int32_t sampleRate) :
        AudioResampler(bitDepth, inChannelCount, sampleRate), mX0L(0), mX0R(0) {
    }
    virtual void resample(int32_t* out, size_t outFrameCount,
            AudioBufferProvider* provider);
private:
    // number of bits used in interpolation multiply - 15 bits avoids overflow
    static const int kNumInterpBits = 15;

    // bits to shift the phase fraction down to avoid overflow
    static const int kPreInterpShift = kNumPhaseBits - kNumInterpBits;

    void init() {}
    void resampleMono16(int32_t* out, size_t outFrameCount,
            AudioBufferProvider* provider);
    void resampleStereo16(int32_t* out, size_t outFrameCount,
            AudioBufferProvider* provider);
    static inline int32_t Interp(int32_t x0, int32_t x1, uint32_t f) {
        return x0 + (((x1 - x0) * (int32_t)(f >> kPreInterpShift)) >> kNumInterpBits);
    }
    static inline void Advance(size_t* index, uint32_t* frac, uint32_t inc) {
        *frac += inc;
        *index += (size_t)(*frac >> kNumPhaseBits);
        *frac &= kPhaseMask;
    }
    int mX0L;
    int mX0R;
};

// ----------------------------------------------------------------------------
AudioResampler* AudioResampler::create(int bitDepth, int inChannelCount,
        int32_t sampleRate, int quality) {

    // can only create low quality resample now
    AudioResampler* resampler;

    char value[PROPERTY_VALUE_MAX];
    if (property_get("af.resampler.quality", value, 0)) {
        quality = atoi(value);
        LOGD("forcing AudioResampler quality to %d", quality);
    }

    if (quality == DEFAULT)
        quality = LOW_QUALITY;
    
    switch (quality) {
    default:
    case LOW_QUALITY:
        resampler = new AudioResamplerOrder1(bitDepth, inChannelCount, sampleRate);
        break;
    case MED_QUALITY:
        resampler = new AudioResamplerCubic(bitDepth, inChannelCount, sampleRate);
        break;
    case HIGH_QUALITY:
        resampler = new AudioResamplerSinc(bitDepth, inChannelCount, sampleRate);
        break;
    }
    
    // initialize resampler
    resampler->init();
    return resampler;
}

AudioResampler::AudioResampler(int bitDepth, int inChannelCount,
        int32_t sampleRate) :
    mBitDepth(bitDepth), mChannelCount(inChannelCount),
            mSampleRate(sampleRate), mInSampleRate(sampleRate), mInputIndex(0),
            mPhaseFraction(0) {
    // sanity check on format
    if ((bitDepth != 16) ||(inChannelCount < 1) || (inChannelCount > 2)) {
        LOGE("Unsupported sample format, %d bits, %d channels", bitDepth,
                inChannelCount);
        // LOG_ASSERT(0);
    }
    
    // initialize common members
    mVolume[0] = mVolume[1] = 0;
    mBuffer.raw = NULL;

    // save format for quick lookup
    if (inChannelCount == 1) {
        mFormat = MONO_16_BIT;
    } else {
        mFormat = STEREO_16_BIT;
    }
}

AudioResampler::~AudioResampler() {
}

void AudioResampler::setSampleRate(int32_t inSampleRate) {
    mInSampleRate = inSampleRate;
    mPhaseIncrement = (uint32_t)((kPhaseMultiplier * inSampleRate) / mSampleRate);
}

void AudioResampler::setVolume(int16_t left, int16_t right) {
    // TODO: Implement anti-zipper filter
    mVolume[0] = left;
    mVolume[1] = right;
}

// ----------------------------------------------------------------------------

void AudioResamplerOrder1::resample(int32_t* out, size_t outFrameCount,
        AudioBufferProvider* provider) {

    // should never happen, but we overflow if it does
    // LOG_ASSERT(outFrameCount < 32767);

    // select the appropriate resampler
    switch (mChannelCount) {
    case 1:
        resampleMono16(out, outFrameCount, provider);
        break;
    case 2:
        resampleStereo16(out, outFrameCount, provider);
        break;
    }
}

void AudioResamplerOrder1::resampleStereo16(int32_t* out, size_t outFrameCount,
        AudioBufferProvider* provider) {

    int32_t vl = mVolume[0];
    int32_t vr = mVolume[1];

    size_t inputIndex = mInputIndex;
    uint32_t phaseFraction = mPhaseFraction;
    uint32_t phaseIncrement = mPhaseIncrement;
    size_t outputIndex = 0;
    size_t outputSampleCount = outFrameCount * 2;

    // LOGE("starting resample %d frames, inputIndex=%d, phaseFraction=%d, phaseIncrement=%d\n",
    //		outFrameCount, inputIndex, phaseFraction, phaseIncrement);

    while (outputIndex < outputSampleCount) {

        // buffer is empty, fetch a new one
        if (mBuffer.raw == NULL) {
            provider->getNextBuffer(&mBuffer);
            if (mBuffer.raw == NULL)
                break;
            // LOGE("New buffer fetched: %d frames\n", mBuffer.frameCount);
        }
        int16_t *in = mBuffer.i16;

        // handle boundary case
        while (inputIndex == 0) {
            // LOGE("boundary case\n");
            out[outputIndex++] += vl * Interp(mX0L, in[0], phaseFraction);
            out[outputIndex++] += vr * Interp(mX0R, in[1], phaseFraction);
            Advance(&inputIndex, &phaseFraction, phaseIncrement);
            if (outputIndex == outputSampleCount)
                break;
        }

        // process input samples
        // LOGE("general case\n");
        while (outputIndex < outputSampleCount) {
            out[outputIndex++] += vl * Interp(in[inputIndex*2-2],
                    in[inputIndex*2], phaseFraction);
            out[outputIndex++] += vr * Interp(in[inputIndex*2-1],
                    in[inputIndex*2+1], phaseFraction);
            Advance(&inputIndex, &phaseFraction, phaseIncrement);
            if (inputIndex >= mBuffer.frameCount)
                break;
        }
        // LOGE("loop done - outputIndex=%d, inputIndex=%d\n", outputIndex, inputIndex);

        // if done with buffer, save samples
        if (inputIndex >= mBuffer.frameCount) {
            inputIndex -= mBuffer.frameCount;

            // LOGE("buffer done, new input index", inputIndex);

            mX0L = mBuffer.i16[mBuffer.frameCount*2-2];
            mX0R = mBuffer.i16[mBuffer.frameCount*2-1];
            provider->releaseBuffer(&mBuffer);

            // verify that the releaseBuffer NULLS the buffer pointer 
            // LOG_ASSERT(mBuffer.raw == NULL);
        }
    }

    // LOGE("output buffer full - outputIndex=%d, inputIndex=%d\n", outputIndex, inputIndex);

    // save state
    mInputIndex = inputIndex;
    mPhaseFraction = phaseFraction;
}

void AudioResamplerOrder1::resampleMono16(int32_t* out, size_t outFrameCount,
        AudioBufferProvider* provider) {

    int32_t vl = mVolume[0];
    int32_t vr = mVolume[1];

    size_t inputIndex = mInputIndex;
    uint32_t phaseFraction = mPhaseFraction;
    uint32_t phaseIncrement = mPhaseIncrement;
    size_t outputIndex = 0;
    size_t outputSampleCount = outFrameCount * 2;

    // LOGE("starting resample %d frames, inputIndex=%d, phaseFraction=%d, phaseIncrement=%d\n",
    //      outFrameCount, inputIndex, phaseFraction, phaseIncrement);

    while (outputIndex < outputSampleCount) {

        // buffer is empty, fetch a new one
        if (mBuffer.raw == NULL) {
            provider->getNextBuffer(&mBuffer);
            if (mBuffer.raw == NULL)
                break;
            // LOGE("New buffer fetched: %d frames\n", mBuffer.frameCount);
        }
        int16_t *in = mBuffer.i16;

        // handle boundary case
        while (inputIndex == 0) {
            // LOGE("boundary case\n");
            int32_t sample = Interp(mX0L, in[0], phaseFraction);
            out[outputIndex++] += vl * sample;
            out[outputIndex++] += vr * sample;
            Advance(&inputIndex, &phaseFraction, phaseIncrement);
            if (outputIndex == outputSampleCount)
                break;
        }

        // process input samples
        // LOGE("general case\n");
        while (outputIndex < outputSampleCount) {
            int32_t sample = Interp(in[inputIndex-1], in[inputIndex],
                    phaseFraction);
            out[outputIndex++] += vl * sample;
            out[outputIndex++] += vr * sample;
            Advance(&inputIndex, &phaseFraction, phaseIncrement);
            if (inputIndex >= mBuffer.frameCount)
                break;
        }
        // LOGE("loop done - outputIndex=%d, inputIndex=%d\n", outputIndex, inputIndex);

        // if done with buffer, save samples
        if (inputIndex >= mBuffer.frameCount) {
            inputIndex -= mBuffer.frameCount;

            // LOGE("buffer done, new input index", inputIndex);

            mX0L = mBuffer.i16[mBuffer.frameCount-1];
            provider->releaseBuffer(&mBuffer);

            // verify that the releaseBuffer NULLS the buffer pointer 
            // LOG_ASSERT(mBuffer.raw == NULL);
        }
    }

    // LOGE("output buffer full - outputIndex=%d, inputIndex=%d\n", outputIndex, inputIndex);

    // save state
    mInputIndex = inputIndex;
    mPhaseFraction = phaseFraction;
}

// ----------------------------------------------------------------------------
}
; // namespace android

