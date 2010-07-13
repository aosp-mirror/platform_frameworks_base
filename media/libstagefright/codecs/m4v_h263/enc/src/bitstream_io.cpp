/* ------------------------------------------------------------------
 * Copyright (C) 1998-2009 PacketVideo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 * -------------------------------------------------------------------
 */
/* Date: 8/02/04                                                                */
/* Description:                                                                 */
/*  Change the bitstream parsing algorithm. Use temporary word of 2 or 4 bytes  */
/*  before writing it to the bitstream buffer.                                  */
/*  Note byteCount doesn't have to be multiple of 2 or 4                        */
/*********************************************************************************/

#include "bitstream_io.h"
#include "m4venc_oscl.h"
#include <stdlib.h>

static const UChar Mask[ ] =
{
    0x00, 0x01, 0x03, 0x07, 0x0F, 0x1F, 0x3F, 0x7F, 0xFF
};

#define WORD_SIZE   4   /* for 32-bit machine */

/*Note:
    1. There is a problem when output the last bits(which can not form a byte yet
    so when you output, you need to stuff to make sure it is a byte
    2.  I now hard coded byte to be 8 bits*/


/* ======================================================================== */
/*  Function : BitStreamCreateEnc(Int bufferSize )                          */
/*  Date     : 08/29/2000                                                   */
/*  Purpose  : Create a bitstream to hold one encoded video packet or frame */
/*  In/out   :                                                              */
/*      bufferSize  :   size of the bitstream buffer in bytes               */
/*  Return   : Pointer to the BitstreamEncVideo                             */
/*  Modified :                                                              */
/* ======================================================================== */

BitstreamEncVideo *BitStreamCreateEnc(Int bufferSize)
{
    BitstreamEncVideo *stream;
    stream = (BitstreamEncVideo *) M4VENC_MALLOC(sizeof(BitstreamEncVideo));
    if (stream == NULL)
    {
        return NULL;
    }
    stream->bufferSize = bufferSize;
    stream->bitstreamBuffer = (UChar *) M4VENC_MALLOC(stream->bufferSize * sizeof(UChar));
    if (stream->bitstreamBuffer == NULL)
    {
        M4VENC_FREE(stream);
        stream = NULL;
        return NULL;
    }
    M4VENC_MEMSET(stream->bitstreamBuffer, 0, stream->bufferSize*sizeof(UChar));
    stream->word = 0;
#if WORD_SIZE==4
    stream->bitLeft = 32;
#else
    stream->bitLeft = 16;
#endif
    stream->byteCount = 0;

    stream->overrunBuffer = NULL;
    stream->oBSize = 0;

    return stream;
}

/* ======================================================================== */
/*  Function : BitstreamCloseEnc( )                                         */
/*  Date     : 08/29/2000                                                   */
/*  Purpose  : close a bitstream                                            */
/*  In/out   :
        stream  :   the bitstream to be closed                              */
/*  Return   :                                                              */
/*  Modified :                                                              */
/* ======================================================================== */

Void  BitstreamCloseEnc(BitstreamEncVideo *stream)
{
    if (stream)
    {
        if (stream->bitstreamBuffer)
        {
            M4VENC_FREE(stream->bitstreamBuffer);
        }

        M4VENC_FREE(stream);
    }
}


/* ======================================================================== */
/*  Function : BitstreamPutBits(BitstreamEncVideo *stream, Int Length,
                         Int Value)                                         */
/*  Date     : 08/29/2000                                                   */
/*  Purpose  : put Length (1-16) number of bits to the stream               */
/*            for 32-bit machine this function can do upto 32 bit input     */
/*  In/out   :                                                              */
/*      stream      the bitstream where the bits are put in                 */
/*      Length      bits length (should belong to 1 to 16)                  */
/*      Value       those bits value                                        */
/*  Return   :  PV_STATUS                                                   */
/*  Modified :                                                              */
/* ======================================================================== */
PV_STATUS BitstreamPutBits(BitstreamEncVideo *stream, Int Length, UInt Value)
{
    PV_STATUS status;

    if (stream->bitLeft > Length)
    {
        stream->word <<= Length;
        stream->word |= Value;  /* assuming Value is not larger than Length */
        stream->bitLeft -= Length;
        return PV_SUCCESS;
    }
    else
    {

        stream->word <<= stream->bitLeft;
        Length -= stream->bitLeft;
        stream->word |= ((UInt)Value >> Length);

        status = BitstreamSaveWord(stream);
        if (status != PV_SUCCESS)
        {
            return status;
        }

        /* we got new Length and Value */
        /* note that Value is not "clean" because of msb are not masked out */
        stream->word = Value;
        stream->bitLeft -= Length;
        /* assuming that Length is no more than 16 bits */
        /* stream->bitLeft should be greater than zero at this point */
        //if(stream->bitLeft<=0)
        //  exit(-1);
        return PV_SUCCESS;
    }
}

