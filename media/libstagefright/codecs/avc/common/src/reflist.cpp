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
#include "avclib_common.h"

/** see subclause 8.2.4 Decoding process for reference picture lists construction. */
OSCL_EXPORT_REF void RefListInit(AVCCommonObj *video)
{
    AVCSliceHeader *sliceHdr = video->sliceHdr;
    AVCDecPicBuffer *dpb = video->decPicBuf;
    int slice_type = video->slice_type;
    int i, list0idx;

    AVCPictureData *tmp_s;

    list0idx = 0;

    if (slice_type == AVC_I_SLICE)
    {
        video->refList0Size = 0;
        video->refList1Size = 0;

        /* we still have to calculate FrameNumWrap to make sure that all I-slice clip
        can perform sliding_window_operation properly. */

        for (i = 0; i < dpb->num_fs; i++)
        {
            if ((dpb->fs[i]->IsReference == 3) && (!dpb->fs[i]->IsLongTerm))
            {
                /* subclause 8.2.4.1 Decoding process for picture numbers. */
                if (dpb->fs[i]->FrameNum > (int)sliceHdr->frame_num)
                {
                    dpb->fs[i]->FrameNumWrap = dpb->fs[i]->FrameNum - video->MaxFrameNum;
                }
                else
                {
                    dpb->fs[i]->FrameNumWrap = dpb->fs[i]->FrameNum;
                }
                dpb->fs[i]->frame.PicNum = dpb->fs[i]->FrameNumWrap;
            }
        }


        return ;
    }
    if (slice_type == AVC_P_SLICE)
    {
        /* Calculate FrameNumWrap and PicNum */

        for (i = 0; i < dpb->num_fs; i++)
        {
            if ((dpb->fs[i]->IsReference == 3) && (!dpb->fs[i]->IsLongTerm))
            {
                /* subclause 8.2.4.1 Decoding process for picture numbers. */
                if (dpb->fs[i]->FrameNum > (int)sliceHdr->frame_num)
                {
                    dpb->fs[i]->FrameNumWrap = dpb->fs[i]->FrameNum - video->MaxFrameNum;
                }
                else
                {
                    dpb->fs[i]->FrameNumWrap = dpb->fs[i]->FrameNum;
                }
                dpb->fs[i]->frame.PicNum = dpb->fs[i]->FrameNumWrap;
                video->RefPicList0[list0idx++] = &(dpb->fs[i]->frame);
            }
        }

        if (list0idx == 0)
        {
            dpb->fs[0]->IsReference = 3;
            video->RefPicList0[0] = &(dpb->fs[0]->frame);
            list0idx = 1;
        }
        /* order list 0 by PicNum from max to min, see subclause 8.2.4.2.1 */
        SortPicByPicNum(video->RefPicList0, list0idx);
        video->refList0Size = list0idx;

        /* long term handling */
        for (i = 0; i < dpb->num_fs; i++)
        {
            if (dpb->fs[i]->IsLongTerm == 3)
            {
                /* subclause 8.2.4.1 Decoding process for picture numbers. */
                dpb->fs[i]->frame.LongTermPicNum = dpb->fs[i]->LongTermFrameIdx;
                video->RefPicList0[list0idx++] = &(dpb->fs[i]->frame);
            }
        }

        /* order PicNum from min to max, see subclause 8.2.4.2.1  */
        SortPicByPicNumLongTerm(&(video->RefPicList0[video->refList0Size]), list0idx - video->refList0Size);
        video->refList0Size = list0idx;


        video->refList1Size = 0;
    }


    if ((video->refList0Size == video->refList1Size) && (video->refList0Size > 1))
    {
        /* check if lists are identical, if yes swap first two elements of listX[1] */
        /* last paragraph of subclause 8.2.4.2.4 */

        for (i = 0; i < video->refList0Size; i++)
        {
            if (video->RefPicList0[i] != video->RefPicList1[i])
            {
                break;
            }
        }
        if (i == video->refList0Size)
        {
            tmp_s = video->RefPicList1[0];
            video->RefPicList1[0] = video->RefPicList1[1];
            video->RefPicList1[1] = tmp_s;
        }
    }

    /* set max size */
    video->refList0Size = AVC_MIN(video->refList0Size, (int)video->sliceHdr->num_ref_idx_l0_active_minus1 + 1);
    video->refList1Size = AVC_MIN(video->refList1Size, (int)video->sliceHdr->num_ref_idx_l1_active_minus1 + 1);

    return ;
}
/* see subclause 8.2.4.3 */
OSCL_EXPORT_REF AVCStatus ReOrderList(AVCCommonObj *video)
{
    AVCSliceHeader *sliceHdr = video->sliceHdr;
    AVCStatus status = AVC_SUCCESS;
    int slice_type = video->slice_type;

    if (slice_type != AVC_I_SLICE)
    {
        if (sliceHdr->ref_pic_list_reordering_flag_l0)
        {
            status = ReorderRefPicList(video, 0);
            if (status != AVC_SUCCESS)
                return status;
        }
        if (video->refList0Size == 0)
        {
            return AVC_FAIL;
        }
    }
    return status;
}

