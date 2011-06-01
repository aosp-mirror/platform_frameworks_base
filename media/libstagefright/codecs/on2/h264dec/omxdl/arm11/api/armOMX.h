/* 
 * 
 * File Name:  armOMX_ReleaseVersion.h
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 *
 * This file allows a version of the OMX DL libraries to be built where some or
 * all of the function names can be given a user specified suffix. 
 *
 * You might want to use it where:
 *
 * - you want to rename a function "out of the way" so that you could replace
 *   a function with a different version (the original version would still be
 *   in the library just with a different name - so you could debug the new
 *   version by comparing it to the output of the old)
 *
 * - you want to rename all the functions to versions with a suffix so that 
 *   you can include two versions of the library and choose between functions
 *   at runtime.
 *
 *     e.g. omxIPBM_Copy_U8_C1R could be renamed omxIPBM_Copy_U8_C1R_CortexA8
 * 
 */

  
#ifndef _armOMX_H_
#define _armOMX_H_


/* We need to define these two macros in order to expand and concatenate the names */
#define OMXCAT2BAR(A, B) omx ## A ## B
#define OMXCATBAR(A, B) OMXCAT2BAR(A, B)

/* Define the suffix to add to all functions - the default is no suffix */
#define BARE_SUFFIX 



/* Define what happens to the bare suffix-less functions, down to the sub-domain accuracy */
#define OMXACAAC_SUFFIX    BARE_SUFFIX   
#define OMXACMP3_SUFFIX    BARE_SUFFIX
#define OMXICJP_SUFFIX     BARE_SUFFIX
#define OMXIPBM_SUFFIX     BARE_SUFFIX
#define OMXIPCS_SUFFIX     BARE_SUFFIX
#define OMXIPPP_SUFFIX     BARE_SUFFIX
#define OMXSP_SUFFIX       BARE_SUFFIX
#define OMXVCCOMM_SUFFIX   BARE_SUFFIX
#define OMXVCM4P10_SUFFIX  BARE_SUFFIX
#define OMXVCM4P2_SUFFIX   BARE_SUFFIX




/* Define what the each bare, un-suffixed OpenMAX API function names is to be renamed */
#define omxACAAC_DecodeChanPairElt                        OMXCATBAR(ACAAC_DecodeChanPairElt, OMXACAAC_SUFFIX)
#define omxACAAC_DecodeDatStrElt                          OMXCATBAR(ACAAC_DecodeDatStrElt, OMXACAAC_SUFFIX)
#define omxACAAC_DecodeFillElt                            OMXCATBAR(ACAAC_DecodeFillElt, OMXACAAC_SUFFIX)
#define omxACAAC_DecodeIsStereo_S32                       OMXCATBAR(ACAAC_DecodeIsStereo_S32, OMXACAAC_SUFFIX)
#define omxACAAC_DecodeMsPNS_S32_I                        OMXCATBAR(ACAAC_DecodeMsPNS_S32_I, OMXACAAC_SUFFIX)
#define omxACAAC_DecodeMsStereo_S32_I                     OMXCATBAR(ACAAC_DecodeMsStereo_S32_I, OMXACAAC_SUFFIX)
#define omxACAAC_DecodePrgCfgElt                          OMXCATBAR(ACAAC_DecodePrgCfgElt, OMXACAAC_SUFFIX)
#define omxACAAC_DecodeTNS_S32_I                          OMXCATBAR(ACAAC_DecodeTNS_S32_I, OMXACAAC_SUFFIX)
#define omxACAAC_DeinterleaveSpectrum_S32                 OMXCATBAR(ACAAC_DeinterleaveSpectrum_S32, OMXACAAC_SUFFIX)
#define omxACAAC_EncodeTNS_S32_I                          OMXCATBAR(ACAAC_EncodeTNS_S32_I, OMXACAAC_SUFFIX)
#define omxACAAC_LongTermPredict_S32                      OMXCATBAR(ACAAC_LongTermPredict_S32, OMXACAAC_SUFFIX)
#define omxACAAC_LongTermReconstruct_S32_I                OMXCATBAR(ACAAC_LongTermReconstruct_S32_I, OMXACAAC_SUFFIX)
#define omxACAAC_MDCTFwd_S32                              OMXCATBAR(ACAAC_MDCTFwd_S32, OMXACAAC_SUFFIX)
#define omxACAAC_MDCTInv_S32_S16                          OMXCATBAR(ACAAC_MDCTInv_S32_S16, OMXACAAC_SUFFIX)
#define omxACAAC_NoiselessDecode                          OMXCATBAR(ACAAC_NoiselessDecode, OMXACAAC_SUFFIX)
#define omxACAAC_QuantInv_S32_I                           OMXCATBAR(ACAAC_QuantInv_S32_I, OMXACAAC_SUFFIX)
#define omxACAAC_UnpackADIFHeader                         OMXCATBAR(ACAAC_UnpackADIFHeader, OMXACAAC_SUFFIX)
#define omxACAAC_UnpackADTSFrameHeader                    OMXCATBAR(ACAAC_UnpackADTSFrameHeader, OMXACAAC_SUFFIX)


