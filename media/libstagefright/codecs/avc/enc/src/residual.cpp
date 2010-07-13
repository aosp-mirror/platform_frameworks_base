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
#include "avcenc_lib.h"

AVCEnc_Status EncodeIntraPCM(AVCEncObject *encvid)
{
    AVCEnc_Status status = AVCENC_SUCCESS;
    AVCCommonObj *video = encvid->common;
    AVCFrameIO  *currInput = encvid->currInput;
    AVCEncBitstream *stream = encvid->bitstream;
    int x_position = (video->mb_x << 4);
    int y_position = (video->mb_y << 4);
    int orgPitch = currInput->pitch;
    int offset1 = y_position * orgPitch + x_position;
    int i, j;
    int offset;
    uint8 *pDst, *pSrc;
    uint code;

    ue_v(stream, 25);

    i = stream->bit_left & 0x7;
    if (i) /* not byte-aligned */
    {
        BitstreamWriteBits(stream, 0, i);
    }

    pSrc = currInput->YCbCr[0] + offset1;
    pDst = video->currPic->Sl + offset1;
    offset = video->PicWidthInSamplesL - 16;

    /* at this point bitstream is byte-aligned */
    j = 16;
    while (j > 0)
    {
#if (WORD_SIZE==32)
        for (i = 0; i < 4; i++)
        {
            code = *((uint*)pSrc);
            pSrc += 4;
            *((uint*)pDst) = code;
            pDst += 4;
            status = BitstreamWriteBits(stream, 32, code);
        }
#else
        for (i = 0; i < 8; i++)
        {
            code = *((uint*)pSrc);
            pSrc += 2;
            *((uint*)pDst) = code;
            pDst += 2;
            status = BitstreamWriteBits(stream, 16, code);
        }
#endif
        pDst += offset;
        pSrc += offset;
        j--;
    }
    if (status != AVCENC_SUCCESS)  /* check only once per line */
        return status;

    pDst = video->currPic->Scb + ((offset1 + x_position) >> 2);
    pSrc = currInput->YCbCr[1] + ((offset1 + x_position) >> 2);
    offset >>= 1;

    j = 8;
    while (j > 0)
    {
#if (WORD_SIZE==32)
        for (i = 0; i < 2; i++)
        {
            code = *((uint*)pSrc);
            pSrc += 4;
            *((uint*)pDst) = code;
            pDst += 4;
            status = BitstreamWriteBits(stream, 32, code);
        }
#else
        for (i = 0; i < 4; i++)
        {
            code = *((uint*)pSrc);
            pSrc += 2;
            *((uint*)pDst) = code;
            pDst += 2;
            status = BitstreamWriteBits(stream, 16, code);
        }
#endif
        pDst += offset;
        pSrc += offset;
        j--;
    }

    if (status != AVCENC_SUCCESS)  /* check only once per line */
        return status;

    pDst = video->currPic->Scr + ((offset1 + x_position) >> 2);
    pSrc = currInput->YCbCr[2] + ((offset1 + x_position) >> 2);

    j = 8;
    while (j > 0)
    {
#if (WORD_SIZE==32)
        for (i = 0; i < 2; i++)
        {
            code = *((uint*)pSrc);
            pSrc += 4;
            *((uint*)pDst) = code;
            pDst += 4;
            status = BitstreamWriteBits(stream, 32, code);
        }
#else
        for (i = 0; i < 4; i++)
        {
            code = *((uint*)pSrc);
            pSrc += 2;
            *((uint*)pDst) = code;
            pDst += 2;
            status = BitstreamWriteBits(stream, 16, code);
        }
#endif
        pDst += offset;
        pSrc += offset;
        j--;
    }

    return status;
}


