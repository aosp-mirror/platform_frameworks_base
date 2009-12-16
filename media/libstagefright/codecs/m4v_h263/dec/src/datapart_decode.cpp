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
#include "scaling.h"
#include "mbtype_mode.h"
#include "idct.h"

#define OSCL_DISABLE_WARNING_CONDITIONAL_IS_CONSTANT
/* ======================================================================== */
/*  Function : DecodeFrameDataPartMode()                                    */
/*  Purpose  : Decode a frame of MPEG4 bitstream in datapartitioning mode.  */
/*  In/out   :                                                              */
/*  Return   :                                                              */
/*  Modified :                                                              */
/*                                                                          */
/*      04/25/2000 : Rewrite the data partitioning path completely  */
/*                           according to the pseudo codes in MPEG-4        */
/*                           standard.                                      */
/*  Modified : 09/18/2000 add fast VlcDecode+Dequant                    */
/*             04/17/2001 cleanup                                       */
/* ======================================================================== */
PV_STATUS DecodeFrameDataPartMode(VideoDecData *video)
{
    PV_STATUS status;
    Vop *currVop = video->currVop;
    BitstreamDecVideo *stream = video->bitstream;

    int nMBPerRow = video->nMBPerRow;

    int vopType = currVop->predictionType;
    int mbnum;
    int nTotalMB = video->nTotalMB;
    int slice_counter;
    int resync_marker_length;

    /* copy and pad to prev_Vop for INTER coding */
    switch (vopType)
    {
        case I_VOP :
//      oscl_memset(Mode, MODE_INTRA, sizeof(uint8)*nTotalMB);
            resync_marker_length = 17;
            break;
        case P_VOP :
            oscl_memset(video->motX, 0, sizeof(MOT)*4*nTotalMB);
            oscl_memset(video->motY, 0, sizeof(MOT)*4*nTotalMB);
//      oscl_memset(Mode, MODE_INTER, sizeof(uint8)*nTotalMB);
            resync_marker_length = 16 + currVop->fcodeForward;
            break;
        default :
            mp4dec_log("DecodeFrameDataPartMode(): Vop type not supported.\n");
            return PV_FAIL;
    }

    /** Initialize sliceNo ***/
    mbnum = slice_counter = 0;
//  oscl_memset(video->sliceNo, 0, sizeof(uint8)*nTotalMB);

    do
    {
        /* This section is equivalent to motion_shape_texture() */
        /* in the MPEG-4 standard.            04/13/2000      */
        video->mbnum = mbnum;
        video->mbnum_row = PV_GET_ROW(mbnum, nMBPerRow);   /*  This is needed if nbnum is read from the packet header */
        video->mbnum_col = mbnum - video->mbnum_row * nMBPerRow;

        switch (vopType)
        {
            case I_VOP :
                status = DecodeDataPart_I_VideoPacket(video, slice_counter);
                break;

            case P_VOP :
                status = DecodeDataPart_P_VideoPacket(video, slice_counter);
                break;

            default :
                mp4dec_log("DecodeFrameDataPartMode(): Vop type not supported.\n");
                return PV_FAIL;
        }

        while ((status = PV_ReadVideoPacketHeader(video, &mbnum)) == PV_FAIL)
        {
            if ((status = quickSearchVideoPacketHeader(stream, resync_marker_length)) != PV_SUCCESS)
            {
                break;
            }
        }

        if (status == PV_END_OF_VOP)
        {
            mbnum = nTotalMB;
        }

        if (mbnum > video->mbnum + 1)
        {
            ConcealPacket(video, video->mbnum, mbnum, slice_counter);
        }
        slice_counter++;
        if (mbnum >= nTotalMB)
        {
            break;
        }


    }
    while (TRUE);

    return PV_SUCCESS;
}


