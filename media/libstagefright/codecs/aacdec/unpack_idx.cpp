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

 Pathname: ./src/unpack_idx.c
 Function:  unpack_idx
            unpack_idx_sgn
            unpack_idx_esc

------------------------------------------------------------------------------
 REVISION HISTORY

 Description:  Modified from original shareware code

 Description:  Eliminated 3 divisions and 1 multiplication through a table
 look-up method for calculating 1/mod and constant allocation of 1/mod^3
 and 1/mod^2.
 Eliminated 3 additions through simple optimizations in the code.
 Changed if/else  statement to a switch/case utilizing fall-through.

 Description:   Made changes per review comments.  Main improvements were
 in change of switch/case to if statement, and use of temporary variable
 to hold value of *pQuantSpec.

 Description: (1) Typecast codeword_indx to Int32 before multiplication, this
              assures the shift operation happens on a 32-bit product on
              TI-C55x processor.
              (2) define temp_spec as Int32 to avoid overflow

 Description: Modified per review comments
              (1) remove the two typecastings of codeword_indx when
                  pHuffCodebook->dim == DIMENSION_4
              (2) temp_spec is Int because the result never exceeds 16 bits

 Description: Break up and combine unpack index with sign bit reading and for
              special escape code. Parent function must know which one of the
              3 functions should be called.

 Description: Put back if-statement to get the max.

 Description: When searching for the max, there was some instances where the
              max was compared against a negative number, so the max was never
              updated (defaulted to 0), leading to block processing in other
              magnitude sensitive stages.

 Who:                       Date:
 Description:
------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
          Int  quant_spec[]  = Array for storage of the quantized
                               spectral coefficients.  Length is either 2 or 4.
                               See Ref #1, Page 76 for a complete description.

          Int  codeword_indx = The index into the Huffman table.
                               Range is [1-288]

    const Hcb *pHuffCodebook = Pointer to HuffmanCodebook information.

          BITS  *pInputStream = Pointer to the bitstream buffer.
          Int *max           = Pointer to maximum coefficient value.

 Local Stores/Buffers/Pointers Needed:
    const UInt div_mod[18]   = An array with the values for 1/mod
                               stored in Q-formats 13.

 Global Stores/Buffers/Pointers Needed:
    None

 Outputs:
    None

 Pointers and Buffers Modified:
    Int quant_spec[] = Output (the quantized and signed spectral coefficients)
                       returned via this pointer.

 Local Stores Modified:
    None

 Global Stores Modified:
    None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 This function decodes quantized spectral coefficients and decode their signs
 from the input bitstream. Quantized spectral coefficients are transmitted as
 four-tuples or 2-tuples, and this information is conveyed to the function via
 the variable HuffCodebook->dim.

 See Reference #1 for a complete description
------------------------------------------------------------------------------
 REQUIREMENTS

 This function shall correctly calculate pQuantSpec[], given the inputs

 codeword_indx     = {1-288};
 HuffCodebook->off = {0, 1, 4};
 HuffCodebook->mod = {3, 8, 9, 13, 17};

 mod =   LAV + 1 if unsigned codebook
 mod = 2*LAV + 1 if   signed codebook

 Range of values for LAV is {2,7,12,16} if unsigned
                            {1,4}       if   signed

 Additionally,
     LAV <= 2 if dim == 4

 This restricts mod ==  3                if dim == 4
            and mod == {3, 8, 9, 13, 17} if dim == 2

 This function will NOT function correctly if fed values that do not
 meet the requirements as stated above.

 This limitation on the range of values was determined by analysis
 of Reference #1 (see below.)

