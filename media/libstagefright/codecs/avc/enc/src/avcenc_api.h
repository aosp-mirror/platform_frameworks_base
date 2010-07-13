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
This file contains application function interfaces to the AVC encoder library
and necessary type defitionitions and enumerations.
@publishedAll
*/

#ifndef AVCENC_API_H_INCLUDED
#define AVCENC_API_H_INCLUDED

#ifndef AVCAPI_COMMON_H_INCLUDED
#include "avcapi_common.h"
#endif

// For memset, etc
#include <string.h>

/**
 This enumeration is used for the status returned from the library interface.
*/
typedef enum
{
    /**
    Fail information, need to add more error code for more specific info
    */
    AVCENC_TRAILINGONES_FAIL = -35,
    AVCENC_SLICE_EMPTY = -34,
    AVCENC_POC_FAIL = -33,
    AVCENC_CONSECUTIVE_NONREF = -32,
    AVCENC_CABAC_FAIL = -31,
    AVCENC_PRED_WEIGHT_TAB_FAIL = -30,
    AVCENC_DEC_REF_PIC_MARK_FAIL = -29,
    AVCENC_SPS_FAIL = -28,
    AVCENC_BITSTREAM_BUFFER_FULL    = -27,
    AVCENC_BITSTREAM_INIT_FAIL = -26,
    AVCENC_CHROMA_QP_FAIL = -25,
    AVCENC_INIT_QS_FAIL = -24,
    AVCENC_INIT_QP_FAIL = -23,
    AVCENC_WEIGHTED_BIPRED_FAIL = -22,
    AVCENC_INVALID_INTRA_PERIOD = -21,
    AVCENC_INVALID_CHANGE_RATE = -20,
    AVCENC_INVALID_BETA_OFFSET = -19,
    AVCENC_INVALID_ALPHA_OFFSET = -18,
    AVCENC_INVALID_DEBLOCK_IDC = -17,
    AVCENC_INVALID_REDUNDANT_PIC = -16,
    AVCENC_INVALID_FRAMERATE = -15,
    AVCENC_INVALID_NUM_SLICEGROUP = -14,
    AVCENC_INVALID_POC_LSB = -13,
    AVCENC_INVALID_NUM_REF = -12,
    AVCENC_INVALID_FMO_TYPE = -11,
    AVCENC_ENCPARAM_MEM_FAIL = -10,
    AVCENC_LEVEL_NOT_SUPPORTED = -9,
    AVCENC_LEVEL_FAIL = -8,
    AVCENC_PROFILE_NOT_SUPPORTED = -7,
    AVCENC_TOOLS_NOT_SUPPORTED = -6,
    AVCENC_WRONG_STATE = -5,
    AVCENC_UNINITIALIZED = -4,
    AVCENC_ALREADY_INITIALIZED = -3,
    AVCENC_NOT_SUPPORTED = -2,
    AVCENC_MEMORY_FAIL = AVC_MEMORY_FAIL,
    AVCENC_FAIL = AVC_FAIL,
    /**
    Generic success value
    */
    AVCENC_SUCCESS = AVC_SUCCESS,
    AVCENC_PICTURE_READY = 2,
    AVCENC_NEW_IDR = 3, /* upon getting this, users have to call PVAVCEncodeSPS and PVAVCEncodePPS to get a new SPS and PPS*/
    AVCENC_SKIPPED_PICTURE = 4 /* continuable error message */

} AVCEnc_Status;

#define MAX_NUM_SLICE_GROUP  8      /* maximum for all the profiles */

