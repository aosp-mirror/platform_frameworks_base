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

     %created_by:    sra % (CM/S)
     %name:          Mac3s_Sat_16x16.c % (CM/S)
     %version:       1 % (CM/S)
     %date_created:  Fri Nov 13 12:07:13 2009 % (CM/S)

***********************************************************************************/

/**********************************************************************************
   INCLUDE FILES
***********************************************************************************/

#include "VectorArithmetic.h"
#include "LVM_Macros.h"

/**********************************************************************************
   FUNCTION Mac3S_16X16
***********************************************************************************/

void Mac3s_Sat_16x16( const LVM_INT16 *src,
                     const LVM_INT16 val,
                     LVM_INT16 *dst,
                     LVM_INT16 n)
{
    LVM_INT16 ii;
    LVM_INT16 srcval;
    LVM_INT32 Temp,dInVal;


    for (ii = n; ii != 0; ii--)
    {
        srcval=*src;
        src++;

        Temp = (srcval *val)>>15;

        dInVal  = (LVM_INT32)*dst;

        Temp = Temp + dInVal;

        if (Temp > 0x00007FFF)
        {
            *dst = 0x7FFF;
        }
        else if (Temp < -0x00008000)
        {
            *dst = - 0x8000;
        }
        else
        {
            *dst = (LVM_INT16)Temp;
        }

        dst++;
    }

    return;
}

/**********************************************************************************/



