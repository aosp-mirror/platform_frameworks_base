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
#include "mp4dec_lib.h"
#include "vlc_decode.h"
#include "bitstream.h"

#define OSCL_DISABLE_WARNING_CONDITIONAL_IS_CONSTANT

#ifdef DEC_INTERNAL_MEMORY_OPT
#define QCIF_MBS 99
#define QCIF_BS (4*QCIF_MBS)
#define QCIF_MB_ROWS 11
extern uint8                IMEM_sliceNo[QCIF_MBS];
extern uint8                IMEM_acPredFlag[QCIF_MBS];
extern uint8                IMEM_headerInfo_Mode[QCIF_MBS];
extern uint8                IMEM_headerInfo_CBP[QCIF_MBS];
extern int                  IMEM_headerInfo_QPMB[QCIF_MBS];
extern MacroBlock           IMEM_mblock;
extern MOT                  IMEM_motX[QCIF_BS];
extern MOT                  IMEM_motY[QCIF_BS];
extern BitstreamDecVideo    IMEM_BitstreamDecVideo[4];
extern typeDCStore          IMEM_predDC[QCIF_MBS];
extern typeDCACStore        IMEM_predDCAC_col[QCIF_MB_ROWS+1];

extern VideoDecData         IMEM_VideoDecData[1];
extern Vop                  IMEM_currVop[1];
extern Vop                  IMEM_prevVop[1];
extern PIXEL                IMEM_currVop_yChan[QCIF_MBS*128*3];
extern PIXEL                IMEM_prevVop_yChan[QCIF_MBS*128*3];
extern uint8                IMEM_pstprcTypCur[6*QCIF_MBS];
extern uint8                IMEM_pstprcTypPrv[6*QCIF_MBS];


extern Vop                  IMEM_vopHEADER[2];
extern Vol                  IMEM_VOL[2];
extern Vop                  IMEM_vopHeader[2][1];
extern Vol                  IMEM_vol[2][1];

#endif

/* ======================================================================== */
/*  Function : PVInitVideoDecoder()                                         */
/*  Date     : 04/11/2000, 08/29/2000                                       */
/*  Purpose  : Initialization of the MPEG-4 video decoder library.          */
/*             The return type is Bool instead of PV_STATUS because         */
/*             we don't want to expose PV_STATUS to (outside) programmers   */
/*             that use our decoder library SDK.                            */
/*  In/out   :                                                              */
/*  Return   : PV_TRUE if successed, PV_FALSE if failed.                    */
/*  Modified :                                                              */
/* ======================================================================== */
OSCL_EXPORT_REF Bool PVInitVideoDecoder(VideoDecControls *decCtrl, uint8 *volbuf[],
                                        int32 *volbuf_size, int nLayers, int width, int height, MP4DecodingMode mode)
{
    VideoDecData *video = (VideoDecData *) decCtrl->videoDecoderData;
    Bool status = PV_TRUE;
    int idx;
    BitstreamDecVideo *stream;


    oscl_memset(decCtrl, 0, sizeof(VideoDecControls)); /* fix a size bug.   03/28/2001 */
    decCtrl->nLayers = nLayers;
    for (idx = 0; idx < nLayers; idx++)
    {
        decCtrl->volbuf[idx] = volbuf[idx];
        decCtrl->volbuf_size[idx] = volbuf_size[idx];
    }

    /* memory allocation & initialization */
#ifdef DEC_INTERNAL_MEMORY_OPT
    video = IMEM_VideoDecData;
#else
    video = (VideoDecData *) oscl_malloc(sizeof(VideoDecData));
#endif
    if (video != NULL)
    {
        oscl_memset(video, 0, sizeof(VideoDecData));
        video->memoryUsage = sizeof(VideoDecData);
        video->numberOfLayers = nLayers;
#ifdef DEC_INTERNAL_MEMORY_OPT
        video->vol = (Vol **) IMEM_VOL;
#else
        video->vol = (Vol **) oscl_malloc(nLayers * sizeof(Vol *));
#endif
        if (video->vol == NULL) status = PV_FALSE;
        video->memoryUsage += nLayers * sizeof(Vol *);


        /* we need to setup this pointer for the application to */
        /*    pass it around.                                   */
        decCtrl->videoDecoderData = (void *) video;
        video->videoDecControls = decCtrl;  /* yes. we have a cyclic */
        /* references here :)    */

        /* Allocating Vop space, this has to change when we add */
        /*    spatial scalability to the decoder                */
#ifdef DEC_INTERNAL_MEMORY_OPT
        video->currVop = IMEM_currVop;
        if (video->currVop == NULL) status = PV_FALSE;
        else oscl_memset(video->currVop, 0, sizeof(Vop));
        video->prevVop = IMEM_prevVop;
        if (video->prevVop == NULL) status = PV_FALSE;
        else oscl_memset(video->prevVop, 0, sizeof(Vop));
        video->memoryUsage += (sizeof(Vop) * 2);
        video->vopHeader = (Vop **) IMEM_vopHEADER;
#else

        video->currVop = (Vop *) oscl_malloc(sizeof(Vop));
        if (video->currVop == NULL) status = PV_FALSE;
        else oscl_memset(video->currVop, 0, sizeof(Vop));
        video->prevVop = (Vop *) oscl_malloc(sizeof(Vop));
        if (video->prevVop == NULL) status = PV_FALSE;
        else oscl_memset(video->prevVop, 0, sizeof(Vop));
        video->memoryUsage += (sizeof(Vop) * 2);

        video->vopHeader = (Vop **) oscl_malloc(sizeof(Vop *) * nLayers);
#endif
        if (video->vopHeader == NULL) status = PV_FALSE;
        else oscl_memset(video->vopHeader, 0, sizeof(Vop *)*nLayers);
        video->memoryUsage += (sizeof(Vop *) * nLayers);

        video->initialized = PV_FALSE;
        /* Decode the header to get all information to allocate data */
        if (status == PV_TRUE)
        {
            /* initialize decoded frame counter.   04/24/2001 */
            video->frame_idx = -1;


            for (idx = 0; idx < nLayers; idx++)
            {

#ifdef DEC_INTERNAL_MEMORY_OPT
                video->vopHeader[idx] = IMEM_vopHeader[idx];
#else
                video->vopHeader[idx] = (Vop *) oscl_malloc(sizeof(Vop));
#endif
                if (video->vopHeader[idx] == NULL)
                {
                    status = PV_FALSE;
                    break;
                }
                else
                {
                    oscl_memset(video->vopHeader[idx], 0, sizeof(Vop));
                    video->vopHeader[idx]->timeStamp = 0;
                    video->memoryUsage += (sizeof(Vop));
                }
#ifdef DEC_INTERNAL_MEMORY_OPT
                video->vol[idx] = IMEM_vol[idx];
                video->memoryUsage += sizeof(Vol);
                oscl_memset(video->vol[idx], 0, sizeof(Vol));
                if (video->vol[idx] == NULL) status = PV_FALSE;
                stream = IMEM_BitstreamDecVideo;
#else
                video->vol[idx] = (Vol *) oscl_malloc(sizeof(Vol));
                if (video->vol[idx] == NULL)
                {
                    status = PV_FALSE;
                    break;
                }
                else
                {
                    video->memoryUsage += sizeof(Vol);
                    oscl_memset(video->vol[idx], 0, sizeof(Vol));
                }

                stream = (BitstreamDecVideo *) oscl_malloc(sizeof(BitstreamDecVideo));
#endif
                video->memoryUsage += sizeof(BitstreamDecVideo);
                if (stream == NULL)
                {
                    status = PV_FALSE;
                    break;
                }
                else
                {
                    int32 buffer_size;
                    if ((buffer_size = BitstreamOpen(stream, idx)) < 0)
                    {
                        mp4dec_log("InitVideoDecoder(): Can't allocate bitstream buffer.\n");
                        status = PV_FALSE;
                        break;
                    }
                    video->memoryUsage += buffer_size;
                    video->vol[idx]->bitstream = stream;
                    video->vol[idx]->volID = idx;
                    video->vol[idx]->timeInc_offset = 0;  /*  11/12/01 */
                    video->vlcDecCoeffIntra = &VlcDecTCOEFShortHeader;
                    video->vlcDecCoeffInter = &VlcDecTCOEFShortHeader;
                    if (mode == MPEG4_MODE)
                    {
                        /* Set up VOL header bitstream for frame-based decoding.  08/30/2000 */
                        BitstreamReset(stream, decCtrl->volbuf[idx], decCtrl->volbuf_size[idx]);

                        switch (DecodeVOLHeader(video, idx))
                        {
                            case PV_SUCCESS :
                                if (status == PV_TRUE)
                                    status = PV_TRUE;   /*  we want to make sure that if first layer is bad, second layer is good return PV_FAIL */
                                else
                                    status = PV_FALSE;
                                break;
#ifdef PV_TOLERATE_VOL_ERRORS
                            case PV_BAD_VOLHEADER:
                                status = PV_TRUE;
                                break;
#endif
                            default :
                                status = PV_FALSE;
                                break;
                        }

                    }
                    else
                    {
                        video->shortVideoHeader = PV_TRUE;
                    }

                    if (video->shortVideoHeader == PV_TRUE)
                    {
                        mode = H263_MODE;
                        /* Set max width and height.  In H.263 mode, we use    */
                        /*  volbuf_size[0] to pass in width and volbuf_size[1] */
                        /*  to pass in height.                    04/23/2001 */
                        video->prevVop->temporalRef = 0; /*  11/12/01 */
                        /* Compute some convenience variables:   04/23/2001 */
                        video->vol[idx]->quantType = 0;
                        video->vol[idx]->quantPrecision = 5;
                        video->vol[idx]->errorResDisable = 1;
                        video->vol[idx]->dataPartitioning = 0;
                        video->vol[idx]->useReverseVLC = 0;
                        video->intra_acdcPredDisable = 1;
                        video->vol[idx]->scalability = 0;
                        video->size = (int32)width * height;

                        video->displayWidth = video->width = width;
                        video->displayHeight = video->height = height;
#ifdef PV_ANNEX_IJKT_SUPPORT
                        video->modified_quant = 0;
                        video->advanced_INTRA = 0;
                        video->deblocking = 0;
                        video->slice_structure = 0;
#endif
                    }

                }
            }

        }
        if (status != PV_FALSE)
        {
            status = PVAllocVideoData(decCtrl, width, height, nLayers);
            video->initialized = PV_TRUE;
        }
    }
    else
    {
        status = PV_FALSE;
    }

    if (status == PV_FALSE) PVCleanUpVideoDecoder(decCtrl);

    return status;
}

