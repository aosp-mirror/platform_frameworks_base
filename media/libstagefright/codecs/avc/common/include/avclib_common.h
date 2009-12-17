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
This file contains declarations of internal functions for common encoder/decoder library.
@publishedAll
*/
#ifndef AVCCOMMON_LIB_H_INCLUDED
#define AVCCOMMON_LIB_H_INCLUDED

#include <stdlib.h>

#ifndef AVCINT_COMMON_H_INCLUDED
#include "avcint_common.h"
#endif

/*----------- deblock.c --------------*/
/**
This function performs conditional deblocking on a complete picture.
\param "video"  "Pointer to AVCCommonObj."
\return "AVC_SUCCESS for success and AVC_FAIL otherwise."
*/
OSCL_IMPORT_REF AVCStatus DeblockPicture(AVCCommonObj *video);

/**
This function performs MB-based deblocking when MB_BASED_DEBLOCK
is defined at compile time.
\param "video"  "Pointer to AVCCommonObj."
\return "AVC_SUCCESS for success and AVC_FAIL otherwise."
*/
void MBInLoopDeblock(AVCCommonObj *video);


/*---------- dpb.c --------------------*/
/**
This function is called everytime a new sequence is detected.
\param "avcHandle"  "Pointer to AVCHandle."
\param "video" "Pointer to AVCCommonObj."
\param "padding"    "Flag specifying whether padding in luma component is needed (used for encoding)."
\return "AVC_SUCCESS or AVC_FAIL."
*/
OSCL_IMPORT_REF AVCStatus AVCConfigureSequence(AVCHandle *avcHandle, AVCCommonObj *video, bool padding);

/**
This function allocates and initializes the decoded picture buffer structure based on
the profile and level for the first sequence parameter set. Currently,
it does not allow changing in profile/level for subsequent SPS.
\param "avcHandle"  "Pointer to AVCHandle."
\param "video" "Pointer to AVCCommonObj."
\param "FrameHeightInMbs"   "Height of the frame in the unit of MBs."
\param "PicWidthInMbs"  "Width of the picture in the unit of MBs."
\param "padding"    "Flag specifying whether padding in luma component is needed (used for encoding)."
\return "AVC_SUCCESS or AVC_FAIL."
*/
AVCStatus InitDPB(AVCHandle *avcHandle, AVCCommonObj *video, int FrameHeightInMbs, int PicWidthInMbs, bool padding);

/**
This function frees the DPB memory.
\param "avcHandle"  "Pointer to AVCHandle."
\param "video" "Pointer to AVCCommonObj."
\return "AVC_SUCCESS or AVC_FAIL."
*/
OSCL_IMPORT_REF AVCStatus CleanUpDPB(AVCHandle *avcHandle, AVCCommonObj *video);

/**
This function finds empty frame in the decoded picture buffer to be used for the
current picture, initializes the corresponding picture structure with Sl, Scb, Scr,
width, height and pitch.
\param "avcHandle" "Pointer to the main handle object."
\param "video"  "Pointer to AVCCommonObj."
\return "AVC_SUCCESS or AVC_FAIL."
*/
OSCL_IMPORT_REF AVCStatus DPBInitBuffer(AVCHandle *avcHandle, AVCCommonObj *video);
/**
This function finds empty frame in the decoded picture buffer to be used for the
current picture, initializes the corresponding picture structure with Sl, Scb, Scr,
width, height and pitch.
\param "video"  "Pointer to AVCCommonObj."
\param "CurrPicNum" "Current picture number (only used in decoder)."
\return "AVC_SUCCESS or AVC_FAIL."
*/

OSCL_IMPORT_REF void DPBInitPic(AVCCommonObj *video, int CurrPicNum);

/**
This function releases the current frame back to the available pool for skipped frame after encoding.
\param "avcHandle" "Pointer to the main handle object."
\param "video" "Pointer to the AVCCommonObj."
\return "void."
*/
OSCL_IMPORT_REF void DPBReleaseCurrentFrame(AVCHandle *avcHandle, AVCCommonObj *video);

