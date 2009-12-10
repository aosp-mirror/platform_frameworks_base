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

OSCL_EXPORT_REF void InitNeighborAvailability(AVCCommonObj *video, int mbNum)
{
    int PicWidthInMbs = video->PicWidthInMbs;

    // do frame-only and postpone intraAvail calculattion
    video->mbAddrA = mbNum - 1;
    video->mbAddrB = mbNum - PicWidthInMbs;
    video->mbAddrC = mbNum - PicWidthInMbs + 1;
    video->mbAddrD = mbNum - PicWidthInMbs - 1;

    video->mbAvailA = video->mbAvailB = video->mbAvailC = video->mbAvailD = 0;
    if (video->mb_x)
    {
        video->mbAvailA = (video->mblock[video->mbAddrA].slice_id == video->currMB->slice_id);
        if (video->mb_y)
        {
            video->mbAvailD = (video->mblock[video->mbAddrD].slice_id == video->currMB->slice_id);
        }
    }

    if (video->mb_y)
    {
        video->mbAvailB = (video->mblock[video->mbAddrB].slice_id == video->currMB->slice_id);
        if (video->mb_x < (PicWidthInMbs - 1))
        {
            video->mbAvailC = (video->mblock[video->mbAddrC].slice_id == video->currMB->slice_id);
        }
    }
    return ;
}

bool mb_is_available(AVCMacroblock *mblock, uint PicSizeInMbs, int mbAddr, int currMbAddr)
{
    if (mbAddr < 0 || mbAddr >= (int)PicSizeInMbs)
    {
        return FALSE;
    }

    if (mblock[mbAddr].slice_id != mblock[currMbAddr].slice_id)
    {
        return FALSE;
    }

    return TRUE;
}

OSCL_EXPORT_REF int predict_nnz(AVCCommonObj *video, int i, int j)
{
    int pred_nnz = 0;
    int cnt      = 1;
    AVCMacroblock *tempMB;

    /* left block */
    /*getLuma4x4Neighbour(video, mb_nr, i, j, -1, 0, &pix);
    leftMB = video->mblock + pix.mb_addr; */
    /* replace the above with below (won't work for field decoding),  1/19/04 */

    if (i)
    {
        pred_nnz = video->currMB->nz_coeff[(j<<2)+i-1];
    }
    else
    {
        if (video->mbAvailA)
        {
            tempMB = video->mblock + video->mbAddrA;
            pred_nnz = tempMB->nz_coeff[(j<<2)+3];
        }
        else
        {
            cnt = 0;
        }
    }


    /* top block */
    /*getLuma4x4Neighbour(video, mb_nr, i, j, 0, -1, &pix);
    topMB = video->mblock + pix.mb_addr;*/
    /* replace the above with below (won't work for field decoding),  1/19/04 */

    if (j)
    {
        pred_nnz += video->currMB->nz_coeff[((j-1)<<2)+i];
        cnt++;
    }
    else
    {
        if (video->mbAvailB)
        {
            tempMB = video->mblock + video->mbAddrB;
            pred_nnz += tempMB->nz_coeff[12+i];
            cnt++;
        }
    }


    if (cnt == 2)
    {
        pred_nnz = (pred_nnz + 1) >> 1;
    }

    return pred_nnz;

}


