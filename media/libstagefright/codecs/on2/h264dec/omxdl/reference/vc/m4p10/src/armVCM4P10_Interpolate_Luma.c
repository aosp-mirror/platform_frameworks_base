/**
 * 
 * File Name:  armVCM4P10_Interpolate_Luma.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 * Description:
 * This function will calculate interpolation for luma components
 * 
 */

#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armCOMM.h"
#include "armVC.h"

/**
 * Function: armM4P10_Copy
 *
 * Description:
 * This function performs copy a block of data from source to destination
 *
 * Remarks:
 *
 *  [in]    pSrc            Pointer to top-left corner of block
 *  [in]    iSrcStep    Step of the source buffer.
 *  [in]    iDstStep    Step of the destination  buffer.
 *  [in]    iWidth      Width of the current block
 *  [in]    iHeight     Height of the current block
 *  [out]   pDst            Pointer to the interpolation buffer
 *
 * Return Value:
 * Standard OMXResult value.
 *
 */
static OMXResult armM4P10_Copy(  
    const OMX_U8*     pSrc,
    OMX_U32     iSrcStep,
    OMX_U8*     pDst,
    OMX_U32     iDstStep, 
    OMX_U32     iWidth,
    OMX_U32     iHeight
)
{
    OMX_U32     x, y;

    for (y = 0; y < iHeight; y++)
    {
        for (x = 0; x < iWidth; x++)
        {
            pDst [y * iDstStep + x] = pSrc [y * iSrcStep + x];
        }
    }

    return OMX_Sts_NoErr;
}

/**
 * Function: armVCM4P10_Interpolate_Luma
 *
 * Description:
 * This function performs interpolation for luma components.
 *
 * Remarks:
 *
 *  [in]    pSrc            Pointer to top-left corner of block used to 
 *                                              interpolate in the reconstructed frame plane
 *  [in]    iSrcStep    Step of the source buffer.
 *  [in]    iDstStep    Step of the destination(interpolation) buffer.
 *  [in]    iWidth      Width of the current block
 *  [in]    iHeight     Height of the current block
 *  [in]    dx              Fractional part of horizontal motion vector 
 *                                              component in 1/4 pixel unit (0~3) 
 *  [in]    dy              Fractional part of vertical motion vector 
 *                                              component in 1/4 pixel unit (0~3) 
 *  [out]   pDst            Pointer to the interpolation buffer
 *
 * Return Value:
 * Standard OMXResult value.
 *
 */

 OMXResult armVCM4P10_Interpolate_Luma(
     const OMX_U8     *pSrc,
     OMX_U32    iSrcStep,
     OMX_U8     *pDst,
     OMX_U32    iDstStep,
     OMX_U32    iWidth,
     OMX_U32    iHeight,
     OMX_U32    dx,
     OMX_U32    dy
)
{
    OMX_U8      pBuf1 [16*16];
    const OMX_U8      *pSrcHalfHor = pSrc;
    const OMX_U8      *pSrcHalfVer = pSrc;

    /* check for argument error */
    armRetArgErrIf(pSrc == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf(pDst == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf(dx > 3, OMX_Sts_BadArgErr)
    armRetArgErrIf(dy > 3, OMX_Sts_BadArgErr)

    /* Work out positions for half pixel interpolation */
    if (dx == 3)
    {
        pSrcHalfVer += 1;
    }
    if (dy == 3)
    {
        pSrcHalfHor += iSrcStep;
    }

    /* Switch on type of pixel
     * Pixels are named 'a' to 's' as in the H.264 standard
     */
    if (dx == 0 && dy == 0)
    {
        /* G */
        armM4P10_Copy(pSrc, iSrcStep, pDst, iDstStep, iWidth, iHeight);
    }
    else if (dy == 0)
    {
        /* a, b, c */
        armVCM4P10_InterpolateHalfHor_Luma
            (pSrcHalfHor, iSrcStep, pDst, iDstStep, iWidth, iHeight);            
        
        if (dx == 1 || dx == 3)
        {
            armVCCOMM_Average 
                (pDst, pSrcHalfVer, iDstStep, iSrcStep, pDst, iDstStep, iWidth, iHeight);
        }
    }
    else if (dx == 0)
    {
        /* d, h, n */
        armVCM4P10_InterpolateHalfVer_Luma
            (pSrcHalfVer, iSrcStep, pDst, iDstStep, iWidth, iHeight);

        if (dy == 1 || dy == 3)
        {
            armVCCOMM_Average 
                (pDst, pSrcHalfHor, iDstStep, iSrcStep, pDst, iDstStep, iWidth, iHeight);
        }
    }
    else if (dx == 2 || dy == 2)
    {
        /* j */
        armVCM4P10_InterpolateHalfDiag_Luma
            (pSrc, iSrcStep, pDst, iDstStep, iWidth, iHeight);

        if (dx == 1 || dx == 3)
        {
            /* i, k */
            armVCM4P10_InterpolateHalfVer_Luma
                (pSrcHalfVer, iSrcStep, pBuf1, iWidth, iWidth, iHeight);
                
            armVCCOMM_Average 
                (pDst, pBuf1, iDstStep, iWidth, pDst, iDstStep, iWidth, iHeight);
        }
        if (dy == 1 || dy == 3)
        {
            /* f,q */
            armVCM4P10_InterpolateHalfHor_Luma
                (pSrcHalfHor, iSrcStep, pBuf1, iWidth, iWidth, iHeight);

            armVCCOMM_Average 
                (pDst, pBuf1, iDstStep, iWidth, pDst, iDstStep, iWidth, iHeight);
        }
    }
    else /* dx=1,3 and dy=1,3 */
    {
        /* e, g, p, r */
        armVCM4P10_InterpolateHalfHor_Luma
            (pSrcHalfHor, iSrcStep, pBuf1, iWidth, iWidth, iHeight);

        armVCM4P10_InterpolateHalfVer_Luma
            (pSrcHalfVer, iSrcStep, pDst, iDstStep, iWidth, iHeight);

        armVCCOMM_Average 
            (pBuf1, pDst, iWidth, iDstStep, pDst, iDstStep, iWidth, iHeight);
    }

    return OMX_Sts_NoErr;
}

/*****************************************************************************
 *                              END OF FILE
 *****************************************************************************/
