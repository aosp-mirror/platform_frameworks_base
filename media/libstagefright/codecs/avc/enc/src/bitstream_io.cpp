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
#include "avcenc_lib.h"

#define WORD_SIZE 32

/* array for trailing bit pattern as function of number of bits */
/* the first one is unused. */
const static uint8 trailing_bits[9] = {0, 0x1, 0x2, 0x4, 0x8, 0x10, 0x20, 0x40, 0x80};

/* ======================================================================== */
/*  Function : BitstreamInit()                                              */
/*  Date     : 11/4/2003                                                    */
/*  Purpose  : Populate bitstream structure with bitstream buffer and size  */
/*             it also initializes internal data                            */
/*  In/out   :                                                              */
/*  Return   : AVCENC_SUCCESS if successed, AVCENC_FAIL if failed.              */
/*  Modified :                                                              */
/* ======================================================================== */
/* |--------|--------|----~~~~~-----|---------|---------|---------|
   ^                                          ^write_pos          ^buf_size
   bitstreamBuffer                  <--------->
                                    current_word

   |-----xxxxxxxxxxxxx|  = current_word 32 or 16 bits
    <---->
     bit_left
 ======================================================================== */

AVCEnc_Status BitstreamEncInit(AVCEncBitstream *stream, uint8 *buffer, int buf_size,
                               uint8 *overrunBuffer, int oBSize)
{
    if (stream == NULL || buffer == NULL || buf_size <= 0)
    {
        return AVCENC_BITSTREAM_INIT_FAIL;
    }

    stream->bitstreamBuffer = buffer;

    stream->buf_size = buf_size;

    stream->write_pos = 0;

    stream->count_zeros = 0;

    stream->current_word = 0;

    stream->bit_left = WORD_SIZE;

    stream->overrunBuffer = overrunBuffer;

    stream->oBSize = oBSize;

    return AVCENC_SUCCESS;
}

/* ======================================================================== */
/*  Function : AVCBitstreamSaveWord()                                           */
/*  Date     : 3/29/2004                                                    */
/*  Purpose  : Save the current_word into the buffer, byte-swap, and        */
/*              add emulation prevention insertion.                         */
/*  In/out   :                                                              */
/*  Return   : AVCENC_SUCCESS if successed, AVCENC_WRITE_FAIL if buffer is  */
/*              full.                                                       */
/*  Modified :                                                              */
/* ======================================================================== */
AVCEnc_Status AVCBitstreamSaveWord(AVCEncBitstream *stream)
{
    int num_bits;
    uint8 *write_pnt, byte;
    uint current_word;

    /* check number of bytes in current_word, must always be byte-aligned!!!! */
    num_bits = WORD_SIZE - stream->bit_left; /* must be multiple of 8 !!*/

    if (stream->buf_size - stream->write_pos <= (num_bits >> 3) + 2) /* 2 more bytes for possible EPBS */
    {
        if (AVCENC_SUCCESS != AVCBitstreamUseOverrunBuffer(stream, (num_bits >> 3) + 2))
        {
            return AVCENC_BITSTREAM_BUFFER_FULL;
        }
    }

    /* write word, byte-by-byte */
    write_pnt = stream->bitstreamBuffer + stream->write_pos;
    current_word = stream->current_word;
    while (num_bits) /* no need to check stream->buf_size and stream->write_pos, taken care already */
    {
        num_bits -= 8;
        byte = (current_word >> num_bits) & 0xFF;
        if (byte != 0)
        {
            *write_pnt++ = byte;
            stream->write_pos++;
            stream->count_zeros = 0;
        }
        else
        {
            stream->count_zeros++;
            *write_pnt++ = byte;
            stream->write_pos++;
            if (stream->count_zeros == 2)
            {   /* for num_bits = 32, this can add 2 more bytes extra for EPBS */
                *write_pnt++ = 0x3;
                stream->write_pos++;
                stream->count_zeros = 0;
            }
        }
    }

    /* reset current_word and bit_left */
    stream->current_word = 0;
    stream->bit_left = WORD_SIZE;

    return AVCENC_SUCCESS;
}

/* ======================================================================== */
/*  Function : BitstreamWriteBits()                                         */
/*  Date     : 3/29/2004                                                    */
/*  Purpose  : Write up to machine word.                                    */
/*  In/out   : Unused bits in 'code' must be all zeros.                     */
/*  Return   : AVCENC_SUCCESS if successed, AVCENC_WRITE_FAIL if buffer is  */
/*              full.                                                       */
/*  Modified :                                                              */
/* ======================================================================== */
AVCEnc_Status BitstreamWriteBits(AVCEncBitstream *stream, int nBits, uint code)
{
    AVCEnc_Status status = AVCENC_SUCCESS;
    int bit_left = stream->bit_left;
    uint current_word = stream->current_word;

    //DEBUG_LOG(userData,AVC_LOGTYPE_INFO,"BitstreamWriteBits",nBits,-1);

    if (nBits > WORD_SIZE) /* has to be taken care of specially */
    {
        return AVCENC_FAIL; /* for now */
        /* otherwise, break it down to 2 write of less than 16 bits at a time. */
    }

    if (nBits <= bit_left) /* more bits left in current_word */
    {
        stream->current_word = (current_word << nBits) | code;
        stream->bit_left -= nBits;
        if (stream->bit_left == 0) /* prepare for the next word */
        {
            status = AVCBitstreamSaveWord(stream);
            return status;
        }
    }
    else
    {
        stream->current_word = (current_word << bit_left) | (code >> (nBits - bit_left));

        nBits -= bit_left;

        stream->bit_left = 0;

        status = AVCBitstreamSaveWord(stream); /* save current word */

        stream->bit_left = WORD_SIZE - nBits;

        stream->current_word = code; /* no extra masking for code, must be handled before saving */
    }

    return status;
}


