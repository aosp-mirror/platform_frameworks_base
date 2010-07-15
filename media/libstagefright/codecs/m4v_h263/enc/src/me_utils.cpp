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
#include "mp4enc_lib.h"
#include "mp4lib_int.h"
#include "m4venc_oscl.h"

#define VOP_OFFSET  ((lx<<4)+16)  /* for offset to image area */
#define CVOP_OFFSET ((lx<<2)+8)

#define PREF_INTRA  512     /* bias for INTRA coding */

/*===============================================================
    Function:   ChooseMode
    Date:       09/21/2000
    Purpose:    Choosing between INTRA or INTER
    Input/Output: Pointer to the starting point of the macroblock.
    Note:
===============================================================*/
void ChooseMode_C(UChar *Mode, UChar *cur, Int lx, Int min_SAD)
{
    Int i, j;
    Int MB_mean, A, tmp, Th;
    Int offset = (lx >> 2) - 4;
    UChar *p = cur;
    Int *pint = (Int *) cur, temp = 0;
    MB_mean = 0;
    A = 0;
    Th = (min_SAD - PREF_INTRA) >> 1;

    for (j = 0; j < 8; j++)
    {

        /* Odd Rows */
        temp += (*pint++) & 0x00FF00FF;
        temp += (*pint++) & 0x00FF00FF;
        temp += (*pint++) & 0x00FF00FF;
        temp += (*pint++) & 0x00FF00FF;
        pint += offset;

        /* Even Rows */
        temp += (*pint++ >> 8) & 0x00FF00FF;
        temp += (*pint++ >> 8) & 0x00FF00FF;
        temp += (*pint++ >> 8) & 0x00FF00FF;
        temp += (*pint++ >> 8) & 0x00FF00FF;
        pint += offset;

    }

    MB_mean = (((temp & 0x0000FFFF)) + ((temp & 0xFFFF0000) >> 16)) >> 7;

    p = cur;
    offset = lx - 16;
    for (j = 0; j < 16; j++)
    {
        temp = (j & 1);
        p += temp;
        i = 8;
        while (i--)
        {
            tmp = *p - MB_mean;
            p += 2;
            if (tmp > 0) A += tmp;
            else    A -= tmp;
        }

        if (A >= Th)
        {
            *Mode = MODE_INTER;
            return ;
        }
        p += (offset - temp);
    }

    if (A < Th)
        *Mode = MODE_INTRA;
    else
        *Mode = MODE_INTER;

    return ;
}


/*===============================================================
    Function:   GetHalfPelMBRegion
    Date:       09/17/2000
    Purpose:    Interpolate the search region for half-pel search
    Input/Output:   Center of the search, Half-pel memory, width
    Note:       rounding type should be parameterized.
                Now fixed it to zero!!!!!!

===============================================================*/


void GetHalfPelMBRegion_C(UChar *cand, UChar *hmem, Int lx)
{
    Int i, j;
    UChar *p1, *p2, *p3, *p4;
    UChar *hmem1 = hmem;
    UChar *hmem2 = hmem1 + 33;
    Int offset = lx - 17;

    p1 = cand - lx - 1;
    p2 = cand - lx;
    p3 = cand - 1;
    p4 = cand;

    for (j = 0; j < 16; j++)
    {
        for (i = 0; i < 16; i++)
        {
            *hmem1++ = ((*p1++) + *p2 + *p3 + *p4 + 2) >> 2;
            *hmem1++ = ((*p2++) + *p4 + 1) >> 1;
            *hmem2++ = ((*p3++) + *p4 + 1) >> 1;
            *hmem2++ = *p4++;
        }
        /*  last pixel */
        *hmem1++ = ((*p1++) + (*p2++) + *p3 + *p4 + 2) >> 2;
        *hmem2++ = ((*p3++) + (*p4++) + 1) >> 1;
        hmem1 += 33;
        hmem2 += 33;
        p1 += offset;
        p2 += offset;
        p3 += offset;
        p4 += offset;
    }
    /* last row */
    for (i = 0; i < 16; i++)
    {
        *hmem1++ = ((*p1++) + *p2 + (*p3++) + *p4 + 2) >> 2;
        *hmem1++ = ((*p2++) + (*p4++) + 1) >> 1;

    }
    *hmem1 = (*p1 + *p2 + *p3 + *p4 + 2) >> 2;

    return ;
}

