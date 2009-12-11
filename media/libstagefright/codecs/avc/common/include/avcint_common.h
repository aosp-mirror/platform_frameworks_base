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
This file contains common code shared between AVC decoder and AVC encoder for
internal use only.
@publishedAll
*/

#ifndef AVCINT_COMMON_H_INCLUDED
#define AVCINT_COMMON_H_INCLUDED

#ifndef AVCAPI_COMMON_H_INCLUDED
#include "avcapi_common.h"
#endif


#ifndef TRUE
#define TRUE  1
#define FALSE 0
#endif



/**
Mathematic functions defined in subclause 5.7.
Can be replaced with assembly instructions for speedup.
@publishedAll
*/
#define AVC_ABS(x)   (((x)<0)? -(x) : (x))
#define AVC_SIGN(x)  (((x)<0)? -1 : 1)
#define AVC_SIGN0(x) (((x)<0)? -1 : (((x)>0) ? 1 : 0))
#define AVC_MAX(x,y) ((x)>(y)? (x):(y))
#define AVC_MIN(x,y) ((x)<(y)? (x):(y))
#define AVC_MEDIAN(A,B,C) ((A) > (B) ? ((A) < (C) ? (A) : (B) > (C) ? (B) : (C)): (B) < (C) ? (B) : (C) > (A) ? (C) : (A))
#define AVC_CLIP3(a,b,x) (AVC_MAX(a,AVC_MIN(x,b)))  /* clip x between a and b */
#define AVC_CLIP(x)  AVC_CLIP3(0,255,x)
#define AVC_FLOOR(x) ((int)(x))
#define AVC_RASTER_SCAN(x,y,n)  ((x)+(y)*(n))
#define AVC_ROUND(x) (AVC_SIGN(x)*AVC_FLOOR(AVC_ABS(x)+0.5))
#define AVC_INVERSE_RASTER_SCAN(a,b,c,d,e) (((e)==0)? (((a)%((d)/(b)))*(b)): (((a)/((d)/(b)))*(c)))
/* a:block address, b:block width, c:block height, d:total_width, e:x or y coordinate */

#define DEFAULT_ATTR  0  /* default memory attribute  */
#define FAST_MEM_ATTR 1  /* fast memory attribute */


/* This section is for definition of constants. */
#define MB_SIZE 16
#define BLOCK_SIZE 4
#define EMULATION_PREVENTION_THREE_BYTE 0x3
#define NUM_PIXELS_IN_MB  (24*16)
#define NUM_BLKS_IN_MB 24

#define AVCNumI4PredMode  9
#define AVCNumI16PredMode  4
#define AVCNumIChromaMode  4

/* constants used in the structures below */
#define MAXIMUMVALUEOFcpb_cnt   32  /* used in HRDParams */
#define MAX_NUM_REF_FRAMES_IN_PIC_ORDER_CNT_CYCLE 255   /* used in SeqParamSet */
#define MAX_NUM_SLICE_GROUP  8      /* used in PicParamSet */
#define MAX_REF_PIC_LIST_REORDERING 32  /* 32 is maximum according to Annex A, SliceHeader */
#define MAX_DEC_REF_PIC_MARKING 64   /* 64 is the maximum possible given the max num ref pictures to 31. */
#define MAX_FS (16+1)  /* pre-defined size of frame store array */
#define MAX_LEVEL_IDX  15  /* only 15 levels defined for now */
#define MAX_REF_PIC_LIST 33 /* max size of the RefPicList0 and RefPicList1 */


/**
Architectural related macros.
@publishedAll
*/
#ifdef USE_PRED_BLOCK
#define MB_BASED_DEBLOCK
#endif

/**
Picture type, PV created.
@publishedAll
*/
typedef enum
{
    AVC_FRAME = 3
} AVCPictureType;

/**
This slice type follows Table 7-3. The bottom 5 items may not needed.
@publishedAll
*/
typedef enum
{
    AVC_P_SLICE = 0,
    AVC_B_SLICE = 1,
    AVC_I_SLICE = 2,
    AVC_SP_SLICE = 3,
    AVC_SI_SLICE = 4,
    AVC_P_ALL_SLICE = 5,
    AVC_B_ALL_SLICE = 6,
    AVC_I_ALL_SLICE = 7,
    AVC_SP_ALL_SLICE = 8,
    AVC_SI_ALL_SLICE = 9
} AVCSliceType;

/**
Types of the macroblock and partition. PV Created.
@publishedAll
*/
typedef enum
{
    /* intra */
    AVC_I4,
    AVC_I16,
    AVC_I_PCM,
    AVC_SI4,

    /* inter for both P and B*/
    AVC_BDirect16,
    AVC_P16,
    AVC_P16x8,
    AVC_P8x16,
    AVC_P8,
    AVC_P8ref0,
    AVC_SKIP
} AVCMBMode;

/**
Enumeration for sub-macroblock mode, interpreted from sub_mb_type.
@publishedAll
*/
typedef enum
{
    /* for sub-partition mode */
    AVC_BDirect8,
    AVC_8x8,
    AVC_8x4,
    AVC_4x8,
    AVC_4x4
} AVCSubMBMode;

