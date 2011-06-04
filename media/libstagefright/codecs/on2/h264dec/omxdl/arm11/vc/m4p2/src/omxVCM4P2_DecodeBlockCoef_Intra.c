/**
 * 
 * File Name:  omxVCM4P2_DecodeBlockCoef_Intra.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 *
 * Description: 
 * Contains modules for intra reconstruction
 * 
 */

#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armCOMM.h"
#include "armVC.h"

/**
 * Function: omxVCM4P2_DecodeBlockCoef_Intra
 *
 * Description:
 * Decodes the INTRA block coefficients. Inverse quantization, inversely zigzag
 * positioning, and IDCT, with appropriate clipping on each step, are performed
 * on the coefficients. The results are then placed in the output frame/plane on
 * a pixel basis. For INTRA block, the output values are clipped to [0, 255] and
 * written to corresponding block buffer within the destination plane.
 *
 * Remarks:
 *
 * Parameters:
 * [in]	ppBitStream		pointer to the pointer to the current byte in
 *								the bit stream buffer. There is no boundary
 *								check for the bit stream buffer.
 * [in]	pBitOffset		pointer to the bit position in the byte pointed
 *								to by *ppBitStream. *pBitOffset is valid within
 *								[0-7].
 * [in]	step			width of the destination plane
 * [in/out]	pCoefBufRow		[in]  pointer to the coefficient row buffer
 *                        [out] updated coefficient rwo buffer
 * [in/out]	pCoefBufCol		[in]  pointer to the coefficient column buffer
 *                        [out] updated coefficient column buffer
 * [in]	curQP			quantization parameter of the macroblock which
 *								the current block belongs to
 * [in]	pQpBuf		 Pointer to a 2-element QP array. pQpBuf[0] holds the QP of the 8x8 block left to
 *                   the current block(QPa). pQpBuf[1] holds the QP of the 8x8 block just above the
 *                   current block(QPc).
 *                   Note, in case the corresponding block is out of VOP bound, the QP value will have
 *                   no effect to the intra-prediction process. Refer to subclause  "7.4.3.3 Adaptive
 *                   ac coefficient prediction" of ISO/IEC 14496-2(MPEG4 Part2) for accurate description.
 * [in]	blockIndex		block index indicating the component type and
 *								position as defined in subclause 6.1.3.8,
 *								Figure 6-5 of ISO/IEC 14496-2. 
 * [in]	intraDCVLC		a code determined by intra_dc_vlc_thr and QP.
 *								This allows a mechanism to switch between two VLC
 *								for coding of Intra DC coefficients as per Table
 *								6-21 of ISO/IEC 14496-2. 
 * [in]	ACPredFlag		a flag equal to ac_pred_flag (of luminance) indicating
 *								if the ac coefficients of the first row or first
 *								column are differentially coded for intra coded
 *								macroblock.
 * [in] shortVideoHeader    a flag indicating presence of short_video_header;
 *                           shortVideoHeader==1 selects linear intra DC mode,
 *							and shortVideoHeader==0 selects nonlinear intra DC mode.
 * [out]	ppBitStream		*ppBitStream is updated after the block is
 *								decoded, so that it points to the current byte
 *								in the bit stream buffer
 * [out]	pBitOffset		*pBitOffset is updated so that it points to the
 *								current bit position in the byte pointed by
 *								*ppBitStream
 * [out]	pDst			pointer to the block in the destination plane.
 *								pDst should be 16-byte aligned.
 * [out]	pCoefBufRow		pointer to the updated coefficient row buffer.
 *
 * Return Value:
 * OMX_Sts_NoErr - no error
 * OMX_Sts_BadArgErr - bad arguments
 *   -	At least one of the following pointers is NULL: ppBitStream, *ppBitStream, pBitOffset,
 *                                                      pCoefBufRow, pCoefBufCol, pQPBuf, pDst.
 *      or
 *   -  At least one of the below case: *pBitOffset exceeds [0,7], curQP exceeds (1, 31),
 *      blockIndex exceeds [0,9], step is not the multiple of 8, intraDCVLC is zero while
 *      blockIndex greater than 5.
 *      or
 *   -	pDst is not 16-byte aligned
 * OMX_Sts_Err - status error
 *
 */

OMXResult omxVCM4P2_DecodeBlockCoef_Intra(
     const OMX_U8 ** ppBitStream,
     OMX_INT *pBitOffset,
     OMX_U8 *pDst,
     OMX_INT step,
     OMX_S16 *pCoefBufRow,
     OMX_S16 *pCoefBufCol,
     OMX_U8 curQP,
     const OMX_U8 *pQPBuf,
     OMX_INT blockIndex,
     OMX_INT intraDCVLC,
     OMX_INT ACPredFlag,
	 OMX_INT shortVideoHeader
 )
{
    OMX_S16 tempBuf1[79], tempBuf2[79];
    OMX_S16 *pTempBuf1, *pTempBuf2;
    OMX_INT predDir, predACDir;
    OMX_INT  predQP;
    OMXVCM4P2VideoComponent videoComp;
    OMXResult errorCode;
    
    
    /* Aligning the local buffers */
    pTempBuf1 = armAlignTo16Bytes(tempBuf1);
    pTempBuf2 = armAlignTo16Bytes(tempBuf2);
    
    /* Setting the AC prediction direction and prediction direction */
    armVCM4P2_SetPredDir(
        blockIndex,
        pCoefBufRow,
        pCoefBufCol,
        &predDir,
        &predQP,
        pQPBuf);

    predACDir = predDir;

    
    if (ACPredFlag == 0)
    {
        predACDir = OMX_VC_NONE;
    }

    /* Setting the videoComp */
    if (blockIndex <= 3)
    {
        videoComp = OMX_VC_LUMINANCE;
    }
    else
    {
        videoComp = OMX_VC_CHROMINANCE;
    }
    

    /* VLD and zigzag */
    if (intraDCVLC == 1)
    {
        errorCode = omxVCM4P2_DecodeVLCZigzag_IntraDCVLC(
            ppBitStream,
            pBitOffset,
            pTempBuf1,
            predACDir,
            shortVideoHeader,
            videoComp);
        armRetDataErrIf((errorCode != OMX_Sts_NoErr), errorCode);
    }
    else
    {
        errorCode = omxVCM4P2_DecodeVLCZigzag_IntraACVLC(
            ppBitStream,
            pBitOffset,
            pTempBuf1,
            predACDir,
            shortVideoHeader);
        armRetDataErrIf((errorCode != OMX_Sts_NoErr), errorCode);
    }

    /* AC DC prediction */
    errorCode = omxVCM4P2_PredictReconCoefIntra(
        pTempBuf1,
        pCoefBufRow,
        pCoefBufCol,
        curQP,
        predQP,
        predDir,
        ACPredFlag,
        videoComp);
    armRetDataErrIf((errorCode != OMX_Sts_NoErr), errorCode);
    
    /* Dequantization */
    errorCode = omxVCM4P2_QuantInvIntra_I(
     pTempBuf1,
     curQP,
     videoComp,
     shortVideoHeader);
    armRetDataErrIf((errorCode != OMX_Sts_NoErr), errorCode);
    
    /* Inverse transform */
    errorCode = omxVCM4P2_IDCT8x8blk (pTempBuf1, pTempBuf2);
    armRetDataErrIf((errorCode != OMX_Sts_NoErr), errorCode);
    
    /* Placing the linear array into the destination plane and clipping
       it to 0 to 255 */
    
	armVCM4P2_Clip8(pTempBuf2,pDst,step);
	
	
    return OMX_Sts_NoErr;
}

/* End of file */


