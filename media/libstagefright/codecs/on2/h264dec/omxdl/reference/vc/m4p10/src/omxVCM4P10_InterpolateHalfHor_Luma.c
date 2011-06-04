/**
 * 
 * File Name:  omxVCM4P10_InterpolateHalfHor_Luma.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 * Description:
 * This function will calculate Half horizontal luma interpolation
 * 
 */

#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armCOMM.h"
#include "armVC.h"

/**
 * Function:  omxVCM4P10_InterpolateHalfHor_Luma   (6.3.5.5.1)
 *
 * Description:
 * This function performs interpolation for two horizontal 1/2-pel positions 
 * (-1/2,0) and (1/2, 0) - around a full-pel position. 
 *
 * Input Arguments:
 *   
 *   pSrc - Pointer to the top-left corner of the block used to interpolate in 
 *            the reconstruction frame plane. 
 *   iSrcStep - Step of the source buffer. 
 *   iDstStep - Step of the destination(interpolation) buffer; must be a 
 *            multiple of iWidth. 
 *   iWidth - Width of the current block; must be equal to either 4, 8, or 16 
 *   iHeight - Height of the current block; must be equal to 4, 8, or 16 
 *
 * Output Arguments:
 *   
 *   pDstLeft -Pointer to the interpolation buffer of the left -pel position 
 *            (-1/2, 0) 
 *                 If iWidth==4,  4-byte alignment required. 
 *                 If iWidth==8,  8-byte alignment required. 
 *                 If iWidth==16, 16-byte alignment required. 
 *   pDstRight -Pointer to the interpolation buffer of the right -pel 
 *            position (1/2, 0) 
 *                 If iWidth==4,  4-byte alignment required. 
 *                 If iWidth==8,  8-byte alignment required. 
 *                 If iWidth==16, 16-byte alignment required. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments; returned if any of the following 
 *              conditions are true: 
 *    -    at least one of the following pointers is NULL: 
 *             pSrc, pDstLeft, or pDstRight 
 *    -    iWidth or iHeight have values other than 4, 8, or 16 
 *    -    iWidth==4 but pDstLeft and/or pDstRight is/are not aligned on a 4-byte boundary 
 *    -    iWidth==8 but pDstLeft and/or pDstRight is/are not aligned on a 8-byte boundary 
 *    -    iWidth==16 but pDstLeft and/or pDstRight is/are not aligned on a 16-byte boundary 
 *    -    any alignment restrictions are violated 
 *
 */

OMXResult omxVCM4P10_InterpolateHalfHor_Luma(
        const OMX_U8*     pSrc, 
        OMX_U32     iSrcStep, 
        OMX_U8*     pDstLeft, 
        OMX_U8*     pDstRight, 
        OMX_U32     iDstStep, 
        OMX_U32     iWidth, 
        OMX_U32     iHeight
)
{
    OMXResult   RetValue;    

    /* check for argument error */
    armRetArgErrIf(pSrc == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf(pDstLeft == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf(pDstRight == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf((iWidth == 4) && 
                   armNot4ByteAligned(pDstLeft) &&
                   armNot4ByteAligned(pDstRight), OMX_Sts_BadArgErr)
    armRetArgErrIf((iWidth == 8) && 
                   armNot8ByteAligned(pDstLeft) &&
                   armNot8ByteAligned(pDstRight), OMX_Sts_BadArgErr)
    armRetArgErrIf((iWidth == 16) && 
                   armNot16ByteAligned(pDstLeft) &&
                   armNot16ByteAligned(pDstRight), OMX_Sts_BadArgErr)

    armRetArgErrIf((iHeight != 16) && (iHeight != 8)&& (iHeight != 4), OMX_Sts_BadArgErr)
	armRetArgErrIf((iWidth != 16) && (iWidth != 8)&& (iWidth != 4), OMX_Sts_BadArgErr)

    RetValue = armVCM4P10_InterpolateHalfHor_Luma (
        pSrc - 1,     
        iSrcStep, 
        pDstLeft,     
        iDstStep, 
        iWidth,   
        iHeight);

    if (RetValue != OMX_Sts_NoErr)
    {
        return RetValue;
    }

    RetValue = armVCM4P10_InterpolateHalfHor_Luma (
        pSrc,     
        iSrcStep, 
        pDstRight,     
        iDstStep, 
        iWidth,   
        iHeight);

    return RetValue;
}

/*****************************************************************************
 *                              END OF FILE
 *****************************************************************************/

