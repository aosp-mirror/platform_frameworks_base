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
#include "rate_control.h"
#include "mp4enc_lib.h"
#include "bitstream_io.h"
#include "m4venc_oscl.h"

void targetBitCalculation(void *input);
void calculateQuantizer_Multipass(void *video);
void updateRateControl(rateControl *rc, VideoEncData *video);
void updateRC_PostProc(rateControl *rc, VideoEncData *video);

/***************************************************************************
**************  RC APIs to core encoding modules  *******************

PV_STATUS RC_Initialize(void *video);
PV_STATUS RC_Cleanup(rateControl *rc[],Int numLayers);
PV_STATUS RC_VopQPSetting(VideoEncData *video,rateControl *rc[]);
PV_STATUS RC_VopUpdateStat(VideoEncData *video,rateControl *rc[]);
PV_STATUS RC_UpdateBuffer(VideoEncData *video, Int currLayer, Int num_skip);
Int       RC_GetSkipNextFrame(VideoEncData *video,Int currLayer);
void      RC_ResetSkipNextFrame(void *video,Int currLayer);

PV_STATUS RC_UpdateBXRCParams(void *input);  Parameters update for target bitrate or framerate change

****************************************************************************/


/************************************************************************/
/************ API part **************************************************/
/* must be called before each sequence*/

PV_STATUS RC_Initialize(void *input)
{
    VideoEncData *video = (VideoEncData *) input;
    VideoEncParams *encParams = video->encParams;
    rateControl **rc = video->rc;
    Int numLayers = encParams->nLayers;
    Int *LayerBitRate = encParams->LayerBitRate;
    float *LayerFrameRate = encParams->LayerFrameRate;
    MultiPass **pMP = video->pMP;

    Int n;

    for (n = 0; n < numLayers; n++)
    {
        /* rate control */
        rc[n]->fine_frame_skip = encParams->FineFrameSkip_Enabled;
        rc[n]->no_frame_skip = encParams->NoFrameSkip_Enabled;
        rc[n]->no_pre_skip = encParams->NoPreSkip_Enabled;
        rc[n]->skip_next_frame = 0; /* must be initialized */

        //rc[n]->TMN_TH = (Int)((float)LayerBitRate[n]/LayerFrameRate[n]);
        rc[n]->Bs = video->encParams->BufferSize[n];
        rc[n]->TMN_W = 0;
        rc[n]->VBV_fullness = (Int)(rc[n]->Bs * 0.5); /* rc[n]->Bs */
        rc[n]->encoded_frames = 0;
        rc[n]->framerate = LayerFrameRate[n];
        if (n == 0)
        {
            rc[n]->TMN_TH = (Int)((float)LayerBitRate[n] / LayerFrameRate[n]);
            rc[n]->bitrate = LayerBitRate[n];
            rc[n]->framerate = LayerFrameRate[n];

            // For h263 or short header mode, the bit variation is within (-2*Rmax*1001/3000, 2*Rmax*1001/3000)
            if (video->encParams->H263_Enabled)
            {
                rc[n]->max_BitVariance_num = (Int)((rc[n]->Bs - video->encParams->maxFrameSize) / 2 / (rc[n]->bitrate / rc[n]->framerate / 10.0)) - 5;
                if (rc[n]->max_BitVariance_num < 0) rc[n]->max_BitVariance_num += 5;
            }
            else   // MPEG-4 normal modes
            {
                rc[n]->max_BitVariance_num = (Int)((float)(rc[n]->Bs - rc[n]->VBV_fullness) / ((float)LayerBitRate[n] / LayerFrameRate[n] / 10.0)) - 5;
                if (rc[n]->max_BitVariance_num < 0) rc[n]->max_BitVariance_num += 5;
            }
        }
        else
        {
            if (LayerFrameRate[n] - LayerFrameRate[n-1] > 0) /*  7/31/03 */
            {
                rc[n]->TMN_TH = (Int)((float)(LayerBitRate[n] - LayerBitRate[n-1]) / (LayerFrameRate[n] - LayerFrameRate[n-1]));
                rc[n]->max_BitVariance_num = (Int)((float)(rc[n]->Bs - rc[n]->VBV_fullness) * 10 / ((float)rc[n]->TMN_TH)) - 5;
                if (rc[n]->max_BitVariance_num < 0) rc[n]->max_BitVariance_num += 5;
            }
            else   /*  7/31/03 */
            {
                rc[n]->TMN_TH = 1 << 30;
                rc[n]->max_BitVariance_num = 0;
            }
            rc[n]->bitrate = LayerBitRate[n] - LayerBitRate[n-1];
            rc[n]->framerate = LayerFrameRate[n] - LayerFrameRate[n-1];
        }

        // Set the initial buffer fullness
        if (1) //!video->encParams->H263_Enabled)  { // MPEG-4
        {
            /* According to the spec, the initial buffer fullness needs to be set to 1/3 */
            rc[n]->VBV_fullness = (Int)(rc[n]->Bs / 3.0 - rc[n]->Bs / 2.0); /* the buffer range is [-Bs/2, Bs/2] */
            pMP[n]->counter_BTsrc = (Int)((rc[n]->Bs / 2.0 - rc[n]->Bs / 3.0) / (rc[n]->bitrate / rc[n]->framerate / 10.0));
            rc[n]->TMN_W = (Int)(rc[n]->VBV_fullness + pMP[n]->counter_BTsrc * (rc[n]->bitrate / rc[n]->framerate / 10.0));

            rc[n]->low_bound = -rc[n]->Bs / 2;
            rc[n]-> VBV_fullness_offset = 0;
        }
        else   /* this part doesn't work in some cases, the low_bound is too high, Jan 4,2006 */
        {
            rc[n]->VBV_fullness =  rc[n]->Bs - (Int)(video->encParams->VBV_delay * rc[n]->bitrate);
            if (rc[n]->VBV_fullness < 0) rc[n]->VBV_fullness = 0;
            //rc[n]->VBV_fullness = (rc[n]->Bs-video->encParams->maxFrameSize)/2 + video->encParams->maxFrameSize;

            rc[n]->VBV_fullness -= rc[n]->Bs / 2; /* the buffer range is [-Bs/2, Bs/2] */
            rc[n]->low_bound = -rc[n]->Bs / 2 + video->encParams->maxFrameSize;  /*  too high */
            rc[n]->VBV_fullness_offset = video->encParams->maxFrameSize / 2; /*  don't understand the meaning of this */
            pMP[n]->counter_BTdst = pMP[n]->counter_BTsrc = 0;

        }

        /* Setting the bitrate and framerate */
        pMP[n]->bitrate = rc[n]->bitrate;
        pMP[n]->framerate = rc[n]->framerate;
        pMP[n]->target_bits_per_frame = pMP[n]->bitrate / pMP[n]->framerate;

    }

    return PV_SUCCESS;
}


