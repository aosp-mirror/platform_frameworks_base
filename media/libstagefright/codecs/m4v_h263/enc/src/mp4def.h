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

#include <stdint.h> // for uint8_t, etc
#include <stdlib.h>
#include <string.h>

// Redefine the int types
typedef uint8_t uint8;
typedef uint16_t uint16;
typedef int16_t int16;
typedef uint32_t uint32;
typedef int32_t int32;
typedef unsigned int uint;

/********** platform dependent in-line assembly *****************************/

/*************** Intel *****************/

/*************** ARM *****************/
/* for general ARM instruction. #define __ARM has to be defined in compiler set up.*/
/* for DSP MUL */
#ifdef __TARGET_FEATURE_DSPMUL
#define _ARM_DSP_MUL
#endif

/* for Count Leading Zero instruction */
#ifdef __TARGET_ARCH_5T
#define _ARM_CLZ
#endif
#ifdef __TARGET_ARCH_5TE
#define _ARM_CLZ
#endif
/****************************************************************************/

#ifndef _PV_TYPES_
#define _PV_TYPES_
typedef unsigned char UChar;
typedef char Char;
typedef unsigned int UInt;
typedef int Int;
typedef unsigned short UShort;
typedef short Short;
typedef short int SInt;
typedef unsigned int Bool;
typedef unsigned long   ULong;
typedef void Void;

#define PV_CODEC_INIT       0
#define PV_CODEC_STOP       1
#define PV_CODEC_RUNNING    2
#define PV_CODEC_RESET      3
#endif

typedef enum
{
    PV_SUCCESS,
    PV_FAIL,
    PV_EOS,             /* hit End_Of_Sequence     */
    PV_MB_STUFFING,     /* hit Macroblock_Stuffing */
    PV_END_OF_VOP,      /* hit End_of_Video_Object_Plane */
    PV_END_OF_MB,       /* hit End_of_Macroblock */
    PV_END_OF_BUF       /* hit End_of_Bitstream_Buffer */
} PV_STATUS;

typedef UChar PIXEL;
//typedef Int MOT;   /* : "int" type runs faster on RISC machine */

#define HTFM            /*  3/2/01, Hypothesis Test Fast Matching for early drop-out*/
//#define _MOVE_INTERFACE

//#define RANDOM_REFSELCODE

/* handle the case of devision by zero in RC */
#define MAD_MIN 1

/* 4/11/01, if SSE or MMX, no HTFM, no SAD_HP_FLY */

/* Code size reduction related Macros */
#ifdef H263_ONLY
#ifndef NO_RVLC
#define NO_RVLC
#endif
#ifndef NO_MPEG_QUANT
#define NO_MPEG_QUANT
#endif
#ifndef NO_INTER4V
#define NO_INTER4V
#endif
#endif
/**************************************/

#define TRUE    1
#define FALSE   0

#define PV_ABS(x)       (((x)<0)? -(x) : (x))
#define PV_SIGN(x)      (((x)<0)? -1 : 1)
#define PV_SIGN0(a)     (((a)<0)? -1 : (((a)>0) ? 1 : 0))
#define PV_MAX(a,b)     ((a)>(b)? (a):(b))
#define PV_MIN(a,b)     ((a)<(b)? (a):(b))

#define MODE_INTRA      0
#define MODE_INTER      1
#define MODE_INTRA_Q    2
#define MODE_INTER_Q    3
#define MODE_INTER4V    4
#define MODE_SKIPPED    6

#define I_VOP       0
#define P_VOP       1
#define B_VOP       2

/*09/04/00 Add MB height and width */
#define MB_WIDTH 16
#define MB_HEIGHT 16

#define VOP_BRIGHT_WHITEENC 255


#define LUMINANCE_DC_TYPE   1
#define CHROMINANCE_DC_TYPE 2

#define EOB_CODE                        1
#define EOB_CODE_LENGTH                32

/* 11/30/98 */
#define FoundRM     1   /* Resync Marker */
#define FoundVSC    2   /* VOP_START_CODE. */
#define FoundGSC    3   /* GROUP_START_CODE */
#define FoundEOB    4   /* EOB_CODE */


/* 05/08/2000, the error code returned from BitstreamShowBits() */
#define BITSTREAM_ERROR_CODE 0xFFFFFFFF

/* PacketVideo "absolution timestamp" object.  06/13/2000 */
#define PVTS_START_CODE         0x01C4
#define PVTS_START_CODE_LENGTH  32

/* session layer and vop layer start codes */

#define SESSION_START_CODE  0x01B0
#define SESSION_END_CODE    0x01B1
#define VISUAL_OBJECT_START_CODE 0x01B5

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

/* overrun buffer size  */
#define DEFAULT_OVERRUN_BUFFER_SIZE 1000


/* VLC decoding related definitions */
#define VLC_ERROR   (-1)
#define VLC_ESCAPE  7167

#endif /* _PVDECDEF_H_ */