/* ======================================================================== */
/*  Function : DecodeDataPart_I_VideoPacket()                               */
/*  Date     : 04/25/2000                                                   */
/*  Purpose  : Decode Data Partitioned Mode Video Packet in I-VOP           */
/*  In/out   :                                                              */
/*  Return   : PV_SUCCESS if successed, PV_FAIL if failed.                  */
/*  Modified : 09/18/2000 add fast VlcDecode+Dequant                    */
/*             04/01/2001 fixed MB_stuffing, removed unnecessary code   */
/* ======================================================================== */
PV_STATUS DecodeDataPart_I_VideoPacket(VideoDecData *video, int slice_counter)
{
    PV_STATUS status;
    uint8 *Mode = video->headerInfo.Mode;
    BitstreamDecVideo *stream = video->bitstream;
    int  nTotalMB = video->nTotalMB;
    int  mbnum, mb_start, mb_end;
    int16 QP, *QPMB = video->QPMB;
    int  MBtype, MCBPC, CBPY;
    uint32 tmpvar;
    uint code;
    int nMBPerRow = video->nMBPerRow;
    Bool valid_stuffing;
    int32 startSecondPart, startFirstPart = getPointer(stream);

    /* decode the first partition */
    QP = video->currVop->quantizer;
    mb_start = mbnum = video->mbnum;
    video->usePrevQP = 0;         /*  04/27/01 */


    BitstreamShowBits16(stream, 9, &code);
    while (code == 1)
    {
        PV_BitstreamFlushBits(stream, 9);
        BitstreamShowBits16(stream, 9, &code);
    }

    do
    {
        /* decode COD, MCBPC, ACpred_flag, CPBY and DQUANT */
        MCBPC = PV_VlcDecMCBPC_com_intra(stream);

        if (!VLC_ERROR_DETECTED(MCBPC))
        {
            Mode[mbnum] = (uint8)(MBtype = MBtype_mode[MCBPC & 7]);
            video->headerInfo.CBP[mbnum] = (uint8)((MCBPC >> 4) & 3);
            status = GetMBheaderDataPart_DQUANT_DC(video, &QP);
            video->usePrevQP = 1;        /* set it after the first coded MB      04/27/01 */
        }
        else
        {
            /* Report the error to the application.   06/20/2000 */
            VideoDecoderErrorDetected(video);
            video->mbnum = mb_start;
            movePointerTo(stream, startFirstPart);
            return PV_FAIL;
        }

        video->sliceNo[mbnum] = (uint8) slice_counter;
        QPMB[mbnum] = QP;
        video->mbnum = ++mbnum;

        BitstreamShowBits16(stream, 9, &code);
        while (code == 1)
        {
            PV_BitstreamFlushBits(stream, 9);
            BitstreamShowBits16(stream, 9, &code);
        }
        /* have we reached the end of the video packet or vop? */
        status = BitstreamShowBits32(stream, DC_MARKER_LENGTH, &tmpvar);

    }
    while (tmpvar != DC_MARKER && video->mbnum < nTotalMB);

    if (tmpvar == DC_MARKER)
    {
        PV_BitstreamFlushBits(stream, DC_MARKER_LENGTH);
    }
    else
    {
        status = quickSearchDCM(stream);
        if (status == PV_SUCCESS)
        {
            /* only way you can end up being here is in the last packet,and there is stuffing at
            the end of the first partition */
            PV_BitstreamFlushBits(stream, DC_MARKER_LENGTH);
        }
        else
        {
            /* Report the error to the application.   06/20/2000 */
            VideoDecoderErrorDetected(video);
            movePointerTo(stream, startFirstPart);
            video->mbnum = mb_start;
            /* concealment will be taken care of in the upper layer */
            return PV_FAIL;
        }
    }

    /* decode the second partition */
    startSecondPart = getPointer(stream);

    mb_end = video->mbnum;

    for (mbnum = mb_start; mbnum < mb_end; mbnum++)
    {
        MBtype = Mode[mbnum];
        /* No skipped mode in I-packets  3/1/2001    */
        video->mbnum = mbnum;

        video->mbnum_row = PV_GET_ROW(mbnum, nMBPerRow);   /*  This is needed if nbnum is read from the packet header */
        video->mbnum_col = mbnum - video->mbnum_row * nMBPerRow;
        /* there is always acdcpred in DataPart mode  04/10/01 */
        video->acPredFlag[mbnum] = (uint8) BitstreamRead1Bits(stream);

        CBPY = PV_VlcDecCBPY(stream, MBtype & INTRA_MASK); /* MODE_INTRA || MODE_INTRA_Q */
        if (CBPY < 0)
        {
            /* Report the error to the application.   06/20/2000 */
            VideoDecoderErrorDetected(video);
            movePointerTo(stream, startSecondPart); /*  */
            /* Conceal packet,  05/15/2000 */
            ConcealTexture_I(video, startFirstPart, mb_start, mb_end, slice_counter);
            return PV_FAIL;
        }

        video->headerInfo.CBP[mbnum] |= (uint8)(CBPY << 2);
    }

    video->usePrevQP = 0;

    for (mbnum = mb_start; mbnum < mb_end; mbnum++)
    {
        video->mbnum = mbnum;

        video->mbnum_row = PV_GET_ROW(mbnum , nMBPerRow);  /*  This is needed if nbnum is read from the packet header */
        video->mbnum_col = mbnum - video->mbnum_row * nMBPerRow;
        /* No skipped mode in I-packets  3/1/2001    */
        /* decode the DCT coeficients for the MB */
        status = GetMBData_DataPart(video);
        if (status != PV_SUCCESS)
        {
            /* Report the error to the application.   06/20/2000 */
            VideoDecoderErrorDetected(video);
            movePointerTo(stream, startSecondPart); /*  */
            /* Conceal packet,  05/15/2000 */
            ConcealTexture_I(video, startFirstPart, mb_start, mb_end, slice_counter);
            return status;
        }
        video->usePrevQP = 1;           /*  04/27/01 should be set after decoding first MB */
    }

    valid_stuffing = validStuffing(stream);
    if (!valid_stuffing)
    {
        VideoDecoderErrorDetected(video);
        movePointerTo(stream, startSecondPart);
        ConcealTexture_I(video, startFirstPart, mb_start, mb_end, slice_counter);
        return PV_FAIL;
    }
    return PV_SUCCESS;
}


