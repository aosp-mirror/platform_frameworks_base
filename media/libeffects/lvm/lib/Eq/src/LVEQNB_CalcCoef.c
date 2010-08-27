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

#include "LVEQNB_Private.h"


/****************************************************************************************/
/*                                                                                      */
/*    Defines                                                                           */
/*                                                                                      */
/****************************************************************************************/

#define PI 3.14159265358979

/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                  LVEQNB_DoublePrecCoefs                                    */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*    Calculate double precision coefficients    for a peaking filter                   */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  Fs                           Sampling frequency index                               */
/*  pFilterDefinition          Pointer to the filter definition                         */
/*  pCoefficients            Pointer to the coefficients                                */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVEQNB_SUCCESS            Always succeeds                                           */
/*                                                                                      */
/* NOTES:                                                                               */
/*  1. The equations used are as follows:                                               */
/*                                                                                      */
/*      G  = 10^(GaindB/20) - 1                                                         */
/*      t0 = 2 * Pi * Fc / Fs                                                           */
/*      D  = 1                  if GaindB >= 0                                          */
/*      D  = 1 / (1 + G)        if GaindB <  0                                          */
/*                                                                                      */
/*      b2 = -0.5 * (2Q - D * t0) / (2Q + D * t0)                                       */
/*      b1 = (0.5 - b2) * (1 - coserr(t0))                                              */
/*      a0 = (0.5 + b2) / 2                                                             */
/*                                                                                      */
/*  Where:                                                                              */
/*      GaindB      is the gain in dBs, range -15dB to +15dB                            */
/*      Fc          is the centre frequency, DC to Fs/50                                */
/*      Fs          is the sample frequency, 8000 to 48000 in descrete steps            */
/*      Q           is the Q factor, 0.25 to 12 (represented by 25 to 1200)             */
/*                                                                                      */
/*  2. The double precision coefficients are only used when fc is less than fs/85, so   */
/*     the cosine of t0 is always close to 1.0. Instead of calculating the cosine       */
/*     itself the difference from the value 1.0 is calculated, this can be done with    */
/*     lower precision maths.                                                           */
/*                                                                                      */
/*  3. The value of the B2 coefficient is only calculated as a single precision value,  */
/*     small errors in this value have a combined effect on the Q and Gain but not the  */
/*     the frequency of the filter.                                                     */
/*                                                                                      */
/****************************************************************************************/


LVEQNB_ReturnStatus_en LVEQNB_DoublePrecCoefs(LVM_UINT16        Fs,
                                              LVEQNB_BandDef_t  *pFilterDefinition,
                                              PK_C32_Coefs_t    *pCoefficients)
{

    extern LVM_INT16    LVEQNB_GainTable[];
    extern LVM_INT16    LVEQNB_TwoPiOnFsTable[];
    extern LVM_INT16    LVEQNB_DTable[];
    extern LVM_INT16    LVEQNB_DPCosCoef[];

    /*
     * Get the filter definition
     */
    LVM_INT16           Gain        = pFilterDefinition->Gain;
    LVM_UINT16          Frequency   = pFilterDefinition->Frequency;
    LVM_UINT16          QFactor     = pFilterDefinition->QFactor;

    /*
     * Intermediate variables and temporary values
     */
    LVM_INT32           T0;
    LVM_INT16           D;
    LVM_INT32           A0;
    LVM_INT32           B1;
    LVM_INT32           B2;
    LVM_INT32           Dt0;
    LVM_INT32           B2_Den;
    LVM_INT32           B2_Num;
    LVM_INT32           CosErr;
    LVM_INT16           coef;
    LVM_INT32           factor;
    LVM_INT16           t0;
    LVM_INT16           i;

    /*
     * Calculating the intermediate values
     */
    T0 = (LVM_INT32)Frequency * LVEQNB_TwoPiOnFsTable[Fs];        /* T0 = 2 * Pi * Fc / Fs */
    if (Gain >= 0)
    {
        D = LVEQNB_DTable[15];                         /* D = 1            if GaindB >= 0 */
    }
    else
    {
        D = LVEQNB_DTable[Gain+15];                    /* D = 1 / (1 + G)  if GaindB <  0 */
    }

    /*
     * Calculate the B2 coefficient
     */
    Dt0 = D * (T0 >> 10);
    B2_Den = ((LVM_INT32)QFactor << 19) + (Dt0 >> 2);
    B2_Num = (Dt0 >> 3) - ((LVM_INT32)QFactor << 18);
    B2 = (B2_Num / (B2_Den >> 16)) << 15;

    /*
     * Calculate the cosine error by a polynomial expansion using the equation:
     *
     *  CosErr += coef(n) * t0^n                For n = 0 to 4
     */
    T0 = (T0 >> 6) * 0x7f53;                    /* Scale to 1.0 in 16-bit for range 0 to fs/50 */
    t0 = (LVM_INT16)(T0 >> 16);
    factor = 0x7fff;                            /* Initialise to 1.0 for the a0 coefficient */
    CosErr = 0;                                 /* Initialise the error to zero */
    for (i=1; i<5; i++)
    {
        coef = LVEQNB_DPCosCoef[i];             /* Get the nth coefficient */
        CosErr += (factor * coef) >> 5;         /* The nth partial sum */
        factor = (factor * t0) >> 15;           /* Calculate t0^n */
    }
    CosErr = CosErr << (LVEQNB_DPCosCoef[0]);   /* Correct the scaling */

    /*
     * Calculate the B1 and A0 coefficients
     */
    B1 = (0x40000000 - B2);                     /* B1 = (0.5 - b2/2) */
    A0 = ((B1 >> 16) * (CosErr >> 10)) >> 6;    /* Temporary storage for (0.5 - b2/2) * coserr(t0) */
    B1 -= A0;                                   /* B1 = (0.5 - b2/2) * (1 - coserr(t0))  */
    A0 = (0x40000000 + B2) >> 1;                /* A0 = (0.5 + b2) */

    /*
     * Write coeff into the data structure
     */
    pCoefficients->A0 = A0;
    pCoefficients->B1 = B1;
    pCoefficients->B2 = B2;
    pCoefficients->G  = LVEQNB_GainTable[Gain+15];

    return(LVEQNB_SUCCESS);

}


