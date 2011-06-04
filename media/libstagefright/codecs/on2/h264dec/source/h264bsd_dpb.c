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
          ComparePictures
          h264bsdReorderRefPicList
          Mmcop1
          Mmcop2
          Mmcop3
          Mmcop4
          Mmcop5
          Mmcop6
          h264bsdMarkDecRefPic
          h264bsdGetRefPicData
          h264bsdAllocateDpbImage
          SlidingWindowRefPicMarking
          h264bsdInitDpb
          h264bsdResetDpb
          h264bsdInitRefPicList
          FindDpbPic
          SetPicNums
          h264bsdCheckGapsInFrameNum
          FindSmallestPicOrderCnt
          OutputPicture
          h264bsdDpbOutputPicture
          h264bsdFlushDpb
          h264bsdFreeDpb

------------------------------------------------------------------------------*/

/*------------------------------------------------------------------------------
    1. Include headers
------------------------------------------------------------------------------*/

#include "h264bsd_cfg.h"
#include "h264bsd_dpb.h"
#include "h264bsd_slice_header.h"
#include "h264bsd_image.h"
#include "h264bsd_util.h"
#include "basetype.h"

/*------------------------------------------------------------------------------
    2. External compiler flags
--------------------------------------------------------------------------------

--------------------------------------------------------------------------------
    3. Module defines
------------------------------------------------------------------------------*/

/* macros to determine picture status. Note that IS_SHORT_TERM macro returns
 * true also for non-existing pictures because non-existing pictures are
 * regarded short term pictures according to H.264 standard */
#define IS_REFERENCE(a) ((a).status)
#define IS_EXISTING(a) ((a).status > NON_EXISTING)
#define IS_SHORT_TERM(a) \
    ((a).status == NON_EXISTING || (a).status == SHORT_TERM)
#define IS_LONG_TERM(a) ((a).status == LONG_TERM)

/* macro to set a picture unused for reference */
#define SET_UNUSED(a) (a).status = UNUSED;

#define MAX_NUM_REF_IDX_L0_ACTIVE 16

/*------------------------------------------------------------------------------
    4. Local function prototypes
------------------------------------------------------------------------------*/

static i32 ComparePictures(const void *ptr1, const void *ptr2);

static u32 Mmcop1(dpbStorage_t *dpb, u32 currPicNum, u32 differenceOfPicNums);

static u32 Mmcop2(dpbStorage_t *dpb, u32 longTermPicNum);

static u32 Mmcop3(dpbStorage_t *dpb, u32 currPicNum, u32 differenceOfPicNums,
    u32 longTermFrameIdx);

static u32 Mmcop4(dpbStorage_t *dpb, u32 maxLongTermFrameIdx);

static u32 Mmcop5(dpbStorage_t *dpb);

static u32 Mmcop6(dpbStorage_t *dpb, u32 frameNum, i32 picOrderCnt,
    u32 longTermFrameIdx);

static u32 SlidingWindowRefPicMarking(dpbStorage_t *dpb);

static i32 FindDpbPic(dpbStorage_t *dpb, i32 picNum, u32 isShortTerm);

static void SetPicNums(dpbStorage_t *dpb, u32 currFrameNum);

static dpbPicture_t* FindSmallestPicOrderCnt(dpbStorage_t *dpb);

static u32 OutputPicture(dpbStorage_t *dpb);

static void ShellSort(dpbPicture_t *pPic, u32 num);

/*------------------------------------------------------------------------------

    Function: ComparePictures

        Functional description:
            Function to compare dpb pictures, used by the ShellSort() function.
            Order of the pictures after sorting shall be as follows:
                1) short term reference pictures starting with the largest
                   picNum
                2) long term reference pictures starting with the smallest
                   longTermPicNum
                3) pictures unused for reference but needed for display
                4) other pictures

        Returns:
            -1      pic 1 is greater than pic 2
             0      equal from comparison point of view
             1      pic 2 is greater then pic 1

------------------------------------------------------------------------------*/

static i32 ComparePictures(const void *ptr1, const void *ptr2)
{

/* Variables */

    dpbPicture_t *pic1, *pic2;

/* Code */

    ASSERT(ptr1);
    ASSERT(ptr2);

    pic1 = (dpbPicture_t*)ptr1;
    pic2 = (dpbPicture_t*)ptr2;

    /* both are non-reference pictures, check if needed for display */
    if (!IS_REFERENCE(*pic1) && !IS_REFERENCE(*pic2))
    {
        if (pic1->toBeDisplayed && !pic2->toBeDisplayed)
            return(-1);
        else if (!pic1->toBeDisplayed && pic2->toBeDisplayed)
            return(1);
        else
            return(0);
    }
    /* only pic 1 needed for reference -> greater */
    else if (!IS_REFERENCE(*pic2))
        return(-1);
    /* only pic 2 needed for reference -> greater */
    else if (!IS_REFERENCE(*pic1))
        return(1);
    /* both are short term reference pictures -> check picNum */
    else if (IS_SHORT_TERM(*pic1) && IS_SHORT_TERM(*pic2))
    {
        if (pic1->picNum > pic2->picNum)
            return(-1);
        else if (pic1->picNum < pic2->picNum)
            return(1);
        else
            return(0);
    }
    /* only pic 1 is short term -> greater */
    else if (IS_SHORT_TERM(*pic1))
        return(-1);
    /* only pic 2 is short term -> greater */
    else if (IS_SHORT_TERM(*pic2))
        return(1);
    /* both are long term reference pictures -> check picNum (contains the
     * longTermPicNum */
    else
    {
        if (pic1->picNum > pic2->picNum)
            return(1);
        else if (pic1->picNum < pic2->picNum)
            return(-1);
        else
            return(0);
    }
}

