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
     2. External compiler flags
     3. Module defines
     4. Local function prototypes
     5. Functions
          h264bsdIntraPrediction
          h264bsdGetNeighbourPels
          h264bsdIntra16x16Prediction
          h264bsdIntra4x4Prediction
          h264bsdIntraChromaPrediction
          h264bsdAddResidual
          Intra16x16VerticalPrediction
          Intra16x16HorizontalPrediction
          Intra16x16DcPrediction
          Intra16x16PlanePrediction
          IntraChromaDcPrediction
          IntraChromaHorizontalPrediction
          IntraChromaVerticalPrediction
          IntraChromaPlanePrediction
          Get4x4NeighbourPels
          Write4x4To16x16
          Intra4x4VerticalPrediction
          Intra4x4HorizontalPrediction
          Intra4x4DcPrediction
          Intra4x4DiagonalDownLeftPrediction
          Intra4x4DiagonalDownRightPrediction
          Intra4x4VerticalRightPrediction
          Intra4x4HorizontalDownPrediction
          Intra4x4VerticalLeftPrediction
          Intra4x4HorizontalUpPrediction
          DetermineIntra4x4PredMode

------------------------------------------------------------------------------*/

/*------------------------------------------------------------------------------
    1. Include headers
------------------------------------------------------------------------------*/

#include "h264bsd_intra_prediction.h"
#include "h264bsd_util.h"
#include "h264bsd_macroblock_layer.h"
#include "h264bsd_neighbour.h"
#include "h264bsd_image.h"

#ifdef H264DEC_OMXDL
#include "omxtypes.h"
#include "omxVC.h"
#endif /* H264DEC_OMXDL */

/*------------------------------------------------------------------------------
    2. External compiler flags
--------------------------------------------------------------------------------

--------------------------------------------------------------------------------
    3. Module defines
------------------------------------------------------------------------------*/

/* Switch off the following Lint messages for this file:
 * Info 702: Shift right of signed quantity (int)
 */
/*lint -e702 */


/* x- and y-coordinates for each block */
const u32 h264bsdBlockX[16] =
    { 0, 4, 0, 4, 8, 12, 8, 12, 0, 4, 0, 4, 8, 12, 8, 12 };
const u32 h264bsdBlockY[16] =
    { 0, 0, 4, 4, 0, 0, 4, 4, 8, 8, 12, 12, 8, 8, 12, 12 };

const u8 h264bsdClip[1280] =
{
    0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
    0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
    0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
    0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
    0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
    0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
    0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
    0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
    0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
    0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
    0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
    0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
    0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
    0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
    0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
    0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
    0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,
    16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,
    32,33,34,35,36,37,38,39,40,41,42,43,44,45,46,47,
    48,49,50,51,52,53,54,55,56,57,58,59,60,61,62,63,
    64,65,66,67,68,69,70,71,72,73,74,75,76,77,78,79,
    80,81,82,83,84,85,86,87,88,89,90,91,92,93,94,95,
    96,97,98,99,100,101,102,103,104,105,106,107,108,109,110,111,
    112,113,114,115,116,117,118,119,120,121,122,123,124,125,126,127,
    128,129,130,131,132,133,134,135,136,137,138,139,140,141,142,143,
    144,145,146,147,148,149,150,151,152,153,154,155,156,157,158,159,
    160,161,162,163,164,165,166,167,168,169,170,171,172,173,174,175,
    176,177,178,179,180,181,182,183,184,185,186,187,188,189,190,191,
    192,193,194,195,196,197,198,199,200,201,202,203,204,205,206,207,
    208,209,210,211,212,213,214,215,216,217,218,219,220,221,222,223,
    224,225,226,227,228,229,230,231,232,233,234,235,236,237,238,239,
    240,241,242,243,244,245,246,247,248,249,250,251,252,253,254,255,
    255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
    255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
    255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
    255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
    255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
    255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
    255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
    255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
    255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
    255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
    255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
    255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
    255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
    255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
    255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
    255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
    255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
    255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
    255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
    255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
    255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
    255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
    255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
    255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
    255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
    255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
    255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
    255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
    255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
    255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
    255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
    255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255
};

#ifndef H264DEC_OMXDL
/*------------------------------------------------------------------------------
    4. Local function prototypes
------------------------------------------------------------------------------*/
static void Get4x4NeighbourPels(u8 *a, u8 *l, u8 *data, u8 *above, u8 *left,
    u32 blockNum);
static void Intra16x16VerticalPrediction(u8 *data, u8 *above);
static void Intra16x16HorizontalPrediction(u8 *data, u8 *left);
static void Intra16x16DcPrediction(u8 *data, u8 *above, u8 *left,
    u32 A, u32 B);
static void Intra16x16PlanePrediction(u8 *data, u8 *above, u8 *left);
static void IntraChromaDcPrediction(u8 *data, u8 *above, u8 *left,
    u32 A, u32 B);
static void IntraChromaHorizontalPrediction(u8 *data, u8 *left);
static void IntraChromaVerticalPrediction(u8 *data, u8 *above);
static void IntraChromaPlanePrediction(u8 *data, u8 *above, u8 *left);

static void Intra4x4VerticalPrediction(u8 *data, u8 *above);
static void Intra4x4HorizontalPrediction(u8 *data, u8 *left);
static void Intra4x4DcPrediction(u8 *data, u8 *above, u8 *left, u32 A, u32 B);
static void Intra4x4DiagonalDownLeftPrediction(u8 *data, u8 *above);
static void Intra4x4DiagonalDownRightPrediction(u8 *data, u8 *above, u8 *left);
static void Intra4x4VerticalRightPrediction(u8 *data, u8 *above, u8 *left);
static void Intra4x4HorizontalDownPrediction(u8 *data, u8 *above, u8 *left);
static void Intra4x4VerticalLeftPrediction(u8 *data, u8 *above);
static void Intra4x4HorizontalUpPrediction(u8 *data, u8 *left);
void h264bsdAddResidual(u8 *data, i32 *residual, u32 blockNum);

static void Write4x4To16x16(u8 *data, u8 *data4x4, u32 blockNum);
#endif /* H264DEC_OMXDL */

static u32 DetermineIntra4x4PredMode(macroblockLayer_t *pMbLayer,
    u32 available, neighbour_t *nA, neighbour_t *nB, u32 index,
    mbStorage_t *nMbA, mbStorage_t *nMbB);


#ifdef H264DEC_OMXDL

/*------------------------------------------------------------------------------

    Function: h264bsdIntra16x16Prediction

        Functional description:
          Perform intra 16x16 prediction mode for luma pixels and add
          residual into prediction. The resulting luma pixels are
          stored in macroblock array 'data'.

------------------------------------------------------------------------------*/
u32 h264bsdIntra16x16Prediction(mbStorage_t *pMb, u8 *data, u8 *ptr,
                                u32 width, u32 constrainedIntraPred)
{

/* Variables */

    u32 availableA, availableB, availableD;
    OMXResult omxRes;

/* Code */
    ASSERT(pMb);
    ASSERT(data);
    ASSERT(ptr);
    ASSERT(h264bsdPredModeIntra16x16(pMb->mbType) < 4);

    availableA = h264bsdIsNeighbourAvailable(pMb, pMb->mbA);
    if (availableA && constrainedIntraPred &&
       (h264bsdMbPartPredMode(pMb->mbA->mbType) == PRED_MODE_INTER))
        availableA = HANTRO_FALSE;
    availableB = h264bsdIsNeighbourAvailable(pMb, pMb->mbB);
    if (availableB && constrainedIntraPred &&
       (h264bsdMbPartPredMode(pMb->mbB->mbType) == PRED_MODE_INTER))
        availableB = HANTRO_FALSE;
    availableD = h264bsdIsNeighbourAvailable(pMb, pMb->mbD);
    if (availableD && constrainedIntraPred &&
       (h264bsdMbPartPredMode(pMb->mbD->mbType) == PRED_MODE_INTER))
        availableD = HANTRO_FALSE;

    omxRes = omxVCM4P10_PredictIntra_16x16( (ptr-1),
                                    (ptr - width),
                                    (ptr - width-1),
                                    data,
                                    (i32)width,
                                    16,
                                    (OMXVCM4P10Intra16x16PredMode)
                                    h264bsdPredModeIntra16x16(pMb->mbType),
                                    (i32)(availableB + (availableA<<1) +
                                     (availableD<<5)) );
    if (omxRes != OMX_Sts_NoErr)
        return HANTRO_NOK;
    else
        return(HANTRO_OK);
}

