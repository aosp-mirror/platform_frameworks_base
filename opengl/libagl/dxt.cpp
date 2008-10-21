/* libs/opengles/dxt.cpp
**
** Copyright 2007, The Android Open Source Project
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

#define TIMING 0

#if TIMING
#include <sys/time.h> // for optimization timing
#include <stdio.h>
#include <stdlib.h>
#endif

#include <GLES/gl.h>
#include <utils/Endian.h>

#include "context.h"

#define TIMING 0

namespace android {

static uint8_t avg23tab[64*64];
static volatile int tables_initialized = 0;

// Definitions below are equivalent to these over the valid range of arguments
//  #define div5(x) ((x)/5)
//  #define div7(x) ((x)/7)

// Use fixed-point to divide by 5 and 7
// 3277 = 2^14/5 + 1
// 2341 = 2^14/7 + 1
#define div5(x) (((x)*3277) >> 14)
#define div7(x) (((x)*2341) >> 14)

// Table with entry [a << 6 | b] = (2*a + b)/3 for 0 <= a,b < 64
#define avg23(x0,x1) avg23tab[((x0) << 6) | (x1)]

// Extract 5/6/5 RGB
#define red(x)   (((x) >> 11) & 0x1f)
#define green(x) (((x) >>  5) & 0x3f)
#define blue(x)  ( (x)        & 0x1f)

/*
 * Convert 5/6/5 RGB (as 3 ints) to 8/8/8
 *
 * Operation count: 8 <<, 0 &, 5 |
 */
inline static int rgb565SepTo888(int r, int g, int b)

{
    return ((((r << 3) | (r >> 2)) << 16) |
            (((g << 2) | (g >> 4)) <<  8) |
             ((b << 3) | (b >> 2)));
}

/*
 * Convert 5/6/5 RGB (as a single 16-bit word) to 8/8/8
 *
 *                   r4r3r2r1 r0g5g4g3 g2g1g0b4 b3b2b1b0   rgb
 *            r4r3r2 r1r0g5g4 g3g2g1g0 b4b3b2b1 b0 0 0 0   rgb << 3
 * r4r3r2r1 r0r4r3r2 g5g4g3g2 g1g0g5g4 b4b3b2b1 b0b4b3b2   desired result
 *
 * Construct the 24-bit RGB word as:
 *
 * r4r3r2r1 r0------ -------- -------- -------- --------  (rgb << 8) & 0xf80000
 *            r4r3r2 -------- -------- -------- --------  (rgb << 3) & 0x070000
 *                   g5g4g3g2 g1g0---- -------- --------  (rgb << 5) & 0x00fc00
 *                                g5g4 -------- --------  (rgb >> 1) & 0x000300
 *                                     b4b3b2b1 b0------  (rgb << 3) & 0x0000f8
 *                                                b4b3b2  (rgb >> 2) & 0x000007
 *
 * Operation count: 5 <<, 6 &, 5 | (n.b. rgb >> 3 is used twice)
 */
inline static int rgb565To888(int rgb)

{
    int rgb3 = rgb >> 3;
    return (((rgb << 8) & 0xf80000) |
            ( rgb3      & 0x070000) |
            ((rgb << 5) & 0x00fc00) |
            ((rgb >> 1) & 0x000300) |
            ( rgb3      & 0x0000f8) |
            ((rgb >> 2) & 0x000007));
}

#if __BYTE_ORDER == __BIG_ENDIAN
static uint32_t swap(uint32_t x) {
    int b0 = (x >> 24) & 0xff;
    int b1 = (x >> 16) & 0xff;
    int b2 = (x >>  8) & 0xff;
    int b3 = (x      ) & 0xff;
    
    return (uint32_t)((b3 << 24) | (b2 << 16) | (b1 << 8) | b0);
}
#endif

static void
init_tables()
{
    if (tables_initialized) {
        return;
    }

    for (int i = 0; i < 64; i++) {
        for (int j = 0; j < 64; j++) {
            int avg = (2*i + j)/3;
            avg23tab[(i << 6) | j] = avg;
        }
    }

    asm volatile ("" : : : "memory");
    tables_initialized = 1;
}

