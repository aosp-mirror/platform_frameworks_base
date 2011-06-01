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
          h264bsdDecodeSeqParamSet
          GetDpbSize
          h264bsdCompareSeqParamSets

------------------------------------------------------------------------------*/

/*------------------------------------------------------------------------------
    1. Include headers
------------------------------------------------------------------------------*/

#include "h264bsd_seq_param_set.h"
#include "h264bsd_util.h"
#include "h264bsd_vlc.h"
#include "h264bsd_vui.h"
#include "h264bsd_cfg.h"

/*------------------------------------------------------------------------------
    2. External compiler flags
--------------------------------------------------------------------------------

--------------------------------------------------------------------------------
    3. Module defines
------------------------------------------------------------------------------*/

/* enumeration to indicate invalid return value from the GetDpbSize function */
enum {INVALID_DPB_SIZE = 0x7FFFFFFF};

/*------------------------------------------------------------------------------
    4. Local function prototypes
------------------------------------------------------------------------------*/

static u32 GetDpbSize(u32 picSizeInMbs, u32 levelIdc);

/*------------------------------------------------------------------------------

    Function name: h264bsdDecodeSeqParamSet

        Functional description:
            Decode sequence parameter set information from the stream.

            Function allocates memory for offsetForRefFrame array if
            picture order count type is 1 and numRefFramesInPicOrderCntCycle
            is greater than zero.

        Inputs:
            pStrmData       pointer to stream data structure

        Outputs:
            pSeqParamSet    decoded information is stored here

        Returns:
            HANTRO_OK       success
            HANTRO_NOK      failure, invalid information or end of stream
            MEMORY_ALLOCATION_ERROR for memory allocation failure

------------------------------------------------------------------------------*/

