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
#include <math.h>

/* rate control variables */
#define RC_MAX_QUANT 51
#define RC_MIN_QUANT 0   //cap to 10 to prevent rate fluctuation    

#define MAD_MIN 1 /* handle the case of devision by zero in RC */


/* local functions */
double QP2Qstep(int QP);
int Qstep2QP(double Qstep);

double ComputeFrameMAD(AVCCommonObj *video, AVCRateControl *rateCtrl);

void targetBitCalculation(AVCEncObject *encvid, AVCCommonObj *video, AVCRateControl *rateCtrl, MultiPass *pMP);

void calculateQuantizer_Multipass(AVCEncObject *encvid, AVCCommonObj *video,
                                  AVCRateControl *rateCtrl, MultiPass *pMP);

void updateRC_PostProc(AVCRateControl *rateCtrl, MultiPass *pMP);

void AVCSaveRDSamples(MultiPass *pMP, int counter_samples);

void updateRateControl(AVCRateControl *rateControl, int nal_type);

int GetAvgFrameQP(AVCRateControl *rateCtrl)
{
    return rateCtrl->Qc;
}

AVCEnc_Status RCDetermineFrameNum(AVCEncObject *encvid, AVCRateControl *rateCtrl, uint32 modTime, uint *frameNum)
{
    AVCCommonObj *video = encvid->common;
    AVCSliceHeader *sliceHdr = video->sliceHdr;
    uint32 modTimeRef = encvid->modTimeRef;
    int32  currFrameNum ;
    int  frameInc;


    /* check with the buffer fullness to make sure that we have enough bits to encode this frame */
    /* we can use a threshold to guarantee minimum picture quality */
    /**********************************/

    /* for now, the default is to encode every frame, To Be Changed */
    if (rateCtrl->first_frame)
    {
        encvid->modTimeRef = modTime;
        encvid->wrapModTime = 0;
        encvid->prevFrameNum = 0;
        encvid->prevProcFrameNum = 0;

        *frameNum = 0;

        /* set frame type to IDR-frame */
        video->nal_unit_type = AVC_NALTYPE_IDR;
        sliceHdr->slice_type = AVC_I_ALL_SLICE;
        video->slice_type = AVC_I_SLICE;

        return AVCENC_SUCCESS;
    }
    else
    {
        if (modTime < modTimeRef) /* modTime wrapped around */
        {
            encvid->wrapModTime += ((uint32)0xFFFFFFFF - modTimeRef) + 1;
            encvid->modTimeRef = modTimeRef = 0;
        }
        modTime += encvid->wrapModTime; /* wrapModTime is non zero after wrap-around */

        currFrameNum = (int32)(((modTime - modTimeRef) * rateCtrl->frame_rate + 200) / 1000); /* add small roundings */

        if (currFrameNum <= (int32)encvid->prevProcFrameNum)
        {
            return AVCENC_FAIL;  /* this is a late frame do not encode it */
        }

        frameInc = currFrameNum - encvid->prevProcFrameNum;

        if (frameInc < rateCtrl->skip_next_frame + 1)
        {
            return AVCENC_FAIL;  /* frame skip required to maintain the target bit rate. */
        }

        RCUpdateBuffer(video, rateCtrl, frameInc - rateCtrl->skip_next_frame);  /* in case more frames dropped */

        *frameNum = currFrameNum;

        /* This part would be similar to DetermineVopType of m4venc */
        if ((*frameNum >= (uint)rateCtrl->idrPeriod && rateCtrl->idrPeriod > 0) || (*frameNum > video->MaxFrameNum)) /* first frame or IDR*/
        {
            /* set frame type to IDR-frame */
            if (rateCtrl->idrPeriod)
            {
                encvid->modTimeRef += (uint32)(rateCtrl->idrPeriod * 1000 / rateCtrl->frame_rate);
                *frameNum -= rateCtrl->idrPeriod;
            }
            else
            {
                encvid->modTimeRef += (uint32)(video->MaxFrameNum * 1000 / rateCtrl->frame_rate);
                *frameNum -= video->MaxFrameNum;
            }

            video->nal_unit_type = AVC_NALTYPE_IDR;
            sliceHdr->slice_type = AVC_I_ALL_SLICE;
            video->slice_type = AVC_I_SLICE;
            encvid->prevProcFrameNum = *frameNum;
        }
        else
        {
            video->nal_unit_type = AVC_NALTYPE_SLICE;
            sliceHdr->slice_type = AVC_P_ALL_SLICE;
            video->slice_type = AVC_P_SLICE;
            encvid->prevProcFrameNum = currFrameNum;
        }

    }

    return AVCENC_SUCCESS;
}

void RCUpdateBuffer(AVCCommonObj *video, AVCRateControl *rateCtrl, int frameInc)
{
    int tmp;
    MultiPass *pMP = rateCtrl->pMP;

    OSCL_UNUSED_ARG(video);

    if (rateCtrl->rcEnable == TRUE)
    {
        if (frameInc > 1)
        {
            tmp = rateCtrl->bitsPerFrame * (frameInc - 1);
            rateCtrl->VBV_fullness -= tmp;
            pMP->counter_BTsrc += 10 * (frameInc - 1);

            /* Check buffer underflow */
            if (rateCtrl->VBV_fullness < rateCtrl->low_bound)
            {
                rateCtrl->VBV_fullness = rateCtrl->low_bound; // -rateCtrl->Bs/2;
                rateCtrl->TMN_W = rateCtrl->VBV_fullness - rateCtrl->low_bound;
                pMP->counter_BTsrc = pMP->counter_BTdst + (int)((OsclFloat)(rateCtrl->Bs / 2 - rateCtrl->low_bound) / 2.0 / (pMP->target_bits_per_frame / 10));
            }
        }
    }
}


