/* ----------------------------------------------------------------
 *
 * 
 * File Name:  omxVCM4P10_DeblockChroma_I.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 *
 * H.264 intra chroma deblock
 * 
 */
 
#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armCOMM.h"
#include "armVC.h"

/**
 * Function:  omxVCM4P10_DeblockChroma_I   (6.3.3.3.6)
 *
 * Description:
 * Performs in-place deblocking filtering on all edges of the chroma 
 * macroblock (16x16). 
 *
 * Input Arguments:
 *   
 *   pSrcDst - pointer to the input macroblock; must be 8-byte aligned. 
 *   srcdstStep - step of the arrays; must be a multiple of 8. 
 *   pAlpha - pointer to a 2x2 array of alpha thresholds, organized as 
 *            follows: {external vertical edge, internal vertical edge, 
 *            external horizontal edge, internal horizontal edge }.  Per 
 *            [ISO14496-10] alpha values must be in the range [0,255]. 
 *   pBeta - pointer to a 2x2 array of Beta Thresholds, organized as follows: 
 *            { external vertical edge, internal vertical edge, external 
 *            horizontal edge, internal horizontal edge }.  Per [ISO14496-10] 
 *            beta values must be in the range [0,18]. 
 *   pThresholds - array of size 8x2 of Thresholds (TC0) (values for the left 
 *            or above edge of each 4x2 or 2x4 block, arranged in vertical 
 *            block order and then in horizontal block order); must be aligned 
 *            on a 4-byte boundary. Per [ISO14496-10] values must be in the 
 *            range [0,25]. 
 *   pBS - array of size 16x2 of BS parameters (arranged in scan block order 
 *            for vertical edges and then horizontal edges); valid in the 
 *            range [0,4] with the following restrictions: i) pBS[i]== 4 may 
 *            occur only for 0<=i<=3, ii) pBS[i]== 4 if and only if pBS[i^3]== 
 *            4.  Must be 4-byte aligned. 
 *
 * Output Arguments:
 *   
 *   pSrcDst - pointer to filtered output macroblock. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments 
 *    -    one or more of the following pointers is NULL: pSrcDst, pAlpha, 
 *              pBeta, pThresholds, or pBS. pSrcDst is not 8-byte aligned. 
 *              either pThresholds or pBS is not 4-byte aligned. 
 *    -   one or more entries in the table pAlpha[0..3] is outside the range 
 *              [0,255]. 
 *    -   one or more entries in the table pBeta[0..3] is outside the range 
 *              [0,18]. 
 *    -   one or more entries in the table pThresholds[0..15]is outside of 
 *              the range [0,25]. 
 *    -   pBS is out of range, i.e., one of the following conditions is true: 
 *            pBS[i]<0, pBS[i]>4, pBS[i]==4  for i>=4, or 
 *            (pBS[i]==4 && pBS[i^3]!=4) for 0<=i<=3. 
 *    -   srcdstStep is not a multiple of 8. 
 *
 */
OMXResult omxVCM4P10_DeblockChroma_I(
	OMX_U8* pSrcDst, 
	OMX_S32 srcdstStep, 
	const OMX_U8* pAlpha, 
	const OMX_U8* pBeta, 
	const OMX_U8* pThresholds,
    const OMX_U8 *pBS
)
{
    OMXResult errorCode;
    
    armRetArgErrIf(pSrcDst == NULL,                 OMX_Sts_BadArgErr);
    armRetArgErrIf(armNot8ByteAligned(pSrcDst),     OMX_Sts_BadArgErr);
    armRetArgErrIf(srcdstStep & 7,                  OMX_Sts_BadArgErr);
    armRetArgErrIf(pAlpha == NULL,                  OMX_Sts_BadArgErr);
    armRetArgErrIf(pBeta == NULL,                   OMX_Sts_BadArgErr);
    armRetArgErrIf(pThresholds == NULL,             OMX_Sts_BadArgErr);
    armRetArgErrIf(armNot4ByteAligned(pThresholds), OMX_Sts_BadArgErr);
    armRetArgErrIf(pBS == NULL,                     OMX_Sts_BadArgErr);
    armRetArgErrIf(armNot4ByteAligned(pBS),         OMX_Sts_BadArgErr);

    errorCode = omxVCM4P10_FilterDeblockingChroma_VerEdge_I(
        pSrcDst, srcdstStep, pAlpha, pBeta, pThresholds, pBS);

    armRetArgErrIf(errorCode != OMX_Sts_NoErr, errorCode)
    
    errorCode = omxVCM4P10_FilterDeblockingChroma_HorEdge_I(
        pSrcDst, srcdstStep, pAlpha+2, pBeta+2, pThresholds+8, pBS+16);

    return errorCode;
}
