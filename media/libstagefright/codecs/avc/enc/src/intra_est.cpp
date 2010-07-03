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
#include "avcenc_lib.h"

#define TH_I4  0  /* threshold biasing toward I16 mode instead of I4 mode */
#define TH_Intra  0 /* threshold biasing toward INTER mode instead of intra mode */

#define FIXED_INTRAPRED_MODE  AVC_I16
#define FIXED_I16_MODE  AVC_I16_DC
#define FIXED_I4_MODE   AVC_I4_Diagonal_Down_Left
#define FIXED_INTRA_CHROMA_MODE AVC_IC_DC

#define CLIP_RESULT(x)      if((uint)x > 0xFF){ \
                 x = 0xFF & (~(x>>31));}


bool IntraDecisionABE(AVCEncObject *encvid, int min_cost, uint8 *curL, int picPitch)
{
    AVCCommonObj *video = encvid->common;
    AVCFrameIO *currInput = encvid->currInput;
    int orgPitch = currInput->pitch;
    int x_pos = (video->mb_x) << 4;
    int y_pos = (video->mb_y) << 4;
    uint8 *orgY = currInput->YCbCr[0] + y_pos * orgPitch + x_pos;
    int j;
    uint8 *topL, *leftL, *orgY_2, *orgY_3;
    int temp, SBE, offset;
    OsclFloat ABE;
    bool intra = true;

    if (((x_pos >> 4) != (int)video->PicWidthInMbs - 1) &&
            ((y_pos >> 4) != (int)video->PicHeightInMbs - 1) &&
            video->intraAvailA &&
            video->intraAvailB)
    {
        SBE = 0;
        /* top neighbor */
        topL = curL - picPitch;
        /* left neighbor */
        leftL = curL - 1;
        orgY_2 = orgY - orgPitch;

        for (j = 0; j < 16; j++)
        {
            temp = *topL++ - orgY[j];
            SBE += ((temp >= 0) ? temp : -temp);
            temp = *(leftL += picPitch) - *(orgY_2 += orgPitch);
            SBE += ((temp >= 0) ? temp : -temp);
        }

        /* calculate chroma */
        offset = (y_pos >> 2) * picPitch + (x_pos >> 1);
        topL = video->currPic->Scb + offset;
        orgY_2 = currInput->YCbCr[1] + offset + (y_pos >> 2) * (orgPitch - picPitch);

        leftL = topL - 1;
        topL -= (picPitch >> 1);
        orgY_3 = orgY_2 - (orgPitch >> 1);
        for (j = 0; j < 8; j++)
        {
            temp = *topL++ - orgY_2[j];
            SBE += ((temp >= 0) ? temp : -temp);
            temp = *(leftL += (picPitch >> 1)) - *(orgY_3 += (orgPitch >> 1));
            SBE += ((temp >= 0) ? temp : -temp);
        }

        topL = video->currPic->Scr + offset;
        orgY_2 = currInput->YCbCr[2] + offset + (y_pos >> 2) * (orgPitch - picPitch);

        leftL = topL - 1;
        topL -= (picPitch >> 1);
        orgY_3 = orgY_2 - (orgPitch >> 1);
        for (j = 0; j < 8; j++)
        {
            temp = *topL++ - orgY_2[j];
            SBE += ((temp >= 0) ? temp : -temp);
            temp = *(leftL += (picPitch >> 1)) - *(orgY_3 += (orgPitch >> 1));
            SBE += ((temp >= 0) ? temp : -temp);
        }

        /* compare mincost/384 and SBE/64 */
        ABE = SBE / 64.0;
        if (ABE*0.8 >= min_cost / 384.0)
        {
            intra = false;
        }
    }

    return intra;
}

/* perform searching for MB mode */
/* assuming that this is done inside the encoding loop,
no need to call InitNeighborAvailability */

void MBIntraSearch(AVCEncObject *encvid, int mbnum, uint8 *curL, int picPitch)
{
    AVCCommonObj *video = encvid->common;
    AVCFrameIO *currInput = encvid->currInput;
    AVCMacroblock *currMB = video->currMB;
    int min_cost;
    uint8 *orgY;
    int x_pos = (video->mb_x) << 4;
    int y_pos = (video->mb_y) << 4;
    uint32 *saved_inter;
    int j;
    int orgPitch = currInput->pitch;
    bool intra = true;

    currMB->CBP = 0;

    /* first do motion vector and variable block size search */
    min_cost = encvid->min_cost[mbnum];

    /* now perform intra prediction search */
    /* need to add the check for encvid->intraSearch[video->mbNum] to skip intra
       if it's not worth checking. */
    if (video->slice_type == AVC_P_SLICE)
    {
        /* Decide whether intra search is necessary or not */
        /* This one, we do it in the encoding loop so the neighboring pixel are the
        actual reconstructed pixels. */
        intra = IntraDecisionABE(encvid, min_cost, curL, picPitch);
    }

    if (intra == true || video->slice_type == AVC_I_SLICE)
    {
        orgY = currInput->YCbCr[0] + y_pos * orgPitch + x_pos;

        /* i16 mode search */
        /* generate all the predictions */
        intrapred_luma_16x16(encvid);

        /* evaluate them one by one */
        find_cost_16x16(encvid, orgY, &min_cost);

        if (video->slice_type == AVC_P_SLICE)
        {
            /* save current inter prediction */
            saved_inter = encvid->subpel_pred; /* reuse existing buffer */
            j = 16;
            curL -= 4;
            picPitch -= 16;
            while (j--)
            {
                *saved_inter++ = *((uint32*)(curL += 4));
                *saved_inter++ = *((uint32*)(curL += 4));
                *saved_inter++ = *((uint32*)(curL += 4));
                *saved_inter++ = *((uint32*)(curL += 4));
                curL += picPitch;
            }

        }

        /* i4 mode search */
        mb_intra4x4_search(encvid, &min_cost);

        encvid->min_cost[mbnum] = min_cost; /* update min_cost */
    }


    if (currMB->mb_intra)
    {
        chroma_intra_search(encvid);

        /* need to set this in order for the MBInterPrediction to work!! */
        memset(currMB->mvL0, 0, sizeof(int32)*16);
        currMB->ref_idx_L0[0] = currMB->ref_idx_L0[1] =
                                    currMB->ref_idx_L0[2] = currMB->ref_idx_L0[3] = -1;
    }
    else if (video->slice_type == AVC_P_SLICE && intra == true)
    {
        /* restore current inter prediction */
        saved_inter = encvid->subpel_pred; /* reuse existing buffer */
        j = 16;
        curL -= ((picPitch + 16) << 4);
        while (j--)
        {
            *((uint32*)(curL += 4)) = *saved_inter++;
            *((uint32*)(curL += 4)) = *saved_inter++;
            *((uint32*)(curL += 4)) = *saved_inter++;
            *((uint32*)(curL += 4)) = *saved_inter++;
            curL += picPitch;
        }
    }

    return ;
}

