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
/* Note for optimization: syntax decoding or operations related to B_SLICE should be
commented out by macro definition or function pointers. */

#include <string.h>

#include "avcdec_lib.h"
#include "avcdec_bitstream.h"

const static int mbPart2raster[3][4] = {{0, 0, 0, 0}, {1, 1, 0, 0}, {1, 0, 1, 0}};
/* decode_frame_slice() */
/* decode_one_slice() */
AVCDec_Status DecodeSlice(AVCDecObject *decvid)
{
    AVCDec_Status status;
    AVCCommonObj *video = decvid->common;
    AVCSliceHeader *sliceHdr = video->sliceHdr;
    AVCMacroblock *currMB ;
    AVCDecBitstream *stream = decvid->bitstream;
    uint slice_group_id;
    uint CurrMbAddr, moreDataFlag;

    /* set the first mb in slice */
    CurrMbAddr = sliceHdr->first_mb_in_slice;
    slice_group_id = video->MbToSliceGroupMap[CurrMbAddr];

    if ((CurrMbAddr && (CurrMbAddr != (uint)(video->mbNum + 1))) && video->currSeqParams->constrained_set1_flag == 1)
    {
        ConcealSlice(decvid, video->mbNum, CurrMbAddr);
    }

    moreDataFlag = 1;
    video->mb_skip_run = -1;


    /* while loop , see subclause 7.3.4 */
    do
    {
        if (CurrMbAddr >= video->PicSizeInMbs)
        {
            return AVCDEC_FAIL;
        }

        currMB = video->currMB = &(video->mblock[CurrMbAddr]);
        video->mbNum = CurrMbAddr;
        currMB->slice_id = video->slice_id;  //  slice

        /* we can remove this check if we don't support Mbaff. */
        /* we can wrap below into an initMB() function which will also
        do necessary reset of macroblock related parameters. */

        video->mb_x = CurrMbAddr % video->PicWidthInMbs;
        video->mb_y = CurrMbAddr / video->PicWidthInMbs;

        /* check the availability of neighboring macroblocks */
        InitNeighborAvailability(video, CurrMbAddr);

        /* read_macroblock and decode_one_macroblock() */
        status = DecodeMB(decvid);
        if (status != AVCDEC_SUCCESS)
        {
            return status;
        }
#ifdef MB_BASED_DEBLOCK
        if (video->currPicParams->num_slice_groups_minus1 == 0)
        {
            MBInLoopDeblock(video); /* MB-based deblocking */
        }
        else    /* this mode cannot be used if the number of slice group is not one. */
        {
            return AVCDEC_FAIL;
        }
#endif
        video->numMBs--;

        moreDataFlag = more_rbsp_data(stream);


        /* go to next MB */
        while (++CurrMbAddr < video->PicSizeInMbs && video->MbToSliceGroupMap[CurrMbAddr] != (int)slice_group_id)
        {
        }

    }
    while ((moreDataFlag && video->numMBs > 0) || video->mb_skip_run > 0); /* even if no more data, but last few MBs are skipped */

    if (video->numMBs == 0)
    {
        video->newPic = TRUE;
        video->mbNum = 0;  // _Conceal
        return AVCDEC_PICTURE_READY;
    }

    return AVCDEC_SUCCESS;
}

