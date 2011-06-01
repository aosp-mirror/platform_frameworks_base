/**
 * 
 * File Name:  omxVCM4P10_BlockMatch_Quarter.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 *
 * Description:
 * Contains modules for quater pel Block matching, 
 * 
 */
 
#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armVC.h"
#include "armCOMM.h"
 
    
/**
 * Function:  omxVCM4P10_BlockMatch_Quarter   (6.3.5.2.3)
 *
 * Description:
 * Performs a quarter-pel block match using results from a prior half-pel 
 * search.  Returns the best MV and associated cost.  This function estimates 
 * the quarter-pixel motion vector by interpolating the half-pel resolution 
 * motion vector referenced by the input parameter pSrcDstBestMV, i.e., the 
 * initial half-pel MV is generated externally.  The function 
 * omxVCM4P10_BlockMatch_Half may be used for half-pel motion estimation. 
 *
 * Input Arguments:
 *   
 *   pSrcOrgY - Pointer to the current position in original picture plane. If 
 *            iBlockWidth==4,  4-byte alignment required. If iBlockWidth==8,  
 *            8-byte alignment required. If iBlockWidth==16, 16-byte alignment 
 *            required. 
 *   pSrcRefY - Pointer to the top-left corner of the co-located block in the 
 *            reference picture  If iBlockWidth==4,  4-byte alignment 
 *            required.  If iBlockWidth==8,  8-byte alignment required.  If 
 *            iBlockWidth==16, 16-byte alignment required. 
 *   nSrcOrgStep - Stride of the original picture plane in terms of full 
 *            pixels; must be a multiple of iBlockWidth. 
 *   nSrcRefStep - Stride of the reference picture plane in terms of full 
 *            pixels 
 *   iBlockWidth - Width of the current block in terms of full pixels; must 
 *            be equal to either 4, 8, or 16. 
 *   iBlockHeight - Height of the current block in terms of full pixels; must 
 *            be equal to either 4, 8, or 16. 
 *   nLamda - Lamda factor, used to compute motion cost 
 *   pMVPred - Predicted MV, represented in terms of 1/4-pel units; used to 
 *            compute motion cost 
 *   pSrcDstBestMV - The best MV resulting from a prior half-pel search, 
 *            represented in terms of 1/4 pel units 
 *
 * Output Arguments:
 *   
 *   pSrcDstBestMV - Best MV resulting from the quarter-pel search, expressed 
 *            in terms of 1/4-pel units 
 *   pBestCost - Motion cost associated with the best MV; computed as 
 *            SAD+Lamda*BitsUsedByMV 
 *
 * Return Value:
 *    OMX_Sts_NoErr, if the function runs without error.
 *    OMX_Sts_BadArgErr - bad arguments: if one of the following cases occurs: 
 *    -    One of more of the following pointers is NULL: 
 *         pSrcOrgY, pSrcRefY, pSrcDstBestMV, pMVPred, pBestCost 
 *    -    iBlockWidth or iBlockHeight are equal to values other than 4, 8, or 16. 
 *    -    Any alignment restrictions are violated 
 *
 */
 
