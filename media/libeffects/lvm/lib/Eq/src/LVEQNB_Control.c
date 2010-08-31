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
/*  Includes                                                                            */
/*                                                                                      */
/****************************************************************************************/

#include "LVEQNB.h"
#include "LVEQNB_Private.h"
#include "VectorArithmetic.h"
#include "BIQUAD.h"


/****************************************************************************************/
/*                                                                                      */
/*  Defines                                                                             */
/*                                                                                      */
/****************************************************************************************/

#define     LOW_FREQ            298             /* 32768/110 for low test frequency */
#define     HIGH_FREQ           386             /* 32768/85 for high test frequency */

/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                 LVEQNB_GetParameters                                       */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  Request the N-Band equaliser parameters. The current parameter set is returned via  */
/*  the parameter pointer.                                                              */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  hInstance                Instance handle                                            */
/*  pParams                  Pointer to an empty parameter structure                    */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVEQNB_SUCCESS          Succeeds                                                    */
/*  LVEQNB_NULLADDRESS      Instance or pParams  is NULL pointer                        */
/*                                                                                      */
/* NOTES:                                                                               */
/*  1.  This function may be interrupted by the LVEQNB_Process function                 */
/*                                                                                      */
/****************************************************************************************/

LVEQNB_ReturnStatus_en LVEQNB_GetParameters(LVEQNB_Handle_t     hInstance,
                                            LVEQNB_Params_t     *pParams)
{

    LVEQNB_Instance_t    *pInstance =(LVEQNB_Instance_t  *)hInstance;

   /*
     * Check for error conditions
     */
    if((hInstance == LVM_NULL) || (pParams == LVM_NULL))
    {
        return LVEQNB_NULLADDRESS;
    }

    *pParams = pInstance->Params;

    return(LVEQNB_SUCCESS);
}


/************************************************************************************/
/*                                                                                  */
/* FUNCTION:                 LVEQNB_GetCapabilities                                 */
/*                                                                                  */
/* DESCRIPTION:                                                                     */
/*  Get the N-Band equaliser capabilities. The current capabilities are returned    */
/*  via the pointer.                                                                */
/*                                                                                  */
/* PARAMETERS:                                                                      */
/*  hInstance                Instance handle                                        */
/*  pCapabilities            Pointer to an empty capability structure               */
/*                                                                                  */
/* RETURNS:                                                                         */
/*  LVEQNB_Success           Succeeds                                               */
/*  LVEQNB_NULLADDRESS       hInstance or pCapabilities is NULL                     */
/*                                                                                  */
/* NOTES:                                                                           */
/*  1.  This function may be interrupted by the LVEQNB_Process function             */
/*                                                                                  */
/************************************************************************************/

LVEQNB_ReturnStatus_en LVEQNB_GetCapabilities(LVEQNB_Handle_t           hInstance,
                                              LVEQNB_Capabilities_t     *pCapabilities)
{

    LVEQNB_Instance_t    *pInstance =(LVEQNB_Instance_t  *)hInstance;

    if((hInstance == LVM_NULL) || (pCapabilities == LVM_NULL))
    {
        return LVEQNB_NULLADDRESS;
    }

    *pCapabilities = pInstance->Capabilities;

    return(LVEQNB_SUCCESS);
}


/************************************************************************************/
/*                                                                                  */
/* FUNCTION:            LVEQNB_SetFilters                                           */
/*                                                                                  */
/* DESCRIPTION:                                                                     */
/*  Sets the filter type based on the definition.                                   */
/*                                                                                  */
/* PARAMETERS:                                                                      */
/*  pInstance           Pointer to the instance                                     */
/*  pParams             Initialisation parameters                                   */
/*                                                                                  */
/* RETURNS:                                                                         */
/*  void                Nothing                                                     */
/*                                                                                  */
/* NOTES:                                                                           */
/*  1. To select the biquad type the follow rules are applied:                      */
/*          Double precision    if (fc <= fs/110)                                   */
/*          Double precision    if (fs/110 < fc < fs/85) & (Q>3)                    */
/*          Single precision    otherwise                                           */
/*                                                                                  */
/************************************************************************************/

