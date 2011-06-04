/*
 * Copyright (C) 2009 The Android Open Source Project
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

/*------------------------------------------------------------------------------

    Table of contents

     1. Include headers
     2. External compiler flags
     3. Module defines
     4. Local function prototypes
     5. Functions

------------------------------------------------------------------------------*/

/*------------------------------------------------------------------------------
    1. Include headers
------------------------------------------------------------------------------*/

#include "basetype.h"
#include "h264bsd_reconstruct.h"
#include "h264bsd_macroblock_layer.h"
#include "h264bsd_image.h"
#include "h264bsd_util.h"

#ifdef H264DEC_OMXDL
#include "omxtypes.h"
#include "omxVC.h"
#include "armVC.h"
#endif /* H264DEC_OMXDL */

/*------------------------------------------------------------------------------
    2. External compiler flags
--------------------------------------------------------------------------------

--------------------------------------------------------------------------------
    3. Module defines
------------------------------------------------------------------------------*/

/* Switch off the following Lint messages for this file:
 * Info 701: Shift left of signed quantity (int)
 * Info 702: Shift right of signed quantity (int)
 */
/*lint -e701 -e702 */

/* Luma fractional-sample positions
 *
 *  G a b c H
 *  d e f g
 *  h i j k m
 *  n p q r
 *  M   s   N
 *
 *  G, H, M and N are integer sample positions
 *  a-s are fractional samples that need to be interpolated.
 */
#ifndef H264DEC_OMXDL
static const u32 lumaFracPos[4][4] = {
  /* G  d  h  n    a  e  i  p    b  f  j   q     c   g   k   r */
    {0, 1, 2, 3}, {4, 5, 6, 7}, {8, 9, 10, 11}, {12, 13, 14, 15}};
#endif /* H264DEC_OMXDL */

/* clipping table, defined in h264bsd_intra_prediction.c */
extern const u8 h264bsdClip[];

/*------------------------------------------------------------------------------
    4. Local function prototypes
------------------------------------------------------------------------------*/

#ifndef H264DEC_OMXDL

/*------------------------------------------------------------------------------

    Function: h264bsdInterpolateChromaHor

        Functional description:
          This function performs chroma interpolation in horizontal direction.
          Overfilling is done only if needed. Reference image (pRef) is
          read at correct position and the predicted part is written to
          macroblock's chrominance (predPartChroma)
        Inputs:
          pRef              pointer to reference frame Cb top-left corner
          x0                integer x-coordinate for prediction
          y0                integer y-coordinate for prediction
          width             width of the reference frame chrominance in pixels
          height            height of the reference frame chrominance in pixels
          xFrac             horizontal fraction for prediction in 1/8 pixels
          chromaPartWidth   width of the predicted part in pixels
          chromaPartHeight  height of the predicted part in pixels
        Outputs:
          predPartChroma    pointer where predicted part is written

------------------------------------------------------------------------------*/
#ifndef H264DEC_ARM11
void h264bsdInterpolateChromaHor(
  u8 *pRef,
  u8 *predPartChroma,
  i32 x0,
  i32 y0,
  u32 width,
  u32 height,
  u32 xFrac,
  u32 chromaPartWidth,
  u32 chromaPartHeight)
{

/* Variables */

    u32 x, y, tmp1, tmp2, tmp3, tmp4, c, val;
    u8 *ptrA, *cbr;
    u32 comp;
    u8 block[9*8*2];

/* Code */

    ASSERT(predPartChroma);
    ASSERT(chromaPartWidth);
    ASSERT(chromaPartHeight);
    ASSERT(xFrac < 8);
    ASSERT(pRef);

    if ((x0 < 0) || ((u32)x0+chromaPartWidth+1 > width) ||
        (y0 < 0) || ((u32)y0+chromaPartHeight > height))
    {
        h264bsdFillBlock(pRef, block, x0, y0, width, height,
            chromaPartWidth + 1, chromaPartHeight, chromaPartWidth + 1);
        pRef += width * height;
        h264bsdFillBlock(pRef, block + (chromaPartWidth+1)*chromaPartHeight,
            x0, y0, width, height, chromaPartWidth + 1,
            chromaPartHeight, chromaPartWidth + 1);

        pRef = block;
        x0 = 0;
        y0 = 0;
        width = chromaPartWidth+1;
        height = chromaPartHeight;
    }

    val = 8 - xFrac;

    for (comp = 0; comp <= 1; comp++)
    {

        ptrA = pRef + (comp * height + (u32)y0) * width + x0;
        cbr = predPartChroma + comp * 8 * 8;

        /* 2x2 pels per iteration
         * bilinear horizontal interpolation */
        for (y = (chromaPartHeight >> 1); y; y--)
        {
            for (x = (chromaPartWidth >> 1); x; x--)
            {
                tmp1 = ptrA[width];
                tmp2 = *ptrA++;
                tmp3 = ptrA[width];
                tmp4 = *ptrA++;
                c = ((val * tmp1 + xFrac * tmp3) << 3) + 32;
                c >>= 6;
                cbr[8] = (u8)c;
                c = ((val * tmp2 + xFrac * tmp4) << 3) + 32;
                c >>= 6;
                *cbr++ = (u8)c;
                tmp1 = ptrA[width];
                tmp2 = *ptrA;
                c = ((val * tmp3 + xFrac * tmp1) << 3) + 32;
                c >>= 6;
                cbr[8] = (u8)c;
                c = ((val * tmp4 + xFrac * tmp2) << 3) + 32;
                c >>= 6;
                *cbr++ = (u8)c;
            }
            cbr += 2*8 - chromaPartWidth;
            ptrA += 2*width - chromaPartWidth;
        }
    }

}

/*------------------------------------------------------------------------------

    Function: h264bsdInterpolateChromaVer

        Functional description:
          This function performs chroma interpolation in vertical direction.
          Overfilling is done only if needed. Reference image (pRef) is
          read at correct position and the predicted part is written to
          macroblock's chrominance (predPartChroma)

------------------------------------------------------------------------------*/

void h264bsdInterpolateChromaVer(
  u8 *pRef,
  u8 *predPartChroma,
  i32 x0,
  i32 y0,
  u32 width,
  u32 height,
  u32 yFrac,
  u32 chromaPartWidth,
  u32 chromaPartHeight)
{

/* Variables */

    u32 x, y, tmp1, tmp2, tmp3, c, val;
    u8 *ptrA, *cbr;
    u32 comp;
    u8 block[9*8*2];

/* Code */

    ASSERT(predPartChroma);
    ASSERT(chromaPartWidth);
    ASSERT(chromaPartHeight);
    ASSERT(yFrac < 8);
    ASSERT(pRef);

    if ((x0 < 0) || ((u32)x0+chromaPartWidth > width) ||
        (y0 < 0) || ((u32)y0+chromaPartHeight+1 > height))
    {
        h264bsdFillBlock(pRef, block, x0, y0, width, height, chromaPartWidth,
            chromaPartHeight + 1, chromaPartWidth);
        pRef += width * height;
        h264bsdFillBlock(pRef, block + chromaPartWidth*(chromaPartHeight+1),
            x0, y0, width, height, chromaPartWidth,
            chromaPartHeight + 1, chromaPartWidth);

        pRef = block;
        x0 = 0;
        y0 = 0;
        width = chromaPartWidth;
        height = chromaPartHeight+1;
    }

    val = 8 - yFrac;

    for (comp = 0; comp <= 1; comp++)
    {

        ptrA = pRef + (comp * height + (u32)y0) * width + x0;
        cbr = predPartChroma + comp * 8 * 8;

        /* 2x2 pels per iteration
         * bilinear vertical interpolation */
        for (y = (chromaPartHeight >> 1); y; y--)
        {
            for (x = (chromaPartWidth >> 1); x; x--)
            {
                tmp3 = ptrA[width*2];
                tmp2 = ptrA[width];
                tmp1 = *ptrA++;
                c = ((val * tmp2 + yFrac * tmp3) << 3) + 32;
                c >>= 6;
                cbr[8] = (u8)c;
                c = ((val * tmp1 + yFrac * tmp2) << 3) + 32;
                c >>= 6;
                *cbr++ = (u8)c;
                tmp3 = ptrA[width*2];
                tmp2 = ptrA[width];
                tmp1 = *ptrA++;
                c = ((val * tmp2 + yFrac * tmp3) << 3) + 32;
                c >>= 6;
                cbr[8] = (u8)c;
                c = ((val * tmp1 + yFrac * tmp2) << 3) + 32;
                c >>= 6;
                *cbr++ = (u8)c;
            }
            cbr += 2*8 - chromaPartWidth;
            ptrA += 2*width - chromaPartWidth;
        }
    }

}
#endif
/*------------------------------------------------------------------------------

    Function: h264bsdInterpolateChromaHorVer

        Functional description:
          This function performs chroma interpolation in horizontal and
          vertical direction. Overfilling is done only if needed. Reference
          image (ref) is read at correct position and the predicted part
          is written to macroblock's chrominance (predPartChroma)

------------------------------------------------------------------------------*/

