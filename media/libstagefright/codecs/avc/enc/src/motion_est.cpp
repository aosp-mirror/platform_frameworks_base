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

#define MIN_GOP     1   /* minimum size of GOP, 1/23/01, need to be tested */

#define DEFAULT_REF_IDX     0  /* always from the first frame in the reflist */

#define ALL_CAND_EQUAL  10  /*  any number greater than 5 will work */


/* from TMN 3.2 */
#define PREF_NULL_VEC 129   /* zero vector bias */
#define PREF_16_VEC 129     /* 1MV bias versus 4MVs*/
#define PREF_INTRA  3024//512       /* bias for INTRA coding */

const static int tab_exclude[9][9] =  // [last_loc][curr_loc]
{
    {0, 0, 0, 0, 0, 0, 0, 0, 0},
    {0, 0, 0, 0, 1, 1, 1, 0, 0},
    {0, 0, 0, 0, 1, 1, 1, 1, 1},
    {0, 0, 0, 0, 0, 0, 1, 1, 1},
    {0, 1, 1, 0, 0, 0, 1, 1, 1},
    {0, 1, 1, 0, 0, 0, 0, 0, 1},
    {0, 1, 1, 1, 1, 0, 0, 0, 1},
    {0, 0, 1, 1, 1, 0, 0, 0, 0},
    {0, 0, 1, 1, 1, 1, 1, 0, 0}
}; //to decide whether to continue or compute

const static int refine_next[8][2] =    /* [curr_k][increment] */
{
    {0, 0}, {2, 0}, {1, 1}, {0, 2}, { -1, 1}, { -2, 0}, { -1, -1}, {0, -2}
};

#ifdef _SAD_STAT
uint32 num_MB = 0;
uint32 num_cand = 0;
#endif

/************************************************************************/
#define TH_INTER_2  100  /* temporary for now */

//#define FIXED_INTERPRED_MODE  AVC_P16
#define FIXED_REF_IDX   0
#define FIXED_MVX 0
#define FIXED_MVY 0

// only use when AVC_P8 or AVC_P8ref0
#define FIXED_SUBMB_MODE    AVC_4x4
/*************************************************************************/

/* Initialize arrays necessary for motion search */
AVCEnc_Status InitMotionSearchModule(AVCHandle *avcHandle)
{
    AVCEncObject *encvid = (AVCEncObject*) avcHandle->AVCObject;
    AVCRateControl *rateCtrl = encvid->rateCtrl;
    int search_range = rateCtrl->mvRange;
    int number_of_subpel_positions = 4 * (2 * search_range + 3);
    int max_mv_bits, max_mvd;
    int temp_bits = 0;
    uint8 *mvbits;
    int bits, imax, imin, i;
    uint8* subpel_pred = (uint8*) encvid->subpel_pred; // all 16 sub-pel positions


    while (number_of_subpel_positions > 0)
    {
        temp_bits++;
        number_of_subpel_positions >>= 1;
    }

    max_mv_bits = 3 + 2 * temp_bits;
    max_mvd  = (1 << (max_mv_bits >> 1)) - 1;

    encvid->mvbits_array = (uint8*) avcHandle->CBAVC_Malloc(encvid->avcHandle->userData,
                           sizeof(uint8) * (2 * max_mvd + 1), DEFAULT_ATTR);

    if (encvid->mvbits_array == NULL)
    {
        return AVCENC_MEMORY_FAIL;
    }

    mvbits = encvid->mvbits  = encvid->mvbits_array + max_mvd;

    mvbits[0] = 1;
    for (bits = 3; bits <= max_mv_bits; bits += 2)
    {
        imax = 1    << (bits >> 1);
        imin = imax >> 1;

        for (i = imin; i < imax; i++)   mvbits[-i] = mvbits[i] = bits;
    }

    /* initialize half-pel search */
    encvid->hpel_cand[0] = subpel_pred + REF_CENTER;
    encvid->hpel_cand[1] = subpel_pred + V2Q_H0Q * SUBPEL_PRED_BLK_SIZE + 1 ;
    encvid->hpel_cand[2] = subpel_pred + V2Q_H2Q * SUBPEL_PRED_BLK_SIZE + 1;
    encvid->hpel_cand[3] = subpel_pred + V0Q_H2Q * SUBPEL_PRED_BLK_SIZE + 25;
    encvid->hpel_cand[4] = subpel_pred + V2Q_H2Q * SUBPEL_PRED_BLK_SIZE + 25;
    encvid->hpel_cand[5] = subpel_pred + V2Q_H0Q * SUBPEL_PRED_BLK_SIZE + 25;
    encvid->hpel_cand[6] = subpel_pred + V2Q_H2Q * SUBPEL_PRED_BLK_SIZE + 24;
    encvid->hpel_cand[7] = subpel_pred + V0Q_H2Q * SUBPEL_PRED_BLK_SIZE + 24;
    encvid->hpel_cand[8] = subpel_pred + V2Q_H2Q * SUBPEL_PRED_BLK_SIZE;

    /* For quarter-pel interpolation around best half-pel result */

    encvid->bilin_base[0][0] = subpel_pred + V2Q_H2Q * SUBPEL_PRED_BLK_SIZE;
    encvid->bilin_base[0][1] = subpel_pred + V2Q_H0Q * SUBPEL_PRED_BLK_SIZE + 1;
    encvid->bilin_base[0][2] = subpel_pred + V0Q_H2Q * SUBPEL_PRED_BLK_SIZE + 24;
    encvid->bilin_base[0][3] = subpel_pred + REF_CENTER;


    encvid->bilin_base[1][0] = subpel_pred + V0Q_H2Q * SUBPEL_PRED_BLK_SIZE;
    encvid->bilin_base[1][1] = subpel_pred + REF_CENTER - 24;
    encvid->bilin_base[1][2] = subpel_pred + V2Q_H2Q * SUBPEL_PRED_BLK_SIZE;
    encvid->bilin_base[1][3] = subpel_pred + V2Q_H0Q * SUBPEL_PRED_BLK_SIZE + 1;

    encvid->bilin_base[2][0] = subpel_pred + REF_CENTER - 24;
    encvid->bilin_base[2][1] = subpel_pred + V0Q_H2Q * SUBPEL_PRED_BLK_SIZE + 1;
    encvid->bilin_base[2][2] = subpel_pred + V2Q_H0Q * SUBPEL_PRED_BLK_SIZE + 1;
    encvid->bilin_base[2][3] = subpel_pred + V2Q_H2Q * SUBPEL_PRED_BLK_SIZE + 1;

    encvid->bilin_base[3][0] = subpel_pred + V2Q_H0Q * SUBPEL_PRED_BLK_SIZE + 1;
    encvid->bilin_base[3][1] = subpel_pred + V2Q_H2Q * SUBPEL_PRED_BLK_SIZE + 1;
    encvid->bilin_base[3][2] = subpel_pred + REF_CENTER;
    encvid->bilin_base[3][3] = subpel_pred + V0Q_H2Q * SUBPEL_PRED_BLK_SIZE + 25;

    encvid->bilin_base[4][0] = subpel_pred + REF_CENTER;
    encvid->bilin_base[4][1] = subpel_pred + V0Q_H2Q * SUBPEL_PRED_BLK_SIZE + 25;
    encvid->bilin_base[4][2] = subpel_pred + V2Q_H0Q * SUBPEL_PRED_BLK_SIZE + 25;
    encvid->bilin_base[4][3] = subpel_pred + V2Q_H2Q * SUBPEL_PRED_BLK_SIZE + 25;

    encvid->bilin_base[5][0] = subpel_pred + V0Q_H2Q * SUBPEL_PRED_BLK_SIZE + 24;
    encvid->bilin_base[5][1] = subpel_pred + REF_CENTER;
    encvid->bilin_base[5][2] = subpel_pred + V2Q_H2Q * SUBPEL_PRED_BLK_SIZE + 24;
    encvid->bilin_base[5][3] = subpel_pred + V2Q_H0Q * SUBPEL_PRED_BLK_SIZE + 25;

    encvid->bilin_base[6][0] = subpel_pred + REF_CENTER - 1;
    encvid->bilin_base[6][1] = subpel_pred + V0Q_H2Q * SUBPEL_PRED_BLK_SIZE + 24;
    encvid->bilin_base[6][2] = subpel_pred + V2Q_H0Q * SUBPEL_PRED_BLK_SIZE + 24;
    encvid->bilin_base[6][3] = subpel_pred + V2Q_H2Q * SUBPEL_PRED_BLK_SIZE + 24;

    encvid->bilin_base[7][0] = subpel_pred + V2Q_H0Q * SUBPEL_PRED_BLK_SIZE;
    encvid->bilin_base[7][1] = subpel_pred + V2Q_H2Q * SUBPEL_PRED_BLK_SIZE;
    encvid->bilin_base[7][2] = subpel_pred + REF_CENTER - 1;
    encvid->bilin_base[7][3] = subpel_pred + V0Q_H2Q * SUBPEL_PRED_BLK_SIZE + 24;

    encvid->bilin_base[8][0] = subpel_pred + REF_CENTER - 25;
    encvid->bilin_base[8][1] = subpel_pred + V0Q_H2Q * SUBPEL_PRED_BLK_SIZE;
    encvid->bilin_base[8][2] = subpel_pred + V2Q_H0Q * SUBPEL_PRED_BLK_SIZE;
    encvid->bilin_base[8][3] = subpel_pred + V2Q_H2Q * SUBPEL_PRED_BLK_SIZE;


    return AVCENC_SUCCESS;
}

