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
This file contains declarations of internal functions for AVC decoder library.
@publishedAll
*/
#ifndef _AVCDEC_LIB_H_
#define _AVCDEC_LIB_H_

#include "avclib_common.h"
#include "avcdec_int.h"

/*----------- avcdec_api.c -------------*/
/**
This function takes out the emulation prevention bytes from the input to creat RBSP.
The result is written over the input bitstream.
\param "nal_unit"   "(I/O) Pointer to the input buffer."
\param "size"       "(I/O) Pointer to the size of the input/output buffer."
\return "AVCDEC_SUCCESS for success and AVCDEC_FAIL otherwise."
*/
AVCDec_Status EBSPtoRBSP(uint8 *nal_unit, int *size);

/*------------- pred_intra.c ---------------*/
/**
This function is the main entry point to intra prediction operation on a
macroblock.
\param "video"  "Pointer to AVCCommonObj."
*/
AVCStatus  IntraMBPrediction(AVCCommonObj *video);

void SaveNeighborForIntraPred(AVCCommonObj *video, int offset);

AVCStatus Intra_4x4(AVCCommonObj *video, int component, int SubBlock_indx, uint8 *comp);
void Intra_4x4_Vertical(AVCCommonObj *video, int block_offset);
void Intra_4x4_Horizontal(AVCCommonObj *video, int pitch, int block_offset);
void Intra_4x4_DC(AVCCommonObj *video, int pitch, int block_offset, AVCNeighborAvailability *availability);
void Intra_4x4_Down_Left(AVCCommonObj *video, int block_offset, AVCNeighborAvailability *availability);
void Intra_4x4_Diagonal_Down_Right(AVCCommonObj *video, int pitch, int block_offset);
void Intra_4x4_Diagonal_Vertical_Right(AVCCommonObj *video, int pitch, int block_offset);
void Intra_4x4_Diagonal_Horizontal_Down(AVCCommonObj *video, int pitch, int block_offset);
void Intra_4x4_Vertical_Left(AVCCommonObj *video,  int block_offset, AVCNeighborAvailability *availability);
void Intra_4x4_Horizontal_Up(AVCCommonObj *video, int pitch, int block_offset);
void  Intra_16x16_Vertical(AVCCommonObj *video);
void Intra_16x16_Horizontal(AVCCommonObj *video, int pitch);
void Intra_16x16_DC(AVCCommonObj *video, int pitch);
void Intra_16x16_Plane(AVCCommonObj *video, int pitch);
void Intra_Chroma_DC(AVCCommonObj *video, int pitch, uint8 *predCb, uint8 *predCr);
void  Intra_Chroma_Horizontal(AVCCommonObj *video, int pitch, uint8 *predCb, uint8 *predCr);
void  Intra_Chroma_Vertical(AVCCommonObj *video, uint8 *predCb, uint8 *predCr);
void  Intra_Chroma_Plane(AVCCommonObj *video, int pitch, uint8 *predCb, uint8 *predCr);

/*------------ pred_inter.c ---------------*/
/**
This function is the main entrance to inter prediction operation for
a macroblock. For decoding, this function also calls inverse transform and
compensation.
\param "video"  "Pointer to AVCCommonObj."
\return "void"
*/
void InterMBPrediction(AVCCommonObj *video);

/**
This function is called for luma motion compensation.
\param "ref"    "Pointer to the origin of a reference luma."
\param "picwidth"   "Width of the picture."
\param "picheight"  "Height of the picture."
\param "x_pos"  "X-coordinate of the predicted block in quarter pel resolution."
\param "y_pos"  "Y-coordinate of the predicted block in quarter pel resolution."
\param "pred"   "Pointer to the output predicted block."
\param "pred_pitch" "Width of pred."
\param "blkwidth"   "Width of the current partition."
\param "blkheight"  "Height of the current partition."
\return "void"
*/
void LumaMotionComp(uint8 *ref, int picwidth, int picheight,
                    int x_pos, int y_pos,
                    uint8 *pred, int pred_pitch,
                    int blkwidth, int blkheight);

