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

   Filename: pvmp3_get_main_data_size.cpp

     Date: 09/21/2007

------------------------------------------------------------------------------
 REVISION HISTORY


 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Input
    mp3Header *info,         pointer to mp3 header info structure
    tmp3dec_file  *pVars
                             contains information that needs to persist
                             between calls to this function, or is too big to
                             be placed on the stack, even though the data is
                             only needed during execution of this function

  Returns

    main data frame size

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

    get main data frame size

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

#include "pvmp3_tables.h"
#include "pvmp3_get_main_data_size.h"
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

int32 pvmp3_get_main_data_size(mp3Header *info,
                               tmp3dec_file  *pVars)
{


    int32 numBytes = fxp_mul32_Q28(mp3_bitrate[info->version_x][info->bitrate_index] << 20,
                                   inv_sfreq[info->sampling_frequency]);


    numBytes >>= (20 - info->version_x);

    /*
     *  Remove the size of the side information from the main data total
     */
    if (info->version_x == MPEG_1)
    {
        pVars->predicted_frame_size = numBytes;
        if (info->mode == MPG_MD_MONO)
        {
            numBytes -= 17;
        }
        else
        {
            numBytes -= 32;
        }
    }
    else
    {
        numBytes >>= 1;
        pVars->predicted_frame_size = numBytes;

        if (info->mode == MPG_MD_MONO)
        {
            numBytes -= 9;
        }
        else
        {
            numBytes -= 17;
        }
    }

    if (info->padding)
    {
        numBytes++;
        pVars->predicted_frame_size++;
    }

    if (info->error_protection)
    {
        numBytes -= 6;
    }
    else
    {
        numBytes -= 4;
    }


    if (numBytes < 0)
    {
        numBytes = 0;
    }

    return(numBytes);
}

