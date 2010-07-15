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
#include "bitstream_io.h"
#include "vlc_encode.h"
#include "m4venc_oscl.h"

PV_STATUS EncodeGOBHeader(VideoEncData *video, Int GOB_number, Int quant_scale, Int bs1stream);

/* ======================================================================== */
/*  Function : EncodeFrameCombinedMode()                                    */
/*  Date     : 09/01/2000                                                   */
/*  History  :                                                              */
/*  Purpose  : Encode a frame of MPEG4 bitstream in Combined mode.          */
/*  In/out   :                                                              */
/*  Return   :  PV_SUCCESS if successful else PV_FAIL                       */
/*  Modified :                                                              */
/*                                                                          */
/* ======================================================================== */
PV_STATUS EncodeFrameCombinedMode(VideoEncData *video)
{
    PV_STATUS status = PV_SUCCESS;
    Vol *currVol = video->vol[video->currLayer];
    Vop *currVop = video->currVop;
    VideoEncParams *encParams = video->encParams;
    Int width = currVop->width; /* has to be Vop, for multiple of 16 */
    Int lx = currVop->pitch; /* with padding */
    Int offset = 0;
    Int ind_x, ind_y;
    Int start_packet_header = 0;
    UChar *QPMB = video->QPMB;
    Int QP;
    Int mbnum = 0, slice_counter = 0, curr_slice_counter = 0;
    Int num_bits, packet_size = encParams->ResyncPacketsize;
    Int GOB_Header_Interval = encParams->GOB_Header_Interval;
    BitstreamEncVideo *bs1 = video->bitstream1;
    Int numHeaderBits;
    approxDCT fastDCTfunction;
    Int ncoefblck[6] = {64, 64, 64, 64, 64, 64}; /* for FastCodeMB,  5/18/2001 */
    PV_STATUS(*CodeMB)(VideoEncData *, approxDCT *, Int, Int[]);
    void (*MBVlcEncode)(VideoEncData*, Int[], void *);
    void (*BlockCodeCoeff)(RunLevelBlock*, BitstreamEncVideo*, Int, Int, UChar);

    /* for H263 GOB changes */
//MP4RateControlType rc_type = encParams->RC_Type;

    video->QP_prev = currVop->quantizer;

    numHeaderBits = BitstreamGetPos(bs1);

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
        MBVlcEncode = &MBVlcEncodeCombined_P_VOP;
    else if (currVop->predictionType == I_VOP)
        MBVlcEncode = &MBVlcEncodeCombined_I_VOP;
    else /* B_VOP not implemented yet */
        return PV_FAIL;

    /* determine which VLC table to be used */
#ifndef H263_ONLY
    if (currVol->shortVideoHeader)
        BlockCodeCoeff = &BlockCodeCoeff_ShortHeader;
#ifndef NO_RVLC
    else if (currVol->useReverseVLC)
        BlockCodeCoeff = &BlockCodeCoeff_RVLC;
#endif
    else
        BlockCodeCoeff = &BlockCodeCoeff_Normal;
#else
    BlockCodeCoeff = &BlockCodeCoeff_ShortHeader;
#endif

    /* gob_frame_id is the same for different vop types - the reason should be SCD */
    if (currVol->shortVideoHeader && currVop->gobFrameID != currVop->predictionType)
        currVop->gobFrameID = currVop->predictionType;


    video->usePrevQP = 0;

    for (ind_y = 0; ind_y < currVol->nMBPerCol; ind_y++)    /* Col MB Loop */
    {

        video->outputMB->mb_y = ind_y; /*  5/28/01 */

        if (currVol->shortVideoHeader)  /* ShortVideoHeader Mode */
        {

            if (slice_counter && GOB_Header_Interval && (ind_y % GOB_Header_Interval == 0))     /* Encode GOB Header */
            {
                QP = QPMB[mbnum];    /* Get quant_scale */
                video->header_bits -= BitstreamGetPos(currVol->stream); /* Header Bits */
                status = EncodeGOBHeader(video, slice_counter, QP, 0);  //ind_y     /* Encode GOB Header */
                video->header_bits += BitstreamGetPos(currVol->stream); /* Header Bits */
                curr_slice_counter = slice_counter;
            }
        }

        for (ind_x = 0; ind_x < currVol->nMBPerRow; ind_x++)  /* Row MB Loop */
        {
            video->outputMB->mb_x = ind_x; /*  5/28/01 */
            video->mbnum = mbnum;
            QP = QPMB[mbnum];   /* always read new QP */

            if (GOB_Header_Interval)
                video->sliceNo[mbnum] = curr_slice_counter; /* Update MB slice number */
            else
                video->sliceNo[mbnum] = slice_counter;

            /****************************************************************************************/
            /* MB Prediction:Put into MC macroblock, substract from currVop, put in predMB */
            /****************************************************************************************/
            getMotionCompensatedMB(video, ind_x, ind_y, offset);

#ifndef H263_ONLY
            if (start_packet_header)
            {
                slice_counter++;                        /* Increment slice counter */
                video->sliceNo[mbnum] = slice_counter;  /* Update MB slice number*/
                video->header_bits -= BitstreamGetPos(bs1); /* Header Bits */
                video->QP_prev = currVop->quantizer;
                status = EncodeVideoPacketHeader(video, mbnum, video->QP_prev, 0);
                video->header_bits += BitstreamGetPos(bs1); /* Header Bits */
                numHeaderBits = BitstreamGetPos(bs1);
                start_packet_header = 0;
                video->usePrevQP = 0;
            }
#endif
            /***********************************************/
            /* Code_MB:  DCT, Q, Q^(-1), IDCT, Motion Comp */
            /***********************************************/

            status = (*CodeMB)(video, &fastDCTfunction, (offset << 5) + QP, ncoefblck);

            /************************************/
            /* MB VLC Encode: VLC Encode MB     */
            /************************************/

            (*MBVlcEncode)(video, ncoefblck, (void*)BlockCodeCoeff);

            /*************************************************************/
            /* Assemble Packets:  Assemble the MB VLC codes into Packets */
            /*************************************************************/

            /* Assemble_Packet(video) */
#ifndef H263_ONLY
            if (!currVol->shortVideoHeader) /* Not in ShortVideoHeader mode */
            {
                if (!currVol->ResyncMarkerDisable) /* RESYNC MARKER MODE */
                {
                    num_bits = BitstreamGetPos(bs1) - numHeaderBits;
                    if (num_bits > packet_size)
                    {
                        video->header_bits += BitstreamMpeg4ByteAlignStuffing(bs1); /* Byte align Packet */

                        status = BitstreamAppendPacket(currVol->stream, bs1); /* Put Packet to Buffer */
                        /* continue even if status == PV_END_OF_BUF, to get the stats */

                        BitstreamEncReset(bs1);

                        start_packet_header = 1;
                    }
                }
                else   /* NO RESYNC MARKER MODE */
                {
                    status = BitstreamAppendEnc(currVol->stream, bs1); /* Initialize to 0 */
                    /* continue even if status == PV_END_OF_BUF, to get the stats */

                    BitstreamEncReset(bs1);
                }
            }
            else
#endif /* H263_ONLY */
            {   /* ShortVideoHeader Mode */
                status = BitstreamAppendEnc(currVol->stream, bs1);  /* Initialize to 0 */
                /* continue even if status == PV_END_OF_BUF, to get the stats */

                BitstreamEncReset(bs1);
            }
            mbnum++;
            offset += 16;
        } /* End of For ind_x */

        offset += (lx << 4) - width;
        if (currVol->shortVideoHeader)  /* ShortVideoHeader = 1 */
        {

            if (GOB_Header_Interval)  slice_counter++;
        }

    } /* End of For ind_y */

    if (currVol->shortVideoHeader) /* ShortVideoHeader = 1 */
    {

        video->header_bits += BitstreamShortHeaderByteAlignStuffing(currVol->stream); /* Byte Align */
    }
#ifndef H263_ONLY
    else   /* Combined Mode*/
    {
        if (!currVol->ResyncMarkerDisable) /* Resync Markers */
        {

            if (!start_packet_header)
            {
                video->header_bits += BitstreamMpeg4ByteAlignStuffing(bs1);/* Byte Align  */

                status = BitstreamAppendPacket(currVol->stream, bs1);   /* Put Packet to Buffer */
                /* continue even if status == PV_END_OF_BUF, to get the stats */

                BitstreamEncReset(bs1);
            }
        }
        else   /* No Resync Markers */
        {
            video->header_bits += BitstreamMpeg4ByteAlignStuffing(currVol->stream); /* Byte Align */
        }
    }
#endif /* H263_ONLY */

    return status; /* if status == PV_END_OF_BUF, this frame will be pre-skipped */
}

