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

#ifdef PV_ANNEX_IJKT_SUPPORT
#include    "motion_comp.h"
#include "mbtype_mode.h"
const static int STRENGTH_tab[] = {0, 1, 1, 2, 2, 3, 3, 4, 4, 4, 5, 5, 6, 6, 7, 7, 7, 8, 8, 8, 9, 9, 9, 10, 10, 10, 11, 11, 11, 12, 12, 12};
#endif

#ifdef PV_POSTPROC_ON
/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/
void PostFilter(
    VideoDecData *video,
    int filter_type,
    uint8 *output)
{
    /*----------------------------------------------------------------------------
    ; Define all local variables
    ----------------------------------------------------------------------------*/
    uint8 *pp_mod;
    int16 *QP_store;
    int combined_with_deblock_filter;
    int nTotalMB = video->nTotalMB;
    int width, height;
    int32 size;
    int softDeblocking;
    uint8 *decodedFrame = video->videoDecControls->outputFrame;
    /*----------------------------------------------------------------------------
    ; Function body here
    ----------------------------------------------------------------------------*/
    width = video->width;
    height = video->height;
    size = (int32)width * height;

    oscl_memcpy(output, decodedFrame, size);
    oscl_memcpy(output + size, decodedFrame + size, (size >> 2));
    oscl_memcpy(output + size + (size >> 2), decodedFrame + size + (size >> 2), (size >> 2));

    if (filter_type == 0)
        return;

    /* The softDecoding cutoff corresponds to ~93000 bps for QCIF 15fps clip  */
    if (PVGetDecBitrate(video->videoDecControls) > (100*video->frameRate*(size >> 12)))  // MC_sofDeblock
        softDeblocking = FALSE;
    else
        softDeblocking = TRUE;

    combined_with_deblock_filter = filter_type & PV_DEBLOCK;
    QP_store = video->QPMB;

    /* Luma */
    pp_mod = video->pstprcTypCur;

    if ((filter_type & PV_DEBLOCK) && (filter_type & PV_DERING))
    {
        CombinedHorzVertRingFilter(output, width, height, QP_store, 0, pp_mod);
    }
    else
    {
        if (filter_type & PV_DEBLOCK)
        {
            if (softDeblocking)
            {
                CombinedHorzVertFilter(output, width, height,
                                       QP_store, 0, pp_mod);
            }
            else
            {
                CombinedHorzVertFilter_NoSoftDeblocking(output, width, height,
                                                        QP_store, 0, pp_mod);
            }
        }
        if (filter_type & PV_DERING)
        {
            Deringing_Luma(output, width, height, QP_store,
                           combined_with_deblock_filter, pp_mod);

        }
    }

    /* Chroma */

    pp_mod += (nTotalMB << 2);
    output += size;

    if ((filter_type & PV_DEBLOCK) && (filter_type & PV_DERING))
    {
        CombinedHorzVertRingFilter(output, (int)(width >> 1), (int)(height >> 1), QP_store, (int) 1, pp_mod);
    }
    else
    {
        if (filter_type & PV_DEBLOCK)
        {
            if (softDeblocking)
            {
                CombinedHorzVertFilter(output, (int)(width >> 1),
                                       (int)(height >> 1), QP_store, (int) 1, pp_mod);
            }
            else
            {
                CombinedHorzVertFilter_NoSoftDeblocking(output, (int)(width >> 1),
                                                        (int)(height >> 1), QP_store, (int) 1, pp_mod);
            }
        }
        if (filter_type & PV_DERING)
        {
            Deringing_Chroma(output, (int)(width >> 1),
                             (int)(height >> 1), QP_store,
                             combined_with_deblock_filter, pp_mod);
        }
    }

    pp_mod += nTotalMB;
    output += (size >> 2);

    if ((filter_type & PV_DEBLOCK) && (filter_type & PV_DERING))
    {
        CombinedHorzVertRingFilter(output, (int)(width >> 1), (int)(height >> 1), QP_store, (int) 1, pp_mod);
    }
    else
    {
        if (filter_type & PV_DEBLOCK)
        {
            if (softDeblocking)
            {
                CombinedHorzVertFilter(output, (int)(width >> 1),
                                       (int)(height >> 1), QP_store, (int) 1, pp_mod);
            }
            else
            {
                CombinedHorzVertFilter_NoSoftDeblocking(output, (int)(width >> 1),
                                                        (int)(height >> 1), QP_store, (int) 1, pp_mod);
            }
        }
        if (filter_type & PV_DERING)
        {
            Deringing_Chroma(output, (int)(width >> 1),
                             (int)(height >> 1), QP_store,
                             combined_with_deblock_filter, pp_mod);
        }
    }

    /*  swap current pp_mod to prev_frame pp_mod */
    pp_mod = video->pstprcTypCur;
    video->pstprcTypCur = video->pstprcTypPrv;
    video->pstprcTypPrv = pp_mod;

    /*----------------------------------------------------------------------------
    ; Return nothing or data or data pointer
    ----------------------------------------------------------------------------*/
    return;
}
#endif


