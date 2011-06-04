/**
 * 
 * File Name:  omxVCM4P10_InterpolateHalfVer_Luma.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 * Description:
 * This function will calculate SAD for 4x4 blocks
 * 
 */

#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armCOMM.h"
#include "armVC.h"


/**
 * Function:  omxVCM4P10_InterpolateHalfVer_Luma   (6.3.5.5.2)
 *
 * Description:
 * This function performs interpolation for two vertical 1/2-pel positions - 
 * (0, -1/2) and (0, 1/2) - around a full-pel position. 
 *
 * Input Arguments:
 *   
 *   pSrc - Pointer to top-left corner of block used to interpolate in the 
 *            reconstructed frame plane 
 *   iSrcStep - Step of the source buffer. 
 *   iDstStep - Step of the destination (interpolation) buffer; must be a 
 *            multiple of iWidth. 
 *   iWidth - Width of the current block; must be equal to either 4, 8, or 16 
 *   iHeight - Height of the current block; must be equal to either 4, 8, or 16 
 *
 * Output Arguments:
 *   
 *   pDstUp -Pointer to the interpolation buffer of the -pel position above 
 *            the current full-pel position (0, -1/2) 
 *                If iWidth==4, 4-byte alignment required. 
 *                If iWidth==8, 8-byte alignment required. 
 *                If iWidth==16, 16-byte alignment required. 
 *   pDstDown -Pointer to the interpolation buffer of the -pel position below 
 *            the current full-pel position (0, 1/2) 
 *                If iWidth==4, 4-byte alignment required. 
 *                If iWidth==8, 8-byte alignment required. 
 *                If iWidth==16, 16-byte alignment required. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments; returned if any of the following 
 *              conditions are true: 
 *    -    at least one of the following pointers is NULL: 
 *            pSrc, pDstUp, or pDstDown 
 *    -    iWidth or iHeight have values other than 4, 8, or 16 
 *    -    iWidth==4 but pDstUp and/or pDstDown is/are not aligned on a 4-byte boundary 
 *    -    iWidth==8 but pDstUp and/or pDstDown is/are not aligned on a 8-byte boundary 
 *    -    iWidth==16 but pDstUp and/or pDstDown is/are not aligned on a 16-byte boundary 
 *
 */
 OMXResult omxVCM4P10_InterpolateHalfVer_Luma(  
     const OMX_U8*    pSrc, 
     OMX_U32    iSrcStep, 
     OMX_U8*    pDstUp, 
     OMX_U8*    pDstDown, 
     OMX_U32    iDstStep, 
     OMX_U32    iWidth, 
     OMX_U32    iHeight
)
{
    OMXResult   RetValue;

    /* check for argument error */
    armRetArgErrIf(pSrc == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf(pDstUp == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf(pDstDown == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf((iWidth == 4) && 
                   armNot4ByteAligned(pDstUp) &&
                   armNot4ByteAligned(pDstDown), OMX_Sts_BadArgErr)
    armRetArgErrIf((iWidth == 8) && 
                   armNot8ByteAligned(pDstUp) &&
                   armNot8ByteAligned(pDstDown), OMX_Sts_BadArgErr)
    armRetArgErrIf((iWidth == 16) && 
                   armNot16ByteAligned(pDstUp) &&
                   armNot16ByteAligned(pDstDown), OMX_Sts_BadArgErr)

    armRetArgErrIf((iHeight != 16) && (iHeight != 8)&& (iHeight != 4), OMX_Sts_BadArgErr)
	armRetArgErrIf((iWidth != 16) && (iWidth != 8)&& (iWidth != 4), OMX_Sts_BadArgErr)

    RetValue = armVCM4P10_InterpolateHalfVer_Luma(  
        pSrc - iSrcStep, 
        iSrcStep, 
        pDstUp,
        iDstStep, 
        iWidth, 
        iHeight);
    
    if (RetValue != OMX_Sts_NoErr)
    {
        return RetValue;
    }

    RetValue = armVCM4P10_InterpolateHalfVer_Luma(  
        pSrc, 
        iSrcStep, 
        pDstDown,
        iDstStep, 
        iWidth, 
        iHeight);
    
    return RetValue;
}

/*****************************************************************************
 *                              END OF FILE
 *****************************************************************************/

