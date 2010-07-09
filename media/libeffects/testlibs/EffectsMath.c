/*
 * Copyright (C) 2008 The Android Open Source Project
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
#define LOG_TAG "EFFECTSMATH"
//#define LOG_NDEBUG 0
#include <cutils/log.h>
#include <assert.h>

#include "EffectsMath.h"

// gLogTab contains pre-calculated values of log2(1 + ai5*2^-1 + ai4*2^-2 + ai3*2^-3 + ai2*2^-4 + ai1*2^-5 + ai0*2^-6)
// for integers in the range 0 to 63 (i = ai5*2^5 + ai4*2^4 + ai3*2^3 + ai2*2^2 + ai1*2^1 + ai0*2^0)
// It is used for a better than piece wise approximation of lin to log2 conversion

static const uint16_t gLogTab[] =
{
    0, 733, 1455, 2166,
    2866, 3556, 4236, 4907,
    5568, 6220, 6863, 7498,
    8124, 8742, 9352, 9954,
    10549, 11136, 11716, 12289,
    12855, 13415, 13968, 14514,
    15055, 15589, 16117, 16639,
    17156, 17667, 18173, 18673,
    19168, 19658, 20143, 20623,
    21098, 21568, 22034, 22495,
    22952, 23404, 23852, 24296,
    24736, 25172, 25604, 26031,
    26455, 26876, 27292, 27705,
    28114, 28520, 28922, 29321,
    29717, 30109, 30498, 30884,
    31267, 31647, 32024, 32397,
    32768
};

int32_t Effects_log2(uint32_t x) {
    int32_t exp = 31 - __builtin_clz(x);
    uint32_t segStart = x >> (exp - 6);
    uint32_t i = segStart & 0x3F;
    int32_t log = (int32_t)gLogTab[i];
    int32_t logEnd = (int32_t)gLogTab[i+1];
    segStart <<= exp - 6;

    return (exp << 15) + log + (((x - segStart) * (logEnd - log)) >> (exp - 6));
}

// gExpTab[i] = (2^(i>>6)) << 22
static const uint32_t gExpTab[] = {
            4194304, 4239977, 4286147, 4332820,
            4380002, 4427697, 4475911, 4524651,
            4573921, 4623728, 4674077, 4724974,
            4776426, 4828438, 4881016, 4934167,
            4987896, 5042211, 5097117, 5152621,
            5208729, 5265449, 5322786, 5380747,
            5439339, 5498570, 5558445, 5618973,
            5680159, 5742012, 5804539, 5867746,
            5931642, 5996233, 6061528, 6127533,
            6194258, 6261709, 6329894, 6398822,
            6468501, 6538938, 6610143, 6682122,
            6754886, 6828442, 6902799, 6977965,
            7053950, 7130763, 7208412, 7286906,
            7366255, 7446469, 7527555, 7609525,
            7692387, 7776152, 7860829, 7946428,
            8032959, 8120432, 8208857, 8298246,
            8388608
};


uint32_t Effects_exp2(int32_t x) {
    int32_t i = x >> 15;
    assert(i < 32);
    x &= (1 << 15) - 1;
    int32_t j = x >> 9;
    x &= (1 << 9) - 1;
    uint32_t exp = gExpTab[j];
    uint32_t expEnd = gExpTab[j+1];

    return ((exp << 9) + (expEnd - exp) * x) >> (31 - i);
}


int16_t Effects_MillibelsToLinear16 (int32_t nGain)
{
    nGain = ((nGain + MB_TO_LIN_K1) << 15 ) / MB_TO_LIN_K2;
    uint32_t exp2 = Effects_exp2(nGain);

    if (exp2 > 32767) exp2 = 32767;

    return (int16_t)exp2;
}


int16_t Effects_Linear16ToMillibels (int32_t nGain)
{
    return (int16_t)(((MB_TO_LIN_K2*Effects_log2(nGain))>>15)-MB_TO_LIN_K1);
}


int32_t Effects_Sqrt(int32_t in)
{
    int32_t tmp;
    int32_t out = 0;
    int32_t i;
    int32_t j;


    if (in == 0) return 0;

    if (in >= 0x10000000)
    {
        out = 0x4000;
        in -= 0x10000000;
    }

    j = 32 - __builtin_clz(in);

    if (j & 1) j++;
    j >>= 1;

    for (i = j; i > 0; i--) {
        tmp = (out << i) + (1 << ((i - 1)*2));
        if (in >= tmp)
        {
            out += 1 << (i-1);
            in -= tmp;
        }
    }

    return out;
}

