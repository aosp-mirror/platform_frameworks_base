/**
 * 
 * File Name:  omxVCCOMM_ComputeTextureErrorBlock.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 *
 * Description:
 * Contains module computing the error for a MB of size 8x8
 * 
 */

#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"
#include "armCOMM.h"

/**
 * Function:  omxVCCOMM_ComputeTextureErrorBlock   (6.1.4.1.2)
 *
 * Description:
 * Computes the texture error of the block. 
 *
 * Input Arguments:
 *   
 *   pSrc - pointer to the source plane. This should be aligned on an 8-byte 
 *            boundary. 
 *   srcStep - step of the source plane 
 *   pSrcRef - pointer to the reference buffer, an 8x8 block. This should be 
 *            aligned on an 8-byte boundary. 
 *
 * Output Arguments:
 *   
 *   pDst - pointer to the destination buffer, an 8x8 block. This should be 
 *            aligned on an 8-byte boundary. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments:
 *    -    At least one of the following pointers is NULL: 
 *         pSrc, pSrcRef, pDst. 
 *    -    pSrc is not 8-byte aligned. 
 *    -    SrcStep <= 0 or srcStep is not a multiple of 8. 
 *    -    pSrcRef is not 8-byte aligned. 
 *    -    pDst is not 8-byte aligned 
 *
 */

OMXResult omxVCCOMM_ComputeTextureErrorBlock(
     const OMX_U8 *pSrc,
     OMX_INT srcStep,
     const OMX_U8 *pSrcRef,
     OMX_S16 * pDst
)
{

    OMX_INT     x, y, count;

    /* Argument error checks */
    armRetArgErrIf(pSrc == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pSrcRef == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pDst == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(!armIs8ByteAligned(pSrc), OMX_Sts_BadArgErr);
    armRetArgErrIf(!armIs8ByteAligned(pSrcRef), OMX_Sts_BadArgErr);
    armRetArgErrIf(!armIs8ByteAligned(pDst), OMX_Sts_BadArgErr);
    armRetArgErrIf((srcStep <= 0) || (srcStep & 7), OMX_Sts_BadArgErr);

    /* Calculate the error block */
    for (y = 0, count = 0;
         y < 8;
         y++, pSrc += srcStep)
    {
        for (x = 0; x < 8; x++, count++)
        {
            pDst[count] = pSrc[x] - pSrcRef[count];
        }
    }

    return OMX_Sts_NoErr;

}

/* End of file */
