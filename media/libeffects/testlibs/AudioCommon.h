/*
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

#ifndef ANDROID_AUDIO_COMMON_H
#define ANDROID_AUDIO_COMMON_H

#include <stdint.h>
#include <stddef.h>
#include <cutils/compiler.h>

namespace android {

// Audio coefficient type.
typedef int32_t audio_coef_t;
// Audio sample type.
typedef int32_t audio_sample_t;
// Accumulator type for coef x sample.
typedef int64_t audio_coef_sample_acc_t;

// Number of fraction bits for audio coefficient.
static const int AUDIO_COEF_PRECISION = 24;
// Audio coefficient with the value of 1.0
static const audio_coef_t AUDIO_COEF_ONE = 1 << AUDIO_COEF_PRECISION;
// Audio coefficient with the value of 0.5
static const audio_coef_t AUDIO_COEF_HALF = 1 << (AUDIO_COEF_PRECISION - 1);
// Number of fraction bits for audio sample.
static const int AUDIO_SAMPLE_PRECISION = 24;
// Audio sample with the value of 1.0
static const audio_sample_t AUDIO_SAMPLE_ONE = 1 << AUDIO_SAMPLE_PRECISION;

// TODO: These are just temporary naive implementations of the necessary
// arithmetic operations needed for the filter. They should be moved to a more
// generic location and implemented more efficiently.

// Multiply a sample by a coefficient to return an accumulator.
inline audio_coef_sample_acc_t mul_coef_sample(audio_coef_t x, audio_sample_t y) {
    return ((audio_coef_sample_acc_t) (x)) * y;
}

// Multiply and accumulate sample by a coefficient to return an accumulator.
inline audio_coef_sample_acc_t mac_coef_sample(audio_coef_t x, audio_sample_t y, audio_coef_sample_acc_t acc) {
    return acc + ((audio_coef_sample_acc_t) (x)) * y;
}

// Convert a sample-coefficient accumulator to a sample.
inline audio_sample_t coef_sample_acc_to_sample(audio_coef_sample_acc_t acc) {
    if (acc < 0) {
        acc += AUDIO_COEF_ONE - 1;
    }
    return (audio_sample_t) (acc >> AUDIO_COEF_PRECISION);
}

// Convert a S15 sample to audio_sample_t
inline audio_sample_t s15_to_audio_sample_t(int16_t s15) {
    return audio_sample_t(s15) << 9;
}

// Convert a audio_sample_t sample to S15 (no clipping)
inline int16_t audio_sample_t_to_s15(audio_sample_t sample) {
    return int16_t((sample + (1 << 8)) >> 9);
}

// Convert a audio_sample_t sample to S15 (with clipping)
inline int16_t audio_sample_t_to_s15_clip(audio_sample_t sample) {
    // TODO: optimize for targets supporting this as an atomic operation.
    if (CC_UNLIKELY(sample >= (0x7FFF << 9))) {
        return 0x7FFF;
    } else if (CC_UNLIKELY(sample <= -(0x8000 << 9))) {
        return 0x8000;
    } else {
        return audio_sample_t_to_s15(sample);
    }
}

////////////////////////////////////////////////////////////////////////////////

}

#endif // ANDROID_AUDIO_COMMON_H