/*
 * Utility to scan a DXT1 compressed texture to determine whether it
 * contains a transparent pixel (color0 < color1, code == 3).  This
 * may be useful if the application lacks information as to whether
 * the true format is GL_COMPRESSED_RGB_S3TC_DXT1_EXT or
 * GL_COMPRESSED_RGBA_S3TC_DXT1_EXT.
 */
bool
DXT1HasAlpha(const GLvoid *data, int width, int height) {    
#if TIMING
    struct timeval start_t, end_t;
    struct timezone tz;
    
    gettimeofday(&start_t, &tz);
#endif

    bool hasAlpha = false;

    int xblocks = (width + 3)/4;
    int yblocks = (height + 3)/4;
    int numblocks = xblocks*yblocks;

    uint32_t const *d32 = (uint32_t *)data;
    for (int b = 0; b < numblocks; b++) {
        uint32_t colors = *d32++;
        
#if __BYTE_ORDER == __BIG_ENDIAN
        colors = swap(colors);
#endif
        
        uint16_t color0 = colors & 0xffff;
        uint16_t color1 = colors >> 16;
        
        if (color0 < color1) {
            // There's no need to endian-swap within 'bits'
            // since we don't care which pixel is the transparent one
            uint32_t bits = *d32++;
            
            // Detect if any (odd, even) pair of bits are '11'
            //      bits: b31 b30 b29 ... b3 b2 b1 b0
            // bits >> 1: b31 b31 b30 ... b4 b3 b2 b1
            //         &: b31 (b31 & b30) (b29 & b28) ... (b2 & b1) (b1 & b0)
            //  & 0x55..:   0 (b31 & b30)       0     ...     0     (b1 & b0)
            if (((bits & (bits >> 1)) & 0x55555555) != 0) {
                hasAlpha = true;
                goto done;
            }
        } else {
            // Skip 4 bytes
            ++d32;
        }
    }
    
 done:
#if TIMING
    gettimeofday(&end_t, &tz);
    long usec = (end_t.tv_sec - start_t.tv_sec)*1000000 +
        (end_t.tv_usec - start_t.tv_usec);
    
    printf("Scanned w=%d h=%d in %ld usec\n", width, height, usec);
#endif
    
    return hasAlpha;
}

static void
decodeDXT1(const GLvoid *data, int width, int height,
           void *surface, int stride,
           bool hasAlpha)
    
{
    init_tables();
    
    uint32_t const *d32 = (uint32_t *)data;
    
    // Color table for the current block
    uint16_t c[4];
    c[0] = c[1] = c[2] = c[3] = 0;
    
    // Specified colors from the previous block
    uint16_t prev_color0 = 0x0000;
    uint16_t prev_color1 = 0x0000;
    
    uint16_t* rowPtr = (uint16_t*)surface;
    for (int base_y = 0; base_y < height; base_y += 4, rowPtr += 4*stride) {
        uint16_t *blockPtr = rowPtr;
        for (int base_x = 0; base_x < width; base_x += 4, blockPtr += 4) {
            uint32_t colors = *d32++;
            uint32_t bits = *d32++;
            
#if __BYTE_ORDER == __BIG_ENDIAN
            colors = swap(colors);
            bits = swap(bits);
#endif
            
            // Raw colors
            uint16_t color0 = colors & 0xffff;
            uint16_t color1 = colors >> 16;
            
            // If the new block has the same base colors as the
            // previous one, we don't need to recompute the color
            // table c[]
            if (color0 != prev_color0 || color1 != prev_color1) {
                // Store raw colors for comparison with next block
                prev_color0 = color0;
                prev_color1 = color1;
                
                int r0 =   red(color0);
                int g0 = green(color0);
                int b0 =  blue(color0);

                int r1 =   red(color1);
                int g1 = green(color1);
                int b1 =  blue(color1);                
                
                if (hasAlpha) {
                    c[0] = (r0 << 11) | ((g0 >> 1) << 6) | (b0 << 1) | 0x1;
                    c[1] = (r1 << 11) | ((g1 >> 1) << 6) | (b1 << 1) | 0x1;
                } else {
                    c[0] = color0;
                    c[1] = color1;
                }
                
                int r2, g2, b2, r3, g3, b3, a3;
                
                int bbits = bits >> 1;
                bool has2 = ((bbits & ~bits) & 0x55555555) != 0;
                bool has3 = ((bbits &  bits) & 0x55555555) != 0;
                
                if (has2 || has3) {
                    if (color0 > color1) {
                        r2 = avg23(r0, r1);
                        g2 = avg23(g0, g1);
                        b2 = avg23(b0, b1);
                        
                        r3 = avg23(r1, r0);
                        g3 = avg23(g1, g0);
                        b3 = avg23(b1, b0);
                        a3 = 1;
                    } else {
                        r2 = (r0 + r1) >> 1;
                        g2 = (g0 + g1) >> 1;
                        b2 = (b0 + b1) >> 1;
                        
                        r3 = g3 = b3 = a3 = 0;
                    }
                    if (hasAlpha) {
                        c[2] = (r2 << 11) | ((g2 >> 1) << 6) |
                            (b2 << 1) | 0x1;
                        c[3] = (r3 << 11) | ((g3 >> 1) << 6) |
                            (b3 << 1) | a3;
                    } else {
                        c[2] = (r2 << 11) | (g2 << 5) | b2;
                        c[3] = (r3 << 11) | (g3 << 5) | b3;
                    }
                }
            }
            
            uint16_t* blockRowPtr = blockPtr;
            for (int y = 0; y < 4; y++, blockRowPtr += stride) {
                // Don't process rows past the botom
                if (base_y + y >= height) {
                    break;
                }
                
                int w = min(width - base_x, 4);
                for (int x = 0; x < w; x++) {
                    int code = bits & 0x3;
                    bits >>= 2;
                    
                    blockRowPtr[x] = c[code];
                }
            }
        }
    }
}
    
