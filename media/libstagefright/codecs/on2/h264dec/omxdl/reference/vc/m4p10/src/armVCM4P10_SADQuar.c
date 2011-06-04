/**
 * 
 * File Name:  armVCM4P10_SADQuar.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 * Description:
 * This function will calculate SAD of pSrc with average of two Ref blocks
 * 
 */

#include "omxtypes.h"
#include "armOMX.h"

#include "armVC.h"
#include "armCOMM.h"

/**
 * Function: armVCM4P10_SADQuar
 *
 * Description:
 * This function calculates the SAD between one block (pSrc) and the 
 * average of the other two (pSrcRef0 and pSrcRef1)
 *
 * Remarks:
 *
 * [in]		pSrc				Pointer to the original block
 * [in]		pSrcRef0		Pointer to reference block 0
 * [in]		pSrcRef1		Pointer to reference block 1
 * [in]		iSrcStep 		Step of the original block buffer
 * [in]		iRefStep0		Step of reference block 0 
 * [in]		iRefStep1 	Step of reference block 1 
 * [in]		iHeight			Height of the block
 * [in]		iWidth			Width of the block
 * [out]	pDstSAD			Pointer of result SAD
 *
 * Return Value:
 * Standard OMXResult value.
 *
 */
OMXResult armVCM4P10_SADQuar(
	const OMX_U8* 	pSrc,
    const OMX_U8* 	pSrcRef0,
	const OMX_U8* 	pSrcRef1,	
    OMX_U32 	iSrcStep,
    OMX_U32		iRefStep0,
    OMX_U32		iRefStep1,
    OMX_U32*	pDstSAD,
    OMX_U32     iHeight,
    OMX_U32     iWidth
)
{
    OMX_INT     x, y;
    OMX_S32     SAD = 0;

    /* check for argument error */
    armRetArgErrIf(pSrc == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf(pSrcRef0 == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf(pSrcRef1 == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf(pDstSAD == NULL, OMX_Sts_BadArgErr)

    for (y = 0; y < iHeight; y++)
    {
        for (x = 0; x < iWidth; x++)
        {
            SAD += armAbs(pSrc [y * iSrcStep + x] - ((
                    pSrcRef0 [y * iRefStep0 + x] + 
                    pSrcRef1 [y * iRefStep1 + x] + 1) >> 1));
        }
    }
        
    *pDstSAD = SAD;

    return OMX_Sts_NoErr;
}

/*****************************************************************************
 *                              END OF FILE
 *****************************************************************************/

