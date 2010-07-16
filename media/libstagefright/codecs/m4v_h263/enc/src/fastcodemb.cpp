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
#include "mp4def.h"
#include "mp4lib_int.h"
#include "mp4enc_lib.h"
#include "dct.h"
#include "m4venc_oscl.h"

/* ======================================================================== */
/*  Function : CodeMB_H263( )                                               */
/*  Date     : 8/15/2001                                                    */
/*  Purpose  : Perform residue calc (only zero MV), DCT, H263 Quant/Dequant,*/
/*              IDCT and motion compensation.Modified from FastCodeMB()     */
/*  Input    :                                                              */
/*      video       Video encoder data structure                            */
/*      function    Approximate DCT function, scaling and threshold         */
/*      ncoefblck   Array for last nonzero coeff for speedup in VlcEncode   */
/*      QP      Combined offset from the origin to the current          */
/*                  macroblock  and QP  for current MB.                     */
/*    Output     :                                                          */
/*      video->outputMB     Quantized DCT coefficients.                     */
/*      currVop->yChan,uChan,vChan  Reconstructed pixels                    */
/*                                                                          */
/*  Return   :   PV_STATUS                                                  */
/*  Modified :                                                              */
/*           2/26/01
            -modified threshold based on correlation coeff 0.75 only for mode H.263
            -ncoefblck[] as input,  to keep position of last non-zero coeff*/
/*           8/10/01
            -modified threshold based on correlation coeff 0.5
            -used column threshold to speedup column DCT.
            -used bitmap zigzag to speedup RunLevel().                      */
/* ======================================================================== */

