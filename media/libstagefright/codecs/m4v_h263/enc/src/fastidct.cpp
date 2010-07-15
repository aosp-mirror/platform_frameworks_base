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
 REVISION HISTORY
 Who:   Date: July/2001
 Description:   1. Optimized BlockIDCT bitmap checking.
                2. Rearranged functions.
                3. Do column IDCT first, then row IDCT.
                4. Combine motion comp and IDCT, require
                   two sets of row IDCTs one for INTRA
                   and one for INTER.
                5. Add AAN IDCT

 Who:   Date: 8/16/01
                1. Increase the input precision to 8 bits, i.e. change RDCTBITS
                   to 11, have to comment out all in-line assembly since 16 bit
                    multiplication doesn't work. Try to use diffent precision with
                    32 bit mult. but hasn't finished. Turns out that without in-line
                    assembly the performance doesn't change much (only 1%).
 Who:   Date: 9/04/05
                1. Replace AAN IDCT with Chen's IDCT to accommodate 16 bit data type.

*/
#include "mp4def.h"
#include "mp4enc_lib.h"
#include "mp4lib_int.h"
#include "dct.h"

#define ADD_CLIP    { \
            tmp = *rec + tmp; \
        if((UInt)tmp > mask) tmp = mask&(~(tmp>>31)); \
        *rec++ = tmp;   \
        }

#define INTRA_CLIP  { \
        if((UInt)tmp > mask) tmp = mask&(~(tmp>>31)); \
        *rec++ = tmp;   \
        }


#define CLIP_RESULT(x)      if((UInt)x > 0xFF){x = 0xFF & (~(x>>31));}
#define ADD_AND_CLIP1(x)    x += (pred_word&0xFF); CLIP_RESULT(x);
#define ADD_AND_CLIP2(x)    x += ((pred_word>>8)&0xFF); CLIP_RESULT(x);
#define ADD_AND_CLIP3(x)    x += ((pred_word>>16)&0xFF); CLIP_RESULT(x);
#define ADD_AND_CLIP4(x)    x += ((pred_word>>24)&0xFF); CLIP_RESULT(x);


void idct_col0(Short *blk)
{
    OSCL_UNUSED_ARG(blk);

    return;
}

void idct_col1(Short *blk)
{
    blk[0] = blk[8] = blk[16] = blk[24] = blk[32] = blk[40] = blk[48] = blk[56] =
                                              blk[0] << 3;
    return ;
}

void idct_col2(Short *blk)
{
    int32 x0, x1, x3, x5, x7;//, x8;

    x1 = blk[8];
    x0 = ((int32)blk[0] << 11) + 128;
    /* both upper and lower*/

    x7 = W7 * x1;
    x1 = W1 * x1;

    x3 = x7;
    x5 = (181 * (x1 - x7) + 128) >> 8;
    x7 = (181 * (x1 + x7) + 128) >> 8;

    blk[0] = (x0 + x1) >> 8;
    blk[8] = (x0 + x7) >> 8;
    blk[16] = (x0 + x5) >> 8;
    blk[24] = (x0 + x3) >> 8;
    blk[56] = (x0 - x1) >> 8;
    blk[48] = (x0 - x7) >> 8;
    blk[40] = (x0 - x5) >> 8;
    blk[32] = (x0 - x3) >> 8;
    return ;
}

void idct_col3(Short *blk)
{
    int32 x0, x1, x2, x3, x4, x5, x6, x7, x8;

    x2 = blk[16];
    x1 = blk[8];
    x0 = ((int32)blk[0] << 11) + 128;

    x4 = x0;
    x6 = W6 * x2;
    x2 = W2 * x2;
    x8 = x0 - x2;
    x0 += x2;
    x2 = x8;
    x8 = x4 - x6;
    x4 += x6;
    x6 = x8;

    x7 = W7 * x1;
    x1 = W1 * x1;
    x3 = x7;
    x5 = (181 * (x1 - x7) + 128) >> 8;
    x7 = (181 * (x1 + x7) + 128) >> 8;

    blk[0] = (x0 + x1) >> 8;
    blk[8] = (x4 + x7) >> 8;
    blk[16] = (x6 + x5) >> 8;
    blk[24] = (x2 + x3) >> 8;
    blk[56] = (x0 - x1) >> 8;
    blk[48] = (x4 - x7) >> 8;
    blk[40] = (x6 - x5) >> 8;
    blk[32] = (x2 - x3) >> 8;
    return ;
}

void idct_col4(Short *blk)
{
    int32 x0, x1, x2, x3, x4, x5, x6, x7, x8;
    x2 = blk[16];
    x1 = blk[8];
    x3 = blk[24];
    x0 = ((int32)blk[0] << 11) + 128;

    x4 = x0;
    x6 = W6 * x2;
    x2 = W2 * x2;
    x8 = x0 - x2;
    x0 += x2;
    x2 = x8;
    x8 = x4 - x6;
    x4 += x6;
    x6 = x8;

    x7 = W7 * x1;
    x1 = W1 * x1;
    x5 = W3 * x3;
    x3 = -W5 * x3;
    x8 = x1 - x5;
    x1 += x5;
    x5 = x8;
    x8 = x7 - x3;
    x3 += x7;
    x7 = (181 * (x5 + x8) + 128) >> 8;
    x5 = (181 * (x5 - x8) + 128) >> 8;


    blk[0] = (x0 + x1) >> 8;
    blk[8] = (x4 + x7) >> 8;
    blk[16] = (x6 + x5) >> 8;
    blk[24] = (x2 + x3) >> 8;
    blk[56] = (x0 - x1) >> 8;
    blk[48] = (x4 - x7) >> 8;
    blk[40] = (x6 - x5) >> 8;
    blk[32] = (x2 - x3) >> 8;
    return ;
}

#ifndef SMALL_DCT
void idct_col0x40(Short *blk)
{
    int32 x1, x3, x5, x7;//, x8;

    x1 = blk[8];
    /* both upper and lower*/

    x7 = W7 * x1;
    x1 = W1 * x1;

    x3 = x7;
    x5 = (181 * (x1 - x7) + 128) >> 8;
    x7 = (181 * (x1 + x7) + 128) >> 8;

    blk[0] = (128 + x1) >> 8;
    blk[8] = (128 + x7) >> 8;
    blk[16] = (128 + x5) >> 8;
    blk[24] = (128 + x3) >> 8;
    blk[56] = (128 - x1) >> 8;
    blk[48] = (128 - x7) >> 8;
    blk[40] = (128 - x5) >> 8;
    blk[32] = (128 - x3) >> 8;

    return ;
}

void idct_col0x20(Short *blk)
{
    int32 x0, x2, x4, x6;

    x2 = blk[16];
    x6 = W6 * x2;
    x2 = W2 * x2;
    x0 = 128 + x2;
    x2 = 128 - x2;
    x4 = 128 + x6;
    x6 = 128 - x6;

    blk[0] = (x0) >> 8;
    blk[56] = (x0) >> 8;
    blk[8] = (x4) >> 8;
    blk[48] = (x4) >> 8;
    blk[16] = (x6) >> 8;
    blk[40] = (x6) >> 8;
    blk[24] = (x2) >> 8;
    blk[32] = (x2) >> 8;

    return ;
}

void idct_col0x10(Short *blk)
{
    int32 x1, x3, x5,  x7;

    x3 = blk[24];
    x1 = W3 * x3;
    x3 = W5 * x3;

    x7 = (181 * (x3 - x1) + 128) >> 8;
    x5 = (-181 * (x1 + x3) + 128) >> 8;


    blk[0] = (128 + x1) >> 8;
    blk[8] = (128 + x7) >> 8;
    blk[16] = (128 + x5) >> 8;
    blk[24] = (128 - x3) >> 8;
    blk[56] = (128 - x1) >> 8;
    blk[48] = (128 - x7) >> 8;
    blk[40] = (128 - x5) >> 8;
    blk[32] = (128 + x3) >> 8;

    return ;
}

#endif /* SMALL_DCT */

