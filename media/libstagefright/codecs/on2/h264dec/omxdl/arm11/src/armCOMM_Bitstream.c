/**
 * 
 * File Name:  armCOMM_Bitstream.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 *
 * Defines bitstream encode and decode functions common to all codecs
 */

#include "omxtypes.h"
#include "armCOMM.h"
#include "armCOMM_Bitstream.h"

/***************************************
 * Fixed bit length Decode
 ***************************************/

/**
 * Function: armLookAheadBits()
 *
 * Description:
 * Get the next N bits from the bitstream without advancing the bitstream pointer
 *
 * Parameters:
 * [in]     **ppBitStream
 * [in]     *pOffset
 * [in]     N=1...32
 *
 * Returns  Value
 */

OMX_U32 armLookAheadBits(const OMX_U8 **ppBitStream, OMX_INT *pOffset, OMX_INT N)
{
    const OMX_U8 *pBitStream = *ppBitStream;
    OMX_INT Offset = *pOffset;
    OMX_U32 Value;

    armAssert(Offset>=0 && Offset<=7);
    armAssert(N>=1 && N<=32);

    /* Read next 32 bits from stream */
    Value = (pBitStream[0] << 24 ) | ( pBitStream[1] << 16)  | (pBitStream[2] << 8 ) | (pBitStream[3]) ;
    Value = (Value << Offset ) | (pBitStream[4] >> (8-Offset));

    /* Return N bits */
    return Value >> (32-N);
}


/**
 * Function: armGetBits()
 *
 * Description:
 * Read N bits from the bitstream
 *    
 * Parameters:
 * [in]     *ppBitStream
 * [in]     *pOffset
 * [in]     N=1..32
 *
 * [out]    *ppBitStream
 * [out]    *pOffset
 * Returns  Value
 */


OMX_U32 armGetBits(const OMX_U8 **ppBitStream, OMX_INT *pOffset, OMX_INT N)
{
    const OMX_U8 *pBitStream = *ppBitStream;
    OMX_INT Offset = *pOffset;
    OMX_U32 Value;
    
    if(N == 0)
    {
      return 0;
    }

    armAssert(Offset>=0 && Offset<=7);
    armAssert(N>=1 && N<=32);

    /* Read next 32 bits from stream */
    Value = (pBitStream[0] << 24 ) | ( pBitStream[1] << 16)  | (pBitStream[2] << 8 ) | (pBitStream[3]) ;
    Value = (Value << Offset ) | (pBitStream[4] >> (8-Offset));

    /* Advance bitstream pointer by N bits */
    Offset += N;
    *ppBitStream = pBitStream + (Offset>>3);
    *pOffset = Offset & 7;

    /* Return N bits */
    return Value >> (32-N);
}

/**
 * Function: armByteAlign()
 *
 * Description:
 * Align the pointer *ppBitStream to the next byte boundary
 *
 * Parameters:
 * [in]     *ppBitStream
 * [in]     *pOffset
 *
 * [out]    *ppBitStream
 * [out]    *pOffset
 *
 **/
 
OMXVoid armByteAlign(const OMX_U8 **ppBitStream,OMX_INT *pOffset)
{
    if(*pOffset > 0)
    {
        *ppBitStream += 1;
        *pOffset = 0;
    }    
}

/** 
 * Function: armSkipBits()
 *
 * Description:
 * Skip N bits from the value at *ppBitStream
 *
 * Parameters:
 * [in]     *ppBitStream
 * [in]     *pOffset
 * [in]     N
 *
 * [out]    *ppBitStream
 * [out]    *pOffset
 *
 **/


OMXVoid armSkipBits(const OMX_U8 **ppBitStream,OMX_INT *pOffset,OMX_INT N)
{
    OMX_INT Offset = *pOffset;
    const OMX_U8 *pBitStream = *ppBitStream;
   
    /* Advance bitstream pointer by N bits */
    Offset += N;
    *ppBitStream = pBitStream + (Offset>>3);
    *pOffset = Offset & 7;
}

/***************************************
 * Variable bit length Decode
 ***************************************/

/**
 * Function: armUnPackVLC32()
 *
 * Description:
 * Variable length decode of variable length symbol (max size 32 bits) read from
 * the bit stream pointed by *ppBitStream at *pOffset by using the table
 * pointed by pCodeBook
 * 
 * Parameters:
 * [in]     *pBitStream
 * [in]     *pOffset
 * [in]     pCodeBook
 * 
 * [out]    *pBitStream
 * [out]    *pOffset
 *
 * Returns : Code Book Index if successfull. 
 *         : ARM_NO_CODEBOOK_INDEX = -1 if search fails.
 **/
#ifndef C_OPTIMIZED_IMPLEMENTATION 