AVCEnc_Status InitRateControlModule(AVCHandle *avcHandle)
{
    AVCEncObject *encvid = (AVCEncObject*) avcHandle->AVCObject;
    AVCCommonObj *video = encvid->common;
    AVCRateControl *rateCtrl = encvid->rateCtrl;
    double L1, L2, L3, bpp;
    int qp;
    int i, j;

    rateCtrl->basicUnit = video->PicSizeInMbs;

    rateCtrl->MADofMB = (double*) avcHandle->CBAVC_Malloc(encvid->avcHandle->userData,
                        video->PicSizeInMbs * sizeof(double), DEFAULT_ATTR);

    if (!rateCtrl->MADofMB)
    {
        goto CLEANUP_RC;
    }

    if (rateCtrl->rcEnable == TRUE)
    {
        rateCtrl->pMP = (MultiPass*) avcHandle->CBAVC_Malloc(encvid->avcHandle->userData, sizeof(MultiPass), DEFAULT_ATTR);
        if (!rateCtrl->pMP)
        {
            goto CLEANUP_RC;
        }
        memset(rateCtrl->pMP, 0, sizeof(MultiPass));
        rateCtrl->pMP->encoded_frames = -1; /* forget about the very first I frame */

        /* RDInfo **pRDSamples */
        rateCtrl->pMP->pRDSamples = (RDInfo **)avcHandle->CBAVC_Malloc(encvid->avcHandle->userData, (30 * sizeof(RDInfo *)), DEFAULT_ATTR);
        if (!rateCtrl->pMP->pRDSamples)
        {
            goto CLEANUP_RC;
        }

        for (i = 0; i < 30; i++)
        {
            rateCtrl->pMP->pRDSamples[i] = (RDInfo *)avcHandle->CBAVC_Malloc(encvid->avcHandle->userData, (32 * sizeof(RDInfo)), DEFAULT_ATTR);
            if (!rateCtrl->pMP->pRDSamples[i])
            {
                goto CLEANUP_RC;
            }
            for (j = 0; j < 32; j++)    memset(&(rateCtrl->pMP->pRDSamples[i][j]), 0, sizeof(RDInfo));
        }
        rateCtrl->pMP->frameRange = (int)(rateCtrl->frame_rate * 1.0); /* 1.0s time frame*/
        rateCtrl->pMP->frameRange = AVC_MAX(rateCtrl->pMP->frameRange, 5);
        rateCtrl->pMP->frameRange = AVC_MIN(rateCtrl->pMP->frameRange, 30);

        rateCtrl->pMP->framePos = -1;


        rateCtrl->bitsPerFrame = (int32)(rateCtrl->bitRate / rateCtrl->frame_rate);

        /* BX rate control */
        rateCtrl->skip_next_frame = 0; /* must be initialized */

        rateCtrl->Bs = rateCtrl->cpbSize;
        rateCtrl->TMN_W = 0;
        rateCtrl->VBV_fullness = (int)(rateCtrl->Bs * 0.5); /* rateCtrl->Bs */
        rateCtrl->encoded_frames = 0;

        rateCtrl->TMN_TH = rateCtrl->bitsPerFrame;

        rateCtrl->max_BitVariance_num = (int)((OsclFloat)(rateCtrl->Bs - rateCtrl->VBV_fullness) / (rateCtrl->bitsPerFrame / 10.0)) - 5;
        if (rateCtrl->max_BitVariance_num < 0) rateCtrl->max_BitVariance_num += 5;

        // Set the initial buffer fullness
        /* According to the spec, the initial buffer fullness needs to be set to 1/3 */
        rateCtrl->VBV_fullness = (int)(rateCtrl->Bs / 3.0 - rateCtrl->Bs / 2.0); /* the buffer range is [-Bs/2, Bs/2] */
        rateCtrl->pMP->counter_BTsrc = (int)((rateCtrl->Bs / 2.0 - rateCtrl->Bs / 3.0) / (rateCtrl->bitsPerFrame / 10.0));
        rateCtrl->TMN_W = (int)(rateCtrl->VBV_fullness + rateCtrl->pMP->counter_BTsrc * (rateCtrl->bitsPerFrame / 10.0));

        rateCtrl->low_bound = -rateCtrl->Bs / 2;
        rateCtrl->VBV_fullness_offset = 0;

        /* Setting the bitrate and framerate */
        rateCtrl->pMP->bitrate = rateCtrl->bitRate;
        rateCtrl->pMP->framerate = rateCtrl->frame_rate;
        rateCtrl->pMP->target_bits_per_frame = rateCtrl->pMP->bitrate / rateCtrl->pMP->framerate;

        /*compute the initial QP*/
        bpp = 1.0 * rateCtrl->bitRate / (rateCtrl->frame_rate * (video->PicSizeInMbs << 8));
        if (video->PicWidthInSamplesL == 176)
        {
            L1 = 0.1;
            L2 = 0.3;
            L3 = 0.6;
        }
        else if (video->PicWidthInSamplesL == 352)
        {
            L1 = 0.2;
            L2 = 0.6;
            L3 = 1.2;
        }
        else
        {
            L1 = 0.6;
            L2 = 1.4;
            L3 = 2.4;
        }

        if (rateCtrl->initQP == 0)
        {
            if (bpp <= L1)
                qp = 35;
            else if (bpp <= L2)
                qp = 25;
            else if (bpp <= L3)
                qp = 20;
            else
                qp = 15;
            rateCtrl->initQP = qp;
        }

        rateCtrl->Qc = rateCtrl->initQP;
    }

    return AVCENC_SUCCESS;

CLEANUP_RC:

    CleanupRateControlModule(avcHandle);
    return AVCENC_MEMORY_FAIL;

}


