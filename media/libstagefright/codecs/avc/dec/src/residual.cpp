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

#include <string.h>

#include "avcdec_lib.h"
#include "avcdec_bitstream.h"

AVCDec_Status DecodeIntraPCM(AVCCommonObj *video, AVCDecBitstream *stream)
{
    AVCDec_Status status;
    int j;
    int mb_x, mb_y, offset1;
    uint8 *pDst;
    uint32 byte0, byte1;
    int pitch;

    mb_x = video->mb_x;
    mb_y = video->mb_y;

#ifdef USE_PRED_BLOCK
    pDst = video->pred_block + 84;
    pitch = 20;
#else
    offset1 = (mb_x << 4) + (mb_y << 4) * video->PicWidthInSamplesL;
    pDst = video->currPic->Sl + offset1;
    pitch = video->currPic->pitch;
#endif

    /* at this point bitstream is byte-aligned */
    j = 16;
    while (j > 0)
    {
        status = BitstreamReadBits(stream, 8, (uint*) & byte0);
        status = BitstreamReadBits(stream, 8, (uint*) & byte1);
        byte0 |= (byte1 << 8);
        status = BitstreamReadBits(stream, 8, (uint*) & byte1);
        byte0 |= (byte1 << 16);
        status = BitstreamReadBits(stream, 8, (uint*) & byte1);
        byte0 |= (byte1 << 24);
        *((uint32*)pDst) = byte0;

        status = BitstreamReadBits(stream, 8, (uint*) & byte0);
        status = BitstreamReadBits(stream, 8, (uint*) & byte1);
        byte0 |= (byte1 << 8);
        status = BitstreamReadBits(stream, 8, (uint*) & byte1);
        byte0 |= (byte1 << 16);
        status = BitstreamReadBits(stream, 8, (uint*) & byte1);
        byte0 |= (byte1 << 24);
        *((uint32*)(pDst + 4)) = byte0;

        status = BitstreamReadBits(stream, 8, (uint*) & byte0);
        status = BitstreamReadBits(stream, 8, (uint*) & byte1);
        byte0 |= (byte1 << 8);
        status = BitstreamReadBits(stream, 8, (uint*) & byte1);
        byte0 |= (byte1 << 16);
        status = BitstreamReadBits(stream, 8, (uint*) & byte1);
        byte0 |= (byte1 << 24);
        *((uint32*)(pDst + 8)) = byte0;

        status = BitstreamReadBits(stream, 8, (uint*) & byte0);
        status = BitstreamReadBits(stream, 8, (uint*) & byte1);
        byte0 |= (byte1 << 8);
        status = BitstreamReadBits(stream, 8, (uint*) & byte1);
        byte0 |= (byte1 << 16);
        status = BitstreamReadBits(stream, 8, (uint*) & byte1);
        byte0 |= (byte1 << 24);
        *((uint32*)(pDst + 12)) = byte0;
        j--;
        pDst += pitch;

        if (status != AVCDEC_SUCCESS)  /* check only once per line */
            return status;
    }

#ifdef USE_PRED_BLOCK
    pDst = video->pred_block + 452;
    pitch = 12;
#else
    offset1 = (offset1 >> 2) + (mb_x << 2);
    pDst = video->currPic->Scb + offset1;
    pitch >>= 1;
#endif

    j = 8;
    while (j > 0)
    {
        status = BitstreamReadBits(stream, 8, (uint*) & byte0);
        status = BitstreamReadBits(stream, 8, (uint*) & byte1);
        byte0 |= (byte1 << 8);
        status = BitstreamReadBits(stream, 8, (uint*) & byte1);
        byte0 |= (byte1 << 16);
        status = BitstreamReadBits(stream, 8, (uint*) & byte1);
        byte0 |= (byte1 << 24);
        *((uint32*)pDst) = byte0;

        status = BitstreamReadBits(stream, 8, (uint*) & byte0);
        status = BitstreamReadBits(stream, 8, (uint*) & byte1);
        byte0 |= (byte1 << 8);
        status = BitstreamReadBits(stream, 8, (uint*) & byte1);
        byte0 |= (byte1 << 16);
        status = BitstreamReadBits(stream, 8, (uint*) & byte1);
        byte0 |= (byte1 << 24);
        *((uint32*)(pDst + 4)) = byte0;

        j--;
        pDst += pitch;

        if (status != AVCDEC_SUCCESS)  /* check only once per line */
            return status;
    }

#ifdef USE_PRED_BLOCK
    pDst = video->pred_block + 596;
    pitch = 12;
#else
    pDst = video->currPic->Scr + offset1;
#endif
    j = 8;
    while (j > 0)
    {
        status = BitstreamReadBits(stream, 8, (uint*) & byte0);
        status = BitstreamReadBits(stream, 8, (uint*) & byte1);
        byte0 |= (byte1 << 8);
        status = BitstreamReadBits(stream, 8, (uint*) & byte1);
        byte0 |= (byte1 << 16);
        status = BitstreamReadBits(stream, 8, (uint*) & byte1);
        byte0 |= (byte1 << 24);
        *((uint32*)pDst) = byte0;

        status = BitstreamReadBits(stream, 8, (uint*) & byte0);
        status = BitstreamReadBits(stream, 8, (uint*) & byte1);
        byte0 |= (byte1 << 8);
        status = BitstreamReadBits(stream, 8, (uint*) & byte1);
        byte0 |= (byte1 << 16);
        status = BitstreamReadBits(stream, 8, (uint*) & byte1);
        byte0 |= (byte1 << 24);
        *((uint32*)(pDst + 4)) = byte0;

        j--;
        pDst += pitch;

        if (status != AVCDEC_SUCCESS)  /* check only once per line */
            return status;
    }

#ifdef MB_BASED_DEBLOCK
    SaveNeighborForIntraPred(video, offset1);
#endif

    return AVCDEC_SUCCESS;
}



