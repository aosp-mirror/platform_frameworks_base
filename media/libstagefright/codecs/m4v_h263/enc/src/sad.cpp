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
#include "mp4lib_int.h"

#include "sad_inline.h"

#define Cached_lx 176

#ifdef _SAD_STAT
ULong num_sad_MB = 0;
ULong num_sad_Blk = 0;
ULong num_sad_MB_call = 0;
ULong num_sad_Blk_call = 0;

#define NUM_SAD_MB_CALL()       num_sad_MB_call++
#define NUM_SAD_MB()            num_sad_MB++
#define NUM_SAD_BLK_CALL()      num_sad_Blk_call++
#define NUM_SAD_BLK()           num_sad_Blk++

#else

#define NUM_SAD_MB_CALL()
#define NUM_SAD_MB()
#define NUM_SAD_BLK_CALL()
#define NUM_SAD_BLK()

#endif


/* consist of
Int SAD_Macroblock_C(UChar *ref,UChar *blk,Int dmin,Int lx,void *extra_info)
Int SAD_MB_HTFM_Collect(UChar *ref,UChar *blk,Int dmin,Int lx,void *extra_info)
Int SAD_MB_HTFM(UChar *ref,UChar *blk,Int dmin,Int lx,void *extra_info)
Int SAD_Block_C(UChar *ref,UChar *blk,Int dmin,Int lx,void *extra_info)
Int SAD_Blk_PADDING(UChar *ref,UChar *cur,Int dmin,Int lx,void *extra_info)
Int SAD_MB_PADDING(UChar *ref,UChar *cur,Int dmin,Int lx,void *extra_info)
Int SAD_MB_PAD1(UChar *ref,UChar *cur,Int dmin,Int lx,Int *rep);
Int SAD_MB_PADDING_HTFM_Collect(UChar *ref,UChar *cur,Int dmin,Int lx,void *extra_info)
Int SAD_MB_PADDING_HTFM(UChar *ref,UChar *cur,Int dmin,Int lx,void *vptr)
*/


