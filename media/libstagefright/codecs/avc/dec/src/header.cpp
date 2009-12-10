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
#include "avcdec_lib.h"
#include "avcdec_bitstream.h"
#include "avcdec_api.h"

/** see subclause 7.4.2.1 */
AVCDec_Status DecodeSPS(AVCDecObject *decvid, AVCDecBitstream *stream)
{
    AVCDec_Status status = AVCDEC_SUCCESS;
    AVCSeqParamSet *seqParam;
    uint temp;
    int i;
    uint profile_idc, constrained_set0_flag, constrained_set1_flag, constrained_set2_flag;
    uint level_idc, seq_parameter_set_id;
    void *userData = decvid->avcHandle->userData;
    AVCHandle *avcHandle = decvid->avcHandle;

    DEBUG_LOG(userData, AVC_LOGTYPE_INFO, "DecodeSPS", -1, -1);

    BitstreamReadBits(stream, 8, &profile_idc);
    BitstreamRead1Bit(stream, &constrained_set0_flag);
//  if (profile_idc != 66 && constrained_set0_flag != 1)
//  {
//      return AVCDEC_FAIL;
//  }
    BitstreamRead1Bit(stream, &constrained_set1_flag);
    BitstreamRead1Bit(stream, &constrained_set2_flag);
    BitstreamReadBits(stream, 5, &temp);
    BitstreamReadBits(stream, 8, &level_idc);
    if (level_idc > 51)
    {
        return AVCDEC_FAIL;
    }
    if (mapLev2Idx[level_idc] == 255)
    {
        return AVCDEC_FAIL;
    }
    ue_v(stream, &seq_parameter_set_id);

    if (seq_parameter_set_id > 31)
    {
        return AVCDEC_FAIL;
    }

    /* Allocate sequence param set for seqParams[seq_parameter_set_id]. */
    if (decvid->seqParams[seq_parameter_set_id] == NULL)  /* allocate seqParams[id] */
    {
        decvid->seqParams[seq_parameter_set_id] =
            (AVCSeqParamSet*) avcHandle->CBAVC_Malloc(userData, sizeof(AVCSeqParamSet), DEFAULT_ATTR);

        if (decvid->seqParams[seq_parameter_set_id] == NULL)
        {
            return AVCDEC_MEMORY_FAIL;
        }
    }

    DEBUG_LOG(userData, AVC_LOGTYPE_INFO, "done alloc seqParams", -1, -1);

    seqParam = decvid->seqParams[seq_parameter_set_id];

    seqParam->profile_idc = profile_idc;
    seqParam->constrained_set0_flag = constrained_set0_flag;
    seqParam->constrained_set1_flag = constrained_set1_flag;
    seqParam->constrained_set2_flag = constrained_set2_flag;
    seqParam->level_idc = level_idc;
    seqParam->seq_parameter_set_id = seq_parameter_set_id;

    /* continue decoding SPS */
    ue_v(stream, &(seqParam->log2_max_frame_num_minus4));

    if (seqParam->log2_max_frame_num_minus4 > 12)
    {
        return AVCDEC_FAIL;
    }

    ue_v(stream, &(seqParam->pic_order_cnt_type));

    DEBUG_LOG(userData, AVC_LOGTYPE_INFO, "check point 1", seqParam->log2_max_frame_num_minus4, seqParam->pic_order_cnt_type);

    if (seqParam->pic_order_cnt_type == 0)
    {
        ue_v(stream, &(seqParam->log2_max_pic_order_cnt_lsb_minus4));
    }
    else if (seqParam->pic_order_cnt_type == 1)
    {               // MC_CHECK
        BitstreamRead1Bit(stream, (uint*)&(seqParam->delta_pic_order_always_zero_flag));
        se_v32bit(stream, &(seqParam->offset_for_non_ref_pic));
        se_v32bit(stream, &(seqParam->offset_for_top_to_bottom_field));
        ue_v(stream, &(seqParam->num_ref_frames_in_pic_order_cnt_cycle));

        for (i = 0; i < (int)(seqParam->num_ref_frames_in_pic_order_cnt_cycle); i++)
        {
            se_v32bit(stream, &(seqParam->offset_for_ref_frame[i]));
        }
    }

    ue_v(stream, &(seqParam->num_ref_frames));

    if (seqParam->num_ref_frames > 16)
    {
        return AVCDEC_FAIL;
    }

    DEBUG_LOG(userData, AVC_LOGTYPE_INFO, "check point 2", seqParam->num_ref_frames, -1);

    BitstreamRead1Bit(stream, (uint*)&(seqParam->gaps_in_frame_num_value_allowed_flag));
    ue_v(stream, &(seqParam->pic_width_in_mbs_minus1));

    DEBUG_LOG(userData, AVC_LOGTYPE_INFO, "picwidth", seqParam->pic_width_in_mbs_minus1, -1);

    ue_v(stream, &(seqParam->pic_height_in_map_units_minus1));

    DEBUG_LOG(userData, AVC_LOGTYPE_INFO, "picwidth", seqParam->pic_height_in_map_units_minus1, -1);

    BitstreamRead1Bit(stream, (uint*)&(seqParam->frame_mbs_only_flag));

    seqParam->mb_adaptive_frame_field_flag = 0; /* default value */
    if (!seqParam->frame_mbs_only_flag)
    {
        BitstreamRead1Bit(stream, (uint*)&(seqParam->mb_adaptive_frame_field_flag));
    }

    DEBUG_LOG(userData, AVC_LOGTYPE_INFO, "check point 3", seqParam->frame_mbs_only_flag, -1);

    BitstreamRead1Bit(stream, (uint*)&(seqParam->direct_8x8_inference_flag));

    DEBUG_LOG(userData, AVC_LOGTYPE_INFO, "check point 4", seqParam->direct_8x8_inference_flag, -1);

    BitstreamRead1Bit(stream, (uint*)&(seqParam->frame_cropping_flag));
    seqParam->frame_crop_left_offset = 0;  /* default value */
    seqParam->frame_crop_right_offset = 0;/* default value */
    seqParam->frame_crop_top_offset = 0;/* default value */
    seqParam->frame_crop_bottom_offset = 0;/* default value */
    if (seqParam->frame_cropping_flag)
    {
        ue_v(stream, &(seqParam->frame_crop_left_offset));
        ue_v(stream, &(seqParam->frame_crop_right_offset));
        ue_v(stream, &(seqParam->frame_crop_top_offset));
        ue_v(stream, &(seqParam->frame_crop_bottom_offset));
    }

    DEBUG_LOG(userData, AVC_LOGTYPE_INFO, "check point 5", seqParam->frame_cropping_flag, -1);

    BitstreamRead1Bit(stream, (uint*)&(seqParam->vui_parameters_present_flag));
    if (seqParam->vui_parameters_present_flag)
    {
        status = vui_parameters(decvid, stream, seqParam);
        if (status != AVCDEC_SUCCESS)
        {
            return AVCDEC_FAIL;
        }
    }

    return status;
}


