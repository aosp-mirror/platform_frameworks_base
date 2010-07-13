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
#include "avcenc_lib.h"
#include "avcenc_api.h"

/** see subclause 7.4.2.1 */
/* no need for checking the valid range , already done in SetEncodeParam(),
if we have to send another SPS, the ranges should be verified first before
users call PVAVCEncodeSPS() */
AVCEnc_Status EncodeSPS(AVCEncObject *encvid, AVCEncBitstream *stream)
{
    AVCCommonObj *video = encvid->common;
    AVCSeqParamSet *seqParam = video->currSeqParams;
    AVCVUIParams *vui = &(seqParam->vui_parameters);
    int i;
    AVCEnc_Status status = AVCENC_SUCCESS;

    //DEBUG_LOG(userData,AVC_LOGTYPE_INFO,"EncodeSPS",-1,-1);

    status = BitstreamWriteBits(stream, 8, seqParam->profile_idc);
    status = BitstreamWrite1Bit(stream, seqParam->constrained_set0_flag);
    status = BitstreamWrite1Bit(stream, seqParam->constrained_set1_flag);
    status = BitstreamWrite1Bit(stream, seqParam->constrained_set2_flag);
    status = BitstreamWrite1Bit(stream, seqParam->constrained_set3_flag);
    status = BitstreamWriteBits(stream, 4, 0);  /* forbidden zero bits */
    if (status != AVCENC_SUCCESS)  /* we can check after each write also */
    {
        return status;
    }

    status = BitstreamWriteBits(stream, 8, seqParam->level_idc);
    status = ue_v(stream, seqParam->seq_parameter_set_id);
    status = ue_v(stream, seqParam->log2_max_frame_num_minus4);
    status = ue_v(stream, seqParam->pic_order_cnt_type);
    if (status != AVCENC_SUCCESS)
    {
        return status;
    }

    if (seqParam->pic_order_cnt_type == 0)
    {
        status = ue_v(stream, seqParam->log2_max_pic_order_cnt_lsb_minus4);
    }
    else if (seqParam->pic_order_cnt_type == 1)
    {
        status = BitstreamWrite1Bit(stream, seqParam->delta_pic_order_always_zero_flag);
        status = se_v(stream, seqParam->offset_for_non_ref_pic); /* upto 32 bits */
        status = se_v(stream, seqParam->offset_for_top_to_bottom_field); /* upto 32 bits */
        status = ue_v(stream, seqParam->num_ref_frames_in_pic_order_cnt_cycle);

        for (i = 0; i < (int)(seqParam->num_ref_frames_in_pic_order_cnt_cycle); i++)
        {
            status = se_v(stream, seqParam->offset_for_ref_frame[i]); /* upto 32 bits */
        }
    }
    if (status != AVCENC_SUCCESS)
    {
        return status;
    }

    status = ue_v(stream, seqParam->num_ref_frames);
    status = BitstreamWrite1Bit(stream, seqParam->gaps_in_frame_num_value_allowed_flag);
    status = ue_v(stream, seqParam->pic_width_in_mbs_minus1);
    status = ue_v(stream, seqParam->pic_height_in_map_units_minus1);
    status = BitstreamWrite1Bit(stream, seqParam->frame_mbs_only_flag);
    if (status != AVCENC_SUCCESS)
    {
        return status;
    }
    /* if frame_mbs_only_flag is 0, then write, mb_adaptive_frame_field_frame here */

    status = BitstreamWrite1Bit(stream, seqParam->direct_8x8_inference_flag);
    status = BitstreamWrite1Bit(stream, seqParam->frame_cropping_flag);
    if (seqParam->frame_cropping_flag)
    {
        status = ue_v(stream, seqParam->frame_crop_left_offset);
        status = ue_v(stream, seqParam->frame_crop_right_offset);
        status = ue_v(stream, seqParam->frame_crop_top_offset);
        status = ue_v(stream, seqParam->frame_crop_bottom_offset);
    }
    if (status != AVCENC_SUCCESS)
    {
        return status;
    }

    status = BitstreamWrite1Bit(stream, seqParam->vui_parameters_present_flag);
    if (seqParam->vui_parameters_present_flag)
    {
        /* not supported */
        //return AVCENC_SPS_FAIL;
        EncodeVUI(stream, vui);
    }

    return status;
}


