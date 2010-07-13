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

#define LOG2_MAX_FRAME_NUM_MINUS4   12   /* 12 default */
#define SLICE_GROUP_CHANGE_CYCLE    1    /* default */

/* initialized variables to be used in SPS*/
AVCEnc_Status  SetEncodeParam(AVCHandle* avcHandle, AVCEncParams* encParam,
                              void* extSPS, void* extPPS)
{
    AVCEncObject *encvid = (AVCEncObject*) avcHandle->AVCObject;
    AVCCommonObj *video = encvid->common;
    AVCSeqParamSet *seqParam = video->currSeqParams;
    AVCPicParamSet *picParam = video->currPicParams;
    AVCSliceHeader *sliceHdr = video->sliceHdr;
    AVCRateControl *rateCtrl = encvid->rateCtrl;
    AVCEnc_Status status;
    void *userData = avcHandle->userData;
    int ii, maxFrameNum;

    AVCSeqParamSet* extS = NULL;
    AVCPicParamSet* extP = NULL;

    if (extSPS) extS = (AVCSeqParamSet*) extSPS;
    if (extPPS) extP = (AVCPicParamSet*) extPPS;

    /* This part sets the default values of the encoding options this
    library supports in seqParam, picParam and sliceHdr structures and
    also copy the values from the encParam into the above 3 structures.

    Some parameters will be assigned later when we encode SPS or PPS such as
    the seq_parameter_id or pic_parameter_id. Also some of the slice parameters
    have to be re-assigned per slice basis such as frame_num, slice_type,
    first_mb_in_slice, pic_order_cnt_lsb, slice_qp_delta, slice_group_change_cycle */

    /* profile_idc, constrained_setx_flag and level_idc is set by VerifyProfile(),
    and VerifyLevel() functions later. */

    encvid->fullsearch_enable = encParam->fullsearch;

    encvid->outOfBandParamSet = ((encParam->out_of_band_param_set == AVC_ON) ? TRUE : FALSE);

    /* parameters derived from the the encParam that are used in SPS */
    if (extS)
    {
        video->MaxPicOrderCntLsb =  1 << (extS->log2_max_pic_order_cnt_lsb_minus4 + 4);
        video->PicWidthInMbs = extS->pic_width_in_mbs_minus1 + 1;
        video->PicHeightInMapUnits = extS->pic_height_in_map_units_minus1 + 1 ;
        video->FrameHeightInMbs = (2 - extS->frame_mbs_only_flag) * video->PicHeightInMapUnits ;
    }
    else
    {
        video->MaxPicOrderCntLsb =  1 << (encParam->log2_max_poc_lsb_minus_4 + 4);
        video->PicWidthInMbs = (encParam->width + 15) >> 4; /* round it to multiple of 16 */
        video->FrameHeightInMbs = (encParam->height + 15) >> 4; /* round it to multiple of 16 */
        video->PicHeightInMapUnits = video->FrameHeightInMbs;
    }

    video->PicWidthInSamplesL = video->PicWidthInMbs * 16 ;
    if (video->PicWidthInSamplesL + 32 > 0xFFFF)
    {
        return AVCENC_NOT_SUPPORTED; // we use 2-bytes for pitch
    }

    video->PicWidthInSamplesC = video->PicWidthInMbs * 8 ;
    video->PicHeightInMbs = video->FrameHeightInMbs;
    video->PicSizeInMapUnits = video->PicWidthInMbs * video->PicHeightInMapUnits ;
    video->PicHeightInSamplesL = video->PicHeightInMbs * 16;
    video->PicHeightInSamplesC = video->PicHeightInMbs * 8;
    video->PicSizeInMbs = video->PicWidthInMbs * video->PicHeightInMbs;

    if (!extS && !extP)
    {
        maxFrameNum = (encParam->idr_period == -1) ? (1 << 16) : encParam->idr_period;
        ii = 0;
        while (maxFrameNum > 0)
        {
            ii++;
            maxFrameNum >>= 1;
        }
        if (ii < 4) ii = 4;
        else if (ii > 16) ii = 16;

        seqParam->log2_max_frame_num_minus4 = ii - 4;//LOG2_MAX_FRAME_NUM_MINUS4; /* default */

        video->MaxFrameNum = 1 << ii; //(LOG2_MAX_FRAME_NUM_MINUS4 + 4); /* default */
        video->MaxPicNum = video->MaxFrameNum;

        /************* set the SPS *******************/
        seqParam->seq_parameter_set_id = 0; /* start with zero */
        /* POC */
        seqParam->pic_order_cnt_type = encParam->poc_type; /* POC type */
        if (encParam->poc_type == 0)
        {
            if (/*encParam->log2_max_poc_lsb_minus_4<0 || (no need, it's unsigned)*/
                encParam->log2_max_poc_lsb_minus_4 > 12)
            {
                return AVCENC_INVALID_POC_LSB;
            }
            seqParam->log2_max_pic_order_cnt_lsb_minus4 = encParam->log2_max_poc_lsb_minus_4;
        }
        else if (encParam->poc_type == 1)
        {
            seqParam->delta_pic_order_always_zero_flag = encParam->delta_poc_zero_flag;
            seqParam->offset_for_non_ref_pic = encParam->offset_poc_non_ref;
            seqParam->offset_for_top_to_bottom_field = encParam->offset_top_bottom;
            seqParam->num_ref_frames_in_pic_order_cnt_cycle = encParam->num_ref_in_cycle;
            if (encParam->offset_poc_ref == NULL)
            {
                return AVCENC_ENCPARAM_MEM_FAIL;
            }
            for (ii = 0; ii < encParam->num_ref_frame; ii++)
            {
                seqParam->offset_for_ref_frame[ii] = encParam->offset_poc_ref[ii];
            }
        }
        /* number of reference frame */
        if (encParam->num_ref_frame > 16 || encParam->num_ref_frame < 0)
        {
            return AVCENC_INVALID_NUM_REF;
        }
        seqParam->num_ref_frames = encParam->num_ref_frame; /* num reference frame range 0...16*/
        seqParam->gaps_in_frame_num_value_allowed_flag = FALSE;
        seqParam->pic_width_in_mbs_minus1 = video->PicWidthInMbs - 1;
        seqParam->pic_height_in_map_units_minus1 = video->PicHeightInMapUnits - 1;
        seqParam->frame_mbs_only_flag = TRUE;
        seqParam->mb_adaptive_frame_field_flag = FALSE;
        seqParam->direct_8x8_inference_flag = FALSE; /* default */
        seqParam->frame_cropping_flag = FALSE;
        seqParam->frame_crop_bottom_offset = 0;
        seqParam->frame_crop_left_offset = 0;
        seqParam->frame_crop_right_offset = 0;
        seqParam->frame_crop_top_offset = 0;
        seqParam->vui_parameters_present_flag = FALSE; /* default */
    }
    else if (extS) // use external SPS and PPS
    {
        seqParam->seq_parameter_set_id = extS->seq_parameter_set_id;
        seqParam->log2_max_frame_num_minus4 = extS->log2_max_frame_num_minus4;
        video->MaxFrameNum = 1 << (extS->log2_max_frame_num_minus4 + 4);
        video->MaxPicNum = video->MaxFrameNum;
        if (encParam->idr_period > (int)(video->MaxFrameNum) || (encParam->idr_period == -1))
        {
            encParam->idr_period = (int)video->MaxFrameNum;
        }

        seqParam->pic_order_cnt_type = extS->pic_order_cnt_type;
        if (seqParam->pic_order_cnt_type == 0)
        {
            if (/*extS->log2_max_pic_order_cnt_lsb_minus4<0 || (no need it's unsigned)*/
                extS->log2_max_pic_order_cnt_lsb_minus4 > 12)
            {
                return AVCENC_INVALID_POC_LSB;
            }
            seqParam->log2_max_pic_order_cnt_lsb_minus4 = extS->log2_max_pic_order_cnt_lsb_minus4;
        }
        else if (seqParam->pic_order_cnt_type == 1)
        {
            seqParam->delta_pic_order_always_zero_flag = extS->delta_pic_order_always_zero_flag;
            seqParam->offset_for_non_ref_pic = extS->offset_for_non_ref_pic;
            seqParam->offset_for_top_to_bottom_field = extS->offset_for_top_to_bottom_field;
            seqParam->num_ref_frames_in_pic_order_cnt_cycle = extS->num_ref_frames_in_pic_order_cnt_cycle;
            if (extS->offset_for_ref_frame == NULL)
            {
                return AVCENC_ENCPARAM_MEM_FAIL;
            }
            for (ii = 0; ii < (int) extS->num_ref_frames; ii++)
            {
                seqParam->offset_for_ref_frame[ii] = extS->offset_for_ref_frame[ii];
            }
        }
        /* number of reference frame */
        if (extS->num_ref_frames > 16 /*|| extS->num_ref_frames<0 (no need, it's unsigned)*/)
        {
            return AVCENC_INVALID_NUM_REF;
        }
        seqParam->num_ref_frames = extS->num_ref_frames; /* num reference frame range 0...16*/
        seqParam->gaps_in_frame_num_value_allowed_flag = extS->gaps_in_frame_num_value_allowed_flag;
        seqParam->pic_width_in_mbs_minus1 = extS->pic_width_in_mbs_minus1;
        seqParam->pic_height_in_map_units_minus1 = extS->pic_height_in_map_units_minus1;
        seqParam->frame_mbs_only_flag = extS->frame_mbs_only_flag;
        if (extS->frame_mbs_only_flag != TRUE)
        {
            return AVCENC_NOT_SUPPORTED;
        }
        seqParam->mb_adaptive_frame_field_flag = extS->mb_adaptive_frame_field_flag;
        if (extS->mb_adaptive_frame_field_flag != FALSE)
        {
            return AVCENC_NOT_SUPPORTED;
        }

        seqParam->direct_8x8_inference_flag = extS->direct_8x8_inference_flag;
        seqParam->frame_cropping_flag = extS->frame_cropping_flag ;
        if (extS->frame_cropping_flag != FALSE)
        {
            return AVCENC_NOT_SUPPORTED;
        }

        seqParam->frame_crop_bottom_offset = 0;
        seqParam->frame_crop_left_offset = 0;
        seqParam->frame_crop_right_offset = 0;
        seqParam->frame_crop_top_offset = 0;
        seqParam->vui_parameters_present_flag = extS->vui_parameters_present_flag;
        if (extS->vui_parameters_present_flag)
        {
            memcpy(&(seqParam->vui_parameters), &(extS->vui_parameters), sizeof(AVCVUIParams));
        }
    }
    else
    {
        return AVCENC_NOT_SUPPORTED;
    }

    /***************** now PPS ******************************/
    if (!extP && !extS)
    {
        picParam->pic_parameter_set_id = (uint)(-1); /* start with zero */
        picParam->seq_parameter_set_id = (uint)(-1); /* start with zero */
        picParam->entropy_coding_mode_flag = 0; /* default to CAVLC */
        picParam->pic_order_present_flag = 0; /* default for now, will need it for B-slice */
        /* FMO */
        if (encParam->num_slice_group < 1 || encParam->num_slice_group > MAX_NUM_SLICE_GROUP)
        {
            return AVCENC_INVALID_NUM_SLICEGROUP;
        }
        picParam->num_slice_groups_minus1 = encParam->num_slice_group - 1;

        if (picParam->num_slice_groups_minus1 > 0)
        {
            picParam->slice_group_map_type = encParam->fmo_type;
            switch (encParam->fmo_type)
            {
                case 0:
                    for (ii = 0; ii <= (int)picParam->num_slice_groups_minus1; ii++)
                    {
                        picParam->run_length_minus1[ii] = encParam->run_length_minus1[ii];
                    }
                    break;
                case 2:
                    for (ii = 0; ii < (int)picParam->num_slice_groups_minus1; ii++)
                    {
                        picParam->top_left[ii] = encParam->top_left[ii];
                        picParam->bottom_right[ii] = encParam->bottom_right[ii];
                    }
                    break;
                case 3:
                case 4:
                case 5:
                    if (encParam->change_dir_flag == AVC_ON)
                    {
                        picParam->slice_group_change_direction_flag = TRUE;
                    }
                    else
                    {
                        picParam->slice_group_change_direction_flag = FALSE;
                    }
                    if (/*encParam->change_rate_minus1 < 0 || (no need it's unsigned) */
                        encParam->change_rate_minus1 > video->PicSizeInMapUnits - 1)
                    {
                        return AVCENC_INVALID_CHANGE_RATE;
                    }
                    picParam->slice_group_change_rate_minus1 = encParam->change_rate_minus1;
                    video->SliceGroupChangeRate = picParam->slice_group_change_rate_minus1 + 1;
                    break;
                case 6:
                    picParam->pic_size_in_map_units_minus1 = video->PicSizeInMapUnits - 1;

                    /* allocate picParam->slice_group_id */
                    picParam->slice_group_id = (uint*)avcHandle->CBAVC_Malloc(userData, sizeof(uint) * video->PicSizeInMapUnits, DEFAULT_ATTR);
                    if (picParam->slice_group_id == NULL)
                    {
                        return AVCENC_MEMORY_FAIL;
                    }

                    if (encParam->slice_group == NULL)
                    {
                        return AVCENC_ENCPARAM_MEM_FAIL;
                    }
                    for (ii = 0; ii < (int)video->PicSizeInMapUnits; ii++)
                    {
                        picParam->slice_group_id[ii] = encParam->slice_group[ii];
                    }
                    break;
                default:
                    return AVCENC_INVALID_FMO_TYPE;
            }
        }
        picParam->num_ref_idx_l0_active_minus1 = encParam->num_ref_frame - 1; /* assume frame only */
        picParam->num_ref_idx_l1_active_minus1 = 0; /* default value */
        picParam->weighted_pred_flag = 0; /* no weighted prediction supported */
        picParam->weighted_bipred_idc = 0; /* range 0,1,2 */
        if (/*picParam->weighted_bipred_idc < 0 || (no need, it's unsigned) */
            picParam->weighted_bipred_idc > 2)
        {
            return AVCENC_WEIGHTED_BIPRED_FAIL;
        }
        picParam->pic_init_qp_minus26 = 0; /* default, will be changed at slice level anyway */
        if (picParam->pic_init_qp_minus26 < -26 || picParam->pic_init_qp_minus26 > 25)
        {
            return AVCENC_INIT_QP_FAIL; /* out of range */
        }
        picParam->pic_init_qs_minus26 = 0;
        if (picParam->pic_init_qs_minus26 < -26 || picParam->pic_init_qs_minus26 > 25)
        {
            return AVCENC_INIT_QS_FAIL; /* out of range */
        }

        picParam->chroma_qp_index_offset = 0; /* default to zero for now */
        if (picParam->chroma_qp_index_offset < -12 || picParam->chroma_qp_index_offset > 12)
        {
            return AVCENC_CHROMA_QP_FAIL; /* out of range */
        }
        /* deblocking */
        picParam->deblocking_filter_control_present_flag = (encParam->db_filter == AVC_ON) ? TRUE : FALSE ;
        /* constrained intra prediction */
        picParam->constrained_intra_pred_flag = (encParam->constrained_intra_pred == AVC_ON) ? TRUE : FALSE;
        picParam->redundant_pic_cnt_present_flag = 0; /* default */
    }
    else if (extP)// external PPS
    {
        picParam->pic_parameter_set_id = extP->pic_parameter_set_id - 1; /* to be increased by one */
        picParam->seq_parameter_set_id = extP->seq_parameter_set_id;
        picParam->entropy_coding_mode_flag = extP->entropy_coding_mode_flag;
        if (extP->entropy_coding_mode_flag != 0) /* default to CAVLC */
        {
            return AVCENC_NOT_SUPPORTED;
        }
        picParam->pic_order_present_flag = extP->pic_order_present_flag; /* default for now, will need it for B-slice */
        if (extP->pic_order_present_flag != 0)
        {
            return AVCENC_NOT_SUPPORTED;
        }
        /* FMO */
        if (/*(extP->num_slice_groups_minus1<0) || (no need it's unsigned) */
            (extP->num_slice_groups_minus1 > MAX_NUM_SLICE_GROUP - 1))
        {
            return AVCENC_INVALID_NUM_SLICEGROUP;
        }
        picParam->num_slice_groups_minus1 = extP->num_slice_groups_minus1;

        if (picParam->num_slice_groups_minus1 > 0)
        {
            picParam->slice_group_map_type = extP->slice_group_map_type;
            switch (extP->slice_group_map_type)
            {
                case 0:
                    for (ii = 0; ii <= (int)extP->num_slice_groups_minus1; ii++)
                    {
                        picParam->run_length_minus1[ii] = extP->run_length_minus1[ii];
                    }
                    break;
                case 2:
                    for (ii = 0; ii < (int)picParam->num_slice_groups_minus1; ii++)
                    {
                        picParam->top_left[ii] = extP->top_left[ii];
                        picParam->bottom_right[ii] = extP->bottom_right[ii];
                    }
                    break;
                case 3:
                case 4:
                case 5:
                    picParam->slice_group_change_direction_flag = extP->slice_group_change_direction_flag;
                    if (/*extP->slice_group_change_rate_minus1 < 0 || (no need, it's unsigned) */
                        extP->slice_group_change_rate_minus1 > video->PicSizeInMapUnits - 1)
                    {
                        return AVCENC_INVALID_CHANGE_RATE;
                    }
                    picParam->slice_group_change_rate_minus1 = extP->slice_group_change_rate_minus1;
                    video->SliceGroupChangeRate = picParam->slice_group_change_rate_minus1 + 1;
                    break;
                case 6:
                    if (extP->pic_size_in_map_units_minus1 != video->PicSizeInMapUnits - 1)
                    {
                        return AVCENC_NOT_SUPPORTED;
                    }

                    picParam->pic_size_in_map_units_minus1 = extP->pic_size_in_map_units_minus1;

                    /* allocate picParam->slice_group_id */
                    picParam->slice_group_id = (uint*)avcHandle->CBAVC_Malloc(userData, sizeof(uint) * video->PicSizeInMapUnits, DEFAULT_ATTR);
                    if (picParam->slice_group_id == NULL)
                    {
                        return AVCENC_MEMORY_FAIL;
                    }

                    if (extP->slice_group_id == NULL)
                    {
                        return AVCENC_ENCPARAM_MEM_FAIL;
                    }
                    for (ii = 0; ii < (int)video->PicSizeInMapUnits; ii++)
                    {
                        picParam->slice_group_id[ii] = extP->slice_group_id[ii];
                    }
                    break;
                default:
                    return AVCENC_INVALID_FMO_TYPE;
            }
        }
        picParam->num_ref_idx_l0_active_minus1 = extP->num_ref_idx_l0_active_minus1;
        picParam->num_ref_idx_l1_active_minus1 = extP->num_ref_idx_l1_active_minus1; /* default value */
        if (picParam->num_ref_idx_l1_active_minus1 != 0)
        {
            return AVCENC_NOT_SUPPORTED;
        }

        if (extP->weighted_pred_flag)
        {
            return AVCENC_NOT_SUPPORTED;
        }

        picParam->weighted_pred_flag = 0; /* no weighted prediction supported */
        picParam->weighted_bipred_idc = extP->weighted_bipred_idc; /* range 0,1,2 */
        if (/*picParam->weighted_bipred_idc < 0 || (no need, it's unsigned) */
            picParam->weighted_bipred_idc > 2)
        {
            return AVCENC_WEIGHTED_BIPRED_FAIL;
        }
        picParam->pic_init_qp_minus26 = extP->pic_init_qp_minus26; /* default, will be changed at slice level anyway */
        if (picParam->pic_init_qp_minus26 < -26 || picParam->pic_init_qp_minus26 > 25)
        {
            return AVCENC_INIT_QP_FAIL; /* out of range */
        }
        picParam->pic_init_qs_minus26 = extP->pic_init_qs_minus26;
        if (picParam->pic_init_qs_minus26 < -26 || picParam->pic_init_qs_minus26 > 25)
        {
            return AVCENC_INIT_QS_FAIL; /* out of range */
        }

        picParam->chroma_qp_index_offset = extP->chroma_qp_index_offset; /* default to zero for now */
        if (picParam->chroma_qp_index_offset < -12 || picParam->chroma_qp_index_offset > 12)
        {
            return AVCENC_CHROMA_QP_FAIL; /* out of range */
        }
        /* deblocking */
        picParam->deblocking_filter_control_present_flag = extP->deblocking_filter_control_present_flag;
        /* constrained intra prediction */
        picParam->constrained_intra_pred_flag = extP->constrained_intra_pred_flag;
        if (extP->redundant_pic_cnt_present_flag  != 0)
        {
            return AVCENC_NOT_SUPPORTED;
        }
        picParam->redundant_pic_cnt_present_flag = extP->redundant_pic_cnt_present_flag; /* default */
    }
    else
    {
        return AVCENC_NOT_SUPPORTED;
    }

    /****************** now set up some SliceHeader parameters ***********/
    if (picParam->deblocking_filter_control_present_flag == TRUE)
    {
        /* these values only present when db_filter is ON */
        if (encParam->disable_db_idc > 2)
        {
            return AVCENC_INVALID_DEBLOCK_IDC; /* out of range */
        }
        sliceHdr->disable_deblocking_filter_idc = encParam->disable_db_idc;

        if (encParam->alpha_offset < -6 || encParam->alpha_offset > 6)
        {
            return AVCENC_INVALID_ALPHA_OFFSET;
        }
        sliceHdr->slice_alpha_c0_offset_div2 = encParam->alpha_offset;

        if (encParam->beta_offset < -6 || encParam->beta_offset > 6)
        {
            return AVCENC_INVALID_BETA_OFFSET;
        }
        sliceHdr->slice_beta_offset_div_2 =  encParam->beta_offset;
    }
    if (encvid->outOfBandParamSet == TRUE)
    {
        sliceHdr->idr_pic_id = 0;
    }
    else
    {
        sliceHdr->idr_pic_id = (uint)(-1); /* start with zero */
    }
    sliceHdr->field_pic_flag = FALSE;
    sliceHdr->bottom_field_flag = FALSE;  /* won't be used anyway */
    video->MbaffFrameFlag = (seqParam->mb_adaptive_frame_field_flag && !sliceHdr->field_pic_flag);

    /* the rest will be set in InitSlice() */

    /* now the rate control and performance related parameters */
    rateCtrl->scdEnable = (encParam->auto_scd == AVC_ON) ? TRUE : FALSE;
    rateCtrl->idrPeriod = encParam->idr_period + 1;
    rateCtrl->intraMBRate = encParam->intramb_refresh;
    rateCtrl->dpEnable = (encParam->data_par == AVC_ON) ? TRUE : FALSE;

    rateCtrl->subPelEnable = (encParam->sub_pel == AVC_ON) ? TRUE : FALSE;
    rateCtrl->mvRange = encParam->search_range;

    rateCtrl->subMBEnable = (encParam->submb_pred == AVC_ON) ? TRUE : FALSE;
    rateCtrl->rdOptEnable = (encParam->rdopt_mode == AVC_ON) ? TRUE : FALSE;
    rateCtrl->bidirPred = (encParam->bidir_pred == AVC_ON) ? TRUE : FALSE;

    rateCtrl->rcEnable = (encParam->rate_control == AVC_ON) ? TRUE : FALSE;
    rateCtrl->initQP = encParam->initQP;
    rateCtrl->initQP = AVC_CLIP3(0, 51, rateCtrl->initQP);

    rateCtrl->bitRate = encParam->bitrate;
    rateCtrl->cpbSize = encParam->CPB_size;
    rateCtrl->initDelayOffset = (rateCtrl->bitRate * encParam->init_CBP_removal_delay / 1000);

    if (encParam->frame_rate == 0)
    {
        return AVCENC_INVALID_FRAMERATE;
    }

    rateCtrl->frame_rate = (OsclFloat)(encParam->frame_rate * 1.0 / 1000);
//  rateCtrl->srcInterval = encParam->src_interval;
    rateCtrl->first_frame = 1; /* set this flag for the first time */

    /* contrained_setx_flag will be set inside the VerifyProfile called below.*/
    if (!extS && !extP)
    {
        seqParam->profile_idc = encParam->profile;
        seqParam->constrained_set0_flag = FALSE;
        seqParam->constrained_set1_flag = FALSE;
        seqParam->constrained_set2_flag = FALSE;
        seqParam->constrained_set3_flag = FALSE;
        seqParam->level_idc = encParam->level;
    }
    else
    {
        seqParam->profile_idc = extS->profile_idc;
        seqParam->constrained_set0_flag = extS->constrained_set0_flag;
        seqParam->constrained_set1_flag = extS->constrained_set1_flag;
        seqParam->constrained_set2_flag = extS->constrained_set2_flag;
        seqParam->constrained_set3_flag = extS->constrained_set3_flag;
        seqParam->level_idc = extS->level_idc;
    }


    status = VerifyProfile(encvid, seqParam, picParam);
    if (status != AVCENC_SUCCESS)
    {
        return status;
    }

    status = VerifyLevel(encvid, seqParam, picParam);
    if (status != AVCENC_SUCCESS)
    {
        return status;
    }

    return AVCENC_SUCCESS;
}

