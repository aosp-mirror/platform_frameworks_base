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
#include "bitstream_io.h"
#include "rate_control.h"
#include "m4venc_oscl.h"


/* Inverse normal zigzag */
const static Int zigzag_i[NCOEFF_BLOCK] =
{
    0, 1, 8, 16, 9, 2, 3, 10,
    17, 24, 32, 25, 18, 11, 4, 5,
    12, 19, 26, 33, 40, 48, 41, 34,
    27, 20, 13, 6, 7, 14, 21, 28,
    35, 42, 49, 56, 57, 50, 43, 36,
    29, 22, 15, 23, 30, 37, 44, 51,
    58, 59, 52, 45, 38, 31, 39, 46,
    53, 60, 61, 54, 47, 55, 62, 63
};

/* INTRA */
const static Int mpeg_iqmat_def[NCOEFF_BLOCK] =
    {  8, 17, 18, 19, 21, 23, 25, 27,
       17, 18, 19, 21, 23, 25, 27, 28,
       20, 21, 22, 23, 24, 26, 28, 30,
       21, 22, 23, 24, 26, 28, 30, 32,
       22, 23, 24, 26, 28, 30, 32, 35,
       23, 24, 26, 28, 30, 32, 35, 38,
       25, 26, 28, 30, 32, 35, 38, 41,
       27, 28, 30, 32, 35, 38, 41, 45
    };

/* INTER */
const static Int mpeg_nqmat_def[64]  =
    { 16, 17, 18, 19, 20, 21, 22, 23,
      17, 18, 19, 20, 21, 22, 23, 24,
      18, 19, 20, 21, 22, 23, 24, 25,
      19, 20, 21, 22, 23, 24, 26, 27,
      20, 21, 22, 23, 25, 26, 27, 28,
      21, 22, 23, 24, 26, 27, 28, 30,
      22, 23, 24, 26, 27, 28, 30, 31,
      23, 24, 25, 27, 28, 30, 31, 33
    };

/* Profiles and levels */
/* Simple profile(level 0-3) and Core profile (level 1-2) */
/* {SPL0, SPL1, SPL2, SPL3, CPL1, CPL2, CPL2, CPL2} , SPL0: Simple Profile@Level0, CPL1: Core Profile@Level1, the last two are redundant for easy table manipulation */
const static Int profile_level_code[8] =
{
    0x08, 0x01, 0x02, 0x03, 0x21, 0x22, 0x22, 0x22
};

const static Int profile_level_max_bitrate[8] =
{
    64000, 64000, 128000, 384000, 384000, 2000000, 2000000, 2000000
};

const static Int profile_level_max_packet_size[8] =
{
    2048, 2048, 4096, 8192, 4096, 8192, 8192, 8192
};

const static Int profile_level_max_mbsPerSec[8] =
{
    1485, 1485, 5940, 11880, 5940, 23760, 23760, 23760
};

const static Int profile_level_max_VBV_size[8] =
{
    163840, 163840, 655360, 655360, 262144, 1310720, 1310720, 1310720
};


/* Simple scalable profile (level 0-2) and Core scalable profile (level 1-3) */
/* {SSPL0, SSPL1, SSPL2, SSPL2, CSPL1, CSPL2, CSPL3, CSPL3} , SSPL0: Simple Scalable Profile@Level0, CSPL1: Core Scalable Profile@Level1, the fourth is redundant for easy table manipulation */

const static Int scalable_profile_level_code[8] =
{
    0x10, 0x11, 0x12, 0x12, 0xA1, 0xA2, 0xA3, 0xA3
};

const static Int scalable_profile_level_max_bitrate[8] =
{
    128000, 128000, 256000, 256000, 768000, 1500000, 4000000, 4000000
};

/* in bits */
const static Int scalable_profile_level_max_packet_size[8] =
{
    2048, 2048, 4096, 4096, 4096, 4096, 16384, 16384
};

const static Int scalable_profile_level_max_mbsPerSec[8] =
{
    1485, 7425, 23760, 23760, 14850, 29700, 120960, 120960
};

const static Int scalable_profile_level_max_VBV_size[8] =
{
    163840, 655360, 655360, 655360, 1048576, 1310720, 1310720, 1310720
};


/* H263 profile 0 @ level 10-70 */
const static Int   h263Level[8] = {0, 10, 20, 30, 40, 50, 60, 70};
const static float rBR_bound[8] = {0, 1, 2, 6, 32, 64, 128, 256};
const static float max_h263_framerate[2] = {(float)30000 / (float)2002,
        (float)30000 / (float)1001
                                           };
const static Int   max_h263_width[2]  = {176, 352};
const static Int   max_h263_height[2] = {144, 288};

/* 6/2/2001, newly added functions to make PVEncodeVop more readable. */
Int DetermineCodingLayer(VideoEncData *video, Int *nLayer, ULong modTime);
void DetermineVopType(VideoEncData *video, Int currLayer);
Int UpdateSkipNextFrame(VideoEncData *video, ULong *modTime, Int *size, PV_STATUS status);
Bool SetProfile_BufferSize(VideoEncData *video, float delay, Int bInitialized);

#ifdef PRINT_RC_INFO
extern FILE *facct;
extern int tiTotalNumBitsGenerated;
extern int iStuffBits;
#endif

#ifdef PRINT_EC
extern FILE *fec;
#endif


/* ======================================================================== */
/*  Function : PVGetDefaultEncOption()                                      */
/*  Date     : 12/12/2005                                                   */
/*  Purpose  :                                                              */
/*  In/out   :                                                              */
/*  Return   : PV_TRUE if successed, PV_FALSE if failed.                    */
/*  Modified :                                                              */
/*                                                                          */
/* ======================================================================== */

OSCL_EXPORT_REF Bool PVGetDefaultEncOption(VideoEncOptions *encOption, Int encUseCase)
{
    VideoEncOptions defaultUseCase = {H263_MODE, profile_level_max_packet_size[SIMPLE_PROFILE_LEVEL0] >> 3,
                                      SIMPLE_PROFILE_LEVEL0, PV_OFF, 0, 1, 1000, 33, {144, 144}, {176, 176}, {15, 30}, {64000, 128000},
                                      {10, 10}, {12, 12}, {0, 0}, CBR_1, 0.0, PV_OFF, -1, 0, PV_OFF, 16, PV_OFF, 0, PV_ON
                                     };

    OSCL_UNUSED_ARG(encUseCase); // unused for now. Later we can add more defaults setting and use this
    // argument to select the right one.
    /* in the future we can create more meaningful use-cases */
    if (encOption == NULL)
    {
        return PV_FALSE;
    }

    M4VENC_MEMCPY(encOption, &defaultUseCase, sizeof(VideoEncOptions));

    return PV_TRUE;
}

