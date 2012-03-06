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
#include "bitstream.h"
#include "vlc_decode.h"
#include "zigzag.h"

#define OSCL_DISABLE_WARNING_CONV_POSSIBLE_LOSS_OF_DATA

#ifdef PV_SUPPORT_MAIN_PROFILE
/* INTRA */
const static int mpeg_iqmat_def[NCOEFF_BLOCK] =
{
    8, 17, 18, 19, 21, 23, 25, 27,
    17, 18, 19, 21, 23, 25, 27, 28,
    20, 21, 22, 23, 24, 26, 28, 30,
    21, 22, 23, 24, 26, 28, 30, 32,
    22, 23, 24, 26, 28, 30, 32, 35,
    23, 24, 26, 28, 30, 32, 35, 38,
    25, 26, 28, 30, 32, 35, 38, 41,
    27, 28, 30, 32, 35, 38, 41, 45
};

/* INTER */
const static int mpeg_nqmat_def[64]  =
{
    16, 17, 18, 19, 20, 21, 22, 23,
    17, 18, 19, 20, 21, 22, 23, 24,
    18, 19, 20, 21, 22, 23, 24, 25,
    19, 20, 21, 22, 23, 24, 26, 27,
    20, 21, 22, 23, 25, 26, 27, 28,
    21, 22, 23, 24, 26, 27, 28, 30,
    22, 23, 24, 26, 27, 28, 30, 31,
    23, 24, 25, 27, 28, 30, 31, 33
};
#endif

/* ======================================================================== */
/*  Function : CalcNumBits()                                                */
/*  Purpose  :                                                              */
/*  In/out   :                                                              */
/*  Return   : Calculate the minimum number of bits required to             */
/*              represent x.                                                */
/*  Note     : This is an equivalent implementation of                      */
/*                      (long)ceil(log((double)x)/log(2.0))                 */
/*  Modified :                                                              */
/* ======================================================================== */
int CalcNumBits(uint x)
{
    int i = 1;
    while (x >>= 1) i++;
    return i;
}