void CleanupRateControlModule(AVCHandle *avcHandle)
{
    AVCEncObject *encvid = (AVCEncObject*) avcHandle->AVCObject;
    AVCRateControl *rateCtrl = encvid->rateCtrl;
    int i;

    if (rateCtrl->MADofMB)
    {
        avcHandle->CBAVC_Free(avcHandle->userData, (int)(rateCtrl->MADofMB));
    }

    if (rateCtrl->pMP)
    {
        if (rateCtrl->pMP->pRDSamples)
        {
            for (i = 0; i < 30; i++)
            {
                if (rateCtrl->pMP->pRDSamples[i])
                {
                    avcHandle->CBAVC_Free(avcHandle->userData, (int)rateCtrl->pMP->pRDSamples[i]);
                }
            }
            avcHandle->CBAVC_Free(avcHandle->userData, (int)rateCtrl->pMP->pRDSamples);
        }
        avcHandle->CBAVC_Free(avcHandle->userData, (int)(rateCtrl->pMP));
    }

    return ;
}

void RCInitGOP(AVCEncObject *encvid)
{
    /* in BX RC, there's no GOP-level RC */

    OSCL_UNUSED_ARG(encvid);

    return ;
}


void RCInitFrameQP(AVCEncObject *encvid)
{
    AVCCommonObj *video = encvid->common;
    AVCRateControl *rateCtrl = encvid->rateCtrl;
    AVCPicParamSet *picParam = video->currPicParams;
    MultiPass *pMP = rateCtrl->pMP;

    if (rateCtrl->rcEnable == TRUE)
    {
        /* frame layer rate control */
        if (rateCtrl->encoded_frames == 0)
        {
            video->QPy = rateCtrl->Qc = rateCtrl->initQP;
        }
        else
        {
            calculateQuantizer_Multipass(encvid, video, rateCtrl, pMP);
            video->QPy = rateCtrl->Qc;
        }

        rateCtrl->NumberofHeaderBits = 0;
        rateCtrl->NumberofTextureBits = 0;
        rateCtrl->numFrameBits = 0; // reset

        /* update pMP->framePos */
        if (++pMP->framePos == pMP->frameRange) pMP->framePos = 0;

        if (rateCtrl->T == 0)
        {
            pMP->counter_BTdst = (int)(rateCtrl->frame_rate * 7.5 + 0.5); /* 0.75s time frame */
            pMP->counter_BTdst = AVC_MIN(pMP->counter_BTdst, (int)(rateCtrl->max_BitVariance_num / 2 * 0.40)); /* 0.75s time frame may go beyond VBV buffer if we set the buffer size smaller than 0.75s */
            pMP->counter_BTdst = AVC_MAX(pMP->counter_BTdst, (int)((rateCtrl->Bs / 2 - rateCtrl->VBV_fullness) * 0.30 / (rateCtrl->TMN_TH / 10.0) + 0.5)); /* At least 30% of VBV buffer size/2 */
            pMP->counter_BTdst = AVC_MIN(pMP->counter_BTdst, 20); /* Limit the target to be smaller than 3C */

            pMP->target_bits = rateCtrl->T = rateCtrl->TMN_TH = (int)(rateCtrl->TMN_TH * (1.0 + pMP->counter_BTdst * 0.1));
            pMP->diff_counter = pMP->counter_BTdst;
        }

        /* collect the necessary data: target bits, actual bits, mad and QP */
        pMP->target_bits = rateCtrl->T;
        pMP->QP  = video->QPy;

        pMP->mad = (OsclFloat)rateCtrl->totalSAD / video->PicSizeInMbs; //ComputeFrameMAD(video, rateCtrl);
        if (pMP->mad < MAD_MIN) pMP->mad = MAD_MIN; /* MAD_MIN is defined as 1 in mp4def.h */

        pMP->bitrate = rateCtrl->bitRate; /* calculated in RCVopQPSetting */
        pMP->framerate = rateCtrl->frame_rate;

        /* first pass encoding */
        pMP->nRe_Quantized = 0;

    } // rcEnable
    else
    {
        video->QPy = rateCtrl->initQP;
    }

//  printf(" %d ",video->QPy);

    if (video->CurrPicNum == 0 && encvid->outOfBandParamSet == FALSE)
    {
        picParam->pic_init_qs_minus26 = 0;
        picParam->pic_init_qp_minus26 = video->QPy - 26;
    }

    // need this for motion estimation
    encvid->lambda_mode = QP2QUANT[AVC_MAX(0, video->QPy-SHIFT_QP)];
    encvid->lambda_motion = LAMBDA_FACTOR(encvid->lambda_mode);
    return ;
}