Bool PVAllocVideoData(VideoDecControls *decCtrl, int width, int height, int nLayers)
{
    VideoDecData *video = (VideoDecData *) decCtrl->videoDecoderData;
    Bool status = PV_TRUE;
    int nTotalMB;
    int nMBPerRow;
    int32 size;

    if (video->shortVideoHeader == PV_TRUE)
    {
        video->displayWidth = video->width = width;
        video->displayHeight = video->height = height;

        video->nMBPerRow =
            video->nMBinGOB  = video->width / MB_SIZE;
        video->nMBPerCol =
            video->nGOBinVop = video->height / MB_SIZE;
        video->nTotalMB =
            video->nMBPerRow * video->nMBPerCol;
    }

    size = (int32)sizeof(PIXEL) * video->width * video->height;
#ifdef PV_MEMORY_POOL
    decCtrl->size = size;
#else
#ifdef DEC_INTERNAL_MEMORY_OPT
    video->currVop->yChan = IMEM_currVop_yChan; /* Allocate memory for all VOP OKA 3/2/1*/
    if (video->currVop->yChan == NULL) status = PV_FALSE;
    video->currVop->uChan = video->currVop->yChan + size;
    video->currVop->vChan = video->currVop->uChan + (size >> 2);

    video->prevVop->yChan = IMEM_prevVop_yChan; /* Allocate memory for all VOP OKA 3/2/1*/
    if (video->prevVop->yChan == NULL) status = PV_FALSE;
    video->prevVop->uChan = video->prevVop->yChan + size;
    video->prevVop->vChan = video->prevVop->uChan + (size >> 2);
#else
    video->currVop->yChan = (PIXEL *) oscl_malloc(size * 3 / 2); /* Allocate memory for all VOP OKA 3/2/1*/
    if (video->currVop->yChan == NULL) status = PV_FALSE;

    video->currVop->uChan = video->currVop->yChan + size;
    video->currVop->vChan = video->currVop->uChan + (size >> 2);
    video->prevVop->yChan = (PIXEL *) oscl_malloc(size * 3 / 2); /* Allocate memory for all VOP OKA 3/2/1*/
    if (video->prevVop->yChan == NULL) status = PV_FALSE;

    video->prevVop->uChan = video->prevVop->yChan + size;
    video->prevVop->vChan = video->prevVop->uChan + (size >> 2);
#endif
    video->memoryUsage += (size * 3);
#endif   // MEMORY_POOL
    /* Note that baseVop, enhcVop is only used to hold enhancement */
    /*    layer header information.                  05/04/2000  */
    if (nLayers > 1)
    {
        video->prevEnhcVop = (Vop *) oscl_malloc(sizeof(Vop));
        video->memoryUsage += (sizeof(Vop));
        if (video->prevEnhcVop == NULL)
        {
            status = PV_FALSE;
        }
        else
        {
            oscl_memset(video->prevEnhcVop, 0, sizeof(Vop));
#ifndef PV_MEMORY_POOL
            video->prevEnhcVop->yChan = (PIXEL *) oscl_malloc(size * 3 / 2); /* Allocate memory for all VOP OKA 3/2/1*/
            if (video->prevEnhcVop->yChan == NULL) status = PV_FALSE;
            video->prevEnhcVop->uChan = video->prevEnhcVop->yChan + size;
            video->prevEnhcVop->vChan = video->prevEnhcVop->uChan + (size >> 2);
            video->memoryUsage += (3 * size / 2);
#endif
        }
    }

    /* Allocating space for slices, AC prediction flag, and */
    /*    AC/DC prediction storage */
    nTotalMB = video->nTotalMB;
    nMBPerRow = video->nMBPerRow;

#ifdef DEC_INTERNAL_MEMORY_OPT
    video->sliceNo = (uint8 *)(IMEM_sliceNo);
    if (video->sliceNo == NULL) status = PV_FALSE;
    video->memoryUsage += nTotalMB;
    video->acPredFlag = (uint8 *)(IMEM_acPredFlag);
    if (video->acPredFlag == NULL) status = PV_FALSE;
    video->memoryUsage += (nTotalMB);
    video->predDC = (typeDCStore *)(IMEM_predDC);
    if (video->predDC == NULL) status = PV_FALSE;
    video->memoryUsage += (nTotalMB * sizeof(typeDCStore));
    video->predDCAC_col = (typeDCACStore *)(IMEM_predDCAC_col);
    if (video->predDCAC_col == NULL) status = PV_FALSE;
    video->memoryUsage += ((nMBPerRow + 1) * sizeof(typeDCACStore));
    video->predDCAC_row = video->predDCAC_col + 1;
    video->headerInfo.Mode = (uint8 *)(IMEM_headerInfo_Mode);
    if (video->headerInfo.Mode == NULL) status = PV_FALSE;
    video->memoryUsage += nTotalMB;
    video->headerInfo.CBP = (uint8 *)(IMEM_headerInfo_CBP);
    if (video->headerInfo.CBP == NULL) status = PV_FALSE;
    video->memoryUsage += nTotalMB;
    video->QPMB = (int *)(IMEM_headerInfo_QPMB);
    if (video->QPMB == NULL) status = PV_FALSE;
    video->memoryUsage += (nTotalMB * sizeof(int));
    video->mblock = &IMEM_mblock;
    if (video->mblock == NULL) status = PV_FALSE;
    oscl_memset(video->mblock->block, 0, sizeof(int16)*6*NCOEFF_BLOCK); //  Aug 23,2005

    video->memoryUsage += sizeof(MacroBlock);
    video->motX = (MOT *)(IMEM_motX);
    if (video->motX == NULL) status = PV_FALSE;
    video->motY = (MOT *)(IMEM_motY);
    if (video->motY == NULL) status = PV_FALSE;
    video->memoryUsage += (sizeof(MOT) * 8 * nTotalMB);
#else
    video->sliceNo = (uint8 *) oscl_malloc(nTotalMB);
    if (video->sliceNo == NULL) status = PV_FALSE;
    video->memoryUsage += nTotalMB;

    video->acPredFlag = (uint8 *) oscl_malloc(nTotalMB * sizeof(uint8));
    if (video->acPredFlag == NULL) status = PV_FALSE;
    video->memoryUsage += (nTotalMB);

    video->predDC = (typeDCStore *) oscl_malloc(nTotalMB * sizeof(typeDCStore));
    if (video->predDC == NULL) status = PV_FALSE;
    video->memoryUsage += (nTotalMB * sizeof(typeDCStore));

    video->predDCAC_col = (typeDCACStore *) oscl_malloc((nMBPerRow + 1) * sizeof(typeDCACStore));
    if (video->predDCAC_col == NULL) status = PV_FALSE;
    video->memoryUsage += ((nMBPerRow + 1) * sizeof(typeDCACStore));

    /* element zero will be used for storing vertical (col) AC coefficients */
    /*  the rest will be used for storing horizontal (row) AC coefficients  */
    video->predDCAC_row = video->predDCAC_col + 1;        /*  ACDC */

    /* Allocating HeaderInfo structure & Quantizer array */
    video->headerInfo.Mode = (uint8 *) oscl_malloc(nTotalMB);
    if (video->headerInfo.Mode == NULL) status = PV_FALSE;
    video->memoryUsage += nTotalMB;
    video->headerInfo.CBP = (uint8 *) oscl_malloc(nTotalMB);
    if (video->headerInfo.CBP == NULL) status = PV_FALSE;
    video->memoryUsage += nTotalMB;
    video->QPMB = (int16 *) oscl_malloc(nTotalMB * sizeof(int16));
    if (video->QPMB == NULL) status = PV_FALSE;
    video->memoryUsage += (nTotalMB * sizeof(int));

    /* Allocating macroblock space */
    video->mblock = (MacroBlock *) oscl_malloc(sizeof(MacroBlock));
    if (video->mblock == NULL)
    {
        status = PV_FALSE;
    }
    else
    {
        oscl_memset(video->mblock->block, 0, sizeof(int16)*6*NCOEFF_BLOCK); //  Aug 23,2005

        video->memoryUsage += sizeof(MacroBlock);
    }
    /* Allocating motion vector space */
    video->motX = (MOT *) oscl_malloc(sizeof(MOT) * 4 * nTotalMB);
    if (video->motX == NULL) status = PV_FALSE;
    video->motY = (MOT *) oscl_malloc(sizeof(MOT) * 4 * nTotalMB);
    if (video->motY == NULL) status = PV_FALSE;
    video->memoryUsage += (sizeof(MOT) * 8 * nTotalMB);
#endif

#ifdef PV_POSTPROC_ON
    /* Allocating space for post-processing Mode */
#ifdef DEC_INTERNAL_MEMORY_OPT
    video->pstprcTypCur = IMEM_pstprcTypCur;
    video->memoryUsage += (nTotalMB * 6);
    if (video->pstprcTypCur == NULL)
    {
        status = PV_FALSE;
    }
    else
    {
        oscl_memset(video->pstprcTypCur, 0, 4*nTotalMB + 2*nTotalMB);
    }

    video->pstprcTypPrv = IMEM_pstprcTypPrv;
    video->memoryUsage += (nTotalMB * 6);
    if (video->pstprcTypPrv == NULL)
    {
        status = PV_FALSE;
    }
    else
    {
        oscl_memset(video->pstprcTypPrv, 0, nTotalMB*6);
    }

#else
    video->pstprcTypCur = (uint8 *) oscl_malloc(nTotalMB * 6);
    video->memoryUsage += (nTotalMB * 6);
    if (video->pstprcTypCur == NULL)
    {
        status = PV_FALSE;
    }
    else
    {
        oscl_memset(video->pstprcTypCur, 0, 4*nTotalMB + 2*nTotalMB);
    }

    video->pstprcTypPrv = (uint8 *) oscl_malloc(nTotalMB * 6);
    video->memoryUsage += (nTotalMB * 6);
    if (video->pstprcTypPrv == NULL)
    {
        status = PV_FALSE;
    }
    else
    {
        oscl_memset(video->pstprcTypPrv, 0, nTotalMB*6);
    }

#endif

#endif

    /* initialize the decoder library */
    video->prevVop->predictionType = I_VOP;
    video->prevVop->timeStamp = 0;
#ifndef PV_MEMORY_POOL
    oscl_memset(video->prevVop->yChan, 16, sizeof(uint8)*size);     /*  10/31/01 */
    oscl_memset(video->prevVop->uChan, 128, sizeof(uint8)*size / 2);

    oscl_memset(video->currVop->yChan, 0, sizeof(uint8)*size*3 / 2);
    if (nLayers > 1)
    {
        oscl_memset(video->prevEnhcVop->yChan, 0, sizeof(uint8)*size*3 / 2);
        video->prevEnhcVop->timeStamp = 0;
    }
    video->concealFrame = video->prevVop->yChan;               /*  07/07/2001 */
    decCtrl->outputFrame = video->prevVop->yChan;              /*  06/19/2002 */
#endif

    /* always start from base layer */
    video->currLayer = 0;
    return status;
}

