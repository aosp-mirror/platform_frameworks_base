/**
 * 
 * File Name:  omxVCM4P10_SATD_4x4.c
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

/**
 * Function:  omxVCM4P10_SATD_4x4   (6.3.5.4.5)
 *
 * Description:
 * This function calculates the sum of absolute transform differences (SATD) 
 * for a 4x4 block by applying a Hadamard transform to the difference block 
 * and then calculating the sum of absolute coefficient values. 
 *
 * Input Arguments:
 *   
 *   pSrcOrg - Pointer to the original block; must be aligned on a 4-byte 
 *            boundary 
 *   iStepOrg - Step of the original block buffer; must be a multiple of 4 
 *   pSrcRef - Pointer to the reference block; must be aligned on a 4-byte 
 *            boundary 
 *   iStepRef - Step of the reference block buffer; must be a multiple of 4 
 *
 * Output Arguments:
 *   
 *   pDstSAD - pointer to the resulting SAD 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments; returned if any of the following 
 *              conditions are true: 
 *    -    at least one of the following pointers is NULL: 
 *         pSrcOrg, pSrcRef, or pDstSAD either pSrcOrg 
 *    -    pSrcRef is not aligned on a 4-byte boundary 
 *    -    iStepOrg <= 0 or iStepOrg is not a multiple of 4 
 *    -    iStepRef <= 0 or iStepRef is not a multiple of 4 
 *
 */
OMXResult omxVCM4P10_SATD_4x4( 
	const OMX_U8*		pSrcOrg,
	OMX_U32     iStepOrg,                         
	const OMX_U8*		pSrcRef,
	OMX_U32		iStepRef,
	OMX_U32*    pDstSAD
)
{
    OMX_INT     i, j;
    OMX_S32     SATD = 0;
    OMX_S32     d [4][4], m1[4][4], m2[4][4];

    /* check for argument error */
    armRetArgErrIf(pSrcOrg == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf(pSrcRef == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf(pDstSAD == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf((iStepOrg == 0) || (iStepOrg & 3), OMX_Sts_BadArgErr)
    armRetArgErrIf((iStepRef == 0) || (iStepRef & 3), OMX_Sts_BadArgErr)
    armRetArgErrIf(armNot4ByteAligned(pSrcOrg), OMX_Sts_BadArgErr)
    armRetArgErrIf(armNot4ByteAligned(pSrcRef), OMX_Sts_BadArgErr)

    /* Calculate the difference */
    for (j = 0; j < 4; j++)
    {
        for (i = 0; i < 4; i++)
        {
            d [j][i] = pSrcOrg [j * iStepOrg + i] - pSrcRef [j * iStepRef + i];
        }
    }

    /* Hadamard Transfor for 4x4 block */

    /* Horizontal */
    for (i = 0; i < 4; i++)
    {
        m1[i][0] = d[i][0] + d[i][2]; /* a+c */
        m1[i][1] = d[i][1] + d[i][3]; /* b+d */
        m1[i][2] = d[i][0] - d[i][2]; /* a-c */
        m1[i][3] = d[i][1] - d[i][3]; /* b-d */

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
    
    /* calculate SAD for Transformed coefficients */
    for (j = 0; j < 4; j++)
    {
        for (i = 0; i < 4; i++)
        {
            SATD += armAbs(m2 [j][i]);
        }
    }
        
    *pDstSAD = (SATD + 1) / 2;

    return OMX_Sts_NoErr;
}

/*****************************************************************************
 *                              END OF FILE
 *****************************************************************************/

