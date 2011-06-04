/* ----------------------------------------------------------------
 *
 * 
 * File Name:  omxVCM4P10_TransformDequantChromaDCFromPair.c
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
 * Dequantize Chroma 2x2 DC block
 */

static void DequantChromaDC2x2(
     OMX_S16* pDst,
     OMX_INT QP        
)
{
    int Shift = (QP/6)-1 ;
    int Scale = armVCM4P10_VMatrix[QP%6][0];
    int i, Value;

    if (Shift >= 0)
    {
        for (i=0; i<4; i++)
        {
            Value = (pDst[i] * Scale) << Shift;
            pDst[i] = (OMX_S16)Value;
        }
    }
    else
    {
        for (i=0; i<4; i++)
        {
            Value = (pDst[i] * Scale) >> 1;
            pDst[i] = (OMX_S16)Value;
        }
    }
}
 

/*
 * Description:
 * Inverse Transform DC 2x2 Coefficients
 */

static void InvTransformDC2x2(OMX_S16* pData)
{
    int c00 = pData[0];
    int c01 = pData[1];
    int c10 = pData[2];
    int c11 = pData[3];

    int d00 = c00 + c01;
    int d01 = c00 - c01;
    int d10 = c10 + c11;
    int d11 = c10 - c11;

    pData[0] = (OMX_S16)(d00 + d10);
    pData[1] = (OMX_S16)(d01 + d11);
    pData[2] = (OMX_S16)(d00 - d10);
    pData[3] = (OMX_S16)(d01 - d11);
}


/**
 * Function:  omxVCM4P10_TransformDequantChromaDCFromPair   (6.3.4.2.2)
 *
 * Description:
 * Reconstruct the 2x2 ChromaDC block from coefficient-position pair buffer, 
 * perform integer inverse transformation, and dequantization for 2x2 chroma 
 * DC coefficients, and update the pair buffer pointer to next non-empty 
 * block. 
 *
 * Input Arguments:
 *   
 *   ppSrc - Double pointer to residual coefficient-position pair buffer 
 *            output by CALVC decoding 
 *   QP - Quantization parameter QpC 
 *
 * Output Arguments:
 *   
 *   ppSrc - *ppSrc is updated to the start of next non empty block 
 *   pDst - Pointer to the reconstructed 2x2 ChromaDC coefficients buffer; 
 *            must be aligned on a 4-byte boundary. 
 *
 * Return Value:
 *    OMX_Sts_NoErr, if the function runs without error.
 *    OMX_Sts_BadArgErr - bad arguments: if one of the following cases occurs: 
 *    -    ppSrc or pDst is NULL. 
 *    -    pDst is not 4-byte aligned. 
 *    -    QP is not in the range of [0-51]. 
 *
 */

OMXResult omxVCM4P10_TransformDequantChromaDCFromPair(
     const OMX_U8 **ppSrc,
     OMX_S16* pDst,
     OMX_INT QP        
 )
{
    armRetArgErrIf(ppSrc  == NULL,           OMX_Sts_BadArgErr);
    armRetArgErrIf(*ppSrc == NULL,           OMX_Sts_BadArgErr);
    armRetArgErrIf(pDst   == NULL,           OMX_Sts_BadArgErr);
    armRetArgErrIf(armNot4ByteAligned(pDst), OMX_Sts_BadArgErr);
    armRetArgErrIf(QP<0,                     OMX_Sts_BadArgErr);
    armRetArgErrIf(QP>51,                    OMX_Sts_BadArgErr);

    armVCM4P10_UnpackBlock2x2(ppSrc, pDst);
    InvTransformDC2x2(pDst);
    DequantChromaDC2x2(pDst, QP);

    return OMX_Sts_NoErr;
}

/* End of file */
