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
/**
This file contains application function interfaces to the AVC decoder library.
@publishedAll
*/

#include <string.h>

#include "avcdec_api.h"
#include "avcdec_lib.h"
#include "avcdec_bitstream.h"

/* ======================================================================== */
/*  Function : EBSPtoRBSP()                                                 */
/*  Date     : 11/4/2003                                                    */
/*  Purpose  : Convert EBSP to RBSP and overwrite it.                       */
/*             Assuming that forbidden_zero, nal_ref_idc and nal_unit_type  */
/*          (first byte), has been taken out of the nal_unit.               */
/*  In/out   :                                                              */
/*  Return   :                                                              */
/*  Modified :                                                              */
/* ======================================================================== */
/**
@pseudocode "
    NumBytesInRBSP = 0;
    for(i=0:i< *size; i++){
        if(i+2 < *size && next_bits(24)==0x000003){
            rbsp_byte[NumBytesInRBSP++];
            rbsp_byte[NumBytesInRBSP++];
            i+=2;
            emulation_prevention_three_byte (0x03)
        }
        else
            rbsp_byte[NumBytesInRBSP++];
    }"
*/
AVCDec_Status EBSPtoRBSP(uint8 *nal_unit, int *size)
{
    int i, j;
    int count = 0;

    /* This code is based on EBSPtoRBSP of JM */
    j = 0;

    for (i = 0; i < *size; i++)
    {
        if (count == 2 && nal_unit[i] == 0x03)
        {
            i++;
            count = 0;
        }
        nal_unit[j] = nal_unit[i];
        if (nal_unit[i] == 0x00)
            count++;
        else
            count = 0;
        j++;
    }

    *size = j;

    return AVCDEC_SUCCESS;
}

/* ======================================================================== */
/*  Function : PVAVCAnnexBGetNALUnit()                                      */
/*  Date     : 11/3/2003                                                    */
/*  Purpose  : Parse a NAL from byte stream format.                         */
/*  In/out   :                                                              */
/*  Return   : AVCDEC_SUCCESS if succeed, AVC_FAIL if fail.                 */
/*  Modified :                                                              */
/* ======================================================================== */
/**
@pseudocode "
    byte_stream_nal_unit(NumBytesInNalunit){
    while(next_bits(24) != 0x000001)
        zero_byte
    if(more_data_in_byte_stream()){
        start_code_prefix_one_3bytes // equal 0x000001
        nal_unit(NumBytesInNALunit)
    }
   }"
*/
OSCL_EXPORT_REF AVCDec_Status PVAVCAnnexBGetNALUnit(uint8 *bitstream, uint8 **nal_unit,
        int *size)
{
    int i, j, FoundStartCode = 0;
    int end;

    i = 0;
    while (bitstream[i] == 0 && i < *size)
    {
        i++;
    }
    if (i >= *size)
    {
        *nal_unit = bitstream;
        return AVCDEC_FAIL; /* cannot find any start_code_prefix. */
    }
    else if (bitstream[i] != 0x1)
    {
        i = -1;  /* start_code_prefix is not at the beginning, continue */
    }

    i++;
    *nal_unit = bitstream + i; /* point to the beginning of the NAL unit */

    j = end = i;
    while (!FoundStartCode)
    {
        while ((j + 1 < *size) && (bitstream[j] != 0 || bitstream[j+1] != 0))  /* see 2 consecutive zero bytes */
        {
            j++;
        }
        end = j;   /* stop and check for start code */
        while (j + 2 < *size && bitstream[j+2] == 0) /* keep reading for zero byte */
        {
            j++;
        }
        if (j + 2 >= *size)
        {
            *size -= i;
            return AVCDEC_NO_NEXT_SC;  /* cannot find the second start_code_prefix */
        }
        if (bitstream[j+2] == 0x1)
        {
            FoundStartCode = 1;
        }
        else
        {
            /* could be emulation code 0x3 */
            j += 2; /* continue the search */
        }
    }

    *size = end - i;

    return AVCDEC_SUCCESS;
}

