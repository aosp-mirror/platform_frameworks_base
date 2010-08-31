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
/*                                                                                      */
/*    Includes                                                                          */
/*                                                                                      */
/****************************************************************************************/

#include "AGC.h"
#include "ScalarArithmetic.h"


/****************************************************************************************/
/*                                                                                      */
/*    Defines                                                                           */
/*                                                                                      */
/****************************************************************************************/

#define VOL_TC_SHIFT                                        21          /* As a power of 2 */
#define DECAY_SHIFT                                        10           /* As a power of 2 */


/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                  AGC_MIX_VOL_2St1Mon_D32_WRA                               */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*    Apply AGC and mix signals                                                         */
/*                                                                                      */
/*                                                                                      */
/*  StSrc   ------------------|                                                         */
/*                            |                                                         */
/*              ______       _|_        ________                                        */
/*             |      |     |   |      |        |                                       */
/*  MonoSrc -->| AGC  |---->| + |----->| Volume |------------------------------+--->    */
/*             | Gain |     |___|      | Gain   |                              |        */
/*             |______|                |________|                              |        */
/*                /|\                               __________     ________    |        */
/*                 |                               |          |   |        |   |        */
/*                 |-------------------------------| AGC Gain |<--| Peak   |<--|        */
/*                                                 | Update   |   | Detect |            */
/*                                                 |__________|   |________|            */
/*                                                                                      */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  pInstance               Instance pointer                                            */
/*  pStereoIn               Stereo source                                               */
/*  pMonoIn                 Mono band pass source                                       */
/*  pStereoOut              Stereo destination                                          */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  Void                                                                                */
/*                                                                                      */
/* NOTES:                                                                               */
/*                                                                                      */
/****************************************************************************************/

void AGC_MIX_VOL_2St1Mon_D32_WRA(AGC_MIX_VOL_2St1Mon_D32_t  *pInstance,     /* Instance pointer */
                                 const LVM_INT32            *pStSrc,        /* Stereo source */
                                 const LVM_INT32            *pMonoSrc,      /* Mono source */
                                 LVM_INT32                  *pDst,          /* Stereo destination */
                                 LVM_UINT16                 NumSamples)     /* Number of samples */
{

    /*
     * General variables
     */
    LVM_UINT16      i;                                          /* Sample index */
    LVM_INT32       Left;                                       /* Left sample */
    LVM_INT32       Right;                                      /* Right sample */
    LVM_INT32       Mono;                                       /* Mono sample */
    LVM_INT32       AbsPeak;                                    /* Absolute peak signal */
    LVM_INT32       HighWord;                                   /* High word in intermediate calculations */
    LVM_INT32       LowWord;                                    /* Low word in intermediate calculations */
    LVM_INT16       AGC_Mult;                                   /* Short AGC gain */
    LVM_INT16       Vol_Mult;                                   /* Short volume */


    /*
     * Instance control variables
     */
    LVM_INT32      AGC_Gain      = pInstance->AGC_Gain;         /* Get the current AGC gain */
    LVM_INT32      AGC_MaxGain   = pInstance->AGC_MaxGain;      /* Get maximum AGC gain */
    LVM_INT16      AGC_GainShift = pInstance->AGC_GainShift;    /* Get the AGC shift */
    LVM_INT16      AGC_Attack    = pInstance->AGC_Attack;       /* Attack scaler */
    LVM_INT16      AGC_Decay     = pInstance->AGC_Decay;        /* Decay scaler */
    LVM_INT32      AGC_Target    = pInstance->AGC_Target;       /* Get the target level */
    LVM_INT32      Vol_Current   = pInstance->Volume;           /* Actual volume setting */
    LVM_INT32      Vol_Target    = pInstance->Target;           /* Target volume setting */
    LVM_INT16      Vol_Shift     = pInstance->VolumeShift;      /* Volume shift scaling */
    LVM_INT16      Vol_TC        = pInstance->VolumeTC;         /* Time constant */


    /*
     * Process on a sample by sample basis
     */
    for (i=0;i<NumSamples;i++)                                  /* For each sample */
    {

        /*
         * Get the short scalers
         */
        AGC_Mult    = (LVM_INT16)(AGC_Gain >> 16);              /* Get the short AGC gain */
        Vol_Mult    = (LVM_INT16)(Vol_Current >> 16);           /* Get the short volume gain */


        /*
         * Get the input samples
         */
        Left  = *pStSrc++;                                      /* Get the left sample */
        Right = *pStSrc++;                                      /* Get the right sample */
        Mono  = *pMonoSrc++;                                    /* Get the mono sample */


        /*
         * Apply the AGC gain to the mono input and mix with the stereo signal
         */
        HighWord = (AGC_Mult * (Mono >> 16));                   /* signed long (Mono) by unsigned short (AGC_Mult) multiply */
        LowWord = (AGC_Mult * (Mono & 0xffff));
        Mono = (HighWord + (LowWord >> 16)) << (AGC_GainShift);
        Left  += Mono;                                          /* Mix in the mono signal */
        Right += Mono;


        /*
         * Apply the volume and write to the output stream
         */
        HighWord = (Vol_Mult * (Left >> 16));                   /* signed long (Left) by unsigned short (Vol_Mult) multiply */
        LowWord = (Vol_Mult * (Left & 0xffff));
        Left = (HighWord + (LowWord >> 16)) << (Vol_Shift);
        HighWord = (Vol_Mult * (Right >> 16));                  /* signed long (Right) by unsigned short (Vol_Mult) multiply */
        LowWord = (Vol_Mult * (Right & 0xffff));
        Right = (HighWord + (LowWord >> 16)) << (Vol_Shift);
        *pDst++ = Left;                                         /* Save the results */
        *pDst++ = Right;


        /*
         * Update the AGC gain
         */
        AbsPeak = (Abs_32(Left)>Abs_32(Right)) ? Abs_32(Left) : Abs_32(Right);  /* Get the absolute peak */
        if (AbsPeak > AGC_Target)
        {
            /*
             * The signal is too large so decrease the gain
             */
            HighWord = (AGC_Attack * (AGC_Gain >> 16));         /* signed long (AGC_Gain) by unsigned short (AGC_Attack) multiply */
            LowWord = (AGC_Attack * (AGC_Gain & 0xffff));
            AGC_Gain = (HighWord + (LowWord >> 16)) << 1;
        }
        else
        {
            /*
             * The signal is too small so increase the gain
             */
            if (AGC_Gain > AGC_MaxGain)
            {
                AGC_Gain -= (AGC_Decay << DECAY_SHIFT);
            }
            else
            {
                AGC_Gain += (AGC_Decay << DECAY_SHIFT);
            }
        }

        /*
         * Update the gain
         */
        Vol_Current += Vol_TC * ((Vol_Target - Vol_Current) >> VOL_TC_SHIFT);
    }


    /*
     * Update the parameters
     */
    pInstance->Volume = Vol_Current;                            /* Actual volume setting */
    pInstance->AGC_Gain = AGC_Gain;

    return;
}