AVCDec_Status vui_parameters(AVCDecObject *decvid, AVCDecBitstream *stream, AVCSeqParamSet *currSPS)
{
    uint temp;
    uint temp32;
    uint aspect_ratio_idc, overscan_appopriate_flag, video_format, video_full_range_flag;
    /* aspect_ratio_info_present_flag */
    BitstreamRead1Bit(stream, &temp);
    if (temp)
    {
        BitstreamReadBits(stream, 8, &aspect_ratio_idc);
        if (aspect_ratio_idc == 255)
        {
            /* sar_width */
            BitstreamReadBits(stream, 16, &temp);
            /* sar_height */
            BitstreamReadBits(stream, 16, &temp);
        }
    }
    /* overscan_info_present */
    BitstreamRead1Bit(stream, &temp);
    if (temp)
    {
        BitstreamRead1Bit(stream, &overscan_appopriate_flag);
    }
    /* video_signal_type_present_flag */
    BitstreamRead1Bit(stream, &temp);
    if (temp)
    {
        BitstreamReadBits(stream, 3, &video_format);
        BitstreamRead1Bit(stream, &video_full_range_flag);
        /* colour_description_present_flag */
        BitstreamRead1Bit(stream, &temp);
        if (temp)
        {
            /* colour_primaries */
            BitstreamReadBits(stream, 8, &temp);
            /* transfer_characteristics */
            BitstreamReadBits(stream, 8, &temp);
            /* matrix coefficients */
            BitstreamReadBits(stream, 8, &temp);
        }
    }
    /*  chroma_loc_info_present_flag */
    BitstreamRead1Bit(stream, &temp);
    if (temp)
    {
        /*  chroma_sample_loc_type_top_field */
        ue_v(stream, &temp);
        /*  chroma_sample_loc_type_bottom_field */
        ue_v(stream, &temp);
    }

    /*  timing_info_present_flag*/
    BitstreamRead1Bit(stream, &temp);
    if (temp)
    {
        /*  num_unit_in_tick*/
        BitstreamReadBits(stream, 32, &temp32);
        /*  time_scale */
        BitstreamReadBits(stream, 32, &temp32);
        /*  fixed_frame_rate_flag */
        BitstreamRead1Bit(stream, &temp);
    }

    /*  nal_hrd_parameters_present_flag */
    BitstreamRead1Bit(stream, &temp);
    currSPS->vui_parameters.nal_hrd_parameters_present_flag = temp;
    if (temp)
    {
        hrd_parameters(decvid, stream, &(currSPS->vui_parameters.nal_hrd_parameters));
    }
    /*  vcl_hrd_parameters_present_flag*/
    BitstreamRead1Bit(stream, &temp);
    currSPS->vui_parameters.vcl_hrd_parameters_present_flag = temp;
    if (temp)
    {
        hrd_parameters(decvid, stream, &(currSPS->vui_parameters.vcl_hrd_parameters));
    }
    if (currSPS->vui_parameters.nal_hrd_parameters_present_flag || currSPS->vui_parameters.vcl_hrd_parameters_present_flag)
    {
        /*  low_delay_hrd_flag */
        BitstreamRead1Bit(stream, &temp);
    }
    /*  pic_struct_present_flag */
    BitstreamRead1Bit(stream, &temp);
    currSPS->vui_parameters.pic_struct_present_flag = temp;
    /*  bitstream_restriction_flag */
    BitstreamRead1Bit(stream, &temp);
    if (temp)
    {
        /*  motion_vectors_over_pic_boundaries_flag */
        BitstreamRead1Bit(stream, &temp);
        /*  max_bytes_per_pic_denom */
        ue_v(stream, &temp);
        /*  max_bits_per_mb_denom */
        ue_v(stream, &temp);
        /*  log2_max_mv_length_horizontal */
        ue_v(stream, &temp);
        /*  log2_max_mv_length_vertical */
        ue_v(stream, &temp);
        /*  num_reorder_frames */
        ue_v(stream, &temp);
        /*  max_dec_frame_buffering */
        ue_v(stream, &temp);
    }
    return AVCDEC_SUCCESS;
}
AVCDec_Status hrd_parameters(AVCDecObject *decvid, AVCDecBitstream *stream, AVCHRDParams *HRDParam)
{
    OSCL_UNUSED_ARG(decvid);
    uint temp;
    uint cpb_cnt_minus1;
    uint i;
    ue_v(stream, &cpb_cnt_minus1);
    HRDParam->cpb_cnt_minus1 = cpb_cnt_minus1;
    /*  bit_rate_scale */
    BitstreamReadBits(stream, 4, &temp);
    /*  cpb_size_scale */
    BitstreamReadBits(stream, 4, &temp);
    for (i = 0; i <= cpb_cnt_minus1; i++)
    {
        /*  bit_rate_value_minus1[i] */
        ue_v(stream, &temp);
        /*  cpb_size_value_minus1[i] */
        ue_v(stream, &temp);
        /*  cbr_flag[i] */
        ue_v(stream, &temp);
    }
    /*  initial_cpb_removal_delay_length_minus1 */
    BitstreamReadBits(stream, 5, &temp);
    /*  cpb_removal_delay_length_minus1 */
    BitstreamReadBits(stream, 5, &temp);
    HRDParam->cpb_removal_delay_length_minus1 = temp;
    /*  dpb_output_delay_length_minus1 */
    BitstreamReadBits(stream, 5, &temp);
    HRDParam->dpb_output_delay_length_minus1 = temp;
    /*  time_offset_length  */
    BitstreamReadBits(stream, 5, &temp);
    HRDParam->time_offset_length = temp;
    return AVCDEC_SUCCESS;
}


