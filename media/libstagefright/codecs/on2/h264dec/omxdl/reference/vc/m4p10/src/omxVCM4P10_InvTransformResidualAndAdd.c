/**
 * 
 * File Name:  omxVCM4P10_InvTransformResidualAndAdd.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 * Description:
 * This function will inverse integer 4x4 transform
 * 
 */

#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armCOMM.h"
#include "armVC.h"

/**
 * Function:  omxVCM4P10_InvTransformResidualAndAdd   (6.3.5.7.1)
 *
 * Description:
 * This function performs inverse an 4x4 integer transformation to produce 
 * the difference signal and then adds the difference to the prediction to get 
 * the reconstructed signal. 
 *
 * Input Arguments:
 *   
 *   pSrcPred - Pointer to prediction signal.  4-byte alignment required. 
 *   pDequantCoeff - Pointer to the transformed coefficients.  8-byte 
 *            alignment required. 
 *   iSrcPredStep - Step of the prediction buffer; must be a multiple of 4. 
 *   iDstReconStep - Step of the destination reconstruction buffer; must be a 
 *            multiple of 4. 
 *   bAC - Indicate whether there is AC coefficients in the coefficients 
 *            matrix. 
 *
 * Output Arguments:
 *   
 *   pDstRecon -Pointer to the destination reconstruction buffer.  4-byte 
 *            alignment required. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments; returned if any of the following 
 *              conditions are true: 
 *    -    at least one of the following pointers is NULL: 
 *            pSrcPred, pDequantCoeff, pDstRecon 
 *    -    pSrcPred is not aligned on a 4-byte boundary 
 *    -    iSrcPredStep or iDstReconStep is not a multiple of 4. 
 *    -    pDequantCoeff is not aligned on an 8-byte boundary 
 *
 */
OMXResult omxVCM4P10_InvTransformResidualAndAdd(
	const OMX_U8* 	pSrcPred, 
	const OMX_S16* 	pDequantCoeff, 
	OMX_U8* 	pDstRecon,
	OMX_U32 	iSrcPredStep, 
	OMX_U32		iDstReconStep, 
	OMX_U8		bAC
)
{
    OMX_INT     i, j;
    OMX_S16     In[16], Out[16];
    OMX_S32     Value;

    /* check for argument error */
    armRetArgErrIf(pSrcPred == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf(armNot4ByteAligned(pSrcPred), OMX_Sts_BadArgErr)
    armRetArgErrIf(pDequantCoeff == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf(armNot8ByteAligned(pDequantCoeff), OMX_Sts_BadArgErr)
    armRetArgErrIf(pDstRecon == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf(armNot4ByteAligned(pDstRecon), OMX_Sts_BadArgErr)
    armRetArgErrIf(bAC > 1, OMX_Sts_BadArgErr)
    armRetArgErrIf(iSrcPredStep == 0 || iSrcPredStep & 3, OMX_Sts_BadArgErr)
    armRetArgErrIf(iDstReconStep == 0 || iDstReconStep & 3, OMX_Sts_BadArgErr)

    if (bAC)
    {
        for (i = 0; i < 16; i++)
        {
            In[i] = pDequantCoeff [i];
        }
    }
    else
    {
        /* Copy DC */
        In[0] = pDequantCoeff [0];
    
        for (i = 1; i < 16; i++)
        {
            In[i] = 0;
        }
    }

    /* Residual Transform */
    armVCM4P10_TransformResidual4x4 (Out, In);    
    
    for (j = 0; j < 4; j++)
    {
        for (i = 0; i < 4; i++)
        {
            /* Add predition */
            Value = (OMX_S32) Out [j * 4 + i] + pSrcPred [j * iSrcPredStep + i];
            
            /* Saturate Value to OMX_U8 */
            Value = armClip (0, 255, Value);

            pDstRecon[j * iDstReconStep + i] = (OMX_U8) Value;
        }
    }

    return OMX_Sts_NoErr;
}

/*****************************************************************************
 *                              END OF FILE
 *****************************************************************************/

