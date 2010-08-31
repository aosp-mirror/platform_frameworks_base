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
/*  Header file for the application layer interface of Concert Sound, Bass Enhancement, */
/*  Equalizer, Power Spectrum Analyzer, Trebble Enhancement and volume management       */
/*  bundle.                                                                             */
/*                                                                                      */
/*  This files includes all definitions, types, structures and function                 */
/*  prototypes required by the calling layer. All other types, structures and           */
/*  functions are private.                                                              */
/*                                                                                      */
/****************************************************************************************/
/*                                                                                      */
/*  Note: 1                                                                             */
/*  =======                                                                             */
/*  The algorithm can execute either with separate input and output buffers or with     */
/*  a common buffer, i.e. the data is processed in-place.                               */
/*                                                                                      */
/****************************************************************************************/
/*                                                                                      */
/*  Note: 2                                                                             */
/*  =======                                                                             */
/*  Three data formats are support Stereo,Mono-In-Stereo and Mono. The data is          */
/*  interleaved as follows:                                                             */
/*                                                                                      */
/*  Byte Offset         Stereo Input         Mono-In-Stereo Input       Mono Input      */
/*  ===========         ============         ====================     ==============    */
/*      0               Left Sample #1          Mono Sample #1        Mono Sample #1    */
/*      2               Right Sample #1         Mono Sample #1        Mono Sample #2    */
/*      4               Left Sample #2          Mono Sample #2        Mono Sample #3    */
/*      6               Right Sample #2         Mono Sample #2        Mono Sample #4    */
/*      .                      .                     .                     .            */
/*      .                      .                     .                     .            */
/*                                                                                      */
/****************************************************************************************/

#ifndef __LVM_H__
#define __LVM_H__