/* ======================================================================== */
/*  Function : PVResetVideoDecoder()                                        */
/*  Date     : 01/14/2002                                                   */
/*  Purpose  : Reset video timestamps                                       */
/*  In/out   :                                                              */
/*  Return   : PV_TRUE if successed, PV_FALSE if failed.                    */
/*  Modified :                                                              */
/* ======================================================================== */
Bool PVResetVideoDecoder(VideoDecControls *decCtrl)
{
    VideoDecData *video = (VideoDecData *) decCtrl->videoDecoderData;
    int idx;

    for (idx = 0; idx < decCtrl->nLayers; idx++)
    {
        video->vopHeader[idx]->timeStamp = 0;
    }
    video->prevVop->timeStamp = 0;
    if (decCtrl->nLayers > 1)
        video->prevEnhcVop->timeStamp = 0;

    oscl_memset(video->mblock->block, 0, sizeof(int16)*6*NCOEFF_BLOCK); //  Aug 23,2005

    return PV_TRUE;
}


/* ======================================================================== */
/*  Function : PVCleanUpVideoDecoder()                                      */
/*  Date     : 04/11/2000, 08/29/2000                                       */
/*  Purpose  : Cleanup of the MPEG-4 video decoder library.                 */
/*  In/out   :                                                              */
/*  Return   : PV_TRUE if successed, PV_FALSE if failed.                    */
/*  Modified :                                                              */
/* ======================================================================== */
OSCL_EXPORT_REF Bool PVCleanUpVideoDecoder(VideoDecControls *decCtrl)
{
    int idx;
    VideoDecData *video = (VideoDecData *) decCtrl->videoDecoderData;
#ifdef DEC_INTERNAL_MEMORY_OPT
    if (video)
    {
#ifdef PV_POSTPROC_ON
        video->pstprcTypCur = NULL;
        video->pstprcTypPrv = NULL;
#endif

        video->acPredFlag       = NULL;
        video->sliceNo          = NULL;
        video->motX             = NULL;
        video->motY             = NULL;
        video->mblock           = NULL;
        video->QPMB             = NULL;
        video->predDC           = NULL;
        video->predDCAC_row     = NULL;
        video->predDCAC_col     = NULL;
        video->headerInfo.Mode  = NULL;
        video->headerInfo.CBP   = NULL;
        if (video->numberOfLayers > 1)
        {
            if (video->prevEnhcVop)
            {
                video->prevEnhcVop->uChan = NULL;
                video->prevEnhcVop->vChan = NULL;
                if (video->prevEnhcVop->yChan) oscl_free(video->prevEnhcVop->yChan);
                oscl_free(video->prevEnhcVop);
            }
        }
        if (video->currVop)
        {
            video->currVop->uChan = NULL;
            video->currVop->vChan = NULL;
            if (video->currVop->yChan)
                video->currVop->yChan = NULL;
            video->currVop = NULL;
        }
        if (video->prevVop)
        {
            video->prevVop->uChan = NULL;
            video->prevVop->vChan = NULL;
            if (video->prevVop->yChan)
                video->prevVop->yChan = NULL;
            video->prevVop = NULL;
        }

        if (video->vol)
        {
            for (idx = 0; idx < video->numberOfLayers; idx++)
            {
                if (video->vol[idx])
                {
                    BitstreamClose(video->vol[idx]->bitstream);
                    video->vol[idx]->bitstream = NULL;
                    video->vol[idx] = NULL;
                }
                video->vopHeader[idx] = NULL;

            }
            video->vol = NULL;
            video->vopHeader = NULL;
        }

        video = NULL;
        decCtrl->videoDecoderData = NULL;
    }

#else

    if (video)
    {
#ifdef PV_POSTPROC_ON
        if (video->pstprcTypCur) oscl_free(video->pstprcTypCur);
        if (video->pstprcTypPrv) oscl_free(video->pstprcTypPrv);
#endif
        if (video->predDC) oscl_free(video->predDC);
        video->predDCAC_row = NULL;
        if (video->predDCAC_col) oscl_free(video->predDCAC_col);
        if (video->motX) oscl_free(video->motX);
        if (video->motY) oscl_free(video->motY);
        if (video->mblock) oscl_free(video->mblock);
        if (video->QPMB) oscl_free(video->QPMB);
        if (video->headerInfo.Mode) oscl_free(video->headerInfo.Mode);
        if (video->headerInfo.CBP) oscl_free(video->headerInfo.CBP);
        if (video->sliceNo) oscl_free(video->sliceNo);
        if (video->acPredFlag) oscl_free(video->acPredFlag);

        if (video->numberOfLayers > 1)
        {
            if (video->prevEnhcVop)
            {
                video->prevEnhcVop->uChan = NULL;
                video->prevEnhcVop->vChan = NULL;
                if (video->prevEnhcVop->yChan) oscl_free(video->prevEnhcVop->yChan);
                oscl_free(video->prevEnhcVop);
            }
        }
        if (video->currVop)
        {

#ifndef PV_MEMORY_POOL
            video->currVop->uChan = NULL;
            video->currVop->vChan = NULL;
            if (video->currVop->yChan)
                oscl_free(video->currVop->yChan);
#endif
            oscl_free(video->currVop);
        }
        if (video->prevVop)
        {
#ifndef PV_MEMORY_POOL
            video->prevVop->uChan = NULL;
            video->prevVop->vChan = NULL;
            if (video->prevVop->yChan)
                oscl_free(video->prevVop->yChan);
#endif
            oscl_free(video->prevVop);
        }

        if (video->vol)
        {
            for (idx = 0; idx < video->numberOfLayers; idx++)
            {
                if (video->vol[idx])
                {
                    if (video->vol[idx]->bitstream)
                    {
                        BitstreamClose(video->vol[idx]->bitstream);
                        oscl_free(video->vol[idx]->bitstream);
                    }
                    oscl_free(video->vol[idx]);
                }

            }
            oscl_free(video->vol);
        }

        for (idx = 0; idx < video->numberOfLayers; idx++)
        {
            if (video->vopHeader[idx]) oscl_free(video->vopHeader[idx]);
        }

        if (video->vopHeader) oscl_free(video->vopHeader);

        oscl_free(video);
        decCtrl->videoDecoderData = NULL;
    }
#endif
    return PV_TRUE;
}
/* ======================================================================== */
/*  Function : PVGetVideoDimensions()                                       */
/*  Date     : 040505                                                       */
/*  Purpose  :                                                              */
/*  In/out   :                                                              */
/*  Return   : the display_width and display_height of                      */
/*          the frame in the current layer.                                 */
/*  Note     : This is not a macro or inline function because we do         */
/*              not want to expose our internal data structure.             */
/*  Modified :                                                              */
/* ======================================================================== */
OSCL_EXPORT_REF void PVGetVideoDimensions(VideoDecControls *decCtrl, int32 *display_width, int32 *display_height)
{
    VideoDecData *video = (VideoDecData *)decCtrl->videoDecoderData;
    *display_width = video->displayWidth;
    *display_height = video->displayHeight;
}

