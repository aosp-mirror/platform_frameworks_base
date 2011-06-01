/**
 * 
 * File Name:  omxVCCOMM_ExpandFrame_I.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 * Description:
 * This function will Expand Frame boundary pixels into Plane
 * 
 */

#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armCOMM.h"

/**
 * Function:  omxVCCOMM_ExpandFrame_I   (6.1.3.2.1)
 *
 * Description:
 * This function expands a reconstructed frame in-place.  The unexpanded 
 * source frame should be stored in a plane buffer with sufficient space 
 * pre-allocated for edge expansion, and the input frame should be located in 
 * the plane buffer center.  This function executes the pixel expansion by 
 * replicating source frame edge pixel intensities in the empty pixel 
 * locations (expansion region) between the source frame edge and the plane 
 * buffer edge.  The width/height of the expansion regions on the 
 * horizontal/vertical edges is controlled by the parameter iExpandPels. 
 *
 * Input Arguments:
 *   
 *   pSrcDstPlane - pointer to the top-left corner of the frame to be 
 *            expanded; must be aligned on an 8-byte boundary. 
 *   iFrameWidth - frame width; must be a multiple of 8. 
 *   iFrameHeight -frame height; must be a multiple of 8. 
 *   iExpandPels - number of pixels to be expanded in the horizontal and 
 *            vertical directions; must be a multiple of 8. 
 *   iPlaneStep - distance, in bytes, between the start of consecutive lines 
 *            in the plane buffer; must be larger than or equal to 
 *            (iFrameWidth + 2 * iExpandPels). 
 *
 * Output Arguments:
 *   
 *   pSrcDstPlane -Pointer to the top-left corner of the frame (NOT the 
 *            top-left corner of the plane); must be aligned on an 8-byte 
 *            boundary. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments; returned under any of the following 
 *              conditions: 
 *    -    pSrcDstPlane is NULL. 
 *    -    pSrcDstPlane is not aligned on an 8-byte boundary. 
 *    -    one of the following parameters is either equal to zero or is a 
 *              non-multiple of 8: iFrameHeight, iFrameWidth, iPlaneStep, or 
 *              iExpandPels. 
 *    -    iPlaneStep < (iFrameWidth + 2 * iExpandPels). 
 *
 */
OMXResult omxVCCOMM_ExpandFrame_I(
	OMX_U8*	pSrcDstPlane, 
	OMX_U32	iFrameWidth, 
	OMX_U32	iFrameHeight, 
	OMX_U32	iExpandPels, 
	OMX_U32	iPlaneStep
)
{
    OMX_INT     x, y;
    OMX_U8*     pLeft;
    OMX_U8*     pRight;
    OMX_U8*     pTop;
    OMX_U8*     pBottom;

    /* check for argument error */
    armRetArgErrIf(pSrcDstPlane == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf(armNot8ByteAligned(pSrcDstPlane), OMX_Sts_BadArgErr)
    armRetArgErrIf(iFrameWidth == 0 || iFrameWidth & 7, OMX_Sts_BadArgErr)
    armRetArgErrIf(iFrameHeight== 0 || iFrameHeight & 7, OMX_Sts_BadArgErr)
    armRetArgErrIf(iExpandPels == 0 || iExpandPels & 7, OMX_Sts_BadArgErr)
    armRetArgErrIf(iPlaneStep == 0 || iPlaneStep & 7, OMX_Sts_BadArgErr)
    armRetArgErrIf(iPlaneStep < (iFrameWidth + 2 * iExpandPels), 
                   OMX_Sts_BadArgErr)

    /* Top and Bottom */
    pTop = pSrcDstPlane - (iExpandPels * iPlaneStep);
    pBottom = pSrcDstPlane + (iFrameHeight * iPlaneStep);

    for (y = 0; y < (OMX_INT)iExpandPels; y++)
    {
        for (x = 0; x < (OMX_INT)iFrameWidth; x++)
        {
            pTop [y * iPlaneStep + x] = 
                pSrcDstPlane [x];
            pBottom [y * iPlaneStep + x] = 
                pSrcDstPlane [(iFrameHeight - 1) * iPlaneStep + x];
        }
    }

    /* Left, Right and Corners */
    pLeft = pSrcDstPlane - iExpandPels;
    pRight = pSrcDstPlane + iFrameWidth;

    for (y = -(OMX_INT)iExpandPels; y < (OMX_INT)(iFrameHeight + iExpandPels); y++)
    {
        for (x = 0; x < (OMX_INT)iExpandPels; x++)
        {
            pLeft [y * iPlaneStep + x] = 
                pSrcDstPlane [y * iPlaneStep + 0];
            pRight [y * iPlaneStep + x] = 
                pSrcDstPlane [y * iPlaneStep + (iFrameWidth - 1)];
        }
    }

    return OMX_Sts_NoErr;
}

/*****************************************************************************
 *                              END OF FILE
 *****************************************************************************/

