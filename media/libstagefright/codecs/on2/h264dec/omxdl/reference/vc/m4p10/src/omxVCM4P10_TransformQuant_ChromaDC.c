/**
 * 
 * File Name:  omxVCM4P10_TransformQuant_ChromaDC.c
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
 * Function:  omxVCM4P10_TransformQuant_ChromaDC   (6.3.5.6.1)
 *
 * Description:
 * This function performs 2x2 Hadamard transform of chroma DC coefficients 
 * and then quantizes the coefficients. 
 *
 * Input Arguments:
 *   
 *   pSrcDst - Pointer to the 2x2 array of chroma DC coefficients.  8-byte 
 *            alignment required. 
 *   iQP - Quantization parameter; must be in the range [0,51]. 
 *   bIntra - Indicate whether this is an INTRA block. 1-INTRA, 0-INTER 
 *
 * Output Arguments:
 *   
 *   pSrcDst - Pointer to transformed and quantized coefficients.  8-byte 
 *            alignment required. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments; returned if any of the following 
 *              conditions are true: 
 *    -    at least one of the following pointers is NULL: 
 *             pSrcDst 
 *    -    pSrcDst is not aligned on an 8-byte boundary 
 *
 */
OMXResult omxVCM4P10_TransformQuant_ChromaDC(
	OMX_S16* 	pSrcDst,
	OMX_U32		iQP,
	OMX_U8		bIntra
)
{
    OMX_INT     i, j;
    OMX_S32     m[2][2];
    OMX_S32     Value;
    OMX_S32     QbitsPlusOne, Two_f, MF00;

    /* Check for argument error */
    armRetArgErrIf(pSrcDst == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(armNot8ByteAligned(pSrcDst), OMX_Sts_BadArgErr);
    armRetArgErrIf(iQP > 51, OMX_Sts_BadArgErr);

    /* Hadamard Transform for 2x2 block */
    m[0][0] = pSrcDst[0] + pSrcDst[1] +  pSrcDst[2] + pSrcDst[3];
    m[0][1] = pSrcDst[0] - pSrcDst[1] +  pSrcDst[2] - pSrcDst[3];
    m[1][0] = pSrcDst[0] + pSrcDst[1] -  pSrcDst[2] - pSrcDst[3];
    m[1][1] = pSrcDst[0] - pSrcDst[1] -  pSrcDst[2] + pSrcDst[3];

    /* Quantization */
    QbitsPlusOne = ARM_M4P10_Q_OFFSET + 1 + (iQP / 6); /*floor (QP/6)*/
    MF00 = armVCM4P10_MFMatrix [iQP % 6][0];

    Two_f = (1 << QbitsPlusOne) / (bIntra ? 3 : 6); /* 3->INTRA, 6->INTER */

    /* Scaling */
    for (j = 0; j < 2; j++)
    {
        for (i = 0; i < 2; i++)
        {
            Value = (armAbs(m[j][i]) * MF00 + Two_f) >> QbitsPlusOne;
            pSrcDst[j * 2 + i] = (OMX_S16)((m[j][i] < 0) ? -Value : Value);
        }
    }

    return OMX_Sts_NoErr;
}

/*****************************************************************************
 *                              END OF FILE
 *****************************************************************************/