/**
Functions below are special cases for luma motion compensation.
LumaFullPelMC is for full pixel motion compensation.
LumaBorderMC is for interpolation in only one dimension.
LumaCrossMC is for interpolation in one dimension and half point in the other dimension.
LumaDiagonalMC is for interpolation in diagonal direction.

\param "ref"    "Pointer to the origin of a reference luma."
\param "picwidth"   "Width of the picture."
\param "picheight"  "Height of the picture."
\param "x_pos"  "X-coordinate of the predicted block in full pel resolution."
\param "y_pos"  "Y-coordinate of the predicted block in full pel resolution."
\param "dx"     "Fraction of x_pos in quarter pel."
\param "dy"     "Fraction of y_pos in quarter pel."
\param "curr"   "Pointer to the current partition in the current picture."
\param "residue"    "Pointer to the current partition for the residue block."
\param "blkwidth"   "Width of the current partition."
\param "blkheight"  "Height of the current partition."
\return "void"
*/
void CreatePad(uint8 *ref, int picwidth, int picheight, int x_pos, int y_pos,
               uint8 *out, int blkwidth, int blkheight);

void FullPelMC(uint8 *in, int inwidth, uint8 *out, int outpitch,
               int blkwidth, int blkheight);

void HorzInterp1MC(uint8 *in, int inpitch, uint8 *out, int outpitch,
                   int blkwidth, int blkheight, int dx);

void HorzInterp2MC(int *in, int inpitch, uint8 *out, int outpitch,
                   int blkwidth, int blkheight, int dx);

void HorzInterp3MC(uint8 *in, int inpitch, int *out, int outpitch,
                   int blkwidth, int blkheight);

void VertInterp1MC(uint8 *in, int inpitch, uint8 *out, int outpitch,
                   int blkwidth, int blkheight, int dy);

void VertInterp2MC(uint8 *in, int inpitch, int *out, int outpitch,
                   int blkwidth, int blkheight);

void VertInterp3MC(int *in, int inpitch, uint8 *out, int outpitch,
                   int blkwidth, int blkheight, int dy);

void DiagonalInterpMC(uint8 *in1, uint8 *in2, int inpitch,
                      uint8 *out, int outpitch,
                      int blkwidth, int blkheight);


void ChromaMotionComp(uint8 *ref, int picwidth, int picheight,
                      int x_pos, int y_pos, uint8 *pred, int pred_pitch,
                      int blkwidth, int blkheight);

void ChromaFullPelMC(uint8 *in, int inpitch, uint8 *out, int outpitch,
                     int blkwidth, int blkheight) ;
void ChromaBorderMC(uint8 *ref, int picwidth, int dx, int dy,
                    uint8 *pred, int pred_pitch, int blkwidth, int blkheight);
void ChromaDiagonalMC(uint8 *ref, int picwidth, int dx, int dy,
                      uint8 *pred, int pred_pitch, int blkwidth, int blkheight);

void ChromaFullPelMCOutside(uint8 *ref, uint8 *pred, int pred_pitch,
                            int blkwidth, int blkheight, int x_inc,
                            int y_inc0, int y_inc1, int x_mid, int y_mid);
void ChromaBorderMCOutside(uint8 *ref, int picwidth, int dx, int dy,
                           uint8 *pred, int pred_pitch, int blkwidth, int blkheight,
                           int x_inc, int z_inc, int y_inc0, int y_inc1, int x_mid, int y_mid);
void ChromaDiagonalMCOutside(uint8 *ref, int picwidth,
                             int dx, int dy, uint8 *pred, int pred_pitch,
                             int blkwidth, int blkheight, int x_inc, int z_inc,
                             int y_inc0, int y_inc1, int x_mid, int y_mid);

void ChromaDiagonalMC_SIMD(uint8 *pRef, int srcPitch, int dx, int dy,
                           uint8 *pOut, int predPitch, int blkwidth, int blkheight);

void ChromaHorizontalMC_SIMD(uint8 *pRef, int srcPitch, int dx, int dy,
                             uint8 *pOut, int predPitch, int blkwidth, int blkheight);

void ChromaVerticalMC_SIMD(uint8 *pRef, int srcPitch, int dx, int dy,
                           uint8 *pOut, int predPitch, int blkwidth, int blkheight);

void ChromaFullMC_SIMD(uint8 *pRef, int srcPitch, int dx, int dy,
                       uint8 *pOut, int predPitch, int blkwidth, int blkheight);

void ChromaVerticalMC2_SIMD(uint8 *pRef, int srcPitch, int dx, int dy,
                            uint8 *pOut, int predPitch, int blkwidth, int blkheight);

