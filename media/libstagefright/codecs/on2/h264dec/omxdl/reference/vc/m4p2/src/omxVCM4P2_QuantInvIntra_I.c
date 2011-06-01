/**
 * 
 * File Name:  omxVCM4P2_QuantInvIntra_I.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 *
 * Description:
 * Contains modules for intra inverse Quantization
 * 
 */ 

#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armCOMM.h"

/**
 * Function:  omxVCM4P2_QuantInvIntra_I   (6.2.5.3.2)
 *
 * Description:
 * Performs the second inverse quantization mode on an intra/inter coded 
 * block. Supports bits_per_pixel = 8. The output coefficients are clipped to 
 * the range [-2048, 2047]. 
 *
 * Input Arguments:
 *   
 *   pSrcDst - pointer to the input (quantized) intra/inter block; must be 
 *            aligned on a 16-byte boundary. 
 *   QP - quantization parameter (quantizer_scale) 
 *   videoComp - video component type of the current block. Takes one of the 
 *            following flags: OMX_VC_LUMINANCE, OMX_VC_CHROMINANCE (intra 
 *            version only). 
 *   shortVideoHeader - binary flag indicating presence of short_video_header 
 *            (intra version only). 
 *
 * Output Arguments:
 *   
 *   pSrcDst - pointer to the output (dequantized) intra/inter block 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments; one or more of the following is 
 *              true: 
 *    -    pSrcDst is NULL 
 *    -    QP <= 0 or QP >=31 
 *    -    videoComp is neither OMX_VC_LUMINANCE nor OMX_VC_CHROMINANCE. 
 *
 */

OMXResult omxVCM4P2_QuantInvIntra_I(
     OMX_S16 * pSrcDst,
     OMX_INT QP,
     OMXVCM4P2VideoComponent videoComp,
	 OMX_INT shortVideoHeader
)
{

    /* Initialized to remove compilation error */
    OMX_INT dcScaler = 0, coeffCount, Sign;

    /* Argument error checks */
    armRetArgErrIf(pSrcDst == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(((QP <= 0) || (QP >= 32)), OMX_Sts_BadArgErr);
	armRetArgErrIf(((videoComp != OMX_VC_LUMINANCE) && (videoComp != OMX_VC_CHROMINANCE)), OMX_Sts_BadArgErr);
    
    /* Calculate the DC scaler value */
    
    /* linear intra DC mode */
    if(shortVideoHeader)
    {
        dcScaler = 8;
    }
    /* nonlinear intra DC mode */
    else
    {
    
        if (videoComp == OMX_VC_LUMINANCE)
        {
            if (QP >= 1 && QP <= 4)
            {
                dcScaler = 8;
            }
            else if (QP >= 5 && QP <= 8)
            {
                dcScaler = 2 * QP;
            }
            else if (QP >= 9 && QP <= 24)
            {
                dcScaler = QP + 8;
            }
            else
            {
                dcScaler = (2 * QP) - 16;
            }
        }

        else if (videoComp == OMX_VC_CHROMINANCE)
        {
            if (QP >= 1 && QP <= 4)
            {
                dcScaler = 8;
            }
            else if (QP >= 5 && QP <= 24)
            {
                dcScaler = (QP + 13)/2;
            }
            else
            {
                dcScaler = QP - 6;
            }
        }
    }
    /* Dequant the DC value, this applies to both the methods */
    pSrcDst[0] = pSrcDst[0] * dcScaler;

    /* Saturate */
    pSrcDst[0] = armClip (-2048, 2047, pSrcDst[0]);

    /* Second Inverse quantisation method */
    for (coeffCount = 1; coeffCount < 64; coeffCount++)
    {
        /* check sign */
        Sign =  armSignCheck (pSrcDst[coeffCount]);  

        if (QP & 0x1)
        {
            pSrcDst[coeffCount] = (2* armAbs(pSrcDst[coeffCount]) + 1) * QP;
            pSrcDst[coeffCount] *= Sign;
        }
        else
        {
            pSrcDst[coeffCount] =
                                (2* armAbs(pSrcDst[coeffCount]) + 1) * QP - 1;
            pSrcDst[coeffCount] *= Sign;
        }

        /* Saturate */
        pSrcDst[coeffCount] = armClip (-2048, 2047, pSrcDst[coeffCount]);
    }
    return OMX_Sts_NoErr;

}

/* End of file */