OMX_U16 armUnPackVLC32(
    const OMX_U8 **ppBitStream,
    OMX_INT *pOffset,
    const ARM_VLC32 *pCodeBook
)
{    
    const OMX_U8 *pBitStream = *ppBitStream;
    OMX_INT Offset = *pOffset;
    OMX_U32 Value;
    OMX_INT Index;
        
    armAssert(Offset>=0 && Offset<=7);

    /* Read next 32 bits from stream */
    Value = (pBitStream[0] << 24 ) | ( pBitStream[1] << 16)  | (pBitStream[2] << 8 ) | (pBitStream[3]) ;
    Value = (Value << Offset ) | (pBitStream[4] >> (8-Offset));

    /* Search through the codebook */    
    for (Index=0; pCodeBook->codeLen != 0; Index++)
    {
        if (pCodeBook->codeWord == (Value >> (32 - pCodeBook->codeLen)))
        {
            Offset       = Offset + pCodeBook->codeLen;
            *ppBitStream = pBitStream + (Offset >> 3) ;
            *pOffset     = Offset & 7;
            
            return Index;
        }        
        pCodeBook++;
    }

    /* No code match found */
    return ARM_NO_CODEBOOK_INDEX;
}

#endif

/***************************************
 * Fixed bit length Encode
 ***************************************/

/**
 * Function: armPackBits
 *
 * Description:
 * Pack a VLC code word into the bitstream
 *
 * Remarks:
 *
 * Parameters:
 * [in] ppBitStream     pointer to the pointer to the current byte 
 *                      in the bit stream.
 * [in] pOffset         pointer to the bit position in the byte 
 *                      pointed by *ppBitStream. Valid within 0
 *                      to 7.
 * [in] codeWord        Code word that need to be inserted in to the
 *                          bitstream
 * [in] codeLength      Length of the code word valid range 1...32
 *
 * [out] ppBitStream    *ppBitStream is updated after the block is encoded,
 *                          so that it points to the current byte in the bit
 *                          stream buffer.
 * [out] pBitOffset     *pBitOffset is updated so that it points to the
 *                          current bit position in the byte pointed by
 *                          *ppBitStream.
 *
 * Return Value:
 * Standard OMX_RESULT result. See enumeration for possible result codes.
 *
 */
 
OMXResult armPackBits (
    OMX_U8  **ppBitStream, 
    OMX_INT *pOffset,
    OMX_U32 codeWord, 
    OMX_INT codeLength 
)
{
    OMX_U8  *pBitStream = *ppBitStream;
    OMX_INT Offset = *pOffset;
    OMX_U32 Value;
        
    /* checking argument validity */
    armRetArgErrIf(Offset < 0, OMX_Sts_BadArgErr);
    armRetArgErrIf(Offset > 7, OMX_Sts_BadArgErr);
    armRetArgErrIf(codeLength < 1, OMX_Sts_BadArgErr);
    armRetArgErrIf(codeLength > 32, OMX_Sts_BadArgErr);

    /* Prepare the first byte */
    codeWord = codeWord << (32-codeLength);
    Value = (pBitStream[0] >> (8-Offset)) << (8-Offset);
    Value = Value | (codeWord >> (24+Offset));

    /* Write out whole bytes */
    while (8-Offset <= codeLength)
    {
        *pBitStream++ = (OMX_U8)Value;
        codeWord   = codeWord  << (8-Offset);
        codeLength = codeLength - (8-Offset);
        Offset = 0;
        Value = codeWord >> 24;
    }

    /* Write out final partial byte */
    *pBitStream  = (OMX_U8)Value;
    *ppBitStream = pBitStream;
    *pOffset = Offset + codeLength;
    
    return  OMX_Sts_NoErr;
}
 
/***************************************
 * Variable bit length Encode
 ***************************************/

/**
 * Function: armPackVLC32
 *
 * Description:
 * Pack a VLC code word into the bitstream
 *
 * Remarks:
 *
 * Parameters:
 * [in]	ppBitStream		pointer to the pointer to the current byte 
 *                      in the bit stream.
 * [in]	pBitOffset	    pointer to the bit position in the byte 
 *                      pointed by *ppBitStream. Valid within 0
 *                      to 7.
 * [in]	 code     		VLC code word that need to be inserted in to the
 *                      bitstream
 *
 * [out] ppBitStream	*ppBitStream is updated after the block is encoded,
 *	                    so that it points to the current byte in the bit
 *						stream buffer.
 * [out] pBitOffset		*pBitOffset is updated so that it points to the
 *						current bit position in the byte pointed by
 *						*ppBitStream.
 *
 * Return Value:
 * Standard OMX_RESULT result. See enumeration for possible result codes.
 *
 */
 
OMXResult armPackVLC32 (
    OMX_U8 **ppBitStream, 
    OMX_INT *pBitOffset,
    ARM_VLC32 code 
)
{
    return (armPackBits(ppBitStream, pBitOffset, code.codeWord, code.codeLen));
}

/*End of File*/
