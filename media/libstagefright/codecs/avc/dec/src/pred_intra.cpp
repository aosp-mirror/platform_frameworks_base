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
#include "avcdec_lib.h"

#define CLIP_COMP  *comp++ = (uint8)(((uint)temp>0xFF)? 0xFF&(~(temp>>31)): temp)
#define CLIP_RESULT(x)      if((uint)x > 0xFF){ \
                 x = 0xFF & (~(x>>31));}


/* We should combine the Intra4x4 functions with residual decoding and compensation  */
AVCStatus IntraMBPrediction(AVCCommonObj *video)
{
    int component, SubBlock_indx, temp;
    AVCStatus status;
    AVCMacroblock *currMB = video->currMB;
    AVCPictureData *currPic = video->currPic;
    uint8 *curL, *curCb, *curCr;
    uint8 *comp;
    int block_x, block_y, offset;
    int16 *dataBlock = video->block;
    uint8 *predCb, *predCr;
#ifdef USE_PRED_BLOCK
    uint8 *pred;
#endif
    int pitch = currPic->pitch;
    uint32 cbp4x4 = video->cbp4x4;

    offset = (video->mb_y << 4) * pitch + (video->mb_x << 4);
    curL = currPic->Sl + offset;

#ifdef USE_PRED_BLOCK
    video->pred_block = video->pred + 84;  /* point to separate prediction memory */
    pred = video->pred_block;
    video->pred_pitch = 20;
#else
    video->pred_block = curL;   /* point directly to the frame buffer */
    video->pred_pitch = pitch;
#endif

    if (currMB->mbMode == AVC_I4)
    {
        /* luminance first */
        block_x = block_y = 0;
        for (component = 0; component < 4; component++)
        {
            block_x = ((component & 1) << 1);
            block_y = ((component >> 1) << 1);
            comp = curL;// + (block_x<<2) + (block_y<<2)*currPic->pitch;

            for (SubBlock_indx = 0; SubBlock_indx < 4; SubBlock_indx++)
            {
                status = Intra_4x4(video, block_x, block_y, comp);
                if (status != AVC_SUCCESS)
                {
                    return status;
                }
                /* transform following the 4x4 prediction, can't be SIMD
                with other blocks. */
#ifdef USE_PRED_BLOCK
                if (cbp4x4&(1 << ((block_y << 2) + block_x)))
                {
                    itrans(dataBlock, pred, pred, 20);
                }
#else
                if (cbp4x4&(1 << ((block_y << 2) + block_x)))
                {
                    itrans(dataBlock, comp, comp, pitch);
                }
#endif
                temp = SubBlock_indx & 1;
                if (temp)
                {
                    block_y++;
                    block_x--;
                    dataBlock += 60;
#ifdef USE_PRED_BLOCK
                    pred += 76;
#else
                    comp += ((pitch << 2) - 4);
#endif
                }
                else
                {
                    block_x++;
                    dataBlock += 4;
#ifdef USE_PRED_BLOCK
                    pred += 4;
#else
                    comp += 4;
#endif
                }
            }
            if (component&1)
            {
#ifdef USE_PRED_BLOCK
                pred -= 8;
#else
                curL += (pitch << 3) - 8;
#endif
                dataBlock -= 8;
            }
            else
            {
#ifdef USE_PRED_BLOCK
                pred -= 152;
#else
                curL += 8;
#endif
                dataBlock -= 120;
            }
        }
        cbp4x4 >>= 16;
    }
    else   /* AVC_I16 */
    {
#ifdef MB_BASED_DEBLOCK
        video->pintra_pred_top = video->intra_pred_top + (video->mb_x << 4);
        video->pintra_pred_left = video->intra_pred_left + 1;
        video->intra_pred_topleft = video->intra_pred_left[0];
        pitch = 1;
#else
        video->pintra_pred_top = curL - pitch;
        video->pintra_pred_left = curL - 1;
        if (video->mb_y)
        {
            video->intra_pred_topleft = *(curL - pitch - 1);
        }
#endif
        switch (currMB->i16Mode)
        {
            case AVC_I16_Vertical:      /* Intra_16x16_Vertical */
                /* check availability of top */
                if (video->intraAvailB)
                {
                    Intra_16x16_Vertical(video);
                }
                else
                {
                    return AVC_FAIL;
                }
                break;
            case AVC_I16_Horizontal:        /* Intra_16x16_Horizontal */
                /* check availability of left */
                if (video->intraAvailA)
                {
                    Intra_16x16_Horizontal(video, pitch);
                }
                else
                {
                    return AVC_FAIL;
                }
                break;
            case AVC_I16_DC:        /* Intra_16x16_DC */
                Intra_16x16_DC(video, pitch);
                break;
            case AVC_I16_Plane:     /* Intra_16x16_Plane */
                if (video->intraAvailA && video->intraAvailB && video->intraAvailD)
                {
                    Intra_16x16_Plane(video, pitch);
                }
                else
                {
                    return AVC_FAIL;
                }
                break;
            default:
                break;
        }

        pitch = currPic->pitch;

        /* transform */
        /* can go in raster scan order now */
        /* can be done in SIMD,  */
        for (block_y = 4; block_y > 0; block_y--)
        {
            for (block_x = 4; block_x > 0; block_x--)
            {
#ifdef USE_PRED_BLOCK
                if (cbp4x4&1)
                {
                    itrans(dataBlock, pred, pred, 20);
                }
#else
                if (cbp4x4&1)
                {
                    itrans(dataBlock, curL, curL, pitch);
                }
#endif
                cbp4x4 >>= 1;
                dataBlock += 4;
#ifdef USE_PRED_BLOCK
                pred += 4;
#else
                curL += 4;
#endif
            }
            dataBlock += 48;
#ifdef USE_PRED_BLOCK
            pred += 64;
#else
            curL += ((pitch << 2) - 16);
#endif
        }
    }

    offset = (offset >> 2) + (video->mb_x << 2); //((video->mb_y << 3)* pitch + (video->mb_x << 3));
    curCb = currPic->Scb + offset;
    curCr = currPic->Scr + offset;

#ifdef MB_BASED_DEBLOCK
    video->pintra_pred_top_cb = video->intra_pred_top_cb + (video->mb_x << 3);
    video->pintra_pred_left_cb = video->intra_pred_left_cb + 1;
    video->intra_pred_topleft_cb = video->intra_pred_left_cb[0];
    video->pintra_pred_top_cr = video->intra_pred_top_cr + (video->mb_x << 3);
    video->pintra_pred_left_cr = video->intra_pred_left_cr + 1;
    video->intra_pred_topleft_cr = video->intra_pred_left_cr[0];
    pitch  = 1;
#else
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
#endif

#ifdef USE_PRED_BLOCK
    predCb = video->pred + 452;
    predCr = predCb + 144;
    video->pred_pitch = 12;
#else
    predCb = curCb;
    predCr = curCr;
    video->pred_pitch = currPic->pitch >> 1;
#endif
    /* chrominance */
    switch (currMB->intra_chroma_pred_mode)
    {
        case AVC_IC_DC:     /* Intra_Chroma_DC */
            Intra_Chroma_DC(video, pitch, predCb, predCr);
            break;
        case AVC_IC_Horizontal:     /* Intra_Chroma_Horizontal */
            if (video->intraAvailA)
            {
                /* check availability of left */
                Intra_Chroma_Horizontal(video, pitch, predCb, predCr);
            }
            else
            {
                return AVC_FAIL;
            }
            break;
        case AVC_IC_Vertical:       /* Intra_Chroma_Vertical */
            if (video->intraAvailB)
            {
                /* check availability of top */
                Intra_Chroma_Vertical(video, predCb, predCr);
            }
            else
            {
                return AVC_FAIL;
            }
            break;
        case AVC_IC_Plane:      /* Intra_Chroma_Plane */
            if (video->intraAvailA && video->intraAvailB && video->intraAvailD)
            {
                /* check availability of top and left */
                Intra_Chroma_Plane(video, pitch, predCb, predCr);
            }
            else
            {
                return AVC_FAIL;
            }
            break;
        default:
            break;
    }

    /* transform, done in raster scan manner */
    pitch = currPic->pitch >> 1;

    for (block_y = 2; block_y > 0; block_y--)
    {
        for (block_x = 2; block_x > 0; block_x--)
        {
#ifdef USE_PRED_BLOCK
            if (cbp4x4&1)
            {
                ictrans(dataBlock, predCb, predCb, 12);
            }
#else
            if (cbp4x4&1)
            {
                ictrans(dataBlock, curCb, curCb, pitch);
            }
#endif
            cbp4x4 >>= 1;
            dataBlock += 4;
#ifdef USE_PRED_BLOCK
            predCb += 4;
#else
            curCb += 4;
#endif
        }
        for (block_x = 2; block_x > 0; block_x--)
        {
#ifdef USE_PRED_BLOCK
            if (cbp4x4&1)
            {
                ictrans(dataBlock, predCr, predCr, 12);
            }
#else
            if (cbp4x4&1)
            {
                ictrans(dataBlock, curCr, curCr, pitch);
            }
#endif
            cbp4x4 >>= 1;
            dataBlock += 4;
#ifdef USE_PRED_BLOCK
            predCr += 4;
#else
            curCr += 4;
#endif
        }
        dataBlock += 48;
#ifdef USE_PRED_BLOCK
        predCb += 40;
        predCr += 40;
#else
        curCb += ((pitch << 2) - 8);
        curCr += ((pitch << 2) - 8);
#endif
    }

#ifdef MB_BASED_DEBLOCK
    SaveNeighborForIntraPred(video, offset);
#endif
    return AVC_SUCCESS;
}

