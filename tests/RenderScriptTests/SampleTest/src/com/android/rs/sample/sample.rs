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

void root(uchar4 *out, uint32_t x, uint32_t y) {

    float destX = (float)rsAllocationGetDimX(destAlloc) - 1.0f;
    float destY = (float)rsAllocationGetDimY(destAlloc) - 1.0f;

    float2 uv;
    uv.x = (float)x / destX;
    uv.y = (float)y / destY;

    out->xyz = convert_uchar3(rsSample(sourceAlloc, allocSampler, uv*2.0f).xyz);
    out->w = 0xff;
}

