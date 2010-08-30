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

#ifndef __LVM_TIMER_H__
#define __LVM_TIMER_H__

#include "LVM_Types.h"

/****************************************************************************************/
/*                                                                                      */
/*  Header file for the LVM_Timer library                                               */
/*                                                                                      */
/*  Functionality:                                                                      */
/*  The timer will count down a number of ms, based on the number of samples it         */
/*  sees and the curent sampling rate.  When the timer expires, a registered            */
/*  callback function will be called.                                                   */
/*  The maximal number of sampless that can be called by the timer is 2^32, which       */
/*  corresponds to 24.8 hours at a sampling rate of 48 kHz                              */
/*  The timer currently does not suport changes in sampling rate while timing.          */
/****************************************************************************************/


#ifdef __cplusplus
extern "C" {
#endif /* __cplusplus */

/****************************************************************************************/
/*  TYPE DEFINITIONS                                                                    */
/****************************************************************************************/

typedef struct
{
    LVM_INT32 Storage[6];

} LVM_Timer_Instance_t;

typedef struct
{
    LVM_INT32  SamplingRate;
    LVM_INT16  TimeInMs;
    LVM_INT32  CallBackParam;
    void       *pCallBackParams;
    void       *pCallbackInstance;
    void       (*pCallBack)(void*,void*,LVM_INT32);

} LVM_Timer_Params_t;

/****************************************************************************************/
/*  FUNCTION PROTOTYPES                                                                 */
/****************************************************************************************/

void LVM_Timer_Init (   LVM_Timer_Instance_t       *pInstance,
                        LVM_Timer_Params_t         *pParams     );


void LVM_Timer      (   LVM_Timer_Instance_t       *pInstance,
                        LVM_INT16                       BlockSize );


/****************************************************************************************/
/*  END OF HEADER                                                                       */
/****************************************************************************************/

#ifdef __cplusplus
}
#endif /* __cplusplus */

#endif  /* __LVM_TIMER_H__ */