/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                  LVEQNB_SinglePrecCoefs                                    */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*    Calculate single precision coefficients    for a peaking filter                   */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  Fs                           Sampling frequency index                               */
/*  pFilterDefinition          Pointer to the filter definition                         */
/*  pCoefficients            Pointer to the coefficients                                */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVEQNB_SUCCESS            Always succeeds                                           */
/*                                                                                      */
/* NOTES:                                                                               */
/*  1. The equations used are as follows:                                               */
/*                                                                                      */
/*      G  = 10^(GaindB/20) - 1                                                         */
/*      t0 = 2 * Pi * Fc / Fs                                                           */
/*      D  = 1                  if GaindB >= 0                                          */
/*      D  = 1 / (1 + G)        if GaindB <  0                                          */
/*                                                                                      */
/*      b2 = -0.5 * (2Q - D * t0) / (2Q + D * t0)                                       */
/*      b1 = (0.5 - b2) * cos(t0)                                                       */
/*      a0 = (0.5 + b2) / 2                                                             */
/*                                                                                      */
/*  Where:                                                                              */
/*      GaindB      is the gain in dBs, range -15dB to +15dB                            */
/*      Fc          is the centre frequency, DC to Nyquist                              */
/*      Fs          is the sample frequency, 8000 to 48000 in descrete steps            */
/*      Q           is the Q factor, 0.25 to 12                                         */
/*                                                                                      */
/****************************************************************************************/


LVEQNB_ReturnStatus_en LVEQNB_SinglePrecCoefs(LVM_UINT16        Fs,
                                              LVEQNB_BandDef_t  *pFilterDefinition,
                                              PK_C16_Coefs_t    *pCoefficients)
{

    extern LVM_INT16    LVEQNB_GainTable[];
    extern LVM_INT16    LVEQNB_TwoPiOnFsTable[];
    extern LVM_INT16    LVEQNB_DTable[];
    extern LVM_INT16    LVEQNB_CosCoef[];


    /*
     * Get the filter definition
     */
    LVM_INT16           Gain        = pFilterDefinition->Gain;
    LVM_UINT16          Frequency   = pFilterDefinition->Frequency;
    LVM_UINT16          QFactor     = pFilterDefinition->QFactor;


    /*
     * Intermediate variables and temporary values
     */
    LVM_INT32           T0;
    LVM_INT16           D;
    LVM_INT32           A0;
    LVM_INT32           B1;
    LVM_INT32           B2;
    LVM_INT32           Dt0;
    LVM_INT32           B2_Den;
    LVM_INT32           B2_Num;
    LVM_INT32           COS_T0;
    LVM_INT16           coef;
    LVM_INT32           factor;
    LVM_INT16           t0;
    LVM_INT16           i;

    /*
     * Calculating the intermediate values
     */
    T0 = (LVM_INT32)Frequency * LVEQNB_TwoPiOnFsTable[Fs];        /* T0 = 2 * Pi * Fc / Fs */
    if (Gain >= 0)
    {
        D = LVEQNB_DTable[15];                         /* D = 1            if GaindB >= 0 */
    }
    else
    {
        D = LVEQNB_DTable[Gain+15];                    /* D = 1 / (1 + G)  if GaindB <  0 */
    }

    /*
     * Calculate the B2 coefficient
     */
    Dt0 = D * (T0 >> 10);
    B2_Den = ((LVM_INT32)QFactor << 19) + (Dt0 >> 2);
    B2_Num = (Dt0 >> 3) - ((LVM_INT32)QFactor << 18);
    B2 = (B2_Num / (B2_Den >> 16)) << 15;

    /*
     * Calculate the cosine by a polynomial expansion using the equation:
     *
     *  Cos += coef(n) * t0^n                   For n = 0 to 6
     */
    T0 = (T0 >> 10) * 20859;                    /* Scale to 1.0 in 16-bit for range 0 to fs/2 */
    t0 = (LVM_INT16)(T0 >> 16);
    factor = 0x7fff;                            /* Initialise to 1.0 for the a0 coefficient */
    COS_T0 = 0;                                 /* Initialise the error to zero */
    for (i=1; i<7; i++)
    {
        coef = LVEQNB_CosCoef[i];               /* Get the nth coefficient */
        COS_T0 += (factor * coef) >> 5;         /* The nth partial sum */
        factor = (factor * t0) >> 15;           /* Calculate t0^n */
    }
    COS_T0 = COS_T0 << (LVEQNB_CosCoef[0]+6);          /* Correct the scaling */


    B1 = ((0x40000000 - B2) >> 16) * (COS_T0 >> 16);    /* B1 = (0.5 - b2/2) * cos(t0) */
    A0 = (0x40000000 + B2) >> 1;                        /* A0 = (0.5 + b2/2) */

    /*
     * Write coeff into the data structure
     */
    pCoefficients->A0 = (LVM_INT16)(A0>>16);
    pCoefficients->B1 = (LVM_INT16)(B1>>15);
    pCoefficients->B2 = (LVM_INT16)(B2>>16);
    pCoefficients->G  = LVEQNB_GainTable[Gain+15];


    return(LVEQNB_SUCCESS);

}