/* ======================================================================== */
/*  Function : PVAVCGetNALType()                                            */
/*  Date     : 11/4/2003                                                    */
/*  Purpose  : Sniff NAL type from the bitstream                            */
/*  In/out   :                                                              */
/*  Return   : AVCDEC_SUCCESS if succeed, AVC_FAIL if fail.                 */
/*  Modified :                                                              */
/* ======================================================================== */
OSCL_EXPORT_REF AVCDec_Status PVAVCDecGetNALType(uint8 *bitstream, int size,
        int *nal_type, int *nal_ref_idc)
{
    int forbidden_zero_bit;
    if (size > 0)
    {
        forbidden_zero_bit = bitstream[0] >> 7;
        if (forbidden_zero_bit != 0)
            return AVCDEC_FAIL;
        *nal_ref_idc = (bitstream[0] & 0x60) >> 5;
        *nal_type = bitstream[0] & 0x1F;
        return AVCDEC_SUCCESS;
    }

    return AVCDEC_FAIL;
}

/* ======================================================================== */
/*  Function : PVAVCDecSeqParamSet()                                        */
/*  Date     : 11/4/2003                                                    */
/*  Purpose  : Initialize sequence, memory allocation if necessary.         */
/*  In/out   :                                                              */
/*  Return   : AVCDEC_SUCCESS if succeed, AVC_FAIL if fail.                 */
/*  Modified :                                                              */
/* ======================================================================== */

OSCL_EXPORT_REF AVCDec_Status   PVAVCDecSeqParamSet(AVCHandle *avcHandle, uint8 *nal_unit,
        int nal_size)
{
    AVCDec_Status status;
    AVCDecObject *decvid;
    AVCCommonObj *video;
    AVCDecBitstream *bitstream;
    void *userData = avcHandle->userData;
    bool  first_seq = FALSE;
    int i;


    DEBUG_LOG(userData, AVC_LOGTYPE_INFO, "PVAVCDecSeqParamSet", -1, -1);

    if (avcHandle->AVCObject == NULL)
    {
        first_seq = TRUE;

        //avcHandle->memory_usage = 0;
        /* allocate AVCDecObject */
        avcHandle->AVCObject = (void*)avcHandle->CBAVC_Malloc(userData, sizeof(AVCDecObject), 0/*DEFAULT_ATTR*/);
        if (avcHandle->AVCObject == NULL)
        {
            return AVCDEC_MEMORY_FAIL;
        }

        decvid = (AVCDecObject*) avcHandle->AVCObject;

        memset(decvid, 0, sizeof(AVCDecObject));

        decvid->common = (AVCCommonObj*)avcHandle->CBAVC_Malloc(userData, sizeof(AVCCommonObj), 0);
        if (decvid->common == NULL)
        {
            return AVCDEC_MEMORY_FAIL;
        }

        video = decvid->common;
        memset(video, 0, sizeof(AVCCommonObj));

        video->seq_parameter_set_id = 9999; /* set it to some illegal value */

        decvid->bitstream = (AVCDecBitstream *) avcHandle->CBAVC_Malloc(userData, sizeof(AVCDecBitstream), 1/*DEFAULT_ATTR*/);
        if (decvid->bitstream == NULL)
        {
            return AVCDEC_MEMORY_FAIL;
        }

        decvid->bitstream->userData = avcHandle->userData; /* callback for more data */
        decvid->avcHandle = avcHandle;
        decvid->debugEnable = avcHandle->debugEnable;
    }

    decvid = (AVCDecObject*) avcHandle->AVCObject;
    video = decvid->common;
    bitstream = decvid->bitstream;

    /* check if we can reuse the memory without re-allocating it. */
    /* always check if(first_seq==TRUE) */

    /* Conversion from EBSP to RBSP */
    video->forbidden_bit = nal_unit[0] >> 7;
    if (video->forbidden_bit) return AVCDEC_FAIL;
    video->nal_ref_idc = (nal_unit[0] & 0x60) >> 5;
    video->nal_unit_type = (AVCNalUnitType)(nal_unit[0] & 0x1F);

    if (video->nal_unit_type != AVC_NALTYPE_SPS) /* not a SPS NAL */
    {
        return AVCDEC_FAIL;
    }

    /* Initialize bitstream structure*/
    BitstreamInit(bitstream, nal_unit + 1, nal_size - 1);

    /* if first_seq == TRUE, allocate the following memory  */
    if (first_seq == TRUE)
    {
        video->currSeqParams = NULL; /* initialize it to NULL */
        video->currPicParams = NULL;

        /* There are 32 pointers to sequence param set, seqParams.
                There are 255 pointers to picture param set, picParams.*/
        for (i = 0; i < 32; i++)
            decvid->seqParams[i] = NULL;

        for (i = 0; i < 256; i++)
            decvid->picParams[i] = NULL;

        video->MbToSliceGroupMap = NULL;

        video->mem_mgr_ctrl_eq_5 = FALSE;
        video->newPic = TRUE;
        video->newSlice = TRUE;
        video->currPic = NULL;
        video->currFS = NULL;
        video->prevRefPic = NULL;

        video->mbNum = 0; // MC_Conceal
        /*  Allocate sliceHdr. */

        video->sliceHdr = (AVCSliceHeader*) avcHandle->CBAVC_Malloc(userData, sizeof(AVCSliceHeader), 5/*DEFAULT_ATTR*/);
        if (video->sliceHdr == NULL)
        {
            return AVCDEC_MEMORY_FAIL;
        }

        video->decPicBuf = (AVCDecPicBuffer*) avcHandle->CBAVC_Malloc(userData, sizeof(AVCDecPicBuffer), 3/*DEFAULT_ATTR*/);
        if (video->decPicBuf == NULL)
        {
            return AVCDEC_MEMORY_FAIL;
        }
        memset(video->decPicBuf, 0, sizeof(AVCDecPicBuffer));
    }

    /* Decode SPS, allocate video->seqParams[i] and assign video->currSeqParams */
    status = DecodeSPS(decvid, bitstream);

    if (status != AVCDEC_SUCCESS)
    {
        return status;
    }
    return AVCDEC_SUCCESS;
}

