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
#include "mp4enc_lib.h"
#include "bitstream_io.h"
#include "m4venc_oscl.h"

PV_STATUS EncodeShortHeader(BitstreamEncVideo *stream, Vop *currVop);
PV_STATUS EncodeVOPHeader(BitstreamEncVideo *stream, Vol *currVol, Vop *currVop);
PV_STATUS EncodeGOVHeader(BitstreamEncVideo *stream, UInt seconds);

PV_STATUS EncodeVop_BXRC(VideoEncData *video);
PV_STATUS EncodeVop_NoME(VideoEncData *video);

/* ======================================================================== */
/*  Function : DecodeVop()                                                  */
/*  Date     : 08/23/2000                                                   */
/*  Purpose  : Encode VOP Header                                            */
/*  In/out   :                                                              */
/*  Return   :                                                              */
/*  Modified :                                                              */
/* ======================================================================== */
PV_STATUS EncodeVop(VideoEncData *video)
{

    PV_STATUS status;
    Int currLayer = video->currLayer;
    Vol *currVol = video->vol[currLayer];
    Vop *currVop = video->currVop;
//  BitstreamEncVideo *stream=video->bitstream1;
    UChar *Mode = video->headerInfo.Mode;
    rateControl **rc = video->rc;
//  UInt time=0;

    /*******************/
    /* Initialize mode */
    /*******************/

    switch (currVop->predictionType)
    {
        case I_VOP:
            M4VENC_MEMSET(Mode, MODE_INTRA, sizeof(UChar)*currVol->nTotalMB);
            break;
        case P_VOP:
            M4VENC_MEMSET(Mode, MODE_INTER, sizeof(UChar)*currVol->nTotalMB);
            break;
        case B_VOP:
            /*M4VENC_MEMSET(Mode, MODE_INTER_B,sizeof(UChar)*nTotalMB);*/
            return PV_FAIL;
        default:
            return PV_FAIL;
    }

    /*********************/
    /* Motion Estimation */
    /* compute MVs, scene change detection, edge padding, */
    /* intra refresh, compute block activity */
    /*********************/
    MotionEstimation(video);    /* do ME for the whole frame */

    /***************************/
    /* rate Control (assign QP) */
    /* 4/11/01, clean-up, and put into a separate function */
    /***************************/
    status = RC_VopQPSetting(video, rc);
    if (status == PV_FAIL)
        return PV_FAIL;

    /**********************/
    /*     Encode VOP     */
    /**********************/
    if (video->slice_coding) /* end here */
    {
        /* initialize state variable for slice-based APIs */
        video->totalSAD = 0;
        video->mbnum = 0;
        video->sliceNo[0] = 0;
        video->numIntra = 0;
        video->offset = 0;
        video->end_of_buf = 0;
        video->hp_guess = -1;
        return status;
    }

    status = EncodeVop_NoME(video);

    /******************************/
    /* rate control (update stat) */
    /* 6/2/01 separate function */
    /******************************/

    RC_VopUpdateStat(video, rc[currLayer]);

    return status;
}

/* ======================================================================== */
/*  Function : EncodeVop_NoME()                                             */
/*  Date     : 08/28/2001                                                   */
/*  History  :                                                              */
/*  Purpose  : EncodeVop without motion est.                                */
/*  In/out   :                                                              */
/*  Return   :                                                              */
/*  Modified :                                                              */
/*                                                                          */
/* ======================================================================== */