void h264bsdInterpolateChromaHorVer(
  u8 *ref,
  u8 *predPartChroma,
  i32 x0,
  i32 y0,
  u32 width,
  u32 height,
  u32 xFrac,
  u32 yFrac,
  u32 chromaPartWidth,
  u32 chromaPartHeight)
{
    u8 block[9*9*2];
    u32 x, y, tmp1, tmp2, tmp3, tmp4, tmp5, tmp6, valX, valY, plus32 = 32;
    u32 comp;
    u8 *ptrA, *cbr;

/* Code */

    ASSERT(predPartChroma);
    ASSERT(chromaPartWidth);
    ASSERT(chromaPartHeight);
    ASSERT(xFrac < 8);
    ASSERT(yFrac < 8);
    ASSERT(ref);

    if ((x0 < 0) || ((u32)x0+chromaPartWidth+1 > width) ||
        (y0 < 0) || ((u32)y0+chromaPartHeight+1 > height))
    {
        h264bsdFillBlock(ref, block, x0, y0, width, height,
            chromaPartWidth + 1, chromaPartHeight + 1, chromaPartWidth + 1);
        ref += width * height;
        h264bsdFillBlock(ref, block + (chromaPartWidth+1)*(chromaPartHeight+1),
            x0, y0, width, height, chromaPartWidth + 1,
            chromaPartHeight + 1, chromaPartWidth + 1);

        ref = block;
        x0 = 0;
        y0 = 0;
        width = chromaPartWidth+1;
        height = chromaPartHeight+1;
    }

    valX = 8 - xFrac;
    valY = 8 - yFrac;

    for (comp = 0; comp <= 1; comp++)
    {

        ptrA = ref + (comp * height + (u32)y0) * width + x0;
        cbr = predPartChroma + comp * 8 * 8;

        /* 2x2 pels per iteration
         * bilinear vertical and horizontal interpolation */
        for (y = (chromaPartHeight >> 1); y; y--)
        {
            tmp1 = *ptrA;
            tmp3 = ptrA[width];
            tmp5 = ptrA[width*2];
            tmp1 *= valY;
            tmp1 += tmp3 * yFrac;
            tmp3 *= valY;
            tmp3 += tmp5 * yFrac;
            for (x = (chromaPartWidth >> 1); x; x--)
            {
                tmp2 = *++ptrA;
                tmp4 = ptrA[width];
                tmp6 = ptrA[width*2];
                tmp2 *= valY;
                tmp2 += tmp4 * yFrac;
                tmp4 *= valY;
                tmp4 += tmp6 * yFrac;
                tmp1 = tmp1 * valX + plus32;
                tmp3 = tmp3 * valX + plus32;
                tmp1 += tmp2 * xFrac;
                tmp1 >>= 6;
                tmp3 += tmp4 * xFrac;
                tmp3 >>= 6;
                cbr[8] = (u8)tmp3;
                *cbr++ = (u8)tmp1;

                tmp1 = *++ptrA;
                tmp3 = ptrA[width];
                tmp5 = ptrA[width*2];
                tmp1 *= valY;
                tmp1 += tmp3 * yFrac;
                tmp3 *= valY;
                tmp3 += tmp5 * yFrac;
                tmp2 = tmp2 * valX + plus32;
                tmp4 = tmp4 * valX + plus32;
                tmp2 += tmp1 * xFrac;
                tmp2 >>= 6;
                tmp4 += tmp3 * xFrac;
                tmp4 >>= 6;
                cbr[8] = (u8)tmp4;
                *cbr++ = (u8)tmp2;
            }
            cbr += 2*8 - chromaPartWidth;
            ptrA += 2*width - chromaPartWidth;
        }
    }

}

/*------------------------------------------------------------------------------

    Function: PredictChroma

        Functional description:
          Top level chroma prediction function that calls the appropriate
          interpolation function. The output is written to macroblock array.

------------------------------------------------------------------------------*/

static void PredictChroma(
  u8 *mbPartChroma,
  u32 xAL,
  u32 yAL,
  u32 partWidth,
  u32 partHeight,
  mv_t *mv,
  image_t *refPic)
{

/* Variables */

    u32 xFrac, yFrac, width, height, chromaPartWidth, chromaPartHeight;
    i32 xInt, yInt;
    u8 *ref;

/* Code */

    ASSERT(mv);
    ASSERT(refPic);
    ASSERT(refPic->data);
    ASSERT(refPic->width);
    ASSERT(refPic->height);

    width  = 8 * refPic->width;
    height = 8 * refPic->height;

    xInt = (xAL >> 1) + (mv->hor >> 3);
    yInt = (yAL >> 1) + (mv->ver >> 3);
    xFrac = mv->hor & 0x7;
    yFrac = mv->ver & 0x7;

    chromaPartWidth  = partWidth >> 1;
    chromaPartHeight = partHeight >> 1;
    ref = refPic->data + 256 * refPic->width * refPic->height;

    if (xFrac && yFrac)
    {
        h264bsdInterpolateChromaHorVer(ref, mbPartChroma, xInt, yInt, width,
                height, xFrac, yFrac, chromaPartWidth, chromaPartHeight);
    }
    else if (xFrac)
    {
        h264bsdInterpolateChromaHor(ref, mbPartChroma, xInt, yInt, width,
                height, xFrac, chromaPartWidth, chromaPartHeight);
    }
    else if (yFrac)
    {
        h264bsdInterpolateChromaVer(ref, mbPartChroma, xInt, yInt, width,
                height, yFrac, chromaPartWidth, chromaPartHeight);
    }
    else
    {
        h264bsdFillBlock(ref, mbPartChroma, xInt, yInt, width, height,
            chromaPartWidth, chromaPartHeight, 8);
        ref += width * height;
        h264bsdFillBlock(ref, mbPartChroma + 8*8, xInt, yInt, width, height,
            chromaPartWidth, chromaPartHeight, 8);
    }

}


/*------------------------------------------------------------------------------

    Function: h264bsdInterpolateVerHalf

        Functional description:
          Function to perform vertical interpolation of pixel position 'h'
          for a block. Overfilling is done only if needed. Reference
          image (ref) is read at correct position and the predicted part
          is written to macroblock array (mb)

------------------------------------------------------------------------------*/
#ifndef H264DEC_ARM11
void h264bsdInterpolateVerHalf(
  u8 *ref,
  u8 *mb,
  i32 x0,
  i32 y0,
  u32 width,
  u32 height,
  u32 partWidth,
  u32 partHeight)
{
    u32 p1[21*21/4+1];
    u32 i, j;
    i32 tmp1, tmp2, tmp3, tmp4, tmp5, tmp6, tmp7;
    u8 *ptrC, *ptrV;
    const u8 *clp = h264bsdClip + 512;

    /* Code */

    ASSERT(ref);
    ASSERT(mb);

    if ((x0 < 0) || ((u32)x0+partWidth > width) ||
        (y0 < 0) || ((u32)y0+partHeight+5 > height))
    {
        h264bsdFillBlock(ref, (u8*)p1, x0, y0, width, height,
                partWidth, partHeight+5, partWidth);

        x0 = 0;
        y0 = 0;
        ref = (u8*)p1;
        width = partWidth;
    }

    ref += (u32)y0 * width + (u32)x0;

    ptrC = ref + width;
    ptrV = ptrC + 5*width;

    /* 4 pixels per iteration, interpolate using 5 vertical samples */
    for (i = (partHeight >> 2); i; i--)
    {
        /* h1 = (16 + A + 16(G+M) + 4(G+M) - 4(C+R) - (C+R) + T) >> 5 */
        for (j = partWidth; j; j--)
        {
            tmp4 = ptrV[-(i32)width*2];
            tmp5 = ptrV[-(i32)width];
            tmp1 = ptrV[width];
            tmp2 = ptrV[width*2];
            tmp6 = *ptrV++;

            tmp7 = tmp4 + tmp1;
            tmp2 -= (tmp7 << 2);
            tmp2 -= tmp7;
            tmp2 += 16;
            tmp7 = tmp5 + tmp6;
            tmp3 = ptrC[width*2];
            tmp2 += (tmp7 << 4);
            tmp2 += (tmp7 << 2);
            tmp2 += tmp3;
            tmp2 = clp[tmp2>>5];
            tmp1 += 16;
            mb[48] = (u8)tmp2;

            tmp7 = tmp3 + tmp6;
            tmp1 -= (tmp7 << 2);
            tmp1 -= tmp7;
            tmp7 = tmp4 + tmp5;
            tmp2 = ptrC[width];
            tmp1 += (tmp7 << 4);
            tmp1 += (tmp7 << 2);
            tmp1 += tmp2;
            tmp1 = clp[tmp1>>5];
            tmp6 += 16;
            mb[32] = (u8)tmp1;

            tmp7 = tmp2 + tmp5;
            tmp6 -= (tmp7 << 2);
            tmp6 -= tmp7;
            tmp7 = tmp4 + tmp3;
            tmp1 = *ptrC;
            tmp6 += (tmp7 << 4);
            tmp6 += (tmp7 << 2);
            tmp6 += tmp1;
            tmp6 = clp[tmp6>>5];
            tmp5 += 16;
            mb[16] = (u8)tmp6;

            tmp1 += tmp4;
            tmp5 -= (tmp1 << 2);
            tmp5 -= tmp1;
            tmp3 += tmp2;
            tmp6 = ptrC[-(i32)width];
            tmp5 += (tmp3 << 4);
            tmp5 += (tmp3 << 2);
            tmp5 += tmp6;
            tmp5 = clp[tmp5>>5];
            *mb++ = (u8)tmp5;
            ptrC++;
        }
        ptrC += 4*width - partWidth;
        ptrV += 4*width - partWidth;
        mb += 4*16 - partWidth;
    }

}

