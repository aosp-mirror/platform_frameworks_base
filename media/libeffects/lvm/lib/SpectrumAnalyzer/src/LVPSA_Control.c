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

#include    "LVPSA.h"
#include    "LVPSA_Private.h"
#include    "VectorArithmetic.h"

#define     LOW_FREQ            298             /* 32768/110 for low test frequency */
#define     HIGH_FREQ           386             /* 32768/85 for high test frequency */

LVPSA_RETURN LVPSA_SetBPFiltersType (  LVPSA_InstancePr_t        *pInst,
                                       LVPSA_ControlParams_t      *pParams  );

LVPSA_RETURN LVPSA_SetQPFCoefficients( LVPSA_InstancePr_t        *pInst,
                                       LVPSA_ControlParams_t      *pParams  );

LVPSA_RETURN LVPSA_BPSinglePrecCoefs(  LVM_UINT16             Fs,
                                       LVPSA_FilterParam_t   *pFilterParams,
                                       BP_C16_Coefs_t        *pCoefficients);

LVPSA_RETURN LVPSA_BPDoublePrecCoefs(  LVM_UINT16            Fs,
                                       LVPSA_FilterParam_t  *pFilterParams,
                                       BP_C32_Coefs_t       *pCoefficients);

LVPSA_RETURN LVPSA_BPDoublePrecCoefs(  LVM_UINT16              Fs,
                                       LVPSA_FilterParam_t     *pFilterParams,
                                       BP_C32_Coefs_t          *pCoefficients);

LVPSA_RETURN LVPSA_SetBPFCoefficients( LVPSA_InstancePr_t        *pInst,
                                       LVPSA_ControlParams_t      *pParams  );

LVPSA_RETURN LVPSA_ClearFilterHistory( LVPSA_InstancePr_t        *pInst);




/************************************************************************************/
/*                                                                                  */
/* FUNCTION:            LVPSA_Control                                               */
/*                                                                                  */
/* DESCRIPTION:                                                                     */
/*  Give some new control parameters to the module.                                 */
/*                                                                                  */
/* PARAMETERS:                                                                      */
/*  hInstance           Pointer to the instance                                     */
/*  NewParams           Structure that contains the new parameters                  */
/*                                                                                  */
/* RETURNS:                                                                         */
/*  LVPSA_OK            Succeeds                                                    */
/*  otherwise           Error due to bad parameters                                 */
/*                                                                                  */
/************************************************************************************/
LVPSA_RETURN LVPSA_Control           ( pLVPSA_Handle_t             hInstance,
                                       LVPSA_ControlParams_t      *pNewParams     )
{

    LVPSA_InstancePr_t     *pLVPSA_Inst    = (LVPSA_InstancePr_t*)hInstance;

    if((hInstance == LVM_NULL) || (pNewParams == LVM_NULL))
    {
        return(LVPSA_ERROR_NULLADDRESS);
    }
    if(pNewParams->Fs >= LVPSA_NR_SUPPORTED_RATE)
    {
        return(LVPSA_ERROR_INVALIDPARAM);
    }
    if(pNewParams->LevelDetectionSpeed >= LVPSA_NR_SUPPORTED_SPEED)
    {
        return(LVPSA_ERROR_INVALIDPARAM);
    }

    pLVPSA_Inst->NewParams = *pNewParams;
    pLVPSA_Inst->bControlPending = LVM_TRUE;

    return(LVPSA_OK);
}

/************************************************************************************/
/*                                                                                  */
/* FUNCTION:            LVPSA_GetControlParams                                      */
/*                                                                                  */
/* DESCRIPTION:                                                                     */
/*  Get the current control parameters of the module                                */
/*                                                                                  */
/* PARAMETERS:                                                                      */
/*  hInstance       Pointer to the instance                                         */
/*  pParams         Pointer to an empty control structure                           */
/* RETURNS:                                                                         */
/*  LVPSA_OK            Succeeds                                                    */
/*  otherwise           Error due to bad parameters                                 */
/*                                                                                  */
/************************************************************************************/
LVPSA_RETURN LVPSA_GetControlParams         (    pLVPSA_Handle_t            hInstance,
                                                 LVPSA_ControlParams_t     *pParams )
{
    LVPSA_InstancePr_t     *pLVPSA_Inst    = (LVPSA_InstancePr_t*)hInstance;

    if((hInstance == LVM_NULL) || (pParams == LVM_NULL))
    {
        return(LVPSA_ERROR_NULLADDRESS);
    }

    pParams->Fs                     = pLVPSA_Inst->CurrentParams.Fs;
    pParams->LevelDetectionSpeed    = pLVPSA_Inst->CurrentParams.LevelDetectionSpeed;

    return(LVPSA_OK);
}