#define omxACMP3_HuffmanDecode_S32                        OMXCATBAR(ACMP3_HuffmanDecode_S32, OMXACMP3_SUFFIX)
#define omxACMP3_HuffmanDecodeSfb_S32                     OMXCATBAR(ACMP3_HuffmanDecodeSfb_S32, OMXACMP3_SUFFIX)
#define omxACMP3_HuffmanDecodeSfbMbp_S32                  OMXCATBAR(ACMP3_HuffmanDecodeSfbMbp_S32, OMXACMP3_SUFFIX)
#define omxACMP3_MDCTInv_S32                              OMXCATBAR(ACMP3_MDCTInv_S32, OMXACMP3_SUFFIX)
#define omxACMP3_ReQuantize_S32_I                         OMXCATBAR(ACMP3_ReQuantize_S32_I, OMXACMP3_SUFFIX)
#define omxACMP3_ReQuantizeSfb_S32_I                      OMXCATBAR(ACMP3_ReQuantizeSfb_S32_I, OMXACMP3_SUFFIX)
#define omxACMP3_SynthPQMF_S32_S16                        OMXCATBAR(ACMP3_SynthPQMF_S32_S16, OMXACMP3_SUFFIX)
#define omxACMP3_UnpackFrameHeader                        OMXCATBAR(ACMP3_UnpackFrameHeader, OMXACMP3_SUFFIX)
#define omxACMP3_UnpackScaleFactors_S8                    OMXCATBAR(ACMP3_UnpackScaleFactors_S8, OMXACMP3_SUFFIX)
#define omxACMP3_UnpackSideInfo                           OMXCATBAR(ACMP3_UnpackSideInfo, OMXACMP3_SUFFIX)

#define omxICJP_CopyExpand_U8_C3                          OMXCATBAR(ICJP_CopyExpand_U8_C3, OMXICJP_SUFFIX)
#define omxICJP_DCTFwd_S16                                OMXCATBAR(ICJP_DCTFwd_S16, OMXICJP_SUFFIX)
#define omxICJP_DCTFwd_S16_I                              OMXCATBAR(ICJP_DCTFwd_S16_I, OMXICJP_SUFFIX)
#define omxICJP_DCTInv_S16                                OMXCATBAR(ICJP_DCTInv_S16, OMXICJP_SUFFIX)
#define omxICJP_DCTInv_S16_I                              OMXCATBAR(ICJP_DCTInv_S16_I, OMXICJP_SUFFIX)
#define omxICJP_DCTQuantFwd_Multiple_S16                  OMXCATBAR(ICJP_DCTQuantFwd_Multiple_S16, OMXICJP_SUFFIX)
#define omxICJP_DCTQuantFwd_S16                           OMXCATBAR(ICJP_DCTQuantFwd_S16, OMXICJP_SUFFIX)
#define omxICJP_DCTQuantFwd_S16_I                         OMXCATBAR(ICJP_DCTQuantFwd_S16_I, OMXICJP_SUFFIX)
#define omxICJP_DCTQuantFwdTableInit                      OMXCATBAR(ICJP_DCTQuantFwdTableInit, OMXICJP_SUFFIX)
#define omxICJP_DCTQuantInv_Multiple_S16                  OMXCATBAR(ICJP_DCTQuantInv_Multiple_S16, OMXICJP_SUFFIX)
#define omxICJP_DCTQuantInv_S16                           OMXCATBAR(ICJP_DCTQuantInv_S16, OMXICJP_SUFFIX)
#define omxICJP_DCTQuantInv_S16_I                         OMXCATBAR(ICJP_DCTQuantInv_S16_I, OMXICJP_SUFFIX)
#define omxICJP_DCTQuantInvTableInit                      OMXCATBAR(ICJP_DCTQuantInvTableInit, OMXICJP_SUFFIX)
#define omxICJP_DecodeHuffman8x8_Direct_S16_C1            OMXCATBAR(ICJP_DecodeHuffman8x8_Direct_S16_C1, OMXICJP_SUFFIX)
#define omxICJP_DecodeHuffmanSpecGetBufSize_U8            OMXCATBAR(ICJP_DecodeHuffmanSpecGetBufSize_U8, OMXICJP_SUFFIX)
#define omxICJP_DecodeHuffmanSpecInit_U8                  OMXCATBAR(ICJP_DecodeHuffmanSpecInit_U8, OMXICJP_SUFFIX)
#define omxICJP_EncodeHuffman8x8_Direct_S16_U1_C1         OMXCATBAR(ICJP_EncodeHuffman8x8_Direct_S16_U1_C1, OMXICJP_SUFFIX)
#define omxICJP_EncodeHuffmanSpecGetBufSize_U8            OMXCATBAR(ICJP_EncodeHuffmanSpecGetBufSize_U8, OMXICJP_SUFFIX)
#define omxICJP_EncodeHuffmanSpecInit_U8                  OMXCATBAR(ICJP_EncodeHuffmanSpecInit_U8, OMXICJP_SUFFIX)