/**
This structure contains the encoding parameters.
*/
typedef struct tagAVCEncParam
{
    /* if profile/level is set to zero, encoder will choose the closest one for you */
    AVCProfile profile; /* profile of the bitstream to be compliant with*/
    AVCLevel   level;   /* level of the bitstream to be compliant with*/

    int width;      /* width of an input frame in pixel */
    int height;     /* height of an input frame in pixel */

    int poc_type; /* picture order count mode, 0,1 or 2 */
    /* for poc_type == 0 */
    uint log2_max_poc_lsb_minus_4; /* specify maximum value of POC Lsb, range 0..12*/
    /* for poc_type == 1 */
    uint delta_poc_zero_flag; /* delta POC always zero */
    int offset_poc_non_ref; /* offset for non-reference pic */
    int offset_top_bottom; /* offset between top and bottom field */
    uint num_ref_in_cycle; /* number of reference frame in one cycle */
    int *offset_poc_ref; /* array of offset for ref pic, dimension [num_ref_in_cycle] */

    int num_ref_frame;  /* number of reference frame used */
    int num_slice_group;  /* number of slice group */
    int fmo_type;   /* 0: interleave, 1: dispersed, 2: foreground with left-over
                    3: box-out, 4:raster scan, 5:wipe, 6:explicit */
    /* for fmo_type == 0 */
    uint run_length_minus1[MAX_NUM_SLICE_GROUP];   /* array of size num_slice_group, in round robin fasion */
    /* fmo_type == 2*/
    uint top_left[MAX_NUM_SLICE_GROUP-1];           /* array of co-ordinates of each slice_group */
    uint bottom_right[MAX_NUM_SLICE_GROUP-1];       /* except the last one which is the background. */
    /* fmo_type == 3,4,5 */
    AVCFlag change_dir_flag;  /* slice group change direction flag */
    uint change_rate_minus1;
    /* fmo_type == 6 */
    uint *slice_group; /* array of size MBWidth*MBHeight */

    AVCFlag db_filter;  /* enable deblocking loop filter */
    int disable_db_idc;  /* 0: filter everywhere, 1: no filter, 2: no filter across slice boundary */
    int alpha_offset;   /* alpha offset range -6,...,6 */
    int beta_offset;    /* beta offset range -6,...,6 */

    AVCFlag constrained_intra_pred; /* constrained intra prediction flag */

    AVCFlag auto_scd;   /* scene change detection on or off */
    int idr_period; /* idr frame refresh rate in number of target encoded frame (no concept of actual time).*/
    int intramb_refresh;    /* minimum number of intra MB per frame */
    AVCFlag data_par;   /* enable data partitioning */

    AVCFlag fullsearch; /* enable full-pel full-search mode */
    int search_range;   /* search range for motion vector in (-search_range,+search_range) pixels */
    AVCFlag sub_pel;    /* enable sub pel prediction */
    AVCFlag submb_pred; /* enable sub MB partition mode */
    AVCFlag rdopt_mode; /* RD optimal mode selection */
    AVCFlag bidir_pred; /* enable bi-directional for B-slice, this flag forces the encoder to encode
                        any frame with POC less than the previously encoded frame as a B-frame.
                        If it's off, then such frames will remain P-frame. */

    AVCFlag rate_control; /* rate control enable, on: RC on, off: constant QP */
    int initQP;     /* initial QP */
    uint32 bitrate;    /* target encoding bit rate in bits/second */
    uint32 CPB_size;  /* coded picture buffer in number of bits */
    uint32 init_CBP_removal_delay; /* initial CBP removal delay in msec */

    uint32 frame_rate;  /* frame rate in the unit of frames per 1000 second */
    /* note, frame rate is only needed by the rate control, AVC is timestamp agnostic. */

    AVCFlag out_of_band_param_set; /* flag to set whether param sets are to be retrieved up front or not */

    AVCFlag use_overrun_buffer;  /* do not throw away the frame if output buffer is not big enough.
                                    copy excess bits to the overrun buffer */
} AVCEncParams;


/**
This structure contains current frame encoding statistics for debugging purpose.
*/
typedef struct tagAVCEncFrameStats
{
    int avgFrameQP;   /* average frame QP */
    int numIntraMBs;  /* number of intra MBs */
    int numFalseAlarm;
    int numMisDetected;
    int numDetected;

} AVCEncFrameStats;

