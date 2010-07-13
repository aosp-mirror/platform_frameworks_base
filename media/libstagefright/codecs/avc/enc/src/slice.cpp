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


AVCEnc_Status AVCEncodeSlice(AVCEncObject *encvid)
{
    AVCEnc_Status status = AVCENC_SUCCESS;
    AVCCommonObj *video = encvid->common;
    AVCPicParamSet *pps = video->currPicParams;
    AVCSliceHeader *sliceHdr = video->sliceHdr;
    AVCMacroblock *currMB ;
    AVCEncBitstream *stream = encvid->bitstream;
    uint slice_group_id;
    int CurrMbAddr, slice_type;

    slice_type = video->slice_type;

    /* set the first mb in slice */
    video->mbNum = CurrMbAddr = sliceHdr->first_mb_in_slice;// * (1+video->MbaffFrameFlag);
    slice_group_id = video->MbToSliceGroupMap[CurrMbAddr];

    video->mb_skip_run = 0;

    /* while loop , see subclause 7.3.4 */
    while (1)
    {
        video->mbNum = CurrMbAddr;
        currMB = video->currMB = &(video->mblock[CurrMbAddr]);
        currMB->slice_id = video->slice_id;  // for deblocking

        video->mb_x = CurrMbAddr % video->PicWidthInMbs;
        video->mb_y = CurrMbAddr / video->PicWidthInMbs;

        /* initialize QP for this MB here*/
        /* calculate currMB->QPy */
        RCInitMBQP(encvid);

        /* check the availability of neighboring macroblocks */
        InitNeighborAvailability(video, CurrMbAddr);

        /* Assuming that InitNeighborAvailability has been called prior to this function */
        video->intraAvailA = video->intraAvailB = video->intraAvailC = video->intraAvailD = 0;
        /* this is necessary for all subsequent intra search */

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

        /* encode_one_macroblock() */
        status = EncodeMB(encvid);
        if (status != AVCENC_SUCCESS)
        {
            break;
        }

        /* go to next MB */
        CurrMbAddr++;

        while ((uint)video->MbToSliceGroupMap[CurrMbAddr] != slice_group_id &&
                (uint)CurrMbAddr < video->PicSizeInMbs)
        {
            CurrMbAddr++;
        }

        if ((uint)CurrMbAddr >= video->PicSizeInMbs)
        {
            /* end of slice, return, but before that check to see if there are other slices
            to be encoded. */
            encvid->currSliceGroup++;
            if (encvid->currSliceGroup > (int)pps->num_slice_groups_minus1) /* no more slice group */
            {
                status = AVCENC_PICTURE_READY;
                break;
            }
            else
            {
                /* find first_mb_num for the next slice */
                CurrMbAddr = 0;
                while (video->MbToSliceGroupMap[CurrMbAddr] != encvid->currSliceGroup &&
                        (uint)CurrMbAddr < video->PicSizeInMbs)
                {
                    CurrMbAddr++;
                }
                if ((uint)CurrMbAddr >= video->PicSizeInMbs)
                {
                    status = AVCENC_SLICE_EMPTY; /* error, one slice group has no MBs in it */
                }

                video->mbNum = CurrMbAddr;
                status = AVCENC_SUCCESS;
                break;
            }
        }
    }

    if (video->mb_skip_run > 0)
    {
        /* write skip_run */
        if (slice_type != AVC_I_SLICE && slice_type != AVC_SI_SLICE)
        {
            ue_v(stream, video->mb_skip_run);
            video->mb_skip_run = 0;
        }
        else    /* shouldn't happen */
        {
            status = AVCENC_FAIL;
        }
    }

    return status;
}