/* ======================================================================== */
/*  Function : DecodeDataPart_P_VideoPacket()                               */
/*  Date     : 04/25/2000                                                   */
/*  Purpose  : Decode Data Partitioned Mode Video Packet in P-VOP           */
/*  In/out   :                                                              */
/*  Return   : PV_SUCCESS if successed, PV_FAIL if failed.                  */
/*  Modified :   09/18/2000,  fast VlcDecode+Dequant                        */
/*              04/13/2001,  fixed MB_stuffing, new ACDC pred structure,  */
/*                              cleanup                                     */
/*              08/07/2001,  remove MBzero                              */
/* ======================================================================== */
PV_STATUS DecodeDataPart_P_VideoPacket(VideoDecData *video, int slice_counter)
{
    PV_STATUS status;
    uint8 *Mode = video->headerInfo.Mode;
    BitstreamDecVideo *stream = video->bitstream;
    int nTotalMB = video->nTotalMB;
    int mbnum, mb_start, mb_end;
    int16 QP, *QPMB = video->QPMB;
    int MBtype, CBPY;
    Bool valid_stuffing;
    int intra_MB;
    uint32 tmpvar;
    uint code;
    int32  startFirstPart, startSecondPart;
    int nMBPerRow = video->nMBPerRow;
    uint8 *pbyte;
    /* decode the first partition */
    startFirstPart = getPointer(stream);
    mb_start = video->mbnum;
    video->usePrevQP = 0;            /*  04/27/01 */

    BitstreamShowBits16(stream, 10, &code);
    while (code == 1)
    {
        PV_BitstreamFlushBits(stream, 10);
        BitstreamShowBits16(stream, 10, &code);
    }

    do
    {
        /* decode COD, MCBPC, ACpred_flag, CPBY and DQUANT */
        /* We have to discard stuffed MB header */

        status = GetMBheaderDataPart_P(video);

        if (status != PV_SUCCESS)
        {
            /* Report the error to the application.   06/20/2000 */
            VideoDecoderErrorDetected(video);
            movePointerTo(stream, startFirstPart);
            video->mbnum = mb_start;
            return PV_FAIL;
        }

        /* we must update slice_counter before motion vector decoding.   */
        video->sliceNo[video->mbnum] = (uint8) slice_counter;

        if (Mode[video->mbnum] & INTER_MASK) /* INTER || INTER_Q || INTER_4V */
        {
            /* decode the motion vector (if there are any) */
            status = PV_GetMBvectors(video, Mode[video->mbnum]);
            if (status != PV_SUCCESS)
            {
                /* Report the error to the application.   06/20/2000 */
                VideoDecoderErrorDetected(video);
                movePointerTo(stream, startFirstPart);
                video->mbnum = mb_start;
                return PV_FAIL;
            }
        }
        video->mbnum++;

        video->mbnum_row = PV_GET_ROW(video->mbnum, nMBPerRow);   /*  This is needed if mbnum is read from the packet header */
        video->mbnum_col = video->mbnum - video->mbnum_row * nMBPerRow;

        BitstreamShowBits16(stream, 10, &code);
        while (code == 1)
        {
            PV_BitstreamFlushBits(stream, 10);
            BitstreamShowBits16(stream, 10, &code);
        }
        /* have we reached the end of the video packet or vop? */
        status = BitstreamShowBits32(stream, MOTION_MARKER_COMB_LENGTH, &tmpvar);
        /*      if (status != PV_SUCCESS && status != PV_END_OF_BUFFER) return status;  */
    }
    while (tmpvar != MOTION_MARKER_COMB && video->mbnum < nTotalMB);

    if (tmpvar == MOTION_MARKER_COMB)
    {
        PV_BitstreamFlushBits(stream, MOTION_MARKER_COMB_LENGTH);
    }
    else
    {
        status = quickSearchMotionMarker(stream);
        if (status == PV_SUCCESS)
        {
            /* only way you can end up being here is in the last packet,and there is stuffing at
            the end of the first partition */
            PV_BitstreamFlushBits(stream, MOTION_MARKER_COMB_LENGTH);
        }
        else
        {
            /* Report the error to the application.   06/20/2000 */
            VideoDecoderErrorDetected(video);
            movePointerTo(stream, startFirstPart);
            video->mbnum = mb_start;
            /* concealment will be taken care of in the upper layer  */
            return PV_FAIL;
        }
    }

    /* decode the second partition */
    startSecondPart = getPointer(stream);
    QP = video->currVop->quantizer;

    mb_end = video->mbnum;

    for (mbnum = mb_start; mbnum < mb_end; mbnum++)
    {
        MBtype = Mode[mbnum];

        if (MBtype == MODE_SKIPPED)
        {
            QPMB[mbnum] = QP; /*  03/01/01 */
            continue;
        }
        intra_MB = (MBtype & INTRA_MASK); /* (MBtype == MODE_INTRA || MBtype == MODE_INTRA_Q) */
        video->mbnum = mbnum;
        video->mbnum_row = PV_GET_ROW(mbnum, nMBPerRow);   /*  This is needed if nbnum is read from the packet header */
        video->mbnum_col = mbnum - video->mbnum_row * nMBPerRow;

        /* there is always acdcprediction in DataPart mode    04/10/01 */
        if (intra_MB)
        {
            video->acPredFlag[mbnum] = (uint8) BitstreamRead1Bits_INLINE(stream);
        }

        CBPY = PV_VlcDecCBPY(stream, intra_MB);
        if (CBPY < 0)
        {
            /* Report the error to the application.   06/20/2000 */
            VideoDecoderErrorDetected(video);
            /* Conceal second partition,  5/15/2000 */
            movePointerTo(stream, startSecondPart);
            ConcealTexture_P(video, mb_start, mb_end, slice_counter);
            return PV_FAIL;
        }

        video->headerInfo.CBP[mbnum] |= (uint8)(CBPY << 2);
        if (intra_MB || MBtype == MODE_INTER_Q)                     /*  04/26/01 */
        {
            status = GetMBheaderDataPart_DQUANT_DC(video, &QP);
            if (status != PV_SUCCESS) return status;
        }
        video->usePrevQP = 1;        /*  04/27/01 */
        QPMB[mbnum] = QP;
    }

    video->usePrevQP = 0;  /*  04/27/01 */

    for (mbnum = mb_start; mbnum < mb_end; mbnum++)
    {
        video->mbnum = mbnum;
        video->mbnum_row = PV_GET_ROW(mbnum, nMBPerRow);  /*  This is needed if nbnum is read from the packet header */
        video->mbnum_col = mbnum - video->mbnum_row * nMBPerRow;


        if (Mode[mbnum] != MODE_SKIPPED)
        {
            /* decode the DCT coeficients for the MB */
            status = GetMBData_DataPart(video);
            if (status != PV_SUCCESS)
            {
                /* Report the error to the application.   06/20/2000 */
                VideoDecoderErrorDetected(video);

                /* Conceal second partition,  5/15/2000 */
                movePointerTo(stream, startSecondPart);
                ConcealTexture_P(video, mb_start, mb_end, slice_counter);
                return status;
            }
            video->usePrevQP = 1;  /*  04/27/01 */
        }
        else
        {   // SKIPPED

            /* Motion compensation and put it to video->mblock->pred_block */
            SkippedMBMotionComp(video);

            //oscl_memset(video->predDCAC_row + video->mbnum_col, 0, sizeof(typeDCACStore)); /*  SKIPPED_ACDC */
            //oscl_memset(video->predDCAC_col, 0, sizeof(typeDCACStore));
            /*  08/08/2005 */
            pbyte = (uint8*)(video->predDCAC_row + video->mbnum_col);
            ZERO_OUT_64BYTES(pbyte);
            pbyte = (uint8*)(video->predDCAC_col);
            ZERO_OUT_64BYTES(pbyte);

        }
    }

    valid_stuffing = validStuffing(stream);   /*  */
    if (!valid_stuffing)
    {
        VideoDecoderErrorDetected(video);
        movePointerTo(stream, startSecondPart); /*  */
        ConcealTexture_P(video, mb_start, mb_end, slice_counter);

        return PV_FAIL;
    }
    return PV_SUCCESS;
}


