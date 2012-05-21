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
#include "avcenc_int.h"


#define CLIP_RESULT(x)      if((uint)x > 0xFF){ \
                 x = 0xFF & (~(x>>31));}

/* (blkwidth << 2) + (dy << 1) + dx */
static void (*const eChromaMC_SIMD[8])(uint8 *, int , int , int , uint8 *, int, int , int) =
{
    &eChromaFullMC_SIMD,
    &eChromaHorizontalMC_SIMD,
    &eChromaVerticalMC_SIMD,
    &eChromaDiagonalMC_SIMD,
    &eChromaFullMC_SIMD,
    &eChromaHorizontalMC2_SIMD,
    &eChromaVerticalMC2_SIMD,
    &eChromaDiagonalMC2_SIMD
};
/* Perform motion prediction and compensation with residue if exist. */
void AVCMBMotionComp(AVCEncObject *encvid, AVCCommonObj *video)
{
    (void)(encvid);

    AVCMacroblock *currMB = video->currMB;
    AVCPictureData *currPic = video->currPic;
    int mbPartIdx, subMbPartIdx;
    int ref_idx;
    int offset_MbPart_indx = 0;
    int16 *mv;
    uint32 x_pos, y_pos;
    uint8 *curL, *curCb, *curCr;
    uint8 *ref_l, *ref_Cb, *ref_Cr;
    uint8 *predBlock, *predCb, *predCr;
    int block_x, block_y, offset_x, offset_y, offsetP, offset;
    int x_position = (video->mb_x << 4);
    int y_position = (video->mb_y << 4);
    int MbHeight, MbWidth, mbPartIdx_X, mbPartIdx_Y, offset_indx;
    int picWidth = currPic->width;
    int picPitch = currPic->pitch;
    int picHeight = currPic->height;
    uint32 tmp_word;

    tmp_word = y_position * picPitch;
    curL = currPic->Sl + tmp_word + x_position;
    offset = (tmp_word >> 2) + (x_position >> 1);
    curCb = currPic->Scb + offset;
    curCr = currPic->Scr + offset;

    predBlock = curL;
    predCb = curCb;
    predCr = curCr;

    GetMotionVectorPredictor(video, 1);

    for (mbPartIdx = 0; mbPartIdx < currMB->NumMbPart; mbPartIdx++)
    {
        MbHeight = currMB->SubMbPartHeight[mbPartIdx];
        MbWidth = currMB->SubMbPartWidth[mbPartIdx];
        mbPartIdx_X = ((mbPartIdx + offset_MbPart_indx) & 1);
        mbPartIdx_Y = (mbPartIdx + offset_MbPart_indx) >> 1;
        ref_idx = currMB->ref_idx_L0[(mbPartIdx_Y << 1) + mbPartIdx_X];
        offset_indx = 0;

        ref_l = video->RefPicList0[ref_idx]->Sl;
        ref_Cb = video->RefPicList0[ref_idx]->Scb;
        ref_Cr = video->RefPicList0[ref_idx]->Scr;

        for (subMbPartIdx = 0; subMbPartIdx < currMB->NumSubMbPart[mbPartIdx]; subMbPartIdx++)
        {
            block_x = (mbPartIdx_X << 1) + ((subMbPartIdx + offset_indx) & 1);
            block_y = (mbPartIdx_Y << 1) + (((subMbPartIdx + offset_indx) >> 1) & 1);
            mv = (int16*)(currMB->mvL0 + block_x + (block_y << 2));
            offset_x = x_position + (block_x << 2);
            offset_y = y_position + (block_y << 2);
            x_pos = (offset_x << 2) + *mv++;   /*quarter pel */
            y_pos = (offset_y << 2) + *mv;   /*quarter pel */

            //offset = offset_y * currPic->width;
            //offsetC = (offset >> 2) + (offset_x >> 1);
            offsetP = (block_y << 2) * picPitch + (block_x << 2);
            eLumaMotionComp(ref_l, picPitch, picHeight, x_pos, y_pos,
                            /*comp_Sl + offset + offset_x,*/
                            predBlock + offsetP, picPitch, MbWidth, MbHeight);

            offsetP = (block_y * picWidth) + (block_x << 1);
            eChromaMotionComp(ref_Cb, picWidth >> 1, picHeight >> 1, x_pos, y_pos,
                              /*comp_Scb +  offsetC,*/
                              predCb + offsetP, picPitch >> 1, MbWidth >> 1, MbHeight >> 1);
            eChromaMotionComp(ref_Cr, picWidth >> 1, picHeight >> 1, x_pos, y_pos,
                              /*comp_Scr +  offsetC,*/
                              predCr + offsetP, picPitch >> 1, MbWidth >> 1, MbHeight >> 1);

            offset_indx = currMB->SubMbPartWidth[mbPartIdx] >> 3;
        }
        offset_MbPart_indx = currMB->MbPartWidth >> 4;
    }

    return ;
}


/* preform the actual  motion comp here */
void eLumaMotionComp(uint8 *ref, int picpitch, int picheight,
                     int x_pos, int y_pos,
                     uint8 *pred, int pred_pitch,
                     int blkwidth, int blkheight)
{
    (void)(picheight);

    int dx, dy;
    int temp2[21][21]; /* for intermediate results */
    uint8 *ref2;

    dx = x_pos & 3;
    dy = y_pos & 3;
    x_pos = x_pos >> 2;  /* round it to full-pel resolution */
    y_pos = y_pos >> 2;

    /* perform actual motion compensation */
    if (dx == 0 && dy == 0)
    {  /* fullpel position *//* G */

        ref += y_pos * picpitch + x_pos;

        eFullPelMC(ref, picpitch, pred, pred_pitch, blkwidth, blkheight);

    }   /* other positions */
    else  if (dy == 0)
    { /* no vertical interpolation *//* a,b,c*/

        ref += y_pos * picpitch + x_pos;

        eHorzInterp1MC(ref, picpitch, pred, pred_pitch, blkwidth, blkheight, dx);
    }
    else if (dx == 0)
    { /*no horizontal interpolation *//* d,h,n */

        ref += y_pos * picpitch + x_pos;

        eVertInterp1MC(ref, picpitch, pred, pred_pitch, blkwidth, blkheight, dy);
    }
    else if (dy == 2)
    {  /* horizontal cross *//* i, j, k */

        ref += y_pos * picpitch + x_pos - 2; /* move to the left 2 pixels */

        eVertInterp2MC(ref, picpitch, &temp2[0][0], 21, blkwidth + 5, blkheight);

        eHorzInterp2MC(&temp2[0][2], 21, pred, pred_pitch, blkwidth, blkheight, dx);
    }
    else if (dx == 2)
    { /* vertical cross */ /* f,q */

        ref += (y_pos - 2) * picpitch + x_pos; /* move to up 2 lines */

        eHorzInterp3MC(ref, picpitch, &temp2[0][0], 21, blkwidth, blkheight + 5);
        eVertInterp3MC(&temp2[2][0], 21, pred, pred_pitch, blkwidth, blkheight, dy);
    }
    else
    { /* diagonal *//* e,g,p,r */

        ref2 = ref + (y_pos + (dy / 2)) * picpitch + x_pos;

        ref += (y_pos * picpitch) + x_pos + (dx / 2);

        eDiagonalInterpMC(ref2, ref, picpitch, pred, pred_pitch, blkwidth, blkheight);
    }

    return ;
}

void eCreateAlign(uint8 *ref, int picpitch, int y_pos,
                  uint8 *out, int blkwidth, int blkheight)
{
    int i, j;
    int offset, out_offset;
    uint32 prev_pix, result, pix1, pix2, pix4;

    ref += y_pos * picpitch;// + x_pos;
    out_offset = 24 - blkwidth;

    //switch(x_pos&0x3){
    switch (((intptr_t)ref)&0x3)
    {
        case 1:
            offset =  picpitch - blkwidth - 3;
            for (j = 0; j < blkheight; j++)
            {
                pix1 = *ref++;
                pix2 = *((uint16*)ref);
                ref += 2;
                result = (pix2 << 8) | pix1;

                for (i = 3; i < blkwidth; i += 4)
                {
                    pix4 = *((uint32*)ref);
                    ref += 4;
                    prev_pix = (pix4 << 24) & 0xFF000000; /* mask out byte belong to previous word */
                    result |= prev_pix;
                    *((uint32*)out) = result;  /* write 4 bytes */
                    out += 4;
                    result = pix4 >> 8; /* for the next loop */
                }
                ref += offset;
                out += out_offset;
            }
            break;
        case 2:
            offset =  picpitch - blkwidth - 2;
            for (j = 0; j < blkheight; j++)
            {
                result = *((uint16*)ref);
                ref += 2;
                for (i = 2; i < blkwidth; i += 4)
                {
                    pix4 = *((uint32*)ref);
                    ref += 4;
                    prev_pix = (pix4 << 16) & 0xFFFF0000; /* mask out byte belong to previous word */
                    result |= prev_pix;
                    *((uint32*)out) = result;  /* write 4 bytes */
                    out += 4;
                    result = pix4 >> 16; /* for the next loop */
                }
                ref += offset;
                out += out_offset;
            }
            break;
        case 3:
            offset =  picpitch - blkwidth - 1;
            for (j = 0; j < blkheight; j++)
            {
                result = *ref++;
                for (i = 1; i < blkwidth; i += 4)
                {
                    pix4 = *((uint32*)ref);
                    ref += 4;
                    prev_pix = (pix4 << 8) & 0xFFFFFF00; /* mask out byte belong to previous word */
                    result |= prev_pix;
                    *((uint32*)out) = result;  /* write 4 bytes */
                    out += 4;
                    result = pix4 >> 24; /* for the next loop */
                }
                ref += offset;
                out += out_offset;
            }
            break;
    }
}

