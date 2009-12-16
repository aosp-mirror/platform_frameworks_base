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
#include "mp4dec_lib.h" /* video decoder function prototypes */
#include "vlc_decode.h"
#include "bitstream.h"
#include "scaling.h"
#include "mbtype_mode.h"

#define OSCL_DISABLE_WARNING_CONDITIONAL_IS_CONSTANT
/* ======================================================================== */
/*  Function : DecodeFrameCombinedMode()                                    */
/*  Purpose  : Decode a frame of MPEG4 bitstream in combined mode.          */
/*  In/out   :                                                              */
/*  Return   :                                                              */
/*  Modified :                                                              */
/*                                                                          */
/*      03/30/2000 : Cleaned up and optimized the code.             */
/*      03/31/2000 : Added proper handling of MB stuffing.          */
/*      04/13/2000 : Rewrote this combined mode path completely     */
/*                           so that it handles "Combined Mode With Error   */
/*                           Resilience."  Now the code resembles the       */
/*                           pseudo codes in MPEG-4 standard better.        */
/*      10/13/2000 : Add fast VLC+dequant                           */
/*      04/13/2001 : fix MB_stuffing                               */
/*      08/07/2001 : remove MBzero                                  */
/* ======================================================================== */
PV_STATUS DecodeFrameCombinedMode(VideoDecData *video)
{
    PV_STATUS status;
    int mbnum;
    Vop *currVop = video->currVop;
    BitstreamDecVideo *stream = video->bitstream;
    int shortVideoHeader = video->shortVideoHeader;
    int16 QP, *QPMB = video->QPMB;
    uint8 *Mode = video->headerInfo.Mode;
    int nTotalMB = video->nTotalMB;
    int nMBPerRow = video->nMBPerRow;
    int slice_counter;
    uint32 tmpvar, long_zero_bits;
    uint code;
    int valid_stuffing;
    int resync_marker_length;
    int stuffing_length;

    /* add this for error resilient, 05/18/2000 */
    int32 startPacket;
    int mb_start;
    /* copy and pad to prev_Vop for INTER coding */
    switch (currVop->predictionType)
    {
        case I_VOP :
//      oscl_memset(Mode, MODE_INTRA, sizeof(uint8)*nTotalMB);
            resync_marker_length = 17;
            stuffing_length = 9;
            break;
        case P_VOP :
            oscl_memset(video->motX, 0, sizeof(MOT)*4*nTotalMB);
            oscl_memset(video->motY, 0, sizeof(MOT)*4*nTotalMB);
//      oscl_memset(Mode, MODE_INTER, sizeof(uint8)*nTotalMB);
            resync_marker_length = 16 + currVop->fcodeForward;
            stuffing_length = 10;
            break;
        default :
            mp4dec_log("DecodeFrameCombinedMode(): Vop type not supported.\n");
            return PV_FAIL;
    }
#ifdef PV_ANNEX_IJKT_SUPPORT
    if (video->shortVideoHeader)
    {
        if (video->advanced_INTRA)
        {
            if (video->modified_quant)
            {
                video->vlcDecCoeffIntra = &VlcDecTCOEFShortHeader_AnnexIT;
                video->vlcDecCoeffInter = &VlcDecTCOEFShortHeader_AnnexT;
            }
            else
            {
                video->vlcDecCoeffIntra = &VlcDecTCOEFShortHeader_AnnexI;
                video->vlcDecCoeffInter = &VlcDecTCOEFShortHeader;
            }
        }
        else
        {
            if (video->modified_quant)
            {
                video->vlcDecCoeffInter = video->vlcDecCoeffIntra = &VlcDecTCOEFShortHeader_AnnexT;
            }
            else
            {
                video->vlcDecCoeffInter = video->vlcDecCoeffIntra = &VlcDecTCOEFShortHeader;
            }
        }
    }

#endif

    /** Initialize sliceNo ***/
    mbnum = slice_counter = 0;
//  oscl_memset(video->sliceNo, 0, sizeof(uint8)*nTotalMB);
    QP = video->currVop->quantizer;

    do
    {
        /* This section is equivalent to motion_shape_texture() */
        /*    in the MPEG-4 standard.     04/13/2000          */
        mb_start = mbnum;
        video->usePrevQP = 0;             /*  04/27/01 */
        startPacket = getPointer(stream);

#ifdef PV_ANNEX_IJKT_SUPPORT
        if (video->modified_quant)
        {
            video->QP_CHR = MQ_chroma_QP_table[QP];
        }
        else
        {
            video->QP_CHR = QP;     /* ANNEX_T */
        }
#endif
        /* remove any stuffing bits */
        BitstreamShowBits16(stream, stuffing_length, &code);
        while (code == 1)
        {
            PV_BitstreamFlushBits(stream, stuffing_length);
            BitstreamShowBits16(stream, stuffing_length, &code);
        }

        do
        {
            /* we need video->mbnum in lower level functions */
            video->mbnum = mbnum;
            video->mbnum_row = PV_GET_ROW(mbnum, nMBPerRow);
            video->mbnum_col = mbnum - video->mbnum_row * nMBPerRow;
            /* assign slice number for each macroblocks */
            video->sliceNo[mbnum] = (uint8) slice_counter;

            /* decode COD, MCBPC, ACpred_flag, CPBY and DQUANT */
            /* We have to discard stuffed MB header */
            status = GetMBheader(video, &QP);

            if (status != PV_SUCCESS)
            {
                VideoDecoderErrorDetected(video);
                video->mbnum = mb_start;
                movePointerTo(stream, (startPacket & -8));
                break;
            }

            /* Store the QP value for later use in AC prediction */
            QPMB[mbnum] = QP;

            if (Mode[mbnum] != MODE_SKIPPED)
            {
                /* decode the DCT coeficients for the MB */
                status = GetMBData(video);
                if (status != PV_SUCCESS)
                {
                    VideoDecoderErrorDetected(video);
                    video->mbnum = mb_start;
                    movePointerTo(stream, (startPacket & -8));
                    break;
                }
            }
            else /* MODE_SKIPPED */
            {
                SkippedMBMotionComp(video); /*  08/04/05 */
            }
            // Motion compensation and put video->mblock->pred_block
            mbnum++;

            /* remove any stuffing bits */
            BitstreamShowBits16(stream, stuffing_length, &code);
            while (code == 1)
            {
                PV_BitstreamFlushBits(stream, stuffing_length);
                BitstreamShowBits16(stream, stuffing_length, &code);
            }

            /* have we reached the end of the video packet or vop? */
            if (shortVideoHeader)
            {
#ifdef PV_ANNEX_IJKT_SUPPORT
                if (!video->slice_structure)
                {
#endif
                    if (mbnum >= (int)(video->mbnum_row + 1)*video->nMBinGOB)   /*  10/11/01 */
                    {
                        if (mbnum >= nTotalMB) return PV_SUCCESS;
                        status = BitstreamShowBits32(stream, GOB_RESYNC_MARKER_LENGTH, &tmpvar);

                        if (tmpvar == GOB_RESYNC_MARKER)
                        {
                            break;
                        }
                        else
                        {
                            status = PV_BitstreamShowBitsByteAlign(stream, GOB_RESYNC_MARKER_LENGTH, &tmpvar);
                            if (tmpvar == GOB_RESYNC_MARKER) break;
                        }
                    }
#ifdef PV_ANNEX_IJKT_SUPPORT
                }
                else
                {

                    if (mbnum >= nTotalMB)  /* in case no valid stuffing  06/23/01 */
                    {
                        valid_stuffing = validStuffing_h263(stream);
                        if (valid_stuffing == 0)
                        {
                            VideoDecoderErrorDetected(video);
                            ConcealPacket(video, mb_start, nTotalMB, slice_counter);
                        }
                        return PV_SUCCESS;
                    }
                    /* ANNEX_K */
                    PV_BitstreamShowBitsByteAlignNoForceStuffing(stream, 17, &tmpvar);
                    if (tmpvar == RESYNC_MARKER)
                    {
                        valid_stuffing = validStuffing_h263(stream);
                        if (valid_stuffing)
                            break; /*  06/21/01 */
                    }

                }
#endif
            }
            else
            {
                if (mbnum >= nTotalMB)  /* in case no valid stuffing  06/23/01 */
                {
                    /*  11/01/2002 if we are at the end of the frame and there is some garbage data
                    at the end of the frame (i.e. no next startcode) break if the stuffing is valid */
                    valid_stuffing = validStuffing(stream);
                    if (valid_stuffing == 0)
                    {
                        /* end 11/01/2002 */
                        VideoDecoderErrorDetected(video);
                        ConcealPacket(video, mb_start, nTotalMB, slice_counter);
                    }
                    PV_BitstreamByteAlign(stream);
                    return PV_SUCCESS;
                }

                status = PV_BitstreamShowBitsByteAlign(stream, 23, &tmpvar); /* this call is valid for f_code < 8 */
                long_zero_bits = !tmpvar;

                if ((tmpvar >> (23 - resync_marker_length)) == RESYNC_MARKER || long_zero_bits)
                {
                    valid_stuffing = validStuffing(stream);
                    if (valid_stuffing)
                        break; /*  06/21/01 */
                }

            }
        }
        while (TRUE);

        if (shortVideoHeader)
        { /* We need to check newgob to refresh quantizer */
#ifdef PV_ANNEX_IJKT_SUPPORT
            if (!video->slice_structure)
            {
#endif
                while ((status = PV_GobHeader(video)) == PV_FAIL)
                {
                    if ((status = quickSearchGOBHeader(stream)) != PV_SUCCESS)
                    {
                        break;
                    }
                }

                mbnum = currVop->gobNumber * video->nMBinGOB;
#ifdef PV_ANNEX_IJKT_SUPPORT
            }
            else
            {
                while ((status = PV_H263SliceHeader(video, &mbnum)) == PV_FAIL)
                {
                    if ((status = quickSearchH263SliceHeader(stream)) != PV_SUCCESS)
                    {
                        break;
                    }
                }
            }

#endif
        }
        else
        {
            while ((status = PV_ReadVideoPacketHeader(video, &mbnum)) == PV_FAIL)
            {
                if ((status = quickSearchVideoPacketHeader(stream, resync_marker_length)) != PV_SUCCESS)
                {
                    break;
                }
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
        QP = video->currVop->quantizer;
        slice_counter++;
        if (mbnum >= nTotalMB) break;

    }
    while (TRUE);
    return PV_SUCCESS;
}


/* ============================================================================ */
/*  Function : GetMBHeader()                                                    */
/*  Purpose  : Decode MB header, not_coded, mcbpc, ac_pred_flag, cbpy, dquant.  */
/*  In/out   :                                                                  */
/*  Return   :                                                                  */
/*  Modified :                                                                  */
/*                                                                              */
/*      3/29/00 : Changed the returned value and optimized the code.    */
/*      4/01/01 : new ACDC prediction structure                         */
/* ============================================================================ */
PV_STATUS GetMBheader(VideoDecData *video, int16 *QP)
{
    BitstreamDecVideo *stream = video->bitstream;
    int mbnum = video->mbnum;
    uint8 *Mode = video->headerInfo.Mode;
    int x_pos = video->mbnum_col;
    typeDCStore *DC = video->predDC + mbnum;
    typeDCACStore *DCAC_row = video->predDCAC_row + x_pos;
    typeDCACStore *DCAC_col = video->predDCAC_col;
    const static int16  DQ_tab[4] = { -1, -2, 1, 2};

    int CBPY, CBPC;
    int MBtype, VopType;
    int MCBPC;
    uint DQUANT;
    int comp;
    Bool mb_coded;

    VopType = video->currVop->predictionType;
    mb_coded = ((VopType == I_VOP) ? TRUE : !BitstreamRead1Bits_INLINE(stream));

    if (!mb_coded)
    {
        /* skipped macroblock */
        Mode[mbnum] = MODE_SKIPPED;
        //oscl_memset(DCAC_row, 0, sizeof(typeDCACStore));   /*  SKIPPED_ACDC */
        //oscl_memset(DCAC_col, 0, sizeof(typeDCACStore));
        ZERO_OUT_64BYTES(DCAC_row);
        ZERO_OUT_64BYTES(DCAC_col); /*  08/12/05 */

        for (comp = 0; comp < 6; comp++)
        {
            (*DC)[comp] = mid_gray;
        }
    }
    else
    {
        /* coded macroblock */
        if (VopType == I_VOP)
        {
            MCBPC = PV_VlcDecMCBPC_com_intra(stream);
        }
        else
        {
#ifdef PV_ANNEX_IJKT_SUPPORT
            if (!video->deblocking)
            {
                MCBPC = PV_VlcDecMCBPC_com_inter(stream);
            }
            else
            {
                MCBPC = PV_VlcDecMCBPC_com_inter_H263(stream);
            }
#else
            MCBPC = PV_VlcDecMCBPC_com_inter(stream);
#endif
        }

        if (VLC_ERROR_DETECTED(MCBPC))
        {
            return PV_FAIL;
        }

        Mode[mbnum] = (uint8)(MBtype = MBtype_mode[MCBPC & 7]);
        CBPC = (MCBPC >> 4) & 3;

#ifdef PV_ANNEX_IJKT_SUPPORT
        if (MBtype & INTRA_MASK)
        {
            if (!video->shortVideoHeader)
            {
                video->acPredFlag[mbnum] = (uint8) BitstreamRead1Bits(stream);
            }
            else
            {
                if (video->advanced_INTRA)
                {
                    if (!BitstreamRead1Bits(stream))
                    {
                        video->acPredFlag[mbnum] = 0;
                    }
                    else
                    {
                        video->acPredFlag[mbnum] = 1;
                        if (BitstreamRead1Bits(stream))
                        {
                            video->mblock->direction = 0;
                        }
                        else
                        {
                            video->mblock->direction = 1;
                        }
                    }
                }
                else
                {
                    video->acPredFlag[mbnum] = 0;
                }
            }
        }
#else
        if ((MBtype & INTRA_MASK) && !video->shortVideoHeader)
        {
            video->acPredFlag[mbnum] = (uint8) BitstreamRead1Bits_INLINE(stream);
        }
        else
        {
            video->acPredFlag[mbnum] = 0;
        }
#endif
        CBPY = PV_VlcDecCBPY(stream, MBtype & INTRA_MASK); /* INTRA || INTRA_Q */
        if (CBPY < 0)
        {
            return PV_FAIL;
        }

        // GW 04/23/99
        video->headerInfo.CBP[mbnum] = (uint8)(CBPY << 2 | (CBPC & 3));
#ifdef PV_ANNEX_IJKT_SUPPORT
        if (MBtype & Q_MASK)
        {
            if (!video->modified_quant)
            {
                DQUANT = BitstreamReadBits16(stream, 2);
                *QP += DQ_tab[DQUANT];

                if (*QP < 1) *QP = 1;
                else if (*QP > 31) *QP = 31;
                video->QP_CHR = *QP;  /* ANNEX_T */
            }
            else
            {
                if (BitstreamRead1Bits(stream))
                {
                    if (BitstreamRead1Bits(stream))
                    {
                        *QP += DQ_tab_Annex_T_11[*QP];
                    }
                    else
                    {
                        *QP += DQ_tab_Annex_T_10[*QP];
                    }
                    if (*QP < 1) *QP = 1;
                    else if (*QP > 31) *QP = 31;
                }
                else
                {
                    *QP = (int16)BitstreamReadBits16(stream, 5);
                }
                video->QP_CHR =  MQ_chroma_QP_table[*QP];
            }
        }
#else
        if (MBtype & Q_MASK)
        {
            DQUANT = BitstreamReadBits16(stream, 2);
            *QP += DQ_tab[DQUANT];

            if (*QP < 1) *QP = 1;
            else if (*QP > 31) *QP = 31;
        }
#endif
    }
    return PV_SUCCESS;
}





/***********************************************************CommentBegin******
*       3/10/00  : initial modification to the
*                new PV-Decoder Lib format.
*       4/2/2000 : Cleanup and error-handling modification.  This
*                   function has been divided into several sub-functions for
*                   better coding style and maintainance reason.  I also
*                   greatly shrunk the code size here.
*       9/18/2000 : VlcDecode+Dequant optimization *
*       4/01/2001 : new ACDC prediction structure
*       3/29/2002 : removed GetIntraMB and GetInterMB
***********************************************************CommentEnd********/
PV_STATUS GetMBData(VideoDecData *video)
{
    BitstreamDecVideo *stream = video->bitstream;
    int mbnum = video->mbnum;
    MacroBlock *mblock = video->mblock;
    int16 *dataBlock;
    PIXEL *c_comp;
    uint mode = video->headerInfo.Mode[mbnum];
    uint CBP = video->headerInfo.CBP[mbnum];
    typeDCStore *DC = video->predDC + mbnum;
    int intra_dc_vlc_thr = video->currVop->intraDCVlcThr;
    int16 QP = video->QPMB[mbnum];
    int16 QP_tmp = QP;
    int width = video->width;
    int  comp;
    int  switched;
    int ncoeffs[6] = {0, 0, 0, 0, 0, 0};
    int *no_coeff = mblock->no_coeff;
    int16 DC_coeff;
    PV_STATUS status;

#ifdef PV_POSTPROC_ON
    /* post-processing */
    uint8 *pp_mod[6];
    int TotalMB = video->nTotalMB;
    int MB_in_width = video->nMBPerRow;
#endif
    int y_pos = video->mbnum_row;
    int x_pos = video->mbnum_col;
    int32 offset = (int32)(y_pos << 4) * width + (x_pos << 4);

    /* Decode each 8-by-8 blocks. comp 0 ~ 3 are luminance blocks, 4 ~ 5 */
    /*  are chrominance blocks.   04/03/2000.                          */
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

    if (mode & INTRA_MASK) /* MODE_INTRA || MODE_INTRA_Q */
    {
        switched = 0;
        if (intra_dc_vlc_thr)
        {
            if (video->usePrevQP)
                QP_tmp = video->QPMB[mbnum-1];   /* running QP  04/26/01 */

            switched = (intra_dc_vlc_thr == 7 || QP_tmp >= intra_dc_vlc_thr * 2 + 11);
        }

        mblock->DCScalarLum = cal_dc_scaler(QP, LUMINANCE_DC_TYPE);   /*  3/01/01 */
        mblock->DCScalarChr = cal_dc_scaler(QP, CHROMINANCE_DC_TYPE);

        for (comp = 0; comp < 6; comp++)
        {
            dataBlock = mblock->block[comp];    /* 10/20/2000 */

            if (video->shortVideoHeader)
            {
#ifdef PV_ANNEX_IJKT_SUPPORT
                if (!video->advanced_INTRA)
                {
#endif
                    DC_coeff = (int16) BitstreamReadBits16_INLINE(stream, 8);

                    if ((DC_coeff & 0x7f) == 0) /* 128 & 0  */
                    {
                        /* currently we will only signal FAIL for 128. We will ignore the 0 case  */
                        if (DC_coeff == 128)
                        {
                            return PV_FAIL;
                        }
                        else
                        {
                            VideoDecoderErrorDetected(video);
                        }
                    }
                    if (DC_coeff == 255)
                    {
                        DC_coeff = 128;
                    }
                    dataBlock[0] = (int16) DC_coeff;
#ifdef PV_ANNEX_IJKT_SUPPORT
                }
#endif
                ncoeffs[comp] = VlcDequantH263IntraBlock_SH(video, comp, mblock->bitmapcol[comp], &mblock->bitmaprow[comp]);

            }
            else
            {
                if (switched == 0)
                {
                    status = PV_DecodePredictedIntraDC(comp, stream, &DC_coeff);
                    if (status != PV_SUCCESS) return PV_FAIL;

                    dataBlock[0] = (int16) DC_coeff;
                }
                ncoeffs[comp] = VlcDequantH263IntraBlock(video, comp,
                                switched, mblock->bitmapcol[comp], &mblock->bitmaprow[comp]);
            }

            if (VLC_ERROR_DETECTED(ncoeffs[comp]))
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

#ifdef PV_POSTPROC_ON
            if (video->postFilterType != PV_NO_POST_PROC)
                *pp_mod[comp] = (uint8) PostProcSemaphore(dataBlock);
#endif
        }
        MBlockIDCT(video);
    }
    else      /* INTER modes */
    {   /*  moved it here Aug 15, 2005 */
        /* decode the motion vector (if there are any) */
        status = PV_GetMBvectors(video, mode);
        if (status != PV_SUCCESS)
        {
            return status;
        }


        MBMotionComp(video, CBP);
        c_comp  = video->currVop->yChan + offset;

#ifdef PV_ANNEX_IJKT_SUPPORT
        for (comp = 0; comp < 4; comp++)
        {
            (*DC)[comp] = mid_gray;
            if (CBP & (1 << (5 - comp)))
            {
                ncoeffs[comp] = VlcDequantH263InterBlock(video, comp, mblock->bitmapcol[comp], &mblock->bitmaprow[comp]);
                if (VLC_ERROR_DETECTED(ncoeffs[comp])) return PV_FAIL;

                BlockIDCT(c_comp + (comp&2)*(width << 2) + 8*(comp&1), mblock->pred_block + (comp&2)*64 + 8*(comp&1), mblock->block[comp], width, ncoeffs[comp],
                          mblock->bitmapcol[comp], mblock->bitmaprow[comp]);

#ifdef PV_POSTPROC_ON
                /* for inter just test for ringing */
                if (video->postFilterType != PV_NO_POST_PROC)
                    *pp_mod[comp] = (uint8)((ncoeffs[comp] > 3) ? 4 : 0);
#endif
            }
            else
            {
                /* no IDCT for all zeros blocks  03/28/2002 */
                /*              BlockIDCT();                */
#ifdef PV_POSTPROC_ON
                if (video->postFilterType != PV_NO_POST_PROC)
                    *pp_mod[comp] = 0;
#endif
            }
        }

        video->QPMB[mbnum] = video->QP_CHR;     /* ANNEX_T */



        (*DC)[4] = mid_gray;
        if (CBP & 2)
        {
            ncoeffs[4] = VlcDequantH263InterBlock(video, 4, mblock->bitmapcol[4], &mblock->bitmaprow[4]);
            if (VLC_ERROR_DETECTED(ncoeffs[4])) return PV_FAIL;

            BlockIDCT(video->currVop->uChan + (offset >> 2) + (x_pos << 2), mblock->pred_block + 256, mblock->block[4], width >> 1, ncoeffs[4],
                      mblock->bitmapcol[4], mblock->bitmaprow[4]);

#ifdef PV_POSTPROC_ON
            /* for inter just test for ringing */
            if (video->postFilterType != PV_NO_POST_PROC)
                *pp_mod[4] = (uint8)((ncoeffs[4] > 3) ? 4 : 0);
#endif
        }
        else
        {
            /* no IDCT for all zeros blocks  03/28/2002 */
            /*              BlockIDCT();                */
#ifdef PV_POSTPROC_ON
            if (video->postFilterType != PV_NO_POST_PROC)
                *pp_mod[4] = 0;
#endif
        }
        (*DC)[5] = mid_gray;
        if (CBP & 1)
        {
            ncoeffs[5] = VlcDequantH263InterBlock(video, 5, mblock->bitmapcol[5], &mblock->bitmaprow[5]);
            if (VLC_ERROR_DETECTED(ncoeffs[5])) return PV_FAIL;

            BlockIDCT(video->currVop->vChan + (offset >> 2) + (x_pos << 2), mblock->pred_block + 264, mblock->block[5], width >> 1, ncoeffs[5],
                      mblock->bitmapcol[5], mblock->bitmaprow[5]);

#ifdef PV_POSTPROC_ON
            /* for inter just test for ringing */
            if (video->postFilterType != PV_NO_POST_PROC)
                *pp_mod[5] = (uint8)((ncoeffs[5] > 3) ? 4 : 0);
#endif
        }
        else
        {
            /* no IDCT for all zeros blocks  03/28/2002 */
            /*              BlockIDCT();                */
#ifdef PV_POSTPROC_ON
            if (video->postFilterType != PV_NO_POST_PROC)
                *pp_mod[5] = 0;
#endif
        }
        video->QPMB[mbnum] = QP;  /* restore the QP values  ANNEX_T*/
#else
        for (comp = 0; comp < 4; comp++)
        {
            (*DC)[comp] = mid_gray;
            if (CBP & (1 << (5 - comp)))
            {
                ncoeffs[comp] = VlcDequantH263InterBlock(video, comp, mblock->bitmapcol[comp], &mblock->bitmaprow[comp]);
                if (VLC_ERROR_DETECTED(ncoeffs[comp])) return PV_FAIL;

                BlockIDCT(c_comp + (comp&2)*(width << 2) + 8*(comp&1), mblock->pred_block + (comp&2)*64 + 8*(comp&1), mblock->block[comp], width, ncoeffs[comp],
                          mblock->bitmapcol[comp], mblock->bitmaprow[comp]);

#ifdef PV_POSTPROC_ON
                /* for inter just test for ringing */
                if (video->postFilterType != PV_NO_POST_PROC)
                    *pp_mod[comp] = (uint8)((ncoeffs[comp] > 3) ? 4 : 0);
#endif
            }
            else
            {
                /* no IDCT for all zeros blocks  03/28/2002 */
                /*              BlockIDCT();                */
#ifdef PV_POSTPROC_ON
                if (video->postFilterType != PV_NO_POST_PROC)
                    *pp_mod[comp] = 0;
#endif
            }
        }

        (*DC)[4] = mid_gray;
        if (CBP & 2)
        {
            ncoeffs[4] = VlcDequantH263InterBlock(video, 4, mblock->bitmapcol[4], &mblock->bitmaprow[4]);
            if (VLC_ERROR_DETECTED(ncoeffs[4])) return PV_FAIL;

            BlockIDCT(video->currVop->uChan + (offset >> 2) + (x_pos << 2), mblock->pred_block + 256, mblock->block[4], width >> 1, ncoeffs[4],
                      mblock->bitmapcol[4], mblock->bitmaprow[4]);

#ifdef PV_POSTPROC_ON
            /* for inter just test for ringing */
            if (video->postFilterType != PV_NO_POST_PROC)
                *pp_mod[4] = (uint8)((ncoeffs[4] > 3) ? 4 : 0);
#endif
        }
        else
        {
            /* no IDCT for all zeros blocks  03/28/2002 */
            /*              BlockIDCT();                */
#ifdef PV_POSTPROC_ON
            if (video->postFilterType != PV_NO_POST_PROC)
                *pp_mod[4] = 0;
#endif
        }
        (*DC)[5] = mid_gray;
        if (CBP & 1)
        {
            ncoeffs[5] = VlcDequantH263InterBlock(video, 5, mblock->bitmapcol[5], &mblock->bitmaprow[5]);
            if (VLC_ERROR_DETECTED(ncoeffs[5])) return PV_FAIL;

            BlockIDCT(video->currVop->vChan + (offset >> 2) + (x_pos << 2), mblock->pred_block + 264, mblock->block[5], width >> 1, ncoeffs[5],
                      mblock->bitmapcol[5], mblock->bitmaprow[5]);

#ifdef PV_POSTPROC_ON
            /* for inter just test for ringing */
            if (video->postFilterType != PV_NO_POST_PROC)
                *pp_mod[5] = (uint8)((ncoeffs[5] > 3) ? 4 : 0);
#endif
        }
        else
        {
            /* no IDCT for all zeros blocks  03/28/2002 */
            /*              BlockIDCT();                */
#ifdef PV_POSTPROC_ON
            if (video->postFilterType != PV_NO_POST_PROC)
                *pp_mod[5] = 0;
#endif
#endif  // PV_ANNEX_IJKT_SUPPORT






    }

    video->usePrevQP = 1;          /* should be set after decoding the first Coded  04/27/01 */
    return PV_SUCCESS;
}