OMXResult omxVCM4P10_BlockMatch_Quarter(
    const OMX_U8* pSrcOrgY, 
    OMX_S32 nSrcOrgStep, 
    const OMX_U8* pSrcRefY, 
    OMX_S32 nSrcRefStep, 
    OMX_U8 iBlockWidth, 
    OMX_U8 iBlockHeight, 
    OMX_U32 nLamda, 
    const OMXVCMotionVector* pMVPred, 
    OMXVCMotionVector* pSrcDstBestMV, 
    OMX_S32* pBestCost
)
{
    /* Definitions and Initializations*/
    OMX_INT     candSAD;
    OMX_INT     fromX, toX, fromY, toY;
    /* Offset to the reference at the begining of the bounding box */
    const OMX_U8      *pTempSrcRefY, *pTempSrcOrgY;
    OMX_S16     x, y;
    OMXVCMotionVector diffMV, candMV, initialMV;
    OMX_U8      interpolY[256];
    OMX_S32     pelPosX, pelPosY;

    /* Argument error checks */
    armRetArgErrIf((iBlockWidth ==  4) && (!armIs4ByteAligned(pSrcOrgY)), OMX_Sts_BadArgErr);
    armRetArgErrIf((iBlockWidth ==  8) && (!armIs8ByteAligned(pSrcOrgY)), OMX_Sts_BadArgErr);
    armRetArgErrIf((iBlockWidth == 16) && (!armIs16ByteAligned(pSrcOrgY)), OMX_Sts_BadArgErr);
	armRetArgErrIf((iBlockWidth ==  4) && (!armIs4ByteAligned(pSrcRefY)), OMX_Sts_BadArgErr);
    armRetArgErrIf((iBlockWidth ==  8) && (!armIs8ByteAligned(pSrcRefY)), OMX_Sts_BadArgErr);
    armRetArgErrIf((iBlockWidth == 16) && (!armIs16ByteAligned(pSrcRefY)), OMX_Sts_BadArgErr);
    armRetArgErrIf((nSrcOrgStep % iBlockWidth), OMX_Sts_BadArgErr);
    armRetArgErrIf(pSrcOrgY == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pSrcRefY == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pMVPred == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pSrcDstBestMV == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pBestCost == NULL, OMX_Sts_BadArgErr);
	armRetArgErrIf(((iBlockWidth!=4)&&(iBlockWidth!=8)&&(iBlockWidth!=16)) , OMX_Sts_BadArgErr);
	armRetArgErrIf(((iBlockHeight!=4)&&(iBlockHeight!=8)&&(iBlockHeight!=16)) , OMX_Sts_BadArgErr);
        
            
    /* Check for valid region */ 
    fromX = 1;
    toX   = 1;
    fromY = 1;
    toY   = 1;
    
    /* Initialize to max value as a start point */
    *pBestCost = 0x7fffffff;
    
    initialMV.dx = pSrcDstBestMV->dx;
    initialMV.dy = pSrcDstBestMV->dy;
    
    /* Looping on y- axis */
    for (y = -fromY; y <= toY; y++)
    {
        /* Looping on x- axis */
        for (x = -fromX; x <= toX; x++)
        {
            /* Positioning the pointer */
            pTempSrcRefY = pSrcRefY + (nSrcRefStep * (initialMV.dy/4)) + (initialMV.dx/4);
            
            /* Calculating the fract pel position */
            pelPosX = (initialMV.dx % 4) + x;
            if (pelPosX < 0) 
            {
                pTempSrcRefY = pTempSrcRefY - 1;
                pelPosX += 4;
            }
            pelPosY = (initialMV.dy % 4) + y;
            if (pelPosY < 0) 
            {
                pTempSrcRefY = pTempSrcRefY - (1 * nSrcRefStep);
                pelPosY += 4;
            }
            
            pTempSrcOrgY = pSrcOrgY; 
            
            /* Prepare cand MV */
            candMV.dx = initialMV.dx + x;
            candMV.dy = initialMV.dy + y;
             
            /* Interpolate Quater pel for the current position*/
            armVCM4P10_Interpolate_Luma(
                        pTempSrcRefY,
                        nSrcRefStep,
                        interpolY,
                        iBlockWidth,
                        iBlockWidth,
                        iBlockHeight,
                        pelPosX,
                        pelPosY);
            
            /* Calculate the SAD */
            armVCCOMM_SAD(	
                        pTempSrcOrgY,
                        nSrcOrgStep,
                        interpolY,
                        iBlockWidth,
                        &candSAD,
                        iBlockHeight,
                        iBlockWidth);
 
            diffMV.dx = candMV.dx - pMVPred->dx;
            diffMV.dy = candMV.dy - pMVPred->dy;
            
            /* Result calculations */
            armVCM4P10_CompareMotionCostToMV (
                        candMV.dx, 
                        candMV.dy, 
                        diffMV, 
                        candSAD, 
                        pSrcDstBestMV, 
                        nLamda, 
                        pBestCost);

        } /* End of x- axis */
    } /* End of y-axis */

    return OMX_Sts_NoErr;

}

/* End of file */
