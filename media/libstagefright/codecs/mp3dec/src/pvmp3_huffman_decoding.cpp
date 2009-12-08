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
/*
------------------------------------------------------------------------------

   PacketVideo Corp.
   MP3 Decoder Library

   Filename: pvmp3_huffman_decoding.cpp

 Funtions:
    pvmp3_huffman_quad_decoding
    pvmp3_huffman_pair_decoding
    pvmp3_huffman_pair_decoding_linbits

     Date: 09/21/2007

------------------------------------------------------------------------------
 REVISION HISTORY


 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    struct huffcodetab *h,   pointer to huffman code record
    int32 *x,    returns decoded x value
    int32 *y,    returns decoded y value
    int32 *v,    returns decoded v value   (only in quad function)
    int32 *w,    returns decoded w value   (only in quad function)
    tbits *pMainData     bit stream

 Outputs:


------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

   These functions are used to decode huffman codewords from the input
   bitstream using combined binary search and look-up table approach.

------------------------------------------------------------------------------
 REQUIREMENTS


------------------------------------------------------------------------------
 REFERENCES
 [1] ISO MPEG Audio Subgroup Software Simulation Group (1996)
     ISO 13818-3 MPEG-2 Audio Decoder - Lower Sampling Frequency Extension


------------------------------------------------------------------------------
 PSEUDO-CODE

------------------------------------------------------------------------------
*/


/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include "pvmp3_dec_defs.h"
#include "pv_mp3_huffman.h"
#include "pvmp3_getbits.h"


/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here. Include conditional
; compile variables also.
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; LOCAL FUNCTION DEFINITIONS
; Function Prototype declaration
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; LOCAL STORE/BUFFER/POINTER DEFINITIONS
; Variable declaration - defined here and used outside this module
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; EXTERNAL FUNCTION REFERENCES
; Declare functions defined elsewhere and referenced in this module
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; EXTERNAL GLOBAL STORE/BUFFER/POINTER REFERENCES
; Declare variables used in this module but defined elsewhere
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/


void pvmp3_huffman_quad_decoding(struct huffcodetab *h,
                                 int32 *is,
                                 tmp3Bits *pMainData)
{

    int32 x;
    int32 y;
    int32 v;
    int32 w;

    y = (*h->pdec_huff_tab)(pMainData);


    if (y)
    {
        v = (y >> 3);

        if (v)
        {
            if (get1bit(pMainData))
            {
                v = -v;
            }
        }
        w = (y >> 2) & 1;
        if (w)
        {
            if (get1bit(pMainData))
            {
                w = -w;
            }
        }
        x = (y >> 1) & 1;
        if (x)
        {
            if (get1bit(pMainData))
            {
                x = -x;
            }
        }
        y =  y & 1;
        if (y)
        {
            if (get1bit(pMainData))
            {
                y = -y;
            }
        }

    }
    else
    {
        v = 0;
        w = 0;
        x = 0;

    }

    *is     = v;
    *(is + 1) = w;
    *(is + 2) = x;
    *(is + 3) = y;

}



void pvmp3_huffman_pair_decoding(struct huffcodetab *h,     /* pointer to huffman code record   */
                                 int32 *is,
                                 tmp3Bits *pMainData)
{
    /* Lookup in Huffman table. */
    int32 x;
    int32 y;

    uint16 cw = (*h->pdec_huff_tab)(pMainData);

    /* Process sign and escape encodings for dual tables. */


    if (cw)
    {
        x = cw >> 4;

        if (x)
        {
            if (get1bit(pMainData))
            {
                x = -x;
            }
            y = cw & 0xf;
            if (y && get1bit(pMainData))
            {
                y = -y;
            }

        }
        else
        {
            y = cw & 0xf;
            if (get1bit(pMainData))
            {
                y = -y;
            }
        }

        *is     = x;
        *(is + 1) = y;
    }
    else
    {
        *is     = 0;
        *(is + 1) = 0;
    }



}




void pvmp3_huffman_pair_decoding_linbits(struct huffcodetab *h,     /* pointer to huffman code record   */
        int32 *is,
        tmp3Bits *pMainData)
{
    int32 x;
    int32 y;

    uint16 cw;
    /* Lookup in Huffman table. */


    cw = (*h->pdec_huff_tab)(pMainData);
    x = cw >> 4;

    /* Process sign and escape encodings for dual tables. */


    if (15 == (uint32)x)
    {
        int32 tmp = getUpTo17bits(pMainData, (h->linbits + 1));
        x += tmp >> 1;
        if (tmp&1)
        {
            x = -x;
        }
    }
    else if (x)
    {
        if (get1bit(pMainData))
        {
            x = -x;
        }
    }

    y = cw & 0xf;
    if (15 == (uint32)y)
    {
        int32 tmp = getUpTo17bits(pMainData, (h->linbits + 1));
        y += tmp >> 1;
        if (tmp&1)
        {
            y = -y;
        }
    }
    else if (y)
    {
        if (get1bit(pMainData))
        {
            y = -y;
        }
    }

    *is     = x;
    *(is + 1) = y;

}





