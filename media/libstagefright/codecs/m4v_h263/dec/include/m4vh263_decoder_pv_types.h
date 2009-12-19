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
 * -------------------------------------------------------------------$
 */

#ifndef M4V_H263_DECODER_PV_TYPES_H_
#define M4V_H263_DECODER_PV_TYPES_H_

#include <stdint.h>
#include <string.h>
#include <stdlib.h> // for free, malloc, etc

// Redefine the int types
typedef uint8_t uint8;
typedef uint16_t uint16;
typedef int16_t int16;
typedef uint32_t uint32;
typedef int32_t int32;
typedef unsigned int uint;

// Redefine the oscl memory management routines
#define oscl_memcpy memcpy
#define oscl_memset memset
#define oscl_malloc malloc
#define oscl_free free
#define oscl_memcmp memcmp
#define OSCL_DELETE(ptr) { delete(ptr); }

// Request status values.  These are negative so that
// they won't conflict with system error codes.
const int32 OSCL_REQUEST_ERR_NONE = 0;
const int32 OSCL_REQUEST_PENDING = (-0x7fffffff);
const int32 OSCL_REQUEST_ERR_CANCEL = (-1);
const int32 OSCL_REQUEST_ERR_GENERAL = (-2);

// Request status type
class OsclAOStatus
{
    public:
        OsclAOStatus();
        OsclAOStatus(int32 aStatus);
        int32 operator=(int32 aStatus);
        int32 operator==(int32 aStatus) const;
        int32 operator!=(int32 aStatus) const;
        int32 operator>=(int32 aStatus) const;
        int32 operator<=(int32 aStatus) const;
        int32 operator>(int32 aStatus) const;
        int32 operator<(int32 aStatus) const;
        int32 Value() const;
    private:
        int32 iStatus;
};

#endif  // M4V_H263_DECODER_PV_TYPES_H_
