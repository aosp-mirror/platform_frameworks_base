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
          h264bsdDecodeMacroblockLayer
          h264bsdMbPartPredMode
          h264bsdNumMbPart
          h264bsdNumSubMbPart
          DecodeMbPred
          DecodeSubMbPred
          DecodeResidual
          DetermineNc
          CbpIntra16x16
          h264bsdPredModeIntra16x16
          h264bsdDecodeMacroblock
          ProcessResidual
          h264bsdSubMbPartMode

------------------------------------------------------------------------------*/

/*------------------------------------------------------------------------------
    1. Include headers
------------------------------------------------------------------------------*/

#include "h264bsd_macroblock_layer.h"
#include "h264bsd_slice_header.h"
#include "h264bsd_util.h"
#include "h264bsd_vlc.h"
#include "h264bsd_cavlc.h"
#include "h264bsd_nal_unit.h"
#include "h264bsd_neighbour.h"
#include "h264bsd_transform.h"
#include "h264bsd_intra_prediction.h"
#include "h264bsd_inter_prediction.h"

#ifdef H264DEC_OMXDL
#include "omxtypes.h"
#include "omxVC.h"
#include "armVC.h"
#endif /* H264DEC_OMXDL */

/*------------------------------------------------------------------------------
    2. External compiler flags
--------------------------------------------------------------------------------

--------------------------------------------------------------------------------
    3. Module defines
------------------------------------------------------------------------------*/
#ifdef H264DEC_OMXDL
static const u32 chromaIndex[8] = { 256, 260, 288, 292, 320, 324, 352, 356 };
static const u32 lumaIndex[16] = {   0,   4,  64,  68,
                                     8,  12,  72,  76,
                                   128, 132, 192, 196,
                                   136, 140, 200, 204 };
#endif
/* mapping of dc coefficients array to luma blocks */
static const u32 dcCoeffIndex[16] =
    {0, 1, 4, 5, 2, 3, 6, 7, 8, 9, 12, 13, 10, 11, 14, 15};

/*------------------------------------------------------------------------------
    4. Local function prototypes
------------------------------------------------------------------------------*/

static u32 DecodeMbPred(strmData_t *pStrmData, mbPred_t *pMbPred,
    mbType_e mbType, u32 numRefIdxActive);
static u32 DecodeSubMbPred(strmData_t *pStrmData, subMbPred_t *pSubMbPred,
    mbType_e mbType, u32 numRefIdxActive);
static u32 DecodeResidual(strmData_t *pStrmData, residual_t *pResidual,
    mbStorage_t *pMb, mbType_e mbType, u32 codedBlockPattern);

#ifdef H264DEC_OMXDL
static u32 DetermineNc(mbStorage_t *pMb, u32 blockIndex, u8 *pTotalCoeff);
#else
static u32 DetermineNc(mbStorage_t *pMb, u32 blockIndex, i16 *pTotalCoeff);
#endif

static u32 CbpIntra16x16(mbType_e mbType);
#ifdef H264DEC_OMXDL
static u32 ProcessIntra4x4Residual(mbStorage_t *pMb, u8 *data, u32 constrainedIntraPred,
                    macroblockLayer_t *mbLayer, const u8 **pSrc, image_t *image);
static u32 ProcessChromaResidual(mbStorage_t *pMb, u8 *data, const u8 **pSrc );
static u32 ProcessIntra16x16Residual(mbStorage_t *pMb, u8 *data, u32 constrainedIntraPred,
                    u32 intraChromaPredMode, const u8 **pSrc, image_t *image);


#else
static u32 ProcessResidual(mbStorage_t *pMb, i32 residualLevel[][16], u32 *);
#endif

/*------------------------------------------------------------------------------

    Function name: h264bsdDecodeMacroblockLayer

        Functional description:
          Parse macroblock specific information from bit stream.

        Inputs:
          pStrmData         pointer to stream data structure
          pMb               pointer to macroblock storage structure
          sliceType         type of the current slice
          numRefIdxActive   maximum reference index

        Outputs:
          pMbLayer          stores the macroblock data parsed from stream

        Returns:
          HANTRO_OK         success
          HANTRO_NOK        end of stream or error in stream

------------------------------------------------------------------------------*/

