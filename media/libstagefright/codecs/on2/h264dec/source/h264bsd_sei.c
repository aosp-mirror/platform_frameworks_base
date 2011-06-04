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
          h264bsdDecodeSeiMessage
          DecodeBufferingPeriod
          DecodePictureTiming
          DecodePanScanRectangle
          DecodeFillerPayload
          DecodeUserDataRegisteredITuTT35
          DecodeUserDataUnregistered
          DecodeRecoveryPoint
          DecodeDecRefPicMarkingRepetition
          DecodeSparePic
          DecodeSceneInfo
          DecodeSubSeqInfo
          DecodeSubSeqLayerCharacteristics
          DecodeSubSeqCharacteristics
          DecodeFullFrameFreeze
          DecodeFullFrameSnapshot
          DecodeProgressiveRefinementSegmentStart
          DecodeProgressiveRefinementSegmentEnd
          DecodeMotionConstrainedSliceGroupSet
          DecodeReservedSeiMessage

------------------------------------------------------------------------------*/

/*------------------------------------------------------------------------------
    1. Include headers
------------------------------------------------------------------------------*/

#include "h264bsd_sei.h"
#include "basetype.h"
#include "h264bsd_util.h"
#include "h264bsd_stream.h"
#include "h264bsd_vlc.h"
#include "h264bsd_seq_param_set.h"
#include "h264bsd_slice_header.h"

/*------------------------------------------------------------------------------
    2. External compiler flags
--------------------------------------------------------------------------------

--------------------------------------------------------------------------------
    3. Module defines
------------------------------------------------------------------------------*/

static const u32 numClockTS[9] = {1,1,1,2,2,3,3,2,3};
static const u32 ceilLog2NumSliceGroups[9] = {0,1,1,2,2,3,3,3,3};

/*------------------------------------------------------------------------------
    4. Local function prototypes
------------------------------------------------------------------------------*/

static u32 DecodeBufferingPeriod(
  strmData_t *pStrmData,
  seiBufferingPeriod_t *pBufferingPeriod,
  u32 cpbCnt,
  u32 initialCpbRemovalDelayLength,
  u32 nalHrdBpPresentFlag,
  u32 vclHrdBpPresentFlag);

static u32 DecodePictureTiming(
  strmData_t *pStrmData,
  seiPicTiming_t *pPicTiming,
  u32 cpbRemovalDelayLength,
  u32 dpbOutputDelayLength,
  u32 timeOffsetLength,
  u32 cpbDpbDelaysPresentFlag,
  u32 picStructPresentFlag);

static u32 DecodePanScanRectangle(
  strmData_t *pStrmData,
  seiPanScanRect_t *pPanScanRectangle);

static u32 DecodeFillerPayload(strmData_t *pStrmData, u32 payloadSize);

static u32 DecodeUserDataRegisteredITuTT35(
  strmData_t *pStrmData,
  seiUserDataRegisteredItuTT35_t *pUserDataRegisteredItuTT35,
  u32 payloadSize);

static u32 DecodeUserDataUnregistered(
  strmData_t *pStrmData,
  seiUserDataUnregistered_t *pUserDataUnregistered,
  u32 payloadSize);

static u32 DecodeRecoveryPoint(
  strmData_t *pStrmData,
  seiRecoveryPoint_t *pRecoveryPoint);

static u32 DecodeDecRefPicMarkingRepetition(
  strmData_t *pStrmData,
  seiDecRefPicMarkingRepetition_t *pDecRefPicMarkingRepetition,
  u32 numRefFrames);

static u32 DecodeSparePic(
  strmData_t *pStrmData,
  seiSparePic_t *pSparePic,
  u32 picSizeInMapUnits);

static u32 DecodeSceneInfo(
  strmData_t *pStrmData,
  seiSceneInfo_t *pSceneInfo);

static u32 DecodeSubSeqInfo(
  strmData_t *pStrmData,
  seiSubSeqInfo_t *pSubSeqInfo);

static u32 DecodeSubSeqLayerCharacteristics(
  strmData_t *pStrmData,
  seiSubSeqLayerCharacteristics_t *pSubSeqLayerCharacteristics);

static u32 DecodeSubSeqCharacteristics(
  strmData_t *pStrmData,
  seiSubSeqCharacteristics_t *pSubSeqCharacteristics);

static u32 DecodeFullFrameFreeze(
  strmData_t *pStrmData,
  seiFullFrameFreeze_t *pFullFrameFreeze);

static u32 DecodeFullFrameSnapshot(
  strmData_t *pStrmData,
  seiFullFrameSnapshot_t *pFullFrameSnapshot);

static u32 DecodeProgressiveRefinementSegmentStart(
  strmData_t *pStrmData,
  seiProgressiveRefinementSegmentStart_t *pProgressiveRefinementSegmentStart);

static u32 DecodeProgressiveRefinementSegmentEnd(
  strmData_t *pStrmData,
  seiProgressiveRefinementSegmentEnd_t *pProgressiveRefinementSegmentEnd);

static u32 DecodeMotionConstrainedSliceGroupSet(
  strmData_t *pStrmData,
  seiMotionConstrainedSliceGroupSet_t *pMotionConstrainedSliceGroupSet,
  u32 numSliceGroups);

static u32 DecodeReservedSeiMessage(
  strmData_t *pStrmData,
  seiReservedSeiMessage_t *pReservedSeiMessage,
  u32 payloadSize);

/*------------------------------------------------------------------------------

    Function: h264bsdDecodeSeiMessage

        Functional description:
          <++>
        Inputs:
          <++>
        Outputs:
          <++>

------------------------------------------------------------------------------*/