void eHorzInterp1MC(uint8 *in, int inpitch, uint8 *out, int outpitch,
                    int blkwidth, int blkheight, int dx)
{
    uint8 *p_ref, *tmp;
    uint32 *p_cur;
    uint32 pkres;
    int result, curr_offset, ref_offset;
    int j;
    int32 r0, r1, r2, r3, r4, r5;
    int32 r13, r6;

    p_cur = (uint32*)out; /* assume it's word aligned */
    curr_offset = (outpitch - blkwidth) >> 2;
    p_ref = in;
    ref_offset = inpitch - blkwidth;

    if (dx&1)
    {
        dx = ((dx >> 1) ? -3 : -4); /* use in 3/4 pel */
        p_ref -= 2;
        r13 = 0;
        for (j = blkheight; j > 0; j--)
        {
            tmp = p_ref + blkwidth;
            r0 = p_ref[0];
            r1 = p_ref[2];
            r0 |= (r1 << 16);           /* 0,c,0,a */
            r1 = p_ref[1];
            r2 = p_ref[3];
            r1 |= (r2 << 16);           /* 0,d,0,b */
            while (p_ref < tmp)
            {
                r2 = *(p_ref += 4); /* move pointer to e */
                r3 = p_ref[2];
                r2 |= (r3 << 16);           /* 0,g,0,e */
                r3 = p_ref[1];
                r4 = p_ref[3];
                r3 |= (r4 << 16);           /* 0,h,0,f */

                r4 = r0 + r3;       /* c+h, a+f */
                r5 = r0 + r1;   /* c+d, a+b */
                r6 = r2 + r3;   /* g+h, e+f */
                r5 >>= 16;
                r5 |= (r6 << 16);   /* e+f, c+d */
                r4 += r5 * 20;      /* c+20*e+20*f+h, a+20*c+20*d+f */
                r4 += 0x100010; /* +16, +16 */
                r5 = r1 + r2;       /* d+g, b+e */
                r4 -= r5 * 5;       /* c-5*d+20*e+20*f-5*g+h, a-5*b+20*c+20*d-5*e+f */
                r4 >>= 5;
                r13 |= r4;      /* check clipping */

                r5 = p_ref[dx+2];
                r6 = p_ref[dx+4];
                r5 |= (r6 << 16);
                r4 += r5;
                r4 += 0x10001;
                r4 = (r4 >> 1) & 0xFF00FF;

                r5 = p_ref[4];  /* i */
                r6 = (r5 << 16);
                r5 = r6 | (r2 >> 16);/* 0,i,0,g */
                r5 += r1;       /* d+i, b+g */ /* r5 not free */
                r1 >>= 16;
                r1 |= (r3 << 16); /* 0,f,0,d */ /* r1 has changed */
                r1 += r2;       /* f+g, d+e */
                r5 += 20 * r1;  /* d+20f+20g+i, b+20d+20e+g */
                r0 >>= 16;
                r0 |= (r2 << 16); /* 0,e,0,c */ /* r0 has changed */
                r0 += r3;       /* e+h, c+f */
                r5 += 0x100010; /* 16,16 */
                r5 -= r0 * 5;       /* d-5e+20f+20g-5h+i, b-5c+20d+20e-5f+g */
                r5 >>= 5;
                r13 |= r5;      /* check clipping */

                r0 = p_ref[dx+3];
                r1 = p_ref[dx+5];
                r0 |= (r1 << 16);
                r5 += r0;
                r5 += 0x10001;
                r5 = (r5 >> 1) & 0xFF00FF;

                r4 |= (r5 << 8);    /* pack them together */
                *p_cur++ = r4;
                r1 = r3;
                r0 = r2;
            }
            p_cur += curr_offset; /* move to the next line */
            p_ref += ref_offset;  /*    ref_offset = inpitch-blkwidth; */

            if (r13&0xFF000700) /* need clipping */
            {
                /* move back to the beginning of the line */
                p_ref -= (ref_offset + blkwidth);   /* input */
                p_cur -= (outpitch >> 2);

                tmp = p_ref + blkwidth;
                for (; p_ref < tmp;)
                {

                    r0 = *p_ref++;
                    r1 = *p_ref++;
                    r2 = *p_ref++;
                    r3 = *p_ref++;
                    r4 = *p_ref++;
                    /* first pixel */
                    r5 = *p_ref++;
                    result = (r0 + r5);
                    r0 = (r1 + r4);
                    result -= (r0 * 5);//result -= r0;  result -= (r0<<2);
                    r0 = (r2 + r3);
                    result += (r0 * 20);//result += (r0<<4);    result += (r0<<2);
                    result = (result + 16) >> 5;
                    CLIP_RESULT(result)
                    /* 3/4 pel,  no need to clip */
                    result = (result + p_ref[dx] + 1);
                    pkres = (result >> 1) ;
                    /* second pixel */
                    r0 = *p_ref++;
                    result = (r1 + r0);
                    r1 = (r2 + r5);
                    result -= (r1 * 5);//result -= r1;  result -= (r1<<2);
                    r1 = (r3 + r4);
                    result += (r1 * 20);//result += (r1<<4);    result += (r1<<2);
                    result = (result + 16) >> 5;
                    CLIP_RESULT(result)
                    /* 3/4 pel,  no need to clip */
                    result = (result + p_ref[dx] + 1);
                    result = (result >> 1);
                    pkres  |= (result << 8);
                    /* third pixel */
                    r1 = *p_ref++;
                    result = (r2 + r1);
                    r2 = (r3 + r0);
                    result -= (r2 * 5);//result -= r2;  result -= (r2<<2);
                    r2 = (r4 + r5);
                    result += (r2 * 20);//result += (r2<<4);    result += (r2<<2);
                    result = (result + 16) >> 5;
                    CLIP_RESULT(result)
                    /* 3/4 pel,  no need to clip */
                    result = (result + p_ref[dx] + 1);
                    result = (result >> 1);
                    pkres  |= (result << 16);
                    /* fourth pixel */
                    r2 = *p_ref++;
                    result = (r3 + r2);
                    r3 = (r4 + r1);
                    result -= (r3 * 5);//result -= r3;  result -= (r3<<2);
                    r3 = (r5 + r0);
                    result += (r3 * 20);//result += (r3<<4);    result += (r3<<2);
                    result = (result + 16) >> 5;
                    CLIP_RESULT(result)
                    /* 3/4 pel,  no need to clip */
                    result = (result + p_ref[dx] + 1);
                    result = (result >> 1);
                    pkres  |= (result << 24);
                    *p_cur++ = pkres; /* write 4 pixels */
                    p_ref -= 5;  /* offset back to the middle of filter */
                }
                p_cur += curr_offset;  /* move to the next line */
                p_ref += ref_offset;    /* move to the next line */
            }
        }
    }
    else
    {
        p_ref -= 2;
        r13 = 0;
        for (j = blkheight; j > 0; j--)
        {
            tmp = p_ref + blkwidth;
            r0 = p_ref[0];
            r1 = p_ref[2];
            r0 |= (r1 << 16);           /* 0,c,0,a */
            r1 = p_ref[1];
            r2 = p_ref[3];
            r1 |= (r2 << 16);           /* 0,d,0,b */
            while (p_ref < tmp)
            {
                r2 = *(p_ref += 4); /* move pointer to e */
                r3 = p_ref[2];
                r2 |= (r3 << 16);           /* 0,g,0,e */
                r3 = p_ref[1];
                r4 = p_ref[3];
                r3 |= (r4 << 16);           /* 0,h,0,f */

                r4 = r0 + r3;       /* c+h, a+f */
                r5 = r0 + r1;   /* c+d, a+b */
                r6 = r2 + r3;   /* g+h, e+f */
                r5 >>= 16;
                r5 |= (r6 << 16);   /* e+f, c+d */
                r4 += r5 * 20;      /* c+20*e+20*f+h, a+20*c+20*d+f */
                r4 += 0x100010; /* +16, +16 */
                r5 = r1 + r2;       /* d+g, b+e */
                r4 -= r5 * 5;       /* c-5*d+20*e+20*f-5*g+h, a-5*b+20*c+20*d-5*e+f */
                r4 >>= 5;
                r13 |= r4;      /* check clipping */
                r4 &= 0xFF00FF; /* mask */

                r5 = p_ref[4];  /* i */
                r6 = (r5 << 16);
                r5 = r6 | (r2 >> 16);/* 0,i,0,g */
                r5 += r1;       /* d+i, b+g */ /* r5 not free */
                r1 >>= 16;
                r1 |= (r3 << 16); /* 0,f,0,d */ /* r1 has changed */
                r1 += r2;       /* f+g, d+e */
                r5 += 20 * r1;  /* d+20f+20g+i, b+20d+20e+g */
                r0 >>= 16;
                r0 |= (r2 << 16); /* 0,e,0,c */ /* r0 has changed */
                r0 += r3;       /* e+h, c+f */
                r5 += 0x100010; /* 16,16 */
                r5 -= r0 * 5;       /* d-5e+20f+20g-5h+i, b-5c+20d+20e-5f+g */
                r5 >>= 5;
                r13 |= r5;      /* check clipping */
                r5 &= 0xFF00FF; /* mask */

                r4 |= (r5 << 8);    /* pack them together */
                *p_cur++ = r4;
                r1 = r3;
                r0 = r2;
            }
            p_cur += curr_offset; /* move to the next line */
            p_ref += ref_offset;  /*    ref_offset = inpitch-blkwidth; */

            if (r13&0xFF000700) /* need clipping */
            {
                /* move back to the beginning of the line */
                p_ref -= (ref_offset + blkwidth);   /* input */
                p_cur -= (outpitch >> 2);

                tmp = p_ref + blkwidth;
                for (; p_ref < tmp;)
                {

                    r0 = *p_ref++;
                    r1 = *p_ref++;
                    r2 = *p_ref++;
                    r3 = *p_ref++;
                    r4 = *p_ref++;
                    /* first pixel */
                    r5 = *p_ref++;
                    result = (r0 + r5);
                    r0 = (r1 + r4);
                    result -= (r0 * 5);//result -= r0;  result -= (r0<<2);
                    r0 = (r2 + r3);
                    result += (r0 * 20);//result += (r0<<4);    result += (r0<<2);
                    result = (result + 16) >> 5;
                    CLIP_RESULT(result)
                    pkres  = result;
                    /* second pixel */
                    r0 = *p_ref++;
                    result = (r1 + r0);
                    r1 = (r2 + r5);
                    result -= (r1 * 5);//result -= r1;  result -= (r1<<2);
                    r1 = (r3 + r4);
                    result += (r1 * 20);//result += (r1<<4);    result += (r1<<2);
                    result = (result + 16) >> 5;
                    CLIP_RESULT(result)
                    pkres  |= (result << 8);
                    /* third pixel */
                    r1 = *p_ref++;
                    result = (r2 + r1);
                    r2 = (r3 + r0);
                    result -= (r2 * 5);//result -= r2;  result -= (r2<<2);
                    r2 = (r4 + r5);
                    result += (r2 * 20);//result += (r2<<4);    result += (r2<<2);
                    result = (result + 16) >> 5;
                    CLIP_RESULT(result)
                    pkres  |= (result << 16);
                    /* fourth pixel */
                    r2 = *p_ref++;
                    result = (r3 + r2);
                    r3 = (r4 + r1);
                    result -= (r3 * 5);//result -= r3;  result -= (r3<<2);
                    r3 = (r5 + r0);
                    result += (r3 * 20);//result += (r3<<4);    result += (r3<<2);
                    result = (result + 16) >> 5;
                    CLIP_RESULT(result)
                    pkres  |= (result << 24);
                    *p_cur++ = pkres;   /* write 4 pixels */
                    p_ref -= 5;
                }
                p_cur += curr_offset; /* move to the next line */
                p_ref += ref_offset;
            }
        }
    }

    return ;
}

