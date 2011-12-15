/* //device/servers/AudioFlinger/AudioCoefInterpolator.cpp
 **
 ** Copyright 2008, The Android Open Source Project
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

#include <cutils/compiler.h>

#include "AudioCoefInterpolator.h"

namespace android {

AudioCoefInterpolator::AudioCoefInterpolator(size_t nInDims,
                                             const size_t inDims[],
                                             size_t nOutDims,
                                             const audio_coef_t * table) {
    mNumInDims = nInDims;
    memcpy(mInDims, inDims, nInDims * sizeof(size_t));
    mNumOutDims = nOutDims;
    mTable = table;
    // Initialize offsets array
    size_t dim = nInDims - 1;
    mInDimOffsets[nInDims - 1] = nOutDims;
    while (dim-- > 0) {
        mInDimOffsets[dim] = mInDimOffsets[dim + 1] * mInDims[dim + 1];
    }
}

void AudioCoefInterpolator::getCoef(const int intCoord[], uint32_t fracCoord[],
                                    audio_coef_t out[]) {
    size_t index = 0;
    size_t dim = mNumInDims;
    while (dim-- > 0) {
        if (CC_UNLIKELY(intCoord[dim] < 0)) {
            fracCoord[dim] = 0;
        } else if (CC_UNLIKELY(intCoord[dim] >= (int)mInDims[dim] - 1)) {
            fracCoord[dim] = 0;
            index += mInDimOffsets[dim] * (mInDims[dim] - 1);
        } else {
            index += mInDimOffsets[dim] * intCoord[dim];
        }
    }
    getCoefRecurse(index, fracCoord, out, 0);
}

void AudioCoefInterpolator::getCoefRecurse(size_t index,
                                           const uint32_t fracCoord[],
                                           audio_coef_t out[], size_t dim) {
    if (dim == mNumInDims) {
        memcpy(out, mTable + index, mNumOutDims * sizeof(audio_coef_t));
    } else {
        getCoefRecurse(index, fracCoord, out, dim + 1);
        if (CC_LIKELY(fracCoord != 0)) {
           audio_coef_t tempCoef[MAX_OUT_DIMS];
           getCoefRecurse(index + mInDimOffsets[dim], fracCoord, tempCoef,
                           dim + 1);
            size_t d = mNumOutDims;
            while (d-- > 0) {
                out[d] = interp(out[d], tempCoef[d], fracCoord[dim]);
            }
        }
    }
}

audio_coef_t AudioCoefInterpolator::interp(audio_coef_t lo, audio_coef_t hi,
                                           uint32_t frac) {
    int64_t delta = static_cast<int64_t>(hi-lo) * frac;
    return lo + static_cast<audio_coef_t> (delta >> 32);
}

}
