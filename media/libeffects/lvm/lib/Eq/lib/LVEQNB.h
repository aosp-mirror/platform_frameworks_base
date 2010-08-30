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
/*  Header file for the application layer interface of the N-Band equaliser.            */
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
/*  Two data formats are support Stereo and Mono-In-Stereo. The data is interleaved as  */
/*  follows:                                                                            */
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
/*  in to Mono-In-Stereo format.                                                        */
/*                                                                                      */
/****************************************************************************************/
/*                                                                                      */
/*  Note: 3                                                                             */
/*  =======                                                                             */
/*  The format of the data in the filter band definition structure is as follows:       */
/*                                                                                      */
/*      Gain        is in integer dB, range -15dB to +15dB inclusive                    */
/*      Frequency   is the centre frequency in Hz, range DC to Nyquist                  */
/*      QFactor     is the Q multiplied by 100, range 0.25 (25) to 12 (1200)            */
/*                                                                                      */
/*  Example:                                                                            */
/*      Gain = 7            7dB gain                                                    */
/*      Frequency = 2467    Centre frequency = 2.467kHz                                 */
/*      QFactor = 1089      Q = 10.89                                                   */
/*                                                                                      */
/*  The equaliser filters are passed as a pointer to and array of filter band           */
/*  definitions structures. There must be one filter definition for each band.          */
/*                                                                                      */
/****************************************************************************************/


#ifndef __LVEQNB_H__
#define __LVEQNB_H__

