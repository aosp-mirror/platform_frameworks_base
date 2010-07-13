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

/* subtract with the prediction and do transformation */
void trans(uint8 *cur, int pitch, uint8 *predBlock, int16 *dataBlock)
{
    int16 *ptr = dataBlock;
    int r0, r1, r2, r3, j;
    int curpitch = (uint)pitch >> 16;
    int predpitch = (pitch & 0xFFFF);

    /* horizontal */
    j = 4;
    while (j > 0)
    {
        /* calculate the residue first */
        r0 = cur[0] - predBlock[0];
        r1 = cur[1] - predBlock[1];
        r2 = cur[2] - predBlock[2];
        r3 = cur[3] - predBlock[3];

        r0 += r3;           //ptr[0] + ptr[3];
        r3 = r0 - (r3 << 1);    //ptr[0] - ptr[3];
        r1 += r2;           //ptr[1] + ptr[2];
        r2 = r1 - (r2 << 1);    //ptr[1] - ptr[2];

        ptr[0] = r0 + r1;
        ptr[2] = r0 - r1;
        ptr[1] = (r3 << 1) + r2;
        ptr[3] = r3 - (r2 << 1);

        ptr += 16;
        predBlock += predpitch;
        cur += curpitch;
        j--;
    }
    /* vertical */
    ptr = dataBlock;
    j = 4;
    while (j > 0)
    {
        r0 = ptr[0] + ptr[48];
        r3 = ptr[0] - ptr[48];
        r1 = ptr[16] + ptr[32];
        r2 = ptr[16] - ptr[32];

        ptr[0] = r0 + r1;
        ptr[32] = r0 - r1;
        ptr[16] = (r3 << 1) + r2;
        ptr[48] = r3 - (r2 << 1);

        ptr++;
        j--;
    }

    return ;
}


/* do residue transform quant invquant, invtrans and write output out */
int dct_luma(AVCEncObject *encvid, int blkidx, uint8 *cur, uint8 *org, int *coef_cost)
{
    AVCCommonObj *video = encvid->common;
    int org_pitch = encvid->currInput->pitch;
    int pitch = video->currPic->pitch;
    int16 *coef = video->block;
    uint8 *pred = video->pred_block; // size 16 for a 4x4 block
    int pred_pitch = video->pred_pitch;
    int r0, r1, r2, r3, j, k, idx;
    int *level, *run;
    int Qq, Rq, q_bits, qp_const, quant;
    int data, lev, zero_run;
    int numcoeff;

    coef += ((blkidx & 0x3) << 2) + ((blkidx >> 2) << 6); /* point to the 4x4 block */

    /* first take a 4x4 transform */
    /* horizontal */
    j = 4;
    while (j > 0)
    {
        /* calculate the residue first */
        r0 = org[0] - pred[0];   /* OPTIMIZEABLE */
        r1 = org[1] - pred[1];
        r2 = org[2] - pred[2];
        r3 = org[3] - pred[3];

        r0 += r3;           //ptr[0] + ptr[3];
        r3 = r0 - (r3 << 1);    //ptr[0] - ptr[3];
        r1 += r2;           //ptr[1] + ptr[2];
        r2 = r1 - (r2 << 1);    //ptr[1] - ptr[2];

        coef[0] = r0 + r1;
        coef[2] = r0 - r1;
        coef[1] = (r3 << 1) + r2;
        coef[3] = r3 - (r2 << 1);

        coef += 16;
        org += org_pitch;
        pred += pred_pitch;
        j--;
    }
    /* vertical */
    coef -= 64;
    pred -= (pred_pitch << 2);
    j = 4;
    while (j > 0)   /* OPTIMIZABLE */
    {
        r0 = coef[0] + coef[48];
        r3 = coef[0] - coef[48];
        r1 = coef[16] + coef[32];
        r2 = coef[16] - coef[32];

        coef[0] = r0 + r1;
        coef[32] = r0 - r1;
        coef[16] = (r3 << 1) + r2;
        coef[48] = r3 - (r2 << 1);

        coef++;
        j--;
    }

    coef -= 4;

    /* quant */
    level = encvid->level[ras2dec[blkidx]];
    run = encvid->run[ras2dec[blkidx]];

    Rq = video->QPy_mod_6;
    Qq = video->QPy_div_6;
    qp_const = encvid->qp_const;
    q_bits = 15 + Qq;

    zero_run = 0;
    numcoeff = 0;
    for (k = 0; k < 16; k++)
    {
        idx = ZZ_SCAN_BLOCK[k]; /* map back to raster scan order */
        data = coef[idx];
        quant = quant_coef[Rq][k];
        if (data > 0)
        {
            lev = data * quant + qp_const;
        }
        else
        {
            lev = -data * quant + qp_const;
        }
        lev >>= q_bits;
        if (lev)
        {
            *coef_cost += ((lev > 1) ? MAX_VALUE : COEFF_COST[DISABLE_THRESHOLDING][zero_run]);

            /* dequant */
            quant = dequant_coefres[Rq][k];
            if (data > 0)
            {
                level[numcoeff] = lev;
                coef[idx] = (lev * quant) << Qq;
            }
            else
            {
                level[numcoeff] = -lev;
                coef[idx] = (-lev * quant) << Qq;
            }
            run[numcoeff++] = zero_run;
            zero_run = 0;
        }
        else
        {
            zero_run++;
            coef[idx] = 0;
        }
    }

    if (video->currMB->mb_intra) // only do inverse transform with intra block
    {
        if (numcoeff) /* then do inverse transform */
        {
            for (j = 4; j > 0; j--) /* horizontal */
            {
                r0 = coef[0] + coef[2];
                r1 = coef[0] - coef[2];
                r2 = (coef[1] >> 1) - coef[3];
                r3 = coef[1] + (coef[3] >> 1);

                coef[0] = r0 + r3;
                coef[1] = r1 + r2;
                coef[2] = r1 - r2;
                coef[3] = r0 - r3;

                coef += 16;
            }

            coef -= 64;
            for (j = 4; j > 0; j--) /* vertical, has to be done after horizontal */
            {
                r0 = coef[0] + coef[32];
                r1 = coef[0] - coef[32];
                r2 = (coef[16] >> 1) - coef[48];
                r3 = coef[16] + (coef[48] >> 1);
                r0 += r3;
                r3 = (r0 - (r3 << 1)); /* r0-r3 */
                r1 += r2;
                r2 = (r1 - (r2 << 1)); /* r1-r2 */
                r0 += 32;
                r1 += 32;
                r2 += 32;
                r3 += 32;

                r0 = pred[0] + (r0 >> 6);
                if ((uint)r0 > 0xFF)   r0 = 0xFF & (~(r0 >> 31));  /* clip */
                r1 = *(pred += pred_pitch) + (r1 >> 6);
                if ((uint)r1 > 0xFF)   r1 = 0xFF & (~(r1 >> 31));  /* clip */
                r2 = *(pred += pred_pitch) + (r2 >> 6);
                if ((uint)r2 > 0xFF)   r2 = 0xFF & (~(r2 >> 31));  /* clip */
                r3 = pred[pred_pitch] + (r3 >> 6);
                if ((uint)r3 > 0xFF)   r3 = 0xFF & (~(r3 >> 31));  /* clip */

                *cur = r0;
                *(cur += pitch) = r1;
                *(cur += pitch) = r2;
                cur[pitch] = r3;
                cur -= (pitch << 1);
                cur++;
                pred -= (pred_pitch << 1);
                pred++;
                coef++;
            }
        }
        else  // copy from pred to cur
        {
            *((uint32*)cur) = *((uint32*)pred);
            *((uint32*)(cur += pitch)) = *((uint32*)(pred += pred_pitch));
            *((uint32*)(cur += pitch)) = *((uint32*)(pred += pred_pitch));
            *((uint32*)(cur += pitch)) = *((uint32*)(pred += pred_pitch));
        }
    }

    return numcoeff;
}