/* Mad based variable bit allocation + QP calculation with a new quadratic method */
void calculateQuantizer_Multipass(AVCEncObject *encvid, AVCCommonObj *video,
                                  AVCRateControl *rateCtrl, MultiPass *pMP)
{
    int prev_actual_bits = 0, curr_target, /*pos=0,*/i, j;
    OsclFloat Qstep, prev_QP = 0.625;

    OsclFloat curr_mad, prev_mad, curr_RD, prev_RD, average_mad, aver_QP;

    /* Mad based variable bit allocation */
    targetBitCalculation(encvid, video, rateCtrl, pMP);

    if (rateCtrl->T <= 0 || rateCtrl->totalSAD == 0)
    {
        if (rateCtrl->T < 0)    rateCtrl->Qc = RC_MAX_QUANT;
        return;
    }

    /* ---------------------------------------------------------------------------------------------------*/
    /* current frame QP estimation */
    curr_target = rateCtrl->T;
    curr_mad = (OsclFloat)rateCtrl->totalSAD / video->PicSizeInMbs;
    if (curr_mad < MAD_MIN) curr_mad = MAD_MIN; /* MAD_MIN is defined as 1 in mp4def.h */
    curr_RD  = (OsclFloat)curr_target / curr_mad;

    if (rateCtrl->skip_next_frame == -1) // previous was skipped
    {
        i = pMP->framePos;
        prev_mad = pMP->pRDSamples[i][0].mad;
        prev_QP = pMP->pRDSamples[i][0].QP;
        prev_actual_bits = pMP->pRDSamples[i][0].actual_bits;
    }
    else
    {
        /* Another version of search the optimal point */
        prev_mad = 0.0;
        i = 0;
        while (i < pMP->frameRange && prev_mad < 0.001) /* find first one with nonzero prev_mad */
        {
            prev_mad = pMP->pRDSamples[i][0].mad;
            i++;
        }

        if (i < pMP->frameRange)
        {
            prev_actual_bits = pMP->pRDSamples[i-1][0].actual_bits;

            for (j = 0; i < pMP->frameRange; i++)
            {
                if (pMP->pRDSamples[i][0].mad != 0 &&
                        AVC_ABS(prev_mad - curr_mad) > AVC_ABS(pMP->pRDSamples[i][0].mad - curr_mad))
                {
                    prev_mad = pMP->pRDSamples[i][0].mad;
                    prev_actual_bits = pMP->pRDSamples[i][0].actual_bits;
                    j = i;
                }
            }
            prev_QP = QP2Qstep(pMP->pRDSamples[j][0].QP);

            for (i = 1; i < pMP->samplesPerFrame[j]; i++)
            {
                if (AVC_ABS(prev_actual_bits - curr_target) > AVC_ABS(pMP->pRDSamples[j][i].actual_bits - curr_target))
                {
                    prev_actual_bits = pMP->pRDSamples[j][i].actual_bits;
                    prev_QP = QP2Qstep(pMP->pRDSamples[j][i].QP);
                }
            }
        }
    }

    // quadratic approximation
    if (prev_mad > 0.001) // only when prev_mad is greater than 0, otherwise keep using the same QP
    {
        prev_RD = (OsclFloat)prev_actual_bits / prev_mad;
        //rateCtrl->Qc = (Int)(prev_QP * sqrt(prev_actual_bits/curr_target) + 0.4);
        if (prev_QP == 0.625) // added this to allow getting out of QP = 0 easily
        {
            Qstep = (int)(prev_RD / curr_RD + 0.5);
        }
        else
        {
            //      rateCtrl->Qc =(Int)(prev_QP * M4VENC_SQRT(prev_RD/curr_RD) + 0.9);

            if (prev_RD / curr_RD > 0.5 && prev_RD / curr_RD < 2.0)
                Qstep = (int)(prev_QP * (sqrt(prev_RD / curr_RD) + prev_RD / curr_RD) / 2.0 + 0.9); /* Quadratic and linear approximation */
            else
                Qstep = (int)(prev_QP * (sqrt(prev_RD / curr_RD) + pow(prev_RD / curr_RD, 1.0 / 3.0)) / 2.0 + 0.9);
        }
        // lower bound on Qc should be a function of curr_mad
        // When mad is already low, lower bound on Qc doesn't have to be small.
        // Note, this doesn't work well for low complexity clip encoded at high bit rate
        // it doesn't hit the target bit rate due to this QP lower bound.
        /// if((curr_mad < 8) && (rateCtrl->Qc < 12))   rateCtrl->Qc = 12;
        //  else    if((curr_mad < 128) && (rateCtrl->Qc < 3)) rateCtrl->Qc = 3;

        rateCtrl->Qc = Qstep2QP(Qstep);

        if (rateCtrl->Qc < RC_MIN_QUANT) rateCtrl->Qc = RC_MIN_QUANT;
        if (rateCtrl->Qc > RC_MAX_QUANT)    rateCtrl->Qc = RC_MAX_QUANT;
    }

    /* active bit resource protection */
    aver_QP = (pMP->encoded_frames == 0 ? 0 : pMP->sum_QP / (OsclFloat)pMP->encoded_frames);
    average_mad = (pMP->encoded_frames == 0 ? 0 : pMP->sum_mad / (OsclFloat)pMP->encoded_frames); /* this function is called from the scond encoded frame*/
    if (pMP->diff_counter == 0 &&
            ((OsclFloat)rateCtrl->Qc <= aver_QP*1.1 || curr_mad <= average_mad*1.1) &&
            pMP->counter_BTsrc <= (pMP->counter_BTdst + (int)(pMP->framerate*1.0 + 0.5)))
    {
        rateCtrl->TMN_TH -= (int)(pMP->target_bits_per_frame / 10.0);
        rateCtrl->T = rateCtrl->TMN_TH - rateCtrl->TMN_W;
        pMP->counter_BTsrc++;
        pMP->diff_counter--;
    }

}

