/* ------------------------------------------------------------------
 * Copyright (C) 1998-2009 PacketVideo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 * -------------------------------------------------------------------
 */
/*
------------------------------------------------------------------------------
 MODULE DESCRIPTION

 This file contains the functions that transform an 8r8 image block from
 dequantized DCT coefficients to spatial domain pirel values by calculating
 inverse discrete cosine transform (IDCT).

------------------------------------------------------------------------------
*/
/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include "mp4dec_lib.h"
#include "idct.h"
#include "motion_comp.h"
#ifndef FAST_IDCT

/*
------------------------------------------------------------------------------
 FUNCTION NAME: idct
------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS FOR idct

 Inputs:
    blk = pointer to the buffer containing the dequantized DCT
          coefficients of type int for an 8r8 image block;
          values range from (-2048, 2047) which defined as standard.

 Local Stores/Buffers/Pointers Needed:
    None

 Global Stores/Buffers/Pointers Needed:
    None

 Outputs:
    None

 Pointers and Buffers Modified:
    blk points to the found IDCT values for an 8r8 image block.

 Local Stores Modified:
    None

 Global Stores Modified:
    None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION FOR idct

 This function transforms an 8r8 image block from dequantized DCT coefficients
 (F(u,v)) to spatial domain pirel values (f(r,y)) by performing the two
 dimensional inverse discrete cosine transform (IDCT).

         _7_ _7_      C(u) C(v)
    f(r,y) = \   \  F(u,v)---- ----cos[(2r+1)*u*pi/16]cos[(2y+1)*v*pi/16]
         /__ /__    2    2
         u=0 v=0

    where   C(i) = 1/sqrt(2)    if i=0
        C(i) = 1        otherwise

 2-D IDCT can be separated as horizontal(row-wise) and vertical(column-wise)
 1-D IDCTs. Therefore, 2-D IDCT values are found by the following two steps:
 1. Find horizontal 1-D IDCT values for each row from 8r8 dequantized DCT
    coefficients by row IDCT operation.

          _7_        C(u)
    g(r,v) =  \   F(u,v) ---- cos[(2r+1)*u*pi/16]
          /__         2
          u=0

 2. Find vertical 1-D IDCT values for each column from the results of 1
    by column IDCT operation.

              _7_        C(v)
    f(r,y) =  \   g(r,v) ---- cos[(2y+1)*v*pi/16]
          /__         2
          v=0

------------------------------------------------------------------------------
 REQUIREMENTS FOR idct

 None

------------------------------------------------------------------------------
*/
/*  REFERENCES FOR idct */
/* idct.c, inverse fast discrete cosine transform
 inverse two dimensional DCT, Chen-Wang algorithm
 (cf. IEEE ASSP-32, pp. 803-816, Aug. 1984)
 32-bit integer arithmetic (8 bit coefficients)
 11 mults, 29 adds per DCT
 sE, 18.8.91

 coefficients ertended to 12 bit for IEEE1180-1990
 compliance                           sE,  2.1.94
*/


