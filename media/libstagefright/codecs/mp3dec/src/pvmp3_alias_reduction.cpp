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

   Filename: pvmp3_alias_reduction.cpp

     Date: 09/21/2007

------------------------------------------------------------------------------
 REVISION HISTORY


 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

Input
    int32 *input_buffer,          Ptr to fequency lines of current channel
    struct gr_info_s *gr_info,    structure with granuke information for the
                                  input
    mp3Header *info               mp3 header information

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    Alias Reduction



    Alias reduction before processing by the IMDCT

                   Csi  +
     >---------0---------0-------->
                \       / -
             Cai \     /
                  \   /
                   \ /
                    \
                  /  \
             Cai /    \
               /       \  +
     >--------0---------0---------->
                  Csi  +

      Aliasing Butterfly
      Alias reduction is not applied to short blocks

------------------------------------------------------------------------------
 REQUIREMENTS


------------------------------------------------------------------------------
 REFERENCES

 [1] ISO MPEG Audio Subgroup Software Simulation Group (1996)
     ISO 13818-3 MPEG-2 Audio Decoder - Lower Sampling Frequency Extension

------------------------------------------------------------------------------
 PSEUDO-CODE
                1                                ci
  csi = ----------------           csi = ----------------
        sqrt( 1 + (ci^2))                sqrt( 1 + (ci^2))


  ci = -0.6, -0.535, -0.33, -0.185, -0.095, -0.041, -0.0142, -0.0037

 ------------------------------------------------------------------------------
*/


/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/

#include "pvmp3_alias_reduction.h"
#include "pv_mp3dec_fxd_op.h"


/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here. Include conditional
; compile variables also.
----------------------------------------------------------------------------*/
#define NUM_BUTTERFLIES 8

#define Q31_fmt(a)    (int32(double(0x7FFFFFFF)*a))

/*----------------------------------------------------------------------------
; LOCAL FUNCTION DEFINITIONS
; Function Prototype declaration
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; LOCAL STORE/BUFFER/POINTER DEFINITIONS
; Variable declaration - defined here and used outside this module
----------------------------------------------------------------------------*/
const int32 c_signal [ NUM_BUTTERFLIES ] =
{

    Q31_fmt(0.85749292571254f), Q31_fmt(0.88174199731771f),
    Q31_fmt(0.94962864910273f), Q31_fmt(0.98331459249179f),
    Q31_fmt(0.99551781606759f), Q31_fmt(0.99916055817815f),
    Q31_fmt(0.99989919524445f), Q31_fmt(0.99999315507028f)

};


const int32 c_alias [ NUM_BUTTERFLIES ] =
{

    Q31_fmt(-0.51449575542753f), Q31_fmt(-0.47173196856497f),
    Q31_fmt(-0.31337745420390f), Q31_fmt(-0.18191319961098f),
    Q31_fmt(-0.09457419252642f), Q31_fmt(-0.04096558288530f),
    Q31_fmt(-0.01419856857247f), Q31_fmt(-0.00369997467376f)
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

void pvmp3_alias_reduction(int32 *input_buffer,         /* Ptr to spec values of current channel */
                           granuleInfo *gr_info,
                           int32  *used_freq_lines,
                           mp3Header *info)
{
    int32 *ptr1;
    int32 *ptr2;
    int32 *ptr3;
    int32 *ptr4;
    const int32 *ptr_csi;
    const int32 *ptr_csa;
    int32  sblim;

    int32 i, j;

    *used_freq_lines = fxp_mul32_Q32(*used_freq_lines << 16, (int32)(0x7FFFFFFF / (float)18 - 1.0f)) >> 15;


    if (gr_info->window_switching_flag &&  gr_info->block_type == 2)
    {
        if (gr_info->mixed_block_flag)
        {
            sblim = ((info->version_x == MPEG_2_5) && (info->sampling_frequency == 2)) ? 3 : 1;
        }
        else
        {
            return;  /* illegal parameter */
        }
    }
    else
    {
        sblim = *used_freq_lines + 1;

        if (sblim > SUBBANDS_NUMBER - 1)
        {
            sblim = SUBBANDS_NUMBER - 1;  /* default */
        }

    }


    ptr3 = &input_buffer[17];
    ptr4 = &input_buffer[18];
    ptr_csi = c_signal;
    ptr_csa = c_alias;

    /*   NUM_BUTTERFLIES (=8) butterflies between each pair of sub-bands*/

    for (i = NUM_BUTTERFLIES >> 1; i != 0; i--)
    {
        int32 csi1  = *ptr_csi++;
        int32 csi2  = *ptr_csi++;
        int32 csa1  = *ptr_csa++;
        int32 csa2  = *ptr_csa++;

        ptr1 = ptr3;
        ptr3 -= 2;
        ptr2 = ptr4;
        ptr4 += 2;

        /*
         *  "sblim"  alias-reduction operations between each
         *  pair of sub-bands
         */

        for (j = sblim >> 1; j != 0; j--)
        {
            int32 y = *ptr2;
            int32 x = *ptr1 << 1;
            *ptr1--  = fxp_msb32_Q32(fxp_mul32_Q32(x, csi1), y << 1, csa1);
            *ptr2++  = fxp_mac32_Q32(fxp_mul32_Q32(y << 1, csi1), x, csa1);
            y = *ptr2;
            x = *ptr1 << 1;
            *ptr1    = fxp_msb32_Q32(fxp_mul32_Q32(x, csi2), y << 1, csa2);
            *ptr2    = fxp_mac32_Q32(fxp_mul32_Q32(y << 1, csi2), x, csa2);
            ptr1 += 19;
            ptr2 += 17;
            y = *ptr2;
            x = *ptr1 << 1;
            *ptr1--  = fxp_msb32_Q32(fxp_mul32_Q32(x, csi1), y << 1, csa1);
            *ptr2++  = fxp_mac32_Q32(fxp_mul32_Q32(y << 1, csi1), x, csa1);
            y = *ptr2;
            x = *ptr1 << 1;
            *ptr1    = fxp_msb32_Q32(fxp_mul32_Q32(x, csi2), y << 1, csa2);
            *ptr2    = fxp_mac32_Q32(fxp_mul32_Q32(y << 1, csi2), x, csa2);
            ptr1 += 19;
            ptr2 += 17;

        }

        if (sblim & 1)
        {
            int32 x = *ptr1 << 1;
            int32 y = *ptr2;
            *ptr1--  = fxp_msb32_Q32(fxp_mul32_Q32(x, csi1), y << 1, csa1);
            *ptr2++  = fxp_mac32_Q32(fxp_mul32_Q32(y << 1, csi1), x, csa1);

            x = *ptr1 << 1;
            y = *ptr2;
            *ptr1    = fxp_msb32_Q32(fxp_mul32_Q32(x, csi2), y << 1, csa2);
            *ptr2    = fxp_mac32_Q32(fxp_mul32_Q32(y << 1, csi2), x, csa2);
        }
    }

}
