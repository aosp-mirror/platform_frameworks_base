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

#include <string.h>

#include "avclib_common.h"

#define MAX_QP 51
#define MB_BLOCK_SIZE 16

// NOTE: these 3 tables are for funtion GetStrength() only
const static int ININT_STRENGTH[4] = {0x04040404, 0x03030303, 0x03030303, 0x03030303};


// NOTE: these 3 tables are for funtion EdgeLoop() only
// NOTE: to change the tables below for instance when the QP doubling is changed from 6 to 8 values

const static int ALPHA_TABLE[52]  = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 4, 4, 5, 6,  7, 8, 9, 10, 12, 13, 15, 17,  20, 22, 25, 28, 32, 36, 40, 45,  50, 56, 63, 71, 80, 90, 101, 113,  127, 144, 162, 182, 203, 226, 255, 255} ;
const static int BETA_TABLE[52]   = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 2, 2, 3,  3, 3, 3, 4, 4, 4, 6, 6,   7, 7, 8, 8, 9, 9, 10, 10,  11, 11, 12, 12, 13, 13, 14, 14,   15, 15, 16, 16, 17, 17, 18, 18} ;
const static int CLIP_TAB[52][5]  =
{
    { 0, 0, 0, 0, 0}, { 0, 0, 0, 0, 0}, { 0, 0, 0, 0, 0}, { 0, 0, 0, 0, 0}, { 0, 0, 0, 0, 0}, { 0, 0, 0, 0, 0}, { 0, 0, 0, 0, 0}, { 0, 0, 0, 0, 0},
    { 0, 0, 0, 0, 0}, { 0, 0, 0, 0, 0}, { 0, 0, 0, 0, 0}, { 0, 0, 0, 0, 0}, { 0, 0, 0, 0, 0}, { 0, 0, 0, 0, 0}, { 0, 0, 0, 0, 0}, { 0, 0, 0, 0, 0},
    { 0, 0, 0, 0, 0}, { 0, 0, 0, 1, 1}, { 0, 0, 0, 1, 1}, { 0, 0, 0, 1, 1}, { 0, 0, 0, 1, 1}, { 0, 0, 1, 1, 1}, { 0, 0, 1, 1, 1}, { 0, 1, 1, 1, 1},
    { 0, 1, 1, 1, 1}, { 0, 1, 1, 1, 1}, { 0, 1, 1, 1, 1}, { 0, 1, 1, 2, 2}, { 0, 1, 1, 2, 2}, { 0, 1, 1, 2, 2}, { 0, 1, 1, 2, 2}, { 0, 1, 2, 3, 3},
    { 0, 1, 2, 3, 3}, { 0, 2, 2, 3, 3}, { 0, 2, 2, 4, 4}, { 0, 2, 3, 4, 4}, { 0, 2, 3, 4, 4}, { 0, 3, 3, 5, 5}, { 0, 3, 4, 6, 6}, { 0, 3, 4, 6, 6},
    { 0, 4, 5, 7, 7}, { 0, 4, 5, 8, 8}, { 0, 4, 6, 9, 9}, { 0, 5, 7, 10, 10}, { 0, 6, 8, 11, 11}, { 0, 6, 8, 13, 13}, { 0, 7, 10, 14, 14}, { 0, 8, 11, 16, 16},
    { 0, 9, 12, 18, 18}, { 0, 10, 13, 20, 20}, { 0, 11, 15, 23, 23}, { 0, 13, 17, 25, 25}
};

// NOTE: this table is only QP clipping, index = QP + video->FilterOffsetA/B, clipped to [0, 51]
//       video->FilterOffsetA/B is in {-12, 12]
const static int QP_CLIP_TAB[76] =
{
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,              // [-12, 0]
    1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12,
    13, 14, 15, 16, 17, 18, 19, 20, 21,
    22, 23, 24, 25, 26, 27, 28, 29, 30,
    31, 32, 33, 34, 35, 36, 37, 38, 39,
    40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, // [1, 51]
    51, 51, 51, 51, 51, 51, 51, 51, 51, 51, 51, 51      // [52,63]
};

static void DeblockMb(AVCCommonObj *video, int mb_x, int mb_y, uint8 *SrcY, uint8 *SrcU, uint8 *SrcV);
//static void GetStrength(AVCCommonObj *video, uint8 *Strength, AVCMacroblock* MbP, AVCMacroblock* MbQ, int dir, int edge);
static void GetStrength_Edge0(uint8 *Strength, AVCMacroblock* MbP, AVCMacroblock* MbQ, int dir);
static void GetStrength_VerticalEdges(uint8 *Strength, AVCMacroblock* MbQ);
static void GetStrength_HorizontalEdges(uint8 Strength[12], AVCMacroblock* MbQ);
static void EdgeLoop_Luma_vertical(uint8* SrcPtr, uint8 *Strength, int Alpha, int Beta, int *clipTable, int pitch);
static void EdgeLoop_Luma_horizontal(uint8* SrcPtr, uint8 *Strength, int Alpha, int Beta, int *clipTable, int pitch);
static void EdgeLoop_Chroma_vertical(uint8* SrcPtr, uint8 *Strength, int Alpha, int Beta, int *clipTable, int pitch);
static void EdgeLoop_Chroma_horizontal(uint8* SrcPtr, uint8 *Strength, int Alpha, int Beta, int *clipTable, int pitch);

/*
 *****************************************************************************************
 * \brief Filter all macroblocks in order of increasing macroblock address.
 *****************************************************************************************
*/

OSCL_EXPORT_REF AVCStatus DeblockPicture(AVCCommonObj *video)
{
    uint   i, j;
    int   pitch = video->currPic->pitch, pitch_c, width;
    uint8 *SrcY, *SrcU, *SrcV;

    SrcY = video->currPic->Sl;      // pointers to source
    SrcU = video->currPic->Scb;
    SrcV = video->currPic->Scr;
    pitch_c = pitch >> 1;
    width = video->currPic->width;

    for (i = 0; i < video->PicHeightInMbs; i++)
    {
        for (j = 0; j < video->PicWidthInMbs; j++)
        {
            DeblockMb(video, j, i, SrcY, SrcU, SrcV);
            // update SrcY, SrcU, SrcV
            SrcY += MB_BLOCK_SIZE;
            SrcU += (MB_BLOCK_SIZE >> 1);
            SrcV += (MB_BLOCK_SIZE >> 1);
        }

        SrcY += ((pitch << 4) - width);
        SrcU += ((pitch_c << 3) - (width >> 1));
        SrcV += ((pitch_c << 3) - (width >> 1));
    }

    return AVC_SUCCESS;
}

