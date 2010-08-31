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
   FUNCTION ADD2_SAT_32X32
***********************************************************************************/

void Add2_Sat_32x32( const LVM_INT32  *src,
                           LVM_INT32  *dst,
                           LVM_INT16  n )
{
    LVM_INT32 a,b,c;
    LVM_INT16 ii;
    for (ii = n; ii != 0; ii--)
    {
        a=*src;
        src++;

        b=*dst;
        c=a+b;
        if ((((c ^ a) & (c ^ b)) >> 31)!=0)     /* overflow / underflow */
        {
            if(a<0)
            {
                c=0x80000000l;
            }
            else
            {
                c=0x7FFFFFFFl;
            }
        }

        *dst = c;
        dst++;
    }
    return;
}

/**********************************************************************************/
