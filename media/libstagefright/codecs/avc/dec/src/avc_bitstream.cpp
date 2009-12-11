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
#include "avcdec_bitstream.h"

/* Swapping may not be needed anymore since we read one byte at a time and perform
EBSP to RBSP conversion in bitstream. */
#ifdef LITTLE_ENDIAN
#if (WORD_SIZE==32)  /* this can be replaced with assembly instructions */
#define SWAP_BYTES(x) ((((x)&0xFF)<<24) | (((x)&0xFF00)<<8) | (((x)&0xFF0000)>>8) | (((x)&0xFF000000)>>24))
#else  /* for 16-bit */
#define SWAP_BYTES(x) ((((x)&0xFF)<<8) | (((x)&0xFF00)>>8))
#endif
#else
#define SWAP_BYTES(x) (x)
#endif


/* array for trailing bit pattern as function of number of bits */
/* the first one is unused. */
const static uint8 trailing_bits[9] = {0, 0x1, 0x2, 0x4, 0x8, 0x10, 0x20, 0x40, 0x80};

/* ======================================================================== */
/*  Function : BitstreamInit()                                              */
/*  Date     : 11/4/2003                                                    */
/*  Purpose  : Populate bitstream structure with bitstream buffer and size  */
/*             it also initializes internal data                            */
/*  In/out   :                                                              */
/*  Return   : AVCDEC_SUCCESS if successed, AVCDEC_FAIL if failed.              */
/*  Modified :                                                              */
/* ======================================================================== */
/* |--------|--------|----~~~~~-----|---------|---------|---------|
   ^                                          ^read_pos           ^data_end_pos
   bitstreamBuffer                  <--------->
                                    current_word

   |xxxxxxxxxxxxx----|  = current_word 32 or 16 bits
    <------------>
     bit_left
 ======================================================================== */


/* ======================================================================== */
/*  Function : BitstreamNextWord()                                          */
/*  Date     : 12/4/2003                                                    */
/*  Purpose  : Read up to machine word.                                     */
/*  In/out   :                                                              */
/*  Return   : Next word with emulation prevention code removed. Everything
    in the bitstream structure got modified except current_word             */
/*  Modified :                                                              */
/* ======================================================================== */

AVCDec_Status BitstreamInit(AVCDecBitstream *stream, uint8 *buffer, int size)
{
    EBSPtoRBSP(buffer, &size);

    stream->incnt = 0;
    stream->incnt_next = 0;
    stream->bitcnt = 0;
    stream->curr_word = stream->next_word = 0;
    stream->read_pos = 0;

    stream->bitstreamBuffer = buffer;

    stream->data_end_pos = size;

    stream->nal_size = size;

    return AVCDEC_SUCCESS;
}
/* ======================================================================== */
/*  Function : AVC_BitstreamFillCache()                                         */
/*  Date     : 1/1/2005                                                     */
/*  Purpose  : Read up to machine word.                                     */
/*  In/out   :                                                              */
/*  Return   : Read in 4 bytes of input data                                */
/*  Modified :                                                              */
/* ======================================================================== */