/**
This function performs decoded reference picture marking process and store the current picture to the
corresponding frame storage in the decoded picture buffer.
\param "avcHandle" "Pointer to the main handle object."
\param "video" "Pointer to the AVCCommonObj."
\return "AVC_SUCCESS or AVC_FAIL."
*/
OSCL_IMPORT_REF AVCStatus StorePictureInDPB(AVCHandle *avcHandle, AVCCommonObj *video);

/**
This function perform sliding window operation on the reference picture lists, see subclause 8.2.5.3.
It removes short-term ref frames with smallest FrameNumWrap from the reference list.
\param "avcHandle" "Pointer to the main handle object."
\param "video" "Pointer to the AVCCommonObj."
\param "dpb"  "Pointer to the AVCDecPicBuffer."
\return "AVC_SUCCESS or AVC_FAIL (contradicting values or scenario as in the Note in the draft)."
*/
AVCStatus sliding_window_process(AVCHandle *avcHandle, AVCCommonObj *video, AVCDecPicBuffer *dpb);


/**
This function perform adaptive memory marking operation on the reference picture lists,
see subclause 8.2.5.4. It calls other functions for specific operations.
\param "video" "Pointer to the AVCCommonObj."
\param "dpb"  "Pointer to the AVCDecPicBuffer."
\param "sliceHdr"   "Pointer to the AVCSliceHeader."
\return "AVC_SUCCESS or AVC_FAIL (contradicting values or scenario as in the Note in the draft)."
*/
AVCStatus adaptive_memory_marking(AVCHandle *avcHandle, AVCCommonObj *video, AVCDecPicBuffer *dpb, AVCSliceHeader *sliceHdr);

/**
This function performs memory management control operation 1, marking a short-term picture
as unused for reference. See subclause 8.2.5.4.1.
\param "video" "Pointer to the AVCCommonObj."
\param "dpb"  "Pointer to the AVCDecPicBuffer."
\param "difference_of_pic_nums_minus1"  "From the syntax in dec_ref_pic_marking()."
*/
void MemMgrCtrlOp1(AVCHandle *avcHandle, AVCCommonObj *video, AVCDecPicBuffer *dpb, int difference_of_pic_nums_minus1);

/**
This function performs memory management control operation 2, marking a long-term picture
as unused for reference. See subclause 8.2.5.4.2.
\param "dpb"  "Pointer to the AVCDecPicBuffer."
\param "field_pic_flag"  "Flag whether the current picture is field or not."
\param "long_term_pic_num"  "From the syntax in dec_ref_pic_marking()."
*/
void MemMgrCtrlOp2(AVCHandle *avcHandle, AVCDecPicBuffer *dpb, int long_term_pic_num);

/**
This function performs memory management control operation 3, assigning a LongTermFrameIdx to
a short-term reference picture. See subclause 8.2.5.4.3.
\param "video" "Pointer to the AVCCommonObj."
\param "dpb"  "Pointer to the AVCDecPicBuffer."
\param "difference_of_pic_nums_minus1"  "From the syntax in dec_ref_pic_marking()."
\param "long_term_pic_num"  "From the syntax in dec_ref_pic_marking()."
*/
void MemMgrCtrlOp3(AVCHandle *avcHandle, AVCCommonObj *video, AVCDecPicBuffer *dpb, uint difference_of_pic_nums_minus1,
                   uint long_term_frame_idx);

/**
This function performs memory management control operation 4, getting new MaxLongTermFrameIdx.
 See subclause 8.2.5.4.4.
\param "video" "Pointer to the AVCCommonObj."
\param "dpb"  "Pointer to the AVCDecPicBuffer."
\param "max_long_term_frame_idx_plus1"  "From the syntax in dec_ref_pic_marking()."
*/
void MemMgrCtrlOp4(AVCHandle *avcHandle, AVCCommonObj *video, AVCDecPicBuffer *dpb, uint max_long_term_frame_idx_plus1);