/*------------------------------------------------------------------------------

    Function: h264bsdReorderRefPicList

        Functional description:
            Function to perform reference picture list reordering based on
            reordering commands received in the slice header. See details
            of the process in the H.264 standard.

        Inputs:
            dpb             pointer to dpb storage structure
            order           pointer to reordering commands
            currFrameNum    current frame number
            numRefIdxActive number of active reference indices for current
                            picture

        Outputs:
            dpb             'list' field of the structure reordered

        Returns:
            HANTRO_OK      success
            HANTRO_NOK     if non-existing pictures referred to in the
                           reordering commands

------------------------------------------------------------------------------*/

u32 h264bsdReorderRefPicList(
  dpbStorage_t *dpb,
  refPicListReordering_t *order,
  u32 currFrameNum,
  u32 numRefIdxActive)
{

/* Variables */

    u32 i, j, k, picNumPred, refIdx;
    i32 picNum, picNumNoWrap, index;
    u32 isShortTerm;

/* Code */

    ASSERT(order);
    ASSERT(currFrameNum <= dpb->maxFrameNum);
    ASSERT(numRefIdxActive <= MAX_NUM_REF_IDX_L0_ACTIVE);

    /* set dpb picture numbers for sorting */
    SetPicNums(dpb, currFrameNum);

    if (!order->refPicListReorderingFlagL0)
        return(HANTRO_OK);

    refIdx     = 0;
    picNumPred = currFrameNum;

    i = 0;
    while (order->command[i].reorderingOfPicNumsIdc < 3)
    {
        /* short term */
        if (order->command[i].reorderingOfPicNumsIdc < 2)
        {
            if (order->command[i].reorderingOfPicNumsIdc == 0)
            {
                picNumNoWrap =
                    (i32)picNumPred - (i32)order->command[i].absDiffPicNum;
                if (picNumNoWrap < 0)
                    picNumNoWrap += (i32)dpb->maxFrameNum;
            }
            else
            {
                picNumNoWrap =
                    (i32)(picNumPred + order->command[i].absDiffPicNum);
                if (picNumNoWrap >= (i32)dpb->maxFrameNum)
                    picNumNoWrap -= (i32)dpb->maxFrameNum;
            }
            picNumPred = (u32)picNumNoWrap;
            picNum = picNumNoWrap;
            if ((u32)picNumNoWrap > currFrameNum)
                picNum -= (i32)dpb->maxFrameNum;
            isShortTerm = HANTRO_TRUE;
        }
        /* long term */
        else
        {
            picNum = (i32)order->command[i].longTermPicNum;
            isShortTerm = HANTRO_FALSE;

        }
        /* find corresponding picture from dpb */
        index = FindDpbPic(dpb, picNum, isShortTerm);
        if (index < 0 || !IS_EXISTING(dpb->buffer[index]))
            return(HANTRO_NOK);

        /* shift pictures */
        for (j = numRefIdxActive; j > refIdx; j--)
            dpb->list[j] = dpb->list[j-1];
        /* put picture into the list */
        dpb->list[refIdx++] = &dpb->buffer[index];
        /* remove later references to the same picture */
        for (j = k = refIdx; j <= numRefIdxActive; j++)
            if(dpb->list[j] != &dpb->buffer[index])
                dpb->list[k++] = dpb->list[j];

        i++;
    }

    return(HANTRO_OK);

}

/*------------------------------------------------------------------------------

    Function: Mmcop1

        Functional description:
            Function to mark a short-term reference picture unused for
            reference, memory_management_control_operation equal to 1

        Returns:
            HANTRO_OK      success
            HANTRO_NOK     failure, picture does not exist in the buffer

------------------------------------------------------------------------------*/

static u32 Mmcop1(dpbStorage_t *dpb, u32 currPicNum, u32 differenceOfPicNums)
{

/* Variables */

    i32 index, picNum;

/* Code */

    ASSERT(currPicNum < dpb->maxFrameNum);

    picNum = (i32)currPicNum - (i32)differenceOfPicNums;

    index = FindDpbPic(dpb, picNum, HANTRO_TRUE);
    if (index < 0)
        return(HANTRO_NOK);

    SET_UNUSED(dpb->buffer[index]);
    dpb->numRefFrames--;
    if (!dpb->buffer[index].toBeDisplayed)
        dpb->fullness--;

    return(HANTRO_OK);

}

/*------------------------------------------------------------------------------

    Function: Mmcop2

        Functional description:
            Function to mark a long-term reference picture unused for
            reference, memory_management_control_operation equal to 2

        Returns:
            HANTRO_OK      success
            HANTRO_NOK     failure, picture does not exist in the buffer

------------------------------------------------------------------------------*/

static u32 Mmcop2(dpbStorage_t *dpb, u32 longTermPicNum)
{

/* Variables */

    i32 index;

/* Code */

    index = FindDpbPic(dpb, (i32)longTermPicNum, HANTRO_FALSE);
    if (index < 0)
        return(HANTRO_NOK);

    SET_UNUSED(dpb->buffer[index]);
    dpb->numRefFrames--;
    if (!dpb->buffer[index].toBeDisplayed)
        dpb->fullness--;

    return(HANTRO_OK);

}

/*------------------------------------------------------------------------------

    Function: Mmcop3

        Functional description:
            Function to assing a longTermFrameIdx to a short-term reference
            frame (i.e. to change it to a long-term reference picture),
            memory_management_control_operation equal to 3

        Returns:
            HANTRO_OK      success
            HANTRO_NOK     failure, short-term picture does not exist in the
                           buffer or is a non-existing picture, or invalid
                           longTermFrameIdx given

------------------------------------------------------------------------------*/

