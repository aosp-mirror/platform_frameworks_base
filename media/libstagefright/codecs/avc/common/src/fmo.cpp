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
#include <string.h>

#include "avclib_common.h"

/* see subclause 8.2.2 Decoding process for macroblock to slice group map */
OSCL_EXPORT_REF AVCStatus FMOInit(AVCCommonObj *video)
{
    AVCPicParamSet *currPPS = video->currPicParams;
    int *MbToSliceGroupMap = video->MbToSliceGroupMap;
    int PicSizeInMapUnits = video->PicSizeInMapUnits;
    int PicWidthInMbs = video->PicWidthInMbs;

    if (currPPS->num_slice_groups_minus1 == 0)
    {
        memset(video->MbToSliceGroupMap, 0, video->PicSizeInMapUnits*sizeof(uint));
    }
    else
    {
        switch (currPPS->slice_group_map_type)
        {
            case 0:
                FmoGenerateType0MapUnitMap(MbToSliceGroupMap, currPPS->run_length_minus1, currPPS->num_slice_groups_minus1, PicSizeInMapUnits);
                break;
            case 1:
                FmoGenerateType1MapUnitMap(MbToSliceGroupMap, PicWidthInMbs, currPPS->num_slice_groups_minus1, PicSizeInMapUnits);
                break;
            case 2:
                FmoGenerateType2MapUnitMap(currPPS, MbToSliceGroupMap, PicWidthInMbs, currPPS->num_slice_groups_minus1, PicSizeInMapUnits);
                break;
            case 3:
                FmoGenerateType3MapUnitMap(video, currPPS, MbToSliceGroupMap, PicWidthInMbs);
                break;
            case 4:
                FmoGenerateType4MapUnitMap(MbToSliceGroupMap, video->MapUnitsInSliceGroup0, currPPS->slice_group_change_direction_flag, PicSizeInMapUnits);
                break;
            case 5:
                FmoGenerateType5MapUnitMap(MbToSliceGroupMap, video, currPPS->slice_group_change_direction_flag, PicSizeInMapUnits);
                break;
            case 6:
                FmoGenerateType6MapUnitMap(MbToSliceGroupMap, (int*)currPPS->slice_group_id, PicSizeInMapUnits);
                break;
            default:
                return AVC_FAIL; /* out of range, shouldn't come this far */
        }
    }

    return AVC_SUCCESS;
}

/* see subclause 8.2.2.1 interleaved slice group map type*/
void FmoGenerateType0MapUnitMap(int *mapUnitToSliceGroupMap, uint *run_length_minus1, uint num_slice_groups_minus1, uint PicSizeInMapUnits)
{
    uint iGroup, j;
    uint i = 0;
    do
    {
        for (iGroup = 0;
                (iGroup <= num_slice_groups_minus1) && (i < PicSizeInMapUnits);
                i += run_length_minus1[iGroup++] + 1)
        {
            for (j = 0; j <= run_length_minus1[ iGroup ] && i + j < PicSizeInMapUnits; j++)
                mapUnitToSliceGroupMap[i+j] = iGroup;
        }
    }
    while (i < PicSizeInMapUnits);
}

/* see subclause 8.2.2.2 dispersed slice group map type*/
void FmoGenerateType1MapUnitMap(int *mapUnitToSliceGroupMap, int PicWidthInMbs, uint num_slice_groups_minus1, uint PicSizeInMapUnits)
{
    uint i;
    for (i = 0; i < PicSizeInMapUnits; i++)
    {
        mapUnitToSliceGroupMap[i] = ((i % PicWidthInMbs) + (((i / PicWidthInMbs) * (num_slice_groups_minus1 + 1)) / 2))
                                    % (num_slice_groups_minus1 + 1);
    }
}

/* see subclause 8.2.2.3 foreground with left-over slice group map type */
void FmoGenerateType2MapUnitMap(AVCPicParamSet *pps, int *mapUnitToSliceGroupMap, int PicWidthInMbs,
                                uint num_slice_groups_minus1, uint PicSizeInMapUnits)
{
    int iGroup;
    uint i, x, y;
    uint yTopLeft, xTopLeft, yBottomRight, xBottomRight;

    for (i = 0; i < PicSizeInMapUnits; i++)
    {
        mapUnitToSliceGroupMap[ i ] = num_slice_groups_minus1;
    }

    for (iGroup = num_slice_groups_minus1 - 1 ; iGroup >= 0; iGroup--)
    {
        yTopLeft = pps->top_left[ iGroup ] / PicWidthInMbs;
        xTopLeft = pps->top_left[ iGroup ] % PicWidthInMbs;
        yBottomRight = pps->bottom_right[ iGroup ] / PicWidthInMbs;
        xBottomRight = pps->bottom_right[ iGroup ] % PicWidthInMbs;
        for (y = yTopLeft; y <= yBottomRight; y++)
        {
            for (x = xTopLeft; x <= xBottomRight; x++)
            {
                mapUnitToSliceGroupMap[ y * PicWidthInMbs + x ] = iGroup;
            }
        }
    }
}