/* verify the profile setting */
AVCEnc_Status VerifyProfile(AVCEncObject *encvid, AVCSeqParamSet *seqParam, AVCPicParamSet *picParam)
{
    AVCRateControl *rateCtrl = encvid->rateCtrl;
    AVCEnc_Status status = AVCENC_SUCCESS;

    if (seqParam->profile_idc == 0) /* find profile for this setting */
    {
        /* find the right profile for it */
        if (seqParam->direct_8x8_inference_flag == TRUE &&
                picParam->entropy_coding_mode_flag == FALSE &&
                picParam->num_slice_groups_minus1 <= 7 /*&&
            picParam->num_slice_groups_minus1>=0 (no need, it's unsigned) */)
        {
            seqParam->profile_idc = AVC_EXTENDED;
            seqParam->constrained_set2_flag = TRUE;
        }

        if (rateCtrl->dpEnable == FALSE &&
                picParam->num_slice_groups_minus1 == 0 &&
                picParam->redundant_pic_cnt_present_flag == FALSE)
        {
            seqParam->profile_idc = AVC_MAIN;
            seqParam->constrained_set1_flag = TRUE;
        }

        if (rateCtrl->bidirPred == FALSE &&
                rateCtrl->dpEnable == FALSE &&
                seqParam->frame_mbs_only_flag == TRUE &&
                picParam->weighted_pred_flag == FALSE &&
                picParam->weighted_bipred_idc == 0 &&
                picParam->entropy_coding_mode_flag == FALSE &&
                picParam->num_slice_groups_minus1 <= 7 /*&&
            picParam->num_slice_groups_minus1>=0 (no need, it's unsigned)*/)
        {
            seqParam->profile_idc = AVC_BASELINE;
            seqParam->constrained_set0_flag = TRUE;
        }

        if (seqParam->profile_idc == 0) /* still zero */
        {
            return AVCENC_PROFILE_NOT_SUPPORTED;
        }
    }

    /* check the list of supported profile by this library */
    switch (seqParam->profile_idc)
    {
        case AVC_BASELINE:
            if (rateCtrl->bidirPred == TRUE ||
                    rateCtrl->dpEnable == TRUE ||
                    seqParam->frame_mbs_only_flag != TRUE ||
                    picParam->weighted_pred_flag == TRUE ||
                    picParam->weighted_bipred_idc != 0 ||
                    picParam->entropy_coding_mode_flag == TRUE ||
                    picParam->num_slice_groups_minus1 > 7 /*||
            picParam->num_slice_groups_minus1<0 (no need, it's unsigned) */)
            {
                status = AVCENC_TOOLS_NOT_SUPPORTED;
            }
            break;

        case AVC_MAIN:
        case AVC_EXTENDED:
            status = AVCENC_PROFILE_NOT_SUPPORTED;
    }

    return status;
}

