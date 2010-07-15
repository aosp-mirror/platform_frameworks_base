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
#ifndef _MP4ENC_LIB_H_
#define _MP4ENC_LIB_H_

#include "mp4def.h"     // typedef
#include "mp4lib_int.h" // main video structure

#ifdef __cplusplus
extern "C"
{
#endif

    /* defined in vop.c */
    PV_STATUS EncodeVop(VideoEncData *video);
    PV_STATUS EncodeSlice(VideoEncData *video);
    PV_STATUS EncodeVideoPacketHeader(VideoEncData *video, int MB_number,
                                      int quant_scale, Int insert);
#ifdef ALLOW_VOP_NOT_CODED
    PV_STATUS EncodeVopNotCoded(VideoEncData *video, UChar *bstream, Int *size, ULong modTime);
#endif

    /* defined in combined_decode.c */
    PV_STATUS EncodeFrameCombinedMode(VideoEncData *video);
    PV_STATUS EncodeSliceCombinedMode(VideoEncData *video);

    /* defined in datapart_decode.c */
    PV_STATUS EncodeFrameDataPartMode(VideoEncData *video);
    PV_STATUS EncodeSliceDataPartMode(VideoEncData *video);

    /* defined in fastcodeMB.c */

//void m4v_memset(void *adr_dst, uint8 value, uint32 size);

    PV_STATUS CodeMB_H263(VideoEncData *video, approxDCT *function, Int offsetQP, Int ncoefblck[]);
#ifndef NO_MPEG_QUANT
    PV_STATUS CodeMB_MPEG(VideoEncData *video, approxDCT *function, Int offsetQP, Int ncoefblck[]);
#endif
    Int getBlockSAV(Short block[]);
    Int Sad8x8(UChar *rec, UChar *prev, Int lx);
    Int getBlockSum(UChar *rec, Int lx);

    /* defined in dct.c */
    void  blockIdct(Short *block);
    void blockIdct_SSE(Short *input);
    void BlockDCTEnc(Short *blockData, Short *blockCoeff);

    /*---- FastQuant.c -----*/
    Int cal_dc_scalerENC(Int QP, Int type) ;
    Int BlockQuantDequantH263Inter(Short *rcoeff, Short *qcoeff, struct QPstruct *QuantParam,
                                   UChar bitmapcol[ ], UChar *bitmaprow, UInt *bitmapzz,
                                   Int dctMode, Int comp, Int dummy, UChar shortHeader);

    Int BlockQuantDequantH263Intra(Short *rcoeff, Short *qcoeff, struct QPstruct *QuantParam,
                                   UChar bitmapcol[ ], UChar *bitmaprow, UInt *bitmapzz,
                                   Int dctMode, Int comp, Int dc_scaler, UChar shortHeader);

    Int BlockQuantDequantH263DCInter(Short *rcoeff, Short *qcoeff, struct QPstruct *QuantParam,
                                     UChar *bitmaprow, UInt *bitmapzz, Int dummy, UChar shortHeader);

    Int BlockQuantDequantH263DCIntra(Short *rcoeff, Short *qcoeff, struct QPstruct *QuantParam,
                                     UChar *bitmaprow, UInt *bitmapzz, Int dc_scaler, UChar shortHeader);

#ifndef NO_MPEG_QUANT
    Int BlockQuantDequantMPEGInter(Short *rcoeff, Short *qcoeff, Int QP, Int *qmat,
                                   UChar bitmapcol[ ], UChar *bitmaprow, UInt *bitmapzz,
                                   Int DctMode, Int comp, Int dc_scaler);

    Int BlockQuantDequantMPEGIntra(Short *rcoeff, Short *qcoeff, Int QP, Int *qmat,
                                   UChar bitmapcol[ ], UChar *bitmaprow, UInt *bitmapzz,
                                   Int DctMode, Int comp, Int dc_scaler);

    Int BlockQuantDequantMPEGDCInter(Short *rcoeff, Short *qcoeff, Int QP, Int *qmat,
                                     UChar bitmapcol[ ], UChar *bitmaprow, UInt *bitmapzz, Int dummy);

    Int BlockQuantDequantMPEGDCIntra(Short *rcoeff, Short *qcoeff, Int QP, Int *qmat,
                                     UChar bitmapcol[ ], UChar *bitmaprow, UInt *bitmapzz, Int dc_scaler);
#endif

    /*---- FastIDCT.c -----*/
    void BlockIDCTMotionComp(Short *block, UChar *bitmapcol, UChar bitmaprow,
                             Int dctMode, UChar *rec, UChar *prev, Int lx_intra_zeroMV);


    /* defined in motion_comp.c */
    void getMotionCompensatedMB(VideoEncData *video, Int ind_x, Int ind_y, Int offset);
    void EncPrediction_INTER(Int xpred, Int ypred, UChar *c_prev, UChar *c_rec,
                             Int width, Int round1);

    void EncPrediction_INTER4V(Int xpred, Int ypred, MOT *mot, UChar *c_prev, UChar *c_rec,
                               Int width, Int round1);

    void EncPrediction_Chrom(Int xpred, Int ypred, UChar *cu_prev, UChar *cv_prev, UChar *cu_rec,
                             UChar *cv_rec, Int pitch_uv, Int width_uv, Int height_uv, Int round1);

    void get_MB(UChar *c_prev, UChar *c_prev_u  , UChar *c_prev_v,
                Short mb[6][64], Int width, Int width_uv);

    void PutSkippedBlock(UChar *rec, UChar *prev, Int lx);

    /* defined in motion_est.c */
    void MotionEstimation(VideoEncData *video);
#ifdef HTFM
    void InitHTFM(VideoEncData *video, HTFM_Stat *htfm_stat, double *newvar, Int *collect);
    void UpdateHTFM(VideoEncData *video, double *newvar, double *exp_lamda, HTFM_Stat *htfm_stat);
#endif

    /* defined in ME_utils.c */
    void ChooseMode_C(UChar *Mode, UChar *cur, Int lx, Int min_SAD);
    void ChooseMode_MMX(UChar *Mode, UChar *cur, Int lx, Int min_SAD);
    void GetHalfPelMBRegion_C(UChar *cand, UChar *hmem, Int lx);
    void GetHalfPelMBRegion_SSE(UChar *cand, UChar *hmem, Int lx);
    void GetHalfPelBlkRegion(UChar *cand, UChar *hmem, Int lx);
    void PaddingEdge(Vop *padVop);
    void ComputeMBSum_C(UChar *cur, Int lx, MOT *mot_mb);
    void ComputeMBSum_MMX(UChar *cur, Int lx, MOT *mot_mb);
    void ComputeMBSum_SSE(UChar *cur, Int lx, MOT *mot_mb);
    void GetHalfPelMBRegionPadding(UChar *ncand, UChar *hmem, Int lx, Int *reptl);
    void GetHalfPelBlkRegionPadding(UChar *ncand, UChar *hmem, Int lx, Int *reptl);

    /* defined in findhalfpel.c */
    void FindHalfPelMB(VideoEncData *video, UChar *cur, MOT *mot, UChar *ncand,
                       Int xpos, Int ypos, Int *xhmin, Int *yhmin, Int hp_guess);
    Int  FindHalfPelBlk(VideoEncData *video, UChar *cur, MOT *mot, Int sad16, UChar *ncand8[],
                        UChar *mode, Int xpos, Int ypos, Int *xhmin, Int *yhmin, UChar *hp_mem);


    /* defined in sad.c */
    Int SAD_MB_HalfPel_Cxhyh(UChar *ref, UChar *blk, Int dmin_lx, void *extra_info);
    Int SAD_MB_HalfPel_Cyh(UChar *ref, UChar *blk, Int dmin_lx, void *extra_info);
    Int SAD_MB_HalfPel_Cxh(UChar *ref, UChar *blk, Int dmin_lx, void *extra_info);
    Int SAD_MB_HalfPel_MMX(UChar *ref, UChar *blk, Int dmin_lx, void *extra_info);
    Int SAD_MB_HalfPel_SSE(UChar *ref, UChar *blk, Int dmin_lx, void *extra_info);
    Int SAD_Blk_HalfPel_C(UChar *ref, UChar *blk, Int dmin, Int lx, Int rx, Int xh, Int yh, void *extra_info);
    Int SAD_Blk_HalfPel_MMX(UChar *ref, UChar *blk, Int dmin, Int lx, void *extra_info);
    Int SAD_Blk_HalfPel_SSE(UChar *ref, UChar *blk, Int dmin, Int lx, void *extra_info);
    Int SAD_Macroblock_C(UChar *ref, UChar *blk, Int dmin_lx, void *extra_info);
    Int SAD_Macroblock_MMX(UChar *ref, UChar *blk, Int dmin_lx, void *extra_info);
    Int SAD_Macroblock_SSE(UChar *ref, UChar *blk, Int dmin_lx, void *extra_info);
    Int SAD_Block_C(UChar *ref, UChar *blk, Int dmin, Int lx, void *extra_info);
    Int SAD_Block_MMX(UChar *ref, UChar *blk, Int dmin, Int lx, void *extra_info);
    Int SAD_Block_SSE(UChar *ref, UChar *blk, Int dmin, Int lx, void *extra_info);

#ifdef HTFM /* Hypothesis Testing Fast Matching */
    Int SAD_MB_HP_HTFM_Collectxhyh(UChar *ref, UChar *blk, Int dmin_x, void *extra_info);
    Int SAD_MB_HP_HTFM_Collectyh(UChar *ref, UChar *blk, Int dmin_x, void *extra_info);
    Int SAD_MB_HP_HTFM_Collectxh(UChar *ref, UChar *blk, Int dmin_x, void *extra_info);
    Int SAD_MB_HP_HTFMxhyh(UChar *ref, UChar *blk, Int dmin_lx, void *extra_info);
    Int SAD_MB_HP_HTFMyh(UChar *ref, UChar *blk, Int dmin_lx, void *extra_info);
    Int SAD_MB_HP_HTFMxh(UChar *ref, UChar *blk, Int dmin_lx, void *extra_info);
    Int SAD_MB_HTFM_Collect(UChar *ref, UChar *blk, Int dmin_lx, void *extra_info);
    Int SAD_MB_HTFM(UChar *ref, UChar *blk, Int dmin_lx, void *extra_info);
#endif
    /* on-the-fly padding */
    Int SAD_Blk_PADDING(UChar *ref, UChar *cur, Int dmin, Int lx, void *extra_info);
    Int SAD_MB_PADDING(UChar *ref, UChar *cur, Int dmin, Int lx, void *extra_info);
#ifdef HTFM
    Int SAD_MB_PADDING_HTFM_Collect(UChar *ref, UChar *blk, Int dmin, Int lx, void *extra_info);
    Int SAD_MB_PADDING_HTFM(UChar *ref, UChar *blk, Int dmin, Int lx, void *extra_info);
#endif

    /* defined in rate_control.c */
    /* These are APIs to rate control exposed to core encoder module. */
    PV_STATUS RC_Initialize(void *video);
    PV_STATUS RC_VopQPSetting(VideoEncData *video, rateControl *rc[]);
    PV_STATUS RC_VopUpdateStat(VideoEncData *video, rateControl *rc);
    PV_STATUS RC_MBQPSetting(VideoEncData *video, rateControl *rc, Int start_packet_header);
    PV_STATUS RC_MBUpdateStat(VideoEncData *video, rateControl *rc, Int Bi, Int Hi);
    PV_STATUS RC_Cleanup(rateControl *rc[], Int numLayers);

    Int       RC_GetSkipNextFrame(VideoEncData *video, Int currLayer);
    Int       RC_GetRemainingVops(VideoEncData *video, Int currLayer);
    void      RC_ResetSkipNextFrame(VideoEncData *video, Int currLayer);
    PV_STATUS RC_UpdateBuffer(VideoEncData *video, Int currLayer, Int num_skip);
    PV_STATUS RC_UpdateBXRCParams(void *input);


    /* defined in vlc_encode.c */
    void MBVlcEncodeDataPar_I_VOP(VideoEncData *video, Int ncoefblck[], void *blkCodePtr);
    void MBVlcEncodeDataPar_P_VOP(VideoEncData *video, Int ncoefblck[], void *blkCodePtr);
    void MBVlcEncodeCombined_I_VOP(VideoEncData *video, Int ncoefblck[], void *blkCodePtr);
    void MBVlcEncodeCombined_P_VOP(VideoEncData *video, Int ncoefblck[], void *blkCodePtr);
    void BlockCodeCoeff_ShortHeader(RunLevelBlock *RLB, BitstreamEncVideo *bs, Int j_start, Int j_stop, UChar Mode);
    void BlockCodeCoeff_RVLC(RunLevelBlock *RLB, BitstreamEncVideo *bs, Int j_start, Int j_stop, UChar Mode);
    void BlockCodeCoeff_Normal(RunLevelBlock *RLB, BitstreamEncVideo *bs, Int j_start, Int j_stop, UChar Mode);

#ifdef __cplusplus
}
#endif

#endif /* _MP4ENC_LIB_H_ */