#ifdef MB_BASED_DEBLOCK
void SaveNeighborForIntraPred(AVCCommonObj *video, int offset)
{
    AVCPictureData *currPic = video->currPic;
    int pitch;
    uint8 *pred, *predCb, *predCr;
    uint8 *tmp_ptr, tmp_byte;
    uint32 tmp_word;
    int mb_x = video->mb_x;

    /* save the value for intra prediction  */
#ifdef USE_PRED_BLOCK
    pitch = 20;
    pred = video->pred + 384; /* bottom line for Y */
    predCb = pred + 152;    /* bottom line for Cb */
    predCr = predCb + 144;  /* bottom line for Cr */
#else
    pitch = currPic->pitch;
    tmp_word = offset + (pitch << 2) - (pitch >> 1);
    predCb = currPic->Scb + tmp_word;/* bottom line for Cb */
    predCr = currPic->Scr + tmp_word;/* bottom line for Cr */

    offset = (offset << 2) - (mb_x << 4);
    pred = currPic->Sl + offset + (pitch << 4) - pitch;/* bottom line for Y */

#endif

    video->intra_pred_topleft = video->intra_pred_top[(mb_x<<4)+15];
    video->intra_pred_topleft_cb = video->intra_pred_top_cb[(mb_x<<3)+7];
    video->intra_pred_topleft_cr = video->intra_pred_top_cr[(mb_x<<3)+7];

    /* then copy to video->intra_pred_top, intra_pred_top_cb, intra_pred_top_cr */
    /*memcpy(video->intra_pred_top + (mb_x<<4), pred, 16);
    memcpy(video->intra_pred_top_cb + (mb_x<<3), predCb, 8);
    memcpy(video->intra_pred_top_cr + (mb_x<<3), predCr, 8);*/
    tmp_ptr = video->intra_pred_top + (mb_x << 4);
    *((uint32*)tmp_ptr) = *((uint32*)pred);
    *((uint32*)(tmp_ptr + 4)) = *((uint32*)(pred + 4));
    *((uint32*)(tmp_ptr + 8)) = *((uint32*)(pred + 8));
    *((uint32*)(tmp_ptr + 12)) = *((uint32*)(pred + 12));
    tmp_ptr = video->intra_pred_top_cb + (mb_x << 3);
    *((uint32*)tmp_ptr) = *((uint32*)predCb);
    *((uint32*)(tmp_ptr + 4)) = *((uint32*)(predCb + 4));
    tmp_ptr = video->intra_pred_top_cr + (mb_x << 3);
    *((uint32*)tmp_ptr) = *((uint32*)predCr);
    *((uint32*)(tmp_ptr + 4)) = *((uint32*)(predCr + 4));


    /* now save last column */
#ifdef USE_PRED_BLOCK
    pred = video->pred + 99;    /* last column*/
#else
    pred -= ((pitch << 4) - pitch - 15);    /* last column */
#endif
    tmp_ptr = video->intra_pred_left;
    tmp_word = video->intra_pred_topleft;
    tmp_byte = *(pred);
    tmp_word |= (tmp_byte << 8);
    tmp_byte = *(pred += pitch);
    tmp_word |= (tmp_byte << 16);
    tmp_byte = *(pred += pitch);
    tmp_word |= (tmp_byte << 24);
    *((uint32*)tmp_ptr) = tmp_word;
    tmp_word = *(pred += pitch);
    tmp_byte = *(pred += pitch);
    tmp_word |= (tmp_byte << 8);
    tmp_byte = *(pred += pitch);
    tmp_word |= (tmp_byte << 16);
    tmp_byte = *(pred += pitch);
    tmp_word |= (tmp_byte << 24);
    *((uint32*)(tmp_ptr += 4)) = tmp_word;
    tmp_word = *(pred += pitch);
    tmp_byte = *(pred += pitch);
    tmp_word |= (tmp_byte << 8);
    tmp_byte = *(pred += pitch);
    tmp_word |= (tmp_byte << 16);
    tmp_byte = *(pred += pitch);
    tmp_word |= (tmp_byte << 24);
    *((uint32*)(tmp_ptr += 4)) = tmp_word;
    tmp_word = *(pred += pitch);
    tmp_byte = *(pred += pitch);
    tmp_word |= (tmp_byte << 8);
    tmp_byte = *(pred += pitch);
    tmp_word |= (tmp_byte << 16);
    tmp_byte = *(pred += pitch);
    tmp_word |= (tmp_byte << 24);
    *((uint32*)(tmp_ptr += 4)) = tmp_word;
    *(tmp_ptr += 4) = *(pred += pitch);

    /* now for Cb */
#ifdef USE_PRED_BLOCK
    predCb = video->pred + 459;
    pitch = 12;
#else
    pitch >>= 1;
    predCb -= (7 * pitch - 7);
#endif
    tmp_ptr = video->intra_pred_left_cb;
    tmp_word = video->intra_pred_topleft_cb;
    tmp_byte = *(predCb);
    tmp_word |= (tmp_byte << 8);
    tmp_byte = *(predCb += pitch);
    tmp_word |= (tmp_byte << 16);
    tmp_byte = *(predCb += pitch);
    tmp_word |= (tmp_byte << 24);
    *((uint32*)tmp_ptr) = tmp_word;
    tmp_word = *(predCb += pitch);
    tmp_byte = *(predCb += pitch);
    tmp_word |= (tmp_byte << 8);
    tmp_byte = *(predCb += pitch);
    tmp_word |= (tmp_byte << 16);
    tmp_byte = *(predCb += pitch);
    tmp_word |= (tmp_byte << 24);
    *((uint32*)(tmp_ptr += 4)) = tmp_word;
    *(tmp_ptr += 4) = *(predCb += pitch);

    /* now for Cr */
#ifdef USE_PRED_BLOCK
    predCr = video->pred + 603;
#else
    predCr -= (7 * pitch - 7);
#endif
    tmp_ptr = video->intra_pred_left_cr;
    tmp_word = video->intra_pred_topleft_cr;
    tmp_byte = *(predCr);
    tmp_word |= (tmp_byte << 8);
    tmp_byte = *(predCr += pitch);
    tmp_word |= (tmp_byte << 16);
    tmp_byte = *(predCr += pitch);
    tmp_word |= (tmp_byte << 24);
    *((uint32*)tmp_ptr) = tmp_word;
    tmp_word = *(predCr += pitch);
    tmp_byte = *(predCr += pitch);
    tmp_word |= (tmp_byte << 8);
    tmp_byte = *(predCr += pitch);
    tmp_word |= (tmp_byte << 16);
    tmp_byte = *(predCr += pitch);
    tmp_word |= (tmp_byte << 24);
    *((uint32*)(tmp_ptr += 4)) = tmp_word;
    *(tmp_ptr += 4) = *(predCr += pitch);

    return ;
}
#endif /* MB_BASED_DEBLOCK */