void MBInterIdct(AVCCommonObj *video, uint8 *curL, AVCMacroblock *currMB, int picPitch)
{
    int16 *coef, *coef8 = video->block;
    uint8 *cur;  // the same as curL
    int b8, b4;
    int r0, r1, r2, r3, j, blkidx;

    for (b8 = 0; b8 < 4; b8++)
    {
        cur = curL;
        coef = coef8;

        if (currMB->CBP&(1 << b8))
        {
            for (b4 = 0; b4 < 4; b4++)
            {
                blkidx = blkIdx2blkXY[b8][b4];
                /* do IDCT */
                if (currMB->nz_coeff[blkidx])
                {
                    for (j = 4; j > 0; j--) /* horizontal */
                    {
                        r0 = coef[0] + coef[2];
                        r1 = coef[0] - coef[2];
                        r2 = (coef[1] >> 1) - coef[3];
                        r3 = coef[1] + (coef[3] >> 1);

                        coef[0] = r0 + r3;
                        coef[1] = r1 + r2;
                        coef[2] = r1 - r2;
                        coef[3] = r0 - r3;

                        coef += 16;
                    }

                    coef -= 64;
                    for (j = 4; j > 0; j--) /* vertical, has to be done after horizontal */
                    {
                        r0 = coef[0] + coef[32];
                        r1 = coef[0] - coef[32];
                        r2 = (coef[16] >> 1) - coef[48];
                        r3 = coef[16] + (coef[48] >> 1);
                        r0 += r3;
                        r3 = (r0 - (r3 << 1)); /* r0-r3 */
                        r1 += r2;
                        r2 = (r1 - (r2 << 1)); /* r1-r2 */
                        r0 += 32;
                        r1 += 32;
                        r2 += 32;
                        r3 += 32;

                        r0 = cur[0] + (r0 >> 6);
                        if ((uint)r0 > 0xFF)   r0 = 0xFF & (~(r0 >> 31));  /* clip */
                        *cur = r0;
                        r1 = *(cur += picPitch) + (r1 >> 6);
                        if ((uint)r1 > 0xFF)   r1 = 0xFF & (~(r1 >> 31));  /* clip */
                        *cur = r1;
                        r2 = *(cur += picPitch) + (r2 >> 6);
                        if ((uint)r2 > 0xFF)   r2 = 0xFF & (~(r2 >> 31));  /* clip */
                        *cur = r2;
                        r3 = cur[picPitch] + (r3 >> 6);
                        if ((uint)r3 > 0xFF)   r3 = 0xFF & (~(r3 >> 31));  /* clip */
                        cur[picPitch] = r3;

                        cur -= (picPitch << 1);
                        cur++;
                        coef++;
                    }
                    cur -= 4;
                    coef -= 4;
                }
                if (b4&1)
                {
                    cur += ((picPitch << 2) - 4);
                    coef += 60;
                }
                else
                {
                    cur += 4;
                    coef += 4;
                }
            }
        }

        if (b8&1)
        {
            curL += ((picPitch << 3) - 8);
            coef8 += 120;
        }
        else
        {
            curL += 8;
            coef8 += 8;
        }
    }

    return ;
}

