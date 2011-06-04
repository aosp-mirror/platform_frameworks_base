/* ----------------------------------------------------------------
 *
 * 
 * File Name:  omxVCM4P10_TransformDequantLumaDCFromPair.c
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
 * Dequantize Luma DC block
 */

static void DequantLumaDC4x4(
     OMX_S16* pDst,
     OMX_INT QP        
)
{
    int Shift = (QP/6)-2 ;
    int Scale = armVCM4P10_VMatrix[QP%6][0];
    int i, Round, Value;

    if (Shift >= 0)
    {
        for (i=0; i<16; i++)
        {
            Value = (pDst[i] * Scale) << Shift;
            pDst[i] = (OMX_S16)Value;
        }
    }
    else
    {
        Shift = -Shift;;
        Round = 1<<(Shift-1);

        for (i=0; i<16; i++)
        {
            Value = (pDst[i] * Scale + Round) >> Shift;
            pDst[i] = (OMX_S16)Value;
        }
    }
}

 

/*
 * Description:
 * Inverse Transform DC 4x4 Coefficients
 */
static void InvTransformDC4x4(OMX_S16* pData)
{
    int i;

    /* Transform rows */
    for (i=0; i<16; i+=4)
    {
        int c0 = pData[i+0];
        int c1 = pData[i+1];
        int c2 = pData[i+2];
        int c3 = pData[i+3];
        pData[i+0] = (OMX_S16)(c0+c1+c2+c3);
        pData[i+1] = (OMX_S16)(c0+c1-c2-c3);
        pData[i+2] = (OMX_S16)(c0-c1-c2+c3);
        pData[i+3] = (OMX_S16)(c0-c1+c2-c3);
    }

    /* Transform columns */
    for (i=0; i<4; i++)
    {
        int c0 = pData[i+0];
        int c1 = pData[i+4];
        int c2 = pData[i+8];
        int c3 = pData[i+12];
        pData[i+0] = (OMX_S16)(c0+c1+c2+c3);
        pData[i+4] = (OMX_S16)(c0+c1-c2-c3);
        pData[i+8] = (OMX_S16)(c0-c1-c2+c3);
        pData[i+12] = (OMX_S16)(c0-c1+c2-c3);
    }
}


/**
 * Function:  omxVCM4P10_TransformDequantLumaDCFromPair   (6.3.4.2.1)
 *
 * Description:
 * Reconstructs the 4x4 LumaDC block from the coefficient-position pair 
 * buffer, performs integer inverse, and dequantization for 4x4 LumaDC 
 * coefficients, and updates the pair buffer pointer to the next non-empty 
 * block. 
 *
 * Input Arguments:
 *   
 *   ppSrc - Double pointer to residual coefficient-position pair buffer 
 *            output by CALVC decoding 
 *   QP - Quantization parameter QpY 
 *
 * Output Arguments:
 *   
 *   ppSrc - *ppSrc is updated to the start of next non empty block 
 *   pDst - Pointer to the reconstructed 4x4 LumaDC coefficients buffer; must 
 *            be aligned on a 8-byte boundary. 
 *
 * Return Value:
 *    OMX_Sts_NoErr, if the function runs without error.
 *    OMX_Sts_BadArgErr - bad arguments: if one of the following cases occurs: 
 *    -    ppSrc or pDst is NULL. 
 *    -    pDst is not 8 byte aligned. 
 *    -    QP is not in the range of [0-51]. 
 *
 */

OMXResult omxVCM4P10_TransformDequantLumaDCFromPair(
     const OMX_U8 **ppSrc,
     OMX_S16* pDst,
     OMX_INT QP        
 )
{
    armRetArgErrIf(ppSrc  == NULL,           OMX_Sts_BadArgErr);
    armRetArgErrIf(*ppSrc == NULL,           OMX_Sts_BadArgErr);
    armRetArgErrIf(pDst   == NULL,           OMX_Sts_BadArgErr);
    armRetArgErrIf(armNot8ByteAligned(pDst), OMX_Sts_BadArgErr);
    armRetArgErrIf(QP<0,                     OMX_Sts_BadArgErr);
    armRetArgErrIf(QP>51,                    OMX_Sts_BadArgErr);

    armVCM4P10_UnpackBlock4x4(ppSrc, pDst);
    /*InvTransformDequantLumaDC4x4(pDst, QP);*/
    InvTransformDC4x4(pDst);
    DequantLumaDC4x4(pDst, QP);

    return OMX_Sts_NoErr;
}

/* End of file */
