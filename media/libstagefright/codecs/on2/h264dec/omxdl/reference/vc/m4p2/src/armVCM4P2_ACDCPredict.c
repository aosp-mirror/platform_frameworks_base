/**
 * 
 * File Name:  armVCM4P2_ACDCPredict.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 * 
 * Description:
 * Contains module for DC/AC coefficient prediction
 *
 */ 
 
#include "omxtypes.h"
#include "armOMX.h"

#include "armVC.h"
#include "armCOMM.h"

/**
 * Function: armVCM4P2_ACDCPredict
 *
 * Description:
 * Performs adaptive DC/AC coefficient prediction for an intra block. Prior
 * to the function call, prediction direction (predDir) should be selected
 * as specified in subclause 7.4.3.1 of ISO/IEC 14496-2.
 *
 * Remarks:
 *
 * Parameters:
 * [in] pSrcDst     pointer to the coefficient buffer which contains
 *                          the quantized coefficient residuals (PQF) of the
 *                          current block
 * [in] pPredBufRow pointer to the coefficient row buffer
 * [in] pPredBufCol pointer to the coefficient column buffer
 * [in] curQP       quantization parameter of the current block. curQP
 *                          may equal to predQP especially when the current
 *                          block and the predictor block are in the same
 *                          macroblock.
 * [in] predQP      quantization parameter of the predictor block
 * [in] predDir     indicates the prediction direction which takes one
 *                          of the following values:
 *                          OMX_VC_HORIZONTAL    predict horizontally
 *                          OMX_VC_VERTICAL      predict vertically
 * [in] ACPredFlag  a flag indicating if AC prediction should be
 *                          performed. It is equal to ac_pred_flag in the bit
 *                          stream syntax of MPEG-4
 * [in] videoComp   video component type (luminance, chrominance or
 *                          alpha) of the current block
 * [in] flag        This flag defines the if one wants to use this functions to
 *                  calculate PQF (set 1, prediction) or QF (set 0, reconstruction)
 * [out]    pPreACPredict   pointer to the predicted coefficients buffer.
 *                          Filled ONLY if it is not NULL
 * [out]    pSrcDst     pointer to the coefficient buffer which contains
 *                          the quantized coefficients (QF) of the current
 *                          block
 * [out]    pPredBufRow pointer to the updated coefficient row buffer
 * [out]    pPredBufCol pointer to the updated coefficient column buffer
 * [out]    pSumErr     pointer to the updated sum of the difference
 *                      between predicted and unpredicted coefficients
 *                      If this is NULL, do not update
 *
 * Return Value:
 * Standard OMXResult result. See enumeration for possible result codes.
 *
 */