void    LVEQNB_SetFilters(LVEQNB_Instance_t     *pInstance,
                          LVEQNB_Params_t       *pParams)
{

    extern const LVM_UINT16   LVEQNB_SampleRateTab[];           /* Sample rate table */
    LVM_UINT16          i;                                      /* Filter band index */
    LVM_UINT32          fs = (LVM_UINT32)LVEQNB_SampleRateTab[(LVM_UINT16)pParams->SampleRate];  /* Sample rate */
    LVM_UINT32          fc;                                     /* Filter centre frequency */
    LVM_INT16           QFactor;                                /* Filter Q factor */


    pInstance->NBands = pParams->NBands;

    for (i=0; i<pParams->NBands; i++)
    {
        /*
         * Get the filter settings
         */
        fc = (LVM_UINT32)pParams->pBandDefinition[i].Frequency;     /* Get the band centre frequency */
        QFactor = (LVM_INT16)pParams->pBandDefinition[i].QFactor;   /* Get the band Q factor */


        /*
         * For each filter set the type of biquad required
         */
        pInstance->pBiquadType[i] = LVEQNB_SinglePrecision;         /* Default to single precision */
        if ((fc << 15) <= (LOW_FREQ * fs))
        {
            /*
             * fc <= fs/110
             */
            pInstance->pBiquadType[i] = LVEQNB_DoublePrecision;
        }
        else if (((fc << 15) <= (HIGH_FREQ * fs)) && (QFactor > 300))
        {
            /*
             * (fs/110 < fc < fs/85) & (Q>3)
             */
            pInstance->pBiquadType[i] = LVEQNB_DoublePrecision;
        }


        /*
         * Check for out of range frequencies
         */
        if (fc > (fs >> 1))
        {
            pInstance->pBiquadType[i] = LVEQNB_OutOfRange;
        }


        /*
         * Copy the filter definition to persistant memory
         */
        pInstance->pBandDefinitions[i] = pParams->pBandDefinition[i];

    }
}


/************************************************************************************/
/*                                                                                  */
/* FUNCTION:            LVEQNB_SetCoefficients                                      */
/*                                                                                  */
/* DESCRIPTION:                                                                     */
/*  Sets the filter coefficients. This uses the type to select single or double     */
/*  precision coefficients.                                                         */
/*                                                                                  */
/* PARAMETERS:                                                                      */
/*  pInstance           Pointer to the instance                                     */
/*  pParams             Initialisation parameters                                   */
/*                                                                                  */
/************************************************************************************/

void    LVEQNB_SetCoefficients(LVEQNB_Instance_t     *pInstance)
{

    LVM_UINT16              i;                          /* Filter band index */
    LVEQNB_BiquadType_en    BiquadType;                 /* Filter biquad type */


    /*
     * Set the coefficients for each band by the init function
     */
    for (i=0; i<pInstance->Params.NBands; i++)
    {

        /*
         * Check band type for correct initialisation method and recalculate the coefficients
         */
        BiquadType = pInstance->pBiquadType[i];
        switch  (BiquadType)
        {
            case    LVEQNB_DoublePrecision:
            {
                PK_C32_Coefs_t      Coefficients;

                /*
                 * Calculate the double precision coefficients
                 */
                LVEQNB_DoublePrecCoefs((LVM_UINT16)pInstance->Params.SampleRate,
                                       &pInstance->pBandDefinitions[i],
                                       &Coefficients);

                /*
                 * Set the coefficients
                 */
                PK_2I_D32F32CllGss_TRC_WRA_01_Init(&pInstance->pEQNB_FilterState[i],
                                                   &pInstance->pEQNB_Taps[i],
                                                   &Coefficients);
                break;
            }

            case    LVEQNB_SinglePrecision:
            {
                PK_C16_Coefs_t      Coefficients;

                /*
                 * Calculate the single precision coefficients
                 */
                LVEQNB_SinglePrecCoefs((LVM_UINT16)pInstance->Params.SampleRate,
                                       &pInstance->pBandDefinitions[i],
                                       &Coefficients);

                /*
                 * Set the coefficients
                 */
                PK_2I_D32F32CssGss_TRC_WRA_01_Init(&pInstance->pEQNB_FilterState[i],
                                                   &pInstance->pEQNB_Taps[i],
                                                   &Coefficients);
                break;
            }
            default:
                break;
        }
    }

}