/***********************************************************CommentBegin******
*
* -- DecodeVolHeader -- Decode the header of a VOL
*
*   04/10/2000 : initial modification to the new PV-Decoder Lib format.
*   10/12/2001 : reject non compliant bitstreams
*
***********************************************************CommentEnd********/
PV_STATUS DecodeVOLHeader(VideoDecData *video, int layer)
{
    PV_STATUS status;
    Vol *currVol;
    BitstreamDecVideo *stream;
    uint32 tmpvar, vol_shape;
    uint32 startCode;
#ifdef PV_SUPPORT_MAIN_PROFILE
    int *qmat, i, j;
#endif
    int version_id = 1;
#ifdef PV_TOLERATE_VOL_ERRORS
    uint32 profile = 0x01;
#endif
    /*  There's a "currLayer" variable inside videoDecData.          */
    /*   However, we don't maintain it until we decode frame data.  04/05/2000 */
    currVol = video->vol[layer];
    stream  = currVol->bitstream;
    currVol->moduloTimeBase = 0;

    /* Determine which start code for the decoder to begin with */
    status = BitstreamShowBits32HC(stream, &startCode);

    if (startCode == VISUAL_OBJECT_SEQUENCE_START_CODE)
    {   /*  Bitstream Exhchange Fix 9/99 */
        /* Bitstream Exchange requires we allow start with Video Object Sequence */
        /* visual_object_sequence_start_code            */
        (void) BitstreamReadBits32HC(stream);
        tmpvar = (uint32) BitstreamReadBits16(stream,  8); /* profile */
#ifndef PV_TOLERATE_VOL_ERRORS
        if (layer)                                                      /*    */
        {
            /* support SSPL0-2  */
            if (tmpvar != 0x10 && tmpvar != 0x11 && tmpvar != 0x12 &&
                    tmpvar != 0xA1 && tmpvar != 0xA2  && tmpvar != 0xA3/* Core SP@L1-L3 */)
                return PV_FAIL;
        }
        else
        {
            /* support SPL0-3 & SSPL0-2   */
            if (tmpvar != 0x01 && tmpvar != 0x02 && tmpvar != 0x03 && tmpvar != 0x08 &&
                    tmpvar != 0x10 && tmpvar != 0x11 && tmpvar != 0x12 &&
                    tmpvar != 0x21 && tmpvar != 0x22 &&  /* Core Profile Levels */
                    tmpvar != 0xA1 && tmpvar != 0xA2 && tmpvar != 0xA3 &&
                    tmpvar != 0xF0 && tmpvar != 0xF1 && /* Advanced Simple Profile Levels*/
                    tmpvar != 0xF2 && tmpvar != 0xF3 &&
                    tmpvar != 0xF4 && tmpvar != 0xF5)
                return PV_FAIL;
        }
#else
        profile = tmpvar;
#endif

        // save the profile and level for the query
        currVol->profile_level_id = (uint)tmpvar; //  6/10/04



        status = BitstreamShowBits32HC(stream, &tmpvar);
        if (tmpvar == USER_DATA_START_CODE)
        {
            /* Something has to be done with user data  11/11/99 */
            status = DecodeUserData(stream);
            if (status != PV_SUCCESS) return PV_FAIL;
        }
        /* visual_object_start_code                     */
        BitstreamShowBits32HC(stream, &tmpvar);
        if (tmpvar != VISUAL_OBJECT_START_CODE)
        {
            do
            {
                /* Search for VOL_HEADER */
                status = PVSearchNextM4VFrame(stream); /* search 0x00 0x00 0x01 */
                if (status != PV_SUCCESS) return PV_FAIL; /* breaks the loop */
                BitstreamShowBits32(stream, VOL_START_CODE_LENGTH, &tmpvar);
                PV_BitstreamFlushBits(stream, 8);
            }
            while (tmpvar != VOL_START_CODE);
            goto decode_vol;
        }
        else
        {
            BitstreamReadBits32HC(stream);
        }

        /*  is_visual_object_identifier            */
        tmpvar = (uint32) BitstreamRead1Bits(stream);
        if (tmpvar)
        {
            /* visual_object_verid                            */
            tmpvar = (uint32) BitstreamReadBits16(stream, 4);
            /* visual_object_priority                         */
            tmpvar = (uint32) BitstreamReadBits16(stream, 3);
        }
        /* visual_object_type                                 */
        BitstreamShowBits32(stream, 4, &tmpvar);
        if (tmpvar == 1)
        { /* video_signal_type */
            PV_BitstreamFlushBits(stream, 4);
            tmpvar = (uint32) BitstreamRead1Bits(stream);
            if (tmpvar == 1)
            {
                /* video_format */
                tmpvar = (uint32) BitstreamReadBits16(stream, 3);
                /* video_range  */
                tmpvar = (uint32) BitstreamRead1Bits(stream);
                /* color_description */
                tmpvar = (uint32) BitstreamRead1Bits(stream);
                if (tmpvar == 1)
                {
                    /* color_primaries */
                    tmpvar = (uint32) BitstreamReadBits16(stream, 8);
                    /* transfer_characteristics */
                    tmpvar = (uint32) BitstreamReadBits16(stream, 8);
                    /* matrix_coefficients */
                    tmpvar = (uint32) BitstreamReadBits16(stream, 8);
                }
            }
        }
        else
        {
            do
            {
                /* Search for VOL_HEADER */
                status = PVSearchNextM4VFrame(stream); /* search 0x00 0x00 0x01 */
                if (status != PV_SUCCESS) return PV_FAIL; /* breaks the loop */
                BitstreamShowBits32(stream, VOL_START_CODE_LENGTH, &tmpvar);
                PV_BitstreamFlushBits(stream, 8);
            }
            while (tmpvar != VOL_START_CODE);
            goto decode_vol;
        }

        /* next_start_code() */
        status = PV_BitstreamByteAlign(stream);                            /*  10/12/01 */
        status = BitstreamShowBits32HC(stream, &tmpvar);

        if (tmpvar == USER_DATA_START_CODE)
        {
            /* Something has to be done to deal with user data (parse it)  11/11/99 */
            status = DecodeUserData(stream);
            if (status != PV_SUCCESS) return PV_FAIL;
        }
        status = BitstreamShowBits32(stream, 27, &tmpvar);   /*  10/12/01 */
    }
    else
    {
        /*      tmpvar = 0;   */                                             /*  10/12/01 */
        status = BitstreamShowBits32(stream, 27, &tmpvar);     /* uncomment this line if you want
                                                                     to start decoding with a
                                                                     video_object_start_code */
    }

    if (tmpvar == VO_START_CODE)
    {
        /*****
        *
        *   Read the VOL header entries from the bitstream
        *
        *****/
        /* video_object_start_code                         */
        tmpvar = BitstreamReadBits32(stream, 27);
        tmpvar = (uint32) BitstreamReadBits16(stream, 5);


        /* video_object_layer_start_code                   */
        BitstreamShowBits32(stream, VOL_START_CODE_LENGTH, &tmpvar);
        if (tmpvar != VOL_START_CODE)
        {
            status = BitstreamCheckEndBuffer(stream);
            if (status == PV_END_OF_VOP)
            {
                video->shortVideoHeader = TRUE;
                return PV_SUCCESS;
            }
            else
            {
                do
                {
                    /* Search for VOL_HEADER */
                    status = PVSearchNextM4VFrame(stream);/* search 0x00 0x00 0x01 */
                    if (status != PV_SUCCESS) return PV_FAIL; /* breaks the loop */
                    BitstreamShowBits32(stream, VOL_START_CODE_LENGTH, &tmpvar);
                    PV_BitstreamFlushBits(stream, 8); /* advance the byte ptr */
                }
                while (tmpvar != VOL_START_CODE);
            }
        }
        else
        {
            PV_BitstreamFlushBits(stream, 8);
        }

decode_vol:
        PV_BitstreamFlushBits(stream, VOL_START_CODE_LENGTH - 8);
        video->shortVideoHeader = 0;

        /* vol_id (4 bits) */
        currVol->volID = (int) BitstreamReadBits16(stream, 4);

        /* RandomAccessible flag */
        tmpvar = (uint32) BitstreamRead1Bits(stream);

        /* object type */
        tmpvar = (uint32) BitstreamReadBits16(stream, 8);                /*  */

#ifdef PV_TOLERATE_VOL_ERRORS
        if (tmpvar == 0)
        {
            if (layer)                                                      /*    */
            {
                /* support SSPL0-2  */
                if (profile != 0x10 && profile != 0x11 && profile != 0x12)
                    return PV_FAIL;
                tmpvar = 0x02;
            }
            else
            {
                /* support SPL0-3 & SSPL0-2   */
                if (profile != 0x01 && profile != 0x02 && profile != 0x03 && profile != 0x08 &&
                        profile != 0x10 && profile != 0x11 && profile != 0x12)
                    return PV_FAIL;
                tmpvar = 0x01;
            }
            profile |= 0x0100;
        }
#endif

        if (layer)
        {
            if (tmpvar != 0x02) return PV_FAIL;
        }
        else
        {
            if (tmpvar != 0x01) return PV_FAIL;
        }

        /* version id specified? */
        tmpvar = (uint32) BitstreamRead1Bits(stream);
        if (tmpvar == 1)
        {
            /* version ID */
            version_id = (uint32) BitstreamReadBits16(stream, 4);
            /* priority */
            tmpvar = (uint32) BitstreamReadBits16(stream, 3);

        }

        /* aspect ratio info */
        tmpvar = (uint32) BitstreamReadBits16(stream, 4);
        if (tmpvar == 0) return PV_FAIL;
        if (tmpvar == 0xf /* extended_par */)
        {
            /* width */
            tmpvar = (uint32) BitstreamReadBits16(stream, 8);
            /* height */
            tmpvar = (uint32) BitstreamReadBits16(stream, 8);
        }


        /* control parameters present? */
        tmpvar = (uint32) BitstreamRead1Bits(stream);

        /*  Get the parameters (skipped) */
        /*  03/10/99 */
        if (tmpvar)
        {
            /* chroma_format                    */
            tmpvar = BitstreamReadBits16(stream, 2);
            if (tmpvar != 1) return PV_FAIL;
            /* low_delay  */
            tmpvar = BitstreamRead1Bits(stream);

            /* vbv_parameters present? */
            tmpvar = (uint32) BitstreamRead1Bits(stream);
            if (tmpvar)
            {
                /* first_half_bit_rate    */
                BitstreamReadBits16(stream, 15);
                if (!BitstreamRead1Bits(stream)) return PV_FAIL;
                /* latter_half_bit_rate   */
                BitstreamReadBits16(stream, 15);
                if (!BitstreamRead1Bits(stream)) return PV_FAIL;
                /* first_half_vbv_buffer_size   */
                BitstreamReadBits16(stream, 15);
                if (!BitstreamRead1Bits(stream)) return PV_FAIL;
                /* latter_half_vbv_buffer_size   */
                BitstreamReadBits16(stream,  3);
                /* first_half_vbv_occupancy     */
                BitstreamReadBits16(stream, 11);
                if (!BitstreamRead1Bits(stream)) return PV_FAIL;
                /* latter_half_vbv_occupancy  */
                BitstreamReadBits16(stream, 15);
                if (!BitstreamRead1Bits(stream)) return PV_FAIL;
            }
        }

        /* video_object_layer_shape (2 bits), only 00 (rect) is supported for now */
        vol_shape = (uint32) BitstreamReadBits16(stream, 2);
        if (vol_shape) return PV_FAIL;

        /* marker bit,  03/10/99 */
        if (!BitstreamRead1Bits(stream)) return PV_FAIL;

        /* vop_time_increment_resolution   */
        currVol->timeIncrementResolution = BitstreamReadBits16(stream, 16);
        if (currVol->timeIncrementResolution == 0) return PV_FAIL;

        /* . since nbitsTimeIncRes will be used over and over again, */
        /*    we should put it in Vol structure.  04/12/2000.          */
        currVol->nbitsTimeIncRes = CalcNumBits((uint)currVol->timeIncrementResolution - 1);

        if (!BitstreamRead1Bits(stream)) return PV_FAIL;

        /* fixed_vop_rate */
        currVol->fixedVopRate = (int) BitstreamRead1Bits(stream);
        if (currVol->fixedVopRate)
        {
            /* fixed_vop_time_increment */
            tmpvar = BitstreamReadBits16(stream, currVol->nbitsTimeIncRes);
        }

        /* marker bit */
        if (!BitstreamRead1Bits(stream)) return PV_FAIL;

        /* video_object_layer_width (13 bits) */
        video->displayWidth = video->width = (int) BitstreamReadBits16(stream, 13);

        /* round up to a multiple of MB_SIZE.   08/09/2000 */
        video->width = (video->width + 15) & -16;
//      video->displayWidth += (video->displayWidth & 0x1);  /* displayed image should be even size */

        /* marker bit */
        if (!BitstreamRead1Bits(stream)) return PV_FAIL;

        /* video_object_layer_height (13 bits) */
        video->displayHeight = video->height = (int) BitstreamReadBits16(stream, 13);

        /* round up to a multiple of MB_SIZE.   08/09/2000 */
        video->height = (video->height + 15) & -16;
//      video->displayHeight += (video->displayHeight & 0x1); /* displayed image should be even size */
        if (!BitstreamRead1Bits(stream)) return PV_FAIL;

        /*  03/10/99 */
        /* interlaced */
        tmpvar = (uint32) BitstreamRead1Bits(stream);
        if (tmpvar != 0)
        {
            mp4dec_log("DecodeVOLHeader(): Interlaced video is not supported.\n");
            return PV_FAIL;
        }

        /* obmc_disable */
        tmpvar = (uint32) BitstreamRead1Bits(stream);
        if (tmpvar == 0) return PV_FAIL;

        if (version_id == 1)
        {
            /*  sprite_enable (1 bits) */
            tmpvar = (uint32) BitstreamRead1Bits(stream);
            if (tmpvar)
            {
                mp4dec_log("DecodeVOLHeader(): Sprite is not supported.\n");
                return PV_FAIL;
            }
        }
        else
        {
            /* For version 2, vol_sprite_usage has two bits. */
            /* sprite_enable */
            tmpvar = (uint32) BitstreamReadBits16(stream, 2);
            if (tmpvar)
            {
                mp4dec_log("DecodeVOLHeader(): Sprite is not supported.\n");
                return PV_FAIL;
            }
        }

        /* not_8_bit */
        if (BitstreamRead1Bits(stream))
        {
            /* quant_precision */
            currVol->quantPrecision = BitstreamReadBits16(stream, 4);
            /* bits_per_pixel  */
            currVol->bitsPerPixel = BitstreamReadBits16(stream, 4);
            mp4dec_log("DecodeVOLHeader(): not an 8-bit stream.\n");    // For the time being we do not support != 8 bits

            return PV_FAIL;
        }
        else
        {
            currVol->quantPrecision = 5;
            currVol->bitsPerPixel = 8;
        }

        /* quant_type (1 bit) */
        currVol->quantType = BitstreamRead1Bits(stream);
        if (currVol->quantType)
        {
#ifdef PV_SUPPORT_MAIN_PROFILE
            /* load quantization matrices.   5/22/2000 */
            /* load_intra_quant_mat (1 bit) */
            qmat = currVol->iqmat;
            currVol->loadIntraQuantMat = BitstreamRead1Bits(stream);
            if (currVol->loadIntraQuantMat)
            {
                /* intra_quant_mat (8*64 bits) */
                i = 0;
                do
                {
                    qmat[*(zigzag_inv+i)] = (int) BitstreamReadBits16(stream, 8);
                }
                while ((qmat[*(zigzag_inv+i)] != 0) && (++i < 64));

                for (j = i; j < 64; j++)
                    qmat[*(zigzag_inv+j)] = qmat[*(zigzag_inv+i-1)];
            }
            else
            {
                oscl_memcpy(qmat, mpeg_iqmat_def, 64*sizeof(int));
            }

            qmat[0] = 0;             /* necessary for switched && MPEG quant  07/09/01 */

            /* load_nonintra_quant_mat (1 bit) */
            qmat = currVol->niqmat;
            currVol->loadNonIntraQuantMat = BitstreamRead1Bits(stream);
            if (currVol->loadNonIntraQuantMat)
            {
                /* nonintra_quant_mat (8*64 bits) */
                i = 0;
                do
                {
                    qmat[*(zigzag_inv+i)] = (int) BitstreamReadBits16(stream, 8);
                }
                while ((qmat[*(zigzag_inv+i)] != 0) && (++i < 64));

                for (j = i; j < 64; j++)
                    qmat[*(zigzag_inv+j)] = qmat[*(zigzag_inv+i-1)];
            }
            else
            {
                oscl_memcpy(qmat, mpeg_nqmat_def, 64*sizeof(int));
            }
#else
            return PV_FAIL;
#endif
        }

        if (version_id != 1)
        {
            /* quarter_sample enabled */
            tmpvar = BitstreamRead1Bits(stream);
            if (tmpvar) return PV_FAIL;
        }

        /* complexity_estimation_disable */
        currVol->complexity_estDisable = BitstreamRead1Bits(stream);
        if (currVol->complexity_estDisable == 0)
        {
            currVol->complexity_estMethod = BitstreamReadBits16(stream, 2);

            if (currVol->complexity_estMethod < 2)
            {
                /* shape_complexity_estimation_disable */
                tmpvar = BitstreamRead1Bits(stream);
                if (tmpvar == 0)
                {
                    mp4dec_log("DecodeVOLHeader(): Shape Complexity estimation is not supported.\n");
                    return PV_FAIL;
                }
                /* texture_complexity_estimation_set_1_disable */
                tmpvar = BitstreamRead1Bits(stream);
                if (tmpvar == 0)
                {
                    currVol->complexity.text_1 = BitstreamReadBits16(stream, 4);
                }
                /* marker bit */
                if (!BitstreamRead1Bits(stream)) return PV_FAIL;
                /* texture_complexity_estimation_set_2_disable */
                tmpvar = BitstreamRead1Bits(stream);
                if (tmpvar == 0)
                {
                    currVol->complexity.text_2 = BitstreamReadBits16(stream, 4);
                }
                /* motion_compensation_complexity_disable */
                tmpvar = BitstreamRead1Bits(stream);
                if (tmpvar == 0)
                {
                    currVol->complexity.mc = BitstreamReadBits16(stream, 6);
                }
                /* marker bit */
                if (!BitstreamRead1Bits(stream)) return PV_FAIL;

                if (currVol->complexity_estMethod == 1)
                {   /* version2_complexity_estimation_disable */
                    tmpvar = BitstreamRead1Bits(stream);
                    if (tmpvar == 0)
                    {
                        mp4dec_log("DecodeVOLHeader(): sadct, quarter pel not supported.\n");
                        return PV_FAIL;
                    }
                }
            }
        }

        /*  03/10/99 */
        /* resync_marker_disable */
        currVol->errorResDisable = (int) BitstreamRead1Bits(stream);
        /* data_partititioned    */
        currVol->dataPartitioning = (int) BitstreamRead1Bits(stream);

        video->vlcDecCoeffIntra = &VlcDecTCOEFIntra;
        video->vlcDecCoeffInter = &VlcDecTCOEFInter;

        if (currVol->dataPartitioning)
        {
            if (layer) return PV_FAIL;                              /*  */
            /* reversible_vlc */
            currVol->useReverseVLC = (int)BitstreamRead1Bits(stream);
            if (currVol->useReverseVLC)
            {
                video->vlcDecCoeffIntra = &RvlcDecTCOEFIntra;
                video->vlcDecCoeffInter = &RvlcDecTCOEFInter;
            }
            currVol->errorResDisable = 0;
        }
        else
        {
            currVol->useReverseVLC = 0;
        }

        if (version_id != 1)
        {
            /* newpred_enable */
            tmpvar = BitstreamRead1Bits(stream);
            if (tmpvar) return PV_FAIL;

            /* reduced_resolution_vop */
            tmpvar = BitstreamRead1Bits(stream);
            if (tmpvar) return PV_FAIL;

        }

        /* Intra AC/DC prediction is always true */
        video->intra_acdcPredDisable = 0;
        /* scalability */
        currVol->scalability = (int) BitstreamRead1Bits(stream);

        if (currVol->scalability)
        {
            if (layer == 0)  return PV_FAIL;                     /*  */
            /* hierarchy_type: 1 : temporal, 0 : spatial */
            /*  03/10/99 */
            currVol->scalType = (int) BitstreamRead1Bits(stream);              /*  */
            if (!currVol->scalType) return PV_FAIL;

            /* ref_layer_id (4 bits) */
            currVol->refVolID = (int) BitstreamReadBits16(stream, 4);
            if (layer)                                                      /*  */
            {
                if (currVol->refVolID != video->vol[0]->volID) return PV_FAIL;
            }
            /* ref_layer_sampling_direc (1 bits)              */
            /*   1 : ref. layer has higher resolution         */
            /*   0 : ref. layer has equal or lower resolution */
            currVol->refSampDir = (int) BitstreamRead1Bits(stream);
            if (currVol->refSampDir) return PV_FAIL;

            /* hor_sampling_factor_n (5 bits) */
            currVol->horSamp_n = (int) BitstreamReadBits16(stream, 5);

            /* hor_sampling_factor_m (5 bits) */
            currVol->horSamp_m = (int) BitstreamReadBits16(stream, 5);

            if (currVol->horSamp_m == 0) return PV_FAIL;
            if (currVol->horSamp_n != currVol->horSamp_m) return PV_FAIL;

            /* ver_sampling_factor_n (5 bits) */
            currVol->verSamp_n = (int) BitstreamReadBits16(stream, 5);

            /* ver_sampling_factor_m (5 bits) */
            currVol->verSamp_m = (int) BitstreamReadBits16(stream, 5);

            if (currVol->verSamp_m == 0) return PV_FAIL;
            if (currVol->verSamp_n != currVol->verSamp_m) return PV_FAIL;


            /* enhancement_type: 1 : partial region, 0 : full region */
            /* 04/10/2000: we only support full region enhancement layer. */
            if (BitstreamRead1Bits(stream)) return PV_FAIL;
        }

        PV_BitstreamByteAlign(stream);

        status = BitstreamShowBits32HC(stream, &tmpvar);

        /* if we hit the end of buffer, tmpvar == 0.   08/30/2000 */
        if (tmpvar == USER_DATA_START_CODE)
        {
            status = DecodeUserData(stream);
            /* you should not check for status here  03/19/2002 */
            status = PV_SUCCESS;
        }

        /* Compute some convenience variables:   04/13/2000 */
        video->nMBPerRow = video->width / MB_SIZE;
        video->nMBPerCol = video->height / MB_SIZE;
        video->nTotalMB = video->nMBPerRow * video->nMBPerCol;
        video->nBitsForMBID = CalcNumBits((uint)video->nTotalMB - 1);
#ifdef PV_ANNEX_IJKT_SUPPORT
        video->modified_quant = 0;
        video->advanced_INTRA = 0;
        video->deblocking = 0;
        video->slice_structure = 0;
#endif
    }
    else
    {
        /* SHORT_HEADER */
        status = BitstreamShowBits32(stream, SHORT_VIDEO_START_MARKER_LENGTH, &tmpvar);

        if (tmpvar == SHORT_VIDEO_START_MARKER)
        {
            video->shortVideoHeader = TRUE;
        }
        else
        {
            do
            {
                /* Search for VOL_HEADER */
                status = PVSearchNextM4VFrame(stream); /* search 0x00 0x00 0x01 */
                if (status != PV_SUCCESS) return PV_FAIL; /* breaks the loop */
                BitstreamShowBits32(stream, VOL_START_CODE_LENGTH, &tmpvar);
                PV_BitstreamFlushBits(stream, 8);
            }
            while (tmpvar != VOL_START_CODE);
            goto decode_vol;
        }
    }
#ifdef PV_TOLERATE_VOL_ERRORS
    if (profile > 0xFF || profile == 0)
    {
        return PV_BAD_VOLHEADER;
    }
#endif

    return status;
}


