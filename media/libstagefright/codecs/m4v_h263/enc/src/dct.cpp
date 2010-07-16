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
#include "mp4enc_lib.h"
#include "mp4lib_int.h"
#include "dct_inline.h"

#define FDCT_SHIFT 10

#ifdef __cplusplus
extern "C"
{
#endif

    /**************************************************************************/
    /*  Function:   BlockDCT_AANwSub
        Date:       7/31/01
        Input:
        Output:     out[64] ==> next block
        Purpose:    Do subtraction for zero MV first
        Modified:
    **************************************************************************/

    Void BlockDCT_AANwSub(Short *out, UChar *cur, UChar *pred, Int width)
    {
        Short *dst;
        Int k0, k1, k2, k3, k4, k5, k6, k7;
        Int round;
        Int k12 = 0x022A02D4;
        Int k14 = 0x0188053A;
        Int abs_sum;
        Int mask;
        Int tmp, tmp2;
        Int ColTh;

        dst = out + 64 ;
        ColTh = *dst;
        out += 128;
        round = 1 << (FDCT_SHIFT - 1);

        do  /* fdct_nextrow */
        {
            /* assuming the block is word-aligned */
            mask = 0x1FE;
            tmp = *((Int*) cur);    /* contains 4 pixels */
            tmp2 = *((Int*) pred); /* prediction 4 pixels */
            k0 = tmp2 & 0xFF;
            k1 = mask & (tmp << 1);
            k0 = k1 - (k0 << 1);
            k1 = (tmp2 >> 8) & 0xFF;
            k2 = mask & (tmp >> 7);
            k1 = k2 - (k1 << 1);
            k2 = (tmp2 >> 16) & 0xFF;
            k3 = mask & (tmp >> 15);
            k2 = k3 - (k2 << 1);
            k3 = (tmp2 >> 24) & 0xFF;
            k4 = mask & (tmp >> 23);
            k3 = k4 - (k3 << 1);
            tmp = *((Int*)(cur + 4));   /* another 4 pixels */
            tmp2 = *((Int*)(pred + 4));
            k4 = tmp2 & 0xFF;
            k5 = mask & (tmp << 1);
            k4 = k5 - (k4 << 1);
            k5 = (tmp2 >> 8) & 0xFF;
            k6 = mask & (tmp >> 7);
            k5 = k6 - (k5 << 1);
            k6 = (tmp2 >> 16) & 0xFF;
            k7 = mask & (tmp >> 15);
            k6 = k7 - (k6 << 1);
            k7 = (tmp2 >> 24) & 0xFF;
            tmp = mask & (tmp >> 23);
            k7 = tmp - (k7 << 1);
            cur += width;
            pred += 16;

            /* fdct_1 */
            k0 = k0 + k7;
            k7 = k0 - (k7 << 1);
            k1 = k1 + k6;
            k6 = k1 - (k6 << 1);
            k2 = k2 + k5;
            k5 = k2 - (k5 << 1);
            k3 = k3 + k4;
            k4 = k3 - (k4 << 1);

            k0 = k0 + k3;
            k3 = k0 - (k3 << 1);
            k1 = k1 + k2;
            k2 = k1 - (k2 << 1);

            k0 = k0 + k1;
            k1 = k0 - (k1 << 1);
            /**********/
            dst[0] = k0;
            dst[4] = k1; /* col. 4 */
            /* fdct_2 */
            k4 = k4 + k5;
            k5 = k5 + k6;
            k6 = k6 + k7;
            k2 = k2 + k3;
            /* MUL2C k2,k5,724,FDCT_SHIFT */
            /* k0, k1 become scratch */
            /* assume FAST MULTIPLY */
            k1 = mla724(k12, k5, round);
            k0 = mla724(k12, k2, round);

            k5 = k1 >> FDCT_SHIFT;
            k2 = k0 >> FDCT_SHIFT;
            /*****************/
            k2 = k2 + k3;
            k3 = (k3 << 1) - k2;
            /********/
            dst[2] = k2;        /* col. 2 */
            k3 <<= 1;       /* scale up col. 6 */
            dst[6] = k3; /* col. 6 */
            /* fdct_3 */
            /* ROTATE k4,k6,392,946, FDCT_SHIFT */
            /* assume FAST MULTIPLY */
            /* k0, k1 are output */
            k0 = k4 - k6;

            k1 = mla392(k0, k14, round);
            k0 = mla554(k4, k12, k1);
            k1 = mla1338(k6, k14, k1);

            k4 = k0 >> FDCT_SHIFT;
            k6 = k1 >> FDCT_SHIFT;
            /***********************/
            k5 = k5 + k7;
            k7 = (k7 << 1) - k5;
            k4 = k4 + k7;
            k7 = (k7 << 1) - k4;
            k5 = k5 + k6;
            k4 <<= 1;       /* scale up col.5 */
            k6 = k5 - (k6 << 1);
            /********/
            dst[5] = k4;    /* col. 5 */
            k6 <<= 2;       /* scale up col. 7 */
            dst[1] = k5;    /* col. 1 */
            dst[7] = k6;    /* col. 7 */
            dst[3] = k7;    /* col. 3 */
            dst += 8;
        }
        while (dst < out);

        out -= 64;
        dst = out + 8;

        /*  Vertical Block Loop  */
        do  /* Vertical 8xDCT loop */
        {
            k0 = out[0];
            k1 = out[8];
            k2 = out[16];
            k3 = out[24];
            k4 = out[32];
            k5 = out[40];
            k6 = out[48];
            k7 = out[56];
            /* deadzone thresholding for column */

            abs_sum = sum_abs(k0, k1, k2, k3, k4, k5, k6, k7);

            if (abs_sum < ColTh)
            {
                out[0] = 0x7fff;
                out++;
                continue;
            }

            /* fdct_1 */
            k0 = k0 + k7;
            k7 = k0 - (k7 << 1);
            k1 = k1 + k6;
            k6 = k1 - (k6 << 1);
            k2 = k2 + k5;
            k5 = k2 - (k5 << 1);
            k3 = k3 + k4;
            k4 = k3 - (k4 << 1);

            k0 = k0 + k3;
            k3 = k0 - (k3 << 1);
            k1 = k1 + k2;
            k2 = k1 - (k2 << 1);

            k0 = k0 + k1;
            k1 = k0 - (k1 << 1);
            /**********/
            out[32] = k1; /* row 4 */
            out[0] = k0; /* row 0 */
            /* fdct_2 */
            k4 = k4 + k5;
            k5 = k5 + k6;
            k6 = k6 + k7;
            k2 = k2 + k3;
            /* MUL2C k2,k5,724,FDCT_SHIFT */
            /* k0, k1 become scratch */
            /* assume FAST MULTIPLY */
            k1 = mla724(k12, k5, round);
            k0 = mla724(k12, k2, round);

            k5 = k1 >> FDCT_SHIFT;
            k2 = k0 >> FDCT_SHIFT;
            /*****************/
            k2 = k2 + k3;
            k3 = (k3 << 1) - k2;
            k3 <<= 1;       /* scale up col. 6 */
            /********/
            out[48] = k3;   /* row 6 */
            out[16] = k2;   /* row 2 */
            /* fdct_3 */
            /* ROTATE k4,k6,392,946, FDCT_SHIFT */
            /* assume FAST MULTIPLY */
            /* k0, k1 are output */
            k0 = k4 - k6;

            k1 = mla392(k0, k14, round);
            k0 = mla554(k4, k12, k1);
            k1 = mla1338(k6, k14, k1);

            k4 = k0 >> FDCT_SHIFT;
            k6 = k1 >> FDCT_SHIFT;
            /***********************/
            k5 = k5 + k7;
            k7 = (k7 << 1) - k5;
            k4 = k4 + k7;
            k7 = (k7 << 1) - k4;
            k5 = k5 + k6;
            k4 <<= 1;       /* scale up col. 5 */
            k6 = k5 - (k6 << 1);
            /********/
            out[24] = k7 ;    /* row 3 */
            k6 <<= 2;       /* scale up col. 7 */
            out[56] = k6 ;   /* row 7 */
            out[8] = k5 ;    /* row 1 */
            out[40] = k4 ;   /* row 5 */
            out++;
        }
        while ((UInt)out < (UInt)dst) ;

        return ;
    }

    /**************************************************************************/
    /*  Function:   Block4x4DCT_AANwSub
        Date:       7/31/01
        Input:
        Output:     out[64] ==> next block
        Purpose:    Do subtraction for zero MV first before 4x4 DCT
        Modified:
    **************************************************************************/

    Void Block4x4DCT_AANwSub(Short *out, UChar *cur, UChar *pred, Int width)
    {
        Short *dst;
        register Int k0, k1, k2, k3, k4, k5, k6, k7;
        Int round;
        Int k12 = 0x022A02D4;
        Int k14 = 0x0188053A;
        Int mask;
        Int tmp, tmp2;
        Int abs_sum;
        Int ColTh;

        dst = out + 64 ;
        ColTh = *dst;
        out += 128;
        round = 1 << (FDCT_SHIFT - 1);

        do  /* fdct_nextrow */
        {
            /* assuming the block is word-aligned */
            mask = 0x1FE;
            tmp = *((Int*) cur);    /* contains 4 pixels */
            tmp2 = *((Int*) pred); /* prediction 4 pixels */
            k0 = tmp2 & 0xFF;
            k1 = mask & (tmp << 1);
            k0 = k1 - (k0 << 1);
            k1 = (tmp2 >> 8) & 0xFF;
            k2 = mask & (tmp >> 7);
            k1 = k2 - (k1 << 1);
            k2 = (tmp2 >> 16) & 0xFF;
            k3 = mask & (tmp >> 15);
            k2 = k3 - (k2 << 1);
            k3 = (tmp2 >> 24) & 0xFF;
            k4 = mask & (tmp >> 23);
            k3 = k4 - (k3 << 1);
            tmp = *((Int*)(cur + 4));   /* another 4 pixels */
            tmp2 = *((Int*)(pred + 4));
            k4 = tmp2 & 0xFF;
            k5 = mask & (tmp << 1);
            k4 = k5 - (k4 << 1);
            k5 = (tmp2 >> 8) & 0xFF;
            k6 = mask & (tmp >> 7);
            k5 = k6 - (k5 << 1);
            k6 = (tmp2 >> 16) & 0xFF;
            k7 = mask & (tmp >> 15);
            k6 = k7 - (k6 << 1);
            k7 = (tmp2 >> 24) & 0xFF;
            tmp = mask & (tmp >> 23);
            k7 = tmp - (k7 << 1);
            cur += width;
            pred += 16;

            /* fdct_1 */
            k0 = k0 + k7;
            k7 = k0 - (k7 << 1);
            k1 = k1 + k6;
            k6 = k1 - (k6 << 1);
            k2 = k2 + k5;
            k5 = k2 - (k5 << 1);
            k3 = k3 + k4;
            k4 = k3 - (k4 << 1);

            k0 = k0 + k3;
            k3 = k0 - (k3 << 1);
            k1 = k1 + k2;
            k2 = k1 - (k2 << 1);

            k0 = k0 + k1;
            /**********/
            dst[0] = k0;
            /* fdct_2 */
            k4 = k4 + k5;
            k5 = k5 + k6;
            k6 = k6 + k7;
            k2 = k2 + k3;
            /* MUL2C k2,k5,724,FDCT_SHIFT */
            /* k0, k1 become scratch */
            /* assume FAST MULTIPLY */
            k1 = mla724(k12, k5, round);
            k0 = mla724(k12, k2, round);

            k5 = k1 >> FDCT_SHIFT;
            k2 = k0 >> FDCT_SHIFT;
            /*****************/
            k2 = k2 + k3;
            /********/
            dst[2] = k2;        /* col. 2 */
            /* fdct_3 */
            /* ROTATE k4,k6,392,946, FDCT_SHIFT */
            /* assume FAST MULTIPLY */
            /* k0, k1 are output */
            k0 = k4 - k6;

            k1 = mla392(k0, k14, round);
            k0 = mla554(k4, k12, k1);
            k1 = mla1338(k6, k14, k1);

            k4 = k0 >> FDCT_SHIFT;
            k6 = k1 >> FDCT_SHIFT;
            /***********************/
            k5 = k5 + k7;
            k7 = (k7 << 1) - k5;
            k7 = k7 - k4;
            k5 = k5 + k6;
            /********/
            dst[1] = k5;        /* col. 1 */
            dst[3] = k7;        /* col. 3 */
            dst += 8;
        }
        while (dst < out);

        out -= 64;
        dst = out + 4;

        /*  Vertical Block Loop  */
        do  /* Vertical 8xDCT loop */
        {
            k0 = out[0];
            k1 = out[8];
            k2 = out[16];
            k3 = out[24];
            k4 = out[32];
            k5 = out[40];
            k6 = out[48];
            k7 = out[56];

            abs_sum = sum_abs(k0, k1, k2, k3, k4, k5, k6, k7);

            if (abs_sum < ColTh)
            {
                out[0] = 0x7fff;
                out++;
                continue;
            }
            /* fdct_1 */
            k0 = k0 + k7;
            k7 = k0 - (k7 << 1);
            k1 = k1 + k6;
            k6 = k1 - (k6 << 1);
            k2 = k2 + k5;
            k5 = k2 - (k5 << 1);
            k3 = k3 + k4;
            k4 = k3 - (k4 << 1);

            k0 = k0 + k3;
            k3 = k0 - (k3 << 1);
            k1 = k1 + k2;
            k2 = k1 - (k2 << 1);

            k0 = k0 + k1;
            /**********/
            out[0] = k0;   /* row 0 */
            /* fdct_2 */
            k4 = k4 + k5;
            k5 = k5 + k6;
            k6 = k6 + k7;
            k2 = k2 + k3;
            /* MUL2C k2,k5,724,FDCT_SHIFT */
            /* k0, k1 become scratch */
            /* assume FAST MULTIPLY */
            k1 = mla724(k12, k5, round);
            k0 = mla724(k12, k2, round);

            k5 = k1 >> FDCT_SHIFT;
            k2 = k0 >> FDCT_SHIFT;
            /*****************/
            k2 = k2 + k3;
            /********/
            out[16] = k2;           /* row 2 */
            /* fdct_3 */
            /* ROTATE k4,k6,392,946, FDCT_SHIFT */
            /* assume FAST MULTIPLY */
            /* k0, k1 are output */
            k0 = k4 - k6;

            k1 = mla392(k0, k14, round);
            k0 = mla554(k4, k12, k1);
            k1 = mla1338(k6, k14, k1);

            k4 = k0 >> FDCT_SHIFT;
            k6 = k1 >> FDCT_SHIFT;
            /***********************/
            k5 = k5 + k7;
            k7 = (k7 << 1) - k5;
            k7 = k7 - k4 ;
            k5 = k5 + k6;
            /********/
            out[24] = k7 ;      /* row 3 */
            out[8] = k5 ;       /* row 1 */
            out++;
        }
        while ((UInt)out < (UInt)dst) ;

        return ;
    }

    /**************************************************************************/
    /*  Function:   Block2x2DCT_AANwSub
        Date:       7/31/01
        Input:
        Output:     out[64] ==> next block
        Purpose:    Do subtraction for zero MV first before 2x2 DCT
        Modified:
    **************************************************************************/


    Void Block2x2DCT_AANwSub(Short *out, UChar *cur, UChar *pred, Int width)
    {
        Short *dst;
        register Int k0, k1, k2, k3, k4, k5, k6, k7;
        Int round;
        Int k12 = 0x022A02D4;
        Int k14 = 0x018803B2;
        Int mask;
        Int tmp, tmp2;
        Int abs_sum;
        Int ColTh;

        dst = out + 64 ;
        ColTh = *dst;
        out += 128;
        round = 1 << (FDCT_SHIFT - 1);

        do  /* fdct_nextrow */
        {
            /* assuming the block is word-aligned */
            mask = 0x1FE;
            tmp = *((Int*) cur);    /* contains 4 pixels */
            tmp2 = *((Int*) pred); /* prediction 4 pixels */
            k0 = tmp2 & 0xFF;
            k1 = mask & (tmp << 1);
            k0 = k1 - (k0 << 1);
            k1 = (tmp2 >> 8) & 0xFF;
            k2 = mask & (tmp >> 7);
            k1 = k2 - (k1 << 1);
            k2 = (tmp2 >> 16) & 0xFF;
            k3 = mask & (tmp >> 15);
            k2 = k3 - (k2 << 1);
            k3 = (tmp2 >> 24) & 0xFF;
            k4 = mask & (tmp >> 23);
            k3 = k4 - (k3 << 1);
            tmp = *((Int*)(cur + 4));   /* another 4 pixels */
            tmp2 = *((Int*)(pred + 4));
            k4 = tmp2 & 0xFF;
            k5 = mask & (tmp << 1);
            k4 = k5 - (k4 << 1);
            k5 = (tmp2 >> 8) & 0xFF;
            k6 = mask & (tmp >> 7);
            k5 = k6 - (k5 << 1);
            k6 = (tmp2 >> 16) & 0xFF;
            k7 = mask & (tmp >> 15);
            k6 = k7 - (k6 << 1);
            k7 = (tmp2 >> 24) & 0xFF;
            tmp = mask & (tmp >> 23);
            k7 = tmp - (k7 << 1);
            cur += width;
            pred += 16;

            /* fdct_1 */
            k0 = k0 + k7;
            k7 = k0 - (k7 << 1);
            k1 = k1 + k6;
            k6 = k1 - (k6 << 1);
            k2 = k2 + k5;
            k5 = k2 - (k5 << 1);
            k3 = k3 + k4;
            k4 = k3 - (k4 << 1);

            k0 = k0 + k3;
            k3 = k0 - (k3 << 1);
            k1 = k1 + k2;
            k2 = k1 - (k2 << 1);

            k0 = k0 + k1;
            /**********/
            dst[0] = k0;
            /* fdct_2 */
            k4 = k4 + k5;
            k5 = k5 + k6;
            k6 = k6 + k7;
            /* MUL2C k2,k5,724,FDCT_SHIFT */
            /* k0, k1 become scratch */
            /* assume FAST MULTIPLY */
            k1 = mla724(k12, k5, round);

            k5 = k1 >> FDCT_SHIFT;
            /*****************/
            /********/
            /* fdct_3 */
            /* ROTATE k4,k6,392,946, FDCT_SHIFT */
            /* assume FAST MULTIPLY */
            /* k0, k1 are output */
            k1 = mla392(k4, k14, round);
            k1 = mla946(k6, k14, k1);

            k6 = k1 >> FDCT_SHIFT;
            /***********************/
            k5 = k5 + k7;
            k5 = k5 + k6;
            /********/
            dst[1] = k5;
            dst += 8;
        }
        while (dst < out);
        out -= 64;
        dst = out + 2;
        /*  Vertical Block Loop  */
        do  /* Vertical 8xDCT loop */
        {
            k0 = out[0];
            k1 = out[8];
            k2 = out[16];
            k3 = out[24];
            k4 = out[32];
            k5 = out[40];
            k6 = out[48];
            k7 = out[56];

            abs_sum = sum_abs(k0, k1, k2, k3, k4, k5, k6, k7);

            if (abs_sum < ColTh)
            {
                out[0] = 0x7fff;
                out++;
                continue;
            }
            /* fdct_1 */
            k0 = k0 + k7;
            k7 = k0 - (k7 << 1);
            k1 = k1 + k6;
            k6 = k1 - (k6 << 1);
            k2 = k2 + k5;
            k5 = k2 - (k5 << 1);
            k3 = k3 + k4;
            k4 = k3 - (k4 << 1);

            k0 = k0 + k3;
            k3 = k0 - (k3 << 1);
            k1 = k1 + k2;
            k2 = k1 - (k2 << 1);

            k0 = k0 + k1;
            /**********/
            out[0] = k0;        /* row 0 */
            /* fdct_2 */
            k4 = k4 + k5;
            k5 = k5 + k6;
            k6 = k6 + k7;
            /* MUL2C k2,k5,724,FDCT_SHIFT */
            /* k0, k1 become scratch */
            /* assume FAST MULTIPLY */
            k1 = mla724(k12, k5, round);

            k5 = k1 >> FDCT_SHIFT;
            /*****************/
            /********/
            /* fdct_3 */
            /* ROTATE k4,k6,392,946, FDCT_SHIFT */
            /* assume FAST MULTIPLY */
            /* k0, k1 are output */
            k1 = mla392(k4, k14, round);
            k1 = mla946(k6, k14, k1);

            k6 = k1 >> FDCT_SHIFT;
            /***********************/
            k5 = k5 + k7;
            k5 = k5 + k6;
            /********/
            out[8] = k5 ;       /* row 1 */
            out++;
        }
        while ((UInt)out < (UInt)dst) ;

        return ;
    }

    /**************************************************************************/
    /*  Function:   BlockDCT_AANIntra
        Date:       8/9/01
        Input:      rec
        Output:     out[64] ==> next block
        Purpose:    Input directly from rec frame.
        Modified:
    **************************************************************************/

    Void BlockDCT_AANIntra(Short *out, UChar *cur, UChar *dummy2, Int width)
    {
        Short *dst;
        Int k0, k1, k2, k3, k4, k5, k6, k7;
        Int round;
        Int k12 = 0x022A02D4;
        Int k14 = 0x0188053A;
        Int abs_sum;
        Int mask;
        Int *curInt, tmp;
        Int ColTh;

        OSCL_UNUSED_ARG(dummy2);

        dst = out + 64 ;
        ColTh = *dst;
        out += 128;
        round = 1 << (FDCT_SHIFT - 1);

        do  /* fdct_nextrow */
        {
            mask = 0x1FE;
            curInt = (Int*) cur;
            tmp = curInt[0];    /* contains 4 pixels */
            k0 = mask & (tmp << 1);
            k1 = mask & (tmp >> 7);
            k2 = mask & (tmp >> 15);
            k3 = mask & (tmp >> 23);
            tmp = curInt[1];    /* another 4 pixels */
            k4 =  mask & (tmp << 1);
            k5 =  mask & (tmp >> 7);
            k6 =  mask & (tmp >> 15);
            k7 =  mask & (tmp >> 23);
            cur += width;
            /* fdct_1 */
            k0 = k0 + k7;
            k7 = k0 - (k7 << 1);
            k1 = k1 + k6;
            k6 = k1 - (k6 << 1);
            k2 = k2 + k5;
            k5 = k2 - (k5 << 1);
            k3 = k3 + k4;
            k4 = k3 - (k4 << 1);

            k0 = k0 + k3;
            k3 = k0 - (k3 << 1);
            k1 = k1 + k2;
            k2 = k1 - (k2 << 1);

            k0 = k0 + k1;
            k1 = k0 - (k1 << 1);
            /**********/
            dst[0] = k0;
            dst[4] = k1; /* col. 4 */
            /* fdct_2 */
            k4 = k4 + k5;
            k5 = k5 + k6;
            k6 = k6 + k7;
            k2 = k2 + k3;
            /* MUL2C k2,k5,724,FDCT_SHIFT */
            /* k0, k1 become scratch */
            /* assume FAST MULTIPLY */
            k1 = mla724(k12, k5, round);
            k0 = mla724(k12, k2, round);

            k5 = k1 >> FDCT_SHIFT;
            k2 = k0 >> FDCT_SHIFT;
            /*****************/
            k2 = k2 + k3;
            k3 = (k3 << 1) - k2;
            /********/
            dst[2] = k2;        /* col. 2 */
            k3 <<= 1;       /* scale up col. 6 */
            dst[6] = k3; /* col. 6 */
            /* fdct_3 */
            /* ROTATE k4,k6,392,946, FDCT_SHIFT */
            /* assume FAST MULTIPLY */
            /* k0, k1 are output */
            k0 = k4 - k6;

            k1 = mla392(k0, k14, round);
            k0 = mla554(k4, k12, k1);
            k1 = mla1338(k6, k14, k1);

            k4 = k0 >> FDCT_SHIFT;
            k6 = k1 >> FDCT_SHIFT;
            /***********************/
            k5 = k5 + k7;
            k7 = (k7 << 1) - k5;
            k4 = k4 + k7;
            k7 = (k7 << 1) - k4;
            k5 = k5 + k6;
            k4 <<= 1;       /* scale up col.5 */
            k6 = k5 - (k6 << 1);
            /********/
            dst[5] = k4;    /* col. 5 */
            k6 <<= 2;       /* scale up col. 7 */
            dst[1] = k5;    /* col. 1 */
            dst[7] = k6;    /* col. 7 */
            dst[3] = k7;    /* col. 3 */
            dst += 8;
        }
        while (dst < out);

        out -= 64;
        dst = out + 8;

        /*  Vertical Block Loop  */
        do  /* Vertical 8xDCT loop */
        {
            k0 = out[0];
            k1 = out[8];
            k2 = out[16];
            k3 = out[24];
            k4 = out[32];
            k5 = out[40];
            k6 = out[48];
            k7 = out[56];
            /* deadzone thresholding for column */

            abs_sum = sum_abs(k0, k1, k2, k3, k4, k5, k6, k7);

            if (abs_sum < ColTh)
            {
                out[0] = 0x7fff;
                out++;
                continue;
            }

            /* fdct_1 */
            k0 = k0 + k7;
            k7 = k0 - (k7 << 1);
            k1 = k1 + k6;
            k6 = k1 - (k6 << 1);
            k2 = k2 + k5;
            k5 = k2 - (k5 << 1);
            k3 = k3 + k4;
            k4 = k3 - (k4 << 1);

            k0 = k0 + k3;
            k3 = k0 - (k3 << 1);
            k1 = k1 + k2;
            k2 = k1 - (k2 << 1);

            k0 = k0 + k1;
            k1 = k0 - (k1 << 1);
            /**********/
            out[32] = k1; /* row 4 */
            out[0] = k0; /* row 0 */
            /* fdct_2 */
            k4 = k4 + k5;
            k5 = k5 + k6;
            k6 = k6 + k7;
            k2 = k2 + k3;
            /* MUL2C k2,k5,724,FDCT_SHIFT */
            /* k0, k1 become scratch */
            /* assume FAST MULTIPLY */
            k1 = mla724(k12, k5, round);
            k0 = mla724(k12, k2, round);

            k5 = k1 >> FDCT_SHIFT;
            k2 = k0 >> FDCT_SHIFT;
            /*****************/
            k2 = k2 + k3;
            k3 = (k3 << 1) - k2;
            k3 <<= 1;       /* scale up col. 6 */
            /********/
            out[48] = k3;   /* row 6 */
            out[16] = k2;   /* row 2 */
            /* fdct_3 */
            /* ROTATE k4,k6,392,946, FDCT_SHIFT */
            /* assume FAST MULTIPLY */
            /* k0, k1 are output */
            k0 = k4 - k6;

            k1 = mla392(k0, k14, round);
            k0 = mla554(k4, k12, k1);
            k1 = mla1338(k6, k14, k1);

            k4 = k0 >> FDCT_SHIFT;
            k6 = k1 >> FDCT_SHIFT;
            /***********************/
            k5 = k5 + k7;
            k7 = (k7 << 1) - k5;
            k4 = k4 + k7;
            k7 = (k7 << 1) - k4;
            k5 = k5 + k6;
            k4 <<= 1;       /* scale up col. 5 */
            k6 = k5 - (k6 << 1);
            /********/
            out[24] = k7 ;    /* row 3 */
            k6 <<= 2;       /* scale up col. 7 */
            out[56] = k6 ;   /* row 7 */
            out[8] = k5 ;    /* row 1 */
            out[40] = k4 ;   /* row 5 */
            out++;
        }
        while ((UInt)out < (UInt)dst) ;

        return ;
    }

    /**************************************************************************/
    /*  Function:   Block4x4DCT_AANIntra
        Date:       8/9/01
        Input:      prev
        Output:     out[64] ==> next block
        Purpose:    Input directly from prev frame. output 2x2 DCT
        Modified:
    **************************************************************************/

    Void Block4x4DCT_AANIntra(Short *out, UChar *cur, UChar *dummy2, Int width)
    {
        Short *dst;
        register Int k0, k1, k2, k3, k4, k5, k6, k7;
        Int round;
        Int k12 = 0x022A02D4;
        Int k14 = 0x0188053A;
        Int mask;
        Int *curInt, tmp;
        Int abs_sum;
        Int ColTh;

        OSCL_UNUSED_ARG(dummy2);

        dst = out + 64 ;
        ColTh = *dst;
        out += 128;
        round = 1 << (FDCT_SHIFT - 1);

        do  /* fdct_nextrow */
        {
            mask = 0x1FE;
            curInt = (Int*) cur;
            tmp = curInt[0];    /* contains 4 pixels */
            k0 = mask & (tmp << 1);
            k1 = mask & (tmp >> 7);
            k2 = mask & (tmp >> 15);
            k3 = mask & (tmp >> 23);
            tmp = curInt[1];    /* another 4 pixels */
            k4 =  mask & (tmp << 1);
            k5 =  mask & (tmp >> 7);
            k6 =  mask & (tmp >> 15);
            k7 =  mask & (tmp >> 23);
            cur += width;
            /* fdct_1 */
            k0 = k0 + k7;
            k7 = k0 - (k7 << 1);
            k1 = k1 + k6;
            k6 = k1 - (k6 << 1);
            k2 = k2 + k5;
            k5 = k2 - (k5 << 1);
            k3 = k3 + k4;
            k4 = k3 - (k4 << 1);

            k0 = k0 + k3;
            k3 = k0 - (k3 << 1);
            k1 = k1 + k2;
            k2 = k1 - (k2 << 1);

            k0 = k0 + k1;
            /**********/
            dst[0] = k0;
            /* fdct_2 */
            k4 = k4 + k5;
            k5 = k5 + k6;
            k6 = k6 + k7;
            k2 = k2 + k3;
            /* MUL2C k2,k5,724,FDCT_SHIFT */
            /* k0, k1 become scratch */
            /* assume FAST MULTIPLY */
            k1 = mla724(k12, k5, round);
            k0 = mla724(k12, k2, round);

            k5 = k1 >> FDCT_SHIFT;
            k2 = k0 >> FDCT_SHIFT;
            /*****************/
            k2 = k2 + k3;
            /********/
            dst[2] = k2;        /* col. 2 */
            /* fdct_3 */
            /* ROTATE k4,k6,392,946, FDCT_SHIFT */
            /* assume FAST MULTIPLY */
            /* k0, k1 are output */
            k0 = k4 - k6;

            k1 = mla392(k0, k14, round);
            k0 = mla554(k4, k12, k1);
            k1 = mla1338(k6, k14, k1);

            k4 = k0 >> FDCT_SHIFT;
            k6 = k1 >> FDCT_SHIFT;
            /***********************/
            k5 = k5 + k7;
            k7 = (k7 << 1) - k5;
            k7 = k7 - k4;
            k5 = k5 + k6;
            /********/
            dst[1] = k5;        /* col. 1 */
            dst[3] = k7;        /* col. 3 */
            dst += 8;
        }
        while (dst < out);

        out -= 64;
        dst = out + 4;

        /*  Vertical Block Loop  */
        do  /* Vertical 8xDCT loop */
        {
            k0 = out[0];
            k1 = out[8];
            k2 = out[16];
            k3 = out[24];
            k4 = out[32];
            k5 = out[40];
            k6 = out[48];
            k7 = out[56];

            abs_sum = sum_abs(k0, k1, k2, k3, k4, k5, k6, k7);

            if (abs_sum < ColTh)
            {
                out[0] = 0x7fff;
                out++;
                continue;
            }
            /* fdct_1 */
            k0 = k0 + k7;
            k7 = k0 - (k7 << 1);
            k1 = k1 + k6;
            k6 = k1 - (k6 << 1);
            k2 = k2 + k5;
            k5 = k2 - (k5 << 1);
            k3 = k3 + k4;
            k4 = k3 - (k4 << 1);

            k0 = k0 + k3;
            k3 = k0 - (k3 << 1);
            k1 = k1 + k2;
            k2 = k1 - (k2 << 1);

            k0 = k0 + k1;
            /**********/
            out[0] = k0;   /* row 0 */
            /* fdct_2 */
            k4 = k4 + k5;
            k5 = k5 + k6;
            k6 = k6 + k7;
            k2 = k2 + k3;
            /* MUL2C k2,k5,724,FDCT_SHIFT */
            /* k0, k1 become scratch */
            /* assume FAST MULTIPLY */
            k1 = mla724(k12, k5, round);
            k0 = mla724(k12, k2, round);

            k5 = k1 >> FDCT_SHIFT;
            k2 = k0 >> FDCT_SHIFT;
            /*****************/
            k2 = k2 + k3;
            /********/
            out[16] = k2;           /* row 2 */
            /* fdct_3 */
            /* ROTATE k4,k6,392,946, FDCT_SHIFT */
            /* assume FAST MULTIPLY */
            /* k0, k1 are output */
            k0 = k4 - k6;

            k1 = mla392(k0, k14, round);
            k0 = mla554(k4, k12, k1);
            k1 = mla1338(k6, k14, k1);

            k4 = k0 >> FDCT_SHIFT;
            k6 = k1 >> FDCT_SHIFT;
            /***********************/
            k5 = k5 + k7;
            k7 = (k7 << 1) - k5;
            k7 = k7 - k4 ;
            k5 = k5 + k6;
            /********/
            out[24] = k7 ;      /* row 3 */
            out[8] = k5 ;       /* row 1 */
            out++;
        }
        while ((UInt)out < (UInt)dst) ;

        return ;
    }

    /**************************************************************************/
    /*  Function:   Block2x2DCT_AANIntra
        Date:       8/9/01
        Input:      prev
        Output:     out[64] ==> next block
        Purpose:    Input directly from prev frame. output 2x2 DCT
        Modified:
    **************************************************************************/

    Void Block2x2DCT_AANIntra(Short *out, UChar *cur, UChar *dummy2, Int width)
    {
        Short *dst;
        register Int k0, k1, k2, k3, k4, k5, k6, k7;
        Int round;
        Int k12 = 0x022A02D4;
        Int k14 = 0x018803B2;
        Int mask;
        Int *curInt, tmp;
        Int abs_sum;
        Int ColTh;

        OSCL_UNUSED_ARG(dummy2);

        dst = out + 64 ;
        ColTh = *dst;
        out += 128;
        round = 1 << (FDCT_SHIFT - 1);

        do  /* fdct_nextrow */
        {
            mask = 0x1FE;
            curInt = (Int*) cur;
            tmp = curInt[0];    /* contains 4 pixels */
            k0 = mask & (tmp << 1);
            k1 = mask & (tmp >> 7);
            k2 = mask & (tmp >> 15);
            k3 = mask & (tmp >> 23);
            tmp = curInt[1];    /* another 4 pixels */
            k4 =  mask & (tmp << 1);
            k5 =  mask & (tmp >> 7);
            k6 =  mask & (tmp >> 15);
            k7 =  mask & (tmp >> 23);
            cur += width;

            /* fdct_1 */
            k0 = k0 + k7;
            k7 = k0 - (k7 << 1);
            k1 = k1 + k6;
            k6 = k1 - (k6 << 1);
            k2 = k2 + k5;
            k5 = k2 - (k5 << 1);
            k3 = k3 + k4;
            k4 = k3 - (k4 << 1);

            k0 = k0 + k3;
            k3 = k0 - (k3 << 1);
            k1 = k1 + k2;
            k2 = k1 - (k2 << 1);

            k0 = k0 + k1;
            /**********/
            dst[0] = k0;
            /* fdct_2 */
            k4 = k4 + k5;
            k5 = k5 + k6;
            k6 = k6 + k7;
            /* MUL2C k2,k5,724,FDCT_SHIFT */
            /* k0, k1 become scratch */
            /* assume FAST MULTIPLY */
            k1 = mla724(k12, k5, round);

            k5 = k1 >> FDCT_SHIFT;
            /*****************/
            /********/
            /* fdct_3 */
            /* ROTATE k4,k6,392,946, FDCT_SHIFT */
            /* assume FAST MULTIPLY */
            /* k0, k1 are output */
            k1 = mla392(k4, k14, round);
            k1 = mla946(k6, k14, k1);

            k6 = k1 >> FDCT_SHIFT;
            /***********************/
            k5 = k5 + k7;
            k5 = k5 + k6;
            /********/
            dst[1] = k5;
            dst += 8;
        }
        while (dst < out);
        out -= 64;
        dst = out + 2;
        /*  Vertical Block Loop  */
        do  /* Vertical 8xDCT loop */
        {
            k0 = out[0];
            k1 = out[8];
            k2 = out[16];
            k3 = out[24];
            k4 = out[32];
            k5 = out[40];
            k6 = out[48];
            k7 = out[56];

            abs_sum = sum_abs(k0, k1, k2, k3, k4, k5, k6, k7);

            if (abs_sum < ColTh)
            {
                out[0] = 0x7fff;
                out++;
                continue;
            }
            /* fdct_1 */
            k0 = k0 + k7;
            k7 = k0 - (k7 << 1);
            k1 = k1 + k6;
            k6 = k1 - (k6 << 1);
            k2 = k2 + k5;
            k5 = k2 - (k5 << 1);
            k3 = k3 + k4;
            k4 = k3 - (k4 << 1);

            k0 = k0 + k3;
            k3 = k0 - (k3 << 1);
            k1 = k1 + k2;
            k2 = k1 - (k2 << 1);

            k0 = k0 + k1;
            /**********/
            out[0] = k0;        /* row 0 */
            /* fdct_2 */
            k4 = k4 + k5;
            k5 = k5 + k6;
            k6 = k6 + k7;
            /* MUL2C k2,k5,724,FDCT_SHIFT */
            /* k0, k1 become scratch */
            /* assume FAST MULTIPLY */
            k1 = mla724(k12, k5, round);

            k5 = k1 >> FDCT_SHIFT;
            /*****************/
            /********/
            /* fdct_3 */
            /* ROTATE k4,k6,392,946, FDCT_SHIFT */
            /* assume FAST MULTIPLY */
            /* k0, k1 are output */
            k1 = mla392(k4, k14, round);
            k1 = mla946(k6, k14, k1);

            k6 = k1 >> FDCT_SHIFT;
            /***********************/
            k5 = k5 + k7;
            k5 = k5 + k6;
            /********/
            out[8] = k5 ;       /* row 1 */
            out++;
        }
        while ((UInt)out < (UInt)dst) ;

        return ;
    }
    /**************************************************************************/
    /*  Function:   Block1x1DCTwSub
        Date:       8/9/01
        Input:      block
        Output:     y
        Purpose:    Compute DC value only
        Modified:
    **************************************************************************/
    void Block1x1DCTwSub(Short *out, UChar *cur, UChar *pred, Int width)
    {
        UChar *end;
        Int temp = 0;
        Int offset2;

        offset2 = width - 8;
        end = pred + (16 << 3);
        do
        {
            temp += (*cur++ - *pred++);
            temp += (*cur++ - *pred++);
            temp += (*cur++ - *pred++);
            temp += (*cur++ - *pred++);
            temp += (*cur++ - *pred++);
            temp += (*cur++ - *pred++);
            temp += (*cur++ - *pred++);
            temp += (*cur++ - *pred++);
            cur += offset2;
            pred += 8;
        }
        while (pred < end) ;

        out[1] = out[2] = out[3] = out[4] = out[5] = out[6] = out[7] = 0;
        out[0] = temp >> 3;

        return ;
    }

    /**************************************************************************/
    /*  Function:   Block1x1DCTIntra
        Date:       8/9/01
        Input:      prev
        Output:     out
        Purpose:    Compute DC value only
        Modified:
    **************************************************************************/
    void Block1x1DCTIntra(Short *out, UChar *cur, UChar *dummy2, Int width)
    {
        UChar *end;
        Int temp = 0;
        ULong word;

        OSCL_UNUSED_ARG(dummy2);

        end = cur + (width << 3);
        do
        {
            word = *((ULong*)cur);
            temp += (word >> 24);
            temp += ((word >> 16) & 0xFF);
            temp += ((word >> 8) & 0xFF);
            temp += (word & 0xFF);

            word = *((ULong*)(cur + 4));
            temp += (word >> 24);
            temp += ((word >> 16) & 0xFF);
            temp += ((word >> 8) & 0xFF);
            temp += (word & 0xFF);

            cur += width;
        }
        while (cur < end) ;

        out[1] = out[2] = out[3] = out[4] = out[5] = out[6] = out[7] = 0;
        out[0] = temp >> 3;

        return ;
    }

#ifdef __cplusplus
}
#endif