void eHorzInterp2MC(int *in, int inpitch, uint8 *out, int outpitch,
                    int blkwidth, int blkheight, int dx)
{
    int *p_ref, *tmp;
    uint32 *p_cur;
    uint32 pkres;
    int result, result2, curr_offset, ref_offset;
    int j, r0, r1, r2, r3, r4, r5;

    p_cur = (uint32*)out; /* assume it's word aligned */
    curr_offset = (outpitch - blkwidth) >> 2;
    p_ref = in;
    ref_offset = inpitch - blkwidth;

    if (dx&1)
    {
        dx = ((dx >> 1) ? -3 : -4); /* use in 3/4 pel */

        for (j = blkheight; j > 0 ; j--)
        {
            tmp = p_ref + blkwidth;
            for (; p_ref < tmp;)
            {

                r0 = p_ref[-2];
                r1 = p_ref[-1];
                r2 = *p_ref++;
                r3 = *p_ref++;
                r4 = *p_ref++;
                /* first pixel */
                r5 = *p_ref++;
                result = (r0 + r5);
                r0 = (r1 + r4);
                result -= (r0 * 5);//result -= r0;  result -= (r0<<2);
                r0 = (r2 + r3);
                result += (r0 * 20);//result += (r0<<4);    result += (r0<<2);
                result = (result + 512) >> 10;
                CLIP_RESULT(result)
                result2 = ((p_ref[dx] + 16) >> 5);
                CLIP_RESULT(result2)
                /* 3/4 pel,  no need to clip */
                result = (result + result2 + 1);
                pkres = (result >> 1);
                /* second pixel */
                r0 = *p_ref++;
                result = (r1 + r0);
                r1 = (r2 + r5);
                result -= (r1 * 5);//result -= r1;  result -= (r1<<2);
                r1 = (r3 + r4);
                result += (r1 * 20);//result += (r1<<4);    result += (r1<<2);
                result = (result + 512) >> 10;
                CLIP_RESULT(result)
                result2 = ((p_ref[dx] + 16) >> 5);
                CLIP_RESULT(result2)
                /* 3/4 pel,  no need to clip */
                result = (result + result2 + 1);
                result = (result >> 1);
                pkres  |= (result << 8);
                /* third pixel */
                r1 = *p_ref++;
                result = (r2 + r1);
                r2 = (r3 + r0);
                result -= (r2 * 5);//result -= r2;  result -= (r2<<2);
                r2 = (r4 + r5);
                result += (r2 * 20);//result += (r2<<4);    result += (r2<<2);
                result = (result + 512) >> 10;
                CLIP_RESULT(result)
                result2 = ((p_ref[dx] + 16) >> 5);
                CLIP_RESULT(result2)
                /* 3/4 pel,  no need to clip */
                result = (result + result2 + 1);
                result = (result >> 1);
                pkres  |= (result << 16);
                /* fourth pixel */
                r2 = *p_ref++;
                result = (r3 + r2);
                r3 = (r4 + r1);
                result -= (r3 * 5);//result -= r3;  result -= (r3<<2);
                r3 = (r5 + r0);
                result += (r3 * 20);//result += (r3<<4);    result += (r3<<2);
                result = (result + 512) >> 10;
                CLIP_RESULT(result)
                result2 = ((p_ref[dx] + 16) >> 5);
                CLIP_RESULT(result2)
                /* 3/4 pel,  no need to clip */
                result = (result + result2 + 1);
                result = (result >> 1);
                pkres  |= (result << 24);
                *p_cur++ = pkres; /* write 4 pixels */
                p_ref -= 3;  /* offset back to the middle of filter */
            }
            p_cur += curr_offset;  /* move to the next line */
            p_ref += ref_offset;    /* move to the next line */
        }
    }
    else
    {
        for (j = blkheight; j > 0 ; j--)
        {
            tmp = p_ref + blkwidth;
            for (; p_ref < tmp;)
            {

                r0 = p_ref[-2];
                r1 = p_ref[-1];
                r2 = *p_ref++;
                r3 = *p_ref++;
                r4 = *p_ref++;
                /* first pixel */
                r5 = *p_ref++;
                result = (r0 + r5);
                r0 = (r1 + r4);
                result -= (r0 * 5);//result -= r0;  result -= (r0<<2);
                r0 = (r2 + r3);
                result += (r0 * 20);//result += (r0<<4);    result += (r0<<2);
                result = (result + 512) >> 10;
                CLIP_RESULT(result)
                pkres  = result;
                /* second pixel */
                r0 = *p_ref++;
                result = (r1 + r0);
                r1 = (r2 + r5);
                result -= (r1 * 5);//result -= r1;  result -= (r1<<2);
                r1 = (r3 + r4);
                result += (r1 * 20);//result += (r1<<4);    result += (r1<<2);
                result = (result + 512) >> 10;
                CLIP_RESULT(result)
                pkres  |= (result << 8);
                /* third pixel */
                r1 = *p_ref++;
                result = (r2 + r1);
                r2 = (r3 + r0);
                result -= (r2 * 5);//result -= r2;  result -= (r2<<2);
                r2 = (r4 + r5);
                result += (r2 * 20);//result += (r2<<4);    result += (r2<<2);
                result = (result + 512) >> 10;
                CLIP_RESULT(result)
                pkres  |= (result << 16);
                /* fourth pixel */
                r2 = *p_ref++;
                result = (r3 + r2);
                r3 = (r4 + r1);
                result -= (r3 * 5);//result -= r3;  result -= (r3<<2);
                r3 = (r5 + r0);
                result += (r3 * 20);//result += (r3<<4);    result += (r3<<2);
                result = (result + 512) >> 10;
                CLIP_RESULT(result)
                pkres  |= (result << 24);
                *p_cur++ = pkres; /* write 4 pixels */
                p_ref -= 3;  /* offset back to the middle of filter */
            }
            p_cur += curr_offset;  /* move to the next line */
            p_ref += ref_offset;    /* move to the next line */
        }
    }

    return ;
}