/**
This function performs memory management control operation 5, marking all reference pictures
as unused for reference and set MaxLongTermFrameIdx to no long-termframe indices.
 See subclause 8.2.5.4.5.
\param "video" "Pointer to the AVCCommonObj."
\param "dpb"  "Pointer to the AVCDecPicBuffer."
*/
void MemMgrCtrlOp5(AVCHandle *avcHandle, AVCCommonObj *video, AVCDecPicBuffer *dpb);

/**
This function performs memory management control operation 6, assigning a long-term frame index
to the current picture. See subclause 8.2.5.4.6.
\param "video" "Pointer to the AVCCommonObj."
\param "dpb"  "Pointer to the AVCDecPicBuffer."
\param "long_term_frame_idx"  "From the syntax in dec_ref_pic_marking()."
*/
void MemMgrCtrlOp6(AVCHandle *avcHandle, AVCCommonObj *video, AVCDecPicBuffer *dpb, uint long_term_frame_idx);

/**
This function mark a long-term ref frame with a specific frame index as unused for reference.
\param "dpb"  "Pointer to the AVCDecPicBuffer."
\param "long_term_frame_idx"  "To look for"
*/
void unmark_long_term_frame_for_reference_by_frame_idx(AVCHandle *avcHandle, AVCDecPicBuffer *dpb, uint long_term_frame_idx);

/**
This function mark a long-term ref field with a specific frame index as unused for reference except
a frame that contains a picture with picNumX.
\param "dpb"  "Pointer to the AVCDecPicBuffer."
\param "long_term_frame_idx"  "To look for."
\param "picNumX"    "To look for."
*/
void unmark_long_term_field_for_reference_by_frame_idx(AVCCommonObj *video, AVCDecPicBuffer *dpb, uint long_term_frame_indx, int picNumX);

/**
This function mark a frame to unused for reference.
\param "fs" "Pointer to AVCFrameStore to be unmarked."
*/
void unmark_for_reference(AVCHandle *avcHandle, AVCDecPicBuffer *dpb, uint idx);

void update_ref_list(AVCDecPicBuffer *dpb);


/*---------- fmo.c --------------*/
/**
This function initializes flexible macroblock reordering.
\param "video"  "Pointer to AVCCommonObj."
\return "AVC_SUCCESS for success and AVC_FAIL otherwise."
*/
OSCL_IMPORT_REF AVCStatus FMOInit(AVCCommonObj *video);

/**
This function fills up an array that maps Map unit to the slice group
following the interleaved slice group map type.
\param "mapUnitToSliceGroupMap" "Array of slice group mapping."
\param "run_length_minus1"  "Array of the run-length."
\param "num_slice_groups_minus_1"   "Number of slice group minus 1."
\param "PicSizeInMapUnit"   "Size of the picture in number Map units."
\return "Void."
*/
void FmoGenerateType0MapUnitMap(int *mapUnitToSliceGroupMap, uint *run_length_minus1, uint num_slice_groups_minus1, uint PicSizeInMapUnits);

/**
This function fills up an array that maps Map unit to the slice group
following the dispersed slice group map type.
\param "mapUnitToSliceGroupMap" "Array of slice group mapping."
\param "PicWidthInMbs"  "Width of the luma picture in macroblock unit."
\param "num_slice_groups_minus_1"   "Number of slice group minus 1."
\param "PicSizeInMapUnit"   "Size of the picture in number Map units."
\return "Void."
*/
void FmoGenerateType1MapUnitMap(int *mapUnitToSliceGroupMap, int PicWidthInMbs, uint num_slice_groups_minus1, uint PicSizeInMapUnits);

/**
This function fills up an array that maps Map unit to the slice group
following the foreground with left-over slice group map type.
\param "pps"    "Pointer to AVCPicParamSets structure."
\param "mapUnitToSliceGroupMap" "Array of slice group mapping."
\param "PicWidthInMbs"  "Width of the luma picture in macroblock unit."
\param "num_slice_groups_minus_1"   "Number of slice group minus 1."
\param "PicSizeInMapUnit"   "Size of the picture in number Map units."
\return "Void."
*/
void FmoGenerateType2MapUnitMap(AVCPicParamSet *pps, int *mapUnitToSliceGroupMap, int PicWidthInMbs,
                                uint num_slice_groups_minus1, uint PicSizeInMapUnits);

