/* libs/opengles/matrix.h
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

#ifndef ANDROID_OPENGLES_MATRIX_H
#define ANDROID_OPENGLES_MATRIX_H

#include <stdint.h>
#include <stddef.h>
#include <sys/types.h>
#include <utils/Log.h>

#include <private/pixelflinger/ggl_context.h>

#include <GLES/gl.h>

namespace android {

const int OGLES_MODELVIEW_STACK_DEPTH   = 16;
const int OGLES_PROJECTION_STACK_DEPTH  =  2;
const int OGLES_TEXTURE_STACK_DEPTH     =  2;

void ogles_init_matrix(ogles_context_t*);
void ogles_uninit_matrix(ogles_context_t*);
void ogles_invalidate_perspective(ogles_context_t* c);
void ogles_validate_transform_impl(ogles_context_t* c, uint32_t want);

int ogles_surfaceport(ogles_context_t* c, GLint x, GLint y);

void ogles_scissor(ogles_context_t* c, 
        GLint x, GLint y, GLsizei w, GLsizei h);

void ogles_viewport(ogles_context_t* c,
        GLint x, GLint y, GLsizei w, GLsizei h);

inline void ogles_validate_transform(
        ogles_context_t* c, uint32_t want)
{
    if (c->transforms.dirty & want)
        ogles_validate_transform_impl(c, want);
}

// ----------------------------------------------------------------------------

inline
GLfixed vsquare3(GLfixed a, GLfixed b, GLfixed c) 
{
#if defined(__arm__) && !defined(__thumb__)

    GLfixed r;
    int32_t t;
    asm(
        "smull %0, %1, %2, %2       \n"
        "smlal %0, %1, %3, %3       \n"
        "smlal %0, %1, %4, %4       \n"
        "movs  %0, %0, lsr #16      \n"
        "adc   %0, %0, %1, lsl #16  \n"
        :   "=&r"(r), "=&r"(t) 
        :   "%r"(a), "r"(b), "r"(c)
        :   "cc"
        ); 
    return r;

#else

    return ((   int64_t(a)*a +
                int64_t(b)*b +
                int64_t(c)*c + 0x8000)>>16);

#endif
}

static inline GLfixed mla2a( GLfixed a0, GLfixed b0,
                            GLfixed a1, GLfixed b1,
                            GLfixed c)
{
#if defined(__arm__) && !defined(__thumb__)
                            
    GLfixed r;
    int32_t t;
    asm(
        "smull %0, %1, %2, %3       \n"
        "smlal %0, %1, %4, %5       \n"
        "add   %0, %6, %0, lsr #16  \n"
        "add   %0, %0, %1, lsl #16  \n"
        :   "=&r"(r), "=&r"(t) 
        :   "%r"(a0), "r"(b0), 
            "%r"(a1), "r"(b1),
            "r"(c)
        :
        ); 
    return r;
    
#else

    return ((   int64_t(a0)*b0 +
                int64_t(a1)*b1)>>16) + c;

#endif
}

static inline GLfixed mla3a( GLfixed a0, GLfixed b0,
                             GLfixed a1, GLfixed b1,
                             GLfixed a2, GLfixed b2,
                             GLfixed c)
{
#if defined(__arm__) && !defined(__thumb__)
                            
    GLfixed r;
    int32_t t;
    asm(
        "smull %0, %1, %2, %3       \n"
        "smlal %0, %1, %4, %5       \n"
        "smlal %0, %1, %6, %7       \n"
        "add   %0, %8, %0, lsr #16  \n"
        "add   %0, %0, %1, lsl #16  \n"
        :   "=&r"(r), "=&r"(t) 
        :   "%r"(a0), "r"(b0),
            "%r"(a1), "r"(b1),
            "%r"(a2), "r"(b2),
            "r"(c)
        :
        ); 
    return r;
    
#else

    return ((   int64_t(a0)*b0 +
                int64_t(a1)*b1 +
                int64_t(a2)*b2)>>16) + c;

#endif
}

// b0, b1, b2 are signed 16-bit quanities
// that have been shifted right by 'shift' bits relative to normal
// S16.16 fixed point
static inline GLfixed mla3a16( GLfixed a0, int32_t b1b0,
                               GLfixed a1,
                               GLfixed a2, int32_t b2,
                               GLint shift,
                               GLfixed c)
{
#if defined(__arm__) && !defined(__thumb__)
                            
    GLfixed r;
    asm(
        "smulwb %0, %1, %2          \n"
        "smlawt %0, %3, %2, %0      \n" 
        "smlawb %0, %4, %5, %0      \n"
        "add    %0, %7, %0, lsl %6  \n"
        :   "=&r"(r)
        :   "r"(a0), "r"(b1b0),
            "r"(a1),
            "r"(a2), "r"(b2),
            "r"(shift),
            "r"(c)
        :
        ); 
    return r;
    
#else

    int32_t accum;
    int16_t b0 = b1b0 & 0xffff;
    int16_t b1 = (b1b0 >> 16) & 0xffff;
    accum  = int64_t(a0)*int16_t(b0) >> 16;
    accum += int64_t(a1)*int16_t(b1) >> 16;
    accum += int64_t(a2)*int16_t(b2) >> 16;
    accum = (accum << shift) + c;
    return accum;

#endif
}


static inline GLfixed mla3a16_btb( GLfixed a0,
                                   GLfixed a1,
                                   GLfixed a2,
                                   int32_t b1b0, int32_t xxb2,
                                   GLint shift,
                                   GLfixed c)
{
#if defined(__arm__) && !defined(__thumb__)
                            
    GLfixed r;
    asm(
        "smulwb %0, %1, %4          \n"
        "smlawt %0, %2, %4, %0      \n" 
        "smlawb %0, %3, %5, %0      \n"
        "add    %0, %7, %0, lsl %6  \n"
        :   "=&r"(r)
        :   "r"(a0),
            "r"(a1),
            "r"(a2),
            "r"(b1b0), "r"(xxb2),
            "r"(shift),
            "r"(c)
        :
        ); 
    return r;
    
#else

    int32_t accum;
    int16_t b0 =  b1b0        & 0xffff;
    int16_t b1 = (b1b0 >> 16) & 0xffff;
    int16_t b2 =  xxb2        & 0xffff;
    accum  = int64_t(a0)*int16_t(b0) >> 16;
    accum += int64_t(a1)*int16_t(b1) >> 16;
    accum += int64_t(a2)*int16_t(b2) >> 16;
    accum = (accum << shift) + c;
    return accum;

#endif
}

static inline GLfixed mla3a16_btt( GLfixed a0,
                                   GLfixed a1,
                                   GLfixed a2,
                                   int32_t b1b0, int32_t b2xx,
                                   GLint shift,
                                   GLfixed c)
{
#if defined(__arm__) && !defined(__thumb__)
                            
    GLfixed r;
    asm(
        "smulwb %0, %1, %4          \n"
        "smlawt %0, %2, %4, %0      \n" 
        "smlawt %0, %3, %5, %0      \n"
        "add    %0, %7, %0, lsl %6  \n"
        :   "=&r"(r)
        :   "r"(a0),
            "r"(a1),
            "r"(a2),
            "r"(b1b0), "r"(b2xx),
            "r"(shift),
            "r"(c)
        :
        ); 
    return r;
    
#else

    int32_t accum;
    int16_t b0 =  b1b0        & 0xffff;
    int16_t b1 = (b1b0 >> 16) & 0xffff;
    int16_t b2 = (b2xx >> 16) & 0xffff;
    accum  = int64_t(a0)*int16_t(b0) >> 16;
    accum += int64_t(a1)*int16_t(b1) >> 16;
    accum += int64_t(a2)*int16_t(b2) >> 16;
    accum = (accum << shift) + c;
    return accum;

#endif
}

static inline GLfixed mla3( GLfixed a0, GLfixed b0,
                            GLfixed a1, GLfixed b1,
                            GLfixed a2, GLfixed b2)
{
#if defined(__arm__) && !defined(__thumb__)
                            
    GLfixed r;
    int32_t t;
    asm(
        "smull %0, %1, %2, %3       \n"
        "smlal %0, %1, %4, %5       \n"
        "smlal %0, %1, %6, %7       \n"
        "movs  %0, %0, lsr #16      \n"
        "adc   %0, %0, %1, lsl #16  \n"
        :   "=&r"(r), "=&r"(t) 
        :   "%r"(a0), "r"(b0),
            "%r"(a1), "r"(b1),
            "%r"(a2), "r"(b2)
        :   "cc"
        ); 
    return r;
    
#else

    return ((   int64_t(a0)*b0 +
                int64_t(a1)*b1 +
                int64_t(a2)*b2 + 0x8000)>>16);

#endif
}

static inline GLfixed mla4( GLfixed a0, GLfixed b0,
                            GLfixed a1, GLfixed b1,
                            GLfixed a2, GLfixed b2,
                            GLfixed a3, GLfixed b3)
{
#if defined(__arm__) && !defined(__thumb__)
                            
    GLfixed r;
    int32_t t;
    asm(
        "smull %0, %1, %2, %3       \n"
        "smlal %0, %1, %4, %5       \n"
        "smlal %0, %1, %6, %7       \n"
        "smlal %0, %1, %8, %9       \n"
        "movs  %0, %0, lsr #16      \n"
        "adc   %0, %0, %1, lsl #16  \n"
        :   "=&r"(r), "=&r"(t) 
        :   "%r"(a0), "r"(b0),
            "%r"(a1), "r"(b1),
            "%r"(a2), "r"(b2),
            "%r"(a3), "r"(b3)
        :   "cc"
        ); 
    return r;
    
#else

    return ((   int64_t(a0)*b0 +
                int64_t(a1)*b1 +
                int64_t(a2)*b2 +
                int64_t(a3)*b3 + 0x8000)>>16);

#endif
}

inline
GLfixed dot4(const GLfixed* a, const GLfixed* b) 
{
    return mla4(a[0], b[0], a[1], b[1], a[2], b[2], a[3], b[3]);
}


inline
GLfixed dot3(const GLfixed* a, const GLfixed* b) 
{
    return mla3(a[0], b[0], a[1], b[1], a[2], b[2]);
}


}; // namespace android

#endif // ANDROID_OPENGLES_MATRIX_H