void EncodeVUI(AVCEncBitstream* stream, AVCVUIParams* vui)
{
    int temp;

    temp = vui->aspect_ratio_info_present_flag;
    BitstreamWrite1Bit(stream, temp);
    if (temp)
    {
        BitstreamWriteBits(stream, 8, vui->aspect_ratio_idc);
        if (vui->aspect_ratio_idc == 255)
        {
            BitstreamWriteBits(stream, 16, vui->sar_width);
            BitstreamWriteBits(stream, 16, vui->sar_height);
        }
    }
    temp = vui->overscan_info_present_flag;
    BitstreamWrite1Bit(stream, temp);
    if (temp)
    {
        BitstreamWrite1Bit(stream, vui->overscan_appropriate_flag);
    }
    temp = vui->video_signal_type_present_flag;
    BitstreamWrite1Bit(stream, temp);
    if (temp)
    {
        BitstreamWriteBits(stream, 3, vui->video_format);
        BitstreamWrite1Bit(stream, vui->video_full_range_flag);
        temp = vui->colour_description_present_flag;
        BitstreamWrite1Bit(stream, temp);
        if (temp)
        {
            BitstreamWriteBits(stream, 8, vui->colour_primaries);
            BitstreamWriteBits(stream, 8, vui->transfer_characteristics);
            BitstreamWriteBits(stream, 8, vui->matrix_coefficients);
        }
    }
    temp = vui->chroma_location_info_present_flag;
    BitstreamWrite1Bit(stream, temp);
    if (temp)
    {
        ue_v(stream, vui->chroma_sample_loc_type_top_field);
        ue_v(stream, vui->chroma_sample_loc_type_bottom_field);
    }

    temp = vui->timing_info_present_flag;
    BitstreamWrite1Bit(stream, temp);
    if (temp)
    {
        BitstreamWriteBits(stream, 32, vui->num_units_in_tick);
        BitstreamWriteBits(stream, 32, vui->time_scale);
        BitstreamWrite1Bit(stream, vui->fixed_frame_rate_flag);
    }

    temp = vui->nal_hrd_parameters_present_flag;
    BitstreamWrite1Bit(stream, temp);
    if (temp)
    {
        EncodeHRD(stream, &(vui->nal_hrd_parameters));
    }
    temp = vui->vcl_hrd_parameters_present_flag;
    BitstreamWrite1Bit(stream, temp);
    if (temp)
    {
        EncodeHRD(stream, &(vui->vcl_hrd_parameters));
    }
    if (vui->nal_hrd_parameters_present_flag || vui->vcl_hrd_parameters_present_flag)
    {
        BitstreamWrite1Bit(stream, vui->low_delay_hrd_flag);
    }
    BitstreamWrite1Bit(stream, vui->pic_struct_present_flag);
    temp = vui->bitstream_restriction_flag;
    BitstreamWrite1Bit(stream, temp);
    if (temp)
    {
        BitstreamWrite1Bit(stream, vui->motion_vectors_over_pic_boundaries_flag);
        ue_v(stream, vui->max_bytes_per_pic_denom);
        ue_v(stream, vui->max_bits_per_mb_denom);
        ue_v(stream, vui->log2_max_mv_length_horizontal);
        ue_v(stream, vui->log2_max_mv_length_vertical);
        ue_v(stream, vui->max_dec_frame_reordering);
        ue_v(stream, vui->max_dec_frame_buffering);
    }

    return ;
}


