/* ----------------------------------------------------------------
 *
 * 
 * File Name:  omxVCM4P10_PredictIntra_16x16.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 *
 * H.264 16x16 intra prediction module
 * 
 */
 
#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armCOMM.h"
#include "armVC.h"

/**
 * Function:  omxVCM4P10_PredictIntra_16x16   (6.3.3.1.2)
 *
 * Description:
 * Perform Intra_16x16 prediction for luma samples. If the upper-right block 
 * is not available, then duplication work should be handled inside the 
 * function. Users need not define them outside. 
 *
 * Input Arguments:
 *   
 *   pSrcLeft - Pointer to the buffer of 16 left pixels: p[x, y] (x = -1, y = 
 *            0..15) 
 *   pSrcAbove - Pointer to the buffer of 16 above pixels: p[x,y] (x = 0..15, 
 *            y= -1); must be aligned on a 16-byte boundary. 
 *   pSrcAboveLeft - Pointer to the above left pixels: p[x,y] (x = -1, y = -1) 
 *   leftStep - Step of left pixel buffer; must be a multiple of 16. 
 *   dstStep - Step of the destination buffer; must be a multiple of 16. 
 *   predMode - Intra_16x16 prediction mode, please refer to section 3.4.1. 
 *   availability - Neighboring 16x16 MB availability flag. Refer to 
 *                  section 3.4.4. 
 *
 * Output Arguments:
 *   
 *   pDst -Pointer to the destination buffer; must be aligned on a 16-byte 
 *            boundary. 
 *
 * Return Value:
 *    If the function runs without error, it returns OMX_Sts_NoErr. 
 *    If one of the following cases occurs, the function returns 
 *              OMX_Sts_BadArgErr: 
 *    pDst is NULL. 
 *    dstStep < 16. or dstStep is not a multiple of 16. 
 *    leftStep is not a multiple of 16. 
 *    predMode is not in the valid range of enumeration 
 *              OMXVCM4P10Intra16x16PredMode 
 *    predMode is OMX_VC_16X16_VERT, but availability doesn't set 
 *              OMX_VC_UPPER indicating p[x,-1] (x = 0..15) is not available. 
 *    predMode is OMX_VC_16X16_HOR, but availability doesn't set OMX_VC_LEFT 
 *              indicating p[-1,y] (y = 0..15) is not available. 
 *    predMode is OMX_VC_16X16_PLANE, but availability doesn't set 
 *              OMX_VC_UPPER_LEFT or OMX_VC_UPPER or OMX_VC_LEFT indicating 
 *              p[x,-1](x = 0..15), or p[-1,y] (y = 0..15), or p[-1,-1] is not 
 *              available. 
 *    availability sets OMX_VC_UPPER, but pSrcAbove is NULL. 
 *    availability sets OMX_VC_LEFT, but pSrcLeft is NULL. 
 *    availability sets OMX_VC_UPPER_LEFT, but pSrcAboveLeft is NULL. 
 *    either pSrcAbove or pDst is not aligned on a 16-byte boundary.  
 *
 * Note: 
 *     pSrcAbove, pSrcAbove, pSrcAboveLeft may be invalid pointers if 
 *     they are not used by intra prediction implied in predMode. 
 * Note: 
 *     OMX_VC_UPPER_RIGHT is not used in intra_16x16 luma prediction. 
 *
 */