void eHorzInterp3MC(uint8 *in, int inpitch, int *out, int outpitch,
                    int blkwidth, int blkheight)
{
    uint8 *p_ref, *tmp;
    int   *p_cur;
    int result, curr_offset, ref_offset;
    int j, r0, r1, r2, r3, r4, r5;

    p_cur = out;
    curr_offset = (outpitch - blkwidth);
    p_ref = in;
    ref_offset = inpitch - blkwidth;

    for (j = blkheight; j > 0 ; j--)
    {
        tmp = p_ref + blkwidth;
        for (; p_ref < tmp;)
        {

            r0 = p_ref[-2];
            r1 = p_ref[-1];
            r2 = *p_ref++;
            r3 = *p_ref++;
            r4 = *p_ref++;
            /* first pixel */
            r5 = *p_ref++;
            result = (r0 + r5);
            r0 = (r1 + r4);
            result -= (r0 * 5);//result -= r0;  result -= (r0<<2);
            r0 = (r2 + r3);
            result += (r0 * 20);//result += (r0<<4);    result += (r0<<2);
            *p_cur++ = result;
            /* second pixel */
            r0 = *p_ref++;
            result = (r1 + r0);
            r1 = (r2 + r5);
            result -= (r1 * 5);//result -= r1;  result -= (r1<<2);
            r1 = (r3 + r4);
            result += (r1 * 20);//result += (r1<<4);    result += (r1<<2);
            *p_cur++ = result;
            /* third pixel */
            r1 = *p_ref++;
            result = (r2 + r1);
            r2 = (r3 + r0);
            result -= (r2 * 5);//result -= r2;  result -= (r2<<2);
            r2 = (r4 + r5);
            result += (r2 * 20);//result += (r2<<4);    result += (r2<<2);
            *p_cur++ = result;
            /* fourth pixel */
            r2 = *p_ref++;
            result = (r3 + r2);
            r3 = (r4 + r1);
            result -= (r3 * 5);//result -= r3;  result -= (r3<<2);
            r3 = (r5 + r0);
            result += (r3 * 20);//result += (r3<<4);    result += (r3<<2);
            *p_cur++ = result;
            p_ref -= 3; /* move back to the middle of the filter */
        }
        p_cur += curr_offset; /* move to the next line */
        p_ref += ref_offset;
    }

    return ;
}
void eVertInterp1MC(uint8 *in, int inpitch, uint8 *out, int outpitch,
                    int blkwidth, int blkheight, int dy)
{
    uint8 *p_cur, *p_ref, *tmp;
    int result, curr_offset, ref_offset;
    int j, i;
    int32 r0, r1, r2, r3, r4, r5, r6, r7, r8, r13;
    uint8  tmp_in[24][24];

    /* not word-aligned */
    if (((intptr_t)in)&0x3)
    {
        eCreateAlign(in, inpitch, -2, &tmp_in[0][0], blkwidth, blkheight + 5);
        in = &tmp_in[2][0];
        inpitch = 24;
    }
    p_cur = out;
    curr_offset = 1 - outpitch * (blkheight - 1); /* offset vertically back up and one pixel to right */
    ref_offset = blkheight * inpitch; /* for limit */

    curr_offset += 3;

    if (dy&1)
    {
        dy = (dy >> 1) ? 0 : -inpitch;

        for (j = 0; j < blkwidth; j += 4, in += 4)
        {
            r13 = 0;
            p_ref = in;
            p_cur -= outpitch;  /* compensate for the first offset */
            tmp = p_ref + ref_offset; /* limit */
            while (p_ref < tmp)  /* the loop un-rolled  */
            {
                r0 = *((uint32*)(p_ref - (inpitch << 1))); /* load 4 bytes */
                p_ref += inpitch;
                r6 = (r0 >> 8) & 0xFF00FF; /* second and fourth byte */
                r0 &= 0xFF00FF;

                r1 = *((uint32*)(p_ref + (inpitch << 1)));  /* r1, r7, ref[3] */
                r7 = (r1 >> 8) & 0xFF00FF;
                r1 &= 0xFF00FF;

                r0 += r1;
                r6 += r7;

                r2 = *((uint32*)p_ref); /* r2, r8, ref[1] */
                r8 = (r2 >> 8) & 0xFF00FF;
                r2 &= 0xFF00FF;

                r1 = *((uint32*)(p_ref - inpitch)); /* r1, r7, ref[0] */
                r7 = (r1 >> 8) & 0xFF00FF;
                r1 &= 0xFF00FF;
                r1 += r2;

                r7 += r8;

                r0 += 20 * r1;
                r6 += 20 * r7;
                r0 += 0x100010;
                r6 += 0x100010;

                r2 = *((uint32*)(p_ref - (inpitch << 1))); /* r2, r8, ref[-1] */
                r8 = (r2 >> 8) & 0xFF00FF;
                r2 &= 0xFF00FF;

                r1 = *((uint32*)(p_ref + inpitch)); /* r1, r7, ref[2] */
                r7 = (r1 >> 8) & 0xFF00FF;
                r1 &= 0xFF00FF;
                r1 += r2;

                r7 += r8;

                r0 -= 5 * r1;
                r6 -= 5 * r7;

                r0 >>= 5;
                r6 >>= 5;
                /* clip */
                r13 |= r6;
                r13 |= r0;
                //CLIPPACK(r6,result)

                r1 = *((uint32*)(p_ref + dy));
                r2 = (r1 >> 8) & 0xFF00FF;
                r1 &= 0xFF00FF;
                r0 += r1;
                r6 += r2;
                r0 += 0x10001;
                r6 += 0x10001;
                r0 = (r0 >> 1) & 0xFF00FF;
                r6 = (r6 >> 1) & 0xFF00FF;

                r0 |= (r6 << 8);  /* pack it back */
                *((uint32*)(p_cur += outpitch)) = r0;
            }
            p_cur += curr_offset; /* offset to the next pixel */
            if (r13 & 0xFF000700) /* this column need clipping */
            {
                p_cur -= 4;
                for (i = 0; i < 4; i++)
                {
                    p_ref = in + i;
                    p_cur -= outpitch;  /* compensate for the first offset */

                    tmp = p_ref + ref_offset; /* limit */
                    while (p_ref < tmp)
                    {                           /* loop un-rolled */
                        r0 = *(p_ref - (inpitch << 1));
                        r1 = *(p_ref - inpitch);
                        r2 = *p_ref;
                        r3 = *(p_ref += inpitch);  /* modify pointer before loading */
                        r4 = *(p_ref += inpitch);
                        /* first pixel */
                        r5 = *(p_ref += inpitch);
                        result = (r0 + r5);
                        r0 = (r1 + r4);
                        result -= (r0 * 5);//result -= r0;  result -= (r0<<2);
                        r0 = (r2 + r3);
                        result += (r0 * 20);//result += (r0<<4);    result += (r0<<2);
                        result = (result + 16) >> 5;
                        CLIP_RESULT(result)
                        /* 3/4 pel,  no need to clip */
                        result = (result + p_ref[dy-(inpitch<<1)] + 1);
                        result = (result >> 1);
                        *(p_cur += outpitch) = result;
                        /* second pixel */
                        r0 = *(p_ref += inpitch);
                        result = (r1 + r0);
                        r1 = (r2 + r5);
                        result -= (r1 * 5);//result -= r1;  result -= (r1<<2);
                        r1 = (r3 + r4);
                        result += (r1 * 20);//result += (r1<<4);    result += (r1<<2);
                        result = (result + 16) >> 5;
                        CLIP_RESULT(result)
                        /* 3/4 pel,  no need to clip */
                        result = (result + p_ref[dy-(inpitch<<1)] + 1);
                        result = (result >> 1);
                        *(p_cur += outpitch) = result;
                        /* third pixel */
                        r1 = *(p_ref += inpitch);
                        result = (r2 + r1);
                        r2 = (r3 + r0);
                        result -= (r2 * 5);//result -= r2;  result -= (r2<<2);
                        r2 = (r4 + r5);
                        result += (r2 * 20);//result += (r2<<4);    result += (r2<<2);
                        result = (result + 16) >> 5;
                        CLIP_RESULT(result)
                        /* 3/4 pel,  no need to clip */
                        result = (result + p_ref[dy-(inpitch<<1)] + 1);
                        result = (result >> 1);
                        *(p_cur += outpitch) = result;
                        /* fourth pixel */
                        r2 = *(p_ref += inpitch);
                        result = (r3 + r2);
                        r3 = (r4 + r1);
                        result -= (r3 * 5);//result -= r3;  result -= (r3<<2);
                        r3 = (r5 + r0);
                        result += (r3 * 20);//result += (r3<<4);    result += (r3<<2);
                        result = (result + 16) >> 5;
                        CLIP_RESULT(result)
                        /* 3/4 pel,  no need to clip */
                        result = (result + p_ref[dy-(inpitch<<1)] + 1);
                        result = (result >> 1);
                        *(p_cur += outpitch) = result;
                        p_ref -= (inpitch << 1);  /* move back to center of the filter of the next one */
                    }
                    p_cur += (curr_offset - 3);
                }
            }
        }
    }
    else
    {
        for (j = 0; j < blkwidth; j += 4, in += 4)
        {
            r13 = 0;
            p_ref = in;
            p_cur -= outpitch;  /* compensate for the first offset */
            tmp = p_ref + ref_offset; /* limit */
            while (p_ref < tmp)  /* the loop un-rolled  */
            {
                r0 = *((uint32*)(p_ref - (inpitch << 1))); /* load 4 bytes */
                p_ref += inpitch;
                r6 = (r0 >> 8) & 0xFF00FF; /* second and fourth byte */
                r0 &= 0xFF00FF;

                r1 = *((uint32*)(p_ref + (inpitch << 1)));  /* r1, r7, ref[3] */
                r7 = (r1 >> 8) & 0xFF00FF;
                r1 &= 0xFF00FF;

                r0 += r1;
                r6 += r7;

                r2 = *((uint32*)p_ref); /* r2, r8, ref[1] */
                r8 = (r2 >> 8) & 0xFF00FF;
                r2 &= 0xFF00FF;

                r1 = *((uint32*)(p_ref - inpitch)); /* r1, r7, ref[0] */
                r7 = (r1 >> 8) & 0xFF00FF;
                r1 &= 0xFF00FF;
                r1 += r2;

                r7 += r8;

                r0 += 20 * r1;
                r6 += 20 * r7;
                r0 += 0x100010;
                r6 += 0x100010;

                r2 = *((uint32*)(p_ref - (inpitch << 1))); /* r2, r8, ref[-1] */
                r8 = (r2 >> 8) & 0xFF00FF;
                r2 &= 0xFF00FF;

                r1 = *((uint32*)(p_ref + inpitch)); /* r1, r7, ref[2] */
                r7 = (r1 >> 8) & 0xFF00FF;
                r1 &= 0xFF00FF;
                r1 += r2;

                r7 += r8;

                r0 -= 5 * r1;
                r6 -= 5 * r7;

                r0 >>= 5;
                r6 >>= 5;
                /* clip */
                r13 |= r6;
                r13 |= r0;
                //CLIPPACK(r6,result)
                r0 &= 0xFF00FF;
                r6 &= 0xFF00FF;
                r0 |= (r6 << 8);  /* pack it back */
                *((uint32*)(p_cur += outpitch)) = r0;
            }
            p_cur += curr_offset; /* offset to the next pixel */
            if (r13 & 0xFF000700) /* this column need clipping */
            {
                p_cur -= 4;
                for (i = 0; i < 4; i++)
                {
                    p_ref = in + i;
                    p_cur -= outpitch;  /* compensate for the first offset */
                    tmp = p_ref + ref_offset; /* limit */
                    while (p_ref < tmp)
                    {                           /* loop un-rolled */
                        r0 = *(p_ref - (inpitch << 1));
                        r1 = *(p_ref - inpitch);
                        r2 = *p_ref;
                        r3 = *(p_ref += inpitch);  /* modify pointer before loading */
                        r4 = *(p_ref += inpitch);
                        /* first pixel */
                        r5 = *(p_ref += inpitch);
                        result = (r0 + r5);
                        r0 = (r1 + r4);
                        result -= (r0 * 5);//result -= r0;  result -= (r0<<2);
                        r0 = (r2 + r3);
                        result += (r0 * 20);//result += (r0<<4);    result += (r0<<2);
                        result = (result + 16) >> 5;
                        CLIP_RESULT(result)
                        *(p_cur += outpitch) = result;
                        /* second pixel */
                        r0 = *(p_ref += inpitch);
                        result = (r1 + r0);
                        r1 = (r2 + r5);
                        result -= (r1 * 5);//result -= r1;  result -= (r1<<2);
                        r1 = (r3 + r4);
                        result += (r1 * 20);//result += (r1<<4);    result += (r1<<2);
                        result = (result + 16) >> 5;
                        CLIP_RESULT(result)
                        *(p_cur += outpitch) = result;
                        /* third pixel */
                        r1 = *(p_ref += inpitch);
                        result = (r2 + r1);
                        r2 = (r3 + r0);
                        result -= (r2 * 5);//result -= r2;  result -= (r2<<2);
                        r2 = (r4 + r5);
                        result += (r2 * 20);//result += (r2<<4);    result += (r2<<2);
                        result = (result + 16) >> 5;
                        CLIP_RESULT(result)
                        *(p_cur += outpitch) = result;
                        /* fourth pixel */
                        r2 = *(p_ref += inpitch);
                        result = (r3 + r2);
                        r3 = (r4 + r1);
                        result -= (r3 * 5);//result -= r3;  result -= (r3<<2);
                        r3 = (r5 + r0);
                        result += (r3 * 20);//result += (r3<<4);    result += (r3<<2);
                        result = (result + 16) >> 5;
                        CLIP_RESULT(result)
                        *(p_cur += outpitch) = result;
                        p_ref -= (inpitch << 1);  /* move back to center of the filter of the next one */
                    }
                    p_cur += (curr_offset - 3);
                }
            }
        }
    }

    return ;
}

