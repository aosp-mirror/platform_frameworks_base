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

#ifndef H264SWDEC_INTRA_PREDICTION_H
#define H264SWDEC_INTRA_PREDICTION_H

/*------------------------------------------------------------------------------
    1. Include headers
------------------------------------------------------------------------------*/

#include "basetype.h"
#include "h264bsd_image.h"
#include "h264bsd_macroblock_layer.h"

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
u32 h264bsdIntraPrediction(mbStorage_t *pMb, macroblockLayer_t *mbLayer,
    image_t *image, u32 mbNum, u32 constrainedIntraPred, u8 *data);

u32 h264bsdIntra4x4Prediction(mbStorage_t *pMb, u8 *data,
                              macroblockLayer_t *mbLayer,
                              u8 *above, u8 *left, u32 constrainedIntraPred);
u32 h264bsdIntra16x16Prediction(mbStorage_t *pMb, u8 *data, i32 residual[][16],
    u8 *above, u8 *left, u32 constrainedIntraPred);

u32 h264bsdIntraChromaPrediction(mbStorage_t *pMb, u8 *data, i32 residual[][16],
    u8 *above, u8 *left, u32 predMode, u32 constrainedIntraPred);

void h264bsdGetNeighbourPels(image_t *image, u8 *above, u8 *left, u32 mbNum);

#else

u32 h264bsdIntra4x4Prediction(mbStorage_t *pMb, u8 *data,
                              macroblockLayer_t *mbLayer,
                              u8 *pImage, u32 width,
                              u32 constrainedIntraPred, u32 block);

u32 h264bsdIntra16x16Prediction(mbStorage_t *pMb, u8 *data, u8 *pImage,
                            u32 width, u32 constrainedIntraPred);

u32 h264bsdIntraChromaPrediction(mbStorage_t *pMb, u8 *data, image_t *image,
                                        u32 predMode, u32 constrainedIntraPred);

#endif

#endif /* #ifdef H264SWDEC_INTRA_PREDICTION_H */