// Output data as internalformat=GL_RGBA, type=GL_UNSIGNED_BYTE
static void
decodeDXT3(const GLvoid *data, int width, int height,
           void *surface, int stride)

{
    init_tables();
    
    uint32_t const *d32 = (uint32_t *)data;
    
    // Specified colors from the previous block
    uint16_t prev_color0 = 0x0000;
    uint16_t prev_color1 = 0x0000;

    // Color table for the current block
    uint32_t c[4];
    c[0] = c[1] = c[2] = c[3] = 0;

    uint32_t* rowPtr = (uint32_t*)surface;
    for (int base_y = 0; base_y < height; base_y += 4, rowPtr += 4*stride) {
        uint32_t *blockPtr = rowPtr;
        for (int base_x = 0; base_x < width; base_x += 4, blockPtr += 4) {
            
#if __BYTE_ORDER == __BIG_ENDIAN
            uint32_t alphahi = *d32++;
            uint32_t alphalo = *d32++;
            alphahi = swap(alphahi);
            alphalo = swap(alphalo);
#else
            uint32_t alphalo = *d32++;
            uint32_t alphahi = *d32++;
#endif

            uint32_t colors = *d32++;
            uint32_t bits = *d32++;
            
#if __BYTE_ORDER == __BIG_ENDIAN
            colors = swap(colors);
            bits = swap(bits);
#endif
            
            uint64_t alpha = ((uint64_t)alphahi << 32) | alphalo;

            // Raw colors
            uint16_t color0 = colors & 0xffff;
            uint16_t color1 = colors >> 16;

            // If the new block has the same base colors as the
            // previous one, we don't need to recompute the color
            // table c[]
            if (color0 != prev_color0 || color1 != prev_color1) {
                // Store raw colors for comparison with next block
                prev_color0 = color0;
                prev_color1 = color1;
                
                int bbits = bits >> 1;
                bool has2 = ((bbits & ~bits) & 0x55555555) != 0;
                bool has3 = ((bbits &  bits) & 0x55555555) != 0;
                
                if (has2 || has3) {
                    int r0 =   red(color0);
                    int g0 = green(color0);
                    int b0 =  blue(color0);
                    
                    int r1 =   red(color1);
                    int g1 = green(color1);
                    int b1 =  blue(color1);
                    
                    int r2 = avg23(r0, r1);
                    int g2 = avg23(g0, g1);
                    int b2 = avg23(b0, b1);
                    
                    int r3 = avg23(r1, r0);
                    int g3 = avg23(g1, g0);
                    int b3 = avg23(b1, b0);

                    c[0] = rgb565SepTo888(r0, g0, b0);
                    c[1] = rgb565SepTo888(r1, g1, b1);
                    c[2] = rgb565SepTo888(r2, g2, b2);
                    c[3] = rgb565SepTo888(r3, g3, b3);
                } else {
                    // Convert to 8 bits
                    c[0] = rgb565To888(color0);
                    c[1] = rgb565To888(color1);
                }
            }

            uint32_t* blockRowPtr = blockPtr;
            for (int y = 0; y < 4; y++, blockRowPtr += stride) {
                // Don't process rows past the botom
                if (base_y + y >= height) {
                    break;
                }
                
                int w = min(width - base_x, 4);
                for (int x = 0; x < w; x++) {
                    int a = alpha & 0xf;
                    alpha >>= 4;

                    int code = bits & 0x3;
                    bits >>= 2;

                    blockRowPtr[x] = c[code] | (a << 28) | (a << 24);
                }
            }
        }
    }
}

