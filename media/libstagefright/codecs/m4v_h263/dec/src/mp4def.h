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
#ifndef _PVDECDEF_H_
#define _PVDECDEF_H_

#include "mp4dec_api.h"

typedef enum
{
    PV_SUCCESS,
    PV_FAIL,
    PV_MB_STUFFING,         /* hit Macroblock_Stuffing */
    PV_END_OF_VOP,          /* hit End_of_Video_Object_Plane */
    PV_END_OF_MB            /* hit End_of_Macroblock */
#ifdef PV_TOLERATE_VOL_ERRORS
    , PV_BAD_VOLHEADER
#endif
} PV_STATUS;

typedef uint8 PIXEL;
typedef int16 MOT;   /*  : "int" type runs faster on RISC machine */

#define TRUE    1
#define FALSE   0

#define PV_ABS(x)       (((x)<0)? -(x) : (x))
#define PV_SIGN(x)      (((x)<0)? -1 : 1)
#define PV_SIGN0(a)     (((a)<0)? -1 : (((a)>0) ? 1 : 0))
#define PV_MAX(a,b)     ((a)>(b)? (a):(b))
#define PV_MIN(a,b)     ((a)<(b)? (a):(b))
#define PV_MEDIAN(A,B,C) ((A) > (B) ? ((A) < (C) ? (A) : (B) > (C) ? (B) : (C)): (B) < (C) ? (B) : (C) > (A) ? (C) : (A))
/* You don't want to use ((x>UB)?UB:(x<LB)?LB:x) for the clipping */
/*    because it will use one extra comparison if the compiler is */
/*    not well-optimized.    04/19/2000.                        */
#define CLIP_THE_RANGE(x,LB,UB) if (x<LB) x = LB; else if (x>UB) x = UB

#define MODE_INTRA      0x08 //01000
#define MODE_INTRA_Q    0x09 //01001
#define MODE_SKIPPED    0x10 //10000
#define MODE_INTER4V    0x14 //10100
#define MODE_INTER      0x16 //10110
#define MODE_INTER_Q    0x17 //10111
#define MODE_INTER4V_Q  0x15 //10101
#define INTER_1VMASK    0x2
#define Q_MASK          0x1
#define INTRA_MASK      0x8
#define INTER_MASK      0x4


#define I_VOP       0
#define P_VOP       1
#define B_VOP       2

#define LUMINANCE_DC_TYPE   1
#define CHROMINANCE_DC_TYPE 2

#define START_CODE_LENGTH       32

/* 11/30/98 */
#define NoMarkerFound -1
#define FoundRM     1   /* Resync Marker */
#define FoundVSC    2   /* VOP_START_CODE. */
#define FoundGSC    3   /* GROUP_START_CODE */
#define FoundEOB    4   /* EOB_CODE */

/* PacketVideo "absolution timestamp" object.   06/13/2000 */
#define PVTS_START_CODE         0x01C4
#define PVTS_START_CODE_LENGTH  32

/* session layer and vop layer start codes */

#define VISUAL_OBJECT_SEQUENCE_START_CODE   0x01B0
#define VISUAL_OBJECT_SEQUENCE_END_CODE     0x01B1

#define VISUAL_OBJECT_START_CODE   0x01B5
#define VO_START_CODE           0x8
#define VO_HEADER_LENGTH        32      /* lengtho of VO header: VO_START_CODE +  VO_ID */

#define SOL_START_CODE          0x01BE
#define SOL_START_CODE_LENGTH   32

#define VOL_START_CODE 0x12
#define VOL_START_CODE_LENGTH 28

#define VOP_START_CODE 0x1B6
#define VOP_START_CODE_LENGTH   32

#define GROUP_START_CODE    0x01B3
#define GROUP_START_CODE_LENGTH  32

#define VOP_ID_CODE_LENGTH      5
#define VOP_TEMP_REF_CODE_LENGTH    16

#define USER_DATA_START_CODE        0x01B2
#define USER_DATA_START_CODE_LENGTH 32

#define START_CODE_PREFIX       0x01
#define START_CODE_PREFIX_LENGTH    24

#define SHORT_VIDEO_START_MARKER         0x20
#define SHORT_VIDEO_START_MARKER_LENGTH  22
#define SHORT_VIDEO_END_MARKER            0x3F
#define GOB_RESYNC_MARKER         0x01
#define GOB_RESYNC_MARKER_LENGTH  17

/* motion and resync markers used in error resilient mode  */

#define DC_MARKER                      438273
#define DC_MARKER_LENGTH                19

#define MOTION_MARKER_COMB             126977
#define MOTION_MARKER_COMB_LENGTH       17

#define MOTION_MARKER_SEP              81921
#define MOTION_MARKER_SEP_LENGTH        17

#define RESYNC_MARKER           1
#define RESYNC_MARKER_LENGTH    17

#define SPRITE_NOT_USED     0
#define STATIC_SPRITE       1
#define ONLINE_SPRITE       2
#define GMC_SPRITE      3

/* macroblock and block size */
#define MB_SIZE 16
#define NCOEFF_MB (MB_SIZE*MB_SIZE)
#define B_SIZE 8
#define NCOEFF_BLOCK (B_SIZE*B_SIZE)
#define NCOEFF_Y NCOEFF_MB
#define NCOEFF_U NCOEFF_BLOCK
#define NCOEFF_V NCOEFF_BLOCK
#define BLK_PER_MB      4   /* Number of blocks per MB */

/* VLC decoding related definitions */
#define VLC_ERROR   (-1)
#define VLC_ESCAPE  7167


/* macro utility */
#define  ZERO_OUT_64BYTES(x)    { *((uint32*)x) = *(((uint32*)(x))+1) =  \
        *(((uint32*)(x))+2) = *(((uint32*)(x))+3) =  \
        *(((uint32*)(x))+4) = *(((uint32*)(x))+5) =  \
        *(((uint32*)(x))+6) = *(((uint32*)(x))+7) =  \
        *(((uint32*)(x))+8) = *(((uint32*)(x))+9) =  \
        *(((uint32*)(x))+10) = *(((uint32*)(x))+11) =  \
        *(((uint32*)(x))+12) = *(((uint32*)(x))+13) =  \
        *(((uint32*)(x))+14) = *(((uint32*)(x))+15) =  0; }



#endif /* _PVDECDEF_H_ */
