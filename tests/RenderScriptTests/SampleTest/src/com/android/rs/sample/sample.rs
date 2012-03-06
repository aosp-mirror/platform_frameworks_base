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
#pragma rs java_package_name(com.android.rs.sample)
#include "rs_graphics.rsh"

rs_sampler wrapUV;
rs_sampler clampUV;
rs_allocation sourceAlloc;
rs_allocation destAlloc;

static uint32_t wrapI(rs_sampler_value wrap, uint32_t coord, uint32_t size) {
    if (wrap == RS_SAMPLER_WRAP) {
        return coord % (size + 1);
    }
    return min(coord, size);
}

static float2 wrap(rs_sampler_value wrapS, rs_sampler_value wrapT, float2 coord) {
    float2 wrappedCoord;
    float temp;
    if (wrapS == RS_SAMPLER_WRAP) {
        wrappedCoord.x = fract(coord.x, &temp);
        // Make sure that non zero integer uv's map to one
        if (wrappedCoord.x == 0.0f && coord.x != 0.0f) {
            wrappedCoord.x = 1.0f;
        }
    } else {
        wrappedCoord.x = min(coord.x, 1.0f);
    }

    if (wrapT == RS_SAMPLER_WRAP) {
        wrappedCoord.y = fract(coord.y, &temp);
        // Make sure that non zero integer uv's map to one
        if (wrappedCoord.y == 0.0f && coord.y != 0.0f) {
            wrappedCoord.y = 1.0f;
        }
    } else {
        wrappedCoord.y = min(coord.y, 1.0f);
    }
    return wrappedCoord;
}

// Naive implementation of texture filtering for prototyping purposes
static float4 sample(rs_allocation a, rs_sampler s, float2 uv) {
    rs_sampler_value wrapS = rsgSamplerGetWrapS(s);
    rs_sampler_value wrapT = rsgSamplerGetWrapT(s);

    rs_sampler_value sampleMin = rsgSamplerGetMinification(s);
    rs_sampler_value sampleMag = rsgSamplerGetMagnification(s);

    uv = wrap(wrapS, wrapT, uv);

    uint32_t sourceW = rsAllocationGetDimX(a) - 1;
    uint32_t sourceH = rsAllocationGetDimY(a) - 1;

    float2 dimF;
    dimF.x = (float)(sourceW);
    dimF.y = (float)(sourceH);
    float2 pixelUV = uv * dimF;
    uint2 iPixel = convert_uint2(pixelUV);

    if (sampleMin == RS_SAMPLER_NEAREST ||
        sampleMag == RS_SAMPLER_NEAREST) {
        uchar4 *nearestSample = (uchar4*)rsGetElementAt(a, iPixel.x, iPixel.y);
        return convert_float4(*nearestSample);
    }

    float2 frac = pixelUV - convert_float2(iPixel);
    float2 oneMinusFrac = 1.0f - frac;

    uint32_t nextX = wrapI(wrapS, iPixel.x + 1, sourceW);
    uint32_t nextY = wrapI(wrapT, iPixel.y + 1, sourceH);

    uchar4 *p0c = (uchar4*)rsGetElementAt(a, iPixel.x, iPixel.y);
    uchar4 *p1c = (uchar4*)rsGetElementAt(a, nextX, iPixel.y);
    uchar4 *p2c = (uchar4*)rsGetElementAt(a, iPixel.x, nextY);
    uchar4 *p3c = (uchar4*)rsGetElementAt(a, nextX, nextY);

    float4 p0 = convert_float4(*p0c);
    float4 p1 = convert_float4(*p1c);
    float4 p2 = convert_float4(*p2c);
    float4 p3 = convert_float4(*p3c);

    float4 weights;
    weights.x = oneMinusFrac.x * oneMinusFrac.y;
    weights.y = frac.x * oneMinusFrac.y;
    weights.z = oneMinusFrac.x * frac.y;
    weights.w = frac.x * frac.y;

    float4 result = p0 * weights.x + p1 * weights.y + p2 * weights.z + p3 * weights.w;

    /*rsDebug("*****************************************", 0);
    rsDebug("u", uv.x);
    rsDebug("v", uv.y);
    rsDebug("sourceW", sourceW);
    rsDebug("sourceH", sourceH);
    rsDebug("iPixelX", iPixel.x);
    rsDebug("iPixelY", iPixel.y);
    rsDebug("fiPixel", (float2)iPixel);
    rsDebug("whole", wholeUV);
    rsDebug("pixelUV", pixelUV);
    rsDebug("frac", frac);
    rsDebug("oneMinusFrac", oneMinusFrac);
    rsDebug("p0", p0);
    rsDebug("p1", p1);
    rsDebug("p2", p2);
    rsDebug("p3", p3);
    rsDebug("w", weights);
    rsDebug("result", result);*/

    return result;
}

void root(uchar4 *out, uint32_t x, uint32_t y) {

    float destX = (float)rsAllocationGetDimX(destAlloc) - 1.0f;
    float destY = (float)rsAllocationGetDimY(destAlloc) - 1.0f;

    /*rsDebug("*****************************************", 0);
    rsDebug("x", x);
    rsDebug("y", y);*/

    float2 uv;
    uv.x = (float)x / destX;
    uv.y = (float)y / destY;

    out->xyz = convert_uchar3(sample(sourceAlloc, wrapUV, uv*2.0f).xyz);
    out->w = 0xff;
}

