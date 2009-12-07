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
------------------------------------------------------------------------------
   PacketVideo Corp.
   MP3 Decoder Library

   Filename: pv_mp3dec_fxd_op.h

     Date: 09/21/2007


------------------------------------------------------------------------------
 REVISION HISTORY


 Description:
------------------------------------------------------------------------------
 INCLUDE DESCRIPTION

 This file select the associated fixed point functions with the OS/ARCH.



------------------------------------------------------------------------------
*/

#ifndef PV_MP3DEC_FXD_OP_H
#define PV_MP3DEC_FXD_OP_H

#include "pvmp3_audio_type_defs.h"


#ifdef __cplusplus
extern "C"
{
#endif


#if (defined(PV_ARM_V5)||defined(PV_ARM_V4))

#include "pv_mp3dec_fxd_op_arm.h"

#elif (defined(PV_ARM_GCC_V5)||defined(PV_ARM_GCC_V4))

#include "pv_mp3dec_fxd_op_arm_gcc.h"

#elif (defined(PV_ARM_MSC_EVC_V5)||defined(PV_ARM_MSC_EVC_V4))

#include "pv_mp3dec_fxd_op_msc_evc.h"

#else

#ifndef C_EQUIVALENT
#define C_EQUIVALENT
#endif

#include "pv_mp3dec_fxd_op_c_equivalent.h"

#endif


#ifdef __cplusplus
}
#endif



#endif  /* PV_MP3DEC_FXD_OP_H */
