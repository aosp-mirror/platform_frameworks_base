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

/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include "mp4dec_lib.h"
#include "vlc_decode.h"
#include "bitstream.h"
#include "zigzag.h"
#include "scaling.h"

void    doDCACPrediction(
    VideoDecData *video,
    int comp,
    int16 *q_block,
    int *direction
)
{
    /*----------------------------------------------------------------------------
    ; Define all local variables
    ----------------------------------------------------------------------------*/
    int i;
    int mbnum = video->mbnum;
    int nMBPerRow = video->nMBPerRow;
    int x_pos = video->mbnum_col;
    int y_pos = video->mbnum_row;
    int16 *AC_tmp;
    int QP_tmp;
    int16 *QP_store = video->QPMB + mbnum;
    int QP = video->QPMB[mbnum];
    int QP_half = QP >> 1;
    int32 val;
    int flag_0 = FALSE, flag_1 = FALSE;
    uint8 *slice_nb = video->sliceNo;
    typeDCStore *DC_store = video->predDC + mbnum;
    typeDCACStore *DCAC_row = video->predDCAC_row + x_pos;
    typeDCACStore *DCAC_col = video->predDCAC_col;

    uint ACpred_flag = (uint) video->acPredFlag[mbnum];

    int left_bnd, up_bnd;

    static const int Xpos[6] = { -1, 0, -1, 0, -1, -1};
    static const int Ypos[6] = { -1, -1, 0, 0, -1, -1};

    static const int Xtab[6] = {1, 0, 3, 2, 4, 5};
    static const int Ytab[6] = {2, 3, 0, 1, 4, 5};
    static const int Ztab[6] = {3, 2, 1, 0, 4, 5};

    /* I added these to speed up comparisons */
    static const int Pos0[6] = { 1, 1, 0, 0, 1, 1};
    static const int Pos1[6] = { 1, 0, 1, 0, 1, 1};

    static const int B_Xtab[6] = {0, 1, 0, 1, 2, 3};
    static const int B_Ytab[6] = {0, 0, 1, 1, 2, 3};

//  int *direction;     /* 0: HORIZONTAL, 1: VERTICAL */
    int block_A, block_B, block_C;
    int DC_pred;
    int y_offset, x_offset, x_tab, y_tab, z_tab;    /* speedup coefficients */
    int b_xtab, b_ytab;

    if (!comp && x_pos && !(video->headerInfo.Mode[mbnum-1]&INTRA_MASK)) /* not intra */
    {
        oscl_memset(DCAC_col, 0, sizeof(typeDCACStore));
    }
    if (!comp && y_pos && !(video->headerInfo.Mode[mbnum-nMBPerRow]&INTRA_MASK)) /* not intra */
    {
        oscl_memset(DCAC_row, 0, sizeof(typeDCACStore));
    }

    y_offset = Ypos[comp] * nMBPerRow;
    x_offset = Xpos[comp];
    x_tab = Xtab[comp];
    y_tab = Ytab[comp];
    z_tab = Ztab[comp];

    b_xtab = B_Xtab[comp];
    b_ytab = B_Ytab[comp];

    /*----------------------------------------------------------------------------
    ; Function body here
    ----------------------------------------------------------------------------*/
    /* Find the direction of prediction and the DC prediction */

    if (x_pos == 0 && y_pos == 0)
    {   /* top left corner */
        block_A = (comp == 1 || comp == 3) ? flag_0 = TRUE, DC_store[0][x_tab] : mid_gray;
        block_B = (comp == 3) ? DC_store[x_offset][z_tab] : mid_gray;
        block_C = (comp == 2 || comp == 3) ? flag_1 = TRUE, DC_store[0][y_tab] : mid_gray;
    }
    else if (x_pos == 0)
    {   /* left edge */
        up_bnd   = Pos0[comp] && slice_nb[mbnum] == slice_nb[mbnum-nMBPerRow];

        block_A = (comp == 1 || comp == 3) ? flag_0 = TRUE, DC_store[0][x_tab] : mid_gray;
        block_B = ((comp == 1 && up_bnd) || comp == 3) ?  DC_store[y_offset+x_offset][z_tab] : mid_gray;
        block_C = (comp == 2 || comp == 3 || up_bnd) ? flag_1 = TRUE, DC_store[y_offset][y_tab] : mid_gray;
    }
    else if (y_pos == 0)
    { /* top row */
        left_bnd = Pos1[comp] && slice_nb[mbnum] == slice_nb[mbnum-1];

        block_A = (comp == 1 || comp == 3 || left_bnd) ? flag_0 = TRUE, DC_store[x_offset][x_tab] : mid_gray;
        block_B = ((comp == 2 && left_bnd) || comp == 3) ? DC_store[y_offset + x_offset][z_tab] : mid_gray;
        block_C = (comp == 2 || comp == 3) ? flag_1 = TRUE, DC_store[y_offset][y_tab] : mid_gray;
    }
    else
    {
        up_bnd   = Pos0[comp] && slice_nb[mbnum] == slice_nb[mbnum-nMBPerRow];
        left_bnd = Pos1[comp] && slice_nb[mbnum] == slice_nb[mbnum-1];

        block_A = (comp == 1 || comp == 3 || left_bnd) ? flag_0 = TRUE, DC_store[x_offset][x_tab] : mid_gray;
        block_B = (((comp == 0 || comp == 4 || comp == 5) && slice_nb[mbnum] == slice_nb[mbnum-1-nMBPerRow]) ||
                   (comp == 1 && up_bnd) || (comp == 2 && left_bnd) || (comp == 3)) ? DC_store[y_offset+x_offset][z_tab] : mid_gray;
        block_C = (comp == 2 || comp == 3 || up_bnd) ? flag_1 = TRUE, DC_store[y_offset][y_tab] : mid_gray;
    }


    if ((PV_ABS((block_A - block_B))) < (PV_ABS((block_B - block_C))))
    {
        DC_pred = block_C;
        *direction = 1;
        if (ACpred_flag == 1)
        {
            if (flag_1)
            {
                AC_tmp = DCAC_row[0][b_xtab];
                QP_tmp = QP_store[y_offset];
                if (QP_tmp == QP)
                {
                    for (i = 1; i < 8; i++)
                    {
                        q_block[i] = *AC_tmp++;
                    }
                }
                else
                {
                    for (i = 1; i < 8; i++)
                    {
                        val = (int32)(*AC_tmp++) * QP_tmp;
                        q_block[i] = (val < 0) ? (int16)((val - QP_half) / QP) : (int16)((val + QP_half) / QP);
                        /* Vertical, top ROW of block C */
                    }
                }
            }
        }
    }
    else
    {
        DC_pred = block_A;
        *direction = 0;
        if (ACpred_flag == 1)
        {
            if (flag_0)
            {
                AC_tmp = DCAC_col[0][b_ytab];
                QP_tmp = QP_store[x_offset];
                if (QP_tmp == QP)
                {
                    for (i = 1; i < 8; i++)
                    {
                        q_block[i<<3] = *AC_tmp++;
                    }
                }
                else
                {
                    for (i = 1; i < 8; i++)
                    {
                        val = (int32)(*AC_tmp++) * QP_tmp;
                        q_block[i<<3] = (val < 0) ? (int16)((val - QP_half) / QP) : (int16)((val + QP_half) / QP);
                        /* Vertical, top ROW of block C */
                    }
                }
            }
        }
    }

    /* Now predict the DC coefficient */
    QP_tmp = (comp < 4) ? video->mblock->DCScalarLum : video->mblock->DCScalarChr;
    q_block[0] += (int16)((DC_pred + (QP_tmp >> 1)) * scale[QP_tmp] >> 18);
//      q_block[0] += (DC_pred+(QP_tmp>>1))/QP_tmp;

    /*----------------------------------------------------------------------------
    ; Return nothing or data or data pointer
    ----------------------------------------------------------------------------*/
    return;
}
#ifdef PV_ANNEX_IJKT_SUPPORT
void    doDCACPrediction_I(
    VideoDecData *video,
    int comp,
    int16 *q_block
)
{
    /*----------------------------------------------------------------------------
    ; Define all local variables
    ----------------------------------------------------------------------------*/
    int mbnum = video->mbnum;
    int nMBPerRow = video->nMBPerRow;
    int x_pos = video->mbnum_col;
    int y_pos = video->mbnum_row;
    int16 *AC_tmp;
    int flag_0 = FALSE, flag_1 = FALSE;
    uint8 *slice_nb = video->sliceNo;
    typeDCStore *DC_store = video->predDC + mbnum;
    typeDCACStore *DCAC_row = video->predDCAC_row + x_pos;
    typeDCACStore *DCAC_col = video->predDCAC_col;
    int left_bnd, up_bnd;
    uint8 *mode = video->headerInfo.Mode;
    uint ACpred_flag = (uint) video->acPredFlag[mbnum];



    static const int Xpos[6] = { -1, 0, -1, 0, -1, -1};
    static const int Ypos[6] = { -1, -1, 0, 0, -1, -1};

    static const int Xtab[6] = {1, 0, 3, 2, 4, 5};
    static const int Ytab[6] = {2, 3, 0, 1, 4, 5};

    /* I added these to speed up comparisons */
    static const int Pos0[6] = { 1, 1, 0, 0, 1, 1};
    static const int Pos1[6] = { 1, 0, 1, 0, 1, 1};

    static const int B_Xtab[6] = {0, 1, 0, 1, 2, 3};
    static const int B_Ytab[6] = {0, 0, 1, 1, 2, 3};

//  int *direction;     /* 0: HORIZONTAL, 1: VERTICAL */
    int block_A, block_C;
    int y_offset, x_offset, x_tab, y_tab;   /* speedup coefficients */
    int b_xtab, b_ytab;
    y_offset = Ypos[comp] * nMBPerRow;
    x_offset = Xpos[comp];
    x_tab = Xtab[comp];
    y_tab = Ytab[comp];

    b_xtab = B_Xtab[comp];
    b_ytab = B_Ytab[comp];

    /*----------------------------------------------------------------------------
    ; Function body here
    ----------------------------------------------------------------------------*/
    /* Find the direction of prediction and the DC prediction */

    if (x_pos == 0 && y_pos == 0)
    {   /* top left corner */
        block_A = (comp == 1 || comp == 3) ? flag_0 = TRUE, DC_store[0][x_tab] : mid_gray;
        block_C = (comp == 2 || comp == 3) ? flag_1 = TRUE, DC_store[0][y_tab] : mid_gray;
    }
    else if (x_pos == 0)
    {   /* left edge */
        up_bnd   = (Pos0[comp] && slice_nb[mbnum] == slice_nb[mbnum-nMBPerRow])
                   && (mode[mbnum-nMBPerRow] == MODE_INTRA || mode[mbnum-nMBPerRow] == MODE_INTRA_Q);;

        block_A = (comp == 1 || comp == 3) ? flag_0 = TRUE, DC_store[0][x_tab] : mid_gray;
        block_C = (comp == 2 || comp == 3 || up_bnd) ? flag_1 = TRUE, DC_store[y_offset][y_tab] : mid_gray;
    }
    else if (y_pos == 0)
    { /* top row */
        left_bnd = (Pos1[comp] && slice_nb[mbnum] == slice_nb[mbnum-1])
                   && (mode[mbnum-1] == MODE_INTRA || mode[mbnum-1] == MODE_INTRA_Q);

        block_A = (comp == 1 || comp == 3 || left_bnd) ? flag_0 = TRUE, DC_store[x_offset][x_tab] : mid_gray;
        block_C = (comp == 2 || comp == 3) ? flag_1 = TRUE, DC_store[y_offset][y_tab] : mid_gray;
    }
    else
    {
        up_bnd   = (Pos0[comp] && slice_nb[mbnum] == slice_nb[mbnum-nMBPerRow])
                   && (mode[mbnum-nMBPerRow] == MODE_INTRA || mode[mbnum-nMBPerRow] == MODE_INTRA_Q);
        left_bnd = (Pos1[comp] && slice_nb[mbnum] == slice_nb[mbnum-1])
                   && (mode[mbnum-1] == MODE_INTRA || mode[mbnum-1] == MODE_INTRA_Q);

        block_A = (comp == 1 || comp == 3 || left_bnd) ? flag_0 = TRUE, DC_store[x_offset][x_tab] : mid_gray;
        block_C = (comp == 2 || comp == 3 || up_bnd) ? flag_1 = TRUE, DC_store[y_offset][y_tab] : mid_gray;
    }

    if (ACpred_flag == 0)
    {
        if (flag_0 == TRUE)
        {
            if (flag_1 == TRUE)
            {
                q_block[0] = (int16)((block_A + block_C) >> 1);
            }
            else
            {
                q_block[0] = (int16)block_A;
            }
        }
        else
        {
            if (flag_1 == TRUE)
            {
                q_block[0] = (int16)block_C;
            }
            else
            {
                q_block[0] = mid_gray;
            }
        }

    }
    else
    {
        if (video->mblock->direction == 1)
        {
            if (flag_1 == TRUE)
            {
                q_block[0] = (int16)block_C;

                AC_tmp = DCAC_row[0][b_xtab];
                q_block[1] = AC_tmp[0];
                q_block[2] = AC_tmp[1];
                q_block[3] = AC_tmp[2];
                q_block[4] = AC_tmp[3];
                q_block[5] = AC_tmp[4];
                q_block[6] = AC_tmp[5];
                q_block[7] = AC_tmp[6];
            }
            else
            {
                q_block[0] = mid_gray;
            }
        }
        else
        {
            if (flag_0 == TRUE)
            {
                q_block[0] = (int16)block_A;

                AC_tmp = DCAC_col[0][b_ytab];
                q_block[8] = AC_tmp[0];
                q_block[16] = AC_tmp[1];
                q_block[24] = AC_tmp[2];
                q_block[32] = AC_tmp[3];
                q_block[40] = AC_tmp[4];
                q_block[48] = AC_tmp[5];
                q_block[56] = AC_tmp[6];
            }
            else
            {
                q_block[0] = mid_gray;
            }
        }
    }
    /*----------------------------------------------------------------------------
    ; Return nothing or data or data pointer
    ----------------------------------------------------------------------------*/
    return;
}
#endif

