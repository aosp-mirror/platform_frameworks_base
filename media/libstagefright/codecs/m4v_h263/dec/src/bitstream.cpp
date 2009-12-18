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
#include "bitstream.h"
#include "mp4dec_lib.h"


#define OSCL_DISABLE_WARNING_CONDITIONAL_IS_CONSTANT
/* to mask the n least significant bits of an integer */
static const uint32 msk[33] =
{
    0x00000000, 0x00000001, 0x00000003, 0x00000007,
    0x0000000f, 0x0000001f, 0x0000003f, 0x0000007f,
    0x000000ff, 0x000001ff, 0x000003ff, 0x000007ff,
    0x00000fff, 0x00001fff, 0x00003fff, 0x00007fff,
    0x0000ffff, 0x0001ffff, 0x0003ffff, 0x0007ffff,
    0x000fffff, 0x001fffff, 0x003fffff, 0x007fffff,
    0x00ffffff, 0x01ffffff, 0x03ffffff, 0x07ffffff,
    0x0fffffff, 0x1fffffff, 0x3fffffff, 0x7fffffff,
    0xffffffff
};


/* ======================================================================== */
/*  Function : BitstreamFillCache()                                         */
/*  Date     : 08/29/2000                                                   */
/*  Purpose  : Read more bitstream data into buffer & the 24-byte cache.    */
/*              This function is different from BitstreamFillBuffer in      */
/*              that the buffer is the frame-based buffer provided by       */
/*              the application.                                            */
/*  In/out   :                                                              */
/*  Return   : PV_SUCCESS if successed, PV_FAIL if failed.                  */
/*  Modified : 4/16/01  : removed return of PV_END_OF_BUFFER                */
/* ======================================================================== */
PV_STATUS BitstreamFillCache(BitstreamDecVideo *stream)
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
        return PV_SUCCESS;
    }
    /* this check can be removed if there is additional extra 4 bytes at the end of the bitstream */
    v = bitstreamBuffer + stream->read_point;

    if (stream->read_point > stream->data_end_pos - 4)
    {
        if (stream->data_end_pos <= stream->read_point)
        {
            stream->incnt = num_bits;
            stream->incnt_next = 0;
            return PV_SUCCESS;
        }

        stream->next_word = 0;

        for (i = 0; i < stream->data_end_pos - stream->read_point; i++)
        {
            stream->next_word |= (v[i] << ((3 - i) << 3));
        }

        stream->read_point = stream->data_end_pos;
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
        return PV_SUCCESS;
    }

    stream->next_word = ((uint32)v[0] << 24) | (v[1] << 16) | (v[2] << 8) | v[3];
    stream->read_point += 4;

    stream->curr_word |= (stream->next_word >> num_bits); // this is safe
    stream->next_word <<= (31 - num_bits);
    stream->next_word <<= 1;
    stream->incnt_next += stream->incnt;
    stream->incnt = 32;
    return PV_SUCCESS;
}


/* ======================================================================== */
/*  Function : BitstreamReset()                                             */
/*  Date     : 08/29/2000                                                   */
/*  Purpose  : Initialize the bitstream buffer for frame-based decoding.    */
/*  In/out   :                                                              */
/*  Return   :                                                              */
/*  Modified :                                                              */
/* ======================================================================== */
void BitstreamReset(BitstreamDecVideo *stream, uint8 *buffer, int32 buffer_size)
{
    /* set up frame-based bitstream buffer */
    oscl_memset(stream, 0, sizeof(BitstreamDecVideo));
    stream->data_end_pos = buffer_size;
    stream->bitstreamBuffer = buffer;
}


/* ======================================================================== */
/*  Function : BitstreamOpen()                                              */
/*  Purpose  : Initialize the bitstream data structure.                     */
/*  In/out   :                                                              */
/*  Return   :                                                              */
/*  Modified :                                                              */
/* ======================================================================== */
int BitstreamOpen(BitstreamDecVideo *stream, int)
{
    int buffer_size = 0;
    /* set up linear bitstream buffer */
//  stream->currentBytePos = 0;
    stream->data_end_pos = 0;

    stream->incnt = 0;
    stream->incnt_next = 0;
    stream->bitcnt = 0;
    stream->curr_word = stream->next_word = 0;
    stream->read_point = stream->data_end_pos;
    return buffer_size;
}