/**
Mode of prediction of partition or sub-partition. PV Created.
Do not change the order!!! Used in table look-up mode prediction in
vlc.c.
@publishedAll
*/
typedef enum
{
    AVC_Pred_L0 = 0,
    AVC_Pred_L1,
    AVC_BiPred,
    AVC_Direct
} AVCPredMode;


/**
Mode of intra 4x4 prediction. Table 8-2
@publishedAll
*/
typedef enum
{
    AVC_I4_Vertical = 0,
    AVC_I4_Horizontal,
    AVC_I4_DC,
    AVC_I4_Diagonal_Down_Left,
    AVC_I4_Diagonal_Down_Right,
    AVC_I4_Vertical_Right,
    AVC_I4_Horizontal_Down,
    AVC_I4_Vertical_Left,
    AVC_I4_Horizontal_Up
} AVCIntra4x4PredMode;

/**
Mode of intra 16x16 prediction. Table 8-3
@publishedAll
*/
typedef enum
{
    AVC_I16_Vertical = 0,
    AVC_I16_Horizontal,
    AVC_I16_DC,
    AVC_I16_Plane
} AVCIntra16x16PredMode;


/**
Mode of intra chroma prediction. Table 8-4
@publishedAll
*/
typedef enum
{
    AVC_IC_DC = 0,
    AVC_IC_Horizontal,
    AVC_IC_Vertical,
    AVC_IC_Plane
} AVCIntraChromaPredMode;

/**
Type of residual going to residual_block_cavlc function, PV created.
@publishedAll
*/
typedef enum
{
    AVC_Luma,
    AVC_Intra16DC,
    AVC_Intra16AC,
    AVC_ChromaDC,
    AVC_ChromaAC
} AVCResidualType;


/**
This structure contains VUI parameters as specified in Annex E.
Some variables may be removed from the structure if they are found to be useless to store.
@publishedAll
*/
typedef struct tagHRDParams
{
    uint  cpb_cnt_minus1;                                   /* ue(v), range 0..31 */
    uint  bit_rate_scale;                          /* u(4) */
    uint  cpb_size_scale;                          /* u(4) */
    uint32  bit_rate_value_minus1[MAXIMUMVALUEOFcpb_cnt];/* ue(v), range 0..2^32-2 */
    uint32  cpb_size_value_minus1[MAXIMUMVALUEOFcpb_cnt]; /* ue(v), range 0..2^32-2 */
    uint  cbr_flag[MAXIMUMVALUEOFcpb_cnt];         /* u(1) */
    uint  initial_cpb_removal_delay_length_minus1;   /* u(5), default 23 */
    uint  cpb_removal_delay_length_minus1;           /* u(5), default 23 */
    uint  dpb_output_delay_length_minus1;            /* u(5), default 23 */
    uint  time_offset_length;                        /* u(5), default 24 */
} AVCHRDParams;

/**
This structure contains VUI parameters as specified in Annex E.
Some variables may be removed from the structure if they are found to be useless to store.
@publishedAll
*/
typedef struct tagVUIParam
{
    uint      aspect_ratio_info_present_flag;     /* u(1) */
    uint  aspect_ratio_idc;                     /* u(8), table E-1 */
    uint  sar_width;                          /* u(16) */
    uint  sar_height;                         /* u(16) */
    uint      overscan_info_present_flag;         /* u(1) */
    uint      overscan_appropriate_flag;        /* u(1) */
    uint      video_signal_type_present_flag;     /* u(1) */
    uint  video_format;                         /* u(3), Table E-2, default 5, unspecified */
    uint      video_full_range_flag;            /* u(1) */
    uint      colour_description_present_flag;  /* u(1) */
    uint  colour_primaries;                   /* u(8), Table E-3, default 2, unspecified */
    uint  transfer_characteristics;           /* u(8), Table E-4, default 2, unspecified */
    uint  matrix_coefficients;                /* u(8), Table E-5, default 2, unspecified */
    uint      chroma_location_info_present_flag;  /* u(1) */
    uint  chroma_sample_loc_type_top_field;                /* ue(v), Fig. E-1range 0..5, default 0 */
    uint  chroma_sample_loc_type_bottom_field;                /* ue(v) */
    uint      timing_info_present_flag;           /* u(1) */
    uint  num_units_in_tick;                    /* u(32), must be > 0 */
    uint  time_scale;                           /* u(32), must be > 0 */
    uint      fixed_frame_rate_flag;            /* u(1), Eq. C-13 */
    uint      nal_hrd_parameters_present_flag;    /* u(1) */
    AVCHRDParams nal_hrd_parameters;               /* hrd_paramters */
    uint      vcl_hrd_parameters_present_flag;    /* u(1) */
    AVCHRDParams vcl_hrd_parameters;               /* hrd_paramters */
    /* if ((nal_hrd_parameters_present_flag || (vcl_hrd_parameters_present_flag)) */
    uint      low_delay_hrd_flag;               /* u(1) */
    uint    pic_struct_present_flag;
    uint      bitstream_restriction_flag;         /* u(1) */
    uint      motion_vectors_over_pic_boundaries_flag;    /* u(1) */
    uint  max_bytes_per_pic_denom;              /* ue(v), default 2 */
    uint  max_bits_per_mb_denom;                /* ue(v), range 0..16, default 1 */
    uint  log2_max_mv_length_vertical;          /* ue(v), range 0..16, default 16 */
    uint  log2_max_mv_length_horizontal;        /* ue(v), range 0..16, default 16 */
    uint  max_dec_frame_reordering;             /* ue(v) */
    uint  max_dec_frame_buffering;              /* ue(v) */
} AVCVUIParams;