u32 h264bsdDecodeMacroblockLayer(strmData_t *pStrmData,
    macroblockLayer_t *pMbLayer, mbStorage_t *pMb, u32 sliceType,
    u32 numRefIdxActive)
{

/* Variables */

    u32 tmp, i, value;
    i32 itmp;
    mbPartPredMode_e partMode;

/* Code */

    ASSERT(pStrmData);
    ASSERT(pMbLayer);

#ifdef H264DEC_NEON
    h264bsdClearMbLayer(pMbLayer, ((sizeof(macroblockLayer_t) + 63) & ~0x3F));
#else
    H264SwDecMemset(pMbLayer, 0, sizeof(macroblockLayer_t));
#endif

    tmp = h264bsdDecodeExpGolombUnsigned(pStrmData, &value);

    if (IS_I_SLICE(sliceType))
    {
        if ((value + 6) > 31 || tmp != HANTRO_OK)
            return(HANTRO_NOK);
        pMbLayer->mbType = (mbType_e)(value + 6);
    }
    else
    {
        if ((value + 1) > 31 || tmp != HANTRO_OK)
            return(HANTRO_NOK);
        pMbLayer->mbType = (mbType_e)(value + 1);
    }

    if (pMbLayer->mbType == I_PCM)
    {
        i32 *level;
        while( !h264bsdIsByteAligned(pStrmData) )
        {
            /* pcm_alignment_zero_bit */
            tmp = h264bsdGetBits(pStrmData, 1);
            if (tmp)
                return(HANTRO_NOK);
        }

        level = pMbLayer->residual.level[0];
        for (i = 0; i < 384; i++)
        {
            value = h264bsdGetBits(pStrmData, 8);
            if (value == END_OF_STREAM)
                return(HANTRO_NOK);
            *level++ = (i32)value;
        }
    }
    else
    {
        partMode = h264bsdMbPartPredMode(pMbLayer->mbType);
        if ( (partMode == PRED_MODE_INTER) &&
             (h264bsdNumMbPart(pMbLayer->mbType) == 4) )
        {
            tmp = DecodeSubMbPred(pStrmData, &pMbLayer->subMbPred,
                pMbLayer->mbType, numRefIdxActive);
        }
        else
        {
            tmp = DecodeMbPred(pStrmData, &pMbLayer->mbPred,
                pMbLayer->mbType, numRefIdxActive);
        }
        if (tmp != HANTRO_OK)
            return(tmp);

        if (partMode != PRED_MODE_INTRA16x16)
        {
            tmp = h264bsdDecodeExpGolombMapped(pStrmData, &value,
                (u32)(partMode == PRED_MODE_INTRA4x4));
            if (tmp != HANTRO_OK)
                return(tmp);
            pMbLayer->codedBlockPattern = value;
        }
        else
        {
            pMbLayer->codedBlockPattern = CbpIntra16x16(pMbLayer->mbType);
        }

        if ( pMbLayer->codedBlockPattern ||
             (partMode == PRED_MODE_INTRA16x16) )
        {
            tmp = h264bsdDecodeExpGolombSigned(pStrmData, &itmp);
            if (tmp != HANTRO_OK || (itmp < -26) || (itmp > 25) )
                return(HANTRO_NOK);
            pMbLayer->mbQpDelta = itmp;

            tmp = DecodeResidual(pStrmData, &pMbLayer->residual, pMb,
                pMbLayer->mbType, pMbLayer->codedBlockPattern);

            pStrmData->strmBuffReadBits =
                (u32)(pStrmData->pStrmCurrPos - pStrmData->pStrmBuffStart) * 8 +
                pStrmData->bitPosInWord;

            if (tmp != HANTRO_OK)
                return(tmp);
        }
    }

    return(HANTRO_OK);

}

/*------------------------------------------------------------------------------

    Function: h264bsdMbPartPredMode

        Functional description:
          Returns the prediction mode of a macroblock type

------------------------------------------------------------------------------*/

mbPartPredMode_e h264bsdMbPartPredMode(mbType_e mbType)
{

/* Variables */


/* Code */

    ASSERT(mbType <= 31);

    if ((mbType <= P_8x8ref0))
        return(PRED_MODE_INTER);
    else if (mbType == I_4x4)
        return(PRED_MODE_INTRA4x4);
    else
        return(PRED_MODE_INTRA16x16);

}

/*------------------------------------------------------------------------------

    Function: h264bsdNumMbPart

        Functional description:
          Returns the amount of macroblock partitions in a macroblock type

------------------------------------------------------------------------------*/

u32 h264bsdNumMbPart(mbType_e mbType)
{

/* Variables */


/* Code */

    ASSERT(h264bsdMbPartPredMode(mbType) == PRED_MODE_INTER);

    switch (mbType)
    {
        case P_L0_16x16:
        case P_Skip:
            return(1);

        case P_L0_L0_16x8:
        case P_L0_L0_8x16:
            return(2);

        /* P_8x8 or P_8x8ref0 */
        default:
            return(4);
    }

}

/*------------------------------------------------------------------------------

    Function: h264bsdNumSubMbPart

        Functional description:
          Returns the amount of sub-partitions in a sub-macroblock type

------------------------------------------------------------------------------*/

u32 h264bsdNumSubMbPart(subMbType_e subMbType)
{

/* Variables */


/* Code */

    ASSERT(subMbType <= P_L0_4x4);

    switch (subMbType)
    {
        case P_L0_8x8:
            return(1);

        case P_L0_8x4:
        case P_L0_4x8:
            return(2);

        /* P_L0_4x4 */
        default:
            return(4);
    }

}

/*------------------------------------------------------------------------------

    Function: DecodeMbPred

        Functional description:
          Parse macroblock prediction information from bit stream and store
          in 'pMbPred'.

------------------------------------------------------------------------------*/