/* ======================================================================== */
/*  Function : BitstreamClose()                                             */
/*  Purpose  : Cleanup the bitstream data structure.                        */
/*  In/out   :                                                              */
/*  Return   :                                                              */
/*  Modified :                                                              */
/* ======================================================================== */
void BitstreamClose(BitstreamDecVideo *)
{
    return;
}


/***********************************************************CommentBegin******
*
* -- BitstreamShowBits32HC
* Shows 32 bits
***********************************************************CommentEnd********/

PV_STATUS BitstreamShowBits32HC(BitstreamDecVideo *stream, uint32 *code)
{
    PV_STATUS status = PV_SUCCESS;

    if (stream->incnt < 32)
    {
        /* frame-based decoding */
        status = BitstreamFillCache(stream);
    }
    *code = stream->curr_word;
    return status;
}

/***********************************************************CommentBegin******
*
* -- BitstreamShowBits32
* Shows upto and including 31 bits
***********************************************************CommentEnd********/
PV_STATUS BitstreamShowBits32(BitstreamDecVideo *stream, int nbits, uint32 *code)
{
    PV_STATUS status = PV_SUCCESS;

    if (stream->incnt < nbits)
    {
        /* frame-based decoding */
        status = BitstreamFillCache(stream);
    }
    *code = stream->curr_word >> (32 - nbits);
    return status;
}


#ifndef PV_BS_INLINE
/*========================================================================= */
/*  Function:   BitstreamShowBits16()                                       */
/*  Date:       12/18/2000                                                  */
/*  Purpose:    To see the next "nbits"(nbits<=16) bitstream bits           */
/*              without advancing the read pointer                          */
/*                                                                          */
/* =========================================================================*/
PV_STATUS BitstreamShowBits16(BitstreamDecVideo *stream, int nbits, uint *code)
{
    PV_STATUS status = PV_SUCCESS;


    if (stream->incnt < nbits)
    {
        /* frame-based decoding */
        status = BitstreamFillCache(stream);
    }

    *code = stream->curr_word >> (32 - nbits);
    return status;
}


/*========================================================================= */
/*  Function:   BitstreamShow15Bits()                                       */
/*  Date:       01/23/2001                                                  */
/*  Purpose:    To see the next 15 bitstream bits                           */
/*              without advancing the read pointer                          */
/*                                                                          */
/* =========================================================================*/
PV_STATUS BitstreamShow15Bits(BitstreamDecVideo *stream, uint *code)
{
    PV_STATUS status = PV_SUCCESS;

    if (stream->incnt < 15)
    {
        /* frame-based decoding */
        status = BitstreamFillCache(stream);
    }
    *code = stream->curr_word >> 17;
    return status;
}
/*========================================================================= */
/*  Function: BitstreamShow13Bits                                           */
/*  Date:       050923                                              */
/*  Purpose:    Faciliate and speed up showing 13 bit from bitstream        */
/*              used in VlcTCOEFF decoding                                  */
/*  Modified:                            */
/* =========================================================================*/
PV_STATUS BitstreamShow13Bits(BitstreamDecVideo *stream, uint *code)
{
    PV_STATUS status = PV_SUCCESS;

    if (stream->incnt < 13)
    {
        /* frame-based decoding */
        status = BitstreamFillCache(stream);
    }
    *code = stream->curr_word >> 19;
    return status;
}

uint BitstreamReadBits16_INLINE(BitstreamDecVideo *stream, int nbits)
{
    uint code;
    PV_STATUS status;

    if (stream->incnt < nbits)
    {
        /* frame-based decoding */
        status = BitstreamFillCache(stream);
    }
    code = stream->curr_word >> (32 - nbits);
    PV_BitstreamFlushBits(stream, nbits);
    return code;
}


uint BitstreamRead1Bits_INLINE(BitstreamDecVideo *stream)
{
    PV_STATUS status = PV_SUCCESS;
    uint    code;


    if (stream->incnt < 1)
    {
        /* frame-based decoding */
        status = BitstreamFillCache(stream);
    }
    code = stream->curr_word >> 31;
    PV_BitstreamFlushBits(stream, 1);

    return code;
}

#endif

