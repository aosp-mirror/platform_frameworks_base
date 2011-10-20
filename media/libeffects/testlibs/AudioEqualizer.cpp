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

#define LOG_TAG "AudioEqualizer"

#include <assert.h>
#include <stdlib.h>
#include <new>
#include <utils/Log.h>

#include "AudioEqualizer.h"
#include "AudioPeakingFilter.h"
#include "AudioShelvingFilter.h"
#include "EffectsMath.h"

namespace android {

size_t AudioEqualizer::GetInstanceSize(int nBands) {
    assert(nBands >= 2);
    return sizeof(AudioEqualizer) +
           sizeof(AudioShelvingFilter) * 2 +
           sizeof(AudioPeakingFilter) * (nBands - 2);
}

AudioEqualizer * AudioEqualizer::CreateInstance(void * pMem, int nBands,
                                                int nChannels, int sampleRate,
                                                const PresetConfig * presets,
                                                int nPresets) {
    ALOGV("AudioEqualizer::CreateInstance(pMem=%p, nBands=%d, nChannels=%d, "
         "sampleRate=%d, nPresets=%d)",
         pMem, nBands, nChannels, sampleRate, nPresets);
    assert(nBands >= 2);
    bool ownMem = false;
    if (pMem == NULL) {
        pMem = malloc(GetInstanceSize(nBands));
        if (pMem == NULL) {
            return NULL;
        }
        ownMem = true;
    }
    return new (pMem) AudioEqualizer(pMem, nBands, nChannels, sampleRate,
                                     ownMem, presets, nPresets);
}

void AudioEqualizer::configure(int nChannels, int sampleRate) {
    ALOGV("AudioEqualizer::configure(nChannels=%d, sampleRate=%d)", nChannels,
         sampleRate);
    mpLowShelf->configure(nChannels, sampleRate);
    for (int i = 0; i < mNumPeaking; ++i) {
        mpPeakingFilters[i].configure(nChannels, sampleRate);
    }
    mpHighShelf->configure(nChannels, sampleRate);
}

void AudioEqualizer::clear() {
    ALOGV("AudioEqualizer::clear()");
    mpLowShelf->clear();
    for (int i = 0; i < mNumPeaking; ++i) {
        mpPeakingFilters[i].clear();
    }
    mpHighShelf->clear();
}

void AudioEqualizer::free() {
    ALOGV("AudioEqualizer::free()");
    if (mpMem != NULL) {
        ::free(mpMem);
    }
}

void AudioEqualizer::reset() {
    ALOGV("AudioEqualizer::reset()");
    const int32_t bottom = Effects_log2(kMinFreq);
    const int32_t top = Effects_log2(mSampleRate * 500);
    const int32_t jump = (top - bottom) / (mNumPeaking + 2);
    int32_t centerFreq = bottom + jump/2;

    mpLowShelf->reset();
    mpLowShelf->setFrequency(Effects_exp2(centerFreq));
    centerFreq += jump;
    for (int i = 0; i < mNumPeaking; ++i) {
        mpPeakingFilters[i].reset();
        mpPeakingFilters[i].setFrequency(Effects_exp2(centerFreq));
        centerFreq += jump;
    }
    mpHighShelf->reset();
    mpHighShelf->setFrequency(Effects_exp2(centerFreq));
    commit(true);
    mCurPreset = PRESET_CUSTOM;
}

void AudioEqualizer::setGain(int band, int32_t millibel) {
    ALOGV("AudioEqualizer::setGain(band=%d, millibel=%d)", band, millibel);
    assert(band >= 0 && band < mNumPeaking + 2);
    if (band == 0) {
        mpLowShelf->setGain(millibel);
    } else if (band == mNumPeaking + 1) {
        mpHighShelf->setGain(millibel);
    } else {
        mpPeakingFilters[band - 1].setGain(millibel);
    }
    mCurPreset = PRESET_CUSTOM;
}

void AudioEqualizer::setFrequency(int band, uint32_t millihertz) {
    ALOGV("AudioEqualizer::setFrequency(band=%d, millihertz=%d)", band,
         millihertz);
    assert(band >= 0 && band < mNumPeaking + 2);
    if (band == 0) {
        mpLowShelf->setFrequency(millihertz);
    } else if (band == mNumPeaking + 1) {
        mpHighShelf->setFrequency(millihertz);
    } else {
        mpPeakingFilters[band - 1].setFrequency(millihertz);
    }
    mCurPreset = PRESET_CUSTOM;
}

void AudioEqualizer::setBandwidth(int band, uint32_t cents) {
    ALOGV("AudioEqualizer::setBandwidth(band=%d, cents=%d)", band, cents);
    assert(band >= 0 && band < mNumPeaking + 2);
    if (band > 0 && band < mNumPeaking + 1) {
        mpPeakingFilters[band - 1].setBandwidth(cents);
        mCurPreset = PRESET_CUSTOM;
    }
}

int32_t AudioEqualizer::getGain(int band) const {
    assert(band >= 0 && band < mNumPeaking + 2);
    if (band == 0) {
        return mpLowShelf->getGain();
    } else if (band == mNumPeaking + 1) {
        return mpHighShelf->getGain();
    } else {
        return mpPeakingFilters[band - 1].getGain();
    }
}

uint32_t AudioEqualizer::getFrequency(int band) const {
    assert(band >= 0 && band < mNumPeaking + 2);
    if (band == 0) {
        return mpLowShelf->getFrequency();
    } else if (band == mNumPeaking + 1) {
        return mpHighShelf->getFrequency();
    } else {
        return mpPeakingFilters[band - 1].getFrequency();
    }
}

uint32_t AudioEqualizer::getBandwidth(int band) const {
    assert(band >= 0 && band < mNumPeaking + 2);
    if (band == 0 || band == mNumPeaking + 1) {
        return 0;
    } else {
        return mpPeakingFilters[band - 1].getBandwidth();
    }
}

void AudioEqualizer::getBandRange(int band, uint32_t & low,
                                  uint32_t & high) const {
    assert(band >= 0 && band < mNumPeaking + 2);
    if (band == 0) {
        low = 0;
        high = mpLowShelf->getFrequency();
    } else if (band == mNumPeaking + 1) {
        low = mpHighShelf->getFrequency();
        high = mSampleRate * 500;
    } else {
        mpPeakingFilters[band - 1].getBandRange(low, high);
    }
}

const char * AudioEqualizer::getPresetName(int preset) const {
    assert(preset < mNumPresets && preset >= PRESET_CUSTOM);
    if (preset == PRESET_CUSTOM) {
        return "Custom";
    } else {
        return mpPresets[preset].name;
    }
}

int AudioEqualizer::getNumPresets() const {
    return mNumPresets;
}

int AudioEqualizer::getPreset() const {
    return mCurPreset;
}

void AudioEqualizer::setPreset(int preset) {
    ALOGV("AudioEqualizer::setPreset(preset=%d)", preset);
    assert(preset < mNumPresets && preset >= 0);
    const PresetConfig &presetCfg = mpPresets[preset];
    for (int band = 0; band < (mNumPeaking + 2); ++band) {
        const BandConfig & bandCfg = presetCfg.bandConfigs[band];
        setGain(band, bandCfg.gain);
        setFrequency(band, bandCfg.freq);
        setBandwidth(band, bandCfg.bandwidth);
    }
    mCurPreset = preset;
}

void AudioEqualizer::commit(bool immediate) {
    ALOGV("AudioEqualizer::commit(immediate=%d)", immediate);
    mpLowShelf->commit(immediate);
    for (int i = 0; i < mNumPeaking; ++i) {
        mpPeakingFilters[i].commit(immediate);
    }
    mpHighShelf->commit(immediate);
}

void AudioEqualizer::process(const audio_sample_t * pIn,
                             audio_sample_t * pOut,
                             int frameCount) {
//    ALOGV("AudioEqualizer::process(frameCount=%d)", frameCount);
    mpLowShelf->process(pIn, pOut, frameCount);
    for (int i = 0; i < mNumPeaking; ++i) {
        mpPeakingFilters[i].process(pIn, pOut, frameCount);
    }
    mpHighShelf->process(pIn, pOut, frameCount);
}

void AudioEqualizer::enable(bool immediate) {
    ALOGV("AudioEqualizer::enable(immediate=%d)", immediate);
    mpLowShelf->enable(immediate);
    for (int i = 0; i < mNumPeaking; ++i) {
        mpPeakingFilters[i].enable(immediate);
    }
    mpHighShelf->enable(immediate);
}

void AudioEqualizer::disable(bool immediate) {
    ALOGV("AudioEqualizer::disable(immediate=%d)", immediate);
    mpLowShelf->disable(immediate);
    for (int i = 0; i < mNumPeaking; ++i) {
        mpPeakingFilters[i].disable(immediate);
    }
    mpHighShelf->disable(immediate);
}

int AudioEqualizer::getMostRelevantBand(uint32_t targetFreq) const {
    // First, find the two bands that the target frequency is between.
    uint32_t low = mpLowShelf->getFrequency();
    if (targetFreq <= low) {
        return 0;
    }
    uint32_t high = mpHighShelf->getFrequency();
    if (targetFreq >= high) {
        return mNumPeaking + 1;
    }
    int band = mNumPeaking;
    for (int i = 0; i < mNumPeaking; ++i) {
        uint32_t freq = mpPeakingFilters[i].getFrequency();
        if (freq >= targetFreq) {
            high = freq;
            band = i;
            break;
        }
        low = freq;
    }
    // Now, low is right below the target and high is right above. See which one
    // is closer on a log scale.
    low = Effects_log2(low);
    high = Effects_log2(high);
    targetFreq = Effects_log2(targetFreq);
    if (high - targetFreq < targetFreq - low) {
        return band + 1;
    } else {
        return band;
    }
}


AudioEqualizer::AudioEqualizer(void * pMem, int nBands, int nChannels,
                               int sampleRate, bool ownMem,
                               const PresetConfig * presets, int nPresets)
                               : mSampleRate(sampleRate)
                               , mpPresets(presets)
                               , mNumPresets(nPresets) {
    assert(pMem != NULL);
    assert(nPresets == 0 || nPresets > 0 && presets != NULL);
    mpMem = ownMem ? pMem : NULL;

    pMem = (char *) pMem + sizeof(AudioEqualizer);
    mpLowShelf = new (pMem) AudioShelvingFilter(AudioShelvingFilter::kLowShelf,
                                                nChannels, sampleRate);
    pMem = (char *) pMem + sizeof(AudioShelvingFilter);
    mpHighShelf = new (pMem) AudioShelvingFilter(AudioShelvingFilter::kHighShelf,
                                                 nChannels, sampleRate);
    pMem = (char *) pMem + sizeof(AudioShelvingFilter);
    mNumPeaking = nBands - 2;
    if (mNumPeaking > 0) {
        mpPeakingFilters = reinterpret_cast<AudioPeakingFilter *>(pMem);
        for (int i = 0; i < mNumPeaking; ++i) {
            new (&mpPeakingFilters[i]) AudioPeakingFilter(nChannels,
                                                          sampleRate);
        }
    }
    reset();
}

}