/* ======================================================================== */
/*  Function : RC_Cleanup                                                   */
/*  Date     : 12/20/2000                                                   */
/*  Purpose  : free Rate Control memory                                     */
/*  In/out   :                                                              */
/*  Return   :                                                              */
/*  Modified :                                                              */
/* ======================================================================== */


PV_STATUS RC_Cleanup(rateControl *rc[], Int numLayers)
{
    OSCL_UNUSED_ARG(rc);
    OSCL_UNUSED_ARG(numLayers);

    return PV_SUCCESS;
}



/* ======================================================================== */
/*  Function : RC_VopQPSetting                                              */
/*  Date     : 4/11/2001                                                    */
/*  Purpose  : Reset rate control before coding VOP, moved from vop.c       */
/*              Compute QP for the whole VOP and initialize MB-based RC
                reset QPMB[], currVop->quantizer, rc->Ec, video->header_bits */
/* to          In order to  work RC_VopQPSetting has to do the followings
                1. Set video->QPMB of all macroblocks.
                2. Set currVop->quantizer
                3. Reset video->header_bits to zero.
                4. Initialize internal RC parameters for Vop cooding        */
/*  In/out   :                                                              */
/*  Return   : PV_STATUS                                                    */
/*  Modified :                                                              */
/* ======================================================================== */
/* To be moved to rate_control.c and separate between BX_RC and ANNEX_L     */

PV_STATUS RC_VopQPSetting(VideoEncData *video, rateControl *prc[])
{
    Int currLayer = video->currLayer;
    Vol *currVol = video->vol[currLayer];
    Vop *currVop = video->currVop;
#ifdef TEST_MBBASED_QP
    int i;
#endif

    rateControl *rc = video->rc[currLayer];
    MultiPass *pMP = video->pMP[currLayer];

    OSCL_UNUSED_ARG(prc);

    if (video->encParams->RC_Type == CONSTANT_Q)
    {
        M4VENC_MEMSET(video->QPMB, currVop->quantizer, sizeof(UChar)*currVol->nTotalMB);
        return PV_SUCCESS;
    }
    else
    {

        if (video->rc[currLayer]->encoded_frames == 0) /* rc[currLayer]->totalFrameNumber*/
        {
            M4VENC_MEMSET(video->QPMB, currVop->quantizer, sizeof(UChar)*currVol->nTotalMB);
            video->rc[currLayer]->Qc = video->encParams->InitQuantIvop[currLayer];
        }
        else
        {
            calculateQuantizer_Multipass((void*) video);
            currVop->quantizer = video->rc[currLayer]->Qc;
#ifdef TEST_MBBASED_QP
            i = currVol->nTotalMB;  /* testing changing QP at MB level */
            while (i)
            {
                i--;
                video->QPMB[i] = (i & 1) ? currVop->quantizer - 1 : currVop->quantizer + 1;
            }
#else
            M4VENC_MEMSET(video->QPMB, currVop->quantizer, sizeof(UChar)*currVol->nTotalMB);
#endif
        }

        video->header_bits = 0;
    }

    /* update pMP->framePos */
    if (++pMP->framePos == pMP->frameRange) pMP->framePos = 0;

    if (rc->T == 0)
    {
        pMP->counter_BTdst = (Int)(video->encParams->LayerFrameRate[video->currLayer] * 7.5 + 0.5); /* 0.75s time frame */
        pMP->counter_BTdst = PV_MIN(pMP->counter_BTdst, (Int)(rc->max_BitVariance_num / 2 * 0.40)); /* 0.75s time frame may go beyond VBV buffer if we set the buffer size smaller than 0.75s */
        pMP->counter_BTdst = PV_MAX(pMP->counter_BTdst, (Int)((rc->Bs / 2 - rc->VBV_fullness) * 0.30 / (rc->TMN_TH / 10.0) + 0.5)); /* At least 30% of VBV buffer size/2 */
        pMP->counter_BTdst = PV_MIN(pMP->counter_BTdst, 20); /* Limit the target to be smaller than 3C */

        pMP->target_bits = rc->T = rc->TMN_TH = (Int)(rc->TMN_TH * (1.0 + pMP->counter_BTdst * 0.1));
        pMP->diff_counter = pMP->counter_BTdst;
    }

    /* collect the necessary data: target bits, actual bits, mad and QP */
    pMP->target_bits = rc->T;
    pMP->QP  = currVop->quantizer;

    pMP->mad = video->sumMAD / (float)currVol->nTotalMB;
    if (pMP->mad < MAD_MIN) pMP->mad = MAD_MIN; /* MAD_MIN is defined as 1 in mp4def.h */

    pMP->bitrate = rc->bitrate; /* calculated in RCVopQPSetting */
    pMP->framerate = rc->framerate;

    /* first pass encoding */
    pMP->nRe_Quantized = 0;

    return  PV_SUCCESS;
}