/*------------------------------------------------------------------------------

    Function: h264bsdIntra4x4Prediction

        Functional description:
          Perform intra 4x4 prediction for luma pixels and add residual
          into prediction. The resulting luma pixels are stored in
          macroblock array 'data'. The intra 4x4 prediction mode for each
          block is stored in 'pMb' structure.

------------------------------------------------------------------------------*/
u32 h264bsdIntra4x4Prediction(mbStorage_t *pMb, u8 *data,
                              macroblockLayer_t *mbLayer,
                              u8 *ptr, u32 width,
                              u32 constrainedIntraPred, u32 block)
{

/* Variables */
    u32 mode;
    neighbour_t neighbour, neighbourB;
    mbStorage_t *nMb, *nMb2;
    u32 availableA, availableB, availableC, availableD;

    OMXResult omxRes;
    u32 x, y;
    u8 *l, *a, *al;
/* Code */
    ASSERT(pMb);
    ASSERT(data);
    ASSERT(mbLayer);
    ASSERT(ptr);
    ASSERT(pMb->intra4x4PredMode[block] < 9);

    neighbour = *h264bsdNeighbour4x4BlockA(block);
    nMb = h264bsdGetNeighbourMb(pMb, neighbour.mb);
    availableA = h264bsdIsNeighbourAvailable(pMb, nMb);
    if (availableA && constrainedIntraPred &&
       ( h264bsdMbPartPredMode(nMb->mbType) == PRED_MODE_INTER) )
    {
        availableA = HANTRO_FALSE;
    }

    neighbourB = *h264bsdNeighbour4x4BlockB(block);
    nMb2 = h264bsdGetNeighbourMb(pMb, neighbourB.mb);
    availableB = h264bsdIsNeighbourAvailable(pMb, nMb2);
    if (availableB && constrainedIntraPred &&
       ( h264bsdMbPartPredMode(nMb2->mbType) == PRED_MODE_INTER) )
    {
        availableB = HANTRO_FALSE;
    }

    mode = DetermineIntra4x4PredMode(mbLayer,
        (u32)(availableA && availableB),
        &neighbour, &neighbourB, block, nMb, nMb2);
    pMb->intra4x4PredMode[block] = (u8)mode;

    neighbour = *h264bsdNeighbour4x4BlockC(block);
    nMb = h264bsdGetNeighbourMb(pMb, neighbour.mb);
    availableC = h264bsdIsNeighbourAvailable(pMb, nMb);
    if (availableC && constrainedIntraPred &&
       ( h264bsdMbPartPredMode(nMb->mbType) == PRED_MODE_INTER) )
    {
        availableC = HANTRO_FALSE;
    }

    neighbour = *h264bsdNeighbour4x4BlockD(block);
    nMb = h264bsdGetNeighbourMb(pMb, neighbour.mb);
    availableD = h264bsdIsNeighbourAvailable(pMb, nMb);
    if (availableD && constrainedIntraPred &&
       ( h264bsdMbPartPredMode(nMb->mbType) == PRED_MODE_INTER) )
    {
        availableD = HANTRO_FALSE;
    }

    x = h264bsdBlockX[block];
    y = h264bsdBlockY[block];

    if (y == 0)
        a = ptr - width + x;
    else
        a = data-16;

    if (x == 0)
        l = ptr + y * width -1;
    else
    {
        l = data-1;
        width = 16;
    }

    if (x == 0)
        al = l-width;
    else
        al = a-1;

    omxRes = omxVCM4P10_PredictIntra_4x4( l,
                                          a,
                                          al,
                                          data,
                                          (i32)width,
                                          16,
                                          (OMXVCM4P10Intra4x4PredMode)mode,
                                          (i32)(availableB +
                                          (availableA<<1) +
                                          (availableD<<5) +
                                          (availableC<<6)) );
    if (omxRes != OMX_Sts_NoErr)
        return HANTRO_NOK;

    return(HANTRO_OK);

}

/*------------------------------------------------------------------------------

    Function: h264bsdIntraChromaPrediction

        Functional description:
          Perform intra prediction for chroma pixels and add residual
          into prediction. The resulting chroma pixels are stored in 'data'.

------------------------------------------------------------------------------*/
u32 h264bsdIntraChromaPrediction(mbStorage_t *pMb, u8 *data, image_t *image,
                                        u32 predMode, u32 constrainedIntraPred)
{

/* Variables */

    u32 availableA, availableB, availableD;
    OMXResult omxRes;
    u8 *ptr;
    u32 width;

/* Code */
    ASSERT(pMb);
    ASSERT(data);
    ASSERT(image);
    ASSERT(predMode < 4);

    availableA = h264bsdIsNeighbourAvailable(pMb, pMb->mbA);
    if (availableA && constrainedIntraPred &&
       (h264bsdMbPartPredMode(pMb->mbA->mbType) == PRED_MODE_INTER))
        availableA = HANTRO_FALSE;
    availableB = h264bsdIsNeighbourAvailable(pMb, pMb->mbB);
    if (availableB && constrainedIntraPred &&
       (h264bsdMbPartPredMode(pMb->mbB->mbType) == PRED_MODE_INTER))
        availableB = HANTRO_FALSE;
    availableD = h264bsdIsNeighbourAvailable(pMb, pMb->mbD);
    if (availableD && constrainedIntraPred &&
       (h264bsdMbPartPredMode(pMb->mbD->mbType) == PRED_MODE_INTER))
        availableD = HANTRO_FALSE;

    ptr = image->cb;
    width = image->width*8;

    omxRes = omxVCM4P10_PredictIntraChroma_8x8( (ptr-1),
                                                (ptr - width),
                                                (ptr - width -1),
                                                data,
                                                (i32)width,
                                                8,
                                                (OMXVCM4P10IntraChromaPredMode)
                                                predMode,
                                                (i32)(availableB +
                                                 (availableA<<1) +
                                                 (availableD<<5)) );
    if (omxRes != OMX_Sts_NoErr)
        return HANTRO_NOK;

    /* advance pointers */
    data += 64;
    ptr = image->cr;

    omxRes = omxVCM4P10_PredictIntraChroma_8x8( (ptr-1),
                                                (ptr - width),
                                                (ptr - width -1),
                                                data,
                                                (i32)width,
                                                8,
                                                (OMXVCM4P10IntraChromaPredMode)
                                                predMode,
                                                (i32)(availableB +
                                                 (availableA<<1) +
                                                 (availableD<<5)) );
    if (omxRes != OMX_Sts_NoErr)
        return HANTRO_NOK;

    return(HANTRO_OK);

}


#else /* H264DEC_OMXDL */


