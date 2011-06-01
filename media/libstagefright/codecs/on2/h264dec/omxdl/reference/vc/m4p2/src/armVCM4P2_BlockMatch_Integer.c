/**
 * 
 * File Name:  armVCM4P2_BlockMatch_Integer.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 *
 * Description:
 * Contains modules for Block matching, a full search algorithm
 * is implemented
 * 
 */
 
#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armVC.h"
#include "armCOMM.h"

/**
 * Function: armVCM4P2_BlockMatch_Integer
 *
 * Description:
 * Performs a 16x16 block search; estimates motion vector and associated minimum SAD.  
 * Both the input and output motion vectors are represented using half-pixel units, and 
 * therefore a shift left or right by 1 bit may be required, respectively, to match the 
 * input or output MVs with other functions that either generate output MVs or expect 
 * input MVs represented using integer pixel units. 
 *
 * Remarks:
 *
 * Parameters:
 * [in]	pSrcRefBuf		pointer to the reference Y plane; points to the reference MB that 
 *                    corresponds to the location of the current macroblock in the current 
 *                    plane.
 * [in]	refWidth		  width of the reference plane
 * [in]	pRefRect		  pointer to the valid rectangular in reference plane. Relative to image origin. 
 *                    It's not limited to the image boundary, but depended on the padding. For example, 
 *                    if you pad 4 pixels outside the image border, then the value for left border 
 *                    can be -4
 * [in]	pSrcCurrBuf		pointer to the current macroblock extracted from original plane (linear array, 
 *                    256 entries); must be aligned on an 8-byte boundary.
 * [in] pCurrPointPos	position of the current macroblock in the current plane
 * [in] pSrcPreMV		  pointer to predicted motion vector; NULL indicates no predicted MV
 * [in] pSrcPreSAD		pointer to SAD associated with the predicted MV (referenced by pSrcPreMV)
 * [in] searchRange		search range for 16X16 integer block,the units of it is full pixel,the search range 
 *                    is the same in all directions.It is in inclusive of the boundary and specified in 
 *                    terms of integer pixel units.
 * [in] pMESpec			  vendor-specific motion estimation specification structure; must have been allocated 
 *                    and then initialized using omxVCM4P2_MEInit prior to calling the block matching 
 *                    function.
 * [out]	pDstMV			pointer to estimated MV
 * [out]	pDstSAD			pointer to minimum SAD
 *
 * Return Value:
 * OMX_Sts_NoErr ¨C no error.
 * OMX_Sts_BadArgErr ¨C bad arguments
 *
 */

OMXResult armVCM4P2_BlockMatch_Integer(
     const OMX_U8 *pSrcRefBuf,
     OMX_INT refWidth,
     const OMXRect *pRefRect,
     const OMX_U8 *pSrcCurrBuf,
     const OMXVCM4P2Coordinate *pCurrPointPos,
     const OMXVCMotionVector *pSrcPreMV,
     const OMX_INT *pSrcPreSAD,
     void *pMESpec,
     OMXVCMotionVector *pDstMV,
     OMX_INT *pDstSAD,
     OMX_U8 BlockSize
)
{

    /* Definitions and Initializations*/

    OMX_INT     outer, inner, count,index;
    OMX_INT     candSAD;
    /*(256*256 +1) this is to make the SAD max initially*/
    OMX_INT     minSAD = 0x10001, fromX, toX, fromY, toY;
    /* Offset to the reference at the begining of the bounding box */
    const OMX_U8      *pTempSrcRefBuf;
    OMX_S16     x, y;
    OMX_INT searchRange;
   
    /* Argument error checks */
    armRetArgErrIf(pSrcRefBuf == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pRefRect == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pSrcCurrBuf == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pCurrPointPos == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pMESpec == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pDstMV == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pDstSAD == NULL, OMX_Sts_BadArgErr);
        
    searchRange = ((OMXVCM4P2MEParams *)pMESpec)->searchRange;
    /* Check for valid region */
    fromX = searchRange;
    toX   = searchRange;
    fromY = searchRange;
    toY   = searchRange;

    if ((pCurrPointPos->x - searchRange) < pRefRect->x)
    {
        fromX =  pCurrPointPos->x - pRefRect->x;
    }

    if ((pCurrPointPos->x + BlockSize + searchRange) > (pRefRect->x + pRefRect->width))
    {
        toX   = pRefRect->width - (pCurrPointPos->x - pRefRect->x) - BlockSize;
    }

    if ((pCurrPointPos->y - searchRange) < pRefRect->y)
    {
        fromY = pCurrPointPos->y - pRefRect->y;
    }

    if ((pCurrPointPos->y + BlockSize + searchRange) > (pRefRect->y + pRefRect->height))
    {
        toY   = pRefRect->width - (pCurrPointPos->y - pRefRect->y) - BlockSize;
    }

    pDstMV->dx = -fromX;
    pDstMV->dy = -fromY;
    /* Looping on y- axis */
    for (y = -fromY; y <= toY; y++)
    {

        /* Looping on x- axis */
        for (x = -fromX; x <= toX; x++)
        {
            /* Positioning the pointer */
            pTempSrcRefBuf = pSrcRefBuf + (refWidth * y) + x;

            /* Calculate the SAD */
            for (outer = 0, count = 0, index = 0, candSAD = 0;
                 outer < BlockSize;
                 outer++, index += refWidth - BlockSize)
            {
                for (inner = 0; inner < BlockSize; inner++, count++, index++)
                {
                    candSAD += armAbs (pTempSrcRefBuf[index] - pSrcCurrBuf[count]);                    
                }
            }

            /* Result calculations */
            if (armVCM4P2_CompareMV (x, y, candSAD, pDstMV->dx/2, pDstMV->dy/2, minSAD))
            {
                *pDstSAD = candSAD;
                minSAD   = candSAD;
                pDstMV->dx = x*2;
                pDstMV->dy = y*2;
            }

        } /* End of x- axis */
    } /* End of y-axis */

    return OMX_Sts_NoErr;

}

/* End of file */
