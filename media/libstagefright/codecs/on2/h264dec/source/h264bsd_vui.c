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
          h264bsdDecodeVuiParameters
          DecodeHrdParameters

------------------------------------------------------------------------------*/

/*------------------------------------------------------------------------------
    1. Include headers
------------------------------------------------------------------------------*/

#include "h264bsd_vui.h"
#include "basetype.h"
#include "h264bsd_vlc.h"
#include "h264bsd_stream.h"
#include "h264bsd_util.h"

/*------------------------------------------------------------------------------
    2. External compiler flags
--------------------------------------------------------------------------------

--------------------------------------------------------------------------------
    3. Module defines
------------------------------------------------------------------------------*/

#define MAX_DPB_SIZE 16
#define MAX_BR       240000 /* for level 5.1 */
#define MAX_CPB      240000 /* for level 5.1 */

/*------------------------------------------------------------------------------
    4. Local function prototypes
------------------------------------------------------------------------------*/

static u32 DecodeHrdParameters(
  strmData_t *pStrmData,
  hrdParameters_t *pHrdParameters);

/*------------------------------------------------------------------------------

    Function: h264bsdDecodeVuiParameters

        Functional description:
            Decode VUI parameters from the stream. See standard for details.

        Inputs:
            pStrmData       pointer to stream data structure

        Outputs:
            pVuiParameters  decoded information is stored here

        Returns:
            HANTRO_OK       success
            HANTRO_NOK      invalid stream data or end of stream

------------------------------------------------------------------------------*/

