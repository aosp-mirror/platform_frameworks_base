/**
 * 
 * File Name:  omxVCM4P2_EncodeMV.c
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
#include "armCOMM_Bitstream.h"
#include "armVCM4P2_Huff_Tables_VLC.h"



/**
 * Function:  omxVCM4P2_EncodeMV   (6.2.4.5.4)
 *
 * Description:
 * Predicts a motion vector for the current macroblock, encodes the 
 * difference, and writes the output to the stream buffer. The input MVs 
 * pMVCurMB, pSrcMVLeftMB, pSrcMVUpperMB, and pSrcMVUpperRightMB should lie 
 * within the ranges associated with the input parameter fcodeForward, as 
 * described in [ISO14496-2], subclause 7.6.3.  This function provides a 
 * superset of the functionality associated with the function 
 * omxVCM4P2_FindMVpred. 
 *
 * Input Arguments:
 *   
 *   ppBitStream - double pointer to the current byte in the bitstream buffer 
 *   pBitOffset - index of the first free (next available) bit in the stream 
 *            buffer referenced by *ppBitStream, valid in the range 0 to 7. 
 *   pMVCurMB - pointer to the current macroblock motion vector; a value of 
 *            NULL indicates unavailability. 
 *   pSrcMVLeftMB - pointer to the source left macroblock motion vector; a 
 *            value of  NULLindicates unavailability. 
 *   pSrcMVUpperMB - pointer to source upper macroblock motion vector; a 
 *            value of NULL indicates unavailability. 
 *   pSrcMVUpperRightMB - pointer to source upper right MB motion vector; a 
 *            value of NULL indicates unavailability. 
 *   fcodeForward - an integer with values from 1 to 7; used in encoding 
 *            motion vectors related to search range, as described in 
 *            [ISO14496-2], subclause 7.6.3. 
 *   MBType - macro block type, valid in the range 0 to 5 
 *
 * Output Arguments:
 *   
 *   ppBitStream - updated pointer to the current byte in the bit stream 
 *            buffer 
 *   pBitOffset - updated index of the next available bit position in stream 
 *            buffer referenced by *ppBitStream 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments 
 *    -    At least one of the following pointers is NULL: ppBitStream, 
 *              *ppBitStream, pBitOffset, pMVCurMB 
 *    -    *pBitOffset < 0, or *pBitOffset >7. 
 *    -    fcodeForward <= 0, or fcodeForward > 7, or MBType < 0. 
 *
 */

OMXResult omxVCM4P2_EncodeMV(
     OMX_U8 **ppBitStream,
     OMX_INT *pBitOffset,
     const OMXVCMotionVector * pMVCurMB,
     const OMXVCMotionVector * pSrcMVLeftMB,
     const OMXVCMotionVector * pSrcMVUpperMB,
     const OMXVCMotionVector * pSrcMVUpperRightMB,
     OMX_INT fcodeForward,
     OMXVCM4P2MacroblockType MBType
)
{
    OMXVCMotionVector dstMVPred, diffMV;
    OMXVCMotionVector dstMVPredME[12];
    /* Initialized to remove compilation warning */
    OMX_INT iBlk, i, count = 1;
    OMX_S32 mvHorResidual, mvVerResidual, mvHorData, mvVerData;
    OMX_U8 scaleFactor, index;

    /* Argument error checks */
    armRetArgErrIf(ppBitStream == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(*ppBitStream == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pBitOffset == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pMVCurMB == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(((*pBitOffset < 0) || (*pBitOffset > 7)), OMX_Sts_BadArgErr);
    armRetArgErrIf(((fcodeForward < 1) || (fcodeForward > 7)), \
                    OMX_Sts_BadArgErr);
    
    if ((MBType == OMX_VC_INTRA) ||
        (MBType == OMX_VC_INTRA_Q)
       )
    {
        /* No candidate vectors hence make them zero */
        for (i = 0; i < 12; i++)
        {
            dstMVPredME[i].dx = 0;
            dstMVPredME[i].dy = 0;
        }

        return OMX_Sts_NoErr;
    }

    if ((MBType == OMX_VC_INTER4V) || (MBType == OMX_VC_INTER4V_Q))
    {
        count = 4;
    }
    else if ((MBType == OMX_VC_INTER) || (MBType == OMX_VC_INTER_Q))
    {
        count = 1;
    }

    /* Calculating the scale factor */
    scaleFactor = 1 << (fcodeForward -1);

    for (iBlk = 0; iBlk < count; iBlk++)
    {

        /* Find the predicted vector */
        omxVCM4P2_FindMVpred (
            pMVCurMB,
            pSrcMVLeftMB,
            pSrcMVUpperMB,
            pSrcMVUpperRightMB,
            &dstMVPred,
            dstMVPredME,
            iBlk );

        /* Calculating the differential motion vector (diffMV) */
        diffMV.dx = pMVCurMB[iBlk].dx - dstMVPred.dx;
        diffMV.dy = pMVCurMB[iBlk].dy - dstMVPred.dy;

        /* Calculating the mv_data and mv_residual for Horizantal MV */
        if (diffMV.dx == 0)
        {
            mvHorResidual = 0;
            mvHorData = 0;
        }
        else
        {
            mvHorResidual = ( armAbs(diffMV.dx) - 1) % scaleFactor;
            mvHorData = (armAbs(diffMV.dx) - mvHorResidual + (scaleFactor - 1))
                     / scaleFactor;
            if (diffMV.dx < 0)
            {
                mvHorData = -mvHorData;
            }
        }

        /* Calculating the mv_data and mv_residual for Vertical MV */
        if (diffMV.dy == 0)
        {
            mvVerResidual = 0;
            mvVerData = 0;
        }
        else
        {
            mvVerResidual = ( armAbs(diffMV.dy) - 1) % scaleFactor;
            mvVerData = (armAbs(diffMV.dy) - mvVerResidual + (scaleFactor - 1))
                     / scaleFactor;
            if (diffMV.dy < 0)
            {
                mvVerData = -mvVerData;
            }
        }

        /* Huffman encoding */

        /* The index is actually calculate as
           index = ((float) (mvHorData/2) + 16) * 2,
           meaning the MV data is halfed and then normalized
           to begin with zero and then doubled to take care of indexing
           the fractional part included */
        index = mvHorData + 32;
        armPackVLC32 (ppBitStream, pBitOffset, armVCM4P2_aVlcMVD[index]);
        if ((fcodeForward > 1) && (diffMV.dx != 0))
        {
            armPackBits (ppBitStream, pBitOffset, mvHorResidual, (fcodeForward -1));
        }

        /* The index is actually calculate as
           index = ((float) (mvVerData/2) + 16) * 2,
           meaning the MV data is halfed and then normalized
           to begin with zero and then doubled to take care of indexing
           the fractional part included */
        index = mvVerData + 32;
        armPackVLC32 (ppBitStream, pBitOffset, armVCM4P2_aVlcMVD[index]);
        if ((fcodeForward > 1) && (diffMV.dy != 0))
        {
            armPackBits (ppBitStream, pBitOffset, mvVerResidual, (fcodeForward -1));
        }
    }

    return OMX_Sts_NoErr;
}


/* End of file */