/* performa dct, quant, iquant, idct for the entire MB */
void dct_luma_16x16(AVCEncObject *encvid, uint8 *curL, uint8 *orgL)
{
    AVCCommonObj *video = encvid->common;
    int pitch = video->currPic->pitch;
    int org_pitch = encvid->currInput->pitch;
    AVCMacroblock *currMB = video->currMB;
    int16 *coef = video->block;
    uint8 *pred = encvid->pred_i16[currMB->i16Mode];
    int blk_x, blk_y, j, k, idx, b8, b4;
    int r0, r1, r2, r3, m0, m1, m2 , m3;
    int data, lev;
    int *level, *run, zero_run, ncoeff;
    int Rq, Qq, quant, q_bits, qp_const;
    int offset_cur[4], offset_pred[4], offset;

    /* horizontal */
    for (j = 16; j > 0; j--)
    {
        for (blk_x = 4; blk_x > 0; blk_x--)
        {
            /* calculate the residue first */
            r0 = *orgL++ - *pred++;
            r1 = *orgL++ - *pred++;
            r2 = *orgL++ - *pred++;
            r3 = *orgL++ - *pred++;

            r0 += r3;           //ptr[0] + ptr[3];
            r3 = r0 - (r3 << 1);    //ptr[0] - ptr[3];
            r1 += r2;           //ptr[1] + ptr[2];
            r2 = r1 - (r2 << 1);    //ptr[1] - ptr[2];

            *coef++ = r0 + r1;
            *coef++ = (r3 << 1) + r2;
            *coef++ = r0 - r1;
            *coef++ = r3 - (r2 << 1);
        }
        orgL += (org_pitch - 16);
    }
    pred -= 256;
    coef -= 256;
    /* vertical */
    for (blk_y = 4; blk_y > 0; blk_y--)
    {
        for (j = 16; j > 0; j--)
        {
            r0 = coef[0] + coef[48];
            r3 = coef[0] - coef[48];
            r1 = coef[16] + coef[32];
            r2 = coef[16] - coef[32];

            coef[0] = r0 + r1;
            coef[32] = r0 - r1;
            coef[16] = (r3 << 1) + r2;
            coef[48] = r3 - (r2 << 1);

            coef++;
        }
        coef += 48;
    }

    /* then perform DC transform */
    coef -= 256;
    for (j = 4; j > 0; j--)
    {
        r0 = coef[0] + coef[12];
        r3 = coef[0] - coef[12];
        r1 = coef[4] + coef[8];
        r2 = coef[4] - coef[8];

        coef[0] = r0 + r1;
        coef[8] = r0 - r1;
        coef[4] = r3 + r2;
        coef[12] = r3 - r2;
        coef += 64;
    }
    coef -= 256;
    for (j = 4; j > 0; j--)
    {
        r0 = coef[0] + coef[192];
        r3 = coef[0] - coef[192];
        r1 = coef[64] + coef[128];
        r2 = coef[64] - coef[128];

        coef[0] = (r0 + r1) >> 1;
        coef[128] = (r0 - r1) >> 1;
        coef[64] = (r3 + r2) >> 1;
        coef[192] = (r3 - r2) >> 1;
        coef += 4;
    }

    coef -= 16;
    // then quantize DC
    level = encvid->leveldc;
    run = encvid->rundc;

    Rq = video->QPy_mod_6;
    Qq = video->QPy_div_6;
    quant = quant_coef[Rq][0];
    q_bits = 15 + Qq;
    qp_const = encvid->qp_const;

    zero_run = 0;
    ncoeff = 0;
    for (k = 0; k < 16; k++) /* in zigzag scan order */
    {
        idx = ZIGZAG2RASTERDC[k];
        data = coef[idx];
        if (data > 0)   // quant
        {
            lev = data * quant + (qp_const << 1);
        }
        else
        {
            lev = -data * quant + (qp_const << 1);
        }
        lev >>= (q_bits + 1);
        if (lev) // dequant
        {
            if (data > 0)
            {
                level[ncoeff] = lev;
                coef[idx] = lev;
            }
            else
            {
                level[ncoeff] = -lev;
                coef[idx] = -lev;
            }
            run[ncoeff++] = zero_run;
            zero_run = 0;
        }
        else
        {
            zero_run++;
            coef[idx] = 0;
        }
    }

    /* inverse transform DC */
    encvid->numcoefdc = ncoeff;
    if (ncoeff)
    {
        quant = dequant_coefres[Rq][0];

        for (j = 0; j < 4; j++)
        {
            m0 = coef[0] + coef[4];
            m1 = coef[0] - coef[4];
            m2 = coef[8] + coef[12];
            m3 = coef[8] - coef[12];


            coef[0] = m0 + m2;
            coef[4] = m0 - m2;
            coef[8] = m1 - m3;
            coef[12] = m1 + m3;
            coef += 64;
        }

        coef -= 256;

        if (Qq >= 2)  /* this way should be faster than JM */
        {           /* they use (((m4*scale)<<(QPy/6))+2)>>2 for both cases. */
            Qq -= 2;
            for (j = 0; j < 4; j++)
            {
                m0 = coef[0] + coef[64];
                m1 = coef[0] - coef[64];
                m2 = coef[128] + coef[192];
                m3 = coef[128] - coef[192];

                coef[0] = ((m0 + m2) * quant) << Qq;
                coef[64] = ((m0 - m2) * quant) << Qq;
                coef[128] = ((m1 - m3) * quant) << Qq;
                coef[192] = ((m1 + m3) * quant) << Qq;
                coef += 4;
            }
            Qq += 2; /* restore the value */
        }
        else
        {
            Qq = 2 - Qq;
            offset = 1 << (Qq - 1);

            for (j = 0; j < 4; j++)
            {
                m0 = coef[0] + coef[64];
                m1 = coef[0] - coef[64];
                m2 = coef[128] + coef[192];
                m3 = coef[128] - coef[192];

                coef[0] = (((m0 + m2) * quant + offset) >> Qq);
                coef[64] = (((m0 - m2) * quant + offset) >> Qq);
                coef[128] = (((m1 - m3) * quant + offset) >> Qq);
                coef[192] = (((m1 + m3) * quant + offset) >> Qq);
                coef += 4;
            }
            Qq = 2 - Qq; /* restore the value */
        }
        coef -= 16; /* back to the origin */
    }

    /* now zigzag scan ac coefs, quant, iquant and itrans */
    run = encvid->run[0];
    level = encvid->level[0];

    /* offset btw 4x4 block */
    offset_cur[0] = 0;
    offset_cur[1] = (pitch << 2) - 8;

    /* offset btw 8x8 block */
    offset_cur[2] = 8 - (pitch << 3);
    offset_cur[3] = -8;

    /* similarly for pred */
    offset_pred[0] = 0;
    offset_pred[1] = 56;
    offset_pred[2] = -120;
    offset_pred[3] = -8;

    currMB->CBP = 0;

    for (b8 = 0; b8 < 4; b8++)
    {
        for (b4 = 0; b4 < 4; b4++)
        {

            zero_run = 0;
            ncoeff = 0;

            for (k = 1; k < 16; k++)
            {
                idx = ZZ_SCAN_BLOCK[k]; /* map back to raster scan order */
                data = coef[idx];
                quant = quant_coef[Rq][k];
                if (data > 0)
                {
                    lev = data * quant + qp_const;
                }
                else
                {
                    lev = -data * quant + qp_const;
                }
                lev >>= q_bits;
                if (lev)
                {   /* dequant */
                    quant = dequant_coefres[Rq][k];
                    if (data > 0)
                    {
                        level[ncoeff] = lev;
                        coef[idx] = (lev * quant) << Qq;
                    }
                    else
                    {
                        level[ncoeff] = -lev;
                        coef[idx] = (-lev * quant) << Qq;
                    }
                    run[ncoeff++] = zero_run;
                    zero_run = 0;
                }
                else
                {
                    zero_run++;
                    coef[idx] = 0;
                }
            }

            currMB->nz_coeff[blkIdx2blkXY[b8][b4]] = ncoeff; /* in raster scan !!! */
            if (ncoeff)
            {
                currMB->CBP |= (1 << b8);

                // do inverse transform here
                for (j = 4; j > 0; j--)
                {
                    r0 = coef[0] + coef[2];
                    r1 = coef[0] - coef[2];
                    r2 = (coef[1] >> 1) - coef[3];
                    r3 = coef[1] + (coef[3] >> 1);

                    coef[0] = r0 + r3;
                    coef[1] = r1 + r2;
                    coef[2] = r1 - r2;
                    coef[3] = r0 - r3;

                    coef += 16;
                }
                coef -= 64;
                for (j = 4; j > 0; j--)
                {
                    r0 = coef[0] + coef[32];
                    r1 = coef[0] - coef[32];
                    r2 = (coef[16] >> 1) - coef[48];
                    r3 = coef[16] + (coef[48] >> 1);

                    r0 += r3;
                    r3 = (r0 - (r3 << 1)); /* r0-r3 */
                    r1 += r2;
                    r2 = (r1 - (r2 << 1)); /* r1-r2 */
                    r0 += 32;
                    r1 += 32;
                    r2 += 32;
                    r3 += 32;
                    r0 = pred[0] + (r0 >> 6);
                    if ((uint)r0 > 0xFF)   r0 = 0xFF & (~(r0 >> 31));  /* clip */
                    r1 = pred[16] + (r1 >> 6);
                    if ((uint)r1 > 0xFF)   r1 = 0xFF & (~(r1 >> 31));  /* clip */
                    r2 = pred[32] + (r2 >> 6);
                    if ((uint)r2 > 0xFF)   r2 = 0xFF & (~(r2 >> 31));  /* clip */
                    r3 = pred[48] + (r3 >> 6);
                    if ((uint)r3 > 0xFF)   r3 = 0xFF & (~(r3 >> 31));  /* clip */
                    *curL = r0;
                    *(curL += pitch) = r1;
                    *(curL += pitch) = r2;
                    curL[pitch] = r3;
                    curL -= (pitch << 1);
                    curL++;
                    pred++;
                    coef++;
                }
            }
            else  // do DC-only inverse
            {
                m0 = coef[0] + 32;

                for (j = 4; j > 0; j--)
                {
                    r0 = pred[0] + (m0 >> 6);
                    if ((uint)r0 > 0xFF)   r0 = 0xFF & (~(r0 >> 31));  /* clip */
                    r1 = pred[16] + (m0 >> 6);
                    if ((uint)r1 > 0xFF)   r1 = 0xFF & (~(r1 >> 31));  /* clip */
                    r2 = pred[32] + (m0 >> 6);
                    if ((uint)r2 > 0xFF)   r2 = 0xFF & (~(r2 >> 31));  /* clip */
                    r3 = pred[48] + (m0 >> 6);
                    if ((uint)r3 > 0xFF)   r3 = 0xFF & (~(r3 >> 31));  /* clip */
                    *curL = r0;
                    *(curL += pitch) = r1;
                    *(curL += pitch) = r2;
                    curL[pitch] = r3;
                    curL -= (pitch << 1);
                    curL++;
                    pred++;
                }
                coef += 4;
            }

            run += 16;  // follow coding order
            level += 16;
            curL += offset_cur[b4&1];
            pred += offset_pred[b4&1];
            coef += offset_pred[b4&1];
        }

        curL += offset_cur[2 + (b8&1)];
        pred += offset_pred[2 + (b8&1)];
        coef += offset_pred[2 + (b8&1)];
    }

    return ;
}