#ifdef MB_BASED_DEBLOCK
/*
 *****************************************************************************************
 * \brief Filter one macroblocks in a fast macroblock memory and copy it to frame
 *****************************************************************************************
*/
void MBInLoopDeblock(AVCCommonObj *video)
{
    AVCPictureData *currPic = video->currPic;
#ifdef USE_PRED_BLOCK
    uint8 *predCb, *predCr, *pred_block;
    int i, j, dst_width, dst_height, dst_widthc, dst_heightc;
#endif
    int pitch = currPic->pitch;
    int x_pos = video->mb_x;
    int y_pos = video->mb_y;
    uint8 *curL, *curCb, *curCr;
    int offset;

    offset = (y_pos << 4) * pitch;

    curL = currPic->Sl + offset + (x_pos << 4);

    offset >>= 2;
    offset += (x_pos << 3);

    curCb = currPic->Scb + offset;
    curCr = currPic->Scr + offset;

#ifdef USE_PRED_BLOCK
    pred_block = video->pred;

    /* 1. copy neighboring pixels from frame to the video->pred_block */
    if (y_pos) /* not the 0th row */
    {
        /* copy to the top 4 lines of the macroblock */
        curL -= (pitch << 2); /* go back 4 lines */

        memcpy(pred_block + 4, curL, 16);
        curL += pitch;
        memcpy(pred_block + 24, curL, 16);
        curL += pitch;
        memcpy(pred_block + 44, curL, 16);
        curL += pitch;
        memcpy(pred_block + 64, curL, 16);
        curL += pitch;

        curCb -= (pitch << 1); /* go back 4 lines chroma */
        curCr -= (pitch << 1);

        pred_block += 400;

        memcpy(pred_block + 4, curCb, 8);
        curCb += (pitch >> 1);
        memcpy(pred_block + 16, curCb, 8);
        curCb += (pitch >> 1);
        memcpy(pred_block + 28, curCb, 8);
        curCb += (pitch >> 1);
        memcpy(pred_block + 40, curCb, 8);
        curCb += (pitch >> 1);

        pred_block += 144;
        memcpy(pred_block + 4, curCr, 8);
        curCr += (pitch >> 1);
        memcpy(pred_block + 16, curCr, 8);
        curCr += (pitch >> 1);
        memcpy(pred_block + 28, curCr, 8);
        curCr += (pitch >> 1);
        memcpy(pred_block + 40, curCr, 8);
        curCr += (pitch >> 1);

        pred_block = video->pred;
    }

    /* 2. perform deblocking. */
    DeblockMb(video, x_pos, y_pos, pred_block + 84, pred_block + 452, pred_block + 596);

    /* 3. copy it back to the frame and update pred_block */
    predCb = pred_block + 400;
    predCr = predCb + 144;

    /* find the range of the block inside pred_block to be copied back */
    if (y_pos)  /* the first row */
    {
        curL -= (pitch << 2);
        curCb -= (pitch << 1);
        curCr -= (pitch << 1);

        dst_height = 20;
        dst_heightc = 12;
    }
    else
    {
        pred_block += 80;
        predCb += 48;
        predCr += 48;
        dst_height = 16;
        dst_heightc = 8;
    }

    if (x_pos) /* find the width */
    {
        curL -= 4;
        curCb -= 4;
        curCr -= 4;
        if (x_pos == (int)(video->PicWidthInMbs - 1))
        {
            dst_width = 20;
            dst_widthc = 12;
        }
        else
        {
            dst_width = 16;
            dst_widthc = 8;
        }
    }
    else
    {
        pred_block += 4;
        predCb += 4;
        predCr += 4;
        dst_width = 12;
        dst_widthc = 4;
    }

    /* perform copy */
    for (j = 0; j < dst_height; j++)
    {
        memcpy(curL, pred_block, dst_width);
        curL += pitch;
        pred_block += 20;
    }
    for (j = 0; j < dst_heightc; j++)
    {
        memcpy(curCb, predCb, dst_widthc);
        memcpy(curCr, predCr, dst_widthc);
        curCb += (pitch >> 1);
        curCr += (pitch >> 1);
        predCb += 12;
        predCr += 12;
    }

    if (x_pos != (int)(video->PicWidthInMbs - 1)) /* now copy from the right-most 4 columns to the left-most 4 columns */
    {
        pred_block = video->pred;
        for (i = 0; i < 20; i += 4)
        {
            *((uint32*)pred_block) = *((uint32*)(pred_block + 16));
            pred_block += 20;
            *((uint32*)pred_block) = *((uint32*)(pred_block + 16));
            pred_block += 20;
            *((uint32*)pred_block) = *((uint32*)(pred_block + 16));
            pred_block += 20;
            *((uint32*)pred_block) = *((uint32*)(pred_block + 16));
            pred_block += 20;
        }

        for (i = 0; i < 24; i += 4)
        {
            *((uint32*)pred_block) = *((uint32*)(pred_block + 8));
            pred_block += 12;
            *((uint32*)pred_block) = *((uint32*)(pred_block + 8));
            pred_block += 12;
            *((uint32*)pred_block) = *((uint32*)(pred_block + 8));
            pred_block += 12;
            *((uint32*)pred_block) = *((uint32*)(pred_block + 8));
            pred_block += 12;
        }

    }
#else
    DeblockMb(video, x_pos, y_pos, curL, curCb, curCr);
#endif

    return ;
}
#endif

/*
 *****************************************************************************************
 * \brief Deblocking filter for one macroblock.
 *****************************************************************************************
 */