/*------------------------------------------------------------------------------

    Function: h264bsdInterpolateVerQuarter

        Functional description:
          Function to perform vertical interpolation of pixel position 'd'
          or 'n' for a block. Overfilling is done only if needed. Reference
          image (ref) is read at correct position and the predicted part
          is written to macroblock array (mb)

------------------------------------------------------------------------------*/

void h264bsdInterpolateVerQuarter(
  u8 *ref,
  u8 *mb,
  i32 x0,
  i32 y0,
  u32 width,
  u32 height,
  u32 partWidth,
  u32 partHeight,
  u32 verOffset)    /* 0 for pixel d, 1 for pixel n */
{
    u32 p1[21*21/4+1];
    u32 i, j;
    i32 tmp1, tmp2, tmp3, tmp4, tmp5, tmp6, tmp7;
    u8 *ptrC, *ptrV, *ptrInt;
    const u8 *clp = h264bsdClip + 512;

    /* Code */

    ASSERT(ref);
    ASSERT(mb);

    if ((x0 < 0) || ((u32)x0+partWidth > width) ||
        (y0 < 0) || ((u32)y0+partHeight+5 > height))
    {
        h264bsdFillBlock(ref, (u8*)p1, x0, y0, width, height,
                partWidth, partHeight+5, partWidth);

        x0 = 0;
        y0 = 0;
        ref = (u8*)p1;
        width = partWidth;
    }

    ref += (u32)y0 * width + (u32)x0;

    ptrC = ref + width;
    ptrV = ptrC + 5*width;

    /* Pointer to integer sample position, either M or R */
    ptrInt = ptrC + (2+verOffset)*width;

    /* 4 pixels per iteration
     * interpolate using 5 vertical samples and average between
     * interpolated value and integer sample value */
    for (i = (partHeight >> 2); i; i--)
    {
        /* h1 = (16 + A + 16(G+M) + 4(G+M) - 4(C+R) - (C+R) + T) >> 5 */
        for (j = partWidth; j; j--)
        {
            tmp4 = ptrV[-(i32)width*2];
            tmp5 = ptrV[-(i32)width];
            tmp1 = ptrV[width];
            tmp2 = ptrV[width*2];
            tmp6 = *ptrV++;

            tmp7 = tmp4 + tmp1;
            tmp2 -= (tmp7 << 2);
            tmp2 -= tmp7;
            tmp2 += 16;
            tmp7 = tmp5 + tmp6;
            tmp3 = ptrC[width*2];
            tmp2 += (tmp7 << 4);
            tmp2 += (tmp7 << 2);
            tmp2 += tmp3;
            tmp2 = clp[tmp2>>5];
            tmp7 = ptrInt[width*2];
            tmp1 += 16;
            tmp2++;
            mb[48] = (u8)((tmp2 + tmp7) >> 1);

            tmp7 = tmp3 + tmp6;
            tmp1 -= (tmp7 << 2);
            tmp1 -= tmp7;
            tmp7 = tmp4 + tmp5;
            tmp2 = ptrC[width];
            tmp1 += (tmp7 << 4);
            tmp1 += (tmp7 << 2);
            tmp1 += tmp2;
            tmp1 = clp[tmp1>>5];
            tmp7 = ptrInt[width];
            tmp6 += 16;
            tmp1++;
            mb[32] = (u8)((tmp1 + tmp7) >> 1);

            tmp7 = tmp2 + tmp5;
            tmp6 -= (tmp7 << 2);
            tmp6 -= tmp7;
            tmp7 = tmp4 + tmp3;
            tmp1 = *ptrC;
            tmp6 += (tmp7 << 4);
            tmp6 += (tmp7 << 2);
            tmp6 += tmp1;
            tmp6 = clp[tmp6>>5];
            tmp7 = *ptrInt;
            tmp5 += 16;
            tmp6++;
            mb[16] = (u8)((tmp6 + tmp7) >> 1);

            tmp1 += tmp4;
            tmp5 -= (tmp1 << 2);
            tmp5 -= tmp1;
            tmp3 += tmp2;
            tmp6 = ptrC[-(i32)width];
            tmp5 += (tmp3 << 4);
            tmp5 += (tmp3 << 2);
            tmp5 += tmp6;
            tmp5 = clp[tmp5>>5];
            tmp7 = ptrInt[-(i32)width];
            tmp5++;
            *mb++ = (u8)((tmp5 + tmp7) >> 1);
            ptrC++;
            ptrInt++;
        }
        ptrC += 4*width - partWidth;
        ptrV += 4*width - partWidth;
        ptrInt += 4*width - partWidth;
        mb += 4*16 - partWidth;
    }

}

/*------------------------------------------------------------------------------

    Function: h264bsdInterpolateHorHalf

        Functional description:
          Function to perform horizontal interpolation of pixel position 'b'
          for a block. Overfilling is done only if needed. Reference
          image (ref) is read at correct position and the predicted part
          is written to macroblock array (mb)

------------------------------------------------------------------------------*/

void h264bsdInterpolateHorHalf(
  u8 *ref,
  u8 *mb,
  i32 x0,
  i32 y0,
  u32 width,
  u32 height,
  u32 partWidth,
  u32 partHeight)
{
    u32 p1[21*21/4+1];
    u8 *ptrJ;
    u32 x, y;
    i32 tmp1, tmp2, tmp3, tmp4, tmp5, tmp6, tmp7;
    const u8 *clp = h264bsdClip + 512;

    /* Code */

    ASSERT(ref);
    ASSERT(mb);
    ASSERT((partWidth&0x3) == 0);
    ASSERT((partHeight&0x3) == 0);

    if ((x0 < 0) || ((u32)x0+partWidth+5 > width) ||
        (y0 < 0) || ((u32)y0+partHeight > height))
    {
        h264bsdFillBlock(ref, (u8*)p1, x0, y0, width, height,
                partWidth+5, partHeight, partWidth+5);

        x0 = 0;
        y0 = 0;
        ref = (u8*)p1;
        width = partWidth + 5;
    }

    ref += (u32)y0 * width + (u32)x0;

    ptrJ = ref + 5;

    for (y = partHeight; y; y--)
    {
        tmp6 = *(ptrJ - 5);
        tmp5 = *(ptrJ - 4);
        tmp4 = *(ptrJ - 3);
        tmp3 = *(ptrJ - 2);
        tmp2 = *(ptrJ - 1);

        /* calculate 4 pels per iteration */
        for (x = (partWidth >> 2); x; x--)
        {
            /* First pixel */
            tmp6 += 16;
            tmp7 = tmp3 + tmp4;
            tmp6 += (tmp7 << 4);
            tmp6 += (tmp7 << 2);
            tmp7 = tmp2 + tmp5;
            tmp1 = *ptrJ++;
            tmp6 -= (tmp7 << 2);
            tmp6 -= tmp7;
            tmp6 += tmp1;
            tmp6 = clp[tmp6>>5];
            /* Second pixel */
            tmp5 += 16;
            tmp7 = tmp2 + tmp3;
            *mb++ = (u8)tmp6;
            tmp5 += (tmp7 << 4);
            tmp5 += (tmp7 << 2);
            tmp7 = tmp1 + tmp4;
            tmp6 = *ptrJ++;
            tmp5 -= (tmp7 << 2);
            tmp5 -= tmp7;
            tmp5 += tmp6;
            tmp5 = clp[tmp5>>5];
            /* Third pixel */
            tmp4 += 16;
            tmp7 = tmp1 + tmp2;
            *mb++ = (u8)tmp5;
            tmp4 += (tmp7 << 4);
            tmp4 += (tmp7 << 2);
            tmp7 = tmp6 + tmp3;
            tmp5 = *ptrJ++;
            tmp4 -= (tmp7 << 2);
            tmp4 -= tmp7;
            tmp4 += tmp5;
            tmp4 = clp[tmp4>>5];
            /* Fourth pixel */
            tmp3 += 16;
            tmp7 = tmp6 + tmp1;
            *mb++ = (u8)tmp4;
            tmp3 += (tmp7 << 4);
            tmp3 += (tmp7 << 2);
            tmp7 = tmp5 + tmp2;
            tmp4 = *ptrJ++;
            tmp3 -= (tmp7 << 2);
            tmp3 -= tmp7;
            tmp3 += tmp4;
            tmp3 = clp[tmp3>>5];
            tmp7 = tmp4;
            tmp4 = tmp6;
            tmp6 = tmp2;
            tmp2 = tmp7;
            *mb++ = (u8)tmp3;
            tmp3 = tmp5;
            tmp5 = tmp1;
        }
        ptrJ += width - partWidth;
        mb += 16 - partWidth;
    }

}

/*------------------------------------------------------------------------------

    Function: h264bsdInterpolateHorQuarter

        Functional description:
          Function to perform horizontal interpolation of pixel position 'a'
          or 'c' for a block. Overfilling is done only if needed. Reference
          image (ref) is read at correct position and the predicted part
          is written to macroblock array (mb)

------------------------------------------------------------------------------*/