AVCStatus Intra_4x4(AVCCommonObj *video, int block_x, int block_y, uint8 *comp)
{
    AVCMacroblock *currMB = video->currMB;
    int block_offset;
    AVCNeighborAvailability availability;
    int pitch = video->currPic->pitch;

#ifdef USE_PRED_BLOCK
    block_offset = (block_y * 80) + (block_x << 2);
#else
    block_offset = (block_y << 2) * pitch + (block_x << 2);
#endif

#ifdef MB_BASED_DEBLOCK
    /* boundary blocks use video->pred_intra_top, pred_intra_left, pred_intra_topleft */
    if (!block_x)
    {
        video->pintra_pred_left = video->intra_pred_left + 1 + (block_y << 2);
        pitch = 1;
    }
    else
    {
        video->pintra_pred_left = video->pred_block + block_offset - 1;
        pitch = video->pred_pitch;
    }

    if (!block_y)
    {
        video->pintra_pred_top = video->intra_pred_top + (block_x << 2) + (video->mb_x << 4);
    }
    else
    {
        video->pintra_pred_top = video->pred_block + block_offset - video->pred_pitch;
    }

    if (!block_x)
    {
        video->intra_pred_topleft = video->intra_pred_left[block_y<<2];
    }
    else if (!block_y)
    {
        video->intra_pred_topleft = video->intra_pred_top[(video->mb_x<<4)+(block_x<<2)-1];
    }
    else
    {
        video->intra_pred_topleft = video->pred_block[block_offset - video->pred_pitch - 1];
    }

#else
    /* normal case */
    video->pintra_pred_top = comp - pitch;
    video->pintra_pred_left = comp - 1;
    if (video->mb_y || block_y)
    {
        video->intra_pred_topleft = *(comp - pitch - 1);
    }
#endif

    switch (currMB->i4Mode[(block_y << 2) + block_x])
    {
        case AVC_I4_Vertical:       /* Intra_4x4_Vertical */
            if (block_y > 0 || video->intraAvailB)/* to prevent out-of-bound access*/
            {
                Intra_4x4_Vertical(video,  block_offset);
            }
            else
            {
                return AVC_FAIL;
            }
            break;

        case AVC_I4_Horizontal:     /* Intra_4x4_Horizontal */
            if (block_x || video->intraAvailA)  /* to prevent out-of-bound access */
            {
                Intra_4x4_Horizontal(video, pitch, block_offset);
            }
            else
            {
                return AVC_FAIL;
            }
            break;

        case AVC_I4_DC:     /* Intra_4x4_DC */
            availability.left = TRUE;
            availability.top = TRUE;
            if (!block_y)
            { /* check availability up */
                availability.top = video->intraAvailB ;
            }
            if (!block_x)
            { /* check availability left */
                availability.left = video->intraAvailA ;
            }
            Intra_4x4_DC(video, pitch, block_offset, &availability);
            break;

        case AVC_I4_Diagonal_Down_Left:     /* Intra_4x4_Diagonal_Down_Left */
            /* lookup table will be more appropriate for this case  */
            if (block_y == 0 && !video->intraAvailB)
            {
                return AVC_FAIL;
            }

            availability.top_right = BlkTopRight[(block_y<<2) + block_x];

            if (availability.top_right == 2)
            {
                availability.top_right = video->intraAvailB;
            }
            else if (availability.top_right == 3)
            {
                availability.top_right = video->intraAvailC;
            }

            Intra_4x4_Down_Left(video, block_offset, &availability);
            break;

        case AVC_I4_Diagonal_Down_Right:        /* Intra_4x4_Diagonal_Down_Right */
            if ((block_y && block_x)  /* to prevent out-of-bound access */
                    || (block_y && video->intraAvailA)
                    || (block_x && video->intraAvailB)
                    || (video->intraAvailA && video->intraAvailD && video->intraAvailB))
            {
                Intra_4x4_Diagonal_Down_Right(video, pitch, block_offset);
            }
            else
            {
                return AVC_FAIL;
            }
            break;

        case AVC_I4_Vertical_Right:     /* Intra_4x4_Vertical_Right */
            if ((block_y && block_x)  /* to prevent out-of-bound access */
                    || (block_y && video->intraAvailA)
                    || (block_x && video->intraAvailB)
                    || (video->intraAvailA && video->intraAvailD && video->intraAvailB))
            {
                Intra_4x4_Diagonal_Vertical_Right(video, pitch, block_offset);
            }
            else
            {
                return AVC_FAIL;
            }
            break;

        case AVC_I4_Horizontal_Down:        /* Intra_4x4_Horizontal_Down */
            if ((block_y && block_x)  /* to prevent out-of-bound access */
                    || (block_y && video->intraAvailA)
                    || (block_x && video->intraAvailB)
                    || (video->intraAvailA && video->intraAvailD && video->intraAvailB))
            {
                Intra_4x4_Diagonal_Horizontal_Down(video, pitch, block_offset);
            }
            else
            {
                return AVC_FAIL;
            }
            break;

        case AVC_I4_Vertical_Left:      /* Intra_4x4_Vertical_Left */
            /* lookup table may be more appropriate for this case  */
            if (block_y == 0 && !video->intraAvailB)
            {
                return AVC_FAIL;
            }

            availability.top_right = BlkTopRight[(block_y<<2) + block_x];

            if (availability.top_right == 2)
            {
                availability.top_right = video->intraAvailB;
            }
            else if (availability.top_right == 3)
            {
                availability.top_right = video->intraAvailC;
            }

            Intra_4x4_Vertical_Left(video,  block_offset, &availability);
            break;

        case AVC_I4_Horizontal_Up:      /* Intra_4x4_Horizontal_Up */
            if (block_x || video->intraAvailA)
            {
                Intra_4x4_Horizontal_Up(video, pitch, block_offset);
            }
            else
            {
                return AVC_FAIL;
            }
            break;


        default:

            break;
    }

    return AVC_SUCCESS;
}


