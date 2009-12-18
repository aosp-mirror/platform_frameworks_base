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
#include    "mp4dec_lib.h"
#include    "post_proc.h"

#ifdef PV_POSTPROC_ON

void Deringing_Chroma(
    uint8 *Rec_C,
    int width,
    int height,
    int16 *QP_store,
    int,
    uint8 *pp_mod
)
{
    /*----------------------------------------------------------------------------
    ; Define all local variables
    ----------------------------------------------------------------------------*/
    int thres;
    int v_blk, h_blk;
    int max_diff;
    int v_pel, h_pel;
    int max_blk, min_blk;
    int v0, h0;
    uint8 *ptr;
    int sum, sum1, incr;
    int32 addr_v;
    int sign_v[10], sum_v[10];
    int *ptr2, *ptr3;
    uint8 pelu, pelc, pell;
    incr = width - BLKSIZE;

    /*----------------------------------------------------------------------------
    ; Function body here
    ----------------------------------------------------------------------------*/
    /* chrominance */
    /* Do the first line (7 pixels at a time => Don't use MMX)*/
    for (h_blk = 0; h_blk < width; h_blk += BLKSIZE)
    {
        max_diff = (QP_store[h_blk>>3] >> 2) + 4;
        ptr = &Rec_C[h_blk];
        max_blk = min_blk = *ptr;
        FindMaxMin(ptr, &min_blk, &max_blk, width);
        h0 = ((h_blk - 1) >= 1) ? (h_blk - 1) : 1;

        if (max_blk - min_blk >= 4)
        {
            thres = (max_blk + min_blk + 1) >> 1;


            for (v_pel = 1; v_pel < BLKSIZE - 1; v_pel++)
            {
                addr_v = (int32)v_pel * width;
                ptr = &Rec_C[addr_v + h0 - 1];
                ptr2 = &sum_v[0];
                ptr3 = &sign_v[0];

                pelu = *(ptr - width);
                pelc = *ptr;
                pell = *(ptr + width);
                ptr++;
                *ptr2++ = pelu + (pelc << 1) + pell;
                *ptr3++ = INDEX(pelu, thres) + INDEX(pelc, thres) + INDEX(pell, thres);

                pelu = *(ptr - width);
                pelc = *ptr;
                pell = *(ptr + width);
                ptr++;
                *ptr2++ = pelu + (pelc << 1) + pell;
                *ptr3++ = INDEX(pelu, thres) + INDEX(pelc, thres) + INDEX(pell, thres);

                for (h_pel = h0; h_pel < h_blk + BLKSIZE - 1; h_pel++)
                {
                    pelu = *(ptr - width);
                    pelc = *ptr;
                    pell = *(ptr + width);

                    *ptr2 = pelu + (pelc << 1) + pell;
                    *ptr3 = INDEX(pelu, thres) + INDEX(pelc, thres) + INDEX(pell, thres);

                    sum1 = *(ptr3 - 2) + *(ptr3 - 1) + *ptr3;
                    if (sum1 == 0 || sum1 == 9)
                    {
                        sum = (*(ptr2 - 2) + (*(ptr2 - 1) << 1) + *ptr2 + 8) >> 4;

                        ptr--;
                        if (PV_ABS(*ptr - sum) > max_diff)
                        {
                            if (sum > *ptr)
                                sum = *ptr + max_diff;
                            else
                                sum = *ptr - max_diff;
                        }
                        *ptr++ = (uint8) sum;
                    }
                    ptr++;
                    ptr2++;
                    ptr3++;
                }
            }
        }
    }

    for (v_blk = BLKSIZE; v_blk < height; v_blk += BLKSIZE)
    {
        v0 = v_blk - 1;
        /* Do the first block (pixels=7 => No MMX) */
        max_diff = (QP_store[((((int32)v_blk*width)>>3))>>3] >> 2) + 4;
        ptr = &Rec_C[(int32)v_blk * width];
        max_blk = min_blk = *ptr;
        FindMaxMin(ptr, &min_blk, &max_blk, incr);

        if (max_blk - min_blk >= 4)
        {
            thres = (max_blk + min_blk + 1) >> 1;

            for (v_pel = v0; v_pel < v_blk + BLKSIZE - 1; v_pel++)
            {
                addr_v = v_pel * width;
                ptr = &Rec_C[addr_v];
                ptr2 = &sum_v[0];
                ptr3 = &sign_v[0];

                pelu = *(ptr - width);
                pelc = *ptr;
                pell = *(ptr + width);
                ptr++;
                *ptr2++ = pelu + (pelc << 1) + pell;
                *ptr3++ = INDEX(pelu, thres) + INDEX(pelc, thres) + INDEX(pell, thres);

                pelu = *(ptr - width);
                pelc = *ptr;
                pell = *(ptr + width);
                ptr++;
                *ptr2++ = pelu + (pelc << 1) + pell;
                *ptr3++ = INDEX(pelu, thres) + INDEX(pelc, thres) + INDEX(pell, thres);

                for (h_pel = 1; h_pel < BLKSIZE - 1; h_pel++)
                {
                    pelu = *(ptr - width);
                    pelc = *ptr;
                    pell = *(ptr + width);

                    *ptr2 = pelu + (pelc << 1) + pell;
                    *ptr3 = INDEX(pelu, thres) + INDEX(pelc, thres) + INDEX(pell, thres);

                    sum1 = *(ptr3 - 2) + *(ptr3 - 1) + *ptr3;
                    if (sum1 == 0 || sum1 == 9)
                    {
                        sum = (*(ptr2 - 2) + (*(ptr2 - 1) << 1) + *ptr2 + 8) >> 4;

                        ptr--;
                        if (PV_ABS(*ptr - sum) > max_diff)
                        {
                            if (sum > *ptr)
                                sum = *ptr + max_diff;
                            else
                                sum = *ptr - max_diff;
                        }
                        *ptr++ = (uint8) sum;
                    }
                    ptr++;
                    ptr2++;
                    ptr3++;
                }
            }
        }


        /* Do the rest in MMX */
        for (h_blk = BLKSIZE; h_blk < width; h_blk += BLKSIZE)
        {
            if ((pp_mod[(v_blk/8)*(width/8)+h_blk/8]&0x4) != 0)
            {
                max_diff = (QP_store[((((int32)v_blk*width)>>3)+h_blk)>>3] >> 2) + 4;
                ptr = &Rec_C[(int32)v_blk * width + h_blk];
                max_blk = min_blk = *ptr;
                FindMaxMin(ptr, &min_blk, &max_blk, incr);
                h0 = h_blk - 1;

                if (max_blk - min_blk >= 4)
                {
                    thres = (max_blk + min_blk + 1) >> 1;
#ifdef NoMMX
                    AdaptiveSmooth_NoMMX(Rec_C, v0, h0, v_blk, h_blk, thres, width, max_diff);
#else
                    DeringAdaptiveSmoothMMX(&Rec_C[(int32)v0*width+h0], width, thres, max_diff);
#endif
                }
            }
        }
    } /* macroblock level */

    /*----------------------------------------------------------------------------
    ; Return nothing or data or data pointer
    ----------------------------------------------------------------------------*/
    return;
}
#endif