void idct_col(Short *blk)
{
    int32 x0, x1, x2, x3, x4, x5, x6, x7, x8;

    x1 = (int32)blk[32] << 11;
    x2 = blk[48];
    x3 = blk[16];
    x4 = blk[8];
    x5 = blk[56];
    x6 = blk[40];
    x7 = blk[24];
    x0 = ((int32)blk[0] << 11) + 128;

    /* first stage */
    x8 = W7 * (x4 + x5);
    x4 = x8 + (W1 - W7) * x4;
    x5 = x8 - (W1 + W7) * x5;
    x8 = W3 * (x6 + x7);
    x6 = x8 - (W3 - W5) * x6;
    x7 = x8 - (W3 + W5) * x7;

    /* second stage */
    x8 = x0 + x1;
    x0 -= x1;
    x1 = W6 * (x3 + x2);
    x2 = x1 - (W2 + W6) * x2;
    x3 = x1 + (W2 - W6) * x3;
    x1 = x4 + x6;
    x4 -= x6;
    x6 = x5 + x7;
    x5 -= x7;

    /* third stage */
    x7 = x8 + x3;
    x8 -= x3;
    x3 = x0 + x2;
    x0 -= x2;
    x2 = (181 * (x4 + x5) + 128) >> 8;
    x4 = (181 * (x4 - x5) + 128) >> 8;

    /* fourth stage */
    blk[0]    = (x7 + x1) >> 8;
    blk[8] = (x3 + x2) >> 8;
    blk[16] = (x0 + x4) >> 8;
    blk[24] = (x8 + x6) >> 8;
    blk[32] = (x8 - x6) >> 8;
    blk[40] = (x0 - x4) >> 8;
    blk[48] = (x3 - x2) >> 8;
    blk[56] = (x7 - x1) >> 8;

    return ;
}

/* This function should not be called at all ****/
void idct_row0Inter(Short *srce, UChar *rec, Int lx)
{
    OSCL_UNUSED_ARG(srce);

    OSCL_UNUSED_ARG(rec);

    OSCL_UNUSED_ARG(lx);

    return;
}

void idct_row1Inter(Short *blk, UChar *rec, Int lx)
{
    int tmp;
    int i = 8;
    uint32 pred_word, dst_word;
    int res, res2;

    /* preset the offset, such that we can take advantage pre-offset addressing mode   */
    rec -= lx;
    blk -= 8;

    while (i--)
    {
        tmp = (*(blk += 8) + 32) >> 6;
        *blk = 0;

        pred_word = *((uint32*)(rec += lx)); /* read 4 bytes from pred */
        res = tmp + (pred_word & 0xFF);
        CLIP_RESULT(res);
        res2 = tmp + ((pred_word >> 8) & 0xFF);
        CLIP_RESULT(res2);
        dst_word = (res2 << 8) | res;
        res = tmp + ((pred_word >> 16) & 0xFF);
        CLIP_RESULT(res);
        dst_word |= (res << 16);
        res = tmp + ((pred_word >> 24) & 0xFF);
        CLIP_RESULT(res);
        dst_word |= (res << 24);
        *((uint32*)rec) = dst_word; /* save 4 bytes to dst */

        pred_word = *((uint32*)(rec + 4)); /* read 4 bytes from pred */
        res = tmp + (pred_word & 0xFF);
        CLIP_RESULT(res);
        res2 = tmp + ((pred_word >> 8) & 0xFF);
        CLIP_RESULT(res2);
        dst_word = (res2 << 8) | res;
        res = tmp + ((pred_word >> 16) & 0xFF);
        CLIP_RESULT(res);
        dst_word |= (res << 16);
        res = tmp + ((pred_word >> 24) & 0xFF);
        CLIP_RESULT(res);
        dst_word |= (res << 24);
        *((uint32*)(rec + 4)) = dst_word; /* save 4 bytes to dst */
    }
    return;
}

void idct_row2Inter(Short *blk, UChar *rec, Int lx)
{
    int32 x0, x1, x2, x4, x5;
    int i = 8;
    uint32 pred_word, dst_word;
    int res, res2;

    /* preset the offset, such that we can take advantage pre-offset addressing mode   */
    rec -= lx;
    blk -= 8;

    while (i--)
    {
        /* shortcut */
        x4 = blk[9];
        blk[9] = 0;
        x0 = ((*(blk += 8)) << 8) + 8192;
        *blk = 0;  /* for proper rounding in the fourth stage */

        /* first stage */
        x5 = (W7 * x4 + 4) >> 3;
        x4 = (W1 * x4 + 4) >> 3;

        /* third stage */
        x2 = (181 * (x4 + x5) + 128) >> 8;
        x1 = (181 * (x4 - x5) + 128) >> 8;

        /* fourth stage */
        pred_word = *((uint32*)(rec += lx)); /* read 4 bytes from pred */
        res = (x0 + x4) >> 14;
        ADD_AND_CLIP1(res);
        res2 = (x0 + x2) >> 14;
        ADD_AND_CLIP2(res2);
        dst_word = (res2 << 8) | res;
        res = (x0 + x1) >> 14;
        ADD_AND_CLIP3(res);
        dst_word |= (res << 16);
        res = (x0 + x5) >> 14;
        ADD_AND_CLIP4(res);
        dst_word |= (res << 24);
        *((uint32*)rec) = dst_word; /* save 4 bytes to dst */

        pred_word = *((uint32*)(rec + 4)); /* read 4 bytes from pred */
        res = (x0 - x5) >> 14;
        ADD_AND_CLIP1(res);
        res2 = (x0 - x1) >> 14;
        ADD_AND_CLIP2(res2);
        dst_word = (res2 << 8) | res;
        res = (x0 - x2) >> 14;
        ADD_AND_CLIP3(res);
        dst_word |= (res << 16);
        res = (x0 - x4) >> 14;
        ADD_AND_CLIP4(res);
        dst_word |= (res << 24);
        *((uint32*)(rec + 4)) = dst_word; /* save 4 bytes to dst */
    }
    return ;
}

void idct_row3Inter(Short *blk, UChar *rec, Int lx)
{
    int32 x0, x1, x2, x3, x4, x5, x6, x7, x8;
    int i = 8;
    uint32 pred_word, dst_word;
    int res, res2;

    /* preset the offset, such that we can take advantage pre-offset addressing mode   */
    rec -= lx;
    blk -= 8;

    while (i--)
    {
        x2 = blk[10];
        blk[10] = 0;
        x1 = blk[9];
        blk[9] = 0;
        x0 = ((*(blk += 8)) << 8) + 8192;
        *blk = 0;  /* for proper rounding in the fourth stage */
        /* both upper and lower*/
        /* both x2orx6 and x0orx4 */

        x4 = x0;
        x6 = (W6 * x2 + 4) >> 3;
        x2 = (W2 * x2 + 4) >> 3;
        x8 = x0 - x2;
        x0 += x2;
        x2 = x8;
        x8 = x4 - x6;
        x4 += x6;
        x6 = x8;

        x7 = (W7 * x1 + 4) >> 3;
        x1 = (W1 * x1 + 4) >> 3;
        x3 = x7;
        x5 = (181 * (x1 - x7) + 128) >> 8;
        x7 = (181 * (x1 + x7) + 128) >> 8;

        pred_word = *((uint32*)(rec += lx)); /* read 4 bytes from pred */
        res = (x0 + x1) >> 14;
        ADD_AND_CLIP1(res);
        res2 = (x4 + x7) >> 14;
        ADD_AND_CLIP2(res2);
        dst_word = (res2 << 8) | res;
        res = (x6 + x5) >> 14;
        ADD_AND_CLIP3(res);
        dst_word |= (res << 16);
        res = (x2 + x3) >> 14;
        ADD_AND_CLIP4(res);
        dst_word |= (res << 24);
        *((uint32*)rec) = dst_word; /* save 4 bytes to dst */

        pred_word = *((uint32*)(rec + 4)); /* read 4 bytes from pred */
        res = (x2 - x3) >> 14;
        ADD_AND_CLIP1(res);
        res2 = (x6 - x5) >> 14;
        ADD_AND_CLIP2(res2);
        dst_word = (res2 << 8) | res;
        res = (x4 - x7) >> 14;
        ADD_AND_CLIP3(res);
        dst_word |= (res << 16);
        res = (x0 - x1) >> 14;
        ADD_AND_CLIP4(res);
        dst_word |= (res << 24);
        *((uint32*)(rec + 4)) = dst_word; /* save 4 bytes to dst */
    }

    return ;
}