static u32 Mmcop3(dpbStorage_t *dpb, u32 currPicNum, u32 differenceOfPicNums,
    u32 longTermFrameIdx)
{

/* Variables */

    i32 index, picNum;
    u32 i;

/* Code */

    ASSERT(dpb);
    ASSERT(currPicNum < dpb->maxFrameNum);

    if ( (dpb->maxLongTermFrameIdx == NO_LONG_TERM_FRAME_INDICES) ||
         (longTermFrameIdx > dpb->maxLongTermFrameIdx) )
        return(HANTRO_NOK);

    /* check if a long term picture with the same longTermFrameIdx already
     * exist and remove it if necessary */
    for (i = 0; i < dpb->maxRefFrames; i++)
        if (IS_LONG_TERM(dpb->buffer[i]) &&
          (u32)dpb->buffer[i].picNum == longTermFrameIdx)
        {
            SET_UNUSED(dpb->buffer[i]);
            dpb->numRefFrames--;
            if (!dpb->buffer[i].toBeDisplayed)
                dpb->fullness--;
            break;
        }

    picNum = (i32)currPicNum - (i32)differenceOfPicNums;

    index = FindDpbPic(dpb, picNum, HANTRO_TRUE);
    if (index < 0)
        return(HANTRO_NOK);
    if (!IS_EXISTING(dpb->buffer[index]))
        return(HANTRO_NOK);

    dpb->buffer[index].status = LONG_TERM;
    dpb->buffer[index].picNum = (i32)longTermFrameIdx;

    return(HANTRO_OK);

}

/*------------------------------------------------------------------------------

    Function: Mmcop4

        Functional description:
            Function to set maxLongTermFrameIdx,
            memory_management_control_operation equal to 4

        Returns:
            HANTRO_OK      success

------------------------------------------------------------------------------*/

static u32 Mmcop4(dpbStorage_t *dpb, u32 maxLongTermFrameIdx)
{

/* Variables */

    u32 i;

/* Code */

    dpb->maxLongTermFrameIdx = maxLongTermFrameIdx;

    for (i = 0; i < dpb->maxRefFrames; i++)
        if (IS_LONG_TERM(dpb->buffer[i]) &&
          ( ((u32)dpb->buffer[i].picNum > maxLongTermFrameIdx) ||
            (dpb->maxLongTermFrameIdx == NO_LONG_TERM_FRAME_INDICES) ) )
        {
            SET_UNUSED(dpb->buffer[i]);
            dpb->numRefFrames--;
            if (!dpb->buffer[i].toBeDisplayed)
                dpb->fullness--;
        }

    return(HANTRO_OK);

}

/*------------------------------------------------------------------------------

    Function: Mmcop5

        Functional description:
            Function to mark all reference pictures unused for reference and
            set maxLongTermFrameIdx to NO_LONG_TERM_FRAME_INDICES,
            memory_management_control_operation equal to 5. Function flushes
            the buffer and places all pictures that are needed for display into
            the output buffer.

        Returns:
            HANTRO_OK      success

------------------------------------------------------------------------------*/

static u32 Mmcop5(dpbStorage_t *dpb)
{

/* Variables */

    u32 i;

/* Code */

    for (i = 0; i < 16; i++)
    {
        if (IS_REFERENCE(dpb->buffer[i]))
        {
            SET_UNUSED(dpb->buffer[i]);
            if (!dpb->buffer[i].toBeDisplayed)
                dpb->fullness--;
        }
    }

    /* output all pictures */
    while (OutputPicture(dpb) == HANTRO_OK)
        ;
    dpb->numRefFrames = 0;
    dpb->maxLongTermFrameIdx = NO_LONG_TERM_FRAME_INDICES;
    dpb->prevRefFrameNum = 0;

    return(HANTRO_OK);

}

/*------------------------------------------------------------------------------

    Function: Mmcop6

        Functional description:
            Function to assign longTermFrameIdx to the current picture,
            memory_management_control_operation equal to 6

        Returns:
            HANTRO_OK      success
            HANTRO_NOK     invalid longTermFrameIdx or no room for current
                           picture in the buffer

------------------------------------------------------------------------------*/

static u32 Mmcop6(dpbStorage_t *dpb, u32 frameNum, i32 picOrderCnt,
    u32 longTermFrameIdx)
{

/* Variables */

    u32 i;

/* Code */

    ASSERT(frameNum < dpb->maxFrameNum);

    if ( (dpb->maxLongTermFrameIdx == NO_LONG_TERM_FRAME_INDICES) ||
         (longTermFrameIdx > dpb->maxLongTermFrameIdx) )
        return(HANTRO_NOK);

    /* check if a long term picture with the same longTermFrameIdx already
     * exist and remove it if necessary */
    for (i = 0; i < dpb->maxRefFrames; i++)
        if (IS_LONG_TERM(dpb->buffer[i]) &&
          (u32)dpb->buffer[i].picNum == longTermFrameIdx)
        {
            SET_UNUSED(dpb->buffer[i]);
            dpb->numRefFrames--;
            if (!dpb->buffer[i].toBeDisplayed)
                dpb->fullness--;
            break;
        }

    if (dpb->numRefFrames < dpb->maxRefFrames)
    {
        dpb->currentOut->frameNum = frameNum;
        dpb->currentOut->picNum   = (i32)longTermFrameIdx;
        dpb->currentOut->picOrderCnt = picOrderCnt;
        dpb->currentOut->status   = LONG_TERM;
        if (dpb->noReordering)
            dpb->currentOut->toBeDisplayed = HANTRO_FALSE;
        else
            dpb->currentOut->toBeDisplayed = HANTRO_TRUE;
        dpb->numRefFrames++;
        dpb->fullness++;
        return(HANTRO_OK);
    }
    /* if there is no room, return an error */
    else
        return(HANTRO_NOK);

}