/**
This structure contains information in a sequence parameter set NAL.
Some variables may be removed from the structure if they are found to be useless to store.
@publishedAll
*/
typedef struct tagSeqParamSet
{
    uint   Valid;            /* indicates the parameter set is valid */

    uint  profile_idc;              /* u(8) */
    uint   constrained_set0_flag;  /* u(1) */
    uint   constrained_set1_flag;  /* u(1) */
    uint   constrained_set2_flag;  /* u(1) */
    uint   constrained_set3_flag;  /* u(1) */
    uint  level_idc;               /* u(8) */
    uint  seq_parameter_set_id;    /* ue(v), range 0..31 */
    uint  log2_max_frame_num_minus4; /* ue(v), range 0..12 */
    uint pic_order_cnt_type;        /* ue(v), range 0..2 */
    /* if( pic_order_cnt_type == 0 )  */
    uint log2_max_pic_order_cnt_lsb_minus4; /* ue(v), range 0..12 */
    /* else if( pic_order_cnt_type == 1 ) */
    uint delta_pic_order_always_zero_flag;  /* u(1) */
    int32  offset_for_non_ref_pic;       /* se(v) */
    int32  offset_for_top_to_bottom_field;  /* se(v) */
    uint  num_ref_frames_in_pic_order_cnt_cycle;   /* ue(v) , range 0..255 */
    /* for( i = 0; i < num_ref_frames_in_pic_order_cnt_cycle; i++ ) */
    int32   offset_for_ref_frame[MAX_NUM_REF_FRAMES_IN_PIC_ORDER_CNT_CYCLE];        /* se(v) */
    uint  num_ref_frames;                           /* ue(v), range 0..16 */
    uint   gaps_in_frame_num_value_allowed_flag;    /* u(1) */
    uint  pic_width_in_mbs_minus1;                  /* ue(v) */
    uint  pic_height_in_map_units_minus1;           /* ue(v) */
    uint   frame_mbs_only_flag;                     /* u(1) */
    /* if( !frame_mbs_only_flag ) */
    uint   mb_adaptive_frame_field_flag;          /* u(1) */
    uint   direct_8x8_inference_flag;    /* u(1), must be 1 when frame_mbs_only_flag is 0 */
    uint   frame_cropping_flag;                     /* u(1) */
    /* if( frmae_cropping_flag) */
    uint  frame_crop_left_offset;                /* ue(v) */
    uint  frame_crop_right_offset;               /* ue(v) */
    uint  frame_crop_top_offset;                 /* ue(v) */
    uint  frame_crop_bottom_offset;              /* ue(v) */
    uint   vui_parameters_present_flag;                      /* u(1) */
//  uint nal_hrd_parameters_present_flag;
//  uint vcl_hrd_parameters_present_flag;
//  AVCHRDParams *nal_hrd_parameters;
//  AVCHRDParams *vcl_hrd_parameters;
    AVCVUIParams vui_parameters;                  /* AVCVUIParam */
} AVCSeqParamSet;

/**
This structure contains information in a picture parameter set NAL.
Some variables may be removed from the structure if they are found to be useless to store.
@publishedAll
*/
typedef struct tagPicParamSet
{
    uint  pic_parameter_set_id;              /* ue(v), range 0..255 */
    uint  seq_parameter_set_id;              /* ue(v), range 0..31 */
    uint  entropy_coding_mode_flag;         /* u(1) */
    uint  pic_order_present_flag;        /* u(1) */
    uint  num_slice_groups_minus1;           /* ue(v), range in Annex A */
    /* if( num_slice_groups_minus1 > 0) */
    uint  slice_group_map_type;           /* ue(v), range 0..6 */
    /* if( slice_group_map_type = = 0 ) */
    /* for(0:1:num_slice_groups_minus1) */
    uint  run_length_minus1[MAX_NUM_SLICE_GROUP]; /* ue(v) */
    /* else if( slice_group_map_type = = 2 ) */
    /* for(0:1:num_slice_groups_minus1-1) */
    uint  top_left[MAX_NUM_SLICE_GROUP-1];      /* ue(v) */
    uint  bottom_right[MAX_NUM_SLICE_GROUP-1];  /* ue(v) */
    /* else if( slice_group_map_type = = 3 || 4 || 5 */
    uint  slice_group_change_direction_flag;        /* u(1) */
    uint  slice_group_change_rate_minus1;            /* ue(v) */
    /* else if( slice_group_map_type = = 6 ) */
    uint  pic_size_in_map_units_minus1;          /* ue(v) */
    /* for(0:1:pic_size_in_map_units_minus1) */
    uint  *slice_group_id;                           /* complete MBAmap u(v) */
    uint  num_ref_idx_l0_active_minus1;                  /* ue(v), range 0..31 */
    uint  num_ref_idx_l1_active_minus1;                  /* ue(v), range 0..31 */
    uint  weighted_pred_flag;                           /* u(1) */
    uint  weighted_bipred_idc;                          /* u(2), range 0..2 */
    int   pic_init_qp_minus26;                       /* se(v), range -26..25 */
    int   pic_init_qs_minus26;                       /* se(v), range -26..25 */
    int   chroma_qp_index_offset;                    /* se(v), range -12..12 */
    uint  deblocking_filter_control_present_flag;       /* u(1) */
    uint  constrained_intra_pred_flag;                  /* u(1) */
    uint  redundant_pic_cnt_present_flag;               /* u(1) */
} AVCPicParamSet;


