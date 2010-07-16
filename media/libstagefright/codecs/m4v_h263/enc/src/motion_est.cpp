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
#include "mp4enc_lib.h"
#include "mp4lib_int.h"
#include "m4venc_oscl.h"

//#define PRINT_MV
#define MIN_GOP 1   /* minimum size of GOP,  1/23/01, need to be tested */

#define CANDIDATE_DISTANCE  0 /* distance candidate from one another to consider as a distinct one */
/* shouldn't be more than 3 */

#define ZERO_MV_PREF    0 /* 0: bias (0,0)MV before full-pel search, lowest complexity*/
/* 1: bias (0,0)MV after full-pel search, before half-pel, highest comp */
/* 2: bias (0,0)MV after half-pel, high comp, better PSNR */

#define RASTER_REFRESH  /* instead of random INTRA refresh, do raster scan,  2/26/01 */

#ifdef RASTER_REFRESH
#define TARGET_REFRESH_PER_REGION 4 /* , no. MB per frame to be INTRA refreshed */
#else
#define TARGET_REFRESH_PER_REGION 1 /* , no. MB per region to be INTRA refreshed */
#endif

#define ALL_CAND_EQUAL  10  /*  any number greater than 5 will work */

#define NumPixelMB  256     /*  number of pixels used in SAD calculation */

#define DEF_8X8_WIN 3   /* search region for 8x8 MVs around the 16x16 MV */
#define MB_Nb  256

#define PREF_NULL_VEC 129   /* for zero vector bias */
#define PREF_16_VEC 129     /* 1MV bias versus 4MVs*/
#define PREF_INTRA  512     /* bias for INTRA coding */

const static Int tab_exclude[9][9] =  // [last_loc][curr_loc]
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

const static Int refine_next[8][2] =    /* [curr_k][increment] */
{
    {0, 0}, {2, 0}, {1, 1}, {0, 2}, { -1, 1}, { -2, 0}, { -1, -1}, {0, -2}
};

#ifdef __cplusplus
extern "C"
{
#endif

    void MBMotionSearch(VideoEncData *video, UChar *cur, UChar *best_cand[],
    Int i0, Int j0, Int type_pred, Int fullsearch, Int *hp_guess);

    Int  fullsearch(VideoEncData *video, Vol *currVol, UChar *ref, UChar *cur,
                    Int *imin, Int *jmin, Int ilow, Int ihigh, Int jlow, Int jhigh);
    Int fullsearchBlk(VideoEncData *video, Vol *currVol, UChar *cent, UChar *cur,
                      Int *imin, Int *jmin, Int ilow, Int ihigh, Int jlow, Int jhigh, Int range);
    void CandidateSelection(Int *mvx, Int *mvy, Int *num_can, Int imb, Int jmb,
                            VideoEncData *video, Int type_pred);
    void RasterIntraUpdate(UChar *intraArray, UChar *Mode, Int totalMB, Int numRefresh);
    void ResetIntraUpdate(UChar *intraArray, Int totalMB);
    void ResetIntraUpdateRegion(UChar *intraArray, Int start_i, Int rwidth,
                                Int start_j, Int rheight, Int mbwidth, Int mbheight);

    void MoveNeighborSAD(Int dn[], Int new_loc);
    Int FindMin(Int dn[]);
    void PrepareCurMB(VideoEncData *video, UChar *cur);

#ifdef __cplusplus
}
#endif

/***************************************/
/*  2/28/01, for HYPOTHESIS TESTING */
#ifdef HTFM     /* defined in mp4def.h */
#ifdef __cplusplus
extern "C"
{
#endif
    void CalcThreshold(double pf, double exp_lamda[], Int nrmlz_th[]);
    void    HTFMPrepareCurMB(VideoEncData *video, HTFM_Stat *htfm_stat, UChar *cur);
#ifdef __cplusplus
}
#endif


#define HTFM_Pf  0.25   /* 3/2/1, probability of false alarm, can be varied from 0 to 0.5 */
/***************************************/
#endif

#ifdef _SAD_STAT
ULong num_MB = 0;
ULong num_HP_MB = 0;
ULong num_Blk = 0;
ULong num_HP_Blk = 0;
ULong num_cand = 0;
ULong num_better_hp = 0;
ULong i_dist_from_guess = 0;
ULong j_dist_from_guess = 0;
ULong num_hp_not_zero = 0;
#endif



/*==================================================================
    Function:   MotionEstimation
    Date:       10/3/2000
    Purpose:    Go through all macroblock for motion search and
                determine scene change detection.
====================================================================*/

