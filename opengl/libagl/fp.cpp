/* libs/opengles/fp.cpp
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

#include "fp.h"

// ----------------------------------------------------------------------------

#if !defined(__arm__)
GGLfixed gglFloatToFixed(float v) {   
    return GGLfixed(floorf(v * 65536.0f + 0.5f));
}
#endif

// ----------------------------------------------------------------------------

namespace android {

namespace gl {

GLfloat fixedToFloat(GLfixed x)
{
#if DEBUG_USE_FLOATS
    return x / 65536.0f;
#else
    if (!x) return 0;
    const uint32_t s = x & 0x80000000;
    union {
        uint32_t i;
        float f;
    };
    i = s ? -x : x;
    const int c = gglClz(i) - 8;
    i = (c>=0) ? (i<<c) : (i>>-c);
    const uint32_t e = 134 - c;
    i &= ~0x800000;
    i |= e<<23;
    i |= s;
    return f;
#endif
}

float sinef(float x)
{
    const float A =   1.0f / (2.0f*M_PI);
    const float B = -16.0f;
    const float C =   8.0f;

    // scale angle for easy argument reduction
    x *= A;
    
    if (fabsf(x) >= 0.5f) {
        // Argument reduction
        x = x - ceilf(x + 0.5f) + 1.0f; 
    }

    const float y = B*x*fabsf(x) + C*x;
    return 0.2215f * (y*fabsf(y) - y) + y;
}

float cosinef(float x)
{
    return sinef(x + float(M_PI/2));
}

void sincosf(GLfloat angle, GLfloat* s, GLfloat* c) {
    *s = sinef(angle);
    *c = cosinef(angle);
}

}; // namespace fp_utils

// ----------------------------------------------------------------------------
}; // namespace android
