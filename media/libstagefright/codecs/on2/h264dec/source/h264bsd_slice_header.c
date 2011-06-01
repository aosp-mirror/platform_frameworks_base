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
          h264bsdDecodeSliceHeader
          NumSliceGroupChangeCycleBits
          RefPicListReordering
          DecRefPicMarking
          CheckPpsId
          CheckFrameNum
          CheckIdrPicId
          CheckPicOrderCntLsb
          CheckDeltaPicOrderCntBottom
          CheckDeltaPicOrderCnt
          CheckRedundantPicCnt

------------------------------------------------------------------------------*/

/*------------------------------------------------------------------------------
    1. Include headers
------------------------------------------------------------------------------*/

#include "h264bsd_slice_header.h"
#include "h264bsd_util.h"
#include "h264bsd_vlc.h"
#include "h264bsd_nal_unit.h"
#include "h264bsd_dpb.h"

/*------------------------------------------------------------------------------
    2. External compiler flags
--------------------------------------------------------------------------------

--------------------------------------------------------------------------------
    3. Module defines
------------------------------------------------------------------------------*/

/*------------------------------------------------------------------------------
    4. Local function prototypes
------------------------------------------------------------------------------*/

static u32 RefPicListReordering(strmData_t *, refPicListReordering_t *,
    u32, u32);

static u32 NumSliceGroupChangeCycleBits(u32 picSizeInMbs,
    u32 sliceGroupChangeRate);

static u32 DecRefPicMarking(strmData_t *pStrmData,
    decRefPicMarking_t *pDecRefPicMarking, nalUnitType_e nalUnitType,
    u32 numRefFrames);


/*------------------------------------------------------------------------------

    Function name: h264bsdDecodeSliceHeader

        Functional description:
            Decode slice header data from the stream.

        Inputs:
            pStrmData       pointer to stream data structure
            pSeqParamSet    pointer to active sequence parameter set
            pPicParamSet    pointer to active picture parameter set
            pNalUnit        pointer to current NAL unit structure

        Outputs:
            pSliceHeader    decoded data is stored here

        Returns:
            HANTRO_OK       success
            HANTRO_NOK      invalid stream data or end of stream

------------------------------------------------------------------------------*/

