 /**
 * 
 * File Name:  omxVCM4P2_PredictReconCoefIntra.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 *
 * File:        omxVCM4P2_PredictReconCoefIntra_S16.c
 * Description: Contains modules for AC DC prediction
 *
 */

#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armCOMM.h"
#include "armVC.h"

/**
 * Function:  omxVCM4P2_PredictReconCoefIntra   (6.2.5.4.3)
 *
 * Description:
 * Performs adaptive DC/AC coefficient prediction for an intra block.  Prior 
 * to the function call, prediction direction (predDir) should be selected as 
 * specified in [ISO14496-2], subclause 7.4.3.1. 
 *
 * Input Arguments:
 *   
 *   pSrcDst - pointer to the coefficient buffer which contains the quantized 
 *            coefficient residuals (PQF) of the current block; must be 
 *            aligned on a 4-byte boundary.  The output coefficients are 
 *            saturated to the range [-2048, 2047]. 
 *   pPredBufRow - pointer to the coefficient row buffer; must be aligned on 
 *            a 4-byte boundary. 
 *   pPredBufCol - pointer to the coefficient column buffer; must be aligned 
 *            on a 4-byte boundary. 
 *   curQP - quantization parameter of the current block. curQP may equal to 
 *            predQP especially when the current block and the predictor block 
 *            are in the same macroblock. 
 *   predQP - quantization parameter of the predictor block 
 *   predDir - indicates the prediction direction which takes one of the 
 *            following values: OMX_VC_HORIZONTAL - predict horizontally 
 *            OMX_VC_VERTICAL - predict vertically 
 *   ACPredFlag - a flag indicating if AC prediction should be performed. It 
 *            is equal to ac_pred_flag in the bit stream syntax of MPEG-4 
 *   videoComp - video component type (luminance or chrominance) of the 
 *            current block 
 *
 * Output Arguments:
 *   
 *   pSrcDst - pointer to the coefficient buffer which contains the quantized 
 *            coefficients (QF) of the current block 
 *   pPredBufRow - pointer to the updated coefficient row buffer 
 *   pPredBufCol - pointer to the updated coefficient column buffer  Note: 
 *            Buffer update: Update the AC prediction buffer (both row and 
 *            column buffer). 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments, if:
 *        -    At least one of the pointers is NULL: 
 *              pSrcDst, pPredBufRow, or pPredBufCol. 
 *        -    curQP <= 0, 
 *        -    predQP <= 0, 
 *        -    curQP >31, 
 *        -    predQP > 31, 
 *        -    preDir exceeds [1,2]
 *        -    pSrcDst, pPredBufRow, or pPredBufCol is not 4-byte aligned. 
 *
 */

OMXResult omxVCM4P2_PredictReconCoefIntra(
     OMX_S16 * pSrcDst,
     OMX_S16 * pPredBufRow,
     OMX_S16 * pPredBufCol,
     OMX_INT curQP,
     OMX_INT predQP,
     OMX_INT predDir,
     OMX_INT ACPredFlag,
     OMXVCM4P2VideoComponent videoComp
 )
{
    OMX_U8 flag;
    /* Argument error checks */
    armRetArgErrIf(pSrcDst == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pPredBufRow == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pPredBufCol == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(curQP <= 0, OMX_Sts_BadArgErr);
    armRetArgErrIf(predQP <= 0, OMX_Sts_BadArgErr);
    armRetArgErrIf(curQP > 31, OMX_Sts_BadArgErr);
    armRetArgErrIf(predQP > 31, OMX_Sts_BadArgErr);
    armRetArgErrIf((predDir != 1) && (predDir != 2), OMX_Sts_BadArgErr);
    armRetArgErrIf(!armIs4ByteAligned(pSrcDst), OMX_Sts_BadArgErr);
    armRetArgErrIf(!armIs4ByteAligned(pPredBufRow), OMX_Sts_BadArgErr);
    armRetArgErrIf(!armIs4ByteAligned(pPredBufCol), OMX_Sts_BadArgErr);

    flag = 0;
    return armVCM4P2_ACDCPredict(
        pSrcDst,
        NULL,
        pPredBufRow,
        pPredBufCol,
        curQP,
        predQP,
        predDir,
        ACPredFlag,
        videoComp,
        flag,
        NULL);

}

/* End of file */


