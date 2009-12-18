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

void CombinedHorzVertRingFilter(
    uint8 *rec,
    int width,
    int height,
    int16 *QP_store,
    int chr,
    uint8 *pp_mod)
{

    /*----------------------------------------------------------------------------
    ; Define all local variables
    ----------------------------------------------------------------------------*/
    int index, counter;
    int br, bc, incr, mbr, mbc;
    int QP = 1;
    int v[5];
    uint8 *ptr, *ptr_c, *ptr_n;
    int w1, w2, w3, w4;
    int pp_w, pp_h, brwidth;
    int sum, delta;
    int a3_0, a3_1, a3_2, A3_0;
    /* for Deringing Threshold approach (MPEG4)*/
    int max_diff, thres, v0, h0, min_blk, max_blk;
    int cnthflag;

    /*----------------------------------------------------------------------------
    ; Function body here
    ----------------------------------------------------------------------------*/
    /* Calculate the width and height of the area in blocks (divide by 8) */
    pp_w = (width >> 3);
    pp_h = (height >> 3);

    /* Set up various values needed for updating pointers into rec */
    w1 = width;             /* Offset to next row in pixels */
    w2 = width << 1;        /* Offset to two rows in pixels */
    w3 = w1 + w2;           /* Offset to three rows in pixels */
    w4 = w2 << 1;           /* Offset to four rows in pixels */
    incr = width - BLKSIZE; /* Offset to next row after processing block */

    /* Work through the area hortizontally by two rows per step */
    for (mbr = 0; mbr < pp_h; mbr += 2)
    {
        /* brwidth contains the block number of the leftmost block
         * of the current row */
        brwidth = mbr * pp_w;

        /* Work through the area vertically by two columns per step */
        for (mbc = 0; mbc < pp_w; mbc += 2)
        {
            /* if the data is luminance info, get the correct
                    * quantization paramenter. One parameter per macroblock */
            if (!chr)
            {
                /* brwidth/4 is the macroblock number and mbc/2 is the macroblock col number*/
                QP = QP_store[(brwidth>>2) + (mbc>>1)];
            }

            /****************** Horiz. Filtering ********************/
            /* Process four blocks for the filtering        */
            /********************************************************/
            /* Loop over two rows of blocks */
            for (br = mbr + 1; br < mbr + 3; br++)    /* br is the row counter in blocks */
            {
                /* Set brwidth to the first (leftmost) block number of the next row */
                /* brwidth is used as an index when counting blocks */
                brwidth += pp_w;

                /* Loop over two columns of blocks in the row */
                for (bc = mbc; bc < mbc + 2; bc++)    /* bc is the column counter in blocks */
                {
                    /****** check boundary for deblocking ************/
                    /* Execute if the row and column counters are within the area */
                    if (br < pp_h && bc < pp_w)
                    {
                        /* Set the ptr to the first pixel of the first block of the second row
                        * brwidth * 64 is the pixel row offset
                        * bc * 8 is the pixel column offset */
                        ptr = rec + (brwidth << 6) + (bc << 3);

                        /* Set the index to the current block of the second row counting in blocks */
                        index = brwidth + bc;

                        /* if the data is chrominance info, get the correct
                         * quantization paramenter. One parameter per block. */
                        if (chr)
                        {
                            QP = QP_store[index];
                        }

                        /* Execute hard horizontal filter if semaphore for horizontal deblocking
                          * is set for the current block and block immediately above it */
                        if (((pp_mod[index]&0x02) != 0) && ((pp_mod[index-pp_w]&0x02) != 0))
                        {   /* Hard filter */

                            /* Set HorzHflag (bit 4) in the pp_mod location */
                            pp_mod[index-pp_w] |= 0x10; /*  4/26/00 reuse pp_mod for HorzHflag*/

                            /* Filter across the 8 pixels of the block */
                            for (index = BLKSIZE; index > 0; index--)
                            {
                                /* Difference between the current pixel and the pixel above it */
                                a3_0 = *ptr - *(ptr - w1);

                                /* if the magnitude of the difference is greater than the KThH threshold
                                 * and within the quantization parameter, apply hard filter */
                                if ((a3_0 > KThH || a3_0 < -KThH) && a3_0<QP && a3_0> -QP)
                                {
                                    ptr_c = ptr - w3;   /* Points to pixel three rows above */
                                    ptr_n = ptr + w1;   /* Points to pixel one row below */
                                    v[0] = (int)(*(ptr_c - w3));
                                    v[1] = (int)(*(ptr_c - w2));
                                    v[2] = (int)(*(ptr_c - w1));
                                    v[3] = (int)(*ptr_c);
                                    v[4] = (int)(*(ptr_c + w1));

                                    sum = v[0]
                                          + v[1]
                                          + v[2]
                                          + *ptr_c
                                          + v[4]
                                          + (*(ptr_c + w2))
                                          + (*(ptr_c + w3));  /* Current pixel */

                                    delta = (sum + *ptr_c + 4) >> 3;   /* Average pixel values with rounding */
                                    *(ptr_c) = (uint8) delta;

                                    /* Move pointer down one row of pixels (points to pixel two rows
                                     * above current pixel) */
                                    ptr_c += w1;

                                    for (counter = 0; counter < 5; counter++)
                                    {
                                        /* Subtract off highest pixel and add in pixel below */
                                        sum = sum - v[counter] + *ptr_n;
                                        /* Average the pixel values with rounding */
                                        delta = (sum + *ptr_c + 4) >> 3;
                                        *ptr_c = (uint8)(delta);

                                        /* Increment pointers to next pixel row */
                                        ptr_c += w1;
                                        ptr_n += w1;
                                    }
                                }
                                /* Increment pointer to next pixel */
                                ++ptr;
                            } /* index*/
                        }
                        else
                        { /* soft filter*/

                            /* Clear HorzHflag (bit 4) in the pp_mod location */
                            pp_mod[index-pp_w] &= 0xef; /* reset 1110,1111 */

                            for (index = BLKSIZE; index > 0; index--)
                            {
                                /* Difference between the current pixel and the pixel above it */
                                a3_0 = *(ptr) - *(ptr - w1);

                                /* if the magnitude of the difference is greater than the KTh threshold,
                                 * apply soft filter */
                                if ((a3_0 > KTh || a3_0 < -KTh))
                                {

                                    /* Sum of weighted differences */
                                    a3_0 += ((*(ptr - w2) - *(ptr + w1)) << 1) + (a3_0 << 2);

                                    /* Check if sum is less than the quantization parameter */
                                    if (PV_ABS(a3_0) < (QP << 3))
                                    {
                                        a3_1 = *(ptr - w2) - *(ptr - w3);
                                        a3_1 += ((*(ptr - w4) - *(ptr - w1)) << 1) + (a3_1 << 2);

                                        a3_2  = *(ptr + w2) - *(ptr + w1);
                                        a3_2 += ((*(ptr) - *(ptr + w3)) << 1) + (a3_2 << 2);

                                        A3_0 = PV_ABS(a3_0) - PV_MIN(PV_ABS(a3_1), PV_ABS(a3_2));

                                        if (A3_0 > 0)
                                        {
                                            A3_0 += A3_0 << 2;
                                            A3_0 = (A3_0 + 32) >> 6;
                                            if (a3_0 > 0)
                                            {
                                                A3_0 = -A3_0;
                                            }

                                            delta = (*(ptr - w1) - *(ptr)) >> 1;
                                            if (delta >= 0)
                                            {
                                                if (delta >= A3_0)
                                                {
                                                    delta = PV_MAX(A3_0, 0);
                                                }
                                            }
                                            else
                                            {
                                                if (A3_0 > 0)
                                                {
                                                    delta = 0;
                                                }
                                                else
                                                {
                                                    delta = PV_MAX(A3_0, delta);
                                                }
                                            }

                                            *(ptr - w1) = (uint8)(*(ptr - w1) - delta);
                                            *(ptr) = (uint8)(*(ptr) + delta);
                                        }
                                    } /*threshold*/
                                }
                                /* Increment pointer to next pixel */
                                ++ptr;
                            } /*index*/
                        } /* Soft filter*/
                    }/* boundary checking*/
                }/*bc*/
            }/*br*/
            brwidth -= (pp_w << 1);


            /****************** Vert. Filtering *********************/
            /* Process four blocks for the filtering        */
            /********************************************************/
            /* Loop over two rows of blocks */
            for (br = mbr; br < mbr + 2; br++)      /* br is the row counter in blocks */
            {
                for (bc = mbc + 1; bc < mbc + 3; bc++)  /* bc is the column counter in blocks */
                {
                    /****** check boundary for deblocking ************/
                    /* Execute if the row and column counters are within the area */
                    if (br < pp_h && bc < pp_w)
                    {
                        /* Set the ptr to the first pixel of the first block of the second row
                        * brwidth * 64 is the pixel row offset
                        * bc * 8 is the pixel column offset */
                        ptr = rec + (brwidth << 6) + (bc << 3);

                        /* Set the index to the current block of the second row counting in blocks */
                        index = brwidth + bc;

                        /* if the data is chrominance info, get the correct
                         * quantization paramenter. One parameter per block. */
                        if (chr)
                        {
                            QP = QP_store[index];
                        }

                        /* Execute hard vertical filter if semaphore for vertical deblocking
                          * is set for the current block and block immediately left of it */
                        if (((pp_mod[index-1]&0x01) != 0) && ((pp_mod[index]&0x01) != 0))
                        {   /* Hard filter */

                            /* Set VertHflag (bit 5) in the pp_mod location of previous block*/
                            pp_mod[index-1] |= 0x20; /*  4/26/00 reuse pp_mod for VertHflag*/

                            /* Filter across the 8 pixels of the block */
                            for (index = BLKSIZE; index > 0; index--)
                            {
                                /* Difference between the current pixel
                                * and the pixel to left of it */
                                a3_0 = *ptr - *(ptr - 1);

                                /* if the magnitude of the difference is greater than the KThH threshold
                                 * and within the quantization parameter, apply hard filter */
                                if ((a3_0 > KThH || a3_0 < -KThH) && a3_0<QP && a3_0> -QP)
                                {
                                    ptr_c = ptr - 3;
                                    ptr_n = ptr + 1;
                                    v[0] = (int)(*(ptr_c - 3));
                                    v[1] = (int)(*(ptr_c - 2));
                                    v[2] = (int)(*(ptr_c - 1));
                                    v[3] = (int)(*ptr_c);
                                    v[4] = (int)(*(ptr_c + 1));

                                    sum = v[0]
                                          + v[1]
                                          + v[2]
                                          + *ptr_c
                                          + v[4]
                                          + (*(ptr_c + 2))
                                          + (*(ptr_c + 3));

                                    delta = (sum + *ptr_c + 4) >> 3;
                                    *(ptr_c) = (uint8) delta;

                                    /* Move pointer down one pixel to the right */
                                    ptr_c += 1;
                                    for (counter = 0; counter < 5; counter++)
                                    {
                                        /* Subtract off highest pixel and add in pixel below */
                                        sum = sum - v[counter] + *ptr_n;
                                        /* Average the pixel values with rounding */
                                        delta = (sum + *ptr_c + 4) >> 3;
                                        *ptr_c = (uint8)(delta);

                                        /* Increment pointers to next pixel */
                                        ptr_c += 1;
                                        ptr_n += 1;
                                    }
                                }
                                /* Increment pointers to next pixel row */
                                ptr += w1;
                            } /* index*/
                        }
                        else
                        { /* soft filter*/

                            /* Clear VertHflag (bit 5) in the pp_mod location */
                            pp_mod[index-1] &= 0xdf; /* reset 1101,1111 */
                            for (index = BLKSIZE; index > 0; index--)
                            {
                                /* Difference between the current pixel and the pixel above it */
                                a3_0 = *(ptr) - *(ptr - 1);

                                /* if the magnitude of the difference is greater than the KTh threshold,
                                 * apply soft filter */
                                if ((a3_0 > KTh || a3_0 < -KTh))
                                {

                                    /* Sum of weighted differences */
                                    a3_0 += ((*(ptr - 2) - *(ptr + 1)) << 1) + (a3_0 << 2);

                                    /* Check if sum is less than the quantization parameter */
                                    if (PV_ABS(a3_0) < (QP << 3))
                                    {
                                        a3_1 = *(ptr - 2) - *(ptr - 3);
                                        a3_1 += ((*(ptr - 4) - *(ptr - 1)) << 1) + (a3_1 << 2);

                                        a3_2  = *(ptr + 2) - *(ptr + 1);
                                        a3_2 += ((*(ptr) - *(ptr + 3)) << 1) + (a3_2 << 2);

                                        A3_0 = PV_ABS(a3_0) - PV_MIN(PV_ABS(a3_1), PV_ABS(a3_2));

                                        if (A3_0 > 0)
                                        {
                                            A3_0 += A3_0 << 2;
                                            A3_0 = (A3_0 + 32) >> 6;
                                            if (a3_0 > 0)
                                            {
                                                A3_0 = -A3_0;
                                            }

                                            delta = (*(ptr - 1) - *(ptr)) >> 1;
                                            if (delta >= 0)
                                            {
                                                if (delta >= A3_0)
                                                {
                                                    delta = PV_MAX(A3_0, 0);
                                                }
                                            }
                                            else
                                            {
                                                if (A3_0 > 0)
                                                {
                                                    delta = 0;
                                                }
                                                else
                                                {
                                                    delta = PV_MAX(A3_0, delta);
                                                }
                                            }

                                            *(ptr - 1) = (uint8)(*(ptr - 1) - delta);
                                            *(ptr) = (uint8)(*(ptr) + delta);
                                        }
                                    } /*threshold*/
                                }
                                ptr += w1;
                            } /*index*/
                        } /* Soft filter*/
                    } /* boundary*/
                } /*bc*/
                /* Increment pointer to next row of pixels */
                brwidth += pp_w;
            }/*br*/
            brwidth -= (pp_w << 1);

            /****************** Deringing ***************************/
            /* Process four blocks for the filtering        */
            /********************************************************/
            /* Loop over two rows of blocks */
            for (br = mbr; br < mbr + 2; br++)
            {
                /* Loop over two columns of blocks in the row */
                for (bc = mbc; bc < mbc + 2; bc++)
                {
                    /* Execute if the row and column counters are within the area */
                    if (br < pp_h && bc < pp_w)
                    {
                        /* Set the index to the current block */
                        index = brwidth + bc;

                        /* Execute deringing if semaphore for deringing (bit-3 of pp_mod)
                         * is set for the current block */
                        if ((pp_mod[index]&0x04) != 0)
                        {
                            /* Don't process deringing if on an edge block */
                            if (br > 0 && bc > 0 && br < pp_h - 1 && bc < pp_w - 1)
                            {
                                /* cnthflag = weighted average of HorzHflag of current,
                                 * one above, previous blocks*/
                                cnthflag = ((pp_mod[index] & 0x10) +
                                            (pp_mod[index-pp_w] & 0x10) +
                                            ((pp_mod[index-1] >> 1) & 0x10) +
                                            ((pp_mod[index] >> 1) & 0x10)) >> 4; /* 4/26/00*/

                                /* Do the deringing if decision flags indicate it's necessary */
                                if (cnthflag < 3)
                                {
                                    /* if the data is chrominance info, get the correct
                                     * quantization paramenter. One parameter per block. */
                                    if (chr)
                                    {
                                        QP = QP_store[index];
                                    }

                                    /* Set amount to change luminance if it needs to be changed
                                     * based on quantization parameter */
                                    max_diff = (QP >> 2) + 4;

                                    /* Set pointer to first pixel of current block */
                                    ptr = rec + (brwidth << 6) + (bc << 3);

                                    /* Find minimum and maximum value of pixel block */
                                    FindMaxMin(ptr, &min_blk, &max_blk, incr);

                                    /* threshold determination */
                                    thres = (max_blk + min_blk + 1) >> 1;

                                    /* If pixel range is greater or equal than DERING_THR, smooth the region */
                                    if ((max_blk - min_blk) >= DERING_THR) /*smooth 8x8 region*/
#ifndef NoMMX
                                    {
                                        /* smooth all pixels in the block*/
                                        DeringAdaptiveSmoothMMX(ptr, width, thres, max_diff);
                                    }
#else
                                    {
                                        /* Setup the starting point of the region to smooth */
                                        v0 = (br << 3) - 1;
                                        h0 = (bc << 3) - 1;

                                        /*smooth 8x8 region*/
                                        AdaptiveSmooth_NoMMX(rec, v0, h0, v0 + 1, h0 + 1, thres, width, max_diff);
                                    }
#endif
                                }/*cnthflag*/
                            } /*dering br==1 or bc==1 (boundary block)*/
                            else    /* Process the boundary blocks */
                            {
                                /* Decide to perform deblocking based on the semaphore flags
                                   * of the neighboring blocks in each case. A certain number of
                                 * hard filtering flags have to be set in order to signal need
                                 * for smoothing */
                                if (br > 0 && br < pp_h - 1)
                                {
                                    if (bc > 0)
                                    {
                                        cnthflag = ((pp_mod[index-pp_w] & 0x10) +
                                                    (pp_mod[index] & 0x10) +
                                                    ((pp_mod[index-1] >> 1) & 0x10)) >> 4;
                                    }
                                    else
                                    {
                                        cnthflag = ((pp_mod[index] & 0x10) +
                                                    (pp_mod[index-pp_w] & 0x10) +
                                                    ((pp_mod[index] >> 1) & 0x10)) >> 4;
                                    }
                                }
                                else if (bc > 0 && bc < pp_w - 1)
                                {
                                    if (br > 0)
                                    {
                                        cnthflag = ((pp_mod[index-pp_w] & 0x10) +
                                                    ((pp_mod[index-1] >> 1) & 0x10) +
                                                    ((pp_mod[index] >> 1) & 0x10)) >> 4;
                                    }
                                    else
                                    {
                                        cnthflag = ((pp_mod[index] & 0x10) +
                                                    ((pp_mod[index-1] >> 1) & 0x10) +
                                                    ((pp_mod[index] >> 1) & 0x10)) >> 4;
                                    }
                                }
                                else /* at the corner do default*/
                                {
                                    cnthflag = 0;
                                }

                                /* Do the deringing if decision flags indicate it's necessary */
                                if (cnthflag < 2)
                                {

                                    /* if the data is chrominance info, get the correct
                                                         * quantization paramenter. One parameter per block. */
                                    if (chr)
                                    {
                                        QP = QP_store[index];
                                    }

                                    /* Set amount to change luminance if it needs to be changed
                                     * based on quantization parameter */
                                    max_diff = (QP >> 2) + 4;

                                    /* Set pointer to first pixel of current block */
                                    ptr = rec + (brwidth << 6) + (bc << 3);

                                    /* Find minimum and maximum value of pixel block */
                                    FindMaxMin(ptr, &min_blk, &max_blk, incr);

                                    /* threshold determination */
                                    thres = (max_blk + min_blk + 1) >> 1;

                                    /* Setup the starting point of the region to smooth
                                     * This is going to be a 4x4 region */
                                    v0 = (br << 3) + 1;
                                    h0 = (bc << 3) + 1;

                                    /* If pixel range is greater or equal than DERING_THR, smooth the region */
                                    if ((max_blk - min_blk) >= DERING_THR)
                                    {
                                        /* Smooth 4x4 region */
                                        AdaptiveSmooth_NoMMX(rec, v0, h0, v0 - 3, h0 - 3, thres, width, max_diff);
                                    }
                                }/*cnthflag*/
                            } /* br==0, bc==0*/
                        }  /* dering*/
                    } /*boundary condition*/
                }/*bc*/
                brwidth += pp_w;
            }/*br*/
            brwidth -= (pp_w << 1);
        }/*mbc*/
        brwidth += (pp_w << 1);
    }/*mbr*/

    /*----------------------------------------------------------------------------
    ; Return nothing or data or data pointer
    ----------------------------------------------------------------------------*/
    return ;
}
#endif