void idct_row4Inter(Short *blk, UChar *rec, Int lx)
{
    int32 x0, x1, x2, x3, x4, x5, x6, x7, x8;
    int i = 8;
    uint32 pred_word, dst_word;
    int res, res2;

    /* preset the offset, such that we can take advantage pre-offset addressing mode   */
    rec -= lx;
    blk -= 8;

    while (i--)
    {
        x2 = blk[10];
        blk[10] = 0;
        x1 = blk[9];
        blk[9] = 0;
        x3 = blk[11];
        blk[11] = 0;
        x0 = ((*(blk += 8)) << 8) + 8192;
        *blk = 0;   /* for proper rounding in the fourth stage */

        x4 = x0;
        x6 = (W6 * x2 + 4) >> 3;
        x2 = (W2 * x2 + 4) >> 3;
        x8 = x0 - x2;
        x0 += x2;
        x2 = x8;
        x8 = x4 - x6;
        x4 += x6;
        x6 = x8;

        x7 = (W7 * x1 + 4) >> 3;
        x1 = (W1 * x1 + 4) >> 3;
        x5 = (W3 * x3 + 4) >> 3;
        x3 = (- W5 * x3 + 4) >> 3;
        x8 = x1 - x5;
        x1 += x5;
        x5 = x8;
        x8 = x7 - x3;
        x3 += x7;
        x7 = (181 * (x5 + x8) + 128) >> 8;
        x5 = (181 * (x5 - x8) + 128) >> 8;

        pred_word = *((uint32*)(rec += lx)); /* read 4 bytes from pred */
        res = (x0 + x1) >> 14;
        ADD_AND_CLIP1(res);
        res2 = (x4 + x7) >> 14;
        ADD_AND_CLIP2(res2);
        dst_word = (res2 << 8) | res;
        res = (x6 + x5) >> 14;
        ADD_AND_CLIP3(res);
        dst_word |= (res << 16);
        res = (x2 + x3) >> 14;
        ADD_AND_CLIP4(res);
        dst_word |= (res << 24);
        *((uint32*)rec) = dst_word; /* save 4 bytes to dst */

        pred_word = *((uint32*)(rec + 4)); /* read 4 bytes from pred */
        res = (x2 - x3) >> 14;
        ADD_AND_CLIP1(res);
        res2 = (x6 - x5) >> 14;
        ADD_AND_CLIP2(res2);
        dst_word = (res2 << 8) | res;
        res = (x4 - x7) >> 14;
        ADD_AND_CLIP3(res);
        dst_word |= (res << 16);
        res = (x0 - x1) >> 14;
        ADD_AND_CLIP4(res);
        dst_word |= (res << 24);
        *((uint32*)(rec + 4)) = dst_word; /* save 4 bytes to dst */
    }
    return ;
}

#ifndef SMALL_DCT
void idct_row0x40Inter(Short *blk, UChar *rec, Int lx)
{
    int32 x1, x2, x4, x5;
    int i = 8;
    uint32 pred_word, dst_word;
    int res, res2;

    /* preset the offset, such that we can take advantage pre-offset addressing mode   */
    rec -= lx;

    while (i--)
    {
        /* shortcut */
        x4 = blk[1];
        blk[1] = 0;
        blk += 8;  /* for proper rounding in the fourth stage */

        /* first stage */
        x5 = (W7 * x4 + 4) >> 3;
        x4 = (W1 * x4 + 4) >> 3;

        /* third stage */
        x2 = (181 * (x4 + x5) + 128) >> 8;
        x1 = (181 * (x4 - x5) + 128) >> 8;

        /* fourth stage */
        pred_word = *((uint32*)(rec += lx)); /* read 4 bytes from pred */
        res = (8192 + x4) >> 14;
        ADD_AND_CLIP1(res);
        res2 = (8192 + x2) >> 14;
        ADD_AND_CLIP2(res2);
        dst_word = (res2 << 8) | res;
        res = (8192 + x1) >> 14;
        ADD_AND_CLIP3(res);
        dst_word |= (res << 16);
        res = (8192 + x5) >> 14;
        ADD_AND_CLIP4(res);
        dst_word |= (res << 24);
        *((uint32*)rec) = dst_word; /* save 4 bytes to dst */

        pred_word = *((uint32*)(rec + 4)); /* read 4 bytes from pred */
        res = (8192 - x5) >> 14;
        ADD_AND_CLIP1(res);
        res2 = (8192 - x1) >> 14;
        ADD_AND_CLIP2(res2);
        dst_word = (res2 << 8) | res;
        res = (8192 - x2) >> 14;
        ADD_AND_CLIP3(res);
        dst_word |= (res << 16);
        res = (8192 - x4) >> 14;
        ADD_AND_CLIP4(res);
        dst_word |= (res << 24);
        *((uint32*)(rec + 4)) = dst_word; /* save 4 bytes to dst */
    }
    return ;
}

void idct_row0x20Inter(Short *blk, UChar *rec, Int lx)
{
    int32 x0, x2, x4, x6;
    int i = 8;
    uint32 pred_word, dst_word;
    int res, res2;

    /* preset the offset, such that we can take advantage pre-offset addressing mode   */
    rec -= lx;

    while (i--)
    {
        x2 = blk[2];
        blk[2] = 0;
        blk += 8; /* for proper rounding in the fourth stage */
        /* both upper and lower*/
        /* both x2orx6 and x0orx4 */
        x6 = (W6 * x2 + 4) >> 3;
        x2 = (W2 * x2 + 4) >> 3;
        x0 = 8192 + x2;
        x2 = 8192 - x2;
        x4 = 8192 + x6;
        x6 = 8192 - x6;

        pred_word = *((uint32*)(rec += lx)); /* read 4 bytes from pred */
        res = (x0) >> 14;
        ADD_AND_CLIP1(res);
        res2 = (x4) >> 14;
        ADD_AND_CLIP2(res2);
        dst_word = (res2 << 8) | res;
        res = (x6) >> 14;
        ADD_AND_CLIP3(res);
        dst_word |= (res << 16);
        res = (x2) >> 14;
        ADD_AND_CLIP4(res);
        dst_word |= (res << 24);
        *((uint32*)rec) = dst_word; /* save 4 bytes to dst */

        pred_word = *((uint32*)(rec + 4)); /* read 4 bytes from pred */
        res = (x2) >> 14;
        ADD_AND_CLIP1(res);
        res2 = (x6) >> 14;
        ADD_AND_CLIP2(res2);
        dst_word = (res2 << 8) | res;
        res = (x4) >> 14;
        ADD_AND_CLIP3(res);
        dst_word |= (res << 16);
        res = (x0) >> 14;
        ADD_AND_CLIP4(res);
        dst_word |= (res << 24);
        *((uint32*)(rec + 4)) = dst_word; /* save 4 bytes to dst */
    }

    return ;
}

void idct_row0x10Inter(Short *blk, UChar *rec, Int lx)
{
    int32 x1, x3, x5, x7;
    int i = 8;
    uint32 pred_word, dst_word;
    int res, res2;

    /* preset the offset, such that we can take advantage pre-offset addressing mode   */
    rec -= lx;

    while (i--)
    {
        x3 = blk[3];
        blk[3] = 0;
        blk += 8;

        x1 = (W3 * x3 + 4) >> 3;
        x3 = (-W5 * x3 + 4) >> 3;

        x7 = (-181 * (x3 + x1) + 128) >> 8;
        x5 = (181 * (x3 - x1) + 128) >> 8;

        pred_word = *((uint32*)(rec += lx)); /* read 4 bytes from pred */
        res = (8192 + x1) >> 14;
        ADD_AND_CLIP1(res);
        res2 = (8192 + x7) >> 14;
        ADD_AND_CLIP2(res2);
        dst_word = (res2 << 8) | res;
        res = (8192 + x5) >> 14;
        ADD_AND_CLIP3(res);
        dst_word |= (res << 16);
        res = (8192 + x3) >> 14;
        ADD_AND_CLIP4(res);
        dst_word |= (res << 24);
        *((uint32*)rec) = dst_word; /* save 4 bytes to dst */

        pred_word = *((uint32*)(rec + 4)); /* read 4 bytes from pred */
        res = (8192 - x3) >> 14;
        ADD_AND_CLIP1(res);
        res2 = (8192 - x5) >> 14;
        ADD_AND_CLIP2(res2);
        dst_word = (res2 << 8) | res;
        res = (8192 - x7) >> 14;
        ADD_AND_CLIP3(res);
        dst_word |= (res << 16);
        res = (8192 - x1) >> 14;
        ADD_AND_CLIP4(res);
        dst_word |= (res << 24);
        *((uint32*)(rec + 4)) = dst_word; /* save 4 bytes to dst */
    }
    return ;
}

#endif /* SMALL_DCT */

