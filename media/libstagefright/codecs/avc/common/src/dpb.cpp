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

#define DPB_MEM_ATTR 0

AVCStatus InitDPB(AVCHandle *avcHandle, AVCCommonObj *video, int FrameHeightInMbs, int PicWidthInMbs, bool padding)
{
    AVCDecPicBuffer *dpb = video->decPicBuf;
    int level, framesize, num_fs;
    void *userData = avcHandle->userData;
#ifndef PV_MEMORY_POOL
    uint32 addr;
#endif
    uint16 refIdx = 0;
    level = video->currSeqParams->level_idc;

    for (num_fs = 0; num_fs < MAX_FS; num_fs++)
    {
        dpb->fs[num_fs] = NULL;
    }

    framesize = (int)(((FrameHeightInMbs * PicWidthInMbs) << 7) * 3);
    if (padding)
    {
        video->padded_size = (int)((((FrameHeightInMbs + 2) * (PicWidthInMbs + 2)) << 7) * 3) - framesize;
    }
    else
    {
        video->padded_size = 0;
    }

#ifndef PV_MEMORY_POOL
    if (dpb->decoded_picture_buffer)
    {
        avcHandle->CBAVC_Free(userData, (int)dpb->decoded_picture_buffer);
        dpb->decoded_picture_buffer = NULL;
    }
#endif
    /* need to allocate one extra frame for current frame, DPB only defines for reference frames */

    dpb->num_fs = (uint32)(MaxDPBX2[mapLev2Idx[level]] << 2) / (3 * FrameHeightInMbs * PicWidthInMbs) + 1;
    if (dpb->num_fs > MAX_FS)
    {
        dpb->num_fs = MAX_FS;
    }

    if (video->currSeqParams->num_ref_frames + 1 > (uint32)dpb->num_fs)
    {
        dpb->num_fs = video->currSeqParams->num_ref_frames + 1;
    }

    dpb->dpb_size = dpb->num_fs * (framesize + video->padded_size);
//  dpb->dpb_size = (uint32)MaxDPBX2[mapLev2Idx[level]]*512 + framesize;

#ifndef PV_MEMORY_POOL
    dpb->decoded_picture_buffer = (uint8*) avcHandle->CBAVC_Malloc(userData, dpb->dpb_size, 100/*DPB_MEM_ATTR*/);

    if (dpb->decoded_picture_buffer == NULL || dpb->decoded_picture_buffer&0x3) // not word aligned
        return AVC_MEMORY_FAIL;
#endif
    dpb->used_size = 0;
    num_fs = 0;

    while (num_fs < dpb->num_fs)
    {
        /*  fs is an array pointers to AVCDecPicture */
        dpb->fs[num_fs] = (AVCFrameStore*) avcHandle->CBAVC_Malloc(userData, sizeof(AVCFrameStore), 101/*DEFAULT_ATTR*/);
        if (dpb->fs[num_fs] == NULL)
        {
            return AVC_MEMORY_FAIL;
        }
#ifndef PV_MEMORY_POOL
        /* assign the actual memory for Sl, Scb, Scr */
        dpb->fs[num_fs]->base_dpb = dpb->decoded_picture_buffer + dpb->used_size;
#endif
        dpb->fs[num_fs]->IsReference = 0;
        dpb->fs[num_fs]->IsLongTerm = 0;
        dpb->fs[num_fs]->IsOutputted = 3;
        dpb->fs[num_fs]->frame.RefIdx = refIdx++; /* this value will remain unchanged through out the encoding session */
        dpb->fs[num_fs]->frame.picType = AVC_FRAME;
        dpb->fs[num_fs]->frame.isLongTerm = 0;
        dpb->fs[num_fs]->frame.isReference = 0;
        video->RefPicList0[num_fs] = &(dpb->fs[num_fs]->frame);
        dpb->fs[num_fs]->frame.padded = 0;
        dpb->used_size += (framesize + video->padded_size);
        num_fs++;
    }

    return AVC_SUCCESS;
}

