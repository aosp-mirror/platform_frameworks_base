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
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    [input_variable_name] = [description of the input to module, its type
                 definition, and length (when applicable)]

 Local Stores/Buffers/Pointers Needed:
    [local_store_name] = [description of the local store, its type
                  definition, and length (when applicable)]
    [local_buffer_name] = [description of the local buffer, its type
                   definition, and length (when applicable)]
    [local_ptr_name] = [description of the local pointer, its type
                definition, and length (when applicable)]

 Global Stores/Buffers/Pointers Needed:
    [global_store_name] = [description of the global store, its type
                   definition, and length (when applicable)]
    [global_buffer_name] = [description of the global buffer, its type
                definition, and length (when applicable)]
    [global_ptr_name] = [description of the global pointer, its type
                 definition, and length (when applicable)]

 Outputs:
    [return_variable_name] = [description of data/pointer returned
                  by module, its type definition, and length
                  (when applicable)]

 Pointers and Buffers Modified:
    [variable_bfr_ptr] points to the [describe where the
      variable_bfr_ptr points to, its type definition, and length
      (when applicable)]
    [variable_bfr] contents are [describe the new contents of
      variable_bfr]

 Local Stores Modified:
    [local_store_name] = [describe new contents, its type
                  definition, and length (when applicable)]

 Global Stores Modified:
    [global_store_name] = [describe new contents, its type
                   definition, and length (when applicable)]

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 This module calculates the DC quantization scale according
 to the incoming Q and type.

------------------------------------------------------------------------------
 REQUIREMENTS

 [List requirements to be satisfied by this module.]

------------------------------------------------------------------------------
 REFERENCES

 [List all references used in designing this module.]

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
#include    "mp4dec_lib.h"
#include    "vlc_decode.h"
#include    "bitstream.h"
#include    "zigzag.h"

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
int cal_dc_scaler(
    int QP,
    int type)
{

    /*----------------------------------------------------------------------------
    ; Define all local variables
    ----------------------------------------------------------------------------*/
    int dc_scaler;

    /*----------------------------------------------------------------------------
    ; Function body here
    ----------------------------------------------------------------------------*/
    if (type == LUMINANCE_DC_TYPE)
    {
        if (QP > 0 && QP < 5) dc_scaler = 8;
        else if (QP > 4 && QP < 9) dc_scaler = 2 * QP;
        else if (QP > 8 && QP < 25) dc_scaler = QP + 8;
        else dc_scaler = 2 * QP - 16;
    }
    else /* if (type == CHROMINANCE_DC_TYPE), there is no other types.  */
    {
        if (QP > 0 && QP < 5) dc_scaler = 8;
        else if (QP > 4 && QP < 25) dc_scaler = (QP + 13) >> 1;
        else dc_scaler = QP - 6;
    }

    /*----------------------------------------------------------------------------
    ; Return nothing or data or data pointer
    ----------------------------------------------------------------------------*/
    return dc_scaler;
}