/* read MB mode and motion vectors */
/* perform Intra/Inter prediction and residue */
/* update video->mb_skip_run */
AVCDec_Status DecodeMB(AVCDecObject *decvid)
{
    AVCDec_Status status;
    AVCCommonObj *video = decvid->common;
    AVCDecBitstream *stream = decvid->bitstream;
    AVCMacroblock *currMB = video->currMB;
    uint mb_type;
    int slice_type = video->slice_type;
    int temp;

    currMB->QPy = video->QPy;
    currMB->QPc = video->QPc;

    if (slice_type == AVC_P_SLICE)
    {
        if (video->mb_skip_run < 0)
        {
            ue_v(stream, (uint *)&(video->mb_skip_run));
        }

        if (video->mb_skip_run == 0)
        {
            /* this will not handle the case where the slice ends with a mb_skip_run == 0 and no following MB data  */
            ue_v(stream, &mb_type);
            if (mb_type > 30)
            {
                return AVCDEC_FAIL;
            }
            InterpretMBModeP(currMB, mb_type);
            video->mb_skip_run = -1;
        }
        else
        {
            /* see subclause 7.4.4 for more details on how
            mb_field_decoding_flag is derived in case of skipped MB */

            currMB->mb_intra = FALSE;

            currMB->mbMode = AVC_SKIP;
            currMB->MbPartWidth = currMB->MbPartHeight = 16;
            currMB->NumMbPart = 1;
            currMB->NumSubMbPart[0] = currMB->NumSubMbPart[1] =
                                          currMB->NumSubMbPart[2] = currMB->NumSubMbPart[3] = 1; //
            currMB->SubMbPartWidth[0] = currMB->SubMbPartWidth[1] =
                                            currMB->SubMbPartWidth[2] = currMB->SubMbPartWidth[3] = currMB->MbPartWidth;
            currMB->SubMbPartHeight[0] = currMB->SubMbPartHeight[1] =
                                             currMB->SubMbPartHeight[2] = currMB->SubMbPartHeight[3] = currMB->MbPartHeight;

            memset(currMB->nz_coeff, 0, sizeof(uint8)*NUM_BLKS_IN_MB);

            currMB->CBP = 0;
            video->cbp4x4 = 0;
            /* for skipped MB, always look at the first entry in RefPicList */
            currMB->RefIdx[0] = currMB->RefIdx[1] =
                                    currMB->RefIdx[2] = currMB->RefIdx[3] = video->RefPicList0[0]->RefIdx;
            InterMBPrediction(video);
            video->mb_skip_run--;
            return AVCDEC_SUCCESS;
        }

    }
    else
    {
        /* Then decode mode and MV */
        ue_v(stream, &mb_type);
        if (mb_type > 25)
        {
            return AVCDEC_FAIL;
        }
        InterpretMBModeI(currMB, mb_type);
    }


    if (currMB->mbMode != AVC_I_PCM)
    {

        if (currMB->mbMode == AVC_P8 || currMB->mbMode == AVC_P8ref0)
        {
            status = sub_mb_pred(video, currMB, stream);
        }
        else
        {
            status = mb_pred(video, currMB, stream) ;
        }

        if (status != AVCDEC_SUCCESS)
        {
            return status;
        }

        if (currMB->mbMode != AVC_I16)
        {
            /* decode coded_block_pattern */
            status = DecodeCBP(currMB, stream);
            if (status != AVCDEC_SUCCESS)
            {
                return status;
            }
        }

        if (currMB->CBP > 0 || currMB->mbMode == AVC_I16)
        {
            se_v(stream, &temp);
            if (temp)
            {
                temp += (video->QPy + 52);
                currMB->QPy = video->QPy = temp - 52 * (temp * 79 >> 12);
                if (currMB->QPy > 51 || currMB->QPy < 0)
                {
                    video->QPy = AVC_CLIP3(0, 51, video->QPy);
//                  return AVCDEC_FAIL;
                }
                video->QPy_div_6 = (video->QPy * 43) >> 8;
                video->QPy_mod_6 = video->QPy - 6 * video->QPy_div_6;
                currMB->QPc = video->QPc = mapQPi2QPc[AVC_CLIP3(0, 51, video->QPy + video->currPicParams->chroma_qp_index_offset)];
                video->QPc_div_6 = (video->QPc * 43) >> 8;
                video->QPc_mod_6 = video->QPc - 6 * video->QPc_div_6;
            }
        }
        /* decode residue and inverse transform */
        status = residual(decvid, currMB);
        if (status != AVCDEC_SUCCESS)
        {
            return status;
        }
    }
    else
    {
        if (stream->bitcnt & 7)
        {
            BitstreamByteAlign(stream);
        }
        /* decode pcm_byte[i] */
        DecodeIntraPCM(video, stream);

        currMB->QPy = 0;  /* necessary for deblocking */ // _OPTIMIZE
        currMB->QPc = mapQPi2QPc[AVC_CLIP3(0, 51, video->currPicParams->chroma_qp_index_offset)];

        /* default values, don't know if really needed */
        currMB->CBP = 0x3F;
        video->cbp4x4 = 0xFFFF;
        currMB->mb_intra = TRUE;
        memset(currMB->nz_coeff, 16, sizeof(uint8)*NUM_BLKS_IN_MB);
        return AVCDEC_SUCCESS;
    }


    /* do Intra/Inter prediction, together with the residue compensation */
    /* This part should be common between the skip and no-skip */
    if (currMB->mbMode == AVC_I4 || currMB->mbMode == AVC_I16)
    {
        IntraMBPrediction(video);
    }
    else
    {
        InterMBPrediction(video);
    }



    return AVCDEC_SUCCESS;
}