void DeblockMb(AVCCommonObj *video, int mb_x, int mb_y, uint8 *SrcY, uint8 *SrcU, uint8 *SrcV)
{
    AVCMacroblock *MbP, *MbQ;
    int     edge, QP, QPC;
    int     filterLeftMbEdgeFlag = (mb_x != 0);
    int     filterTopMbEdgeFlag  = (mb_y != 0);
    int     pitch = video->currPic->pitch;
    int     indexA, indexB;
    int     *tmp;
    int     Alpha, Beta, Alpha_c, Beta_c;
    int     mbNum = mb_y * video->PicWidthInMbs + mb_x;
    int     *clipTable, *clipTable_c, *qp_clip_tab;
    uint8   Strength[16];
    void*     str;

    MbQ = &(video->mblock[mbNum]);      // current Mb


    // If filter is disabled, return
    if (video->sliceHdr->disable_deblocking_filter_idc == 1) return;

    if (video->sliceHdr->disable_deblocking_filter_idc == 2)
    {
        // don't filter at slice boundaries
        filterLeftMbEdgeFlag = mb_is_available(video->mblock, video->PicSizeInMbs, mbNum - 1, mbNum);
        filterTopMbEdgeFlag  = mb_is_available(video->mblock, video->PicSizeInMbs, mbNum - video->PicWidthInMbs, mbNum);
    }

    /* NOTE: edge=0 and edge=1~3 are separate cases because of the difference of MbP, index A and indexB calculation */
    /*       for edge = 1~3, MbP, indexA and indexB remain the same, and thus there is no need to re-calculate them for each edge */

    qp_clip_tab = (int *)QP_CLIP_TAB + 12;

    /* 1.VERTICAL EDGE + MB BOUNDARY (edge = 0) */
    if (filterLeftMbEdgeFlag)
    {
        MbP = MbQ - 1;
        //GetStrength(video, Strength, MbP, MbQ, 0, 0); // Strength for 4 blks in 1 stripe, 0 => vertical edge
        GetStrength_Edge0(Strength, MbP, MbQ, 0);

        str = (void*)Strength; //de-ref type-punned pointer fix
        if (*((uint32*)str))    // only if one of the 4 Strength bytes is != 0
        {
            QP = (MbP->QPy + MbQ->QPy + 1) >> 1; // Average QP of the two blocks;
            indexA = QP + video->FilterOffsetA;
            indexB = QP + video->FilterOffsetB;
            indexA = qp_clip_tab[indexA]; // IClip(0, MAX_QP, QP+video->FilterOffsetA)
            indexB = qp_clip_tab[indexB]; // IClip(0, MAX_QP, QP+video->FilterOffsetB)

            Alpha  = ALPHA_TABLE[indexA];
            Beta = BETA_TABLE[indexB];
            clipTable = (int *) CLIP_TAB[indexA];

            if (Alpha > 0 && Beta > 0)
#ifdef USE_PRED_BLOCK
                EdgeLoop_Luma_vertical(SrcY, Strength,  Alpha, Beta, clipTable, 20);
#else
                EdgeLoop_Luma_vertical(SrcY, Strength,  Alpha, Beta, clipTable, pitch);
#endif

            QPC = (MbP->QPc + MbQ->QPc + 1) >> 1;
            indexA = QPC + video->FilterOffsetA;
            indexB = QPC + video->FilterOffsetB;
            indexA = qp_clip_tab[indexA]; // IClip(0, MAX_QP, QP+video->FilterOffsetA)
            indexB = qp_clip_tab[indexB]; // IClip(0, MAX_QP, QP+video->FilterOffsetB)

            Alpha  = ALPHA_TABLE[indexA];
            Beta = BETA_TABLE[indexB];
            clipTable = (int *) CLIP_TAB[indexA];
            if (Alpha > 0 && Beta > 0)
            {
#ifdef USE_PRED_BLOCK
                EdgeLoop_Chroma_vertical(SrcU, Strength, Alpha, Beta, clipTable, 12);
                EdgeLoop_Chroma_vertical(SrcV, Strength, Alpha, Beta, clipTable, 12);
#else
                EdgeLoop_Chroma_vertical(SrcU, Strength, Alpha, Beta, clipTable, pitch >> 1);
                EdgeLoop_Chroma_vertical(SrcV, Strength, Alpha, Beta, clipTable, pitch >> 1);
#endif
            }
        }

    } /* end of: if(filterLeftMbEdgeFlag) */

    /* 2.VERTICAL EDGE (no boundary), the edges are all inside a MB */
    /* First calculate the necesary parameters all at once, outside the loop */
    MbP = MbQ;

    indexA = MbQ->QPy + video->FilterOffsetA;
    indexB = MbQ->QPy + video->FilterOffsetB;
    //  index
    indexA = qp_clip_tab[indexA]; // IClip(0, MAX_QP, QP+video->FilterOffsetA)
    indexB = qp_clip_tab[indexB]; // IClip(0, MAX_QP, QP+video->FilterOffsetB)

    Alpha = ALPHA_TABLE[indexA];
    Beta = BETA_TABLE[indexB];
    clipTable = (int *)CLIP_TAB[indexA];

    /* Save Alpha,  Beta and clipTable for future use, with the obselete variables filterLeftMbEdgeFlag, mbNum amd tmp */
    filterLeftMbEdgeFlag = Alpha;
    mbNum = Beta;
    tmp = clipTable;

    indexA = MbQ->QPc + video->FilterOffsetA;
    indexB = MbQ->QPc + video->FilterOffsetB;
    indexA = qp_clip_tab[indexA]; // IClip(0, MAX_QP, QP+video->FilterOffsetA)
    indexB = qp_clip_tab[indexB]; // IClip(0, MAX_QP, QP+video->FilterOffsetB)

    Alpha_c  = ALPHA_TABLE[indexA];
    Beta_c = BETA_TABLE[indexB];
    clipTable_c = (int *)CLIP_TAB[indexA];

    GetStrength_VerticalEdges(Strength + 4, MbQ); // Strength for 4 blks in 1 stripe, 0 => vertical edge

    for (edge = 1; edge < 4; edge++)  // 4 vertical strips of 16 pel
    {
        //GetStrength_VerticalEdges(video, Strength, MbP, MbQ, 0, edge); // Strength for 4 blks in 1 stripe, 0 => vertical edge
        if (*((int*)(Strength + (edge << 2))))   // only if one of the 4 Strength bytes is != 0
        {
            if (Alpha > 0 && Beta > 0)
#ifdef USE_PRED_BLOCK
                EdgeLoop_Luma_vertical(SrcY + (edge << 2), Strength + (edge << 2),  Alpha, Beta, clipTable, 20);
#else
                EdgeLoop_Luma_vertical(SrcY + (edge << 2), Strength + (edge << 2),  Alpha, Beta, clipTable, pitch);
#endif

            if (!(edge & 1) && Alpha_c > 0 && Beta_c > 0)
            {
#ifdef USE_PRED_BLOCK
                EdgeLoop_Chroma_vertical(SrcU + (edge << 1), Strength + (edge << 2), Alpha_c, Beta_c, clipTable_c, 12);
                EdgeLoop_Chroma_vertical(SrcV + (edge << 1), Strength + (edge << 2), Alpha_c, Beta_c, clipTable_c, 12);
#else
                EdgeLoop_Chroma_vertical(SrcU + (edge << 1), Strength + (edge << 2), Alpha_c, Beta_c, clipTable_c, pitch >> 1);
                EdgeLoop_Chroma_vertical(SrcV + (edge << 1), Strength + (edge << 2), Alpha_c, Beta_c, clipTable_c, pitch >> 1);
#endif
            }
        }

    } //end edge



    /* 3.HORIZONTAL EDGE + MB BOUNDARY (edge = 0) */
    if (filterTopMbEdgeFlag)
    {
        MbP = MbQ - video->PicWidthInMbs;
        //GetStrength(video, Strength, MbP, MbQ, 1, 0); // Strength for 4 blks in 1 stripe, 0 => vertical edge
        GetStrength_Edge0(Strength, MbP, MbQ, 1);
        str = (void*)Strength; //de-ref type-punned pointer fix
        if (*((uint32*)str))    // only if one of the 4 Strength bytes is != 0
        {
            QP = (MbP->QPy + MbQ->QPy + 1) >> 1; // Average QP of the two blocks;
            indexA = QP + video->FilterOffsetA;
            indexB = QP + video->FilterOffsetB;
            indexA = qp_clip_tab[indexA]; // IClip(0, MAX_QP, QP+video->FilterOffsetA)
            indexB = qp_clip_tab[indexB]; // IClip(0, MAX_QP, QP+video->FilterOffsetB)

            Alpha  = ALPHA_TABLE[indexA];
            Beta = BETA_TABLE[indexB];
            clipTable = (int *)CLIP_TAB[indexA];

            if (Alpha > 0 && Beta > 0)
            {
#ifdef USE_PRED_BLOCK
                EdgeLoop_Luma_horizontal(SrcY, Strength,  Alpha, Beta, clipTable, 20);
#else
                EdgeLoop_Luma_horizontal(SrcY, Strength,  Alpha, Beta, clipTable, pitch);
#endif
            }

            QPC = (MbP->QPc + MbQ->QPc + 1) >> 1;
            indexA = QPC + video->FilterOffsetA;
            indexB = QPC + video->FilterOffsetB;
            indexA = qp_clip_tab[indexA]; // IClip(0, MAX_QP, QP+video->FilterOffsetA)
            indexB = qp_clip_tab[indexB]; // IClip(0, MAX_QP, QP+video->FilterOffsetB)

            Alpha  = ALPHA_TABLE[indexA];
            Beta = BETA_TABLE[indexB];
            clipTable = (int *)CLIP_TAB[indexA];
            if (Alpha > 0 && Beta > 0)
            {
#ifdef USE_PRED_BLOCK
                EdgeLoop_Chroma_horizontal(SrcU, Strength, Alpha, Beta, clipTable, 12);
                EdgeLoop_Chroma_horizontal(SrcV, Strength, Alpha, Beta, clipTable, 12);
#else
                EdgeLoop_Chroma_horizontal(SrcU, Strength, Alpha, Beta, clipTable, pitch >> 1);
                EdgeLoop_Chroma_horizontal(SrcV, Strength, Alpha, Beta, clipTable, pitch >> 1);
#endif
            }
        }

    } /* end of: if(filterTopMbEdgeFlag) */


    /* 4.HORIZONTAL EDGE (no boundary), the edges are inside a MB */
    MbP = MbQ;

    /* Recover Alpha,  Beta and clipTable for edge!=0 with the variables filterLeftMbEdgeFlag, mbNum and tmp */
    /* Note that Alpha_c, Beta_c and clipTable_c for chroma is already calculated */
    Alpha = filterLeftMbEdgeFlag;
    Beta = mbNum;
    clipTable = tmp;

    GetStrength_HorizontalEdges(Strength + 4, MbQ); // Strength for 4 blks in 1 stripe, 0 => vertical edge

    for (edge = 1; edge < 4; edge++)  // 4 horicontal strips of 16 pel
    {
        //GetStrength(video, Strength, MbP, MbQ, 1, edge); // Strength for 4 blks in 1 stripe   1 => horizontal edge
        if (*((int*)(Strength + (edge << 2)))) // only if one of the 4 Strength bytes is != 0
        {
            if (Alpha > 0 && Beta > 0)
            {
#ifdef USE_PRED_BLOCK
                EdgeLoop_Luma_horizontal(SrcY + (edge << 2)*20, Strength + (edge << 2),  Alpha, Beta, clipTable, 20);
#else
                EdgeLoop_Luma_horizontal(SrcY + (edge << 2)*pitch, Strength + (edge << 2),  Alpha, Beta, clipTable, pitch);
#endif
            }

            if (!(edge & 1) && Alpha_c > 0 && Beta_c > 0)
            {
#ifdef USE_PRED_BLOCK
                EdgeLoop_Chroma_horizontal(SrcU + (edge << 1)*12, Strength + (edge << 2), Alpha_c, Beta_c, clipTable_c, 12);
                EdgeLoop_Chroma_horizontal(SrcV + (edge << 1)*12, Strength + (edge << 2), Alpha_c, Beta_c, clipTable_c, 12);
#else
                EdgeLoop_Chroma_horizontal(SrcU + (edge << 1)*(pitch >> 1), Strength + (edge << 2), Alpha_c, Beta_c, clipTable_c, pitch >> 1);
                EdgeLoop_Chroma_horizontal(SrcV + (edge << 1)*(pitch >> 1), Strength + (edge << 2), Alpha_c, Beta_c, clipTable_c, pitch >> 1);
#endif
            }
        }

    } //end edge

    return;
}