u32 h264bsdDecodeSeqParamSet(strmData_t *pStrmData, seqParamSet_t *pSeqParamSet)
{

/* Variables */

    u32 tmp, i, value;

/* Code */

    ASSERT(pStrmData);
    ASSERT(pSeqParamSet);

    H264SwDecMemset(pSeqParamSet, 0, sizeof(seqParamSet_t));

    /* profile_idc */
    tmp = h264bsdGetBits(pStrmData, 8);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);
    if (tmp != 66)
    {
        DEBUG(("NOT BASELINE PROFILE %d\n", tmp));
    }
    pSeqParamSet->profileIdc = tmp;

    /* constrained_set0_flag */
    tmp = h264bsdGetBits(pStrmData, 1);
    /* constrained_set1_flag */
    tmp = h264bsdGetBits(pStrmData, 1);
    /* constrained_set2_flag */
    tmp = h264bsdGetBits(pStrmData, 1);

    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);

    /* reserved_zero_5bits, values of these bits shall be ignored */
    tmp = h264bsdGetBits(pStrmData, 5);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);

    tmp = h264bsdGetBits(pStrmData, 8);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);
    pSeqParamSet->levelIdc = tmp;

    tmp = h264bsdDecodeExpGolombUnsigned(pStrmData,
        &pSeqParamSet->seqParameterSetId);
    if (tmp != HANTRO_OK)
        return(tmp);
    if (pSeqParamSet->seqParameterSetId >= MAX_NUM_SEQ_PARAM_SETS)
    {
        EPRINT("seq_param_set_id");
        return(HANTRO_NOK);
    }

    /* log2_max_frame_num_minus4 */
    tmp = h264bsdDecodeExpGolombUnsigned(pStrmData, &value);
    if (tmp != HANTRO_OK)
        return(tmp);
    if (value > 12)
    {
        EPRINT("log2_max_frame_num_minus4");
        return(HANTRO_NOK);
    }
    /* maxFrameNum = 2^(log2_max_frame_num_minus4 + 4) */
    pSeqParamSet->maxFrameNum = 1 << (value+4);

    /* valid POC types are 0, 1 and 2 */
    tmp = h264bsdDecodeExpGolombUnsigned(pStrmData, &value);
    if (tmp != HANTRO_OK)
        return(tmp);
    if (value > 2)
    {
        EPRINT("pic_order_cnt_type");
        return(HANTRO_NOK);
    }
    pSeqParamSet->picOrderCntType = value;

    if (pSeqParamSet->picOrderCntType == 0)
    {
        /* log2_max_pic_order_cnt_lsb_minus4 */
        tmp = h264bsdDecodeExpGolombUnsigned(pStrmData, &value);
        if (tmp != HANTRO_OK)
            return(tmp);
        if (value > 12)
        {
            EPRINT("log2_max_pic_order_cnt_lsb_minus4");
            return(HANTRO_NOK);
        }
        /* maxPicOrderCntLsb = 2^(log2_max_pic_order_cnt_lsb_minus4 + 4) */
        pSeqParamSet->maxPicOrderCntLsb = 1 << (value+4);
    }
    else if (pSeqParamSet->picOrderCntType == 1)
    {
        tmp = h264bsdGetBits(pStrmData, 1);
        if (tmp == END_OF_STREAM)
            return(HANTRO_NOK);
        pSeqParamSet->deltaPicOrderAlwaysZeroFlag = (tmp == 1) ?
                                        HANTRO_TRUE : HANTRO_FALSE;

        tmp = h264bsdDecodeExpGolombSigned(pStrmData,
            &pSeqParamSet->offsetForNonRefPic);
        if (tmp != HANTRO_OK)
            return(tmp);

        tmp = h264bsdDecodeExpGolombSigned(pStrmData,
            &pSeqParamSet->offsetForTopToBottomField);
        if (tmp != HANTRO_OK)
            return(tmp);

        tmp = h264bsdDecodeExpGolombUnsigned(pStrmData,
            &pSeqParamSet->numRefFramesInPicOrderCntCycle);
        if (tmp != HANTRO_OK)
            return(tmp);
        if (pSeqParamSet->numRefFramesInPicOrderCntCycle > 255)
        {
            EPRINT("num_ref_frames_in_pic_order_cnt_cycle");
            return(HANTRO_NOK);
        }

        if (pSeqParamSet->numRefFramesInPicOrderCntCycle)
        {
            /* NOTE: This has to be freed somewhere! */
            ALLOCATE(pSeqParamSet->offsetForRefFrame,
                     pSeqParamSet->numRefFramesInPicOrderCntCycle, i32);
            if (pSeqParamSet->offsetForRefFrame == NULL)
                return(MEMORY_ALLOCATION_ERROR);

            for (i = 0; i < pSeqParamSet->numRefFramesInPicOrderCntCycle; i++)
            {
                tmp =  h264bsdDecodeExpGolombSigned(pStrmData,
                    pSeqParamSet->offsetForRefFrame + i);
                if (tmp != HANTRO_OK)
                    return(tmp);
            }
        }
        else
        {
            pSeqParamSet->offsetForRefFrame = NULL;
        }
    }

    tmp = h264bsdDecodeExpGolombUnsigned(pStrmData,
        &pSeqParamSet->numRefFrames);
    if (tmp != HANTRO_OK)
        return(tmp);
    if (pSeqParamSet->numRefFrames > MAX_NUM_REF_PICS)
    {
        EPRINT("num_ref_frames");
        return(HANTRO_NOK);
    }

    tmp = h264bsdGetBits(pStrmData, 1);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);
    pSeqParamSet->gapsInFrameNumValueAllowedFlag = (tmp == 1) ?
                                        HANTRO_TRUE : HANTRO_FALSE;

    tmp = h264bsdDecodeExpGolombUnsigned(pStrmData, &value);
    if (tmp != HANTRO_OK)
        return(tmp);
    pSeqParamSet->picWidthInMbs = value + 1;

    tmp = h264bsdDecodeExpGolombUnsigned(pStrmData, &value);
    if (tmp != HANTRO_OK)
        return(tmp);
    pSeqParamSet->picHeightInMbs = value + 1;

    /* frame_mbs_only_flag, shall be 1 for baseline profile */
    tmp = h264bsdGetBits(pStrmData, 1);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);
    if (!tmp)
    {
        EPRINT("frame_mbs_only_flag");
        return(HANTRO_NOK);
    }

    /* direct_8x8_inference_flag */
    tmp = h264bsdGetBits(pStrmData, 1);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);

    tmp = h264bsdGetBits(pStrmData, 1);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);
    pSeqParamSet->frameCroppingFlag = (tmp == 1) ? HANTRO_TRUE : HANTRO_FALSE;

    if (pSeqParamSet->frameCroppingFlag)
    {
        tmp = h264bsdDecodeExpGolombUnsigned(pStrmData,
            &pSeqParamSet->frameCropLeftOffset);
        if (tmp != HANTRO_OK)
            return(tmp);
        tmp = h264bsdDecodeExpGolombUnsigned(pStrmData,
            &pSeqParamSet->frameCropRightOffset);
        if (tmp != HANTRO_OK)
            return(tmp);
        tmp = h264bsdDecodeExpGolombUnsigned(pStrmData,
            &pSeqParamSet->frameCropTopOffset);
        if (tmp != HANTRO_OK)
            return(tmp);
        tmp = h264bsdDecodeExpGolombUnsigned(pStrmData,
            &pSeqParamSet->frameCropBottomOffset);
        if (tmp != HANTRO_OK)
            return(tmp);

        /* check that frame cropping params are valid, parameters shall
         * specify non-negative area within the original picture */
        if ( ( (i32)pSeqParamSet->frameCropLeftOffset >
               ( 8 * (i32)pSeqParamSet->picWidthInMbs -
                 ((i32)pSeqParamSet->frameCropRightOffset + 1) ) ) ||
             ( (i32)pSeqParamSet->frameCropTopOffset >
               ( 8 * (i32)pSeqParamSet->picHeightInMbs -
                 ((i32)pSeqParamSet->frameCropBottomOffset + 1) ) ) )
        {
            EPRINT("frame_cropping");
            return(HANTRO_NOK);
        }
    }

    /* check that image dimensions and levelIdc match */
    tmp = pSeqParamSet->picWidthInMbs * pSeqParamSet->picHeightInMbs;
    value = GetDpbSize(tmp, pSeqParamSet->levelIdc);
    if (value == INVALID_DPB_SIZE || pSeqParamSet->numRefFrames > value)
    {
        DEBUG(("WARNING! Invalid DPB size based on SPS Level!\n"));
        DEBUG(("WARNING! Using num_ref_frames =%d for DPB size!\n",
                        pSeqParamSet->numRefFrames));
        value = pSeqParamSet->numRefFrames;
    }
    pSeqParamSet->maxDpbSize = value;

    tmp = h264bsdGetBits(pStrmData, 1);
    if (tmp == END_OF_STREAM)
        return(HANTRO_NOK);
    pSeqParamSet->vuiParametersPresentFlag = (tmp == 1) ?
                                HANTRO_TRUE : HANTRO_FALSE;

    /* VUI */
    if (pSeqParamSet->vuiParametersPresentFlag)
    {
        ALLOCATE(pSeqParamSet->vuiParameters, 1, vuiParameters_t);
        if (pSeqParamSet->vuiParameters == NULL)
            return(MEMORY_ALLOCATION_ERROR);
        tmp = h264bsdDecodeVuiParameters(pStrmData,
            pSeqParamSet->vuiParameters);
        if (tmp != HANTRO_OK)
            return(tmp);
        /* check numReorderFrames and maxDecFrameBuffering */
        if (pSeqParamSet->vuiParameters->bitstreamRestrictionFlag)
        {
            if (pSeqParamSet->vuiParameters->numReorderFrames >
                    pSeqParamSet->vuiParameters->maxDecFrameBuffering ||
                pSeqParamSet->vuiParameters->maxDecFrameBuffering <
                    pSeqParamSet->numRefFrames ||
                pSeqParamSet->vuiParameters->maxDecFrameBuffering >
                    pSeqParamSet->maxDpbSize)
            {
                return(HANTRO_NOK);
            }

            /* standard says that "the sequence shall not require a DPB with
             * size of more than max(1, maxDecFrameBuffering) */
            pSeqParamSet->maxDpbSize =
                MAX(1, pSeqParamSet->vuiParameters->maxDecFrameBuffering);
        }
    }

    tmp = h264bsdRbspTrailingBits(pStrmData);

    /* ignore possible errors in trailing bits of parameters sets */
    return(HANTRO_OK);

}