/* Clean-up memory */
void CleanMotionSearchModule(AVCHandle *avcHandle)
{
    AVCEncObject *encvid = (AVCEncObject*) avcHandle->AVCObject;

    if (encvid->mvbits_array)
    {
        avcHandle->CBAVC_Free(avcHandle->userData, (int)(encvid->mvbits_array));
        encvid->mvbits = NULL;
    }

    return ;
}


bool IntraDecisionABE(int *min_cost, uint8 *cur, int pitch, bool ave)
{
    int j;
    uint8 *out;
    int temp, SBE;
    OsclFloat ABE;
    bool intra = true;

    SBE = 0;
    /* top neighbor */
    out = cur - pitch;
    for (j = 0; j < 16; j++)
    {
        temp = out[j] - cur[j];
        SBE += ((temp >= 0) ? temp : -temp);
    }

    /* left neighbor */
    out = cur - 1;
    out -= pitch;
    cur -= pitch;
    for (j = 0; j < 16; j++)
    {
        temp = *(out += pitch) - *(cur += pitch);
        SBE += ((temp >= 0) ? temp : -temp);
    }

    /* compare mincost/384 and SBE/64 */
    ABE = SBE / 32.0; //ABE = SBE/64.0; //
    if (ABE >= *min_cost / 256.0) //if( ABE*0.8 >= min_cost/384.0) //
    {
        intra = false; // no possibility of intra, just use inter
    }
    else
    {
        if (ave == true)
        {
            *min_cost = (*min_cost + (int)(SBE * 8)) >> 1; // possibility of intra, averaging the cost
        }
        else
        {
            *min_cost = (int)(SBE * 8);
        }
    }

    return intra;
}

/******* main function for macroblock prediction for the entire frame ***/
/* if turns out to be IDR frame, set video->nal_unit_type to AVC_NALTYPE_IDR */
void AVCMotionEstimation(AVCEncObject *encvid)
{
    AVCCommonObj *video = encvid->common;
    int slice_type = video->slice_type;
    AVCFrameIO *currInput = encvid->currInput;
    AVCPictureData *refPic = video->RefPicList0[0];
    int i, j, k;
    int mbwidth = video->PicWidthInMbs;
    int mbheight = video->PicHeightInMbs;
    int totalMB = video->PicSizeInMbs;
    int pitch = currInput->pitch;
    AVCMacroblock *currMB, *mblock = video->mblock;
    AVCMV *mot_mb_16x16, *mot16x16 = encvid->mot16x16;
    // AVCMV *mot_mb_16x8, *mot_mb_8x16, *mot_mb_8x8, etc;
    AVCRateControl *rateCtrl = encvid->rateCtrl;
    uint8 *intraSearch = encvid->intraSearch;
    uint FS_en = encvid->fullsearch_enable;

    int NumIntraSearch, start_i, numLoop, incr_i;
    int mbnum, offset;
    uint8 *cur, *best_cand[5];
    int totalSAD = 0;   /* average SAD for rate control */
    int type_pred;
    int abe_cost;

#ifdef HTFM
    /***** HYPOTHESIS TESTING ********/  /* 2/28/01 */
    int collect = 0;
    HTFM_Stat htfm_stat;
    double newvar[16];
    double exp_lamda[15];
    /*********************************/
#endif
    int hp_guess = 0;
    uint32 mv_uint32;

    offset = 0;

    if (slice_type == AVC_I_SLICE)
    {
        /* cannot do I16 prediction here because it needs full decoding. */
        for (i = 0; i < totalMB; i++)
        {
            encvid->min_cost[i] = 0x7FFFFFFF;  /* max value for int */
        }

        memset(intraSearch, 1, sizeof(uint8)*totalMB);

        encvid->firstIntraRefreshMBIndx = 0; /* reset this */

        return ;
    }
    else   // P_SLICE
    {
        for (i = 0; i < totalMB; i++)
        {
            mblock[i].mb_intra = 0;
        }
        memset(intraSearch, 1, sizeof(uint8)*totalMB);
    }

    if (refPic->padded == 0)
    {
        AVCPaddingEdge(refPic);
        refPic->padded = 1;
    }
    /* Random INTRA update */
    if (rateCtrl->intraMBRate)
    {
        AVCRasterIntraUpdate(encvid, mblock, totalMB, rateCtrl->intraMBRate);
    }

    encvid->sad_extra_info = NULL;
#ifdef HTFM
    /***** HYPOTHESIS TESTING ********/
    InitHTFM(video, &htfm_stat, newvar, &collect);
    /*********************************/
#endif

    if ((rateCtrl->scdEnable == 1)
            && ((rateCtrl->frame_rate < 5.0) || (video->sliceHdr->frame_num > MIN_GOP)))
        /* do not try to detect a new scene if low frame rate and too close to previous I-frame */
    {
        incr_i = 2;
        numLoop = 2;
        start_i = 1;
        type_pred = 0; /* for initial candidate selection */
    }
    else
    {
        incr_i = 1;
        numLoop = 1;
        start_i = 0;
        type_pred = 2;
    }

    /* First pass, loop thru half the macroblock */
    /* determine scene change */
    /* Second pass, for the rest of macroblocks */
    NumIntraSearch = 0; // to be intra searched in the encoding loop.
    while (numLoop--)
    {
        for (j = 0; j < mbheight; j++)
        {
            if (incr_i > 1)
                start_i = (start_i == 0 ? 1 : 0) ; /* toggle 0 and 1 */

            offset = pitch * (j << 4) + (start_i << 4);

            mbnum = j * mbwidth + start_i;

            for (i = start_i; i < mbwidth; i += incr_i)
            {
                video->mbNum = mbnum;
                video->currMB = currMB = mblock + mbnum;
                mot_mb_16x16 = mot16x16 + mbnum;

                cur = currInput->YCbCr[0] + offset;

                if (currMB->mb_intra == 0) /* for INTER mode */
                {
#if defined(HTFM)
                    HTFMPrepareCurMB_AVC(encvid, &htfm_stat, cur, pitch);
#else
                    AVCPrepareCurMB(encvid, cur, pitch);
#endif
                    /************************************************************/
                    /******** full-pel 1MV search **********************/

                    AVCMBMotionSearch(encvid, cur, best_cand, i << 4, j << 4, type_pred,
                                      FS_en, &hp_guess);

                    abe_cost = encvid->min_cost[mbnum] = mot_mb_16x16->sad;

                    /* set mbMode and MVs */
                    currMB->mbMode = AVC_P16;
                    currMB->MBPartPredMode[0][0] = AVC_Pred_L0;
                    mv_uint32 = ((mot_mb_16x16->y) << 16) | ((mot_mb_16x16->x) & 0xffff);
                    for (k = 0; k < 32; k += 2)
                    {
                        currMB->mvL0[k>>1] = mv_uint32;
                    }

                    /* make a decision whether it should be tested for intra or not */
                    if (i != mbwidth - 1 && j != mbheight - 1 && i != 0 && j != 0)
                    {
                        if (false == IntraDecisionABE(&abe_cost, cur, pitch, true))
                        {
                            intraSearch[mbnum] = 0;
                        }
                        else
                        {
                            NumIntraSearch++;
                            rateCtrl->MADofMB[mbnum] = abe_cost;
                        }
                    }
                    else // boundary MBs, always do intra search
                    {
                        NumIntraSearch++;
                    }

                    totalSAD += (int) rateCtrl->MADofMB[mbnum];//mot_mb_16x16->sad;
                }
                else    /* INTRA update, use for prediction */
                {
                    mot_mb_16x16[0].x = mot_mb_16x16[0].y = 0;

                    /* reset all other MVs to zero */
                    /* mot_mb_16x8, mot_mb_8x16, mot_mb_8x8, etc. */
                    abe_cost = encvid->min_cost[mbnum] = 0x7FFFFFFF;  /* max value for int */

                    if (i != mbwidth - 1 && j != mbheight - 1 && i != 0 && j != 0)
                    {
                        IntraDecisionABE(&abe_cost, cur, pitch, false);

                        rateCtrl->MADofMB[mbnum] = abe_cost;
                        totalSAD += abe_cost;
                    }

                    NumIntraSearch++ ;
                    /* cannot do I16 prediction here because it needs full decoding. */
                    // intraSearch[mbnum] = 1;

                }

                mbnum += incr_i;
                offset += (incr_i << 4);

            } /* for i */
        } /* for j */

        /* since we cannot do intra/inter decision here, the SCD has to be
        based on other criteria such as motion vectors coherency or the SAD */
        if (incr_i > 1 && numLoop) /* scene change on and first loop */
        {
            //if(NumIntraSearch > ((totalMB>>3)<<1) + (totalMB>>3)) /* 75% of 50%MBs */
            if (NumIntraSearch*99 > (48*totalMB)) /* 20% of 50%MBs */
                /* need to do more investigation about this threshold since the NumIntraSearch
                only show potential intra MBs, not the actual one */
            {
                /* we can choose to just encode I_SLICE without IDR */
                //video->nal_unit_type = AVC_NALTYPE_IDR;
                video->nal_unit_type = AVC_NALTYPE_SLICE;
                video->sliceHdr->slice_type = AVC_I_ALL_SLICE;
                video->slice_type = AVC_I_SLICE;
                memset(intraSearch, 1, sizeof(uint8)*totalMB);
                i = totalMB;
                while (i--)
                {
                    mblock[i].mb_intra = 1;
                    encvid->min_cost[i] = 0x7FFFFFFF;  /* max value for int */
                }

                rateCtrl->totalSAD = totalSAD * 2;  /* SAD */

                return ;
            }
        }
        /******** no scene change, continue motion search **********************/
        start_i = 0;
        type_pred++; /* second pass */
    }

    rateCtrl->totalSAD = totalSAD;  /* SAD */

#ifdef HTFM
    /***** HYPOTHESIS TESTING ********/
    if (collect)
    {
        collect = 0;
        UpdateHTFM(encvid, newvar, exp_lamda, &htfm_stat);
    }
    /*********************************/
#endif

    return ;
}

