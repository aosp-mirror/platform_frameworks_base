/* //device/include/server/AudioFlinger/AudioCoefInterpolator.h
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

#ifndef ANDROID_AUDIO_COEF_INTERPOLATOR_H
#define ANDROID_AUDIO_COEF_INTERPOLATOR_H

#include "AudioCommon.h"

namespace android {

// A helper class for linear interpolation of N-D -> M-D coefficient tables.
// This class provides support for out-of-range indexes.
// Details:
// The purpose is efficient approximation of a N-dimensional vector to
// M-dimensional function. The approximation is based on a table of output
// values on a uniform grid of the input values. Values not on the grid are
// linearly interpolated.
// Access to values are done by specifying input values in table index units,
// having an integer and a fractional part, e.g. retrieving a value from index
// 1.4 will result in linear interpolation between index 1 and index 2.
class AudioCoefInterpolator {
public:
    // Constructor.
    // nInDims      Number of input dimensions (limited to MAX_IN_DIMS).
    // inDims       An array of size nInDims with the size of the table on each
    //              respective dimension.
    // nOutDims     Number of output dimensions (limited to MAX_OUT_DIMS).
    // table        The coefficient table. Should be of size:
    //              inDims[0]*inDims[1]*...*inDims[nInDims-1]*nOutDims, where
    //              func([i,j,k]) = table(i,j,k,:)
    AudioCoefInterpolator(size_t nInDims, const size_t inDims[],
                          size_t nOutDims, const audio_coef_t * table);

    // Get the value of the approximated function at a given point.
    // intCoord     The integer part of the input value. Should be an array of
    //              size nInDims.
    // fracCoord    The fractional part of the input value. Should be an array
    //              of size nInDims. This value is in 32-bit precision.
    // out          An array for the output value. Should be of size nOutDims.
    void getCoef(const int intCoord[], uint32_t fracCoord[], audio_coef_t out[]);

private:
    // Maximum allowed number of input dimensions.
    static const size_t MAX_IN_DIMS = 8;
    // Maximum allowed number of output dimensions.
    static const size_t MAX_OUT_DIMS = 8;

    // Number of input dimensions.
    size_t mNumInDims;
    // Number of input dimensions.
    size_t mInDims[MAX_IN_DIMS];
    // The offset between two consecutive indexes of each dimension. This is in
    // fact a cumulative product of mInDims (done in reverse).
    size_t mInDimOffsets[MAX_IN_DIMS];
    // Number of output dimensions.
    size_t mNumOutDims;
    // The coefficient table.
    const audio_coef_t * mTable;

    // A recursive function for getting an interpolated coefficient value.
    // The recursion depth is the number of input dimensions.
    // At each step, we fetch two interpolated values of the current dimension,
    // by two recursive calls to this method for the next dimensions. We then
    // linearly interpolate these values over the current dimension.
    // index      The linear integer index of the value we need to interpolate.
    // fracCoord  A vector of fractional coordinates for each of the input
    //            dimensions.
    // out        Where the output should be written. Needs to be of size
    //            mNumOutDims.
    // dim        The input dimensions we are currently interpolating. This
    //            value will be increased on recursive calls.
    void getCoefRecurse(size_t index, const uint32_t fracCoord[],
                        audio_coef_t out[], size_t dim);

    // Scalar interpolation of two data points.
    // lo       The first data point.
    // hi       The second data point.
    // frac     A 32-bit fraction designating the weight of the second point.
    static audio_coef_t interp(audio_coef_t lo, audio_coef_t hi, uint32_t frac);
};

}

#endif // ANDROID_AUDIO_COEF_INTERPOLATOR_H