/*------------------------------------------------------------------------------

    Function: h264bsdMarkDecRefPic

        Functional description:
            Function to perform reference picture marking process. This
            function should be called both for reference and non-reference
            pictures.  Non-reference pictures shall have mark pointer set to
            NULL.

        Inputs:
            dpb         pointer to the DPB data structure
            mark        pointer to reference picture marking commands
            image       pointer to current picture to be placed in the buffer
            frameNum    frame number of the current picture
            picOrderCnt picture order count for the current picture
            isIdr       flag to indicate if the current picture is an
                        IDR picture
            currentPicId    identifier for the current picture, from the
                            application, stored along with the picture
            numErrMbs       number of concealed macroblocks in the current
                            picture, stored along with the picture

        Outputs:
            dpb         'buffer' modified, possible output frames placed into
                        'outBuf'

        Returns:
            HANTRO_OK   success
            HANTRO_NOK  failure

------------------------------------------------------------------------------*/

u32 h264bsdMarkDecRefPic(
  dpbStorage_t *dpb,
  decRefPicMarking_t *mark,
  image_t *image,
  u32 frameNum,
  i32 picOrderCnt,
  u32 isIdr,
  u32 currentPicId,
  u32 numErrMbs)
{

/* Variables */

    u32 i, status;
    u32 markedAsLongTerm;
    u32 toBeDisplayed;

/* Code */

    ASSERT(dpb);
    ASSERT(mark || !isIdr);
    ASSERT(!isIdr || (frameNum == 0 && picOrderCnt == 0));
    ASSERT(frameNum < dpb->maxFrameNum);

    if (image->data != dpb->currentOut->data)
    {
        EPRINT("TRYING TO MARK NON-ALLOCATED IMAGE");
        return(HANTRO_NOK);
    }

    dpb->lastContainsMmco5 = HANTRO_FALSE;
    status = HANTRO_OK;

    toBeDisplayed = dpb->noReordering ? HANTRO_FALSE : HANTRO_TRUE;

    /* non-reference picture, stored for display reordering purposes */
    if (mark == NULL)
    {
        dpb->currentOut->status = UNUSED;
        dpb->currentOut->frameNum = frameNum;
        dpb->currentOut->picNum = (i32)frameNum;
        dpb->currentOut->picOrderCnt = picOrderCnt;
        dpb->currentOut->toBeDisplayed = toBeDisplayed;
        if (!dpb->noReordering)
            dpb->fullness++;
    }
    /* IDR picture */
    else if (isIdr)
    {

        /* h264bsdCheckGapsInFrameNum not called for IDR pictures -> have to
         * reset numOut and outIndex here */
        dpb->numOut = dpb->outIndex = 0;

        /* flush the buffer */
        Mmcop5(dpb);
        /* if noOutputOfPriorPicsFlag was set -> the pictures preceding the
         * IDR picture shall not be output -> set output buffer empty */
        if (mark->noOutputOfPriorPicsFlag || dpb->noReordering)
        {
            dpb->numOut = 0;
            dpb->outIndex = 0;
        }

        if (mark->longTermReferenceFlag)
        {
            dpb->currentOut->status = LONG_TERM;
            dpb->maxLongTermFrameIdx = 0;
        }
        else
        {
            dpb->currentOut->status = SHORT_TERM;
            dpb->maxLongTermFrameIdx = NO_LONG_TERM_FRAME_INDICES;
        }
        dpb->currentOut->frameNum  = 0;
        dpb->currentOut->picNum    = 0;
        dpb->currentOut->picOrderCnt = 0;
        dpb->currentOut->toBeDisplayed = toBeDisplayed;
        dpb->fullness = 1;
        dpb->numRefFrames = 1;
    }
    /* reference picture */
    else
    {
        markedAsLongTerm = HANTRO_FALSE;
        if (mark->adaptiveRefPicMarkingModeFlag)
        {
            i = 0;
            while (mark->operation[i].memoryManagementControlOperation)
            {
                switch (mark->operation[i].memoryManagementControlOperation)
                {
                    case 1:
                        status = Mmcop1(
                          dpb,
                          frameNum,
                          mark->operation[i].differenceOfPicNums);
                        break;

                    case 2:
                        status = Mmcop2(dpb, mark->operation[i].longTermPicNum);
                        break;

                    case 3:
                        status =  Mmcop3(
                          dpb,
                          frameNum,
                          mark->operation[i].differenceOfPicNums,
                          mark->operation[i].longTermFrameIdx);
                        break;

                    case 4:
                        status = Mmcop4(
                          dpb,
                          mark->operation[i].maxLongTermFrameIdx);
                        break;

                    case 5:
                        status = Mmcop5(dpb);
                        dpb->lastContainsMmco5 = HANTRO_TRUE;
                        frameNum = 0;
                        break;

                    case 6:
                        status = Mmcop6(
                          dpb,
                          frameNum,
                          picOrderCnt,
                          mark->operation[i].longTermFrameIdx);
                        if (status == HANTRO_OK)
                            markedAsLongTerm = HANTRO_TRUE;
                        break;

                    default: /* invalid memory management control operation */
                        status = HANTRO_NOK;
                        break;
                }
                if (status != HANTRO_OK)
                {
                    break;
                }
                i++;
            }
        }
        else
        {
            status = SlidingWindowRefPicMarking(dpb);
        }
        /* if current picture was not marked as long-term reference by
         * memory management control operation 6 -> mark current as short
         * term and insert it into dpb (if there is room) */
        if (!markedAsLongTerm)
        {
            if (dpb->numRefFrames < dpb->maxRefFrames)
            {
                dpb->currentOut->frameNum = frameNum;
                dpb->currentOut->picNum   = (i32)frameNum;
                dpb->currentOut->picOrderCnt = picOrderCnt;
                dpb->currentOut->status   = SHORT_TERM;
                dpb->currentOut->toBeDisplayed = toBeDisplayed;
                dpb->fullness++;
                dpb->numRefFrames++;
            }
            /* no room */
            else
            {
                status = HANTRO_NOK;
            }
        }
    }

    dpb->currentOut->isIdr = isIdr;
    dpb->currentOut->picId = currentPicId;
    dpb->currentOut->numErrMbs = numErrMbs;

    /* dpb was initialized to not to reorder the pictures -> output current
     * picture immediately */
    if (dpb->noReordering)
    {
        ASSERT(dpb->numOut == 0);
        ASSERT(dpb->outIndex == 0);
        dpb->outBuf[dpb->numOut].data  = dpb->currentOut->data;
        dpb->outBuf[dpb->numOut].isIdr = dpb->currentOut->isIdr;
        dpb->outBuf[dpb->numOut].picId = dpb->currentOut->picId;
        dpb->outBuf[dpb->numOut].numErrMbs = dpb->currentOut->numErrMbs;
        dpb->numOut++;
    }
    else
    {
        /* output pictures if buffer full */
        while (dpb->fullness > dpb->dpbSize)
        {
            i = OutputPicture(dpb);
            ASSERT(i == HANTRO_OK);
        }
    }

    /* sort dpb */
    ShellSort(dpb->buffer, dpb->dpbSize+1);

    return(status);

}