#ifdef __cplusplus
extern "C" {
#endif /* __cplusplus */


/****************************************************************************************/
/*                                                                                      */
/*  Includes                                                                            */
/*                                                                                      */
/****************************************************************************************/

#include "LVM_Types.h"


/****************************************************************************************/
/*                                                                                      */
/*  Definitions                                                                         */
/*                                                                                      */
/****************************************************************************************/

/* Memory table*/
#define LVM_NR_MEMORY_REGIONS                 4     /* Number of memory regions */

/* Concert Sound effect level presets */
#define LVM_CS_EFFECT_NONE                    0     /* 0% effect, minimum value */
#define LVM_CS_EFFECT_LOW                 16384     /* 50% effect */
#define LVM_CS_EFFECT_MED                 24576     /* 75% effect */
#define LVM_CS_EFFECT_HIGH                32767     /* 100% effect, maximum value */

/* Treble enhancement */
#define LVM_TE_LOW_MIPS                   32767

/* Bass enhancement effect level presets */
#define LVM_BE_0DB                            0     /* 0dB boost, no effect */
#define LVM_BE_3DB                            3     /* +3dB boost */
#define LVM_BE_6DB                            6     /* +6dB boost */
#define LVM_BE_9DB                            9     /* +9dB boost */
#define LVM_BE_12DB                          12     /* +12dB boost */
#define LVM_BE_15DB                          15     /* +15dB boost */

/* N-Band Equalizer */
#define LVM_EQ_NBANDS                         5    /* Number of bands for equalizer */

/* Headroom management */
#define LVM_HEADROOM_MAX_NBANDS               5

/****************************************************************************************/
/*                                                                                      */
/*  Types                                                                               */
/*                                                                                      */
/****************************************************************************************/

/* Instance handle */
typedef void *LVM_Handle_t;


/* Status return values */
typedef enum
{
    LVM_SUCCESS            = 0,                     /* Successful return from a routine */
    LVM_ALIGNMENTERROR     = 1,                     /* Memory alignment error */
    LVM_NULLADDRESS        = 2,                     /* NULL allocation address */
    LVM_OUTOFRANGE         = 3,                     /* Out of range control parameter */
    LVM_INVALIDNUMSAMPLES  = 4,                     /* Invalid number of samples */
    LVM_WRONGAUDIOTIME     = 5,                     /* Wrong time value for audio time*/
    LVM_ALGORITHMDISABLED  = 6,                     /* Algorithm is disabled*/
    LVM_ALGORITHMPSA       = 7,                     /* Algorithm PSA returns an error */
    LVM_RETURNSTATUS_DUMMY = LVM_MAXENUM
} LVM_ReturnStatus_en;


/* Buffer Management mode */
typedef enum
{
    LVM_MANAGED_BUFFERS   = 0,
    LVM_UNMANAGED_BUFFERS = 1,
    LVM_BUFFERS_DUMMY     = LVM_MAXENUM
} LVM_BufferMode_en;

/* Output device type */
typedef enum
{
    LVM_HEADPHONES             = 0,
    LVM_EX_HEADPHONES          = 1,
    LVM_SPEAKERTYPE_MAX        = LVM_MAXENUM
} LVM_OutputDeviceType_en;

/* Virtualizer mode selection*/
typedef enum
{
    LVM_CONCERTSOUND       = 0,
    LVM_VIRTUALIZERTYPE_DUMMY   = LVM_MAXENUM
} LVM_VirtualizerType_en;

/* N-Band Equaliser operating mode */
typedef enum
{
    LVM_EQNB_OFF   = 0,
    LVM_EQNB_ON    = 1,
    LVM_EQNB_DUMMY = LVM_MAXENUM
} LVM_EQNB_Mode_en;

/* Bass Enhancement operating mode */
typedef enum
{
    LVM_BE_OFF   = 0,
    LVM_BE_ON    = 1,
    LVM_BE_DUMMY = LVM_MAXENUM
} LVM_BE_Mode_en;

/* Bass Enhancement centre frequency selection control */
typedef enum
{
    LVM_BE_CENTRE_55Hz  = 0,
    LVM_BE_CENTRE_66Hz  = 1,
    LVM_BE_CENTRE_78Hz  = 2,
    LVM_BE_CENTRE_90Hz  = 3,
    LVM_BE_CENTRE_DUMMY = LVM_MAXENUM
} LVM_BE_CentreFreq_en;

/* Bass Enhancement HPF selection control */
typedef enum
{
    LVM_BE_HPF_OFF   = 0,
    LVM_BE_HPF_ON    = 1,
    LVM_BE_HPF_DUMMY = LVM_MAXENUM
} LVM_BE_FilterSelect_en;

/* Volume Control operating mode */
typedef enum
{
    LVM_VC_OFF   = 0,
    LVM_VC_ON    = 1,
    LVM_VC_DUMMY = LVM_MAXENUM
} LVM_VC_Mode_en;

/* Treble Enhancement operating mode */
typedef enum
{
    LVM_TE_OFF   = 0,
    LVM_TE_ON    = 1,
    LVM_TE_DUMMY = LVM_MAXENUM
} LVM_TE_Mode_en;

/* Headroom management operating mode */
typedef enum
{
    LVM_HEADROOM_OFF   = 0,
    LVM_HEADROOM_ON    = 1,
    LVM_Headroom_DUMMY = LVM_MAXENUM
} LVM_Headroom_Mode_en;

typedef enum
{
    LVM_PSA_SPEED_SLOW,                                  /* Peak decaying at slow speed */
    LVM_PSA_SPEED_MEDIUM,                                /* Peak decaying at medium speed */
    LVM_PSA_SPEED_FAST,                                  /* Peak decaying at fast speed */
    LVM_PSA_SPEED_DUMMY = LVM_MAXENUM
} LVM_PSA_DecaySpeed_en;

typedef enum
{
    LVM_PSA_OFF   = 0,
    LVM_PSA_ON    = 1,
    LVM_PSA_DUMMY = LVM_MAXENUM
} LVM_PSA_Mode_en;

/* Version information */
typedef struct
{
    LVM_CHAR                    *pVersionNumber;        /* Pointer to the version number in the format X.YY.ZZ */
    LVM_CHAR                    *pPlatform;             /* Pointer to the library platform type */
} LVM_VersionInfo_st;


/****************************************************************************************/
/*                                                                                      */
/*  Structures                                                                          */
/*                                                                                      */
/****************************************************************************************/

/* Memory table containing the region definitions */
typedef struct
{
    LVM_MemoryRegion_st         Region[LVM_NR_MEMORY_REGIONS];  /* One definition for each region */
} LVM_MemTab_t;

/* N-Band equaliser band definition */
typedef struct
{
    LVM_INT16                   Gain;                   /* Band gain in dB */
    LVM_UINT16                  Frequency;              /* Band centre frequency in Hz */
    LVM_UINT16                  QFactor;                /* Band quality factor (x100) */
} LVM_EQNB_BandDef_t;


/* Headroom band definition */
typedef struct
{
    LVM_UINT16                  Limit_Low;              /* Low frequency limit of the band in Hertz */
    LVM_UINT16                  Limit_High;             /* High frequency limit of the band in Hertz */
    LVM_INT16                   Headroom_Offset;        /* Headroom = biggest band gain - Headroom_Offset */
} LVM_HeadroomBandDef_t;


/* Control Parameter structure */
typedef struct
{
    /* General parameters */
    LVM_Mode_en                 OperatingMode;          /* Bundle operating mode On/Bypass */
    LVM_Fs_en                   SampleRate;             /* Sample rate */
    LVM_Format_en               SourceFormat;           /* Input data format */
    LVM_OutputDeviceType_en     SpeakerType;            /* Output device type */

    /* Concert Sound Virtualizer parameters*/
    LVM_Mode_en                 VirtualizerOperatingMode; /* Virtualizer operating mode On/Off */
    LVM_VirtualizerType_en      VirtualizerType;          /* Virtualizer type: ConcertSound */
    LVM_UINT16                  VirtualizerReverbLevel;   /* Virtualizer reverb level in % */
    LVM_INT16                   CS_EffectLevel;           /* Concert Sound effect level */

    /* N-Band Equaliser parameters */
    LVM_EQNB_Mode_en            EQNB_OperatingMode;     /* N-Band Equaliser operating mode */
    LVM_UINT16                  EQNB_NBands;            /* Number of bands */
    LVM_EQNB_BandDef_t          *pEQNB_BandDefinition;  /* Pointer to equaliser definitions */

    /* Bass Enhancement parameters */
    LVM_BE_Mode_en              BE_OperatingMode;       /* Bass Enhancement operating mode */
    LVM_INT16                   BE_EffectLevel;         /* Bass Enhancement effect level */
    LVM_BE_CentreFreq_en        BE_CentreFreq;          /* Bass Enhancement centre frequency */
    LVM_BE_FilterSelect_en      BE_HPF;                 /* Bass Enhancement high pass filter selector */

    /* Volume Control parameters */
    LVM_INT16                   VC_EffectLevel;         /* Volume Control setting in dBs */
    LVM_INT16                   VC_Balance;             /* Left Right Balance control in dB (-96 to 96 dB), -ve values reduce
                                                           Right channel while +ve value reduces Left channel*/

    /* Treble Enhancement parameters */
    LVM_TE_Mode_en              TE_OperatingMode;       /* Treble Enhancement On/Off */
    LVM_INT16                   TE_EffectLevel;         /* Treble Enhancement gain dBs */

    /* Spectrum Analyzer parameters Control */
    LVM_PSA_Mode_en             PSA_Enable;
    LVM_PSA_DecaySpeed_en       PSA_PeakDecayRate;      /* Peak value decay rate*/

} LVM_ControlParams_t;


/* Instance Parameter structure */
typedef struct
{
    /* General */
    LVM_BufferMode_en           BufferMode;             /* Buffer management mode */
    LVM_UINT16                  MaxBlockSize;           /* Maximum processing block size */

    /* N-Band Equaliser */
    LVM_UINT16                  EQNB_NumBands;          /* Maximum number of equaliser bands */

    /* PSA */
    LVM_PSA_Mode_en             PSA_Included;            /* Controls the instance memory allocation for PSA: ON/OFF */
} LVM_InstParams_t;

/* Headroom management parameter structure */
typedef struct
{
    LVM_Headroom_Mode_en        Headroom_OperatingMode; /* Headroom Control On/Off */
    LVM_HeadroomBandDef_t       *pHeadroomDefinition;   /* Pointer to headroom bands definition */
    LVM_UINT16                  NHeadroomBands;         /* Number of headroom bands */

} LVM_HeadroomParams_t;

/****************************************************************************************/
/*                                                                                      */
/*  Function Prototypes                                                                 */
/*                                                                                      */
/****************************************************************************************/


/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                LVM_GetVersionInfo                                          */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  This function is used to retrieve information about the library's version.          */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  pVersion                Pointer to an empty version info structure                  */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVM_SUCCESS             Succeeded                                                   */
/*  LVM_NULLADDRESS         when pVersion is NULL                                       */
/*                                                                                      */
/* NOTES:                                                                               */
/*  1.  This function may be interrupted by the LVM_Process function                    */
/*                                                                                      */
/****************************************************************************************/
LVM_ReturnStatus_en LVM_GetVersionInfo(LVM_VersionInfo_st  *pVersion);


/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                LVM_GetMemoryTable                                          */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  This function is used for memory allocation and free. It can be called in           */
/*  two ways:                                                                           */
/*                                                                                      */
/*      hInstance = NULL                Returns the memory requirements                 */
/*      hInstance = Instance handle     Returns the memory requirements and             */
/*                                      allocated base addresses for the instance       */
/*                                                                                      */
/*  When this function is called for memory allocation (hInstance=NULL) the memory      */
/*  base address pointers are NULL on return.                                           */
/*                                                                                      */
/*  When the function is called for free (hInstance = Instance Handle) the memory       */
/*  table returns the allocated memory and base addresses used during initialisation.   */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  hInstance               Instance Handle                                             */
/*  pMemoryTable            Pointer to an empty memory definition table                 */
/*  pInstParams             Pointer to the instance parameters                          */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVM_SUCCESS             Succeeded                                                   */
/*  LVM_NULLADDRESS         When one of pMemoryTable or pInstParams is NULL             */
/*  LVM_OUTOFRANGE          When any of the Instance parameters are out of range        */
/*                                                                                      */
/* NOTES:                                                                               */
/*  1.  This function may be interrupted by the LVM_Process function                    */
/*                                                                                      */
/****************************************************************************************/
LVM_ReturnStatus_en LVM_GetMemoryTable(LVM_Handle_t         hInstance,
                                       LVM_MemTab_t         *pMemoryTable,
                                       LVM_InstParams_t     *pInstParams);


/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                LVM_GetInstanceHandle                                       */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  This function is used to create a bundle instance. It returns the created instance  */
/*  handle through phInstance. All parameters are set to their default, inactive state. */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  phInstance              pointer to the instance handle                              */
/*  pMemoryTable            Pointer to the memory definition table                      */
/*  pInstParams             Pointer to the instance parameters                          */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVM_SUCCESS             Initialisation succeeded                                    */
/*  LVM_NULLADDRESS         One or more memory has a NULL pointer                       */
/*  LVM_OUTOFRANGE          When any of the Instance parameters are out of range        */
/*                                                                                      */
/* NOTES:                                                                               */
/*  1. This function must not be interrupted by the LVM_Process function                */
/*                                                                                      */
/****************************************************************************************/
LVM_ReturnStatus_en LVM_GetInstanceHandle(LVM_Handle_t        *phInstance,
                                          LVM_MemTab_t        *pMemoryTable,
                                          LVM_InstParams_t    *pInstParams);


/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                LVM_ClearAudioBuffers                                       */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  This function is used to clear the internal audio buffers of the bundle.            */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  hInstance               Instance handle                                             */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVM_SUCCESS             Initialisation succeeded                                    */
/*  LVM_NULLADDRESS         Instance memory has a NULL pointer                          */
/*                                                                                      */
/* NOTES:                                                                               */
/*  1. This function must not be interrupted by the LVM_Process function                */
/*                                                                                      */
/****************************************************************************************/
LVM_ReturnStatus_en LVM_ClearAudioBuffers(LVM_Handle_t  hInstance);


/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                 LVM_GetControlParameters                                   */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  Request the LifeVibes module parameters. The current parameter set is returned      */
/*  via the parameter pointer.                                                          */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  hInstance                Instance handle                                            */
/*  pParams                  Pointer to an empty parameter structure                    */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVM_SUCCESS             Succeeded                                                   */
/*  LVM_NULLADDRESS         when any of hInstance or pParams is NULL                    */
/*                                                                                      */
/* NOTES:                                                                               */
/*  1.  This function may be interrupted by the LVM_Process function                    */
/*                                                                                      */
/****************************************************************************************/
LVM_ReturnStatus_en LVM_GetControlParameters(LVM_Handle_t           hInstance,
                                             LVM_ControlParams_t    *pParams);


/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                LVM_SetControlParameters                                    */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  Sets or changes the LifeVibes module parameters.                                    */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  hInstance               Instance handle                                             */
/*  pParams                 Pointer to a parameter structure                            */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVM_SUCCESS             Succeeded                                                   */
/*  LVM_NULLADDRESS         When hInstance, pParams or any control pointers are NULL    */
/*  LVM_OUTOFRANGE          When any of the control parameters are out of range         */
/*                                                                                      */
/* NOTES:                                                                               */
/*  1.  This function may be interrupted by the LVM_Process function                    */
/*                                                                                      */
/****************************************************************************************/
LVM_ReturnStatus_en LVM_SetControlParameters(LVM_Handle_t           hInstance,
                                             LVM_ControlParams_t    *pParams);


/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                LVM_Process                                                 */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  Process function for the LifeVibes module.                                          */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  hInstance               Instance handle                                             */
/*  pInData                 Pointer to the input data                                   */
/*  pOutData                Pointer to the output data                                  */
/*  NumSamples              Number of samples in the input buffer                       */
/*  AudioTime               Audio Time of the current input data in milli-seconds       */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVM_SUCCESS            Succeeded                                                    */
/*  LVM_INVALIDNUMSAMPLES  When the NumSamples is not a valied multiple in unmanaged    */
/*                         buffer mode                                                  */
/*  LVM_ALIGNMENTERROR     When either the input our output buffers are not 32-bit      */
/*                         aligned in unmanaged mode                                    */
/*  LVM_NULLADDRESS        When one of hInstance, pInData or pOutData is NULL           */
/*                                                                                      */
/* NOTES:                                                                               */
/*  1. The input and output buffers must be 32-bit aligned                              */
/*  2. Number of samples is defined as follows:                                         */
/*      MONO                the number of samples in the block                          */
/*      MONOINSTEREO        the number of sample pairs in the block                     */
/*      STEREO              the number of sample pairs in the block                     */
/*                                                                                      */
/****************************************************************************************/
LVM_ReturnStatus_en LVM_Process(LVM_Handle_t                hInstance,
                                const LVM_INT16             *pInData,
                                LVM_INT16                   *pOutData,
                                LVM_UINT16                  NumSamples,
                                LVM_UINT32                  AudioTime);


/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                LVM_SetHeadroomParams                                       */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  This function is used to set the automatic headroom management parameters.          */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  hInstance               Instance Handle                                             */
/*  pHeadroomParams         Pointer to headroom parameter structure                     */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVM_NULLADDRESS         When hInstance or pHeadroomParams is NULL                   */
/*  LVM_SUCCESS             Succeeded                                                   */
/*                                                                                      */
/* NOTES:                                                                               */
/*  1.  This function may be interrupted by the LVM_Process function                    */
/*                                                                                      */
/****************************************************************************************/
LVM_ReturnStatus_en LVM_SetHeadroomParams(  LVM_Handle_t            hInstance,
                                            LVM_HeadroomParams_t    *pHeadroomParams);


/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                LVM_GetHeadroomParams                                       */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  This function is used to get the automatic headroom management parameters.          */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  hInstance               Instance Handle                                             */
/*  pHeadroomParams         Pointer to headroom parameter structure (output)            */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVM_SUCCESS             Succeeded                                                   */
/*  LVM_NULLADDRESS         When hInstance or pHeadroomParams are NULL                  */
/*                                                                                      */
/* NOTES:                                                                               */
/*  1.  This function may be interrupted by the LVM_Process function                    */
/*                                                                                      */
/****************************************************************************************/
LVM_ReturnStatus_en LVM_GetHeadroomParams(  LVM_Handle_t            hInstance,
                                            LVM_HeadroomParams_t    *pHeadroomParams);


/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                LVM_GetSpectrum                                             */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/* This function is used to retrieve Spectral information at a given Audio time         */
/* for display usage                                                                    */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  hInstance               Instance Handle                                             */
/*  pCurrentPeaks           Pointer to location where currents peaks are to be saved    */
/*  pPastPeaks              Pointer to location where past peaks are to be saved        */
/*  pCentreFreqs            Pointer to location where centre frequency of each band is  */
/*                          to be saved                                                 */
/*  AudioTime               Audio time at which the spectral information is needed      */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVM_SUCCESS             Succeeded                                                   */
/*  LVM_NULLADDRESS         If any of input addresses are NULL                          */
/*  LVM_WRONGAUDIOTIME      Failure due to audio time error                             */
/*                                                                                      */
/* NOTES:                                                                               */
/*  1. This function may be interrupted by the LVM_Process function                     */
/*                                                                                      */
/****************************************************************************************/
LVM_ReturnStatus_en LVM_GetSpectrum( LVM_Handle_t            hInstance,
                                     LVM_UINT8               *pCurrentPeaks,
                                     LVM_UINT8               *pPastPeaks,
                                     LVM_INT32               AudioTime);

/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                LVM_SetVolumeNoSmoothing                                    */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/* This function is used to set output volume without any smoothing                     */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  hInstance               Instance Handle                                             */
/*  pParams                 Control Parameters, only volume value is used here          */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVM_SUCCESS             Succeeded                                                   */
/*  LVM_NULLADDRESS         If any of input addresses are NULL                          */
/*  LVM_OUTOFRANGE          When any of the control parameters are out of range         */
/*                                                                                      */
/* NOTES:                                                                               */
/*  1. This function may be interrupted by the LVM_Process function                     */
/*                                                                                      */
/****************************************************************************************/
LVM_ReturnStatus_en LVM_SetVolumeNoSmoothing( LVM_Handle_t           hInstance,
                                              LVM_ControlParams_t    *pParams);


#ifdef __cplusplus
}
#endif /* __cplusplus */

#endif      /* __LVM_H__ */

