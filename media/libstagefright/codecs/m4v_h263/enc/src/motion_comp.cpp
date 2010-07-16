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
#include "mp4lib_int.h"
#include "mp4enc_lib.h"

//const static Int roundtab4[] = {0,1,1,1};
//const static Int roundtab8[] = {0,0,1,1,1,1,1,2};
//const static Int roundtab12[] = {0,0,0,1,1,1,1,1,1,1,2,2};
const static Int roundtab16[] = {0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2};

#define FORWARD_MODE    1
#define BACKWARD_MODE   2
#define BIDIRECTION_MODE    3
#define DIRECT_MODE         4

#ifdef __cplusplus
extern "C"
{
#endif
    /*Function Prototype */
    /* no-edge padding */
    Int EncGetPredOutside(Int xpos, Int ypos, UChar *c_prev, UChar *rec,
    Int width, Int height, Int rnd1);

    void Copy_MB_from_Vop(UChar *comp, Int yChan[][NCOEFF_BLOCK], Int width);
    void Copy_B_from_Vop(UChar *comp, Int cChan[], Int width);
    void Copy_MB_into_Vop(UChar *comp, Int yChan[][NCOEFF_BLOCK], Int width);
    void Copy_B_into_Vop(UChar *comp, Int cChan[], Int width);
    void get_MB(UChar *c_prev, UChar *c_prev_u  , UChar *c_prev_v,
                Short mb[6][64], Int lx, Int lx_uv);

    Int GetPredAdvBy0x0(
        UChar *c_prev,      /* i */
        UChar *pred_block,      /* i */
        Int lx,     /* i */
        Int rnd1 /* i */
    );

    Int GetPredAdvBy0x1(
        UChar *c_prev,      /* i */
        UChar *pred_block,      /* i */
        Int lx,     /* i */
        Int rnd1 /* i */
    );

    Int GetPredAdvBy1x0(
        UChar *c_prev,      /* i */
        UChar *pred_block,      /* i */
        Int lx,     /* i */
        Int rnd1 /* i */
    );

    Int GetPredAdvBy1x1(
        UChar *c_prev,      /* i */
        UChar *pred_block,      /* i */
        Int lx,     /* i */
        Int rnd1 /* i */
    );

    static Int(*const GetPredAdvBTable[2][2])(UChar*, UChar*, Int, Int) =
    {
        {&GetPredAdvBy0x0, &GetPredAdvBy0x1},
        {&GetPredAdvBy1x0, &GetPredAdvBy1x1}
    };


#ifdef __cplusplus
}
#endif


/* ======================================================================== */
/*  Function : getMotionCompensatedMB( )                                    */
/*  Date     : 4/17/2001                                                    */
/*  Purpose  : Get the motion compensate block into video->predictionMB     */
/*              and generate video->predictionErrorMB                       */
/*              modified from MBMotionComp() function in the decoder        */
/*  In/out   :                                                              */
/*  Return   :                                                              */
/*  Modified :                                                              */
/* ======================================================================== */

void getMotionCompensatedMB(VideoEncData *video, Int ind_x, Int ind_y, Int offset)
{
    Vop *prevVop = video->forwardRefVop; //reference frame
    Vop *currVop = video->currVop;
    Int mbnum = video->mbnum;       //mb index
    MOT *mot = video->mot[mbnum];
    Int ypos, xpos;
    UChar *c_prev, *cu_prev, *cv_prev;
    UChar *c_rec, *cu_rec, *cv_rec;
    Int height, pitch, pitch_uv, height_uv;
    Int mode = video->headerInfo.Mode[mbnum];  /* get mode */
    Int dx, dy;
    Int xpred, ypred;
    Int xsum, ysum;
    Int round1;

    OSCL_UNUSED_ARG(offset);

    round1 = (Int)(1 - video->currVop->roundingType);

    pitch  = currVop->pitch;
    height = currVop->height;
    pitch_uv  = pitch >> 1;
    height_uv = height >> 1;

    ypos = ind_y << 4 ;
    xpos = ind_x << 4 ;

    c_rec = video->predictedMB;
    cu_rec = video->predictedMB + 256;
    cv_rec = video->predictedMB + 264;

    if (mode == MODE_INTER || mode == MODE_INTER_Q)
    {
        /* Motion vector in x direction       */
        dx = mot[0].x;
        dy = mot[0].y;

        c_prev  = prevVop->yChan;

        xpred = (xpos << 1) + dx ;
        ypred = (ypos << 1) + dy ;

        /* Call function that performs luminance prediction */
        EncPrediction_INTER(xpred, ypred, c_prev, c_rec,
                            pitch, round1);

        if ((dx & 3) == 0)  dx = dx >> 1;
        else        dx = (dx >> 1) | 1;

        if ((dy & 3) == 0)      dy = dy >> 1;
        else        dy = (dy >> 1) | 1;

        xpred = xpos + dx;
        ypred = ypos + dy;

        cu_prev = prevVop->uChan;
        cv_prev = prevVop->vChan;

        EncPrediction_Chrom(xpred, ypred, cu_prev, cv_prev, cu_rec, cv_rec,
                            pitch_uv, (currVop->width) >> 1, height_uv, round1);
    }
#ifndef NO_INTER4V
    else if (mode == MODE_INTER4V)
    {
        c_prev  = prevVop->yChan;
        cu_prev = prevVop->uChan;
        cv_prev = prevVop->vChan;

        EncPrediction_INTER4V(xpos, ypos, mot, c_prev, c_rec,
                              pitch, round1);

        xsum = mot[1].x + mot[2].x + mot[3].x + mot[4].x;
        ysum = mot[1].y + mot[2].y + mot[3].y + mot[4].y;

        dx = PV_SIGN(xsum) * (roundtab16[(PV_ABS(xsum)) & 0xF] +
                              (((PV_ABS(xsum)) >> 4) << 1));
        dy = PV_SIGN(ysum) * (roundtab16[(PV_ABS(ysum)) & 0xF] +
                              (((PV_ABS(ysum)) >> 4) << 1));

        ypred = ypos + dy;
        xpred = xpos + dx;

        EncPrediction_Chrom(xpred, ypred, cu_prev, cv_prev, cu_rec, cv_rec,
                            pitch_uv, (currVop->width) >> 1, height_uv, round1);
    }
#endif
    else
    {
        ;//printf("Error, MODE_SKIPPED is not decided yet!\n");
    }

    return ;
}

/***************************************************************************
    Function:   EncPrediction_INTER
    Date:       04/17/2001
    Purpose:    Get predicted area for luminance and compensate with the residue.
                Modified from luminance_pred_mode_inter() in decoder.
***************************************************************************/

void EncPrediction_INTER(
    Int xpred,          /* i */
    Int ypred,          /* i */
    UChar *c_prev,          /* i */
    UChar *c_rec,       /* i */
    Int lx,         /* i */
    Int round1          /* i */
)
{
    c_prev += (xpred >> 1) + ((ypred >> 1) * lx);

    GetPredAdvBTable[ypred&1][xpred&1](c_prev, c_rec, lx, round1);

    c_prev += B_SIZE;
    c_rec += B_SIZE;

    GetPredAdvBTable[ypred&1][xpred&1](c_prev, c_rec, lx, round1);

    c_prev += (lx << 3) - B_SIZE;
    c_rec += (16 << 3) - B_SIZE; /* padding */

    GetPredAdvBTable[ypred&1][xpred&1](c_prev, c_rec, lx, round1);

    c_prev += B_SIZE;
    c_rec += B_SIZE;

    GetPredAdvBTable[ypred&1][xpred&1](c_prev, c_rec, lx, round1);

    return;
}

#ifndef NO_INTER4V
/***************************************************************************
    Function:   EncPrediction_INTER4V
    Date:       04/17/2001
    Purpose:    Get predicted area for luminance and compensate with the residue.
                Modified from luminance_pred_mode_inter4v() in decoder.
***************************************************************************/

void EncPrediction_INTER4V(
    Int xpos,           /* i */
    Int ypos,           /* i */
    MOT *mot,           /* i */
    UChar *c_prev,          /* i */
    UChar *c_rec,           /* i */
    Int lx,         /* i */
    Int round1          /* i */
)
{
    Int ypred, xpred;

    xpred = (Int)((xpos << 1) + mot[1].x);
    ypred = (Int)((ypos << 1) + mot[1].y);

    GetPredAdvBTable[ypred&1][xpred&1](c_prev + (xpred >> 1) + ((ypred >> 1)*lx),
                                       c_rec, lx, round1);

    c_rec += B_SIZE;

    xpred = (Int)(((xpos + B_SIZE) << 1) + mot[2].x);
    ypred = (Int)((ypos << 1) + mot[2].y);

    GetPredAdvBTable[ypred&1][xpred&1](c_prev + (xpred >> 1) + ((ypred >> 1)*lx),
                                       c_rec, lx, round1);

    c_rec += (16 << 3) - B_SIZE; /* padding */

    xpred = (Int)((xpos << 1) + mot[3].x);
    ypred = (Int)(((ypos + B_SIZE) << 1) + mot[3].y);

    GetPredAdvBTable[ypred&1][xpred&1](c_prev + (xpred >> 1) + ((ypred >> 1)*lx),
                                       c_rec, lx, round1);

    c_rec += B_SIZE;

    xpred = (Int)(((xpos + B_SIZE) << 1) + mot[4].x);
    ypred = (Int)(((ypos + B_SIZE) << 1) + mot[4].y);

    GetPredAdvBTable[ypred&1][xpred&1](c_prev + (xpred >> 1) + ((ypred >> 1)*lx),
                                       c_rec, lx, round1);

    return;
}
#endif /* NO_INTER4V */