/* ======================================================================== */
/*  Function : BitstreamPutGT16Bits(BitstreamEncVideo *stream, Int Length, UInt32 Value)    */
/*  Date     : 08/29/2000                                                   */
/*  Purpose  : Use this function to put Length (17-32) number of bits to    */
/*              for 16-bit machine  the stream.                             */
/*  In/out   :                                                              */
/*      stream      the bitstream where the bits are put in                 */
/*      Length      bits length (should belong to 17 to 32)                 */
/*      Value       those bits value                                        */
/*  Return   :  PV_STATUS                                                   */
/*  Modified :                                                              */
/* ======================================================================== */
PV_STATUS BitstreamPutGT16Bits(BitstreamEncVideo *stream, Int Length, ULong Value)
{
    PV_STATUS status;
    UInt topValue;
    Int topLength;

    topValue = (Value >> 16);
    topLength = Length - 16;

    if (topLength > 0)
    {
        status = BitstreamPutBits(stream, topLength, topValue);

        if (status != PV_SUCCESS)
        {
            return status;
        }

        status = BitstreamPutBits(stream, 16, (UInt)(Value & 0xFFFF));

        return status;
    }
    else
    {
        status = BitstreamPutBits(stream, Length, (UInt)Value);
        return status;
    }
}

/* ======================================================================== */
/*  Function : BitstreamSaveWord                                            */
/*  Date     : 08/03/2004                                                   */
/*  Purpose  : save written word into the bitstream buffer.                 */
/*  In/out   :                                                              */
/*      stream      the bitstream where the bits are put in                 */
/*  Return   :  PV_STATUS                                                   */
/*  Modified :                                                              */
/* ======================================================================== */

PV_STATUS BitstreamSaveWord(BitstreamEncVideo *stream)
{
    UChar *ptr;
    UInt word;

    /* assume that stream->bitLeft is always zero when this function is called */
    if (stream->byteCount + WORD_SIZE > stream->bufferSize)
    {
        if (PV_SUCCESS != BitstreamUseOverrunBuffer(stream, WORD_SIZE))
        {
            stream->byteCount += WORD_SIZE;
            return PV_FAIL;
        }
    }

    ptr = stream->bitstreamBuffer + stream->byteCount;
    word = stream->word;
    stream->word = 0; /* important to reset to zero */

    /* NOTE: byteCount does not have to be multiple of 2 or 4 */
#if (WORD_SIZE == 4)
    *ptr++ = word >> 24;
    *ptr++ = 0xFF & (word >> 16);
#endif

    *ptr++ = 0xFF & (word >> 8);
    *ptr = 0xFF & word;

#if (WORD_SIZE == 4)
    stream->byteCount += 4;
    stream->bitLeft = 32;
#else
    stream->byteCount += 2;
    stream->bitLeft = 16;
#endif

    return PV_SUCCESS;
}


/* ======================================================================== */
/*  Function : BitstreamSavePartial                                         */
/*  Date     : 08/03/2004                                                   */
/*  Purpose  : save unfinished written word into the bitstream buffer.      */
/*  In/out   :                                                              */
/*      stream      the bitstream where the bits are put in                 */
/*  Return   :  PV_STATUS                                                   */
/*  Modified :                                                              */
/* ======================================================================== */

