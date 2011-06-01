/**
 * 
 * File Name:  omxVCM4P2_DecodePadMV_PVOP.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 *
 * Description: 
 * Contains module for decoding MV and padding the same
 * 
 */

#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armCOMM_Bitstream.h"
#include "armCOMM.h"
#include "armVCM4P2_Huff_Tables_VLC.h"



/**
 * Function:  omxVCM4P2_DecodePadMV_PVOP   (6.2.5.1.1)
 *
 * Description:
 * Decodes and pads the four motion vectors associated with a non-intra P-VOP 
 * macroblock.  For macroblocks of type OMX_VC_INTER4V, the output MV is 
 * padded as specified in [ISO14496-2], subclause 7.6.1.6. Otherwise, for 
 * macroblocks of types other than OMX_VC_INTER4V, the decoded MV is copied to 
 * all four output MV buffer entries. 
 *
 * Input Arguments:
 *   
 *   ppBitStream - pointer to the pointer to the current byte in the bit 
 *            stream buffer 
 *   pBitOffset - pointer to the bit position in the byte pointed to by 
 *            *ppBitStream. *pBitOffset is valid within [0-7]. 
 *   pSrcMVLeftMB, pSrcMVUpperMB, and pSrcMVUpperRightMB - pointers to the 
 *            motion vector buffers of the macroblocks specially at the left, 
 *            upper, and upper-right side of the current macroblock, 
 *            respectively; a value of NULL indicates unavailability.  Note: 
 *            Any neighborhood macroblock outside the current VOP or video 
 *            packet or outside the current GOB (when short_video_header is 
 *             1 ) for which gob_header_empty is  0  is treated as 
 *            transparent, according to [ISO14496-2], subclause 7.6.5. 
 *   fcodeForward - a code equal to vop_fcode_forward in MPEG-4 bit stream 
 *            syntax 
 *   MBType - the type of the current macroblock. If MBType is not equal to 
 *            OMX_VC_INTER4V, the destination motion vector buffer is still 
 *            filled with the same decoded vector. 
 *
 * Output Arguments:
 *   
 *   ppBitStream - *ppBitStream is updated after the block is decoded, so 
 *            that it points to the current byte in the bit stream buffer 
 *   pBitOffset - *pBitOffset is updated so that it points to the current bit 
 *            position in the byte pointed by *ppBitStream 
 *   pDstMVCurMB - pointer to the motion vector buffer for the current 
 *            macroblock; contains four decoded motion vectors 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments:
 *    -    At least one of the following pointers is NULL: 
 *         ppBitStream, *ppBitStream, pBitOffset, pDstMVCurMB 
 *    -    *pBitOffset exceeds [0,7]
 *    -    fcodeForward exceeds (0,7]
 *    -    MBType less than zero
 *    -    motion vector buffer is not 4-byte aligned. 
 *    OMX_Sts_Err - status error 
 *
 */