u32 h264bsdDecodeSeiMessage(
  strmData_t *pStrmData,
  seqParamSet_t *pSeqParamSet,
  seiMessage_t *pSeiMessage,
  u32 numSliceGroups)
{

/* Variables */

    u32 tmp, payloadType, payloadSize, status;

/* Code */

    ASSERT(pStrmData);
    ASSERT(pSeiMessage);


    H264SwDecMemset(pSeiMessage, 0, sizeof(seiMessage_t));

    do
    {
        payloadType = 0;
        while((tmp = h264bsdGetBits(pStrmData, 8)) == 0xFF)
        {
            payloadType += 255;
                    }
        if (tmp == END_OF_STREAM)
            return(HANTRO_NOK);
        payloadType += tmp;

        payloadSize = 0;
        while((tmp = h264bsdGetBits(pStrmData, 8)) == 0xFF)
        {
            payloadSize += 255;
        }
        if (tmp == END_OF_STREAM)
            return(HANTRO_NOK);
        payloadSize += tmp;

        pSeiMessage->payloadType = payloadType;

        switch (payloadType)
        {
            case 0:
                ASSERT(pSeqParamSet);
                status = DecodeBufferingPeriod(
                  pStrmData,
                  &pSeiMessage->bufferingPeriod,
                  pSeqParamSet->vuiParameters->vclHrdParameters.cpbCnt,
                  pSeqParamSet->vuiParameters->vclHrdParameters.
                  initialCpbRemovalDelayLength,
                  pSeqParamSet->vuiParameters->nalHrdParametersPresentFlag,
                  pSeqParamSet->vuiParameters->vclHrdParametersPresentFlag);
                break;

            case 1:
                ASSERT(pSeqParamSet->vuiParametersPresentFlag);
                status = DecodePictureTiming(
                  pStrmData,
                  &pSeiMessage->picTiming,
                  pSeqParamSet->vuiParameters->vclHrdParameters.
                      cpbRemovalDelayLength,
                  pSeqParamSet->vuiParameters->vclHrdParameters.
                      dpbOutputDelayLength,
                  pSeqParamSet->vuiParameters->vclHrdParameters.
                    timeOffsetLength,
                  pSeqParamSet->vuiParameters->nalHrdParametersPresentFlag ||
                  pSeqParamSet->vuiParameters->vclHrdParametersPresentFlag ?
                  HANTRO_TRUE : HANTRO_FALSE,
                  pSeqParamSet->vuiParameters->picStructPresentFlag);
                break;

            case 2:
                status = DecodePanScanRectangle(
                  pStrmData,
                  &pSeiMessage->panScanRect);
                break;

            case 3:
                status = DecodeFillerPayload(pStrmData, payloadSize);
                break;

            case 4:
                status = DecodeUserDataRegisteredITuTT35(
                  pStrmData,
                  &pSeiMessage->userDataRegisteredItuTT35,
                  payloadSize);
                break;

            case 5:
                status = DecodeUserDataUnregistered(
                  pStrmData,
                  &pSeiMessage->userDataUnregistered,
                  payloadSize);
                break;

            case 6:
                status = DecodeRecoveryPoint(
                  pStrmData,
                  &pSeiMessage->recoveryPoint);
                break;

            case 7:
                status = DecodeDecRefPicMarkingRepetition(
                  pStrmData,
                  &pSeiMessage->decRefPicMarkingRepetition,
                  pSeqParamSet->numRefFrames);
                break;

            case 8:
                ASSERT(pSeqParamSet);
                status = DecodeSparePic(
                  pStrmData,
                  &pSeiMessage->sparePic,
                  pSeqParamSet->picWidthInMbs * pSeqParamSet->picHeightInMbs);
                break;

            case 9:
                status = DecodeSceneInfo(
                  pStrmData,
                  &pSeiMessage->sceneInfo);
                break;

            case 10:
                status = DecodeSubSeqInfo(
                  pStrmData,
                  &pSeiMessage->subSeqInfo);
                break;

            case 11:
                status = DecodeSubSeqLayerCharacteristics(
                  pStrmData,
                  &pSeiMessage->subSeqLayerCharacteristics);
                break;

            case 12:
                status = DecodeSubSeqCharacteristics(
                  pStrmData,
                  &pSeiMessage->subSeqCharacteristics);
                break;

            case 13:
                status = DecodeFullFrameFreeze(
                  pStrmData,
                  &pSeiMessage->fullFrameFreeze);
                break;

            case 14: /* This SEI does not contain data, what to do ??? */
                status = HANTRO_OK;
                break;

            case 15:
                status = DecodeFullFrameSnapshot(
                  pStrmData,
                  &pSeiMessage->fullFrameSnapshot);
                break;

            case 16:
                status = DecodeProgressiveRefinementSegmentStart(
                  pStrmData,
                  &pSeiMessage->progressiveRefinementSegmentStart);
                break;

            case 17:
                status = DecodeProgressiveRefinementSegmentEnd(
                  pStrmData,
                  &pSeiMessage->progressiveRefinementSegmentEnd);
                break;

            case 18:
                ASSERT(numSliceGroups);
                status = DecodeMotionConstrainedSliceGroupSet(
                  pStrmData,
                  &pSeiMessage->motionConstrainedSliceGroupSet,
                  numSliceGroups);
                break;

            default:
                status = DecodeReservedSeiMessage(
                  pStrmData,
                  &pSeiMessage->reservedSeiMessage,
                  payloadSize);
                break;
        }

        if (status != HANTRO_OK)
            return(status);

        while (!h264bsdIsByteAligned(pStrmData))
        {
            if (h264bsdGetBits(pStrmData, 1) != 1)
                return(HANTRO_NOK);
            while (!h264bsdIsByteAligned(pStrmData))
            {
                if (h264bsdGetBits(pStrmData, 1) != 0)
                    return(HANTRO_NOK);
            }
        }
    } while (h264bsdMoreRbspData(pStrmData));

    return(h264bsdRbspTrailingBits(pStrmData));

}

/*------------------------------------------------------------------------------

    Function: DecodeBufferingPeriod

        Functional description:
          <++>
        Inputs:
          <++>
        Outputs:
          <++>

------------------------------------------------------------------------------*/