/*------------------------------------------------------------------------------

    Function: GetDpbSize

        Functional description:
            Get size of the DPB in frames. Size is determined based on the
            picture size and MaxDPB for the specified level. These determine
            how many pictures may fit into to the buffer. However, the size
            is also limited to a maximum of 16 frames and therefore function
            returns the minimum of the determined size and 16.

        Inputs:
            picSizeInMbs    number of macroblocks in the picture
            levelIdc        indicates the level

        Outputs:
            none

        Returns:
            size of the DPB in frames
            INVALID_DPB_SIZE when invalid levelIdc specified or picSizeInMbs
            is higher than supported by the level in question

------------------------------------------------------------------------------*/

u32 GetDpbSize(u32 picSizeInMbs, u32 levelIdc)
{

/* Variables */

    u32 tmp;
    u32 maxPicSizeInMbs;

/* Code */

    ASSERT(picSizeInMbs);

    /* use tmp as the size of the DPB in bytes, computes as 1024 * MaxDPB
     * (from table A-1 in Annex A) */
    switch (levelIdc)
    {
        case 10:
            tmp = 152064;
            maxPicSizeInMbs = 99;
            break;

        case 11:
            tmp = 345600;
            maxPicSizeInMbs = 396;
            break;

        case 12:
            tmp = 912384;
            maxPicSizeInMbs = 396;
            break;

        case 13:
            tmp = 912384;
            maxPicSizeInMbs = 396;
            break;

        case 20:
            tmp = 912384;
            maxPicSizeInMbs = 396;
            break;

        case 21:
            tmp = 1824768;
            maxPicSizeInMbs = 792;
            break;

        case 22:
            tmp = 3110400;
            maxPicSizeInMbs = 1620;
            break;

        case 30:
            tmp = 3110400;
            maxPicSizeInMbs = 1620;
            break;

        case 31:
            tmp = 6912000;
            maxPicSizeInMbs = 3600;
            break;

        case 32:
            tmp = 7864320;
            maxPicSizeInMbs = 5120;
            break;

        case 40:
            tmp = 12582912;
            maxPicSizeInMbs = 8192;
            break;

        case 41:
            tmp = 12582912;
            maxPicSizeInMbs = 8192;
            break;

        case 42:
            tmp = 34816*384;
            maxPicSizeInMbs = 8704;
            break;

        case 50:
            /* standard says 42301440 here, but corrigendum "corrects" this to
             * 42393600 */
            tmp = 42393600;
            maxPicSizeInMbs = 22080;
            break;

        case 51:
            tmp = 70778880;
            maxPicSizeInMbs = 36864;
            break;

        default:
            return(INVALID_DPB_SIZE);
    }

    /* this is not "correct" return value! However, it results in error in
     * decoding and this was easiest place to check picture size */
    if (picSizeInMbs > maxPicSizeInMbs)
        return(INVALID_DPB_SIZE);

    tmp /= (picSizeInMbs*384);

    return(MIN(tmp, 16));

}