/*------------------------------------------------------------------------------

    Function: h264bsdIntraPrediction

        Functional description:
          Processes one intra macroblock. Performs intra prediction using
          specified prediction mode. Writes the final macroblock
          (prediction + residual) into the output image (image)

        Inputs:
          pMb           pointer to macroblock specific information
          mbLayer       pointer to current macroblock data from stream
          image         pointer to output image
          mbNum         current macroblock number
          constrainedIntraPred  flag specifying if neighbouring inter
                                macroblocks are used in intra prediction
          data          pointer where output macroblock will be stored

        Outputs:
          pMb           structure is updated with current macroblock
          image         current macroblock is written into image
          data          current macroblock is stored here

        Returns:
          HANTRO_OK     success
          HANTRO_NOK    error in intra prediction

------------------------------------------------------------------------------*/
u32 h264bsdIntraPrediction(mbStorage_t *pMb, macroblockLayer_t *mbLayer,
    image_t *image, u32 mbNum, u32 constrainedIntraPred, u8 *data)
{

/* Variables */

    /* pelAbove and pelLeft contain samples above and left to the current
     * macroblock. Above array contains also sample above-left to the current
     * mb as well as 4 samples above-right to the current mb (latter only for
     * luma) */
    /* lumD + lumB + lumC + cbD + cbB + crD + crB */
    u8 pelAbove[1 + 16 + 4 + 1 + 8 + 1 + 8];
    /* lumA + cbA + crA */
    u8 pelLeft[16 + 8 + 8];
    u32 tmp;

/* Code */

    ASSERT(pMb);
    ASSERT(image);
    ASSERT(mbNum < image->width * image->height);
    ASSERT(h264bsdMbPartPredMode(pMb->mbType) != PRED_MODE_INTER);

    h264bsdGetNeighbourPels(image, pelAbove, pelLeft, mbNum);

    if (h264bsdMbPartPredMode(pMb->mbType) == PRED_MODE_INTRA16x16)
    {
        tmp = h264bsdIntra16x16Prediction(pMb, data, mbLayer->residual.level,
            pelAbove, pelLeft, constrainedIntraPred);
        if (tmp != HANTRO_OK)
            return(tmp);
    }
    else
    {
        tmp = h264bsdIntra4x4Prediction(pMb, data, mbLayer,
            pelAbove, pelLeft, constrainedIntraPred);
        if (tmp != HANTRO_OK)
            return(tmp);
    }

    tmp = h264bsdIntraChromaPrediction(pMb, data + 256,
            mbLayer->residual.level+16, pelAbove + 21, pelLeft + 16,
            mbLayer->mbPred.intraChromaPredMode, constrainedIntraPred);
    if (tmp != HANTRO_OK)
        return(tmp);

    /* if decoded flag > 1 -> mb has already been successfully decoded and
     * written to output -> do not write again */
    if (pMb->decoded > 1)
        return HANTRO_OK;

    h264bsdWriteMacroblock(image, data);

    return(HANTRO_OK);

}

/*------------------------------------------------------------------------------

    Function: h264bsdGetNeighbourPels

        Functional description:
          Get pixel values from neighbouring macroblocks into 'above'
          and 'left' arrays.

------------------------------------------------------------------------------*/

void h264bsdGetNeighbourPels(image_t *image, u8 *above, u8 *left, u32 mbNum)
{

/* Variables */

    u32 i;
    u32 width, picSize;
    u8 *ptr, *tmp;
    u32 row, col;

/* Code */

    ASSERT(image);
    ASSERT(above);
    ASSERT(left);
    ASSERT(mbNum < image->width * image->height);

    if (!mbNum)
        return;

    width = image->width;
    picSize = width * image->height;
    row = mbNum / width;
    col = mbNum - row * width;

    width *= 16;
    ptr = image->data + row * 16 * width  + col * 16;

    /* note that luma samples above-right to current macroblock do not make
     * sense when current mb is the right-most mb in a row. Same applies to
     * sample above-left if col is zero. However, usage of pels in prediction
     * is controlled by neighbour availability information in actual prediction
     * process */
    if (row)
    {
        tmp = ptr - (width + 1);
        for (i = 21; i--;)
            *above++ = *tmp++;
    }

    if (col)
    {
        ptr--;
        for (i = 16; i--; ptr+=width)
            *left++ = *ptr;
    }

    width >>= 1;
    ptr = image->data + picSize * 256 + row * 8 * width  + col * 8;

    if (row)
    {
        tmp = ptr - (width + 1);
        for (i = 9; i--;)
            *above++ = *tmp++;
        tmp += (picSize * 64) - 9;
        for (i = 9; i--;)
            *above++ = *tmp++;
    }

    if (col)
    {
        ptr--;
        for (i = 8; i--; ptr+=width)
            *left++ = *ptr;
        ptr += (picSize * 64) - 8 * width;
        for (i = 8; i--; ptr+=width)
            *left++ = *ptr;
    }
}

/*------------------------------------------------------------------------------

    Function: Intra16x16Prediction

        Functional description:
          Perform intra 16x16 prediction mode for luma pixels and add
          residual into prediction. The resulting luma pixels are
          stored in macroblock array 'data'.

------------------------------------------------------------------------------*/

u32 h264bsdIntra16x16Prediction(mbStorage_t *pMb, u8 *data, i32 residual[][16],
                                u8 *above, u8 *left, u32 constrainedIntraPred)
{

/* Variables */

    u32 i;
    u32 availableA, availableB, availableD;

/* Code */

    ASSERT(data);
    ASSERT(residual);
    ASSERT(above);
    ASSERT(left);
    ASSERT(h264bsdPredModeIntra16x16(pMb->mbType) < 4);

    availableA = h264bsdIsNeighbourAvailable(pMb, pMb->mbA);
    if (availableA && constrainedIntraPred &&
       (h264bsdMbPartPredMode(pMb->mbA->mbType) == PRED_MODE_INTER))
        availableA = HANTRO_FALSE;
    availableB = h264bsdIsNeighbourAvailable(pMb, pMb->mbB);
    if (availableB && constrainedIntraPred &&
       (h264bsdMbPartPredMode(pMb->mbB->mbType) == PRED_MODE_INTER))
        availableB = HANTRO_FALSE;
    availableD = h264bsdIsNeighbourAvailable(pMb, pMb->mbD);
    if (availableD && constrainedIntraPred &&
       (h264bsdMbPartPredMode(pMb->mbD->mbType) == PRED_MODE_INTER))
        availableD = HANTRO_FALSE;

    switch(h264bsdPredModeIntra16x16(pMb->mbType))
    {
        case 0: /* Intra_16x16_Vertical */
            if (!availableB)
                return(HANTRO_NOK);
            Intra16x16VerticalPrediction(data, above+1);
            break;

        case 1: /* Intra_16x16_Horizontal */
            if (!availableA)
                return(HANTRO_NOK);
            Intra16x16HorizontalPrediction(data, left);
            break;

        case 2: /* Intra_16x16_DC */
            Intra16x16DcPrediction(data, above+1, left, availableA, availableB);
            break;

        default: /* case 3: Intra_16x16_Plane */
            if (!availableA || !availableB || !availableD)
                return(HANTRO_NOK);
            Intra16x16PlanePrediction(data, above+1, left);
            break;
    }
    /* add residual */
    for (i = 0; i < 16; i++)
        h264bsdAddResidual(data, residual[i], i);

    return(HANTRO_OK);

}

/*------------------------------------------------------------------------------

    Function: Intra4x4Prediction

        Functional description:
          Perform intra 4x4 prediction for luma pixels and add residual
          into prediction. The resulting luma pixels are stored in
          macroblock array 'data'. The intra 4x4 prediction mode for each
          block is stored in 'pMb' structure.

------------------------------------------------------------------------------*/