PV_STATUS EncodeVop_NoME(VideoEncData *video)
{
    Vop *currVop = video->currVop;
    Vol *currVol = video->vol[video->currLayer];
    BitstreamEncVideo *stream = video->bitstream1;
    Int time = 0;   /* follows EncodeVop value */
    PV_STATUS status = PV_SUCCESS;

    if (currVol->shortVideoHeader) /* Short Video Header = 1 */
    {

        status = EncodeShortHeader(stream, currVop); /* Encode Short Header */

        video->header_bits = BitstreamGetPos(stream); /* Header Bits */

        status = EncodeFrameCombinedMode(video);

    }
#ifndef H263_ONLY
    else    /* Short Video Header = 0 */
    {

        if (currVol->GOVStart && currVop->predictionType == I_VOP)
            status = EncodeGOVHeader(stream, time); /* Encode GOV Header */

        status = EncodeVOPHeader(stream, currVol, currVop);  /* Encode VOP Header */

        video->header_bits = BitstreamGetPos(stream); /* Header Bits */

        if (currVop->vopCoded)
        {
            if (!currVol->scalability)
            {
                if (currVol->dataPartitioning)
                {
                    status = EncodeFrameDataPartMode(video); /* Encode Data Partitioning Mode VOP */
                }
                else
                {
                    status = EncodeFrameCombinedMode(video); /* Encode Combined Mode VOP */
                }
            }
            else
                status = EncodeFrameCombinedMode(video); /* Encode Combined Mode VOP */
        }
        else  /* Vop Not coded */
        {

            return status;
        }
    }
#endif /* H263_ONLY */
    return status;

}

#ifndef NO_SLICE_ENCODE
/* ======================================================================== */
/*  Function : EncodeSlice()                                                */
/*  Date     : 04/19/2002                                                   */
/*  History  :                                                              */
/*  Purpose  : Encode one slice.                                            */
/*  In/out   :                                                              */
/*  Return   :                                                              */
/*  Modified :                                                              */
/*                                                                          */
/* ======================================================================== */

PV_STATUS EncodeSlice(VideoEncData *video)
{
    Vop *currVop = video->currVop;
    Int currLayer = video->currLayer;
    Vol *currVol = video->vol[currLayer];
    BitstreamEncVideo *stream = video->bitstream1; /* different from frame-based */
    Int time = 0;   /* follows EncodeVop value */
    PV_STATUS status = PV_SUCCESS;
    rateControl **rc = video->rc;

    if (currVol->shortVideoHeader) /* Short Video Header = 1 */
    {

        if (video->mbnum == 0)
        {
            status = EncodeShortHeader(stream, currVop); /* Encode Short Header */

            video->header_bits = BitstreamGetPos(stream); /* Header Bits */
        }

        status = EncodeSliceCombinedMode(video);

    }
#ifndef H263_ONLY
    else    /* Short Video Header = 0 */
    {

        if (video->mbnum == 0)
        {
            if (currVol->GOVStart)
                status = EncodeGOVHeader(stream, time); /* Encode GOV Header */

            status = EncodeVOPHeader(stream, currVol, currVop);  /* Encode VOP Header */

            video->header_bits = BitstreamGetPos(stream); /* Header Bits */
        }

        if (currVop->vopCoded)
        {
            if (!currVol->scalability)
            {
                if (currVol->dataPartitioning)
                {
                    status = EncodeSliceDataPartMode(video); /* Encode Data Partitioning Mode VOP */
                }
                else
                {
                    status = EncodeSliceCombinedMode(video); /* Encode Combined Mode VOP */
                }
            }
            else
                status = EncodeSliceCombinedMode(video); /* Encode Combined Mode VOP */
        }
        else  /* Vop Not coded */
        {

            return status;
        }
    }
#endif /* H263_ONLY */
    if (video->mbnum >= currVol->nTotalMB && status != PV_END_OF_BUF) /* end of Vop */
    {
        /******************************/
        /* rate control (update stat) */
        /* 6/2/01 separate function */
        /******************************/

        status = RC_VopUpdateStat(video, rc[currLayer]);
    }

    return status;

}
#endif /* NO_SLICE_ENCODE */