#ifndef NO_SLICE_ENCODE
/* ======================================================================== */
/*  Function : EncodeSliceCombinedMode()                                    */
/*  Date     : 04/19/2002                                                   */
/*  History  :                                                              */
/*  Purpose  : Encode a slice of MPEG4 bitstream in Combined mode and save  */
/*              the current MB to continue next time it is called.          */
/*  In/out   :                                                              */
/*  Return   :  PV_SUCCESS if successful else PV_FAIL                       */
/*  Modified :                                                              */
/*                                                                          */
/* ======================================================================== */
PV_STATUS EncodeSliceCombinedMode(VideoEncData *video)
{
    PV_STATUS status = PV_SUCCESS;
    Vol *currVol = video->vol[video->currLayer];
    Vop *currVop = video->currVop;
    UChar mode = MODE_INTRA;
    UChar *Mode = video->headerInfo.Mode;
    VideoEncParams *encParams = video->encParams;
    Int nTotalMB = currVol->nTotalMB;
    Int width = currVop->width; /* has to be Vop, for multiple of 16 */
    Int lx = currVop->pitch; /* , with padding */
//  rateControl *rc = encParams->rc[video->currLayer];
    UChar *QPMB = video->QPMB;
    Int QP;
    Int ind_x = video->outputMB->mb_x, ind_y = video->outputMB->mb_y;
    Int offset = video->offset;                 /* get current MB location */
    Int mbnum = video->mbnum, slice_counter = video->sliceNo[mbnum]; /* get current MB location */
    Int firstMB = mbnum;
    Int start_packet_header = 0;
    Int num_bits = 0;
    Int packet_size = encParams->ResyncPacketsize - 1;
    Int resync_marker = ((!currVol->shortVideoHeader) && (!currVol->ResyncMarkerDisable));
    BitstreamEncVideo *bs1 = video->bitstream1;
    Int byteCount = 0, byteCount1 = 0, bitCount = 0;
    Int numHeaderBits = 0;
    approxDCT fastDCTfunction;
    Int ncoefblck[6] = {64, 64, 64, 64, 64, 64}; /* for FastCodeMB,  5/18/2001 */
    UChar CBP = 0;
    Short outputMB[6][64];
    Int k;
    PV_STATUS(*CodeMB)(VideoEncData *, approxDCT *, Int, Int[]);
    void (*MBVlcEncode)(VideoEncData*, Int[], void *);
    void (*BlockCodeCoeff)(RunLevelBlock*, BitstreamEncVideo*, Int, Int, UChar);

    video->QP_prev = 31;

#define H263_GOB_CHANGES


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

        numHeaderBits = BitstreamGetPos(bs1);
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
        MBVlcEncode = &MBVlcEncodeCombined_P_VOP;
    else if (currVop->predictionType == I_VOP)
        MBVlcEncode = &MBVlcEncodeCombined_I_VOP;
    else /* B_VOP not implemented yet */
        return PV_FAIL;

    /* determine which VLC table to be used */
#ifndef H263_ONLY
    if (currVol->shortVideoHeader)
        BlockCodeCoeff = &BlockCodeCoeff_ShortHeader;
#ifndef NO_RVLC
    else if (currVol->useReverseVLC)
        BlockCodeCoeff = &BlockCodeCoeff_RVLC;
#endif
    else
        BlockCodeCoeff = &BlockCodeCoeff_Normal;
#else
    BlockCodeCoeff = &BlockCodeCoeff_ShortHeader;
#endif

    /*  (gob_frame_id is the same for different vop types) The reason should be SCD */
    if (currVol->shortVideoHeader && currVop->gobFrameID != currVop->predictionType)
        currVop->gobFrameID = currVop->predictionType;


    if (mbnum != 0)
    {
        if (currVol->shortVideoHeader)
        {
            /* Encode GOB Header */
            bitCount = BitstreamGetPos(bs1);
            byteCount1 = byteCount = bitCount >> 3; /* save the position before GOB header */
            bitCount = bitCount & 0x7;

#ifdef H263_GOB_CHANGES
            video->header_bits -= BitstreamGetPos(bs1); /* Header Bits */
            status = EncodeGOBHeader(video, slice_counter, QP, 1);  //ind_y    /* Encode GOB Header */
            video->header_bits += BitstreamGetPos(bs1); /* Header Bits */
#endif
            goto JUMP_IN_SH;
        }
        else if (currVol->ResyncMarkerDisable)
        {
            goto JUMP_IN_SH;
        }
        else
        {
            start_packet_header = 1;
            goto JUMP_IN;
        }
    }

    for (ind_y = 0; ind_y < currVol->nMBPerCol; ind_y++)    /* Col MB Loop */
    {

        video->outputMB->mb_y = ind_y; /*  5/28/01, do not remove */

        for (ind_x = 0; ind_x < currVol->nMBPerRow; ind_x++)  /* Row MB Loop */
        {

            video->outputMB->mb_x = ind_x; /*  5/28/01, do not remove */
            video->mbnum = mbnum;
            video->sliceNo[mbnum] = slice_counter;      /* Update MB slice number */
JUMP_IN_SH:
            /****************************************************************************************/
            /* MB Prediction:Put into MC macroblock, substract from currVop, put in predMB */
            /****************************************************************************************/
            getMotionCompensatedMB(video, ind_x, ind_y, offset);

JUMP_IN:
            QP = QPMB[mbnum];   /* always read new QP */
#ifndef H263_ONLY
            if (start_packet_header)
            {
                slice_counter++;                        /* Increment slice counter */
                video->sliceNo[mbnum] = slice_counter;  /* Update MB slice number*/
                video->QP_prev = currVop->quantizer;                        /* store QP */
                num_bits = BitstreamGetPos(bs1);
                status = EncodeVideoPacketHeader(video, mbnum, video->QP_prev, 1);
                numHeaderBits = BitstreamGetPos(bs1) - num_bits;
                video->header_bits += numHeaderBits; /* Header Bits */
                start_packet_header = 0;
                video->usePrevQP = 0;
            }
            else  /* don't encode the first MB in packet again */
#endif /* H263_ONLY */
            {
                /***********************************************/
                /* Code_MB:  DCT, Q, Q^(-1), IDCT, Motion Comp */
                /***********************************************/
                status = (*CodeMB)(video, &fastDCTfunction, (offset << 5) + QP, ncoefblck);
            }

            /************************************/
            /* MB VLC Encode: VLC Encode MB     */
            /************************************/

            /* save the state before VLC encoding */
            if (resync_marker)
            {
                bitCount = BitstreamGetPos(bs1);
                byteCount = bitCount >> 3; /* save the state before encoding */
                bitCount = bitCount & 0x7;
                mode = Mode[mbnum];
                CBP = video->headerInfo.CBP[mbnum];
                for (k = 0; k < 6; k++)
                {
                    M4VENC_MEMCPY(outputMB[k], video->outputMB->block[k], sizeof(Short) << 6);
                }
            }
            /*************************************/

            (*MBVlcEncode)(video, ncoefblck, (void*)BlockCodeCoeff);

            /*************************************************************/
            /* Assemble Packets:  Assemble the MB VLC codes into Packets */
            /*************************************************************/

            /* Assemble_Packet(video) */
#ifndef H263_ONLY
            if (!currVol->shortVideoHeader)
            {
                if (!currVol->ResyncMarkerDisable)
                {
                    /* Not in ShortVideoHeader mode and RESYNC MARKER MODE */

                    num_bits = BitstreamGetPos(bs1) ;//- numHeaderBits; // include header

                    /* Assemble packet and return when size reached */
                    if (num_bits > packet_size && mbnum != firstMB)
                    {

                        BitstreamRepos(bs1, byteCount, bitCount); /* rewind one MB */

                        video->header_bits += BitstreamMpeg4ByteAlignStuffing(bs1); /* Byte align Packet */

                        status = BitstreamAppendPacketNoOffset(currVol->stream, bs1); /* Put Packet to Buffer */

                        if (status == PV_END_OF_BUF)
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
                }
                else  /* NO RESYNC MARKER , return when buffer is full*/
                {

                    if (mbnum < nTotalMB - 1 && currVol->stream->byteCount + bs1->byteCount + 1 >= currVol->stream->bufferSize)
                    {
                        /* find maximum bytes to fit in the buffer */
                        byteCount = currVol->stream->bufferSize - currVol->stream->byteCount - 1;

                        num_bits = BitstreamGetPos(bs1) - (byteCount << 3);
                        BitstreamRepos(bs1, byteCount, 0);
                        status = BitstreamAppendPacketNoOffset(currVol->stream, bs1);
                        BitstreamFlushBits(bs1, num_bits);

                        /* move on to next MB */
                        mbnum++ ;
                        offset += 16;
                        video->outputMB->mb_x++;
                        if (video->outputMB->mb_x >= currVol->nMBPerRow)
                        {
                            video->outputMB->mb_x = 0;
                            video->outputMB->mb_y++;
                            offset += (lx << 4) - width;
                        }
                        video->mbnum = mbnum;
                        video->offset = offset;
                        video->sliceNo[mbnum] = slice_counter;
                        return status;
                    }
                }
            }
#endif /* H263_ONLY */
            offset += 16;
            mbnum++; /* has to increment before SCD, to preserve Mode[mbnum] */

        } /* End of For ind_x */

        offset += (lx << 4) - width;

        if (currVol->shortVideoHeader)  /* ShortVideoHeader = 1 */
        {
#ifdef H263_GOB_CHANGES
            slice_counter++;
            video->header_bits += BitstreamShortHeaderByteAlignStuffing(bs1);
#endif
            //video->header_bits+=BitstreamShortHeaderByteAlignStuffing(bs1);

            /* check if time to packetize */
            if (currVol->stream->byteCount + bs1->byteCount > currVol->stream->bufferSize)
            {
                if (byteCount == byteCount1) /* a single GOB bigger than packet size */
                {
                    status = BitstreamAppendPacketNoOffset(currVol->stream, bs1);
                    status = PV_END_OF_BUF;
                    video->end_of_buf = 1;
                    start_packet_header = 1;
                }
                else    /* for short_header scooch back to previous GOB */
                {
                    num_bits = ((bs1->byteCount - byteCount) << 3);
                    //num_bits = ((bs1->byteCount<<3) + bs1->bitCount) - ((byteCount<<3) + bitCount);
                    BitstreamRepos(bs1, byteCount, 0);
                    //BitstreamRepos(bs1,byteCount,bitCount);
//                  k = currVol->stream->byteCount; /* save state before appending */
                    status = BitstreamAppendPacketNoOffset(currVol->stream, bs1);
                    BitstreamFlushBits(bs1, num_bits);
//                  if(mbnum == nTotalMB || k + bs1->byteCount >= currVol->stream->bufferSize){
                    /* last GOB or current one with larger size will be returned next run */
//                      status = PV_END_OF_BUF;
//                      video->end_of_buf = 1;
//                  }
                    start_packet_header = 1;
                    if (mbnum == nTotalMB) /* there's one more GOB to packetize for the next round */
                    {
                        status = PV_END_OF_BUF;
                        video->end_of_buf = 1;
                    }
                }

                if (mbnum < nTotalMB) /* return here */
                {
                    /* move on to next MB */
                    video->outputMB->mb_x = 0;
                    video->outputMB->mb_y++;
                    video->mbnum = mbnum;
                    video->offset = offset;
                    video->sliceNo[mbnum] = slice_counter;
                    return status;
                }
            }
            else if (mbnum < nTotalMB) /* do not write GOB header if end of vop */
            {
                bitCount = BitstreamGetPos(bs1);
                byteCount = bitCount >> 3;  /* save the position before GOB header */
                bitCount = bitCount & 0x7;
#ifdef H263_GOB_CHANGES
                video->header_bits -= BitstreamGetPos(bs1); /* Header Bits */
                status = EncodeGOBHeader(video, slice_counter, QP, 1);         /* Encode GOB Header */
                video->header_bits += BitstreamGetPos(bs1); /* Header Bits */
#endif
            }
        }

    } /* End of For ind_y */
#ifndef H263_ONLY
    if (!currVol->shortVideoHeader) /* Combined Mode*/
    {
        if (!currVol->ResyncMarkerDisable) /* Resync Markers */
        {

            if (!start_packet_header)
            {

                video->header_bits += BitstreamMpeg4ByteAlignStuffing(bs1);/* Byte Align  */

                status = BitstreamAppendPacketNoOffset(currVol->stream, bs1);   /* Put Packet to Buffer */
                if (status == PV_END_OF_BUF)
                {
                    video->end_of_buf = 1;
                }
                else
                {
                    BitstreamEncReset(bs1);
                }
            }
        }
        else   /* No Resync Markers */
        {
            video->header_bits += BitstreamMpeg4ByteAlignStuffing(bs1); /* Byte Align */
            status = BitstreamAppendPacketNoOffset(currVol->stream, bs1); /* Initialize to 0 */
            if (status == PV_END_OF_BUF)
            {
                video->end_of_buf = 1;
            }
            else
            {
                BitstreamEncReset(bs1);
            }
        }
    }
    else
#endif /* H263_ONLY */
    {
        if (!start_packet_header) /* not yet packetized */
        {
            video->header_bits += BitstreamShortHeaderByteAlignStuffing(bs1);
            status = BitstreamAppendPacketNoOffset(currVol->stream, bs1);
            if (status == PV_END_OF_BUF)
            {
                video->end_of_buf = 1;
            }
            else
            {
                BitstreamEncReset(bs1);
                video->end_of_buf = 0;
            }
        }
    }

    video->mbnum = mbnum;
    if (mbnum < nTotalMB)
        video->sliceNo[mbnum] = slice_counter;
    video->offset = offset;

    return status;
}
#endif  /* NO_SLICE_ENCODE */

/* ======================================================================== */
/*  Function : EncodeGOBHeader()                                            */
/*  Date     : 09/05/2000                                                   */
/*  History  :                                                              */
/*  Purpose  : Encode a frame of MPEG4 bitstream in Combined mode.          */
/*  In/out   :                                                              */
/*  Return   :  PV_SUCCESS if successful else PV_FAIL                       */
/*  Modified :                                                              */
/*                                                                          */
/* ======================================================================== */

PV_STATUS EncodeGOBHeader(VideoEncData *video, Int GOB_number, Int quant_scale, Int bs1stream)
{
    PV_STATUS status = PV_SUCCESS;
    BitstreamEncVideo *stream = (bs1stream ? video->bitstream1 : video->vol[video->currLayer]->stream);

    status = BitstreamPutGT16Bits(stream, 17, GOB_RESYNC_MARKER); /* gob_resync_marker */
    status = BitstreamPutBits(stream, 5, GOB_number);           /* Current gob_number */
    status = BitstreamPutBits(stream, 2, video->currVop->gobFrameID); /* gob_frame_id */
    status = BitstreamPutBits(stream, 5, quant_scale);              /* quant_scale */
    return status;
}