/***************************************************************************
    Function:   EncPrediction_Chrom
    Date:       04/17/2001
    Purpose:    Get predicted area for chrominance and compensate with the residue.
                Modified from chrominance_pred() in decoder.
***************************************************************************/

void EncPrediction_Chrom(
    Int xpred,          /* i */
    Int ypred,          /* i */
    UChar *cu_prev,         /* i */
    UChar *cv_prev,         /* i */
    UChar *cu_rec,
    UChar *cv_rec,
    Int lx,
    Int width_uv,           /* i */
    Int height_uv,          /* i */
    Int round1          /* i */
)
{
    /* check whether the MV points outside the frame */
    /* Compute prediction for Chrominance b block (block[4]) */
    if (xpred >= 0 && xpred <= ((width_uv << 1) - (2*B_SIZE)) && ypred >= 0 &&
            ypred <= ((height_uv << 1) - (2*B_SIZE)))
    {
        /*****************************/
        /* (x,y) is inside the frame */
        /*****************************/

        /* Compute prediction for Chrominance b (block[4]) */
        GetPredAdvBTable[ypred&1][xpred&1](cu_prev + (xpred >> 1) + ((ypred >> 1)*lx),
                                           cu_rec, lx, round1);

        /* Compute prediction for Chrominance r (block[5]) */
        GetPredAdvBTable[ypred&1][xpred&1](cv_prev + (xpred >> 1) + ((ypred >> 1)*lx),
                                           cv_rec,  lx, round1);
    }
    else
    {
        /******************************/
        /* (x,y) is outside the frame */
        /******************************/

        /* Compute prediction for Chrominance b (block[4]) */
        EncGetPredOutside(xpred, ypred,
                          cu_prev, cu_rec,
                          width_uv, height_uv, round1);

        /* Compute prediction for Chrominance r (block[5]) */
        EncGetPredOutside(xpred, ypred,
                          cv_prev, cv_rec,
                          width_uv, height_uv, round1);
    }

    return;
}
/***************************************************************************
    Function:   GetPredAdvancedB
    Date:       04/17/2001
    Purpose:    Get predicted area (block) and compensate with the residue.
                - modified from GetPredAdvancedBAdd in decoder.
    Intput/Output:
    Modified:
***************************************************************************/

