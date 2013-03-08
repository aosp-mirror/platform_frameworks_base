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

#include "ip.rsh"
//#pragma rs_fp_relaxed

static float sr = 0.f;
static float sg = 0.f;
static float sb = 0.f;

void prepareBwFilter(uint32_t rw, uint32_t gw, uint32_t bw) {

    sr = rw;
    sg = gw;
    sb = bw;

    float imageMin = min(sg,sb);
    imageMin = fmin(sr,imageMin);
    float imageMax = max(sg,sb);
    imageMax = fmax(sr,imageMax);
    float avg = (imageMin + imageMax)/2;
    sb /= avg;
    sg /= avg;
    sr /= avg;

}

void bwFilterKernel(const uchar4 *in, uchar4 *out) {
    float r = in->r * sr;
    float g = in->g * sg;
    float b = in->b * sb;
    float localMin, localMax, avg;
    localMin = fmin(g,b);
    localMin = fmin(r,localMin);
    localMax = fmax(g,b);
    localMax = fmax(r,localMax);
    avg = (localMin+localMax) * 0.5f;
    out->r = out->g = out->b = rsClamp(avg, 0, 255);
}
