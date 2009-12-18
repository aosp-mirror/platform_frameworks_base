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


/***********************************************************CommentBegin******
*       04/13/2000 : initial modification to the new PV-Decoder
*                            Lib format.
*       04/16/2001 : Removed PV_END_OF_BUFFER case, error resilience
***********************************************************CommentEnd********/
PV_STATUS PV_ReadVideoPacketHeader(VideoDecData *video, int *next_MB)
{
    PV_STATUS status;
    Vol *currVol = video->vol[video->currLayer];
    Vop *currVop = video->currVop;
    BitstreamDecVideo *stream = video->bitstream;
    int fcode_forward;
    int resync_marker_length;
    int nbits = video->nBitsForMBID;
    uint32 tmpvar32;
    uint tmpvar16;
    int16 quantizer;
    int nTotalMB = video->nTotalMB;

    fcode_forward = currVop->fcodeForward;
    resync_marker_length = 17;

    if (currVop->predictionType != I_VOP) resync_marker_length = 16 + fcode_forward;

    status = PV_BitstreamShowBitsByteAlign(stream, resync_marker_length, &tmpvar32);
    /*  if (status != PV_SUCCESS && status != PV_END_OF_BUFFER) return status; */
    if (tmpvar32 == RESYNC_MARKER)
    {
//      DecNextStartCode(stream);
        PV_BitstreamByteAlign(stream);
        BitstreamReadBits32(stream, resync_marker_length);

        *next_MB = (int) BitstreamReadBits16(stream, nbits);
//      if (*next_MB <= video->mbnum)   /*  needs more investigation */
//          *next_MB = video->mbnum+1;

        if (*next_MB >= nTotalMB)  /* fix  04/05/01 */
        {
            *next_MB = video->mbnum + 1;
            if (*next_MB >= nTotalMB)    /* this check is needed  */
                *next_MB = nTotalMB - 1;
        }
        quantizer = (int16) BitstreamReadBits16(stream, currVol->quantPrecision);
        if (quantizer == 0) return PV_FAIL;        /*  04/03/01 */

        currVop->quantizer = quantizer;

        /* if we have HEC, read some redundant VOP header information */
        /* this part needs improvement  04/05/01 */
        if (BitstreamRead1Bits(stream))
        {
            int time_base = -1;

            /* modulo_time_base (? bits) */
            do
            {
                time_base++;
                tmpvar16 = BitstreamRead1Bits(stream);
            }
            while (tmpvar16 == 1);

            /* marker bit */
            BitstreamRead1Bits(stream);

            /* vop_time_increment (1-15 bits) */
            BitstreamReadBits16(stream, currVol->nbitsTimeIncRes);

            /* marker bit */
            BitstreamRead1Bits(stream);

            /* vop_prediction_type (2 bits) */
            BitstreamReadBits16(stream, 2);

            /* Added intra_dc_vlc_thr reading  */
            BitstreamReadBits16(stream, 3);

            /* fcodes */
            if (currVop->predictionType != I_VOP)
            {
                fcode_forward = (int) BitstreamReadBits16(stream, 3);

                if (currVop->predictionType == B_VOP)
                {
                    BitstreamReadBits16(stream, 3);
                }
            }

        }
    }
    else
    {
        PV_BitstreamByteAlign(stream);  /*  */
        status = BitstreamCheckEndBuffer(stream);   /* return end_of_VOP  03/30/01 */
        if (status != PV_SUCCESS)
        {
            return status;
        }
        status = BitstreamShowBits32HC(stream, &tmpvar32);   /*  07/07/01 */
        /* -16 = 0xFFFFFFF0*/
        if ((tmpvar32 & 0xFFFFFFF0) == VISUAL_OBJECT_SEQUENCE_START_CODE) /* start code mask 00 00 01 */

        {
            /* we don't have to check for legl stuffing here.   05/08/2000 */
            return PV_END_OF_VOP;
        }
        else
        {
            return PV_FAIL;
        }
    }

    return PV_SUCCESS;
}