PV_STATUS CodeMB_H263(VideoEncData *video, approxDCT *function, Int QP, Int ncoefblck[])
{
    Int sad, k, CBP, mbnum = video->mbnum;
    Short *output, *dataBlock;
    UChar Mode = video->headerInfo.Mode[mbnum];
    UChar *bitmapcol, *bitmaprow = video->bitmaprow;
    UInt  *bitmapzz ;
    UChar shortHeader = video->vol[video->currLayer]->shortVideoHeader;
    Int dc_scaler = 8;
    Int intra = (Mode == MODE_INTRA || Mode == MODE_INTRA_Q);
    struct QPstruct QuantParam;
    Int dctMode, DctTh1;
    Int ColTh;
    Int(*BlockQuantDequantH263)(Short *, Short *, struct QPstruct *,
                                UChar[], UChar *, UInt *, Int, Int, Int, UChar);
    Int(*BlockQuantDequantH263DC)(Short *, Short *, struct QPstruct *,
                                  UChar *, UInt *, Int, UChar);
    void (*BlockDCT1x1)(Short *, UChar *, UChar *, Int);
    void (*BlockDCT2x2)(Short *, UChar *, UChar *, Int);
    void (*BlockDCT4x4)(Short *, UChar *, UChar *, Int);
    void (*BlockDCT8x8)(Short *, UChar *, UChar *, Int);

    /* motion comp. related var. */
    Vop *currVop = video->currVop;
    VideoEncFrameIO *inputFrame = video->input;
    Int ind_x = video->outputMB->mb_x;
    Int ind_y = video->outputMB->mb_y;
    Int lx = currVop->pitch;
    Int width = currVop->width;
    UChar *rec, *input, *pred;
    Int offset = QP >> 5;  /* QP is combined offset and QP */
    Int offsetc = (offset >> 2) + (ind_x << 2); /* offset for chrom */
    /*****************************/

    OSCL_UNUSED_ARG(function);

    output = video->outputMB->block[0];
    CBP = 0;
    QP = QP & 0x1F;
//  M4VENC_MEMSET(output,0,(sizeof(Short)<<6)*6); /* reset quantized coeff. to zero , 7/24/01*/

    QuantParam.QPx2 = QP << 1;
    QuantParam.QP = QP;
    QuantParam.QPdiv2 = QP >> 1;
    QuantParam.QPx2plus = QuantParam.QPx2 + QuantParam.QPdiv2;
    QuantParam.Addition = QP - 1 + (QP & 0x1);

    if (intra)
    {
        BlockDCT1x1 = &Block1x1DCTIntra;
        BlockDCT2x2 = &Block2x2DCT_AANIntra;
        BlockDCT4x4 = &Block4x4DCT_AANIntra;
        BlockDCT8x8 = &BlockDCT_AANIntra;
        BlockQuantDequantH263 = &BlockQuantDequantH263Intra;
        BlockQuantDequantH263DC = &BlockQuantDequantH263DCIntra;
        if (shortHeader)
        {
            dc_scaler = 8;
        }
        else
        {
            dc_scaler = cal_dc_scalerENC(QP, 1); /* luminance blocks */
        }
        DctTh1 = (Int)(dc_scaler * 3);//*1.829
        ColTh = ColThIntra[QP];
    }
    else
    {
        BlockDCT1x1 = &Block1x1DCTwSub;
        BlockDCT2x2 = &Block2x2DCT_AANwSub;
        BlockDCT4x4 = &Block4x4DCT_AANwSub;
        BlockDCT8x8 = &BlockDCT_AANwSub;

        BlockQuantDequantH263 = &BlockQuantDequantH263Inter;
        BlockQuantDequantH263DC = &BlockQuantDequantH263DCInter;
        ColTh = ColThInter[QP];
        DctTh1 = (Int)(16 * QP);  //9*QP;
    }

    rec = currVop->yChan + offset;
    input = inputFrame->yChan + offset;
    if (lx != width) input -= (ind_y << 9);  /* non-padded offset */

    dataBlock = video->dataBlock;
    pred = video->predictedMB;

    for (k = 0; k < 6; k++)
    {
        CBP <<= 1;
        bitmapcol = video->bitmapcol[k];
        bitmapzz = video->bitmapzz[k];  /*  7/30/01 */
        if (k < 4)
        {
            sad = video->mot[mbnum][k+1].sad;
            if (k&1)
            {
                rec += 8;
                input += 8;
            }
            else if (k == 2)
            {
                dctMode = ((width << 3) - 8);
                input += dctMode;
                dctMode = ((lx << 3) - 8);
                rec += dctMode;
            }
        }
        else
        {
            if (k == 4)
            {
                rec = currVop->uChan + offsetc;
                input = inputFrame->uChan + offsetc;
                if (lx != width) input -= (ind_y << 7);
                lx >>= 1;
                width >>= 1;
                if (intra)
                {
                    sad = getBlockSum(input, width);
                    if (shortHeader)
                        dc_scaler = 8;
                    else
                    {
                        dc_scaler = cal_dc_scalerENC(QP, 2); /* chrominance blocks */
                    }
                    DctTh1 = (Int)(dc_scaler * 3);//*1.829
                }
                else
                    sad = Sad8x8(input, pred, width);
            }
            else
            {
                rec = currVop->vChan + offsetc;
                input = inputFrame->vChan + offsetc;
                if (lx != width) input -= (ind_y << 7);
                if (intra)
                {
                    sad = getBlockSum(input, width);
                }
                else
                    sad = Sad8x8(input, pred, width);
            }
        }

        if (sad < DctTh1 && !(shortHeader && intra)) /* all-zero */
        {                       /* For shortHeader intra block, DC value cannot be zero */
            dctMode = 0;
            CBP |= 0;
            ncoefblck[k] = 0;
        }
        else if (sad < 18*QP/*(QP<<4)*/) /* DC-only */
        {
            dctMode = 1;
            BlockDCT1x1(dataBlock, input, pred, width);

            CBP |= (*BlockQuantDequantH263DC)(dataBlock, output, &QuantParam,
                                              bitmaprow + k, bitmapzz, dc_scaler, shortHeader);
            ncoefblck[k] = 1;
        }
        else
        {

            dataBlock[64] = ColTh;

            if (sad < 22*QP/*(QP<<4)+(QP<<1)*/)  /* 2x2 DCT */
            {
                dctMode = 2;
                BlockDCT2x2(dataBlock, input, pred, width);
                ncoefblck[k] = 6;
            }
            else if (sad < (QP << 5)) /* 4x4 DCT */
            {
                dctMode = 4;
                BlockDCT4x4(dataBlock, input, pred, width);
                ncoefblck[k] = 26;
            }
            else /* Full-DCT */
            {
                dctMode = 8;
                BlockDCT8x8(dataBlock, input, pred, width);
                ncoefblck[k] = 64;
            }

            CBP |= (*BlockQuantDequantH263)(dataBlock, output, &QuantParam,
                                            bitmapcol, bitmaprow + k, bitmapzz, dctMode, k, dc_scaler, shortHeader);
        }
        BlockIDCTMotionComp(dataBlock, bitmapcol, bitmaprow[k], dctMode, rec, pred, (lx << 1) | intra);
        output += 64;
        if (!(k&1))
        {
            pred += 8;
        }
        else
        {
            pred += 120;
        }
    }

    video->headerInfo.CBP[mbnum] = CBP; /*  5/18/2001 */
    return PV_SUCCESS;
}

