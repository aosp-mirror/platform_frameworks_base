/* ----------------------------------------------------------------
 *
 * 
 * File Name:  armVCM4P10_DeBlockPixel.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 * 
 * H.264 luma deblock module
 * 
 */

#ifdef DEBUG_ARMVCM4P10_DEBLOCKPIXEL
#undef DEBUG_ON
#define DEBUG_ON
#endif /* DEBUG_ARMVCM4P10_DEBLOCKPIXEL */

#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armCOMM.h"
#include "armVC.h"

/*
 * Description
 * Deblock one boundary pixel
 *
 * Parameters:
 * [in]	pQ0         Pointer to pixel q0
 * [in] Step        Step between pixels q0 and q1
 * [in] tC0         Edge threshold value
 * [in] alpha       alpha threshold value
 * [in] beta        beta threshold value
 * [in] bS          deblocking strength
 * [in] ChromaFlag  True for chroma blocks
 * [out] pQ0        Deblocked pixels
 * 
 */

void armVCM4P10_DeBlockPixel(
    OMX_U8 *pQ0,    /* pointer to the pixel q0 */
    int Step,       /* step between pixels q0 and q1 */
    int tC0,        /* edge threshold value */
    int alpha,      /* alpha */
    int beta,       /* beta */
    int bS,         /* deblocking strength */
    int ChromaFlag
)
{
    int p3, p2, p1, p0, q0, q1, q2, q3;
    int ap, aq, delta;

    if (bS==0)
    {
        return;
    }

    p3 = pQ0[-4*Step];
    p2 = pQ0[-3*Step];
    p1 = pQ0[-2*Step];
    p0 = pQ0[-1*Step];
    q0 = pQ0[ 0*Step];
    q1 = pQ0[ 1*Step];
    q2 = pQ0[ 2*Step];
    q3 = pQ0[ 3*Step];

    if (armAbs(p0-q0)>=alpha || armAbs(p1-p0)>=beta || armAbs(q1-q0)>=beta)
    {
        DEBUG_PRINTF_10("DeBlockPixel: %02x %02x %02x %02x | %02x %02x %02x %02x alpha=%d beta=%d\n",
            p3, p2, p1, p0, q0, q1, q2, q3, alpha, beta);
        return;
    }

    ap = armAbs(p2 - p0);
    aq = armAbs(q2 - q0);

    if (bS < 4)
    {
        int tC = tC0;

        if (ChromaFlag)
        {
            tC++;
        }
        else
        {
            if (ap < beta)
            {
                tC++;
            }
            if (aq < beta)
            {
                tC++;
            }
        }
    
        delta = (((q0-p0)<<2) + (p1-q1) + 4) >> 3;
        delta = armClip(-tC, tC, delta);

        pQ0[-1*Step] = (OMX_U8)armClip(0, 255, p0 + delta);
        pQ0[ 0*Step] = (OMX_U8)armClip(0, 255, q0 - delta);

        if (ChromaFlag==0 && ap<beta)
        {
            delta = (p2 + ((p0+q0+1)>>1) - (p1<<1))>>1;
            delta = armClip(-tC0, tC0, delta);
            pQ0[-2*Step] = (OMX_U8)(p1 + delta);
        }

        if (ChromaFlag==0 && aq<beta)
        {
            delta = (q2 + ((p0+q0+1)>>1) - (q1<<1))>>1;
            delta = armClip(-tC0, tC0, delta);
            pQ0[ 1*Step] = (OMX_U8)(q1 + delta);
        }
    }
    else /* bS==4 */
    {
        if (ChromaFlag==0 && ap<beta && armAbs(p0-q0)<((alpha>>2)+2))
        {
            pQ0[-1*Step] = (OMX_U8)((p2 + 2*p1 + 2*p0 + 2*q0 + q1 + 4)>>3);
            pQ0[-2*Step] = (OMX_U8)((p2 + p1 + p0 + q0 + 2)>>2);
            pQ0[-3*Step] = (OMX_U8)((2*p3 + 3*p2 + p1 + p0 + q0 + 4)>>3);
        }
        else
        {
            pQ0[-1*Step] = (OMX_U8)((2*p1 + p0 + q1 + 2)>>2);
        }

        if (ChromaFlag==0 && aq<beta && armAbs(p0-q0)<((alpha>>2)+2))
        {
            pQ0[ 0*Step] = (OMX_U8)((q2 + 2*q1 + 2*q0 + 2*p0 + p1 + 4)>>3);
            pQ0[ 1*Step] = (OMX_U8)((q2 + q1 + p0 + q0 + 2)>>2);
            pQ0[ 2*Step] = (OMX_U8)((2*q3 + 3*q2 + q1 + q0 + p0 + 4)>>3);
        }
        else
        {
            pQ0[ 0*Step] = (OMX_U8)((2*q1 + q0 + p1 + 2)>>2);
        }
    }

    DEBUG_PRINTF_13("DeBlockPixel: %02x %02x %02x %02x | %02x %02x %02x %02x bS=%d -> %02x %02x %02x %02x\n",
        p3, p2, p1, p0, q0, q1, q2, q3, bS,
        pQ0[-2*Step], pQ0[-1*Step],pQ0[0*Step],pQ0[1*Step]);

}