u32 DecodeMbPred(strmData_t *pStrmData, mbPred_t *pMbPred, mbType_e mbType,
    u32 numRefIdxActive)
{

/* Variables */

    u32 tmp, i, j, value;
    i32 itmp;

/* Code */

    ASSERT(pStrmData);
    ASSERT(pMbPred);

    switch (h264bsdMbPartPredMode(mbType))
    {
        case PRED_MODE_INTER: /* PRED_MODE_INTER */
            if (numRefIdxActive > 1)
            {
                for (i = h264bsdNumMbPart(mbType), j = 0; i--;  j++)
                {
                    tmp = h264bsdDecodeExpGolombTruncated(pStrmData, &value,
                        (u32)(numRefIdxActive > 2));
                    if (tmp != HANTRO_OK || value >= numRefIdxActive)
                        return(HANTRO_NOK);

                    pMbPred->refIdxL0[j] = value;
                }
            }

            for (i = h264bsdNumMbPart(mbType), j = 0; i--;  j++)
            {
                tmp = h264bsdDecodeExpGolombSigned(pStrmData, &itmp);
                if (tmp != HANTRO_OK)
                    return(tmp);
                pMbPred->mvdL0[j].hor = (i16)itmp;

                tmp = h264bsdDecodeExpGolombSigned(pStrmData, &itmp);
                if (tmp != HANTRO_OK)
                    return(tmp);
                pMbPred->mvdL0[j].ver = (i16)itmp;
            }
            break;

        case PRED_MODE_INTRA4x4:
            for (itmp = 0, i = 0; itmp < 2; itmp++)
            {
                value = h264bsdShowBits32(pStrmData);
                tmp = 0;
                for (j = 8; j--; i++)
                {
                    pMbPred->prevIntra4x4PredModeFlag[i] =
                        value & 0x80000000 ? HANTRO_TRUE : HANTRO_FALSE;
                    value <<= 1;
                    if (!pMbPred->prevIntra4x4PredModeFlag[i])
                    {
                        pMbPred->remIntra4x4PredMode[i] = value>>29;
                        value <<= 3;
                        tmp++;
                    }
                }
                if (h264bsdFlushBits(pStrmData, 8 + 3*tmp) == END_OF_STREAM)
                    return(HANTRO_NOK);
            }
            /* fall-through */

        case PRED_MODE_INTRA16x16:
            tmp = h264bsdDecodeExpGolombUnsigned(pStrmData, &value);
            if (tmp != HANTRO_OK || value > 3)
                return(HANTRO_NOK);
            pMbPred->intraChromaPredMode = value;
            break;
    }

    return(HANTRO_OK);

}

/*------------------------------------------------------------------------------

    Function: DecodeSubMbPred

        Functional description:
          Parse sub-macroblock prediction information from bit stream and
          store in 'pMbPred'.

------------------------------------------------------------------------------*/

u32 DecodeSubMbPred(strmData_t *pStrmData, subMbPred_t *pSubMbPred,
    mbType_e mbType, u32 numRefIdxActive)
{

/* Variables */

    u32 tmp, i, j, value;
    i32 itmp;

/* Code */

    ASSERT(pStrmData);
    ASSERT(pSubMbPred);
    ASSERT(h264bsdMbPartPredMode(mbType) == PRED_MODE_INTER);

    for (i = 0; i < 4; i++)
    {
        tmp = h264bsdDecodeExpGolombUnsigned(pStrmData, &value);
        if (tmp != HANTRO_OK || value > 3)
            return(HANTRO_NOK);
        pSubMbPred->subMbType[i] = (subMbType_e)value;
    }

    if ( (numRefIdxActive > 1) && (mbType != P_8x8ref0) )
    {
        for (i = 0; i < 4; i++)
        {
            tmp = h264bsdDecodeExpGolombTruncated(pStrmData, &value,
                (u32)(numRefIdxActive > 2));
            if (tmp != HANTRO_OK || value >= numRefIdxActive)
                return(HANTRO_NOK);
            pSubMbPred->refIdxL0[i] = value;
        }
    }

    for (i = 0; i < 4; i++)
    {
        j = 0;
        for (value = h264bsdNumSubMbPart(pSubMbPred->subMbType[i]);
             value--; j++)
        {
            tmp = h264bsdDecodeExpGolombSigned(pStrmData, &itmp);
            if (tmp != HANTRO_OK)
                return(tmp);
            pSubMbPred->mvdL0[i][j].hor = (i16)itmp;

            tmp = h264bsdDecodeExpGolombSigned(pStrmData, &itmp);
            if (tmp != HANTRO_OK)
                return(tmp);
            pSubMbPred->mvdL0[i][j].ver = (i16)itmp;
        }
    }

    return(HANTRO_OK);

}

#ifdef H264DEC_OMXDL
/*------------------------------------------------------------------------------

    Function: DecodeResidual

        Functional description:
          Parse residual information from bit stream and store in 'pResidual'.

------------------------------------------------------------------------------*/