void EncodeHRD(AVCEncBitstream* stream, AVCHRDParams* hrd)
{
    int i;

    ue_v(stream, hrd->cpb_cnt_minus1);
    BitstreamWriteBits(stream, 4, hrd->bit_rate_scale);
    BitstreamWriteBits(stream, 4, hrd->cpb_size_scale);
    for (i = 0; i <= (int)hrd->cpb_cnt_minus1; i++)
    {
        ue_v(stream, hrd->bit_rate_value_minus1[i]);
        ue_v(stream, hrd->cpb_size_value_minus1[i]);
        ue_v(stream, hrd->cbr_flag[i]);
    }
    BitstreamWriteBits(stream, 5, hrd->initial_cpb_removal_delay_length_minus1);
    BitstreamWriteBits(stream, 5, hrd->cpb_removal_delay_length_minus1);
    BitstreamWriteBits(stream, 5, hrd->dpb_output_delay_length_minus1);
    BitstreamWriteBits(stream, 5, hrd->time_offset_length);

    return ;
}



/** see subclause 7.4.2.2 */
/* no need for checking the valid range , already done in SetEncodeParam().
If we have to send another SPS, the ranges should be verified first before
users call PVAVCEncodeSPS()*/
AVCEnc_Status EncodePPS(AVCEncObject *encvid, AVCEncBitstream *stream)
{
    AVCCommonObj *video = encvid->common;
    AVCEnc_Status status = AVCENC_SUCCESS;
    AVCPicParamSet *picParam = video->currPicParams;
    int i, iGroup, numBits;
    uint temp;

    status = ue_v(stream, picParam->pic_parameter_set_id);
    status = ue_v(stream, picParam->seq_parameter_set_id);
    status = BitstreamWrite1Bit(stream, picParam->entropy_coding_mode_flag);
    status = BitstreamWrite1Bit(stream, picParam->pic_order_present_flag);
    if (status != AVCENC_SUCCESS)
    {
        return status;
    }

    status = ue_v(stream, picParam->num_slice_groups_minus1);
    if (picParam->num_slice_groups_minus1 > 0)
    {
        status = ue_v(stream, picParam->slice_group_map_type);
        if (picParam->slice_group_map_type == 0)
        {
            for (iGroup = 0; iGroup <= (int)picParam->num_slice_groups_minus1; iGroup++)
            {
                status = ue_v(stream, picParam->run_length_minus1[iGroup]);
            }
        }
        else if (picParam->slice_group_map_type == 2)
        {
            for (iGroup = 0; iGroup < (int)picParam->num_slice_groups_minus1; iGroup++)
            {
                status = ue_v(stream, picParam->top_left[iGroup]);
                status = ue_v(stream, picParam->bottom_right[iGroup]);
            }
        }
        else if (picParam->slice_group_map_type == 3 ||
                 picParam->slice_group_map_type == 4 ||
                 picParam->slice_group_map_type == 5)
        {
            status = BitstreamWrite1Bit(stream, picParam->slice_group_change_direction_flag);
            status = ue_v(stream, picParam->slice_group_change_rate_minus1);
        }
        else /*if(picParam->slice_group_map_type == 6)*/
        {
            status = ue_v(stream, picParam->pic_size_in_map_units_minus1);

            numBits = 0;/* ceil(log2(num_slice_groups_minus1+1)) bits */
            i = picParam->num_slice_groups_minus1;
            while (i > 0)
            {
                numBits++;
                i >>= 1;
            }

            for (i = 0; i <= (int)picParam->pic_size_in_map_units_minus1; i++)
            {
                status = BitstreamWriteBits(stream, numBits, picParam->slice_group_id[i]);
            }
        }
    }
    if (status != AVCENC_SUCCESS)
    {
        return status;
    }

    status = ue_v(stream, picParam->num_ref_idx_l0_active_minus1);
    status = ue_v(stream, picParam->num_ref_idx_l1_active_minus1);
    status = BitstreamWrite1Bit(stream, picParam->weighted_pred_flag);
    status = BitstreamWriteBits(stream, 2, picParam->weighted_bipred_idc);
    if (status != AVCENC_SUCCESS)
    {
        return status;
    }

    status = se_v(stream, picParam->pic_init_qp_minus26);
    status = se_v(stream, picParam->pic_init_qs_minus26);
    status = se_v(stream, picParam->chroma_qp_index_offset);

    temp = picParam->deblocking_filter_control_present_flag << 2;
    temp |= (picParam->constrained_intra_pred_flag << 1);
    temp |= picParam->redundant_pic_cnt_present_flag;

    status = BitstreamWriteBits(stream, 3, temp);

    return status;
}