Int GetPredAdvBy0x0(
    UChar *prev,        /* i */
    UChar *rec,     /* i */
    Int lx,     /* i */
    Int rnd /* i */
)
{
    Int i;      /* loop variable */
    ULong  pred_word, word1, word2;
    Int tmp;

    OSCL_UNUSED_ARG(rnd);

    /* initialize offset to adjust pixel counter */
    /*    the next row; full-pel resolution      */

    tmp = (ULong)prev & 0x3;

    if (tmp == 0)  /* word-aligned */
    {
        rec -= 16; /* preset */
        prev -= lx;

        for (i = 8; i > 0; i--)
        {
            *((ULong*)(rec += 16)) = *((ULong*)(prev += lx));
            *((ULong*)(rec + 4)) = *((ULong*)(prev + 4));
        }
        return 1;
    }
    else if (tmp == 1) /* first position */
    {
        prev--; /* word-aligned */
        rec -= 16; /* preset */
        prev -= lx;

        for (i = 8; i > 0; i--)
        {
            word1 = *((ULong*)(prev += lx)); /* read 4 bytes, b4 b3 b2 b1 */
            word2 = *((ULong*)(prev + 4));  /* read 4 bytes, b8 b7 b6 b5 */
            word1 >>= 8; /* 0 b4 b3 b2 */
            pred_word = word1 | (word2 << 24);  /* b5 b4 b3 b2 */
            *((ULong*)(rec += 16)) = pred_word;

            word1 = *((ULong*)(prev + 8)); /* b12 b11 b10 b9 */
            word2 >>= 8; /* 0 b8 b7 b6 */
            pred_word = word2 | (word1 << 24); /* b9 b8 b7 b6 */
            *((ULong*)(rec + 4)) = pred_word;
        }

        return 1;
    }
    else if (tmp == 2) /* second position */
    {
        prev -= 2; /* word1-aligned */
        rec -= 16; /* preset */
        prev -= lx;

        for (i = 8; i > 0; i--)
        {
            word1 = *((ULong*)(prev += lx)); /* read 4 bytes, b4 b3 b2 b1 */
            word2 = *((ULong*)(prev + 4));  /* read 4 bytes, b8 b7 b6 b5 */
            word1 >>= 16; /* 0 0 b4 b3 */
            pred_word = word1 | (word2 << 16);  /* b6 b5 b4 b3 */
            *((ULong*)(rec += 16)) = pred_word;

            word1 = *((ULong*)(prev + 8)); /* b12 b11 b10 b9 */
            word2 >>= 16; /* 0 0 b8 b7 */
            pred_word = word2 | (word1 << 16); /* b10 b9 b8 b7 */
            *((ULong*)(rec + 4)) = pred_word;
        }

        return 1;
    }
    else /* third position */
    {
        prev -= 3; /* word1-aligned */
        rec -= 16; /* preset */
        prev -= lx;

        for (i = 8; i > 0; i--)
        {
            word1 = *((ULong*)(prev += lx)); /* read 4 bytes, b4 b3 b2 b1 */
            word2 = *((ULong*)(prev + 4));  /* read 4 bytes, b8 b7 b6 b5 */
            word1 >>= 24; /* 0 0 0 b4 */
            pred_word = word1 | (word2 << 8);   /* b7 b6 b5 b4 */
            *((ULong*)(rec += 16)) = pred_word;

            word1 = *((ULong*)(prev + 8)); /* b12 b11 b10 b9 */
            word2 >>= 24; /* 0 0 0 b8 */
            pred_word = word2 | (word1 << 8); /* b11 b10 b9 b8 */
            *((ULong*)(rec + 4)) = pred_word;

        }

        return 1;
    }
}
/**************************************************************************/
Int GetPredAdvBy0x1(
    UChar *prev,        /* i */
    UChar *rec,     /* i */
    Int lx,     /* i */
    Int rnd1 /* i */
)
{
    Int i;      /* loop variable */
    Int offset;
    ULong word1, word2, word3, word12;
    Int tmp;
    ULong mask;

    /* initialize offset to adjust pixel counter */
    /*    the next row; full-pel resolution      */
    offset = lx - B_SIZE; /* offset for prev */

    /* Branch based on pixel location (half-pel or full-pel) for x and y */
    rec -= 12; /* preset */

    tmp = (ULong)prev & 3;
    mask = 254;
    mask |= (mask << 8);
    mask |= (mask << 16); /* 0xFEFEFEFE */

    if (tmp == 0) /* word-aligned */
    {
        if (rnd1 == 1)
        {
            for (i = B_SIZE; i > 0; i--)
            {
                word1 = *((ULong*)prev); /* b4 b3 b2 b1 */
                word2 = *((ULong*)(prev += 4)); /* b8 b7 b6 b5 */
                word12 = (word1 >> 8); /* 0 b4 b3 b2 */
                word12 |= (word2 << 24); /* b5 b4 b3 b2 */
                word3 = word1 | word12; // rnd1 = 1; otherwise word3 = word1&word12
                word1 &= mask;
                word3 &= (~mask); /* 0x1010101, check last bit */
                word12 &= mask;
                word1 >>= 1;
                word1 = word1 + (word12 >> 1);
                word1 += word3;
                *((ULong*)(rec += 12)) = word1; /* write 4 pixels */

                word1 = *((ULong*)(prev += 4)); /* b12 b11 b10 b9 */
                word12 = (word2 >> 8); /* 0 b8 b7 b6 */
                word12 |= (word1 << 24); /* b9 b8 b7 b6 */
                word3 = word2 | word12;
                word2 &= mask;
                word3 &= (~mask);  /* 0x1010101, check last bit */
                word12 &= mask;
                word2 >>= 1;
                word2 = word2 + (word12 >> 1);
                word2 += word3;
                *((ULong*)(rec += 4)) = word2; /* write 4 pixels */

                prev += offset;
            }
            return 1;
        }
        else /* rnd1 == 0 */
        {
            for (i = B_SIZE; i > 0; i--)
            {
                word1 = *((ULong*)prev); /* b4 b3 b2 b1 */

                word2 = *((ULong*)(prev += 4)); /* b8 b7 b6 b5 */
                word12 = (word1 >> 8); /* 0 b4 b3 b2 */
                word12 |= (word2 << 24); /* b5 b4 b3 b2 */
                word3 = word1 & word12; // rnd1 = 1; otherwise word3 = word1&word12
                word1 &= mask;
                word3 &= (~mask); /* 0x1010101, check last bit */
                word12 &= mask;
                word1 >>= 1;
                word1 = word1 + (word12 >> 1);
                word1 += word3;
                *((ULong*)(rec += 12)) = word1; /* write 4 pixels */

                word1 = *((ULong*)(prev += 4)); /* b12 b11 b10 b9 */
                word12 = (word2 >> 8); /* 0 b8 b7 b6 */
                word12 |= (word1 << 24); /* b9 b8 b7 b6 */
                word3 = word2 & word12;
                word2 &= mask;
                word3 &= (~mask);  /* 0x1010101, check last bit */
                word12 &= mask;
                word2 >>= 1;
                word2 = word2 + (word12 >> 1);
                word2 += word3;
                *((ULong*)(rec += 4)) = word2; /* write 4 pixels */

                prev += offset;
            }
            return 1;
        } /* rnd1 */
    }
    else if (tmp == 1)
    {
        prev--; /* word-aligned */
        if (rnd1 == 1)
        {
            for (i = B_SIZE; i > 0; i--)
            {
                word1 = *((ULong*)prev); /* b3 b2 b1 b0 */
                word2 = *((ULong*)(prev += 4)); /* b7 b6 b5 b4 */
                word12 = (word1 >> 8); /* 0 b3 b2 b1 */
                word1 >>= 16; /* 0 0 b3 b2 */
                word12 |= (word2 << 24); /* b4 b3 b2 b1 */
                word1 |= (word2 << 16); /* b5 b4 b3 b2 */
                word3 = word1 | word12; // rnd1 = 1; otherwise word3 = word1&word12
                word1 &= mask;
                word3 &= (~mask); /* 0x1010101, check last bit */
                word12 &= mask;
                word1 >>= 1;
                word1 = word1 + (word12 >> 1);
                word1 += word3;
                *((ULong*)(rec += 12)) = word1; /* write 4 pixels */

                word1 = *((ULong*)(prev += 4)); /* b11 b10 b9 b8 */
                word12 = (word2 >> 8); /* 0 b7 b6 b5 */
                word2 >>= 16; /* 0 0 b7 b6 */
                word12 |= (word1 << 24); /* b8 b7 b6 b5 */
                word2 |= (word1 << 16); /* b9 b8 b7 b6 */
                word3 = word2 | word12; // rnd1 = 1; otherwise word3 = word2&word12
                word2 &= mask;
                word3 &= (~mask); /* 0x1010101, check last bit */
                word12 &= mask;
                word2 >>= 1;
                word2 = word2 + (word12 >> 1);
                word2 += word3;
                *((ULong*)(rec += 4)) = word2; /* write 4 pixels */

                prev += offset;
            }
            return 1;
        }
        else /* rnd1 = 0 */
        {
            for (i = B_SIZE; i > 0; i--)
            {
                word1 = *((ULong*)prev); /* b3 b2 b1 b0 */

                word2 = *((ULong*)(prev += 4)); /* b7 b6 b5 b4 */
                word12 = (word1 >> 8); /* 0 b3 b2 b1 */
                word1 >>= 16; /* 0 0 b3 b2 */
                word12 |= (word2 << 24); /* b4 b3 b2 b1 */
                word1 |= (word2 << 16); /* b5 b4 b3 b2 */
                word3 = word1 & word12;
                word1 &= mask;
                word3 &= (~mask); /* 0x1010101, check last bit */
                word12 &= mask;
                word1 >>= 1;
                word1 = word1 + (word12 >> 1);
                word1 += word3;
                *((ULong*)(rec += 12)) = word1; /* write 4 pixels */

                word1 = *((ULong*)(prev += 4)); /* b11 b10 b9 b8 */
                word12 = (word2 >> 8); /* 0 b7 b6 b5 */
                word2 >>= 16; /* 0 0 b7 b6 */
                word12 |= (word1 << 24); /* b8 b7 b6 b5 */
                word2 |= (word1 << 16); /* b9 b8 b7 b6 */
                word3 = word2 & word12;
                word2 &= mask;
                word3 &= (~mask); /* 0x1010101, check last bit */
                word12 &= mask;
                word2 >>= 1;
                word2 = word2 + (word12 >> 1);
                word2 += word3;
                *((ULong*)(rec += 4)) = word2; /* write 4 pixels */

                prev += offset;
            }
            return 1;
        } /* rnd1 */
    }
    else if (tmp == 2)
    {
        prev -= 2; /* word-aligned */
        if (rnd1 == 1)
        {
            for (i = B_SIZE; i > 0; i--)
            {
                word1 = *((ULong*)prev); /* b2 b1 b0 bN1 */
                word2 = *((ULong*)(prev += 4)); /* b6 b5 b4 b3 */
                word12 = (word1 >> 16); /* 0 0 b2 b1 */
                word1 >>= 24; /* 0 0 0 b2 */
                word12 |= (word2 << 16); /* b4 b3 b2 b1 */
                word1 |= (word2 << 8); /* b5 b4 b3 b2 */
                word3 = word1 | word12; // rnd1 = 1; otherwise word3 = word1&word12
                word1 &= mask;
                word3 &= (~mask); /* 0x1010101, check last bit */
                word12 &= mask;
                word1 >>= 1;
                word1 = word1 + (word12 >> 1);
                word1 += word3;
                *((ULong*)(rec += 12)) = word1; /* write 4 pixels */

                word1 = *((ULong*)(prev += 4)); /* b10 b9 b8 b7 */
                word12 = (word2 >> 16); /* 0 0 b6 b5 */
                word2 >>= 24; /* 0 0 0 b6 */
                word12 |= (word1 << 16); /* b8 b7 b6 b5 */
                word2 |= (word1 << 8); /* b9 b8 b7 b6 */
                word3 = word2 | word12; // rnd1 = 1; otherwise word3 = word1&word12
                word2 &= mask;
                word3 &= (~mask); /* 0x1010101, check last bit */
                word12 &= mask;
                word2 >>= 1;
                word2 = word2 + (word12 >> 1);
                word2 += word3;
                *((ULong*)(rec += 4)) = word2; /* write 4 pixels */
                prev += offset;
            }
            return 1;
        }
        else /* rnd1 == 0 */
        {
            for (i = B_SIZE; i > 0; i--)
            {
                word1 = *((ULong*)prev); /* b2 b1 b0 bN1 */
                word2 = *((ULong*)(prev += 4)); /* b6 b5 b4 b3 */
                word12 = (word1 >> 16); /* 0 0 b2 b1 */
                word1 >>= 24; /* 0 0 0 b2 */
                word12 |= (word2 << 16); /* b4 b3 b2 b1 */
                word1 |= (word2 << 8); /* b5 b4 b3 b2 */
                word3 = word1 & word12; // rnd1 = 1; otherwise word3 = word1&word12
                word1 &= mask;
                word3 &= (~mask); /* 0x1010101, check last bit */
                word12 &= mask;
                word1 >>= 1;
                word1 = word1 + (word12 >> 1);
                word1 += word3;
                *((ULong*)(rec += 12)) = word1; /* write 4 pixels */

                word1 = *((ULong*)(prev += 4)); /* b10 b9 b8 b7 */
                word12 = (word2 >> 16); /* 0 0 b6 b5 */
                word2 >>= 24; /* 0 0 0 b6 */
                word12 |= (word1 << 16); /* b8 b7 b6 b5 */
                word2 |= (word1 << 8); /* b9 b8 b7 b6 */
                word3 = word2 & word12; // rnd1 = 1; otherwise word3 = word1&word12
                word2 &= mask;
                word3 &= (~mask); /* 0x1010101, check last bit */
                word12 &= mask;
                word2 >>= 1;
                word2 = word2 + (word12 >> 1);
                word2 += word3;
                *((ULong*)(rec += 4)) = word2; /* write 4 pixels */
                prev += offset;
            }
            return 1;
        }
    }
    else /* tmp = 3 */
    {
        prev -= 3; /* word-aligned */
        if (rnd1 == 1)
        {
            for (i = B_SIZE; i > 0; i--)
            {
                word1 = *((ULong*)prev); /* b1 b0 bN1 bN2 */
                word2 = *((ULong*)(prev += 4)); /* b5 b4 b3 b2 */
                word12 = (word1 >> 24); /* 0 0 0 b1 */
                word12 |= (word2 << 8); /* b4 b3 b2 b1 */
                word1 = word2;
                word3 = word1 | word12; // rnd1 = 1; otherwise word3 = word1&word12
                word1 &= mask;
                word3 &= (~mask); /* 0x1010101, check last bit */
                word12 &= mask;
                word1 >>= 1;
                word1 = word1 + (word12 >> 1);
                word1 += word3;
                *((ULong*)(rec += 12)) = word1; /* write 4 pixels */

                word1 = *((ULong*)(prev += 4)); /* b9 b8 b7 b6 */
                word12 = (word2 >> 24); /* 0 0 0 b5 */
                word12 |= (word1 << 8); /* b8 b7 b6 b5 */
                word2 = word1; /* b9 b8 b7 b6 */
                word3 = word2 | word12; // rnd1 = 1; otherwise word3 = word1&word12
                word2 &= mask;
                word3 &= (~mask); /* 0x1010101, check last bit */
                word12 &= mask;
                word2 >>= 1;
                word2 = word2 + (word12 >> 1);
                word2 += word3;
                *((ULong*)(rec += 4)) = word2; /* write 4 pixels */
                prev += offset;
            }
            return 1;
        }
        else
        {
            for (i = B_SIZE; i > 0; i--)
            {
                word1 = *((ULong*)prev); /* b1 b0 bN1 bN2 */
                word2 = *((ULong*)(prev += 4)); /* b5 b4 b3 b2 */
                word12 = (word1 >> 24); /* 0 0 0 b1 */
                word12 |= (word2 << 8); /* b4 b3 b2 b1 */
                word1 = word2;
                word3 = word1 & word12; // rnd1 = 1; otherwise word3 = word1&word12
                word1 &= mask;
                word3 &= (~mask); /* 0x1010101, check last bit */
                word12 &= mask;
                word1 >>= 1;
                word1 = word1 + (word12 >> 1);
                word1 += word3;
                *((ULong*)(rec += 12)) = word1; /* write 4 pixels */

                word1 = *((ULong*)(prev += 4)); /* b9 b8 b7 b6 */
                word12 = (word2 >> 24); /* 0 0 0 b5 */
                word12 |= (word1 << 8); /* b8 b7 b6 b5 */
                word2 = word1; /* b9 b8 b7 b6 */
                word3 = word2 & word12; // rnd1 = 1; otherwise word3 = word1&word12
                word2 &= mask;
                word3 &= (~mask); /* 0x1010101, check last bit */
                word12 &= mask;
                word2 >>= 1;
                word2 = word2 + (word12 >> 1);
                word2 += word3;
                *((ULong*)(rec += 4)) = word2; /* write 4 pixels */
                prev += offset;
            }
            return 1;
        }
    }
}

