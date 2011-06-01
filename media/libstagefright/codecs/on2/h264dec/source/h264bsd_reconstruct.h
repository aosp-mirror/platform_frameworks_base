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

#ifndef H264SWDEC_RECONSTRUCT_H
#define H264SWDEC_RECONSTRUCT_H

/*------------------------------------------------------------------------------
    1. Include headers
------------------------------------------------------------------------------*/

#include "basetype.h"
#include "h264bsd_macroblock_layer.h"
#include "h264bsd_image.h"

/*------------------------------------------------------------------------------
    2. Module defines
------------------------------------------------------------------------------*/

/*------------------------------------------------------------------------------
    3. Data types
------------------------------------------------------------------------------*/

/*------------------------------------------------------------------------------
    4. Function prototypes
------------------------------------------------------------------------------*/
#ifndef H264DEC_OMXDL
void h264bsdPredictSamples(
  u8 *data,
  mv_t *mv,
  image_t *refPic,
  u32 xA,
  u32 yA,
  u32 partX,
  u32 partY,
  u32 partWidth,
  u32 partHeight);
#else
void h264bsdPredictSamples(
  u8 *data,
  mv_t *mv,
  image_t *refPic,
  u32 colAndRow,/* packaged data | column    | row                |*/
  u32 part,     /* packaged data |partX|partY|partWidth|partHeight|*/
  u8 *pFill);
#endif

void h264bsdFillBlock(
  u8 * ref,
  u8 * fill,
  i32 x0,
  i32 y0,
  u32 width,
  u32 height,
  u32 blockWidth,
  u32 blockHeight,
  u32 fillScanLength);

void h264bsdInterpolateChromaHor(
  u8 *pRef,
  u8 *predPartChroma,
  i32 x0,
  i32 y0,
  u32 width,
  u32 height,
  u32 xFrac,
  u32 chromaPartWidth,
  u32 chromaPartHeight);

void h264bsdInterpolateChromaVer(
  u8 *pRef,
  u8 *predPartChroma,
  i32 x0,
  i32 y0,
  u32 width,
  u32 height,
  u32 yFrac,
  u32 chromaPartWidth,
  u32 chromaPartHeight);

void h264bsdInterpolateChromaHorVer(
  u8 *ref,
  u8 *predPartChroma,
  i32 x0,
  i32 y0,
  u32 width,
  u32 height,
  u32 xFrac,
  u32 yFrac,
  u32 chromaPartWidth,
  u32 chromaPartHeight);

void h264bsdInterpolateVerHalf(
  u8 *ref,
  u8 *mb,
  i32 x0,
  i32 y0,
  u32 width,
  u32 height,
  u32 partWidth,
  u32 partHeight);

void h264bsdInterpolateVerQuarter(
  u8 *ref,
  u8 *mb,
  i32 x0,
  i32 y0,
  u32 width,
  u32 height,
  u32 partWidth,
  u32 partHeight,
  u32 verOffset);

void h264bsdInterpolateHorHalf(
  u8 *ref,
  u8 *mb,
  i32 x0,
  i32 y0,
  u32 width,
  u32 height,
  u32 partWidth,
  u32 partHeight);

void h264bsdInterpolateHorQuarter(
  u8 *ref,
  u8 *mb,
  i32 x0,
  i32 y0,
  u32 width,
  u32 height,
  u32 partWidth,
  u32 partHeight,
  u32 horOffset);

void h264bsdInterpolateHorVerQuarter(
  u8 *ref,
  u8 *mb,
  i32 x0,
  i32 y0,
  u32 width,
  u32 height,
  u32 partWidth,
  u32 partHeight,
  u32 horVerOffset);

void h264bsdInterpolateMidHalf(
  u8 *ref,
  u8 *mb,
  i32 x0,
  i32 y0,
  u32 width,
  u32 height,
  u32 partWidth,
  u32 partHeight);

void h264bsdInterpolateMidVerQuarter(
  u8 *ref,
  u8 *mb,
  i32 x0,
  i32 y0,
  u32 width,
  u32 height,
  u32 partWidth,
  u32 partHeight,
  u32 verOffset);

void h264bsdInterpolateMidHorQuarter(
  u8 *ref,
  u8 *mb,
  i32 x0,
  i32 y0,
  u32 width,
  u32 height,
  u32 partWidth,
  u32 partHeight,
  u32 horOffset);


void h264bsdFillRow7(
  u8 *ref,
  u8 *fill,
  i32 left,
  i32 center,
  i32 right);

#endif /* #ifdef H264SWDEC_RECONSTRUCT_H */

