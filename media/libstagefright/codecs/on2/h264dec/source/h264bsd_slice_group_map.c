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
          DecodeInterleavedMap
          DecodeDispersedMap
          DecodeForegroundLeftOverMap
          DecodeBoxOutMap
          DecodeRasterScanMap
          DecodeWipeMap
          h264bsdDecodeSliceGroupMap

------------------------------------------------------------------------------*/

/*------------------------------------------------------------------------------
    1. Include headers
------------------------------------------------------------------------------*/

#include "basetype.h"
#include "h264bsd_slice_group_map.h"
#include "h264bsd_cfg.h"
#include "h264bsd_pic_param_set.h"
#include "h264bsd_util.h"

/*------------------------------------------------------------------------------
    2. External compiler flags
--------------------------------------------------------------------------------

--------------------------------------------------------------------------------
    3. Module defines
------------------------------------------------------------------------------*/

/*------------------------------------------------------------------------------
    4. Local function prototypes
------------------------------------------------------------------------------*/

static void DecodeInterleavedMap(
  u32 *map,
  u32 numSliceGroups,
  u32 *runLength,
  u32 picSize);

static void DecodeDispersedMap(
  u32 *map,
  u32 numSliceGroups,
  u32 picWidth,
  u32 picHeight);

static void DecodeForegroundLeftOverMap(
  u32 *map,
  u32 numSliceGroups,
  u32 *topLeft,
  u32 *bottomRight,
  u32 picWidth,
  u32 picHeight);

static void DecodeBoxOutMap(
  u32 *map,
  u32 sliceGroupChangeDirectionFlag,
  u32 unitsInSliceGroup0,
  u32 picWidth,
  u32 picHeight);

static void DecodeRasterScanMap(
  u32 *map,
  u32 sliceGroupChangeDirectionFlag,
  u32 sizeOfUpperLeftGroup,
  u32 picSize);

static void DecodeWipeMap(
  u32 *map,
  u32 sliceGroupChangeDirectionFlag,
  u32 sizeOfUpperLeftGroup,
  u32 picWidth,
  u32 picHeight);

/*------------------------------------------------------------------------------

    Function: DecodeInterleavedMap

        Functional description:
            Function to decode interleaved slice group map type, i.e. slice
            group map type 0.

        Inputs:
            map             pointer to the map
            numSliceGroups  number of slice groups
            runLength       run_length[] values for each slice group
            picSize         picture size in macroblocks

        Outputs:
            map             slice group map is stored here

        Returns:
            none

------------------------------------------------------------------------------*/

void DecodeInterleavedMap(
  u32 *map,
  u32 numSliceGroups,
  u32 *runLength,
  u32 picSize)
{

/* Variables */

    u32 i,j, group;

/* Code */

    ASSERT(map);
    ASSERT(numSliceGroups >= 1 && numSliceGroups <= MAX_NUM_SLICE_GROUPS);
    ASSERT(runLength);

    i = 0;

    do {
        for (group = 0; group < numSliceGroups && i < picSize;
          i += runLength[group++])
        {
            ASSERT(runLength[group] <= picSize);
            for (j = 0; j < runLength[group] && i + j < picSize; j++)
                map[i+j] = group;
        }
    } while (i < picSize);


}

/*------------------------------------------------------------------------------

    Function: DecodeDispersedMap

        Functional description:
            Function to decode dispersed slice group map type, i.e. slice
            group map type 1.

        Inputs:
            map               pointer to the map
            numSliceGroups    number of slice groups
            picWidth          picture width in macroblocks
            picHeight         picture height in macroblocks

        Outputs:
            map               slice group map is stored here

        Returns:
            none

------------------------------------------------------------------------------*/

void DecodeDispersedMap(
  u32 *map,
  u32 numSliceGroups,
  u32 picWidth,
  u32 picHeight)
{

/* Variables */

    u32 i, picSize;

/* Code */

    ASSERT(map);
    ASSERT(numSliceGroups >= 1 && numSliceGroups <= MAX_NUM_SLICE_GROUPS);
    ASSERT(picWidth);
    ASSERT(picHeight);

    picSize = picWidth * picHeight;

    for (i = 0; i < picSize; i++)
        map[i] = ((i % picWidth) + (((i / picWidth) * numSliceGroups) >> 1)) %
            numSliceGroups;


}

/*------------------------------------------------------------------------------

    Function: DecodeForegroundLeftOverMap

        Functional description:
            Function to decode foreground with left-over slice group map type,
            i.e. slice group map type 2.

        Inputs:
            map               pointer to the map
            numSliceGroups    number of slice groups
            topLeft           top_left[] values
            bottomRight       bottom_right[] values
            picWidth          picture width in macroblocks
            picHeight         picture height in macroblocks

        Outputs:
            map               slice group map is stored here

        Returns:
            none

------------------------------------------------------------------------------*/