u32 h264bsdIntra4x4Prediction(mbStorage_t *pMb, u8 *data,
                              macroblockLayer_t *mbLayer, u8 *above,
                              u8 *left, u32 constrainedIntraPred)
{

/* Variables */

    u32 block;
    u32 mode;
    neighbour_t neighbour, neighbourB;
    mbStorage_t *nMb, *nMb2;
    u8 a[1 + 4 + 4], l[1 + 4];
    u32 data4x4[4];
    u32 availableA, availableB, availableC, availableD;

/* Code */

    ASSERT(data);
    ASSERT(mbLayer);
    ASSERT(above);
    ASSERT(left);

    for (block = 0; block < 16; block++)
    {

        ASSERT(pMb->intra4x4PredMode[block] < 9);

        neighbour = *h264bsdNeighbour4x4BlockA(block);
        nMb = h264bsdGetNeighbourMb(pMb, neighbour.mb);
        availableA = h264bsdIsNeighbourAvailable(pMb, nMb);
        if (availableA && constrainedIntraPred &&
           ( h264bsdMbPartPredMode(nMb->mbType) == PRED_MODE_INTER) )
        {
            availableA = HANTRO_FALSE;
        }

        neighbourB = *h264bsdNeighbour4x4BlockB(block);
        nMb2 = h264bsdGetNeighbourMb(pMb, neighbourB.mb);
        availableB = h264bsdIsNeighbourAvailable(pMb, nMb2);
        if (availableB && constrainedIntraPred &&
           ( h264bsdMbPartPredMode(nMb2->mbType) == PRED_MODE_INTER) )
        {
            availableB = HANTRO_FALSE;
        }

        mode = DetermineIntra4x4PredMode(mbLayer,
            (u32)(availableA && availableB),
            &neighbour, &neighbourB, block, nMb, nMb2);
        pMb->intra4x4PredMode[block] = (u8)mode;

        neighbour = *h264bsdNeighbour4x4BlockC(block);
        nMb = h264bsdGetNeighbourMb(pMb, neighbour.mb);
        availableC = h264bsdIsNeighbourAvailable(pMb, nMb);
        if (availableC && constrainedIntraPred &&
           ( h264bsdMbPartPredMode(nMb->mbType) == PRED_MODE_INTER) )
        {
            availableC = HANTRO_FALSE;
        }

        neighbour = *h264bsdNeighbour4x4BlockD(block);
        nMb = h264bsdGetNeighbourMb(pMb, neighbour.mb);
        availableD = h264bsdIsNeighbourAvailable(pMb, nMb);
        if (availableD && constrainedIntraPred &&
           ( h264bsdMbPartPredMode(nMb->mbType) == PRED_MODE_INTER) )
        {
            availableD = HANTRO_FALSE;
        }

        Get4x4NeighbourPels(a, l, data, above, left, block);

        switch(mode)
        {
            case 0: /* Intra_4x4_Vertical */
                if (!availableB)
                    return(HANTRO_NOK);
                Intra4x4VerticalPrediction((u8*)data4x4, a + 1);
                break;
            case 1: /* Intra_4x4_Horizontal */
                if (!availableA)
                    return(HANTRO_NOK);
                Intra4x4HorizontalPrediction((u8*)data4x4, l + 1);
                break;
            case 2: /* Intra_4x4_DC */
                Intra4x4DcPrediction((u8*)data4x4, a + 1, l + 1,
                    availableA, availableB);
                break;
            case 3: /* Intra_4x4_Diagonal_Down_Left */
                if (!availableB)
                    return(HANTRO_NOK);
                if (!availableC)
                {
                    a[5] = a[6] = a[7] = a[8] = a[4];
                }
                Intra4x4DiagonalDownLeftPrediction((u8*)data4x4, a + 1);
                break;
            case 4: /* Intra_4x4_Diagonal_Down_Right */
                if (!availableA || !availableB || !availableD)
                    return(HANTRO_NOK);
                Intra4x4DiagonalDownRightPrediction((u8*)data4x4, a + 1, l + 1);
                break;
            case 5: /* Intra_4x4_Vertical_Right */
                if (!availableA || !availableB || !availableD)
                    return(HANTRO_NOK);
                Intra4x4VerticalRightPrediction((u8*)data4x4, a + 1, l + 1);
                break;
            case 6: /* Intra_4x4_Horizontal_Down */
                if (!availableA || !availableB || !availableD)
                    return(HANTRO_NOK);
                Intra4x4HorizontalDownPrediction((u8*)data4x4, a + 1, l + 1);
                break;
            case 7: /* Intra_4x4_Vertical_Left */
                if (!availableB)
                    return(HANTRO_NOK);
                if (!availableC)
                {
                    a[5] = a[6] = a[7] = a[8] = a[4];
                }
                Intra4x4VerticalLeftPrediction((u8*)data4x4, a + 1);
                break;
            default: /* case 8 Intra_4x4_Horizontal_Up */
                if (!availableA)
                    return(HANTRO_NOK);
                Intra4x4HorizontalUpPrediction((u8*)data4x4, l + 1);
                break;
        }

        Write4x4To16x16(data, (u8*)data4x4, block);
        h264bsdAddResidual(data, mbLayer->residual.level[block], block);
    }

    return(HANTRO_OK);

}

/*------------------------------------------------------------------------------

    Function: IntraChromaPrediction

        Functional description:
          Perform intra prediction for chroma pixels and add residual
          into prediction. The resulting chroma pixels are stored in 'data'.

------------------------------------------------------------------------------*/

u32 h264bsdIntraChromaPrediction(mbStorage_t *pMb, u8 *data, i32 residual[][16],
                    u8 *above, u8 *left, u32 predMode, u32 constrainedIntraPred)
{

/* Variables */

    u32 i, comp, block;
    u32 availableA, availableB, availableD;

/* Code */

    ASSERT(data);
    ASSERT(residual);
    ASSERT(above);
    ASSERT(left);
    ASSERT(predMode < 4);

    availableA = h264bsdIsNeighbourAvailable(pMb, pMb->mbA);
    if (availableA && constrainedIntraPred &&
       (h264bsdMbPartPredMode(pMb->mbA->mbType) == PRED_MODE_INTER))
        availableA = HANTRO_FALSE;
    availableB = h264bsdIsNeighbourAvailable(pMb, pMb->mbB);
    if (availableB && constrainedIntraPred &&
       (h264bsdMbPartPredMode(pMb->mbB->mbType) == PRED_MODE_INTER))
        availableB = HANTRO_FALSE;
    availableD = h264bsdIsNeighbourAvailable(pMb, pMb->mbD);
    if (availableD && constrainedIntraPred &&
       (h264bsdMbPartPredMode(pMb->mbD->mbType) == PRED_MODE_INTER))
        availableD = HANTRO_FALSE;

    for (comp = 0, block = 16; comp < 2; comp++)
    {
        switch(predMode)
        {
            case 0: /* Intra_Chroma_DC */
                IntraChromaDcPrediction(data, above+1, left, availableA,
                    availableB);
                break;

            case 1: /* Intra_Chroma_Horizontal */
                if (!availableA)
                    return(HANTRO_NOK);
                IntraChromaHorizontalPrediction(data, left);
                break;

            case 2: /* Intra_Chroma_Vertical */
                if (!availableB)
                    return(HANTRO_NOK);
                IntraChromaVerticalPrediction(data, above+1);

                break;

            default: /* case 3: Intra_Chroma_Plane */
                if (!availableA || !availableB || !availableD)
                    return(HANTRO_NOK);
                IntraChromaPlanePrediction(data, above+1, left);
                break;
        }
        for (i = 0; i < 4; i++, block++)
            h264bsdAddResidual(data, residual[i], block);

        /* advance pointers */
        data += 64;
        above += 9;
        left += 8;
        residual += 4;
    }

    return(HANTRO_OK);

}