/* see subclause 7.3.5.1 */
AVCDec_Status mb_pred(AVCCommonObj *video, AVCMacroblock *currMB, AVCDecBitstream *stream)
{
    int mbPartIdx;
    AVCSliceHeader *sliceHdr = video->sliceHdr;
    uint max_ref_idx;
    const int *temp_0;
    int16 *temp_1;
    uint code;

    if (currMB->mbMode == AVC_I4 || currMB->mbMode == AVC_I16)
    {

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


        if (currMB->mbMode == AVC_I4)
        {
            /* perform prediction to get the actual intra 4x4 pred mode */
            DecodeIntra4x4Mode(video, currMB, stream);
            /* output will be in currMB->i4Mode[4][4] */
        }

        ue_v(stream, &code);

        if (code > 3)
        {
            return AVCDEC_FAIL; /* out of range */
        }
        currMB->intra_chroma_pred_mode = (AVCIntraChromaPredMode)code;
    }
    else
    {

        memset(currMB->ref_idx_L0, 0, sizeof(int16)*4);

        /* see subclause 7.4.5.1 for the range of ref_idx_lX */
//      max_ref_idx = sliceHdr->num_ref_idx_l0_active_minus1;
        max_ref_idx = video->refList0Size - 1;

        /* decode ref index for L0 */
        if (sliceHdr->num_ref_idx_l0_active_minus1 > 0)
        {
            for (mbPartIdx = 0; mbPartIdx < currMB->NumMbPart; mbPartIdx++)
            {
                te_v(stream, &code, max_ref_idx);
                if (code > (uint)max_ref_idx)
                {
                    return AVCDEC_FAIL;
                }
                currMB->ref_idx_L0[mbPartIdx] = code;
            }
        }

        /* populate ref_idx_L0 */
        temp_0 = &mbPart2raster[currMB->mbMode-AVC_P16][0];
        temp_1 = &currMB->ref_idx_L0[3];

        *temp_1-- = currMB->ref_idx_L0[*temp_0++];
        *temp_1-- = currMB->ref_idx_L0[*temp_0++];
        *temp_1-- = currMB->ref_idx_L0[*temp_0++];
        *temp_1-- = currMB->ref_idx_L0[*temp_0++];

        /* Global reference index, these values are used in deblock */
        currMB->RefIdx[0] = video->RefPicList0[currMB->ref_idx_L0[0]]->RefIdx;
        currMB->RefIdx[1] = video->RefPicList0[currMB->ref_idx_L0[1]]->RefIdx;
        currMB->RefIdx[2] = video->RefPicList0[currMB->ref_idx_L0[2]]->RefIdx;
        currMB->RefIdx[3] = video->RefPicList0[currMB->ref_idx_L0[3]]->RefIdx;

        /* see subclause 7.4.5.1 for the range of ref_idx_lX */
        max_ref_idx = sliceHdr->num_ref_idx_l1_active_minus1;
        /* decode mvd_l0 */
        for (mbPartIdx = 0; mbPartIdx < currMB->NumMbPart; mbPartIdx++)
        {
            se_v(stream, &(video->mvd_l0[mbPartIdx][0][0]));
            se_v(stream, &(video->mvd_l0[mbPartIdx][0][1]));
        }
    }

    return AVCDEC_SUCCESS;
}

