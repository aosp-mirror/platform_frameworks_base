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

#include "LVM_Types.h"
#include "LVM_Macros.h"
#include "VectorArithmetic.h"

/**********************************************************************************
   FUNCTION DelayAllPass_32x32
***********************************************************************************/

void DelayAllPass_Sat_32x16To32(  LVM_INT32  *delay,                    /* Delay buffer */
                                  LVM_UINT16 size,                      /* Delay size */
                                  LVM_INT16 coeff,                      /* All pass filter coefficient */
                                  LVM_UINT16 DelayOffset,               /* Simple delay offset */
                                  LVM_UINT16 *pAllPassOffset,           /* All pass filter delay offset */
                                  LVM_INT32  *dst,                      /* Source/destination */
                                  LVM_INT16 n)                          /* Number of  samples */
{
    LVM_INT16   i;
    LVM_UINT16   AllPassOffset = *pAllPassOffset;
    LVM_INT32    temp;
    LVM_INT32    a,b,c;

    for (i = 0; i < n; i++)
    {

        MUL32x16INTO32(delay[AllPassOffset], coeff, temp, 15)
        a = temp;
        b = delay[DelayOffset];
        DelayOffset++;

        c = a + b;
        if ((((c ^ a) & (c ^ b)) >> 31) != 0)  /* overflow / underflow */
        {
            if(a < 0)
            {
                c = 0x80000000l;
            }
            else
            {
                c = 0x7FFFFFFFl;
            }
        }
        *dst = c;
        dst++;


        MUL32x16INTO32(c, -coeff, temp, 15)
        a = temp;
        b = delay[AllPassOffset];
        c = a + b;
        if ((((c ^ a) & (c ^ b)) >> 31)!=0)  /* overflow / underflow */
        {
            if(a < 0)
            {
                c = 0x80000000l;
            }
            else
            {
                c = 0x7FFFFFFFl;
            }
        }
        delay[AllPassOffset] = c;
        AllPassOffset++;

        /* Make the delay buffer a circular buffer */
        if (DelayOffset >= size)
        {
            DelayOffset = 0;
        }

        if (AllPassOffset >= size)
        {
            AllPassOffset = 0;
        }
    }

    /* Update the offset */
    *pAllPassOffset = AllPassOffset;

    return;
}

/**********************************************************************************/