/*----------------------------------------------------------------------------
; Function Code FOR idct
----------------------------------------------------------------------------*/
void idct_intra(
    int *blk, uint8 *comp, int width
)
{
    /*----------------------------------------------------------------------------
    ; Define all local variables
    ----------------------------------------------------------------------------*/
    int i;
    int32   tmpBLK[64];
    int32   *tmpBLK32 = &tmpBLK[0];
    int32   r0, r1, r2, r3, r4, r5, r6, r7, r8; /* butterfly nodes */
    int32   a;
    int offset = width - 8;
    /*----------------------------------------------------------------------------
    ; Function body here
    ----------------------------------------------------------------------------*/
    /* two dimensional inverse discrete cosine transform */


    /* column (vertical) IDCT */
    for (i = B_SIZE - 1; i >= 0; i--)
    {
        /* initialize butterfly nodes at first stage */

        r1 = blk[B_SIZE * 4 + i] << 11;
        /* since row IDCT results have net left shift by 3 */
        /* this left shift by 8 gives net left shift by 11 */
        /* in order to maintain the same scale as that of  */
        /* coefficients Wi */

        r2 = blk[B_SIZE * 6 + i];
        r3 = blk[B_SIZE * 2 + i];
        r4 = blk[B_SIZE * 1 + i];
        r5 = blk[B_SIZE * 7 + i];
        r6 = blk[B_SIZE * 5 + i];
        r7 = blk[B_SIZE * 3 + i];

        if (!(r1 | r2 | r3 | r4 | r5 | r6 | r7))
        {
            /* shortcut */
            /* execute if values of g(r,1) to g(r,7) in a column*/
            /* are all zeros */

            /* make output of IDCT >>3 or scaled by 1/8 and */
            /* with the proper rounding */
            a = (blk[B_SIZE * 0 + i]) << 3;
            tmpBLK32[B_SIZE * 0 + i] = a;
            tmpBLK32[B_SIZE * 1 + i] = a;
            tmpBLK32[B_SIZE * 2 + i] = a;
            tmpBLK32[B_SIZE * 3 + i] = a;
            tmpBLK32[B_SIZE * 4 + i] = a;
            tmpBLK32[B_SIZE * 5 + i] = a;
            tmpBLK32[B_SIZE * 6 + i] = a;
            tmpBLK32[B_SIZE * 7 + i] = a;
        }
        else
        {
            r0 = (blk[8 * 0 + i] << 11) + 128;

            /* first stage */

            r8 = W7 * (r4 + r5);
            r4 = (r8 + (W1 - W7) * r4);
            /* Multiplication with Wi increases the net left */
            /* shift from 11 to 14,we have to shift back by 3*/
            r5 = (r8 - (W1 + W7) * r5);
            r8 = W3 * (r6 + r7);
            r6 = (r8 - (W3 - W5) * r6);
            r7 = (r8 - (W3 + W5) * r7);

            /* second stage */
            r8 = r0 + r1;
            r0 -= r1;

            r1 = W6 * (r3 + r2);
            r2 = (r1 - (W2 + W6) * r2);
            r3 = (r1 + (W2 - W6) * r3);

            r1 = r4 + r6;
            r4 -= r6;
            r6 = r5 + r7;
            r5 -= r7;

            /* third stage */
            r7 = r8 + r3;
            r8 -= r3;
            r3 = r0 + r2;
            r0 -= r2;
            r2 = (181 * (r4 + r5) + 128) >> 8;  /* rounding */
            r4 = (181 * (r4 - r5) + 128) >> 8;

            /* fourth stage */
            /* net shift of IDCT is >>3 after the following */
            /* shift operation, it makes output of 2-D IDCT */
            /* scaled by 1/8, that is scaled twice by       */
            /* 1/(2*sqrt(2)) for row IDCT and column IDCT.  */
            /* see detail analysis in design doc.           */
            tmpBLK32[0 + i] = (r7 + r1) >> 8;
            tmpBLK32[(1<<3) + i] = (r3 + r2) >> 8;
            tmpBLK32[(2<<3) + i] = (r0 + r4) >> 8;
            tmpBLK32[(3<<3) + i] = (r8 + r6) >> 8;
            tmpBLK32[(4<<3) + i] = (r8 - r6) >> 8;
            tmpBLK32[(5<<3) + i] = (r0 - r4) >> 8;
            tmpBLK32[(6<<3) + i] = (r3 - r2) >> 8;
            tmpBLK32[(7<<3) + i] = (r7 - r1) >> 8;
        }
    }
    /* row (horizontal) IDCT */
    for (i = 0 ; i < B_SIZE; i++)
    {
        /* initialize butterfly nodes at the first stage */

        r1 = ((int32)tmpBLK32[4+(i<<3)]) << 8;
        /* r1 left shift by 11 is to maintain the same  */
        /* scale as that of coefficients (W1,...W7) */
        /* since blk[4] won't multiply with Wi.     */
        /* see detail diagram in design document.   */

        r2 = tmpBLK32[6+(i<<3)];
        r3 = tmpBLK32[2+(i<<3)];
        r4 = tmpBLK32[1+(i<<3)];
        r5 = tmpBLK32[7+(i<<3)];
        r6 = tmpBLK32[5+(i<<3)];
        r7 = tmpBLK32[3+(i<<3)];

        if (!(r1 | r2 | r3 | r4 | r5 | r6 | r7))
        {
            /* shortcut */
            /* execute if values of F(1,v) to F(7,v) in a row*/
            /* are all zeros */

            /* output of row IDCT scaled by 8 */
            a = (((int32)tmpBLK32[0+(i<<3)] + 32) >> 6);
            CLIP_RESULT(a)
            *comp++ = a;
            *comp++ = a;
            *comp++ = a;
            *comp++ = a;
            *comp++ = a;
            *comp++ = a;
            *comp++ = a;
            *comp++ = a;

            comp += offset;
        }

        else
        {
            /* for proper rounding in the fourth stage */
            r0 = (((int32)tmpBLK32[0+(i<<3)]) << 8) + 8192;

            /* first stage */

            r8 = W7 * (r4 + r5) + 4;
            r4 = (r8 + (W1 - W7) * r4) >> 3;
            r5 = (r8 - (W1 + W7) * r5) >> 3;

            r8 = W3 * (r6 + r7) + 4;
            r6 = (r8 - (W3 - W5) * r6) >> 3;
            r7 = (r8 - (W3 + W5) * r7) >> 3;

            /* second stage */
            r8 = r0 + r1;
            r0 -= r1;

            r1 = W6 * (r3 + r2) + 4;
            r2 = (r1 - (W2 + W6) * r2) >> 3;
            r3 = (r1 + (W2 - W6) * r3) >> 3;

            r1 = r4 + r6;
            r4 -= r6;
            r6 = r5 + r7;
            r5 -= r7;

            /* third stage */
            r7 = r8 + r3;
            r8 -= r3;
            r3 = r0 + r2;
            r0 -= r2;
            r2 = (181 * (r4 + r5) + 128) >> 8;    /* rounding */
            r4 = (181 * (r4 - r5) + 128) >> 8;

            /* fourth stage */
            /* net shift of this function is <<3 after the    */
            /* following shift operation, it makes output of  */
            /* row IDCT scaled by 8 to retain 3 bits precision*/
            a = ((r7 + r1) >> 14);
            CLIP_RESULT(a)
            *comp++ = a;
            a = ((r3 + r2) >> 14);
            CLIP_RESULT(a)
            *comp++ = a;
            a = ((r0 + r4) >> 14);
            CLIP_RESULT(a)
            *comp++ = a;
            a = ((r8 + r6) >> 14);
            CLIP_RESULT(a)
            *comp++ = a;
            a = ((r8 - r6) >> 14);
            CLIP_RESULT(a)
            *comp++ = a;
            a = ((r0 - r4) >> 14);
            CLIP_RESULT(a)
            *comp++ = a;
            a = ((r3 - r2) >> 14);
            CLIP_RESULT(a)
            *comp++ = a;
            a = ((r7 - r1) >> 14);
            CLIP_RESULT(a)
            *comp++ = a;

            comp += offset;
        }
    }



    /*----------------------------------------------------------------------------
    ; Return nothing or data or data pointer
    ----------------------------------------------------------------------------*/
    return;
}