void MotionEstimation(VideoEncData *video)
{
    UChar use_4mv = video->encParams->MV8x8_Enabled;
    Vol *currVol = video->vol[video->currLayer];
    Vop *currVop = video->currVop;
    VideoEncFrameIO *currFrame = video->input;
    Int i, j, comp;
    Int mbwidth = currVol->nMBPerRow;
    Int mbheight = currVol->nMBPerCol;
    Int totalMB = currVol->nTotalMB;
    Int width = currFrame->pitch;
    UChar *mode_mb, *Mode = video->headerInfo.Mode;
    MOT *mot_mb, **mot = video->mot;
    UChar *intraArray = video->intraArray;
    Int FS_en = video->encParams->FullSearch_Enabled;
    void (*ComputeMBSum)(UChar *, Int, MOT *) = video->functionPointer->ComputeMBSum;
    void (*ChooseMode)(UChar*, UChar*, Int, Int) = video->functionPointer->ChooseMode;

    Int numIntra, start_i, numLoop, incr_i;
    Int mbnum, offset;
    UChar *cur, *best_cand[5];
    Int sad8 = 0, sad16 = 0;
    Int totalSAD = 0;   /* average SAD for rate control */
    Int skip_halfpel_4mv;
    Int f_code_p, f_code_n, max_mag = 0, min_mag = 0;
    Int type_pred;
    Int xh[5] = {0, 0, 0, 0, 0};
    Int yh[5] = {0, 0, 0, 0, 0}; /* half-pel */
    UChar hp_mem4MV[17*17*4];

#ifdef HTFM
    /***** HYPOTHESIS TESTING ********/  /* 2/28/01 */
    Int collect = 0;
    HTFM_Stat htfm_stat;
    double newvar[16];
    double exp_lamda[15];
    /*********************************/
#endif
    Int hp_guess = 0;
#ifdef PRINT_MV
    FILE *fp_debug;
#endif

//  FILE *fstat;
//  static int frame_num = 0;

    offset = 0;

    if (video->currVop->predictionType == I_VOP)
    {   /* compute the SAV */
        mbnum = 0;
        cur = currFrame->yChan;

        for (j = 0; j < mbheight; j++)
        {
            for (i = 0; i < mbwidth; i++)
            {
                video->mbnum = mbnum;
                mot_mb = mot[mbnum];

                (*ComputeMBSum)(cur + (i << 4), width, mot_mb);

                totalSAD += mot_mb[0].sad;

                mbnum++;
            }
            cur += (width << 4);
        }

        video->sumMAD = (float)totalSAD / (float)NumPixelMB;

        ResetIntraUpdate(intraArray, totalMB);

        return  ;
    }

    /* 09/20/05 */
    if (video->prevBaseVop->padded == 0 && !video->encParams->H263_Enabled)
    {
        PaddingEdge(video->prevBaseVop);
        video->prevBaseVop->padded = 1;
    }

    /* Random INTRA update */
    /*  suggest to do it in CodeMB */
    /*  2/21/2001 */
    //if(video->encParams->RC_Type == CBR_1 || video->encParams->RC_Type == CBR_2)
    if (video->currLayer == 0 && video->encParams->Refresh)
    {
        RasterIntraUpdate(intraArray, Mode, totalMB, video->encParams->Refresh);
    }

    video->sad_extra_info = NULL;

#ifdef HTFM
    /***** HYPOTHESIS TESTING ********/  /* 2/28/01 */
    InitHTFM(video, &htfm_stat, newvar, &collect);
    /*********************************/
#endif

    if ((video->encParams->SceneChange_Det == 1) /*&& video->currLayer==0 */
            && ((video->encParams->LayerFrameRate[0] < 5.0) || (video->numVopsInGOP > MIN_GOP)))
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
    numIntra = 0;
    while (numLoop--)
    {
        for (j = 0; j < mbheight; j++)
        {
            if (incr_i > 1)
                start_i = (start_i == 0 ? 1 : 0) ; /* toggle 0 and 1 */

            offset = width * (j << 4) + (start_i << 4);

            mbnum = j * mbwidth + start_i;

            for (i = start_i; i < mbwidth; i += incr_i)
            {
                video->mbnum = mbnum;
                mot_mb = mot[mbnum];
                mode_mb = Mode + mbnum;

                cur = currFrame->yChan + offset;


                if (*mode_mb != MODE_INTRA)
                {
#if defined(HTFM)
                    HTFMPrepareCurMB(video, &htfm_stat, cur);
#else
                    PrepareCurMB(video, cur);
#endif
                    /************************************************************/
                    /******** full-pel 1MV and 4MVs search **********************/

#ifdef _SAD_STAT
                    num_MB++;
#endif
                    MBMotionSearch(video, cur, best_cand, i << 4, j << 4, type_pred,
                                   FS_en, &hp_guess);

#ifdef PRINT_MV
                    fp_debug = fopen("c:\\bitstream\\mv1_debug.txt", "a");
                    fprintf(fp_debug, "#%d (%d,%d,%d) : ", mbnum, mot_mb[0].x, mot_mb[0].y, mot_mb[0].sad);
                    fprintf(fp_debug, "(%d,%d,%d) : (%d,%d,%d) : (%d,%d,%d) : (%d,%d,%d) : ==>\n",
                            mot_mb[1].x, mot_mb[1].y, mot_mb[1].sad,
                            mot_mb[2].x, mot_mb[2].y, mot_mb[2].sad,
                            mot_mb[3].x, mot_mb[3].y, mot_mb[3].sad,
                            mot_mb[4].x, mot_mb[4].y, mot_mb[4].sad);
                    fclose(fp_debug);
#endif
                    sad16 = mot_mb[0].sad;
#ifdef NO_INTER4V
                    sad8 = sad16;
#else
                    sad8 = mot_mb[1].sad + mot_mb[2].sad + mot_mb[3].sad + mot_mb[4].sad;
#endif

                    /* choose between INTRA or INTER */
                    (*ChooseMode)(mode_mb, cur, width, ((sad8 < sad16) ? sad8 : sad16));
                }
                else    /* INTRA update, use for prediction 3/23/01 */
                {
                    mot_mb[0].x = mot_mb[0].y = 0;
                }

                if (*mode_mb == MODE_INTRA)
                {
                    numIntra++ ;

                    /* compute SAV for rate control and fast DCT, 11/28/00 */
                    (*ComputeMBSum)(cur, width, mot_mb);

                    /* leave mot_mb[0] as it is for fast motion search */
                    /* set the 4 MVs to zeros */
                    for (comp = 1; comp <= 4; comp++)
                    {
                        mot_mb[comp].x = 0;
                        mot_mb[comp].y = 0;
                    }
#ifdef PRINT_MV
                    fp_debug = fopen("c:\\bitstream\\mv1_debug.txt", "a");
                    fprintf(fp_debug, "\n");
                    fclose(fp_debug);
#endif
                }
                else /* *mode_mb = MODE_INTER;*/
                {
                    if (video->encParams->HalfPel_Enabled)
                    {
#ifdef _SAD_STAT
                        num_HP_MB++;
#endif
                        /* find half-pel resolution motion vector */
                        FindHalfPelMB(video, cur, mot_mb, best_cand[0],
                                      i << 4, j << 4, xh, yh, hp_guess);
#ifdef PRINT_MV
                        fp_debug = fopen("c:\\bitstream\\mv1_debug.txt", "a");
                        fprintf(fp_debug, "(%d,%d), %d\n", mot_mb[0].x, mot_mb[0].y, mot_mb[0].sad);
                        fclose(fp_debug);
#endif
                        skip_halfpel_4mv = ((sad16 - mot_mb[0].sad) <= (MB_Nb >> 1) + 1);
                        sad16 = mot_mb[0].sad;

#ifndef NO_INTER4V
                        if (use_4mv && !skip_halfpel_4mv)
                        {
                            /* Also decide 1MV or 4MV !!!!!!!!*/
                            sad8 = FindHalfPelBlk(video, cur, mot_mb, sad16,
                                                  best_cand, mode_mb, i << 4, j << 4, xh, yh, hp_mem4MV);

#ifdef PRINT_MV
                            fp_debug = fopen("c:\\bitstream\\mv1_debug.txt", "a");
                            fprintf(fp_debug, " (%d,%d,%d) : (%d,%d,%d) : (%d,%d,%d) : (%d,%d,%d) \n",
                                    mot_mb[1].x, mot_mb[1].y, mot_mb[1].sad,
                                    mot_mb[2].x, mot_mb[2].y, mot_mb[2].sad,
                                    mot_mb[3].x, mot_mb[3].y, mot_mb[3].sad,
                                    mot_mb[4].x, mot_mb[4].y, mot_mb[4].sad);
                            fclose(fp_debug);
#endif
                        }
#endif /* NO_INTER4V */
                    }
                    else    /* HalfPel_Enabled ==0  */
                    {
#ifndef NO_INTER4V
                        //if(sad16 < sad8-PREF_16_VEC)
                        if (sad16 - PREF_16_VEC > sad8)
                        {
                            *mode_mb = MODE_INTER4V;
                        }
#endif
                    }
#if (ZERO_MV_PREF==2)   /* use mot_mb[7].sad as d0 computed in MBMotionSearch*/
                    /******************************************************/
                    if (mot_mb[7].sad - PREF_NULL_VEC < sad16 && mot_mb[7].sad - PREF_NULL_VEC < sad8)
                    {
                        mot_mb[0].sad = mot_mb[7].sad - PREF_NULL_VEC;
                        mot_mb[0].x = mot_mb[0].y = 0;
                        *mode_mb = MODE_INTER;
                    }
                    /******************************************************/
#endif
                    if (*mode_mb == MODE_INTER)
                    {
                        if (mot_mb[0].x == 0 && mot_mb[0].y == 0)   /* use zero vector */
                            mot_mb[0].sad += PREF_NULL_VEC; /* add back the bias */

                        mot_mb[1].sad = mot_mb[2].sad = mot_mb[3].sad = mot_mb[4].sad = (mot_mb[0].sad + 2) >> 2;
                        mot_mb[1].x = mot_mb[2].x = mot_mb[3].x = mot_mb[4].x = mot_mb[0].x;
                        mot_mb[1].y = mot_mb[2].y = mot_mb[3].y = mot_mb[4].y = mot_mb[0].y;

                    }
                }

                /* find maximum magnitude */
                /* compute average SAD for rate control, 11/28/00 */
                if (*mode_mb == MODE_INTER)
                {
#ifdef PRINT_MV
                    fp_debug = fopen("c:\\bitstream\\mv1_debug.txt", "a");
                    fprintf(fp_debug, "%d MODE_INTER\n", mbnum);
                    fclose(fp_debug);
#endif
                    totalSAD += mot_mb[0].sad;
                    if (mot_mb[0].x > max_mag)
                        max_mag = mot_mb[0].x;
                    if (mot_mb[0].y > max_mag)
                        max_mag = mot_mb[0].y;
                    if (mot_mb[0].x < min_mag)
                        min_mag = mot_mb[0].x;
                    if (mot_mb[0].y < min_mag)
                        min_mag = mot_mb[0].y;
                }
                else if (*mode_mb == MODE_INTER4V)
                {
#ifdef PRINT_MV
                    fp_debug = fopen("c:\\bitstream\\mv1_debug.txt", "a");
                    fprintf(fp_debug, "%d MODE_INTER4V\n", mbnum);
                    fclose(fp_debug);
#endif
                    totalSAD += sad8;
                    for (comp = 1; comp <= 4; comp++)
                    {
                        if (mot_mb[comp].x > max_mag)
                            max_mag = mot_mb[comp].x;
                        if (mot_mb[comp].y > max_mag)
                            max_mag = mot_mb[comp].y;
                        if (mot_mb[comp].x < min_mag)
                            min_mag = mot_mb[comp].x;
                        if (mot_mb[comp].y < min_mag)
                            min_mag = mot_mb[comp].y;
                    }
                }
                else    /* MODE_INTRA */
                {
#ifdef PRINT_MV
                    fp_debug = fopen("c:\\bitstream\\mv1_debug.txt", "a");
                    fprintf(fp_debug, "%d MODE_INTRA\n", mbnum);
                    fclose(fp_debug);
#endif
                    totalSAD += mot_mb[0].sad;
                }
                mbnum += incr_i;
                offset += (incr_i << 4);

            }
        }

        if (incr_i > 1 && numLoop) /* scene change on and first loop */
        {
            //if(numIntra > ((totalMB>>3)<<1) + (totalMB>>3)) /* 75% of 50%MBs */
            if (numIntra > (0.30*(totalMB / 2.0))) /* 15% of 50%MBs */
            {
                /******** scene change detected *******************/
                currVop->predictionType = I_VOP;
                M4VENC_MEMSET(Mode, MODE_INTRA, sizeof(UChar)*totalMB); /* set this for MB level coding*/
                currVop->quantizer = video->encParams->InitQuantIvop[video->currLayer];

                /* compute the SAV for rate control & fast DCT */
                totalSAD = 0;
                offset = 0;
                mbnum = 0;
                cur = currFrame->yChan;

                for (j = 0; j < mbheight; j++)
                {
                    for (i = 0; i < mbwidth; i++)
                    {
                        video->mbnum = mbnum;
                        mot_mb = mot[mbnum];


                        (*ComputeMBSum)(cur + (i << 4), width, mot_mb);
                        totalSAD += mot_mb[0].sad;

                        mbnum++;
                    }
                    cur += (width << 4);
                }

                video->sumMAD = (float)totalSAD / (float)NumPixelMB;
                ResetIntraUpdate(intraArray, totalMB);
                /* video->numVopsInGOP=0; 3/13/01 move it to vop.c*/

                return ;
            }
        }
        /******** no scene change, continue motion search **********************/
        start_i = 0;
        type_pred++; /* second pass */
    }

    video->sumMAD = (float)totalSAD / (float)NumPixelMB;    /* avg SAD */

    /* find f_code , 10/27/2000 */
    f_code_p = 1;
    while ((max_mag >> (4 + f_code_p)) > 0)
        f_code_p++;

    f_code_n = 1;
    min_mag *= -1;
    while ((min_mag - 1) >> (4 + f_code_n) > 0)
        f_code_n++;

    currVop->fcodeForward = (f_code_p > f_code_n ? f_code_p : f_code_n);

#ifdef HTFM
    /***** HYPOTHESIS TESTING ********/  /* 2/28/01 */
    if (collect)
    {
        collect = 0;
        UpdateHTFM(video, newvar, exp_lamda, &htfm_stat);
    }
    /*********************************/
#endif

    return ;
}


