/**
 * 
 * File Name:  omxVCM4P2_BlockMatch_Half_8x8.c
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
 * Function:  omxVCM4P2_BlockMatch_Half_8x8   (6.2.4.2.4)
 *
 * Description:
 * Performs an 8x8 block match with half-pixel resolution. Returns the 
 * estimated motion vector and associated minimum SAD.  This function 
 * estimates the half-pixel motion vector by interpolating the integer 
 * resolution motion vector referenced by the input parameter pSrcDstMV, i.e., 
 * the initial integer MV is generated externally.  The input parameters 
 * pSrcRefBuf and pSearchPointRefPos should be shifted by the winning MV of 
 * 8x8 integer search prior to calling BlockMatch_Half_8x8. The function 
 * BlockMatch_Integer_8x8 may be used for integer motion estimation. 
 *
 * Input Arguments:
 *   
 *   pSrcRefBuf - pointer to the reference Y plane; points to the reference 
 *            block that corresponds to the location of the current 8x8 block 
 *            in the current plane. 
 *   refWidth - width of the reference plane 
 *   pRefRect - reference plane valid region rectangle 
 *   pSrcCurrBuf - pointer to the current block in the current macroblock 
 *            buffer extracted from the original plane (linear array, 128 
 *            entries); must be aligned on a 8-byte boundary.  The number of 
 *            bytes between lines (step) is 16. 
 *   pSearchPointRefPos - position of the starting point for half pixel 
 *            search (specified in terms of integer pixel units) in the 
 *            reference plane. 
 *   rndVal - rounding control parameter: 0 - disabled; 1 - enabled. 
 *   pSrcDstMV - pointer to the initial MV estimate; typically generated 
 *            during a prior 8x8 integer search, specified in terms of 
 *            half-pixel units. 
 *
 * Output Arguments:
 *   
 *   pSrcDstMV - pointer to estimated MV 
 *   pDstSAD - pointer to minimum SAD 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments.  Returned if one of the following 
 *              conditions is true: 
 *    -    at least one of the following pointers is NULL: 
 *         pSrcRefBuf, pRefRect, pSrcCurrBuff, pSearchPointRefPos, pSrcDstMV
 *    -    pSrcCurrBuf is not 8-byte aligned 
 *
 */

OMXResult omxVCM4P2_BlockMatch_Half_8x8(
     const OMX_U8 *pSrcRefBuf,
     OMX_INT refWidth,
     const OMXRect *pRefRect,
     const OMX_U8 *pSrcCurrBuf,
     const OMXVCM4P2Coordinate *pSearchPointRefPos,
     OMX_INT rndVal,
     OMXVCMotionVector *pSrcDstMV,
     OMX_INT *pDstSAD
)
{
    /* For a blocksize of 8x8 */
    OMX_U8 BlockSize = 8;
    
    /* Argument error checks */  
    armRetArgErrIf(pSrcRefBuf         == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pRefRect           == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pSrcCurrBuf        == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pSearchPointRefPos == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pSrcDstMV          == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(!armIs8ByteAligned(pSrcCurrBuf), OMX_Sts_BadArgErr);
   
    return (armVCM4P2_BlockMatch_Half(
                                pSrcRefBuf,
                                refWidth,
                                pRefRect,
                                pSrcCurrBuf,
                                pSearchPointRefPos,
                                rndVal,
                                pSrcDstMV,
                                pDstSAD,
                                BlockSize));

}

/* End of file */