/**************************************************************************/
Int GetPredAdvBy1x0(
    UChar *prev,        /* i */
    UChar *rec,     /* i */
    Int lx,     /* i */
    Int rnd1 /* i */
)
{
    Int i;      /* loop variable */
    Int offset;
    ULong  word1, word2, word3, word12, word22;
    Int tmp;
    ULong mask;

    /* initialize offset to adjust pixel counter */
    /*    the next row; full-pel resolution      */
    offset = lx - B_SIZE; /* offset for prev */

    /* Branch based on pixel location (half-pel or full-pel) for x and y */
    rec -= 12; /* preset */

    tmp = (ULong)prev & 3;
    mask = 254;
    mask |= (mask << 8);
    mask |= (mask << 16); /* 0xFEFEFEFE */

    if (tmp == 0) /* word-aligned */
    {
        prev -= 4;
        if (rnd1 == 1)
        {
            for (i = B_SIZE; i > 0; i--)
            {
                word1 = *((ULong*)(prev += 4));
                word2 = *((ULong*)(prev + lx));
                word3 = word1 | word2; // rnd1 = 1; otherwise word3 = word1&word2
                word1 &= mask;
                word3 &= (~mask); /* 0x1010101, check last bit */
                word2 &= mask;
                word1 >>= 1;
                word1 = word1 + (word2 >> 1);
                word1 += word3;
                *((ULong*)(rec += 12)) = word1;
                word1 = *((ULong*)(prev += 4));
                word2 = *((ULong*)(prev + lx));
                word3 = word1 | word2; // rnd1 = 1; otherwise word3 = word1&word2
                word1 &= mask;
                word3 &= (~mask); /* 0x1010101, check last bit */
                word2 &= mask;
                word1 >>= 1;
                word1 = word1 + (word2 >> 1);
                word1 += word3;
                *((ULong*)(rec += 4)) = word1;

                prev += offset;
            }
            return 1;
        }
        else   /* rnd1 = 0 */
        {
            for (i = B_SIZE; i > 0; i--)
            {
                word1 = *((ULong*)(prev += 4));
                word2 = *((ULong*)(prev + lx));
                word3 = word1 & word2;  /* rnd1 = 0; */
                word1 &= mask;
                word3 &= (~mask); /* 0x1010101, check last bit */
                word2 &= mask;
                word1 >>= 1;
                word1 = word1 + (word2 >> 1);
                word1 += word3;
                *((ULong*)(rec += 12)) = word1;
                word1 = *((ULong*)(prev += 4));
                word2 = *((ULong*)(prev + lx));
                word3 = word1 & word2;  /* rnd1 = 0; */
                word1 &= mask;
                word3 &= (~mask); /* 0x1010101, check last bit */
                word2 &= mask;
                word1 >>= 1;
                word1 = word1 + (word2 >> 1);
                word1 += word3;
                *((ULong*)(rec += 4)) = word1;

                prev += offset;
            }
            return 1;
        }
    }
    else if (tmp == 1)
    {
        prev--; /* word-aligned */
        if (rnd1 == 1)
        {
            for (i = B_SIZE; i > 0; i--)
            {
                word12 = *((ULong*)prev); /* read b4 b3 b2 b1 */
                word22 = *((ULong*)(prev + lx));

                word1 = *((ULong*)(prev += 4)); /* read b8 b7 b6 b5 */
                word2 = *((ULong*)(prev + lx));
                word12 >>= 8; /* 0 b4 b3 b2 */
                word22 >>= 8;
                word12 = word12 | (word1 << 24); /* b5 b4 b3 b2 */
                word22 = word22 | (word2 << 24);
                word3 = word12 | word22;
                word12 &= mask;
                word22 &= mask;
                word3 &= (~mask); /* 0x1010101, check last bit */
                word12 >>= 1;
                word12 = word12 + (word22 >> 1);
                word12 += word3;
                *((ULong*)(rec += 12)) = word12;

                word12 = *((ULong*)(prev += 4)); /* read b12 b11 b10 b9 */
                word22 = *((ULong*)(prev + lx));
                word1 >>= 8; /* 0 b8 b7 b6 */
                word2 >>= 8;
                word1 = word1 | (word12 << 24); /* b9 b8 b7 b6 */
                word2 = word2 | (word22 << 24);
                word3 = word1 | word2;
                word1 &= mask;
                word2 &= mask;
                word3 &= (~mask); /* 0x1010101, check last bit */
                word1 >>= 1;
                word1 = word1 + (word2 >> 1);
                word1 += word3;
                *((ULong*)(rec += 4)) = word1;
                prev += offset;
            }
            return 1;
        }
        else /* rnd1 = 0 */
        {
            for (i = B_SIZE; i > 0; i--)
            {
                word12 = *((ULong*)prev); /* read b4 b3 b2 b1 */
                word22 = *((ULong*)(prev + lx));

                word1 = *((ULong*)(prev += 4)); /* read b8 b7 b6 b5 */
                word2 = *((ULong*)(prev + lx));
                word12 >>= 8; /* 0 b4 b3 b2 */
                word22 >>= 8;
                word12 = word12 | (word1 << 24); /* b5 b4 b3 b2 */
                word22 = word22 | (word2 << 24);
                word3 = word12 & word22;
                word12 &= mask;
                word22 &= mask;
                word3 &= (~mask); /* 0x1010101, check last bit */
                word12 >>= 1;
                word12 = word12 + (word22 >> 1);
                word12 += word3;
                *((ULong*)(rec += 12)) = word12;

                word12 = *((ULong*)(prev += 4)); /* read b12 b11 b10 b9 */
                word22 = *((ULong*)(prev + lx));
                word1 >>= 8; /* 0 b8 b7 b6 */
                word2 >>= 8;
                word1 = word1 | (word12 << 24); /* b9 b8 b7 b6 */
                word2 = word2 | (word22 << 24);
                word3 = word1 & word2;
                word1 &= mask;
                word2 &= mask;
                word3 &= (~mask); /* 0x1010101, check last bit */
                word1 >>= 1;
                word1 = word1 + (word2 >> 1);
                word1 += word3;
                *((ULong*)(rec += 4)) = word1;
                prev += offset;
            }
            return 1;
        }
    }
    else if (tmp == 2)
    {
        prev -= 2; /* word-aligned */
        if (rnd1 == 1)
        {
            for (i = B_SIZE; i > 0; i--)
            {
                word12 = *((ULong*)prev); /* read b4 b3 b2 b1 */
                word22 = *((ULong*)(prev + lx));

                word1 = *((ULong*)(prev += 4)); /* read b8 b7 b6 b5 */
                word2 = *((ULong*)(prev + lx));
                word12 >>= 16; /* 0 0 b4 b3 */
                word22 >>= 16;
                word12 = word12 | (word1 << 16); /* b6 b5 b4 b3 */
                word22 = word22 | (word2 << 16);
                word3 = word12 | word22;
                word12 &= mask;
                word22 &= mask;
                word3 &= (~mask); /* 0x1010101, check last bit */
                word12 >>= 1;
                word12 = word12 + (word22 >> 1);
                word12 += word3;
                *((ULong*)(rec += 12)) = word12;

                word12 = *((ULong*)(prev += 4)); /* read b12 b11 b10 b9 */
                word22 = *((ULong*)(prev + lx));
                word1 >>= 16; /* 0 0 b8 b7 */
                word2 >>= 16;
                word1 = word1 | (word12 << 16); /* b10 b9 b8 b7 */
                word2 = word2 | (word22 << 16);
                word3 = word1 | word2;
                word1 &= mask;
                word2 &= mask;
                word3 &= (~mask); /* 0x1010101, check last bit */
                word1 >>= 1;
                word1 = word1 + (word2 >> 1);
                word1 += word3;
                *((ULong*)(rec += 4)) = word1;
                prev += offset;
            }
            return 1;
        }
        else /* rnd1 = 0 */
        {
            for (i = B_SIZE; i > 0; i--)
            {
                word12 = *((ULong*)prev); /* read b4 b3 b2 b1 */
                word22 = *((ULong*)(prev + lx));

                word1 = *((ULong*)(prev += 4)); /* read b8 b7 b6 b5 */
                word2 = *((ULong*)(prev + lx));
                word12 >>= 16; /* 0 0 b4 b3 */
                word22 >>= 16;
                word12 = word12 | (word1 << 16); /* b6 b5 b4 b3 */
                word22 = word22 | (word2 << 16);
                word3 = word12 & word22;
                word12 &= mask;
                word22 &= mask;
                word3 &= (~mask); /* 0x1010101, check last bit */
                word12 >>= 1;
                word12 = word12 + (word22 >> 1);
                word12 += word3;
                *((ULong*)(rec += 12)) = word12;

                word12 = *((ULong*)(prev += 4)); /* read b12 b11 b10 b9 */
                word22 = *((ULong*)(prev + lx));
                word1 >>= 16; /* 0 0 b8 b7 */
                word2 >>= 16;
                word1 = word1 | (word12 << 16); /* b10 b9 b8 b7 */
                word2 = word2 | (word22 << 16);
                word3 = word1 & word2;
                word1 &= mask;
                word2 &= mask;
                word3 &= (~mask); /* 0x1010101, check last bit */
                word1 >>= 1;
                word1 = word1 + (word2 >> 1);
                word1 += word3;
                *((ULong*)(rec += 4)) = word1;
                prev += offset;
            }

            return 1;
        }
    }
    else /* tmp == 3 */
    {
        prev -= 3; /* word-aligned */
        if (rnd1 == 1)
        {
            for (i = B_SIZE; i > 0; i--)
            {
                word12 = *((ULong*)prev); /* read b4 b3 b2 b1 */
                word22 = *((ULong*)(prev + lx));

                word1 = *((ULong*)(prev += 4)); /* read b8 b7 b6 b5 */
                word2 = *((ULong*)(prev + lx));
                word12 >>= 24; /* 0 0 0 b4 */
                word22 >>= 24;
                word12 = word12 | (word1 << 8); /* b7 b6 b5 b4 */
                word22 = word22 | (word2 << 8);
                word3 = word12 | word22;
                word12 &= mask;
                word22 &= mask;
                word3 &= (~mask); /* 0x1010101, check last bit */
                word12 >>= 1;
                word12 = word12 + (word22 >> 1);
                word12 += word3;
                *((ULong*)(rec += 12)) = word12;

                word12 = *((ULong*)(prev += 4)); /* read b12 b11 b10 b9 */
                word22 = *((ULong*)(prev + lx));
                word1 >>= 24; /* 0 0 0 b8 */
                word2 >>= 24;
                word1 = word1 | (word12 << 8); /* b11 b10 b9 b8 */
                word2 = word2 | (word22 << 8);
                word3 = word1 | word2;
                word1 &= mask;
                word2 &= mask;
                word3 &= (~mask); /* 0x1010101, check last bit */
                word1 >>= 1;
                word1 = word1 + (word2 >> 1);
                word1 += word3;
                *((ULong*)(rec += 4)) = word1;
                prev += offset;
            }
            return 1;
        }
        else /* rnd1 = 0 */
        {
            for (i = B_SIZE; i > 0; i--)
            {
                word12 = *((ULong*)prev); /* read b4 b3 b2 b1 */
                word22 = *((ULong*)(prev + lx));

                word1 = *((ULong*)(prev += 4)); /* read b8 b7 b6 b5 */
                word2 = *((ULong*)(prev + lx));
                word12 >>= 24; /* 0 0 0 b4 */
                word22 >>= 24;
                word12 = word12 | (word1 << 8); /* b7 b6 b5 b4 */
                word22 = word22 | (word2 << 8);
                word3 = word12 & word22;
                word12 &= mask;
                word22 &= mask;
                word3 &= (~mask); /* 0x1010101, check last bit */
                word12 >>= 1;
                word12 = word12 + (word22 >> 1);
                word12 += word3;
                *((ULong*)(rec += 12)) = word12;

                word12 = *((ULong*)(prev += 4)); /* read b12 b11 b10 b9 */
                word22 = *((ULong*)(prev + lx));
                word1 >>= 24; /* 0 0 0 b8 */
                word2 >>= 24;
                word1 = word1 | (word12 << 8); /* b11 b10 b9 b8 */
                word2 = word2 | (word22 << 8);
                word3 = word1 & word2;
                word1 &= mask;
                word2 &= mask;
                word3 &= (~mask); /* 0x1010101, check last bit */
                word1 >>= 1;
                word1 = word1 + (word2 >> 1);
                word1 += word3;
                *((ULong*)(rec += 4)) = word1;
                prev += offset;
            }
            return 1;
        } /* rnd */
    } /* tmp */
}

