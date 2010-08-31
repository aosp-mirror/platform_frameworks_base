/*
 * Copyright (C) 2010 The Android Open Source Project
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

/* A Fixed point implementation of Fast Fourier Transform (FFT). Complex numbers
 * are represented by 32-bit integers, where higher 16 bits are real part and
 * lower ones are imaginary part. Few compromises are made between efficiency,
 * accuracy, and maintainability. To make it fast, arithmetic shifts are used
 * instead of divisions, and bitwise inverses are used instead of negates. To
 * keep it small, only radix-2 Cooley-Tukey algorithm is implemented, and only
 * half of the twiddle factors are stored. Although there are still ways to make
 * it even faster or smaller, it costs too much on one of the aspects.
 */

#include <stdio.h>
#include <stdint.h>
#ifdef __arm__
#include <machine/cpu-features.h>
#endif

#define LOG_FFT_SIZE 10
#define MAX_FFT_SIZE (1 << LOG_FFT_SIZE)

static const int32_t twiddle[MAX_FFT_SIZE / 4] = {
    0x00008000, 0xff378001, 0xfe6e8002, 0xfda58006, 0xfcdc800a, 0xfc13800f,
    0xfb4a8016, 0xfa81801e, 0xf9b88027, 0xf8ef8032, 0xf827803e, 0xf75e804b,
    0xf6958059, 0xf5cd8068, 0xf5058079, 0xf43c808b, 0xf374809e, 0xf2ac80b2,
    0xf1e480c8, 0xf11c80de, 0xf05580f6, 0xef8d8110, 0xeec6812a, 0xedff8146,
    0xed388163, 0xec718181, 0xebab81a0, 0xeae481c1, 0xea1e81e2, 0xe9588205,
    0xe892822a, 0xe7cd824f, 0xe7078276, 0xe642829d, 0xe57d82c6, 0xe4b982f1,
    0xe3f4831c, 0xe3308349, 0xe26d8377, 0xe1a983a6, 0xe0e683d6, 0xe0238407,
    0xdf61843a, 0xde9e846e, 0xdddc84a3, 0xdd1b84d9, 0xdc598511, 0xdb998549,
    0xdad88583, 0xda1885be, 0xd95885fa, 0xd8988637, 0xd7d98676, 0xd71b86b6,
    0xd65c86f6, 0xd59e8738, 0xd4e1877b, 0xd42487c0, 0xd3678805, 0xd2ab884c,
    0xd1ef8894, 0xd13488dd, 0xd0798927, 0xcfbe8972, 0xcf0489be, 0xce4b8a0c,
    0xcd928a5a, 0xccd98aaa, 0xcc218afb, 0xcb698b4d, 0xcab28ba0, 0xc9fc8bf5,
    0xc9468c4a, 0xc8908ca1, 0xc7db8cf8, 0xc7278d51, 0xc6738dab, 0xc5c08e06,
    0xc50d8e62, 0xc45b8ebf, 0xc3a98f1d, 0xc2f88f7d, 0xc2488fdd, 0xc198903e,
    0xc0e990a1, 0xc03a9105, 0xbf8c9169, 0xbedf91cf, 0xbe329236, 0xbd86929e,
    0xbcda9307, 0xbc2f9371, 0xbb8593dc, 0xbadc9448, 0xba3394b5, 0xb98b9523,
    0xb8e39592, 0xb83c9603, 0xb7969674, 0xb6f196e6, 0xb64c9759, 0xb5a897ce,
    0xb5059843, 0xb46298b9, 0xb3c09930, 0xb31f99a9, 0xb27f9a22, 0xb1df9a9c,
    0xb1409b17, 0xb0a29b94, 0xb0059c11, 0xaf689c8f, 0xaecc9d0e, 0xae319d8e,
    0xad979e0f, 0xacfd9e91, 0xac659f14, 0xabcd9f98, 0xab36a01c, 0xaaa0a0a2,
    0xaa0aa129, 0xa976a1b0, 0xa8e2a238, 0xa84fa2c2, 0xa7bda34c, 0xa72ca3d7,
    0xa69ca463, 0xa60ca4f0, 0xa57ea57e, 0xa4f0a60c, 0xa463a69c, 0xa3d7a72c,
    0xa34ca7bd, 0xa2c2a84f, 0xa238a8e2, 0xa1b0a976, 0xa129aa0a, 0xa0a2aaa0,
    0xa01cab36, 0x9f98abcd, 0x9f14ac65, 0x9e91acfd, 0x9e0fad97, 0x9d8eae31,
    0x9d0eaecc, 0x9c8faf68, 0x9c11b005, 0x9b94b0a2, 0x9b17b140, 0x9a9cb1df,
    0x9a22b27f, 0x99a9b31f, 0x9930b3c0, 0x98b9b462, 0x9843b505, 0x97ceb5a8,
    0x9759b64c, 0x96e6b6f1, 0x9674b796, 0x9603b83c, 0x9592b8e3, 0x9523b98b,
    0x94b5ba33, 0x9448badc, 0x93dcbb85, 0x9371bc2f, 0x9307bcda, 0x929ebd86,
    0x9236be32, 0x91cfbedf, 0x9169bf8c, 0x9105c03a, 0x90a1c0e9, 0x903ec198,
    0x8fddc248, 0x8f7dc2f8, 0x8f1dc3a9, 0x8ebfc45b, 0x8e62c50d, 0x8e06c5c0,
    0x8dabc673, 0x8d51c727, 0x8cf8c7db, 0x8ca1c890, 0x8c4ac946, 0x8bf5c9fc,
    0x8ba0cab2, 0x8b4dcb69, 0x8afbcc21, 0x8aaaccd9, 0x8a5acd92, 0x8a0cce4b,
    0x89becf04, 0x8972cfbe, 0x8927d079, 0x88ddd134, 0x8894d1ef, 0x884cd2ab,
    0x8805d367, 0x87c0d424, 0x877bd4e1, 0x8738d59e, 0x86f6d65c, 0x86b6d71b,
    0x8676d7d9, 0x8637d898, 0x85fad958, 0x85beda18, 0x8583dad8, 0x8549db99,
    0x8511dc59, 0x84d9dd1b, 0x84a3dddc, 0x846ede9e, 0x843adf61, 0x8407e023,
    0x83d6e0e6, 0x83a6e1a9, 0x8377e26d, 0x8349e330, 0x831ce3f4, 0x82f1e4b9,
    0x82c6e57d, 0x829de642, 0x8276e707, 0x824fe7cd, 0x822ae892, 0x8205e958,
    0x81e2ea1e, 0x81c1eae4, 0x81a0ebab, 0x8181ec71, 0x8163ed38, 0x8146edff,
    0x812aeec6, 0x8110ef8d, 0x80f6f055, 0x80def11c, 0x80c8f1e4, 0x80b2f2ac,
    0x809ef374, 0x808bf43c, 0x8079f505, 0x8068f5cd, 0x8059f695, 0x804bf75e,
    0x803ef827, 0x8032f8ef, 0x8027f9b8, 0x801efa81, 0x8016fb4a, 0x800ffc13,
    0x800afcdc, 0x8006fda5, 0x8002fe6e, 0x8001ff37,
};

