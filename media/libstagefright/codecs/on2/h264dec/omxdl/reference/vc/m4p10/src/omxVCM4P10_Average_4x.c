/**
 * 
 * File Name:  omxVCM4P10_Average_4x.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 * Description:
 * This function will calculate Average of two 4x4 or 4x8 blocks
 * 
 */

#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armCOMM.h"
#include "armVC.h"

/**
 * Function:  omxVCM4P10_Average_4x   (6.3.5.5.3)
 *
 * Description:
 * This function calculates the average of two 4x4, 4x8 blocks.  The result 
 * is rounded according to (a+b+1)/2. 
 *
 * Input Arguments:
 *   
 *   pPred0 - Pointer to the top-left corner of reference block 0 
 *   pPred1 - Pointer to the top-left corner of reference block 1 
 *   iPredStep0 - Step of reference block 0; must be a multiple of 4. 
 *   iPredStep1 - Step of reference block 1; must be a multiple of 4. 
 *   iDstStep - Step of the destination buffer; must be a multiple of 4. 
 *   iHeight - Height of the blocks; must be either 4 or 8. 
 *
 * Output Arguments:
 *   
 *   pDstPred - Pointer to the destination buffer. 4-byte alignment required. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments; returned if any of the following 
 *              conditions are true: 
 *    -    at least one of the following pointers is NULL: 
 *           pPred0, pPred1, or pDstPred 
 *    -    pDstPred is not aligned on a 4-byte boundary 
 *    -    iPredStep0 <= 0 or iPredStep0 is not a multiple of 4 
 *    -    iPredStep1 <= 0 or iPredStep1 is not a multiple of 4 
 *    -    iDstStep <= 0 or iDstStep is not a multiple of 4 
 *    -    iHeight is not equal to either 4 or 8 
 *
 */
 OMXResult omxVCM4P10_Average_4x (
	 const OMX_U8* 	    pPred0,
	 const OMX_U8* 	    pPred1,	
	 OMX_U32		iPredStep0,
	 OMX_U32		iPredStep1,
	 OMX_U8*		pDstPred,
	 OMX_U32		iDstStep, 
	 OMX_U32		iHeight
)
{
    /* check for argument error */
    armRetArgErrIf(pPred0 == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf(pPred1 == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf(pDstPred == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf((iHeight != 4) && (iHeight != 8), OMX_Sts_BadArgErr)
    armRetArgErrIf((iPredStep0 == 0) || (iPredStep0 & 3), OMX_Sts_BadArgErr)
    armRetArgErrIf((iPredStep1 == 0) || (iPredStep1 & 3), OMX_Sts_BadArgErr)
    armRetArgErrIf((iDstStep == 0) || (iDstStep & 3), OMX_Sts_BadArgErr)
    armRetArgErrIf(armNot4ByteAligned(pDstPred), OMX_Sts_BadArgErr)

    return armVCCOMM_Average 
        (pPred0, pPred1, iPredStep0, iPredStep1, pDstPred, iDstStep, 4, iHeight);
}

/*****************************************************************************
 *                              END OF FILE
 *****************************************************************************/