/** see subclause 7.4.2.2 */
AVCDec_Status DecodePPS(AVCDecObject *decvid, AVCCommonObj *video, AVCDecBitstream *stream)
{
    AVCPicParamSet *picParam;
    AVCDec_Status status;
    int i, iGroup, numBits;
    int PicWidthInMbs, PicHeightInMapUnits, PicSizeInMapUnits;
    uint pic_parameter_set_id, seq_parameter_set_id;
    void *userData = decvid->avcHandle->userData;
    AVCHandle *avcHandle = decvid->avcHandle;

    ue_v(stream, &pic_parameter_set_id);
    if (pic_parameter_set_id > 255)
    {
        return AVCDEC_FAIL;
    }

    ue_v(stream, &seq_parameter_set_id);

    if (seq_parameter_set_id > 31)
    {
        return AVCDEC_FAIL;
    }

    /* 2.1 if picParams[pic_param_set_id] is NULL, allocate it. */
    if (decvid->picParams[pic_parameter_set_id] == NULL)
    {
        decvid->picParams[pic_parameter_set_id] =
            (AVCPicParamSet*)avcHandle->CBAVC_Malloc(userData, sizeof(AVCPicParamSet), DEFAULT_ATTR);
        if (decvid->picParams[pic_parameter_set_id] == NULL)
        {
            return AVCDEC_MEMORY_FAIL;
        }

        decvid->picParams[pic_parameter_set_id]->slice_group_id = NULL;
    }

    video->currPicParams = picParam = decvid->picParams[pic_parameter_set_id];
    picParam->seq_parameter_set_id = seq_parameter_set_id;
    picParam->pic_parameter_set_id = pic_parameter_set_id;

    BitstreamRead1Bit(stream, (uint*)&(picParam->entropy_coding_mode_flag));
    if (picParam->entropy_coding_mode_flag)
    {
        status = AVCDEC_FAIL;
        goto clean_up;
    }
    BitstreamRead1Bit(stream, (uint*)&(picParam->pic_order_present_flag));
    ue_v(stream, &(picParam->num_slice_groups_minus1));

    if (picParam->num_slice_groups_minus1 > MAX_NUM_SLICE_GROUP - 1)
    {
        status = AVCDEC_FAIL;
        goto clean_up;
    }

    picParam->slice_group_change_rate_minus1 = 0; /* default value */
    if (picParam->num_slice_groups_minus1 > 0)
    {
        ue_v(stream, &(picParam->slice_group_map_type));
        if (picParam->slice_group_map_type == 0)
        {
            for (iGroup = 0; iGroup <= (int)picParam->num_slice_groups_minus1; iGroup++)
            {
                ue_v(stream, &(picParam->run_length_minus1[iGroup]));
            }
        }
        else if (picParam->slice_group_map_type == 2)
        {   // MC_CHECK  <= or <
            for (iGroup = 0; iGroup < (int)picParam->num_slice_groups_minus1; iGroup++)
            {
                ue_v(stream, &(picParam->top_left[iGroup]));
                ue_v(stream, &(picParam->bottom_right[iGroup]));
            }
        }
        else if (picParam->slice_group_map_type == 3 ||
                 picParam->slice_group_map_type == 4 ||
                 picParam->slice_group_map_type == 5)
        {
            BitstreamRead1Bit(stream, (uint*)&(picParam->slice_group_change_direction_flag));
            ue_v(stream, &(picParam->slice_group_change_rate_minus1));
        }
        else if (picParam->slice_group_map_type == 6)
        {
            ue_v(stream, &(picParam->pic_size_in_map_units_minus1));

            numBits = 0;/* ceil(log2(num_slice_groups_minus1+1)) bits */
            i = picParam->num_slice_groups_minus1;
            while (i > 0)
            {
                numBits++;
                i >>= 1;
            }

            i = picParam->seq_parameter_set_id;
            if (decvid->seqParams[i] == NULL)
            {
                status = AVCDEC_FAIL;
                goto clean_up;
            }


            PicWidthInMbs = decvid->seqParams[i]->pic_width_in_mbs_minus1 + 1;
            PicHeightInMapUnits = decvid->seqParams[i]->pic_height_in_map_units_minus1 + 1 ;
            PicSizeInMapUnits = PicWidthInMbs * PicHeightInMapUnits ;

            /* information has to be consistent with the seq_param */
            if ((int)picParam->pic_size_in_map_units_minus1 != PicSizeInMapUnits - 1)
            {
                status = AVCDEC_FAIL;
                goto clean_up;
            }

            if (picParam->slice_group_id)
            {
                avcHandle->CBAVC_Free(userData, (int)picParam->slice_group_id);
            }
            picParam->slice_group_id = (uint*)avcHandle->CBAVC_Malloc(userData, sizeof(uint) * PicSizeInMapUnits, DEFAULT_ATTR);
            if (picParam->slice_group_id == NULL)
            {
                status =  AVCDEC_MEMORY_FAIL;
                goto clean_up;
            }

            for (i = 0; i < PicSizeInMapUnits; i++)
            {
                BitstreamReadBits(stream, numBits, &(picParam->slice_group_id[i]));
            }
        }

    }

    ue_v(stream, &(picParam->num_ref_idx_l0_active_minus1));
    if (picParam->num_ref_idx_l0_active_minus1 > 31)
    {
        status = AVCDEC_FAIL; /* out of range */
        goto clean_up;
    }

    ue_v(stream, &(picParam->num_ref_idx_l1_active_minus1));
    if (picParam->num_ref_idx_l1_active_minus1 > 31)
    {
        status = AVCDEC_FAIL; /* out of range */
        goto clean_up;
    }

    BitstreamRead1Bit(stream, (uint*)&(picParam->weighted_pred_flag));
    BitstreamReadBits(stream, 2, &(picParam->weighted_bipred_idc));
    if (picParam->weighted_bipred_idc > 2)
    {
        status = AVCDEC_FAIL; /* out of range */
        goto clean_up;
    }

    se_v(stream, &(picParam->pic_init_qp_minus26));
    if (picParam->pic_init_qp_minus26 < -26 || picParam->pic_init_qp_minus26 > 25)
    {
        status = AVCDEC_FAIL; /* out of range */
        goto clean_up;
    }

    se_v(stream, &(picParam->pic_init_qs_minus26));
    if (picParam->pic_init_qs_minus26 < -26 || picParam->pic_init_qs_minus26 > 25)
    {
        status = AVCDEC_FAIL; /* out of range */
        goto clean_up;
    }

    se_v(stream, &(picParam->chroma_qp_index_offset));
    if (picParam->chroma_qp_index_offset < -12 || picParam->chroma_qp_index_offset > 12)
    {
        status = AVCDEC_FAIL; /* out of range */
        status = AVCDEC_FAIL; /* out of range */
        goto clean_up;
    }

    BitstreamReadBits(stream, 3, &pic_parameter_set_id);
    picParam->deblocking_filter_control_present_flag = pic_parameter_set_id >> 2;
    picParam->constrained_intra_pred_flag = (pic_parameter_set_id >> 1) & 1;
    picParam->redundant_pic_cnt_present_flag = pic_parameter_set_id & 1;

    return AVCDEC_SUCCESS;
clean_up:
    if (decvid->picParams[pic_parameter_set_id])
    {
        if (picParam->slice_group_id)
        {
            avcHandle->CBAVC_Free(userData, (int)picParam->slice_group_id);
        }
        decvid->picParams[pic_parameter_set_id]->slice_group_id = NULL;
        avcHandle->CBAVC_Free(userData, (int)decvid->picParams[pic_parameter_set_id]);
        decvid->picParams[pic_parameter_set_id] = NULL;
        return status;
    }
    return AVCDEC_SUCCESS;
}