/*------------------------------------------------------------------------------

    Function: h264bsdAddResidual

        Functional description:
          Add residual of a block into prediction in macroblock array 'data'.
          The result (residual + prediction) is stored in 'data'.

------------------------------------------------------------------------------*/
#ifndef H264DEC_OMXDL
void h264bsdAddResidual(u8 *data, i32 *residual, u32 blockNum)
{

/* Variables */

    u32 i;
    u32 x, y;
    u32 width;
    i32 tmp1, tmp2, tmp3, tmp4;
    u8 *tmp;
    const u8 *clp = h264bsdClip + 512;

/* Code */

    ASSERT(data);
    ASSERT(residual);
    ASSERT(blockNum < 16 + 4 + 4);

    if (IS_RESIDUAL_EMPTY(residual))
        return;

    RANGE_CHECK_ARRAY(residual, -512, 511, 16);

    if (blockNum < 16)
    {
        width = 16;
        x = h264bsdBlockX[blockNum];
        y = h264bsdBlockY[blockNum];
    }
    else
    {
        width = 8;
        x = h264bsdBlockX[blockNum & 0x3];
        y = h264bsdBlockY[blockNum & 0x3];
    }

    tmp = data + y*width + x;
    for (i = 4; i; i--)
    {
        tmp1 = *residual++;
        tmp2 = tmp[0];
        tmp3 = *residual++;
        tmp4 = tmp[1];

        tmp[0] = clp[tmp1 + tmp2];

        tmp1 = *residual++;
        tmp2 = tmp[2];

        tmp[1] = clp[tmp3 + tmp4];

        tmp3 = *residual++;
        tmp4 = tmp[3];

        tmp1 = clp[tmp1 + tmp2];
        tmp3 = clp[tmp3 + tmp4];
        tmp[2] = (u8)tmp1;
        tmp[3] = (u8)tmp3;

        tmp += width;
    }

}
#endif
/*------------------------------------------------------------------------------

    Function: Intra16x16VerticalPrediction

        Functional description:
          Perform intra 16x16 vertical prediction mode.

------------------------------------------------------------------------------*/

void Intra16x16VerticalPrediction(u8 *data, u8 *above)
{

/* Variables */

    u32 i, j;

/* Code */

    ASSERT(data);
    ASSERT(above);

    for (i = 0; i < 16; i++)
    {
        for (j = 0; j < 16; j++)
        {
            *data++ = above[j];
        }
    }

}

/*------------------------------------------------------------------------------

    Function: Intra16x16HorizontalPrediction

        Functional description:
          Perform intra 16x16 horizontal prediction mode.

------------------------------------------------------------------------------*/

void Intra16x16HorizontalPrediction(u8 *data, u8 *left)
{

/* Variables */

    u32 i, j;

/* Code */

    ASSERT(data);
    ASSERT(left);

    for (i = 0; i < 16; i++)
    {
        for (j = 0; j < 16; j++)
        {
            *data++ = left[i];
        }
    }

}

/*------------------------------------------------------------------------------

    Function: Intra16x16DcPrediction

        Functional description:
          Perform intra 16x16 DC prediction mode.

------------------------------------------------------------------------------*/

void Intra16x16DcPrediction(u8 *data, u8 *above, u8 *left, u32 availableA,
    u32 availableB)
{

/* Variables */

    u32 i, tmp;

/* Code */

    ASSERT(data);
    ASSERT(above);
    ASSERT(left);

    if (availableA && availableB)
    {
        for (i = 0, tmp = 0; i < 16; i++)
            tmp += above[i] + left[i];
        tmp = (tmp + 16) >> 5;
    }
    else if (availableA)
    {
        for (i = 0, tmp = 0; i < 16; i++)
            tmp += left[i];
        tmp = (tmp + 8) >> 4;
    }
    else if (availableB)
    {
        for (i = 0, tmp = 0; i < 16; i++)
            tmp += above[i];
        tmp = (tmp + 8) >> 4;
    }
    /* neither A nor B available */
    else
    {
        tmp = 128;
    }
    for (i = 0; i < 256; i++)
        data[i] = (u8)tmp;

}

/*------------------------------------------------------------------------------

    Function: Intra16x16PlanePrediction

        Functional description:
          Perform intra 16x16 plane prediction mode.

------------------------------------------------------------------------------*/

void Intra16x16PlanePrediction(u8 *data, u8 *above, u8 *left)
{

/* Variables */

    u32 i, j;
    i32 a, b, c;
    i32 tmp;

/* Code */

    ASSERT(data);
    ASSERT(above);
    ASSERT(left);

    a = 16 * (above[15] + left[15]);

    for (i = 0, b = 0; i < 8; i++)
        b += ((i32)i + 1) * (above[8+i] - above[6-i]);
    b = (5 * b + 32) >> 6;

    for (i = 0, c = 0; i < 7; i++)
        c += ((i32)i + 1) * (left[8+i] - left[6-i]);
    /* p[-1,-1] has to be accessed through above pointer */
    c += ((i32)i + 1) * (left[8+i] - above[-1]);
    c = (5 * c + 32) >> 6;

    for (i = 0; i < 16; i++)
    {
        for (j = 0; j < 16; j++)
        {
            tmp = (a + b * ((i32)j - 7) + c * ((i32)i - 7) + 16) >> 5;
            data[i*16+j] = (u8)CLIP1(tmp);
        }
    }

}

/*------------------------------------------------------------------------------

    Function: IntraChromaDcPrediction

        Functional description:
          Perform intra chroma DC prediction mode.

------------------------------------------------------------------------------*/

void IntraChromaDcPrediction(u8 *data, u8 *above, u8 *left, u32 availableA,
    u32 availableB)
{

/* Variables */

    u32 i;
    u32 tmp1, tmp2;

/* Code */

    ASSERT(data);
    ASSERT(above);
    ASSERT(left);

    /* y = 0..3 */
    if (availableA && availableB)
    {
        tmp1 = above[0] + above[1] + above[2] + above[3] +
              left[0] + left[1] + left[2] + left[3];
        tmp1 = (tmp1 + 4) >> 3;
        tmp2 = (above[4] + above[5] + above[6] + above[7] + 2) >> 2;
    }
    else if (availableB)
    {
        tmp1 = (above[0] + above[1] + above[2] + above[3] + 2) >> 2;
        tmp2 = (above[4] + above[5] + above[6] + above[7] + 2) >> 2;
    }
    else if (availableA)
    {
        tmp1 = (left[0] + left[1] + left[2] + left[3] + 2) >> 2;
        tmp2 = tmp1;
    }
    /* neither A nor B available */
    else
    {
        tmp1 = tmp2 = 128;
    }

    ASSERT(tmp1 < 256 && tmp2 < 256);
    for (i = 4; i--;)
    {
        *data++ = (u8)tmp1;
        *data++ = (u8)tmp1;
        *data++ = (u8)tmp1;
        *data++ = (u8)tmp1;
        *data++ = (u8)tmp2;
        *data++ = (u8)tmp2;
        *data++ = (u8)tmp2;
        *data++ = (u8)tmp2;
    }

    /* y = 4...7 */
    if (availableA)
    {
        tmp1 = (left[4] + left[5] + left[6] + left[7] + 2) >> 2;
        if (availableB)
        {
            tmp2 = above[4] + above[5] + above[6] + above[7] +
                   left[4] + left[5] + left[6] + left[7];
            tmp2 = (tmp2 + 4) >> 3;
        }
        else
            tmp2 = tmp1;
    }
    else if (availableB)
    {
        tmp1 = (above[0] + above[1] + above[2] + above[3] + 2) >> 2;
        tmp2 = (above[4] + above[5] + above[6] + above[7] + 2) >> 2;
    }
    else
    {
        tmp1 = tmp2 = 128;
    }

    ASSERT(tmp1 < 256 && tmp2 < 256);
    for (i = 4; i--;)
    {
        *data++ = (u8)tmp1;
        *data++ = (u8)tmp1;
        *data++ = (u8)tmp1;
        *data++ = (u8)tmp1;
        *data++ = (u8)tmp2;
        *data++ = (u8)tmp2;
        *data++ = (u8)tmp2;
        *data++ = (u8)tmp2;
    }
}