OSCL_EXPORT_REF AVCStatus AVCConfigureSequence(AVCHandle *avcHandle, AVCCommonObj *video, bool padding)
{
    void *userData = avcHandle->userData;
    AVCDecPicBuffer *dpb = video->decPicBuf;
    int framesize, ii; /* size of one frame */
    uint PicWidthInMbs, PicHeightInMapUnits, FrameHeightInMbs, PicSizeInMapUnits;
    uint num_fs;
    /* derived variables from SPS */
    PicWidthInMbs = video->currSeqParams->pic_width_in_mbs_minus1 + 1;
    PicHeightInMapUnits = video->currSeqParams->pic_height_in_map_units_minus1 + 1 ;
    FrameHeightInMbs = (2 - video->currSeqParams->frame_mbs_only_flag) * PicHeightInMapUnits ;
    PicSizeInMapUnits = PicWidthInMbs * PicHeightInMapUnits ;

    if (video->PicSizeInMapUnits != PicSizeInMapUnits || video->currSeqParams->level_idc != video->level_idc)
    {
        /* make sure you mark all the frames as unused for reference for flushing*/
        for (ii = 0; ii < dpb->num_fs; ii++)
        {
            dpb->fs[ii]->IsReference = 0;
            dpb->fs[ii]->IsOutputted |= 0x02;
        }

        num_fs = (uint32)(MaxDPBX2[(uint32)mapLev2Idx[video->currSeqParams->level_idc]] << 2) / (3 * PicSizeInMapUnits) + 1;
        if (num_fs >= MAX_FS)
        {
            num_fs = MAX_FS;
        }
#ifdef PV_MEMORY_POOL
        if (padding)
        {
            avcHandle->CBAVC_DPBAlloc(avcHandle->userData,
                                      PicSizeInMapUnits + ((PicWidthInMbs + 2) << 1) + (PicHeightInMapUnits << 1), num_fs);
        }
        else
        {
            avcHandle->CBAVC_DPBAlloc(avcHandle->userData, PicSizeInMapUnits, num_fs);
        }
#endif
        CleanUpDPB(avcHandle, video);
        if (InitDPB(avcHandle, video, FrameHeightInMbs, PicWidthInMbs, padding) != AVC_SUCCESS)
        {
            return AVC_FAIL;
        }
        /*  Allocate video->mblock upto PicSizeInMbs and populate the structure  such as the neighboring MB pointers.   */
        framesize = (FrameHeightInMbs * PicWidthInMbs);
        if (video->mblock)
        {
            avcHandle->CBAVC_Free(userData, (uint32)video->mblock);
            video->mblock = NULL;
        }
        video->mblock = (AVCMacroblock*) avcHandle->CBAVC_Malloc(userData, sizeof(AVCMacroblock) * framesize, DEFAULT_ATTR);
        if (video->mblock == NULL)
        {
            return AVC_FAIL;
        }
        for (ii = 0; ii < framesize; ii++)
        {
            video->mblock[ii].slice_id = -1;
        }
        /* Allocate memory for intra prediction */
#ifdef MB_BASED_DEBLOCK
        video->intra_pred_top = (uint8*) avcHandle->CBAVC_Malloc(userData, PicWidthInMbs << 4, FAST_MEM_ATTR);
        if (video->intra_pred_top == NULL)
        {
            return AVC_FAIL;
        }
        video->intra_pred_top_cb = (uint8*) avcHandle->CBAVC_Malloc(userData, PicWidthInMbs << 3, FAST_MEM_ATTR);
        if (video->intra_pred_top_cb == NULL)
        {
            return AVC_FAIL;
        }
        video->intra_pred_top_cr = (uint8*) avcHandle->CBAVC_Malloc(userData, PicWidthInMbs << 3, FAST_MEM_ATTR);
        if (video->intra_pred_top_cr == NULL)
        {
            return AVC_FAIL;
        }

#endif
        /*  Allocate slice group MAP map */

        if (video->MbToSliceGroupMap)
        {
            avcHandle->CBAVC_Free(userData, (uint32)video->MbToSliceGroupMap);
            video->MbToSliceGroupMap = NULL;
        }
        video->MbToSliceGroupMap = (int*) avcHandle->CBAVC_Malloc(userData, sizeof(uint) * PicSizeInMapUnits * 2, 7/*DEFAULT_ATTR*/);
        if (video->MbToSliceGroupMap == NULL)
        {
            return AVC_FAIL;
        }
        video->PicSizeInMapUnits = PicSizeInMapUnits;
        video->level_idc = video->currSeqParams->level_idc;

    }
    return AVC_SUCCESS;
}