/* FirstPartOfSliceHeader();
    RestOfSliceHeader() */
/** see subclause 7.4.3 */
AVCDec_Status DecodeSliceHeader(AVCDecObject *decvid, AVCCommonObj *video, AVCDecBitstream *stream)
{
    AVCSliceHeader *sliceHdr = video->sliceHdr;
    AVCPicParamSet *currPPS;
    AVCSeqParamSet *currSPS;
    AVCDec_Status status;
    uint idr_pic_id;
    int slice_type, temp, i;

    ue_v(stream, &(sliceHdr->first_mb_in_slice));
    ue_v(stream, (uint*)&slice_type);

    if (sliceHdr->first_mb_in_slice != 0)
    {
        if ((int)sliceHdr->slice_type >= 5 && slice_type != (int)sliceHdr->slice_type - 5)
        {
            return AVCDEC_FAIL; /* slice type doesn't follow the first slice in the picture */
        }
    }
    sliceHdr->slice_type = (AVCSliceType) slice_type;
    if (slice_type > 4)
    {
        slice_type -= 5;
    }

    if (slice_type == 1 || slice_type > 2)
    {
        return AVCDEC_FAIL;
    }

    video->slice_type = (AVCSliceType) slice_type;

    ue_v(stream, &(sliceHdr->pic_parameter_set_id));
    /* end FirstPartSliceHeader() */
    /* begin RestOfSliceHeader() */
    /* after getting pic_parameter_set_id, we have to load corresponding SPS and PPS */
    if (sliceHdr->pic_parameter_set_id > 255)
    {
        return AVCDEC_FAIL;
    }

    if (decvid->picParams[sliceHdr->pic_parameter_set_id] == NULL)
        return AVCDEC_FAIL; /* PPS doesn't exist */

    currPPS = video->currPicParams = decvid->picParams[sliceHdr->pic_parameter_set_id];

    if (decvid->seqParams[currPPS->seq_parameter_set_id] == NULL)
        return AVCDEC_FAIL; /* SPS doesn't exist */

    currSPS = video->currSeqParams = decvid->seqParams[currPPS->seq_parameter_set_id];

    if (currPPS->seq_parameter_set_id != video->seq_parameter_set_id)
    {
        video->seq_parameter_set_id = currPPS->seq_parameter_set_id;
        status = (AVCDec_Status)AVCConfigureSequence(decvid->avcHandle, video, false);
        if (status != AVCDEC_SUCCESS)
            return status;
        video->level_idc = currSPS->level_idc;
    }

    /* derived variables from SPS */
    video->MaxFrameNum = 1 << (currSPS->log2_max_frame_num_minus4 + 4);
    // MC_OPTIMIZE
    video->PicWidthInMbs = currSPS->pic_width_in_mbs_minus1 + 1;
    video->PicWidthInSamplesL = video->PicWidthInMbs * 16 ;
    video->PicWidthInSamplesC = video->PicWidthInMbs * 8 ;
    video->PicHeightInMapUnits = currSPS->pic_height_in_map_units_minus1 + 1 ;
    video->PicSizeInMapUnits = video->PicWidthInMbs * video->PicHeightInMapUnits ;
    video->FrameHeightInMbs = (2 - currSPS->frame_mbs_only_flag) * video->PicHeightInMapUnits ;

    /* derived from PPS */
    video->SliceGroupChangeRate = currPPS->slice_group_change_rate_minus1 + 1;

    /* then we can continue decoding slice header */

    BitstreamReadBits(stream, currSPS->log2_max_frame_num_minus4 + 4, &(sliceHdr->frame_num));

    if (video->currFS == NULL && sliceHdr->frame_num != 0)
    {
        video->prevFrameNum = video->PrevRefFrameNum = sliceHdr->frame_num - 1;
    }

    if (!currSPS->frame_mbs_only_flag)
    {
        BitstreamRead1Bit(stream, &(sliceHdr->field_pic_flag));
        if (sliceHdr->field_pic_flag)
        {
            return AVCDEC_FAIL;
        }
    }

    /* derived variables from slice header*/
    video->PicHeightInMbs = video->FrameHeightInMbs;
    video->PicHeightInSamplesL = video->PicHeightInMbs * 16;
    video->PicHeightInSamplesC = video->PicHeightInMbs * 8;
    video->PicSizeInMbs = video->PicWidthInMbs * video->PicHeightInMbs;

    if (sliceHdr->first_mb_in_slice >= video->PicSizeInMbs)
    {
        return AVCDEC_FAIL;
    }
    video->MaxPicNum = video->MaxFrameNum;
    video->CurrPicNum = sliceHdr->frame_num;


    if (video->nal_unit_type == AVC_NALTYPE_IDR)
    {
        if (sliceHdr->frame_num != 0)
        {
            return AVCDEC_FAIL;
        }
        ue_v(stream, &idr_pic_id);
    }

    sliceHdr->delta_pic_order_cnt_bottom = 0; /* default value */
    sliceHdr->delta_pic_order_cnt[0] = 0; /* default value */
    sliceHdr->delta_pic_order_cnt[1] = 0; /* default value */
    if (currSPS->pic_order_cnt_type == 0)
    {
        BitstreamReadBits(stream, currSPS->log2_max_pic_order_cnt_lsb_minus4 + 4,
                          &(sliceHdr->pic_order_cnt_lsb));
        video->MaxPicOrderCntLsb =  1 << (currSPS->log2_max_pic_order_cnt_lsb_minus4 + 4);
        if (sliceHdr->pic_order_cnt_lsb > video->MaxPicOrderCntLsb - 1)
            return AVCDEC_FAIL; /* out of range */

        if (currPPS->pic_order_present_flag)
        {
            se_v32bit(stream, &(sliceHdr->delta_pic_order_cnt_bottom));
        }
    }
    if (currSPS->pic_order_cnt_type == 1 && !currSPS->delta_pic_order_always_zero_flag)
    {
        se_v32bit(stream, &(sliceHdr->delta_pic_order_cnt[0]));
        if (currPPS->pic_order_present_flag)
        {
            se_v32bit(stream, &(sliceHdr->delta_pic_order_cnt[1]));
        }
    }

    sliceHdr->redundant_pic_cnt = 0; /* default value */
    if (currPPS->redundant_pic_cnt_present_flag)
    {
        // MC_CHECK
        ue_v(stream, &(sliceHdr->redundant_pic_cnt));
        if (sliceHdr->redundant_pic_cnt > 127) /* out of range */
            return AVCDEC_FAIL;

        if (sliceHdr->redundant_pic_cnt > 0) /* redundant picture */
            return AVCDEC_FAIL; /* not supported */
    }
    sliceHdr->num_ref_idx_l0_active_minus1 = currPPS->num_ref_idx_l0_active_minus1;
    sliceHdr->num_ref_idx_l1_active_minus1 = currPPS->num_ref_idx_l1_active_minus1;

    if (slice_type == AVC_P_SLICE)
    {
        BitstreamRead1Bit(stream, &(sliceHdr->num_ref_idx_active_override_flag));
        if (sliceHdr->num_ref_idx_active_override_flag)
        {
            ue_v(stream, &(sliceHdr->num_ref_idx_l0_active_minus1));
        }
        else  /* the following condition is not allowed if the flag is zero */
        {
            if ((slice_type == AVC_P_SLICE) && currPPS->num_ref_idx_l0_active_minus1 > 15)
            {
                return AVCDEC_FAIL; /* not allowed */
            }
        }
    }


    if (sliceHdr->num_ref_idx_l0_active_minus1 > 15 ||
            sliceHdr->num_ref_idx_l1_active_minus1 > 15)
    {
        return AVCDEC_FAIL; /* not allowed */
    }
    /* if MbaffFrameFlag =1,
    max value of index is num_ref_idx_l0_active_minus1 for frame MBs and
    2*sliceHdr->num_ref_idx_l0_active_minus1 + 1 for field MBs */

    /* ref_pic_list_reordering() */
    status = ref_pic_list_reordering(video, stream, sliceHdr, slice_type);
    if (status != AVCDEC_SUCCESS)
    {
        return status;
    }


    if (video->nal_ref_idc != 0)
    {
        dec_ref_pic_marking(video, stream, sliceHdr);
    }
    se_v(stream, &(sliceHdr->slice_qp_delta));

    video->QPy = 26 + currPPS->pic_init_qp_minus26 + sliceHdr->slice_qp_delta;
    if (video->QPy > 51 || video->QPy < 0)
    {
        video->QPy = AVC_CLIP3(0, 51, video->QPy);
//                  return AVCDEC_FAIL;
    }
    video->QPc = mapQPi2QPc[AVC_CLIP3(0, 51, video->QPy + video->currPicParams->chroma_qp_index_offset)];

    video->QPy_div_6 = (video->QPy * 43) >> 8;
    video->QPy_mod_6 = video->QPy - 6 * video->QPy_div_6;

    video->QPc_div_6 = (video->QPc * 43) >> 8;
    video->QPc_mod_6 = video->QPc - 6 * video->QPc_div_6;

    sliceHdr->slice_alpha_c0_offset_div2 = 0;
    sliceHdr->slice_beta_offset_div_2 = 0;
    sliceHdr->disable_deblocking_filter_idc = 0;
    video->FilterOffsetA = video->FilterOffsetB = 0;

    if (currPPS->deblocking_filter_control_present_flag)
    {
        ue_v(stream, &(sliceHdr->disable_deblocking_filter_idc));
        if (sliceHdr->disable_deblocking_filter_idc > 2)
        {
            return AVCDEC_FAIL; /* out of range */
        }
        if (sliceHdr->disable_deblocking_filter_idc != 1)
        {
            se_v(stream, &(sliceHdr->slice_alpha_c0_offset_div2));
            if (sliceHdr->slice_alpha_c0_offset_div2 < -6 ||
                    sliceHdr->slice_alpha_c0_offset_div2 > 6)
            {
                return AVCDEC_FAIL;
            }
            video->FilterOffsetA = sliceHdr->slice_alpha_c0_offset_div2 << 1;

            se_v(stream, &(sliceHdr->slice_beta_offset_div_2));
            if (sliceHdr->slice_beta_offset_div_2 < -6 ||
                    sliceHdr->slice_beta_offset_div_2 > 6)
            {
                return AVCDEC_FAIL;
            }
            video->FilterOffsetB = sliceHdr->slice_beta_offset_div_2 << 1;
        }
    }

    if (currPPS->num_slice_groups_minus1 > 0 && currPPS->slice_group_map_type >= 3
            && currPPS->slice_group_map_type <= 5)
    {
        /* Ceil(Log2(PicSizeInMapUnits/(float)SliceGroupChangeRate + 1)) */
        temp = video->PicSizeInMapUnits / video->SliceGroupChangeRate;
        if (video->PicSizeInMapUnits % video->SliceGroupChangeRate)
        {
            temp++;
        }
        i = 0;
        temp++;
        while (temp)
        {
            temp >>= 1;
            i++;
        }

        BitstreamReadBits(stream, i, &(sliceHdr->slice_group_change_cycle));
        video->MapUnitsInSliceGroup0 =
            AVC_MIN(sliceHdr->slice_group_change_cycle * video->SliceGroupChangeRate, video->PicSizeInMapUnits);
    }

    return AVCDEC_SUCCESS;
}


