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

#include "CompLim_private.h"

/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                 NonLinComp_D16                                             */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  Non-linear compression by companding. The function works on a sample by sample      */
/*  basis by increasing the level near the zero crossing. This gives a ttrade-off       */
/*  between THD and compression. It uses the equation:                                  */
/*                                                                                      */
/*        Output = Input + K * (Input - Input^2)        if Input >  0                   */
/*               = Input + K * (Input + Input^2)      if Input <= 0                     */
/*                                                                                      */
/*    The value of K controls the amount of compression and as a side effect the amount */
/*  distortion introduced. The amount of compression is signal dependent and the values */
/*  given below are approximate.                                                        */
/*                                                                                      */
/*        Gain (fractional)  Gain (integer)    Compression          Pk-Pk THD           */
/*            1.0                 32767            +6dB            16dB                 */
/*            0.78                25559            +5dB            19dB                 */
/*            0.6                 19661            +4dB            21dB                 */
/*            0.41                13435            +3dB            24dB                 */
/*            0.26                 8520            +2dB            28dB                 */
/*            0.12                 3932            +1dB            34dB                 */
/*            0.0                     0            +0dB            98dB                 */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*    Gain            -    compression control parameter                                */
/*    pDataIn         -    pointer to the input data buffer                             */
/*    pDataOut        -    pointer to the output data buffer                            */
/*    BlockLength     -    number of samples to process                                 */
/*                                                                                      */
/* RETURNS:                                                                             */
/*    None                                                                              */
/*                                                                                      */
/* NOTES:                                                                               */
/*                                                                                      */
/****************************************************************************************/

void NonLinComp_D16(LVM_INT16        Gain,
                      LVM_INT16        *pDataIn,
                    LVM_INT16        *pDataOut,
                    LVM_INT32        BlockLength)
{

    LVM_INT16            Sample;                    /* Input samples */
    LVM_INT32            SampleNo;                /* Sample index */
    LVM_INT16            Temp;


    /*
     * Process a block of samples
     */
    for(SampleNo = 0; SampleNo<BlockLength; SampleNo++)
    {

        /*
         * Read the input
         */
        Sample = *pDataIn;
        pDataIn++;


        /*
         * Apply the compander, this compresses the signal at the expense of
         * harmonic distortion. The amount of compression is control by the
         * gain factor
         */
        if ((LVM_INT32)Sample != -32768)
        {
            Temp = (LVM_INT16)((Sample * Sample) >> 15);
            if(Sample >0)
            {
                Sample = (LVM_INT16)(Sample + ((Gain * (Sample - Temp)) >> 15));
            }
            else
            {
                Sample = (LVM_INT16)(Sample + ((Gain * (Sample + Temp)) >> 15));
            }
        }


        /*
         * Save the output
         */
        *pDataOut = Sample;
        pDataOut++;


    }

}