AVCEnc_Status enc_residual_block(AVCEncObject *encvid, AVCResidualType type, int cindx, AVCMacroblock *currMB)
{
    AVCEnc_Status status = AVCENC_SUCCESS;
    AVCCommonObj *video = encvid->common;
    int i, maxNumCoeff, nC;
    int cdc = 0, cac = 0;
    int TrailingOnes;
    AVCEncBitstream *stream = encvid->bitstream;
    uint trailing_ones_sign_flag;
    int zerosLeft;
    int *level, *run;
    int TotalCoeff;
    const static int incVlc[] = {0, 3, 6, 12, 24, 48, 32768};  // maximum vlc = 6
    int escape, numPrefix, sufmask, suffix, shift, sign, value, absvalue, vlcnum, level_two_or_higher;
    int bindx = blkIdx2blkXY[cindx>>2][cindx&3] ; // raster scan index

    switch (type)
    {
        case AVC_Luma:
            maxNumCoeff = 16;
            level = encvid->level[cindx];
            run = encvid->run[cindx];
            TotalCoeff = currMB->nz_coeff[bindx];
            break;
        case AVC_Intra16DC:
            maxNumCoeff = 16;
            level = encvid->leveldc;
            run = encvid->rundc;
            TotalCoeff = cindx; /* special case */
            bindx = 0;
            cindx = 0;
            break;
        case AVC_Intra16AC:
            maxNumCoeff = 15;
            level = encvid->level[cindx];
            run = encvid->run[cindx];
            TotalCoeff = currMB->nz_coeff[bindx];
            break;
        case AVC_ChromaDC:  /* how to differentiate Cb from Cr */
            maxNumCoeff = 4;
            cdc = 1;
            if (cindx >= 8)
            {
                level = encvid->levelcdc + 4;
                run = encvid->runcdc + 4;
                TotalCoeff = cindx - 8;  /* special case */
            }
            else
            {
                level = encvid->levelcdc;
                run = encvid->runcdc;
                TotalCoeff = cindx;  /* special case */
            }
            break;
        case AVC_ChromaAC:
            maxNumCoeff = 15;
            cac = 1;
            level = encvid->level[cindx];
            run = encvid->run[cindx];
            cindx -= 16;
            bindx = 16 + blkIdx2blkXY[cindx>>2][cindx&3];
            cindx += 16;
            TotalCoeff = currMB->nz_coeff[bindx];
            break;
        default:
            return AVCENC_FAIL;
    }


    /* find TrailingOnes */
    TrailingOnes = 0;
    zerosLeft = 0;
    i = TotalCoeff - 1;
    nC = 1;
    while (i >= 0)
    {
        zerosLeft += run[i];
        if (nC && (level[i] == 1 || level[i] == -1))
        {
            TrailingOnes++;
        }
        else
        {
            nC = 0;
        }
        i--;
    }
    if (TrailingOnes > 3)
    {
        TrailingOnes = 3; /* clip it */
    }

    if (!cdc)
    {
        if (!cac)  /* not chroma */
        {
            nC = predict_nnz(video, bindx & 3, bindx >> 2);
        }
        else /* chroma ac but not chroma dc */
        {
            nC = predict_nnz_chroma(video, bindx & 3, bindx >> 2);
        }

        status = ce_TotalCoeffTrailingOnes(stream, TrailingOnes, TotalCoeff, nC);
    }
    else
    {
        nC = -1; /* Chroma DC level */
        status = ce_TotalCoeffTrailingOnesChromaDC(stream, TrailingOnes, TotalCoeff);
    }

    /* This part is done quite differently in ReadCoef4x4_CAVLC() */
    if (TotalCoeff > 0)
    {

        i = TotalCoeff - 1;

        if (TrailingOnes) /* keep reading the sign of those trailing ones */
        {
            nC = TrailingOnes;
            trailing_ones_sign_flag = 0;
            while (nC)
            {
                trailing_ones_sign_flag <<= 1;
                trailing_ones_sign_flag |= ((uint32)level[i--] >> 31); /* 0 or positive, 1 for negative */
                nC--;
            }

            /* instead of writing one bit at a time, read the whole thing at once */
            status = BitstreamWriteBits(stream, TrailingOnes, trailing_ones_sign_flag);
        }

        level_two_or_higher = 1;
        if (TotalCoeff > 3 && TrailingOnes == 3)
        {
            level_two_or_higher = 0;
        }

        if (TotalCoeff > 10 && TrailingOnes < 3)
        {
            vlcnum = 1;
        }
        else
        {
            vlcnum = 0;
        }

        /* then do this TotalCoeff-TrailingOnes times */
        for (i = TotalCoeff - TrailingOnes - 1; i >= 0; i--)
        {
            value = level[i];
            absvalue = (value >= 0) ? value : -value;

            if (level_two_or_higher)
            {
                if (value > 0) value--;
                else    value++;
                level_two_or_higher = 0;
            }

            if (value >= 0)
            {
                sign = 0;
            }
            else
            {
                sign = 1;
                value = -value;
            }

            if (vlcnum == 0) // VLC1
            {
                if (value < 8)
                {
                    status = BitstreamWriteBits(stream, value * 2 + sign - 1, 1);
                }
                else if (value < 8 + 8)
                {
                    status = BitstreamWriteBits(stream, 14 + 1 + 4, (1 << 4) | ((value - 8) << 1) | sign);
                }
                else
                {
                    status = BitstreamWriteBits(stream, 14 + 2 + 12, (1 << 12) | ((value - 16) << 1) | sign) ;
                }
            }
            else  // VLCN
            {
                shift = vlcnum - 1;
                escape = (15 << shift) + 1;
                numPrefix = (value - 1) >> shift;
                sufmask = ~((0xffffffff) << shift);
                suffix = (value - 1) & sufmask;
                if (value < escape)
                {
                    status = BitstreamWriteBits(stream, numPrefix + vlcnum + 1, (1 << (shift + 1)) | (suffix << 1) | sign);
                }
                else
                {
                    status = BitstreamWriteBits(stream, 28, (1 << 12) | ((value - escape) << 1) | sign);
                }

            }

            if (absvalue > incVlc[vlcnum])
                vlcnum++;

            if (i == TotalCoeff - TrailingOnes - 1 && absvalue > 3)
                vlcnum = 2;
        }

        if (status != AVCENC_SUCCESS)  /* occasionally check the bitstream */
        {
            return status;
        }
        if (TotalCoeff < maxNumCoeff)
        {
            if (!cdc)
            {
                ce_TotalZeros(stream, zerosLeft, TotalCoeff);
            }
            else
            {
                ce_TotalZerosChromaDC(stream, zerosLeft, TotalCoeff);
            }
        }
        else
        {
            zerosLeft = 0;
        }

        i = TotalCoeff - 1;
        while (i > 0) /* don't do the last one */
        {
            if (zerosLeft > 0)
            {
                ce_RunBefore(stream, run[i], zerosLeft);
            }

            zerosLeft = zerosLeft - run[i];
            i--;
        }
    }

    return status;
}
