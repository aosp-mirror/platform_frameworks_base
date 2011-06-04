/* ----------------------------------------------------------------
 *
 * 
 * File Name:  omxVCM4P10_DecodeCoeffsToPairCAVLC.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 *
 * H.264 decode coefficients module
 * 
 */
 
#include "omxtypes.h"
#include "armOMX.h"
#include "omxVC.h"

#include "armCOMM.h"
#include "armVC.h"

/**
 * Function: omxVCM4P10_DecodeCoeffsToPairCAVLC
 *
 * Description:
 * Performs CAVLC decoding and inverse zigzag scan for 4x4 block of 
 * Intra16x16DCLevel, Intra16x16ACLevel,LumaLevel, and ChromaACLevel. 
 * Inverse field scan is not supported. The decoded coefficients in packed 
 * position-coefficient buffer are stored in increasing zigzag order instead 
 * of position order.
 *
 * Remarks:
 *
 * Parameters:
 * [in]	ppBitStream		Double pointer to current byte in bit stream buffer
 * [in]	pOffset			Pointer to current bit position in the byte pointed
 *								to by *ppBitStream
 * [in]	sMaxNumCoeff	Maximum number of non-zero coefficients in current
 *								block
 * [in]	sVLCSelect		VLC table selector, obtained from number of non-zero
 *								AC coefficients of above and left 4x4 blocks. It is 
 *								equivalent to the variable nC described in H.264 standard 
 *								table 9-5, except its value can¡¯t be less than zero.
 * [out]	ppBitStream		*ppBitStream is updated after each block is decoded
 * [out]	pOffset			*pOffset is updated after each block is decoded
 * [out]	pNumCoeff		Pointer to the number of nonzero coefficients in
 *								this block
 * [out]	ppPosCoefbuf	Double pointer to destination residual
 *								coefficient-position pair buffer
 * Return Value:
 * Standard omxError result. See enumeration for possible result codes.
 *
 */

OMXResult omxVCM4P10_DecodeCoeffsToPairCAVLC(
     const OMX_U8** ppBitStream,
     OMX_S32* pOffset,
     OMX_U8* pNumCoeff,
     OMX_U8**ppPosCoefbuf,
     OMX_INT sVLCSelect,
     OMX_INT sMaxNumCoeff        
 )
{
    return armVCM4P10_DecodeCoeffsToPair(ppBitStream, pOffset, pNumCoeff,
                                         ppPosCoefbuf, sVLCSelect, sMaxNumCoeff);
}