/* ======================================================================== */
/*  Function : SaveRDSamples()                                              */
/*  Date     : 08/29/2001                                                   */
/*  History  :                                                              */
/*  Purpose  : Save QP, actual_bits, mad and R_D of the current iteration   */
/*  In/out   :                                                              */
/*  Return   :                                                              */
/*  Modified :                                                              */
/*                                                                          */
/* ======================================================================== */

Void SaveRDSamples(MultiPass *pMP, Int counter_samples)
{
    /* for pMP->pRDSamples */
    pMP->pRDSamples[pMP->framePos][counter_samples].QP    = pMP->QP;
    pMP->pRDSamples[pMP->framePos][counter_samples].actual_bits = pMP->actual_bits;
    pMP->pRDSamples[pMP->framePos][counter_samples].mad   = pMP->mad;
    pMP->pRDSamples[pMP->framePos][counter_samples].R_D = (float)(pMP->actual_bits / (pMP->mad + 0.0001));

    return ;
}
/* ======================================================================== */
/*  Function : RC_VopUpdateStat                                             */
/*  Date     : 12/20/2000                                                   */
/*  Purpose  : Update statistics for rate control after encoding each VOP.  */
/*             No need to change anything in VideoEncData structure.        */
/*  In/out   :                                                              */
/*  Return   :                                                              */
/*  Modified :                                                              */
/* ======================================================================== */

PV_STATUS RC_VopUpdateStat(VideoEncData *video, rateControl *rc)
{
    Int currLayer = video->currLayer;
    Vol *currVol = video->vol[currLayer];
    MultiPass *pMP = video->pMP[currLayer];
    Int diff_BTCounter;

    switch (video->encParams->RC_Type)
    {
        case CONSTANT_Q:
            break;

        case CBR_1:
        case CBR_2:
        case VBR_1:
        case VBR_2:
        case CBR_LOWDELAY:

            pMP->actual_bits = currVol->stream->byteCount << 3;

            SaveRDSamples(pMP, 0);

            pMP->encoded_frames++;

            /* for pMP->samplesPerFrame */
            pMP->samplesPerFrame[pMP->framePos] = 0;

            pMP->sum_QP += pMP->QP;


            /* update pMP->counter_BTsrc, pMP->counter_BTdst */
            /* re-allocate the target bit again and then stop encoding */
            diff_BTCounter = (Int)((float)(rc->TMN_TH - rc->TMN_W - pMP->actual_bits) /
                                   (pMP->bitrate / (pMP->framerate + 0.0001) + 0.0001) / 0.1);
            if (diff_BTCounter >= 0)
                pMP->counter_BTsrc += diff_BTCounter; /* pMP->actual_bits is smaller */
            else
                pMP->counter_BTdst -= diff_BTCounter; /* pMP->actual_bits is bigger */

            rc->TMN_TH -= (Int)((float)pMP->bitrate / (pMP->framerate + 0.0001) * (diff_BTCounter * 0.1));
            rc->T = pMP->target_bits = rc->TMN_TH - rc->TMN_W;
            pMP->diff_counter -= diff_BTCounter;

            rc->Rc = currVol->stream->byteCount << 3;   /* Total Bits for current frame */
            rc->Hc = video->header_bits;    /* Total Bits in Header and Motion Vector */

            /* BX_RC */
            updateRateControl(rc, video);

            break;

        default: /* for case CBR_1/2, VBR_1/2 */

            return PV_FAIL;
    }


    return PV_SUCCESS;
}

/* ======================================================================== */
/*  Function : RC_GetSkipNextFrame, RC_GetRemainingVops                     */
/*  Date     : 2/20/2001                                                    */
/*  Purpose  : To access RC parameters from other parts of the code.        */
/*  In/out   :                                                              */
/*  Return   :                                                              */
/*  Modified :                                                              */
/* ======================================================================== */

Int RC_GetSkipNextFrame(VideoEncData *video, Int currLayer)
{
    return video->rc[currLayer]->skip_next_frame;
}

