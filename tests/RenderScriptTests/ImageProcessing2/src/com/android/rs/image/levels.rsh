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

float inBlack;
float outBlack;
float inWMinInB;
float outWMinOutB;
float overInWMinInB;
rs_matrix3x3 colorMat;

void root(const uchar4 *in, uchar4 *out, uint32_t x, uint32_t y) {
    float3 pixel = convert_float4(in[0]).rgb;
    pixel = rsMatrixMultiply(&colorMat, pixel);
    pixel = clamp(pixel, 0.f, 255.f);
    pixel = (pixel - inBlack) * overInWMinInB;
    pixel = pixel * outWMinOutB + outBlack;
    pixel = clamp(pixel, 0.f, 255.f);
    out->xyz = convert_uchar3(pixel);
    out->w = 0xff;
}

void root4(const uchar4 *in, uchar4 *out, uint32_t x, uint32_t y) {
    float4 pixel = convert_float4(in[0]);
    pixel.rgb = rsMatrixMultiply(&colorMat, pixel.rgb);
    pixel = clamp(pixel, 0.f, 255.f);
    pixel = (pixel - inBlack) * overInWMinInB;
    pixel = pixel * outWMinOutB + outBlack;
    pixel = clamp(pixel, 0.f, 255.f);
    out->xyzw = convert_uchar4(pixel);
}