void h264bsdInterpolateHorQuarter(
  u8 *ref,
  u8 *mb,
  i32 x0,
  i32 y0,
  u32 width,
  u32 height,
  u32 partWidth,
  u32 partHeight,
  u32 horOffset) /* 0 for pixel a, 1 for pixel c */
{
    u32 p1[21*21/4+1];
    u8 *ptrJ;
    u32 x, y;
    i32 tmp1, tmp2, tmp3, tmp4, tmp5, tmp6, tmp7;
    const u8 *clp = h264bsdClip + 512;

    /* Code */

    ASSERT(ref);
    ASSERT(mb);

    if ((x0 < 0) || ((u32)x0+partWidth+5 > width) ||
        (y0 < 0) || ((u32)y0+partHeight > height))
    {
        h264bsdFillBlock(ref, (u8*)p1, x0, y0, width, height,
                partWidth+5, partHeight, partWidth+5);

        x0 = 0;
        y0 = 0;
        ref = (u8*)p1;
        width = partWidth + 5;
    }

    ref += (u32)y0 * width + (u32)x0;

    ptrJ = ref + 5;

    for (y = partHeight; y; y--)
    {
        tmp6 = *(ptrJ - 5);
        tmp5 = *(ptrJ - 4);
        tmp4 = *(ptrJ - 3);
        tmp3 = *(ptrJ - 2);
        tmp2 = *(ptrJ - 1);

        /* calculate 4 pels per iteration */
        for (x = (partWidth >> 2); x; x--)
        {
            /* First pixel */
            tmp6 += 16;
            tmp7 = tmp3 + tmp4;
            tmp6 += (tmp7 << 4);
            tmp6 += (tmp7 << 2);
            tmp7 = tmp2 + tmp5;
            tmp1 = *ptrJ++;
            tmp6 -= (tmp7 << 2);
            tmp6 -= tmp7;
            tmp6 += tmp1;
            tmp6 = clp[tmp6>>5];
            tmp5 += 16;
            if (!horOffset)
                tmp6 += tmp4;
            else
                tmp6 += tmp3;
            *mb++ = (u8)((tmp6 + 1) >> 1);
            /* Second pixel */
            tmp7 = tmp2 + tmp3;
            tmp5 += (tmp7 << 4);
            tmp5 += (tmp7 << 2);
            tmp7 = tmp1 + tmp4;
            tmp6 = *ptrJ++;
            tmp5 -= (tmp7 << 2);
            tmp5 -= tmp7;
            tmp5 += tmp6;
            tmp5 = clp[tmp5>>5];
            tmp4 += 16;
            if (!horOffset)
                tmp5 += tmp3;
            else
                tmp5 += tmp2;
            *mb++ = (u8)((tmp5 + 1) >> 1);
            /* Third pixel */
            tmp7 = tmp1 + tmp2;
            tmp4 += (tmp7 << 4);
            tmp4 += (tmp7 << 2);
            tmp7 = tmp6 + tmp3;
            tmp5 = *ptrJ++;
            tmp4 -= (tmp7 << 2);
            tmp4 -= tmp7;
            tmp4 += tmp5;
            tmp4 = clp[tmp4>>5];
            tmp3 += 16;
            if (!horOffset)
                tmp4 += tmp2;
            else
                tmp4 += tmp1;
            *mb++ = (u8)((tmp4 + 1) >> 1);
            /* Fourth pixel */
            tmp7 = tmp6 + tmp1;
            tmp3 += (tmp7 << 4);
            tmp3 += (tmp7 << 2);
            tmp7 = tmp5 + tmp2;
            tmp4 = *ptrJ++;
            tmp3 -= (tmp7 << 2);
            tmp3 -= tmp7;
            tmp3 += tmp4;
            tmp3 = clp[tmp3>>5];
            if (!horOffset)
                tmp3 += tmp1;
            else
                tmp3 += tmp6;
            *mb++ = (u8)((tmp3 + 1) >> 1);
            tmp3 = tmp5;
            tmp5 = tmp1;
            tmp7 = tmp4;
            tmp4 = tmp6;
            tmp6 = tmp2;
            tmp2 = tmp7;
        }
        ptrJ += width - partWidth;
        mb += 16 - partWidth;
    }

}

/*------------------------------------------------------------------------------

    Function: h264bsdInterpolateHorVerQuarter

        Functional description:
          Function to perform horizontal and vertical interpolation of pixel
          position 'e', 'g', 'p' or 'r' for a block. Overfilling is done only
          if needed. Reference image (ref) is read at correct position and
          the predicted part is written to macroblock array (mb)

------------------------------------------------------------------------------*/

void h264bsdInterpolateHorVerQuarter(
  u8 *ref,
  u8 *mb,
  i32 x0,
  i32 y0,
  u32 width,
  u32 height,
  u32 partWidth,
  u32 partHeight,
  u32 horVerOffset) /* 0 for pixel e, 1 for pixel g,
                       2 for pixel p, 3 for pixel r */
{
    u32 p1[21*21/4+1];
    u8 *ptrC, *ptrJ, *ptrV;
    u32 x, y;
    i32 tmp1, tmp2, tmp3, tmp4, tmp5, tmp6, tmp7;
    const u8 *clp = h264bsdClip + 512;

    /* Code */

    ASSERT(ref);
    ASSERT(mb);

    if ((x0 < 0) || ((u32)x0+partWidth+5 > width) ||
        (y0 < 0) || ((u32)y0+partHeight+5 > height))
    {
        h264bsdFillBlock(ref, (u8*)p1, x0, y0, width, height,
                partWidth+5, partHeight+5, partWidth+5);

        x0 = 0;
        y0 = 0;
        ref = (u8*)p1;
        width = partWidth+5;
    }

    /* Ref points to G + (-2, -2) */
    ref += (u32)y0 * width + (u32)x0;

    /* ptrJ points to either J or Q, depending on vertical offset */
    ptrJ = ref + (((horVerOffset & 0x2) >> 1) + 2) * width + 5;

    /* ptrC points to either C or D, depending on horizontal offset */
    ptrC = ref + width + 2 + (horVerOffset & 0x1);

    for (y = partHeight; y; y--)
    {
        tmp6 = *(ptrJ - 5);
        tmp5 = *(ptrJ - 4);
        tmp4 = *(ptrJ - 3);
        tmp3 = *(ptrJ - 2);
        tmp2 = *(ptrJ - 1);

        /* Horizontal interpolation, calculate 4 pels per iteration */
        for (x = (partWidth >> 2); x; x--)
        {
            /* First pixel */
            tmp6 += 16;
            tmp7 = tmp3 + tmp4;
            tmp6 += (tmp7 << 4);
            tmp6 += (tmp7 << 2);
            tmp7 = tmp2 + tmp5;
            tmp1 = *ptrJ++;
            tmp6 -= (tmp7 << 2);
            tmp6 -= tmp7;
            tmp6 += tmp1;
            tmp6 = clp[tmp6>>5];
            /* Second pixel */
            tmp5 += 16;
            tmp7 = tmp2 + tmp3;
            *mb++ = (u8)tmp6;
            tmp5 += (tmp7 << 4);
            tmp5 += (tmp7 << 2);
            tmp7 = tmp1 + tmp4;
            tmp6 = *ptrJ++;
            tmp5 -= (tmp7 << 2);
            tmp5 -= tmp7;
            tmp5 += tmp6;
            tmp5 = clp[tmp5>>5];
            /* Third pixel */
            tmp4 += 16;
            tmp7 = tmp1 + tmp2;
            *mb++ = (u8)tmp5;
            tmp4 += (tmp7 << 4);
            tmp4 += (tmp7 << 2);
            tmp7 = tmp6 + tmp3;
            tmp5 = *ptrJ++;
            tmp4 -= (tmp7 << 2);
            tmp4 -= tmp7;
            tmp4 += tmp5;
            tmp4 = clp[tmp4>>5];
            /* Fourth pixel */
            tmp3 += 16;
            tmp7 = tmp6 + tmp1;
            *mb++ = (u8)tmp4;
            tmp3 += (tmp7 << 4);
            tmp3 += (tmp7 << 2);
            tmp7 = tmp5 + tmp2;
            tmp4 = *ptrJ++;
            tmp3 -= (tmp7 << 2);
            tmp3 -= tmp7;
            tmp3 += tmp4;
            tmp3 = clp[tmp3>>5];
            tmp7 = tmp4;
            tmp4 = tmp6;
            tmp6 = tmp2;
            tmp2 = tmp7;
            *mb++ = (u8)tmp3;
            tmp3 = tmp5;
            tmp5 = tmp1;
        }
        ptrJ += width - partWidth;
        mb += 16 - partWidth;
    }

    mb -= 16*partHeight;
    ptrV = ptrC + 5*width;

    for (y = (partHeight >> 2); y; y--)
    {
        /* Vertical interpolation and averaging, 4 pels per iteration */
        for (x = partWidth; x; x--)
        {
            tmp4 = ptrV[-(i32)width*2];
            tmp5 = ptrV[-(i32)width];
            tmp1 = ptrV[width];
            tmp2 = ptrV[width*2];
            tmp6 = *ptrV++;

            tmp7 = tmp4 + tmp1;
            tmp2 -= (tmp7 << 2);
            tmp2 -= tmp7;
            tmp2 += 16;
            tmp7 = tmp5 + tmp6;
            tmp3 = ptrC[width*2];
            tmp2 += (tmp7 << 4);
            tmp2 += (tmp7 << 2);
            tmp2 += tmp3;
            tmp7 = clp[tmp2>>5];
            tmp2 = mb[48];
            tmp1 += 16;
            tmp7++;
            mb[48] = (u8)((tmp2 + tmp7) >> 1);

            tmp7 = tmp3 + tmp6;
            tmp1 -= (tmp7 << 2);
            tmp1 -= tmp7;
            tmp7 = tmp4 + tmp5;
            tmp2 = ptrC[width];
            tmp1 += (tmp7 << 4);
            tmp1 += (tmp7 << 2);
            tmp1 += tmp2;
            tmp7 = clp[tmp1>>5];
            tmp1 = mb[32];
            tmp6 += 16;
            tmp7++;
            mb[32] = (u8)((tmp1 + tmp7) >> 1);

            tmp1 = *ptrC;
            tmp7 = tmp2 + tmp5;
            tmp6 -= (tmp7 << 2);
            tmp6 -= tmp7;
            tmp7 = tmp4 + tmp3;
            tmp6 += (tmp7 << 4);
            tmp6 += (tmp7 << 2);
            tmp6 += tmp1;
            tmp7 = clp[tmp6>>5];
            tmp6 = mb[16];
            tmp5 += 16;
            tmp7++;
            mb[16] = (u8)((tmp6 + tmp7) >> 1);

            tmp6 = ptrC[-(i32)width];
            tmp1 += tmp4;
            tmp5 -= (tmp1 << 2);
            tmp5 -= tmp1;
            tmp3 += tmp2;
            tmp5 += (tmp3 << 4);
            tmp5 += (tmp3 << 2);
            tmp5 += tmp6;
            tmp7 = clp[tmp5>>5];
            tmp5 = *mb;
            tmp7++;
            *mb++ = (u8)((tmp5 + tmp7) >> 1);
            ptrC++;

        }
        ptrC += 4*width - partWidth;
        ptrV += 4*width - partWidth;
        mb += 4*16 - partWidth;
    }

}
#endif

