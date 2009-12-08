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

 Pathname: pv_sqrt.h

------------------------------------------------------------------------------
 REVISION HISTORY

 Who:                                       Date:
 Description:
------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

------------------------------------------------------------------------------
*/

#ifndef PV_SQRT_H
#define PV_SQRT_H

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
    ; EXTERNAL VARIABLES REFERENCES
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; DEFINES AND SIMPLE TYPEDEF'S
    ----------------------------------------------------------------------------*/

    struct intg_sqrt
    {
        Int32 root;
        Int32 shift_factor;
    };
    typedef struct intg_sqrt Root_sq;

    void pv_sqrt(Int32 man, Int32 exp, Root_sq *result, Int32 *sqrt_cache);


#ifdef __cplusplus
}
#endif

#endif  /* PV_SQRT_H */