OSCL_EXPORT_REF int predict_nnz_chroma(AVCCommonObj *video, int i, int j)
{
    int pred_nnz = 0;
    int cnt      = 1;
    AVCMacroblock *tempMB;

    /* left block */
    /*getChroma4x4Neighbour(video, mb_nr, i%2, j-4, -1, 0, &pix);
    leftMB = video->mblock + pix.mb_addr;*/
    /* replace the above with below (won't work for field decoding),  1/19/04 */
    if (i&1)
    {
        pred_nnz = video->currMB->nz_coeff[(j<<2)+i-1];

    }
    else
    {
        if (video->mbAvailA)
        {
            tempMB = video->mblock + video->mbAddrA;
            pred_nnz = tempMB->nz_coeff[(j<<2)+i+1];
        }
        else
        {
            cnt = 0;
        }
    }


    /* top block */
    /*getChroma4x4Neighbour(video, mb_nr, i%2, j-4, 0, -1, &pix);
    topMB = video->mblock + pix.mb_addr;*/
    /* replace the above with below (won't work for field decoding),  1/19/04 */

    if (j&1)
    {
        pred_nnz += video->currMB->nz_coeff[((j-1)<<2)+i];
        cnt++;
    }
    else
    {
        if (video->mbAvailB)
        {
            tempMB = video->mblock + video->mbAddrB;
            pred_nnz += tempMB->nz_coeff[20+i];
            cnt++;
        }

    }

    if (cnt == 2)
    {
        pred_nnz = (pred_nnz + 1) >> 1;
    }

    return pred_nnz;
}

