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

#ifndef H264SWDEC_NEIGHBOUR_H
#define H264SWDEC_NEIGHBOUR_H

/*------------------------------------------------------------------------------
    1. Include headers
------------------------------------------------------------------------------*/

#include "basetype.h"
#include "h264bsd_macroblock_layer.h"

/*------------------------------------------------------------------------------
    2. Module defines
------------------------------------------------------------------------------*/

typedef enum {
    MB_A = 0,
    MB_B,
    MB_C,
    MB_D,
    MB_CURR,
    MB_NA = 0xFF
} neighbourMb_e;

/*------------------------------------------------------------------------------
    3. Data types
------------------------------------------------------------------------------*/

typedef struct
{
    neighbourMb_e   mb;
    u8             index;
} neighbour_t;

/*------------------------------------------------------------------------------
    4. Function prototypes
------------------------------------------------------------------------------*/

void h264bsdInitMbNeighbours(mbStorage_t *pMbStorage, u32 picWidth,
    u32 picSizeInMbs);

mbStorage_t* h264bsdGetNeighbourMb(mbStorage_t *pMb, neighbourMb_e neighbour);

u32 h264bsdIsNeighbourAvailable(mbStorage_t *pMb, mbStorage_t *pNeighbour);

const neighbour_t* h264bsdNeighbour4x4BlockA(u32 blockIndex);
const neighbour_t* h264bsdNeighbour4x4BlockB(u32 blockIndex);
const neighbour_t* h264bsdNeighbour4x4BlockC(u32 blockIndex);
const neighbour_t* h264bsdNeighbour4x4BlockD(u32 blockIndex);

#endif /* #ifdef H264SWDEC_NEIGHBOUR_H */