void ChromaHorizontalMC2_SIMD(uint8 *pRef, int srcPitch, int dx, int dy,
                              uint8 *pOut, int predPitch, int blkwidth, int blkheight);

void ChromaDiagonalMC2_SIMD(uint8 *pRef, int srcPitch, int dx, int dy,
                            uint8 *pOut, int predPitch, int blkwidth, int blkheight);


/*----------- slice.c ---------------*/
/**
This function performs the main decoding loop for slice data including
INTRA/INTER prediction, transform and quantization and compensation.
See decode_frame_slice() in JM.
\param "video"  "Pointer to AVCDecObject."
\return "AVCDEC_SUCCESS for success, AVCDEC_PICTURE_READY for end-of-picture and AVCDEC_FAIL otherwise."
*/
AVCDec_Status DecodeSlice(AVCDecObject *video);
AVCDec_Status ConcealSlice(AVCDecObject *decvid, int mbnum_start, int mbnum_end);
/**
This function performs the decoding of one macroblock.
\param "video"  "Pointer to AVCDecObject."
\param "prevMbSkipped"  "A value derived in 7.3.4."
\return "AVCDEC_SUCCESS for success or AVCDEC_FAIL otherwise."
*/
AVCDec_Status DecodeMB(AVCDecObject *video);

/**
This function performs macroblock prediction type decoding as in subclause 7.3.5.1.
\param "video" "Pointer to AVCCommonObj."
\param "currMB" "Pointer to the current macroblock."
\param "stream" "Pointer to AVCDecBitstream."
\return "AVCDEC_SUCCESS for success or AVCDEC_FAIL otherwise."
*/
AVCDec_Status mb_pred(AVCCommonObj *video, AVCMacroblock *currMB, AVCDecBitstream *stream);

/**
This function performs sub-macroblock prediction type decoding as in subclause 7.3.5.2.
\param "video" "Pointer to AVCCommonObj."
\param "currMB" "Pointer to the current macroblock."
\param "stream" "Pointer to AVCDecBitstream."
\return "AVCDEC_SUCCESS for success or AVCDEC_FAIL otherwise."
*/
AVCDec_Status sub_mb_pred(AVCCommonObj *video, AVCMacroblock *currMB, AVCDecBitstream *stream);

/**
This function interprets the mb_type and sets necessary information
when the slice type is AVC_I_SLICE.
in the macroblock structure.
\param "mblock" "Pointer to current AVCMacroblock."
\param "mb_type" "From the syntax bitstream."
\return "void"
*/
void InterpretMBModeI(AVCMacroblock *mblock, uint mb_type);

/**
This function interprets the mb_type and sets necessary information
when the slice type is AVC_P_SLICE.
in the macroblock structure.
\param "mblock" "Pointer to current AVCMacroblock."
\param "mb_type" "From the syntax bitstream."
\return "void"
*/
void InterpretMBModeP(AVCMacroblock *mblock, uint mb_type);

/**
This function interprets the mb_type and sets necessary information
when the slice type is AVC_B_SLICE.
in the macroblock structure.
\param "mblock" "Pointer to current AVCMacroblock."
\param "mb_type" "From the syntax bitstream."
\return "void"
*/
void InterpretMBModeB(AVCMacroblock *mblock, uint mb_type);

/**
This function interprets the mb_type and sets necessary information
when the slice type is AVC_SI_SLICE.
in the macroblock structure.
\param "mblock" "Pointer to current AVCMacroblock."
\param "mb_type" "From the syntax bitstream."
\return "void"
*/
void InterpretMBModeSI(AVCMacroblock *mblock, uint mb_type);

/**
This function interprets the sub_mb_type and sets necessary information
when the slice type is AVC_P_SLICE.
in the macroblock structure.
\param "mblock" "Pointer to current AVCMacroblock."
\param "sub_mb_type" "From the syntax bitstream."
\return "void"
*/
void InterpretSubMBModeP(AVCMacroblock *mblock, uint *sub_mb_type);

/**
This function interprets the sub_mb_type and sets necessary information
when the slice type is AVC_B_SLICE.
in the macroblock structure.
\param "mblock" "Pointer to current AVCMacroblock."
\param "sub_mb_type" "From the syntax bitstream."
\return "void"
*/
void InterpretSubMBModeB(AVCMacroblock *mblock, uint *sub_mb_type);