AVCDec_Status fill_frame_num_gap(AVCHandle *avcHandle, AVCCommonObj *video)
{
    AVCDec_Status status;
    int CurrFrameNum;
    int UnusedShortTermFrameNum;
    int tmp1 = video->sliceHdr->delta_pic_order_cnt[0];
    int tmp2 = video->sliceHdr->delta_pic_order_cnt[1];
    int tmp3 = video->CurrPicNum;
    int tmp4 = video->sliceHdr->adaptive_ref_pic_marking_mode_flag;
    UnusedShortTermFrameNum = (video->prevFrameNum + 1) % video->MaxFrameNum;
    CurrFrameNum = video->sliceHdr->frame_num;

    video->sliceHdr->delta_pic_order_cnt[0] = 0;
    video->sliceHdr->delta_pic_order_cnt[1] = 0;
    while (CurrFrameNum != UnusedShortTermFrameNum)
    {
        video->CurrPicNum = UnusedShortTermFrameNum;
        video->sliceHdr->frame_num = UnusedShortTermFrameNum;

        status = (AVCDec_Status)DPBInitBuffer(avcHandle, video);
        if (status != AVCDEC_SUCCESS)  /* no buffer available */
        {
            return status;
        }
        DecodePOC(video);
        DPBInitPic(video, UnusedShortTermFrameNum);


        video->currFS->PicOrderCnt = video->PicOrderCnt;
        video->currFS->FrameNum = video->sliceHdr->frame_num;

        /* initialize everything to zero */
        video->currFS->IsOutputted = 0x01;
        video->currFS->IsReference = 3;
        video->currFS->IsLongTerm = 0;
        video->currFS->frame.isReference = TRUE;
        video->currFS->frame.isLongTerm = FALSE;

        video->sliceHdr->adaptive_ref_pic_marking_mode_flag = 0;

        status = (AVCDec_Status)StorePictureInDPB(avcHandle, video);  // MC_CHECK check the return status
        if (status != AVCDEC_SUCCESS)
        {
            return AVCDEC_FAIL;
        }
        video->prevFrameNum = UnusedShortTermFrameNum;
        UnusedShortTermFrameNum = (UnusedShortTermFrameNum + 1) % video->MaxFrameNum;
    }
    video->sliceHdr->frame_num = CurrFrameNum;
    video->CurrPicNum = tmp3;
    video->sliceHdr->delta_pic_order_cnt[0] = tmp1;
    video->sliceHdr->delta_pic_order_cnt[1] = tmp2;
    video->sliceHdr->adaptive_ref_pic_marking_mode_flag = tmp4;
    return AVCDEC_SUCCESS;
}

