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

 Pathname: imdct_fxp.h

------------------------------------------------------------------------------
 REVISION HISTORY

 Description: This extern had the incorrect length of the arrays.  The true
 lengths are 128 and 1024, not 64 and 512.

 Description:  Modified interface so a vector with extended precision is
               returned, this is a 32 bit vector whose MSB 16 bits will be
               extracted later.  Added copyright notice.

 Description:   Modified function interface to accomodate the normalization
                that now is done in this function.

 Who:                       Date:
 Description:

------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

 Header file for function imdct_fxp()


------------------------------------------------------------------------------
*/

/*----------------------------------------------------------------------------
; CONTINUE ONLY IF NOT ALREADY DEFINED
----------------------------------------------------------------------------*/
#ifndef IMDCT_FXP_H
#define IMDCT_FXP_H

#ifdef __cplusplus
extern "C"
{
#endif

    /*----------------------------------------------------------------------------
    ; INCLUDES
    ----------------------------------------------------------------------------*/
#include "pv_audio_type_defs.h"

    /*----------------------------------------------------------------------------
    ; MACROS
    ; Define module specific macros here
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; DEFINES
    ; Include all pre-processor statements here.
    ----------------------------------------------------------------------------*/

#define     LONG_WINDOW_TYPE  2048
#define     SHORT_WINDOW_TYPE  256

#define     ALL_ZEROS_BUFFER       31

    /*----------------------------------------------------------------------------
    ; EXTERNAL VARIABLES REFERENCES
    ; Declare variables used in this module but defined elsewhere
    ----------------------------------------------------------------------------*/

    extern const Int32 exp_rotation_N_256[64];
    extern const Int32 exp_rotation_N_2048[512];
    /*
    extern const Int exp_rotation_N_256[128];
    extern const Int exp_rotation_N_2048[1024];
    */

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
    Int imdct_fxp(
        Int32   data_quant[],
        Int32   freq_2_time_buffer[],
        const   Int     n,
        Int     Q_format,
        Int32   max
    );


#ifdef __cplusplus
}
#endif

/*----------------------------------------------------------------------------
; END
----------------------------------------------------------------------------*/
#endif  /* IMDCT_FXP_H */