/**********************************************************************************/
Int GetPredAdvBy1x1(
    UChar *prev,        /* i */
    UChar *rec,     /* i */
    Int lx,     /* i */
    Int rnd1 /* i */
)
{
    Int i;      /* loop variable */
    Int offset;
    ULong  x1, x2, x1m, x2m, y1, y2, y1m, y2m; /* new way */
    Int tmp;
    Int rnd2;
    ULong mask;

    /* initialize offset to adjust pixel counter */
    /*    the next row; full-pel resolution      */
    offset = lx - B_SIZE; /* offset for prev */

    rnd2 = rnd1 + 1;
    rnd2 |= (rnd2 << 8);
    rnd2 |= (rnd2 << 16);

    mask = 0x3F;
    mask |= (mask << 8);
    mask |= (mask << 16); /* 0x3f3f3f3f */

    tmp = (ULong)prev & 3;

    rec -= 4; /* preset */

    if (tmp == 0) /* word-aligned */
    {
        for (i = B_SIZE; i > 0; i--)
        {
            x1 = *((ULong*)prev); /* load a3 a2 a1 a0 */
            x2 = *((ULong*)(prev + lx)); /* load b3 b2 b1 b0, another line */
            y1 = *((ULong*)(prev += 4)); /* a7 a6 a5 a4 */
            y2 = *((ULong*)(prev + lx)); /* b7 b6 b5 b4 */

            x1m = (x1 >> 2) & mask; /* zero out last 2 bits */
            x2m = (x2 >> 2) & mask;
            x1 = x1 ^(x1m << 2);
            x2 = x2 ^(x2m << 2);
            x1m += x2m;
            x1 += x2;

            /* x2m, x2 free */
            y1m = (y1 >> 2) & mask; /* zero out last 2 bits */
            y2m = (y2 >> 2) & mask;
            y1 = y1 ^(y1m << 2);
            y2 = y2 ^(y2m << 2);
            y1m += y2m;
            y1 += y2;

            /* y2m, y2 free */
            /* x2m, x2 free */
            x2 = *((ULong*)(prev += 4)); /* a11 a10 a9 a8 */
            y2 = *((ULong*)(prev + lx)); /* b11 b10 b9 b8 */
            x2m = (x2 >> 2) & mask;
            y2m = (y2 >> 2) & mask;
            x2 = x2 ^(x2m << 2);
            y2 = y2 ^(y2m << 2);
            x2m += y2m;
            x2 += y2;
            /* y2m, y2 free */

            /* now operate on x1m, x1, y1m, y1, x2m, x2 */
            /* x1m = a3+b3, a2+b2, a1+b1, a0+b0 */
            /* y1m = a7+b7, a6+b6, a5+b5, a4+b4 */
            /* x2m = a11+b11, a10+b10, a9+b9, a8+b8 */
            /* x1, y1, x2 */

            y2m = x1m >> 8;
            y2 = x1 >> 8;
            y2m |= (y1m << 24);  /* a4+b4, a3+b3, a2+b2, a1+b1 */
            y2 |= (y1 << 24);
            x1m += y2m;  /* a3+b3+a4+b4, ....., a0+b0+a1+b1 */
            x1 += y2;
            x1 += rnd2;
            x1 &= (mask << 2);
            x1m += (x1 >> 2);
            *((ULong*)(rec += 4)) = x1m; /* save x1m */

            y2m = y1m >> 8;
            y2 = y1 >> 8;
            y2m |= (x2m << 24); /* a8+b8, a7+b7, a6+b6, a5+b5 */
            y2 |= (x2 << 24);
            y1m += y2m;  /* a7+b7+a8+b8, ....., a4+b4+a5+b5 */
            y1 += y2;
            y1 += rnd2;
            y1 &= (mask << 2);
            y1m += (y1 >> 2);
            *((ULong*)(rec += 4)) = y1m; /* save y1m */

            rec += 8;
            prev += offset;
        }

        return 1;
    }
    else if (tmp == 1)
    {
        prev--; /* to word-aligned */
        for (i = B_SIZE; i > 0; i--)
        {
            x1 = *((ULong*)prev); /* load a3 a2 a1 a0 */
            x2 = *((ULong*)(prev + lx)); /* load b3 b2 b1 b0, another line */
            y1 = *((ULong*)(prev += 4)); /* a7 a6 a5 a4 */
            y2 = *((ULong*)(prev + lx)); /* b7 b6 b5 b4 */

            x1m = (x1 >> 2) & mask; /* zero out last 2 bits */
            x2m = (x2 >> 2) & mask;
            x1 = x1 ^(x1m << 2);
            x2 = x2 ^(x2m << 2);
            x1m += x2m;
            x1 += x2;

            /* x2m, x2 free */
            y1m = (y1 >> 2) & mask; /* zero out last 2 bits */
            y2m = (y2 >> 2) & mask;
            y1 = y1 ^(y1m << 2);
            y2 = y2 ^(y2m << 2);
            y1m += y2m;
            y1 += y2;

            /* y2m, y2 free */
            /* x2m, x2 free */
            x2 = *((ULong*)(prev += 4)); /* a11 a10 a9 a8 */
            y2 = *((ULong*)(prev + lx)); /* b11 b10 b9 b8 */
            x2m = (x2 >> 2) & mask;
            y2m = (y2 >> 2) & mask;
            x2 = x2 ^(x2m << 2);
            y2 = y2 ^(y2m << 2);
            x2m += y2m;
            x2 += y2;
            /* y2m, y2 free */

            /* now operate on x1m, x1, y1m, y1, x2m, x2 */
            /* x1m = a3+b3, a2+b2, a1+b1, a0+b0 */
            /* y1m = a7+b7, a6+b6, a5+b5, a4+b4 */
            /* x2m = a11+b11, a10+b10, a9+b9, a8+b8 */
            /* x1, y1, x2 */

            x1m >>= 8 ;
            x1 >>= 8;
            x1m |= (y1m << 24);  /* a4+b4, a3+b3, a2+b2, a1+b1 */
            x1 |= (y1 << 24);
            y2m = (y1m << 16);
            y2 = (y1 << 16);
            y2m |= (x1m >> 8); /* a5+b5, a4+b4, a3+b3, a2+b2 */
            y2 |= (x1 >> 8);
            x1 += rnd2;
            x1m += y2m;  /* a4+b4+a5+b5, ....., a1+b1+a2+b2 */
            x1 += y2;
            x1 &= (mask << 2);
            x1m += (x1 >> 2);
            *((ULong*)(rec += 4)) = x1m; /* save x1m */

            y1m >>= 8;
            y1 >>= 8;
            y1m |= (x2m << 24); /* a8+b8, a7+b7, a6+b6, a5+b5 */
            y1 |= (x2 << 24);
            y2m = (x2m << 16);
            y2 = (x2 << 16);
            y2m |= (y1m >> 8); /*  a9+b9, a8+b8, a7+b7, a6+b6,*/
            y2 |= (y1 >> 8);
            y1 += rnd2;
            y1m += y2m;  /* a8+b8+a9+b9, ....., a5+b5+a6+b6 */
            y1 += y2;
            y1 &= (mask << 2);
            y1m += (y1 >> 2);
            *((ULong*)(rec += 4)) = y1m; /* save y1m */

            rec += 8;
            prev += offset;
        }
        return 1;
    }
    else if (tmp == 2)
    {
        prev -= 2; /* to word-aligned */
        for (i = B_SIZE; i > 0; i--)
        {
            x1 = *((ULong*)prev); /* load a3 a2 a1 a0 */
            x2 = *((ULong*)(prev + lx)); /* load b3 b2 b1 b0, another line */
            y1 = *((ULong*)(prev += 4)); /* a7 a6 a5 a4 */
            y2 = *((ULong*)(prev + lx)); /* b7 b6 b5 b4 */

            x1m = (x1 >> 2) & mask; /* zero out last 2 bits */
            x2m = (x2 >> 2) & mask;
            x1 = x1 ^(x1m << 2);
            x2 = x2 ^(x2m << 2);
            x1m += x2m;
            x1 += x2;

            /* x2m, x2 free */
            y1m = (y1 >> 2) & mask; /* zero out last 2 bits */
            y2m = (y2 >> 2) & mask;
            y1 = y1 ^(y1m << 2);
            y2 = y2 ^(y2m << 2);
            y1m += y2m;
            y1 += y2;

            /* y2m, y2 free */
            /* x2m, x2 free */
            x2 = *((ULong*)(prev += 4)); /* a11 a10 a9 a8 */
            y2 = *((ULong*)(prev + lx)); /* b11 b10 b9 b8 */
            x2m = (x2 >> 2) & mask;
            y2m = (y2 >> 2) & mask;
            x2 = x2 ^(x2m << 2);
            y2 = y2 ^(y2m << 2);
            x2m += y2m;
            x2 += y2;
            /* y2m, y2 free */

            /* now operate on x1m, x1, y1m, y1, x2m, x2 */
            /* x1m = a3+b3, a2+b2, a1+b1, a0+b0 */
            /* y1m = a7+b7, a6+b6, a5+b5, a4+b4 */
            /* x2m = a11+b11, a10+b10, a9+b9, a8+b8 */
            /* x1, y1, x2 */

            x1m >>= 16 ;
            x1 >>= 16;
            x1m |= (y1m << 16);  /* a5+b5, a4+b4, a3+b3, a2+b2 */
            x1 |= (y1 << 16);
            y2m = (y1m << 8);
            y2 = (y1 << 8);
            y2m |= (x1m >> 8); /* a6+b6, a5+b5, a4+b4, a3+b3 */
            y2 |= (x1 >> 8);
            x1 += rnd2;
            x1m += y2m;  /* a5+b5+a6+b6, ....., a2+b2+a3+b3 */
            x1 += y2;
            x1 &= (mask << 2);
            x1m += (x1 >> 2);
            *((ULong*)(rec += 4)) = x1m; /* save x1m */

            y1m >>= 16;
            y1 >>= 16;
            y1m |= (x2m << 16); /* a9+b9, a8+b8, a7+b7, a6+b6 */
            y1 |= (x2 << 16);
            y2m = (x2m << 8);
            y2 = (x2 << 8);
            y2m |= (y1m >> 8); /*  a10+b10, a9+b9, a8+b8, a7+b7,*/
            y2 |= (y1 >> 8);
            y1 += rnd2;
            y1m += y2m;  /* a9+b9+a10+b10, ....., a6+b6+a7+b7 */
            y1 += y2;
            y1 &= (mask << 2);
            y1m += (y1 >> 2);
            *((ULong*)(rec += 4)) = y1m; /* save y1m */

            rec += 8;
            prev += offset;
        }
        return 1;
    }
    else /* tmp == 3 */
    {
        prev -= 3; /* to word-aligned */
        for (i = B_SIZE; i > 0; i--)
        {
            x1 = *((ULong*)prev); /* load a3 a2 a1 a0 */
            x2 = *((ULong*)(prev + lx)); /* load b3 b2 b1 b0, another line */
            y1 = *((ULong*)(prev += 4)); /* a7 a6 a5 a4 */
            y2 = *((ULong*)(prev + lx)); /* b7 b6 b5 b4 */

            x1m = (x1 >> 2) & mask; /* zero out last 2 bits */
            x2m = (x2 >> 2) & mask;
            x1 = x1 ^(x1m << 2);
            x2 = x2 ^(x2m << 2);
            x1m += x2m;
            x1 += x2;

            /* x2m, x2 free */
            y1m = (y1 >> 2) & mask; /* zero out last 2 bits */
            y2m = (y2 >> 2) & mask;
            y1 = y1 ^(y1m << 2);
            y2 = y2 ^(y2m << 2);
            y1m += y2m;
            y1 += y2;

            /* y2m, y2 free */
            /* x2m, x2 free */
            x2 = *((ULong*)(prev += 4)); /* a11 a10 a9 a8 */
            y2 = *((ULong*)(prev + lx)); /* b11 b10 b9 b8 */
            x2m = (x2 >> 2) & mask;
            y2m = (y2 >> 2) & mask;
            x2 = x2 ^(x2m << 2);
            y2 = y2 ^(y2m << 2);
            x2m += y2m;
            x2 += y2;
            /* y2m, y2 free */

            /* now operate on x1m, x1, y1m, y1, x2m, x2 */
            /* x1m = a3+b3, a2+b2, a1+b1, a0+b0 */
            /* y1m = a7+b7, a6+b6, a5+b5, a4+b4 */
            /* x2m = a11+b11, a10+b10, a9+b9, a8+b8 */
            /* x1, y1, x2 */

            x1m >>= 24 ;
            x1 >>= 24;
            x1m |= (y1m << 8);  /* a6+b6, a5+b5, a4+b4, a3+b3 */
            x1 |= (y1 << 8);

            x1m += y1m;  /* a6+b6+a7+b7, ....., a3+b3+a4+b4 */
            x1 += y1;
            x1 += rnd2;
            x1 &= (mask << 2);
            x1m += (x1 >> 2);
            *((ULong*)(rec += 4)) = x1m; /* save x1m */

            y1m >>= 24;
            y1 >>= 24;
            y1m |= (x2m << 8); /* a10+b10, a9+b9, a8+b8, a7+b7 */
            y1 |= (x2 << 8);
            y1m += x2m;  /* a10+b10+a11+b11, ....., a7+b7+a8+b8 */
            y1 += x2;
            y1 += rnd2;
            y1 &= (mask << 2);
            y1m += (y1 >> 2);
            *((ULong*)(rec += 4)) = y1m; /* save y1m */

            rec += 8;
            prev += offset;
        }
        return 1;
    }
}