void idct_rowInter(Short *blk, UChar *rec, Int lx)
{
    int32 x0, x1, x2, x3, x4, x5, x6, x7, x8;
    int i = 8;
    uint32 pred_word, dst_word;
    int res, res2;

    /* preset the offset, such that we can take advantage pre-offset addressing mode   */
    rec -= lx;
    blk -= 8;

    while (i--)
    {
        x1 = (int32)blk[12] << 8;
        blk[12] = 0;
        x2 = blk[14];
        blk[14] = 0;
        x3 = blk[10];
        blk[10] = 0;
        x4 = blk[9];
        blk[9] = 0;
        x5 = blk[15];
        blk[15] = 0;
        x6 = blk[13];
        blk[13] = 0;
        x7 = blk[11];
        blk[11] = 0;
        x0 = ((*(blk += 8)) << 8) + 8192;
        *blk = 0;   /* for proper rounding in the fourth stage */

        /* first stage */
        x8 = W7 * (x4 + x5) + 4;
        x4 = (x8 + (W1 - W7) * x4) >> 3;
        x5 = (x8 - (W1 + W7) * x5) >> 3;
        x8 = W3 * (x6 + x7) + 4;
        x6 = (x8 - (W3 - W5) * x6) >> 3;
        x7 = (x8 - (W3 + W5) * x7) >> 3;

        /* second stage */
        x8 = x0 + x1;
        x0 -= x1;
        x1 = W6 * (x3 + x2) + 4;
        x2 = (x1 - (W2 + W6) * x2) >> 3;
        x3 = (x1 + (W2 - W6) * x3) >> 3;
        x1 = x4 + x6;
        x4 -= x6;
        x6 = x5 + x7;
        x5 -= x7;

        /* third stage */
        x7 = x8 + x3;
        x8 -= x3;
        x3 = x0 + x2;
        x0 -= x2;
        x2 = (181 * (x4 + x5) + 128) >> 8;
        x4 = (181 * (x4 - x5) + 128) >> 8;

        /* fourth stage */
        pred_word = *((uint32*)(rec += lx)); /* read 4 bytes from pred */

        res = (x7 + x1) >> 14;
        ADD_AND_CLIP1(res);
        res2 = (x3 + x2) >> 14;
        ADD_AND_CLIP2(res2);
        dst_word = (res2 << 8) | res;
        res = (x0 + x4) >> 14;
        ADD_AND_CLIP3(res);
        dst_word |= (res << 16);
        res = (x8 + x6) >> 14;
        ADD_AND_CLIP4(res);
        dst_word |= (res << 24);
        *((uint32*)rec) = dst_word; /* save 4 bytes to dst */

        pred_word = *((uint32*)(rec + 4)); /* read 4 bytes from pred */

        res = (x8 - x6) >> 14;
        ADD_AND_CLIP1(res);
        res2 = (x0 - x4) >> 14;
        ADD_AND_CLIP2(res2);
        dst_word = (res2 << 8) | res;
        res = (x3 - x2) >> 14;
        ADD_AND_CLIP3(res);
        dst_word |= (res << 16);
        res = (x7 - x1) >> 14;
        ADD_AND_CLIP4(res);
        dst_word |= (res << 24);
        *((uint32*)(rec + 4)) = dst_word; /* save 4 bytes to dst */
    }
    return;
}

void idct_row0Intra(Short *srce, UChar *rec, Int lx)
{
    OSCL_UNUSED_ARG(srce);

    OSCL_UNUSED_ARG(rec);

    OSCL_UNUSED_ARG(lx);

    return;
}

void idct_row1Intra(Short *blk, UChar *rec, Int lx)
{
    int32 tmp;
    int i = 8;

    rec -= lx;
    blk -= 8;
    while (i--)
    {
        tmp = ((*(blk += 8) + 32) >> 6);
        *blk = 0;
        CLIP_RESULT(tmp)

        tmp |= (tmp << 8);
        tmp |= (tmp << 16);
        *((uint32*)(rec += lx)) = tmp;
        *((uint32*)(rec + 4)) = tmp;
    }
    return;
}

void idct_row2Intra(Short *blk, UChar *rec, Int lx)
{
    int32 x0, x1, x2, x4, x5;
    int res, res2;
    uint32 dst_word;
    int i = 8;

    rec -= lx;
    blk -= 8;
    while (i--)
    {
        /* shortcut */
        x4 = blk[9];
        blk[9] = 0;
        x0 = ((*(blk += 8)) << 8) + 8192;
        *blk = 0;   /* for proper rounding in the fourth stage */

        /* first stage */
        x5 = (W7 * x4 + 4) >> 3;
        x4 = (W1 * x4 + 4) >> 3;

        /* third stage */
        x2 = (181 * (x4 + x5) + 128) >> 8;
        x1 = (181 * (x4 - x5) + 128) >> 8;

        /* fourth stage */
        res = ((x0 + x4) >> 14);
        CLIP_RESULT(res)
        res2 = ((x0 + x2) >> 14);
        CLIP_RESULT(res2)
        dst_word = (res2 << 8) | res;
        res = ((x0 + x1) >> 14);
        CLIP_RESULT(res)
        dst_word |= (res << 16);
        res = ((x0 + x5) >> 14);
        CLIP_RESULT(res)
        dst_word |= (res << 24);
        *((uint32*)(rec += lx)) = dst_word;

        res = ((x0 - x5) >> 14);
        CLIP_RESULT(res)
        res2 = ((x0 - x1) >> 14);
        CLIP_RESULT(res2)
        dst_word = (res2 << 8) | res;
        res = ((x0 - x2) >> 14);
        CLIP_RESULT(res)
        dst_word |= (res << 16);
        res = ((x0 - x4) >> 14);
        CLIP_RESULT(res)
        dst_word |= (res << 24);
        *((uint32*)(rec + 4)) = dst_word;
    }
    return ;
}

void idct_row3Intra(Short *blk, UChar *rec, Int lx)
{
    int32 x0, x1, x2, x3, x4, x5, x6, x7, x8;
    int res, res2;
    uint32 dst_word;
    int i = 8;

    rec -= lx;
    blk -= 8;
    while (i--)
    {
        x2 = blk[10];
        blk[10] = 0;
        x1 = blk[9];
        blk[9] = 0;
        x0 = ((*(blk += 8)) << 8) + 8192;
        *blk = 0;/* for proper rounding in the fourth stage */
        /* both upper and lower*/
        /* both x2orx6 and x0orx4 */

        x4 = x0;
        x6 = (W6 * x2 + 4) >> 3;
        x2 = (W2 * x2 + 4) >> 3;
        x8 = x0 - x2;
        x0 += x2;
        x2 = x8;
        x8 = x4 - x6;
        x4 += x6;
        x6 = x8;

        x7 = (W7 * x1 + 4) >> 3;
        x1 = (W1 * x1 + 4) >> 3;
        x3 = x7;
        x5 = (181 * (x1 - x7) + 128) >> 8;
        x7 = (181 * (x1 + x7) + 128) >> 8;

        res = ((x0 + x1) >> 14);
        CLIP_RESULT(res)
        res2 = ((x4 + x7) >> 14);
        CLIP_RESULT(res2)
        dst_word = (res2 << 8) | res;
        res = ((x6 + x5) >> 14);
        CLIP_RESULT(res)
        dst_word |= (res << 16);
        res = ((x2 + x3) >> 14);
        CLIP_RESULT(res)
        dst_word |= (res << 24);
        *((uint32*)(rec += lx)) = dst_word;

        res = ((x2 - x3) >> 14);
        CLIP_RESULT(res)
        res2 = ((x6 - x5) >> 14);
        CLIP_RESULT(res2)
        dst_word = (res2 << 8) | res;
        res = ((x4 - x7) >> 14);
        CLIP_RESULT(res)
        dst_word |= (res << 16);
        res = ((x0 - x1) >> 14);
        CLIP_RESULT(res)
        dst_word |= (res << 24);
        *((uint32*)(rec + 4)) = dst_word;

    }
    return ;
}

void idct_row4Intra(Short *blk, UChar *rec, Int lx)
{
    int32 x0, x1, x2, x3, x4, x5, x6, x7, x8;
    int res, res2;
    uint32 dst_word;
    int i = 8;

    rec -= lx;
    blk -= 8;
    while (i--)
    {
        x2 = blk[10];
        blk[10] = 0;
        x1 = blk[9];
        blk[9] = 0;
        x3 = blk[11];
        blk[11] = 0;
        x0 = ((*(blk += 8)) << 8) + 8192;
        *blk = 0; /* for proper rounding in the fourth stage */

        x4 = x0;
        x6 = (W6 * x2 + 4) >> 3;
        x2 = (W2 * x2 + 4) >> 3;
        x8 = x0 - x2;
        x0 += x2;
        x2 = x8;
        x8 = x4 - x6;
        x4 += x6;
        x6 = x8;

        x7 = (W7 * x1 + 4) >> 3;
        x1 = (W1 * x1 + 4) >> 3;
        x5 = (W3 * x3 + 4) >> 3;
        x3 = (- W5 * x3 + 4) >> 3;
        x8 = x1 - x5;
        x1 += x5;
        x5 = x8;
        x8 = x7 - x3;
        x3 += x7;
        x7 = (181 * (x5 + x8) + 128) >> 8;
        x5 = (181 * (x5 - x8) + 128) >> 8;

        res = ((x0 + x1) >> 14);
        CLIP_RESULT(res)
        res2 = ((x4 + x7) >> 14);
        CLIP_RESULT(res2)
        dst_word = (res2 << 8) | res;
        res = ((x6 + x5) >> 14);
        CLIP_RESULT(res)
        dst_word |= (res << 16);
        res = ((x2 + x3) >> 14);
        CLIP_RESULT(res)
        dst_word |= (res << 24);
        *((uint32*)(rec += lx)) = dst_word;

        res = ((x2 - x3) >> 14);
        CLIP_RESULT(res)
        res2 = ((x6 - x5) >> 14);
        CLIP_RESULT(res2)
        dst_word = (res2 << 8) | res;
        res = ((x4 - x7) >> 14);
        CLIP_RESULT(res)
        dst_word |= (res << 16);
        res = ((x0 - x1) >> 14);
        CLIP_RESULT(res)
        dst_word |= (res << 24);
        *((uint32*)(rec + 4)) = dst_word;
    }

    return ;
}