AVCEnc_Status EncodeMB(AVCEncObject *encvid)
{
    AVCEnc_Status status = AVCENC_SUCCESS;
    AVCCommonObj *video = encvid->common;
    AVCPictureData *currPic = video->currPic;
    AVCFrameIO  *currInput = encvid->currInput;
    AVCMacroblock *currMB = video->currMB;
    AVCMacroblock *MB_A, *MB_B;
    AVCEncBitstream *stream = encvid->bitstream;
    AVCRateControl *rateCtrl = encvid->rateCtrl;
    uint8 *cur, *curL, *curCb, *curCr;
    uint8 *orgL, *orgCb, *orgCr, *org4;
    int CurrMbAddr = video->mbNum;
    int picPitch = currPic->pitch;
    int orgPitch = currInput->pitch;
    int x_position = (video->mb_x << 4);
    int y_position = (video->mb_y << 4);
    int offset;
    int b8, b4, blkidx;
    AVCResidualType  resType;
    int slice_type;
    int numcoeff; /* output from residual_block_cavlc */
    int cost16, cost8;

    int num_bits, start_mb_bits, start_text_bits;

    slice_type = video->slice_type;

    /* now, point to the reconstructed frame */
    offset = y_position * picPitch + x_position;
    curL = currPic->Sl + offset;
    orgL = currInput->YCbCr[0] + offset;
    offset = (offset + x_position) >> 2;
    curCb = currPic->Scb + offset;
    curCr = currPic->Scr + offset;
    orgCb = currInput->YCbCr[1] + offset;
    orgCr = currInput->YCbCr[2] + offset;

    if (orgPitch != picPitch)
    {
        offset = y_position * (orgPitch - picPitch);
        orgL += offset;
        offset >>= 2;
        orgCb += offset;
        orgCr += offset;
    }

    /******* determine MB prediction mode *******/
    if (encvid->intraSearch[CurrMbAddr])
    {
        MBIntraSearch(encvid, CurrMbAddr, curL, picPitch);
    }
    /******* This part should be determined somehow ***************/
    if (currMB->mbMode == AVC_I_PCM)
    {
        /* write down mb_type and PCM data */
        /* and copy from currInput to currPic */
        status = EncodeIntraPCM(encvid);


        return status;
    }

    /****** for intra prediction, pred is already done *******/
    /****** for I4, the recon is ready and Xfrm coefs are ready to be encoded *****/

    //RCCalculateMAD(encvid,currMB,orgL,orgPitch); // no need to re-calculate MAD for Intra
    // not used since totalSAD is used instead

    /* compute the prediction */
    /* output is video->pred_block */
    if (!currMB->mb_intra)
    {
        AVCMBMotionComp(encvid, video); /* perform prediction and residue calculation */
        /* we can do the loop here and call dct_luma */
        video->pred_pitch = picPitch;
        currMB->CBP = 0;
        cost16 = 0;
        cur = curL;
        org4 = orgL;

        for (b8 = 0; b8 < 4; b8++)
        {
            cost8 = 0;

            for (b4 = 0; b4 < 4; b4++)
            {
                blkidx = blkIdx2blkXY[b8][b4];
                video->pred_block = cur;
                numcoeff = dct_luma(encvid, blkidx, cur, org4, &cost8);
                currMB->nz_coeff[blkidx] = numcoeff;
                if (numcoeff)
                {
                    video->cbp4x4 |= (1 << blkidx);
                    currMB->CBP |= (1 << b8);
                }

                if (b4&1)
                {
                    cur += ((picPitch << 2) - 4);
                    org4 += ((orgPitch << 2) - 4);
                }
                else
                {
                    cur += 4;
                    org4 += 4;
                }
            }

            /* move the IDCT part out of dct_luma to accommodate the check
               for coeff_cost. */

            if ((currMB->CBP&(1 << b8)) && (cost8 <= _LUMA_COEFF_COST_))
            {
                cost8 = 0; // reset it

                currMB->CBP ^= (1 << b8);
                blkidx = blkIdx2blkXY[b8][0];

                currMB->nz_coeff[blkidx] = 0;
                currMB->nz_coeff[blkidx+1] = 0;
                currMB->nz_coeff[blkidx+4] = 0;
                currMB->nz_coeff[blkidx+5] = 0;
            }

            cost16 += cost8;

            if (b8&1)
            {
                cur -= 8;
                org4 -= 8;
            }
            else
            {
                cur += (8 - (picPitch << 3));
                org4 += (8 - (orgPitch << 3));
            }
        }

        /* after the whole MB, we do another check for coeff_cost */
        if ((currMB->CBP&0xF) && (cost16 <= _LUMA_MB_COEFF_COST_))
        {
            currMB->CBP = 0;  // reset it to zero
            memset(currMB->nz_coeff, 0, sizeof(uint8)*16);
        }

        // now we do IDCT
        MBInterIdct(video, curL, currMB, picPitch);

//      video->pred_block = video->pred + 256;
    }
    else    /* Intra prediction */
    {
        encvid->numIntraMB++;

        if (currMB->mbMode == AVC_I16) /* do prediction for the whole macroblock */
        {
            currMB->CBP = 0;
            /* get the prediction from encvid->pred_i16 */
            dct_luma_16x16(encvid, curL, orgL);
        }
        video->pred_block = encvid->pred_ic[currMB->intra_chroma_pred_mode];
    }

    /* chrominance */
    /* not need to do anything, the result is in encvid->pred_ic
    chroma dct must be aware that prediction block can come from either intra or inter. */

    dct_chroma(encvid, curCb, orgCb, 0);

    dct_chroma(encvid, curCr, orgCr, 1);


    /* 4.1 if there's nothing in there, video->mb_skip_run++ */
    /* 4.2 if coded, check if there is a run of skipped MB, encodes it,
            set video->QPyprev = currMB->QPy; */

    /* 5. vlc encode */

    /* check for skipped macroblock, INTER only */
    if (!currMB->mb_intra)
    {
        /* decide whether this MB (for inter MB) should be skipped if there's nothing left. */
        if (!currMB->CBP && currMB->NumMbPart == 1 && currMB->QPy == video->QPy)
        {
            if (currMB->MBPartPredMode[0][0] == AVC_Pred_L0 && currMB->ref_idx_L0[0] == 0)
            {
                MB_A = &video->mblock[video->mbAddrA];
                MB_B = &video->mblock[video->mbAddrB];

                if (!video->mbAvailA || !video->mbAvailB)
                {
                    if (currMB->mvL0[0] == 0) /* both mv components are zeros.*/
                    {
                        currMB->mbMode = AVC_SKIP;
                        video->mvd_l0[0][0][0] = 0;
                        video->mvd_l0[0][0][1] = 0;
                    }
                }
                else
                {
                    if ((MB_A->ref_idx_L0[1] == 0 && MB_A->mvL0[3] == 0) ||
                            (MB_B->ref_idx_L0[2] == 0 && MB_B->mvL0[12] == 0))
                    {
                        if (currMB->mvL0[0] == 0) /* both mv components are zeros.*/
                        {
                            currMB->mbMode = AVC_SKIP;
                            video->mvd_l0[0][0][0] = 0;
                            video->mvd_l0[0][0][1] = 0;
                        }
                    }
                    else if (video->mvd_l0[0][0][0] == 0 && video->mvd_l0[0][0][1] == 0)
                    {
                        currMB->mbMode = AVC_SKIP;
                    }
                }
            }

            if (currMB->mbMode == AVC_SKIP)
            {
                video->mb_skip_run++;

                /* set parameters */
                /* not sure whether we need the followings */
                if (slice_type == AVC_P_SLICE)
                {
                    currMB->mbMode = AVC_SKIP;
                    currMB->MbPartWidth = currMB->MbPartHeight = 16;
                    currMB->MBPartPredMode[0][0] = AVC_Pred_L0;
                    currMB->NumMbPart = 1;
                    currMB->NumSubMbPart[0] = currMB->NumSubMbPart[1] =
                                                  currMB->NumSubMbPart[2] = currMB->NumSubMbPart[3] = 1;
                    currMB->SubMbPartWidth[0] = currMB->SubMbPartWidth[1] =
                                                    currMB->SubMbPartWidth[2] = currMB->SubMbPartWidth[3] = currMB->MbPartWidth;
                    currMB->SubMbPartHeight[0] = currMB->SubMbPartHeight[1] =
                                                     currMB->SubMbPartHeight[2] = currMB->SubMbPartHeight[3] = currMB->MbPartHeight;

                }
                else if (slice_type == AVC_B_SLICE)
                {
                    currMB->mbMode = AVC_SKIP;
                    currMB->MbPartWidth = currMB->MbPartHeight = 8;
                    currMB->MBPartPredMode[0][0] = AVC_Direct;
                    currMB->NumMbPart = -1;
                }

                /* for skipped MB, always look at the first entry in RefPicList */
                currMB->RefIdx[0] = currMB->RefIdx[1] =
                                        currMB->RefIdx[2] = currMB->RefIdx[3] = video->RefPicList0[0]->RefIdx;

                /* do not return yet, need to do some copies */
            }
        }
    }
    /* non-skipped MB */


    /************* START ENTROPY CODING *************************/

    start_mb_bits = 32 + (encvid->bitstream->write_pos << 3) - encvid->bitstream->bit_left;

    /* encode mb_type, mb_pred, sub_mb_pred, CBP */
    if (slice_type != AVC_I_SLICE && slice_type != AVC_SI_SLICE && currMB->mbMode != AVC_SKIP)
    {
        //if(!pps->entropy_coding_mode_flag)  ALWAYS true
        {
            ue_v(stream, video->mb_skip_run);
            video->mb_skip_run = 0;
        }
    }

    if (currMB->mbMode != AVC_SKIP)
    {
        status = EncodeMBHeader(currMB, encvid);
        if (status != AVCENC_SUCCESS)
        {
            return status;
        }
    }

    start_text_bits = 32 + (encvid->bitstream->write_pos << 3) - encvid->bitstream->bit_left;

    /**** now decoding part *******/
    resType = AVC_Luma;

    /* DC transform for luma I16 mode */
    if (currMB->mbMode == AVC_I16)
    {
        /* vlc encode level/run */
        status = enc_residual_block(encvid, AVC_Intra16DC, encvid->numcoefdc, currMB);
        if (status != AVCENC_SUCCESS)
        {
            return status;
        }
        resType = AVC_Intra16AC;
    }

    /* VLC encoding for luma */
    for (b8 = 0; b8 < 4; b8++)
    {
        if (currMB->CBP&(1 << b8))
        {
            for (b4 = 0; b4 < 4; b4++)
            {
                /* vlc encode level/run */
                status = enc_residual_block(encvid, resType, (b8 << 2) + b4, currMB);
                if (status != AVCENC_SUCCESS)
                {
                    return status;
                }
            }
        }
    }

    /* chroma */
    if (currMB->CBP & (3 << 4)) /* chroma DC residual present */
    {
        for (b8 = 0; b8 < 2; b8++) /* for iCbCr */
        {
            /* vlc encode level/run */
            status = enc_residual_block(encvid, AVC_ChromaDC, encvid->numcoefcdc[b8] + (b8 << 3), currMB);
            if (status != AVCENC_SUCCESS)
            {
                return status;
            }
        }
    }

    if (currMB->CBP & (2 << 4))
    {
        /* AC part */
        for (b8 = 0; b8 < 2; b8++) /* for iCbCr */
        {
            for (b4 = 0; b4 < 4; b4++)  /* for each block inside Cb or Cr */
            {
                /* vlc encode level/run */
                status = enc_residual_block(encvid, AVC_ChromaAC, 16 + (b8 << 2) + b4, currMB);
                if (status != AVCENC_SUCCESS)
                {
                    return status;
                }
            }
        }
    }


    num_bits = 32 + (encvid->bitstream->write_pos << 3) - encvid->bitstream->bit_left;

    RCPostMB(video, rateCtrl, start_text_bits - start_mb_bits,
             num_bits - start_text_bits);

//  num_bits -= start_mb_bits;
//  fprintf(fdebug,"MB #%d: %d bits\n",CurrMbAddr,num_bits);
//  fclose(fdebug);
    return status;
}

