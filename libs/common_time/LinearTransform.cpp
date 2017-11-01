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

#define __STDC_LIMIT_MACROS

#include "LinearTransform.h"
#include <assert.h>


// disable sanitize as these functions may intentionally overflow (see comments below).
// the ifdef can be removed when host builds use clang.
#if defined(__clang__)
#define ATTRIBUTE_NO_SANITIZE_INTEGER __attribute__((no_sanitize("integer")))
#else
#define ATTRIBUTE_NO_SANITIZE_INTEGER
#endif

namespace android {

// sanitize failure with T = int32_t and x = 0x80000000
template<class T>
ATTRIBUTE_NO_SANITIZE_INTEGER
static inline T ABS(T x) { return (x < 0) ? -x : x; }

// Static math methods involving linear transformations
// remote sanitize failure on overflow case.
ATTRIBUTE_NO_SANITIZE_INTEGER
static bool scale_u64_to_u64(
        uint64_t val,
        uint32_t N,
        uint32_t D,
        uint64_t* res,
        bool round_up_not_down) {
    uint64_t tmp1, tmp2;
    uint32_t r;

    assert(res);
    assert(D);

    // Let U32(X) denote a uint32_t containing the upper 32 bits of a 64 bit
    // integer X.
    // Let L32(X) denote a uint32_t containing the lower 32 bits of a 64 bit
    // integer X.
    // Let X[A, B] with A <= B denote bits A through B of the integer X.
    // Let (A | B) denote the concatination of two 32 bit ints, A and B.
    // IOW X = (A | B) => U32(X) == A && L32(X) == B
    //
    // compute M = val * N (a 96 bit int)
    // ---------------------------------
    // tmp2 = U32(val) * N (a 64 bit int)
    // tmp1 = L32(val) * N (a 64 bit int)
    // which means
    // M = val * N = (tmp2 << 32) + tmp1
    tmp2 = (val >> 32) * N;
    tmp1 = (val & UINT32_MAX) * N;

    // compute M[32, 95]
    // tmp2 = tmp2 + U32(tmp1)
    //      = (U32(val) * N) + U32(L32(val) * N)
    //      = M[32, 95]
    tmp2 += tmp1 >> 32;

    // if M[64, 95] >= D, then M/D has bits > 63 set and we have
    // an overflow.
    if ((tmp2 >> 32) >= D) {
        *res = UINT64_MAX;
        return false;
    }

    // Divide.  Going in we know
    // tmp2 = M[32, 95]
    // U32(tmp2) < D
    r = tmp2 % D;
    tmp2 /= D;

    // At this point
    // tmp1      = L32(val) * N
    // tmp2      = M[32, 95] / D
    //           = (M / D)[32, 95]
    // r         = M[32, 95] % D
    // U32(tmp2) = 0
    //
    // compute tmp1 = (r | M[0, 31])
    tmp1 = (tmp1 & UINT32_MAX) | ((uint64_t)r << 32);

    // Divide again.  Keep the remainder around in order to round properly.
    r = tmp1 % D;
    tmp1 /= D;

    // At this point
    // tmp2      = (M / D)[32, 95]
    // tmp1      = (M / D)[ 0, 31]
    // r         =  M % D
    // U32(tmp1) = 0
    // U32(tmp2) = 0

    // Pack the result and deal with the round-up case (As well as the
    // remote possiblility over overflow in such a case).
    *res = (tmp2 << 32) | tmp1;
    if (r && round_up_not_down) {
        ++(*res);
        if (!(*res)) {
            *res = UINT64_MAX;
            return false;
        }
    }

    return true;
}

// at least one known sanitize failure (see comment below)
ATTRIBUTE_NO_SANITIZE_INTEGER
static bool linear_transform_s64_to_s64(
        int64_t  val,
        int64_t  basis1,
        int32_t  N,
        uint32_t D,
        bool     invert_frac,
        int64_t  basis2,
        int64_t* out) {
    uint64_t scaled, res;
    uint64_t abs_val;
    bool is_neg;

    if (!out)
        return false;

    // Compute abs(val - basis_64). Keep track of whether or not this delta
    // will be negative after the scale opertaion.
    if (val < basis1) {
        is_neg = true;
        abs_val = basis1 - val;
    } else {
        is_neg = false;
        abs_val = val - basis1;
    }

    if (N < 0)
        is_neg = !is_neg;

    if (!scale_u64_to_u64(abs_val,
                          invert_frac ? D : ABS(N),
                          invert_frac ? ABS(N) : D,
                          &scaled,
                          is_neg))
        return false; // overflow/undeflow

    // if scaled is >= 0x8000<etc>, then we are going to overflow or
    // underflow unless ABS(basis2) is large enough to pull us back into the
    // non-overflow/underflow region.
    if (scaled & INT64_MIN) {
        if (is_neg && (basis2 < 0))
            return false; // certain underflow

        if (!is_neg && (basis2 >= 0))
            return false; // certain overflow

        if (ABS(basis2) <= static_cast<int64_t>(scaled & INT64_MAX))
            return false; // not enough

        // Looks like we are OK
        *out = (is_neg ? (-scaled) : scaled) + basis2;
    } else {
        // Scaled fits within signed bounds, so we just need to check for
        // over/underflow for two signed integers.  Basically, if both scaled
        // and basis2 have the same sign bit, and the result has a different
        // sign bit, then we have under/overflow.  An easy way to compute this
        // is
        // (scaled_signbit XNOR basis_signbit) &&
        // (scaled_signbit XOR res_signbit)
        // ==
        // (scaled_signbit XOR basis_signbit XOR 1) &&
        // (scaled_signbit XOR res_signbit)

        if (is_neg)
            scaled = -scaled; // known sanitize failure
        res = scaled + basis2;

        if ((scaled ^ basis2 ^ INT64_MIN) & (scaled ^ res) & INT64_MIN)
            return false;

        *out = res;
    }

    return true;
}

bool LinearTransform::doForwardTransform(int64_t a_in, int64_t* b_out) const {
    if (0 == a_to_b_denom)
        return false;

    return linear_transform_s64_to_s64(a_in,
                                       a_zero,
                                       a_to_b_numer,
                                       a_to_b_denom,
                                       false,
                                       b_zero,
                                       b_out);
}

bool LinearTransform::doReverseTransform(int64_t b_in, int64_t* a_out) const {
    if (0 == a_to_b_numer)
        return false;

    return linear_transform_s64_to_s64(b_in,
                                       b_zero,
                                       a_to_b_numer,
                                       a_to_b_denom,
                                       true,
                                       a_zero,
                                       a_out);
}

template <class T> void LinearTransform::reduce(T* N, T* D) {
    T a, b;
    if (!N || !D || !(*D)) {
        assert(false);
        return;
    }

    a = *N;
    b = *D;

    if (a == 0) {
        *D = 1;
        return;
    }

    // This implements Euclid's method to find GCD.
    if (a < b) {
        T tmp = a;
        a = b;
        b = tmp;
    }

    while (1) {
        // a is now the greater of the two.
        const T remainder = a % b;
        if (remainder == 0) {
            *N /= b;
            *D /= b;
            return;
        }
        // by swapping remainder and b, we are guaranteeing that a is
        // still the greater of the two upon entrance to the loop.
        a = b;
        b = remainder;
    }
};

template void LinearTransform::reduce<uint64_t>(uint64_t* N, uint64_t* D);
template void LinearTransform::reduce<uint32_t>(uint32_t* N, uint32_t* D);

// sanitize failure if *N = 0x80000000
ATTRIBUTE_NO_SANITIZE_INTEGER
void LinearTransform::reduce(int32_t* N, uint32_t* D) {
    if (N && D && *D) {
        if (*N < 0) {
            *N = -(*N);
            reduce(reinterpret_cast<uint32_t*>(N), D);
            *N = -(*N);
        } else {
            reduce(reinterpret_cast<uint32_t*>(N), D);
        }
    }
}

}  // namespace android
