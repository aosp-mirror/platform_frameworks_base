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
/* 3/29/01 fast half-pel search based on neighboring guess */
/* value ranging from 0 to 4, high complexity (more accurate) to
   low complexity (less accurate) */
#define HP_DISTANCE_TH      5 // 2  /* half-pel distance threshold */

#define PREF_16_VEC 129     /* 1MV bias versus 4MVs*/

const static int distance_tab[9][9] =   /* [hp_guess][k] */
{
    {0, 1, 1, 1, 1, 1, 1, 1, 1},
    {1, 0, 1, 2, 3, 4, 3, 2, 1},
    {1, 0, 0, 0, 1, 2, 3, 2, 1},
    {1, 2, 1, 0, 1, 2, 3, 4, 3},
    {1, 2, 1, 0, 0, 0, 1, 2, 3},
    {1, 4, 3, 2, 1, 0, 1, 2, 3},
    {1, 2, 3, 2, 1, 0, 0, 0, 1},
    {1, 2, 3, 4, 3, 2, 1, 0, 1},
    {1, 0, 1, 2, 3, 2, 1, 0, 0}
};

#define CLIP_RESULT(x)      if((uint)x > 0xFF){ \
                 x = 0xFF & (~(x>>31));}

#define CLIP_UPPER16(x)     if((uint)x >= 0x20000000){ \
        x = 0xFF0000 & (~(x>>31));} \
        else { \
        x = (x>>5)&0xFF0000; \
        }

/*=====================================================================
    Function:   AVCFindHalfPelMB
    Date:       10/31/2007
    Purpose:    Find half pel resolution MV surrounding the full-pel MV
=====================================================================*/

int AVCFindHalfPelMB(AVCEncObject *encvid, uint8 *cur, AVCMV *mot, uint8 *ncand,
                     int xpos, int ypos, int hp_guess, int cmvx, int cmvy)
{
    AVCPictureData *currPic = encvid->common->currPic;
    int lx = currPic->pitch;
    int d, dmin, satd_min;
    uint8* cand;
    int lambda_motion = encvid->lambda_motion;
    uint8 *mvbits = encvid->mvbits;
    int mvcost;
    /* list of candidate to go through for half-pel search*/
    uint8 *subpel_pred = (uint8*) encvid->subpel_pred; // all 16 sub-pel positions
    uint8 **hpel_cand = (uint8**) encvid->hpel_cand; /* half-pel position */

    int xh[9] = {0, 0, 2, 2, 2, 0, -2, -2, -2};
    int yh[9] = {0, -2, -2, 0, 2, 2, 2, 0, -2};
    int xq[8] = {0, 1, 1, 1, 0, -1, -1, -1};
    int yq[8] = { -1, -1, 0, 1, 1, 1, 0, -1};
    int h, hmin, q, qmin;

    OSCL_UNUSED_ARG(xpos);
    OSCL_UNUSED_ARG(ypos);
    OSCL_UNUSED_ARG(hp_guess);

    GenerateHalfPelPred(subpel_pred, ncand, lx);

    cur = encvid->currYMB; // pre-load current original MB

    cand = hpel_cand[0];

    // find cost for the current full-pel position
    dmin = SATD_MB(cand, cur, 65535); // get Hadamaard transform SAD
    mvcost = MV_COST_S(lambda_motion, mot->x, mot->y, cmvx, cmvy);
    satd_min = dmin;
    dmin += mvcost;
    hmin = 0;

    /* find half-pel */
    for (h = 1; h < 9; h++)
    {
        d = SATD_MB(hpel_cand[h], cur, dmin);
        mvcost = MV_COST_S(lambda_motion, mot->x + xh[h], mot->y + yh[h], cmvx, cmvy);
        d += mvcost;

        if (d < dmin)
        {
            dmin = d;
            hmin = h;
            satd_min = d - mvcost;
        }
    }

    mot->sad = dmin;
    mot->x += xh[hmin];
    mot->y += yh[hmin];
    encvid->best_hpel_pos = hmin;

    /*** search for quarter-pel ****/
    GenerateQuartPelPred(encvid->bilin_base[hmin], &(encvid->qpel_cand[0][0]), hmin);

    encvid->best_qpel_pos = qmin = -1;

    for (q = 0; q < 8; q++)
    {
        d = SATD_MB(encvid->qpel_cand[q], cur, dmin);
        mvcost = MV_COST_S(lambda_motion, mot->x + xq[q], mot->y + yq[q], cmvx, cmvy);
        d += mvcost;
        if (d < dmin)
        {
            dmin = d;
            qmin = q;
            satd_min = d - mvcost;
        }
    }

    if (qmin != -1)
    {
        mot->sad = dmin;
        mot->x += xq[qmin];
        mot->y += yq[qmin];
        encvid->best_qpel_pos = qmin;
    }

    return satd_min;
}