/* copy the content from predBlock back to the reconstructed YUV frame */
void Copy_MB(uint8 *curL, uint8 *curCb, uint8 *curCr, uint8 *predBlock, int picPitch)
{
    int j, offset;
    uint32 *dst, *dst2, *src;

    dst = (uint32*)curL;
    src = (uint32*)predBlock;

    offset = (picPitch - 16) >> 2;

    for (j = 0; j < 16; j++)
    {
        *dst++ = *src++;
        *dst++ = *src++;
        *dst++ = *src++;
        *dst++ = *src++;

        dst += offset;
    }

    dst = (uint32*)curCb;
    dst2 = (uint32*)curCr;
    offset >>= 1;

    for (j = 0; j < 8; j++)
    {
        *dst++ = *src++;
        *dst++ = *src++;
        *dst2++ = *src++;
        *dst2++ = *src++;

        dst += offset;
        dst2 += offset;
    }
    return ;
}

/* encode mb_type, mb_pred, sub_mb_pred, CBP */
/* decide whether this MB (for inter MB) should be skipped */
AVCEnc_Status EncodeMBHeader(AVCMacroblock *currMB, AVCEncObject *encvid)
{
    AVCEnc_Status status = AVCENC_SUCCESS;
    uint mb_type;
    AVCCommonObj *video = encvid->common;
    AVCEncBitstream *stream = encvid->bitstream;

    if (currMB->CBP > 47)   /* chroma CBP is 11 */
    {
        currMB->CBP -= 16;  /* remove the 5th bit from the right */
    }

    mb_type = InterpretMBType(currMB, video->slice_type);

    status = ue_v(stream, mb_type);

    if (currMB->mbMode == AVC_P8 || currMB->mbMode == AVC_P8ref0)
    {
        status = sub_mb_pred(video, currMB, stream);
    }
    else
    {
        status = mb_pred(video, currMB, stream) ;
    }

    if (currMB->mbMode != AVC_I16)
    {
        /* decode coded_block_pattern */
        status = EncodeCBP(currMB, stream);
    }

    /* calculate currMB->mb_qp_delta = currMB->QPy - video->QPyprev */
    if (currMB->CBP > 0 || currMB->mbMode == AVC_I16)
    {
        status = se_v(stream, currMB->QPy - video->QPy);
        video->QPy = currMB->QPy; /* = (video->QPyprev + currMB->mb_qp_delta + 52)%52; */
        // no need video->QPc = currMB->QPc;
    }
    else
    {
        if (currMB->QPy != video->QPy) // current QP is not the same as previous QP
        {
            /* restore these values */
            RCRestoreQP(currMB, video, encvid);
        }
    }

    return status;
}


