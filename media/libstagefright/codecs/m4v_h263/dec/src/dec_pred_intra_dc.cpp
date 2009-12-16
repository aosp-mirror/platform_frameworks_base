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
#include "mp4dec_lib.h"
#include "vlc_decode.h"
#include "bitstream.h"
#include "zigzag.h"

PV_STATUS PV_DecodePredictedIntraDC(
    int compnum,
    BitstreamDecVideo *stream,
    int16 *INTRADC_delta)
{

    /*----------------------------------------------------------------------------
    ; Define all local variables
    ----------------------------------------------------------------------------*/
    PV_STATUS status = PV_SUCCESS;
    uint DC_size;
    uint code;
    int first_bit;

    /*----------------------------------------------------------------------------
    ; Function body here
    ----------------------------------------------------------------------------*/
    /* read DC size 2 - 8 bits */
    status = PV_VlcDecIntraDCPredSize(stream, compnum, &DC_size);

    if (status == PV_SUCCESS)
    {
        if (DC_size == 0)
        {
            *INTRADC_delta = 0;
        }
        else
        {
            /* read delta DC 0 - 8 bits */
            code = (int) BitstreamReadBits16_INLINE(stream, DC_size);

            first_bit = code >> (DC_size - 1);

            if (first_bit == 0)
            {
                /* negative delta INTRA DC */
                *INTRADC_delta = code ^((1 << DC_size) - 1);
                *INTRADC_delta = -(*INTRADC_delta);
            }
            else
            { /* positive delta INTRA DC */
                *INTRADC_delta = code;
            }
            if (DC_size > 8) BitstreamRead1Bits_INLINE(stream);
        }
    }

    /*----------------------------------------------------------------------------
    ; Return nothing or data or data pointer
    ----------------------------------------------------------------------------*/
    return status;
}