/* =============================== BEGIN 4x4
MODES======================================*/
void Intra_4x4_Vertical(AVCCommonObj *video,  int block_offset)
{
    uint8 *comp_ref = video->pintra_pred_top;
    uint32 temp;
    uint8 *pred = video->pred_block + block_offset;
    int pred_pitch = video->pred_pitch;

    /*P = (int) *comp_ref++;
    Q = (int) *comp_ref++;
    R = (int) *comp_ref++;
    S = (int) *comp_ref++;
    temp = S|(R<<8)|(Q<<16)|(P<<24);*/
    temp = *((uint32*)comp_ref);

    *((uint32*)pred) =  temp; /* write 4 at a time */
    pred += pred_pitch;
    *((uint32*)pred) =  temp;
    pred += pred_pitch;
    *((uint32*)pred) =  temp;
    pred += pred_pitch;
    *((uint32*)pred) =  temp;

    return ;
}

void Intra_4x4_Horizontal(AVCCommonObj *video, int pitch, int block_offset)
{
    uint8   *comp_ref = video->pintra_pred_left;
    uint32 temp;
    int P;
    uint8 *pred = video->pred_block + block_offset;
    int pred_pitch = video->pred_pitch;

    P = *comp_ref;
    temp = P | (P << 8);
    temp = temp | (temp << 16);
    *((uint32*)pred) = temp;
    pred += pred_pitch;
    comp_ref += pitch;
    P = *comp_ref;
    temp = P | (P << 8);
    temp = temp | (temp << 16);
    *((uint32*)pred) = temp;
    pred += pred_pitch;
    comp_ref += pitch;
    P = *comp_ref;
    temp = P | (P << 8);
    temp = temp | (temp << 16);
    *((uint32*)pred) = temp;
    pred += pred_pitch;
    comp_ref += pitch;
    P = *comp_ref;
    temp = P | (P << 8);
    temp = temp | (temp << 16);
    *((uint32*)pred) = temp;

    return ;
}

void Intra_4x4_DC(AVCCommonObj *video, int pitch, int block_offset,
                  AVCNeighborAvailability *availability)
{
    uint8   *comp_ref = video->pintra_pred_left;
    uint32  temp;
    int DC;
    uint8 *pred = video->pred_block + block_offset;
    int pred_pitch = video->pred_pitch;

    if (availability->left)
    {
        DC = *comp_ref;
        comp_ref += pitch;
        DC += *comp_ref;
        comp_ref += pitch;
        DC += *comp_ref;
        comp_ref += pitch;
        DC += *comp_ref;
        comp_ref = video->pintra_pred_top;

        if (availability->top)
        {
            DC = (comp_ref[0] + comp_ref[1] + comp_ref[2] + comp_ref[3] + DC + 4) >> 3;
        }
        else
        {
            DC = (DC + 2) >> 2;

        }
    }
    else if (availability->top)
    {
        comp_ref = video->pintra_pred_top;
        DC = (comp_ref[0] + comp_ref[1] + comp_ref[2] + comp_ref[3] + 2) >> 2;

    }
    else
    {
        DC = 128;
    }

    temp = DC | (DC << 8);
    temp = temp | (temp << 16);
    *((uint32*)pred) = temp;
    pred += pred_pitch;
    *((uint32*)pred) = temp;
    pred += pred_pitch;
    *((uint32*)pred) = temp;
    pred += pred_pitch;
    *((uint32*)pred) = temp;

    return ;
}

void Intra_4x4_Down_Left(AVCCommonObj *video, int block_offset,
                         AVCNeighborAvailability *availability)
{
    uint8   *comp_refx = video->pintra_pred_top;
    uint32 temp;
    int r0, r1, r2, r3, r4, r5, r6, r7;
    uint8 *pred = video->pred_block + block_offset;
    int pred_pitch = video->pred_pitch;

    r0 = *comp_refx++;
    r1 = *comp_refx++;
    r2 = *comp_refx++;
    r3 = *comp_refx++;
    if (availability->top_right)
    {
        r4 = *comp_refx++;
        r5 = *comp_refx++;
        r6 = *comp_refx++;
        r7 = *comp_refx++;
    }
    else
    {
        r4 = r3;
        r5 = r3;
        r6 = r3;
        r7 = r3;
    }

    r0 += (r1 << 1);
    r0 += r2;
    r0 += 2;
    r0 >>= 2;
    r1 += (r2 << 1);
    r1 += r3;
    r1 += 2;
    r1 >>= 2;
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
    pred += pred_pitch;

    temp = (temp >> 8) | (r4 << 24);
    *((uint32*)pred) = temp;
    pred += pred_pitch;

    temp = (temp >> 8) | (r5 << 24);
    *((uint32*)pred) = temp;
    pred += pred_pitch;

    temp = (temp >> 8) | (r6 << 24);
    *((uint32*)pred) = temp;

    return ;
}