/*
 *****************************************************************************************************
 * \brief   returns a buffer of 4 Strength values for one stripe in a mb (for different Frame types)
 *****************************************************************************************************
*/

void GetStrength_Edge0(uint8 *Strength, AVCMacroblock* MbP, AVCMacroblock* MbQ, int dir)
{
    int tmp;
    int16 *ptrQ, *ptrP;
    void* vptr;
    uint8 *pStrength;
    void* refIdx;

    if (MbP->mbMode == AVC_I4 || MbP->mbMode == AVC_I16 ||
            MbQ->mbMode == AVC_I4 || MbQ->mbMode == AVC_I16)
    {

        *((int*)Strength) = ININT_STRENGTH[0];      // Start with Strength=3. or Strength=4 for Mb-edge

    }
    else // if not intra or SP-frame
    {
        *((int*)Strength) = 0;

        if (dir == 0)  // Vertical Edge 0
        {

            //1. Check the ref_frame_id
            refIdx = (void*) MbQ->RefIdx; //de-ref type-punned pointer fix
            ptrQ = (int16*)refIdx;
            refIdx = (void*)MbP->RefIdx; //de-ref type-punned pointer fix
            ptrP = (int16*)refIdx;
            pStrength = Strength;
            if (ptrQ[0] != ptrP[1]) pStrength[0] = 1;
            if (ptrQ[2] != ptrP[3]) pStrength[2] = 1;
            pStrength[1] = pStrength[0];
            pStrength[3] = pStrength[2];

            //2. Check the non-zero coeff blocks (4x4)
            if (MbQ->nz_coeff[0] != 0 || MbP->nz_coeff[3] != 0) pStrength[0] = 2;
            if (MbQ->nz_coeff[4] != 0 || MbP->nz_coeff[7] != 0) pStrength[1] = 2;
            if (MbQ->nz_coeff[8] != 0 || MbP->nz_coeff[11] != 0) pStrength[2] = 2;
            if (MbQ->nz_coeff[12] != 0 || MbP->nz_coeff[15] != 0) pStrength[3] = 2;

            //3. Only need to check the mv difference
            vptr = (void*)MbQ->mvL0;  // for deref type-punned pointer
            ptrQ = (int16*)vptr;
            ptrP = (int16*)(MbP->mvL0 + 3); // points to 4x4 block #3 (the 4th column)

            // 1st blk
            if (*pStrength == 0)
            {
                // check |mv difference| >= 4
                tmp = *ptrQ++ - *ptrP++;
                if (tmp < 0) tmp = -tmp;
                if (tmp >= 4) *pStrength = 1;

                tmp = *ptrQ-- - *ptrP--;
                if (tmp < 0) tmp = -tmp;
                if (tmp >= 4) *pStrength = 1;
            }

            pStrength++;
            ptrQ += 8;
            ptrP += 8;

            // 2nd blk
            if (*pStrength == 0)
            {
                // check |mv difference| >= 4
                tmp = *ptrQ++ - *ptrP++;
                if (tmp < 0) tmp = -tmp;
                if (tmp >= 4) *pStrength = 1;

                tmp = *ptrQ-- - *ptrP--;
                if (tmp < 0) tmp = -tmp;
                if (tmp >= 4) *pStrength = 1;
            }

            pStrength++;
            ptrQ += 8;
            ptrP += 8;

            // 3rd blk
            if (*pStrength == 0)
            {
                // check |mv difference| >= 4
                tmp = *ptrQ++ - *ptrP++;
                if (tmp < 0) tmp = -tmp;
                if (tmp >= 4) *pStrength = 1;

                tmp = *ptrQ-- - *ptrP--;
                if (tmp < 0) tmp = -tmp;
                if (tmp >= 4) *pStrength = 1;
            }

            pStrength++;
            ptrQ += 8;
            ptrP += 8;

            // 4th blk
            if (*pStrength == 0)
            {
                // check |mv difference| >= 4
                tmp = *ptrQ++ - *ptrP++;
                if (tmp < 0) tmp = -tmp;
                if (tmp >= 4) *pStrength = 1;

                tmp = *ptrQ-- - *ptrP--;
                if (tmp < 0) tmp = -tmp;
                if (tmp >= 4) *pStrength = 1;
            }
        }
        else   // Horizontal Edge 0
        {

            //1. Check the ref_frame_id
            refIdx = (void*)MbQ->RefIdx;  //de-ref type-punned pointer
            ptrQ = (int16*)refIdx;
            refIdx = (void*)MbP->RefIdx;  //de-ref type-punned pointer
            ptrP = (int16*)refIdx;
            pStrength = Strength;
            if (ptrQ[0] != ptrP[2]) pStrength[0] = 1;
            if (ptrQ[1] != ptrP[3]) pStrength[2] = 1;
            pStrength[1] = pStrength[0];
            pStrength[3] = pStrength[2];

            //2. Check the non-zero coeff blocks (4x4)
            if (MbQ->nz_coeff[0] != 0 || MbP->nz_coeff[12] != 0) pStrength[0] = 2;
            if (MbQ->nz_coeff[1] != 0 || MbP->nz_coeff[13] != 0) pStrength[1] = 2;
            if (MbQ->nz_coeff[2] != 0 || MbP->nz_coeff[14] != 0) pStrength[2] = 2;
            if (MbQ->nz_coeff[3] != 0 || MbP->nz_coeff[15] != 0) pStrength[3] = 2;

            //3. Only need to check the mv difference
            vptr = (void*)MbQ->mvL0;
            ptrQ = (int16*)vptr;
            ptrP = (int16*)(MbP->mvL0 + 12); // points to 4x4 block #12 (the 4th row)

            // 1st blk
            if (*pStrength == 0)
            {
                // check |mv difference| >= 4
                tmp = *ptrQ++ - *ptrP++;
                if (tmp < 0) tmp = -tmp;
                if (tmp >= 4) *pStrength = 1;

                tmp = *ptrQ-- - *ptrP--;
                if (tmp < 0) tmp = -tmp;
                if (tmp >= 4) *pStrength = 1;
            }

            pStrength++;
            ptrQ += 2;
            ptrP += 2;

            // 2nd blk
            if (*pStrength  == 0)
            {
                // check |mv difference| >= 4
                tmp = *ptrQ++ - *ptrP++;
                if (tmp < 0) tmp = -tmp;
                if (tmp >= 4) *pStrength = 1;

                tmp = *ptrQ-- - *ptrP--;
                if (tmp < 0) tmp = -tmp;
                if (tmp >= 4) *pStrength = 1;
            }

            pStrength++;
            ptrQ += 2;
            ptrP += 2;

            // 3rd blk
            if (*pStrength  == 0)
            {
                // check |mv difference| >= 4
                tmp = *ptrQ++ - *ptrP++;
                if (tmp < 0) tmp = -tmp;
                if (tmp >= 4) *pStrength = 1;

                tmp = *ptrQ-- - *ptrP--;
                if (tmp < 0) tmp = -tmp;
                if (tmp >= 4) *pStrength = 1;
            }

            pStrength++;
            ptrQ += 2;
            ptrP += 2;

            // 4th blk
            if (*pStrength  == 0)
            {
                // check |mv difference| >= 4
                tmp = *ptrQ++ - *ptrP++;
                if (tmp < 0) tmp = -tmp;
                if (tmp >= 4) *pStrength = 1;

                tmp = *ptrQ-- - *ptrP--;
                if (tmp < 0) tmp = -tmp;
                if (tmp >= 4) *pStrength = 1;
            }

        } /* end of: else if(dir == 0) */

    } /* end of: if( !(MbP->mbMode == AVC_I4 ...) */
}