/***********************************************************CommentBegin******
*
* -- DecodeGOV -- Decodes the Group of VOPs from bitstream
*
*   04/20/2000  initial modification to the new PV-Decoder Lib format.
*
***********************************************************CommentEnd********/
PV_STATUS DecodeGOVHeader(BitstreamDecVideo *stream, uint32 *time_base)
{
    uint32 tmpvar, time_s;
    int closed_gov, broken_link;

    /* group_start_code (32 bits) */
//   tmpvar = BitstreamReadBits32(stream, 32);

    /* hours */
    tmpvar = (uint32) BitstreamReadBits16(stream, 5);
    time_s = tmpvar * 3600;

    /* minutes */
    tmpvar = (uint32) BitstreamReadBits16(stream, 6);
    time_s += tmpvar * 60;

    /* marker bit */
    tmpvar = (uint32) BitstreamRead1Bits(stream);

    /* seconds */
    tmpvar = (uint32) BitstreamReadBits16(stream, 6);
    time_s += tmpvar;

    /* We have to check the timestamp here.  If the sync timestamp is */
    /*    earlier than the previous timestamp or longer than 60 sec.  */
    /*    after the previous timestamp, assume the GOV header is      */
    /*    corrupted.                                 05/12/2000     */
    *time_base = time_s;   /*  02/27/2002 */
//  *time_base = *time_base/1000;
//  tmpvar = time_s - *time_base;
//  if (tmpvar <= 60) *time_base = time_s;
//  else return PV_FAIL;

    tmpvar = (uint32) BitstreamRead1Bits(stream);
    closed_gov = tmpvar;
    tmpvar = (uint32) BitstreamRead1Bits(stream);
    broken_link = tmpvar;

    if ((closed_gov == 0) && (broken_link == 1))
    {
        return PV_SUCCESS;        /*  03/15/2002  you can also return PV_FAIL */
    }

    PV_BitstreamByteAlign(stream);

    BitstreamShowBits32HC(stream, &tmpvar);

    while (tmpvar == USER_DATA_START_CODE)       /*  03/15/2002 */
    {
        DecodeUserData(stream);
        BitstreamShowBits32HC(stream, &tmpvar);
    }

    return PV_SUCCESS;
}