/**
This function decodes the Intra4x4 prediction mode from neighboring information
and from the decoded syntax.
\param "video"  "Pointer to AVCCommonObj."
\param "currMB" "Pointer to current macroblock."
\param "stream" "Pointer to AVCDecBitstream."
\return "AVCDEC_SUCCESS or AVCDEC_FAIL."
*/
AVCDec_Status DecodeIntra4x4Mode(AVCCommonObj *video, AVCMacroblock *currMB, AVCDecBitstream *stream);

/*----------- vlc.c -------------------*/
/**
This function reads and decodes Exp-Golomb codes.
\param "bitstream" "Pointer to AVCDecBitstream."
\param "codeNum" "Pointer to the value of the codeNum."
\return "AVCDEC_SUCCESS or AVCDEC_FAIL."
*/
AVCDec_Status ue_v(AVCDecBitstream *bitstream, uint *codeNum);

/**
This function reads and decodes signed Exp-Golomb codes.
\param "bitstream" "Pointer to AVCDecBitstream."
\param "value"  "Pointer to syntax element value."
\return "AVCDEC_SUCCESS or AVCDEC_FAIL."
*/
AVCDec_Status  se_v(AVCDecBitstream *bitstream, int *value);

/**
This function reads and decodes signed Exp-Golomb codes for
32 bit codeword.
\param "bitstream" "Pointer to AVCDecBitstream."
\param "value"  "Pointer to syntax element value."
\return "AVCDEC_SUCCESS or AVCDEC_FAIL."
*/
AVCDec_Status  se_v32bit(AVCDecBitstream *bitstream, int32 *value);

/**
This function reads and decodes truncated Exp-Golomb codes.
\param "bitstream" "Pointer to AVCDecBitstream."
\param "value"  "Pointer to syntax element value."
\param "range"  "Range of the value as input to determine the algorithm."
\return "AVCDEC_SUCCESS or AVCDEC_FAIL."
*/
AVCDec_Status te_v(AVCDecBitstream *bitstream, uint *value, uint range);

/**
This function parse Exp-Golomb code from the bitstream.
\param "bitstream" "Pointer to AVCDecBitstream."
\param "leadingZeros" "Pointer to the number of leading zeros."
\param "infobits"   "Pointer to the value after leading zeros and the first one.
                    The total number of bits read is 2*leadingZeros + 1."
\return "AVCDEC_SUCCESS or AVCDEC_FAIL."
*/
AVCDec_Status GetEGBitstring(AVCDecBitstream *bitstream, int *leadingZeros, int *infobits);

/**
This function parse Exp-Golomb code from the bitstream for 32 bit codewords.
\param "bitstream" "Pointer to AVCDecBitstream."
\param "leadingZeros" "Pointer to the number of leading zeros."
\param "infobits"   "Pointer to the value after leading zeros and the first one.
                    The total number of bits read is 2*leadingZeros + 1."
\return "AVCDEC_SUCCESS or AVCDEC_FAIL."
*/
AVCDec_Status GetEGBitstring32bit(AVCDecBitstream *bitstream, int *leadingZeros, uint32 *infobits);

/**
This function performs CAVLC decoding of the CBP (coded block pattern) of a macroblock
by calling ue_v() and then mapping the codeNum to the corresponding CBP value.
\param "currMB"  "Pointer to the current AVCMacroblock structure."
\param "stream"  "Pointer to the AVCDecBitstream."
\return "void"
*/
AVCDec_Status DecodeCBP(AVCMacroblock *currMB, AVCDecBitstream *stream);

/**
This function decodes the syntax for trailing ones and total coefficient.
Subject to optimization.
\param "stream" "Pointer to the AVCDecBitstream."
\param "TrailingOnes"   "Pointer to the trailing one variable output."
\param "TotalCoeff" "Pointer to the total coefficient variable output."
\param "nC" "Context for number of nonzero coefficient (prediction context)."
\return "AVCDEC_SUCCESS for success."
*/
AVCDec_Status ce_TotalCoeffTrailingOnes(AVCDecBitstream *stream, int *TrailingOnes, int *TotalCoeff, int nC);

/**
This function decodes the syntax for trailing ones and total coefficient for
chroma DC block. Subject to optimization.
\param "stream" "Pointer to the AVCDecBitstream."
\param "TrailingOnes"   "Pointer to the trailing one variable output."
\param "TotalCoeff" "Pointer to the total coefficient variable output."
\return "AVCDEC_SUCCESS for success."
*/
AVCDec_Status ce_TotalCoeffTrailingOnesChromaDC(AVCDecBitstream *stream, int *TrailingOnes, int *TotalCoeff);