/*=============================================================================
    Function:   EncGetPredOutside
    Date:       04/17/2001
    Purpose:    - modified from GetPredOutside in the decoder.
    Modified:    09/24/05
                use the existing non-initialized padded region
=============================================================================*/
// not really needed since padding is included
#define PAD_CORNER  { temp = *src; \
                     temp |= (temp<<8); \
                     temp |= (temp<<16); \
                     *((ULong*)dst) = temp; \
                     *((ULong*)(dst+4)) = temp; \
                     *((ULong*)(dst+=lx)) = temp; \
                     *((ULong*)(dst+4)) = temp; \
                     *((ULong*)(dst+=lx)) = temp; \
                     *((ULong*)(dst+4)) = temp; \
                     *((ULong*)(dst+=lx)) = temp; \
                     *((ULong*)(dst+4)) = temp; \
                     *((ULong*)(dst+=lx)) = temp; \
                     *((ULong*)(dst+4)) = temp; \
                     *((ULong*)(dst+=lx)) = temp; \
                     *((ULong*)(dst+4)) = temp; \
                     *((ULong*)(dst+=lx)) = temp; \
                     *((ULong*)(dst+4)) = temp; \
                     *((ULong*)(dst+=lx)) = temp; \
                     *((ULong*)(dst+4)) = temp; }