OSCL_EXPORT_REF AVCStatus CleanUpDPB(AVCHandle *avcHandle, AVCCommonObj *video)
{
    AVCDecPicBuffer *dpb = video->decPicBuf;
    int ii;
    void *userData = avcHandle->userData;

    for (ii = 0; ii < MAX_FS; ii++)
    {
        if (dpb->fs[ii] != NULL)
        {
            avcHandle->CBAVC_Free(userData, (int)dpb->fs[ii]);
            dpb->fs[ii] = NULL;
        }
    }
#ifndef PV_MEMORY_POOL
    if (dpb->decoded_picture_buffer)
    {
        avcHandle->CBAVC_Free(userData, (int)dpb->decoded_picture_buffer);
        dpb->decoded_picture_buffer = NULL;
    }
#endif
    dpb->used_size = 0;
    dpb->dpb_size = 0;

    return AVC_SUCCESS;
}

OSCL_EXPORT_REF AVCStatus DPBInitBuffer(AVCHandle *avcHandle, AVCCommonObj *video)
{
    AVCDecPicBuffer *dpb = video->decPicBuf;
    int ii, status;

    /* Before doing any decoding, check if there's a frame memory available */
    /* look for next unused dpb->fs, or complementary field pair */
    /* video->currPic is assigned to this */

    /* There's also restriction on the frame_num, see page 59 of JVT-I1010.doc. */

    for (ii = 0; ii < dpb->num_fs; ii++)
    {
        /* looking for the one not used or not reference and has been outputted */
        if (dpb->fs[ii]->IsReference == 0 && dpb->fs[ii]->IsOutputted == 3)
        {
            video->currFS = dpb->fs[ii];
#ifdef PV_MEMORY_POOL
            status = avcHandle->CBAVC_FrameBind(avcHandle->userData, ii, &(video->currFS->base_dpb));
            if (status == AVC_FAIL)
            {
                return AVC_NO_BUFFER; /* this should not happen */
            }
#endif
            break;
        }
    }
    if (ii == dpb->num_fs)
    {
        return AVC_PICTURE_OUTPUT_READY; /* no empty frame available */
    }
    return AVC_SUCCESS;
}