/* ======================================================================== */
/*  Function : PVAVCDecGetSeqInfo()                                         */
/*  Date     : 11/4/2003                                                    */
/*  Purpose  : Get sequence parameter info. after SPS NAL is decoded.       */
/*  In/out   :                                                              */
/*  Return   : AVCDEC_SUCCESS if succeed, AVC_FAIL if fail.                 */
/*  Modified :                                                              */
/*  12/20/03:  change input argument, use structure instead.                */
/* ======================================================================== */

OSCL_EXPORT_REF AVCDec_Status PVAVCDecGetSeqInfo(AVCHandle *avcHandle, AVCDecSPSInfo *seqInfo)
{
    AVCDecObject *decvid = (AVCDecObject*) avcHandle->AVCObject;
    AVCCommonObj *video;
    int PicWidthInMbs, PicHeightInMapUnits, FrameHeightInMbs;

    if (decvid == NULL || decvid->seqParams[0] == NULL)
    {
        return AVCDEC_FAIL;
    }

    video = decvid->common;

    PicWidthInMbs = decvid->seqParams[0]->pic_width_in_mbs_minus1 + 1;
    PicHeightInMapUnits = decvid->seqParams[0]->pic_height_in_map_units_minus1 + 1 ;
    FrameHeightInMbs = (2 - decvid->seqParams[0]->frame_mbs_only_flag) * PicHeightInMapUnits ;

    seqInfo->FrameWidth = PicWidthInMbs << 4;
    seqInfo->FrameHeight = FrameHeightInMbs << 4;

    seqInfo->frame_only_flag = decvid->seqParams[0]->frame_mbs_only_flag;

    if (decvid->seqParams[0]->frame_cropping_flag)
    {
        seqInfo->frame_crop_left = 2 * decvid->seqParams[0]->frame_crop_left_offset;
        seqInfo->frame_crop_right = seqInfo->FrameWidth - (2 * decvid->seqParams[0]->frame_crop_right_offset + 1);

        if (seqInfo->frame_only_flag)
        {
            seqInfo->frame_crop_top = 2 * decvid->seqParams[0]->frame_crop_top_offset;
            seqInfo->frame_crop_bottom = seqInfo->FrameHeight - (2 * decvid->seqParams[0]->frame_crop_bottom_offset + 1);
            /* Note in 7.4.2.1, there is a contraint on the value of frame_crop_left and frame_crop_top
            such that they have to be less than or equal to frame_crop_right/2 and frame_crop_bottom/2, respectively. */
        }
        else
        {
            seqInfo->frame_crop_top = 4 * decvid->seqParams[0]->frame_crop_top_offset;
            seqInfo->frame_crop_bottom = seqInfo->FrameHeight - (4 * decvid->seqParams[0]->frame_crop_bottom_offset + 1);
            /* Note in 7.4.2.1, there is a contraint on the value of frame_crop_left and frame_crop_top
            such that they have to be less than or equal to frame_crop_right/2 and frame_crop_bottom/4, respectively. */
        }
    }
    else  /* no cropping flag, just give the first and last pixel */
    {
        seqInfo->frame_crop_bottom = seqInfo->FrameHeight - 1;
        seqInfo->frame_crop_right = seqInfo->FrameWidth - 1;
        seqInfo->frame_crop_top = seqInfo->frame_crop_left = 0;
    }

    return AVCDEC_SUCCESS;
}