/* generate all the prediction values */
void intrapred_luma_16x16(AVCEncObject *encvid)
{
    AVCCommonObj *video = encvid->common;
    AVCPictureData *currPic = video->currPic;

    int x_pos = (video->mb_x) << 4;
    int y_pos = (video->mb_y) << 4;
    int pitch = currPic->pitch;

    int offset = y_pos * pitch + x_pos;

    uint8 *pred, *top, *left;
    uint8 *curL = currPic->Sl + offset; /* point to reconstructed frame */
    uint32 word1, word2, word3, word4;
    uint32 sum = 0;

    int a_16, b, c, factor_c;
    uint8 *comp_ref_x0, *comp_ref_x1, *comp_ref_y0, *comp_ref_y1;
    int H = 0, V = 0, tmp, value;
    int i;

    if (video->intraAvailB)
    {
        //get vertical prediction mode
        top = curL - pitch;

        pred = encvid->pred_i16[AVC_I16_Vertical] - 16;

        word1 = *((uint32*)(top));  /* read 4 bytes from top */
        word2 = *((uint32*)(top + 4)); /* read 4 bytes from top */
        word3 = *((uint32*)(top + 8)); /* read 4 bytes from top */
        word4 = *((uint32*)(top + 12)); /* read 4 bytes from top */

        for (i = 0; i < 16; i++)
        {
            *((uint32*)(pred += 16)) = word1;
            *((uint32*)(pred + 4)) = word2;
            *((uint32*)(pred + 8)) = word3;
            *((uint32*)(pred + 12)) = word4;

        }

        sum = word1 & 0xFF00FF;
        word1 = (word1 >> 8) & 0xFF00FF;
        sum += word1;
        word1 = (word2 & 0xFF00FF);
        sum += word1;
        word2 = (word2 >> 8) & 0xFF00FF;
        sum += word2;
        word1 = (word3 & 0xFF00FF);
        sum += word1;
        word3 = (word3 >> 8) & 0xFF00FF;
        sum += word3;
        word1 = (word4 & 0xFF00FF);
        sum += word1;
        word4 = (word4 >> 8) & 0xFF00FF;
        sum += word4;

        sum += (sum >> 16);
        sum &= 0xFFFF;

        if (!video->intraAvailA)
        {
            sum = (sum + 8) >> 4;
        }
    }

    if (video->intraAvailA)
    {
        // get horizontal mode
        left = curL - 1 - pitch;

        pred = encvid->pred_i16[AVC_I16_Horizontal] - 16;

        for (i = 0; i < 16; i++)
        {
            word1 = *(left += pitch);
            sum += word1;

            word1 = (word1 << 8) | word1;
            word1 = (word1 << 16) | word1; /* make it 4 */

            *(uint32*)(pred += 16) = word1;
            *(uint32*)(pred + 4) = word1;
            *(uint32*)(pred + 8) = word1;
            *(uint32*)(pred + 12) = word1;
        }

        if (!video->intraAvailB)
        {
            sum = (sum + 8) >> 4;
        }
        else
        {
            sum = (sum + 16) >> 5;
        }
    }

    // get DC mode
    if (!video->intraAvailA && !video->intraAvailB)
    {
        sum = 0x80808080;
    }
    else
    {
        sum = (sum << 8) | sum;
        sum = (sum << 16) | sum;
    }

    pred = encvid->pred_i16[AVC_I16_DC] - 16;
    for (i = 0; i < 16; i++)
    {
        *((uint32*)(pred += 16)) = sum;
        *((uint32*)(pred + 4)) = sum;
        *((uint32*)(pred + 8)) = sum;
        *((uint32*)(pred + 12)) = sum;
    }

    // get plane mode
    if (video->intraAvailA && video->intraAvailB && video->intraAvailD)
    {
        pred = encvid->pred_i16[AVC_I16_Plane] - 16;

        comp_ref_x0 = curL - pitch + 8;
        comp_ref_x1 = curL - pitch + 6;
        comp_ref_y0 = curL - 1 + (pitch << 3);
        comp_ref_y1 = curL - 1 + 6 * pitch;

        for (i = 1; i < 8; i++)
        {
            H += i * (*comp_ref_x0++ - *comp_ref_x1--);
            V += i * (*comp_ref_y0 - *comp_ref_y1);
            comp_ref_y0 += pitch;
            comp_ref_y1 -= pitch;
        }

        H += i * (*comp_ref_x0++ - curL[-pitch-1]);
        V += i * (*comp_ref_y0 - *comp_ref_y1);


        a_16 = ((*(curL - pitch + 15) + *(curL - 1 + 15 * pitch)) << 4) + 16;;
        b = (5 * H + 32) >> 6;
        c = (5 * V + 32) >> 6;

        tmp = 0;
        for (i = 0; i < 16; i++)
        {
            factor_c = a_16 + c * (tmp++ - 7);
            factor_c -= 7 * b;

            value = factor_c >> 5;
            factor_c += b;
            CLIP_RESULT(value)
            word1 = value;
            value = factor_c >> 5;
            factor_c += b;
            CLIP_RESULT(value)
            word1 = (word1) | (value << 8);
            value = factor_c >> 5;
            factor_c += b;
            CLIP_RESULT(value)
            word1 = (word1) | (value << 16);
            value = factor_c >> 5;
            factor_c += b;
            CLIP_RESULT(value)
            word1 = (word1) | (value << 24);
            *((uint32*)(pred += 16)) = word1;
            value = factor_c >> 5;
            factor_c += b;
            CLIP_RESULT(value)
            word1 = value;
            value = factor_c >> 5;
            factor_c += b;
            CLIP_RESULT(value)
            word1 = (word1) | (value << 8);
            value = factor_c >> 5;
            factor_c += b;
            CLIP_RESULT(value)
            word1 = (word1) | (value << 16);
            value = factor_c >> 5;
            factor_c += b;
            CLIP_RESULT(value)
            word1 = (word1) | (value << 24);
            *((uint32*)(pred + 4)) = word1;
            value = factor_c >> 5;
            factor_c += b;
            CLIP_RESULT(value)
            word1 = value;
            value = factor_c >> 5;
            factor_c += b;
            CLIP_RESULT(value)
            word1 = (word1) | (value << 8);
            value = factor_c >> 5;
            factor_c += b;
            CLIP_RESULT(value)
            word1 = (word1) | (value << 16);
            value = factor_c >> 5;
            factor_c += b;
            CLIP_RESULT(value)
            word1 = (word1) | (value << 24);
            *((uint32*)(pred + 8)) = word1;
            value = factor_c >> 5;
            factor_c += b;
            CLIP_RESULT(value)
            word1 = value;
            value = factor_c >> 5;
            factor_c += b;
            CLIP_RESULT(value)
            word1 = (word1) | (value << 8);
            value = factor_c >> 5;
            factor_c += b;
            CLIP_RESULT(value)
            word1 = (word1) | (value << 16);
            value = factor_c >> 5;
            CLIP_RESULT(value)
            word1 = (word1) | (value << 24);
            *((uint32*)(pred + 12)) = word1;
        }
    }

    return ;
}


/* evaluate each prediction mode of I16 */
void find_cost_16x16(AVCEncObject *encvid, uint8 *orgY, int *min_cost)
{
    AVCCommonObj *video = encvid->common;
    AVCMacroblock *currMB = video->currMB;
    int cost;
    int org_pitch = encvid->currInput->pitch;

    /* evaluate vertical mode */
    if (video->intraAvailB)
    {
        cost = cost_i16(orgY, org_pitch, encvid->pred_i16[AVC_I16_Vertical], *min_cost);
        if (cost < *min_cost)
        {
            *min_cost = cost;
            currMB->mbMode = AVC_I16;
            currMB->mb_intra = 1;
            currMB->i16Mode = AVC_I16_Vertical;
        }
    }


    /* evaluate horizontal mode */
    if (video->intraAvailA)
    {
        cost = cost_i16(orgY, org_pitch, encvid->pred_i16[AVC_I16_Horizontal], *min_cost);
        if (cost < *min_cost)
        {
            *min_cost = cost;
            currMB->mbMode = AVC_I16;
            currMB->mb_intra = 1;
            currMB->i16Mode = AVC_I16_Horizontal;
        }
    }

    /* evaluate DC mode */
    cost = cost_i16(orgY, org_pitch, encvid->pred_i16[AVC_I16_DC], *min_cost);
    if (cost < *min_cost)
    {
        *min_cost = cost;
        currMB->mbMode = AVC_I16;
        currMB->mb_intra = 1;
        currMB->i16Mode = AVC_I16_DC;
    }

    /* evaluate plane mode */
    if (video->intraAvailA && video->intraAvailB && video->intraAvailD)
    {
        cost = cost_i16(orgY, org_pitch, encvid->pred_i16[AVC_I16_Plane], *min_cost);
        if (cost < *min_cost)
        {
            *min_cost = cost;
            currMB->mbMode = AVC_I16;
            currMB->mb_intra = 1;
            currMB->i16Mode = AVC_I16_Plane;
        }
    }

    return ;
}


int cost_i16(uint8 *org, int org_pitch, uint8 *pred, int min_cost)
{

    int cost;
    int j, k;
    int16 res[256], *pres; // residue
    int m0, m1, m2, m3;

    // calculate SATD
    org_pitch -= 16;
    pres = res;
    // horizontal transform
    for (j = 0; j < 16; j++)
    {
        k = 4;
        while (k > 0)
        {
            m0 = org[0] - pred[0];
            m3 = org[3] - pred[3];
            m0 += m3;
            m3 = m0 - (m3 << 1);
            m1 = org[1] - pred[1];
            m2 = org[2] - pred[2];
            m1 += m2;
            m2 = m1 - (m2 << 1);
            pres[0] = m0 + m1;
            pres[2] = m0 - m1;
            pres[1] = m2 + m3;
            pres[3] = m3 - m2;

            org += 4;
            pres += 4;
            pred += 4;
            k--;
        }
        org += org_pitch;
    }
    /* vertical transform */
    cost = 0;
    for (j = 0; j < 4; j++)
    {
        pres = res + (j << 6);
        k = 16;
        while (k > 0)
        {
            m0 = pres[0];
            m3 = pres[3<<4];
            m0 += m3;
            m3 = m0 - (m3 << 1);
            m1 = pres[1<<4];
            m2 = pres[2<<4];
            m1 += m2;
            m2 = m1 - (m2 << 1);
            pres[0] = m0 = m0 + m1;

            if (k&0x3)  // only sum up non DC values.
            {
                cost += ((m0 > 0) ? m0 : -m0);
            }

            m1 = m0 - (m1 << 1);
            cost += ((m1 > 0) ? m1 : -m1);
            m3 = m2 + m3;
            cost += ((m3 > 0) ? m3 : -m3);
            m2 = m3 - (m2 << 1);
            cost += ((m2 > 0) ? m2 : -m2);

            pres++;
            k--;
        }
        if ((cost >> 1) > min_cost) /* early drop out */
        {
            return (cost >> 1);
        }
    }

    /* Hadamard of the DC coefficient */
    pres = res;
    k = 4;
    while (k > 0)
    {
        m0 = pres[0];
        m3 = pres[3<<2];
        m0 >>= 2;
        m0 += (m3 >> 2);
        m3 = m0 - (m3 >> 1);
        m1 = pres[1<<2];
        m2 = pres[2<<2];
        m1 >>= 2;
        m1 += (m2 >> 2);
        m2 = m1 - (m2 >> 1);
        pres[0] = (m0 + m1);
        pres[2<<2] = (m0 - m1);
        pres[1<<2] = (m2 + m3);
        pres[3<<2] = (m3 - m2);
        pres += (4 << 4);
        k--;
    }

    pres = res;
    k = 4;
    while (k > 0)
    {
        m0 = pres[0];
        m3 = pres[3<<6];
        m0 += m3;
        m3 = m0 - (m3 << 1);
        m1 = pres[1<<6];
        m2 = pres[2<<6];
        m1 += m2;
        m2 = m1 - (m2 << 1);
        m0 = m0 + m1;
        cost += ((m0 >= 0) ? m0 : -m0);
        m1 = m0 - (m1 << 1);
        cost += ((m1 >= 0) ? m1 : -m1);
        m3 = m2 + m3;
        cost += ((m3 >= 0) ? m3 : -m3);
        m2 = m3 - (m2 << 1);
        cost += ((m2 >= 0) ? m2 : -m2);
        pres += 4;

        if ((cost >> 1) > min_cost) /* early drop out */
        {
            return (cost >> 1);
        }

        k--;
    }

    return (cost >> 1);
}


