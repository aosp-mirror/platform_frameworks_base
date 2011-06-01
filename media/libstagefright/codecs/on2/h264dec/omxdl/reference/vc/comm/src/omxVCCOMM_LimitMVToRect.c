/**
 * 
 * File Name:  omxVCCOMM_LimitMVToRect.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 *
 * Description:
 * Contains module for limiting the MV
 * 
 */
 
#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armCOMM.h"

/**
 * Function:  omxVCCOMM_LimitMVToRect   (6.1.4.1.3)
 *
 * Description:
 * Limits the motion vector associated with the current block/macroblock to 
 * prevent the motion compensated block/macroblock from moving outside a 
 * bounding rectangle as shown in Figure 6-1. 
 *
 * Input Arguments:
 *   
 *   pSrcMV - pointer to the motion vector associated with the current block 
 *            or macroblock 
 *   pRectVOPRef - pointer to the bounding rectangle 
 *   Xcoord, Ycoord  - coordinates of the current block or macroblock 
 *   size - size of the current block or macroblock; must be equal to 8 or 
 *            16. 
 *
 * Output Arguments:
 *   
 *   pDstMV - pointer to the limited motion vector 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments.  Returned if one or more of the 
 *              following conditions is true: 
 *    -    at least one of the following pointers is NULL: 
 *         pSrcMV, pDstMV, or pRectVOPRef. 
 *    -    size is not equal to either 8 or 16. 
 *    -    the width or height of the bounding rectangle is less than 
 *         twice the block size.
 */
OMXResult omxVCCOMM_LimitMVToRect(
     const OMXVCMotionVector * pSrcMV,
     OMXVCMotionVector *pDstMV,
     const OMXRect * pRectVOPRef,
     OMX_INT Xcoord,
     OMX_INT Ycoord,
     OMX_INT size
)
{
    /* Argument error checks */
    armRetArgErrIf(pSrcMV == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pDstMV == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pRectVOPRef == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf((size != 8) && (size != 16), OMX_Sts_BadArgErr);
    armRetArgErrIf((pRectVOPRef->width < (2* size)), OMX_Sts_BadArgErr);
    armRetArgErrIf((pRectVOPRef->height < (2* size)), OMX_Sts_BadArgErr);
    
    pDstMV->dx = armMin (armMax (pSrcMV->dx, 2*pRectVOPRef->x - Xcoord),
                    (2*pRectVOPRef->x + pRectVOPRef->width - Xcoord - size));
    pDstMV->dy = armMin (armMax (pSrcMV->dy, 2*pRectVOPRef->y - Ycoord),
                    (2*pRectVOPRef->y + pRectVOPRef->height - Ycoord - size));


    return OMX_Sts_NoErr;
}

/* End of file */