/************************************************************************************/
/*                                                                                  */
/* FUNCTION:            LVEQNB_ClearFilterHistory                                   */
/*                                                                                  */
/* DESCRIPTION:                                                                     */
/*  Clears the filter data history                                                  */
/*                                                                                  */
/* PARAMETERS:                                                                      */
/*  pInstance           Pointer to the instance                                     */
/*                                                                                  */
/************************************************************************************/

void    LVEQNB_ClearFilterHistory(LVEQNB_Instance_t     *pInstance)
{
    LVM_INT16       *pTapAddress;
    LVM_INT16       NumTaps;


    pTapAddress = (LVM_INT16 *)pInstance->pEQNB_Taps;
    NumTaps     = (LVM_INT16)((pInstance->Capabilities.MaxBands * sizeof(Biquad_2I_Order2_Taps_t))/sizeof(LVM_INT16));

    if (NumTaps != 0)
    {
        LoadConst_16(0,                                 /* Clear the history, value 0 */
                     pTapAddress,                       /* Destination */
                     NumTaps);                          /* Number of words */
    }
}


/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                LVEQNB_Control                                              */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  Sets or changes the LifeVibes module parameters.                                    */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  hInstance               Instance handle                                             */
/*  pParams                 Pointer to a parameter structure                            */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVEQNB_Success          Always succeeds                                             */
/*  LVEQNB_NULLADDRESS      Instance or pParams  is NULL pointer                        */
/*  LVEQNB_NULLADDRESS      NULL address for the equaliser filter definitions and the   */
/*                          number of bands is non-zero                                 */
/*                                                                                      */
/* NOTES:                                                                               */
/*  1.  This function may be interrupted by the LVEQNB_Process function                 */
/*                                                                                      */
/****************************************************************************************/