/* ======================================================================== */
/*  Function : PVAVCDecPicParamSet()                                        */
/*  Date     : 11/4/2003                                                    */
/*  Purpose  : Initialize picture                                           */
/*             create reference picture list.                               */
/*  In/out   :                                                              */
/*  Return   : AVCDEC_SUCCESS if succeed, AVC_FAIL if fail.                 */
/*  Modified :                                                              */
/* ======================================================================== */
/**
Since PPS doesn't contain much data, most of the picture initialization will
be done after decoding the slice header in PVAVCDecodeSlice. */
OSCL_EXPORT_REF AVCDec_Status   PVAVCDecPicParamSet(AVCHandle *avcHandle, uint8 *nal_unit,
        int nal_size)
{
    AVCDec_Status status;
    AVCDecObject *decvid = (AVCDecObject*) avcHandle->AVCObject;
    AVCCommonObj *video;
    AVCDecBitstream *bitstream;

    if (decvid == NULL)
    {
        return AVCDEC_FAIL;
    }

    video = decvid->common;
    bitstream = decvid->bitstream;
    /* 1. Convert EBSP to RBSP. Create bitstream structure */
    video->forbidden_bit = nal_unit[0] >> 7;
    video->nal_ref_idc = (nal_unit[0] & 0x60) >> 5;
    video->nal_unit_type = (AVCNalUnitType)(nal_unit[0] & 0x1F);

    if (video->nal_unit_type != AVC_NALTYPE_PPS) /* not a PPS NAL */
    {
        return AVCDEC_FAIL;
    }


    /* 2. Initialize bitstream structure*/
    BitstreamInit(bitstream, nal_unit + 1, nal_size - 1);

    /* 2. Decode pic_parameter_set_rbsp syntax. Allocate video->picParams[i] and assign to currPicParams */
    status = DecodePPS(decvid, video, bitstream);
    if (status != AVCDEC_SUCCESS)
    {
        return status;
    }

    video->SliceGroupChangeRate = video->currPicParams->slice_group_change_rate_minus1 + 1 ;

    return AVCDEC_SUCCESS;
}