/* see subclause 7.3.5.2 */
AVCDec_Status sub_mb_pred(AVCCommonObj *video, AVCMacroblock *currMB, AVCDecBitstream *stream)
{
    int mbPartIdx, subMbPartIdx;
    AVCSliceHeader *sliceHdr = video->sliceHdr;
    uint max_ref_idx;
    uint sub_mb_type[4];
    uint code;

    memset(currMB->ref_idx_L0, 0, sizeof(int16)*4);

    for (mbPartIdx = 0; mbPartIdx < 4; mbPartIdx++)
    {
        ue_v(stream, &(sub_mb_type[mbPartIdx]));
        if (sub_mb_type[mbPartIdx] > 3)
        {
            return AVCDEC_FAIL;
        }

    }
    /* we have to check the values to make sure they are valid  */
    /* assign values to currMB->sub_mb_type[], currMB->MBPartPredMode[][x] */

    InterpretSubMBModeP(currMB, sub_mb_type);


    /* see subclause 7.4.5.1 for the range of ref_idx_lX */
//      max_ref_idx = sliceHdr->num_ref_idx_l0_active_minus1;
    max_ref_idx = video->refList0Size - 1;

    if (sliceHdr->num_ref_idx_l0_active_minus1 > 0 && currMB->mbMode != AVC_P8ref0)
    {
        for (mbPartIdx = 0; mbPartIdx < 4; mbPartIdx++)
        {
            te_v(stream, (uint*)&code, max_ref_idx);
            if (code > max_ref_idx)
            {
                return AVCDEC_FAIL;
            }
            currMB->ref_idx_L0[mbPartIdx] = code;
        }
    }
    /* see subclause 7.4.5.1 for the range of ref_idx_lX */

    max_ref_idx = sliceHdr->num_ref_idx_l1_active_minus1;
    /*  if(video->MbaffFrameFlag && currMB->mb_field_decoding_flag)
            max_ref_idx = 2*sliceHdr->num_ref_idx_l1_active_minus1 + 1;*/
    for (mbPartIdx = 0; mbPartIdx < 4; mbPartIdx++)
    {
        for (subMbPartIdx = 0; subMbPartIdx < currMB->NumSubMbPart[mbPartIdx]; subMbPartIdx++)
        {
            se_v(stream, &(video->mvd_l0[mbPartIdx][subMbPartIdx][0]));
            se_v(stream, &(video->mvd_l0[mbPartIdx][subMbPartIdx][1]));
        }
        /* used in deblocking */
        currMB->RefIdx[mbPartIdx] = video->RefPicList0[currMB->ref_idx_L0[mbPartIdx]]->RefIdx;
    }
    return AVCDEC_SUCCESS;
}

void InterpretMBModeI(AVCMacroblock *mblock, uint mb_type)
{
    mblock->NumMbPart = 1;

    mblock->mb_intra = TRUE;

    if (mb_type == 0) /* I_4x4 */
    {
        mblock->mbMode = AVC_I4;
    }
    else if (mb_type < 25) /* I_PCM */
    {
        mblock->mbMode = AVC_I16;
        mblock->i16Mode = (AVCIntra16x16PredMode)((mb_type - 1) & 0x3);
        if (mb_type > 12)
        {
            mblock->CBP = (((mb_type - 13) >> 2) << 4) + 0x0F;
        }
        else
        {
            mblock->CBP = ((mb_type - 1) >> 2) << 4;
        }
    }
    else
    {
        mblock->mbMode = AVC_I_PCM;
    }

    return ;
}

