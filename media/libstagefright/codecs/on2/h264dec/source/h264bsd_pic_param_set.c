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
          h264bsdDecodePicParamSet

------------------------------------------------------------------------------*/

/*------------------------------------------------------------------------------
    1. Include headers
------------------------------------------------------------------------------*/

#include "h264bsd_pic_param_set.h"
#include "h264bsd_util.h"
#include "h264bsd_vlc.h"
#include "h264bsd_cfg.h"

/*------------------------------------------------------------------------------
    2. External compiler flags
--------------------------------------------------------------------------------

--------------------------------------------------------------------------------
    3. Module defines
------------------------------------------------------------------------------*/

/* lookup table for ceil(log2(numSliceGroups)), i.e. number of bits needed to
 * represent range [0, numSliceGroups)
 *
 * NOTE: if MAX_NUM_SLICE_GROUPS is higher than 8 this table has to be resized
 * accordingly */
static const u32 CeilLog2NumSliceGroups[8] = {1, 1, 2, 2, 3, 3, 3, 3};

/*------------------------------------------------------------------------------
    4. Local function prototypes
------------------------------------------------------------------------------*/

/*------------------------------------------------------------------------------

    Function name: h264bsdDecodePicParamSet

        Functional description:
            Decode picture parameter set information from the stream.

            Function allocates memory for
                - run lengths if slice group map type is 0
                - top-left and bottom-right arrays if map type is 2
                - for slice group ids if map type is 6

            Validity of some of the slice group mapping information depends
            on the image dimensions which are not known here. Therefore the
            validity has to be checked afterwards, currently in the parameter
            set activation phase.

        Inputs:
            pStrmData       pointer to stream data structure

        Outputs:
            pPicParamSet    decoded information is stored here

        Returns:
            HANTRO_OK       success
            HANTRO_NOK      failure, invalid information or end of stream
            MEMORY_ALLOCATION_ERROR for memory allocation failure


------------------------------------------------------------------------------*/