#ifndef NO_MPEG_QUANT
/* ======================================================================== */
/*  Function : CodeMB_MPEG( )                                               */
/*  Date     : 8/15/2001                                                    */
/*  Purpose  : Perform residue calc (only zero MV), DCT, MPEG Quant/Dequant,*/
/*              IDCT and motion compensation.Modified from FastCodeMB()     */
/*  Input    :                                                              */
/*      video       Video encoder data structure                            */
/*      function    Approximate DCT function, scaling and threshold         */
/*      ncoefblck   Array for last nonzero coeff for speedup in VlcEncode   */
/*      QP      Combined offset from the origin to the current          */
/*                  macroblock  and QP  for current MB.                     */
/*    Output     :                                                          */
/*      video->outputMB     Quantized DCT coefficients.                     */
/*      currVop->yChan,uChan,vChan  Reconstructed pixels                    */
/*                                                                          */
/*  Return   :   PV_STATUS                                                  */
/*  Modified :                                                              */
/*           2/26/01
            -modified threshold based on correlation coeff 0.75 only for mode H.263
            -ncoefblck[] as input, keep position of last non-zero coeff*/
/*           8/10/01
            -modified threshold based on correlation coeff 0.5
            -used column threshold to speedup column DCT.
            -used bitmap zigzag to speedup RunLevel().                      */
/* ======================================================================== */