u32 DecodeResidual(strmData_t *pStrmData, residual_t *pResidual,
    mbStorage_t *pMb, mbType_e mbType, u32 codedBlockPattern)
{

/* Variables */

    u32 i, j;
    u32 blockCoded;
    u32 blockIndex;
    u32 is16x16;
    OMX_INT nc;
    OMXResult omxRes;
    OMX_U8 *pPosCoefBuf;


/* Code */

    ASSERT(pStrmData);
    ASSERT(pResidual);

    pPosCoefBuf = pResidual->posCoefBuf;

    /* luma DC is at index 24 */
    if (h264bsdMbPartPredMode(mbType) == PRED_MODE_INTRA16x16)
    {
        nc = (OMX_INT)DetermineNc(pMb, 0, pResidual->totalCoeff);
#ifndef H264DEC_NEON
        omxRes =  omxVCM4P10_DecodeCoeffsToPairCAVLC(
                (const OMX_U8 **) (&pStrmData->pStrmCurrPos),
                (OMX_S32*) (&pStrmData->bitPosInWord),
                &pResidual->totalCoeff[24],
                &pPosCoefBuf,
                nc,
                16);
#else
        omxRes = armVCM4P10_DecodeCoeffsToPair(
                (const OMX_U8 **) (&pStrmData->pStrmCurrPos),
                (OMX_S32*) (&pStrmData->bitPosInWord),
                &pResidual->totalCoeff[24],
                &pPosCoefBuf,
                nc,
                16);
#endif
        if (omxRes != OMX_Sts_NoErr)
            return(HANTRO_NOK);
        is16x16 = HANTRO_TRUE;
    }
    else
        is16x16 = HANTRO_FALSE;

    for (i = 4, blockIndex = 0; i--;)
    {
        /* luma cbp in bits 0-3 */
        blockCoded = codedBlockPattern & 0x1;
        codedBlockPattern >>= 1;
        if (blockCoded)
        {
            for (j = 4; j--; blockIndex++)
            {
                nc = (OMX_INT)DetermineNc(pMb,blockIndex,pResidual->totalCoeff);
                if (is16x16)
                {
#ifndef H264DEC_NEON
                    omxRes =  omxVCM4P10_DecodeCoeffsToPairCAVLC(
                            (const OMX_U8 **) (&pStrmData->pStrmCurrPos),
                            (OMX_S32*) (&pStrmData->bitPosInWord),
                            &pResidual->totalCoeff[blockIndex],
                            &pPosCoefBuf,
                            nc,
                            15);
#else
                    omxRes =  armVCM4P10_DecodeCoeffsToPair(
                            (const OMX_U8 **) (&pStrmData->pStrmCurrPos),
                            (OMX_S32*) (&pStrmData->bitPosInWord),
                            &pResidual->totalCoeff[blockIndex],
                            &pPosCoefBuf,
                            nc,
                            15);
#endif
                }
                else
                {
#ifndef H264DEC_NEON
                    omxRes =  omxVCM4P10_DecodeCoeffsToPairCAVLC(
                            (const OMX_U8 **) (&pStrmData->pStrmCurrPos),
                            (OMX_S32*) (&pStrmData->bitPosInWord),
                            &pResidual->totalCoeff[blockIndex],
                            &pPosCoefBuf,
                            nc,
                            16);
#else
                    omxRes = armVCM4P10_DecodeCoeffsToPair(
                            (const OMX_U8 **) (&pStrmData->pStrmCurrPos),
                            (OMX_S32*) (&pStrmData->bitPosInWord),
                            &pResidual->totalCoeff[blockIndex],
                            &pPosCoefBuf,
                            nc,
                            16);
#endif
                }
                if (omxRes != OMX_Sts_NoErr)
                    return(HANTRO_NOK);
            }
        }
        else
            blockIndex += 4;
    }

    /* chroma DC block are at indices 25 and 26 */
    blockCoded = codedBlockPattern & 0x3;
    if (blockCoded)
    {
#ifndef H264DEC_NEON
        omxRes =  omxVCM4P10_DecodeChromaDcCoeffsToPairCAVLC(
                (const OMX_U8**) (&pStrmData->pStrmCurrPos),
                (OMX_S32*) (&pStrmData->bitPosInWord),
                &pResidual->totalCoeff[25],
                &pPosCoefBuf);
#else
        omxRes = armVCM4P10_DecodeCoeffsToPair(
                (const OMX_U8**) (&pStrmData->pStrmCurrPos),
                (OMX_S32*) (&pStrmData->bitPosInWord),
                &pResidual->totalCoeff[25],
                &pPosCoefBuf,
                17,
                4);
#endif
        if (omxRes != OMX_Sts_NoErr)
            return(HANTRO_NOK);
#ifndef H264DEC_NEON
        omxRes =  omxVCM4P10_DecodeChromaDcCoeffsToPairCAVLC(
                (const OMX_U8**) (&pStrmData->pStrmCurrPos),
                (OMX_S32*) (&pStrmData->bitPosInWord),
                &pResidual->totalCoeff[26],
                &pPosCoefBuf);
#else
        omxRes = armVCM4P10_DecodeCoeffsToPair(
                (const OMX_U8**) (&pStrmData->pStrmCurrPos),
                (OMX_S32*) (&pStrmData->bitPosInWord),
                &pResidual->totalCoeff[26],
                &pPosCoefBuf,
                17,
                4);
#endif
        if (omxRes != OMX_Sts_NoErr)
            return(HANTRO_NOK);
    }

    /* chroma AC */
    blockCoded = codedBlockPattern & 0x2;
    if (blockCoded)
    {
        for (i = 8; i--;blockIndex++)
        {
            nc = (OMX_INT)DetermineNc(pMb, blockIndex, pResidual->totalCoeff);
#ifndef H264DEC_NEON
            omxRes =  omxVCM4P10_DecodeCoeffsToPairCAVLC(
                    (const OMX_U8 **) (&pStrmData->pStrmCurrPos),
                    (OMX_S32*) (&pStrmData->bitPosInWord),
                    &pResidual->totalCoeff[blockIndex],
                    &pPosCoefBuf,
                    nc,
                    15);
#else
            omxRes =  armVCM4P10_DecodeCoeffsToPair(
                    (const OMX_U8 **) (&pStrmData->pStrmCurrPos),
                    (OMX_S32*) (&pStrmData->bitPosInWord),
                    &pResidual->totalCoeff[blockIndex],
                    &pPosCoefBuf,
                    nc,
                    15);
#endif
            if (omxRes != OMX_Sts_NoErr)
                return(HANTRO_NOK);
        }
    }

    return(HANTRO_OK);

}

#else
/*------------------------------------------------------------------------------

    Function: DecodeResidual

        Functional description:
          Parse residual information from bit stream and store in 'pResidual'.

------------------------------------------------------------------------------*/

