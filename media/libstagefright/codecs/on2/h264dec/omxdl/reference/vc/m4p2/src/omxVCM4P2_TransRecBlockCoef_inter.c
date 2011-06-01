/**
 * 
 * File Name:  omxVCM4P2_TransRecBlockCoef_inter.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 *
 * Description:
 * Contains modules DCT->quant and reconstructing the inter texture data
 * 
 */ 

#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armCOMM.h"


/**
 * Function:  omxVCM4P2_TransRecBlockCoef_inter   (6.2.4.4.5)
 *
 * Description:
 * Implements DCT, and quantizes the DCT coefficients of the inter block 
 * while reconstructing the texture residual. There is no boundary check for 
 * the bit stream buffer. 
 *
 * Input Arguments:
 *   
 *   pSrc -pointer to the residuals to be encoded; must be aligned on an 
 *            16-byte boundary. 
 *   QP - quantization parameter. 
 *   shortVideoHeader - binary flag indicating presence of short_video_header; 
 *                      shortVideoHeader==1 selects linear intra DC mode, and 
 *                      shortVideoHeader==0 selects non linear intra DC mode. 
 *
 * Output Arguments:
 *   
 *   pDst - pointer to the quantized DCT coefficients buffer; must be aligned 
 *            on a 16-byte boundary. 
 *   pRec - pointer to the reconstructed texture residuals; must be aligned 
 *            on a 16-byte boundary. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments:
 *    -    At least one of the following pointers is either NULL or 
 *         not 16-byte aligned: 
 *            - pSrc 
 *            - pDst
 *            - pRec
 *    -    QP <= 0 or QP >= 32. 
 *
 */

OMXResult omxVCM4P2_TransRecBlockCoef_inter(
     const OMX_S16 *pSrc,
     OMX_S16 * pDst,
     OMX_S16 * pRec,
     OMX_U8 QP,
     OMX_INT shortVideoHeader
)
{
    /* 64 elements are needed but to align it to 16 bytes need 
    8 more elements of padding */
    OMX_S16 tempBuffer[72];
    OMX_S16 *pTempBuffer;
    OMX_INT i;
        
    /* Aligning the local buffers */
    pTempBuffer = armAlignTo16Bytes(tempBuffer);

    /* Argument error checks */
    armRetArgErrIf(pSrc == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pRec == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pDst == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(!armIs16ByteAligned(pSrc), OMX_Sts_BadArgErr);
    armRetArgErrIf(!armIs16ByteAligned(pRec), OMX_Sts_BadArgErr);
    armRetArgErrIf(!armIs16ByteAligned(pDst), OMX_Sts_BadArgErr);
    armRetArgErrIf(((QP <= 0) || (QP >= 32)), OMX_Sts_BadArgErr);
    
    omxVCM4P2_DCT8x8blk (pSrc, pDst);
    omxVCM4P2_QuantInter_I(
     pDst,
     QP,
     shortVideoHeader);

    for (i = 0; i < 64; i++)
    {
        pTempBuffer[i] = pDst[i];
    }

    omxVCM4P2_QuantInvInter_I(
     pTempBuffer,
     QP);
    omxVCM4P2_IDCT8x8blk (pTempBuffer, pRec);

    return OMX_Sts_NoErr;
}

/* End of file */


