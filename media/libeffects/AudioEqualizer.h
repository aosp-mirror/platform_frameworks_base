/*
 * Copyright 2009, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef AUDIOEQUALIZER_H_
#define AUDIOEQUALIZER_H_

#include "AudioCommon.h"

namespace android {

class AudioShelvingFilter;
class AudioPeakingFilter;

// A parametric audio equalizer. Supports an arbitrary number of bands and
// presets.
// The EQ is composed of a low-shelf, zero or more peaking filters and a high
// shelf, where each band has frequency and gain controls, and the peaking
// filters have an additional bandwidth control.
class AudioEqualizer {
public:
    // Configuration of a single band.
    struct BandConfig {
        // Gain in millibel.
        int32_t gain;
        // Frequency in millihertz.
        uint32_t freq;
        // Bandwidth in cents (ignored on shelving filters).
        uint32_t bandwidth;
    };

    // Preset configuration.
    struct PresetConfig {
        // Human-readable name.
        const char * name;
        // An array of size nBands where each element is a configuration for the
        // corresponding band.
        const BandConfig * bandConfigs;
    };

    // This value is used when requesting current preset, and EQ is not using a
    // preset.
    static const int PRESET_CUSTOM = -1;

    // Get the required memory size for an instance of this class.
    // nBands   Number of bands required in the instance.
    static size_t GetInstanceSize(int nBands);

    // Create an instance of this class.
    // If succeeds, a respective call is expected to freeInstance(), regardless
    // of who owns the context memory.
    // pMem         A memory buffer of at least the size returned by
    //              GetInstanceSize(), where the instance context is to be
    //              stored. If NULL, it will be automatically allocated (using
    //              malloc).
    // nBands       Number of bands. Must be >= 2.
    // nChannels    Number of input/output channels (interlaced).
    // sampleRate   The input/output sample rate, in Hz.
    // presets      The presets configuration. May be NULL, but in that case the
    //              client is required not to call preset-related functions.
    //              This array is owned by the client and is not copied. It
    //              must be kept valid by the client as long as the instance is
    //              alive.
    // nPresets     Number of elements in the presets array.
    // returns      The instance if success. NULL if pMem is NULL and allocation
    //              failed.
    static AudioEqualizer * CreateInstance(void * pMem, int nBands,
                                           int nChannels,
                                           int sampleRate,
                                           const PresetConfig * presets,
                                           int nPresets);

    // Reconfiguration of the filter. Changes input/output format, but does not
    // alter current parameter values. Causes reset of the delay lines.
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
    void clear();

    // Frees the object. Will free the memory if the object owned it, i.e. if
    // a NULL pointer was passed to CreateInstance as pMem.
    void free();

    // Sets gain value. Actual change will only take place upon commit().
    // This value will be remembered even if the filter is in disabled() state.
    // band     The band to set the gain for.
    // millibel Gain value in millibel (1/100 of decibel).
    void setGain(int band, int32_t millibel);

    // Gets gain of a certain band. This is always the last value set (or
    // default value after reset).
    // band     The band to get the gain for.
    // returns  Gain value in millibel (1/100 of decibel).
    int32_t getGain(int band) const;

    // Sets cutoff frequency value. Actual change will only take place upon
    // commit().
    // This value will be remembered even if the filter is in disabled() state.
    // band       The band to set the frequency for.
    // millihertz Frequency value in mHz.
    void setFrequency(int band, uint32_t millihertz);

    // Gets frequency of a certain band. This is always the last value set (or
    // default value after reset).
    // band     The band to get the frequency for.
    // returns  Frequency value in mHz.
    uint32_t getFrequency(int band) const;

    // Sets bandwidth value. Actual change will only take place upon commit().
    // This value will be remembered even if the filter is in disabled() state.
    // If called on the first or last band, this call is ignored.
    // band  The band to set the frequency for.
    // cents Bandwidth value in cents (1/1200 octave).
    void setBandwidth(int band, uint32_t cents);

    // Gets bandwidth of a certain band. This is always the last value set (or
    // default value after reset). For the first and last bands, 0 is always
    // returned.
    // band     The band to get the bandwidth for.
    // returns  Bandwidth value in cents (1/1200 octave).
    uint32_t getBandwidth(int band) const;

    // Gets lower and upper boundaries of a band.
    // For the low shelf, the low bound is 0 and the high bound is the band
    // frequency.
    // For the high shelf, the low bound is the band frequency and the high
    // bound is Nyquist.
    // For the peaking filters, they are the gain[dB]/2 points.
    void getBandRange(int band, uint32_t & low, uint32_t & high) const;

    // Gets a human-readable name for a preset ID. Will return "Custom" if
    // PRESET_CUSTOM is passed.
    // preset       The preset ID. Must be less than number of presets.
    const char * getPresetName(int preset) const;

    // Gets the number of presets.
    int getNumPresets() const;

    // Gets the currently set preset ID.
    // Will return PRESET_CUSTOM in case the EQ parameters have been modified
    // manually since a preset was set.
    int getPreset() const;

    // Sets the current preset by ID.
    // All the band parameters will be overridden.
    // Change will not be applied until commit() is called.
    // preset       The preset ID. Must be less than number of presets.
    //              PRESET_CUSTOM is NOT a valid value here.
    void setPreset(int preset);

    // Applies all parameter changes done to this point in time.
    // If the filter is disabled, the new parameters will take place when it is
    // enabled again. Does not introduce artifacts, unless immediate is set.
    // immediate    Whether to apply change abruptly (ignored if filter is
    // disabled).
   void commit(bool immediate = false);

    // Process a buffer of input data. The input and output should contain
    // frameCount * nChannels interlaced samples. Processing can be done
    // in-place, by passing the same buffer as both arguments.
    // pIn          Input buffer.
    // pOut         Output buffer.
    // frameCount   Number of frames to produce on each call to process().
    void process(const audio_sample_t * pIn, audio_sample_t * pOut,
                 int frameCount);

    // Enables the filter, so it would start processing input. Does not
    // introduce artifacts, unless immediate is set.
    // immediate    Whether to apply change abruptly.
    void enable(bool immediate = false);

    // Disabled (bypasses) the filter. Does not introduce artifacts, unless
    // immediate is set.
    // immediate    Whether to apply change abruptly.
    void disable(bool immediate = false);

    // Returns the band with the maximum influence on a given frequency.
    // Result is unaffected by whether EQ is enabled or not, or by whether
    // changes have been committed or not.
    // targetFreq   The target frequency, in millihertz.
    int getMostRelevantBand(uint32_t targetFreq) const;

private:
    // Bottom frequency, in mHz.
    static const int kMinFreq = 20000;
    // Sample rate, in Hz.
    int mSampleRate;
    // Number of peaking filters. Total number of bands is +2.
    int mNumPeaking;
    // Preset configurations.
    const PresetConfig * mpPresets;
    // Number of elements in mpPresets;
    int mNumPresets;
    // Current preset.
    int mCurPreset;

    // Memory space to free when instance is deleted, or NULL if no memory is
    // owned.
    void * mpMem;
    // The low-shelving filter.
    AudioShelvingFilter * mpLowShelf;
    // The high-shelving filter.
    AudioShelvingFilter * mpHighShelf;
    // An array of size mNumPeaking of peaking filters.
    AudioPeakingFilter * mpPeakingFilters;

    // Constructor. Resets the filter (see reset()). Must call init() doing
    // anything else.
    // pMem       Memory buffer for bands.
    // nChannels  Number of input/output channels (interlaced).
    // sampleRate The input/output sample rate, in Hz.
    // ownMem     Whether pMem is owned by me.
    // presets      The presets configuration. May be NULL, but in that case the
    //              client is required not to call preset-related functions.
    //              This array is owned by the client and is not copied. It
    //              must be kept valid by the client as long as the instance is
    //              alive.
    // nPresets     Number of elements in the presets array.
    AudioEqualizer(void * pMem, int nBands, int nChannels, int sampleRate,
                   bool ownMem, const PresetConfig * presets, int nPresets);
};

}

#endif // AUDIOEQUALIZER_H_