/***********************************************************CommentBegin******
*
* -- DecodeVopHeader -- Decodes the VOPheader information from the bitstream
*
*   04/12/2000  Initial port to the new PV decoder library format.
*   05/10/2000  Error resilient decoding of vop header.
*
***********************************************************CommentEnd********/
PV_STATUS DecodeVOPHeader(VideoDecData *video, Vop *currVop, Bool use_ext_timestamp)
{
    PV_STATUS status = PV_SUCCESS;
    Vol *currVol = video->vol[video->currLayer];
    BitstreamDecVideo *stream = currVol->bitstream;
    uint32 tmpvar;
    int time_base;

    /*****
    *   Read the VOP header from the bitstream (No shortVideoHeader Mode here!)
    *****/
    BitstreamShowBits32HC(stream, &tmpvar);

    /* check if we have a GOV header here.   08/30/2000 */
    if (tmpvar == GROUP_START_CODE)
    {
        tmpvar = BitstreamReadBits32HC(stream);
//      rewindBitstream(stream, START_CODE_LENGTH); /* for backward compatibility */
        status = DecodeGOVHeader(stream, &tmpvar);
        if (status != PV_SUCCESS)
        {
            return status;
        }
//      use_ext_timestamp = TRUE;   /*  02/08/2002 */
        /* We should have a VOP header following the GOV header.  03/15/2001 */
        BitstreamShowBits32HC(stream, &tmpvar);
    }
#ifdef PV_SUPPORT_TEMPORAL_SCALABILITY
    currVop->timeStamp = -1;
#endif
    if (tmpvar == VOP_START_CODE)
    {
        tmpvar = BitstreamReadBits32HC(stream);
    }
    else
    {
        PV_BitstreamFlushBits(stream, 8); // advance by a byte
        status = PV_FAIL;
        goto return_point;
    }



    /* vop_prediction_type (2 bits) */
    currVop->predictionType = (int) BitstreamReadBits16(stream, 2);

    /* modulo_time_base (? bits) */
    time_base = -1;
    do
    {
        time_base++;
        tmpvar = (uint32) BitstreamRead1Bits(stream);
    }
    while (tmpvar == 1);



    if (!use_ext_timestamp)
    {
        currVol->moduloTimeBase += 1000 * time_base; /* milliseconds based MTB  11/12/01 */
    }

    /* marker_bit (1 bit) */
    if (!BitstreamRead1Bits(stream))
    {
        status = PV_FAIL;
        goto return_point;
    }

    /* vop_time_increment (1-15 bits) in Nov_Compliant (1-16 bits) */
    /*    we always assumes fixed vop rate here */
    currVop->timeInc = BitstreamReadBits16(stream, currVol->nbitsTimeIncRes);


    /* marker_bit (1 bit) */
    if (!BitstreamRead1Bits(stream))
    {
        status = PV_FAIL;
        goto return_point;
    }

    /* vop_coded */
    currVop->vopCoded = (int) BitstreamRead1Bits(stream);


    if (currVop->vopCoded == 0)
    {
        status = PV_SUCCESS;
        goto return_point;
    }


    /* read vop_rounding_type */
    if (currVop->predictionType == P_VOP)
    {
        currVop->roundingType = (int) BitstreamRead1Bits(stream);
    }
    else
    {
        currVop->roundingType = 0;
    }

    if (currVol->complexity_estDisable == 0)
    {
        if (currVol->complexity_estMethod < 2)   /*   OCT 2002 */
        {
            if ((currVol->complexity.text_1 >> 3) & 0x1)    /* intra        */
                BitstreamReadBits16(stream, 8);
            if (currVol->complexity.text_1 & 0x1)           /* not_coded    */
                BitstreamReadBits16(stream, 8);
            if ((currVol->complexity.text_2 >> 3) & 0x1)    /* dct_coefs    */
                BitstreamReadBits16(stream, 8);
            if ((currVol->complexity.text_2 >> 2) & 0x1)    /* dct_lines    */
                BitstreamReadBits16(stream, 8);
            if ((currVol->complexity.text_2 >> 1) & 0x1)    /* vlc_symbols  */
                BitstreamReadBits16(stream, 8);
            if (currVol->complexity.text_2 & 0x1)           /* vlc_bits     */
                BitstreamReadBits16(stream, 4);

            if (currVop->predictionType != I_VOP)
            {
                if ((currVol->complexity.text_1 >> 2) & 0x1)    /* inter    */
                    BitstreamReadBits16(stream, 8);
                if ((currVol->complexity.text_1 >> 1) & 0x1)    /* inter_4v */
                    BitstreamReadBits16(stream, 8);
                if ((currVol->complexity.mc >> 5) & 0x1)        /* apm      */
                    BitstreamReadBits16(stream, 8);
                if ((currVol->complexity.mc >> 4) & 0x1)        /* npm      */
                    BitstreamReadBits16(stream, 8);
                /* interpolate_mc_q */
                if ((currVol->complexity.mc >> 2) & 0x1)        /* forw_back_mc_q */
                    BitstreamReadBits16(stream, 8);
                if ((currVol->complexity.mc >> 1) & 0x1)        /* halfpel2 */
                    BitstreamReadBits16(stream, 8);
                if (currVol->complexity.mc & 0x1)               /* halfpel4 */
                    BitstreamReadBits16(stream, 8);
            }
            if (currVop->predictionType == B_VOP)
            {
                if ((currVol->complexity.mc >> 3) & 0x1)        /* interpolate_mc_q */
                    BitstreamReadBits16(stream, 8);
            }
        }
    }

    /* read intra_dc_vlc_thr */
    currVop->intraDCVlcThr = (int) BitstreamReadBits16(stream, 3);

    /* read vop_quant (currVol->quantPrecision bits) */
    currVop->quantizer = (int16) BitstreamReadBits16(stream, currVol->quantPrecision);
    if (currVop->quantizer == 0)
    {
        currVop->quantizer = video->prevVop->quantizer;
        status = PV_FAIL;
        goto return_point;
    }


    /* read vop_fcode_forward */
    if (currVop->predictionType != I_VOP)
    {
        tmpvar = (uint32) BitstreamReadBits16(stream, 3);
        if (tmpvar < 1)
        {
            currVop->fcodeForward = 1;
            status = PV_FAIL;
            goto return_point;
        }
        currVop->fcodeForward = tmpvar;
    }
    else
    {
        currVop->fcodeForward = 0;
    }

    /* read vop_fcode_backward */
    if (currVop->predictionType == B_VOP)
    {
        tmpvar = (uint32) BitstreamReadBits16(stream, 3);
        if (tmpvar < 1)
        {
            currVop->fcodeBackward = 1;
            status = PV_FAIL;
            goto return_point;
        }
        currVop->fcodeBackward = tmpvar;
    }
    else
    {
        currVop->fcodeBackward = 0;
    }

    if (currVol->scalability)
    {
        currVop->refSelectCode = (int) BitstreamReadBits16(stream, 2);
    }

return_point:
    return status;
}


