/**
 * 
 * File Name:  omxVCM4P2_DecodeBlockCoef_Inter.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 *
 * Description: 
 * Contains modules for inter reconstruction
 * 
 */
 

#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armCOMM.h"


/**
 * Function: omxVCM4P2_DecodeBlockCoef_Inter
 *
 * Description:
 * Decodes the INTER block coefficients. Inverse quantization, inversely zigzag
 * positioning and IDCT, with appropriate clipping on each step, are performed
 * on the coefficients. The results (residuals) are placed in a contiguous array
 * of 64 elements. For INTER block, the output buffer holds the residuals for
 * further reconstruction.
 *
 * Remarks:
 *
 * Parameters:
 * [in]	ppBitStream		pointer to the pointer to the current byte in
 *								the bit stream buffer. There is no boundary
 *								check for the bit stream buffer.
 * [in]	pBitOffset		pointer to the bit position in the byte pointed
 *								to by *ppBitStream. *pBitOffset is valid within
 *								[0-7]
 * [in]	QP				quantization parameter
 * [in] shortVideoHeader    a flag indicating presence of short_video_header;
 *                           shortVideoHeader==1 indicates using quantization method defined in short
 *                           video header mode, and shortVideoHeader==0 indicates normail quantization method.
 * [out] ppBitStream 	*ppBitStream is updated after the block is decoded, so that it points to the
 *                      current byte in the bit stream buffer.
 * [out] pBitOffset		*pBitOffset is updated so that it points to the current bit position in the
 *                      byte pointed by *ppBitStream
 * [out] pDst			pointer to the decoded residual buffer (a contiguous array of 64 elements of
 *                      OMX_S16 data type). Must be 16-byte aligned.
 *
 * Return Value:
 * OMX_Sts_NoErr - no error
 * OMX_Sts_BadArgErr - bad arguments
 *   - At least one of the following pointers is Null: ppBitStream, *ppBitStream, pBitOffset , pDst
 *   - At least one of the below case:
 *   - *pBitOffset exceeds [0,7], QP <= 0;
 *	 - pDst not 16-byte aligned
 * OMX_Sts_Err - status error
 *
 */
OMXResult omxVCM4P2_DecodeBlockCoef_Inter(
     const OMX_U8 ** ppBitStream,
     OMX_INT * pBitOffset,
     OMX_S16 * pDst,
     OMX_INT QP,
     OMX_INT shortVideoHeader
)
{
    /* 64 elements are needed but to align it to 16 bytes need
    15 more elements of padding */
    OMX_S16 tempBuf[79];
    OMX_S16 *pTempBuf1;
    OMXResult errorCode;
    /* Aligning the local buffers */
    pTempBuf1 = armAlignTo16Bytes(tempBuf);
    
    
    /* VLD and zigzag */
    errorCode = omxVCM4P2_DecodeVLCZigzag_Inter(ppBitStream, pBitOffset, 
                                        pTempBuf1,shortVideoHeader);
    armRetDataErrIf((errorCode != OMX_Sts_NoErr), errorCode);
    
    /* Dequantization */
    errorCode = omxVCM4P2_QuantInvInter_I(
     pTempBuf1,
     QP);
    armRetDataErrIf((errorCode != OMX_Sts_NoErr), errorCode);
    
    /* Inverse transform */
    errorCode = omxVCM4P2_IDCT8x8blk(pTempBuf1, pDst);
    armRetDataErrIf((errorCode != OMX_Sts_NoErr), errorCode);
	    
    return OMX_Sts_NoErr;
}

/* End of file */


