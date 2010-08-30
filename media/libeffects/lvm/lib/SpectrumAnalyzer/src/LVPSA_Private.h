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

#ifndef _LVPSA_PRIVATE_H_
#define _LVPSA_PRIVATE_H_

#include "LVPSA.h"
#include "BIQUAD.h"
#include "LVPSA_QPD.h"
#include "LVM_Macros.h"



#ifdef __cplusplus
extern "C" {
#endif /* __cplusplus */

/**********************************************************************************
   CONSTANT DEFINITIONS
***********************************************************************************/

/* Memory */
#define LVPSA_INSTANCE_ALIGN             4      /* 32-bit alignment for structures                                  */
#define LVPSA_SCRATCH_ALIGN              4      /* 32-bit alignment for long data                                   */
#define LVPSA_COEF_ALIGN                 4      /* 32-bit alignment for long words                                  */
#define LVPSA_DATA_ALIGN                 4      /* 32-bit alignment for long data                                   */

#define LVPSA_MEMREGION_INSTANCE         0      /* Offset to instance memory region in memory table                 */
#define LVPSA_MEMREGION_PERSISTENT_COEF  1      /* Offset to persistent coefficients  memory region in memory table */
#define LVPSA_MEMREGION_PERSISTENT_DATA  2      /* Offset to persistent taps  memory region in memory table         */
#define LVPSA_MEMREGION_SCRATCH          3      /* Offset to scratch  memory region in memory table                 */

#define LVPSA_NR_SUPPORTED_RATE          9      /* From 8000Hz to 48000Hz                                           */
#define LVPSA_NR_SUPPORTED_SPEED         3      /* LOW, MEDIUM, HIGH                                                */

#define LVPSA_MAXBUFFERDURATION          4000   /* Maximum length in ms of the levels buffer                        */
#define LVPSA_MAXINPUTBLOCKSIZE          5000   /* Maximum length in mono samples of the block to process           */
#define LVPSA_NBANDSMIN                  1      /* Minimum number of frequency band                                 */
#define LVPSA_NBANDSMAX                  30     /* Maximum number of frequency band                                 */
#define LVPSA_MAXCENTERFREQ              20000  /* Maximum possible center frequency                                */
#define LVPSA_MINPOSTGAIN                -15    /* Minimum possible post gain                                       */
#define LVPSA_MAXPOSTGAIN                15     /* Maximum possible post gain                                       */
#define LVPSA_MINQFACTOR                 25     /* Minimum possible Q factor                                        */
#define LVPSA_MAXQFACTOR                 1200   /* Maximum possible Q factor                                        */

#define LVPSA_MAXLEVELDECAYFACTOR        0x4111 /* Decay factor for the maximum values calculation                  */
#define LVPSA_MAXLEVELDECAYSHIFT         14     /* Decay shift for the maximum values calculation                   */

#define LVPSA_MAXUNSIGNEDCHAR            0xFF

#define LVPSA_FsInvertShift              31
#define LVPSA_GAINSHIFT                  11
#define LVPSA_FREQSHIFT                  25

/**********************************************************************************
   TYPES DEFINITIONS
***********************************************************************************/

#define LVPSA_InternalRefreshTime       0x0014    /* 20 ms (50Hz) in Q16.0      */
#define LVPSA_InternalRefreshTimeInv    0x0666    /* 1/20ms left shifted by 15  */
#define LVPSA_InternalRefreshTimeShift  15


/* Precision of the filter */
typedef enum
{
    LVPSA_SimplePrecisionFilter,    /* Simple precision */
    LVPSA_DoublePrecisionFilter     /* Double precision */
} LVPSA_BPFilterPrecision_en;

typedef struct
{
    LVM_CHAR                    bControlPending;                    /* Flag incating a change of the control parameters                                             */
    LVM_UINT16                  nBands;                             /* Number of bands of the spectrum analyzer                                                     */
    LVM_UINT16                  MaxInputBlockSize;                  /* Maximum input data buffer size                                                               */

    LVPSA_ControlParams_t       CurrentParams;                      /* Current control parameters of the module                                                     */
    LVPSA_ControlParams_t       NewParams;                          /* New control parameters given by the user                                                     */
    LVPSA_MemTab_t              MemoryTable;

    LVPSA_BPFilterPrecision_en *pBPFiltersPrecision;                /* Points a nBands elements array that contains the filter precision for each band              */
    Biquad_Instance_t          *pBP_Instances;                      /* Points a nBands elements array that contains the band pass filter instance for each band     */
    Biquad_1I_Order2_Taps_t    *pBP_Taps;                           /* Points a nBands elements array that contains the band pass filter taps for each band         */
    QPD_State_t                *pQPD_States;                        /* Points a nBands elements array that contains the QPD filter instance for each band           */
    QPD_Taps_t                 *pQPD_Taps;                          /* Points a nBands elements array that contains the QPD filter taps for each band               */
    LVM_UINT16                 *pPostGains;                         /* Points a nBands elements array that contains the post-filter gains for each band             */

    LVPSA_FilterParam_t        *pFiltersParams;                     /* Copy of the filters parameters from the input parameters                                     */


    LVM_UINT16                  nSamplesBufferUpdate;               /* Number of samples to make 20ms                                                               */
    LVM_INT32                   BufferUpdateSamplesCount;           /* Counter used to know when to put a new value in the buffer                                   */
    LVM_UINT16                  nRelevantFilters;                   /* Number of relevent filters depending on sampling frequency and bands center frequency        */
    LVM_UINT16                  LocalSamplesCount;                  /* Counter used to update the SpectralDataBufferAudioTime                                       */

    LVM_UINT16                  DownSamplingFactor;                 /* Down sampling factor depending on the sampling frequency                                     */
    LVM_UINT16                  DownSamplingCount;                  /* Counter used for the downsampling handling                                                   */

    LVM_UINT16                  SpectralDataBufferDuration;         /* Length of the buffer in time (ms) defined by the application                                 */
    LVM_UINT8                  *pSpectralDataBufferStart;           /* Starting address of the buffer                                                               */
    LVM_UINT8                  *pSpectralDataBufferWritePointer;    /* Current position of the writting pointer of the buffer                                       */
    LVPSA_Time                  SpectralDataBufferAudioTime;        /* AudioTime at which the last value save occured in the buffer                                 */
    LVM_UINT32                  SpectralDataBufferLength;           /* Number of spectrum data value that the buffer can contain (per band)
                                                                       = SpectralDataBufferDuration/20ms                                                            */

    LVM_UINT8                  *pPreviousPeaks;                     /* Points to a nBands elements array that contains the previous peak value of the level
                                                                     detection. Those values are decremented after each call to the GetSpectrum function          */

}LVPSA_InstancePr_t, *pLVPSA_InstancePr_t;



/**********************************************************************************
   FUNCTIONS PROTOTYPE
***********************************************************************************/
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
/*  LVPSA_OK            Always succeeds                                             */
/*                                                                                  */
/************************************************************************************/
LVPSA_RETURN LVPSA_ApplyNewSettings (LVPSA_InstancePr_t     *pInst);

#ifdef __cplusplus
}
#endif /* __cplusplus */

#endif /* _LVPSA_PRIVATE_H */
