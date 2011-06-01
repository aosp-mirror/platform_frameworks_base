/**
 * 
 * File Name:  omxVCM4P2_MotionEstimationMB.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 *
 * Description:
 * Contains module for motion search 16x16 macroblock
 * 
 */
 
#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armVC.h"
#include "armCOMM.h"

/**
 * Function: armVCM4P2_BlockMatch_16x16
 *
 * Description:
 * 16x16 block match wrapper function, calls omxVCM4P2_BlockMatch_Integer_16x16.
 * If half pel search is enabled it also calls omxVCM4P2_BlockMatch_Half_16x16
 *
 * Remarks:
 *
 * Parameters:
 * [in]	pSrcRefBuf	  pointer to the reference Y plane; points to the reference MB that
 *                    corresponds to the location of the current macroblock in the current
 *                    plane.
 * [in]	srcRefStep	  width of the reference plane
 * [in]	pRefRect	  pointer to the valid rectangular in reference plane. Relative to image origin.
 *                    It's not limited to the image boundary, but depended on the padding. For example,
 *                    if you pad 4 pixels outside the image border, then the value for left border
 *                    can be -4
 * [in]	pSrcCurrBuf	  pointer to the current macroblock extracted from original plane (linear array,
 *                    256 entries); must be aligned on an 16-byte boundary.
 * [in] pCurrPointPos position of the current macroblock in the current plane
 * [in] pSrcPreMV	  pointer to predicted motion vector; NULL indicates no predicted MV
 * [in] pSrcPreSAD	  pointer to SAD associated with the predicted MV (referenced by pSrcPreMV); may be set to NULL if unavailable.
 * [in] pMESpec		  vendor-specific motion estimation specification structure; must have been allocated
 *                    and then initialized using omxVCM4P2_MEInit prior to calling the block matching
 *                    function.
 * [out] pDstMV	      pointer to estimated MV
 * [out] pDstSAD	  pointer to minimum SAD
 * *
 * Return Value:
 * OMX_Sts_NoErr - no error
 * OMX_Sts_BadArgErr - bad arguments
 *
 */
static OMXResult armVCM4P2_BlockMatch_16x16(
     const OMX_U8 *pSrcRefBuf,
     const OMX_INT srcRefStep,
     const OMXRect *pRefRect,
     const OMX_U8 *pSrcCurrBuf,
     const OMXVCM4P2Coordinate *pCurrPointPos,
     OMXVCMotionVector *pSrcPreMV,
     OMX_INT *pSrcPreSAD,
     void *pMESpec,
     OMXVCMotionVector *pDstMV,
     OMX_INT *pDstSAD
)
{
    OMXVCM4P2MEParams *pMEParams = (OMXVCM4P2MEParams *)pMESpec;
    OMX_INT rndVal;
    
    rndVal = pMEParams->rndVal;
    
    omxVCM4P2_BlockMatch_Integer_16x16(
        pSrcRefBuf,
        srcRefStep,
        pRefRect,
        pSrcCurrBuf,
        pCurrPointPos,
        pSrcPreMV,
        pSrcPreSAD,
        pMEParams,
        pDstMV,
        pDstSAD);
    
    if (pMEParams->halfPelSearchEnable)
    {
        omxVCM4P2_BlockMatch_Half_16x16(
            pSrcRefBuf,
            srcRefStep,
            pRefRect,
            pSrcCurrBuf,
            pCurrPointPos,
            rndVal,
            pDstMV,
            pDstSAD);
    }
 
    return OMX_Sts_NoErr;        
}