static u32 DecodeBufferingPeriod(
  strmData_t *pStrmData,
  seiBufferingPeriod_t *pBufferingPeriod,
  u32 cpbCnt,
  u32 initialCpbRemovalDelayLength,
  u32 nalHrdBpPresentFlag,
  u32 vclHrdBpPresentFlag)
{

/* Variables */

    u32 tmp, i;

/* Code */

    ASSERT(pStrmData);
    ASSERT(pBufferingPeriod);
    ASSERT(cpbCnt);
    ASSERT(initialCpbRemovalDelayLength);


    tmp = h264bsdDecodeExpGolombUnsigned(pStrmData,
      &pBufferingPeriod->seqParameterSetId);
    if (tmp != HANTRO_OK)
        return(tmp);
    if (pBufferingPeriod->seqParameterSetId > 31)
        return(HANTRO_NOK);

    if (nalHrdBpPresentFlag)
    {
        for (i = 0; i < cpbCnt; i++)
        {
            tmp = h264bsdGetBits(pStrmData, initialCpbRemovalDelayLength);
            if (tmp == END_OF_STREAM)
                return(HANTRO_NOK);
            if (tmp == 0)
                return(HANTRO_NOK);
            pBufferingPeriod->initialCpbRemovalDelay[i] = tmp;

            tmp = h264bsdGetBits(pStrmData, initialCpbRemovalDelayLength);
            if (tmp == END_OF_STREAM)
                return(HANTRO_NOK);
            pBufferingPeriod->initialCpbRemovalDelayOffset[i] = tmp;
        }
    }

    if (vclHrdBpPresentFlag)
    {
        for (i = 0; i < cpbCnt; i++)
        {
            tmp = h264bsdGetBits(pStrmData, initialCpbRemovalDelayLength);
            if (tmp == END_OF_STREAM)
                return(HANTRO_NOK);
            pBufferingPeriod->initialCpbRemovalDelay[i] = tmp;

            tmp = h264bsdGetBits(pStrmData, initialCpbRemovalDelayLength);
            if (tmp == END_OF_STREAM)
                return(HANTRO_NOK);
            pBufferingPeriod->initialCpbRemovalDelayOffset[i] = tmp;
        }
    }

    return(HANTRO_OK);

}

/*------------------------------------------------------------------------------

    Function: DecodePictureTiming

        Functional description:
          <++>
        Inputs:
          <++>
        Outputs:
          <++>

------------------------------------------------------------------------------*/

static u32 DecodePictureTiming(
  strmData_t *pStrmData,
  seiPicTiming_t *pPicTiming,
  u32 cpbRemovalDelayLength,
  u32 dpbOutputDelayLength,
  u32 timeOffsetLength,
  u32 cpbDpbDelaysPresentFlag,
  u32 picStructPresentFlag)
{

/* Variables */

    u32 tmp, i;
    i32 itmp;

/* Code */

    ASSERT(pStrmData);
    ASSERT(pPicTiming);


    if (cpbDpbDelaysPresentFlag)
    {
        tmp = h264bsdGetBits(pStrmData, cpbRemovalDelayLength);
        if (tmp == END_OF_STREAM)
            return(HANTRO_NOK);
        pPicTiming->cpbRemovalDelay = tmp;

        tmp = h264bsdGetBits(pStrmData, dpbOutputDelayLength);
        if (tmp == END_OF_STREAM)
            return(HANTRO_NOK);
        pPicTiming->dpbOutputDelay = tmp;
    }

    if (picStructPresentFlag)
    {
        tmp = h264bsdGetBits(pStrmData, 4);
        if (tmp == END_OF_STREAM)
            return(HANTRO_NOK);
        if (tmp > 8)
            return(HANTRO_NOK);
        pPicTiming->picStruct = tmp;

        for (i = 0; i < numClockTS[pPicTiming->picStruct]; i++)
        {
            tmp = h264bsdGetBits(pStrmData, 1);
            if (tmp == END_OF_STREAM)
                return(HANTRO_NOK);
            pPicTiming->clockTimeStampFlag[i] = tmp == 1 ?
                                    HANTRO_TRUE : HANTRO_FALSE;

            if (pPicTiming->clockTimeStampFlag[i])
            {
                tmp = h264bsdGetBits(pStrmData, 2);
                if (tmp == END_OF_STREAM)
                    return(HANTRO_NOK);
                pPicTiming->ctType[i] = tmp;

                tmp = h264bsdGetBits(pStrmData, 1);
                if (tmp == END_OF_STREAM)
                    return(HANTRO_NOK);
                pPicTiming->nuitFieldBasedFlag[i] = tmp == 1 ?
                                    HANTRO_TRUE : HANTRO_FALSE;

                tmp = h264bsdGetBits(pStrmData, 5);
                if (tmp == END_OF_STREAM)
                    return(HANTRO_NOK);
                if (tmp > 6)
                    return(HANTRO_NOK);
                pPicTiming->countingType[i] = tmp;

                tmp = h264bsdGetBits(pStrmData, 1);
                if (tmp == END_OF_STREAM)
                    return(HANTRO_NOK);
                pPicTiming->fullTimeStampFlag[i] = tmp == 1 ?
                                    HANTRO_TRUE : HANTRO_FALSE;

                tmp = h264bsdGetBits(pStrmData, 1);
                if (tmp == END_OF_STREAM)
                    return(HANTRO_NOK);
                pPicTiming->discontinuityFlag[i] = tmp == 1 ?
                                    HANTRO_TRUE : HANTRO_FALSE;

                tmp = h264bsdGetBits(pStrmData, 1);
                if (tmp == END_OF_STREAM)
                    return(HANTRO_NOK);
                pPicTiming->cntDroppedFlag[i] = tmp == 1 ?
                                    HANTRO_TRUE : HANTRO_FALSE;

                tmp = h264bsdGetBits(pStrmData, 8);
                if (tmp == END_OF_STREAM)
                    return(HANTRO_NOK);
                pPicTiming->nFrames[i] = tmp;

                if (pPicTiming->fullTimeStampFlag[i])
                {
                    tmp = h264bsdGetBits(pStrmData, 6);
                    if (tmp == END_OF_STREAM)
                        return(HANTRO_NOK);
                    if (tmp > 59)
                        return(HANTRO_NOK);
                    pPicTiming->secondsValue[i] = tmp;

                    tmp = h264bsdGetBits(pStrmData, 6);
                    if (tmp == END_OF_STREAM)
                        return(HANTRO_NOK);
                    if (tmp > 59)
                        return(HANTRO_NOK);
                    pPicTiming->minutesValue[i] = tmp;

                    tmp = h264bsdGetBits(pStrmData, 5);
                    if (tmp == END_OF_STREAM)
                        return(HANTRO_NOK);
                    if (tmp > 23)
                        return(HANTRO_NOK);
                    pPicTiming->hoursValue[i] = tmp;
                }
                else
                {
                    tmp = h264bsdGetBits(pStrmData, 1);
                    if (tmp == END_OF_STREAM)
                        return(HANTRO_NOK);
                    pPicTiming->secondsFlag[i] = tmp == 1 ?
                                    HANTRO_TRUE : HANTRO_FALSE;

                    if (pPicTiming->secondsFlag[i])
                    {
                        tmp = h264bsdGetBits(pStrmData, 6);
                        if (tmp == END_OF_STREAM)
                            return(HANTRO_NOK);
                        if (tmp > 59)
                            return(HANTRO_NOK);
                        pPicTiming->secondsValue[i] = tmp;

                        tmp = h264bsdGetBits(pStrmData, 1);
                        if (tmp == END_OF_STREAM)
                            return(HANTRO_NOK);
                        pPicTiming->minutesFlag[i] = tmp == 1 ?
                                    HANTRO_TRUE : HANTRO_FALSE;

                        if (pPicTiming->minutesFlag[i])
                        {
                            tmp = h264bsdGetBits(pStrmData, 6);
                            if (tmp == END_OF_STREAM)
                                return(HANTRO_NOK);
                            if (tmp > 59)
                                return(HANTRO_NOK);
                            pPicTiming->minutesValue[i] = tmp;

                            tmp = h264bsdGetBits(pStrmData, 1);
                            if (tmp == END_OF_STREAM)
                                return(HANTRO_NOK);
                            pPicTiming->hoursFlag[i] = tmp == 1 ?
                                    HANTRO_TRUE : HANTRO_FALSE;

                            if (pPicTiming->hoursFlag[i])
                            {
                                tmp = h264bsdGetBits(pStrmData, 5);
                                if (tmp == END_OF_STREAM)
                                    return(HANTRO_NOK);
                                if (tmp > 23)
                                    return(HANTRO_NOK);
                                pPicTiming->hoursValue[i] = tmp;
                            }
                        }
                    }
                }
                if (timeOffsetLength)
                {
                    tmp = h264bsdGetBits(pStrmData, timeOffsetLength);
                    if (tmp == END_OF_STREAM)
                        return(HANTRO_NOK);
                    itmp = (i32)tmp;
                    /* following "converts" timeOffsetLength-bit signed
                     * integer into i32 */
                    /*lint -save -e701 -e702 */
                    itmp <<= (32 - timeOffsetLength);
                    itmp >>= (32 - timeOffsetLength);
                    /*lint -restore */
                    pPicTiming->timeOffset[i] = itmp;
                                    }
                else
                    pPicTiming->timeOffset[i] = 0;
            }
        }
    }

    return(HANTRO_OK);

}