/** This function generates sub-pel prediction around the full-pel candidate.
Each sub-pel position array is 20 pixel wide (for word-alignment) and 17 pixel tall. */
/** The sub-pel position is labeled in spiral manner from the center. */

void GenerateHalfPelPred(uint8* subpel_pred, uint8 *ncand, int lx)
{
    /* let's do straightforward way first */
    uint8 *ref;
    uint8 *dst;
    uint8 tmp8;
    int32 tmp32;
    int16 tmp_horz[18*22], *dst_16, *src_16;
    register int a = 0, b = 0, c = 0, d = 0, e = 0, f = 0; // temp register
    int msk;
    int i, j;

    /* first copy full-pel to the first array */
    /* to be optimized later based on byte-offset load */
    ref = ncand - 3 - lx - (lx << 1); /* move back (-3,-3) */
    dst = subpel_pred;

    dst -= 4; /* offset */
    for (j = 0; j < 22; j++) /* 24x22 */
    {
        i = 6;
        while (i > 0)
        {
            tmp32 = *ref++;
            tmp8 = *ref++;
            tmp32 |= (tmp8 << 8);
            tmp8 = *ref++;
            tmp32 |= (tmp8 << 16);
            tmp8 = *ref++;
            tmp32 |= (tmp8 << 24);
            *((uint32*)(dst += 4)) = tmp32;
            i--;
        }
        ref += (lx - 24);
    }

    /* from the first array, we do horizontal interp */
    ref = subpel_pred + 2;
    dst_16 = tmp_horz; /* 17 x 22 */

    for (j = 4; j > 0; j--)
    {
        for (i = 16; i > 0; i -= 4)
        {
            a = ref[-2];
            b = ref[-1];
            c = ref[0];
            d = ref[1];
            e = ref[2];
            f = ref[3];
            *dst_16++ = a + f - 5 * (b + e) + 20 * (c + d);
            a = ref[4];
            *dst_16++ = b + a - 5 * (c + f) + 20 * (d + e);
            b = ref[5];
            *dst_16++ = c + b - 5 * (d + a) + 20 * (e + f);
            c = ref[6];
            *dst_16++ = d + c - 5 * (e + b) + 20 * (f + a);

            ref += 4;
        }
        /* do the 17th column here */
        d = ref[3];
        *dst_16 =  e + d - 5 * (f + c) + 20 * (a + b);
        dst_16 += 2; /* stride for tmp_horz is 18 */
        ref += 8;  /* stride for ref is 24 */
        if (j == 3)  // move 18 lines down
        {
            dst_16 += 324;//18*18;
            ref += 432;//18*24;
        }
    }

    ref -= 480;//20*24;
    dst_16 -= 360;//20*18;
    dst = subpel_pred + V0Q_H2Q * SUBPEL_PRED_BLK_SIZE; /* go to the 14th array 17x18*/

    for (j = 18; j > 0; j--)
    {
        for (i = 16; i > 0; i -= 4)
        {
            a = ref[-2];
            b = ref[-1];
            c = ref[0];
            d = ref[1];
            e = ref[2];
            f = ref[3];
            tmp32 = a + f - 5 * (b + e) + 20 * (c + d);
            *dst_16++ = tmp32;
            tmp32 = (tmp32 + 16) >> 5;
            CLIP_RESULT(tmp32)
            *dst++ = tmp32;

            a = ref[4];
            tmp32 = b + a - 5 * (c + f) + 20 * (d + e);
            *dst_16++ = tmp32;
            tmp32 = (tmp32 + 16) >> 5;
            CLIP_RESULT(tmp32)
            *dst++ = tmp32;

            b = ref[5];
            tmp32 = c + b - 5 * (d + a) + 20 * (e + f);
            *dst_16++ = tmp32;
            tmp32 = (tmp32 + 16) >> 5;
            CLIP_RESULT(tmp32)
            *dst++ = tmp32;

            c = ref[6];
            tmp32 = d + c - 5 * (e + b) + 20 * (f + a);
            *dst_16++ = tmp32;
            tmp32 = (tmp32 + 16) >> 5;
            CLIP_RESULT(tmp32)
            *dst++ = tmp32;

            ref += 4;
        }
        /* do the 17th column here */
        d = ref[3];
        tmp32 =  e + d - 5 * (f + c) + 20 * (a + b);
        *dst_16 = tmp32;
        tmp32 = (tmp32 + 16) >> 5;
        CLIP_RESULT(tmp32)
        *dst = tmp32;

        dst += 8;  /* stride for dst is 24 */
        dst_16 += 2; /* stride for tmp_horz is 18 */
        ref += 8;  /* stride for ref is 24 */
    }


    /* Do middle point filtering*/
    src_16 = tmp_horz; /* 17 x 22 */
    dst = subpel_pred + V2Q_H2Q * SUBPEL_PRED_BLK_SIZE; /* 12th array 17x17*/
    dst -= 24; // offset
    for (i = 0; i < 17; i++)
    {
        for (j = 16; j > 0; j -= 4)
        {
            a = *src_16;
            b = *(src_16 += 18);
            c = *(src_16 += 18);
            d = *(src_16 += 18);
            e = *(src_16 += 18);
            f = *(src_16 += 18);

            tmp32 = a + f - 5 * (b + e) + 20 * (c + d);
            tmp32 = (tmp32 + 512) >> 10;
            CLIP_RESULT(tmp32)
            *(dst += 24) = tmp32;

            a = *(src_16 += 18);
            tmp32 = b + a - 5 * (c + f) + 20 * (d + e);
            tmp32 = (tmp32 + 512) >> 10;
            CLIP_RESULT(tmp32)
            *(dst += 24) = tmp32;

            b = *(src_16 += 18);
            tmp32 = c + b - 5 * (d + a) + 20 * (e + f);
            tmp32 = (tmp32 + 512) >> 10;
            CLIP_RESULT(tmp32)
            *(dst += 24) = tmp32;

            c = *(src_16 += 18);
            tmp32 = d + c - 5 * (e + b) + 20 * (f + a);
            tmp32 = (tmp32 + 512) >> 10;
            CLIP_RESULT(tmp32)
            *(dst += 24) = tmp32;

            src_16 -= (18 << 2);
        }

        d = src_16[90]; // 18*5
        tmp32 = e + d - 5 * (f + c) + 20 * (a + b);
        tmp32 = (tmp32 + 512) >> 10;
        CLIP_RESULT(tmp32)
        dst[24] = tmp32;

        src_16 -= ((18 << 4) - 1);
        dst -= ((24 << 4) - 1);
    }

    /* do vertical interpolation */
    ref = subpel_pred + 2;
    dst = subpel_pred + V2Q_H0Q * SUBPEL_PRED_BLK_SIZE; /* 10th array 18x17 */
    dst -= 24; // offset

    for (i = 2; i > 0; i--)
    {
        for (j = 16; j > 0; j -= 4)
        {
            a = *ref;
            b = *(ref += 24);
            c = *(ref += 24);
            d = *(ref += 24);
            e = *(ref += 24);
            f = *(ref += 24);

            tmp32 = a + f - 5 * (b + e) + 20 * (c + d);
            tmp32 = (tmp32 + 16) >> 5;
            CLIP_RESULT(tmp32)
            *(dst += 24) = tmp32;  // 10th

            a = *(ref += 24);
            tmp32 = b + a - 5 * (c + f) + 20 * (d + e);
            tmp32 = (tmp32 + 16) >> 5;
            CLIP_RESULT(tmp32)
            *(dst += 24) = tmp32;  // 10th

            b = *(ref += 24);
            tmp32 = c + b - 5 * (d + a) + 20 * (e + f);
            tmp32 = (tmp32 + 16) >> 5;
            CLIP_RESULT(tmp32)
            *(dst += 24) = tmp32;  // 10th

            c = *(ref += 24);
            tmp32 = d + c - 5 * (e + b) + 20 * (f + a);
            tmp32 = (tmp32 + 16) >> 5;
            CLIP_RESULT(tmp32)
            *(dst += 24) = tmp32;  // 10th

            ref -= (24 << 2);
        }

        d = ref[120]; // 24*5
        tmp32 = e + d - 5 * (f + c) + 20 * (a + b);
        tmp32 = (tmp32 + 16) >> 5;
        CLIP_RESULT(tmp32)
        dst[24] = tmp32;  // 10th

        dst -= ((24 << 4) - 1);
        ref -= ((24 << 4) - 1);
    }

    // note that using SIMD here doesn't help much, the cycle almost stays the same
    // one can just use the above code and change the for(i=2 to for(i=18
    for (i = 16; i > 0; i -= 4)
    {
        msk = 0;
        for (j = 17; j > 0; j--)
        {
            a = *((uint32*)ref); /* load 4 bytes */
            b = (a >> 8) & 0xFF00FF; /* second and fourth byte */
            a &= 0xFF00FF;

            c = *((uint32*)(ref + 120));
            d = (c >> 8) & 0xFF00FF;
            c &= 0xFF00FF;

            a += c;
            b += d;

            e = *((uint32*)(ref + 72)); /* e, f */
            f = (e >> 8) & 0xFF00FF;
            e &= 0xFF00FF;

            c = *((uint32*)(ref + 48)); /* c, d */
            d = (c >> 8) & 0xFF00FF;
            c &= 0xFF00FF;

            c += e;
            d += f;

            a += 20 * c;
            b += 20 * d;
            a += 0x100010;
            b += 0x100010;

            e = *((uint32*)(ref += 24)); /* e, f */
            f = (e >> 8) & 0xFF00FF;
            e &= 0xFF00FF;

            c = *((uint32*)(ref + 72)); /* c, d */
            d = (c >> 8) & 0xFF00FF;
            c &= 0xFF00FF;

            c += e;
            d += f;

            a -= 5 * c;
            b -= 5 * d;

            c = a << 16;
            d = b << 16;
            CLIP_UPPER16(a)
            CLIP_UPPER16(c)
            CLIP_UPPER16(b)
            CLIP_UPPER16(d)

            a |= (c >> 16);
            b |= (d >> 16);
            //  a>>=5;
            //  b>>=5;
            /* clip */
            //  msk |= b;  msk|=a;
            //  a &= 0xFF00FF;
            //  b &= 0xFF00FF;
            a |= (b << 8);  /* pack it back */

            *((uint16*)(dst += 24)) = a & 0xFFFF; //dst is not word-aligned.
            *((uint16*)(dst + 2)) = a >> 16;

        }
        dst -= 404; // 24*17-4
        ref -= 404;
        /*      if(msk & 0xFF00FF00) // need clipping
                {
                    VertInterpWClip(dst,ref); // re-do 4 column with clip
                }*/
    }

    return ;
}