PV_STATUS BitstreamSavePartial(BitstreamEncVideo *stream, Int *fraction)
{
    UChar *ptr;
    UInt word, shift;
    Int numbyte, bitleft, bitused;

    bitleft = stream->bitLeft;
    bitused = (WORD_SIZE << 3) - bitleft; /* number of bits used */
    numbyte = bitused >> 3; /* number of byte fully used */

    if (stream->byteCount + numbyte > stream->bufferSize)
    {
        if (PV_SUCCESS != BitstreamUseOverrunBuffer(stream, numbyte))
        {
            stream->byteCount += numbyte;
            return PV_FAIL;
        }
    }

    ptr = stream->bitstreamBuffer + stream->byteCount;
    word = stream->word;
    word <<= bitleft;   /* word is not all consumed */
    bitleft = bitused - (numbyte << 3); /* number of bits used (fraction) */
    stream->byteCount += numbyte;
    if (bitleft)
    {
        *fraction = 1;
    }
    else
    {
        *fraction = 0;
    }
    bitleft = (WORD_SIZE << 3) - bitleft;
    /* save new value */
    stream->bitLeft = bitleft;

    shift = ((WORD_SIZE - 1) << 3);
    while (numbyte)
    {
        *ptr++ = (UChar)((word >> shift) & 0xFF);
        word <<= 8;
        numbyte--;
    }

    if (*fraction)
    {// this could lead to buffer overrun when ptr is already out of bound.
        //  *ptr = (UChar)((word>>shift)&0xFF); /* need to do it for the last fractional byte */
    }

    /* save new values */
    stream->word = word >> bitleft;

    /* note we don't update byteCount, bitLeft and word */
    /* so that encoder can continue PutBits if they don't */

    return PV_SUCCESS;
}


/* ======================================================================== */
/*  Function : BitstreamShortHeaderByteAlignStuffing(                       */
/*                                      BitstreamEncVideo *stream)          */
/*  Date     : 08/29/2000                                                   */
/*  Purpose  : bit stuffing for next start code in short video header       */
/*  In/out   :                                                              */
/*  Return   :  number of bits to be stuffed                                */
/*  Modified :                                                              */
/* ======================================================================== */

Int BitstreamShortHeaderByteAlignStuffing(BitstreamEncVideo *stream)
{
    UInt restBits;
    Int fraction;

    restBits = (stream->bitLeft & 0x7); /* modulo 8 */

    if (restBits)  /*short_video_header[0] is 1 in h263 baseline*/
    {
        /* H.263 style stuffing */
        BitstreamPutBits(stream, restBits, 0);
    }

    if (stream->bitLeft != (WORD_SIZE << 3))
    {
        BitstreamSavePartial(stream, &fraction);
    }

    return restBits;
}

/* ======================================================================== */
/*  Function : BitstreamMpeg4ByteAlignStuffing(BitstreamEncVideo *stream)   */
/*  Date     : 08/29/2000                                                   */
/*  Purpose  : bit stuffing for next start code in MPEG-4                  */
/*  In/out   :                                                              */
/*  Return   :  number of bits to be stuffed                                */
/*  Modified :                                                              */
/* ======================================================================== */
Int BitstreamMpeg4ByteAlignStuffing(BitstreamEncVideo *stream)
{

    UInt restBits;
    Int fraction;
    /* Question: in MPEG-4 , short_video_header[0]==0 => even already byte aligned, will still stuff 8 bits
       need to check with  */
    /*if (!(getPointerENC(index1, index2)%8) && short_video_header[0]) return 0;*/

    /* need stuffing bits, */
    BitstreamPutBits(stream, 1, 0);

    restBits = (stream->bitLeft & 0x7); /* modulo 8 */

    if (restBits)  /*short_video_header[0] is 1 in h263 baseline*/
    {
        /* need stuffing bits, */
        BitstreamPutBits(stream, restBits, Mask[restBits]);
    }

    if (stream->bitLeft != (WORD_SIZE << 3))
    {
        BitstreamSavePartial(stream, &fraction);
    }

    return (restBits);
}

/*does bit stuffing for next resync marker*/
/*  does bit stuffing for next resync marker
 *                                            "0"
 *                                           "01"
 *                                          "011"
 *                                         "0111"
 *                                        "01111"
 *                                       "011111"
 *                                      "0111111"
 *                                     "01111111"   (8-bit codeword)
 */

/*Int BitstreamNextResyncMarkerEnc(BitstreamEncVideo *stream)
{
  Int count;
  BitstreamPut1Bits(stream,0);
  count=8-stream->totalBits & 8;
  BitstreamPutBits(stream,count,Mask[count]);
  return count;
}*/

