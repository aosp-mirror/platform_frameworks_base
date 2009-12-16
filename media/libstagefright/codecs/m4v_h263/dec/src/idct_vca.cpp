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
#include "mp4def.h"
#include "idct.h"
#include "motion_comp.h"

#ifdef FAST_IDCT

/****************************************************************
*       vca_idct.c : created 6/1/99 for several options
*                     of hard-coded reduced idct function (using nz_coefs)
******************************************************************/

/*****************************************************/
//pretested version
void idctrow0(int16 *, uint8 *, uint8 *, int)
{
    return ;
}
void idctcol0(int16 *)
{
    return ;
}

void idctrow1(int16 *blk, uint8 *pred, uint8 *dst, int width)
{
    /* shortcut */
    int tmp;
    int i = 8;
    uint32 pred_word, dst_word;
    int res, res2;

    /* preset the offset, such that we can take advantage pre-offset addressing mode   */
    width -= 4;
    dst -= width;
    pred -= 12;
    blk -= 8;

    while (i--)
    {
        tmp = (*(blk += 8) + 32) >> 6;
        *blk = 0;

        pred_word = *((uint32*)(pred += 12)); /* read 4 bytes from pred */
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
        *((uint32*)(dst += width)) = dst_word; /* save 4 bytes to dst */

        pred_word = *((uint32*)(pred += 4)); /* read 4 bytes from pred */
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
        *((uint32*)(dst += 4)) = dst_word; /* save 4 bytes to dst */
    }
    return;
}

void idctcol1(int16 *blk)
{ /* shortcut */
    blk[0] = blk[8] = blk[16] = blk[24] = blk[32] = blk[40] = blk[48] = blk[56] =
                                              blk[0] << 3;
    return;
}

void idctrow2(int16 *blk, uint8 *pred, uint8 *dst, int width)
{
    int32 x0, x1, x2, x4, x5;
    int i = 8;
    uint32 pred_word, dst_word;
    int res, res2;

    /* preset the offset, such that we can take advantage pre-offset addressing mode   */
    width -= 4;
    dst -= width;
    pred -= 12;
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
        pred_word = *((uint32*)(pred += 12)); /* read 4 bytes from pred */
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
        *((uint32*)(dst += width)) = dst_word; /* save 4 bytes to dst */

        pred_word = *((uint32*)(pred += 4)); /* read 4 bytes from pred */
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
        *((uint32*)(dst += 4)) = dst_word; /* save 4 bytes to dst */
    }
    return ;
}

void idctcol2(int16 *blk)
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

void idctrow3(int16 *blk, uint8 *pred, uint8 *dst, int width)
{
    int32 x0, x1, x2, x3, x4, x5, x6, x7, x8;
    int i = 8;
    uint32 pred_word, dst_word;
    int res, res2;

    /* preset the offset, such that we can take advantage pre-offset addressing mode   */
    width -= 4;
    dst -= width;
    pred -= 12;
    blk -= 8;

    while (i--)
    {
        x2 = blk[10];
        blk[10] = 0;
        x1 = blk[9];
        blk[9] = 0;
        x0 = ((*(blk += 8)) << 8) + 8192;
        *blk = 0;   /* for proper rounding in the fourth stage */
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

        pred_word = *((uint32*)(pred += 12)); /* read 4 bytes from pred */
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
        *((uint32*)(dst += width)) = dst_word; /* save 4 bytes to dst */

        pred_word = *((uint32*)(pred += 4)); /* read 4 bytes from pred */
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
        *((uint32*)(dst += 4)) = dst_word; /* save 4 bytes to dst */
    }

    return ;
}

void idctcol3(int16 *blk)
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

    return;
}


void idctrow4(int16 *blk, uint8 *pred, uint8 *dst, int width)
{
    int32 x0, x1, x2, x3, x4, x5, x6, x7, x8;
    int i = 8;
    uint32 pred_word, dst_word;
    int res, res2;

    /* preset the offset, such that we can take advantage pre-offset addressing mode   */
    width -= 4;
    dst -= width;
    pred -= 12;
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
        *blk = 0;    /* for proper rounding in the fourth stage */

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

        pred_word = *((uint32*)(pred += 12)); /* read 4 bytes from pred */
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
        *((uint32*)(dst += width)) = dst_word; /* save 4 bytes to dst */

        pred_word = *((uint32*)(pred += 4)); /* read 4 bytes from pred */
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
        *((uint32*)(dst += 4)) = dst_word; /* save 4 bytes to dst */
    }
    return ;
}

void idctcol4(int16 *blk)
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

void idctrow0_intra(int16 *, PIXEL *, int)
{
    return ;
}

void idctrow1_intra(int16 *blk, PIXEL *comp, int width)
{
    /* shortcut */
    int32 tmp;
    int i = 8;
    int offset = width;
    uint32 word;

    comp -= offset;
    while (i--)
    {
        tmp = ((blk[0] + 32) >> 6);
        blk[0] = 0;
        CLIP_RESULT(tmp)

        word = (tmp << 8) | tmp;
        word = (word << 16) | word;

        *((uint32*)(comp += offset)) = word;
        *((uint32*)(comp + 4)) = word;




        blk += B_SIZE;
    }
    return;
}

