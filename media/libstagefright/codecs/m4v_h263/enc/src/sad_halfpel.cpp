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
/* contains
Int HalfPel1_SAD_MB(UChar *ref,UChar *blk,Int dmin,Int width,Int ih,Int jh)
Int HalfPel2_SAD_MB(UChar *ref,UChar *blk,Int dmin,Int width)
Int HalfPel1_SAD_Blk(UChar *ref,UChar *blk,Int dmin,Int width,Int ih,Int jh)
Int HalfPel2_SAD_Blk(UChar *ref,UChar *blk,Int dmin,Int width)

Int SAD_MB_HalfPel_C(UChar *ref,UChar *blk,Int dmin,Int width,Int rx,Int xh,Int yh,void *extra_info)
Int SAD_MB_HP_HTFM_Collect(UChar *ref,UChar *blk,Int dmin,Int width,Int rx,Int xh,Int yh,void *extra_info)
Int SAD_MB_HP_HTFM(UChar *ref,UChar *blk,Int dmin,Int width,Int rx,Int xh,Int yh,void *extra_info)
Int SAD_Blk_HalfPel_C(UChar *ref,UChar *blk,Int dmin,Int width,Int rx,Int xh,Int yh,void *extra_info)
*/

//#include <stdlib.h> /* for RAND_MAX */
#include "mp4def.h"
#include "mp4lib_int.h"
#include "sad_halfpel_inline.h"

#ifdef _SAD_STAT
ULong num_sad_HP_MB = 0;
ULong num_sad_HP_Blk = 0;
ULong num_sad_HP_MB_call = 0;
ULong num_sad_HP_Blk_call = 0;
#define NUM_SAD_HP_MB_CALL()    num_sad_HP_MB_call++
#define NUM_SAD_HP_MB()         num_sad_HP_MB++
#define NUM_SAD_HP_BLK_CALL()   num_sad_HP_Blk_call++
#define NUM_SAD_HP_BLK()        num_sad_HP_Blk++
#else
#define NUM_SAD_HP_MB_CALL()
#define NUM_SAD_HP_MB()
#define NUM_SAD_HP_BLK_CALL()
#define NUM_SAD_HP_BLK()
#endif


