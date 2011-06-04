/**
 * 
 * File Name:  omxVCM4P2_MEGetBufSize.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 *
 * Description:
 * Initialization modules for the vendor specific Motion Estimation structure.
 * 
 */

#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armVC.h"
#include "armCOMM.h"

/**
 * Function:  omxVCM4P2_MEGetBufSize   (6.2.4.1.1)
 *
 * Description:
 * Computes the size, in bytes, of the vendor-specific specification 
 * structure for the following motion estimation functions: 
 * BlockMatch_Integer_8x8, BlockMatch_Integer_16x16, and MotionEstimationMB. 
 *
 * Input Arguments:
 *   
 *   MEmode - motion estimation mode; available modes are defined by the 
 *            enumerated type OMXVCM4P2MEMode 
 *   pMEParams - motion estimation parameters 
 *
 * Output Arguments:
 *   
 *   pSize - pointer to the number of bytes required for the specification 
 *            structure 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - one or more of the following is true: 
 *    -    an invalid value was specified for the parameter MEmode 
 *    -    a negative or zero value was specified for the 
 *         parameter pMEParams->searchRange 
 *
 */

OMXResult omxVCM4P2_MEGetBufSize(
    OMXVCM4P2MEMode MEMode, 
    const OMXVCM4P2MEParams *pMEParams, 
    OMX_U32 *pSize
   )
{
    armRetArgErrIf(!pMEParams, OMX_Sts_BadArgErr);
    armRetArgErrIf(!pSize, OMX_Sts_BadArgErr);
    armRetArgErrIf(pMEParams->searchRange <= 0, OMX_Sts_BadArgErr);
    armRetArgErrIf((MEMode != OMX_VC_M4P10_FAST_SEARCH) &&
                   (MEMode != OMX_VC_M4P10_FULL_SEARCH), OMX_Sts_BadArgErr);
    
    *pSize = (OMX_INT) sizeof(ARMVCM4P2_MESpec);

    return OMX_Sts_NoErr;
}

/* End of file */