OSCL_EXPORT_REF AVCDec_Status   PVAVCDecSEI(AVCHandle *avcHandle, uint8 *nal_unit,
        int nal_size)
{
    OSCL_UNUSED_ARG(avcHandle);
    OSCL_UNUSED_ARG(nal_unit);
    OSCL_UNUSED_ARG(nal_size);

    return AVCDEC_SUCCESS;
}
/* ======================================================================== */
/*  Function : PVAVCDecodeSlice()                                           */
/*  Date     : 11/4/2003                                                    */
/*  Purpose  : Decode one NAL unit.                                         */
/*  In/out   :                                                              */
/*  Return   : See enum AVCDec_Status for return values.                    */
/*  Modified :                                                              */
/* ======================================================================== */
OSCL_EXPORT_REF AVCDec_Status PVAVCDecodeSlice(AVCHandle *avcHandle, uint8 *buffer,
        int buf_size)
{
    AVCDecObject *decvid = (AVCDecObject*) avcHandle->AVCObject;
    AVCCommonObj *video;
    AVCDecBitstream *bitstream;
    AVCDec_Status status;

    if (decvid == NULL)
    {
        return AVCDEC_FAIL;
    }

    video = decvid->common;
    bitstream = decvid->bitstream;

    if (video->mem_mgr_ctrl_eq_5)
    {
        return AVCDEC_PICTURE_OUTPUT_READY;      // to flushout frame buffers
    }

    if (video->newSlice)
    {
        /* 2. Check NAL type  */
        if (buffer == NULL)
        {
            return AVCDEC_FAIL;
        }
        video->prev_nal_unit_type = video->nal_unit_type;
        video->forbidden_bit = buffer[0] >> 7;
        video->nal_ref_idc = (buffer[0] & 0x60) >> 5;
        video->nal_unit_type = (AVCNalUnitType)(buffer[0] & 0x1F);


        if (video->nal_unit_type == AVC_NALTYPE_AUD)
        {
            return AVCDEC_SUCCESS;
        }

        if (video->nal_unit_type != AVC_NALTYPE_SLICE &&
                video->nal_unit_type != AVC_NALTYPE_IDR)
        {
            return AVCDEC_FAIL; /* not supported */
        }



        if (video->nal_unit_type >= 2 && video->nal_unit_type <= 4)
        {
            return AVCDEC_FAIL; /* not supported */
        }
        else
        {
            video->slice_data_partitioning = FALSE;
        }

        video->newSlice = FALSE;
        /*  Initialize bitstream structure*/
        BitstreamInit(bitstream, buffer + 1, buf_size - 1);


        /* 2.1 Decode Slice Header (separate function)*/
        status = DecodeSliceHeader(decvid, video, bitstream);
        if (status != AVCDEC_SUCCESS)
        {
            video->newSlice = TRUE;
            return status;
        }

        if (video->sliceHdr->frame_num != video->prevFrameNum || (video->sliceHdr->first_mb_in_slice < (uint)video->mbNum && video->currSeqParams->constrained_set1_flag == 1))
        {
            video->newPic = TRUE;
            if (video->numMBs > 0)
            {
                // Conceal missing MBs of previously decoded frame
                ConcealSlice(decvid, video->PicSizeInMbs - video->numMBs, video->PicSizeInMbs);  // Conceal
                video->numMBs = 0;

                //              DeblockPicture(video);   // No need to deblock

                /* 3.2 Decoded frame reference marking. */
                /* 3.3 Put the decoded picture in output buffers */
                /* set video->mem_mge_ctrl_eq_5 */
                AVCNalUnitType temp = video->nal_unit_type;
                video->nal_unit_type = video->prev_nal_unit_type;
                StorePictureInDPB(avcHandle, video);
                video->nal_unit_type = temp;
                video->mbNum = 0; // MC_Conceal
                return AVCDEC_PICTURE_OUTPUT_READY;
            }
        }

        if (video->nal_unit_type == AVC_NALTYPE_IDR)
        {
            video->prevFrameNum = 0;
            video->PrevRefFrameNum = 0;
        }

        if (!video->currSeqParams->gaps_in_frame_num_value_allowed_flag)
        {   /* no gaps allowed, frame_num has to increase by one only */
            /*          if(sliceHdr->frame_num != (video->PrevRefFrameNum + 1)%video->MaxFrameNum) */
            if (video->sliceHdr->frame_num != video->PrevRefFrameNum && video->sliceHdr->frame_num != (video->PrevRefFrameNum + 1) % video->MaxFrameNum)
            {
                // Conceal missing MBs of previously decoded frame
                video->numMBs = 0;
                video->newPic = TRUE;
                video->prevFrameNum++; // FIX
                video->PrevRefFrameNum++;
                AVCNalUnitType temp = video->nal_unit_type;
                video->nal_unit_type = AVC_NALTYPE_SLICE; //video->prev_nal_unit_type;
                status = (AVCDec_Status)DPBInitBuffer(avcHandle, video);
                if (status != AVCDEC_SUCCESS)
                {
                    return status;
                }
                video->currFS->IsOutputted = 0x01;
                video->currFS->IsReference = 3;
                video->currFS->IsLongTerm = 0;

                DecodePOC(video);
                /* find an empty memory from DPB and assigned to currPic */
                DPBInitPic(video, video->PrevRefFrameNum % video->MaxFrameNum);
                RefListInit(video);
                ConcealSlice(decvid, 0, video->PicSizeInMbs);  // Conceal
                video->currFS->IsOutputted |= 0x02;
                //conceal frame
                /* 3.2 Decoded frame reference marking. */
                /* 3.3 Put the decoded picture in output buffers */
                /* set video->mem_mge_ctrl_eq_5 */
                video->mbNum = 0; // Conceal
                StorePictureInDPB(avcHandle, video);
                video->nal_unit_type = temp;

                return AVCDEC_PICTURE_OUTPUT_READY;
            }
        }
    }

    if (video->newPic == TRUE)
    {
        status = (AVCDec_Status)DPBInitBuffer(avcHandle, video);
        if (status != AVCDEC_SUCCESS)
        {
            return status;
        }
    }

    video->newSlice = TRUE;

    /* function pointer setting at slice-level */
    // OPTIMIZE
    decvid->residual_block = &residual_block_cavlc;

    /* derive picture order count */
    if (video->newPic == TRUE)
    {
        video->numMBs = video->PicSizeInMbs;

        if (video->nal_unit_type != AVC_NALTYPE_IDR && video->currSeqParams->gaps_in_frame_num_value_allowed_flag)
        {
            if (video->sliceHdr->frame_num != (video->PrevRefFrameNum + 1) % video->MaxFrameNum)
            {
                status = fill_frame_num_gap(avcHandle, video);
                if (status != AVCDEC_SUCCESS)
                {
                    video->numMBs = 0;
                    return status;
                }

                status = (AVCDec_Status)DPBInitBuffer(avcHandle, video);
                if (status != AVCDEC_SUCCESS)
                {
                    video->numMBs = 0;
                    return status;
                }


            }
        }
        /* if there's gap in the frame_num, we have to fill in the gap with
            imaginary frames that won't get used for short-term ref. */
        /* see fill_frame_num_gap() in JM */


        DecodePOC(video);
        /* find an empty memory from DPB and assigned to currPic */
        DPBInitPic(video, video->CurrPicNum);

        video->currPic->isReference = TRUE;  // FIX

        if (video->nal_ref_idc == 0)
        {
            video->currPic->isReference = FALSE;
            video->currFS->IsOutputted |= 0x02;     /* The MASK 0x02 means not needed for reference, or returned */
            /* node need to check for freeing of this buffer */
        }

        FMOInit(video);

        if (video->currPic->isReference)
        {
            video->PrevRefFrameNum = video->sliceHdr->frame_num;
        }


        video->prevFrameNum = video->sliceHdr->frame_num;
    }

    video->newPic = FALSE;


    /* Initialize refListIdx for this picture */
    RefListInit(video);

    /* Re-order the reference list according to the ref_pic_list_reordering() */
    status = (AVCDec_Status)ReOrderList(video);
    if (status != AVCDEC_SUCCESS)
    {
        return AVCDEC_FAIL;
    }

    /* 2.2 Decode Slice. */
    status = (AVCDec_Status)DecodeSlice(decvid);

    video->slice_id++;  //  slice

    if (status == AVCDEC_PICTURE_READY)
    {
        /* 3. Check complete picture */
#ifndef MB_BASED_DEBLOCK
        /* 3.1 Deblock */
        DeblockPicture(video);
#endif
        /* 3.2 Decoded frame reference marking. */
        /* 3.3 Put the decoded picture in output buffers */
        /* set video->mem_mge_ctrl_eq_5 */
        status = (AVCDec_Status)StorePictureInDPB(avcHandle, video);          // CHECK check the retunr status
        if (status != AVCDEC_SUCCESS)
        {
            return AVCDEC_FAIL;
        }

        if (video->mem_mgr_ctrl_eq_5)
        {
            video->PrevRefFrameNum = 0;
            video->prevFrameNum = 0;
            video->prevPicOrderCntMsb = 0;
            video->prevPicOrderCntLsb = video->TopFieldOrderCnt;
            video->prevFrameNumOffset = 0;
        }
        else
        {
            video->prevPicOrderCntMsb = video->PicOrderCntMsb;
            video->prevPicOrderCntLsb = video->sliceHdr->pic_order_cnt_lsb;
            video->prevFrameNumOffset = video->FrameNumOffset;
        }

        return AVCDEC_PICTURE_READY;
    }
    else if (status != AVCDEC_SUCCESS)
    {
        return AVCDEC_FAIL;
    }

    return AVCDEC_SUCCESS;
}