void mb_intra4x4_search(AVCEncObject *encvid, int *min_cost)
{
    AVCCommonObj *video = encvid->common;
    AVCMacroblock *currMB = video->currMB;
    AVCPictureData *currPic = video->currPic;
    AVCFrameIO *currInput = encvid->currInput;
    int pitch = currPic->pitch;
    int org_pitch = currInput->pitch;
    int offset;
    uint8 *curL, *comp, *org4, *org8;
    int y = video->mb_y << 4;
    int x = video->mb_x << 4;

    int b8, b4, cost4x4, blkidx;
    int cost = 0;
    int numcoef;
    int dummy = 0;
    int mb_intra = currMB->mb_intra; // save the original value

    offset = y * pitch + x;

    curL = currPic->Sl + offset;
    org8 = currInput->YCbCr[0] + y * org_pitch + x;
    video->pred_pitch = 4;

    cost = (int)(6.0 * encvid->lambda_mode + 0.4999);
    cost <<= 2;

    currMB->mb_intra = 1;  // temporary set this to one to enable the IDCT
    // operation inside dct_luma

    for (b8 = 0; b8 < 4; b8++)
    {
        comp = curL;
        org4 = org8;

        for (b4 = 0; b4 < 4; b4++)
        {
            blkidx = blkIdx2blkXY[b8][b4];
            cost4x4 = blk_intra4x4_search(encvid, blkidx, comp, org4);
            cost += cost4x4;
            if (cost > *min_cost)
            {
                currMB->mb_intra = mb_intra; // restore the value
                return ;
            }

            /* do residue, Xfrm, Q, invQ, invXfrm, recon and save the DCT coefs.*/
            video->pred_block = encvid->pred_i4[currMB->i4Mode[blkidx]];
            numcoef = dct_luma(encvid, blkidx, comp, org4, &dummy);
            currMB->nz_coeff[blkidx] = numcoef;
            if (numcoef)
            {
                video->cbp4x4 |= (1 << blkidx);
                currMB->CBP |= (1 << b8);
            }

            if (b4&1)
            {
                comp += ((pitch << 2) - 4);
                org4 += ((org_pitch << 2) - 4);
            }
            else
            {
                comp += 4;
                org4 += 4;
            }
        }

        if (b8&1)
        {
            curL += ((pitch << 3) - 8);
            org8 += ((org_pitch << 3) - 8);
        }
        else
        {
            curL += 8;
            org8 += 8;
        }
    }

    currMB->mb_intra = mb_intra; // restore the value

    if (cost < *min_cost)
    {
        *min_cost = cost;
        currMB->mbMode = AVC_I4;
        currMB->mb_intra = 1;
    }

    return ;
}