#define omxIPBM_AddC_U8_C1R_Sfs                           OMXCATBAR(IPBM_AddC_U8_C1R_Sfs, OMXIPBM_SUFFIX)
#define omxIPBM_Copy_U8_C1R                               OMXCATBAR(IPBM_Copy_U8_C1R, OMXIPBM_SUFFIX)
#define omxIPBM_Copy_U8_C3R                               OMXCATBAR(IPBM_Copy_U8_C3R, OMXIPBM_SUFFIX)
#define omxIPBM_Mirror_U8_C1R                             OMXCATBAR(IPBM_Mirror_U8_C1R, OMXIPBM_SUFFIX)
#define omxIPBM_MulC_U8_C1R_Sfs                           OMXCATBAR(IPBM_MulC_U8_C1R_Sfs, OMXIPBM_SUFFIX)

#define omxIPCS_ColorTwistQ14_U8_C3R                      OMXCATBAR(IPCS_ColorTwistQ14_U8_C3R, OMXIPCS_SUFFIX)
#define omxIPCS_BGR565ToYCbCr420LS_MCU_U16_S16_C3P3R      OMXCATBAR(IPCS_BGR565ToYCbCr420LS_MCU_U16_S16_C3P3R, OMXIPCS_SUFFIX)
#define omxIPCS_BGR565ToYCbCr422LS_MCU_U16_S16_C3P3R      OMXCATBAR(IPCS_BGR565ToYCbCr422LS_MCU_U16_S16_C3P3R, OMXIPCS_SUFFIX)
#define omxIPCS_BGR565ToYCbCr444LS_MCU_U16_S16_C3P3R      OMXCATBAR(IPCS_BGR565ToYCbCr444LS_MCU_U16_S16_C3P3R, OMXIPCS_SUFFIX)
#define omxIPCS_BGR888ToYCbCr420LS_MCU_U8_S16_C3P3R       OMXCATBAR(IPCS_BGR888ToYCbCr420LS_MCU_U8_S16_C3P3R, OMXIPCS_SUFFIX)
#define omxIPCS_BGR888ToYCbCr422LS_MCU_U8_S16_C3P3R       OMXCATBAR(IPCS_BGR888ToYCbCr422LS_MCU_U8_S16_C3P3R, OMXIPCS_SUFFIX)
#define omxIPCS_BGR888ToYCbCr444LS_MCU_U8_S16_C3P3R       OMXCATBAR(IPCS_BGR888ToYCbCr444LS_MCU_U8_S16_C3P3R, OMXIPCS_SUFFIX)
#define omxIPCS_YCbCr420RszCscRotBGR_U8_P3C3R             OMXCATBAR(IPCS_YCbCr420RszCscRotBGR_U8_P3C3R, OMXIPCS_SUFFIX)
#define omxIPCS_YCbCr420RszRot_U8_P3R                     OMXCATBAR(IPCS_YCbCr420RszRot_U8_P3R, OMXIPCS_SUFFIX)
#define omxIPCS_YCbCr420ToBGR565_U8_U16_P3C3R             OMXCATBAR(IPCS_YCbCr420ToBGR565_U8_U16_P3C3R, OMXIPCS_SUFFIX)
#define omxIPCS_YCbCr420ToBGR565LS_MCU_S16_U16_P3C3R      OMXCATBAR(IPCS_YCbCr420ToBGR565LS_MCU_S16_U16_P3C3R, OMXIPCS_SUFFIX)
#define omxIPCS_YCbCr420ToBGR888LS_MCU_S16_U8_P3C3R       OMXCATBAR(IPCS_YCbCr420ToBGR888LS_MCU_S16_U8_P3C3R, OMXIPCS_SUFFIX)
#define omxIPCS_YCbCr422RszCscRotBGR_U8_P3C3R             OMXCATBAR(IPCS_YCbCr422RszCscRotBGR_U8_P3C3R, OMXIPCS_SUFFIX)
#define omxIPCS_CbYCrY422RszCscRotBGR_U8_U16_C2R          OMXCATBAR(IPCS_CbYCrY422RszCscRotBGR_U8_U16_C2R, OMXIPCS_SUFFIX)
#define omxIPCS_YCbCr422RszRot_U8_P3R                     OMXCATBAR(IPCS_YCbCr422RszRot_U8_P3R, OMXIPCS_SUFFIX)
#define omxIPCS_YCbYCr422ToBGR565_U8_U16_C2C3R            OMXCATBAR(IPCS_YCbYCr422ToBGR565_U8_U16_C2C3R, OMXIPCS_SUFFIX)
#define omxIPCS_YCbCr422ToBGR565LS_MCU_S16_U16_P3C3R      OMXCATBAR(IPCS_YCbCr422ToBGR565LS_MCU_S16_U16_P3C3R, OMXIPCS_SUFFIX)
#define omxIPCS_YCbYCr422ToBGR888_U8_C2C3R                OMXCATBAR(IPCS_YCbYCr422ToBGR888_U8_C2C3R, OMXIPCS_SUFFIX)
#define omxIPCS_YCbCr422ToBGR888LS_MCU_S16_U8_P3C3R       OMXCATBAR(IPCS_YCbCr422ToBGR888LS_MCU_S16_U8_P3C3R, OMXIPCS_SUFFIX)
#define omxIPCS_YCbCr422ToBGR888LS_MCU_S16_U8_P3C3R       OMXCATBAR(IPCS_YCbCr422ToBGR888LS_MCU_S16_U8_P3C3R, OMXIPCS_SUFFIX)
#define omxIPCS_CbYCrY422ToYCbCr420Rotate_U8_C2P3R        OMXCATBAR(IPCS_CbYCrY422ToYCbCr420Rotate_U8_C2P3R, OMXIPCS_SUFFIX)
#define omxIPCS_YCbCr422ToYCbCr420Rotate_U8_P3R           OMXCATBAR(IPCS_YCbCr422ToYCbCr420Rotate_U8_P3R, OMXIPCS_SUFFIX)
#define omxIPCS_YCbCr444ToBGR565_U8_U16_C3R               OMXCATBAR(IPCS_YCbCr444ToBGR565_U8_U16_C3R, OMXIPCS_SUFFIX)
#define omxIPCS_YCbCr444ToBGR565_U8_U16_P3C3R             OMXCATBAR(IPCS_YCbCr444ToBGR565_U8_U16_P3C3R, OMXIPCS_SUFFIX)
#define omxIPCS_YCbCr444ToBGR565LS_MCU_S16_U16_P3C3R      OMXCATBAR(IPCS_YCbCr444ToBGR565LS_MCU_S16_U16_P3C3R, OMXIPCS_SUFFIX)
#define omxIPCS_YCbCr444ToBGR888_U8_C3R                   OMXCATBAR(IPCS_YCbCr444ToBGR888_U8_C3R, OMXIPCS_SUFFIX)