/* inputs are mbMode, mb_intra, i16Mode, CBP, NumMbPart, MbPartWidth, MbPartHeight */
uint InterpretMBType(AVCMacroblock *currMB, int slice_type)
{
    int CBP_chrom;
    int mb_type;// part1, part2, part3;
//  const static int MapParts2Type[2][3][3]={{{4,8,12},{10,6,14},{16,18,20}},
//  {{5,9,13},{11,7,15},{17,19,21}}};

    if (currMB->mb_intra)
    {
        if (currMB->mbMode == AVC_I4)
        {
            mb_type = 0;
        }
        else if (currMB->mbMode == AVC_I16)
        {
            CBP_chrom = (currMB->CBP & 0x30);
            if (currMB->CBP&0xF)
            {
                currMB->CBP |= 0xF;  /* either 0x0 or 0xF */
                mb_type = 13;
            }
            else
            {
                mb_type = 1;
            }
            mb_type += (CBP_chrom >> 2) + currMB->i16Mode;
        }
        else /* if(currMB->mbMode == AVC_I_PCM) */
        {
            mb_type = 25;
        }
    }
    else
    {  /* P-MB *//* note that the order of the enum AVCMBMode cannot be changed
        since we use it here. */
        mb_type = currMB->mbMode - AVC_P16;
    }

    if (slice_type == AVC_P_SLICE)
    {
        if (currMB->mb_intra)
        {
            mb_type += 5;
        }
    }
    // following codes have not been tested yet, not needed.
    /*  else if(slice_type == AVC_B_SLICE)
        {
            if(currMB->mbMode == AVC_BDirect16)
            {
                mb_type = 0;
            }
            else if(currMB->mbMode == AVC_P16)
            {
                mb_type = currMB->MBPartPredMode[0][0] + 1; // 1 or 2
            }
            else if(currMB->mbMode == AVC_P8)
            {
                mb_type = 26;
            }
            else if(currMB->mbMode == AVC_P8ref0)
            {
                mb_type = 27;
            }
            else
            {
                part1 = currMB->mbMode - AVC_P16x8;
                part2 = currMB->MBPartPredMode[0][0];
                part3 = currMB->MBPartPredMode[1][0];
                mb_type = MapParts2Type[part1][part2][part3];
            }
        }

        if(slice_type == AVC_SI_SLICE)
        {
            mb_type++;
        }
    */
    return (uint)mb_type;
}