/**
This structure contains slice header information.
Some variables may be removed from the structure if they are found to be useless to store.
@publishedAll
*/
typedef struct tagSliceHeader
{
    uint    first_mb_in_slice;      /* ue(v) */
    AVCSliceType slice_type;                /* ue(v), Table 7-3, range 0..9 */
    uint    pic_parameter_set_id;   /* ue(v), range 0..255 */
    uint    frame_num;              /* u(v), see log2max_frame_num_minus4 */
    /* if( !frame_mbs_only_flag) */
    uint    field_pic_flag;         /* u(1) */
    /* if(field_pic_flag) */
    uint bottom_field_flag; /* u(1) */
    /* if(nal_unit_type == 5) */
    uint    idr_pic_id;         /* ue(v), range 0..65535 */
    /* if(pic_order_cnt_type==0) */
    uint    pic_order_cnt_lsb;  /* u(v), range 0..MaxPicOrderCntLsb-1 */
    /* if(pic_order_present_flag && !field_pic_flag) */
    int32 delta_pic_order_cnt_bottom;   /* se(v) */
    /* if(pic_order_cnt_type==1 && !delta_pic_order_always_zero_flag) */
    /* if(pic_order_present_flag && !field_pic_flag) */
    int32 delta_pic_order_cnt[2];
    /* if(redundant_pic_cnt_present_flag) */
    uint redundant_pic_cnt; /* ue(v), range 0..127 */
    /* if(slice_type == B) */
    uint direct_spatial_mv_pred_flag; /* u(1) */
    /* if(slice_type == P || slice_type==SP || slice_type==B) */
    uint num_ref_idx_active_override_flag;  /* u(1) */
    /* if(num_ref_idx_active_override_flag) */
    uint num_ref_idx_l0_active_minus1;  /* ue(v) */
    /* if(slie_type == B) */
    uint num_ref_idx_l1_active_minus1;  /* ue(v) */

    /* ref_pic_list_reordering() */
    uint ref_pic_list_reordering_flag_l0;   /* u(1) */
    uint reordering_of_pic_nums_idc_l0[MAX_REF_PIC_LIST_REORDERING];   /* ue(v), range 0..3 */
    uint abs_diff_pic_num_minus1_l0[MAX_REF_PIC_LIST_REORDERING];   /* ue(v) */
    uint long_term_pic_num_l0[MAX_REF_PIC_LIST_REORDERING];     /* ue(v) */
    uint ref_pic_list_reordering_flag_l1;   /* u(1) */
    uint reordering_of_pic_nums_idc_l1[MAX_REF_PIC_LIST_REORDERING];   /* ue(v), range 0..3 */
    uint abs_diff_pic_num_minus1_l1[MAX_REF_PIC_LIST_REORDERING];   /* ue(v) */
    uint long_term_pic_num_l1[MAX_REF_PIC_LIST_REORDERING];     /* ue(v) */

    /* end ref_pic_list_reordering() */
    /* if(nal_ref_idc!=0) */
    /* dec_ref_pic_marking() */
    uint    no_output_of_prior_pics_flag;   /* u(1) */
    uint long_term_reference_flag;      /* u(1) */
    uint    adaptive_ref_pic_marking_mode_flag; /* u(1) */
    uint    memory_management_control_operation[MAX_DEC_REF_PIC_MARKING];   /* ue(v), range 0..6 */
    uint difference_of_pic_nums_minus1[MAX_DEC_REF_PIC_MARKING];    /* ue(v) */
    uint    long_term_pic_num[MAX_DEC_REF_PIC_MARKING];             /* ue(v) */
    uint    long_term_frame_idx[MAX_DEC_REF_PIC_MARKING];           /* ue(v) */
    uint    max_long_term_frame_idx_plus1[MAX_DEC_REF_PIC_MARKING]; /* ue(v) */
    /* end dec_ref_pic_marking() */
    /* if(entropy_coding_mode_flag && slice_type!=I && slice_type!=SI) */
    uint cabac_init_idc;        /* ue(v), range 0..2 */
    int slice_qp_delta;     /* se(v), range 0..51 */
    /* if(slice_type==SP || slice_type==SI) */
    /* if(slice_type==SP) */
    uint    sp_for_switch_flag; /* u(1) */
    int slice_qs_delta;     /* se(v) */

    /* if(deblocking_filter_control_present_flag)*/
    uint disable_deblocking_filter_idc; /* ue(v), range 0..2 */
    /* if(disable_deblocking_filter_idc!=1) */
    int slice_alpha_c0_offset_div2; /* se(v), range -6..6, default 0 */
    int slice_beta_offset_div_2; /* se(v), range -6..6, default 0 */
    /* if(num_slice_groups_minus1>0 && slice_group_map_type>=3 && slice_group_map_type<=5)*/
    uint    slice_group_change_cycle;   /* u(v), use ceil(log2(PicSizeInMapUnits/SliceGroupChangeRate + 1)) bits*/

} AVCSliceHeader;