void Intra_4x4_Diagonal_Down_Right(AVCCommonObj *video, int pitch, int
                                   block_offset)
{
    uint8 *comp_refx = video->pintra_pred_top;
    uint8 *comp_refy = video->pintra_pred_left;
    uint32 temp;
    int P_x, Q_x, R_x, P_y, Q_y, R_y, D;
    int x0, x1, x2;
    uint8 *pred = video->pred_block + block_offset;
    int pred_pitch = video->pred_pitch;

    temp = *((uint32*)comp_refx); /* read 4 bytes */
    x0 = temp & 0xFF;
    x1 = (temp >> 8) & 0xFF;
    x2 = (temp >> 16) & 0xFF;

    Q_x = (x0 + 2 * x1 + x2 + 2) >> 2;
    R_x = (x1 + 2 * x2 + (temp >> 24) + 2) >> 2;

    x2 = video->intra_pred_topleft; /* re-use x2 instead of y0 */
    P_x = (x2 + 2 * x0 + x1 + 2) >> 2;

    x1 = *comp_refy;
    comp_refy += pitch; /* re-use x1 instead of y1 */
    D = (x0 + 2 * x2 + x1 + 2) >> 2;

    x0 = *comp_refy;
    comp_refy += pitch; /* re-use x0 instead of y2 */
    P_y = (x2 + 2 * x1 + x0 + 2) >> 2;

    x2 = *comp_refy;
    comp_refy += pitch; /* re-use x2 instead of y3 */
    Q_y = (x1 + 2 * x0 + x2 + 2) >> 2;

    x1 = *comp_refy;                    /* re-use x1 instead of y4 */
    R_y = (x0 + 2 * x2 + x1 + 2) >> 2;

    /* we can pack these  */
    temp =  D | (P_x << 8);   //[D   P_x Q_x R_x]
    //[P_y D   P_x Q_x]
    temp |= (Q_x << 16); //[Q_y P_y D   P_x]
    temp |= (R_x << 24);  //[R_y Q_y P_y D  ]
    *((uint32*)pred) = temp;
    pred += pred_pitch;

    temp =  P_y | (D << 8);
    temp |= (P_x << 16);
    temp |= (Q_x << 24);
    *((uint32*)pred) = temp;
    pred += pred_pitch;

    temp =  Q_y | (P_y << 8);
    temp |= (D << 16);
    temp |= (P_x << 24);
    *((uint32*)pred) = temp;
    pred += pred_pitch;

    temp = R_y | (Q_y << 8);
    temp |= (P_y << 16);
    temp |= (D << 24);
    *((uint32*)pred) = temp;

    return ;
}

void    Intra_4x4_Diagonal_Vertical_Right(AVCCommonObj *video, int pitch, int block_offset)
{
    uint8   *comp_refx = video->pintra_pred_top;
    uint8   *comp_refy = video->pintra_pred_left;
    uint32 temp;
    int P0, Q0, R0, S0, P1, Q1, R1, P2, Q2, D;
    int x0, x1, x2;
    uint8 *pred = video->pred_block + block_offset;
    int pred_pitch = video->pred_pitch;

    x0 = *comp_refx++;
    x1 = *comp_refx++;
    Q0 = x0 + x1 + 1;

    x2 = *comp_refx++;
    R0 = x1 + x2 + 1;

    x1 = *comp_refx++; /* reuse x1 instead of x3 */
    S0 = x2 + x1 + 1;

    x1 = video->intra_pred_topleft; /* reuse x1 instead of y0 */
    P0 = x1 + x0 + 1;

    x2 = *comp_refy;
    comp_refy += pitch; /* reuse x2 instead of y1 */
    D = (x2 + 2 * x1 + x0 + 2) >> 2;

    P1 = (P0 + Q0) >> 2;
    Q1 = (Q0 + R0) >> 2;
    R1 = (R0 + S0) >> 2;

    P0 >>= 1;
    Q0 >>= 1;
    R0 >>= 1;
    S0 >>= 1;

    x0 = *comp_refy;
    comp_refy += pitch; /* reuse x0 instead of y2 */
    P2 = (x1 + 2 * x2 + x0 + 2) >> 2;
    x1 = *comp_refy;
    comp_refy += pitch; /* reuse x1 instead of y3 */
    Q2 = (x2 + 2 * x0 + x1 + 2) >> 2;

    temp =  P0 | (Q0 << 8);  //[P0 Q0 R0 S0]
    //[D  P1 Q1 R1]
    temp |= (R0 << 16); //[P2 P0 Q0 R0]
    temp |= (S0 << 24); //[Q2 D  P1 Q1]
    *((uint32*)pred) =  temp;
    pred += pred_pitch;

    temp =  D | (P1 << 8);
    temp |= (Q1 << 16);
    temp |= (R1 << 24);
    *((uint32*)pred) =  temp;
    pred += pred_pitch;

    temp = P2 | (P0 << 8);
    temp |= (Q0 << 16);
    temp |= (R0 << 24);
    *((uint32*)pred) =  temp;
    pred += pred_pitch;

    temp = Q2 | (D << 8);
    temp |= (P1 << 16);
    temp |= (Q1 << 24);
    *((uint32*)pred) =  temp;

    return ;
}

void Intra_4x4_Diagonal_Horizontal_Down(AVCCommonObj *video, int pitch,
                                        int block_offset)
{
    uint8   *comp_refx = video->pintra_pred_top;
    uint8   *comp_refy = video->pintra_pred_left;
    uint32 temp;
    int P0, Q0, R0, S0, P1, Q1, R1, P2, Q2, D;
    int x0, x1, x2;
    uint8 *pred = video->pred_block + block_offset;
    int pred_pitch = video->pred_pitch;

    x0 = *comp_refx++;
    x1 = *comp_refx++;
    x2 = *comp_refx++;
    Q2 = (x0 + 2 * x1 + x2 + 2) >> 2;

    x2 = video->intra_pred_topleft; /* reuse x2 instead of y0 */
    P2 = (x2 + 2 * x0 + x1 + 2) >> 2;

    x1 = *comp_refy;
    comp_refy += pitch; /* reuse x1 instead of y1 */
    D = (x1 + 2 * x2 + x0 + 2) >> 2;
    P0 = x2 + x1 + 1;

    x0 = *comp_refy;
    comp_refy += pitch; /* reuse x0 instead of y2 */
    Q0 = x1 + x0 + 1;

    x1 = *comp_refy;
    comp_refy += pitch; /* reuse x1 instead of y3 */
    R0 = x0 + x1 + 1;

    x2 = *comp_refy;    /* reuse x2 instead of y4 */
    S0 = x1 + x2 + 1;

    P1 = (P0 + Q0) >> 2;
    Q1 = (Q0 + R0) >> 2;
    R1 = (R0 + S0) >> 2;

    P0 >>= 1;
    Q0 >>= 1;
    R0 >>= 1;
    S0 >>= 1;


    /* we can pack these  */
    temp = P0 | (D << 8);   //[P0 D  P2 Q2]
    //[Q0 P1 P0 D ]
    temp |= (P2 << 16);  //[R0 Q1 Q0 P1]
    temp |= (Q2 << 24); //[S0 R1 R0 Q1]
    *((uint32*)pred) = temp;
    pred += pred_pitch;

    temp = Q0 | (P1 << 8);
    temp |= (P0 << 16);
    temp |= (D << 24);
    *((uint32*)pred) = temp;
    pred += pred_pitch;

    temp = R0 | (Q1 << 8);
    temp |= (Q0 << 16);
    temp |= (P1 << 24);
    *((uint32*)pred) = temp;
    pred += pred_pitch;

    temp = S0 | (R1 << 8);
    temp |= (R0 << 16);
    temp |= (Q1 << 24);
    *((uint32*)pred) = temp;

    return ;
}