/************************************************************************************/
/*                                                                                  */
/* FUNCTION:            LVPSA_GetInitParams                                         */
/*                                                                                  */
/* DESCRIPTION:                                                                     */
/*  Get the initialization parameters of the module                                 */
/*                                                                                  */
/* PARAMETERS:                                                                      */
/*  hInstance       Pointer to the instance                                         */
/*  pParams         Pointer to an empty control structure                           */
/* RETURNS:                                                                         */
/*  LVPSA_OK            Succeeds                                                    */
/*  otherwise           Error due to bad parameters                                 */
/*                                                                                  */
/************************************************************************************/
LVPSA_RETURN LVPSA_GetInitParams         (    pLVPSA_Handle_t            hInstance,
                                              LVPSA_InitParams_t        *pParams )
{
    LVPSA_InstancePr_t     *pLVPSA_Inst    = (LVPSA_InstancePr_t*)hInstance;

    if((hInstance == LVM_NULL) || (pParams == LVM_NULL))
    {
        return(LVPSA_ERROR_NULLADDRESS);
    }

    pParams->SpectralDataBufferDuration   = pLVPSA_Inst->SpectralDataBufferDuration;
    pParams->MaxInputBlockSize            = pLVPSA_Inst->MaxInputBlockSize;
    pParams->nBands                       = pLVPSA_Inst->nBands;
    pParams->pFiltersParams               = pLVPSA_Inst->pFiltersParams;

    return(LVPSA_OK);
}