// Output data as internalformat=GL_RGBA, type=GL_UNSIGNED_BYTE
static void
decodeDXT5(const GLvoid *data, int width, int height,
           void *surface, int stride)

{
    init_tables();
    
    uint32_t const *d32 = (uint32_t *)data;
    
    // Specified alphas from the previous block
    uint8_t prev_alpha0 = 0x00;
    uint8_t prev_alpha1 = 0x00;

    // Specified colors from the previous block
    uint16_t prev_color0 = 0x0000;
     uint16_t prev_color1 = 0x0000;

    // Alpha table for the current block
    uint8_t a[8];
    a[0] = a[1] = a[2] = a[3] = a[4] = a[5] = a[6] = a[7] = 0;

    // Color table for the current block
    uint32_t c[4];
    c[0] = c[1] = c[2] = c[3] = 0;

    int good_a5 = 0;
    int bad_a5 = 0;
    int good_a6 = 0;
    int bad_a6 = 0;
    int good_a7 = 0;
    int bad_a7 = 0;

    uint32_t* rowPtr = (uint32_t*)surface;
    for (int base_y = 0; base_y < height; base_y += 4, rowPtr += 4*stride) {
        uint32_t *blockPtr = rowPtr;
        for (int base_x = 0; base_x < width; base_x += 4, blockPtr += 4) {
            
#if __BYTE_ORDER == __BIG_ENDIAN
            uint32_t alphahi = *d32++;
            uint32_t alphalo = *d32++;
            alphahi = swap(alphahi);
            alphalo = swap(alphalo);
#else
             uint32_t alphalo = *d32++;
             uint32_t alphahi = *d32++;
#endif

            uint32_t colors = *d32++;
            uint32_t bits = *d32++;
            
#if __BYTE_ORDER == __BIG_ENDIANx
            colors = swap(colors);
            bits = swap(bits);
#endif
            
            uint64_t alpha = ((uint64_t)alphahi << 32) | alphalo;
            uint64_t alpha0 = alpha & 0xff;
            alpha >>= 8;
            uint64_t alpha1 = alpha & 0xff;
            alpha >>= 8;

            if (alpha0 != prev_alpha0 || alpha1 != prev_alpha1) {
                prev_alpha0 = alpha0;
                prev_alpha1 = alpha1;
                
                a[0] = alpha0;
                a[1] = alpha1;
                int a01 = alpha0 + alpha1 - 1;
                if (alpha0 > alpha1) {
                    a[2] = div7(6*alpha0 +   alpha1);
                    a[4] = div7(4*alpha0 + 3*alpha1);
                    a[6] = div7(2*alpha0 + 5*alpha1);

                    // Use symmetry to derive half of the values
                    // A few values will be off by 1 (~.5%)
                    // Alternate which values are computed directly
                    // and which are derived to try to reduce bias
                    a[3] = a01 - a[6];
                    a[5] = a01 - a[4];
                    a[7] = a01 - a[2];
                } else {
                    a[2] = div5(4*alpha0 +   alpha1);
                    a[4] = div5(2*alpha0 + 3*alpha1);
                    a[3] = a01 - a[4];
                    a[5] = a01 - a[2];
                    a[6] = 0x00;
                    a[7] = 0xff;
                }
            }

            // Raw colors
            uint16_t color0 = colors & 0xffff;
            uint16_t color1 = colors >> 16;

            // If the new block has the same base colors as the
            // previous one, we don't need to recompute the color
            // table c[]
            if (color0 != prev_color0 || color1 != prev_color1) {
                // Store raw colors for comparison with next block
                prev_color0 = color0;
                prev_color1 = color1;
                
                int bbits = bits >> 1;
                bool has2 = ((bbits & ~bits) & 0x55555555) != 0;
                bool has3 = ((bbits &  bits) & 0x55555555) != 0;
                
                if (has2 || has3) {
                    int r0 =   red(color0);
                    int g0 = green(color0);
                    int b0 =  blue(color0);
                    
                    int r1 =   red(color1);
                    int g1 = green(color1);
                    int b1 =  blue(color1);
                
                    int r2 = avg23(r0, r1);
                    int g2 = avg23(g0, g1);
                    int b2 = avg23(b0, b1);
                    
                    int r3 = avg23(r1, r0);
                    int g3 = avg23(g1, g0);
                    int b3 = avg23(b1, b0);

                    c[0] = rgb565SepTo888(r0, g0, b0);
                    c[1] = rgb565SepTo888(r1, g1, b1);
                    c[2] = rgb565SepTo888(r2, g2, b2);
                    c[3] = rgb565SepTo888(r3, g3, b3);
                } else {
                    // Convert to 8 bits
                    c[0] = rgb565To888(color0);
                    c[1] = rgb565To888(color1);
                }                
            }

            uint32_t* blockRowPtr = blockPtr;
            for (int y = 0; y < 4; y++, blockRowPtr += stride) {
                // Don't process rows past the botom
                if (base_y + y >= height) {
                    break;
                }
                
                int w = min(width - base_x, 4);
                for (int x = 0; x < w; x++) {
                    int acode = alpha & 0x7;
                    alpha >>= 3;

                    int code = bits & 0x3;
                    bits >>= 2;

                    blockRowPtr[x] = c[code] | (a[acode] << 24);
                }
            }
        }
    }
}
   