/* search for i4 mode for a 4x4 block */
int blk_intra4x4_search(AVCEncObject *encvid, int blkidx, uint8 *cur, uint8 *org)
{
    AVCCommonObj *video = encvid->common;
    AVCNeighborAvailability availability;
    AVCMacroblock *currMB = video->currMB;
    bool top_left = FALSE;
    int pitch = video->currPic->pitch;
    uint8 mode_avail[AVCNumI4PredMode];
    uint32 temp, DC;
    uint8 *pred;
    int org_pitch = encvid->currInput->pitch;
    uint16 min_cost, cost;

    int P_x, Q_x, R_x, P_y, Q_y, R_y, D, D0, D1;
    int P0, Q0, R0, S0, P1, Q1, R1, P2, Q2;
    uint8 P_A, P_B, P_C, P_D, P_E, P_F, P_G, P_H, P_I, P_J, P_K, P_L, P_X;
    int r0, r1, r2, r3, r4, r5, r6, r7;
    int x0, x1, x2, x3, x4, x5;
    uint32 temp1, temp2;

    int ipmode, mostProbableMode;
    int fixedcost = 4 * encvid->lambda_mode;
    int min_sad = 0x7FFF;

    availability.left = TRUE;
    availability.top = TRUE;
    if (blkidx <= 3) /* top row block  (!block_y) */
    { /* check availability up */
        availability.top = video->intraAvailB ;
    }
    if (!(blkidx&0x3)) /* left column block (!block_x)*/
    { /* check availability left */
        availability.left = video->intraAvailA ;
    }
    availability.top_right = BlkTopRight[blkidx];

    if (availability.top_right == 2)
    {
        availability.top_right = video->intraAvailB;
    }
    else if (availability.top_right == 3)
    {
        availability.top_right = video->intraAvailC;
    }

    if (availability.top == TRUE)
    {
        temp = *(uint32*)(cur - pitch);
        P_A = temp & 0xFF;
        P_B = (temp >> 8) & 0xFF;
        P_C = (temp >> 16) & 0xFF;
        P_D = (temp >> 24) & 0xFF;
    }
    else
    {
        P_A = P_B = P_C = P_D = 128;
    }

    if (availability.top_right == TRUE)
    {
        temp = *(uint32*)(cur - pitch + 4);
        P_E = temp & 0xFF;
        P_F = (temp >> 8) & 0xFF;
        P_G = (temp >> 16) & 0xFF;
        P_H = (temp >> 24) & 0xFF;
    }
    else
    {
        P_E = P_F = P_G = P_H = 128;
    }

    if (availability.left == TRUE)
    {
        cur--;
        P_I = *cur;
        P_J = *(cur += pitch);
        P_K = *(cur += pitch);
        P_L = *(cur + pitch);
        cur -= (pitch << 1);
        cur++;
    }
    else
    {
        P_I = P_J = P_K = P_L = 128;
    }

    /* check if top-left pixel is available */
    if (((blkidx > 3) && (blkidx&0x3)) || ((blkidx > 3) && video->intraAvailA)
            || ((blkidx&0x3) && video->intraAvailB)
            || (video->intraAvailA && video->intraAvailD && video->intraAvailB))
    {
        top_left = TRUE;
        P_X = *(cur - pitch - 1);
    }
    else
    {
        P_X = 128;
    }

    //===== INTRA PREDICTION FOR 4x4 BLOCK =====
    /* vertical */
    mode_avail[AVC_I4_Vertical] = 0;
    if (availability.top)
    {
        mode_avail[AVC_I4_Vertical] = 1;
        pred = encvid->pred_i4[AVC_I4_Vertical];

        temp = (P_D << 24) | (P_C << 16) | (P_B << 8) | P_A ;
        *((uint32*)pred) =  temp; /* write 4 at a time */
        *((uint32*)(pred += 4)) =  temp;
        *((uint32*)(pred += 4)) =  temp;
        *((uint32*)(pred += 4)) =  temp;
    }
    /* horizontal */
    mode_avail[AVC_I4_Horizontal] = 0;
    mode_avail[AVC_I4_Horizontal_Up] = 0;
    if (availability.left)
    {
        mode_avail[AVC_I4_Horizontal] = 1;
        pred = encvid->pred_i4[AVC_I4_Horizontal];

        temp = P_I | (P_I << 8);
        temp = temp | (temp << 16);
        *((uint32*)pred) = temp;
        temp = P_J | (P_J << 8);
        temp = temp | (temp << 16);
        *((uint32*)(pred += 4)) = temp;
        temp = P_K | (P_K << 8);
        temp = temp | (temp << 16);
        *((uint32*)(pred += 4)) = temp;
        temp = P_L | (P_L << 8);
        temp = temp | (temp << 16);
        *((uint32*)(pred += 4)) = temp;

        mode_avail[AVC_I4_Horizontal_Up] = 1;
        pred = encvid->pred_i4[AVC_I4_Horizontal_Up];

        Q0 = (P_J + P_K + 1) >> 1;
        Q1 = (P_J + (P_K << 1) + P_L + 2) >> 2;
        P0 = ((P_I + P_J + 1) >> 1);
        P1 = ((P_I + (P_J << 1) + P_K + 2) >> 2);

        temp = P0 | (P1 << 8);      // [P0 P1 Q0 Q1]
        temp |= (Q0 << 16);     // [Q0 Q1 R0 DO]
        temp |= (Q1 << 24);     // [R0 D0 D1 D1]
        *((uint32*)pred) = temp;      // [D1 D1 D1 D1]

        D0 = (P_K + 3 * P_L + 2) >> 2;
        R0 = (P_K + P_L + 1) >> 1;

        temp = Q0 | (Q1 << 8);
        temp |= (R0 << 16);
        temp |= (D0 << 24);
        *((uint32*)(pred += 4)) = temp;

        D1 = P_L;

        temp = R0 | (D0 << 8);
        temp |= (D1 << 16);
        temp |= (D1 << 24);
        *((uint32*)(pred += 4)) = temp;

        temp = D1 | (D1 << 8);
        temp |= (temp << 16);
        *((uint32*)(pred += 4)) = temp;
    }
    /* DC */
    mode_avail[AVC_I4_DC] = 1;
    pred = encvid->pred_i4[AVC_I4_DC];
    if (availability.left)
    {
        DC = P_I + P_J + P_K + P_L;

        if (availability.top)
        {
            DC = (P_A + P_B + P_C + P_D + DC + 4) >> 3;
        }
        else
        {
            DC = (DC + 2) >> 2;

        }
    }
    else if (availability.top)
    {
        DC = (P_A + P_B + P_C + P_D + 2) >> 2;

    }
    else
    {
        DC = 128;
    }

    temp = DC | (DC << 8);
    temp = temp | (temp << 16);
    *((uint32*)pred) = temp;
    *((uint32*)(pred += 4)) = temp;
    *((uint32*)(pred += 4)) = temp;
    *((uint32*)(pred += 4)) = temp;

    /* Down-left */
    mode_avail[AVC_I4_Diagonal_Down_Left] = 0;

    if (availability.top)
    {
        mode_avail[AVC_I4_Diagonal_Down_Left] = 1;

        pred = encvid->pred_i4[AVC_I4_Diagonal_Down_Left];

        r0 = P_A;
        r1 = P_B;
        r2 = P_C;
        r3 = P_D;

        r0 += (r1 << 1);
        r0 += r2;
        r0 += 2;
        r0 >>= 2;
        r1 += (r2 << 1);
        r1 += r3;
        r1 += 2;
        r1 >>= 2;

        if (availability.top_right)
        {
            r4 = P_E;
            r5 = P_F;
            r6 = P_G;
            r7 = P_H;

            r2 += (r3 << 1);
            r2 += r4;
            r2 += 2;
            r2 >>= 2;
            r3 += (r4 << 1);
            r3 += r5;
            r3 += 2;
            r3 >>= 2;
            r4 += (r5 << 1);
            r4 += r6;
            r4 += 2;
            r4 >>= 2;
            r5 += (r6 << 1);
            r5 += r7;
            r5 += 2;
            r5 >>= 2;
            r6 += (3 * r7);
            r6 += 2;
            r6 >>= 2;
            temp = r0 | (r1 << 8);
            temp |= (r2 << 16);
            temp |= (r3 << 24);
            *((uint32*)pred) = temp;

            temp = (temp >> 8) | (r4 << 24);
            *((uint32*)(pred += 4)) = temp;

            temp = (temp >> 8) | (r5 << 24);
            *((uint32*)(pred += 4)) = temp;

            temp = (temp >> 8) | (r6 << 24);
            *((uint32*)(pred += 4)) = temp;
        }
        else
        {
            r2 += (r3 * 3);
            r2 += 2;
            r2 >>= 2;
            r3 = ((r3 << 2) + 2);
            r3 >>= 2;

            temp = r0 | (r1 << 8);
            temp |= (r2 << 16);
            temp |= (r3 << 24);
            *((uint32*)pred) = temp;

            temp = (temp >> 8) | (r3 << 24);
            *((uint32*)(pred += 4)) = temp;

            temp = (temp >> 8) | (r3 << 24);
            *((uint32*)(pred += 4)) = temp;

            temp = (temp >> 8) | (r3 << 24);
            *((uint32*)(pred += 4)) = temp;

        }
    }

    /* Down Right */
    mode_avail[AVC_I4_Diagonal_Down_Right] = 0;
    /* Diagonal Vertical Right */
    mode_avail[AVC_I4_Vertical_Right] = 0;
    /* Horizontal Down */
    mode_avail[AVC_I4_Horizontal_Down] = 0;

    if (top_left == TRUE)
    {
        /* Down Right */
        mode_avail[AVC_I4_Diagonal_Down_Right] = 1;
        pred = encvid->pred_i4[AVC_I4_Diagonal_Down_Right];

        Q_x = (P_A + 2 * P_B + P_C + 2) >> 2;
        R_x = (P_B + 2 * P_C + P_D + 2) >> 2;
        P_x = (P_X + 2 * P_A + P_B + 2) >> 2;
        D   = (P_A + 2 * P_X + P_I + 2) >> 2;
        P_y = (P_X + 2 * P_I + P_J + 2) >> 2;
        Q_y = (P_I + 2 * P_J + P_K + 2) >> 2;
        R_y = (P_J + 2 * P_K + P_L + 2) >> 2;

        /* we can pack these */
        temp =  D | (P_x << 8);   //[D   P_x Q_x R_x]
        //[P_y D   P_x Q_x]
        temp |= (Q_x << 16); //[Q_y P_y D   P_x]
        temp |= (R_x << 24);  //[R_y Q_y P_y D  ]
        *((uint32*)pred) = temp;

        temp =  P_y | (D << 8);
        temp |= (P_x << 16);
        temp |= (Q_x << 24);
        *((uint32*)(pred += 4)) = temp;

        temp =  Q_y | (P_y << 8);
        temp |= (D << 16);
        temp |= (P_x << 24);
        *((uint32*)(pred += 4)) = temp;

        temp = R_y | (Q_y << 8);
        temp |= (P_y << 16);
        temp |= (D << 24);
        *((uint32*)(pred += 4)) = temp;


        /* Diagonal Vertical Right */
        mode_avail[AVC_I4_Vertical_Right] = 1;
        pred = encvid->pred_i4[AVC_I4_Vertical_Right];

        Q0 = P_A + P_B + 1;
        R0 = P_B + P_C + 1;
        S0 = P_C + P_D + 1;
        P0 = P_X + P_A + 1;
        D = (P_I + 2 * P_X + P_A + 2) >> 2;

        P1 = (P0 + Q0) >> 2;
        Q1 = (Q0 + R0) >> 2;
        R1 = (R0 + S0) >> 2;

        P0 >>= 1;
        Q0 >>= 1;
        R0 >>= 1;
        S0 >>= 1;

        P2 = (P_X + 2 * P_I + P_J + 2) >> 2;
        Q2 = (P_I + 2 * P_J + P_K + 2) >> 2;

        temp =  P0 | (Q0 << 8);  //[P0 Q0 R0 S0]
        //[D  P1 Q1 R1]
        temp |= (R0 << 16); //[P2 P0 Q0 R0]
        temp |= (S0 << 24); //[Q2 D  P1 Q1]
        *((uint32*)pred) =  temp;

        temp =  D | (P1 << 8);
        temp |= (Q1 << 16);
        temp |= (R1 << 24);
        *((uint32*)(pred += 4)) =  temp;

        temp = P2 | (P0 << 8);
        temp |= (Q0 << 16);
        temp |= (R0 << 24);
        *((uint32*)(pred += 4)) =  temp;

        temp = Q2 | (D << 8);
        temp |= (P1 << 16);
        temp |= (Q1 << 24);
        *((uint32*)(pred += 4)) =  temp;


        /* Horizontal Down */
        mode_avail[AVC_I4_Horizontal_Down] = 1;
        pred = encvid->pred_i4[AVC_I4_Horizontal_Down];


        Q2 = (P_A + 2 * P_B + P_C + 2) >> 2;
        P2 = (P_X + 2 * P_A + P_B + 2) >> 2;
        D = (P_I + 2 * P_X + P_A + 2) >> 2;
        P0 = P_X + P_I + 1;
        Q0 = P_I + P_J + 1;
        R0 = P_J + P_K + 1;
        S0 = P_K + P_L + 1;

        P1 = (P0 + Q0) >> 2;
        Q1 = (Q0 + R0) >> 2;
        R1 = (R0 + S0) >> 2;

        P0 >>= 1;
        Q0 >>= 1;
        R0 >>= 1;
        S0 >>= 1;


        /* we can pack these */
        temp = P0 | (D << 8);   //[P0 D  P2 Q2]
        //[Q0 P1 P0 D ]
        temp |= (P2 << 16);  //[R0 Q1 Q0 P1]
        temp |= (Q2 << 24); //[S0 R1 R0 Q1]
        *((uint32*)pred) = temp;

        temp = Q0 | (P1 << 8);
        temp |= (P0 << 16);
        temp |= (D << 24);
        *((uint32*)(pred += 4)) = temp;

        temp = R0 | (Q1 << 8);
        temp |= (Q0 << 16);
        temp |= (P1 << 24);
        *((uint32*)(pred += 4)) = temp;

        temp = S0 | (R1 << 8);
        temp |= (R0 << 16);
        temp |= (Q1 << 24);
        *((uint32*)(pred += 4)) = temp;

    }

    /* vertical left */
    mode_avail[AVC_I4_Vertical_Left] = 0;
    if (availability.top)
    {
        mode_avail[AVC_I4_Vertical_Left] = 1;
        pred = encvid->pred_i4[AVC_I4_Vertical_Left];

        x0 = P_A + P_B + 1;
        x1 = P_B + P_C + 1;
        x2 = P_C + P_D + 1;
        if (availability.top_right)
        {
            x3 = P_D + P_E + 1;
            x4 = P_E + P_F + 1;
            x5 = P_F + P_G + 1;
        }
        else
        {
            x3 = x4 = x5 = (P_D << 1) + 1;
        }

        temp1 = (x0 >> 1);
        temp1 |= ((x1 >> 1) << 8);
        temp1 |= ((x2 >> 1) << 16);
        temp1 |= ((x3 >> 1) << 24);

        *((uint32*)pred) = temp1;

        temp2 = ((x0 + x1) >> 2);
        temp2 |= (((x1 + x2) >> 2) << 8);
        temp2 |= (((x2 + x3) >> 2) << 16);
        temp2 |= (((x3 + x4) >> 2) << 24);

        *((uint32*)(pred += 4)) = temp2;

        temp1 = (temp1 >> 8) | ((x4 >> 1) << 24);   /* rotate out old value */
        *((uint32*)(pred += 4)) = temp1;

        temp2 = (temp2 >> 8) | (((x4 + x5) >> 2) << 24); /* rotate out old value */
        *((uint32*)(pred += 4)) = temp2;
    }

    //===== LOOP OVER ALL 4x4 INTRA PREDICTION MODES =====
    // can re-order the search here instead of going in order

    // find most probable mode
    encvid->mostProbableI4Mode[blkidx] = mostProbableMode = FindMostProbableI4Mode(video, blkidx);

    min_cost = 0xFFFF;

    for (ipmode = 0; ipmode < AVCNumI4PredMode; ipmode++)
    {
        if (mode_avail[ipmode] == TRUE)
        {
            cost  = (ipmode == mostProbableMode) ? 0 : fixedcost;
            pred = encvid->pred_i4[ipmode];

            cost_i4(org, org_pitch, pred, &cost);

            if (cost < min_cost)
            {
                currMB->i4Mode[blkidx] = (AVCIntra4x4PredMode)ipmode;
                min_cost   = cost;
                min_sad = cost - ((ipmode == mostProbableMode) ? 0 : fixedcost);
            }
        }
    }

    if (blkidx == 0)
    {
        encvid->i4_sad = min_sad;
    }
    else
    {
        encvid->i4_sad += min_sad;
    }

    return min_cost;
}

