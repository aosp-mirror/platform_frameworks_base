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
#pragma rs java_package_name(com.android.rs.image)
#pragma rs_fp_relaxed

uchar __attribute__((kernel)) genRand() {
    return (uchar)rsRand(0xff);
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

int32_t gWMask;
int32_t gHMask;

rs_allocation gBlendSource;
uchar __attribute__((kernel)) blend9(uint32_t x, uint32_t y) {
    uint32_t x1 = (x-1) & gWMask;
    uint32_t x2 = (x+1) & gWMask;
    uint32_t y1 = (y-1) & gHMask;
    uint32_t y2 = (y+1) & gHMask;

    uint p00 = 56 *  rsGetElementAt_uchar(gBlendSource, x1, y1);
    uint p01 = 114 * rsGetElementAt_uchar(gBlendSource, x, y1);
    uint p02 = 56 *  rsGetElementAt_uchar(gBlendSource, x2, y1);
    uint p10 = 114 * rsGetElementAt_uchar(gBlendSource, x1, y);
    uint p11 = 230 * rsGetElementAt_uchar(gBlendSource, x, y);
    uint p12 = 114 * rsGetElementAt_uchar(gBlendSource, x2, y);
    uint p20 = 56 *  rsGetElementAt_uchar(gBlendSource, x1, y2);
    uint p21 = 114 * rsGetElementAt_uchar(gBlendSource, x, y2);
    uint p22 = 56 *  rsGetElementAt_uchar(gBlendSource, x2, y2);

    p00 += p01;
    p02 += p10;
    p11 += p12;
    p20 += p21;

    p22 += p00;
    p02 += p11;

    p20 += p22;
    p20 += p02;

    p20 = min(p20 >> 10, (uint)255);
    return (uchar)p20;
}

float gNoiseStrength;

rs_allocation gNoise;
uchar4 __attribute__((kernel)) root(uchar4 in, uint32_t x, uint32_t y) {
    float4 ip = convert_float4(in);
    float pnoise = (float) rsGetElementAt_uchar(gNoise, x & gWMask, y & gHMask);

    float energy_level = ip.r + ip.g + ip.b;
    float energy_mask = (28.f - sqrt(energy_level)) * 0.03571f;
    pnoise = (pnoise - 128.f) * energy_mask;

    ip += pnoise * gNoiseStrength;
    ip = clamp(ip, 0.f, 255.f);

    uchar4 p = convert_uchar4(ip);
    p.a = 0xff;
    return p;
}