/* ======================================================================== */
/*  Function : PVInitVideoEncoder()                                         */
/*  Date     : 08/22/2000                                                   */
/*  Purpose  : Initialization of MP4 Encoder and VO bitstream               */
/*  In/out   :                                                              */
/*  Return   : PV_TRUE if successed, PV_FALSE if failed.                    */
/*  Modified :  5/21/01, allocate only yChan and assign uChan & vChan   */
/*              12/12/05, add encoding option as input argument         */
/* ======================================================================== */
OSCL_EXPORT_REF Bool    PVInitVideoEncoder(VideoEncControls *encoderControl, VideoEncOptions *encOption)
{

    Bool        status = PV_TRUE;
    Int         nLayers, idx, i, j;
    Int         max = 0, max_width = 0, max_height = 0, pitch, offset;
    Int         size = 0, nTotalMB = 0;
    VideoEncData *video;
    Vol         *pVol;
    VideoEncParams  *pEncParams;
    Int         temp_w, temp_h, mbsPerSec;

    /******************************************/
    /*      this part use to be PVSetEncode() */
    Int profile_table_index, *profile_level_table;
    Int profile_level = encOption->profile_level;
    Int PacketSize = encOption->packetSize << 3;
    Int timeInc, timeIncRes;
    float profile_max_framerate;
    VideoEncParams *encParams;

    if (encoderControl->videoEncoderData) /* this has been called */
    {
        if (encoderControl->videoEncoderInit) /* check if PVInitVideoEncoder() has been called  */
        {
            PVCleanUpVideoEncoder(encoderControl);
            encoderControl->videoEncoderInit = 0;
        }

        M4VENC_FREE(encoderControl->videoEncoderData);
        encoderControl->videoEncoderData = NULL;
    }
    encoderControl->videoEncoderInit = 0;   /* reset this value */

    video = (VideoEncData *)M4VENC_MALLOC(sizeof(VideoEncData)); /* allocate memory for encData */

    if (video == NULL)
        return PV_FALSE;

    M4VENC_MEMSET(video, 0, sizeof(VideoEncData));

    encoderControl->videoEncoderData = (void *) video;         /* set up pointer in VideoEncData structure */

    video->encParams = (VideoEncParams *)M4VENC_MALLOC(sizeof(VideoEncParams));
    if (video->encParams == NULL)
        goto CLEAN_UP;

    M4VENC_MEMSET(video->encParams, 0, sizeof(VideoEncParams));

    encParams = video->encParams;
    encParams->nLayers = encOption->numLayers;

    /* Check whether the input packetsize is valid (Note: put code here (before any memory allocation) in order to avoid memory leak */
    if ((Int)profile_level < (Int)(SIMPLE_SCALABLE_PROFILE_LEVEL0))  /* non-scalable profile */
    {
        profile_level_table = (Int *)profile_level_max_packet_size;
        profile_table_index = (Int)profile_level;
        if (encParams->nLayers != 1)
        {
            goto CLEAN_UP;
        }

        encParams->LayerMaxMbsPerSec[0] = profile_level_max_mbsPerSec[profile_table_index];

    }
    else   /* scalable profile */
    {
        profile_level_table = (Int *)scalable_profile_level_max_packet_size;
        profile_table_index = (Int)profile_level - (Int)(SIMPLE_SCALABLE_PROFILE_LEVEL0);
        if (encParams->nLayers < 2)
        {
            goto CLEAN_UP;
        }
        for (i = 0; i < encParams->nLayers; i++)
        {
            encParams->LayerMaxMbsPerSec[i] = scalable_profile_level_max_mbsPerSec[profile_table_index];
        }

    }

    /* cannot have zero size packet with these modes */
    if (PacketSize == 0)
    {
        if (encOption->encMode == DATA_PARTITIONING_MODE)
        {
            goto CLEAN_UP;
        }
        if (encOption->encMode == COMBINE_MODE_WITH_ERR_RES)
        {
            encOption->encMode = COMBINE_MODE_NO_ERR_RES;
        }
    }

    if (encOption->gobHeaderInterval == 0)
    {
        if (encOption->encMode == H263_MODE_WITH_ERR_RES)
        {
            encOption->encMode = H263_MODE;
        }

        if (encOption->encMode == SHORT_HEADER_WITH_ERR_RES)
        {
            encOption->encMode = SHORT_HEADER;
        }
    }

    if (PacketSize > profile_level_table[profile_table_index])
        goto CLEAN_UP;

    /* Initial Defaults for all Modes */

    encParams->SequenceStartCode = 1;
    encParams->GOV_Enabled = 0;
    encParams->RoundingType = 0;
    encParams->IntraDCVlcThr = PV_MAX(PV_MIN(encOption->intraDCVlcTh, 7), 0);
    encParams->ACDCPrediction = ((encOption->useACPred == PV_ON) ? TRUE : FALSE);
    encParams->RC_Type = encOption->rcType;
    encParams->Refresh = encOption->numIntraMB;
    encParams->ResyncMarkerDisable = 0; /* Enable Resync Marker */

    for (i = 0; i < encOption->numLayers; i++)
    {
#ifdef NO_MPEG_QUANT
        encParams->QuantType[i] = 0;
#else
        encParams->QuantType[i] = encOption->quantType[i];      /* H263 */
#endif
        if (encOption->pQuant[i] >= 1 && encOption->pQuant[i] <= 31)
        {
            encParams->InitQuantPvop[i] = encOption->pQuant[i];
        }
        else
        {
            goto CLEAN_UP;
        }
        if (encOption->iQuant[i] >= 1 && encOption->iQuant[i] <= 31)
        {
            encParams->InitQuantIvop[i] = encOption->iQuant[i];
        }
        else
        {
            goto CLEAN_UP;
        }
    }

    encParams->HalfPel_Enabled = 1;
    encParams->SearchRange = encOption->searchRange; /* 4/16/2001 */
    encParams->FullSearch_Enabled = 0;
#ifdef NO_INTER4V
    encParams->MV8x8_Enabled = 0;
#else
    encParams->MV8x8_Enabled = 0;// comment out for now!! encOption->mv8x8Enable;
#endif
    encParams->H263_Enabled = 0;
    encParams->GOB_Header_Interval = 0; // need to be reset to 0
    encParams->IntraPeriod = encOption->intraPeriod;    /* Intra update period update default*/
    encParams->SceneChange_Det = encOption->sceneDetect;
    encParams->FineFrameSkip_Enabled = 0;
    encParams->NoFrameSkip_Enabled = encOption->noFrameSkipped;
    encParams->NoPreSkip_Enabled = encOption->noFrameSkipped;
    encParams->GetVolHeader[0] = 0;
    encParams->GetVolHeader[1] = 0;
    encParams->ResyncPacketsize = encOption->packetSize << 3;
    encParams->LayerMaxBitRate[0] = 0;
    encParams->LayerMaxBitRate[1] = 0;
    encParams->LayerMaxFrameRate[0] = (float)0.0;
    encParams->LayerMaxFrameRate[1] = (float)0.0;
    encParams->VBV_delay = encOption->vbvDelay;  /* 2sec VBV buffer size */

    switch (encOption->encMode)
    {

        case SHORT_HEADER:
        case SHORT_HEADER_WITH_ERR_RES:

            /* From Table 6-26 */
            encParams->nLayers = 1;
            encParams->QuantType[0] = 0;    /*H263 */
            encParams->ResyncMarkerDisable = 1; /* Disable Resync Marker */
            encParams->DataPartitioning = 0; /* Combined Mode */
            encParams->ReversibleVLC = 0;   /* Disable RVLC */
            encParams->RoundingType = 0;
            encParams->IntraDCVlcThr = 7;   /* use_intra_dc_vlc = 0 */
            encParams->MV8x8_Enabled = 0;

            encParams->GOB_Header_Interval = encOption->gobHeaderInterval;
            encParams->H263_Enabled = 2;
            encParams->GOV_Enabled = 0;
            encParams->TimeIncrementRes = 30000;        /* timeIncrementRes for H263 */
            break;

        case H263_MODE:
        case H263_MODE_WITH_ERR_RES:

            /* From Table 6-26 */
            encParams->nLayers = 1;
            encParams->QuantType[0] = 0;    /*H263 */
            encParams->ResyncMarkerDisable = 1; /* Disable Resync Marker */
            encParams->DataPartitioning = 0; /* Combined Mode */
            encParams->ReversibleVLC = 0;   /* Disable RVLC */
            encParams->RoundingType = 0;
            encParams->IntraDCVlcThr = 7;   /* use_intra_dc_vlc = 0 */
            encParams->MV8x8_Enabled = 0;

            encParams->H263_Enabled = 1;
            encParams->GOV_Enabled = 0;
            encParams->TimeIncrementRes = 30000;        /* timeIncrementRes for H263 */

            break;
#ifndef H263_ONLY
        case DATA_PARTITIONING_MODE:

            encParams->DataPartitioning = 1;        /* Base Layer Data Partitioning */
            encParams->ResyncMarkerDisable = 0; /* Resync Marker */
#ifdef NO_RVLC
            encParams->ReversibleVLC = 0;
#else
            encParams->ReversibleVLC = (encOption->rvlcEnable == PV_ON); /* RVLC when Data Partitioning */
#endif
            encParams->ResyncPacketsize = PacketSize;
            break;

        case COMBINE_MODE_WITH_ERR_RES:

            encParams->DataPartitioning = 0;        /* Combined Mode */
            encParams->ResyncMarkerDisable = 0; /* Resync Marker */
            encParams->ReversibleVLC = 0;           /* No RVLC */
            encParams->ResyncPacketsize = PacketSize;
            break;

        case COMBINE_MODE_NO_ERR_RES:

            encParams->DataPartitioning = 0;        /* Combined Mode */
            encParams->ResyncMarkerDisable = 1; /* Disable Resync Marker */
            encParams->ReversibleVLC = 0;           /* No RVLC */
            break;
#endif
        default:
            goto CLEAN_UP;
    }
    /* Set the constraints (maximum values) according to the input profile and level */
    /* Note that profile_table_index is already figured out above */

    /* base layer */
    encParams->profile_table_index    = profile_table_index; /* Used to limit the profile and level in SetProfile_BufferSize() */

    /* check timeIncRes */
    timeIncRes = encOption->timeIncRes;
    timeInc = encOption->tickPerSrc;

    if ((timeIncRes >= 1) && (timeIncRes <= 65536) && (timeInc < timeIncRes) && (timeInc != 0))
    {
        if (!encParams->H263_Enabled)
        {
            encParams->TimeIncrementRes = timeIncRes;
        }
        else
        {
            encParams->TimeIncrementRes = 30000;
//          video->FrameRate = 30000/(float)1001; /* fix it to 29.97 fps */
        }
        video->FrameRate = timeIncRes / ((float)timeInc);
    }
    else
    {
        goto CLEAN_UP;
    }

    /* check frame dimension */
    if (encParams->H263_Enabled)
    {
        switch (encOption->encWidth[0])
        {
            case 128:
                if (encOption->encHeight[0] != 96) /* source_format = 1 */
                    goto CLEAN_UP;
                break;
            case 176:
                if (encOption->encHeight[0] != 144) /* source_format = 2 */
                    goto CLEAN_UP;
                break;
            case 352:
                if (encOption->encHeight[0] != 288) /* source_format = 2 */
                    goto CLEAN_UP;
                break;

            case 704:
                if (encOption->encHeight[0] != 576) /* source_format = 2 */
                    goto CLEAN_UP;
                break;
            case 1408:
                if (encOption->encHeight[0] != 1152) /* source_format = 2 */
                    goto CLEAN_UP;
                break;

            default:
                goto CLEAN_UP;
        }
    }
    for (i = 0; i < encParams->nLayers; i++)
    {
        encParams->LayerHeight[i] = encOption->encHeight[i];
        encParams->LayerWidth[i] = encOption->encWidth[i];
    }

    /* check frame rate */
    for (i = 0; i < encParams->nLayers; i++)
    {
        encParams->LayerFrameRate[i] = encOption->encFrameRate[i];
    }

    if (encParams->nLayers > 1)
    {
        if (encOption->encFrameRate[0] == encOption->encFrameRate[1] ||
                encOption->encFrameRate[0] == 0. || encOption->encFrameRate[1] == 0.) /* 7/31/03 */
            goto CLEAN_UP;
    }
    /* set max frame rate */
    for (i = 0; i < encParams->nLayers; i++)
    {

        /* Make sure the maximum framerate is consistent with the given profile and level */
        nTotalMB = ((encParams->LayerWidth[i] + 15) / 16) * ((encParams->LayerHeight[i] + 15) / 16);

        if (nTotalMB > 0)
            profile_max_framerate = (float)encParams->LayerMaxMbsPerSec[i] / (float)nTotalMB;

        else
            profile_max_framerate = (float)30.0;

        encParams->LayerMaxFrameRate[i] = PV_MIN(profile_max_framerate, encParams->LayerFrameRate[i]);
    }

    /* check bit rate */
    /* set max bit rate */
    for (i = 0; i < encParams->nLayers; i++)
    {
        encParams->LayerBitRate[i] = encOption->bitRate[i];
        encParams->LayerMaxBitRate[i] = encOption->bitRate[i];
    }
    if (encParams->nLayers > 1)
    {
        if (encOption->bitRate[0] == encOption->bitRate[1] ||
                encOption->bitRate[0] == 0 || encOption->bitRate[1] == 0) /* 7/31/03 */
            goto CLEAN_UP;
    }
    /* check rate control and vbv delay*/
    encParams->RC_Type = encOption->rcType;

    if (encOption->vbvDelay == 0.0) /* set to default */
    {
        switch (encOption->rcType)
        {
            case CBR_1:
            case CBR_2:
                encParams->VBV_delay = (float)2.0; /* default 2sec VBV buffer size */
                break;

            case CBR_LOWDELAY:
                encParams->VBV_delay = (float)0.5; /* default 0.5sec VBV buffer size */
                break;

            case VBR_1:
            case VBR_2:
                encParams->VBV_delay = (float)10.0; /* default 10sec VBV buffer size */
                break;
            default:
                break;
        }
    }
    else /* force this value */
    {
        encParams->VBV_delay = encOption->vbvDelay;
    }

    /* check search range */
    if (encParams->H263_Enabled && encOption->searchRange > 16)
    {
        encParams->SearchRange = 16; /* 4/16/2001 */
    }

    /*****************************************/
    /* checking for conflict between options */
    /*****************************************/

    if (video->encParams->RC_Type == CBR_1 || video->encParams->RC_Type == CBR_2 || video->encParams->RC_Type == CBR_LOWDELAY)  /* if CBR */
    {
#ifdef _PRINT_STAT
        if (video->encParams->NoFrameSkip_Enabled == PV_ON ||
                video->encParams->NoPreSkip_Enabled == PV_ON) /* don't allow frame skip*/
            printf("WARNING!!!! CBR with NoFrameSkip\n");
#endif
    }
    else if (video->encParams->RC_Type == CONSTANT_Q)   /* constant_Q */
    {
        video->encParams->NoFrameSkip_Enabled = PV_ON;  /* no frame skip */
        video->encParams->NoPreSkip_Enabled = PV_ON;    /* no frame skip */
#ifdef _PRINT_STAT
        printf("Turn on NoFrameSkip\n");
#endif
    }

    if (video->encParams->NoFrameSkip_Enabled == PV_ON) /* if no frame skip */
    {
        video->encParams->FineFrameSkip_Enabled = PV_OFF;
#ifdef _PRINT_STAT
        printf("NoFrameSkip !!! may violate VBV_BUFFER constraint.\n");
        printf("Turn off FineFrameSkip\n");
#endif
    }

    /******************************************/
    /******************************************/

    nLayers = video->encParams->nLayers; /* Number of Layers to be encoded */

    /* Find the maximum width*height for memory allocation of the VOPs */
    for (idx = 0; idx < nLayers; idx++)
    {
        temp_w = video->encParams->LayerWidth[idx];
        temp_h = video->encParams->LayerHeight[idx];

        if ((temp_w*temp_h) > max)
        {
            max = temp_w * temp_h;
            max_width = ((temp_w + 15) >> 4) << 4;
            max_height = ((temp_h + 15) >> 4) << 4;
            nTotalMB = ((max_width * max_height) >> 8);
        }

        /* Check if the video size and framerate(MBsPerSec) are vald */
        mbsPerSec = (Int)(nTotalMB * video->encParams->LayerFrameRate[idx]);
        if (mbsPerSec > video->encParams->LayerMaxMbsPerSec[idx]) status = PV_FALSE;
    }

    /****************************************************/
    /* Set Profile and Video Buffer Size for each layer */
    /****************************************************/
    if (video->encParams->RC_Type == CBR_LOWDELAY) video->encParams->VBV_delay = 0.5; /* For CBR_LOWDELAY, we set 0.5sec buffer */
    status = SetProfile_BufferSize(video, video->encParams->VBV_delay, 1);
    if (status != PV_TRUE)
        goto CLEAN_UP;

    /****************************************/
    /* memory allocation and initialization */
    /****************************************/

    if (video == NULL) goto CLEAN_UP;

    /* cyclic reference for passing through both structures */
    video->videoEncControls = encoderControl;

    //video->currLayer = 0; /* Set current Layer to 0 */
    //video->currFrameNo = 0; /* Set current frame Number to 0 */
    video->nextModTime = 0;
    video->nextEncIVop = 0; /* Sets up very first frame to be I-VOP! */
    video->numVopsInGOP = 0; /* counter for Vops in Gop, 2/8/01 */

    //video->frameRate = video->encParams->LayerFrameRate[0]; /* Set current layer frame rate */

    video->QPMB = (UChar *) M4VENC_MALLOC(nTotalMB * sizeof(UChar)); /* Memory for MB quantizers */
    if (video->QPMB == NULL) goto CLEAN_UP;


    video->headerInfo.Mode = (UChar *) M4VENC_MALLOC(sizeof(UChar) * nTotalMB); /* Memory for MB Modes */
    if (video->headerInfo.Mode == NULL) goto CLEAN_UP;
    video->headerInfo.CBP = (UChar *) M4VENC_MALLOC(sizeof(UChar) * nTotalMB);   /* Memory for CBP (Y and C) of each MB */
    if (video->headerInfo.CBP == NULL) goto CLEAN_UP;

    /* Allocating motion vector space and interpolation memory*/

    video->mot = (MOT **)M4VENC_MALLOC(sizeof(MOT *) * nTotalMB);
    if (video->mot == NULL) goto CLEAN_UP;

    for (idx = 0; idx < nTotalMB; idx++)
    {
        video->mot[idx] = (MOT *)M4VENC_MALLOC(sizeof(MOT) * 8);
        if (video->mot[idx] == NULL)
        {
            goto CLEAN_UP;
        }
    }

    video->intraArray = (UChar *)M4VENC_MALLOC(sizeof(UChar) * nTotalMB);
    if (video->intraArray == NULL) goto CLEAN_UP;

    video->sliceNo = (UChar *) M4VENC_MALLOC(nTotalMB); /* Memory for Slice Numbers */
    if (video->sliceNo == NULL) goto CLEAN_UP;
    /* Allocating space for predDCAC[][8][16], Not that I intentionally  */
    /*    increase the dimension of predDCAC from [][6][15] to [][8][16] */
    /*    so that compilers can generate faster code to indexing the     */
    /*    data inside (by using << instead of *).         04/14/2000. */
    /* 5/29/01, use  decoder lib ACDC prediction memory scheme.  */
    video->predDC = (typeDCStore *) M4VENC_MALLOC(nTotalMB * sizeof(typeDCStore));
    if (video->predDC == NULL) goto CLEAN_UP;

    if (!video->encParams->H263_Enabled)
    {
        video->predDCAC_col = (typeDCACStore *) M4VENC_MALLOC(((max_width >> 4) + 1) * sizeof(typeDCACStore));
        if (video->predDCAC_col == NULL) goto CLEAN_UP;

        /* element zero will be used for storing vertical (col) AC coefficients */
        /*  the rest will be used for storing horizontal (row) AC coefficients  */
        video->predDCAC_row = video->predDCAC_col + 1;        /*  ACDC */

        video->acPredFlag = (Int *) M4VENC_MALLOC(nTotalMB * sizeof(Int)); /* Memory for acPredFlag */
        if (video->acPredFlag == NULL) goto CLEAN_UP;
    }

    video->outputMB = (MacroBlock *) M4VENC_MALLOC(sizeof(MacroBlock)); /* Allocating macroblock space */
    if (video->outputMB == NULL) goto CLEAN_UP;
    M4VENC_MEMSET(video->outputMB->block[0], 0, (sizeof(Short) << 6)*6);

    M4VENC_MEMSET(video->dataBlock, 0, sizeof(Short) << 7);
    /* Allocate (2*packetsize) working bitstreams */

    video->bitstream1 = BitStreamCreateEnc(2 * 4096); /*allocate working stream 1*/
    if (video->bitstream1 == NULL) goto CLEAN_UP;
    video->bitstream2 = BitStreamCreateEnc(2 * 4096); /*allocate working stream 2*/
    if (video->bitstream2 == NULL) goto CLEAN_UP;
    video->bitstream3 = BitStreamCreateEnc(2 * 4096); /*allocate working stream 3*/
    if (video->bitstream3 == NULL) goto CLEAN_UP;

    /* allocate overrun buffer */
    // this buffer is used when user's buffer is too small to hold one frame.
    // It is not needed for slice-based encoding.
    if (nLayers == 1)
    {
        video->oBSize = encParams->BufferSize[0] >> 3;
    }
    else
    {
        video->oBSize = PV_MAX((encParams->BufferSize[0] >> 3), (encParams->BufferSize[1] >> 3));
    }

    if (video->oBSize > DEFAULT_OVERRUN_BUFFER_SIZE || encParams->RC_Type == CONSTANT_Q) // set limit
    {
        video->oBSize = DEFAULT_OVERRUN_BUFFER_SIZE;
    }
    video->overrunBuffer = (UChar*) M4VENC_MALLOC(sizeof(UChar) * video->oBSize);
    if (video->overrunBuffer == NULL) goto CLEAN_UP;


    video->currVop = (Vop *) M4VENC_MALLOC(sizeof(Vop)); /* Memory for Current VOP */
    if (video->currVop == NULL) goto CLEAN_UP;

    /* add padding, 09/19/05 */
    if (video->encParams->H263_Enabled) /* make it conditional  11/28/05 */
    {
        pitch = max_width;
        offset = 0;
    }
    else
    {
        pitch = max_width + 32;
        offset = (pitch << 4) + 16;
        max_height += 32;
    }
    size = pitch * max_height;

    video->currVop->yChan = (PIXEL *)M4VENC_MALLOC(sizeof(PIXEL) * (size + (size >> 1))); /* Memory for currVop Y */
    if (video->currVop->yChan == NULL) goto CLEAN_UP;
    video->currVop->uChan = video->currVop->yChan + size;/* Memory for currVop U */
    video->currVop->vChan = video->currVop->uChan + (size >> 2);/* Memory for currVop V */

    /* shift for the offset */
    if (offset)
    {
        video->currVop->yChan += offset; /* offset to the origin.*/
        video->currVop->uChan += (offset >> 2) + 4;
        video->currVop->vChan += (offset >> 2) + 4;
    }

    video->forwardRefVop = video->currVop;      /*  Initialize forwardRefVop */
    video->backwardRefVop = video->currVop;     /*  Initialize backwardRefVop */

    video->prevBaseVop = (Vop *) M4VENC_MALLOC(sizeof(Vop));         /* Memory for Previous Base Vop */
    if (video->prevBaseVop == NULL) goto CLEAN_UP;
    video->prevBaseVop->yChan = (PIXEL *) M4VENC_MALLOC(sizeof(PIXEL) * (size + (size >> 1))); /* Memory for prevBaseVop Y */
    if (video->prevBaseVop->yChan == NULL) goto CLEAN_UP;
    video->prevBaseVop->uChan = video->prevBaseVop->yChan + size; /* Memory for prevBaseVop U */
    video->prevBaseVop->vChan = video->prevBaseVop->uChan + (size >> 2); /* Memory for prevBaseVop V */

    if (offset)
    {
        video->prevBaseVop->yChan += offset; /* offset to the origin.*/
        video->prevBaseVop->uChan += (offset >> 2) + 4;
        video->prevBaseVop->vChan += (offset >> 2) + 4;
    }


    if (0) /* If B Frames */
    {
        video->nextBaseVop = (Vop *) M4VENC_MALLOC(sizeof(Vop));         /* Memory for Next Base Vop */
        if (video->nextBaseVop == NULL) goto CLEAN_UP;
        video->nextBaseVop->yChan = (PIXEL *) M4VENC_MALLOC(sizeof(PIXEL) * (size + (size >> 1))); /* Memory for nextBaseVop Y */
        if (video->nextBaseVop->yChan == NULL) goto CLEAN_UP;
        video->nextBaseVop->uChan = video->nextBaseVop->yChan + size; /* Memory for nextBaseVop U */
        video->nextBaseVop->vChan = video->nextBaseVop->uChan + (size >> 2); /* Memory for nextBaseVop V */

        if (offset)
        {
            video->nextBaseVop->yChan += offset; /* offset to the origin.*/
            video->nextBaseVop->uChan += (offset >> 2) + 4;
            video->nextBaseVop->vChan += (offset >> 2) + 4;
        }
    }

    if (nLayers > 1)   /* If enhancement layers */
    {
        video->prevEnhanceVop = (Vop *) M4VENC_MALLOC(sizeof(Vop));      /* Memory for Previous Enhancement Vop */
        if (video->prevEnhanceVop == NULL) goto CLEAN_UP;
        video->prevEnhanceVop->yChan = (PIXEL *) M4VENC_MALLOC(sizeof(PIXEL) * (size + (size >> 1))); /* Memory for Previous Ehancement Y */
        if (video->prevEnhanceVop->yChan == NULL) goto CLEAN_UP;
        video->prevEnhanceVop->uChan = video->prevEnhanceVop->yChan + size; /* Memory for Previous Enhancement U */
        video->prevEnhanceVop->vChan = video->prevEnhanceVop->uChan + (size >> 2); /* Memory for Previous Enhancement V */

        if (offset)
        {
            video->prevEnhanceVop->yChan += offset; /* offset to the origin.*/
            video->prevEnhanceVop->uChan += (offset >> 2) + 4;
            video->prevEnhanceVop->vChan += (offset >> 2) + 4;
        }
    }

    video->numberOfLayers = nLayers; /* Number of Layers */
    video->sumMAD = 0;


    /* 04/09/01, for Vops in the use multipass processing */
    for (idx = 0; idx < nLayers; idx++)
    {
        video->pMP[idx] = (MultiPass *)M4VENC_MALLOC(sizeof(MultiPass));
        if (video->pMP[idx] == NULL)    goto CLEAN_UP;
        M4VENC_MEMSET(video->pMP[idx], 0, sizeof(MultiPass));

        video->pMP[idx]->encoded_frames = -1; /* forget about the very first I frame */


        /* RDInfo **pRDSamples */
        video->pMP[idx]->pRDSamples = (RDInfo **)M4VENC_MALLOC(30 * sizeof(RDInfo *));
        if (video->pMP[idx]->pRDSamples == NULL)    goto CLEAN_UP;
        for (i = 0; i < 30; i++)
        {
            video->pMP[idx]->pRDSamples[i] = (RDInfo *)M4VENC_MALLOC(32 * sizeof(RDInfo));
            if (video->pMP[idx]->pRDSamples[i] == NULL) goto CLEAN_UP;
            for (j = 0; j < 32; j++)    M4VENC_MEMSET(&(video->pMP[idx]->pRDSamples[i][j]), 0, sizeof(RDInfo));
        }
        video->pMP[idx]->frameRange = (Int)(video->encParams->LayerFrameRate[idx] * 1.0); /* 1.0s time frame*/
        video->pMP[idx]->frameRange = PV_MAX(video->pMP[idx]->frameRange, 5);
        video->pMP[idx]->frameRange = PV_MIN(video->pMP[idx]->frameRange, 30);

        video->pMP[idx]->framePos = -1;

    }
    /* /// End /////////////////////////////////////// */


    video->vol = (Vol **)M4VENC_MALLOC(nLayers * sizeof(Vol *)); /* Memory for VOL pointers */

    /* Memory allocation and Initialization of Vols and writing of headers */
    if (video->vol == NULL) goto CLEAN_UP;

    for (idx = 0; idx < nLayers; idx++)
    {
        video->volInitialize[idx] = 1;
        video->refTick[idx] = 0;
        video->relLayerCodeTime[idx] = 1000;
        video->vol[idx] = (Vol *)M4VENC_MALLOC(sizeof(Vol));
        if (video->vol[idx] == NULL)  goto CLEAN_UP;

        pVol = video->vol[idx];
        pEncParams = video->encParams;

        M4VENC_MEMSET(video->vol[idx], 0, sizeof(Vol));
        /* Initialize some VOL parameters */
        pVol->volID = idx;  /* Set VOL ID */
        pVol->shortVideoHeader = pEncParams->H263_Enabled; /*Short Header */
        pVol->GOVStart = pEncParams->GOV_Enabled; /* GOV Header */
        pVol->timeIncrementResolution = video->encParams->TimeIncrementRes;
        pVol->nbitsTimeIncRes = 1;
        while (pVol->timeIncrementResolution > (1 << pVol->nbitsTimeIncRes))
        {
            pVol->nbitsTimeIncRes++;
        }

        /* timing stuff */
        pVol->timeIncrement = 0;
        pVol->moduloTimeBase = 0;
        pVol->fixedVopRate = 0; /* No fixed VOP rate */
        pVol->stream = (BitstreamEncVideo *)M4VENC_MALLOC(sizeof(BitstreamEncVideo)); /* allocate BitstreamEncVideo Instance */
        if (pVol->stream == NULL)  goto CLEAN_UP;

        pVol->width = pEncParams->LayerWidth[idx];      /* Layer Width */
        pVol->height = pEncParams->LayerHeight[idx];    /* Layer Height */
        //  pVol->intra_acdcPredDisable = pEncParams->ACDCPrediction; /* ACDC Prediction */
        pVol->ResyncMarkerDisable = pEncParams->ResyncMarkerDisable; /* Resync Marker Mode */
        pVol->dataPartitioning = pEncParams->DataPartitioning; /* Data Partitioning */
        pVol->useReverseVLC = pEncParams->ReversibleVLC; /* RVLC */
        if (idx > 0) /* Scalability layers */
        {
            pVol->ResyncMarkerDisable = 1;
            pVol->dataPartitioning = 0;
            pVol->useReverseVLC = 0; /*  No RVLC */
        }
        pVol->quantType = pEncParams->QuantType[idx];           /* Quantizer Type */

        /* no need to init Quant Matrices */

        pVol->scalability = 0;  /* Vol Scalability */
        if (idx > 0)
            pVol->scalability = 1; /* Multiple layers => Scalability */

        /* Initialize Vol to Temporal scalability.  It can change during encoding */
        pVol->scalType = 1;
        /* Initialize reference Vol ID to the base layer = 0 */
        pVol->refVolID = 0;
        /* Initialize layer resolution to same as the reference */
        pVol->refSampDir = 0;
        pVol->horSamp_m = 1;
        pVol->horSamp_n = 1;
        pVol->verSamp_m = 1;
        pVol->verSamp_n = 1;
        pVol->enhancementType = 0; /* We always enhance the entire region */

        pVol->nMBPerRow = (pVol->width + 15) / 16;
        pVol->nMBPerCol = (pVol->height + 15) / 16;
        pVol->nTotalMB = pVol->nMBPerRow * pVol->nMBPerCol;

        if (pVol->nTotalMB >= 1)
            pVol->nBitsForMBID = 1;
        if (pVol->nTotalMB >= 3)
            pVol->nBitsForMBID = 2;
        if (pVol->nTotalMB >= 5)
            pVol->nBitsForMBID = 3;
        if (pVol->nTotalMB >= 9)
            pVol->nBitsForMBID = 4;
        if (pVol->nTotalMB >= 17)
            pVol->nBitsForMBID = 5;
        if (pVol->nTotalMB >= 33)
            pVol->nBitsForMBID = 6;
        if (pVol->nTotalMB >= 65)
            pVol->nBitsForMBID = 7;
        if (pVol->nTotalMB >= 129)
            pVol->nBitsForMBID = 8;
        if (pVol->nTotalMB >= 257)
            pVol->nBitsForMBID = 9;
        if (pVol->nTotalMB >= 513)
            pVol->nBitsForMBID = 10;
        if (pVol->nTotalMB >= 1025)
            pVol->nBitsForMBID = 11;
        if (pVol->nTotalMB >= 2049)
            pVol->nBitsForMBID = 12;
        if (pVol->nTotalMB >= 4097)
            pVol->nBitsForMBID = 13;
        if (pVol->nTotalMB >= 8193)
            pVol->nBitsForMBID = 14;
        if (pVol->nTotalMB >= 16385)
            pVol->nBitsForMBID = 15;
        if (pVol->nTotalMB >= 32769)
            pVol->nBitsForMBID = 16;
        if (pVol->nTotalMB >= 65537)
            pVol->nBitsForMBID = 17;
        if (pVol->nTotalMB >= 131073)
            pVol->nBitsForMBID = 18;

        if (pVol->shortVideoHeader)
        {
            switch (pVol->width)
            {
                case 128:
                    if (pVol->height == 96)  /* source_format = 1 */
                    {
                        pVol->nGOBinVop = 6;
                        pVol->nMBinGOB = 8;
                    }
                    else
                        status = PV_FALSE;
                    break;

                case 176:
                    if (pVol->height == 144)  /* source_format = 2 */
                    {
                        pVol->nGOBinVop = 9;
                        pVol->nMBinGOB = 11;
                    }
                    else
                        status = PV_FALSE;
                    break;
                case 352:
                    if (pVol->height == 288)  /* source_format = 2 */
                    {
                        pVol->nGOBinVop = 18;
                        pVol->nMBinGOB = 22;
                    }
                    else
                        status = PV_FALSE;
                    break;

                case 704:
                    if (pVol->height == 576)  /* source_format = 2 */
                    {
                        pVol->nGOBinVop = 18;
                        pVol->nMBinGOB = 88;
                    }
                    else
                        status = PV_FALSE;
                    break;
                case 1408:
                    if (pVol->height == 1152)  /* source_format = 2 */
                    {
                        pVol->nGOBinVop = 18;
                        pVol->nMBinGOB = 352;
                    }
                    else
                        status = PV_FALSE;
                    break;

                default:
                    status = PV_FALSE;
                    break;
            }
        }
    }

    /***************************************************/
    /* allocate and initialize rate control parameters */
    /***************************************************/

    /* BEGIN INITIALIZATION OF ANNEX L RATE CONTROL */
    if (video->encParams->RC_Type != CONSTANT_Q)
    {
        for (idx = 0; idx < nLayers; idx++) /* 12/25/00 */
        {
            video->rc[idx] =
                (rateControl *)M4VENC_MALLOC(sizeof(rateControl));

            if (video->rc[idx] == NULL) goto CLEAN_UP;

            M4VENC_MEMSET(video->rc[idx], 0, sizeof(rateControl));
        }
        if (PV_SUCCESS != RC_Initialize(video))
        {
            goto CLEAN_UP;
        }
        /* initialization for 2-pass rate control */
    }
    /* END INITIALIZATION OF ANNEX L RATE CONTROL */

    /********** assign platform dependent functions ***********************/
    /* 1/23/01 */
    /* This must be done at run-time not a compile time */
    video->functionPointer = (FuncPtr*) M4VENC_MALLOC(sizeof(FuncPtr));
    if (video->functionPointer == NULL) goto CLEAN_UP;

    video->functionPointer->ComputeMBSum = &ComputeMBSum_C;
    video->functionPointer->SAD_MB_HalfPel[0] = NULL;
    video->functionPointer->SAD_MB_HalfPel[1] = &SAD_MB_HalfPel_Cxh;
    video->functionPointer->SAD_MB_HalfPel[2] = &SAD_MB_HalfPel_Cyh;
    video->functionPointer->SAD_MB_HalfPel[3] = &SAD_MB_HalfPel_Cxhyh;

#ifndef NO_INTER4V
    video->functionPointer->SAD_Blk_HalfPel = &SAD_Blk_HalfPel_C;
    video->functionPointer->SAD_Block = &SAD_Block_C;
#endif
    video->functionPointer->SAD_Macroblock = &SAD_Macroblock_C;
    video->functionPointer->ChooseMode = &ChooseMode_C;
    video->functionPointer->GetHalfPelMBRegion = &GetHalfPelMBRegion_C;
//  video->functionPointer->SAD_MB_PADDING = &SAD_MB_PADDING; /* 4/21/01 */


    encoderControl->videoEncoderInit = 1;  /* init done! */

    return PV_TRUE;

CLEAN_UP:
    PVCleanUpVideoEncoder(encoderControl);

    return PV_FALSE;
}


