/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*------------------------------------------------------------------------------

    Table of contents

     1. Include headers
     2. External compiler flags
     3. Module defines
     4. Local function prototypes
     5. Functions
          h264bsdInit
          h264bsdDecode
          h264bsdShutdown
          h264bsdCurrentImage
          h264bsdNextOutputPicture
          h264bsdPicWidth
          h264bsdPicHeight
          h264bsdFlushBuffer
          h264bsdCheckValidParamSets
          h264bsdVideoRange
          h264bsdMatrixCoefficients
          h264bsdCroppingParams

------------------------------------------------------------------------------*/

/*------------------------------------------------------------------------------
    1. Include headers
------------------------------------------------------------------------------*/

#include "h264bsd_decoder.h"
#include "h264bsd_nal_unit.h"
#include "h264bsd_byte_stream.h"
#include "h264bsd_seq_param_set.h"
#include "h264bsd_pic_param_set.h"
#include "h264bsd_slice_header.h"
#include "h264bsd_slice_data.h"
#include "h264bsd_neighbour.h"
#include "h264bsd_util.h"
#include "h264bsd_dpb.h"
#include "h264bsd_deblocking.h"
#include "h264bsd_conceal.h"

/*------------------------------------------------------------------------------
    2. External compiler flags
--------------------------------------------------------------------------------

--------------------------------------------------------------------------------
    3. Module defines
------------------------------------------------------------------------------*/

/*------------------------------------------------------------------------------
    4. Local function prototypes
------------------------------------------------------------------------------*/

/*------------------------------------------------------------------------------

    Function name: h264bsdInit

        Functional description:
            Initialize the decoder.

        Inputs:
            noOutputReordering  flag to indicate the decoder that it does not
                                have to perform reordering of display images.

        Outputs:
            pStorage            pointer to initialized storage structure

        Returns:
            none

------------------------------------------------------------------------------*/

u32 h264bsdInit(storage_t *pStorage, u32 noOutputReordering)
{

/* Variables */
    u32 size;
/* Code */

    ASSERT(pStorage);

    h264bsdInitStorage(pStorage);

    /* allocate mbLayer to be next multiple of 64 to enable use of
     * specific NEON optimized "memset" for clearing the structure */
    size = (sizeof(macroblockLayer_t) + 63) & ~0x3F;

    pStorage->mbLayer = (macroblockLayer_t*)H264SwDecMalloc(size);
    if (!pStorage->mbLayer)
        return HANTRO_NOK;

    if (noOutputReordering)
        pStorage->noReordering = HANTRO_TRUE;

    return HANTRO_OK;
}

/*------------------------------------------------------------------------------

    Function: h264bsdDecode

        Functional description:
            Decode a NAL unit. This function calls other modules to perform
            tasks like
                * extract and decode NAL unit from the byte stream
                * decode parameter sets
                * decode slice header and slice data
                * conceal errors in the picture
                * perform deblocking filtering

            This function contains top level control logic of the decoder.

        Inputs:
            pStorage        pointer to storage data structure
            byteStrm        pointer to stream buffer given by application
            len             length of the buffer in bytes
            picId           identifier for a picture, assigned by the
                            application

        Outputs:
            readBytes       number of bytes read from the stream is stored
                            here

        Returns:
            H264BSD_RDY             decoding finished, nothing special
            H264BSD_PIC_RDY         decoding of a picture finished
            H264BSD_HDRS_RDY        param sets activated, information like
                                    picture dimensions etc can be read
            H264BSD_ERROR           error in decoding
            H264BSD_PARAM_SET_ERROR serius error in decoding, failed to
                                    activate param sets

------------------------------------------------------------------------------*/