void eVertInterp2MC(uint8 *in, int inpitch, int *out, int outpitch,
                    int blkwidth, int blkheight)
{
    int *p_cur;
    uint8 *p_ref, *tmp;
    int result, curr_offset, ref_offset;
    int j, r0, r1, r2, r3, r4, r5;

    p_cur = out;
    curr_offset = 1 - outpitch * (blkheight - 1); /* offset vertically back up and one pixel to right */
    ref_offset = blkheight * inpitch; /* for limit */

    for (j = 0; j < blkwidth; j++)
    {
        p_cur -= outpitch; /* compensate for the first offset */
        p_ref = in++;

        tmp = p_ref + ref_offset; /* limit */
        while (p_ref < tmp)
        {                           /* loop un-rolled */
            r0 = *(p_ref - (inpitch << 1));
            r1 = *(p_ref - inpitch);
            r2 = *p_ref;
            r3 = *(p_ref += inpitch);  /* modify pointer before loading */
            r4 = *(p_ref += inpitch);
            /* first pixel */
            r5 = *(p_ref += inpitch);
            result = (r0 + r5);
            r0 = (r1 + r4);
            result -= (r0 * 5);//result -= r0;  result -= (r0<<2);
            r0 = (r2 + r3);
            result += (r0 * 20);//result += (r0<<4);    result += (r0<<2);
            *(p_cur += outpitch) = result;
            /* second pixel */
            r0 = *(p_ref += inpitch);
            result = (r1 + r0);
            r1 = (r2 + r5);
            result -= (r1 * 5);//result -= r1;  result -= (r1<<2);
            r1 = (r3 + r4);
            result += (r1 * 20);//result += (r1<<4);    result += (r1<<2);
            *(p_cur += outpitch) = result;
            /* third pixel */
            r1 = *(p_ref += inpitch);
            result = (r2 + r1);
            r2 = (r3 + r0);
            result -= (r2 * 5);//result -= r2;  result -= (r2<<2);
            r2 = (r4 + r5);
            result += (r2 * 20);//result += (r2<<4);    result += (r2<<2);
            *(p_cur += outpitch) = result;
            /* fourth pixel */
            r2 = *(p_ref += inpitch);
            result = (r3 + r2);
            r3 = (r4 + r1);
            result -= (r3 * 5);//result -= r3;  result -= (r3<<2);
            r3 = (r5 + r0);
            result += (r3 * 20);//result += (r3<<4);    result += (r3<<2);
            *(p_cur += outpitch) = result;
            p_ref -= (inpitch << 1);  /* move back to center of the filter of the next one */
        }
        p_cur += curr_offset;
    }

    return ;
}

void eVertInterp3MC(int *in, int inpitch, uint8 *out, int outpitch,
                    int blkwidth, int blkheight, int dy)
{
    uint8 *p_cur;
    int *p_ref, *tmp;
    int result, result2, curr_offset, ref_offset;
    int j, r0, r1, r2, r3, r4, r5;

    p_cur = out;
    curr_offset = 1 - outpitch * (blkheight - 1); /* offset vertically back up and one pixel to right */
    ref_offset = blkheight * inpitch; /* for limit */

    if (dy&1)
    {
        dy = (dy >> 1) ? -(inpitch << 1) : -(inpitch << 1) - inpitch;

        for (j = 0; j < blkwidth; j++)
        {
            p_cur -= outpitch; /* compensate for the first offset */
            p_ref = in++;

            tmp = p_ref + ref_offset; /* limit */
            while (p_ref < tmp)
            {                           /* loop un-rolled */
                r0 = *(p_ref - (inpitch << 1));
                r1 = *(p_ref - inpitch);
                r2 = *p_ref;
                r3 = *(p_ref += inpitch);  /* modify pointer before loading */
                r4 = *(p_ref += inpitch);
                /* first pixel */
                r5 = *(p_ref += inpitch);
                result = (r0 + r5);
                r0 = (r1 + r4);
                result -= (r0 * 5);//result -= r0;  result -= (r0<<2);
                r0 = (r2 + r3);
                result += (r0 * 20);//result += (r0<<4);    result += (r0<<2);
                result = (result + 512) >> 10;
                CLIP_RESULT(result)
                result2 = ((p_ref[dy] + 16) >> 5);
                CLIP_RESULT(result2)
                /* 3/4 pel,  no need to clip */
                result = (result + result2 + 1);
                result = (result >> 1);
                *(p_cur += outpitch) = result;
                /* second pixel */
                r0 = *(p_ref += inpitch);
                result = (r1 + r0);
                r1 = (r2 + r5);
                result -= (r1 * 5);//result -= r1;  result -= (r1<<2);
                r1 = (r3 + r4);
                result += (r1 * 20);//result += (r1<<4);    result += (r1<<2);
                result = (result + 512) >> 10;
                CLIP_RESULT(result)
                result2 = ((p_ref[dy] + 16) >> 5);
                CLIP_RESULT(result2)
                /* 3/4 pel,  no need to clip */
                result = (result + result2 + 1);
                result = (result >> 1);
                *(p_cur += outpitch) = result;
                /* third pixel */
                r1 = *(p_ref += inpitch);
                result = (r2 + r1);
                r2 = (r3 + r0);
                result -= (r2 * 5);//result -= r2;  result -= (r2<<2);
                r2 = (r4 + r5);
                result += (r2 * 20);//result += (r2<<4);    result += (r2<<2);
                result = (result + 512) >> 10;
                CLIP_RESULT(result)
                result2 = ((p_ref[dy] + 16) >> 5);
                CLIP_RESULT(result2)
                /* 3/4 pel,  no need to clip */
                result = (result + result2 + 1);
                result = (result >> 1);
                *(p_cur += outpitch) = result;
                /* fourth pixel */
                r2 = *(p_ref += inpitch);
                result = (r3 + r2);
                r3 = (r4 + r1);
                result -= (r3 * 5);//result -= r3;  result -= (r3<<2);
                r3 = (r5 + r0);
                result += (r3 * 20);//result += (r3<<4);    result += (r3<<2);
                result = (result + 512) >> 10;
                CLIP_RESULT(result)
                result2 = ((p_ref[dy] + 16) >> 5);
                CLIP_RESULT(result2)
                /* 3/4 pel,  no need to clip */
                result = (result + result2 + 1);
                result = (result >> 1);
                *(p_cur += outpitch) = result;
                p_ref -= (inpitch << 1);  /* move back to center of the filter of the next one */
            }
            p_cur += curr_offset;
        }
    }
    else
    {
        for (j = 0; j < blkwidth; j++)
        {
            p_cur -= outpitch; /* compensate for the first offset */
            p_ref = in++;

            tmp = p_ref + ref_offset; /* limit */
            while (p_ref < tmp)
            {                           /* loop un-rolled */
                r0 = *(p_ref - (inpitch << 1));
                r1 = *(p_ref - inpitch);
                r2 = *p_ref;
                r3 = *(p_ref += inpitch);  /* modify pointer before loading */
                r4 = *(p_ref += inpitch);
                /* first pixel */
                r5 = *(p_ref += inpitch);
                result = (r0 + r5);
                r0 = (r1 + r4);
                result -= (r0 * 5);//result -= r0;  result -= (r0<<2);
                r0 = (r2 + r3);
                result += (r0 * 20);//result += (r0<<4);    result += (r0<<2);
                result = (result + 512) >> 10;
                CLIP_RESULT(result)
                *(p_cur += outpitch) = result;
                /* second pixel */
                r0 = *(p_ref += inpitch);
                result = (r1 + r0);
                r1 = (r2 + r5);
                result -= (r1 * 5);//result -= r1;  result -= (r1<<2);
                r1 = (r3 + r4);
                result += (r1 * 20);//result += (r1<<4);    result += (r1<<2);
                result = (result + 512) >> 10;
                CLIP_RESULT(result)
                *(p_cur += outpitch) = result;
                /* third pixel */
                r1 = *(p_ref += inpitch);
                result = (r2 + r1);
                r2 = (r3 + r0);
                result -= (r2 * 5);//result -= r2;  result -= (r2<<2);
                r2 = (r4 + r5);
                result += (r2 * 20);//result += (r2<<4);    result += (r2<<2);
                result = (result + 512) >> 10;
                CLIP_RESULT(result)
                *(p_cur += outpitch) = result;
                /* fourth pixel */
                r2 = *(p_ref += inpitch);
                result = (r3 + r2);
                r3 = (r4 + r1);
                result -= (r3 * 5);//result -= r3;  result -= (r3<<2);
                r3 = (r5 + r0);
                result += (r3 * 20);//result += (r3<<4);    result += (r3<<2);
                result = (result + 512) >> 10;
                CLIP_RESULT(result)
                *(p_cur += outpitch) = result;
                p_ref -= (inpitch << 1);  /* move back to center of the filter of the next one */
            }
            p_cur += curr_offset;
        }
    }

    return ;
}