/* ======================================================================== */
/*  Function : PVCleanUpVideoEncoder()                                      */
/*  Date     : 08/22/2000                                                   */
/*  Purpose  : Deallocates allocated memory from InitVideoEncoder()         */
/*  In/out   :                                                              */
/*  Return   : PV_TRUE if successed, PV_FALSE if failed.                    */
/*  Modified : 5/21/01, free only yChan in Vop                          */
/*                                                                          */
/* ======================================================================== */

OSCL_EXPORT_REF Bool    PVCleanUpVideoEncoder(VideoEncControls *encoderControl)
{
    Int idx, i;
    VideoEncData *video = (VideoEncData *)encoderControl->videoEncoderData;
    int nTotalMB;
    int max_width, offset;

#ifdef PRINT_RC_INFO
    if (facct != NULL)
    {
        fprintf(facct, "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@\n");
        fprintf(facct, "TOTAL NUM BITS GENERATED %d\n", tiTotalNumBitsGenerated);
        fprintf(facct, "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@\n");
        fprintf(facct, "TOTAL NUMBER OF FRAMES CODED %d\n",
                video->encParams->rc[0]->totalFrameNumber);
        fprintf(facct, "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@\n");
        fprintf(facct, "Average BitRate %d\n",
                (tiTotalNumBitsGenerated / (90 / 30)));
        fprintf(facct, "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@\n");
        fprintf(facct, "TOTAL NUMBER OF STUFF BITS %d\n", (iStuffBits + 10740));
        fprintf(facct, "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@\n");
        fprintf(facct, "TOTAL NUMBER OF BITS TO NETWORK %d\n", (35800*90 / 30));;
        fprintf(facct, "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@\n");
        fprintf(facct, "SUM OF STUFF BITS AND GENERATED BITS %d\n",
                (tiTotalNumBitsGenerated + iStuffBits + 10740));
        fprintf(facct, "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@\n");
        fprintf(facct, "UNACCOUNTED DIFFERENCE %d\n",
                ((35800*90 / 30) - (tiTotalNumBitsGenerated + iStuffBits + 10740)));
        fprintf(facct, "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@\n");
        fclose(facct);
    }
#endif

#ifdef PRINT_EC
    fclose(fec);
#endif

    if (video != NULL)
    {

        if (video->QPMB) M4VENC_FREE(video->QPMB);
        if (video->headerInfo.Mode)M4VENC_FREE(video->headerInfo.Mode);
        if (video->headerInfo.CBP)M4VENC_FREE(video->headerInfo.CBP);


        if (video->mot)
        {
            nTotalMB = video->vol[0]->nTotalMB;
            for (idx = 1; idx < video->currLayer; idx++)
                if (video->vol[idx]->nTotalMB > nTotalMB)
                    nTotalMB = video->vol[idx]->nTotalMB;
            for (idx = 0; idx < nTotalMB; idx++)
            {
                if (video->mot[idx])
                    M4VENC_FREE(video->mot[idx]);
            }
            M4VENC_FREE(video->mot);
        }

        if (video->intraArray) M4VENC_FREE(video->intraArray);

        if (video->sliceNo)M4VENC_FREE(video->sliceNo);
        if (video->acPredFlag)M4VENC_FREE(video->acPredFlag);
//      if(video->predDCAC)M4VENC_FREE(video->predDCAC);
        if (video->predDC) M4VENC_FREE(video->predDC);
        video->predDCAC_row = NULL;
        if (video->predDCAC_col) M4VENC_FREE(video->predDCAC_col);
        if (video->outputMB)M4VENC_FREE(video->outputMB);

        if (video->bitstream1)BitstreamCloseEnc(video->bitstream1);
        if (video->bitstream2)BitstreamCloseEnc(video->bitstream2);
        if (video->bitstream3)BitstreamCloseEnc(video->bitstream3);

        if (video->overrunBuffer) M4VENC_FREE(video->overrunBuffer);

        max_width = video->encParams->LayerWidth[0];
        max_width = (((max_width + 15) >> 4) << 4); /* 09/19/05 */
        if (video->encParams->H263_Enabled)
        {
            offset = 0;
        }
        else
        {
            offset = ((max_width + 32) << 4) + 16;
        }

        if (video->currVop)
        {
            if (video->currVop->yChan)
            {
                video->currVop->yChan -= offset;
                M4VENC_FREE(video->currVop->yChan);
            }
            M4VENC_FREE(video->currVop);
        }

        if (video->nextBaseVop)
        {
            if (video->nextBaseVop->yChan)
            {
                video->nextBaseVop->yChan -= offset;
                M4VENC_FREE(video->nextBaseVop->yChan);
            }
            M4VENC_FREE(video->nextBaseVop);
        }

        if (video->prevBaseVop)
        {
            if (video->prevBaseVop->yChan)
            {
                video->prevBaseVop->yChan -= offset;
                M4VENC_FREE(video->prevBaseVop->yChan);
            }
            M4VENC_FREE(video->prevBaseVop);
        }
        if (video->prevEnhanceVop)
        {
            if (video->prevEnhanceVop->yChan)
            {
                video->prevEnhanceVop->yChan -= offset;
                M4VENC_FREE(video->prevEnhanceVop->yChan);
            }
            M4VENC_FREE(video->prevEnhanceVop);
        }

        /* 04/09/01, for Vops in the use multipass processing */
        for (idx = 0; idx < video->encParams->nLayers; idx++)
        {
            if (video->pMP[idx])
            {
                if (video->pMP[idx]->pRDSamples)
                {
                    for (i = 0; i < 30; i++)
                    {
                        if (video->pMP[idx]->pRDSamples[i])
                            M4VENC_FREE(video->pMP[idx]->pRDSamples[i]);
                    }
                    M4VENC_FREE(video->pMP[idx]->pRDSamples);
                }

                M4VENC_MEMSET(video->pMP[idx], 0, sizeof(MultiPass));
                M4VENC_FREE(video->pMP[idx]);
            }
        }
        /* //  End /////////////////////////////////////// */

        if (video->vol)
        {
            for (idx = 0; idx < video->encParams->nLayers; idx++)
            {
                if (video->vol[idx])
                {
                    if (video->vol[idx]->stream)
                        M4VENC_FREE(video->vol[idx]->stream);
                    M4VENC_FREE(video->vol[idx]);
                }
            }
            M4VENC_FREE(video->vol);
        }

        /***************************************************/
        /* stop rate control parameters */
        /***************************************************/

        /* ANNEX L RATE CONTROL */
        if (video->encParams->RC_Type != CONSTANT_Q)
        {
            RC_Cleanup(video->rc, video->encParams->nLayers);

            for (idx = 0; idx < video->encParams->nLayers; idx++)
            {
                if (video->rc[idx])
                    M4VENC_FREE(video->rc[idx]);
            }
        }

        if (video->functionPointer) M4VENC_FREE(video->functionPointer);

        /* If application has called PVCleanUpVideoEncoder then we deallocate */
        /* If PVInitVideoEncoder class it, then we DO NOT deallocate */
        if (video->encParams)
        {
            M4VENC_FREE(video->encParams);
        }

        M4VENC_FREE(video);
        encoderControl->videoEncoderData = NULL; /* video */
    }

    encoderControl->videoEncoderInit = 0;

    return PV_TRUE;
}

