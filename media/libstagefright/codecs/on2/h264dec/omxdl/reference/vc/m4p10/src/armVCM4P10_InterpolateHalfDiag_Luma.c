/**
 * 
 * File Name:  armVCM4P10_InterpolateHalfDiag_Luma.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 * Description:
 * This functions will help to calculate Half Pel luma interpolation
 * 
 */

#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armCOMM.h"
#include "armVC.h"


/**
 * Function: armVCM4P10_InterpolateHalfDiag_Luma
 * 
 * Description:
 * This function performs interpolation for (1/2, 1/2)  positions 
 * around a full-pel position.
 *
 * Remarks:
 *
 *  [in]    pSrc        Pointer to top-left corner of block used to interpolate 
 *                      in the reconstructed frame plane
 *  [in]    iSrcStep    Step of the source buffer.
 *  [in]    iDstStep    Step of the destination(interpolation) buffer.
 *  [in]    iWidth      Width of the current block
 *  [in]    iHeight     Height of the current block
 *  [out]   pDst        Pointer to the interpolation buffer of the (1/2,1/2)-pel
 *
 * Return Value:
 * Standard OMXResult value.
 *
 */

OMXResult armVCM4P10_InterpolateHalfDiag_Luma(  
        const OMX_U8*     pSrc, 
        OMX_U32     iSrcStep, 
        OMX_U8*     pDst, 
        OMX_U32     iDstStep,
        OMX_U32     iWidth, 
        OMX_U32     iHeight
)
{
    OMX_S32     HalfCoeff, pos;
    OMX_S16     Buf [21 * 16];  /* 21 rows by 16 pixels per row */
    OMX_U32     y, x;

    /* check for argument error */
    armRetArgErrIf(pSrc == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf(pDst == NULL, OMX_Sts_BadArgErr)

    /*
     * Intermediate values will be 1/2 pel at Horizontal direction
     * Starting at (0.5, -2) at top extending to (0.5, height + 3) at bottom
     * Buf contains a 2D array of size (iWidth)X(iHeight + 5)
     */
    for (y = 0; y < iHeight + 5; y++)
    {
        for (x = 0; x < iWidth; x++)
        {
            pos = (y-2) * iSrcStep + x;
            HalfCoeff = 
                pSrc [pos - 2] - 
                5 * pSrc [pos - 1] + 
                20 * pSrc [pos] + 
                20 * pSrc [pos + 1] - 
                5 * pSrc [pos + 2] + 
                pSrc [pos + 3];
            Buf [y * iWidth + x] = (OMX_S16)HalfCoeff;
        } /* x */
    } /* y */

    /* Vertical interpolate */
    for (y = 0; y < iHeight; y++)
    {
        for (x = 0; x < iWidth; x++)
        {
            pos = y * iWidth + x;
            HalfCoeff = 
                Buf [pos] - 
                5 * Buf [pos + 1 * iWidth] + 
                20 * Buf [pos + 2 * iWidth] + 
                20 * Buf [pos + 3 * iWidth] - 
                5 * Buf [pos + 4 * iWidth] + 
                Buf [pos + 5 * iWidth];

            HalfCoeff = (HalfCoeff + 512) >> 10;
            HalfCoeff = armClip(0, 255, HalfCoeff);

            pDst [y * iDstStep + x] = (OMX_U8) HalfCoeff;
        }
    }
        
    return OMX_Sts_NoErr;
}
