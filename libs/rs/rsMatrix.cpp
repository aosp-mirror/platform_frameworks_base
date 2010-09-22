/*
 * Copyright (C) 2009 The Android Open Source Project
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

#include "rsMatrix.h"

#include "stdlib.h"
#include "string.h"
#include "math.h"

using namespace android;
using namespace android::renderscript;



void Matrix::loadIdentity()
{
    set(0, 0, 1);
    set(1, 0, 0);
    set(2, 0, 0);
    set(3, 0, 0);

    set(0, 1, 0);
    set(1, 1, 1);
    set(2, 1, 0);
    set(3, 1, 0);

    set(0, 2, 0);
    set(1, 2, 0);
    set(2, 2, 1);
    set(3, 2, 0);

    set(0, 3, 0);
    set(1, 3, 0);
    set(2, 3, 0);
    set(3, 3, 1);
}

void Matrix::load(const float *v)
{
    memcpy(m, v, sizeof(m));
}

void Matrix::load(const Matrix *v)
{
    memcpy(m, v->m, sizeof(m));
}

void Matrix::loadRotate(float rot, float x, float y, float z)
{
    float c, s;
    m[3] = 0;
    m[7] = 0;
    m[11]= 0;
    m[12]= 0;
    m[13]= 0;
    m[14]= 0;
    m[15]= 1;
    rot *= float(M_PI / 180.0f);
    c = cosf(rot);
    s = sinf(rot);

    const float len = sqrtf(x*x + y*y + z*z);
    if (len != 1) {
        const float recipLen = 1.f / len;
        x *= recipLen;
        y *= recipLen;
        z *= recipLen;
    }
    const float nc = 1.0f - c;
    const float xy = x * y;
    const float yz = y * z;
    const float zx = z * x;
    const float xs = x * s;
    const float ys = y * s;
    const float zs = z * s;
    m[ 0] = x*x*nc +  c;
    m[ 4] =  xy*nc - zs;
    m[ 8] =  zx*nc + ys;
    m[ 1] =  xy*nc + zs;
    m[ 5] = y*y*nc +  c;
    m[ 9] =  yz*nc - xs;
    m[ 2] =  zx*nc - ys;
    m[ 6] =  yz*nc + xs;
    m[10] = z*z*nc +  c;
}

void Matrix::loadScale(float x, float y, float z)
{
    loadIdentity();
    m[0] = x;
    m[5] = y;
    m[10] = z;
}

void Matrix::loadTranslate(float x, float y, float z)
{
    loadIdentity();
    m[12] = x;
    m[13] = y;
    m[14] = z;
}

void Matrix::loadMultiply(const Matrix *lhs, const Matrix *rhs)
{
    for (int i=0 ; i<4 ; i++) {
        float ri0 = 0;
        float ri1 = 0;
        float ri2 = 0;
        float ri3 = 0;
        for (int j=0 ; j<4 ; j++) {
            const float rhs_ij = rhs->get(i,j);
            ri0 += lhs->get(j,0) * rhs_ij;
            ri1 += lhs->get(j,1) * rhs_ij;
            ri2 += lhs->get(j,2) * rhs_ij;
            ri3 += lhs->get(j,3) * rhs_ij;
        }
        set(i,0, ri0);
        set(i,1, ri1);
        set(i,2, ri2);
        set(i,3, ri3);
    }
}

void Matrix::loadOrtho(float l, float r, float b, float t, float n, float f) {
    loadIdentity();
    m[0] = 2 / (r - l);
    m[5] = 2 / (t - b);
    m[10]= -2 / (f - n);
    m[12]= -(r + l) / (r - l);
    m[13]= -(t + b) / (t - b);
    m[14]= -(f + n) / (f - n);
}

void Matrix::loadFrustum(float l, float r, float b, float t, float n, float f) {
    loadIdentity();
    m[0] = 2 * n / (r - l);
    m[5] = 2 * n / (t - b);
    m[8] = (r + l) / (r - l);
    m[9] = (t + b) / (t - b);
    m[10]= -(f + n) / (f - n);
    m[11]= -1;
    m[14]= -2*f*n / (f - n);
    m[15]= 0;
}

void Matrix::vectorMultiply(float *out, const float *in) const {
    out[0] = (m[0] * in[0]) + (m[4] * in[1]) + (m[8] * in[2]) + m[12];
    out[1] = (m[1] * in[0]) + (m[5] * in[1]) + (m[9] * in[2]) + m[13];
    out[2] = (m[2] * in[0]) + (m[6] * in[1]) + (m[10] * in[2]) + m[14];
    out[3] = (m[3] * in[0]) + (m[7] * in[1]) + (m[11] * in[2]) + m[15];
}
