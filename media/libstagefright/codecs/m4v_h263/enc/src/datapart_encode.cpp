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
#ifndef H263_ONLY

#include "mp4def.h"
#include "mp4lib_int.h"
#include "bitstream_io.h"
#include "mp4enc_lib.h"
#include "m4venc_oscl.h"

/* ======================================================================== */
/*  Function : EncodeFrameDataPartMode()                                    */
/*  Date     : 09/6/2000                                                    */
/*  History  :                                                              */
/*  Purpose  : Encode a frame of MPEG4 bitstream in datapartitioning mode.  */
/*  In/out   :                                                              */
/*  Return   :  PV_SUCCESS if successful else PV_FAIL                       */
/*  Modified :                                                              */
/*                                                                          */
/* ======================================================================== */
PV_STATUS EncodeFrameDataPartMode(VideoEncData *video)
{
    PV_STATUS status = PV_SUCCESS;
    Vol *currVol = video->vol[video->currLayer];
    Vop *currVop = video->currVop;
    VideoEncParams *encParams = video->encParams;
    Int width = currVop->width; /* has to be Vop, for multiple of 16 */
    Int lx = currVop->pitch; /*  with padding */
    Int offset = 0;
    Int ind_x, ind_y;
    Int start_packet_header = 0;
    UChar *QPMB = video->QPMB;
    Int QP;
    Int mbnum = 0, slice_counter = 0;
    Int num_bits, packet_size = encParams->ResyncPacketsize;
    BitstreamEncVideo *bs1 = video->bitstream1;
    BitstreamEncVideo *bs2 = video->bitstream2;
    BitstreamEncVideo *bs3 = video->bitstream3;
    Int numHeaderBits;
    approxDCT fastDCTfunction;
    Int ncoefblck[6] = {64, 64, 64, 64, 64, 64}; /* for FastCodeMB,  5/18/2001 */
    PV_STATUS(*CodeMB)(VideoEncData *, approxDCT *, Int, Int[]);
    void (*MBVlcEncode)(VideoEncData*, Int[], void *);
    void (*BlockCodeCoeff)(RunLevelBlock*, BitstreamEncVideo*, Int, Int, UChar);

    video->QP_prev = currVop->quantizer;

    numHeaderBits = BitstreamGetPos(bs1); /* Number of bits in VOP Header */

    /* determine type of quantization   */
#ifndef NO_MPEG_QUANT
    if (currVol->quantType == 0)
        CodeMB = &CodeMB_H263;
    else
        CodeMB = &CodeMB_MPEG;
#else
    CodeMB = &CodeMB_H263;
#endif

    /* determine which functions to be used, in MB-level */
    if (currVop->predictionType == P_VOP)
        MBVlcEncode = &MBVlcEncodeDataPar_P_VOP;
    else if (currVop->predictionType == I_VOP)
        MBVlcEncode = &MBVlcEncodeDataPar_I_VOP;
    else /* B_VOP not implemented yet */
        return PV_FAIL;

    /* determine which VLC table to be used */
    if (currVol->shortVideoHeader)
        BlockCodeCoeff = &BlockCodeCoeff_ShortHeader;
#ifndef NO_RVLC
    else if (currVol->useReverseVLC)
        BlockCodeCoeff = &BlockCodeCoeff_RVLC;
#endif
    else
        BlockCodeCoeff = &BlockCodeCoeff_Normal;

    video->usePrevQP = 0;

    for (ind_y = 0; ind_y < currVol->nMBPerCol; ind_y++)    /* Col MB Loop */
    {

        video->outputMB->mb_y = ind_y; /*  5/28/01 */

        for (ind_x = 0; ind_x < currVol->nMBPerRow; ind_x++)  /* Row MB Loop */
        {
            video->outputMB->mb_x = ind_x; /*  5/28/01 */
            video->mbnum = mbnum;
            video->sliceNo[mbnum] = slice_counter;      /* Update MB slice number */
            QP = QPMB[mbnum];   /* always read new QP */

            /****************************************************************************************/
            /* MB Prediction:Put into MC macroblock, substract from currVop, put in predMB */
            /****************************************************************************************/

            getMotionCompensatedMB(video, ind_x, ind_y, offset);

            if (start_packet_header)
            {
                slice_counter++;                        /* Increment slice counter */
                video->sliceNo[mbnum] = slice_counter;  /* Update MB slice number*/
                video->header_bits -= BitstreamGetPos(bs1); /* Header Bits */
                video->QP_prev = currVop->quantizer;                        /* store QP */
                status = EncodeVideoPacketHeader(video, mbnum, video->QP_prev, 0);
                video->header_bits += BitstreamGetPos(bs1); /* Header Bits */
                numHeaderBits = BitstreamGetPos(bs1);
                start_packet_header = 0;
                video->usePrevQP = 0;
            }

            /***********************************************/
            /* Code_MB:  DCT, Q, Q^(-1), IDCT, Motion Comp */
            /***********************************************/

            status = (*CodeMB)(video, &fastDCTfunction, (offset << 5) + QP, ncoefblck);

            /************************************/
            /* MB VLC Encode: VLC Encode MB     */
            /************************************/

            MBVlcEncode(video, ncoefblck, (void*)BlockCodeCoeff);

            /*************************************************************/
            /* Assemble Packets:  Assemble the MB VLC codes into Packets */
            /*************************************************************/

            /* INCLUDE VOP HEADER IN COUNT */

            num_bits = BitstreamGetPos(bs1) + BitstreamGetPos(bs2) +
                       BitstreamGetPos(bs3) - numHeaderBits;

            /* Assemble_Packet(video) */

            if (num_bits > packet_size)
            {
                if (video->currVop->predictionType == I_VOP)
                    BitstreamPutGT16Bits(bs1, 19, DC_MARKER);   /* Add dc_marker */
                else
                    BitstreamPutGT16Bits(bs1, 17, MOTION_MARKER_COMB); /*Add motion_marker*/
                BitstreamAppendEnc(bs1, bs2);   /* Combine bs1 and bs2 */
                BitstreamAppendEnc(bs1, bs3);   /* Combine bs1 and bs3 */
                video->header_bits += BitstreamMpeg4ByteAlignStuffing(bs1); /* Byte align Packet */

                status = BitstreamAppendPacket(currVol->stream, bs1); /* Put Packet to Buffer */
                /* continue even if status == PV_END_OF_BUF, to get the stats */

                BitstreamEncReset(bs1); /* Initialize to 0 */
                BitstreamEncReset(bs2);
                BitstreamEncReset(bs3);
                start_packet_header = 1;
            }
            mbnum++;
            offset += 16;
        } /* End of For ind_x */

        offset += (lx << 4) - width;
    } /* End of For ind_y */

    if (!start_packet_header)
    {
        if (video->currVop->predictionType == I_VOP)
        {
            BitstreamPutGT16Bits(bs1, 19, DC_MARKER);   /* Add dc_marker */
            video->header_bits += 19;
        }
        else
        {
            BitstreamPutGT16Bits(bs1, 17, MOTION_MARKER_COMB); /* Add motion_marker */
            video->header_bits += 17;
        }
        BitstreamAppendEnc(bs1, bs2);
        BitstreamAppendEnc(bs1, bs3);
        video->header_bits += BitstreamMpeg4ByteAlignStuffing(bs1); /* Byte align Packet */
        status = BitstreamAppendPacket(currVol->stream, bs1); /* Put Packet to Buffer */
        /* continue even if status == PV_END_OF_BUF, to get the stats */
        BitstreamEncReset(bs1); /* Initialize to 0 */
        BitstreamEncReset(bs2);
        BitstreamEncReset(bs3);
    }

    return status; /* if status == PV_END_OF_BUF, this frame will be pre-skipped */
}

