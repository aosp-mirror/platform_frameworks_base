/*
 * Copyright (C) 2012 The Android Open Source Project
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
#pragma rs java_package_name(com.android.rs.image2)
#pragma rs_fp_relaxed

void genRand(uchar *out) {
    *out = (uchar)rsRand(0xff);
}

/*
 * Convolution matrix of distance 2 with fixed point of 'kShiftBits' bits
 * shifted. Thus the sum of this matrix should be 'kShiftValue'. Entries of
 * small values are not calculated to gain efficiency.
 * The order ot pixels represented in this matrix is:
 *  1  2  3
 *  4  0  5
 *  6  7  8
 *  and the matrix should be: {230, 56, 114, 56, 114, 114, 56, 114, 56}.
 *  However, since most of the valus are identical, we only use the first three
 *  entries and the entries corresponding to the pixels is:
 *  1  2  1
 *  2  0  2
 *  1  2  1
 */

int32_t gWidth;
int32_t gHeight;

rs_allocation gBlendSource;
void blend9(uchar *out, uint32_t x, uint32_t y) {
    uint32_t x1 = min(x+1, (uint32_t)gWidth);
    uint32_t x2 = max(x-1, (uint32_t)0);
    uint32_t y1 = min(y+1, (uint32_t)gHeight);
    uint32_t y2 = max(y-1, (uint32_t)0);

    uint p00 = 56 *  ((uchar *)rsGetElementAt(gBlendSource, x1, y1))[0];
    uint p01 = 114 * ((uchar *)rsGetElementAt(gBlendSource, x, y1))[0];
    uint p02 = 56 *  ((uchar *)rsGetElementAt(gBlendSource, x2, y1))[0];
    uint p10 = 114 * ((uchar *)rsGetElementAt(gBlendSource, x1, y))[0];
    uint p11 = 230 * ((uchar *)rsGetElementAt(gBlendSource, x, y))[0];
    uint p12 = 114 * ((uchar *)rsGetElementAt(gBlendSource, x2, y))[0];
    uint p20 = 56 *  ((uchar *)rsGetElementAt(gBlendSource, x1, y2))[0];
    uint p21 = 114 * ((uchar *)rsGetElementAt(gBlendSource, x, y2))[0];
    uint p22 = 56 *  ((uchar *)rsGetElementAt(gBlendSource, x2, y2))[0];

    p00 += p01;
    p02 += p10;
    p11 += p12;
    p20 += p21;

    p22 += p00;
    p02 += p11;

    p20 += p22;
    p20 += p02;

    *out = (uchar)(p20 >> 10);
}

float gNoiseStrength;

rs_allocation gNoise;
void root(const uchar4 *in, uchar4 *out, uint32_t x, uint32_t y) {
    float4 ip = convert_float4(*in);
    float pnoise = (float) ((uchar *)rsGetElementAt(gNoise, x, y))[0];

    float energy_level = ip.r + ip.g + ip.b;
    float energy_mask = (28.f - sqrt(energy_level)) * 0.03571f;
    pnoise = (pnoise - 128.f) * energy_mask;

    ip += pnoise * gNoiseStrength;
    ip = clamp(ip, 0.f, 255.f);

    uchar4 p = convert_uchar4(ip);
    p.a = 0xff;
    *out = p;
}