/*===============================================================
   Function:    GetHalfPelBlkRegion
   Date:        09/20/2000
   Purpose: Interpolate the search region for half-pel search
            in 4MV mode.
   Input/Output:    Center of the search, Half-pel memory, width
   Note:        rounding type should be parameterized.
            Now fixed it to zero!!!!!!

===============================================================*/


void GetHalfPelBlkRegion(UChar *cand, UChar *hmem, Int lx)
{
    Int i, j;
    UChar *p1, *p2, *p3, *p4;
    UChar *hmem1 = hmem;
    UChar *hmem2 = hmem1 + 17;
    Int offset = lx - 9;

    p1 = cand - lx - 1;
    p2 = cand - lx;
    p3 = cand - 1;
    p4 = cand;

    for (j = 0; j < 8; j++)
    {
        for (i = 0; i < 8; i++)
        {
            *hmem1++ = ((*p1++) + *p2 + *p3 + *p4 + 2) >> 2;
            *hmem1++ = ((*p2++) + *p4 + 1) >> 1;
            *hmem2++ = ((*p3++) + *p4 + 1) >> 1;
            *hmem2++ = *p4++;
        }
        /*  last pixel */
        *hmem1++ = ((*p1++) + (*p2++) + *p3 + *p4 + 2) >> 2;
        *hmem2++ = ((*p3++) + (*p4++) + 1) >> 1;
        hmem1 += 17;
        hmem2 += 17;
        p1 += offset;
        p2 += offset;
        p3 += offset;
        p4 += offset;
    }
    /* last row */
    for (i = 0; i < 8; i++)
    {
        *hmem1++ = ((*p1++) + *p2 + (*p3++) + *p4 + 2) >> 2;
        *hmem1++ = ((*p2++) + (*p4++) + 1) >> 1;

    }
    *hmem1 = (*p1 + *p2 + *p3 + *p4 + 2) >> 2;

    return ;
}


/*=====================================================================
    Function:   PaddingEdge
    Date:       09/16/2000
    Purpose:    Pad edge of a Vop
    Modification: 09/20/05.
=====================================================================*/

void  PaddingEdge(Vop *refVop)
{
    UChar *src, *dst;
    Int i;
    Int pitch, width, height;
    ULong temp1, temp2;

    width = refVop->width;
    height = refVop->height;
    pitch = refVop->pitch;

    /* pad top */
    src = refVop->yChan;

    temp1 = *src; /* top-left corner */
    temp2 = src[width-1]; /* top-right corner */
    temp1 |= (temp1 << 8);
    temp1 |= (temp1 << 16);
    temp2 |= (temp2 << 8);
    temp2 |= (temp2 << 16);

    dst = src - (pitch << 4);

    *((ULong*)(dst - 16)) = temp1;
    *((ULong*)(dst - 12)) = temp1;
    *((ULong*)(dst - 8)) = temp1;
    *((ULong*)(dst - 4)) = temp1;

    M4VENC_MEMCPY(dst, src, width);

    *((ULong*)(dst += width)) = temp2;
    *((ULong*)(dst + 4)) = temp2;
    *((ULong*)(dst + 8)) = temp2;
    *((ULong*)(dst + 12)) = temp2;

    dst = dst - width - 16;

    i = 15;
    while (i--)
    {
        M4VENC_MEMCPY(dst + pitch, dst, pitch);
        dst += pitch;
    }

    /* pad sides */
    dst += (pitch + 16);
    src = dst;
    i = height;
    while (i--)
    {
        temp1 = *src;
        temp2 = src[width-1];
        temp1 |= (temp1 << 8);
        temp1 |= (temp1 << 16);
        temp2 |= (temp2 << 8);
        temp2 |= (temp2 << 16);

        *((ULong*)(dst - 16)) = temp1;
        *((ULong*)(dst - 12)) = temp1;
        *((ULong*)(dst - 8)) = temp1;
        *((ULong*)(dst - 4)) = temp1;

        *((ULong*)(dst += width)) = temp2;
        *((ULong*)(dst + 4)) = temp2;
        *((ULong*)(dst + 8)) = temp2;
        *((ULong*)(dst + 12)) = temp2;

        src += pitch;
        dst = src;
    }

    /* pad bottom */
    dst -= 16;
    i = 16;
    while (i--)
    {
        M4VENC_MEMCPY(dst, dst - pitch, pitch);
        dst += pitch;
    }


    return ;
}