/* ======================================================================== */
/*  Function : PVGetVolHeader()                                             */
/*  Date     : 7/17/2001,                                                   */
/*  Purpose  :                                                              */
/*  In/out   :                                                              */
/*  Return   : PV_TRUE if successed, PV_FALSE if failed.                    */
/*  Modified :                                                              */
/*                                                                          */
/* ======================================================================== */

OSCL_EXPORT_REF Bool PVGetVolHeader(VideoEncControls *encCtrl, UChar *volHeader, Int *size, Int layer)
{
    VideoEncData    *encData;
    PV_STATUS   EncodeVOS_Start(VideoEncControls *encCtrl);
    encData = (VideoEncData *)encCtrl->videoEncoderData;


    if (encData == NULL)
        return PV_FALSE;
    if (encData->encParams == NULL)
        return PV_FALSE;


    encData->currLayer = layer; /* Set Layer */
    /*pv_status = */
    EncodeVOS_Start(encCtrl); /* Encode VOL Header */

    encData->encParams->GetVolHeader[layer] = 1; /* Set usage flag: Needed to support old method*/

    /* Copy bitstream to buffer and set the size */

    if (*size > encData->bitstream1->byteCount)
    {
        *size = encData->bitstream1->byteCount;
        M4VENC_MEMCPY(volHeader, encData->bitstream1->bitstreamBuffer, *size);
    }
    else
        return PV_FALSE;

    /* Reset bitstream1 buffer parameters */
    BitstreamEncReset(encData->bitstream1);

    return PV_TRUE;
}

/* ======================================================================== */
/*  Function : PVGetOverrunBuffer()                                         */
/*  Purpose  : Get the overrun buffer `                                     */
/*  In/out   :                                                              */
/*  Return   : Pointer to overrun buffer.                                   */
/*  Modified :                                                              */
/* ======================================================================== */

OSCL_EXPORT_REF UChar* PVGetOverrunBuffer(VideoEncControls *encCtrl)
{
    VideoEncData *video = (VideoEncData *)encCtrl->videoEncoderData;
    Int currLayer = video->currLayer;
    Vol *currVol = video->vol[currLayer];

    if (currVol->stream->bitstreamBuffer != video->overrunBuffer) // not used
    {
        return NULL;
    }

    return video->overrunBuffer;
}




/* ======================================================================== */
/*  Function : EncodeVideoFrame()                                           */
/*  Date     : 08/22/2000                                                   */
/*  Purpose  : Encode video frame and return bitstream                      */
/*  In/out   :                                                              */
/*  Return   : PV_TRUE if successed, PV_FALSE if failed.                    */
/*  Modified :                                                              */
/*  02.14.2001                                      */
/*              Finishing new timestamp 32-bit input                        */
/*              Applications need to take care of wrap-around               */
/* ======================================================================== */
OSCL_EXPORT_REF Bool PVEncodeVideoFrame(VideoEncControls *encCtrl, VideoEncFrameIO *vid_in, VideoEncFrameIO *vid_out,
                                        ULong *nextModTime, UChar *bstream, Int *size, Int *nLayer)
{
    Bool status = PV_TRUE;
    PV_STATUS pv_status;
    VideoEncData *video = (VideoEncData *)encCtrl->videoEncoderData;
    VideoEncParams *encParams = video->encParams;
    Vol *currVol;
    Vop *tempForwRefVop = NULL;
    Int tempRefSelCode = 0;
    PV_STATUS   EncodeVOS_Start(VideoEncControls *encCtrl);
    Int width_16, height_16;
    Int width, height;
    Vop *temp;
    Int encodeVop = 0;
    void  PaddingEdge(Vop *padVop);
    Int currLayer = -1;
    //Int nLayers = encParams->nLayers;

    ULong modTime = vid_in->timestamp;

#ifdef RANDOM_REFSELCODE   /* add random selection of reference Vop */
    Int random_val[30] = {0, 1, 1, 1, 0, 0, 1, 0, 1, 0, 0, 0, 1, 1, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 0, 1, 0, 1, 0, 0};
    static Int rand_idx = 0;
#endif

    /*******************************************************/
    /* Determine Next Vop to encode, if any, and nLayer    */
    /*******************************************************/
    //i = nLayers-1;

    if (video->volInitialize[0]) /* first vol to code */
    {
        video->nextModTime = video->modTimeRef = ((modTime) - ((modTime) % 1000));
    }

    encodeVop = DetermineCodingLayer(video, nLayer, modTime);
    currLayer = *nLayer;
    if ((currLayer < 0) || (currLayer > encParams->nLayers - 1))
        return PV_FALSE;

    /******************************************/
    /* If post-skipping still effective --- return */
    /******************************************/

    if (!encodeVop) /* skip enh layer, no base layer coded --- return */
    {
#ifdef _PRINT_STAT
        printf("No frame coded. Continue to next frame.");
#endif
        /* expected next code time, convert back to millisec */
        *nextModTime = video->nextModTime;

#ifdef ALLOW_VOP_NOT_CODED
        if (video->vol[0]->shortVideoHeader) /* Short Video Header = 1 */
        {
            *size = 0;
            *nLayer = -1;
        }
        else
        {
            *nLayer = 0;
            EncodeVopNotCoded(video, bstream, size, modTime);
            *size = video->vol[0]->stream->byteCount;
        }
#else
        *size = 0;
        *nLayer = -1;
#endif
        return status;
    }


//ENCODE_VOP_AGAIN:  /* 12/30/00 */

    /**************************************************************/
    /* Initialize Vol stream structure with application bitstream */
    /**************************************************************/

    currVol = video->vol[currLayer];
    currVol->stream->bitstreamBuffer = bstream;
    currVol->stream->bufferSize = *size;
    BitstreamEncReset(currVol->stream);
    BitstreamSetOverrunBuffer(currVol->stream, video->overrunBuffer, video->oBSize, video);

    /***********************************************************/
    /* Encode VOS and VOL Headers on first call for each layer */
    /***********************************************************/

    if (video->volInitialize[currLayer])
    {
        video->currVop->timeInc = 0;
        video->prevBaseVop->timeInc = 0;
        if (!video->encParams->GetVolHeader[currLayer])
            pv_status = EncodeVOS_Start(encCtrl);
    }

    /***************************************************/
    /* Copy Input Video Frame to Internal Video Buffer */
    /***************************************************/
    /* Determine Width and Height of Vop Layer */

    width = encParams->LayerWidth[currLayer];   /* Get input width */
    height = encParams->LayerHeight[currLayer]; /* Get input height */
    /* Round Up to nearest multiple of 16 : MPEG-4 Standard */

    width_16 = ((width + 15) / 16) * 16;            /* Round up to nearest multiple of 16 */
    height_16 = ((height + 15) / 16) * 16;          /* Round up to nearest multiple of 16 */

    video->input = vid_in;  /* point to the frame input */

    /*//  End ////////////////////////////// */


    /**************************************/
    /* Determine VOP Type                 */
    /* 6/2/2001, separate function      */
    /**************************************/
    DetermineVopType(video, currLayer);

    /****************************/
    /*    Initialize VOP        */
    /****************************/
    video->currVop->volID = currVol->volID;
    video->currVop->width = width_16;
    video->currVop->height = height_16;
    if (video->encParams->H263_Enabled) /*  11/28/05 */
    {
        video->currVop->pitch = width_16;
    }
    else
    {
        video->currVop->pitch = width_16 + 32;
    }
    video->currVop->timeInc = currVol->timeIncrement;
    video->currVop->vopCoded = 1;
    video->currVop->roundingType = 0;
    video->currVop->intraDCVlcThr = encParams->IntraDCVlcThr;

    if (currLayer == 0
#ifdef RANDOM_REFSELCODE   /* add random selection of reference Vop */
            || random_val[rand_idx] || video->volInitialize[currLayer]
#endif
       )
    {
        tempForwRefVop = video->forwardRefVop; /* keep initial state */
        if (tempForwRefVop != NULL) tempRefSelCode = tempForwRefVop->refSelectCode;

        video->forwardRefVop = video->prevBaseVop;
        video->forwardRefVop->refSelectCode = 1;
    }
#ifdef RANDOM_REFSELCODE
    else
    {
        tempForwRefVop = video->forwardRefVop; /* keep initial state */
        if (tempForwRefVop != NULL) tempRefSelCode = tempForwRefVop->refSelectCode;

        video->forwardRefVop = video->prevEnhanceVop;
        video->forwardRefVop->refSelectCode = 0;
    }
    rand_idx++;
    rand_idx %= 30;
#endif

    video->currVop->refSelectCode = video->forwardRefVop->refSelectCode;
    video->currVop->gobNumber = 0;
    video->currVop->gobFrameID = video->currVop->predictionType;
    video->currVop->temporalRef = (modTime * 30 / 1001) % 256;

    video->currVop->temporalInterval = 0;

    if (video->currVop->predictionType == I_VOP)
        video->currVop->quantizer = encParams->InitQuantIvop[currLayer];
    else
        video->currVop->quantizer = encParams->InitQuantPvop[currLayer];


    /****************/
    /* Encode Vop */
    /****************/
    video->slice_coding = 0;

    pv_status = EncodeVop(video);
#ifdef _PRINT_STAT
    if (video->currVop->predictionType == I_VOP)
        printf(" I-VOP ");
    else
        printf(" P-VOP (ref.%d)", video->forwardRefVop->refSelectCode);
#endif

    /************************************/
    /* Update Skip Next Frame           */
    /************************************/
    *nLayer = UpdateSkipNextFrame(video, nextModTime, size, pv_status);
    if (*nLayer == -1) /* skip current frame */
    {
        /* make sure that pointers are restored to the previous state */
        if (currLayer == 0)
        {
            video->forwardRefVop = tempForwRefVop; /* For P-Vop base only */
            video->forwardRefVop->refSelectCode = tempRefSelCode;
        }

        return status;
    }

    /* If I-VOP was encoded, reset IntraPeriod */
    if ((currLayer == 0) && (encParams->IntraPeriod > 0) && (video->currVop->predictionType == I_VOP))
        video->nextEncIVop = encParams->IntraPeriod;

    /* Set HintTrack Information */
    if (currLayer != -1)
    {
        if (currVol->prevModuloTimeBase)
            video->hintTrackInfo.MTB = 1;
        else
            video->hintTrackInfo.MTB = 0;
        video->hintTrackInfo.LayerID = (UChar)currVol->volID;
        video->hintTrackInfo.CodeType = (UChar)video->currVop->predictionType;
        video->hintTrackInfo.RefSelCode = (UChar)video->currVop->refSelectCode;
    }

    /************************************************/
    /* Determine nLayer and timeInc for next encode */
    /* 12/27/00 always go by the highest layer*/
    /************************************************/

    /**********************************************************/
    /* Copy Reconstructed Buffer to Output Video Frame Buffer */
    /**********************************************************/
    vid_out->yChan = video->currVop->yChan;
    vid_out->uChan = video->currVop->uChan;
    vid_out->vChan = video->currVop->vChan;
    if (video->encParams->H263_Enabled)
    {
        vid_out->height = video->currVop->height; /* padded height */
        vid_out->pitch = video->currVop->width; /* padded width */
    }
    else
    {
        vid_out->height = video->currVop->height + 32; /* padded height */
        vid_out->pitch = video->currVop->width + 32; /* padded width */
    }
    //video_out->timestamp = video->modTime;
    vid_out->timestamp = (ULong)(((video->prevFrameNum[currLayer] * 1000) / encParams->LayerFrameRate[currLayer]) + video->modTimeRef + 0.5);

    /*// End /////////////////////// */

    /***********************************/
    /* Update Ouput bstream byte count */
    /***********************************/

    *size = currVol->stream->byteCount;

    /****************************************/
    /* Swap Vop Pointers for Base Layer     */
    /****************************************/
    if (currLayer == 0)
    {
        temp = video->prevBaseVop;
        video->prevBaseVop = video->currVop;
        video->prevBaseVop->padded = 0; /* not padded */
        video->currVop  = temp;
        video->forwardRefVop = video->prevBaseVop; /* For P-Vop base only */
        video->forwardRefVop->refSelectCode = 1;
    }
    else
    {
        temp = video->prevEnhanceVop;
        video->prevEnhanceVop = video->currVop;
        video->prevEnhanceVop->padded = 0; /* not padded */
        video->currVop = temp;
        video->forwardRefVop = video->prevEnhanceVop;
        video->forwardRefVop->refSelectCode = 0;
    }

    /****************************************/
    /* Modify the intialize flag at the end.*/
    /****************************************/
    if (video->volInitialize[currLayer])
        video->volInitialize[currLayer] = 0;

    return status;
}