/* ======================================================================== */
/*  Function : GetMBheaderDataPart_DQUANT_DC()                              */
/*  Date     : 04/26/2000                                                   */
/*  Purpose  : Decode DQUANT and DC in Data Partitioned Mode for both       */
/*             I-VOP and P-VOP.                                             */
/*  In/out   :                                                              */
/*  Return   : PV_SUCCESS if successed, PV_FAIL if failed.                  */
/*  Modified : 02/13/2001 new ACDC prediction structure,        */
/*                                       cleanup                            */
/* ======================================================================== */
PV_STATUS GetMBheaderDataPart_DQUANT_DC(VideoDecData *video, int16 *QP)
{
    PV_STATUS status = PV_SUCCESS;
    BitstreamDecVideo *stream = video->bitstream;
    int mbnum = video->mbnum;
    int intra_dc_vlc_thr = video->currVop->intraDCVlcThr;
    uint8 *Mode = video->headerInfo.Mode;
    int  MBtype = Mode[mbnum];
    typeDCStore *DC = video->predDC + mbnum;
    int  comp;
    Bool switched;
    uint  DQUANT;
    int16 QP_tmp;

    const static int  DQ_tab[4] = { -1, -2, 1, 2};

    if (MBtype & Q_MASK)             /* INTRA_Q || INTER_Q */
    {
        DQUANT = BitstreamReadBits16(stream, 2);
        *QP += DQ_tab[DQUANT];

        if (*QP < 1) *QP = 1;
        else if (*QP > 31) *QP = 31;
    }
    if (MBtype & INTRA_MASK)  /* INTRA || INTRA_Q */ /* no switch, code DC separately */
    {
        QP_tmp = *QP;                      /* running QP  04/26/01*/
        switched = 0;
        if (intra_dc_vlc_thr)                 /*  04/27/01 */
        {
            if (video->usePrevQP)
                QP_tmp = video->QPMB[mbnum-1];
            switched = (intra_dc_vlc_thr == 7 || QP_tmp >= intra_dc_vlc_thr * 2 + 11);
        }
        if (!switched)
        {
            for (comp = 0; comp < 6; comp++)
            {
                status = PV_DecodePredictedIntraDC(comp, stream, (*DC + comp));   /*  03/01/01 */
                if (status != PV_SUCCESS) return PV_FAIL;
            }
        }
        else
        {
            for (comp = 0; comp < 6; comp++)
            {
                (*DC)[comp] = 0;   /*  04/26/01 needed for switched case*/
            }
        }
    }
    return status;
}