/*------------------------------------------------------------------------------

    Function: DecodePanScanRectangle

        Functional description:
          <++>
        Inputs:
          <++>
        Outputs:
          <++>

------------------------------------------------------------------------------*/

static u32 DecodePanScanRectangle(
  strmData_t *pStrmData,
  seiPanScanRect_t *pPanScanRectangle)
{

/* Variables */

    u32 tmp, i;

/* Code */

    ASSERT(pStrmData);
    ASSERT(pPanScanRectangle);


    tmp = h264bsdDecodeExpGolombUnsigned(pStrmData,
      &pPanScanRectangle->panScanRectId);
    if (tmp != HANTRO_OK)
        return(tmp);

    tmp = h264bsdGetBits(pStrmData, 1);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);
    pPanScanRectangle->panScanRectCancelFlag = tmp == 1 ?
                                HANTRO_TRUE : HANTRO_FALSE;

    if (!pPanScanRectangle->panScanRectCancelFlag)
    {
        tmp = h264bsdDecodeExpGolombUnsigned(pStrmData,
          &pPanScanRectangle->panScanCnt);
        if (tmp != HANTRO_OK)
            return(tmp);
        if (pPanScanRectangle->panScanCnt > 2)
            return(HANTRO_NOK);
        pPanScanRectangle->panScanCnt++;

        for (i = 0; i < pPanScanRectangle->panScanCnt; i++)
        {
            tmp = h264bsdDecodeExpGolombSigned(pStrmData,
              &pPanScanRectangle->panScanRectLeftOffset[i]);
            if (tmp != HANTRO_OK)
                return(tmp);

            tmp = h264bsdDecodeExpGolombSigned(pStrmData,
              &pPanScanRectangle->panScanRectRightOffset[i]);
            if (tmp != HANTRO_OK)
                return(tmp);

            tmp = h264bsdDecodeExpGolombSigned(pStrmData,
              &pPanScanRectangle->panScanRectTopOffset[i]);
            if (tmp != HANTRO_OK)
                return(tmp);

            tmp = h264bsdDecodeExpGolombSigned(pStrmData,
              &pPanScanRectangle->panScanRectBottomOffset[i]);
            if (tmp != HANTRO_OK)
                return(tmp);
        }
        tmp = h264bsdDecodeExpGolombUnsigned(pStrmData,
          &pPanScanRectangle->panScanRectRepetitionPeriod);
        if (tmp != HANTRO_OK)
            return(tmp);
        if (pPanScanRectangle->panScanRectRepetitionPeriod > 16384)
            return(HANTRO_NOK);
        if (pPanScanRectangle->panScanCnt > 1 &&
          pPanScanRectangle->panScanRectRepetitionPeriod > 1)
            return(HANTRO_NOK);
    }

    return(HANTRO_OK);
}

/*------------------------------------------------------------------------------

    Function: DecodeFillerPayload

        Functional description:
          <++>
        Inputs:
          <++>
        Outputs:
          <++>

------------------------------------------------------------------------------*/

static u32 DecodeFillerPayload(strmData_t *pStrmData, u32 payloadSize)
{

/* Variables */

/* Code */

    ASSERT(pStrmData);


    if (payloadSize)
        if (h264bsdFlushBits(pStrmData, 8 * payloadSize) == END_OF_STREAM)
            return(HANTRO_NOK);

    return(HANTRO_OK);

}

/*------------------------------------------------------------------------------

    Function: DecodeUserDataRegisteredITuTT35

        Functional description:
          <++>
        Inputs:
          <++>
        Outputs:
          <++>

------------------------------------------------------------------------------*/

static u32 DecodeUserDataRegisteredITuTT35(
  strmData_t *pStrmData,
  seiUserDataRegisteredItuTT35_t *pUserDataRegisteredItuTT35,
  u32 payloadSize)
{

/* Variables */

    u32 tmp, i, j;

/* Code */

    ASSERT(pStrmData);
    ASSERT(pUserDataRegisteredItuTT35);
    ASSERT(payloadSize);

        tmp = h264bsdGetBits(pStrmData, 8);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);
    pUserDataRegisteredItuTT35->ituTT35CountryCode = tmp;

    if (pUserDataRegisteredItuTT35->ituTT35CountryCode != 0xFF)
        i = 1;
    else
    {
        tmp = h264bsdGetBits(pStrmData, 8);
        if (tmp == END_OF_STREAM)
            return(HANTRO_NOK);
        pUserDataRegisteredItuTT35->ituTT35CountryCodeExtensionByte = tmp;
        i = 2;
    }

    /* where corresponding FREE() ??? */
    ALLOCATE(pUserDataRegisteredItuTT35->ituTT35PayloadByte,payloadSize-i,u8);
    pUserDataRegisteredItuTT35->numPayloadBytes = payloadSize - i;
    if (pUserDataRegisteredItuTT35->ituTT35PayloadByte == NULL)
        return(MEMORY_ALLOCATION_ERROR);

    j = 0;
    do
    {
        tmp = h264bsdGetBits(pStrmData, 8);
        if (tmp == END_OF_STREAM)
            return(HANTRO_NOK);
        pUserDataRegisteredItuTT35->ituTT35PayloadByte[j] = (u8)tmp;
        i++;
        j++;
    } while (i < payloadSize);

    return(HANTRO_OK);

}