/* see subclause 7.3.5.3 and readCBPandCoeffsFromNAL() in JM*/
AVCDec_Status residual(AVCDecObject *decvid, AVCMacroblock *currMB)
{
    AVCCommonObj *video = decvid->common;
    int16 *block;
    int level[16], run[16], numcoeff; /* output from residual_block_cavlc */
    int block_x, i, j, k, idx, iCbCr;
    int mbPartIdx, subMbPartIdx, mbPartIdx_X, mbPartIdx_Y;
    int nC, maxNumCoeff = 16;
    int coeffNum, start_scan = 0;
    uint8 *zz_scan;
    int Rq, Qq;
    uint32 cbp4x4 = 0;

    /* in 8.5.4, it only says if it's field macroblock. */

    zz_scan = (uint8*) ZZ_SCAN_BLOCK;


    /* see 8.5.8 for the initialization of these values */
    Qq = video->QPy_div_6;
    Rq = video->QPy_mod_6;

    memset(video->block, 0, sizeof(int16)*NUM_PIXELS_IN_MB);

    if (currMB->mbMode == AVC_I16)
    {
        nC = predict_nnz(video, 0, 0);
        decvid->residual_block(decvid, nC, 16, level, run, &numcoeff);
        /* then performs zigzag and transform */
        block = video->block;
        coeffNum = -1;
        for (i = numcoeff - 1; i >= 0; i--)
        {
            coeffNum += run[i] + 1;
            if (coeffNum > 15)
            {
                return AVCDEC_FAIL;
            }
            idx = zz_scan[coeffNum] << 2;
            /*          idx = ((idx>>2)<<6) + ((idx&3)<<2); */
            block[idx] = level[i];
        }

        /* inverse transform on Intra16x16DCLevel */
        if (numcoeff)
        {
            Intra16DCTrans(block, Qq, Rq);
            cbp4x4 = 0xFFFF;
        }
        maxNumCoeff = 15;
        start_scan = 1;
    }

    memset(currMB->nz_coeff, 0, sizeof(uint8)*24);

    for (mbPartIdx = 0; mbPartIdx < 4; mbPartIdx++)
    {
        mbPartIdx_X = (mbPartIdx & 1) << 1;
        mbPartIdx_Y = mbPartIdx & -2;

        if (currMB->CBP&(1 << mbPartIdx))
        {
            for (subMbPartIdx = 0; subMbPartIdx < 4; subMbPartIdx++)
            {
                i = mbPartIdx_X + (subMbPartIdx & 1);  // check this
                j = mbPartIdx_Y + (subMbPartIdx >> 1);
                block = video->block + (j << 6) + (i << 2);  //
                nC = predict_nnz(video, i, j);
                decvid->residual_block(decvid, nC, maxNumCoeff, level, run, &numcoeff);

                /* convert to raster scan and quantize*/
                /* Note: for P mb in SP slice and SI mb in SI slice,
                 the quantization cannot be done here.
                 block[idx] should be assigned with level[k].
                itrans will be done after the prediction.
                There will be transformation on the predicted value,
                then addition with block[idx], then this quantization
                and transform.*/

                coeffNum = -1 + start_scan;
                for (k = numcoeff - 1; k >= 0; k--)
                {
                    coeffNum += run[k] + 1;
                    if (coeffNum > 15)
                    {
                        return AVCDEC_FAIL;
                    }
                    idx = zz_scan[coeffNum];
                    block[idx] = (level[k] * dequant_coefres[Rq][coeffNum]) << Qq ;
                }

                currMB->nz_coeff[(j<<2)+i] = numcoeff;
                if (numcoeff)
                {
                    cbp4x4 |= (1 << ((j << 2) + i));
                }
            }
        }
    }

    Qq = video->QPc_div_6;
    Rq = video->QPc_mod_6;

    if (currMB->CBP & (3 << 4)) /* chroma DC residual present */
    {
        for (iCbCr = 0; iCbCr < 2; iCbCr++)
        {
            decvid->residual_block(decvid, -1, 4, level, run, &numcoeff);
            block = video->block + 256 + (iCbCr << 3);
            coeffNum = -1;
            for (i = numcoeff - 1; i >= 0; i--)
            {
                coeffNum += run[i] + 1;
                if (coeffNum > 3)
                {
                    return AVCDEC_FAIL;
                }
                block[(coeffNum>>1)*64 + (coeffNum&1)*4] = level[i];
            }
            /* inverse transform on chroma DC */
            /* for P in SP and SI in SI, this function can't be done here,
            must do prediction transform/quant first. */
            if (numcoeff)
            {
                ChromaDCTrans(block, Qq, Rq);
                cbp4x4 |= (iCbCr ? 0xcc0000 : 0x330000);
            }
        }
    }

    if (currMB->CBP & (2 << 4))
    {
        for (block_x = 0; block_x < 4; block_x += 2) /* for iCbCr */
        {
            for (j = 4; j < 6; j++)  /* for each block inside Cb or Cr */
            {
                for (i = block_x; i < block_x + 2; i++)
                {

                    block = video->block + (j << 6) + (i << 2);

                    nC = predict_nnz_chroma(video, i, j);
                    decvid->residual_block(decvid, nC, 15, level, run, &numcoeff);

                    /* convert to raster scan and quantize */
                    /* for P MB in SP slice and SI MB in SI slice,
                       the dequant and transform cannot be done here.
                       It needs the prediction values. */
                    coeffNum = 0;
                    for (k = numcoeff - 1; k >= 0; k--)
                    {
                        coeffNum += run[k] + 1;
                        if (coeffNum > 15)
                        {
                            return AVCDEC_FAIL;
                        }
                        idx = zz_scan[coeffNum];
                        block[idx] = (level[k] * dequant_coefres[Rq][coeffNum]) << Qq;
                    }


                    /* then transform */
                    //              itrans(block); /* transform */
                    currMB->nz_coeff[(j<<2)+i] = numcoeff;    //
                    if (numcoeff)
                    {
                        cbp4x4 |= (1 << ((j << 2) + i));
                    }
                }

            }
        }
    }

    video->cbp4x4 = cbp4x4;

    return AVCDEC_SUCCESS;
}