#define omxIPPP_Deblock_HorEdge_U8_I                      OMXCATBAR(IPPP_Deblock_HorEdge_U8_I, OMXIPPP_SUFFIX)
#define omxIPPP_Deblock_VerEdge_U8_I                      OMXCATBAR(IPPP_Deblock_VerEdge_U8_I, OMXIPPP_SUFFIX)
#define omxIPPP_FilterFIR_U8_C1R                          OMXCATBAR(IPPP_FilterFIR_U8_C1R, OMXIPPP_SUFFIX)
#define omxIPPP_FilterMedian_U8_C1R                       OMXCATBAR(IPPP_FilterMedian_U8_C1R, OMXIPPP_SUFFIX)
#define omxIPPP_GetCentralMoment_S64                      OMXCATBAR(IPPP_GetCentralMoment_S64, OMXIPPP_SUFFIX)
#define omxIPPP_GetSpatialMoment_S64                      OMXCATBAR(IPPP_GetSpatialMoment_S64, OMXIPPP_SUFFIX)
#define omxIPPP_MomentGetStateSize                        OMXCATBAR(IPPP_MomentGetStateSize, OMXIPPP_SUFFIX)
#define omxIPPP_MomentInit                                OMXCATBAR(IPPP_MomentInit, OMXIPPP_SUFFIX)
#define omxIPPP_Moments_U8_C1R                            OMXCATBAR(IPPP_Moments_U8_C1R, OMXIPPP_SUFFIX)
#define omxIPPP_Moments_U8_C3R                            OMXCATBAR(IPPP_Moments_U8_C3R, OMXIPPP_SUFFIX)