/* ======================================================================== */
/*  Function : BitstreamReadBits16()                                        */
/*  Purpose  : Read bits (nbits <=16) from bitstream buffer.                */
/*  In/out   :                                                              */
/*  Return   :                                                              */
/* ======================================================================== */
uint BitstreamReadBits16(BitstreamDecVideo *stream, int nbits)
{
    uint code;

    if (stream->incnt < nbits)
    {
        /* frame-based decoding */
        BitstreamFillCache(stream);
    }
    code = stream->curr_word >> (32 - nbits);
    PV_BitstreamFlushBits(stream, nbits);
    return code;
}

/* ======================================================================== */
/*  Function : BitstreamRead1Bits()                                         */
/*  Date     : 10/23/2000                                                   */
/*  Purpose  : Faciliate and speed up reading 1 bit from bitstream.         */
/*  In/out   :                                                              */
/*  Return   :                                                              */
/* ======================================================================== */

uint BitstreamRead1Bits(BitstreamDecVideo *stream)
{
    uint    code;

    if (stream->incnt < 1)
    {
        /* frame-based decoding */
        BitstreamFillCache(stream);
    }
    code = stream->curr_word >> 31;
    PV_BitstreamFlushBits(stream, 1);

    return code;
}

/* ======================================================================== */
/*  Function : PV_BitstreamFlushBitsCheck()                                 */
/*  Purpose  : Flush nbits bits from bitstream buffer. Check for cache      */
/*  In/out   :                                                              */
/*  Return   :                                                              */
/*  Modified :                                                              */
/* ======================================================================== */
PV_STATUS PV_BitstreamFlushBitsCheck(BitstreamDecVideo *stream, int nbits)
{
    PV_STATUS status = PV_SUCCESS;

    stream->bitcnt += nbits;
    stream->incnt -= nbits;
    if (stream->incnt < 0)
    {
        /* frame-based decoding */
        status = BitstreamFillCache(stream);

        if (stream->incnt < 0)
        {
            stream->bitcnt += stream->incnt;
            stream->incnt = 0;
        }
    }
    stream->curr_word <<= nbits;
    return status;
}

/* ======================================================================== */
/*  Function : BitstreamReadBits32()                                        */
/*  Purpose  : Read bits from bitstream buffer.                             */
/*  In/out   :                                                              */
/*  Return   :                                                              */
/* ======================================================================== */
uint32 BitstreamReadBits32(BitstreamDecVideo *stream, int nbits)
{
    uint32 code;

    if (stream->incnt < nbits)
    {
        /* frame-based decoding */
        BitstreamFillCache(stream);
    }
    code = stream->curr_word >> (32 - nbits);
    PV_BitstreamFlushBits(stream, nbits);
    return code;
}

uint32 BitstreamReadBits32HC(BitstreamDecVideo *stream)
{
    uint32 code;

    BitstreamShowBits32HC(stream, &code);
    stream->bitcnt += 32;
    stream->incnt = 0;
    stream->curr_word = 0;
    return code;
}

/* ======================================================================== */
/*  Function : BitstreamCheckEndBuffer()                                    */
/*  Date     : 03/30/2001                                                   */
/*  Purpose  : Check to see if we are at the end of buffer                  */
/*  In/out   :                                                              */
/*  Return   :                                                              */
/*  Modified :                                                              */
/* ======================================================================== */
PV_STATUS BitstreamCheckEndBuffer(BitstreamDecVideo *stream)
{
    if (stream->read_point >= stream->data_end_pos && stream->incnt <= 0) return PV_END_OF_VOP;
    return PV_SUCCESS;
}


PV_STATUS PV_BitstreamShowBitsByteAlign(BitstreamDecVideo *stream, int nbits, uint32 *code)
{
    PV_STATUS status = PV_SUCCESS;

    int n_stuffed;

    n_stuffed = 8 - (stream->bitcnt & 0x7); /*  07/05/01 */

    if (stream->incnt < (nbits + n_stuffed))
    {
        /* frame-based decoding */
        status = BitstreamFillCache(stream);
    }

    *code = (stream->curr_word << n_stuffed) >> (32 - nbits);
    return status;
}

#ifdef PV_ANNEX_IJKT_SUPPORT
PV_STATUS PV_BitstreamShowBitsByteAlignNoForceStuffing(BitstreamDecVideo *stream, int nbits, uint32 *code)
{
    PV_STATUS status = PV_SUCCESS;

    int n_stuffed;

    n_stuffed = (8 - (stream->bitcnt & 0x7)) & 7;

    if (stream->incnt < (nbits + n_stuffed))
    {
        /* frame-based decoding */
        status = BitstreamFillCache(stream);
    }

    *code = (stream->curr_word << n_stuffed) >> (32 - nbits);
    return status;
}
#endif

