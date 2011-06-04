/**
 * 
 * File Name:  armVCM4P2_DecodeVLCZigzag_intra.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 * 
 * Description:
 * Contains modules for filling of the coefficient buffer
 *
 */
 
#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armVC.h"
#include "armCOMM_Bitstream.h"
#include "armCOMM.h"
#include "armVCM4P2_Huff_Tables_VLC.h"
#include "armVCM4P2_ZigZag_Tables.h"



/**
 * Function: armVCM4P2_DecodeVLCZigzag_Intra
 *
 * Description:
 * Performs VLC decoding and inverse zigzag scan for one intra coded block.
 *
 * Remarks:
 *
 * Parameters:
 * [in] ppBitStream     pointer to the pointer to the current byte in
 *                              the bitstream buffer
 * [in] pBitOffset      pointer to the bit position in the byte pointed
 *                              to by *ppBitStream. *pBitOffset is valid within
 *                              [0-7].
 * [in] predDir         AC prediction direction which is used to decide
 *                              the zigzag scan pattern. It takes one of the
 *                              following values:
 *                              OMX_VC_NONE  AC prediction not used;
 *                                              perform classical zigzag scan;
 *                              OMX_VC_HORIZONTAL    Horizontal prediction;
 *                                                      perform alternate-vertical
 *                                                      zigzag scan;
 *                              OMX_VC_VERTICAL      Vertical prediction;
 *                                                      thus perform
 *                                                      alternate-horizontal
 *                                                      zigzag scan.
 * [in] start           start indicates whether the encoding begins with 0th element
 *                      or 1st.
 * [out]    ppBitStream     *ppBitStream is updated after the block is
 *                              decoded, so that it points to the current byte
 *                              in the bit stream buffer
 * [out]    pBitOffset      *pBitOffset is updated so that it points to the
 *                              current bit position in the byte pointed by
 *                              *ppBitStream
 * [out]    pDst            pointer to the coefficient buffer of current
 *                              block. Should be 32-bit aligned
 *
 * Return Value:
 * Standard OMXResult result. See enumeration for possible result codes.
 *
 */

OMXResult armVCM4P2_DecodeVLCZigzag_Intra(
     const OMX_U8 ** ppBitStream,
     OMX_INT * pBitOffset,
     OMX_S16 * pDst,
     OMX_U8 predDir,
     OMX_INT shortVideoHeader,
     OMX_U8  start
)
{
    OMX_U8  last = 0;
    const OMX_U8  *pZigzagTable = armVCM4P2_aClassicalZigzagScan;
    OMXResult errorCode;
    
    /* Argument error checks */
    armRetArgErrIf(ppBitStream == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(*ppBitStream == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pBitOffset == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pDst == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(!armIs4ByteAligned(pDst), OMX_Sts_BadArgErr);
    armRetArgErrIf((*pBitOffset < 0) || (*pBitOffset >7), OMX_Sts_BadArgErr);
    armRetArgErrIf((predDir > 2), OMX_Sts_BadArgErr);

    switch (predDir)
    {
        case OMX_VC_NONE:
        {
            pZigzagTable = armVCM4P2_aClassicalZigzagScan;
            break;
        }

        case OMX_VC_HORIZONTAL:
        {
            pZigzagTable = armVCM4P2_aVerticalZigzagScan;
            break;
        }

        case OMX_VC_VERTICAL:
        {
            pZigzagTable = armVCM4P2_aHorizontalZigzagScan;
            break;
        }
    }
    
    errorCode = armVCM4P2_GetVLCBits (
              ppBitStream,
              pBitOffset,
			  pDst,
			  shortVideoHeader,
			  start,
			  &last,
			  10,
			  62,
			   7,
			  21,
              armVCM4P2_IntraL0RunIdx,
              armVCM4P2_IntraVlcL0,
			  armVCM4P2_IntraL1RunIdx,
              armVCM4P2_IntraVlcL1,
              armVCM4P2_IntraL0LMAX,
              armVCM4P2_IntraL1LMAX,
              armVCM4P2_IntraL0RMAX,
              armVCM4P2_IntraL1RMAX,
              pZigzagTable );
    armRetDataErrIf((errorCode != OMX_Sts_NoErr), errorCode);
    
    if (last == 0)
    {
        return OMX_Sts_Err;
    }
    return OMX_Sts_NoErr;
}

/* End of file */