void InterpretMBModeP(AVCMacroblock *mblock, uint mb_type)
{
    const static int map2PartWidth[5] = {16, 16, 8, 8, 8};
    const static int map2PartHeight[5] = {16, 8, 16, 8, 8};
    const static int map2NumPart[5] = {1, 2, 2, 4, 4};
    const static AVCMBMode map2mbMode[5] = {AVC_P16, AVC_P16x8, AVC_P8x16, AVC_P8, AVC_P8ref0};

    mblock->mb_intra = FALSE;
    if (mb_type < 5)
    {
        mblock->mbMode = map2mbMode[mb_type];
        mblock->MbPartWidth = map2PartWidth[mb_type];
        mblock->MbPartHeight = map2PartHeight[mb_type];
        mblock->NumMbPart = map2NumPart[mb_type];
        mblock->NumSubMbPart[0] = mblock->NumSubMbPart[1] =
                                      mblock->NumSubMbPart[2] = mblock->NumSubMbPart[3] = 1;
        mblock->SubMbPartWidth[0] = mblock->SubMbPartWidth[1] =
                                        mblock->SubMbPartWidth[2] = mblock->SubMbPartWidth[3] = mblock->MbPartWidth;
        mblock->SubMbPartHeight[0] = mblock->SubMbPartHeight[1] =
                                         mblock->SubMbPartHeight[2] = mblock->SubMbPartHeight[3] = mblock->MbPartHeight;
    }
    else
    {
        InterpretMBModeI(mblock, mb_type - 5);
        /* set MV and Ref_Idx codes of Intra blocks in P-slices  */
        memset(mblock->mvL0, 0, sizeof(int32)*16);
        mblock->ref_idx_L0[0] = mblock->ref_idx_L0[1] = mblock->ref_idx_L0[2] = mblock->ref_idx_L0[3] = -1;
    }
    return ;
}

void InterpretMBModeB(AVCMacroblock *mblock, uint mb_type)
{
    const static int map2PartWidth[23] = {8, 16, 16, 16, 16, 8, 16, 8, 16, 8,
                                          16, 8, 16, 8, 16, 8, 16, 8, 16, 8, 16, 8, 8
                                         };
    const static int map2PartHeight[23] = {8, 16, 16, 16, 8, 16, 8, 16, 8,
                                           16, 8, 16, 8, 16, 8, 16, 8, 16, 8, 16, 8, 16, 8
                                          };
    /* see enum AVCMBType declaration */
    const static AVCMBMode map2mbMode[23] = {AVC_BDirect16, AVC_P16, AVC_P16, AVC_P16,
                                            AVC_P16x8, AVC_P8x16, AVC_P16x8, AVC_P8x16, AVC_P16x8, AVC_P8x16,
                                            AVC_P16x8, AVC_P8x16, AVC_P16x8, AVC_P8x16, AVC_P16x8, AVC_P8x16,
                                            AVC_P16x8, AVC_P8x16, AVC_P16x8, AVC_P8x16, AVC_P16x8, AVC_P8x16, AVC_P8
                                            };
    const static int map2PredMode1[23] = {3, 0, 1, 2, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 2, 2, 2, 2, 2, 2, -1};
    const static int map2PredMode2[23] = { -1, -1, -1, -1, 0, 0, 1, 1, 1, 1, 0, 0, 2, 2, 2, 2, 0, 0, 1, 1, 2, 2, -1};
    const static int map2NumPart[23] = { -1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 4};

    mblock->mb_intra = FALSE;

    if (mb_type < 23)
    {
        mblock->mbMode = map2mbMode[mb_type];
        mblock->NumMbPart = map2NumPart[mb_type];
        mblock->MBPartPredMode[0][0] = (AVCPredMode)map2PredMode1[mb_type];
        if (mblock->NumMbPart > 1)
        {
            mblock->MBPartPredMode[1][0] = (AVCPredMode)map2PredMode2[mb_type];
        }
        mblock->MbPartWidth = map2PartWidth[mb_type];
        mblock->MbPartHeight = map2PartHeight[mb_type];
    }
    else
    {
        InterpretMBModeI(mblock, mb_type - 23);
    }

    return ;
}

