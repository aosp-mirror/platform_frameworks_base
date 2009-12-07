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

   Filename: pvmp3_poly_phase_synthesis.cpp


     Date: 09/21/2007

------------------------------------------------------------------------------
 REVISION HISTORY


 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

  Input
    tmp3dec_chan   *pChVars,          decoder state structure per channel
    int32          numChannels,       number of channels
    e_equalization equalizerType,     equalization mode
    int16          *outPcm            pointer to the PCM output data

  Output
    int16          *outPcm            pointer to the PCM output data

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    polyphase synthesis
    Each time the subband samples for all 32 polyphase subbands of one
    channel have been calculated, they can be applied to the synthesis
    subband filter and 32 consecutive audio samples can be calculated

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

#include "pvmp3_poly_phase_synthesis.h"
#include "pvmp3_polyphase_filter_window.h"
#include "pv_mp3dec_fxd_op.h"
#include "pvmp3_dec_defs.h"
#include "pvmp3_dct_16.h"
#include "pvmp3_equalizer.h"
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

void pvmp3_poly_phase_synthesis(tmp3dec_chan   *pChVars,
                                int32          numChannels,
                                e_equalization equalizerType,
                                int16          *outPcm)
{
    /*
     *  Equalizer
     */
    pvmp3_equalizer(pChVars->circ_buffer,
                    equalizerType,
                    pChVars->work_buf_int32);


    int16 * ptr_out = outPcm;


    for (int32  band = 0; band < FILTERBANK_BANDS; band += 2)
    {
        int32 *inData  = &pChVars->circ_buffer[544 - (band<<5)];

        /*
         *   DCT 32
         */

        pvmp3_split(&inData[16]);

        pvmp3_dct_16(&inData[16], 0);
        pvmp3_dct_16(inData, 1);     // Even terms

        pvmp3_merge_in_place_N32(inData);

        pvmp3_polyphase_filter_window(inData,
                                      ptr_out,
                                      numChannels);

        inData  -= SUBBANDS_NUMBER;

        /*
         *   DCT 32
         */

        pvmp3_split(&inData[16]);

        pvmp3_dct_16(&inData[16], 0);
        pvmp3_dct_16(inData, 1);     // Even terms

        pvmp3_merge_in_place_N32(inData);

        pvmp3_polyphase_filter_window(inData,
                                      ptr_out + (numChannels << 5),
                                      numChannels);

        ptr_out += (numChannels << 6);

        inData  -= SUBBANDS_NUMBER;

    }/* end band loop */

    pv_memmove(&pChVars->circ_buffer[576],
               pChVars->circ_buffer,
               480*sizeof(*pChVars->circ_buffer));

}



