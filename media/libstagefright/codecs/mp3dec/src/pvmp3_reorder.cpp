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

   Filename: pvmp3_reorder.cpp


     Date: 09/21/2007

------------------------------------------------------------------------------
 REVISION HISTORY


 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:

    int32 xr[ ],                rescaled data
    struct gr_info_s *gr_info,  granule structure
    mp3Header *info,            mp3 header info
    int32  Scratch_mem[198]     for temporary usage

 Outputs:

    int32 xr[ ],                reordered data

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 If short blocks are used (block_type[gr][ch]=='10'), the rescaled data
 xr[scf_band][window][freq_line] shall be reordered in polyphase subband
 order, xr[subband][window][freq_line], prior to the IMDCT operation.

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
#include "pv_mp3dec_fxd_op.h"
#include "pvmp3_dec_defs.h"
#include "pvmp3_reorder.h"
#include "pvmp3_tables.h"
#include "mp3_mem_funcs.h"

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

void pvmp3_reorder(int32 xr[SUBBANDS_NUMBER*FILTERBANK_BANDS],
                   granuleInfo *gr_info,
                   int32  *used_freq_lines,
                   mp3Header *info,
                   int32  Scratch_mem[198])
{
    int32 sfreq =  info->version_x + (info->version_x << 1);
    sfreq += info->sampling_frequency;

    if (gr_info->window_switching_flag && (gr_info->block_type == 2))
    {
        int32   sfb_lines;
        int32   freq;
        int32   src_line;
        int32   sfb;
        if (gr_info->mixed_block_flag)
        {
            /* REORDERING FOR REST SWITCHED SHORT */
            sfb = 3;  /* no reorder for low 2 subbands */
            src_line = 36;
        }
        else
        {  /* pure short */
            sfb = 0;
            src_line = 0;
        }
        int16 ct = src_line;

        for (; sfb < 13; sfb++)
        {
            if (*used_freq_lines > 3*mp3_sfBandIndex[sfreq].s[sfb+1])
            {
                sfb_lines = mp3_sfBandIndex[sfreq].s[sfb+1]  - mp3_sfBandIndex[sfreq].s[sfb];

                for (freq = 0; freq < 3*sfb_lines; freq += 3)
                {
                    int32 tmp1 = xr[src_line];
                    int32 tmp2 = xr[src_line+(sfb_lines)];
                    int32 tmp3 = xr[src_line+(sfb_lines<<1)];
                    src_line++;
                    Scratch_mem[freq  ] = tmp1;
                    Scratch_mem[freq+1] = tmp2;
                    Scratch_mem[freq+2] = tmp3;
                }
                src_line += (sfb_lines << 1);

                pv_memcpy(&xr[ct], Scratch_mem, sfb_lines*3*sizeof(int32));
                ct += sfb_lines + (sfb_lines << 1);

            }
            else
            {

                sfb_lines = mp3_sfBandIndex[sfreq].s[sfb+1]  - mp3_sfBandIndex[sfreq].s[sfb];

                for (freq = 0; freq < 3*sfb_lines; freq += 3)
                {
                    int32 tmp1 = xr[src_line];
                    int32 tmp2 = xr[src_line+(sfb_lines)];
                    int32 tmp3 = xr[src_line+(sfb_lines<<1)];
                    src_line++;
                    Scratch_mem[freq  ] = tmp1;
                    Scratch_mem[freq+1] = tmp2;
                    Scratch_mem[freq+2] = tmp3;
                }

                pv_memcpy(&xr[ct], Scratch_mem, sfb_lines*3*sizeof(int32));

                *used_freq_lines = mp3_sfBandIndex[sfreq].s[sfb+1] * 3;

                sfb = 13;   /* force out of the for-loop */
            }
        }
    }
}