/* see subclause 7.3.5.3.1 and 9.2 and readCoeff4x4_CAVLC() in JM */
AVCDec_Status residual_block_cavlc(AVCDecObject *decvid, int nC, int maxNumCoeff,
                                   int *level, int *run, int *numcoeff)
{
    int i, j;
    int TrailingOnes, TotalCoeff;
    AVCDecBitstream *stream = decvid->bitstream;
    int suffixLength;
    uint trailing_ones_sign_flag, level_prefix, level_suffix;
    int levelCode, levelSuffixSize, zerosLeft;
    int run_before;


    if (nC >= 0)
    {
        ce_TotalCoeffTrailingOnes(stream, &TrailingOnes, &TotalCoeff, nC);
    }
    else
    {
        ce_TotalCoeffTrailingOnesChromaDC(stream, &TrailingOnes, &TotalCoeff);
    }

    *numcoeff = TotalCoeff;

    /* This part is done quite differently in ReadCoef4x4_CAVLC() */
    if (TotalCoeff == 0)
    {
        return AVCDEC_SUCCESS;
    }

    if (TrailingOnes) /* keep reading the sign of those trailing ones */
    {
        /* instead of reading one bit at a time, read the whole thing at once */
        BitstreamReadBits(stream, TrailingOnes, &trailing_ones_sign_flag);
        trailing_ones_sign_flag <<= 1;
        for (i = 0; i < TrailingOnes; i++)
        {
            level[i] = 1 - ((trailing_ones_sign_flag >> (TrailingOnes - i - 1)) & 2);
        }
    }

    i = TrailingOnes;
    suffixLength = 1;
    if (TotalCoeff > TrailingOnes)
    {
        ce_LevelPrefix(stream, &level_prefix);
        if (TotalCoeff < 11 || TrailingOnes == 3)
        {
            if (level_prefix < 14)
            {
//              levelSuffixSize = 0;
                levelCode = level_prefix;
            }
            else if (level_prefix == 14)
            {
//              levelSuffixSize = 4;
                BitstreamReadBits(stream, 4, &level_suffix);
                levelCode = 14 + level_suffix;
            }
            else /* if (level_prefix == 15) */
            {
//              levelSuffixSize = 12;
                BitstreamReadBits(stream, 12, &level_suffix);
                levelCode = 30 + level_suffix;
            }
        }
        else
        {
            /*              suffixLength = 1; */
            if (level_prefix < 15)
            {
                levelSuffixSize = suffixLength;
            }
            else
            {
                levelSuffixSize = 12;
            }
            BitstreamReadBits(stream, levelSuffixSize, &level_suffix);

            levelCode = (level_prefix << 1) + level_suffix;
        }

        if (TrailingOnes < 3)
        {
            levelCode += 2;
        }

        level[i] = (levelCode + 2) >> 1;
        if (level[i] > 3)
        {
            suffixLength = 2;
        }

        if (levelCode & 1)
        {
            level[i] = -level[i];
        }
        i++;

    }

    for (j = TotalCoeff - i; j > 0 ; j--)
    {
        ce_LevelPrefix(stream, &level_prefix);
        if (level_prefix < 15)
        {
            levelSuffixSize = suffixLength;
        }
        else
        {
            levelSuffixSize = 12;
        }
        BitstreamReadBits(stream, levelSuffixSize, &level_suffix);

        levelCode = (level_prefix << suffixLength) + level_suffix;
        level[i] = (levelCode >> 1) + 1;
        if (level[i] > (3 << (suffixLength - 1)) && suffixLength < 6)
        {
            suffixLength++;
        }
        if (levelCode & 1)
        {
            level[i] = -level[i];
        }
        i++;
    }


    if (TotalCoeff < maxNumCoeff)
    {
        if (nC >= 0)
        {
            ce_TotalZeros(stream, &zerosLeft, TotalCoeff);
        }
        else
        {
            ce_TotalZerosChromaDC(stream, &zerosLeft, TotalCoeff);
        }
    }
    else
    {
        zerosLeft = 0;
    }

    for (i = 0; i < TotalCoeff - 1; i++)
    {
        if (zerosLeft > 0)
        {
            ce_RunBefore(stream, &run_before, zerosLeft);
            run[i] = run_before;
        }
        else
        {
            run[i] = 0;
            zerosLeft = 0; // could be negative under error conditions
        }

        zerosLeft = zerosLeft - run[i];
    }

    if (zerosLeft < 0)
    {
        zerosLeft = 0;
//      return AVCDEC_FAIL;
    }

    run[TotalCoeff-1] = zerosLeft;

    /* leave the inverse zigzag scan part for the caller */


    return AVCDEC_SUCCESS;
}
