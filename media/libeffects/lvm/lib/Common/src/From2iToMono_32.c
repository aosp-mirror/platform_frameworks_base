/*
 * Copyright (C) 2004-2010 NXP Software
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**********************************************************************************
   INCLUDE FILES
***********************************************************************************/

#include "VectorArithmetic.h"

/**********************************************************************************
   FUNCTION From2iToMono_32
***********************************************************************************/

void From2iToMono_32( const LVM_INT32 *src,
                            LVM_INT32 *dst,
                            LVM_INT16 n)
{
    LVM_INT16 ii;
    LVM_INT32 Temp;

    for (ii = n; ii != 0; ii--)
    {
        Temp = (*src>>1);
        src++;

        Temp +=(*src>>1);
        src++;

        *dst = Temp;
        dst++;
    }

    return;
}

/**********************************************************************************/
