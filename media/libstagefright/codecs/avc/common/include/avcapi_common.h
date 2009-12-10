/* ------------------------------------------------------------------
 * Copyright (C) 1998-2009 PacketVideo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 * -------------------------------------------------------------------
 */
/**
This file contains common type definitions and enumerations used by AVC encoder
and decoder libraries which are exposed to the users.
@publishedAll
*/

#ifndef AVCAPI_COMMON_H_INCLUDED
#define AVCAPI_COMMON_H_INCLUDED

#include "avc_types.h"

#define PV_MEMORY_POOL
/**
This is common return status.
@publishedAll
*/
typedef enum
{
    AVC_NO_BUFFER = -2,
    AVC_MEMORY_FAIL = -1,
    AVC_FAIL = 0,
    AVC_SUCCESS = 1,
    AVC_PICTURE_OUTPUT_READY = 2
} AVCStatus;

/**
This enumeration is for profiles. The value follows the profile_idc  in sequence
parameter set rbsp. See Annex A.
@publishedAll
*/
typedef enum
{
    AVC_BASELINE = 66,
    AVC_MAIN = 77,
    AVC_EXTENDED = 88,
    AVC_HIGH = 100,
    AVC_HIGH10 = 110,
    AVC_HIGH422 = 122,
    AVC_HIGH444 = 144
} AVCProfile;

/**
This enumeration is for levels. The value follows the level_idc in sequence
parameter set rbsp. See Annex A.
@published All
*/
typedef enum
{
    AVC_LEVEL_AUTO = 0,
    AVC_LEVEL1_B = 9,
    AVC_LEVEL1 = 10,
    AVC_LEVEL1_1 = 11,
    AVC_LEVEL1_2 = 12,
    AVC_LEVEL1_3 = 13,
    AVC_LEVEL2 = 20,
    AVC_LEVEL2_1 = 21,
    AVC_LEVEL2_2 = 22,
    AVC_LEVEL3 = 30,
    AVC_LEVEL3_1 = 31,
    AVC_LEVEL3_2 = 32,
    AVC_LEVEL4 = 40,
    AVC_LEVEL4_1 = 41,
    AVC_LEVEL4_2 = 42,
    AVC_LEVEL5 = 50,
    AVC_LEVEL5_1 = 51
} AVCLevel;

/**
This enumeration follows Table 7-1 for NAL unit type codes.
This may go to avccommon_api.h later (external common).
@publishedAll
*/
typedef enum
{
    AVC_NALTYPE_SLICE = 1,  /* non-IDR non-data partition */
    AVC_NALTYPE_DPA = 2,    /* data partition A */
    AVC_NALTYPE_DPB = 3,    /* data partition B */
    AVC_NALTYPE_DPC = 4,    /* data partition C */
    AVC_NALTYPE_IDR = 5,    /* IDR NAL */
    AVC_NALTYPE_SEI = 6,    /* supplemental enhancement info */
    AVC_NALTYPE_SPS = 7,    /* sequence parameter set */
    AVC_NALTYPE_PPS = 8,    /* picture parameter set */
    AVC_NALTYPE_AUD = 9,    /* access unit delimiter */
    AVC_NALTYPE_EOSEQ = 10, /* end of sequence */
    AVC_NALTYPE_EOSTREAM = 11, /* end of stream */
    AVC_NALTYPE_FILL = 12   /* filler data */
} AVCNalUnitType;

/**
This enumeration specifies debug logging type.
This may go to avccommon_api.h later (external common).
@publishedAll
*/
typedef enum
{
    AVC_LOGTYPE_ERROR = 0,
    AVC_LOGTYPE_WARNING = 1,
    AVC_LOGTYPE_INFO = 2
} AVCLogType;

/**
This enumerate the status of certain flags.
@publishedAll
*/
typedef enum
{
    AVC_OFF = 0,
    AVC_ON = 1
} AVCFlag;