/*------------------------------------------------------------------------------

    Function: h264bsdGetRefPicData

        Functional description:
            Function to get reference picture data from the reference picture
            list

        Returns:
            pointer to desired reference picture data
            NULL if invalid index or non-existing picture referred

------------------------------------------------------------------------------*/

u8* h264bsdGetRefPicData(dpbStorage_t *dpb, u32 index)
{

/* Variables */

/* Code */

    if(index > 16 || dpb->list[index] == NULL)
        return(NULL);
    else if(!IS_EXISTING(*dpb->list[index]))
        return(NULL);
    else
        return(dpb->list[index]->data);

}

/*------------------------------------------------------------------------------

    Function: h264bsdAllocateDpbImage

        Functional description:
            function to allocate memory for a image. This function does not
            really allocate any memory but reserves one of the buffer
            positions for decoding of current picture

        Returns:
            pointer to memory area for the image


------------------------------------------------------------------------------*/

u8* h264bsdAllocateDpbImage(dpbStorage_t *dpb)
{

/* Variables */

/* Code */

    ASSERT( !dpb->buffer[dpb->dpbSize].toBeDisplayed &&
            !IS_REFERENCE(dpb->buffer[dpb->dpbSize]) );
    ASSERT(dpb->fullness <=  dpb->dpbSize);

    dpb->currentOut = dpb->buffer + dpb->dpbSize;

    return(dpb->currentOut->data);

}

/*------------------------------------------------------------------------------

    Function: SlidingWindowRefPicMarking

        Functional description:
            Function to perform sliding window refence picture marking process.

        Outputs:
            HANTRO_OK      success
            HANTRO_NOK     failure, no short-term reference frame found that
                           could be marked unused


------------------------------------------------------------------------------*/

static u32 SlidingWindowRefPicMarking(dpbStorage_t *dpb)
{

/* Variables */

    i32 index, picNum;
    u32 i;

/* Code */

    if (dpb->numRefFrames < dpb->maxRefFrames)
    {
        return(HANTRO_OK);
    }
    else
    {
        index = -1;
        picNum = 0;
        /* find the oldest short term picture */
        for (i = 0; i < dpb->numRefFrames; i++)
            if (IS_SHORT_TERM(dpb->buffer[i]))
                if (dpb->buffer[i].picNum < picNum || index == -1)
                {
                    index = (i32)i;
                    picNum = dpb->buffer[i].picNum;
                }
        if (index >= 0)
        {
            SET_UNUSED(dpb->buffer[index]);
            dpb->numRefFrames--;
            if (!dpb->buffer[index].toBeDisplayed)
                dpb->fullness--;

            return(HANTRO_OK);
        }
    }

    return(HANTRO_NOK);

}

/*------------------------------------------------------------------------------

    Function: h264bsdInitDpb

        Functional description:
            Function to initialize DPB. Reserves memories for the buffer,
            reference picture list and output buffer. dpbSize indicates
            the maximum DPB size indicated by the levelIdc in the stream.
            If noReordering flag is FALSE the DPB stores dpbSize pictures
            for display reordering purposes. On the other hand, if the
            flag is TRUE the DPB only stores maxRefFrames reference pictures
            and outputs all the pictures immediately.

        Inputs:
            picSizeInMbs    picture size in macroblocks
            dpbSize         size of the DPB (number of pictures)
            maxRefFrames    max number of reference frames
            maxFrameNum     max frame number
            noReordering    flag to indicate that DPB does not have to
                            prepare to reorder frames for display

        Outputs:
            dpb             pointer to dpb data storage

        Returns:
            HANTRO_OK       success
            MEMORY_ALLOCATION_ERROR if memory allocation failed

------------------------------------------------------------------------------*/