#ifndef NO_SLICE_ENCODE
/* ======================================================================== */
/*  Function : PVEncodeFrameSet()                                           */
/*  Date     : 04/18/2000                                                   */
/*  Purpose  : Enter a video frame and perform front-end time check plus ME */
/*  In/out   :                                                              */
/*  Return   : PV_TRUE if successed, PV_FALSE if failed.                    */
/*  Modified :                                                              */
/*                                                                          */
/* ======================================================================== */
OSCL_EXPORT_REF Bool PVEncodeFrameSet(VideoEncControls *encCtrl, VideoEncFrameIO *vid_in, ULong *nextModTime, Int *nLayer)
{
    Bool status = PV_TRUE;
    VideoEncData *video = (VideoEncData *)encCtrl->videoEncoderData;
    VideoEncParams *encParams = video->encParams;
    Vol *currVol;
    PV_STATUS   EncodeVOS_Start(VideoEncControls *encCtrl);
    Int width_16, height_16;
    Int width, height;
    Int encodeVop = 0;
    void  PaddingEdge(Vop *padVop);
    Int currLayer = -1;
    //Int nLayers = encParams->nLayers;

    ULong   modTime = vid_in->timestamp;

#ifdef RANDOM_REFSELCODE   /* add random selection of reference Vop */
    Int random_val[30] = {0, 1, 1, 1, 0, 0, 1, 0, 1, 0, 0, 0, 1, 1, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 0, 1, 0, 1, 0, 0};
    static Int rand_idx = 0;
#endif
    /*******************************************************/
    /* Determine Next Vop to encode, if any, and nLayer    */
    /*******************************************************/

    video->modTime = modTime;

    //i = nLayers-1;

    if (video->volInitialize[0]) /* first vol to code */
    {
        video->nextModTime = video->modTimeRef = ((modTime) - ((modTime) % 1000));
    }


    encodeVop = DetermineCodingLayer(video, nLayer, modTime);

    currLayer = *nLayer;

    /******************************************/
    /* If post-skipping still effective --- return */
    /******************************************/

    if (!encodeVop) /* skip enh layer, no base layer coded --- return */
    {
#ifdef _PRINT_STAT
        printf("No frame coded. Continue to next frame.");
#endif
        *nLayer = -1;

        /* expected next code time, convert back to millisec */
        *nextModTime = video->nextModTime;;
        return status;
    }

    /**************************************************************/
    /* Initialize Vol stream structure with application bitstream */
    /**************************************************************/

    currVol = video->vol[currLayer];
    currVol->stream->bufferSize = 0;
    BitstreamEncReset(currVol->stream);

    /***********************************************************/
    /* Encode VOS and VOL Headers on first call for each layer */
    /***********************************************************/

    if (video->volInitialize[currLayer])
    {
        video->currVop->timeInc = 0;
        video->prevBaseVop->timeInc = 0;
    }

    /***************************************************/
    /* Copy Input Video Frame to Internal Video Buffer */
    /***************************************************/
    /* Determine Width and Height of Vop Layer */

    width = encParams->LayerWidth[currLayer];   /* Get input width */
    height = encParams->LayerHeight[currLayer]; /* Get input height */
    /* Round Up to nearest multiple of 16 : MPEG-4 Standard */

    width_16 = ((width + 15) / 16) * 16;            /* Round up to nearest multiple of 16 */
    height_16 = ((height + 15) / 16) * 16;          /* Round up to nearest multiple of 16 */

    video->input = vid_in;  /* point to the frame input */

    /*//  End ////////////////////////////// */


    /**************************************/
    /* Determine VOP Type                 */
    /* 6/2/2001, separate function      */
    /**************************************/
    DetermineVopType(video, currLayer);

    /****************************/
    /*    Initialize VOP        */
    /****************************/
    video->currVop->volID = currVol->volID;
    video->currVop->width = width_16;
    video->currVop->height = height_16;
    if (video->encParams->H263_Enabled) /*  11/28/05 */
    {
        video->currVop->pitch = width_16;
    }
    else
    {
        video->currVop->pitch = width_16 + 32;
    }
    video->currVop->timeInc = currVol->timeIncrement;
    video->currVop->vopCoded = 1;
    video->currVop->roundingType = 0;
    video->currVop->intraDCVlcThr = encParams->IntraDCVlcThr;

    if (currLayer == 0
#ifdef RANDOM_REFSELCODE   /* add random selection of reference Vop */
            || random_val[rand_idx] || video->volInitialize[currLayer]
#endif
       )
    {
        video->tempForwRefVop = video->forwardRefVop; /* keep initial state */
        if (video->tempForwRefVop != NULL) video->tempRefSelCode = video->tempForwRefVop->refSelectCode;

        video->forwardRefVop = video->prevBaseVop;
        video->forwardRefVop->refSelectCode = 1;
    }
#ifdef RANDOM_REFSELCODE
    else
    {
        video->tempForwRefVop = video->forwardRefVop; /* keep initial state */
        if (video->tempForwRefVop != NULL) video->tempRefSelCode = video->tempForwRefVop->refSelectCode;

        video->forwardRefVop = video->prevEnhanceVop;
        video->forwardRefVop->refSelectCode = 0;
    }
    rand_idx++;
    rand_idx %= 30;
#endif

    video->currVop->refSelectCode = video->forwardRefVop->refSelectCode;
    video->currVop->gobNumber = 0;
    video->currVop->gobFrameID = video->currVop->predictionType;
    video->currVop->temporalRef = ((modTime) * 30 / 1001) % 256;

    video->currVop->temporalInterval = 0;

    if (video->currVop->predictionType == I_VOP)
        video->currVop->quantizer = encParams->InitQuantIvop[currLayer];
    else
        video->currVop->quantizer = encParams->InitQuantPvop[currLayer];

    /****************/
    /* Encode Vop   */
    /****************/
    video->slice_coding = 1;

    /*pv_status =*/
    EncodeVop(video);

#ifdef _PRINT_STAT
    if (video->currVop->predictionType == I_VOP)
        printf(" I-VOP ");
    else
        printf(" P-VOP (ref.%d)", video->forwardRefVop->refSelectCode);
#endif

    /* Set HintTrack Information */
    if (currVol->prevModuloTimeBase)
        video->hintTrackInfo.MTB = 1;
    else
        video->hintTrackInfo.MTB = 0;

    video->hintTrackInfo.LayerID = (UChar)currVol->volID;
    video->hintTrackInfo.CodeType = (UChar)video->currVop->predictionType;
    video->hintTrackInfo.RefSelCode = (UChar)video->currVop->refSelectCode;

    return status;
}
#endif /* NO_SLICE_ENCODE */

#ifndef NO_SLICE_ENCODE
/* ======================================================================== */
/*  Function : PVEncodePacket()                                             */
/*  Date     : 04/18/2002                                                   */
/*  Purpose  : Encode one packet and return bitstream                       */
/*  In/out   :                                                              */
/*  Return   : PV_TRUE if successed, PV_FALSE if failed.                    */
/*  Modified :                                                              */
/*                                                                          */
/* ======================================================================== */
OSCL_EXPORT_REF Bool PVEncodeSlice(VideoEncControls *encCtrl, UChar *bstream, Int *size,
                                   Int *endofFrame, VideoEncFrameIO *vid_out, ULong *nextModTime)
{
    PV_STATUS pv_status;
    VideoEncData *video = (VideoEncData *)encCtrl->videoEncoderData;
    VideoEncParams *encParams = video->encParams;
    Vol *currVol;
    PV_STATUS   EncodeVOS_Start(VideoEncControls *encCtrl);
    Vop *temp;
    void  PaddingEdge(Vop *padVop);
    Int currLayer = video->currLayer;
    Int pre_skip;
    Int pre_size;
    /**************************************************************/
    /* Initialize Vol stream structure with application bitstream */
    /**************************************************************/

    currVol = video->vol[currLayer];
    currVol->stream->bitstreamBuffer = bstream;
    pre_size = currVol->stream->byteCount;
    currVol->stream->bufferSize = pre_size + (*size);

    /***********************************************************/
    /* Encode VOS and VOL Headers on first call for each layer */
    /***********************************************************/

    if (video->volInitialize[currLayer])
    {
        if (!video->encParams->GetVolHeader[currLayer])
            pv_status = EncodeVOS_Start(encCtrl);
    }

    /****************/
    /* Encode Slice */
    /****************/
    pv_status = EncodeSlice(video);

    *endofFrame = 0;

    if (video->mbnum >= currVol->nTotalMB && !video->end_of_buf)
    {
        *endofFrame = 1;

        /************************************/
        /* Update Skip Next Frame           */
        /************************************/
        pre_skip = UpdateSkipNextFrame(video, nextModTime, size, pv_status); /* modified such that no pre-skipped */

        if (pre_skip == -1) /* error */
        {
            *endofFrame = -1;
            /* make sure that pointers are restored to the previous state */
            if (currLayer == 0)
            {
                video->forwardRefVop = video->tempForwRefVop; /* For P-Vop base only */
                video->forwardRefVop->refSelectCode = video->tempRefSelCode;
            }

            return pv_status;
        }

        /* If I-VOP was encoded, reset IntraPeriod */
        if ((currLayer == 0) && (encParams->IntraPeriod > 0) && (video->currVop->predictionType == I_VOP))
            video->nextEncIVop = encParams->IntraPeriod;

        /**********************************************************/
        /* Copy Reconstructed Buffer to Output Video Frame Buffer */
        /**********************************************************/
        vid_out->yChan = video->currVop->yChan;
        vid_out->uChan = video->currVop->uChan;
        vid_out->vChan = video->currVop->vChan;
        if (video->encParams->H263_Enabled)
        {
            vid_out->height = video->currVop->height; /* padded height */
            vid_out->pitch = video->currVop->width; /* padded width */
        }
        else
        {
            vid_out->height = video->currVop->height + 32; /* padded height */
            vid_out->pitch = video->currVop->width + 32; /* padded width */
        }
        //vid_out->timestamp = video->modTime;
        vid_out->timestamp = (ULong)(((video->prevFrameNum[currLayer] * 1000) / encParams->LayerFrameRate[currLayer]) + video->modTimeRef + 0.5);

        /*// End /////////////////////// */

        /****************************************/
        /* Swap Vop Pointers for Base Layer     */
        /****************************************/

        if (currLayer == 0)
        {
            temp = video->prevBaseVop;
            video->prevBaseVop = video->currVop;
            video->prevBaseVop->padded = 0; /* not padded */
            video->currVop = temp;
            video->forwardRefVop = video->prevBaseVop; /* For P-Vop base only */
            video->forwardRefVop->refSelectCode = 1;
        }
        else
        {
            temp = video->prevEnhanceVop;
            video->prevEnhanceVop = video->currVop;
            video->prevEnhanceVop->padded = 0; /* not padded */
            video->currVop = temp;
            video->forwardRefVop = video->prevEnhanceVop;
            video->forwardRefVop->refSelectCode = 0;
        }
    }

    /***********************************/
    /* Update Ouput bstream byte count */
    /***********************************/

    *size = currVol->stream->byteCount - pre_size;

    /****************************************/
    /* Modify the intialize flag at the end.*/
    /****************************************/
    if (video->volInitialize[currLayer])
        video->volInitialize[currLayer] = 0;

    return pv_status;
}
#endif /* NO_SLICE_ENCODE */


/* ======================================================================== */
/*  Function : PVGetH263ProfileLevelID()                                    */
/*  Date     : 02/05/2003                                                   */
/*  Purpose  : Get H.263 Profile ID and level ID for profile 0              */
/*  In/out   : Profile ID=0, levelID is what we want                        */
/*  Return   : PV_TRUE if successed, PV_FALSE if failed.                    */
/*  Modified :                                                              */
/*  Note     : h263Level[8], rBR_bound[8], max_h263_framerate[2]            */
/*             max_h263_width[2], max_h263_height[2] are global             */
/*                                                                          */
/* ======================================================================== */
OSCL_EXPORT_REF Bool PVGetH263ProfileLevelID(VideoEncControls *encCtrl, Int *profileID, Int *levelID)
{
    VideoEncData *encData;
    Int width, height;
    float bitrate_r, framerate;


    /* For this version, we only support H.263 profile 0 */
    *profileID = 0;

    *levelID = 0;
    encData = (VideoEncData *)encCtrl->videoEncoderData;

    if (encData == NULL)
        return PV_FALSE;
    if (encData->encParams == NULL)
        return PV_FALSE;

    if (!encData->encParams->H263_Enabled) return PV_FALSE;


    /* get image width, height, bitrate and framerate */
    width     = encData->encParams->LayerWidth[0];
    height    = encData->encParams->LayerHeight[0];
    bitrate_r = (float)(encData->encParams->LayerBitRate[0]) / (float)64000.0;
    framerate = encData->encParams->LayerFrameRate[0];
    if (!width || !height || !(bitrate_r > 0 && framerate > 0)) return PV_FALSE;

    /* This is the most frequent case : level 10 */
    if (bitrate_r <= rBR_bound[1] && framerate <= max_h263_framerate[0] &&
            (width <= max_h263_width[0] && height <= max_h263_height[0]))
    {
        *levelID = h263Level[1];
        return PV_TRUE;
    }
    else if (bitrate_r > rBR_bound[4] ||
             (width > max_h263_width[1] || height > max_h263_height[1]) ||
             framerate > max_h263_framerate[1])    /* check the highest level 70 */
    {
        *levelID = h263Level[7];
        return PV_TRUE;
    }
    else   /* search level 20, 30, 40 */
    {

        /* pick out level 20 */
        if (bitrate_r <= rBR_bound[2] &&
                ((width <= max_h263_width[0] && height <= max_h263_height[0] && framerate <= max_h263_framerate[1]) ||
                 (width <= max_h263_width[1] && height <= max_h263_height[1] && framerate <= max_h263_framerate[0])))
        {
            *levelID = h263Level[2];
            return PV_TRUE;
        }
        else   /* width, height and framerate are ok, now choose level 30 or 40 */
        {
            *levelID = (bitrate_r <= rBR_bound[3] ? h263Level[3] : h263Level[4]);
            return PV_TRUE;
        }
    }
}

/* ======================================================================== */
/*  Function : PVGetMPEG4ProfileLevelID()                                   */
/*  Date     : 26/06/2008                                                   */
/*  Purpose  : Get MPEG4 Level after initialized                            */
/*  In/out   : profile_level according to interface                         */
/*  Return   : PV_TRUE if successed, PV_FALSE if failed.                    */
/*  Modified :                                                              */
/*                                                                          */
/* ======================================================================== */
OSCL_EXPORT_REF Bool PVGetMPEG4ProfileLevelID(VideoEncControls *encCtrl, Int *profile_level, Int nLayer)
{
    VideoEncData* video;
    Int i;

    video = (VideoEncData *)encCtrl->videoEncoderData;

    if (nLayer == 0)
    {
        for (i = 0; i < 8; i++)
        {
            if (video->encParams->ProfileLevel[0] == profile_level_code[i])
            {
                break;
            }
        }
        *profile_level = i;
    }
    else
    {
        for (i = 0; i < 8; i++)
        {
            if (video->encParams->ProfileLevel[0] == scalable_profile_level_code[i])
            {
                break;
            }
        }
        *profile_level = i + SIMPLE_SCALABLE_PROFILE_LEVEL0;
    }

    return true;
}

