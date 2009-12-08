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

   Filename: pvmp3_normalize.h

   Date: 10/02/2007

------------------------------------------------------------------------------
 REVISION HISTORY

 Description:
------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

------------------------------------------------------------------------------
*/

#ifndef PVMP3_NORMALIZE_H
#define PVMP3_NORMALIZE_H


/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/

#include "pvmp3_audio_type_defs.h"

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

#if (defined(PV_ARM_V5)||defined(PV_ARM_V4))

__inline int32 pvmp3_normalize(int32 x)
{
    int32 y;
    __asm
    {
        clz y, x;
        sub y, y, #1
    }
    return (y);
}


#elif (defined(PV_ARM_GCC_V5)||defined(PV_ARM_GCC_V4))

__inline int32 pvmp3_normalize(int32 x)
{
    register int32 y;
    register int32 ra = x;


    asm volatile(
        "clz %0, %1\n\t"
        "sub %0, %0, #1"
    : "=&r*i"(y)
                : "r"(ra));
    return (y);

}

#else

#ifdef __cplusplus
extern "C"
{
#endif

    int32 pvmp3_normalize(int32 x);

#ifdef __cplusplus
}
#endif

#endif



#endif  /* PV_NORMALIZE_H */
