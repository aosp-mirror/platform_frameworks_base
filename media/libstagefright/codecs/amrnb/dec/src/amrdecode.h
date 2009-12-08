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



 Pathname: ./gsm-amr/c/include/amrdecode.h

     Date: 05/23/2001

------------------------------------------------------------------------------
 REVISION HISTORY

 Description: Added BytesUsed table so that the code can allow for padding
              at the end of each frame.

 Description: Removed function prototypes for getbits, putbits,
              put_frame_header_in, and get_frame_header_off. Removed
              basicop_malloc.h and replaced it with typedef.h in the include
              section. Fixed table entries for various SID modes. Removed
              #defines because they are not used by AMRDecode function.
              Removed tables not used by AMRDecode function.

 Description: The data type Speech_Decode_FrameState is now being passed into
              this function as a void pointer rather than a structure of type
              Speech_Decode_FrameState.

 Description: The variable decoder_state was renamed to state_data.

 Description: Updated function prototype and header template.

 Description: Added mode.h and frame_type_3gpp.h to include section, and
              removed sp_dec.h.

 Description: Removed definition of Changed BytesThisFrame[] table. Added
              extern declaration for BytesThisFrame[] table.

 Description: Added #define for WMF and IF2. Updated function prototype.

 Description: Moved input format #defines and BytesThisFrame table to
              dec_input_format_tab.h and dec_input_format_tab.c, respectively.
              Updated function prototype.

 Description: Updated function prototype of AMRDecode due to the removal of
              *prev_mode_ptr. Added extern of If2BytesPerFrame

 Description: Added #defines for WMF, IF2, and ETS input formats.

 Description: Changed WmfBytesPerFrame to WmfDecBytesPerFrame, and
              If2BytesPerFrame to If2DecBytesPerFrame.

 Description: Renamed #defines for input format types to make it unique to the
              decoder.

 Description:  Replaced "int" and/or "char" with OSCL defined types.

 Description: Moved _cplusplus #ifdef after Include section.

 Description:

------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

 This file contains all the constant definitions and prototype definitions
 needed by the norm_s function.

------------------------------------------------------------------------------
*/

/*----------------------------------------------------------------------------
; CONTINUE ONLY IF NOT ALREADY DEFINED
----------------------------------------------------------------------------*/
#ifndef ARMDECODE_H
#define ARMDECODE_H

/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include    "typedef.h"
#include    "mode.h"
#include    "frame_type_3gpp.h"
#include    "pvamrnbdecoder_api.h"

/*--------------------------------------------------------------------------*/
#ifdef __cplusplus
extern "C"
{
#endif

    /*----------------------------------------------------------------------------
    ; MACROS
    ; Define module specific macros here
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; DEFINES
    ; Include all pre-processor statements here.
    ----------------------------------------------------------------------------*/
#define NUM_AMRSID_RXMODE_BITS   3
#define AMRSID_RXMODE_BIT_OFFSET 36
#define AMRSID_RXTYPE_BIT_OFFSET 35

    /*----------------------------------------------------------------------------
    ; EXTERNAL VARIABLES REFERENCES
    ; Declare variables used in this module but defined elsewhere
    ----------------------------------------------------------------------------*/
    extern const Word16 WmfDecBytesPerFrame[];
    extern const Word16 If2DecBytesPerFrame[];

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

    Word16 AMRDecode(
        void *state_data,
        enum Frame_Type_3GPP  frame_type,
        UWord8 *speech_bits_ptr,
        Word16 *raw_pcm_buffer,
        bitstream_format input_format
    );

#ifdef __cplusplus
}
#endif

#endif  /* _AMRDECODE_H_ */