u32 h264bsdInitDpb(
  dpbStorage_t *dpb,
  u32 picSizeInMbs,
  u32 dpbSize,
  u32 maxRefFrames,
  u32 maxFrameNum,
  u32 noReordering)
{

/* Variables */

    u32 i;

/* Code */

    ASSERT(picSizeInMbs);
    ASSERT(maxRefFrames <= MAX_NUM_REF_PICS);
    ASSERT(maxRefFrames <= dpbSize);
    ASSERT(maxFrameNum);
    ASSERT(dpbSize);

    dpb->maxLongTermFrameIdx = NO_LONG_TERM_FRAME_INDICES;
    dpb->maxRefFrames        = MAX(maxRefFrames, 1);
    if (noReordering)
        dpb->dpbSize         = dpb->maxRefFrames;
    else
        dpb->dpbSize         = dpbSize;
    dpb->maxFrameNum         = maxFrameNum;
    dpb->noReordering        = noReordering;
    dpb->fullness            = 0;
    dpb->numRefFrames        = 0;
    dpb->prevRefFrameNum     = 0;

    ALLOCATE(dpb->buffer, MAX_NUM_REF_IDX_L0_ACTIVE + 1, dpbPicture_t);
    if (dpb->buffer == NULL)
        return(MEMORY_ALLOCATION_ERROR);
    H264SwDecMemset(dpb->buffer, 0,
            (MAX_NUM_REF_IDX_L0_ACTIVE + 1)*sizeof(dpbPicture_t));
    for (i = 0; i < dpb->dpbSize + 1; i++)
    {
        /* Allocate needed amount of memory, which is:
         * image size + 32 + 15, where 32 cames from the fact that in ARM OpenMax
         * DL implementation Functions may read beyond the end of an array,
         * by a maximum of 32 bytes. And +15 cames for the need to align memory
         * to 16-byte boundary */
        ALLOCATE(dpb->buffer[i].pAllocatedData, (picSizeInMbs*384 + 32+15), u8);
        if (dpb->buffer[i].pAllocatedData == NULL)
            return(MEMORY_ALLOCATION_ERROR);

        dpb->buffer[i].data = ALIGN(dpb->buffer[i].pAllocatedData, 16);
    }

    ALLOCATE(dpb->list, MAX_NUM_REF_IDX_L0_ACTIVE + 1, dpbPicture_t*);
    ALLOCATE(dpb->outBuf, dpb->dpbSize+1, dpbOutPicture_t);

    if (dpb->list == NULL || dpb->outBuf == NULL)
        return(MEMORY_ALLOCATION_ERROR);

    H264SwDecMemset(dpb->list, 0,
            ((MAX_NUM_REF_IDX_L0_ACTIVE + 1) * sizeof(dpbPicture_t*)) );

    dpb->numOut = dpb->outIndex = 0;

    return(HANTRO_OK);

}

/*------------------------------------------------------------------------------

    Function: h264bsdResetDpb

        Functional description:
            Function to reset DPB. This function should be called when an IDR
            slice (other than the first) activates new sequence parameter set.
            Function calls h264bsdFreeDpb to free old allocated memories and
            h264bsdInitDpb to re-initialize the DPB. Same inputs, outputs and
            returns as for h264bsdInitDpb.

------------------------------------------------------------------------------*/

u32 h264bsdResetDpb(
  dpbStorage_t *dpb,
  u32 picSizeInMbs,
  u32 dpbSize,
  u32 maxRefFrames,
  u32 maxFrameNum,
  u32 noReordering)
{

/* Code */

    ASSERT(picSizeInMbs);
    ASSERT(maxRefFrames <= MAX_NUM_REF_PICS);
    ASSERT(maxRefFrames <= dpbSize);
    ASSERT(maxFrameNum);
    ASSERT(dpbSize);

    h264bsdFreeDpb(dpb);

    return h264bsdInitDpb(dpb, picSizeInMbs, dpbSize, maxRefFrames,
                          maxFrameNum, noReordering);
}

/*------------------------------------------------------------------------------

    Function: h264bsdInitRefPicList

        Functional description:
            Function to initialize reference picture list. Function just
            sets pointers in the list according to pictures in the buffer.
            The buffer is assumed to contain pictures sorted according to
            what the H.264 standard says about initial reference picture list.

        Inputs:
            dpb     pointer to dpb data structure

        Outputs:
            dpb     'list' field initialized

        Returns:
            none

------------------------------------------------------------------------------*/

void h264bsdInitRefPicList(dpbStorage_t *dpb)
{

/* Variables */

    u32 i;

/* Code */

    for (i = 0; i < dpb->numRefFrames; i++)
        dpb->list[i] = &dpb->buffer[i];

}

/*------------------------------------------------------------------------------

    Function: FindDpbPic

        Functional description:
            Function to find a reference picture from the buffer. The picture
            to be found is identified by picNum and isShortTerm flag.

        Returns:
            index of the picture in the buffer
            -1 if the specified picture was not found in the buffer

------------------------------------------------------------------------------*/

static i32 FindDpbPic(dpbStorage_t *dpb, i32 picNum, u32 isShortTerm)
{

/* Variables */

    u32 i = 0;
    u32 found = HANTRO_FALSE;

/* Code */

    if (isShortTerm)
    {
        while (i < dpb->maxRefFrames && !found)
        {
            if (IS_SHORT_TERM(dpb->buffer[i]) &&
              dpb->buffer[i].picNum == picNum)
                found = HANTRO_TRUE;
            else
                i++;
        }
    }
    else
    {
        ASSERT(picNum >= 0);
        while (i < dpb->maxRefFrames && !found)
        {
            if (IS_LONG_TERM(dpb->buffer[i]) &&
              dpb->buffer[i].picNum == picNum)
                found = HANTRO_TRUE;
            else
                i++;
        }
    }

    if (found)
        return((i32)i);
    else
        return(-1);

}