void eDiagonalInterpMC(uint8 *in1, uint8 *in2, int inpitch,
                       uint8 *out, int outpitch,
                       int blkwidth, int blkheight)
{
    int j, i;
    int result;
    uint8 *p_cur, *p_ref, *p_tmp8, *tmp;
    int curr_offset, ref_offset;
    uint8 tmp_res[24][24], tmp_in[24][24];
    uint32 *p_tmp;
    uint32 pkres, tmp_result;
    int32 r0, r1, r2, r3, r4, r5;
    int32 r6, r7, r8, r9, r10, r13;

    ref_offset = inpitch - blkwidth;
    p_ref = in1 - 2;
    /* perform horizontal interpolation */
    /* not word-aligned */
    /* It is faster to read 1 byte at time to avoid calling CreateAlign */
    /*  if(((uint32)p_ref)&0x3)
        {
            CreateAlign(p_ref,inpitch,0,&tmp_in[0][0],blkwidth+8,blkheight);
            p_ref = &tmp_in[0][0];
            ref_offset = 24-blkwidth;
        }*/

    p_tmp = (uint32*) & (tmp_res[0][0]);
    for (j = blkheight; j > 0; j--)
    {
        r13 = 0;
        tmp = p_ref + blkwidth;

        //r0 = *((uint32*)p_ref);   /* d,c,b,a */
        //r1 = (r0>>8)&0xFF00FF;    /* 0,d,0,b */
        //r0 &= 0xFF00FF;           /* 0,c,0,a */
        /* It is faster to read 1 byte at a time */
        r0 = p_ref[0];
        r1 = p_ref[2];
        r0 |= (r1 << 16);           /* 0,c,0,a */
        r1 = p_ref[1];
        r2 = p_ref[3];
        r1 |= (r2 << 16);           /* 0,d,0,b */

        while (p_ref < tmp)
        {
            //r2 = *((uint32*)(p_ref+=4));/* h,g,f,e */
            //r3 = (r2>>8)&0xFF00FF;  /* 0,h,0,f */
            //r2 &= 0xFF00FF;           /* 0,g,0,e */
            /* It is faster to read 1 byte at a time */
            r2 = *(p_ref += 4);
            r3 = p_ref[2];
            r2 |= (r3 << 16);           /* 0,g,0,e */
            r3 = p_ref[1];
            r4 = p_ref[3];
            r3 |= (r4 << 16);           /* 0,h,0,f */

            r4 = r0 + r3;       /* c+h, a+f */
            r5 = r0 + r1;   /* c+d, a+b */
            r6 = r2 + r3;   /* g+h, e+f */
            r5 >>= 16;
            r5 |= (r6 << 16);   /* e+f, c+d */
            r4 += r5 * 20;      /* c+20*e+20*f+h, a+20*c+20*d+f */
            r4 += 0x100010; /* +16, +16 */
            r5 = r1 + r2;       /* d+g, b+e */
            r4 -= r5 * 5;       /* c-5*d+20*e+20*f-5*g+h, a-5*b+20*c+20*d-5*e+f */
            r4 >>= 5;
            r13 |= r4;      /* check clipping */
            r4 &= 0xFF00FF; /* mask */

            r5 = p_ref[4];  /* i */
            r6 = (r5 << 16);
            r5 = r6 | (r2 >> 16);/* 0,i,0,g */
            r5 += r1;       /* d+i, b+g */ /* r5 not free */
            r1 >>= 16;
            r1 |= (r3 << 16); /* 0,f,0,d */ /* r1 has changed */
            r1 += r2;       /* f+g, d+e */
            r5 += 20 * r1;  /* d+20f+20g+i, b+20d+20e+g */
            r0 >>= 16;
            r0 |= (r2 << 16); /* 0,e,0,c */ /* r0 has changed */
            r0 += r3;       /* e+h, c+f */
            r5 += 0x100010; /* 16,16 */
            r5 -= r0 * 5;       /* d-5e+20f+20g-5h+i, b-5c+20d+20e-5f+g */
            r5 >>= 5;
            r13 |= r5;      /* check clipping */
            r5 &= 0xFF00FF; /* mask */

            r4 |= (r5 << 8);    /* pack them together */
            *p_tmp++ = r4;
            r1 = r3;
            r0 = r2;
        }
        p_tmp += ((24 - blkwidth) >> 2); /* move to the next line */
        p_ref += ref_offset;  /*    ref_offset = inpitch-blkwidth; */

        if (r13&0xFF000700) /* need clipping */
        {
            /* move back to the beginning of the line */
            p_ref -= (ref_offset + blkwidth);   /* input */
            p_tmp -= 6; /* intermediate output */
            tmp = p_ref + blkwidth;
            while (p_ref < tmp)
            {
                r0 = *p_ref++;
                r1 = *p_ref++;
                r2 = *p_ref++;
                r3 = *p_ref++;
                r4 = *p_ref++;
                /* first pixel */
                r5 = *p_ref++;
                result = (r0 + r5);
                r0 = (r1 + r4);
                result -= (r0 * 5);//result -= r0;  result -= (r0<<2);
                r0 = (r2 + r3);
                result += (r0 * 20);//result += (r0<<4);    result += (r0<<2);
                result = (result + 16) >> 5;
                CLIP_RESULT(result)
                pkres = result;
                /* second pixel */
                r0 = *p_ref++;
                result = (r1 + r0);
                r1 = (r2 + r5);
                result -= (r1 * 5);//result -= r1;  result -= (r1<<2);
                r1 = (r3 + r4);
                result += (r1 * 20);//result += (r1<<4);    result += (r1<<2);
                result = (result + 16) >> 5;
                CLIP_RESULT(result)
                pkres |= (result << 8);
                /* third pixel */
                r1 = *p_ref++;
                result = (r2 + r1);
                r2 = (r3 + r0);
                result -= (r2 * 5);//result -= r2;  result -= (r2<<2);
                r2 = (r4 + r5);
                result += (r2 * 20);//result += (r2<<4);    result += (r2<<2);
                result = (result + 16) >> 5;
                CLIP_RESULT(result)
                pkres |= (result << 16);
                /* fourth pixel */
                r2 = *p_ref++;
                result = (r3 + r2);
                r3 = (r4 + r1);
                result -= (r3 * 5);//result -= r3;  result -= (r3<<2);
                r3 = (r5 + r0);
                result += (r3 * 20);//result += (r3<<4);    result += (r3<<2);
                result = (result + 16) >> 5;
                CLIP_RESULT(result)
                pkres |= (result << 24);

                *p_tmp++ = pkres; /* write 4 pixel */
                p_ref -= 5;
            }
            p_tmp += ((24 - blkwidth) >> 2); /* move to the next line */
            p_ref += ref_offset;  /*    ref_offset = inpitch-blkwidth; */
        }
    }

    /*  perform vertical interpolation */
    /* not word-aligned */
    if (((intptr_t)in2)&0x3)
    {
        eCreateAlign(in2, inpitch, -2, &tmp_in[0][0], blkwidth, blkheight + 5);
        in2 = &tmp_in[2][0];
        inpitch = 24;
    }

    p_cur = out;
    curr_offset = 1 - outpitch * (blkheight - 1); /* offset vertically up and one pixel right */
    pkres = blkheight * inpitch; /* reuse it for limit */

    curr_offset += 3;

    for (j = 0; j < blkwidth; j += 4, in2 += 4)
    {
        r13 = 0;
        p_ref = in2;
        p_tmp8 = &(tmp_res[0][j]); /* intermediate result */
        p_tmp8 -= 24;  /* compensate for the first offset */
        p_cur -= outpitch;  /* compensate for the first offset */
        tmp = p_ref + pkres; /* limit */
        while (p_ref < tmp)  /* the loop un-rolled  */
        {
            /* Read 1 byte at a time is too slow, too many read and pack ops, need to call CreateAlign */
            /*p_ref8 = p_ref-(inpitch<<1);          r0 = p_ref8[0];         r1 = p_ref8[2];
            r0 |= (r1<<16);         r6 = p_ref8[1];         r1 = p_ref8[3];
            r6 |= (r1<<16);         p_ref+=inpitch; */
            r0 = *((uint32*)(p_ref - (inpitch << 1))); /* load 4 bytes */
            p_ref += inpitch;
            r6 = (r0 >> 8) & 0xFF00FF; /* second and fourth byte */
            r0 &= 0xFF00FF;

            /*p_ref8 = p_ref+(inpitch<<1);
            r1 = p_ref8[0];         r7 = p_ref8[2];         r1 |= (r7<<16);
            r7 = p_ref8[1];         r2 = p_ref8[3];         r7 |= (r2<<16);*/
            r1 = *((uint32*)(p_ref + (inpitch << 1)));  /* r1, r7, ref[3] */
            r7 = (r1 >> 8) & 0xFF00FF;
            r1 &= 0xFF00FF;

            r0 += r1;
            r6 += r7;

            /*r2 = p_ref[0];            r8 = p_ref[2];          r2 |= (r8<<16);
            r8 = p_ref[1];          r1 = p_ref[3];          r8 |= (r1<<16);*/
            r2 = *((uint32*)p_ref); /* r2, r8, ref[1] */
            r8 = (r2 >> 8) & 0xFF00FF;
            r2 &= 0xFF00FF;

            /*p_ref8 = p_ref-inpitch;           r1 = p_ref8[0];         r7 = p_ref8[2];
            r1 |= (r7<<16);         r1 += r2;           r7 = p_ref8[1];
            r2 = p_ref8[3];         r7 |= (r2<<16);*/
            r1 = *((uint32*)(p_ref - inpitch)); /* r1, r7, ref[0] */
            r7 = (r1 >> 8) & 0xFF00FF;
            r1 &= 0xFF00FF;
            r1 += r2;

            r7 += r8;

            r0 += 20 * r1;
            r6 += 20 * r7;
            r0 += 0x100010;
            r6 += 0x100010;

            /*p_ref8 = p_ref-(inpitch<<1);          r2 = p_ref8[0];         r8 = p_ref8[2];
            r2 |= (r8<<16);         r8 = p_ref8[1];         r1 = p_ref8[3];         r8 |= (r1<<16);*/
            r2 = *((uint32*)(p_ref - (inpitch << 1))); /* r2, r8, ref[-1] */
            r8 = (r2 >> 8) & 0xFF00FF;
            r2 &= 0xFF00FF;

            /*p_ref8 = p_ref+inpitch;           r1 = p_ref8[0];         r7 = p_ref8[2];
            r1 |= (r7<<16);         r1 += r2;           r7 = p_ref8[1];
            r2 = p_ref8[3];         r7 |= (r2<<16);*/
            r1 = *((uint32*)(p_ref + inpitch)); /* r1, r7, ref[2] */
            r7 = (r1 >> 8) & 0xFF00FF;
            r1 &= 0xFF00FF;
            r1 += r2;

            r7 += r8;

            r0 -= 5 * r1;
            r6 -= 5 * r7;

            r0 >>= 5;
            r6 >>= 5;
            /* clip */
            r13 |= r6;
            r13 |= r0;
            //CLIPPACK(r6,result)
            /* add with horizontal results */
            r10 = *((uint32*)(p_tmp8 += 24));
            r9 = (r10 >> 8) & 0xFF00FF;
            r10 &= 0xFF00FF;

            r0 += r10;
            r0 += 0x10001;
            r0 = (r0 >> 1) & 0xFF00FF;   /* mask to 8 bytes */

            r6 += r9;
            r6 += 0x10001;
            r6 = (r6 >> 1) & 0xFF00FF;   /* mask to 8 bytes */

            r0 |= (r6 << 8);  /* pack it back */
            *((uint32*)(p_cur += outpitch)) = r0;
        }
        p_cur += curr_offset; /* offset to the next pixel */
        if (r13 & 0xFF000700) /* this column need clipping */
        {
            p_cur -= 4;
            for (i = 0; i < 4; i++)
            {
                p_ref = in2 + i;
                p_tmp8 = &(tmp_res[0][j+i]); /* intermediate result */
                p_tmp8 -= 24;  /* compensate for the first offset */
                p_cur -= outpitch;  /* compensate for the first offset */
                tmp = p_ref + pkres; /* limit */
                while (p_ref < tmp)  /* the loop un-rolled  */
                {
                    r0 = *(p_ref - (inpitch << 1));
                    r1 = *(p_ref - inpitch);
                    r2 = *p_ref;
                    r3 = *(p_ref += inpitch);  /* modify pointer before loading */
                    r4 = *(p_ref += inpitch);
                    /* first pixel */
                    r5 = *(p_ref += inpitch);
                    result = (r0 + r5);
                    r0 = (r1 + r4);
                    result -= (r0 * 5);//result -= r0;  result -= (r0<<2);
                    r0 = (r2 + r3);
                    result += (r0 * 20);//result += (r0<<4);    result += (r0<<2);
                    result = (result + 16) >> 5;
                    CLIP_RESULT(result)
                    tmp_result = *(p_tmp8 += 24);  /* modify pointer before loading */
                    result = (result + tmp_result + 1);  /* no clip */
                    result = (result >> 1);
                    *(p_cur += outpitch) = result;
                    /* second pixel */
                    r0 = *(p_ref += inpitch);
                    result = (r1 + r0);
                    r1 = (r2 + r5);
                    result -= (r1 * 5);//result -= r1;  result -= (r1<<2);
                    r1 = (r3 + r4);
                    result += (r1 * 20);//result += (r1<<4);    result += (r1<<2);
                    result = (result + 16) >> 5;
                    CLIP_RESULT(result)
                    tmp_result = *(p_tmp8 += 24);  /* intermediate result */
                    result = (result + tmp_result + 1);  /* no clip */
                    result = (result >> 1);
                    *(p_cur += outpitch) = result;
                    /* third pixel */
                    r1 = *(p_ref += inpitch);
                    result = (r2 + r1);
                    r2 = (r3 + r0);
                    result -= (r2 * 5);//result -= r2;  result -= (r2<<2);
                    r2 = (r4 + r5);
                    result += (r2 * 20);//result += (r2<<4);    result += (r2<<2);
                    result = (result + 16) >> 5;
                    CLIP_RESULT(result)
                    tmp_result = *(p_tmp8 += 24);  /* intermediate result */
                    result = (result + tmp_result + 1);  /* no clip */
                    result = (result >> 1);
                    *(p_cur += outpitch) = result;
                    /* fourth pixel */
                    r2 = *(p_ref += inpitch);
                    result = (r3 + r2);
                    r3 = (r4 + r1);
                    result -= (r3 * 5);//result -= r3;  result -= (r3<<2);
                    r3 = (r5 + r0);
                    result += (r3 * 20);//result += (r3<<4);    result += (r3<<2);
                    result = (result + 16) >> 5;
                    CLIP_RESULT(result)
                    tmp_result = *(p_tmp8 += 24);  /* intermediate result */
                    result = (result + tmp_result + 1);  /* no clip */
                    result = (result >> 1);
                    *(p_cur += outpitch) = result;
                    p_ref -= (inpitch << 1);  /* move back to center of the filter of the next one */
                }
                p_cur += (curr_offset - 3);
            }
        }
    }

    return ;
}