/*------------------------------------------------------------------------------

    Function: IntraChromaHorizontalPrediction

        Functional description:
          Perform intra chroma horizontal prediction mode.

------------------------------------------------------------------------------*/

void IntraChromaHorizontalPrediction(u8 *data, u8 *left)
{

/* Variables */

    u32 i;

/* Code */

    ASSERT(data);
    ASSERT(left);

    for (i = 8; i--;)
    {
        *data++ = *left;
        *data++ = *left;
        *data++ = *left;
        *data++ = *left;
        *data++ = *left;
        *data++ = *left;
        *data++ = *left;
        *data++ = *left++;
    }

}

/*------------------------------------------------------------------------------

    Function: IntraChromaVerticalPrediction

        Functional description:
          Perform intra chroma vertical prediction mode.

------------------------------------------------------------------------------*/

void IntraChromaVerticalPrediction(u8 *data, u8 *above)
{

/* Variables */

    u32 i;

/* Code */

    ASSERT(data);
    ASSERT(above);

    for (i = 8; i--;data++/*above-=8*/)
    {
        data[0] = *above;
        data[8] = *above;
        data[16] = *above;
        data[24] = *above;
        data[32] = *above;
        data[40] = *above;
        data[48] = *above;
        data[56] = *above++;
    }

}

/*------------------------------------------------------------------------------

    Function: IntraChromaPlanePrediction

        Functional description:
          Perform intra chroma plane prediction mode.

------------------------------------------------------------------------------*/

void IntraChromaPlanePrediction(u8 *data, u8 *above, u8 *left)
{

/* Variables */

    u32 i;
    i32 a, b, c;
    i32 tmp;
    const u8 *clp = h264bsdClip + 512;

/* Code */

    ASSERT(data);
    ASSERT(above);
    ASSERT(left);

    a = 16 * (above[7] + left[7]);

    b = (above[4] - above[2]) + 2 * (above[5] - above[1])
        + 3 * (above[6] - above[0]) + 4 * (above[7] - above[-1]);
    b = (17 * b + 16) >> 5;

    /* p[-1,-1] has to be accessed through above pointer */
    c = (left[4] - left[2]) + 2 * (left[5] - left[1])
        + 3 * (left[6] - left[0]) + 4 * (left[7] - above[-1]);
    c = (17 * c + 16) >> 5;

    /*a += 16;*/
    a = a - 3 * c + 16;
    for (i = 8; i--; a += c)
    {
        tmp = (a - 3 * b);
        *data++ = clp[tmp>>5];
        tmp += b;
        *data++ = clp[tmp>>5];
        tmp += b;
        *data++ = clp[tmp>>5];
        tmp += b;
        *data++ = clp[tmp>>5];
        tmp += b;
        *data++ = clp[tmp>>5];
        tmp += b;
        *data++ = clp[tmp>>5];
        tmp += b;
        *data++ = clp[tmp>>5];
        tmp += b;
        *data++ = clp[tmp>>5];
    }

}

/*------------------------------------------------------------------------------

    Function: Get4x4NeighbourPels

        Functional description:
          Get neighbouring pixels of a 4x4 block into 'a' and 'l'.

------------------------------------------------------------------------------*/

void Get4x4NeighbourPels(u8 *a, u8 *l, u8 *data, u8 *above, u8 *left,
    u32 blockNum)
{

/* Variables */

    u32 x, y;
    u8 t1, t2;

/* Code */

    ASSERT(a);
    ASSERT(l);
    ASSERT(data);
    ASSERT(above);
    ASSERT(left);
    ASSERT(blockNum < 16);

    x = h264bsdBlockX[blockNum];
    y = h264bsdBlockY[blockNum];

    /* A and D */
    if (x == 0)
    {
        t1 = left[y    ];
        t2 = left[y + 1];
        l[1] = t1;
        l[2] = t2;
        t1 = left[y + 2];
        t2 = left[y + 3];
        l[3] = t1;
        l[4] = t2;
    }
    else
    {
        t1 = data[y * 16 + x - 1     ];
        t2 = data[y * 16 + x - 1 + 16];
        l[1] = t1;
        l[2] = t2;
        t1 = data[y * 16 + x - 1 + 32];
        t2 = data[y * 16 + x - 1 + 48];
        l[3] = t1;
        l[4] = t2;
    }

    /* B, C and D */
    if (y == 0)
    {
        t1 = above[x    ];
        t2 = above[x    ];
        l[0] = t1;
        a[0] = t2;
        t1 = above[x + 1];
        t2 = above[x + 2];
        a[1] = t1;
        a[2] = t2;
        t1 = above[x + 3];
        t2 = above[x + 4];
        a[3] = t1;
        a[4] = t2;
        t1 = above[x + 5];
        t2 = above[x + 6];
        a[5] = t1;
        a[6] = t2;
        t1 = above[x + 7];
        t2 = above[x + 8];
        a[7] = t1;
        a[8] = t2;
    }
    else
    {
        t1 = data[(y - 1) * 16 + x    ];
        t2 = data[(y - 1) * 16 + x + 1];
        a[1] = t1;
        a[2] = t2;
        t1 = data[(y - 1) * 16 + x + 2];
        t2 = data[(y - 1) * 16 + x + 3];
        a[3] = t1;
        a[4] = t2;
        t1 = data[(y - 1) * 16 + x + 4];
        t2 = data[(y - 1) * 16 + x + 5];
        a[5] = t1;
        a[6] = t2;
        t1 = data[(y - 1) * 16 + x + 6];
        t2 = data[(y - 1) * 16 + x + 7];
        a[7] = t1;
        a[8] = t2;

        if (x == 0)
            l[0] = a[0] = left[y-1];
        else
            l[0] = a[0] = data[(y - 1) * 16 + x - 1];
    }
}


/*------------------------------------------------------------------------------

    Function: Intra4x4VerticalPrediction

        Functional description:
          Perform intra 4x4 vertical prediction mode.

------------------------------------------------------------------------------*/

void Intra4x4VerticalPrediction(u8 *data, u8 *above)
{

/* Variables */

    u8 t1, t2;

/* Code */

    ASSERT(data);
    ASSERT(above);

    t1 = above[0];
    t2 = above[1];
    data[0] = data[4] = data[8] = data[12] = t1;
    data[1] = data[5] = data[9] = data[13] = t2;
    t1 = above[2];
    t2 = above[3];
    data[2] = data[6] = data[10] = data[14] = t1;
    data[3] = data[7] = data[11] = data[15] = t2;

}