OSCL_EXPORT_REF void DPBInitPic(AVCCommonObj *video, int CurrPicNum)
{
    int offset = 0;
    int offsetc = 0;
    int luma_framesize;
    /* this part has to be set here, assuming that slice header and POC have been decoded. */
    /* used in GetOutput API */
    video->currFS->PicOrderCnt = video->PicOrderCnt;
    video->currFS->FrameNum = video->sliceHdr->frame_num;
    video->currFS->FrameNumWrap = CurrPicNum;    // MC_FIX
    /* initialize everything to zero */
    video->currFS->IsOutputted = 0;
    video->currFS->IsReference = 0;
    video->currFS->IsLongTerm = 0;
    video->currFS->frame.isReference = FALSE;
    video->currFS->frame.isLongTerm = FALSE;

    /* initialize the pixel pointer to NULL */
    video->currFS->frame.Sl = video->currFS->frame.Scb = video->currFS->frame.Scr = NULL;

    /* determine video->currPic */
    /* assign dbp->base_dpb to fs[i]->frame.Sl, Scb, Scr .*/
    /* For PicSizeInMbs, see DecodeSliceHeader() */

    video->currPic = &(video->currFS->frame);

    video->currPic->padded = 0; // reset this flag to not-padded

    if (video->padded_size)
    {
        offset = ((video->PicWidthInSamplesL + 32) << 4) + 16; // offset to the origin
        offsetc = (offset >> 2) + 4;
        luma_framesize = (int)((((video->FrameHeightInMbs + 2) * (video->PicWidthInMbs + 2)) << 8));
    }
    else
        luma_framesize = video->PicSizeInMbs << 8;


    video->currPic->Sl = video->currFS->base_dpb + offset;
    video->currPic->Scb = video->currFS->base_dpb  + luma_framesize + offsetc;
    video->currPic->Scr = video->currPic->Scb + (luma_framesize >> 2);
    video->currPic->pitch = video->PicWidthInSamplesL + (video->padded_size == 0 ? 0 : 32);


    video->currPic->height = video->PicHeightInSamplesL;
    video->currPic->width = video->PicWidthInSamplesL;
    video->currPic->PicNum = CurrPicNum;
}

/* to release skipped frame after encoding */
OSCL_EXPORT_REF void DPBReleaseCurrentFrame(AVCHandle *avcHandle, AVCCommonObj *video)
{
    AVCDecPicBuffer *dpb = video->decPicBuf;
    int ii;

    video->currFS->IsOutputted = 3; // return this buffer.

#ifdef PV_MEMORY_POOL /* for non-memory pool, no need to do anything */

    /* search for current frame index */
    ii = dpb->num_fs;
    while (ii--)
    {
        if (dpb->fs[ii] == video->currFS)
        {
            avcHandle->CBAVC_FrameUnbind(avcHandle->userData, ii);
            break;
        }
    }
#endif

    return ;
}

/* see subclause 8.2.5.1 */
OSCL_EXPORT_REF AVCStatus StorePictureInDPB(AVCHandle *avcHandle, AVCCommonObj *video)
{
    AVCStatus status;
    AVCDecPicBuffer *dpb = video->decPicBuf;
    AVCSliceHeader *sliceHdr = video->sliceHdr;
    int ii, num_ref;

    /* number 1 of 8.2.5.1, we handle gaps in frame_num differently without using the memory */
    /* to be done!!!! */

    /* number 3 of 8.2.5.1 */
    if (video->nal_unit_type == AVC_NALTYPE_IDR)
    {
        for (ii = 0; ii < dpb->num_fs; ii++)
        {
            if (dpb->fs[ii] != video->currFS) /* not current frame */
            {
                dpb->fs[ii]->IsReference = 0; /* mark as unused for reference */
                dpb->fs[ii]->IsLongTerm = 0;  /* but still used until output */
                dpb->fs[ii]->IsOutputted |= 0x02;
#ifdef PV_MEMORY_POOL
                if (dpb->fs[ii]->IsOutputted == 3)
                {
                    avcHandle->CBAVC_FrameUnbind(avcHandle->userData, ii);
                }
#endif
            }
        }

        video->currPic->isReference = TRUE;
        video->currFS->IsReference = 3;

        if (sliceHdr->long_term_reference_flag == 0)
        {
            video->currPic->isLongTerm = FALSE;
            video->currFS->IsLongTerm = 0;
            video->MaxLongTermFrameIdx = -1;
        }
        else
        {
            video->currPic->isLongTerm = TRUE;
            video->currFS->IsLongTerm = 3;
            video->currFS->LongTermFrameIdx = 0;
            video->MaxLongTermFrameIdx = 0;
        }
        if (sliceHdr->no_output_of_prior_pics_flag)
        {
            for (ii = 0; ii < dpb->num_fs; ii++)
            {
                if (dpb->fs[ii] != video->currFS) /* not current frame */
                {
                    dpb->fs[ii]->IsOutputted = 3;
#ifdef PV_MEMORY_POOL
                    avcHandle->CBAVC_FrameUnbind(avcHandle->userData, ii);
#endif
                }
            }
        }
        video->mem_mgr_ctrl_eq_5 = TRUE;    /* flush reference frames MC_FIX */
    }
    else
    {
        if (video->currPic->isReference == TRUE)
        {
            if (sliceHdr->adaptive_ref_pic_marking_mode_flag == 0)
            {
                status = sliding_window_process(avcHandle, video, dpb); /* we may have to do this after adaptive_memory_marking */
            }
            else
            {
                status = adaptive_memory_marking(avcHandle, video, dpb, sliceHdr);
            }
            if (status != AVC_SUCCESS)
            {
                return status;
            }
        }
    }
    /* number 4 of 8.2.5.1 */
    /* This basically says every frame must be at least used for short-term ref. */
    /* Need to be revisited!!! */
    /* look at insert_picture_in_dpb() */



    if (video->nal_unit_type != AVC_NALTYPE_IDR && video->currPic->isLongTerm == FALSE)
    {
        if (video->currPic->isReference)
        {
            video->currFS->IsReference = 3;
        }
        else
        {
            video->currFS->IsReference = 0;
        }
        video->currFS->IsLongTerm = 0;
    }

    /* check if number of reference frames doesn't exceed num_ref_frames */
    num_ref = 0;
    for (ii = 0; ii < dpb->num_fs; ii++)
    {
        if (dpb->fs[ii]->IsReference)
        {
            num_ref++;
        }
    }

    if (num_ref > (int)video->currSeqParams->num_ref_frames)
    {
        return AVC_FAIL; /* out of range */
    }

    return AVC_SUCCESS;
}