void Intra_4x4_Vertical_Left(AVCCommonObj *video, int block_offset, AVCNeighborAvailability *availability)
{
    uint8   *comp_refx = video->pintra_pred_top;
    uint32 temp1, temp2;
    int x0, x1, x2, x3, x4, x5, x6;
    uint8 *pred = video->pred_block + block_offset;
    int pred_pitch = video->pred_pitch;

    x0 = *comp_refx++;
    x1 = *comp_refx++;
    x2 = *comp_refx++;
    x3 = *comp_refx++;
    if (availability->top_right)
    {
        x4 = *comp_refx++;
        x5 = *comp_refx++;
        x6 = *comp_refx++;
    }
    else
    {
        x4 = x3;
        x5 = x3;
        x6 = x3;
    }

    x0 += x1 + 1;
    x1 += x2 + 1;
    x2 += x3 + 1;
    x3 += x4 + 1;
    x4 += x5 + 1;
    x5 += x6 + 1;

    temp1 = (x0 >> 1);
    temp1 |= ((x1 >> 1) << 8);
    temp1 |= ((x2 >> 1) << 16);
    temp1 |= ((x3 >> 1) << 24);

    *((uint32*)pred) = temp1;
    pred += pred_pitch;

    temp2 = ((x0 + x1) >> 2);
    temp2 |= (((x1 + x2) >> 2) << 8);
    temp2 |= (((x2 + x3) >> 2) << 16);
    temp2 |= (((x3 + x4) >> 2) << 24);

    *((uint32*)pred) = temp2;
    pred += pred_pitch;

    temp1 = (temp1 >> 8) | ((x4 >> 1) << 24);   /* rotate out old value */
    *((uint32*)pred) = temp1;
    pred += pred_pitch;

    temp2 = (temp2 >> 8) | (((x4 + x5) >> 2) << 24); /* rotate out old value */
    *((uint32*)pred) = temp2;
    pred += pred_pitch;

    return ;
}

void Intra_4x4_Horizontal_Up(AVCCommonObj *video, int pitch, int block_offset)
{
    uint8   *comp_refy = video->pintra_pred_left;
    uint32 temp;
    int Q0, R0, Q1, D0, D1, P0, P1;
    int y0, y1, y2, y3;
    uint8 *pred = video->pred_block + block_offset;
    int pred_pitch = video->pred_pitch;

    y0 = *comp_refy;
    comp_refy += pitch;
    y1 = *comp_refy;
    comp_refy += pitch;
    y2 = *comp_refy;
    comp_refy += pitch;
    y3 = *comp_refy;

    Q0 = (y1 + y2 + 1) >> 1;
    Q1 = (y1 + (y2 << 1) + y3 + 2) >> 2;
    P0 = ((y0 + y1 + 1) >> 1);
    P1 = ((y0 + (y1 << 1) + y2 + 2) >> 2);

    temp = P0 | (P1 << 8);      // [P0 P1 Q0 Q1]
    temp |= (Q0 << 16);     // [Q0 Q1 R0 DO]
    temp |= (Q1 << 24);     // [R0 D0 D1 D1]
    *((uint32*)pred) = temp;      // [D1 D1 D1 D1]
    pred += pred_pitch;

    D0 = (y2 + 3 * y3 + 2) >> 2;
    R0 = (y2 + y3 + 1) >> 1;

    temp = Q0 | (Q1 << 8);
    temp |= (R0 << 16);
    temp |= (D0 << 24);
    *((uint32*)pred) = temp;
    pred += pred_pitch;

    D1 = y3;

    temp = R0 | (D0 << 8);
    temp |= (D1 << 16);
    temp |= (D1 << 24);
    *((uint32*)pred) = temp;
    pred += pred_pitch;

    temp = D1 | (D1 << 8);
    temp |= (temp << 16);
    *((uint32*)pred) = temp;

    return ;
}
/* =============================== END 4x4 MODES======================================*/
void  Intra_16x16_Vertical(AVCCommonObj *video)
{
    int i;
    uint32 temp1, temp2, temp3, temp4;
    uint8   *comp_ref = video->pintra_pred_top;
    uint8 *pred = video->pred_block;
    int pred_pitch = video->pred_pitch;

    temp1 = *((uint32*)comp_ref);
    comp_ref += 4;

    temp2 = *((uint32*)comp_ref);
    comp_ref += 4;

    temp3 = *((uint32*)comp_ref);
    comp_ref += 4;

    temp4 = *((uint32*)comp_ref);
    comp_ref += 4;

    i = 16;
    while (i > 0)
    {
        *((uint32*)pred) = temp1;
        *((uint32*)(pred + 4)) = temp2;
        *((uint32*)(pred + 8)) = temp3;
        *((uint32*)(pred + 12)) = temp4;
        pred += pred_pitch;
        i--;
    }

    return ;
}

void Intra_16x16_Horizontal(AVCCommonObj *video, int pitch)
{
    int i;
    uint32 temp;
    uint8 *comp_ref = video->pintra_pred_left;
    uint8 *pred = video->pred_block;
    int pred_pitch = video->pred_pitch;

    for (i = 0; i < 16; i++)
    {
        temp = *comp_ref;
        temp |= (temp << 8);
        temp |= (temp << 16);
        *((uint32*)pred) = temp;
        *((uint32*)(pred + 4)) = temp;
        *((uint32*)(pred + 8)) = temp;
        *((uint32*)(pred + 12)) = temp;
        pred += pred_pitch;
        comp_ref += pitch;
    }
}