#define PAD_ROW     { temp = *((ULong*)src); \
                      temp2 = *((ULong*)(src+4)); \
                      *((ULong*)dst) = temp; \
                      *((ULong*)(dst+4)) = temp2; \
                      *((ULong*)(dst+=lx)) = temp; \
                      *((ULong*)(dst+4)) = temp2; \
                      *((ULong*)(dst+=lx)) = temp; \
                      *((ULong*)(dst+4)) = temp2; \
                      *((ULong*)(dst+=lx)) = temp; \
                      *((ULong*)(dst+4)) = temp2; \
                      *((ULong*)(dst+=lx)) = temp; \
                      *((ULong*)(dst+4)) = temp2; \
                      *((ULong*)(dst+=lx)) = temp; \
                      *((ULong*)(dst+4)) = temp2; \
                      *((ULong*)(dst+=lx)) = temp; \
                      *((ULong*)(dst+4)) = temp2; \
                      *((ULong*)(dst+=lx)) = temp; \
                      *((ULong*)(dst+4)) = temp2; }

#define PAD_COL     { temp = *src;   temp |= (temp<<8);  temp |= (temp<<16); \
                      *((ULong*)dst) = temp; \
                     *((ULong*)(dst+4)) = temp; \
                      temp = *(src+=lx);     temp |= (temp<<8);  temp |= (temp<<16); \
                      *((ULong*)(dst+=lx)) = temp; \
                     *((ULong*)(dst+4)) = temp; \
                      temp = *(src+=lx);     temp |= (temp<<8);  temp |= (temp<<16); \
                      *((ULong*)(dst+=lx)) = temp; \
                     *((ULong*)(dst+4)) = temp; \
                      temp = *(src+=lx);     temp |= (temp<<8);  temp |= (temp<<16); \
                      *((ULong*)(dst+=lx)) = temp; \
                     *((ULong*)(dst+4)) = temp; \
                      temp = *(src+=lx);     temp |= (temp<<8);  temp |= (temp<<16); \
                      *((ULong*)(dst+=lx)) = temp; \
                     *((ULong*)(dst+4)) = temp; \
                      temp = *(src+=lx);     temp |= (temp<<8);  temp |= (temp<<16); \
                      *((ULong*)(dst+=lx)) = temp; \
                     *((ULong*)(dst+4)) = temp; \
                      temp = *(src+=lx);     temp |= (temp<<8);  temp |= (temp<<16); \
                      *((ULong*)(dst+=lx)) = temp; \
                     *((ULong*)(dst+4)) = temp; \
                      temp = *(src+=lx);     temp |= (temp<<8);  temp |= (temp<<16); \
                      *((ULong*)(dst+=lx)) = temp; \
                      *((ULong*)(dst+4)) = temp; }


Int EncGetPredOutside(Int xpos, Int ypos, UChar *c_prev, UChar *rec,
                      Int width, Int height, Int rnd1)
{
    Int lx;
    UChar *src, *dst;
    ULong temp, temp2;
    Int xoffset;

    lx = width + 16; /* only works for chroma */

    if (xpos < 0)
    {
        if (ypos < 0) /* pad top-left */
        {
            /* pad corner */
            src = c_prev;
            dst = c_prev - (lx << 3) - 8;
            PAD_CORNER

            /* pad top */
            dst = c_prev - (lx << 3);
            PAD_ROW

            /* pad left */
            dst = c_prev - 8;
            PAD_COL

            GetPredAdvBTable[ypos&1][xpos&1](c_prev + (xpos >> 1) + ((ypos >> 1)*lx),
                                             rec, lx, rnd1);

            return 1;
        }
        else if ((ypos >> 1) < (height - 8)) /* pad left of frame */
        {
            /* pad left */
            src = c_prev + (ypos >> 1) * lx;
            dst = src - 8;
            PAD_COL
            /* pad extra row */
            temp = *(src += lx);
            temp |= (temp << 8);
            temp |= (temp << 16);
            *((ULong*)(dst += lx)) = temp;
            *((ULong*)(dst + 4)) = temp;

            GetPredAdvBTable[ypos&1][xpos&1](c_prev + (xpos >> 1) + ((ypos >> 1)*lx),
                                             rec, lx, rnd1);

            return 1;
        }
        else /* pad bottom-left */
        {
            /* pad corner */
            src = c_prev + (height - 1) * lx;
            dst = src + lx - 8;
            PAD_CORNER

            /* pad bottom */
            dst = src + lx;
            PAD_ROW

            /* pad left */
            src -= (lx << 3);
            src += lx;
            dst = src - 8;
            PAD_COL

            GetPredAdvBTable[ypos&1][xpos&1](c_prev + (xpos >> 1) + ((ypos >> 1)*lx),
                                             rec, lx, rnd1);

            return 1;
        }
    }
    else if ((xpos >> 1) < (width - 8))
    {
        if (ypos < 0) /* pad top of frame */
        {
            xoffset = (xpos >> 1) & 0x3;
            src = c_prev + (xpos >> 1) - xoffset;
            dst = src - (lx << 3);
            PAD_ROW
            if (xoffset || (xpos&1))
            {
                temp = *((ULong*)(src + 8));
                dst = src - (lx << 3) + 8;
                *((ULong*)dst) = temp;
                *((ULong*)(dst += lx)) = temp;
                *((ULong*)(dst += lx)) = temp;
                *((ULong*)(dst += lx)) = temp;
                *((ULong*)(dst += lx)) = temp;
                *((ULong*)(dst += lx)) = temp;
                *((ULong*)(dst += lx)) = temp;
                *((ULong*)(dst += lx)) = temp;
            }

            GetPredAdvBTable[ypos&1][xpos&1](c_prev + (xpos >> 1) + ((ypos >> 1)*lx),
                                             rec, lx, rnd1);

            return 1;
        }
        else /* pad bottom of frame */
        {
            xoffset = (xpos >> 1) & 0x3;
            src = c_prev + (xpos >> 1) - xoffset + (height - 1) * lx;
            dst = src + lx;
            PAD_ROW
            if (xoffset || (xpos&1))
            {
                temp = *((ULong*)(src + 8));
                dst = src + lx + 8;
                *((ULong*)dst) = temp;
                *((ULong*)(dst += lx)) = temp;
                *((ULong*)(dst += lx)) = temp;
                *((ULong*)(dst += lx)) = temp;
                *((ULong*)(dst += lx)) = temp;
                *((ULong*)(dst += lx)) = temp;
                *((ULong*)(dst += lx)) = temp;
                *((ULong*)(dst += lx)) = temp;
            }

            GetPredAdvBTable[ypos&1][xpos&1](c_prev + (xpos >> 1) + ((ypos >> 1)*lx),
                                             rec, lx, rnd1);

            return 1;
        }
    }
    else
    {
        if (ypos < 0) /* pad top-right */
        {
            /* pad corner */
            src = c_prev + width - 1;
            dst = src - (lx << 3) + 1;
            PAD_CORNER

            /* pad top */
            src -= 7;
            dst = src - (lx << 3);
            PAD_ROW

            /* pad left */
            src += 7;
            dst = src + 1;
            PAD_COL

            GetPredAdvBTable[ypos&1][xpos&1](c_prev + (xpos >> 1) + ((ypos >> 1)*lx),
                                             rec, lx, rnd1);

            return 1;
        }
        else if ((ypos >> 1) < (height - B_SIZE)) /* pad right of frame */
        {
            /* pad left */
            src = c_prev + (ypos >> 1) * lx + width - 1;
            dst = src + 1;
            PAD_COL
            /* pad extra row */
            temp = *(src += lx);
            temp |= (temp << 8);
            temp |= (temp << 16);
            *((ULong*)(dst += lx)) = temp;
            *((ULong*)(dst + 4)) = temp;

            GetPredAdvBTable[ypos&1][xpos&1](c_prev + (xpos >> 1) + ((ypos >> 1)*lx),
                                             rec, lx, rnd1);

            return 1;
        }
        else /* pad bottom-right */
        {
            /* pad left */
            src = c_prev + (height - 8) * lx + width - 1;
            dst = src + 1;
            PAD_COL

            /* pad corner */
            dst = src + lx + 1;
            PAD_CORNER

            /* pad bottom */
            src -= 7;
            dst = src + lx;
            PAD_ROW

            GetPredAdvBTable[ypos&1][xpos&1](c_prev + (xpos >> 1) + ((ypos >> 1)*lx),
                                             rec, lx, rnd1);

            return 1;
        }
    }
}