void targetBitCalculation(AVCEncObject *encvid, AVCCommonObj *video, AVCRateControl *rateCtrl, MultiPass *pMP)
{
    OSCL_UNUSED_ARG(encvid);
    OsclFloat curr_mad;//, average_mad;
    int diff_counter_BTsrc, diff_counter_BTdst, prev_counter_diff, curr_counter_diff, bound;
    /* BT = Bit Transfer, for pMP->counter_BTsrc, pMP->counter_BTdst */

    /* some stuff about frame dropping remained here to be done because pMP cannot be inserted into updateRateControl()*/
    updateRC_PostProc(rateCtrl, pMP);

    /* update pMP->counter_BTsrc and pMP->counter_BTdst to avoid interger overflow */
    if (pMP->counter_BTsrc > 1000 && pMP->counter_BTdst > 1000)
    {
        pMP->counter_BTsrc -= 1000;
        pMP->counter_BTdst -= 1000;
    }

    /* ---------------------------------------------------------------------------------------------------*/
    /* target calculation */
    curr_mad = (OsclFloat)rateCtrl->totalSAD / video->PicSizeInMbs;
    if (curr_mad < MAD_MIN) curr_mad = MAD_MIN; /* MAD_MIN is defined as 1 in mp4def.h */
    diff_counter_BTsrc = diff_counter_BTdst = 0;
    pMP->diff_counter = 0;


    /*1.calculate average mad */
    pMP->sum_mad += curr_mad;
    //average_mad = (pMP->encoded_frames < 1 ? curr_mad : pMP->sum_mad/(OsclFloat)(pMP->encoded_frames+1)); /* this function is called from the scond encoded frame*/
    //pMP->aver_mad = average_mad;
    if (pMP->encoded_frames >= 0) /* pMP->encoded_frames is set to -1 initially, so forget about the very first I frame */
        pMP->aver_mad = (pMP->aver_mad * pMP->encoded_frames + curr_mad) / (pMP->encoded_frames + 1);

    if (pMP->overlapped_win_size > 0 && pMP->encoded_frames_prev >= 0)
        pMP->aver_mad_prev = (pMP->aver_mad_prev * pMP->encoded_frames_prev + curr_mad) / (pMP->encoded_frames_prev + 1);

    /*2.average_mad, mad ==> diff_counter_BTsrc, diff_counter_BTdst */
    if (pMP->overlapped_win_size == 0)
    {
        /* original verison */
        if (curr_mad > pMP->aver_mad*1.1)
        {
            if (curr_mad / (pMP->aver_mad + 0.0001) > 2)
                diff_counter_BTdst = (int)(sqrt(curr_mad / (pMP->aver_mad + 0.0001)) * 10 + 0.4) - 10;
            //diff_counter_BTdst = (int)((sqrt(curr_mad/pMP->aver_mad)*2+curr_mad/pMP->aver_mad)/(3*0.1) + 0.4) - 10;
            else
                diff_counter_BTdst = (int)(curr_mad / (pMP->aver_mad + 0.0001) * 10 + 0.4) - 10;
        }
        else /* curr_mad <= average_mad*1.1 */
            //diff_counter_BTsrc = 10 - (int)((sqrt(curr_mad/pMP->aver_mad) + pow(curr_mad/pMP->aver_mad, 1.0/3.0))/(2.0*0.1) + 0.4);
            diff_counter_BTsrc = 10 - (int)(sqrt(curr_mad / (pMP->aver_mad + 0.0001)) * 10 + 0.5);

        /* actively fill in the possible gap */
        if (diff_counter_BTsrc == 0 && diff_counter_BTdst == 0 &&
                curr_mad <= pMP->aver_mad*1.1 && pMP->counter_BTsrc < pMP->counter_BTdst)
            diff_counter_BTsrc = 1;

    }
    else if (pMP->overlapped_win_size > 0)
    {
        /* transition time: use previous average mad "pMP->aver_mad_prev" instead of the current average mad "pMP->aver_mad" */
        if (curr_mad > pMP->aver_mad_prev*1.1)
        {
            if (curr_mad / pMP->aver_mad_prev > 2)
                diff_counter_BTdst = (int)(sqrt(curr_mad / (pMP->aver_mad_prev + 0.0001)) * 10 + 0.4) - 10;
            //diff_counter_BTdst = (int)((M4VENC_SQRT(curr_mad/pMP->aver_mad_prev)*2+curr_mad/pMP->aver_mad_prev)/(3*0.1) + 0.4) - 10;
            else
                diff_counter_BTdst = (int)(curr_mad / (pMP->aver_mad_prev + 0.0001) * 10 + 0.4) - 10;
        }
        else /* curr_mad <= average_mad*1.1 */
            //diff_counter_BTsrc = 10 - (Int)((sqrt(curr_mad/pMP->aver_mad_prev) + pow(curr_mad/pMP->aver_mad_prev, 1.0/3.0))/(2.0*0.1) + 0.4);
            diff_counter_BTsrc = 10 - (int)(sqrt(curr_mad / (pMP->aver_mad_prev + 0.0001)) * 10 + 0.5);

        /* actively fill in the possible gap */
        if (diff_counter_BTsrc == 0 && diff_counter_BTdst == 0 &&
                curr_mad <= pMP->aver_mad_prev*1.1 && pMP->counter_BTsrc < pMP->counter_BTdst)
            diff_counter_BTsrc = 1;

        if (--pMP->overlapped_win_size <= 0)    pMP->overlapped_win_size = 0;
    }


    /* if difference is too much, do clipping */
    /* First, set the upper bound for current bit allocation variance: 80% of available buffer */
    bound = (int)((rateCtrl->Bs / 2 - rateCtrl->VBV_fullness) * 0.6 / (pMP->target_bits_per_frame / 10)); /* rateCtrl->Bs */
    diff_counter_BTsrc =  AVC_MIN(diff_counter_BTsrc, bound);
    diff_counter_BTdst =  AVC_MIN(diff_counter_BTdst, bound);

    /* Second, set another upper bound for current bit allocation: 4-5*bitrate/framerate */
    bound = 50;
//  if(video->encParams->RC_Type == CBR_LOWDELAY)
//  not necessary       bound = 10;  -- For Low delay */

    diff_counter_BTsrc =  AVC_MIN(diff_counter_BTsrc, bound);
    diff_counter_BTdst =  AVC_MIN(diff_counter_BTdst, bound);


    /* Third, check the buffer */
    prev_counter_diff = pMP->counter_BTdst - pMP->counter_BTsrc;
    curr_counter_diff = prev_counter_diff + (diff_counter_BTdst - diff_counter_BTsrc);

    if (AVC_ABS(prev_counter_diff) >= rateCtrl->max_BitVariance_num || AVC_ABS(curr_counter_diff) >= rateCtrl->max_BitVariance_num)
    {   //diff_counter_BTsrc = diff_counter_BTdst = 0;

        if (curr_counter_diff > rateCtrl->max_BitVariance_num && diff_counter_BTdst)
        {
            diff_counter_BTdst = (rateCtrl->max_BitVariance_num - prev_counter_diff) + diff_counter_BTsrc;
            if (diff_counter_BTdst < 0) diff_counter_BTdst = 0;
        }

        else if (curr_counter_diff < -rateCtrl->max_BitVariance_num && diff_counter_BTsrc)
        {
            diff_counter_BTsrc = diff_counter_BTdst - (-rateCtrl->max_BitVariance_num - prev_counter_diff);
            if (diff_counter_BTsrc < 0) diff_counter_BTsrc = 0;
        }
    }


    /*3.diff_counter_BTsrc, diff_counter_BTdst ==> TMN_TH */
    rateCtrl->TMN_TH = (int)(pMP->target_bits_per_frame);
    pMP->diff_counter = 0;

    if (diff_counter_BTsrc)
    {
        rateCtrl->TMN_TH -= (int)(pMP->target_bits_per_frame * diff_counter_BTsrc * 0.1);
        pMP->diff_counter = -diff_counter_BTsrc;
    }
    else if (diff_counter_BTdst)
    {
        rateCtrl->TMN_TH += (int)(pMP->target_bits_per_frame * diff_counter_BTdst * 0.1);
        pMP->diff_counter = diff_counter_BTdst;
    }


    /*4.update pMP->counter_BTsrc, pMP->counter_BTdst */
    pMP->counter_BTsrc += diff_counter_BTsrc;
    pMP->counter_BTdst += diff_counter_BTdst;


    /*5.target bit calculation */
    rateCtrl->T = rateCtrl->TMN_TH - rateCtrl->TMN_W;

    return ;
}

