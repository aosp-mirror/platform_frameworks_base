/*
 * This implementation of the noise functions was ported from the Java
 * implementation by Jerry Huxtable (http://www.jhlabs.com) under
 * Apache License 2.0 (see http://jhlabs.com/ip/filters/download.html)
 *
 * Original header:
 *
 * Copyright 2006 Jerry Huxtable
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "rsNoise.h"

#include <math.h>
#include <stdlib.h>
#include <time.h>

namespace android {
namespace renderscript {

#define B 0x100
#define BM 0xff
#define N 0x1000

static int p[B + B + 2];
static float g3[B + B + 2][3];
static float g2[B + B + 2][2];
static float g1[B + B + 2];
static bool noise_start = true;

#define lerpf(start, stop, amount) start + (stop - start) * amount

static inline float noise_sCurve(float t)
{
    return t * t * (3.0f - 2.0f * t);
}

inline void SC_normalizef2(float v[])
{
    float s = (float)sqrtf(v[0] * v[0] + v[1] * v[1]);
    v[0] = v[0] / s;
    v[1] = v[1] / s;
}

inline void SC_normalizef3(float v[])
{
    float s = (float)sqrtf(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    v[0] = v[0] / s;
    v[1] = v[1] / s;
    v[2] = v[2] / s;
}

static void noise_init()
{
    int i, j, k;
    
    for (i = 0; i < B; i++) {
        p[i] = i;
        
        g1[i] = (float)((rand() % (B + B)) - B) / B;
        
        for (j = 0; j < 2; j++)
            g2[i][j] = (float)((rand() % (B + B)) - B) / B;
        SC_normalizef2(g2[i]);
        
        for (j = 0; j < 3; j++)
            g3[i][j] = (float)((rand() % (B + B)) - B) / B;
        SC_normalizef3(g3[i]);
    }
    
    for (i = B-1; i >= 0; i--) {
        k = p[i];
        p[i] = p[j = rand() % B];
        p[j] = k;
    }
    
    for (i = 0; i < B + 2; i++) {
        p[B + i] = p[i];
        g1[B + i] = g1[i];
        for (j = 0; j < 2; j++)
            g2[B + i][j] = g2[i][j];
        for (j = 0; j < 3; j++)
            g3[B + i][j] = g3[i][j];
    }
}

float SC_noisef(float x)
{
    srand(time(NULL));
    int bx0, bx1;
    float rx0, rx1, sx, t, u, v;
    
    if (noise_start) {
        noise_start = false;
        noise_init();
    }
    
    t = x + N;
    bx0 = ((int)t) & BM;
    bx1 = (bx0+1) & BM;
    rx0 = t - (int)t;
    rx1 = rx0 - 1.0f;
    
    sx = noise_sCurve(rx0);
    
    u = rx0 * g1[p[bx0]];
    v = rx1 * g1[p[bx1]];
    return 2.3f * lerpf(u, v, sx);
}

float SC_noisef2(float x, float y)
{
    srand(time(NULL));
    int bx0, bx1, by0, by1, b00, b10, b01, b11;
    float rx0, rx1, ry0, ry1, sx, sy, a, b, t, u, v;
    float *q;
    int i, j;
    
    if (noise_start) {
        noise_start = false;
        noise_init();
    }
    
    t = x + N;
    bx0 = ((int)t) & BM;
    bx1 = (bx0+1) & BM;
    rx0 = t - (int)t;
    rx1 = rx0 - 1.0f;
	
    t = y + N;
    by0 = ((int)t) & BM;
    by1 = (by0+1) & BM;
    ry0 = t - (int)t;
    ry1 = ry0 - 1.0f;
	
    i = p[bx0];
    j = p[bx1];
    
    b00 = p[i + by0];
    b10 = p[j + by0];
    b01 = p[i + by1];
    b11 = p[j + by1];
    
    sx = noise_sCurve(rx0);
    sy = noise_sCurve(ry0);
    
    q = g2[b00]; u = rx0 * q[0] + ry0 * q[1];
    q = g2[b10]; v = rx1 * q[0] + ry0 * q[1];
    a = lerpf(u, v, sx);
    
    q = g2[b01]; u = rx0 * q[0] + ry1 * q[1];
    q = g2[b11]; v = rx1 * q[0] + ry1 * q[1];
    b = lerpf(u, v, sx);
    
    return 1.5f*lerpf(a, b, sy);
}

float SC_noisef3(float x, float y, float z)
{
    srand(time(NULL));
    int bx0, bx1, by0, by1, bz0, bz1, b00, b10, b01, b11;
    float rx0, rx1, ry0, ry1, rz0, rz1, sy, sz, a, b, c, d, t, u, v;
    float *q;
    int i, j;
    
    if (noise_start) {
        noise_start = false;
        noise_init();
    }
    
    t = x + N;
    bx0 = ((int)t) & BM;
    bx1 = (bx0+1) & BM;
    rx0 = t - (int)t;
    rx1 = rx0 - 1.0f;
    
    t = y + N;
    by0 = ((int)t) & BM;
    by1 = (by0+1) & BM;
    ry0 = t - (int)t;
    ry1 = ry0 - 1.0f;
	
    t = z + N;
    bz0 = ((int)t) & BM;
    bz1 = (bz0+1) & BM;
    rz0 = t - (int)t;
    rz1 = rz0 - 1.0f;
	
    i = p[bx0];
    j = p[bx1];
    
    b00 = p[i + by0];
    b10 = p[j + by0];
    b01 = p[i + by1];
    b11 = p[j + by1];
    
    t  = noise_sCurve(rx0);
    sy = noise_sCurve(ry0);
    sz = noise_sCurve(rz0);
    
    q = g3[b00 + bz0]; u = rx0 * q[0] + ry0 * q[1] + rz0 * q[2];
    q = g3[b10 + bz0]; v = rx1 * q[0] + ry0 * q[1] + rz0 * q[2];
    a = lerpf(u, v, t);
    
    q = g3[b01 + bz0]; u = rx0 * q[0] + ry1 * q[1] + rz0 * q[2];
    q = g3[b11 + bz0]; v = rx1 * q[0] + ry1 * q[1] + rz0 * q[2];
    b = lerpf(u, v, t);
    
    c = lerpf(a, b, sy);
    
    q = g3[b00 + bz1]; u = rx0 * q[0] + ry0 * q[1] + rz1 * q[2];
    q = g3[b10 + bz1]; v = rx1 * q[0] + ry0 * q[1] + rz1 * q[2];
    a = lerpf(u, v, t);
    
    q = g3[b01 + bz1]; u = rx0 * q[0] + ry1 * q[1] + rz1 * q[2];
    q = g3[b11 + bz1]; v = rx1 * q[0] + ry1 * q[1] + rz1 * q[2];
    b = lerpf(u, v, t);
    
    d = lerpf(a, b, sy);
    
    return 1.5f*lerpf(c, d, sz);
}

float SC_turbulencef2(float x, float y, float octaves)
{
    srand(time(NULL));
    float t = 0.0f;
    
    for (float f = 1.0f; f <= octaves; f *= 2)
        t += fabs(SC_noisef2(f * x, f * y)) / f;
    return t;
}

float SC_turbulencef3(float x, float y, float z, float octaves)
{
    srand(time(NULL));
    float t = 0.0f;
    
    for (float f = 1.0f; f <= octaves; f *= 2)
        t += fabs(SC_noisef3(f * x, f * y, f * z)) / f;
    return t;
}

}
}