u32 h264bsdDecodeSliceHeader(strmData_t *pStrmData, sliceHeader_t *pSliceHeader,
    seqParamSet_t *pSeqParamSet, picParamSet_t *pPicParamSet,
    nalUnit_t *pNalUnit)
{

/* Variables */

    u32 tmp, i, value;
    i32 itmp;
    u32 picSizeInMbs;

/* Code */

    ASSERT(pStrmData);
    ASSERT(pSliceHeader);
    ASSERT(pSeqParamSet);
    ASSERT(pPicParamSet);
    ASSERT( pNalUnit->nalUnitType == NAL_CODED_SLICE ||
            pNalUnit->nalUnitType == NAL_CODED_SLICE_IDR );


    H264SwDecMemset(pSliceHeader, 0, sizeof(sliceHeader_t));

    picSizeInMbs = pSeqParamSet->picWidthInMbs * pSeqParamSet->picHeightInMbs;
    tmp = h264bsdDecodeExpGolombUnsigned(pStrmData, &value);
    if (tmp != HANTRO_OK)
        return(tmp);
    pSliceHeader->firstMbInSlice = value;
    if (value >= picSizeInMbs)
    {
        EPRINT("first_mb_in_slice");
        return(HANTRO_NOK);
    }

    tmp = h264bsdDecodeExpGolombUnsigned(pStrmData, &value);
    if (tmp != HANTRO_OK)
        return(tmp);
    pSliceHeader->sliceType = value;
    /* slice type has to be either I or P slice. P slice is not allowed when
     * current NAL unit is an IDR NAL unit or num_ref_frames is 0 */
    if ( !IS_I_SLICE(pSliceHeader->sliceType) &&
         ( !IS_P_SLICE(pSliceHeader->sliceType) ||
           IS_IDR_NAL_UNIT(pNalUnit) ||
           !pSeqParamSet->numRefFrames ) )
    {
        EPRINT("slice_type");
        return(HANTRO_NOK);
    }

    tmp = h264bsdDecodeExpGolombUnsigned(pStrmData, &value);
    if (tmp != HANTRO_OK)
        return(tmp);
    pSliceHeader->picParameterSetId = value;
    if (pSliceHeader->picParameterSetId != pPicParamSet->picParameterSetId)
    {
        EPRINT("pic_parameter_set_id");
        return(HANTRO_NOK);
    }

    /* log2(maxFrameNum) -> num bits to represent frame_num */
    i = 0;
    while (pSeqParamSet->maxFrameNum >> i)
        i++;
    i--;

    tmp = h264bsdGetBits(pStrmData, i);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);
    if (IS_IDR_NAL_UNIT(pNalUnit) && tmp != 0)
    {
        EPRINT("frame_num");
        return(HANTRO_NOK);
    }
    pSliceHeader->frameNum = tmp;

    if (IS_IDR_NAL_UNIT(pNalUnit))
    {
        tmp = h264bsdDecodeExpGolombUnsigned(pStrmData, &value);
        if (tmp != HANTRO_OK)
            return(tmp);
        pSliceHeader->idrPicId = value;
        if (value > 65535)
        {
            EPRINT("idr_pic_id");
            return(HANTRO_NOK);
        }
    }

    if (pSeqParamSet->picOrderCntType == 0)
    {
        /* log2(maxPicOrderCntLsb) -> num bits to represent pic_order_cnt_lsb */
        i = 0;
        while (pSeqParamSet->maxPicOrderCntLsb >> i)
            i++;
        i--;

        tmp = h264bsdGetBits(pStrmData, i);
        if (tmp == END_OF_STREAM)
            return(HANTRO_NOK);
        pSliceHeader->picOrderCntLsb = tmp;

        if (pPicParamSet->picOrderPresentFlag)
        {
            tmp = h264bsdDecodeExpGolombSigned(pStrmData, &itmp);
            if (tmp != HANTRO_OK)
                return(tmp);
            pSliceHeader->deltaPicOrderCntBottom = itmp;
        }

        /* check that picOrderCnt for IDR picture will be zero. See
         * DecodePicOrderCnt function to understand the logic here */
        if ( IS_IDR_NAL_UNIT(pNalUnit) &&
             ( (pSliceHeader->picOrderCntLsb >
                pSeqParamSet->maxPicOrderCntLsb/2) ||
                MIN((i32)pSliceHeader->picOrderCntLsb,
                    (i32)pSliceHeader->picOrderCntLsb +
                    pSliceHeader->deltaPicOrderCntBottom) != 0 ) )
        {
            return(HANTRO_NOK);
        }
    }

    if ( (pSeqParamSet->picOrderCntType == 1) &&
         !pSeqParamSet->deltaPicOrderAlwaysZeroFlag )
    {
        tmp = h264bsdDecodeExpGolombSigned(pStrmData, &itmp);
        if (tmp != HANTRO_OK)
            return(tmp);
        pSliceHeader->deltaPicOrderCnt[0] = itmp;

        if (pPicParamSet->picOrderPresentFlag)
        {
            tmp = h264bsdDecodeExpGolombSigned(pStrmData, &itmp);
            if (tmp != HANTRO_OK)
                return(tmp);
            pSliceHeader->deltaPicOrderCnt[1] = itmp;
        }

        /* check that picOrderCnt for IDR picture will be zero. See
         * DecodePicOrderCnt function to understand the logic here */
        if ( IS_IDR_NAL_UNIT(pNalUnit) &&
             MIN(pSliceHeader->deltaPicOrderCnt[0],
                 pSliceHeader->deltaPicOrderCnt[0] +
                 pSeqParamSet->offsetForTopToBottomField +
                 pSliceHeader->deltaPicOrderCnt[1]) != 0)
        {
            return(HANTRO_NOK);
        }
    }

    if (pPicParamSet->redundantPicCntPresentFlag)
    {
        tmp = h264bsdDecodeExpGolombUnsigned(pStrmData, &value);
        if (tmp != HANTRO_OK)
            return(tmp);
        pSliceHeader->redundantPicCnt = value;
        if (value > 127)
        {
            EPRINT("redundant_pic_cnt");
            return(HANTRO_NOK);
        }
    }

    if (IS_P_SLICE(pSliceHeader->sliceType))
    {
        tmp = h264bsdGetBits(pStrmData, 1);
        if (tmp == END_OF_STREAM)
            return(HANTRO_NOK);
        pSliceHeader->numRefIdxActiveOverrideFlag = tmp;

        if (pSliceHeader->numRefIdxActiveOverrideFlag)
        {
            tmp = h264bsdDecodeExpGolombUnsigned(pStrmData, &value);
            if (tmp != HANTRO_OK)
                return(tmp);
            if (value > 15)
            {
                EPRINT("num_ref_idx_l0_active_minus1");
                return(HANTRO_NOK);
            }
            pSliceHeader->numRefIdxL0Active = value + 1;
        }
        /* set numRefIdxL0Active from pic param set */
        else
        {
            /* if value (minus1) in picture parameter set exceeds 15 it should
             * have been overridden here */
            if (pPicParamSet->numRefIdxL0Active > 16)
            {
                EPRINT("num_ref_idx_active_override_flag");
                return(HANTRO_NOK);
            }
            pSliceHeader->numRefIdxL0Active = pPicParamSet->numRefIdxL0Active;
        }
    }

    if (IS_P_SLICE(pSliceHeader->sliceType))
    {
        tmp = RefPicListReordering(pStrmData,
            &pSliceHeader->refPicListReordering,
            pSliceHeader->numRefIdxL0Active,
            pSeqParamSet->maxFrameNum);
        if (tmp != HANTRO_OK)
            return(tmp);
    }

    if (pNalUnit->nalRefIdc != 0)
    {
        tmp = DecRefPicMarking(pStrmData, &pSliceHeader->decRefPicMarking,
            pNalUnit->nalUnitType, pSeqParamSet->numRefFrames);
        if (tmp != HANTRO_OK)
            return(tmp);
    }

    /* decode sliceQpDelta and check that initial QP for the slice will be on
     * the range [0, 51] */
    tmp = h264bsdDecodeExpGolombSigned(pStrmData, &itmp);
    if (tmp != HANTRO_OK)
        return(tmp);
    pSliceHeader->sliceQpDelta = itmp;
    itmp += (i32)pPicParamSet->picInitQp;
    if ( (itmp < 0) || (itmp > 51) )
    {
        EPRINT("slice_qp_delta");
        return(HANTRO_NOK);
    }

    if (pPicParamSet->deblockingFilterControlPresentFlag)
    {
        tmp = h264bsdDecodeExpGolombUnsigned(pStrmData, &value);
        if (tmp != HANTRO_OK)
            return(tmp);
        pSliceHeader->disableDeblockingFilterIdc = value;
        if (pSliceHeader->disableDeblockingFilterIdc > 2)
        {
            EPRINT("disable_deblocking_filter_idc");
            return(HANTRO_NOK);
        }

        if (pSliceHeader->disableDeblockingFilterIdc != 1)
        {
            tmp = h264bsdDecodeExpGolombSigned(pStrmData, &itmp);
            if (tmp != HANTRO_OK)
                return(tmp);
            if ( (itmp < -6) || (itmp > 6) )
            {
               EPRINT("slice_alpha_c0_offset_div2");
               return(HANTRO_NOK);
            }
            pSliceHeader->sliceAlphaC0Offset = itmp * 2;

            tmp = h264bsdDecodeExpGolombSigned(pStrmData, &itmp);
            if (tmp != HANTRO_OK)
                return(tmp);
            if ( (itmp < -6) || (itmp > 6) )
            {
               EPRINT("slice_beta_offset_div2");
               return(HANTRO_NOK);
            }
            pSliceHeader->sliceBetaOffset = itmp * 2;
        }
    }

    if ( (pPicParamSet->numSliceGroups > 1) &&
         (pPicParamSet->sliceGroupMapType >= 3) &&
         (pPicParamSet->sliceGroupMapType <= 5) )
    {
        /* set tmp to number of bits used to represent slice_group_change_cycle
         * in the stream */
        tmp = NumSliceGroupChangeCycleBits(picSizeInMbs,
            pPicParamSet->sliceGroupChangeRate);
        value = h264bsdGetBits(pStrmData, tmp);
        if (value == END_OF_STREAM)
            return(HANTRO_NOK);
        pSliceHeader->sliceGroupChangeCycle = value;

        /* corresponds to tmp = Ceil(picSizeInMbs / sliceGroupChangeRate) */
        tmp = (picSizeInMbs + pPicParamSet->sliceGroupChangeRate - 1) /
              pPicParamSet->sliceGroupChangeRate;
        if (pSliceHeader->sliceGroupChangeCycle > tmp)
        {
            EPRINT("slice_group_change_cycle");
            return(HANTRO_NOK);
        }
    }

    return(HANTRO_OK);

}