/**
This struct contains information about the neighboring pixel.
@publishedAll
*/
typedef struct tagPixPos
{
    int available;
    int mb_addr;    /* macroblock address of the current pixel, see below */
    int x;      /* x,y positions of current pixel relative to the macroblock mb_addr */
    int y;
    int pos_x;  /* x,y positions of current pixel relative to the picture. */
    int pos_y;
} AVCPixelPos;

typedef struct tagNeighborAvailability
{
    int left;
    int top;    /* macroblock address of the current pixel, see below */
    int top_right;      /* x,y positions of current pixel relative to the macroblock mb_addr */
} AVCNeighborAvailability;


/**
This structure contains picture data and related information necessary to be used as
reference frame.
@publishedAll
*/
typedef struct tagPictureData
{
    uint16 RefIdx;  /* index used for reference frame */
    uint8 *Sl;   /* derived from base_dpb in AVCFrameStore */
    uint8 *Scb;  /* for complementary fields, YUV are interlaced */
    uint8 *Scr;  /* Sl of top_field and bottom_fields will be one line apart and the
                    stride will be 2 times the width. */
    /* For non-complementary field, the above still applies. A special
       output formatting is required. */

    /* Then, necessary variables that need to be stored */
    AVCPictureType  picType; /* frame, top-field or bot-field */
    /*bool*/
    uint    isReference;
    /*bool*/
    uint    isLongTerm;
    int     PicOrderCnt;
    int     PicNum;
    int     LongTermPicNum;

    int     width; /* how many pixel per line */
    int     height;/* how many line */
    int     pitch; /* how many pixel between the line */

    uint    padded; /* flag for being padded */

} AVCPictureData;

/**
This structure contains information for frame storage.
@publishedAll
*/
typedef struct tagFrameStore
{
    uint8 *base_dpb;    /* base pointer for the YCbCr */

    int     IsReference; /*  0=not used for ref; 1=top used; 2=bottom used; 3=both fields (or frame) used */
    int     IsLongTerm;  /*  0=not used for ref; 1=top used; 2=bottom used; 3=both fields (or frame) used */
    /* if IsLongTerm is true, IsReference can be ignored. */
    /* if IsReference is true, IsLongterm will be checked for short-term or long-term. */
    /* IsUsed must be true to enable the validity of IsReference and IsLongTerm */

    int     IsOutputted;  /* has it been outputted via AVCDecGetOutput API, then don't output it again,
                            wait until it is returned. */
    AVCPictureData frame;

    int     FrameNum;
    int     FrameNumWrap;
    int     LongTermFrameIdx;
    int     PicOrderCnt; /* of the frame, smaller of the 2 fields */

} AVCFrameStore;

/**
This structure maintains the actual memory for the decoded picture buffer (DPB) which is
allocated at the beginning according to profile/level.
Once decoded_picture_buffer is allocated, Sl,Scb,Scr in
AVCPictureData structure just point to the address in decoded_picture_buffer.
used_size maintains the used space.
NOTE:: In order to maintain contiguous memory space, memory equal to a single frame is
assigned at a time. Two opposite fields reside in the same frame memory.

  |-------|---|---|---|xxx|-------|xxx|---|-------|   decoded_picture_buffer
    frame  top bot top      frame      bot  frame
      0     1   1   2         3         4     5

  bot 2 and top 4 do not exist, the memory is not used.

@publishedAll
*/
typedef struct tagDecPicBuffer
{
    uint8 *decoded_picture_buffer;  /* actual memory */
    uint32  dpb_size;       /* size of dpb in bytes */
    uint32  used_size;  /* used size */
    struct tagFrameStore    *fs[MAX_FS]; /* list of frame stored, actual buffer */
    int     num_fs;  /* size of fs */

} AVCDecPicBuffer;


/**
This structure contains macroblock related variables.
@publishedAll
*/
typedef struct tagMacroblock
{
    AVCIntraChromaPredMode  intra_chroma_pred_mode;  /* ue(v) */

    int32 mvL0[16];  /* motion vectors, 16 bit packed (x,y) per element  */
    int32 mvL1[16];
    int16 ref_idx_L0[4];
    int16 ref_idx_L1[4];
    uint16 RefIdx[4]; /* ref index, has value of AVCPictureData->RefIdx */
    /* stored data */
    /*bool*/
    uint    mb_intra; /* intra flag */
    /*bool*/
    uint    mb_bottom_field;

    AVCMBMode mbMode;   /* type of MB prediction */
    AVCSubMBMode subMbMode[4]; /* for each 8x8 partition */

    uint    CBP; /* CodeBlockPattern */
    AVCIntra16x16PredMode i16Mode; /* Intra16x16PredMode */
    AVCIntra4x4PredMode i4Mode[16]; /* Intra4x4PredMode, in raster scan order */
    int NumMbPart; /* number of partition */
    AVCPredMode MBPartPredMode[4][4]; /* prediction mode [MBPartIndx][subMBPartIndx] */
    int MbPartWidth;
    int MbPartHeight;
    int NumSubMbPart[4];  /* for each 8x8 partition */
    int SubMbPartWidth[4];  /* for each 8x8 partition */
    int SubMbPartHeight[4]; /* for each 8x8 partition */

    uint8 nz_coeff[NUM_BLKS_IN_MB];  /* [blk_y][blk_x], Chroma is [4..5][0...3], see predict_nnz() function */

    int QPy; /* Luma QP */
    int QPc; /* Chroma QP */
    int QSc; /* Chroma QP S-picture */

    int slice_id;           // MC slice
} AVCMacroblock;


