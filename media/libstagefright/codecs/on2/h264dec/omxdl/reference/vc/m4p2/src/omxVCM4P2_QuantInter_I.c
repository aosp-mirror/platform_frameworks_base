/**
 * 
 * File Name:  omxVCM4P2_QuantInter_I.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 *
 * Description:
 * Contains modules for inter Quantization
 * 
 */
 
#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armCOMM.h"

/**
 * Function:  omxVCM4P2_QuantInter_I   (6.2.4.4.3)
 *
 * Description:
 * Performs quantization on an inter coefficient block; supports 
 * bits_per_pixel == 8. 
 *
 * Input Arguments:
 *   
 *   pSrcDst - pointer to the input inter block coefficients; must be aligned 
 *            on a 16-byte boundary. 
 *   QP - quantization parameter (quantizer_scale) 
 *   shortVideoHeader - binary flag indicating presence of short_video_header; 
 *            shortVideoHeader==1 selects linear intra DC mode, and 
 *            shortVideoHeader==0 selects non linear intra DC mode. 
 *
 * Output Arguments:
 *   
 *   pSrcDst - pointer to the output (quantized) interblock coefficients.  
 *            When shortVideoHeader==1, AC coefficients are saturated on the 
 *            interval [-127, 127], and DC coefficients are saturated on the 
 *            interval [1, 254].  When shortVideoHeader==0, AC coefficients 
 *            are saturated on the interval [-2047, 2047]. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments:
 *    -    pSrcDst is NULL. 
 *    -    QP <= 0 or QP >= 32. 
 *
 */

OMXResult omxVCM4P2_QuantInter_I(
     OMX_S16 * pSrcDst,
     OMX_U8 QP,
	 OMX_INT shortVideoHeader
)
{

    /* Definitions and Initializations*/
    OMX_INT coeffCount;
    OMX_INT fSign;
    OMX_INT maxClpAC = 0, minClpAC = 0;
    OMX_INT maxClpDC = 0, minClpDC = 0;
    
    /* Argument error checks */
    armRetArgErrIf(pSrcDst == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(((QP <= 0) || (QP >= 32)), OMX_Sts_BadArgErr);
   /* One argument check is delayed until we have ascertained that  */
   /* pQMatrix is not NULL.                                         */
                
    /* Set the Clip Range based on SVH on/off */
    if(shortVideoHeader == 1)
    {
       maxClpDC = 254;
       minClpDC = 1;
       maxClpAC = 127;
       minClpAC = -127;        
    }
    else
    {
        maxClpDC = 2047;
        minClpDC = -2047;
        maxClpAC = 2047;
        minClpAC = -2047;   
    }
                
    /* Second Inverse quantisation method */
    for (coeffCount = 0; coeffCount < 64; coeffCount++)
    {
        fSign =  armSignCheck (pSrcDst[coeffCount]);  
        pSrcDst[coeffCount] = (armAbs(pSrcDst[coeffCount]) 
                              - (QP/2))/(2 * QP);
        pSrcDst[coeffCount] *= fSign;
        
        /* Clip */
        if (coeffCount == 0)
        {
           pSrcDst[coeffCount] =
           (OMX_S16) armClip (minClpDC, maxClpDC, pSrcDst[coeffCount]);
        }
        else
        {
           pSrcDst[coeffCount] =
           (OMX_S16) armClip (minClpAC, maxClpAC, pSrcDst[coeffCount]);
        }
    }
    return OMX_Sts_NoErr;

}

/* End of file */


