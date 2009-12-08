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

 Pathname: PVMP4AudioDecoderGetMemRequirements.c

------------------------------------------------------------------------------
 REVISION HISTORY

 Description:  Copied from aac_decode_frame

 Description: Cleaned up.

 Description: (1) use UInt32 to replace size_t type
              (2) memory of tDec_Int_File is splitted into 3 pieces,
                  sizeof(tDec_Int_File) is only part of the total memory
                  required. The additional memory required to decode per
                  channel information is allocated by a DPI call outside this
                  API

 Who:                                   Date: MM/DD/YYYY
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs: None

 Local Stores/Buffers/Pointers Needed: None

 Global Stores/Buffers/Pointers Needed: None

 Outputs:
    size = amount of memory needed to be allocated by the calling
        environment.

 Pointers and Buffers Modified: None

 Local Stores Modified: None

 Global Stores Modified: None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 This function returns the amount of internal memory needed by the library.
 Presently this is a constant value, but could later be more sophisticated
 by taking into account mono or stereo, and whether LTP is to be used.

------------------------------------------------------------------------------
 REQUIREMENTS


------------------------------------------------------------------------------
 REFERENCES


------------------------------------------------------------------------------
 PSEUDO-CODE

    size = sizeof(tDec_Int_File);

 RETURN (size)

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
#include "s_tdec_int_file.h"
#include "pvmp4audiodecoder_api.h" /* Where this function is declared */

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
OSCL_EXPORT_REF UInt32 PVMP4AudioDecoderGetMemRequirements(void)
{
    UInt32 size;

    size = (UInt32) sizeof(tDec_Int_File);

    return (size);

} /* PVMP4AudioDecoderGetMemRequirements() */

