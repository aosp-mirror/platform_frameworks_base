/**
 * 
 * File Name:  omxVCCOMM_Copy16x16.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 * Description:
 * MPEG4 16x16 Copy module
 * 
 */
 
#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armCOMM.h"

/**
 * Function:  omxVCCOMM_Copy16x16   (6.1.3.3.2)
 *
 * Description:
 * Copies the reference 16x16 macroblock to the current macroblock. 
 *
 * Input Arguments:
 *   
 *   pSrc - pointer to the reference macroblock in the source frame; must be 
 *            aligned on a 16-byte boundary. 
 *   step - distance between the starts of consecutive lines in the reference 
 *            frame, in bytes; must be a multiple of 16 and must be larger 
 *            than or equal to 16. 
 *
 * Output Arguments:
 *   
 *   pDst - pointer to the destination macroblock; must be aligned on a 
 *            16-byte boundary. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments; returned under any of the following 
 *              conditions: 
 *    -   one or more of the following pointers is NULL: pSrc, pDst 
 *    -   one or more of the following pointers is not aligned on a 16-byte 
 *              boundary: pSrc, pDst 
 *    -    step <16 or step is not a multiple of 16. 
 *
 */

OMXResult omxVCCOMM_Copy16x16(
		const OMX_U8 *pSrc, 
		OMX_U8 *pDst, 
		OMX_INT step)
 {
    /* Definitions and Initializations*/

    OMX_INT count,index, x, y;
    
    /* Argument error checks */
    armRetArgErrIf(pSrc == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pDst == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(!armIs16ByteAligned(pSrc), OMX_Sts_BadArgErr);
    armRetArgErrIf(!armIs16ByteAligned(pDst), OMX_Sts_BadArgErr);
    armRetArgErrIf(((step < 16) || (step % 16)), OMX_Sts_BadArgErr);
    
    
    /* Copying the ref 16x16 blk to the curr blk */
    for (y = 0, count = 0, index = 0; y < 16; y++, count = count + step - 16)
    {
        for (x = 0; x < 16; x++, count++, index++)
        {
            pDst[index] = pSrc[count];
        }       
    }
    return OMX_Sts_NoErr;
 }