void DecodeForegroundLeftOverMap(
  u32 *map,
  u32 numSliceGroups,
  u32 *topLeft,
  u32 *bottomRight,
  u32 picWidth,
  u32 picHeight)
{

/* Variables */

    u32 i,y,x,yTopLeft,yBottomRight,xTopLeft,xBottomRight, picSize;
    u32 group;

/* Code */

    ASSERT(map);
    ASSERT(numSliceGroups >= 1 && numSliceGroups <= MAX_NUM_SLICE_GROUPS);
    ASSERT(topLeft);
    ASSERT(bottomRight);
    ASSERT(picWidth);
    ASSERT(picHeight);

    picSize = picWidth * picHeight;

    for (i = 0; i < picSize; i++)
        map[i] = numSliceGroups - 1;

    for (group = numSliceGroups - 1; group--; )
    {
        ASSERT( topLeft[group] <= bottomRight[group] &&
                bottomRight[group] < picSize );
        yTopLeft = topLeft[group] / picWidth;
        xTopLeft = topLeft[group] % picWidth;
        yBottomRight = bottomRight[group] / picWidth;
        xBottomRight = bottomRight[group] % picWidth;
        ASSERT(xTopLeft <= xBottomRight);

        for (y = yTopLeft; y <= yBottomRight; y++)
            for (x = xTopLeft; x <= xBottomRight; x++)
                map[ y * picWidth + x ] = group;
    }


}

/*------------------------------------------------------------------------------

    Function: DecodeBoxOutMap

        Functional description:
            Function to decode box-out slice group map type, i.e. slice group
            map type 3.

        Inputs:
            map                               pointer to the map
            sliceGroupChangeDirectionFlag     slice_group_change_direction_flag
            unitsInSliceGroup0                mbs on slice group 0
            picWidth                          picture width in macroblocks
            picHeight                         picture height in macroblocks

        Outputs:
            map                               slice group map is stored here

        Returns:
            none

------------------------------------------------------------------------------*/

void DecodeBoxOutMap(
  u32 *map,
  u32 sliceGroupChangeDirectionFlag,
  u32 unitsInSliceGroup0,
  u32 picWidth,
  u32 picHeight)
{

/* Variables */

    u32 i, k, picSize;
    i32 x, y, xDir, yDir, leftBound, topBound, rightBound, bottomBound;
    u32 mapUnitVacant;

/* Code */

    ASSERT(map);
    ASSERT(picWidth);
    ASSERT(picHeight);

    picSize = picWidth * picHeight;
    ASSERT(unitsInSliceGroup0 <= picSize);

    for (i = 0; i < picSize; i++)
        map[i] = 1;

    x = (picWidth - (u32)sliceGroupChangeDirectionFlag) >> 1;
    y = (picHeight - (u32)sliceGroupChangeDirectionFlag) >> 1;

    leftBound = x;
    topBound = y;

    rightBound = x;
    bottomBound = y;

    xDir = (i32)sliceGroupChangeDirectionFlag - 1;
    yDir = (i32)sliceGroupChangeDirectionFlag;

    for (k = 0; k < unitsInSliceGroup0; k += mapUnitVacant ? 1 : 0)
    {
        mapUnitVacant = (map[ (u32)y * picWidth + (u32)x ] == 1) ?
                                        HANTRO_TRUE : HANTRO_FALSE;

        if (mapUnitVacant)
            map[ (u32)y * picWidth + (u32)x ] = 0;

        if (xDir == -1 && x == leftBound)
        {
            leftBound = MAX(leftBound - 1, 0);
            x = leftBound;
            xDir = 0;
            yDir = 2 * (i32)sliceGroupChangeDirectionFlag - 1;
        }
        else if (xDir == 1 && x == rightBound)
        {
            rightBound = MIN(rightBound + 1, (i32)picWidth - 1);
            x = rightBound;
            xDir = 0;
            yDir = 1 - 2 * (i32)sliceGroupChangeDirectionFlag;
        }
        else if (yDir == -1 && y == topBound)
        {
            topBound = MAX(topBound - 1, 0);
            y = topBound;
            xDir = 1 - 2 * (i32)sliceGroupChangeDirectionFlag;
            yDir = 0;
        }
        else if (yDir == 1 && y == bottomBound)
        {
            bottomBound = MIN(bottomBound + 1, (i32)picHeight - 1);
            y = bottomBound;
            xDir = 2 * (i32)sliceGroupChangeDirectionFlag - 1;
            yDir = 0;
        }
        else
        {
            x += xDir;
            y += yDir;
        }
    }


}

/*------------------------------------------------------------------------------

    Function: DecodeRasterScanMap

        Functional description:
            Function to decode raster scan slice group map type, i.e. slice
            group map type 4.

        Inputs:
            map                               pointer to the map
            sliceGroupChangeDirectionFlag     slice_group_change_direction_flag
            sizeOfUpperLeftGroup              mbs in upperLeftGroup
            picSize                           picture size in macroblocks

        Outputs:
            map                               slice group map is stored here

        Returns:
            none

------------------------------------------------------------------------------*/