/*=====================================================================
    Function:   PaddingEdge
    Date:       09/16/2000
    Purpose:    Pad edge of a Vop
=====================================================================*/

void  AVCPaddingEdge(AVCPictureData *refPic)
{
    uint8 *src, *dst;
    int i;
    int pitch, width, height;
    uint32 temp1, temp2;

    width = refPic->width;
    height = refPic->height;
    pitch = refPic->pitch;

    /* pad top */
    src = refPic->Sl;

    temp1 = *src; /* top-left corner */
    temp2 = src[width-1]; /* top-right corner */
    temp1 |= (temp1 << 8);
    temp1 |= (temp1 << 16);
    temp2 |= (temp2 << 8);
    temp2 |= (temp2 << 16);

    dst = src - (pitch << 4);

    *((uint32*)(dst - 16)) = temp1;
    *((uint32*)(dst - 12)) = temp1;
    *((uint32*)(dst - 8)) = temp1;
    *((uint32*)(dst - 4)) = temp1;

    memcpy(dst, src, width);

    *((uint32*)(dst += width)) = temp2;
    *((uint32*)(dst + 4)) = temp2;
    *((uint32*)(dst + 8)) = temp2;
    *((uint32*)(dst + 12)) = temp2;

    dst = dst - width - 16;

    i = 15;
    while (i--)
    {
        memcpy(dst + pitch, dst, pitch);
        dst += pitch;
    }

    /* pad sides */
    dst += (pitch + 16);
    src = dst;
    i = height;
    while (i--)
    {
        temp1 = *src;
        temp2 = src[width-1];
        temp1 |= (temp1 << 8);
        temp1 |= (temp1 << 16);
        temp2 |= (temp2 << 8);
        temp2 |= (temp2 << 16);

        *((uint32*)(dst - 16)) = temp1;
        *((uint32*)(dst - 12)) = temp1;
        *((uint32*)(dst - 8)) = temp1;
        *((uint32*)(dst - 4)) = temp1;

        *((uint32*)(dst += width)) = temp2;
        *((uint32*)(dst + 4)) = temp2;
        *((uint32*)(dst + 8)) = temp2;
        *((uint32*)(dst + 12)) = temp2;

        src += pitch;
        dst = src;
    }

    /* pad bottom */
    dst -= 16;
    i = 16;
    while (i--)
    {
        memcpy(dst, dst - pitch, pitch);
        dst += pitch;
    }


    return ;
}

/*===========================================================================
    Function:   AVCRasterIntraUpdate
    Date:       2/26/01
    Purpose:    To raster-scan assign INTRA-update .
                N macroblocks are updated (also was programmable).
===========================================================================*/
void AVCRasterIntraUpdate(AVCEncObject *encvid, AVCMacroblock *mblock, int totalMB, int numRefresh)
{
    int indx, i;

    indx = encvid->firstIntraRefreshMBIndx;
    for (i = 0; i < numRefresh && indx < totalMB; i++)
    {
        (mblock + indx)->mb_intra = 1;
        encvid->intraSearch[indx++] = 1;
    }

    /* if read the end of frame, reset and loop around */
    if (indx >= totalMB - 1)
    {
        indx = 0;
        while (i < numRefresh && indx < totalMB)
        {
            (mblock + indx)->mb_intra = 1;
            encvid->intraSearch[indx++] = 1;
            i++;
        }
    }

    encvid->firstIntraRefreshMBIndx = indx; /* update with a new value */

    return ;
}