u32 h264bsdDecode(storage_t *pStorage, u8 *byteStrm, u32 len, u32 picId,
    u32 *readBytes)
{

/* Variables */

    u32 tmp, ppsId, spsId;
    i32 picOrderCnt;
    nalUnit_t nalUnit;
    seqParamSet_t seqParamSet;
    picParamSet_t picParamSet;
    strmData_t strm;
    u32 accessUnitBoundaryFlag = HANTRO_FALSE;
    u32 picReady = HANTRO_FALSE;

/* Code */

    ASSERT(pStorage);
    ASSERT(byteStrm);
    ASSERT(len);
    ASSERT(readBytes);

    /* if previous buffer was not finished and same pointer given -> skip NAL
     * unit extraction */
    if (pStorage->prevBufNotFinished && byteStrm == pStorage->prevBufPointer)
    {
        strm = pStorage->strm[0];
        strm.pStrmCurrPos = strm.pStrmBuffStart;
        strm.strmBuffReadBits = strm.bitPosInWord = 0;
        *readBytes = pStorage->prevBytesConsumed;
    }
    else
    {
        tmp = h264bsdExtractNalUnit(byteStrm, len, &strm, readBytes);
        if (tmp != HANTRO_OK)
        {
            EPRINT("BYTE_STREAM");
            return(H264BSD_ERROR);
        }
        /* store stream */
        pStorage->strm[0] = strm;
        pStorage->prevBytesConsumed = *readBytes;
        pStorage->prevBufPointer = byteStrm;
    }
    pStorage->prevBufNotFinished = HANTRO_FALSE;

    tmp = h264bsdDecodeNalUnit(&strm, &nalUnit);
    if (tmp != HANTRO_OK)
    {
        EPRINT("NAL_UNIT");
        return(H264BSD_ERROR);
    }

    /* Discard unspecified, reserved, SPS extension and auxiliary picture slices */
    if(nalUnit.nalUnitType == 0 || nalUnit.nalUnitType >= 13)
    {
        DEBUG(("DISCARDED NAL (UNSPECIFIED, REGISTERED, SPS ext or AUX slice)\n"));
        return(H264BSD_RDY);
    }

    tmp = h264bsdCheckAccessUnitBoundary(
      &strm,
      &nalUnit,
      pStorage,
      &accessUnitBoundaryFlag);
    if (tmp != HANTRO_OK)
    {
        EPRINT("ACCESS UNIT BOUNDARY CHECK");
        if (tmp == PARAM_SET_ERROR)
            return(H264BSD_PARAM_SET_ERROR);
        else
            return(H264BSD_ERROR);
    }

    if ( accessUnitBoundaryFlag )
    {
        DEBUG(("Access unit boundary\n"));
        /* conceal if picture started and param sets activated */
        if (pStorage->picStarted && pStorage->activeSps != NULL)
        {
            DEBUG(("CONCEALING..."));

            /* return error if second phase of
             * initialization is not completed */
            if (pStorage->pendingActivation)
            {
                EPRINT("Pending activation not completed");
                return (H264BSD_ERROR);
            }

            if (!pStorage->validSliceInAccessUnit)
            {
                pStorage->currImage->data =
                    h264bsdAllocateDpbImage(pStorage->dpb);
                h264bsdInitRefPicList(pStorage->dpb);
                tmp = h264bsdConceal(pStorage, pStorage->currImage, P_SLICE);
            }
            else
                tmp = h264bsdConceal(pStorage, pStorage->currImage,
                    pStorage->sliceHeader->sliceType);

            picReady = HANTRO_TRUE;

            /* current NAL unit should be decoded on next activation -> set
             * readBytes to 0 */
            *readBytes = 0;
            pStorage->prevBufNotFinished = HANTRO_TRUE;
            DEBUG(("...DONE\n"));
        }
        else
        {
            pStorage->validSliceInAccessUnit = HANTRO_FALSE;
        }
        pStorage->skipRedundantSlices = HANTRO_FALSE;
    }

    if (!picReady)
    {
        switch (nalUnit.nalUnitType)
        {
            case NAL_SEQ_PARAM_SET:
                DEBUG(("SEQ PARAM SET\n"));
                tmp = h264bsdDecodeSeqParamSet(&strm, &seqParamSet);
                if (tmp != HANTRO_OK)
                {
                    EPRINT("SEQ_PARAM_SET");
                    FREE(seqParamSet.offsetForRefFrame);
                    FREE(seqParamSet.vuiParameters);
                    return(H264BSD_ERROR);
                }
                tmp = h264bsdStoreSeqParamSet(pStorage, &seqParamSet);
                break;

            case NAL_PIC_PARAM_SET:
                DEBUG(("PIC PARAM SET\n"));
                tmp = h264bsdDecodePicParamSet(&strm, &picParamSet);
                if (tmp != HANTRO_OK)
                {
                    EPRINT("PIC_PARAM_SET");
                    FREE(picParamSet.runLength);
                    FREE(picParamSet.topLeft);
                    FREE(picParamSet.bottomRight);
                    FREE(picParamSet.sliceGroupId);
                    return(H264BSD_ERROR);
                }
                tmp = h264bsdStorePicParamSet(pStorage, &picParamSet);
                break;

            case NAL_CODED_SLICE_IDR:
                DEBUG(("IDR "));
                /* fall through */
            case NAL_CODED_SLICE:
                DEBUG(("SLICE HEADER\n"));

                /* picture successfully finished and still decoding same old
                 * access unit -> no need to decode redundant slices */
                if (pStorage->skipRedundantSlices)
                    return(H264BSD_RDY);

                pStorage->picStarted = HANTRO_TRUE;

                if (h264bsdIsStartOfPicture(pStorage))
                {
                    pStorage->numConcealedMbs = 0;
                    pStorage->currentPicId    = picId;

                    tmp = h264bsdCheckPpsId(&strm, &ppsId);
                    ASSERT(tmp == HANTRO_OK);
                    /* store old activeSpsId and return headers ready
                     * indication if activeSps changes */
                    spsId = pStorage->activeSpsId;
                    tmp = h264bsdActivateParamSets(pStorage, ppsId,
                            IS_IDR_NAL_UNIT(&nalUnit) ?
                            HANTRO_TRUE : HANTRO_FALSE);
                    if (tmp != HANTRO_OK)
                    {
                        EPRINT("Param set activation");
                        pStorage->activePpsId = MAX_NUM_PIC_PARAM_SETS;
                        pStorage->activePps = NULL;
                        pStorage->activeSpsId = MAX_NUM_SEQ_PARAM_SETS;
                        pStorage->activeSps = NULL;
                        pStorage->pendingActivation = HANTRO_FALSE;

                        if(tmp == MEMORY_ALLOCATION_ERROR)
                        {
                            return H264BSD_MEMALLOC_ERROR;
                        }
                        else
                            return(H264BSD_PARAM_SET_ERROR);
                    }

                    if (spsId != pStorage->activeSpsId)
                    {
                        seqParamSet_t *oldSPS = NULL;
                        seqParamSet_t *newSPS = pStorage->activeSps;
                        u32 noOutputOfPriorPicsFlag = 1;

                        if(pStorage->oldSpsId < MAX_NUM_SEQ_PARAM_SETS)
                        {
                            oldSPS = pStorage->sps[pStorage->oldSpsId];
                        }

                        *readBytes = 0;
                        pStorage->prevBufNotFinished = HANTRO_TRUE;


                        if(nalUnit.nalUnitType == NAL_CODED_SLICE_IDR)
                        {
                            tmp =
                            h264bsdCheckPriorPicsFlag(&noOutputOfPriorPicsFlag,
                                                          &strm, newSPS,
                                                          pStorage->activePps,
                                                          nalUnit.nalUnitType);
                        }
                        else
                        {
                            tmp = HANTRO_NOK;
                        }

                        if((tmp != HANTRO_OK) ||
                           (noOutputOfPriorPicsFlag != 0) ||
                           (pStorage->dpb->noReordering) ||
                           (oldSPS == NULL) ||
                           (oldSPS->picWidthInMbs != newSPS->picWidthInMbs) ||
                           (oldSPS->picHeightInMbs != newSPS->picHeightInMbs) ||
                           (oldSPS->maxDpbSize != newSPS->maxDpbSize))
                        {
                            pStorage->dpb->flushed = 0;
                        }
                        else
                        {
                            h264bsdFlushDpb(pStorage->dpb);
                        }

                        pStorage->oldSpsId = pStorage->activeSpsId;

                        return(H264BSD_HDRS_RDY);
                    }
                }

                /* return error if second phase of
                 * initialization is not completed */
                if (pStorage->pendingActivation)
                {
                    EPRINT("Pending activation not completed");
                    return (H264BSD_ERROR);
                }
                tmp = h264bsdDecodeSliceHeader(&strm, pStorage->sliceHeader + 1,
                    pStorage->activeSps, pStorage->activePps, &nalUnit);
                if (tmp != HANTRO_OK)
                {
                    EPRINT("SLICE_HEADER");
                    return(H264BSD_ERROR);
                }
                if (h264bsdIsStartOfPicture(pStorage))
                {
                    if (!IS_IDR_NAL_UNIT(&nalUnit))
                    {
                        tmp = h264bsdCheckGapsInFrameNum(pStorage->dpb,
                            pStorage->sliceHeader[1].frameNum,
                            nalUnit.nalRefIdc != 0 ?
                            HANTRO_TRUE : HANTRO_FALSE,
                            pStorage->activeSps->
                            gapsInFrameNumValueAllowedFlag);
                        if (tmp != HANTRO_OK)
                        {
                            EPRINT("Gaps in frame num");
                            return(H264BSD_ERROR);
                        }
                    }
                    pStorage->currImage->data =
                        h264bsdAllocateDpbImage(pStorage->dpb);
                }

                /* store slice header to storage if successfully decoded */
                pStorage->sliceHeader[0] = pStorage->sliceHeader[1];
                pStorage->validSliceInAccessUnit = HANTRO_TRUE;
                pStorage->prevNalUnit[0] = nalUnit;

                h264bsdComputeSliceGroupMap(pStorage,
                    pStorage->sliceHeader->sliceGroupChangeCycle);

                h264bsdInitRefPicList(pStorage->dpb);
                tmp = h264bsdReorderRefPicList(pStorage->dpb,
                    &pStorage->sliceHeader->refPicListReordering,
                    pStorage->sliceHeader->frameNum,
                    pStorage->sliceHeader->numRefIdxL0Active);
                if (tmp != HANTRO_OK)
                {
                    EPRINT("Reordering");
                    return(H264BSD_ERROR);
                }

                DEBUG(("SLICE DATA, FIRST %d\n",
                        pStorage->sliceHeader->firstMbInSlice));
                tmp = h264bsdDecodeSliceData(&strm, pStorage,
                    pStorage->currImage, pStorage->sliceHeader);
                if (tmp != HANTRO_OK)
                {
                    EPRINT("SLICE_DATA");
                    h264bsdMarkSliceCorrupted(pStorage,
                        pStorage->sliceHeader->firstMbInSlice);
                    return(H264BSD_ERROR);
                }

                if (h264bsdIsEndOfPicture(pStorage))
                {
                    picReady = HANTRO_TRUE;
                    pStorage->skipRedundantSlices = HANTRO_TRUE;
                }
                break;

            case NAL_SEI:
                DEBUG(("SEI MESSAGE, NOT DECODED"));
                break;

            default:
                DEBUG(("NOT IMPLEMENTED YET %d\n",nalUnit.nalUnitType));
        }
    }

    if (picReady)
    {
        h264bsdFilterPicture(pStorage->currImage, pStorage->mb);

        h264bsdResetStorage(pStorage);

        picOrderCnt = h264bsdDecodePicOrderCnt(pStorage->poc,
            pStorage->activeSps, pStorage->sliceHeader, pStorage->prevNalUnit);

        if (pStorage->validSliceInAccessUnit)
        {
            if (pStorage->prevNalUnit->nalRefIdc)
            {
                tmp = h264bsdMarkDecRefPic(pStorage->dpb,
                    &pStorage->sliceHeader->decRefPicMarking,
                    pStorage->currImage, pStorage->sliceHeader->frameNum,
                    picOrderCnt,
                    IS_IDR_NAL_UNIT(pStorage->prevNalUnit) ?
                    HANTRO_TRUE : HANTRO_FALSE,
                    pStorage->currentPicId, pStorage->numConcealedMbs);
            }
            /* non-reference picture, just store for possible display
             * reordering */
            else
            {
                tmp = h264bsdMarkDecRefPic(pStorage->dpb, NULL,
                    pStorage->currImage, pStorage->sliceHeader->frameNum,
                    picOrderCnt,
                    IS_IDR_NAL_UNIT(pStorage->prevNalUnit) ?
                    HANTRO_TRUE : HANTRO_FALSE,
                    pStorage->currentPicId, pStorage->numConcealedMbs);
            }
        }

        pStorage->picStarted = HANTRO_FALSE;
        pStorage->validSliceInAccessUnit = HANTRO_FALSE;

        return(H264BSD_PIC_RDY);
    }
    else
        return(H264BSD_RDY);

}