void updateRC_PostProc(AVCRateControl *rateCtrl, MultiPass *pMP)
{
    if (rateCtrl->skip_next_frame > 0) /* skip next frame */
    {
        pMP->counter_BTsrc += 10 * rateCtrl->skip_next_frame;

    }
    else if (rateCtrl->skip_next_frame == -1) /* skip current frame */
    {
        pMP->counter_BTdst -= pMP->diff_counter;
        pMP->counter_BTsrc += 10;

        pMP->sum_mad -= pMP->mad;
        pMP->aver_mad = (pMP->aver_mad * pMP->encoded_frames - pMP->mad) / (pMP->encoded_frames - 1 + 0.0001);
        pMP->sum_QP  -= pMP->QP;
        pMP->encoded_frames --;
    }
    /* some stuff in update VBV_fullness remains here */
    //if(rateCtrl->VBV_fullness < -rateCtrl->Bs/2) /* rateCtrl->Bs */
    if (rateCtrl->VBV_fullness < rateCtrl->low_bound)
    {
        rateCtrl->VBV_fullness = rateCtrl->low_bound; // -rateCtrl->Bs/2;
        rateCtrl->TMN_W = rateCtrl->VBV_fullness - rateCtrl->low_bound;
        pMP->counter_BTsrc = pMP->counter_BTdst + (int)((OsclFloat)(rateCtrl->Bs / 2 - rateCtrl->low_bound) / 2.0 / (pMP->target_bits_per_frame / 10));
    }
}