AVCStatus ReorderRefPicList(AVCCommonObj *video, int isL1)
{
    AVCSliceHeader *sliceHdr = video->sliceHdr;
    AVCStatus status;

    int *list_size;
    int num_ref_idx_lX_active_minus1;
    uint *remapping_of_pic_nums_idc;
    int *abs_diff_pic_num_minus1;
    int *long_term_pic_idx;
    int i;
    int maxPicNum, currPicNum, picNumLXNoWrap, picNumLXPred, picNumLX;
    int refIdxLX = 0;
    void* tmp;

    if (!isL1) /* list 0 */
    {
        list_size = &(video->refList0Size);
        num_ref_idx_lX_active_minus1 = sliceHdr->num_ref_idx_l0_active_minus1;
        remapping_of_pic_nums_idc = sliceHdr->reordering_of_pic_nums_idc_l0;
        tmp = (void*)sliceHdr->abs_diff_pic_num_minus1_l0;
        abs_diff_pic_num_minus1 = (int*) tmp;
        tmp = (void*)sliceHdr->long_term_pic_num_l0;
        long_term_pic_idx = (int*) tmp;
    }
    else
    {
        list_size = &(video->refList1Size);
        num_ref_idx_lX_active_minus1 = sliceHdr->num_ref_idx_l1_active_minus1;
        remapping_of_pic_nums_idc = sliceHdr->reordering_of_pic_nums_idc_l1;
        tmp = (void*) sliceHdr->abs_diff_pic_num_minus1_l1;
        abs_diff_pic_num_minus1 = (int*) tmp;
        tmp = (void*) sliceHdr->long_term_pic_num_l1;
        long_term_pic_idx = (int*)tmp;
    }

    maxPicNum = video->MaxPicNum;
    currPicNum = video->CurrPicNum;

    picNumLXPred = currPicNum; /* initial value */

    for (i = 0; remapping_of_pic_nums_idc[i] != 3; i++)
    {
        if ((remapping_of_pic_nums_idc[i] > 3) || (i >= MAX_REF_PIC_LIST_REORDERING))
        {
            return AVC_FAIL; /* out of range */
        }
        /* see subclause 8.2.4.3.1 */
        if (remapping_of_pic_nums_idc[i] < 2)
        {
            if (remapping_of_pic_nums_idc[i] == 0)
            {
                if (picNumLXPred - (abs_diff_pic_num_minus1[i] + 1) < 0)
                    picNumLXNoWrap = picNumLXPred - (abs_diff_pic_num_minus1[i] + 1) + maxPicNum;
                else
                    picNumLXNoWrap = picNumLXPred - (abs_diff_pic_num_minus1[i] + 1);
            }
            else /* (remapping_of_pic_nums_idc[i] == 1) */
            {
                if (picNumLXPred + (abs_diff_pic_num_minus1[i] + 1)  >=  maxPicNum)
                    picNumLXNoWrap = picNumLXPred + (abs_diff_pic_num_minus1[i] + 1) - maxPicNum;
                else
                    picNumLXNoWrap = picNumLXPred + (abs_diff_pic_num_minus1[i] + 1);
            }
            picNumLXPred = picNumLXNoWrap; /* prediction for the next one */

            if (picNumLXNoWrap > currPicNum)
                picNumLX = picNumLXNoWrap - maxPicNum;
            else
                picNumLX = picNumLXNoWrap;

            status = ReorderShortTerm(video, picNumLX, &refIdxLX, isL1);
            if (status != AVC_SUCCESS)
            {
                return status;
            }
        }
        else /* (remapping_of_pic_nums_idc[i] == 2), subclause 8.2.4.3.2 */
        {
            status = ReorderLongTerm(video, long_term_pic_idx[i], &refIdxLX, isL1);
            if (status != AVC_SUCCESS)
            {
                return status;
            }
        }
    }
    /* that's a definition */
    *list_size = num_ref_idx_lX_active_minus1 + 1;

    return AVC_SUCCESS;
}

