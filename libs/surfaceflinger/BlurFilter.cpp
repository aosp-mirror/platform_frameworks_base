/* 
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


#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <utils/Errors.h>

#include <pixelflinger/pixelflinger.h>

#include "clz.h"

#define LIKELY( exp )       (__builtin_expect( (exp) != 0, true  ))
#define UNLIKELY( exp )     (__builtin_expect( (exp) != 0, false ))

namespace android {

#if BYTE_ORDER == LITTLE_ENDIAN
inline uint32_t BLUR_RGBA_TO_HOST(uint32_t v) {
    return v;
}
inline uint32_t BLUR_HOST_TO_RGBA(uint32_t v) {
    return v;
}
#else
inline uint32_t BLUR_RGBA_TO_HOST(uint32_t v) {
    return (v<<24) | (v>>24) | ((v<<8)&0xff0000) | ((v>>8)&0xff00);
}
inline uint32_t BLUR_HOST_TO_RGBA(uint32_t v) {
    return (v<<24) | (v>>24) | ((v<<8)&0xff0000) | ((v>>8)&0xff00);
}
#endif

const int BLUR_DITHER_BITS = 6;  // dither weights stored on 6 bits
const int BLUR_DITHER_ORDER_SHIFT= 3;
const int BLUR_DITHER_ORDER      = (1<<BLUR_DITHER_ORDER_SHIFT);
const int BLUR_DITHER_SIZE       = BLUR_DITHER_ORDER * BLUR_DITHER_ORDER;
const int BLUR_DITHER_MASK       = BLUR_DITHER_ORDER-1;

static const uint8_t gDitherMatrix[BLUR_DITHER_SIZE] = {
     0, 32,  8, 40,  2, 34, 10, 42,
    48, 16, 56, 24, 50, 18, 58, 26,
    12, 44,  4, 36, 14, 46,  6, 38,
    60, 28, 52, 20, 62, 30, 54, 22,
     3, 35, 11, 43,  1, 33,  9, 41,
    51, 19, 59, 27, 49, 17, 57, 25,
    15, 47,  7, 39, 13, 45,  5, 37,
    63, 31, 55, 23, 61, 29, 53, 21
};


template <int FACTOR = 0>
struct BlurColor565
{
    typedef uint16_t type;
    int r, g, b;    
    inline BlurColor565() { }
    inline BlurColor565(uint16_t v) {
        r = v >> 11;
        g = (v >> 5) & 0x3E;
        b = v & 0x1F;
    }
    inline void clear() { r=g=b=0; }
    inline uint16_t to(int shift, int last, int dither) const {
        int R = r;
        int G = g;
        int B = b;
        if  (UNLIKELY(last)) {
            if (FACTOR>0) {
                int L = (R+G+B)>>1;
                R += (((L>>1) - R) * FACTOR) >> 8;
                G += (((L   ) - G) * FACTOR) >> 8;
                B += (((L>>1) - B) * FACTOR) >> 8;
            }
            R += (dither << shift) >> BLUR_DITHER_BITS;
            G += (dither << shift) >> BLUR_DITHER_BITS;
            B += (dither << shift) >> BLUR_DITHER_BITS;
        }
        R >>= shift;
        G >>= shift;
        B >>= shift;
        return (R<<11) | (G<<5) | B;
    }    
    inline BlurColor565& operator += (const BlurColor565& rhs) {
        r += rhs.r;
        g += rhs.g;
        b += rhs.b;
        return *this;
    }
    inline BlurColor565& operator -= (const BlurColor565& rhs) {
        r -= rhs.r;
        g -= rhs.g;
        b -= rhs.b;
        return *this;
    }
};

template <int FACTOR = 0>
struct BlurColor888X
{
    typedef uint32_t type;
    int r, g, b;    
    inline BlurColor888X() { }
    inline BlurColor888X(uint32_t v) {
        v = BLUR_RGBA_TO_HOST(v);
        r = v & 0xFF;
        g = (v >>  8) & 0xFF;
        b = (v >> 16) & 0xFF;
    }
    inline void clear() { r=g=b=0; }
    inline uint32_t to(int shift, int last, int dither) const {
        int R = r;
        int G = g;
        int B = b;
        if  (UNLIKELY(last)) {
            if (FACTOR>0) {
                int L = (R+G+G+B)>>2;
                R += ((L - R) * FACTOR) >> 8;
                G += ((L - G) * FACTOR) >> 8;
                B += ((L - B) * FACTOR) >> 8;
            }
        }
        R >>= shift;
        G >>= shift;
        B >>= shift;
        return BLUR_HOST_TO_RGBA((0xFF<<24) | (B<<16) | (G<<8) | R);
    }    
    inline BlurColor888X& operator += (const BlurColor888X& rhs) {
        r += rhs.r;
        g += rhs.g;
        b += rhs.b;
        return *this;
    }
    inline BlurColor888X& operator -= (const BlurColor888X& rhs) {
        r -= rhs.r;
        g -= rhs.g;
        b -= rhs.b;
        return *this;
    }
};

struct BlurGray565
{
    typedef uint16_t type;
    int l;    
    inline BlurGray565() { }
    inline BlurGray565(uint16_t v) {
        int r = v >> 11;
        int g = (v >> 5) & 0x3F;
        int b = v & 0x1F;
        l = (r + g + b + 1)>>1;
    }
    inline void clear() { l=0; }
    inline uint16_t to(int shift, int last, int dither) const {
        int L = l;
        if  (UNLIKELY(last)) {
            L += (dither << shift) >> BLUR_DITHER_BITS;
        }
        L >>= shift;
        return ((L>>1)<<11) | (L<<5) | (L>>1);
    }
    inline BlurGray565& operator += (const BlurGray565& rhs) {
        l += rhs.l;
        return *this;
    }
    inline BlurGray565& operator -= (const BlurGray565& rhs) {
        l -= rhs.l;
        return *this;
    }
};

struct BlurGray8888
{
    typedef uint32_t type;
    int l, a;    
    inline BlurGray8888() { }
    inline BlurGray8888(uint32_t v) {
        v = BLUR_RGBA_TO_HOST(v);
        int r = v & 0xFF;
        int g = (v >>  8) & 0xFF;
        int b = (v >> 16) & 0xFF;
        a = v >> 24;
        l = r + g + g + b;
    }    
    inline void clear() { l=a=0; }
    inline uint32_t to(int shift, int last, int dither) const {
        int L = l;
        int A = a;
        if  (UNLIKELY(last)) {
            L += (dither << (shift+2)) >> BLUR_DITHER_BITS;
            A += (dither << shift) >> BLUR_DITHER_BITS;
        }
        L >>= (shift+2);
        A >>= shift;
        return BLUR_HOST_TO_RGBA((A<<24) | (L<<16) | (L<<8) | L);
    }
    inline BlurGray8888& operator += (const BlurGray8888& rhs) {
        l += rhs.l;
        a += rhs.a;
        return *this;
    }
    inline BlurGray8888& operator -= (const BlurGray8888& rhs) {
        l -= rhs.l;
        a -= rhs.a;
        return *this;
    }
};


template<typename PIXEL>
static status_t blurFilter(
        GGLSurface const* dst,
        GGLSurface const* src,
        int kernelSizeUser,
        int repeat)
{
    typedef typename PIXEL::type TYPE;

    const int shift             = 31 - clz(kernelSizeUser);
    const int areaShift         = shift*2;
    const int kernelSize        = 1<<shift;
    const int kernelHalfSize    = kernelSize/2;
    const int mask              = kernelSize-1;
    const int w                 = src->width;
    const int h                 = src->height;
    const uint8_t* ditherMatrix = gDitherMatrix;

    // we need a temporary buffer to store one line of blurred columns
    // as well as kernelSize lines of source pixels organized as a ring buffer.
    void* const temporary_buffer = malloc(
            (w + kernelSize) * sizeof(PIXEL) +
            (src->stride * kernelSize) * sizeof(TYPE));
    if (!temporary_buffer)
        return NO_MEMORY;

    PIXEL* const sums = (PIXEL*)temporary_buffer;
    TYPE* const scratch = (TYPE*)(sums + w + kernelSize);

    // Apply the blur 'repeat' times, this is used to approximate
    // gaussian blurs. 3 times gives good results.
    for (int k=0 ; k<repeat ; k++) {

        // Clear the columns sums for this round
        memset(sums, 0, (w + kernelSize) * sizeof(PIXEL));
        TYPE* head;
        TYPE pixel;
        PIXEL current;

        // Since we're going to override the source data we need
        // to copy it in a temporary buffer. Only kernelSize lines are
        // required. But since we start in the center of the kernel,
        // we only copy half of the data, and fill the rest with zeros
        // (assuming black/transparent pixels).
        memcpy( scratch + src->stride*kernelHalfSize,
                src->data,
                src->stride*kernelHalfSize*sizeof(TYPE));

        // sum half of each column, because we assume the first half is
        // zeros (black/transparent).
        for (int y=0 ; y<kernelHalfSize ; y++) {
            head = (TYPE*)src->data + y*src->stride;
            for (int x=0 ; x<w ; x++)
                sums[x] += PIXEL( *head++ );
        }

        for (int y=0 ; y<h ; y++) {
            TYPE* fb = (TYPE*)dst->data + y*dst->stride;

            // compute the dither matrix line
            uint8_t const * ditherY = ditherMatrix
                    + (y & BLUR_DITHER_MASK)*BLUR_DITHER_ORDER;

            // Horizontal blur pass on the columns sums
            int count, dither, x=0;
            PIXEL const * out= sums;
            PIXEL const * in = sums;
            current.clear();

            count = kernelHalfSize;
            do {
                current += *in;
                in++;
            } while (--count);
            
            count = kernelHalfSize;
            do {
                current += *in;
                dither = *(ditherY + ((x++)&BLUR_DITHER_MASK));
                *fb++ = current.to(areaShift, k==repeat-1, dither);
                in++;
            } while (--count);

            count = w-kernelSize;
            do {
                current += *in;
                current -= *out;
                dither = *(ditherY + ((x++)&BLUR_DITHER_MASK));
                *fb++ = current.to(areaShift, k==repeat-1, dither);
                in++, out++;
            } while (--count);

            count = kernelHalfSize;
            do {
                current -= *out;
                dither = *(ditherY + ((x++)&BLUR_DITHER_MASK));
                *fb++ = current.to(areaShift, k==repeat-1, dither);
                out++;
            } while (--count);

            // vertical blur pass, subtract the oldest line from each columns
            // and add a new line. Subtract or add zeros at the top
            // and bottom edges.
            TYPE* const tail = scratch + (y & mask) * src->stride;
            if (y >= kernelHalfSize) {
                for (int x=0 ; x<w ; x++)
                    sums[x] -= PIXEL( tail[x] );
            }
            if (y < h-kernelSize) {
                memcpy( tail,
                        (TYPE*)src->data + (y+kernelHalfSize)*src->stride,
                        src->stride*sizeof(TYPE));
                for (int x=0 ; x<w ; x++)
                    sums[x] += PIXEL( tail[x] );
            }
        }

        // The subsequent passes are always done in-place.
        src = dst;
    }
    
    free(temporary_buffer);

    return NO_ERROR;
}

template status_t blurFilter< BlurColor565<0x80> >(
        GGLSurface const* dst,
        GGLSurface const* src,
        int kernelSizeUser,
        int repeat);

status_t blurFilter(
        GGLSurface const* image,
        int kernelSizeUser,
        int repeat)
{
    status_t err = BAD_VALUE;
    if (image->format == GGL_PIXEL_FORMAT_RGB_565) {
        err = blurFilter< BlurColor565<0x80> >(image, image, kernelSizeUser, repeat);
    } else if (image->format == GGL_PIXEL_FORMAT_RGBX_8888) {
        err = blurFilter< BlurColor888X<0x80> >(image, image, kernelSizeUser, repeat);
    }
    return err;
}

} // namespace android

//err = blur< BlurColor565<0x80> >(dst, src, kernelSizeUser, repeat);
//err = blur<BlurGray565>(dst, src, kernelSizeUser, repeat);
//err = blur<BlurGray8888>(dst, src, kernelSizeUser, repeat);