void VertInterpWClip(uint8 *dst, uint8 *ref)
{
    int i, j;
    int a, b, c, d, e, f;
    int32 tmp32;

    dst -= 4;
    ref -= 4;

    for (i = 4; i > 0; i--)
    {
        for (j = 16; j > 0; j -= 4)
        {
            a = *ref;
            b = *(ref += 24);
            c = *(ref += 24);
            d = *(ref += 24);
            e = *(ref += 24);
            f = *(ref += 24);

            tmp32 = a + f - 5 * (b + e) + 20 * (c + d);
            tmp32 = (tmp32 + 16) >> 5;
            CLIP_RESULT(tmp32)
            *(dst += 24) = tmp32;  // 10th

            a = *(ref += 24);
            tmp32 = b + a - 5 * (c + f) + 20 * (d + e);
            tmp32 = (tmp32 + 16) >> 5;
            CLIP_RESULT(tmp32)
            *(dst += 24) = tmp32;  // 10th

            b = *(ref += 24);
            tmp32 = c + b - 5 * (d + a) + 20 * (e + f);
            tmp32 = (tmp32 + 16) >> 5;
            CLIP_RESULT(tmp32)
            *(dst += 24) = tmp32;  // 10th

            c = *(ref += 24);
            tmp32 = d + c - 5 * (e + b) + 20 * (f + a);
            tmp32 = (tmp32 + 16) >> 5;
            CLIP_RESULT(tmp32)
            *(dst += 24) = tmp32;  // 10th

            ref -= (24 << 2);
        }

        d = ref[120]; // 24*5
        tmp32 = e + d - 5 * (f + c) + 20 * (a + b);
        tmp32 = (tmp32 + 16) >> 5;
        CLIP_RESULT(tmp32)
        dst[24] = tmp32;  // 10th

        dst -= ((24 << 4) - 1);
        ref -= ((24 << 4) - 1);
    }

    return ;
}