/*------------------------------------------------------------------------------

    Function: h264bsdShutdown

        Functional description:
            Shutdown a decoder instance. Function frees all the memories
            allocated for the decoder instance.

        Inputs:
            pStorage    pointer to storage data structure

        Returns:
            none


------------------------------------------------------------------------------*/

void h264bsdShutdown(storage_t *pStorage)
{

/* Variables */

    u32 i;

/* Code */

    ASSERT(pStorage);

    for (i = 0; i < MAX_NUM_SEQ_PARAM_SETS; i++)
    {
        if (pStorage->sps[i])
        {
            FREE(pStorage->sps[i]->offsetForRefFrame);
            FREE(pStorage->sps[i]->vuiParameters);
            FREE(pStorage->sps[i]);
        }
    }

    for (i = 0; i < MAX_NUM_PIC_PARAM_SETS; i++)
    {
        if (pStorage->pps[i])
        {
            FREE(pStorage->pps[i]->runLength);
            FREE(pStorage->pps[i]->topLeft);
            FREE(pStorage->pps[i]->bottomRight);
            FREE(pStorage->pps[i]->sliceGroupId);
            FREE(pStorage->pps[i]);
        }
    }

    FREE(pStorage->mbLayer);
    FREE(pStorage->mb);
    FREE(pStorage->sliceGroupMap);

    h264bsdFreeDpb(pStorage->dpb);

}