/***********************************************************CommentBegin******
*       04/25/2000 : Initial modification to the new PV Lib format.
*       04/17/2001 : new ACDC pred structure
***********************************************************CommentEnd********/
PV_STATUS GetMBheaderDataPart_P(VideoDecData *video)
{
    BitstreamDecVideo *stream = video->bitstream;
    int mbnum = video->mbnum;
    uint8 *Mode = video->headerInfo.Mode;
    typeDCStore *DC = video->predDC + mbnum;
    uint no_dct_flag;
    int comp;
    int MCBPC;

    no_dct_flag = BitstreamRead1Bits_INLINE(stream);

    if (no_dct_flag)
    {
        /* skipped macroblock */
        Mode[mbnum] = MODE_SKIPPED;

        for (comp = 0; comp < 6; comp++)
        {
            (*DC)[comp] = mid_gray;
            /*  ACDC REMOVE AC coefs are set in DecodeDataPart_P */
        }
    }
    else
    {
        /* coded macroblock */
        MCBPC = PV_VlcDecMCBPC_com_inter(stream);

        if (VLC_ERROR_DETECTED(MCBPC))
        {
            return PV_FAIL;
        }

        Mode[mbnum] = (uint8)MBtype_mode[MCBPC & 7];
        video->headerInfo.CBP[mbnum] = (uint8)((MCBPC >> 4) & 3);
    }

    return PV_SUCCESS;
}


