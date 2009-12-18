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

#ifndef _BITSTREAM_D_H_
#define _BITSTREAM_D_H_

#include "mp4dec_lib.h" /* video decoder function prototypes */

#ifdef __cplusplus
extern "C"
{
#endif /* __cplusplus */

#define PV_BS_INLINE  /* support inline bitstream functions */

#define PV_BitstreamFlushBits(A,B)  {(A)->bitcnt += (B); (A)->incnt -= (B); (A)->curr_word <<= (B);}

    PV_STATUS BitstreamFillBuffer(BitstreamDecVideo *stream);
    PV_STATUS BitstreamFillCache(BitstreamDecVideo *stream);
    void BitstreamReset(BitstreamDecVideo *stream, uint8 *buffer, int32 buffer_size);
    int BitstreamOpen(BitstreamDecVideo *stream, int layer);
    void BitstreamClose(BitstreamDecVideo *stream);

    PV_STATUS BitstreamShowBits32(BitstreamDecVideo *stream, int nbits, uint32 *code);
    uint32 BitstreamReadBits32(BitstreamDecVideo *stream, int nbits);

    uint BitstreamReadBits16(BitstreamDecVideo *stream, int nbits);
    uint BitstreamRead1Bits(BitstreamDecVideo *stream);
#ifndef PV_BS_INLINE
    PV_STATUS BitstreamShowBits16(BitstreamDecVideo *stream, int nbits, uint *code);
    PV_STATUS BitstreamShow15Bits(BitstreamDecVideo *stream, uint *code);
    PV_STATUS BitstreamShow13Bits(BitstreamDecVideo *stream, uint *code);
    uint BitstreamReadBits16_INLINE(BitstreamDecVideo *stream, int nbits);
    uint BitstreamRead1Bits_INLINE(BitstreamDecVideo *stream);
#else
    __inline PV_STATUS BitstreamShowBits16(BitstreamDecVideo *stream, int nbits, uint *code)
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



    /* =========================================================================*/
    __inline PV_STATUS BitstreamShow15Bits(BitstreamDecVideo *stream, uint *code)
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


    __inline PV_STATUS BitstreamShow13Bits(BitstreamDecVideo *stream, uint *code)
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
    __inline uint BitstreamReadBits16_INLINE(BitstreamDecVideo *stream, int nbits)
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


    __inline uint BitstreamRead1Bits_INLINE(BitstreamDecVideo *stream)
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

#endif







    PV_STATUS PV_BitstreamFlushBitsCheck(BitstreamDecVideo *stream, int nbits);

    uint32 BitstreamReadBits32HC(BitstreamDecVideo *stream);
    PV_STATUS BitstreamShowBits32HC(BitstreamDecVideo *stream, uint32 *code);



    PV_STATUS BitstreamCheckEndBuffer(BitstreamDecVideo *stream);

    PV_STATUS PV_BitstreamShowBitsByteAlign(BitstreamDecVideo *stream, int nbits, uint32 *code);
#ifdef PV_ANNEX_IJKT_SUPPORT
    PV_STATUS PV_BitstreamShowBitsByteAlignNoForceStuffing(BitstreamDecVideo *stream, int nbits, uint32 *code);
    Bool validStuffing_h263(BitstreamDecVideo *stream);
    PV_STATUS quickSearchH263SliceHeader(BitstreamDecVideo *stream);
#endif
    PV_STATUS PV_BitstreamByteAlign(BitstreamDecVideo *stream);
    PV_STATUS BitstreamByteAlignNoForceStuffing(BitstreamDecVideo *stream);
    Bool validStuffing(BitstreamDecVideo *stream);

    PV_STATUS movePointerTo(BitstreamDecVideo *stream, int32 pos);
    PV_STATUS PVSearchNextM4VFrame(BitstreamDecVideo *stream);
    PV_STATUS PVSearchNextH263Frame(BitstreamDecVideo *stream);
    PV_STATUS quickSearchVideoPacketHeader(BitstreamDecVideo *stream, int marker_length);


    /* for error concealment & soft-decoding */
    void PVLocateM4VFrameBoundary(BitstreamDecVideo *stream);
    void PVSearchH263FrameBoundary(BitstreamDecVideo *stream);

    PV_STATUS quickSearchMotionMarker(BitstreamDecVideo *stream);
    PV_STATUS quickSearchDCM(BitstreamDecVideo *stream);
    PV_STATUS quickSearchGOBHeader(BitstreamDecVideo *stream);
    void BitstreamShowBuffer(BitstreamDecVideo *stream, int32 startbit, int32 endbit, uint8 *bitBfr);

    /*  10/8/98 New prototyps. */
    int32 getPointer(BitstreamDecVideo *stream);

#ifdef __cplusplus
}
#endif /* __cplusplus  */

#endif /* _BITSTREAM_D_H_ */
