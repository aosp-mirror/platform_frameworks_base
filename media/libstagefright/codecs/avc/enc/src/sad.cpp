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
#include "avcenc_lib.h"
#include "sad_inline.h"

#define Cached_lx 176

#ifdef _SAD_STAT
uint32 num_sad_MB = 0;
uint32 num_sad_Blk = 0;
uint32 num_sad_MB_call = 0;
uint32 num_sad_Blk_call = 0;

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
int AVCSAD_Macroblock_C(uint8 *ref,uint8 *blk,int dmin,int lx,void *extra_info)
int AVCSAD_MB_HTFM_Collect(uint8 *ref,uint8 *blk,int dmin,int lx,void *extra_info)
int AVCSAD_MB_HTFM(uint8 *ref,uint8 *blk,int dmin,int lx,void *extra_info)
*/


/*==================================================================
    Function:   SAD_Macroblock
    Date:       09/07/2000
    Purpose:    Compute SAD 16x16 between blk and ref.
    To do:      Uniform subsampling will be inserted later!
                Hypothesis Testing Fast Matching to be used later!
    Changes:
    11/7/00:    implemented MMX
    1/24/01:    implemented SSE
==================================================================*/
/********** C ************/
int AVCSAD_Macroblock_C(uint8 *ref, uint8 *blk, int dmin_lx, void *extra_info)
{
    (void)(extra_info);

    int32 x10;
    int dmin = (uint32)dmin_lx >> 16;
    int lx = dmin_lx & 0xFFFF;

    NUM_SAD_MB_CALL();

    x10 = simd_sad_mb(ref, blk, dmin, lx);

    return x10;
}

#ifdef HTFM   /* HTFM with uniform subsampling implementation 2/28/01 */
/*===============================================================
    Function:   AVCAVCSAD_MB_HTFM_Collect and AVCSAD_MB_HTFM
    Date:       3/2/1
    Purpose:    Compute the SAD on a 16x16 block using
                uniform subsampling and hypothesis testing fast matching
                for early dropout. SAD_MB_HP_HTFM_Collect is to collect
                the statistics to compute the thresholds to be used in
                SAD_MB_HP_HTFM.
    Input/Output:
    Changes:
  ===============================================================*/

int AVCAVCSAD_MB_HTFM_Collect(uint8 *ref, uint8 *blk, int dmin_lx, void *extra_info)
{
    int i;
    int sad = 0;
    uint8 *p1;
    int lx4 = (dmin_lx << 2) & 0x3FFFC;
    uint32 cur_word;
    int saddata[16], tmp, tmp2;    /* used when collecting flag (global) is on */
    int difmad;
    int madstar;
    HTFM_Stat *htfm_stat = (HTFM_Stat*) extra_info;
    int *abs_dif_mad_avg = &(htfm_stat->abs_dif_mad_avg);
    uint *countbreak = &(htfm_stat->countbreak);
    int *offsetRef = htfm_stat->offsetRef;

    madstar = (uint32)dmin_lx >> 20;

    NUM_SAD_MB_CALL();

    blk -= 4;
    for (i = 0; i < 16; i++)
    {
        p1 = ref + offsetRef[i];
        cur_word = *((uint32*)(blk += 4));
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

        cur_word = *((uint32*)(blk += 4));
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

        cur_word = *((uint32*)(blk += 4));
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

        cur_word = *((uint32*)(blk += 4));
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
            if ((uint32)sad > ((uint32)dmin_lx >> 16))
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

int AVCSAD_MB_HTFM(uint8 *ref, uint8 *blk, int dmin_lx, void *extra_info)
{
    int sad = 0;
    uint8 *p1;

    int i;
    int tmp, tmp2;
    int lx4 = (dmin_lx << 2) & 0x3FFFC;
    int sadstar = 0, madstar;
    int *nrmlz_th = (int*) extra_info;
    int *offsetRef = (int*) extra_info + 32;
    uint32 cur_word;

    madstar = (uint32)dmin_lx >> 20;

    NUM_SAD_MB_CALL();

    blk -= 4;
    for (i = 0; i < 16; i++)
    {
        p1 = ref + offsetRef[i];
        cur_word = *((uint32*)(blk += 4));
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

        cur_word = *((uint32*)(blk += 4));
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

        cur_word = *((uint32*)(blk += 4));
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

        cur_word = *((uint32*)(blk += 4));
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
        if (((uint32)sad <= ((uint32)dmin_lx >> 16)) && (sad <= (sadstar - *nrmlz_th++)))
            ;
        else
            return 65536;
    }

    return sad;
}
#endif /* HTFM */



