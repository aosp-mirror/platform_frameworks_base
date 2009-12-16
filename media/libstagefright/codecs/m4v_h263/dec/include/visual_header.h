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
#ifndef _VISUAL_HEADER_H
#define _VISUAL_HEADER_H

#ifndef _PV_TYPES_ // In order to compile in MDF wrapper
#define _PV_TYPES_

#include "m4vh263_decoder_pv_types.h"

typedef uint Bool;

#endif // #ifndef _PV_TYPES_


typedef struct tagVolInfo
{
    int32   shortVideoHeader;       /* shortVideoHeader mode */

    /* Error Resilience Flags */
    int32   errorResDisable;        /* VOL disable error resilence mode(Use Resynch markers) */
    int32   useReverseVLC;          /* VOL reversible VLCs */
    int32   dataPartitioning;       /* VOL data partitioning */

    /* Parameters used for scalability */
    int32   scalability;            /* VOL scalability (flag) */

    int32   nbitsTimeIncRes;        /* number of bits for time increment () */

    int32   profile_level_id;       /* profile and level */


} VolInfo;

#endif // #ifndef _VISUAL_HEADER_H

