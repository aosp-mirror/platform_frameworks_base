/**
 * 
 * File Name:  omxVCM4P2_IDCT8x8blk.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 *
 * Description:
 * Contains modules for 8x8 block IDCT
 * 
 */


#include <math.h>
#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armCOMM.h"
#include "armVCM4P2_DCT_Table.h"

/**
 * Function:  omxVCM4P2_IDCT8x8blk   (6.2.3.2.1)
 *
 * Description:
 * Computes a 2D inverse DCT for a single 8x8 block, as defined in 
 * [ISO14496-2]. 
 *
 * Input Arguments:
 *   
 *   pSrc - pointer to the start of the linearly arranged IDCT input buffer; 
 *            must be aligned on a 16-byte boundary.  According to 
 *            [ISO14496-2], the input coefficient values should lie within the 
 *            range [-2048, 2047]. 
 *
 * Output Arguments:
 *   
 *   pDst - pointer to the start of the linearly arranged IDCT output buffer; 
 *            must be aligned on a 16-byte boundary. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments:
 *    -    pSrc or pDst is NULL. 
 *    -    pSrc or pDst is not 16-byte aligned. 
 *
 */
OMXResult omxVCM4P2_IDCT8x8blk (const OMX_S16 *pSrc, OMX_S16 *pDst)
{
    OMX_INT x, y, u, v;

    /* Argument error checks */
    armRetArgErrIf(pSrc == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(!armIs16ByteAligned(pSrc), OMX_Sts_BadArgErr);
    armRetArgErrIf(pDst == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(!armIs16ByteAligned(pDst), OMX_Sts_BadArgErr);

    for (x = 0; x < 8; x++)
    {
        for (y = 0; y < 8; y++)
        {
            OMX_F64 sum = 0.0;
            for (u = 0; u < 8; u++)
            {
                for (v = 0; v < 8; v++)
                {
                    sum += pSrc[(u * 8) + v] *
                        armVCM4P2_preCalcDCTCos[x][u] *
                        armVCM4P2_preCalcDCTCos[y][v];
                }
            }
            pDst[(x * 8) + y] = (OMX_S16) floor(sum + 0.5);

            /* Saturate to [-256, 255] */
            pDst[(x * 8) + y] = armClip (
                                            -256,
                                            255,
                                            pDst[(x * 8) + y]);
        }
    }

    return OMX_Sts_NoErr;
}

/* End of file */


