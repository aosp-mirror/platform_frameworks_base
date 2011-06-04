/**
 * 
 * File Name:  omxVCM4P10_InterpolateChroma.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 * Description:
 * This function will calculate 1/8 Pixel interpolation for Chroma Block
 * 
 */

#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armVC.h"
#include "armCOMM.h"


/**
 * Function:  omxVCM4P10_InterpolateChroma   (6.3.3.2.2)
 *
 * Description:
 * Performs 1/8-pixel interpolation for inter chroma MB. 
 *
 * Input Arguments:
 *   
 *   pSrc -Pointer to the source reference frame buffer 
 *   srcStep -Reference frame step in bytes 
 *   dstStep -Destination frame step in bytes; must be a multiple of 
 *            roi.width. 
 *   dx -Fractional part of horizontal motion vector component in 1/8 pixel 
 *            unit; valid in the range [0,7] 
 *   dy -Fractional part of vertical motion vector component in 1/8 pixel 
 *            unit; valid in the range [0,7] 
 *   roi -Dimension of the interpolation region; the parameters roi.width and 
 *            roi.height must be equal to either 2, 4, or 8. 
 *
 * Output Arguments:
 *   
 *   pDst -Pointer to the destination frame buffer if roi.width==2,  2-byte 
 *            alignment required if roi.width==4,  4-byte alignment required 
 *            if roi.width==8, 8-byte alignment required 
 *
 * Return Value:
 *    If the function runs without error, it returns OMX_Sts_NoErr. 
 *    If one of the following cases occurs, the function returns 
 *              OMX_Sts_BadArgErr: 
 *    pSrc or pDst is NULL. 
 *    srcStep or dstStep < 8. 
 *    dx or dy is out of range [0-7]. 
 *    roi.width or roi.height is out of range {2,4,8}. 
 *    roi.width is equal to 2, but pDst is not 2-byte aligned. 
 *    roi.width is equal to 4, but pDst is not 4-byte aligned. 
 *    roi.width is equal to 8, but pDst is not 8 byte aligned. 
 *    srcStep or dstStep is not a multiple of 8. 
 *
 */

OMXResult omxVCM4P10_InterpolateChroma (
     const OMX_U8* pSrc,
     OMX_S32 srcStep,
     OMX_U8* pDst,
     OMX_S32 dstStep,
     OMX_S32 dx,
     OMX_S32 dy,
     OMXSize roi
 )
{
    /* check for argument error */
    armRetArgErrIf(pSrc == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf(pDst == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf(srcStep < 8, OMX_Sts_BadArgErr)
    armRetArgErrIf(dstStep < 8, OMX_Sts_BadArgErr)
    armRetArgErrIf(dx < 0, OMX_Sts_BadArgErr)
    armRetArgErrIf(dx > 7, OMX_Sts_BadArgErr)
    armRetArgErrIf(dy < 0, OMX_Sts_BadArgErr)
    armRetArgErrIf(dy > 7, OMX_Sts_BadArgErr)
    armRetArgErrIf((roi.width != 2) && (roi.width != 4) && (roi.width != 8), OMX_Sts_BadArgErr)
    armRetArgErrIf((roi.height != 2) && (roi.height != 4) && (roi.height != 8), OMX_Sts_BadArgErr)
    armRetArgErrIf((roi.width == 2) && armNot2ByteAligned(pDst), OMX_Sts_BadArgErr)
    armRetArgErrIf((roi.width == 4) && armNot4ByteAligned(pDst), OMX_Sts_BadArgErr)
    armRetArgErrIf((roi.width == 8) && armNot8ByteAligned(pDst), OMX_Sts_BadArgErr)
    armRetArgErrIf(srcStep & 7, OMX_Sts_BadArgErr)
    armRetArgErrIf(dstStep & 7, OMX_Sts_BadArgErr)

    return armVCM4P10_Interpolate_Chroma 
        ((OMX_U8*)pSrc, srcStep, pDst, dstStep, roi.width, roi.height, dx, dy);
}


/*****************************************************************************
 *                              END OF FILE
 *****************************************************************************/