/************************************************************************************/
/*                                                                                  */
/* FUNCTION:            LVPSA_ApplyNewSettings                                      */
/*                                                                                  */
/* DESCRIPTION:                                                                     */
/*  Reinitialize some parameters and changes filters' coefficients if               */
/*  some control parameters have changed.                                           */
/*                                                                                  */
/* PARAMETERS:                                                                      */
/*  pInst               Pointer to the instance                                     */
/*                                                                                  */
/* RETURNS:                                                                         */
/*  LVPSA_OK            Succeeds                                                    */
/*  otherwise           Error due to bad parameters                                 */
/*                                                                                  */
/* NOTES:                                                                           */
/*                                                                                  */
/************************************************************************************/
LVPSA_RETURN LVPSA_ApplyNewSettings (LVPSA_InstancePr_t     *pInst)
{
    LVM_UINT16 ii;
    LVM_UINT16 Freq;
    LVPSA_ControlParams_t   Params;
    extern LVM_INT16        LVPSA_nSamplesBufferUpdate[];
    extern LVM_UINT16       LVPSA_SampleRateTab[];
    extern LVM_UINT16       LVPSA_DownSamplingFactor[];


    if(pInst == 0)
    {
        return(LVPSA_ERROR_NULLADDRESS);
    }

    Params = pInst->NewParams;

    /* Modifies filters types and coefficients, clear the taps and
       re-initializes parameters if sample frequency has changed    */
    if(Params.Fs != pInst->CurrentParams.Fs)
    {
        pInst->CurrentParams.Fs = Params.Fs;

        /* Initialize the center freqeuncies as a function of the sample rate */
        Freq = (LVM_UINT16) ((LVPSA_SampleRateTab[pInst->CurrentParams.Fs]>>1) / (pInst->nBands + 1));
        for(ii = pInst->nBands; ii > 0; ii--)
        {
            pInst->pFiltersParams[ii-1].CenterFrequency = (LVM_UINT16) (Freq * ii);
        }

        /* Count the number of relevant filters. If the center frequency of the filter is
           bigger than the nyquist frequency, then the filter is not relevant and doesn't
           need to be used */
        for(ii = pInst->nBands; ii > 0; ii--)
        {
            if(pInst->pFiltersParams[ii-1].CenterFrequency < (LVPSA_SampleRateTab[pInst->CurrentParams.Fs]>>1))
            {
                pInst->nRelevantFilters = ii;
                break;
            }
        }
        LVPSA_SetBPFiltersType(pInst, &Params);
        LVPSA_SetBPFCoefficients(pInst, &Params);
        LVPSA_SetQPFCoefficients(pInst, &Params);
        LVPSA_ClearFilterHistory(pInst);
        pInst->nSamplesBufferUpdate = (LVM_UINT16)LVPSA_nSamplesBufferUpdate[Params.Fs];
        pInst->BufferUpdateSamplesCount = 0;
        pInst->DownSamplingFactor = LVPSA_DownSamplingFactor[Params.Fs];
        pInst->DownSamplingCount = 0;
        for(ii = 0; ii < (pInst->nBands * pInst->SpectralDataBufferLength); ii++)
        {
            pInst->pSpectralDataBufferStart[ii] = 0;
        }
        for(ii = 0; ii < pInst->nBands; ii++)
        {
            pInst->pPreviousPeaks[ii] = 0;
        }
    }
    else
    {
        if(Params.LevelDetectionSpeed != pInst->CurrentParams.LevelDetectionSpeed)
        {
            LVPSA_SetQPFCoefficients(pInst, &Params);
        }
    }

    pInst->CurrentParams = pInst->NewParams;

    return (LVPSA_OK);
}
/************************************************************************************/
/*                                                                                  */
/* FUNCTION:            LVPSA_SetBPFiltersType                                      */
/*                                                                                  */
/* DESCRIPTION:                                                                     */
/*  Sets the filter type based on the BPFilterType.                                 */
/*                                                                                  */
/* PARAMETERS:                                                                      */
/*  pInst               Pointer to the instance                                     */
/*  pParams             Poniter to conrol parameters                                */
/*                                                                                  */
/* RETURNS:                                                                         */
/*  LVPSA_OK            Always succeeds                                             */
/*                                                                                  */
/* NOTES:                                                                           */
/*  1. To select the biquad type the follow rules are applied:                      */
/*          Double precision    if (fc <= fs/110)                                   */
/*          Double precision    if (fs/110 < fc < fs/85) & (Q>3)                    */
/*          Single precision    otherwise                                           */
/*                                                                                  */
/************************************************************************************/
LVPSA_RETURN LVPSA_SetBPFiltersType (   LVPSA_InstancePr_t        *pInst,
                                        LVPSA_ControlParams_t      *pParams  )
{

    extern LVM_UINT16   LVPSA_SampleRateTab[];                                            /* Sample rate table */
    LVM_UINT16          ii;                                                         /* Filter band index */
    LVM_UINT32          fs = (LVM_UINT32)LVPSA_SampleRateTab[(LVM_UINT16)pParams->Fs];      /* Sample rate */
    LVM_UINT32          fc;                                                         /* Filter centre frequency */
    LVM_INT16           QFactor;                                                    /* Filter Q factor */

    for (ii = 0; ii < pInst->nRelevantFilters; ii++)
    {
        /*
         * Get the filter settings
         */
        fc = (LVM_UINT32)pInst->pFiltersParams[ii].CenterFrequency;     /* Get the band centre frequency */
        QFactor =(LVM_INT16) pInst->pFiltersParams[ii].QFactor;                    /* Get the band Q factor */


        /*
         * For each filter set the type of biquad required
         */
        pInst->pBPFiltersPrecision[ii] = LVPSA_SimplePrecisionFilter;     /* Default to single precision */
        if ((LOW_FREQ * fs) >= (fc << 15))
        {
            /*
             * fc <= fs/110
             */
            pInst->pBPFiltersPrecision[ii] = LVPSA_DoublePrecisionFilter;
        }
        else
        {
            if (((LOW_FREQ * fs) < (fc << 15)) && ((fc << 15) < (HIGH_FREQ * fs)) && (QFactor > 300))
            {
                /*
                * (fs/110 < fc < fs/85) & (Q>3)
                */
                pInst->pBPFiltersPrecision[ii] = LVPSA_DoublePrecisionFilter;
            }
        }
    }

    return(LVPSA_OK);
}