PV_STATUS PV_BitstreamByteAlign(BitstreamDecVideo *stream)
{
    PV_STATUS status = PV_SUCCESS;
    int n_stuffed;

    n_stuffed = 8 - (stream->bitcnt & 0x7); /*  07/05/01 */

    /* We have to make sure we have enough bits in the cache.   08/15/2000 */
    if (stream->incnt < n_stuffed)
    {
        /* frame-based decoding */
        status = BitstreamFillCache(stream);
    }


    stream->bitcnt += n_stuffed;
    stream->incnt -= n_stuffed;
    stream->curr_word <<= n_stuffed;
    if (stream->incnt < 0)
    {
        stream->bitcnt += stream->incnt;
        stream->incnt = 0;
    }
    return status;
}


PV_STATUS BitstreamByteAlignNoForceStuffing(BitstreamDecVideo *stream)
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
    return PV_SUCCESS;
}


/* ==================================================================== */
/*  Function : getPointer()                                             */
/*  Date     : 10/98                                                    */
/*  Purpose  : get current position of file pointer                     */
/*  In/out   :                                                          */
/*  Return   :                                                          */
/* ==================================================================== */
int32 getPointer(BitstreamDecVideo *stream)
{
    return stream->bitcnt;
}




/* ====================================================================== /
Function : movePointerTo()
Date     : 05/14/2004
Purpose  : move bitstream pointer to a desired position
In/out   :
Return   :
Modified :
/ ====================================================================== */
PV_STATUS movePointerTo(BitstreamDecVideo *stream, int32 pos)
{
    int32 byte_pos;
    if (pos < 0)
    {
        pos = 0;
    }

    byte_pos = pos >> 3;

    if (byte_pos > stream->data_end_pos)
    {
        byte_pos = stream->data_end_pos;
    }

    stream->read_point = byte_pos & -4;
    stream->bitcnt = stream->read_point << 3;;
    stream->curr_word = 0;
    stream->next_word = 0;
    stream->incnt = 0;
    stream->incnt_next = 0;
    BitstreamFillCache(stream);
    PV_BitstreamFlushBits(stream, ((pos & 0x7) + ((byte_pos & 0x3) << 3)));
    return PV_SUCCESS;
}


/* ======================================================================== */
/*  Function : validStuffing()                                              */
/*  Date     : 04/11/2000                                                   */
/*  Purpose  : Check whether we have valid stuffing at current position.    */
/*  In/out   :                                                              */
/*  Return   : PV_TRUE if successed, PV_FALSE if failed.                    */
/*  Modified : 12/18/2000 : changed the pattern type to uint    */
/*             04/01/2001 : removed PV_END_OF_BUFFER                        */
/* ======================================================================== */
Bool validStuffing(BitstreamDecVideo *stream)
{
    uint n_stuffed;
    uint pattern;


    n_stuffed = 8 - (stream->bitcnt & 0x7);
    BitstreamShowBits16(stream, n_stuffed, &pattern);
    if (pattern == msk[n_stuffed-1]) return PV_TRUE;
    return PV_FALSE;
}
#ifdef PV_ANNEX_IJKT_SUPPORT
Bool validStuffing_h263(BitstreamDecVideo *stream)
{
    uint n_stuffed;
    uint pattern;


    n_stuffed = (8 - (stream->bitcnt & 0x7)) & 7;  //  stream->incnt % 8
    if (n_stuffed == 0)
    {
        return PV_TRUE;
    }
    BitstreamShowBits16(stream, n_stuffed, &pattern);
    if (pattern == 0) return PV_TRUE;
    return PV_FALSE;
}
#endif