/*------------------------------------------------------------------------------

    Function: DecodeUserDataUnregistered

        Functional description:
          <++>
        Inputs:
          <++>
        Outputs:
          <++>

------------------------------------------------------------------------------*/

static u32 DecodeUserDataUnregistered(
  strmData_t *pStrmData,
  seiUserDataUnregistered_t *pUserDataUnregistered,
  u32 payloadSize)
{

/* Variables */

    u32 i, tmp;

/* Code */

    ASSERT(pStrmData);
    ASSERT(pUserDataUnregistered);


    for (i = 0; i < 4; i++)
    {
        pUserDataUnregistered->uuidIsoIec11578[i] = h264bsdShowBits32(pStrmData);
        if (h264bsdFlushBits(pStrmData,32) == END_OF_STREAM)
            return(HANTRO_NOK);
    }

    /* where corresponding FREE() ??? */
    ALLOCATE(pUserDataUnregistered->userDataPayloadByte, payloadSize - 16, u8);
    if (pUserDataUnregistered->userDataPayloadByte == NULL)
        return(MEMORY_ALLOCATION_ERROR);

    pUserDataUnregistered->numPayloadBytes = payloadSize - 16;

    for (i = 0; i < payloadSize - 16; i++)
    {
        tmp = h264bsdGetBits(pStrmData, 8);
        if (tmp == END_OF_STREAM)
            return(HANTRO_NOK);
        pUserDataUnregistered->userDataPayloadByte[i] = (u8)tmp;
    }

    return(HANTRO_OK);

}

/*------------------------------------------------------------------------------

    Function: DecodeRecoveryPoint

        Functional description:
          <++>
        Inputs:
          <++>
        Outputs:
          <++>

------------------------------------------------------------------------------*/

static u32 DecodeRecoveryPoint(
  strmData_t *pStrmData,
  seiRecoveryPoint_t *pRecoveryPoint)
{

/* Variables */

    u32 tmp;

/* Code */

    ASSERT(pStrmData);
    ASSERT(pRecoveryPoint);


    tmp = h264bsdDecodeExpGolombUnsigned(pStrmData,
        &pRecoveryPoint->recoveryFrameCnt);
    if (tmp != HANTRO_OK)
        return(tmp);

    tmp = h264bsdGetBits(pStrmData, 1);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);
    pRecoveryPoint->exactMatchFlag = tmp == 1 ? HANTRO_TRUE : HANTRO_FALSE;

    tmp = h264bsdGetBits(pStrmData, 1);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);
    pRecoveryPoint->brokenLinkFlag = tmp == 1 ? HANTRO_TRUE : HANTRO_FALSE;

    tmp = h264bsdGetBits(pStrmData, 2);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);
    if (tmp > 2)
        return(HANTRO_NOK);
    pRecoveryPoint->changingSliceGroupIdc = tmp;

    return(HANTRO_OK);

}

/*------------------------------------------------------------------------------

    Function: DecodeDecRefPicMarkingRepetition

        Functional description:
          <++>
        Inputs:
          <++>
        Outputs:
          <++>

------------------------------------------------------------------------------*/

static u32 DecodeDecRefPicMarkingRepetition(
  strmData_t *pStrmData,
  seiDecRefPicMarkingRepetition_t *pDecRefPicMarkingRepetition,
  u32 numRefFrames)
{

/* Variables */

    u32 tmp;

/* Code */

    ASSERT(pStrmData);
    ASSERT(pDecRefPicMarkingRepetition);


    tmp = h264bsdGetBits(pStrmData, 1);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);
    pDecRefPicMarkingRepetition->originalIdrFlag = tmp == 1 ? HANTRO_TRUE : HANTRO_FALSE;

    tmp = h264bsdDecodeExpGolombUnsigned(pStrmData,
      &pDecRefPicMarkingRepetition->originalFrameNum);
    if (tmp != HANTRO_OK)
        return(tmp);

    /* frame_mbs_only_flag assumed always true so some field related syntax
     * elements are skipped, see H.264 standard */
    tmp = h264bsdDecRefPicMarking(pStrmData,
      &pDecRefPicMarkingRepetition->decRefPicMarking, NAL_SEI, numRefFrames);

    return(tmp);

}

/*------------------------------------------------------------------------------

    Function: DecodeSparePic

        Functional description:
          <++>
        Inputs:
          <++>
        Outputs:
          <++>

------------------------------------------------------------------------------*/