/**
 * Function: armVCM4P2_BlockMatch_8x8
 *
 * Description:
 * 8x8 block match wrapper function, calls omxVCM4P2_BlockMatch_Integer_8x8.
 * If half pel search is enabled it also calls omxVCM4P2_BlockMatch_Half_8x8
 *
 * Remarks:
 *
 * Parameters:
 * [in]	pSrcRefBuf	  pointer to the reference Y plane; points to the reference MB that
 *                    corresponds to the location of the current macroblock in the current
 *                    plane.
 * [in]	srcRefStep	  width of the reference plane
 * [in]	pRefRect	  pointer to the valid rectangular in reference plane. Relative to image origin.
 *                    It's not limited to the image boundary, but depended on the padding. For example,
 *                    if you pad 4 pixels outside the image border, then the value for left border
 *                    can be -4
 * [in]	pSrcCurrBuf	  pointer to the current macroblock extracted from original plane (linear array,
 *                    256 entries); must be aligned on an 16-byte boundary.
 * [in] pCurrPointPos position of the current macroblock in the current plane
 * [in] pSrcPreMV	  pointer to predicted motion vector; NULL indicates no predicted MV
 * [in] pSrcPreSAD	  pointer to SAD associated with the predicted MV (referenced by pSrcPreMV); may be set to NULL if unavailable.
 * [in] pMESpec		  vendor-specific motion estimation specification structure; must have been allocated
 *                    and then initialized using omxVCM4P2_MEInit prior to calling the block matching
 *                    function.
 * [out] pDstMV	      pointer to estimated MV
 * [out] pDstSAD	  pointer to minimum SAD
 * *
 * Return Value:
 * OMX_Sts_NoErr - no error
 * OMX_Sts_BadArgErr - bad arguments
 *
 */
static OMXResult armVCM4P2_BlockMatch_8x8(
     const OMX_U8 *pSrcRefBuf,
     OMX_INT srcRefStep,
     const OMXRect *pRefRect,
     const OMX_U8 *pSrcCurrBuf,
     const OMXVCM4P2Coordinate *pCurrPointPos,
     OMXVCMotionVector *pSrcPreMV,
     OMX_INT *pSrcPreSAD,
     void *pMESpec,
     OMXVCMotionVector *pSrcDstMV,
     OMX_INT *pDstSAD
)
{
    OMXVCM4P2MEParams *pMEParams = (OMXVCM4P2MEParams *)pMESpec;
    OMX_INT rndVal;
    
    rndVal = pMEParams->rndVal;
    
    omxVCM4P2_BlockMatch_Integer_8x8(
        pSrcRefBuf,
        srcRefStep,
        pRefRect,
        pSrcCurrBuf,
        pCurrPointPos,
        pSrcPreMV,
        pSrcPreSAD,
        pMEParams,
        pSrcDstMV,
        pDstSAD);
    
    if (pMEParams->halfPelSearchEnable)
    {
        omxVCM4P2_BlockMatch_Half_8x8(
            pSrcRefBuf,
            srcRefStep,
            pRefRect,
            pSrcCurrBuf,
            pCurrPointPos,
            rndVal,
            pSrcDstMV,
            pDstSAD);
    }
    
    return OMX_Sts_NoErr;        
}


