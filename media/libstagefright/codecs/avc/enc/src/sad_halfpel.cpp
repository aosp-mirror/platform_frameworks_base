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
int AVCHalfPel1_SAD_MB(uint8 *ref,uint8 *blk,int dmin,int width,int ih,int jh)
int AVCHalfPel2_SAD_MB(uint8 *ref,uint8 *blk,int dmin,int width)
int AVCHalfPel1_SAD_Blk(uint8 *ref,uint8 *blk,int dmin,int width,int ih,int jh)
int AVCHalfPel2_SAD_Blk(uint8 *ref,uint8 *blk,int dmin,int width)

int AVCSAD_MB_HalfPel_C(uint8 *ref,uint8 *blk,int dmin,int width,int rx,int xh,int yh,void *extra_info)
int AVCSAD_MB_HP_HTFM_Collect(uint8 *ref,uint8 *blk,int dmin,int width,int rx,int xh,int yh,void *extra_info)
int AVCSAD_MB_HP_HTFM(uint8 *ref,uint8 *blk,int dmin,int width,int rx,int xh,int yh,void *extra_info)
int AVCSAD_Blk_HalfPel_C(uint8 *ref,uint8 *blk,int dmin,int width,int rx,int xh,int yh,void *extra_info)
*/

#include "avcenc_lib.h"
#include "sad_halfpel_inline.h"

#ifdef _SAD_STAT
uint32 num_sad_HP_MB = 0;
uint32 num_sad_HP_Blk = 0;
uint32 num_sad_HP_MB_call = 0;
uint32 num_sad_HP_Blk_call = 0;
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



/*===============================================================
    Function:   SAD_MB_HalfPel
    Date:       09/17/2000
    Purpose:    Compute the SAD on the half-pel resolution
    Input/Output:   hmem is assumed to be a pointer to the starting
                point of the search in the 33x33 matrix search region
    Changes:
    11/7/00:    implemented MMX
  ===============================================================*/
/*==================================================================
    Function:   AVCSAD_MB_HalfPel_C
    Date:       04/30/2001
    Purpose:    Compute SAD 16x16 between blk and ref in halfpel
                resolution,
    Changes:
  ==================================================================*/
/* One component is half-pel */
int AVCSAD_MB_HalfPel_Cxhyh(uint8 *ref, uint8 *blk, int dmin_rx, void *extra_info)
{
    (void)(extra_info);

    int i, j;
    int sad = 0;
    uint8 *kk, *p1, *p2, *p3, *p4;
//  int sumref=0;
    int temp;
    int rx = dmin_rx & 0xFFFF;

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
            sad += AVC_ABS(temp);
        }

        NUM_SAD_HP_MB();

        if (sad > (int)((uint32)dmin_rx >> 16))
            return sad;

        p1 += rx;
        p3 += rx;
        p2 += rx;
        p4 += rx;
    }
    return sad;
}

int AVCSAD_MB_HalfPel_Cyh(uint8 *ref, uint8 *blk, int dmin_rx, void *extra_info)
{
    (void)(extra_info);

    int i, j;
    int sad = 0;
    uint8 *kk, *p1, *p2;
//  int sumref=0;
    int temp;
    int rx = dmin_rx & 0xFFFF;

    NUM_SAD_HP_MB_CALL();

    p1 = ref;
    p2 = ref + rx; /* either left/right or top/bottom pixel */
    kk  = blk;

    for (i = 0; i < 16; i++)
    {
        for (j = 0; j < 16; j++)
        {

            temp = ((p1[j] + p2[j] + 1) >> 1) - *kk++;
            sad += AVC_ABS(temp);
        }

        NUM_SAD_HP_MB();

        if (sad > (int)((uint32)dmin_rx >> 16))
            return sad;
        p1 += rx;
        p2 += rx;
    }
    return sad;
}

int AVCSAD_MB_HalfPel_Cxh(uint8 *ref, uint8 *blk, int dmin_rx, void *extra_info)
{
    (void)(extra_info);

    int i, j;
    int sad = 0;
    uint8 *kk, *p1;
    int temp;
    int rx = dmin_rx & 0xFFFF;

    NUM_SAD_HP_MB_CALL();

    p1 = ref;
    kk  = blk;

    for (i = 0; i < 16; i++)
    {
        for (j = 0; j < 16; j++)
        {

            temp = ((p1[j] + p1[j+1] + 1) >> 1) - *kk++;
            sad += AVC_ABS(temp);
        }

        NUM_SAD_HP_MB();

        if (sad > (int)((uint32)dmin_rx >> 16))
            return sad;
        p1 += rx;
    }
    return sad;
}

#ifdef HTFM  /* HTFM with uniform subsampling implementation,  2/28/01 */