/*------------------------------------------------------------------------------

    Function: Intra4x4HorizontalPrediction

        Functional description:
          Perform intra 4x4 horizontal prediction mode.

------------------------------------------------------------------------------*/

void Intra4x4HorizontalPrediction(u8 *data, u8 *left)
{

/* Variables */

    u8 t1, t2;

/* Code */

    ASSERT(data);
    ASSERT(left);

    t1 = left[0];
    t2 = left[1];
    data[0] = data[1] = data[2] = data[3] = t1;
    data[4] = data[5] = data[6] = data[7] = t2;
    t1 = left[2];
    t2 = left[3];
    data[8] = data[9] = data[10] = data[11] = t1;
    data[12] = data[13] = data[14] = data[15] = t2;

}

/*------------------------------------------------------------------------------

    Function: Intra4x4DcPrediction

        Functional description:
          Perform intra 4x4 DC prediction mode.

------------------------------------------------------------------------------*/

void Intra4x4DcPrediction(u8 *data, u8 *above, u8 *left, u32 availableA,
    u32 availableB)
{

/* Variables */

    u32 tmp;
    u8 t1, t2, t3, t4;

/* Code */

    ASSERT(data);
    ASSERT(above);
    ASSERT(left);

    if (availableA && availableB)
    {
        t1 = above[0]; t2 = above[1]; t3 = above[2]; t4 = above[3];
        tmp = t1 + t2 + t3 + t4;
        t1 = left[0]; t2 = left[1]; t3 = left[2]; t4 = left[3];
        tmp += t1 + t2 + t3 + t4;
        tmp = (tmp + 4) >> 3;
    }
    else if (availableA)
    {
        t1 = left[0]; t2 = left[1]; t3 = left[2]; t4 = left[3];
        tmp = (t1 + t2 + t3 + t4 + 2) >> 2;
    }
    else if (availableB)
    {
        t1 = above[0]; t2 = above[1]; t3 = above[2]; t4 = above[3];
        tmp = (t1 + t2 + t3 + t4 + 2) >> 2;
    }
    else
    {
        tmp = 128;
    }

    ASSERT(tmp < 256);
    data[0] = data[1] = data[2] = data[3] =
    data[4] = data[5] = data[6] = data[7] =
    data[8] = data[9] = data[10] = data[11] =
    data[12] = data[13] = data[14] = data[15] = (u8)tmp;

}

/*------------------------------------------------------------------------------

    Function: Intra4x4DiagonalDownLeftPrediction

        Functional description:
          Perform intra 4x4 diagonal down-left prediction mode.

------------------------------------------------------------------------------*/

void Intra4x4DiagonalDownLeftPrediction(u8 *data, u8 *above)
{

/* Variables */

/* Code */

    ASSERT(data);
    ASSERT(above);

    data[ 0] = (above[0] + 2 * above[1] + above[2] + 2) >> 2;
    data[ 1] = (above[1] + 2 * above[2] + above[3] + 2) >> 2;
    data[ 4] = (above[1] + 2 * above[2] + above[3] + 2) >> 2;
    data[ 2] = (above[2] + 2 * above[3] + above[4] + 2) >> 2;
    data[ 5] = (above[2] + 2 * above[3] + above[4] + 2) >> 2;
    data[ 8] = (above[2] + 2 * above[3] + above[4] + 2) >> 2;
    data[ 3] = (above[3] + 2 * above[4] + above[5] + 2) >> 2;
    data[ 6] = (above[3] + 2 * above[4] + above[5] + 2) >> 2;
    data[ 9] = (above[3] + 2 * above[4] + above[5] + 2) >> 2;
    data[12] = (above[3] + 2 * above[4] + above[5] + 2) >> 2;
    data[ 7] = (above[4] + 2 * above[5] + above[6] + 2) >> 2;
    data[10] = (above[4] + 2 * above[5] + above[6] + 2) >> 2;
    data[13] = (above[4] + 2 * above[5] + above[6] + 2) >> 2;
    data[11] = (above[5] + 2 * above[6] + above[7] + 2) >> 2;
    data[14] = (above[5] + 2 * above[6] + above[7] + 2) >> 2;
    data[15] = (above[6] + 3 * above[7] + 2) >> 2;

}

/*------------------------------------------------------------------------------

    Function: Intra4x4DiagonalDownRightPrediction

        Functional description:
          Perform intra 4x4 diagonal down-right prediction mode.

------------------------------------------------------------------------------*/

void Intra4x4DiagonalDownRightPrediction(u8 *data, u8 *above, u8 *left)
{

/* Variables */

/* Code */

    ASSERT(data);
    ASSERT(above);
    ASSERT(left);

    data[ 0] = (above[0] + 2 * above[-1] + left[0] + 2) >> 2;
    data[ 5] = (above[0] + 2 * above[-1] + left[0] + 2) >> 2;
    data[10] = (above[0] + 2 * above[-1] + left[0] + 2) >> 2;
    data[15] = (above[0] + 2 * above[-1] + left[0] + 2) >> 2;
    data[ 1] = (above[-1] + 2 * above[0] + above[1] + 2) >> 2;
    data[ 6] = (above[-1] + 2 * above[0] + above[1] + 2) >> 2;
    data[11] = (above[-1] + 2 * above[0] + above[1] + 2) >> 2;
    data[ 2] = (above[0] + 2 * above[1] + above[2] + 2) >> 2;
    data[ 7] = (above[0] + 2 * above[1] + above[2] + 2) >> 2;
    data[ 3] = (above[1] + 2 * above[2] + above[3] + 2) >> 2;
    data[ 4] = (left[-1] + 2 * left[0] + left[1] + 2) >> 2;
    data[ 9] = (left[-1] + 2 * left[0] + left[1] + 2) >> 2;
    data[14] = (left[-1] + 2 * left[0] + left[1] + 2) >> 2;
    data[ 8] = (left[0] + 2 * left[1] + left[2] + 2) >> 2;
    data[13] = (left[0] + 2 * left[1] + left[2] + 2) >> 2;
    data[12] = (left[1] + 2 * left[2] + left[3] + 2) >> 2;
}

/*------------------------------------------------------------------------------

    Function: Intra4x4VerticalRightPrediction

        Functional description:
          Perform intra 4x4 vertical right prediction mode.

------------------------------------------------------------------------------*/

void Intra4x4VerticalRightPrediction(u8 *data, u8 *above, u8 *left)
{

/* Variables */

/* Code */

    ASSERT(data);
    ASSERT(above);
    ASSERT(left);

    data[ 0] = (above[-1] + above[0] + 1) >> 1;
    data[ 9] = (above[-1] + above[0] + 1) >> 1;
    data[ 5] = (above[-1] + 2 * above[0] + above[1] + 2) >> 2;
    data[14] = (above[-1] + 2 * above[0] + above[1] + 2) >> 2;
    data[ 4] = (above[0] + 2 * above[-1] + left[0] + 2) >> 2;
    data[13] = (above[0] + 2 * above[-1] + left[0] + 2) >> 2;
    data[ 1] = (above[0] + above[1] + 1) >> 1;
    data[10] = (above[0] + above[1] + 1) >> 1;
    data[ 6] = (above[0] + 2 * above[1] + above[2] + 2) >> 2;
    data[15] = (above[0] + 2 * above[1] + above[2] + 2) >> 2;
    data[ 2] = (above[1] + above[2] + 1) >> 1;
    data[11] = (above[1] + above[2] + 1) >> 1;
    data[ 7] = (above[1] + 2 * above[2] + above[3] + 2) >> 2;
    data[ 3] = (above[2] + above[3] + 1) >> 1;
    data[ 8] = (left[1] + 2 * left[0] + left[-1] + 2) >> 2;
    data[12] = (left[2] + 2 * left[1] + left[0] + 2) >> 2;

}