#ifdef __cplusplus
extern "C"
{
#endif
    /** THE FOLLOWINGS ARE APIS */
    /**
    This function initializes the encoder library. It verifies the validity of the
    encoding parameters against the specified profile/level and the list of supported
    tools by this library. It allocates necessary memories required to perform encoding.
    For re-encoding application, if users want to setup encoder in a more precise way,
    users can give the external SPS and PPS to the encoder to follow.
    \param "avcHandle"  "Handle to the AVC encoder library object."
    \param "encParam"   "Pointer to the encoding parameter structure."
    \param "extSPS"     "External SPS used for re-encoding purpose. NULL if not present"
    \param "extPPS"     "External PPS used for re-encoding purpose. NULL if not present"
    \return "AVCENC_SUCCESS for success,
             AVCENC_NOT_SUPPORTED for the use of unsupported tools,
             AVCENC_MEMORY_FAIL for memory allocation failure,
             AVCENC_FAIL for generic failure."
    */
    OSCL_IMPORT_REF AVCEnc_Status PVAVCEncInitialize(AVCHandle *avcHandle, AVCEncParams *encParam, void* extSPS, void* extPPS);


    /**
    Since the output buffer size is not known prior to encoding a frame, users need to
    allocate big enough buffer otherwise, that frame will be dropped. This function returns
    the size of the output buffer to be allocated by the users that guarantees to hold one frame.
    It follows the CPB spec for a particular level.  However, when the users set use_overrun_buffer
    flag, this API is useless as excess output bits are saved in the overrun buffer waiting to be
    copied out in small chunks, i.e. users can allocate any size of output buffer.
    \param "avcHandle"  "Handle to the AVC encoder library object."
    \param "size"   "Pointer to the size to be modified."
    \return "AVCENC_SUCCESS for success, AVCENC_UNINITIALIZED when level is not known.
    */

    OSCL_IMPORT_REF AVCEnc_Status PVAVCEncGetMaxOutputBufferSize(AVCHandle *avcHandle, int* size);

    /**
    Users call this function to provide an input structure to the encoder library which will keep
    a list of input structures it receives in case the users call this function many time before
    calling PVAVCEncodeSlice. The encoder library will encode them according to the frame_num order.
    Users should not modify the content of a particular frame until this frame is encoded and
    returned thru CBAVCEnc_ReturnInput() callback function.
    \param "avcHandle"  "Handle to the AVC encoder library object."
    \param "input"      "Pointer to the input structure."
    \return "AVCENC_SUCCESS for success,
            AVCENC_FAIL if the encoder is not in the right state to take a new input frame.
            AVCENC_NEW_IDR for the detection or determination of a new IDR, with this status,
            the returned NAL is an SPS NAL,
            AVCENC_NO_PICTURE if the input frame coding timestamp is too early, users must
            get next frame or adjust the coding timestamp."
    */
    OSCL_IMPORT_REF AVCEnc_Status PVAVCEncSetInput(AVCHandle *avcHandle, AVCFrameIO *input);

    /**
    This function is called to encode a NAL unit which can be an SPS NAL, a PPS NAL or
    a VCL (video coding layer) NAL which contains one slice of data. It could be a
    fixed number of macroblocks, as specified in the encoder parameters set, or the
    maximum number of macroblocks fitted into the given input argument "buffer". The
    input frame is taken from the oldest unencoded input frame retrieved by users by
    PVAVCEncGetInput API.
    \param "avcHandle"  "Handle to the AVC encoder library object."
    \param "buffer"     "Pointer to the output AVC bitstream buffer, the format will be EBSP,
                         not RBSP."
    \param "buf_nal_size"   "As input, the size of the buffer in bytes.
                        This is the physical limitation of the buffer. As output, the size of the EBSP."
    \param "nal_type"   "Pointer to the NAL type of the returned buffer."
    \return "AVCENC_SUCCESS for success of encoding one slice,
             AVCENC_PICTURE_READY for the completion of a frame encoding,
             AVCENC_FAIL for failure (this should not occur, though)."
    */
    OSCL_IMPORT_REF AVCEnc_Status PVAVCEncodeNAL(AVCHandle *avcHandle, uint8 *buffer, uint *buf_nal_size, int *nal_type);

    /**
    This function sniffs the nal_unit_type such that users can call corresponding APIs.
    This function is identical to PVAVCDecGetNALType() in the decoder.
    \param "bitstream"  "Pointer to the beginning of a NAL unit (start with forbidden_zero_bit, etc.)."
    \param "size"       "size of the bitstream (NumBytesInNALunit + 1)."
    \param "nal_unit_type" "Pointer to the return value of nal unit type."
    \return "AVCENC_SUCCESS if success, AVCENC_FAIL otherwise."
    */
    OSCL_IMPORT_REF AVCEnc_Status PVAVCEncGetNALType(uint8 *bitstream, int size, int *nal_type, int *nal_ref_idc);

    /**
    This function returns the pointer to internal overrun buffer. Users can call this to query
    whether the overrun buffer has been used to encode the current NAL.
    \param "avcHandle"  "Pointer to the handle."
    \return "Pointer to overrun buffer if it is used, otherwise, NULL."
    */
    OSCL_IMPORT_REF uint8* PVAVCEncGetOverrunBuffer(AVCHandle* avcHandle);

    /**
    This function returns the reconstructed frame of the most recently encoded frame.
    Note that this frame is not returned to the users yet. Users should only read the
    content of this frame.
    \param "avcHandle"  "Handle to the AVC encoder library object."
    \param "output"     "Pointer to the input structure."
    \return "AVCENC_SUCCESS for success, AVCENC_NO_PICTURE if no picture to be outputted."
    */
    OSCL_IMPORT_REF AVCEnc_Status PVAVCEncGetRecon(AVCHandle *avcHandle, AVCFrameIO *recon);

    /**
    This function is used to return the recontructed frame back to the AVC encoder library
    in order to be re-used for encoding operation. If users want the content of it to remain
    unchanged for a long time, they should make a copy of it and release the memory back to
    the encoder. The encoder relies on the id element in the AVCFrameIO structure,
    thus users should not change the id value.
    \param "avcHandle"  "Handle to the AVC decoder library object."
    \param "output"      "Pointer to the AVCFrameIO structure."
    \return "AVCENC_SUCCESS for success, AVCENC_FAIL for fail for id not found."
    */
    OSCL_IMPORT_REF AVCEnc_Status PVAVCEncReleaseRecon(AVCHandle *avcHandle, AVCFrameIO *recon);

    /**
    This function performs clean up operation including memory deallocation.
    The encoder will also clear the list of input structures it has not released.
    This implies that users must keep track of the number of input structure they have allocated
    and free them accordingly.
    \param "avcHandle"  "Handle to the AVC encoder library object."
    */
    OSCL_IMPORT_REF void    PVAVCCleanUpEncoder(AVCHandle *avcHandle);

    /**
    This function extracts statistics of the current frame. If the encoder has not finished
    with the current frame, the result is not accurate.
    \param "avcHandle"  "Handle to the AVC encoder library object."
    \param "avcStats"   "Pointer to AVCEncFrameStats structure."
    \return "void."
    */
    void PVAVCEncGetFrameStats(AVCHandle *avcHandle, AVCEncFrameStats *avcStats);

    /**
    These functions are used for the modification of encoding parameters.
    To be polished.
    */
    OSCL_IMPORT_REF AVCEnc_Status PVAVCEncUpdateBitRate(AVCHandle *avcHandle, uint32 bitrate);
    OSCL_IMPORT_REF AVCEnc_Status PVAVCEncUpdateFrameRate(AVCHandle *avcHandle, uint32 num, uint32 denom);
    OSCL_IMPORT_REF AVCEnc_Status PVAVCEncUpdateIDRInterval(AVCHandle *avcHandle, int IDRInterval);
    OSCL_IMPORT_REF AVCEnc_Status PVAVCEncIDRRequest(AVCHandle *avcHandle);
    OSCL_IMPORT_REF AVCEnc_Status PVAVCEncUpdateIMBRefresh(AVCHandle *avcHandle, int numMB);


#ifdef __cplusplus
}
#endif
#endif  /* _AVCENC_API_H_ */