/*
 * Decode a DXT-compressed texture into memory.  DXT textures consist of
 * a series of 4x4 pixel blocks in left-to-right, top-down order.
 * The number of blocks is given by ceil(width/4)*ceil(height/4).
 *
 * 'data' points to the texture data. 'width' and 'height' indicate the
 * dimensions of the texture.  We assume width and height are >= 0 but
 * do not require them to be powers of 2 or divisible by any factor.
 *
 * The output is written to 'surface' with each scanline separated by
 * 'stride' 2- or 4-byte words.
 *
 * 'format' indicates the type of compression and must be one of the following:
 *
 *   GL_COMPRESSED_RGB_S3TC_DXT1_EXT:
 *      The output is written as 5/6/5 opaque RGB (16 bit words).
 *      8 bytes are read from 'data' for each block.
 *
 *   GL_COMPRESSED_RGBA_S3TC_DXT1_EXT
 *      The output is written as 5/5/5/1 RGBA (16 bit words)
 *      8 bytes are read from 'data' for each block.
 *
 *   GL_COMPRESSED_RGBA_S3TC_DXT3_EXT
 *   GL_COMPRESSED_RGBA_S3TC_DXT5_EXT
 *      The output is written as 8/8/8/8 ARGB (32 bit words)
 *      16 bytes are read from 'data' for each block.
 */
void
decodeDXT(const GLvoid *data, int width, int height,
          void *surface, int stride, int format)
{
#if TIMING
    struct timeval start_t, end_t;
    struct timezone tz;
    
    gettimeofday(&start_t, &tz);
#endif

    switch (format) {
    case GL_COMPRESSED_RGB_S3TC_DXT1_EXT:
        decodeDXT1(data, width, height, surface, stride, false);
        break;
        
    case GL_COMPRESSED_RGBA_S3TC_DXT1_EXT:
        decodeDXT1(data, width, height, surface, stride, true);
        break;
        
    case GL_COMPRESSED_RGBA_S3TC_DXT3_EXT:
        decodeDXT3(data, width, height, surface, stride);
        break;
        
    case GL_COMPRESSED_RGBA_S3TC_DXT5_EXT:
        decodeDXT5(data, width, height, surface, stride);
        break;
    }
    
#if TIMING
    gettimeofday(&end_t, &tz);
    long usec = (end_t.tv_sec - start_t.tv_sec)*1000000 +
        (end_t.tv_usec - start_t.tv_usec);
    
    printf("Loaded w=%d h=%d in %ld usec\n", width, height, usec);
#endif
}

} // namespace android