AVCStatus sliding_window_process(AVCHandle *avcHandle, AVCCommonObj *video, AVCDecPicBuffer *dpb)
{
    int ii, numShortTerm, numLongTerm;
    int32 MinFrameNumWrap;
    int MinIdx;


    numShortTerm = 0;
    numLongTerm = 0;
    for (ii = 0; ii < dpb->num_fs; ii++)
    {
        if (dpb->fs[ii] != video->currFS) /* do not count the current frame */
        {
            if (dpb->fs[ii]->IsLongTerm)
            {
                numLongTerm++;
            }
            else if (dpb->fs[ii]->IsReference)
            {
                numShortTerm++;
            }
        }
    }

    while (numShortTerm + numLongTerm >= (int)video->currSeqParams->num_ref_frames)
    {
        /* get short-term ref frame with smallest PicOrderCnt */
        /* this doesn't work for all I-slice clip since PicOrderCnt will not be initialized */

        MinFrameNumWrap = 0x7FFFFFFF;
        MinIdx = -1;
        for (ii = 0; ii < dpb->num_fs; ii++)
        {
            if (dpb->fs[ii]->IsReference && !dpb->fs[ii]->IsLongTerm)
            {
                if (dpb->fs[ii]->FrameNumWrap < MinFrameNumWrap)
                {
                    MinFrameNumWrap = dpb->fs[ii]->FrameNumWrap;
                    MinIdx = ii;
                }
            }
        }
        if (MinIdx < 0) /* something wrong, impossible */
        {
            return AVC_FAIL;
        }

        /* mark the frame with smallest PicOrderCnt to be unused for reference */
        dpb->fs[MinIdx]->IsReference = 0;
        dpb->fs[MinIdx]->IsLongTerm = 0;
        dpb->fs[MinIdx]->frame.isReference = FALSE;
        dpb->fs[MinIdx]->frame.isLongTerm = FALSE;
        dpb->fs[MinIdx]->IsOutputted |= 0x02;
#ifdef PV_MEMORY_POOL
        if (dpb->fs[MinIdx]->IsOutputted == 3)
        {
            avcHandle->CBAVC_FrameUnbind(avcHandle->userData, MinIdx);
        }
#endif
        numShortTerm--;
    }
    return AVC_SUCCESS;
}

