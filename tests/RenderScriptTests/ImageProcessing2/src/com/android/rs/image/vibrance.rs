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

float vibrance = 0.f;

static const float Rf = 0.2999f;
static const float Gf = 0.587f;
static const float Bf = 0.114f;

static float S  = 0.f;
static float MS = 0.f;
static float Rt = 0.f;
static float Gt = 0.f;
static float Bt = 0.f;
static float Vib = 0.f;

void vibranceKernel(const uchar4 *in, uchar4 *out) {

    float R, G, B;

    int r = in->r;
    int g = in->g;
    int b = in->b;
    float red = (r-max(g, b))/256.f;
    float sx = (float)(Vib/(1+native_exp(-red*3)));
    S = sx+1;
    MS = 1.0f - S;
    Rt = Rf * MS;
    Gt = Gf * MS;
    Bt = Bf * MS;
    int t = (r + g) / 2;
    R = r;
    G = g;
    B = b;

    float Rc = R * (Rt + S) + G * Gt + B * Bt;
    float Gc = R * Rt + G * (Gt + S) + B * Bt;
    float Bc = R * Rt + G * Gt + B * (Bt + S);

    out->r = rsClamp(Rc, 0, 255);
    out->g = rsClamp(Gc, 0, 255);
    out->b = rsClamp(Bc, 0, 255);

}

void prepareVibrance() {

    Vib = vibrance/100.f;
    S  = Vib + 1;
    MS = 1.0f - S;
    Rt = Rf * MS;
    Gt = Gf * MS;
    Bt = Bf * MS;

}