/*------------------------------------------------------------------------------

    Function: NumSliceGroupChangeCycleBits

        Functional description:
            Determine number of bits needed to represent
            slice_group_change_cycle in the stream. The standard states that
            slice_group_change_cycle is represented by
                Ceil( Log2( (picSizeInMbs / sliceGroupChangeRate) + 1) )

            bits. Division "/" in the equation is non-truncating division.

        Inputs:
            picSizeInMbs            picture size in macroblocks
            sliceGroupChangeRate

        Outputs:
            none

        Returns:
            number of bits needed

------------------------------------------------------------------------------*/

u32 NumSliceGroupChangeCycleBits(u32 picSizeInMbs, u32 sliceGroupChangeRate)
{

/* Variables */

    u32 tmp,numBits,mask;

/* Code */

    ASSERT(picSizeInMbs);
    ASSERT(sliceGroupChangeRate);
    ASSERT(sliceGroupChangeRate <= picSizeInMbs);

    /* compute (picSizeInMbs / sliceGroupChangeRate + 1), rounded up */
    if (picSizeInMbs % sliceGroupChangeRate)
        tmp = 2 + picSizeInMbs/sliceGroupChangeRate;
    else
        tmp = 1 + picSizeInMbs/sliceGroupChangeRate;

    numBits = 0;
    mask = ~0U;

    /* set numBits to position of right-most non-zero bit */
    while (tmp & (mask<<++numBits))
        ;
    numBits--;

    /* add one more bit if value greater than 2^numBits */
    if (tmp & ((1<<numBits)-1))
        numBits++;

    return(numBits);

}

/*------------------------------------------------------------------------------

    Function: RefPicListReordering

        Functional description:
            Decode reference picture list reordering syntax elements from
            the stream. Max number of reordering commands is numRefIdxActive.

        Inputs:
            pStrmData       pointer to stream data structure
            numRefIdxActive number of active reference indices to be used for
                            current slice
            maxPicNum       maxFrameNum from the active SPS

        Outputs:
            pRefPicListReordering   decoded data is stored here

        Returns:
            HANTRO_OK       success
            HANTRO_NOK      invalid stream data

------------------------------------------------------------------------------*/

u32 RefPicListReordering(strmData_t *pStrmData,
    refPicListReordering_t *pRefPicListReordering, u32 numRefIdxActive,
    u32 maxPicNum)
{

/* Variables */

    u32 tmp, value, i;
    u32 command;

/* Code */

    ASSERT(pStrmData);
    ASSERT(pRefPicListReordering);
    ASSERT(numRefIdxActive);
    ASSERT(maxPicNum);


    tmp = h264bsdGetBits(pStrmData, 1);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);

    pRefPicListReordering->refPicListReorderingFlagL0 = tmp;

    if (pRefPicListReordering->refPicListReorderingFlagL0)
    {
        i = 0;

        do
        {
            if (i > numRefIdxActive)
            {
                EPRINT("Too many reordering commands");
                return(HANTRO_NOK);
            }

            tmp = h264bsdDecodeExpGolombUnsigned(pStrmData, &command);
            if (tmp != HANTRO_OK)
                return(tmp);
            if (command > 3)
            {
                EPRINT("reordering_of_pic_nums_idc");
                return(HANTRO_NOK);
            }

            pRefPicListReordering->command[i].reorderingOfPicNumsIdc = command;

            if ((command == 0) || (command == 1))
            {
                tmp = h264bsdDecodeExpGolombUnsigned(pStrmData, &value);
                if (tmp != HANTRO_OK)
                    return(tmp);
                if (value >= maxPicNum)
                {
                    EPRINT("abs_diff_pic_num_minus1");
                    return(HANTRO_NOK);
                }
                pRefPicListReordering->command[i].absDiffPicNum = value + 1;
                            }
            else if (command == 2)
            {
                tmp = h264bsdDecodeExpGolombUnsigned(pStrmData, &value);
                if (tmp != HANTRO_OK)
                    return(tmp);
                pRefPicListReordering->command[i].longTermPicNum = value;
                            }
            i++;
        } while (command != 3);

        /* there shall be at least one reordering command if
         * refPicListReorderingFlagL0 was set */
        if (i == 1)
        {
            EPRINT("ref_pic_list_reordering");
            return(HANTRO_NOK);
        }
    }

    return(HANTRO_OK);

}