/*------------------------------------------------------------------------------

    Function: h264bsdNextOutputPicture

        Functional description:
            Get next output picture in display order.

        Inputs:
            pStorage    pointer to storage data structure

        Outputs:
            picId       identifier of the picture will be stored here
            isIdrPic    IDR flag of the picture will be stored here
            numErrMbs   number of concealed macroblocks in the picture
                        will be stored here

        Returns:
            pointer to the picture data
            NULL if no pictures available for display

------------------------------------------------------------------------------*/

u8* h264bsdNextOutputPicture(storage_t *pStorage, u32 *picId, u32 *isIdrPic,
    u32 *numErrMbs)
{

/* Variables */

    dpbOutPicture_t *pOut;

/* Code */

    ASSERT(pStorage);

    pOut = h264bsdDpbOutputPicture(pStorage->dpb);

    if (pOut != NULL)
    {
        *picId = pOut->picId;
        *isIdrPic = pOut->isIdr;
        *numErrMbs = pOut->numErrMbs;
        return (pOut->data);
    }
    else
        return(NULL);

}

/*------------------------------------------------------------------------------

    Function: h264bsdPicWidth

        Functional description:
            Get width of the picture in macroblocks

        Inputs:
            pStorage    pointer to storage data structure

        Outputs:
            none

        Returns:
            picture width
            0 if parameters sets not yet activated

------------------------------------------------------------------------------*/