#define omxSP_BlockExp_S16                                OMXCATBAR(SP_BlockExp_S16, OMXSP_SUFFIX)
#define omxSP_BlockExp_S32                                OMXCATBAR(SP_BlockExp_S32, OMXSP_SUFFIX)
#define omxSP_Copy_S16                                    OMXCATBAR(SP_Copy_S16, OMXSP_SUFFIX)
#define omxSP_DotProd_S16                                 OMXCATBAR(SP_DotProd_S16, OMXSP_SUFFIX)
#define omxSP_DotProd_S16_Sfs                             OMXCATBAR(SP_DotProd_S16_Sfs, OMXSP_SUFFIX)
#define omxSP_FFTFwd_CToC_SC16_Sfs                        OMXCATBAR(SP_FFTFwd_CToC_SC16_Sfs, OMXSP_SUFFIX)
#define omxSP_FFTFwd_CToC_SC32_Sfs                        OMXCATBAR(SP_FFTFwd_CToC_SC32_Sfs, OMXSP_SUFFIX)
#define omxSP_FFTFwd_RToCCS_S16S32_Sfs                    OMXCATBAR(SP_FFTFwd_RToCCS_S16S32_Sfs, OMXSP_SUFFIX)
#define omxSP_FFTFwd_RToCCS_S32_Sfs                       OMXCATBAR(SP_FFTFwd_RToCCS_S32_Sfs, OMXSP_SUFFIX)
#define omxSP_FFTGetBufSize_C_SC16                        OMXCATBAR(SP_FFTGetBufSize_C_SC16, OMXSP_SUFFIX)
#define omxSP_FFTGetBufSize_C_SC32                        OMXCATBAR(SP_FFTGetBufSize_C_SC32, OMXSP_SUFFIX)
#define omxSP_FFTGetBufSize_R_S16S32                      OMXCATBAR(SP_FFTGetBufSize_R_S16S32, OMXSP_SUFFIX)
#define omxSP_FFTGetBufSize_R_S32                         OMXCATBAR(SP_FFTGetBufSize_R_S32, OMXSP_SUFFIX)
#define omxSP_FFTInit_C_SC16                              OMXCATBAR(SP_FFTInit_C_SC16, OMXSP_SUFFIX)
#define omxSP_FFTInit_C_SC32                              OMXCATBAR(SP_FFTInit_C_SC32, OMXSP_SUFFIX)
#define omxSP_FFTInit_R_S16S32                            OMXCATBAR(SP_FFTInit_R_S16S32, OMXSP_SUFFIX)
#define omxSP_FFTInit_R_S32                               OMXCATBAR(SP_FFTInit_R_S32, OMXSP_SUFFIX)
#define omxSP_FFTInv_CCSToR_S32_Sfs                       OMXCATBAR(SP_FFTInv_CCSToR_S32_Sfs, OMXSP_SUFFIX)
#define omxSP_FFTInv_CCSToR_S32S16_Sfs                    OMXCATBAR(SP_FFTInv_CCSToR_S32S16_Sfs, OMXSP_SUFFIX)
#define omxSP_FFTInv_CToC_SC16_Sfs                        OMXCATBAR(SP_FFTInv_CToC_SC16_Sfs, OMXSP_SUFFIX)
#define omxSP_FFTInv_CToC_SC32_Sfs                        OMXCATBAR(SP_FFTInv_CToC_SC32_Sfs, OMXSP_SUFFIX)
#define omxSP_FilterMedian_S32                            OMXCATBAR(SP_FilterMedian_S32, OMXSP_SUFFIX)
#define omxSP_FilterMedian_S32_I                          OMXCATBAR(SP_FilterMedian_S32_I, OMXSP_SUFFIX)
#define omxSP_FIR_Direct_S16                              OMXCATBAR(SP_FIR_Direct_S16, OMXSP_SUFFIX)
#define omxSP_FIR_Direct_S16_I                            OMXCATBAR(SP_FIR_Direct_S16_I, OMXSP_SUFFIX)
#define omxSP_FIR_Direct_S16_ISfs                         OMXCATBAR(SP_FIR_Direct_S16_ISfs, OMXSP_SUFFIX)
#define omxSP_FIR_Direct_S16_Sfs                          OMXCATBAR(SP_FIR_Direct_S16_Sfs, OMXSP_SUFFIX)
#define omxSP_FIROne_Direct_S16                           OMXCATBAR(SP_FIROne_Direct_S16, OMXSP_SUFFIX)
#define omxSP_FIROne_Direct_S16_I                         OMXCATBAR(SP_FIROne_Direct_S16_I, OMXSP_SUFFIX)
#define omxSP_FIROne_Direct_S16_ISfs                      OMXCATBAR(SP_FIROne_Direct_S16_ISfs, OMXSP_SUFFIX)
#define omxSP_FIROne_Direct_S16_Sfs                       OMXCATBAR(SP_FIROne_Direct_S16_Sfs, OMXSP_SUFFIX)
#define omxSP_IIR_BiQuadDirect_S16                        OMXCATBAR(SP_IIR_BiQuadDirect_S16, OMXSP_SUFFIX)
#define omxSP_IIR_BiQuadDirect_S16_I                      OMXCATBAR(SP_IIR_BiQuadDirect_S16_I, OMXSP_SUFFIX)
#define omxSP_IIR_Direct_S16                              OMXCATBAR(SP_IIR_Direct_S16, OMXSP_SUFFIX)
#define omxSP_IIR_Direct_S16_I                            OMXCATBAR(SP_IIR_Direct_S16_I, OMXSP_SUFFIX)
#define omxSP_IIROne_BiQuadDirect_S16                     OMXCATBAR(SP_IIROne_BiQuadDirect_S16, OMXSP_SUFFIX)
#define omxSP_IIROne_BiQuadDirect_S16_I                   OMXCATBAR(SP_IIROne_BiQuadDirect_S16_I, OMXSP_SUFFIX)
#define omxSP_IIROne_Direct_S16                           OMXCATBAR(SP_IIROne_Direct_S16, OMXSP_SUFFIX)
#define omxSP_IIROne_Direct_S16_I                         OMXCATBAR(SP_IIROne_Direct_S16_I, OMXSP_SUFFIX)

