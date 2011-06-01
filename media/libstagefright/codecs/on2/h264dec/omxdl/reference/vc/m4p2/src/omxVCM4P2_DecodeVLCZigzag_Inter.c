/**
 * 
 * File Name:  omxVCM4P2_DecodeVLCZigzag_Inter.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 *
 * Description: 
 * Contains modules for zigzag scanning and VLC decoding
 * for inter block.
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
 * Function:  omxVCM4P2_DecodeVLCZigzag_Inter   (6.2.5.2.3)
 *
 * Description:
 * Performs VLC decoding and inverse zigzag scan for one inter-coded block. 
 *
 * Input Arguments:
 *   
 *   ppBitStream - double pointer to the current byte in the stream buffer 
 *   pBitOffset - pointer to the next available bit in the current stream 
 *            byte referenced by *ppBitStream. The parameter *pBitOffset is 
 *            valid within the range [0-7]. 
 *   shortVideoHeader - binary flag indicating presence of 
 *            short_video_header; escape modes 0-3 are used if 
 *            shortVideoHeader==0, and escape mode 4 is used when 
 *            shortVideoHeader==1. 
 *
 * Output Arguments:
 *   
 *   ppBitStream - *ppBitStream is updated after the block is decoded such 
 *            that it points to the current byte in the stream buffer 
 *   pBitOffset - *pBitOffset is updated after decoding such that it points 
 *            to the next available bit in the stream byte referenced by 
 *            *ppBitStream 
 *   pDst - pointer to the coefficient buffer of current block; must be 
 *            4-byte aligned. 
 *
 * Return Value:
 *    
 *    OMX_Sts_BadArgErr - bad arguments:
 *    -    At least one of the following pointers is NULL: 
 *         ppBitStream, *ppBitStream, pBitOffset, pDst
 *    -    pDst is not 4-byte aligned
 *    -   *pBitOffset exceeds [0,7]
 *    OMX_Sts_Err - status error, if:
 *    -    At least one mark bit is equal to zero 
 *    -    Encountered an illegal stream code that cannot be found in the VLC table 
 *    -    Encountered an illegal code in the VLC FLC table 
 *    -    The number of coefficients is greater than 64 
 *
 */

OMXResult omxVCM4P2_DecodeVLCZigzag_Inter(
     const OMX_U8 ** ppBitStream,
     OMX_INT * pBitOffset,
     OMX_S16 * pDst,
     OMX_INT shortVideoHeader
)
{
    OMX_U8  last,start = 0;
    const OMX_U8  *pZigzagTable = armVCM4P2_aClassicalZigzagScan;
    OMXResult errorCode;
    
    /* Argument error checks */
    armRetArgErrIf(ppBitStream == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(*ppBitStream == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pBitOffset == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pDst == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(!armIs4ByteAligned(pDst), OMX_Sts_BadArgErr);

    errorCode = armVCM4P2_GetVLCBits (
              ppBitStream,
              pBitOffset,
			  pDst,
			  shortVideoHeader,
              start,
			  &last,
			  11,
			  42,
			   2,
			   5,
              armVCM4P2_InterL0RunIdx,
              armVCM4P2_InterVlcL0,
			  armVCM4P2_InterL1RunIdx,
              armVCM4P2_InterVlcL1,
              armVCM4P2_InterL0LMAX,
              armVCM4P2_InterL1LMAX,
              armVCM4P2_InterL0RMAX,
              armVCM4P2_InterL1RMAX,
              pZigzagTable );
    armRetDataErrIf((errorCode != OMX_Sts_NoErr), errorCode);
    
    if (last == 0)
    {
        return OMX_Sts_Err;
    }
    return OMX_Sts_NoErr;
}

/* End of file */

