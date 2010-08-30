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
   FUNCTION Shift_Sat_v16xv16
***********************************************************************************/

void Shift_Sat_v16xv16 (const   LVM_INT16   val,
                        const   LVM_INT16   *src,
                        LVM_INT16   *dst,
                        LVM_INT16   n)
{
    LVM_INT32   temp;
    LVM_INT32   ii;
    LVM_INT16   RShift;
    if(val>0)
    {
        for (ii = n; ii != 0; ii--)
        {
            temp = (LVM_INT32)*src;
            src++;

            temp = temp << val;

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
    }
    else if(val<0)
    {
        RShift=(LVM_INT16)(-val);

        for (ii = n; ii != 0; ii--)
        {
            *dst = (LVM_INT16)(*src >> RShift);
            dst++;
            src++;
        }
    }
    else
    {
        if(src!=dst)
        {
            Copy_16(src,dst,n);
        }
    }
    return;
}

/**********************************************************************************/
