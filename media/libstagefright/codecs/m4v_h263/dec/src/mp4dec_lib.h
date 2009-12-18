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
#ifndef _MP4DECLIB_H_
#define _MP4DECLIB_H_

/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include "mp4def.h" /* typedef */
#include "mp4lib_int.h" /* main video structure */

/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here.
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; EXTERNAL VARIABLES REFERENCES
; Declare variables used in this module but defined elsewhere
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; SIMPLE TYPEDEF'S
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; ENUMERATED TYPEDEF'S
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; STRUCTURES TYPEDEF'S
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; GLOBAL FUNCTION DEFINITIONS
; Function Prototype declaration
----------------------------------------------------------------------------*/
#ifdef __cplusplus
extern "C"
{
#endif /* __cplusplus */

    /* defined in pvdec_api.c, these function are not supposed to be    */
    /* exposed to programmers outside PacketVideo.  08/15/2000.    */
    uint VideoDecoderErrorDetected(VideoDecData *video);

#ifdef ENABLE_LOG
    void m4vdec_dprintf(char *format, ...);
#define mp4dec_log(message) m4vdec_dprintf(message)
#else
#define mp4dec_log(message)
#endif

    /*--------------------------------------------------------------------------*/
    /* defined in frame_buffer.c */
    PV_STATUS FillFrameBufferNew(BitstreamDecVideo *stream);
    PV_STATUS FillFrameBuffer(BitstreamDecVideo *stream, int short_header);

    /*--------------------------------------------------------------------------*/
    /* defined in dc_ac_pred.c */
    int cal_dc_scaler(int QP, int type);
    PV_STATUS PV_DecodePredictedIntraDC(int compnum, BitstreamDecVideo *stream,
                                        int16 *IntraDC_delta);

    void    doDCACPrediction(VideoDecData *video, int comp, int16 *q_block,
                             int *direction);

#ifdef PV_ANNEX_IJKT_SUPPORT
    void    doDCACPrediction_I(VideoDecData *video, int comp, int16 *q_block);
#endif
    /*--------------------------------------------------------------------------*/
    /* defined in block_idct.c */
    void MBlockIDCTAdd(VideoDecData *video, int nz_coefs[]);

    void BlockIDCT(uint8 *dst, uint8 *pred, int16 *blk, int width, int nzcoefs,
                   uint8 *bitmapcol, uint8 bitmaprow);

    void MBlockIDCT(VideoDecData *video);
    void BlockIDCT_intra(MacroBlock *mblock, PIXEL *c_comp, int comp, int width_offset);
    /*--------------------------------------------------------------------------*/
    /* defined in combined_decode.c */
    PV_STATUS DecodeFrameCombinedMode(VideoDecData *video);
    PV_STATUS GetMBheader(VideoDecData *video, int16 *QP);
    PV_STATUS GetMBData(VideoDecData *video);

    /*--------------------------------------------------------------------------*/
    /* defined in datapart_decode.c */
    PV_STATUS DecodeFrameDataPartMode(VideoDecData *video);
    PV_STATUS GetMBheaderDataPart_DQUANT_DC(VideoDecData *video, int16 *QP);
    PV_STATUS GetMBheaderDataPart_P(VideoDecData *video);
    PV_STATUS DecodeDataPart_I_VideoPacket(VideoDecData *video, int slice_counter);
    PV_STATUS DecodeDataPart_P_VideoPacket(VideoDecData *video, int slice_counter);
    PV_STATUS GetMBData_DataPart(VideoDecData *video);

    /*--------------------------------------------------------------------------*/
    /* defined in packet_util.c */
    PV_STATUS PV_ReadVideoPacketHeader(VideoDecData *video, int *next_MB);
    PV_STATUS RecoverPacketError(BitstreamDecVideo *stream, int marker_length, int32 *nextVop);
    PV_STATUS RecoverGOBError(BitstreamDecVideo *stream, int marker_length, int32 *vopPos);
    PV_STATUS PV_GobHeader(VideoDecData *video);
#ifdef PV_ANNEX_IJKT_SUPPORT
    PV_STATUS PV_H263SliceHeader(VideoDecData *videoInt, int *next_MB);
#endif
    /*--------------------------------------------------------------------------*/
    /* defined in motion_comp.c */
    void MBMotionComp(VideoDecData *video, int CBP);
    void  SkippedMBMotionComp(VideoDecData *video);

    /*--------------------------------------------------------------------------*/
    /* defined in chrominance_pred.c */
    void chrominance_pred(
        int xpred,          /* i */
        int ypred,          /* i */
        uint8 *cu_prev,     /* i */
        uint8 *cv_prev,     /* i */
        uint8 *pred_block,  /* i */
        int width_uv,       /* i */
        int height_uv,      /* i */
        int round1
    );

    /*--------------------------------------------------------------------------*/
    /* defined in luminance_pred_mode_inter.c */
    void luminance_pred_mode_inter(
        int xpred,          /* i */
        int ypred,          /* i */
        uint8 *c_prev,      /* i */
        uint8 *pred_block,  /* i */
        int width,          /* i */
        int height,         /* i */
        int round1
    );

    /*--------------------------------------------------------------------------*/
    /* defined in luminance_pred_mode_inter4v.c */
    void luminance_pred_mode_inter4v(
        int xpos,           /* i */
        int ypos,           /* i */
        MOT *px,            /* i */
        MOT *py,            /* i */
        uint8 *c_prev,      /* i */
        uint8 *pred_block,  /* i */
        int width,          /* i */
        int height,         /* i */
        int round1,         /* i */
        int mvwidth,            /* i */
        int *xsum_ptr,          /* i/o */
        int *ysum_ptr           /* i/o */
    );

    /*--------------------------------------------------------------------------*/
    /* defined in pp_semaphore_chroma_inter.c */
#ifdef PV_POSTPROC_ON
    void pp_semaphore_chroma_inter(
        int xpred,      /* i */
        int ypred,      /* i */
        uint8   *pp_dec_u,  /* i/o */
        uint8   *pstprcTypPrv,  /* i */
        int dx,     /* i */
        int dy,     /* i */
        int mvwidth,    /* i */
        int height,     /* i */
        int32   size,       /* i */
        int mv_loc,     /* i */
        uint8   msk_deblock /* i */
    );

    /*--------------------------------------------------------------------------*/
    /* defined in pp_semaphore_luma.c */
    uint8 pp_semaphore_luma(
        int xpred,      /* i */
        int ypred,      /* i */
        uint8   *pp_dec_y,  /* i/o */
        uint8   *pstprcTypPrv,  /* i */
        int *ll,        /* i */
        int *mv_loc,    /* i/o */
        int dx,     /* i */
        int dy,     /* i */
        int mvwidth,    /* i */
        int width,      /* i */
        int height      /* i */
    );
#endif
    /*--------------------------------------------------------------------------*/
    /* defined in get_pred_adv_mb_add.c */
    int GetPredAdvancedMB(
        int xpos,
        int ypos,
        uint8 *c_prev,
        uint8 *pred_block,
        int width,
        int rnd1
    );

    /*--------------------------------------------------------------------------*/
    /* defined in get_pred_adv_b_add.c */
    int GetPredAdvancedBy0x0(
        uint8 *c_prev,      /* i */
        uint8 *pred_block,      /* i */
        int width,      /* i */
        int pred_width_rnd /* i */
    );

    int GetPredAdvancedBy0x1(
        uint8 *c_prev,      /* i */
        uint8 *pred_block,      /* i */
        int width,      /* i */
        int pred_width_rnd /* i */
    );

    int GetPredAdvancedBy1x0(
        uint8 *c_prev,      /* i */
        uint8 *pred_block,      /* i */
        int width,      /* i */
        int pred_width_rnd /* i */
    );

    int GetPredAdvancedBy1x1(
        uint8 *c_prev,      /* i */
        uint8 *pred_block,      /* i */
        int width,      /* i */
        int pred_width_rnd /* i */
    );

    /*--------------------------------------------------------------------------*/
    /* defined in get_pred_outside.c */
    int GetPredOutside(
        int xpos,
        int ypos,
        uint8 *c_prev,
        uint8 *pred_block,
        int width,
        int height,
        int rnd1,
        int pred_width
    );

    /*--------------------------------------------------------------------------*/
    /* defined in find_pmvsErrRes.c */
    void mv_prediction(VideoDecData *video, int block, MOT *mvx, MOT *mvy);

    /*--------------------------------------------------------------------------*/

    /*--------------------------------------------------------------------------*/
    /* defined in mb_utils.c */
    void Copy_MB_into_Vop(uint8 *comp, int yChan[][NCOEFF_BLOCK], int width);
    void Copy_B_into_Vop(uint8 *comp, int cChan[], int width);
    void PutSKIPPED_MB(uint8 *comp, uint8 *c_prev, int width);
    void PutSKIPPED_B(uint8 *comp, uint8 *c_prev, int width);

    /*--------------------------------------------------------------------------*/
    /* defined in vop.c */
    PV_STATUS DecodeGOVHeader(BitstreamDecVideo *stream, uint32 *time_base);
    PV_STATUS DecodeVOLHeader(VideoDecData *video, int layer);
    PV_STATUS DecodeVOPHeader(VideoDecData *video, Vop *currVop, Bool use_ext_tiemstamp);
    PV_STATUS DecodeShortHeader(VideoDecData *video, Vop *currVop);
    PV_STATUS PV_DecodeVop(VideoDecData *video);
    uint32 CalcVopDisplayTime(Vol *currVol, Vop *currVop, int shortVideoHeader);

    /*--------------------------------------------------------------------------*/
    /* defined in post_proc.c */
#ifdef PV_ANNEX_IJKT_SUPPORT
    void H263_Deblock(uint8 *rec,   int width, int height, int16 *QP_store, uint8 *mode, int chr, int T);
#endif
    int  PostProcSemaphore(int16 *q_block);
    void PostFilter(VideoDecData *video, int filer_type, uint8 *output);
    void FindMaxMin(uint8 *ptr, int *min, int *max, int incr);
    void DeringAdaptiveSmoothMMX(uint8 *img, int incr, int thres, int mxdf);
    void AdaptiveSmooth_NoMMX(uint8 *Rec_Y, int v0, int h0, int v_blk, int h_blk,
                              int thr, int width, int max_diff);
    void Deringing_Luma(uint8 *Rec_Y, int width, int height, int16 *QP_store,
                        int Combined, uint8 *pp_mod);
    void Deringing_Chroma(uint8 *Rec_C, int width, int height, int16 *QP_store,
                          int Combined, uint8 *pp_mod);
    void CombinedHorzVertFilter(uint8 *rec, int width, int height, int16 *QP_store,
                                int chr, uint8 *pp_mod);
    void CombinedHorzVertFilter_NoSoftDeblocking(uint8 *rec, int width, int height, int16 *QP_store,
            int chr, uint8 *pp_mod);
    void CombinedHorzVertRingFilter(uint8 *rec, int width, int height,
                                    int16 *QP_store, int chr, uint8 *pp_mod);

    /*--------------------------------------------------------------------------*/
    /* defined in conceal.c */
    void ConcealTexture_I(VideoDecData *video, int32 startFirstPartition, int mb_start, int mb_stop,
                          int slice_counter);
    void ConcealTexture_P(VideoDecData *video, int mb_start, int mb_stop,
                          int slice_counter);
    void ConcealPacket(VideoDecData *video, int mb_start, int mb_stop,
                       int slice_counter);
    void CopyVopMB(Vop *curr, uint8 *prev, int mbnum, int width, int height);

    /* define in vlc_dequant.c ,  09/18/2000*/
#ifdef PV_SUPPORT_MAIN_PROFILE
    int VlcDequantMpegIntraBlock(void *video, int comp, int switched,
                                 uint8 *bitmapcol, uint8 *bitmaprow);
    int VlcDequantMpegInterBlock(void *video, int comp,
                                 uint8 *bitmapcol, uint8 *bitmaprow);
#endif
    int VlcDequantH263IntraBlock(VideoDecData *video, int comp, int switched,
                                 uint8 *bitmapcol, uint8 *bitmaprow);
    int VlcDequantH263IntraBlock_SH(VideoDecData *video, int comp,
                                    uint8 *bitmapcol, uint8 *bitmaprow);
    int VlcDequantH263InterBlock(VideoDecData *video, int comp,
                                 uint8 *bitmapcol, uint8 *bitmaprow);

#ifdef __cplusplus
}
#endif /* __cplusplus */

/*----------------------------------------------------------------------------
; END
----------------------------------------------------------------------------*/
#endif

