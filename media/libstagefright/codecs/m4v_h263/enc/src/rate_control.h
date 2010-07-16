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
#ifndef _RATE_CONTROL_H_
#define _RATE_CONTROL_H_

#include "mp4def.h"

typedef struct tagdataPointArray
{
    Int Qp;
    Int Rp;
    float Mp;   /* for MB-based RC, 3/14/01 */
    struct tagdataPointArray *next;
    struct tagdataPointArray *prev;
} dataPointArray;


typedef struct
{
    Int alpha;  /* weight for I frame */
    Int Rs;     /*bit rate for the sequence (or segment) e.g., 24000 bits/sec */
    Int Rc;     /*bits used for the current frame. It is the bit count obtained after encoding. */
    Int Rp;     /*bits to be removed from the buffer per picture. */
    /*? is this the average one, or just the bits coded for the previous frame */
    Int Rps;    /*bit to be removed from buffer per src frame */
    float Ts;   /*number of seconds for the sequence  (or segment). e.g., 10 sec */
    float Ep;
    float Ec;   /*mean absolute difference for the current frame after motion compensation.*/
    /*If the macroblock is intra coded, the original spatial pixel values are summed.*/
    Int Qc;     /*quantization level used for the current frame. */
    Int Nr;     /*number of P frames remaining for encoding.*/
    Int Rr; /*number of bits remaining for encoding this sequence (or segment).*/
    Int Rr_Old;/* 12/24/00 */
    Int T;      /*target bit to be used for the current frame.*/
    Int S;      /*number of bits used for encoding the previous frame.*/
    Int Hc; /*header and motion vector bits used in the current frame. It includes all the  information except to the residual information.*/
    Int Hp; /*header and motion vector bits used in the previous frame. It includes all the     information except to the residual information.*/
    Int Ql; /*quantization level used in the previous frame */
    Int Bs; /*buffer size e.g., R/2 */
    Int B;      /*current buffer level e.g., R/4 - start from the middle of the buffer */
    float X1;
    float X2;
    float X11;
    float M;            /*safe margin for the buffer */
    float smTick;    /*ratio of src versus enc frame rate */
    double remnant;  /*remainder frame of src/enc frame for fine frame skipping */
    Int timeIncRes; /* vol->timeIncrementResolution */

    dataPointArray   *end; /*quantization levels for the past (20) frames */

    Int     frameNumber; /* ranging from 0 to 20 nodes*/
    Int     w;
    Int     Nr_Original;
    Int     Nr_Old, Nr_Old2;
    Int     skip_next_frame;
    Int     Qdep;       /* smooth Q adjustment */
    Int     fine_frame_skip;
    Int     VBR_Enabled;
    Int     no_frame_skip;
    Int     no_pre_skip;

    Int totalFrameNumber; /* total coded frames, for debugging!!*/

    char    oFirstTime;

    /* BX rate control */
    Int     TMN_W;
    Int     TMN_TH;
    Int     VBV_fullness;
    Int     max_BitVariance_num; /* the number of the maximum bit variance within the given buffer with the unit of 10% of bitrate/framerate*/
    Int     encoded_frames; /* counter for all encoded frames */
    float   framerate;
    Int     bitrate;
    Int     low_bound;              /* bound for underflow detection, usually low_bound=-Bs/2, but could be changed in H.263 mode */
    Int     VBV_fullness_offset;    /* offset of VBV_fullness, usually is zero, but can be changed in H.263 mode*/
    /* End BX */

} rateControl;


#endif /* _RATE_CONTROL_H_ */
