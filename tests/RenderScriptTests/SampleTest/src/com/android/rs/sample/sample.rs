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

#define convert_float(v) (float)v
#define SAMPLE_1D_FUNC(vecsize)                                                                 \
        static float##vecsize get1DSample##vecsize(rs_allocation a, float2 weights,             \
                                                 int iPixel, int next) {                        \
            uchar##vecsize *p0c = (uchar##vecsize*)rsGetElementAt(a, iPixel);                   \
            uchar##vecsize *p1c = (uchar##vecsize*)rsGetElementAt(a, next);                     \
            float##vecsize p0 = convert_float##vecsize(*p0c);                                   \
            float##vecsize p1 = convert_float##vecsize(*p1c);                                   \
            return p0 * weights.x + p1 * weights.y;                                             \
        }
#define SAMPLE_2D_FUNC(vecsize)                                                                 \
        static float##vecsize get2DSample##vecsize(rs_allocation a, float4 weights,             \
                                                 int2 iPixel, int nextX, int nextY) {           \
            uchar##vecsize *p0c = (uchar##vecsize*)rsGetElementAt(a, iPixel.x, iPixel.y);       \
            uchar##vecsize *p1c = (uchar##vecsize*)rsGetElementAt(a, nextX, iPixel.y);          \
            uchar##vecsize *p2c = (uchar##vecsize*)rsGetElementAt(a, iPixel.x, nextY);          \
            uchar##vecsize *p3c = (uchar##vecsize*)rsGetElementAt(a, nextX, nextY);             \
            float##vecsize p0 = convert_float##vecsize(*p0c);                                   \
            float##vecsize p1 = convert_float##vecsize(*p1c);                                   \
            float##vecsize p2 = convert_float##vecsize(*p2c);                                   \
            float##vecsize p3 = convert_float##vecsize(*p3c);                                   \
            return p0 * weights.x + p1 * weights.y + p2 * weights.z + p3 * weights.w;           \
        }

SAMPLE_1D_FUNC()
SAMPLE_1D_FUNC(2)
SAMPLE_1D_FUNC(3)
SAMPLE_1D_FUNC(4)

SAMPLE_2D_FUNC()
SAMPLE_2D_FUNC(2)
SAMPLE_2D_FUNC(3)
SAMPLE_2D_FUNC(4)

static float4 getBilinearSample565(rs_allocation a, float4 weights,
                                   int2 iPixel, int nextX, int nextY) {
    float4 zero = {0.0f, 0.0f, 0.0f, 0.0f};
    return zero;
}

static float4 getBilinearSample(rs_allocation a, float4 weights,
                                int2 iPixel, int nextX, int nextY,
                                uint32_t vecSize, rs_data_type dt) {
    if (dt == RS_TYPE_UNSIGNED_5_6_5) {
        return getBilinearSample565(a, weights, iPixel, nextX, nextY);
    }

    float4 result;
    switch(vecSize) {
    case 1:
        result.x = get2DSample(a, weights, iPixel, nextX, nextY);
        result.yzw = 0.0f;
        break;
    case 2:
        result.xy = get2DSample2(a, weights, iPixel, nextX, nextY);
        result.zw = 0.0f;
        break;
    case 3:
        result.xyz = get2DSample3(a, weights, iPixel, nextX, nextY);
        result.w = 0.0f;
        break;
    case 4:
        result = get2DSample4(a, weights, iPixel, nextX, nextY);
        break;
    }

    return result;
}

static float4 getNearestSample(rs_allocation a, int2 iPixel, uint32_t vecSize, rs_data_type dt) {
    if (dt == RS_TYPE_UNSIGNED_5_6_5) {
        float4 zero = {0.0f, 0.0f, 0.0f, 0.0f};
        return zero;
    }

    float4 result;
    switch(vecSize) {
    case 1:
        result.x = convert_float(*((uchar*)rsGetElementAt(a, iPixel.x, iPixel.y)));
        result.yzw = 0.0f;
    case 2:
        result.xy = convert_float2(*((uchar2*)rsGetElementAt(a, iPixel.x, iPixel.y)));
        result.zw = 0.0f;
    case 3:
        result.xyz = convert_float3(*((uchar3*)rsGetElementAt(a, iPixel.x, iPixel.y)));
        result.w = 0.0f;
    case 4:
        result = convert_float4(*((uchar4*)rsGetElementAt(a, iPixel.x, iPixel.y)));
    }

    return result;
}


// Naive implementation of texture filtering for prototyping purposes
static float4 sample(rs_allocation a, rs_sampler s, float2 uv) {

    // Find out what kind of input data we are sampling
    rs_element elem = rsAllocationGetElement(a);
    uint32_t vecSize = rsElementGetVectorSize(elem);
    rs_data_kind dk = rsElementGetDataKind(elem);
    rs_data_type dt = rsElementGetDataType(elem);

    if (dk == RS_KIND_USER || (dt != RS_TYPE_UNSIGNED_8 && dt != RS_TYPE_UNSIGNED_5_6_5)) {
        float4 zero = {0.0f, 0.0f, 0.0f, 0.0f};
        return zero;
    }
    //rsDebug("*****************************************", 0);
    rs_sampler_value wrapS = rsgSamplerGetWrapS(s);
    rs_sampler_value wrapT = rsgSamplerGetWrapT(s);

    rs_sampler_value sampleMin = rsgSamplerGetMinification(s);
    rs_sampler_value sampleMag = rsgSamplerGetMagnification(s);

    int32_t sourceW = rsAllocationGetDimX(a);
    int32_t sourceH = rsAllocationGetDimY(a);

    float2 dimF;
    dimF.x = (float)(sourceW);
    dimF.y = (float)(sourceH);
    float2 pixelUV = uv * dimF;
    int2 iPixel = convert_int2(pixelUV);

    if (sampleMin == RS_SAMPLER_NEAREST ||
        sampleMag == RS_SAMPLER_NEAREST) {
        iPixel.x = wrapI(wrapS, iPixel.x, sourceW);
        iPixel.y = wrapI(wrapT, iPixel.y, sourceH);
        return getNearestSample(a, iPixel, vecSize, dt);
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

    int32_t nextX = wrapI(wrapS, iPixel.x + 1, sourceW);
    int32_t nextY = wrapI(wrapT, iPixel.y + 1, sourceH);
    iPixel.x = wrapI(wrapS, iPixel.x, sourceW);
    iPixel.y = wrapI(wrapT, iPixel.y, sourceH);

    return getBilinearSample(a, weights, iPixel, nextX, nextY, vecSize, dt);
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