u32 h264bsdDecodeVuiParameters(strmData_t *pStrmData,
    vuiParameters_t *pVuiParameters)
{

/* Variables */

    u32 tmp;

/* Code */

    ASSERT(pStrmData);
    ASSERT(pVuiParameters);

    H264SwDecMemset(pVuiParameters, 0, sizeof(vuiParameters_t));

    tmp = h264bsdGetBits(pStrmData, 1);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);
    pVuiParameters->aspectRatioPresentFlag = (tmp == 1) ?
                                HANTRO_TRUE : HANTRO_FALSE;

    if (pVuiParameters->aspectRatioPresentFlag)
    {
        tmp = h264bsdGetBits(pStrmData, 8);
        if (tmp == END_OF_STREAM)
            return(HANTRO_NOK);
        pVuiParameters->aspectRatioIdc = tmp;

        if (pVuiParameters->aspectRatioIdc == ASPECT_RATIO_EXTENDED_SAR)
        {
            tmp = h264bsdGetBits(pStrmData, 16);
            if (tmp == END_OF_STREAM)
                return(HANTRO_NOK);
            pVuiParameters->sarWidth = tmp;

            tmp = h264bsdGetBits(pStrmData, 16);
            if (tmp == END_OF_STREAM)
                return(HANTRO_NOK);
            pVuiParameters->sarHeight = tmp;
        }
    }

    tmp = h264bsdGetBits(pStrmData, 1);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);
    pVuiParameters->overscanInfoPresentFlag = (tmp == 1) ?
                                HANTRO_TRUE : HANTRO_FALSE;

    if (pVuiParameters->overscanInfoPresentFlag)
    {
        tmp = h264bsdGetBits(pStrmData, 1);
        if (tmp == END_OF_STREAM)
            return(HANTRO_NOK);
        pVuiParameters->overscanAppropriateFlag = (tmp == 1) ?
                                HANTRO_TRUE : HANTRO_FALSE;
    }

    tmp = h264bsdGetBits(pStrmData, 1);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);
    pVuiParameters->videoSignalTypePresentFlag = (tmp == 1) ?
                                HANTRO_TRUE : HANTRO_FALSE;

    if (pVuiParameters->videoSignalTypePresentFlag)
    {
        tmp = h264bsdGetBits(pStrmData, 3);
        if (tmp == END_OF_STREAM)
            return(HANTRO_NOK);
        pVuiParameters->videoFormat = tmp;

        tmp = h264bsdGetBits(pStrmData, 1);
        if (tmp == END_OF_STREAM)
            return(HANTRO_NOK);
        pVuiParameters->videoFullRangeFlag = (tmp == 1) ?
                                HANTRO_TRUE : HANTRO_FALSE;

        tmp = h264bsdGetBits(pStrmData, 1);
        if (tmp == END_OF_STREAM)
            return(HANTRO_NOK);
        pVuiParameters->colourDescriptionPresentFlag =
            (tmp == 1) ? HANTRO_TRUE : HANTRO_FALSE;

        if (pVuiParameters->colourDescriptionPresentFlag)
        {
            tmp = h264bsdGetBits(pStrmData, 8);
            if (tmp == END_OF_STREAM)
                return(HANTRO_NOK);
            pVuiParameters->colourPrimaries = tmp;

            tmp = h264bsdGetBits(pStrmData, 8);
            if (tmp == END_OF_STREAM)
                return(HANTRO_NOK);
            pVuiParameters->transferCharacteristics = tmp;

            tmp = h264bsdGetBits(pStrmData, 8);
            if (tmp == END_OF_STREAM)
                return(HANTRO_NOK);
            pVuiParameters->matrixCoefficients = tmp;
        }
        else
        {
            pVuiParameters->colourPrimaries         = 2;
            pVuiParameters->transferCharacteristics = 2;
            pVuiParameters->matrixCoefficients      = 2;
        }
    }
    else
    {
        pVuiParameters->videoFormat             = 5;
        pVuiParameters->colourPrimaries         = 2;
        pVuiParameters->transferCharacteristics = 2;
        pVuiParameters->matrixCoefficients      = 2;
    }

    tmp = h264bsdGetBits(pStrmData, 1);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);
    pVuiParameters->chromaLocInfoPresentFlag =
        (tmp == 1) ? HANTRO_TRUE : HANTRO_FALSE;

    if (pVuiParameters->chromaLocInfoPresentFlag)
    {
        tmp = h264bsdDecodeExpGolombUnsigned(pStrmData,
          &pVuiParameters->chromaSampleLocTypeTopField);
        if (tmp != HANTRO_OK)
            return(tmp);
        if (pVuiParameters->chromaSampleLocTypeTopField > 5)
            return(HANTRO_NOK);

        tmp = h264bsdDecodeExpGolombUnsigned(pStrmData,
          &pVuiParameters->chromaSampleLocTypeBottomField);
        if (tmp != HANTRO_OK)
            return(tmp);
        if (pVuiParameters->chromaSampleLocTypeBottomField > 5)
            return(HANTRO_NOK);
    }

    tmp = h264bsdGetBits(pStrmData, 1);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);
    pVuiParameters->timingInfoPresentFlag =
        (tmp == 1) ? HANTRO_TRUE : HANTRO_FALSE;

    if (pVuiParameters->timingInfoPresentFlag)
    {
        tmp = h264bsdShowBits32(pStrmData);
        if (h264bsdFlushBits(pStrmData, 32) == END_OF_STREAM)
            return(HANTRO_NOK);
        if (tmp == 0)
            return(HANTRO_NOK);
        pVuiParameters->numUnitsInTick = tmp;

        tmp = h264bsdShowBits32(pStrmData);
        if (h264bsdFlushBits(pStrmData, 32) == END_OF_STREAM)
            return(HANTRO_NOK);
        if (tmp == 0)
            return(HANTRO_NOK);
        pVuiParameters->timeScale = tmp;

        tmp = h264bsdGetBits(pStrmData, 1);
        if (tmp == END_OF_STREAM)
            return(HANTRO_NOK);
        pVuiParameters->fixedFrameRateFlag =
            (tmp == 1) ? HANTRO_TRUE : HANTRO_FALSE;
    }

    tmp = h264bsdGetBits(pStrmData, 1);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);
    pVuiParameters->nalHrdParametersPresentFlag =
        (tmp == 1) ? HANTRO_TRUE : HANTRO_FALSE;

    if (pVuiParameters->nalHrdParametersPresentFlag)
    {
        tmp = DecodeHrdParameters(pStrmData, &pVuiParameters->nalHrdParameters);
        if (tmp != HANTRO_OK)
            return(tmp);
    }
    else
    {
        pVuiParameters->nalHrdParameters.cpbCnt          = 1;
        /* MaxBR and MaxCPB should be the values correspondig to the levelIdc
         * in the SPS containing these VUI parameters. However, these values
         * are not used anywhere and maximum for any level will be used here */
        pVuiParameters->nalHrdParameters.bitRateValue[0] = 1200 * MAX_BR + 1;
        pVuiParameters->nalHrdParameters.cpbSizeValue[0] = 1200 * MAX_CPB + 1;
        pVuiParameters->nalHrdParameters.initialCpbRemovalDelayLength = 24;
        pVuiParameters->nalHrdParameters.cpbRemovalDelayLength        = 24;
        pVuiParameters->nalHrdParameters.dpbOutputDelayLength         = 24;
        pVuiParameters->nalHrdParameters.timeOffsetLength             = 24;
    }

    tmp = h264bsdGetBits(pStrmData, 1);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);
    pVuiParameters->vclHrdParametersPresentFlag =
        (tmp == 1) ? HANTRO_TRUE : HANTRO_FALSE;

    if (pVuiParameters->vclHrdParametersPresentFlag)
    {
        tmp = DecodeHrdParameters(pStrmData, &pVuiParameters->vclHrdParameters);
        if (tmp != HANTRO_OK)
            return(tmp);
    }
    else
    {
        pVuiParameters->vclHrdParameters.cpbCnt          = 1;
        /* MaxBR and MaxCPB should be the values correspondig to the levelIdc
         * in the SPS containing these VUI parameters. However, these values
         * are not used anywhere and maximum for any level will be used here */
        pVuiParameters->vclHrdParameters.bitRateValue[0] = 1000 * MAX_BR + 1;
        pVuiParameters->vclHrdParameters.cpbSizeValue[0] = 1000 * MAX_CPB + 1;
        pVuiParameters->vclHrdParameters.initialCpbRemovalDelayLength = 24;
        pVuiParameters->vclHrdParameters.cpbRemovalDelayLength        = 24;
        pVuiParameters->vclHrdParameters.dpbOutputDelayLength         = 24;
        pVuiParameters->vclHrdParameters.timeOffsetLength             = 24;
    }

    if (pVuiParameters->nalHrdParametersPresentFlag ||
      pVuiParameters->vclHrdParametersPresentFlag)
    {
        tmp = h264bsdGetBits(pStrmData, 1);
        if (tmp == END_OF_STREAM)
            return(HANTRO_NOK);
        pVuiParameters->lowDelayHrdFlag =
            (tmp == 1) ? HANTRO_TRUE : HANTRO_FALSE;
    }

    tmp = h264bsdGetBits(pStrmData, 1);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);
    pVuiParameters->picStructPresentFlag =
        (tmp == 1) ? HANTRO_TRUE : HANTRO_FALSE;

    tmp = h264bsdGetBits(pStrmData, 1);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);
    pVuiParameters->bitstreamRestrictionFlag =
        (tmp == 1) ? HANTRO_TRUE : HANTRO_FALSE;

    if (pVuiParameters->bitstreamRestrictionFlag)
    {
        tmp = h264bsdGetBits(pStrmData, 1);
        if (tmp == END_OF_STREAM)
            return(HANTRO_NOK);
        pVuiParameters->motionVectorsOverPicBoundariesFlag =
            (tmp == 1) ? HANTRO_TRUE : HANTRO_FALSE;

        tmp = h264bsdDecodeExpGolombUnsigned(pStrmData,
          &pVuiParameters->maxBytesPerPicDenom);
        if (tmp != HANTRO_OK)
            return(tmp);
        if (pVuiParameters->maxBytesPerPicDenom > 16)
            return(HANTRO_NOK);

        tmp = h264bsdDecodeExpGolombUnsigned(pStrmData,
          &pVuiParameters->maxBitsPerMbDenom);
        if (tmp != HANTRO_OK)
            return(tmp);
        if (pVuiParameters->maxBitsPerMbDenom > 16)
            return(HANTRO_NOK);

        tmp = h264bsdDecodeExpGolombUnsigned(pStrmData,
          &pVuiParameters->log2MaxMvLengthHorizontal);
        if (tmp != HANTRO_OK)
            return(tmp);
        if (pVuiParameters->log2MaxMvLengthHorizontal > 16)
            return(HANTRO_NOK);

        tmp = h264bsdDecodeExpGolombUnsigned(pStrmData,
          &pVuiParameters->log2MaxMvLengthVertical);
        if (tmp != HANTRO_OK)
            return(tmp);
        if (pVuiParameters->log2MaxMvLengthVertical > 16)
            return(HANTRO_NOK);

        tmp = h264bsdDecodeExpGolombUnsigned(pStrmData,
          &pVuiParameters->numReorderFrames);
        if (tmp != HANTRO_OK)
            return(tmp);

        tmp = h264bsdDecodeExpGolombUnsigned(pStrmData,
          &pVuiParameters->maxDecFrameBuffering);
        if (tmp != HANTRO_OK)
            return(tmp);
    }
    else
    {
        pVuiParameters->motionVectorsOverPicBoundariesFlag = HANTRO_TRUE;
        pVuiParameters->maxBytesPerPicDenom       = 2;
        pVuiParameters->maxBitsPerMbDenom         = 1;
        pVuiParameters->log2MaxMvLengthHorizontal = 16;
        pVuiParameters->log2MaxMvLengthVertical   = 16;
        pVuiParameters->numReorderFrames          = MAX_DPB_SIZE;
        pVuiParameters->maxDecFrameBuffering      = MAX_DPB_SIZE;
    }

    return(HANTRO_OK);

}