#ifndef SMALL_DCT
void idct_row0x40Intra(Short *blk, UChar *rec, Int lx)
{
    int32  x1, x2, x4, x5;
    int res, res2;
    uint32 dst_word;
    int i = 8;

    rec -= lx;

    while (i--)
    {
        /* shortcut */
        x4 = blk[1];
        blk[1] = 0;
        blk += 8;

        /* first stage */
        x5 = (W7 * x4 + 4) >> 3;
        x4 = (W1 * x4 + 4) >> 3;

        /* third stage */
        x2 = (181 * (x4 + x5) + 128) >> 8;
        x1 = (181 * (x4 - x5) + 128) >> 8;

        /* fourth stage */
        res = ((8192 + x4) >> 14);
        CLIP_RESULT(res)
        res2 = ((8192 + x2) >> 14);
        CLIP_RESULT(res2)
        dst_word = (res2 << 8) | res;
        res = ((8192 + x1) >> 14);
        CLIP_RESULT(res)
        dst_word |= (res << 16);
        res = ((8192 + x5) >> 14);
        CLIP_RESULT(res)
        dst_word |= (res << 24);
        *((uint32*)(rec += lx)) = dst_word;

        res = ((8192 - x5) >> 14);
        CLIP_RESULT(res)
        res2 = ((8192 - x1) >> 14);
        CLIP_RESULT(res2)
        dst_word = (res2 << 8) | res;
        res = ((8192 - x2) >> 14);
        CLIP_RESULT(res)
        dst_word |= (res << 16);
        res = ((8192 - x4) >> 14);
        CLIP_RESULT(res)
        dst_word |= (res << 24);
        *((uint32*)(rec + 4)) = dst_word;

    }
    return ;
}

void idct_row0x20Intra(Short *blk, UChar *rec, Int lx)
{
    int32 x0, x2, x4, x6;
    int res, res2;
    uint32 dst_word;
    int i = 8;

    rec -= lx;
    while (i--)
    {
        x2 = blk[2];
        blk[2] = 0;
        blk += 8;

        /* both upper and lower*/
        /* both x2orx6 and x0orx4 */
        x6 = (W6 * x2 + 4) >> 3;
        x2 = (W2 * x2 + 4) >> 3;
        x0 = 8192 + x2;
        x2 = 8192 - x2;
        x4 = 8192 + x6;
        x6 = 8192 - x6;

        res = ((x0) >> 14);
        CLIP_RESULT(res)
        res2 = ((x4) >> 14);
        CLIP_RESULT(res2)
        dst_word = (res2 << 8) | res;
        res = ((x6) >> 14);
        CLIP_RESULT(res)
        dst_word |= (res << 16);
        res = ((x2) >> 14);
        CLIP_RESULT(res)
        dst_word |= (res << 24);
        *((uint32*)(rec += lx)) = dst_word;

        res = ((x2) >> 14);
        CLIP_RESULT(res)
        res2 = ((x6) >> 14);
        CLIP_RESULT(res2)
        dst_word = (res2 << 8) | res;
        res = ((x4) >> 14);
        CLIP_RESULT(res)
        dst_word |= (res << 16);
        res = ((x0) >> 14);
        CLIP_RESULT(res)
        dst_word |= (res << 24);
        *((uint32*)(rec + 4)) = dst_word;

    }
    return ;
}

void idct_row0x10Intra(Short *blk, UChar *rec, Int lx)
{
    int32 x1, x3, x5, x7;
    int res, res2;
    uint32 dst_word;
    int i = 8;

    rec -= lx;
    while (i--)
    {
        x3 = blk[3];
        blk[3] = 0 ;
        blk += 8;

        x1 = (W3 * x3 + 4) >> 3;
        x3 = (W5 * x3 + 4) >> 3;

        x7 = (181 * (x3 - x1) + 128) >> 8;
        x5 = (-181 * (x1 + x3) + 128) >> 8;

        res = ((8192 + x1) >> 14);
        CLIP_RESULT(res)
        res2 = ((8192 + x7) >> 14);
        CLIP_RESULT(res2)
        dst_word = (res2 << 8) | res;
        res = ((8192 + x5) >> 14);
        CLIP_RESULT(res)
        dst_word |= (res << 16);
        res = ((8192 - x3) >> 14);
        CLIP_RESULT(res)
        dst_word |= (res << 24);
        *((uint32*)(rec += lx)) = dst_word;

        res = ((8192 + x3) >> 14);
        CLIP_RESULT(res)
        res2 = ((8192 - x5) >> 14);
        CLIP_RESULT(res2)
        dst_word = (res2 << 8) | res;
        res = ((8192 - x7) >> 14);
        CLIP_RESULT(res)
        dst_word |= (res << 16);
        res = ((8192 - x1) >> 14);
        CLIP_RESULT(res)
        dst_word |= (res << 24);
        *((uint32*)(rec + 4)) = dst_word;

    }

    return ;
}

#endif /* SMALL_DCT */
void idct_rowIntra(Short *blk, UChar *rec, Int lx)
{
    int32 x0, x1, x2, x3, x4, x5, x6, x7, x8;
    int i = 8;
    int res, res2;
    uint32 dst_word;

    blk -= 8;
    rec -= lx;

    while (i--)
    {
        x1 = (int32)blk[12] << 8;
        blk[12] = 0;
        x2 = blk[14];
        blk[14] = 0;
        x3 = blk[10];
        blk[10] = 0;
        x4 = blk[9];
        blk[9] = 0;
        x5 = blk[15];
        blk[15] = 0;
        x6 = blk[13];
        blk[13] = 0;
        x7 = blk[11];
        blk[11] = 0;
        x0 = ((*(blk += 8)) << 8) + 8192;
        *blk = 0;  /* for proper rounding in the fourth stage */

        /* first stage */
        x8 = W7 * (x4 + x5) + 4;
        x4 = (x8 + (W1 - W7) * x4) >> 3;
        x5 = (x8 - (W1 + W7) * x5) >> 3;
        x8 = W3 * (x6 + x7) + 4;
        x6 = (x8 - (W3 - W5) * x6) >> 3;
        x7 = (x8 - (W3 + W5) * x7) >> 3;

        /* second stage */
        x8 = x0 + x1;
        x0 -= x1;
        x1 = W6 * (x3 + x2) + 4;
        x2 = (x1 - (W2 + W6) * x2) >> 3;
        x3 = (x1 + (W2 - W6) * x3) >> 3;
        x1 = x4 + x6;
        x4 -= x6;
        x6 = x5 + x7;
        x5 -= x7;

        /* third stage */
        x7 = x8 + x3;
        x8 -= x3;
        x3 = x0 + x2;
        x0 -= x2;
        x2 = (181 * (x4 + x5) + 128) >> 8;
        x4 = (181 * (x4 - x5) + 128) >> 8;

        /* fourth stage */
        res = ((x7 + x1) >> 14);
        CLIP_RESULT(res)
        res2 = ((x3 + x2) >> 14);
        CLIP_RESULT(res2)
        dst_word = res | (res2 << 8);
        res = ((x0 + x4) >> 14);
        CLIP_RESULT(res)
        dst_word |= (res << 16);
        res = ((x8 + x6) >> 14);
        CLIP_RESULT(res)
        dst_word |= (res << 24);
        *((uint32*)(rec += lx)) = dst_word;

        res = ((x8 - x6) >> 14);
        CLIP_RESULT(res)
        res2 = ((x0 - x4) >> 14);
        CLIP_RESULT(res2)
        dst_word = res | (res2 << 8);
        res = ((x3 - x2) >> 14);
        CLIP_RESULT(res)
        dst_word |= (res << 16);
        res = ((x7 - x1) >> 14);
        CLIP_RESULT(res)
        dst_word |= (res << 24);
        *((uint32*)(rec + 4)) = dst_word;
    }
    return;
}


/* This function should not be called at all ****/
void idct_row0zmv(Short *srce, UChar *rec, UChar *pred, Int lx)
{
    OSCL_UNUSED_ARG(srce);
    OSCL_UNUSED_ARG(rec);
    OSCL_UNUSED_ARG(pred);
    OSCL_UNUSED_ARG(lx);

    return;
}