/*------------------------------------------------------------------------------

    Function: Intra4x4HorizontalDownPrediction

        Functional description:
          Perform intra 4x4 horizontal down prediction mode.

------------------------------------------------------------------------------*/

void Intra4x4HorizontalDownPrediction(u8 *data, u8 *above, u8 *left)
{

/* Variables */

/* Code */

    ASSERT(data);
    ASSERT(above);
    ASSERT(left);

    data[ 0] = (left[-1] + left[0] + 1) >> 1;
    data[ 6] = (left[-1] + left[0] + 1) >> 1;
    data[ 5] = (left[-1] + 2 * left[0] + left[1] + 2) >> 2;
    data[11] = (left[-1] + 2 * left[0] + left[1] + 2) >> 2;
    data[ 4] = (left[0] + left[1] + 1) >> 1;
    data[10] = (left[0] + left[1] + 1) >> 1;
    data[ 9] = (left[0] + 2 * left[1] + left[2] + 2) >> 2;
    data[15] = (left[0] + 2 * left[1] + left[2] + 2) >> 2;
    data[ 8] = (left[1] + left[2] + 1) >> 1;
    data[14] = (left[1] + left[2] + 1) >> 1;
    data[13] = (left[1] + 2 * left[2] + left[3] + 2) >> 2;
    data[12] = (left[2] + left[3] + 1) >> 1;
    data[ 1] = (above[0] + 2 * above[-1] + left[0] + 2) >> 2;
    data[ 7] = (above[0] + 2 * above[-1] + left[0] + 2) >> 2;
    data[ 2] = (above[1] + 2 * above[0] + above[-1] + 2) >> 2;
    data[ 3] = (above[2] + 2 * above[1] + above[0] + 2) >> 2;
}

/*------------------------------------------------------------------------------

    Function: Intra4x4VerticalLeftPrediction

        Functional description:
          Perform intra 4x4 vertical left prediction mode.

------------------------------------------------------------------------------*/

void Intra4x4VerticalLeftPrediction(u8 *data, u8 *above)
{

/* Variables */

/* Code */

    ASSERT(data);
    ASSERT(above);

    data[ 0] = (above[0] + above[1] + 1) >> 1;
    data[ 1] = (above[1] + above[2] + 1) >> 1;
    data[ 2] = (above[2] + above[3] + 1) >> 1;
    data[ 3] = (above[3] + above[4] + 1) >> 1;
    data[ 4] = (above[0] + 2 * above[1] + above[2] + 2) >> 2;
    data[ 5] = (above[1] + 2 * above[2] + above[3] + 2) >> 2;
    data[ 6] = (above[2] + 2 * above[3] + above[4] + 2) >> 2;
    data[ 7] = (above[3] + 2 * above[4] + above[5] + 2) >> 2;
    data[ 8] = (above[1] + above[2] + 1) >> 1;
    data[ 9] = (above[2] + above[3] + 1) >> 1;
    data[10] = (above[3] + above[4] + 1) >> 1;
    data[11] = (above[4] + above[5] + 1) >> 1;
    data[12] = (above[1] + 2 * above[2] + above[3] + 2) >> 2;
    data[13] = (above[2] + 2 * above[3] + above[4] + 2) >> 2;
    data[14] = (above[3] + 2 * above[4] + above[5] + 2) >> 2;
    data[15] = (above[4] + 2 * above[5] + above[6] + 2) >> 2;

}

/*------------------------------------------------------------------------------

    Function: Intra4x4HorizontalUpPrediction

        Functional description:
          Perform intra 4x4 horizontal up prediction mode.

------------------------------------------------------------------------------*/

void Intra4x4HorizontalUpPrediction(u8 *data, u8 *left)
{

/* Variables */

/* Code */

    ASSERT(data);
    ASSERT(left);

    data[ 0] = (left[0] + left[1] + 1) >> 1;
    data[ 1] = (left[0] + 2 * left[1] + left[2] + 2) >> 2;
    data[ 2] = (left[1] + left[2] + 1) >> 1;
    data[ 3] = (left[1] + 2 * left[2] + left[3] + 2) >> 2;
    data[ 4] = (left[1] + left[2] + 1) >> 1;
    data[ 5] = (left[1] + 2 * left[2] + left[3] + 2) >> 2;
    data[ 6] = (left[2] + left[3] + 1) >> 1;
    data[ 7] = (left[2] + 3 * left[3] + 2) >> 2;
    data[ 8] = (left[2] + left[3] + 1) >> 1;
    data[ 9] = (left[2] + 3 * left[3] + 2) >> 2;
    data[10] = left[3];
    data[11] = left[3];
    data[12] = left[3];
    data[13] = left[3];
    data[14] = left[3];
    data[15] = left[3];

}

#endif /* H264DEC_OMXDL */

/*------------------------------------------------------------------------------

    Function: Write4x4To16x16

        Functional description:
          Write a 4x4 block (data4x4) into correct position
          in 16x16 macroblock (data).

------------------------------------------------------------------------------*/

void Write4x4To16x16(u8 *data, u8 *data4x4, u32 blockNum)
{

/* Variables */

    u32 x, y;
    u32 *in32, *out32;

/* Code */

    ASSERT(data);
    ASSERT(data4x4);
    ASSERT(blockNum < 16);

    x = h264bsdBlockX[blockNum];
    y = h264bsdBlockY[blockNum];

    data += y*16+x;

    ASSERT(((u32)data&0x3) == 0);

    /*lint --e(826) */
    out32 = (u32 *)data;
    /*lint --e(826) */
    in32 = (u32 *)data4x4;

    out32[0] = *in32++;
    out32[4] = *in32++;
    out32[8] = *in32++;
    out32[12] = *in32++;
}

/*------------------------------------------------------------------------------

    Function: DetermineIntra4x4PredMode

        Functional description:
          Returns the intra 4x4 prediction mode of a block based on the
          neighbouring macroblocks and information parsed from stream.

------------------------------------------------------------------------------*/

u32 DetermineIntra4x4PredMode(macroblockLayer_t *pMbLayer,
    u32 available, neighbour_t *nA, neighbour_t *nB, u32 index,
    mbStorage_t *nMbA, mbStorage_t *nMbB)
{

/* Variables */

    u32 mode1, mode2;
    mbStorage_t *pMb;

/* Code */

    ASSERT(pMbLayer);

    /* dc only prediction? */
    if (!available)
        mode1 = 2;
    else
    {
        pMb = nMbA;
        if (h264bsdMbPartPredMode(pMb->mbType) == PRED_MODE_INTRA4x4)
        {
            mode1 = pMb->intra4x4PredMode[nA->index];
        }
        else
            mode1 = 2;

        pMb = nMbB;
        if (h264bsdMbPartPredMode(pMb->mbType) == PRED_MODE_INTRA4x4)
        {
            mode2 = pMb->intra4x4PredMode[nB->index];
        }
        else
            mode2 = 2;

        mode1 = MIN(mode1, mode2);
    }

    if (!pMbLayer->mbPred.prevIntra4x4PredModeFlag[index])
    {
        if (pMbLayer->mbPred.remIntra4x4PredMode[index] < mode1)
        {
            mode1 = pMbLayer->mbPred.remIntra4x4PredMode[index];
        }
        else
        {
            mode1 = pMbLayer->mbPred.remIntra4x4PredMode[index] + 1;
        }
    }

    return(mode1);
}


/*lint +e702 */


