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

 Pathname: get_pulse_data.c

------------------------------------------------------------------------------
 REVISION HISTORY

 Description:  Modified from original shareware code

 Description:  Modified to pass variables by reference to eliminate use
               of global variables.

 Description: Put into PV format

 Description: 1) Change loop to use pointers.
              2) Rename to from get_nec_nc to get_pulse_data

 Description: Changes per code review
              1) Fix pathname
              2) Read in two fields to save call to getbits
              3) Change how pPulseInfo->number_pulse is stored.

 Description: Placed typecast to Int in places where UInt->Int

 Description: Replace some instances of getbits to get9_n_lessbits
              when the number of bits read is 9.

 Who:                                  Date:
 Description:
------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
     pInputStream = pointer to a BITS structure, used by the function getbits
                   to provide data. Data type pointer to BITS structure

     pPulseInfo   = pointer to pulse data structure to be filled with data
                    concerning pulses in the frequency domain.
                    Data type pointer to PulseInfo

 Local Stores/Buffers/Pointers Needed: None

 Global Stores/Buffers/Pointers Needed: None

 Outputs:
     status       = return value, zero signifies success, non-zero otherwise.
                    Presently this function only returns a success, error
                    checking may be added later.
                    Data type Int.

 Pointers and Buffers Modified:

    pPulseInfo contents are updated with pulse information. Specifically,
    pPulseInfo->number_pulse with the number of pulses found, and
    pPulseInfo->pulse_start_sfb is set to the first scale factor band.
    Then pPulseInfo->pulse_offset and pPulseInfo->pulse_amp are filled
    with data. For these array, only the number of pulses defined will be
    set, those values beyond the number of pulses will retain their previous
    value and should not be read from.
    Note: The value in pPulseInfo->number_pulse is different by a value of
          one from the original ISO code.

    pInputBuffer contents are updated to the next location to be read from
        the input stream.

 Local Stores Modified: None

 Global Stores Modified: None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 This function fills in the pulse data structure with information to be used
 later for restoring pulses in the spectrum.

------------------------------------------------------------------------------
 REQUIREMENTS

 This function shall not use global or static variables.

------------------------------------------------------------------------------
 REFERENCES

  (1) ISO/IEC 13818-7:1997 Titled "Information technology - Generic coding
      of moving pictures and associated audio information - Part 7: Advanced
      Audio Coding (AAC)", Table 6.17 - Syntax of pulse_data(),
      page 15, and section 9.3 "Decoding process", starting on page 41.


 (2) MPEG-2 NBC Audio Decoder
   "This software module was originally developed by AT&T, Dolby
   Laboratories, Fraunhofer Gesellschaft IIS in the course of development
   of the MPEG-2 NBC/MPEG-4 Audio standard ISO/IEC 13818-7, 14496-1,2 and
   3. This software module is an implementation of a part of one or more
   MPEG-2 NBC/MPEG-4 Audio tools as specified by the MPEG-2 NBC/MPEG-4
   Audio standard. ISO/IEC gives users of the MPEG-2 NBC/MPEG-4 Audio
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

------------------------------------------------------------------------------
 PSEUDO-CODE

    status = SUCCESS;

    CALL getbits(neededBits = LEN_PULSE_NPULSE + LEN_PULSE_ST_SFB,
                 pInputStream = pInputStream)
    MODIFYING(*pInputStream)
    RETURNING(temp)

    pPulseInfo->number_pulse = 1 + (temp >> LEN_PULSE_ST_SFB);
    pPulseInfo->pulse_start_sfb = temp & ((1 << LEN_PULSE_ST_SFB) - 1);

    pPulseOffset = &pPulseInfo->pulse_offset[0];
    pPulseAmp    = &pPulseInfo->pulse_amp[0];

    FOR (i = PulseInfo->number_pulse; i > 0; i--)
        CALL getbits(neededBits = LEN_PULSE_POFF + LEN_PULSE_PAMP,
                     pInputStream = pInputStream)
        MODIFYING(*pInputStream)
        RETURNING(temp)

        *pPulseOffset++ = temp >> LEN_PULSE_PAMP;
        *pPulseAmp++    = temp & ((1 << LEN_PULSE_PAMP) - 1);
    END FOR

    MODIFYING (*pInputStream)
    MODIFYING (*pPulseInfo)

    RETURN status

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
#include "ibstream.h"
#include "s_pulseinfo.h"
#include "s_bits.h"
#include "e_rawbitstreamconst.h"
#include "get_pulse_data.h"


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
; LOCAL VARIABLE DEFINITIONS
; Variable declaration - defined here and used outside this module
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; EXTERNAL FUNCTION REFERENCES
; Declare functions defined elsewhere and referenced in this module
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; EXTERNAL VARIABLES REFERENCES
; Declare variables used in this module but defined elsewhere
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/
Int get_pulse_data(
    PulseInfo   *pPulseInfo,
    BITS        *pInputStream)
{
    Int   i;
    Int  *pPulseOffset;
    Int  *pPulseAmp;
    Int   status = SUCCESS;
    UInt  temp;

    /*
     * Read in both field fields at once to save cycles. These are the
     * original lines of code:
     * pPulseInfo->number_pulse = getbits(LEN_PULSE_NPULSE, pInputStream);
     * pPulseInfo->pulse_start_sfb = getbits(LEN_PULSE_ST_SFB, pInputStream);
     */

    temp =
        get9_n_lessbits(
            LEN_PULSE_NPULSE + LEN_PULSE_ST_SFB,
            pInputStream);

    pPulseInfo->number_pulse = (Int)(1 + (temp >> LEN_PULSE_ST_SFB));
    pPulseInfo->pulse_start_sfb = (Int)(temp & ((1 << LEN_PULSE_ST_SFB) - 1));

    pPulseOffset = &pPulseInfo->pulse_offset[0];
    pPulseAmp    = &pPulseInfo->pulse_amp[0];

    /*
     * This loop needs to count one more than the number read in from
     * the bitstream - look at reference [1].
     */

    for (i = pPulseInfo->number_pulse; i > 0; i--)
    {
        /*
         * Read in both fields. Original lines:
         *  *pPulseOffset++ = getbits(LEN_PULSE_POFF, pInputStream);
         *  *pPulseAmp++    = getbits(LEN_PULSE_PAMP, pInputStream);
         */

        temp =
            get9_n_lessbits(
                LEN_PULSE_POFF + LEN_PULSE_PAMP,
                pInputStream);

        *pPulseOffset++ = (Int)(temp >> LEN_PULSE_PAMP);

        *pPulseAmp++    = (Int)(temp & ((1 << LEN_PULSE_PAMP) - 1));
    }

    return (status);
}