/**
This function fills up an array that maps Map unit to the slice group
following the box-out slice group map type.
\param "pps"    "Pointer to AVCPicParamSets structure."
\param "mapUnitToSliceGroupMap" "Array of slice group mapping."
\param "PicWidthInMbs"  "Width of the luma picture in macroblock unit."
\return "Void."
*/
void FmoGenerateType3MapUnitMap(AVCCommonObj *video, AVCPicParamSet* pps, int *mapUnitToSliceGroupMap,
                                int PicWidthInMbs);

/**
This function fills up an array that maps Map unit to the slice group
following the raster scan slice group map type.
\param "mapUnitToSliceGroupMap" "Array of slice group mapping."
\param "MapUnitsInSliceGroup0"  "Derived in subclause 7.4.3."
\param "slice_group_change_direction_flag"  "A value from the slice header."
\param "PicSizeInMapUnit"   "Size of the picture in number Map units."
\return "void"
*/
void FmoGenerateType4MapUnitMap(int *mapUnitToSliceGroupMap, int MapUnitsInSliceGroup0,
                                int slice_group_change_direction_flag, uint PicSizeInMapUnits);

/**
This function fills up an array that maps Map unit to the slice group
following wipe slice group map type.
\param "mapUnitToSliceGroupMap" "Array of slice group mapping."
\param "video"  "Pointer to AVCCommonObj structure."
\param "slice_group_change_direction_flag"  "A value from the slice header."
\param "PicSizeInMapUnit"   "Size of the picture in number Map units."
\return "void"
*/
void FmoGenerateType5MapUnitMap(int *mapUnitsToSliceGroupMap, AVCCommonObj *video,
                                int slice_group_change_direction_flag, uint PicSizeInMapUnits);

/**
This function fills up an array that maps Map unit to the slice group
following wipe slice group map type.
\param "mapUnitToSliceGroupMap" "Array of slice group mapping."
\param "slice_group_id" "Array of slice_group_id from AVCPicParamSet structure."
\param "PicSizeInMapUnit"   "Size of the picture in number Map units."
\return "void"
*/
void FmoGenerateType6MapUnitMap(int *mapUnitsToSliceGroupMap, int *slice_group_id, uint PicSizeInMapUnits);

/*------------- itrans.c --------------*/
/**
This function performs transformation of the Intra16x16DC value according to
subclause 8.5.6.
\param "block"  "Pointer to the video->block[0][0][0]."
\param "QPy"    "Quantization parameter."
\return "void."
*/
void Intra16DCTrans(int16 *block, int Qq, int Rq);

/**
This function performs transformation of a 4x4 block according to
subclause 8.5.8.
\param "block"  "Pointer to the origin of transform coefficient area."
\param "pred"   "Pointer to the origin of predicted area."
\param "cur"    "Pointer to the origin of the output area."
\param "width"  "Pitch of cur."
\return "void."
*/
void itrans(int16 *block, uint8 *pred, uint8 *cur, int width);

/*
This function is the same one as itrans except for chroma.
\param "block"  "Pointer to the origin of transform coefficient area."
\param "pred"   "Pointer to the origin of predicted area."
\param "cur"    "Pointer to the origin of the output area."
\param "width"  "Pitch of cur."
\return "void."
*/
void ictrans(int16 *block, uint8 *pred, uint8 *cur, int width);

/**
This function performs transformation of the DCChroma value according to
subclause 8.5.7.
\param "block"  "Pointer to the video->block[0][0][0]."
\param "QPc"    "Quantization parameter."
\return "void."
*/
void ChromaDCTrans(int16 *block, int Qq, int Rq);

/**
This function copies a block from pred to cur.
\param "pred"   "Pointer to prediction block."
\param "cur"    "Pointer to the current YUV block."
\param "width"  "Pitch of cur memory."
\param "pred_pitch" "Pitch for pred memory.
\return "void."
*/
void copy_block(uint8 *pred, uint8 *cur, int width, int pred_pitch);