void RC_ResetSkipNextFrame(VideoEncData *video, Int currLayer)
{

    video->rc[currLayer]->skip_next_frame = 0;
    return ;
}

/* ======================================================================== */
/*  Function : RC_UpdateBuffer                                      */
/*  Date     : 2/20/2001                                                    */
/*  Purpose  : Update RC in case of there are frames skipped (camera freeze)*/
/*              from the application level in addition to what RC requested */
/*  In/out   : Nr, B, Rr                                                    */
/*  Return   : Void                                                         */
/*  Modified :                                                              */
/* ======================================================================== */


PV_STATUS RC_UpdateBuffer(VideoEncData *video, Int currLayer, Int num_skip)
{
    rateControl *rc  = video->rc[currLayer];
    MultiPass   *pMP = video->pMP[currLayer];

    if (video == NULL || rc == NULL || pMP == NULL)
        return PV_FAIL;

    rc->VBV_fullness   -= (Int)(rc->bitrate / rc->framerate * num_skip); //rc[currLayer]->Rp;
    pMP->counter_BTsrc += 10 * num_skip;

    /* Check buffer underflow */
    if (rc->VBV_fullness < rc->low_bound)
    {
        rc->VBV_fullness = rc->low_bound; // -rc->Bs/2;
        rc->TMN_W = rc->VBV_fullness - rc->low_bound;
        pMP->counter_BTsrc = pMP->counter_BTdst + (Int)((float)(rc->Bs / 2 - rc->low_bound) / 2.0 / (pMP->target_bits_per_frame / 10));
    }

    return PV_SUCCESS;
}


/* ======================================================================== */
/*  Function : RC_UpdateBXRCParams                                          */
/*  Date     : 4/08/2002                                                    */
/*  Purpose  : Update RC parameters specifically for target bitrate or      */
/*             framerate update during an encoding session                  */
/*  In/out   :                                                              */
/*  Return   : PV_TRUE if successed, PV_FALSE if failed.                    */
/*  Modified :                                                              */
/* ======================================================================== */

PV_STATUS RC_UpdateBXRCParams(void *input)
{
    VideoEncData *video = (VideoEncData *) input;
    VideoEncParams *encParams = video->encParams;
    rateControl **rc = video->rc;
    Int numLayers = encParams->nLayers;
    Int *LayerBitRate = encParams->LayerBitRate;
    float *LayerFrameRate = encParams->LayerFrameRate;
    MultiPass **pMP = video->pMP;

    Int n, VBV_fullness;
    Int diff_counter;

    extern Bool SetProfile_BufferSize(VideoEncData *video, float delay, Int bInitialized);


    /* Reset video buffer size due to target bitrate change */
    SetProfile_BufferSize(video, video->encParams->VBV_delay, 0); /* output: video->encParams->BufferSize[] */

    for (n = 0; n < numLayers; n++)
    {
        /* Remaining stuff about frame dropping and underflow check in update RC */
        updateRC_PostProc(rc[n], video);
        rc[n]->skip_next_frame = 0; /* must be initialized */

        /* New changes: bitrate and framerate, Bs, max_BitVariance_num, TMN_TH(optional), encoded_frames(optional) */
        rc[n]->Bs = video->encParams->BufferSize[n];
        VBV_fullness = (Int)(rc[n]->Bs * 0.5);

        if (n == 0)
        {
            rc[n]->TMN_TH = (Int)((float)LayerBitRate[n] / LayerFrameRate[n]);
            rc[n]->bitrate   = pMP[n]->bitrate   = LayerBitRate[n];
            rc[n]->framerate = pMP[n]->framerate = LayerFrameRate[n];

            // For h263 or short header mode, the bit variation is within (-2*Rmax*1001/3000, 2*Rmax*1001/3000)
            if (video->encParams->H263_Enabled)
            {
                rc[n]->max_BitVariance_num = (Int)((rc[n]->Bs - video->encParams->maxFrameSize) / 2 / (rc[n]->bitrate / rc[n]->framerate / 10.0)) - 5;
                //rc[n]->max_BitVariance_num = (Int)((float)(rc[n]->Bs - rc[n]->VBV_fullness)/((float)LayerBitRate[n]/LayerFrameRate[n]/10.0))-5;
            }
            else   // MPEG-4 normal modes
            {
                rc[n]->max_BitVariance_num = (Int)((float)(rc[n]->Bs - VBV_fullness) * 10 / ((float)LayerBitRate[n] / LayerFrameRate[n])) - 5;
            }
        }
        else
        {
            if (LayerFrameRate[n] - LayerFrameRate[n-1] > 0) /*  7/31/03 */
            {
                rc[n]->TMN_TH = (Int)((float)(LayerBitRate[n] - LayerBitRate[n-1]) / (LayerFrameRate[n] - LayerFrameRate[n-1]));
                rc[n]->max_BitVariance_num = (Int)((float)(rc[n]->Bs - VBV_fullness) * 10 / ((float)rc[n]->TMN_TH)) - 5;
                if (rc[n]->max_BitVariance_num < 0) rc[n]->max_BitVariance_num += 5;
            }
            else   /*  7/31/03 */
            {
                rc[n]->TMN_TH = 1 << 30;
                rc[n]->max_BitVariance_num = 0;
            }
            rc[n]->bitrate   = pMP[n]->bitrate   = LayerBitRate[n] - LayerBitRate[n-1];
            rc[n]->framerate = pMP[n]->framerate = LayerFrameRate[n] - LayerFrameRate[n-1];
        }

        pMP[n]->target_bits_per_frame_prev = pMP[n]->target_bits_per_frame;
        pMP[n]->target_bits_per_frame = pMP[n]->bitrate / (float)(pMP[n]->framerate + 0.0001);  /*  7/31/03 */

        /* rc[n]->VBV_fullness and rc[n]->TMN_W should be kept same */
        /* update pMP[n]->counter_BTdst and pMP[n]->counter_BTsrc   */
        diff_counter = (Int)((float)(rc[n]->VBV_fullness - rc[n]->TMN_W) /
                             (pMP[n]->target_bits_per_frame / 10 + 0.0001)); /*  7/31/03 */

        pMP[n]->counter_BTdst = pMP[n]->counter_BTsrc = 0;
        if (diff_counter > 0)
            pMP[n]->counter_BTdst = diff_counter;

        else if (diff_counter < 0)
            pMP[n]->counter_BTsrc = -diff_counter;

        rc[n]->TMN_W = (Int)(rc[n]->VBV_fullness -      /* re-calculate rc[n]->TMN_W in order for higher accuracy */
                             (pMP[n]->target_bits_per_frame / 10) * (pMP[n]->counter_BTdst - pMP[n]->counter_BTsrc));

        /* Keep the current average mad */
        if (pMP[n]->aver_mad != 0)
        {
            pMP[n]->aver_mad_prev = pMP[n]->aver_mad;
            pMP[n]->encoded_frames_prev = pMP[n]->encoded_frames;
        }

        pMP[n]->aver_mad = 0;
        pMP[n]->overlapped_win_size = 4;

        /* Misc */
        pMP[n]->sum_mad = pMP[n]->sum_QP = 0;
        //pMP[n]->encoded_frames_prev = pMP[n]->encoded_frames;
        pMP[n]->encoded_frames = pMP[n]->re_encoded_frames = pMP[n]->re_encoded_times = 0;

    } /* end of: for(n=0; n<numLayers; n++) */

    return PV_SUCCESS;

}