/**
This structure contains common internal variables between the encoder and decoder
such that some functions can be shared among them.
@publishedAll
*/
typedef struct tagCommonObj
{
    /* put these 2 up here to make sure they are word-aligned */
    int16   block[NUM_PIXELS_IN_MB]; /* for transformed residue coefficient */
    uint8   *pred_block;    /* pointer to prediction block, could point to a frame */
#ifdef USE_PRED_BLOCK
    uint8   pred[688];  /* for prediction */
    /* Luma [0-399], Cb [400-543], Cr[544-687] */
#endif
    int     pred_pitch; /* either equal to 20 or to frame pitch */

    /* temporary buffers for intra prediction */
    /* these variables should remain inside fast RAM */
#ifdef MB_BASED_DEBLOCK
    uint8   *intra_pred_top; /* a row of pixel for intra prediction */
    uint8   intra_pred_left[17]; /* a column of pixel for intra prediction */
    uint8   *intra_pred_top_cb;
    uint8   intra_pred_left_cb[9];
    uint8   *intra_pred_top_cr;
    uint8   intra_pred_left_cr[9];
#endif
    /* pointer to the prediction area for intra prediction */
    uint8   *pintra_pred_top;   /* pointer to the top intra prediction value */
    uint8   *pintra_pred_left;  /* pointer to the left intra prediction value */
    uint8   intra_pred_topleft; /* the [-1,-1] neighboring pixel */
    uint8   *pintra_pred_top_cb;
    uint8   *pintra_pred_left_cb;
    uint8   intra_pred_topleft_cb;
    uint8   *pintra_pred_top_cr;
    uint8   *pintra_pred_left_cr;
    uint8   intra_pred_topleft_cr;

    int QPy;
    int QPc;
    int QPy_div_6;
    int QPy_mod_6;
    int QPc_div_6;
    int QPc_mod_6;
    /**** nal_unit ******/
    /* previously in AVCNALUnit format */
    uint    NumBytesInRBSP;
    int     forbidden_bit;
    int     nal_ref_idc;
    AVCNalUnitType  nal_unit_type;
    AVCNalUnitType  prev_nal_unit_type;
    /*bool*/
    uint    slice_data_partitioning; /* flag when nal_unit_type is between 2 and 4 */
    /**** ******** ******/
    AVCSliceType slice_type;
    AVCDecPicBuffer     *decPicBuf; /* decoded picture buffer */

    AVCSeqParamSet *currSeqParams; /*  the currently used one */

    AVCPicParamSet  *currPicParams; /* the currently used one */
    uint        seq_parameter_set_id;
    /* slice header */
    AVCSliceHeader *sliceHdr;   /* slice header param syntax variables */

    AVCPictureData  *currPic; /* pointer to current picture */
    AVCFrameStore   *currFS;  /* pointer to current frame store */
    AVCPictureType  currPicType; /* frame, top-field or bot-field */
    /*bool*/
    uint    newPic; /* flag for new picture */
    uint            newSlice; /* flag for new slice */
    AVCPictureData  *prevRefPic; /* pointer to previous picture */

    AVCMacroblock   *mblock; /* array of macroblocks covering entire picture */
    AVCMacroblock   *currMB; /* pointer to current macroblock */
    uint                    mbNum; /* number of current MB */
    int                 mb_x;  /* x-coordinate of the current mbNum */
    int                 mb_y;  /* y-coordinate of the current mbNum */

    /* For internal operation, scratch memory for MV, prediction, transform, etc.*/
    uint32 cbp4x4; /* each bit represent nonzero 4x4 block in reverse raster scan order */
    /* starting from luma, Cb and Cr, lsb toward msb */
    int mvd_l0[4][4][2]; /* [mbPartIdx][subMbPartIdx][compIdx], se(v) */
    int mvd_l1[4][4][2]; /* [mbPartIdx][subMbPartIdx][compIdx], se(v) */

    int mbAddrA, mbAddrB, mbAddrC, mbAddrD; /* address of neighboring MBs */
    /*bool*/
    uint    mbAvailA, mbAvailB, mbAvailC, mbAvailD; /* availability */
    /*bool*/
    uint    intraAvailA, intraAvailB, intraAvailC, intraAvailD; /* for intra mode */
    /***********************************************/
    /* The following variables are defined in the draft. */
    /* They may need to be stored in PictureData structure and used for reference. */
    /* In that case, just move or copy it to AVCDecPictureData structure. */

    int     padded_size;    /* size of extra padding to a frame */

    uint    MaxFrameNum;    /*2^(log2_max_frame_num_minus4+4), range 0.. 2^16-1 */
    uint    MaxPicOrderCntLsb; /*2^(log2_max_pic_order_cnt_lsb_minus4+4), 0..2^16-1 */
    uint    PicWidthInMbs;  /*pic_width_in_mbs_minus1+1 */
    uint    PicWidthInSamplesL; /* PicWidthInMbs*16 */
    uint    PicWidthInSamplesC; /* PicWIdthInMbs*8 */
    uint    PicHeightInMapUnits; /* pic_height_in_map_units_minus1+1 */
    uint    PicSizeInMapUnits;  /* PicWidthInMbs*PicHeightInMapUnits */
    uint    FrameHeightInMbs;   /*(2-frame_mbs_only_flag)*PicHeightInMapUnits */

    uint    SliceGroupChangeRate; /* slice_group_change_rate_minus1 + 1 */

    /* access unit */
    uint    primary_pic_type;   /* u(3), Table 7-2, kinda informative only */

    /* slice data partition */
    uint    slice_id;           /* ue(v) */

    uint    UnusedShortTermFrameNum;
    uint    PrevRefFrameNum;
    uint    MbaffFrameFlag; /* (mb_adaptive_frame_field_flag && !field_pic_flag) */
    uint    PicHeightInMbs; /* FrameHeightInMbs/(1+field_pic_flag) */
    int     PicHeightInSamplesL; /* PicHeightInMbs*16 */
    int     PicHeightInSamplesC; /* PicHeightInMbs*8 */
    uint    PicSizeInMbs;   /* PicWidthInMbs*PicHeightInMbs */
    uint    level_idc;
    int     numMBs;
    uint    MaxPicNum;
    uint    CurrPicNum;
    int     QSy;    /* 26+pic_init_qp_minus26+slice_qs_delta */
    int     FilterOffsetA;
    int     FilterOffsetB;
    uint    MapUnitsInSliceGroup0;  /* Min(slie_group_change_cycle*SliceGroupChangeRate,PicSizeInMapUnits) */
    /* dec_ref_pic_marking */
    int     MaxLongTermFrameIdx;
    int     LongTermFrameIdx;

    /* POC related variables */
    /*bool*/
    uint    mem_mgr_ctrl_eq_5;  /* if memory_management_control_operation equal to 5 flag */
    int     PicOrderCnt;
    int     BottomFieldOrderCnt, TopFieldOrderCnt;
    /* POC mode 0 */
    int     prevPicOrderCntMsb;
    uint    prevPicOrderCntLsb;
    int     PicOrderCntMsb;
    /* POC mode 1 */
    int     prevFrameNumOffset, FrameNumOffset;
    uint    prevFrameNum;
    int     absFrameNum;
    int     picOrderCntCycleCnt, frameNumInPicOrderCntCycle;
    int     expectedDeltaPerPicOrderCntCycle;
    int     expectedPicOrderCnt;

    /* FMO */
    int *MbToSliceGroupMap;  /* to be re-calculate at the beginning */

    /* ref pic list */
    AVCPictureData  *RefPicList0[MAX_REF_PIC_LIST]; /* list 0 */
    AVCPictureData  *RefPicList1[MAX_REF_PIC_LIST]; /* list 1 */
    AVCFrameStore   *refFrameList0ShortTerm[32];
    AVCFrameStore   *refFrameList1ShortTerm[32];
    AVCFrameStore   *refFrameListLongTerm[32];
    int     refList0Size;
    int     refList1Size;

    /* slice data semantics*/
    int mb_skip_run;    /* ue(v) */
    /*uint  mb_skip_flag;*/ /* ae(v) */
    /* uint end_of_slice_flag;*//* ae(v) */
    /***********************************************/

    /* function pointers */
    int (*is_short_ref)(AVCPictureData *s);
    int (*is_long_ref)(AVCPictureData *s);

} AVCCommonObj;