#ifdef __cplusplus
extern "C"
{
#endif

    Int SAD_MB_PAD1(UChar *ref, UChar *cur, Int dmin, Int lx, Int *rep);


    /*==================================================================
        Function:   SAD_Macroblock
        Date:       09/07/2000
        Purpose:    Compute SAD 16x16 between blk and ref.
        To do:      Uniform subsampling will be inserted later!
                    Hypothesis Testing Fast Matching to be used later!
        Changes:
    11/7/00:     implemented MMX
    1/24/01:     implemented SSE
    ==================================================================*/
    /********** C ************/
    Int SAD_Macroblock_C(UChar *ref, UChar *blk, Int dmin_lx, void *extra_info)
    {
        int32 x10;
        Int dmin = (ULong)dmin_lx >> 16;
        Int lx = dmin_lx & 0xFFFF;

        OSCL_UNUSED_ARG(extra_info);

        NUM_SAD_MB_CALL();

        x10 = simd_sad_mb(ref, blk, dmin, lx);

        return x10;
    }

#ifdef HTFM   /* HTFM with uniform subsampling implementation, 2/28/01 */
    /*===============================================================
        Function:   SAD_MB_HTFM_Collect and SAD_MB_HTFM
        Date:       3/2/1
        Purpose:    Compute the SAD on a 16x16 block using
                    uniform subsampling and hypothesis testing fast matching
                    for early dropout. SAD_MB_HP_HTFM_Collect is to collect
                    the statistics to compute the thresholds to be used in
                    SAD_MB_HP_HTFM.
        Input/Output:
        Changes:
      ===============================================================*/

    Int SAD_MB_HTFM_Collect(UChar *ref, UChar *blk, Int dmin_lx, void *extra_info)
    {
        Int i;
        Int sad = 0;
        UChar *p1;
        Int lx4 = (dmin_lx << 2) & 0x3FFFC;
        ULong cur_word;
        Int saddata[16], tmp, tmp2;    /* used when collecting flag (global) is on */
        Int difmad;
        HTFM_Stat *htfm_stat = (HTFM_Stat*) extra_info;
        Int *abs_dif_mad_avg = &(htfm_stat->abs_dif_mad_avg);
        UInt *countbreak = &(htfm_stat->countbreak);
        Int *offsetRef = htfm_stat->offsetRef;

        NUM_SAD_MB_CALL();

        blk -= 4;
        for (i = 0; i < 16; i++)
        {
            p1 = ref + offsetRef[i];
            cur_word = *((ULong*)(blk += 4));
            tmp = p1[12];
            tmp2 = (cur_word >> 24) & 0xFF;
            sad = SUB_SAD(sad, tmp, tmp2);
            tmp = p1[8];
            tmp2 = (cur_word >> 16) & 0xFF;
            sad = SUB_SAD(sad, tmp, tmp2);
            tmp = p1[4];
            tmp2 = (cur_word >> 8) & 0xFF;
            sad = SUB_SAD(sad, tmp, tmp2);
            tmp = p1[0];
            p1 += lx4;
            tmp2 = (cur_word & 0xFF);
            sad = SUB_SAD(sad, tmp, tmp2);

            cur_word = *((ULong*)(blk += 4));
            tmp = p1[12];
            tmp2 = (cur_word >> 24) & 0xFF;
            sad = SUB_SAD(sad, tmp, tmp2);
            tmp = p1[8];
            tmp2 = (cur_word >> 16) & 0xFF;
            sad = SUB_SAD(sad, tmp, tmp2);
            tmp = p1[4];
            tmp2 = (cur_word >> 8) & 0xFF;
            sad = SUB_SAD(sad, tmp, tmp2);
            tmp = p1[0];
            p1 += lx4;
            tmp2 = (cur_word & 0xFF);
            sad = SUB_SAD(sad, tmp, tmp2);

            cur_word = *((ULong*)(blk += 4));
            tmp = p1[12];
            tmp2 = (cur_word >> 24) & 0xFF;
            sad = SUB_SAD(sad, tmp, tmp2);
            tmp = p1[8];
            tmp2 = (cur_word >> 16) & 0xFF;
            sad = SUB_SAD(sad, tmp, tmp2);
            tmp = p1[4];
            tmp2 = (cur_word >> 8) & 0xFF;
            sad = SUB_SAD(sad, tmp, tmp2);
            tmp = p1[0];
            p1 += lx4;
            tmp2 = (cur_word & 0xFF);
            sad = SUB_SAD(sad, tmp, tmp2);

            cur_word = *((ULong*)(blk += 4));
            tmp = p1[12];
            tmp2 = (cur_word >> 24) & 0xFF;
            sad = SUB_SAD(sad, tmp, tmp2);
            tmp = p1[8];
            tmp2 = (cur_word >> 16) & 0xFF;
            sad = SUB_SAD(sad, tmp, tmp2);
            tmp = p1[4];
            tmp2 = (cur_word >> 8) & 0xFF;
            sad = SUB_SAD(sad, tmp, tmp2);
            tmp = p1[0];
            p1 += lx4;
            tmp2 = (cur_word & 0xFF);
            sad = SUB_SAD(sad, tmp, tmp2);

            NUM_SAD_MB();

            saddata[i] = sad;

            if (i > 0)
            {
                if ((ULong)sad > ((ULong)dmin_lx >> 16))
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

    Int SAD_MB_HTFM(UChar *ref, UChar *blk, Int dmin_lx, void *extra_info)
    {
        Int sad = 0;
        UChar *p1;

        Int i;
        Int tmp, tmp2;
        Int lx4 = (dmin_lx << 2) & 0x3FFFC;
        Int sadstar = 0, madstar;
        Int *nrmlz_th = (Int*) extra_info;
        Int *offsetRef = (Int*) extra_info + 32;
        ULong cur_word;

        madstar = (ULong)dmin_lx >> 20;

        NUM_SAD_MB_CALL();

        blk -= 4;
        for (i = 0; i < 16; i++)
        {
            p1 = ref + offsetRef[i];
            cur_word = *((ULong*)(blk += 4));
            tmp = p1[12];
            tmp2 = (cur_word >> 24) & 0xFF;
            sad = SUB_SAD(sad, tmp, tmp2);
            tmp = p1[8];
            tmp2 = (cur_word >> 16) & 0xFF;
            sad = SUB_SAD(sad, tmp, tmp2);
            tmp = p1[4];
            tmp2 = (cur_word >> 8) & 0xFF;
            sad = SUB_SAD(sad, tmp, tmp2);
            tmp = p1[0];
            p1 += lx4;
            tmp2 = (cur_word & 0xFF);
            sad = SUB_SAD(sad, tmp, tmp2);

            cur_word = *((ULong*)(blk += 4));
            tmp = p1[12];
            tmp2 = (cur_word >> 24) & 0xFF;
            sad = SUB_SAD(sad, tmp, tmp2);
            tmp = p1[8];
            tmp2 = (cur_word >> 16) & 0xFF;
            sad = SUB_SAD(sad, tmp, tmp2);
            tmp = p1[4];
            tmp2 = (cur_word >> 8) & 0xFF;
            sad = SUB_SAD(sad, tmp, tmp2);
            tmp = p1[0];
            p1 += lx4;
            tmp2 = (cur_word & 0xFF);
            sad = SUB_SAD(sad, tmp, tmp2);

            cur_word = *((ULong*)(blk += 4));
            tmp = p1[12];
            tmp2 = (cur_word >> 24) & 0xFF;
            sad = SUB_SAD(sad, tmp, tmp2);
            tmp = p1[8];
            tmp2 = (cur_word >> 16) & 0xFF;
            sad = SUB_SAD(sad, tmp, tmp2);
            tmp = p1[4];
            tmp2 = (cur_word >> 8) & 0xFF;
            sad = SUB_SAD(sad, tmp, tmp2);
            tmp = p1[0];
            p1 += lx4;
            tmp2 = (cur_word & 0xFF);
            sad = SUB_SAD(sad, tmp, tmp2);

            cur_word = *((ULong*)(blk += 4));
            tmp = p1[12];
            tmp2 = (cur_word >> 24) & 0xFF;
            sad = SUB_SAD(sad, tmp, tmp2);
            tmp = p1[8];
            tmp2 = (cur_word >> 16) & 0xFF;
            sad = SUB_SAD(sad, tmp, tmp2);
            tmp = p1[4];
            tmp2 = (cur_word >> 8) & 0xFF;
            sad = SUB_SAD(sad, tmp, tmp2);
            tmp = p1[0];
            p1 += lx4;
            tmp2 = (cur_word & 0xFF);
            sad = SUB_SAD(sad, tmp, tmp2);

            NUM_SAD_MB();

            sadstar += madstar;
            if (((ULong)sad <= ((ULong)dmin_lx >> 16)) && (sad <= (sadstar - *nrmlz_th++)))
                ;
            else
                return 65536;
        }

        return sad;
    }
#endif /* HTFM */

#ifndef NO_INTER4V
    /*==================================================================
        Function:   SAD_Block
        Date:       09/07/2000
        Purpose:    Compute SAD 16x16 between blk and ref.
        To do:      Uniform subsampling will be inserted later!
                    Hypothesis Testing Fast Matching to be used later!
        Changes:
    11/7/00:     implemented MMX
    1/24/01:     implemented SSE
      ==================================================================*/
    /********** C ************/
    Int SAD_Block_C(UChar *ref, UChar *blk, Int dmin, Int lx, void *)
    {
        Int sad = 0;

        Int i;
        UChar *ii;
        Int *kk;
        Int tmp, tmp2, tmp3, mask = 0xFF;
        Int width = (lx - 32);

        NUM_SAD_BLK_CALL();

        ii = ref;
        kk  = (Int*)blk; /* assuming word-align for blk */
        for (i = 0; i < 8; i++)
        {
            tmp3 = kk[1];
            tmp = ii[7];
            tmp2 = (UInt)tmp3 >> 24;
            sad = SUB_SAD(sad, tmp, tmp2);
            tmp = ii[6];
            tmp2 = (tmp3 >> 16) & mask;
            sad = SUB_SAD(sad, tmp, tmp2);
            tmp = ii[5];
            tmp2 = (tmp3 >> 8) & mask;
            sad = SUB_SAD(sad, tmp, tmp2);
            tmp = ii[4];
            tmp2 = tmp3 & mask;
            sad = SUB_SAD(sad, tmp, tmp2);
            tmp3 = *kk;
            kk += (width >> 2);
            tmp = ii[3];
            tmp2 = (UInt)tmp3 >> 24;
            sad = SUB_SAD(sad, tmp, tmp2);
            tmp = ii[2];
            tmp2 = (tmp3 >> 16) & mask;
            sad = SUB_SAD(sad, tmp, tmp2);
            tmp = ii[1];
            tmp2 = (tmp3 >> 8) & mask;
            sad = SUB_SAD(sad, tmp, tmp2);
            tmp = *ii;
            ii += lx;
            tmp2 = tmp3 & mask;
            sad = SUB_SAD(sad, tmp, tmp2);

            NUM_SAD_BLK();

            if (sad > dmin)
                return sad;
        }

        return sad;
    }

#endif /* NO_INTER4V */

#ifdef __cplusplus
}
#endif



