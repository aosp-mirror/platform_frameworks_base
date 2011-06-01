/*
 * Copyright (C) 2009 The Android Open Source Project
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

/*------------------------------------------------------------------------------

    Table of contents

    1. Include headers
    2. Module defines
    3. Data types
    4. Function prototypes

------------------------------------------------------------------------------*/

#ifndef H264SWDEC_STORAGE_H
#define H264SWDEC_STORAGE_H

/*------------------------------------------------------------------------------
    1. Include headers
------------------------------------------------------------------------------*/

#include "basetype.h"
#include "h264bsd_cfg.h"
#include "h264bsd_seq_param_set.h"
#include "h264bsd_pic_param_set.h"
#include "h264bsd_macroblock_layer.h"
#include "h264bsd_nal_unit.h"
#include "h264bsd_slice_header.h"
#include "h264bsd_seq_param_set.h"
#include "h264bsd_dpb.h"
#include "h264bsd_pic_order_cnt.h"

/*------------------------------------------------------------------------------
    2. Module defines
------------------------------------------------------------------------------*/

/*------------------------------------------------------------------------------
    3. Data types
------------------------------------------------------------------------------*/

typedef struct
{
    u32 sliceId;
    u32 numDecodedMbs;
    u32 lastMbAddr;
} sliceStorage_t;

/* structure to store parameters needed for access unit boundary checking */
typedef struct
{
    nalUnit_t nuPrev[1];
    u32 prevFrameNum;
    u32 prevIdrPicId;
    u32 prevPicOrderCntLsb;
    i32 prevDeltaPicOrderCntBottom;
    i32 prevDeltaPicOrderCnt[2];
    u32 firstCallFlag;
} aubCheck_t;

/* storage data structure, holds all data of a decoder instance */
typedef struct
{
    /* active paramet set ids and pointers */
    u32 oldSpsId;
    u32 activePpsId;
    u32 activeSpsId;
    picParamSet_t *activePps;
    seqParamSet_t *activeSps;
    seqParamSet_t *sps[MAX_NUM_SEQ_PARAM_SETS];
    picParamSet_t *pps[MAX_NUM_PIC_PARAM_SETS];

    /* current slice group map, recomputed for each slice */
    u32 *sliceGroupMap;

    u32 picSizeInMbs;

    /* this flag is set after all macroblocks of a picture successfully
     * decoded -> redundant slices not decoded */
    u32 skipRedundantSlices;
    u32 picStarted;

    /* flag to indicate if current access unit contains any valid slices */
    u32 validSliceInAccessUnit;

    /* store information needed for handling of slice decoding */
    sliceStorage_t slice[1];

    /* number of concealed macroblocks in the current image */
    u32 numConcealedMbs;

    /* picId given by application */
    u32 currentPicId;

    /* macroblock specific storages, size determined by image dimensions */
    mbStorage_t *mb;

    /* flag to store noOutputReordering flag set by the application */
    u32 noReordering;

    /* DPB */
    dpbStorage_t dpb[1];

    /* structure to store picture order count related information */
    pocStorage_t poc[1];

    /* access unit boundary checking related data */
    aubCheck_t aub[1];

    /* current processed image */
    image_t currImage[1];

    /* last valid NAL unit header is stored here */
    nalUnit_t prevNalUnit[1];

    /* slice header, second structure used as a temporary storage while
     * decoding slice header, first one stores last successfully decoded
     * slice header */
    sliceHeader_t sliceHeader[2];

    /* fields to store old stream buffer pointers, needed when only part of
     * a stream buffer is processed by h264bsdDecode function */
    u32 prevBufNotFinished;
    u8 *prevBufPointer;
    u32 prevBytesConsumed;
    strmData_t strm[1];

    /* macroblock layer structure, there is no need to store this but it
     * would have increased the stack size excessively and needed to be
     * allocated from head -> easiest to put it here */
    macroblockLayer_t *mbLayer;

    u32 pendingActivation; /* Activate parameter sets after returning
                              HEADERS_RDY to the user */
    u32 intraConcealmentFlag; /* 0 gray picture for corrupted intra
                                 1 previous frame used if available */
} storage_t;

/*------------------------------------------------------------------------------
    4. Function prototypes
------------------------------------------------------------------------------*/

void h264bsdInitStorage(storage_t *pStorage);
void h264bsdResetStorage(storage_t *pStorage);
u32 h264bsdIsStartOfPicture(storage_t *pStorage);
u32 h264bsdIsEndOfPicture(storage_t *pStorage);
u32 h264bsdStoreSeqParamSet(storage_t *pStorage, seqParamSet_t *pSeqParamSet);
u32 h264bsdStorePicParamSet(storage_t *pStorage, picParamSet_t *pPicParamSet);
u32 h264bsdActivateParamSets(storage_t *pStorage, u32 ppsId, u32 isIdr);
void h264bsdComputeSliceGroupMap(storage_t *pStorage,
    u32 sliceGroupChangeCycle);

u32 h264bsdCheckAccessUnitBoundary(
  strmData_t *strm,
  nalUnit_t *nuNext,
  storage_t *storage,
  u32 *accessUnitBoundaryFlag);

u32 h264bsdValidParamSets(storage_t *pStorage);

#endif /* #ifdef H264SWDEC_STORAGE_H */

