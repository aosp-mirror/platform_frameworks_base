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
 * Function:  omxVCM4P2_DecodeBlockCoef_Inter   (6.2.5.4.2)
 *
 * Description:
 * Decodes the INTER block coefficients. This function performs inverse 
 * quantization, inverse zigzag positioning, and IDCT (with appropriate 
 * clipping on each step) on the coefficients. The results (residuals) are 
 * placed in a contiguous array of 64 elements. For INTER block, the output 
 * buffer holds the residuals for further reconstruction. 
 *
 * Input Arguments:
 *   
 *   ppBitStream - pointer to the pointer to the current byte in the bit 
 *            stream buffer. There is no boundary check for the bit stream 
 *            buffer. 
 *   pBitOffset - pointer to the bit position in the byte pointed to by 
 *            *ppBitStream. *pBitOffset is valid within [0-7] 
 *   QP - quantization parameter 
 *   shortVideoHeader - binary flag indicating presence of 
 *            short_video_header; shortVideoHeader==1 selects linear intra DC 
 *            mode, and shortVideoHeader==0 selects non linear intra DC mode. 
 *
 * Output Arguments:
 *   
 *   ppBitStream - *ppBitStream is updated after the block is decoded, so 
 *            that it points to the current byte in the bit stream buffer 
 *   pBitOffset - *pBitOffset is updated so that it points to the current bit 
 *            position in the byte pointed by *ppBitStream 
 *   pDst - pointer to the decoded residual buffer (a contiguous array of 64 
 *            elements of OMX_S16 data type); must be aligned on a 16-byte 
 *            boundary. 
 *
 * Return Value:
 *    
 *    OMX_Sts_NoErr - no error 
 *    OMX_Sts_BadArgErr - bad arguments, if:
 *    -    At least one of the following pointers is Null: 
 *         ppBitStream, *ppBitStream, pBitOffset , pDst 
 *    -    *pBitOffset exceeds [0,7]
 *    -    QP <= 0. 
 *    -    pDst is not 16-byte aligned 
 *    OMX_Sts_Err - status error. Refer to OMX_Sts_Err of DecodeVLCZigzag_Inter . 
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
    
    /* Argument error checks */
    armRetArgErrIf(ppBitStream == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(*ppBitStream == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pBitOffset == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(pDst == NULL, OMX_Sts_BadArgErr);
    armRetArgErrIf(!armIs16ByteAligned(pDst), OMX_Sts_BadArgErr);
    armRetArgErrIf(((QP <= 0) || (QP >= 32)), OMX_Sts_BadArgErr);
	armRetArgErrIf(((*pBitOffset < 0) || (*pBitOffset > 7)), OMX_Sts_BadArgErr);


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