void dct_chroma(AVCEncObject *encvid, uint8 *curC, uint8 *orgC, int cr)
{
    AVCCommonObj *video = encvid->common;
    AVCMacroblock *currMB = video->currMB;
    int org_pitch = (encvid->currInput->pitch) >> 1;
    int pitch = (video->currPic->pitch) >> 1;
    int pred_pitch = 16;
    int16 *coef = video->block + 256;
    uint8 *pred = video->pred_block;
    int j, blk_x, blk_y, k, idx, b4;
    int r0, r1, r2, r3, m0;
    int Qq, Rq, qp_const, q_bits, quant;
    int *level, *run, zero_run, ncoeff;
    int data, lev;
    int offset_cur[2], offset_pred[2], offset_coef[2];
    uint8 nz_temp[4];
    int  coeff_cost;

    if (cr)
    {
        coef += 8;
        pred += 8;
    }

    if (currMB->mb_intra == 0) // inter mode
    {
        pred = curC;
        pred_pitch = pitch;
    }

    /* do 4x4 transform */
    /* horizontal */
    for (j = 8; j > 0; j--)
    {
        for (blk_x = 2; blk_x > 0; blk_x--)
        {
            /* calculate the residue first */
            r0 = *orgC++ - *pred++;
            r1 = *orgC++ - *pred++;
            r2 = *orgC++ - *pred++;
            r3 = *orgC++ - *pred++;

            r0 += r3;           //ptr[0] + ptr[3];
            r3 = r0 - (r3 << 1);    //ptr[0] - ptr[3];
            r1 += r2;           //ptr[1] + ptr[2];
            r2 = r1 - (r2 << 1);    //ptr[1] - ptr[2];

            *coef++ = r0 + r1;
            *coef++ = (r3 << 1) + r2;
            *coef++ = r0 - r1;
            *coef++ = r3 - (r2 << 1);

        }
        coef += 8; // coef pitch is 16
        pred += (pred_pitch - 8); // pred_pitch is 16
        orgC += (org_pitch - 8);
    }
    pred -= (pred_pitch << 3);
    coef -= 128;
    /* vertical */
    for (blk_y = 2; blk_y > 0; blk_y--)
    {
        for (j = 8; j > 0; j--)
        {
            r0 = coef[0] + coef[48];
            r3 = coef[0] - coef[48];
            r1 = coef[16] + coef[32];
            r2 = coef[16] - coef[32];

            coef[0] = r0 + r1;
            coef[32] = r0 - r1;
            coef[16] = (r3 << 1) + r2;
            coef[48] = r3 - (r2 << 1);

            coef++;
        }
        coef += 56;
    }
    /* then perform DC transform */
    coef -= 128;

    /* 2x2 transform of DC components*/
    r0 = coef[0];
    r1 = coef[4];
    r2 = coef[64];
    r3 = coef[68];

    coef[0] = r0 + r1 + r2 + r3;
    coef[4] = r0 - r1 + r2 - r3;
    coef[64] = r0 + r1 - r2 - r3;
    coef[68] = r0 - r1 - r2 + r3;

    Qq    = video->QPc_div_6;
    Rq    = video->QPc_mod_6;
    quant = quant_coef[Rq][0];
    q_bits    = 15 + Qq;
    qp_const = encvid->qp_const_c;

    zero_run = 0;
    ncoeff = 0;
    run = encvid->runcdc + (cr << 2);
    level = encvid->levelcdc + (cr << 2);

    /* in zigzag scan order */
    for (k = 0; k < 4; k++)
    {
        idx = ((k >> 1) << 6) + ((k & 1) << 2);
        data = coef[idx];
        if (data > 0)
        {
            lev = data * quant + (qp_const << 1);
        }
        else
        {
            lev = -data * quant + (qp_const << 1);
        }
        lev >>= (q_bits + 1);
        if (lev)
        {
            if (data > 0)
            {
                level[ncoeff] = lev;
                coef[idx] = lev;
            }
            else
            {
                level[ncoeff] = -lev;
                coef[idx] = -lev;
            }
            run[ncoeff++] = zero_run;
            zero_run = 0;
        }
        else
        {
            zero_run++;
            coef[idx] = 0;
        }
    }

    encvid->numcoefcdc[cr] = ncoeff;

    if (ncoeff)
    {
        currMB->CBP |= (1 << 4); // DC present
        // do inverse transform
        quant = dequant_coefres[Rq][0];

        r0 = coef[0] + coef[4];
        r1 = coef[0] - coef[4];
        r2 = coef[64] + coef[68];
        r3 = coef[64] - coef[68];

        r0 += r2;
        r2 = r0 - (r2 << 1);
        r1 += r3;
        r3 = r1 - (r3 << 1);

        if (Qq >= 1)
        {
            Qq -= 1;
            coef[0] = (r0 * quant) << Qq;
            coef[4] = (r1 * quant) << Qq;
            coef[64] = (r2 * quant) << Qq;
            coef[68] = (r3 * quant) << Qq;
            Qq++;
        }
        else
        {
            coef[0] = (r0 * quant) >> 1;
            coef[4] = (r1 * quant) >> 1;
            coef[64] = (r2 * quant) >> 1;
            coef[68] = (r3 * quant) >> 1;
        }
    }

    /* now do AC zigzag scan, quant, iquant and itrans */
    if (cr)
    {
        run = encvid->run[20];
        level = encvid->level[20];
    }
    else
    {
        run = encvid->run[16];
        level = encvid->level[16];
    }

    /* offset btw 4x4 block */
    offset_cur[0] = 0;
    offset_cur[1] = (pitch << 2) - 8;
    offset_pred[0] = 0;
    offset_pred[1] = (pred_pitch << 2) - 8;
    offset_coef[0] = 0;
    offset_coef[1] = 56;

    coeff_cost = 0;

    for (b4 = 0; b4 < 4; b4++)
    {
        zero_run = 0;
        ncoeff = 0;
        for (k = 1; k < 16; k++) /* in zigzag scan order */
        {
            idx = ZZ_SCAN_BLOCK[k]; /* map back to raster scan order */
            data = coef[idx];
            quant = quant_coef[Rq][k];
            if (data > 0)
            {
                lev = data * quant + qp_const;
            }
            else
            {
                lev = -data * quant + qp_const;
            }
            lev >>= q_bits;
            if (lev)
            {
                /* for RD performance*/
                if (lev > 1)
                    coeff_cost += MAX_VALUE;                // set high cost, shall not be discarded
                else
                    coeff_cost += COEFF_COST[DISABLE_THRESHOLDING][zero_run];

                /* dequant */
                quant = dequant_coefres[Rq][k];
                if (data > 0)
                {
                    level[ncoeff] = lev;
                    coef[idx] = (lev * quant) << Qq;
                }
                else
                {
                    level[ncoeff] = -lev;
                    coef[idx] = (-lev * quant) << Qq;
                }
                run[ncoeff++] = zero_run;
                zero_run = 0;
            }
            else
            {
                zero_run++;
                coef[idx] = 0;
            }
        }

        nz_temp[b4] = ncoeff; // raster scan

        // just advance the pointers for now, do IDCT later
        coef += 4;
        run += 16;
        level += 16;
        coef += offset_coef[b4&1];
    }

    /* rewind the pointers */
    coef -= 128;

    if (coeff_cost < _CHROMA_COEFF_COST_)
    {
        /* if it's not efficient to encode any blocks.
        Just do DC only */
        /* We can reset level and run also, but setting nz to zero should be enough. */
        currMB->nz_coeff[16+(cr<<1)] = 0;
        currMB->nz_coeff[17+(cr<<1)] = 0;
        currMB->nz_coeff[20+(cr<<1)] = 0;
        currMB->nz_coeff[21+(cr<<1)] = 0;

        for (b4 = 0; b4 < 4; b4++)
        {
            // do DC-only inverse
            m0 = coef[0] + 32;

            for (j = 4; j > 0; j--)
            {
                r0 = pred[0] + (m0 >> 6);
                if ((uint)r0 > 0xFF)   r0 = 0xFF & (~(r0 >> 31));  /* clip */
                r1 = *(pred += pred_pitch) + (m0 >> 6);
                if ((uint)r1 > 0xFF)   r1 = 0xFF & (~(r1 >> 31));  /* clip */
                r2 = pred[pred_pitch] + (m0 >> 6);
                if ((uint)r2 > 0xFF)   r2 = 0xFF & (~(r2 >> 31));  /* clip */
                r3 = pred[pred_pitch<<1] + (m0 >> 6);
                if ((uint)r3 > 0xFF)   r3 = 0xFF & (~(r3 >> 31));  /* clip */
                *curC = r0;
                *(curC += pitch) = r1;
                *(curC += pitch) = r2;
                curC[pitch] = r3;
                curC -= (pitch << 1);
                curC++;
                pred += (1 - pred_pitch);
            }
            coef += 4;
            curC += offset_cur[b4&1];
            pred += offset_pred[b4&1];
            coef += offset_coef[b4&1];
        }
    }
    else // not dropping anything, continue with the IDCT
    {
        for (b4 = 0; b4 < 4; b4++)
        {
            ncoeff = nz_temp[b4] ; // in raster scan
            currMB->nz_coeff[16+(b4&1)+(cr<<1)+((b4>>1)<<2)] = ncoeff; // in raster scan

            if (ncoeff) // do a check on the nonzero-coeff
            {
                currMB->CBP |= (2 << 4);

                // do inverse transform here
                for (j = 4; j > 0; j--)
                {
                    r0 = coef[0] + coef[2];
                    r1 = coef[0] - coef[2];
                    r2 = (coef[1] >> 1) - coef[3];
                    r3 = coef[1] + (coef[3] >> 1);

                    coef[0] = r0 + r3;
                    coef[1] = r1 + r2;
                    coef[2] = r1 - r2;
                    coef[3] = r0 - r3;

                    coef += 16;
                }
                coef -= 64;
                for (j = 4; j > 0; j--)
                {
                    r0 = coef[0] + coef[32];
                    r1 = coef[0] - coef[32];
                    r2 = (coef[16] >> 1) - coef[48];
                    r3 = coef[16] + (coef[48] >> 1);

                    r0 += r3;
                    r3 = (r0 - (r3 << 1)); /* r0-r3 */
                    r1 += r2;
                    r2 = (r1 - (r2 << 1)); /* r1-r2 */
                    r0 += 32;
                    r1 += 32;
                    r2 += 32;
                    r3 += 32;
                    r0 = pred[0] + (r0 >> 6);
                    if ((uint)r0 > 0xFF)   r0 = 0xFF & (~(r0 >> 31));  /* clip */
                    r1 = *(pred += pred_pitch) + (r1 >> 6);
                    if ((uint)r1 > 0xFF)   r1 = 0xFF & (~(r1 >> 31));  /* clip */
                    r2 = pred[pred_pitch] + (r2 >> 6);
                    if ((uint)r2 > 0xFF)   r2 = 0xFF & (~(r2 >> 31));  /* clip */
                    r3 = pred[pred_pitch<<1] + (r3 >> 6);
                    if ((uint)r3 > 0xFF)   r3 = 0xFF & (~(r3 >> 31));  /* clip */
                    *curC = r0;
                    *(curC += pitch) = r1;
                    *(curC += pitch) = r2;
                    curC[pitch] = r3;
                    curC -= (pitch << 1);
                    curC++;
                    pred += (1 - pred_pitch);
                    coef++;
                }
            }
            else
            {
                // do DC-only inverse
                m0 = coef[0] + 32;

                for (j = 4; j > 0; j--)
                {
                    r0 = pred[0] + (m0 >> 6);
                    if ((uint)r0 > 0xFF)   r0 = 0xFF & (~(r0 >> 31));  /* clip */
                    r1 = *(pred += pred_pitch) + (m0 >> 6);
                    if ((uint)r1 > 0xFF)   r1 = 0xFF & (~(r1 >> 31));  /* clip */
                    r2 = pred[pred_pitch] + (m0 >> 6);
                    if ((uint)r2 > 0xFF)   r2 = 0xFF & (~(r2 >> 31));  /* clip */
                    r3 = pred[pred_pitch<<1] + (m0 >> 6);
                    if ((uint)r3 > 0xFF)   r3 = 0xFF & (~(r3 >> 31));  /* clip */
                    *curC = r0;
                    *(curC += pitch) = r1;
                    *(curC += pitch) = r2;
                    curC[pitch] = r3;
                    curC -= (pitch << 1);
                    curC++;
                    pred += (1 - pred_pitch);
                }
                coef += 4;
            }
            curC += offset_cur[b4&1];
            pred += offset_pred[b4&1];
            coef += offset_coef[b4&1];
        }
    }

    return ;
}