PV_STATUS CodeMB_MPEG(VideoEncData *video, approxDCT *function, Int QP, Int ncoefblck[])
{
    Int sad, k, CBP, mbnum = video->mbnum;
    Short *output, *dataBlock;
    UChar Mode = video->headerInfo.Mode[mbnum];
    UChar *bitmapcol, *bitmaprow = video->bitmaprow;
    UInt  *bitmapzz ;
    Int dc_scaler = 8;
    Vol *currVol = video->vol[video->currLayer];
    Int intra = (Mode == MODE_INTRA || Mode == MODE_INTRA_Q);
    Int *qmat;
    Int dctMode, DctTh1, DctTh2, DctTh3, DctTh4;
    Int ColTh;

    Int(*BlockQuantDequantMPEG)(Short *, Short *, Int, Int *,
                                UChar [], UChar *, UInt *, Int,  Int, Int);
    Int(*BlockQuantDequantMPEGDC)(Short *, Short *, Int, Int *,
                                  UChar [], UChar *, UInt *, Int);

    void (*BlockDCT1x1)(Short *, UChar *, UChar *, Int);
    void (*BlockDCT2x2)(Short *, UChar *, UChar *, Int);
    void (*BlockDCT4x4)(Short *, UChar *, UChar *, Int);
    void (*BlockDCT8x8)(Short *, UChar *, UChar *, Int);

    /* motion comp. related var. */
    Vop *currVop = video->currVop;
    VideoEncFrameIO *inputFrame = video->input;
    Int ind_x = video->outputMB->mb_x;
    Int ind_y = video->outputMB->mb_y;
    Int lx = currVop->pitch;
    Int width = currVop->width;
    UChar *rec, *input, *pred;
    Int offset = QP >> 5;
    Int offsetc = (offset >> 2) + (ind_x << 2); /* offset for chrom */
    /*****************************/

    OSCL_UNUSED_ARG(function);

    output = video->outputMB->block[0];
    CBP = 0;
    QP = QP & 0x1F;
//  M4VENC_MEMSET(output,0,(sizeof(Short)<<6)*6); /* reset quantized coeff. to zero ,  7/24/01*/

    if (intra)
    {
        BlockDCT1x1 = &Block1x1DCTIntra;
        BlockDCT2x2 = &Block2x2DCT_AANIntra;
        BlockDCT4x4 = &Block4x4DCT_AANIntra;
        BlockDCT8x8 = &BlockDCT_AANIntra;

        BlockQuantDequantMPEG = &BlockQuantDequantMPEGIntra;
        BlockQuantDequantMPEGDC = &BlockQuantDequantMPEGDCIntra;
        dc_scaler = cal_dc_scalerENC(QP, 1); /* luminance blocks */
        qmat = currVol->iqmat;
        DctTh1 = (Int)(3 * dc_scaler);//2*dc_scaler);
        DctTh2 = (Int)((1.25 * QP - 1) * qmat[1] * 0.45);//0.567);//0.567);
        DctTh3 = (Int)((1.25 * QP - 1) * qmat[2] * 0.55);//1.162); /*  8/2/2001 */
        DctTh4 = (Int)((1.25 * QP - 1) * qmat[32] * 0.8);//1.7583);//0.7942);
        ColTh = ColThIntra[QP];
    }
    else
    {
        BlockDCT1x1 = &Block1x1DCTwSub;
        BlockDCT2x2 = &Block2x2DCT_AANwSub;
        BlockDCT4x4 = &Block4x4DCT_AANwSub;
        BlockDCT8x8 = &BlockDCT_AANwSub;

        BlockQuantDequantMPEG = &BlockQuantDequantMPEGInter;
        BlockQuantDequantMPEGDC = &BlockQuantDequantMPEGDCInter;
        qmat = currVol->niqmat;
        DctTh1 = (Int)(((QP << 1) - 0.5) * qmat[0] * 0.4);//0.2286);//0.3062);
        DctTh2 = (Int)(((QP << 1) - 0.5) * qmat[1] * 0.45);//0.567);//0.4);
        DctTh3 = (Int)(((QP << 1) - 0.5) * qmat[2] * 0.55);//1.162); /*  8/2/2001 */
        DctTh4 = (Int)(((QP << 1) - 0.5) * qmat[32] * 0.8);//1.7583);//0.7942);
        ColTh = ColThInter[QP];
    }// get qmat, DctTh1, DctTh2, DctTh3

    rec = currVop->yChan + offset;
    input = inputFrame->yChan + offset;
    if (lx != width) input -= (ind_y << 9);  /* non-padded offset */

    dataBlock = video->dataBlock;
    pred = video->predictedMB;

    for (k = 0; k < 6; k++)
    {
        CBP <<= 1;
        bitmapcol = video->bitmapcol[k];
        bitmapzz = video->bitmapzz[k];  /*  8/2/01 */
        if (k < 4)
        {//Y block
            sad = video->mot[mbnum][k+1].sad;
            if (k&1)
            {
                rec += 8;
                input += 8;
            }
            else if (k == 2)
            {
                dctMode = ((width << 3) - 8);
                input += dctMode;
                dctMode = ((lx << 3) - 8);
                rec += dctMode;
            }
        }
        else
        {// U, V block
            if (k == 4)
            {
                rec = currVop->uChan + offsetc;
                input = inputFrame->uChan + offsetc;
                if (lx != width) input -= (ind_y << 7);
                lx >>= 1;
                width >>= 1;
                if (intra)
                {
                    dc_scaler = cal_dc_scalerENC(QP, 2); /* luminance blocks */
                    DctTh1 = dc_scaler * 3;
                    sad = getBlockSum(input, width);
                }
                else
                    sad = Sad8x8(input, pred, width);
            }
            else
            {
                rec = currVop->vChan + offsetc;
                input = inputFrame->vChan + offsetc;
                if (lx != width) input -= (ind_y << 7);
                if (intra)
                    sad = getBlockSum(input, width);
                else
                    sad = Sad8x8(input, pred, width);
            }
        }

        if (sad < DctTh1) /* all-zero */
        {
            dctMode = 0;
            CBP |= 0;
            ncoefblck[k] = 0;
        }
        else if (sad < DctTh2) /* DC-only */
        {
            dctMode = 1;
            BlockDCT1x1(dataBlock, input, pred, width);

            CBP |= (*BlockQuantDequantMPEGDC)(dataBlock, output, QP, qmat,
                                              bitmapcol, bitmaprow + k, bitmapzz, dc_scaler);
            ncoefblck[k] = 1;
        }
        else
        {
            dataBlock[64] = ColTh;

            if (sad < DctTh3) /* 2x2-DCT */
            {
                dctMode = 2;
                BlockDCT2x2(dataBlock, input, pred, width);
                ncoefblck[k] = 6;
            }
            else if (sad < DctTh4) /* 4x4 DCT */
            {
                dctMode = 4;
                BlockDCT4x4(dataBlock, input, pred, width);
                ncoefblck[k] = 26;
            }
            else /* full-DCT */
            {
                dctMode = 8;
                BlockDCT8x8(dataBlock, input, pred, width);
                ncoefblck[k] = 64;
            }

            CBP |= (*BlockQuantDequantMPEG)(dataBlock, output, QP, qmat,
                                            bitmapcol, bitmaprow + k, bitmapzz, dctMode, k, dc_scaler); //
        }
        dctMode = 8; /* for mismatch handle */
        BlockIDCTMotionComp(dataBlock, bitmapcol, bitmaprow[k], dctMode, rec, pred, (lx << 1) | (intra));

        output += 64;
        if (!(k&1))
        {
            pred += 8;
        }
        else
        {
            pred += 120;
        }
    }

    video->headerInfo.CBP[mbnum] = CBP; /*  5/18/2001 */
    return PV_SUCCESS;
}