OSCL_EXPORT_REF void PVGetBufferDimensions(VideoDecControls *decCtrl, int32 *width, int32 *height) {
    VideoDecData *video = (VideoDecData *)decCtrl->videoDecoderData;
    *width = video->width;
    *height = video->height;
}

/* ======================================================================== */
/*  Function : PVGetVideoTimeStamp()                                        */
/*  Date     : 04/27/2000, 08/29/2000                                       */
/*  Purpose  :                                                              */
/*  In/out   :                                                              */
/*  Return   : current time stamp in millisecond.                           */
/*  Note     :                                                              */
/*  Modified :                                                              */
/* ======================================================================== */
uint32 PVGetVideoTimeStamp(VideoDecControls *decCtrl)
{
    VideoDecData *video = (VideoDecData *)decCtrl->videoDecoderData;
    return video->currTimestamp;
}


/* ======================================================================== */
/*  Function : PVSetPostProcType()                                          */
/*  Date     : 07/07/2000                                                   */
/*  Purpose  :                                                              */
/*  In/out   :                                                              */
/*  Return   : Set post-processing filter type.                             */
/*  Note     :                                                              */
/*  Modified : . 08/29/2000 changes the name for consistency.               */
/* ======================================================================== */
OSCL_EXPORT_REF void PVSetPostProcType(VideoDecControls *decCtrl, int mode)
{
    VideoDecData *video = (VideoDecData *)decCtrl->videoDecoderData;
    video->postFilterType = mode;
}


/* ======================================================================== */
/*  Function : PVGetDecBitrate()                                            */
/*  Date     : 08/23/2000                                                   */
/*  Purpose  :                                                              */
/*  In/out   :                                                              */
/*  Return   : This function returns the average bits per second.           */
/*  Note     :                                                              */
/*  Modified :                                                              */
/* ======================================================================== */
int PVGetDecBitrate(VideoDecControls *decCtrl)
{
    VideoDecData *video = (VideoDecData *)decCtrl->videoDecoderData;
    int     idx;
    int32   sum = 0;

    for (idx = 0; idx < BITRATE_AVERAGE_WINDOW; idx++)
    {
        sum += video->nBitsPerVop[idx];
    }
    sum = (sum * video->frameRate) / (10 * BITRATE_AVERAGE_WINDOW);
    return (int) sum;
}


/* ======================================================================== */
/*  Function : PVGetDecFramerate()                                          */
/*  Date     : 08/23/2000                                                   */
/*  Purpose  :                                                              */
/*  In/out   :                                                              */
/*  Return   : This function returns the average frame per 10 second.       */
/*  Note     : The fps can be calculated by PVGetDecFramerate()/10          */
/*  Modified :                                                              */
/* ======================================================================== */
int PVGetDecFramerate(VideoDecControls *decCtrl)
{
    VideoDecData *video = (VideoDecData *)decCtrl->videoDecoderData;

    return video->frameRate;
}

/* ======================================================================== */
/*  Function : PVGetOutputFrame()                                           */
/*  Date     : 05/07/2001                                                   */
/*  Purpose  :                                                              */
/*  In/out   :                                                              */
/*  Return   : This function returns the pointer to the output frame        */
/*  Note     :                                                              */
/*  Modified :                                                              */
/* ======================================================================== */
uint8 *PVGetDecOutputFrame(VideoDecControls *decCtrl)
{
    return decCtrl->outputFrame;
}