static u32 DecodeSparePic(
  strmData_t *pStrmData,
  seiSparePic_t *pSparePic,
  u32 picSizeInMapUnits)
{

/* Variables */

    u32 tmp, i, j, mapUnitCnt;

/* Code */

    ASSERT(pStrmData);
    ASSERT(pSparePic);


    tmp = h264bsdDecodeExpGolombUnsigned(pStrmData,
        &pSparePic->targetFrameNum);
    if (tmp != HANTRO_OK)
        return(tmp);

    tmp = h264bsdGetBits(pStrmData, 1);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);
    pSparePic->spareFieldFlag = tmp == 1 ? HANTRO_TRUE : HANTRO_FALSE;
    /* do not accept fields */
    if (pSparePic->spareFieldFlag)
        return(HANTRO_NOK);

    tmp = h264bsdDecodeExpGolombUnsigned(pStrmData, &pSparePic->numSparePics);
    if (tmp != HANTRO_OK)
        return(tmp);
    pSparePic->numSparePics++;
    if (pSparePic->numSparePics > MAX_NUM_SPARE_PICS)
        return(HANTRO_NOK);

    for (i = 0; i < pSparePic->numSparePics; i++)
    {
        tmp = h264bsdDecodeExpGolombUnsigned(pStrmData,
          &pSparePic->deltaSpareFrameNum[i]);
        if (tmp != HANTRO_OK)
            return(tmp);

        tmp = h264bsdDecodeExpGolombUnsigned(pStrmData,
            &pSparePic->spareAreaIdc[i]);
        if (tmp != HANTRO_OK)
            return(tmp);
        if (pSparePic->spareAreaIdc[i] > 2)
            return(HANTRO_NOK);

        if (pSparePic->spareAreaIdc[i] == 1)
        {
            /* where corresponding FREE() ??? */
            ALLOCATE(pSparePic->spareUnitFlag[i], picSizeInMapUnits, u32);
            if (pSparePic->spareUnitFlag[i] == NULL)
                return(MEMORY_ALLOCATION_ERROR);
            pSparePic->zeroRunLength[i] = NULL;

            for (j = 0; j < picSizeInMapUnits; j++)
            {
                tmp = h264bsdGetBits(pStrmData, 1);
                if (tmp == END_OF_STREAM)
                    return(HANTRO_NOK);
                pSparePic->spareUnitFlag[i][j] = tmp == 1 ?
                                    HANTRO_TRUE : HANTRO_FALSE;
            }
        }
        else if (pSparePic->spareAreaIdc[i] == 2)
        {
            /* where corresponding FREE() ??? */
            ALLOCATE(pSparePic->zeroRunLength[i], picSizeInMapUnits, u32);
            if (pSparePic->zeroRunLength[i] == NULL)
                return(MEMORY_ALLOCATION_ERROR);
            pSparePic->spareUnitFlag[i] = NULL;

            for (j = 0, mapUnitCnt = 0; mapUnitCnt < picSizeInMapUnits; j++)
            {
                tmp = h264bsdDecodeExpGolombUnsigned(pStrmData,
                  &pSparePic->zeroRunLength[i][j]);
                if (tmp != HANTRO_OK)
                    return(tmp);
                mapUnitCnt += pSparePic->zeroRunLength[i][j] + 1;
            }
        }
    }

    /* set rest to null */
    for (i = pSparePic->numSparePics; i < MAX_NUM_SPARE_PICS; i++)
    {
        pSparePic->spareUnitFlag[i] = NULL;
        pSparePic->zeroRunLength[i] = NULL;
    }

    return(HANTRO_OK);

}

/*------------------------------------------------------------------------------

    Function: DecodeSceneInfo

        Functional description:
          <++>
        Inputs:
          <++>
        Outputs:
          <++>

------------------------------------------------------------------------------*/

static u32 DecodeSceneInfo(
  strmData_t *pStrmData,
  seiSceneInfo_t *pSceneInfo)
{

/* Variables */

    u32 tmp;

/* Code */

    ASSERT(pStrmData);
    ASSERT(pSceneInfo);


    tmp = h264bsdGetBits(pStrmData, 1);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);
    pSceneInfo->sceneInfoPresentFlag = tmp == 1 ? HANTRO_TRUE : HANTRO_FALSE;

    if (pSceneInfo->sceneInfoPresentFlag)
    {
        tmp = h264bsdDecodeExpGolombUnsigned(pStrmData, &pSceneInfo->sceneId);
        if (tmp != HANTRO_OK)
            return(tmp);

        tmp = h264bsdDecodeExpGolombUnsigned(pStrmData,
          &pSceneInfo->sceneTransitionType);
        if (tmp != HANTRO_OK)
            return(tmp);
        if (pSceneInfo->sceneTransitionType > 6)
            return(HANTRO_NOK);

        if (pSceneInfo->sceneTransitionType)
        {
            tmp = h264bsdDecodeExpGolombUnsigned(pStrmData,
              &pSceneInfo->secondSceneId);
            if (tmp != HANTRO_OK)
                return(tmp);
        }

    }

    return(HANTRO_OK);

}

/*------------------------------------------------------------------------------

    Function: DecodeSubSeqInfo

        Functional description:
          <++>
        Inputs:
          <++>
        Outputs:
          <++>

-----------------------------------------------------------------------------*/

static u32 DecodeSubSeqInfo(
  strmData_t *pStrmData,
  seiSubSeqInfo_t *pSubSeqInfo)
{

/* Variables */

    u32 tmp;

/* Code */

    ASSERT(pStrmData);
    ASSERT(pSubSeqInfo);


    tmp = h264bsdDecodeExpGolombUnsigned(pStrmData,
        &pSubSeqInfo->subSeqLayerNum);
    if (tmp != HANTRO_OK)
        return(tmp);
    if (pSubSeqInfo->subSeqLayerNum > 255)
        return(HANTRO_NOK);

    tmp = h264bsdDecodeExpGolombUnsigned(pStrmData, &pSubSeqInfo->subSeqId);
    if (tmp != HANTRO_OK)
        return(tmp);
    if (pSubSeqInfo->subSeqId > 65535)
        return(HANTRO_NOK);

    tmp = h264bsdGetBits(pStrmData, 1);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);
    pSubSeqInfo->firstRefPicFlag = tmp == 1 ? HANTRO_TRUE : HANTRO_FALSE;

    tmp = h264bsdGetBits(pStrmData, 1);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);
    pSubSeqInfo->leadingNonRefPicFlag = tmp == 1 ? HANTRO_TRUE : HANTRO_FALSE;

    tmp = h264bsdGetBits(pStrmData, 1);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);
    pSubSeqInfo->lastPicFlag = tmp == 1 ? HANTRO_TRUE : HANTRO_FALSE;

    tmp = h264bsdGetBits(pStrmData, 1);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);
    pSubSeqInfo->subSeqFrameNumFlag = tmp == 1 ? HANTRO_TRUE : HANTRO_FALSE;

    if (pSubSeqInfo->subSeqFrameNumFlag)
    {
        tmp = h264bsdDecodeExpGolombUnsigned(pStrmData,
            &pSubSeqInfo->subSeqFrameNum);
        if (tmp != HANTRO_OK)
            return(tmp);
    }

    return(HANTRO_OK);

}

/*------------------------------------------------------------------------------

    Function: DecodeSubSeqLayerCharacteristics

        Functional description:
          <++>
        Inputs:
          <++>
        Outputs:
          <++>

------------------------------------------------------------------------------*/