/* ======================================================================== */
/*  Function : BitstreamAppendEnc( BitstreamEncVideo *bitstream1,           */
/*                                      BitstreamEncVideo *bitstream2   )   */
/*  Date     : 08/29/2000                                                   */
/*  Purpose  : Append the intermediate bitstream (bitstream2) to the end of */
/*                              output bitstream(bitstream1)                */
/*  In/out   :                                                              */
/*  Return   :                                                              */
/*  Modified :                                                              */
/* ======================================================================== */


PV_STATUS BitstreamAppendEnc(BitstreamEncVideo *bitstream1, BitstreamEncVideo *bitstream2)
{
    PV_STATUS status;
    UChar *ptrBS2, *ptrBS1;
    UChar byteBS2, byteBS1;
    Int  numbyte2;
    Int bitused, bitleft, offset, fraction;

    status = BitstreamSavePartial(bitstream1, &fraction);
    if (status != PV_SUCCESS)
    {
        return status;
    }

    offset = fraction;
    status = BitstreamSavePartial(bitstream2, &fraction);
    if (status != PV_SUCCESS)
    {
        return status;
    }

    if (!offset) /* bitstream1 is byte-aligned */
    {
        return BitstreamAppendPacket(bitstream1, bitstream2);
    }

    offset += fraction;

    /* since bitstream1 doesn't have to be byte-aligned, we have to process byte by byte */
    /* we read one byte from bitstream2 and use BitstreamPutBits to do the job */
    if (bitstream1->byteCount + bitstream2->byteCount + offset > bitstream1->bufferSize)
    {
        if (PV_SUCCESS != BitstreamUseOverrunBuffer(bitstream1, bitstream2->byteCount + offset))
        {
            bitstream1->byteCount += (bitstream2->byteCount + offset);
            return PV_FAIL;
        }
    }

    ptrBS1 = bitstream1->bitstreamBuffer + bitstream1->byteCount; /* move ptr bs1*/
    ptrBS2 = bitstream2->bitstreamBuffer;

    bitused = (WORD_SIZE << 3) - bitstream1->bitLeft; /* this must be between 1-7 */
    bitleft = 8 - bitused;

    numbyte2 = bitstream2->byteCount;   /* number of byte to copy from bs2 */
    bitstream1->byteCount += numbyte2;  /* new byteCount */

    byteBS1 = ((UChar) bitstream1->word) << bitleft;    /* fraction byte from bs1 */

    while (numbyte2)
    {
        byteBS2 = *ptrBS2++;
        byteBS1 |= (byteBS2 >> bitused);
        *ptrBS1++ = byteBS1;
        byteBS1 = byteBS2 << bitleft;
        numbyte2--;
    }

    bitstream1->word = byteBS1 >> bitleft;  /* bitstream->bitLeft remains the same */

    /* now save bs2->word in bs1 */
    status = BitstreamPutBits(bitstream1, (WORD_SIZE << 3) - bitstream2->bitLeft, bitstream2->word);

    return status;
}

/* ======================================================================== */
/*  Function : BitstreamAppendPacket( BitstreamEncVideo *bitstream1,        */
/*                                      BitstreamEncVideo *bitstream2   )   */
/*  Date     : 05/31/2001                                                   */
/*  Purpose  : Append the intermediate bitstream (bitstream2) to the end of */
/*              output bitstream(bitstream1) knowing that bitstream1 is byte-aligned*/
/*  In/out   :                                                              */
/*  Return   :                                                              */
/*  Modified :                                                              */
/* ======================================================================== */
PV_STATUS BitstreamAppendPacket(BitstreamEncVideo *bitstream1, BitstreamEncVideo *bitstream2)
{
    UChar *ptrBS2, *ptrBS1;
    Int  numbyte2;

    if (bitstream1->byteCount + bitstream2->byteCount  > bitstream1->bufferSize)
    {
        if (PV_SUCCESS != BitstreamUseOverrunBuffer(bitstream1, bitstream2->byteCount))
        {
            bitstream1->byteCount += bitstream2->byteCount; /* legacy, to keep track of total bytes */
            return PV_FAIL;
        }
    }

    ptrBS1 = bitstream1->bitstreamBuffer + bitstream1->byteCount; /* move ptr bs1*/
    ptrBS2 = bitstream2->bitstreamBuffer;

    numbyte2 = bitstream2->byteCount;
    bitstream1->byteCount += numbyte2; /* new byteCount */

    /*copy all the bytes in bitstream2*/
    M4VENC_MEMCPY(ptrBS1, ptrBS2, sizeof(UChar)*numbyte2);

    bitstream1->word = bitstream2->word;  /* bitstream1->bitLeft is the same */
    bitstream1->bitLeft = bitstream2->bitLeft;

    return PV_SUCCESS;
}