LVEQNB_ReturnStatus_en LVEQNB_Control(LVEQNB_Handle_t        hInstance,
                                      LVEQNB_Params_t        *pParams)
{

    LVEQNB_Instance_t    *pInstance = (LVEQNB_Instance_t  *)hInstance;
    LVM_INT16            bChange    = LVM_FALSE;
    LVM_INT16            i = 0;
    LVEQNB_Mode_en       OperatingModeSave ;

    /*
     * Check for error conditions
     */
    if((hInstance == LVM_NULL) || (pParams == LVM_NULL))
    {
        return LVEQNB_NULLADDRESS;
    }

    if((pParams->NBands !=0) && (pParams->pBandDefinition==LVM_NULL))
    {
        return LVEQNB_NULLADDRESS;
    }

    OperatingModeSave = pInstance->Params.OperatingMode;

    /* Set the alpha factor of the mixer */
    if (pParams->SampleRate != pInstance->Params.SampleRate)
    {
        LVC_Mixer_VarSlope_SetTimeConstant(&pInstance->BypassMixer.MixerStream[0],LVEQNB_BYPASS_MIXER_TC,(LVM_Fs_en)pParams->SampleRate,2);
        LVC_Mixer_VarSlope_SetTimeConstant(&pInstance->BypassMixer.MixerStream[1],LVEQNB_BYPASS_MIXER_TC,(LVM_Fs_en)pParams->SampleRate,2);
    }


    if( (pInstance->Params.NBands            !=  pParams->NBands          ) ||
        (pInstance->Params.OperatingMode     !=  pParams->OperatingMode   ) ||
        (pInstance->Params.pBandDefinition   !=  pParams->pBandDefinition ) ||
        (pInstance->Params.SampleRate        !=  pParams->SampleRate      ) ||
        (pInstance->Params.SourceFormat      !=  pParams->SourceFormat    ))
    {

        bChange = LVM_TRUE;
    }
    else
    {
        for(i = 0; i < pParams->NBands; i++)
        {

            if((pInstance->pBandDefinitions[i].Frequency  != pParams->pBandDefinition[i].Frequency )||
                (pInstance->pBandDefinitions[i].Gain       != pParams->pBandDefinition[i].Gain      )||
                (pInstance->pBandDefinitions[i].QFactor    != pParams->pBandDefinition[i].QFactor   ))
            {

                bChange = LVM_TRUE;
            }
        }
    }


    if(bChange){

        /*
         * If the sample rate has changed clear the history
         */
        if (pInstance->Params.SampleRate != pParams->SampleRate)
        {
            LVEQNB_ClearFilterHistory(pInstance);           /* Clear the history */
        }

        /*
         * Update the instance parameters
         */
        pInstance->Params = *pParams;


        /*
         * Reset the filters except if the algo is switched off
         */
        if(pParams->OperatingMode != LVEQNB_BYPASS){
            /*
             * Reset the filters as all parameters could have changed
             */
            LVEQNB_SetFilters(pInstance,                        /* Instance pointer */
                              pParams);                         /* New parameters */

            /*
             * Update the filters
             */
            LVEQNB_SetCoefficients(pInstance);                  /* Instance pointer */
        }

        if(pParams->OperatingMode != OperatingModeSave)
        {
            if(pParams->OperatingMode == LVEQNB_ON)
            {
                LVC_Mixer_SetTarget(&pInstance->BypassMixer.MixerStream[0],LVM_MAXINT_16);
                LVC_Mixer_SetTarget(&pInstance->BypassMixer.MixerStream[1],0);

                pInstance->BypassMixer.MixerStream[0].CallbackSet        = 1;
                pInstance->BypassMixer.MixerStream[1].CallbackSet        = 1;
            }
            else
            {
                /* Stay on the ON operating mode until the transition is done */
                pInstance->Params.OperatingMode = LVEQNB_ON;

                LVC_Mixer_SetTarget(&pInstance->BypassMixer.MixerStream[0],0);
                LVC_Mixer_SetTarget(&pInstance->BypassMixer.MixerStream[1],LVM_MAXINT_16);
                pInstance->BypassMixer.MixerStream[0].CallbackSet        = 1;
                pInstance->BypassMixer.MixerStream[1].CallbackSet        = 1;
            }
            LVC_Mixer_VarSlope_SetTimeConstant(&pInstance->BypassMixer.MixerStream[0],LVEQNB_BYPASS_MIXER_TC,(LVM_Fs_en)pParams->SampleRate,2);
            LVC_Mixer_VarSlope_SetTimeConstant(&pInstance->BypassMixer.MixerStream[1],LVEQNB_BYPASS_MIXER_TC,(LVM_Fs_en)pParams->SampleRate,2);

            pInstance->bInOperatingModeTransition = LVM_TRUE;
        }

    }
    return(LVEQNB_SUCCESS);
}


/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                LVEQNB_BypassMixerCallBack                                  */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  CallBack function of the mixer                                                      */
/*  transition                                                                          */
/*                                                                                      */
/****************************************************************************************/
LVM_INT32 LVEQNB_BypassMixerCallBack (void* hInstance,
                                      void *pGeneralPurpose,
                                      LVM_INT16 CallbackParam)
{
    LVEQNB_Instance_t      *pInstance =(LVEQNB_Instance_t  *)hInstance;
    LVM_Callback            CallBack  = pInstance->Capabilities.CallBack;

    (void) pGeneralPurpose;

     /*
      * Send an ALGOFF event if the ON->OFF switch transition is finished
      */
    if((LVC_Mixer_GetTarget(&pInstance->BypassMixer.MixerStream[0]) == 0x00000000) &&
       (CallbackParam == 0)){
        pInstance->Params.OperatingMode = LVEQNB_BYPASS;
        if (CallBack != LVM_NULL){
            CallBack(pInstance->Capabilities.pBundleInstance, LVM_NULL, ALGORITHM_EQNB_ID|LVEQNB_EVENT_ALGOFF);
        }
    }

    /*
     *  Exit transition state
     */
    pInstance->bInOperatingModeTransition = LVM_FALSE;

    return 1;
}