/* ====================================================================== /
    Function : Copy_MB_from_Vop()
    Date     : 04/17/2001
 ====================================================================== */

void Copy_MB_from_Vop(UChar *comp, Int yChan[][NCOEFF_BLOCK], Int pitch)
{
    Int row, col, i;
    Int *src1, *src2;
    Int offset = pitch - MB_SIZE;
    ULong temp;

    for (i = 0; i < 4; i += 2)
    {
        src1 = yChan[i];
        src2 = yChan[i+1];

        row = B_SIZE;
        while (row--)
        {
            col = B_SIZE;
            while (col)
            {
                temp = *((ULong*)comp);
                *src1++ = (Int)(temp & 0xFF);
                *src1++ = (Int)((temp >> 8) & 0xFF);
                *src1++ = (Int)((temp >> 16) & 0xFF);
                *src1++ = (Int)((temp >> 24) & 0xFF);
                comp += 4;
                col -= 4;
            }
            col = B_SIZE;
            while (col)
            {
                temp = *((ULong*)comp);
                *src2++ = (Int)(temp & 0xFF);
                *src2++ = (Int)((temp >> 8) & 0xFF);
                *src2++ = (Int)((temp >> 16) & 0xFF);
                *src2++ = (Int)((temp >> 24) & 0xFF);
                comp += 4;
                col -= 4;
            }
            comp += offset;
        }
    }
    return ;
}

/* ====================================================================== /
    Function : Copy_B_from_Vop()
    Date     : 04/17/2001
/ ====================================================================== */

void Copy_B_from_Vop(UChar *comp, Int cChan[], Int pitch)
{
    Int row, col;
    Int offset = pitch - B_SIZE;
    ULong temp;

    row = B_SIZE;
    while (row--)
    {
        col = B_SIZE;
        while (col)
        {
            temp = *((ULong*)comp);
            *cChan++ = (Int)(temp & 0xFF);
            *cChan++ = (Int)((temp >> 8) & 0xFF);
            *cChan++ = (Int)((temp >> 16) & 0xFF);
            *cChan++ = (Int)((temp >> 24) & 0xFF);
            comp += 4;
            col -= 4;
        }
        comp += offset;
    }
}

/* ====================================================================== /
    Function : Copy_MB_into_Vop()
    Date     : 04/17/2001
    History  : From decoder
/ ====================================================================== */

void Copy_MB_into_Vop(UChar *comp, Int yChan[][NCOEFF_BLOCK], Int pitch)
{
    Int row, col, i;
    Int *src1, *src2;
    Int offset = pitch - MB_SIZE;
    UChar mask = 0xFF;
    Int tmp;
    ULong temp;

    for (i = 0; i < 4; i += 2)
    {
        src1 = yChan[i];
        src2 = yChan[i+1];

        row = B_SIZE;
        while (row--)
        {
            col = B_SIZE;
            while (col)
            {
                tmp = (*src1++);
                if ((UInt)tmp > mask) tmp = mask & (~(tmp >> 31));
                temp = tmp << 24;
                tmp = (*src1++);
                if ((UInt)tmp > mask) tmp = mask & (~(tmp >> 31));
                temp |= (tmp << 16);
                tmp = (*src1++);
                if ((UInt)tmp > mask) tmp = mask & (~(tmp >> 31));
                temp |= (tmp << 8);
                tmp = (*src1++);
                if ((UInt)tmp > mask) tmp = mask & (~(tmp >> 31));
                temp |= tmp;
                *((ULong*)comp) = temp;
                comp += 4;
                col -= 4;
            }
            col = B_SIZE;
            while (col)
            {
                tmp = (*src2++);
                if ((UInt)tmp > mask) tmp = mask & (~(tmp >> 31));
                temp = tmp << 24;
                tmp = (*src2++);
                if ((UInt)tmp > mask) tmp = mask & (~(tmp >> 31));
                temp |= (tmp << 16);
                tmp = (*src2++);
                if ((UInt)tmp > mask) tmp = mask & (~(tmp >> 31));
                temp |= (tmp << 8);
                tmp = (*src2++);
                if ((UInt)tmp > mask) tmp = mask & (~(tmp >> 31));
                temp |= tmp;
                *((ULong*)comp) = temp;
                comp += 4;
                col -= 4;
            }
            comp += offset;
        }
    }
    return ;
}


/* ====================================================================== /
    Function : Copy_B_into_Vop()
    Date     : 04/17/2001
    History  : From decoder
/ ====================================================================== */

void Copy_B_into_Vop(UChar *comp, Int cChan[], Int pitch)
{
    Int row, col;
    Int offset = pitch - B_SIZE;
    Int tmp;
    UChar mask = 0xFF;
    ULong temp;

    row = B_SIZE;
    while (row--)
    {
        col = B_SIZE;
        while (col)
        {
            tmp = (*cChan++);
            if ((UInt)tmp > mask) tmp = mask & (~(tmp >> 31));
            temp = tmp << 24;
            tmp = (*cChan++);
            if ((UInt)tmp > mask) tmp = mask & (~(tmp >> 31));
            temp |= (tmp << 16);
            tmp = (*cChan++);
            if ((UInt)tmp > mask) tmp = mask & (~(tmp >> 31));
            temp |= (tmp << 8);
            tmp = (*cChan++);
            if ((UInt)tmp > mask) tmp = mask & (~(tmp >> 31));
            temp |= tmp;
            *((ULong*)comp) = temp;
            comp += 4;
            col -= 4;
        }
        comp += offset;
    }
}

/* ======================================================================== */
/*  Function : get_MB( )                                                    */
/*  Date     : 10/03/2000                                                   */
/*  Purpose  : Copy 4 Y to reference frame                                  */
/*  In/out   :                                                              */
/*  Return   :                                                              */
/*  Modified :                                                              */
/* ======================================================================== */
void get_MB(UChar *c_prev, UChar *c_prev_u  , UChar *c_prev_v,
            Short mb[6][64], Int lx, Int lx_uv)

{
    Int i, j, count = 0, count1 = 0;
    Int k1 = lx - MB_SIZE, k2 = lx_uv - B_SIZE;

    for (i = 0; i < B_SIZE; i++)
    {
        for (j = 0; j < B_SIZE; j++)
        {
            mb[0][count] = (Int)(*c_prev++);
            mb[4][count] = (Int)(*c_prev_u++);
            mb[5][count++] = (Int)(*c_prev_v++);
        }

        for (j = 0; j < B_SIZE; j++)
            mb[1][count1++] = (Int)(*c_prev++);

        c_prev += k1;
        c_prev_u += k2;
        c_prev_v += k2;


    }

    count = count1 = 0;
    for (i = 0; i < B_SIZE; i++)
    {
        for (j = 0; j < B_SIZE; j++)
            mb[2][count++] = (Int)(*c_prev++);

        for (j = 0; j < B_SIZE; j++)
            mb[3][count1++] = (Int)(*c_prev++);

        c_prev += k1;
    }
}

void PutSkippedBlock(UChar *rec, UChar *prev, Int lx)
{
    UChar *end;
    Int offset = (lx - 8) >> 2;
    Int *src, *dst;

    dst = (Int*)rec;
    src = (Int*)prev;

    end = prev + (lx << 3);

    do
    {
        *dst++ = *src++;
        *dst++ = *src++;
        dst += offset;
        src += offset;
    }
    while ((UInt)src < (UInt)end);

    return ;
}
