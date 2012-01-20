/*
 * Copyright (C) 2011 The Android Open Source Project
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

#include "rsMatrix2x2.h"
#include "rsMatrix3x3.h"
#include "rsMatrix4x4.h"

#include "stdlib.h"
#include "string.h"
#include "math.h"

using namespace android;
using namespace android::renderscript;

//////////////////////////////////////////////////////////////////////////////
// Heavy math functions
//////////////////////////////////////////////////////////////////////////////





// Returns true if the matrix was successfully inversed
bool Matrix4x4::inverse() {
    rs_matrix4x4 result;

    int i, j;
    for (i = 0; i < 4; ++i) {
        for (j = 0; j < 4; ++j) {
            // computeCofactor for int i, int j
            int c0 = (i+1) % 4;
            int c1 = (i+2) % 4;
            int c2 = (i+3) % 4;
            int r0 = (j+1) % 4;
            int r1 = (j+2) % 4;
            int r2 = (j+3) % 4;

            float minor =
                (m[c0 + 4*r0] * (m[c1 + 4*r1] * m[c2 + 4*r2] - m[c1 + 4*r2] * m[c2 + 4*r1]))
                - (m[c0 + 4*r1] * (m[c1 + 4*r0] * m[c2 + 4*r2] - m[c1 + 4*r2] * m[c2 + 4*r0]))
                + (m[c0 + 4*r2] * (m[c1 + 4*r0] * m[c2 + 4*r1] - m[c1 + 4*r1] * m[c2 + 4*r0]));

            float cofactor = (i+j) & 1 ? -minor : minor;

            result.m[4*i + j] = cofactor;
        }
    }

    // Dot product of 0th column of source and 0th row of result
    float det = m[0]*result.m[0] + m[4]*result.m[1] +
                 m[8]*result.m[2] + m[12]*result.m[3];

    if (fabs(det) < 1e-6) {
        return false;
    }

    det = 1.0f / det;
    for (i = 0; i < 16; ++i) {
        m[i] = result.m[i] * det;
    }

    return true;
}

// Returns true if the matrix was successfully inversed
bool Matrix4x4::inverseTranspose() {
    rs_matrix4x4 result;

    int i, j;
    for (i = 0; i < 4; ++i) {
        for (j = 0; j < 4; ++j) {
            // computeCofactor for int i, int j
            int c0 = (i+1) % 4;
            int c1 = (i+2) % 4;
            int c2 = (i+3) % 4;
            int r0 = (j+1) % 4;
            int r1 = (j+2) % 4;
            int r2 = (j+3) % 4;

            float minor = (m[c0 + 4*r0] * (m[c1 + 4*r1] * m[c2 + 4*r2] - m[c1 + 4*r2] * m[c2 + 4*r1]))
                         - (m[c0 + 4*r1] * (m[c1 + 4*r0] * m[c2 + 4*r2] - m[c1 + 4*r2] * m[c2 + 4*r0]))
                         + (m[c0 + 4*r2] * (m[c1 + 4*r0] * m[c2 + 4*r1] - m[c1 + 4*r1] * m[c2 + 4*r0]));

            float cofactor = (i+j) & 1 ? -minor : minor;

            result.m[4*j + i] = cofactor;
        }
    }

    // Dot product of 0th column of source and 0th column of result
    float det = m[0]*result.m[0] + m[4]*result.m[4] +
                 m[8]*result.m[8] + m[12]*result.m[12];

    if (fabs(det) < 1e-6) {
        return false;
    }

    det = 1.0f / det;
    for (i = 0; i < 16; ++i) {
        m[i] = result.m[i] * det;
    }

    return true;
}

void Matrix4x4::transpose() {
    int i, j;
    float temp;
    for (i = 0; i < 3; ++i) {
        for (j = i + 1; j < 4; ++j) {
            temp = m[i*4 + j];
            m[i*4 + j] = m[j*4 + i];
            m[j*4 + i] = temp;
        }
    }
}


///////////////////////////////////////////////////////////////////////////////////

void Matrix4x4::loadIdentity() {
    m[0] = 1.f;
    m[1] = 0.f;
    m[2] = 0.f;
    m[3] = 0.f;
    m[4] = 0.f;
    m[5] = 1.f;
    m[6] = 0.f;
    m[7] = 0.f;
    m[8] = 0.f;
    m[9] = 0.f;
    m[10] = 1.f;
    m[11] = 0.f;
    m[12] = 0.f;
    m[13] = 0.f;
    m[14] = 0.f;
    m[15] = 1.f;
}

void Matrix4x4::load(const float *v) {
    memcpy(m, v, sizeof(m));
}

void Matrix4x4::load(const rs_matrix4x4 *v) {
    memcpy(m, v->m, sizeof(m));
}

void Matrix4x4::load(const rs_matrix3x3 *v) {
    m[0] = v->m[0];
    m[1] = v->m[1];
    m[2] = v->m[2];
    m[3] = 0.f;
    m[4] = v->m[3];
    m[5] = v->m[4];
    m[6] = v->m[5];
    m[7] = 0.f;
    m[8] = v->m[6];
    m[9] = v->m[7];
    m[10] = v->m[8];
    m[11] = 0.f;
    m[12] = 0.f;
    m[13] = 0.f;
    m[14] = 0.f;
    m[15] = 1.f;
}

void Matrix4x4::load(const rs_matrix2x2 *v) {
    m[0] = v->m[0];
    m[1] = v->m[1];
    m[2] = 0.f;
    m[3] = 0.f;
    m[4] = v->m[2];
    m[5] = v->m[3];
    m[6] = 0.f;
    m[7] = 0.f;
    m[8] = 0.f;
    m[9] = 0.f;
    m[10] = 1.f;
    m[11] = 0.f;
    m[12] = 0.f;
    m[13] = 0.f;
    m[14] = 0.f;
    m[15] = 1.f;
}


void Matrix4x4::loadRotate(float rot, float x, float y, float z) {
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

    const float len = x*x + y*y + z*z;
    if (len != 1) {
        const float recipLen = 1.f / sqrtf(len);
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

void Matrix4x4::loadScale(float x, float y, float z) {
    loadIdentity();
    set(0, 0, x);
    set(1, 1, y);
    set(2, 2, z);
}

void Matrix4x4::loadTranslate(float x, float y, float z) {
    loadIdentity();
    m[12] = x;
    m[13] = y;
    m[14] = z;
}

void Matrix4x4::loadMultiply(const rs_matrix4x4 *lhs, const rs_matrix4x4 *rhs) {
    for (int i=0 ; i<4 ; i++) {
        float ri0 = 0;
        float ri1 = 0;
        float ri2 = 0;
        float ri3 = 0;
        for (int j=0 ; j<4 ; j++) {
            const float rhs_ij = ((const Matrix4x4 *)rhs)->get(i,j);
            ri0 += ((const Matrix4x4 *)lhs)->get(j,0) * rhs_ij;
            ri1 += ((const Matrix4x4 *)lhs)->get(j,1) * rhs_ij;
            ri2 += ((const Matrix4x4 *)lhs)->get(j,2) * rhs_ij;
            ri3 += ((const Matrix4x4 *)lhs)->get(j,3) * rhs_ij;
        }
        set(i,0, ri0);
        set(i,1, ri1);
        set(i,2, ri2);
        set(i,3, ri3);
    }
}

void Matrix4x4::loadOrtho(float left, float right, float bottom, float top, float near, float far) {
    loadIdentity();
    m[0] = 2.f / (right - left);
    m[5] = 2.f / (top - bottom);
    m[10]= -2.f / (far - near);
    m[12]= -(right + left) / (right - left);
    m[13]= -(top + bottom) / (top - bottom);
    m[14]= -(far + near) / (far - near);
}

void Matrix4x4::loadFrustum(float left, float right, float bottom, float top, float near, float far) {
    loadIdentity();
    m[0] = 2.f * near / (right - left);
    m[5] = 2.f * near / (top - bottom);
    m[8] = (right + left) / (right - left);
    m[9] = (top + bottom) / (top - bottom);
    m[10]= -(far + near) / (far - near);
    m[11]= -1.f;
    m[14]= -2.f * far * near / (far - near);
    m[15]= 0.f;
}

void Matrix4x4::loadPerspective(float fovy, float aspect, float near, float far) {
    float top = near * tan((float) (fovy * M_PI / 360.0f));
    float bottom = -top;
    float left = bottom * aspect;
    float right = top * aspect;
    loadFrustum(left, right, bottom, top, near, far);
}

void Matrix4x4::vectorMultiply(float *out, const float *in) const {
    out[0] = (m[0] * in[0]) + (m[4] * in[1]) + (m[8] * in[2]) + m[12];
    out[1] = (m[1] * in[0]) + (m[5] * in[1]) + (m[9] * in[2]) + m[13];
    out[2] = (m[2] * in[0]) + (m[6] * in[1]) + (m[10] * in[2]) + m[14];
    out[3] = (m[3] * in[0]) + (m[7] * in[1]) + (m[11] * in[2]) + m[15];
}

void Matrix4x4::logv(const char *s) const {
    ALOGV("%s {%f, %f, %f, %f",  s, m[0], m[4], m[8], m[12]);
    ALOGV("%s  %f, %f, %f, %f",  s, m[1], m[5], m[9], m[13]);
    ALOGV("%s  %f, %f, %f, %f",  s, m[2], m[6], m[10], m[14]);
    ALOGV("%s  %f, %f, %f, %f}", s, m[3], m[7], m[11], m[15]);
}