#ifdef HTFM
void InitHTFM(VideoEncData *video, HTFM_Stat *htfm_stat, double *newvar, Int *collect)
{
    Int i;
    Int lx = video->currVop->width; //  padding
    Int lx2 = lx << 1;
    Int lx3 = lx2 + lx;
    Int rx = video->currVop->pitch;
    Int rx2 = rx << 1;
    Int rx3 = rx2 + rx;

    Int *offset, *offset2;

    /* 4/11/01, collect data every 30 frames, doesn't have to be base layer */
    if (((Int)video->numVopsInGOP) % 30 == 1)
    {

        *collect = 1;

        htfm_stat->countbreak = 0;
        htfm_stat->abs_dif_mad_avg = 0;

        for (i = 0; i < 16; i++)
        {
            newvar[i] = 0.0;
        }
//      video->functionPointer->SAD_MB_PADDING = &SAD_MB_PADDING_HTFM_Collect;
        video->functionPointer->SAD_Macroblock = &SAD_MB_HTFM_Collect;
        video->functionPointer->SAD_MB_HalfPel[0] = NULL;
        video->functionPointer->SAD_MB_HalfPel[1] = &SAD_MB_HP_HTFM_Collectxh;
        video->functionPointer->SAD_MB_HalfPel[2] = &SAD_MB_HP_HTFM_Collectyh;
        video->functionPointer->SAD_MB_HalfPel[3] = &SAD_MB_HP_HTFM_Collectxhyh;
        video->sad_extra_info = (void*)(htfm_stat);
        offset = htfm_stat->offsetArray;
        offset2 = htfm_stat->offsetRef;
    }
    else
    {
//      video->functionPointer->SAD_MB_PADDING = &SAD_MB_PADDING_HTFM;
        video->functionPointer->SAD_Macroblock = &SAD_MB_HTFM;
        video->functionPointer->SAD_MB_HalfPel[0] = NULL;
        video->functionPointer->SAD_MB_HalfPel[1] = &SAD_MB_HP_HTFMxh;
        video->functionPointer->SAD_MB_HalfPel[2] = &SAD_MB_HP_HTFMyh;
        video->functionPointer->SAD_MB_HalfPel[3] = &SAD_MB_HP_HTFMxhyh;
        video->sad_extra_info = (void*)(video->nrmlz_th);
        offset = video->nrmlz_th + 16;
        offset2 = video->nrmlz_th + 32;
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

void UpdateHTFM(VideoEncData *video, double *newvar, double *exp_lamda, HTFM_Stat *htfm_stat)
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

    CalcThreshold(HTFM_Pf, exp_lamda, video->nrmlz_th);
    return ;
}


void CalcThreshold(double pf, double exp_lamda[], Int nrmlz_th[])
{
    Int i;
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
        nrmlz_th[i] = (Int)(temp[i] * ((i + 1) << 4) + 0.5);

    return ;
}

void    HTFMPrepareCurMB(VideoEncData *video, HTFM_Stat *htfm_stat, UChar *cur)
{
    void* tmp = (void*)(video->currYMB);
    ULong *htfmMB = (ULong*)tmp;
    UChar *ptr, byte;
    Int *offset;
    Int i;
    ULong word;
    Int width = video->currVop->width;

    if (((Int)video->numVopsInGOP) % 30 == 1)
    {
        offset = htfm_stat->offsetArray;
    }
    else
    {
        offset = video->nrmlz_th + 16;
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

        word = *(ptr += (width << 2));
        byte = ptr[4];
        word |= (byte << 8);
        byte = ptr[8];
        word |= (byte << 16);
        byte = ptr[12];
        word |= (byte << 24);
        *htfmMB++ = word;

        word = *(ptr += (width << 2));
        byte = ptr[4];
        word |= (byte << 8);
        byte = ptr[8];
        word |= (byte << 16);
        byte = ptr[12];
        word |= (byte << 24);
        *htfmMB++ = word;

        word = *(ptr += (width << 2));
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


#endif

void    PrepareCurMB(VideoEncData *video, UChar *cur)
{
    void* tmp = (void*)(video->currYMB);
    ULong *currYMB = (ULong*)tmp;
    Int i;
    Int width = video->currVop->width;

    cur -= width;

    for (i = 0; i < 16; i++)
    {
        *currYMB++ = *((ULong*)(cur += width));
        *currYMB++ = *((ULong*)(cur + 4));
        *currYMB++ = *((ULong*)(cur + 8));
        *currYMB++ = *((ULong*)(cur + 12));
    }

    return ;
}


/*==================================================================
    Function:   MBMotionSearch
    Date:       09/06/2000
    Purpose:    Perform motion estimation for a macroblock.
                Find 1MV and 4MVs in half-pels resolutions.
                Using ST1 algorithm provided by Chalidabhongse and Kuo
                CSVT March'98.

==================================================================*/

void MBMotionSearch(VideoEncData *video, UChar *cur, UChar *best_cand[],
                    Int i0, Int j0, Int type_pred, Int FS_en, Int *hp_guess)
{
    Vol *currVol = video->vol[video->currLayer];
    UChar *ref, *cand, *ncand = NULL, *cur8;
    void *extra_info = video->sad_extra_info;
    Int mbnum = video->mbnum;
    Int width = video->currVop->width; /* 6/12/01, must be multiple of 16 */
    Int height = video->currVop->height;
    MOT **mot = video->mot;
    UChar use_4mv = video->encParams->MV8x8_Enabled;
    UChar h263_mode = video->encParams->H263_Enabled;
    Int(*SAD_Macroblock)(UChar*, UChar*, Int, void*) = video->functionPointer->SAD_Macroblock;
    Int(*SAD_Block)(UChar*, UChar*, Int, Int, void*) = video->functionPointer->SAD_Block;
    VideoEncParams *encParams = video->encParams;
    Int range = encParams->SearchRange;

    Int lx = video->currVop->pitch; /* padding */
    Int comp;
    Int i, j, imin, jmin, ilow, ihigh, jlow, jhigh, iorg, jorg;
    Int d, dmin, dn[9];
#if (ZERO_MV_PREF==1)   /* compute (0,0) MV at the end */
    Int d0;
#endif
    Int k;
    Int mvx[5], mvy[5], imin0, jmin0;
    Int num_can, center_again;
    Int last_loc, new_loc = 0;
    Int step, max_step = range >> 1;
    Int next;

    ref = video->forwardRefVop->yChan; /* origin of actual frame */

    cur = video->currYMB; /* use smaller memory space for current MB */

    /*  find limit of the search (adjusting search range)*/

    if (!h263_mode)
    {
        ilow = i0 - range;
        if (ilow < -15)
            ilow = -15;
        ihigh = i0 + range - 1;
        if (ihigh > width - 1)
            ihigh = width - 1;
        jlow = j0 - range;
        if (jlow < -15)
            jlow = -15;
        jhigh = j0 + range - 1;
        if (jhigh > height - 1)
            jhigh = height - 1;
    }
    else
    {
        ilow = i0 - range;
        if (ilow < 0)
            ilow = 0;
        ihigh = i0 + range - 1;
        if (ihigh > width - 16)
            ihigh = width - 16;
        jlow = j0 - range;
        if (jlow < 0)
            jlow = 0;
        jhigh = j0 + range - 1;
        if (jhigh > height - 16)
            jhigh = height - 16;
    }

    imin = i0;
    jmin = j0; /* needed for fullsearch */
    ncand = ref + imin + jmin * lx;

    /* for first row of MB, fullsearch can be used */
    if (FS_en)
    {
        *hp_guess = 0; /* no guess for fast half-pel */

        dmin =  fullsearch(video, currVol, ref, cur, &imin, &jmin, ilow, ihigh, jlow, jhigh);

        ncand = ref + imin + jmin * lx;

        mot[mbnum][0].sad = dmin;
        mot[mbnum][0].x = (imin - i0) << 1;
        mot[mbnum][0].y = (jmin - j0) << 1;
        imin0 = imin << 1;  /* 16x16 MV in half-pel resolution */
        jmin0 = jmin << 1;
        best_cand[0] = ncand;
    }
    else
    {   /* 4/7/01, modified this testing for fullsearch the top row to only upto (0,3) MB */
        /*            upto 30% complexity saving with the same complexity */
        if (video->forwardRefVop->predictionType == I_VOP && j0 == 0 && i0 <= 64 && type_pred != 1)
        {
            *hp_guess = 0; /* no guess for fast half-pel */
            dmin =  fullsearch(video, currVol, ref, cur, &imin, &jmin, ilow, ihigh, jlow, jhigh);
            ncand = ref + imin + jmin * lx;
        }
        else
        {
            /************** initialize candidate **************************/
            /* find initial motion vector */
            CandidateSelection(mvx, mvy, &num_can, i0 >> 4, j0 >> 4, video, type_pred);

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

                    if (d < dmin)
                    {
                        dmin = d;
                        imin = i;
                        jmin = j;
                        ncand = cand;
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

                        if (d < dmin)
                        {
                            dmin = d;
                            imin = i;
                            jmin = j;
                            ncand = cand;
                        }
                        else if ((d == dmin) && PV_ABS(mvx[k]) + PV_ABS(mvy[k]) < PV_ABS(i0 - imin) + PV_ABS(j0 - jmin))
                        {
                            dmin = d;
                            imin = i;
                            jmin = j;
                            ncand = cand;
                        }
                    }
                }
            }
            if (num_can == 0 || dmin == 65535) /* no candidate selected */
            {
                ncand = ref + i0 + j0 * lx; /* use (0,0) MV as initial value */
                mot[mbnum][7].sad = dmin = (*SAD_Macroblock)(ncand, cur, (65535 << 16) | lx, extra_info);
#if (ZERO_MV_PREF==1)   /* compute (0,0) MV at the end */
                d0 = dmin;
#endif
                imin = i0;
                jmin = j0;
            }

#if (ZERO_MV_PREF==0)  /*  COMPUTE ZERO VECTOR FIRST !!!!!*/
            dmin -= PREF_NULL_VEC;
#endif

            /******************* local refinement ***************************/
            center_again = 0;
            last_loc = new_loc = 0;
            //          ncand = ref + jmin*lx + imin;  /* center of the search */
            step = 0;
            dn[0] = dmin;
            while (!center_again && step <= max_step)
            {

                MoveNeighborSAD(dn, last_loc);

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
                            dn[k] = d; /* keep it for half pel use */

                            if (d < dmin)
                            {
                                ncand = cand;
                                dmin = d;
                                imin = i;
                                jmin = j;
                                center_again = 0;
                                new_loc = k;
                            }
                            else if ((d == dmin) && PV_ABS(i0 - i) + PV_ABS(j0 - j) < PV_ABS(i0 - imin) + PV_ABS(j0 - jmin))
                            {
                                ncand = cand;
                                imin = i;
                                jmin = j;
                                center_again = 0;
                                new_loc = k;
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
                MoveNeighborSAD(dn, last_loc);

            *hp_guess = FindMin(dn);

        }

#if (ZERO_MV_PREF==1)   /* compute (0,0) MV at the end */
        if (d0 - PREF_NULL_VEC < dmin)
        {
            ncand = ref + i0 + j0 * lx;
            dmin = d0;
            imin = i0;
            jmin = j0;
        }
#endif
        mot[mbnum][0].sad = dmin;
        mot[mbnum][0].x = (imin - i0) << 1;
        mot[mbnum][0].y = (jmin - j0) << 1;
        imin0 = imin << 1;  /* 16x16 MV in half-pel resolution */
        jmin0 = jmin << 1;
        best_cand[0] = ncand;
    }
    /* imin and jmin is the best 1 MV */
#ifndef NO_INTER4V
    /*******************  Find 4 motion vectors ****************************/
    if (use_4mv && !h263_mode)
    {
#ifdef _SAD_STAT
        num_Blk += 4;
#endif
        /* starting from the best 1MV */
        //offset = imin + jmin*lx;
        iorg = i0;
        jorg = j0;

        for (comp = 0; comp < 4; comp++)
        {
            i0 = iorg + ((comp & 1) << 3);
            j0 = jorg + ((comp & 2) << 2);

            imin = (imin0 >> 1) + ((comp & 1) << 3);    /* starting point from 16x16 MV */
            jmin = (jmin0 >> 1) + ((comp & 2) << 2);
            ncand = ref + imin + jmin * lx;

            cur8 = cur + ((comp & 1) << 3) + (((comp & 2) << 2) << 4) ; /* 11/30/05, smaller cache */

            /*  find limit of the search (adjusting search range)*/
            ilow = i0 - range;
            ihigh = i0 + range - 1 ;/* 4/9/01 */
            if (ilow < -15)
                ilow = -15;
            if (ihigh > width - 1)
                ihigh = width - 1;
            jlow = j0 - range;
            jhigh = j0 + range - 1 ;/* 4/9/01 */
            if (jlow < -15)
                jlow = -15;
            if (jhigh > height - 1)
                jhigh = height - 1;

            SAD_Block = video->functionPointer->SAD_Block;

            if (FS_en)  /* fullsearch enable, center around 16x16 MV */
            {
                dmin =  fullsearchBlk(video, currVol, ncand, cur8, &imin, &jmin, ilow, ihigh, jlow, jhigh, range);
                ncand = ref + imin + jmin * lx;

                mot[mbnum][comp+1].sad = dmin;
                mot[mbnum][comp+1].x = (imin - i0) << 1;
                mot[mbnum][comp+1].y = (jmin - j0) << 1;
                best_cand[comp+1] = ncand;
            }
            else    /* no fullsearch, do local search */
            {
                /* starting point from 16x16 */
                dmin = (*SAD_Block)(ncand, cur8, 65536, lx, extra_info);

                /******************* local refinement ***************************/
                center_again = 0;
                last_loc = 0;

                while (!center_again)
                {
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
                                d = (*SAD_Block)(cand, cur8, dmin, lx, extra_info);

                                if (d < dmin)
                                {
                                    ncand = cand;
                                    dmin = d;
                                    imin = i;
                                    jmin = j;
                                    center_again = 0;
                                    new_loc = k;
                                }
                                else if ((d == dmin) &&
                                         PV_ABS(i0 - i) + PV_ABS(j0 - j) < PV_ABS(i0 - imin) + PV_ABS(j0 - jmin))
                                {
                                    ncand = cand;
                                    imin = i;
                                    jmin = j;
                                    center_again = 0;
                                    new_loc = k;
                                }
                            }
                        }
                        if (k == 8)  /* end side search*/
                        {
                            if (!center_again)
                            {
                                k = -1; /* start diagonal search */
                                if (j <= height - 1 && j > 0)   cand -= lx;
                                j--;
                            }
                        }
                        else
                        {
                            next = refine_next[k][0];
                            cand += next;
                            i += next;
                            next = refine_next[k][1];
                            cand += lx * next;
                            j += next;
                        }
                    }
                    last_loc = new_loc;
                }
                mot[mbnum][comp+1].sad = dmin;
                mot[mbnum][comp+1].x = (imin - i0) << 1;
                mot[mbnum][comp+1].y = (jmin - j0) << 1;
                best_cand[comp+1] = ncand;
            }
            /********************************************/
        }
    }
    else
#endif  /* NO_INTER4V */
    {
        mot[mbnum][1].sad = mot[mbnum][2].sad = mot[mbnum][3].sad = mot[mbnum][4].sad = (dmin + 2) >> 2;
        mot[mbnum][1].x = mot[mbnum][2].x = mot[mbnum][3].x = mot[mbnum][4].x = mot[mbnum][0].x;
        mot[mbnum][1].y = mot[mbnum][2].y = mot[mbnum][3].y = mot[mbnum][4].y = mot[mbnum][0].y;
        best_cand[1] = best_cand[2] = best_cand[3] = best_cand[4] = ncand;

    }
    return ;
}