/**
This function decode a VLC table with 2 output.
\param "stream" "Pointer to the AVCDecBitstream."
\param "lentab" "Table for code length."
\param "codtab" "Table for code value."
\param "tabwidth" "Width of the table or alphabet size of the first output."
\param "tabheight"  "Height of the table or alphabet size of the second output."
\param "code1"  "Pointer to the first output."
\param "code2"  "Pointer to the second output."
\return "AVCDEC_SUCCESS for success."
*/
AVCDec_Status code_from_bitstream_2d(AVCDecBitstream *stream, int *lentab, int *codtab, int tabwidth,
                                     int tabheight, int *code1, int *code2);

/**
This function decodes the level_prefix VLC value as in Table 9-6.
\param "stream" "Pointer to the AVCDecBitstream."
\param "code"   "Pointer to the output."
\return "AVCDEC_SUCCESS for success."
*/
AVCDec_Status ce_LevelPrefix(AVCDecBitstream *stream, uint *code);

/**
This function decodes total_zeros VLC syntax as in Table 9-7 and 9-8.
\param "stream" "Pointer to the AVCDecBitstream."
\param "code"   "Pointer to the output."
\param "TotalCoeff" "Context parameter."
\return "AVCDEC_SUCCESS for success."
*/
AVCDec_Status ce_TotalZeros(AVCDecBitstream *stream, int *code, int TotalCoeff);

/**
This function decodes total_zeros VLC syntax for chroma DC as in Table 9-9.
\param "stream" "Pointer to the AVCDecBitstream."
\param "code"   "Pointer to the output."
\param "TotalCoeff" "Context parameter."
\return "AVCDEC_SUCCESS for success."
*/
AVCDec_Status ce_TotalZerosChromaDC(AVCDecBitstream *stream, int *code, int TotalCoeff);

/**
This function decodes run_before VLC syntax as in Table 9-10.
\param "stream" "Pointer to the AVCDecBitstream."
\param "code"   "Pointer to the output."
\param "zeroLeft"   "Context parameter."
\return "AVCDEC_SUCCESS for success."
*/
AVCDec_Status ce_RunBefore(AVCDecBitstream *stream, int *code, int zeroLeft);

/*----------- header.c -------------------*/
/**
This function parses vui_parameters.
\param "decvid" "Pointer to AVCDecObject."
\param "stream" "Pointer to AVCDecBitstream."
\return "AVCDEC_SUCCESS or AVCDEC_FAIL."
*/
AVCDec_Status vui_parameters(AVCDecObject *decvid, AVCDecBitstream *stream, AVCSeqParamSet *currSPS);
AVCDec_Status sei_payload(AVCDecObject *decvid, AVCDecBitstream *stream, uint payloadType, uint payloadSize);

AVCDec_Status buffering_period(AVCDecObject *decvid, AVCDecBitstream *stream);
AVCDec_Status pic_timing(AVCDecObject *decvid, AVCDecBitstream *stream);
AVCDec_Status recovery_point(AVCDecObject *decvid, AVCDecBitstream *stream);
AVCDec_Status dec_ref_pic_marking_repetition(AVCDecObject *decvid, AVCDecBitstream *stream);
AVCDec_Status motion_constrained_slice_group_set(AVCDecObject *decvid, AVCDecBitstream *stream);


/**
This function parses hrd_parameters.
\param "decvid" "Pointer to AVCDecObject."
\param "stream" "Pointer to AVCDecBitstream."
\return "AVCDEC_SUCCESS or AVCDEC_FAIL."
*/
AVCDec_Status hrd_parameters(AVCDecObject *decvid, AVCDecBitstream *stream, AVCHRDParams *HRDParam);

/**
This function decodes the syntax in sequence parameter set slice and fill up the AVCSeqParamSet
structure.
\param "decvid" "Pointer to AVCDecObject."
\param "video" "Pointer to AVCCommonObj."
\param "stream" "Pointer to AVCDecBitstream."
\return "AVCDEC_SUCCESS or AVCDEC_FAIL."
*/
AVCDec_Status DecodeSPS(AVCDecObject *decvid, AVCDecBitstream *stream);