/************************************************************************************/
/*                                                                                  */
/* FUNCTION:            LVPSA_SetBPFCoefficients                                    */
/*                                                                                  */
/* DESCRIPTION:                                                                     */
/*  Sets the band pass filter coefficients. This uses the type to select            */
/*  single or double precision coefficients.                                        */
/*                                                                                  */
/* PARAMETERS:                                                                      */
/*  pInst               Pointer to the instance                                     */
/*  Params              Initialisation parameters                                   */
/*                                                                                  */
/* RETURNS:                                                                         */
/*  LVPSA_OK            Always succeeds                                             */
/*                                                                                  */
/* NOTES:                                                                           */
/*                                                                                  */
/************************************************************************************/
LVPSA_RETURN LVPSA_SetBPFCoefficients(  LVPSA_InstancePr_t        *pInst,
                                        LVPSA_ControlParams_t      *pParams)
{

    LVM_UINT16                      ii;

    /*
     * Set the coefficients for each band by the init function
     */
    for (ii = 0; ii < pInst->nRelevantFilters; ii++)
    {
        switch  (pInst->pBPFiltersPrecision[ii])
        {
            case    LVPSA_DoublePrecisionFilter:
            {
                BP_C32_Coefs_t      Coefficients;

                /*
                 * Calculate the double precision coefficients
                 */
                LVPSA_BPDoublePrecCoefs((LVM_UINT16)pParams->Fs,
                                       &pInst->pFiltersParams[ii],
                                       &Coefficients);

                /*
                 * Set the coefficients
                 */
                BP_1I_D16F32Cll_TRC_WRA_01_Init ( &pInst->pBP_Instances[ii],
                                                  &pInst->pBP_Taps[ii],
                                                  &Coefficients);
                break;
            }

            case    LVPSA_SimplePrecisionFilter:
            {
                BP_C16_Coefs_t      Coefficients;

                /*
                 * Calculate the single precision coefficients
                 */
                LVPSA_BPSinglePrecCoefs((LVM_UINT16)pParams->Fs,
                                       &pInst->pFiltersParams[ii],
                                       &Coefficients);

                /*
                 * Set the coefficients
                 */
                BP_1I_D16F16Css_TRC_WRA_01_Init ( &pInst->pBP_Instances[ii],
                                                  &pInst->pBP_Taps[ii],
                                                  &Coefficients);
                break;
            }
        }
    }

    return(LVPSA_OK);
}


/************************************************************************************/
/*                                                                                  */
/* FUNCTION:            LVPSA_SetQPFCoefficients                                    */
/*                                                                                  */
/* DESCRIPTION:                                                                     */
/*  Sets the quasi peak filters coefficients. This uses the chosen                  */
/*  LevelDetectionSpeed from the control parameters.                                */
/*                                                                                  */
/* PARAMETERS:                                                                      */
/*  pInst               Pointer to the instance                                     */
/*  Params              Control parameters                                          */
/*                                                                                  */
/* RETURNS:                                                                         */
/*  LVPSA_OK            Always succeeds                                             */
/*                                                                                  */
/* NOTES:                                                                           */
/*                                                                                  */
/************************************************************************************/
LVPSA_RETURN LVPSA_SetQPFCoefficients(   LVPSA_InstancePr_t        *pInst,
                                         LVPSA_ControlParams_t      *pParams  )
{
    LVM_UINT16     ii;
    LVM_Fs_en      Fs = pParams->Fs;
    QPD_C32_Coefs  *pCoefficients;
    extern         QPD_C32_Coefs     LVPSA_QPD_Coefs[];


    pCoefficients = &LVPSA_QPD_Coefs[(pParams->LevelDetectionSpeed * LVPSA_NR_SUPPORTED_RATE) + Fs];


    for (ii = 0; ii < pInst->nRelevantFilters; ii++)
    {
            LVPSA_QPD_Init (&pInst->pQPD_States[ii],
                            &pInst->pQPD_Taps[ii],
                            pCoefficients );
    }

    return(LVPSA_OK);

}