int FindMostProbableI4Mode(AVCCommonObj *video, int blkidx)
{
    int dcOnlyPredictionFlag;
    AVCMacroblock *currMB = video->currMB;
    int intra4x4PredModeA, intra4x4PredModeB, predIntra4x4PredMode;


    dcOnlyPredictionFlag = 0;
    if (blkidx&0x3)
    {
        intra4x4PredModeA = currMB->i4Mode[blkidx-1]; // block to the left
    }
    else /* for blk 0, 4, 8, 12 */
    {
        if (video->intraAvailA)
        {
            if (video->mblock[video->mbAddrA].mbMode == AVC_I4)
            {
                intra4x4PredModeA = video->mblock[video->mbAddrA].i4Mode[blkidx + 3];
            }
            else
            {
                intra4x4PredModeA = AVC_I4_DC;
            }
        }
        else
        {
            dcOnlyPredictionFlag = 1;
            goto PRED_RESULT_READY;  // skip below
        }
    }

    if (blkidx >> 2)
    {
        intra4x4PredModeB = currMB->i4Mode[blkidx-4]; // block above
    }
    else /* block 0, 1, 2, 3 */
    {
        if (video->intraAvailB)
        {
            if (video->mblock[video->mbAddrB].mbMode == AVC_I4)
            {
                intra4x4PredModeB = video->mblock[video->mbAddrB].i4Mode[blkidx+12];
            }
            else
            {
                intra4x4PredModeB = AVC_I4_DC;
            }
        }
        else
        {
            dcOnlyPredictionFlag = 1;
        }
    }

PRED_RESULT_READY:
    if (dcOnlyPredictionFlag)
    {
        intra4x4PredModeA = intra4x4PredModeB = AVC_I4_DC;
    }

    predIntra4x4PredMode = AVC_MIN(intra4x4PredModeA, intra4x4PredModeB);

    return predIntra4x4PredMode;
}

void cost_i4(uint8 *org, int org_pitch, uint8 *pred, uint16 *cost)
{
    int k;
    int16 res[16], *pres;
    int m0, m1, m2, m3, tmp1;
    int satd = 0;

    pres = res;
    // horizontal transform
    k = 4;
    while (k > 0)
    {
        m0 = org[0] - pred[0];
        m3 = org[3] - pred[3];
        m0 += m3;
        m3 = m0 - (m3 << 1);
        m1 = org[1] - pred[1];
        m2 = org[2] - pred[2];
        m1 += m2;
        m2 = m1 - (m2 << 1);
        pres[0] = m0 + m1;
        pres[2] = m0 - m1;
        pres[1] = m2 + m3;
        pres[3] = m3 - m2;

        org += org_pitch;
        pres += 4;
        pred += 4;
        k--;
    }
    /* vertical transform */
    pres = res;
    k = 4;
    while (k > 0)
    {
        m0 = pres[0];
        m3 = pres[12];
        m0 += m3;
        m3 = m0 - (m3 << 1);
        m1 = pres[4];
        m2 = pres[8];
        m1 += m2;
        m2 = m1 - (m2 << 1);
        pres[0] = m0 + m1;
        pres[8] = m0 - m1;
        pres[4] = m2 + m3;
        pres[12] = m3 - m2;

        pres++;
        k--;

    }

    pres = res;
    k = 4;
    while (k > 0)
    {
        tmp1 = *pres++;
        satd += ((tmp1 >= 0) ? tmp1 : -tmp1);
        tmp1 = *pres++;
        satd += ((tmp1 >= 0) ? tmp1 : -tmp1);
        tmp1 = *pres++;
        satd += ((tmp1 >= 0) ? tmp1 : -tmp1);
        tmp1 = *pres++;
        satd += ((tmp1 >= 0) ? tmp1 : -tmp1);
        k--;
    }

    satd = (satd + 1) >> 1;
    *cost += satd;

    return ;
}