/* ======================================================================== */
/*  Function : BitstreamAppendPacketNoOffset( BitstreamEncVideo *bitstream1,*/
/*                                      BitstreamEncVideo *bitstream2   )   */
/*  Date     : 04/23/2002                                                   */
/*  Purpose  : Append the intermediate bitstream (bitstream2) to the end of */
/*              output bitstream(bitstream1) , for slice-based coding only */
/*  In/out   :                                                              */
/*  Return   :                                                              */
/*  Modified :                                                              */
/* ======================================================================== */
PV_STATUS BitstreamAppendPacketNoOffset(BitstreamEncVideo *bitstream1, BitstreamEncVideo *bitstream2)
{
    PV_STATUS status = PV_SUCCESS;
    UChar *ptrBS2, *ptrBS1;
    Int  numbyte2;
    Int  byteleft;

    numbyte2 = bitstream2->byteCount;

    if (bitstream1->byteCount + bitstream2->byteCount > bitstream1->bufferSize)
    {
        numbyte2 =  bitstream1->bufferSize - bitstream1->byteCount;
        status =  PV_END_OF_BUF;    /* signal end of buffer */
    }

    ptrBS1 = bitstream1->bitstreamBuffer; /* move ptr bs1*/
    ptrBS2 = bitstream2->bitstreamBuffer;

    bitstream1->byteCount += numbyte2; /* should be equal to bufferSize */

    /*copy all the bytes in bitstream2*/
    M4VENC_MEMCPY(ptrBS1, ptrBS2, sizeof(UChar)*numbyte2);
    bitstream1->word = 0;
    bitstream1->bitLeft = (WORD_SIZE << 3);

    if (status == PV_END_OF_BUF) /* re-position bitstream2 */
    {
        byteleft = bitstream2->byteCount - numbyte2;

        M4VENC_MEMCPY(ptrBS2, ptrBS2 + numbyte2, sizeof(UChar)*byteleft);

        bitstream2->byteCount = byteleft;
        /* bitstream2->word and bitstream->bitLeft are unchanged.
           they should be 0 and (WORD_SIZE<<3) */
    }

    return status;
}

#ifndef NO_SLICE_ENCODE
/* ======================================================================== */
/*  Function : BitstreamRepos( BitstreamEncVideo *bitstream,                */
/*                                      Int byteCount, Int bitCount)        */
/*  Date     : 04/28/2002                                                   */
/*  Purpose  : Reposition the size of the buffer content (curtail)          */
/*  In/out   :                                                              */
/*  Return   :                                                              */
/*  Modified :                                                              */
/* ======================================================================== */
PV_STATUS   BitstreamRepos(BitstreamEncVideo *bitstream, Int byteCount, Int bitCount)
{
    UChar *ptr, byte;
    UInt word;
    Int fraction;

    BitstreamSavePartial(bitstream, &fraction);

    bitstream->byteCount = byteCount;
    ptr = bitstream->bitstreamBuffer + byteCount; /* get fraction of the byte */
    if (bitCount)
    {
        bitstream->bitLeft = (WORD_SIZE << 3) - bitCount; /* bitCount should be 0-31 */
        word = *ptr++;
        byte = *ptr++;
        word = byte | (word << 8);
#if (WORD_SIZE == 4)
        byte = *ptr++;
        word = byte | (word << 8);
        byte = *ptr++;
        word = byte | (word << 8);
#endif
        bitstream->word = word >> (bitstream->bitLeft);
    }
    else
    {
        bitstream->word = 0;
        bitstream->bitLeft = (WORD_SIZE << 3);
    }

    return PV_SUCCESS;
}

/* ======================================================================== */
/*  Function : BitstreamFlushBits(BitstreamEncVideo *bitstream1,            */
/*                              Int num_bit_left)                           */
/*  Date     : 04/24/2002                                                   */
/*  Purpose  : Flush buffer except the last num_bit_left bits.              */
/*  In/out   :                                                              */
/*  Return   :                                                              */
/*  Modified :                                                              */
/* ======================================================================== */


