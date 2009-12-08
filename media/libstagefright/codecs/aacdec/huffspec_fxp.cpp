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

 Pathname:  huffspec_fxp.c
 Funtions:
    huffspec_fxp

------------------------------------------------------------------------------
 REVISION HISTORY

 Description:  Modified from original shareware code

 Description:  Modified to pass variables by reference to eliminate use
               of global variables.

 Description: (1) Modified to keep in-line with PV standards
              (2) Eliminated "continue" in if(sect_cb==ZERO_HCB||...)

 Description: (1) Use SectInfo *pSect
              (2) Convert 'Real' to 'Int32', float -> fixed-point
              (3) move BITS *pInputStream to second parameter
              (4) pass in quantSpec and tmp_spec, scratch shared with hufffac
              (5) pass in FrameInfo *pLongFrameInfo, eliminate only_long_info

 Description: (1) Eliminate parameter Hcb *book, because of eliminating
                  function 'hufftab.c', Hcb hcbbook defined as a
                  const structure in 'hcbtables.h'.
              (2) Replace three nested 'for' loops with a for-while loop in
                  the rescaling part.
              (3) Change esc_iquant-> esc_iquant_fxp, call esc_iquant_fxp()
                  by sfb

 Description: Cleaned up include files.

 Description:  Correct definition of stack variable "scale".
        It was defined as Int, but it receives an UInt value,
        this present a problem when Int is 16 bits and
        the sign bit is not interpreted correctly. This does not
        shows for 32-bit implementations. This problem manifest itself
        as a flipping sign on some spectral coefficients (the ones
        multiplied by 0x8000).

 Description: Typecast b_low and b_high to 32-bits before multiplication, this
              assures propoer compilation on a 16-bit platform (TI-C55x)

 Description: Modified to speed up decode_huff_cw

 Description: pass codebook index to decode_huff_cw, delete pointer to Huffman
              structure

 Description: keep memset to quantSpec, remove memset to temp_spec

 Description: Modified per review comments

 Description: Use Binary tree search in decode_huff_cw_binary

 Description: Modified per review comments
              (1) delete unused codes

 Description: (1) Change the interface to decode huffman codeword.
              (2) Move the scaling inside the inverse quantization.
              (3) Change scaling factor accuracy to 10 bits.

 Description:
              (1) delete unused variable max_fac

 Description: Addresses of huffman tables are now found by means of a
              switch statement, this solve linking problem when using the
              /ropi option (Read-only position independent) for some
              compilers

 Who:                                   Date: MM/DD/YYYY
 Description:
------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    pFrameInfo  = ptr to structure that holds Information of current Frame,
                  type FrameInfo

    pInputStream = ptr to structure of bitstream, type BITS

    nsect       = number of sections in current Frame, at fs = 44.1 kHz,
                  range [0, 49] long block, [0,112] short blocks. type Int

    pSect       = ptr to structure that holds section codebook and boundary
                  type SectInfo

    factors[]   = array that contains scalefactors for each sfb, type Int16

    coef[]      = array that holds inverse quantized coefs, Int32 QFormat.

    quantSpec[] = array that holds quantized spectral coefs, type Int

    tmp_spec[]  = temporary buffer to hold the de-interleaved coefs.

    pLongFrameInfo = ptr to structure that holds long frame info

 Local Stores/Buffers/Pointers Needed:
    exptable = array contains the Q15 format data for 2^0, 2^0.25, 2^0.5,
               and 2^0.75, type const Int.

 Global Stores/Buffers/Pointers Needed:
    None

 Outputs:

    return 0 if decoding properly.

 Pointers and Buffers Modified:

    pInputStream    read codeword index and/or sign bits and/or ESC value

    coef            contains the newly inverse quantized 1024 spec coefs,
                    type Int32 Q-format from esc_iquant()

    quantSpec       contains decoded quantized 1024 spec coefs, type Int

    tmp_spec        contains the de-interleaved version of quantSpec

    qFormat         contains Q-Format for each scalefactor band

 Local Stores Modified:
    None

 Global Stores Modified:
    None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 This function first reads the section info (codebook and boundary), then
 decode the spectral coefficients if a spectral codebook is used.
 If necessary, get the sign bits, ESC value or the NEC_pulse data. In case of
 short window sequences, the decoded data is de-interleaved before
 multiplied by scalefactors.