#define omxVCCOMM_Average_16x                             OMXCATBAR(VCCOMM_Average_16x, OMXVCCOMM_SUFFIX)
#define omxVCCOMM_Average_8x                              OMXCATBAR(VCCOMM_Average_8x, OMXVCCOMM_SUFFIX)
#define omxVCCOMM_ComputeTextureErrorBlock                OMXCATBAR(VCCOMM_ComputeTextureErrorBlock, OMXVCCOMM_SUFFIX)
#define omxVCCOMM_ComputeTextureErrorBlock_SAD            OMXCATBAR(VCCOMM_ComputeTextureErrorBlock_SAD, OMXVCCOMM_SUFFIX)
#define omxVCCOMM_Copy16x16                               OMXCATBAR(VCCOMM_Copy16x16, OMXVCCOMM_SUFFIX)
#define omxVCCOMM_Copy8x8                                 OMXCATBAR(VCCOMM_Copy8x8, OMXVCCOMM_SUFFIX)
#define omxVCCOMM_ExpandFrame_I                           OMXCATBAR(VCCOMM_ExpandFrame_I, OMXVCCOMM_SUFFIX)
#define omxVCCOMM_LimitMVToRect                           OMXCATBAR(VCCOMM_LimitMVToRect, OMXVCCOMM_SUFFIX)
#define omxVCCOMM_SAD_16x                                 OMXCATBAR(VCCOMM_SAD_16x, OMXVCCOMM_SUFFIX)
#define omxVCCOMM_SAD_8x                                  OMXCATBAR(VCCOMM_SAD_8x, OMXVCCOMM_SUFFIX)