void GenerateQuartPelPred(uint8 **bilin_base, uint8 *qpel_cand, int hpel_pos)
{
    // for even value of hpel_pos, start with pattern 1, otherwise, start with pattern 2
    int i, j;

    uint8 *c1 = qpel_cand;
    uint8 *tl = bilin_base[0];
    uint8 *tr = bilin_base[1];
    uint8 *bl = bilin_base[2];
    uint8 *br = bilin_base[3];
    int a, b, c, d;
    int offset = 1 - (384 * 7);

    if (!(hpel_pos&1)) // diamond pattern
    {
        j = 16;
        while (j--)
        {
            i = 16;
            while (i--)
            {
                d = tr[24];
                a = *tr++;
                b = bl[1];
                c = *br++;

                *c1 = (c + a + 1) >> 1;
                *(c1 += 384) = (b + a + 1) >> 1; /* c2 */
                *(c1 += 384) = (b + c + 1) >> 1; /* c3 */
                *(c1 += 384) = (b + d + 1) >> 1; /* c4 */

                b = *bl++;

                *(c1 += 384) = (c + d + 1) >> 1;  /* c5 */
                *(c1 += 384) = (b + d + 1) >> 1;  /* c6 */
                *(c1 += 384) = (b + c + 1) >> 1;  /* c7 */
                *(c1 += 384) = (b + a + 1) >> 1;  /* c8 */

                c1 += offset;
            }
            // advance to the next line, pitch is 24
            tl += 8;
            tr += 8;
            bl += 8;
            br += 8;
            c1 += 8;
        }
    }
    else // star pattern
    {
        j = 16;
        while (j--)
        {
            i = 16;
            while (i--)
            {
                a = *br++;
                b = *tr++;
                c = tl[1];
                *c1 = (a + b + 1) >> 1;
                b = bl[1];
                *(c1 += 384) = (a + c + 1) >> 1; /* c2 */
                c = tl[25];
                *(c1 += 384) = (a + b + 1) >> 1; /* c3 */
                b = tr[23];
                *(c1 += 384) = (a + c + 1) >> 1; /* c4 */
                c = tl[24];
                *(c1 += 384) = (a + b + 1) >> 1; /* c5 */
                b = *bl++;
                *(c1 += 384) = (a + c + 1) >> 1; /* c6 */
                c = *tl++;
                *(c1 += 384) = (a + b + 1) >> 1; /* c7 */
                *(c1 += 384) = (a + c + 1) >> 1; /* c8 */

                c1 += offset;
            }
            // advance to the next line, pitch is 24
            tl += 8;
            tr += 8;
            bl += 8;
            br += 8;
            c1 += 8;
        }
    }

    return ;
}


/* assuming cand always has a pitch of 24 */
int SATD_MB(uint8 *cand, uint8 *cur, int dmin)
{
    int cost;


    dmin = (dmin << 16) | 24;
    cost = AVCSAD_Macroblock_C(cand, cur, dmin, NULL);

    return cost;
}