#ifdef HTFM
void InitHTFM(VideoEncData *encvid, HTFM_Stat *htfm_stat, double *newvar, int *collect)
{
    AVCCommonObj *video = encvid->common;
    int i;
    int lx = video->currPic->width; // padding
    int lx2 = lx << 1;
    int lx3 = lx2 + lx;
    int rx = video->currPic->pitch;
    int rx2 = rx << 1;
    int rx3 = rx2 + rx;

    int *offset, *offset2;

    /* 4/11/01, collect data every 30 frames, doesn't have to be base layer */
    if (((int)video->sliceHdr->frame_num) % 30 == 1)
    {

        *collect = 1;

        htfm_stat->countbreak = 0;
        htfm_stat->abs_dif_mad_avg = 0;

        for (i = 0; i < 16; i++)
        {
            newvar[i] = 0.0;
        }
//      encvid->functionPointer->SAD_MB_PADDING = &SAD_MB_PADDING_HTFM_Collect;
        encvid->functionPointer->SAD_Macroblock = &SAD_MB_HTFM_Collect;
        encvid->functionPointer->SAD_MB_HalfPel[0] = NULL;
        encvid->functionPointer->SAD_MB_HalfPel[1] = &SAD_MB_HP_HTFM_Collectxh;
        encvid->functionPointer->SAD_MB_HalfPel[2] = &SAD_MB_HP_HTFM_Collectyh;
        encvid->functionPointer->SAD_MB_HalfPel[3] = &SAD_MB_HP_HTFM_Collectxhyh;
        encvid->sad_extra_info = (void*)(htfm_stat);
        offset = htfm_stat->offsetArray;
        offset2 = htfm_stat->offsetRef;
    }
    else
    {
//      encvid->functionPointer->SAD_MB_PADDING = &SAD_MB_PADDING_HTFM;
        encvid->functionPointer->SAD_Macroblock = &SAD_MB_HTFM;
        encvid->functionPointer->SAD_MB_HalfPel[0] = NULL;
        encvid->functionPointer->SAD_MB_HalfPel[1] = &SAD_MB_HP_HTFMxh;
        encvid->functionPointer->SAD_MB_HalfPel[2] = &SAD_MB_HP_HTFMyh;
        encvid->functionPointer->SAD_MB_HalfPel[3] = &SAD_MB_HP_HTFMxhyh;
        encvid->sad_extra_info = (void*)(encvid->nrmlz_th);
        offset = encvid->nrmlz_th + 16;
        offset2 = encvid->nrmlz_th + 32;
    }

    offset[0] = 0;
    offset[1] = lx2 + 2;
    offset[2] = 2;
    offset[3] = lx2;
    offset[4] = lx + 1;
    offset[5] = lx3 + 3;
    offset[6] = lx + 3;
    offset[7] = lx3 + 1;
    offset[8] = lx;
    offset[9] = lx3 + 2;
    offset[10] = lx3 ;
    offset[11] = lx + 2 ;
    offset[12] = 1;
    offset[13] = lx2 + 3;
    offset[14] = lx2 + 1;
    offset[15] = 3;

    offset2[0] = 0;
    offset2[1] = rx2 + 2;
    offset2[2] = 2;
    offset2[3] = rx2;
    offset2[4] = rx + 1;
    offset2[5] = rx3 + 3;
    offset2[6] = rx + 3;
    offset2[7] = rx3 + 1;
    offset2[8] = rx;
    offset2[9] = rx3 + 2;
    offset2[10] = rx3 ;
    offset2[11] = rx + 2 ;
    offset2[12] = 1;
    offset2[13] = rx2 + 3;
    offset2[14] = rx2 + 1;
    offset2[15] = 3;

    return ;
}

void UpdateHTFM(AVCEncObject *encvid, double *newvar, double *exp_lamda, HTFM_Stat *htfm_stat)
{
    if (htfm_stat->countbreak == 0)
        htfm_stat->countbreak = 1;

    newvar[0] = (double)(htfm_stat->abs_dif_mad_avg) / (htfm_stat->countbreak * 16.);

    if (newvar[0] < 0.001)
    {
        newvar[0] = 0.001; /* to prevent floating overflow */
    }
    exp_lamda[0] =  1 / (newvar[0] * 1.4142136);
    exp_lamda[1] = exp_lamda[0] * 1.5825;
    exp_lamda[2] = exp_lamda[0] * 2.1750;
    exp_lamda[3] = exp_lamda[0] * 3.5065;
    exp_lamda[4] = exp_lamda[0] * 3.1436;
    exp_lamda[5] = exp_lamda[0] * 3.5315;
    exp_lamda[6] = exp_lamda[0] * 3.7449;
    exp_lamda[7] = exp_lamda[0] * 4.5854;
    exp_lamda[8] = exp_lamda[0] * 4.6191;
    exp_lamda[9] = exp_lamda[0] * 5.4041;
    exp_lamda[10] = exp_lamda[0] * 6.5974;
    exp_lamda[11] = exp_lamda[0] * 10.5341;
    exp_lamda[12] = exp_lamda[0] * 10.0719;
    exp_lamda[13] = exp_lamda[0] * 12.0516;
    exp_lamda[14] = exp_lamda[0] * 15.4552;

    CalcThreshold(HTFM_Pf, exp_lamda, encvid->nrmlz_th);
    return ;
}


void CalcThreshold(double pf, double exp_lamda[], int nrmlz_th[])
{
    int i;
    double temp[15];
    //  printf("\nLamda: ");

    /* parametric PREMODELling */
    for (i = 0; i < 15; i++)
    {
        //    printf("%g ",exp_lamda[i]);
        if (pf < 0.5)
            temp[i] = 1 / exp_lamda[i] * M4VENC_LOG(2 * pf);
        else
            temp[i] = -1 / exp_lamda[i] * M4VENC_LOG(2 * (1 - pf));
    }

    nrmlz_th[15] = 0;
    for (i = 0; i < 15; i++)        /* scale upto no.pixels */
        nrmlz_th[i] = (int)(temp[i] * ((i + 1) << 4) + 0.5);

    return ;
}

void    HTFMPrepareCurMB_AVC(AVCEncObject *encvid, HTFM_Stat *htfm_stat, uint8 *cur, int pitch)
{
    AVCCommonObj *video = encvid->common;
    uint32 *htfmMB = (uint32*)(encvid->currYMB);
    uint8 *ptr, byte;
    int *offset;
    int i;
    uint32 word;

    if (((int)video->sliceHdr->frame_num) % 30 == 1)
    {
        offset = htfm_stat->offsetArray;
    }
    else
    {
        offset = encvid->nrmlz_th + 16;
    }

    for (i = 0; i < 16; i++)
    {
        ptr = cur + offset[i];
        word = ptr[0];
        byte = ptr[4];
        word |= (byte << 8);
        byte = ptr[8];
        word |= (byte << 16);
        byte = ptr[12];
        word |= (byte << 24);
        *htfmMB++ = word;

        word = *(ptr += (pitch << 2));
        byte = ptr[4];
        word |= (byte << 8);
        byte = ptr[8];
        word |= (byte << 16);
        byte = ptr[12];
        word |= (byte << 24);
        *htfmMB++ = word;

        word = *(ptr += (pitch << 2));
        byte = ptr[4];
        word |= (byte << 8);
        byte = ptr[8];
        word |= (byte << 16);
        byte = ptr[12];
        word |= (byte << 24);
        *htfmMB++ = word;

        word = *(ptr += (pitch << 2));
        byte = ptr[4];
        word |= (byte << 8);
        byte = ptr[8];
        word |= (byte << 16);
        byte = ptr[12];
        word |= (byte << 24);
        *htfmMB++ = word;
    }

    return ;
}


#endif // HTFM

void    AVCPrepareCurMB(AVCEncObject *encvid, uint8 *cur, int pitch)
{
    void* tmp = (void*)(encvid->currYMB);
    uint32 *currYMB = (uint32*) tmp;
    int i;

    cur -= pitch;

    for (i = 0; i < 16; i++)
    {
        *currYMB++ = *((uint32*)(cur += pitch));
        *currYMB++ = *((uint32*)(cur + 4));
        *currYMB++ = *((uint32*)(cur + 8));
        *currYMB++ = *((uint32*)(cur + 12));
    }

    return ;
}

#ifdef FIXED_INTERPRED_MODE

/* due to the complexity of the predicted motion vector, we may not decide to skip
a macroblock here just yet. */
/* We will find the best motion vector and the best intra prediction mode for each block. */
/* output are
    currMB->NumMbPart,  currMB->MbPartWidth, currMB->MbPartHeight,
    currMB->NumSubMbPart[], currMB->SubMbPartWidth[], currMB->SubMbPartHeight,
    currMB->MBPartPredMode[][] (L0 or L1 or BiPred)
    currMB->RefIdx[], currMB->ref_idx_L0[],
    currMB->mvL0[], currMB->mvL1[]
    */

