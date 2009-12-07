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

 Pathname: getfill.c
 Funtions: getfill

------------------------------------------------------------------------------
 REVISION HISTORY

 Description:  Modified from original shareware code

 Description:  Modified to pass variables by reference to eliminate use
               of global variables.

 Description:  1. Used template to re-organize function and filled out
                  Input/Output and Function definition section.
               2. Optimized code.

 Description:  Made the following changes based on review comments.
               1. Exchanging MODIFYING and RETURNING on line 87, 88.
               2. Added MPEG reference.
               3. Changed "fill" to "pass over", "bitstreams are" to
                  "bitstream is" in FUNCTION DESCRIPTION section.
               4. Fixed tabs.

 Description: Replace some instances of getbits to get9_n_lessbits
              when the number of bits read is 9 or less.

 Who:                                   Date:
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    pInputStream = pointer to structure BITS containing input stream
                   information.

 Local Stores/Buffers/Pointers Needed:
    None

 Global Stores/Buffers/Pointers Needed:
    None

 Outputs:
    None

 Pointers and Buffers Modified:
    pInputStream->usedBits is updated to the newly calculated value.

 Local Stores Modified:
    None

 Global Stores Modified:
    None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 This function passes over fill bits in the raw data block to adjust the
 instantaneous bit rate when the bitstream is to be transmitted over a
 constant rate channel.

------------------------------------------------------------------------------
 REQUIREMENTS

 None

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
     Subpart 4      p15     (Table 4.4.11)

------------------------------------------------------------------------------
 PSEUDO-CODE

    CALL getbits(
            LEN_F_CNT,
            pInputStream);
    MODIFYING (pInputStream)
    RETURNING (cnt)

    IF ( cnt == (1<<LEN_F_CNT)-1 )

        CALL getbits(
                LEN_F_ESC,
                pInputStream);
        MODIFYING (pInputStream)
        RETURNING (esc_cnt)

        cnt +=  esc_cnt - 1;

    ENDIF

    pInputStream->usedBits += cnt * LEN_BYTE;

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
#include "s_bits.h"
#include "ibstream.h"
#include "e_rawbitstreamconst.h"
#include "getfill.h"

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
; EXTERNAL GLOBAL STORE/BUFFER/POINTER REFERENCES
; Declare variables used in this module but defined elsewhere
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/

void getfill(BITS *pInputStream)
{
    /*----------------------------------------------------------------------------
    ; Define all local variables
    ----------------------------------------------------------------------------*/
    Int cnt;
    Int esc_cnt;

    /*----------------------------------------------------------------------------
    ; Function body here
    ----------------------------------------------------------------------------*/

    cnt = get9_n_lessbits(
              LEN_F_CNT,
              pInputStream);

    if (cnt == (1 << LEN_F_CNT) - 1)  /* if (cnt == 15) */
    {
        esc_cnt = get9_n_lessbits(
                      LEN_F_ESC,
                      pInputStream);

        cnt +=  esc_cnt - 1;
    }

    /*
     * The following codes are replaced by directly updating usedBits
     * in BITS structure. This will save one call for getbits().
     *
     * for (i=0; i<cnt; i++)
     * { getbits(LEN_BYTE, pInputStream); }
     */

    pInputStream->usedBits += cnt * LEN_BYTE;

    /*----------------------------------------------------------------------------
    ; Return nothing or data or data pointer
    ----------------------------------------------------------------------------*/

} /* getfill */

