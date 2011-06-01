/**
 * 
 * File Name:  omxVCM4P10_TransformQuant_LumaDC.c
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
 * Function:  omxVCM4P10_TransformQuant_LumaDC   (6.3.5.6.2)
 *
 * Description:
 * This function performs a 4x4 Hadamard transform of luma DC coefficients 
 * and then quantizes the coefficients. 
 *
 * Input Arguments:
 *   
 *   pSrcDst - Pointer to the 4x4 array of luma DC coefficients.  16-byte 
 *            alignment required. 
 *   iQP - Quantization parameter; must be in the range [0,51]. 
 *
 * Output Arguments:
 *   
 *   pSrcDst - Pointer to transformed and quantized coefficients.  16-byte 
 *             alignment required. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments; returned if any of the following 
 *              conditions are true: 
 *    -    at least one of the following pointers is NULL: pSrcDst 
 *    -    pSrcDst is not aligned on an 16-byte boundary 
 *
 */
OMXResult omxVCM4P10_TransformQuant_LumaDC(
	OMX_S16* 	pSrcDst,
	OMX_U32		iQP
)
{
    OMX_INT     i, j;
    OMX_S32     m1[4][4], m2[4][4];
    OMX_S32     Value;
    OMX_U32     QbitsPlusOne, Two_f, MF;

    /* Check for argument error */
    armRetArgErrIf(pSrcDst == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(armNot16ByteAligned(pSrcDst), OMX_Sts_BadArgErr);
    armRetArgErrIf(iQP > 51, OMX_Sts_BadArgErr);

    /* Hadamard Transform for 4x4 block */
    /* Horizontal Hadamard */
    for (i = 0; i < 4; i++)
    {
        j = i * 4;
        
        m1[i][0] = pSrcDst[j + 0] + pSrcDst[j + 2]; /* a+c */
        m1[i][1] = pSrcDst[j + 1] + pSrcDst[j + 3]; /* b+d */
        m1[i][2] = pSrcDst[j + 0] - pSrcDst[j + 2]; /* a-c */
        m1[i][3] = pSrcDst[j + 1] - pSrcDst[j + 3]; /* b-d */

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

    
    /* Quantization */
    QbitsPlusOne = ARM_M4P10_Q_OFFSET + 1 + (iQP / 6); /*floor (QP/6)*/
    Two_f = (1 << QbitsPlusOne) / 3; /* 3->INTRA, 6->INTER */
    MF = armVCM4P10_MFMatrix [iQP % 6][0];

    /* Scaling */
    for (j = 0; j < 4; j++)
    {
        for (i = 0; i < 4; i++)
        {
            Value = (armAbs((m2[j][i]/* + 1*/) / 2) * MF + Two_f) >> QbitsPlusOne;
            pSrcDst[j * 4 + i] = (OMX_S16)((m2[j][i] < 0) ? -Value : Value);
        }
    }
    return OMX_Sts_NoErr;
}

/*****************************************************************************
 *                              END OF FILE
 *****************************************************************************/