/*------------------------------------------------------------------------------

    Function: h264bsdInterpolateMidHalf

        Functional description:
          Function to perform horizontal and vertical interpolation of pixel
          position 'j' for a block. Overfilling is done only if needed.
          Reference image (ref) is read at correct position and the predicted
          part is written to macroblock array (mb)

------------------------------------------------------------------------------*/

void h264bsdInterpolateMidHalf(
  u8 *ref,
  u8 *mb,
  i32 x0,
  i32 y0,
  u32 width,
  u32 height,
  u32 partWidth,
  u32 partHeight)
{
    u32 p1[21*21/4+1];
    u32 x, y;
    i32 tmp1, tmp2, tmp3, tmp4, tmp5, tmp6, tmp7;
    i32 *ptrC, *ptrV, *b1;
    u8  *ptrJ;
    i32 table[21*16];
    const u8 *clp = h264bsdClip + 512;

    /* Code */

    ASSERT(ref);
    ASSERT(mb);

    if ((x0 < 0) || ((u32)x0+partWidth+5 > width) ||
        (y0 < 0) || ((u32)y0+partHeight+5 > height))
    {
        h264bsdFillBlock(ref, (u8*)p1, x0, y0, width, height,
                partWidth+5, partHeight+5, partWidth+5);

        x0 = 0;
        y0 = 0;
        ref = (u8*)p1;
        width = partWidth+5;
    }

    ref += (u32)y0 * width + (u32)x0;

    b1 = table;
    ptrJ = ref + 5;

    /* First step: calculate intermediate values for
     * horizontal interpolation */
    for (y = partHeight + 5; y; y--)
    {
        tmp6 = *(ptrJ - 5);
        tmp5 = *(ptrJ - 4);
        tmp4 = *(ptrJ - 3);
        tmp3 = *(ptrJ - 2);
        tmp2 = *(ptrJ - 1);

        /* 4 pels per iteration */
        for (x = (partWidth >> 2); x; x--)
        {
            /* First pixel */
            tmp7 = tmp3 + tmp4;
            tmp6 += (tmp7 << 4);
            tmp6 += (tmp7 << 2);
            tmp7 = tmp2 + tmp5;
            tmp1 = *ptrJ++;
            tmp6 -= (tmp7 << 2);
            tmp6 -= tmp7;
            tmp6 += tmp1;
            *b1++ = tmp6;
            /* Second pixel */
            tmp7 = tmp2 + tmp3;
            tmp5 += (tmp7 << 4);
            tmp5 += (tmp7 << 2);
            tmp7 = tmp1 + tmp4;
            tmp6 = *ptrJ++;
            tmp5 -= (tmp7 << 2);
            tmp5 -= tmp7;
            tmp5 += tmp6;
            *b1++ = tmp5;
            /* Third pixel */
            tmp7 = tmp1 + tmp2;
            tmp4 += (tmp7 << 4);
            tmp4 += (tmp7 << 2);
            tmp7 = tmp6 + tmp3;
            tmp5 = *ptrJ++;
            tmp4 -= (tmp7 << 2);
            tmp4 -= tmp7;
            tmp4 += tmp5;
            *b1++ = tmp4;
            /* Fourth pixel */
            tmp7 = tmp6 + tmp1;
            tmp3 += (tmp7 << 4);
            tmp3 += (tmp7 << 2);
            tmp7 = tmp5 + tmp2;
            tmp4 = *ptrJ++;
            tmp3 -= (tmp7 << 2);
            tmp3 -= tmp7;
            tmp3 += tmp4;
            *b1++ = tmp3;
            tmp7 = tmp4;
            tmp4 = tmp6;
            tmp6 = tmp2;
            tmp2 = tmp7;
            tmp3 = tmp5;
            tmp5 = tmp1;
        }
        ptrJ += width - partWidth;
    }

    /* Second step: calculate vertical interpolation */
    ptrC = table + partWidth;
    ptrV = ptrC + 5*partWidth;
    for (y = (partHeight >> 2); y; y--)
    {
        /* 4 pels per iteration */
        for (x = partWidth; x; x--)
        {
            tmp4 = ptrV[-(i32)partWidth*2];
            tmp5 = ptrV[-(i32)partWidth];
            tmp1 = ptrV[partWidth];
            tmp2 = ptrV[partWidth*2];
            tmp6 = *ptrV++;

            tmp7 = tmp4 + tmp1;
            tmp2 -= (tmp7 << 2);
            tmp2 -= tmp7;
            tmp2 += 512;
            tmp7 = tmp5 + tmp6;
            tmp3 = ptrC[partWidth*2];
            tmp2 += (tmp7 << 4);
            tmp2 += (tmp7 << 2);
            tmp2 += tmp3;
            tmp7 = clp[tmp2>>10];
            tmp1 += 512;
            mb[48] = (u8)tmp7;

            tmp7 = tmp3 + tmp6;
            tmp1 -= (tmp7 << 2);
            tmp1 -= tmp7;
            tmp7 = tmp4 + tmp5;
            tmp2 = ptrC[partWidth];
            tmp1 += (tmp7 << 4);
            tmp1 += (tmp7 << 2);
            tmp1 += tmp2;
            tmp7 = clp[tmp1>>10];
            tmp6 += 512;
            mb[32] = (u8)tmp7;

            tmp1 = *ptrC;
            tmp7 = tmp2 + tmp5;
            tmp6 -= (tmp7 << 2);
            tmp6 -= tmp7;
            tmp7 = tmp4 + tmp3;
            tmp6 += (tmp7 << 4);
            tmp6 += (tmp7 << 2);
            tmp6 += tmp1;
            tmp7 = clp[tmp6>>10];
            tmp5 += 512;
            mb[16] = (u8)tmp7;

            tmp6 = ptrC[-(i32)partWidth];
            tmp1 += tmp4;
            tmp5 -= (tmp1 << 2);
            tmp5 -= tmp1;
            tmp3 += tmp2;
            tmp5 += (tmp3 << 4);
            tmp5 += (tmp3 << 2);
            tmp5 += tmp6;
            tmp7 = clp[tmp5>>10];
            *mb++ = (u8)tmp7;
            ptrC++;
        }
        mb += 4*16 - partWidth;
        ptrC += 3*partWidth;
        ptrV += 3*partWidth;
    }

}


/*------------------------------------------------------------------------------

    Function: h264bsdInterpolateMidVerQuarter

        Functional description:
          Function to perform horizontal and vertical interpolation of pixel
          position 'f' or 'q' for a block. Overfilling is done only if needed.
          Reference image (ref) is read at correct position and the predicted
          part is written to macroblock array (mb)

------------------------------------------------------------------------------*/

