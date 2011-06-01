/**
 * 
 * File Name:  omxVCM4P10_SubAndTransformQDQResidual.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 * Description:
 * This function will calculate SAD for 4x4 blocks
 * 
 */

#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armCOMM.h"
#include "armVC.h"

/**
 * Function:  omxVCM4P10_SubAndTransformQDQResidual   (6.3.5.8.1)
 *
 * Description:
 * This function subtracts the prediction signal from the original signal to 
 * produce the difference signal and then performs a 4x4 integer transform and 
 * quantization. The quantized transformed coefficients are stored as 
 * pDstQuantCoeff. This function can also output dequantized coefficients or 
 * unquantized DC coefficients optionally by setting the pointers 
 * pDstDeQuantCoeff, pDCCoeff. 
 *
 * Input Arguments:
 *   
 *   pSrcOrg - Pointer to original signal. 4-byte alignment required. 
 *   pSrcPred - Pointer to prediction signal. 4-byte alignment required. 
 *   iSrcOrgStep - Step of the original signal buffer; must be a multiple of 
 *            4. 
 *   iSrcPredStep - Step of the prediction signal buffer; must be a multiple 
 *            of 4. 
 *   pNumCoeff -Number of non-zero coefficients after quantization. If this 
 *            parameter is not required, it is set to NULL. 
 *   nThreshSAD - Zero-block early detection threshold. If this parameter is 
 *            not required, it is set to 0. 
 *   iQP - Quantization parameter; must be in the range [0,51]. 
 *   bIntra - Indicates whether this is an INTRA block, either 1-INTRA or 
 *            0-INTER 
 *
 * Output Arguments:
 *   
 *   pDstQuantCoeff - Pointer to the quantized transformed coefficients.  
 *            8-byte alignment required. 
 *   pDstDeQuantCoeff - Pointer to the dequantized transformed coefficients 
 *            if this parameter is not equal to NULL.  8-byte alignment 
 *            required. 
 *   pDCCoeff - Pointer to the unquantized DC coefficient if this parameter 
 *            is not equal to NULL. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments; returned if any of the following 
 *              conditions are true: 
 *    -    at least one of the following pointers is NULL: 
 *            pSrcOrg, pSrcPred, pNumCoeff, pDstQuantCoeff, 
 *            pDstDeQuantCoeff, pDCCoeff 
 *    -    pSrcOrg is not aligned on a 4-byte boundary 
 *    -    pSrcPred is not aligned on a 4-byte boundary 
 *    -    iSrcOrgStep is not a multiple of 4 
 *    -    iSrcPredStep is not a multiple of 4 
 *    -    pDstQuantCoeff or pDstDeQuantCoeff is not aligned on an 8-byte boundary 
 *
 */
 OMXResult omxVCM4P10_SubAndTransformQDQResidual (
	 const OMX_U8*		pSrcOrg,
	 const OMX_U8*		pSrcPred,
	 OMX_U32		iSrcOrgStep,
	 OMX_U32		iSrcPredStep,
	 OMX_S16*	    pDstQuantCoeff,
	 OMX_S16* 	    pDstDeQuantCoeff,
	 OMX_S16*	    pDCCoeff,
	 OMX_S8*		pNumCoeff,
	 OMX_U32		nThreshSAD,
	 OMX_U32		iQP,
	 OMX_U8		    bIntra
)
{
    OMX_INT     i, j;
    OMX_S8      NumCoeff = 0;
    OMX_S16     Buf[16], m[16];
    OMX_U32     QBits, QPper, QPmod, f;
    OMX_S32     Value, MF, ThreshDC;

    /* check for argument error */
    armRetArgErrIf(pSrcOrg == NULL, OMX_Sts_BadArgErr)
	armRetArgErrIf(pDstDeQuantCoeff == NULL, OMX_Sts_BadArgErr)
	armRetArgErrIf(pNumCoeff == NULL, OMX_Sts_BadArgErr)
	armRetArgErrIf(pDCCoeff == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf(armNot4ByteAligned(pSrcOrg), OMX_Sts_BadArgErr)
    armRetArgErrIf(pSrcPred == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf(armNot4ByteAligned(pSrcPred), OMX_Sts_BadArgErr)
    armRetArgErrIf(pDstQuantCoeff == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf(armNot8ByteAligned(pDstQuantCoeff), OMX_Sts_BadArgErr)
    armRetArgErrIf((pDstDeQuantCoeff != NULL) && 
			armNot8ByteAligned(pDstDeQuantCoeff), OMX_Sts_BadArgErr)
    armRetArgErrIf((bIntra != 0) && (bIntra != 1), OMX_Sts_BadArgErr)
    armRetArgErrIf(iQP > 51, OMX_Sts_BadArgErr)
    armRetArgErrIf(iSrcOrgStep == 0, OMX_Sts_BadArgErr)
    armRetArgErrIf(iSrcPredStep == 0, OMX_Sts_BadArgErr)
    armRetArgErrIf(iSrcOrgStep & 3, OMX_Sts_BadArgErr)
    armRetArgErrIf(iSrcPredStep & 3, OMX_Sts_BadArgErr)

    /* 
     * Zero-Block Early detection using nThreshSAD param 
     */

    QPper = iQP / 6;
    QPmod = iQP % 6;    
    QBits = 15 + QPper;
    
    f = (1 << QBits) / (bIntra ? 3 : 6);
    
    /* Do Zero-Block Early detection if enabled */
    if (nThreshSAD)
    {
        ThreshDC = ((1 << QBits) - f) / armVCM4P10_MFMatrix[QPmod][0];
        if (nThreshSAD < ThreshDC)
        {
            /* Set block to zero */
            if (pDCCoeff != NULL)
            {
                *pDCCoeff = 0;
            }

            for (j = 0; j < 4; j++)
            {
                for (i = 0; i < 4; i++)
                {
                    pDstQuantCoeff [4 * j + i] = 0;
                    if (pDstDeQuantCoeff != NULL)
                    {
                        pDstDeQuantCoeff [4 * j + i] = 0;    
                    }                    
                }
            }

            if (pNumCoeff != NULL)
            {
                *pNumCoeff = 0;
            }
            return OMX_Sts_NoErr;
        }
    }


   /* Calculate difference */
    for (j = 0; j < 4; j++)
    {
        for (i = 0; i < 4; i++)
        {
            Buf [j * 4 + i] = 
                pSrcOrg [j * iSrcOrgStep + i] - pSrcPred [j * iSrcPredStep + i];
        }
    }

    /* Residual Transform */
    armVCM4P10_FwdTransformResidual4x4 (m, Buf);

    if (pDCCoeff != NULL)
    {
        /* Copy unquantized DC value into pointer */
        *pDCCoeff = m[0];
    }

    /* Quantization */
    for (j = 0; j < 4; j++)
    {
        for (i = 0; i < 4; i++)
        {
            MF = armVCM4P10_MFMatrix[QPmod][armVCM4P10_PosToVCol4x4[j * 4 + i]];
            Value = armAbs(m[j * 4 + i]) * MF + f;
            Value >>= QBits;
            Value = m[j * 4 + i] < 0 ? -Value : Value;
            Buf[4 * j + i] = pDstQuantCoeff [4 * j + i] = (OMX_S16)Value;
            if ((pNumCoeff != NULL) && Value)
            {
                NumCoeff++;
            }
        }
    }

    /* Output number of non-zero Coeffs */
    if (pNumCoeff != NULL)
    {
        *pNumCoeff = NumCoeff;
    }
    
    /* Residual Inv Transform */
    if (pDstDeQuantCoeff != NULL)
    {    
        /* Re Scale */
        for (j = 0; j < 4; j++)
        {
            for (i = 0; i < 4; i++)
            {
                m [j * 4 + i]  = Buf [j * 4 + i] * (1 << QPper) *
                    armVCM4P10_VMatrix[QPmod][armVCM4P10_PosToVCol4x4[j * 4 + i]];
            }
        }
        armVCM4P10_TransformResidual4x4 (pDstDeQuantCoeff, m);        
    }
        
    return OMX_Sts_NoErr;
}

/*****************************************************************************
 *                              END OF FILE
 *****************************************************************************/