//const static int mbPart2raster[3][4] = {{0,0,0,0},{1,1,0,0},{1,0,1,0}};

/* see subclause 7.3.5.1 */
AVCEnc_Status mb_pred(AVCCommonObj *video, AVCMacroblock *currMB, AVCEncBitstream *stream)
{
    AVCEnc_Status status = AVCENC_SUCCESS;
    int mbPartIdx;
    AVCSliceHeader *sliceHdr = video->sliceHdr;
    int max_ref_idx;
    uint code;

    if (currMB->mbMode == AVC_I4 || currMB->mbMode == AVC_I16)
    {
        if (currMB->mbMode == AVC_I4)
        {
            /* perform prediction to get the actual intra 4x4 pred mode */
            EncodeIntra4x4Mode(video, currMB, stream);
            /* output will be in currMB->i4Mode[4][4] */
        }

        /* assume already set from MBPrediction() */
        status = ue_v(stream, currMB->intra_chroma_pred_mode);
    }
    else if (currMB->MBPartPredMode[0][0] != AVC_Direct)
    {

        memset(currMB->ref_idx_L0, 0, sizeof(int16)*4);

        /* see subclause 7.4.5.1 for the range of ref_idx_lX */
        max_ref_idx = sliceHdr->num_ref_idx_l0_active_minus1;
        /*      if(video->MbaffFrameFlag && currMB->mb_field_decoding_flag)
                    max_ref_idx = 2*sliceHdr->num_ref_idx_l0_active_minus1 + 1;
        */
        /* decode ref index for L0 */
        if (sliceHdr->num_ref_idx_l0_active_minus1 > 0)
        {
            for (mbPartIdx = 0; mbPartIdx < currMB->NumMbPart; mbPartIdx++)
            {
                if (/*(sliceHdr->num_ref_idx_l0_active_minus1>0 || currMB->mb_field_decoding_flag) &&*/
                    currMB->MBPartPredMode[mbPartIdx][0] != AVC_Pred_L1)
                {
                    code = currMB->ref_idx_L0[mbPartIdx];
                    status = te_v(stream, code, max_ref_idx);
                }
            }
        }

        /* see subclause 7.4.5.1 for the range of ref_idx_lX */
        max_ref_idx = sliceHdr->num_ref_idx_l1_active_minus1;
        /*      if(video->MbaffFrameFlag && currMB->mb_field_decoding_flag)
                    max_ref_idx = 2*sliceHdr->num_ref_idx_l1_active_minus1 + 1;
        */
        /* decode ref index for L1 */
        if (sliceHdr->num_ref_idx_l1_active_minus1 > 0)
        {
            for (mbPartIdx = 0; mbPartIdx < currMB->NumMbPart; mbPartIdx++)
            {
                if (/*(sliceHdr->num_ref_idx_l1_active_minus1>0 || currMB->mb_field_decoding_flag) &&*/
                    currMB->MBPartPredMode[mbPartIdx][0] != AVC_Pred_L0)
                {
                    status = te_v(stream, currMB->ref_idx_L1[mbPartIdx], max_ref_idx);
                }
            }
        }

        /* encode mvd_l0 */
        for (mbPartIdx = 0; mbPartIdx < currMB->NumMbPart; mbPartIdx++)
        {
            if (currMB->MBPartPredMode[mbPartIdx][0] != AVC_Pred_L1)
            {
                status = se_v(stream, video->mvd_l0[mbPartIdx][0][0]);
                status = se_v(stream, video->mvd_l0[mbPartIdx][0][1]);
            }
        }
        /* encode mvd_l1 */
        for (mbPartIdx = 0; mbPartIdx < currMB->NumMbPart; mbPartIdx++)
        {
            if (currMB->MBPartPredMode[mbPartIdx][0] != AVC_Pred_L0)
            {
                status = se_v(stream, video->mvd_l1[mbPartIdx][0][0]);
                status = se_v(stream, video->mvd_l1[mbPartIdx][0][1]);
            }
        }
    }

    return status;
}