------------------------------------------------------------------------------
 REFERENCES

 (1) ISO/IEC 14496-3:1999(E)
     Part 3
        Subpart 4.6.3.3   Decoding Process
        Subpart 4.6.4     Tables
 (2) MPEG-2 NBC Audio Decoder
   "This software module was originally developed by AT&T, Dolby
   Laboratories, Fraunhofer Gesellschaft IIS in the course of development
   of the MPEG-2 NBC/MPEG-4 Audio standard ISO/IEC 13818-7, 14496-1,2 and
   3. This software module is an implementation of a part of one or more
   MPEG-2 NBC/MPEG-4 Audio tools as specified by the MPEG-2 NBC/MPEG-4
   Audio standard. ISO/IEC  gives users of the MPEG-2 NBC/MPEG-4 Audio
   standards free license to this software module or modifications thereof
   for use in hardware or software products claiming conformance to the
   MPEG-2 NBC/MPEG-4 Audio  standards. Those intending to use this software
   module in hardware or software products are advised that this use may
   infringe existing patents. The original developer of this software
   module and his/her company, the subsequent editors and their companies,
   and ISO/IEC have no liability for use of this software module or
   modifications thereof in an implementation. Copyright is not released
   for non MPEG-2 NBC/MPEG-4 Audio conforming products.The original
   developer retains full right to use the code for his/her  own purpose,
   assign or donate the code to a third party and to inhibit third party
   from using the code for non MPEG-2 NBC/MPEG-4 Audio conforming products.
   This copyright notice must be included in all copies or derivative
   works."
   Copyright(c)1996.

------------------------------------------------------------------------------
 PSEUDO-CODE

    IF (pHuffCodebook->dim == 4)
        *(pQuantSpec) = codeword_indx/(3^3);
        codeword_indx = codeword_indx - *(pQuantSpec)*(3^3);
        *(pQuantSpec) = *(pQuantSpec) - off;

        pQuantSpec    = pQuantSpec + 1;

        *(pQuantSpec) = codeword_indx/(3^2);
        codeword_indx = codeword_indx - *(pQuantSpec)*(3^2);
        *(pQuantSpec) = *(pQuantSpec) - off;

        pQuantSpec    = pQuantSpec + 1;
    ENDIF

        *(pQuantSpec) = codeword_indx/mod;
        codeword_indx = codeword_indx - (*pQuantSpec)*mod;
        *(pQuantSpec) = *(pQuantSpec) - off;

        pQuantSpec    = pQuantSpec + 1;

        *(pQuantSpec) = codeword_indx - off;

------------------------------------------------------------------------------
 RESOURCES USED
   When the code is written for a specific target processor the
     the resources used should be documented below.

 STACK USAGE: [stack count for this module] + [variable to represent
          stack usage for each subroutine called]

     where: [stack usage variable] = stack usage for [subroutine
         name] (see [filename].ext)

 DATA MEMORY USED: x words

 PROGRAM MEMORY USED: x words

 CLOCK CYCLES: [cycle count equation for this module] + [variable
           used to represent cycle count for each subroutine
           called]

     where: [cycle count variable] = cycle count for [subroutine
        name] (see [filename].ext)

------------------------------------------------------------------------------
*/


/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include "pv_audio_type_defs.h"
#include "s_hcb.h"
#include "ibstream.h"
#include "unpack_idx.h"

#include "fxp_mul32.h"


/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here. Include conditional
; compile variables also.
----------------------------------------------------------------------------*/
#define DIV_3_CUBED    19  /* 19 = 1/27 in Q-9 format    */
#define THREE_CUBED    27  /* 27 = 3^3                   */

#define DIV_3_SQUARED  57  /* 57 = 1/9  in Q-9 format    */
#define THREE_SQUARED   9  /*  9 = 3^2                   */

#define Q_FORMAT_MOD   13  /* Q-format for 1/mod table   */
#define Q_FORMAT_MOD2   9  /* Q-format for DIV_3_SQUARED */
#define Q_FORMAT_MOD3   9  /* Q-format for DIV_3_CUBED   */

#define LOWER_5_BITS_MASK 0x1F


#if ( defined(PV_ARM_V5) || defined(PV_ARM_V4))

__inline Int32 abs1(Int32 x)
{
    Int32 z;
    /*
        z = x - (x<0);
        x = z ^ sign(z)
     */
    __asm
    {
        sub  z, x, x, lsr #31
        eor  x, z, z, asr #31
    }
    return (x);
}

#define pv_abs(x)   abs1(x)


#else

#define pv_abs(x)   ((x) > 0)? (x) : (-x)

#endif



/*----------------------------------------------------------------------------
; LOCAL FUNCTION DEFINITIONS
; Function Prototype declaration
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; LOCAL STORE/BUFFER/POINTER DEFINITIONS
; Variable declaration - defined here and used outside this module
----------------------------------------------------------------------------*/
/*--------------------------------------------------------------------------
    Possible values for mod = {3,8,9,13,17}

    There exists "empty" spaces in the table.  These can potentially
    be utilized by other const tables, if available memory becomes an issue.
---------------------------------------------------------------------------*/

