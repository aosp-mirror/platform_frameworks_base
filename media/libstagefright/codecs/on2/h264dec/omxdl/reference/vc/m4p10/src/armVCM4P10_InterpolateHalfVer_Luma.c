/**
 * 
 * File Name:  armVCM4P10_InterpolateHalfVer_Luma.c
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
 * Function: armVCM4P10_InterpolateHalfVer_Luma
 * 
 * Description:
 * This function performs interpolation for vertical 1/2-pel positions 
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
 *  [out]   pDst        Pointer to the interpolation buffer of the 1/2-pel
 *
 * Return Value:
 * Standard OMXResult value.
 *
 */

OMXResult armVCM4P10_InterpolateHalfVer_Luma(   
     const OMX_U8*    pSrc, 
     OMX_U32    iSrcStep, 
     OMX_U8*    pDst,
     OMX_U32    iDstStep, 
     OMX_U32    iWidth, 
     OMX_U32    iHeight
)
{
    OMX_S32     HalfCoeff, pos;
    OMX_INT     y, x;

    /* check for argument error */
    armRetArgErrIf(pSrc == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf(pDst == NULL, OMX_Sts_BadArgErr)


    for (y = 0; y < iHeight; y++)
    {
        for (x = 0; x < iWidth; x++)
        {
            pos = y * iSrcStep + x;
            HalfCoeff = 
                pSrc [pos - 2 * iSrcStep] - 
                5 * pSrc [pos - 1 * iSrcStep] + 
                20 * pSrc [pos] + 
                20 * pSrc [pos + 1 * iSrcStep] - 
                5 * pSrc [pos + 2 * iSrcStep] + 
                pSrc [pos + 3 * iSrcStep];

            HalfCoeff = (HalfCoeff + 16) >> 5;
            HalfCoeff = armClip(0, 255, HalfCoeff);

            pDst [y * iDstStep + x] = (OMX_U8) HalfCoeff;
        }
    }
    
    return OMX_Sts_NoErr;
}

