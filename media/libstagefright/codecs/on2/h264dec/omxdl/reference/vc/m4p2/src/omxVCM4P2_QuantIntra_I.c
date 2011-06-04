/**
 * 
 * File Name:  omxVCM4P2_QuantIntra_I.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 *
 * Description:
 * Contains modules for intra Quantization
 * 
 */

#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armCOMM.h"
/**
 * Function:  omxVCM4P2_QuantIntra_I   (6.2.4.4.2)
 *
 * Description:
 * Performs quantization on intra block coefficients. This function supports 
 * bits_per_pixel == 8. 
 *
 * Input Arguments:
 *   
 *   pSrcDst - pointer to the input intra block coefficients; must be aligned 
 *            on a 16-byte boundary. 
 *   QP - quantization parameter (quantizer_scale). 
 *   blockIndex - block index indicating the component type and position, 
 *            valid in the range 0 to 5, as defined in [ISO14496-2], subclause 
 *            6.1.3.8. 
 *   shortVideoHeader - binary flag indicating presence of 
 *            short_video_header; shortVideoHeader==1 selects linear intra DC 
 *            mode, and shortVideoHeader==0 selects non linear intra DC mode. 
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
 *    -    blockIndex < 0 or blockIndex >= 10 
 *    -    QP <= 0 or QP >= 32. 
 *
 */

OMXResult omxVCM4P2_QuantIntra_I(
     OMX_S16 * pSrcDst,
     OMX_U8 QP,
     OMX_INT blockIndex,
	 OMX_INT shortVideoHeader
 )
{

    /* Definitions and Initializations*/
    /* Initialized to remove compilation error */
    OMX_INT dcScaler = 0, coeffCount,fSign;
    OMX_INT maxClpAC, minClpAC;

    /* Argument error checks */
    armRetArgErrIf(pSrcDst == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(((blockIndex < 0) || (blockIndex >= 10)), OMX_Sts_BadArgErr);
    armRetArgErrIf(((QP <= 0) || (QP >= 32)), OMX_Sts_BadArgErr);
   /* One argument check is delayed until we have ascertained that  */
   /* pQMatrix is not NULL.                                         */

    
    /* Set the Clip Range based on SVH on/off */
    if(shortVideoHeader == 1)
    {
        maxClpAC = 127;
        minClpAC = -127;
        dcScaler = 8;
        /* Dequant the DC value, this applies to both the methods */
        pSrcDst[0] = armIntDivAwayFromZero (pSrcDst[0], dcScaler);
    
        /* Clip between 1 and 254 */
        pSrcDst[0] = (OMX_S16) armClip (1, 254, pSrcDst[0]);
    }
    else
    {
        maxClpAC = 2047;
        minClpAC = -2047;   
        /* Calculate the DC scaler value */
        if ((blockIndex  < 4) || (blockIndex  > 5))
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
        else if (blockIndex < 6)
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
        
        /* Dequant the DC value, this applies to both the methods */
        pSrcDst[0] = armIntDivAwayFromZero (pSrcDst[0], dcScaler);
    }
    
    /* Second Inverse quantisation method */
    for (coeffCount = 1; coeffCount < 64; coeffCount++)
    {
        fSign =  armSignCheck (pSrcDst[coeffCount]);  
        pSrcDst[coeffCount] = armAbs(pSrcDst[coeffCount])/(2 * QP);
        pSrcDst[coeffCount] *= fSign;

        /* Clip */
        pSrcDst[coeffCount] =
        (OMX_S16) armClip (minClpAC, maxClpAC, pSrcDst[coeffCount]);
    }
    return OMX_Sts_NoErr;

}

/* End of file */


