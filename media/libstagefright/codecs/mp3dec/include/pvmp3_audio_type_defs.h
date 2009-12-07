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

   Filename: pvmp3_audio_type_defs.h

   Date: 09/21/2007

------------------------------------------------------------------------------
 REVISION HISTORY

 Description:
------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

 This file was derived from a number of standards bodies. The type
 definitions below were created from some of the best practices observed
 in the standards bodies.

 This file is dependent on limits.h for defining the bit widths. In an
 ANSI C environment limits.h is expected to always be present and contain
 the following definitions:

     SCHAR_MIN
     SCHAR_MAX
     UCHAR_MAX

     INT_MAX
     INT_MIN
     UINT_MAX

     SHRT_MIN
     SHRT_MAX
     USHRT_MAX

     LONG_MIN
     LONG_MAX
     ULONG_MAX

------------------------------------------------------------------------------
*/

#ifndef PVMP3_AUDIO_TYPE_DEFS_H
#define PVMP3_AUDIO_TYPE_DEFS_H

#include <stdint.h>

typedef int8_t int8;
typedef uint8_t uint8;
typedef int16_t int16;
typedef uint16_t uint16;
typedef int32_t int32;
typedef uint32_t uint32;
typedef int64_t int64;
typedef uint64_t uint64;

typedef int32_t Int32;

#endif  /* PVMP3_AUDIO_TYPE_DEFS_H */