/*------------------------------------------------------------------------------

    Function: SetPicNums

        Functional description:
            Function to set picNum values for short-term pictures in the
            buffer. Numbering of pictures is based on frame numbers and as
            frame numbers are modulo maxFrameNum -> frame numbers of older
            pictures in the buffer may be bigger than the currFrameNum.
            picNums will be set so that current frame has the largest picNum
            and all the short-term frames in the buffer will get smaller picNum
            representing their "distance" from the current frame. This
            function kind of maps the modulo arithmetic back to normal.

------------------------------------------------------------------------------*/

static void SetPicNums(dpbStorage_t *dpb, u32 currFrameNum)
{

/* Variables */

    u32 i;
    i32 frameNumWrap;

/* Code */

    ASSERT(dpb);
    ASSERT(currFrameNum < dpb->maxFrameNum);

    for (i = 0; i < dpb->numRefFrames; i++)
        if (IS_SHORT_TERM(dpb->buffer[i]))
        {
            if (dpb->buffer[i].frameNum > currFrameNum)
                frameNumWrap =
                    (i32)dpb->buffer[i].frameNum - (i32)dpb->maxFrameNum;
            else
                frameNumWrap = (i32)dpb->buffer[i].frameNum;
            dpb->buffer[i].picNum = frameNumWrap;
        }

}

/*------------------------------------------------------------------------------

    Function: h264bsdCheckGapsInFrameNum

        Functional description:
            Function to check gaps in frame_num and generate non-existing
            (short term) reference pictures if necessary. This function should
            be called only for non-IDR pictures.

        Inputs:
            dpb         pointer to dpb data structure
            frameNum    frame number of the current picture
            isRefPic    flag to indicate if current picture is a reference or
                        non-reference picture
            gapsAllowed Flag which indicates active SPS stance on whether
                        to allow gaps

        Outputs:
            dpb         'buffer' possibly modified by inserting non-existing
                        pictures with sliding window marking process

        Returns:
            HANTRO_OK   success
            HANTRO_NOK  error in sliding window reference picture marking or
                        frameNum equal to previous reference frame used for
                        a reference picture

------------------------------------------------------------------------------*/

u32 h264bsdCheckGapsInFrameNum(dpbStorage_t *dpb, u32 frameNum, u32 isRefPic,
                               u32 gapsAllowed)
{

/* Variables */

    u32 unUsedShortTermFrameNum;
    u8 *tmp;

/* Code */

    ASSERT(dpb);
    ASSERT(dpb->fullness <= dpb->dpbSize);
    ASSERT(frameNum < dpb->maxFrameNum);

    dpb->numOut = 0;
    dpb->outIndex = 0;

    if(!gapsAllowed)
        return(HANTRO_OK);

    if ( (frameNum != dpb->prevRefFrameNum) &&
         (frameNum != ((dpb->prevRefFrameNum + 1) % dpb->maxFrameNum)))
    {

        unUsedShortTermFrameNum = (dpb->prevRefFrameNum + 1) % dpb->maxFrameNum;

        /* store data pointer of last buffer position to be used as next
         * "allocated" data pointer if last buffer position after this process
         * contains data pointer located in outBuf (buffer placed in the output
         * shall not be overwritten by the current picture) */
        tmp = dpb->buffer[dpb->dpbSize].data;
        do
        {
            SetPicNums(dpb, unUsedShortTermFrameNum);

            if (SlidingWindowRefPicMarking(dpb) != HANTRO_OK)
            {
                return(HANTRO_NOK);
            }

            /* output pictures if buffer full */
            while (dpb->fullness >= dpb->dpbSize)
            {
#ifdef _ASSERT_USED
                ASSERT(!dpb->noReordering);
                ASSERT(OutputPicture(dpb) == HANTRO_OK);
#else
                OutputPicture(dpb);
#endif
            }

            /* add to end of list */
            ASSERT( !dpb->buffer[dpb->dpbSize].toBeDisplayed &&
                    !IS_REFERENCE(dpb->buffer[dpb->dpbSize]) );
            dpb->buffer[dpb->dpbSize].status = NON_EXISTING;
            dpb->buffer[dpb->dpbSize].frameNum = unUsedShortTermFrameNum;
            dpb->buffer[dpb->dpbSize].picNum   = (i32)unUsedShortTermFrameNum;
            dpb->buffer[dpb->dpbSize].picOrderCnt = 0;
            dpb->buffer[dpb->dpbSize].toBeDisplayed = HANTRO_FALSE;
            dpb->fullness++;
            dpb->numRefFrames++;

            /* sort the buffer */
            ShellSort(dpb->buffer, dpb->dpbSize+1);

            unUsedShortTermFrameNum = (unUsedShortTermFrameNum + 1) %
                dpb->maxFrameNum;

        } while (unUsedShortTermFrameNum != frameNum);

        /* pictures placed in output buffer -> check that 'data' in
         * buffer position dpbSize is not in the output buffer (this will be
         * "allocated" by h264bsdAllocateDpbImage). If it is -> exchange data
         * pointer with the one stored in the beginning */
        if (dpb->numOut)
        {
            u32 i;

            for (i = 0; i < dpb->numOut; i++)
            {
                if (dpb->outBuf[i].data == dpb->buffer[dpb->dpbSize].data)
                {
                    /* find buffer position containing data pointer stored in
                     * tmp */
                    for (i = 0; i < dpb->dpbSize; i++)
                    {
                        if (dpb->buffer[i].data == tmp)
                        {
                            dpb->buffer[i].data =
                                dpb->buffer[dpb->dpbSize].data;
                            dpb->buffer[dpb->dpbSize].data = tmp;
                            break;
                        }
                    }
                    ASSERT(i < dpb->dpbSize);
                    break;
                }
            }
        }
    }
    /* frameNum for reference pictures shall not be the same as for previous
     * reference picture, otherwise accesses to pictures in the buffer cannot
     * be solved unambiguously */
    else if (isRefPic && frameNum == dpb->prevRefFrameNum)
    {
        return(HANTRO_NOK);
    }

    /* save current frame_num in prevRefFrameNum. For non-reference frame
     * prevFrameNum is set to frame number of last non-existing frame above */
    if (isRefPic)
        dpb->prevRefFrameNum = frameNum;
    else if (frameNum != dpb->prevRefFrameNum)
    {
        dpb->prevRefFrameNum =
            (frameNum + dpb->maxFrameNum - 1) % dpb->maxFrameNum;
    }

    return(HANTRO_OK);

}

