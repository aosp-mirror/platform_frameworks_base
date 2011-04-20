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

void Matrix3x3::loadIdentity() {
    m[0] = 1.f;
    m[1] = 0.f;
    m[2] = 0.f;
    m[3] = 0.f;
    m[4] = 1.f;
    m[5] = 0.f;
    m[6] = 0.f;
    m[7] = 0.f;
    m[8] = 1.f;
}

void Matrix3x3::load(const float *v) {
    memcpy(m, v, sizeof(m));
}

void Matrix3x3::load(const rs_matrix3x3 *v) {
    memcpy(m, v->m, sizeof(m));
}

void Matrix3x3::loadMultiply(const rs_matrix3x3 *lhs, const rs_matrix3x3 *rhs) {
    for (int i=0 ; i<3 ; i++) {
        float ri0 = 0;
        float ri1 = 0;
        float ri2 = 0;
        for (int j=0 ; j<3 ; j++) {
            const float rhs_ij = ((const Matrix3x3 *)rhs)->get(i, j);
            ri0 += ((const Matrix3x3 *)lhs)->get(j, 0) * rhs_ij;
            ri1 += ((const Matrix3x3 *)lhs)->get(j, 1) * rhs_ij;
            ri2 += ((const Matrix3x3 *)lhs)->get(j, 2) * rhs_ij;
        }
        set(i, 0, ri0);
        set(i, 1, ri1);
        set(i, 2, ri2);
    }
}

void Matrix3x3::transpose() {
    int i, j;
    float temp;
    for (i = 0; i < 2; ++i) {
        for (j = i + 1; j < 3; ++j) {
            temp = get(i, j);
            set(i, j, get(j, i));
            set(j, i, temp);
        }
    }
}

