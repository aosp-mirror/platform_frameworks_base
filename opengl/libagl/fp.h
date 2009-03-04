/* libs/opengles/fp.h
**
** Copyright 2006, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License"); 
** you may not use this file except in compliance with the License. 
** You may obtain a copy of the License at 
**
**     http://www.apache.org/licenses/LICENSE-2.0 
**
** Unless required by applicable law or agreed to in writing, software 
** distributed under the License is distributed on an "AS IS" BASIS, 
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
** See the License for the specific language governing permissions and 
** limitations under the License.
*/

#ifndef ANDROID_OPENGLES_FP_H
#define ANDROID_OPENGLES_FP_H

#include <stdint.h>
#include <stddef.h>
#include <sys/types.h>
#include <math.h>

#include <private/pixelflinger/ggl_context.h>

#include <GLES/gl.h>

#define DEBUG_USE_FLOATS      0

// ----------------------------------------------------------------------------

extern "C" GLfixed gglFloatToFixed(float f) __attribute__((const));

// ----------------------------------------------------------------------------
namespace android {

namespace gl {

        GLfloat fixedToFloat(GLfixed) CONST;

        void    sincosf(GLfloat angle, GLfloat* s, GLfloat* c);
        float   sinef(GLfloat x) CONST;
        float   cosinef(GLfloat x) CONST;

inline bool     cmpf(GLfloat a, GLfloat b) CONST;
inline bool     isZerof(GLfloat) CONST;
inline bool     isOnef(GLfloat) CONST;

inline int      isZeroOrNegativef(GLfloat) CONST;

inline int      exponent(GLfloat) CONST;
inline int32_t  mantissa(GLfloat) CONST;
inline GLfloat  clampToZerof(GLfloat) CONST;
inline GLfloat  reciprocalf(GLfloat) CONST;
inline GLfloat  rsqrtf(GLfloat) CONST;
inline GLfloat  sqrf(GLfloat) CONST;
inline GLfloat  addExpf(GLfloat v, int e) CONST;
inline GLfloat  mul2f(GLfloat v) CONST;
inline GLfloat  div2f(GLfloat v) CONST;
inline GLfloat  absf(GLfloat v) CONST;


/* 
 * float fastexpf(float) : a fast approximation of expf(x)
 *		give somewhat accurate results for -88 <= x <= 88
 *
 * exp(x) = 2^(x/ln(2))
 * we use the properties of float encoding
 * to get a fast 2^ and linear interpolation
 *
 */

inline float fastexpf(float y) __attribute__((const));

inline float fastexpf(float y)
{
	union {
		float	r;
		int32_t	i;
	} u;	

	// 127*ln(2) = 88
	if (y < -88.0f) {
		u.r = 0.0f;
	} else if (y > 88.0f) {
		u.r = INFINITY;
	} else {
		const float kOneOverLogTwo = (1L<<23) / M_LN2;
		const int32_t kExponentBias = 127L<<23;
		const int32_t e = int32_t(y*kOneOverLogTwo);
		u.i = e + kExponentBias;
	}
	
	return u.r;
}


bool cmpf(GLfloat a, GLfloat b) {
#if DEBUG_USE_FLOATS
    return a == b;
#else
    union {
        float       f;
        uint32_t    i;
    } ua, ub;
    ua.f = a;
    ub.f = b;
    return ua.i == ub.i;
#endif
} 

bool isZerof(GLfloat v) {
#if DEBUG_USE_FLOATS
    return v == 0;
#else
    union {
        float       f;
        int32_t     i;
    };
    f = v;
    return (i<<1) == 0;
#endif
}

bool isOnef(GLfloat v) {
    return cmpf(v, 1.0f);
}

int isZeroOrNegativef(GLfloat v) {
#if DEBUG_USE_FLOATS
    return v <= 0;
#else
    union {
        float       f;
        int32_t     i;
    };
    f = v;
    return isZerof(v) | (i>>31);
#endif
}

int exponent(GLfloat v) {
    union {
        float    f;
        uint32_t i;
    };
    f = v;
    return ((i << 1) >> 24) - 127;
}

int32_t mantissa(GLfloat v) {
    union {
        float    f;
        uint32_t i;
    };
    f = v;
    if (!(i&0x7F800000)) return 0;
    const int s = i >> 31;
    i |= (1L<<23);
    i &= ~0xFF000000;
    return s ? -i : i;
}

GLfloat clampToZerof(GLfloat v) {
#if DEBUG_USE_FLOATS
    return v<0 ? 0 : (v>1 ? 1 : v);
#else
    union {
        float       f;
        int32_t     i;
    };
    f = v;
    i &= ~(i>>31);
    return f;
#endif
}

GLfloat reciprocalf(GLfloat v) {
    // XXX: do better
    return 1.0f / v;
}

GLfloat rsqrtf(GLfloat v) {
    // XXX: do better
    return 1.0f / sqrtf(v);
}

GLfloat sqrf(GLfloat v) {
    // XXX: do better
    return v*v;
}

GLfloat addExpf(GLfloat v, int e) {
    union {
        float       f;
        int32_t     i;
    };
    f = v;
    if (i<<1) { // XXX: deal with over/underflow	
        i += int32_t(e)<<23;
    }
    return f;
}

GLfloat mul2f(GLfloat v) {
#if DEBUG_USE_FLOATS
    return v*2;
#else
    return addExpf(v, 1);
#endif
}

GLfloat div2f(GLfloat v) {
#if DEBUG_USE_FLOATS
    return v*0.5f;
#else
    return addExpf(v, -1);
#endif
}

GLfloat  absf(GLfloat v) {
#if DEBUG_USE_FLOATS
    return v<0 ? -v : v;
#else
    union {
        float       f;
        int32_t     i;
    };
    f = v;
    i &= ~0x80000000;
    return f;
#endif
}

};  // namespace gl

// ----------------------------------------------------------------------------
}; // namespace android

#endif // ANDROID_OPENGLES_FP_H