/* see subclause 7.3.5.2 */
AVCEnc_Status sub_mb_pred(AVCCommonObj *video, AVCMacroblock *currMB, AVCEncBitstream *stream)
{
    AVCEnc_Status status = AVCENC_SUCCESS;
    int mbPartIdx, subMbPartIdx;
    AVCSliceHeader *sliceHdr = video->sliceHdr;
    uint max_ref_idx;
    uint slice_type = video->slice_type;
    uint sub_mb_type[4];

    /* this should move somewhere else where we don't have to make this check */
    if (currMB->mbMode == AVC_P8ref0)
    {
        memset(currMB->ref_idx_L0, 0, sizeof(int16)*4);
    }

    /* we have to check the values to make sure they are valid  */
    /* assign values to currMB->sub_mb_type[] */
    if (slice_type == AVC_P_SLICE)
    {
        InterpretSubMBTypeP(currMB, sub_mb_type);
    }
    /* no need to check for B-slice
        else if(slice_type == AVC_B_SLICE)
        {
            InterpretSubMBTypeB(currMB,sub_mb_type);
        }*/

    for (mbPartIdx = 0; mbPartIdx < 4; mbPartIdx++)
    {
        status = ue_v(stream, sub_mb_type[mbPartIdx]);
    }

    /* see subclause 7.4.5.1 for the range of ref_idx_lX */
    max_ref_idx = sliceHdr->num_ref_idx_l0_active_minus1;
    /*  if(video->MbaffFrameFlag && currMB->mb_field_decoding_flag)
            max_ref_idx = 2*sliceHdr->num_ref_idx_l0_active_minus1 + 1; */

    for (mbPartIdx = 0; mbPartIdx < 4; mbPartIdx++)
    {
        if ((sliceHdr->num_ref_idx_l0_active_minus1 > 0 /*|| currMB->mb_field_decoding_flag*/) &&
                currMB->mbMode != AVC_P8ref0 && /*currMB->subMbMode[mbPartIdx]!=AVC_BDirect8 &&*/
                currMB->MBPartPredMode[mbPartIdx][0] != AVC_Pred_L1)
        {
            status = te_v(stream, currMB->ref_idx_L0[mbPartIdx], max_ref_idx);
        }
        /* used in deblocking */
        currMB->RefIdx[mbPartIdx] = video->RefPicList0[currMB->ref_idx_L0[mbPartIdx]]->RefIdx;
    }
    /* see subclause 7.4.5.1 for the range of ref_idx_lX */
    max_ref_idx = sliceHdr->num_ref_idx_l1_active_minus1;
    /*  if(video->MbaffFrameFlag && currMB->mb_field_decoding_flag)
            max_ref_idx = 2*sliceHdr->num_ref_idx_l1_active_minus1 + 1;*/

    if (sliceHdr->num_ref_idx_l1_active_minus1 > 0)
    {
        for (mbPartIdx = 0; mbPartIdx < 4; mbPartIdx++)
        {
            if (/*(sliceHdr->num_ref_idx_l1_active_minus1>0 || currMB->mb_field_decoding_flag) &&*/
                /*currMB->subMbMode[mbPartIdx]!=AVC_BDirect8 &&*/
                currMB->MBPartPredMode[mbPartIdx][0] != AVC_Pred_L0)
            {
                status = te_v(stream, currMB->ref_idx_L1[mbPartIdx], max_ref_idx);
            }
        }
    }

    for (mbPartIdx = 0; mbPartIdx < 4; mbPartIdx++)
    {
        if (/*currMB->subMbMode[mbPartIdx]!=AVC_BDirect8 &&*/
            currMB->MBPartPredMode[mbPartIdx][0] != AVC_Pred_L1)
        {
            for (subMbPartIdx = 0; subMbPartIdx < currMB->NumSubMbPart[mbPartIdx]; subMbPartIdx++)
            {
                status = se_v(stream, video->mvd_l0[mbPartIdx][subMbPartIdx][0]);
                status = se_v(stream, video->mvd_l0[mbPartIdx][subMbPartIdx][1]);
            }
        }
    }

    for (mbPartIdx = 0; mbPartIdx < 4; mbPartIdx++)
    {
        if (/*currMB->subMbMode[mbPartIdx]!=AVC_BDirect8 &&*/
            currMB->MBPartPredMode[mbPartIdx][0] != AVC_Pred_L0)
        {
            for (subMbPartIdx = 0; subMbPartIdx < currMB->NumSubMbPart[mbPartIdx]; subMbPartIdx++)
            {
                status = se_v(stream, video->mvd_l1[mbPartIdx][subMbPartIdx][0]);
                status = se_v(stream, video->mvd_l1[mbPartIdx][subMbPartIdx][1]);
            }
        }
    }

    return status;
}