/* ================================================================================ */
/*  Function : targetBitCalculation                                                 */
/*  Date     : 10/01/2001                                                           */
/*  Purpose  : quadratic bit allocation model: T(n) = C*sqrt(mad(n)/aver_mad(n-1))  */
/*                                                                                  */
/*  In/out   : rc->T                                                                */
/*  Return   : Void                                                                 */
/*  Modified :                                                                      */
/* ================================================================================ */

void targetBitCalculation(void *input)
{
    VideoEncData *video = (VideoEncData *) input;
    MultiPass *pMP = video->pMP[video->currLayer];
    Vol *currVol = video->vol[video->currLayer];
    rateControl *rc = video->rc[video->currLayer];

    float curr_mad;//, average_mad;
    Int diff_counter_BTsrc, diff_counter_BTdst, prev_counter_diff, curr_counter_diff, bound;
    /* BT = Bit Transfer, for pMP->counter_BTsrc, pMP->counter_BTdst */

    if (video == NULL || currVol == NULL || pMP == NULL || rc == NULL)
        return;

    /* some stuff about frame dropping remained here to be done because pMP cannot be inserted into updateRateControl()*/
    updateRC_PostProc(rc, video);

    /* update pMP->counter_BTsrc and pMP->counter_BTdst to avoid interger overflow */
    if (pMP->counter_BTsrc > 1000 && pMP->counter_BTdst > 1000)
    {
        pMP->counter_BTsrc -= 1000;
        pMP->counter_BTdst -= 1000;
    }

    /* ---------------------------------------------------------------------------------------------------*/
    /* target calculation */
    curr_mad = video->sumMAD / (float)currVol->nTotalMB;
    if (curr_mad < MAD_MIN) curr_mad = MAD_MIN; /* MAD_MIN is defined as 1 in mp4def.h */
    diff_counter_BTsrc = diff_counter_BTdst = 0;
    pMP->diff_counter = 0;


    /*1.calculate average mad */
    pMP->sum_mad += curr_mad;
    //average_mad = (pMP->encoded_frames < 1 ? curr_mad : pMP->sum_mad/(float)(pMP->encoded_frames+1)); /* this function is called from the scond encoded frame*/
    //pMP->aver_mad = average_mad;
    if (pMP->encoded_frames >= 0) /* pMP->encoded_frames is set to -1 initially, so forget about the very first I frame */
        pMP->aver_mad = (pMP->aver_mad * pMP->encoded_frames + curr_mad) / (pMP->encoded_frames + 1);

    if (pMP->overlapped_win_size > 0 && pMP->encoded_frames_prev >= 0)  /*  7/31/03 */
        pMP->aver_mad_prev = (pMP->aver_mad_prev * pMP->encoded_frames_prev + curr_mad) / (pMP->encoded_frames_prev + 1);

    /*2.average_mad, mad ==> diff_counter_BTsrc, diff_counter_BTdst */
    if (pMP->overlapped_win_size == 0)
    {
        /* original verison */
        if (curr_mad > pMP->aver_mad*1.1)
        {
            if (curr_mad / (pMP->aver_mad + 0.0001) > 2)
                diff_counter_BTdst = (Int)(M4VENC_SQRT(curr_mad / (pMP->aver_mad + 0.0001)) * 10 + 0.4) - 10;
            //diff_counter_BTdst = (Int)((sqrt(curr_mad/pMP->aver_mad)*2+curr_mad/pMP->aver_mad)/(3*0.1) + 0.4) - 10;
            else
                diff_counter_BTdst = (Int)(curr_mad / (pMP->aver_mad + 0.0001) * 10 + 0.4) - 10;
        }
        else /* curr_mad <= average_mad*1.1 */
            //diff_counter_BTsrc = 10 - (Int)((sqrt(curr_mad/pMP->aver_mad) + pow(curr_mad/pMP->aver_mad, 1.0/3.0))/(2.0*0.1) + 0.4);
            diff_counter_BTsrc = 10 - (Int)(M4VENC_SQRT(curr_mad / (pMP->aver_mad + 0.0001)) * 10 + 0.5);
        //diff_counter_BTsrc = 10 - (Int)(curr_mad/pMP->aver_mad/0.1 + 0.5)

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
                diff_counter_BTdst = (Int)(M4VENC_SQRT(curr_mad / (pMP->aver_mad_prev + 0.0001)) * 10 + 0.4) - 10;
            //diff_counter_BTdst = (Int)((M4VENC_SQRT(curr_mad/pMP->aver_mad_prev)*2+curr_mad/pMP->aver_mad_prev)/(3*0.1) + 0.4) - 10;
            else
                diff_counter_BTdst = (Int)(curr_mad / (pMP->aver_mad_prev + 0.0001) * 10 + 0.4) - 10;
        }
        else /* curr_mad <= average_mad*1.1 */
            //diff_counter_BTsrc = 10 - (Int)((sqrt(curr_mad/pMP->aver_mad_prev) + pow(curr_mad/pMP->aver_mad_prev, 1.0/3.0))/(2.0*0.1) + 0.4);
            diff_counter_BTsrc = 10 - (Int)(M4VENC_SQRT(curr_mad / (pMP->aver_mad_prev + 0.0001)) * 10 + 0.5);
        //diff_counter_BTsrc = 10 - (Int)(curr_mad/pMP->aver_mad_prev/0.1 + 0.5)

        /* actively fill in the possible gap */
        if (diff_counter_BTsrc == 0 && diff_counter_BTdst == 0 &&
                curr_mad <= pMP->aver_mad_prev*1.1 && pMP->counter_BTsrc < pMP->counter_BTdst)
            diff_counter_BTsrc = 1;

        if (--pMP->overlapped_win_size <= 0)    pMP->overlapped_win_size = 0;
    }


    /* if difference is too much, do clipping */
    /* First, set the upper bound for current bit allocation variance: 80% of available buffer */
    bound = (Int)((rc->Bs / 2 - rc->VBV_fullness) * 0.6 / (pMP->target_bits_per_frame / 10)); /* rc->Bs */
    diff_counter_BTsrc =  PV_MIN(diff_counter_BTsrc, bound);
    diff_counter_BTdst =  PV_MIN(diff_counter_BTdst, bound);

    /* Second, set another upper bound for current bit allocation: 4-5*bitrate/framerate */
    bound = 50;