------------------------------------------------------------------------------
 REQUIREMENTS

 This function should set the content of the array 'coef' with the inverse
 quantized and rescaled value of spectral coefficients.

------------------------------------------------------------------------------
 REFERENCES

 (1) MPEG-2 NBC Audio Decoder
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
   developer retains full right to use the code for his/her own purpose,
   assign or donate the code to a third party and to inhibit third party
   from using the code for non MPEG-2 NBC/MPEG-4 Audio conforming products.
   This copyright notice must be included in all copies or derivative
   works."
   Copyright(c)1996.

 (2) ISO/IEC 14496-3: 1999(E)
    Subpart (4)         p56 (spectral_data() parsing and decoding)
                        p26 (Syntax of spectral_data())
                        p74-78 (decoding: unpack_idx, get_sign_bits,
                                getescape, pulse_nc, deinterleave)
                        p72 (inverse quantization: esc_iquant)

------------------------------------------------------------------------------
 PSEUDO-CODE


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
#include    "pv_audio_type_defs.h"
#include    "aac_mem_funcs.h"
#include    "esc_iquant_scaling.h"
#include    "huffman.h"
#include    "unpack_idx.h"
#include    "pulse_nc.h"
#include    "iquant_table.h"
#include    "e_huffmanconst.h"


#include "pv_normalize.h"

/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here. Include conditional
; compile variables also.
----------------------------------------------------------------------------*/
#define ORDER        (3)

/*
 * Format the table is stored in.
 */
#define QTABLE       (27)

/*
 * Number of bits for data in a signed 32 bit integer.
 */
#define SIGNED32BITS  (31)

/*
 * Round up value for intermediate values obtained from the table
 */
#define ROUND_UP (( ((UInt32) 1) << (QTABLE) )-1)