void chroma_intra_search(AVCEncObject *encvid)
{
    AVCCommonObj *video = encvid->common;
    AVCPictureData *currPic = video->currPic;

    int x_pos = video->mb_x << 3;
    int y_pos = video->mb_y << 3;
    int pitch = currPic->pitch >> 1;
    int offset = y_pos * pitch + x_pos;

    uint8 *comp_ref_x, *comp_ref_y, *pred;
    int  sum_x0, sum_x1, sum_y0, sum_y1;
    int pred_0[2], pred_1[2], pred_2[2], pred_3[2];
    uint32 pred_a, pred_b, pred_c, pred_d;
    int i, j, component;
    int a_16, b, c, factor_c, topleft;
    int H, V, value;
    uint8 *comp_ref_x0, *comp_ref_x1,  *comp_ref_y0, *comp_ref_y1;

    uint8 *curCb = currPic->Scb + offset;
    uint8 *curCr = currPic->Scr + offset;

    uint8 *orgCb, *orgCr;
    AVCFrameIO *currInput = encvid->currInput;
    AVCMacroblock *currMB = video->currMB;
    int org_pitch;
    int cost, mincost;

    /* evaluate DC mode */
    if (video->intraAvailB & video->intraAvailA)
    {
        comp_ref_x = curCb - pitch;
        comp_ref_y = curCb - 1;

        for (i = 0; i < 2; i++)
        {
            pred_a = *((uint32*)comp_ref_x);
            comp_ref_x += 4;
            pred_b = (pred_a >> 8) & 0xFF00FF;
            pred_a &= 0xFF00FF;
            pred_a += pred_b;
            pred_a += (pred_a >> 16);
            sum_x0 = pred_a & 0xFFFF;

            pred_a = *((uint32*)comp_ref_x);
            pred_b = (pred_a >> 8) & 0xFF00FF;
            pred_a &= 0xFF00FF;
            pred_a += pred_b;
            pred_a += (pred_a >> 16);
            sum_x1 = pred_a & 0xFFFF;

            pred_1[i] = (sum_x1 + 2) >> 2;

            sum_y0 = *comp_ref_y;
            sum_y0 += *(comp_ref_y += pitch);
            sum_y0 += *(comp_ref_y += pitch);
            sum_y0 += *(comp_ref_y += pitch);

            sum_y1 = *(comp_ref_y += pitch);
            sum_y1 += *(comp_ref_y += pitch);
            sum_y1 += *(comp_ref_y += pitch);
            sum_y1 += *(comp_ref_y += pitch);

            pred_2[i] = (sum_y1 + 2) >> 2;

            pred_0[i] = (sum_y0 + sum_x0 + 4) >> 3;
            pred_3[i] = (sum_y1 + sum_x1 + 4) >> 3;

            comp_ref_x = curCr - pitch;
            comp_ref_y = curCr - 1;
        }
    }

    else if (video->intraAvailA)
    {
        comp_ref_y = curCb - 1;
        for (i = 0; i < 2; i++)
        {
            sum_y0 = *comp_ref_y;
            sum_y0 += *(comp_ref_y += pitch);
            sum_y0 += *(comp_ref_y += pitch);
            sum_y0 += *(comp_ref_y += pitch);

            sum_y1 = *(comp_ref_y += pitch);
            sum_y1 += *(comp_ref_y += pitch);
            sum_y1 += *(comp_ref_y += pitch);
            sum_y1 += *(comp_ref_y += pitch);

            pred_0[i] = pred_1[i] = (sum_y0 + 2) >> 2;
            pred_2[i] = pred_3[i] = (sum_y1 + 2) >> 2;

            comp_ref_y = curCr - 1;
        }
    }
    else if (video->intraAvailB)
    {
        comp_ref_x = curCb - pitch;
        for (i = 0; i < 2; i++)
        {
            pred_a = *((uint32*)comp_ref_x);
            comp_ref_x += 4;
            pred_b = (pred_a >> 8) & 0xFF00FF;
            pred_a &= 0xFF00FF;
            pred_a += pred_b;
            pred_a += (pred_a >> 16);
            sum_x0 = pred_a & 0xFFFF;

            pred_a = *((uint32*)comp_ref_x);
            pred_b = (pred_a >> 8) & 0xFF00FF;
            pred_a &= 0xFF00FF;
            pred_a += pred_b;
            pred_a += (pred_a >> 16);
            sum_x1 = pred_a & 0xFFFF;

            pred_0[i] = pred_2[i] = (sum_x0 + 2) >> 2;
            pred_1[i] = pred_3[i] = (sum_x1 + 2) >> 2;

            comp_ref_x = curCr - pitch;
        }
    }
    else
    {
        pred_0[0] = pred_0[1] = pred_1[0] = pred_1[1] =
                                                pred_2[0] = pred_2[1] = pred_3[0] = pred_3[1] = 128;
    }

    pred = encvid->pred_ic[AVC_IC_DC];

    pred_a = pred_0[0];
    pred_b = pred_1[0];
    pred_a |= (pred_a << 8);
    pred_a |= (pred_a << 16);
    pred_b |= (pred_b << 8);
    pred_b |= (pred_b << 16);

    pred_c = pred_0[1];
    pred_d = pred_1[1];
    pred_c |= (pred_c << 8);
    pred_c |= (pred_c << 16);
    pred_d |= (pred_d << 8);
    pred_d |= (pred_d << 16);


    for (j = 0; j < 4; j++) /* 4 lines */
    {
        *((uint32*)pred) = pred_a;
        *((uint32*)(pred + 4)) = pred_b;
        *((uint32*)(pred + 8)) = pred_c;
        *((uint32*)(pred + 12)) = pred_d;
        pred += 16; /* move to the next line */
    }

    pred_a = pred_2[0];
    pred_b = pred_3[0];
    pred_a |= (pred_a << 8);
    pred_a |= (pred_a << 16);
    pred_b |= (pred_b << 8);
    pred_b |= (pred_b << 16);

    pred_c = pred_2[1];
    pred_d = pred_3[1];
    pred_c |= (pred_c << 8);
    pred_c |= (pred_c << 16);
    pred_d |= (pred_d << 8);
    pred_d |= (pred_d << 16);

    for (j = 0; j < 4; j++) /* 4 lines */
    {
        *((uint32*)pred) = pred_a;
        *((uint32*)(pred + 4)) = pred_b;
        *((uint32*)(pred + 8)) = pred_c;
        *((uint32*)(pred + 12)) = pred_d;
        pred += 16; /* move to the next line */
    }

    /* predict horizontal mode */
    if (video->intraAvailA)
    {
        comp_ref_y = curCb - 1;
        comp_ref_x = curCr - 1;
        pred = encvid->pred_ic[AVC_IC_Horizontal];

        for (i = 4; i < 6; i++)
        {
            for (j = 0; j < 4; j++)
            {
                pred_a = *comp_ref_y;
                comp_ref_y += pitch;
                pred_a |= (pred_a << 8);
                pred_a |= (pred_a << 16);
                *((uint32*)pred) = pred_a;
                *((uint32*)(pred + 4)) = pred_a;

                pred_a = *comp_ref_x;
                comp_ref_x += pitch;
                pred_a |= (pred_a << 8);
                pred_a |= (pred_a << 16);
                *((uint32*)(pred + 8)) = pred_a;
                *((uint32*)(pred + 12)) = pred_a;

                pred += 16;
            }
        }
    }

    /* vertical mode */
    if (video->intraAvailB)
    {
        comp_ref_x = curCb - pitch;
        comp_ref_y = curCr - pitch;
        pred = encvid->pred_ic[AVC_IC_Vertical];

        pred_a = *((uint32*)comp_ref_x);
        pred_b = *((uint32*)(comp_ref_x + 4));
        pred_c = *((uint32*)comp_ref_y);
        pred_d = *((uint32*)(comp_ref_y + 4));

        for (j = 0; j < 8; j++)
        {
            *((uint32*)pred) = pred_a;
            *((uint32*)(pred + 4)) = pred_b;
            *((uint32*)(pred + 8)) = pred_c;
            *((uint32*)(pred + 12)) = pred_d;
            pred += 16;
        }
    }

    /* Intra_Chroma_Plane */
    if (video->intraAvailA && video->intraAvailB && video->intraAvailD)
    {
        comp_ref_x = curCb - pitch;
        comp_ref_y = curCb - 1;
        topleft = curCb[-pitch-1];

        pred = encvid->pred_ic[AVC_IC_Plane];
        for (component = 0; component < 2; component++)
        {
            H = V = 0;
            comp_ref_x0 = comp_ref_x + 4;
            comp_ref_x1 = comp_ref_x + 2;
            comp_ref_y0 = comp_ref_y + (pitch << 2);
            comp_ref_y1 = comp_ref_y + (pitch << 1);
            for (i = 1; i < 4; i++)
            {
                H += i * (*comp_ref_x0++ - *comp_ref_x1--);
                V += i * (*comp_ref_y0 - *comp_ref_y1);
                comp_ref_y0 += pitch;
                comp_ref_y1 -= pitch;
            }
            H += i * (*comp_ref_x0++ - topleft);
            V += i * (*comp_ref_y0 - *comp_ref_y1);

            a_16 = ((*(comp_ref_x + 7) + *(comp_ref_y + 7 * pitch)) << 4) + 16;
            b = (17 * H + 16) >> 5;
            c = (17 * V + 16) >> 5;

            pred_a = 0;
            for (i = 4; i < 6; i++)
            {
                for (j = 0; j < 4; j++)
                {
                    factor_c = a_16 + c * (pred_a++ - 3);

                    factor_c -= 3 * b;

                    value = factor_c >> 5;
                    factor_c += b;
                    CLIP_RESULT(value)
                    pred_b = value;
                    value = factor_c >> 5;
                    factor_c += b;
                    CLIP_RESULT(value)
                    pred_b |= (value << 8);
                    value = factor_c >> 5;
                    factor_c += b;
                    CLIP_RESULT(value)
                    pred_b |= (value << 16);
                    value = factor_c >> 5;
                    factor_c += b;
                    CLIP_RESULT(value)
                    pred_b |= (value << 24);
                    *((uint32*)pred) = pred_b;

                    value = factor_c >> 5;
                    factor_c += b;
                    CLIP_RESULT(value)
                    pred_b = value;
                    value = factor_c >> 5;
                    factor_c += b;
                    CLIP_RESULT(value)
                    pred_b |= (value << 8);
                    value = factor_c >> 5;
                    factor_c += b;
                    CLIP_RESULT(value)
                    pred_b |= (value << 16);
                    value = factor_c >> 5;
                    factor_c += b;
                    CLIP_RESULT(value)
                    pred_b |= (value << 24);
                    *((uint32*)(pred + 4)) = pred_b;
                    pred += 16;
                }
            }

            pred -= 120; /* point to cr */
            comp_ref_x = curCr - pitch;
            comp_ref_y = curCr - 1;
            topleft = curCr[-pitch-1];
        }
    }

    /* now evaluate it */

    org_pitch = (currInput->pitch) >> 1;
    offset = x_pos + y_pos * org_pitch;

    orgCb = currInput->YCbCr[1] + offset;
    orgCr = currInput->YCbCr[2] + offset;

    mincost = 0x7fffffff;
    cost = SATDChroma(orgCb, orgCr, org_pitch, encvid->pred_ic[AVC_IC_DC], mincost);
    if (cost < mincost)
    {
        mincost = cost;
        currMB->intra_chroma_pred_mode = AVC_IC_DC;
    }

    if (video->intraAvailA)
    {
        cost = SATDChroma(orgCb, orgCr, org_pitch, encvid->pred_ic[AVC_IC_Horizontal], mincost);
        if (cost < mincost)
        {
            mincost = cost;
            currMB->intra_chroma_pred_mode = AVC_IC_Horizontal;
        }
    }

    if (video->intraAvailB)
    {
        cost = SATDChroma(orgCb, orgCr, org_pitch, encvid->pred_ic[AVC_IC_Vertical], mincost);
        if (cost < mincost)
        {
            mincost = cost;
            currMB->intra_chroma_pred_mode = AVC_IC_Vertical;
        }
    }

    if (video->intraAvailA && video->intraAvailB && video->intraAvailD)
    {
        cost = SATDChroma(orgCb, orgCr, org_pitch, encvid->pred_ic[AVC_IC_Plane], mincost);
        if (cost < mincost)
        {
            mincost = cost;
            currMB->intra_chroma_pred_mode = AVC_IC_Plane;
        }
    }


    return ;
}


