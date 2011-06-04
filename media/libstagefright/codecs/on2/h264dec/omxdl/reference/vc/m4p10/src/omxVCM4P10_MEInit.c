/**
 * 
 * File Name:  omxVCM4P10_MEInit.c
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
 * Function:  omxVCM4P10_MEInit   (6.3.5.1.2)
 *
 * Description:
 * Initializes the vendor-specific specification structure required for the 
 * omxVCM4P10 motion estimation functions:  BlockMatch_Integer and 
 * MotionEstimationMB. Memory for the specification structure *pMESpec must be 
 * allocated prior to calling the function, and should be aligned on a 4-byte 
 * boundary.  The number of bytes required for the specification structure can 
 * be determined using the function omxVCM4P10_MEGetBufSize. Following 
 * initialization by this function, the vendor-specific structure *pMESpec 
 * should contain an implementation-specific representation of all motion 
 * estimation parameters received via the structure pMEParams, for example  
 * searchRange16x16, searchRange8x8, etc. 
 *
 * Input Arguments:
 *   
 *   MEmode - motion estimation mode; available modes are defined by the 
 *            enumerated type OMXVCM4P10MEMode 
 *   pMEParams - motion estimation parameters 
 *   pMESpec - pointer to the uninitialized ME specification structure 
 *
 * Output Arguments:
 *   
 *   pMESpec - pointer to the initialized ME specification structure 
 *
 * Return Value:
 *    OMX_Sts_NoErr, if the function runs without error.
 *    OMX_Sts_BadArgErr - bad arguments: if one of the following cases occurs: 
 *    -    pMEParams or pSize is NULL. 
 *    -    an invalid value was specified for the parameter MEmode 
 *    -    a negative or zero value was specified for one of the search ranges 
 *         (e.g.,  pMBParams >searchRange8x8, pMEParams->searchRange16x16, etc.) 
 *    -    either in isolation or in combination, one or more of the enables or 
 *         search ranges in the structure *pMEParams were configured such 
 *         that the requested behavior fails to comply with [ISO14496-10]. 
 *
 */

OMXResult omxVCM4P10_MEInit(
        OMXVCM4P10MEMode MEMode,
        const OMXVCM4P10MEParams *pMEParams,
        void *pMESpec
       )
{
    ARMVCM4P10_MESpec *armMESpec = (ARMVCM4P10_MESpec *) pMESpec;
    
    armRetArgErrIf(!pMEParams, OMX_Sts_BadArgErr);
    armRetArgErrIf(!pMESpec, OMX_Sts_BadArgErr);
    armRetArgErrIf((MEMode != OMX_VC_M4P10_FAST_SEARCH) && 
                   (MEMode != OMX_VC_M4P10_FULL_SEARCH), OMX_Sts_BadArgErr);
    armRetArgErrIf((pMEParams->searchRange16x16 <= 0) || 
                   (pMEParams->searchRange8x8 <= 0) || 
                   (pMEParams->searchRange4x4 <= 0), OMX_Sts_BadArgErr);
    
    armMESpec->MEParams.blockSplitEnable8x8 = pMEParams->blockSplitEnable8x8;
    armMESpec->MEParams.blockSplitEnable4x4 = pMEParams->blockSplitEnable4x4;
    armMESpec->MEParams.halfSearchEnable    = pMEParams->halfSearchEnable;
    armMESpec->MEParams.quarterSearchEnable = pMEParams->quarterSearchEnable;
    armMESpec->MEParams.intraEnable4x4      = pMEParams->intraEnable4x4;     
    armMESpec->MEParams.searchRange16x16    = pMEParams->searchRange16x16;   
    armMESpec->MEParams.searchRange8x8      = pMEParams->searchRange8x8;
    armMESpec->MEParams.searchRange4x4      = pMEParams->searchRange4x4;
    armMESpec->MEMode                       = MEMode;
    
    return OMX_Sts_NoErr;
}

/* End of file */
