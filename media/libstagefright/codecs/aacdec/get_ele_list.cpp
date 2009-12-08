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

 Pathname: get_ele_list.c

------------------------------------------------------------------------------
 REVISION HISTORY

 Description:  Modified from original shareware code

 Description:  Modified to pass variables by reference to eliminate use
               of global variables.

 Description: Change to PacketVideo standard, rename variables.

 Description: Add own header file, make pInputStream second param for speed.

 Description: Changes per code review:
              1) Include header file
              2) Convert to count down
              3) Add return (not in review)

 Description:
 (1) Updated copyright header
 (2) Replaced include of "interface.h" with "e_ProgConfig.h"

 Description: Replace some instances of getbits to get9_n_lessbits
              when the number of bits read is 9 or less and get1bits
              when only 1 bit is read.

 Who:                                 Date:
 Description:
------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    pElementList = pointer to an EleList structure - only the field num_ele
                   needs to be set. Data type pointer to EleList.

   pInputStream = pointer to a BITS structure, used by the function getbits
                   to provide data. Data type pointer to BITS

    enableCPE = boolean value indicating the area to be read contains
                a channel pair element field. Data type Bool


 Local Stores/Buffers/Pointers Needed: None

 Global Stores/Buffers/Pointers Needed: None

 Outputs: None

 Pointers and Buffers Modified:
    pElementList contents are updated with information pertaining to channel
        configuration.

    pInputBuffer contents are updated to the next location to be read from
        the input stream.

 Local Stores Modified: None

 Global Stores Modified: None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 This function is called several times by get_prog_config() to read in part of
 the program configuration data related to channel setup.

------------------------------------------------------------------------------
 REQUIREMENTS

 This function shall not have static or global variables.

------------------------------------------------------------------------------
 REFERENCES

 (1) ISO/IEC 13818-7:1997 Titled "Information technology - Generic coding
   of moving pictures and associated audio information - Part 7: Advanced
   Audio Coding (AAC)", Table 6.21 - Syntax of program_config_element(),
   page 16, and section 8.5 "Program Config Element (PCE)", page 30.

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

    elementCount = pElementList->num_ele;

    FOR (index = 0; index < elementCount; index++)
        IF (enableCPE != FALSE) THEN
            pElementList->ele_is_cpe[index] =
                getbits(LEN_ELE_IS_CPE, pInputStream);
        ELSE
            pElementList->ele_is_cpe[index] = 0;
        END IF

        pElementList->ele_tag[index] = getbits(LEN_TAG, pInputStream);

    END FOR

    RETURNS nothing

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
#include "s_elelist.h"
#include "s_bits.h"
#include "e_progconfigconst.h"
#include "ibstream.h"
#include "get_ele_list.h"

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
void get_ele_list(
    EleList     *pElementList,
    BITS        *pInputStream,
    const Bool   enableCPE)
{
    Int index;
    Int *pEleIsCPE;
    Int *pEleTag;

    pEleIsCPE = &pElementList->ele_is_cpe[0];
    pEleTag   = &pElementList->ele_tag[0];

    for (index = pElementList->num_ele; index > 0; index--)
    {
        if (enableCPE != FALSE)
        {
            *pEleIsCPE++ = get1bits(/*LEN_ELE_IS_CPE, */pInputStream);
        }
        else
        {
            *pEleIsCPE++ = FALSE;
        }

        *pEleTag++ = get9_n_lessbits(LEN_TAG, pInputStream);

    } /* end for (index) */

    return;

} /* end get_ele_list */