#ifndef H263_ONLY
/* ======================================================================== */
/*  Function : EncodeGOVHeader()                                            */
/*  Date     : 08/23/2000                                                   */
/*  Purpose  : Encode GOV Header                                            */
/*  In/out   :                                                              */
/*  Return   :                                                              */
/*  Modified :                                                              */
/* ======================================================================== */
PV_STATUS EncodeGOVHeader(BitstreamEncVideo *stream, UInt seconds)
{
    PV_STATUS status;
//  int temp;
    UInt tmpvar;

    /********************************/
    /* Group_of_VideoObjectPlane()  */
    /********************************/

    status = BitstreamPutGT16Bits(stream, 32, GROUP_START_CODE);
    /* time_code */
    tmpvar = seconds / 3600;
    status = BitstreamPutBits(stream, 5, tmpvar); /* Hours*/

    tmpvar = (seconds - tmpvar * 3600) / 60;
    status = BitstreamPutBits(stream, 6, tmpvar); /* Minutes*/

    status = BitstreamPut1Bits(stream, 1); /* Marker*/

    tmpvar = seconds % 60;
    status = BitstreamPutBits(stream, 6, tmpvar); /* Seconds*/

    status = BitstreamPut1Bits(stream, 1); /* closed_gov */
    status = BitstreamPut1Bits(stream, 0); /* broken_link */
    /*temp =*/
    BitstreamMpeg4ByteAlignStuffing(stream); /* Byte align GOV Header */

    return status;
}

#ifdef ALLOW_VOP_NOT_CODED

PV_STATUS EncodeVopNotCoded(VideoEncData *video, UChar *bstream, Int *size, ULong modTime)
{
    PV_STATUS status;
    Vol *currVol = video->vol[0];
    Vop *currVop = video->currVop;
    BitstreamEncVideo *stream = currVol->stream;
    UInt frameTick;
    Int timeInc;

    stream->bitstreamBuffer = bstream;
    stream->bufferSize = *size;
    BitstreamEncReset(stream);

    status = BitstreamPutGT16Bits(stream, 32, VOP_START_CODE); /*Start Code for VOP*/
    status = BitstreamPutBits(stream, 2, P_VOP);/* VOP Coding Type*/

    frameTick = (Int)(((double)(modTime - video->modTimeRef) * currVol->timeIncrementResolution + 500) / 1000);
    timeInc = frameTick - video->refTick[0];
    while (timeInc >= currVol->timeIncrementResolution)
    {
        timeInc -= currVol->timeIncrementResolution;
        status = BitstreamPut1Bits(stream, 1);
        /* do not update refTick and modTimeRef yet, do it after encoding!! */
    }
    status = BitstreamPut1Bits(stream, 0);
    status = BitstreamPut1Bits(stream, 1); /* marker bit */
    status = BitstreamPutBits(stream, currVol->nbitsTimeIncRes, timeInc); /* vop_time_increment */
    status = BitstreamPut1Bits(stream, 1); /* marker bit */
    status = BitstreamPut1Bits(stream, 0); /* vop_coded bit */
    BitstreamMpeg4ByteAlignStuffing(stream);

    return status;
}
#endif

/* ======================================================================== */
/*  Function : EncodeVOPHeader()                                            */
/*  Date     : 08/23/2000                                                   */
/*  Purpose  : Encode VOP Header                                            */
/*  In/out   :                                                              */
/*  Return   :                                                              */
/*  Modified :                                                              */
/* ======================================================================== */