#endif

/* ======================================================================== */
/*  Function : getBlockSAV( )                                               */
/*  Date     : 8/10/2000                                                    */
/*  Purpose  : Get SAV for one block                                        */
/*  In/out   : block[64] contain one block data                             */
/*  Return   :                                                              */
/*  Modified :                                                              */
/* ======================================================================== */
/* can be written in MMX or SSE,  2/22/2001 */
Int getBlockSAV(Short block[])
{
    Int i, val, sav = 0;

    i = 8;
    while (i--)
    {
        val = *block++;
        if (val > 0)    sav += val;
        else        sav -= val;
        val = *block++;
        if (val > 0)    sav += val;
        else        sav -= val;
        val = *block++;
        if (val > 0)    sav += val;
        else        sav -= val;
        val = *block++;
        if (val > 0)    sav += val;
        else        sav -= val;
        val = *block++;
        if (val > 0)    sav += val;
        else        sav -= val;
        val = *block++;
        if (val > 0)    sav += val;
        else        sav -= val;
        val = *block++;
        if (val > 0)    sav += val;
        else        sav -= val;
        val = *block++;
        if (val > 0)    sav += val;
        else        sav -= val;
    }

    return sav;

}

/* ======================================================================== */
/*  Function : Sad8x8( )                                                    */
/*  Date     : 8/10/2000                                                    */
/*  Purpose  : Find SAD between prev block and current block                */
/*  In/out   : Previous and current frame block pointers, and frame width   */
/*  Return   :                                                              */
/*  Modified :                                                              */
/*      8/15/01,  - do 4 pixel at a time    assuming 32 bit register        */
/* ======================================================================== */
Int Sad8x8(UChar *cur, UChar *prev, Int width)
{
    UChar *end = cur + (width << 3);
    Int sad = 0;
    Int *curInt = (Int*) cur;
    Int *prevInt = (Int*) prev;
    Int cur1, cur2, prev1, prev2;
    UInt mask, sgn_msk = 0x80808080;
    Int  sum2 = 0, sum4 = 0;
    Int  tmp;
    do
    {
        mask    = ~(0xFF00);
        cur1    = curInt[1];        /* load cur[4..7] */
        cur2    = curInt[0];
        curInt += (width >> 2);     /* load cur[0..3] and +=lx */
        prev1   = prevInt[1];
        prev2   = prevInt[0];
        prevInt += 4;

        tmp     = prev2 ^ cur2;
        cur2    = prev2 - cur2;
        tmp     = tmp ^ cur2;       /* (^)^(-) last bit is one if carry */
        tmp     = sgn_msk & ((UInt)tmp >> 1); /* check the sign of each byte */
        if (cur2 < 0)   tmp = tmp | 0x80000000; /* corcurt sign of first byte */
        tmp     = (tmp << 8) - tmp;     /* carry borrowed bytes are marked with 0x1FE */
        cur2    = cur2 + (tmp >> 7);     /* negative bytes is added with 0xFF, -1 */
        cur2    = cur2 ^(tmp >> 7); /* take absolute by inverting bits (EOR) */

        tmp     = prev1 ^ cur1;
        cur1    = prev1 - cur1;
        tmp     = tmp ^ cur1;       /* (^)^(-) last bit is one if carry */
        tmp     = sgn_msk & ((UInt)tmp >> 1); /* check the sign of each byte */
        if (cur1 < 0)   tmp = tmp | 0x80000000; /* corcurt sign of first byte */
        tmp     = (tmp << 8) - tmp;     /* carry borrowed bytes are marked with 0x1FE */
        cur1    = cur1 + (tmp >> 7);     /* negative bytes is added with 0xFF, -1 */
        cur1    = cur1 ^(tmp >> 7); /* take absolute by inverting bits (EOR) */

        sum4    = sum4 + cur1;
        cur1    = cur1 & (mask << 8);   /* mask first and third bytes */
        sum2    = sum2 + ((UInt)cur1 >> 8);
        sum4    = sum4 + cur2;
        cur2    = cur2 & (mask << 8);   /* mask first and third bytes */
        sum2    = sum2 + ((UInt)cur2 >> 8);
    }
    while ((UInt)curInt < (UInt)end);

    cur1 = sum4 - (sum2 << 8);  /* get even-sum */
    cur1 = cur1 + sum2;         /* add 16 bit even-sum and odd-sum*/
    cur1 = cur1 + (cur1 << 16); /* add upper and lower 16 bit sum */
    sad  = ((UInt)cur1 >> 16);  /* take upper 16 bit */
    return sad;
}

