/* ----------------------------------------------------------------
 *
 * 
 * File Name:  omxVCM4P10_DequantTransformResidualFromPairAndAdd.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 *
 * H.264 inverse quantize and transform module
 * 
 */
 
#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armCOMM.h"
#include "armVC.h"

/*
 * Description:
 * Dequantize Luma AC block
 */

static void DequantLumaAC4x4(
     OMX_S16* pSrcDst,
     OMX_INT QP        
)
{
    const OMX_U8 *pVRow = &armVCM4P10_VMatrix[QP%6][0];
    int Shift = QP / 6;
    int i;
    OMX_S32 Value;

    for (i=0; i<16; i++)
    {

        Value = (pSrcDst[i] * pVRow[armVCM4P10_PosToVCol4x4[i]]) << Shift;
        pSrcDst[i] = (OMX_S16)Value;
    }
}

/**
 * Function:  omxVCM4P10_DequantTransformResidualFromPairAndAdd   (6.3.4.2.3)
 *
 * Description:
 * Reconstruct the 4x4 residual block from coefficient-position pair buffer, 
 * perform dequantization and integer inverse transformation for 4x4 block of 
 * residuals with previous intra prediction or motion compensation data, and 
 * update the pair buffer pointer to next non-empty block. If pDC == NULL, 
 * there re 16 non-zero AC coefficients at most in the packed buffer starting 
 * from 4x4 block position 0; If pDC != NULL, there re 15 non-zero AC 
 * coefficients at most in the packet buffer starting from 4x4 block position 
 * 1. 
 *
 * Input Arguments:
 *   
 *   ppSrc - Double pointer to residual coefficient-position pair buffer 
 *            output by CALVC decoding 
 *   pPred - Pointer to the predicted 4x4 block; must be aligned on a 4-byte 
 *            boundary 
 *   predStep - Predicted frame step size in bytes; must be a multiple of 4 
 *   dstStep - Destination frame step in bytes; must be a multiple of 4 
 *   pDC - Pointer to the DC coefficient of this block, NULL if it doesn't 
 *            exist 
 *   QP - QP Quantization parameter.  It should be QpC in chroma 4x4 block 
 *            decoding, otherwise it should be QpY. 
 *   AC - Flag indicating if at least one non-zero AC coefficient exists 
 *
 * Output Arguments:
 *   
 *   pDst - pointer to the reconstructed 4x4 block data; must be aligned on a 
 *            4-byte boundary 
 *
 * Return Value:
 *    OMX_Sts_NoErr, if the function runs without error.
 *    OMX_Sts_BadArgErr - bad arguments: if one of the following cases occurs: 
 *    -    pPred or pDst is NULL. 
 *    -    pPred or pDst is not 4-byte aligned. 
 *    -    predStep or dstStep is not a multiple of 4. 
 *    -    AC !=0 and Qp is not in the range of [0-51] or ppSrc == NULL. 
 *    -    AC ==0 && pDC ==NULL. 
 *
 */

OMXResult omxVCM4P10_DequantTransformResidualFromPairAndAdd(
     const OMX_U8 **ppSrc,
     const OMX_U8 *pPred,
     const OMX_S16 *pDC,
     OMX_U8 *pDst,
     OMX_INT predStep,
     OMX_INT dstStep,
     OMX_INT QP,
     OMX_INT AC        
)
{
    OMX_S16 pBuffer[16+4];
    OMX_S16 *pDelta;
    int i,x,y;
    
    armRetArgErrIf(pPred == NULL,            OMX_Sts_BadArgErr);
    armRetArgErrIf(armNot4ByteAligned(pPred),OMX_Sts_BadArgErr);
    armRetArgErrIf(pDst   == NULL,           OMX_Sts_BadArgErr);
    armRetArgErrIf(armNot4ByteAligned(pDst), OMX_Sts_BadArgErr);
    armRetArgErrIf(predStep & 3,             OMX_Sts_BadArgErr);
    armRetArgErrIf(dstStep & 3,              OMX_Sts_BadArgErr);
    armRetArgErrIf(AC!=0 && (QP<0),          OMX_Sts_BadArgErr);
    armRetArgErrIf(AC!=0 && (QP>51),         OMX_Sts_BadArgErr);
    armRetArgErrIf(AC!=0 && ppSrc==NULL,     OMX_Sts_BadArgErr);
    armRetArgErrIf(AC!=0 && *ppSrc==NULL,    OMX_Sts_BadArgErr);
    armRetArgErrIf(AC==0 && pDC==NULL,       OMX_Sts_BadArgErr);
    
    pDelta = armAlignTo8Bytes(pBuffer);    

    for (i=0; i<16; i++)
    {
        pDelta[i] = 0;
    }
    if (AC)
    {
        armVCM4P10_UnpackBlock4x4(ppSrc, pDelta);
        DequantLumaAC4x4(pDelta, QP);
    }
    if (pDC)
    {
        pDelta[0] = pDC[0];
    }
    armVCM4P10_TransformResidual4x4(pDelta,pDelta);

    for (y=0; y<4; y++)
    {
        for (x=0; x<4; x++)
        {
            pDst[y*dstStep+x] = (OMX_U8)armClip(0,255,pPred[y*predStep+x] + pDelta[4*y+x]);
        }
    }

    return OMX_Sts_NoErr;
}

/* End of file */