void RCInitChromaQP(AVCEncObject *encvid)
{
    AVCCommonObj *video = encvid->common;
    AVCMacroblock *currMB = video->currMB;
    int q_bits;

    /* we have to do the same thing for AVC_CLIP3(0,51,video->QSy) */

    video->QPy_div_6 = (currMB->QPy * 43) >> 8;
    video->QPy_mod_6 = currMB->QPy - 6 * video->QPy_div_6;
    currMB->QPc = video->QPc = mapQPi2QPc[AVC_CLIP3(0, 51, currMB->QPy + video->currPicParams->chroma_qp_index_offset)];
    video->QPc_div_6 = (video->QPc * 43) >> 8;
    video->QPc_mod_6 = video->QPc - 6 * video->QPc_div_6;

    /* pre-calculate this to save computation */
    q_bits = 4 + video->QPy_div_6;
    if (video->slice_type == AVC_I_SLICE)
    {
        encvid->qp_const = 682 << q_bits;       // intra
    }
    else
    {
        encvid->qp_const = 342 << q_bits;       // inter
    }

    q_bits = 4 + video->QPc_div_6;
    if (video->slice_type == AVC_I_SLICE)
    {
        encvid->qp_const_c = 682 << q_bits;    // intra
    }
    else
    {
        encvid->qp_const_c = 342 << q_bits;    // inter
    }

    encvid->lambda_mode = QP2QUANT[AVC_MAX(0, currMB->QPy-SHIFT_QP)];
    encvid->lambda_motion = LAMBDA_FACTOR(encvid->lambda_mode);

    return ;
}


void RCInitMBQP(AVCEncObject *encvid)
{
    AVCCommonObj *video =  encvid->common;
    AVCMacroblock *currMB = video->currMB;

    currMB->QPy = video->QPy; /* set to previous value or picture level */

    RCInitChromaQP(encvid);

}

void RCPostMB(AVCCommonObj *video, AVCRateControl *rateCtrl, int num_header_bits, int num_texture_bits)
{
    OSCL_UNUSED_ARG(video);
    rateCtrl->numMBHeaderBits = num_header_bits;
    rateCtrl->numMBTextureBits = num_texture_bits;
    rateCtrl->NumberofHeaderBits += rateCtrl->numMBHeaderBits;
    rateCtrl->NumberofTextureBits += rateCtrl->numMBTextureBits;
}

void RCRestoreQP(AVCMacroblock *currMB, AVCCommonObj *video, AVCEncObject *encvid)
{
    currMB->QPy = video->QPy; /* use previous QP */
    RCInitChromaQP(encvid);

    return ;
}


void RCCalculateMAD(AVCEncObject *encvid, AVCMacroblock *currMB, uint8 *orgL, int orgPitch)
{
    AVCCommonObj *video = encvid->common;
    AVCRateControl *rateCtrl = encvid->rateCtrl;
    uint32 dmin_lx;

    if (rateCtrl->rcEnable == TRUE)
    {
        if (currMB->mb_intra)
        {
            if (currMB->mbMode == AVC_I16)
            {
                dmin_lx = (0xFFFF << 16) | orgPitch;
                rateCtrl->MADofMB[video->mbNum] = AVCSAD_Macroblock_C(orgL,
                                                  encvid->pred_i16[currMB->i16Mode], dmin_lx, NULL);
            }
            else /* i4 */
            {
                rateCtrl->MADofMB[video->mbNum] = encvid->i4_sad / 256.;
            }
        }
        /* for INTER, we have already saved it with the MV search */
    }

    return ;
}



AVCEnc_Status RCUpdateFrame(AVCEncObject *encvid)
{
    AVCCommonObj *video = encvid->common;
    AVCRateControl *rateCtrl = encvid->rateCtrl;
    AVCEnc_Status status = AVCENC_SUCCESS;
    MultiPass *pMP = rateCtrl->pMP;
    int diff_BTCounter;
    int nal_type = video->nal_unit_type;

    /* update the complexity weight of I, P, B frame */

    if (rateCtrl->rcEnable == TRUE)
    {
        pMP->actual_bits = rateCtrl->numFrameBits;
        pMP->mad = (OsclFloat)rateCtrl->totalSAD / video->PicSizeInMbs; //ComputeFrameMAD(video, rateCtrl);

        AVCSaveRDSamples(pMP, 0);

        pMP->encoded_frames++;

        /* for pMP->samplesPerFrame */
        pMP->samplesPerFrame[pMP->framePos] = 0;

        pMP->sum_QP += pMP->QP;

        /* update pMP->counter_BTsrc, pMP->counter_BTdst */
        /* re-allocate the target bit again and then stop encoding */
        diff_BTCounter = (int)((OsclFloat)(rateCtrl->TMN_TH - rateCtrl->TMN_W - pMP->actual_bits) /
                               (pMP->bitrate / (pMP->framerate + 0.0001) + 0.0001) / 0.1);
        if (diff_BTCounter >= 0)
            pMP->counter_BTsrc += diff_BTCounter; /* pMP->actual_bits is smaller */
        else
            pMP->counter_BTdst -= diff_BTCounter; /* pMP->actual_bits is bigger */

        rateCtrl->TMN_TH -= (int)((OsclFloat)pMP->bitrate / (pMP->framerate + 0.0001) * (diff_BTCounter * 0.1));
        rateCtrl->T = pMP->target_bits = rateCtrl->TMN_TH - rateCtrl->TMN_W;
        pMP->diff_counter -= diff_BTCounter;

        rateCtrl->Rc = rateCtrl->numFrameBits;  /* Total Bits for current frame */
        rateCtrl->Hc = rateCtrl->NumberofHeaderBits;    /* Total Bits in Header and Motion Vector */

        /* BX_RC */
        updateRateControl(rateCtrl, nal_type);
        if (rateCtrl->skip_next_frame == -1) // skip current frame
        {
            status = AVCENC_SKIPPED_PICTURE;
        }
    }

    rateCtrl->first_frame = 0;  // reset here after we encode the first frame.

    return status;
}