u32 h264bsdPicWidth(storage_t *pStorage)
{

/* Variables */

/* Code */

    ASSERT(pStorage);

    if (pStorage->activeSps)
        return(pStorage->activeSps->picWidthInMbs);
    else
        return(0);

}

/*------------------------------------------------------------------------------

    Function: h264bsdPicHeight

        Functional description:
            Get height of the picture in macroblocks

        Inputs:
            pStorage    pointer to storage data structure

        Outputs:
            none

        Returns:
            picture width
            0 if parameters sets not yet activated

------------------------------------------------------------------------------*/

u32 h264bsdPicHeight(storage_t *pStorage)
{

/* Variables */

/* Code */

    ASSERT(pStorage);

    if (pStorage->activeSps)
        return(pStorage->activeSps->picHeightInMbs);
    else
        return(0);

}

/*------------------------------------------------------------------------------

    Function: h264bsdFlushBuffer

        Functional description:
            Flush the decoded picture buffer, see dpb.c for details

        Inputs:
            pStorage    pointer to storage data structure

------------------------------------------------------------------------------*/

void h264bsdFlushBuffer(storage_t *pStorage)
{

/* Variables */

/* Code */

    ASSERT(pStorage);

    h264bsdFlushDpb(pStorage->dpb);

}

/*------------------------------------------------------------------------------

    Function: h264bsdCheckValidParamSets

        Functional description:
            Check if any valid parameter set combinations (SPS/PPS) exists.

        Inputs:
            pStorage    pointer to storage structure

        Returns:
            1       at least one valid SPS/PPS combination found
            0       no valid param set combinations found


------------------------------------------------------------------------------*/