void idct(
    int *blk, uint8 *pred, uint8 *dst, int width)
{
    /*----------------------------------------------------------------------------
    ; Define all local variables
    ----------------------------------------------------------------------------*/
    int i;
    int32   tmpBLK[64];
    int32   *tmpBLK32 = &tmpBLK[0];
    int32   r0, r1, r2, r3, r4, r5, r6, r7, r8; /* butterfly nodes */
    int32   a;
    int res;

    /*----------------------------------------------------------------------------
    ; Function body here
    ----------------------------------------------------------------------------*/
    /* two dimensional inverse discrete cosine transform */


    /* column (vertical) IDCT */
    for (i = B_SIZE - 1; i >= 0; i--)
    {
        /* initialize butterfly nodes at first stage */

        r1 = blk[B_SIZE * 4 + i] << 11;
        /* since row IDCT results have net left shift by 3 */
        /* this left shift by 8 gives net left shift by 11 */
        /* in order to maintain the same scale as that of  */
        /* coefficients Wi */

        r2 = blk[B_SIZE * 6 + i];
        r3 = blk[B_SIZE * 2 + i];
        r4 = blk[B_SIZE * 1 + i];
        r5 = blk[B_SIZE * 7 + i];
        r6 = blk[B_SIZE * 5 + i];
        r7 = blk[B_SIZE * 3 + i];

        if (!(r1 | r2 | r3 | r4 | r5 | r6 | r7))
        {
            /* shortcut */
            /* execute if values of g(r,1) to g(r,7) in a column*/
            /* are all zeros */

            /* make output of IDCT >>3 or scaled by 1/8 and */
            /* with the proper rounding */
            a = (blk[B_SIZE * 0 + i]) << 3;
            tmpBLK32[B_SIZE * 0 + i] = a;
            tmpBLK32[B_SIZE * 1 + i] = a;
            tmpBLK32[B_SIZE * 2 + i] = a;
            tmpBLK32[B_SIZE * 3 + i] = a;
            tmpBLK32[B_SIZE * 4 + i] = a;
            tmpBLK32[B_SIZE * 5 + i] = a;
            tmpBLK32[B_SIZE * 6 + i] = a;
            tmpBLK32[B_SIZE * 7 + i] = a;
        }
        else
        {
            r0 = (blk[8 * 0 + i] << 11) + 128;

            /* first stage */

            r8 = W7 * (r4 + r5);
            r4 = (r8 + (W1 - W7) * r4);
            /* Multiplication with Wi increases the net left */
            /* shift from 11 to 14,we have to shift back by 3*/
            r5 = (r8 - (W1 + W7) * r5);
            r8 = W3 * (r6 + r7);
            r6 = (r8 - (W3 - W5) * r6);
            r7 = (r8 - (W3 + W5) * r7);

            /* second stage */
            r8 = r0 + r1;
            r0 -= r1;

            r1 = W6 * (r3 + r2);
            r2 = (r1 - (W2 + W6) * r2);
            r3 = (r1 + (W2 - W6) * r3);

            r1 = r4 + r6;
            r4 -= r6;
            r6 = r5 + r7;
            r5 -= r7;

            /* third stage */
            r7 = r8 + r3;
            r8 -= r3;
            r3 = r0 + r2;
            r0 -= r2;
            r2 = (181 * (r4 + r5) + 128) >> 8;  /* rounding */
            r4 = (181 * (r4 - r5) + 128) >> 8;

            /* fourth stage */
            /* net shift of IDCT is >>3 after the following */
            /* shift operation, it makes output of 2-D IDCT */
            /* scaled by 1/8, that is scaled twice by       */
            /* 1/(2*sqrt(2)) for row IDCT and column IDCT.  */
            /* see detail analysis in design doc.           */
            tmpBLK32[0 + i] = (r7 + r1) >> 8;
            tmpBLK32[(1<<3) + i] = (r3 + r2) >> 8;
            tmpBLK32[(2<<3) + i] = (r0 + r4) >> 8;
            tmpBLK32[(3<<3) + i] = (r8 + r6) >> 8;
            tmpBLK32[(4<<3) + i] = (r8 - r6) >> 8;
            tmpBLK32[(5<<3) + i] = (r0 - r4) >> 8;
            tmpBLK32[(6<<3) + i] = (r3 - r2) >> 8;
            tmpBLK32[(7<<3) + i] = (r7 - r1) >> 8;
        }
    }
    /* row (horizontal) IDCT */
    for (i = B_SIZE - 1; i >= 0; i--)
    {
        /* initialize butterfly nodes at the first stage */

        r1 = ((int32)tmpBLK32[4+(i<<3)]) << 8;
        /* r1 left shift by 11 is to maintain the same  */
        /* scale as that of coefficients (W1,...W7) */
        /* since blk[4] won't multiply with Wi.     */
        /* see detail diagram in design document.   */

        r2 = tmpBLK32[6+(i<<3)];
        r3 = tmpBLK32[2+(i<<3)];
        r4 = tmpBLK32[1+(i<<3)];
        r5 = tmpBLK32[7+(i<<3)];
        r6 = tmpBLK32[5+(i<<3)];
        r7 = tmpBLK32[3+(i<<3)];

        if (!(r1 | r2 | r3 | r4 | r5 | r6 | r7))
        {
            /* shortcut */
            /* execute if values of F(1,v) to F(7,v) in a row*/
            /* are all zeros */

            /* output of row IDCT scaled by 8 */
            a = (tmpBLK32[0+(i<<3)] + 32) >> 6;
            blk[0+(i<<3)] = a;
            blk[1+(i<<3)] = a;
            blk[2+(i<<3)] = a;
            blk[3+(i<<3)] = a;
            blk[4+(i<<3)] = a;
            blk[5+(i<<3)] = a;
            blk[6+(i<<3)] = a;
            blk[7+(i<<3)] = a;

        }

        else
        {
            /* for proper rounding in the fourth stage */
            r0 = (((int32)tmpBLK32[0+(i<<3)]) << 8) + 8192;

            /* first stage */

            r8 = W7 * (r4 + r5) + 4;
            r4 = (r8 + (W1 - W7) * r4) >> 3;
            r5 = (r8 - (W1 + W7) * r5) >> 3;

            r8 = W3 * (r6 + r7) + 4;
            r6 = (r8 - (W3 - W5) * r6) >> 3;
            r7 = (r8 - (W3 + W5) * r7) >> 3;

            /* second stage */
            r8 = r0 + r1;
            r0 -= r1;

            r1 = W6 * (r3 + r2) + 4;
            r2 = (r1 - (W2 + W6) * r2) >> 3;
            r3 = (r1 + (W2 - W6) * r3) >> 3;

            r1 = r4 + r6;
            r4 -= r6;
            r6 = r5 + r7;
            r5 -= r7;

            /* third stage */
            r7 = r8 + r3;
            r8 -= r3;
            r3 = r0 + r2;
            r0 -= r2;
            r2 = (181 * (r4 + r5) + 128) >> 8;    /* rounding */
            r4 = (181 * (r4 - r5) + 128) >> 8;

            /* fourth stage */
            /* net shift of this function is <<3 after the    */
            /* following shift operation, it makes output of  */
            /* row IDCT scaled by 8 to retain 3 bits precision*/
            blk[0+(i<<3)] = (r7 + r1) >> 14;
            blk[1+(i<<3)] = (r3 + r2) >> 14;
            blk[2+(i<<3)] = (r0 + r4) >> 14;
            blk[3+(i<<3)] = (r8 + r6) >> 14;
            blk[4+(i<<3)] = (r8 - r6) >> 14;
            blk[5+(i<<3)] = (r0 - r4) >> 14;
            blk[6+(i<<3)] = (r3 - r2) >> 14;
            blk[7+(i<<3)] = (r7 - r1) >> 14;
        }
        /*  add with prediction ,  08/03/05 */
        res = (*pred++ + block[0+(i<<3)]);
        CLIP_RESULT(res);
        *dst++ = res;
        res = (*pred++ + block[1+(i<<3)]);
        CLIP_RESULT(res);
        *dst++ = res;
        res = (*pred++ + block[2+(i<<3)]);
        CLIP_RESULT(res);
        *dst++ = res;
        res = (*pred++ + block[3+(i<<3)]);
        CLIP_RESULT(res);
        *dst++ = res;
        res = (*pred++ + block[4+(i<<3)]);
        CLIP_RESULT(res);
        *dst++ = res;
        res = (*pred++ + block[5+(i<<3)]);
        CLIP_RESULT(res);
        *dst++ = res;
        res = (*pred++ + block[6+(i<<3)]);
        CLIP_RESULT(res);
        *dst++ = res;
        res = (*pred++ + block[7+(i<<3)]);
        CLIP_RESULT(res);
        *dst++ = res;

        pred += 8;
        dst += (width - 8);
    }



    /*----------------------------------------------------------------------------
    ; Return nothing or data or data pointer
    ----------------------------------------------------------------------------*/
    return;
}

#endif
/*----------------------------------------------------------------------------
; End Function: idct
----------------------------------------------------------------------------*/