/*----------------------------------------------------------------------------
; LOCAL FUNCTION DEFINITIONS
; Function Prototype declaration
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; LOCAL STORE/BUFFER/POINTER DEFINITIONS
; Variable declaration - defined here and used outside this module
----------------------------------------------------------------------------*/
const UInt16 exptable[4] =
{
    0,  /* (2^0.00)<<15 (Q10), use zero to signal no scaling required! */
    19485,  /* (2^0.25)<<15 */
    23171,  /* (2^0.50)<<15 */
    27555   /* (2^0.75)<<15 */

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

Int huffspec_fxp(
    FrameInfo *pFrameInfo,
    BITS      *pInputStream,
    Int       nsect,
    SectInfo  *pSectInfo,
    Int       factors[],
    Int32     coef[],
    Int16     quantSpec[],
    Int16     tmp_spec[],
    const FrameInfo  *pLongFrameInfo,
    PulseInfo  *pPulseInfo,
    Int         qFormat[])
{
    /*----------------------------------------------------------------------------
    ; Define all local variables
    ----------------------------------------------------------------------------*/
    const Hcb       *pHcb;
    Int     i;
    Int     sfb;
    Int     idx_count;
    Int     sect_cb;    /* section codebook */
    Int     dim;
    Int     idx;
    Int     stop_idx;     /* index of 1st coef in next sfb */
    Int     sect_start;   /* start index of sfb in one section*/
    Int     sect_end;     /* index of 1st sfb in next section */
    Int     *pSfbStart;
    Int     *pSfb;
    Int16     *pQuantSpec;        /* probably could be short */
    Int     max = 0;
    /* rescaling parameters */
    Int     nsfb;
    Int     tot_sfb;
    Int     fac;

    Int32   *pCoef; /* ptr to coef[], inverse quantized coefs */
    UInt16     scale;

    Int     power_scale_div_4;
    Int     sfbWidth;

    void (*pUnpack_idx)(
        Int16  quant_spec[],
        Int  codeword_indx,
        const Hcb *pHuffCodebook,
        BITS  *pInputStream,
        Int *max);

    Int(*pDec_huff_tab)(BITS *) = NULL;

    UInt32 temp;
    Int    binaryDigits, QFormat;

    /*----------------------------------------------------------------------------
    ; Function body here
    ----------------------------------------------------------------------------*/

    sect_start = 0;
    stop_idx   = 0;

    /* pSfb: ptr to array that holds stop index of each sfb */
    pSfbStart = pFrameInfo->frame_sfb_top;

    if (pSfbStart == NULL)
    {
        return (-1);   /*  error condition */
    }

    pSfb      = pSfbStart;

    /* decoding spectral values section by section */
    for (i = nsect; i > 0; i--)
    {
        /* read the codebook and section length */
        sect_cb  =  pSectInfo->sect_cb;     /* codebook */
        if ((sect_cb > 15) || (sect_cb < 0))
        {
            return (-1);   /*  error condition */
        }
        sect_end =  pSectInfo->sect_end;    /* # of sfbs */

        if (sect_end < 0)
        {
            return (-1);   /*  error condition */
        }

        pSectInfo++;

        /*  sect_cb       sect_cb - 1
         *  ZERO_HCB        1111b
         *    1             0000b
         *    2             0001b
         *    3             0010b
         *    4             0011b
         *    5             0100b
         *    6             0101b
         *    7             0110b
         *    8             0111b
         *    9             1000b
         *    10            1001b
         *    11            1010b
         *    12            1011b
         * NOISE_HCB        1100b
         * INTENSITY_HCB2   1101b
         * INTENSITY_HCB    1110b
         * if ( ((sect_cb - 1) & 0xC) == 0xC ) is identical to
         * if !((sect_cb == ZERO_HCB) || (sect_cb == NOISE_HCB) ||
         *      (sec_cb == INTENSITY_HCB) || (sect_cb==INTENSITY_HCB2) )
         * use this compare scheme to speed up the execution
         */

        if (((sect_cb - 1) & 0xC) != 0xC)
        {
            /* decode spec in one section */
            if (sect_cb > BY4BOOKS)
            {
                dim = DIMENSION_2; /* set codebook dimension */
            }
            else
            {
                dim = DIMENSION_4;
            }

            pHcb        = &hcbbook_binary[sect_cb];

            if (sect_cb == ESCBOOK)
            {
                pUnpack_idx = &unpack_idx_esc;
            }
            else if (pHcb->signed_cb == FALSE)
            {
                pUnpack_idx = &unpack_idx_sgn;
            }
            else
            {
                pUnpack_idx = &unpack_idx;
            }


            switch (sect_cb)
            {
                case 1:
                    pDec_huff_tab = decode_huff_cw_tab1;
                    break;
                case 2:
                    pDec_huff_tab = decode_huff_cw_tab2;
                    break;
                case 3:
                    pDec_huff_tab = decode_huff_cw_tab3;
                    break;
                case 4:
                    pDec_huff_tab = decode_huff_cw_tab4;
                    break;
                case 5:
                    pDec_huff_tab = decode_huff_cw_tab5;
                    break;
                case 6:
                    pDec_huff_tab = decode_huff_cw_tab6;
                    break;
                case 7:
                    pDec_huff_tab = decode_huff_cw_tab7;
                    break;
                case 8:
                    pDec_huff_tab = decode_huff_cw_tab8;
                    break;
                case 9:
                    pDec_huff_tab = decode_huff_cw_tab9;
                    break;
                case 10:
                    pDec_huff_tab = decode_huff_cw_tab10;
                    break;
                case 11:
                    pDec_huff_tab = decode_huff_cw_tab11;
                    break;
                default:
                    return (-1); /* error condition */
            }

            /* move ptr to first sfb of current section */
            pQuantSpec  = quantSpec + stop_idx;

            /* step through all sfbs in current section */
            for (sfb = sect_start; sfb < sect_end; sfb++)
            {
                idx_count = *pSfb - stop_idx;
                stop_idx  = *pSfb++;

                /* decode all coefs for one sfb */
                while ((idx_count > 0) && (idx_count < 1024))
                {

                    idx = (*pDec_huff_tab)(pInputStream);

                    (*pUnpack_idx)(pQuantSpec,
                                   idx,
                                   pHcb,
                                   pInputStream,
                                   &max);      /* unpack idx -> coefs */

                    pQuantSpec += dim;
                    idx_count  -= dim;

                } /* while(idx_count) */

            } /* for (sfb=sect_start) */
        }
        else
        {

            /* current section uses ZERO_HCB, NOISE_HCB, etc */

            /* move sfb pointer to the start sfb of next section */
            pSfb        = pSfbStart + sect_end;
            /* number of coefs in current section */
            idx_count   = *(pSfb - 1) - stop_idx;

            if ((idx_count > 1024) || (idx_count < 0))
            {
                return (-1);   /*  error condition */
            }

            /*
             * This memset is necessary in terms of (1) net savings in total
             * MIPS and (2) accurate Q-Formats for fft_rx2
             * In case a scalefactor band uses ZERO_HCB, all coefficients of
             * that sfb should be zeros. Without this call to memset, the
             * coefficients for a ZERO_HCB sfb are the "leftovers" of the
             * previous frame, which may not have all zero values. This leads
             * to a drastical increase in the cycles consumed by esc_iquant_fxp
             * and fft_rx2, which is the most "expensive" function of the
             * library.
             * This memset also guarantees the Q_Format for sfbs with all zero
             * coefficients will be set properly.
             * Profiling data on ARM and TMS320C55x proves that there is a net
             * gain in total MIPS if a memset is called here.
             */
            pv_memset(&quantSpec[stop_idx],
                      0,
                      idx_count * sizeof(quantSpec[0]));

            /*
             * This memset is called because pQuantSpec points to tmp_spec
             * after deinterleaving
             */

            pv_memset(&tmp_spec[stop_idx],
                      0,
                      idx_count * sizeof(tmp_spec[0]));


            /* stop_idx is the index of the 1st coef of next section */
            stop_idx    = *(pSfb - 1);

        }/* if (sect_cb) */

        sect_start = sect_end;

    } /* for (i=nsect) */

    /* noisless coding reconstruction */
    if (pFrameInfo->islong != FALSE)
    {
        if (pPulseInfo->pulse_data_present == 1)
        {
            pulse_nc(quantSpec,
                     pPulseInfo,
                     pLongFrameInfo,
                     &max);    /* add pulse data */
        }

        pQuantSpec = quantSpec;

    }
    else
    {
        deinterleave(quantSpec,
                     tmp_spec,
                     pFrameInfo);

        pQuantSpec = tmp_spec;
    }


    /* inverse quantization, Q_format: Int32 */
    /* rescaling */

    /* what we can do here is assuming that we already know maxInput for each band, we have to go
    though each one of them for re-quant and scaling, and pick the right qFormat to apply to
    all spectral coeffs.*/

    if ((max < 0) || (max > 8192))    /* (8192>>ORDER) == 1024 is the inverseQuantTable size */
    {
        return (-1);   /*  error condition */
    }
    else
    {
        /* Get  (max/SPACING) ^ (1/3), in Q Format  */
        temp = inverseQuantTable[(max >> ORDER) + 1];
    }


    /* Round up before shifting down to Q0 */
    temp += ROUND_UP;

    /* shift down to Q0 and multiply by 2 (FACTOR) in one step */
    temp >>= (QTABLE - 1);

    /* Now get max ^ (4/3) in Q0 */
    temp *= max;


    binaryDigits = 31 - pv_normalize(temp);


    /* Prevent negative shifts caused by low maximums. */
    if (binaryDigits < (SIGNED32BITS - QTABLE))
    {
        binaryDigits = SIGNED32BITS - QTABLE;
    }

    QFormat = SIGNED32BITS - binaryDigits;

    /********************/
    tot_sfb = 0;
    nsfb = pFrameInfo->sfb_per_win[0];
    pCoef = coef;

    for (i = pFrameInfo->num_win; i > 0; i--)
    {
        stop_idx  = 0;

        for (sfb = 0; sfb < nsfb; sfb++)
        {
            sfbWidth   =  pFrameInfo->win_sfb_top[0][sfb] - stop_idx;

            if ((sfbWidth < 0) || (sfbWidth > 1024))
            {
                return (-1);   /*  error condition */
            }

            stop_idx  += sfbWidth;

            fac   = factors[tot_sfb] - SF_OFFSET;
            scale = exptable[fac & 0x3];

            power_scale_div_4 = fac >> 2;

            power_scale_div_4++;

            qFormat[tot_sfb] = QFormat;

            esc_iquant_scaling(pQuantSpec,
                               pCoef,
                               sfbWidth,
                               QFormat,
                               scale,
                               max);

            pQuantSpec += sfbWidth;
            qFormat[tot_sfb] -= power_scale_div_4;
            pCoef += sfbWidth;

            tot_sfb++;

        } /* for (sfb) */
    } /* for (i) */


    /*----------------------------------------------------------------------------
    ; Return status
    ----------------------------------------------------------------------------*/
    return SUCCESS;

} /* huffspec_fxp */