AVCEnc_Status AVCMBMotionSearch(AVCEncObject *encvid, AVCMacroblock *currMB, int mbNum,
                                int num_pass)
{
    AVCCommonObj *video = encvid->common;
    int mbPartIdx, subMbPartIdx;
    int16 *mv;
    int i;
    int SubMbPartHeight, SubMbPartWidth, NumSubMbPart;

    /* assign value to currMB->MBPartPredMode[][x],subMbMode[],NumSubMbPart[],SubMbPartWidth[],SubMbPartHeight[] */

    currMB->mbMode = FIXED_INTERPRED_MODE;
    currMB->mb_intra = 0;

    if (currMB->mbMode == AVC_P16)
    {
        currMB->NumMbPart = 1;
        currMB->MbPartWidth = 16;
        currMB->MbPartHeight = 16;
        currMB->SubMbPartHeight[0] = 16;
        currMB->SubMbPartWidth[0] = 16;
        currMB->NumSubMbPart[0] =  1;
    }
    else if (currMB->mbMode == AVC_P16x8)
    {
        currMB->NumMbPart = 2;
        currMB->MbPartWidth = 16;
        currMB->MbPartHeight = 8;
        for (i = 0; i < 2; i++)
        {
            currMB->SubMbPartWidth[i] = 16;
            currMB->SubMbPartHeight[i] = 8;
            currMB->NumSubMbPart[i] = 1;
        }
    }
    else if (currMB->mbMode == AVC_P8x16)
    {
        currMB->NumMbPart = 2;
        currMB->MbPartWidth = 8;
        currMB->MbPartHeight = 16;
        for (i = 0; i < 2; i++)
        {
            currMB->SubMbPartWidth[i] = 8;
            currMB->SubMbPartHeight[i] = 16;
            currMB->NumSubMbPart[i] = 1;
        }
    }
    else if (currMB->mbMode == AVC_P8 || currMB->mbMode == AVC_P8ref0)
    {
        currMB->NumMbPart = 4;
        currMB->MbPartWidth = 8;
        currMB->MbPartHeight = 8;
        if (FIXED_SUBMB_MODE == AVC_8x8)
        {
            SubMbPartHeight = 8;
            SubMbPartWidth = 8;
            NumSubMbPart = 1;
        }
        else if (FIXED_SUBMB_MODE == AVC_8x4)
        {
            SubMbPartHeight = 4;
            SubMbPartWidth = 8;
            NumSubMbPart = 2;
        }
        else if (FIXED_SUBMB_MODE == AVC_4x8)
        {
            SubMbPartHeight = 8;
            SubMbPartWidth = 4;
            NumSubMbPart = 2;
        }
        else if (FIXED_SUBMB_MODE == AVC_4x4)
        {
            SubMbPartHeight = 4;
            SubMbPartWidth = 4;
            NumSubMbPart = 4;
        }

        for (i = 0; i < 4; i++)
        {
            currMB->subMbMode[i] = FIXED_SUBMB_MODE;
            currMB->SubMbPartHeight[i] = SubMbPartHeight;
            currMB->SubMbPartWidth[i] = SubMbPartWidth;
            currMB->NumSubMbPart[i] = NumSubMbPart;
        }
    }
    else /* it's probably intra mode */
    {
        return AVCENC_SUCCESS;
    }

    for (mbPartIdx = 0; mbPartIdx < 4; mbPartIdx++)
    {
        currMB->MBPartPredMode[mbPartIdx][0]  = AVC_Pred_L0;
        currMB->ref_idx_L0[mbPartIdx] = FIXED_REF_IDX;
        currMB->RefIdx[mbPartIdx] = video->RefPicList0[FIXED_REF_IDX]->RefIdx;

        for (subMbPartIdx = 0; subMbPartIdx < 4; subMbPartIdx++)
        {
            mv = (int16*)(currMB->mvL0 + (mbPartIdx << 2) + subMbPartIdx);

            *mv++ = FIXED_MVX;
            *mv = FIXED_MVY;
        }
    }

    encvid->min_cost = 0;

    return AVCENC_SUCCESS;
}

#else /* perform the search */

/* This option #1 search is very similar to PV's MPEG4 motion search algorithm.
  The search is done in hierarchical manner from 16x16 MB down to smaller and smaller
  partition. At each level, a decision can be made to stop the search if the expected
  prediction gain is not worth the computation. The decision can also be made at the finest
  level for more fullsearch-like behavior with the price of heavier computation. */