int SATDChroma(uint8 *orgCb, uint8 *orgCr, int org_pitch, uint8 *pred, int min_cost)
{
    int cost;
    /* first take difference between orgCb, orgCr and pred */
    int16 res[128], *pres; // residue
    int m0, m1, m2, m3, tmp1;
    int j, k;

    pres = res;
    org_pitch -= 8;
    // horizontal transform
    for (j = 0; j < 8; j++)
    {
        k = 2;
        while (k > 0)
        {
            m0 = orgCb[0] - pred[0];
            m3 = orgCb[3] - pred[3];
            m0 += m3;
            m3 = m0 - (m3 << 1);
            m1 = orgCb[1] - pred[1];
            m2 = orgCb[2] - pred[2];
            m1 += m2;
            m2 = m1 - (m2 << 1);
            pres[0] = m0 + m1;
            pres[2] = m0 - m1;
            pres[1] = m2 + m3;
            pres[3] = m3 - m2;

            orgCb += 4;
            pres += 4;
            pred += 4;
            k--;
        }
        orgCb += org_pitch;
        k = 2;
        while (k > 0)
        {
            m0 = orgCr[0] - pred[0];
            m3 = orgCr[3] - pred[3];
            m0 += m3;
            m3 = m0 - (m3 << 1);
            m1 = orgCr[1] - pred[1];
            m2 = orgCr[2] - pred[2];
            m1 += m2;
            m2 = m1 - (m2 << 1);
            pres[0] = m0 + m1;
            pres[2] = m0 - m1;
            pres[1] = m2 + m3;
            pres[3] = m3 - m2;

            orgCr += 4;
            pres += 4;
            pred += 4;
            k--;
        }
        orgCr += org_pitch;
    }

    /* vertical transform */
    for (j = 0; j < 2; j++)
    {
        pres = res + (j << 6);
        k = 16;
        while (k > 0)
        {
            m0 = pres[0];
            m3 = pres[3<<4];
            m0 += m3;
            m3 = m0 - (m3 << 1);
            m1 = pres[1<<4];
            m2 = pres[2<<4];
            m1 += m2;
            m2 = m1 - (m2 << 1);
            pres[0] = m0 + m1;
            pres[2<<4] = m0 - m1;
            pres[1<<4] = m2 + m3;
            pres[3<<4] = m3 - m2;

            pres++;
            k--;
        }
    }

    /* now sum of absolute value */
    pres = res;
    cost = 0;
    k = 128;
    while (k > 0)
    {
        tmp1 = *pres++;
        cost += ((tmp1 >= 0) ? tmp1 : -tmp1);
        tmp1 = *pres++;
        cost += ((tmp1 >= 0) ? tmp1 : -tmp1);
        tmp1 = *pres++;
        cost += ((tmp1 >= 0) ? tmp1 : -tmp1);
        tmp1 = *pres++;
        cost += ((tmp1 >= 0) ? tmp1 : -tmp1);
        tmp1 = *pres++;
        cost += ((tmp1 >= 0) ? tmp1 : -tmp1);
        tmp1 = *pres++;
        cost += ((tmp1 >= 0) ? tmp1 : -tmp1);
        tmp1 = *pres++;
        cost += ((tmp1 >= 0) ? tmp1 : -tmp1);
        tmp1 = *pres++;
        cost += ((tmp1 >= 0) ? tmp1 : -tmp1);
        k -= 8;
        if (cost > min_cost) /* early drop out */
        {
            return cost;
        }
    }

    return cost;
}



///////////////////////////////// old code, unused
/* find the best intra mode based on original (unencoded) frame */
/* output is
    currMB->mb_intra, currMB->mbMode,
    currMB->i16Mode  (if currMB->mbMode == AVC_I16)
    currMB->i4Mode[..] (if currMB->mbMode == AVC_I4) */