/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                 LVPSA_BPSinglePrecCoefs                                    */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  Calculate single precision coefficients for a band pass filter                      */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  Fs                       Sampling frequency index                                   */
/*  pFilterParams            Pointer to the filter definition                           */
/*  pCoefficients            Pointer to the coefficients                                */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVPSA_OK         Always succeeds                                                    */
/*                                                                                      */
/* NOTES:                                                                               */
/*  1. The equations used are as follows:                                               */
/*                                                                                      */
/*      t0 = 2 * Pi * Fc / Fs                                                           */
/*                                                                                      */
/*      b2 = -0.5 * (2Q - t0) / (2Q + t0)                                               */
/*      b1 = (0.5 - b2) * cos(t0)                                                       */
/*      a0 = (0.5 + b2) / 2                                                             */
/*                                                                                      */
/*  Where:                                                                              */
/*      Fc          is the centre frequency, DC to Nyquist                              */
/*      Fs          is the sample frequency, 8000 to 48000 in descrete steps            */
/*      Q           is the Q factor, 0.25 to 12                                         */
/*                                                                                      */
/*  2. This function is entirely based on the LVEQNB_SinglePrecCoefs function           */
/*     of the n bands equalizer (LVEQNB                                                 */
/*                                                                                      */
/****************************************************************************************/
LVPSA_RETURN LVPSA_BPSinglePrecCoefs(    LVM_UINT16              Fs,
                                         LVPSA_FilterParam_t    *pFilterParams,
                                         BP_C16_Coefs_t         *pCoefficients)
{

    extern LVM_INT16    LVPSA_TwoPiOnFsTable[];
    extern LVM_INT16    LVPSA_CosCoef[];


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
     * Get the filter definition
     */
    LVM_UINT16          Frequency   = pFilterParams->CenterFrequency;
    LVM_UINT16          QFactor     = pFilterParams->QFactor;


    /*
     * Calculating the intermediate values
     */
    T0 = (LVM_INT32)Frequency * LVPSA_TwoPiOnFsTable[Fs];   /* T0 = 2 * Pi * Fc / Fs */
    D = 3200;                                               /* Floating point value 1.000000 (1*100*2^5) */
                                                            /* Force D = 1 : the function was originally used for a peaking filter.
                                                               The D parameter do not exist for a BandPass filter coefficients */

    /*
     * Calculate the B2 coefficient
     */
    Dt0 = D * (T0 >> 10);
    B2_Den = (LVM_INT32)(((LVM_UINT32)QFactor << 19) + (LVM_UINT32)(Dt0 >> 2));
    B2_Num = (LVM_INT32)((LVM_UINT32)(Dt0 >> 3) - ((LVM_UINT32)QFactor << 18));
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
        coef = LVPSA_CosCoef[i];                /* Get the nth coefficient */
        COS_T0 += (factor * coef) >> 5;         /* The nth partial sum */
        factor = (factor * t0) >> 15;           /* Calculate t0^n */
    }
    COS_T0 = COS_T0 << (LVPSA_CosCoef[0]+6);          /* Correct the scaling */


    B1 = ((0x40000000 - B2) >> 16) * (COS_T0 >> 16);    /* B1 = (0.5 - b2) * cos(t0) */
    A0 = (0x40000000 + B2) >> 1;                        /* A0 = (0.5 + b2) / 2 */

    /*
     * Write coeff into the data structure
     */
    pCoefficients->A0 = (LVM_INT16)(A0>>16);
    pCoefficients->B1 = (LVM_INT16)(B1>>15);
    pCoefficients->B2 = (LVM_INT16)(B2>>16);


    return(LVPSA_OK);
}