void AVCMBMotionSearch(AVCEncObject *encvid, uint8 *cur, uint8 *best_cand[],
                       int i0, int j0, int type_pred, int FS_en, int *hp_guess)
{
    AVCCommonObj *video = encvid->common;
    AVCPictureData *currPic = video->currPic;
    AVCSeqParamSet *currSPS = video->currSeqParams;
    AVCRateControl *rateCtrl = encvid->rateCtrl;
    AVCMacroblock *currMB = video->currMB;
    uint8 *ref, *cand, *ncand;
    void *extra_info = encvid->sad_extra_info;
    int mbnum = video->mbNum;
    int width = currPic->width; /* 6/12/01, must be multiple of 16 */
    int height = currPic->height;
    AVCMV *mot16x16 = encvid->mot16x16;
    int (*SAD_Macroblock)(uint8*, uint8*, int, void*) = encvid->functionPointer->SAD_Macroblock;

    int range = rateCtrl->mvRange;

    int lx = currPic->pitch; /*  padding */
    int i, j, imin, jmin, ilow, ihigh, jlow, jhigh;
    int d, dmin, dn[9];
    int k;
    int mvx[5], mvy[5];
    int num_can, center_again;
    int last_loc, new_loc = 0;
    int step, max_step = range >> 1;
    int next;

    int cmvx, cmvy; /* estimated predicted MV */
    int lev_idx;
    int lambda_motion = encvid->lambda_motion;
    uint8 *mvbits = encvid->mvbits;
    int mvshift = 2;
    int mvcost;

    int min_sad = 65535;

    ref = video->RefPicList0[DEFAULT_REF_IDX]->Sl; /* origin of actual frame */

    /* have to initialize these params, necessary for interprediction part */
    currMB->NumMbPart = 1;
    currMB->SubMbPartHeight[0] = 16;
    currMB->SubMbPartWidth[0] = 16;
    currMB->NumSubMbPart[0] = 1;
    currMB->ref_idx_L0[0] = currMB->ref_idx_L0[1] =
                                currMB->ref_idx_L0[2] = currMB->ref_idx_L0[3] = DEFAULT_REF_IDX;
    currMB->ref_idx_L1[0] = currMB->ref_idx_L1[1] =
                                currMB->ref_idx_L1[2] = currMB->ref_idx_L1[3] = DEFAULT_REF_IDX;
    currMB->RefIdx[0] = currMB->RefIdx[1] =
                            currMB->RefIdx[2] = currMB->RefIdx[3] = video->RefPicList0[DEFAULT_REF_IDX]->RefIdx;

    cur = encvid->currYMB; /* use smaller memory space for current MB */

    /*  find limit of the search (adjusting search range)*/
    lev_idx = mapLev2Idx[currSPS->level_idc];

    /* we can make this part dynamic based on previous statistics */
    ilow = i0 - range;
    if (i0 - ilow > 2047) /* clip to conform with the standard */
    {
        ilow = i0 - 2047;
    }
    if (ilow < -13)  // change it from -15 to -13 because of 6-tap filter needs extra 2 lines.
    {
        ilow = -13;
    }

    ihigh = i0 + range - 1;
    if (ihigh - i0 > 2047) /* clip to conform with the standard */
    {
        ihigh = i0 + 2047;
    }
    if (ihigh > width - 3)
    {
        ihigh = width - 3;  // change from width-1 to width-3 for the same reason as above
    }

    jlow = j0 - range;
    if (j0 - jlow > MaxVmvR[lev_idx] - 1) /* clip to conform with the standard */
    {
        jlow = j0 - MaxVmvR[lev_idx] + 1;
    }
    if (jlow < -13)     // same reason as above
    {
        jlow = -13;
    }

    jhigh = j0 + range - 1;
    if (jhigh - j0 > MaxVmvR[lev_idx] - 1) /* clip to conform with the standard */
    {
        jhigh = j0 + MaxVmvR[lev_idx] - 1;
    }
    if (jhigh > height - 3) // same reason as above
    {
        jhigh = height - 3;
    }

    /* find initial motion vector & predicted MV*/
    AVCCandidateSelection(mvx, mvy, &num_can, i0 >> 4, j0 >> 4, encvid, type_pred, &cmvx, &cmvy);

    imin = i0;
    jmin = j0; /* needed for fullsearch */
    ncand = ref + i0 + j0 * lx;

    /* for first row of MB, fullsearch can be used */
    if (FS_en)
    {
        *hp_guess = 0; /* no guess for fast half-pel */

        dmin =  AVCFullSearch(encvid, ref, cur, &imin, &jmin, ilow, ihigh, jlow, jhigh, cmvx, cmvy);

        ncand = ref + imin + jmin * lx;
    }
    else
    {   /*       fullsearch the top row to only upto (0,3) MB */
        /*       upto 30% complexity saving with the same complexity */
        if (video->PrevRefFrameNum == 0 && j0 == 0 && i0 <= 64 && type_pred != 1)
        {
            *hp_guess = 0; /* no guess for fast half-pel */
            dmin =  AVCFullSearch(encvid, ref, cur, &imin, &jmin, ilow, ihigh, jlow, jhigh, cmvx, cmvy);
            ncand = ref + imin + jmin * lx;
        }
        else
        {
            /************** initialize candidate **************************/

            dmin = 65535;

            /* check if all are equal */
            if (num_can == ALL_CAND_EQUAL)
            {
                i = i0 + mvx[0];
                j = j0 + mvy[0];

                if (i >= ilow && i <= ihigh && j >= jlow && j <= jhigh)
                {
                    cand = ref + i + j * lx;

                    d = (*SAD_Macroblock)(cand, cur, (dmin << 16) | lx, extra_info);
                    mvcost = MV_COST(lambda_motion, mvshift, i - i0, j - j0, cmvx, cmvy);
                    d +=  mvcost;

                    if (d < dmin)
                    {
                        dmin = d;
                        imin = i;
                        jmin = j;
                        ncand = cand;
                        min_sad = d - mvcost; // for rate control
                    }
                }
            }
            else
            {
                /************** evaluate unique candidates **********************/
                for (k = 0; k < num_can; k++)
                {
                    i = i0 + mvx[k];
                    j = j0 + mvy[k];

                    if (i >= ilow && i <= ihigh && j >= jlow && j <= jhigh)
                    {
                        cand = ref + i + j * lx;
                        d = (*SAD_Macroblock)(cand, cur, (dmin << 16) | lx, extra_info);
                        mvcost = MV_COST(lambda_motion, mvshift, i - i0, j - j0, cmvx, cmvy);
                        d +=  mvcost;

                        if (d < dmin)
                        {
                            dmin = d;
                            imin = i;
                            jmin = j;
                            ncand = cand;
                            min_sad = d - mvcost; // for rate control
                        }
                    }
                }
            }

            /******************* local refinement ***************************/
            center_again = 0;
            last_loc = new_loc = 0;
            //          ncand = ref + jmin*lx + imin;  /* center of the search */
            step = 0;
            dn[0] = dmin;
            while (!center_again && step <= max_step)
            {

                AVCMoveNeighborSAD(dn, last_loc);

                center_again = 1;
                i = imin;
                j = jmin - 1;
                cand = ref + i + j * lx;

                /*  starting from [0,-1] */
                /* spiral check one step at a time*/
                for (k = 2; k <= 8; k += 2)
                {
                    if (!tab_exclude[last_loc][k]) /* exclude last step computation */
                    {       /* not already computed */
                        if (i >= ilow && i <= ihigh && j >= jlow && j <= jhigh)
                        {
                            d = (*SAD_Macroblock)(cand, cur, (dmin << 16) | lx, extra_info);
                            mvcost = MV_COST(lambda_motion, mvshift, i - i0, j - j0, cmvx, cmvy);
                            d += mvcost;

                            dn[k] = d; /* keep it for half pel use */

                            if (d < dmin)
                            {
                                ncand = cand;
                                dmin = d;
                                imin = i;
                                jmin = j;
                                center_again = 0;
                                new_loc = k;
                                min_sad = d - mvcost; // for rate control
                            }
                        }
                    }
                    if (k == 8)  /* end side search*/
                    {
                        if (!center_again)
                        {
                            k = -1; /* start diagonal search */
                            cand -= lx;
                            j--;
                        }
                    }
                    else
                    {
                        next = refine_next[k][0];
                        i += next;
                        cand += next;
                        next = refine_next[k][1];
                        j += next;
                        cand += lx * next;
                    }
                }
                last_loc = new_loc;
                step ++;
            }
            if (!center_again)
                AVCMoveNeighborSAD(dn, last_loc);

            *hp_guess = AVCFindMin(dn);

            encvid->rateCtrl->MADofMB[mbnum] = min_sad / 256.0;
        }
    }

    mot16x16[mbnum].sad = dmin;
    mot16x16[mbnum].x = (imin - i0) << 2;
    mot16x16[mbnum].y = (jmin - j0) << 2;
    best_cand[0] = ncand;

    if (rateCtrl->subPelEnable) // always enable half-pel search
    {
        /* find half-pel resolution motion vector */
        min_sad = AVCFindHalfPelMB(encvid, cur, mot16x16 + mbnum, best_cand[0], i0, j0, *hp_guess, cmvx, cmvy);

        encvid->rateCtrl->MADofMB[mbnum] = min_sad / 256.0;


        if (encvid->best_qpel_pos == -1)
        {
            ncand = encvid->hpel_cand[encvid->best_hpel_pos];
        }
        else
        {
            ncand = encvid->qpel_cand[encvid->best_qpel_pos];
        }
    }
    else
    {
        encvid->rateCtrl->MADofMB[mbnum] = min_sad / 256.0;
    }

    /** do motion comp here for now */
    ref = currPic->Sl + i0 + j0 * lx;
    /* copy from the best result to current Picture */
    for (j = 0; j < 16; j++)
    {
        for (i = 0; i < 16; i++)
        {
            *ref++ = *ncand++;
        }
        ref += (lx - 16);
        ncand += 8;
    }

    return ;
}

#endif

/*===============================================================================
    Function:   AVCFullSearch
    Date:       09/16/2000
    Purpose:    Perform full-search motion estimation over the range of search
                region in a spiral-outward manner.
    Input/Output:   VideoEncData, current Vol, previou Vop, pointer to the left corner of
                current VOP, current coord (also output), boundaries.
===============================================================================*/
int AVCFullSearch(AVCEncObject *encvid, uint8 *prev, uint8 *cur,
                  int *imin, int *jmin, int ilow, int ihigh, int jlow, int jhigh,
                  int cmvx, int cmvy)
{
    int range = encvid->rateCtrl->mvRange;
    AVCPictureData *currPic = encvid->common->currPic;
    uint8 *cand;
    int i, j, k, l;
    int d, dmin;
    int i0 = *imin; /* current position */
    int j0 = *jmin;
    int (*SAD_Macroblock)(uint8*, uint8*, int, void*) = encvid->functionPointer->SAD_Macroblock;
    void *extra_info = encvid->sad_extra_info;
    int lx = currPic->pitch; /* with padding */

    int offset = i0 + j0 * lx;

    int lambda_motion = encvid->lambda_motion;
    uint8 *mvbits = encvid->mvbits;
    int mvshift = 2;
    int mvcost;
    int min_sad;

    cand = prev + offset;

    dmin  = (*SAD_Macroblock)(cand, cur, (65535 << 16) | lx, (void*)extra_info);
    mvcost = MV_COST(lambda_motion, mvshift, 0, 0, cmvx, cmvy);
    min_sad = dmin;
    dmin += mvcost;

    /* perform spiral search */
    for (k = 1; k <= range; k++)
    {

        i = i0 - k;
        j = j0 - k;

        cand = prev + i + j * lx;

        for (l = 0; l < 8*k; l++)
        {
            /* no need for boundary checking again */
            if (i >= ilow && i <= ihigh && j >= jlow && j <= jhigh)
            {
                d = (*SAD_Macroblock)(cand, cur, (dmin << 16) | lx, (void*)extra_info);
                mvcost = MV_COST(lambda_motion, mvshift, i - i0, j - j0, cmvx, cmvy);
                d +=  mvcost;

                if (d < dmin)
                {
                    dmin = d;
                    *imin = i;
                    *jmin = j;
                    min_sad = d - mvcost;
                }
            }

            if (l < (k << 1))
            {
                i++;
                cand++;
            }
            else if (l < (k << 2))
            {
                j++;
                cand += lx;
            }
            else if (l < ((k << 2) + (k << 1)))
            {
                i--;
                cand--;
            }
            else
            {
                j--;
                cand -= lx;
            }
        }
    }

    encvid->rateCtrl->MADofMB[encvid->common->mbNum] = (min_sad / 256.0); // for rate control

    return dmin;
}