OMXResult omxVCM4P10_PredictIntra_16x16(
    const OMX_U8* pSrcLeft, 
    const OMX_U8 *pSrcAbove, 
    const OMX_U8 *pSrcAboveLeft, 
    OMX_U8* pDst, 
    OMX_INT leftStep, 
    OMX_INT dstStep, 
    OMXVCM4P10Intra16x16PredMode predMode, 
    OMX_S32 availability)
{
    int x,y,Sum,Count;
    int H,V,a,b,c;

    armRetArgErrIf(pDst == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(dstStep < 16,  OMX_Sts_BadArgErr);
    armRetArgErrIf((dstStep % 16) != 0,  OMX_Sts_BadArgErr);
    armRetArgErrIf((leftStep % 16) != 0,  OMX_Sts_BadArgErr);
    armRetArgErrIf(armNot16ByteAligned(pSrcAbove), OMX_Sts_BadArgErr);
    armRetArgErrIf(armNot16ByteAligned(pDst), OMX_Sts_BadArgErr);        
    armRetArgErrIf((availability & OMX_VC_UPPER)      && pSrcAbove     == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf((availability & OMX_VC_LEFT )      && pSrcLeft      == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf((availability & OMX_VC_UPPER_LEFT) && pSrcAboveLeft == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(predMode==OMX_VC_16X16_VERT  && !(availability & OMX_VC_UPPER),      OMX_Sts_BadArgErr);
    armRetArgErrIf(predMode==OMX_VC_16X16_HOR   && !(availability & OMX_VC_LEFT),       OMX_Sts_BadArgErr);
    armRetArgErrIf(predMode==OMX_VC_16X16_PLANE && !(availability & OMX_VC_UPPER),      OMX_Sts_BadArgErr);
    armRetArgErrIf(predMode==OMX_VC_16X16_PLANE && !(availability & OMX_VC_UPPER_LEFT), OMX_Sts_BadArgErr);
    armRetArgErrIf(predMode==OMX_VC_16X16_PLANE && !(availability & OMX_VC_LEFT),       OMX_Sts_BadArgErr);
    armRetArgErrIf((unsigned)predMode > OMX_VC_16X16_PLANE,  OMX_Sts_BadArgErr);

    switch (predMode)
    {
    case OMX_VC_16X16_VERT:
        for (y=0; y<16; y++)
        {
            for (x=0; x<16; x++)
            {
                pDst[y*dstStep+x] = pSrcAbove[x];
            }
        }
        break;

    case OMX_VC_16X16_HOR:
        for (y=0; y<16; y++)
        {
            for (x=0; x<16; x++)
            {
                pDst[y*dstStep+x] = pSrcLeft[y*leftStep];
            }
        }
        break;

    case OMX_VC_16X16_DC:
        /* This can always be used even if no blocks available */
        Sum = 0;
        Count = 0;
        if (availability & OMX_VC_LEFT)
        {
            for (y=0; y<16; y++)
            {
                Sum += pSrcLeft[y*leftStep];
            }
            Count++;
        }
        if (availability & OMX_VC_UPPER)
        {
            for (x=0; x<16; x++)
            {
                Sum += pSrcAbove[x];
            }
            Count++;
        }
        if (Count==0)
        {
            Sum = 128;
        }
        else if (Count==1)
        {
            Sum = (Sum + 8) >> 4;
        }
        else /* Count = 2 */
        {
            Sum = (Sum + 16) >> 5;
        }
        for (y=0; y<16; y++)
        {
            for (x=0; x<16; x++)
            {
                pDst[y*dstStep+x] = (OMX_U8)Sum;
            }
        }
        break;

    case OMX_VC_16X16_PLANE:
        H = 8*(pSrcAbove[15] - pSrcAboveLeft[0]);
        for (x=6; x>=0; x--)
        {
            H += (x+1)*(pSrcAbove[8+x] - pSrcAbove[6-x]);
        }
        V = 8*(pSrcLeft[15*leftStep] - pSrcAboveLeft[0]);
        for (y=6; y>=0; y--)
        {
            V += (y+1)*(pSrcLeft[(8+y)*leftStep] - pSrcLeft[(6-y)*leftStep]);
        }
        a = 16*(pSrcAbove[15] + pSrcLeft[15*leftStep]);
        b = (5*H+32)>>6;
        c = (5*V+32)>>6;
        for (y=0; y<16; y++)
        {
            for (x=0; x<16; x++)
            {
                Sum = (a + b*(x-7) + c*(y-7) + 16)>>5;
                pDst[y*dstStep+x] = (OMX_U8)armClip(0,255,Sum);
            }
        }
        break;
    }

    return OMX_Sts_NoErr;
}