void h264bsdInterpolateMidVerQuarter(
  u8 *ref,
  u8 *mb,
  i32 x0,
  i32 y0,
  u32 width,
  u32 height,
  u32 partWidth,
  u32 partHeight,
  u32 verOffset)    /* 0 for pixel f, 1 for pixel q */
{
    u32 p1[21*21/4+1];
    u32 x, y;
    i32 tmp1, tmp2, tmp3, tmp4, tmp5, tmp6, tmp7;
    i32 *ptrC, *ptrV, *ptrInt, *b1;
    u8  *ptrJ;
    i32 table[21*16];
    const u8 *clp = h264bsdClip + 512;

    /* Code */

    ASSERT(ref);
    ASSERT(mb);

    if ((x0 < 0) || ((u32)x0+partWidth+5 > width) ||
        (y0 < 0) || ((u32)y0+partHeight+5 > height))
    {
        h264bsdFillBlock(ref, (u8*)p1, x0, y0, width, height,
                partWidth+5, partHeight+5, partWidth+5);

        x0 = 0;
        y0 = 0;
        ref = (u8*)p1;
        width = partWidth+5;
    }

    ref += (u32)y0 * width + (u32)x0;

    b1 = table;
    ptrJ = ref + 5;

    /* First step: calculate intermediate values for
     * horizontal interpolation */
    for (y = partHeight + 5; y; y--)
    {
        tmp6 = *(ptrJ - 5);
        tmp5 = *(ptrJ - 4);
        tmp4 = *(ptrJ - 3);
        tmp3 = *(ptrJ - 2);
        tmp2 = *(ptrJ - 1);
        for (x = (partWidth >> 2); x; x--)
        {
            /* First pixel */
            tmp7 = tmp3 + tmp4;
            tmp6 += (tmp7 << 4);
            tmp6 += (tmp7 << 2);
            tmp7 = tmp2 + tmp5;
            tmp1 = *ptrJ++;
            tmp6 -= (tmp7 << 2);
            tmp6 -= tmp7;
            tmp6 += tmp1;
            *b1++ = tmp6;
            /* Second pixel */
            tmp7 = tmp2 + tmp3;
            tmp5 += (tmp7 << 4);
            tmp5 += (tmp7 << 2);
            tmp7 = tmp1 + tmp4;
            tmp6 = *ptrJ++;
            tmp5 -= (tmp7 << 2);
            tmp5 -= tmp7;
            tmp5 += tmp6;
            *b1++ = tmp5;
            /* Third pixel */
            tmp7 = tmp1 + tmp2;
            tmp4 += (tmp7 << 4);
            tmp4 += (tmp7 << 2);
            tmp7 = tmp6 + tmp3;
            tmp5 = *ptrJ++;
            tmp4 -= (tmp7 << 2);
            tmp4 -= tmp7;
            tmp4 += tmp5;
            *b1++ = tmp4;
            /* Fourth pixel */
            tmp7 = tmp6 + tmp1;
            tmp3 += (tmp7 << 4);
            tmp3 += (tmp7 << 2);
            tmp7 = tmp5 + tmp2;
            tmp4 = *ptrJ++;
            tmp3 -= (tmp7 << 2);
            tmp3 -= tmp7;
            tmp3 += tmp4;
            *b1++ = tmp3;
            tmp7 = tmp4;
            tmp4 = tmp6;
            tmp6 = tmp2;
            tmp2 = tmp7;
            tmp3 = tmp5;
            tmp5 = tmp1;
        }
        ptrJ += width - partWidth;
    }

    /* Second step: calculate vertical interpolation and average */
    ptrC = table + partWidth;
    ptrV = ptrC + 5*partWidth;
    /* Pointer to integer sample position, either M or R */
    ptrInt = ptrC + (2+verOffset)*partWidth;
    for (y = (partHeight >> 2); y; y--)
    {
        for (x = partWidth; x; x--)
        {
            tmp4 = ptrV[-(i32)partWidth*2];
            tmp5 = ptrV[-(i32)partWidth];
            tmp1 = ptrV[partWidth];
            tmp2 = ptrV[partWidth*2];
            tmp6 = *ptrV++;

            tmp7 = tmp4 + tmp1;
            tmp2 -= (tmp7 << 2);
            tmp2 -= tmp7;
            tmp2 += 512;
            tmp7 = tmp5 + tmp6;
            tmp3 = ptrC[partWidth*2];
            tmp2 += (tmp7 << 4);
            tmp2 += (tmp7 << 2);
            tmp7 = ptrInt[partWidth*2];
            tmp2 += tmp3;
            tmp2 = clp[tmp2>>10];
            tmp7 += 16;
            tmp7 = clp[tmp7>>5];
            tmp1 += 512;
            tmp2++;
            mb[48] = (u8)((tmp7 + tmp2) >> 1);

            tmp7 = tmp3 + tmp6;
            tmp1 -= (tmp7 << 2);
            tmp1 -= tmp7;
            tmp7 = tmp4 + tmp5;
            tmp2 = ptrC[partWidth];
            tmp1 += (tmp7 << 4);
            tmp1 += (tmp7 << 2);
            tmp7 = ptrInt[partWidth];
            tmp1 += tmp2;
            tmp1 = clp[tmp1>>10];
            tmp7 += 16;
            tmp7 = clp[tmp7>>5];
            tmp6 += 512;
            tmp1++;
            mb[32] = (u8)((tmp7 + tmp1) >> 1);

            tmp1 = *ptrC;
            tmp7 = tmp2 + tmp5;
            tmp6 -= (tmp7 << 2);
            tmp6 -= tmp7;
            tmp7 = tmp4 + tmp3;
            tmp6 += (tmp7 << 4);
            tmp6 += (tmp7 << 2);
            tmp7 = *ptrInt;
            tmp6 += tmp1;
            tmp6 = clp[tmp6>>10];
            tmp7 += 16;
            tmp7 = clp[tmp7>>5];
            tmp5 += 512;
            tmp6++;
            mb[16] = (u8)((tmp7 + tmp6) >> 1);

            tmp6 = ptrC[-(i32)partWidth];
            tmp1 += tmp4;
            tmp5 -= (tmp1 << 2);
            tmp5 -= tmp1;
            tmp3 += tmp2;
            tmp5 += (tmp3 << 4);
            tmp5 += (tmp3 << 2);
            tmp7 = ptrInt[-(i32)partWidth];
            tmp5 += tmp6;
            tmp5 = clp[tmp5>>10];
            tmp7 += 16;
            tmp7 = clp[tmp7>>5];
            tmp5++;
            *mb++ = (u8)((tmp7 + tmp5) >> 1);
            ptrC++;
            ptrInt++;
        }
        mb += 4*16 - partWidth;
        ptrC += 3*partWidth;
        ptrV += 3*partWidth;
        ptrInt += 3*partWidth;
    }

}


/*------------------------------------------------------------------------------

    Function: h264bsdInterpolateMidHorQuarter

        Functional description:
          Function to perform horizontal and vertical interpolation of pixel
          position 'i' or 'k' for a block. Overfilling is done only if needed.
          Reference image (ref) is read at correct position and the predicted
          part is written to macroblock array (mb)

------------------------------------------------------------------------------*/

void h264bsdInterpolateMidHorQuarter(
  u8 *ref,
  u8 *mb,
  i32 x0,
  i32 y0,
  u32 width,
  u32 height,
  u32 partWidth,
  u32 partHeight,
  u32 horOffset)    /* 0 for pixel i, 1 for pixel k */
{
    u32 p1[21*21/4+1];
    u32 x, y;
    i32 tmp1, tmp2, tmp3, tmp4, tmp5, tmp6, tmp7;
    i32 *ptrJ, *ptrInt, *h1;
    u8  *ptrC, *ptrV;
    i32 table[21*16];
    i32 tableWidth = (i32)partWidth+5;
    const u8 *clp = h264bsdClip + 512;

    /* Code */

    ASSERT(ref);
    ASSERT(mb);

    if ((x0 < 0) || ((u32)x0+partWidth+5 > width) ||
        (y0 < 0) || ((u32)y0+partHeight+5 > height))
    {
        h264bsdFillBlock(ref, (u8*)p1, x0, y0, width, height,
                partWidth+5, partHeight+5, partWidth+5);

        x0 = 0;
        y0 = 0;
        ref = (u8*)p1;
        width = partWidth+5;
    }

    ref += (u32)y0 * width + (u32)x0;

    h1 = table + tableWidth;
    ptrC = ref + width;
    ptrV = ptrC + 5*width;

    /* First step: calculate intermediate values for
     * vertical interpolation */
    for (y = (partHeight >> 2); y; y--)
    {
        for (x = (u32)tableWidth; x; x--)
        {
            tmp4 = ptrV[-(i32)width*2];
            tmp5 = ptrV[-(i32)width];
            tmp1 = ptrV[width];
            tmp2 = ptrV[width*2];
            tmp6 = *ptrV++;

            tmp7 = tmp4 + tmp1;
            tmp2 -= (tmp7 << 2);
            tmp2 -= tmp7;
            tmp7 = tmp5 + tmp6;
            tmp3 = ptrC[width*2];
            tmp2 += (tmp7 << 4);
            tmp2 += (tmp7 << 2);
            tmp2 += tmp3;
            h1[tableWidth*2] = tmp2;

            tmp7 = tmp3 + tmp6;
            tmp1 -= (tmp7 << 2);
            tmp1 -= tmp7;
            tmp7 = tmp4 + tmp5;
            tmp2 = ptrC[width];
            tmp1 += (tmp7 << 4);
            tmp1 += (tmp7 << 2);
            tmp1 += tmp2;
            h1[tableWidth] = tmp1;

            tmp1 = *ptrC;
            tmp7 = tmp2 + tmp5;
            tmp6 -= (tmp7 << 2);
            tmp6 -= tmp7;
            tmp7 = tmp4 + tmp3;
            tmp6 += (tmp7 << 4);
            tmp6 += (tmp7 << 2);
            tmp6 += tmp1;
            *h1 = tmp6;

            tmp6 = ptrC[-(i32)width];
            tmp1 += tmp4;
            tmp5 -= (tmp1 << 2);
            tmp5 -= tmp1;
            tmp3 += tmp2;
            tmp5 += (tmp3 << 4);
            tmp5 += (tmp3 << 2);
            tmp5 += tmp6;
            h1[-tableWidth] = tmp5;
            h1++;
            ptrC++;
        }
        ptrC += 4*width - partWidth - 5;
        ptrV += 4*width - partWidth - 5;
        h1 += 3*tableWidth;
    }

    /* Second step: calculate horizontal interpolation and average */
    ptrJ = table + 5;
    /* Pointer to integer sample position, either G or H */
    ptrInt = table + 2 + horOffset;
    for (y = partHeight; y; y--)
    {
        tmp6 = *(ptrJ - 5);
        tmp5 = *(ptrJ - 4);
        tmp4 = *(ptrJ - 3);
        tmp3 = *(ptrJ - 2);
        tmp2 = *(ptrJ - 1);
        for (x = (partWidth>>2); x; x--)
        {
            /* First pixel */
            tmp6 += 512;
            tmp7 = tmp3 + tmp4;
            tmp6 += (tmp7 << 4);
            tmp6 += (tmp7 << 2);
            tmp7 = tmp2 + tmp5;
            tmp1 = *ptrJ++;
            tmp6 -= (tmp7 << 2);
            tmp6 -= tmp7;
            tmp7 = *ptrInt++;
            tmp6 += tmp1;
            tmp6 = clp[tmp6 >> 10];
            tmp7 += 16;
            tmp7 = clp[tmp7 >> 5];
            tmp5 += 512;
            tmp6++;
            *mb++ = (u8)((tmp6 + tmp7) >> 1);
            /* Second pixel */
            tmp7 = tmp2 + tmp3;
            tmp5 += (tmp7 << 4);
            tmp5 += (tmp7 << 2);
            tmp7 = tmp1 + tmp4;
            tmp6 = *ptrJ++;
            tmp5 -= (tmp7 << 2);
            tmp5 -= tmp7;
            tmp7 = *ptrInt++;
            tmp5 += tmp6;
            tmp5 = clp[tmp5 >> 10];
            tmp7 += 16;
            tmp7 = clp[tmp7 >> 5];
            tmp4 += 512;
            tmp5++;
            *mb++ = (u8)((tmp5 + tmp7) >> 1);
            /* Third pixel */
            tmp7 = tmp1 + tmp2;
            tmp4 += (tmp7 << 4);
            tmp4 += (tmp7 << 2);
            tmp7 = tmp6 + tmp3;
            tmp5 = *ptrJ++;
            tmp4 -= (tmp7 << 2);
            tmp4 -= tmp7;
            tmp7 = *ptrInt++;
            tmp4 += tmp5;
            tmp4 = clp[tmp4 >> 10];
            tmp7 += 16;
            tmp7 = clp[tmp7 >> 5];
            tmp3 += 512;
            tmp4++;
            *mb++ = (u8)((tmp4 + tmp7) >> 1);
            /* Fourth pixel */
            tmp7 = tmp6 + tmp1;
            tmp3 += (tmp7 << 4);
            tmp3 += (tmp7 << 2);
            tmp7 = tmp5 + tmp2;
            tmp4 = *ptrJ++;
            tmp3 -= (tmp7 << 2);
            tmp3 -= tmp7;
            tmp7 = *ptrInt++;
            tmp3 += tmp4;
            tmp3 = clp[tmp3 >> 10];
            tmp7 += 16;
            tmp7 = clp[tmp7 >> 5];
            tmp3++;
            *mb++ = (u8)((tmp3 + tmp7) >> 1);
            tmp3 = tmp5;
            tmp5 = tmp1;
            tmp7 = tmp4;
            tmp4 = tmp6;
            tmp6 = tmp2;
            tmp2 = tmp7;
        }
        ptrJ += 5;
        ptrInt += 5;
        mb += 16 - partWidth;
    }

}