/* input is mblock->sub_mb_type[] */
void InterpretSubMBTypeP(AVCMacroblock *mblock, uint *sub_mb_type)
{
    int i;
    /* see enum AVCMBType declaration */
    /*const static AVCSubMBMode map2subMbMode[4] = {AVC_8x8,AVC_8x4,AVC_4x8,AVC_4x4};
    const static int map2subPartWidth[4] = {8,8,4,4};
    const static int map2subPartHeight[4] = {8,4,8,4};
    const static int map2numSubPart[4] = {1,2,2,4};*/

    for (i = 0; i < 4 ; i++)
    {
        sub_mb_type[i] = mblock->subMbMode[i] - AVC_8x8;
    }

    return ;
}

void InterpretSubMBTypeB(AVCMacroblock *mblock, uint *sub_mb_type)
{
    int i;
    /* see enum AVCMBType declaration */
    /*  const static AVCSubMBMode map2subMbMode[13] = {AVC_BDirect8,AVC_8x8,AVC_8x8,
            AVC_8x8,AVC_8x4,AVC_4x8,AVC_8x4,AVC_4x8,AVC_8x4,AVC_4x8,AVC_4x4,AVC_4x4,AVC_4x4};
        const static int map2subPartWidth[13] = {4,8,8,8,8,4,8,4,8,4,4,4,4};
        const static int map2subPartHeight[13] = {4,8,8,8,4,8,4,8,4,8,4,4,4};
        const static int map2numSubPart[13] = {4,1,1,1,2,2,2,2,2,2,4,4,4};
        const static int map2predMode[13] = {3,0,1,2,0,0,1,1,2,2,0,1,2};*/

    for (i = 0; i < 4 ; i++)
    {
        if (mblock->subMbMode[i] == AVC_BDirect8)
        {
            sub_mb_type[i] = 0;
        }
        else if (mblock->subMbMode[i] == AVC_8x8)
        {
            sub_mb_type[i] = 1 + mblock->MBPartPredMode[i][0];
        }
        else if (mblock->subMbMode[i] == AVC_4x4)
        {
            sub_mb_type[i] = 10 + mblock->MBPartPredMode[i][0];
        }
        else
        {
            sub_mb_type[i] = 4 + (mblock->MBPartPredMode[i][0] << 1) + (mblock->subMbMode[i] - AVC_8x4);
        }
    }

    return ;
}