/**
Commonly used constant arrays.
@publishedAll
*/
/**
Zigzag scan from 1-D to 2-D. */
const static uint8 ZZ_SCAN[16] = {0, 1, 4, 8, 5, 2, 3, 6, 9, 12, 13, 10, 7, 11, 14, 15};
/* Zigzag scan from 1-D to 2-D output to block[24][16]. */
const static uint8 ZZ_SCAN_BLOCK[16] = {0, 1, 16, 32, 17, 2, 3, 18, 33, 48, 49, 34, 19, 35, 50, 51};

/**
From zigzag to raster for luma DC value */
const static uint8 ZIGZAG2RASTERDC[16] = {0, 4, 64, 128, 68, 8, 12, 72, 132, 192, 196, 136, 76, 140, 200, 204};


/**
Mapping from coding scan block indx to raster scan block index */
const static int blkIdx2blkX[16] = {0, 1, 0, 1, 2, 3, 2, 3, 0, 1, 0, 1, 2, 3, 2, 3};
const static int blkIdx2blkY[16] = {0, 0, 1, 1, 0, 0, 1, 1, 2, 2, 3, 3, 2, 2, 3, 3};
/** from [blk8indx][blk4indx] to raster scan index */
const static int blkIdx2blkXY[4][4] = {{0, 1, 4, 5}, {2, 3, 6, 7}, {8, 9, 12, 13}, {10, 11, 14, 15}};