/** see subclause 7.4.3.1 */
AVCDec_Status ref_pic_list_reordering(AVCCommonObj *video, AVCDecBitstream *stream, AVCSliceHeader *sliceHdr, int slice_type)
{
    int i;

    if (slice_type != AVC_I_SLICE)
    {
        BitstreamRead1Bit(stream, &(sliceHdr->ref_pic_list_reordering_flag_l0));
        if (sliceHdr->ref_pic_list_reordering_flag_l0)
        {
            i = 0;
            do
            {
                ue_v(stream, &(sliceHdr->reordering_of_pic_nums_idc_l0[i]));
                if (sliceHdr->reordering_of_pic_nums_idc_l0[i] == 0 ||
                        sliceHdr->reordering_of_pic_nums_idc_l0[i] == 1)
                {
                    ue_v(stream, &(sliceHdr->abs_diff_pic_num_minus1_l0[i]));
                    if (sliceHdr->reordering_of_pic_nums_idc_l0[i] == 0 &&
                            sliceHdr->abs_diff_pic_num_minus1_l0[i] > video->MaxPicNum / 2 - 1)
                    {
                        return AVCDEC_FAIL; /* out of range */
                    }
                    if (sliceHdr->reordering_of_pic_nums_idc_l0[i] == 1 &&
                            sliceHdr->abs_diff_pic_num_minus1_l0[i] > video->MaxPicNum / 2 - 2)
                    {
                        return AVCDEC_FAIL; /* out of range */
                    }
                }
                else if (sliceHdr->reordering_of_pic_nums_idc_l0[i] == 2)
                {
                    ue_v(stream, &(sliceHdr->long_term_pic_num_l0[i]));
                }
                i++;
            }
            while (sliceHdr->reordering_of_pic_nums_idc_l0[i-1] != 3
                    && i <= (int)sliceHdr->num_ref_idx_l0_active_minus1 + 1) ;
        }
    }
    return AVCDEC_SUCCESS;
}

/** see subclause 7.4.3.3 */
AVCDec_Status dec_ref_pic_marking(AVCCommonObj *video, AVCDecBitstream *stream, AVCSliceHeader *sliceHdr)
{
    int i;
    if (video->nal_unit_type == AVC_NALTYPE_IDR)
    {
        BitstreamRead1Bit(stream, &(sliceHdr->no_output_of_prior_pics_flag));
        BitstreamRead1Bit(stream, &(sliceHdr->long_term_reference_flag));
        if (sliceHdr->long_term_reference_flag == 0) /* used for short-term */
        {
            video->MaxLongTermFrameIdx = -1; /* no long-term frame indx */
        }
        else /* used for long-term */
        {
            video->MaxLongTermFrameIdx = 0;
            video->LongTermFrameIdx = 0;
        }
    }
    else
    {
        BitstreamRead1Bit(stream, &(sliceHdr->adaptive_ref_pic_marking_mode_flag));
        if (sliceHdr->adaptive_ref_pic_marking_mode_flag)
        {
            i = 0;
            do
            {
                ue_v(stream, &(sliceHdr->memory_management_control_operation[i]));
                if (sliceHdr->memory_management_control_operation[i] == 1 ||
                        sliceHdr->memory_management_control_operation[i] == 3)
                {
                    ue_v(stream, &(sliceHdr->difference_of_pic_nums_minus1[i]));
                }
                if (sliceHdr->memory_management_control_operation[i] == 2)
                {
                    ue_v(stream, &(sliceHdr->long_term_pic_num[i]));
                }
                if (sliceHdr->memory_management_control_operation[i] == 3 ||
                        sliceHdr->memory_management_control_operation[i] == 6)
                {
                    ue_v(stream, &(sliceHdr->long_term_frame_idx[i]));
                }
                if (sliceHdr->memory_management_control_operation[i] == 4)
                {
                    ue_v(stream, &(sliceHdr->max_long_term_frame_idx_plus1[i]));
                }
                i++;
            }
            while (sliceHdr->memory_management_control_operation[i-1] != 0 && i < MAX_DEC_REF_PIC_MARKING);
            if (i >= MAX_DEC_REF_PIC_MARKING)
            {
                return AVCDEC_FAIL; /* we're screwed!!, not enough memory */
            }
        }
    }

    return AVCDEC_SUCCESS;
}