const Int div_mod[18] =   /*   mod   index  Q-format */
{
    /* ----------------------- */
    0xCC,                 /* |      |  0  |          */
    0xCC,                 /* |      |  1  |          */
    0xCC,                 /* |      |  2  |          */
    2731,                 /* |  3   |  3  |   13     */
    0xCC,                 /* |      |  4  |          */
    0xCC,                 /* |      |  5  |          */
    0xCC,                 /* |      |  6  |          */
    0xCC,                 /* |      |  7  |          */
    1025,                 /* |  8   |  8  |   13     */
    911,                 /* |  9   |  9  |   13     */
    0xCC,                 /* |      | 10  |          */
    0xCC,                 /* |      | 11  |          */
    0xCC,                 /* |      | 12  |          */
    631,                 /* |  13  | 13  |   13     */
    0xCC,                 /* |      | 14  |          */
    0xCC,                 /* |      | 15  |          */
    0xCC,                 /* |      | 16  |          */
    482,                 /* |  17  | 17  |   13     */
};

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
void unpack_idx(
    Int16   quant_spec[],
    Int codeword_indx,
    const Hcb   *pHuffCodebook,
    BITS  *pInputStream,
    Int *max)
{
    Int16 *pQuantSpec = &quant_spec[0];
    Int  temp_spec;

    const Int mod = pHuffCodebook->mod;
    const Int off = pHuffCodebook->off;

    OSCL_UNUSED_ARG(pInputStream);


    if (pHuffCodebook->dim == DIMENSION_4)
    {
        /* Calculate pQuantSpec[0] */

        temp_spec      = (codeword_indx * DIV_3_CUBED) >> Q_FORMAT_MOD3;

        codeword_indx -= temp_spec * THREE_CUBED;

        temp_spec -= off;
        *pQuantSpec++  = (Int16)temp_spec;

        temp_spec = pv_abs(temp_spec);

        if (temp_spec > *max)
        {
            *max = temp_spec;
        }

        /* Calculate pQuantSpec[1] */
        temp_spec      = (codeword_indx * DIV_3_SQUARED) >> Q_FORMAT_MOD2;

        codeword_indx -= temp_spec * THREE_SQUARED;

        temp_spec -= off;
        *pQuantSpec++  = (Int16)temp_spec;

        temp_spec = pv_abs(temp_spec);

        if (temp_spec > *max)
        {
            *max = temp_spec;
        }
    }

    /*
     *  Calculate pQuantSpec[2] if dim == 4
     *  Calculate pQuantSpec[0] if dim == 2
     */

    temp_spec      = ((Int32) codeword_indx * div_mod[mod]) >> Q_FORMAT_MOD;

    codeword_indx -= temp_spec * mod;

    temp_spec -= off;
    *pQuantSpec++  = (Int16)temp_spec;

    temp_spec = pv_abs(temp_spec);


    if (temp_spec > *max)
    {
        *max = temp_spec;
    }

    /*
    *  Calculate pQuantSpec[3] if dim == 4
    *  Calculate pQuantSpec[1] if dim == 2
    */
    codeword_indx -= off;
    *pQuantSpec    = (Int16)codeword_indx ;


    codeword_indx = pv_abs(codeword_indx);

    if (codeword_indx > *max)
    {
        *max = codeword_indx;
    }


    return ;
} /* unpack_idx */


