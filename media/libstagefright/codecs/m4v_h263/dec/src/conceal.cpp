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
#include "mp4dec_lib.h" /* video decoder function prototypes */
#include "vlc_decode.h"
#include "bitstream.h"
#include "scaling.h"

/* ====================================================================== /
Function : ConcealTexture_I()
Date     : 06/12/2001
Purpose  : Conceal texture for I-partition
In/out   :
Return   :
Modified :
/ ====================================================================== */
void ConcealTexture_I(VideoDecData *video, int32 startFirstPartition, int mb_start, int mb_stop, int slice_counter)
{
    int mbnum;
    BitstreamDecVideo *stream = video->bitstream;
    int16 QP;
    int intra_dc_vlc_thr = video->currVop->intraDCVlcThr;

    movePointerTo(stream, startFirstPartition);

    video->usePrevQP = 0;
    for (mbnum = mb_start; mbnum < mb_stop; mbnum++)
    {
        video->mbnum = mbnum;
        video->mbnum_row = PV_GET_ROW(mbnum, video->nMBPerRow);
        video->mbnum_col = mbnum - video->mbnum_row * video->nMBPerRow;
        video->sliceNo[mbnum] = (uint8) slice_counter;
        QP = video->QPMB[mbnum];
        PV_VlcDecMCBPC_com_intra(stream);
        GetMBheaderDataPart_DQUANT_DC(video, &QP);

        if (intra_dc_vlc_thr)
        {
            if (video->usePrevQP)
                QP = video->QPMB[mbnum-1];
            if (intra_dc_vlc_thr == 7 || QP >= intra_dc_vlc_thr*2 + 11)  /* if switched then conceal from previous frame  */
            {
                ConcealPacket(video, mbnum, mb_stop, slice_counter);
                video->mbnum = mb_stop - 1;
                video->mbnum_row = PV_GET_ROW(video->mbnum, video->nMBPerRow);
                video->mbnum_col = video->mbnum - video->mbnum_row * video->nMBPerRow;
                break;
            }
        }

        video->headerInfo.CBP[mbnum] = 0;
        video->acPredFlag[mbnum] = 0;
        GetMBData_DataPart(video);
        video->usePrevQP = 1;
    }
    return;
}

/* ====================================================================== /
Function : ConcealTexture_P()
Date     : 05/16/2000
Purpose  : Conceal texture for P-partition
In/out   :
Return   :
/ ====================================================================== */

void ConcealTexture_P(VideoDecData *video, int mb_start, int mb_stop, int slice_counter)
{
    int mbnum;

    for (mbnum = mb_start; mbnum < mb_stop; mbnum++)
    {
        video->mbnum = mbnum;
        video->mbnum_row = PV_GET_ROW(mbnum, video->nMBPerRow);
        video->mbnum_col = mbnum - video->mbnum_row * video->nMBPerRow;
        video->sliceNo[mbnum] = (uint8) slice_counter;
        oscl_memset(video->mblock->block, 0, sizeof(typeMBStore));
        /*  to get rid of dark region caused by INTRA blocks */
        /* 05/19/2000 */
        if (video->headerInfo.Mode[mbnum] & INTER_MASK)
        {
            MBMotionComp(video, 0);
        }
        else
        {
            video->headerInfo.Mode[mbnum] = MODE_SKIPPED;
            SkippedMBMotionComp(video);
        }
    }

    return;
}

/***************************************************************
Function:   ConcealPacket
Purpose :   Conceal motion and texture of a packet by direct
copying from previous frame.
Returned:   void
Modified:
*************************************************************/
void ConcealPacket(VideoDecData *video,
                   int mb_start,
                   int mb_stop,
                   int slice_counter)
{
    int i;
    for (i = mb_start; i < mb_stop; i++)
    {
        CopyVopMB(video->currVop, video->concealFrame, i, video->width, video->height);
        video->sliceNo[i] = (uint8) slice_counter;
        video->headerInfo.Mode[i] = MODE_SKIPPED;
    }

    return;
}

/****************************************************************************
Function:   CopyVopMB
Purpose :   Fill a macroblock with previous Vop.
Returned    :   void
Modified:   6/04/2001 rewrote the function
            copies from concealFrame
****************************************************************************/
void CopyVopMB(Vop *curr, uint8 *prevFrame, int mbnum, int width_Y, int height)
{
    int width_C = width_Y >> 1;
    int row = MB_SIZE;
    uint8              *y1, *y2, *u1, *u2, *v1, *v2;
    int xpos, ypos, MB_in_width;
    int32 lumstart, chrstart, size;

    MB_in_width = (width_Y + 15) >> 4;
    ypos = PV_GET_ROW(mbnum, MB_in_width);
    xpos = mbnum - ypos * MB_in_width;
    lumstart = (ypos << 4) * (int32)width_Y  + (xpos << 4);
    chrstart = (ypos << 3) * (int32)width_C  + (xpos << 3);

    size = (int32)height * width_Y;

    y1 =  curr->yChan + lumstart;
    u1 =  curr->uChan + chrstart;
    v1 =  curr->vChan + chrstart;
    y2 =  prevFrame + lumstart;
    u2 =  prevFrame + size + chrstart;
    v2 =  prevFrame + size + (size >> 2) + chrstart;
    while (row)
    {
        oscl_memcpy(y1, y2, MB_SIZE);
        y1 += width_Y;
        y2 += width_Y;
        oscl_memcpy(y1, y2, MB_SIZE);
        y1 += width_Y;
        y2 += width_Y;
        oscl_memcpy(y1, y2, MB_SIZE);
        y1 += width_Y;
        y2 += width_Y;
        oscl_memcpy(y1, y2, MB_SIZE);
        y1 += width_Y;
        y2 += width_Y;

        oscl_memcpy(u1, u2, B_SIZE);
        u1 += width_C;
        u2 += width_C;
        oscl_memcpy(u1, u2, B_SIZE);
        u1 += width_C;
        u2 += width_C;

        oscl_memcpy(v1, v2, B_SIZE);
        v1 += width_C;
        v2 += width_C;
        oscl_memcpy(v1, v2, B_SIZE);
        v1 += width_C;
        v2 += width_C;

        row -= 4;
    }
    return;
}               /* CopyVopMB */