#define omxVCM4P10_Average_4x                             OMXCATBAR(VCM4P10_Average_4x, OMXVCM4P10_SUFFIX)
#define omxVCM4P10_BlockMatch_Half                        OMXCATBAR(VCM4P10_BlockMatch_Half, OMXVCM4P10_SUFFIX)
#define omxVCM4P10_BlockMatch_Integer                     OMXCATBAR(VCM4P10_BlockMatch_Integer, OMXVCM4P10_SUFFIX)
#define omxVCM4P10_BlockMatch_Quarter                     OMXCATBAR(VCM4P10_BlockMatch_Quarter, OMXVCM4P10_SUFFIX)
#define omxVCM4P10_DeblockChroma_I                        OMXCATBAR(VCM4P10_DeblockChroma_I, OMXVCM4P10_SUFFIX)
#define omxVCM4P10_DeblockLuma_I                          OMXCATBAR(VCM4P10_DeblockLuma_I, OMXVCM4P10_SUFFIX)
#define omxVCM4P10_DecodeChromaDcCoeffsToPairCAVLC        OMXCATBAR(VCM4P10_DecodeChromaDcCoeffsToPairCAVLC, OMXVCM4P10_SUFFIX)
#define omxVCM4P10_DecodeCoeffsToPairCAVLC                OMXCATBAR(VCM4P10_DecodeCoeffsToPairCAVLC, OMXVCM4P10_SUFFIX)
#define omxVCM4P10_DequantTransformResidualFromPairAndAdd OMXCATBAR(VCM4P10_DequantTransformResidualFromPairAndAdd, OMXVCM4P10_SUFFIX)
#define omxVCM4P10_FilterDeblockingChroma_HorEdge_I       OMXCATBAR(VCM4P10_FilterDeblockingChroma_HorEdge_I, OMXVCM4P10_SUFFIX)
#define omxVCM4P10_FilterDeblockingChroma_VerEdge_I       OMXCATBAR(VCM4P10_FilterDeblockingChroma_VerEdge_I, OMXVCM4P10_SUFFIX)
#define omxVCM4P10_FilterDeblockingLuma_HorEdge_I         OMXCATBAR(VCM4P10_FilterDeblockingLuma_HorEdge_I, OMXVCM4P10_SUFFIX)
#define omxVCM4P10_FilterDeblockingLuma_VerEdge_I         OMXCATBAR(VCM4P10_FilterDeblockingLuma_VerEdge_I, OMXVCM4P10_SUFFIX)
#define omxVCM4P10_GetVLCInfo                             OMXCATBAR(VCM4P10_GetVLCInfo, OMXVCM4P10_SUFFIX)
#define omxVCM4P10_InterpolateChroma                      OMXCATBAR(VCM4P10_InterpolateChroma, OMXVCM4P10_SUFFIX)
#define omxVCM4P10_InterpolateHalfHor_Luma                OMXCATBAR(VCM4P10_InterpolateHalfHor_Luma, OMXVCM4P10_SUFFIX)
#define omxVCM4P10_InterpolateHalfVer_Luma                OMXCATBAR(VCM4P10_InterpolateHalfVer_Luma, OMXVCM4P10_SUFFIX)
#define omxVCM4P10_InterpolateLuma                        OMXCATBAR(VCM4P10_InterpolateLuma, OMXVCM4P10_SUFFIX)
#define omxVCM4P10_InvTransformDequant_ChromaDC           OMXCATBAR(VCM4P10_InvTransformDequant_ChromaDC, OMXVCM4P10_SUFFIX)
#define omxVCM4P10_InvTransformDequant_LumaDC             OMXCATBAR(VCM4P10_InvTransformDequant_LumaDC, OMXVCM4P10_SUFFIX)
#define omxVCM4P10_InvTransformResidualAndAdd             OMXCATBAR(VCM4P10_InvTransformResidualAndAdd, OMXVCM4P10_SUFFIX)
#define omxVCM4P10_MEGetBufSize                           OMXCATBAR(VCM4P10_MEGetBufSize, OMXVCM4P10_SUFFIX)
#define omxVCM4P10_MEInit                                 OMXCATBAR(VCM4P10_MEInit, OMXVCM4P10_SUFFIX)
#define omxVCM4P10_MotionEstimationMB                     OMXCATBAR(VCM4P10_MotionEstimationMB, OMXVCM4P10_SUFFIX)
#define omxVCM4P10_PredictIntra_16x16                     OMXCATBAR(VCM4P10_PredictIntra_16x16, OMXVCM4P10_SUFFIX)
#define omxVCM4P10_PredictIntra_4x4                       OMXCATBAR(VCM4P10_PredictIntra_4x4, OMXVCM4P10_SUFFIX)
#define omxVCM4P10_PredictIntraChroma_8x8                  OMXCATBAR(VCM4P10_PredictIntraChroma_8x8, OMXVCM4P10_SUFFIX)
#define omxVCM4P10_SAD_4x                                 OMXCATBAR(VCM4P10_SAD_4x, OMXVCM4P10_SUFFIX)
#define omxVCM4P10_SADQuar_16x                            OMXCATBAR(VCM4P10_SADQuar_16x, OMXVCM4P10_SUFFIX)
#define omxVCM4P10_SADQuar_4x                             OMXCATBAR(VCM4P10_SADQuar_4x, OMXVCM4P10_SUFFIX)
#define omxVCM4P10_SADQuar_8x                             OMXCATBAR(VCM4P10_SADQuar_8x, OMXVCM4P10_SUFFIX)
#define omxVCM4P10_SATD_4x4                               OMXCATBAR(VCM4P10_SATD_4x4, OMXVCM4P10_SUFFIX)
#define omxVCM4P10_SubAndTransformQDQResidual             OMXCATBAR(VCM4P10_SubAndTransformQDQResidual, OMXVCM4P10_SUFFIX)
#define omxVCM4P10_TransformDequantChromaDCFromPair       OMXCATBAR(VCM4P10_TransformDequantChromaDCFromPair, OMXVCM4P10_SUFFIX)
#define omxVCM4P10_TransformDequantLumaDCFromPair         OMXCATBAR(VCM4P10_TransformDequantLumaDCFromPair, OMXVCM4P10_SUFFIX)
#define omxVCM4P10_TransformQuant_ChromaDC                OMXCATBAR(VCM4P10_TransformQuant_ChromaDC, OMXVCM4P10_SUFFIX)
#define omxVCM4P10_TransformQuant_LumaDC                  OMXCATBAR(VCM4P10_TransformQuant_LumaDC, OMXVCM4P10_SUFFIX)