/*
Availability of the neighboring top-right block relative to the current block. */
const static int BlkTopRight[16] = {2, 2, 2, 3, 1, 0, 1, 0, 1, 1, 1, 0, 1, 0, 1, 0};

/**
Table 8-13 Specification of QPc as a function of qPI. */
const static uint8 mapQPi2QPc[52] = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
                                     21, 22, 23, 24, 25, 26, 27, 28, 29, 29, 30, 31, 32, 32, 33, 34, 34, 35, 35, 36, 36,
                                     37, 37, 37, 38, 38, 38, 39, 39, 39, 39
                                    };

/**
See 8.5.5 equation (8-252 and 8-253) the definition of v matrix. */
/* in zigzag scan */
const static int dequant_coefres[6][16] =
{
    {10, 13, 13, 10, 16, 10, 13, 13, 13, 13, 16, 10, 16, 13, 13, 16},
    {11, 14, 14, 11, 18, 11, 14, 14, 14, 14, 18, 11, 18, 14, 14, 18},
    {13, 16, 16, 13, 20, 13, 16, 16, 16, 16, 20, 13, 20, 16, 16, 20},
    {14, 18, 18, 14, 23, 14, 18, 18, 18, 18, 23, 14, 23, 18, 18, 23},
    {16, 20, 20, 16, 25, 16, 20, 20, 20, 20, 25, 16, 25, 20, 20, 25},
    {18, 23, 23, 18, 29, 18, 23, 23, 23, 23, 29, 18, 29, 23, 23, 29}
};

/**
From jm7.6 block.c. (in zigzag scan) */
const static int quant_coef[6][16] =
{
    {13107, 8066,   8066,   13107,  5243,   13107,  8066,   8066,   8066,   8066,   5243,   13107,  5243,   8066,   8066,   5243},
    {11916, 7490,   7490,   11916,  4660,   11916,  7490,   7490,   7490,   7490,   4660,   11916,  4660,   7490,   7490,   4660},
    {10082, 6554,   6554,   10082,  4194,   10082,  6554,   6554,   6554,   6554,   4194,   10082,  4194,   6554,   6554,   4194},
    {9362,  5825,   5825,   9362,   3647,   9362,   5825,   5825,   5825,   5825,   3647,   9362,   3647,   5825,   5825,   3647},
    {8192,  5243,   5243,   8192,   3355,   8192,   5243,   5243,   5243,   5243,   3355,   8192,   3355,   5243,   5243,   3355},
    {7282,  4559,   4559,   7282,   2893,   7282,   4559,   4559,   4559,   4559,   2893,   7282,   2893,   4559,   4559,   2893}
};

/**
Convert scan from raster scan order to block decoding order and
from block decoding order to raster scan order. Same table!!!
*/
const static uint8 ras2dec[16] = {0, 1, 4, 5, 2, 3, 6, 7, 8, 9, 12, 13, 10, 11, 14, 15};

/* mapping from level_idc to index map */
const static uint8 mapLev2Idx[61] = {255, 255, 255, 255, 255, 255, 255, 255, 255, 1,
                                     0, 1, 2, 3, 255, 255, 255, 255, 255, 255,
                                     4, 5, 6, 255, 255, 255, 255, 255, 255, 255,
                                     7, 8, 9, 255, 255, 255, 255, 255, 255, 255,
                                     10, 11, 12, 255, 255, 255, 255, 255, 255, 255,
                                     13, 14, 255, 255, 255, 255, 255, 255, 255, 255
                                    };
/* map back from index to Level IDC */
const static uint8 mapIdx2Lev[MAX_LEVEL_IDX] = {10, 11, 12, 13, 20, 21, 22, 30, 31, 32, 40, 41, 42, 50, 51};

/**
from the index map to the MaxDPB value times 2 */
const static int32 MaxDPBX2[MAX_LEVEL_IDX] = {297, 675, 1782, 1782, 1782, 3564, 6075, 6075,
        13500, 15360, 24576, 24576, 24576, 82620, 138240
                                             };

/* map index to the max frame size */
const static int MaxFS[MAX_LEVEL_IDX] = {99, 396, 396, 396, 396, 792, 1620, 1620, 3600, 5120,
                                        8192, 8192, 8192, 22080, 36864
                                        };

/* map index to max MB processing rate */
const static int32 MaxMBPS[MAX_LEVEL_IDX] = {1485, 3000, 6000, 11880, 11880, 19800, 20250, 40500,
        108000, 216000, 245760, 245760, 491520, 589824, 983040
                                            };

/* map index to max video bit rate */
const static uint32 MaxBR[MAX_LEVEL_IDX] = {64, 192, 384, 768, 2000, 4000, 4000, 10000, 14000, 20000,
        20000, 50000, 50000, 135000, 240000
                                           };

/* map index to max CPB size */
const static uint32 MaxCPB[MAX_LEVEL_IDX] = {175, 500, 1000, 2000, 2000, 4000, 4000, 10000, 14000,
        20000, 25000, 62500, 62500, 135000, 240000
                                            };

/* map index to max vertical MV range */
const static int MaxVmvR[MAX_LEVEL_IDX] = {64, 128, 128, 128, 128, 256, 256, 256, 512, 512, 512, 512, 512, 512, 512};

#endif /*  _AVCINT_COMMON_H_ */