OMXResult omxVCM4P2_DecodePadMV_PVOP(
     const OMX_U8 ** ppBitStream,
     OMX_INT * pBitOffset,
     OMXVCMotionVector * pSrcMVLeftMB,
     OMXVCMotionVector *pSrcMVUpperMB,
     OMXVCMotionVector * pSrcMVUpperRightMB,
     OMXVCMotionVector * pDstMVCurMB,
     OMX_INT fcodeForward,
     OMXVCM4P2MacroblockType MBType
 )
{
    OMXVCMotionVector diffMV;
    OMXVCMotionVector dstMVPredME[12];
    OMX_INT iBlk, i, count = 1;
    OMX_S32 mvHorResidual = 1, mvVerResidual = 1, mvHorData, mvVerData;
    OMX_S8 scaleFactor, index;
    OMX_S16 high, low, range;


    /* Argument error checks */
    armRetArgErrIf(ppBitStream == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(*ppBitStream == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pBitOffset == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pDstMVCurMB == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(((*pBitOffset < 0) || (*pBitOffset > 7)), OMX_Sts_BadArgErr);
    armRetArgErrIf(((fcodeForward < 1) || (fcodeForward > 7)), \
                    OMX_Sts_BadArgErr);
    armRetArgErrIf(!armIs4ByteAligned(pDstMVCurMB), OMX_Sts_BadArgErr);
    
    if ((MBType == OMX_VC_INTRA) ||
        (MBType == OMX_VC_INTRA_Q)
       )
    {
        /* All MV's are zero */
        for (i = 0; i < 4; i++)
        {
            pDstMVCurMB[i].dx = 0;
            pDstMVCurMB[i].dy = 0;
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
    high =  ( 32 * scaleFactor) - 1;
    low =   ( (-32) * scaleFactor);
    range = ( 64 * scaleFactor);

    /* Huffman decoding and MV reconstruction */
    for (iBlk = 0; iBlk < count; iBlk++)
    {

        /* Huffman decoding to get Horizontal data and residual */
        index = armUnPackVLC32(ppBitStream, pBitOffset,
                                            armVCM4P2_aVlcMVD);
        armRetDataErrIf(index == -1, OMX_Sts_Err);

        mvHorData = index - 32;

        if ((fcodeForward > 1) && (mvHorData != 0))
        {
            mvHorResidual = (OMX_S32) armGetBits(ppBitStream,
                                            pBitOffset, (fcodeForward -1));
        }

        /* Huffman decoding to get Vertical data and residual */
        index = armUnPackVLC32(ppBitStream, pBitOffset, armVCM4P2_aVlcMVD);
        armRetDataErrIf(index == -1, OMX_Sts_Err);

        mvVerData = index - 32;

        if ((fcodeForward > 1) && (mvVerData != 0))
        {
            mvVerResidual = (OMX_S32) armGetBits(ppBitStream,
                                            pBitOffset, (fcodeForward -1));
        }

        /* Calculating the differtial MV */
        if ( (scaleFactor == 1) || (mvHorData == 0) )
        {
            diffMV.dx = mvHorData;
        }
        else
        {
            diffMV.dx = ((armAbs(mvHorData) - 1) * fcodeForward)
                         + mvHorResidual + 1;
            if (mvHorData < 0)
            {
                diffMV.dx = -diffMV.dx;
            }
        }

        if ( (scaleFactor == 1) || (mvVerData == 0) )
        {
            diffMV.dy = mvVerData;
        }
        else
        {
            diffMV.dy = ((armAbs(mvVerData) - 1) * fcodeForward)
                         + mvVerResidual + 1;
            if (mvVerData < 0)
            {
                diffMV.dy = -diffMV.dy;
            }
        }

        /* Find the predicted vector */
        omxVCM4P2_FindMVpred (
            pDstMVCurMB,
            pSrcMVLeftMB,
            pSrcMVUpperMB,
            pSrcMVUpperRightMB,
            &pDstMVCurMB[iBlk],
            dstMVPredME,
            iBlk);

        /* Adding the difference to the predicted MV to reconstruct MV */
        pDstMVCurMB[iBlk].dx += diffMV.dx;
        pDstMVCurMB[iBlk].dy += diffMV.dy;

        /* Checking the range and keeping it within the limits */
        if ( pDstMVCurMB[iBlk].dx < low )
        {
            pDstMVCurMB[iBlk].dx += range;
        }
        if (pDstMVCurMB[iBlk].dx > high)
        {
            pDstMVCurMB[iBlk].dx -= range;
        }

        if ( pDstMVCurMB[iBlk].dy < low )
        {
            pDstMVCurMB[iBlk].dy += range;
        }
        if (pDstMVCurMB[iBlk].dy > high)
        {
            pDstMVCurMB[iBlk].dy -= range;
        }
    }

    if ((MBType == OMX_VC_INTER) || (MBType == OMX_VC_INTER_Q))
    {
        pDstMVCurMB[1] = pDstMVCurMB[0];
        pDstMVCurMB[2] = pDstMVCurMB[0];
        pDstMVCurMB[3] = pDstMVCurMB[0];
    }

    return OMX_Sts_NoErr;
}


/* End of file */