OMXResult armVCM4P2_ACDCPredict(
     OMX_S16 * pSrcDst,
     OMX_S16 * pPreACPredict,
     OMX_S16 * pPredBufRow,
     OMX_S16 * pPredBufCol,
     OMX_INT curQP,
     OMX_INT predQP,
     OMX_INT predDir,
     OMX_INT ACPredFlag,
     OMXVCM4P2VideoComponent videoComp,
     OMX_U8 flag,
     OMX_INT *pSumErr
)
{
    OMX_INT dcScaler, i;
    OMX_S16 tempPred;

    /* Argument error checks */
    armRetArgErrIf(pSrcDst == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pPredBufRow == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pPredBufCol == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(curQP <= 0, OMX_Sts_BadArgErr);
    armRetArgErrIf(predQP <= 0, OMX_Sts_BadArgErr);
    armRetArgErrIf((predDir != 1) && (predDir != 2), OMX_Sts_BadArgErr);
    armRetArgErrIf(!armIs4ByteAligned(pSrcDst), OMX_Sts_BadArgErr);
    armRetArgErrIf(!armIs4ByteAligned(pPredBufRow), OMX_Sts_BadArgErr);
    armRetArgErrIf(!armIs4ByteAligned(pPredBufCol), OMX_Sts_BadArgErr);

    
    /* Set DC scaler value to avoid some compilers giving a warning. */
    dcScaler=0;
    
    /* Calculate the DC scaler value */
    if (videoComp == OMX_VC_LUMINANCE)
    {
        if (curQP >= 1 && curQP <= 4)
        {
            dcScaler = 8;
        }
        else if (curQP >= 5 && curQP <= 8)
        {
            dcScaler = 2 * curQP;
        }
        else if (curQP >= 9 && curQP <= 24)
        {
            dcScaler = curQP + 8;
        }
        else
        {
            dcScaler = (2 * curQP) - 16;
        }
    }
    else if (videoComp == OMX_VC_CHROMINANCE)
    {
        if (curQP >= 1 && curQP <= 4)
        {
            dcScaler = 8;
        }
        else if (curQP >= 5 && curQP <= 24)
        {
            dcScaler = (curQP + 13)/2;
        }
        else
        {
            dcScaler = curQP - 6;
        }
    }

    if (pPreACPredict != NULL)
    {
        pPreACPredict[0] = predDir;
    }

    if (predDir == OMX_VC_VERTICAL)
    {
        /* F[0][0]//dc_scaler */
        tempPred = armIntDivAwayFromZero(pPredBufRow[0], dcScaler);
    }
    else
    {
        /* F[0][0]//dc_scaler */
        tempPred = armIntDivAwayFromZero(pPredBufCol[0], dcScaler);
    }

    /* Updating the DC value to the row and col buffer */
    *(pPredBufRow - 8) = *pPredBufCol;

    if (flag)
    {
        /* Cal and store F[0][0] into the col buffer */
        *pPredBufCol = pSrcDst[0] * dcScaler;

        /* PQF = QF - F[0][0]//dc_scaler */
        pSrcDst[0] -= tempPred;
    }
    else
    {
        /* QF = PQF + F[0][0]//dc_scaler */
        pSrcDst[0] += tempPred;
        
        /* Saturate */
        pSrcDst[0] = armClip (-2048, 2047, pSrcDst[0]);

        /* Cal and store F[0][0] into the col buffer */
        *pPredBufCol = pSrcDst[0] * dcScaler;
    }


    if (ACPredFlag == 1)
    {
        if (predDir == OMX_VC_VERTICAL)
        {
            for (i = 1; i < 8; i++)
            {
                tempPred = armIntDivAwayFromZero \
                              (pPredBufRow[i] * predQP, curQP);
                if (flag)
                {
                    /* Updating QF to the row buff */
                    pPredBufRow[i] = pSrcDst[i];
                    /*PQFX[v][0] = QFX[v][0] - (QFA[v][0] * QPA) // QPX */
                    pSrcDst[i] -= tempPred;
                    /* Sum of absolute values of AC prediction error, this can
                    be used as a reference to choose whether to use
                    AC prediction */
                    *pSumErr += armAbs(pSrcDst[i]);
                    /* pPreACPredict[1~7] store the error signal
                    after AC prediction */
                    pPreACPredict[i] = pSrcDst[i];
                }
                else
                {
                    /*QFX[v][0] = PQFX[v][0] + (QFA[v][0] * QPA) // QPX */
                    pSrcDst[i] += tempPred;
                    
                    /* Saturate */
                    pSrcDst[i] = armClip (-2048, 2047, pSrcDst[i]);
                    
                    /* Updating QF to the row buff */
                    pPredBufRow[i] = pSrcDst[i];
                }
            }
        }
        else
        {
            for (i = 8; i < 64; i += 8)
            {
                tempPred = armIntDivAwayFromZero \
                              (pPredBufCol[i>>3] * predQP, curQP);
                if (flag)
                {
                    /* Updating QF to col buff */
                    pPredBufCol[i>>3] = pSrcDst[i];
                    /*PQFX[0][u] = QFX[0][u] - (QFA[0][u] * QPA) // QPX */
                    pSrcDst[i] -= tempPred;
                    /* Sum of absolute values of AC prediction error, this can
                    be used as a reference to choose whether to use AC
                    prediction */
                    *pSumErr += armAbs(pSrcDst[i]);
                    /* pPreACPredict[1~7] store the error signal
                    after AC prediction */
                    pPreACPredict[i>>3] = pSrcDst[i];
                }
                else
                {
                    /*QFX[0][u] = PQFX[0][u] + (QFA[0][u] * QPA) // QPX */
                    pSrcDst[i] += tempPred;
                    
                    /* Saturate */
                    pSrcDst[i] = armClip (-2048, 2047, pSrcDst[i]);
                    
                    /* Updating QF to col buff */
                    pPredBufCol[i>>3] = pSrcDst[i];
                }
            }
        }
    }

    return OMX_Sts_NoErr;
}

/*End of File*/

