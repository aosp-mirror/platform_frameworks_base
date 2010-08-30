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

/****************************************************************************************/
/*  INCLUDE FILES                                                                       */
/****************************************************************************************/

#include "LVM_Timer.h"
#include "LVM_Timer_Private.h"
#include "LVM_Macros.h"

/****************************************************************************************/
/*  DEFINITIONS                                                                         */
/****************************************************************************************/

#define OneOverThousandInQ24 16777

/****************************************************************************************/
/*  INIT FUNCTION                                                                       */
/****************************************************************************************/

void LVM_Timer_Init (   LVM_Timer_Instance_t       *pInstance,
                        LVM_Timer_Params_t         *pParams     ){

    LVM_Timer_Instance_Private_t *pInstancePr;
    pInstancePr = (LVM_Timer_Instance_Private_t *)pInstance;

    pInstancePr->CallBackParam     = pParams->CallBackParam;
    pInstancePr->pCallBackParams   = pParams->pCallBackParams;
    pInstancePr->pCallbackInstance = pParams->pCallbackInstance;
    pInstancePr->pCallBack         = pParams->pCallBack;
    pInstancePr->TimerArmed        = 1;

    MUL32x16INTO32(pParams->SamplingRate,OneOverThousandInQ24,pInstancePr->RemainingTimeInSamples,16);  /* (Q0 * Q24) >>16 into Q8*/
    MUL32x16INTO32(pInstancePr->RemainingTimeInSamples,pParams->TimeInMs,pInstancePr->RemainingTimeInSamples,8);  /* (Q8 * Q0) >>8 into Q0*/
}

/****************************************************************************************/
/*  END OF FILE                                                                         */
/****************************************************************************************/
