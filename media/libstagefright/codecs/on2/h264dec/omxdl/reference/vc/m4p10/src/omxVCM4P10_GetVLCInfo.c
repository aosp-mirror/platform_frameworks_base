/**
 * 
 * File Name:  omxVCM4P10_GetVLCInfo.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 * Description:
 * 
 * This function extracts run-length encoding (RLE) information
 */

#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armCOMM.h"
#include "armVC.h"

/**
 * Function:  omxVCM4P10_GetVLCInfo   (6.3.5.9.1)
 *
 * Description:
 * This function extracts run-length encoding (RLE) information from the 
 * coefficient matrix.  The results are returned in an OMXVCM4P10VLCInfo 
 * structure. 
 *
 * Input Arguments:
 *   
 *   pSrcCoeff - pointer to the transform coefficient matrix.  8-byte 
 *            alignment required. 
 *   pScanMatrix - pointer to the scan order definition matrix.  For a luma 
 *            block the scan matrix should follow [ISO14496-10] section 8.5.4, 
 *            and should contain the values 0, 1, 4, 8, 5, 2, 3, 6, 9, 12, 13, 
 *            10, 7, 11, 14, 15.  For a chroma block, the scan matrix should 
 *            contain the values 0, 1, 2, 3. 
 *   bAC - indicates presence of a DC coefficient; 0 = DC coefficient 
 *            present, 1= DC coefficient absent. 
 *   MaxNumCoef - specifies the number of coefficients contained in the 
 *            transform coefficient matrix, pSrcCoeff. The value should be 16 
 *            for blocks of type LUMADC, LUMAAC, LUMALEVEL, and CHROMAAC. The 
 *            value should be 4 for blocks of type CHROMADC. 
 *
 * Output Arguments:
 *   
 *   pDstVLCInfo - pointer to structure that stores information for 
 *            run-length coding. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments; returned if any of the following 
 *              conditions are true: 
 *    -    at least one of the following pointers is NULL: 
 *            pSrcCoeff, pScanMatrix, pDstVLCInfo 
 *    -    pSrcCoeff is not aligned on an 8-byte boundary 
 *
 */
OMXResult omxVCM4P10_GetVLCInfo (
	const OMX_S16*		    pSrcCoeff,
	const OMX_U8*			    pScanMatrix,
	OMX_U8			    bAC,
	OMX_U32			    MaxNumCoef,
	OMXVCM4P10VLCInfo*	pDstVLCInfo
)
{
    OMX_INT     i, MinIndex;
    OMX_S32     Value;
    OMX_U32     Mask = 4, RunBefore;
    OMX_S16     *pLevel;
    OMX_U8      *pRun;
    OMX_S16     Buf [16];

    /* check for argument error */
    armRetArgErrIf(pSrcCoeff == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf(armNot8ByteAligned(pSrcCoeff), OMX_Sts_BadArgErr)
    armRetArgErrIf(pScanMatrix == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf(pDstVLCInfo == NULL, OMX_Sts_BadArgErr)
    armRetArgErrIf(bAC > 1, OMX_Sts_BadArgErr)
    armRetArgErrIf(MaxNumCoef > 16, OMX_Sts_BadArgErr)

    /* Initialize RLE Info structure */
    pDstVLCInfo->uTrailing_Ones = 0;
    pDstVLCInfo->uTrailing_One_Signs = 0;
    pDstVLCInfo->uNumCoeffs = 0;
    pDstVLCInfo->uTotalZeros = 0;

    for (i = 0; i < 16; i++)
    {
        pDstVLCInfo->iLevels [i] = 0;
        pDstVLCInfo->uRuns [i] = 0;
    }
    
    MinIndex = (bAC == 0 && MaxNumCoef == 15) ? 1 : 0;
    for (i = MinIndex; i < (MaxNumCoef + MinIndex); i++)
    {        
        /* Scan */
        Buf [i - MinIndex] = pSrcCoeff [pScanMatrix [i]];
    }

    /* skip zeros at the end */
    i = MaxNumCoef - 1;
    while (!Buf [i] && i >= 0)
    {
        i--;
    }
    
    if (i < 0)
    {
        return OMX_Sts_NoErr;
    }

    /* Fill RLE Info structure */
    pLevel = pDstVLCInfo->iLevels;
    pRun = pDstVLCInfo->uRuns;
    RunBefore = 0;

    /* Handle first non zero separate */
    pDstVLCInfo->uNumCoeffs++;
    Value = Buf [i];
    if (Value == 1 || Value == -1)
    {
        pDstVLCInfo->uTrailing_Ones++;
        
        pDstVLCInfo->uTrailing_One_Signs |= 
            Value == -1 ? Mask : 0;
        Mask >>= 1;
    }
    else
    {
        Value -= (Value > 0 ? 1 : -1);
        *pLevel++ = Value;
        Mask = 0;
    }

    /* Remaining non zero */
    while (--i >= 0)
    {
        Value = Buf [i];
        if (Value)
        {
            pDstVLCInfo->uNumCoeffs++;

            /* Mask becomes zero after entering */
            if (Mask &&
                (Value == 1 || 
                 Value == -1))
            {
                pDstVLCInfo->uTrailing_Ones++;
                
                pDstVLCInfo->uTrailing_One_Signs |= 
                    Value == -1 ? Mask : 0;
                Mask >>= 1;
                *pRun++ = RunBefore;
                RunBefore = 0;
            }
            else
            {
                /* If 3 trailing ones are not completed */
                if (Mask)
                {
                    Mask = 0;
                    Value -= (Value > 0 ? 1 : -1);
                }
                *pLevel++ = Value;
                *pRun++ = RunBefore;
                RunBefore = 0;
            }
        }
        else
        {
            pDstVLCInfo->uTotalZeros++;
            RunBefore++;
        }        
    }
    
    /* Update last run */
    if (RunBefore)
    {
        *pRun++ = RunBefore;
    }

    return OMX_Sts_NoErr;
}

/*****************************************************************************
 *                              END OF FILE
 *****************************************************************************/

