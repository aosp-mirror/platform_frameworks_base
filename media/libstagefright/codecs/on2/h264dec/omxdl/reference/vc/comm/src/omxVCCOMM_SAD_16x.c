/**
 * 
 * File Name:  omxVCCOMM_SAD_16x.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 * Description:
 * This function will calculate SAD for 16x16 and 16x8 blocks
 * 
 */

#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armVC.h"
#include "armCOMM.h"

/**
 * Function:  omxVCCOMM_SAD_16x   (6.1.4.1.4)
 *
 * Description:
 * This function calculates the SAD for 16x16 and 16x8 blocks. 
 *
 * Input Arguments:
 *   
 *   pSrcOrg - Pointer to the original block; must be aligned on a 16-byte 
 *             boundary. 
 *   iStepOrg - Step of the original block buffer 
 *   pSrcRef  - Pointer to the reference block 
 *   iStepRef - Step of the reference block buffer 
 *   iHeight  - Height of the block 
 *
 * Output Arguments:
 *   
 *   pDstSAD - Pointer of result SAD 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments.  Returned if one or more of the 
 *              following conditions is true: 
 *    -    at least one of the following pointers is NULL: 
 *         pSrcOrg, pDstSAD, or pSrcRef 
 *    -    pSrcOrg is not 16-byte aligned. 
 *    -    iStepOrg  <= 0 or iStepOrg is not a multiple of 16 
 *    -    iStepRef <= 0 or iStepRef is not a multiple of 16 
 *    -    iHeight is not 8 or 16 
 *
 */
OMXResult omxVCCOMM_SAD_16x(
			const OMX_U8* 	pSrcOrg,
			OMX_U32 	iStepOrg,
			const OMX_U8* 	pSrcRef,
			OMX_U32 	iStepRef,
			OMX_S32*	pDstSAD,
			OMX_U32		iHeight
)
{
    /* check for argument error */
    armRetArgErrIf(pSrcOrg == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf(pSrcRef == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf(pDstSAD == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf((iHeight != 16) && (iHeight != 8), OMX_Sts_BadArgErr)
    armRetArgErrIf(armNot16ByteAligned(pSrcOrg), OMX_Sts_BadArgErr)
    armRetArgErrIf((iStepOrg == 0) || (iStepOrg & 15), OMX_Sts_BadArgErr)
    armRetArgErrIf((iStepRef == 0) || (iStepRef & 15), OMX_Sts_BadArgErr)

    return armVCCOMM_SAD 
        (pSrcOrg, iStepOrg, pSrcRef, iStepRef, pDstSAD, iHeight, 16);
}

/*****************************************************************************
 *                              END OF FILE
 *****************************************************************************/

