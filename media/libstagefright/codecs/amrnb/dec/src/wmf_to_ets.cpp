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
/****************************************************************************************
Portions of this file are derived from the following 3GPP standard:

    3GPP TS 26.073
    ANSI-C code for the Adaptive Multi-Rate (AMR) speech codec
    Available from http://www.3gpp.org

(C) 2004, 3GPP Organizational Partners (ARIB, ATIS, CCSA, ETSI, TTA, TTC)
Permission to distribute, modify and use this file under the standard license
terms listed above has been obtained from the copyright holder.
****************************************************************************************/
/*
------------------------------------------------------------------------------



 Pathname: ./audio/gsm-amr/c/src/wmf_to_ets.c
 Funtions: wmf_to_ets

     Date: 01/21/2002

------------------------------------------------------------------------------
 REVISION HISTORY

 Description: Changing mode to frame_type_3gpp for DTX support. Modifying for
              loops for optimized code. Updating as per review comments.

 Description: Changed MRDTX to AMR_SID in the code and added bitreorder_tab.h
              in the Include section.

 Description:

------------------------------------------------------------------------------
*/

/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include "frame_type_3gpp.h"
#include "wmf_to_ets.h"
#include "typedef.h"
#include "bitreorder_tab.h"
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



/*
------------------------------------------------------------------------------
 FUNCTION NAME: wmf_to_ets
------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:
    frame_type_3gpp = decoder speech bit rate (enum Frame_Type_3GPP)
    wmf_input_ptr   = pointer to input encoded speech bits in WMF (non-IF2) format
                     (Word8)
    ets_output_ptr  = pointer to output encoded speech bits in ETS format (Word16)

 Outputs:
    ets_output_ptr  = pointer to encoded speech bits in the ETS format (Word16)

 Returns:
    None

 Global Variables Used:
    None

 Local Variables Needed:
    None

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

 This function performs a transformation on the data buffers. It converts the
 data format from WMF (non-IF2) (Wireless Multi-media Forum) to ETS (European
 Telecommunication Standard). WMF format has the encoded speech bits byte
 aligned with MSB to LSB going left to right. ETS format has the encoded speech
 bits each separate with only one bit stored in each word.

------------------------------------------------------------------------------
 REQUIREMENTS

 None

------------------------------------------------------------------------------
 REFERENCES

AMR Speech Codec Frame Structure", 3GPP TS 26.101 version 4.1.0 Release 4, June 2001

------------------------------------------------------------------------------
 PSEUDO-CODE



------------------------------------------------------------------------------
 RESOURCES USED [optional]

 When the code is written for a specific target processor the
 the resources used should be documented below.

 HEAP MEMORY USED: x bytes

 STACK MEMORY USED: x bytes

 CLOCK CYCLES: (cycle count equation for this function) + (variable
                used to represent cycle count for each subroutine
                called)
     where: (cycle count variable) = cycle count for [subroutine
                                     name]

------------------------------------------------------------------------------
 CAUTION [optional]
 [State any special notes, constraints or cautions for users of this function]

------------------------------------------------------------------------------
*/

void wmf_to_ets(
    enum Frame_Type_3GPP frame_type_3gpp,
    UWord8   *wmf_input_ptr,
    Word16   *ets_output_ptr)
{

    Word16 i;

    /*
     * The following section of code accesses bits in the WMF method of
     * bit ordering. Each bit is given its own location in the buffer pointed
     * to by ets_output_ptr. If the frame_type_3gpp is less than MRDTX then
     * the elements are reordered within the buffer pointed to by ets_output_ptr.
     */

    if (frame_type_3gpp < AMR_SID)
    {
        /* The table numOfBits[] can be found in bitreorder.c. */
        for (i = numOfBits[frame_type_3gpp] - 1; i >= 0; i--)
        {
            /* The table reorderBits[][] can be found in bitreorder.c. */
            ets_output_ptr[reorderBits[frame_type_3gpp][i]] =
                (wmf_input_ptr[i>>3] >> ((~i) & 0x7)) & 0x01;
        }
    }
    else
    {
        /* The table numOfBits[] can be found in bitreorder.c. */
        for (i = numOfBits[frame_type_3gpp] - 1; i >= 0; i--)
        {
            ets_output_ptr[i] = (wmf_input_ptr[i>>3] >> ((~i) & 0x7)) & 0x01;
        }
    }

    return;
}