AVCDec_Status AVC_BitstreamFillCache(AVCDecBitstream *stream)
{
    uint8 *bitstreamBuffer = stream->bitstreamBuffer;
    uint8 *v;
    int num_bits, i;

    stream->curr_word |= (stream->next_word >> stream->incnt);   // stream->incnt cannot be 32
    stream->next_word <<= (31 - stream->incnt);
    stream->next_word <<= 1;
    num_bits = stream->incnt_next + stream->incnt;
    if (num_bits >= 32)
    {
        stream->incnt_next -= (32 - stream->incnt);
        stream->incnt = 32;
        return AVCDEC_SUCCESS;
    }
    /* this check can be removed if there is additional extra 4 bytes at the end of the bitstream */
    v = bitstreamBuffer + stream->read_pos;

    if (stream->read_pos > stream->data_end_pos - 4)
    {
        if (stream->data_end_pos <= stream->read_pos)
        {
            stream->incnt = num_bits;
            stream->incnt_next = 0;
            return AVCDEC_SUCCESS;
        }

        stream->next_word = 0;

        for (i = 0; i < stream->data_end_pos - stream->read_pos; i++)
        {
            stream->next_word |= (v[i] << ((3 - i) << 3));
        }

        stream->read_pos = stream->data_end_pos;
        stream->curr_word |= (stream->next_word >> num_bits); // this is safe

        stream->next_word <<= (31 - num_bits);
        stream->next_word <<= 1;
        num_bits = i << 3;
        stream->incnt += stream->incnt_next;
        stream->incnt_next = num_bits - (32 - stream->incnt);
        if (stream->incnt_next < 0)
        {
            stream->incnt +=  num_bits;
            stream->incnt_next = 0;
        }
        else
        {
            stream->incnt = 32;
        }
        return AVCDEC_SUCCESS;
    }

    stream->next_word = ((uint32)v[0] << 24) | (v[1] << 16) | (v[2] << 8) | v[3];
    stream->read_pos += 4;

    stream->curr_word |= (stream->next_word >> num_bits); // this is safe
    stream->next_word <<= (31 - num_bits);
    stream->next_word <<= 1;
    stream->incnt_next += stream->incnt;
    stream->incnt = 32;
    return AVCDEC_SUCCESS;

}
/* ======================================================================== */
/*  Function : BitstreamReadBits()                                          */
/*  Date     : 11/4/2003                                                    */
/*  Purpose  : Read up to machine word.                                     */
/*  In/out   :                                                              */
/*  Return   : AVCDEC_SUCCESS if successed, AVCDEC_FAIL if number of bits   */
/*              is greater than the word-size, AVCDEC_PACKET_LOSS or        */
/*              AVCDEC_NO_DATA if callback to get data fails.               */
/*  Modified :                                                              */
/* ======================================================================== */
AVCDec_Status BitstreamReadBits(AVCDecBitstream *stream, int nBits, uint *code)
{
    if (stream->incnt < nBits)
    {
        /* frame-based decoding */
        AVC_BitstreamFillCache(stream);
    }
    *code = stream->curr_word >> (32 - nBits);
    BitstreamFlushBits(stream, nBits);
    return AVCDEC_SUCCESS;
}



/* ======================================================================== */
/*  Function : BitstreamShowBits()                                          */
/*  Date     : 11/4/2003                                                    */
/*  Purpose  : Show up to machine word without advancing the pointer.       */
/*  In/out   :                                                              */
/*  Return   : AVCDEC_SUCCESS if successed, AVCDEC_FAIL if number of bits   */
/*              is greater than the word-size, AVCDEC_NO_DATA if it needs   */
/*              to callback to get data.                                    */
/*  Modified :                                                              */
/* ======================================================================== */
AVCDec_Status BitstreamShowBits(AVCDecBitstream *stream, int nBits, uint *code)
{
    if (stream->incnt < nBits)
    {
        /* frame-based decoding */
        AVC_BitstreamFillCache(stream);
    }

    *code = stream->curr_word >> (32 - nBits);

    return AVCDEC_SUCCESS;
}

/* ======================================================================== */
/*  Function : BitstreamRead1Bit()                                          */
/*  Date     : 11/4/2003                                                    */
/*  Purpose  : Read 1 bit from the bitstream.                               */
/*  In/out   :                                                              */
/*  Return   : AVCDEC_SUCCESS if successed, AVCDEC_FAIL if number of bits   */
/*              is greater than the word-size, AVCDEC_PACKET_LOSS or        */
/*              AVCDEC_NO_DATA if callback to get data fails.               */
/*  Modified :                                                              */
/* ======================================================================== */

AVCDec_Status BitstreamRead1Bit(AVCDecBitstream *stream, uint *code)
{
    if (stream->incnt < 1)
    {
        /* frame-based decoding */
        AVC_BitstreamFillCache(stream);
    }
    *code = stream->curr_word >> 31;
    BitstreamFlushBits(stream, 1);
    return AVCDEC_SUCCESS;
}



AVCDec_Status BitstreamByteAlign(AVCDecBitstream  *stream)
{
    uint n_stuffed;

    n_stuffed = (8 - (stream->bitcnt & 0x7)) & 0x7; /*  07/05/01 */

    stream->bitcnt += n_stuffed;
    stream->incnt -= n_stuffed;

    if (stream->incnt < 0)
    {
        stream->bitcnt += stream->incnt;
        stream->incnt = 0;
    }
    stream->curr_word <<= n_stuffed;
    return AVCDEC_SUCCESS;
}

/* check whether there are more RBSP data. */
/* ignore the emulation prevention code, assume it has been taken out. */
bool more_rbsp_data(AVCDecBitstream *stream)
{
    int total_bit_left;
    uint code;

    if (stream->read_pos >= stream->nal_size)
    {
        total_bit_left = stream->incnt_next + stream->incnt;
        if (total_bit_left <= 0)
        {
            return FALSE;
        }
        else if (total_bit_left <= 8)
        {
            BitstreamShowBits(stream, total_bit_left, &code);
            if (code == trailing_bits[total_bit_left])
            {
                return FALSE;
            }
        }
    }

    return TRUE;
}