PV_STATUS BitstreamFlushBits(BitstreamEncVideo *bitstream1, Int num_bit_left)
{
    Int i;
    UChar *ptrDst, *ptrSrc;
    Int leftover, bitused;
    Int new_byte = (num_bit_left >> 3);
    Int new_bit = num_bit_left - (new_byte << 3); /* between 0-7 */

    ptrSrc = bitstream1->bitstreamBuffer + bitstream1->byteCount;
    ptrDst = bitstream1->bitstreamBuffer;

    bitused = (WORD_SIZE << 3) - bitstream1->bitLeft;

    leftover = 8 - bitused; /* bitused should be between 0-7 */

    bitstream1->byteCount = new_byte;
    bitstream1->bitLeft = (WORD_SIZE << 3) - new_bit;

    if (!bitused) /* byte aligned */
    {
        M4VENC_MEMCPY(ptrDst, ptrSrc, new_byte + 1);
    }
    else
    {
        /*copy all the bytes in bitstream2*/
        for (i = 0; i < new_byte; i++)
        {
            *ptrDst++ = (ptrSrc[0] << bitused) | (ptrSrc[1] >> leftover);
            ptrSrc++;
        }
        /* copy for the last byte of ptrSrc, copy extra bits doesn't hurt */
        if (new_bit)
        {
            *ptrDst++ = (ptrSrc[0] << bitused) | (ptrSrc[1] >> leftover);
            ptrSrc++;
        }
    }
    if (new_bit)
    {
        ptrSrc = bitstream1->bitstreamBuffer + new_byte;
        bitstream1->word = (*ptrSrc) >> (8 - new_bit);
    }

    return PV_SUCCESS;
}

/* ======================================================================== */
/*  Function : BitstreamPrependPacket( BitstreamEncVideo *bitstream1,       */
/*                                      BitstreamEncVideo *bitstream2   )   */
/*  Date     : 04/26/2002                                                   */
/*  Purpose  : Prepend the intermediate bitstream (bitstream2) to the beginning of */
/*              output bitstream(bitstream1) */
/*  In/out   :                                                              */
/*  Return   :                                                              */
/*  Modified :                                                              */
/* ======================================================================== */
PV_STATUS BitstreamPrependPacket(BitstreamEncVideo *bitstream1, BitstreamEncVideo *bitstream2)
{
    UChar *pSrc, *pDst, byte;
    Int     movebyte, bitused, leftover, i, fraction;

    BitstreamSavePartial(bitstream2, &fraction); /* make sure only fraction of byte left */
    BitstreamSavePartial(bitstream1, &fraction);

    if (bitstream1->byteCount + bitstream2->byteCount >= bitstream1->bufferSize)
    {
        bitstream1->byteCount += bitstream2->byteCount;
        return PV_END_OF_BUF;
    }

    movebyte = bitstream1->byteCount;
    if (movebyte < bitstream2->byteCount)
        movebyte = bitstream2->byteCount;
    movebyte++;

    /* shift bitstream1 to the right by movebyte */
    pSrc = bitstream1->bitstreamBuffer;
    pDst = pSrc + movebyte;

    M4VENC_MEMCPY(pDst, pSrc, bitstream1->byteCount + 1);

    /* copy bitstream2 to the beginning of bitstream1 */
    M4VENC_MEMCPY(pSrc, bitstream2->bitstreamBuffer, bitstream2->byteCount + 1);

    /* now shift back previous bitstream1 buffer to the end */
    pSrc = pDst;
    pDst = bitstream1->bitstreamBuffer + bitstream2->byteCount;

    bitused = (WORD_SIZE << 3) - bitstream2->bitLeft;
    leftover = 8 - bitused;     /* bitused should be 0-7 */

    byte = (bitstream2->word) << leftover;

    *pDst++ = byte | (pSrc[0] >> bitused);

    for (i = 0; i < bitstream1->byteCount + 1; i++)
    {
        *pDst++ = ((pSrc[0] << leftover) | (pSrc[1] >> bitused));
        pSrc++;
    }

    bitstream1->byteCount += bitstream2->byteCount;
    //bitstream1->bitCount += bitstream2->bitCount;
    bitused = (WORD_SIZE << 4) - (bitstream1->bitLeft + bitstream2->bitLeft);

    if (bitused >= 8)
    {
        bitused -= 8;
        bitstream1->byteCount++;
    }

    bitstream1->bitLeft = (WORD_SIZE << 3) - bitused;

    bitstream2->byteCount = bitstream2->word = 0;
    bitstream2->bitLeft = (WORD_SIZE << 3);

    pSrc = bitstream1->bitstreamBuffer + bitstream1->byteCount;
    leftover = 8 - bitused;
    //*pSrc = (pSrc[0]>>leftover)<<leftover; /* make sure the rest of bits are zeros */

    bitstream1->word = (UInt)((pSrc[0]) >> leftover);

    return PV_SUCCESS;
}
#endif  /* NO_SLICE_ENCODE */