/*------------------------------------------------------------------------------

    Function: DecRefPicMarking

        Functional description:
            Decode decoded reference picture marking syntax elements from
            the stream.

        Inputs:
            pStrmData       pointer to stream data structure
            nalUnitType     type of the current NAL unit
            numRefFrames    max number of reference frames from the active SPS

        Outputs:
            pDecRefPicMarking   decoded data is stored here

        Returns:
            HANTRO_OK       success
            HANTRO_NOK      invalid stream data

------------------------------------------------------------------------------*/

u32 DecRefPicMarking(strmData_t *pStrmData,
    decRefPicMarking_t *pDecRefPicMarking, nalUnitType_e nalUnitType,
    u32 numRefFrames)
{

/* Variables */

    u32 tmp, value;
    u32 i;
    u32 operation;
    /* variables for error checking purposes, store number of memory
     * management operations of certain type */
    u32 num4 = 0, num5 = 0, num6 = 0, num1to3 = 0;

/* Code */

    ASSERT( nalUnitType == NAL_CODED_SLICE_IDR ||
            nalUnitType == NAL_CODED_SLICE ||
            nalUnitType == NAL_SEI );


    if (nalUnitType == NAL_CODED_SLICE_IDR)
    {
        tmp = h264bsdGetBits(pStrmData, 1);
        if (tmp == END_OF_STREAM)
            return(HANTRO_NOK);
        pDecRefPicMarking->noOutputOfPriorPicsFlag = tmp;

        tmp = h264bsdGetBits(pStrmData, 1);
        if (tmp == END_OF_STREAM)
            return(HANTRO_NOK);
        pDecRefPicMarking->longTermReferenceFlag = tmp;
        if (!numRefFrames && pDecRefPicMarking->longTermReferenceFlag)
        {
            EPRINT("long_term_reference_flag");
            return(HANTRO_NOK);
        }
    }
    else
    {
        tmp = h264bsdGetBits(pStrmData, 1);
        if (tmp == END_OF_STREAM)
            return(HANTRO_NOK);
        pDecRefPicMarking->adaptiveRefPicMarkingModeFlag = tmp;
        if (pDecRefPicMarking->adaptiveRefPicMarkingModeFlag)
        {
            i = 0;
            do
            {
                /* see explanation of the MAX_NUM_MMC_OPERATIONS in
                 * slice_header.h */
                if (i > (2 * numRefFrames + 2))
                {
                    EPRINT("Too many management operations");
                    return(HANTRO_NOK);
                }

                tmp = h264bsdDecodeExpGolombUnsigned(pStrmData, &operation);
                if (tmp != HANTRO_OK)
                    return(tmp);
                if (operation > 6)
                {
                    EPRINT("memory_management_control_operation");
                    return(HANTRO_NOK);
                }

                pDecRefPicMarking->operation[i].
                    memoryManagementControlOperation = operation;
                if ((operation == 1) || (operation == 3))
                {
                    tmp = h264bsdDecodeExpGolombUnsigned(pStrmData, &value);
                    if (tmp != HANTRO_OK)
                        return(tmp);
                    pDecRefPicMarking->operation[i].differenceOfPicNums =
                        value + 1;
                }
                if (operation == 2)
                {
                    tmp = h264bsdDecodeExpGolombUnsigned(pStrmData, &value);
                    if (tmp != HANTRO_OK)
                        return(tmp);
                    pDecRefPicMarking->operation[i].longTermPicNum = value;
                }
                if ((operation == 3) || (operation == 6))
                {
                    tmp = h264bsdDecodeExpGolombUnsigned(pStrmData, &value);
                    if (tmp != HANTRO_OK)
                        return(tmp);
                    pDecRefPicMarking->operation[i].longTermFrameIdx =
                        value;
                }
                if (operation == 4)
                {
                    tmp = h264bsdDecodeExpGolombUnsigned(pStrmData, &value);
                    if (tmp != HANTRO_OK)
                        return(tmp);
                    /* value shall be in range [0, numRefFrames] */
                    if (value > numRefFrames)
                    {
                        EPRINT("max_long_term_frame_idx_plus1");
                        return(HANTRO_NOK);
                    }
                    if (value == 0)
                    {
                        pDecRefPicMarking->operation[i].
                            maxLongTermFrameIdx =
                            NO_LONG_TERM_FRAME_INDICES;
                    }
                    else
                    {
                        pDecRefPicMarking->operation[i].
                            maxLongTermFrameIdx = value - 1;
                    }
                    num4++;
                }
                if (operation == 5)
                {
                    num5++;
                }
                if (operation && operation <= 3)
                    num1to3++;
                if (operation == 6)
                    num6++;

                i++;
            } while (operation != 0);

            /* error checking */
            if (num4 > 1 || num5 > 1 || num6 > 1 || (num1to3 && num5))
                return(HANTRO_NOK);

        }
    }

    return(HANTRO_OK);
}

/*------------------------------------------------------------------------------

    Function name: h264bsdCheckPpsId

        Functional description:
            Peek value of pic_parameter_set_id from the slice header. Function
            does not modify current stream positions but copies the stream
            data structure to tmp structure which is used while accessing
            stream data.

        Inputs:
            pStrmData       pointer to stream data structure

        Outputs:
            picParamSetId   value is stored here

        Returns:
            HANTRO_OK       success
            HANTRO_NOK      invalid stream data

------------------------------------------------------------------------------*/

u32 h264bsdCheckPpsId(strmData_t *pStrmData, u32 *picParamSetId)
{

/* Variables */

    u32 tmp, value;
    strmData_t tmpStrmData[1];

/* Code */

    ASSERT(pStrmData);

    /* don't touch original stream position params */
    *tmpStrmData = *pStrmData;

    /* first_mb_in_slice */
    tmp = h264bsdDecodeExpGolombUnsigned(tmpStrmData, &value);
    if (tmp != HANTRO_OK)
        return(tmp);

    /* slice_type */
    tmp = h264bsdDecodeExpGolombUnsigned(tmpStrmData, &value);
    if (tmp != HANTRO_OK)
        return(tmp);

    tmp = h264bsdDecodeExpGolombUnsigned(tmpStrmData, &value);
    if (tmp != HANTRO_OK)
        return(tmp);
    if (value >= MAX_NUM_PIC_PARAM_SETS)
        return(HANTRO_NOK);

    *picParamSetId = value;

    return(HANTRO_OK);

}