void InterpretMBModeSI(AVCMacroblock *mblock, uint mb_type)
{
    mblock->mb_intra = TRUE;

    if (mb_type == 0)
    {
        mblock->mbMode = AVC_SI4;
        /* other values are N/A */
    }
    else
    {
        InterpretMBModeI(mblock, mb_type - 1);
    }
    return ;
}

/* input is mblock->sub_mb_type[] */
void InterpretSubMBModeP(AVCMacroblock *mblock, uint *sub_mb_type)
{
    int i,  sub_type;
    /* see enum AVCMBType declaration */
//  const static AVCSubMBMode map2subMbMode[4] = {AVC_8x8,AVC_8x4,AVC_4x8,AVC_4x4};
    const static int map2subPartWidth[4] = {8, 8, 4, 4};
    const static int map2subPartHeight[4] = {8, 4, 8, 4};
    const static int map2numSubPart[4] = {1, 2, 2, 4};

    for (i = 0; i < 4 ; i++)
    {
        sub_type = (int) sub_mb_type[i];
        //  mblock->subMbMode[i] = map2subMbMode[sub_type];
        mblock->NumSubMbPart[i] = map2numSubPart[sub_type];
        mblock->SubMbPartWidth[i] = map2subPartWidth[sub_type];
        mblock->SubMbPartHeight[i] = map2subPartHeight[sub_type];
    }

    return ;
}

void InterpretSubMBModeB(AVCMacroblock *mblock, uint *sub_mb_type)
{
    int i, j, sub_type;
    /* see enum AVCMBType declaration */
    const static AVCSubMBMode map2subMbMode[13] = {AVC_BDirect8, AVC_8x8, AVC_8x8,
            AVC_8x8, AVC_8x4, AVC_4x8, AVC_8x4, AVC_4x8, AVC_8x4, AVC_4x8, AVC_4x4, AVC_4x4, AVC_4x4
                                                  };
    const static int map2subPartWidth[13] = {4, 8, 8, 8, 8, 4, 8, 4, 8, 4, 4, 4, 4};
    const static int map2subPartHeight[13] = {4, 8, 8, 8, 4, 8, 4, 8, 4, 8, 4, 4, 4};
    const static int map2numSubPart[13] = {1, 1, 1, 2, 2, 2, 2, 2, 2, 4, 4, 4};
    const static int map2predMode[13] = {3, 0, 1, 2, 0, 0, 1, 1, 2, 2, 0, 1, 2};

    for (i = 0; i < 4 ; i++)
    {
        sub_type = (int) sub_mb_type[i];
        mblock->subMbMode[i] = map2subMbMode[sub_type];
        mblock->NumSubMbPart[i] = map2numSubPart[sub_type];
        mblock->SubMbPartWidth[i] = map2subPartWidth[sub_type];
        mblock->SubMbPartHeight[i] = map2subPartHeight[sub_type];
        for (j = 0; j < 4; j++)
        {
            mblock->MBPartPredMode[i][j] = (AVCPredMode)map2predMode[sub_type];
        }
    }

    return ;
}

