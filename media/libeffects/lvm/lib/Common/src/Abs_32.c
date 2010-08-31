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

/*######################################################################################*/
/*  Include files                                                                       */
/*######################################################################################*/

#include    "ScalarArithmetic.h"

/****************************************************************************************
 *  Name        : Abs_32()
 *  Input       : Signed 32-bit integer
 *  Output      :
 *  Returns     : Absolute value
 *  Description : Absolute value with maximum negative value corner case
 *  Remarks     :
 ****************************************************************************************/

LVM_INT32    Abs_32(LVM_INT32    input)
{
    if(input <  0)
    {
        if (input == (LVM_INT32)(0x80000000U))
        {
            /* The corner case, so set to the maximum positive value */
            input=(LVM_INT32) 0x7fffffff;
        }
        else
        {
            /* Negative input, so invert */
            input = (LVM_INT32)(-input);
        }
    }
    return input;
}