//  if(video->encParams->RC_Type == CBR_LOWDELAY)
//  not necessary       bound = 10;     /*  1/17/02 -- For Low delay */

    diff_counter_BTsrc =  PV_MIN(diff_counter_BTsrc, bound);
    diff_counter_BTdst =  PV_MIN(diff_counter_BTdst, bound);


    /* Third, check the buffer */
    prev_counter_diff = pMP->counter_BTdst - pMP->counter_BTsrc;
    curr_counter_diff = prev_counter_diff + (diff_counter_BTdst - diff_counter_BTsrc);

    if (PV_ABS(prev_counter_diff) >= rc->max_BitVariance_num || PV_ABS(curr_counter_diff) >= rc->max_BitVariance_num) // PV_ABS(curr_counter_diff) >= PV_ABS(prev_counter_diff) )
    {   //diff_counter_BTsrc = diff_counter_BTdst = 0;

        if (curr_counter_diff > rc->max_BitVariance_num && diff_counter_BTdst)
        {
            diff_counter_BTdst = (rc->max_BitVariance_num - prev_counter_diff) + diff_counter_BTsrc;
            if (diff_counter_BTdst < 0) diff_counter_BTdst = 0;
        }

        else if (curr_counter_diff < -rc->max_BitVariance_num && diff_counter_BTsrc)
        {
            diff_counter_BTsrc = diff_counter_BTdst - (-rc->max_BitVariance_num - prev_counter_diff);
            if (diff_counter_BTsrc < 0) diff_counter_BTsrc = 0;
        }
    }


    /*3.diff_counter_BTsrc, diff_counter_BTdst ==> TMN_TH */
    //rc->TMN_TH = (Int)((float)pMP->bitrate/pMP->framerate);
    rc->TMN_TH = (Int)(pMP->target_bits_per_frame);
    pMP->diff_counter = 0;

    if (diff_counter_BTsrc)
    {
        rc->TMN_TH -= (Int)(pMP->target_bits_per_frame * diff_counter_BTsrc * 0.1);
        pMP->diff_counter = -diff_counter_BTsrc;
    }
    else if (diff_counter_BTdst)
    {
        rc->TMN_TH += (Int)(pMP->target_bits_per_frame * diff_counter_BTdst * 0.1);
        pMP->diff_counter = diff_counter_BTdst;
    }


    /*4.update pMP->counter_BTsrc, pMP->counter_BTdst */
    pMP->counter_BTsrc += diff_counter_BTsrc;
    pMP->counter_BTdst += diff_counter_BTdst;


    /*5.target bit calculation */
    rc->T = rc->TMN_TH - rc->TMN_W;
    //rc->T = rc->TMN_TH - (Int)((float)rc->TMN_W/rc->frameRate);

    if (video->encParams->H263_Enabled && rc->T > video->encParams->maxFrameSize)
    {
        rc->T = video->encParams->maxFrameSize;  //  added this 11/07/05
    }

}

