/* //device/servers/AudioFlinger/AudioBiquadFilter.cpp
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

#include <string.h>
#include <assert.h>
#include <cutils/compiler.h>

#include "AudioBiquadFilter.h"

namespace android {

const audio_coef_t AudioBiquadFilter::IDENTITY_COEFS[AudioBiquadFilter::NUM_COEFS] = { AUDIO_COEF_ONE, 0, 0, 0, 0 };

AudioBiquadFilter::AudioBiquadFilter(int nChannels, int sampleRate) {
    configure(nChannels, sampleRate);
    reset();
}

void AudioBiquadFilter::configure(int nChannels, int sampleRate) {
    assert(nChannels > 0 && nChannels <= MAX_CHANNELS);
    assert(sampleRate > 0);
    mNumChannels  = nChannels;
    mMaxDelta = static_cast<int64_t>(MAX_DELTA_PER_SEC)
                * AUDIO_COEF_ONE
                / sampleRate;
    clear();
}

void AudioBiquadFilter::reset() {
    memcpy(mCoefs, IDENTITY_COEFS, sizeof(mCoefs));
    mCoefDirtyBits = 0;
    setState(STATE_BYPASS);
}

void AudioBiquadFilter::clear() {
    memset(mDelays, 0, sizeof(mDelays));
}

void AudioBiquadFilter::setCoefs(const audio_coef_t coefs[NUM_COEFS], bool immediate) {
    memcpy(mTargetCoefs, coefs, sizeof(mTargetCoefs));
    if (mState & STATE_ENABLED_MASK) {
        if (CC_UNLIKELY(immediate)) {
            memcpy(mCoefs, coefs, sizeof(mCoefs));
            setState(STATE_NORMAL);
        } else {
            setState(STATE_TRANSITION_TO_NORMAL);
        }
    }
}

void AudioBiquadFilter::process(const audio_sample_t in[], audio_sample_t out[],
                                int frameCount) {
    (this->*mCurProcessFunc)(in, out, frameCount);
}

void AudioBiquadFilter::enable(bool immediate) {
    if (CC_UNLIKELY(immediate)) {
        memcpy(mCoefs, mTargetCoefs, sizeof(mCoefs));
        setState(STATE_NORMAL);
    } else {
        setState(STATE_TRANSITION_TO_NORMAL);
    }
}

void AudioBiquadFilter::disable(bool immediate) {
    if (CC_UNLIKELY(immediate)) {
        memcpy(mCoefs, IDENTITY_COEFS, sizeof(mCoefs));
        setState(STATE_BYPASS);
    } else {
        setState(STATE_TRANSITION_TO_BYPASS);
    }
}

void AudioBiquadFilter::setState(state_t state) {
    switch (state) {
    case STATE_BYPASS:
      mCurProcessFunc = &AudioBiquadFilter::process_bypass;
      break;
    case STATE_TRANSITION_TO_BYPASS:
      if (mNumChannels == 1) {
        mCurProcessFunc = &AudioBiquadFilter::process_transition_bypass_mono;
      } else {
        mCurProcessFunc = &AudioBiquadFilter::process_transition_bypass_multi;
      }
      mCoefDirtyBits = (1 << NUM_COEFS) - 1;
      break;
    case STATE_TRANSITION_TO_NORMAL:
      if (mNumChannels == 1) {
        mCurProcessFunc = &AudioBiquadFilter::process_transition_normal_mono;
      } else {
        mCurProcessFunc = &AudioBiquadFilter::process_transition_normal_multi;
      }
      mCoefDirtyBits = (1 << NUM_COEFS) - 1;
      break;
    case STATE_NORMAL:
      if (mNumChannels == 1) {
        mCurProcessFunc = &AudioBiquadFilter::process_normal_mono;
      } else {
        mCurProcessFunc = &AudioBiquadFilter::process_normal_multi;
      }
      break;
    }
    mState = state;
}

bool AudioBiquadFilter::updateCoefs(const audio_coef_t coefs[NUM_COEFS],
                                    int frameCount) {
    int64_t maxDelta = mMaxDelta * frameCount;
    for (int i = 0; i < NUM_COEFS; ++i) {
        if (mCoefDirtyBits & (1<<i)) {
            audio_coef_t diff = coefs[i] - mCoefs[i];
            if (diff > maxDelta) {
                mCoefs[i] += maxDelta;
            } else if (diff < -maxDelta) {
                mCoefs[i] -= maxDelta;
            } else {
                mCoefs[i] = coefs[i];
                mCoefDirtyBits ^= (1<<i);
            }
        }
    }
    return mCoefDirtyBits == 0;
}

void AudioBiquadFilter::process_bypass(const audio_sample_t * in,
                                       audio_sample_t * out,
                                       int frameCount) {
    // The common case is in-place processing, because this is what the EQ does.
    if (CC_UNLIKELY(in != out)) {
        memcpy(out, in, frameCount * mNumChannels * sizeof(audio_sample_t));
    }
}

void AudioBiquadFilter::process_normal_mono(const audio_sample_t * in,
                                            audio_sample_t * out,
                                            int frameCount) {
    size_t nFrames = frameCount;
    audio_sample_t x1 = mDelays[0][0];
    audio_sample_t x2 = mDelays[0][1];
    audio_sample_t y1 = mDelays[0][2];
    audio_sample_t y2 = mDelays[0][3];
    const audio_coef_t b0 = mCoefs[0];
    const audio_coef_t b1 = mCoefs[1];
    const audio_coef_t b2 = mCoefs[2];
    const audio_coef_t a1 = mCoefs[3];
    const audio_coef_t a2 = mCoefs[4];
    while (nFrames-- > 0) {
        audio_sample_t x0 = *(in++);
        audio_coef_sample_acc_t acc;
        acc = mul_coef_sample(b0, x0);
        acc = mac_coef_sample(b1, x1, acc);
        acc = mac_coef_sample(b2, x2, acc);
        acc = mac_coef_sample(a1, y1, acc);
        acc = mac_coef_sample(a2, y2, acc);
        audio_sample_t y0 = coef_sample_acc_to_sample(acc);
        y2 = y1;
        y1 = y0;
        x2 = x1;
        x1 = x0;
        (*out++) = y0;
    }
    mDelays[0][0] = x1;
    mDelays[0][1] = x2;
    mDelays[0][2] = y1;
    mDelays[0][3] = y2;
}

void AudioBiquadFilter::process_transition_normal_mono(const audio_sample_t * in,
                                                       audio_sample_t * out,
                                                       int frameCount) {
    if (updateCoefs(mTargetCoefs, frameCount)) {
        setState(STATE_NORMAL);
    }
    process_normal_mono(in, out, frameCount);
}

void AudioBiquadFilter::process_transition_bypass_mono(const audio_sample_t * in,
                                                       audio_sample_t * out,
                                                       int frameCount)  {
  if (updateCoefs(IDENTITY_COEFS, frameCount)) {
      setState(STATE_NORMAL);
  }
  process_normal_mono(in, out, frameCount);
}

void AudioBiquadFilter::process_normal_multi(const audio_sample_t * in,
                                             audio_sample_t * out,
                                             int frameCount) {
    const audio_coef_t b0 = mCoefs[0];
    const audio_coef_t b1 = mCoefs[1];
    const audio_coef_t b2 = mCoefs[2];
    const audio_coef_t a1 = mCoefs[3];
    const audio_coef_t a2 = mCoefs[4];
    for (int ch = 0; ch < mNumChannels; ++ch) {
        size_t nFrames = frameCount;
        audio_sample_t x1 = mDelays[ch][0];
        audio_sample_t x2 = mDelays[ch][1];
        audio_sample_t y1 = mDelays[ch][2];
        audio_sample_t y2 = mDelays[ch][3];
        while (nFrames-- > 0) {
            audio_sample_t x0 = *in;
            audio_coef_sample_acc_t acc;
            acc = mul_coef_sample(b0, x0);
            acc = mac_coef_sample(b1, x1, acc);
            acc = mac_coef_sample(b2, x2, acc);
            acc = mac_coef_sample(a1, y1, acc);
            acc = mac_coef_sample(a2, y2, acc);
            audio_sample_t y0 = coef_sample_acc_to_sample(acc);
            y2 = y1;
            y1 = y0;
            x2 = x1;
            x1 = x0;
            *out = y0;
            in += mNumChannels;
            out += mNumChannels;
        }
        mDelays[ch][0] = x1;
        mDelays[ch][1] = x2;
        mDelays[ch][2] = y1;
        mDelays[ch][3] = y2;
        in -= frameCount * mNumChannels - 1;
        out -= frameCount * mNumChannels - 1;
    }
}

void AudioBiquadFilter::process_transition_normal_multi(const audio_sample_t * in,
                                                        audio_sample_t * out,
                                                        int frameCount) {
    if (updateCoefs(mTargetCoefs, frameCount)) {
        setState(STATE_NORMAL);
    }
    process_normal_multi(in, out, frameCount);
}

void AudioBiquadFilter::process_transition_bypass_multi(const audio_sample_t * in,
                                                        audio_sample_t * out,
                                                        int frameCount)  {
    if (updateCoefs(IDENTITY_COEFS, frameCount)) {
        setState(STATE_NORMAL);
    }
    process_normal_multi(in, out, frameCount);
}

}
