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

#ifndef ANDROID_FUSION_H
#define ANDROID_FUSION_H

#include <utils/Errors.h>

#include "vec.h"
#include "mat.h"

namespace android {

class Fusion {
    /*
     * the state vector is made of two sub-vector containing respectively:
     * - modified Rodrigues parameters
     * - the estimated gyro bias
     */
    vec<vec3_t, 2> x;

    /*
     * the predicated covariance matrix is made of 4 3x3 sub-matrices and it
     * semi-definite positive.
     *
     * P = | P00  P10 | = | P00  P10 |
     *     | P01  P11 |   | P10t  Q1 |
     *
     * Since P01 = transpose(P10), the code below never calculates or
     * stores P01. P11 is always equal to Q1, so we don't store it either.
     */
    mat<mat33_t, 2, 2> P;

    /*
     * the process noise covariance matrix is made of 2 3x3 sub-matrices
     * Q0 encodes the attitude's noise
     * Q1 encodes the bias' noise
     */
    vec<mat33_t, 2> Q;

    static const float gyroSTDEV = 1.0e-5;  // rad/s (measured 1.2e-5)
    static const float accSTDEV  = 0.05f;   // m/s^2 (measured 0.08 / CDD 0.05)
    static const float magSTDEV  = 0.5f;    // uT    (measured 0.7  / CDD 0.5)
    static const float biasSTDEV = 2e-9;    // rad/s^2 (guessed)

public:
    Fusion();
    void init();
    void handleGyro(const vec3_t& w, float dT);
    status_t handleAcc(const vec3_t& a);
    status_t handleMag(const vec3_t& m);
    vec3_t getAttitude() const;
    vec3_t getBias() const;
    mat33_t getRotationMatrix() const;
    bool hasEstimate() const;

private:
    vec3_t Ba, Bm;
    uint32_t mInitState;
    vec<vec3_t, 3> mData;
    size_t mCount[3];
    enum { ACC=0x1, MAG=0x2, GYRO=0x4 };
    bool checkInitComplete(int, const vec3_t&);
    bool checkState(const vec3_t& v);
    void predict(const vec3_t& w);
    void update(const vec3_t& z, const vec3_t& Bi, float sigma);
    static mat33_t getF(const vec3_t& p);
    static mat33_t getdFdp(const vec3_t& p, const vec3_t& we);
};

}; // namespace android

#endif // ANDROID_FUSION_H