void idctrow2_intra(int16 *blk, PIXEL *comp, int width)
{
    int32 x0, x1, x2, x4, x5, temp;
    int i = 8;
    int offset = width;
    int32 word;

    comp -= offset;
    while (i--)
    {
        /* shortcut */
        x4 = blk[1];
        blk[1] = 0;
        x0 = ((int32)blk[0] << 8) + 8192;
        blk[0] = 0;   /* for proper rounding in the fourth stage */

        /* first stage */
        x5 = (W7 * x4 + 4) >> 3;
        x4 = (W1 * x4 + 4) >> 3;

        /* third stage */
        x2 = (181 * (x4 + x5) + 128) >> 8;
        x1 = (181 * (x4 - x5) + 128) >> 8;

        /* fourth stage */
        word = ((x0 + x4) >> 14);
        CLIP_RESULT(word)

        temp = ((x0 + x2) >> 14);
        CLIP_RESULT(temp)
        word = word | (temp << 8);
        temp = ((x0 + x1) >> 14);
        CLIP_RESULT(temp)
        word = word | (temp << 16);
        temp = ((x0 + x5) >> 14);
        CLIP_RESULT(temp)
        word = word | (temp << 24);
        *((int32*)(comp += offset)) = word;

        word = ((x0 - x5) >> 14);
        CLIP_RESULT(word)
        temp = ((x0 - x1) >> 14);
        CLIP_RESULT(temp)
        word = word | (temp << 8);
        temp = ((x0 - x2) >> 14);
        CLIP_RESULT(temp)
        word = word | (temp << 16);
        temp = ((x0 - x4) >> 14);
        CLIP_RESULT(temp)
        word = word | (temp << 24);
        *((int32*)(comp + 4)) = word;

        blk += B_SIZE;
    }
    return ;
}

void idctrow3_intra(int16 *blk, PIXEL *comp, int width)
{
    int32 x0, x1, x2, x3, x4, x5, x6, x7, x8, temp;
    int i = 8;
    int offset = width;
    int32 word;

    comp -= offset;

    while (i--)
    {
        x2 = blk[2];
        blk[2] = 0;
        x1 = blk[1];
        blk[1] = 0;
        x0 = ((int32)blk[0] << 8) + 8192;
        blk[0] = 0;/* for proper rounding in the fourth stage */
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

        word = ((x0 + x1) >> 14);
        CLIP_RESULT(word)
        temp = ((x4 + x7) >> 14);
        CLIP_RESULT(temp)
        word = word | (temp << 8);


        temp = ((x6 + x5) >> 14);
        CLIP_RESULT(temp)
        word = word | (temp << 16);

        temp = ((x2 + x3) >> 14);
        CLIP_RESULT(temp)
        word = word | (temp << 24);
        *((int32*)(comp += offset)) = word;

        word = ((x2 - x3) >> 14);
        CLIP_RESULT(word)

        temp = ((x6 - x5) >> 14);
        CLIP_RESULT(temp)
        word = word | (temp << 8);

        temp = ((x4 - x7) >> 14);
        CLIP_RESULT(temp)
        word = word | (temp << 16);

        temp = ((x0 - x1) >> 14);
        CLIP_RESULT(temp)
        word = word | (temp << 24);
        *((int32*)(comp + 4)) = word;

        blk += B_SIZE;
    }
    return ;
}

void idctrow4_intra(int16 *blk, PIXEL *comp, int width)
{
    int32 x0, x1, x2, x3, x4, x5, x6, x7, x8, temp;
    int i = 8;
    int offset = width;
    int32 word;

    comp -= offset;

    while (i--)
    {
        x2 = blk[2];
        blk[2] = 0;
        x1 = blk[1];
        blk[1] = 0;
        x3 = blk[3];
        blk[3] = 0;
        x0 = ((int32)blk[0] << 8) + 8192;
        blk[0] = 0;/* for proper rounding in the fourth stage */

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

        word = ((x0 + x1) >> 14);
        CLIP_RESULT(word)

        temp = ((x4 + x7) >> 14);
        CLIP_RESULT(temp)
        word = word | (temp << 8);


        temp = ((x6 + x5) >> 14);
        CLIP_RESULT(temp)
        word = word | (temp << 16);

        temp = ((x2 + x3) >> 14);
        CLIP_RESULT(temp)
        word = word | (temp << 24);
        *((int32*)(comp += offset)) = word;

        word = ((x2 - x3) >> 14);
        CLIP_RESULT(word)

        temp = ((x6 - x5) >> 14);
        CLIP_RESULT(temp)
        word = word | (temp << 8);

        temp = ((x4 - x7) >> 14);
        CLIP_RESULT(temp)
        word = word | (temp << 16);

        temp = ((x0 - x1) >> 14);
        CLIP_RESULT(temp)
        word = word | (temp << 24);
        *((int32*)(comp + 4)) = word;

        blk += B_SIZE;
    }

    return ;
}

#endif

