/* ----------------------------------------------------------------
 *
 * 
 * File Name:  omxVCM4P10_DeblockLuma_I.c
 * OpenMAX DL: v1.0.2
 * Revision:   12290
 * Date:       Wednesday, April 9, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 *
 * H.264 luma deblock
 * 
 */
 
#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armCOMM.h"
#include "armVC.h"
 

/**
 * Function: omxVCM4P10_DeblockLuma_I
 *
 * Description:
 * This function performs deblock filtering the horizontal and vertical edges of a luma macroblock
 *(16x16).
 *
 * Remarks:
 *
 * Parameters:
 * [in]	pSrcDst         pointer to the input macroblock. Must be 8-byte aligned.
 * [in]	srcdstStep      image width
 * [in]	pAlpha          pointer to a 2x2 table of alpha thresholds, organized as follows: { external
 *                             vertical edge, internal vertical edge, external horizontal
 *                             edge, internal horizontal edge }
 * [in]	pBeta			pointer to a 2x2 table of beta thresholds, organized as follows: { external
 *                              vertical edge, internal vertical edge, external  horizontal edge,
 *                              internal  horizontal edge }
 * [in]	pThresholds		pointer to a 16x2 table of threshold (TC0), organized as follows: { values for
 *                              the  left or above edge of each 4x4 block, arranged in  vertical block order
 *                              and then in horizontal block order)
 * [in]	pBS				 pointer to a 16x2 table of BS parameters arranged in scan block order for vertical edges and then horizontal edges;
 *                               valid in the range [0,4] with the following restrictions: i) pBS[i]== 4 may occur only for 0<=i<=3, ii) pBS[i]== 4 if and only if pBS[i^1]== 4.  Must be 4-byte aligned.
 * [out]	pSrcDst		pointer to filtered output macroblock.
 *
 * Return Value:
 * OMX_Sts_NoErr - no error
 * OMX_Sts_BadArgErr - bad arguments
 *    - Either of the pointers in pSrcDst, pAlpha, pBeta, pTresholds or pBS is NULL.
 *    - pSrcDst is not 8-byte aligned.
 *    - srcdstStep is not a multiple of 8
 *    - pBS is out of range, i.e., one of the following conditions is true: pBS[i]<0, pBS[i]>4, pBS[i]==4 for i>=4, or (pBS[i]==4 && pBS[i^1]!=4) for 0<=i<=3.
.
 *
 */

OMXResult omxVCM4P10_DeblockLuma_I(
	OMX_U8* pSrcDst, 
	OMX_S32 srcdstStep, 
	const OMX_U8* pAlpha, 
	const OMX_U8* pBeta, 
	const OMX_U8* pThresholds, 
	const OMX_U8 *pBS
)
{
    OMXResult errorCode;
    
    armRetArgErrIf(pSrcDst == NULL,             OMX_Sts_BadArgErr);
    armRetArgErrIf(armNot8ByteAligned(pSrcDst), OMX_Sts_BadArgErr);
    armRetArgErrIf(srcdstStep & 7,              OMX_Sts_BadArgErr);    
    armRetArgErrIf(pAlpha == NULL,              OMX_Sts_BadArgErr);
    armRetArgErrIf(pBeta == NULL,               OMX_Sts_BadArgErr);
    armRetArgErrIf(pThresholds == NULL,         OMX_Sts_BadArgErr);
    armRetArgErrIf(armNot4ByteAligned(pThresholds), OMX_Sts_BadArgErr);
    armRetArgErrIf(pBS == NULL,                     OMX_Sts_BadArgErr);
    armRetArgErrIf(armNot4ByteAligned(pBS),         OMX_Sts_BadArgErr);

    errorCode = omxVCM4P10_FilterDeblockingLuma_VerEdge_I(
        pSrcDst, srcdstStep, pAlpha, pBeta, pThresholds, pBS);

    armRetArgErrIf(errorCode != OMX_Sts_NoErr, errorCode)
    
    errorCode = omxVCM4P10_FilterDeblockingLuma_HorEdge_I(
        pSrcDst, srcdstStep, pAlpha+2, pBeta+2, pThresholds+16, pBS+16);

    return errorCode;
}