/* ======================================================================== */
/*  Function : PVGetLayerID()                                               */
/*  Date     : 07/09/2001                                                   */
/*  Purpose  :                                                              */
/*  In/out   :                                                              */
/*  Return   : This function returns decoded frame layer id (BASE/ENHANCE)  */
/*  Note     :                                                              */
/*  Modified :                                                              */
/* ======================================================================== */
int PVGetLayerID(VideoDecControls *decCtrl)
{
    VideoDecData *video = (VideoDecData *)decCtrl->videoDecoderData;
    return video->currLayer;
}
/* ======================================================================== */
/*  Function : PVGetDecMemoryUsage()                                        */
/*  Date     : 08/23/2000                                                   */
/*  Purpose  :                                                              */
/*  In/out   :                                                              */
/*  Return   : This function returns the amount of memory used.             */
/*  Note     :                                                              */
/*  Modified :                                                              */
/* ======================================================================== */
int32 PVGetDecMemoryUsage(VideoDecControls *decCtrl)
{
    VideoDecData *video = (VideoDecData *)decCtrl->videoDecoderData;
    return video->memoryUsage;
}


/* ======================================================================== */
/*  Function : PVGetDecBitstreamMode()                                      */
/*  Date     : 08/23/2000                                                   */
/*  Purpose  :                                                              */
/*  In/out   :                                                              */
/*  Return   : This function returns the decoding mode of the baselayer     */
/*              bitstream.                                                  */
/*  Note     :                                                              */
/*  Modified :                                                              */
/* ======================================================================== */
OSCL_EXPORT_REF MP4DecodingMode PVGetDecBitstreamMode(VideoDecControls *decCtrl)
{
    VideoDecData *video = (VideoDecData *)decCtrl->videoDecoderData;
    if (video->shortVideoHeader)
    {
        return H263_MODE;
    }
    else
    {
        return MPEG4_MODE;
    }
}


/* ======================================================================== */
/*  Function : PVExtractVolHeader()                                         */
/*  Date     : 08/29/2000                                                   */
/*  Purpose  :                                                              */
/*  In/out   :                                                              */
/*  Return   : Extract vol header of the bitstream from buffer[].           */
/*  Note     :                                                              */
/*  Modified :                                                              */
/* ======================================================================== */
Bool PVExtractVolHeader(uint8 *video_buffer, uint8 *vol_header, int32 *vol_header_size)
{
    int idx = -1;
    uint8 start_code_prefix[] = { 0x00, 0x00, 0x01 };
    uint8 h263_prefix[] = { 0x00, 0x00, 0x80 };

    if (oscl_memcmp(h263_prefix, video_buffer, 3) == 0) /* we have short header stream */
    {
        oscl_memcpy(vol_header, video_buffer, 32);
        *vol_header_size = 32;
        return TRUE;
    }
    else
    {
        if (oscl_memcmp(start_code_prefix, video_buffer, 3) ||
                (video_buffer[3] != 0xb0 && video_buffer[3] >= 0x20)) return FALSE;

        do
        {
            idx++;
            while (oscl_memcmp(start_code_prefix, video_buffer + idx, 3))
            {
                idx++;
                if (idx + 3 >= *vol_header_size) goto quit;
            }
        }
        while (video_buffer[idx+3] != 0xb3 && video_buffer[idx+3] != 0xb6);

        oscl_memcpy(vol_header, video_buffer, idx);
        *vol_header_size = idx;
        return TRUE;
    }

quit:
    oscl_memcpy(vol_header, video_buffer, *vol_header_size);
    return FALSE;
}


/* ======================================================================== */
/*  Function : PVLocateFrameHeader()                                        */
/*  Date     : 04/8/2005                                                    */
/*  Purpose  :                                                              */
/*  In/out   :                                                              */
/*  Return   : Return the offset to the first SC in the buffer              */
/*  Note     :                                                              */
/*  Modified :                                                              */
/* ======================================================================== */
int32 PVLocateFrameHeader(uint8 *ptr, int32 size)
{
    int count = 0;
    int32 i = size;

    if (size < 1)
    {
        return 0;
    }
    while (i--)
    {
        if ((count > 1) && (*ptr == 0x01))
        {
            i += 2;
            break;
        }

        if (*ptr++)
            count = 0;
        else
            count++;
    }
    return (size - (i + 1));
}


/* ======================================================================== */
/*  Function : PVLocateH263FrameHeader()                                    */
/*  Date     : 04/8/2005                                                    */
/*  Purpose  :                                                              */
/*  In/out   :                                                              */
/*  Return   : Return the offset to the first SC in the buffer              */
/*  Note     :                                                              */
/*  Modified :                                                              */
/* ======================================================================== */
int32 PVLocateH263FrameHeader(uint8 *ptr, int32 size)
{
    int count = 0;
    int32 i = size;

    if (size < 1)
    {
        return 0;
    }

    while (i--)
    {
        if ((count > 1) && ((*ptr & 0xFC) == 0x80))
        {
            i += 2;
            break;
        }

        if (*ptr++)
            count = 0;
        else
            count++;
    }
    return (size - (i + 1));
}


/* ======================================================================== */
/*  Function : PVDecodeVideoFrame()                                         */
/*  Date     : 08/29/2000                                                   */
/*  Purpose  : Decode one video frame and return a YUV-12 image.            */
/*  In/out   :                                                              */
/*  Return   :                                                              */
/*  Note     :                                                              */
/*  Modified : 04/17/2001 removed PV_EOS, PV_END_OF_BUFFER              */
/*           : 08/22/2002 break up into 2 functions PVDecodeVopHeader and */
/*                          PVDecodeVopBody                                 */
/* ======================================================================== */
OSCL_EXPORT_REF Bool PVDecodeVideoFrame(VideoDecControls *decCtrl, uint8 *buffer[],
                                        uint32 timestamp[], int32 buffer_size[], uint use_ext_timestamp[], uint8 *currYUV)
{
    PV_STATUS status = PV_FAIL;
    VopHeaderInfo header_info;

    status = (PV_STATUS)PVDecodeVopHeader(decCtrl, buffer, timestamp, buffer_size, &header_info, use_ext_timestamp, currYUV);
    if (status != PV_TRUE)
        return PV_FALSE;

    if (PVDecodeVopBody(decCtrl, buffer_size) != PV_TRUE)
    {
        return PV_FALSE;
    }

    return PV_TRUE;
}