void idct_row1zmv(Short *blk, UChar *rec, UChar *pred, Int lx)
{
    int tmp;
    int i = 8;
    uint32 pred_word, dst_word;
    int res, res2;

    /* preset the offset, such that we can take advantage pre-offset addressing mode   */
    pred -= 16;
    rec -= lx;
    blk -= 8;

    while (i--)
    {
        tmp = (*(blk += 8) + 32) >> 6;
        *blk = 0;

        pred_word = *((uint32*)(pred += 16)); /* read 4 bytes from pred */
        res = tmp + (pred_word & 0xFF);
        CLIP_RESULT(res);
        res2 = tmp + ((pred_word >> 8) & 0xFF);
        CLIP_RESULT(res2);
        dst_word = (res2 << 8) | res;
        res = tmp + ((pred_word >> 16) & 0xFF);
        CLIP_RESULT(res);
        dst_word |= (res << 16);
        res = tmp + ((pred_word >> 24) & 0xFF);
        CLIP_RESULT(res);
        dst_word |= (res << 24);
        *((uint32*)(rec += lx)) = dst_word; /* save 4 bytes to dst */

        pred_word = *((uint32*)(pred + 4)); /* read 4 bytes from pred */
        res = tmp + (pred_word & 0xFF);
        CLIP_RESULT(res);
        res2 = tmp + ((pred_word >> 8) & 0xFF);
        CLIP_RESULT(res2);
        dst_word = (res2 << 8) | res;
        res = tmp + ((pred_word >> 16) & 0xFF);
        CLIP_RESULT(res);
        dst_word |= (res << 16);
        res = tmp + ((pred_word >> 24) & 0xFF);
        CLIP_RESULT(res);
        dst_word |= (res << 24);
        *((uint32*)(rec + 4)) = dst_word; /* save 4 bytes to dst */
    }
    return;
}

void idct_row2zmv(Short *blk, UChar *rec, UChar *pred, Int lx)
{
    int32 x0, x1, x2, x4, x5;
    int i = 8;
    uint32 pred_word, dst_word;
    int res, res2;

    /* preset the offset, such that we can take advantage pre-offset addressing mode   */
    rec -= lx;
    pred -= 16;
    blk -= 8;

    while (i--)
    {
        /* shortcut */
        x4 = blk[9];
        blk[9] = 0;
        x0 = ((*(blk += 8)) << 8) + 8192;
        *blk = 0;  /* for proper rounding in the fourth stage */

        /* first stage */
        x5 = (W7 * x4 + 4) >> 3;
        x4 = (W1 * x4 + 4) >> 3;

        /* third stage */
        x2 = (181 * (x4 + x5) + 128) >> 8;
        x1 = (181 * (x4 - x5) + 128) >> 8;

        /* fourth stage */
        pred_word = *((uint32*)(pred += 16)); /* read 4 bytes from pred */
        res = (x0 + x4) >> 14;
        ADD_AND_CLIP1(res);
        res2 = (x0 + x2) >> 14;
        ADD_AND_CLIP2(res2);
        dst_word = (res2 << 8) | res;
        res = (x0 + x1) >> 14;
        ADD_AND_CLIP3(res);
        dst_word |= (res << 16);
        res = (x0 + x5) >> 14;
        ADD_AND_CLIP4(res);
        dst_word |= (res << 24);
        *((uint32*)(rec += lx)) = dst_word; /* save 4 bytes to dst */

        pred_word = *((uint32*)(pred + 4)); /* read 4 bytes from pred */
        res = (x0 - x5) >> 14;
        ADD_AND_CLIP1(res);
        res2 = (x0 - x1) >> 14;
        ADD_AND_CLIP2(res2);
        dst_word = (res2 << 8) | res;
        res = (x0 - x2) >> 14;
        ADD_AND_CLIP3(res);
        dst_word |= (res << 16);
        res = (x0 - x4) >> 14;
        ADD_AND_CLIP4(res);
        dst_word |= (res << 24);
        *((uint32*)(rec + 4)) = dst_word; /* save 4 bytes to dst */
    }
    return ;
}

void idct_row3zmv(Short *blk, UChar *rec, UChar *pred, Int lx)
{
    int32 x0, x1, x2, x3, x4, x5, x6, x7, x8;
    int i = 8;
    uint32 pred_word, dst_word;
    int res, res2;

    /* preset the offset, such that we can take advantage pre-offset addressing mode   */
    rec -= lx;
    pred -= 16;
    blk -= 8;

    while (i--)
    {
        x2 = blk[10];
        blk[10] = 0;
        x1 = blk[9];
        blk[9] = 0;
        x0 = ((*(blk += 8)) << 8) + 8192;
        *blk = 0;  /* for proper rounding in the fourth stage */
        /* both upper and lower*/
        /* both x2orx6 and x0orx4 */

        x4 = x0;
        x6 = (W6 * x2 + 4) >> 3;
        x2 = (W2 * x2 + 4) >> 3;
        x8 = x0 - x2;
        x0 += x2;
        x2 = x8;
        x8 = x4 - x6;
        x4 += x6;
        x6 = x8;

        x7 = (W7 * x1 + 4) >> 3;
        x1 = (W1 * x1 + 4) >> 3;
        x3 = x7;
        x5 = (181 * (x1 - x7) + 128) >> 8;
        x7 = (181 * (x1 + x7) + 128) >> 8;

        pred_word = *((uint32*)(pred += 16)); /* read 4 bytes from pred */
        res = (x0 + x1) >> 14;
        ADD_AND_CLIP1(res);
        res2 = (x4 + x7) >> 14;
        ADD_AND_CLIP2(res2);
        dst_word = (res2 << 8) | res;
        res = (x6 + x5) >> 14;
        ADD_AND_CLIP3(res);
        dst_word |= (res << 16);
        res = (x2 + x3) >> 14;
        ADD_AND_CLIP4(res);
        dst_word |= (res << 24);
        *((uint32*)(rec += lx)) = dst_word; /* save 4 bytes to dst */

        pred_word = *((uint32*)(pred + 4)); /* read 4 bytes from pred */
        res = (x2 - x3) >> 14;
        ADD_AND_CLIP1(res);
        res2 = (x6 - x5) >> 14;
        ADD_AND_CLIP2(res2);
        dst_word = (res2 << 8) | res;
        res = (x4 - x7) >> 14;
        ADD_AND_CLIP3(res);
        dst_word |= (res << 16);
        res = (x0 - x1) >> 14;
        ADD_AND_CLIP4(res);
        dst_word |= (res << 24);
        *((uint32*)(rec + 4)) = dst_word; /* save 4 bytes to dst */
    }

    return ;
}

void idct_row4zmv(Short *blk, UChar *rec, UChar *pred, Int lx)
{
    int32 x0, x1, x2, x3, x4, x5, x6, x7, x8;
    int i = 8;
    uint32 pred_word, dst_word;
    int res, res2;

    /* preset the offset, such that we can take advantage pre-offset addressing mode   */
    rec -= lx;
    pred -= 16;
    blk -= 8;

    while (i--)
    {
        x2 = blk[10];
        blk[10] = 0;
        x1 = blk[9];
        blk[9] = 0;
        x3 = blk[11];
        blk[11] = 0;
        x0 = ((*(blk += 8)) << 8) + 8192;
        *blk = 0;   /* for proper rounding in the fourth stage */

        x4 = x0;
        x6 = (W6 * x2 + 4) >> 3;
        x2 = (W2 * x2 + 4) >> 3;
        x8 = x0 - x2;
        x0 += x2;
        x2 = x8;
        x8 = x4 - x6;
        x4 += x6;
        x6 = x8;

        x7 = (W7 * x1 + 4) >> 3;
        x1 = (W1 * x1 + 4) >> 3;
        x5 = (W3 * x3 + 4) >> 3;
        x3 = (- W5 * x3 + 4) >> 3;
        x8 = x1 - x5;
        x1 += x5;
        x5 = x8;
        x8 = x7 - x3;
        x3 += x7;
        x7 = (181 * (x5 + x8) + 128) >> 8;
        x5 = (181 * (x5 - x8) + 128) >> 8;

        pred_word = *((uint32*)(pred += 16)); /* read 4 bytes from pred */
        res = (x0 + x1) >> 14;
        ADD_AND_CLIP1(res);
        res2 = (x4 + x7) >> 14;
        ADD_AND_CLIP2(res2);
        dst_word = (res2 << 8) | res;
        res = (x6 + x5) >> 14;
        ADD_AND_CLIP3(res);
        dst_word |= (res << 16);
        res = (x2 + x3) >> 14;
        ADD_AND_CLIP4(res);
        dst_word |= (res << 24);
        *((uint32*)(rec += lx)) = dst_word; /* save 4 bytes to dst */

        pred_word = *((uint32*)(pred + 4)); /* read 4 bytes from pred */
        res = (x2 - x3) >> 14;
        ADD_AND_CLIP1(res);
        res2 = (x6 - x5) >> 14;
        ADD_AND_CLIP2(res2);
        dst_word = (res2 << 8) | res;
        res = (x4 - x7) >> 14;
        ADD_AND_CLIP3(res);
        dst_word |= (res << 16);
        res = (x0 - x1) >> 14;
        ADD_AND_CLIP4(res);
        dst_word |= (res << 24);
        *((uint32*)(rec + 4)) = dst_word; /* save 4 bytes to dst */
    }
    return ;
}

