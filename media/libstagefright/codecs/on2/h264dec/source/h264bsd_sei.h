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
    2. Module defines
    3. Data types
    4. Function prototypes

------------------------------------------------------------------------------*/

#ifndef H264SWDEC_SEI_H
#define H264SWDEC_SEI_H

/*------------------------------------------------------------------------------
    1. Include headers
------------------------------------------------------------------------------*/

#include "basetype.h"
#include "h264bsd_stream.h"
#include "h264bsd_slice_header.h"
#include "h264bsd_seq_param_set.h"
#include "h264bsd_vui.h"

/*------------------------------------------------------------------------------
    2. Module defines
------------------------------------------------------------------------------*/

#define MAX_PAN_SCAN_CNT 32
#define MAX_NUM_SPARE_PICS 16
#define MAX_NUM_CLOCK_TS 3
#define MAX_NUM_SUB_SEQ_LAYERS 256

/*------------------------------------------------------------------------------
    3. Data types
------------------------------------------------------------------------------*/

typedef struct
{
    u32 seqParameterSetId;
    u32 initialCpbRemovalDelay[MAX_CPB_CNT];
    u32 initialCpbRemovalDelayOffset[MAX_CPB_CNT];
} seiBufferingPeriod_t;

typedef struct
{
    u32 cpbRemovalDelay;
    u32 dpbOutputDelay;
    u32 picStruct;
    u32 clockTimeStampFlag[MAX_NUM_CLOCK_TS];
    u32 clockTimeStamp[MAX_NUM_CLOCK_TS];
    u32 ctType[MAX_NUM_CLOCK_TS];
    u32 nuitFieldBasedFlag[MAX_NUM_CLOCK_TS];
    u32 countingType[MAX_NUM_CLOCK_TS];
    u32 fullTimeStampFlag[MAX_NUM_CLOCK_TS];
    u32 discontinuityFlag[MAX_NUM_CLOCK_TS];
    u32 cntDroppedFlag[MAX_NUM_CLOCK_TS];
    u32 nFrames[MAX_NUM_CLOCK_TS];
    u32 secondsFlag[MAX_NUM_CLOCK_TS];
    u32 secondsValue[MAX_NUM_CLOCK_TS];
    u32 minutesFlag[MAX_NUM_CLOCK_TS];
    u32 minutesValue[MAX_NUM_CLOCK_TS];
    u32 hoursFlag[MAX_NUM_CLOCK_TS];
    u32 hoursValue[MAX_NUM_CLOCK_TS];
    i32 timeOffset[MAX_NUM_CLOCK_TS];
} seiPicTiming_t;

typedef struct
{
    u32 panScanRectId;
    u32 panScanRectCancelFlag;
    u32 panScanCnt;
    i32 panScanRectLeftOffset[MAX_PAN_SCAN_CNT];
    i32 panScanRectRightOffset[MAX_PAN_SCAN_CNT];
    i32 panScanRectTopOffset[MAX_PAN_SCAN_CNT];
    i32 panScanRectBottomOffset[MAX_PAN_SCAN_CNT];
    u32 panScanRectRepetitionPeriod;
} seiPanScanRect_t;

typedef struct
{
    u32 ituTT35CountryCode;
    u32 ituTT35CountryCodeExtensionByte;
    u8 *ituTT35PayloadByte;
    u32 numPayloadBytes;
} seiUserDataRegisteredItuTT35_t;

typedef struct
{
    u32 uuidIsoIec11578[4];
    u8 *userDataPayloadByte;
    u32 numPayloadBytes;
} seiUserDataUnregistered_t;

typedef struct
{
    u32 recoveryFrameCnt;
    u32 exactMatchFlag;
    u32 brokenLinkFlag;
    u32 changingSliceGroupIdc;
} seiRecoveryPoint_t;

typedef struct
{
    u32 originalIdrFlag;
    u32 originalFrameNum;
    decRefPicMarking_t decRefPicMarking;
} seiDecRefPicMarkingRepetition_t;

typedef struct
{
    u32 targetFrameNum;
    u32 spareFieldFlag;
    u32 targetBottomFieldFlag;
    u32 numSparePics;
    u32 deltaSpareFrameNum[MAX_NUM_SPARE_PICS];
    u32 spareBottomFieldFlag[MAX_NUM_SPARE_PICS];
    u32 spareAreaIdc[MAX_NUM_SPARE_PICS];
    u32 *spareUnitFlag[MAX_NUM_SPARE_PICS];
    u32 *zeroRunLength[MAX_NUM_SPARE_PICS];
} seiSparePic_t;

