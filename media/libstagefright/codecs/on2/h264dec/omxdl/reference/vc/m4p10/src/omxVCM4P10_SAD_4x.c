/**
 * 
 * File Name:  omxVCM4P10_SAD_4x.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 * Description:
 * This function will calculate SAD for 4x8 and 4x4 blocks
 * 
 */

#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armVC.h"
#include "armCOMM.h"

/**
 * Function:  omxVCM4P10_SAD_4x   (6.3.5.4.1)
 *
 * Description:
 * This function calculates the SAD for 4x8 and 4x4 blocks. 
 *
 * Input Arguments:
 *   
 *   pSrcOrg -Pointer to the original block; must be aligned on a 4-byte 
 *            boundary. 
 *   iStepOrg -Step of the original block buffer; must be a multiple of 4. 
 *   pSrcRef -Pointer to the reference block 
 *   iStepRef -Step of the reference block buffer 
 *   iHeight -Height of the block; must be equal to either 4 or 8. 
 *
 * Output Arguments:
 *   
 *   pDstSAD -Pointer of result SAD 
 *
 * Return Value:
 *    OMX_Sts_NoErr, if the function runs without error.
 *    OMX_Sts_BadArgErr - bad arguments: if one of the following cases occurs: 
 *    -    One of more of the following pointers is NULL: 
 *         pSrcOrg, pSrcRef, or pDstSAD 
 *    -    iHeight is not equal to either 4 or 8. 
 *    -    iStepOrg is not a multiple of 4 
 *    -    Any alignment restrictions are violated 
 *
 */
OMXResult omxVCM4P10_SAD_4x(	
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
    armRetArgErrIf((iHeight != 8) && (iHeight != 4), OMX_Sts_BadArgErr)
    armRetArgErrIf(armNot4ByteAligned(pSrcOrg), OMX_Sts_BadArgErr)
    armRetArgErrIf((iStepOrg == 0) || (iStepOrg & 3), OMX_Sts_BadArgErr)
    armRetArgErrIf((iStepRef == 0) || (iStepRef & 3), OMX_Sts_BadArgErr)

    return armVCCOMM_SAD 
        (pSrcOrg, iStepOrg, pSrcRef, iStepRef, pDstSAD, iHeight, 4);
}

/*****************************************************************************
 *                              END OF FILE
 *****************************************************************************/