/*------------------------------------------------------------------------------

    Function: h264bsdPredictSamples

        Functional description:
          This function reconstructs a prediction for a macroblock partition.
          The prediction is either copied or interpolated using the reference
          frame and the motion vector. Both luminance and chrominance parts are
          predicted. The prediction is stored in given macroblock array (data).
        Inputs:
          data          pointer to macroblock array (384 bytes) for output
          mv            pointer to motion vector used for prediction
          refPic        pointer to reference picture structure
          xA            x-coordinate for current macroblock
          yA            y-coordinate for current macroblock
          partX         x-offset for partition in macroblock
          partY         y-offset for partition in macroblock
          partWidth     width of partition
          partHeight    height of partition
        Outputs:
          data          macroblock array (16x16+8x8+8x8) where predicted
                        partition is stored at correct position

------------------------------------------------------------------------------*/

void h264bsdPredictSamples(
  u8 *data,
  mv_t *mv,
  image_t *refPic,
  u32 xA,
  u32 yA,
  u32 partX,
  u32 partY,
  u32 partWidth,
  u32 partHeight)

{

/* Variables */

    u32 xFrac, yFrac, width, height;
    i32 xInt, yInt;
    u8 *lumaPartData;

/* Code */

    ASSERT(data);
    ASSERT(mv);
    ASSERT(partWidth);
    ASSERT(partHeight);
    ASSERT(refPic);
    ASSERT(refPic->data);
    ASSERT(refPic->width);
    ASSERT(refPic->height);

    /* luma */
    lumaPartData = data + 16*partY + partX;

    xFrac = mv->hor & 0x3;
    yFrac = mv->ver & 0x3;

    width = 16 * refPic->width;
    height = 16 * refPic->height;

    xInt = (i32)xA + (i32)partX + (mv->hor >> 2);
    yInt = (i32)yA + (i32)partY + (mv->ver >> 2);

    ASSERT(lumaFracPos[xFrac][yFrac] < 16);

    switch (lumaFracPos[xFrac][yFrac])
    {
        case 0: /* G */
            h264bsdFillBlock(refPic->data, lumaPartData,
                    xInt,yInt,width,height,partWidth,partHeight,16);
            break;
        case 1: /* d */
            h264bsdInterpolateVerQuarter(refPic->data, lumaPartData,
                    xInt, yInt-2, width, height, partWidth, partHeight, 0);
            break;
        case 2: /* h */
            h264bsdInterpolateVerHalf(refPic->data, lumaPartData,
                    xInt, yInt-2, width, height, partWidth, partHeight);
            break;
        case 3: /* n */
            h264bsdInterpolateVerQuarter(refPic->data, lumaPartData,
                    xInt, yInt-2, width, height, partWidth, partHeight, 1);
            break;
        case 4: /* a */
            h264bsdInterpolateHorQuarter(refPic->data, lumaPartData,
                    xInt-2, yInt, width, height, partWidth, partHeight, 0);
            break;
        case 5: /* e */
            h264bsdInterpolateHorVerQuarter(refPic->data, lumaPartData,
                    xInt-2, yInt-2, width, height, partWidth, partHeight, 0);
            break;
        case 6: /* i */
            h264bsdInterpolateMidHorQuarter(refPic->data, lumaPartData,
                    xInt-2, yInt-2, width, height, partWidth, partHeight, 0);
            break;
        case 7: /* p */
            h264bsdInterpolateHorVerQuarter(refPic->data, lumaPartData,
                    xInt-2, yInt-2, width, height, partWidth, partHeight, 2);
            break;
        case 8: /* b */
            h264bsdInterpolateHorHalf(refPic->data, lumaPartData,
                    xInt-2, yInt, width, height, partWidth, partHeight);
            break;
        case 9: /* f */
            h264bsdInterpolateMidVerQuarter(refPic->data, lumaPartData,
                    xInt-2, yInt-2, width, height, partWidth, partHeight, 0);
            break;
        case 10: /* j */
            h264bsdInterpolateMidHalf(refPic->data, lumaPartData,
                    xInt-2, yInt-2, width, height, partWidth, partHeight);
            break;
        case 11: /* q */
            h264bsdInterpolateMidVerQuarter(refPic->data, lumaPartData,
                    xInt-2, yInt-2, width, height, partWidth, partHeight, 1);
            break;
        case 12: /* c */
            h264bsdInterpolateHorQuarter(refPic->data, lumaPartData,
                    xInt-2, yInt, width, height, partWidth, partHeight, 1);
            break;
        case 13: /* g */
            h264bsdInterpolateHorVerQuarter(refPic->data, lumaPartData,
                    xInt-2, yInt-2, width, height, partWidth, partHeight, 1);
            break;
        case 14: /* k */
            h264bsdInterpolateMidHorQuarter(refPic->data, lumaPartData,
                    xInt-2, yInt-2, width, height, partWidth, partHeight, 1);
            break;
        default: /* case 15, r */
            h264bsdInterpolateHorVerQuarter(refPic->data, lumaPartData,
                    xInt-2, yInt-2, width, height, partWidth, partHeight, 3);
            break;
    }

    /* chroma */
    PredictChroma(
      data + 16*16 + (partY>>1)*8 + (partX>>1),
      xA + partX,
      yA + partY,
      partWidth,
      partHeight,
      mv,
      refPic);

}

#else /* H264DEC_OMXDL */
/*------------------------------------------------------------------------------

    Function: h264bsdPredictSamples

        Functional description:
          This function reconstructs a prediction for a macroblock partition.
          The prediction is either copied or interpolated using the reference
          frame and the motion vector. Both luminance and chrominance parts are
          predicted. The prediction is stored in given macroblock array (data).
        Inputs:
          data          pointer to macroblock array (384 bytes) for output
          mv            pointer to motion vector used for prediction
          refPic        pointer to reference picture structure
          xA            x-coordinate for current macroblock
          yA            y-coordinate for current macroblock
          partX         x-offset for partition in macroblock
          partY         y-offset for partition in macroblock
          partWidth     width of partition
          partHeight    height of partition
        Outputs:
          data          macroblock array (16x16+8x8+8x8) where predicted
                        partition is stored at correct position

------------------------------------------------------------------------------*/