/** see subclause 7.4.3 */
AVCEnc_Status EncodeSliceHeader(AVCEncObject *encvid, AVCEncBitstream *stream)
{
    AVCCommonObj *video = encvid->common;
    AVCSliceHeader *sliceHdr = video->sliceHdr;
    AVCPicParamSet *currPPS = video->currPicParams;
    AVCSeqParamSet *currSPS = video->currSeqParams;
    AVCEnc_Status status = AVCENC_SUCCESS;
    int slice_type, temp, i;
    int num_bits;

    num_bits = (stream->write_pos << 3) - stream->bit_left;

    status = ue_v(stream, sliceHdr->first_mb_in_slice);

    slice_type = video->slice_type;

    if (video->mbNum == 0) /* first mb in frame */
    {
        status = ue_v(stream, sliceHdr->slice_type);
    }
    else
    {
        status = ue_v(stream, slice_type);
    }

    status = ue_v(stream, sliceHdr->pic_parameter_set_id);

    status = BitstreamWriteBits(stream, currSPS->log2_max_frame_num_minus4 + 4, sliceHdr->frame_num);

    if (status != AVCENC_SUCCESS)
    {
        return status;
    }
    /* if frame_mbs_only_flag is 0, encode field_pic_flag, bottom_field_flag here */

    if (video->nal_unit_type == AVC_NALTYPE_IDR)
    {
        status = ue_v(stream, sliceHdr->idr_pic_id);
    }

    if (currSPS->pic_order_cnt_type == 0)
    {
        status = BitstreamWriteBits(stream, currSPS->log2_max_pic_order_cnt_lsb_minus4 + 4,
                                    sliceHdr->pic_order_cnt_lsb);

        if (currPPS->pic_order_present_flag && !sliceHdr->field_pic_flag)
        {
            status = se_v(stream, sliceHdr->delta_pic_order_cnt_bottom); /* 32 bits */
        }
    }
    if (currSPS->pic_order_cnt_type == 1 && !currSPS->delta_pic_order_always_zero_flag)
    {
        status = se_v(stream, sliceHdr->delta_pic_order_cnt[0]);    /* 32 bits */
        if (currPPS->pic_order_present_flag && !sliceHdr->field_pic_flag)
        {
            status = se_v(stream, sliceHdr->delta_pic_order_cnt[1]); /* 32 bits */
        }
    }

    if (currPPS->redundant_pic_cnt_present_flag)
    {
        status = ue_v(stream, sliceHdr->redundant_pic_cnt);
    }

    if (slice_type == AVC_B_SLICE)
    {
        status = BitstreamWrite1Bit(stream, sliceHdr->direct_spatial_mv_pred_flag);
    }

    if (status != AVCENC_SUCCESS)
    {
        return status;
    }

    if (slice_type == AVC_P_SLICE || slice_type == AVC_SP_SLICE || slice_type == AVC_B_SLICE)
    {
        status = BitstreamWrite1Bit(stream, sliceHdr->num_ref_idx_active_override_flag);
        if (sliceHdr->num_ref_idx_active_override_flag)
        {
            /* we shouldn't enter this part at all */
            status = ue_v(stream, sliceHdr->num_ref_idx_l0_active_minus1);
            if (slice_type == AVC_B_SLICE)
            {
                status = ue_v(stream, sliceHdr->num_ref_idx_l1_active_minus1);
            }
        }
    }
    if (status != AVCENC_SUCCESS)
    {
        return status;
    }

    /* ref_pic_list_reordering() */
    status = ref_pic_list_reordering(video, stream, sliceHdr, slice_type);
    if (status != AVCENC_SUCCESS)
    {
        return status;
    }

    if ((currPPS->weighted_pred_flag && (slice_type == AVC_P_SLICE || slice_type == AVC_SP_SLICE)) ||
            (currPPS->weighted_bipred_idc == 1 && slice_type == AVC_B_SLICE))
    {
        //      pred_weight_table(); // not supported !!
        return AVCENC_PRED_WEIGHT_TAB_FAIL;
    }

    if (video->nal_ref_idc != 0)
    {
        status = dec_ref_pic_marking(video, stream, sliceHdr);
        if (status != AVCENC_SUCCESS)
        {
            return status;
        }
    }

    if (currPPS->entropy_coding_mode_flag && slice_type != AVC_I_SLICE && slice_type != AVC_SI_SLICE)
    {
        return AVCENC_CABAC_FAIL;
        /*      ue_v(stream,&(sliceHdr->cabac_init_idc));
                if(sliceHdr->cabac_init_idc > 2){
                    // not supported !!!!
                }*/
    }

    status = se_v(stream, sliceHdr->slice_qp_delta);
    if (status != AVCENC_SUCCESS)
    {
        return status;
    }

    if (slice_type == AVC_SP_SLICE || slice_type == AVC_SI_SLICE)
    {
        if (slice_type == AVC_SP_SLICE)
        {
            status = BitstreamWrite1Bit(stream, sliceHdr->sp_for_switch_flag);
            /* if sp_for_switch_flag is 0, P macroblocks in SP slice is decoded using
            SP decoding process for non-switching pictures in 8.6.1 */
            /* else, P macroblocks in SP slice is decoded using SP and SI decoding
            process for switching picture in 8.6.2 */
        }
        status = se_v(stream, sliceHdr->slice_qs_delta);
        if (status != AVCENC_SUCCESS)
        {
            return status;
        }
    }

    if (currPPS->deblocking_filter_control_present_flag)
    {

        status = ue_v(stream, sliceHdr->disable_deblocking_filter_idc);

        if (sliceHdr->disable_deblocking_filter_idc != 1)
        {
            status = se_v(stream, sliceHdr->slice_alpha_c0_offset_div2);

            status = se_v(stream, sliceHdr->slice_beta_offset_div_2);
        }
        if (status != AVCENC_SUCCESS)
        {
            return status;
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
        while (temp > 1)
        {
            temp >>= 1;
            i++;
        }

        BitstreamWriteBits(stream, i, sliceHdr->slice_group_change_cycle);
    }


    encvid->rateCtrl->NumberofHeaderBits += (stream->write_pos << 3) - stream->bit_left - num_bits;

    return AVCENC_SUCCESS;
}

/** see subclause 7.4.3.1 */
AVCEnc_Status ref_pic_list_reordering(AVCCommonObj *video, AVCEncBitstream *stream, AVCSliceHeader *sliceHdr, int slice_type)
{
    (void)(video);
    int i;
    AVCEnc_Status status = AVCENC_SUCCESS;

    if (slice_type != AVC_I_SLICE && slice_type != AVC_SI_SLICE)
    {
        status = BitstreamWrite1Bit(stream, sliceHdr->ref_pic_list_reordering_flag_l0);
        if (sliceHdr->ref_pic_list_reordering_flag_l0)
        {
            i = 0;
            do
            {
                status = ue_v(stream, sliceHdr->reordering_of_pic_nums_idc_l0[i]);
                if (sliceHdr->reordering_of_pic_nums_idc_l0[i] == 0 ||
                        sliceHdr->reordering_of_pic_nums_idc_l0[i] == 1)
                {
                    status = ue_v(stream, sliceHdr->abs_diff_pic_num_minus1_l0[i]);
                    /* this check should be in InitSlice(), if we ever use it */
                    /*if(sliceHdr->reordering_of_pic_nums_idc_l0[i] == 0 &&
                        sliceHdr->abs_diff_pic_num_minus1_l0[i] > video->MaxPicNum/2 -1)
                    {
                        return AVCENC_REF_PIC_REORDER_FAIL; // out of range
                    }
                    if(sliceHdr->reordering_of_pic_nums_idc_l0[i] == 1 &&
                        sliceHdr->abs_diff_pic_num_minus1_l0[i] > video->MaxPicNum/2 -2)
                    {
                        return AVCENC_REF_PIC_REORDER_FAIL; // out of range
                    }*/
                }
                else if (sliceHdr->reordering_of_pic_nums_idc_l0[i] == 2)
                {
                    status = ue_v(stream, sliceHdr->long_term_pic_num_l0[i]);
                }
                i++;
            }
            while (sliceHdr->reordering_of_pic_nums_idc_l0[i] != 3
                    && i <= (int)sliceHdr->num_ref_idx_l0_active_minus1 + 1) ;
        }
    }
    if (slice_type == AVC_B_SLICE)
    {
        status = BitstreamWrite1Bit(stream, sliceHdr->ref_pic_list_reordering_flag_l1);
        if (sliceHdr->ref_pic_list_reordering_flag_l1)
        {
            i = 0;
            do
            {
                status = ue_v(stream, sliceHdr->reordering_of_pic_nums_idc_l1[i]);
                if (sliceHdr->reordering_of_pic_nums_idc_l1[i] == 0 ||
                        sliceHdr->reordering_of_pic_nums_idc_l1[i] == 1)
                {
                    status = ue_v(stream, sliceHdr->abs_diff_pic_num_minus1_l1[i]);
                    /* This check should be in InitSlice() if we ever use it
                    if(sliceHdr->reordering_of_pic_nums_idc_l1[i] == 0 &&
                        sliceHdr->abs_diff_pic_num_minus1_l1[i] > video->MaxPicNum/2 -1)
                    {
                        return AVCENC_REF_PIC_REORDER_FAIL; // out of range
                    }
                    if(sliceHdr->reordering_of_pic_nums_idc_l1[i] == 1 &&
                        sliceHdr->abs_diff_pic_num_minus1_l1[i] > video->MaxPicNum/2 -2)
                    {
                        return AVCENC_REF_PIC_REORDER_FAIL; // out of range
                    }*/
                }
                else if (sliceHdr->reordering_of_pic_nums_idc_l1[i] == 2)
                {
                    status = ue_v(stream, sliceHdr->long_term_pic_num_l1[i]);
                }
                i++;
            }
            while (sliceHdr->reordering_of_pic_nums_idc_l1[i] != 3
                    && i <= (int)sliceHdr->num_ref_idx_l1_active_minus1 + 1) ;
        }
    }

    return status;
}

/** see subclause 7.4.3.3 */
AVCEnc_Status dec_ref_pic_marking(AVCCommonObj *video, AVCEncBitstream *stream, AVCSliceHeader *sliceHdr)
{
    int i;
    AVCEnc_Status status = AVCENC_SUCCESS;

    if (video->nal_unit_type == AVC_NALTYPE_IDR)
    {
        status = BitstreamWrite1Bit(stream, sliceHdr->no_output_of_prior_pics_flag);
        status = BitstreamWrite1Bit(stream, sliceHdr->long_term_reference_flag);
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
        status = BitstreamWrite1Bit(stream, sliceHdr->adaptive_ref_pic_marking_mode_flag); /* default to zero */
        if (sliceHdr->adaptive_ref_pic_marking_mode_flag)
        {
            i = 0;
            do
            {
                status = ue_v(stream, sliceHdr->memory_management_control_operation[i]);
                if (sliceHdr->memory_management_control_operation[i] == 1 ||
                        sliceHdr->memory_management_control_operation[i] == 3)
                {
                    status = ue_v(stream, sliceHdr->difference_of_pic_nums_minus1[i]);
                }
                if (sliceHdr->memory_management_control_operation[i] == 2)
                {
                    status = ue_v(stream, sliceHdr->long_term_pic_num[i]);
                }
                if (sliceHdr->memory_management_control_operation[i] == 3 ||
                        sliceHdr->memory_management_control_operation[i] == 6)
                {
                    status = ue_v(stream, sliceHdr->long_term_frame_idx[i]);
                }
                if (sliceHdr->memory_management_control_operation[i] == 4)
                {
                    status = ue_v(stream, sliceHdr->max_long_term_frame_idx_plus1[i]);
                }
                i++;
            }
            while (sliceHdr->memory_management_control_operation[i] != 0 && i < MAX_DEC_REF_PIC_MARKING);
            if (i >= MAX_DEC_REF_PIC_MARKING && sliceHdr->memory_management_control_operation[i] != 0)
            {
                return AVCENC_DEC_REF_PIC_MARK_FAIL; /* we're screwed!!, not enough memory */
            }
        }
    }

    return status;
}

/* see subclause 8.2.1 Decoding process for picture order count.
See also PostPOC() for initialization of some variables. */
AVCEnc_Status InitPOC(AVCEncObject *encvid)
{
    AVCCommonObj *video = encvid->common;
    AVCSeqParamSet *currSPS = video->currSeqParams;
    AVCSliceHeader *sliceHdr = video->sliceHdr;
    AVCFrameIO  *currInput = encvid->currInput;
    int i;

    switch (currSPS->pic_order_cnt_type)
    {
        case 0: /* POC MODE 0 , subclause 8.2.1.1 */
            /* encoding part */
            if (video->nal_unit_type == AVC_NALTYPE_IDR)
            {
                encvid->dispOrdPOCRef = currInput->disp_order;
            }
            while (currInput->disp_order < encvid->dispOrdPOCRef)
            {
                encvid->dispOrdPOCRef -= video->MaxPicOrderCntLsb;
            }
            sliceHdr->pic_order_cnt_lsb = currInput->disp_order - encvid->dispOrdPOCRef;
            while (sliceHdr->pic_order_cnt_lsb >= video->MaxPicOrderCntLsb)
            {
                sliceHdr->pic_order_cnt_lsb -= video->MaxPicOrderCntLsb;
            }
            /* decoding part */
            /* Calculate the MSBs of current picture */
            if (video->nal_unit_type == AVC_NALTYPE_IDR)
            {
                video->prevPicOrderCntMsb = 0;
                video->prevPicOrderCntLsb = 0;
            }
            if (sliceHdr->pic_order_cnt_lsb  <  video->prevPicOrderCntLsb  &&
                    (video->prevPicOrderCntLsb - sliceHdr->pic_order_cnt_lsb)  >= (video->MaxPicOrderCntLsb / 2))
                video->PicOrderCntMsb = video->prevPicOrderCntMsb + video->MaxPicOrderCntLsb;
            else if (sliceHdr->pic_order_cnt_lsb  >  video->prevPicOrderCntLsb  &&
                     (sliceHdr->pic_order_cnt_lsb - video->prevPicOrderCntLsb)  > (video->MaxPicOrderCntLsb / 2))
                video->PicOrderCntMsb = video->prevPicOrderCntMsb - video->MaxPicOrderCntLsb;
            else
                video->PicOrderCntMsb = video->prevPicOrderCntMsb;

            /* JVT-I010 page 81 is different from JM7.3 */
            if (!sliceHdr->field_pic_flag || !sliceHdr->bottom_field_flag)
            {
                video->PicOrderCnt = video->TopFieldOrderCnt = video->PicOrderCntMsb + sliceHdr->pic_order_cnt_lsb;
            }

            if (!sliceHdr->field_pic_flag)
            {
                video->BottomFieldOrderCnt = video->TopFieldOrderCnt + sliceHdr->delta_pic_order_cnt_bottom;
            }
            else if (sliceHdr->bottom_field_flag)
            {
                video->PicOrderCnt = video->BottomFieldOrderCnt = video->PicOrderCntMsb + sliceHdr->pic_order_cnt_lsb;
            }

            if (!sliceHdr->field_pic_flag)
            {
                video->PicOrderCnt = AVC_MIN(video->TopFieldOrderCnt, video->BottomFieldOrderCnt);
            }

            if (video->currPicParams->pic_order_present_flag && !sliceHdr->field_pic_flag)
            {
                sliceHdr->delta_pic_order_cnt_bottom = 0; /* defaulted to zero */
            }

            break;
        case 1: /* POC MODE 1, subclause 8.2.1.2 */
            /* calculate FrameNumOffset */
            if (video->nal_unit_type == AVC_NALTYPE_IDR)
            {
                encvid->dispOrdPOCRef = currInput->disp_order;  /* reset the reference point */
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
            /* derive expectedDeltaPerPicOrderCntCycle, this value can be computed up front. */
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
            /* encoding part */
            if (!currSPS->delta_pic_order_always_zero_flag)
            {
                sliceHdr->delta_pic_order_cnt[0] = currInput->disp_order - encvid->dispOrdPOCRef - video->expectedPicOrderCnt;

                if (video->currPicParams->pic_order_present_flag && !sliceHdr->field_pic_flag)
                {
                    sliceHdr->delta_pic_order_cnt[1] = sliceHdr->delta_pic_order_cnt[0]; /* should be calculated from currInput->bottom_field->disp_order */
                }
                else
                {
                    sliceHdr->delta_pic_order_cnt[1] = 0;
                }
            }
            else
            {
                sliceHdr->delta_pic_order_cnt[0] = sliceHdr->delta_pic_order_cnt[1] = 0;
            }

            if (sliceHdr->field_pic_flag == 0)
            {
                video->TopFieldOrderCnt = video->expectedPicOrderCnt + sliceHdr->delta_pic_order_cnt[0];
                video->BottomFieldOrderCnt = video->TopFieldOrderCnt + currSPS->offset_for_top_to_bottom_field + sliceHdr->delta_pic_order_cnt[1];

                video->PicOrderCnt = AVC_MIN(video->TopFieldOrderCnt, video->BottomFieldOrderCnt);
            }
            else if (sliceHdr->bottom_field_flag == 0)
            {
                video->TopFieldOrderCnt = video->expectedPicOrderCnt + sliceHdr->delta_pic_order_cnt[0];
                video->PicOrderCnt = video->TopFieldOrderCnt;
            }
            else
            {
                video->BottomFieldOrderCnt = video->expectedPicOrderCnt + currSPS->offset_for_top_to_bottom_field + sliceHdr->delta_pic_order_cnt[0];
                video->PicOrderCnt = video->BottomFieldOrderCnt;
            }
            break;


        case 2: /* POC MODE 2, subclause 8.2.1.3 */
            /* decoding order must be the same as display order */
            /* we don't check for that. The decoder will just output in decoding order. */
            /* Check for 2 consecutive non-reference frame */
            if (video->nal_ref_idc == 0)
            {
                if (encvid->dispOrdPOCRef == 1)
                {
                    return AVCENC_CONSECUTIVE_NONREF;
                }
                encvid->dispOrdPOCRef = 1;  /* act as a flag for non ref */
            }
            else
            {
                encvid->dispOrdPOCRef = 0;
            }


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
            /* derive TopFieldOrderCnt and BottomFieldOrderCnt */
            if (sliceHdr->field_pic_flag == 0)
            {
                video->TopFieldOrderCnt = video->BottomFieldOrderCnt = video->PicOrderCnt;
            }
            else if (sliceHdr->bottom_field_flag)
            {
                video->BottomFieldOrderCnt = video->PicOrderCnt;
            }
            else
            {
                video->TopFieldOrderCnt = video->PicOrderCnt;
            }
            break;
        default:
            return AVCENC_POC_FAIL;
    }

    return AVCENC_SUCCESS;
}

/** see subclause 8.2.1 */
AVCEnc_Status PostPOC(AVCCommonObj *video)
{
    AVCSliceHeader *sliceHdr = video->sliceHdr;
    AVCSeqParamSet *currSPS = video->currSeqParams;

    video->prevFrameNum = sliceHdr->frame_num;

    switch (currSPS->pic_order_cnt_type)
    {
        case 0: /* subclause 8.2.1.1 */
            if (video->mem_mgr_ctrl_eq_5)
            {
                video->prevPicOrderCntMsb = 0;
                video->prevPicOrderCntLsb = video->TopFieldOrderCnt;
            }
            else
            {
                video->prevPicOrderCntMsb = video->PicOrderCntMsb;
                video->prevPicOrderCntLsb = sliceHdr->pic_order_cnt_lsb;
            }
            break;
        case 1:  /* subclause 8.2.1.2 and 8.2.1.3 */
        case 2:
            if (video->mem_mgr_ctrl_eq_5)
            {
                video->prevFrameNumOffset = 0;
            }
            else
            {
                video->prevFrameNumOffset = video->FrameNumOffset;
            }
            break;
    }

    return AVCENC_SUCCESS;
}