void unpack_idx_sgn(
    Int16   quant_spec[],
    Int codeword_indx,
    const Hcb   *pHuffCodebook,
    BITS  *pInputStream,
    Int *max)
{
    Int16 *pQuantSpec = &quant_spec[0];
    Int  temp_spec;
    Int  sgn;

    const Int mod = pHuffCodebook->mod;
    const Int off = pHuffCodebook->off;



    if (pHuffCodebook->dim == DIMENSION_4)
    {
        /* Calculate pQuantSpec[0] */
        preload_cache((Int32 *)pQuantSpec);
        temp_spec      = (codeword_indx * DIV_3_CUBED) >> Q_FORMAT_MOD3;

        codeword_indx -= temp_spec * THREE_CUBED;

        temp_spec -= off;
        if (temp_spec)
        {
            sgn = get1bits(pInputStream);


            *pQuantSpec++ = (Int16)((sgn) ? -temp_spec : temp_spec);

            temp_spec = pv_abs(temp_spec);

            if (temp_spec > *max)
            {
                *max = temp_spec;
            }

        }
        else
        {
            *pQuantSpec++ = 0;
        }

        /* Calculate pQuantSpec[1] */
        temp_spec      = (codeword_indx * DIV_3_SQUARED) >> Q_FORMAT_MOD2;

        codeword_indx -= temp_spec * THREE_SQUARED;

        temp_spec -= off;
        if (temp_spec)
        {

            sgn = get1bits(pInputStream);

            *pQuantSpec++ = (Int16)((sgn) ? -temp_spec : temp_spec);

            temp_spec = pv_abs(temp_spec);

            if (temp_spec > *max)
            {
                *max = temp_spec;
            }
        }
        else
        {
            *pQuantSpec++ = 0;
        }
    }

    /*
     *  Calculate pQuantSpec[2] if dim == 4
     *  Calculate pQuantSpec[0] if dim == 2
     */

    temp_spec      = ((Int32) codeword_indx * div_mod[mod]) >> Q_FORMAT_MOD;

    codeword_indx -= temp_spec * mod;

    temp_spec -= off;
    if (temp_spec)
    {

        sgn = get1bits(pInputStream);

        *pQuantSpec++ = (Int16)((sgn) ? -temp_spec : temp_spec);

        temp_spec = pv_abs(temp_spec);

        if (temp_spec > *max)
        {
            *max = temp_spec;
        }
    }
    else
    {
        *pQuantSpec++ = 0;
    }

    /*
     *  Calculate pQuantSpec[3] if dim == 4
     *  Calculate pQuantSpec[1] if dim == 2
     */
    codeword_indx -= off;
    if (codeword_indx)
    {

        sgn = get1bits(pInputStream);

        *pQuantSpec = (Int16)((sgn) ? -codeword_indx : codeword_indx);

        codeword_indx = pv_abs(codeword_indx);

        if (codeword_indx > *max)
        {
            *max = codeword_indx;
        }
    }
    else
    {
        *pQuantSpec = 0;
    }

    return ;
} /* unpack_idx_sgn */


void unpack_idx_esc(
    Int16   quant_spec[],
    Int codeword_indx,
    const Hcb   *pHuffCodebook,
    BITS  *pInputStream,
    Int *max)
{
    Int  temp_spec;
    Int  sgn1 = 0, sgn2 = 0;
    Int N;
    Int32 esc_seq;

    const Int mod = pHuffCodebook->mod;
    const Int off = pHuffCodebook->off;


    temp_spec      = ((Int32) codeword_indx * div_mod[mod]) >> Q_FORMAT_MOD;

    codeword_indx -= temp_spec * mod;

    temp_spec -= off;
    if (temp_spec)
    {
        sgn1 = get1bits(pInputStream);
    }

    codeword_indx -= off;
    if (codeword_indx)
    {
        sgn2 = get1bits(pInputStream);
    }


    if ((temp_spec & LOWER_5_BITS_MASK) == 16)
    {
        N = 3;
        do
        {
            N++;

            esc_seq = get1bits(pInputStream);

        }
        while (esc_seq != 0);

        esc_seq  = getbits(N, pInputStream);

        esc_seq += (1 << N);


        temp_spec = (Int)((temp_spec * esc_seq) >> 4);

    }


    if (sgn1)
    {
        quant_spec[0]  = (Int16)(-temp_spec);
    }
    else
    {
        quant_spec[0]  = (Int16)temp_spec;
    }

    temp_spec = pv_abs(temp_spec);

    if (temp_spec > *max)
    {
        *max = temp_spec;
    }

    if ((codeword_indx & LOWER_5_BITS_MASK) == 16)
    {
        N = 3;
        do
        {
            N++;

            esc_seq = get1bits(pInputStream);

        }
        while (esc_seq != 0);

        esc_seq  = getbits(N, pInputStream);

        esc_seq += (1 << N);

        codeword_indx = (Int)((codeword_indx * esc_seq) >> 4);
    }




    if (sgn2)
    {
        quant_spec[1]    = (Int16)(-codeword_indx);
    }
    else
    {
        quant_spec[1]    = (Int16)codeword_indx;
    }


    codeword_indx = pv_abs(codeword_indx);

    if (codeword_indx > *max)
    {
        *max = codeword_indx;
    }


    return ;
} /* unpack_idx_esc */
