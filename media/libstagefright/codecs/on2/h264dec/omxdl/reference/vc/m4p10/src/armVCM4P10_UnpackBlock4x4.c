/* ----------------------------------------------------------------
 *
 * 
 * File Name:  armVCM4P10_UnpackBlock4x4.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 *
 * H.264 inverse quantize and transform helper module
 * 
 */
 
#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armVC.h"

/*
 * Description
 * Unpack a 4x4 block of coefficient-residual pair values
 *
 * Parameters:
 * [in]	ppSrc	Double pointer to residual coefficient-position pair
 *						buffer output by CALVC decoding
 * [out]	ppSrc	*ppSrc is updated to the start of next non empty block
 * [out]	pDst	Pointer to unpacked 4x4 block
 */

void armVCM4P10_UnpackBlock4x4(
     const OMX_U8 **ppSrc,
     OMX_S16* pDst
)
{
    const OMX_U8 *pSrc = *ppSrc;
    int i;
    int Flag, Value;

    for (i=0; i<16; i++)
    {
        pDst[i] = 0;
    }

    do
    {
        Flag  = *pSrc++;
        if (Flag & 0x10)
        {
            /* 16 bit */
            Value = *pSrc++;
            Value = Value | ((*pSrc++)<<8);
            if (Value & 0x8000)
            {
                Value -= 0x10000;
            }
        }
        else
        {
            /* 8 bit */
            Value = *pSrc++;
            if (Value & 0x80)
            {
                Value -= 0x100;
            }
        }
        i = Flag & 15;
        pDst[i] = (OMX_S16)Value;
    }
    while ((Flag & 0x20)==0);

    *ppSrc = pSrc;
}

/* End of file */
