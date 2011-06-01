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
          h264bsdDecodePicOrderCnt

------------------------------------------------------------------------------*/

/*------------------------------------------------------------------------------
    1. Include headers
------------------------------------------------------------------------------*/

#include "h264bsd_util.h"
#include "h264bsd_pic_order_cnt.h"

/*------------------------------------------------------------------------------
    2. External compiler flags
--------------------------------------------------------------------------------

--------------------------------------------------------------------------------
    3. Module defines
------------------------------------------------------------------------------*/

/*------------------------------------------------------------------------------
    4. Local function prototypes
------------------------------------------------------------------------------*/

/*------------------------------------------------------------------------------

    Function: h264bsdDecodePicOrderCnt

        Functional description:
            Compute picture order count for a picture. Function implements
            computation of all POC types (0, 1 and 2), type is obtained from
            sps. See standard for description of the POC types and how POC is
            computed for each type.

            Function returns the minimum of top field and bottom field pic
            order counts.

        Inputs:
            poc         pointer to previous results
            sps         pointer to sequence parameter set
            slicHeader  pointer to current slice header, frame number and
                        other params needed for POC computation
            pNalUnit    pointer to current NAL unit structrue, function needs
                        to know if this is an IDR picture and also if this is
                        a reference picture

        Outputs:
            poc         results stored here for computation of next POC

        Returns:
            picture order count

------------------------------------------------------------------------------*/