/*--------- mb_access.c ----------------*/
/**
This function initializes the neighboring information before start macroblock decoding.
\param "video"  "Pointer to AVCCommonObj."
\param "mbNum"  "The current macroblock index."
\param "currMB" "Pointer to the current AVCMacroblock structure."
\return "void"
*/
OSCL_IMPORT_REF void InitNeighborAvailability(AVCCommonObj *video, int mbNum);

/**
This function checks whether the requested neighboring macroblock is available.
\param "MbToSliceGroupMap"  "Array containing the slice group ID mapping to MB index."
\param "PicSizeInMbs"   "Size of the picture in number of MBs."
\param "mbAddr"     "Neighboring macroblock index to check."
\param "currMbAddr" "Current macroblock index."
\return "TRUE if the neighboring MB is available, FALSE otherwise."
*/
bool mb_is_available(AVCMacroblock *mblock, uint PicSizeInMbs, int mbAddr, int currMbAddr);

/**
This function performs prediction of the nonzero coefficient for a luma block (i,j).
\param "video"  "Pointer to AVCCommonObj."
\param "i"  "Block index, horizontal."
\param "j"  "Block index, vertical."
\return "Predicted number of nonzero coefficient."
*/
OSCL_IMPORT_REF int predict_nnz(AVCCommonObj *video, int i, int j);

/**
This function performs prediction of the nonzero coefficient for a chroma block (i,j).
\param "video"  "Pointer to AVCCommonObj."
\param "i"  "Block index, horizontal."
\param "j"  "Block index, vertical."
\return "Predicted number of nonzero coefficient."
*/
OSCL_IMPORT_REF int predict_nnz_chroma(AVCCommonObj *video, int i, int j);

/**
This function calculates the predicted motion vectors for the current macroblock.
\param "video" "Pointer to AVCCommonObj."
\param "encFlag"    "Boolean whether this function is used by encoder or decoder."
\return "void."
*/
OSCL_IMPORT_REF void GetMotionVectorPredictor(AVCCommonObj *video, int encFlag);

/*---------- reflist.c -----------------*/
/**
This function initializes reference picture list used in INTER prediction
at the beginning of each slice decoding. See subclause 8.2.4.
\param "video"  "Pointer to AVCCommonObj."
\return "void"
Output is video->RefPicList0, video->RefPicList1, video->refList0Size and video->refList1Size.
*/
OSCL_IMPORT_REF void RefListInit(AVCCommonObj *video);

/**
This function generates picture list from frame list. Used when current picture is field.
see subclause 8.2.4.2.5.
\param "video"  "Pointer to AVCCommonObj."
\param "IsL1"   "Is L1 list?"
\param "long_term"  "Is long-term prediction?"
\return "void"
*/
void    GenPicListFromFrameList(AVCCommonObj *video, int IsL1, int long_term);

/**
This function performs reference picture list reordering according to the
ref_pic_list_reordering() syntax. See subclause 8.2.4.3.
\param "video"  "Pointer to AVCCommonObj."
\return "AVC_SUCCESS or AVC_FAIL"
Output is video->RefPicList0, video->RefPicList1, video->refList0Size and video->refList1Size.
*/
OSCL_IMPORT_REF AVCStatus ReOrderList(AVCCommonObj *video);

/**
This function performs reference picture list reordering according to the
ref_pic_list_reordering() syntax regardless of list 0 or list 1. See subclause 8.2.4.3.
\param "video"  "Pointer to AVCCommonObj."
\param "isL1"   "Is list 1 or not."
\return "AVC_SUCCESS or AVC_FAIL"
Output is video->RefPicList0 and video->refList0Size or video->RefPicList1 and video->refList1Size.
*/
AVCStatus ReorderRefPicList(AVCCommonObj *video, int isL1);

/**
This function performs reordering process of reference picture list for short-term pictures.
See subclause 8.2.4.3.1.
\param "video"  "Pointer to AVCCommonObj."
\param "picNumLX"   "picNumLX of an entry in the reference list."
\param "refIdxLX"   "Pointer to the current entry index in the reference."
\param "isL1"       "Is list 1 or not."
\return "AVC_SUCCESS or AVC_FAIL"
*/
AVCStatus ReorderShortTerm(AVCCommonObj *video, int picNumLX, int *refIdxLX, int isL1);

