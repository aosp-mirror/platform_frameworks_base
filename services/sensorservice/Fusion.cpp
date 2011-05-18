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

#include <stdio.h>

#include <utils/Log.h>

#include "Fusion.h"

namespace android {

// -----------------------------------------------------------------------

template <typename TYPE>
static inline TYPE sqr(TYPE x) {
    return x*x;
}

template <typename T>
static inline T clamp(T v) {
    return v < 0 ? 0 : v;
}

template <typename TYPE, size_t C, size_t R>
static mat<TYPE, R, R> scaleCovariance(
        const mat<TYPE, C, R>& A,
        const mat<TYPE, C, C>& P) {
    // A*P*transpose(A);
    mat<TYPE, R, R> APAt;
    for (size_t r=0 ; r<R ; r++) {
        for (size_t j=r ; j<R ; j++) {
            double apat(0);
            for (size_t c=0 ; c<C ; c++) {
                double v(A[c][r]*P[c][c]*0.5);
                for (size_t k=c+1 ; k<C ; k++)
                    v += A[k][r] * P[c][k];
                apat += 2 * v * A[c][j];
            }
            APAt[j][r] = apat;
            APAt[r][j] = apat;
        }
    }
    return APAt;
}

template <typename TYPE, typename OTHER_TYPE>
static mat<TYPE, 3, 3> crossMatrix(const vec<TYPE, 3>& p, OTHER_TYPE diag) {
    mat<TYPE, 3, 3> r;
    r[0][0] = diag;
    r[1][1] = diag;
    r[2][2] = diag;
    r[0][1] = p.z;
    r[1][0] =-p.z;
    r[0][2] =-p.y;
    r[2][0] = p.y;
    r[1][2] = p.x;
    r[2][1] =-p.x;
    return r;
}

template <typename TYPE>
static mat<TYPE, 3, 3> MRPsToMatrix(const vec<TYPE, 3>& p) {
    mat<TYPE, 3, 3> res(1);
    const mat<TYPE, 3, 3> px(crossMatrix(p, 0));
    const TYPE ptp(dot_product(p,p));
    const TYPE t = 4/sqr(1+ptp);
    res -= t * (1-ptp) * px;
    res += t * 2 * sqr(px);
    return res;
}

template <typename TYPE>
vec<TYPE, 3> matrixToMRPs(const mat<TYPE, 3, 3>& R) {
    // matrix to MRPs
    vec<TYPE, 3> q;
    const float Hx = R[0].x;
    const float My = R[1].y;
    const float Az = R[2].z;
    const float w = 1 / (1 + sqrtf( clamp( Hx + My + Az + 1) * 0.25f ));
    q.x = sqrtf( clamp( Hx - My - Az + 1) * 0.25f ) * w;
    q.y = sqrtf( clamp(-Hx + My - Az + 1) * 0.25f ) * w;
    q.z = sqrtf( clamp(-Hx - My + Az + 1) * 0.25f ) * w;
    q.x = copysignf(q.x, R[2].y - R[1].z);
    q.y = copysignf(q.y, R[0].z - R[2].x);
    q.z = copysignf(q.z, R[1].x - R[0].y);
    return q;
}

template<typename TYPE, size_t SIZE>
class Covariance {
    mat<TYPE, SIZE, SIZE> mSumXX;
    vec<TYPE, SIZE> mSumX;
    size_t mN;
public:
    Covariance() : mSumXX(0.0f), mSumX(0.0f), mN(0) { }
    void update(const vec<TYPE, SIZE>& x) {
        mSumXX += x*transpose(x);
        mSumX  += x;
        mN++;
    }
    mat<TYPE, SIZE, SIZE> operator()() const {
        const float N = 1.0f / mN;
        return mSumXX*N - (mSumX*transpose(mSumX))*(N*N);
    }
    void reset() {
        mN = 0;
        mSumXX = 0;
        mSumX = 0;
    }
    size_t getCount() const {
        return mN;
    }
};

// -----------------------------------------------------------------------

Fusion::Fusion() {
    // process noise covariance matrix
    const float w1 = gyroSTDEV;
    const float w2 = biasSTDEV;
    Q[0] = w1*w1;
    Q[1] = w2*w2;

    Ba.x = 0;
    Ba.y = 0;
    Ba.z = 1;

    Bm.x = 0;
    Bm.y = 1;
    Bm.z = 0;

    init();
}

void Fusion::init() {
    // initial estimate: E{ x(t0) }
    x = 0;

    // initial covariance: Var{ x(t0) }
    P = 0;

    mInitState = 0;
    mCount[0] = 0;
    mCount[1] = 0;
    mCount[2] = 0;
    mData = 0;
}

bool Fusion::hasEstimate() const {
    return (mInitState == (MAG|ACC|GYRO));
}

bool Fusion::checkInitComplete(int what, const vec3_t& d) {
    if (mInitState == (MAG|ACC|GYRO))
        return true;

    if (what == ACC) {
        mData[0] += d * (1/length(d));
        mCount[0]++;
        mInitState |= ACC;
    } else if (what == MAG) {
        mData[1] += d * (1/length(d));
        mCount[1]++;
        mInitState |= MAG;
    } else if (what == GYRO) {
        mData[2] += d;
        mCount[2]++;
        if (mCount[2] == 64) {
            // 64 samples is good enough to estimate the gyro drift and
            // doesn't take too much time.
            mInitState |= GYRO;
        }
    }

    if (mInitState == (MAG|ACC|GYRO)) {
        // Average all the values we collected so far
        mData[0] *= 1.0f/mCount[0];
        mData[1] *= 1.0f/mCount[1];
        mData[2] *= 1.0f/mCount[2];

        // calculate the MRPs from the data collection, this gives us
        // a rough estimate of our initial state
        mat33_t R;
        vec3_t up(mData[0]);
        vec3_t east(cross_product(mData[1], up));
        east *= 1/length(east);
        vec3_t north(cross_product(up, east));
        R << east << north << up;
        x[0] = matrixToMRPs(R);

        // NOTE: we could try to use the average of the gyro data
        // to estimate the initial bias, but this only works if
        // the device is not moving. For now, we don't use that value
        // and start with a bias of 0.
        x[1] = 0;

        // initial covariance
        P = 0;
    }

    return false;
}

void Fusion::handleGyro(const vec3_t& w, float dT) {
    const vec3_t wdT(w * dT);   // rad/s * s -> rad
    if (!checkInitComplete(GYRO, wdT))
        return;

    predict(wdT);
}

status_t Fusion::handleAcc(const vec3_t& a) {
    if (length(a) < 0.981f)
        return BAD_VALUE;

    if (!checkInitComplete(ACC, a))
        return BAD_VALUE;

    // ignore acceleration data if we're close to free-fall
    const float l = 1/length(a);
    update(a*l, Ba, accSTDEV*l);
    return NO_ERROR;
}

status_t Fusion::handleMag(const vec3_t& m) {
    // the geomagnetic-field should be between 30uT and 60uT
    // reject obviously wrong magnetic-fields
    if (length(m) > 100)
        return BAD_VALUE;

    if (!checkInitComplete(MAG, m))
        return BAD_VALUE;

    const vec3_t up( getRotationMatrix() * Ba );
    const vec3_t east( cross_product(m, up) );
    vec3_t north( cross_product(up, east) );

    const float l = 1 / length(north);
    north *= l;

#if 0
    // in practice the magnetic-field sensor is so wrong
    // that there is no point trying to use it to constantly
    // correct the gyro. instead, we use the mag-sensor only when
    // the device points north (just to give us a reference).
    // We're hoping that it'll actually point north, if it doesn't
    // we'll be offset, but at least the instantaneous posture
    // of the device will be correct.

    const float cos_30 = 0.8660254f;
    if (dot_product(north, Bm) < cos_30)
        return BAD_VALUE;
#endif

    update(north, Bm, magSTDEV*l);
    return NO_ERROR;
}

bool Fusion::checkState(const vec3_t& v) {
    if (isnanf(length(v))) {
        LOGW("9-axis fusion diverged. reseting state.");
        P = 0;
        x[1] = 0;
        mInitState = 0;
        mCount[0] = 0;
        mCount[1] = 0;
        mCount[2] = 0;
        mData = 0;
        return false;
    }
    return true;
}

vec3_t Fusion::getAttitude() const {
    return x[0];
}

vec3_t Fusion::getBias() const {
    return x[1];
}

mat33_t Fusion::getRotationMatrix() const {
    return MRPsToMatrix(x[0]);
}

mat33_t Fusion::getF(const vec3_t& p) {
    const float p0 = p.x;
    const float p1 = p.y;
    const float p2 = p.z;

    // f(p, w)
    const float p0p1 = p0*p1;
    const float p0p2 = p0*p2;
    const float p1p2 = p1*p2;
    const float p0p0 = p0*p0;
    const float p1p1 = p1*p1;
    const float p2p2 = p2*p2;
    const float pp = 0.5f * (1 - (p0p0 + p1p1 + p2p2));

    mat33_t F;
    F[0][0] = 0.5f*(p0p0 + pp);
    F[0][1] = 0.5f*(p0p1 + p2);
    F[0][2] = 0.5f*(p0p2 - p1);
    F[1][0] = 0.5f*(p0p1 - p2);
    F[1][1] = 0.5f*(p1p1 + pp);
    F[1][2] = 0.5f*(p1p2 + p0);
    F[2][0] = 0.5f*(p0p2 + p1);
    F[2][1] = 0.5f*(p1p2 - p0);
    F[2][2] = 0.5f*(p2p2 + pp);
    return F;
}

mat33_t Fusion::getdFdp(const vec3_t& p, const vec3_t& we) {

    // dF = | A = df/dp  -F |
    //      |   0         0 |

    mat33_t A;
    A[0][0] = A[1][1] = A[2][2] = 0.5f * (p.x*we.x + p.y*we.y + p.z*we.z);
    A[0][1] = 0.5f * (p.y*we.x - p.x*we.y - we.z);
    A[0][2] = 0.5f * (p.z*we.x - p.x*we.z + we.y);
    A[1][2] = 0.5f * (p.z*we.y - p.y*we.z - we.x);
    A[1][0] = -A[0][1];
    A[2][0] = -A[0][2];
    A[2][1] = -A[1][2];
    return A;
}

void Fusion::predict(const vec3_t& w) {
    // f(p, w)
    vec3_t& p(x[0]);

    // There is a discontinuity at 2.pi, to avoid it we need to switch to
    // the shadow of p when pT.p gets too big.
    const float ptp(dot_product(p,p));
    if (ptp >= 2.0f) {
        p = -p * (1/ptp);
    }

    const mat33_t F(getF(p));

    // compute w with the bias correction:
    //  w_estimated = w - b_estimated
    const vec3_t& b(x[1]);
    const vec3_t we(w - b);

    // prediction
    const vec3_t dX(F*we);

    if (!checkState(dX))
        return;

    p += dX;

    const mat33_t A(getdFdp(p, we));

    // G  = | G0  0 |  =  | -F  0 |
    //      |  0  1 |     |  0  1 |

    // P += A*P + P*At + F*Q*Ft
    const mat33_t AP(A*transpose(P[0][0]));
    const mat33_t PAt(P[0][0]*transpose(A));
    const mat33_t FPSt(F*transpose(P[1][0]));
    const mat33_t PSFt(P[1][0]*transpose(F));
    const mat33_t FQFt(scaleCovariance(F, Q[0]));
    P[0][0] += AP + PAt - FPSt - PSFt + FQFt;
    P[1][0] += A*P[1][0] - F*P[1][1];
    P[1][1] += Q[1];
}

void Fusion::update(const vec3_t& z, const vec3_t& Bi, float sigma) {
    const vec3_t p(x[0]);
    // measured vector in body space: h(p) = A(p)*Bi
    const mat33_t A(MRPsToMatrix(p));
    const vec3_t Bb(A*Bi);

    // Sensitivity matrix H = dh(p)/dp
    // H = [ L 0 ]
    const float ptp(dot_product(p,p));
    const mat33_t px(crossMatrix(p, 0.5f*(ptp-1)));
    const mat33_t ppt(p*transpose(p));
    const mat33_t L((8 / sqr(1+ptp))*crossMatrix(Bb, 0)*(ppt-px));

    // update...
    const mat33_t R(sigma*sigma);
    const mat33_t S(scaleCovariance(L, P[0][0]) + R);
    const mat33_t Si(invert(S));
    const mat33_t LtSi(transpose(L)*Si);

    vec<mat33_t, 2> K;
    K[0] = P[0][0] * LtSi;
    K[1] = transpose(P[1][0])*LtSi;

    const vec3_t e(z - Bb);
    const vec3_t K0e(K[0]*e);
    const vec3_t K1e(K[1]*e);

    if (!checkState(K0e))
        return;

    if (!checkState(K1e))
        return;

    x[0] += K0e;
    x[1] += K1e;

    // P -= K*H*P;
    const mat33_t K0L(K[0] * L);
    const mat33_t K1L(K[1] * L);
    P[0][0] -= K0L*P[0][0];
    P[1][1] -= K1L*P[1][0];
    P[1][0] -= K0L*P[1][0];
}

// -----------------------------------------------------------------------

}; // namespace android