/*------------------------------------------------------------------------------

    Function: h264bsdCheckFrameNum

        Functional description:
            Peek value of frame_num from the slice header. Function does not
            modify current stream positions but copies the stream data
            structure to tmp structure which is used while accessing stream
            data.

        Inputs:
            pStrmData       pointer to stream data structure
            maxFrameNum

        Outputs:
            frameNum        value is stored here

        Returns:
            HANTRO_OK       success
            HANTRO_NOK      invalid stream data

------------------------------------------------------------------------------*/

u32 h264bsdCheckFrameNum(
  strmData_t *pStrmData,
  u32 maxFrameNum,
  u32 *frameNum)
{

/* Variables */

    u32 tmp, value, i;
    strmData_t tmpStrmData[1];

/* Code */

    ASSERT(pStrmData);
    ASSERT(maxFrameNum);
    ASSERT(frameNum);

    /* don't touch original stream position params */
    *tmpStrmData = *pStrmData;

    /* skip first_mb_in_slice */
    tmp = h264bsdDecodeExpGolombUnsigned(tmpStrmData, &value);
    if (tmp != HANTRO_OK)
        return(tmp);

    /* skip slice_type */
    tmp = h264bsdDecodeExpGolombUnsigned(tmpStrmData, &value);
    if (tmp != HANTRO_OK)
        return(tmp);

    /* skip pic_parameter_set_id */
    tmp = h264bsdDecodeExpGolombUnsigned(tmpStrmData, &value);
    if (tmp != HANTRO_OK)
        return(tmp);

    /* log2(maxFrameNum) -> num bits to represent frame_num */
    i = 0;
    while (maxFrameNum >> i)
        i++;
    i--;

    /* frame_num */
    tmp = h264bsdGetBits(tmpStrmData, i);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);
    *frameNum = tmp;

    return(HANTRO_OK);

}

/*------------------------------------------------------------------------------

    Function: h264bsdCheckIdrPicId

        Functional description:
            Peek value of idr_pic_id from the slice header. Function does not
            modify current stream positions but copies the stream data
            structure to tmp structure which is used while accessing stream
            data.

        Inputs:
            pStrmData       pointer to stream data structure
            maxFrameNum     max frame number from active SPS
            nalUnitType     type of the current NAL unit

        Outputs:
            idrPicId        value is stored here

        Returns:
            HANTRO_OK       success
            HANTRO_NOK      invalid stream data

------------------------------------------------------------------------------*/

u32 h264bsdCheckIdrPicId(
  strmData_t *pStrmData,
  u32 maxFrameNum,
  nalUnitType_e nalUnitType,
  u32 *idrPicId)
{

/* Variables */

    u32 tmp, value, i;
    strmData_t tmpStrmData[1];

/* Code */

    ASSERT(pStrmData);
    ASSERT(maxFrameNum);
    ASSERT(idrPicId);

    /* nalUnitType must be equal to 5 because otherwise idrPicId is not
     * present */
    if (nalUnitType != NAL_CODED_SLICE_IDR)
        return(HANTRO_NOK);

    /* don't touch original stream position params */
    *tmpStrmData = *pStrmData;

    /* skip first_mb_in_slice */
    tmp = h264bsdDecodeExpGolombUnsigned(tmpStrmData, &value);
    if (tmp != HANTRO_OK)
        return(tmp);

    /* skip slice_type */
    tmp = h264bsdDecodeExpGolombUnsigned(tmpStrmData, &value);
    if (tmp != HANTRO_OK)
        return(tmp);

    /* skip pic_parameter_set_id */
    tmp = h264bsdDecodeExpGolombUnsigned(tmpStrmData, &value);
    if (tmp != HANTRO_OK)
        return(tmp);

    /* log2(maxFrameNum) -> num bits to represent frame_num */
    i = 0;
    while (maxFrameNum >> i)
        i++;
    i--;

    /* skip frame_num */
    tmp = h264bsdGetBits(tmpStrmData, i);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);

    /* idr_pic_id */
    tmp = h264bsdDecodeExpGolombUnsigned(tmpStrmData, idrPicId);
    if (tmp != HANTRO_OK)
        return(tmp);

    return(HANTRO_OK);

}

/*------------------------------------------------------------------------------

    Function: h264bsdCheckPicOrderCntLsb

        Functional description:
            Peek value of pic_order_cnt_lsb from the slice header. Function
            does not modify current stream positions but copies the stream
            data structure to tmp structure which is used while accessing
            stream data.

        Inputs:
            pStrmData       pointer to stream data structure
            pSeqParamSet    pointer to active SPS
            nalUnitType     type of the current NAL unit

        Outputs:
            picOrderCntLsb  value is stored here

        Returns:
            HANTRO_OK       success
            HANTRO_NOK      invalid stream data

------------------------------------------------------------------------------*/

