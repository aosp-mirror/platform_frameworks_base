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

#ifndef H264SWDEC_NAL_UNIT_H
#define H264SWDEC_NAL_UNIT_H

/*------------------------------------------------------------------------------
    1. Include headers
------------------------------------------------------------------------------*/

#include "basetype.h"
#include "h264bsd_stream.h"

/*------------------------------------------------------------------------------
    2. Module defines
------------------------------------------------------------------------------*/

/* macro to determine if NAL unit pointed by pNalUnit contains an IDR slice */
#define IS_IDR_NAL_UNIT(pNalUnit) \
    ((pNalUnit)->nalUnitType == NAL_CODED_SLICE_IDR)

/*------------------------------------------------------------------------------
    3. Data types
------------------------------------------------------------------------------*/

typedef enum {
    NAL_CODED_SLICE = 1,
    NAL_CODED_SLICE_IDR = 5,
    NAL_SEI = 6,
    NAL_SEQ_PARAM_SET = 7,
    NAL_PIC_PARAM_SET = 8,
    NAL_ACCESS_UNIT_DELIMITER = 9,
    NAL_END_OF_SEQUENCE = 10,
    NAL_END_OF_STREAM = 11,
    NAL_FILLER_DATA = 12,
    NAL_MAX_TYPE_VALUE = 31
} nalUnitType_e;

typedef struct
{
    nalUnitType_e nalUnitType;
    u32 nalRefIdc;
} nalUnit_t;

/*------------------------------------------------------------------------------
    4. Function prototypes
------------------------------------------------------------------------------*/

u32 h264bsdDecodeNalUnit(strmData_t *pStrmData, nalUnit_t *pNalUnit);

#endif /* #ifdef H264SWDEC_NAL_UNIT_H */

