/**
 * 
 * File Name:  armVCM4P2_BlockMatch_Half.c
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
 * Function: armVCM4P2_BlockMatch_Half
 *
 * Description:
 * Performs a 16x16 block match with half-pixel resolution.  Returns the estimated 
 * motion vector and associated minimum SAD.  This function estimates the half-pixel 
 * motion vector by interpolating the integer resolution motion vector referenced 
 * by the input parameter pSrcDstMV, i.e., the initial integer MV is generated 
 * externally.  The input parameters pSrcRefBuf and pSearchPointRefPos should be 
 * shifted by the winning MV of 16x16 integer search prior to calling BlockMatch_Half_16x16.  
 * The function BlockMatch_Integer_16x16 may be used for integer motion estimation.
 *
 * Remarks:
 *
 * Parameters:
 * [in]	pSrcRefBuf		pointer to the reference Y plane; points to the reference MB 
 *                    that corresponds to the location of the current macroblock in 
 *                    the	current plane.
 * [in]	refWidth		  width of the reference plane
 * [in]	pRefRect		  reference plane valid region rectangle
 * [in]	pSrcCurrBuf		pointer to the current macroblock extracted from original plane 
 *                    (linear array, 256 entries); must be aligned on an 8-byte boundary. 
 * [in]	pSearchPointRefPos	position of the starting point for half pixel search (specified 
 *                          in terms of integer pixel units) in the reference plane.
 * [in]	rndVal			  rounding control bit for half pixel motion estimation; 
 *                    0=rounding control disabled; 1=rounding control enabled
 * [in]	pSrcDstMV		pointer to the initial MV estimate; typically generated during a prior 
 *                  16X16 integer search and its unit is half pixel.
 * [in] BlockSize     MacroBlock Size i.e either 16x16 or 8x8.
 * [out]pSrcDstMV		pointer to estimated MV
 * [out]pDstSAD			pointer to minimum SAD
 *
 * Return Value:
 * OMX_Sts_NoErr ¨C no error
 * OMX_Sts_BadArgErr ¨C bad arguments
 *
 */

OMXResult armVCM4P2_BlockMatch_Half(
     const OMX_U8 *pSrcRefBuf,
     OMX_INT refWidth,
     const OMXRect *pRefRect,
     const OMX_U8 *pSrcCurrBuf,
     const OMXVCM4P2Coordinate *pSearchPointRefPos,
     OMX_INT rndVal,
     OMXVCMotionVector *pSrcDstMV,
     OMX_INT *pDstSAD,
     OMX_U8 BlockSize
)
{
    OMX_INT     outer, inner, count, index;
    OMX_S16     halfPelX = 0, halfPelY = 0, x, y;
    OMX_INT     candSAD, refSAD = 0;
    OMX_INT     minSAD, fromX, toX, fromY, toY;
    /* Offset to the reference at the begining of the bounding box */
    const OMX_U8      *pTempSrcRefBuf;
    OMX_U8 tempPel;
        
    /* Argument error checks */
    armRetArgErrIf(pSrcRefBuf == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pRefRect == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pSrcCurrBuf == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pSearchPointRefPos == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pSrcDstMV == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pDstSAD == NULL, OMX_Sts_BadArgErr);

    /* Positioning the pointer */
    pTempSrcRefBuf = pSrcRefBuf + (refWidth * (pSrcDstMV->dy/2)) + (pSrcDstMV->dx/2);

    /* Copy the candidate to the temporary linear array */
    for (outer = 0, count = 0,index = 0;
         outer < BlockSize;
         outer++, index += refWidth - BlockSize)
    {
        for (inner = 0; inner < BlockSize; inner++, count++, index++)
        {
            refSAD += armAbs (pTempSrcRefBuf[index] - pSrcCurrBuf[count]);
        }
    }

    /* Set the minSad as reference SAD */
    minSAD = refSAD;
    *pDstSAD = refSAD;

    /* Check for valid region */
    fromX = 1;
    toX   = 1;
    fromY = 1;
    toY   = 1;
    if ((pSearchPointRefPos->x - 1) < pRefRect->x)
    {
        fromX = 0;
    }

    if ((pSearchPointRefPos->x + BlockSize + 1) > (pRefRect->x + pRefRect->width))
    {
        toX   = 0;
    }

    if ((pSearchPointRefPos->y - 1) < pRefRect->y)
    {
        fromY = 0;
    }

    if ((pSearchPointRefPos->y + BlockSize + 1) > (pRefRect->y + pRefRect->height))
    {
        toY   = 0;
    }

    /* Looping on y- axis */
    for (y = -fromY; y <= toY; y++)
    {
        /* Looping on x- axis */
        for (x = -fromX; x <= toX; x++)
        {
            /* check for integer position */
            if ( x == 0 && y == 0)
            {
                continue;
            }
            /* Positioning the pointer */
            pTempSrcRefBuf = pSrcRefBuf + (refWidth * (pSrcDstMV->dy/2))
                             + (pSrcDstMV->dx/2);

            /* Interpolate the pixel and calculate the SAD*/
            for (outer = 0, count = 0, candSAD = 0,index = 0;
                 outer < BlockSize;
                 outer++, index += refWidth - BlockSize)
            {
                for (inner = 0; inner < BlockSize; inner++, count++,index++)
                {
                    tempPel = (
                                pTempSrcRefBuf[index]
                                + pTempSrcRefBuf[index + x] * armAbs(x)
                                + pTempSrcRefBuf[index + refWidth * y] * armAbs(y)
                                + pTempSrcRefBuf[index + refWidth * y + x]
                                  * armAbs(x*y)
                                + armAbs (x) + armAbs (y) - rndVal
                              ) / (2 * (armAbs (x) + armAbs (y)));
                    candSAD += armAbs (tempPel - pSrcCurrBuf[count]);
                }
            }

            /* Result calculations */
            if (armVCM4P2_CompareMV (x, y, candSAD, halfPelX, halfPelY, minSAD))
            {
                *pDstSAD = candSAD;
                minSAD   = candSAD;
                halfPelX = x;
                halfPelY = y;
            }

        } /* End of x- axis */
    } /* End of y-axis */

    pSrcDstMV->dx += halfPelX;
    pSrcDstMV->dy += halfPelY;

    return OMX_Sts_NoErr;

}

/* End of file */
