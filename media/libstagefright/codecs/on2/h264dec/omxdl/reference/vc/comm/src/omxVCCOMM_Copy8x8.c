/**
 * 
 * File Name:  omxVCCOMM_Copy8x8.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 * Description:
 * MPEG4 8x8 Copy module
 * 
 */
 
#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armCOMM.h"

/**
 * Function:  omxVCCOMM_Copy8x8   (6.1.3.3.1)
 *
 * Description:
 * Copies the reference 8x8 block to the current block. 
 *
 * Input Arguments:
 *   
 *   pSrc - pointer to the reference block in the source frame; must be 
 *            aligned on an 8-byte boundary. 
 *   step - distance between the starts of consecutive lines in the reference 
 *            frame, in bytes; must be a multiple of 8 and must be larger than 
 *            or equal to 8. 
 *
 * Output Arguments:
 *   
 *   pDst - pointer to the destination block; must be aligned on an 8-byte 
 *            boundary. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments; returned under any of the following 
 *              conditions: 
 *    -   one or more of the following pointers is NULL: pSrc, pDst 
 *    -   one or more of the following pointers is not aligned on an 8-byte 
 *              boundary: pSrc, pDst 
 *    -    step <8 or step is not a multiple of 8. 
 *
 */

OMXResult omxVCCOMM_Copy8x8(
		const OMX_U8 *pSrc, 
		OMX_U8 *pDst, 
		OMX_INT step)
 {
    /* Definitions and Initializations*/

    OMX_INT count,index, x, y;
    
    /* Argument error checks */
    armRetArgErrIf(pSrc == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pDst == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(!armIs8ByteAligned(pSrc), OMX_Sts_BadArgErr);
    armRetArgErrIf(!armIs8ByteAligned(pDst), OMX_Sts_BadArgErr);
    armRetArgErrIf(((step < 8) || (step % 8)), OMX_Sts_BadArgErr);
    
    
    /* Copying the ref 8x8 blk to the curr blk */
    for (y = 0, count = 0, index = 0; y < 8; y++, count = count + step - 8)
    {
        for (x = 0; x < 8; x++, count++, index++)
        {
            pDst[index] = pSrc[count];
        }       
    }
    return OMX_Sts_NoErr;
 }