void DecodeRasterScanMap(
  u32 *map,
  u32 sliceGroupChangeDirectionFlag,
  u32 sizeOfUpperLeftGroup,
  u32 picSize)
{

/* Variables */

    u32 i;

/* Code */

    ASSERT(map);
    ASSERT(picSize);
    ASSERT(sizeOfUpperLeftGroup <= picSize);

    for (i = 0; i < picSize; i++)
        if (i < sizeOfUpperLeftGroup)
            map[i] = (u32)sliceGroupChangeDirectionFlag;
        else
            map[i] = 1 - (u32)sliceGroupChangeDirectionFlag;


}

/*------------------------------------------------------------------------------

    Function: DecodeWipeMap

        Functional description:
            Function to decode wipe slice group map type, i.e. slice group map
            type 5.

        Inputs:
            sliceGroupChangeDirectionFlag     slice_group_change_direction_flag
            sizeOfUpperLeftGroup              mbs in upperLeftGroup
            picWidth                          picture width in macroblocks
            picHeight                         picture height in macroblocks

        Outputs:
            map                               slice group map is stored here

        Returns:
            none

------------------------------------------------------------------------------*/

void DecodeWipeMap(
  u32 *map,
  u32 sliceGroupChangeDirectionFlag,
  u32 sizeOfUpperLeftGroup,
  u32 picWidth,
  u32 picHeight)
{

/* Variables */

    u32 i,j,k;

/* Code */

    ASSERT(map);
    ASSERT(picWidth);
    ASSERT(picHeight);
    ASSERT(sizeOfUpperLeftGroup <= picWidth * picHeight);

    k = 0;
    for (j = 0; j < picWidth; j++)
        for (i = 0; i < picHeight; i++)
            if (k++ < sizeOfUpperLeftGroup)
                map[ i * picWidth + j ] = (u32)sliceGroupChangeDirectionFlag;
            else
                map[ i * picWidth + j ] = 1 -
                    (u32)sliceGroupChangeDirectionFlag;


}

/*------------------------------------------------------------------------------

    Function: h264bsdDecodeSliceGroupMap

        Functional description:
            Function to decode macroblock to slice group map. Construction
            of different slice group map types is handled by separate
            functions defined above. See standard for details how slice group
            maps are computed.

        Inputs:
            pps                     active picture parameter set
            sliceGroupChangeCycle   slice_group_change_cycle
            picWidth                picture width in macroblocks
            picHeight               picture height in macroblocks

        Outputs:
            map                     slice group map is stored here

        Returns:
            none

------------------------------------------------------------------------------*/

void h264bsdDecodeSliceGroupMap(
  u32 *map,
  picParamSet_t *pps,
  u32 sliceGroupChangeCycle,
  u32 picWidth,
  u32 picHeight)
{

/* Variables */

    u32 i, picSize, unitsInSliceGroup0 = 0, sizeOfUpperLeftGroup = 0;

/* Code */

    ASSERT(map);
    ASSERT(pps);
    ASSERT(picWidth);
    ASSERT(picHeight);
    ASSERT(pps->sliceGroupMapType < 7);

    picSize = picWidth * picHeight;

    /* just one slice group -> all macroblocks belong to group 0 */
    if (pps->numSliceGroups == 1)
    {
        H264SwDecMemset(map, 0, picSize * sizeof(u32));
        return;
    }

    if (pps->sliceGroupMapType > 2 && pps->sliceGroupMapType < 6)
    {
        ASSERT(pps->sliceGroupChangeRate &&
               pps->sliceGroupChangeRate <= picSize);

        unitsInSliceGroup0 =
            MIN(sliceGroupChangeCycle * pps->sliceGroupChangeRate, picSize);

        if (pps->sliceGroupMapType == 4 || pps->sliceGroupMapType == 5)
            sizeOfUpperLeftGroup = pps->sliceGroupChangeDirectionFlag ?
                (picSize - unitsInSliceGroup0) : unitsInSliceGroup0;
    }

    switch (pps->sliceGroupMapType)
    {
        case 0:
            DecodeInterleavedMap(map, pps->numSliceGroups,
              pps->runLength, picSize);
            break;

        case 1:
            DecodeDispersedMap(map, pps->numSliceGroups, picWidth,
              picHeight);
            break;

        case 2:
            DecodeForegroundLeftOverMap(map, pps->numSliceGroups,
              pps->topLeft, pps->bottomRight, picWidth, picHeight);
            break;

        case 3:
            DecodeBoxOutMap(map, pps->sliceGroupChangeDirectionFlag,
              unitsInSliceGroup0, picWidth, picHeight);
            break;

        case 4:
            DecodeRasterScanMap(map,
              pps->sliceGroupChangeDirectionFlag, sizeOfUpperLeftGroup,
              picSize);
            break;

        case 5:
            DecodeWipeMap(map, pps->sliceGroupChangeDirectionFlag,
              sizeOfUpperLeftGroup, picWidth, picHeight);
            break;

        default:
            ASSERT(pps->sliceGroupId);
            for (i = 0; i < picSize; i++)
            {
                ASSERT(pps->sliceGroupId[i] < pps->numSliceGroups);
                map[i] = pps->sliceGroupId[i];
            }
            break;
    }

}

