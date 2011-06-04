/* ----------------------------------------------------------------
 *
 * 
 * File Name:  armVCM4P10_PredictIntraDC4x4.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 *
 * H.264 4x4 intra prediction module
 * 
 */
 
#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armCOMM.h"
#include "armVC.h"

/*
 * Description:
 * Perform DC style intra prediction, averaging upper and left block
 *
 * Parameters:
 * [in]	pSrcLeft		Pointer to the buffer of 16 left coefficients:
 *								p[x, y] (x = -1, y = 0..3)
 * [in]	pSrcAbove		Pointer to the buffer of 16 above coefficients:
 *								p[x,y] (x = 0..3, y = -1)
 * [in]	leftStep		Step of left coefficient buffer
 * [in]	dstStep			Step of the destination buffer
 * [in]	availability	Neighboring 16x16 MB availability flag
 * [out]	pDst			Pointer to the destination buffer
 *
 * Return Value:
 * None
 */

void armVCM4P10_PredictIntraDC4x4(
     const OMX_U8* pSrcLeft,
     const OMX_U8 *pSrcAbove,
     OMX_U8* pDst,
     OMX_INT leftStep,
     OMX_INT dstStep,
     OMX_S32 availability        
)
{
    int x, y, Sum=0, Count = 0;

    if (availability & OMX_VC_LEFT)
    {
        for (y=0; y<4; y++)
        {
            Sum += pSrcLeft[y*leftStep];
        }
        Count++;
    }
    if (availability & OMX_VC_UPPER)
    {
        for (x=0; x<4; x++)
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
        Sum = (Sum + 2) >> 2;
    }
    else /* Count = 2 */
    {
        Sum = (Sum + 4) >> 3;
    }
    for (y=0; y<4; y++)
    {
        for (x=0; x<4; x++)
        {
            pDst[y*dstStep+x] = (OMX_U8)Sum;
        }
    }
}
