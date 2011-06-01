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
 * Function:  omxVCM4P2_DecodeBlockCoef_Intra   (6.2.5.4.1)
 *
 * Description:
 * Decodes the INTRA block coefficients. Inverse quantization, inversely 
 * zigzag positioning, and IDCT, with appropriate clipping on each step, are 
 * performed on the coefficients. The results are then placed in the output 
 * frame/plane on a pixel basis.  Note: This function will be used only when 
 * at least one non-zero AC coefficient of current block exists in the bit 
 * stream. The DC only condition will be handled in another function. 
 *
 *
 * Input Arguments:
 *   
 *   ppBitStream - pointer to the pointer to the current byte in the bit 
 *            stream buffer. There is no boundary check for the bit stream 
 *            buffer. 
 *   pBitOffset - pointer to the bit position in the byte pointed to by 
 *            *ppBitStream. *pBitOffset is valid within [0-7]. 
 *   step - width of the destination plane 
 *   pCoefBufRow - pointer to the coefficient row buffer; must be aligned on 
 *            an 8-byte boundary. 
 *   pCoefBufCol - pointer to the coefficient column buffer; must be aligned 
 *            on an 8-byte boundary. 
 *   curQP - quantization parameter of the macroblock which the current block 
 *            belongs to 
 *   pQPBuf - pointer to the quantization parameter buffer 
 *   blockIndex - block index indicating the component type and position as 
 *            defined in [ISO14496-2], subclause 6.1.3.8, Figure 6-5. 
 *   intraDCVLC - a code determined by intra_dc_vlc_thr and QP. This allows a 
 *            mechanism to switch between two VLC for coding of Intra DC 
 *            coefficients as per [ISO14496-2], Table 6-21. 
 *   ACPredFlag - a flag equal to ac_pred_flag (of luminance) indicating if 
 *            the ac coefficients of the first row or first column are 
 *            differentially coded for intra coded macroblock. 
 *   shortVideoHeader - binary flag indicating presence of 
 *            short_video_header; shortVideoHeader==1 selects linear intra DC 
 *            mode, and shortVideoHeader==0 selects non linear intra DC mode. 
 *
 * Output Arguments:
 *   
 *   ppBitStream - *ppBitStream is updated after the block is decoded, so 
 *            that it points to the current byte in the bit stream buffer 
 *   pBitOffset - *pBitOffset is updated so that it points to the current bit 
 *            position in the byte pointed by *ppBitStream 
 *   pDst - pointer to the block in the destination plane; must be aligned on 
 *            an 8-byte boundary. 
 *   pCoefBufRow - pointer to the updated coefficient row buffer. 
 *   pCoefBufCol - pointer to the updated coefficient column buffer  Note: 
 *            The coefficient buffers must be updated in accordance with the 
 *            update procedure defined in section 6.2.2. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments, if:
 *    -    At least one of the following pointers is NULL: 
 *         ppBitStream, *ppBitStream, pBitOffset, pCoefBufRow, pCoefBufCol, 
 *         pQPBuf, pDst. 
 *    -    *pBitOffset exceeds [0,7] 
 *    -    curQP exceeds (1, 31)
 *    -    blockIndex exceeds [0,5]
 *    -    step is not the multiple of 8
 *    -    a pointer alignment requirement was violated. 
 *    OMX_Sts_Err - status error. Refer to OMX_Sts_Err of DecodeVLCZigzag_Intra.  
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
    OMX_INT predDir, predACDir, i, j, count;
    OMX_INT  predQP;
    OMXVCM4P2VideoComponent videoComp;
    OMXResult errorCode;
    
    /* Argument error checks */
    armRetArgErrIf(ppBitStream == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(*ppBitStream == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pBitOffset == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pDst == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pCoefBufRow == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pCoefBufCol == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pQPBuf == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(!armIs8ByteAligned(pDst), OMX_Sts_BadArgErr);
    armRetArgErrIf(((curQP <= 0) || (curQP >= 32)), OMX_Sts_BadArgErr);
    armRetArgErrIf((*pBitOffset < 0) || (*pBitOffset >7), OMX_Sts_BadArgErr);
    armRetArgErrIf((blockIndex < 0) || (blockIndex > 5), OMX_Sts_BadArgErr);
    armRetArgErrIf((step % 8) != 0, OMX_Sts_BadArgErr);
    

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

    armRetArgErrIf(((predQP <= 0) || (predQP >= 32)), OMX_Sts_BadArgErr);

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
    for (j = 0, count = 0; j < 8; j++)
    {
        for(i = 0; i < 8; i++, count++)
        {
            pDst[i] = armClip (0, 255, pTempBuf2[count]);
        }
        pDst += step;
    }

    return OMX_Sts_NoErr;
}

/* End of file */


