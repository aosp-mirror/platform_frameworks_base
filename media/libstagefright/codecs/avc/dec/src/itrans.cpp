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
#include "avclib_common.h"

/* input are in the first 16 elements of block,
   output must be in the location specified in Figure 8-6. */
/* subclause 8.5.6 */
void Intra16DCTrans(int16 *block, int Qq, int Rq)
{
    int m0, m1, m2, m3;
    int j, offset;
    int16 *inout;
    int scale = dequant_coefres[Rq][0];

    inout = block;
    for (j = 0; j < 4; j++)
    {
        m0 = inout[0] + inout[4];
        m1 = inout[0] - inout[4];
        m2 = inout[8] + inout[12];
        m3 = inout[8] - inout[12];


        inout[0] = m0 + m2;
        inout[4] = m0 - m2;
        inout[8] = m1 - m3;
        inout[12] = m1 + m3;
        inout += 64;
    }

    inout = block;

    if (Qq >= 2)  /* this way should be faster than JM */
    {           /* they use (((m4*scale)<<(QPy/6))+2)>>2 for both cases. */
        Qq -= 2;
        for (j = 0; j < 4; j++)
        {
            m0 = inout[0] + inout[64];
            m1 = inout[0] - inout[64];
            m2 = inout[128] + inout[192];
            m3 = inout[128] - inout[192];

            inout[0] = ((m0 + m2) * scale) << Qq;
            inout[64] = ((m0 - m2) * scale) << Qq;
            inout[128] = ((m1 - m3) * scale) << Qq;
            inout[192] = ((m1 + m3) * scale) << Qq;
            inout += 4;
        }
    }
    else
    {
        Qq = 2 - Qq;
        offset = 1 << (Qq - 1);

        for (j = 0; j < 4; j++)
        {
            m0 = inout[0] + inout[64];
            m1 = inout[0] - inout[64];
            m2 = inout[128] + inout[192];
            m3 = inout[128] - inout[192];

            inout[0] = (((m0 + m2) * scale + offset) >> Qq);
            inout[64] = (((m0 - m2) * scale + offset) >> Qq);
            inout[128] = (((m1 - m3) * scale + offset) >> Qq);
            inout[192] = (((m1 + m3) * scale + offset) >> Qq);
            inout += 4;
        }
    }

    return ;
}

/* see subclase 8.5.8 */
void itrans(int16 *block, uint8 *pred, uint8 *cur, int width)
{
    int e0, e1, e2, e3; /* note, at every step of the calculation, these values */
    /* shall never exceed 16bit sign value, but we don't check */
    int i;           /* to save the cycles. */
    int16 *inout;

    inout = block;

    for (i = 4; i > 0; i--)
    {
        e0 = inout[0] + inout[2];
        e1 = inout[0] - inout[2];
        e2 = (inout[1] >> 1) - inout[3];
        e3 = inout[1] + (inout[3] >> 1);

        inout[0] = e0 + e3;
        inout[1] = e1 + e2;
        inout[2] = e1 - e2;
        inout[3] = e0 - e3;

        inout += 16;
    }

    for (i = 4; i > 0; i--)
    {
        e0 = block[0] + block[32];
        e1 = block[0] - block[32];
        e2 = (block[16] >> 1) - block[48];
        e3 = block[16] + (block[48] >> 1);

        e0 += e3;
        e3 = (e0 - (e3 << 1)); /* e0-e3 */
        e1 += e2;
        e2 = (e1 - (e2 << 1)); /* e1-e2 */
        e0 += 32;
        e1 += 32;
        e2 += 32;
        e3 += 32;
#ifdef USE_PRED_BLOCK
        e0 = pred[0] + (e0 >> 6);
        if ((uint)e0 > 0xFF)   e0 = 0xFF & (~(e0 >> 31));  /* clip */
        e1 = pred[20] + (e1 >> 6);
        if ((uint)e1 > 0xFF)   e1 = 0xFF & (~(e1 >> 31));  /* clip */
        e2 = pred[40] + (e2 >> 6);
        if ((uint)e2 > 0xFF)   e2 = 0xFF & (~(e2 >> 31));  /* clip */
        e3 = pred[60] + (e3 >> 6);
        if ((uint)e3 > 0xFF)   e3 = 0xFF & (~(e3 >> 31));  /* clip */
        *cur = e0;
        *(cur += width) = e1;
        *(cur += width) = e2;
        cur[width] = e3;
        cur -= (width << 1);
        cur++;
        pred++;
#else
        OSCL_UNUSED_ARG(pred);

        e0 = *cur + (e0 >> 6);
        if ((uint)e0 > 0xFF)   e0 = 0xFF & (~(e0 >> 31));  /* clip */
        *cur = e0;
        e1 = *(cur += width) + (e1 >> 6);
        if ((uint)e1 > 0xFF)   e1 = 0xFF & (~(e1 >> 31));  /* clip */
        *cur = e1;
        e2 = *(cur += width) + (e2 >> 6);
        if ((uint)e2 > 0xFF)   e2 = 0xFF & (~(e2 >> 31));  /* clip */
        *cur = e2;
        e3 = cur[width] + (e3 >> 6);
        if ((uint)e3 > 0xFF)   e3 = 0xFF & (~(e3 >> 31));  /* clip */
        cur[width] = e3;
        cur -= (width << 1);
        cur++;
#endif
        block++;
    }

    return ;
}