/* see subclause 8.2.1 Decoding process for picture order count. */
AVCDec_Status DecodePOC(AVCCommonObj *video)
{
    AVCSeqParamSet *currSPS = video->currSeqParams;
    AVCSliceHeader *sliceHdr = video->sliceHdr;
    int i;

    switch (currSPS->pic_order_cnt_type)
    {
        case 0: /* POC MODE 0 , subclause 8.2.1.1 */
            if (video->nal_unit_type == AVC_NALTYPE_IDR)
            {
                video->prevPicOrderCntMsb = 0;
                video->prevPicOrderCntLsb = 0;
            }

            /* Calculate the MSBs of current picture */
            if (sliceHdr->pic_order_cnt_lsb  <  video->prevPicOrderCntLsb  &&
                    (video->prevPicOrderCntLsb - sliceHdr->pic_order_cnt_lsb)  >= (video->MaxPicOrderCntLsb / 2))
                video->PicOrderCntMsb = video->prevPicOrderCntMsb + video->MaxPicOrderCntLsb;
            else if (sliceHdr->pic_order_cnt_lsb  >  video->prevPicOrderCntLsb  &&
                     (sliceHdr->pic_order_cnt_lsb - video->prevPicOrderCntLsb)  > (video->MaxPicOrderCntLsb / 2))
                video->PicOrderCntMsb = video->prevPicOrderCntMsb - video->MaxPicOrderCntLsb;
            else
                video->PicOrderCntMsb = video->prevPicOrderCntMsb;

            /* JVT-I010 page 81 is different from JM7.3 */


            video->PicOrderCnt = video->TopFieldOrderCnt = video->PicOrderCntMsb + sliceHdr->pic_order_cnt_lsb;
            video->BottomFieldOrderCnt = video->TopFieldOrderCnt + sliceHdr->delta_pic_order_cnt_bottom;

            break;


        case 1: /* POC MODE 1, subclause 8.2.1.2 */
            /* calculate FrameNumOffset */
            if (video->nal_unit_type == AVC_NALTYPE_IDR)
            {
                video->prevFrameNumOffset = 0;
                video->FrameNumOffset = 0;
            }
            else if (video->prevFrameNum > sliceHdr->frame_num)
            {
                video->FrameNumOffset = video->prevFrameNumOffset + video->MaxFrameNum;
            }
            else
            {
                video->FrameNumOffset = video->prevFrameNumOffset;
            }
            /* calculate absFrameNum */
            if (currSPS->num_ref_frames_in_pic_order_cnt_cycle)
            {
                video->absFrameNum = video->FrameNumOffset + sliceHdr->frame_num;
            }
            else
            {
                video->absFrameNum = 0;
            }

            if (video->absFrameNum > 0 && video->nal_ref_idc == 0)
            {
                video->absFrameNum--;
            }

            /* derive picOrderCntCycleCnt and frameNumInPicOrderCntCycle */
            if (video->absFrameNum > 0)
            {
                video->picOrderCntCycleCnt = (video->absFrameNum - 1) / currSPS->num_ref_frames_in_pic_order_cnt_cycle;
                video->frameNumInPicOrderCntCycle = (video->absFrameNum - 1) % currSPS->num_ref_frames_in_pic_order_cnt_cycle;
            }
            /* derive expectedDeltaPerPicOrderCntCycle */
            video->expectedDeltaPerPicOrderCntCycle = 0;
            for (i = 0; i < (int)currSPS->num_ref_frames_in_pic_order_cnt_cycle; i++)
            {
                video->expectedDeltaPerPicOrderCntCycle += currSPS->offset_for_ref_frame[i];
            }
            /* derive expectedPicOrderCnt */
            if (video->absFrameNum)
            {
                video->expectedPicOrderCnt = video->picOrderCntCycleCnt * video->expectedDeltaPerPicOrderCntCycle;
                for (i = 0; i <= video->frameNumInPicOrderCntCycle; i++)
                {
                    video->expectedPicOrderCnt += currSPS->offset_for_ref_frame[i];
                }
            }
            else
            {
                video->expectedPicOrderCnt = 0;
            }

            if (video->nal_ref_idc == 0)
            {
                video->expectedPicOrderCnt += currSPS->offset_for_non_ref_pic;
            }
            /* derive TopFieldOrderCnt and BottomFieldOrderCnt */

            video->TopFieldOrderCnt = video->expectedPicOrderCnt + sliceHdr->delta_pic_order_cnt[0];
            video->BottomFieldOrderCnt = video->TopFieldOrderCnt + currSPS->offset_for_top_to_bottom_field + sliceHdr->delta_pic_order_cnt[1];

            video->PicOrderCnt = AVC_MIN(video->TopFieldOrderCnt, video->BottomFieldOrderCnt);


            break;


        case 2: /* POC MODE 2, subclause 8.2.1.3 */
            if (video->nal_unit_type == AVC_NALTYPE_IDR)
            {
                video->FrameNumOffset = 0;
            }
            else if (video->prevFrameNum > sliceHdr->frame_num)
            {
                video->FrameNumOffset = video->prevFrameNumOffset + video->MaxFrameNum;
            }
            else
            {
                video->FrameNumOffset = video->prevFrameNumOffset;
            }
            /* derive tempPicOrderCnt, we just use PicOrderCnt */
            if (video->nal_unit_type == AVC_NALTYPE_IDR)
            {
                video->PicOrderCnt = 0;
            }
            else if (video->nal_ref_idc == 0)
            {
                video->PicOrderCnt = 2 * (video->FrameNumOffset + sliceHdr->frame_num) - 1;
            }
            else
            {
                video->PicOrderCnt = 2 * (video->FrameNumOffset + sliceHdr->frame_num);
            }
            video->TopFieldOrderCnt = video->BottomFieldOrderCnt = video->PicOrderCnt;
            break;
        default:
            return AVCDEC_FAIL;
    }

    return AVCDEC_SUCCESS;
}


AVCDec_Status DecodeSEI(AVCDecObject *decvid, AVCDecBitstream *stream)
{
    OSCL_UNUSED_ARG(decvid);
    OSCL_UNUSED_ARG(stream);
    return AVCDEC_SUCCESS;
}

AVCDec_Status sei_payload(AVCDecObject *decvid, AVCDecBitstream *stream, uint payloadType, uint payloadSize)
{
    AVCDec_Status status = AVCDEC_SUCCESS;
    uint i;
    switch (payloadType)
    {
        case 0:
            /*  buffering period SEI */
            status = buffering_period(decvid, stream);
            break;
        case 1:
            /*  picture timing SEI */
            status = pic_timing(decvid, stream);
            break;
        case 2:

        case 3:

        case 4:

        case 5:

        case 8:

        case 9:

        case 10:

        case 11:

        case 12:

        case 13:

        case 14:

        case 15:

        case 16:

        case 17:
            for (i = 0; i < payloadSize; i++)
            {
                BitstreamFlushBits(stream, 8);
            }
            break;
        case 6:
            /*      recovery point SEI              */
            status = recovery_point(decvid, stream);
            break;
        case 7:
            /*      decoded reference picture marking repetition SEI */
            status = dec_ref_pic_marking_repetition(decvid, stream);
            break;

        case 18:
            /*      motion-constrained slice group set SEI */
            status = motion_constrained_slice_group_set(decvid, stream);
            break;
        default:
            /*          reserved_sei_message */
            for (i = 0; i < payloadSize; i++)
            {
                BitstreamFlushBits(stream, 8);
            }
            break;
    }
    BitstreamByteAlign(stream);
    return status;
}