/* ======================================================================== */
/*  Function : PVSearchNextH263Frame()                                      */
/*  Date     : 04/08/2005                                                   */
/*  Purpose  : search for 0x00 0x00 0x80                                    */
/*  In/out   :                                                              */
/*  Return   : PV_SUCCESS if succeeded  or PV_END_OF_VOP if failed          */
/*  Modified :                                                              */
/* ======================================================================== */
PV_STATUS PVSearchNextH263Frame(BitstreamDecVideo *stream)
{
    PV_STATUS status = PV_SUCCESS;
    uint8 *ptr;
    int32 i;
    int32 initial_byte_aligned_position = (stream->bitcnt + 7) >> 3;

    ptr = stream->bitstreamBuffer + initial_byte_aligned_position;

    i = PVLocateH263FrameHeader(ptr, stream->data_end_pos - initial_byte_aligned_position);
    if (stream->data_end_pos <= initial_byte_aligned_position + i)
    {
        status = PV_END_OF_VOP;
    }
    (void)movePointerTo(stream, ((i + initial_byte_aligned_position) << 3)); /* ptr + i */
    return status;
}


/* ======================================================================== */
/*  Function : PVSearchNextM4VFrame()                                       */
/*  Date     : 04/08/2005                                                   */
/*  Purpose  : search for 0x00 0x00 0x01 and move the pointer to the        */
/*  beginning of the start code                                             */
/*  In/out   :                                                              */
/*  Return   : PV_SUCCESS if succeeded  or PV_END_OF_VOP if failed          */
/*  Modified :                                                              */
/* ======================================================================== */

PV_STATUS PVSearchNextM4VFrame(BitstreamDecVideo *stream)
{
    PV_STATUS status = PV_SUCCESS;
    uint8 *ptr;
    int32 i;
    int32 initial_byte_aligned_position = (stream->bitcnt + 7) >> 3;

    ptr = stream->bitstreamBuffer + initial_byte_aligned_position;

    i = PVLocateFrameHeader(ptr, stream->data_end_pos - initial_byte_aligned_position);
    if (stream->data_end_pos <= initial_byte_aligned_position + i)
    {
        status = PV_END_OF_VOP;
    }
    (void)movePointerTo(stream, ((i + initial_byte_aligned_position) << 3)); /* ptr + i */
    return status;
}



void PVLocateM4VFrameBoundary(BitstreamDecVideo *stream)
{
    uint8 *ptr;
    int32 byte_pos = (stream->bitcnt >> 3);

    stream->searched_frame_boundary = 1;
    ptr = stream->bitstreamBuffer + byte_pos;

    stream->data_end_pos = PVLocateFrameHeader(ptr, (int32)stream->data_end_pos - byte_pos) + byte_pos;
}

void PVLocateH263FrameBoundary(BitstreamDecVideo *stream)
{
    uint8 *ptr;
    int32 byte_pos = (stream->bitcnt >> 3);

    stream->searched_frame_boundary = 1;
    ptr = stream->bitstreamBuffer + byte_pos;

    stream->data_end_pos = PVLocateH263FrameHeader(ptr, (int32)stream->data_end_pos - byte_pos) + byte_pos;
}

/* ======================================================================== */
/*  Function : quickSearchVideoPacketHeader()               */
/*  Date     : 05/08/2000                           */
/*  Purpose  : Quick search for the next video packet header        */
/*  In/out   :                              */
/*  Return   : PV_TRUE if successed, PV_FALSE if failed.            */
/*  Modified :                              */
/* ======================================================================== */
PV_STATUS quickSearchVideoPacketHeader(BitstreamDecVideo *stream, int marker_length)
{
    PV_STATUS status = PV_SUCCESS;
    uint32 tmpvar;


    if (stream->searched_frame_boundary == 0)
    {
        PVLocateM4VFrameBoundary(stream);
    }

    do
    {
        status = BitstreamCheckEndBuffer(stream);
        if (status == PV_END_OF_VOP) break;
        PV_BitstreamShowBitsByteAlign(stream, marker_length, &tmpvar);
        if (tmpvar == RESYNC_MARKER) break;
        PV_BitstreamFlushBits(stream, 8);
    }
    while (status == PV_SUCCESS);

    return status;
}
#ifdef PV_ANNEX_IJKT_SUPPORT
PV_STATUS quickSearchH263SliceHeader(BitstreamDecVideo *stream)
{
    PV_STATUS status = PV_SUCCESS;
    uint32 tmpvar;


    if (stream->searched_frame_boundary == 0)
    {
        PVLocateH263FrameBoundary(stream);
    }

    do
    {
        status = BitstreamCheckEndBuffer(stream);
        if (status == PV_END_OF_VOP) break;
        PV_BitstreamShowBitsByteAlignNoForceStuffing(stream, 17, &tmpvar);
        if (tmpvar == RESYNC_MARKER) break;
        PV_BitstreamFlushBits(stream, 8);
    }
    while (status == PV_SUCCESS);

    return status;
}
#endif
/* ======================================================================== */
/*          The following functions are for Error Concealment.              */
/* ======================================================================== */

