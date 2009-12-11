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
This file contains application function interfaces to the AVC decoder library
and necessary type defitionitions and enumerations.
Naming convention for variables:
lower_case_with_under_line  is  syntax element in subclause 7.2 and 7.3
noUnderLine or NoUnderLine  is  derived variables defined somewhere else in the draft
                                or introduced by this decoder library.
@publishedAll
*/

#ifndef _AVCDEC_INT_H_
#define _AVCDEC_INT_H_

#include "avcint_common.h"
#include "avcdec_api.h"


/**
Bitstream structure contains bitstream related parameters such as the pointer
to the buffer, the current byte position and bit position.
@publishedAll
*/
typedef struct tagDecBitstream
{
    uint8 *bitstreamBuffer; /* pointer to buffer memory   */
    int nal_size;       /* size of the current NAL unit */
    int data_end_pos;  /* bitstreamBuffer size in bytes */
    int read_pos;       /* next position to read from bitstreamBuffer  */
    uint curr_word; /* byte-swapped (MSB left) current word read from buffer */
    int bit_left;      /* number of bit left in current_word */
    uint next_word;     /* in case for old data in previous buffer hasn't been flushed. */
    int incnt;  /* bit left in the prev_word */
    int incnt_next;
    int bitcnt;
    void *userData;
} AVCDecBitstream;

/**
This structure is the main object for AVC decoder library providing access to all
global variables. It is allocated at PVAVCInitDecoder and freed at PVAVCCleanUpDecoder.
@publishedAll
*/
typedef struct tagDecObject
{

    AVCCommonObj *common;

    AVCDecBitstream     *bitstream; /* for current NAL */

    /* sequence parameter set */
    AVCSeqParamSet *seqParams[32]; /* Array of pointers, get allocated at arrival of new seq_id */

    /* picture parameter set */
    AVCPicParamSet *picParams[256]; /* Array of pointers to picture param set structures */

    /* For internal operation, scratch memory for MV, prediction, transform, etc.*/
    uint    ref_idx_l0[4]; /* [mbPartIdx], te(v) */
    uint    ref_idx_l1[4];

    /* function pointers */
    AVCDec_Status(*residual_block)(struct tagDecObject*, int,  int,
                                   int *, int *, int *);
    /* Application control data */
    AVCHandle *avcHandle;
    void (*AVC_DebugLog)(AVCLogType type, char *string1, char *string2);
    /*bool*/
    uint    debugEnable;

} AVCDecObject;

#endif /* _AVCDEC_INT_H_ */