/* see subclause 8.3.1 */
AVCEnc_Status EncodeIntra4x4Mode(AVCCommonObj *video, AVCMacroblock *currMB, AVCEncBitstream *stream)
{
    int intra4x4PredModeA = 0;
    int intra4x4PredModeB, predIntra4x4PredMode;
    int component, SubBlock_indx, block_x, block_y;
    int dcOnlyPredictionFlag;
    uint    flag;
    int     rem = 0;
    int     mode;
    int bindx = 0;

    for (component = 0; component < 4; component++) /* partition index */
    {
        block_x = ((component & 1) << 1);
        block_y = ((component >> 1) << 1);

        for (SubBlock_indx = 0; SubBlock_indx < 4; SubBlock_indx++) /* sub-partition index */
        {
            dcOnlyPredictionFlag = 0;
            if (block_x > 0)
            {
                intra4x4PredModeA = currMB->i4Mode[(block_y << 2) + block_x - 1 ];
            }
            else
            {
                if (video->intraAvailA)
                {
                    if (video->mblock[video->mbAddrA].mbMode == AVC_I4)
                    {
                        intra4x4PredModeA = video->mblock[video->mbAddrA].i4Mode[(block_y << 2) + 3];
                    }
                    else
                    {
                        intra4x4PredModeA = AVC_I4_DC;
                    }
                }
                else
                {
                    dcOnlyPredictionFlag = 1;
                }
            }

            if (block_y > 0)
            {
                intra4x4PredModeB = currMB->i4Mode[((block_y-1) << 2) + block_x];
            }
            else
            {
                if (video->intraAvailB)
                {
                    if (video->mblock[video->mbAddrB].mbMode == AVC_I4)
                    {
                        intra4x4PredModeB = video->mblock[video->mbAddrB].i4Mode[(3 << 2) + block_x];
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

            if (dcOnlyPredictionFlag)
            {
                intra4x4PredModeA = intra4x4PredModeB = AVC_I4_DC;
            }

            predIntra4x4PredMode = AVC_MIN(intra4x4PredModeA, intra4x4PredModeB);

            flag = 0;
            mode = currMB->i4Mode[(block_y<<2)+block_x];

            if (mode == (AVCIntra4x4PredMode)predIntra4x4PredMode)
            {
                flag = 1;
            }
            else if (mode < predIntra4x4PredMode)
            {
                rem = mode;
            }
            else
            {
                rem = mode - 1;
            }

            BitstreamWrite1Bit(stream, flag);

            if (!flag)
            {
                BitstreamWriteBits(stream, 3, rem);
            }

            bindx++;
            block_y += (SubBlock_indx & 1) ;
            block_x += (1 - 2 * (SubBlock_indx & 1)) ;
        }
    }

    return AVCENC_SUCCESS;
}