i32 h264bsdDecodePicOrderCnt(pocStorage_t *poc, seqParamSet_t *sps,
    sliceHeader_t *pSliceHeader, nalUnit_t *pNalUnit)
{

/* Variables */

    u32 i;
    i32 picOrderCnt;
    u32 frameNumOffset, absFrameNum, picOrderCntCycleCnt;
    u32 frameNumInPicOrderCntCycle;
    i32 expectedDeltaPicOrderCntCycle;
    u32 containsMmco5;

/* Code */

    ASSERT(poc);
    ASSERT(sps);
    ASSERT(pSliceHeader);
    ASSERT(pNalUnit);
    ASSERT(sps->picOrderCntType <= 2);

#if 0
    /* JanSa: I don't think this is necessary, don't see any reason to
     * increment prevFrameNum one by one instead of one big increment.
     * However, standard specifies that this should be done -> if someone
     * figures out any case when the outcome would be different for step by
     * step increment, this part of the code should be enabled */

    /* if there was a gap in frame numbering and picOrderCntType is 1 or 2 ->
     * "compute" pic order counts for non-existing frames. These are not
     * actually computed, but process needs to be done to update the
     * prevFrameNum and prevFrameNumOffset */
    if ( sps->picOrderCntType > 0 &&
         pSliceHeader->frameNum != poc->prevFrameNum &&
         pSliceHeader->frameNum != ((poc->prevFrameNum + 1) % sps->maxFrameNum))
    {

        /* use variable i for unUsedShortTermFrameNum */
        i = (poc->prevFrameNum + 1) % sps->maxFrameNum;

        do
        {
            if (poc->prevFrameNum > i)
                frameNumOffset = poc->prevFrameNumOffset + sps->maxFrameNum;
            else
                frameNumOffset = poc->prevFrameNumOffset;

            poc->prevFrameNumOffset = frameNumOffset;
            poc->prevFrameNum = i;

            i = (i + 1) % sps->maxFrameNum;

        } while (i != pSliceHeader->frameNum);
    }
#endif

    /* check if current slice includes mmco equal to 5 */
    containsMmco5 = HANTRO_FALSE;
    if (pSliceHeader->decRefPicMarking.adaptiveRefPicMarkingModeFlag)
    {
        i = 0;
        while (pSliceHeader->decRefPicMarking.operation[i].
            memoryManagementControlOperation)
        {
            if (pSliceHeader->decRefPicMarking.operation[i].
                memoryManagementControlOperation == 5)
            {
                containsMmco5 = HANTRO_TRUE;
                break;
            }
            i++;
        }
    }
    switch (sps->picOrderCntType)
    {

        case 0:
            /* set prevPicOrderCnt values for IDR frame */
            if (IS_IDR_NAL_UNIT(pNalUnit))
            {
                poc->prevPicOrderCntMsb = 0;
                poc->prevPicOrderCntLsb = 0;
            }

            /* compute picOrderCntMsb (stored in picOrderCnt variable) */
            if ( (pSliceHeader->picOrderCntLsb < poc->prevPicOrderCntLsb) &&
                ((poc->prevPicOrderCntLsb - pSliceHeader->picOrderCntLsb) >=
                 sps->maxPicOrderCntLsb/2) )
            {
                picOrderCnt = poc->prevPicOrderCntMsb +
                    (i32)sps->maxPicOrderCntLsb;
            }
            else if ((pSliceHeader->picOrderCntLsb > poc->prevPicOrderCntLsb) &&
                ((pSliceHeader->picOrderCntLsb - poc->prevPicOrderCntLsb) >
                 sps->maxPicOrderCntLsb/2) )
            {
                picOrderCnt = poc->prevPicOrderCntMsb -
                    (i32)sps->maxPicOrderCntLsb;
            }
            else
                picOrderCnt = poc->prevPicOrderCntMsb;

            /* standard specifies that prevPicOrderCntMsb is from previous
             * rererence frame -> replace old value only if current frame is
             * rererence frame */
            if (pNalUnit->nalRefIdc)
                poc->prevPicOrderCntMsb = picOrderCnt;

            /* compute top field order cnt (stored in picOrderCnt) */
            picOrderCnt += (i32)pSliceHeader->picOrderCntLsb;

            /* if delta for bottom field is negative -> bottom will be the
             * minimum pic order count */
            if (pSliceHeader->deltaPicOrderCntBottom < 0)
                picOrderCnt += pSliceHeader->deltaPicOrderCntBottom;

            /* standard specifies that prevPicOrderCntLsb is from previous
             * rererence frame -> replace old value only if current frame is
             * rererence frame */
            if (pNalUnit->nalRefIdc)
            {
                /* if current frame contains mmco5 -> modify values to be
                 * stored */
                if (containsMmco5)
                {
                    poc->prevPicOrderCntMsb = 0;
                    /* prevPicOrderCntLsb should be the top field picOrderCnt
                     * if previous frame included mmco5. Top field picOrderCnt
                     * for frames containing mmco5 is obtained by subtracting
                     * the picOrderCnt from original top field order count ->
                     * value is zero if top field was the minimum, i.e. delta
                     * for bottom was positive, otherwise value is
                     * -deltaPicOrderCntBottom */
                    if (pSliceHeader->deltaPicOrderCntBottom < 0)
                        poc->prevPicOrderCntLsb =
                            (u32)(-pSliceHeader->deltaPicOrderCntBottom);
                    else
                        poc->prevPicOrderCntLsb = 0;
                    picOrderCnt = 0;
                }
                else
                {
                    poc->prevPicOrderCntLsb = pSliceHeader->picOrderCntLsb;
                }
            }

            break;

        case 1:

            /* step 1 (in the description in the standard) */
            if (IS_IDR_NAL_UNIT(pNalUnit))
                frameNumOffset = 0;
            else if (poc->prevFrameNum > pSliceHeader->frameNum)
                frameNumOffset = poc->prevFrameNumOffset + sps->maxFrameNum;
            else
                frameNumOffset = poc->prevFrameNumOffset;

            /* step 2 */
            if (sps->numRefFramesInPicOrderCntCycle)
                absFrameNum = frameNumOffset + pSliceHeader->frameNum;
            else
                absFrameNum = 0;

            if (pNalUnit->nalRefIdc == 0 && absFrameNum > 0)
                absFrameNum -= 1;

            /* step 3 */
            if (absFrameNum > 0)
            {
                picOrderCntCycleCnt =
                    (absFrameNum - 1)/sps->numRefFramesInPicOrderCntCycle;
                frameNumInPicOrderCntCycle =
                    (absFrameNum - 1)%sps->numRefFramesInPicOrderCntCycle;
            }

            /* step 4 */
            expectedDeltaPicOrderCntCycle = 0;
            for (i = 0; i < sps->numRefFramesInPicOrderCntCycle; i++)
                expectedDeltaPicOrderCntCycle += sps->offsetForRefFrame[i];

            /* step 5 (picOrderCnt used to store expectedPicOrderCnt) */
            /*lint -esym(644,picOrderCntCycleCnt) always initialized */
            /*lint -esym(644,frameNumInPicOrderCntCycle) always initialized */
            if (absFrameNum > 0)
            {
                picOrderCnt =
                    (i32)picOrderCntCycleCnt * expectedDeltaPicOrderCntCycle;
                for (i = 0; i <= frameNumInPicOrderCntCycle; i++)
                    picOrderCnt += sps->offsetForRefFrame[i];
            }
            else
                picOrderCnt = 0;

            if (pNalUnit->nalRefIdc == 0)
                picOrderCnt += sps->offsetForNonRefPic;

            /* step 6 (picOrderCnt is top field order cnt if delta for bottom
             * is positive, otherwise it is bottom field order cnt) */
            picOrderCnt += pSliceHeader->deltaPicOrderCnt[0];

            if ( (sps->offsetForTopToBottomField +
                    pSliceHeader->deltaPicOrderCnt[1]) < 0 )
            {
                picOrderCnt += sps->offsetForTopToBottomField +
                    pSliceHeader->deltaPicOrderCnt[1];
            }

            /* if current picture contains mmco5 -> set prevFrameNumOffset and
             * prevFrameNum to 0 for computation of picOrderCnt of next
             * frame, otherwise store frameNum and frameNumOffset to poc
             * structure */
            if (!containsMmco5)
            {
                poc->prevFrameNumOffset = frameNumOffset;
                poc->prevFrameNum = pSliceHeader->frameNum;
            }
            else
            {
                poc->prevFrameNumOffset = 0;
                poc->prevFrameNum = 0;
                picOrderCnt = 0;
            }
            break;

        default: /* case 2 */
            /* derive frameNumOffset */
            if (IS_IDR_NAL_UNIT(pNalUnit))
                frameNumOffset = 0;
            else if (poc->prevFrameNum > pSliceHeader->frameNum)
                frameNumOffset = poc->prevFrameNumOffset + sps->maxFrameNum;
            else
                frameNumOffset = poc->prevFrameNumOffset;

            /* derive picOrderCnt (type 2 has same value for top and bottom
             * field order cnts) */
            if (IS_IDR_NAL_UNIT(pNalUnit))
                picOrderCnt = 0;
            else if (pNalUnit->nalRefIdc == 0)
                picOrderCnt =
                    2 * (i32)(frameNumOffset + pSliceHeader->frameNum) - 1;
            else
                picOrderCnt =
                    2 * (i32)(frameNumOffset + pSliceHeader->frameNum);

            /* if current picture contains mmco5 -> set prevFrameNumOffset and
             * prevFrameNum to 0 for computation of picOrderCnt of next
             * frame, otherwise store frameNum and frameNumOffset to poc
             * structure */
            if (!containsMmco5)
            {
                poc->prevFrameNumOffset = frameNumOffset;
                poc->prevFrameNum = pSliceHeader->frameNum;
            }
            else
            {
                poc->prevFrameNumOffset = 0;
                poc->prevFrameNum = 0;
                picOrderCnt = 0;
            }
            break;

    }

    /*lint -esym(644,picOrderCnt) always initialized */
    return(picOrderCnt);

}

