/**
 * 
 * File Name:  omxVCM4P2_TransRecBlockCoef_intra.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 *
 * Description:
 * Contains modules DCT->quant and reconstructing the intra texture data
 * 
 */ 
 
#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armCOMM.h"
#include "armVC.h"


/**
 * Function:  omxVCM4P2_TransRecBlockCoef_intra   (6.2.4.4.4)
 *
 * Description:
 * Quantizes the DCT coefficients, implements intra block AC/DC coefficient 
 * prediction, and reconstructs the current intra block texture for prediction 
 * on the next frame.  Quantized row and column coefficients are returned in 
 * the updated coefficient buffers. 
 *
 * Input Arguments:
 *   
 *   pSrc - pointer to the pixels of current intra block; must be aligned on 
 *            an 8-byte boundary. 
 *   pPredBufRow - pointer to the coefficient row buffer containing 
 *            ((num_mb_per_row * 2 + 1) * 8) elements of type OMX_S16. 
 *            Coefficients are organized into blocks of eight as described 
 *            below (Internal Prediction Coefficient Update Procedures).  The 
 *            DC coefficient is first, and the remaining buffer locations 
 *            contain the quantized AC coefficients. Each group of eight row 
 *            buffer elements combined with one element eight elements ahead 
 *            contains the coefficient predictors of the neighboring block 
 *            that is spatially above or to the left of the block currently to 
 *            be decoded. A negative-valued DC coefficient indicates that this 
 *            neighboring block is not INTRA-coded or out of bounds, and 
 *            therefore the AC and DC coefficients are invalid.  Pointer must 
 *            be aligned on an 8-byte boundary. 
 *   pPredBufCol - pointer to the prediction coefficient column buffer 
 *            containing 16 elements of type OMX_S16. Coefficients are 
 *            organized as described in section 6.2.2.5.  Pointer must be 
 *            aligned on an 8-byte boundary. 
 *   pSumErr - pointer to a flag indicating whether or not AC prediction is 
 *            required; AC prediction is enabled if *pSumErr >=0, but the 
 *            value is not used for coefficient prediction, i.e., the sum of 
 *            absolute differences starts from 0 for each call to this 
 *            function.  Otherwise AC prediction is disabled if *pSumErr < 0 . 
 *   blockIndex - block index indicating the component type and position, as 
 *            defined in [ISO14496-2], subclause 6.1.3.8. 
 *   curQp - quantization parameter of the macroblock to which the current 
 *            block belongs 
 *   pQpBuf - pointer to a 2-element quantization parameter buffer; pQpBuf[0] 
 *            contains the quantization parameter associated with the 8x8 
 *            block left of the current block (QPa), and pQpBuf[1] contains 
 *            the quantization parameter associated with the 8x8 block above 
 *            the current block (QPc).  In the event that the corresponding 
 *            block is outside of the VOP bound, the Qp value will not affect 
 *            the intra prediction process, as described in [ISO14496-2], 
 *            sub-clause 7.4.3.3,  Adaptive AC Coefficient Prediction.  
 *   srcStep - width of the source buffer; must be a multiple of 8. 
 *   dstStep - width of the reconstructed destination buffer; must be a 
 *            multiple of 16. 
 *   shortVideoHeader - binary flag indicating presence of 
 *            short_video_header; shortVideoHeader==1 selects linear intra DC 
 *            mode, and shortVideoHeader==0 selects non linear intra DC mode. 
 *
 * Output Arguments:
 *   
 *   pDst - pointer to the quantized DCT coefficient buffer; pDst[0] contains 
 *            the predicted DC coefficient; the remaining entries contain the 
 *            quantized AC coefficients (without prediction).  The pointer 
 *            pDstmust be aligned on a 16-byte boundary. 
 *   pRec - pointer to the reconstructed texture; must be aligned on an 
 *            8-byte boundary. 
 *   pPredBufRow - pointer to the updated coefficient row buffer 
 *   pPredBufCol - pointer to the updated coefficient column buffer 
 *   pPreACPredict - if prediction is enabled, the parameter points to the 
 *            start of the buffer containing the coefficient differences for 
 *            VLC encoding. The entry pPreACPredict[0]indicates prediction 
 *            direction for the current block and takes one of the following 
 *            values: OMX_VC_NONE (prediction disabled), OMX_VC_HORIZONTAL, or 
 *            OMX_VC_VERTICAL.  The entries 
 *            pPreACPredict[1]-pPreACPredict[7]contain predicted AC 
 *            coefficients.  If prediction is disabled (*pSumErr<0) then the 
 *            contents of this buffer are undefined upon return from the 
 *            function 
 *   pSumErr - pointer to the value of the accumulated AC coefficient errors, 
 *            i.e., sum of the absolute differences between predicted and 
 *            unpredicted AC coefficients 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - Bad arguments:
 *    -    At least one of the following pointers is NULL: pSrc, pDst, pRec, 
 *         pCoefBufRow, pCoefBufCol, pQpBuf, pPreACPredict, pSumErr. 
 *    -    blockIndex < 0 or blockIndex >= 10; 
 *    -    curQP <= 0 or curQP >= 32. 
 *    -    srcStep, or dstStep <= 0 or not a multiple of 8. 
 *    -    pDst is not 16-byte aligned: . 
 *    -    At least one of the following pointers is not 8-byte aligned: 
 *         pSrc, pRec.  
 *
 *  Note: The coefficient buffers must be updated in accordance with the 
 *        update procedures defined in section in 6.2.2. 
 *
 */

