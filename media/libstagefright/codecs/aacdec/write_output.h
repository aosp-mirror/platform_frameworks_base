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

 Pathname: write_output.h

------------------------------------------------------------------------------
 REVISION HISTORY

 Description: Change function prototype.

 Description: Remove CR/LF from unknown editor

 Who:                                       Date:
 Description:

------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

 Header file for the declaration of the function write_output()

------------------------------------------------------------------------------
*/

/*----------------------------------------------------------------------------
; CONTINUE ONLY IF NOT ALREADY DEFINED
----------------------------------------------------------------------------*/
#ifndef WRITE_OUTPUT_H
#define WRITE_OUTPUT_H

#include "pv_audio_type_defs.h"

#ifdef __cplusplus
extern "C"
{
#endif

    /*----------------------------------------------------------------------------
    ; INCLUDES
    ----------------------------------------------------------------------------*/
#include "pvmp4audiodecoder_api.h"

    /*----------------------------------------------------------------------------
    ; MACROS
    ; Define module specific macros here
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; DEFINES
    ; Include all pre-processor statements here.
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; EXTERNAL VARIABLES REFERENCES
    ; Declare variables used in this module but defined elsewhere
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; SIMPLE TYPEDEF'S
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; ENUMERATED TYPEDEF'S
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; STRUCTURES TYPEDEF'S
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; GLOBAL FUNCTION DEFINITIONS
    ; Function Prototype declaration
    ----------------------------------------------------------------------------*/

#ifndef AAC_PLUS

    Int write_output(
        const Int16   sourceLeft[],
        const Int16   sourceRight[],
        Int16   outputBuffer[],
        const Int     sourcePointsPerChannel,
        const Int     sourceChannels,
        const Int     requestedChannels,
        const tPVMP4AudioDecoderOutputFormat  outputFormat);

#else

    Int write_output(
        const Int16   sourceLeft[],
        const Int16   sourceRight[],
        Int16   outputBuffer[],
        const Int     sourcePointsPerChannel,
        const Int     sourceChannels,
        const Int     requestedChannels,
#ifdef PARAMETRICSTEREO
        Int32 sbrEnablePS,
#endif
        const tPVMP4AudioDecoderOutputFormat  outputFormat);

#ifdef AAC_32_BIT_INTERFACE

    Int write_output_1(
        const Int32   sourceLeft[],
        const Int32   sourceRight[],
        Int16   outputBuffer[],
        const Int     sourcePointsPerChannel,
        const Int     sourceChannels,
        const Int     requestedChannels,
        const tPVMP4AudioDecoderOutputFormat  outputFormat);
#endif

#endif

    /*----------------------------------------------------------------------------
    ; END
    ----------------------------------------------------------------------------*/
#ifdef __cplusplus
}
#endif

#endif /* WRITE_OUTPUT_H */