/*===============================================================================
    Function:   AVCCandidateSelection
    Date:       09/16/2000
    Purpose:    Fill up the list of candidate using spatio-temporal correlation
                among neighboring blocks.
    Input/Output:   type_pred = 0: first pass, 1: second pass, or no SCD
    Modified:   , 09/23/01, get rid of redundant candidates before passing back.
                , 09/11/07, added return for modified predicted MV, this will be
                    needed for both fast search and fullsearch.
===============================================================================*/

void AVCCandidateSelection(int *mvx, int *mvy, int *num_can, int imb, int jmb,
                           AVCEncObject *encvid, int type_pred, int *cmvx, int *cmvy)
{
    AVCCommonObj *video = encvid->common;
    AVCMV *mot16x16 = encvid->mot16x16;
    AVCMV *pmot;
    int mbnum = video->mbNum;
    int mbwidth = video->PicWidthInMbs;
    int mbheight = video->PicHeightInMbs;
    int i, j, same, num1;

    /* this part is for predicted MV */
    int pmvA_x = 0, pmvA_y = 0, pmvB_x = 0, pmvB_y = 0, pmvC_x = 0, pmvC_y = 0;
    int availA = 0, availB = 0, availC = 0;

    *num_can = 0;

    if (video->PrevRefFrameNum != 0) // previous frame is an IDR frame
    {
        /* Spatio-Temporal Candidate (five candidates) */
        if (type_pred == 0) /* first pass */
        {
            pmot = &mot16x16[mbnum]; /* same coordinate previous frame */
            mvx[(*num_can)] = (pmot->x) >> 2;
            mvy[(*num_can)++] = (pmot->y) >> 2;
            if (imb >= (mbwidth >> 1) && imb > 0)  /*left neighbor previous frame */
            {
                pmot = &mot16x16[mbnum-1];
                mvx[(*num_can)] = (pmot->x) >> 2;
                mvy[(*num_can)++] = (pmot->y) >> 2;
            }
            else if (imb + 1 < mbwidth)   /*right neighbor previous frame */
            {
                pmot = &mot16x16[mbnum+1];
                mvx[(*num_can)] = (pmot->x) >> 2;
                mvy[(*num_can)++] = (pmot->y) >> 2;
            }

            if (jmb < mbheight - 1)  /*bottom neighbor previous frame */
            {
                pmot = &mot16x16[mbnum+mbwidth];
                mvx[(*num_can)] = (pmot->x) >> 2;
                mvy[(*num_can)++] = (pmot->y) >> 2;
            }
            else if (jmb > 0)   /*upper neighbor previous frame */
            {
                pmot = &mot16x16[mbnum-mbwidth];
                mvx[(*num_can)] = (pmot->x) >> 2;
                mvy[(*num_can)++] = (pmot->y) >> 2;
            }

            if (imb > 0 && jmb > 0)  /* upper-left neighbor current frame*/
            {
                pmot = &mot16x16[mbnum-mbwidth-1];
                mvx[(*num_can)] = (pmot->x) >> 2;
                mvy[(*num_can)++] = (pmot->y) >> 2;
            }
            if (jmb > 0 && imb < mbheight - 1)  /* upper right neighbor current frame*/
            {
                pmot = &mot16x16[mbnum-mbwidth+1];
                mvx[(*num_can)] = (pmot->x) >> 2;
                mvy[(*num_can)++] = (pmot->y) >> 2;
            }
        }
        else    /* second pass */
            /* original ST1 algorithm */
        {
            pmot = &mot16x16[mbnum]; /* same coordinate previous frame */
            mvx[(*num_can)] = (pmot->x) >> 2;
            mvy[(*num_can)++] = (pmot->y) >> 2;

            if (imb > 0)  /*left neighbor current frame */
            {
                pmot = &mot16x16[mbnum-1];
                mvx[(*num_can)] = (pmot->x) >> 2;
                mvy[(*num_can)++] = (pmot->y) >> 2;
            }
            if (jmb > 0)  /*upper neighbor current frame */
            {
                pmot = &mot16x16[mbnum-mbwidth];
                mvx[(*num_can)] = (pmot->x) >> 2;
                mvy[(*num_can)++] = (pmot->y) >> 2;
            }
            if (imb < mbwidth - 1)  /*right neighbor previous frame */
            {
                pmot = &mot16x16[mbnum+1];
                mvx[(*num_can)] = (pmot->x) >> 2;
                mvy[(*num_can)++] = (pmot->y) >> 2;
            }
            if (jmb < mbheight - 1)  /*bottom neighbor previous frame */
            {
                pmot = &mot16x16[mbnum+mbwidth];
                mvx[(*num_can)] = (pmot->x) >> 2;
                mvy[(*num_can)++] = (pmot->y) >> 2;
            }
        }

        /* get predicted MV */
        if (imb > 0)    /* get MV from left (A) neighbor either on current or previous frame */
        {
            availA = 1;
            pmot = &mot16x16[mbnum-1];
            pmvA_x = pmot->x;
            pmvA_y = pmot->y;
        }

        if (jmb > 0) /* get MV from top (B) neighbor either on current or previous frame */
        {
            availB = 1;
            pmot = &mot16x16[mbnum-mbwidth];
            pmvB_x = pmot->x;
            pmvB_y = pmot->y;

            availC = 1;

            if (imb < mbwidth - 1) /* get MV from top-right (C) neighbor of current frame */
            {
                pmot = &mot16x16[mbnum-mbwidth+1];
            }
            else /* get MV from top-left (D) neighbor of current frame */
            {
                pmot = &mot16x16[mbnum-mbwidth-1];
            }
            pmvC_x = pmot->x;
            pmvC_y = pmot->y;
        }

    }
    else  /* only Spatial Candidate (four candidates)*/
    {
        if (type_pred == 0) /*first pass*/
        {
            if (imb > 1)  /* neighbor two blocks away to the left */
            {
                pmot = &mot16x16[mbnum-2];
                mvx[(*num_can)] = (pmot->x) >> 2;
                mvy[(*num_can)++] = (pmot->y) >> 2;
            }
            if (imb > 0 && jmb > 0)  /* upper-left neighbor */
            {
                pmot = &mot16x16[mbnum-mbwidth-1];
                mvx[(*num_can)] = (pmot->x) >> 2;
                mvy[(*num_can)++] = (pmot->y) >> 2;
            }
            if (jmb > 0 && imb < mbheight - 1)  /* upper right neighbor */
            {
                pmot = &mot16x16[mbnum-mbwidth+1];
                mvx[(*num_can)] = (pmot->x) >> 2;
                mvy[(*num_can)++] = (pmot->y) >> 2;
            }

            /* get predicted MV */
            if (imb > 1)    /* get MV from 2nd left (A) neighbor either of current frame */
            {
                availA = 1;
                pmot = &mot16x16[mbnum-2];
                pmvA_x = pmot->x;
                pmvA_y = pmot->y;
            }

            if (jmb > 0 && imb > 0) /* get MV from top-left (B) neighbor of current frame */
            {
                availB = 1;
                pmot = &mot16x16[mbnum-mbwidth-1];
                pmvB_x = pmot->x;
                pmvB_y = pmot->y;
            }

            if (jmb > 0 && imb < mbwidth - 1)
            {
                availC = 1;
                pmot = &mot16x16[mbnum-mbwidth+1];
                pmvC_x = pmot->x;
                pmvC_y = pmot->y;
            }
        }
//#ifdef SCENE_CHANGE_DETECTION
        /* second pass (ST2 algorithm)*/
        else
        {
            if (type_pred == 1) /*  4/7/01 */
            {
                if (imb > 0)  /*left neighbor current frame */
                {
                    pmot = &mot16x16[mbnum-1];
                    mvx[(*num_can)] = (pmot->x) >> 2;
                    mvy[(*num_can)++] = (pmot->y) >> 2;
                }
                if (jmb > 0)  /*upper neighbor current frame */
                {
                    pmot = &mot16x16[mbnum-mbwidth];
                    mvx[(*num_can)] = (pmot->x) >> 2;
                    mvy[(*num_can)++] = (pmot->y) >> 2;
                }
                if (imb < mbwidth - 1)  /*right neighbor current frame */
                {
                    pmot = &mot16x16[mbnum+1];
                    mvx[(*num_can)] = (pmot->x) >> 2;
                    mvy[(*num_can)++] = (pmot->y) >> 2;
                }
                if (jmb < mbheight - 1)  /*bottom neighbor current frame */
                {
                    pmot = &mot16x16[mbnum+mbwidth];
                    mvx[(*num_can)] = (pmot->x) >> 2;
                    mvy[(*num_can)++] = (pmot->y) >> 2;
                }
            }
            //#else
            else /* original ST1 algorithm */
            {
                if (imb > 0)  /*left neighbor current frame */
                {
                    pmot = &mot16x16[mbnum-1];
                    mvx[(*num_can)] = (pmot->x) >> 2;
                    mvy[(*num_can)++] = (pmot->y) >> 2;

                    if (jmb > 0)  /*upper-left neighbor current frame */
                    {
                        pmot = &mot16x16[mbnum-mbwidth-1];
                        mvx[(*num_can)] = (pmot->x) >> 2;
                        mvy[(*num_can)++] = (pmot->y) >> 2;
                    }

                }
                if (jmb > 0)  /*upper neighbor current frame */
                {
                    pmot = &mot16x16[mbnum-mbwidth];
                    mvx[(*num_can)] = (pmot->x) >> 2;
                    mvy[(*num_can)++] = (pmot->y) >> 2;

                    if (imb < mbheight - 1)  /*upper-right neighbor current frame */
                    {
                        pmot = &mot16x16[mbnum-mbwidth+1];
                        mvx[(*num_can)] = (pmot->x) >> 2;
                        mvy[(*num_can)++] = (pmot->y) >> 2;
                    }
                }
            }

            /* get predicted MV */
            if (imb > 0)    /* get MV from left (A) neighbor either on current or previous frame */
            {
                availA = 1;
                pmot = &mot16x16[mbnum-1];
                pmvA_x = pmot->x;
                pmvA_y = pmot->y;
            }

            if (jmb > 0) /* get MV from top (B) neighbor either on current or previous frame */
            {
                availB = 1;
                pmot = &mot16x16[mbnum-mbwidth];
                pmvB_x = pmot->x;
                pmvB_y = pmot->y;

                availC = 1;

                if (imb < mbwidth - 1) /* get MV from top-right (C) neighbor of current frame */
                {
                    pmot = &mot16x16[mbnum-mbwidth+1];
                }
                else /* get MV from top-left (D) neighbor of current frame */
                {
                    pmot = &mot16x16[mbnum-mbwidth-1];
                }
                pmvC_x = pmot->x;
                pmvC_y = pmot->y;
            }
        }
//#endif
    }

    /*  3/23/01, remove redundant candidate (possible k-mean) */
    num1 = *num_can;
    *num_can = 1;
    for (i = 1; i < num1; i++)
    {
        same = 0;
        j = 0;
        while (!same && j < *num_can)
        {
#if (CANDIDATE_DISTANCE==0)
            if (mvx[i] == mvx[j] && mvy[i] == mvy[j])
#else
            // modified k-mean,  3/24/01, shouldn't be greater than 3
            if (AVC_ABS(mvx[i] - mvx[j]) + AVC_ABS(mvy[i] - mvy[j]) < CANDIDATE_DISTANCE)
#endif
                same = 1;
            j++;
        }
        if (!same)
        {
            mvx[*num_can] = mvx[i];
            mvy[*num_can] = mvy[i];
            (*num_can)++;
        }
    }

    if (num1 == 5 && *num_can == 1)
        *num_can = ALL_CAND_EQUAL; /* all are equal */

    /* calculate predicted MV */

    if (availA && !(availB || availC))
    {
        *cmvx = pmvA_x;
        *cmvy = pmvA_y;
    }
    else
    {
        *cmvx = AVC_MEDIAN(pmvA_x, pmvB_x, pmvC_x);
        *cmvy = AVC_MEDIAN(pmvA_y, pmvB_y, pmvC_y);
    }

    return ;
}