OMXResult omxVCM4P2_TransRecBlockCoef_intra(
     const OMX_U8 *pSrc,
     OMX_S16 * pDst,
     OMX_U8 * pRec,
     OMX_S16 *pPredBufRow,
     OMX_S16 *pPredBufCol,
     OMX_S16 * pPreACPredict,
     OMX_INT *pSumErr,
     OMX_INT blockIndex,
     OMX_U8 curQp,
     const OMX_U8 *pQpBuf,
     OMX_INT srcStep,
     OMX_INT dstStep,
	 OMX_INT shortVideoHeader
)
{
    /* 64 elements are needed but to align it to 16 bytes need
    8 more elements of padding */
    OMX_S16 tempBuf1[79], tempBuf2[79];
    OMX_S16 tempBuf3[79];
    OMX_S16 *pTempBuf1, *pTempBuf2,*pTempBuf3;
    OMXVCM4P2VideoComponent videoComp;
    OMX_U8  flag;
    OMX_INT x, y, count, predDir;
    OMX_INT predQP, ACPredFlag;
    

    /* Aligning the local buffers */
    pTempBuf1 = armAlignTo16Bytes(tempBuf1);
    pTempBuf2 = armAlignTo16Bytes(tempBuf2);
    pTempBuf3 = armAlignTo16Bytes(tempBuf3);

    /* Argument error checks */
    armRetArgErrIf(pSrc == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pRec == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pDst == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(!armIs8ByteAligned(pSrc), OMX_Sts_BadArgErr);
    armRetArgErrIf(!armIs8ByteAligned(pRec), OMX_Sts_BadArgErr);
    armRetArgErrIf(!armIs16ByteAligned(pDst), OMX_Sts_BadArgErr);
    armRetArgErrIf(pPredBufRow == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pPredBufCol == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pPreACPredict == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pSumErr == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pQpBuf == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf((srcStep <= 0) || (dstStep <= 0) ||
                (dstStep & 7) || (srcStep & 7)
                , OMX_Sts_BadArgErr);
    armRetArgErrIf((blockIndex < 0) || (blockIndex > 9), OMX_Sts_BadArgErr);

    armRetArgErrIf((curQp <= 0) || (curQp >=32), OMX_Sts_BadArgErr);


   /* Setting the videoComp */
    if (blockIndex <= 3)
    {
        videoComp = OMX_VC_LUMINANCE;
    }
    else
    {
        videoComp = OMX_VC_CHROMINANCE;
    }
    /* Converting from 2-d to 1-d buffer */
    for (y = 0, count = 0; y < 8; y++)
    {
        for(x= 0; x < 8; x++, count++)
        {
            pTempBuf1[count] = pSrc[(y*srcStep) + x];
        }
    }

    omxVCM4P2_DCT8x8blk  (pTempBuf1, pTempBuf2);
    omxVCM4P2_QuantIntra_I(
        pTempBuf2,
        curQp,
        blockIndex,
        shortVideoHeader);

    /* Converting from 1-D to 2-D buffer */
    for (y = 0, count = 0; y < 8; y++)
    {
        for(x = 0; x < 8; x++, count++)
        {
            /* storing tempbuf2 to tempbuf1 */
            pTempBuf1[count] = pTempBuf2[count];
            pDst[(y*dstStep) + x] = pTempBuf2[count];
        }
    }

    /* AC and DC prediction */
    armVCM4P2_SetPredDir(
        blockIndex,
        pPredBufRow,
        pPredBufCol,
        &predDir,
        &predQP,
        pQpBuf);

    armRetDataErrIf(((predQP <= 0) || (predQP >= 32)), OMX_Sts_BadArgErr);

    flag = 1;
    if (*pSumErr < 0)
    {
        ACPredFlag = 0;
    }
    else
    {
        ACPredFlag = 1;
    }

    armVCM4P2_ACDCPredict(
        pTempBuf2,
        pPreACPredict,
        pPredBufRow,
        pPredBufCol,
        curQp,
        predQP,
        predDir,
        ACPredFlag,
        videoComp,
        flag,
        pSumErr);

    /* Reconstructing the texture data */
    omxVCM4P2_QuantInvIntra_I(
        pTempBuf1,
        curQp,
        videoComp,
        shortVideoHeader);
    omxVCM4P2_IDCT8x8blk (pTempBuf1, pTempBuf3);
    for(count = 0; count < 64; count++)
    {
        pRec[count] = armMax(0,pTempBuf3[count]);
    }

    return OMX_Sts_NoErr;
}

/* End of file */