void GetStrength_VerticalEdges(uint8 *Strength, AVCMacroblock* MbQ)
{
    int     idx, tmp;
    int16   *ptr, *pmvx, *pmvy;
    uint8   *pnz;
    uint8   *pStrength, *pStr;
    void* refIdx;

    if (MbQ->mbMode == AVC_I4 || MbQ->mbMode == AVC_I16)
    {
        *((int*)Strength)     = ININT_STRENGTH[1];      // Start with Strength=3. or Strength=4 for Mb-edge
        *((int*)(Strength + 4)) = ININT_STRENGTH[2];
        *((int*)(Strength + 8)) = ININT_STRENGTH[3];
    }
    else   // Not intra or SP-frame
    {

        *((int*)Strength)     = 0; // for non-intra MB, strength = 0, 1 or 2.
        *((int*)(Strength + 4)) = 0;
        *((int*)(Strength + 8)) = 0;

        //1. Check the ref_frame_id
        refIdx = (void*)MbQ->RefIdx;  //de-ref type-punned pointer fix
        ptr = (int16*)refIdx;
        pStrength = Strength;
        if (ptr[0] != ptr[1]) pStrength[4] = 1;
        if (ptr[2] != ptr[3]) pStrength[6] = 1;
        pStrength[5] = pStrength[4];
        pStrength[7] = pStrength[6];

        //2. Check the nz_coeff block and mv difference
        pmvx = (int16*)(MbQ->mvL0 + 1); // points to 4x4 block #1,not #0
        pmvy = pmvx + 1;
        for (idx = 0; idx < 4; idx += 2) // unroll the loop, make 4 iterations to 2
        {
            // first/third row : 1,2,3 or 9,10,12
            // Strength = 2 for a whole row
            pnz = MbQ->nz_coeff + (idx << 2);
            if (*pnz++ != 0) *pStrength = 2;
            if (*pnz++ != 0)
            {
                *pStrength = 2;
                *(pStrength + 4) = 2;
            }
            if (*pnz++ != 0)
            {
                *(pStrength + 4) = 2;
                *(pStrength + 8) = 2;
            }
            if (*pnz != 0) *(pStrength + 8) = 2;

            // Then Strength = 1
            if (*pStrength == 0)
            {
                //within the same 8x8 block, no need to check the reference id
                //only need to check the |mv difference| >= 4
                tmp = *pmvx - *(pmvx - 2);
                if (tmp < 0) tmp = -tmp;
                if (tmp >= 4) *pStrength = 1;

                tmp = *pmvy - *(pmvy - 2);
                if (tmp < 0) tmp = -tmp;
                if (tmp >= 4) *pStrength = 1;
            }

            pmvx += 2;
            pmvy += 2;
            pStr = pStrength + 4;

            if (*pStr == 0)
            {
                //check the |mv difference| >= 4
                tmp = *pmvx - *(pmvx - 2);
                if (tmp < 0) tmp = -tmp;
                if (tmp >= 4) *pStr = 1;

                tmp = *pmvy - *(pmvy - 2);
                if (tmp < 0) tmp = -tmp;
                if (tmp >= 4) *pStr = 1;
            }

            pmvx += 2;
            pmvy += 2;
            pStr = pStrength + 8;

            if (*pStr == 0)
            {
                //within the same 8x8 block, no need to check the reference id
                //only need to check the |mv difference| >= 4
                tmp = *pmvx - *(pmvx - 2);
                if (tmp < 0) tmp = -tmp;
                if (tmp >= 4) *pStr = 1;

                tmp = *pmvy - *(pmvy - 2);
                if (tmp < 0) tmp = -tmp;
                if (tmp >= 4) *pStr = 1;
            }

            // Second/fourth row: 5,6,7 or 14,15,16
            // Strength = 2 for a whole row
            pnz = MbQ->nz_coeff + ((idx + 1) << 2);
            if (*pnz++ != 0) *(pStrength + 1) = 2;
            if (*pnz++ != 0)
            {
                *(pStrength + 1) = 2;
                *(pStrength + 5) = 2;
            }
            if (*pnz++ != 0)
            {
                *(pStrength + 5) = 2;
                *(pStrength + 9) = 2;
            }
            if (*pnz != 0) *(pStrength + 9) = 2;

            // Then Strength = 1
            pmvx += 4;
            pmvy += 4;
            pStr = pStrength + 1;
            if (*pStr == 0)
            {
                //within the same 8x8 block, no need to check the reference id
                //only need to check the |mv difference| >= 4
                tmp = *pmvx - *(pmvx - 2);
                if (tmp < 0) tmp = -tmp;
                if (tmp >= 4) *pStr = 1;

                tmp = *pmvy - *(pmvy - 2);
                if (tmp < 0) tmp = -tmp;
                if (tmp >= 4) *pStr = 1;
            }

            pmvx += 2;
            pmvy += 2;
            pStr = pStrength + 5;

            if (*pStr == 0)
            {
                //check the |mv difference| >= 4
                tmp = *pmvx - *(pmvx - 2);
                if (tmp < 0) tmp = -tmp;
                if (tmp >= 4) *pStr = 1;

                tmp = *pmvy - *(pmvy - 2);
                if (tmp < 0) tmp = -tmp;
                if (tmp >= 4) *pStr = 1;
            }

            pmvx += 2;
            pmvy += 2;
            pStr = pStrength + 9;

            if (*pStr == 0)
            {
                //within the same 8x8 block, no need to check the reference id
                //only need to check the |mv difference| >= 4
                tmp = *pmvx - *(pmvx - 2);
                if (tmp < 0) tmp = -tmp;
                if (tmp >= 4) *pStr = 1;

                tmp = *pmvy - *(pmvy - 2);
                if (tmp < 0) tmp = -tmp;
                if (tmp >= 4) *pStr = 1;
            }

            // update some variables for the next two rows
            pmvx += 4;
            pmvy += 4;
            pStrength += 2;

        } /* end of: for(idx=0; idx<2; idx++) */

    } /* end of: else if( MbQ->mbMode == AVC_I4 ...) */
}


