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

 Pathname: pulse_nc.c

------------------------------------------------------------------------------
 REVISION HISTORY

 Description:  Modified from original shareware code

 Description:  Modified to pass variables by reference to eliminate use
               of global variables.

 Description:  Modified to bring code in-line with PV standards.

 Description: Pass in max as input argument.

 Description: Went back to the if-statement to check for max.

 Who:                       Date:
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    coef[]         =  Array of quantized spectral coefficents.
                      (Int [])

    pPulseInfo     =  Pointer to structure which contains noiseless
                      encoding info, includes information about the pulse data,
                      pulse amplitude, etc.
                      (const PulseInfo *)

    pLongFrameInfo =  Pointer to structure that holds information about
                      each group. (long block flag, number of windows,
                      scalefactor bands per group, etc.)

                      Variable is named (pLongFrameInfo) because this function
                      is only used for LONG windows.
                      (FrameInfo *)
    max             = Pointer to the maximum value of coef[]

 Local Stores/Buffers/Pointers Needed:
    None

 Global Stores/Buffers/Pointers Needed:
    None

 Outputs:
    None

 Pointers and Buffers Modified:
    coef[]  =  coefficient contents are modified by the encoded pulse

 Local Stores Modified:

 Global Stores Modified:

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 This function adds pulses to defined ranges of coefficients in the window,
 for the case of LONG windows.  The pulses are unsigned, so
 negative coefficients subtract the pulse, and positive coefficients add it.
 (The ampltiude of the coefficient is always increased by the pulse)

 A maximum of 4 coefficients may be modified by a pulse, and these
 coefficients must all occur in the same scalefactor band.

 The number of pulse-encoded coefficients to be processed by this function
 is communicated to this function via pPulseInfo->number_pulse

 This value is equal to the actual number of pulses - 1.
 (e.g if pPulseInfo->number_pulse == 0, one pulse is assumed)
 This function must not be called if no pulse encoded data exists.
 The function assumes that at least one pulse exists.
------------------------------------------------------------------------------
 REQUIREMENTS

 This module shall correctly add transmitted pulse(s) to the correct
 coefficients in a LONG window.

------------------------------------------------------------------------------
 REFERENCES
 (1) ISO/IEC 14496-3:1999(E)
     Part 3
        Subpart 4.6.3.3 Decoding Process

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

    index = pLongFrameInfo->win_sfb_top[0][pPulseInfo->pulse_start_sfb];

    pPulseOffset = &(pPulseInfo->pulse_offset[0]);

    pPulseAmp    = &(pPulseInfo->pulse_amp[0]);

    pCoef        = &(Coef[index]);

    FOR (index = pPulseInfo->number_pulse; index >= 0; index--)

        pCoef   = pCoef + *(pPulseOffset);
        pPulseOffset = pPulseOffset + 1;

        IF (*pCoef > 0)
            *(pCoef) = *(pCoef) + *(pPulseAmp);
            pPulseAmp     = pPulseAmp + 1;
        ELSE
            *(pCoef) = *(pCoef) - *(pPulseAmp);
            pPulseAmp     = pPulseAmp + 1;
        ENDIF

    ENDFOR

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
#include "s_frameinfo.h"
#include "s_pulseinfo.h"
#include "pulse_nc.h"

/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/

/*---------------------------------------------------------------------------
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

void pulse_nc(
    Int16      coef[],
    const PulseInfo  *pPulseInfo,
    const FrameInfo  *pLongFrameInfo,
    Int      *max)
{
    Int index;

    Int16 *pCoef;
    Int temp;

    const Int *pPulseOffset;
    const Int *pPulseAmp;

    /*--- Find the scalefactor band where pulse-encoded data starts ---*/

    if (pPulseInfo->pulse_start_sfb > 0)
    {
        index = pLongFrameInfo->win_sfb_top[0][pPulseInfo->pulse_start_sfb - 1];
    }
    else
    {
        index = 0;
    }

    /*-------------------------------------------------------------------------
      Each pulse index is stored as an offset from the previous pulse

      Example - here we have a sfb that is 20 coefficients in length:

      [0][1][2][3][4][5][6][7][8][9][10][11][12][13][14][15][16][17][18][19]
      [ ][ ][ ][ ][ ][P][P][ ][ ][ ][  ][  ][  ][  ][  ][ P][  ][  ][  ][ P]

      The array pointed to by pPulseOffset == [5][1][9][4]

      pPulseAmp is of the same length as pPulseOffset, and contains
      an individual pulse amplitude for each coefficient.
    --------------------------------------------------------------------------*/

    pCoef        = &(coef[index]);

    pPulseOffset = &(pPulseInfo->pulse_offset[0]);

    pPulseAmp    = &(pPulseInfo->pulse_amp[0]);

    for (index = pPulseInfo->number_pulse; index > 0; index--)
    {
        pCoef  += *pPulseOffset++;

        temp = *pCoef;

        if (temp > 0)
        {
            temp += *(pPulseAmp++);
            *pCoef = (Int16)temp;
            if (temp > *max)
            {
                *max = temp;
            }
        }
        else
        {
            temp -= *(pPulseAmp++);
            *pCoef = (Int16)temp;
            if (-temp > *max)
            {
                *max = -temp;
            }
        }

    } /* for() */

    return;

} /* pulse_nc */