u32 DecodeResidual(strmData_t *pStrmData, residual_t *pResidual,
    mbStorage_t *pMb, mbType_e mbType, u32 codedBlockPattern)
{

/* Variables */

    u32 i, j, tmp;
    i32 nc;
    u32 blockCoded;
    u32 blockIndex;
    u32 is16x16;
    i32 (*level)[16];

/* Code */

    ASSERT(pStrmData);
    ASSERT(pResidual);

    level = pResidual->level;

    /* luma DC is at index 24 */
    if (h264bsdMbPartPredMode(mbType) == PRED_MODE_INTRA16x16)
    {
        nc = (i32)DetermineNc(pMb, 0, pResidual->totalCoeff);
        tmp = h264bsdDecodeResidualBlockCavlc(pStrmData, level[24], nc, 16);
        if ((tmp & 0xF) != HANTRO_OK)
            return(tmp);
        pResidual->totalCoeff[24] = (tmp >> 4) & 0xFF;
        is16x16 = HANTRO_TRUE;
    }
    else
        is16x16 = HANTRO_FALSE;

    for (i = 4, blockIndex = 0; i--;)
    {
        /* luma cbp in bits 0-3 */
        blockCoded = codedBlockPattern & 0x1;
        codedBlockPattern >>= 1;
        if (blockCoded)
        {
            for (j = 4; j--; blockIndex++)
            {
                nc = (i32)DetermineNc(pMb, blockIndex, pResidual->totalCoeff);
                if (is16x16)
                {
                    tmp = h264bsdDecodeResidualBlockCavlc(pStrmData,
                        level[blockIndex] + 1, nc, 15);
                    pResidual->coeffMap[blockIndex] = tmp >> 15;
                }
                else
                {
                    tmp = h264bsdDecodeResidualBlockCavlc(pStrmData,
                        level[blockIndex], nc, 16);
                    pResidual->coeffMap[blockIndex] = tmp >> 16;
                }
                if ((tmp & 0xF) != HANTRO_OK)
                    return(tmp);
                pResidual->totalCoeff[blockIndex] = (tmp >> 4) & 0xFF;
            }
        }
        else
            blockIndex += 4;
    }

    /* chroma DC block are at indices 25 and 26 */
    blockCoded = codedBlockPattern & 0x3;
    if (blockCoded)
    {
        tmp = h264bsdDecodeResidualBlockCavlc(pStrmData, level[25], -1, 4);
        if ((tmp & 0xF) != HANTRO_OK)
            return(tmp);
        pResidual->totalCoeff[25] = (tmp >> 4) & 0xFF;
        tmp = h264bsdDecodeResidualBlockCavlc(pStrmData, level[25]+4, -1, 4);
        if ((tmp & 0xF) != HANTRO_OK)
            return(tmp);
        pResidual->totalCoeff[26] = (tmp >> 4) & 0xFF;
    }

    /* chroma AC */
    blockCoded = codedBlockPattern & 0x2;
    if (blockCoded)
    {
        for (i = 8; i--;blockIndex++)
        {
            nc = (i32)DetermineNc(pMb, blockIndex, pResidual->totalCoeff);
            tmp = h264bsdDecodeResidualBlockCavlc(pStrmData,
                level[blockIndex] + 1, nc, 15);
            if ((tmp & 0xF) != HANTRO_OK)
                return(tmp);
            pResidual->totalCoeff[blockIndex] = (tmp >> 4) & 0xFF;
            pResidual->coeffMap[blockIndex] = (tmp >> 15);
        }
    }

    return(HANTRO_OK);

}
#endif

/*------------------------------------------------------------------------------

    Function: DetermineNc

        Functional description:
          Returns the nC of a block.

------------------------------------------------------------------------------*/
#ifdef H264DEC_OMXDL
u32 DetermineNc(mbStorage_t *pMb, u32 blockIndex, u8 *pTotalCoeff)
#else
u32 DetermineNc(mbStorage_t *pMb, u32 blockIndex, i16 *pTotalCoeff)
#endif
{
/*lint -e702 */
/* Variables */

    u32 tmp;
    i32 n;
    const neighbour_t *neighbourA, *neighbourB;
    u8 neighbourAindex, neighbourBindex;

/* Code */

    ASSERT(blockIndex < 24);

    /* if neighbour block belongs to current macroblock totalCoeff array
     * mbStorage has not been set/updated yet -> use pTotalCoeff */
    neighbourA = h264bsdNeighbour4x4BlockA(blockIndex);
    neighbourB = h264bsdNeighbour4x4BlockB(blockIndex);
    neighbourAindex = neighbourA->index;
    neighbourBindex = neighbourB->index;
    if (neighbourA->mb == MB_CURR && neighbourB->mb == MB_CURR)
    {
        n = (pTotalCoeff[neighbourAindex] +
             pTotalCoeff[neighbourBindex] + 1)>>1;
    }
    else if (neighbourA->mb == MB_CURR)
    {
        n = pTotalCoeff[neighbourAindex];
        if (h264bsdIsNeighbourAvailable(pMb, pMb->mbB))
        {
            n = (n + pMb->mbB->totalCoeff[neighbourBindex] + 1) >> 1;
        }
    }
    else if (neighbourB->mb == MB_CURR)
    {
        n = pTotalCoeff[neighbourBindex];
        if (h264bsdIsNeighbourAvailable(pMb, pMb->mbA))
        {
            n = (n + pMb->mbA->totalCoeff[neighbourAindex] + 1) >> 1;
        }
    }
    else
    {
        n = tmp = 0;
        if (h264bsdIsNeighbourAvailable(pMb, pMb->mbA))
        {
            n = pMb->mbA->totalCoeff[neighbourAindex];
            tmp = 1;
        }
        if (h264bsdIsNeighbourAvailable(pMb, pMb->mbB))
        {
            if (tmp)
                n = (n + pMb->mbB->totalCoeff[neighbourBindex] + 1) >> 1;
            else
                n = pMb->mbB->totalCoeff[neighbourBindex];
        }
    }
    return((u32)n);
/*lint +e702 */
}