PV_STATUS EncodeVOPHeader(BitstreamEncVideo *stream, Vol *currVol, Vop *currVop)
{
    PV_STATUS status;
    //int temp;

    int MTB = currVol->moduloTimeBase;
    /************************/
    /* VideoObjectPlane()   */
    /************************/

    status = BitstreamPutGT16Bits(stream, 32, VOP_START_CODE); /*Start Code for VOP*/
    status = BitstreamPutBits(stream, 2, currVop->predictionType);/* VOP Coding Type*/

    currVol->prevModuloTimeBase = currVol->moduloTimeBase;

    while (MTB)
    {
        status = BitstreamPut1Bits(stream, 1);
        MTB--;
    }
    status = BitstreamPut1Bits(stream, 0);

    status = BitstreamPut1Bits(stream, 1); /* marker bit */
    status = BitstreamPutBits(stream, currVol->nbitsTimeIncRes, currVop->timeInc); /* vop_time_increment */
    status = BitstreamPut1Bits(stream, 1); /* marker bit */
    status = BitstreamPut1Bits(stream, currVop->vopCoded); /* vop_coded bit */
    if (currVop->vopCoded == 0)
    {
        /*temp =*/
        BitstreamMpeg4ByteAlignStuffing(stream); /* Byte align VOP Header */
        return status;
    }
    if (currVop->predictionType == P_VOP)
        status = BitstreamPut1Bits(stream, currVop->roundingType); /* vop_rounding_type */

    status = BitstreamPutBits(stream, 3, currVop->intraDCVlcThr); /* intra_dc_vlc_thr */
    status = BitstreamPutBits(stream, 5, currVop->quantizer);   /* vop_quant */

    if (currVop->predictionType != I_VOP)
        status = BitstreamPutBits(stream, 3, currVop->fcodeForward); /* vop_fcode_forward */
    if (currVop->predictionType == B_VOP)
        status = BitstreamPutBits(stream, 3, currVop->fcodeBackward);/* vop_fcode_backward */

    if (currVol->scalability)
        /* enhancement_type = 0 */
        status = BitstreamPutBits(stream, 2, currVop->refSelectCode); /* ref_select_code */

    return status;
}
#endif /* H263_ONLY */
/* ======================================================================== */
/*  Function : EncodeShortHeader()                                          */
/*  Date     : 08/23/2000                                                   */
/*  Purpose  : Encode VOP Header                                            */
/*  In/out   :                                                              */
/*  Return   :                                                              */
/*  Modified :                                                              */
/* ======================================================================== */

PV_STATUS EncodeShortHeader(BitstreamEncVideo *stream, Vop *currVop)
{

    PV_STATUS status;

    status = BitstreamPutGT16Bits(stream, 22, SHORT_VIDEO_START_MARKER); /* Short_video_start_marker */
    status = BitstreamPutBits(stream, 8, currVop->temporalRef); /* temporal_reference */
    status = BitstreamPut1Bits(stream, 1); /* marker bit */
    status = BitstreamPut1Bits(stream, 0); /* zero bit */
    status = BitstreamPut1Bits(stream, 0); /* split_screen_indicator=0*/
    status = BitstreamPut1Bits(stream, 0); /* document_camera_indicator=0*/
    status = BitstreamPut1Bits(stream, 0); /* full_picture_freeze_release=0*/

    switch (currVop->width)
    {
        case 128:
            if (currVop->height == 96)
                status = BitstreamPutBits(stream, 3, 1); /* source_format = 1 */
            else
            {
                status = PV_FAIL;
                return status;
            }
            break;

        case 176:
            if (currVop->height == 144)
                status = BitstreamPutBits(stream, 3, 2); /* source_format = 2 */
            else
            {
                status = PV_FAIL;
                return status;
            }
            break;

        case 352:
            if (currVop->height == 288)
                status = BitstreamPutBits(stream, 3, 3); /* source_format = 3 */
            else
            {
                status = PV_FAIL;
                return status;
            }
            break;

        case 704:
            if (currVop->height == 576)
                status = BitstreamPutBits(stream, 3, 4); /* source_format = 4 */
            else
            {
                status = PV_FAIL;
                return status;
            }
            break;

        case 1408:
            if (currVop->height == 1152)
                status = BitstreamPutBits(stream, 3, 5); /* source_format = 5 */
            else
            {
                status = PV_FAIL;
                return status;
            }
            break;

        default:
            status = PV_FAIL;
            return status;
    }


    status = BitstreamPut1Bits(stream, currVop->predictionType); /* picture_coding type */
    status = BitstreamPutBits(stream, 4, 0); /* four_reserved_zero_bits */
    status = BitstreamPutBits(stream, 5, currVop->quantizer); /* vop_quant*/
    status = BitstreamPut1Bits(stream, 0); /* zero_bit*/
    status = BitstreamPut1Bits(stream, 0); /* pei=0 */

    return status;
}