/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                 LVPSA_BPDoublePrecCoefs                                    */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  Calculate double precision coefficients for a band pass filter                      */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  Fs                       Sampling frequency index                                   */
/*  pFilterParams            Pointer to the filter definition                           */
/*  pCoefficients            Pointer to the coefficients                                */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVPSA_OK                 Always succeeds                                            */
/*                                                                                      */
/* NOTES:                                                                               */
/*  1. The equations used are as follows:                                               */
/*                                                                                      */
/*      t0 = 2 * Pi * Fc / Fs                                                           */
/*                                                                                      */
/*      b2 = -0.5 * (2Q - t0) / (2Q + t0)                                               */
/*      b1 = (0.5 - b2) * (1 - coserr(t0))                                              */
/*      a0 = (0.5 + b2) / 2                                                             */
/*                                                                                      */
/*  Where:                                                                              */
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
/*  4. This function is entirely based on the LVEQNB_DoublePrecCoefs function           */
/*     of the n bands equalizer (LVEQNB                                                 */
/*                                                                                      */
/****************************************************************************************/
LVPSA_RETURN LVPSA_BPDoublePrecCoefs(   LVM_UINT16            Fs,
                                        LVPSA_FilterParam_t  *pFilterParams,
                                        BP_C32_Coefs_t       *pCoefficients)
{

    extern LVM_INT16    LVPSA_TwoPiOnFsTable[];
    extern LVM_INT16    LVPSA_DPCosCoef[];

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
     * Get the filter definition
     */
    LVM_UINT16          Frequency   = pFilterParams->CenterFrequency;
    LVM_UINT16          QFactor     = pFilterParams->QFactor;


    /*
     * Calculating the intermediate values
     */
    T0 = (LVM_INT32)Frequency * LVPSA_TwoPiOnFsTable[Fs];   /* T0 = 2 * Pi * Fc / Fs */
    D = 3200;                                               /* Floating point value 1.000000 (1*100*2^5) */
                                                            /* Force D = 1 : the function was originally used for a peaking filter.
                                                               The D parameter do not exist for a BandPass filter coefficients */

    /*
     * Calculate the B2 coefficient
     */
    Dt0 = D * (T0 >> 10);
    B2_Den = (LVM_INT32)(((LVM_UINT32)QFactor << 19) + (LVM_UINT32)(Dt0 >> 2));
    B2_Num = (LVM_INT32)((LVM_UINT32)(Dt0 >> 3) - ((LVM_UINT32)QFactor << 18));
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
        coef = LVPSA_DPCosCoef[i];              /* Get the nth coefficient */
        CosErr += (factor * coef) >> 5;         /* The nth partial sum */
        factor = (factor * t0) >> 15;           /* Calculate t0^n */
    }
    CosErr = CosErr << (LVPSA_DPCosCoef[0]);          /* Correct the scaling */

    /*
     * Calculate the B1 and A0 coefficients
     */
    B1 = (0x40000000 - B2);                     /* B1 = (0.5 - b2) */
    A0 = ((B1 >> 16) * (CosErr >> 10)) >> 6;    /* Temporary storage for (0.5 - b2) * coserr(t0) */
    B1 -= A0;                                   /* B1 = (0.5 - b2) * (1 - coserr(t0))  */
    A0 = (0x40000000 + B2) >> 1;                /* A0 = (0.5 + b2) / 2 */

    /*
     * Write coeff into the data structure
     */
    pCoefficients->A0 = A0;
    pCoefficients->B1 = B1;
    pCoefficients->B2 = B2;

    return(LVPSA_OK);
}

/************************************************************************************/
/*                                                                                  */
/* FUNCTION:            LVPSA_ClearFilterHistory                                    */
/*                                                                                  */
/* DESCRIPTION:                                                                     */
/*  Clears the filters' data history                                                */
/*                                                                                  */
/* PARAMETERS:                                                                      */
/*  pInst           Pointer to the instance                                         */
/*                                                                                  */
/* RETURNS:                                                                         */
/*  LVPSA_OK         Always succeeds                                                */
/*                                                                                  */
/* NOTES:                                                                           */
/*                                                                                  */
/************************************************************************************/
LVPSA_RETURN LVPSA_ClearFilterHistory(LVPSA_InstancePr_t        *pInst)
{
    LVM_INT8       *pTapAddress;
    LVM_UINT32       i;

    /* Band Pass filters taps */
    pTapAddress = (LVM_INT8 *)pInst->pBP_Taps;
    for(i = 0; i < pInst->nBands * sizeof(Biquad_1I_Order2_Taps_t); i++)
    {
        pTapAddress[i] = 0;
    }

    /* Quasi-peak filters taps */
    pTapAddress = (LVM_INT8 *)pInst->pQPD_Taps;
    for(i = 0; i < pInst->nBands * sizeof(QPD_Taps_t); i++)
    {
        pTapAddress[i] = 0;
    }

    return(LVPSA_OK);
}

