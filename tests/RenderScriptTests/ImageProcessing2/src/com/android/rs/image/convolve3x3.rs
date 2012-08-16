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

int32_t gWidth;
int32_t gHeight;
rs_allocation gIn;

float gCoeffs[9];

void root(uchar4 *out, uint32_t x, uint32_t y) {
    uint32_t x1 = min((int32_t)x+1, gWidth);
    uint32_t x2 = max((int32_t)x-1, 0);
    uint32_t y1 = min((int32_t)y+1, gHeight);
    uint32_t y2 = max((int32_t)y-1, 0);

    float4 p00 = convert_float4(((uchar4 *)rsGetElementAt(gIn, x1, y1))[0]);
    float4 p01 = convert_float4(((uchar4 *)rsGetElementAt(gIn, x, y1))[0]);
    float4 p02 = convert_float4(((uchar4 *)rsGetElementAt(gIn, x2, y1))[0]);
    float4 p10 = convert_float4(((uchar4 *)rsGetElementAt(gIn, x1, y))[0]);
    float4 p11 = convert_float4(((uchar4 *)rsGetElementAt(gIn, x, y))[0]);
    float4 p12 = convert_float4(((uchar4 *)rsGetElementAt(gIn, x2, y))[0]);
    float4 p20 = convert_float4(((uchar4 *)rsGetElementAt(gIn, x1, y2))[0]);
    float4 p21 = convert_float4(((uchar4 *)rsGetElementAt(gIn, x, y2))[0]);
    float4 p22 = convert_float4(((uchar4 *)rsGetElementAt(gIn, x2, y2))[0]);
    p00 *= gCoeffs[0];
    p01 *= gCoeffs[1];
    p02 *= gCoeffs[2];
    p10 *= gCoeffs[3];
    p11 *= gCoeffs[4];
    p12 *= gCoeffs[5];
    p20 *= gCoeffs[6];
    p21 *= gCoeffs[7];
    p22 *= gCoeffs[8];

    p00 += p01;
    p02 += p10;
    p11 += p12;
    p20 += p21;

    p22 += p00;
    p02 += p11;

    p20 += p22;
    p20 += p02;

    p20 = clamp(p20, 0.f, 255.f);
    *out = convert_uchar4(p20);
}