u32 h264bsdDecodePicParamSet(strmData_t *pStrmData, picParamSet_t *pPicParamSet)
{

/* Variables */

    u32 tmp, i, value;
    i32 itmp;

/* Code */

    ASSERT(pStrmData);
    ASSERT(pPicParamSet);


    H264SwDecMemset(pPicParamSet, 0, sizeof(picParamSet_t));

    tmp = h264bsdDecodeExpGolombUnsigned(pStrmData,
        &pPicParamSet->picParameterSetId);
    if (tmp != HANTRO_OK)
        return(tmp);
    if (pPicParamSet->picParameterSetId >= MAX_NUM_PIC_PARAM_SETS)
    {
        EPRINT("pic_parameter_set_id");
        return(HANTRO_NOK);
    }

    tmp = h264bsdDecodeExpGolombUnsigned(pStrmData,
        &pPicParamSet->seqParameterSetId);
    if (tmp != HANTRO_OK)
        return(tmp);
    if (pPicParamSet->seqParameterSetId >= MAX_NUM_SEQ_PARAM_SETS)
    {
        EPRINT("seq_param_set_id");
        return(HANTRO_NOK);
    }

    /* entropy_coding_mode_flag, shall be 0 for baseline profile */
    tmp = h264bsdGetBits(pStrmData, 1);
    if (tmp)
    {
        EPRINT("entropy_coding_mode_flag");
        return(HANTRO_NOK);
    }

    tmp = h264bsdGetBits(pStrmData, 1);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);
    pPicParamSet->picOrderPresentFlag = (tmp == 1) ? HANTRO_TRUE : HANTRO_FALSE;

    /* num_slice_groups_minus1 */
    tmp = h264bsdDecodeExpGolombUnsigned(pStrmData, &value);
    if (tmp != HANTRO_OK)
        return(tmp);
    pPicParamSet->numSliceGroups = value + 1;
    if (pPicParamSet->numSliceGroups > MAX_NUM_SLICE_GROUPS)
    {
        EPRINT("num_slice_groups_minus1");
        return(HANTRO_NOK);
    }

    /* decode slice group mapping information if more than one slice groups */
    if (pPicParamSet->numSliceGroups > 1)
    {
        tmp = h264bsdDecodeExpGolombUnsigned(pStrmData,
            &pPicParamSet->sliceGroupMapType);
        if (tmp != HANTRO_OK)
            return(tmp);
        if (pPicParamSet->sliceGroupMapType > 6)
        {
            EPRINT("slice_group_map_type");
            return(HANTRO_NOK);
        }

        if (pPicParamSet->sliceGroupMapType == 0)
        {
            ALLOCATE(pPicParamSet->runLength,
                pPicParamSet->numSliceGroups, u32);
            if (pPicParamSet->runLength == NULL)
                return(MEMORY_ALLOCATION_ERROR);
            for (i = 0; i < pPicParamSet->numSliceGroups; i++)
            {
                tmp = h264bsdDecodeExpGolombUnsigned(pStrmData, &value);
                if (tmp != HANTRO_OK)
                    return(tmp);
                pPicParamSet->runLength[i] = value+1;
                /* param values checked in CheckPps() */
            }
        }
        else if (pPicParamSet->sliceGroupMapType == 2)
        {
            ALLOCATE(pPicParamSet->topLeft,
                pPicParamSet->numSliceGroups - 1, u32);
            ALLOCATE(pPicParamSet->bottomRight,
                pPicParamSet->numSliceGroups - 1, u32);
            if (pPicParamSet->topLeft == NULL ||
                pPicParamSet->bottomRight == NULL)
                return(MEMORY_ALLOCATION_ERROR);
            for (i = 0; i < pPicParamSet->numSliceGroups - 1; i++)
            {
                tmp = h264bsdDecodeExpGolombUnsigned(pStrmData, &value);
                if (tmp != HANTRO_OK)
                    return(tmp);
                pPicParamSet->topLeft[i] = value;
                tmp = h264bsdDecodeExpGolombUnsigned(pStrmData, &value);
                if (tmp != HANTRO_OK)
                    return(tmp);
                pPicParamSet->bottomRight[i] = value;
                /* param values checked in CheckPps() */
            }
        }
        else if ( (pPicParamSet->sliceGroupMapType == 3) ||
                  (pPicParamSet->sliceGroupMapType == 4) ||
                  (pPicParamSet->sliceGroupMapType == 5) )
        {
            tmp = h264bsdGetBits(pStrmData, 1);
            if (tmp == END_OF_STREAM)
                return(HANTRO_NOK);
            pPicParamSet->sliceGroupChangeDirectionFlag =
                (tmp == 1) ? HANTRO_TRUE : HANTRO_FALSE;
            tmp = h264bsdDecodeExpGolombUnsigned(pStrmData, &value);
            if (tmp != HANTRO_OK)
                return(tmp);
            pPicParamSet->sliceGroupChangeRate = value + 1;
            /* param value checked in CheckPps() */
        }
        else if (pPicParamSet->sliceGroupMapType == 6)
        {
            tmp = h264bsdDecodeExpGolombUnsigned(pStrmData, &value);
            if (tmp != HANTRO_OK)
                return(tmp);
            pPicParamSet->picSizeInMapUnits = value + 1;

            ALLOCATE(pPicParamSet->sliceGroupId,
                pPicParamSet->picSizeInMapUnits, u32);
            if (pPicParamSet->sliceGroupId == NULL)
                return(MEMORY_ALLOCATION_ERROR);

            /* determine number of bits needed to represent range
             * [0, numSliceGroups) */
            tmp = CeilLog2NumSliceGroups[pPicParamSet->numSliceGroups-1];

            for (i = 0; i < pPicParamSet->picSizeInMapUnits; i++)
            {
                pPicParamSet->sliceGroupId[i] = h264bsdGetBits(pStrmData, tmp);
                if ( pPicParamSet->sliceGroupId[i] >=
                     pPicParamSet->numSliceGroups )
                {
                    EPRINT("slice_group_id");
                    return(HANTRO_NOK);
                }
            }
        }
    }

    /* num_ref_idx_l0_active_minus1 */
    tmp = h264bsdDecodeExpGolombUnsigned(pStrmData, &value);
    if (tmp != HANTRO_OK)
        return(tmp);
    if (value > 31)
    {
        EPRINT("num_ref_idx_l0_active_minus1");
        return(HANTRO_NOK);
    }
    pPicParamSet->numRefIdxL0Active = value + 1;

    /* num_ref_idx_l1_active_minus1 */
    tmp = h264bsdDecodeExpGolombUnsigned(pStrmData, &value);
    if (tmp != HANTRO_OK)
        return(tmp);
    if (value > 31)
    {
        EPRINT("num_ref_idx_l1_active_minus1");
        return(HANTRO_NOK);
    }

    /* weighted_pred_flag, this shall be 0 for baseline profile */
    tmp = h264bsdGetBits(pStrmData, 1);
    if (tmp)
    {
        EPRINT("weighted_pred_flag");
        return(HANTRO_NOK);
    }

    /* weighted_bipred_idc */
    tmp = h264bsdGetBits(pStrmData, 2);
    if (tmp > 2)
    {
        EPRINT("weighted_bipred_idc");
        return(HANTRO_NOK);
    }

    /* pic_init_qp_minus26 */
    tmp = h264bsdDecodeExpGolombSigned(pStrmData, &itmp);
    if (tmp != HANTRO_OK)
        return(tmp);
    if ((itmp < -26) || (itmp > 25))
    {
        EPRINT("pic_init_qp_minus26");
        return(HANTRO_NOK);
    }
    pPicParamSet->picInitQp = (u32)(itmp + 26);

    /* pic_init_qs_minus26 */
    tmp = h264bsdDecodeExpGolombSigned(pStrmData, &itmp);
    if (tmp != HANTRO_OK)
        return(tmp);
    if ((itmp < -26) || (itmp > 25))
    {
        EPRINT("pic_init_qs_minus26");
        return(HANTRO_NOK);
    }

    tmp = h264bsdDecodeExpGolombSigned(pStrmData, &itmp);
    if (tmp != HANTRO_OK)
        return(tmp);
    if ((itmp < -12) || (itmp > 12))
    {
        EPRINT("chroma_qp_index_offset");
        return(HANTRO_NOK);
    }
    pPicParamSet->chromaQpIndexOffset = itmp;

    tmp = h264bsdGetBits(pStrmData, 1);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);
    pPicParamSet->deblockingFilterControlPresentFlag =
        (tmp == 1) ? HANTRO_TRUE : HANTRO_FALSE;

    tmp = h264bsdGetBits(pStrmData, 1);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);
    pPicParamSet->constrainedIntraPredFlag = (tmp == 1) ?
                                    HANTRO_TRUE : HANTRO_FALSE;

    tmp = h264bsdGetBits(pStrmData, 1);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);
    pPicParamSet->redundantPicCntPresentFlag = (tmp == 1) ?
                                    HANTRO_TRUE : HANTRO_FALSE;

    tmp = h264bsdRbspTrailingBits(pStrmData);

    /* ignore possible errors in trailing bits of parameters sets */
    return(HANTRO_OK);

}

