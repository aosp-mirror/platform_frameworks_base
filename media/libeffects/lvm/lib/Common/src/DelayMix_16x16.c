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

void DelayMix_16x16(const LVM_INT16 *src,           /* Source 1, to be delayed */
                          LVM_INT16 *delay,         /* Delay buffer */
                          LVM_INT16 size,           /* Delay size */
                          LVM_INT16 *dst,           /* Source/destination */
                          LVM_INT16 *pOffset,       /* Delay offset */
                          LVM_INT16 n)              /* Number of stereo samples */
{
    LVM_INT16   i;
    LVM_INT16   Offset  = *pOffset;
    LVM_INT16   temp;

    for (i=0; i<n; i++)
    {
        /* Left channel */
        temp            = (LVM_INT16)((LVM_UINT32)((LVM_INT32)(*dst) + (LVM_INT32)delay[Offset]) >> 1);
        *dst            = temp;
        dst++;

        delay[Offset] = *src;
        Offset++;
        src++;


        /* Right channel */
        temp            = (LVM_INT16)((LVM_UINT32)((LVM_INT32)(*dst) - (LVM_INT32)delay[Offset]) >> 1);
        *dst            = temp;
        dst++;

        delay[Offset] = *src;
        Offset++;
        src++;

        /* Make the reverb delay buffer a circular buffer */
        if (Offset >= size)
        {
            Offset = 0;
        }
    }

    /* Update the offset */
    *pOffset = Offset;

    return;
}

/**********************************************************************************/