#ifdef PV_ANNEX_IJKT_SUPPORT
void H263_Deblock(uint8 *rec,
                  int width,
                  int height,
                  int16 *QP_store,
                  uint8 *mode,
                  int chr, int annex_T)
{
    /*----------------------------------------------------------------------------
    ; Define all local variables
    ----------------------------------------------------------------------------*/
    int i, j, k;
    uint8 *rec_y;
    int tmpvar;
    int mbnum, strength, A_D, d1_2, d1, d2, A, B, C, D, b_size;
    int d, offset, nMBPerRow, nMBPerCol, width2 = (width << 1);
    /* MAKE SURE I-VOP INTRA MACROBLOCKS ARE SET TO NON-SKIPPED MODE*/
    mbnum = 0;

    if (chr)
    {
        nMBPerRow = width >> 3;
        nMBPerCol = height >> 3;
        b_size = 8;
    }
    else
    {
        nMBPerRow = width >> 4;
        nMBPerCol = height >> 4;
        b_size = 16;
    }


    /********************************* VERTICAL FILTERING ****************************/
    /* vertical filtering of mid sections no need to check neighboring QP's etc */
    if (!chr)
    {
        rec_y = rec + (width << 3);
        for (i = 0; i < (height >> 4); i++)
        {
            for (j = 0; j < (width >> 4); j++)
            {
                if (mode[mbnum] != MODE_SKIPPED)
                {
                    k = 16;
                    strength = STRENGTH_tab[QP_store[mbnum]];
                    while (k--)
                    {
                        A =  *(rec_y - width2);
                        D = *(rec_y + width);
                        A_D = A - D;
                        C = *rec_y;
                        B = *(rec_y - width);
                        d = (((C - B) << 2) + A_D);

                        if (d < 0)
                        {
                            d1 = -(-d >> 3);
                            if (d1 < -(strength << 1))
                            {
                                d1 = 0;
                            }
                            else if (d1 < -strength)
                            {
                                d1 = -d1 - (strength << 1);
                            }
                            d1_2 = -d1 >> 1;
                        }
                        else
                        {
                            d1 = d >> 3;
                            if (d1 > (strength << 1))
                            {
                                d1 = 0;
                            }
                            else if (d1 > strength)
                            {
                                d1 = (strength << 1) - d1;
                            }
                            d1_2 = d1 >> 1;
                        }

                        if (A_D < 0)
                        {
                            d2 = -(-A_D >> 2);
                            if (d2 < -d1_2)
                            {
                                d2 = -d1_2;
                            }
                        }
                        else
                        {
                            d2 = A_D >> 2;
                            if (d2 > d1_2)
                            {
                                d2 = d1_2;
                            }
                        }

                        *(rec_y - width2) = A - d2;
                        tmpvar = B + d1;
                        CLIP_RESULT(tmpvar)
                        *(rec_y - width) = tmpvar;
                        tmpvar = C - d1;
                        CLIP_RESULT(tmpvar)
                        *rec_y = tmpvar;
                        *(rec_y + width) = D + d2;
                        rec_y++;
                    }
                }
                else
                {
                    rec_y += b_size;
                }
                mbnum++;
            }
            rec_y += (15 * width);

        }
    }

    /* VERTICAL boundary blocks */


    rec_y = rec + width * b_size;

    mbnum = nMBPerRow;
    for (i = 0; i < nMBPerCol - 1; i++)
    {
        for (j = 0; j < nMBPerRow; j++)
        {
            if (mode[mbnum] != MODE_SKIPPED || mode[mbnum - nMBPerRow] != MODE_SKIPPED)
            {
                k = b_size;
                if (mode[mbnum] != MODE_SKIPPED)
                {
                    strength = STRENGTH_tab[(annex_T ?  MQ_chroma_QP_table[QP_store[mbnum]] : QP_store[mbnum])];
                }
                else
                {
                    strength = STRENGTH_tab[(annex_T ?  MQ_chroma_QP_table[QP_store[mbnum - nMBPerRow]] : QP_store[mbnum - nMBPerRow])];
                }

                while (k--)
                {
                    A =  *(rec_y - width2);
                    D =  *(rec_y + width);
                    A_D = A - D;
                    C = *rec_y;
                    B = *(rec_y - width);
                    d = (((C - B) << 2) + A_D);

                    if (d < 0)
                    {
                        d1 = -(-d >> 3);
                        if (d1 < -(strength << 1))
                        {
                            d1 = 0;
                        }
                        else if (d1 < -strength)
                        {
                            d1 = -d1 - (strength << 1);
                        }
                        d1_2 = -d1 >> 1;
                    }
                    else
                    {
                        d1 = d >> 3;
                        if (d1 > (strength << 1))
                        {
                            d1 = 0;
                        }
                        else if (d1 > strength)
                        {
                            d1 = (strength << 1) - d1;
                        }
                        d1_2 = d1 >> 1;
                    }

                    if (A_D < 0)
                    {
                        d2 = -(-A_D >> 2);
                        if (d2 < -d1_2)
                        {
                            d2 = -d1_2;
                        }
                    }
                    else
                    {
                        d2 = A_D >> 2;
                        if (d2 > d1_2)
                        {
                            d2 = d1_2;
                        }
                    }

                    *(rec_y - width2) = A - d2;
                    tmpvar = B + d1;
                    CLIP_RESULT(tmpvar)
                    *(rec_y - width) = tmpvar;
                    tmpvar = C - d1;
                    CLIP_RESULT(tmpvar)
                    *rec_y = tmpvar;
                    *(rec_y + width) = D + d2;
                    rec_y++;
                }
            }
            else
            {
                rec_y += b_size;
            }
            mbnum++;
        }
        rec_y += ((b_size - 1) * width);

    }


    /***************************HORIZONTAL FILTERING ********************************************/
    mbnum = 0;
    /* HORIZONTAL INNER */
    if (!chr)
    {
        rec_y = rec + 8;
        offset = width * b_size - b_size;

        for (i = 0; i < nMBPerCol; i++)
        {
            for (j = 0; j < nMBPerRow; j++)
            {
                if (mode[mbnum] != MODE_SKIPPED)
                {
                    k = 16;
                    strength = STRENGTH_tab[QP_store[mbnum]];
                    while (k--)
                    {
                        A =  *(rec_y - 2);
                        D =  *(rec_y + 1);
                        A_D = A - D;
                        C = *rec_y;
                        B = *(rec_y - 1);
                        d = (((C - B) << 2) + A_D);

                        if (d < 0)
                        {
                            d1 = -(-d >> 3);
                            if (d1 < -(strength << 1))
                            {
                                d1 = 0;
                            }
                            else if (d1 < -strength)
                            {
                                d1 = -d1 - (strength << 1);
                            }
                            d1_2 = -d1 >> 1;
                        }
                        else
                        {
                            d1 = d >> 3;
                            if (d1 > (strength << 1))
                            {
                                d1 = 0;
                            }
                            else if (d1 > strength)
                            {
                                d1 = (strength << 1) - d1;
                            }
                            d1_2 = d1 >> 1;
                        }

                        if (A_D < 0)
                        {
                            d2 = -(-A_D >> 2);
                            if (d2 < -d1_2)
                            {
                                d2 = -d1_2;
                            }
                        }
                        else
                        {
                            d2 = A_D >> 2;
                            if (d2 > d1_2)
                            {
                                d2 = d1_2;
                            }
                        }

                        *(rec_y - 2) = A - d2;
                        tmpvar = B + d1;
                        CLIP_RESULT(tmpvar)
                        *(rec_y - 1) = tmpvar;
                        tmpvar = C - d1;
                        CLIP_RESULT(tmpvar)
                        *rec_y = tmpvar;
                        *(rec_y + 1) = D + d2;
                        rec_y += width;
                    }
                    rec_y -= offset;
                }
                else
                {
                    rec_y += b_size;
                }
                mbnum++;
            }
            rec_y += (15 * width);

        }
    }



    /* HORIZONTAL EDGE */
    rec_y = rec + b_size;
    offset = width * b_size - b_size;
    mbnum = 1;
    for (i = 0; i < nMBPerCol; i++)
    {
        for (j = 0; j < nMBPerRow - 1; j++)
        {
            if (mode[mbnum] != MODE_SKIPPED || mode[mbnum-1] != MODE_SKIPPED)
            {
                k = b_size;
                if (mode[mbnum] != MODE_SKIPPED)
                {
                    strength = STRENGTH_tab[(annex_T ?  MQ_chroma_QP_table[QP_store[mbnum]] : QP_store[mbnum])];
                }
                else
                {
                    strength = STRENGTH_tab[(annex_T ?  MQ_chroma_QP_table[QP_store[mbnum - 1]] : QP_store[mbnum - 1])];
                }

                while (k--)
                {
                    A =  *(rec_y - 2);
                    D =  *(rec_y + 1);
                    A_D = A - D;
                    C = *rec_y;
                    B = *(rec_y - 1);
                    d = (((C - B) << 2) + A_D);

                    if (d < 0)
                    {
                        d1 = -(-d >> 3);
                        if (d1 < -(strength << 1))
                        {
                            d1 = 0;
                        }
                        else if (d1 < -strength)
                        {
                            d1 = -d1 - (strength << 1);
                        }
                        d1_2 = -d1 >> 1;
                    }
                    else
                    {
                        d1 = d >> 3;
                        if (d1 > (strength << 1))
                        {
                            d1 = 0;
                        }
                        else if (d1 > strength)
                        {
                            d1 = (strength << 1) - d1;
                        }
                        d1_2 = d1 >> 1;
                    }

                    if (A_D < 0)
                    {
                        d2 = -(-A_D >> 2);
                        if (d2 < -d1_2)
                        {
                            d2 = -d1_2;
                        }
                    }
                    else
                    {
                        d2 = A_D >> 2;
                        if (d2 > d1_2)
                        {
                            d2 = d1_2;
                        }
                    }

                    *(rec_y - 2) = A - d2;
                    tmpvar = B + d1;
                    CLIP_RESULT(tmpvar)
                    *(rec_y - 1) = tmpvar;
                    tmpvar = C - d1;
                    CLIP_RESULT(tmpvar)
                    *rec_y = tmpvar;
                    *(rec_y + 1) = D + d2;
                    rec_y += width;
                }
                rec_y -= offset;
            }
            else
            {
                rec_y += b_size;
            }
            mbnum++;
        }
        rec_y += ((width * (b_size - 1)) + b_size);
        mbnum++;
    }

    return;
}
#endif