#ifndef SMALL_DCT
void idct_row0x40zmv(Short *blk, UChar *rec, UChar *pred, Int lx)
{
    int32 x1, x2, x4, x5;
    int i = 8;
    uint32 pred_word, dst_word;
    int res, res2;

    /* preset the offset, such that we can take advantage pre-offset addressing mode   */
    rec -= lx;
    pred -= 16;

    while (i--)
    {
        /* shortcut */
        x4 = blk[1];
        blk[1] = 0;
        blk += 8;  /* for proper rounding in the fourth stage */

        /* first stage */
        x5 = (W7 * x4 + 4) >> 3;
        x4 = (W1 * x4 + 4) >> 3;

        /* third stage */
        x2 = (181 * (x4 + x5) + 128) >> 8;
        x1 = (181 * (x4 - x5) + 128) >> 8;

        /* fourth stage */
        pred_word = *((uint32*)(pred += 16)); /* read 4 bytes from pred */
        res = (8192 + x4) >> 14;
        ADD_AND_CLIP1(res);
        res2 = (8192 + x2) >> 14;
        ADD_AND_CLIP2(res2);
        dst_word = (res2 << 8) | res;
        res = (8192 + x1) >> 14;
        ADD_AND_CLIP3(res);
        dst_word |= (res << 16);
        res = (8192 + x5) >> 14;
        ADD_AND_CLIP4(res);
        dst_word |= (res << 24);
        *((uint32*)(rec += lx)) = dst_word; /* save 4 bytes to dst */

        pred_word = *((uint32*)(pred + 4)); /* read 4 bytes from pred */
        res = (8192 - x5) >> 14;
        ADD_AND_CLIP1(res);
        res2 = (8192 - x1) >> 14;
        ADD_AND_CLIP2(res2);
        dst_word = (res2 << 8) | res;
        res = (8192 - x2) >> 14;
        ADD_AND_CLIP3(res);
        dst_word |= (res << 16);
        res = (8192 - x4) >> 14;
        ADD_AND_CLIP4(res);
        dst_word |= (res << 24);
        *((uint32*)(rec + 4)) = dst_word; /* save 4 bytes to dst */
    }
    return ;
}

void idct_row0x20zmv(Short *blk, UChar *rec, UChar *pred, Int lx)
{
    int32 x0, x2, x4, x6;
    int i = 8;
    uint32 pred_word, dst_word;
    int res, res2;

    /* preset the offset, such that we can take advantage pre-offset addressing mode   */
    rec -= lx;
    pred -= 16;

    while (i--)
    {
        x2 = blk[2];
        blk[2] = 0;
        blk += 8; /* for proper rounding in the fourth stage */
        /* both upper and lower*/
        /* both x2orx6 and x0orx4 */
        x6 = (W6 * x2 + 4) >> 3;
        x2 = (W2 * x2 + 4) >> 3;
        x0 = 8192 + x2;
        x2 = 8192 - x2;
        x4 = 8192 + x6;
        x6 = 8192 - x6;

        pred_word = *((uint32*)(pred += 16)); /* read 4 bytes from pred */
        res = (x0) >> 14;
        ADD_AND_CLIP1(res);
        res2 = (x4) >> 14;
        ADD_AND_CLIP2(res2);
        dst_word = (res2 << 8) | res;
        res = (x6) >> 14;
        ADD_AND_CLIP3(res);
        dst_word |= (res << 16);
        res = (x2) >> 14;
        ADD_AND_CLIP4(res);
        dst_word |= (res << 24);
        *((uint32*)(rec += lx)) = dst_word; /* save 4 bytes to dst */

        pred_word = *((uint32*)(pred + 4)); /* read 4 bytes from pred */
        res = (x2) >> 14;
        ADD_AND_CLIP1(res);
        res2 = (x6) >> 14;
        ADD_AND_CLIP2(res2);
        dst_word = (res2 << 8) | res;
        res = (x4) >> 14;
        ADD_AND_CLIP3(res);
        dst_word |= (res << 16);
        res = (x0) >> 14;
        ADD_AND_CLIP4(res);
        dst_word |= (res << 24);
        *((uint32*)(rec + 4)) = dst_word; /* save 4 bytes to dst */
    }

    return ;
}

void idct_row0x10zmv(Short *blk, UChar *rec, UChar *pred, Int lx)
{
    int32 x1, x3, x5, x7;
    int i = 8;
    uint32 pred_word, dst_word;
    int res, res2;

    /* preset the offset, such that we can take advantage pre-offset addressing mode   */
    rec -= lx;
    pred -= 16;

    while (i--)
    {
        x3 = blk[3];
        blk[3] = 0;
        blk += 8;

        x1 = (W3 * x3 + 4) >> 3;
        x3 = (-W5 * x3 + 4) >> 3;

        x7 = (-181 * (x3 + x1) + 128) >> 8;
        x5 = (181 * (x3 - x1) + 128) >> 8;

        pred_word = *((uint32*)(pred += 16)); /* read 4 bytes from pred */
        res = (8192 + x1) >> 14;
        ADD_AND_CLIP1(res);
        res2 = (8192 + x7) >> 14;
        ADD_AND_CLIP2(res2);
        dst_word = (res2 << 8) | res;
        res = (8192 + x5) >> 14;
        ADD_AND_CLIP3(res);
        dst_word |= (res << 16);
        res = (8192 + x3) >> 14;
        ADD_AND_CLIP4(res);
        dst_word |= (res << 24);
        *((uint32*)(rec += lx)) = dst_word; /* save 4 bytes to dst */

        pred_word = *((uint32*)(pred + 4)); /* read 4 bytes from pred */
        res = (8192 - x3) >> 14;
        ADD_AND_CLIP1(res);
        res2 = (8192 - x5) >> 14;
        ADD_AND_CLIP2(res2);
        dst_word = (res2 << 8) | res;
        res = (8192 - x7) >> 14;
        ADD_AND_CLIP3(res);
        dst_word |= (res << 16);
        res = (8192 - x1) >> 14;
        ADD_AND_CLIP4(res);
        dst_word |= (res << 24);
        *((uint32*)(rec + 4)) = dst_word; /* save 4 bytes to dst */
    }
    return ;
}

#endif /* SMALL_DCT */

void idct_rowzmv(Short *blk, UChar *rec, UChar *pred, Int lx)
{
    int32 x0, x1, x2, x3, x4, x5, x6, x7, x8;
    int i = 8;
    uint32 pred_word, dst_word;
    int res, res2;

    /* preset the offset, such that we can take advantage pre-offset addressing mode   */
    rec -= lx;
    pred -= 16;
    blk -= 8;

    while (i--)
    {
        x1 = (int32)blk[12] << 8;
        blk[12] = 0;
        x2 = blk[14];
        blk[14] = 0;
        x3 = blk[10];
        blk[10] = 0;
        x4 = blk[9];
        blk[9] = 0;
        x5 = blk[15];
        blk[15] = 0;
        x6 = blk[13];
        blk[13] = 0;
        x7 = blk[11];
        blk[11] = 0;
        x0 = ((*(blk += 8)) << 8) + 8192;
        *blk = 0;   /* for proper rounding in the fourth stage */

        /* first stage */
        x8 = W7 * (x4 + x5) + 4;
        x4 = (x8 + (W1 - W7) * x4) >> 3;
        x5 = (x8 - (W1 + W7) * x5) >> 3;
        x8 = W3 * (x6 + x7) + 4;
        x6 = (x8 - (W3 - W5) * x6) >> 3;
        x7 = (x8 - (W3 + W5) * x7) >> 3;

        /* second stage */
        x8 = x0 + x1;
        x0 -= x1;
        x1 = W6 * (x3 + x2) + 4;
        x2 = (x1 - (W2 + W6) * x2) >> 3;
        x3 = (x1 + (W2 - W6) * x3) >> 3;
        x1 = x4 + x6;
        x4 -= x6;
        x6 = x5 + x7;
        x5 -= x7;

        /* third stage */
        x7 = x8 + x3;
        x8 -= x3;
        x3 = x0 + x2;
        x0 -= x2;
        x2 = (181 * (x4 + x5) + 128) >> 8;
        x4 = (181 * (x4 - x5) + 128) >> 8;

        /* fourth stage */
        pred_word = *((uint32*)(pred += 16)); /* read 4 bytes from pred */

        res = (x7 + x1) >> 14;
        ADD_AND_CLIP1(res);
        res2 = (x3 + x2) >> 14;
        ADD_AND_CLIP2(res2);
        dst_word = (res2 << 8) | res;
        res = (x0 + x4) >> 14;
        ADD_AND_CLIP3(res);
        dst_word |= (res << 16);
        res = (x8 + x6) >> 14;
        ADD_AND_CLIP4(res);
        dst_word |= (res << 24);
        *((uint32*)(rec += lx)) = dst_word; /* save 4 bytes to dst */

        pred_word = *((uint32*)(pred + 4)); /* read 4 bytes from pred */

        res = (x8 - x6) >> 14;
        ADD_AND_CLIP1(res);
        res2 = (x0 - x4) >> 14;
        ADD_AND_CLIP2(res2);
        dst_word = (res2 << 8) | res;
        res = (x3 - x2) >> 14;
        ADD_AND_CLIP3(res);
        dst_word |= (res << 16);
        res = (x7 - x1) >> 14;
        ADD_AND_CLIP4(res);
        dst_word |= (res << 24);
        *((uint32*)(rec + 4)) = dst_word; /* save 4 bytes to dst */
    }
    return;
}