#ifndef  NO_SLICE_ENCODE
/* ======================================================================== */
/*  Function : EncodeSliceDataPartMode()                                    */
/*  Date     : 04/19/2002                                                   */
/*  History  :                                                              */
/*  Purpose  : Encode a slice of MPEG4 bitstream in DataPar mode and save   */
/*              the current MB to continue next time it is called.          */
/*  In/out   :                                                              */
/*  Return   :  PV_SUCCESS if successful else PV_FAIL                       */
/*  Modified :                                                              */
/*                                                                          */
/* ======================================================================== */
PV_STATUS EncodeSliceDataPartMode(VideoEncData *video)
{
    PV_STATUS status = PV_SUCCESS;
    Vol *currVol = video->vol[video->currLayer];
    Vop *currVop = video->currVop;
    UChar mode, *Mode = video->headerInfo.Mode;
    VideoEncParams *encParams = video->encParams;
    Int nTotalMB = currVol->nTotalMB;
    Int width = currVop->width; /* has to be Vop, for multiple of 16 */
    Int lx = currVop->pitch; /* , with pading */
    UChar *QPMB = video->QPMB;
    Int QP;
    Int ind_x = video->outputMB->mb_x, ind_y = video->outputMB->mb_y;
    Int offset = video->offset;                 /* get current MB location */
    Int mbnum = video->mbnum, slice_counter = video->sliceNo[mbnum]; /* get current MB location */
    Int firstMB = mbnum;
    Int start_packet_header = (mbnum != 0);
    Int num_bits = 0;
    Int packet_size = encParams->ResyncPacketsize - 1 - (currVop->predictionType == I_VOP ? 19 : 17);
    BitstreamEncVideo *bs1 = video->bitstream1;
    BitstreamEncVideo *bs2 = video->bitstream2;
    BitstreamEncVideo *bs3 = video->bitstream3;
    Int bitCount1 = 0, bitCount2 = 0, bitCount3 = 0, byteCount1 = 0, byteCount2 = 0, byteCount3 = 0;
    Int numHeaderBits = 0;
    approxDCT fastDCTfunction;
    Int ncoefblck[6] = {64, 64, 64, 64, 64, 64}; /* for FastCodeMB,  5/18/2001 */
    UChar CBP;
    Short outputMB[6][64];
    PV_STATUS(*CodeMB)(VideoEncData *, approxDCT *, Int, Int[]);
    void (*MBVlcEncode)(VideoEncData*, Int[], void *);
    void (*BlockCodeCoeff)(RunLevelBlock*, BitstreamEncVideo*, Int, Int, UChar);
    Int k;

    video->QP_prev = 31;

    if (video->end_of_buf) /* left-over from previous run */
    {
        status = BitstreamAppendPacketNoOffset(currVol->stream, bs1);
        if (status != PV_END_OF_BUF)
        {
            BitstreamEncReset(bs1);
            video->end_of_buf = 0;
        }
        return status;
    }

    if (mbnum == 0) /* only do this at the start of a frame */
    {
        QPMB[0] = video->QP_prev = QP = currVop->quantizer;
        video->usePrevQP = 0;

        numHeaderBits = BitstreamGetPos(bs1); /* Number of bits in VOP Header */

    }


    /* Re-assign fast functions on every slice, don't have to put it in the memory */
    QP = QPMB[mbnum];
    if (mbnum > 0)   video->QP_prev = QPMB[mbnum-1];

    /* determine type of quantization   */
#ifndef NO_MPEG_QUANT
    if (currVol->quantType == 0)
        CodeMB = &CodeMB_H263;
    else
        CodeMB = &CodeMB_MPEG;
#else
    CodeMB = &CodeMB_H263;
#endif

    /* determine which functions to be used, in MB-level */
    if (currVop->predictionType == P_VOP)
        MBVlcEncode = &MBVlcEncodeDataPar_P_VOP;
    else if (currVop->predictionType == I_VOP)
        MBVlcEncode = &MBVlcEncodeDataPar_I_VOP;
    else /* B_VOP not implemented yet */
        return PV_FAIL;

    /* determine which VLC table to be used */
#ifndef NO_RVLC
    if (currVol->useReverseVLC)
        BlockCodeCoeff = &BlockCodeCoeff_RVLC;
    else
#endif
        BlockCodeCoeff = &BlockCodeCoeff_Normal;

    if (mbnum != 0)
    {
        goto JUMP_IN;
    }

    for (ind_y = 0; ind_y < currVol->nMBPerCol; ind_y++)    /* Col MB Loop */
    {

        video->outputMB->mb_y = ind_y; /*  5/28/01 */

        for (ind_x = 0; ind_x < currVol->nMBPerRow; ind_x++)  /* Row MB Loop */
        {

            video->outputMB->mb_x = ind_x; /*  5/28/01 */
            video->mbnum = mbnum;
            video->sliceNo[mbnum] = slice_counter;      /* Update MB slice number */

            /****************************************************************************************/
            /* MB Prediction:Put into MC macroblock, substract from currVop, put in predMB */
            /****************************************************************************************/
            getMotionCompensatedMB(video, ind_x, ind_y, offset);

JUMP_IN:

            QP = QPMB[mbnum];   /* always read new QP */

            if (start_packet_header)
            {
                slice_counter++;                        /* Increment slice counter */
                video->sliceNo[mbnum] = slice_counter;  /* Update MB slice number*/
                video->QP_prev = currVop->quantizer;                        /* store QP */
                num_bits = BitstreamGetPos(bs1);
                status = EncodeVideoPacketHeader(video, mbnum, video->QP_prev, 0);
                numHeaderBits = BitstreamGetPos(bs1) - num_bits;
                video->header_bits += numHeaderBits; /* Header Bits */
                start_packet_header = 0;
                video->usePrevQP = 0;
            }
            else  /* don't encode the first MB in packet again */
            {
                /***********************************************/
                /* Code_MB:  DCT, Q, Q^(-1), IDCT, Motion Comp */
                /***********************************************/

                status = (*CodeMB)(video, &fastDCTfunction, (offset << 5) + QP, ncoefblck);
                for (k = 0; k < 6; k++)
                {
                    M4VENC_MEMCPY(outputMB[k], video->outputMB->block[k], sizeof(Short) << 6);
                }
            }

            /************************************/
            /* MB VLC Encode: VLC Encode MB     */
            /************************************/

            /* save the state before VLC encoding */
            bitCount1 = BitstreamGetPos(bs1);
            bitCount2 = BitstreamGetPos(bs2);
            bitCount3 = BitstreamGetPos(bs3);
            byteCount1 = bitCount1 >> 3;
            byteCount2 = bitCount2 >> 3;
            byteCount3 = bitCount3 >> 3;
            bitCount1 &= 0x7;
            bitCount2 &= 0x7;
            bitCount3 &= 0x7;
            mode = Mode[mbnum];
            CBP = video->headerInfo.CBP[mbnum];

            /*************************************/

            MBVlcEncode(video, ncoefblck, (void*)BlockCodeCoeff);

            /*************************************************************/
            /* Assemble Packets:  Assemble the MB VLC codes into Packets */
            /*************************************************************/

            num_bits = BitstreamGetPos(bs1) + BitstreamGetPos(bs2) +
                       BitstreamGetPos(bs3);// - numHeaderBits; //include header bits

            /* Assemble_Packet(video) */
            if (num_bits > packet_size && mbnum != firstMB)  /* encoding at least one more MB*/
            {

                BitstreamRepos(bs1, byteCount1, bitCount1); /* rewind one MB */
                BitstreamRepos(bs2, byteCount2, bitCount2); /* rewind one MB */
                BitstreamRepos(bs3, byteCount3, bitCount3); /* rewind one MB */

                if (video->currVop->predictionType == I_VOP)
                {
                    BitstreamPutGT16Bits(bs1, 19, DC_MARKER);   /* Add dc_marker */
                    video->header_bits += 19;
                }
                else
                {
                    BitstreamPutGT16Bits(bs1, 17, MOTION_MARKER_COMB); /*Add motion_marker*/
                    video->header_bits += 17;
                }

                status = BitstreamAppendEnc(bs1, bs2);  /* Combine with bs2 */
                status = BitstreamAppendEnc(bs1, bs3);  /* Combine with bs3 */

                video->header_bits += BitstreamMpeg4ByteAlignStuffing(bs1); /* Byte align Packet */
                status = BitstreamAppendPacketNoOffset(currVol->stream, bs1);

                BitstreamEncReset(bs2);
                BitstreamEncReset(bs3);

                if (status == PV_END_OF_BUF) /* if cannot fit a buffer */
                {
                    video->end_of_buf = 1;
                }
                else
                {
                    BitstreamEncReset(bs1);
                }

                start_packet_header = 1;

                if (mbnum < nTotalMB || video->end_of_buf) /* return here */
                {
                    video->mbnum = mbnum;
                    video->sliceNo[mbnum] = slice_counter;
                    video->offset = offset;
                    Mode[mbnum] = mode;
                    video->headerInfo.CBP[mbnum] = CBP;

                    for (k = 0; k < 6; k++)
                    {
                        M4VENC_MEMCPY(video->outputMB->block[k], outputMB[k], sizeof(Short) << 6);
                    }

                    return status;
                }
            }

            offset += 16;
            mbnum++; /* has to increment before SCD, to preserve Mode[mbnum] */
        } /* End of For ind_x */

        offset += (lx << 4) - width;

    } /* End of For ind_y */

    if (!start_packet_header)
    {
        if (video->currVop->predictionType == I_VOP)
        {
            BitstreamPutGT16Bits(bs1, 19, DC_MARKER);   /* Add dc_marker */
            video->header_bits += 19;
        }
        else
        {
            BitstreamPutGT16Bits(bs1, 17, MOTION_MARKER_COMB); /*Add motion_marker*/
            video->header_bits += 17;
        }

        status = BitstreamAppendEnc(bs1, bs2);  /* Combine with bs2 */
        status = BitstreamAppendEnc(bs1, bs3);  /* Combine with bs3 */

        video->header_bits += BitstreamMpeg4ByteAlignStuffing(bs1); /* Byte align Packet */
        status = BitstreamAppendPacketNoOffset(currVol->stream, bs1);

        BitstreamEncReset(bs2);
        BitstreamEncReset(bs3);

        if (status == PV_END_OF_BUF)
        {
            video->end_of_buf = 1;
        }
        else
        {
            BitstreamEncReset(bs1);
        }
    }

    video->mbnum = mbnum;
    if (mbnum < nTotalMB)
        video->sliceNo[mbnum] = slice_counter;
    video->offset = offset;

    return status;
}
#endif /* NO_SLICE_ENCODE */
#endif /* H263_ONLY */