/***********************************************************CommentBegin******
*       3/10/00  : initial modification to the
*                new PV-Decoder Lib format.
*       04/17/01 : remove PV_END_OF_BUFFER, error checking
***********************************************************CommentEnd********/
PV_STATUS PV_GobHeader(VideoDecData *video)
{
    uint32 tmpvar;
    Vop *currVop = video->currVop;
    BitstreamDecVideo *stream = video->bitstream;
    int quantPrecision = 5;
    int16 quantizer;

    BitstreamShowBits32(stream, GOB_RESYNC_MARKER_LENGTH, &tmpvar);

    if (tmpvar != GOB_RESYNC_MARKER)
    {
        PV_BitstreamShowBitsByteAlign(stream, GOB_RESYNC_MARKER_LENGTH, &tmpvar);

        if (tmpvar != GOB_RESYNC_MARKER)
        {
            return PV_FAIL;
        }
        else
            PV_BitstreamByteAlign(stream);  /* if bytealigned GOBHEADER search is performed */
        /* then no more noforcestuffing  */
    }

    /* we've got a GOB header info here */
    BitstreamShowBits32(stream, GOB_RESYNC_MARKER_LENGTH + 5, &tmpvar);
    tmpvar &= 0x1F;

    if (tmpvar == 0)
    {
        return PV_END_OF_VOP;
    }

    if (tmpvar == 31)
    {
        PV_BitstreamFlushBits(stream, GOB_RESYNC_MARKER_LENGTH + 5);
        BitstreamByteAlignNoForceStuffing(stream);
        return PV_END_OF_VOP;
    }

    PV_BitstreamFlushBits(stream, GOB_RESYNC_MARKER_LENGTH + 5);
    currVop->gobNumber = (int) tmpvar;
    if (currVop->gobNumber >= video->nGOBinVop) return PV_FAIL;
    currVop->gobFrameID = (int) BitstreamReadBits16(stream, 2);
    quantizer = (int16) BitstreamReadBits16(stream, quantPrecision);
    if (quantizer == 0)   return PV_FAIL;         /*  04/03/01 */

    currVop->quantizer = quantizer;
    return PV_SUCCESS;
}
#ifdef PV_ANNEX_IJKT_SUPPORT
PV_STATUS PV_H263SliceHeader(VideoDecData *video, int *next_MB)
{
    PV_STATUS status;
    uint32 tmpvar;
    Vop *currVop = video->currVop;
    BitstreamDecVideo *stream = video->bitstream;
    int nTotalMB = video->nTotalMB;
    int16 quantizer;

    PV_BitstreamShowBitsByteAlignNoForceStuffing(stream, 17, &tmpvar);
    if (tmpvar == RESYNC_MARKER)
    {
        BitstreamByteAlignNoForceStuffing(stream);
        PV_BitstreamFlushBits(stream, 17);
        if (!BitstreamRead1Bits(stream))
        {
            return PV_FAIL;
        }
        *next_MB = BitstreamReadBits16(stream, video->nBitsForMBID);
        if (*next_MB >= nTotalMB)  /* fix  04/05/01 */
        {
            *next_MB = video->mbnum + 1;
            if (*next_MB >= nTotalMB)    /* this check is needed  */
                *next_MB = nTotalMB - 1;
        }
        /* we will not parse sebp2 for large pictures 3GPP */
        quantizer = (int16) BitstreamReadBits16(stream, 5);
        if (quantizer == 0) return PV_FAIL;

        currVop->quantizer = quantizer;
        if (!BitstreamRead1Bits(stream))
        {
            return PV_FAIL;
        }
        currVop->gobFrameID = (int) BitstreamReadBits16(stream, 2);
    }
    else
    {
        status = BitstreamCheckEndBuffer(stream);   /* return end_of_VOP  03/30/01 */
        if (status != PV_SUCCESS)
        {
            return status;
        }
        PV_BitstreamShowBitsByteAlign(stream, SHORT_VIDEO_START_MARKER_LENGTH, &tmpvar);

        if (tmpvar == SHORT_VIDEO_START_MARKER)
        {
            /* we don't have to check for legal stuffing here.   05/08/2000 */
            return PV_END_OF_VOP;
        }
        else
        {
            return PV_FAIL;
        }
    }
    return PV_SUCCESS;
}
#endif