/* verify the level setting */
AVCEnc_Status VerifyLevel(AVCEncObject *encvid, AVCSeqParamSet *seqParam, AVCPicParamSet *picParam)
{
    (void)(picParam);

    AVCRateControl *rateCtrl = encvid->rateCtrl;
    AVCCommonObj *video = encvid->common;
    int mb_per_sec, ii;
    int lev_idx;
    int dpb_size;

    mb_per_sec = (int)(video->PicSizeInMbs * rateCtrl->frame_rate + 0.5);
    dpb_size = (seqParam->num_ref_frames * video->PicSizeInMbs * 3) >> 6;

    if (seqParam->level_idc == 0) /* find level for this setting */
    {
        for (ii = 0; ii < MAX_LEVEL_IDX; ii++)
        {
            if (mb_per_sec <= MaxMBPS[ii] &&
                    video->PicSizeInMbs <= (uint)MaxFS[ii] &&
                    rateCtrl->bitRate <= (int32)MaxBR[ii]*1000 &&
                    rateCtrl->cpbSize <= (int32)MaxCPB[ii]*1000 &&
                    rateCtrl->mvRange <= MaxVmvR[ii] &&
                    dpb_size <= MaxDPBX2[ii]*512)
            {
                seqParam->level_idc = mapIdx2Lev[ii];
                break;
            }
        }
        if (seqParam->level_idc == 0)
        {
            return AVCENC_LEVEL_NOT_SUPPORTED;
        }
    }

    /* check if this level is supported by this library */
    lev_idx = mapLev2Idx[seqParam->level_idc];
    if (seqParam->level_idc == AVC_LEVEL1_B)
    {
        seqParam->constrained_set3_flag = 1;
    }


    if (lev_idx == 255) /* not defined */
    {
        return AVCENC_LEVEL_NOT_SUPPORTED;
    }

    /* check if the encoding setting complies with the level */
    if (mb_per_sec > MaxMBPS[lev_idx] ||
            video->PicSizeInMbs > (uint)MaxFS[lev_idx] ||
            rateCtrl->bitRate > (int32)MaxBR[lev_idx]*1000 ||
            rateCtrl->cpbSize > (int32)MaxCPB[lev_idx]*1000 ||
            rateCtrl->mvRange > MaxVmvR[lev_idx])
    {
        return AVCENC_LEVEL_FAIL;
    }

    return AVCENC_SUCCESS;
}