static u32 DecodeSubSeqLayerCharacteristics(
  strmData_t *pStrmData,
  seiSubSeqLayerCharacteristics_t *pSubSeqLayerCharacteristics)
{

/* Variables */

    u32 tmp, i;

/* Code */

    ASSERT(pStrmData);
    ASSERT(pSubSeqLayerCharacteristics);


    tmp = h264bsdDecodeExpGolombUnsigned(pStrmData,
      &pSubSeqLayerCharacteristics->numSubSeqLayers);
    if (tmp != HANTRO_OK)
        return(tmp);
    pSubSeqLayerCharacteristics->numSubSeqLayers++;
    if (pSubSeqLayerCharacteristics->numSubSeqLayers > MAX_NUM_SUB_SEQ_LAYERS)
        return(HANTRO_NOK);

    for (i = 0; i < pSubSeqLayerCharacteristics->numSubSeqLayers; i++)
    {
        tmp = h264bsdGetBits(pStrmData, 1);
        if (tmp == END_OF_STREAM)
            return(HANTRO_NOK);
        pSubSeqLayerCharacteristics->accurateStatisticsFlag[i] =
            tmp == 1 ? HANTRO_TRUE : HANTRO_FALSE;

        tmp = h264bsdGetBits(pStrmData, 16);
        if (tmp == END_OF_STREAM)
            return(HANTRO_NOK);
        pSubSeqLayerCharacteristics->averageBitRate[i] = tmp;

        tmp = h264bsdGetBits(pStrmData, 16);
        if (tmp == END_OF_STREAM)
            return(HANTRO_NOK);
        pSubSeqLayerCharacteristics->averageFrameRate[i] = tmp;
    }

    return(HANTRO_OK);

}

/*------------------------------------------------------------------------------

    Function: DecodeSubSeqCharacteristics

        Functional description:
          <++>
        Inputs:
          <++>
        Outputs:
          <++>

------------------------------------------------------------------------------*/

static u32 DecodeSubSeqCharacteristics(
  strmData_t *pStrmData,
  seiSubSeqCharacteristics_t *pSubSeqCharacteristics)
{

/* Variables */

    u32 tmp, i;

/* Code */

    ASSERT(pStrmData);
    ASSERT(pSubSeqCharacteristics);


    tmp = h264bsdDecodeExpGolombUnsigned(pStrmData,
      &pSubSeqCharacteristics->subSeqLayerNum);
    if (tmp != HANTRO_OK)
        return(tmp);
    if (pSubSeqCharacteristics->subSeqLayerNum > MAX_NUM_SUB_SEQ_LAYERS-1)
        return(HANTRO_NOK);

    tmp = h264bsdDecodeExpGolombUnsigned(pStrmData,
        &pSubSeqCharacteristics->subSeqId);
    if (tmp != HANTRO_OK)
        return(tmp);
    if (pSubSeqCharacteristics->subSeqId > 65535)
        return(HANTRO_NOK);

    tmp = h264bsdGetBits(pStrmData, 1);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);
    pSubSeqCharacteristics->durationFlag = tmp == 1 ?
                            HANTRO_TRUE : HANTRO_FALSE;

    if (pSubSeqCharacteristics->durationFlag)
    {
        pSubSeqCharacteristics->subSeqDuration = h264bsdShowBits32(pStrmData);
        if (h264bsdFlushBits(pStrmData,32) == END_OF_STREAM)
            return(HANTRO_NOK);
    }

    tmp = h264bsdGetBits(pStrmData, 1);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);
    pSubSeqCharacteristics->averageRateFlag = tmp == 1 ?
                            HANTRO_TRUE : HANTRO_FALSE;

    if (pSubSeqCharacteristics->averageRateFlag)
    {
        tmp = h264bsdGetBits(pStrmData, 1);
        if (tmp == END_OF_STREAM)
            return(HANTRO_NOK);
        pSubSeqCharacteristics->accurateStatisticsFlag =
            tmp == 1 ? HANTRO_TRUE : HANTRO_FALSE;

        tmp = h264bsdGetBits(pStrmData, 16);
        if (tmp == END_OF_STREAM)
            return(HANTRO_NOK);
        pSubSeqCharacteristics->averageBitRate = tmp;

        tmp = h264bsdGetBits(pStrmData, 16);
        if (tmp == END_OF_STREAM)
            return(HANTRO_NOK);
        pSubSeqCharacteristics->averageFrameRate = tmp;
    }

    tmp = h264bsdDecodeExpGolombUnsigned(pStrmData,
      &pSubSeqCharacteristics->numReferencedSubseqs);
    if (tmp != HANTRO_OK)
        return(tmp);
    if (pSubSeqCharacteristics->numReferencedSubseqs > MAX_NUM_SUB_SEQ_LAYERS-1)
        return(HANTRO_NOK);

    for (i = 0; i < pSubSeqCharacteristics->numReferencedSubseqs; i++)
    {
        tmp = h264bsdDecodeExpGolombUnsigned(pStrmData,
          &pSubSeqCharacteristics->refSubSeqLayerNum[i]);
        if (tmp != HANTRO_OK)
            return(tmp);

        tmp = h264bsdDecodeExpGolombUnsigned(pStrmData,
          &pSubSeqCharacteristics->refSubSeqId[i]);
        if (tmp != HANTRO_OK)
            return(tmp);

        tmp = h264bsdGetBits(pStrmData, 1);
        if (tmp == END_OF_STREAM)
            return(HANTRO_NOK);
        pSubSeqCharacteristics->refSubSeqDirection[i] = tmp;
    }

    return(HANTRO_OK);

}

/*------------------------------------------------------------------------------

    Function: DecodeFullFrameFreeze

        Functional description:
          <++>
        Inputs:
          <++>
        Outputs:
          <++>

------------------------------------------------------------------------------*/

static u32 DecodeFullFrameFreeze(
  strmData_t *pStrmData,
  seiFullFrameFreeze_t *pFullFrameFreeze)
{

/* Variables */

    u32 tmp;

/* Code */

    ASSERT(pStrmData);
    ASSERT(pFullFrameFreeze);


    tmp = h264bsdDecodeExpGolombUnsigned(pStrmData,
      &pFullFrameFreeze->fullFrameFreezeRepetitionPeriod);
    if (tmp != HANTRO_OK)
        return(tmp);
    if (pFullFrameFreeze->fullFrameFreezeRepetitionPeriod > 16384)
        return(HANTRO_NOK);

    return(HANTRO_OK);

}

/*------------------------------------------------------------------------------

    Function: DecodeFullFrameSnapshot

        Functional description:
          <++>
        Inputs:
          <++>
        Outputs:
          <++>

------------------------------------------------------------------------------*/