#ifndef H263_ONLY
/* ======================================================================== */
/*  Function : EncodeVideoPacketHeader()                                    */
/*  Date     : 09/05/2000                                                   */
/*  History  :                                                              */
/*  Purpose  : Encode a frame of MPEG4 bitstream in Combined mode.          */
/*  In/out   :                                                              */
/*  Return   :                                                              */
/*  Modified : 04/25/2002                               */
/*             Add bitstream structure as input argument                    */
/*                                                                          */
/* ======================================================================== */
PV_STATUS EncodeVideoPacketHeader(VideoEncData *video, int MB_number,
                                  int quant_scale, Int insert)
{
//  PV_STATUS status=PV_SUCCESS;
    int fcode;
    Vop *currVop = video->currVop;
    Vol *currVol = video->vol[video->currLayer];
    BitstreamEncVideo *bs, tmp;
    UChar buffer[30];

    if (insert) /* insert packet header to the beginning of bs1 */
    {
        tmp.bitstreamBuffer = buffer; /* use temporary buffer */
        tmp.bufferSize = 30;
        BitstreamEncReset(&tmp);
        bs = &tmp;
    }
    else
        bs = video->bitstream1;


    if (currVop->predictionType == I_VOP)
        BitstreamPutGT16Bits(bs, 17, 1);    /* resync_marker I_VOP */
    else if (currVop->predictionType == P_VOP)
    {
        fcode = currVop->fcodeForward;
        BitstreamPutGT16Bits(bs, 16 + fcode, 1);    /* resync_marker P_VOP */

    }
    else
    {
        fcode = currVop->fcodeForward;
        if (currVop->fcodeBackward > fcode)
            fcode = currVop->fcodeBackward;
        BitstreamPutGT16Bits(bs, 16 + fcode, 1);    /* resync_marker B_VOP */
    }

    BitstreamPutBits(bs, currVol->nBitsForMBID, MB_number); /* resync_marker */
    BitstreamPutBits(bs, 5, quant_scale); /* quant_scale */
    BitstreamPut1Bits(bs, 0); /* header_extension_code = 0 */

    if (0) /* header_extension_code = 1 */
    {
        /* NEED modulo_time_base code here ... default 0x01  belo*/
        /*status =*/
        BitstreamPut1Bits(bs, 1);
        /*status = */
        BitstreamPut1Bits(bs, 0);

        /*status = */
        BitstreamPut1Bits(bs, 1); /* marker bit */
        /*status = */
        BitstreamPutBits(bs, currVol->nbitsTimeIncRes, currVop->timeInc); /* vop_time_increment */
        /*status = */
        BitstreamPut1Bits(bs, 1); /* marker bit */

        /*status = */
        BitstreamPutBits(bs, 2, currVop->predictionType);/* VOP Coding Type*/

        /*status = */
        BitstreamPutBits(bs, 3, currVop->intraDCVlcThr); /* intra_dc_vlc_thr */

        if (currVop->predictionType != I_VOP)
            /*status = */ BitstreamPutBits(bs, 3, currVop->fcodeForward);
        if (currVop->predictionType == B_VOP)
            /*status = */ BitstreamPutBits(bs, 3, currVop->fcodeBackward);
    }
#ifndef NO_SLICE_ENCODE
    if (insert)
        BitstreamPrependPacket(video->bitstream1, bs);
#endif
    return PV_SUCCESS;
}

#endif /* H263_ONLY */



