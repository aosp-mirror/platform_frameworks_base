/* //device/include/server/AudioFlinger/AudioBiquadFilter.h
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

#ifndef ANDROID_AUDIO_BIQUAD_FILTER_H
#define ANDROID_AUDIO_BIQUAD_FILTER_H

#include "AudioCommon.h"

namespace android {
// A biquad filter.
// Implements the recursion y[n]=a0*y[n-1]+a1*y[n-2]+b0*x[n]+b1*x[n-1]+b2*x[n-2]
// (the a0 and a1 coefficients have an opposite sign to the common convention)
// The filter works on fixed sized blocks of data (frameCount multi-channel
// samples, as defined during construction). An arbitrary number of interlaced
// channels is supported.
// Filter can operate in an enabled (active) or disabled (bypassed) states.
// A mechanism for suppression of artifacts caused by abrupt coefficient changes
// is implemented: normally, when the enable(), disable() and setCoefs() methods
// are called without the immediate flag set, the filter smoothly transitions
// from its current state to the desired state.
class AudioBiquadFilter {
public:
    // Max number of channels (can be changed here, and everything should work).
    static const int MAX_CHANNELS = 2;
    // Number of coefficients.
    static const int NUM_COEFS = 5;

    // Constructor.
    // nChannels    Number of input/output channels.
    // sampleRate   Sample rate, in Hz.
    AudioBiquadFilter(int nChannels, int sampleRate);

    // Reconfiguration of the filter. Implies clear().
    // nChannels    Number of input/output channels.
    // sampleRate   Sample rate, in Hz.
    void configure(int nChannels, int sampleRate);

    // Resets the internal state of the filter.
    // Coefficients are reset to identity, state becomes disabled. This change
    // happens immediately and might cause discontinuities in the output.
    // Delay lines are not cleared.
    void reset();

    // Clears the delay lines.
    // This change happens immediately and might cause discontinuities in the
    // output.
    void clear();

    // Sets the coefficients.
    // If called when filter is disabled, will have no immediate effect, but the
    // new coefficients will be set and used next time the filter is enabled.
    // coefs        The new coefficients.
    // immediate    If true, transitions to new coefficients smoothly, without
    //              introducing discontinuities in the output. Otherwise,
    //              transitions immediately.
    void setCoefs(const audio_coef_t coefs[NUM_COEFS], bool immediate = false);

    // Process a buffer of data. Always processes frameCount multi-channel
    // samples. Processing can be done in-place, by passing the same buffer as
    // both arguments.
    // in           The input buffer. Should be of size frameCount * nChannels.
    // out          The output buffer. Should be of size frameCount * nChannels.
    // frameCount   Number of multi-channel samples to process.
    void process(const audio_sample_t in[], audio_sample_t out[],
                 int frameCount);

    // Enables (activates) the filter.
    // immediate    If true, transitions to new state smoothly, without
    //              introducing discontinuities in the output. Otherwise,
    //              transitions immediately.
    void enable(bool immediate = false);

    // Disables (bypasses) the filter.
    // immediate    If true, transitions to new state smoothly, without
    //              introducing discontinuities in the output. Otherwise,
    //              transitions immediately.
    void disable(bool immediate = false);

private:
    // A prototype of the actual processing function. Has the same semantics as
    // the process() method.
    typedef void (AudioBiquadFilter::*process_func)(const audio_sample_t[],
                                                    audio_sample_t[],
                                                    int frameCount);

    // The maximum rate of coefficient change, measured in coefficient units per
    // second.
    static const audio_coef_t MAX_DELTA_PER_SEC = 2000;

    // Coefficients of identity transformation.
    static const audio_coef_t IDENTITY_COEFS[NUM_COEFS];

    // Filter state.
    enum state_t {
        // Bypass.
        STATE_BYPASS = 0x01,
        // In the process of smooth transition to bypass state.
        STATE_TRANSITION_TO_BYPASS = 0x02,
        // In the process of smooth transition to normal (enabled) state.
        STATE_TRANSITION_TO_NORMAL = 0x04,
        // In normal (enabled) state.
        STATE_NORMAL = 0x05,
        // A bit-mask for determining whether the filter is enabled or disabled
        // in the eyes of the client.
        STATE_ENABLED_MASK = 0x04
    };

    // Number of channels.
    int mNumChannels;
    // Current state.
    state_t mState;
    // Maximum coefficient delta per sample.
    audio_coef_t mMaxDelta;

    // A bit-mask designating for which coefficients the current value is not
    // necessarily identical to the target value (since we're in transition
    // state).
    uint32_t mCoefDirtyBits;
    // The current coefficients.
    audio_coef_t mCoefs[NUM_COEFS];
    // The target coefficients. Will not be identical to mCoefs if we are in a
    // transition state.
    audio_coef_t mTargetCoefs[NUM_COEFS];

    // The delay lines.
    audio_sample_t mDelays[MAX_CHANNELS][4];

    // Current processing function (determines according to current state and
    // number of channels).
    process_func mCurProcessFunc;

    // Sets a new state. Updates the processing function accordingly, and sets
    // the dirty bits if changing to a transition state.
    void setState(state_t state);

    // In a transition state, modifies the current coefs towards the passed
    // coefs, while keeping a smooth change rate. Whenever a coef reaches its
    // target value, the dirty bit is cleared. If all are clear, the function
    // returns true, and we can then change to our target state.
    bool updateCoefs(const audio_coef_t coefs[NUM_COEFS], int frameCount);

    // Processing function when in disabled state.
    void process_bypass(const audio_sample_t * in, audio_sample_t * out,
                        int frameCount);
    // Processing function when in normal state, mono.
    void process_normal_mono(const audio_sample_t * in, audio_sample_t * out,
                             int frameCount);
    // Processing function when transitioning to normal state, mono.
    void process_transition_normal_mono(const audio_sample_t * in,
                                        audio_sample_t * out, int frameCount);
    // Processing function when transitioning to bypass state, mono.
    void process_transition_bypass_mono(const audio_sample_t * in,
                                        audio_sample_t * out, int frameCount);
    // Processing function when in normal state, multi-channel.
    void process_normal_multi(const audio_sample_t * in, audio_sample_t * out,
                              int frameCount);
    // Processing function when transitioning to normal state, multi-channel.
    void process_transition_normal_multi(const audio_sample_t * in,
                                         audio_sample_t * out, int frameCount);
    // Processing function when transitioning to bypass state, multi-channel.
    void process_transition_bypass_multi(const audio_sample_t * in,
                                         audio_sample_t * out, int frameCount);
};
}

#endif // ANDROID_AUDIO_BIQUAD_FILTER_H
