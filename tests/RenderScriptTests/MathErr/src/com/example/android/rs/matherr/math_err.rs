/*
 * Copyright (C) 2013 The Android Open Source Project
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

#pragma version(1)
#pragma rs java_package_name(com.example.android.rs.matherr)

typedef union
{
  float fv;
  int32_t iv;
} ieee_float_shape_type;

/* Get a 32 bit int from a float.  */

#define GET_FLOAT_WORD(i,d)         \
do {                                \
  ieee_float_shape_type gf_u;       \
  gf_u.fv = (d);                    \
  (i) = gf_u.iv;                    \
} while (0)

/* Set a float from a 32 bit int.  */

#define SET_FLOAT_WORD(d,i)         \
do {                                \
  ieee_float_shape_type sf_u;       \
  sf_u.iv = (i);                    \
  (d) = sf_u.fv;                    \
} while (0)


static float fast_log2(float v) {
    int32_t ibits;
    GET_FLOAT_WORD(ibits, v);

    int32_t e = (ibits >> 23) & 0xff;

    ibits &= 0x7fffff;
    ibits |= 127 << 23;

    float ir;
    SET_FLOAT_WORD(ir, ibits);

    ir -= 1.5f;
    float ir2 = ir*ir;
    float adj2 = 0.405465108f + // -0.00009f +
                 (0.666666667f * ir) -
                 (0.222222222f * ir2) +
                 (0.098765432f * ir*ir2) -
                 (0.049382716f * ir2*ir2) +
                 (0.026337449f * ir*ir2*ir2) -
                 (0.014631916f * ir2*ir2*ir2);
    adj2 *= (1.f / 0.693147181f);

    return (float)(e - 127) + adj2;
}

void testExp2(const float *in, float *out) {
    float i = *in;
    if (i > (-125.f) && i < 125.f) {
        *out = native_exp2(i);
    } else {
        *out = exp2(i);
    }
    *out = native_exp2(i);
}

void testLog2(const float *in, float *out) {
    *out = fast_log2(*in);
}

