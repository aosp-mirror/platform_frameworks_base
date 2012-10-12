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

float gCoeffs[25];

void root(uchar4 *out, uint32_t x, uint32_t y) {
    uint32_t x0 = max((int32_t)x-2, 0);
    uint32_t x1 = max((int32_t)x-1, 0);
    uint32_t x2 = x;
    uint32_t x3 = min((int32_t)x+1, gWidth-1);
    uint32_t x4 = min((int32_t)x+2, gWidth-1);

    uint32_t y0 = max((int32_t)y-2, 0);
    uint32_t y1 = max((int32_t)y-1, 0);
    uint32_t y2 = y;
    uint32_t y3 = min((int32_t)y+1, gHeight-1);
    uint32_t y4 = min((int32_t)y+2, gHeight-1);

    float4 p0 = convert_float4(rsGetElementAt_uchar4(gIn, x0, y0)) * gCoeffs[0]
              + convert_float4(rsGetElementAt_uchar4(gIn, x1, y0)) * gCoeffs[1]
              + convert_float4(rsGetElementAt_uchar4(gIn, x2, y0)) * gCoeffs[2]
              + convert_float4(rsGetElementAt_uchar4(gIn, x3, y0)) * gCoeffs[3]
              + convert_float4(rsGetElementAt_uchar4(gIn, x4, y0)) * gCoeffs[4];

    float4 p1 = convert_float4(rsGetElementAt_uchar4(gIn, x0, y1)) * gCoeffs[5]
              + convert_float4(rsGetElementAt_uchar4(gIn, x1, y1)) * gCoeffs[6]
              + convert_float4(rsGetElementAt_uchar4(gIn, x2, y1)) * gCoeffs[7]
              + convert_float4(rsGetElementAt_uchar4(gIn, x3, y1)) * gCoeffs[8]
              + convert_float4(rsGetElementAt_uchar4(gIn, x4, y1)) * gCoeffs[9];

    float4 p2 = convert_float4(rsGetElementAt_uchar4(gIn, x0, y2)) * gCoeffs[10]
              + convert_float4(rsGetElementAt_uchar4(gIn, x1, y2)) * gCoeffs[11]
              + convert_float4(rsGetElementAt_uchar4(gIn, x2, y2)) * gCoeffs[12]
              + convert_float4(rsGetElementAt_uchar4(gIn, x3, y2)) * gCoeffs[13]
              + convert_float4(rsGetElementAt_uchar4(gIn, x4, y2)) * gCoeffs[14];

    float4 p3 = convert_float4(rsGetElementAt_uchar4(gIn, x0, y3)) * gCoeffs[15]
              + convert_float4(rsGetElementAt_uchar4(gIn, x1, y3)) * gCoeffs[16]
              + convert_float4(rsGetElementAt_uchar4(gIn, x2, y3)) * gCoeffs[17]
              + convert_float4(rsGetElementAt_uchar4(gIn, x3, y3)) * gCoeffs[18]
              + convert_float4(rsGetElementAt_uchar4(gIn, x4, y3)) * gCoeffs[19];

    float4 p4 = convert_float4(rsGetElementAt_uchar4(gIn, x0, y4)) * gCoeffs[20]
              + convert_float4(rsGetElementAt_uchar4(gIn, x1, y4)) * gCoeffs[21]
              + convert_float4(rsGetElementAt_uchar4(gIn, x2, y4)) * gCoeffs[22]
              + convert_float4(rsGetElementAt_uchar4(gIn, x3, y4)) * gCoeffs[23]
              + convert_float4(rsGetElementAt_uchar4(gIn, x4, y4)) * gCoeffs[24];

    p0 = clamp(p0 + p1 + p2 + p3 + p4, 0.f, 255.f);
    *out = convert_uchar4(p0);
}