/* see subclause 8.2.4.3.1 */
AVCStatus ReorderShortTerm(AVCCommonObj *video, int picNumLX, int *refIdxLX, int isL1)
{
    int cIdx, nIdx;
    int num_ref_idx_lX_active_minus1;
    AVCPictureData *picLX, **RefPicListX;

    if (!isL1) /* list 0 */
    {
        RefPicListX = video->RefPicList0;
        num_ref_idx_lX_active_minus1 = video->sliceHdr->num_ref_idx_l0_active_minus1;
    }
    else
    {
        RefPicListX = video->RefPicList1;
        num_ref_idx_lX_active_minus1 = video->sliceHdr->num_ref_idx_l1_active_minus1;
    }

    picLX = GetShortTermPic(video, picNumLX);

    if (picLX == NULL)
    {
        return AVC_FAIL;
    }
    /* Note RefPicListX has to access element number num_ref_idx_lX_active */
    /* There could be access violation here. */
    if (num_ref_idx_lX_active_minus1 + 1 >= MAX_REF_PIC_LIST)
    {
        return AVC_FAIL;
    }

    for (cIdx = num_ref_idx_lX_active_minus1 + 1; cIdx > *refIdxLX; cIdx--)
    {
        RefPicListX[ cIdx ] = RefPicListX[ cIdx - 1];
    }

    RefPicListX[(*refIdxLX)++ ] = picLX;

    nIdx = *refIdxLX;

    for (cIdx = *refIdxLX; cIdx <= num_ref_idx_lX_active_minus1 + 1; cIdx++)
    {
        if (RefPicListX[ cIdx ])
        {
            if ((RefPicListX[ cIdx ]->isLongTerm) || ((int)RefPicListX[ cIdx ]->PicNum != picNumLX))
            {
                RefPicListX[ nIdx++ ] = RefPicListX[ cIdx ];
            }
        }
    }
    return AVC_SUCCESS;
}

/* see subclause 8.2.4.3.2 */
AVCStatus ReorderLongTerm(AVCCommonObj *video, int LongTermPicNum, int *refIdxLX, int isL1)
{
    AVCPictureData **RefPicListX;
    int num_ref_idx_lX_active_minus1;
    int cIdx, nIdx;
    AVCPictureData *picLX;

    if (!isL1) /* list 0 */
    {
        RefPicListX = video->RefPicList0;
        num_ref_idx_lX_active_minus1 = video->sliceHdr->num_ref_idx_l0_active_minus1;
    }
    else
    {
        RefPicListX = video->RefPicList1;
        num_ref_idx_lX_active_minus1 = video->sliceHdr->num_ref_idx_l1_active_minus1;
    }

    picLX = GetLongTermPic(video, LongTermPicNum);
    if (picLX == NULL)
    {
        return AVC_FAIL;
    }
    /* Note RefPicListX has to access element number num_ref_idx_lX_active */
    /* There could be access violation here. */
    if (num_ref_idx_lX_active_minus1 + 1 >= MAX_REF_PIC_LIST)
    {
        return AVC_FAIL;
    }
    for (cIdx = num_ref_idx_lX_active_minus1 + 1; cIdx > *refIdxLX; cIdx--)
        RefPicListX[ cIdx ] = RefPicListX[ cIdx - 1];

    RefPicListX[(*refIdxLX)++ ] = picLX;

    nIdx = *refIdxLX;

    for (cIdx = *refIdxLX; cIdx <= num_ref_idx_lX_active_minus1 + 1; cIdx++)
    {
        if ((!RefPicListX[ cIdx ]->isLongTerm) || ((int)RefPicListX[ cIdx ]->LongTermPicNum != LongTermPicNum))
        {
            RefPicListX[ nIdx++ ] = RefPicListX[ cIdx ];
        }
    }
    return AVC_SUCCESS;
}


AVCPictureData*  GetShortTermPic(AVCCommonObj *video, int picNum)
{
    int i;
    AVCDecPicBuffer *dpb = video->decPicBuf;

    for (i = 0; i < dpb->num_fs; i++)
    {

        if (dpb->fs[i]->IsReference == 3)
        {
            if ((dpb->fs[i]->frame.isLongTerm == FALSE) && (dpb->fs[i]->frame.PicNum == picNum))
            {
                return &(dpb->fs[i]->frame);
            }
        }

    }

    return NULL;
}

AVCPictureData*  GetLongTermPic(AVCCommonObj *video, int LongtermPicNum)
{
    AVCDecPicBuffer *dpb = video->decPicBuf;
    int i;

    for (i = 0; i < dpb->num_fs; i++)
    {

        if (dpb->fs[i]->IsReference == 3)
        {
            if ((dpb->fs[i]->frame.isLongTerm == TRUE) && (dpb->fs[i]->frame.LongTermPicNum == LongtermPicNum))
            {
                return &(dpb->fs[i]->frame);
            }
        }

    }
    return NULL;
}