/* see subclase 8.5.8 */
void ictrans(int16 *block, uint8 *pred, uint8 *cur, int width)
{
    int e0, e1, e2, e3; /* note, at every step of the calculation, these values */
    /* shall never exceed 16bit sign value, but we don't check */
    int i;           /* to save the cycles. */
    int16 *inout;

    inout = block;

    for (i = 4; i > 0; i--)
    {
        e0 = inout[0] + inout[2];
        e1 = inout[0] - inout[2];
        e2 = (inout[1] >> 1) - inout[3];
        e3 = inout[1] + (inout[3] >> 1);

        inout[0] = e0 + e3;
        inout[1] = e1 + e2;
        inout[2] = e1 - e2;
        inout[3] = e0 - e3;

        inout += 16;
    }

    for (i = 4; i > 0; i--)
    {
        e0 = block[0] + block[32];
        e1 = block[0] - block[32];
        e2 = (block[16] >> 1) - block[48];
        e3 = block[16] + (block[48] >> 1);

        e0 += e3;
        e3 = (e0 - (e3 << 1)); /* e0-e3 */
        e1 += e2;
        e2 = (e1 - (e2 << 1)); /* e1-e2 */
        e0 += 32;
        e1 += 32;
        e2 += 32;
        e3 += 32;
#ifdef USE_PRED_BLOCK
        e0 = pred[0] + (e0 >> 6);
        if ((uint)e0 > 0xFF)   e0 = 0xFF & (~(e0 >> 31));  /* clip */
        e1 = pred[12] + (e1 >> 6);
        if ((uint)e1 > 0xFF)   e1 = 0xFF & (~(e1 >> 31));  /* clip */
        e2 = pred[24] + (e2 >> 6);
        if ((uint)e2 > 0xFF)   e2 = 0xFF & (~(e2 >> 31));  /* clip */
        e3 = pred[36] + (e3 >> 6);
        if ((uint)e3 > 0xFF)   e3 = 0xFF & (~(e3 >> 31));  /* clip */
        *cur = e0;
        *(cur += width) = e1;
        *(cur += width) = e2;
        cur[width] = e3;
        cur -= (width << 1);
        cur++;
        pred++;
#else
        OSCL_UNUSED_ARG(pred);

        e0 = *cur + (e0 >> 6);
        if ((uint)e0 > 0xFF)   e0 = 0xFF & (~(e0 >> 31));  /* clip */
        *cur = e0;
        e1 = *(cur += width) + (e1 >> 6);
        if ((uint)e1 > 0xFF)   e1 = 0xFF & (~(e1 >> 31));  /* clip */
        *cur = e1;
        e2 = *(cur += width) + (e2 >> 6);
        if ((uint)e2 > 0xFF)   e2 = 0xFF & (~(e2 >> 31));  /* clip */
        *cur = e2;
        e3 = cur[width] + (e3 >> 6);
        if ((uint)e3 > 0xFF)   e3 = 0xFF & (~(e3 >> 31));  /* clip */
        cur[width] = e3;
        cur -= (width << 1);
        cur++;
#endif
        block++;
    }

    return ;
}

/* see subclause 8.5.7 */
void ChromaDCTrans(int16 *block, int Qq, int Rq)
{
    int c00, c01, c10, c11;
    int f0, f1, f2, f3;
    int scale = dequant_coefres[Rq][0];

    c00 = block[0] + block[4];
    c01 = block[0] - block[4];
    c10 = block[64] + block[68];
    c11 = block[64] - block[68];

    f0 = c00 + c10;
    f1 = c01 + c11;
    f2 = c00 - c10;
    f3 = c01 - c11;

    if (Qq >= 1)
    {
        Qq -= 1;
        block[0] = (f0 * scale) << Qq;
        block[4] = (f1 * scale) << Qq;
        block[64] = (f2 * scale) << Qq;
        block[68] = (f3 * scale) << Qq;
    }
    else
    {
        block[0] = (f0 * scale) >> 1;
        block[4] = (f1 * scale) >> 1;
        block[64] = (f2 * scale) >> 1;
        block[68] = (f3 * scale) >> 1;
    }

    return ;
}


void copy_block(uint8 *pred, uint8 *cur, int width, int pred_pitch)
{
    uint32 temp;

    temp = *((uint32*)pred);
    pred += pred_pitch;
    *((uint32*)cur) = temp;
    cur += width;
    temp = *((uint32*)pred);
    pred += pred_pitch;
    *((uint32*)cur) = temp;
    cur += width;
    temp = *((uint32*)pred);
    pred += pred_pitch;
    *((uint32*)cur) = temp;
    cur += width;
    temp = *((uint32*)pred);
    *((uint32*)cur) = temp;

    return ;
}


