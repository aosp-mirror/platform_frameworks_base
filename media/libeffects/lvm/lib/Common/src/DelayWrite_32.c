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
   FUNCTION DelayMix_16x16
***********************************************************************************/

void DelayWrite_32(const LVM_INT32  *src,               /* Source 1, to be delayed */
                         LVM_INT32  *delay,             /* Delay buffer */
                         LVM_UINT16 size,               /* Delay size */
                         LVM_UINT16 *pOffset,           /* Delay offset */
                         LVM_INT16  n)                  /* Number of samples */
{
    LVM_INT16   i;
    LVM_INT16   Offset  = (LVM_INT16)*pOffset;

    for (i=0; i<n; i++)
    {
        delay[Offset] = *src;
        Offset++;
        src++;

        /* Make the delay buffer a circular buffer */
        if (Offset >= size)
        {
            Offset = 0;
        }
    }

    /* Update the offset */
    *pOffset = (LVM_UINT16)Offset;

    return;
}

/**********************************************************************************/