/* initialize variables at the beginning of each frame */
/* determine the picture type */
/* encode POC */
/* maybe we should do more stuff here. MotionEstimation+SCD and generate a new SPS and PPS */
AVCEnc_Status InitFrame(AVCEncObject *encvid)
{
    AVCStatus ret;
    AVCEnc_Status status;
    AVCCommonObj *video = encvid->common;
    AVCSliceHeader *sliceHdr = video->sliceHdr;

    /* look for the next frame in coding_order and look for available picture
       in the DPB. Note, video->currFS->PicOrderCnt, currFS->FrameNum and currPic->PicNum
       are set to wrong number in this function (right for decoder). */
    if (video->nal_unit_type == AVC_NALTYPE_IDR)
    {
        // call init DPB in here.
        ret = AVCConfigureSequence(encvid->avcHandle, video, TRUE);
        if (ret != AVC_SUCCESS)
        {
            return AVCENC_FAIL;
        }
    }

    /* flexible macroblock ordering (every frame)*/
    /* populate video->mapUnitToSliceGroupMap and video->MbToSliceGroupMap */
    /* It changes once per each PPS. */
    FMOInit(video);

    ret = DPBInitBuffer(encvid->avcHandle, video); // get new buffer

    if (ret != AVC_SUCCESS)
    {
        return (AVCEnc_Status)ret; // AVCENC_PICTURE_READY, FAIL
    }

    DPBInitPic(video, 0); /* 0 is dummy */

    /************* determine picture type IDR or non-IDR ***********/
    video->currPicType = AVC_FRAME;
    video->slice_data_partitioning = FALSE;
    encvid->currInput->is_reference = 1; /* default to all frames */
    video->nal_ref_idc = 1;  /* need to set this for InitPOC */
    video->currPic->isReference = TRUE;

    /************* set frame_num ********************/
    if (video->nal_unit_type == AVC_NALTYPE_IDR)
    {
        video->prevFrameNum = video->MaxFrameNum;
        video->PrevRefFrameNum = 0;
        sliceHdr->frame_num = 0;
    }
    /* otherwise, it's set to previous reference frame access unit's frame_num in decoding order,
       see the end of PVAVCDecodeSlice()*/
    /* There's also restriction on the frame_num, see page 59 of JVT-I1010.doc. */
    /* Basically, frame_num can't be repeated unless it's opposite fields or non reference fields */
    else
    {
        sliceHdr->frame_num = (video->PrevRefFrameNum + 1) % video->MaxFrameNum;
    }
    video->CurrPicNum = sliceHdr->frame_num;  /* for field_pic_flag = 0 */
    //video->CurrPicNum = 2*sliceHdr->frame_num + 1; /* for field_pic_flag = 1 */

    /* assign pic_order_cnt, video->PicOrderCnt */
    status = InitPOC(encvid);
    if (status != AVCENC_SUCCESS)  /* incorrigable fail */
    {
        return status;
    }

    /* Initialize refListIdx for this picture */
    RefListInit(video);

    /************* motion estimation and scene analysis ************/
    // , to move this to MB-based MV search for comparison
    // use sub-optimal QP for mv search
    AVCMotionEstimation(encvid);  /* AVCENC_SUCCESS or AVCENC_NEW_IDR */

    /* after this point, the picture type will be fixed to either IDR or non-IDR */
    video->currFS->PicOrderCnt = video->PicOrderCnt;
    video->currFS->FrameNum = video->sliceHdr->frame_num;
    video->currPic->PicNum = video->CurrPicNum;
    video->mbNum = 0; /* start from zero MB */
    encvid->currSliceGroup = 0; /* start from slice group #0 */
    encvid->numIntraMB = 0; /* reset this counter */

    if (video->nal_unit_type == AVC_NALTYPE_IDR)
    {
        RCInitGOP(encvid);

        /* calculate picture QP */
        RCInitFrameQP(encvid);

        return AVCENC_NEW_IDR;
    }

    /* calculate picture QP */
    RCInitFrameQP(encvid); /* get QP after MV search */

    return AVCENC_SUCCESS;
}