#define omxVCM4P2_BlockMatch_Half_16x16                   OMXCATBAR(VCM4P2_BlockMatch_Half_16x16, OMXVCM4P2_SUFFIX)
#define omxVCM4P2_BlockMatch_Half_8x8                     OMXCATBAR(VCM4P2_BlockMatch_Half_8x8, OMXVCM4P2_SUFFIX)
#define omxVCM4P2_BlockMatch_Integer_16x16                OMXCATBAR(VCM4P2_BlockMatch_Integer_16x16, OMXVCM4P2_SUFFIX)
#define omxVCM4P2_BlockMatch_Integer_8x8                  OMXCATBAR(VCM4P2_BlockMatch_Integer_8x8, OMXVCM4P2_SUFFIX)
#define omxVCM4P2_DCT8x8blk                               OMXCATBAR(VCM4P2_DCT8x8blk, OMXVCM4P2_SUFFIX)
#define omxVCM4P2_DecodeBlockCoef_Inter                   OMXCATBAR(VCM4P2_DecodeBlockCoef_Inter, OMXVCM4P2_SUFFIX)
#define omxVCM4P2_DecodeBlockCoef_Intra                   OMXCATBAR(VCM4P2_DecodeBlockCoef_Intra, OMXVCM4P2_SUFFIX)
#define omxVCM4P2_DecodePadMV_PVOP                        OMXCATBAR(VCM4P2_DecodePadMV_PVOP, OMXVCM4P2_SUFFIX)
#define omxVCM4P2_DecodeVLCZigzag_Inter                   OMXCATBAR(VCM4P2_DecodeVLCZigzag_Inter, OMXVCM4P2_SUFFIX)
#define omxVCM4P2_DecodeVLCZigzag_IntraACVLC              OMXCATBAR(VCM4P2_DecodeVLCZigzag_IntraACVLC, OMXVCM4P2_SUFFIX)
#define omxVCM4P2_DecodeVLCZigzag_IntraDCVLC              OMXCATBAR(VCM4P2_DecodeVLCZigzag_IntraDCVLC, OMXVCM4P2_SUFFIX)
#define omxVCM4P2_EncodeMV                                OMXCATBAR(VCM4P2_EncodeMV, OMXVCM4P2_SUFFIX)
#define omxVCM4P2_EncodeVLCZigzag_Inter                   OMXCATBAR(VCM4P2_EncodeVLCZigzag_Inter, OMXVCM4P2_SUFFIX)
#define omxVCM4P2_EncodeVLCZigzag_IntraACVLC              OMXCATBAR(VCM4P2_EncodeVLCZigzag_IntraACVLC, OMXVCM4P2_SUFFIX)
#define omxVCM4P2_EncodeVLCZigzag_IntraDCVLC              OMXCATBAR(VCM4P2_EncodeVLCZigzag_IntraDCVLC, OMXVCM4P2_SUFFIX)
#define omxVCM4P2_FindMVpred                              OMXCATBAR(VCM4P2_FindMVpred, OMXVCM4P2_SUFFIX)
#define omxVCM4P2_IDCT8x8blk                              OMXCATBAR(VCM4P2_IDCT8x8blk, OMXVCM4P2_SUFFIX)
#define omxVCM4P2_MCReconBlock                            OMXCATBAR(VCM4P2_MCReconBlock, OMXVCM4P2_SUFFIX)
#define omxVCM4P2_MEGetBufSize                            OMXCATBAR(VCM4P2_MEGetBufSize, OMXVCM4P2_SUFFIX)
#define omxVCM4P2_MEInit                                  OMXCATBAR(VCM4P2_MEInit, OMXVCM4P2_SUFFIX)
#define omxVCM4P2_MotionEstimationMB                      OMXCATBAR(VCM4P2_MotionEstimationMB, OMXVCM4P2_SUFFIX)
#define omxVCM4P2_PredictReconCoefIntra                   OMXCATBAR(VCM4P2_PredictReconCoefIntra, OMXVCM4P2_SUFFIX)
#define omxVCM4P2_QuantInter_I                            OMXCATBAR(VCM4P2_QuantInter_I, OMXVCM4P2_SUFFIX)
#define omxVCM4P2_QuantIntra_I                            OMXCATBAR(VCM4P2_QuantIntra_I, OMXVCM4P2_SUFFIX)
#define omxVCM4P2_QuantInvInter_I                         OMXCATBAR(VCM4P2_QuantInvInter_I, OMXVCM4P2_SUFFIX)
#define omxVCM4P2_QuantInvIntra_I                         OMXCATBAR(VCM4P2_QuantInvIntra_I, OMXVCM4P2_SUFFIX)
#define omxVCM4P2_TransRecBlockCoef_inter                 OMXCATBAR(VCM4P2_TransRecBlockCoef_inter, OMXVCM4P2_SUFFIX)
#define omxVCM4P2_TransRecBlockCoef_intra                 OMXCATBAR(VCM4P2_TransRecBlockCoef_intra, OMXVCM4P2_SUFFIX)


#endif /* _armOMX_h_ */