void  Intra_16x16_DC(AVCCommonObj *video, int pitch)
{
    int i;
    uint32 temp, temp2;
    uint8 *comp_ref_x = video->pintra_pred_top;
    uint8 *comp_ref_y = video->pintra_pred_left;
    int sum = 0;
    uint8 *pred = video->pred_block;
    int pred_pitch = video->pred_pitch;

    if (video->intraAvailB)
    {
        temp = *((uint32*)comp_ref_x);
        comp_ref_x += 4;
        temp2 = (temp >> 8) & 0xFF00FF;
        temp &= 0xFF00FF;
        temp += temp2;
        sum = temp + (temp >> 16);
        temp = *((uint32*)comp_ref_x);
        comp_ref_x += 4;
        temp2 = (temp >> 8) & 0xFF00FF;
        temp &= 0xFF00FF;
        temp += temp2;
        sum += temp + (temp >> 16);
        temp = *((uint32*)comp_ref_x);
        comp_ref_x += 4;
        temp2 = (temp >> 8) & 0xFF00FF;
        temp &= 0xFF00FF;
        temp += temp2;
        sum += temp + (temp >> 16);
        temp = *((uint32*)comp_ref_x);
        comp_ref_x += 4;
        temp2 = (temp >> 8) & 0xFF00FF;
        temp &= 0xFF00FF;
        temp += temp2;
        sum += temp + (temp >> 16);
        sum &= 0xFFFF;

        if (video->intraAvailA)
        {
            for (i = 0; i < 16; i++)
            {
                sum += (*comp_ref_y);
                comp_ref_y += pitch;
            }
            sum = (sum + 16) >> 5;
        }
        else
        {
            sum = (sum + 8) >> 4;
        }
    }
    else if (video->intraAvailA)
    {
        for (i = 0; i < 16; i++)
        {
            sum += *comp_ref_y;
            comp_ref_y += pitch;
        }
        sum = (sum + 8) >> 4;
    }
    else
    {
        sum = 128;
    }

    temp = sum | (sum << 8);
    temp |= (temp << 16);

    for (i = 0; i < 16; i++)
    {
        *((uint32*)pred) = temp;
        *((uint32*)(pred + 4)) = temp;
        *((uint32*)(pred + 8)) = temp;
        *((uint32*)(pred + 12)) = temp;
        pred += pred_pitch;
    }

}

void Intra_16x16_Plane(AVCCommonObj *video, int pitch)
{
    int i, a_16, b, c, factor_c;
    uint8 *comp_ref_x = video->pintra_pred_top;
    uint8 *comp_ref_y = video->pintra_pred_left;
    uint8 *comp_ref_x0, *comp_ref_x1, *comp_ref_y0, *comp_ref_y1;
    int H = 0, V = 0 , tmp;
    uint8 *pred = video->pred_block;
    uint32 temp;
    uint8 byte1, byte2, byte3;
    int value;
    int pred_pitch = video->pred_pitch;

    comp_ref_x0 = comp_ref_x + 8;
    comp_ref_x1 = comp_ref_x + 6;
    comp_ref_y0 = comp_ref_y + (pitch << 3);
    comp_ref_y1 = comp_ref_y + 6 * pitch;

    for (i = 1; i < 8; i++)
    {
        H += i * (*comp_ref_x0++ - *comp_ref_x1--);
        V += i * (*comp_ref_y0 - *comp_ref_y1);
        comp_ref_y0 += pitch;
        comp_ref_y1 -= pitch;
    }

    H += i * (*comp_ref_x0++ - video->intra_pred_topleft);
    V += i * (*comp_ref_y0 - *comp_ref_y1);


    a_16 = ((*(comp_ref_x + 15) + *(comp_ref_y + 15 * pitch)) << 4) + 16;;
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
        byte1 = value;
        value = factor_c >> 5;
        factor_c += b;
        CLIP_RESULT(value)
        byte2 = value;
        value = factor_c >> 5;
        factor_c += b;
        CLIP_RESULT(value)
        byte3 = value;
        value = factor_c >> 5;
        factor_c += b;
        CLIP_RESULT(value)
        temp = byte1 | (byte2 << 8);
        temp |= (byte3 << 16);
        temp |= (value << 24);
        *((uint32*)pred) = temp;

        value = factor_c >> 5;
        factor_c += b;
        CLIP_RESULT(value)
        byte1 = value;
        value = factor_c >> 5;
        factor_c += b;
        CLIP_RESULT(value)
        byte2 = value;
        value = factor_c >> 5;
        factor_c += b;
        CLIP_RESULT(value)
        byte3 = value;
        value = factor_c >> 5;
        factor_c += b;
        CLIP_RESULT(value)
        temp = byte1 | (byte2 << 8);
        temp |= (byte3 << 16);
        temp |= (value << 24);
        *((uint32*)(pred + 4)) = temp;

        value = factor_c >> 5;
        factor_c += b;
        CLIP_RESULT(value)
        byte1 = value;
        value = factor_c >> 5;
        factor_c += b;
        CLIP_RESULT(value)
        byte2 = value;
        value = factor_c >> 5;
        factor_c += b;
        CLIP_RESULT(value)
        byte3 = value;
        value = factor_c >> 5;
        factor_c += b;
        CLIP_RESULT(value)
        temp = byte1 | (byte2 << 8);
        temp |= (byte3 << 16);
        temp |= (value << 24);
        *((uint32*)(pred + 8)) = temp;

        value = factor_c >> 5;
        factor_c += b;
        CLIP_RESULT(value)
        byte1 = value;
        value = factor_c >> 5;
        factor_c += b;
        CLIP_RESULT(value)
        byte2 = value;
        value = factor_c >> 5;
        factor_c += b;
        CLIP_RESULT(value)
        byte3 = value;
        value = factor_c >> 5;
        CLIP_RESULT(value)
        temp = byte1 | (byte2 << 8);
        temp |= (byte3 << 16);
        temp |= (value << 24);
        *((uint32*)(pred + 12)) = temp;
        pred += pred_pitch;
    }
}

/************** Chroma intra prediction *********************/