u32 h264bsdCheckPicOrderCntLsb(
  strmData_t *pStrmData,
  seqParamSet_t *pSeqParamSet,
  nalUnitType_e nalUnitType,
  u32 *picOrderCntLsb)
{

/* Variables */

    u32 tmp, value, i;
    strmData_t tmpStrmData[1];

/* Code */

    ASSERT(pStrmData);
    ASSERT(pSeqParamSet);
    ASSERT(picOrderCntLsb);

    /* picOrderCntType must be equal to 0 */
    ASSERT(pSeqParamSet->picOrderCntType == 0);
    ASSERT(pSeqParamSet->maxFrameNum);
    ASSERT(pSeqParamSet->maxPicOrderCntLsb);

    /* don't touch original stream position params */
    *tmpStrmData = *pStrmData;

    /* skip first_mb_in_slice */
    tmp = h264bsdDecodeExpGolombUnsigned(tmpStrmData, &value);
    if (tmp != HANTRO_OK)
        return(tmp);

    /* skip slice_type */
    tmp = h264bsdDecodeExpGolombUnsigned(tmpStrmData, &value);
    if (tmp != HANTRO_OK)
        return(tmp);

    /* skip pic_parameter_set_id */
    tmp = h264bsdDecodeExpGolombUnsigned(tmpStrmData, &value);
    if (tmp != HANTRO_OK)
        return(tmp);

    /* log2(maxFrameNum) -> num bits to represent frame_num */
    i = 0;
    while (pSeqParamSet->maxFrameNum >> i)
        i++;
    i--;

    /* skip frame_num */
    tmp = h264bsdGetBits(tmpStrmData, i);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);

    /* skip idr_pic_id when necessary */
    if (nalUnitType == NAL_CODED_SLICE_IDR)
    {
        tmp = h264bsdDecodeExpGolombUnsigned(tmpStrmData, &value);
        if (tmp != HANTRO_OK)
            return(tmp);
    }

    /* log2(maxPicOrderCntLsb) -> num bits to represent pic_order_cnt_lsb */
    i = 0;
    while (pSeqParamSet->maxPicOrderCntLsb >> i)
        i++;
    i--;

    /* pic_order_cnt_lsb */
    tmp = h264bsdGetBits(tmpStrmData, i);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);
    *picOrderCntLsb = tmp;

    return(HANTRO_OK);

}

/*------------------------------------------------------------------------------

    Function: h264bsdCheckDeltaPicOrderCntBottom

        Functional description:
            Peek value of delta_pic_order_cnt_bottom from the slice header.
            Function does not modify current stream positions but copies the
            stream data structure to tmp structure which is used while
            accessing stream data.

        Inputs:
            pStrmData       pointer to stream data structure
            pSeqParamSet    pointer to active SPS
            nalUnitType     type of the current NAL unit

        Outputs:
            deltaPicOrderCntBottom  value is stored here

        Returns:
            HANTRO_OK       success
            HANTRO_NOK      invalid stream data

------------------------------------------------------------------------------*/

u32 h264bsdCheckDeltaPicOrderCntBottom(
  strmData_t *pStrmData,
  seqParamSet_t *pSeqParamSet,
  nalUnitType_e nalUnitType,
  i32 *deltaPicOrderCntBottom)
{

/* Variables */

    u32 tmp, value, i;
    strmData_t tmpStrmData[1];

/* Code */

    ASSERT(pStrmData);
    ASSERT(pSeqParamSet);
    ASSERT(deltaPicOrderCntBottom);

    /* picOrderCntType must be equal to 0 and picOrderPresentFlag must be TRUE
     * */
    ASSERT(pSeqParamSet->picOrderCntType == 0);
    ASSERT(pSeqParamSet->maxFrameNum);
    ASSERT(pSeqParamSet->maxPicOrderCntLsb);

    /* don't touch original stream position params */
    *tmpStrmData = *pStrmData;

    /* skip first_mb_in_slice */
    tmp = h264bsdDecodeExpGolombUnsigned(tmpStrmData, &value);
    if (tmp != HANTRO_OK)
        return(tmp);

    /* skip slice_type */
    tmp = h264bsdDecodeExpGolombUnsigned(tmpStrmData, &value);
    if (tmp != HANTRO_OK)
        return(tmp);

    /* skip pic_parameter_set_id */
    tmp = h264bsdDecodeExpGolombUnsigned(tmpStrmData, &value);
    if (tmp != HANTRO_OK)
        return(tmp);

    /* log2(maxFrameNum) -> num bits to represent frame_num */
    i = 0;
    while (pSeqParamSet->maxFrameNum >> i)
        i++;
    i--;

    /* skip frame_num */
    tmp = h264bsdGetBits(tmpStrmData, i);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);

    /* skip idr_pic_id when necessary */
    if (nalUnitType == NAL_CODED_SLICE_IDR)
    {
        tmp = h264bsdDecodeExpGolombUnsigned(tmpStrmData, &value);
        if (tmp != HANTRO_OK)
            return(tmp);
    }

    /* log2(maxPicOrderCntLsb) -> num bits to represent pic_order_cnt_lsb */
    i = 0;
    while (pSeqParamSet->maxPicOrderCntLsb >> i)
        i++;
    i--;

    /* skip pic_order_cnt_lsb */
    tmp = h264bsdGetBits(tmpStrmData, i);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);

    /* delta_pic_order_cnt_bottom */
    tmp = h264bsdDecodeExpGolombSigned(tmpStrmData, deltaPicOrderCntBottom);
    if (tmp != HANTRO_OK)
        return(tmp);

    return(HANTRO_OK);

}

/*------------------------------------------------------------------------------

    Function: h264bsdCheckDeltaPicOrderCnt

        Functional description:
            Peek values delta_pic_order_cnt[0] and delta_pic_order_cnt[1]
            from the slice header. Function does not modify current stream
            positions but copies the stream data structure to tmp structure
            which is used while accessing stream data.

        Inputs:
            pStrmData               pointer to stream data structure
            pSeqParamSet            pointer to active SPS
            nalUnitType             type of the current NAL unit
            picOrderPresentFlag     flag indicating if delta_pic_order_cnt[1]
                                    is present in the stream

        Outputs:
            deltaPicOrderCnt        values are stored here

        Returns:
            HANTRO_OK               success
            HANTRO_NOK              invalid stream data

------------------------------------------------------------------------------*/