/*===============================================================================
    Function:   fullsearch
    Date:       09/16/2000
    Purpose:    Perform full-search motion estimation over the range of search
                region in a spiral-outward manner.
    Input/Output:   VideoEncData, current Vol, previou Vop, pointer to the left corner of
                current VOP, current coord (also output), boundaries.
===============================================================================*/

Int fullsearch(VideoEncData *video, Vol *currVol, UChar *prev, UChar *cur,
               Int *imin, Int *jmin, Int ilow, Int ihigh, Int jlow, Int jhigh)
{
    Int range = video->encParams->SearchRange;
    UChar *cand;
    Int i, j, k, l;
    Int d, dmin;
    Int i0 = *imin; /* current position */
    Int j0 = *jmin;
    Int(*SAD_Macroblock)(UChar*, UChar*, Int, void*) = video->functionPointer->SAD_Macroblock;
    void *extra_info = video->sad_extra_info;
//  UChar h263_mode = video->encParams->H263_Enabled;
    Int lx = video->currVop->pitch; /* with padding */

    Int offset = i0 + j0 * lx;

    OSCL_UNUSED_ARG(currVol);

    cand = prev + offset;

    dmin  = (*SAD_Macroblock)(cand, cur, (65535 << 16) | lx, (void*)extra_info) - PREF_NULL_VEC;

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

                if (d < dmin)
                {
                    dmin = d;
                    *imin = i;
                    *jmin = j;
                }
                else if ((d == dmin) && PV_ABS(i0 - i) + PV_ABS(j0 - j) < PV_ABS(i0 - *imin) + PV_ABS(j0 - *jmin))
                {
                    dmin = d;
                    *imin = i;
                    *jmin = j;
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

    return dmin;
}

#ifndef NO_INTER4V
/*===============================================================================
    Function:   fullsearchBlk
    Date:       01/9/2001
    Purpose:    Perform full-search motion estimation of an 8x8 block over the range
                of search region in a spiral-outward manner centered at the 16x16 MV.
    Input/Output:   VideoEncData, MB coordinate, pointer to the initial MV on the
                reference, pointer to coor of current block, search range.
===============================================================================*/
Int fullsearchBlk(VideoEncData *video, Vol *currVol, UChar *cent, UChar *cur,
                  Int *imin, Int *jmin, Int ilow, Int ihigh, Int jlow, Int jhigh, Int range)
{
    UChar *cand, *ref;
    Int i, j, k, l, istart, jstart;
    Int d, dmin;
    Int lx = video->currVop->pitch; /* with padding */
    Int(*SAD_Block)(UChar*, UChar*, Int, Int, void*) = video->functionPointer->SAD_Block;
    void *extra_info = video->sad_extra_info;

    OSCL_UNUSED_ARG(currVol);

    /* starting point centered at 16x16 MV */
    ref = cent;
    istart = *imin;
    jstart = *jmin;

    dmin = (*SAD_Block)(ref, cur, 65536, lx, (void*)extra_info);

    cand = ref;
    /* perform spiral search */
    for (k = 1; k <= range; k++)
    {

        i = istart - k;
        j = jstart - k;
        cand -= (lx + 1);  /* candidate region */

        for (l = 0; l < 8*k; l++)
        {
            /* no need for boundary checking again */
            if (i >= ilow && i <= ihigh && j >= jlow && j <= jhigh)
            {
                d = (*SAD_Block)(cand, cur, dmin, lx, (void*)extra_info);

                if (d < dmin)
                {
                    dmin = d;
                    *imin = i;
                    *jmin = j;
                }
                else if ((d == dmin) &&
                         PV_ABS(istart - i) + PV_ABS(jstart - j) < PV_ABS(istart - *imin) + PV_ABS(jstart - *jmin))
                {
                    dmin = d;
                    *imin = i;
                    *jmin = j;
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

    return dmin;
}
#endif /* NO_INTER4V */

/*===============================================================================
    Function:   CandidateSelection
    Date:       09/16/2000
    Purpose:    Fill up the list of candidate using spatio-temporal correlation
                among neighboring blocks.
    Input/Output:   type_pred = 0: first pass, 1: second pass, or no SCD
    Modified:    09/23/01, get rid of redundant candidates before passing back.
===============================================================================*/

void CandidateSelection(Int *mvx, Int *mvy, Int *num_can, Int imb, Int jmb,
                        VideoEncData *video, Int type_pred)
{
    MOT **mot = video->mot;
    MOT *pmot;
    Int mbnum = video->mbnum;
    Vol *currVol = video->vol[video->currLayer];
    Int mbwidth = currVol->nMBPerRow;
    Int mbheight = currVol->nMBPerCol;
    Int i, j, same, num1;

    *num_can = 0;

    if (video->forwardRefVop->predictionType == P_VOP)
    {
        /* Spatio-Temporal Candidate (five candidates) */
        if (type_pred == 0) /* first pass */
        {
            pmot = &mot[mbnum][0]; /* same coordinate previous frame */
            mvx[(*num_can)] = (pmot->x) >> 1;
            mvy[(*num_can)++] = (pmot->y) >> 1;
            if (imb >= (mbwidth >> 1) && imb > 0)  /*left neighbor previous frame */
            {
                pmot = &mot[mbnum-1][0];
                mvx[(*num_can)] = (pmot->x) >> 1;
                mvy[(*num_can)++] = (pmot->y) >> 1;
            }
            else if (imb + 1 < mbwidth)   /*right neighbor previous frame */
            {
                pmot = &mot[mbnum+1][0];
                mvx[(*num_can)] = (pmot->x) >> 1;
                mvy[(*num_can)++] = (pmot->y) >> 1;
            }

            if (jmb < mbheight - 1)  /*bottom neighbor previous frame */
            {
                pmot = &mot[mbnum+mbwidth][0];
                mvx[(*num_can)] = (pmot->x) >> 1;
                mvy[(*num_can)++] = (pmot->y) >> 1;
            }
            else if (jmb > 0)   /*upper neighbor previous frame */
            {
                pmot = &mot[mbnum-mbwidth][0];
                mvx[(*num_can)] = (pmot->x) >> 1;
                mvy[(*num_can)++] = (pmot->y) >> 1;
            }

            if (imb > 0 && jmb > 0)  /* upper-left neighbor current frame*/
            {
                pmot = &mot[mbnum-mbwidth-1][0];
                mvx[(*num_can)] = (pmot->x) >> 1;
                mvy[(*num_can)++] = (pmot->y) >> 1;
            }
            if (jmb > 0 && imb < mbheight - 1)  /* upper right neighbor current frame*/
            {
                pmot = &mot[mbnum-mbwidth+1][0];
                mvx[(*num_can)] = (pmot->x) >> 1;
                mvy[(*num_can)++] = (pmot->y) >> 1;
            }
        }
        else    /* second pass */
            /* original ST1 algorithm */
        {
            pmot = &mot[mbnum][0]; /* same coordinate previous frame */
            mvx[(*num_can)] = (pmot->x) >> 1;
            mvy[(*num_can)++] = (pmot->y) >> 1;

            if (imb > 0)  /*left neighbor current frame */
            {
                pmot = &mot[mbnum-1][0];
                mvx[(*num_can)] = (pmot->x) >> 1;
                mvy[(*num_can)++] = (pmot->y) >> 1;
            }
            if (jmb > 0)  /*upper neighbor current frame */
            {
                pmot = &mot[mbnum-mbwidth][0];
                mvx[(*num_can)] = (pmot->x) >> 1;
                mvy[(*num_can)++] = (pmot->y) >> 1;
            }
            if (imb < mbwidth - 1)  /*right neighbor previous frame */
            {
                pmot = &mot[mbnum+1][0];
                mvx[(*num_can)] = (pmot->x) >> 1;
                mvy[(*num_can)++] = (pmot->y) >> 1;
            }
            if (jmb < mbheight - 1)  /*bottom neighbor previous frame */
            {
                pmot = &mot[mbnum+mbwidth][0];
                mvx[(*num_can)] = (pmot->x) >> 1;
                mvy[(*num_can)++] = (pmot->y) >> 1;
            }
        }
    }
    else  /* only Spatial Candidate (four candidates)*/
    {
        if (type_pred == 0) /*first pass*/
        {
            if (imb > 1)  /* neighbor two blocks away to the left */
            {
                pmot = &mot[mbnum-2][0];
                mvx[(*num_can)] = (pmot->x) >> 1;
                mvy[(*num_can)++] = (pmot->y) >> 1;
            }
            if (imb > 0 && jmb > 0)  /* upper-left neighbor */
            {
                pmot = &mot[mbnum-mbwidth-1][0];
                mvx[(*num_can)] = (pmot->x) >> 1;
                mvy[(*num_can)++] = (pmot->y) >> 1;
            }
            if (jmb > 0 && imb < mbheight - 1)  /* upper right neighbor */
            {
                pmot = &mot[mbnum-mbwidth+1][0];
                mvx[(*num_can)] = (pmot->x) >> 1;
                mvy[(*num_can)++] = (pmot->y) >> 1;
            }
        }
//#ifdef SCENE_CHANGE_DETECTION
        /* second pass (ST2 algorithm)*/
        else if (type_pred == 1) /* 4/7/01 */
        {
            if (imb > 0)  /*left neighbor current frame */
            {
                pmot = &mot[mbnum-1][0];
                mvx[(*num_can)] = (pmot->x) >> 1;
                mvy[(*num_can)++] = (pmot->y) >> 1;
            }
            if (jmb > 0)  /*upper neighbor current frame */
            {
                pmot = &mot[mbnum-mbwidth][0];
                mvx[(*num_can)] = (pmot->x) >> 1;
                mvy[(*num_can)++] = (pmot->y) >> 1;
            }
            if (imb < mbwidth - 1)  /*right neighbor current frame */
            {
                pmot = &mot[mbnum+1][0];
                mvx[(*num_can)] = (pmot->x) >> 1;
                mvy[(*num_can)++] = (pmot->y) >> 1;
            }
            if (jmb < mbheight - 1)  /*bottom neighbor current frame */
            {
                pmot = &mot[mbnum+mbwidth][0];
                mvx[(*num_can)] = (pmot->x) >> 1;
                mvy[(*num_can)++] = (pmot->y) >> 1;
            }
        }
//#else
        else /* original ST1 algorithm */
        {
            if (imb > 0)  /*left neighbor current frame */
            {
                pmot = &mot[mbnum-1][0];
                mvx[(*num_can)] = (pmot->x) >> 1;
                mvy[(*num_can)++] = (pmot->y) >> 1;

                if (jmb > 0)  /*upper-left neighbor current frame */
                {
                    pmot = &mot[mbnum-mbwidth-1][0];
                    mvx[(*num_can)] = (pmot->x) >> 1;
                    mvy[(*num_can)++] = (pmot->y) >> 1;
                }

            }
            if (jmb > 0)  /*upper neighbor current frame */
            {
                pmot = &mot[mbnum-mbwidth][0];
                mvx[(*num_can)] = (pmot->x) >> 1;
                mvy[(*num_can)++] = (pmot->y) >> 1;

                if (imb < mbheight - 1)  /*upper-right neighbor current frame */
                {
                    pmot = &mot[mbnum-mbwidth+1][0];
                    mvx[(*num_can)] = (pmot->x) >> 1;
                    mvy[(*num_can)++] = (pmot->y) >> 1;
                }
            }
        }
//#endif
    }

    /* 3/23/01, remove redundant candidate (possible k-mean) */
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
            // modified k-mean, 3/24/01, shouldn't be greater than 3
            if (PV_ABS(mvx[i] - mvx[j]) + PV_ABS(mvy[i] - mvy[j]) < CANDIDATE_DISTANCE)
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

#ifdef _SAD_STAT
    num_cand += (*num_can);
#endif

    if (num1 == 5 && *num_can == 1)
        *num_can = ALL_CAND_EQUAL; /* all are equal */

    return ;
}

/*===========================================================================
    Function:   RasterIntraUpdate
    Date:       2/26/01
    Purpose:    To raster-scan assign INTRA-update .
                N macroblocks are updated (also was programmable).
===========================================================================*/
void RasterIntraUpdate(UChar *intraArray, UChar *Mode, Int totalMB, Int numRefresh)
{
    Int indx, i;

    /* find the last refresh MB */
    indx = 0;
    while (intraArray[indx] == 1 && indx < totalMB)
        indx++;

    /* add more  */
    for (i = 0; i < numRefresh && indx < totalMB; i++)
    {
        Mode[indx] = MODE_INTRA;
        intraArray[indx++] = 1;
    }

    /* if read the end of frame, reset and loop around */
    if (indx >= totalMB - 1)
    {
        ResetIntraUpdate(intraArray, totalMB);
        indx = 0;
        while (i < numRefresh && indx < totalMB)
        {
            intraArray[indx] = 1;
            Mode[indx++] = MODE_INTRA;
            i++;
        }
    }

    return ;
}

/*===========================================================================
    Function:   ResetIntraUpdate
    Date:       11/28/00
    Purpose:    Reset already intra updated flags to all zero
===========================================================================*/

void ResetIntraUpdate(UChar *intraArray, Int totalMB)
{
    M4VENC_MEMSET(intraArray, 0, sizeof(UChar)*totalMB);
    return ;
}

/*===========================================================================
    Function:   ResetIntraUpdateRegion
    Date:       12/1/00
    Purpose:    Reset already intra updated flags in one region to all zero
===========================================================================*/
void ResetIntraUpdateRegion(UChar *intraArray, Int start_i, Int rwidth,
                            Int start_j, Int rheight, Int mbwidth, Int mbheight)
{
    Int indx, j;

    if (start_i + rwidth >= mbwidth)
        rwidth = mbwidth - start_i;
    if (start_j + rheight >= mbheight)
        rheight = mbheight - start_j;

    for (j = start_j; j < start_j + rheight; j++)
    {
        indx = j * mbwidth;
        M4VENC_MEMSET(intraArray + indx + start_i, 0, sizeof(UChar)*rwidth);
    }

    return ;
}

/*************************************************************
    Function:   MoveNeighborSAD
    Date:       3/27/01
    Purpose:    Move neighboring SAD around when center has shifted
*************************************************************/

void MoveNeighborSAD(Int dn[], Int new_loc)
{
    Int tmp[9];
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

/* 3/28/01, find minimal of dn[9] */

Int FindMin(Int dn[])
{
    Int min, i;
    Int dmin;

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