OSCL_EXPORT_REF void GetMotionVectorPredictor(AVCCommonObj *video, int encFlag)
{
    AVCMacroblock *currMB = video->currMB;
    AVCMacroblock *MB_A, *MB_B, *MB_C, *MB_D;
    int block_x, block_y, block_x_1, block_y_1, new_block_x;
    int mbPartIdx, subMbPartIdx, offset_indx;
    int16 *mv, pmv_x, pmv_y;
    int nmSubMbHeight, nmSubMbWidth, mbPartIdx_X, mbPartIdx_Y;
    int avail_a, avail_b, avail_c;
    const static uint32 C = 0x5750;
    int i, j, offset_MbPart_indx, refIdxLXA, refIdxLXB, refIdxLXC = 0, curr_ref_idx;
    int pmv_A_x, pmv_B_x, pmv_C_x = 0, pmv_A_y, pmv_B_y, pmv_C_y = 0;

    /* we have to take care of Intra/skip blocks somewhere, i.e. set MV to  0 and set ref to -1! */
    /* we have to populate refIdx as well */


    MB_A = &video->mblock[video->mbAddrA];
    MB_B = &video->mblock[video->mbAddrB];


    if (currMB->mbMode == AVC_SKIP /* && !encFlag */) /* only for decoder */
    {
        currMB->ref_idx_L0[0] = currMB->ref_idx_L0[1] = currMB->ref_idx_L0[2] = currMB->ref_idx_L0[3] = 0;
        if (video->mbAvailA && video->mbAvailB)
        {
            if ((MB_A->ref_idx_L0[1] == 0 && MB_A->mvL0[3] == 0) ||
                    (MB_B->ref_idx_L0[2] == 0 && MB_B->mvL0[12] == 0))
            {
                memset(currMB->mvL0, 0, sizeof(int32)*16);
                return;
            }
        }
        else
        {
            memset(currMB->mvL0, 0, sizeof(int32)*16);
            return;
        }
        video->mvd_l0[0][0][0] = 0;
        video->mvd_l0[0][0][1] = 0;
    }

    MB_C = &video->mblock[video->mbAddrC];
    MB_D = &video->mblock[video->mbAddrD];

    offset_MbPart_indx = 0;
    for (mbPartIdx = 0; mbPartIdx < currMB->NumMbPart; mbPartIdx++)
    {
        offset_indx = 0;
        nmSubMbHeight = currMB->SubMbPartHeight[mbPartIdx] >> 2;
        nmSubMbWidth = currMB->SubMbPartWidth[mbPartIdx] >> 2;
        mbPartIdx_X = ((mbPartIdx + offset_MbPart_indx) & 1) << 1;
        mbPartIdx_Y = (mbPartIdx + offset_MbPart_indx) & 2;

        for (subMbPartIdx = 0; subMbPartIdx < currMB->NumSubMbPart[mbPartIdx]; subMbPartIdx++)
        {
            block_x = mbPartIdx_X + ((subMbPartIdx + offset_indx) & 1);
            block_y = mbPartIdx_Y + (((subMbPartIdx + offset_indx) >> 1) & 1);

            block_x_1 = block_x - 1;
            block_y_1 = block_y - 1;
            refIdxLXA = refIdxLXB = refIdxLXC = -1;
            pmv_A_x = pmv_A_y = pmv_B_x = pmv_B_y = pmv_C_x = pmv_C_y = 0;

            if (block_x)
            {
                avail_a = 1;
                refIdxLXA = currMB->ref_idx_L0[(block_y & 2) + (block_x_1 >> 1)];
                mv = (int16*)(currMB->mvL0 + (block_y << 2) + block_x_1);
                pmv_A_x = *mv++;
                pmv_A_y = *mv;
            }
            else
            {
                avail_a = video->mbAvailA;
                if (avail_a)
                {
                    refIdxLXA = MB_A->ref_idx_L0[(block_y & 2) + 1];
                    mv = (int16*)(MB_A->mvL0 + (block_y << 2) + 3);
                    pmv_A_x = *mv++;
                    pmv_A_y = *mv;
                }
            }

            if (block_y)
            {
                avail_b = 1;
                refIdxLXB = currMB->ref_idx_L0[(block_y_1 & 2) + (block_x >> 1)];
                mv = (int16*)(currMB->mvL0 + (block_y_1 << 2) + block_x);
                pmv_B_x = *mv++;
                pmv_B_y = *mv;
            }

            else
            {
                avail_b = video->mbAvailB;
                if (avail_b)
                {
                    refIdxLXB = MB_B->ref_idx_L0[2 + (block_x >> 1)];
                    mv = (int16*)(MB_B->mvL0 + 12 + block_x);
                    pmv_B_x = *mv++;
                    pmv_B_y = *mv;
                }
            }

            new_block_x = block_x + (currMB->SubMbPartWidth[mbPartIdx] >> 2) - 1;
            avail_c = (C >> ((block_y << 2) + new_block_x)) & 0x1;

            if (avail_c)
            {
                /* it guaranteed that block_y > 0 && new_block_x<3 ) */
                refIdxLXC = currMB->ref_idx_L0[(block_y_1 & 2) + ((new_block_x+1) >> 1)];
                mv = (int16*)(currMB->mvL0 + (block_y_1 << 2) + (new_block_x + 1));
                pmv_C_x = *mv++;
                pmv_C_y = *mv;
            }
            else
            {
                if (block_y == 0 && new_block_x < 3)
                {
                    avail_c = video->mbAvailB;
                    if (avail_c)
                    {
                        refIdxLXC = MB_B->ref_idx_L0[2 + ((new_block_x+1)>>1)];
                        mv = (int16*)(MB_B->mvL0 + 12 + (new_block_x + 1));
                        pmv_C_x = *mv++;
                        pmv_C_y = *mv;
                    }
                }
                else if (block_y == 0 && new_block_x == 3)
                {
                    avail_c = video->mbAvailC;
                    if (avail_c)
                    {
                        refIdxLXC = MB_C->ref_idx_L0[2];
                        mv = (int16*)(MB_C->mvL0 + 12);
                        pmv_C_x = *mv++;
                        pmv_C_y = *mv;
                    }
                }

                if (avail_c == 0)
                {   /* check D */
                    if (block_x && block_y)
                    {
                        avail_c = 1;
                        refIdxLXC =  currMB->ref_idx_L0[(block_y_1 & 2) + (block_x_1 >> 1)];
                        mv = (int16*)(currMB->mvL0 + (block_y_1 << 2) + block_x_1);
                        pmv_C_x = *mv++;
                        pmv_C_y = *mv;
                    }
                    else if (block_y)
                    {
                        avail_c = video->mbAvailA;
                        if (avail_c)
                        {
                            refIdxLXC =  MB_A->ref_idx_L0[(block_y_1 & 2) + 1];
                            mv = (int16*)(MB_A->mvL0 + (block_y_1 << 2) + 3);
                            pmv_C_x = *mv++;
                            pmv_C_y = *mv;
                        }
                    }
                    else if (block_x)
                    {
                        avail_c = video->mbAvailB;
                        if (avail_c)
                        {
                            refIdxLXC = MB_B->ref_idx_L0[2 + (block_x_1 >> 1)];
                            mv = (int16*)(MB_B->mvL0 + 12 + block_x_1);
                            pmv_C_x = *mv++;
                            pmv_C_y = *mv;
                        }
                    }
                    else
                    {
                        avail_c = video->mbAvailD;
                        if (avail_c)
                        {
                            refIdxLXC = MB_D->ref_idx_L0[3];
                            mv = (int16*)(MB_D->mvL0 + 15);
                            pmv_C_x = *mv++;
                            pmv_C_y = *mv;
                        }
                    }
                }
            }

            offset_indx = currMB->SubMbPartWidth[mbPartIdx] >> 3;

            curr_ref_idx = currMB->ref_idx_L0[(block_y & 2) + (block_x >> 1)];

            if (avail_a && !(avail_b || avail_c))
            {
                pmv_x = pmv_A_x;
                pmv_y = pmv_A_y;
            }
            else if (((curr_ref_idx == refIdxLXA) + (curr_ref_idx == refIdxLXB) + (curr_ref_idx == refIdxLXC)) == 1)
            {
                if (curr_ref_idx == refIdxLXA)
                {
                    pmv_x = pmv_A_x;
                    pmv_y = pmv_A_y;
                }
                else if (curr_ref_idx == refIdxLXB)
                {
                    pmv_x = pmv_B_x;
                    pmv_y = pmv_B_y;
                }
                else
                {
                    pmv_x = pmv_C_x;
                    pmv_y = pmv_C_y;
                }
            }
            else
            {
                pmv_x = AVC_MEDIAN(pmv_A_x, pmv_B_x, pmv_C_x);
                pmv_y = AVC_MEDIAN(pmv_A_y, pmv_B_y, pmv_C_y);
            }

            /* overwrite if special case */
            if (currMB->NumMbPart == 2)
            {
                if (currMB->MbPartWidth == 16)
                {
                    if (mbPartIdx == 0)
                    {
                        if (refIdxLXB == curr_ref_idx)
                        {
                            pmv_x = pmv_B_x;
                            pmv_y = pmv_B_y;
                        }
                    }
                    else if (refIdxLXA == curr_ref_idx)
                    {
                        pmv_x = pmv_A_x;
                        pmv_y = pmv_A_y;
                    }
                }
                else
                {
                    if (mbPartIdx == 0)
                    {
                        if (refIdxLXA == curr_ref_idx)
                        {
                            pmv_x = pmv_A_x;
                            pmv_y = pmv_A_y;
                        }
                    }
                    else if (refIdxLXC == curr_ref_idx)
                    {
                        pmv_x = pmv_C_x;
                        pmv_y = pmv_C_y;
                    }
                }
            }

            mv = (int16*)(currMB->mvL0 + block_x + (block_y << 2));

            if (encFlag) /* calculate residual MV video->mvd_l0 */
            {
                video->mvd_l0[mbPartIdx][subMbPartIdx][0] = *mv++ - pmv_x;
                video->mvd_l0[mbPartIdx][subMbPartIdx][1] = *mv++ - pmv_y;
            }
            else    /* calculate original MV currMB->mvL0 */
            {
                pmv_x += video->mvd_l0[mbPartIdx][subMbPartIdx][0];
                pmv_y += video->mvd_l0[mbPartIdx][subMbPartIdx][1];

                for (i = 0; i < nmSubMbHeight; i++)
                {
                    for (j = 0; j < nmSubMbWidth; j++)
                    {
                        *mv++ = pmv_x;
                        *mv++ = pmv_y;
                    }
                    mv += (8 - (j << 1));
                }
            }
        }
        offset_MbPart_indx = currMB->MbPartWidth >> 4;

    }
}