/* see subclause 8.3.1 */
AVCDec_Status DecodeIntra4x4Mode(AVCCommonObj *video, AVCMacroblock *currMB, AVCDecBitstream *stream)
{
    int intra4x4PredModeA = 0, intra4x4PredModeB = 0, predIntra4x4PredMode = 0;
    int component, SubBlock_indx, block_x, block_y;
    int dcOnlyPredictionFlag;
    uint    prev_intra4x4_pred_mode_flag[16];
    int     rem_intra4x4_pred_mode[16];
    int bindx = 0;

    for (component = 0; component < 4; component++) /* partition index */
    {
        block_x = ((component & 1) << 1);
        block_y = ((component >> 1) << 1);

        for (SubBlock_indx = 0; SubBlock_indx < 4; SubBlock_indx++) /* sub-partition index */
        {
            BitstreamRead1Bit(stream, &(prev_intra4x4_pred_mode_flag[bindx]));

            if (!prev_intra4x4_pred_mode_flag[bindx])
            {
                BitstreamReadBits(stream, 3, (uint*)&(rem_intra4x4_pred_mode[bindx]));
            }

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
            if (prev_intra4x4_pred_mode_flag[bindx])
            {
                currMB->i4Mode[(block_y<<2)+block_x] = (AVCIntra4x4PredMode)predIntra4x4PredMode;
            }
            else
            {
                if (rem_intra4x4_pred_mode[bindx] < predIntra4x4PredMode)
                {
                    currMB->i4Mode[(block_y<<2)+block_x] = (AVCIntra4x4PredMode)rem_intra4x4_pred_mode[bindx];
                }
                else
                {
                    currMB->i4Mode[(block_y<<2)+block_x] = (AVCIntra4x4PredMode)(rem_intra4x4_pred_mode[bindx] + 1);
                }
            }
            bindx++;
            block_y += (SubBlock_indx & 1) ;
            block_x += (1 - 2 * (SubBlock_indx & 1)) ;
        }
    }
    return AVCDEC_SUCCESS;
}
AVCDec_Status ConcealSlice(AVCDecObject *decvid, int mbnum_start, int mbnum_end)
{
    AVCCommonObj *video = decvid->common;
    AVCMacroblock *currMB ;

    int CurrMbAddr;

    if (video->RefPicList0[0] == NULL)
    {
        return AVCDEC_FAIL;
    }

    for (CurrMbAddr = mbnum_start; CurrMbAddr < mbnum_end; CurrMbAddr++)
    {
        currMB = video->currMB = &(video->mblock[CurrMbAddr]);
        video->mbNum = CurrMbAddr;
        currMB->slice_id = video->slice_id++;  //  slice

        /* we can remove this check if we don't support Mbaff. */
        /* we can wrap below into an initMB() function which will also
        do necessary reset of macroblock related parameters. */

        video->mb_x = CurrMbAddr % video->PicWidthInMbs;
        video->mb_y = CurrMbAddr / video->PicWidthInMbs;

        /* check the availability of neighboring macroblocks */
        InitNeighborAvailability(video, CurrMbAddr);

        currMB->mb_intra = FALSE;

        currMB->mbMode = AVC_SKIP;
        currMB->MbPartWidth = currMB->MbPartHeight = 16;

        currMB->NumMbPart = 1;
        currMB->NumSubMbPart[0] = currMB->NumSubMbPart[1] =
                                      currMB->NumSubMbPart[2] = currMB->NumSubMbPart[3] = 1;
        currMB->SubMbPartWidth[0] = currMB->SubMbPartWidth[1] =
                                        currMB->SubMbPartWidth[2] = currMB->SubMbPartWidth[3] = currMB->MbPartWidth;
        currMB->SubMbPartHeight[0] = currMB->SubMbPartHeight[1] =
                                         currMB->SubMbPartHeight[2] = currMB->SubMbPartHeight[3] = currMB->MbPartHeight;
        currMB->QPy = 26;
        currMB->QPc = 26;
        memset(currMB->nz_coeff, 0, sizeof(uint8)*NUM_BLKS_IN_MB);

        currMB->CBP = 0;
        video->cbp4x4 = 0;
        /* for skipped MB, always look at the first entry in RefPicList */
        currMB->RefIdx[0] = currMB->RefIdx[1] =
                                currMB->RefIdx[2] = currMB->RefIdx[3] = video->RefPicList0[0]->RefIdx;
        InterMBPrediction(video);

        video->numMBs--;

    }

    return AVCDEC_SUCCESS;
}

