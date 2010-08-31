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

#include "LVM_Types.h"
#include "LVM_Macros.h"
#include "LVC_Mixer_Private.h"


/************************************************************************/
/* FUNCTION:                                                            */
/*   LVMixer3_VarSlope_SetTimeConstant                                  */
/*                                                                      */
/* DESCRIPTION:                                                         */
/*  This function calculates the step change for fractional gain for a  */
/*  given time constant, sample rate and num channels                   */
/*  Delta=(2147483647*4*1000)/(NumChannels*SampleRate*Tc_millisec)      */
/*  in Q 0.31 format                                                    */
/*                                                                      */
/* PARAMETERS:                                                          */
/*  pStream     - ptr to Instance Parameter Structure LVMixer3_st for an*/
/*                Audio Stream                                          */
/*  Tc_millisec - TimeConstant i.e time required in milli second to     */
/*                go from linear fractional gain of 0 to 0.99999999     */
/*  Fs          - LVM_Fs_en enumerator for Sampling Frequency           */
/*  NumChannels - Number of channels in Audio Stream 1=Mono, 2=Stereo   */
/*                                                                      */
/* UPDATES:                                                             */
/*  Delta       - the step change for fractional gain per 4 samples     */
/*                in Q0.31 format for a given Time Constant,            */
/*                Sample Rate and NumChannels                           */
/* RETURNS:                                                             */
/*  void                                                                */
/************************************************************************/

void LVC_Mixer_VarSlope_SetTimeConstant( LVMixer3_st *pStream,
                                        LVM_INT32           Tc_millisec,
                                        LVM_Fs_en           Fs,
                                        LVM_INT16           NumChannels)
{
    LVM_INT32   DeltaTable[9]={1073741824,
                               779132389,
                               715827882,
                               536870912,
                               389566194,
                               357913941,
                               268435456,
                               194783097,
                               178956971};
    Mix_Private_st  *pInstance=(Mix_Private_st *)pStream->PrivateParams;
    LVM_INT32   Delta=DeltaTable[Fs];

    LVM_INT32   Current;
    LVM_INT32   Target;

    Delta=Delta>>(NumChannels-1);

    /*  Get gain values  */
    Current = LVC_Mixer_GetCurrent( pStream );
    Target = LVC_Mixer_GetTarget( pStream );

    if (Current != Target)
    {
        Tc_millisec = Tc_millisec * 32767 / (Current - Target);
        if (Tc_millisec<0) Tc_millisec = -Tc_millisec;

        if(Tc_millisec==0)
            Delta=0x7FFFFFFF;
        else
            Delta=Delta/Tc_millisec;

        if(Delta==0)
            Delta=1;            // If Time Constant is so large that Delta is 0, assign minimum value to Delta
    }
    else
    {
        Delta =1;               // Minimum value for proper call-backs (setting it to zero has some problems, to be corrected)
    }


    pInstance->Delta=Delta;     // Delta=(2147483647*4*1000)/(NumChannels*SampleRate*Tc_millisec) in Q 0.31 format
}
