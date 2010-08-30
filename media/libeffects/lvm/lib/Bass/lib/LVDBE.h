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
/*    Header file for the application layer interface of Dynamic Bass Enhancement       */
/*  module.                                                                             */
/*                                                                                      */
/*  This files includes all definitions, types, structures and function                 */
/*  prototypes required by the calling layer. All other types, structures and           */
/*  functions are private.                                                              */
/*                                                                                      */
/****************************************************************************************/
/*                                                                                      */
/*    Note: 1                                                                           */
/*    =======                                                                           */
/*    The algorithm can execute either with separate input and output buffers or with   */
/*    a common buffer, i.e. the data is processed in-place.                             */
/*                                                                                      */
/****************************************************************************************/
/*                                                                                      */
/*    Note: 2                                                                           */
/*    =======                                                                           */
/*    The Dynamic Bass Enhancement algorithm always processes data as stereo input. Mono*/
/*  format data is not supported. The data is interleaved as follows:                   */
/*                                                                                      */
/*              Byte Offset         Stereo Input         Mono-In-Stereo Input           */
/*              ===========         ============         ====================           */
/*                  0               Left Sample #1          Mono Sample #1              */
/*                  2               Right Sample #1         Mono Sample #1              */
/*                  4               Left Sample #2          Mono Sample #2              */
/*                  6               Right Sample #2         Mono Sample #2              */
/*                  .                      .                     .                      */
/*                  .                      .                     .                      */
/*                                                                                      */
/*  Mono format data is not supported, the calling routine must convert a Mono stream   */
/*    in to Mono-In-Stereo format.                                                      */
/*                                                                                      */
/****************************************************************************************/

#ifndef __LVDBE_H__
#define __LVDBE_H__