#ifndef LIMITED_API
/* ======================================================================== */
/*  Function : PVUpdateEncFrameRate                                         */
/*  Date     : 04/08/2002                                                   */
/*  Purpose  : Update target frame rates of the encoded base and enhance    */
/*             layer(if any) while encoding operation is ongoing            */
/*  In/out   :                                                              */
/*  Return   : PV_TRUE if successed, PV_FALSE if failed.                    */
/*  Modified :                                                              */
/*                                                                          */
/* ======================================================================== */

OSCL_EXPORT_REF Bool PVUpdateEncFrameRate(VideoEncControls *encCtrl, float *frameRate)
{
    VideoEncData    *encData;
    Int i;// nTotalMB, mbPerSec;

    encData = (VideoEncData *)encCtrl->videoEncoderData;

    if (encData == NULL)
        return PV_FALSE;
    if (encData->encParams == NULL)
        return PV_FALSE;

    /* Update the framerates for all the layers */
    for (i = 0; i < encData->encParams->nLayers; i++)
    {

        /* New check: encoding framerate should be consistent with the given profile and level */
        //nTotalMB = (((encData->encParams->LayerWidth[i]+15)/16)*16)*(((encData->encParams->LayerHeight[i]+15)/16)*16)/(16*16);
        //mbPerSec = (Int)(nTotalMB * frameRate[i]);
        //if(mbPerSec > encData->encParams->LayerMaxMbsPerSec[i]) return PV_FALSE;
        if (frameRate[i] > encData->encParams->LayerMaxFrameRate[i]) return PV_FALSE; /* set by users or profile */

        encData->encParams->LayerFrameRate[i] = frameRate[i];
    }

    return RC_UpdateBXRCParams((void*) encData);

}
#endif
#ifndef LIMITED_API
/* ======================================================================== */
/*  Function : PVUpdateBitRate                                              */
/*  Date     : 04/08/2002                                                   */
/*  Purpose  : Update target bit rates of the encoded base and enhance      */
/*             layer(if any) while encoding operation is ongoing            */
/*  In/out   :                                                              */
/*  Return   : PV_TRUE if successed, PV_FALSE if failed.                    */
/*  Modified :                                                              */
/*                                                                          */
/* ======================================================================== */

OSCL_EXPORT_REF Bool PVUpdateBitRate(VideoEncControls *encCtrl, Int *bitRate)
{
    VideoEncData    *encData;
    Int i;

    encData = (VideoEncData *)encCtrl->videoEncoderData;

    if (encData == NULL)
        return PV_FALSE;
    if (encData->encParams == NULL)
        return PV_FALSE;

    /* Update the bitrates for all the layers */
    for (i = 0; i < encData->encParams->nLayers; i++)
    {
        if (bitRate[i] > encData->encParams->LayerMaxBitRate[i]) /* set by users or profile */
        {
            return PV_FALSE;
        }
        encData->encParams->LayerBitRate[i] = bitRate[i];
    }

    return RC_UpdateBXRCParams((void*) encData);

}
#endif
#ifndef LIMITED_API
/* ============================================================================ */
/*  Function : PVUpdateVBVDelay()                                                   */
/*  Date     : 4/23/2004                                                        */
/*  Purpose  : Update VBV buffer size(in delay)                                 */
/*  In/out   :                                                                  */
/*  Return   : PV_TRUE if successed, PV_FALSE if failed.                        */
/*  Modified :                                                                  */
/*                                                                              */
/* ============================================================================ */

Bool PVUpdateVBVDelay(VideoEncControls *encCtrl, float delay)
{

    VideoEncData    *encData;
    Int total_bitrate, max_buffer_size;
    int index;

    encData = (VideoEncData *)encCtrl->videoEncoderData;

    if (encData == NULL)
        return PV_FALSE;
    if (encData->encParams == NULL)
        return PV_FALSE;

    /* Check whether the input delay is valid based on the given profile */
    total_bitrate   = (encData->encParams->nLayers == 1 ? encData->encParams->LayerBitRate[0] :
                       encData->encParams->LayerBitRate[1]);
    index = encData->encParams->profile_table_index;
    max_buffer_size = (encData->encParams->nLayers == 1 ? profile_level_max_VBV_size[index] :
                       scalable_profile_level_max_VBV_size[index]);

    if (total_bitrate*delay > (float)max_buffer_size)
        return PV_FALSE;

    encData->encParams->VBV_delay = delay;
    return PV_TRUE;

}
#endif
#ifndef LIMITED_API
/* ======================================================================== */
/*  Function : PVUpdateIFrameInterval()                                         */
/*  Date     : 04/10/2002                                                   */
/*  Purpose  : updates the INTRA frame refresh interval while encoding      */
/*             is ongoing                                                   */
/*  In/out   :                                                              */
/*  Return   : PV_TRUE if successed, PV_FALSE if failed.                    */
/*  Modified :                                                              */
/*                                                                          */
/* ======================================================================== */

OSCL_EXPORT_REF Bool PVUpdateIFrameInterval(VideoEncControls *encCtrl, Int aIFramePeriod)
{
    VideoEncData    *encData;

    encData = (VideoEncData *)encCtrl->videoEncoderData;

    if (encData == NULL)
        return PV_FALSE;
    if (encData->encParams == NULL)
        return PV_FALSE;

    encData->encParams->IntraPeriod = aIFramePeriod;
    return PV_TRUE;
}
#endif
#ifndef LIMITED_API
/* ======================================================================== */
/*  Function : PVSetNumIntraMBRefresh()                                     */
/*  Date     : 08/05/2003                                                   */
/*  Purpose  :                                                              */
/*  In/out   :                                                              */
/*  Return   : PV_TRUE if successed, PV_FALSE if failed.                    */
/*  Modified :                                                              */
/*                                                                          */
/* ======================================================================== */
OSCL_EXPORT_REF Bool    PVUpdateNumIntraMBRefresh(VideoEncControls *encCtrl, Int numMB)
{
    VideoEncData    *encData;

    encData = (VideoEncData *)encCtrl->videoEncoderData;

    if (encData == NULL)
        return PV_FALSE;

    encData->encParams->Refresh = numMB;

    return PV_TRUE;
}
#endif
#ifndef LIMITED_API
/* ======================================================================== */
/*  Function : PVIFrameRequest()                                            */
/*  Date     : 04/10/2002                                                   */
/*  Purpose  : encodes the next base frame as an I-Vop                      */
/*  In/out   :                                                              */
/*  Return   : PV_TRUE if successed, PV_FALSE if failed.                    */
/*  Modified :                                                              */
/*                                                                          */
/* ======================================================================== */

OSCL_EXPORT_REF Bool PVIFrameRequest(VideoEncControls *encCtrl)
{
    VideoEncData    *encData;

    encData = (VideoEncData *)encCtrl->videoEncoderData;

    if (encData == NULL)
        return PV_FALSE;
    if (encData->encParams == NULL)
        return PV_FALSE;

    encData->nextEncIVop = 1;
    return PV_TRUE;
}
#endif
#ifndef LIMITED_API
/* ======================================================================== */
/*  Function : PVGetEncMemoryUsage()                                        */
/*  Date     : 10/17/2000                                                   */
/*  Purpose  :                                                              */
/*  In/out   :                                                              */
/*  Return   : PV_TRUE if successed, PV_FALSE if failed.                    */
/*  Modified :                                                              */
/*                                                                          */
/* ======================================================================== */

OSCL_EXPORT_REF Int PVGetEncMemoryUsage(VideoEncControls *encCtrl)
{
    VideoEncData    *encData;

    encData = (VideoEncData *)encCtrl->videoEncoderData;

    if (encData == NULL)
        return PV_FALSE;
    if (encData->encParams == NULL)
        return PV_FALSE;
    return encData->encParams->MemoryUsage;
}
#endif

/* ======================================================================== */
/*  Function : PVGetHintTrack()                                             */
/*  Date     : 1/17/2001,                                                   */
/*  Purpose  :                                                              */
/*  In/out   :                                                              */
/*  Return   : PV_TRUE if successed, PV_FALSE if failed.                    */
/*  Modified :                                                              */
/*                                                                          */
/* ======================================================================== */

OSCL_EXPORT_REF Bool PVGetHintTrack(VideoEncControls *encCtrl, MP4HintTrack *info)
{
    VideoEncData    *encData;

    encData = (VideoEncData *)encCtrl->videoEncoderData;

    if (encData == NULL)
        return PV_FALSE;
    if (encData->encParams == NULL)
        return PV_FALSE;
    info->MTB = encData->hintTrackInfo.MTB;
    info->LayerID = encData->hintTrackInfo.LayerID;
    info->CodeType = encData->hintTrackInfo.CodeType;
    info->RefSelCode = encData->hintTrackInfo.RefSelCode;

    return PV_TRUE;
}

/* ======================================================================== */
/*  Function : PVGetMaxVideoFrameSize()                                     */
/*  Date     : 7/17/2001,                                                   */
/*  Purpose  : Function merely returns the maximum buffer size              */
/*  In/out   :                                                              */
/*  Return   : PV_TRUE if successed, PV_FALSE if failed.                    */
/*  Modified :                                                              */
/*                                                                          */
/* ======================================================================== */

OSCL_EXPORT_REF Bool PVGetMaxVideoFrameSize(VideoEncControls *encCtrl, Int *maxVideoFrameSize)
{
    VideoEncData    *encData;

    encData = (VideoEncData *)encCtrl->videoEncoderData;

    if (encData == NULL)
        return PV_FALSE;
    if (encData->encParams == NULL)
        return PV_FALSE;



    *maxVideoFrameSize = encData->encParams->BufferSize[0];

    if (encData->encParams->nLayers == 2)
        if (*maxVideoFrameSize < encData->encParams->BufferSize[1])
            *maxVideoFrameSize = encData->encParams->BufferSize[1];
    *maxVideoFrameSize >>= 3;   /* Convert to Bytes */

    if (*maxVideoFrameSize <= 4000)
        *maxVideoFrameSize = 4000;

    return PV_TRUE;
}
#ifndef LIMITED_API
/* ======================================================================== */
/*  Function : PVGetVBVSize()                                               */
/*  Date     : 4/15/2002                                                    */
/*  Purpose  : Function merely returns the maximum buffer size              */
/*  In/out   :                                                              */
/*  Return   : PV_TRUE if successed, PV_FALSE if failed.                    */
/*  Modified :                                                              */
/*                                                                          */
/* ======================================================================== */

OSCL_EXPORT_REF Bool PVGetVBVSize(VideoEncControls *encCtrl, Int *VBVSize)
{
    VideoEncData    *encData;

    encData = (VideoEncData *)encCtrl->videoEncoderData;

    if (encData == NULL)
        return PV_FALSE;
    if (encData->encParams == NULL)
        return PV_FALSE;

    *VBVSize = encData->encParams->BufferSize[0];
    if (encData->encParams->nLayers == 2)
        *VBVSize += encData->encParams->BufferSize[1];

    return PV_TRUE;

}
#endif
/* ======================================================================== */
/*  Function : EncodeVOS_Start()                                            */
/*  Date     : 08/22/2000                                                   */
/*  Purpose  : Encodes the VOS,VO, and VOL or Short Headers                 */
/*  In/out   :                                                              */
/*  Return   : PV_TRUE if successed, PV_FALSE if failed.                    */
/*  Modified :                                                              */
/*                                                                          */
/* ======================================================================== */
PV_STATUS EncodeVOS_Start(VideoEncControls *encoderControl)
{

    VideoEncData *video = (VideoEncData *)encoderControl->videoEncoderData;
    Vol         *currVol = video->vol[video->currLayer];
    PV_STATUS status = PV_SUCCESS;
    //int profile_level=0x01;
    BitstreamEncVideo *stream = video->bitstream1;
    int i, j;

    /********************************/
    /* Check for short_video_header */
    /********************************/
    if (currVol->shortVideoHeader == 1)
        return status;
    else
    {
        /* Short Video Header or M4V */

        /**************************/
        /* VisualObjectSequence ()*/
        /**************************/
        status = BitstreamPutGT16Bits(stream, 32, SESSION_START_CODE);
        /*  Determine profile_level */
        status = BitstreamPutBits(stream, 8, video->encParams->ProfileLevel[video->currLayer]);

        /******************/
        /* VisualObject() */
        /******************/

        status = BitstreamPutGT16Bits(stream, 32, VISUAL_OBJECT_START_CODE);
        status = BitstreamPut1Bits(stream, 0x00); /* visual object identifier */
        status = BitstreamPutBits(stream, 4, 0x01); /* visual object Type == "video ID" */
        status = BitstreamPut1Bits(stream, 0x00); /* no video signal type */

        /*temp   = */
        BitstreamMpeg4ByteAlignStuffing(stream);


        status = BitstreamPutGT16Bits(stream, 27, VO_START_CODE);/* byte align: should be 2 bits */
        status = BitstreamPutBits(stream, 5, 0x00);/*  Video ID = 0  */



        /**********************/
        /* VideoObjectLayer() */
        /**********************/
        if (currVol->shortVideoHeader == 0)
        { /* M4V  else Short Video Header */
            status = BitstreamPutGT16Bits(stream, VOL_START_CODE_LENGTH, VOL_START_CODE);
            status = BitstreamPutBits(stream, 4, currVol->volID);/*  video_object_layer_id */
            status = BitstreamPut1Bits(stream, 0x00);/*  Random Access = 0  */

            if (video->currLayer == 0)
                status = BitstreamPutBits(stream, 8, 0x01);/* Video Object Type Indication = 1  ... Simple Object Type */
            else
                status = BitstreamPutBits(stream, 8, 0x02);/* Video Object Type Indication = 2  ... Simple Scalable Object Type */

            status = BitstreamPut1Bits(stream, 0x00);/*  is_object_layer_identifer = 0 */


            status = BitstreamPutBits(stream, 4, 0x01); /* aspect_ratio_info = 1 ... 1:1(Square) */
            status = BitstreamPut1Bits(stream, 0x00);/* vol_control_parameters = 0 */
            status = BitstreamPutBits(stream, 2, 0x00);/* video_object_layer_shape = 00 ... rectangular */
            status = BitstreamPut1Bits(stream, 0x01);/* marker bit */
            status = BitstreamPutGT8Bits(stream, 16, currVol->timeIncrementResolution);/* vop_time_increment_resolution */
            status = BitstreamPut1Bits(stream, 0x01);/* marker bit */
            status = BitstreamPut1Bits(stream, currVol->fixedVopRate);/* fixed_vop_rate = 0 */

            /* For Rectangular VO layer shape */
            status = BitstreamPut1Bits(stream, 0x01);/* marker bit */
            status = BitstreamPutGT8Bits(stream, 13, currVol->width);/* video_object_layer_width */
            status = BitstreamPut1Bits(stream, 0x01);/* marker bit */
            status = BitstreamPutGT8Bits(stream, 13, currVol->height);/* video_object_layer_height */
            status = BitstreamPut1Bits(stream, 0x01);/*marker bit */

            status = BitstreamPut1Bits(stream, 0x00);/*interlaced = 0 */
            status = BitstreamPut1Bits(stream, 0x01);/* obmc_disable = 1 */
            status = BitstreamPut1Bits(stream, 0x00);/* sprite_enable = 0 */
            status = BitstreamPut1Bits(stream, 0x00);/* not_8_bit = 0 */
            status = BitstreamPut1Bits(stream, currVol->quantType);/*   quant_type */

            if (currVol->quantType)
            {
                status = BitstreamPut1Bits(stream, currVol->loadIntraQuantMat); /* Intra quant matrix */
                if (currVol->loadIntraQuantMat)
                {
                    for (j = 63; j >= 1; j--)
                        if (currVol->iqmat[*(zigzag_i+j)] != currVol->iqmat[*(zigzag_i+j-1)])
                            break;
                    if ((j == 1) && (currVol->iqmat[*(zigzag_i+j)] == currVol->iqmat[*(zigzag_i+j-1)]))
                        j = 0;
                    for (i = 0; i < j + 1; i++)
                        BitstreamPutBits(stream, 8, currVol->iqmat[*(zigzag_i+i)]);
                    if (j < 63)
                        BitstreamPutBits(stream, 8, 0);
                }
                else
                {
                    for (j = 0; j < 64; j++)
                        currVol->iqmat[j] = mpeg_iqmat_def[j];

                }
                status = BitstreamPut1Bits(stream, currVol->loadNonIntraQuantMat); /* Non-Intra quant matrix */
                if (currVol->loadNonIntraQuantMat)
                {
                    for (j = 63; j >= 1; j--)
                        if (currVol->niqmat[*(zigzag_i+j)] != currVol->niqmat[*(zigzag_i+j-1)])
                            break;
                    if ((j == 1) && (currVol->niqmat[*(zigzag_i+j)] == currVol->niqmat[*(zigzag_i+j-1)]))
                        j = 0;
                    for (i = 0; i < j + 1; i++)
                        BitstreamPutBits(stream, 8, currVol->niqmat[*(zigzag_i+i)]);
                    if (j < 63)
                        BitstreamPutBits(stream, 8, 0);
                }
                else
                {
                    for (j = 0; j < 64; j++)
                        currVol->niqmat[j] = mpeg_nqmat_def[j];
                }
            }

            status = BitstreamPut1Bits(stream, 0x01);   /* complexity_estimation_disable = 1 */
            status = BitstreamPut1Bits(stream, currVol->ResyncMarkerDisable);/* Resync_marker_disable */
            status = BitstreamPut1Bits(stream, currVol->dataPartitioning);/* Data partitioned */

            if (currVol->dataPartitioning)
                status = BitstreamPut1Bits(stream, currVol->useReverseVLC); /* Reversible_vlc */


            if (currVol->scalability) /* Scalability*/
            {

                status = BitstreamPut1Bits(stream, currVol->scalability);/* Scalability = 1 */
                status = BitstreamPut1Bits(stream, currVol->scalType);/* hierarchy _type ... Spatial= 0 and Temporal = 1 */
                status = BitstreamPutBits(stream, 4, currVol->refVolID);/* ref_layer_id  */
                status = BitstreamPut1Bits(stream, currVol->refSampDir);/* ref_layer_sampling_direc*/
                status = BitstreamPutBits(stream, 5, currVol->horSamp_n);/*hor_sampling_factor_n*/
                status = BitstreamPutBits(stream, 5, currVol->horSamp_m);/*hor_sampling_factor_m*/
                status = BitstreamPutBits(stream, 5, currVol->verSamp_n);/*vert_sampling_factor_n*/
                status = BitstreamPutBits(stream, 5, currVol->verSamp_m);/*vert_sampling_factor_m*/
                status = BitstreamPut1Bits(stream, currVol->enhancementType);/* enhancement_type*/
            }
            else /* No Scalability */
                status = BitstreamPut1Bits(stream, currVol->scalability);/* Scalability = 0 */

            /*temp = */
            BitstreamMpeg4ByteAlignStuffing(stream); /* Byte align Headers for VOP */
        }
    }

    return status;
}