/****************************************************/
//  01/22/99 Quick search of Resync Marker
// (actually the first part of it, i.e. 16 0's and a 1.

/* We are not using the fastest algorithm possible. What this function does is
to locate 11 consecutive 0's and then check if the 5 bits before them and
the 1 bit after them are all 1's.
*/

//  Table used for quick search of markers. Gives the last `1' in
// 4 bits. The MSB is bit #1, the LSB is bit #4.
const int lastOne[] =
{
    0,  4,  3,  4,  2,  4,  3,  4,
    1,  4,  3,  4,  2,  4,  3,  4
};

//  Table used for quick search of markers. Gives the last `0' in
// 4 bits. The MSB is bit #1, the LSB is bit #4.
/*const int lastZero[]=
{
    4,  3,  4,  2,  4,  3,  4,  1,
        4,  3,  4,  2,  4,  3,  4,  0
};
*/
//  Table used for quick search of markers. Gives the first `0' in
// 4 bits. The MSB is bit #1, the LSB is bit #4.
const int firstZero[] =
{
    1, 1, 1, 1, 1, 1, 1, 1,
    2, 2, 2, 2, 3, 3, 4, 0
};

//  Table used for quick search of markers. Gives the first `1' in
// 4 bits. The MSB is bit #1, the LSB is bit #4.
const int firstOne[] =
{
    0, 4, 3, 3, 2, 2, 2, 2,
    1, 1, 1, 1, 1, 1, 1, 1
};


/* ======================================================================== */
/*  Function : quickSearchMarkers()                                         */
/*  Date     : 01/25/99                                                     */
/*  Purpose  : Quick search for Motion marker                               */
/*  In/out   :                                                              */
/*  Return   : Boolean true of false                                        */
/*  Modified : 12/18/2000 : 32-bit version                    */
/* ======================================================================== */
PV_STATUS quickSearchMotionMarker(BitstreamDecVideo *stream)
// MM: (11111000000000001)
{
    PV_STATUS status;
    uint32 tmpvar, tmpvar2;

    if (stream->searched_frame_boundary == 0)
    {
        PVLocateM4VFrameBoundary(stream);
    }

    while (TRUE)
    {
        status = BitstreamCheckEndBuffer(stream);
        if (status == PV_END_OF_VOP) return PV_END_OF_VOP;

        BitstreamShowBits32(stream, 17, &tmpvar);
        if (!tmpvar) return PV_FAIL;

        if (tmpvar & 1) //  Check if the 17th bit from the curr bit pos is a '1'
        {
            if (tmpvar == MOTION_MARKER_COMB)
            {
                return PV_SUCCESS; //  Found
            }
            else
            {
                tmpvar >>= 1;
                tmpvar &= 0xF;
                PV_BitstreamFlushBits(stream, (int)(12 + firstZero[tmpvar]));
            }
        }
        else
        {
            //  01/25/99 Get the first 16 bits
            tmpvar >>= 1;
            tmpvar2 = tmpvar & 0xF;

            //  01/26/99 Check bits #13 ~ #16
            if (tmpvar2)
            {
                PV_BitstreamFlushBits(stream, (int)(7 + lastOne[tmpvar2]));
            }
            else
            {
                tmpvar >>= 4;
                tmpvar2 = tmpvar & 0xF;

                //  01/26/99 Check bits #9 ~ #12
                if (tmpvar2)
                {
                    PV_BitstreamFlushBits(stream, (int)(3 + lastOne[tmpvar2]));
                }
                else
                {
                    tmpvar >>= 4;
                    tmpvar2 = tmpvar & 0xF;

                    //  01/26/99 Check bits #5 ~ #8
                    // We don't need to check further
                    // for the first 5 bits should be all 1's
                    if (lastOne[tmpvar2] < 2)
                    {
                        /* we already have too many consecutive 0's. */
                        /* Go directly pass the last of the 17 bits. */
                        PV_BitstreamFlushBits(stream, 17);
                    }
                    else
                    {
                        PV_BitstreamFlushBits(stream, (int)(lastOne[tmpvar2] - 1));
                    }
                }
            }
        }

    }
}