/**
 * Function:  omxVCM4P2_MotionEstimationMB   (6.2.4.3.1)
 *
 * Description:
 * Performs motion search for a 16x16 macroblock.  Selects best motion search 
 * strategy from among inter-1MV, inter-4MV, and intra modes.  Supports 
 * integer and half pixel resolution. 
 *
 * Input Arguments:
 *   
 *   pSrcCurrBuf - pointer to the top-left corner of the current MB in the 
 *            original picture plane; must be aligned on a 16-byte boundary.  
 *            The function does not expect source data outside the region 
 *            bounded by the MB to be available; for example it is not 
 *            necessary for the caller to guarantee the availability of 
 *            pSrcCurrBuf[-SrcCurrStep], i.e., the row of pixels above the MB 
 *            to be processed. 
 *   srcCurrStep - width of the original picture plane, in terms of full 
 *            pixels; must be a multiple of 16. 
 *   pSrcRefBuf - pointer to the reference Y plane; points to the reference 
 *            plane location corresponding to the location of the current 
 *            macroblock in the current plane; must be aligned on a 16-byte 
 *            boundary. 
 *   srcRefStep - width of the reference picture plane, in terms of full 
 *            pixels; must be a multiple of 16. 
 *   pRefRect - reference plane valid region rectangle, specified relative to 
 *            the image origin 
 *   pCurrPointPos - position of the current macroblock in the current plane 
 *   pMESpec - pointer to the vendor-specific motion estimation specification 
 *            structure; must be allocated and then initialized using 
 *            omxVCM4P2_MEInit prior to calling this function. 
 *   pMBInfo - array, of dimension four, containing pointers to information 
 *            associated with four nearby MBs: 
 *            -   pMBInfo[0] - pointer to left MB information 
 *            -   pMBInfo[1] - pointer to top MB information 
 *            -   pMBInfo[2] - pointer to top-left MB information 
 *            -   pMBInfo[3] - pointer to top-right MB information 
 *            Any pointer in the array may be set equal to NULL if the 
 *            corresponding MB doesn't exist.  For each MB, the following structure 
 *            members are used:    
 *            -   mbType - macroblock type, either OMX_VC_INTRA, OMX_VC_INTER, or 
 *                OMX_VC_INTER4V 
 *            -   pMV0[2][2] - estimated motion vectors; represented 
 *                in 1/2 pixel units 
 *            -   sliceID - number of the slice to which the MB belongs 
 *   pSrcDstMBCurr - pointer to information structure for the current MB.  
 *            The following entries should be set prior to calling the 
 *            function: sliceID - the number of the slice the to which the 
 *            current MB belongs.  The structure elements cbpy and cbpc are 
 *            ignored. 
 *
 * Output Arguments:
 *   
 *   pSrcDstMBCurr - pointer to updated information structure for the current 
 *            MB after MB-level motion estimation has been completed.  The 
 *            following structure members are updated by the ME function:   
 *              -  mbType - macroblock type: OMX_VC_INTRA, OMX_VC_INTER, or 
 *                 OMX_VC_INTER4V. 
 *              -  pMV0[2][2] - estimated motion vectors; represented in 
 *                 terms of 1/2 pel units. 
 *              -  pMVPred[2][2] - predicted motion vectors; represented 
 *                 in terms of 1/2 pel units. 
 *            The structure members cbpy and cbpc are not updated by the function. 
 *   pDstSAD - pointer to the minimum SAD for INTER1V, or sum of minimum SADs 
 *            for INTER4V 
 *   pDstBlockSAD - pointer to an array of SAD values for each of the four 
 *            8x8 luma blocks in the MB.  The block SADs are in scan order for 
 *            each MB. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments.  Returned if one or more of the 
 *              following conditions is true: 
 *    -    at least one of the following pointers is NULL: pSrcCurrBuf, 
 *              pSrcRefBuf, pRefRect, pCurrPointPos, pMBInter, pMBIntra, 
 *              pSrcDstMBCurr, or pDstSAD. 
 *
 */

