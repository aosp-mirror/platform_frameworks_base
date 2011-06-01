/**
 * 
 * File Name:  omxVCM4P10_InterpolateLuma.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 * Description:
 * This function will calculate Performs quarter-pixel interpolation 
 * 
 */

#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armVC.h"
#include "armCOMM.h"

/**
 * Function:  omxVCM4P10_InterpolateLuma   (6.3.3.2.1)
 *
 * Description:
 * Performs quarter-pixel interpolation for inter luma MB. It is assumed that 
 * the frame is already padded when calling this function. 
 *
 * Input Arguments:
 *   
 *   pSrc -Pointer to the source reference frame buffer 
 *   srcStep -reference frame step, in bytes; must be a multiple of roi.width 
 *   dstStep -destination frame step, in bytes; must be a multiple of 
 *            roi.width 
 *   dx -Fractional part of horizontal motion vector component in 1/4 pixel 
 *            unit; valid in the range [0,3] 
 *   dy -Fractional part of vertical motion vector y component in 1/4 pixel 
 *            unit; valid in the range [0,3] 
 *   roi -Dimension of the interpolation region; the parameters roi.width and 
 *            roi.height must be equal to either 4, 8, or 16. 
 *
 * Output Arguments:
 *   
 *   pDst -Pointer to the destination frame buffer if roi.width==4,  4-byte 
 *            alignment required if roi.width==8,  8-byte alignment required 
 *            if roi.width==16, 16-byte alignment required 
 *
 * Return Value:
 *    If the function runs without error, it returns OMX_Sts_NoErr. 
 *    If one of the following cases occurs, the function returns 
 *              OMX_Sts_BadArgErr: 
 *    pSrc or pDst is NULL. 
 *    srcStep or dstStep < roi.width. 
 *    dx or dy is out of range [0,3]. 
 *    roi.width or roi.height is out of range {4, 8, 16}. 
 *    roi.width is equal to 4, but pDst is not 4 byte aligned. 
 *    roi.width is equal to 8 or 16, but pDst is not 8 byte aligned. 
 *    srcStep or dstStep is not a multiple of 8. 
 *
 */

OMXResult omxVCM4P10_InterpolateLuma (
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
    armRetArgErrIf(srcStep < roi.width, OMX_Sts_BadArgErr)
    armRetArgErrIf(dstStep < roi.width, OMX_Sts_BadArgErr)
    armRetArgErrIf(dx < 0, OMX_Sts_BadArgErr)
    armRetArgErrIf(dx > 3, OMX_Sts_BadArgErr)
    armRetArgErrIf(dy < 0, OMX_Sts_BadArgErr)
    armRetArgErrIf(dy > 3, OMX_Sts_BadArgErr)
    armRetArgErrIf((roi.width != 4) && (roi.width != 8) && (roi.width != 16), OMX_Sts_BadArgErr)
    armRetArgErrIf((roi.height != 4) && (roi.height != 8) && (roi.height != 16), OMX_Sts_BadArgErr)
    armRetArgErrIf((roi.width == 4) && armNot4ByteAligned(pDst), OMX_Sts_BadArgErr)
    armRetArgErrIf((roi.width == 8) && armNot8ByteAligned(pDst), OMX_Sts_BadArgErr)
    armRetArgErrIf((roi.width == 16) && armNot16ByteAligned(pDst), OMX_Sts_BadArgErr)
    armRetArgErrIf(srcStep & 7, OMX_Sts_BadArgErr)
    armRetArgErrIf(dstStep & 7, OMX_Sts_BadArgErr) 

    return armVCM4P10_Interpolate_Luma 
        (pSrc, srcStep, pDst, dstStep, roi.width, roi.height, dx, dy);

}


/*****************************************************************************
 *                              END OF FILE
 *****************************************************************************/