/* ======================================================================== */
/*  Function : PVDecodeVopHeader()                                          */
/*  Date     : 08/22/2002                                                   */
/*  Purpose  : Determine target layer and decode vop header, modified from  */
/*              original PVDecodeVideoFrame.                                */
/*  In/out   :                                                              */
/*  Return   :                                                              */
/*  Note     :                                                              */
/*  Modified :                                                              */
/* ======================================================================== */
Bool PVDecodeVopHeader(VideoDecControls *decCtrl, uint8 *buffer[],
                       uint32 timestamp[], int32 buffer_size[], VopHeaderInfo *header_info, uint use_ext_timestamp [], uint8 *currYUV)
{
    VideoDecData *video = (VideoDecData *) decCtrl->videoDecoderData;
    Vol *currVol;
    Vop *currVop = video->currVop;
    Vop **vopHeader = video->vopHeader;
    BitstreamDecVideo *stream;

    int target_layer;

#ifdef PV_SUPPORT_TEMPORAL_SCALABILITY
    PV_STATUS status = PV_FAIL;
    int idx;
    int32 display_time;

    /* decide which frame to decode next */
    if (decCtrl->nLayers > 1)
    {
        display_time = target_layer = -1;
        for (idx = 0; idx < decCtrl->nLayers; idx++)
        {
            /* do we have data for this layer? */
            if (buffer_size[idx] <= 0)
            {
                timestamp[idx] = -1;
                continue;
            }

            /* did the application provide a timestamp for this vop? */
            if (timestamp[idx] < 0)
            {
                if (vopHeader[idx]->timeStamp < 0)
                {
                    /* decode the timestamp in the bitstream */
                    video->currLayer = idx;
                    stream = video->vol[idx]->bitstream;
                    BitstreamReset(stream, buffer[idx], buffer_size[idx]);

                    while ((status = DecodeVOPHeader(video, vopHeader[idx], FALSE)) != PV_SUCCESS)
                    {
                        /* Try to find a VOP header in the buffer.   08/30/2000. */
                        if (PVSearchNextM4VFrame(stream) != PV_SUCCESS)
                        {
                            /* if we don't have data for enhancement layer, */
                            /*    don't just stop.   09/07/2000.          */
                            buffer_size[idx] = 0;
                            break;
                        }
                    }
                    if (status == PV_SUCCESS)
                    {
                        vopHeader[idx]->timeStamp =
                            timestamp[idx] = CalcVopDisplayTime(video->vol[idx], vopHeader[idx], video->shortVideoHeader);
                        if (idx == 0) vopHeader[idx]->refSelectCode = 1;
                    }
                }
                else
                {
                    /* We've decoded this vop header in the previous run already. */
                    timestamp[idx] = vopHeader[idx]->timeStamp;
                }
            }

            /* Use timestamps to select the next VOP to be decoded */
            if (timestamp[idx] >= 0 && (display_time < 0 || display_time > timestamp[idx]))
            {
                display_time = timestamp[idx];
                target_layer = idx;
            }
            else if (display_time == timestamp[idx])
            {
                /* we have to handle either SNR or spatial scalability here. */
            }
        }
        if (target_layer < 0) return PV_FALSE;

        /* set up for decoding the target layer */
        video->currLayer = target_layer;
        currVol = video->vol[target_layer];
        video->bitstream = stream = currVol->bitstream;

        /* We need to decode the vop header if external timestamp   */
        /*    is provided.    10/04/2000                            */
        if (vopHeader[target_layer]->timeStamp < 0)
        {
            stream = video->vol[target_layer]->bitstream;
            BitstreamReset(stream, buffer[target_layer], buffer_size[target_layer]);

            while (DecodeVOPHeader(video, vopHeader[target_layer], TRUE) != PV_SUCCESS)
            {
                /* Try to find a VOP header in the buffer.   08/30/2000. */
                if (PVSearchNextM4VFrame(stream) != PV_SUCCESS)
                {
                    /* if we don't have data for enhancement layer, */
                    /*    don't just stop.   09/07/2000.          */
                    buffer_size[target_layer] = 0;
                    break;
                }
            }
            video->vol[target_layer]->timeInc_offset = vopHeader[target_layer]->timeInc;
            video->vol[target_layer]->moduloTimeBase = timestamp[target_layer];
            vopHeader[target_layer]->timeStamp = timestamp[target_layer];
            if (target_layer == 0) vopHeader[target_layer]->refSelectCode = 1;
        }
    }
    else /* base layer only decoding */
    {
#endif
        video->currLayer = target_layer = 0;
        currVol = video->vol[0];
        video->bitstream = stream = currVol->bitstream;
        if (buffer_size[0] <= 0) return PV_FALSE;
        BitstreamReset(stream, buffer[0], buffer_size[0]);

        if (video->shortVideoHeader)
        {
            while (DecodeShortHeader(video, vopHeader[0]) != PV_SUCCESS)
            {
                if (PVSearchNextH263Frame(stream) != PV_SUCCESS)
                {
                    /* There is no vop header in the buffer,    */
                    /*   clean bitstream buffer.     2/5/2001   */
                    buffer_size[0] = 0;
                    if (video->initialized == PV_FALSE)
                    {
                        video->displayWidth = video->width = 0;
                        video->displayHeight = video->height = 0;
                    }
                    return PV_FALSE;
                }
            }

            if (use_ext_timestamp[0])
            {
                /* MTB for H263 is absolute TR */
                /* following line is equivalent to  round((timestamp[0]*30)/1001);   11/13/2001 */
                video->vol[0]->moduloTimeBase = 30 * ((timestamp[0] + 17) / 1001) + (30 * ((timestamp[0] + 17) % 1001) / 1001);
                vopHeader[0]->timeStamp = timestamp[0];
            }
            else
                vopHeader[0]->timeStamp = CalcVopDisplayTime(currVol, vopHeader[0], video->shortVideoHeader);
        }
        else
        {
            while (DecodeVOPHeader(video, vopHeader[0], FALSE) != PV_SUCCESS)
            {
                /* Try to find a VOP header in the buffer.   08/30/2000. */
                if (PVSearchNextM4VFrame(stream) != PV_SUCCESS)
                {
                    /* There is no vop header in the buffer,    */
                    /*   clean bitstream buffer.     2/5/2001   */
                    buffer_size[0] = 0;
                    return PV_FALSE;
                }
            }

            if (use_ext_timestamp[0])
            {
                video->vol[0]->timeInc_offset = vopHeader[0]->timeInc;
                video->vol[0]->moduloTimeBase = timestamp[0];  /*  11/12/2001 */
                vopHeader[0]->timeStamp = timestamp[0];
            }
            else
            {
                vopHeader[0]->timeStamp = CalcVopDisplayTime(currVol, vopHeader[0], video->shortVideoHeader);
            }
        }

        /* set up some base-layer only parameters */
        vopHeader[0]->refSelectCode = 1;
#ifdef PV_SUPPORT_TEMPORAL_SCALABILITY
    }
#endif
    timestamp[target_layer] = video->currTimestamp = vopHeader[target_layer]->timeStamp;
#ifdef PV_MEMORY_POOL
    vopHeader[target_layer]->yChan = (PIXEL *)currYUV;
    vopHeader[target_layer]->uChan = (PIXEL *)currYUV + decCtrl->size;
    vopHeader[target_layer]->vChan = (PIXEL *)(vopHeader[target_layer]->uChan) + (decCtrl->size >> 2);
#else
    vopHeader[target_layer]->yChan = currVop->yChan;
    vopHeader[target_layer]->uChan = currVop->uChan;
    vopHeader[target_layer]->vChan = currVop->vChan;
#endif
    oscl_memcpy(currVop, vopHeader[target_layer], sizeof(Vop));

#ifdef PV_SUPPORT_TEMPORAL_SCALABILITY
    vopHeader[target_layer]->timeStamp = -1;
#endif
    /* put header info into the structure */
    header_info->currLayer = target_layer;
    header_info->timestamp = video->currTimestamp;
    header_info->frameType = (MP4FrameType)currVop->predictionType;
    header_info->refSelCode = vopHeader[target_layer]->refSelectCode;
    header_info->quantizer = currVop->quantizer;
    /***************************************/

    return PV_TRUE;
}