/*------------------------------------------------------------------------------

    Function: DecodeHrdParameters

        Functional description:
            Decode HRD parameters from the stream. See standard for details.

        Inputs:
            pStrmData       pointer to stream data structure

        Outputs:
            pHrdParameters  decoded information is stored here

        Returns:
            HANTRO_OK       success
            HANTRO_NOK      invalid stream data

------------------------------------------------------------------------------*/

static u32 DecodeHrdParameters(
  strmData_t *pStrmData,
  hrdParameters_t *pHrdParameters)
{

/* Variables */

    u32 tmp, i;

/* Code */

    ASSERT(pStrmData);
    ASSERT(pHrdParameters);


    tmp = h264bsdDecodeExpGolombUnsigned(pStrmData, &pHrdParameters->cpbCnt);
    if (tmp != HANTRO_OK)
        return(tmp);
    /* cpbCount = cpb_cnt_minus1 + 1 */
    pHrdParameters->cpbCnt++;
    if (pHrdParameters->cpbCnt > MAX_CPB_CNT)
        return(HANTRO_NOK);

    tmp = h264bsdGetBits(pStrmData, 4);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);
    pHrdParameters->bitRateScale = tmp;

    tmp = h264bsdGetBits(pStrmData, 4);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);
    pHrdParameters->cpbSizeScale = tmp;

    for (i = 0; i < pHrdParameters->cpbCnt; i++)
    {
        /* bit_rate_value_minus1 in the range [0, 2^32 - 2] */
        tmp = h264bsdDecodeExpGolombUnsigned(pStrmData,
          &pHrdParameters->bitRateValue[i]);
        if (tmp != HANTRO_OK)
            return(tmp);
        if (pHrdParameters->bitRateValue[i] > 4294967294U)
            return(HANTRO_NOK);
        pHrdParameters->bitRateValue[i]++;
        /* this may result in overflow, but this value is not used for
         * anything */
        pHrdParameters->bitRateValue[i] *=
            1 << (6 + pHrdParameters->bitRateScale);

        /* cpb_size_value_minus1 in the range [0, 2^32 - 2] */
        tmp = h264bsdDecodeExpGolombUnsigned(pStrmData,
          &pHrdParameters->cpbSizeValue[i]);
        if (tmp != HANTRO_OK)
            return(tmp);
        if (pHrdParameters->cpbSizeValue[i] > 4294967294U)
            return(HANTRO_NOK);
        pHrdParameters->cpbSizeValue[i]++;
        /* this may result in overflow, but this value is not used for
         * anything */
        pHrdParameters->cpbSizeValue[i] *=
            1 << (4 + pHrdParameters->cpbSizeScale);

        tmp = h264bsdGetBits(pStrmData, 1);
        if (tmp == END_OF_STREAM)
            return(HANTRO_NOK);
        pHrdParameters->cbrFlag[i] = (tmp == 1) ? HANTRO_TRUE : HANTRO_FALSE;
    }

    tmp = h264bsdGetBits(pStrmData, 5);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);
    pHrdParameters->initialCpbRemovalDelayLength = tmp + 1;

    tmp = h264bsdGetBits(pStrmData, 5);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);
    pHrdParameters->cpbRemovalDelayLength = tmp + 1;

    tmp = h264bsdGetBits(pStrmData, 5);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);
    pHrdParameters->dpbOutputDelayLength = tmp + 1;

    tmp = h264bsdGetBits(pStrmData, 5);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);
    pHrdParameters->timeOffsetLength = tmp;

    return(HANTRO_OK);

}

