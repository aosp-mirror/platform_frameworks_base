/* ----------------------------------------------------------------
 *
 * 
 * File Name:  omxVCM4P10_FilterDeblockingLuma_HorEdge_I.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 *
 * H.264 luma deblock module
 * 
 */
 
#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armCOMM.h"
#include "armVC.h"

/**
 * Function:  omxVCM4P10_FilterDeblockingLuma_HorEdge_I   (6.3.3.3.2)
 *
 * Description:
 * Performs in-place deblock filtering on four horizontal edges of the luma 
 * macroblock (16x16). 
 *
 * Input Arguments:
 *   
 *   pSrcDst - pointer to the input macroblock; must be 16-byte aligned. 
 *   srcdstStep -s tep of the arrays; must be a multiple of 16. 
 *   pAlpha - array of size 2 of alpha thresholds (the first item is the alpha 
 *            threshold for the external vertical edge, and the second item is 
 *            for the internal horizontal edge); per [ISO14496-10] alpha 
 *            values must be in the range [0,255]. 
 *   pBeta - array of size 2 of beta thresholds (the first item is the beta 
 *            threshold for the external horizontal edge, and the second item 
 *            is for the internal horizontal edge). Per [ISO14496-10] beta 
 *            values must be in the range [0,18]. 
 *   pThresholds - array of size 16 containing thresholds, TC0, for the top 
 *            horizontal edge of each 4x4 block, arranged in horizontal block 
 *            order; must be aligned on a 4-byte boundary.  Per [ISO14496 10] 
 *            values must be in the range [0,25]. 
 *   pBS - array of size 16 of BS parameters (arranged in horizontal block 
 *            order); valid in the range [0,4] with the following 
 *            restrictions: i) pBS[i]== 4 may occur only for 0<=i<=3, ii) 
 *            pBS[i]== 4 if and only if pBS[i^3]== 4.  Must be 4-byte aligned. 
 *
 * Output Arguments:
 *   
 *   pSrcDst -Pointer to filtered output macroblock. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr, if the function runs without error.
 * 
 *    OMX_Sts_BadArgErr, if one of the following cases occurs: 
 *    -    one or more of the following pointers is NULL: pSrcDst, pAlpha, 
 *              pBeta, pThresholds, or pBS. 
 *    -    either pThresholds or pBS is not aligned on a 4-byte boundary. 
 *    -    pSrcDst is not 16-byte aligned. 
 *    -    srcdstStep is not a multiple of 16. 
 *    -    pAlpha[0] and/or pAlpha[1] is outside the range [0,255]. 
 *    -    pBeta[0] and/or pBeta[1] is outside the range [0,18]. 
 *    -    One or more entries in the table pThresholds[0..15] is 
 *         outside of the range [0,25]. 
 *    -    pBS is out of range, i.e., one of the following conditions is true: 
 *              pBS[i]<0, pBS[i]>4, pBS[i]==4 for i>=4, or 
 *              (pBS[i]==4 && pBS[i^3]!=4) for 0<=i<=3. 
 *
 */

OMXResult omxVCM4P10_FilterDeblockingLuma_HorEdge_I(
     OMX_U8* pSrcDst,
     OMX_S32 srcdstStep,
     const OMX_U8* pAlpha,
     const OMX_U8* pBeta,
     const OMX_U8* pThresholds,
     const OMX_U8 *pBS        
 )
{
    int I, X, Y, Internal=0;

    armRetArgErrIf(pSrcDst == NULL,             OMX_Sts_BadArgErr);
    armRetArgErrIf(armNot8ByteAligned(pSrcDst), OMX_Sts_BadArgErr);
    armRetArgErrIf(srcdstStep & 7,              OMX_Sts_BadArgErr);    
    armRetArgErrIf(pAlpha == NULL,              OMX_Sts_BadArgErr);
    armRetArgErrIf(pBeta == NULL,               OMX_Sts_BadArgErr);
    armRetArgErrIf(pThresholds == NULL,         OMX_Sts_BadArgErr);
    armRetArgErrIf(armNot4ByteAligned(pThresholds), OMX_Sts_BadArgErr);
    armRetArgErrIf(pBS == NULL,                     OMX_Sts_BadArgErr);
    armRetArgErrIf(armNot4ByteAligned(pBS),         OMX_Sts_BadArgErr);
    
    for (Y=0; Y<16; Y+=4, Internal=1)
    {
        for (X=0; X<16; X++)
        {
            I = (X>>2)+4*(Y>>2);
            
            armRetArgErrIf(pBS[I] > 4, OMX_Sts_BadArgErr)
            
            armRetArgErrIf( (I > 3) && (pBS[I] == 4),
                            OMX_Sts_BadArgErr)
            
            armRetArgErrIf( (I < 4)       && 
                          ( (pBS[I] == 4) && (pBS[I^1] != 4) ),
                            OMX_Sts_BadArgErr)
            
            /* Filter horizontal edge with q0 at (X,Y) */
            armVCM4P10_DeBlockPixel(
                pSrcDst + Y*srcdstStep + X,
                srcdstStep,
                pThresholds[I],
                pAlpha[Internal],
                pBeta[Internal],
                pBS[I],
                0);
        }
    }

    return OMX_Sts_NoErr;
}