/***********************************************************CommentBegin******
*       04/17/01  new ACDC pred structure, reorganized code, cleanup
***********************************************************CommentEnd********/
PV_STATUS GetMBData_DataPart(VideoDecData *video)
{
    int mbnum = video->mbnum;
    int16 *dataBlock;
    MacroBlock *mblock = video->mblock;
    int QP = video->QPMB[mbnum];
    int32 offset;
    PIXEL *c_comp;
    int width = video->width;
    int intra_dc_vlc_thr = video->currVop->intraDCVlcThr;
    uint CBP = video->headerInfo.CBP[mbnum];
    uint8 mode = video->headerInfo.Mode[mbnum];
    int x_pos = video->mbnum_col;
    typeDCStore *DC = video->predDC + mbnum;
    int  ncoeffs[6], *no_coeff = mblock->no_coeff;
    int  comp;
    Bool  switched;
    int QP_tmp = QP;

    int y_pos = video->mbnum_row;
#ifdef PV_POSTPROC_ON
    uint8 *pp_mod[6];
    int TotalMB = video->nTotalMB;
    int MB_in_width = video->nMBPerRow;
#endif



    /*****
    *     Decoding of the 6 blocks (depending on transparent pattern)
    *****/
#ifdef PV_POSTPROC_ON
    if (video->postFilterType != PV_NO_POST_PROC)
    {
        /** post-processing ***/
        pp_mod[0] = video->pstprcTypCur + (y_pos << 1) * (MB_in_width << 1) + (x_pos << 1);
        pp_mod[1] = pp_mod[0] + 1;
        pp_mod[2] = pp_mod[0] + (MB_in_width << 1);
        pp_mod[3] = pp_mod[2] + 1;
        pp_mod[4] = video->pstprcTypCur + (TotalMB << 2) + mbnum;
        pp_mod[5] = pp_mod[4] + TotalMB;
    }
#endif

    /*  oscl_memset(mblock->block, 0, sizeof(typeMBStore));    Aug 9,2005 */

    if (mode & INTRA_MASK) /* MODE_INTRA || mode == MODE_INTRA_Q */
    {
        switched = 0;
        if (intra_dc_vlc_thr)
        {
            if (video->usePrevQP)
                QP_tmp = video->QPMB[mbnum-1];   /* running QP  04/26/01 */

            switched = (intra_dc_vlc_thr == 7 || QP_tmp >= intra_dc_vlc_thr * 2 + 11);
        }

        mblock->DCScalarLum = cal_dc_scaler(QP, LUMINANCE_DC_TYPE);     /*   ACDC 03/01/01 */
        mblock->DCScalarChr = cal_dc_scaler(QP, CHROMINANCE_DC_TYPE);

        for (comp = 0; comp < 6; comp++)
        {
            dataBlock = mblock->block[comp];    /*, 10/20/2000 */

            dataBlock[0] = (*DC)[comp];

            ncoeffs[comp] = VlcDequantH263IntraBlock(video, comp,
                            switched, mblock->bitmapcol[comp], &mblock->bitmaprow[comp]);

            if (VLC_ERROR_DETECTED(ncoeffs[comp]))         /*  */
            {
                if (switched)
                    return PV_FAIL;
                else
                {
                    ncoeffs[comp] = 1;
                    oscl_memset((dataBlock + 1), 0, sizeof(int16)*63);
                }
            }
            no_coeff[comp] = ncoeffs[comp];
            /*  modified to new semaphore for post-proc */
            // Future work:: can be combined in the dequant function
            // @todo Deblocking Semaphore for INTRA block
#ifdef PV_POSTPROC_ON
            if (video->postFilterType != PV_NO_POST_PROC)
                *pp_mod[comp] = (uint8) PostProcSemaphore(dataBlock);
#endif
        }
        MBlockIDCT(video);
    }
    else /* MODE INTER*/
    {




        MBMotionComp(video, CBP);
        offset = (int32)(y_pos << 4) * width + (x_pos << 4);
        c_comp  = video->currVop->yChan + offset;


        for (comp = 0; comp < 4; comp++)
        {
            (*DC)[comp] = mid_gray;

            if (CBP & (1 << (5 - comp)))
            {
                ncoeffs[comp] = VlcDequantH263InterBlock(video, comp,
                                mblock->bitmapcol[comp], &mblock->bitmaprow[comp]);
                if (VLC_ERROR_DETECTED(ncoeffs[comp]))
                    return PV_FAIL;


                BlockIDCT(c_comp + (comp&2)*(width << 2) + 8*(comp&1), mblock->pred_block + (comp&2)*64 + 8*(comp&1), mblock->block[comp], width, ncoeffs[comp],
                          mblock->bitmapcol[comp], mblock->bitmaprow[comp]);

            }
            else
            {
                ncoeffs[comp] = 0;
            }

            /*  @todo Deblocking Semaphore for INTRA block, for inter just test for ringing  */
#ifdef PV_POSTPROC_ON
            if (video->postFilterType != PV_NO_POST_PROC)
                *pp_mod[comp] = (uint8)((ncoeffs[comp] > 3) ? 4 : 0);
#endif
        }

        (*DC)[4] = mid_gray;
        if (CBP & 2)
        {
            ncoeffs[4] = VlcDequantH263InterBlock(video, 4,
                                                  mblock->bitmapcol[4], &mblock->bitmaprow[4]);
            if (VLC_ERROR_DETECTED(ncoeffs[4]))
                return PV_FAIL;

            BlockIDCT(video->currVop->uChan + (offset >> 2) + (x_pos << 2), mblock->pred_block + 256, mblock->block[4], width >> 1, ncoeffs[4],
                      mblock->bitmapcol[4], mblock->bitmaprow[4]);

        }
        else
        {
            ncoeffs[4] = 0;
        }
#ifdef PV_POSTPROC_ON
        if (video->postFilterType != PV_NO_POST_PROC)
            *pp_mod[4] = (uint8)((ncoeffs[4] > 3) ? 4 : 0);
#endif
        (*DC)[5] = mid_gray;
        if (CBP & 1)
        {
            ncoeffs[5] = VlcDequantH263InterBlock(video, 5,
                                                  mblock->bitmapcol[5], &mblock->bitmaprow[5]);
            if (VLC_ERROR_DETECTED(ncoeffs[5]))
                return PV_FAIL;

            BlockIDCT(video->currVop->vChan + (offset >> 2) + (x_pos << 2), mblock->pred_block + 264, mblock->block[5], width >> 1, ncoeffs[5],
                      mblock->bitmapcol[5], mblock->bitmaprow[5]);

        }
        else
        {
            ncoeffs[5] = 0;
        }
#ifdef PV_POSTPROC_ON
        if (video->postFilterType != PV_NO_POST_PROC)
            *pp_mod[5] = (uint8)((ncoeffs[5] > 3) ? 4 : 0);
#endif




        /* Motion compensation and put it to video->mblock->pred_block */
    }
    return PV_SUCCESS;
}