static u32 DecodeFullFrameSnapshot(
  strmData_t *pStrmData,
  seiFullFrameSnapshot_t *pFullFrameSnapshot)
{

/* Variables */

    u32 tmp;

/* Code */

    ASSERT(pStrmData);
    ASSERT(pFullFrameSnapshot);


    tmp = h264bsdDecodeExpGolombUnsigned(pStrmData,
        &pFullFrameSnapshot->snapShotId);
    if (tmp != HANTRO_OK)
        return(tmp);

    return(HANTRO_OK);

}

/*------------------------------------------------------------------------------

    Function: DecodeProgressiveRefinementSegmentStart

        Functional description:
          <++>
        Inputs:
          <++>
        Outputs:
          <++>

------------------------------------------------------------------------------*/

static u32 DecodeProgressiveRefinementSegmentStart(
  strmData_t *pStrmData,
  seiProgressiveRefinementSegmentStart_t *pProgressiveRefinementSegmentStart)
{

/* Variables */

    u32 tmp;

/* Code */

    ASSERT(pStrmData);
    ASSERT(pProgressiveRefinementSegmentStart);


    tmp = h264bsdDecodeExpGolombUnsigned(pStrmData,
      &pProgressiveRefinementSegmentStart->progressiveRefinementId);
    if (tmp != HANTRO_OK)
        return(tmp);

    tmp = h264bsdDecodeExpGolombUnsigned(pStrmData,
      &pProgressiveRefinementSegmentStart->numRefinementSteps);
    if (tmp != HANTRO_OK)
        return(tmp);
    pProgressiveRefinementSegmentStart->numRefinementSteps++;

    return(HANTRO_OK);

}

/*------------------------------------------------------------------------------

    Function: DecodeProgressiveRefinementSegmentEnd

        Functional description:
          <++>
        Inputs:
          <++>
        Outputs:
          <++>

------------------------------------------------------------------------------*/

static u32 DecodeProgressiveRefinementSegmentEnd(
  strmData_t *pStrmData,
  seiProgressiveRefinementSegmentEnd_t *pProgressiveRefinementSegmentEnd)
{

/* Variables */

    u32 tmp;

/* Code */

    ASSERT(pStrmData);
    ASSERT(pProgressiveRefinementSegmentEnd);


    tmp = h264bsdDecodeExpGolombUnsigned(pStrmData,
      &pProgressiveRefinementSegmentEnd->progressiveRefinementId);
    if (tmp != HANTRO_OK)
        return(tmp);

    return(HANTRO_OK);

}

/*------------------------------------------------------------------------------

    Function: DecodeMotionConstrainedSliceGroupSet

        Functional description:
          <++>
        Inputs:
          <++>
        Outputs:
          <++>

------------------------------------------------------------------------------*/

static u32 DecodeMotionConstrainedSliceGroupSet(
  strmData_t *pStrmData,
  seiMotionConstrainedSliceGroupSet_t *pMotionConstrainedSliceGroupSet,
  u32 numSliceGroups)
{

/* Variables */

    u32 tmp,i;

/* Code */

    ASSERT(pStrmData);
    ASSERT(pMotionConstrainedSliceGroupSet);
    ASSERT(numSliceGroups < MAX_NUM_SLICE_GROUPS);


    tmp = h264bsdDecodeExpGolombUnsigned(pStrmData,
      &pMotionConstrainedSliceGroupSet->numSliceGroupsInSet);
    if (tmp != HANTRO_OK)
        return(tmp);
    pMotionConstrainedSliceGroupSet->numSliceGroupsInSet++;
    if (pMotionConstrainedSliceGroupSet->numSliceGroupsInSet > numSliceGroups)
        return(HANTRO_NOK);

    for (i = 0; i < pMotionConstrainedSliceGroupSet->numSliceGroupsInSet; i++)
    {
        tmp = h264bsdGetBits(pStrmData,
            ceilLog2NumSliceGroups[numSliceGroups]);
        if (tmp == END_OF_STREAM)
            return(HANTRO_NOK);
        pMotionConstrainedSliceGroupSet->sliceGroupId[i] = tmp;
        if (pMotionConstrainedSliceGroupSet->sliceGroupId[i] >
          pMotionConstrainedSliceGroupSet->numSliceGroupsInSet-1)
            return(HANTRO_NOK);
    }

    tmp = h264bsdGetBits(pStrmData, 1);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);
    pMotionConstrainedSliceGroupSet->exactSampleValueMatchFlag =
        tmp == 1 ? HANTRO_TRUE : HANTRO_FALSE;

    tmp = h264bsdGetBits(pStrmData, 1);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);
    pMotionConstrainedSliceGroupSet->panScanRectFlag = tmp == 1 ?
                                        HANTRO_TRUE : HANTRO_FALSE;

    if (pMotionConstrainedSliceGroupSet->panScanRectFlag)
    {
        tmp = h264bsdDecodeExpGolombUnsigned(pStrmData,
          &pMotionConstrainedSliceGroupSet->panScanRectId);
        if (tmp != HANTRO_OK)
            return(tmp);
    }

    return(HANTRO_OK);

}

/*------------------------------------------------------------------------------

    Function: DecodeReservedSeiMessage

        Functional description:
          <++>
        Inputs:
          <++>
        Outputs:
          <++>

------------------------------------------------------------------------------*/

static u32 DecodeReservedSeiMessage(
  strmData_t *pStrmData,
  seiReservedSeiMessage_t *pReservedSeiMessage,
  u32 payloadSize)
{

/* Variables */

    u32 i, tmp;

/* Code */

    ASSERT(pStrmData);
    ASSERT(pReservedSeiMessage);


    /* where corresponding FREE() ??? */
    ALLOCATE(pReservedSeiMessage->reservedSeiMessagePayloadByte,payloadSize,u8);
    if (pReservedSeiMessage->reservedSeiMessagePayloadByte == NULL)
        return(MEMORY_ALLOCATION_ERROR);

    pReservedSeiMessage->numPayloadBytes = payloadSize;

    for (i = 0; i < payloadSize; i++)
    {
        tmp = h264bsdGetBits(pStrmData,8);
        if (tmp == END_OF_STREAM)
            return(HANTRO_NOK);
        pReservedSeiMessage->reservedSeiMessagePayloadByte[i] = (u8)tmp;
    }

    return(HANTRO_OK);

}

