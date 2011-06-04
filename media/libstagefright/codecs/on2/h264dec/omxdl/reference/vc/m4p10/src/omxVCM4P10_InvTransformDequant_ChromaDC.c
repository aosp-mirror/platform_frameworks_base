/**
 * 
 * File Name:  omxVCM4P10_InvTransformDequant_ChromaDC.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 * Description:
 * This function will calculate 4x4 hadamard transform of chroma DC  
 * coefficients and quantization
 * 
 */

#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armVC.h"
#include "armCOMM.h"

/**
 * Function:  omxVCM4P10_InvTransformDequant_ChromaDC   (6.3.5.6.4)
 *
 * Description:
 * This function performs inverse 2x2 Hadamard transform and then dequantizes 
 * the coefficients. 
 *
 * Input Arguments:
 *   
 *   pSrc - Pointer to the 2x2 array of the 2x2 Hadamard-transformed and 
 *            quantized coefficients.  8 byte alignment required. 
 *   iQP - Quantization parameter; must be in the range [0,51]. 
 *
 * Output Arguments:
 *   
 *   pDst - Pointer to inverse-transformed and dequantized coefficients.  
 *            8-byte alignment required. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments; returned if any of the following 
 *              conditions are true: 
 *    -    at least one of the following pointers is NULL: pSrc 
 *    -    pSrc or pDst is not aligned on an 8-byte boundary 
 *
 */
OMXResult omxVCM4P10_InvTransformDequant_ChromaDC(
	const OMX_S16* 	pSrc,
	OMX_S16*	pDst,
	OMX_U32		iQP
)
{
    OMX_INT     i, j;
    OMX_S32     m[2][2];
    OMX_S32     QPer, V00, Value;

    /* check for argument error */
    armRetArgErrIf(pSrc == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf(armNot8ByteAligned(pSrc), OMX_Sts_BadArgErr);
    armRetArgErrIf(pDst == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf(armNot8ByteAligned(pDst), OMX_Sts_BadArgErr);
    armRetArgErrIf(iQP > 51, OMX_Sts_BadArgErr)

    /* Inv Hadamard Transform for 2x2 block */
    m[0][0] = pSrc[0] + pSrc[1] +  pSrc[2] + pSrc[3];
    m[0][1] = pSrc[0] - pSrc[1] +  pSrc[2] - pSrc[3];
    m[1][0] = pSrc[0] + pSrc[1] -  pSrc[2] - pSrc[3];
    m[1][1] = pSrc[0] - pSrc[1] -  pSrc[2] + pSrc[3];

    /* Quantization */
    /* Scaling */
    QPer = iQP / 6;
    V00 = armVCM4P10_VMatrix [iQP % 6][0];

    for (j = 0; j < 2; j++)
    {
        for (i = 0; i < 2; i++)
        {
            if (QPer < 1)
            {
                Value = (m[j][i] * V00) >> 1;
            }
            else
            {
                Value = (m[j][i] * V00) << (QPer - 1);
            }

            pDst[j * 2 + i] = (OMX_S16) Value;
        }
    }

    return OMX_Sts_NoErr;
}

/*****************************************************************************
 *                              END OF FILE
 *****************************************************************************/

