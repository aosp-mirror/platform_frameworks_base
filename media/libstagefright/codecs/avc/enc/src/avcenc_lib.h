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
#ifndef AVCENC_LIB_H_INCLUDED
#define AVCENC_LIB_H_INCLUDED

#ifndef AVCLIB_COMMON_H_INCLUDED
#include "avclib_common.h"
#endif
#ifndef AVCENC_INT_H_INCLUDED
#include "avcenc_int.h"
#endif

#ifdef __cplusplus
extern "C"
{
#endif
    /*------------- block.c -------------------------*/

    /**
    This function perform residue calculation, transform, quantize, inverse quantize,
    inverse transform and residue compensation on a 4x4 block.
    \param "encvid" "Pointer to AVCEncObject."
    \param "blkidx"  "raster scan block index of the current 4x4 block."
    \param "cur"    "Pointer to the reconstructed block."
    \param "org"    "Pointer to the original block."
    \param "coef_cost"  "Pointer to the coefficient cost to be filled in and returned."
    \return "Number of non-zero coefficients."
    */
    int dct_luma(AVCEncObject *encvid, int blkidx, uint8 *cur, uint8 *org, int *coef_cost);

    /**
    This function performs IDCT on an INTER macroblock.
    \param "video"  "Pointer to AVCCommonObj."
    \param "curL"   "Pointer to the origin of the macroblock on the current frame."
    \param "currMB" "Pointer to the AVCMacroblock structure."
    \param "picPitch" "Pitch of the current frame."
    \return "void".
    */
    void MBInterIdct(AVCCommonObj *video, uint8 *curL, AVCMacroblock *currMB, int picPitch);

    /**
    This function perform residue calculation, transform, quantize, inverse quantize,
    inverse transform and residue compensation on a macroblock.
    \param "encvid" "Pointer to AVCEncObject."
    \param "curL"   "Pointer to the reconstructed MB."
    \param "orgL"    "Pointer to the original MB."
    \return "void"
    */
    void dct_luma_16x16(AVCEncObject *encvid, uint8 *curL, uint8 *orgL);

    /**
    This function perform residue calculation, transform, quantize, inverse quantize,
    inverse transform and residue compensation for chroma components of an MB.
    \param "encvid" "Pointer to AVCEncObject."
    \param "curC"   "Pointer to the reconstructed MB."
    \param "orgC"    "Pointer to the original MB."
    \param "cr"     "Flag whether it is Cr or not."
    \return "void"
    */
    void dct_chroma(AVCEncObject *encvid, uint8 *curC, uint8 *orgC, int cr);

    /*----------- init.c ------------------*/
    /**
    This function interprets the encoding parameters provided by users in encParam.
    The results are kept in AVCEncObject, AVCSeqParamSet, AVCPicParamSet and AVCSliceHeader.
    \param "encvid"     "Pointer to AVCEncObject."
    \param "encParam"   "Pointer to AVCEncParam."
    \param "extSPS"     "External SPS template to be followed. NULL if not present."
    \param "extPPS"     "External PPS template to be followed. NULL if not present."
    \return "see AVCEnc_Status."
    */
    AVCEnc_Status  SetEncodeParam(AVCHandle *avcHandle, AVCEncParams *encParam,
                                  void *extSPS, void *extPPS);

    /**
    This function verifies the encoding parameters whether they meet the set of supported
    tool by a specific profile. If the profile is not set, it will just find the closest
    profile instead of verifying it.
    \param "video"  "Pointer to AVCEncObject."
    \param "seqParam"   "Pointer to AVCSeqParamSet."
    \param "picParam"   "Pointer to AVCPicParamSet."
    \return "AVCENC_SUCCESS if success,
            AVCENC_PROFILE_NOT_SUPPORTED if the specified profile
                is not supported by this version of the library,
            AVCENC_TOOLS_NOT_SUPPORTED if any of the specified encoding tools are
            not supported by the user-selected profile."
    */
    AVCEnc_Status VerifyProfile(AVCEncObject *video, AVCSeqParamSet *seqParam, AVCPicParamSet *picParam);

    /**
    This function verifies the encoding parameters whether they meet the requirement
    for a specific level. If the level is not set, it will just find the closest
    level instead of verifying it.
    \param "video"  "Pointer to AVCEncObject."
    \param "seqParam"   "Pointer to AVCSeqParamSet."
    \param "picParam"   "Pointer to AVCPicParamSet."
    \return "AVCENC_SUCCESS if success,
            AVCENC_LEVEL_NOT_SUPPORTED if the specified level
                is not supported by this version of the library,
            AVCENC_LEVEL_FAIL if any of the encoding parameters exceed
            the range of the user-selected level."
    */
    AVCEnc_Status VerifyLevel(AVCEncObject *video, AVCSeqParamSet *seqParam, AVCPicParamSet *picParam);

    /**
    This funciton initializes the frame encoding by setting poc/frame_num related parameters. it
    also performs motion estimation.
    \param "encvid" "Pointer to the AVCEncObject."
    \return "AVCENC_SUCCESS if success, AVCENC_NO_PICTURE if there is no input picture
            in the queue to encode, AVCENC_POC_FAIL or AVCENC_CONSECUTIVE_NONREF for POC
            related errors, AVCENC_NEW_IDR if new IDR is detected."
    */
    AVCEnc_Status InitFrame(AVCEncObject *encvid);

    /**
    This function initializes slice header related variables and other variables necessary
    for decoding one slice.
    \param "encvid" "Pointer to the AVCEncObject."
    \return "AVCENC_SUCCESS if success."
    */
    AVCEnc_Status InitSlice(AVCEncObject *encvid);

    /*----------- header.c ----------------*/
    /**
    This function performs bitstream encoding of the sequence parameter set NAL.
    \param "encvid" "Pointer to the AVCEncObject."
    \param "stream" "Pointer to AVCEncBitstream."
    \return "AVCENC_SUCCESS if success or AVCENC_SPS_FAIL or others for unexpected failure which
    should not occur. The SPS parameters should all be verified before this function is called."
    */
    AVCEnc_Status EncodeSPS(AVCEncObject *encvid, AVCEncBitstream *stream);

    /**
    This function encodes the VUI parameters into the sequence parameter set bitstream.
    \param "stream" "Pointer to AVCEncBitstream."
    \param "vui"    "Pointer to AVCVUIParams."
    \return "nothing."
    */
    void EncodeVUI(AVCEncBitstream* stream, AVCVUIParams* vui);

    /**
    This function encodes HRD parameters into the sequence parameter set bitstream
    \param "stream" "Pointer to AVCEncBitstream."
    \param "hrd"    "Pointer to AVCHRDParams."
    \return "nothing."
    */
    void EncodeHRD(AVCEncBitstream* stream, AVCHRDParams* hrd);


    /**
    This function performs bitstream encoding of the picture parameter set NAL.
    \param "encvid" "Pointer to the AVCEncObject."
    \param "stream" "Pointer to AVCEncBitstream."
    \return "AVCENC_SUCCESS if success or AVCENC_PPS_FAIL or others for unexpected failure which
    should not occur. The SPS parameters should all be verified before this function is called."
    */
    AVCEnc_Status EncodePPS(AVCEncObject *encvid, AVCEncBitstream *stream);

    /**
    This function encodes slice header information which has been initialized or fabricated
    prior to entering this funciton.
    \param "encvid" "Pointer to the AVCEncObject."
    \param "stream" "Pointer to AVCEncBitstream."
    \return "AVCENC_SUCCESS if success or bitstream fail statuses."
    */
    AVCEnc_Status EncodeSliceHeader(AVCEncObject *encvid, AVCEncBitstream *stream);

    /**
    This function encodes reference picture list reordering relted syntax.
    \param "video" "Pointer to AVCCommonObj."
    \param "stream" "Pointer to AVCEncBitstream."
    \param "sliceHdr" "Pointer to AVCSliceHdr."
    \param "slice_type" "Value of slice_type - 5 if greater than 5."
    \return "AVCENC_SUCCESS for success and AVCENC_FAIL otherwise."
    */
    AVCEnc_Status ref_pic_list_reordering(AVCCommonObj *video, AVCEncBitstream *stream, AVCSliceHeader *sliceHdr, int slice_type);

    /**
    This function encodes dec_ref_pic_marking related syntax.
    \param "video" "Pointer to AVCCommonObj."
    \param "stream" "Pointer to AVCEncBitstream."
    \param "sliceHdr" "Pointer to AVCSliceHdr."
    \return "AVCENC_SUCCESS for success and AVCENC_FAIL otherwise."
    */
    AVCEnc_Status dec_ref_pic_marking(AVCCommonObj *video, AVCEncBitstream *stream, AVCSliceHeader *sliceHdr);

    /**
    This function initializes the POC related variables and the POC syntax to be encoded
    to the slice header derived from the disp_order and is_reference flag of the original
    input frame to be encoded.
    \param "video"  "Pointer to the AVCEncObject."
    \return "AVCENC_SUCCESS if success,
            AVCENC_POC_FAIL if the poc type is undefined or
            AVCENC_CONSECUTIVE_NONREF if there are consecutive non-reference frame for POC type 2."
    */
    AVCEnc_Status InitPOC(AVCEncObject *video);

    /**
    This function performs POC related operation after a picture is decoded.
    \param "video" "Pointer to AVCCommonObj."
    \return "AVCENC_SUCCESS"
    */
    AVCEnc_Status PostPOC(AVCCommonObj *video);

    /*----------- bitstream_io.c ----------------*/
    /**
    This function initializes the bitstream structure with the information given by
    the users.
    \param "bitstream"  "Pointer to the AVCEncBitstream structure."
    \param "buffer"     "Pointer to the unsigned char buffer for output."
    \param "buf_size"   "The size of the buffer in bytes."
    \param "overrunBuffer"  "Pointer to extra overrun buffer."
    \param "oBSize"     "Size of overrun buffer in bytes."
    \return "AVCENC_SUCCESS if success, AVCENC_BITSTREAM_INIT_FAIL if fail"
    */
    AVCEnc_Status BitstreamEncInit(AVCEncBitstream *bitstream, uint8 *buffer, int buf_size,
                                   uint8 *overrunBuffer, int oBSize);

    /**
    This function writes the data from the cache into the bitstream buffer. It also adds the
    emulation prevention code if necessary.
    \param "stream"     "Pointer to the AVCEncBitstream structure."
    \return "AVCENC_SUCCESS if success or AVCENC_BITSTREAM_BUFFER_FULL if fail."
    */
    AVCEnc_Status AVCBitstreamSaveWord(AVCEncBitstream *stream);

    /**
    This function writes the codeword into the cache which will eventually be written to
    the bitstream buffer.
    \param "stream"     "Pointer to the AVCEncBitstream structure."
    \param "nBits"      "Number of bits in the codeword."
    \param "code"       "The codeword."
    \return "AVCENC_SUCCESS if success or AVCENC_BITSTREAM_BUFFER_FULL if fail."
    */
    AVCEnc_Status BitstreamWriteBits(AVCEncBitstream *stream, int nBits, uint code);

    /**
    This function writes one bit of data into the cache which will eventually be written
    to the bitstream buffer.
    \param "stream"     "Pointer to the AVCEncBitstream structure."
    \param "code"       "The codeword."
    \return "AVCENC_SUCCESS if success or AVCENC_BITSTREAM_BUFFER_FULL if fail."
    */
    AVCEnc_Status BitstreamWrite1Bit(AVCEncBitstream *stream, uint code);

    /**
    This function adds trailing bits to the bitstream and reports back the final EBSP size.
    \param "stream"     "Pointer to the AVCEncBitstream structure."
    \param "nal_size"   "Output the final NAL size."
    \return "AVCENC_SUCCESS if success or AVCENC_BITSTREAM_BUFFER_FULL if fail."
    */
    AVCEnc_Status BitstreamTrailingBits(AVCEncBitstream *bitstream, uint *nal_size);

    /**
    This function checks whether the current bit position is byte-aligned or not.
    \param "stream" "Pointer to the bitstream structure."
    \return "true if byte-aligned, false otherwise."
    */
    bool byte_aligned(AVCEncBitstream *stream);


    /**
    This function checks the availability of overrun buffer and switches to use it when
    normal bufffer is not big enough.
    \param "stream" "Pointer to the bitstream structure."
    \param "numExtraBytes" "Number of extra byte needed."
    \return "AVCENC_SUCCESS or AVCENC_FAIL."
    */
    AVCEnc_Status AVCBitstreamUseOverrunBuffer(AVCEncBitstream* stream, int numExtraBytes);


    /*-------------- intra_est.c ---------------*/

    /** This function performs intra/inter decision based on ABE.
    \param "encvid" "Pointer to AVCEncObject."
    \param "min_cost"   "Best inter cost."
    \param "curL"   "Pointer to the current MB origin in reconstructed frame."
    \param "picPitch" "Pitch of the reconstructed frame."
    \return "Boolean for intra mode."
    */

//bool IntraDecisionABE(AVCEncObject *encvid, int min_cost, uint8 *curL, int picPitch);
    bool IntraDecision(int *min_cost, uint8 *cur, int pitch, bool ave);

    /**
    This function performs intra prediction mode search.
    \param "encvid" "Pointer to AVCEncObject."
    \param "mbnum"  "Current MB number."
    \param "curL"   "Pointer to the current MB origin in reconstructed frame."
    \param "picPitch" "Pitch of the reconstructed frame."
    \return "void."
    */
    void MBIntraSearch(AVCEncObject *encvid, int mbnum, uint8 *curL, int picPitch);

    /**
    This function generates all the I16 prediction modes for an MB and keep it in
    encvid->pred_i16.
    \param "encvid" "Pointer to AVCEncObject."
    \return "void"
    */
    void intrapred_luma_16x16(AVCEncObject *encvid);

    /**
    This function calculate the cost of all I16 modes and compare them to get the minimum.
    \param "encvid" "Pointer to AVCEncObject."
    \param "orgY"   "Pointer to the original luma MB."
    \param "min_cost" "Pointer to the minimal cost so-far."
    \return "void"
    */
    void find_cost_16x16(AVCEncObject *encvid, uint8 *orgY, int *min_cost);

    /**
    This function calculates the cost of each I16 mode.
    \param "org"    "Pointer to the original luma MB."
    \param "org_pitch" "Stride size of the original frame."
    \param "pred"   "Pointer to the prediction values."
    \param "min_cost" "Minimal cost so-far."
    \return "Cost"
    */

    int cost_i16(uint8 *org, int org_pitch, uint8 *pred, int min_cost);

    /**
    This function generates all the I4 prediction modes and select the best one
    for all the blocks inside a macroblock.It also calls dct_luma to generate the reconstructed
    MB, and transform coefficients to be encoded.
    \param "encvid" "Pointer to AVCEncObject."
    \param "min_cost" "Pointer to the minimal cost so-far."
    \return "void"
    */
    void mb_intra4x4_search(AVCEncObject *encvid, int *min_cost);

    /**
    This function calculates the most probable I4 mode of a given 4x4 block
    from neighboring informationaccording to AVC/H.264 standard.
    \param "video"  "Pointer to AVCCommonObj."
    \param "blkidx" "The current block index."
    \return "Most probable mode."
    */
    int FindMostProbableI4Mode(AVCCommonObj *video, int blkidx);

    /**
    This function is where a lot of actions take place in the 4x4 block level inside
    mb_intra4x4_search.
    \param "encvid" "Pointer to AVCEncObject."
    \param "blkidx" "The current 4x4 block index."
    \param "cur"    "Pointer to the reconstructed block."
    \param "org"    "Pointer to the original block."
    \return "Minimal cost, also set currMB->i4Mode"
    */
    int blk_intra4x4_search(AVCEncObject *encvid, int blkidx, uint8 *cur, uint8 *org);

    /**
    This function calculates the cost of a given I4 prediction mode.
    \param "org"    "Pointer to the original block."
    \param "org_pitch"  "Stride size of the original frame."
    \param "pred"   "Pointer to the prediction block. (encvid->pred_i4)"
    \param "cost"   "Pointer to the minimal cost (to be updated)."
    \return "void"
    */
    void cost_i4(uint8 *org, int org_pitch, uint8 *pred, uint16 *cost);

    /**
    This function performs chroma intra search. Each mode is saved in encvid->pred_ic.
    \param "encvid" "Pointer to AVCEncObject."
    \return "void"
    */
    void chroma_intra_search(AVCEncObject *encvid);

    /**
    This function calculates the cost of a chroma prediction mode.
    \param "orgCb"  "Pointer to the original Cb block."
    \param "orgCr"  "Pointer to the original Cr block."
    \param "org_pitch"  "Stride size of the original frame."
    \param "pred"   "Pointer to the prediction block (encvid->pred_ic)"
    \param "mincost"    "Minimal cost so far."
    \return "Cost."
    */

    int SATDChroma(uint8 *orgCb, uint8 *orgCr, int org_pitch, uint8 *pred, int mincost);

    /*-------------- motion_comp.c ---------------*/

    /**
    This is a main function to peform inter prediction.
    \param "encvid"     "Pointer to AVCEncObject."
    \param "video"      "Pointer to AVCCommonObj."
    \return "void".
    */
    void AVCMBMotionComp(AVCEncObject *encvid, AVCCommonObj *video);


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
    void eLumaMotionComp(uint8 *ref, int picwidth, int picheight,
                         int x_pos, int y_pos,
                         uint8 *pred, int pred_pitch,
                         int blkwidth, int blkheight);

    void eFullPelMC(uint8 *in, int inwidth, uint8 *out, int outpitch,
                    int blkwidth, int blkheight);

    void eHorzInterp1MC(uint8 *in, int inpitch, uint8 *out, int outpitch,
                        int blkwidth, int blkheight, int dx);

    void eHorzInterp2MC(int *in, int inpitch, uint8 *out, int outpitch,
                        int blkwidth, int blkheight, int dx);

    void eHorzInterp3MC(uint8 *in, int inpitch, int *out, int outpitch,
                        int blkwidth, int blkheight);

    void eVertInterp1MC(uint8 *in, int inpitch, uint8 *out, int outpitch,
                        int blkwidth, int blkheight, int dy);

    void eVertInterp2MC(uint8 *in, int inpitch, int *out, int outpitch,
                        int blkwidth, int blkheight);

    void eVertInterp3MC(int *in, int inpitch, uint8 *out, int outpitch,
                        int blkwidth, int blkheight, int dy);

    void eDiagonalInterpMC(uint8 *in1, uint8 *in2, int inpitch,
                           uint8 *out, int outpitch,
                           int blkwidth, int blkheight);

    void eChromaMotionComp(uint8 *ref, int picwidth, int picheight,
                           int x_pos, int y_pos, uint8 *pred, int pred_pitch,
                           int blkwidth, int blkheight);

    void eChromaDiagonalMC_SIMD(uint8 *pRef, int srcPitch, int dx, int dy,
                                uint8 *pOut, int predPitch, int blkwidth, int blkheight);

    void eChromaHorizontalMC_SIMD(uint8 *pRef, int srcPitch, int dx, int dy,
                                  uint8 *pOut, int predPitch, int blkwidth, int blkheight);

    void eChromaVerticalMC_SIMD(uint8 *pRef, int srcPitch, int dx, int dy,
                                uint8 *pOut, int predPitch, int blkwidth, int blkheight);

    void eChromaFullMC_SIMD(uint8 *pRef, int srcPitch, int dx, int dy,
                            uint8 *pOut, int predPitch, int blkwidth, int blkheight);

    void eChromaVerticalMC2_SIMD(uint8 *pRef, int srcPitch, int dx, int dy,
                                 uint8 *pOut, int predPitch, int blkwidth, int blkheight);

    void eChromaHorizontalMC2_SIMD(uint8 *pRef, int srcPitch, int dx, int dy,
                                   uint8 *pOut, int predPitch, int blkwidth, int blkheight);

    void eChromaDiagonalMC2_SIMD(uint8 *pRef, int srcPitch, int dx, int dy,
                                 uint8 *pOut, int predPitch, int blkwidth, int blkheight);


    /*-------------- motion_est.c ---------------*/

    /**
    Allocate and initialize arrays necessary for motion search algorithm.
    \param "envid" "Pointer to AVCEncObject."
    \return "AVC_SUCCESS or AVC_MEMORY_FAIL."
    */
    AVCEnc_Status InitMotionSearchModule(AVCHandle *avcHandle);

    /**
    Clean up memory allocated in InitMotionSearchModule.
    \param "envid" "Pointer to AVCEncObject."
    \return "void."
    */
    void CleanMotionSearchModule(AVCHandle *avcHandle);


    /**
    This function performs motion estimation of all macroblocks in a frame during the InitFrame.
    The goal is to find the best MB partition for inter and find out if intra search is needed for
    any MBs. This intra MB tendency can be used for scene change detection.
    \param "encvid" "Pointer to AVCEncObject."
    \return "void"
    */
    void AVCMotionEstimation(AVCEncObject *encvid);

    /**
    This function performs repetitive edge padding to the reference picture by adding 16 pixels
    around the luma and 8 pixels around the chromas.
    \param "refPic" "Pointer to the reference picture."
    \return "void"
    */
    void  AVCPaddingEdge(AVCPictureData *refPic);

    /**
    This function keeps track of intra refresh macroblock locations.
    \param "encvid" "Pointer to the global array structure AVCEncObject."
    \param "mblock" "Pointer to the array of AVCMacroblock structures."
    \param "totalMB" "Total number of MBs in a frame."
    \param "numRefresh" "Number of MB to be intra refresh in a single frame."
    \return "void"
    */
    void AVCRasterIntraUpdate(AVCEncObject *encvid, AVCMacroblock *mblock, int totalMB, int numRefresh);

#ifdef HTFM
    void InitHTFM(VideoEncData *encvid, HTFM_Stat *htfm_stat, double *newvar, int *collect);
    void UpdateHTFM(AVCEncObject *encvid, double *newvar, double *exp_lamda, HTFM_Stat *htfm_stat);
    void CalcThreshold(double pf, double exp_lamda[], int nrmlz_th[]);
    void    HTFMPrepareCurMB_AVC(AVCEncObject *encvid, HTFM_Stat *htfm_stat, uint8 *cur, int pitch);
#endif

    /**
    This function reads the input MB into a smaller faster memory space to minimize the cache miss.
    \param "encvid" "Pointer to the global AVCEncObject."
    \param "cur"    "Pointer to the original input macroblock."
    \param "pitch"  "Stride size of the input frame (luma)."
    \return "void"
    */
    void    AVCPrepareCurMB(AVCEncObject *encvid, uint8 *cur, int pitch);

    /**
    Performs motion vector search for a macroblock.
    \param "encvid" "Pointer to AVCEncObject structure."
    \param "cur"    "Pointer to the current macroblock in the input frame."
    \param "best_cand" "Array of best candidates (to be filled in and returned)."
    \param "i0"     "X-coordinate of the macroblock."
    \param "j0"     "Y-coordinate of the macroblock."
    \param "type_pred" "Indicates the type of operations."
    \param "FS_en"      "Flag for fullsearch enable."
    \param "hp_guess"   "Guess for half-pel search."
    \return "void"
    */
    void AVCMBMotionSearch(AVCEncObject *encvid, uint8 *cur, uint8 *best_cand[],
                           int i0, int j0, int type_pred, int FS_en, int *hp_guess);

//AVCEnc_Status AVCMBMotionSearch(AVCEncObject *encvid, AVCMacroblock *currMB, int mbNum,
//                           int num_pass);

    /**
    Perform full-pel exhaustive search around the predicted MV.
    \param "encvid" "Pointer to AVCEncObject structure."
    \param "prev"   "Pointer to the reference frame."
    \param "cur"    "Pointer to the input macroblock."
    \param "imin"   "Pointer to minimal mv (x)."
    \param "jmin"   "Pointer to minimal mv (y)."
    \param "ilow, ihigh, jlow, jhigh"   "Lower bound on search range."
    \param "cmvx, cmvy" "Predicted MV value."

    \return "The cost function of the best candidate."
    */
    int AVCFullSearch(AVCEncObject *encvid, uint8 *prev, uint8 *cur,
                      int *imin, int *jmin, int ilow, int ihigh, int jlow, int jhigh,
                      int cmvx, int cmvy);

    /**
    Select candidates from neighboring blocks according to the type of the
    prediction selection.
    \param "mvx"    "Pointer to the candidate, x-coordinate."
    \param "mvy"    "Pointer to the candidate, y-coordinate."
    \param "num_can"    "Pointer to the number of candidates returned."
    \param "imb"    "The MB index x-coordinate."
    \param "jmb"    "The MB index y-coordinate."
    \param "type_pred"  "Type of the prediction."
    \param "cmvx, cmvy" "Pointer to predicted MV (modified version)."
    \return "void."
    */
    void AVCCandidateSelection(int *mvx, int *mvy, int *num_can, int imb, int jmb,
                               AVCEncObject *encvid, int type_pred, int *cmvx, int *cmvy);

    /**
    Utility function to move the values in the array dn according to the new
    location to avoid redundant calculation.
    \param "dn" "Array of integer of size 9."
    \param "new_loc"    "New location index."
    \return "void."
    */
    void AVCMoveNeighborSAD(int dn[], int new_loc);

    /**
    Find minimum index of dn.
    \param "dn" "Array of integer of size 9."
    \return "The index of dn with the smallest dn[] value."
    */
    int AVCFindMin(int dn[]);


    /*------------- findhalfpel.c -------------------*/

    /**
    Search for the best half-pel resolution MV around the full-pel MV.
    \param "encvid" "Pointer to the global AVCEncObject structure."
    \param "cur"    "Pointer to the current macroblock."
    \param "mot"    "Pointer to the AVCMV array of the frame."
    \param "ncand"  "Pointer to the origin of the fullsearch result."
    \param "xpos"   "The current MB position in x."
    \param "ypos"   "The current MB position in y."
    \param "hp_guess"   "Input to help speedup the search."
    \param "cmvx, cmvy" "Predicted motion vector use for mvcost."
    \return "Minimal cost (SATD) without MV cost. (for rate control purpose)"
    */
    int AVCFindHalfPelMB(AVCEncObject *encvid, uint8 *cur, AVCMV *mot, uint8 *ncand,
                         int xpos, int ypos, int hp_guess, int cmvx, int cmvy);

    /**
    This function generates sub-pel pixels required to do subpel MV search.
    \param "subpel_pred" "Pointer to 2-D array, each array for each position."
    \param "ncand" "Pointer to the full-pel center position in ref frame."
    \param "lx" "Pitch of the ref frame."
    \return "void"
     */
    void GenerateHalfPelPred(uint8 *subpel_pred, uint8 *ncand, int lx);

    /**
    This function calculate vertical interpolation at half-point of size 4x17.
    \param "dst" "Pointer to destination."
    \param "ref" "Pointer to the starting reference pixel."
    \return "void."
    */
    void VertInterpWClip(uint8 *dst, uint8 *ref);

    /**
    This function generates quarter-pel pixels around the best half-pel result
    during the sub-pel MV search.
    \param "bilin_base"  "Array of pointers to be used as basis for q-pel interp."
    \param "qpel_pred"  "Array of pointers pointing to quarter-pel candidates."
    \param "hpel_pos" "Best half-pel position at the center."
    \return "void"
    */
    void GenerateQuartPelPred(uint8 **bilin_base, uint8 *qpel_pred, int hpel_pos);

    /**
    This function calculates the SATD of a subpel candidate.
    \param "cand"   "Pointer to a candidate."
    \param "cur"    "Pointer to the current block."
    \param "dmin"   "Min-so-far SATD."
    \return "Sum of Absolute Transformed Difference."
    */
    int SATD_MB(uint8 *cand, uint8 *cur, int dmin);

    /*------------- rate_control.c -------------------*/

    /** This function is a utility function. It returns average QP of the previously encoded frame.
    \param "rateCtrl" "Pointer to AVCRateControl structure."
    \return "Average QP."
    */
    int GetAvgFrameQP(AVCRateControl *rateCtrl);

    /**
    This function takes the timestamp of the input and determine whether it should be encoded
    or skipped.
    \param "encvid" "Pointer to the AVCEncObject structure."
    \param "rateCtrl"   "Pointer to the AVCRateControl structure."
    \param "modTime"    "The 32 bit timestamp of the input frame."
    \param "frameNum"   "Pointer to the frame number if to be encoded."
    \return "AVC_SUCCESS or else."
    */
    AVCEnc_Status RCDetermineFrameNum(AVCEncObject *encvid, AVCRateControl *rateCtrl, uint32 modTime, uint *frameNum);

    /**
    This function updates the buffer fullness when frames are dropped either by the
    rate control algorithm or by the users to make sure that target bit rate is still met.
    \param "video" "Pointer to the common object structure."
    \param "rateCtrl" "Pointer to rate control structure."
    \param "frameInc" "Difference of the current frame number and previous frame number."
    \return "void."
    */
    void RCUpdateBuffer(AVCCommonObj *video, AVCRateControl *rateCtrl, int frameInc);

    /**
    This function initializes rate control module and allocates necessary bufferes to do the job.
    \param "avcHandle" "Pointer to the encoder handle."
    \return "AVCENC_SUCCESS or AVCENC_MEMORY_FAIL."
    */
    AVCEnc_Status InitRateControlModule(AVCHandle *avcHandle);

    /**
    This function frees buffers allocated in InitRateControlModule.
    \param "avcHandle" "Pointer to the encoder handle."
    \return "void."
    */
    void CleanupRateControlModule(AVCHandle *avcHandle);

    /**
    This function is called at the beginning of each GOP or the first IDR frame. It calculates
    target bits for a GOP.
    \param "encvid" "Pointer to the encoder object."
    \return "void."
    */
    void RCInitGOP(AVCEncObject *encvid);

    /**
    This function calculates target bits for a particular frame.
    \param "video"  "Pointer to the AVCEncObject structure."
    \return "void"
    */
    void RCInitFrameQP(AVCEncObject *video);

    /**
    This function calculates QP for the upcoming frame or basic unit.
    \param "encvid" "Pointer to the encoder object."
    \param "rateCtrl" "Pointer to the rate control object."
    \return "QP value ranging from 0-51."
    */
    int  RCCalculateQP(AVCEncObject *encvid, AVCRateControl *rateCtrl);

    /**
    This function translates the luma QP to chroma QP and calculates lambda based on QP.
    \param "video"  "Pointer to the AVCEncObject structure."
    \return "void"
    */
    void RCInitChromaQP(AVCEncObject *encvid);

    /**
    This function is called before encoding each macroblock.
    \param "encvid" "Pointer to the encoder object."
    \return "void."
    */
    void RCInitMBQP(AVCEncObject *encvid);

    /**
    This function updates bits usage stats after encoding an macroblock.
    \param "video" "Pointer to AVCCommonObj."
    \param "rateCtrl" "Pointer to AVCRateControl."
    \param "num_header_bits" "Number of bits used for MB header."
    \param "num_texture_bits" "Number of bits used for MB texture."
    \return "void"
    */
    void RCPostMB(AVCCommonObj *video, AVCRateControl *rateCtrl, int num_header_bits, int num_texture_bits);

    /**
    This function calculates the difference between prediction and original MB.
    \param "encvid" "Pointer to the encoder object."
    \param "currMB" "Pointer to the current macroblock structure."
    \param "orgL" "Pointer to the original MB."
    \param "orgPitch" "Pointer to the original picture pitch."
    \return "void."
    */
    void RCCalculateMAD(AVCEncObject *encvid, AVCMacroblock *currMB, uint8 *orgL, int orgPitch);

    /**
    Restore QP related parameters of previous MB when current MB is skipped.
    \param "currMB" "Pointer to the current macroblock."
    \param "video"  "Pointer to the common video structure."
    \param "encvid" "Pointer to the global encoding structure."
    \return "void"
    */
    void RCRestoreQP(AVCMacroblock *currMB, AVCCommonObj *video, AVCEncObject *encvid);

    /**
    This function is called after done with a frame.
    \param "encvid" "Pointer to the encoder object."
    \return "AVCENC_SUCCESS or AVCENC_SKIPPED_PICTURE when bufer overflow (need to discard current frame)."
    */
    AVCEnc_Status RCUpdateFrame(AVCEncObject *encvid);

    /*--------- residual.c -------------------*/

    /**
    This function encodes the intra pcm data and fill it in the corresponding location
    on the current picture.
    \param "video"  "Pointer to AVCEncObject."
    \return "AVCENC_SUCCESS if success, or else for bitstream errors."
    */
    AVCEnc_Status EncodeIntraPCM(AVCEncObject *video);

    /**
    This function performs CAVLC syntax encoding on the run and level information of the coefficients.
    The level and run arrays are elements in AVCEncObject structure, populated by TransQuantZZ,
    TransQuantIntraDC and TransQuantChromaDC functions.
    \param "video"  "Pointer to AVCEncObject."
    \param "type"   "One of AVCResidualType for a particular 4x4 block."
    \param "bindx"  "Block index or number of nonzero coefficients for AVC_Intra16DC and AVC_ChromaDC mode."
    \param "currMB" "Pointer to the current macroblock structure."
    \return "AVCENC_SUCCESS for success."
    \Note   "This function has 32-bit machine specific instruction!!!!"
    */
    AVCEnc_Status enc_residual_block(AVCEncObject *encvid, AVCResidualType type, int bindx, AVCMacroblock *currMB);


    /*------------- sad.c ---------------------------*/


    int AVCSAD_MB_HalfPel_Cxhyh(uint8 *ref, uint8 *blk, int dmin_lx, void *extra_info);
    int AVCSAD_MB_HalfPel_Cyh(uint8 *ref, uint8 *blk, int dmin_lx, void *extra_info);
    int AVCSAD_MB_HalfPel_Cxh(uint8 *ref, uint8 *blk, int dmin_lx, void *extra_info);
    int AVCSAD_Macroblock_C(uint8 *ref, uint8 *blk, int dmin_lx, void *extra_info);

#ifdef HTFM /*  3/2/1, Hypothesis Testing Fast Matching */
    int AVCSAD_MB_HP_HTFM_Collectxhyh(uint8 *ref, uint8 *blk, int dmin_x, void *extra_info);
    int AVCSAD_MB_HP_HTFM_Collectyh(uint8 *ref, uint8 *blk, int dmin_x, void *extra_info);
    int AVCSAD_MB_HP_HTFM_Collectxh(uint8 *ref, uint8 *blk, int dmin_x, void *extra_info);
    int AVCSAD_MB_HP_HTFMxhyh(uint8 *ref, uint8 *blk, int dmin_lx, void *extra_info);
    int AVCSAD_MB_HP_HTFMyh(uint8 *ref, uint8 *blk, int dmin_lx, void *extra_info);
    int AVCSAD_MB_HP_HTFMxh(uint8 *ref, uint8 *blk, int dmin_lx, void *extra_info);
    int AVCSAD_MB_HTFM_Collect(uint8 *ref, uint8 *blk, int dmin_lx, void *extra_info);
    int AVCSAD_MB_HTFM(uint8 *ref, uint8 *blk, int dmin_lx, void *extra_info);
#endif


    /*------------- slice.c -------------------------*/

    /**
    This function performs the main encoding loop for a slice.
    \param "encvid" "Pointer to AVCEncObject."
    \return "AVCENC_SUCCESS for success, AVCENC_PICTURE_READY for end-of-picture and
             AVCENC_FAIL or AVCENC_SLICE_EMPTY otherwise."
    */
    AVCEnc_Status AVCEncodeSlice(AVCEncObject *encvid);

    /**
    This function performs the main encoding operation for one macroblock.
    \param "video" "pointer to AVCEncObject."
    \return "AVCENC_SUCCESS for success, or other bitstream related failure status."
    */
    AVCEnc_Status EncodeMB(AVCEncObject *video);

    /**
    This function calls prediction INTRA/INTER functions, transform,
    quantization and zigzag scanning to get the run-level symbols.
    \param "encvid" "pointer to AVCEncObject."
    \param "curL"   "pointer to Luma component of the current frame.
    \param "curCb"  "pointer to Cb component of the current frame.
    \param "curCr"  "pointer to Cr component of the current frame.
    \return "void for now."
     */
    void MBPredTransQuantZZ(AVCEncObject *encvid, uint8 *curL, uint8 *curCb, uint8 *curCr);

    /**
    This function copies the content of the prediction MB into the reconstructed YUV
    frame directly.
    \param "curL"   "Pointer to the destination Y component."
    \param "curCb"  "Pointer to the destination Cb component."
    \param "curCr"  "Pointer to the destination Cr component."
    \param "predBlock"  "Pointer to the prediction MB."
    \param "picWidth"   "The width of the frame."
    \return "None."
    */
    void Copy_MB(uint8 *curL, uint8 *curCb, uint8 *curCr, uint8 *predBlock, int picWidth);

    /**
    This function encodes the mb_type, CBP, prediction mode, ref idx and MV.
    \param "currMB" "Pointer to the current macroblock structure."
    \param "video" "Pointer to the AVCEncObject structure."
    \return "AVCENC_SUCCESS for success or else for fail."
    */
    AVCEnc_Status EncodeMBHeader(AVCMacroblock *currMB, AVCEncObject *video);

    /**
    This function finds the right mb_type for a macroblock given the mbMode, CBP,
    NumPart, PredPartMode.
    \param "currMB" "Pointer to the current macroblock structure."
    \param "slice_type" "Value of the slice_type."
    \return "mb_type."
    */
    uint InterpretMBType(AVCMacroblock *currMB, int slice_type);

    /**
    This function encodes the mb_pred part of the macroblock data.
    \param "video"  "Pointer to the AVCCommonObj structure."
    \param "currMB" "Pointer to the current macroblock structure."
    \param "stream" "Pointer to the AVCEncBitstream structure."
    \return "AVCENC_SUCCESS for success or bitstream fail status."
    */
    AVCEnc_Status mb_pred(AVCCommonObj *video, AVCMacroblock *currMB, AVCEncBitstream *stream);

    /**
    This function encodes the sub_mb_pred part of the macroblock data.
    \param "video"  "Pointer to the AVCCommonObj structure."
    \param "currMB" "Pointer to the current macroblock structure."
    \param "stream" "Pointer to the AVCEncBitstream structure."
    \return "AVCENC_SUCCESS for success or bitstream fail status."
    */
    AVCEnc_Status sub_mb_pred(AVCCommonObj *video, AVCMacroblock *currMB, AVCEncBitstream *stream);

    /**
    This function interprets the sub_mb_type and sets necessary information
    when the slice type is AVC_P_SLICE.
    in the macroblock structure.
    \param "mblock" "Pointer to current AVCMacroblock."
    \param "sub_mb_type" "From the syntax bitstream."
    \return "void"
    */
    void InterpretSubMBTypeP(AVCMacroblock *mblock, uint *sub_mb_type);

    /**
    This function interprets the sub_mb_type and sets necessary information
    when the slice type is AVC_B_SLICE.
    in the macroblock structure.
    \param "mblock" "Pointer to current AVCMacroblock."
    \param "sub_mb_type" "From the syntax bitstream."
    \return "void"
    */
    void InterpretSubMBTypeB(AVCMacroblock *mblock, uint *sub_mb_type);

    /**
    This function encodes intra 4x4 mode. It calculates the predicted I4x4 mode and the
    remnant to be encoded.
    \param "video"  "Pointer to AVCEncObject structure."
    \param "currMB" "Pointer to the AVCMacroblock structure."
    \param "stream" "Pointer to AVCEncBitstream sructure."
    \return "AVCENC_SUCCESS for success."
    */
    AVCEnc_Status EncodeIntra4x4Mode(AVCCommonObj *video, AVCMacroblock *currMB, AVCEncBitstream *stream);

    /*------------- vlc_encode.c -----------------------*/
    /**
    This function encodes and writes a value into an Exp-Golomb codeword.
    \param "bitstream" "Pointer to AVCEncBitstream."
    \param "codeNum" "Pointer to the value of the codeNum."
    \return "AVCENC_SUCCESS for success or bitstream error messages for fail."
    */
    AVCEnc_Status ue_v(AVCEncBitstream *bitstream, uint codeNum);

    /**
    This function maps and encodes signed Exp-Golomb codes.
    \param "bitstream" "Pointer to AVCEncBitstream."
    \param "value"  "Pointer to syntax element value."
    \return "AVCENC_SUCCESS or AVCENC_FAIL."
    */
    AVCEnc_Status  se_v(AVCEncBitstream *bitstream, int value);

    /**
    This function maps and encodes truncated Exp-Golomb codes.
    \param "bitstream" "Pointer to AVCEncBitstream."
    \param "value"  "Pointer to syntax element value."
    \param "range"  "Range of the value as input to determine the algorithm."
    \return "AVCENC_SUCCESS or AVCENC_FAIL."
    */
    AVCEnc_Status te_v(AVCEncBitstream *bitstream, uint value, uint range);

    /**
    This function creates Exp-Golomb codeword from codeNum.
    \param "bitstream" "Pointer to AVCEncBitstream."
    \param "codeNum" "Pointer to the codeNum value."
    \return "AVCENC_SUCCESS for success or bitstream error messages for fail."
    */
    AVCEnc_Status SetEGBitstring(AVCEncBitstream *bitstream, uint codeNum);

    /**
    This function performs CAVLC encoding of the CBP (coded block pattern) of a macroblock
    by calling ue_v() and then mapping the CBP to the corresponding VLC codeNum.
    \param "currMB"  "Pointer to the current AVCMacroblock structure."
    \param "stream"  "Pointer to the AVCEncBitstream."
    \return "void"
    */
    AVCEnc_Status EncodeCBP(AVCMacroblock *currMB, AVCEncBitstream *stream);

    /**
    This function encodes trailing ones and total coefficient.
    \param "stream" "Pointer to the AVCEncBitstream."
    \param "TrailingOnes"   "The trailing one variable output."
    \param "TotalCoeff" "The total coefficient variable output."
    \param "nC" "Context for number of nonzero coefficient (prediction context)."
    \return "AVCENC_SUCCESS for success or else for bitstream failure."
    */
    AVCEnc_Status ce_TotalCoeffTrailingOnes(AVCEncBitstream *stream, int TrailingOnes, int TotalCoeff, int nC);

    /**
    This function encodes trailing ones and total coefficient for chroma DC block.
    \param "stream" "Pointer to the AVCEncBitstream."
    \param "TrailingOnes"   "The trailing one variable output."
    \param "TotalCoeff" "The total coefficient variable output."
    \return "AVCENC_SUCCESS for success or else for bitstream failure."
    */
    AVCEnc_Status ce_TotalCoeffTrailingOnesChromaDC(AVCEncBitstream *stream, int TrailingOnes, int TotalCoeff);

    /**
    This function encodes total_zeros value as in Table 9-7 and 9-8.
    \param "stream" "Pointer to the AVCEncBitstream."
    \param "TotalZeros" "The total_zeros value."
    \param "TotalCoeff" "The total coefficient variable output."
    \return "AVCENC_SUCCESS for success or else for bitstream failure."
    */
    AVCEnc_Status ce_TotalZeros(AVCEncBitstream *stream, int total_zeros, int TotalCoeff);

    /**
    This function encodes total_zeros VLC syntax for chroma DC as in Table 9-9.
    \param "stream" "Pointer to the AVCEncBitstream."
    \param "TotalZeros" "The total_zeros value."
    \param "TotalCoeff" "The total coefficient variable output."
    \return "AVCENC_SUCCESS for success or else for bitstream failure."
    */
    AVCEnc_Status ce_TotalZerosChromaDC(AVCEncBitstream *stream, int total_zeros, int TotalCoeff);

    /**
    This function encodes run_before VLC syntax as in Table 9-10.
    \param "stream" "Pointer to the AVCEncBitstream."
    \param "run_before" "The run_before value."
    \param "zerosLeft"  "The context for number of zeros left."
    \return "AVCENC_SUCCESS for success or else for bitstream failure."
    */
    AVCEnc_Status ce_RunBefore(AVCEncBitstream *stream, int run_before, int zerosLeft);

#ifdef __cplusplus
}
#endif


#endif /* _AVCENC_LIB_H_ */