/* see subclause 8.2.2.4 box-out slice group map type */
/* follow the text rather than the JM, it's quite different. */
void FmoGenerateType3MapUnitMap(AVCCommonObj *video, AVCPicParamSet* pps, int *mapUnitToSliceGroupMap,
                                int PicWidthInMbs)
{
    uint i, k;
    int leftBound, topBound, rightBound, bottomBound;
    int x, y, xDir, yDir;
    int mapUnitVacant;
    uint PicSizeInMapUnits = video->PicSizeInMapUnits;
    uint MapUnitsInSliceGroup0 = video->MapUnitsInSliceGroup0;

    for (i = 0; i < PicSizeInMapUnits; i++)
    {
        mapUnitToSliceGroupMap[ i ] = 1;
    }

    x = (PicWidthInMbs - pps->slice_group_change_direction_flag) / 2;
    y = (video->PicHeightInMapUnits - pps->slice_group_change_direction_flag) / 2;

    leftBound   = x;
    topBound    = y;
    rightBound  = x;
    bottomBound = y;

    xDir =  pps->slice_group_change_direction_flag - 1;
    yDir =  pps->slice_group_change_direction_flag;

    for (k = 0; k < MapUnitsInSliceGroup0; k += mapUnitVacant)
    {
        mapUnitVacant = (mapUnitToSliceGroupMap[ y * PicWidthInMbs + x ]  ==  1);
        if (mapUnitVacant)
        {
            mapUnitToSliceGroupMap[ y * PicWidthInMbs + x ] = 0;
        }

        if (xDir  ==  -1  &&  x  ==  leftBound)
        {
            leftBound = AVC_MAX(leftBound - 1, 0);
            x = leftBound;
            xDir = 0;
            yDir = 2 * pps->slice_group_change_direction_flag - 1;
        }
        else if (xDir  ==  1  &&  x  ==  rightBound)
        {
            rightBound = AVC_MIN(rightBound + 1, (int)PicWidthInMbs - 1);
            x = rightBound;
            xDir = 0;
            yDir = 1 - 2 * pps->slice_group_change_direction_flag;
        }
        else if (yDir  ==  -1  &&  y  ==  topBound)
        {
            topBound = AVC_MAX(topBound - 1, 0);
            y = topBound;
            xDir = 1 - 2 * pps->slice_group_change_direction_flag;
            yDir = 0;
        }
        else  if (yDir  ==  1  &&  y  ==  bottomBound)
        {
            bottomBound = AVC_MIN(bottomBound + 1, (int)video->PicHeightInMapUnits - 1);
            y = bottomBound;
            xDir = 2 * pps->slice_group_change_direction_flag - 1;
            yDir = 0;
        }
        else
        {
            x = x + xDir;
            y = y + yDir;
        }
    }
}

/* see subclause 8.2.2.5 raster scan slice group map types */
void FmoGenerateType4MapUnitMap(int *mapUnitToSliceGroupMap, int MapUnitsInSliceGroup0, int slice_group_change_direction_flag, uint PicSizeInMapUnits)
{
    uint sizeOfUpperLeftGroup = slice_group_change_direction_flag ? (PicSizeInMapUnits - MapUnitsInSliceGroup0) : MapUnitsInSliceGroup0;

    uint i;

    for (i = 0; i < PicSizeInMapUnits; i++)
        if (i < sizeOfUpperLeftGroup)
            mapUnitToSliceGroupMap[ i ] = 1 - slice_group_change_direction_flag;
        else
            mapUnitToSliceGroupMap[ i ] = slice_group_change_direction_flag;

}

/* see subclause 8.2.2.6, wipe slice group map type. */
void FmoGenerateType5MapUnitMap(int *mapUnitToSliceGroupMap, AVCCommonObj *video,
                                int slice_group_change_direction_flag, uint PicSizeInMapUnits)
{
    int PicWidthInMbs = video->PicWidthInMbs;
    int PicHeightInMapUnits = video->PicHeightInMapUnits;
    int MapUnitsInSliceGroup0 = video->MapUnitsInSliceGroup0;
    int sizeOfUpperLeftGroup = slice_group_change_direction_flag ? (PicSizeInMapUnits - MapUnitsInSliceGroup0) : MapUnitsInSliceGroup0;
    int i, j, k = 0;

    for (j = 0; j < PicWidthInMbs; j++)
    {
        for (i = 0; i < PicHeightInMapUnits; i++)
        {
            if (k++ < sizeOfUpperLeftGroup)
            {
                mapUnitToSliceGroupMap[ i * PicWidthInMbs + j ] = 1 - slice_group_change_direction_flag;
            }
            else
            {
                mapUnitToSliceGroupMap[ i * PicWidthInMbs + j ] = slice_group_change_direction_flag;
            }
        }
    }
}

/* see subclause 8.2.2.7, explicit slice group map */
void FmoGenerateType6MapUnitMap(int *mapUnitToSliceGroupMap, int *slice_group_id, uint PicSizeInMapUnits)
{
    uint i;
    for (i = 0; i < PicSizeInMapUnits; i++)
    {
        mapUnitToSliceGroupMap[i] = slice_group_id[i];
    }
}