/*------------------------------------------------------------------------------

    Function: CbpIntra16x16

        Functional description:
          Returns the coded block pattern for intra 16x16 macroblock.

------------------------------------------------------------------------------*/

u32 CbpIntra16x16(mbType_e mbType)
{

/* Variables */

    u32 cbp;
    u32 tmp;

/* Code */

    ASSERT(mbType >= I_16x16_0_0_0 && mbType <= I_16x16_3_2_1);

    if (mbType >= I_16x16_0_0_1)
        cbp = 15;
    else
        cbp = 0;

    /* tmp is 0 for I_16x16_0_0_0 mb type */
    /* ignore lint warning on arithmetic on enum's */
    tmp = /*lint -e(656)*/(mbType - I_16x16_0_0_0) >> 2;
    if (tmp > 2)
        tmp -= 3;

    cbp += tmp << 4;

    return(cbp);

}

/*------------------------------------------------------------------------------

    Function: h264bsdPredModeIntra16x16

        Functional description:
          Returns the prediction mode for intra 16x16 macroblock.

------------------------------------------------------------------------------*/

u32 h264bsdPredModeIntra16x16(mbType_e mbType)
{

/* Variables */

    u32 tmp;

/* Code */

    ASSERT(mbType >= I_16x16_0_0_0 && mbType <= I_16x16_3_2_1);

    /* tmp is 0 for I_16x16_0_0_0 mb type */
    /* ignore lint warning on arithmetic on enum's */
    tmp = /*lint -e(656)*/(mbType - I_16x16_0_0_0);

    return(tmp & 0x3);

}

/*------------------------------------------------------------------------------

    Function: h264bsdDecodeMacroblock

        Functional description:
          Decode one macroblock and write into output image.

        Inputs:
          pMb           pointer to macroblock specific information
          mbLayer       pointer to current macroblock data from stream
          currImage     pointer to output image
          dpb           pointer to decoded picture buffer
          qpY           pointer to slice QP
          mbNum         current macroblock number
          constrainedIntraPred  flag specifying if neighbouring inter
                                macroblocks are used in intra prediction

        Outputs:
          pMb           structure is updated with current macroblock
          currImage     decoded macroblock is written into output image

        Returns:
          HANTRO_OK     success
          HANTRO_NOK    error in macroblock decoding

------------------------------------------------------------------------------*/

u32 h264bsdDecodeMacroblock(mbStorage_t *pMb, macroblockLayer_t *pMbLayer,
    image_t *currImage, dpbStorage_t *dpb, i32 *qpY, u32 mbNum,
    u32 constrainedIntraPredFlag, u8* data)
{

/* Variables */

    u32 i, tmp;
    mbType_e mbType;
#ifdef H264DEC_OMXDL
    const u8 *pSrc;
#endif
/* Code */

    ASSERT(pMb);
    ASSERT(pMbLayer);
    ASSERT(currImage);
    ASSERT(qpY && *qpY < 52);
    ASSERT(mbNum < currImage->width*currImage->height);

    mbType = pMbLayer->mbType;
    pMb->mbType = mbType;

    pMb->decoded++;

    h264bsdSetCurrImageMbPointers(currImage, mbNum);

    if (mbType == I_PCM)
    {
        u8 *pData = (u8*)data;
#ifdef H264DEC_OMXDL
        u8 *tot = pMb->totalCoeff;
#else
        i16 *tot = pMb->totalCoeff;
#endif
        i32 *lev = pMbLayer->residual.level[0];

        pMb->qpY = 0;

        /* if decoded flag > 1 -> mb has already been successfully decoded and
         * written to output -> do not write again */
        if (pMb->decoded > 1)
        {
            for (i = 24; i--;)
                *tot++ = 16;
            return HANTRO_OK;
        }

        for (i = 24; i--;)
        {
            *tot++ = 16;
            for (tmp = 16; tmp--;)
                *pData++ = (u8)(*lev++);
        }
        h264bsdWriteMacroblock(currImage, (u8*)data);

        return(HANTRO_OK);
    }
    else
    {
#ifdef H264DEC_OMXDL
        if (h264bsdMbPartPredMode(mbType) == PRED_MODE_INTER)
        {
            tmp = h264bsdInterPrediction(pMb, pMbLayer, dpb, mbNum,
                currImage, (u8*)data);
            if (tmp != HANTRO_OK) return (tmp);
        }
#endif
        if (mbType != P_Skip)
        {
            H264SwDecMemcpy(pMb->totalCoeff,
                            pMbLayer->residual.totalCoeff,
                            27*sizeof(*pMb->totalCoeff));

            /* update qpY */
            if (pMbLayer->mbQpDelta)
            {
                *qpY = *qpY + pMbLayer->mbQpDelta;
                if (*qpY < 0) *qpY += 52;
                else if (*qpY >= 52) *qpY -= 52;
            }
            pMb->qpY = (u32)*qpY;

#ifdef H264DEC_OMXDL
            pSrc = pMbLayer->residual.posCoefBuf;

            if (h264bsdMbPartPredMode(mbType) == PRED_MODE_INTER)
            {
                OMXResult res;
                u8 *p;
                u8 *totalCoeff = pMb->totalCoeff;

                for (i = 0; i < 16; i++, totalCoeff++)
                {
                    p = data + lumaIndex[i];
                    if (*totalCoeff)
                    {
                        res = omxVCM4P10_DequantTransformResidualFromPairAndAdd(
                                &pSrc, p, 0, p, 16, 16, *qpY, *totalCoeff);
                        if (res != OMX_Sts_NoErr)
                            return (HANTRO_NOK);
                    }
                }

            }
            else if (h264bsdMbPartPredMode(mbType) == PRED_MODE_INTRA4x4)
            {
                tmp = ProcessIntra4x4Residual(pMb,
                                              data,
                                              constrainedIntraPredFlag,
                                              pMbLayer,
                                              &pSrc,
                                              currImage);
                if (tmp != HANTRO_OK)
                    return (tmp);
            }
            else if (h264bsdMbPartPredMode(mbType) == PRED_MODE_INTRA16x16)
            {
                tmp = ProcessIntra16x16Residual(pMb,
                                        data,
                                        constrainedIntraPredFlag,
                                        pMbLayer->mbPred.intraChromaPredMode,
                                        &pSrc,
                                        currImage);
                if (tmp != HANTRO_OK)
                    return (tmp);
            }

            tmp = ProcessChromaResidual(pMb, data, &pSrc);

#else
            tmp = ProcessResidual(pMb, pMbLayer->residual.level,
                pMbLayer->residual.coeffMap);
#endif
            if (tmp != HANTRO_OK)
                return (tmp);
        }
        else
        {
            H264SwDecMemset(pMb->totalCoeff, 0, 27*sizeof(*pMb->totalCoeff));
            pMb->qpY = (u32)*qpY;
        }
#ifdef H264DEC_OMXDL
        /* if decoded flag > 1 -> mb has already been successfully decoded and
         * written to output -> do not write again */
        if (pMb->decoded > 1)
            return HANTRO_OK;

        h264bsdWriteMacroblock(currImage, data);
#else
        if (h264bsdMbPartPredMode(mbType) != PRED_MODE_INTER)
        {
            tmp = h264bsdIntraPrediction(pMb, pMbLayer, currImage, mbNum,
                constrainedIntraPredFlag, (u8*)data);
            if (tmp != HANTRO_OK) return (tmp);
        }
        else
        {
            tmp = h264bsdInterPrediction(pMb, pMbLayer, dpb, mbNum,
                currImage, (u8*)data);
            if (tmp != HANTRO_OK) return (tmp);
        }
#endif
    }

    return HANTRO_OK;
}