u32 h264bsdCheckValidParamSets(storage_t *pStorage)
{

/* Variables */

/* Code */

    ASSERT(pStorage);

    return(h264bsdValidParamSets(pStorage) == HANTRO_OK ? 1 : 0);

}

/*------------------------------------------------------------------------------

    Function: h264bsdVideoRange

        Functional description:
            Get value of video_full_range_flag received in the VUI data.

        Inputs:
            pStorage    pointer to storage structure

        Returns:
            1   video_full_range_flag received and value is 1
            0   otherwise

------------------------------------------------------------------------------*/

u32 h264bsdVideoRange(storage_t *pStorage)
{

/* Variables */

/* Code */

    ASSERT(pStorage);

    if (pStorage->activeSps && pStorage->activeSps->vuiParametersPresentFlag &&
        pStorage->activeSps->vuiParameters &&
        pStorage->activeSps->vuiParameters->videoSignalTypePresentFlag &&
        pStorage->activeSps->vuiParameters->videoFullRangeFlag)
        return(1);
    else /* default value of video_full_range_flag is 0 */
        return(0);

}

/*------------------------------------------------------------------------------

    Function: h264bsdMatrixCoefficients

        Functional description:
            Get value of matrix_coefficients received in the VUI data

        Inputs:
            pStorage    pointer to storage structure

        Outputs:
            value of matrix_coefficients if received
            2   otherwise (this is the default value)

------------------------------------------------------------------------------*/

u32 h264bsdMatrixCoefficients(storage_t *pStorage)
{

/* Variables */

/* Code */

    ASSERT(pStorage);

    if (pStorage->activeSps && pStorage->activeSps->vuiParametersPresentFlag &&
        pStorage->activeSps->vuiParameters &&
        pStorage->activeSps->vuiParameters->videoSignalTypePresentFlag &&
        pStorage->activeSps->vuiParameters->colourDescriptionPresentFlag)
        return(pStorage->activeSps->vuiParameters->matrixCoefficients);
    else /* default unspecified */
        return(2);

}

/*------------------------------------------------------------------------------

    Function: hh264bsdCroppingParams

        Functional description:
            Get cropping parameters of the active SPS

        Inputs:
            pStorage    pointer to storage structure

        Outputs:
            croppingFlag    flag indicating if cropping params present is
                            stored here
            leftOffset      cropping left offset in pixels is stored here
            width           width of the image after cropping is stored here
            topOffset       cropping top offset in pixels is stored here
            height          height of the image after cropping is stored here

        Returns:
            none

------------------------------------------------------------------------------*/

