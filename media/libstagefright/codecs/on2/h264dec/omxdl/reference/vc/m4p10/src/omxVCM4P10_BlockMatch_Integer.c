/**
 * 
 * File Name:  omxVCM4P10_BlockMatch_Integer.c
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
 * Function:  omxVCM4P10_BlockMatch_Integer   (6.3.5.2.1)
 *
 * Description:
 * Performs integer block match.  Returns best MV and associated cost. 
 *
 * Input Arguments:
 *   
 *   pSrcOrgY - Pointer to the top-left corner of the current block. If 
 *            iBlockWidth==4,  4-byte alignment required. If iBlockWidth==8,  
 *            8-byte alignment required. If iBlockWidth==16, 16-byte alignment 
 *            required. 
 *   pSrcRefY - Pointer to the top-left corner of the co-located block in the 
 *            reference picture. If iBlockWidth==4,  4-byte alignment 
 *            required.  If iBlockWidth==8,  8-byte alignment required.  If 
 *            iBlockWidth==16, 16-byte alignment required. 
 *   nSrcOrgStep - Stride of the original picture plane, expressed in terms 
 *            of integer pixels; must be a multiple of iBlockWidth. 
 *   nSrcRefStep - Stride of the reference picture plane, expressed in terms 
 *            of integer pixels 
 *   pRefRect - pointer to the valid reference rectangle inside the reference 
 *            picture plane 
 *   nCurrPointPos - position of the current block in the current plane 
 *   iBlockWidth - Width of the current block, expressed in terms of integer 
 *            pixels; must be equal to either 4, 8, or 16. 
 *   iBlockHeight - Height of the current block, expressed in terms of 
 *            integer pixels; must be equal to either 4, 8, or 16. 
 *   nLamda - Lamda factor; used to compute motion cost 
 *   pMVPred - Predicted MV; used to compute motion cost, expressed in terms 
 *            of 1/4-pel units 
 *   pMVCandidate - Candidate MV; used to initialize the motion search, 
 *            expressed in terms of integer pixels 
 *   pMESpec - pointer to the ME specification structure 
 *
 * Output Arguments:
 *   
 *   pDstBestMV - Best MV resulting from integer search, expressed in terms 
 *            of 1/4-pel units 
 *   pBestCost - Motion cost associated with the best MV; computed as 
 *            SAD+Lamda*BitsUsedByMV 
 *
 * Return Value:
 *    OMX_Sts_NoErr, if the function runs without error.
 *    OMX_Sts_BadArgErr - bad arguments: if one of the following cases occurs: 
 *    -    any of the following poitners are NULL:
 *         pSrcOrgY, pSrcRefY, pRefRect, pMVPred, pMVCandidate, or pMESpec. 
 *    -    Either iBlockWidth or iBlockHeight are values other than 4, 8, or 16. 
 *    -    Any alignment restrictions are violated 
 *
 */
 
 OMXResult omxVCM4P10_BlockMatch_Integer (
     const OMX_U8 *pSrcOrgY,
     OMX_S32 nSrcOrgStep,
     const OMX_U8 *pSrcRefY,
     OMX_S32 nSrcRefStep,
	 const OMXRect *pRefRect,
	 const OMXVCM4P2Coordinate *pCurrPointPos,
     OMX_U8 iBlockWidth,
     OMX_U8 iBlockHeight,
     OMX_U32 nLamda,
     const OMXVCMotionVector *pMVPred,
     const OMXVCMotionVector *pMVCandidate,
     OMXVCMotionVector *pBestMV,
     OMX_S32 *pBestCost,
     void *pMESpec
)
{
    /* Definitions and Initializations*/
    OMX_INT candSAD;
    OMX_INT fromX, toX, fromY, toY;
    /* Offset to the reference at the begining of the bounding box */
    const OMX_U8 *pTempSrcRefY, *pTempSrcOrgY;
    OMX_S16 x, y;
    OMXVCMotionVector diffMV;
    OMX_S32 nSearchRange;
    ARMVCM4P10_MESpec *armMESpec = (ARMVCM4P10_MESpec *) pMESpec;

    /* Argument error checks */
    armRetArgErrIf((iBlockWidth ==  4) && (!armIs4ByteAligned(pSrcOrgY)), OMX_Sts_BadArgErr);
    armRetArgErrIf((iBlockWidth ==  8) && (!armIs8ByteAligned(pSrcOrgY)), OMX_Sts_BadArgErr);
    armRetArgErrIf((iBlockWidth == 16) && (!armIs16ByteAligned(pSrcOrgY)), OMX_Sts_BadArgErr);
	armRetArgErrIf((iBlockWidth ==  4) && (!armIs4ByteAligned(pSrcRefY)), OMX_Sts_BadArgErr);
    armRetArgErrIf((iBlockWidth ==  8) && (!armIs8ByteAligned(pSrcRefY)), OMX_Sts_BadArgErr);
    armRetArgErrIf((iBlockWidth == 16) && (!armIs16ByteAligned(pSrcRefY)), OMX_Sts_BadArgErr);
    armRetArgErrIf(pSrcOrgY == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pSrcRefY == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pMVPred == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pMVCandidate == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pBestMV == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pBestCost == NULL, OMX_Sts_BadArgErr);
	armRetArgErrIf(((iBlockWidth!=4)&&(iBlockWidth!=8)&&(iBlockWidth!=16)) , OMX_Sts_BadArgErr);
	armRetArgErrIf(((iBlockHeight!=4)&&(iBlockHeight!=8)&&(iBlockHeight!=16)) , OMX_Sts_BadArgErr);
    armIgnore (pMESpec);

    if(iBlockWidth == 4)
    {
        nSearchRange = armMESpec->MEParams.searchRange4x4;
    }
    else if(iBlockWidth == 8)
    {
        nSearchRange = armMESpec->MEParams.searchRange8x8;
    }
    else
    {
        nSearchRange = armMESpec->MEParams.searchRange16x16;
    }
    /* Check for valid region */ 
    fromX = nSearchRange;
    toX   = nSearchRange;
    fromY = nSearchRange;
    toY   = nSearchRange;
    
    if ((pCurrPointPos->x - nSearchRange) < pRefRect->x)
    {
        fromX =  pCurrPointPos->x - pRefRect->x;
    }

    if ((pCurrPointPos->x + iBlockWidth + nSearchRange) > (pRefRect->x + pRefRect->width))
    {
        toX   = pRefRect->width - (pCurrPointPos->x - pRefRect->x) - iBlockWidth;
    }

    if ((pCurrPointPos->y - nSearchRange) < pRefRect->y)
    {
        fromY = pCurrPointPos->y - pRefRect->y;
    }

    if ((pCurrPointPos->y + iBlockWidth + nSearchRange) > (pRefRect->y + pRefRect->height))
    {
        toY   = pRefRect->width - (pCurrPointPos->y - pRefRect->y) - iBlockWidth;
    }
    
    pBestMV->dx = -fromX * 4;
    pBestMV->dy = -fromY * 4;
    /* Initialize to max value as a start point */
    *pBestCost = 0x7fffffff;
    
    /* Looping on y- axis */
    for (y = -fromY; y <= toY; y++)
    {
        /* Looping on x- axis */
        for (x = -fromX; x <= toX; x++)
        {
            /* Positioning the pointer */
            pTempSrcRefY = pSrcRefY + (nSrcRefStep * y) + x;
            pTempSrcOrgY = pSrcOrgY;
            
            /* Calculate the SAD */
            armVCCOMM_SAD(	
    	        pTempSrcOrgY,
    	        nSrcOrgStep,
    	        pTempSrcRefY,
    	        nSrcRefStep,
    	        &candSAD,
    	        iBlockHeight,
    	        iBlockWidth);
    	    
            diffMV.dx = (x * 4) - pMVPred->dx;
            diffMV.dy = (y * 4) - pMVPred->dy;
            
            /* Result calculations */
            armVCM4P10_CompareMotionCostToMV ((x * 4), (y * 4), diffMV, candSAD, pBestMV, nLamda, pBestCost);

        } /* End of x- axis */
    } /* End of y-axis */

    return OMX_Sts_NoErr;

}

/* End of file */
