/**
 * 
 * File Name:  armVCM4P2_SetPredDir.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 * 
 * Description:
 * Contains module for detecting the prediction direction
 *
 */
 
#include "omxtypes.h"
#include "armOMX.h"

#include "armVC.h"
#include "armCOMM.h"

/**
 * Function: armVCM4P2_SetPredDir
 *
 * Description:
 * Performs detecting the prediction direction
 *
 * Remarks:
 *
 * Parameters:
 * [in] blockIndex  block index indicating the component type and
 *                          position as defined in subclause 6.1.3.8, of ISO/IEC
 *                          14496-2. Furthermore, indexes 6 to 9 indicate the
 *                          alpha blocks spatially corresponding to luminance
 *                          blocks 0 to 3 in the same macroblock.
 * [in] pCoefBufRow pointer to the coefficient row buffer
 * [in] pQpBuf      pointer to the quantization parameter buffer
 * [out]    predQP      quantization parameter of the predictor block
 * [out]    predDir     indicates the prediction direction which takes one
 *                          of the following values:
 *                          OMX_VC_HORIZONTAL    predict horizontally
 *                          OMX_VC_VERTICAL      predict vertically
 *
 * Return Value:
 * Standard OMXResult result. See enumeration for possible result codes.
 *
 */

OMXResult armVCM4P2_SetPredDir(
     OMX_INT blockIndex,
     OMX_S16 *pCoefBufRow,
     OMX_S16 *pCoefBufCol,
     OMX_INT *predDir,
     OMX_INT *predQP,
     const OMX_U8 *pQpBuf
)
{
    OMX_U8  blockDCLeft;
    OMX_U8  blockDCTop;
    OMX_U8  blockDCTopLeft;

    if (blockIndex == 3)
    {
        blockDCTop = *(pCoefBufCol - 8);
    }
    else
    {
        blockDCTop = *pCoefBufRow;
    }
    blockDCLeft = *pCoefBufCol;
    blockDCTopLeft = *(pCoefBufRow - 8);

    if (armAbs(blockDCLeft - blockDCTopLeft) < armAbs(blockDCTopLeft \
                                                        - blockDCTop))
    {
        *predDir = OMX_VC_VERTICAL;
        *predQP = pQpBuf[1];
    }
    else
    {
        *predDir = OMX_VC_HORIZONTAL;
        *predQP = pQpBuf[0];
    }
    return OMX_Sts_NoErr;
}


/*End of File*/
