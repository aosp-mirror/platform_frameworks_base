/* /android/src/frameworks/base/media/libeffects/AudioFormatAdapter.h
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

#ifndef AUDIOFORMATADAPTER_H_
#define AUDIOFORMATADAPTER_H_

#include <hardware/audio_effect.h>


#define min(x,y) (((x) < (y)) ? (x) : (y))

namespace android {

// An adapter for an audio processor working on audio_sample_t samples with a
// buffer override behavior to arbitrary sample formats and buffer behaviors.
// The adapter may work on any processing class which has a processing function
// with the following signature:
// void process(const audio_sample_t * pIn,
//              audio_sample_t * pOut,
//              int frameCount);
// It is assumed that the underlying processor works in S7.24 format and an
// overwrite behavior.
//
// Usage is simple: just work with the processor normally, but instead of
// calling its process() function directly, work with the process() function of
// the adapter.
// The adapter supports re-configuration to a different format on the fly.
//
// T        The processor class.
// bufSize  The maximum number of samples (single channel) to process on a
//          single call to the underlying processor. Setting this to a small
//          number will save a little memory, but will cost function call
//          overhead, resulting from multiple calls to the underlying process()
//          per a single call to this class's process().
template<class T, size_t bufSize>
class AudioFormatAdapter {
public:
    // Configure the adapter.
    // processor    The underlying audio processor.
    // nChannels    Number of input and output channels. The adapter does not do
    //              channel conversion - this parameter must be in sync with the
    //              actual processor.
    // pcmFormat    The desired input/output sample format.
    // behavior     The desired behavior (overwrite or accumulate).
    void configure(T & processor, int nChannels, uint8_t pcmFormat,
                   uint32_t behavior) {
        mpProcessor = &processor;
        mNumChannels = nChannels;
        mPcmFormat = pcmFormat;
        mBehavior = behavior;
        mMaxSamplesPerCall = bufSize / nChannels;
    }

    // Process a block of samples.
    // pIn          A buffer of samples with the format specified on
    //              configure().
    // pOut         A buffer of samples with the format specified on
    //              configure(). May be the same as pIn.
    // numSamples   The number of multi-channel samples to process.
    void process(const void * pIn, void * pOut, uint32_t numSamples) {
        while (numSamples > 0) {
            uint32_t numSamplesIter = min(numSamples, mMaxSamplesPerCall);
            uint32_t nSamplesChannels = numSamplesIter * mNumChannels;
            if (mPcmFormat == AUDIO_FORMAT_PCM_8_24_BIT) {
                if (mBehavior == EFFECT_BUFFER_ACCESS_WRITE) {
                    mpProcessor->process(
                        reinterpret_cast<const audio_sample_t *> (pIn),
                        reinterpret_cast<audio_sample_t *> (pOut),
                        numSamplesIter);
                } else if (mBehavior == EFFECT_BUFFER_ACCESS_ACCUMULATE) {
                    mpProcessor->process(
                        reinterpret_cast<const audio_sample_t *> (pIn),
                        mBuffer, numSamplesIter);
                    MixOutput(pOut, numSamplesIter);
                } else {
                    assert(false);
                }
                pIn = reinterpret_cast<const audio_sample_t *> (pIn)
                        + nSamplesChannels;
                pOut = reinterpret_cast<audio_sample_t *> (pOut)
                        + nSamplesChannels;
            } else {
                ConvertInput(pIn, nSamplesChannels);
                mpProcessor->process(mBuffer, mBuffer, numSamplesIter);
                ConvertOutput(pOut, nSamplesChannels);
            }
            numSamples -= numSamplesIter;
        }
    }

private:
    // The underlying processor.
    T * mpProcessor;
    // The number of input/output channels.
    int mNumChannels;
    // The desired PCM format.
    uint8_t mPcmFormat;
    // The desired buffer behavior.
    uint32_t mBehavior;
    // An intermediate buffer for processing.
    audio_sample_t mBuffer[bufSize];
    // The buffer size, divided by the number of channels - represents the
    // maximum number of multi-channel samples that can be stored in the
    // intermediate buffer.
    size_t mMaxSamplesPerCall;

    // Converts a buffer of input samples to audio_sample_t format.
    // Output is written to the intermediate buffer.
    // pIn          The input buffer with the format designated in configure().
    //              When function exist will point to the next unread input
    //              sample.
    // numSamples   The number of single-channel samples to process.
    void ConvertInput(const void *& pIn, uint32_t numSamples) {
        if (mPcmFormat == AUDIO_FORMAT_PCM_16_BIT) {
            const int16_t * pIn16 = reinterpret_cast<const int16_t *>(pIn);
            audio_sample_t * pOut = mBuffer;
            while (numSamples-- > 0) {
                *(pOut++) = s15_to_audio_sample_t(*(pIn16++));
            }
            pIn = pIn16;
        } else {
            assert(false);
        }
    }

    // Converts audio_sample_t samples from the intermediate buffer to the
    // output buffer, converting to the desired format and buffer behavior.
    // pOut         The buffer to write the output to.
    //              When function exist will point to the next output sample.
    // numSamples   The number of single-channel samples to process.
    void ConvertOutput(void *& pOut, uint32_t numSamples) {
        if (mPcmFormat == AUDIO_FORMAT_PCM_16_BIT) {
            const audio_sample_t * pIn = mBuffer;
            int16_t * pOut16 = reinterpret_cast<int16_t *>(pOut);
            if (mBehavior == EFFECT_BUFFER_ACCESS_WRITE) {
                while (numSamples-- > 0) {
                    *(pOut16++) = audio_sample_t_to_s15_clip(*(pIn++));
                }
            } else if (mBehavior == EFFECT_BUFFER_ACCESS_ACCUMULATE) {
                while (numSamples-- > 0) {
                    *(pOut16++) += audio_sample_t_to_s15_clip(*(pIn++));
                }
            } else {
                assert(false);
            }
            pOut = pOut16;
        } else {
            assert(false);
        }
    }

    // Accumulate data from the intermediate buffer to the output. Output is
    // assumed to be of audio_sample_t type.
    // pOut         The buffer to mix the output to.
    //              When function exist will point to the next output sample.
    // numSamples   The number of single-channel samples to process.
    void MixOutput(void *& pOut, uint32_t numSamples) {
        const audio_sample_t * pIn = mBuffer;
        audio_sample_t * pOut24 = reinterpret_cast<audio_sample_t *>(pOut);
        numSamples *= mNumChannels;
        while (numSamples-- > 0) {
            *(pOut24++) += *(pIn++);
        }
        pOut = pOut24;
    }
};

}

#endif // AUDIOFORMATADAPTER_H_