/* ======================================================================== */
/*  Function : VOS_End()                                                    */
/*  Date     : 08/22/2000                                                   */
/*  Purpose  : Visual Object Sequence End                                   */
/*  In/out   :                                                              */
/*  Return   : PV_TRUE if successed, PV_FALSE if failed.                    */
/*  Modified :                                                              */
/*                                                                          */
/* ======================================================================== */

PV_STATUS VOS_End(VideoEncControls *encoderControl)
{
    PV_STATUS status = PV_SUCCESS;
    VideoEncData *video = (VideoEncData *)encoderControl->videoEncoderData;
    Vol         *currVol = video->vol[video->currLayer];
    BitstreamEncVideo *stream = currVol->stream;


    status = BitstreamPutBits(stream, SESSION_END_CODE, 32);

    return status;
}

/* ======================================================================== */
/*  Function : DetermineCodingLayer                                         */
/*  Date     : 06/02/2001                                                   */
/*  Purpose  : Find layer to code based on current mod time, assuming that
               it's time to encode enhanced layer.                          */
/*  In/out   :                                                              */
/*  Return   : Number of layer to code.                                     */
/*  Modified :                                                              */
/*                                                                          */
/* ======================================================================== */

Int DetermineCodingLayer(VideoEncData *video, Int *nLayer, ULong modTime)
{
    Vol **vol = video->vol;
    VideoEncParams *encParams = video->encParams;
    Int numLayers = encParams->nLayers;
    UInt modTimeRef = video->modTimeRef;
    float *LayerFrameRate = encParams->LayerFrameRate;
    UInt frameNum[4], frameTick;
    ULong frameModTime, nextFrmModTime;
#ifdef REDUCE_FRAME_VARIANCE    /* To limit how close 2 frames can be */
    float frameInterval;
#endif
    float srcFrameInterval;
    Int frameInc;
    Int i, extra_skip;
    Int encodeVop = 0;

    i = numLayers - 1;

    if (modTime - video->nextModTime > ((ULong)(-1)) >> 1) /* next time wrapped around */
        return 0; /* not time to code it yet */

    video->relLayerCodeTime[i] -= 1000;
    video->nextEncIVop--;  /* number of Vops in highest layer resolution. */
    video->numVopsInGOP++;

    /* from this point frameModTime and nextFrmModTime are internal */

    frameNum[i] = (UInt)((modTime - modTimeRef) * LayerFrameRate[i] + 500) / 1000;
    if (video->volInitialize[i])
    {
        video->prevFrameNum[i] = frameNum[i] - 1;
    }
    else if (frameNum[i] <= video->prevFrameNum[i])
    {
        return 0; /* do not encode this frame */
    }

    /**** this part computes expected next frame *******/
    frameModTime = (ULong)(((frameNum[i] * 1000) / LayerFrameRate[i]) + modTimeRef + 0.5); /* rec. time */
    nextFrmModTime = (ULong)((((frameNum[i] + 1) * 1000) / LayerFrameRate[i]) + modTimeRef + 0.5); /* rec. time */

    srcFrameInterval = 1000 / video->FrameRate;

    video->nextModTime = nextFrmModTime - (ULong)(srcFrameInterval / 2.) - 1; /* between current and next frame */

#ifdef REDUCE_FRAME_VARIANCE    /* To limit how close 2 frames can be */
    frameInterval = 1000 / LayerFrameRate[i]; /* next rec. time */
    delta = (Int)(frameInterval / 4); /* empirical number */
    if (video->nextModTime - modTime  < (ULong)delta) /* need to move nextModTime further. */
    {
        video->nextModTime += ((delta - video->nextModTime + modTime)); /* empirical formula  */
    }
#endif
    /****************************************************/

    /* map frame no.to tick from modTimeRef */
    /*frameTick = (frameNum[i]*vol[i]->timeIncrementResolution) ;
    frameTick = (UInt)((frameTick + (encParams->LayerFrameRate[i]/2))/encParams->LayerFrameRate[i]);*/
    /*  11/16/01, change frameTick to be the closest tick from the actual modTime */
    /*  12/12/02, add (double) to prevent large number wrap-around */
    frameTick = (Int)(((double)(modTime - modTimeRef) * vol[i]->timeIncrementResolution + 500) / 1000);

    /* find timeIncrement to be put in the bitstream */
    /* refTick is second boundary reference. */
    vol[i]->timeIncrement = frameTick - video->refTick[i];


    vol[i]->moduloTimeBase = 0;
    while (vol[i]->timeIncrement >= vol[i]->timeIncrementResolution)
    {
        vol[i]->timeIncrement -= vol[i]->timeIncrementResolution;
        vol[i]->moduloTimeBase++;
        /* do not update refTick and modTimeRef yet, do it after encoding!! */
    }

    if (video->relLayerCodeTime[i] <= 0)    /* no skipping */
    {
        encodeVop = 1;
        video->currLayer = *nLayer = i;
        video->relLayerCodeTime[i] += 1000;

        /* takes care of more dropped frame than expected */
        extra_skip = -1;
        frameInc = (frameNum[i] - video->prevFrameNum[i]);
        extra_skip += frameInc;

        if (extra_skip > 0)
        {   /* update rc->Nr, rc->B, (rc->Rr)*/
            video->nextEncIVop -= extra_skip;
            video->numVopsInGOP += extra_skip;
            if (encParams->RC_Type != CONSTANT_Q)
            {
                RC_UpdateBuffer(video, i, extra_skip);
            }
        }

    }
    /* update frame no. */
    video->prevFrameNum[i] = frameNum[i];

    /* go through all lower layer */
    for (i = (numLayers - 2); i >= 0; i--)
    {

        video->relLayerCodeTime[i] -= 1000;

        /* find timeIncrement to be put in the bitstream */
        vol[i]->timeIncrement = frameTick - video->refTick[i];

        if (video->relLayerCodeTime[i] <= 0) /* time to encode base */
        {
            /* 12/27/00 */
            encodeVop = 1;
            video->currLayer = *nLayer = i;
            video->relLayerCodeTime[i] +=
                (Int)((1000.0 * encParams->LayerFrameRate[numLayers-1]) / encParams->LayerFrameRate[i]);

            vol[i]->moduloTimeBase = 0;
            while (vol[i]->timeIncrement >= vol[i]->timeIncrementResolution)
            {
                vol[i]->timeIncrement -= vol[i]->timeIncrementResolution;
                vol[i]->moduloTimeBase++;
                /* do not update refTick and modTimeRef yet, do it after encoding!! */
            }

            /* takes care of more dropped frame than expected */
            frameNum[i] = (UInt)((frameModTime - modTimeRef) * encParams->LayerFrameRate[i] + 500) / 1000;
            if (video->volInitialize[i])
                video->prevFrameNum[i] = frameNum[i] - 1;

            extra_skip = -1;
            frameInc = (frameNum[i] - video->prevFrameNum[i]);
            extra_skip += frameInc;

            if (extra_skip > 0)
            {   /* update rc->Nr, rc->B, (rc->Rr)*/
                if (encParams->RC_Type != CONSTANT_Q)
                {
                    RC_UpdateBuffer(video, i, extra_skip);
                }
            }
            /* update frame no. */
            video->prevFrameNum[i] = frameNum[i];
        }
    }

#ifdef _PRINT_STAT
    if (encodeVop)
        printf(" TI: %d ", vol[*nLayer]->timeIncrement);
#endif

    return encodeVop;
}

/* ======================================================================== */
/*  Function : DetermineVopType                                             */
/*  Date     : 06/02/2001                                                   */
/*  Purpose  : The name says it all.                                        */
/*  In/out   :                                                              */
/*  Return   : void .                                                       */
/*  Modified :                                                              */
/*                                                                          */
/* ======================================================================== */

void DetermineVopType(VideoEncData *video, Int currLayer)
{
    VideoEncParams *encParams = video->encParams;
//  Vol *currVol = video->vol[currLayer];

    if (encParams->IntraPeriod == 0) /* I-VOPs only */
    {
        if (video->currLayer > 0)
            video->currVop->predictionType = P_VOP;
        else
        {
            video->currVop->predictionType = I_VOP;
            if (video->numVopsInGOP >= 132)
                video->numVopsInGOP = 0;
        }
    }
    else if (encParams->IntraPeriod == -1)  /* IPPPPP... */
    {

        /* maintain frame type if previous frame is pre-skipped, 06/02/2001 */
        if (encParams->RC_Type == CONSTANT_Q || video->rc[currLayer]->skip_next_frame != -1)
            video->currVop->predictionType = P_VOP;

        if (video->currLayer == 0)
        {
            if (/*video->numVopsInGOP>=132 || */video->volInitialize[currLayer])
            {
                video->currVop->predictionType = I_VOP;
                video->numVopsInGOP = 0; /* force INTRA update every 132 base frames*/
                video->nextEncIVop = 1;
            }
            else if (video->nextEncIVop == 0 || video->currVop->predictionType == I_VOP)
            {
                video->numVopsInGOP = 0;
                video->nextEncIVop = 1;
            }
        }
    }
    else   /* IntraPeriod>0 : IPPPPPIPPPPPI... */
    {

        /* maintain frame type if previous frame is pre-skipped, 06/02/2001 */
        if (encParams->RC_Type == CONSTANT_Q || video->rc[currLayer]->skip_next_frame != -1)
            video->currVop->predictionType = P_VOP;

        if (currLayer == 0)
        {
            if (video->nextEncIVop <= 0 || video->currVop->predictionType == I_VOP)
            {
                video->nextEncIVop = encParams->IntraPeriod;
                video->currVop->predictionType = I_VOP;
                video->numVopsInGOP = 0;
            }
        }
    }

    return ;
}

/* ======================================================================== */
/*  Function : UpdateSkipNextFrame                                          */
/*  Date     : 06/02/2001                                                   */
/*  Purpose  : From rate control frame skipping decision, update timing
                related parameters.                                         */
/*  In/out   :                                                              */
/*  Return   : Current coded layer.                                         */
/*  Modified :                                                              */
/*                                                                          */
/* ======================================================================== */

Int UpdateSkipNextFrame(VideoEncData *video, ULong *modTime, Int *size, PV_STATUS status)
{
    Int currLayer = video->currLayer;
    Int nLayer = currLayer;
    VideoEncParams *encParams = video->encParams;
    Int numLayers = encParams->nLayers;
    Vol *currVol = video->vol[currLayer];
    Vol **vol = video->vol;
    Int num_skip, extra_skip;
    Int i;
    UInt newRefTick, deltaModTime;
    UInt temp;

    if (encParams->RC_Type != CONSTANT_Q)
    {
        if (video->volInitialize[0] && currLayer == 0)  /* always encode the first frame */
        {
            RC_ResetSkipNextFrame(video, currLayer);
            //return currLayer;  09/15/05
        }
        else
        {
            if (RC_GetSkipNextFrame(video, currLayer) < 0 || status == PV_END_OF_BUF)   /* Skip Current Frame */
            {

#ifdef _PRINT_STAT
                printf("Skip current frame");
#endif
                currVol->moduloTimeBase = currVol->prevModuloTimeBase;

                /*********************/
                /* prepare to return */
                /*********************/
                *size = 0;  /* Set Bitstream buffer to zero */

                /* Determine nLayer and modTime for next encode */

                *modTime = video->nextModTime;
                nLayer = -1;

                return nLayer; /* return immediately without updating RefTick & modTimeRef */
                /* If I-VOP was attempted, then ensure next base is I-VOP */
                /*if((encParams->IntraPeriod>0) && (video->currVop->predictionType == I_VOP))
                video->nextEncIVop = 0; commented out by 06/05/01 */

            }
            else if ((num_skip = RC_GetSkipNextFrame(video, currLayer)) > 0)
            {

#ifdef _PRINT_STAT
                printf("Skip next %d frames", num_skip);
#endif
                /* to keep the Nr of enh layer the same */
                /* adjust relLayerCodeTime only, do not adjust layerCodeTime[numLayers-1] */
                extra_skip = 0;
                for (i = 0; i < currLayer; i++)
                {
                    if (video->relLayerCodeTime[i] <= 1000)
                    {
                        extra_skip = 1;
                        break;
                    }
                }

                for (i = currLayer; i < numLayers; i++)
                {
                    video->relLayerCodeTime[i] += (num_skip + extra_skip) *
                                                  ((Int)((1000.0 * encParams->LayerFrameRate[numLayers-1]) / encParams->LayerFrameRate[i]));
                }
            }
        }/* first frame */
    }
    /*****  current frame is encoded, now update refTick ******/

    video->refTick[currLayer] += vol[currLayer]->prevModuloTimeBase * vol[currLayer]->timeIncrementResolution;

    /* Reset layerCodeTime every I-VOP to prevent overflow */
    if (currLayer == 0)
    {
        /*  12/12/02, fix for weird targer frame rate of 9.99 fps or 3.33 fps */
        if (((encParams->IntraPeriod != 0) /*&& (video->currVop->predictionType==I_VOP)*/) ||
                ((encParams->IntraPeriod == 0) && (video->numVopsInGOP == 0)))
        {
            newRefTick = video->refTick[0];

            for (i = 1; i < numLayers; i++)
            {
                if (video->refTick[i] < newRefTick)
                    newRefTick = video->refTick[i];
            }

            /* check to make sure that the update is integer multiple of frame number */
            /* how many msec elapsed from last modTimeRef */
            deltaModTime = (newRefTick / vol[0]->timeIncrementResolution) * 1000;

            for (i = numLayers - 1; i >= 0; i--)
            {
                temp = (UInt)(deltaModTime * encParams->LayerFrameRate[i]); /* 12/12/02 */
                if (temp % 1000)
                    newRefTick = 0;

            }
            if (newRefTick > 0)
            {
                video->modTimeRef += deltaModTime;
                for (i = numLayers - 1; i >= 0; i--)
                {
                    video->prevFrameNum[i] -= (UInt)(deltaModTime * encParams->LayerFrameRate[i]) / 1000;
                    video->refTick[i] -= newRefTick;
                }
            }
        }
    }

    *modTime =  video->nextModTime;

    return nLayer;
}