#ifdef __cplusplus
extern "C" {
#endif /* __cplusplus */


/****************************************************************************************/
/*                                                                                      */
/*    Includes                                                                          */
/*                                                                                      */
/****************************************************************************************/

#include "LVM_Types.h"


/****************************************************************************************/
/*                                                                                      */
/*    Definitions                                                                       */
/*                                                                                      */
/****************************************************************************************/

/* Memory table*/
#define LVDBE_NR_MEMORY_REGIONS        4                            /* Number of memory regions */

/* Bass Enhancement effect level */
#define LVDBE_EFFECT_03DB            3                              /* Effect defines for backwards compatibility */
#define LVDBE_EFFECT_06DB            6
#define LVDBE_EFFECT_09DB            9
#define LVDBE_EFFECT_12DB            12
#define LVDBE_EFFECT_15DB            15


/****************************************************************************************/
/*                                                                                      */
/*    Types                                                                             */
/*                                                                                      */
/****************************************************************************************/

/* Instance handle */
typedef void    *LVDBE_Handle_t;


/* Operating modes */
typedef enum
{
    LVDBE_OFF      = 0,
    LVDBE_ON       = 1,
    LVDBE_MODE_MAX = LVM_MAXINT_32
} LVDBE_Mode_en;


/* High pass filter */
typedef enum
{
    LVDBE_HPF_OFF = 0,
    LVDBE_HPF_ON  = 1,
    LVDBE_HPF_MAX = LVM_MAXINT_32
} LVDBE_FilterSelect_en;


/* Volume control */
typedef enum
{
    LVDBE_VOLUME_OFF = 0,
    LVDBE_VOLUME_ON  = 1,
    LVDBE_VOLUME_MAX = LVM_MAXINT_32
} LVDBE_Volume_en;


/* Memory Types */
typedef enum
{
    LVDBE_PERSISTENT      = 0,
    LVDBE_PERSISTENT_DATA = 1,
    LVDBE_PERSISTENT_COEF = 2,
    LVDBE_SCRATCH         = 3,
    LVDBE_MEMORY_MAX      = LVM_MAXINT_32

} LVDBE_MemoryTypes_en;


/* Function return status */
typedef enum
{
    LVDBE_SUCCESS        = 0,                        /* Successful return from a routine */
    LVDBE_ALIGNMENTERROR = 1,                        /* Memory alignment error */
    LVDBE_NULLADDRESS    = 2,                        /* NULL allocation address */
    LVDBE_TOOMANYSAMPLES = 3,                        /* Maximum block size exceeded */
    LVDBE_SIZEERROR      = 4,                        /* Incorrect structure size */
    LVDBE_STATUS_MAX     = LVM_MAXINT_32
} LVDBE_ReturnStatus_en;


/****************************************************************************************/
/*                                                                                      */
/*    Linked enumerated type and capability definitions                                 */
/*                                                                                      */
/*  The capability definitions are used to define the required capabilities at          */
/*  initialisation, these are added together to give the capability word. The           */
/*  enumerated type is used to select the mode through a control function at run time.  */
/*                                                                                      */
/*  The capability definition is related to the enumerated type value by the equation:  */
/*                                                                                      */
/*          Capability_value = 2^Enumerated_value                                       */
/*                                                                                      */
/*  For example, a module could be configurd at initialisation to support two sample    */
/*  rates only by calling the init function with the value:                             */
/*      Capabilities.SampleRate = LVDBE_CAP_32000 + LVCS_DBE_44100;                     */
/*                                                                                      */
/*  and at run time it would be passed the value LVDBE_FS_32000 through the control     */
/*  function to select operation at 32kHz                                               */
/*                                                                                      */
/****************************************************************************************/

/*
 * Bass Enhancement centre frequency
 */
#define LVDBE_CAP_CENTRE_55Hz       1
#define LVDBE_CAP_CENTRE_66Hz       2
#define LVDBE_CAP_CENTRE_78Hz       4
#define LVDBE_CAP_CENTRE_90Hz       8

typedef enum
{
    LVDBE_CENTRE_55HZ = 0,
    LVDBE_CENTRE_66HZ = 1,
    LVDBE_CENTRE_78HZ = 2,
    LVDBE_CENTRE_90HZ = 3,
    LVDBE_CENTRE_MAX  = LVM_MAXINT_32
} LVDBE_CentreFreq_en;


/*
 * Supported sample rates in samples per second
 */
#define LVDBE_CAP_FS_8000                1
#define LVDBE_CAP_FS_11025               2
#define LVDBE_CAP_FS_12000               4
#define LVDBE_CAP_FS_16000               8
#define LVDBE_CAP_FS_22050               16
#define LVDBE_CAP_FS_24000               32
#define LVDBE_CAP_FS_32000               64
#define LVDBE_CAP_FS_44100               128
#define LVDBE_CAP_FS_48000               256

typedef enum
{
    LVDBE_FS_8000  = 0,
    LVDBE_FS_11025 = 1,
    LVDBE_FS_12000 = 2,
    LVDBE_FS_16000 = 3,
    LVDBE_FS_22050 = 4,
    LVDBE_FS_24000 = 5,
    LVDBE_FS_32000 = 6,
    LVDBE_FS_44100 = 7,
    LVDBE_FS_48000 = 8,
    LVDBE_FS_MAX   = LVM_MAXINT_32
} LVDBE_Fs_en;


/****************************************************************************************/
/*                                                                                      */
/*    Structures                                                                        */
/*                                                                                      */
/****************************************************************************************/

/* Memory region definition */
typedef struct
{
    LVM_UINT32                Size;                        /* Region size in bytes */
    LVM_UINT16                Alignment;                  /* Region alignment in bytes */
    LVDBE_MemoryTypes_en      Type;                       /* Region type */
    void                      *pBaseAddress;              /* Pointer to the region base address */
} LVDBE_MemoryRegion_t;


/* Memory table containing the region definitions */
typedef struct
{
    LVDBE_MemoryRegion_t    Region[LVDBE_NR_MEMORY_REGIONS];  /* One definition for each region */
} LVDBE_MemTab_t;


/* Parameter structure */
typedef struct
{
    LVDBE_Mode_en           OperatingMode;
    LVDBE_Fs_en             SampleRate;
    LVM_INT16               EffectLevel;
    LVDBE_CentreFreq_en     CentreFrequency;
    LVDBE_FilterSelect_en   HPFSelect;
    LVDBE_Volume_en         VolumeControl;
    LVM_INT16               VolumedB;
    LVM_INT16               HeadroomdB;

} LVDBE_Params_t;


/* Capability structure */
typedef struct
{
      LVM_UINT16              SampleRate;               /* Sampling rate capabilities */
      LVM_UINT16              CentreFrequency;          /* Centre frequency capabilities */
      LVM_UINT16              MaxBlockSize;             /* Maximum block size in sample pairs */
} LVDBE_Capabilities_t;


/****************************************************************************************/
/*                                                                                      */
/*    Function Prototypes                                                               */
/*                                                                                      */
/****************************************************************************************/

/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                 LVDBE_Memory                                               */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*    This function is used for memory allocation and free. It can be called in         */
/*    two ways:                                                                         */
/*                                                                                      */
/*        hInstance = NULL                Returns the memory requirements               */
/*        hInstance = Instance handle        Returns the memory requirements and        */
/*                                        allocated base addresses for the instance     */
/*                                                                                      */
/*    When this function is called for memory allocation (hInstance=NULL) the memory    */
/*  base address pointers are NULL on return.                                           */
/*                                                                                      */
/*    When the function is called for free (hInstance = Instance Handle) the memory     */
/*  table returns the allocated memory and base addresses used during initialisation.   */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  hInstance                Instance Handle                                            */
/*  pMemoryTable             Pointer to an empty memory definition table                */
/*    pCapabilities            Pointer to the default capabilites                       */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVDBE_SUCCESS            Succeeded                                                  */
/*                                                                                      */
/* NOTES:                                                                               */
/*    1.    This function may be interrupted by the LVDBE_Process function              */
/*                                                                                      */
/****************************************************************************************/

LVDBE_ReturnStatus_en LVDBE_Memory(LVDBE_Handle_t           hInstance,
                                   LVDBE_MemTab_t           *pMemoryTable,
                                   LVDBE_Capabilities_t     *pCapabilities);


/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                 LVDBE_Init                                                 */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*    Create and initialisation function for the Bass Enhancement module                */
/*                                                                                      */
/*    This function can be used to create an algorithm instance by calling with         */
/*    hInstance set to NULL. In this case the algorithm returns the new instance        */
/*    handle.                                                                           */
/*                                                                                      */
/*    This function can be used to force a full re-initialisation of the algorithm      */
/*    by calling with hInstance = Instance Handle. In this case the memory table        */
/*    should be correct for the instance, this can be ensured by calling the function   */
/*    LVDBE_Memory before calling this function.                                        */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  hInstance                  Instance handle                                          */
/*  pMemoryTable             Pointer to the memory definition table                     */
/*  pCapabilities            Pointer to the initialisation capabilities                 */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVDBE_SUCCESS                Initialisation succeeded                               */
/*  LVDBE_ALIGNMENTERROR        Instance or scratch memory on incorrect alignment       */
/*    LVDBE_NULLADDRESS            One or more memory has a NULL pointer                */
/*                                                                                      */
/* NOTES:                                                                               */
/*  1.     The instance handle is the pointer to the base address of the first memory   */
/*        region.                                                                       */
/*    2.    This function must not be interrupted by the LVDBE_Process function         */
/*                                                                                      */
/****************************************************************************************/

LVDBE_ReturnStatus_en LVDBE_Init(LVDBE_Handle_t             *phInstance,
                                   LVDBE_MemTab_t           *pMemoryTable,
                                   LVDBE_Capabilities_t     *pCapabilities);


/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                  LVDBE_GetParameters                                       */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*    Request the Bass Enhancement parameters. The current parameter set is returned    */
/*    via the parameter pointer.                                                        */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  hInstance                   Instance handle                                         */
/*  pParams                  Pointer to an empty parameter structure                    */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVDBE_SUCCESS             Always succeeds                                           */
/*                                                                                      */
/* NOTES:                                                                               */
/*  1.    This function may be interrupted by the LVDBE_Process function                */
/*                                                                                      */
/****************************************************************************************/

LVDBE_ReturnStatus_en LVDBE_GetParameters(LVDBE_Handle_t        hInstance,
                                            LVDBE_Params_t      *pParams);


/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                  LVDBE_GetCapabilities                                     */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*    Request the Dynamic Bass Enhancement capabilities. The initial capabilities are   */
/*  returned via the pointer.                                                           */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  hInstance                   Instance handle                                         */
/*  pCapabilities              Pointer to an empty capabilitiy structure                */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVDBE_Success             Always succeeds                                           */
/*                                                                                      */
/* NOTES:                                                                               */
/*  1.    This function may be interrupted by the LVDBE_Process function                */
/*                                                                                      */
/****************************************************************************************/

LVDBE_ReturnStatus_en LVDBE_GetCapabilities(LVDBE_Handle_t            hInstance,
                                              LVDBE_Capabilities_t    *pCapabilities);


/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                LVDBE_Control                                               */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  Sets or changes the Bass Enhancement parameters. Changing the parameters while the  */
/*  module is processing signals may have the following side effects:                   */
/*                                                                                      */
/*  General parameters:                                                                 */
/*  ===================                                                                 */
/*  OperatingMode:      Changing the mode of operation may cause a change in volume     */
/*                      level.                                                          */
/*                                                                                      */
/*  SampleRate:         Changing the sample rate may cause pops and clicks.             */
/*                                                                                      */
/*  EffectLevel:        Changing the effect level setting will have no side effects     */
/*                                                                                      */
/*  CentreFrequency:    Changing the centre frequency may cause pops and clicks         */
/*                                                                                      */
/*  HPFSelect:          Selecting/de-selecting the high pass filter may cause pops and  */
/*                      clicks                                                          */
/*                                                                                      */
/*  VolumedB            Changing the volume setting will have no side effects           */
/*                                                                                      */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  hInstance               Instance handle                                             */
/*  pParams                 Pointer to a parameter structure                            */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVDBE_SUCCESS           Always succeeds                                             */
/*                                                                                      */
/* NOTES:                                                                               */
/*  1.  This function must not be interrupted by the LVDBE_Process function             */
/*                                                                                      */
/****************************************************************************************/

LVDBE_ReturnStatus_en LVDBE_Control(LVDBE_Handle_t      hInstance,
                                      LVDBE_Params_t    *pParams);


/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                 LVDBE_Process                                              */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  Process function for the Bass Enhancement module.                                   */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  hInstance                Instance handle                                            */
/*  pInData                  Pointer to the input data                                  */
/*  pOutData                 Pointer to the output data                                 */
/*  NumSamples              Number of samples in the input buffer                       */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVDBE_SUCCESS             Succeeded                                                 */
/*    LVDBE_TOOMANYSAMPLES    NumSamples was larger than the maximum block size         */
/*                                                                                      */
/* NOTES:                                                                               */
/*                                                                                      */
/****************************************************************************************/

LVDBE_ReturnStatus_en LVDBE_Process(LVDBE_Handle_t          hInstance,
                                       const LVM_INT16      *pInData,
                                       LVM_INT16            *pOutData,
                                       LVM_UINT16           NumSamples);


#ifdef __cplusplus
}
#endif /* __cplusplus */

#endif /* __LVDBE_H__ */
