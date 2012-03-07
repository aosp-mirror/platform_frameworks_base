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

static rs_allocation sourceAlloc;
static rs_allocation destAlloc;
static rs_sampler allocSampler;

void setSampleData(rs_allocation dest, rs_allocation source, rs_sampler sampler) {
    destAlloc = dest;
    sourceAlloc = source;
    allocSampler = sampler;
}

static int32_t wrapI(rs_sampler_value wrap, int32_t coord, int32_t size) {
    if (wrap == RS_SAMPLER_WRAP) {
        coord = coord % size;
        if (coord < 0) {
            coord += size;
        }
    }
    return max(0, min(coord, size - 1));
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
        if (wrappedCoord.x < 0.0f) {
            wrappedCoord.x += 1.0f;
        }
    } else {
        wrappedCoord.x = max(0.0f, min(coord.x, 1.0f));
    }

    if (wrapT == RS_SAMPLER_WRAP) {
        wrappedCoord.y = fract(coord.y, &temp);
        // Make sure that non zero integer uv's map to one
        if (wrappedCoord.y == 0.0f && coord.y != 0.0f) {
            wrappedCoord.y = 1.0f;
        }
        if (wrappedCoord.y < 0.0f) {
            wrappedCoord.y += 1.0f;
        }
    } else {
        wrappedCoord.y = max(0.0f, min(coord.y, 1.0f));
    }
    return wrappedCoord;
}

// Naive implementation of texture filtering for prototyping purposes
static float4 sample(rs_allocation a, rs_sampler s, float2 uv) {
    //rsDebug("*****************************************", 0);
    rs_sampler_value wrapS = rsgSamplerGetWrapS(s);
    rs_sampler_value wrapT = rsgSamplerGetWrapT(s);

    rs_sampler_value sampleMin = rsgSamplerGetMinification(s);
    rs_sampler_value sampleMag = rsgSamplerGetMagnification(s);

    uv = wrap(wrapS, wrapT, uv);

    int32_t sourceW = rsAllocationGetDimX(a);
    int32_t sourceH = rsAllocationGetDimY(a);

    /*rsDebug("uv", uv);
    rsDebug("sourceW", sourceW);
    rsDebug("sourceH", sourceH);*/

    float2 dimF;
    dimF.x = (float)(sourceW);
    dimF.y = (float)(sourceH);
    float2 pixelUV = uv * dimF;
    int2 iPixel = convert_int2(pixelUV);
    /*rsDebug("iPixelX initial", iPixel.x);
    rsDebug("iPixelY initial", iPixel.y);*/

    if (sampleMin == RS_SAMPLER_NEAREST ||
        sampleMag == RS_SAMPLER_NEAREST) {
        iPixel.x = wrapI(wrapS, iPixel.x, sourceW);
        iPixel.y = wrapI(wrapT, iPixel.y, sourceH);
        uchar4 *nearestSample = (uchar4*)rsGetElementAt(a, iPixel.x, iPixel.y);
        return convert_float4(*nearestSample);
    }

    float2 frac = pixelUV - convert_float2(iPixel);

    if (frac.x < 0.5f) {
        iPixel.x -= 1;
        frac.x += 0.5f;
    } else {
        frac.x -= 0.5f;
    }
    if (frac.y < 0.5f) {
        iPixel.y -= 1;
        frac.y += 0.5f;
    } else {
        frac.y -= 0.5f;
    }
    float2 oneMinusFrac = 1.0f - frac;

    float4 weights;
    weights.x = oneMinusFrac.x * oneMinusFrac.y;
    weights.y = frac.x * oneMinusFrac.y;
    weights.z = oneMinusFrac.x * frac.y;
    weights.w = frac.x * frac.y;

    uint32_t nextX = wrapI(wrapS, iPixel.x + 1, sourceW);
    uint32_t nextY = wrapI(wrapT, iPixel.y + 1, sourceH);
    iPixel.x = wrapI(wrapS, iPixel.x, sourceW);
    iPixel.y = wrapI(wrapT, iPixel.y, sourceH);
    /*rsDebug("iPixelX wrapped", iPixel.x);
    rsDebug("iPixelY wrapped", iPixel.y);*/

    uchar4 *p0c = (uchar4*)rsGetElementAt(a, iPixel.x, iPixel.y);
    uchar4 *p1c = (uchar4*)rsGetElementAt(a, nextX, iPixel.y);
    uchar4 *p2c = (uchar4*)rsGetElementAt(a, iPixel.x, nextY);
    uchar4 *p3c = (uchar4*)rsGetElementAt(a, nextX, nextY);

    float4 p0 = convert_float4(*p0c);
    float4 p1 = convert_float4(*p1c);
    float4 p2 = convert_float4(*p2c);
    float4 p3 = convert_float4(*p3c);

    float4 result = p0 * weights.x + p1 * weights.y + p2 * weights.z + p3 * weights.w;

    /*rsDebug("pixelUV", pixelUV);
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

    float2 uv;
    uv.x = (float)x / destX;
    uv.y = (float)y / destY;

    out->xyz = convert_uchar3(sample(sourceAlloc, allocSampler, uv*2.0f).xyz);
    out->w = 0xff;
}