/* only DC transform */
int TransQuantIntra16DC(AVCEncObject *encvid)
{
    AVCCommonObj *video = encvid->common;
    int16 *block = video->block;
    int *level = encvid->leveldc;
    int *run = encvid->rundc;
    int16 *ptr = block;
    int r0, r1, r2, r3, j;
    int Qq = video->QPy_div_6;
    int Rq = video->QPy_mod_6;
    int q_bits, qp_const, quant;
    int data, lev, zero_run;
    int k, ncoeff, idx;

    /* DC transform */
    /* horizontal */
    j = 4;
    while (j)
    {
        r0 = ptr[0] + ptr[12];
        r3 = ptr[0] - ptr[12];
        r1 = ptr[4] + ptr[8];
        r2 = ptr[4] - ptr[8];

        ptr[0] = r0 + r1;
        ptr[8] = r0 - r1;
        ptr[4] = r3 + r2;
        ptr[12] = r3 - r2;
        ptr += 64;
        j--;
    }
    /* vertical */
    ptr = block;
    j = 4;
    while (j)
    {
        r0 = ptr[0] + ptr[192];
        r3 = ptr[0] - ptr[192];
        r1 = ptr[64] + ptr[128];
        r2 = ptr[64] - ptr[128];

        ptr[0] = (r0 + r1) >> 1;
        ptr[128] = (r0 - r1) >> 1;
        ptr[64] = (r3 + r2) >> 1;
        ptr[192] = (r3 - r2) >> 1;
        ptr += 4;
        j--;
    }

    quant = quant_coef[Rq][0];
    q_bits    = 15 + Qq;
    qp_const = (1 << q_bits) / 3;    // intra

    zero_run = 0;
    ncoeff = 0;

    for (k = 0; k < 16; k++) /* in zigzag scan order */
    {
        idx = ZIGZAG2RASTERDC[k];
        data = block[idx];
        if (data > 0)
        {
            lev = data * quant + (qp_const << 1);
        }
        else
        {
            lev = -data * quant + (qp_const << 1);
        }
        lev >>= (q_bits + 1);
        if (lev)
        {
            if (data > 0)
            {
                level[ncoeff] = lev;
                block[idx] = lev;
            }
            else
            {
                level[ncoeff] = -lev;
                block[idx] = -lev;
            }
            run[ncoeff++] = zero_run;
            zero_run = 0;
        }
        else
        {
            zero_run++;
            block[idx] = 0;
        }
    }
    return ncoeff;
}

