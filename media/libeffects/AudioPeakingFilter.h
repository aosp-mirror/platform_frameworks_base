/* //device/include/server/AudioFlinger/AudioPeakingFilter.h
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

#ifndef ANDROID_AUDIO_PEAKING_FILTER_H
#define ANDROID_AUDIO_PEAKING_FILTER_H

#include "AudioBiquadFilter.h"
#include "AudioCoefInterpolator.h"

namespace android {

// A peaking audio filter, with unity skirt gain, and controllable peak
// frequency, gain and bandwidth.
// This filter is able to suppress introduce discontinuities and other artifacts
// in the output, even when changing parameters abruptly.
// Parameters can be set to any value - this class will make sure to clip them
// when they are out of supported range.
//
// Implementation notes:
// This class uses an underlying biquad filter whose parameters are determined
// using a linear interpolation from a coefficient table, using a
// AudioCoefInterpolator.
// All is left for this class to do is mapping between high-level parameters to
// fractional indices into the coefficient table.
class AudioPeakingFilter {
public:
    // Constructor. Resets the filter (see reset()).
    // nChannels  Number of input/output channels (interlaced).
    // sampleRate The input/output sample rate, in Hz.
    AudioPeakingFilter(int nChannels, int sampleRate);

    // Reconfiguration of the filter. Changes input/output format, but does not
    // alter current parameter values. Clears delay lines.
    // nChannels  Number of input/output channels (interlaced).
    // sampleRate The input/output sample rate, in Hz.
    void configure(int nChannels, int sampleRate);

    // Resets the filter parameters to the following values:
    // frequency: 0
    // gain: 0
    // bandwidth: 1200 cents.
    // It also disables the filter. Does not clear the delay lines.
    void reset();

    // Clears delay lines. Does not alter parameter values.
    void clear() { mBiquad.clear(); }

    // Sets gain value. Actual change will only take place upon commit().
    // This value will be remembered even if the filter is in disabled() state.
    // millibel Gain value in millibel (1/100 of decibel).
    void setGain(int32_t millibel);

    // Gets the gain, in millibel, as set.
    int32_t getGain() const { return mGain - 9600; }

    // Sets bandwidth value. Actual change will only take place upon commit().
    // This value will be remembered even if the filter is in disabled() state.
    // cents Bandwidth value in cents (1/1200 octave).
    void setBandwidth(uint32_t cents);

    // Gets the gain, in cents, as set.
    uint32_t getBandwidth() const { return mBandwidth + 1; }

    // Sets frequency value. Actual change will only take place upon commit().
    // This value will be remembered even if the filter is in disabled() state.
    // millihertz Frequency value in mHz.
    void setFrequency(uint32_t millihertz);

    // Gets the frequency, in mHz, as set.
    uint32_t getFrequency() const { return mNominalFrequency; }

    // Gets gain[dB]/2 points.
    // Results in mHz, and are computed based on the nominal values set, not on
    // possibly rounded or truncated actual values.
    void getBandRange(uint32_t & low, uint32_t & high) const;

    // Applies all parameter changes done to this point in time.
    // If the filter is disabled, the new parameters will take place when it is
    // enabled again. Does not introduce artifacts, unless immediate is set.
    // immediate    Whether to apply change abruptly (ignored if filter is
    // disabled).
   void commit(bool immediate = false);

    // Process a buffer of input data. The input and output should contain
    // frameCount * nChannels interlaced samples. Processing can be done
    // in-place, by passing the same buffer as both arguments.
    // in           Input buffer.
    // out          Output buffer.
    // frameCount   Number of frames to produce.
    void process(const audio_sample_t in[], audio_sample_t out[],
                 int frameCount) { mBiquad.process(in, out, frameCount); }

    // Enables the filter, so it would start processing input. Does not
    // introduce artifacts, unless immediate is set.
    // immediate    Whether to apply change abruptly.
    void enable(bool immediate = false) { mBiquad.enable(immediate); }

    // Disabled (bypasses) the filter. Does not introduce artifacts, unless
    // immediate is set.
    // immediate    Whether to apply change abruptly.
    void disable(bool immediate = false) { mBiquad.disable(immediate); }

private:
    // Precision for the mFrequency member.
    static const int FREQ_PRECISION_BITS = 26;
    // Precision for the mGain member.
    static const int GAIN_PRECISION_BITS = 10;
    // Precision for the mBandwidth member.
    static const int BANDWIDTH_PRECISION_BITS = 10;

    // Nyquist, in mHz.
    uint32_t mNiquistFreq;
    // Fractional index into the gain dimension of the coef table in
    // GAIN_PRECISION_BITS precision.
    int32_t mGain;
    // Fractional index into the bandwidth dimension of the coef table in
    // BANDWIDTH_PRECISION_BITS precision.
    uint32_t mBandwidth;
    // Fractional index into the frequency dimension of the coef table in
    // FREQ_PRECISION_BITS precision.
    uint32_t mFrequency;
    // Nominal value of frequency, as set.
    uint32_t mNominalFrequency;
    // 1/Nyquist[mHz], in 42-bit precision (very small).
    // Used for scaling the frequency.
    uint32_t mFrequencyFactor;

    // A biquad filter, used for the actual processing.
    AudioBiquadFilter mBiquad;
    // A coefficient interpolator, used for mapping the high level parameters to
    // the low-level biquad coefficients.
    static AudioCoefInterpolator mCoefInterp;
};

}

#endif // ANDROID_AUDIO_PEAKING_FILTER_H
