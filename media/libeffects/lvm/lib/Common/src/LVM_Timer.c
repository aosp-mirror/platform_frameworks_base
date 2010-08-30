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

/****************************************************************************************/
/*  TIMER FUNCTION                                                                      */
/****************************************************************************************/

void LVM_Timer      (   LVM_Timer_Instance_t       *pInstance,
                        LVM_INT16                  BlockSize ){

    LVM_Timer_Instance_Private_t *pInstancePr;
    pInstancePr = (LVM_Timer_Instance_Private_t *)pInstance;

    if (pInstancePr->TimerArmed){
        pInstancePr->RemainingTimeInSamples -= BlockSize;
        if (pInstancePr->RemainingTimeInSamples <= 0){
            pInstancePr->TimerArmed = 0;
            (*pInstancePr->pCallBack) ( pInstancePr->pCallbackInstance,
                                        pInstancePr->pCallBackParams,
                                        pInstancePr->CallBackParam );
        }
    }
}

/****************************************************************************************/
/*  END OF FILE                                                                         */
/****************************************************************************************/