typedef struct
{
    u32 sceneInfoPresentFlag;
    u32 sceneId;
    u32 sceneTransitionType;
    u32 secondSceneId;
} seiSceneInfo_t;

typedef struct
{
    u32 subSeqLayerNum;
    u32 subSeqId;
    u32 firstRefPicFlag;
    u32 leadingNonRefPicFlag;
    u32 lastPicFlag;
    u32 subSeqFrameNumFlag;
    u32 subSeqFrameNum;
} seiSubSeqInfo_t;

typedef struct
{
    u32 numSubSeqLayers;
    u32 accurateStatisticsFlag[MAX_NUM_SUB_SEQ_LAYERS];
    u32 averageBitRate[MAX_NUM_SUB_SEQ_LAYERS];
    u32 averageFrameRate[MAX_NUM_SUB_SEQ_LAYERS];
} seiSubSeqLayerCharacteristics_t;

typedef struct
{
    u32 subSeqLayerNum;
    u32 subSeqId;
    u32 durationFlag;
    u32 subSeqDuration;
    u32 averageRateFlag;
    u32 accurateStatisticsFlag;
    u32 averageBitRate;
    u32 averageFrameRate;
    u32 numReferencedSubseqs;
    u32 refSubSeqLayerNum[MAX_NUM_SUB_SEQ_LAYERS];
    u32 refSubSeqId[MAX_NUM_SUB_SEQ_LAYERS];
    u32 refSubSeqDirection[MAX_NUM_SUB_SEQ_LAYERS];
} seiSubSeqCharacteristics_t;

typedef struct
{
    u32 fullFrameFreezeRepetitionPeriod;
} seiFullFrameFreeze_t;

typedef struct
{
    u32 snapShotId;
} seiFullFrameSnapshot_t;

typedef struct
{
    u32 progressiveRefinementId;
    u32 numRefinementSteps;
} seiProgressiveRefinementSegmentStart_t;

typedef struct
{
    u32 progressiveRefinementId;
} seiProgressiveRefinementSegmentEnd_t;

typedef struct
{
    u32 numSliceGroupsInSet;
    u32 sliceGroupId[MAX_NUM_SLICE_GROUPS];
    u32 exactSampleValueMatchFlag;
    u32 panScanRectFlag;
    u32 panScanRectId;
} seiMotionConstrainedSliceGroupSet_t;

typedef struct
{
    u8 *reservedSeiMessagePayloadByte;
    u32 numPayloadBytes;
} seiReservedSeiMessage_t;

typedef struct
{
    u32 payloadType;
    seiBufferingPeriod_t bufferingPeriod;
    seiPicTiming_t picTiming;
    seiPanScanRect_t panScanRect;
    seiUserDataRegisteredItuTT35_t userDataRegisteredItuTT35;
    seiUserDataUnregistered_t userDataUnregistered;
    seiRecoveryPoint_t recoveryPoint;
    seiDecRefPicMarkingRepetition_t decRefPicMarkingRepetition;
    seiSparePic_t sparePic;
    seiSceneInfo_t sceneInfo;
    seiSubSeqInfo_t subSeqInfo;
    seiSubSeqLayerCharacteristics_t subSeqLayerCharacteristics;
    seiSubSeqCharacteristics_t subSeqCharacteristics;
    seiFullFrameFreeze_t fullFrameFreeze;
    seiFullFrameSnapshot_t fullFrameSnapshot;
    seiProgressiveRefinementSegmentStart_t progressiveRefinementSegmentStart;
    seiProgressiveRefinementSegmentEnd_t progressiveRefinementSegmentEnd;
    seiMotionConstrainedSliceGroupSet_t motionConstrainedSliceGroupSet;
    seiReservedSeiMessage_t reservedSeiMessage;
} seiMessage_t;

/*------------------------------------------------------------------------------
    4. Function prototypes
------------------------------------------------------------------------------*/

u32 h264bsdDecodeSeiMessage(
  strmData_t *pStrmData,
  seqParamSet_t *pSeqParamSet,
  seiMessage_t *pSeiMessage,
  u32 numSliceGroups);

#endif /* #ifdef H264SWDEC_SEI_H */