OMXResult omxVCM4P2_MotionEstimationMB (
    const OMX_U8 *pSrcCurrBuf,
    OMX_S32 srcCurrStep,
    const OMX_U8 *pSrcRefBuf,
    OMX_S32 srcRefStep,
    const OMXRect*pRefRect,
    const OMXVCM4P2Coordinate *pCurrPointPos,
    void *pMESpec,
    const OMXVCM4P2MBInfoPtr *pMBInfo,
    OMXVCM4P2MBInfo *pSrcDstMBCurr,
    OMX_U16 *pDstSAD,
    OMX_U16 *pDstBlockSAD
)
{
 
    OMX_INT intraSAD, average, count, index, x, y;
    OMXVCMotionVector dstMV16x16;
    OMX_INT           dstSAD16x16;
    OMX_INT           dstSAD8x8;
    OMXVCM4P2MEParams  *pMEParams; 
	OMXVCM4P2Coordinate TempCurrPointPos; 
    OMXVCM4P2Coordinate *pTempCurrPointPos; 
    OMX_U8 aTempSrcCurrBuf[271];
    OMX_U8 *pTempSrcCurrBuf;
    OMX_U8 *pDst;
    OMX_U8 aDst[71];
    OMX_S32 dstStep = 8;
    OMX_INT predictType;
	OMX_S32 Sad;
    const OMX_U8 *pTempSrcRefBuf;
    OMXVCMotionVector* pSrcCandMV1[4];
    OMXVCMotionVector* pSrcCandMV2[4];
    OMXVCMotionVector* pSrcCandMV3[4];
        
    /* Argument error checks */
    armRetArgErrIf(!armIs16ByteAligned(pSrcCurrBuf), OMX_Sts_BadArgErr);
	armRetArgErrIf(!armIs16ByteAligned(pSrcRefBuf), OMX_Sts_BadArgErr);
    armRetArgErrIf(((srcCurrStep % 16) || (srcRefStep % 16)), OMX_Sts_BadArgErr);
	armRetArgErrIf(pSrcCurrBuf == NULL, OMX_Sts_BadArgErr);
	armRetArgErrIf(pSrcRefBuf == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pRefRect == NULL, OMX_Sts_BadArgErr);    
    armRetArgErrIf(pCurrPointPos == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pSrcDstMBCurr == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pDstSAD == NULL, OMX_Sts_BadArgErr);
    
    
    pTempCurrPointPos = &(TempCurrPointPos);
    pTempSrcCurrBuf = armAlignTo16Bytes(aTempSrcCurrBuf);
    pMEParams = (OMXVCM4P2MEParams *)pMESpec;
    pTempCurrPointPos->x = pCurrPointPos->x;
    pTempCurrPointPos->y = pCurrPointPos->y;
    pSrcDstMBCurr->mbType = OMX_VC_INTER;
    
    /* Preparing a linear buffer for block match */
    for (y = 0, index = count = 0; y < 16; y++, index += srcCurrStep - 16)
    {
        for(x = 0; x < 16; x++, count++, index++)
        {
            pTempSrcCurrBuf[count] = pSrcCurrBuf[index];
        }
    }
    for(y = 0, index = 0; y < 2; y++)
    {
        for(x = 0; x < 2; x++,index++)
        {
            if((pMBInfo[0] != NULL) && (pMBInfo[0]->mbType != OMX_VC_INTRA))
            {
               pSrcCandMV1[index] = &(pMBInfo[0]->pMV0[y][x]); 
            }
            else
            {
               pSrcCandMV1[index] = NULL;
            }
            if((pMBInfo[1] != NULL) && (pMBInfo[1]->mbType != OMX_VC_INTRA))
            {
               pSrcCandMV2[index] = &(pMBInfo[1]->pMV0[y][x]);
            }
            else
            {
               pSrcCandMV2[index] = NULL; 
            }
            if((pMBInfo[3] != NULL) && (pMBInfo[3]->mbType != OMX_VC_INTRA))
            {
               pSrcCandMV3[index] = &(pMBInfo[3]->pMV0[y][x]);
            }
            else
            {
               pSrcCandMV3[index] = NULL; 
            }
        }
    }
	/* Calculating SAD at MV(0,0) */
	armVCCOMM_SAD(pTempSrcCurrBuf,
					  16,
					  pSrcRefBuf,
					  srcRefStep,
					  &Sad,
					  16,
					  16);
	*pDstSAD = Sad;

    /* Mode decision for NOT_CODED MB */
	if(*pDstSAD == 0)
	{
        pSrcDstMBCurr->pMV0[0][0].dx = 0;
        pSrcDstMBCurr->pMV0[0][0].dy = 0;
        *pDstSAD   = 0;
		return OMX_Sts_NoErr;
	}

    omxVCM4P2_FindMVpred(
                    &(pSrcDstMBCurr->pMV0[0][0]),
                    pSrcCandMV1[0],
                    pSrcCandMV2[0],
                    pSrcCandMV3[0],
                    &(pSrcDstMBCurr->pMVPred[0][0]),
                    NULL,
                    0);
                    
    /* Inter 1 MV */
    armVCM4P2_BlockMatch_16x16(
        pSrcRefBuf,
        srcRefStep,
        pRefRect,
        pTempSrcCurrBuf,
        pCurrPointPos,
        &(pSrcDstMBCurr->pMVPred[0][0]),
        NULL,
        pMEParams,
        &dstMV16x16,
        &dstSAD16x16);
    
    /* Initialize all with 1 MV values */
    pSrcDstMBCurr->pMV0[0][0].dx = dstMV16x16.dx;
    pSrcDstMBCurr->pMV0[0][0].dy = dstMV16x16.dy;
    pSrcDstMBCurr->pMV0[0][1].dx = dstMV16x16.dx;
    pSrcDstMBCurr->pMV0[0][1].dy = dstMV16x16.dy;
    pSrcDstMBCurr->pMV0[1][0].dx = dstMV16x16.dx;
    pSrcDstMBCurr->pMV0[1][0].dy = dstMV16x16.dy;
    pSrcDstMBCurr->pMV0[1][1].dx = dstMV16x16.dx;
    pSrcDstMBCurr->pMV0[1][1].dy = dstMV16x16.dy; 
    
    *pDstSAD   = dstSAD16x16;       
    
    if (pMEParams->searchEnable8x8)
    {
        /* Inter 4MV */
        armVCM4P2_BlockMatch_8x8 (pSrcRefBuf,
                                      srcRefStep, pRefRect,
                                      pTempSrcCurrBuf, pTempCurrPointPos,
                                      &(pSrcDstMBCurr->pMVPred[0][0]), NULL,
                                      pMEParams, &(pSrcDstMBCurr->pMV0[0][0]),
                                      &dstSAD8x8
                                      );
        pDstBlockSAD[0] = dstSAD8x8;
        *pDstSAD = dstSAD8x8;
        pTempCurrPointPos->x += 8;
        pSrcRefBuf += 8;
        omxVCM4P2_FindMVpred(
                    &(pSrcDstMBCurr->pMV0[0][1]),
                    pSrcCandMV1[1],
                    pSrcCandMV2[1],
                    pSrcCandMV3[1],
                    &(pSrcDstMBCurr->pMVPred[0][1]),
                    NULL,
                    1);
        
        armVCM4P2_BlockMatch_8x8 (pSrcRefBuf,
                                      srcRefStep, pRefRect,
                                      pTempSrcCurrBuf, pTempCurrPointPos,
                                      &(pSrcDstMBCurr->pMVPred[0][1]), NULL,
                                      pMEParams, &(pSrcDstMBCurr->pMV0[0][1]),
                                      &dstSAD8x8
                                      );
        pDstBlockSAD[1] = dstSAD8x8;
        *pDstSAD += dstSAD8x8;
        pTempCurrPointPos->x -= 8;
        pTempCurrPointPos->y += 8;
        pSrcRefBuf += (srcRefStep * 8) - 8;
        
        omxVCM4P2_FindMVpred(
                    &(pSrcDstMBCurr->pMV0[1][0]),
                    pSrcCandMV1[2],
                    pSrcCandMV2[2],
                    pSrcCandMV3[2],
                    &(pSrcDstMBCurr->pMVPred[1][0]),
                    NULL,
                    2);
        armVCM4P2_BlockMatch_8x8 (pSrcRefBuf,
                                      srcRefStep, pRefRect,
                                      pTempSrcCurrBuf, pTempCurrPointPos,
                                      &(pSrcDstMBCurr->pMVPred[1][0]), NULL,
                                      pMEParams, &(pSrcDstMBCurr->pMV0[1][0]),
                                      &dstSAD8x8
                                      );
        pDstBlockSAD[2] = dstSAD8x8;
        *pDstSAD += dstSAD8x8;
        pTempCurrPointPos->x += 8;
        pSrcRefBuf += 8;
        omxVCM4P2_FindMVpred(
                    &(pSrcDstMBCurr->pMV0[1][1]),
                    pSrcCandMV1[3],
                    pSrcCandMV2[3],
                    pSrcCandMV3[3],
                    &(pSrcDstMBCurr->pMVPred[1][1]),
                    NULL,
                    3);
        armVCM4P2_BlockMatch_8x8 (pSrcRefBuf,
                                      srcRefStep, pRefRect,
                                      pTempSrcCurrBuf, pTempCurrPointPos,
                                      &(pSrcDstMBCurr->pMVPred[1][1]), NULL,
                                      pMEParams, &(pSrcDstMBCurr->pMV0[1][1]),
                                      &dstSAD8x8
                                      );
        pDstBlockSAD[3] = dstSAD8x8;
        *pDstSAD += dstSAD8x8;   
        
        
        /* Checking if 4MV is equal to 1MV */
        if (
            (pSrcDstMBCurr->pMV0[0][0].dx != dstMV16x16.dx) ||
            (pSrcDstMBCurr->pMV0[0][0].dy != dstMV16x16.dy) ||
            (pSrcDstMBCurr->pMV0[0][1].dx != dstMV16x16.dx) ||
            (pSrcDstMBCurr->pMV0[0][1].dy != dstMV16x16.dy) ||
            (pSrcDstMBCurr->pMV0[1][0].dx != dstMV16x16.dx) ||
            (pSrcDstMBCurr->pMV0[1][0].dy != dstMV16x16.dy) ||
            (pSrcDstMBCurr->pMV0[1][1].dx != dstMV16x16.dx) ||
            (pSrcDstMBCurr->pMV0[1][1].dy != dstMV16x16.dy)
           )
        {
            /* select the 4 MV */
            pSrcDstMBCurr->mbType = OMX_VC_INTER4V;
        }                                      
    }
                                         
    /* finding the error in intra mode */
    for (count = 0, average = 0; count < 256 ; count++)
    {
        average = average + pTempSrcCurrBuf[count];
    }
    average = average/256;
    
	intraSAD = 0;

    /* Intra SAD calculation */
    for (count = 0; count < 256 ; count++)
    {
        intraSAD += armAbs ((pTempSrcCurrBuf[count]) - (average));
    }
    
	/* Using the MPEG4 VM formula for intra/inter mode decision 
	   Var < (SAD - 2*NB) where NB = N^2 is the number of pixels
	   of the macroblock.*/

    if (intraSAD <= (*pDstSAD - 512))
    {
        pSrcDstMBCurr->mbType = OMX_VC_INTRA;
        pSrcDstMBCurr->pMV0[0][0].dx = 0;
        pSrcDstMBCurr->pMV0[0][0].dy = 0;
        *pDstSAD   = intraSAD;
        pDstBlockSAD[0] = 0xFFFF;
        pDstBlockSAD[1] = 0xFFFF;
        pDstBlockSAD[2] = 0xFFFF;
        pDstBlockSAD[3] = 0xFFFF;
    }

    if(pSrcDstMBCurr->mbType == OMX_VC_INTER)
    {
      pTempSrcRefBuf = pSrcRefBuf + (srcRefStep * dstMV16x16.dy) + dstMV16x16.dx;
    
      if((dstMV16x16.dx & 0x1) && (dstMV16x16.dy & 0x1))
      {
        predictType = OMX_VC_HALF_PIXEL_XY;
      }
      else if(dstMV16x16.dx & 0x1)
      {
        predictType = OMX_VC_HALF_PIXEL_X;
      }
      else if(dstMV16x16.dy & 0x1)
      {
        predictType = OMX_VC_HALF_PIXEL_Y;
      }
      else
      {
        predictType = OMX_VC_INTEGER_PIXEL;
      }
      
      pDst = armAlignTo8Bytes(&(aDst[0]));
      /* Calculating Block SAD at MV(dstMV16x16.dx,dstMV16x16.dy) */
	  /* Block 0 */
      omxVCM4P2_MCReconBlock(pTempSrcRefBuf,
	                             srcRefStep,
                                 NULL,
                                 pDst, 
                                 dstStep,
                                 predictType,
                                 pMEParams->rndVal);
    
      armVCCOMM_SAD(pTempSrcCurrBuf,
                        16,
                        pDst,
                        dstStep,
                        &Sad,
                        8,
                        8);
      pDstBlockSAD[0] = Sad;
   
      /* Block 1 */
      omxVCM4P2_MCReconBlock(pTempSrcRefBuf + 8,
                                 srcRefStep,
                                 NULL,
                                 pDst, 
                                 dstStep,
                                 predictType,
                                 pMEParams->rndVal);					  

      armVCCOMM_SAD(pTempSrcCurrBuf + 8,
                        16,
                        pDst,
                        dstStep,
                        &Sad,
                        8,
                        8);
      pDstBlockSAD[1] = Sad;
	
      /* Block 2 */
      omxVCM4P2_MCReconBlock(pTempSrcRefBuf + (srcRefStep*8),
                                 srcRefStep,
                                 NULL,
                                 pDst, 
                                 dstStep,
                                 predictType,
                                 pMEParams->rndVal);

      armVCCOMM_SAD(pTempSrcCurrBuf + (16*8),
                        16,
                        pDst,
                        dstStep,
                        &Sad,
                        8,
                        8);
      pDstBlockSAD[2] = Sad;

	  /* Block 3 */
      omxVCM4P2_MCReconBlock(pTempSrcRefBuf + (srcRefStep*8) + 8,
                                 srcRefStep,
                                 NULL,
                                 pDst, 
                                 dstStep,
                                 predictType,
                                 pMEParams->rndVal);

      armVCCOMM_SAD(pTempSrcCurrBuf + (16*8) + 8,
                        16,
                        pDst,
                        dstStep,
                        &Sad,
                        8,
                        8);
      pDstBlockSAD[3] = Sad;
    }
    return OMX_Sts_NoErr;
}

/* End of file */