void Intra_Chroma_DC(AVCCommonObj *video, int pitch, uint8 *predCb, uint8 *predCr)
{
    int i;
    uint32 temp, temp2, pred_a, pred_b;
    uint8 *comp_ref_x, *comp_ref_y;
    uint8 *comp_ref_cb_x = video->pintra_pred_top_cb;
    uint8 *comp_ref_cb_y = video->pintra_pred_left_cb;
    uint8 *comp_ref_cr_x = video->pintra_pred_top_cr;
    uint8 *comp_ref_cr_y = video->pintra_pred_left_cr;
    int  component, j;
    int  sum_x0, sum_x1, sum_y0, sum_y1;
    int pred_0[2], pred_1[2], pred_2[2], pred_3[2];
    int pred_pitch = video->pred_pitch;
    uint8 *pred;

    if (video->intraAvailB & video->intraAvailA)
    {
        comp_ref_x = comp_ref_cb_x;
        comp_ref_y = comp_ref_cb_y;
        for (i = 0; i < 2; i++)
        {
            temp = *((uint32*)comp_ref_x);
            comp_ref_x += 4;
            temp2 = (temp >> 8) & 0xFF00FF;
            temp &= 0xFF00FF;
            temp += temp2;
            temp += (temp >> 16);
            sum_x0 = temp & 0xFFFF;

            temp = *((uint32*)comp_ref_x);
            temp2 = (temp >> 8) & 0xFF00FF;
            temp &= 0xFF00FF;
            temp += temp2;
            temp += (temp >> 16);
            sum_x1 = temp & 0xFFFF;

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

            comp_ref_x = comp_ref_cr_x;
            comp_ref_y = comp_ref_cr_y;
        }
    }

    else if (video->intraAvailA)
    {
        comp_ref_y = comp_ref_cb_y;
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
            comp_ref_y = comp_ref_cr_y;
        }
    }
    else if (video->intraAvailB)
    {
        comp_ref_x = comp_ref_cb_x;
        for (i = 0; i < 2; i++)
        {
            temp = *((uint32*)comp_ref_x);
            comp_ref_x += 4;
            temp2 = (temp >> 8) & 0xFF00FF;
            temp &= 0xFF00FF;
            temp += temp2;
            temp += (temp >> 16);
            sum_x0 = temp & 0xFFFF;

            temp = *((uint32*)comp_ref_x);
            temp2 = (temp >> 8) & 0xFF00FF;
            temp &= 0xFF00FF;
            temp += temp2;
            temp += (temp >> 16);
            sum_x1 = temp & 0xFFFF;

            pred_0[i] = pred_2[i] = (sum_x0 + 2) >> 2;
            pred_1[i] = pred_3[i] = (sum_x1 + 2) >> 2;
            comp_ref_x = comp_ref_cr_x;
        }
    }
    else
    {
        pred_0[0] = pred_0[1] = pred_1[0] = pred_1[1] =
                                                pred_2[0] = pred_2[1] = pred_3[0] = pred_3[1] = 128;
    }

    pred = predCb;
    for (component = 0; component < 2; component++)
    {
        pred_a = pred_0[component];
        pred_b = pred_1[component];
        pred_a |= (pred_a << 8);
        pred_a |= (pred_a << 16);
        pred_b |= (pred_b << 8);
        pred_b |= (pred_b << 16);

        for (i = 4; i < 6; i++)
        {
            for (j = 0; j < 4; j++) /* 4 lines */
            {
                *((uint32*)pred) = pred_a;
                *((uint32*)(pred + 4)) = pred_b;
                pred += pred_pitch; /* move to the next line */
            }
            pred_a = pred_2[component];
            pred_b = pred_3[component];
            pred_a |= (pred_a << 8);
            pred_a |= (pred_a << 16);
            pred_b |= (pred_b << 8);
            pred_b |= (pred_b << 16);
        }
        pred = predCr; /* point to cr */
    }
}

void  Intra_Chroma_Horizontal(AVCCommonObj *video, int pitch, uint8 *predCb, uint8 *predCr)
{
    int i;
    uint32 temp;
    uint8   *comp_ref_cb_y = video->pintra_pred_left_cb;
    uint8   *comp_ref_cr_y = video->pintra_pred_left_cr;
    uint8  *comp;
    int component, j;
    int     pred_pitch = video->pred_pitch;
    uint8   *pred;

    comp = comp_ref_cb_y;
    pred = predCb;
    for (component = 0; component < 2; component++)
    {
        for (i = 4; i < 6; i++)
        {
            for (j = 0; j < 4; j++)
            {
                temp = *comp;
                comp += pitch;
                temp |= (temp << 8);
                temp |= (temp << 16);
                *((uint32*)pred) = temp;
                *((uint32*)(pred + 4)) = temp;
                pred += pred_pitch;
            }
        }
        comp = comp_ref_cr_y;
        pred = predCr; /* point to cr */
    }

}

void  Intra_Chroma_Vertical(AVCCommonObj *video, uint8 *predCb, uint8 *predCr)
{
    uint32  temp1, temp2;
    uint8   *comp_ref_cb_x = video->pintra_pred_top_cb;
    uint8   *comp_ref_cr_x = video->pintra_pred_top_cr;
    uint8   *comp_ref;
    int     component, j;
    int     pred_pitch = video->pred_pitch;
    uint8   *pred;

    comp_ref = comp_ref_cb_x;
    pred = predCb;
    for (component = 0; component < 2; component++)
    {
        temp1 = *((uint32*)comp_ref);
        temp2 = *((uint32*)(comp_ref + 4));
        for (j = 0; j < 8; j++)
        {
            *((uint32*)pred) = temp1;
            *((uint32*)(pred + 4)) = temp2;
            pred += pred_pitch;
        }
        comp_ref = comp_ref_cr_x;
        pred = predCr; /* point to cr */
    }

}

void  Intra_Chroma_Plane(AVCCommonObj *video, int pitch, uint8 *predCb, uint8 *predCr)
{
    int i;
    int a_16_C[2], b_C[2], c_C[2], a_16, b, c, factor_c;
    uint8 *comp_ref_x, *comp_ref_y, *comp_ref_x0, *comp_ref_x1,  *comp_ref_y0, *comp_ref_y1;
    int component, j;
    int H, V, tmp;
    uint32 temp;
    uint8 byte1, byte2, byte3;
    int value;
    uint8 topleft;
    int pred_pitch = video->pred_pitch;
    uint8 *pred;

    comp_ref_x = video->pintra_pred_top_cb;
    comp_ref_y = video->pintra_pred_left_cb;
    topleft = video->intra_pred_topleft_cb;

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

        a_16_C[component] = ((*(comp_ref_x + 7) + *(comp_ref_y + 7 * pitch)) << 4) + 16;
        b_C[component] = (17 * H + 16) >> 5;
        c_C[component] = (17 * V + 16) >> 5;

        comp_ref_x = video->pintra_pred_top_cr;
        comp_ref_y = video->pintra_pred_left_cr;
        topleft = video->intra_pred_topleft_cr;
    }

    pred = predCb;
    for (component = 0; component < 2; component++)
    {
        a_16 = a_16_C[component];
        b = b_C[component];
        c = c_C[component];
        tmp = 0;
        for (i = 4; i < 6; i++)
        {
            for (j = 0; j < 4; j++)
            {
                factor_c = a_16 + c * (tmp++ - 3);

                factor_c -= 3 * b;

                value = factor_c >> 5;
                factor_c += b;
                CLIP_RESULT(value)
                byte1 = value;
                value = factor_c >> 5;
                factor_c += b;
                CLIP_RESULT(value)
                byte2 = value;
                value = factor_c >> 5;
                factor_c += b;
                CLIP_RESULT(value)
                byte3 = value;
                value = factor_c >> 5;
                factor_c += b;
                CLIP_RESULT(value)
                temp = byte1 | (byte2 << 8);
                temp |= (byte3 << 16);
                temp |= (value << 24);
                *((uint32*)pred) = temp;

                value = factor_c >> 5;
                factor_c += b;
                CLIP_RESULT(value)
                byte1 = value;
                value = factor_c >> 5;
                factor_c += b;
                CLIP_RESULT(value)
                byte2 = value;
                value = factor_c >> 5;
                factor_c += b;
                CLIP_RESULT(value)
                byte3 = value;
                value = factor_c >> 5;
                factor_c += b;
                CLIP_RESULT(value)
                temp = byte1 | (byte2 << 8);
                temp |= (byte3 << 16);
                temp |= (value << 24);
                *((uint32*)(pred + 4)) = temp;
                pred += pred_pitch;
            }
        }
        pred = predCr; /* point to cr */
    }
}

