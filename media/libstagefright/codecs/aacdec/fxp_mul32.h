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
 * -------------------------------------------------------------------
 */
/*

 Pathname: ./c/include/fxp_mul32.h

------------------------------------------------------------------------------
 REVISION HISTORY

 Who:                                       Date:
 Description:
------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

------------------------------------------------------------------------------
*/

#ifndef FXP_MUL32
#define FXP_MUL32

#if   defined(PV_ARM_V5)

#include "fxp_mul32_arm_v5.h"

#elif defined(PV_ARM_V4)

#include "fxp_mul32_arm_v4.h"

#elif defined(PV_ARM_MSC_EVC_V4)

#include "fxp_mul32_c_msc_evc.h"

#elif defined(PV_ARM_MSC_EVC_V5)

#include "fxp_mul32_c_msc_evc_armv5.h"

#elif defined(PV_ARM_GCC_V5)

#include "fxp_mul32_arm_gcc.h"

#elif defined(PV_ARM_GCC_V4)

#include "fxp_mul32_arm_v4_gcc.h"

#else

#ifndef C_EQUIVALENT
#define C_EQUIVALENT
#endif

#include "fxp_mul32_c_equivalent.h"

#endif


#endif   /*  FXP_MUL32  */