/* see subclause 8.2.5.4 */
AVCStatus adaptive_memory_marking(AVCHandle *avcHandle, AVCCommonObj *video, AVCDecPicBuffer *dpb, AVCSliceHeader *sliceHdr)
{
    int ii;

    ii = 0;
    while (ii < MAX_DEC_REF_PIC_MARKING && sliceHdr->memory_management_control_operation[ii] != 0)
    {
        switch (sliceHdr->memory_management_control_operation[ii])
        {
            case 1:
                MemMgrCtrlOp1(avcHandle, video, dpb, sliceHdr->difference_of_pic_nums_minus1[ii]);
                //      update_ref_list(dpb);
                break;
            case 2:
                MemMgrCtrlOp2(avcHandle, dpb, sliceHdr->long_term_pic_num[ii]);
                break;
            case 3:
                MemMgrCtrlOp3(avcHandle, video, dpb, sliceHdr->difference_of_pic_nums_minus1[ii], sliceHdr->long_term_frame_idx[ii]);
                break;
            case 4:
                MemMgrCtrlOp4(avcHandle, video, dpb, sliceHdr->max_long_term_frame_idx_plus1[ii]);
                break;
            case 5:
                MemMgrCtrlOp5(avcHandle, video, dpb);
                video->currFS->FrameNum = 0;    //
                video->currFS->PicOrderCnt = 0;
                break;
            case 6:
                MemMgrCtrlOp6(avcHandle, video, dpb, sliceHdr->long_term_frame_idx[ii]);
                break;
        }
        ii++;
    }

    if (ii == MAX_DEC_REF_PIC_MARKING)
    {
        return AVC_FAIL; /* exceed the limit */
    }

    return AVC_SUCCESS;
}


/* see subclause 8.2.5.4.1, mark short-term picture as "unused for reference" */
void MemMgrCtrlOp1(AVCHandle *avcHandle, AVCCommonObj *video, AVCDecPicBuffer *dpb, int difference_of_pic_nums_minus1)
{
    int picNumX, ii;

    picNumX = video->CurrPicNum - (difference_of_pic_nums_minus1 + 1);

    for (ii = 0; ii < dpb->num_fs; ii++)
    {
        if (dpb->fs[ii]->IsReference == 3 && dpb->fs[ii]->IsLongTerm == 0)
        {
            if (dpb->fs[ii]->frame.PicNum == picNumX)
            {
                unmark_for_reference(avcHandle, dpb, ii);
                return ;
            }
        }
    }

    return ;
}

/* see subclause 8.2.5.4.2 mark long-term picture as "unused for reference" */
void MemMgrCtrlOp2(AVCHandle *avcHandle, AVCDecPicBuffer *dpb, int long_term_pic_num)
{
    int ii;

    for (ii = 0; ii < dpb->num_fs; ii++)
    {
        if (dpb->fs[ii]->IsLongTerm == 3)
        {
            if (dpb->fs[ii]->frame.LongTermPicNum == long_term_pic_num)
            {
                unmark_for_reference(avcHandle, dpb, ii);
            }
        }
    }
}

/* see subclause 8.2.5.4.3 assign LongTermFrameIdx to a short-term ref picture */
void MemMgrCtrlOp3(AVCHandle *avcHandle, AVCCommonObj *video, AVCDecPicBuffer *dpb, uint difference_of_pic_nums_minus1,
                   uint long_term_frame_idx)
{
    int picNumX, ii;

    picNumX = video->CurrPicNum - (difference_of_pic_nums_minus1 + 1);

    /* look for fs[i] with long_term_frame_idx */

    unmark_long_term_frame_for_reference_by_frame_idx(avcHandle, dpb, long_term_frame_idx);


    /* now mark the picture with picNumX to long term frame idx */

    for (ii = 0; ii < dpb->num_fs; ii++)
    {
        if (dpb->fs[ii]->IsReference == 3)
        {
            if ((dpb->fs[ii]->frame.isLongTerm == FALSE) && (dpb->fs[ii]->frame.PicNum == picNumX))
            {
                dpb->fs[ii]->LongTermFrameIdx = long_term_frame_idx;
                dpb->fs[ii]->frame.LongTermPicNum = long_term_frame_idx;

                dpb->fs[ii]->frame.isLongTerm = TRUE;

                dpb->fs[ii]->IsLongTerm = 3;
                return;
            }
        }
    }

}