#ifdef __cplusplus
extern "C" {
#endif /* __cplusplus */


/****************************************************************************************/
/*                                                                                      */
/*  Includes                                                                            */
/*                                                                                      */
/****************************************************************************************/

#include "LVM_Types.h"
#include "LVM_Common.h"

/****************************************************************************************/
/*                                                                                      */
/*  Definitions                                                                         */
/*                                                                                      */
/****************************************************************************************/

/* Memory table */
#define LVEQNB_MEMREGION_INSTANCE          0   /* Offset to the instance memory region */
#define LVEQNB_MEMREGION_PERSISTENT_DATA   1   /* Offset to persistent data memory region */
#define LVEQNB_MEMREGION_PERSISTENT_COEF   2   /* Offset to persistent coefficient region */
#define LVEQNB_MEMREGION_SCRATCH           3   /* Offset to data scratch memory region */
#define LVEQNB_NR_MEMORY_REGIONS           4   /* Number of memory regions */

/* Callback events */
#define LVEQNB_EVENT_NONE                   0x0000    /* Not a valid event */
#define LVEQNB_EVENT_ALGOFF                 0x0001    /* EQNB has completed switch off */

/****************************************************************************************/
/*                                                                                      */
/*  Types                                                                               */
/*                                                                                      */
/****************************************************************************************/

/* Instance handle */
typedef void *LVEQNB_Handle_t;


/* Operating modes */
typedef enum
{
    LVEQNB_BYPASS   = 0,
    LVEQNB_ON       = 1,
    LVEQNB_MODE_MAX = LVM_MAXINT_32
} LVEQNB_Mode_en;


/* Filter mode control */
typedef enum
{
    LVEQNB_FILTER_OFF   = 0,
    LVEQNB_FILTER_ON    = 1,
    LVEQNB_FILTER_DUMMY = LVM_MAXINT_32
} LVEQNB_FilterMode_en;


/* Memory Types */
typedef enum
{
    LVEQNB_PERSISTENT      = 0,
    LVEQNB_PERSISTENT_DATA = 1,
    LVEQNB_PERSISTENT_COEF = 2,
    LVEQNB_SCRATCH         = 3,
    LVEQNB_MEMORY_MAX      = LVM_MAXINT_32
} LVEQNB_MemoryTypes_en;


/* Function return status */
typedef enum
{
    LVEQNB_SUCCESS        = 0,                          /* Successful return from a routine */
    LVEQNB_ALIGNMENTERROR = 1,                          /* Memory alignment error */
    LVEQNB_NULLADDRESS    = 2,                          /* NULL allocation address */
    LVEQNB_TOOMANYSAMPLES = 3,                          /* Maximum block size exceeded */
    LVEQNB_STATUS_MAX     = LVM_MAXINT_32
} LVEQNB_ReturnStatus_en;


/****************************************************************************************/
/*                                                                                      */
/*  Linked enumerated type and capability definitions                                   */
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
/*      Capabilities.SampleRate = LVEQNB_CAP_32000 + LVEQNB_CAP_44100;                  */
/*                                                                                      */
/*  and at run time it would be passed the value LVEQNB_FS_32000 through the control    */
/*  function to select operation at 32kHz                                               */
/*                                                                                      */
/****************************************************************************************/

/*
 * Supported source data formats
 */
#define LVEQNB_CAP_STEREO                  1
#define LVEQNB_CAP_MONOINSTEREO            2

typedef enum
{
    LVEQNB_STEREO       = 0,
    LVEQNB_MONOINSTEREO = 1,
    LVEQNB_SOURCE_MAX   = LVM_MAXINT_32
} LVEQNB_SourceFormat_en;


/*
 * Supported sample rates in samples per second
 */
#define LVEQNB_CAP_FS_8000                 1
#define LVEQNB_CAP_FS_11025                2
#define LVEQNB_CAP_FS_12000                4
#define LVEQNB_CAP_FS_16000                8
#define LVEQNB_CAP_FS_22050                16
#define LVEQNB_CAP_FS_24000                32
#define LVEQNB_CAP_FS_32000                64
#define LVEQNB_CAP_FS_44100                128
#define LVEQNB_CAP_FS_48000                256

typedef enum
{
    LVEQNB_FS_8000  = 0,
    LVEQNB_FS_11025 = 1,
    LVEQNB_FS_12000 = 2,
    LVEQNB_FS_16000 = 3,
    LVEQNB_FS_22050 = 4,
    LVEQNB_FS_24000 = 5,
    LVEQNB_FS_32000 = 6,
    LVEQNB_FS_44100 = 7,
    LVEQNB_FS_48000 = 8,
    LVEQNB_FS_MAX   = LVM_MAXINT_32
} LVEQNB_Fs_en;


/****************************************************************************************/
/*                                                                                      */
/*  Structures                                                                          */
/*                                                                                      */
/****************************************************************************************/

/* Memory region definition */
typedef struct
{
    LVM_UINT32                  Size;                   /* Region size in bytes */
    LVM_UINT16                  Alignment;              /* Region alignment in bytes */
    LVEQNB_MemoryTypes_en       Type;                   /* Region type */
    void                        *pBaseAddress;          /* Pointer to the region base address */
} LVEQNB_MemoryRegion_t;


/* Memory table containing the region definitions */
typedef struct
{
    LVEQNB_MemoryRegion_t       Region[LVEQNB_NR_MEMORY_REGIONS];  /* One definition for each region */
} LVEQNB_MemTab_t;


/* Equaliser band definition */
typedef struct
{
    LVM_INT16                   Gain;                   /* Band gain in dB */
    LVM_UINT16                  Frequency;              /* Band centre frequency in Hz */
    LVM_UINT16                  QFactor;                /* Band quality factor */
} LVEQNB_BandDef_t;


/* Parameter structure */
typedef struct
{
    /* General parameters */
    LVEQNB_Mode_en              OperatingMode;
    LVEQNB_Fs_en                SampleRate;
    LVEQNB_SourceFormat_en      SourceFormat;

    /* Equaliser parameters */
    LVM_UINT16                  NBands;                 /* Number of bands */
    LVEQNB_BandDef_t            *pBandDefinition;       /* Pointer to equaliser definitions */

} LVEQNB_Params_t;


/* Capability structure */
typedef struct
{
    /* General parameters */
    LVM_UINT16                  SampleRate;
    LVM_UINT16                  SourceFormat;
    LVM_UINT16                  MaxBlockSize;
    LVM_UINT16                  MaxBands;

    /* Callback parameters */
    LVM_Callback                CallBack;               /* Bundle callback */
    void                        *pBundleInstance;       /* Bundle instance handle */

} LVEQNB_Capabilities_t;


/****************************************************************************************/
/*                                                                                      */
/*  Function Prototypes                                                                 */
/*                                                                                      */
/****************************************************************************************/

/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                LVEQNB_Memory                                               */
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
/*  pCapabilities           Pointer to the default capabilities                         */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVEQNB_SUCCESS          Succeeded                                                   */
/*  LVEQNB_NULLADDRESS      When any of pMemoryTable and pCapabilities is NULL address  */
/*                                                                                      */
/* NOTES:                                                                               */
/*  1.  This function may be interrupted by the LVEQNB_Process function                 */
/*                                                                                      */
/****************************************************************************************/

LVEQNB_ReturnStatus_en LVEQNB_Memory(LVEQNB_Handle_t            hInstance,
                                     LVEQNB_MemTab_t            *pMemoryTable,
                                     LVEQNB_Capabilities_t      *pCapabilities);


/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                LVEQNB_Init                                                 */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  Create and initialisation function for the N-Band equalliser module                 */
/*                                                                                      */
/*  This function can be used to create an algorithm instance by calling with           */
/*  hInstance set to NULL. In this case the algorithm returns the new instance          */
/*  handle.                                                                             */
/*                                                                                      */
/*  This function can be used to force a full re-initialisation of the algorithm        */
/*  by calling with hInstance = Instance Handle. In this case the memory table          */
/*  should be correct for the instance, this can be ensured by calling the function     */
/*  LVEQNB_Memory before calling this function.                                         */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  hInstance               Instance handle                                             */
/*  pMemoryTable            Pointer to the memory definition table                      */
/*  pCapabilities           Pointer to the initialisation capabilities                  */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVEQNB_SUCCESS          Initialisation succeeded                                    */
/*  LVEQNB_NULLADDRESS        When pCapabilities or pMemoryTableis or phInstance are NULL */
/*  LVEQNB_NULLADDRESS        One or more of the memory regions has a NULL base address   */
/*                          pointer for a memory region with a non-zero size.           */
/*                                                                                      */
/*                                                                                      */
/* NOTES:                                                                               */
/*  1.  The instance handle is the pointer to the base address of the first memory      */
/*      region.                                                                         */
/*  2.  This function must not be interrupted by the LVEQNB_Process function            */
/*                                                                                      */
/****************************************************************************************/

LVEQNB_ReturnStatus_en LVEQNB_Init(LVEQNB_Handle_t          *phInstance,
                                   LVEQNB_MemTab_t          *pMemoryTable,
                                   LVEQNB_Capabilities_t    *pCapabilities);


/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                 LVEQNB_GetParameters                                       */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  Request the equaliser module parameters. The current parameter set is returned      */
/*  via the parameter pointer.                                                          */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  hInstance                Instance handle                                            */
/*  pParams                  Pointer to an empty parameter structure                    */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVEQNB_SUCCESS           Succeeds                                                   */
/*  LVEQNB_NULLADDRESS       Instance or pParams  is NULL pointer                       */
/*                                                                                      */
/* NOTES:                                                                               */
/*  1.  This function may be interrupted by the LVEQNB_Process function                 */
/*                                                                                      */
/****************************************************************************************/

LVEQNB_ReturnStatus_en LVEQNB_GetParameters(LVEQNB_Handle_t     hInstance,
                                            LVEQNB_Params_t     *pParams);


/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                 LVEQNB_GetCapabilities                                     */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  Request the equaliser module capabilities. The capabilities set is returned         */
/*  via the pointer.                                                                    */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  hInstance                Instance handle                                            */
/*  pCapabilities            Pointer to an empty capability structure                   */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVEQNB_SUCCESS           Succeeds                                                   */
/*  LVEQNB_NULLADDRESS       hInstance or pCapabilities is NULL                         */
/*                                                                                      */
/* NOTES:                                                                               */
/*  1.  This function may be interrupted by the LVEQNB_Process function                 */
/*                                                                                      */
/****************************************************************************************/

LVEQNB_ReturnStatus_en LVEQNB_GetCapabilities(LVEQNB_Handle_t           hInstance,
                                              LVEQNB_Capabilities_t     *pCapabilities);


/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                LVEQNB_Control                                              */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  Sets or changes the equaliser module parameters.                                    */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  hInstance               Instance handle                                             */
/*  pParams                 Pointer to a parameter structure                            */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVEQNB_SUCCESS          Succeeded                                                   */
/*  LVEQNB_NULLADDRESS      Instance or pParams  is NULL pointer                        */
/*  LVEQNB_NULLADDRESS      NULL address for the equaliser filter definitions and the   */
/*                          number of bands is non-zero                                 */
/*                                                                                      */
/* NOTES:                                                                               */
/*  1.  This function may be interrupted by the LVEQNB_Process function                 */
/*                                                                                      */
/****************************************************************************************/

LVEQNB_ReturnStatus_en LVEQNB_Control(LVEQNB_Handle_t       hInstance,
                                      LVEQNB_Params_t       *pParams);


/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                LVEQNB_Process                                              */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  Process function for the LifeVibes module.                                          */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  hInstance               Instance handle                                             */
/*  pInData                 Pointer to the input data                                   */
/*  pOutData                Pointer to the output data                                  */
/*  NumSamples              Number of samples in the input buffer                       */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVEQNB_SUCCESS          Succeeded                                                   */
/*  LVEQNB_NULLADDRESS      When hInstance, pInData or pOutData are NULL                */
/*  LVEQNB_ALIGNMENTERROR   When pInData or pOutData are not 32-bit aligned             */
/*  LVEQNB_TOOMANYSAMPLES   NumSamples was larger than the maximum block size           */
/*                                                                                      */
/* NOTES:                                                                               */
/*                                                                                      */
/****************************************************************************************/

LVEQNB_ReturnStatus_en LVEQNB_Process(LVEQNB_Handle_t       hInstance,
                                      const LVM_INT16       *pInData,
                                      LVM_INT16             *pOutData,
                                      LVM_UINT16            NumSamples);



#ifdef __cplusplus
}
#endif /* __cplusplus */

#endif      /* __LVEQNB__ */