void h264bsdCroppingParams(storage_t *pStorage, u32 *croppingFlag,
    u32 *leftOffset, u32 *width, u32 *topOffset, u32 *height)
{

/* Variables */

/* Code */

    ASSERT(pStorage);

    if (pStorage->activeSps && pStorage->activeSps->frameCroppingFlag)
    {
        *croppingFlag = 1;
        *leftOffset = 2 * pStorage->activeSps->frameCropLeftOffset;
        *width = 16 * pStorage->activeSps->picWidthInMbs -
                 2 * (pStorage->activeSps->frameCropLeftOffset +
                      pStorage->activeSps->frameCropRightOffset);
        *topOffset = 2 * pStorage->activeSps->frameCropTopOffset;
        *height = 16 * pStorage->activeSps->picHeightInMbs -
                  2 * (pStorage->activeSps->frameCropTopOffset +
                       pStorage->activeSps->frameCropBottomOffset);
    }
    else
    {
        *croppingFlag = 0;
        *leftOffset = 0;
        *width = 0;
        *topOffset = 0;
        *height = 0;
    }

}

/*------------------------------------------------------------------------------

    Function: h264bsdSampleAspectRatio

        Functional description:
            Get aspect ratio received in the VUI data

        Inputs:
            pStorage    pointer to storage structure

        Outputs:
            sarWidth    sample aspect ratio height
            sarHeight   sample aspect ratio width

------------------------------------------------------------------------------*/

void h264bsdSampleAspectRatio(storage_t *pStorage, u32 *sarWidth, u32 *sarHeight)
{

/* Variables */
    u32 w = 1;
    u32 h = 1;
/* Code */

    ASSERT(pStorage);


    if (pStorage->activeSps &&
        pStorage->activeSps->vuiParametersPresentFlag &&
        pStorage->activeSps->vuiParameters &&
        pStorage->activeSps->vuiParameters->aspectRatioPresentFlag )
    {
        switch (pStorage->activeSps->vuiParameters->aspectRatioIdc)
        {
            case ASPECT_RATIO_UNSPECIFIED:  w =   0; h =  0; break;
            case ASPECT_RATIO_1_1:          w =   1; h =  1; break;
            case ASPECT_RATIO_12_11:        w =  12; h = 11; break;
            case ASPECT_RATIO_10_11:        w =  10; h = 11; break;
            case ASPECT_RATIO_16_11:        w =  16; h = 11; break;
            case ASPECT_RATIO_40_33:        w =  40; h = 33; break;
            case ASPECT_RATIO_24_11:        w =  24; h = 11; break;
            case ASPECT_RATIO_20_11:        w =  20; h = 11; break;
            case ASPECT_RATIO_32_11:        w =  32; h = 11; break;
            case ASPECT_RATIO_80_33:        w =  80; h = 33; break;
            case ASPECT_RATIO_18_11:        w =  18; h = 11; break;
            case ASPECT_RATIO_15_11:        w =  15; h = 11; break;
            case ASPECT_RATIO_64_33:        w =  64; h = 33; break;
            case ASPECT_RATIO_160_99:       w = 160; h = 99; break;
            case ASPECT_RATIO_EXTENDED_SAR:
                w = pStorage->activeSps->vuiParameters->sarWidth;
                h = pStorage->activeSps->vuiParameters->sarHeight;
                if ((w == 0) || (h == 0))
                    w = h = 0;
                break;
            default:
                w = 0;
                h = 0;
                break;
        }
    }

    /* set aspect ratio*/
    *sarWidth = w;
    *sarHeight = h;

}

/*------------------------------------------------------------------------------

    Function: h264bsdProfile

        Functional description:
            Get profile information from active SPS

        Inputs:
            pStorage    pointer to storage structure

        Outputs:
            profile   current profile

------------------------------------------------------------------------------*/
u32 h264bsdProfile(storage_t *pStorage)
{
    if (pStorage->activeSps)
        return pStorage->activeSps->profileIdc;
    else
        return 0;
}