/* ======================================================================== */
/*  Function : quickSearchDCM()                                             */
/*  Date     : 01/22/99                                                     */
/*  Purpose  : Quick search for DC Marker                                   */
/*              We are not using the fastest algorithm possible.  What this */
/*              function does is to locate 11 consecutive 0's and then      */
/*              check if the 7 bits before them and the 1 bit after them    */
/*              are correct.  (actually the first part of it, i.e. 16 0's   */
/*              and a 1.                                                    */
/*  In/out   :                                                              */
/*  Return   : Boolean true of false                                        */
/*  Modified : 12/18/2000 : 32-bit version                    */
/* ======================================================================== */
PV_STATUS quickSearchDCM(BitstreamDecVideo *stream)
// DCM: (110 1011 0000 0000 0001)
{
    PV_STATUS status;
    uint32 tmpvar, tmpvar2;

    if (stream->searched_frame_boundary == 0)
    {
        PVLocateM4VFrameBoundary(stream);
    }

    while (TRUE)
    {
        status = BitstreamCheckEndBuffer(stream);
        if (status == PV_END_OF_VOP) return PV_END_OF_VOP;
        BitstreamShowBits32(stream, 19, &tmpvar);

        if (tmpvar & 1) //  Check if the 17th bit from the curr bit pos is a '1'
        {
            if (tmpvar == DC_MARKER)
            {
                return PV_SUCCESS; //  Found
            }
            else
            {
                //  01/25/99 We treat the last of the 19 bits as its 7th bit (which is
                // also a `1'
                PV_BitstreamFlushBits(stream, 12);
            }
        }
        else
        {
            tmpvar >>= 1;
            tmpvar2 = tmpvar & 0xF;

            if (tmpvar2)
            {
                PV_BitstreamFlushBits(stream, (int)(7 + lastOne[tmpvar2]));
            }
            else
            {
                tmpvar >>= 4;
                tmpvar2 = tmpvar & 0xF;
                if (tmpvar2)
                {
                    PV_BitstreamFlushBits(stream, (int)(3 + lastOne[tmpvar2]));
                }
                else
                {
                    tmpvar >>= 4;
                    tmpvar2 = tmpvar & 0xF;
                    if (lastOne[tmpvar2] < 2)
                    {
                        /* we already have too many consecutive 0's. */
                        /* Go directly pass the last of the 17 bits. */
                        PV_BitstreamFlushBits(stream, 19);
                    }
                    else
                    {
                        PV_BitstreamFlushBits(stream, (int)(lastOne[tmpvar2] - 1));
                    }
                }
            }
        }
    }
}

/* ======================================================================== */
/*  Function : quickSearchGOBHeader()   0000 0000 0000 0000 1               */
/*  Date     : 07/06/01                                                     */
/*  Purpose  : Quick search of GOBHeader (not byte aligned)                 */
/*  In/out   :                                                              */
/*  Return   : Integer value indicates type of marker found                 */
/*  Modified :                                                              */
/* ======================================================================== */
PV_STATUS quickSearchGOBHeader(BitstreamDecVideo *stream)
{
    PV_STATUS status;
    int byte0, byte1, byte2, shift, tmpvar;

    BitstreamByteAlignNoForceStuffing(stream);

    if (stream->searched_frame_boundary == 0)
    {
        PVLocateH263FrameBoundary(stream);
    }

    while (TRUE)
    {
        status = BitstreamCheckEndBuffer(stream);
        if (status == PV_END_OF_VOP) return PV_END_OF_VOP;

        if (stream->incnt < 24)
        {
            status = BitstreamFillCache(stream);
        }


        byte1 = (stream->curr_word << 8) >> 24;
        if (byte1 == 0)
        {
            byte2 = (stream->curr_word << 16) >> 24;
            if (byte2)
            {
                tmpvar = byte2 >> 4;

                if (tmpvar)
                {
                    shift = 9 - firstOne[tmpvar];
                }
                else
                {
                    shift = 5 - firstOne[byte2];
                }
                byte0 = stream->curr_word >> 24;
                if ((byte0 & msk[shift]) == 0)
                {
                    PV_BitstreamFlushBits(stream, 8 - shift);
                    return PV_SUCCESS;
                }
                PV_BitstreamFlushBits(stream, 8);    /* third_byte is not zero */
            }
        }

        PV_BitstreamFlushBits(stream, 8);
    }
}