#ifndef ORIGINAL_VERSION

/* ======================================================================== */
/*  Function : SetProfile_BufferSize                                        */
/*  Date     : 04/08/2002                                                   */
/*  Purpose  : Set profile and video buffer size, copied from Jim's code    */
/*             in PVInitVideoEncoder(.), since we have different places     */
/*             to reset profile and video buffer size                       */
/*  In/out   :                                                              */
/*  Return   :                                                              */
/*  Modified :                                                              */
/*                                                                          */
/* ======================================================================== */

Bool SetProfile_BufferSize(VideoEncData *video, float delay, Int bInitialized)
{
    Int i, j, start, end;
//  Int BaseMBsPerSec = 0, EnhMBsPerSec = 0;
    Int nTotalMB = 0;
    Int idx, temp_w, temp_h, max = 0, max_width, max_height;

    Int nLayers = video->encParams->nLayers; /* Number of Layers to be encoded */

    Int total_bitrate = 0, base_bitrate;
    Int total_packet_size = 0, base_packet_size;
    Int total_MBsPerSec = 0, base_MBsPerSec;
    Int total_VBV_size = 0, base_VBV_size, enhance_VBV_size = 0;
    float total_framerate, base_framerate;
    float upper_bound_ratio;
    Int bFound = 0;
    Int k = 0, width16, height16, index;
    Int lowest_level;

#define MIN_BUFF    16000 /* 16k minimum buffer size */
#define BUFF_CONST  2.0    /* 2000ms */
#define UPPER_BOUND_RATIO 8.54 /* upper_bound = 1.4*(1.1+bound/10)*bitrate/framerate */

#define QCIF_WIDTH  176
#define QCIF_HEIGHT 144

    index = video->encParams->profile_table_index;

    /* Calculate "nTotalMB" */
    /* Find the maximum width*height for memory allocation of the VOPs */
    for (idx = 0; idx < nLayers; idx++)
    {
        temp_w = video->encParams->LayerWidth[idx];
        temp_h = video->encParams->LayerHeight[idx];

        if ((temp_w*temp_h) > max)
        {
            max = temp_w * temp_h;
            max_width = temp_w;
            max_height = temp_h;
            nTotalMB = ((max_width + 15) >> 4) * ((max_height + 15) >> 4);
        }
    }
    upper_bound_ratio = (video->encParams->RC_Type == CBR_LOWDELAY ? (float)5.0 : (float)UPPER_BOUND_RATIO);


    /* Get the basic information: bitrate, packet_size, MBs/s and VBV_size */
    base_bitrate        = video->encParams->LayerBitRate[0];
    if (video->encParams->LayerMaxBitRate[0] != 0) /* video->encParams->LayerMaxBitRate[0] == 0 means it has not been set */
    {
        base_bitrate    = PV_MAX(base_bitrate, video->encParams->LayerMaxBitRate[0]);
    }
    else /* if the max is not set, set it to the specified profile/level */
    {
        video->encParams->LayerMaxBitRate[0] = profile_level_max_bitrate[index];
    }

    base_framerate      = video->encParams->LayerFrameRate[0];
    if (video->encParams->LayerMaxFrameRate[0] != 0)
    {
        base_framerate  = PV_MAX(base_framerate, video->encParams->LayerMaxFrameRate[0]);
    }
    else /* if the max is not set, set it to the specified profile/level */
    {
        video->encParams->LayerMaxFrameRate[0] = (float)profile_level_max_mbsPerSec[index] / nTotalMB;
    }

    base_packet_size    = video->encParams->ResyncPacketsize;
    base_MBsPerSec      = (Int)(base_framerate * nTotalMB);
    base_VBV_size       = PV_MAX((Int)(base_bitrate * delay),
                                 (Int)(upper_bound_ratio * base_bitrate / base_framerate));
    base_VBV_size       = PV_MAX(base_VBV_size, MIN_BUFF);

    /* if the buffer is larger than maximum buffer size, we'll clip it */
    if (base_VBV_size > profile_level_max_VBV_size[5])
        base_VBV_size = profile_level_max_VBV_size[5];


    /* Check if the buffer exceeds the maximum buffer size given the maximum profile and level */
    if (nLayers == 1 && base_VBV_size > profile_level_max_VBV_size[index])
        return FALSE;


    if (nLayers == 2)
    {
        total_bitrate       = video->encParams->LayerBitRate[1];
        if (video->encParams->LayerMaxBitRate[1] != 0)
        {
            total_bitrate   = PV_MIN(total_bitrate, video->encParams->LayerMaxBitRate[1]);
        }
        else /* if the max is not set, set it to the specified profile/level */
        {
            video->encParams->LayerMaxBitRate[1] = scalable_profile_level_max_bitrate[index];
        }

        total_framerate     = video->encParams->LayerFrameRate[1];
        if (video->encParams->LayerMaxFrameRate[1] != 0)
        {
            total_framerate     = PV_MIN(total_framerate, video->encParams->LayerMaxFrameRate[1]);
        }
        else /* if the max is not set, set it to the specified profile/level */
        {
            video->encParams->LayerMaxFrameRate[1] = (float)scalable_profile_level_max_mbsPerSec[index] / nTotalMB;
        }

        total_packet_size   = video->encParams->ResyncPacketsize;
        total_MBsPerSec     = (Int)(total_framerate * nTotalMB);

        enhance_VBV_size    = PV_MAX((Int)((total_bitrate - base_bitrate) * delay),
                                     (Int)(upper_bound_ratio * (total_bitrate - base_bitrate) / (total_framerate - base_framerate)));
        enhance_VBV_size    = PV_MAX(enhance_VBV_size, MIN_BUFF);

        total_VBV_size      = base_VBV_size + enhance_VBV_size;

        /* if the buffer is larger than maximum buffer size, we'll clip it */
        if (total_VBV_size > scalable_profile_level_max_VBV_size[6])
        {
            total_VBV_size = scalable_profile_level_max_VBV_size[6];
            enhance_VBV_size = total_VBV_size - base_VBV_size;
        }

        /* Check if the buffer exceeds the maximum buffer size given the maximum profile and level */
        if (total_VBV_size > scalable_profile_level_max_VBV_size[index])
            return FALSE;
    }


    if (!bInitialized) /* Has been initialized --> profile @ level has been figured out! */
    {
        video->encParams->BufferSize[0] = base_VBV_size;
        if (nLayers > 1)
            video->encParams->BufferSize[1] = enhance_VBV_size;

        return PV_TRUE;
    }


    /* Profile @ level determination */
    if (nLayers == 1)
    {
        /* BASE ONLY : Simple Profile(SP) Or Core Profile(CP) */
        if (base_bitrate     > profile_level_max_bitrate[index]     ||
                base_packet_size > profile_level_max_packet_size[index] ||
                base_MBsPerSec   > profile_level_max_mbsPerSec[index]   ||
                base_VBV_size    > profile_level_max_VBV_size[index])

            return PV_FALSE; /* Beyond the bound of Core Profile @ Level2 */

        /* For H263/Short header, determine k*16384 */
        width16  = ((video->encParams->LayerWidth[0] + 15) >> 4) << 4;
        height16 = ((video->encParams->LayerHeight[0] + 15) >> 4) << 4;
        if (video->encParams->H263_Enabled)
        {
            k = 4;
            if (width16  == 2*QCIF_WIDTH && height16 == 2*QCIF_HEIGHT)  /* CIF */
                k = 16;

            else if (width16  == 4*QCIF_WIDTH && height16 == 4*QCIF_HEIGHT)  /* 4CIF */
                k = 32;

            else if (width16  == 8*QCIF_WIDTH && height16 == 8*QCIF_HEIGHT)  /* 16CIF */
                k = 64;

            video->encParams->maxFrameSize  = k * 16384;

            /* Make sure the buffer size is limited to the top profile and level: the Core profile and level 2 */
            if (base_VBV_size > (Int)(k*16384 + 4*(float)profile_level_max_bitrate[5]*1001.0 / 30000.0))
                base_VBV_size = (Int)(k * 16384 + 4 * (float)profile_level_max_bitrate[5] * 1001.0 / 30000.0);

            if (base_VBV_size > (Int)(k*16384 + 4*(float)profile_level_max_bitrate[index]*1001.0 / 30000.0))
                return PV_FALSE;
        }

        /* Search the appropriate profile@level index */
        if (!video->encParams->H263_Enabled &&
                (video->encParams->IntraDCVlcThr != 0 || video->encParams->SearchRange > 16))
        {
            lowest_level = 1; /* cannot allow SPL0 */
        }
        else
        {
            lowest_level = 0; /* SPL0 */
        }

        for (i = lowest_level; i <= index; i++)
        {
            if (i != 4 && /* skip Core Profile@Level1 because the parameters in it are smaller than those in Simple Profile@Level3 */
                    base_bitrate     <= profile_level_max_bitrate[i]     &&
                    base_packet_size <= profile_level_max_packet_size[i] &&
                    base_MBsPerSec   <= profile_level_max_mbsPerSec[i]   &&
                    base_VBV_size    <= (video->encParams->H263_Enabled ? (Int)(k*16384 + 4*(float)profile_level_max_bitrate[i]*1001.0 / 30000.0) :
                                         profile_level_max_VBV_size[i]))
                break;
        }
        if (i > index) return PV_FALSE; /* Nothing found!! */

        /* Found out the actual profile @ level : index "i" */
        if (i == 0)
        {
            /* For Simple Profile @ Level 0, we need to do one more check: image size <= QCIF */
            if (width16 > QCIF_WIDTH || height16 > QCIF_HEIGHT)
                i = 1; /* image size > QCIF, then set SP level1 */
        }

        video->encParams->ProfileLevel[0] = profile_level_code[i];
        video->encParams->BufferSize[0]   = base_VBV_size;

        if (video->encParams->LayerMaxBitRate[0] == 0)
            video->encParams->LayerMaxBitRate[0] = profile_level_max_bitrate[i];

        if (video->encParams->LayerMaxFrameRate[0] == 0)
            video->encParams->LayerMaxFrameRate[0] = PV_MIN(30, (float)profile_level_max_mbsPerSec[i] / nTotalMB);

        /* For H263/Short header, one special constraint for VBV buffer size */
        if (video->encParams->H263_Enabled)
            video->encParams->BufferSize[0] = (Int)(k * 16384 + 4 * (float)profile_level_max_bitrate[i] * 1001.0 / 30000.0);

    }
    else
    {
        /* SCALABALE MODE: Simple Scalable Profile(SSP) Or Core Scalable Profile(CSP) */

        if (total_bitrate       > scalable_profile_level_max_bitrate[index]     ||
                total_packet_size   > scalable_profile_level_max_packet_size[index] ||
                total_MBsPerSec     > scalable_profile_level_max_mbsPerSec[index]   ||
                total_VBV_size      > scalable_profile_level_max_VBV_size[index])

            return PV_FALSE; /* Beyond given profile and level */

        /* One-time check: Simple Scalable Profile or Core Scalable Profile */
        if (total_bitrate       <= scalable_profile_level_max_bitrate[2]        &&
                total_packet_size   <= scalable_profile_level_max_packet_size[2]    &&
                total_MBsPerSec     <= scalable_profile_level_max_mbsPerSec[2]      &&
                total_VBV_size      <= scalable_profile_level_max_VBV_size[2])

        {
            start = 0;
            end = index;
        }

        else
        {
            start = 4;
            end = index;
        }


        /* Search the scalable profile */
        for (i = start; i <= end; i++)
        {
            if (total_bitrate       <= scalable_profile_level_max_bitrate[i]     &&
                    total_packet_size   <= scalable_profile_level_max_packet_size[i] &&
                    total_MBsPerSec     <= scalable_profile_level_max_mbsPerSec[i]   &&
                    total_VBV_size      <= scalable_profile_level_max_VBV_size[i])

                break;
        }
        if (i > end) return PV_FALSE;

        /* Search the base profile */
        if (i == 0)
        {
            j = 0;
            bFound = 1;
        }
        else        bFound = 0;

        for (j = start; !bFound && j <= i; j++)
        {
            if (base_bitrate        <= profile_level_max_bitrate[j]      &&
                    base_packet_size    <= profile_level_max_packet_size[j]  &&
                    base_MBsPerSec      <= profile_level_max_mbsPerSec[j]    &&
                    base_VBV_size       <= profile_level_max_VBV_size[j])

            {
                bFound = 1;
                break;
            }
        }

        if (!bFound) // && start == 4)
            return PV_FALSE; /* mis-match in the profiles between base layer and enhancement layer */

        /* j for base layer, i for enhancement layer */
        video->encParams->ProfileLevel[0] = profile_level_code[j];
        video->encParams->ProfileLevel[1] = scalable_profile_level_code[i];
        video->encParams->BufferSize[0]   = base_VBV_size;
        video->encParams->BufferSize[1]   = enhance_VBV_size;

        if (video->encParams->LayerMaxBitRate[0] == 0)
            video->encParams->LayerMaxBitRate[0] = profile_level_max_bitrate[j];

        if (video->encParams->LayerMaxBitRate[1] == 0)
            video->encParams->LayerMaxBitRate[1] = scalable_profile_level_max_bitrate[i];

        if (video->encParams->LayerMaxFrameRate[0] == 0)
            video->encParams->LayerMaxFrameRate[0] = PV_MIN(30, (float)profile_level_max_mbsPerSec[j] / nTotalMB);

        if (video->encParams->LayerMaxFrameRate[1] == 0)
            video->encParams->LayerMaxFrameRate[1] = PV_MIN(30, (float)scalable_profile_level_max_mbsPerSec[i] / nTotalMB);


    } /* end of: if(nLayers == 1) */


    if (!video->encParams->H263_Enabled && (video->encParams->ProfileLevel[0] == 0x08)) /* SPL0 restriction*/
    {
        /* PV only allow frame-based rate control, no QP change from one MB to another
        if(video->encParams->ACDCPrediction == TRUE && MB-based rate control)
         return PV_FALSE */
    }

    return PV_TRUE;
}

#endif /* #ifndef ORIGINAL_VERSION */