#ifdef H264DEC_OMXDL

/*------------------------------------------------------------------------------

    Function: ProcessChromaResidual

        Functional description:
          Process the residual data of chroma with
          inverse quantization and inverse transform.

------------------------------------------------------------------------------*/
u32 ProcessChromaResidual(mbStorage_t *pMb, u8 *data, const u8 **pSrc )
{
    u32 i;
    u32 chromaQp;
    i16 *pDc;
    i16 dc[4 + 4] = {0,0,0,0,0,0,0,0};
    u8 *totalCoeff;
    OMXResult result;
    u8 *p;

    /* chroma DC processing. First chroma dc block is block with index 25 */
    chromaQp =
        h264bsdQpC[CLIP3(0, 51, (i32)pMb->qpY + pMb->chromaQpIndexOffset)];

    if (pMb->totalCoeff[25])
    {
        pDc = dc;
        result = omxVCM4P10_TransformDequantChromaDCFromPair(
                pSrc,
                pDc,
                (i32)chromaQp);
        if (result != OMX_Sts_NoErr)
            return (HANTRO_NOK);
    }
    if (pMb->totalCoeff[26])
    {
        pDc = dc+4;
        result = omxVCM4P10_TransformDequantChromaDCFromPair(
                pSrc,
                pDc,
                (i32)chromaQp);
        if (result != OMX_Sts_NoErr)
            return (HANTRO_NOK);
    }

    pDc = dc;
    totalCoeff = pMb->totalCoeff + 16;
    for (i = 0; i < 8; i++, pDc++, totalCoeff++)
    {
        /* chroma prediction */
        if (*totalCoeff || *pDc)
        {
            p = data + chromaIndex[i];
            result = omxVCM4P10_DequantTransformResidualFromPairAndAdd(
                    pSrc,
                    p,
                    pDc,
                    p,
                    8,
                    8,
                    (i32)chromaQp,
                    *totalCoeff);
            if (result != OMX_Sts_NoErr)
                return (HANTRO_NOK);
        }
    }

    return(HANTRO_OK);
}

/*------------------------------------------------------------------------------

    Function: ProcessIntra16x16Residual

        Functional description:
          Process the residual data of luma with
          inverse quantization and inverse transform.

------------------------------------------------------------------------------*/
u32 ProcessIntra16x16Residual(mbStorage_t *pMb,
                              u8 *data,
                              u32 constrainedIntraPred,
                              u32 intraChromaPredMode,
                              const u8** pSrc,
                              image_t *image)
{
    u32 i;
    i16 *pDc;
    i16 dc[16] = {0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0};
    u8 *totalCoeff;
    OMXResult result;
    u8 *p;

    totalCoeff = pMb->totalCoeff;

    if (totalCoeff[24])
    {
        pDc = dc;
        result = omxVCM4P10_TransformDequantLumaDCFromPair(
                    pSrc,
                    pDc,
                    (i32)pMb->qpY);
        if (result != OMX_Sts_NoErr)
            return (HANTRO_NOK);
    }
    /* Intra 16x16 pred */
    if (h264bsdIntra16x16Prediction(pMb, data, image->luma,
                            image->width*16, constrainedIntraPred) != HANTRO_OK)
        return(HANTRO_NOK);
    for (i = 0; i < 16; i++, totalCoeff++)
    {
        p = data + lumaIndex[i];
        pDc = &dc[dcCoeffIndex[i]];
        if (*totalCoeff || *pDc)
        {
            result = omxVCM4P10_DequantTransformResidualFromPairAndAdd(
                    pSrc,
                    p,
                    pDc,
                    p,
                    16,
                    16,
                    (i32)pMb->qpY,
                    *totalCoeff);
            if (result != OMX_Sts_NoErr)
                return (HANTRO_NOK);
        }
    }

    if (h264bsdIntraChromaPrediction(pMb, data + 256,
                image,
                intraChromaPredMode,
                constrainedIntraPred) != HANTRO_OK)
        return(HANTRO_NOK);

    return HANTRO_OK;
}