/*----------------------------------------------------------------------------
;  End Function: idctcol
----------------------------------------------------------------------------*/
/* ======================================================================== */
/*  Function : BlockIDCTMotionComp                                              */
/*  Date     : 10/16/2000                                                   */
/*  Purpose  : fast IDCT routine                                    */
/*  In/out   :                                                              */
/*      Int* coeff_in   Dequantized coefficient
        Int block_out   output IDCT coefficient
        Int maxval      clip value                                          */
/*  Modified :   7/31/01, add checking for all-zero and DC-only block.  */
/*              do 8 columns at a time                                      */
/*               8/2/01, do column first then row-IDCT.                 */
/*               8/2/01, remove clipping (included in motion comp).     */
/*               8/7/01, combine with motion comp.                      */
/*               8/8/01, use AAN IDCT                                       */
/*               9/4/05, use Chen's IDCT and 16 bit block                   */
/* ======================================================================== */
void BlockIDCTMotionComp(Short *block, UChar *bitmapcol, UChar bitmaprow,
                         Int dctMode, UChar *rec, UChar *pred, Int lx_intra)
{
    Int i;
    Int tmp, tmp2;
    ULong tmp4;
    Int bmap;
    Short *ptr = block;
    UChar *endcol;
    UInt mask = 0xFF;
    Int lx = lx_intra >> 1;
    Int intra = (lx_intra & 1);

    /*  all-zero block */
    if (dctMode == 0 || bitmaprow == 0)
    {
        if (intra)
        {
            *((ULong*)rec) = *((ULong*)(rec + 4)) = 0;
            *((ULong*)(rec += lx)) = 0;
            *((ULong*)(rec + 4)) = 0;
            *((ULong*)(rec += lx)) = 0;
            *((ULong*)(rec + 4)) = 0;
            *((ULong*)(rec += lx)) = 0;
            *((ULong*)(rec + 4)) = 0;
            *((ULong*)(rec += lx)) = 0;
            *((ULong*)(rec + 4)) = 0;
            *((ULong*)(rec += lx)) = 0;
            *((ULong*)(rec + 4)) = 0;
            *((ULong*)(rec += lx)) = 0;
            *((ULong*)(rec + 4)) = 0;
            *((ULong*)(rec += lx)) = 0;
            *((ULong*)(rec + 4)) = 0;
            return ;
        }
        else /* copy from previous frame */
        {
            *((ULong*)rec) = *((ULong*)pred);
            *((ULong*)(rec + 4)) = *((ULong*)(pred + 4));
            *((ULong*)(rec += lx)) = *((ULong*)(pred += 16));
            *((ULong*)(rec + 4)) = *((ULong*)(pred + 4));
            *((ULong*)(rec += lx)) = *((ULong*)(pred += 16));
            *((ULong*)(rec + 4)) = *((ULong*)(pred + 4));
            *((ULong*)(rec += lx)) = *((ULong*)(pred += 16));
            *((ULong*)(rec + 4)) = *((ULong*)(pred + 4));
            *((ULong*)(rec += lx)) = *((ULong*)(pred += 16));
            *((ULong*)(rec + 4)) = *((ULong*)(pred + 4));
            *((ULong*)(rec += lx)) = *((ULong*)(pred += 16));
            *((ULong*)(rec + 4)) = *((ULong*)(pred + 4));
            *((ULong*)(rec += lx)) = *((ULong*)(pred += 16));
            *((ULong*)(rec + 4)) = *((ULong*)(pred + 4));
            *((ULong*)(rec += lx)) = *((ULong*)(pred += 16));
            *((ULong*)(rec + 4)) = *((ULong*)(pred + 4));
            return ;
        }
    }

    /* Test for DC only block */
    if (dctMode == 1 || (bitmaprow == 0x80 && bitmapcol[0] == 0x80))
    {
        i = ((block[0] << 3) + 32) >> 6;
        block[0] = 0;
        if (intra)
        {
            if ((UInt)i > mask) i = mask & (~(i >> 31));

            tmp = i | (i << 8);
            tmp |= (tmp << 16);

            *((ULong*)rec) = *((ULong*)(rec + 4)) = tmp;
            *((ULong*)(rec += lx)) = tmp;
            *((ULong*)(rec + 4)) = tmp;
            *((ULong*)(rec += lx)) = tmp;
            *((ULong*)(rec + 4)) = tmp;
            *((ULong*)(rec += lx)) = tmp;
            *((ULong*)(rec + 4)) = tmp;
            *((ULong*)(rec += lx)) = tmp;
            *((ULong*)(rec + 4)) = tmp;
            *((ULong*)(rec += lx)) = tmp;
            *((ULong*)(rec + 4)) = tmp;
            *((ULong*)(rec += lx)) = tmp;
            *((ULong*)(rec + 4)) = tmp;
            *((ULong*)(rec += lx)) = tmp;
            *((ULong*)(rec + 4)) = tmp;

            return ;
        }
        else
        {
            endcol = rec + (lx << 3);
            do
            {
                tmp4 = *((ULong*)pred);
                tmp2 = tmp4 & 0xFF;
                tmp2 += i;
                if ((UInt)tmp2 > mask) tmp2 = mask & (~(tmp2 >> 31));
                tmp = (tmp4 >> 8) & 0xFF;
                tmp += i;
                if ((UInt)tmp > mask) tmp = mask & (~(tmp >> 31));
                tmp2 |= (tmp << 8);
                tmp = (tmp4 >> 16) & 0xFF;
                tmp += i;
                if ((UInt)tmp > mask) tmp = mask & (~(tmp >> 31));
                tmp2 |= (tmp << 16);
                tmp = (tmp4 >> 24) & 0xFF;
                tmp += i;
                if ((UInt)tmp > mask) tmp = mask & (~(tmp >> 31));
                tmp2 |= (tmp << 24);
                *((ULong*)rec) = tmp2;

                tmp4 = *((ULong*)(pred + 4));
                tmp2 = tmp4 & 0xFF;
                tmp2 += i;
                if ((UInt)tmp2 > mask) tmp2 = mask & (~(tmp2 >> 31));
                tmp = (tmp4 >> 8) & 0xFF;
                tmp += i;
                if ((UInt)tmp > mask) tmp = mask & (~(tmp >> 31));
                tmp2 |= (tmp << 8);
                tmp = (tmp4 >> 16) & 0xFF;
                tmp += i;
                if ((UInt)tmp > mask) tmp = mask & (~(tmp >> 31));
                tmp2 |= (tmp << 16);
                tmp = (tmp4 >> 24) & 0xFF;
                tmp += i;
                if ((UInt)tmp > mask) tmp = mask & (~(tmp >> 31));
                tmp2 |= (tmp << 24);
                *((ULong*)(rec + 4)) = tmp2;

                rec += lx;
                pred += 16;
            }
            while (rec < endcol);
            return ;
        }
    }

    for (i = 0; i < dctMode; i++)
    {
        bmap = (Int)bitmapcol[i];
        if (bmap)
        {
            if ((bmap&0xf) == 0)
                (*(idctcolVCA[bmap>>4]))(ptr);
            else
                idct_col(ptr);
        }
        ptr++;
    }

    if ((bitmaprow&0xf) == 0)
    {
        if (intra)
            (*(idctrowVCAIntra[(Int)(bitmaprow>>4)]))(block, rec, lx);
        else
            (*(idctrowVCAzmv[(Int)(bitmaprow>>4)]))(block, rec, pred, lx);
    }
    else
    {
        if (intra)
            idct_rowIntra(block, rec, lx);
        else
            idct_rowzmv(block, rec, pred, lx);
    }
}
