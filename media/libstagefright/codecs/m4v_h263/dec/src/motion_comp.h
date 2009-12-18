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
#ifndef motion_comp_h
#define motion_comp_h

/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include "mp4dec_lib.h"

/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here.
----------------------------------------------------------------------------*/
/* CBP Mask defines used in chrominance prediction */
#define CBP_MASK_CHROMA_BLK4    0x2
#define CBP_MASK_CHROMA_BLK5    0x1

/* CBP Mask defines used in luminance prediction (MODE_INTER4V) */
#define CBP_MASK_BLK0_MODE_INTER4V  0x20
#define CBP_MASK_BLK1_MODE_INTER4V  0x10
#define CBP_MASK_BLK2_MODE_INTER4V  0x08
#define CBP_MASK_BLK3_MODE_INTER4V  0x04

/* CBP Mask defines used in luminance prediction (MODE_INTER or MODE_INTER_Q) */
#define CBP_MASK_MB_MODE_INTER  0x3c

/*----------------------------------------------------------------------------
; EXTERNAL VARIABLES REFERENCES
; Declare variables used in this module but defined elsewhere
----------------------------------------------------------------------------*/
#ifdef __cplusplus
extern "C"
{
#endif

#define CLIP_RESULT(x)      if(x & -256){x = 0xFF & (~(x>>31));}
#define ADD_AND_CLIP1(x)    x += (pred_word&0xFF); CLIP_RESULT(x);
#define ADD_AND_CLIP2(x)    x += ((pred_word>>8)&0xFF); CLIP_RESULT(x);
#define ADD_AND_CLIP3(x)    x += ((pred_word>>16)&0xFF); CLIP_RESULT(x);
#define ADD_AND_CLIP4(x)    x += ((pred_word>>24)&0xFF); CLIP_RESULT(x);

#define ADD_AND_CLIP(x,y)    {  x9 = ~(x>>8); \
                            if(x9!=-1){ \
                                x9 = ((uint32)x9)>>24; \
                                y = x9|(y<<8); \
                            } \
                            else \
                            {    \
                                y =  x|(y<<8); \
                            } \
                            }


    static int (*const GetPredAdvBTable[2][2])(uint8*, uint8*, int, int) =
    {
        {&GetPredAdvancedBy0x0, &GetPredAdvancedBy0x1},
        {&GetPredAdvancedBy1x0, &GetPredAdvancedBy1x1}
    };

    /*----------------------------------------------------------------------------
    ; SIMPLE TYPEDEF'S
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; ENUMERATED TYPEDEF'S
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; STRUCTURES TYPEDEF'S
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; GLOBAL FUNCTION DEFINITIONS
    ; Function Prototype declaration
    ----------------------------------------------------------------------------*/

    /*----------------------------------------------------------------------------
    ; END
    ----------------------------------------------------------------------------*/
#endif

#ifdef __cplusplus
}
#endif



