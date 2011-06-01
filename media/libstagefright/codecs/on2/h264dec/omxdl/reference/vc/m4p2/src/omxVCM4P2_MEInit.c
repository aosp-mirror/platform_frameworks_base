/**
 * 
 * File Name:  omxVCM4P2_MEInit.c
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
 * Function:  omxVCM4P2_MEInit   (6.2.4.1.2)
 *
 * Description:
 * Initializes the vendor-specific specification structure required for the 
 * following motion estimation functions:  BlockMatch_Integer_8x8, 
 * BlockMatch_Integer_16x16, and MotionEstimationMB. Memory for the 
 * specification structure *pMESpec must be allocated prior to calling the 
 * function, and should be aligned on a 4-byte boundary.  Following 
 * initialization by this function, the vendor-specific structure *pMESpec 
 * should contain an implementation-specific representation of all motion 
 * estimation parameters received via the structure pMEParams, for example  
 * rndVal, searchRange, etc.  The number of bytes required for the 
 * specification structure can be determined using the function 
 * omxVCM4P2_MEGetBufSize. 
 *
 * Input Arguments:
 *   
 *   MEmode - motion estimation mode; available modes are defined by the 
 *            enumerated type OMXVCM4P2MEMode 
 *   pMEParams - motion estimation parameters 
 *   pMESpec - pointer to the uninitialized ME specification structure 
 *
 * Output Arguments:
 *   
 *   pMESpec - pointer to the initialized ME specification structure 
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

OMXResult omxVCM4P2_MEInit(
    OMXVCM4P2MEMode MEMode, 
    const OMXVCM4P2MEParams *pMEParams, 
    void *pMESpec
   )
{
    ARMVCM4P2_MESpec *armMESpec = (ARMVCM4P2_MESpec *) pMESpec;
    
    armRetArgErrIf(!pMEParams, OMX_Sts_BadArgErr);
    armRetArgErrIf(!pMESpec, OMX_Sts_BadArgErr);
    armRetArgErrIf((MEMode != OMX_VC_M4P2_FAST_SEARCH) && 
                   (MEMode != OMX_VC_M4P2_FULL_SEARCH), OMX_Sts_BadArgErr);
    armRetArgErrIf(pMEParams->searchRange <= 0, OMX_Sts_BadArgErr);
    
    armMESpec->MEParams.searchEnable8x8     = pMEParams->searchEnable8x8;
    armMESpec->MEParams.halfPelSearchEnable = pMEParams->halfPelSearchEnable;
    armMESpec->MEParams.searchRange         = pMEParams->searchRange;        
    armMESpec->MEParams.rndVal              = pMEParams->rndVal;
    armMESpec->MEMode                       = MEMode;
    
    return OMX_Sts_NoErr;
}

/* End of file */