/**
This function decodes the syntax in picture parameter set and fill up the AVCPicParamSet
structure.
\param "decvid" "Pointer to AVCDecObject."
\param "video" "Pointer to AVCCommonObj."
\param "stream" "Pointer to AVCDecBitstream."
\return "AVCDEC_SUCCESS or AVCDEC_FAIL."
*/
AVCDec_Status DecodePPS(AVCDecObject *decvid, AVCCommonObj *video, AVCDecBitstream *stream);
AVCDec_Status DecodeSEI(AVCDecObject *decvid, AVCDecBitstream *stream);

/**
This function decodes slice header, calls related functions such as
reference picture list reordering, prediction weight table, decode ref marking.
See FirstPartOfSliceHeader() and RestOfSliceHeader() in JM.
\param "decvid" "Pointer to AVCDecObject."
\param "video" "Pointer to AVCCommonObj."
\param "stream" "Pointer to AVCDecBitstream."
\return "AVCDEC_SUCCESS for success and AVCDEC_FAIL otherwise."
*/
AVCDec_Status DecodeSliceHeader(AVCDecObject *decvid, AVCCommonObj *video, AVCDecBitstream *stream);

/**
This function performes necessary operations to create dummy frames when
there is a gap in frame_num.
\param "video"  "Pointer to AVCCommonObj."
\return "AVCDEC_SUCCESS for success and AVCDEC_FAIL otherwise."
*/
AVCDec_Status fill_frame_num_gap(AVCHandle *avcHandle, AVCCommonObj *video);

/**
This function decodes ref_pic_list_reordering related syntax and fill up the AVCSliceHeader
structure.
\param "video" "Pointer to AVCCommonObj."
\param "stream" "Pointer to AVCDecBitstream."
\param "sliceHdr" "Pointer to AVCSliceHdr."
\param "slice_type" "Value of slice_type - 5 if greater than 5."
\return "AVCDEC_SUCCESS for success and AVCDEC_FAIL otherwise."
*/
AVCDec_Status ref_pic_list_reordering(AVCCommonObj *video, AVCDecBitstream *stream, AVCSliceHeader *sliceHdr, int slice_type);

/**
This function decodes dec_ref_pic_marking related syntax  and fill up the AVCSliceHeader
structure.
\param "video" "Pointer to AVCCommonObj."
\param "stream" "Pointer to AVCDecBitstream."
\param "sliceHdr" "Pointer to AVCSliceHdr."
\return "AVCDEC_SUCCESS for success and AVCDEC_FAIL otherwise."
*/
AVCDec_Status dec_ref_pic_marking(AVCCommonObj *video, AVCDecBitstream *stream, AVCSliceHeader *sliceHdr);

/**
This function performs POC related operation prior to decoding a picture
\param "video" "Pointer to AVCCommonObj."
\return "AVCDEC_SUCCESS for success and AVCDEC_FAIL otherwise."
See also PostPOC() for initialization of some variables.
*/
AVCDec_Status DecodePOC(AVCCommonObj *video);



/*------------ residual.c ------------------*/
/**
This function decodes the intra pcm data and fill it in the corresponding location
on the current picture.
\param "video"  "Pointer to AVCCommonObj."
\param "stream" "Pointer to AVCDecBitstream."
*/
AVCDec_Status DecodeIntraPCM(AVCCommonObj *video, AVCDecBitstream *stream);

/**
This function performs residual syntax decoding as well as quantization and transformation of
the decoded coefficients. See subclause 7.3.5.3.
\param "video"  "Pointer to AVCDecObject."
\param "currMB" "Pointer to current macroblock."
*/
AVCDec_Status residual(AVCDecObject *video, AVCMacroblock *currMB);

/**
This function performs CAVLC syntax decoding to get the run and level information of the coefficients.
\param "video"  "Pointer to AVCDecObject."
\param "type"   "One of AVCResidualType for a particular 4x4 block."
\param "bx"     "Horizontal block index."
\param "by"     "Vertical block index."
\param "level"  "Pointer to array of level for output."
\param "run"    "Pointer to array of run for output."
\param "numcoeff"   "Pointer to the total number of nonzero coefficients."
\return "AVCDEC_SUCCESS for success."
*/
AVCDec_Status residual_block_cavlc(AVCDecObject *video, int nC, int maxNumCoeff,
                                   int *level, int *run, int *numcoeff);

#endif /* _AVCDEC_LIB_H_ */