/* ======================================================================== */
/*  Function : BitstreamGetPos( BitstreamEncVideo *stream                   */
/*  Date     : 08/05/2004                                                   */
/*  Purpose  : Get the bit position.                                        */
/*  In/out   :                                                              */
/*  Return   :                                                              */
/*  Modified :                                                              */
/* ======================================================================== */
Int BitstreamGetPos(BitstreamEncVideo *stream)
{

    return stream->byteCount*8 + (WORD_SIZE << 3) - stream->bitLeft;
}

void BitstreamEncReset(BitstreamEncVideo *stream)
{
    stream->bitLeft = (WORD_SIZE << 3);
    stream->word = 0;
    stream->byteCount = 0;
    return ;
}

/* This function set the overrun buffer, and VideoEncData context for callback to reallocate
overrun buffer.  */
Void  BitstreamSetOverrunBuffer(BitstreamEncVideo* stream, UChar* overrunBuffer, Int oBSize, VideoEncData *video)
{
    stream->overrunBuffer = overrunBuffer;
    stream->oBSize = oBSize;
    stream->video = video;

    return ;
}


/* determine whether overrun buffer can be used or not */
PV_STATUS BitstreamUseOverrunBuffer(BitstreamEncVideo* stream, Int numExtraBytes)
{
    VideoEncData *video = stream->video;

    if (stream->overrunBuffer != NULL) // overrunBuffer is set
    {
        if (stream->bitstreamBuffer != stream->overrunBuffer) // not already used
        {
            if (stream->byteCount + numExtraBytes >= stream->oBSize)
            {
                stream->oBSize = stream->byteCount + numExtraBytes + 100;
                stream->oBSize &= (~0x3); // make it multiple of 4

                // allocate new overrun Buffer
                if (video->overrunBuffer)
                {
                    M4VENC_FREE(video->overrunBuffer);
                }
                video->oBSize = stream->oBSize;
                video->overrunBuffer = (UChar*) M4VENC_MALLOC(sizeof(UChar) * stream->oBSize);
                stream->overrunBuffer = video->overrunBuffer;
                if (stream->overrunBuffer == NULL)
                {
                    return PV_FAIL;
                }
            }

            // copy everything to overrun buffer and start using it.
            memcpy(stream->overrunBuffer, stream->bitstreamBuffer, stream->byteCount);
            stream->bitstreamBuffer = stream->overrunBuffer;
            stream->bufferSize = stream->oBSize;
        }
        else // overrun buffer is already used
        {
            if (stream->byteCount + numExtraBytes >= stream->oBSize)
            {
                stream->oBSize = stream->byteCount + numExtraBytes + 100;
            }

            // allocate new overrun buffer
            stream->oBSize &= (~0x3); // make it multiple of 4
            video->oBSize = stream->oBSize;
            video->overrunBuffer = (UChar*) M4VENC_MALLOC(sizeof(UChar) * stream->oBSize);
            if (video->overrunBuffer == NULL)
            {
                return PV_FAIL;
            }

            // copy from the old buffer to new buffer
            memcpy(video->overrunBuffer, stream->overrunBuffer, stream->byteCount);
            // free old buffer
            M4VENC_FREE(stream->overrunBuffer);
            // assign pointer to new buffer
            stream->overrunBuffer = video->overrunBuffer;
            stream->bitstreamBuffer = stream->overrunBuffer;
            stream->bufferSize = stream->oBSize;
        }

        return PV_SUCCESS;
    }
    else // overrunBuffer is not enable.
    {
        return PV_FAIL;
    }

}







