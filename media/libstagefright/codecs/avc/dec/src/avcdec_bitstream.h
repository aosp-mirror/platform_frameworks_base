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
/**
This file contains bitstream related functions.
@publishedAll
*/

#ifndef _AVCDEC_BITSTREAM_H_
#define _AVCDEC_BITSTREAM_H_

#include "avcdec_lib.h"

#define WORD_SIZE   32  /* this can vary, default to 32 bit for now */

#ifndef __cplusplus

#define AVC_GETDATA(x,y)   userData->AVC_GetData(x,y)

#endif

#ifdef __cplusplus
extern "C"
{
#endif
#define BitstreamFlushBits(A,B)     {(A)->bitcnt += (B); (A)->incnt -= (B); (A)->curr_word <<= (B);}

    AVCDec_Status AVC_BitstreamFillCache(AVCDecBitstream *stream);
    /**
    This function populates bitstream structure.
    \param "stream" "Pointer to bitstream structure."
    \param "buffer" "Pointer to the bitstream buffer."
    \param "size"   "Size of the buffer."
    \param "nal_size"   "Size of the NAL unit."
    \param "resetall"   "Flag for reset everything."
    \return "AVCDEC_SUCCESS for success and AVCDEC_FAIL for fail."
    */
    AVCDec_Status BitstreamInit(AVCDecBitstream *stream, uint8 *buffer, int size);

    /**
    This function reads next aligned word and remove the emulation prevention code
    if necessary.
    \param "stream" "Pointer to bitstream structure."
    \return "Next word."
    */
    uint BitstreamNextWord(AVCDecBitstream *stream);

    /**
    This function reads nBits bits from the current position and advance the pointer.
    \param "stream" "Pointer to bitstream structure."
    \param "nBits" "Number of bits to be read."
    \param "code"   "Point to the read value."
    \return "AVCDEC_SUCCESS if successed, AVCDEC_FAIL if number of bits
                is greater than the word-size, AVCDEC_PACKET_LOSS or
                AVCDEC_NO_DATA if callback to get data fails."
    */
    AVCDec_Status BitstreamReadBits(AVCDecBitstream *stream, int nBits, uint *code);

    /**
    This function shows nBits bits from the current position without advancing the pointer.
    \param "stream" "Pointer to bitstream structure."
    \param "nBits" "Number of bits to be read."
    \param "code"   "Point to the read value."
    \return "AVCDEC_SUCCESS if successed, AVCDEC_FAIL if number of bits
                    is greater than the word-size, AVCDEC_NO_DATA if it needs
                    to callback to get data."
    */
    AVCDec_Status BitstreamShowBits(AVCDecBitstream *stream, int nBits, uint *code);


    /**
    This function flushes nBits bits from the current position.
    \param "stream" "Pointer to bitstream structure."
    \param "nBits" "Number of bits to be read."
    \return "AVCDEC_SUCCESS if successed, AVCDEC_FAIL if number of bits
                    is greater than the word-size It will not call back to get
                   more data. Users should call BitstreamShowBits to determine
                   how much they want to flush."
    */

    /**
    This function read 1 bit from the current position and advance the pointer.
    \param "stream" "Pointer to bitstream structure."
    \param "nBits" "Number of bits to be read."
    \param "code"   "Point to the read value."
    \return "AVCDEC_SUCCESS if successed, AVCDEC_FAIL if number of bits
                is greater than the word-size, AVCDEC_PACKET_LOSS or
                AVCDEC_NO_DATA if callback to get data fails."
    */
    AVCDec_Status BitstreamRead1Bit(AVCDecBitstream *stream, uint *code);

    /**
    This function checks whether the current bit position is byte-aligned or not.
    \param "stream" "Pointer to the bitstream structure."
    \return "TRUE if byte-aligned, FALSE otherwise."
    */
    bool byte_aligned(AVCDecBitstream *stream);
    AVCDec_Status BitstreamByteAlign(AVCDecBitstream  *stream);
    /**
    This function checks whether there are more RBSP data before the trailing bits.
    \param "stream" "Pointer to the bitstream structure."
    \return "TRUE if yes, FALSE otherwise."
    */
    bool more_rbsp_data(AVCDecBitstream *stream);


#ifdef __cplusplus
}
#endif /* __cplusplus  */

#endif /* _AVCDEC_BITSTREAM_H_ */