/*===================================================================
    Function:   ComputeMBSum
    Date:       10/28/2000
    Purpose:    Compute sum of absolute value (SAV) of blocks in a macroblock
                in INTRA mode needed for rate control. Thus, instead of
                computing the SAV, we can compute first order moment or
                variance .

    11/28/00:    add MMX
    9/3/01:      do parallel comp for C function.
===================================================================*/
void ComputeMBSum_C(UChar *cur, Int lx, MOT *mot_mb)
{
    Int j;
    Int *cInt, *cInt2;
    Int sad1 = 0, sad2 = 0, sad3 = 0, sad4 = 0;
    Int tmp, tmp2, mask = 0x00FF00FF;

    cInt = (Int*)cur;   /* make sure this is word-align */
    cInt2 = (Int*)(cur + (lx << 3));
    j = 8;
    while (j--)
    {
        tmp = cInt[3];  /* load 4 pixels at a time */
        tmp2 = tmp & mask;
        tmp = (tmp >> 8) & mask;
        tmp += tmp2;
        sad2 += tmp;
        tmp = cInt[2];
        tmp2 = tmp & mask;
        tmp = (tmp >> 8) & mask;
        tmp += tmp2;
        sad2 += tmp;
        tmp = cInt[1];
        tmp2 = tmp & mask;
        tmp = (tmp >> 8) & mask;
        tmp += tmp2;
        sad1 += tmp;
        tmp = *cInt;
        cInt += (lx >> 2);
        tmp2 = tmp & mask;
        tmp = (tmp >> 8) & mask;
        tmp += tmp2;
        sad1 += tmp;

        tmp = cInt2[3];
        tmp2 = tmp & mask;
        tmp = (tmp >> 8) & mask;
        tmp += tmp2;
        sad4 += tmp;
        tmp = cInt2[2];
        tmp2 = tmp & mask;
        tmp = (tmp >> 8) & mask;
        tmp += tmp2;
        sad4 += tmp;
        tmp = cInt2[1];
        tmp2 = tmp & mask;
        tmp = (tmp >> 8) & mask;
        tmp += tmp2;
        sad3 += tmp;
        tmp = *cInt2;
        cInt2 += (lx >> 2);
        tmp2 = tmp & mask;
        tmp = (tmp >> 8) & mask;
        tmp += tmp2;
        sad3 += tmp;
    }
    sad1 += (sad1 << 16);
    sad2 += (sad2 << 16);
    sad3 += (sad3 << 16);
    sad4 += (sad4 << 16);
    sad1 >>= 16;
    sad2 >>= 16;
    sad3 >>= 16;
    sad4 >>= 16;

    mot_mb[1].sad = sad1;
    mot_mb[2].sad = sad2;
    mot_mb[3].sad = sad3;
    mot_mb[4].sad = sad4;
    mot_mb[0].sad = sad1 + sad2 + sad3 + sad4;

    return ;
}