int is_short_ref(AVCPictureData *s)
{
    return ((s->isReference) && !(s->isLongTerm));
}

int is_long_ref(AVCPictureData *s)
{
    return ((s->isReference) && (s->isLongTerm));
}


/* sort by PicNum, descending order */
void SortPicByPicNum(AVCPictureData *data[], int num)
{
    int i, j;
    AVCPictureData *temp;

    for (i = 0; i < num - 1; i++)
    {
        for (j = i + 1; j < num; j++)
        {
            if (data[j]->PicNum > data[i]->PicNum)
            {
                temp = data[j];
                data[j] = data[i];
                data[i] = temp;
            }
        }
    }

    return ;
}

/* sort by PicNum, ascending order */
void SortPicByPicNumLongTerm(AVCPictureData *data[], int num)
{
    int i, j;
    AVCPictureData *temp;

    for (i = 0; i < num - 1; i++)
    {
        for (j = i + 1; j < num; j++)
        {
            if (data[j]->LongTermPicNum < data[i]->LongTermPicNum)
            {
                temp = data[j];
                data[j] = data[i];
                data[i] = temp;
            }
        }
    }

    return ;
}


/* sort by FrameNumWrap, descending order */
void SortFrameByFrameNumWrap(AVCFrameStore *data[], int num)
{
    int i, j;
    AVCFrameStore *temp;

    for (i = 0; i < num - 1; i++)
    {
        for (j = i + 1; j < num; j++)
        {
            if (data[j]->FrameNumWrap > data[i]->FrameNumWrap)
            {
                temp = data[j];
                data[j] = data[i];
                data[i] = temp;
            }
        }
    }

    return ;
}

/* sort frames by LongTermFrameIdx, ascending order */
void SortFrameByLTFrameIdx(AVCFrameStore *data[], int num)
{
    int i, j;
    AVCFrameStore *temp;

    for (i = 0; i < num - 1; i++)
    {
        for (j = i + 1; j < num; j++)
        {
            if (data[j]->LongTermFrameIdx < data[i]->LongTermFrameIdx)
            {
                temp = data[j];
                data[j] = data[i];
                data[i] = temp;
            }
        }
    }

    return ;
}

/* sort PictureData by POC in descending order */
void SortPicByPOC(AVCPictureData *data[], int num, int descending)
{
    int i, j;
    AVCPictureData *temp;

    if (descending)
    {
        for (i = 0; i < num - 1; i++)
        {
            for (j = i + 1; j < num; j++)
            {
                if (data[j]->PicOrderCnt > data[i]->PicOrderCnt)
                {
                    temp = data[j];
                    data[j] = data[i];
                    data[i] = temp;
                }
            }
        }
    }
    else
    {
        for (i = 0; i < num - 1; i++)
        {
            for (j = i + 1; j < num; j++)
            {
                if (data[j]->PicOrderCnt < data[i]->PicOrderCnt)
                {
                    temp = data[j];
                    data[j] = data[i];
                    data[i] = temp;
                }
            }
        }
    }
    return ;
}

/* sort PictureData by LongTermPicNum in ascending order */
void SortPicByLTPicNum(AVCPictureData *data[], int num)
{
    int i, j;
    AVCPictureData *temp;

    for (i = 0; i < num - 1; i++)
    {
        for (j = i + 1; j < num; j++)
        {
            if (data[j]->LongTermPicNum < data[i]->LongTermPicNum)
            {
                temp = data[j];
                data[j] = data[i];
                data[i] = temp;
            }
        }
    }

    return ;
}

/* sort by PicOrderCnt, descending order */
void SortFrameByPOC(AVCFrameStore *data[], int num, int descending)
{
    int i, j;
    AVCFrameStore *temp;

    if (descending)
    {
        for (i = 0; i < num - 1; i++)
        {
            for (j = i + 1; j < num; j++)
            {
                if (data[j]->PicOrderCnt > data[i]->PicOrderCnt)
                {
                    temp = data[j];
                    data[j] = data[i];
                    data[i] = temp;
                }
            }
        }
    }
    else
    {
        for (i = 0; i < num - 1; i++)
        {
            for (j = i + 1; j < num; j++)
            {
                if (data[j]->PicOrderCnt < data[i]->PicOrderCnt)
                {
                    temp = data[j];
                    data[j] = data[i];
                    data[i] = temp;
                }
            }
        }
    }

    return ;
}