void GetStrength_HorizontalEdges(uint8 Strength[12], AVCMacroblock* MbQ)
{
    int     idx, tmp;
    int16   *ptr, *pmvx, *pmvy;
    uint8   *pStrength, *pStr;
    void* refIdx;

    if (MbQ->mbMode == AVC_I4 || MbQ->mbMode == AVC_I16)
    {
        *((int*)Strength)     = ININT_STRENGTH[1];      // Start with Strength=3. or Strength=4 for Mb-edge
        *((int*)(Strength + 4)) = ININT_STRENGTH[2];
        *((int*)(Strength + 8)) = ININT_STRENGTH[3];
    }
    else   // Not intra or SP-frame
    {

        *((int*)Strength)     = 0; // for non-intra MB, strength = 0, 1 or 2.
        *((int*)(Strength + 4)) = 0; // for non-intra MB, strength = 0, 1 or 2.
        *((int*)(Strength + 8)) = 0; // for non-intra MB, strength = 0, 1 or 2.


        //1. Check the ref_frame_id
        refIdx = (void*) MbQ->RefIdx; // de-ref type-punned fix
        ptr = (int16*) refIdx;
        pStrength = Strength;
        if (ptr[0] != ptr[2]) pStrength[4] = 1;
        if (ptr[1] != ptr[3]) pStrength[6] = 1;
        pStrength[5] = pStrength[4];
        pStrength[7] = pStrength[6];

        //2. Check the nz_coeff block and mv difference
        pmvx = (int16*)(MbQ->mvL0 + 4); // points to 4x4 block #4,not #0
        pmvy = pmvx + 1;
        for (idx = 0; idx < 4; idx += 2) // unroll the loop, make 4 iterations to 2
        {
            // first/third row : 1,2,3 or 9,10,12
            // Strength = 2 for a whole row
            if (MbQ->nz_coeff[idx] != 0) *pStrength = 2;
            if (MbQ->nz_coeff[4+idx] != 0)
            {
                *pStrength = 2;
                *(pStrength + 4) = 2;
            }
            if (MbQ->nz_coeff[8+idx] != 0)
            {
                *(pStrength + 4) = 2;
                *(pStrength + 8) = 2;
            }
            if (MbQ->nz_coeff[12+idx] != 0) *(pStrength + 8) = 2;

            // Then Strength = 1
            if (*pStrength == 0)
            {
                //within the same 8x8 block, no need to check the reference id
                //only need to check the |mv difference| >= 4
                tmp = *pmvx - *(pmvx - 8);
                if (tmp < 0) tmp = -tmp;
                if (tmp >= 4) *pStrength = 1;

                tmp = *pmvy - *(pmvy - 8);
                if (tmp < 0) tmp = -tmp;
                if (tmp >= 4) *pStrength = 1;
            }

            pmvx += 8;
            pmvy += 8;
            pStr = pStrength + 4;

            if (*pStr == 0)
            {
                //check the |mv difference| >= 4
                tmp = *pmvx - *(pmvx - 8);
                if (tmp < 0) tmp = -tmp;
                if (tmp >= 4) *pStr = 1;

                tmp = *pmvy - *(pmvy - 8);
                if (tmp < 0) tmp = -tmp;
                if (tmp >= 4) *pStr = 1;
            }

            pmvx += 8;
            pmvy += 8;
            pStr = pStrength + 8;

            if (*pStr == 0)
            {
                //within the same 8x8 block, no need to check the reference id
                //only need to check the |mv difference| >= 4
                tmp = *pmvx - *(pmvx - 8);
                if (tmp < 0) tmp = -tmp;
                if (tmp >= 4) *pStr = 1;

                tmp = *pmvy - *(pmvy - 8);
                if (tmp < 0) tmp = -tmp;
                if (tmp >= 4) *pStr = 1;
            }

            // Second/fourth row: 5,6,7 or 14,15,16
            // Strength = 2 for a whole row
            if (MbQ->nz_coeff[idx+1] != 0) *(pStrength + 1) = 2;
            if (MbQ->nz_coeff[4+idx+1] != 0)
            {
                *(pStrength + 1) = 2;
                *(pStrength + 5) = 2;
            }
            if (MbQ->nz_coeff[8+idx+1] != 0)
            {
                *(pStrength + 5) = 2;
                *(pStrength + 9) = 2;
            }
            if (MbQ->nz_coeff[12+idx+1] != 0) *(pStrength + 9) = 2;

            // Then Strength = 1
            pmvx -= 14;
            pmvy -= 14; // -14 = -16 + 2
            pStr = pStrength + 1;
            if (*pStr == 0)
            {
                //within the same 8x8 block, no need to check the reference id
                //only need to check the |mv difference| >= 4
                tmp = *pmvx - *(pmvx - 8);
                if (tmp < 0) tmp = -tmp;
                if (tmp >= 4) *pStr = 1;

                tmp = *pmvy - *(pmvy - 8);
                if (tmp < 0) tmp = -tmp;
                if (tmp >= 4) *pStr = 1;
            }

            pmvx += 8;
            pmvy += 8;
            pStr = pStrength + 5;

            if (*pStr == 0)
            {
                //check the |mv difference| >= 4
                tmp = *pmvx - *(pmvx - 8);
                if (tmp < 0) tmp = -tmp;
                if (tmp >= 4) *pStr = 1;

                tmp = *pmvy - *(pmvy - 8);
                if (tmp < 0) tmp = -tmp;
                if (tmp >= 4) *pStr = 1;
            }

            pmvx += 8;
            pmvy += 8;
            pStr = pStrength + 9;

            if (*pStr == 0)
            {
                //within the same 8x8 block, no need to check the reference id
                //only need to check the |mv difference| >= 4
                tmp = *pmvx - *(pmvx - 8);
                if (tmp < 0) tmp = -tmp;
                if (tmp >= 4) *pStr = 1;

                tmp = *pmvy - *(pmvy - 8);
                if (tmp < 0) tmp = -tmp;
                if (tmp >= 4) *pStr = 1;
            }

            // update some variables for the next two rows
            pmvx -= 14;
            pmvy -= 14; // -14 = -16 + 2
            pStrength += 2;

        } /* end of: for(idx=0; idx<2; idx++) */

    } /* end of: else if( MbQ->mbMode == AVC_I4 ...) */
}

/*
 *****************************************************************************************
 * \brief  Filters one edge of 16 (luma) or 8 (chroma) pel
 *****************************************************************************************
*/

void EdgeLoop_Luma_horizontal(uint8* SrcPtr, uint8 *Strength, int Alpha, int Beta, int *clipTable, int pitch)
{
    int  pel, ap = 0, aq = 0, Strng;
    int  C0, c0, dif, AbsDelta, tmp, tmp1;
    int  L2 = 0, L1, L0, R0, R1, R2 = 0, RL0;


    if (Strength[0] == 4)  /* INTRA strong filtering */
    {
        for (pel = 0; pel < 16; pel++)
        {
            R0  = SrcPtr[0];
            R1  = SrcPtr[pitch];
            L0  = SrcPtr[-pitch];
            L1  = SrcPtr[-(pitch<<1)];

            // |R0 - R1| < Beta
            tmp1 = R0 - R1;
            if (tmp1 < 0) tmp1 = -tmp1;
            tmp = (tmp1 - Beta);

            //|L0 - L1| < Beta
            tmp1 = L0 - L1;
            if (tmp1 < 0) tmp1 = -tmp1;
            tmp &= (tmp1 - Beta);

            //|R0 - L0| < Alpha
            AbsDelta = R0 - L0;
            if (AbsDelta < 0) AbsDelta = -AbsDelta;
            tmp &= (AbsDelta - Alpha);

            if (tmp < 0)
            {
                AbsDelta -= ((Alpha >> 2) + 2);
                R2 = SrcPtr[pitch<<1]; //inc2
                L2 = SrcPtr[-(pitch+(pitch<<1))]; // -inc3

                // |R0 - R2| < Beta && |R0 - L0| < (Alpha/4 + 2)
                tmp = R0 - R2;
                if (tmp < 0) tmp = -tmp;
                aq = AbsDelta & (tmp - Beta);

                // |L0 - L2| < Beta && |R0 - L0| < (Alpha/4 + 2)
                tmp = L0 - L2;
                if (tmp < 0) tmp = -tmp;
                ap = AbsDelta & (tmp - Beta);

                if (aq < 0)
                {
                    tmp = R1 + R0 + L0;
                    SrcPtr[0] = (L1 + (tmp << 1) +  R2 + 4) >> 3;
                    tmp += R2;
                    SrcPtr[pitch]  = (tmp + 2) >> 2;
                    SrcPtr[pitch<<1] = (((SrcPtr[(pitch+(pitch<<1))] + R2) << 1) + tmp + 4) >> 3;
                }
                else
                    SrcPtr[0] = ((R1 << 1) + R0 + L1 + 2) >> 2;

                if (ap < 0)
                {
                    tmp = L1 + R0 + L0;
                    SrcPtr[-pitch]  = (R1 + (tmp << 1) +  L2 + 4) >> 3;
                    tmp += L2;
                    SrcPtr[-(pitch<<1)] = (tmp + 2) >> 2;
                    SrcPtr[-(pitch+(pitch<<1))] = (((SrcPtr[-(pitch<<2)] + L2) << 1) + tmp + 4) >> 3;
                }
                else
                    SrcPtr[-pitch] = ((L1 << 1) + L0 + R1 + 2) >> 2;

            } /* if(tmp < 0) */

            SrcPtr ++; // Increment to next set of pixel

        } /* end of: for(pel=0; pel<16; pel++) */

    } /* if(Strength[0] == 4) */

    else   /* Normal filtering */
    {
        for (pel = 0; pel < 16; pel++)
        {
            Strng = Strength[pel >> 2];
            if (Strng)
            {
                R0  = SrcPtr[0];
                R1  = SrcPtr[pitch];
                L0  = SrcPtr[-pitch];
                L1  = SrcPtr[-(pitch<<1)]; // inc2

                //|R0 - L0| < Alpha
                tmp1 = R0 - L0;
                if (tmp1 < 0) tmp1 = -tmp1;
                tmp = (tmp1 - Alpha);

                // |R0 - R1| < Beta
                tmp1 = R0 - R1;
                if (tmp1 < 0) tmp1 = -tmp1;
                tmp &= (tmp1 - Beta);

                //|L0 - L1| < Beta
                tmp1 = L0 - L1;
                if (tmp1 < 0) tmp1 = -tmp1;
                tmp &= (tmp1 - Beta);

                if (tmp < 0)
                {
                    R2 = SrcPtr[pitch<<1]; //inc2
                    L2 = SrcPtr[-(pitch+(pitch<<1))]; // -inc3

                    // |R0 - R2| < Beta
                    tmp = R0 - R2;
                    if (tmp < 0) tmp = -tmp;
                    aq = tmp - Beta;

                    // |L0 - L2| < Beta
                    tmp = L0 - L2;
                    if (tmp < 0) tmp = -tmp;
                    ap = tmp - Beta;


                    c0 = C0 = clipTable[Strng];
                    if (ap < 0) c0++;
                    if (aq < 0) c0++;

                    //dif = IClip(-c0, c0, ((Delta << 2) + (L1 - R1) + 4) >> 3);
                    dif = (((R0 - L0) << 2) + (L1 - R1) + 4) >> 3;
                    tmp = dif + c0;
                    if ((uint)tmp > (uint)c0 << 1)
                    {
                        tmp = ~(tmp >> 31);
                        dif = (tmp & (c0 << 1)) - c0;
                    }

                    //SrcPtr[0]    = (uint8)IClip(0, 255, R0 - dif);
                    //SrcPtr[-inc] = (uint8)IClip(0, 255, L0 + dif);
                    RL0 = R0 + L0;
                    R0 -= dif;
                    L0 += dif;
                    if ((uint)R0 > 255)
                    {
                        tmp = ~(R0 >> 31);
                        R0 = tmp & 255;
                    }
                    if ((uint)L0 > 255)
                    {
                        tmp = ~(L0 >> 31);
                        L0 = tmp & 255;
                    }
                    SrcPtr[-pitch] = L0;
                    SrcPtr[0] = R0;

                    if (C0 != 0) /* Multiple zeros in the clip tables */
                    {
                        if (aq < 0)  // SrcPtr[inc]   += IClip(-C0, C0,(R2 + ((RL0 + 1) >> 1) - (R1<<1)) >> 1);
                        {
                            R2 = (R2 + ((RL0 + 1) >> 1) - (R1 << 1)) >> 1;
                            tmp = R2 + C0;
                            if ((uint)tmp > (uint)C0 << 1)
                            {
                                tmp = ~(tmp >> 31);
                                R2 = (tmp & (C0 << 1)) - C0;
                            }
                            SrcPtr[pitch] += R2;
                        }

                        if (ap < 0)  //SrcPtr[-inc2] += IClip(-C0, C0,(L2 + ((RL0 + 1) >> 1) - (L1<<1)) >> 1);
                        {
                            L2 = (L2 + ((RL0 + 1) >> 1) - (L1 << 1)) >> 1;
                            tmp = L2 + C0;
                            if ((uint)tmp > (uint)C0 << 1)
                            {
                                tmp = ~(tmp >> 31);
                                L2 = (tmp & (C0 << 1)) - C0;
                            }
                            SrcPtr[-(pitch<<1)] += L2;
                        }
                    }

                } /* if(tmp < 0) */

            } /* end of:  if((Strng = Strength[pel >> 2])) */

            SrcPtr ++; // Increment to next set of pixel

        } /* for(pel=0; pel<16; pel++) */

    } /* else if(Strength[0] == 4) */
}