/* position G */
void eFullPelMC(uint8 *in, int inpitch, uint8 *out, int outpitch,
                int blkwidth, int blkheight)
{
    int i, j;
    int offset_in = inpitch - blkwidth;
    int offset_out = outpitch - blkwidth;
    uint32 temp;
    uint8 byte;

    if (((intptr_t)in)&3)
    {
        for (j = blkheight; j > 0; j--)
        {
            for (i = blkwidth; i > 0; i -= 4)
            {
                temp = *in++;
                byte = *in++;
                temp |= (byte << 8);
                byte = *in++;
                temp |= (byte << 16);
                byte = *in++;
                temp |= (byte << 24);

                *((uint32*)out) = temp; /* write 4 bytes */
                out += 4;
            }
            out += offset_out;
            in += offset_in;
        }
    }
    else
    {
        for (j = blkheight; j > 0; j--)
        {
            for (i = blkwidth; i > 0; i -= 4)
            {
                temp = *((uint32*)in);
                *((uint32*)out) = temp;
                in += 4;
                out += 4;
            }
            out += offset_out;
            in += offset_in;
        }
    }
    return ;
}

void ePadChroma(uint8 *ref, int picwidth, int picheight, int picpitch, int x_pos, int y_pos)
{
    int pad_height;
    int pad_width;
    uint8 *start;
    uint32 word1, word2, word3;
    int offset, j;


    pad_height = 8 + ((y_pos & 7) ? 1 : 0);
    pad_width = 8 + ((x_pos & 7) ? 1 : 0);

    y_pos >>= 3;
    x_pos >>= 3;
    // pad vertical first
    if (y_pos < 0) // need to pad up
    {
        if (x_pos < -8) start = ref - 8;
        else if (x_pos + pad_width > picwidth + 7) start = ref + picwidth + 7 - pad_width;
        else start = ref + x_pos;

        /* word-align start */
        offset = (intptr_t)start & 0x3;
        if (offset) start -= offset;

        word1 = *((uint32*)start);
        word2 = *((uint32*)(start + 4));
        word3 = *((uint32*)(start + 8));

        /* pad up N rows */
        j = -y_pos;
        if (j > 8) j = 8;
        while (j--)
        {
            *((uint32*)(start -= picpitch)) = word1;
            *((uint32*)(start + 4)) = word2;
            *((uint32*)(start + 8)) = word3;
        }

    }
    else if (y_pos + pad_height >= picheight) /* pad down */
    {
        if (x_pos < -8) start = ref + picpitch * (picheight - 1) - 8;
        else if (x_pos + pad_width > picwidth + 7) start = ref + picpitch * (picheight - 1) +
                    picwidth + 7 - pad_width;
        else    start = ref + picpitch * (picheight - 1) + x_pos;

        /* word-align start */
        offset = (intptr_t)start & 0x3;
        if (offset) start -= offset;

        word1 = *((uint32*)start);
        word2 = *((uint32*)(start + 4));
        word3 = *((uint32*)(start + 8));

        /* pad down N rows */
        j = y_pos + pad_height - picheight;
        if (j > 8) j = 8;
        while (j--)
        {
            *((uint32*)(start += picpitch)) = word1;
            *((uint32*)(start + 4)) = word2;
            *((uint32*)(start + 8)) = word3;
        }
    }

    /* now pad horizontal */
    if (x_pos < 0) // pad left
    {
        if (y_pos < -8) start = ref - (picpitch << 3);
        else if (y_pos + pad_height > picheight + 7) start = ref + (picheight + 7 - pad_height) * picpitch;
        else start = ref + y_pos * picpitch;

        // now pad left 8 pixels for pad_height rows */
        j = pad_height;
        start -= picpitch;
        while (j--)
        {
            word1 = *(start += picpitch);
            word1 |= (word1 << 8);
            word1 |= (word1 << 16);
            *((uint32*)(start - 8)) = word1;
            *((uint32*)(start - 4)) = word1;
        }
    }
    else if (x_pos + pad_width >= picwidth) /* pad right */
    {
        if (y_pos < -8) start = ref - (picpitch << 3) + picwidth - 1;
        else if (y_pos + pad_height > picheight + 7) start = ref + (picheight + 7 - pad_height) * picpitch + picwidth - 1;
        else start = ref + y_pos * picpitch + picwidth - 1;

        // now pad right 8 pixels for pad_height rows */
        j = pad_height;
        start -= picpitch;
        while (j--)
        {
            word1 = *(start += picpitch);
            word1 |= (word1 << 8);
            word1 |= (word1 << 16);
            *((uint32*)(start + 1)) = word1;
            *((uint32*)(start + 5)) = word1;
        }
    }

    return ;
}