AVCDec_Status buffering_period(AVCDecObject *decvid, AVCDecBitstream *stream)
{
    AVCSeqParamSet *currSPS;
    uint seq_parameter_set_id;
    uint temp;
    uint i;
    ue_v(stream, &seq_parameter_set_id);
    if (seq_parameter_set_id > 31)
    {
        return AVCDEC_FAIL;
    }

//  decvid->common->seq_parameter_set_id = seq_parameter_set_id;

    currSPS = decvid->seqParams[seq_parameter_set_id];
    if (currSPS->vui_parameters.nal_hrd_parameters_present_flag)
    {
        for (i = 0; i <= currSPS->vui_parameters.nal_hrd_parameters.cpb_cnt_minus1; i++)
        {
            /* initial_cpb_removal_delay[i] */
            BitstreamReadBits(stream, currSPS->vui_parameters.nal_hrd_parameters.cpb_removal_delay_length_minus1 + 1, &temp);
            /*initial _cpb_removal_delay_offset[i] */
            BitstreamReadBits(stream, currSPS->vui_parameters.nal_hrd_parameters.cpb_removal_delay_length_minus1 + 1, &temp);
        }
    }

    if (currSPS->vui_parameters.vcl_hrd_parameters_present_flag)
    {
        for (i = 0; i <= currSPS->vui_parameters.vcl_hrd_parameters.cpb_cnt_minus1; i++)
        {
            /* initial_cpb_removal_delay[i] */
            BitstreamReadBits(stream, currSPS->vui_parameters.vcl_hrd_parameters.cpb_removal_delay_length_minus1 + 1, &temp);
            /*initial _cpb_removal_delay_offset[i] */
            BitstreamReadBits(stream, currSPS->vui_parameters.vcl_hrd_parameters.cpb_removal_delay_length_minus1 + 1, &temp);
        }
    }

    return AVCDEC_SUCCESS;
}
AVCDec_Status pic_timing(AVCDecObject *decvid, AVCDecBitstream *stream)
{
    AVCSeqParamSet *currSPS;
    uint temp, NumClockTs = 0, time_offset_length = 24, full_timestamp_flag;
    uint i;

    currSPS = decvid->seqParams[decvid->common->seq_parameter_set_id];

    if (currSPS->vui_parameters.nal_hrd_parameters_present_flag)
    {
        BitstreamReadBits(stream, currSPS->vui_parameters.nal_hrd_parameters.cpb_removal_delay_length_minus1 + 1, &temp);
        BitstreamReadBits(stream, currSPS->vui_parameters.nal_hrd_parameters.dpb_output_delay_length_minus1 + 1, &temp);
        time_offset_length = currSPS->vui_parameters.nal_hrd_parameters.time_offset_length;
    }
    else if (currSPS->vui_parameters.vcl_hrd_parameters_present_flag)
    {
        BitstreamReadBits(stream, currSPS->vui_parameters.vcl_hrd_parameters.cpb_removal_delay_length_minus1 + 1, &temp);
        BitstreamReadBits(stream, currSPS->vui_parameters.vcl_hrd_parameters.dpb_output_delay_length_minus1 + 1, &temp);
        time_offset_length = currSPS->vui_parameters.vcl_hrd_parameters.time_offset_length;
    }

    if (currSPS->vui_parameters.pic_struct_present_flag)
    {
        /* pic_struct */
        BitstreamReadBits(stream, 4, &temp);

        switch (temp)
        {
            case 0:
            case 1:
            case 2:
                NumClockTs = 1;
                break;
            case 3:
            case 4:
            case 7:
                NumClockTs = 2;
                break;
            case 5:
            case 6:
            case 8:
                NumClockTs = 3;
                break;
            default:
                NumClockTs = 0;
                break;
        }

        for (i = 0; i < NumClockTs; i++)
        {
            /* clock_timestamp_flag[i] */
            BitstreamRead1Bit(stream, &temp);
            if (temp)
            {
                /* ct_type */
                BitstreamReadBits(stream, 2, &temp);
                /* nuit_field_based_flag */
                BitstreamRead1Bit(stream, &temp);
                /* counting_type        */
                BitstreamReadBits(stream, 5, &temp);
                /* full_timestamp_flag */
                BitstreamRead1Bit(stream, &temp);
                full_timestamp_flag = temp;
                /* discontinuity_flag */
                BitstreamRead1Bit(stream, &temp);
                /* cnt_dropped_flag */
                BitstreamRead1Bit(stream, &temp);
                /* n_frames           */
                BitstreamReadBits(stream, 8, &temp);


                if (full_timestamp_flag)
                {
                    /* seconds_value */
                    BitstreamReadBits(stream, 6, &temp);
                    /* minutes_value */
                    BitstreamReadBits(stream, 6, &temp);
                    /* hours_value */
                    BitstreamReadBits(stream, 5, &temp);
                }
                else
                {
                    /* seconds_flag  */
                    BitstreamRead1Bit(stream, &temp);
                    if (temp)
                    {
                        /* seconds_value */
                        BitstreamReadBits(stream, 6, &temp);
                        /* minutes_flag  */
                        BitstreamRead1Bit(stream, &temp);
                        if (temp)
                        {
                            /* minutes_value */
                            BitstreamReadBits(stream, 6, &temp);

                            /* hourss_flag  */
                            BitstreamRead1Bit(stream, &temp);

                            if (temp)
                            {
                                /* hours_value */
                                BitstreamReadBits(stream, 5, &temp);
                            }

                        }
                    }
                }

                if (time_offset_length)
                {
                    /* time_offset */
                    BitstreamReadBits(stream, time_offset_length, &temp);
                }
                else
                {
                    /* time_offset */
                    temp = 0;
                }
            }
        }
    }
    return AVCDEC_SUCCESS;
}
AVCDec_Status recovery_point(AVCDecObject *decvid, AVCDecBitstream *stream)
{
    OSCL_UNUSED_ARG(decvid);
    uint temp;
    /* recover_frame_cnt */
    ue_v(stream, &temp);
    /* exact_match_flag */
    BitstreamRead1Bit(stream, &temp);
    /* broken_link_flag */
    BitstreamRead1Bit(stream, &temp);
    /* changing slic_group_idc */
    BitstreamReadBits(stream, 2, &temp);
    return AVCDEC_SUCCESS;
}
AVCDec_Status dec_ref_pic_marking_repetition(AVCDecObject *decvid, AVCDecBitstream *stream)
{
    AVCSeqParamSet *currSPS;
    uint temp;
    currSPS = decvid->seqParams[decvid->common->seq_parameter_set_id];
    /* original_idr_flag */
    BitstreamRead1Bit(stream, &temp);
    /* original_frame_num */
    ue_v(stream, &temp);
    if (currSPS->frame_mbs_only_flag == 0)
    {
        /* original_field_pic_flag */
        BitstreamRead1Bit(stream, &temp);
        if (temp)
        {
            /* original_bottom_field_flag */
            BitstreamRead1Bit(stream, &temp);
        }
    }

    /*  dec_ref_pic_marking(video,stream,sliceHdr); */


    return AVCDEC_SUCCESS;
}
AVCDec_Status motion_constrained_slice_group_set(AVCDecObject *decvid, AVCDecBitstream *stream)
{
    OSCL_UNUSED_ARG(decvid);
    uint temp, i, numBits;
    /* num_slice_groups_in_set_minus1 */
    ue_v(stream, &temp);

    numBits = 0;/* ceil(log2(num_slice_groups_minus1+1)) bits */
    i = temp;
    while (i > 0)
    {
        numBits++;
        i >>= 1;
    }
    for (i = 0; i <= temp; i++)
    {
        /* slice_group_id */
        BitstreamReadBits(stream, numBits, &temp);
    }
    /* exact_sample_value_match_flag */
    BitstreamRead1Bit(stream, &temp);
    /* pan_scan_rect_flag */
    BitstreamRead1Bit(stream, &temp);
    if (temp)
    {
        /* pan_scan_rect_id */
        ue_v(stream, &temp);
    }

    return AVCDEC_SUCCESS;
}