void EdgeLoop_Luma_vertical(uint8* SrcPtr, uint8 *Strength, int Alpha, int Beta, int *clipTable, int pitch)
{
    int  pel, ap = 1, aq = 1;
    int  C0, c0, dif, AbsDelta, Strng, tmp, tmp1;
    int  L2 = 0, L1, L0, R0, R1, R2 = 0;
    uint8 *ptr, *ptr1;
    register uint R_in, L_in;
    uint R_out, L_out;


    if (Strength[0] == 4)  /* INTRA strong filtering */
    {

        for (pel = 0; pel < 16; pel++)
        {

            // Read 8 pels
            R_in = *((uint *)SrcPtr);       // R_in = {R3, R2, R1, R0}
            L_in = *((uint *)(SrcPtr - 4)); // L_in = {L0, L1, L2, L3}
            R1   = (R_in >> 8) & 0xff;
            R0   = R_in & 0xff;
            L0   = L_in >> 24;
            L1   = (L_in >> 16) & 0xff;

            // |R0 - R1| < Beta
            tmp1 = (R_in & 0xff) - R1;
            if (tmp1 < 0) tmp1 = -tmp1;
            tmp = (tmp1 - Beta);


            //|L0 - L1| < Beta
            tmp1 = (L_in >> 24) - L1;
            if (tmp1 < 0) tmp1 = -tmp1;
            tmp &= (tmp1 - Beta);

            //|R0 - L0| < Alpha
            AbsDelta = (R_in & 0xff) - (L_in >> 24);
            if (AbsDelta < 0) AbsDelta = -AbsDelta;
            tmp &= (AbsDelta - Alpha);

            if (tmp < 0)
            {
                AbsDelta -= ((Alpha >> 2) + 2);
                R2   = (R_in >> 16) & 0xff;
                L2   = (L_in >> 8) & 0xff;

                // |R0 - R2| < Beta && |R0 - L0| < (Alpha/4 + 2)
                tmp1 = (R_in & 0xff) - R2;
                if (tmp1 < 0) tmp1 = -tmp1;
                aq = AbsDelta & (tmp1 - Beta);

                // |L0 - L2| < Beta && |R0 - L0| < (Alpha/4 + 2)
                tmp1 = (L_in >> 24) - L2;
                if (tmp1 < 0) tmp1 = -tmp1;
                ap = AbsDelta & (tmp1 - Beta);


                ptr = SrcPtr;
                if (aq < 0)
                {
                    R_out = (R_in >> 24) << 24; // Keep R3 at the fourth byte

                    tmp  = R0 + L0 + R1;
                    R_out |= (((tmp << 1) +  L1 + R2 + 4) >> 3);
                    tmp += R2;
                    R_out |= (((tmp + 2) >> 2) << 8);
                    tmp1 = ((R_in >> 24) + R2) << 1;
                    R_out |= (((tmp1 + tmp + 4) >> 3) << 16);

                    *((uint *)SrcPtr) = R_out;
                }
                else
                    *ptr = ((R1 << 1) + R0 + L1 + 2) >> 2;


                if (ap < 0)
                {
                    L_out = (L_in << 24) >> 24; // Keep L3 at the first byte

                    tmp  = R0 + L0 + L1;
                    L_out |= ((((tmp << 1) + R1 + L2 + 4) >> 3) << 24);
                    tmp += L2;
                    L_out |= (((tmp + 2) >> 2) << 16);
                    tmp1 = ((L_in & 0xff) + L2) << 1;
                    L_out |= (((tmp1 + tmp + 4) >> 3) << 8);

                    *((uint *)(SrcPtr - 4)) = L_out;
                }
                else
                    *(--ptr) = ((L1 << 1) + L0 + R1 + 2) >> 2;

            } /* if(tmp < 0) */

            SrcPtr += pitch;    // Increment to next set of pixel

        } /* end of: for(pel=0; pel<16; pel++) */

    } /* if(Strength[0] == 4) */

    else   /* Normal filtering */
    {

        for (pel = 0; pel < 16; pel++)
        {
            Strng = Strength[pel >> 2];
            if (Strng)
            {
                // Read 8 pels
                R_in = *((uint *)SrcPtr);       // R_in = {R3, R2, R1, R0}
                L_in = *((uint *)(SrcPtr - 4)); // L_in = {L0, L1, L2, L3}
                R1   = (R_in >> 8) & 0xff;
                R0   = R_in & 0xff;
                L0   = L_in >> 24;
                L1   = (L_in >> 16) & 0xff;

                //|R0 - L0| < Alpha
                tmp = R0 - L0;
                if (tmp < 0) tmp = -tmp;
                tmp -= Alpha;

                // |R0 - R1| < Beta
                tmp1 = R0 - R1;
                if (tmp1 < 0) tmp1 = -tmp1;
                tmp &= (tmp1 - Beta);

                //|L0 - L1| < Beta
                tmp1 = L0 - L1;
                if (tmp1 < 0) tmp1 = -tmp1;
                tmp &= (tmp1 - Beta);

                if (tmp < 0)
                {
                    L2 = SrcPtr[-3];
                    R2 = SrcPtr[2];

                    // |R0 - R2| < Beta
                    tmp = R0 - R2;
                    if (tmp < 0) tmp = -tmp;
                    aq = tmp - Beta;

                    // |L0 - L2| < Beta
                    tmp = L0 - L2;
                    if (tmp < 0) tmp = -tmp;
                    ap = tmp - Beta;


                    c0 = C0 = clipTable[Strng];
                    if (ap < 0) c0++;
                    if (aq < 0) c0++;

                    //dif = IClip(-c0, c0, ((Delta << 2) + (L1 - R1) + 4) >> 3);
                    dif = (((R0 - L0) << 2) + (L1 - R1) + 4) >> 3;
                    tmp = dif + c0;
                    if ((uint)tmp > (uint)c0 << 1)
                    {
                        tmp = ~(tmp >> 31);
                        dif = (tmp & (c0 << 1)) - c0;
                    }

                    ptr = SrcPtr;
                    ptr1 = SrcPtr - 1;
                    //SrcPtr[0]    = (uint8)IClip(0, 255, R0 - dif);
                    //SrcPtr[-inc] = (uint8)IClip(0, 255, L0 + dif);
                    R_in = R0 - dif;
                    L_in = L0 + dif; /* cannot re-use R0 and L0 here */
                    if ((uint)R_in > 255)
                    {
                        tmp = ~((int)R_in >> 31);
                        R_in = tmp & 255;
                    }
                    if ((uint)L_in > 255)
                    {
                        tmp = ~((int)L_in >> 31);
                        L_in = tmp & 255;
                    }
                    *ptr1-- = L_in;
                    *ptr++  = R_in;

                    if (C0 != 0) // Multiple zeros in the clip tables
                    {
                        if (ap < 0)  //SrcPtr[-inc2] += IClip(-C0, C0,(L2 + ((RL0 + 1) >> 1) - (L1<<1)) >> 1);
                        {
                            L2 = (L2 + ((R0 + L0 + 1) >> 1) - (L1 << 1)) >> 1;
                            tmp = L2 + C0;
                            if ((uint)tmp > (uint)C0 << 1)
                            {
                                tmp = ~(tmp >> 31);
                                L2 = (tmp & (C0 << 1)) - C0;
                            }
                            *ptr1 += L2;
                        }

                        if (aq < 0)  // SrcPtr[inc] += IClip(-C0, C0,(R2 + ((RL0 + 1) >> 1) - (R1<<1)) >> 1);
                        {
                            R2 = (R2 + ((R0 + L0 + 1) >> 1) - (R1 << 1)) >> 1;
                            tmp = R2 + C0;
                            if ((uint)tmp > (uint)C0 << 1)
                            {
                                tmp = ~(tmp >> 31);
                                R2 = (tmp & (C0 << 1)) - C0;
                            }
                            *ptr += R2;
                        }
                    }

                } /* if(tmp < 0) */

            } /* end of:  if((Strng = Strength[pel >> 2])) */

            SrcPtr += pitch;    // Increment to next set of pixel

        } /* for(pel=0; pel<16; pel++) */

    } /* else if(Strength[0] == 4) */

}