void eChromaMotionComp(uint8 *ref, int picwidth, int picheight,
                       int x_pos, int y_pos,
                       uint8 *pred, int picpitch,
                       int blkwidth, int blkheight)
{
    int dx, dy;
    int offset_dx, offset_dy;
    int index;

    ePadChroma(ref, picwidth, picheight, picpitch, x_pos, y_pos);

    dx = x_pos & 7;
    dy = y_pos & 7;
    offset_dx = (dx + 7) >> 3;
    offset_dy = (dy + 7) >> 3;
    x_pos = x_pos >> 3;  /* round it to full-pel resolution */
    y_pos = y_pos >> 3;

    ref += y_pos * picpitch + x_pos;

    index = offset_dx + (offset_dy << 1) + ((blkwidth << 1) & 0x7);

    (*(eChromaMC_SIMD[index]))(ref, picpitch , dx, dy, pred, picpitch, blkwidth, blkheight);
    return ;
}


/* SIMD routines, unroll the loops in vertical direction, decreasing loops (things to be done) */
void eChromaDiagonalMC_SIMD(uint8 *pRef, int srcPitch, int dx, int dy,
                            uint8 *pOut, int predPitch, int blkwidth, int blkheight)
{
    int32 r0, r1, r2, r3, result0, result1;
    uint8 temp[288];
    uint8 *ref, *out;
    int i, j;
    int dx_8 = 8 - dx;
    int dy_8 = 8 - dy;

    /* horizontal first */
    out = temp;
    for (i = 0; i < blkheight + 1; i++)
    {
        ref = pRef;
        r0 = ref[0];
        for (j = 0; j < blkwidth; j += 4)
        {
            r0 |= (ref[2] << 16);
            result0 = dx_8 * r0;

            r1 = ref[1] | (ref[3] << 16);
            result0 += dx * r1;
            *(int32 *)out = result0;

            result0 = dx_8 * r1;

            r2 = ref[4];
            r0 = r0 >> 16;
            r1 = r0 | (r2 << 16);
            result0 += dx * r1;
            *(int32 *)(out + 16) = result0;

            ref += 4;
            out += 4;
            r0 = r2;
        }
        pRef += srcPitch;
        out += (32 - blkwidth);
    }

//  pRef -= srcPitch*(blkheight+1);
    ref = temp;

    for (j = 0; j < blkwidth; j += 4)
    {
        r0 = *(int32 *)ref;
        r1 = *(int32 *)(ref + 16);
        ref += 32;
        out = pOut;
        for (i = 0; i < (blkheight >> 1); i++)
        {
            result0 = dy_8 * r0 + 0x00200020;
            r2 = *(int32 *)ref;
            result0 += dy * r2;
            result0 >>= 6;
            result0 &= 0x00FF00FF;
            r0 = r2;

            result1 = dy_8 * r1 + 0x00200020;
            r3 = *(int32 *)(ref + 16);
            result1 += dy * r3;
            result1 >>= 6;
            result1 &= 0x00FF00FF;
            r1 = r3;
            *(int32 *)out = result0 | (result1 << 8);
            out += predPitch;
            ref += 32;

            result0 = dy_8 * r0 + 0x00200020;
            r2 = *(int32 *)ref;
            result0 += dy * r2;
            result0 >>= 6;
            result0 &= 0x00FF00FF;
            r0 = r2;

            result1 = dy_8 * r1 + 0x00200020;
            r3 = *(int32 *)(ref + 16);
            result1 += dy * r3;
            result1 >>= 6;
            result1 &= 0x00FF00FF;
            r1 = r3;
            *(int32 *)out = result0 | (result1 << 8);
            out += predPitch;
            ref += 32;
        }
        pOut += 4;
        ref = temp + 4; /* since it can only iterate twice max */
    }
    return;
}

void eChromaHorizontalMC_SIMD(uint8 *pRef, int srcPitch, int dx, int dy,
                              uint8 *pOut, int predPitch, int blkwidth, int blkheight)
{
    (void)(dy);

    int32 r0, r1, r2, result0, result1;
    uint8 *ref, *out;
    int i, j;
    int dx_8 = 8 - dx;

    /* horizontal first */
    for (i = 0; i < blkheight; i++)
    {
        ref = pRef;
        out = pOut;

        r0 = ref[0];
        for (j = 0; j < blkwidth; j += 4)
        {
            r0 |= (ref[2] << 16);
            result0 = dx_8 * r0 + 0x00040004;

            r1 = ref[1] | (ref[3] << 16);
            result0 += dx * r1;
            result0 >>= 3;
            result0 &= 0x00FF00FF;

            result1 = dx_8 * r1 + 0x00040004;

            r2 = ref[4];
            r0 = r0 >> 16;
            r1 = r0 | (r2 << 16);
            result1 += dx * r1;
            result1 >>= 3;
            result1 &= 0x00FF00FF;

            *(int32 *)out = result0 | (result1 << 8);

            ref += 4;
            out += 4;
            r0 = r2;
        }

        pRef += srcPitch;
        pOut += predPitch;
    }
    return;
}

void eChromaVerticalMC_SIMD(uint8 *pRef, int srcPitch, int dx, int dy,
                            uint8 *pOut, int predPitch, int blkwidth, int blkheight)
{
    (void)(dx);

    int32 r0, r1, r2, r3, result0, result1;
    int i, j;
    uint8 *ref, *out;
    int dy_8 = 8 - dy;
    /* vertical first */
    for (i = 0; i < blkwidth; i += 4)
    {
        ref = pRef;
        out = pOut;

        r0 = ref[0] | (ref[2] << 16);
        r1 = ref[1] | (ref[3] << 16);
        ref += srcPitch;
        for (j = 0; j < blkheight; j++)
        {
            result0 = dy_8 * r0 + 0x00040004;
            r2 = ref[0] | (ref[2] << 16);
            result0 += dy * r2;
            result0 >>= 3;
            result0 &= 0x00FF00FF;
            r0 = r2;

            result1 = dy_8 * r1 + 0x00040004;
            r3 = ref[1] | (ref[3] << 16);
            result1 += dy * r3;
            result1 >>= 3;
            result1 &= 0x00FF00FF;
            r1 = r3;
            *(int32 *)out = result0 | (result1 << 8);
            ref += srcPitch;
            out += predPitch;
        }
        pOut += 4;
        pRef += 4;
    }
    return;
}

void eChromaDiagonalMC2_SIMD(uint8 *pRef, int srcPitch, int dx, int dy,
                             uint8 *pOut,  int predPitch, int blkwidth, int blkheight)
{
    (void)(blkwidth);

    int32 r0, r1, temp0, temp1, result;
    int32 temp[9];
    int32 *out;
    int i, r_temp;
    int dy_8 = 8 - dy;

    /* horizontal first */
    out = temp;
    for (i = 0; i < blkheight + 1; i++)
    {
        r_temp = pRef[1];
        temp0 = (pRef[0] << 3) + dx * (r_temp - pRef[0]);
        temp1 = (r_temp << 3) + dx * (pRef[2] - r_temp);
        r0 = temp0 | (temp1 << 16);
        *out++ = r0;
        pRef += srcPitch;
    }

    pRef -= srcPitch * (blkheight + 1);

    out = temp;

    r0 = *out++;

    for (i = 0; i < blkheight; i++)
    {
        result = dy_8 * r0 + 0x00200020;
        r1 = *out++;
        result += dy * r1;
        result >>= 6;
        result &= 0x00FF00FF;
        *(int16 *)pOut = (result >> 8) | (result & 0xFF);
        r0 = r1;
        pOut += predPitch;
    }
    return;
}

void eChromaHorizontalMC2_SIMD(uint8 *pRef, int srcPitch, int dx, int dy,
                               uint8 *pOut, int predPitch, int blkwidth, int blkheight)
{
    (void)(dy);
    (void)(blkwidth);

    int i, temp, temp0, temp1;

    /* horizontal first */
    for (i = 0; i < blkheight; i++)
    {
        temp = pRef[1];
        temp0 = ((pRef[0] << 3) + dx * (temp - pRef[0]) + 4) >> 3;
        temp1 = ((temp << 3) + dx * (pRef[2] - temp) + 4) >> 3;

        *(int16 *)pOut = temp0 | (temp1 << 8);
        pRef += srcPitch;
        pOut += predPitch;

    }
    return;
}
void eChromaVerticalMC2_SIMD(uint8 *pRef, int srcPitch, int dx, int dy,
                             uint8 *pOut, int predPitch, int blkwidth, int blkheight)
{
    (void)(dx);
    (void)(blkwidth);

    int32 r0, r1, result;
    int i;
    int dy_8 = 8 - dy;
    r0 = pRef[0] | (pRef[1] << 16);
    pRef += srcPitch;
    for (i = 0; i < blkheight; i++)
    {
        result = dy_8 * r0 + 0x00040004;
        r1 = pRef[0] | (pRef[1] << 16);
        result += dy * r1;
        result >>= 3;
        result &= 0x00FF00FF;
        *(int16 *)pOut = (result >> 8) | (result & 0xFF);
        r0 = r1;
        pRef += srcPitch;
        pOut += predPitch;
    }
    return;
}

void eChromaFullMC_SIMD(uint8 *pRef, int srcPitch, int dx, int dy,
                        uint8 *pOut, int predPitch, int blkwidth, int blkheight)
{
    (void)(dx);
    (void)(dy);

    int i, j;
    int offset_in = srcPitch - blkwidth;
    int offset_out = predPitch - blkwidth;
    uint16 temp;
    uint8 byte;

    if (((intptr_t)pRef)&1)
    {
        for (j = blkheight; j > 0; j--)
        {
            for (i = blkwidth; i > 0; i -= 2)
            {
                temp = *pRef++;
                byte = *pRef++;
                temp |= (byte << 8);
                *((uint16*)pOut) = temp; /* write 2 bytes */
                pOut += 2;
            }
            pOut += offset_out;
            pRef += offset_in;
        }
    }
    else
    {
        for (j = blkheight; j > 0; j--)
        {
            for (i = blkwidth; i > 0; i -= 2)
            {
                temp = *((uint16*)pRef);
                *((uint16*)pOut) = temp;
                pRef += 2;
                pOut += 2;
            }
            pOut += offset_out;
            pRef += offset_in;
        }
    }
    return ;
}
