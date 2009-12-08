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

 Pathname: ./c/include/dct64.h

------------------------------------------------------------------------------
 REVISION HISTORY

 Who:                                       Date:
 Description:
------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

------------------------------------------------------------------------------
*/

#ifndef DCT64_H
#define DCT64_H


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
    extern const Int32 CosTable_48[48];

    /*----------------------------------------------------------------------------
    ; DEFINES AND SIMPLE TYPEDEF'S
    ----------------------------------------------------------------------------*/

    void pv_split_LC(Int32 *vector,
                     Int32 *temp_o);


#ifdef HQ_SBR

    void dct_64(Int32 vec[], Int32 *scratch_mem);

#endif

    void pv_split(Int32 *temp_o);

    void pv_split_z(Int32 *vector);

    void pv_merge_in_place_N32(Int32 vec[]);

#ifdef __cplusplus
}
#endif

#endif  /* DCT64_H */