/**
This function performs reordering process of reference picture list for long-term pictures.
See subclause 8.2.4.3.2.
\param "video"  "Pointer to AVCCommonObj."
\param "LongTermPicNum" "LongTermPicNum of an entry in the reference list."
\param "refIdxLX"   "Pointer to the current entry index in the reference."
\param "isL1"       "Is list 1 or not."
\return "AVC_SUCCESS or AVC_FAIL"
*/
AVCStatus ReorderLongTerm(AVCCommonObj *video, int LongTermPicNum, int *refIdxLX, int isL1);

/**
This function gets the pictures in DPB according to the PicNum.
\param "video"  "Pointer to AVCCommonObj."
\param "picNum" "PicNum of the picture we are looking for."
\return "Pointer to the AVCPictureData or NULL if not found"
*/
AVCPictureData*  GetShortTermPic(AVCCommonObj *video, int picNum);

/**
This function gets the pictures in DPB according to the LongtermPicNum.
\param "video"  "Pointer to AVCCommonObj."
\param "LongtermPicNum" "LongtermPicNum of the picture we are looking for."
\return "Pointer to the AVCPictureData."
*/
AVCPictureData*  GetLongTermPic(AVCCommonObj *video, int LongtermPicNum);

/**
This function indicates whether the picture is used for short-term reference or not.
\param "s"  "Pointer to AVCPictureData."
\return "1 if it is used for short-term, 0 otherwise."
*/
int is_short_ref(AVCPictureData *s);

/**
This function indicates whether the picture is used for long-term reference or not.
\param "s"  "Pointer to AVCPictureData."
\return "1 if it is used for long-term, 0 otherwise."
*/
int is_long_ref(AVCPictureData *s);

/**
This function sorts array of pointers to AVCPictureData in descending order of
the PicNum value.
\param "data"   "Array of pointers to AVCPictureData."
\param "num"    "Size of the array."
\return "void"
*/
void SortPicByPicNum(AVCPictureData *data[], int num);

/**
This function sorts array of pointers to AVCPictureData in ascending order of
the PicNum value.
\param "data"   "Array of pointers to AVCPictureData."
\param "num"    "Size of the array."
\return "void"
*/
void SortPicByPicNumLongTerm(AVCPictureData *data[], int num);

/**
This function sorts array of pointers to AVCFrameStore in descending order of
the FrameNumWrap value.
\param "data"   "Array of pointers to AVCFrameStore."
\param "num"    "Size of the array."
\return "void"
*/
void SortFrameByFrameNumWrap(AVCFrameStore *data[], int num);

/**
This function sorts array of pointers to AVCFrameStore in ascending order of
the LongTermFrameIdx value.
\param "data"   "Array of pointers to AVCFrameStore."
\param "num"    "Size of the array."
\return "void"
*/
void SortFrameByLTFrameIdx(AVCFrameStore *data[], int num);

/**
This function sorts array of pointers to AVCPictureData in descending order of
the PicOrderCnt value.
\param "data"   "Array of pointers to AVCPictureData."
\param "num"    "Size of the array."
\return "void"
*/
void SortPicByPOC(AVCPictureData *data[], int num, int descending);

/**
This function sorts array of pointers to AVCPictureData in ascending order of
the LongTermPicNum value.
\param "data"   "Array of pointers to AVCPictureData."
\param "num"    "Size of the array."
\return "void"
*/
void SortPicByLTPicNum(AVCPictureData *data[], int num);

/**
This function sorts array of pointers to AVCFrameStore in descending order of
the PicOrderCnt value.
\param "data"   "Array of pointers to AVCFrameStore."
\param "num"    "Size of the array."
\return "void"
*/
void SortFrameByPOC(AVCFrameStore *data[], int num, int descending);


#endif /* _AVCCOMMON_LIB_H_ */