/* initialize variables for this slice */
AVCEnc_Status InitSlice(AVCEncObject *encvid)
{
    AVCCommonObj *video = encvid->common;
    AVCSliceHeader *sliceHdr = video->sliceHdr;
    AVCPicParamSet *currPPS = video->currPicParams;
    AVCSeqParamSet *currSPS = video->currSeqParams;
    int slice_type = video->slice_type;

    sliceHdr->first_mb_in_slice = video->mbNum;
    if (video->mbNum) // not first slice of a frame
    {
        video->sliceHdr->slice_type = (AVCSliceType)slice_type;
    }

    /* sliceHdr->slice_type already set in InitFrame */

    sliceHdr->pic_parameter_set_id = video->currPicParams->pic_parameter_set_id;

    /* sliceHdr->frame_num already set in InitFrame */

    if (!currSPS->frame_mbs_only_flag)  /* we shouldn't need this check */
    {
        sliceHdr->field_pic_flag = sliceHdr->bottom_field_flag = FALSE;
        return AVCENC_TOOLS_NOT_SUPPORTED;
    }

    /* sliceHdr->idr_pic_id already set in PVAVCEncodeNAL

     sliceHdr->pic_order_cnt_lsb already set in InitFrame..InitPOC
     sliceHdr->delta_pic_order_cnt_bottom  already set in InitPOC

    sliceHdr->delta_pic_order_cnt[0] already set in InitPOC
    sliceHdr->delta_pic_order_cnt[1] already set in InitPOC
    */

    sliceHdr->redundant_pic_cnt = 0; /* default if(currPPS->redundant_pic_cnt_present_flag), range 0..127 */
    sliceHdr->direct_spatial_mv_pred_flag = 0; // default if(slice_type == AVC_B_SLICE)

    sliceHdr->num_ref_idx_active_override_flag = FALSE; /* default, if(slice_type== P,SP or B)*/
    sliceHdr->num_ref_idx_l0_active_minus1 = 0; /* default, if (num_ref_idx_active_override_flag) */
    sliceHdr->num_ref_idx_l1_active_minus1 = 0; /* default, if above and B_slice */
    /* the above 2 values range from 0..15 for frame picture and 0..31 for field picture */

    /* ref_pic_list_reordering(), currently we don't do anything */
    sliceHdr->ref_pic_list_reordering_flag_l0 = FALSE; /* default */
    sliceHdr->ref_pic_list_reordering_flag_l1 = FALSE; /* default */
    /* if the above are TRUE, some other params must be set */

    if ((currPPS->weighted_pred_flag && (slice_type == AVC_P_SLICE || slice_type == AVC_SP_SLICE)) ||
            (currPPS->weighted_bipred_idc == 1 && slice_type == AVC_B_SLICE))
    {
        //      pred_weight_table(); // not supported !!
        return AVCENC_TOOLS_NOT_SUPPORTED;
    }

    /* dec_ref_pic_marking(), this will be done later*/
    sliceHdr->no_output_of_prior_pics_flag = FALSE; /* default */
    sliceHdr->long_term_reference_flag = FALSE; /* for IDR frame, do not make it long term */
    sliceHdr->adaptive_ref_pic_marking_mode_flag = FALSE; /* default */
    /* other params are not set here because they are not used */

    sliceHdr->cabac_init_idc = 0; /* default, if entropy_coding_mode_flag && slice_type==I or SI, range 0..2  */
    sliceHdr->slice_qp_delta = 0; /* default for now */
    sliceHdr->sp_for_switch_flag = FALSE; /* default, if slice_type == SP */
    sliceHdr->slice_qs_delta = 0; /* default, if slice_type == SP or SI */

    /* derived variables from encParam */
    /* deblocking filter */
    video->FilterOffsetA = video->FilterOffsetB = 0;
    if (currPPS->deblocking_filter_control_present_flag == TRUE)
    {
        video->FilterOffsetA = sliceHdr->slice_alpha_c0_offset_div2 << 1;
        video->FilterOffsetB = sliceHdr->slice_beta_offset_div_2 << 1;
    }

    /* flexible macroblock ordering */
    /* populate video->mapUnitToSliceGroupMap and video->MbToSliceGroupMap */
    /* We already call it at the end of PVAVCEncInitialize(). It changes once per each PPS. */
    if (video->currPicParams->num_slice_groups_minus1 > 0 && video->currPicParams->slice_group_map_type >= 3
            && video->currPicParams->slice_group_map_type <= 5)
    {
        sliceHdr->slice_group_change_cycle = SLICE_GROUP_CHANGE_CYCLE;  /* default, don't understand how to set it!!!*/

        video->MapUnitsInSliceGroup0 =
            AVC_MIN(sliceHdr->slice_group_change_cycle * video->SliceGroupChangeRate, video->PicSizeInMapUnits);

        FMOInit(video);
    }

    /* calculate SliceQPy first  */
    /* calculate QSy first */

    sliceHdr->slice_qp_delta = video->QPy - 26 - currPPS->pic_init_qp_minus26;
    //sliceHdr->slice_qs_delta = video->QSy - 26 - currPPS->pic_init_qs_minus26;

    return AVCENC_SUCCESS;
}