u32 h264bsdCheckDeltaPicOrderCnt(
  strmData_t *pStrmData,
  seqParamSet_t *pSeqParamSet,
  nalUnitType_e nalUnitType,
  u32 picOrderPresentFlag,
  i32 *deltaPicOrderCnt)
{

/* Variables */

    u32 tmp, value, i;
    strmData_t tmpStrmData[1];

/* Code */

    ASSERT(pStrmData);
    ASSERT(pSeqParamSet);
    ASSERT(deltaPicOrderCnt);

    /* picOrderCntType must be equal to 1 and deltaPicOrderAlwaysZeroFlag must
     * be FALSE */
    ASSERT(pSeqParamSet->picOrderCntType == 1);
    ASSERT(!pSeqParamSet->deltaPicOrderAlwaysZeroFlag);
    ASSERT(pSeqParamSet->maxFrameNum);

    /* don't touch original stream position params */
    *tmpStrmData = *pStrmData;

    /* skip first_mb_in_slice */
    tmp = h264bsdDecodeExpGolombUnsigned(tmpStrmData, &value);
    if (tmp != HANTRO_OK)
        return(tmp);

    /* skip slice_type */
    tmp = h264bsdDecodeExpGolombUnsigned(tmpStrmData, &value);
    if (tmp != HANTRO_OK)
        return(tmp);

    /* skip pic_parameter_set_id */
    tmp = h264bsdDecodeExpGolombUnsigned(tmpStrmData, &value);
    if (tmp != HANTRO_OK)
        return(tmp);

    /* log2(maxFrameNum) -> num bits to represent frame_num */
    i = 0;
    while (pSeqParamSet->maxFrameNum >> i)
        i++;
    i--;

    /* skip frame_num */
    tmp = h264bsdGetBits(tmpStrmData, i);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);

    /* skip idr_pic_id when necessary */
    if (nalUnitType == NAL_CODED_SLICE_IDR)
    {
        tmp = h264bsdDecodeExpGolombUnsigned(tmpStrmData, &value);
        if (tmp != HANTRO_OK)
            return(tmp);
    }

    /* delta_pic_order_cnt[0] */
    tmp = h264bsdDecodeExpGolombSigned(tmpStrmData, &deltaPicOrderCnt[0]);
    if (tmp != HANTRO_OK)
        return(tmp);

    /* delta_pic_order_cnt[1] if present */
    if (picOrderPresentFlag)
    {
        tmp = h264bsdDecodeExpGolombSigned(tmpStrmData, &deltaPicOrderCnt[1]);
        if (tmp != HANTRO_OK)
            return(tmp);
    }

    return(HANTRO_OK);

}

/*------------------------------------------------------------------------------

    Function: h264bsdCheckRedundantPicCnt

        Functional description:
            Peek value of redundant_pic_cnt from the slice header. Function
            does not modify current stream positions but copies the stream
            data structure to tmp structure which is used while accessing
            stream data.

        Inputs:
            pStrmData       pointer to stream data structure
            pSeqParamSet    pointer to active SPS
            pPicParamSet    pointer to active PPS
            nalUnitType     type of the current NAL unit

        Outputs:
            redundantPicCnt value is stored here

        Returns:
            HANTRO_OK       success
            HANTRO_NOK      invalid stream data

------------------------------------------------------------------------------*/

u32 h264bsdCheckRedundantPicCnt(
  strmData_t *pStrmData,
  seqParamSet_t *pSeqParamSet,
  picParamSet_t *pPicParamSet,
  nalUnitType_e nalUnitType,
  u32 *redundantPicCnt)
{

/* Variables */

    u32 tmp, value, i;
    i32 ivalue;
    strmData_t tmpStrmData[1];

/* Code */

    ASSERT(pStrmData);
    ASSERT(pSeqParamSet);
    ASSERT(pPicParamSet);
    ASSERT(redundantPicCnt);

    /* redundant_pic_cnt_flag must be TRUE */
    ASSERT(pPicParamSet->redundantPicCntPresentFlag);
    ASSERT(pSeqParamSet->maxFrameNum);
    ASSERT(pSeqParamSet->picOrderCntType > 0 ||
           pSeqParamSet->maxPicOrderCntLsb);

    /* don't touch original stream position params */
    *tmpStrmData = *pStrmData;

    /* skip first_mb_in_slice */
    tmp = h264bsdDecodeExpGolombUnsigned(tmpStrmData, &value);
    if (tmp != HANTRO_OK)
        return(tmp);

    /* skip slice_type */
    tmp = h264bsdDecodeExpGolombUnsigned(tmpStrmData, &value);
    if (tmp != HANTRO_OK)
        return(tmp);

    /* skip pic_parameter_set_id */
    tmp = h264bsdDecodeExpGolombUnsigned(tmpStrmData, &value);
    if (tmp != HANTRO_OK)
        return(tmp);

    /* log2(maxFrameNum) -> num bits to represent frame_num */
    i = 0;
    while (pSeqParamSet->maxFrameNum >> i)
        i++;
    i--;

    /* skip frame_num */
    tmp = h264bsdGetBits(tmpStrmData, i);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);

    /* skip idr_pic_id when necessary */
    if (nalUnitType == NAL_CODED_SLICE_IDR)
    {
        tmp = h264bsdDecodeExpGolombUnsigned(tmpStrmData, &value);
        if (tmp != HANTRO_OK)
            return(tmp);
    }

    if (pSeqParamSet->picOrderCntType == 0)
    {
        /* log2(maxPicOrderCntLsb) -> num bits to represent pic_order_cnt_lsb */
        i = 0;
        while (pSeqParamSet->maxPicOrderCntLsb >> i)
            i++;
        i--;

        /* pic_order_cnt_lsb */
        tmp = h264bsdGetBits(tmpStrmData, i);
        if (tmp == END_OF_STREAM)
            return(HANTRO_NOK);

        if (pPicParamSet->picOrderPresentFlag)
        {
            /* skip delta_pic_order_cnt_bottom */
            tmp = h264bsdDecodeExpGolombSigned(tmpStrmData, &ivalue);
            if (tmp != HANTRO_OK)
                return(tmp);
        }
    }

    if (pSeqParamSet->picOrderCntType == 1 &&
      !pSeqParamSet->deltaPicOrderAlwaysZeroFlag)
    {
        /* delta_pic_order_cnt[0] */
        tmp = h264bsdDecodeExpGolombSigned(tmpStrmData, &ivalue);
        if (tmp != HANTRO_OK)
            return(tmp);

        /* delta_pic_order_cnt[1] if present */
        if (pPicParamSet->picOrderPresentFlag)
        {
            tmp = h264bsdDecodeExpGolombSigned(tmpStrmData, &ivalue);
            if (tmp != HANTRO_OK)
                return(tmp);
        }
    }

    /* redundant_pic_cnt */
    tmp = h264bsdDecodeExpGolombUnsigned(tmpStrmData, redundantPicCnt);
    if (tmp != HANTRO_OK)
        return(tmp);

    return(HANTRO_OK);

}