/* ======================================================================== */
/*  Function : PVDecodeVopBody()                                            */
/*  Date     : 08/22/2002                                                   */
/*  Purpose  : Decode vop body after the header is decoded, modified from   */
/*              original PVDecodeVideoFrame.                                */
/*  In/out   :                                                              */
/*  Return   :                                                              */
/*  Note     :                                                              */
/*  Modified :                                                              */
/* ======================================================================== */
Bool PVDecodeVopBody(VideoDecControls *decCtrl, int32 buffer_size[])
{
    PV_STATUS status = PV_FAIL;
    VideoDecData *video = (VideoDecData *) decCtrl->videoDecoderData;
    int target_layer = video->currLayer;
    Vol *currVol = video->vol[target_layer];
    Vop *currVop = video->currVop;
    Vop *prevVop = video->prevVop;
    Vop *tempVopPtr;
    int bytes_consumed = 0; /* Record how many bits we used in the buffer.   04/24/2001 */

    int idx;

    if (currVop->vopCoded == 0)                  /*  07/03/2001 */
    {
        PV_BitstreamByteAlign(currVol->bitstream);
        /* We should always clear up bitstream buffer.   10/10/2000 */
        bytes_consumed = (getPointer(currVol->bitstream) + 7) >> 3;

        if (bytes_consumed > currVol->bitstream->data_end_pos)
        {
            bytes_consumed = currVol->bitstream->data_end_pos;
        }

        if (bytes_consumed < buffer_size[target_layer])
        {
            /* If we only consume part of the bits in the buffer, take those */
            /*  out.     04/24/2001 */
            /*          oscl_memcpy(buffer[target_layer], buffer[target_layer]+bytes_consumed,
                            (buffer_size[target_layer]-=bytes_consumed)); */
            buffer_size[target_layer] -= bytes_consumed;
        }
        else
        {
            buffer_size[target_layer] = 0;
        }
#ifdef PV_MEMORY_POOL

        if (target_layer)
        {
            if (video->prevEnhcVop->timeStamp > video->prevVop->timeStamp)
            {
                video->prevVop = video->prevEnhcVop;
            }
        }

        oscl_memcpy(currVop->yChan, video->prevVop->yChan, (decCtrl->size*3) / 2);

        video->prevVop = prevVop;

        video->concealFrame = currVop->yChan;       /*  07/07/2001 */

        video->vop_coding_type = currVop->predictionType; /*  07/09/01 */

        decCtrl->outputFrame = currVop->yChan;

        /* Swap VOP pointers.  No enhc. frame oscl_memcpy() anymore!   04/24/2001 */
        if (target_layer)
        {
            tempVopPtr = video->prevEnhcVop;
            video->prevEnhcVop = video->currVop;
            video->currVop = tempVopPtr;
        }
        else
        {
            tempVopPtr = video->prevVop;
            video->prevVop = video->currVop;
            video->currVop = tempVopPtr;
        }
#else
        if (target_layer)       /* this is necessary to avoid flashback problems   06/21/2002*/
        {
            video->prevEnhcVop->timeStamp = currVop->timeStamp;
        }
        else
        {
            video->prevVop->timeStamp = currVop->timeStamp;
        }
#endif
        video->vop_coding_type = currVop->predictionType; /*  07/09/01 */
        /* the following is necessary to avoid displaying an notCoded I-VOP at the beginning of a session
        or after random positioning  07/03/02*/
        if (currVop->predictionType == I_VOP)
        {
            video->vop_coding_type = P_VOP;
        }


        return PV_TRUE;
    }
    /* ======================================================= */
    /*  Decode vop body (if there is no error in the header!)  */
    /* ======================================================= */

    /* first, we need to select a reference frame */
    if (decCtrl->nLayers > 1)
    {
        if (currVop->predictionType == I_VOP)
        {
            /* do nothing here */
        }
        else if (currVop->predictionType == P_VOP)
        {
            switch (currVop->refSelectCode)
            {
                case 0 : /* most recently decoded enhancement vop */
                    /* Setup video->prevVop before we call PV_DecodeVop().   04/24/2001 */
                    if (video->prevEnhcVop->timeStamp >= video->prevVop->timeStamp)
                        video->prevVop = video->prevEnhcVop;
                    break;

                case 1 : /* most recently displayed base-layer vop */
                    if (target_layer)
                    {
                        if (video->prevEnhcVop->timeStamp > video->prevVop->timeStamp)
                            video->prevVop = video->prevEnhcVop;
                    }
                    break;

                case 2 : /* next base-layer vop in display order */
                    break;

                case 3 : /* temporally coincident base-layer vop (no MV's) */
                    break;
            }
        }
        else /* we have a B-Vop */
        {
            mp4dec_log("DecodeVideoFrame(): B-VOP not supported.\n");
        }
    }

    /* This is for the calculation of the frame rate and bitrate. */
    idx = ++video->frame_idx % BITRATE_AVERAGE_WINDOW;

    /* Calculate bitrate for this layer.   08/23/2000 */
    status = PV_DecodeVop(video);
    video->nBitsPerVop[idx] = getPointer(currVol->bitstream);
    video->prevTimestamp[idx] = currVop->timeStamp;

    /* restore video->prevVop after PV_DecodeVop().   04/24/2001 */
//  if (currVop->refSelectCode == 0) video->prevVop = prevVop;
    video->prevVop = prevVop;

    /* Estimate the frame rate.   08/23/2000 */
    video->duration = video->prevTimestamp[idx];
    video->duration -= video->prevTimestamp[(++idx)%BITRATE_AVERAGE_WINDOW];
    if (video->duration > 0)
    { /* Only update framerate when the timestamp is right */
        video->frameRate = (int)(FRAMERATE_SCALE) / video->duration;
    }

    /* We should always clear up bitstream buffer.   10/10/2000 */
    bytes_consumed = (getPointer(currVol->bitstream) + 7) >> 3; /*  11/4/03 */

    if (bytes_consumed > currVol->bitstream->data_end_pos)
    {
        bytes_consumed = currVol->bitstream->data_end_pos;
    }

    if (bytes_consumed < buffer_size[target_layer])
    {
        /* If we only consume part of the bits in the buffer, take those */
        /*  out.     04/24/2001 */
        /*      oscl_memcpy(buffer[target_layer], buffer[target_layer]+bytes_consumed,
                    (buffer_size[target_layer]-=bytes_consumed)); */
        buffer_size[target_layer] -= bytes_consumed;
    }
    else
    {
        buffer_size[target_layer] = 0;
    }
    switch (status)
    {
        case PV_FAIL :
            return PV_FALSE;        /* this will take care of concealment if we lose whole frame  */

        case PV_END_OF_VOP :
            /* we may want to differenciate PV_END_OF_VOP and PV_SUCCESS */
            /*    in the future.     05/10/2000                      */

        case PV_SUCCESS :
            /* Nohting is wrong :). */


            video->concealFrame = video->currVop->yChan;       /*  07/07/2001 */

            video->vop_coding_type = video->currVop->predictionType; /*  07/09/01 */

            decCtrl->outputFrame = video->currVop->yChan;

            /* Swap VOP pointers.  No enhc. frame oscl_memcpy() anymore!   04/24/2001 */
            if (target_layer)
            {
                tempVopPtr = video->prevEnhcVop;
                video->prevEnhcVop = video->currVop;
                video->currVop = tempVopPtr;
            }
            else
            {
                tempVopPtr = video->prevVop;
                video->prevVop = video->currVop;
                video->currVop = tempVopPtr;
            }
            break;

        default :
            /* This will never happen */
            break;
    }

    return PV_TRUE;
}

#ifdef PV_MEMORY_POOL
OSCL_EXPORT_REF void PVSetReferenceYUV(VideoDecControls *decCtrl, uint8 *YUV)
{
    VideoDecData *video = (VideoDecData *)decCtrl->videoDecoderData;
    video->prevVop->yChan = (PIXEL *)YUV;
    video->prevVop->uChan = (PIXEL *)YUV + video->size;
    video->prevVop->vChan = (PIXEL *)video->prevVop->uChan + (decCtrl->size >> 2);
    oscl_memset(video->prevVop->yChan, 16, sizeof(uint8)*decCtrl->size);     /*  10/31/01 */
    oscl_memset(video->prevVop->uChan, 128, sizeof(uint8)*decCtrl->size / 2);
    video->concealFrame = video->prevVop->yChan;               /*  07/07/2001 */
    decCtrl->outputFrame = video->prevVop->yChan;              /*  06/19/2002 */
}
#endif


/* ======================================================================== */
/*  Function : VideoDecoderErrorDetected()                                  */
/*  Date     : 06/20/2000                                                   */
/*  Purpose  :                                                              */
/*  In/out   :                                                              */
/*  Return   : This function will be called everytime an error int the      */
/*              bitstream is detected.                                      */
/*  Note     :                                                              */
/*  Modified :                                                              */
/* ======================================================================== */
uint VideoDecoderErrorDetected(VideoDecData *)
{
    /* This is only used for trapping bitstream error for debuging */
    return 0;
}