void AVCSaveRDSamples(MultiPass *pMP, int counter_samples)
{
    /* for pMP->pRDSamples */
    pMP->pRDSamples[pMP->framePos][counter_samples].QP    = pMP->QP;
    pMP->pRDSamples[pMP->framePos][counter_samples].actual_bits = pMP->actual_bits;
    pMP->pRDSamples[pMP->framePos][counter_samples].mad   = pMP->mad;
    pMP->pRDSamples[pMP->framePos][counter_samples].R_D = (OsclFloat)pMP->actual_bits / (pMP->mad + 0.0001);

    return ;
}

void updateRateControl(AVCRateControl *rateCtrl, int nal_type)
{
    int  frame_bits;
    MultiPass *pMP = rateCtrl->pMP;

    /* BX rate contro\l */
    frame_bits = (int)(rateCtrl->bitRate / rateCtrl->frame_rate);
    rateCtrl->TMN_W += (rateCtrl->Rc - rateCtrl->TMN_TH);
    rateCtrl->VBV_fullness += (rateCtrl->Rc - frame_bits); //rateCtrl->Rp);
    //if(rateCtrl->VBV_fullness < 0) rateCtrl->VBV_fullness = -1;

    rateCtrl->encoded_frames++;

    /* frame dropping */
    rateCtrl->skip_next_frame = 0;

    if ((rateCtrl->VBV_fullness > rateCtrl->Bs / 2) && nal_type != AVC_NALTYPE_IDR) /* skip the current frame */ /* rateCtrl->Bs */
    {
        rateCtrl->TMN_W -= (rateCtrl->Rc - rateCtrl->TMN_TH);
        rateCtrl->VBV_fullness -= rateCtrl->Rc;
        rateCtrl->skip_next_frame = -1;
    }
    else if ((OsclFloat)(rateCtrl->VBV_fullness - rateCtrl->VBV_fullness_offset) > (rateCtrl->Bs / 2 - rateCtrl->VBV_fullness_offset)*0.95) /* skip next frame */
    {
        rateCtrl->VBV_fullness -= frame_bits; //rateCtrl->Rp;
        rateCtrl->skip_next_frame = 1;
        pMP->counter_BTsrc -= (int)((OsclFloat)(rateCtrl->Bs / 2 - rateCtrl->low_bound) / 2.0 / (pMP->target_bits_per_frame / 10));
        /* BX_1, skip more than 1 frames  */
        //while(rateCtrl->VBV_fullness > rateCtrl->Bs*0.475)
        while ((rateCtrl->VBV_fullness - rateCtrl->VBV_fullness_offset) > (rateCtrl->Bs / 2 - rateCtrl->VBV_fullness_offset)*0.95)
        {
            rateCtrl->VBV_fullness -= frame_bits; //rateCtrl->Rp;
            rateCtrl->skip_next_frame++;
            pMP->counter_BTsrc -= (int)((OsclFloat)(rateCtrl->Bs / 2 - rateCtrl->low_bound) / 2.0 / (pMP->target_bits_per_frame / 10));
        }

        /* END BX_1 */
    }
}


double ComputeFrameMAD(AVCCommonObj *video, AVCRateControl *rateCtrl)
{
    double TotalMAD;
    int i;
    TotalMAD = 0.0;
    for (i = 0; i < (int)video->PicSizeInMbs; i++)
        TotalMAD += rateCtrl->MADofMB[i];
    TotalMAD /= video->PicSizeInMbs;
    return TotalMAD;
}





/* convert from QP to Qstep */
double QP2Qstep(int QP)
{
    int i;
    double Qstep;
    static const double QP2QSTEP[6] = { 0.625, 0.6875, 0.8125, 0.875, 1.0, 1.125 };

    Qstep = QP2QSTEP[QP % 6];
    for (i = 0; i < (QP / 6); i++)
        Qstep *= 2;

    return Qstep;
}

/* convert from step size to QP */
int Qstep2QP(double Qstep)
{
    int q_per = 0, q_rem = 0;

    //  assert( Qstep >= QP2Qstep(0) && Qstep <= QP2Qstep(51) );
    if (Qstep < QP2Qstep(0))
        return 0;
    else if (Qstep > QP2Qstep(51))
        return 51;

    while (Qstep > QP2Qstep(5))
    {
        Qstep /= 2;
        q_per += 1;
    }

    if (Qstep <= (0.625 + 0.6875) / 2)
    {
        Qstep = 0.625;
        q_rem = 0;
    }
    else if (Qstep <= (0.6875 + 0.8125) / 2)
    {
        Qstep = 0.6875;
        q_rem = 1;
    }
    else if (Qstep <= (0.8125 + 0.875) / 2)
    {
        Qstep = 0.8125;
        q_rem = 2;
    }
    else if (Qstep <= (0.875 + 1.0) / 2)
    {
        Qstep = 0.875;
        q_rem = 3;
    }
    else if (Qstep <= (1.0 + 1.125) / 2)
    {
        Qstep = 1.0;
        q_rem = 4;
    }
    else
    {
        Qstep = 1.125;
        q_rem = 5;
    }

    return (q_per * 6 + q_rem);
}