/* ================================================================================ */
/*  Function : calculateQuantizer_Multipass                                         */
/*  Date     : 10/01/2001                                                           */
/*  Purpose  : variable rate bit allocation + new QP determination scheme           */
/*                                                                                  */
/*  In/out   : rc->T and rc->Qc                                                     */
/*  Return   : Void                                                                 */
/*  Modified :                                                                      */
/* ================================================================================ */

/* Mad based variable bit allocation + QP calculation with a new quadratic method */
void calculateQuantizer_Multipass(void *input)
{
    VideoEncData *video = (VideoEncData *) input;
    MultiPass *pMP = video->pMP[video->currLayer];
    Vol *currVol = video->vol[video->currLayer];
    rateControl *rc = video->rc[video->currLayer];

    Int prev_QP, prev_actual_bits, curr_target, i, j;

    float curr_mad, prev_mad, curr_RD, prev_RD, average_mad, aver_QP;


    if (video == NULL || currVol == NULL || pMP == NULL || rc == NULL)
        return;

    /* Mad based variable bit allocation */
    targetBitCalculation((void*) video);

    if (rc->T <= 0 || video->sumMAD == 0)
    {
        if (rc->T < 0)  rc->Qc = 31;
        return;
    }

    /* ---------------------------------------------------------------------------------------------------*/
    /* current frame QP estimation */
    curr_target = rc->T;
    curr_mad = video->sumMAD / (float)currVol->nTotalMB;
    if (curr_mad < MAD_MIN) curr_mad = MAD_MIN; /* MAD_MIN is defined as 1 in mp4def.h */
    curr_RD  = (float)curr_target / curr_mad;

    /* Another version of search the optimal point */
    prev_actual_bits = pMP->pRDSamples[0][0].actual_bits;
    prev_mad = pMP->pRDSamples[0][0].mad;

    for (i = 0, j = 0; i < pMP->frameRange; i++)
    {
        if (pMP->pRDSamples[i][0].mad != 0 && prev_mad != 0 &&
                PV_ABS(prev_mad - curr_mad) > PV_ABS(pMP->pRDSamples[i][0].mad - curr_mad))
        {
            prev_mad = pMP->pRDSamples[i][0].mad;
            prev_actual_bits = pMP->pRDSamples[i][0].actual_bits;
            j = i;
        }
    }
    prev_QP = pMP->pRDSamples[j][0].QP;
    for (i = 1; i < pMP->samplesPerFrame[j]; i++)
    {
        if (PV_ABS(prev_actual_bits - curr_target) > PV_ABS(pMP->pRDSamples[j][i].actual_bits - curr_target))
        {
            prev_actual_bits = pMP->pRDSamples[j][i].actual_bits;
            prev_QP = pMP->pRDSamples[j][i].QP;
        }
    }

    // quadratic approximation
    prev_RD = (float)prev_actual_bits / prev_mad;
    //rc->Qc = (Int)(prev_QP * sqrt(prev_actual_bits/curr_target) + 0.4);
    if (prev_QP == 1) // 11/14/05, added this to allow getting out of QP = 1 easily
    {
        rc->Qc = (Int)(prev_RD / curr_RD + 0.5);
    }
    else
    {
        rc->Qc = (Int)(prev_QP * M4VENC_SQRT(prev_RD / curr_RD) + 0.9);

        if (prev_RD / curr_RD > 0.5 && prev_RD / curr_RD < 2.0)
            rc->Qc = (Int)(prev_QP * (M4VENC_SQRT(prev_RD / curr_RD) + prev_RD / curr_RD) / 2.0 + 0.9); /* Quadratic and linear approximation */
        else
            rc->Qc = (Int)(prev_QP * (M4VENC_SQRT(prev_RD / curr_RD) + M4VENC_POW(prev_RD / curr_RD, 1.0 / 3.0)) / 2.0 + 0.9);
    }
    //rc->Qc =(Int)(prev_QP * sqrt(prev_RD/curr_RD) + 0.4);
    // 11/08/05
    // lower bound on Qc should be a function of curr_mad
    // When mad is already low, lower bound on Qc doesn't have to be small.
    // Note, this doesn't work well for low complexity clip encoded at high bit rate
    // it doesn't hit the target bit rate due to this QP lower bound.
/// if((curr_mad < 8) && (rc->Qc < 12)) rc->Qc = 12;
//  else    if((curr_mad < 128) && (rc->Qc < 3)) rc->Qc = 3;

    if (rc->Qc < 1) rc->Qc = 1;
    if (rc->Qc > 31)    rc->Qc = 31;


    /* active bit resource protection */
    aver_QP = (pMP->encoded_frames == 0 ? 0 : pMP->sum_QP / (float)pMP->encoded_frames);
    average_mad = (pMP->encoded_frames == 0 ? 0 : pMP->sum_mad / (float)pMP->encoded_frames); /* this function is called from the scond encoded frame*/
    if (pMP->diff_counter == 0 &&
            ((float)rc->Qc <= aver_QP*1.1 || curr_mad <= average_mad*1.1) &&
            pMP->counter_BTsrc <= (pMP->counter_BTdst + (Int)(pMP->framerate*1.0 + 0.5)))
    {
        rc->TMN_TH -= (Int)(pMP->target_bits_per_frame / 10.0);
        rc->T = rc->TMN_TH - rc->TMN_W;
        pMP->counter_BTsrc++;
        pMP->diff_counter--;
    }

}