/*lint -e{550} Symbol 'res' not accessed */
void h264bsdPredictSamples(
  u8 *data,
  mv_t *mv,
  image_t *refPic,
  u32 colAndRow,
  u32 part,
  u8 *pFill)

{

/* Variables */

    u32 xFrac, yFrac;
    u32 width, height;
    i32 xInt, yInt, x0, y0;
    u8 *partData, *ref;
    OMXSize roi;
    u32 fillWidth;
    u32 fillHeight;
    OMXResult res;
    u32 xA, yA;
    u32 partX, partY;
    u32 partWidth, partHeight;

/* Code */

    ASSERT(data);
    ASSERT(mv);
    ASSERT(refPic);
    ASSERT(refPic->data);
    ASSERT(refPic->width);
    ASSERT(refPic->height);

    xA = (colAndRow & 0xFFFF0000) >> 16;
    yA = (colAndRow & 0x0000FFFF);

    partX = (part & 0xFF000000) >> 24;
    partY = (part & 0x00FF0000) >> 16;
    partWidth = (part & 0x0000FF00) >> 8;
    partHeight = (part & 0x000000FF);

    ASSERT(partWidth);
    ASSERT(partHeight);

    /* luma */
    partData = data + 16*partY + partX;

    xFrac = mv->hor & 0x3;
    yFrac = mv->ver & 0x3;

    width = 16 * refPic->width;
    height = 16 * refPic->height;

    xInt = (i32)xA + (i32)partX + (mv->hor >> 2);
    yInt = (i32)yA + (i32)partY + (mv->ver >> 2);

    x0 = (xFrac) ? xInt-2 : xInt;
    y0 = (yFrac) ? yInt-2 : yInt;

    if (xFrac)
    {
        if (partWidth == 16)
            fillWidth = 32;
        else
            fillWidth = 16;
    }
    else
        fillWidth = (partWidth*2);
    if (yFrac)
        fillHeight = partHeight+5;
    else
        fillHeight = partHeight;


    if ((x0 < 0) || ((u32)x0+fillWidth > width) ||
        (y0 < 0) || ((u32)y0+fillHeight > height))
    {
        h264bsdFillBlock(refPic->data, (u8*)pFill, x0, y0, width, height,
                fillWidth, fillHeight, fillWidth);

        x0 = 0;
        y0 = 0;
        ref = pFill;
        width = fillWidth;
        if (yFrac)
            ref += 2*width;
        if (xFrac)
            ref += 2;
    }
    else
    {
        /*lint --e(737) Loss of sign */
        ref = refPic->data + yInt*width + xInt;
    }
    /* Luma interpolation */
    roi.width = (i32)partWidth;
    roi.height = (i32)partHeight;

    res = omxVCM4P10_InterpolateLuma(ref, (i32)width, partData, 16,
                                        (i32)xFrac, (i32)yFrac, roi);
    ASSERT(res == 0);

    /* Chroma */
    width  = 8 * refPic->width;
    height = 8 * refPic->height;

    x0 = ((xA + partX) >> 1) + (mv->hor >> 3);
    y0 = ((yA + partY) >> 1) + (mv->ver >> 3);
    xFrac = mv->hor & 0x7;
    yFrac = mv->ver & 0x7;

    ref = refPic->data + 256 * refPic->width * refPic->height;

    roi.width = (i32)(partWidth >> 1);
    fillWidth = ((partWidth >> 1) + 8) & ~0x7;
    roi.height = (i32)(partHeight >> 1);
    fillHeight = (partHeight >> 1) + 1;

    if ((x0 < 0) || ((u32)x0+fillWidth > width) ||
        (y0 < 0) || ((u32)y0+fillHeight > height))
    {
        h264bsdFillBlock(ref, pFill, x0, y0, width, height,
            fillWidth, fillHeight, fillWidth);
        ref += width * height;
        h264bsdFillBlock(ref, pFill + fillWidth*fillHeight,
            x0, y0, width, height, fillWidth,
            fillHeight, fillWidth);

        ref = pFill;
        x0 = 0;
        y0 = 0;
        width = fillWidth;
        height = fillHeight;
    }

    partData = data + 16*16 + (partY>>1)*8 + (partX>>1);

    /* Chroma interpolation */
    /*lint --e(737) Loss of sign */
    ref += y0 * width + x0;
    res = armVCM4P10_Interpolate_Chroma(ref, width, partData, 8,
                            (u32)roi.width, (u32)roi.height, xFrac, yFrac);
    ASSERT(res == 0);
    partData += 8 * 8;
    ref += height * width;
    res = armVCM4P10_Interpolate_Chroma(ref, width, partData, 8,
                            (u32)roi.width, (u32)roi.height, xFrac, yFrac);
    ASSERT(res == 0);

}

#endif /* H264DEC_OMXDL */


/*------------------------------------------------------------------------------

    Function: FillRow1

        Functional description:
          This function gets a row of reference pels in a 'normal' case when no
          overfilling is necessary.

------------------------------------------------------------------------------*/

static void FillRow1(
  u8 *ref,
  u8 *fill,
  i32 left,
  i32 center,
  i32 right)
{

    ASSERT(ref);
    ASSERT(fill);

    H264SwDecMemcpy(fill, ref, (u32)center);

    /*lint -e(715) */
}


/*------------------------------------------------------------------------------

    Function: h264bsdFillRow7

        Functional description:
          This function gets a row of reference pels when horizontal coordinate
          is partly negative or partly greater than reference picture width
          (overfilling some pels on left and/or right edge).
        Inputs:
          ref       pointer to reference samples
          left      amount of pixels to overfill on left-edge
          center    amount of pixels to copy
          right     amount of pixels to overfill on right-edge
        Outputs:
          fill      pointer where samples are stored

------------------------------------------------------------------------------*/
#ifndef H264DEC_NEON
void h264bsdFillRow7(
  u8 *ref,
  u8 *fill,
  i32 left,
  i32 center,
  i32 right)
{
    u8 tmp;

    ASSERT(ref);
    ASSERT(fill);

    if (left)
        tmp = *ref;

    for ( ; left; left--)
        /*lint -esym(644,tmp)  tmp is initialized if used */
        *fill++ = tmp;

    for ( ; center; center--)
        *fill++ = *ref++;

    if (right)
        tmp = ref[-1];

    for ( ; right; right--)
        /*lint -esym(644,tmp)  tmp is initialized if used */
        *fill++ = tmp;
}
#endif
/*------------------------------------------------------------------------------

    Function: h264bsdFillBlock

        Functional description:
          This function gets a block of reference pels. It determines whether
          overfilling is needed or not and repeatedly calls an appropriate
          function (by using a function pointer) that fills one row the block.
        Inputs:
          ref               pointer to reference frame
          x0                x-coordinate for block
          y0                y-coordinate for block
          width             width of reference frame
          height            height of reference frame
          blockWidth        width of block
          blockHeight       height of block
          fillScanLength    length of a line in output array (pixels)
        Outputs:
          fill              pointer to array where output block is written

------------------------------------------------------------------------------*/

void h264bsdFillBlock(
  u8 *ref,
  u8 *fill,
  i32 x0,
  i32 y0,
  u32 width,
  u32 height,
  u32 blockWidth,
  u32 blockHeight,
  u32 fillScanLength)

{

/* Variables */

    i32 xstop, ystop;
    void (*fp)(u8*, u8*, i32, i32, i32);
    i32 left, x, right;
    i32 top, y, bottom;

/* Code */

    ASSERT(ref);
    ASSERT(fill);
    ASSERT(width);
    ASSERT(height);
    ASSERT(fill);
    ASSERT(blockWidth);
    ASSERT(blockHeight);

    xstop = x0 + (i32)blockWidth;
    ystop = y0 + (i32)blockHeight;

    /* Choose correct function whether overfilling on left-edge or right-edge
     * is needed or not */
    if (x0 >= 0 && xstop <= (i32)width)
        fp = FillRow1;
    else
        fp = h264bsdFillRow7;

    if (ystop < 0)
        y0 = -(i32)blockHeight;

    if (xstop < 0)
        x0 = -(i32)blockWidth;

    if (y0 > (i32)height)
        y0 = (i32)height;

    if (x0 > (i32)width)
        x0 = (i32)width;

    xstop = x0 + (i32)blockWidth;
    ystop = y0 + (i32)blockHeight;

    if (x0 > 0)
        ref += x0;

    if (y0 > 0)
        ref += y0 * (i32)width;

    left = x0 < 0 ? -x0 : 0;
    right = xstop > (i32)width ? xstop - (i32)width : 0;
    x = (i32)blockWidth - left - right;

    top = y0 < 0 ? -y0 : 0;
    bottom = ystop > (i32)height ? ystop - (i32)height : 0;
    y = (i32)blockHeight - top - bottom;

    /* Top-overfilling */
    for ( ; top; top-- )
    {
        (*fp)(ref, fill, left, x, right);
        fill += fillScanLength;
    }

    /* Lines inside reference image */
    for ( ; y; y-- )
    {
        (*fp)(ref, fill, left, x, right);
        ref += width;
        fill += fillScanLength;
    }

    ref -= width;

    /* Bottom-overfilling */
    for ( ; bottom; bottom-- )
    {
        (*fp)(ref, fill, left, x, right);
        fill += fillScanLength;
    }
}

/*lint +e701 +e702 */


