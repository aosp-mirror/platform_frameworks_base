/**
 * 
 * File Name:  omxVCM4P10_InvTransformDequant_LumaDC.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 * Description:
 * This function will calculate 4x4 hadamard transform of luma DC coefficients 
 * and quantization
 * 
 */

#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armCOMM.h"
#include "armVC.h"

/**
 * Function:  omxVCM4P10_InvTransformDequant_LumaDC   (6.3.5.6.3)
 *
 * Description:
 * This function performs inverse 4x4 Hadamard transform and then dequantizes 
 * the coefficients. 
 *
 * Input Arguments:
 *   
 *   pSrc - Pointer to the 4x4 array of the 4x4 Hadamard-transformed and 
 *            quantized coefficients.  16 byte alignment required. 
 *   iQP - Quantization parameter; must be in the range [0,51]. 
 *
 * Output Arguments:
 *   
 *   pDst - Pointer to inverse-transformed and dequantized coefficients.  
 *            16-byte alignment required. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments; returned if any of the following 
 *              conditions are true: 
 *    -    at least one of the following pointers is NULL: pSrc 
 *    -    pSrc or pDst is not aligned on a 16-byte boundary 
 *
 */
OMXResult omxVCM4P10_InvTransformDequant_LumaDC(	
	const OMX_S16* 	pSrc,
	OMX_S16*	pDst,
	OMX_U32		iQP
)
{
    OMX_INT     i, j;
    OMX_S32     m1[4][4], m2[4][4], Value;
    OMX_S32     QPer, V;

    /* check for argument error */
    armRetArgErrIf(pSrc == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf(pDst == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf(iQP > 51, OMX_Sts_BadArgErr)
    armRetArgErrIf(armNot16ByteAligned(pSrc), OMX_Sts_BadArgErr)
    armRetArgErrIf(armNot16ByteAligned(pDst), OMX_Sts_BadArgErr)

    /* Inv Hadamard Transform for DC Luma 4x4 block */
    /* Horizontal */
    for (i = 0; i < 4; i++)
    {
        j = i * 4;
        
        m1[i][0] = pSrc[j + 0] + pSrc[j + 2]; /* a+c */
        m1[i][1] = pSrc[j + 1] + pSrc[j + 3]; /* b+d */
        m1[i][2] = pSrc[j + 0] - pSrc[j + 2]; /* a-c */
        m1[i][3] = pSrc[j + 1] - pSrc[j + 3]; /* b-d */

        m2[i][0] = m1[i][0] + m1[i][1]; /* a+b+c+d */
        m2[i][1] = m1[i][2] + m1[i][3]; /* a+b-c-d */
        m2[i][2] = m1[i][2] - m1[i][3]; /* a-b-c+d */
        m2[i][3] = m1[i][0] - m1[i][1]; /* a-b+c-d */

    }

    /* Vertical */
    for (i = 0; i < 4; i++)
    {
        m1[0][i] = m2[0][i] + m2[2][i];
        m1[1][i] = m2[1][i] + m2[3][i];
        m1[2][i] = m2[0][i] - m2[2][i];
        m1[3][i] = m2[1][i] - m2[3][i];

        m2[0][i] = m1[0][i] + m1[1][i];
        m2[1][i] = m1[2][i] + m1[3][i];
        m2[2][i] = m1[2][i] - m1[3][i];
        m2[3][i] = m1[0][i] - m1[1][i];
    }

    
    /* Scaling */
    QPer = iQP / 6;
    V = armVCM4P10_VMatrix [iQP % 6][0];

    for (j = 0; j < 4; j++)
    {
        for (i = 0; i < 4; i++)
        {
            if (QPer < 2)
            {
                Value = (m2[j][i] * V + (1 << (1 - QPer))) >> (2 - QPer);
            }
            else
            {
                Value = m2[j][i] * V * (1 << (QPer - 2));
            }
                        
            pDst[j * 4 + i] = (OMX_S16) Value;
            
        }
    }
    return OMX_Sts_NoErr;
}

/*****************************************************************************
 *                              END OF FILE
 *****************************************************************************/