int TransQuantChromaDC(AVCEncObject *encvid, int16 *block, int slice_type, int cr)
{
    AVCCommonObj *video = encvid->common;
    int *level, *run;
    int r0, r1, r2, r3;
    int Qq, Rq, q_bits, qp_const, quant;
    int data, lev, zero_run;
    int k, ncoeff, idx;

    level = encvid->levelcdc + (cr << 2); /* cb or cr */
    run = encvid->runcdc + (cr << 2);

    /* 2x2 transform of DC components*/
    r0 = block[0];
    r1 = block[4];
    r2 = block[64];
    r3 = block[68];

    block[0] = r0 + r1 + r2 + r3;
    block[4] = r0 - r1 + r2 - r3;
    block[64] = r0 + r1 - r2 - r3;
    block[68] = r0 - r1 - r2 + r3;

    Qq    = video->QPc_div_6;
    Rq    = video->QPc_mod_6;
    quant = quant_coef[Rq][0];
    q_bits    = 15 + Qq;
    if (slice_type == AVC_I_SLICE)
    {
        qp_const = (1 << q_bits) / 3;
    }
    else
    {
        qp_const = (1 << q_bits) / 6;
    }

    zero_run = 0;
    ncoeff = 0;

    for (k = 0; k < 4; k++) /* in zigzag scan order */
    {
        idx = ((k >> 1) << 6) + ((k & 1) << 2);
        data = block[idx];
        if (data > 0)
        {
            lev = data * quant + (qp_const << 1);
        }
        else
        {
            lev = -data * quant + (qp_const << 1);
        }
        lev >>= (q_bits + 1);
        if (lev)
        {
            if (data > 0)
            {
                level[ncoeff] = lev;
                block[idx] = lev;
            }
            else
            {
                level[ncoeff] = -lev;
                block[idx] = -lev;
            }
            run[ncoeff++] = zero_run;
            zero_run = 0;
        }
        else
        {
            zero_run++;
            block[idx] = 0;
        }
    }
    return ncoeff;
}