/* ======================================================================== */
/*  Function : updateRateControl                                            */
/*  Date     : 11/17/2000                                                   */
/*  Purpose  :Update the RD Modal (After Encoding the Current Frame)        */
/*  In/out   :                                                              */
/*  Return   :                                                              */
/*  Modified :                                                              */
/* ======================================================================== */

void updateRateControl(rateControl *rc, VideoEncData *video)
{
    Int  frame_bits;


    /* rate contro\l */
    frame_bits = (Int)(rc->bitrate / rc->framerate);
    rc->TMN_W += (rc->Rc - rc->TMN_TH);
    rc->VBV_fullness += (rc->Rc - frame_bits); //rc->Rp);
    //if(rc->VBV_fullness < 0) rc->VBV_fullness = -1;

    rc->encoded_frames++;

    /* frame dropping */
    rc->skip_next_frame = 0;

    if ((video->encParams->H263_Enabled && rc->Rc > video->encParams->maxFrameSize) || /*  For H263/short header mode, drop the frame if the actual frame size exceeds the bound */
            (rc->VBV_fullness > rc->Bs / 2 && !rc->no_pre_skip)) /* skip the current frame */ /* rc->Bs */
    {
        rc->TMN_W -= (rc->Rc - rc->TMN_TH);
        rc->VBV_fullness -= rc->Rc;
        rc->skip_next_frame = -1;
    }
    else if ((float)(rc->VBV_fullness - rc->VBV_fullness_offset) > (rc->Bs / 2 - rc->VBV_fullness_offset)*0.95 &&
             !rc->no_frame_skip) /* skip next frame */
    {
        rc->VBV_fullness -= frame_bits; //rc->Rp;
        rc->skip_next_frame = 1;
        /*  skip more than 1 frames  */
        //while(rc->VBV_fullness > rc->Bs*0.475)
        while ((rc->VBV_fullness - rc->VBV_fullness_offset) > (rc->Bs / 2 - rc->VBV_fullness_offset)*0.95)
        {
            rc->VBV_fullness -= frame_bits; //rc->Rp;
            rc->skip_next_frame++;
        }
        /* END  */
    }

}

/* ======================================================================== */
/*  Function : updateRC_PostProc                                            */
/*  Date     : 04/08/2002                                                   */
/*  Purpose  : Remaing RC update stuff for frame skip and buffer underflow  */
/*             check                                                        */
/*  In/out   :                                                              */
/*  Return   :                                                              */
/*  Modified :                                                              */
/* ======================================================================== */
void updateRC_PostProc(rateControl *rc, VideoEncData *video)
{
    MultiPass *pMP = video->pMP[video->currLayer];

    if (rc->skip_next_frame == 1 && !rc->no_frame_skip) /* skip next frame */
    {
        pMP->counter_BTsrc += 10 * rc->skip_next_frame;

    }
    else if (rc->skip_next_frame == -1 && !rc->no_pre_skip) /* skip current frame */
    {
        pMP->counter_BTdst -= pMP->diff_counter;
        pMP->counter_BTsrc += 10;

        pMP->sum_mad -= pMP->mad;
        pMP->aver_mad = (pMP->aver_mad * pMP->encoded_frames - pMP->mad) / (float)(pMP->encoded_frames - 1 + 0.0001);
        pMP->sum_QP  -= pMP->QP;
        pMP->encoded_frames --;
    }
    /* some stuff in update VBV_fullness remains here */
    //if(rc->VBV_fullness < -rc->Bs/2) /* rc->Bs */
    if (rc->VBV_fullness < rc->low_bound)
    {
        rc->VBV_fullness = rc->low_bound; // -rc->Bs/2;
        rc->TMN_W = rc->VBV_fullness - rc->low_bound;
        pMP->counter_BTsrc = pMP->counter_BTdst + (Int)((float)(rc->Bs / 2 - rc->low_bound) / 2.0 / (pMP->target_bits_per_frame / 10));
    }
}

