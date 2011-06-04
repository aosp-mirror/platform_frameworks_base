/**
 * 
 * File Name:  omxVCM4P2_FindMVpred.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 *
 * Description: 
 * Contains module for predicting MV of MB
 *
 */
  
#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armCOMM.h"

/**
 * Function:  omxVCM4P2_FindMVpred   (6.2.3.1.1)
 *
 * Description:
 * Predicts a motion vector for the current block using the procedure 
 * specified in [ISO14496-2], subclause 7.6.5.  The resulting predicted MV is 
 * returned in pDstMVPred. If the parameter pDstMVPredME if is not NULL then 
 * the set of three MV candidates used for prediction is also returned, 
 * otherwise pDstMVPredMEis NULL upon return. 
 *
 * Input Arguments:
 *   
 *   pSrcMVCurMB - pointer to the MV buffer associated with the current Y 
 *            macroblock; a value of NULL indicates unavailability. 
 *   pSrcCandMV1 - pointer to the MV buffer containing the 4 MVs associated 
 *            with the MB located to the left of the current MB; set to NULL 
 *            if there is no MB to the left. 
 *   pSrcCandMV2 - pointer to the MV buffer containing the 4 MVs associated 
 *            with the MB located above the current MB; set to NULL if there 
 *            is no MB located above the current MB. 
 *   pSrcCandMV3 - pointer to the MV buffer containing the 4 MVs associated 
 *            with the MB located to the right and above the current MB; set 
 *            to NULL if there is no MB located to the above-right. 
 *   iBlk - the index of block in the current macroblock 
 *   pDstMVPredME - MV candidate return buffer;  if set to NULL then 
 *            prediction candidate MVs are not returned and pDstMVPredME will 
 *            be NULL upon function return; if pDstMVPredME is non-NULL then it 
 *            must point to a buffer containing sufficient space for three 
 *            return MVs. 
 *
 * Output Arguments:
 *   
 *   pDstMVPred - pointer to the predicted motion vector 
 *   pDstMVPredME - if non-NULL upon input then pDstMVPredME  points upon 
 *            return to a buffer containing the three motion vector candidates 
 *            used for prediction as specified in [ISO14496-2], subclause 
 *            7.6.5, otherwise if NULL upon input then pDstMVPredME is NULL 
 *            upon output. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments; returned under any of the following 
 *              conditions: 
 *    -    the pointer pDstMVPred is NULL 
 *    -    the parameter iBlk does not fall into the range 0 <= iBlk<=3 
 *
 */

OMXResult omxVCM4P2_FindMVpred(
     const OMXVCMotionVector* pSrcMVCurMB,
     const OMXVCMotionVector* pSrcCandMV1,
     const OMXVCMotionVector* pSrcCandMV2,
     const OMXVCMotionVector* pSrcCandMV3,
     OMXVCMotionVector* pDstMVPred,
     OMXVCMotionVector* pDstMVPredME,
     OMX_INT iBlk
 )
{
    OMXVCMotionVector CandMV;
	const OMXVCMotionVector *pCandMV1;
    const OMXVCMotionVector *pCandMV2;
    const OMXVCMotionVector *pCandMV3;
    
    /* Argument error checks */
	armRetArgErrIf(iBlk!=0 && pSrcMVCurMB == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pDstMVPred == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf((iBlk < 0) || (iBlk > 3), OMX_Sts_BadArgErr); 

    CandMV.dx = CandMV.dy = 0;
	/* Based on the position of the block extract the motion vectors and
       the tranperancy status */
   
    
    /* Set the default value for these to be used if pSrcCandMV[1|2|3] == NULL */
    pCandMV1 = pCandMV2 = pCandMV3 = &CandMV;

    
    switch (iBlk)
    {
        case 0:
        {
            if(pSrcCandMV1 != NULL)
            {
			    pCandMV1 = &pSrcCandMV1[1];
			}
			if(pSrcCandMV2 != NULL)
            {
				pCandMV2 = &pSrcCandMV2[2];
			}
			if(pSrcCandMV3 != NULL)
            {
				pCandMV3 = &pSrcCandMV3[2];
			}
			if ((pSrcCandMV1 == NULL) && (pSrcCandMV2 == NULL))
            {
                pCandMV1 = pCandMV2 = pCandMV3;
            }
            else if((pSrcCandMV1 == NULL) && (pSrcCandMV3 == NULL))
            {
                pCandMV1 = pCandMV3 = pCandMV2;
            }
            else if((pSrcCandMV2 == NULL) && (pSrcCandMV3 == NULL))
            {
                pCandMV2 = pCandMV3 = pCandMV1;
            }
            break;
        }
        case 1:
        {
            pCandMV1 = &pSrcMVCurMB[0];
			if(pSrcCandMV2 != NULL)
            {
				pCandMV2 = &pSrcCandMV2[3];
			}
			if(pSrcCandMV3 != NULL)
            {
				pCandMV3 = &pSrcCandMV3[2];
			}
			if((pSrcCandMV2 == NULL) && (pSrcCandMV3 == NULL))
            {
                pCandMV2 = pCandMV3 = pCandMV1;
            }
            break;
        }
        case 2:
        {
            if(pSrcCandMV1 != NULL)
            {
				pCandMV1 = &pSrcCandMV1[3];
			}
			pCandMV2 = &pSrcMVCurMB[0];
			pCandMV3 = &pSrcMVCurMB[1];
			break;
        }
        case 3:
        {
            pCandMV1 = &pSrcMVCurMB[2];
			pCandMV2 = &pSrcMVCurMB[0];
			pCandMV3 = &pSrcMVCurMB[1];
			break;
        }
    }

    /* Find the median of the 3 candidate MV's */
    pDstMVPred->dx = armMedianOf3 (pCandMV1->dx, pCandMV2->dx, pCandMV3->dx);
    pDstMVPred->dy = armMedianOf3 (pCandMV1->dy, pCandMV2->dy, pCandMV3->dy);
        
    if (pDstMVPredME != NULL)
    {
        /* Store the candidate MV's into the pDstMVPredME, these can be used
           in the fast algorithm if implemented */
        pDstMVPredME[0].dx = pCandMV1->dx;
        pDstMVPredME[0].dy = pCandMV1->dy;
        pDstMVPredME[1].dx = pCandMV2->dx;
        pDstMVPredME[1].dy = pCandMV2->dy;
        pDstMVPredME[2].dx = pCandMV3->dx;
        pDstMVPredME[2].dy = pCandMV3->dy;
    }

    return OMX_Sts_NoErr;
}


/* End of file */