/*************************************************************
    Function:   AVCMoveNeighborSAD
    Date:       3/27/01
    Purpose:    Move neighboring SAD around when center has shifted
*************************************************************/

void AVCMoveNeighborSAD(int dn[], int new_loc)
{
    int tmp[9];
    tmp[0] = dn[0];
    tmp[1] = dn[1];
    tmp[2] = dn[2];
    tmp[3] = dn[3];
    tmp[4] = dn[4];
    tmp[5] = dn[5];
    tmp[6] = dn[6];
    tmp[7] = dn[7];
    tmp[8] = dn[8];
    dn[0] = dn[1] = dn[2] = dn[3] = dn[4] = dn[5] = dn[6] = dn[7] = dn[8] = 65536;

    switch (new_loc)
    {
        case 0:
            break;
        case 1:
            dn[4] = tmp[2];
            dn[5] = tmp[0];
            dn[6] = tmp[8];
            break;
        case 2:
            dn[4] = tmp[3];
            dn[5] = tmp[4];
            dn[6] = tmp[0];
            dn[7] = tmp[8];
            dn[8] = tmp[1];
            break;
        case 3:
            dn[6] = tmp[4];
            dn[7] = tmp[0];
            dn[8] = tmp[2];
            break;
        case 4:
            dn[1] = tmp[2];
            dn[2] = tmp[3];
            dn[6] = tmp[5];
            dn[7] = tmp[6];
            dn[8] = tmp[0];
            break;
        case 5:
            dn[1] = tmp[0];
            dn[2] = tmp[4];
            dn[8] = tmp[6];
            break;
        case 6:
            dn[1] = tmp[8];
            dn[2] = tmp[0];
            dn[3] = tmp[4];
            dn[4] = tmp[5];
            dn[8] = tmp[7];
            break;
        case 7:
            dn[2] = tmp[8];
            dn[3] = tmp[0];
            dn[4] = tmp[6];
            break;
        case 8:
            dn[2] = tmp[1];
            dn[3] = tmp[2];
            dn[4] = tmp[0];
            dn[5] = tmp[6];
            dn[6] = tmp[7];
            break;
    }
    dn[0] = tmp[new_loc];

    return ;
}

/*  3/28/01, find minimal of dn[9] */

int AVCFindMin(int dn[])
{
    int min, i;
    int dmin;

    dmin = dn[1];
    min = 1;
    for (i = 2; i < 9; i++)
    {
        if (dn[i] < dmin)
        {
            dmin = dn[i];
            min = i;
        }
    }

    return min;
}



