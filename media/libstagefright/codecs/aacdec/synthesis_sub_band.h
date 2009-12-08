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

 Pathname: synthesis_sub_band.h

------------------------------------------------------------------------------
 REVISION HISTORY

 Who:                                       Date:
 Description:
------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

------------------------------------------------------------------------------
*/

#ifndef SYNTHESIS_SUB_BAND_H
#define SYNTHESIS_SUB_BAND_H

/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/

#include "pv_audio_type_defs.h"

/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/
#ifdef __cplusplus
extern "C"
{
#endif


    /*----------------------------------------------------------------------------
    ; EXTERNAL VARIABLES REFERENCES
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; DEFINES AND SIMPLE TYPEDEF'S
    ----------------------------------------------------------------------------*/


    void synthesis_sub_band_LC(Int32 Sr[], Int16 data[]);
    void synthesis_sub_band_LC_down_sampled(Int32 Sr[], Int16 data[]);


#ifdef HQ_SBR

    void synthesis_sub_band(Int32 Sr[], Int32 Si[], Int16 data[]);
    void synthesis_sub_band_down_sampled(Int32 Sr[], Int32 Si[], Int16 data[]);

#endif


#ifdef __cplusplus
}
#endif

#endif  /* SYNTHESIS_SUB_BAND_H */