#ifdef __cplusplus
extern "C"
{
#endif
    /*==================================================================
        Function:   HalfPel1_SAD_MB
        Date:       03/27/2001
        Purpose:    Compute SAD 16x16 between blk and ref in halfpel
                    resolution,
        Changes:
      ==================================================================*/
    /* One component is half-pel */
    Int HalfPel1_SAD_MB(UChar *ref, UChar *blk, Int dmin, Int width, Int ih, Int jh)
    {
        Int i, j;
        Int sad = 0;
        UChar *kk, *p1, *p2;
        Int temp;

        OSCL_UNUSED_ARG(jh);

        p1 = ref;
        if (ih) p2 = ref + 1;
        else p2 = ref + width;
        kk  = blk;

        for (i = 0; i < 16; i++)
        {
            for (j = 0; j < 16; j++)
            {

                temp = ((p1[j] + p2[j] + 1) >> 1) - *kk++;
                sad += PV_ABS(temp);
            }

            if (sad > dmin)
                return sad;
            p1 += width;
            p2 += width;
        }
        return sad;
    }

    /* Two components need half-pel */
    Int HalfPel2_SAD_MB(UChar *ref, UChar *blk, Int dmin, Int width)
    {
        Int i, j;
        Int sad = 0;
        UChar *kk, *p1, *p2, *p3, *p4;
        Int temp;

        p1 = ref;
        p2 = ref + 1;
        p3 = ref + width;
        p4 = ref + width + 1;
        kk  = blk;

        for (i = 0; i < 16; i++)
        {
            for (j = 0; j < 16; j++)
            {

                temp = ((p1[j] + p2[j] + p3[j] + p4[j] + 2) >> 2) - *kk++;
                sad += PV_ABS(temp);
            }

            if (sad > dmin)
                return sad;

            p1 += width;
            p3 += width;
            p2 += width;
            p4 += width;
        }
        return sad;
    }

#ifndef NO_INTER4V
    /*==================================================================
        Function:   HalfPel1_SAD_Blk
        Date:       03/27/2001
        Purpose:    Compute SAD 8x8 between blk and ref in halfpel
                    resolution.
        Changes:
      ==================================================================*/
    /* One component needs half-pel */
    Int HalfPel1_SAD_Blk(UChar *ref, UChar *blk, Int dmin, Int width, Int ih, Int jh)
    {
        Int i, j;
        Int sad = 0;
        UChar *kk, *p1, *p2;
        Int temp;

        OSCL_UNUSED_ARG(jh);

        p1 = ref;
        if (ih) p2 = ref + 1;
        else p2 = ref + width;
        kk  = blk;

        for (i = 0; i < 8; i++)
        {
            for (j = 0; j < 8; j++)
            {

                temp = ((p1[j] + p2[j] + 1) >> 1) - *kk++;
                sad += PV_ABS(temp);
            }

            if (sad > dmin)
                return sad;
            p1 += width;
            p2 += width;
            kk += 8;
        }
        return sad;
    }
    /* Two components need half-pel */
    Int HalfPel2_SAD_Blk(UChar *ref, UChar *blk, Int dmin, Int width)
    {
        Int i, j;
        Int sad = 0;
        UChar *kk, *p1, *p2, *p3, *p4;
        Int temp;

        p1 = ref;
        p2 = ref + 1;
        p3 = ref + width;
        p4 = ref + width + 1;
        kk  = blk;

        for (i = 0; i < 8; i++)
        {
            for (j = 0; j < 8; j++)
            {

                temp = ((p1[j] + p2[j] + p3[j] + p4[j] + 2) >> 2) - *kk++;
                sad += PV_ABS(temp);
            }

            if (sad > dmin)
                return sad;

            p1 += width;
            p3 += width;
            p2 += width;
            p4 += width;
            kk += 8;
        }
        return sad;
    }
#endif // NO_INTER4V
    /*===============================================================
        Function:   SAD_MB_HalfPel
        Date:       09/17/2000
        Purpose:    Compute the SAD on the half-pel resolution
        Input/Output:   hmem is assumed to be a pointer to the starting
                    point of the search in the 33x33 matrix search region
        Changes:
    11/7/00:     implemented MMX
      ===============================================================*/
    /*==================================================================
        Function:   SAD_MB_HalfPel_C
        Date:       04/30/2001
        Purpose:    Compute SAD 16x16 between blk and ref in halfpel
                    resolution,
        Changes:
      ==================================================================*/
    /* One component is half-pel */
    Int SAD_MB_HalfPel_Cxhyh(UChar *ref, UChar *blk, Int dmin_rx, void *extra_info)
    {
        Int i, j;
        Int sad = 0;
        UChar *kk, *p1, *p2, *p3, *p4;
//  Int sumref=0;
        Int temp;
        Int rx = dmin_rx & 0xFFFF;

        OSCL_UNUSED_ARG(extra_info);

        NUM_SAD_HP_MB_CALL();

        p1 = ref;
        p2 = ref + 1;
        p3 = ref + rx;
        p4 = ref + rx + 1;
        kk  = blk;

        for (i = 0; i < 16; i++)
        {
            for (j = 0; j < 16; j++)
            {

                temp = ((p1[j] + p2[j] + p3[j] + p4[j] + 2) >> 2) - *kk++;
                sad += PV_ABS(temp);
            }

            NUM_SAD_HP_MB();

            if (sad > (Int)((ULong)dmin_rx >> 16))
                return sad;

            p1 += rx;
            p3 += rx;
            p2 += rx;
            p4 += rx;
        }
        return sad;
    }

    Int SAD_MB_HalfPel_Cyh(UChar *ref, UChar *blk, Int dmin_rx, void *extra_info)
    {
        Int i, j;
        Int sad = 0;
        UChar *kk, *p1, *p2;
//  Int sumref=0;
        Int temp;
        Int rx = dmin_rx & 0xFFFF;

        OSCL_UNUSED_ARG(extra_info);

        NUM_SAD_HP_MB_CALL();

        p1 = ref;
        p2 = ref + rx; /* either left/right or top/bottom pixel */
        kk  = blk;

        for (i = 0; i < 16; i++)
        {
            for (j = 0; j < 16; j++)
            {

                temp = ((p1[j] + p2[j] + 1) >> 1) - *kk++;
                sad += PV_ABS(temp);
            }

            NUM_SAD_HP_MB();

            if (sad > (Int)((ULong)dmin_rx >> 16))
                return sad;
            p1 += rx;
            p2 += rx;
        }
        return sad;
    }

    Int SAD_MB_HalfPel_Cxh(UChar *ref, UChar *blk, Int dmin_rx, void *extra_info)
    {
        Int i, j;
        Int sad = 0;
        UChar *kk, *p1;
//  Int sumref=0;
        Int temp;
        Int rx = dmin_rx & 0xFFFF;

        OSCL_UNUSED_ARG(extra_info);

        NUM_SAD_HP_MB_CALL();

        p1 = ref;
        kk  = blk;

        for (i = 0; i < 16; i++)
        {
            for (j = 0; j < 16; j++)
            {

                temp = ((p1[j] + p1[j+1] + 1) >> 1) - *kk++;
                sad += PV_ABS(temp);
            }

            NUM_SAD_HP_MB();

            if (sad > (Int)((ULong)dmin_rx >> 16))
                return sad;
            p1 += rx;
        }
        return sad;
    }

#ifdef HTFM  /* HTFM with uniform subsampling implementation, 2/28/01 */

//Checheck here
    Int SAD_MB_HP_HTFM_Collectxhyh(UChar *ref, UChar *blk, Int dmin_rx, void *extra_info)
    {
        Int i, j;
        Int sad = 0;
        UChar *p1, *p2;
        Int rx = dmin_rx & 0xFFFF;
        Int refwx4 = rx << 2;
        Int saddata[16];      /* used when collecting flag (global) is on */
        Int difmad, tmp, tmp2;
        HTFM_Stat *htfm_stat = (HTFM_Stat*) extra_info;
        Int *abs_dif_mad_avg = &(htfm_stat->abs_dif_mad_avg);
        UInt *countbreak = &(htfm_stat->countbreak);
        Int *offsetRef = htfm_stat->offsetRef;
        ULong cur_word;

        NUM_SAD_HP_MB_CALL();

        blk -= 4;

        for (i = 0; i < 16; i++) /* 16 stages */
        {
            p1 = ref + offsetRef[i];
            p2 = p1 + rx;

            j = 4;/* 4 lines */
            do
            {
                cur_word = *((ULong*)(blk += 4));
                tmp = p1[12] + p2[12];
                tmp2 = p1[13] + p2[13];
                tmp += tmp2;
                tmp2 = (cur_word >> 24) & 0xFF;
                tmp += 2;
                sad = INTERP2_SUB_SAD(sad, tmp, tmp2);;
                tmp = p1[8] + p2[8];
                tmp2 = p1[9] + p2[9];
                tmp += tmp2;
                tmp2 = (cur_word >> 16) & 0xFF;
                tmp += 2;
                sad = INTERP2_SUB_SAD(sad, tmp, tmp2);;
                tmp = p1[4] + p2[4];
                tmp2 = p1[5] + p2[5];
                tmp += tmp2;
                tmp2 = (cur_word >> 8) & 0xFF;
                tmp += 2;
                sad = INTERP2_SUB_SAD(sad, tmp, tmp2);;
                tmp2 = p1[1] + p2[1];
                tmp = p1[0] + p2[0];
                p1 += refwx4;
                p2 += refwx4;
                tmp += tmp2;
                tmp2 = (cur_word & 0xFF);
                tmp += 2;
                sad = INTERP2_SUB_SAD(sad, tmp, tmp2);;
            }
            while (--j);

            NUM_SAD_HP_MB();

            saddata[i] = sad;

            if (i > 0)
            {
                if (sad > (Int)((ULong)dmin_rx >> 16))
                {
                    difmad = saddata[0] - ((saddata[1] + 1) >> 1);
                    (*abs_dif_mad_avg) += ((difmad > 0) ? difmad : -difmad);
                    (*countbreak)++;
                    return sad;
                }
            }
        }
        difmad = saddata[0] - ((saddata[1] + 1) >> 1);
        (*abs_dif_mad_avg) += ((difmad > 0) ? difmad : -difmad);
        (*countbreak)++;

        return sad;
    }

    Int SAD_MB_HP_HTFM_Collectyh(UChar *ref, UChar *blk, Int dmin_rx, void *extra_info)
    {
        Int i, j;
        Int sad = 0;
        UChar *p1, *p2;
        Int rx = dmin_rx & 0xFFFF;
        Int refwx4 = rx << 2;
        Int saddata[16];      /* used when collecting flag (global) is on */
        Int difmad, tmp, tmp2;
        HTFM_Stat *htfm_stat = (HTFM_Stat*) extra_info;
        Int *abs_dif_mad_avg = &(htfm_stat->abs_dif_mad_avg);
        UInt *countbreak = &(htfm_stat->countbreak);
        Int *offsetRef = htfm_stat->offsetRef;
        ULong cur_word;

        NUM_SAD_HP_MB_CALL();

        blk -= 4;

        for (i = 0; i < 16; i++) /* 16 stages */
        {
            p1 = ref + offsetRef[i];
            p2 = p1 + rx;
            j = 4;
            do
            {
                cur_word = *((ULong*)(blk += 4));
                tmp = p1[12];
                tmp2 = p2[12];
                tmp++;
                tmp2 += tmp;
                tmp = (cur_word >> 24) & 0xFF;
                sad = INTERP1_SUB_SAD(sad, tmp, tmp2);;
                tmp = p1[8];
                tmp2 = p2[8];
                tmp++;
                tmp2 += tmp;
                tmp = (cur_word >> 16) & 0xFF;
                sad = INTERP1_SUB_SAD(sad, tmp, tmp2);;
                tmp = p1[4];
                tmp2 = p2[4];
                tmp++;
                tmp2 += tmp;
                tmp = (cur_word >> 8) & 0xFF;
                sad = INTERP1_SUB_SAD(sad, tmp, tmp2);;
                tmp = p1[0];
                p1 += refwx4;
                tmp2 = p2[0];
                p2 += refwx4;
                tmp++;
                tmp2 += tmp;
                tmp = (cur_word & 0xFF);
                sad = INTERP1_SUB_SAD(sad, tmp, tmp2);;
            }
            while (--j);

            NUM_SAD_HP_MB();

            saddata[i] = sad;

            if (i > 0)
            {
                if (sad > (Int)((ULong)dmin_rx >> 16))
                {
                    difmad = saddata[0] - ((saddata[1] + 1) >> 1);
                    (*abs_dif_mad_avg) += ((difmad > 0) ? difmad : -difmad);
                    (*countbreak)++;
                    return sad;
                }
            }
        }
        difmad = saddata[0] - ((saddata[1] + 1) >> 1);
        (*abs_dif_mad_avg) += ((difmad > 0) ? difmad : -difmad);
        (*countbreak)++;

        return sad;
    }

    Int SAD_MB_HP_HTFM_Collectxh(UChar *ref, UChar *blk, Int dmin_rx, void *extra_info)
    {
        Int i, j;
        Int sad = 0;
        UChar *p1;
        Int rx = dmin_rx & 0xFFFF;
        Int refwx4 = rx << 2;
        Int saddata[16];      /* used when collecting flag (global) is on */
        Int difmad, tmp, tmp2;
        HTFM_Stat *htfm_stat = (HTFM_Stat*) extra_info;
        Int *abs_dif_mad_avg = &(htfm_stat->abs_dif_mad_avg);
        UInt *countbreak = &(htfm_stat->countbreak);
        Int *offsetRef = htfm_stat->offsetRef;
        ULong cur_word;

        NUM_SAD_HP_MB_CALL();

        blk -= 4;

        for (i = 0; i < 16; i++) /* 16 stages */
        {
            p1 = ref + offsetRef[i];

            j = 4; /* 4 lines */
            do
            {
                cur_word = *((ULong*)(blk += 4));
                tmp = p1[12];
                tmp2 = p1[13];
                tmp++;
                tmp2 += tmp;
                tmp = (cur_word >> 24) & 0xFF;
                sad = INTERP1_SUB_SAD(sad, tmp, tmp2);;
                tmp = p1[8];
                tmp2 = p1[9];
                tmp++;
                tmp2 += tmp;
                tmp = (cur_word >> 16) & 0xFF;
                sad = INTERP1_SUB_SAD(sad, tmp, tmp2);;
                tmp = p1[4];
                tmp2 = p1[5];
                tmp++;
                tmp2 += tmp;
                tmp = (cur_word >> 8) & 0xFF;
                sad = INTERP1_SUB_SAD(sad, tmp, tmp2);;
                tmp = p1[0];
                tmp2 = p1[1];
                p1 += refwx4;
                tmp++;
                tmp2 += tmp;
                tmp = (cur_word & 0xFF);
                sad = INTERP1_SUB_SAD(sad, tmp, tmp2);;
            }
            while (--j);

            NUM_SAD_HP_MB();

            saddata[i] = sad;

            if (i > 0)
            {
                if (sad > (Int)((ULong)dmin_rx >> 16))
                {
                    difmad = saddata[0] - ((saddata[1] + 1) >> 1);
                    (*abs_dif_mad_avg) += ((difmad > 0) ? difmad : -difmad);
                    (*countbreak)++;
                    return sad;
                }
            }
        }
        difmad = saddata[0] - ((saddata[1] + 1) >> 1);
        (*abs_dif_mad_avg) += ((difmad > 0) ? difmad : -difmad);
        (*countbreak)++;

        return sad;
    }

    Int SAD_MB_HP_HTFMxhyh(UChar *ref, UChar *blk, Int dmin_rx, void *extra_info)
    {
        Int i, j;
        Int sad = 0, tmp, tmp2;
        UChar *p1, *p2;
        Int rx = dmin_rx & 0xFFFF;
        Int refwx4 = rx << 2;
        Int sadstar = 0, madstar;
        Int *nrmlz_th = (Int*) extra_info;
        Int *offsetRef = nrmlz_th + 32;
        ULong cur_word;

        madstar = (ULong)dmin_rx >> 20;

        NUM_SAD_HP_MB_CALL();

        blk -= 4;

        for (i = 0; i < 16; i++) /* 16 stages */
        {
            p1 = ref + offsetRef[i];
            p2 = p1 + rx;

            j = 4; /* 4 lines */
            do
            {
                cur_word = *((ULong*)(blk += 4));
                tmp = p1[12] + p2[12];
                tmp2 = p1[13] + p2[13];
                tmp += tmp2;
                tmp2 = (cur_word >> 24) & 0xFF;
                tmp += 2;
                sad = INTERP2_SUB_SAD(sad, tmp, tmp2);;
                tmp = p1[8] + p2[8];
                tmp2 = p1[9] + p2[9];
                tmp += tmp2;
                tmp2 = (cur_word >> 16) & 0xFF;
                tmp += 2;
                sad = INTERP2_SUB_SAD(sad, tmp, tmp2);;
                tmp = p1[4] + p2[4];
                tmp2 = p1[5] + p2[5];
                tmp += tmp2;
                tmp2 = (cur_word >> 8) & 0xFF;
                tmp += 2;
                sad = INTERP2_SUB_SAD(sad, tmp, tmp2);;
                tmp2 = p1[1] + p2[1];
                tmp = p1[0] + p2[0];
                p1 += refwx4;
                p2 += refwx4;
                tmp += tmp2;
                tmp2 = (cur_word & 0xFF);
                tmp += 2;
                sad = INTERP2_SUB_SAD(sad, tmp, tmp2);;
            }
            while (--j);

            NUM_SAD_HP_MB();

            sadstar += madstar;
            if (sad > sadstar - nrmlz_th[i] || sad > (Int)((ULong)dmin_rx >> 16))
            {
                return 65536;
            }
        }

        return sad;
    }

    Int SAD_MB_HP_HTFMyh(UChar *ref, UChar *blk, Int dmin_rx, void *extra_info)
    {
        Int i, j;
        Int sad = 0, tmp, tmp2;
        UChar *p1, *p2;
        Int rx = dmin_rx & 0xFFFF;
        Int refwx4 = rx << 2;
        Int sadstar = 0, madstar;
        Int *nrmlz_th = (Int*) extra_info;
        Int *offsetRef = nrmlz_th + 32;
        ULong cur_word;

        madstar = (ULong)dmin_rx >> 20;

        NUM_SAD_HP_MB_CALL();

        blk -= 4;

        for (i = 0; i < 16; i++) /* 16 stages */
        {
            p1 = ref + offsetRef[i];
            p2 = p1 + rx;
            j = 4;
            do
            {
                cur_word = *((ULong*)(blk += 4));
                tmp = p1[12];
                tmp2 = p2[12];
                tmp++;
                tmp2 += tmp;
                tmp = (cur_word >> 24) & 0xFF;
                sad = INTERP1_SUB_SAD(sad, tmp, tmp2);;
                tmp = p1[8];
                tmp2 = p2[8];
                tmp++;
                tmp2 += tmp;
                tmp = (cur_word >> 16) & 0xFF;
                sad = INTERP1_SUB_SAD(sad, tmp, tmp2);;
                tmp = p1[4];
                tmp2 = p2[4];
                tmp++;
                tmp2 += tmp;
                tmp = (cur_word >> 8) & 0xFF;
                sad = INTERP1_SUB_SAD(sad, tmp, tmp2);;
                tmp = p1[0];
                p1 += refwx4;
                tmp2 = p2[0];
                p2 += refwx4;
                tmp++;
                tmp2 += tmp;
                tmp = (cur_word & 0xFF);
                sad = INTERP1_SUB_SAD(sad, tmp, tmp2);;
            }
            while (--j);

            NUM_SAD_HP_MB();
            sadstar += madstar;
            if (sad > sadstar - nrmlz_th[i] || sad > (Int)((ULong)dmin_rx >> 16))
            {
                return 65536;
            }
        }

        return sad;
    }

    Int SAD_MB_HP_HTFMxh(UChar *ref, UChar *blk, Int dmin_rx, void *extra_info)
    {
        Int i, j;
        Int sad = 0, tmp, tmp2;
        UChar *p1;
        Int rx = dmin_rx & 0xFFFF;
        Int refwx4 = rx << 2;
        Int sadstar = 0, madstar;
        Int *nrmlz_th = (Int*) extra_info;
        Int *offsetRef = nrmlz_th + 32;
        ULong cur_word;

        madstar = (ULong)dmin_rx >> 20;

        NUM_SAD_HP_MB_CALL();

        blk -= 4;

        for (i = 0; i < 16; i++) /* 16 stages */
        {
            p1 = ref + offsetRef[i];

            j = 4;/* 4 lines */
            do
            {
                cur_word = *((ULong*)(blk += 4));
                tmp = p1[12];
                tmp2 = p1[13];
                tmp++;
                tmp2 += tmp;
                tmp = (cur_word >> 24) & 0xFF;
                sad = INTERP1_SUB_SAD(sad, tmp, tmp2);;
                tmp = p1[8];
                tmp2 = p1[9];
                tmp++;
                tmp2 += tmp;
                tmp = (cur_word >> 16) & 0xFF;
                sad = INTERP1_SUB_SAD(sad, tmp, tmp2);;
                tmp = p1[4];
                tmp2 = p1[5];
                tmp++;
                tmp2 += tmp;
                tmp = (cur_word >> 8) & 0xFF;
                sad = INTERP1_SUB_SAD(sad, tmp, tmp2);;
                tmp = p1[0];
                tmp2 = p1[1];
                p1 += refwx4;
                tmp++;
                tmp2 += tmp;
                tmp = (cur_word & 0xFF);
                sad = INTERP1_SUB_SAD(sad, tmp, tmp2);;
            }
            while (--j);

            NUM_SAD_HP_MB();

            sadstar += madstar;
            if (sad > sadstar - nrmlz_th[i] || sad > (Int)((ULong)dmin_rx >> 16))
            {
                return 65536;
            }
        }

        return sad;
    }

#endif /* HTFM */

#ifndef NO_INTER4V
    /*==================================================================
        Function:   SAD_Blk_HalfPel_C
        Date:       04/30/2001
        Purpose:    Compute SAD 16x16 between blk and ref in halfpel
                    resolution,
        Changes:
      ==================================================================*/
    /* One component is half-pel */
    Int SAD_Blk_HalfPel_C(UChar *ref, UChar *blk, Int dmin, Int width, Int rx, Int xh, Int yh, void *extra_info)
    {
        Int i, j;
        Int sad = 0;
        UChar *kk, *p1, *p2, *p3, *p4;
        Int temp;

        OSCL_UNUSED_ARG(extra_info);

        NUM_SAD_HP_BLK_CALL();

        if (xh && yh)
        {
            p1 = ref;
            p2 = ref + xh;
            p3 = ref + yh * rx;
            p4 = ref + yh * rx + xh;
            kk  = blk;

            for (i = 0; i < 8; i++)
            {
                for (j = 0; j < 8; j++)
                {

                    temp = ((p1[j] + p2[j] + p3[j] + p4[j] + 2) >> 2) - kk[j];
                    sad += PV_ABS(temp);
                }

                NUM_SAD_HP_BLK();

                if (sad > dmin)
                    return sad;

                p1 += rx;
                p3 += rx;
                p2 += rx;
                p4 += rx;
                kk += width;
            }
            return sad;
        }
        else
        {
            p1 = ref;
            p2 = ref + xh + yh * rx; /* either left/right or top/bottom pixel */

            kk  = blk;

            for (i = 0; i < 8; i++)
            {
                for (j = 0; j < 8; j++)
                {

                    temp = ((p1[j] + p2[j] + 1) >> 1) - kk[j];
                    sad += PV_ABS(temp);
                }

                NUM_SAD_HP_BLK();

                if (sad > dmin)
                    return sad;
                p1 += rx;
                p2 += rx;
                kk += width;
            }
            return sad;
        }
    }
#endif /* NO_INTER4V */

#ifdef __cplusplus
}
#endif



