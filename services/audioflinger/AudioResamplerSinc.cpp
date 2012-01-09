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

#include <string.h>
#include "AudioResamplerSinc.h"

namespace android {
// ----------------------------------------------------------------------------


/*
 * These coeficients are computed with the "fir" utility found in
 * tools/resampler_tools
 * TODO: A good optimization would be to transpose this matrix, to take
 * better advantage of the data-cache.
 */
const int32_t AudioResamplerSinc::mFirCoefsUp[] = {
        0x7fffffff, 0x7f15d078, 0x7c5e0da6, 0x77ecd867, 0x71e2e251, 0x6a6c304a, 0x61be7269, 0x58170412, 0x4db8ab05, 0x42e92ea6, 0x37eee214, 0x2d0e3bb1, 0x22879366, 0x18951e95, 0x0f693d0d, 0x072d2621,
        0x00000000, 0xf9f66655, 0xf51a5fd7, 0xf16bbd84, 0xeee0d9ac, 0xed67a922, 0xece70de6, 0xed405897, 0xee50e505, 0xeff3be30, 0xf203370f, 0xf45a6741, 0xf6d67d53, 0xf957db66, 0xfbc2f647, 0xfe00f2b9,
        0x00000000, 0x01b37218, 0x0313a0c6, 0x041d930d, 0x04d28057, 0x053731b0, 0x05534dff, 0x05309bfd, 0x04da440d, 0x045c1aee, 0x03c1fcdd, 0x03173ef5, 0x02663ae8, 0x01b7f736, 0x0113ec79, 0x007fe6a9,
        0x00000000, 0xff96b229, 0xff44f99f, 0xff0a86be, 0xfee5f803, 0xfed518fd, 0xfed521fd, 0xfee2f4fd, 0xfefb54f8, 0xff1b159b, 0xff3f4203, 0xff6539e0, 0xff8ac502, 0xffae1ddd, 0xffcdf3f9, 0xffe96798,
        0x00000000, 0x00119de6, 0x001e6b7e, 0x0026cb7a, 0x002b4830, 0x002c83d6, 0x002b2a82, 0x0027e67a, 0x002356f9, 0x001e098e, 0x001875e4, 0x0012fbbe, 0x000de2d1, 0x00095c10, 0x00058414, 0x00026636,
        0x00000000, 0xfffe44a9, 0xfffd206d, 0xfffc7b7f, 0xfffc3c8f, 0xfffc4ac2, 0xfffc8f2b, 0xfffcf5c4, 0xfffd6df3, 0xfffdeab2, 0xfffe6275, 0xfffececf, 0xffff2c07, 0xffff788c, 0xffffb471, 0xffffe0f2,
        0x00000000, 0x000013e6, 0x00001f03, 0x00002396, 0x00002399, 0x000020b6, 0x00001c3c, 0x00001722, 0x00001216, 0x00000d81, 0x0000099c, 0x0000067c, 0x00000419, 0x0000025f, 0x00000131, 0x00000070,
        0x00000000, 0xffffffc7, 0xffffffb3, 0xffffffb3, 0xffffffbe, 0xffffffcd, 0xffffffdb, 0xffffffe7, 0xfffffff0, 0xfffffff7, 0xfffffffb, 0xfffffffe, 0xffffffff, 0x00000000, 0x00000000, 0x00000000,
        0x00000000 // this one is needed for lerping the last coefficient
};

/*
 * These coefficients are optimized for 48KHz -> 44.1KHz (stop-band at 22.050KHz)
 * It's possible to use the above coefficient for any down-sampling
 * at the expense of a slower processing loop (we can interpolate
 * these coefficient from the above by "Stretching" them in time).
 */
const int32_t AudioResamplerSinc::mFirCoefsDown[] = {
        0x7fffffff, 0x7f55e46d, 0x7d5b4c60, 0x7a1b4b98, 0x75a7fb14, 0x7019f0bd, 0x698f875a, 0x622bfd59, 0x5a167256, 0x5178cc54, 0x487e8e6c, 0x3f53aae8, 0x36235ad4, 0x2d17047b, 0x245539ab, 0x1c00d540,
        0x14383e57, 0x0d14d5ca, 0x06aa910b, 0x0107c38b, 0xfc351654, 0xf835abae, 0xf5076b45, 0xf2a37202, 0xf0fe9faa, 0xf00a3bbd, 0xefb4aa81, 0xefea2b05, 0xf0959716, 0xf1a11e83, 0xf2f6f7a0, 0xf481fff4,
        0xf62e48ce, 0xf7e98ca5, 0xf9a38b4c, 0xfb4e4bfa, 0xfcde456f, 0xfe4a6d30, 0xff8c2fdf, 0x009f5555, 0x0181d393, 0x0233940f, 0x02b62f06, 0x030ca07d, 0x033afa62, 0x03461725, 0x03334f83, 0x030835fa,
        0x02ca59cc, 0x027f12d1, 0x022b570d, 0x01d39a49, 0x017bb78f, 0x0126e414, 0x00d7aaaf, 0x008feec7, 0x0050f584, 0x001b73e3, 0xffefa063, 0xffcd46ed, 0xffb3ddcd, 0xffa29aaa, 0xff988691, 0xff949066,
        0xff959d24, 0xff9a959e, 0xffa27195, 0xffac4011, 0xffb72d2b, 0xffc28569, 0xffcdb706, 0xffd85171, 0xffe20364, 0xffea97e9, 0xfff1f2b2, 0xfff80c06, 0xfffcec92, 0x0000a955, 0x00035fd8, 0x000532cf,
        0x00064735, 0x0006c1f9, 0x0006c62d, 0x000673ba, 0x0005e68f, 0x00053630, 0x000475a3, 0x0003b397, 0x0002fac1, 0x00025257, 0x0001be9e, 0x0001417a, 0x0000dafd, 0x000089eb, 0x00004c28, 0x00001f1d,
        0x00000000, 0xffffec10, 0xffffe0be, 0xffffdbc5, 0xffffdb39, 0xffffdd8b, 0xffffe182, 0xffffe638, 0xffffeb0a, 0xffffef8f, 0xfffff38b, 0xfffff6e3, 0xfffff993, 0xfffffba6, 0xfffffd30, 0xfffffe4a,
        0xffffff09, 0xffffff85, 0xffffffd1, 0xfffffffb, 0x0000000f, 0x00000016, 0x00000015, 0x00000012, 0x0000000d, 0x00000009, 0x00000006, 0x00000003, 0x00000002, 0x00000001, 0x00000000, 0x00000000,
        0x00000000 // this one is needed for lerping the last coefficient
};

// ----------------------------------------------------------------------------

static inline
int32_t mulRL(int left, int32_t in, uint32_t vRL)
{
#if defined(__arm__) && !defined(__thumb__)
    int32_t out;
    if (left) {
        asm( "smultb %[out], %[in], %[vRL] \n"
             : [out]"=r"(out)
             : [in]"%r"(in), [vRL]"r"(vRL)
             : );
    } else {
        asm( "smultt %[out], %[in], %[vRL] \n"
             : [out]"=r"(out)
             : [in]"%r"(in), [vRL]"r"(vRL)
             : );
    }
    return out;
#else
    if (left) {
        return int16_t(in>>16) * int16_t(vRL&0xFFFF);
    } else {
        return int16_t(in>>16) * int16_t(vRL>>16);
    }
#endif
}

static inline
int32_t mulAdd(int16_t in, int32_t v, int32_t a)
{
#if defined(__arm__) && !defined(__thumb__)
    int32_t out;
    asm( "smlawb %[out], %[v], %[in], %[a] \n"
         : [out]"=r"(out)
         : [in]"%r"(in), [v]"r"(v), [a]"r"(a)
         : );
    return out;
#else
    return a + in * (v>>16);
    // improved precision
    // return a + in * (v>>16) + ((in * (v & 0xffff)) >> 16);
#endif
}

static inline
int32_t mulAddRL(int left, uint32_t inRL, int32_t v, int32_t a)
{
#if defined(__arm__) && !defined(__thumb__)
    int32_t out;
    if (left) {
        asm( "smlawb %[out], %[v], %[inRL], %[a] \n"
             : [out]"=r"(out)
             : [inRL]"%r"(inRL), [v]"r"(v), [a]"r"(a)
             : );
    } else {
        asm( "smlawt %[out], %[v], %[inRL], %[a] \n"
             : [out]"=r"(out)
             : [inRL]"%r"(inRL), [v]"r"(v), [a]"r"(a)
             : );
    }
    return out;
#else
    if (left) {
        return a + (int16_t(inRL&0xFFFF) * (v>>16));
        //improved precision
        // return a + (int16_t(inRL&0xFFFF) * (v>>16)) + ((int16_t(inRL&0xFFFF) * (v & 0xffff)) >> 16);
    } else {
        return a + (int16_t(inRL>>16) * (v>>16));
    }
#endif
}

// ----------------------------------------------------------------------------

AudioResamplerSinc::AudioResamplerSinc(int bitDepth,
        int inChannelCount, int32_t sampleRate)
    : AudioResampler(bitDepth, inChannelCount, sampleRate),
    mState(0)
{
    /*
     * Layout of the state buffer for 32 tap:
     *
     * "present" sample            beginning of 2nd buffer
     *                 v                v
     *  0              01               2              23              3
     *  0              F0               0              F0              F
     * [pppppppppppppppInnnnnnnnnnnnnnnnpppppppppppppppInnnnnnnnnnnnnnnn]
     *                 ^               ^ head
     *
     * p = past samples, convoluted with the (p)ositive side of sinc()
     * n = future samples, convoluted with the (n)egative side of sinc()
     * r = extra space for implementing the ring buffer
     *
     */

    const size_t numCoefs = 2*halfNumCoefs;
    const size_t stateSize = numCoefs * inChannelCount * 2;
    mState = new int16_t[stateSize];
    memset(mState, 0, sizeof(int16_t)*stateSize);
    mImpulse = mState + (halfNumCoefs-1)*inChannelCount;
    mRingFull = mImpulse + (numCoefs+1)*inChannelCount;
}

AudioResamplerSinc::~AudioResamplerSinc()
{
    delete [] mState;
}

void AudioResamplerSinc::init() {
}

void AudioResamplerSinc::resample(int32_t* out, size_t outFrameCount,
            AudioBufferProvider* provider)
{
    mFirCoefs = (mInSampleRate <= mSampleRate) ? mFirCoefsUp : mFirCoefsDown;

    // select the appropriate resampler
    switch (mChannelCount) {
    case 1:
        resample<1>(out, outFrameCount, provider);
        break;
    case 2:
        resample<2>(out, outFrameCount, provider);
        break;
    }
}


template<int CHANNELS>
void AudioResamplerSinc::resample(int32_t* out, size_t outFrameCount,
        AudioBufferProvider* provider)
{
    int16_t* impulse = mImpulse;
    uint32_t vRL = mVolumeRL;
    size_t inputIndex = mInputIndex;
    uint32_t phaseFraction = mPhaseFraction;
    uint32_t phaseIncrement = mPhaseIncrement;
    size_t outputIndex = 0;
    size_t outputSampleCount = outFrameCount * 2;
    size_t inFrameCount = (outFrameCount*mInSampleRate)/mSampleRate;

    AudioBufferProvider::Buffer& buffer(mBuffer);
    while (outputIndex < outputSampleCount) {
        // buffer is empty, fetch a new one
        while (buffer.frameCount == 0) {
            buffer.frameCount = inFrameCount;
            provider->getNextBuffer(&buffer);
            if (buffer.raw == NULL) {
                goto resample_exit;
            }
            const uint32_t phaseIndex = phaseFraction >> kNumPhaseBits;
            if (phaseIndex == 1) {
                // read one frame
                read<CHANNELS>(impulse, phaseFraction, buffer.i16, inputIndex);
            } else if (phaseIndex == 2) {
                // read 2 frames
                read<CHANNELS>(impulse, phaseFraction, buffer.i16, inputIndex);
                inputIndex++;
                if (inputIndex >= mBuffer.frameCount) {
                    inputIndex -= mBuffer.frameCount;
                    provider->releaseBuffer(&buffer);
                } else {
                    read<CHANNELS>(impulse, phaseFraction, buffer.i16, inputIndex);
                }
           }
        }
        int16_t *in = buffer.i16;
        const size_t frameCount = buffer.frameCount;

        // Always read-in the first samples from the input buffer
        int16_t* head = impulse + halfNumCoefs*CHANNELS;
        head[0] = in[inputIndex*CHANNELS + 0];
        if (CHANNELS == 2)
            head[1] = in[inputIndex*CHANNELS + 1];

        // handle boundary case
        int32_t l, r;
        while (outputIndex < outputSampleCount) {
            filterCoefficient<CHANNELS>(l, r, phaseFraction, impulse);
            out[outputIndex++] += 2 * mulRL(1, l, vRL);
            out[outputIndex++] += 2 * mulRL(0, r, vRL);

            phaseFraction += phaseIncrement;
            const uint32_t phaseIndex = phaseFraction >> kNumPhaseBits;
            if (phaseIndex == 1) {
                inputIndex++;
                if (inputIndex >= frameCount)
                    break;  // need a new buffer
                read<CHANNELS>(impulse, phaseFraction, in, inputIndex);
            } else if(phaseIndex == 2) {    // maximum value
                inputIndex++;
                if (inputIndex >= frameCount)
                    break;  // 0 frame available, 2 frames needed
                // read first frame
                read<CHANNELS>(impulse, phaseFraction, in, inputIndex);
                inputIndex++;
                if (inputIndex >= frameCount)
                    break;  // 0 frame available, 1 frame needed
                // read second frame
                read<CHANNELS>(impulse, phaseFraction, in, inputIndex);
            }
        }

        // if done with buffer, save samples
        if (inputIndex >= frameCount) {
            inputIndex -= frameCount;
            provider->releaseBuffer(&buffer);
        }
    }

resample_exit:
    mImpulse = impulse;
    mInputIndex = inputIndex;
    mPhaseFraction = phaseFraction;
}

template<int CHANNELS>
/***
* read()
*
* This function reads only one frame from input buffer and writes it in
* state buffer
*
**/
void AudioResamplerSinc::read(
        int16_t*& impulse, uint32_t& phaseFraction,
        const int16_t* in, size_t inputIndex)
{
    const uint32_t phaseIndex = phaseFraction >> kNumPhaseBits;
    impulse += CHANNELS;
    phaseFraction -= 1LU<<kNumPhaseBits;
    if (impulse >= mRingFull) {
        const size_t stateSize = (halfNumCoefs*2)*CHANNELS;
        memcpy(mState, mState+stateSize, sizeof(int16_t)*stateSize);
        impulse -= stateSize;
    }
    int16_t* head = impulse + halfNumCoefs*CHANNELS;
    head[0] = in[inputIndex*CHANNELS + 0];
    if (CHANNELS == 2)
        head[1] = in[inputIndex*CHANNELS + 1];
}

template<int CHANNELS>
void AudioResamplerSinc::filterCoefficient(
        int32_t& l, int32_t& r, uint32_t phase, const int16_t *samples)
{
    // compute the index of the coefficient on the positive side and
    // negative side
    uint32_t indexP = (phase & cMask) >> cShift;
    uint16_t lerpP  = (phase & pMask) >> pShift;
    uint32_t indexN = (-phase & cMask) >> cShift;
    uint16_t lerpN  = (-phase & pMask) >> pShift;
    if ((indexP == 0) && (lerpP == 0)) {
        indexN = cMask >> cShift;
        lerpN = pMask >> pShift;
    }

    l = 0;
    r = 0;
    const int32_t* coefs = mFirCoefs;
    const int16_t *sP = samples;
    const int16_t *sN = samples+CHANNELS;
    for (unsigned int i=0 ; i<halfNumCoefs/4 ; i++) {
        interpolate<CHANNELS>(l, r, coefs+indexP, lerpP, sP);
        interpolate<CHANNELS>(l, r, coefs+indexN, lerpN, sN);
        sP -= CHANNELS; sN += CHANNELS; coefs += 1<<coefsBits;
        interpolate<CHANNELS>(l, r, coefs+indexP, lerpP, sP);
        interpolate<CHANNELS>(l, r, coefs+indexN, lerpN, sN);
        sP -= CHANNELS; sN += CHANNELS; coefs += 1<<coefsBits;
        interpolate<CHANNELS>(l, r, coefs+indexP, lerpP, sP);
        interpolate<CHANNELS>(l, r, coefs+indexN, lerpN, sN);
        sP -= CHANNELS; sN += CHANNELS; coefs += 1<<coefsBits;
        interpolate<CHANNELS>(l, r, coefs+indexP, lerpP, sP);
        interpolate<CHANNELS>(l, r, coefs+indexN, lerpN, sN);
        sP -= CHANNELS; sN += CHANNELS; coefs += 1<<coefsBits;
    }
}

template<int CHANNELS>
void AudioResamplerSinc::interpolate(
        int32_t& l, int32_t& r,
        const int32_t* coefs, int16_t lerp, const int16_t* samples)
{
    int32_t c0 = coefs[0];
    int32_t c1 = coefs[1];
    int32_t sinc = mulAdd(lerp, (c1-c0)<<1, c0);
    if (CHANNELS == 2) {
        uint32_t rl = *reinterpret_cast<const uint32_t*>(samples);
        l = mulAddRL(1, rl, sinc, l);
        r = mulAddRL(0, rl, sinc, r);
    } else {
        r = l = mulAdd(samples[0], sinc, l);
    }
}

// ----------------------------------------------------------------------------
}; // namespace android