#ifdef FIXED_INTRAPRED_MODE
void MBIntraSearch(AVCEncObject *encvid, AVCMacroblock *currMB, int mbNum)
{
    (void)(mbNum);

    AVCCommonObj *video = encvid->common;
    int indx, block_x, block_y;

    video->intraAvailA = video->intraAvailB = video->intraAvailC = video->intraAvailD = 0;

    if (!video->currPicParams->constrained_intra_pred_flag)
    {
        video->intraAvailA = video->mbAvailA;
        video->intraAvailB = video->mbAvailB;
        video->intraAvailC = video->mbAvailC;
        video->intraAvailD = video->mbAvailD;
    }
    else
    {
        if (video->mbAvailA)
        {
            video->intraAvailA = video->mblock[video->mbAddrA].mb_intra;
        }
        if (video->mbAvailB)
        {
            video->intraAvailB = video->mblock[video->mbAddrB].mb_intra ;
        }
        if (video->mbAvailC)
        {
            video->intraAvailC = video->mblock[video->mbAddrC].mb_intra;
        }
        if (video->mbAvailD)
        {
            video->intraAvailD = video->mblock[video->mbAddrD].mb_intra;
        }
    }

    currMB->mb_intra = TRUE;
    currMB->mbMode = FIXED_INTRAPRED_MODE;

    if (currMB->mbMode == AVC_I16)
    {
        currMB->i16Mode = FIXED_I16_MODE;

        if (FIXED_I16_MODE == AVC_I16_Vertical && !video->intraAvailB)
        {
            currMB->i16Mode = AVC_I16_DC;
        }

        if (FIXED_I16_MODE == AVC_I16_Horizontal && !video->intraAvailA)
        {
            currMB->i16Mode = AVC_I16_DC;
        }

        if (FIXED_I16_MODE == AVC_I16_Plane && !(video->intraAvailA && video->intraAvailB && video->intraAvailD))
        {
            currMB->i16Mode = AVC_I16_DC;
        }
    }
    else //if(currMB->mbMode == AVC_I4)
    {
        for (indx = 0; indx < 16; indx++)
        {
            block_x = blkIdx2blkX[indx];
            block_y = blkIdx2blkY[indx];

            currMB->i4Mode[(block_y<<2)+block_x] = FIXED_I4_MODE;

            if (FIXED_I4_MODE == AVC_I4_Vertical && !(block_y > 0 || video->intraAvailB))
            {
                currMB->i4Mode[(block_y<<2)+block_x] = AVC_I4_DC;
            }

            if (FIXED_I4_MODE == AVC_I4_Horizontal && !(block_x || video->intraAvailA))
            {
                currMB->i4Mode[(block_y<<2)+block_x] = AVC_I4_DC;
            }

            if (FIXED_I4_MODE == AVC_I4_Diagonal_Down_Left &&
                    (block_y == 0 && !video->intraAvailB))
            {
                currMB->i4Mode[(block_y<<2)+block_x] = AVC_I4_DC;
            }

            if (FIXED_I4_MODE == AVC_I4_Diagonal_Down_Right &&
                    !((block_y && block_x)
                      || (block_y && video->intraAvailA)
                      || (block_x && video->intraAvailB)
                      || (video->intraAvailA && video->intraAvailD && video->intraAvailB)))
            {
                currMB->i4Mode[(block_y<<2)+block_x] = AVC_I4_DC;
            }

            if (FIXED_I4_MODE == AVC_I4_Vertical_Right &&
                    !((block_y && block_x)
                      || (block_y && video->intraAvailA)
                      || (block_x && video->intraAvailB)
                      || (video->intraAvailA && video->intraAvailD && video->intraAvailB)))
            {
                currMB->i4Mode[(block_y<<2)+block_x] = AVC_I4_DC;
            }

            if (FIXED_I4_MODE == AVC_I4_Horizontal_Down &&
                    !((block_y && block_x)
                      || (block_y && video->intraAvailA)
                      || (block_x && video->intraAvailB)
                      || (video->intraAvailA && video->intraAvailD && video->intraAvailB)))
            {
                currMB->i4Mode[(block_y<<2)+block_x] = AVC_I4_DC;
            }

            if (FIXED_I4_MODE == AVC_I4_Vertical_Left &&
                    (block_y == 0 && !video->intraAvailB))
            {
                currMB->i4Mode[(block_y<<2)+block_x] = AVC_I4_DC;
            }

            if (FIXED_I4_MODE == AVC_I4_Horizontal_Up && !(block_x || video->intraAvailA))
            {
                currMB->i4Mode[(block_y<<2)+block_x] = AVC_I4_DC;
            }
        }
    }

    currMB->intra_chroma_pred_mode = FIXED_INTRA_CHROMA_MODE;

    if (FIXED_INTRA_CHROMA_MODE == AVC_IC_Horizontal && !(video->intraAvailA))
    {
        currMB->intra_chroma_pred_mode = AVC_IC_DC;
    }

    if (FIXED_INTRA_CHROMA_MODE == AVC_IC_Vertical && !(video->intraAvailB))
    {
        currMB->intra_chroma_pred_mode = AVC_IC_DC;
    }

    if (FIXED_INTRA_CHROMA_MODE == AVC_IC_Plane && !(video->intraAvailA && video->intraAvailB && video->intraAvailD))
    {
        currMB->intra_chroma_pred_mode = AVC_IC_DC;
    }

    /* also reset the motion vectors */
    /* set MV and Ref_Idx codes of Intra blocks in P-slices */
    memset(currMB->mvL0, 0, sizeof(int32)*16);
    currMB->ref_idx_L0[0] = -1;
    currMB->ref_idx_L0[1] = -1;
    currMB->ref_idx_L0[2] = -1;
    currMB->ref_idx_L0[3] = -1;

    // output from this function, currMB->mbMode should be set to either
    // AVC_I4, AVC_I16, or else in AVCMBMode enum, mbType, mb_intra, intra_chroma_pred_mode */
    return ;
}
#else // faster combined prediction+SAD calculation
void MBIntraSearch(AVCEncObject *encvid, AVCMacroblock *currMB, int mbNum)
{
    AVCCommonObj *video = encvid->common;
    AVCFrameIO *currInput = encvid->currInput;
    uint8 *curL, *curCb, *curCr;
    uint8 *comp, *pred_block;
    int block_x, block_y, offset;
    uint sad, sad4, sadI4, sadI16;
    int component, SubBlock_indx, temp;
    int pitch = video->currPic->pitch;

    /* calculate the cost of each intra prediction mode  and compare to the
    inter mode */
    /* full search for all intra prediction */
    offset = (video->mb_y << 4) * pitch + (video->mb_x << 4);
    curL = currInput->YCbCr[0] + offset;
    pred_block = video->pred_block + 84;

    /* Assuming that InitNeighborAvailability has been called prior to this function */
    video->intraAvailA = video->intraAvailB = video->intraAvailC = video->intraAvailD = 0;

    if (!video->currPicParams->constrained_intra_pred_flag)
    {
        video->intraAvailA = video->mbAvailA;
        video->intraAvailB = video->mbAvailB;
        video->intraAvailC = video->mbAvailC;
        video->intraAvailD = video->mbAvailD;
    }
    else
    {
        if (video->mbAvailA)
        {
            video->intraAvailA = video->mblock[video->mbAddrA].mb_intra;
        }
        if (video->mbAvailB)
        {
            video->intraAvailB = video->mblock[video->mbAddrB].mb_intra ;
        }
        if (video->mbAvailC)
        {
            video->intraAvailC = video->mblock[video->mbAddrC].mb_intra;
        }
        if (video->mbAvailD)
        {
            video->intraAvailD = video->mblock[video->mbAddrD].mb_intra;
        }
    }

    /* currently we're doing exhaustive search. Smart search will be used later */

    /* I16 modes */
    curL = currInput->YCbCr[0] + offset;
    video->pintra_pred_top = curL - pitch;
    video->pintra_pred_left = curL - 1;
    if (video->mb_y)
    {
        video->intra_pred_topleft = *(curL - pitch - 1);
    }

    /* Intra_16x16_Vertical */
    sadI16 = 65536;
    /* check availability of top */
    if (video->intraAvailB)
    {
        sad = SAD_I16_Vert(video, curL, sadI16);

        if (sad < sadI16)
        {
            sadI16 = sad;
            currMB->i16Mode = AVC_I16_Vertical;
        }
    }
    /* Intra_16x16_Horizontal */
    /* check availability of left */
    if (video->intraAvailA)
    {
        sad = SAD_I16_HorzDC(video, curL, AVC_I16_Horizontal, sadI16);

        if (sad < sadI16)
        {
            sadI16 = sad;
            currMB->i16Mode = AVC_I16_Horizontal;
        }
    }

    /* Intra_16x16_DC, default mode */
    sad = SAD_I16_HorzDC(video, curL, AVC_I16_DC, sadI16);
    if (sad < sadI16)
    {
        sadI16 = sad;
        currMB->i16Mode = AVC_I16_DC;
    }

    /* Intra_16x16_Plane */
    if (video->intraAvailA && video->intraAvailB && video->intraAvailD)
    {
        sad = SAD_I16_Plane(video, curL, sadI16);

        if (sad < sadI16)
        {
            sadI16 = sad;
            currMB->i16Mode = AVC_I16_Plane;
        }
    }

    sadI16 >>= 1;  /* before comparison */

    /* selection between intra4, intra16 or inter mode */
    if (sadI16 < encvid->min_cost)
    {
        currMB->mb_intra = TRUE;
        currMB->mbMode = AVC_I16;
        encvid->min_cost = sadI16;
    }

    if (currMB->mb_intra) /* only do the chrominance search when intra is decided */
    {
        /* Note that we might be able to guess the type of prediction from
        the luma prediction type */

        /* now search for the best chroma intra prediction */
        offset = (offset >> 2) + (video->mb_x << 2);
        curCb = currInput->YCbCr[1] + offset;
        curCr = currInput->YCbCr[2] + offset;

        pitch >>= 1;
        video->pintra_pred_top_cb = curCb - pitch;
        video->pintra_pred_left_cb = curCb - 1;
        video->pintra_pred_top_cr = curCr - pitch;
        video->pintra_pred_left_cr = curCr - 1;

        if (video->mb_y)
        {
            video->intra_pred_topleft_cb = *(curCb - pitch - 1);
            video->intra_pred_topleft_cr = *(curCr - pitch - 1);
        }

        /* Intra_Chroma_DC */
        sad4 = SAD_Chroma_DC(video, curCb, curCr, 65536);
        currMB->intra_chroma_pred_mode = AVC_IC_DC;

        /* Intra_Chroma_Horizontal */
        if (video->intraAvailA)
        {
            /* check availability of left */
            sad = SAD_Chroma_Horz(video, curCb, curCr, sad4);
            if (sad < sad4)
            {
                sad4 = sad;
                currMB->intra_chroma_pred_mode = AVC_IC_Horizontal;
            }
        }

        /* Intra_Chroma_Vertical */
        if (video->intraAvailB)
        {
            /* check availability of top */
            sad = SAD_Chroma_Vert(video, curCb, curCr, sad4);

            if (sad < sad4)
            {
                sad4 = sad;
                currMB->intra_chroma_pred_mode = AVC_IC_Vertical;
            }
        }

        /* Intra_Chroma_Plane */
        if (video->intraAvailA && video->intraAvailB && video->intraAvailD)
        {
            /* check availability of top and left */
            Intra_Chroma_Plane(video, pitch);

            sad = SADChroma(pred_block + 452, curCb, curCr, pitch);

            if (sad < sad4)
            {
                sad4 = sad;
                currMB->intra_chroma_pred_mode = AVC_IC_Plane;
            }
        }

        /* also reset the motion vectors */
        /* set MV and Ref_Idx codes of Intra blocks in P-slices */
        memset(currMB->mvL0, 0, sizeof(int32)*16);
        memset(currMB->ref_idx_L0, -1, sizeof(int16)*4);

    }

    // output from this function, currMB->mbMode should be set to either
    // AVC_I4, AVC_I16, or else in AVCMBMode enum, mbType, mb_intra, intra_chroma_pred_mode */

    return ;
}
#endif