/*------------------------------------------------------------------------------

    Function: FindSmallestPicOrderCnt

        Functional description:
            Function to find picture with smallest picture order count. This
            will be the next picture in display order.

        Returns:
            pointer to the picture, NULL if no pictures to be displayed

------------------------------------------------------------------------------*/

dpbPicture_t* FindSmallestPicOrderCnt(dpbStorage_t *dpb)
{

/* Variables */

    u32 i;
    i32 picOrderCnt;
    dpbPicture_t *tmp;

/* Code */

    ASSERT(dpb);

    picOrderCnt = 0x7FFFFFFF;
    tmp = NULL;

    for (i = 0; i <= dpb->dpbSize; i++)
    {
        if (dpb->buffer[i].toBeDisplayed &&
            (dpb->buffer[i].picOrderCnt < picOrderCnt))
        {
            tmp = dpb->buffer + i;
            picOrderCnt = dpb->buffer[i].picOrderCnt;
        }
    }

    return(tmp);

}

/*------------------------------------------------------------------------------

    Function: OutputPicture

        Functional description:
            Function to put next display order picture into the output buffer.

        Returns:
            HANTRO_OK      success
            HANTRO_NOK     no pictures to display

------------------------------------------------------------------------------*/

u32 OutputPicture(dpbStorage_t *dpb)
{

/* Variables */

    dpbPicture_t *tmp;

/* Code */

    ASSERT(dpb);

    if (dpb->noReordering)
        return(HANTRO_NOK);

    tmp = FindSmallestPicOrderCnt(dpb);

    /* no pictures to be displayed */
    if (tmp == NULL)
        return(HANTRO_NOK);

    dpb->outBuf[dpb->numOut].data  = tmp->data;
    dpb->outBuf[dpb->numOut].isIdr = tmp->isIdr;
    dpb->outBuf[dpb->numOut].picId = tmp->picId;
    dpb->outBuf[dpb->numOut].numErrMbs = tmp->numErrMbs;
    dpb->numOut++;

    tmp->toBeDisplayed = HANTRO_FALSE;
    if (!IS_REFERENCE(*tmp))
    {
        dpb->fullness--;
    }

    return(HANTRO_OK);

}

/*------------------------------------------------------------------------------

    Function: h264bsdDpbOutputPicture

        Functional description:
            Function to get next display order picture from the output buffer.

        Return:
            pointer to output picture structure, NULL if no pictures to
            display

------------------------------------------------------------------------------*/

dpbOutPicture_t* h264bsdDpbOutputPicture(dpbStorage_t *dpb)
{

/* Variables */

/* Code */

    ASSERT(dpb);

    if (dpb->outIndex < dpb->numOut)
        return(dpb->outBuf + dpb->outIndex++);
    else
        return(NULL);

}

/*------------------------------------------------------------------------------

    Function: h264bsdFlushDpb

        Functional description:
            Function to flush the DPB. Function puts all pictures needed for
            display into the output buffer. This function shall be called in
            the end of the stream to obtain pictures buffered for display
            re-ordering purposes.

------------------------------------------------------------------------------*/

void h264bsdFlushDpb(dpbStorage_t *dpb)
{

    /* don't do anything if buffer not reserved */
    if (dpb->buffer)
    {
        dpb->flushed = 1;
        /* output all pictures */
        while (OutputPicture(dpb) == HANTRO_OK)
            ;
    }

}

/*------------------------------------------------------------------------------

    Function: h264bsdFreeDpb

        Functional description:
            Function to free memories reserved for the DPB.

------------------------------------------------------------------------------*/

void h264bsdFreeDpb(dpbStorage_t *dpb)
{

/* Variables */

    u32 i;

/* Code */

    ASSERT(dpb);

    if (dpb->buffer)
    {
        for (i = 0; i < dpb->dpbSize+1; i++)
        {
            FREE(dpb->buffer[i].pAllocatedData);
        }
    }
    FREE(dpb->buffer);
    FREE(dpb->list);
    FREE(dpb->outBuf);

}

/*------------------------------------------------------------------------------

    Function: ShellSort

        Functional description:
            Sort pictures in the buffer. Function implements Shell's method,
            i.e. diminishing increment sort. See e.g. "Numerical Recipes in C"
            for more information.

------------------------------------------------------------------------------*/

static void ShellSort(dpbPicture_t *pPic, u32 num)
{

    u32 i, j;
    u32 step;
    dpbPicture_t tmpPic;

    step = 7;

    while (step)
    {
        for (i = step; i < num; i++)
        {
            tmpPic = pPic[i];
            j = i;
            while (j >= step && ComparePictures(pPic + j - step, &tmpPic) > 0)
            {
                pPic[j] = pPic[j-step];
                j -= step;
            }
            pPic[j] = tmpPic;
        }
        step >>= 1;
    }

}