/***********************************************************CommentBegin******
*
* -- VideoPlaneWithShortHeader -- Decodes the short_video_header information from the bitstream
* Modified :
             04/23/2001.  Remove the codes related to the
                 "first pass" decoding.  We use a different function
                 to set up the decoder now.
***********************************************************CommentEnd********/
PV_STATUS DecodeShortHeader(VideoDecData *video, Vop *currVop)
{
    PV_STATUS status = PV_SUCCESS;
    Vol *currVol = video->vol[0];
    BitstreamDecVideo *stream = currVol->bitstream;
    uint32 tmpvar;
    int32 size;

    int extended_PTYPE = FALSE;
    int UFEP = 0, custom_PFMT = 0, custom_PCF = 0;

    status = BitstreamShowBits32(stream, SHORT_VIDEO_START_MARKER_LENGTH, &tmpvar);

    if (tmpvar !=  SHORT_VIDEO_START_MARKER)
    {
        status = PV_FAIL;
        goto return_point;
    }


    PV_BitstreamFlushBits(stream, SHORT_VIDEO_START_MARKER_LENGTH);

    /* Temporal reference. Using vop_time_increment_resolution = 30000 */
    tmpvar = (uint32) BitstreamReadBits16(stream, 8);
    currVop->temporalRef = (int) tmpvar;


    currVop->timeInc = 0xff & (256 + currVop->temporalRef - video->prevVop->temporalRef);
    currVol->moduloTimeBase += currVop->timeInc; /* mseconds   11/12/01 */
    /* Marker Bit */
    if (!BitstreamRead1Bits(stream))
    {
        mp4dec_log("DecodeShortHeader(): Marker bit wrong.\n");
        status = PV_FAIL;
        goto return_point;
    }

    /* Zero Bit */
    if (BitstreamRead1Bits(stream))
    {
        mp4dec_log("DecodeShortHeader(): Zero bit wrong.\n");
        status = PV_FAIL;
        goto return_point;
    }

    /*split_screen_indicator*/
    if (BitstreamRead1Bits(stream))
    {
        mp4dec_log("DecodeShortHeader(): Split Screen not supported.\n");
        VideoDecoderErrorDetected(video);
    }

    /*document_freeze_camera*/
    if (BitstreamRead1Bits(stream))
    {
        mp4dec_log("DecodeShortHeader(): Freeze Camera not supported.\n");
        VideoDecoderErrorDetected(video);
    }

    /*freeze_picture_release*/
    if (BitstreamRead1Bits(stream))
    {
        mp4dec_log("DecodeShortHeader(): Freeze Release not supported.\n");
        VideoDecoderErrorDetected(video);
    }
    /* source format */
    switch (BitstreamReadBits16(stream, 3))
    {
        case 1:
            if (video->size < 128*96)
            {
                status = PV_FAIL;
                goto return_point;
            }
            video->displayWidth = video->width =  128;
            video->displayHeight = video->height  = 96;
            break;

        case 2:
            if (video->size < 176*144)
            {
                status = PV_FAIL;
                goto return_point;
            }
            video->displayWidth = video->width  = 176;
            video->displayHeight = video->height  = 144;
            break;

        case 3:
            if (video->size < 352*288)
            {
                status = PV_FAIL;
                goto return_point;
            }
            video->displayWidth = video->width = 352;
            video->displayHeight = video->height = 288;
            break;

        case 4:
            if (video->size < 704*576)
            {
                status = PV_FAIL;
                goto return_point;
            }
            video->displayWidth = video->width = 704;
            video->displayHeight = video->height = 576;
            break;

        case 5:
            if (video->size < 1408*1152)
            {
                status = PV_FAIL;
                goto return_point;
            }
            video->displayWidth = video->width = 1408;
            video->displayHeight = video->height = 1152;
            break;

        case 7:
            extended_PTYPE = TRUE;
            break;

        default:
            /* Msg("H.263 source format not legal\n"); */
            status = PV_FAIL;
            goto return_point;
    }


    currVop->roundingType = 0;

    if (extended_PTYPE == FALSE)
    {
        currVop->predictionType = (int) BitstreamRead1Bits(stream);

        /* four_reserved_zero_bits */
        if (BitstreamReadBits16(stream, 4))
        {
            mp4dec_log("DecodeShortHeader(): Reserved bits wrong.\n");
            status = PV_FAIL;
            goto return_point;
        }
    }
    else
    {
        UFEP = BitstreamReadBits16(stream, 3);
        if (UFEP == 1)
        {
            /* source format */
            switch (BitstreamReadBits16(stream, 3))
            {
                case 1:
                    if (video->size < 128*96)
                    {
                        status = PV_FAIL;
                        goto return_point;
                    }
                    video->displayWidth = video->width =  128;
                    video->displayHeight = video->height  = 96;
                    break;

                case 2:
                    if (video->size < 176*144)
                    {
                        status = PV_FAIL;
                        goto return_point;
                    }
                    video->displayWidth = video->width  = 176;
                    video->displayHeight = video->height  = 144;
                    break;

                case 3:
                    if (video->size < 352*288)
                    {
                        status = PV_FAIL;
                        goto return_point;
                    }
                    video->displayWidth = video->width = 352;
                    video->displayHeight = video->height = 288;
                    break;

                case 4:
                    if (video->size < 704*576)
                    {
                        status = PV_FAIL;
                        goto return_point;
                    }
                    video->displayWidth = video->width = 704;
                    video->displayHeight = video->height = 576;
                    break;

                case 5:
                    if (video->size < 1408*1152)
                    {
                        status = PV_FAIL;
                        goto return_point;
                    }
                    video->displayWidth = video->width = 1408;
                    video->displayHeight = video->height = 1152;
                    break;

                case 6:
                    custom_PFMT = TRUE;
                    break;

                default:
                    /* Msg("H.263 source format not legal\n"); */
                    status = PV_FAIL;
                    goto return_point;
            }

            custom_PCF = BitstreamRead1Bits(stream);
            /* unrestricted MV */
            if (BitstreamRead1Bits(stream))
            {
                status = PV_FAIL;
                goto return_point;
            }
            /* SAC */
            if (BitstreamRead1Bits(stream))
            {
                status = PV_FAIL;
                goto return_point;
            }

            /* AP */
            if (BitstreamRead1Bits(stream))
            {
                status = PV_FAIL;
                goto return_point;
            }

            video->advanced_INTRA = BitstreamRead1Bits(stream);

            video->deblocking = BitstreamRead1Bits(stream);

            video->slice_structure = BitstreamRead1Bits(stream);

            /* RPS, ISD, AIV */
            if (BitstreamReadBits16(stream, 3))
            {
                status = PV_FAIL;
                goto return_point;
            }
            video->modified_quant = BitstreamRead1Bits(stream);

            /* Marker Bit and reserved*/
            if (BitstreamReadBits16(stream, 4) != 8)
            {
                status = PV_FAIL;
                goto return_point;
            }
        }
#ifndef PV_ANNEX_IJKT_SUPPORT
        if (video->advanced_INTRA | video->deblocking | video->modified_quant | video->modified_quant)
        {
            status = PV_FAIL;
            goto return_point;
        }
#endif

        if (UFEP == 0 || UFEP == 1)
        {
            tmpvar = BitstreamReadBits16(stream, 3);
            if (tmpvar > 1)
            {
                status = PV_FAIL;
                goto return_point;
            }
            currVop->predictionType = tmpvar;
            /* RPR */
            if (BitstreamRead1Bits(stream))
            {
                status = PV_FAIL;
                goto return_point;
            }

            /* RRU */
            if (BitstreamRead1Bits(stream))
            {
                status = PV_FAIL;
                goto return_point;
            }
            currVop->roundingType = (int) BitstreamRead1Bits(stream);
            if (BitstreamReadBits16(stream, 3) != 1)
            {
                status = PV_FAIL;
                goto return_point;
            }
        }
        else
        {
            status = PV_FAIL;
            goto return_point;
        }
        /* CPM */
        if (BitstreamRead1Bits(stream))
        {
            status = PV_FAIL;
            goto return_point;
        }
        /* CPFMT */
        if (custom_PFMT == 1 && UFEP == 1)
        {
            /* aspect ratio */
            tmpvar = BitstreamReadBits16(stream, 4);
            if (tmpvar == 0)
            {
                status = PV_FAIL;
                goto return_point;
            }
            /* Extended PAR */
            if (tmpvar == 0xF)
            {
                /* Read par_width and par_height but do nothing */
                /* par_width */
                tmpvar = BitstreamReadBits16(stream, 8);

                /* par_height */
                tmpvar = BitstreamReadBits16(stream, 8);
            }
            tmpvar = BitstreamReadBits16(stream, 9);

            video->displayWidth = (tmpvar + 1) << 2;
            video->width = (video->displayWidth + 15) & -16;
            /* marker bit */
            if (!BitstreamRead1Bits(stream))
            {
                status = PV_FAIL;
                goto return_point;
            }
            tmpvar = BitstreamReadBits16(stream, 9);
            if (tmpvar == 0)
            {
                status = PV_FAIL;
                goto return_point;
            }
            video->displayHeight = tmpvar << 2;
            video->height = (video->displayHeight + 15) & -16;

            if (video->height * video->width > video->size)
            {
                status = PV_FAIL;
                goto return_point;
            }

            video->nTotalMB = video->width / MB_SIZE * video->height / MB_SIZE;

            if (video->nTotalMB <= 48)
            {
                video->nBitsForMBID = 6;
            }
            else if (video->nTotalMB <= 99)
            {
                video->nBitsForMBID = 7;
            }
            else if (video->nTotalMB <= 396)
            {
                video->nBitsForMBID = 9;
            }
            else if (video->nTotalMB <= 1584)
            {
                video->nBitsForMBID = 11;
            }
            else if (video->nTotalMB <= 6336)
            {
                video->nBitsForMBID = 13 ;
            }
            else if (video->nTotalMB <= 9216)
            {
                video->nBitsForMBID = 14 ;
            }
            else
            {
                status = PV_FAIL;
                goto return_point;
            }
        }
        if (UFEP == 1 && custom_PCF == 1)
        {
            BitstreamRead1Bits(stream);

            tmpvar = BitstreamReadBits16(stream, 7);
            if (tmpvar == 0)
            {
                status = PV_FAIL;
                goto return_point;
            }
        }

        if (custom_PCF == 1)
        {
            currVop->ETR = BitstreamReadBits16(stream, 2);
        }

        if (UFEP == 1 && video->slice_structure == 1)
        {
            /* SSS */
            tmpvar = BitstreamReadBits16(stream, 2);
            if (tmpvar != 0)
            {
                status = PV_FAIL;
                goto return_point;
            }
        }
    }

    /* Recalculate number of macroblocks per row & col since */
    /*  the frame size can change.           04/23/2001.   */
    video->nMBinGOB = video->nMBPerRow = video->width / MB_SIZE;
    video->nGOBinVop = video->nMBPerCol = video->height / MB_SIZE;
    video->nTotalMB = video->nMBPerRow * video->nMBPerCol;
    if (custom_PFMT == 0  || UFEP == 0)
    {
        video->nBitsForMBID = CalcNumBits((uint)video->nTotalMB - 1); /* otherwise calculate above */
    }
    size = (int32)video->width * video->height;
    if (video->currVop->predictionType == P_VOP && size > video->videoDecControls->size)
    {
        status = PV_FAIL;
        goto return_point;
    }
    video->videoDecControls->size = size;
    video->currVop->uChan = video->currVop->yChan + size;
    video->currVop->vChan = video->currVop->uChan + (size >> 2);
    video->prevVop->uChan = video->prevVop->yChan + size;
    video->prevVop->vChan = video->prevVop->uChan + (size >> 2);


    currVop->quantizer = (int16) BitstreamReadBits16(stream, 5);

    if (currVop->quantizer == 0)                          /*  04/03/01 */
    {
        currVop->quantizer = video->prevVop->quantizer;
        status = PV_FAIL;
        goto return_point;
    }


    /* Zero bit */
    if (extended_PTYPE == FALSE)
    {
        if (BitstreamRead1Bits(stream))
        {
            mp4dec_log("DecodeShortHeader(): Zero bit wrong.\n");
            status = PV_FAIL;
            goto return_point;
        }
    }
    /* pei */
    tmpvar = (uint32) BitstreamRead1Bits(stream);

    while (tmpvar)
    {
        tmpvar = (uint32) BitstreamReadBits16(stream, 8); /* "PSPARE" */
        tmpvar = (uint32) BitstreamRead1Bits(stream); /* "PEI" */
    }

    if (video->slice_structure)  /* ANNEX_K */
    {
        if (!BitstreamRead1Bits(stream))  /* SEPB1 */
        {
            status = PV_FAIL;
            goto return_point;
        }

        //  if (currVol->nBitsForMBID //
        if (BitstreamReadBits16(stream, video->nBitsForMBID))
        {
            status = PV_FAIL;             /* no ASO, RS support for Annex K */
            goto return_point;
        }

        if (!BitstreamRead1Bits(stream))  /*SEPB3 */
        {
            status = PV_FAIL;
            goto return_point;
        }

    }
    /* Setting of other VOP-header parameters */
    currVop->gobNumber = 0;
    currVop->vopCoded = 1;

    currVop->intraDCVlcThr = 0;
    currVop->gobFrameID = 0; /* initial value,  05/22/00 */
    currVol->errorResDisable = 0;
    /*PutVopInterlaced(0,curr_vop); no implemented yet */
    if (currVop->predictionType != I_VOP)
        currVop->fcodeForward = 1;
    else
        currVop->fcodeForward = 0;

return_point:

    return status;
}
/***********************************************************CommentBegin******
*
* -- PV_DecodeVop -- Decodes the VOP information from the bitstream
*
*   04/12/2000
*                   Initial port to the new PV decoder library format.
*                   This function is different from the one in MoMuSys MPEG-4
*                   visual decoder.  We handle combined mode with or withput
*                   error resilience and H.263 mode through the sam path now.
*
*   05/04/2000
*                   Added temporal scalability to the decoder.
*
***********************************************************CommentEnd********/
PV_STATUS PV_DecodeVop(VideoDecData *video)
{
    Vol *currVol = video->vol[video->currLayer];
    PV_STATUS status;
    uint32 tmpvar;

    /*****
    *   Do scalable or non-scalable decoding of the current VOP
    *****/

    if (!currVol->scalability)
    {
        if (currVol->dataPartitioning)
        {
            /* Data partitioning mode comes here */
            status = DecodeFrameDataPartMode(video);
        }
        else
        {
            /* Combined mode with or without error resilience */
            /*    and short video header comes here.          */
            status = DecodeFrameCombinedMode(video);
        }
    }
    else
    {
#ifdef DO_NOT_FOLLOW_STANDARD
        /* according to the standard, only combined mode is allowed */
        /*    in the enhancement layer.          06/01/2000.        */
        if (currVol->dataPartitioning)
        {
            /* Data partitioning mode comes here */
            status = DecodeFrameDataPartMode(video);
        }
        else
        {
            /* Combined mode with or without error resilience */
            /*    and short video header comes here.          */
            status = DecodeFrameCombinedMode(video);
        }
#else
        status = DecodeFrameCombinedMode(video);
#endif
    }

    /* This part is for consuming Visual_object_sequence_end_code and EOS Code */   /*  10/15/01 */
    if (!video->shortVideoHeader)
    {
        /* at this point bitstream is expected to be byte aligned */
        BitstreamByteAlignNoForceStuffing(currVol->bitstream);

        status = BitstreamShowBits32HC(currVol->bitstream, &tmpvar);  /*  07/07/01 */
        if (tmpvar == VISUAL_OBJECT_SEQUENCE_END_CODE)/* VOS_END_CODE */
        {
            PV_BitstreamFlushBits(currVol->bitstream, 16);
            PV_BitstreamFlushBits(currVol->bitstream, 16);
        }

    }
    else
    {
#ifdef PV_ANNEX_IJKT_SUPPORT
        if (video->deblocking)
        {
            H263_Deblock(video->currVop->yChan, video->width, video->height, video->QPMB, video->headerInfo.Mode, 0, 0);
            H263_Deblock(video->currVop->uChan, video->width >> 1, video->height >> 1, video->QPMB, video->headerInfo.Mode, 1, video->modified_quant);
            H263_Deblock(video->currVop->vChan, video->width >> 1, video->height >> 1, video->QPMB, video->headerInfo.Mode, 1, video->modified_quant);
        }
#endif
        /* Read EOS code for shortheader bitstreams    */
        status = BitstreamShowBits32(currVol->bitstream, 22, &tmpvar);
        if (tmpvar == SHORT_VIDEO_END_MARKER)
        {
            PV_BitstreamFlushBits(currVol->bitstream, 22);
        }
        else
        {
            status = PV_BitstreamShowBitsByteAlign(currVol->bitstream, 22, &tmpvar);
            if (tmpvar == SHORT_VIDEO_END_MARKER)
            {
                PV_BitstreamByteAlign(currVol->bitstream);
                PV_BitstreamFlushBits(currVol->bitstream, 22);
            }
        }
    }
    return status;
}


/***********************************************************CommentBegin******
*
* -- CalcVopDisplayTime -- calculate absolute time when VOP is to be displayed
*
*   04/12/2000 Initial port to the new PV decoder library format.
*
***********************************************************CommentEnd********/
uint32 CalcVopDisplayTime(Vol *currVol, Vop *currVop, int shortVideoHeader)
{
    uint32 display_time;


    /*****
    *   Calculate the time when the VOP is to be displayed next
    *****/

    if (!shortVideoHeader)
    {
        display_time = (uint32)(currVol->moduloTimeBase + (((int32)currVop->timeInc - (int32)currVol->timeInc_offset) * 1000) / ((int32)currVol->timeIncrementResolution));  /*  11/12/2001 */
        if (currVop->timeStamp >= display_time)
        {
            display_time += 1000;  /* this case is valid if GOVHeader timestamp is ignored */
        }
    }
    else
    {
        display_time = (uint32)(currVol->moduloTimeBase * 33 + (currVol->moduloTimeBase * 11) / 30); /*  11/12/2001 */
    }

    return(display_time);
}