void EdgeLoop_Chroma_vertical(uint8* SrcPtr, uint8 *Strength, int Alpha, int Beta, int *clipTable, int pitch)
{
    int     pel, Strng;
    int     c0, dif;
    int     L1, L0, R0, R1, tmp, tmp1;
    uint8   *ptr;
    uint    R_in, L_in;


    for (pel = 0; pel < 16; pel++)
    {
        Strng = Strength[pel>>2];
        if (Strng)
        {
            // Read 8 pels
            R_in = *((uint *)SrcPtr);       // R_in = {R3, R2, R1, R0}
            L_in = *((uint *)(SrcPtr - 4)); // L_in = {L0, L1, L2, L3}
            R1   = (R_in >> 8) & 0xff;
            R0   = R_in & 0xff;
            L0   = L_in >> 24;
            L1   = (L_in >> 16) & 0xff;

            // |R0 - R1| < Beta
            tmp1 = R0 - R1;
            if (tmp1 < 0) tmp1 = -tmp1;
            tmp = (tmp1 - Beta);

            //|L0 - L1| < Beta
            tmp1 = L0 - L1;
            if (tmp1 < 0) tmp1 = -tmp1;
            tmp &= (tmp1 - Beta);

            //|R0 - L0| < Alpha
            tmp1 = R0 - L0;
            if (tmp1 < 0) tmp1 = -tmp1;
            tmp &= (tmp1 - Alpha);

            if (tmp < 0)
            {
                ptr = SrcPtr;
                if (Strng == 4) /* INTRA strong filtering */
                {
                    *ptr-- = ((R1 << 1) + R0 + L1 + 2) >> 2;
                    *ptr   = ((L1 << 1) + L0 + R1 + 2) >> 2;
                }
                else  /* normal filtering */
                {
                    c0  = clipTable[Strng] + 1;
                    //dif = IClip(-c0, c0, ((Delta << 2) + (L1 - R1) + 4) >> 3);
                    dif = (((R0 - L0) << 2) + (L1 - R1) + 4) >> 3;
                    tmp = dif + c0;
                    if ((uint)tmp > (uint)c0 << 1)
                    {
                        tmp = ~(tmp >> 31);
                        dif = (tmp & (c0 << 1)) - c0;
                    }

                    //SrcPtr[0]    = (uint8)IClip(0, 255, R0 - dif);
                    //SrcPtr[-inc] = (uint8)IClip(0, 255, L0 + dif);
                    L0 += dif;
                    R0 -= dif;
                    if ((uint)L0 > 255)
                    {
                        tmp = ~(L0 >> 31);
                        L0 = tmp & 255;
                    }
                    if ((uint)R0 > 255)
                    {
                        tmp = ~(R0 >> 31);
                        R0 = tmp & 255;
                    }

                    *ptr-- = R0;
                    *ptr = L0;
                }
            }
            pel ++;
            SrcPtr += pitch;   // Increment to next set of pixel

        } /* end of: if((Strng = Strength[pel >> 2])) */
        else
        {
            pel += 3;
            SrcPtr += (pitch << 1); //PtrInc << 1;
        }

    } /* end of: for(pel=0; pel<16; pel++) */
}


void EdgeLoop_Chroma_horizontal(uint8* SrcPtr, uint8 *Strength, int Alpha, int Beta, int *clipTable, int pitch)
{
    int  pel, Strng;
    int  c0, dif;
    int  L1, L0, R0, R1, tmp, tmp1;

    for (pel = 0; pel < 16; pel++)
    {
        Strng = Strength[pel>>2];
        if (Strng)
        {
            R0  = SrcPtr[0];
            L0  = SrcPtr[-pitch];
            L1  = SrcPtr[-(pitch<<1)]; //inc2
            R1  = SrcPtr[pitch];

            // |R0 - R1| < Beta
            tmp1 = R0 - R1;
            if (tmp1 < 0) tmp1 = -tmp1;
            tmp = (tmp1 - Beta);

            //|L0 - L1| < Beta
            tmp1 = L0 - L1;
            if (tmp1 < 0) tmp1 = -tmp1;
            tmp &= (tmp1 - Beta);

            //|R0 - L0| < Alpha
            tmp1 = R0 - L0;
            if (tmp1 < 0) tmp1 = -tmp1;
            tmp &= (tmp1 - Alpha);

            if (tmp < 0)
            {
                if (Strng == 4) /* INTRA strong filtering */
                {
                    SrcPtr[0]      = ((R1 << 1) + R0 + L1 + 2) >> 2;
                    SrcPtr[-pitch] = ((L1 << 1) + L0 + R1 + 2) >> 2;
                }
                else  /* normal filtering */
                {
                    c0  = clipTable[Strng] + 1;
                    //dif = IClip(-c0, c0, ((Delta << 2) + (L1 - R1) + 4) >> 3);
                    dif = (((R0 - L0) << 2) + (L1 - R1) + 4) >> 3;
                    tmp = dif + c0;
                    if ((uint)tmp > (uint)c0 << 1)
                    {
                        tmp = ~(tmp >> 31);
                        dif = (tmp & (c0 << 1)) - c0;
                    }

                    //SrcPtr[-inc] = (uint8)IClip(0, 255, L0 + dif);
                    //SrcPtr[0]    = (uint8)IClip(0, 255, R0 - dif);
                    L0 += dif;
                    R0 -= dif;
                    if ((uint)L0 > 255)
                    {
                        tmp = ~(L0 >> 31);
                        L0 = tmp & 255;
                    }
                    if ((uint)R0 > 255)
                    {
                        tmp = ~(R0 >> 31);
                        R0 = tmp & 255;
                    }
                    SrcPtr[0] = R0;
                    SrcPtr[-pitch] = L0;
                }
            }

            pel ++;
            SrcPtr ++; // Increment to next set of pixel

        } /* end of: if((Strng = Strength[pel >> 2])) */
        else
        {
            pel += 3;
            SrcPtr += 2;
        }

    } /* end of: for(pel=0; pel<16; pel++) */
}