/* ======================================================================== */
/*  Function : PVAVCDecGetOutput()                                          */
/*  Date     : 11/3/2003                                                    */
/*  Purpose  : Get the next picture according to PicOrderCnt.               */
/*  In/out   :                                                              */
/*  Return   : AVCFrameIO structure                                         */
/*  Modified :                                                              */
/* ======================================================================== */

OSCL_EXPORT_REF AVCDec_Status PVAVCDecGetOutput(AVCHandle *avcHandle, int *indx, int *release, AVCFrameIO *output)
{
    AVCDecObject *decvid = (AVCDecObject*) avcHandle->AVCObject;
    AVCCommonObj *video;
    AVCDecPicBuffer *dpb;
    AVCFrameStore *oldestFrame = NULL;
    int i, first = 1;
    int count_frame = 0;
    int index = 0;
    int min_poc = 0;

    if (decvid == NULL)
    {
        return AVCDEC_FAIL;
    }

    video = decvid->common;
    dpb = video->decPicBuf;

    if (dpb->num_fs == 0)
    {
        return AVCDEC_FAIL;
    }

    /* search for the oldest frame_num in dpb */
    /* extension to field decoding, we have to search for every top_field/bottom_field within
    each frame in the dpb. This code only works for frame based.*/

    if (video->mem_mgr_ctrl_eq_5 == FALSE)
    {
        for (i = 0; i < dpb->num_fs; i++)
        {
            if ((dpb->fs[i]->IsOutputted & 0x01) == 0)
            {
                count_frame++;
                if (first)
                {
                    min_poc = dpb->fs[i]->PicOrderCnt;
                    first = 0;
                    oldestFrame = dpb->fs[i];
                    index = i;
                }
                if (dpb->fs[i]->PicOrderCnt < min_poc)
                {
                    min_poc = dpb->fs[i]->PicOrderCnt;
                    oldestFrame = dpb->fs[i];
                    index = i;
                }
            }
        }
    }
    else
    {
        for (i = 0; i < dpb->num_fs; i++)
        {
            if ((dpb->fs[i]->IsOutputted & 0x01) == 0 && dpb->fs[i] != video->currFS)
            {
                count_frame++;
                if (first)
                {
                    min_poc = dpb->fs[i]->PicOrderCnt;
                    first = 0;
                    oldestFrame = dpb->fs[i];
                    index = i;
                }
                if (dpb->fs[i]->PicOrderCnt < min_poc)
                {
                    min_poc = dpb->fs[i]->PicOrderCnt;
                    oldestFrame = dpb->fs[i];
                    index = i;
                }
            }
        }

        if (count_frame < 2 && video->nal_unit_type != AVC_NALTYPE_IDR)
        {
            video->mem_mgr_ctrl_eq_5 = FALSE;  // FIX
        }
        else if (count_frame < 1 && video->nal_unit_type == AVC_NALTYPE_IDR)
        {
            for (i = 0; i < dpb->num_fs; i++)
            {
                if (dpb->fs[i] == video->currFS && (dpb->fs[i]->IsOutputted & 0x01) == 0)
                {
                    oldestFrame = dpb->fs[i];
                    index = i;
                    break;
                }
            }
            video->mem_mgr_ctrl_eq_5 = FALSE;
        }
    }

    if (oldestFrame == NULL)
    {

        /*      Check for Mem_mgmt_operation_5 based forced output */
        for (i = 0; i < dpb->num_fs; i++)
        {
            /* looking for the one not used or not reference and has been outputted */
            if (dpb->fs[i]->IsReference == 0 && dpb->fs[i]->IsOutputted == 3)
            {
                break;
            }
        }
        if (i < dpb->num_fs)
        {
            /* there are frames available for decoding */
            return AVCDEC_FAIL; /* no frame to be outputted */
        }


        /* no free frame available, we have to release one to continue decoding */
        int MinIdx = 0;
        int32 MinFrameNumWrap = 0x7FFFFFFF;

        for (i = 0; i < dpb->num_fs; i++)
        {
            if (dpb->fs[i]->IsReference && !dpb->fs[i]->IsLongTerm)
            {
                if (dpb->fs[i]->FrameNumWrap < MinFrameNumWrap)
                {
                    MinFrameNumWrap = dpb->fs[i]->FrameNumWrap;
                    MinIdx = i;
                }
            }
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
        return AVCDEC_FAIL;
    }
    /* MASK 0x01 means the frame is outputted (for display). A frame gets freed when it is
    outputted (0x01) and not needed for reference (0x02)   */
    oldestFrame->IsOutputted |= 0x01;

    if (oldestFrame->IsOutputted == 3)
    {
        *release = 1; /* flag to release the buffer */
    }
    else
    {
        *release = 0;
    }
    /* do not release buffer here, release it after it is sent to the sink node */

    output->YCbCr[0] = oldestFrame->frame.Sl;
    output->YCbCr[1] = oldestFrame->frame.Scb;
    output->YCbCr[2] = oldestFrame->frame.Scr;
    output->height = oldestFrame->frame.height;
    output->pitch = oldestFrame->frame.width;
    output->disp_order = oldestFrame->PicOrderCnt;
    output->coding_order = oldestFrame->FrameNum;
    output->id = (uint32) oldestFrame->base_dpb; /* use the pointer as the id */
    *indx = index;



    return AVCDEC_SUCCESS;
}


/* ======================================================================== */
/*  Function : PVAVCDecReset()                                              */
/*  Date     : 03/04/2004                                                   */
/*  Purpose  : Reset decoder, prepare it for a new IDR frame.               */
/*  In/out   :                                                              */
/*  Return   :  void                                                        */
/*  Modified :                                                              */
/* ======================================================================== */
OSCL_EXPORT_REF void    PVAVCDecReset(AVCHandle *avcHandle)
{
    AVCDecObject *decvid = (AVCDecObject*) avcHandle->AVCObject;
    AVCCommonObj *video;
    AVCDecPicBuffer *dpb;
    int i;

    if (decvid == NULL)
    {
        return;
    }

    video = decvid->common;
    dpb = video->decPicBuf;

    /* reset the DPB */


    for (i = 0; i < dpb->num_fs; i++)
    {
        dpb->fs[i]->IsLongTerm = 0;
        dpb->fs[i]->IsReference = 0;
        dpb->fs[i]->IsOutputted = 3;
        dpb->fs[i]->frame.isReference = 0;
        dpb->fs[i]->frame.isLongTerm = 0;
    }

    video->mem_mgr_ctrl_eq_5 = FALSE;
    video->newPic = TRUE;
    video->newSlice = TRUE;
    video->currPic = NULL;
    video->currFS = NULL;
    video->prevRefPic = NULL;
    video->prevFrameNum = 0;
    video->PrevRefFrameNum = 0;
    video->prevFrameNumOffset = 0;
    video->FrameNumOffset = 0;
    video->mbNum = 0;
    video->numMBs = 0;

    return ;
}


/* ======================================================================== */
/*  Function : PVAVCCleanUpDecoder()                                        */
/*  Date     : 11/4/2003                                                    */
/*  Purpose  : Clean up the decoder, free all memories allocated.           */
/*  In/out   :                                                              */
/*  Return   :  void                                                        */
/*  Modified :                                                              */
/* ======================================================================== */

OSCL_EXPORT_REF void PVAVCCleanUpDecoder(AVCHandle *avcHandle)
{
    AVCDecObject *decvid = (AVCDecObject*) avcHandle->AVCObject;
    AVCCommonObj *video;
    void *userData = avcHandle->userData;
    int i;

    DEBUG_LOG(userData, AVC_LOGTYPE_INFO, "PVAVCCleanUpDecoder", -1, -1);

    if (decvid != NULL)
    {
        video = decvid->common;
        if (video != NULL)
        {
            if (video->MbToSliceGroupMap != NULL)
            {
                avcHandle->CBAVC_Free(userData, (int)video->MbToSliceGroupMap);
            }

#ifdef MB_BASED_DEBLOCK
            if (video->intra_pred_top != NULL)
            {
                avcHandle->CBAVC_Free(userData, (int)video->intra_pred_top);
            }
            if (video->intra_pred_top_cb != NULL)
            {
                avcHandle->CBAVC_Free(userData, (int)video->intra_pred_top_cb);
            }
            if (video->intra_pred_top_cr != NULL)
            {
                avcHandle->CBAVC_Free(userData, (int)video->intra_pred_top_cr);
            }
#endif
            if (video->mblock != NULL)
            {
                avcHandle->CBAVC_Free(userData, (int)video->mblock);
            }

            if (video->decPicBuf != NULL)
            {
                CleanUpDPB(avcHandle, video);
                avcHandle->CBAVC_Free(userData, (int)video->decPicBuf);
            }

            if (video->sliceHdr != NULL)
            {
                avcHandle->CBAVC_Free(userData, (int)video->sliceHdr);
            }

            avcHandle->CBAVC_Free(userData, (int)video); /* last thing to do */

        }

        for (i = 0; i < 256; i++)
        {
            if (decvid->picParams[i] != NULL)
            {
                if (decvid->picParams[i]->slice_group_id != NULL)
                {
                    avcHandle->CBAVC_Free(userData, (int)decvid->picParams[i]->slice_group_id);
                }
                avcHandle->CBAVC_Free(userData, (int)decvid->picParams[i]);
            }
        }
        for (i = 0; i < 32; i++)
        {
            if (decvid->seqParams[i] != NULL)
            {
                avcHandle->CBAVC_Free(userData, (int)decvid->seqParams[i]);
            }
        }
        if (decvid->bitstream != NULL)
        {
            avcHandle->CBAVC_Free(userData, (int)decvid->bitstream);
        }


        avcHandle->CBAVC_Free(userData, (int)decvid);
    }


    return ;
}