//Checheck here
int AVCAVCSAD_MB_HP_HTFM_Collectxhyh(uint8 *ref, uint8 *blk, int dmin_rx, void *extra_info)
{
    int i, j;
    int sad = 0;
    uint8 *p1, *p2;
    int rx = dmin_rx & 0xFFFF;
    int refwx4 = rx << 2;
    int saddata[16];      /* used when collecting flag (global) is on */
    int difmad, tmp, tmp2;
    int madstar;
    HTFM_Stat *htfm_stat = (HTFM_Stat*) extra_info;
    int *abs_dif_mad_avg = &(htfm_stat->abs_dif_mad_avg);
    UInt *countbreak = &(htfm_stat->countbreak);
    int *offsetRef = htfm_stat->offsetRef;
    uint32 cur_word;

    madstar = (uint32)dmin_rx >> 20;

    NUM_SAD_HP_MB_CALL();

    blk -= 4;

    for (i = 0; i < 16; i++) /* 16 stages */
    {
        p1 = ref + offsetRef[i];
        p2 = p1 + rx;

        j = 4;/* 4 lines */
        do
        {
            cur_word = *((uint32*)(blk += 4));
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
            if (sad > ((uint32)dmin_rx >> 16))
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

int AVCAVCSAD_MB_HP_HTFM_Collectyh(uint8 *ref, uint8 *blk, int dmin_rx, void *extra_info)
{
    int i, j;
    int sad = 0;
    uint8 *p1, *p2;
    int rx = dmin_rx & 0xFFFF;
    int refwx4 = rx << 2;
    int saddata[16];      /* used when collecting flag (global) is on */
    int difmad, tmp, tmp2;
    int madstar;
    HTFM_Stat *htfm_stat = (HTFM_Stat*) extra_info;
    int *abs_dif_mad_avg = &(htfm_stat->abs_dif_mad_avg);
    UInt *countbreak = &(htfm_stat->countbreak);
    int *offsetRef = htfm_stat->offsetRef;
    uint32 cur_word;

    madstar = (uint32)dmin_rx >> 20;

    NUM_SAD_HP_MB_CALL();

    blk -= 4;

    for (i = 0; i < 16; i++) /* 16 stages */
    {
        p1 = ref + offsetRef[i];
        p2 = p1 + rx;
        j = 4;
        do
        {
            cur_word = *((uint32*)(blk += 4));
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
            if (sad > ((uint32)dmin_rx >> 16))
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

int AVCAVCSAD_MB_HP_HTFM_Collectxh(uint8 *ref, uint8 *blk, int dmin_rx, void *extra_info)
{
    int i, j;
    int sad = 0;
    uint8 *p1;
    int rx = dmin_rx & 0xFFFF;
    int refwx4 = rx << 2;
    int saddata[16];      /* used when collecting flag (global) is on */
    int difmad, tmp, tmp2;
    int madstar;
    HTFM_Stat *htfm_stat = (HTFM_Stat*) extra_info;
    int *abs_dif_mad_avg = &(htfm_stat->abs_dif_mad_avg);
    UInt *countbreak = &(htfm_stat->countbreak);
    int *offsetRef = htfm_stat->offsetRef;
    uint32 cur_word;

    madstar = (uint32)dmin_rx >> 20;

    NUM_SAD_HP_MB_CALL();

    blk -= 4;

    for (i = 0; i < 16; i++) /* 16 stages */
    {
        p1 = ref + offsetRef[i];

        j = 4; /* 4 lines */
        do
        {
            cur_word = *((uint32*)(blk += 4));
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
            if (sad > ((uint32)dmin_rx >> 16))
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

int AVCSAD_MB_HP_HTFMxhyh(uint8 *ref, uint8 *blk, int dmin_rx, void *extra_info)
{
    int i, j;
    int sad = 0, tmp, tmp2;
    uint8 *p1, *p2;
    int rx = dmin_rx & 0xFFFF;
    int refwx4 = rx << 2;
    int sadstar = 0, madstar;
    int *nrmlz_th = (int*) extra_info;
    int *offsetRef = nrmlz_th + 32;
    uint32 cur_word;

    madstar = (uint32)dmin_rx >> 20;

    NUM_SAD_HP_MB_CALL();

    blk -= 4;

    for (i = 0; i < 16; i++) /* 16 stages */
    {
        p1 = ref + offsetRef[i];
        p2 = p1 + rx;

        j = 4; /* 4 lines */
        do
        {
            cur_word = *((uint32*)(blk += 4));
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
        if (sad > sadstar - nrmlz_th[i] || sad > ((uint32)dmin_rx >> 16))
        {
            return 65536;
        }
    }

    return sad;
}

int AVCSAD_MB_HP_HTFMyh(uint8 *ref, uint8 *blk, int dmin_rx, void *extra_info)
{
    int i, j;
    int sad = 0, tmp, tmp2;
    uint8 *p1, *p2;
    int rx = dmin_rx & 0xFFFF;
    int refwx4 = rx << 2;
    int sadstar = 0, madstar;
    int *nrmlz_th = (int*) extra_info;
    int *offsetRef = nrmlz_th + 32;
    uint32 cur_word;

    madstar = (uint32)dmin_rx >> 20;

    NUM_SAD_HP_MB_CALL();

    blk -= 4;

    for (i = 0; i < 16; i++) /* 16 stages */
    {
        p1 = ref + offsetRef[i];
        p2 = p1 + rx;
        j = 4;
        do
        {
            cur_word = *((uint32*)(blk += 4));
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
        if (sad > sadstar - nrmlz_th[i] || sad > ((uint32)dmin_rx >> 16))
        {
            return 65536;
        }
    }

    return sad;
}

int AVCSAD_MB_HP_HTFMxh(uint8 *ref, uint8 *blk, int dmin_rx, void *extra_info)
{
    int i, j;
    int sad = 0, tmp, tmp2;
    uint8 *p1;
    int rx = dmin_rx & 0xFFFF;
    int refwx4 = rx << 2;
    int sadstar = 0, madstar;
    int *nrmlz_th = (int*) extra_info;
    int *offsetRef = nrmlz_th + 32;
    uint32 cur_word;

    madstar = (uint32)dmin_rx >> 20;

    NUM_SAD_HP_MB_CALL();

    blk -= 4;

    for (i = 0; i < 16; i++) /* 16 stages */
    {
        p1 = ref + offsetRef[i];

        j = 4;/* 4 lines */
        do
        {
            cur_word = *((uint32*)(blk += 4));
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
        if (sad > sadstar - nrmlz_th[i] || sad > ((uint32)dmin_rx >> 16))
        {
            return 65536;
        }
    }

    return sad;
}

#endif /* HTFM */