/*------------------------------------------------------------------------------

    Function name: h264bsdCompareSeqParamSets

        Functional description:
            Compare two sequence parameter sets.

        Inputs:
            pSps1   pointer to a sequence parameter set
            pSps2   pointer to another sequence parameter set

        Outputs:
            0       sequence parameter sets are equal
            1       otherwise

------------------------------------------------------------------------------*/

u32 h264bsdCompareSeqParamSets(seqParamSet_t *pSps1, seqParamSet_t *pSps2)
{

/* Variables */

    u32 i;

/* Code */

    ASSERT(pSps1);
    ASSERT(pSps2);

    /* first compare parameters whose existence does not depend on other
     * parameters and only compare the rest of the params if these are equal */
    if (pSps1->profileIdc        == pSps2->profileIdc &&
        pSps1->levelIdc          == pSps2->levelIdc &&
        pSps1->maxFrameNum       == pSps2->maxFrameNum &&
        pSps1->picOrderCntType   == pSps2->picOrderCntType &&
        pSps1->numRefFrames      == pSps2->numRefFrames &&
        pSps1->gapsInFrameNumValueAllowedFlag ==
            pSps2->gapsInFrameNumValueAllowedFlag &&
        pSps1->picWidthInMbs     == pSps2->picWidthInMbs &&
        pSps1->picHeightInMbs    == pSps2->picHeightInMbs &&
        pSps1->frameCroppingFlag == pSps2->frameCroppingFlag &&
        pSps1->vuiParametersPresentFlag == pSps2->vuiParametersPresentFlag)
    {
        if (pSps1->picOrderCntType == 0)
        {
            if (pSps1->maxPicOrderCntLsb != pSps2->maxPicOrderCntLsb)
                return 1;
        }
        else if (pSps1->picOrderCntType == 1)
        {
            if (pSps1->deltaPicOrderAlwaysZeroFlag !=
                    pSps2->deltaPicOrderAlwaysZeroFlag ||
                pSps1->offsetForNonRefPic != pSps2->offsetForNonRefPic ||
                pSps1->offsetForTopToBottomField !=
                    pSps2->offsetForTopToBottomField ||
                pSps1->numRefFramesInPicOrderCntCycle !=
                    pSps2->numRefFramesInPicOrderCntCycle)
            {
                return 1;
            }
            else
            {
                for (i = 0; i < pSps1->numRefFramesInPicOrderCntCycle; i++)
                    if (pSps1->offsetForRefFrame[i] !=
                        pSps2->offsetForRefFrame[i])
                    {
                        return 1;
                    }
            }
        }
        if (pSps1->frameCroppingFlag)
        {
            if (pSps1->frameCropLeftOffset   != pSps2->frameCropLeftOffset ||
                pSps1->frameCropRightOffset  != pSps2->frameCropRightOffset ||
                pSps1->frameCropTopOffset    != pSps2->frameCropTopOffset ||
                pSps1->frameCropBottomOffset != pSps2->frameCropBottomOffset)
            {
                return 1;
            }
        }

        return 0;
    }

    return 1;
}