/*------------------------------------------------------------------------------

    Function: ProcessIntra4x4Residual

        Functional description:
          Process the residual data of luma with
          inverse quantization and inverse transform.

------------------------------------------------------------------------------*/
u32 ProcessIntra4x4Residual(mbStorage_t *pMb,
                            u8 *data,
                            u32 constrainedIntraPred,
                            macroblockLayer_t *mbLayer,
                            const u8 **pSrc,
                            image_t *image)
{
    u32 i;
    u8 *totalCoeff;
    OMXResult result;
    u8 *p;

    totalCoeff = pMb->totalCoeff;

    for (i = 0; i < 16; i++, totalCoeff++)
    {
        p = data + lumaIndex[i];
        if (h264bsdIntra4x4Prediction(pMb, p, mbLayer, image->luma,
                    image->width*16, constrainedIntraPred, i) != HANTRO_OK)
            return(HANTRO_NOK);

        if (*totalCoeff)
        {
            result = omxVCM4P10_DequantTransformResidualFromPairAndAdd(
                    pSrc,
                    p,
                    NULL,
                    p,
                    16,
                    16,
                    (i32)pMb->qpY,
                    *totalCoeff);
            if (result != OMX_Sts_NoErr)
                return (HANTRO_NOK);
        }
    }

    if (h264bsdIntraChromaPrediction(pMb, data + 256,
                image,
                mbLayer->mbPred.intraChromaPredMode,
                constrainedIntraPred) != HANTRO_OK)
        return(HANTRO_NOK);

    return HANTRO_OK;
}

#else /* H264DEC_OMXDL */

/*------------------------------------------------------------------------------

    Function: ProcessResidual

        Functional description:
          Process the residual data of one macroblock with
          inverse quantization and inverse transform.

------------------------------------------------------------------------------*/

u32 ProcessResidual(mbStorage_t *pMb, i32 residualLevel[][16], u32 *coeffMap)
{

/* Variables */

    u32 i;
    u32 chromaQp;
    i32 (*blockData)[16];
    i32 (*blockDc)[16];
    i16 *totalCoeff;
    i32 *chromaDc;
    const u32 *dcCoeffIdx;

/* Code */

    ASSERT(pMb);
    ASSERT(residualLevel);

    /* set pointers to DC coefficient blocks */
    blockDc = residualLevel + 24;

    blockData = residualLevel;
    totalCoeff = pMb->totalCoeff;
    if (h264bsdMbPartPredMode(pMb->mbType) == PRED_MODE_INTRA16x16)
    {
        if (totalCoeff[24])
        {
            h264bsdProcessLumaDc(*blockDc, pMb->qpY);
        }
        dcCoeffIdx = dcCoeffIndex;

        for (i = 16; i--; blockData++, totalCoeff++, coeffMap++)
        {
            /* set dc coefficient of luma block */
            (*blockData)[0] = (*blockDc)[*dcCoeffIdx++];
            if ((*blockData)[0] || *totalCoeff)
            {
                if (h264bsdProcessBlock(*blockData, pMb->qpY, 1, *coeffMap) !=
                    HANTRO_OK)
                    return(HANTRO_NOK);
            }
            else
                MARK_RESIDUAL_EMPTY(*blockData);
        }
    }
    else
    {
        for (i = 16; i--; blockData++, totalCoeff++, coeffMap++)
        {
            if (*totalCoeff)
            {
                if (h264bsdProcessBlock(*blockData, pMb->qpY, 0, *coeffMap) !=
                    HANTRO_OK)
                    return(HANTRO_NOK);
            }
            else
                MARK_RESIDUAL_EMPTY(*blockData);
        }
    }

    /* chroma DC processing. First chroma dc block is block with index 25 */
    chromaQp =
        h264bsdQpC[CLIP3(0, 51, (i32)pMb->qpY + pMb->chromaQpIndexOffset)];
    if (pMb->totalCoeff[25] || pMb->totalCoeff[26])
        h264bsdProcessChromaDc(residualLevel[25], chromaQp);
    chromaDc = residualLevel[25];
    for (i = 8; i--; blockData++, totalCoeff++, coeffMap++)
    {
        /* set dc coefficient of chroma block */
        (*blockData)[0] = *chromaDc++;
        if ((*blockData)[0] || *totalCoeff)
        {
            if (h264bsdProcessBlock(*blockData, chromaQp, 1,*coeffMap) !=
                HANTRO_OK)
                return(HANTRO_NOK);
        }
        else
            MARK_RESIDUAL_EMPTY(*blockData);
    }

    return(HANTRO_OK);
}
#endif /* H264DEC_OMXDL */

/*------------------------------------------------------------------------------

    Function: h264bsdSubMbPartMode

        Functional description:
          Returns the macroblock's sub-partition mode.

------------------------------------------------------------------------------*/

subMbPartMode_e h264bsdSubMbPartMode(subMbType_e subMbType)
{

/* Variables */


/* Code */

    ASSERT(subMbType < 4);

    return((subMbPartMode_e)subMbType);

}