#ifdef ENABLE_LOG
#include <stdio.h>
#include <stdarg.h>
/* ======================================================================== */
/*  Function : m4vdec_dprintf()                                             */
/*  Date     : 08/15/2000                                                   */
/*  Purpose  : This is a function that logs messages in the mpeg4 video     */
/*             decoder.  We can call the standard PacketVideo PVMessage     */
/*             from inside this function if necessary.                      */
/*  In/out   :                                                              */
/*  Return   :                                                              */
/*  Note     : To turn on the logging, LOG_MP4DEC_MESSAGE must be defined   */
/*              when compiling this file (only this file).                  */
/*  Modified :                                                              */
/* ======================================================================== */
void m4vdec_dprintf(char *format, ...)
{
    FILE *log_fp;
    va_list args;
    va_start(args, format);

    /* open the log file */
    log_fp = fopen("\\mp4dec_log.txt", "a+");
    if (log_fp == NULL) return;
    /* output the message */
    vfprintf(log_fp, format, args);
    fclose(log_fp);

    va_end(args);
}
#endif


/* ======================================================================== */
/*  Function : IsIntraFrame()                                               */
/*  Date     : 05/29/2000                                                   */
/*  Purpose  :                                                              */
/*  In/out   :                                                              */
/*  Return   : The most recently decoded frame is an Intra frame.           */
/*  Note     :                                                              */
/*  Modified :                                                              */
/* ======================================================================== */
Bool IsIntraFrame(VideoDecControls *decCtrl)
{
    VideoDecData *video = (VideoDecData *)decCtrl->videoDecoderData;
    return (video->vop_coding_type == I_VOP);
}

/* ======================================================================== */
/*  Function : PVDecPostProcess()                                           */
/*  Date     : 01/09/2002                                                   */
/*  Purpose  : PostProcess one video frame and return a YUV-12 image.       */
/*  In/out   :                                                              */
/*  Return   :                                                              */
/*  Note     :                                                              */
/*  Modified :                                                              */
/* ======================================================================== */
void PVDecPostProcess(VideoDecControls *decCtrl, uint8 *outputYUV)
{
    uint8 *outputBuffer;
#ifdef PV_POSTPROC_ON
    VideoDecData *video = (VideoDecData *) decCtrl->videoDecoderData;
    int32 tmpvar;
    if (outputYUV)
    {
        outputBuffer = outputYUV;
    }
    else
    {
        if (video->postFilterType)
        {
            outputBuffer = video->currVop->yChan;
        }
        else
        {
            outputBuffer = decCtrl->outputFrame;
        }
    }

    if (video->postFilterType)
    {
        /* Post-processing,  */
        PostFilter(video, video->postFilterType, outputBuffer);
    }
    else
    {
        if (outputYUV)
        {
            /* Copy decoded frame to the output buffer. */
            tmpvar = (int32)video->width * video->height;
            oscl_memcpy(outputBuffer, decCtrl->outputFrame, tmpvar*3 / 2);           /*  3/3/01 */
        }
    }
#else
    outputBuffer = decCtrl->outputFrame;
    outputYUV;
#endif
    decCtrl->outputFrame = outputBuffer;
    return;
}


/* ======================================================================== */
/*  Function : PVDecSetReference(VideoDecControls *decCtrl, uint8 *refYUV,  */
/*                              int32 timestamp)                            */
/*  Date     : 07/22/2003                                                   */
/*  Purpose  : Get YUV reference frame from external source.                */
/*  In/out   : YUV 4-2-0 frame containing new reference frame in the same   */
/*   : dimension as original, i.e., doesn't have to be multiple of 16 !!!.  */
/*  Return   :                                                              */
/*  Note     :                                                              */
/*  Modified :                                                              */
/* ======================================================================== */
Bool PVDecSetReference(VideoDecControls *decCtrl, uint8 *refYUV, uint32 timestamp)
{
    VideoDecData *video = (VideoDecData *) decCtrl->videoDecoderData;
    Vop *prevVop = video->prevVop;
    int width = video->width;
    uint8 *dstPtr, *orgPtr, *dstPtr2, *orgPtr2;
    int32 size = (int32)width * video->height;


    /* set new parameters */
    prevVop->timeStamp = timestamp;
    prevVop->predictionType = I_VOP;

    dstPtr = prevVop->yChan;
    orgPtr = refYUV;
    oscl_memcpy(dstPtr, orgPtr, size);
    dstPtr = prevVop->uChan;
    dstPtr2 = prevVop->vChan;
    orgPtr = refYUV + size;
    orgPtr2 = orgPtr + (size >> 2);
    oscl_memcpy(dstPtr, orgPtr, (size >> 2));
    oscl_memcpy(dstPtr2, orgPtr2, (size >> 2));

    video->concealFrame = video->prevVop->yChan;
    video->vop_coding_type = I_VOP;
    decCtrl->outputFrame = video->prevVop->yChan;

    return PV_TRUE;
}

/* ======================================================================== */
/*  Function : PVDecSetEnhReference(VideoDecControls *decCtrl, uint8 *refYUV,   */
/*                              int32 timestamp)                            */
/*  Date     : 07/23/2003                                                   */
/*  Purpose  : Get YUV enhance reference frame from external source.        */
/*  In/out   : YUV 4-2-0 frame containing new reference frame in the same   */
/*   : dimension as original, i.e., doesn't have to be multiple of 16 !!!.  */
/*  Return   :                                                              */
/*  Note     :                                                              */
/*  Modified :                                                              */
/* ======================================================================== */
Bool PVDecSetEnhReference(VideoDecControls *decCtrl, uint8 *refYUV, uint32 timestamp)
{
    VideoDecData *video = (VideoDecData *) decCtrl->videoDecoderData;
    Vop *prevEnhcVop = video->prevEnhcVop;
    uint8 *dstPtr, *orgPtr, *dstPtr2, *orgPtr2;
    int32 size = (int32) video->width * video->height;

    if (video->numberOfLayers <= 1)
        return PV_FALSE;


    /* set new parameters */
    prevEnhcVop->timeStamp = timestamp;
    prevEnhcVop->predictionType = I_VOP;

    dstPtr = prevEnhcVop->yChan;
    orgPtr = refYUV;
    oscl_memcpy(dstPtr, orgPtr, size);
    dstPtr = prevEnhcVop->uChan;
    dstPtr2 = prevEnhcVop->vChan;
    orgPtr = refYUV + size;
    orgPtr2 = orgPtr + (size >> 2);
    oscl_memcpy(dstPtr, orgPtr, (size >> 2));
    oscl_memcpy(dstPtr2, orgPtr2, (size >> 2));
    video->concealFrame = video->prevEnhcVop->yChan;
    video->vop_coding_type = I_VOP;
    decCtrl->outputFrame = video->prevEnhcVop->yChan;

    return PV_TRUE;
}


/* ======================================================================== */
/*  Function : PVGetVolInfo()                                               */
/*  Date     : 08/06/2003                                                   */
/*  Purpose  : Get the vol info(only base-layer).                           */
/*  In/out   :                                                              */
/*  Return   :                                                              */
/*  Note     :                                                              */
/*  Modified : 06/24/2004                                                   */
/* ======================================================================== */
Bool PVGetVolInfo(VideoDecControls *decCtrl, VolInfo *pVolInfo)
{
    Vol *currVol;

    if (pVolInfo == NULL || decCtrl == NULL || decCtrl->videoDecoderData == NULL ||
            ((VideoDecData *)decCtrl->videoDecoderData)->vol[0] == NULL) return PV_FALSE;

    currVol = ((VideoDecData *)(decCtrl->videoDecoderData))->vol[0];

    // get the VOL info
    pVolInfo->shortVideoHeader = (int32)((VideoDecData *)(decCtrl->videoDecoderData))->shortVideoHeader;
    pVolInfo->dataPartitioning = (int32)currVol->dataPartitioning;
    pVolInfo->errorResDisable  = (int32)currVol->errorResDisable;
    pVolInfo->useReverseVLC    = (int32)currVol->useReverseVLC;
    pVolInfo->scalability      = (int32)currVol->scalability;
    pVolInfo->nbitsTimeIncRes  = (int32)currVol->nbitsTimeIncRes;
    pVolInfo->profile_level_id = (int32)currVol->profile_level_id;

    return PV_TRUE;
}



