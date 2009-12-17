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

void Deringing_Luma(
    uint8 *Rec_Y,
    int width,
    int height,
    int16 *QP_store,
    int,
    uint8 *pp_mod)
{
    /*----------------------------------------------------------------------------
    ; Define all local variables
    ----------------------------------------------------------------------------*/
    int thres[4], range[4], max_range_blk, max_thres_blk;
    int MB_V, MB_H, BLK_V, BLK_H;
    int v_blk, h_blk;
    int max_diff;
    int max_blk, min_blk;
    int v0, h0;
    uint8 *ptr;
    int thr, blks, incr;
    int mb_indx, blk_indx;

    /*----------------------------------------------------------------------------
    ; Function body here
    ----------------------------------------------------------------------------*/
    incr = width - BLKSIZE;

    /* Dering the first line of macro blocks */
    for (MB_H = 0; MB_H < width; MB_H += MBSIZE)
    {
        max_diff = (QP_store[(MB_H)>>4] >> 2) + 4;

        /* threshold determination */
        max_range_blk = max_thres_blk = 0;
        blks = 0;

        for (BLK_V = 0; BLK_V < MBSIZE; BLK_V += BLKSIZE)
        {
            for (BLK_H = 0; BLK_H < MBSIZE; BLK_H += BLKSIZE)
            {
                ptr = &Rec_Y[(int32)(BLK_V) * width + MB_H + BLK_H];
                FindMaxMin(ptr, &min_blk, &max_blk, incr);

                thres[blks] = (max_blk + min_blk + 1) >> 1;
                range[blks] = max_blk - min_blk;

                if (range[blks] >= max_range_blk)
                {
                    max_range_blk = range[blks];
                    max_thres_blk = thres[blks];
                }
                blks++;
            }
        }

        blks = 0;
        for (v_blk = 0; v_blk < MBSIZE; v_blk += BLKSIZE)
        {
            v0 = ((v_blk - 1) >= 1) ? (v_blk - 1) : 1;
            for (h_blk = MB_H; h_blk < MB_H + MBSIZE; h_blk += BLKSIZE)
            {
                h0 = ((h_blk - 1) >= 1) ? (h_blk - 1) : 1;

                /* threshold rearrangement for flat region adjacent to non-flat region */
                if (range[blks]<32 && max_range_blk >= 64)
                    thres[blks] = max_thres_blk;

                /* threshold rearrangement for deblocking
                (blockiness annoying at DC dominant region) */
                if (max_range_blk >= 16)
                {
                    /* adaptive smoothing */
                    thr = thres[blks];

                    AdaptiveSmooth_NoMMX(Rec_Y, v0, h0, v_blk, h_blk,
                                         thr, width, max_diff);
                }
                blks++;
            } /* block level (Luminance) */
        }
    } /* macroblock level */


    /* Do the rest of the macro-block-lines */
    for (MB_V = MBSIZE; MB_V < height; MB_V += MBSIZE)
    {
        /* First macro-block */
        max_diff = (QP_store[((((int32)MB_V*width)>>4))>>4] >> 2) + 4;
        /* threshold determination */
        max_range_blk = max_thres_blk = 0;
        blks = 0;
        for (BLK_V = 0; BLK_V < MBSIZE; BLK_V += BLKSIZE)
        {
            for (BLK_H = 0; BLK_H < MBSIZE; BLK_H += BLKSIZE)
            {
                ptr = &Rec_Y[(int32)(MB_V + BLK_V) * width + BLK_H];
                FindMaxMin(ptr, &min_blk, &max_blk, incr);
                thres[blks] = (max_blk + min_blk + 1) >> 1;
                range[blks] = max_blk - min_blk;

                if (range[blks] >= max_range_blk)
                {
                    max_range_blk = range[blks];
                    max_thres_blk = thres[blks];
                }
                blks++;
            }
        }

        blks = 0;
        for (v_blk = MB_V; v_blk < MB_V + MBSIZE; v_blk += BLKSIZE)
        {
            v0 = v_blk - 1;
            for (h_blk = 0; h_blk < MBSIZE; h_blk += BLKSIZE)
            {
                h0 = ((h_blk - 1) >= 1) ? (h_blk - 1) : 1;

                /* threshold rearrangement for flat region adjacent to non-flat region */
                if (range[blks]<32 && max_range_blk >= 64)
                    thres[blks] = max_thres_blk;

                /* threshold rearrangement for deblocking
                (blockiness annoying at DC dominant region) */
                if (max_range_blk >= 16)
                {
                    /* adaptive smoothing */
                    thr = thres[blks];

                    AdaptiveSmooth_NoMMX(Rec_Y, v0, h0, v_blk, h_blk,
                                         thr, width, max_diff);
                }
                blks++;
            }
        } /* block level (Luminance) */

        /* Rest of the macro-blocks */
        for (MB_H = MBSIZE; MB_H < width; MB_H += MBSIZE)
        {
            max_diff = (QP_store[((((int32)MB_V*width)>>4)+MB_H)>>4] >> 2) + 4;

            /* threshold determination */
            max_range_blk = max_thres_blk = 0;
            blks = 0;

            mb_indx = (MB_V / 8) * (width / 8) + MB_H / 8;
            for (BLK_V = 0; BLK_V < MBSIZE; BLK_V += BLKSIZE)
            {
                for (BLK_H = 0; BLK_H < MBSIZE; BLK_H += BLKSIZE)
                {
                    blk_indx = mb_indx + (BLK_V / 8) * width / 8 + BLK_H / 8;
                    /* Update based on pp_mod only */
                    if ((pp_mod[blk_indx]&0x4) != 0)
                    {
                        ptr = &Rec_Y[(int32)(MB_V + BLK_V) * width + MB_H + BLK_H];
                        FindMaxMin(ptr, &min_blk, &max_blk, incr);
                        thres[blks] = (max_blk + min_blk + 1) >> 1;
                        range[blks] = max_blk - min_blk;

                        if (range[blks] >= max_range_blk)
                        {
                            max_range_blk = range[blks];
                            max_thres_blk = thres[blks];
                        }
                    }
                    blks++;
                }
            }

            blks = 0;
            for (v_blk = MB_V; v_blk < MB_V + MBSIZE; v_blk += BLKSIZE)
            {
                v0 = v_blk - 1;
                mb_indx = (v_blk / 8) * (width / 8);
                for (h_blk = MB_H; h_blk < MB_H + MBSIZE; h_blk += BLKSIZE)
                {
                    h0 = h_blk - 1;
                    blk_indx = mb_indx + h_blk / 8;
                    if ((pp_mod[blk_indx]&0x4) != 0)
                    {
                        /* threshold rearrangement for flat region adjacent to non-flat region */
                        if (range[blks]<32 && max_range_blk >= 64)
                            thres[blks] = max_thres_blk;

                        /* threshold rearrangement for deblocking
                        (blockiness annoying at DC dominant region) */
                        if (max_range_blk >= 16)
                        {
                            /* adaptive smoothing */
                            thr = thres[blks];
#ifdef NoMMX
                            AdaptiveSmooth_NoMMX(Rec_Y, v0, h0, v_blk, h_blk,
                                                 thr, width, max_diff);
#else
                            DeringAdaptiveSmoothMMX(&Rec_Y[v0*width+h0],
                                                    width, thr, max_diff);
#endif
                        }
                    }
                    blks++;
                }
            } /* block level (Luminance) */
        } /* macroblock level */
    } /* macroblock level */

    /*----------------------------------------------------------------------------
    ; Return nothing or data or data pointer
    ----------------------------------------------------------------------------*/
    return;
}
#endif