/* ======================================================================== */
/*  Function : getBlockSum( )                                               */
/*  Date     : 8/10/2000                                                    */
/*  Purpose  : Find summation of value within a block.                      */
/*  In/out   : Pointer to current block in a frame and frame width          */
/*  Return   :                                                              */
/*  Modified :                                                              */
/*          8/15/01,  - SIMD 4 pixels at a time                         */
/* ======================================================================== */

Int getBlockSum(UChar *cur, Int width)
{
    Int sad = 0, sum4 = 0, sum2 = 0;
    UChar *end = cur + (width << 3);
    Int *curInt = (Int*)cur;
    UInt mask   = ~(0xFF00);
    Int load1, load2;

    do
    {
        load1 = curInt[1];
        load2 = curInt[0];
        curInt += (width >> 2);
        sum4 += load1;
        load1 = load1 & (mask << 8); /* even bytes */
        sum2 += ((UInt)load1 >> 8); /* sum even bytes, 16 bit */
        sum4 += load2;
        load2 = load2 & (mask << 8); /* even bytes */
        sum2 += ((UInt)load2 >> 8); /* sum even bytes, 16 bit */
    }
    while ((UInt)curInt < (UInt)end);
    load1 = sum4 - (sum2 << 8);     /* get even-sum */
    load1 = load1 + sum2;           /* add 16 bit even-sum and odd-sum*/
    load1 = load1 + (load1 << 16);  /* add upper and lower 16 bit sum */
    sad  = ((UInt)load1 >> 16); /* take upper 16 bit */

    return sad;
}