/**
This structure contains input information.
Note, this structure is identical to AVCDecOutput for now.
*/
typedef struct tagAVCFrameIO
{
    /** A unique identification number for a particular instance of this structure.
    To remain unchanged by the application between the time when it is given to the
    library and the time when the library returns it back. */
    uint32 id;

    /** Array of pointers to Y,Cb,Cr content in 4:2:0 format. For AVC decoding,
    this memory is allocated by the AVC decoder library. For AVC encoding, only the
    memory for original unencoded frame is allocated by the application. Internal
    memory is also allocated by the AVC encoder library. */
    uint8 *YCbCr[3];

    /** In/Out: Coded width of the luma component, it has to be multiple of 16. */
    int pitch;

    /** In/Out: Coded height of the luma component, must be multiple of 16. */
    int height;

    /** In/Out: Display width, less than picth */
    int clip_width;

    /** In/Out: Display height, less than height */
    int clip_height;

    /** Input: Origin of the display area [0]=>row, [1]=>column  */
    int clip_origin[2];

    /** Output: Frame number in de/encoding order (not necessary)*/
    uint32 coding_order;

    /** Output: Frame number in displaying order (this may or may not be associated with the POC at all!!!). */
    uint32 disp_order;

    /** In/Out: Flag for use for reference or not. */
    uint  is_reference;

    /** In/Out: Coding timestamp in msec (not display timestamp) */
    uint32 coding_timestamp;

    /* there could be something else here such as format, DON (decoding order number)
     if available thru SEI, etc. */
} AVCFrameIO;


/** CALLBACK FUNCTION TO BE IMPLEMENTED BY APPLICATION */
/** In AVCDecControls structure, userData is a pointer to an object with the following
    member functions.
*/


/** @brief Decoded picture buffers (DPB) must be allocated or re-allocated before an
    IDR frame is decoded. If PV_MEMORY_POOL is not defined, AVC lib will allocate DPB
    internally which cannot be shared with the application. In that case, this function
    will not be called.
    @param userData  The same value of userData in AVCHandle object.
    @param frame_size_in_mbs  The size of each frame in number of macroblocks.
    @param num_frames The number of frames in DPB.
    @return 1 for success, 0 for fail (cannot allocate DPB)
*/

typedef int (*FunctionType_DPBAlloc)(void *userData, uint frame_size_in_mbs, uint num_buffers);

/** @brief AVC library calls this function is reserve a memory of one frame from the DPB.
    Once reserved, this frame shall not be deleted or over-written by the app.
    @param userData  The same value of userData in AVCHandle object.
    @param indx      Index of a frame in DPB (AVC library keeps track of the index).
    @param yuv      The address of the yuv pointer returned to the AVC lib.
    @return         1 for success, 0 for fail (no frames available to bind).
    */
typedef int (*FunctionType_FrameBind)(void *userData, int indx, uint8 **yuv);

/** @brief AVC library calls this function once a bound frame is not needed for decoding
    operation (falls out of the sliding window, or marked unused for reference).
    @param userData  The same value of userData in AVCHandle object.
    @param indx      Index of frame to be unbound (AVC library keeps track of the index).
    @return  none.
*/
typedef void (*FuctionType_FrameUnbind)(void *userData, int);

/** Pointer to malloc function for general memory allocation, so that application can keep track of
    memory usage.
\param "size" "Size of requested memory in bytes."
\param "attribute" "Some value specifying types, priority, etc. of the memory."
\return "The address of the allocated memory casted to int"
*/
typedef int (*FunctionType_Malloc)(void *userData, int32 size, int attribute);

/** Function pointer to free
\param "mem" "Pointer to the memory to be freed casted to int"
\return "void"
*/
typedef void (*FunctionType_Free)(void *userData, int mem);

/** Debug logging information is returned to the application thru this function.
\param "type"   "Type of logging message, see definition of AVCLogType."
\param "string1"    "Logging message."
\param "string2"    "To be defined."
*/
typedef void (*FunctionType_DebugLog)(uint32 *userData, AVCLogType type, char *string1, int val1, int val2);

/**
This structure has to be allocated and maintained by the user of the library.
This structure is used as a handle to the library object.
*/
typedef struct tagAVCHandle
{
    /** A pointer to the internal data structure. Users have to make sure that this value
        is NULL at the beginning.
    */
    void        *AVCObject;

    /** A pointer to user object which has the following member functions used for
    callback purpose.  !!! */
    void        *userData;

    /** Pointers to functions implemented by the users of AVC library */
    FunctionType_DPBAlloc CBAVC_DPBAlloc;

    FunctionType_FrameBind CBAVC_FrameBind;

    FuctionType_FrameUnbind CBAVC_FrameUnbind;

    FunctionType_Malloc CBAVC_Malloc;

    FunctionType_Free  CBAVC_Free;

    FunctionType_DebugLog CBAVC_DebugLog;

    /** Flag to enable debugging */
    uint32  debugEnable;

} AVCHandle;



#ifdef PVDEBUGMSG_LOG
#define DEBUG_LOG(a,b,c,d,e)    CBAVC_DebugLog(a,b,c,d,e)
#else
#define DEBUG_LOG(a,b,c,d,e)
#endif

#endif /* _AVCAPI_COMMON_H_ */