/*------------------------------------------------------------------------------

    Function: h264bsdCheckPriorPicsFlag

        Functional description:
            Peek value of no_output_of_prior_pics_flag from the slice header.
            Function does not modify current stream positions but copies
            the stream data structure to tmp structure which is used while
            accessing stream data.

        Inputs:
            pStrmData       pointer to stream data structure
            pSeqParamSet    pointer to active SPS
            pPicParamSet    pointer to active PPS
            nalUnitType     type of the current NAL unit

        Outputs:
            noOutputOfPriorPicsFlag value is stored here

        Returns:
            HANTRO_OK       success
            HANTRO_NOK      invalid stream data

------------------------------------------------------------------------------*/
/*lint -e715 disable lint info nalUnitType not referenced */
u32 h264bsdCheckPriorPicsFlag(u32 * noOutputOfPriorPicsFlag,
                              const strmData_t * pStrmData,
                              const seqParamSet_t * pSeqParamSet,
                              const picParamSet_t * pPicParamSet,
                              nalUnitType_e nalUnitType)
{
/* Variables */

    u32 tmp, value, i;
    i32 ivalue;
    strmData_t tmpStrmData[1];

/* Code */

    ASSERT(pStrmData);
    ASSERT(pSeqParamSet);
    ASSERT(pPicParamSet);
    ASSERT(noOutputOfPriorPicsFlag);

    /* must be IDR lsice */
    ASSERT(nalUnitType == NAL_CODED_SLICE_IDR);

    /* don't touch original stream position params */
    *tmpStrmData = *pStrmData;

    /* skip first_mb_in_slice */
    tmp = h264bsdDecodeExpGolombUnsigned(tmpStrmData, &value);
    if(tmp != HANTRO_OK)
        return (tmp);

    /* slice_type */
    tmp = h264bsdDecodeExpGolombUnsigned(tmpStrmData, &value);
    if(tmp != HANTRO_OK)
        return (tmp);

    /* skip pic_parameter_set_id */
    tmp = h264bsdDecodeExpGolombUnsigned(tmpStrmData, &value);
    if(tmp != HANTRO_OK)
        return (tmp);

    /* log2(maxFrameNum) -> num bits to represent frame_num */
    i = 0;
    while(pSeqParamSet->maxFrameNum >> i)
        i++;
    i--;

    /* skip frame_num */
    tmp = h264bsdGetBits(tmpStrmData, i);
    if(tmp == END_OF_STREAM)
        return (HANTRO_NOK);

    /* skip idr_pic_id */
    tmp = h264bsdDecodeExpGolombUnsigned(tmpStrmData, &value);
    if(tmp != HANTRO_OK)
        return (tmp);

    if(pSeqParamSet->picOrderCntType == 0)
    {
        /* log2(maxPicOrderCntLsb) -> num bits to represent pic_order_cnt_lsb */
        i = 0;
        while(pSeqParamSet->maxPicOrderCntLsb >> i)
            i++;
        i--;

        /* skip pic_order_cnt_lsb */
        tmp = h264bsdGetBits(tmpStrmData, i);
        if(tmp == END_OF_STREAM)
            return (HANTRO_NOK);

        if(pPicParamSet->picOrderPresentFlag)
        {
            /* skip delta_pic_order_cnt_bottom */
            tmp = h264bsdDecodeExpGolombSigned(tmpStrmData, &ivalue);
            if(tmp != HANTRO_OK)
                return (tmp);
        }
    }

    if(pSeqParamSet->picOrderCntType == 1 &&
       !pSeqParamSet->deltaPicOrderAlwaysZeroFlag)
    {
        /* skip delta_pic_order_cnt[0] */
        tmp = h264bsdDecodeExpGolombSigned(tmpStrmData, &ivalue);
        if(tmp != HANTRO_OK)
            return (tmp);

        /* skip delta_pic_order_cnt[1] if present */
        if(pPicParamSet->picOrderPresentFlag)
        {
            tmp = h264bsdDecodeExpGolombSigned(tmpStrmData, &ivalue);
            if(tmp != HANTRO_OK)
                return (tmp);
        }
    }

    /* skip redundant_pic_cnt */
    if(pPicParamSet->redundantPicCntPresentFlag)
    {
        tmp = h264bsdDecodeExpGolombUnsigned(tmpStrmData, &value);
        if(tmp != HANTRO_OK)
            return (tmp);
    }

    *noOutputOfPriorPicsFlag = h264bsdGetBits(tmpStrmData, 1);
    if(*noOutputOfPriorPicsFlag == END_OF_STREAM)
        return (HANTRO_NOK);

    return (HANTRO_OK);

}
/*lint +e715 */


