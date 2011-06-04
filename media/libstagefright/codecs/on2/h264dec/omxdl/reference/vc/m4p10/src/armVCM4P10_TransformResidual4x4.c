/* ----------------------------------------------------------------
 *
 * 
 * File Name:  armVCM4P10_TransformResidual4x4.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 *
 * H.264 transform module
 * 
 */
 
#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armCOMM.h"
#include "armVC.h"
 
/*
 * Description:
 * Transform Residual 4x4 Coefficients
 *
 * Parameters:
 * [in]  pSrc		Source 4x4 block
 * [out] pDst		Destination 4x4 block
 *
 */

void armVCM4P10_TransformResidual4x4(OMX_S16* pDst, OMX_S16 *pSrc)
{
    int i;

    /* Transform rows */
    for (i=0; i<16; i+=4)
    {
        int d0 = pSrc[i+0];
        int d1 = pSrc[i+1];
        int d2 = pSrc[i+2];
        int d3 = pSrc[i+3];
        int e0 = d0 + d2;
        int e1 = d0 - d2;
        int e2 = (d1>>1) - d3;
        int e3 = d1 + (d3>>1);
        int f0 = e0 + e3;
        int f1 = e1 + e2;
        int f2 = e1 - e2;
        int f3 = e0 - e3;
        pDst[i+0] = (OMX_S16)f0;
        pDst[i+1] = (OMX_S16)f1;
        pDst[i+2] = (OMX_S16)f2;
        pDst[i+3] = (OMX_S16)f3;
    }

    /* Transform columns */
    for (i=0; i<4; i++)
    {
        int f0 = pDst[i+0];
        int f1 = pDst[i+4];
        int f2 = pDst[i+8];
        int f3 = pDst[i+12];
        int g0 = f0 + f2;
        int g1 = f0 - f2;
        int g2 = (f1>>1) - f3;
        int g3 = f1 + (f3>>1);
        int h0 = g0 + g3;
        int h1 = g1 + g2;
        int h2 = g1 - g2;
        int h3 = g0 - g3;
        pDst[i+0] = (OMX_S16)((h0+32)>>6);
        pDst[i+4] = (OMX_S16)((h1+32)>>6);
        pDst[i+8] = (OMX_S16)((h2+32)>>6);
        pDst[i+12] = (OMX_S16)((h3+32)>>6);
    }
}

