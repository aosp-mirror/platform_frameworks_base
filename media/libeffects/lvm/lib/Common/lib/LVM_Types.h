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
/*  Header file defining the standard LifeVibes types for use in the application layer  */
/*  interface of all LifeVibes modules                                                  */
/*                                                                                      */
/****************************************************************************************/

#ifndef LVM_TYPES_H
#define LVM_TYPES_H

#ifdef __cplusplus
extern "C" {
#endif /* __cplusplus */


/****************************************************************************************/
/*                                                                                      */
/*  definitions                                                                         */
/*                                                                                      */
/****************************************************************************************/

#define LVM_NULL                0                   /* NULL pointer */

#define LVM_TRUE                1                   /* Booleans */
#define LVM_FALSE               0

#define LVM_MAXINT_8            127                 /* Maximum positive integer size */
#define LVM_MAXINT_16           32767
#define LVM_MAXINT_32           2147483647
#define LVM_MAXENUM             2147483647

#define LVM_MODULEID_MASK       0xFF00              /* Mask to extract the calling module ID from callbackId */
#define LVM_EVENTID_MASK        0x00FF              /* Mask to extract the callback event from callbackId */

/* Memory table*/
#define LVM_MEMREGION_PERSISTENT_SLOW_DATA      0   /* Offset to the instance memory region */
#define LVM_MEMREGION_PERSISTENT_FAST_DATA      1   /* Offset to the persistent data memory region */
#define LVM_MEMREGION_PERSISTENT_FAST_COEF      2   /* Offset to the persistent coefficient memory region */
#define LVM_MEMREGION_TEMPORARY_FAST            3   /* Offset to temporary memory region */

#define LVM_NR_MEMORY_REGIONS                   4   /* Number of memory regions */

/* Memory partition type */
#define LVM_MEM_PARTITION0      0                   /* 1st memory partition */
#define LVM_MEM_PARTITION1      1                   /* 2nd memory partition */
#define LVM_MEM_PARTITION2      2                   /* 3rd memory partition */
#define LVM_MEM_PARTITION3      3                   /* 4th memory partition */

/* Use type */
#define LVM_MEM_PERSISTENT      0                   /* Persistent memory type */
#define LVM_MEM_SCRATCH         4                   /* Scratch  memory type */

/* Access type */
#define LVM_MEM_INTERNAL        0                   /* Internal (fast) access memory */
#define LVM_MEM_EXTERNAL        8                   /* External (slow) access memory */

/* Platform specific */
#define LVM_PERSISTENT          LVM_MEM_PARTITION0+LVM_MEM_PERSISTENT+LVM_MEM_INTERNAL
#define LVM_PERSISTENT_DATA     LVM_MEM_PARTITION1+LVM_MEM_PERSISTENT+LVM_MEM_INTERNAL
#define LVM_PERSISTENT_COEF     LVM_MEM_PARTITION2+LVM_MEM_PERSISTENT+LVM_MEM_INTERNAL
#define LVM_SCRATCH             LVM_MEM_PARTITION3+LVM_MEM_SCRATCH+LVM_MEM_INTERNAL

/****************************************************************************************/
/*                                                                                      */
/*  Basic types                                                                         */
/*                                                                                      */
/****************************************************************************************/

typedef     char                LVM_CHAR;           /* ASCII character */

typedef     char                LVM_INT8;           /* Signed 8-bit word */
typedef     unsigned char       LVM_UINT8;          /* Unsigned 8-bit word */

typedef     short               LVM_INT16;          /* Signed 16-bit word */
typedef     unsigned short      LVM_UINT16;         /* Unsigned 16-bit word */

typedef     long                LVM_INT32;          /* Signed 32-bit word */
typedef     unsigned long       LVM_UINT32;         /* Unsigned 32-bit word */


/****************************************************************************************/
/*                                                                                      */
/*  Standard Enumerated types                                                           */
/*                                                                                      */
/****************************************************************************************/

/* Operating mode */
typedef enum
{
    LVM_MODE_OFF    = 0,
    LVM_MODE_ON     = 1,
    LVM_MODE_DUMMY  = LVM_MAXENUM
} LVM_Mode_en;


/* Format */
typedef enum
{
    LVM_STEREO          = 0,
    LVM_MONOINSTEREO    = 1,
    LVM_MONO            = 2,
    LVM_SOURCE_DUMMY    = LVM_MAXENUM
} LVM_Format_en;


/* LVM sampling rates */
typedef enum
{
    LVM_FS_8000  = 0,
    LVM_FS_11025 = 1,
    LVM_FS_12000 = 2,
    LVM_FS_16000 = 3,
    LVM_FS_22050 = 4,
    LVM_FS_24000 = 5,
    LVM_FS_32000 = 6,
    LVM_FS_44100 = 7,
    LVM_FS_48000 = 8,
    LVM_FS_INVALID = LVM_MAXENUM-1,
    LVM_FS_DUMMY = LVM_MAXENUM
} LVM_Fs_en;


/* Memory Types */
typedef enum
{
    LVM_PERSISTENT_SLOW_DATA    = LVM_MEMREGION_PERSISTENT_SLOW_DATA,
    LVM_PERSISTENT_FAST_DATA    = LVM_MEMREGION_PERSISTENT_FAST_DATA,
    LVM_PERSISTENT_FAST_COEF    = LVM_MEMREGION_PERSISTENT_FAST_COEF,
    LVM_TEMPORARY_FAST          = LVM_MEMREGION_TEMPORARY_FAST,
    LVM_MEMORYTYPE_DUMMY        = LVM_MAXENUM
} LVM_MemoryTypes_en;


/* Memory region definition */
typedef struct
{
    LVM_UINT32                  Size;                   /* Region size in bytes */
    LVM_MemoryTypes_en          Type;                   /* Region type */
    void                        *pBaseAddress;          /* Pointer to the region base address */
} LVM_MemoryRegion_st;


/* Memory table containing the region definitions */
typedef struct
{
    LVM_MemoryRegion_st         Region[LVM_NR_MEMORY_REGIONS];  /* One definition for each region */
} LVM_MemoryTable_st;


/****************************************************************************************/
/*                                                                                      */
/*  Standard Function Prototypes                                                        */
/*                                                                                      */
/****************************************************************************************/
typedef LVM_INT32 (*LVM_Callback)(void          *pCallbackData,     /* Pointer to the callback data structure */
                                  void          *pGeneralPurpose,   /* General purpose pointer (e.g. to a data structure needed in the callback) */
                                  LVM_INT16     GeneralPurpose );   /* General purpose variable (e.g. to be used as callback ID) */


/****************************************************************************************/
/*                                                                                      */
/*  End of file                                                                         */
/*                                                                                      */
/****************************************************************************************/

#ifdef __cplusplus
}
#endif /* __cplusplus */

#endif  /* LVM_TYPES_H */