/* Returns the multiplication of \conj{a} and {b}. */
static inline int32_t mult(int32_t a, int32_t b)
{
#if __ARM_ARCH__ >= 6
    int32_t t = b;
    __asm__("smuad  %0, %0, %1"          : "+r" (t) : "r" (a));
    __asm__("smusdx %0, %0, %1"          : "+r" (b) : "r" (a));
    __asm__("pkhtb  %0, %0, %1, ASR #16" : "+r" (t) : "r" (b));
    return t;
#else
    return (((a >> 16) * (b >> 16) + (int16_t)a * (int16_t)b) & ~0xFFFF) |
        ((((a >> 16) * (int16_t)b - (int16_t)a * (b >> 16)) >> 16) & 0xFFFF);
#endif
}

static inline int32_t half(int32_t a)
{
#if __ARM_ARCH__ >= 6
    __asm__("shadd16 %0, %0, %1" : "+r" (a) : "r" (0));
    return a;
#else
    return ((a >> 1) & ~0x8000) | (a & 0x8000);
#endif
}

void fixed_fft(int n, int32_t *v)
{
    int scale = LOG_FFT_SIZE, i, p, r;

    for (r = 0, i = 1; i < n; ++i) {
        for (p = n; !(p & r); p >>= 1, r ^= p);
        if (i < r) {
            int32_t t = v[i];
            v[i] = v[r];
            v[r] = t;
        }
    }

    for (p = 1; p < n; p <<= 1) {
        --scale;

        for (i = 0; i < n; i += p << 1) {
            int32_t x = half(v[i]);
            int32_t y = half(v[i + p]);
            v[i] = x + y;
            v[i + p] = x - y;
        }

        for (r = 1; r < p; ++r) {
            int32_t w = MAX_FFT_SIZE / 4 - (r << scale);
            i = w >> 31;
            w = twiddle[(w ^ i) - i] ^ (i << 16);
            for (i = r; i < n; i += p << 1) {
                int32_t x = half(v[i]);
                int32_t y = mult(w, v[i + p]);
                v[i] = x - y;
                v[i + p] = x + y;
            }
        }
    }
}

void fixed_fft_real(int n, int32_t *v)
{
    int scale = LOG_FFT_SIZE, m = n >> 1, i;

    fixed_fft(n, v);
    for (i = 1; i <= n; i <<= 1, --scale);
    v[0] = mult(~v[0], 0x80008000);
    v[m] = half(v[m]);

    for (i = 1; i < n >> 1; ++i) {
        int32_t x = half(v[i]);
        int32_t z = half(v[n - i]);
        int32_t y = z - (x ^ 0xFFFF);
        x = half(x + (z ^ 0xFFFF));
        y = mult(y, twiddle[i << scale]);
        v[i] = x - y;
        v[n - i] = (x + y) ^ 0xFFFF;
    }
}