/* see subclause 8.2.5.4.4, MaxLongTermFrameIdx */
void MemMgrCtrlOp4(AVCHandle *avcHandle, AVCCommonObj *video, AVCDecPicBuffer *dpb, uint max_long_term_frame_idx_plus1)
{
    int ii;

    video->MaxLongTermFrameIdx = max_long_term_frame_idx_plus1 - 1;

    /* then mark long term frame with exceeding LongTermFrameIdx to unused for reference. */
    for (ii = 0; ii < dpb->num_fs; ii++)
    {
        if (dpb->fs[ii]->IsLongTerm && dpb->fs[ii] != video->currFS)
        {
            if (dpb->fs[ii]->LongTermFrameIdx > video->MaxLongTermFrameIdx)
            {
                unmark_for_reference(avcHandle, dpb, ii);
            }
        }
    }
}

/* see subclause 8.2.5.4.5 mark all reference picture as "unused for reference" and setting
MaxLongTermFrameIdx to "no long-term frame indices" */
void MemMgrCtrlOp5(AVCHandle *avcHandle, AVCCommonObj *video, AVCDecPicBuffer *dpb)
{
    int ii;

    video->MaxLongTermFrameIdx = -1;
    for (ii = 0; ii < dpb->num_fs; ii++) /* including the current frame ??????*/
    {
        if (dpb->fs[ii] != video->currFS) // MC_FIX
        {
            unmark_for_reference(avcHandle, dpb, ii);
        }
    }

    video->mem_mgr_ctrl_eq_5 = TRUE;
}

/* see subclause 8.2.5.4.6 assing long-term frame index to the current picture */
void MemMgrCtrlOp6(AVCHandle *avcHandle, AVCCommonObj *video, AVCDecPicBuffer *dpb, uint long_term_frame_idx)
{

    unmark_long_term_frame_for_reference_by_frame_idx(avcHandle, dpb, long_term_frame_idx);
    video->currFS->IsLongTerm = 3;
    video->currFS->IsReference = 3;

    video->currPic->isLongTerm = TRUE;
    video->currPic->isReference = TRUE;
    video->currFS->LongTermFrameIdx = long_term_frame_idx;
}


void unmark_for_reference(AVCHandle *avcHandle, AVCDecPicBuffer *dpb, uint idx)
{

    AVCFrameStore *fs = dpb->fs[idx];
    fs->frame.isReference = FALSE;
    fs->frame.isLongTerm = FALSE;

    fs->IsLongTerm = 0;
    fs->IsReference = 0;
    fs->IsOutputted |= 0x02;
#ifdef PV_MEMORY_POOL
    if (fs->IsOutputted == 3)
    {
        avcHandle->CBAVC_FrameUnbind(avcHandle->userData, idx);
    }
#endif
    return ;
}

void unmark_long_term_frame_for_reference_by_frame_idx(AVCHandle *avcHandle, AVCDecPicBuffer *dpb, uint long_term_frame_idx)
{
    int ii;
    for (ii = 0; ii < dpb->num_fs; ii++)
    {

        if (dpb->fs[ii]->IsLongTerm && (dpb->fs[ii]->LongTermFrameIdx == (int)long_term_frame_idx))
        {
            unmark_for_reference(avcHandle, dpb, ii);
        }

    }
}