/* ======================================================================== */
/*  Function : BitstreamWrite1Bit()                                         */
/*  Date     : 3/30/2004                                                    */
/*  Purpose  : Write 1 bit                                                  */
/*  In/out   : Unused bits in 'code' must be all zeros.                     */
/*  Return   : AVCENC_SUCCESS if successed, AVCENC_WRITE_FAIL if buffer is  */
/*              full.                                                       */
/*  Modified :                                                              */
/* ======================================================================== */
AVCEnc_Status BitstreamWrite1Bit(AVCEncBitstream *stream, uint code)
{
    AVCEnc_Status status;
    uint current_word = stream->current_word;

    //DEBUG_LOG(userData,AVC_LOGTYPE_INFO,"BitstreamWrite1Bit",code,-1);

    //if(1 <= bit_left) /* more bits left in current_word */
    /* we can assume that there always be positive bit_left in the current word */
    stream->current_word = (current_word << 1) | code;
    stream->bit_left--;
    if (stream->bit_left == 0) /* prepare for the next word */
    {
        status = AVCBitstreamSaveWord(stream);
        return status;
    }

    return AVCENC_SUCCESS;
}


/* ======================================================================== */
/*  Function : BitstreamTrailingBits()                                      */
/*  Date     : 3/31/2004                                                    */
/*  Purpose  : Add trailing bits and report the final EBSP size.            */
/*  In/out   :                                                              */
/*  Return   : AVCENC_SUCCESS if successed, AVCENC_WRITE_FAIL if buffer is  */
/*              full.                                                       */
/*  Modified :                                                              */
/* ======================================================================== */
AVCEnc_Status BitstreamTrailingBits(AVCEncBitstream *bitstream, uint *nal_size)
{
    (void)(nal_size);

    AVCEnc_Status status;
    int bit_left = bitstream->bit_left;

    bit_left &= 0x7; /* modulo by 8 */
    if (bit_left == 0) bit_left = 8;
    /* bitstream->bit_left == 0 cannot happen here since it would have been Saved already */

    status = BitstreamWriteBits(bitstream, bit_left, trailing_bits[bit_left]);

    if (status != AVCENC_SUCCESS)
    {
        return status;
    }

    /* if it's not saved, save it. */
    //if(bitstream->bit_left<(WORD_SIZE<<3)) /* in fact, no need to check */
    {
        status = AVCBitstreamSaveWord(bitstream);
    }

    return status;
}

/* check whether it's byte-aligned */
bool byte_aligned(AVCEncBitstream *stream)
{
    if (stream->bit_left % 8)
        return false;
    else
        return true;
}


/* determine whether overrun buffer can be used or not */
AVCEnc_Status AVCBitstreamUseOverrunBuffer(AVCEncBitstream* stream, int numExtraBytes)
{
    AVCEncObject *encvid = (AVCEncObject*)stream->encvid;

    if (stream->overrunBuffer != NULL) // overrunBuffer is set
    {
        if (stream->bitstreamBuffer != stream->overrunBuffer) // not already used
        {
            if (stream->write_pos + numExtraBytes >= stream->oBSize)
            {
                stream->oBSize = stream->write_pos + numExtraBytes + 100;
                stream->oBSize &= (~0x3); // make it multiple of 4

                // allocate new overrun Buffer
                if (encvid->overrunBuffer)
                {
                    encvid->avcHandle->CBAVC_Free((uint32*)encvid->avcHandle->userData,
                                                  (int)encvid->overrunBuffer);
                }

                encvid->oBSize = stream->oBSize;
                encvid->overrunBuffer = (uint8*) encvid->avcHandle->CBAVC_Malloc(encvid->avcHandle->userData,
                                        stream->oBSize, DEFAULT_ATTR);

                stream->overrunBuffer = encvid->overrunBuffer;
                if (stream->overrunBuffer == NULL)
                {
                    return AVCENC_FAIL;
                }
            }

            // copy everything to overrun buffer and start using it.
            memcpy(stream->overrunBuffer, stream->bitstreamBuffer, stream->write_pos);
            stream->bitstreamBuffer = stream->overrunBuffer;
            stream->buf_size = stream->oBSize;
        }
        else // overrun buffer is already used
        {
            stream->oBSize = stream->write_pos + numExtraBytes + 100;
            stream->oBSize &= (~0x3); // make it multiple of 4

            // allocate new overrun buffer
            encvid->oBSize = stream->oBSize;
            encvid->overrunBuffer = (uint8*) encvid->avcHandle->CBAVC_Malloc(encvid->avcHandle->userData,
                                    stream->oBSize, DEFAULT_ATTR);

            if (encvid->overrunBuffer == NULL)
            {
                return AVCENC_FAIL;
            }


            // copy from the old buffer to new buffer
            memcpy(encvid->overrunBuffer, stream->overrunBuffer, stream->write_pos);
            // free old buffer
            encvid->avcHandle->CBAVC_Free((uint32*)encvid->avcHandle->userData,
                                          (int)stream->overrunBuffer);

            // assign pointer to new buffer
            stream->overrunBuffer = encvid->overrunBuffer;
            stream->bitstreamBuffer = stream->overrunBuffer;
            stream->buf_size = stream->oBSize;
        }

        return AVCENC_SUCCESS;
    }
    else // overrunBuffer is not enable.
    {
        return AVCENC_FAIL;
    }

}



