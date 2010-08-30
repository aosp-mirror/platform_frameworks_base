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
   FUNCTION INT32RSHIFTTOINT16_SAT_32X16
***********************************************************************************/

void Int32RShiftToInt16_Sat_32x16(const LVM_INT32  *src,
                                  LVM_INT16 *dst,
                                  LVM_INT16 n,
                                  LVM_INT16 shift )
{
    LVM_INT32 temp;
    LVM_INT16 ii;

    for (ii = n; ii != 0; ii--)
    {
        temp = *src >> shift;
        src++;

        if (temp > 0x00007FFF)
        {
            *dst = 0x7FFF;
        }
        else if (temp < -0x00008000)
        {
            *dst = - 0x8000;
        }
        else
        {
            *dst = (LVM_INT16)temp;
        }

        dst++;
    }

    return;
}

/**********************************************************************************/
