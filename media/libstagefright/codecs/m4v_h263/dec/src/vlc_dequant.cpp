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
#include "mp4dec_lib.h"
#include "vlc_decode.h"
#include "zigzag.h"


typedef PV_STATUS(*VlcDecFuncP)(BitstreamDecVideo *stream, Tcoef *pTcoef);
static const uint8 AC_rowcol[64] = {    0, 0, 0, 0, 0, 0, 0, 0,
                                        0, 1, 1, 1, 1, 1, 1, 1,
                                        0, 1, 1, 1, 1, 1, 1, 1,
                                        0, 1, 1, 1, 1, 1, 1, 1,
                                        0, 1, 1, 1, 1, 1, 1, 1,
                                        0, 1, 1, 1, 1, 1, 1, 1,
                                        0, 1, 1, 1, 1, 1, 1, 1,
                                        0, 1, 1, 1, 1, 1, 1, 1,
                                   };
static const uint8 mask[8] = /*  for fast bitmap */
    {128, 64, 32, 16, 8, 4, 2, 1};



/***********************************************************CommentBegin******
*
* -- VlcDequantMpegBlock -- Decodes the DCT coefficients of one 8x8 block and perform
            dequantization using Mpeg mode.
    Date:       08/08/2000

    Modified:      3/21/01
                Added pre IDCT clipping, new ACDC prediction structure, ACDC prediction clipping,
                16-bit int case, removed multiple zigzaging
******************************************************************************/

#ifdef PV_SUPPORT_MAIN_PROFILE
int VlcDequantMpegIntraBlock(void *vid, int comp, int switched,
                             uint8 *bitmapcol, uint8 *bitmaprow)
{
    VideoDecData *video = (VideoDecData*) vid;
    Vol *currVol = video->vol[video->currLayer];
    BitstreamDecVideo *stream = video->bitstream;
    int16 *datablock = video->mblock->block[comp]; /* 10/20/2000, assume it has been reset of all-zero !!!*/
    int mbnum = video->mbnum;
    uint CBP = video->headerInfo.CBP[mbnum];
    int QP = video->QPMB[mbnum];
    typeDCStore *DC = video->predDC + mbnum;
    int x_pos = video->mbnum_col;
    typeDCACStore *DCAC_row = video->predDCAC_row + x_pos;
    typeDCACStore *DCAC_col = video->predDCAC_col;
    uint ACpred_flag = (uint) video->acPredFlag[mbnum];

    /*** VLC *****/
    int i, j, k;
    Tcoef run_level;
    int last, return_status;
    VlcDecFuncP vlcDecCoeff;
    int direction;
    const int *inv_zigzag;
    /*** Quantizer ****/
    int dc_scaler;
    int sum;
    int *qmat;
    int32 temp;

    const int B_Xtab[6] = {0, 1, 0, 1, 2, 3};
    const int B_Ytab[6] = {0, 0, 1, 1, 2, 3};

    int16 *dcac_row, *dcac_col;

    dcac_row = (*DCAC_row)[B_Xtab[comp]];
    dcac_col = (*DCAC_col)[B_Ytab[comp]];


    i = 1 - switched;

#ifdef FAST_IDCT
    *((uint32*)bitmapcol) = *((uint32*)(bitmapcol + 4)) = 0;
    *bitmaprow = 0;
#endif


    /* select which Huffman table to be used */
    vlcDecCoeff = video->vlcDecCoeffIntra;

    dc_scaler = (comp < 4) ? video->mblock->DCScalarLum : video->mblock->DCScalarChr;

    /* enter the zero run decoding loop */
    sum = 0;
    qmat = currVol->iqmat;

    /* perform only VLC decoding */
    /* We cannot do DCACrecon before VLC decoding.  10/17/2000 */
    doDCACPrediction(video, comp, datablock, &direction);
    if (!ACpred_flag) direction = 0;
    inv_zigzag = zigzag_inv + (ACpred_flag << 6) + (direction << 6);
    if (CBP & (1 << (5 - comp)))
    {
        do
        {
            return_status = (*vlcDecCoeff)(stream, &run_level);
            if (return_status != PV_SUCCESS)
            {
                last = 1;/*  11/1/2000 let it slips undetected, just like
                         in original version */
                i = VLC_ERROR;
                ACpred_flag = 0;    /* no of coefficients should not get reset   03/07/2002 */
                break;
            }

            i += run_level.run;
            last = run_level.last;
            if (i >= 64)
            {
                /*  i = NCOEFF_BLOCK; */    /*  11/1/00 */
                ACpred_flag = 0;    /* no of coefficients should not get reset   03/07/2002 */
                i = VLC_NO_LAST_BIT;
                last = 1;
                break;
            }

            k = inv_zigzag[i];

            if (run_level.sign == 1)
            {
                datablock[k] -= run_level.level;
            }
            else
            {
                datablock[k] += run_level.level;
            }

            if (AC_rowcol[k])
            {
                temp = (int32)datablock[k] * qmat[k] * QP;
                temp = (temp + (0x7 & (temp >> 31))) >> 3;
                if (temp > 2047) temp = 2047;
                else if (temp < -2048) temp = -2048;
                datablock[k] = (int) temp;

#ifdef FAST_IDCT
                bitmapcol[k&0x7] |= mask[k>>3];
#endif
                sum ^= temp;
            }

            i++;
        }
        while (!last);

    }
    else
    {
        i = 1;       /*  04/26/01  needed for switched case */
    }
    ///// NEED TO DEQUANT THOSE PREDICTED AC COEFF
    /* dequantize the rest of AC predicted coeff that haven't been dequant */
    if (ACpred_flag)
    {

        i = NCOEFF_BLOCK; /* otherwise, FAST IDCT won't work correctly,  10/18/2000 */

        if (!direction) /* check vertical */
        {
            dcac_row[0]  = datablock[1];
            dcac_row[1]  = datablock[2];
            dcac_row[2]  = datablock[3];
            dcac_row[3]  = datablock[4];
            dcac_row[4]  = datablock[5];
            dcac_row[5]  = datablock[6];
            dcac_row[6]  = datablock[7];

            for (j = 0, k = 8; k < 64; k += 8, j++)
            {
                if (dcac_col[j] = datablock[k])
                {     /* ACDC clipping  03/26/01 */
                    if (datablock[k] > 2047) dcac_col[j] = 2047;
                    else if (datablock[k] < -2048) dcac_col[j] = -2048;

                    temp = (int32)dcac_col[j] * qmat[k] * QP;
                    temp = (temp + (0x7 & (temp >> 31))) >> 3;  /*  03/26/01*/
                    if (temp > 2047) temp = 2047;
                    else if (temp < -2048) temp = -2048;
                    datablock[k] = (int)temp;
                    sum ^= temp; /*  7/5/01 */
#ifdef FAST_IDCT
                    bitmapcol[0] |= mask[k>>3];
#endif

                }
            }
            for (k = 1; k < 8; k++)
            {
                if (datablock[k])
                {
                    temp = (int32)datablock[k] * qmat[k] * QP;
                    temp = (temp + (0x7 & (temp >> 31))) >> 3;  /*  03/26/01*/
                    if (temp > 2047) temp = 2047;
                    else if (temp < -2048) temp = -2048;
                    datablock[k] = (int)temp;
                    sum ^= temp; /*  7/5/01 */
#ifdef FAST_IDCT
                    bitmapcol[k] |= 128;
#endif

                }
            }

        }
        else
        {

            dcac_col[0]  = datablock[8];
            dcac_col[1]  = datablock[16];
            dcac_col[2]  = datablock[24];
            dcac_col[3]  = datablock[32];
            dcac_col[4]  = datablock[40];
            dcac_col[5]  = datablock[48];
            dcac_col[6]  = datablock[56];


            for (j = 0, k = 1; k < 8; k++, j++)
            {
                if (dcac_row[j] = datablock[k])
                {     /* ACDC clipping  03/26/01 */
                    if (datablock[k] > 2047) dcac_row[j] = 2047;
                    else if (datablock[k] < -2048) dcac_row[j] = -2048;

                    temp = (int32)dcac_row[j] * qmat[k] * QP;
                    temp = (temp + (0x7 & (temp >> 31))) >> 3;  /*  03/26/01 */
                    if (temp > 2047) temp = 2047;
                    else if (temp < -2048) temp = -2048;
                    datablock[k] = (int)temp;
                    sum ^= temp;
#ifdef FAST_IDCT
                    bitmapcol[k] |= 128;
#endif

                }
            }

            for (k = 8; k < 64; k += 8)
            {
                if (datablock[k])
                {
                    temp = (int32)datablock[k] * qmat[k] * QP;
                    temp = (temp + (0x7 & (temp >> 31))) >> 3;  /*  03/26/01 */
                    if (temp > 2047) temp = 2047;
                    else if (temp < -2048) temp = -2048;
                    datablock[k] = (int)temp;
                    sum ^= temp;
#ifdef FAST_IDCT
                    bitmapcol[0] |= mask[k>>3];
#endif
                }
            }

        }
    }
    else
    {

        /* Store the qcoeff-values needed later for prediction */

        dcac_row[0] = datablock[1];                /*  ACDC, no need for clipping */
        dcac_row[1] = datablock[2];
        dcac_row[2] = datablock[3];
        dcac_row[3] = datablock[4];
        dcac_row[4] = datablock[5];
        dcac_row[5] = datablock[6];
        dcac_row[6] = datablock[7];

        dcac_col[0] = datablock[8];
        dcac_col[1] = datablock[16];
        dcac_col[2] = datablock[24];
        dcac_col[3] = datablock[32];
        dcac_col[4] = datablock[40];
        dcac_col[5] = datablock[48];
        dcac_col[6] = datablock[56];

        for (k = 1; k < 8; k++)
        {
            if (datablock[k])
            {
                temp = (int32)datablock[k] * qmat[k] * QP;
                temp = (temp + (0x7 & (temp >> 31))) >> 3;  /*  03/26/01*/
                if (temp > 2047) temp = 2047;
                else if (temp < -2048) temp = -2048;
                datablock[k] = (int)temp;
                sum ^= temp; /*  7/5/01 */
#ifdef FAST_IDCT
                bitmapcol[k] |= 128;
#endif

            }
        }
        for (k = 8; k < 64; k += 8)
        {
            if (datablock[k])
            {
                temp = (int32)datablock[k] * qmat[k] * QP;
                temp = (temp + (0x7 & (temp >> 31))) >> 3;  /*  03/26/01 */
                if (temp > 2047) temp = 2047;
                else if (temp < -2048) temp = -2048;
                datablock[k] = (int)temp;
                sum ^= temp;
#ifdef FAST_IDCT
                bitmapcol[0] |= mask[k>>3];
#endif
            }
        }

    }



    if (datablock[0])
    {
        temp = (int32)datablock[0] * dc_scaler;
        if (temp > 2047) temp = 2047;            /*  03/14/01 */
        else if (temp < -2048)  temp = -2048;
        datablock[0] = (int)temp;
        sum ^= temp;
#ifdef FAST_IDCT
        bitmapcol[0] |= 128;
#endif
    }

    if ((sum & 1) == 0)
    {
        datablock[63] = datablock[63] ^ 0x1;
#ifdef FAST_IDCT   /*  7/5/01, need to update bitmap */
        if (datablock[63])
            bitmapcol[7] |= 1;
#endif
        i = (-64 & i) | NCOEFF_BLOCK;   /*  if i > -1 then i is set to NCOEFF_BLOCK */
    }


#ifdef FAST_IDCT
    if (i > 10)
    {
        for (k = 1; k < 4; k++)
        {
            if (bitmapcol[k] != 0)
            {
                (*bitmaprow) |= mask[k];
            }
        }
    }
#endif

    /* Store the qcoeff-values needed later for prediction */
    (*DC)[comp] = datablock[0];
    return i;

}


/***********************************************************CommentBegin******
*
* -- VlcDequantMpegInterBlock -- Decodes the DCT coefficients of one 8x8 block and perform
            dequantization using Mpeg mode for INTER block.
    Date:       08/08/2000
    Modified:              3/21/01
                clean up, added clipping, 16-bit int case, new ACDC prediction
******************************************************************************/


int VlcDequantMpegInterBlock(void *vid, int comp,
                             uint8 *bitmapcol, uint8 *bitmaprow)
{
    VideoDecData *video = (VideoDecData*) vid;
    BitstreamDecVideo *stream = video->bitstream;
    Vol *currVol = video->vol[video->currLayer];
    int16 *datablock = video->mblock->block[comp]; /* 10/20/2000, assume it has been reset of all-zero !!!*/
    int mbnum = video->mbnum;
    int QP = video->QPMB[mbnum];
    /*** VLC *****/
    int i, k;
    Tcoef run_level;
    int last, return_status;
    VlcDecFuncP vlcDecCoeff;

    /*** Quantizer ****/
    int sum;
    int *qmat;

    int32 temp;

    i = 0 ;

#ifdef FAST_IDCT
    *((uint32*)bitmapcol) = *((uint32*)(bitmapcol + 4)) = 0;
    *bitmaprow = 0;
#endif

    /* select which Huffman table to be used */
    vlcDecCoeff = video->vlcDecCoeffInter;

    /* enter the zero run decoding loop */
    sum = 0;
    qmat = currVol->niqmat;
    do
    {
        return_status = (*vlcDecCoeff)(stream, &run_level);
        if (return_status != PV_SUCCESS)
        {
            last = 1;/*  11/1/2000 let it slips undetected, just like
                     in original version */
            i = VLC_ERROR;
            sum = 1;    /* no of coefficients should not get reset   03/07/2002 */
            break;
        }

        i += run_level.run;
        last = run_level.last;
        if (i >= 64)
        {
            /*  i = NCOEFF_BLOCK; */    /*  11/1/00 */
            //return VLC_NO_LAST_BIT;
            i = VLC_NO_LAST_BIT;
            last = 1;
            sum = 1;    /* no of coefficients should not get reset   03/07/2002 */
            break;
        }

        k = zigzag_inv[i];

        if (run_level.sign == 1)
        {
            temp = (-(int32)(2 * run_level.level + 1) * qmat[k] * QP + 15) >> 4; /*  03/23/01 */
            if (temp < -2048) temp = - 2048;
        }
        else
        {
            temp = ((int32)(2 * run_level.level + 1) * qmat[k] * QP) >> 4; /*  03/23/01 */
            if (temp > 2047) temp = 2047;
        }

        datablock[k] = (int)temp;

#ifdef FAST_IDCT
        bitmapcol[k&0x7] |= mask[k>>3];
#endif
        sum ^= temp;

        i++;
    }
    while (!last);

    if ((sum & 1) == 0)
    {
        datablock[63] = datablock[63] ^ 0x1;
#ifdef FAST_IDCT   /*  7/5/01, need to update bitmap */
        if (datablock[63])
            bitmapcol[7] |= 1;
#endif
        i = NCOEFF_BLOCK;
    }


#ifdef FAST_IDCT
    if (i > 10)
    {
        for (k = 1; k < 4; k++)               /*  07/19/01 */
        {
            if (bitmapcol[k] != 0)
            {
                (*bitmaprow) |= mask[k];
            }
        }
    }
#endif

    return i;
}
#endif
/***********************************************************CommentBegin******
*
* -- VlcDequantIntraH263Block -- Decodes the DCT coefficients of one 8x8 block and perform
            dequantization in H.263 mode for INTRA block.
    Date:       08/08/2000
    Modified:               3/21/01
                clean up, added clipping, 16-bit int case, removed multiple zigzaging
******************************************************************************/


int VlcDequantH263IntraBlock(VideoDecData *video, int comp, int switched,
                             uint8 *bitmapcol, uint8 *bitmaprow)
{
    BitstreamDecVideo *stream = video->bitstream;
    int16 *datablock = video->mblock->block[comp]; /* 10/20/2000, assume it has been reset of all-zero !!!*/
    int32 temp;
    int mbnum = video->mbnum;
    uint CBP = video->headerInfo.CBP[mbnum];
    int QP = video->QPMB[mbnum];
    typeDCStore *DC = video->predDC + mbnum;
    int x_pos = video->mbnum_col;
    typeDCACStore *DCAC_row = video->predDCAC_row + x_pos;
    typeDCACStore *DCAC_col = video->predDCAC_col;
    uint ACpred_flag = (uint) video->acPredFlag[mbnum];

    /*** VLC *****/
    int i, j, k;
    Tcoef run_level;
    int last, return_status;
    VlcDecFuncP vlcDecCoeff;
    int direction;
    const int *inv_zigzag;

    /*** Quantizer ****/
    int dc_scaler;
    int sgn_coeff;



    const int B_Xtab[6] = {0, 1, 0, 1, 2, 3};
    const int B_Ytab[6] = {0, 0, 1, 1, 2, 3};

    int16 *dcac_row, *dcac_col;

    dcac_row = (*DCAC_row)[B_Xtab[comp]];
    dcac_col = (*DCAC_col)[B_Ytab[comp]];

#ifdef FAST_IDCT
    *((uint32*)bitmapcol) = *((uint32*)(bitmapcol + 4)) = 0;
    *bitmaprow = 0;
#endif
    /* select which Huffman table to be used */
    vlcDecCoeff = video->vlcDecCoeffIntra;

    dc_scaler = (comp < 4) ? video->mblock->DCScalarLum : video->mblock->DCScalarChr;

    /* perform only VLC decoding */
    doDCACPrediction(video, comp, datablock, &direction);
    if (!ACpred_flag) direction = 0;

    inv_zigzag = zigzag_inv + (ACpred_flag << 6) + (direction << 6);  /*  04/17/01 */

    i = 1;
    if (CBP & (1 << (5 - comp)))
    {
        i = 1 - switched;
        do
        {
            return_status = (*vlcDecCoeff)(stream, &run_level);
            if (return_status != PV_SUCCESS)
            {
                last = 1;/* 11/1/2000 let it slips undetected, just like
                         in original version */
                i = VLC_ERROR;
                ACpred_flag = 0;   /* no of coefficients should not get reset   03/07/2002 */
                break;
            }

            i += run_level.run;
            last = run_level.last;
            if (i >= 64)
            {
                ACpred_flag = 0;    /* no of coefficients should not get reset   03/07/2002 */
                i = VLC_NO_LAST_BIT;
                last = 1;
                break;
            }

            k = inv_zigzag[i];

            if (run_level.sign == 1)
            {
                datablock[k] -= run_level.level;
                sgn_coeff = -1;
            }
            else
            {
                datablock[k] += run_level.level;
                sgn_coeff = 1;
            }


            if (AC_rowcol[k])   /*  10/25/2000 */
            {
                temp = (int32)QP * (2 * datablock[k] + sgn_coeff) - sgn_coeff + (QP & 1) * sgn_coeff;
                if (temp > 2047) temp = 2047;            /*  03/14/01 */
                else if (temp < -2048)  temp = -2048;
                datablock[k] = (int16) temp;

#ifdef FAST_IDCT
                bitmapcol[k&0x7] |= mask[k>>3];
#endif
            }

            i++;
        }
        while (!last);

    }

    ///// NEED TO DEQUANT THOSE PREDICTED AC COEFF
    /* dequantize the rest of AC predicted coeff that haven't been dequant */
    if (ACpred_flag)
    {

        i = NCOEFF_BLOCK; /* otherwise, FAST IDCT won't work correctly,  10/18/2000 */

        if (!direction) /* check vertical */
        {

            dcac_row[0]  = datablock[1];
            dcac_row[1]  = datablock[2];
            dcac_row[2]  = datablock[3];
            dcac_row[3]  = datablock[4];
            dcac_row[4]  = datablock[5];
            dcac_row[5]  = datablock[6];
            dcac_row[6]  = datablock[7];

            for (j = 0, k = 8; k < 64; k += 8, j++)
            {
                dcac_col[j] = datablock[k];
                if (dcac_col[j])
                {
                    if (datablock[k] > 0)
                    {
                        if (datablock[k] > 2047) dcac_col[j] = 2047;
                        sgn_coeff = 1;
                    }
                    else
                    {
                        if (datablock[k] < -2048) dcac_col[j] = -2048;
                        sgn_coeff = -1;
                    }
                    temp = (int32)QP * (2 * datablock[k] + sgn_coeff) - sgn_coeff + (QP & 1) * sgn_coeff;
                    if (temp > 2047) temp = 2047;            /*  03/14/01 */
                    else if (temp < -2048)  temp = -2048;
                    datablock[k] = (int16) temp;
#ifdef FAST_IDCT
                    bitmapcol[0] |= mask[k>>3];
#endif

                }
            }

            for (k = 1; k < 8; k++)
            {
                if (datablock[k])
                {
                    sgn_coeff = (datablock[k] > 0) ? 1 : -1;
                    temp = (int32)QP * (2 * datablock[k] + sgn_coeff) - sgn_coeff + (QP & 1) * sgn_coeff;
                    if (temp > 2047) temp = 2047;            /*  03/14/01 */
                    else if (temp < -2048)  temp = -2048;
                    datablock[k] = (int16) temp;
#ifdef FAST_IDCT
                    bitmapcol[k] |= 128;
#endif

                }
            }
        }
        else
        {

            dcac_col[0]  = datablock[8];
            dcac_col[1]  = datablock[16];
            dcac_col[2]  = datablock[24];
            dcac_col[3]  = datablock[32];
            dcac_col[4]  = datablock[40];
            dcac_col[5]  = datablock[48];
            dcac_col[6]  = datablock[56];


            for (j = 0, k = 1; k < 8; k++, j++)
            {
                dcac_row[j] = datablock[k];
                if (dcac_row[j])
                {
                    if (datablock[k] > 0)
                    {
                        if (datablock[k] > 2047) dcac_row[j] = 2047;
                        sgn_coeff = 1;
                    }
                    else
                    {
                        if (datablock[k] < -2048) dcac_row[j] = -2048;
                        sgn_coeff = -1;
                    }

                    temp = (int32)QP * (2 * datablock[k] + sgn_coeff) - sgn_coeff + (QP & 1) * sgn_coeff;
                    if (temp > 2047) temp = 2047;            /*  03/14/01 */
                    else if (temp < -2048)  temp = -2048;
                    datablock[k] = (int) temp;
#ifdef FAST_IDCT
                    bitmapcol[k] |= 128;
#endif

                }
            }
            for (k = 8; k < 64; k += 8)
            {
                if (datablock[k])
                {
                    sgn_coeff = (datablock[k] > 0) ? 1 : -1;
                    temp = (int32)QP * (2 * datablock[k] + sgn_coeff) - sgn_coeff + (QP & 1) * sgn_coeff;
                    if (temp > 2047) temp = 2047;            /*  03/14/01 */
                    else if (temp < -2048)  temp = -2048;
                    datablock[k] = (int16) temp;
#ifdef FAST_IDCT
                    bitmapcol[0] |= mask[k>>3];
#endif
                }
            }

        }
    }
    else
    {
        dcac_row[0]  = datablock[1];
        dcac_row[1]  = datablock[2];
        dcac_row[2]  = datablock[3];
        dcac_row[3]  = datablock[4];
        dcac_row[4]  = datablock[5];
        dcac_row[5]  = datablock[6];
        dcac_row[6]  = datablock[7];

        dcac_col[0]  = datablock[8];
        dcac_col[1]  = datablock[16];
        dcac_col[2]  = datablock[24];
        dcac_col[3]  = datablock[32];
        dcac_col[4]  = datablock[40];
        dcac_col[5]  = datablock[48];
        dcac_col[6]  = datablock[56];

        for (k = 1; k < 8; k++)
        {
            if (datablock[k])
            {
                sgn_coeff = (datablock[k] > 0) ? 1 : -1;
                temp = (int32)QP * (2 * datablock[k] + sgn_coeff) - sgn_coeff + (QP & 1) * sgn_coeff;
                if (temp > 2047) temp = 2047;            /*  03/14/01 */
                else if (temp < -2048)  temp = -2048;
                datablock[k] = (int16) temp;
#ifdef FAST_IDCT
                bitmapcol[k] |= 128;
#endif
            }
        }
        for (k = 8; k < 64; k += 8)
        {
            if (datablock[k])
            {
                sgn_coeff = (datablock[k] > 0) ? 1 : -1;
                temp = (int32)QP * (2 * datablock[k] + sgn_coeff) - sgn_coeff + (QP & 1) * sgn_coeff;
                if (temp > 2047) temp = 2047;            /*  03/14/01 */
                else if (temp < -2048)  temp = -2048;
                datablock[k] = (int16) temp;
#ifdef FAST_IDCT
                bitmapcol[0] |= mask[k>>3];
#endif
            }
        }
    }
    if (datablock[0])
    {
#ifdef FAST_IDCT
        bitmapcol[0] |= 128;
#endif

        temp = (int32)datablock[0] * dc_scaler;
        if (temp > 2047) temp = 2047;            /*  03/14/01 */
        else if (temp < -2048)  temp = -2048;
        datablock[0] = (int16)temp;
    }


#ifdef FAST_IDCT
    if (i > 10)
    {
        for (k = 1; k < 4; k++)  /* if i > 10 then k = 0 does not matter  */
        {
            if (bitmapcol[k] != 0)
            {
                (*bitmaprow) |= mask[k]; /* (1<<(7-i)); */
            }
        }
    }
#endif

    /* Store the qcoeff-values needed later for prediction */
    (*DC)[comp] = datablock[0];
    return i;
}

int VlcDequantH263IntraBlock_SH(VideoDecData *video, int comp, uint8 *bitmapcol, uint8 *bitmaprow)
{
    BitstreamDecVideo *stream = video->bitstream;
    int16 *datablock = video->mblock->block[comp]; /*, 10/20/2000, assume it has been reset of all-zero !!!*/
    int32 temp;
    int mbnum = video->mbnum;
    uint CBP = video->headerInfo.CBP[mbnum];
    int16 QP = video->QPMB[mbnum];
    typeDCStore *DC = video->predDC + mbnum;
    int x_pos = video->mbnum_col;
    typeDCACStore *DCAC_row = video->predDCAC_row + x_pos;
    typeDCACStore *DCAC_col = video->predDCAC_col;
    uint ACpred_flag = (uint) video->acPredFlag[mbnum];

    /*** VLC *****/
    int i, k;
    Tcoef run_level;
    int last, return_status;
    VlcDecFuncP vlcDecCoeff;
#ifdef PV_ANNEX_IJKT_SUPPORT
    int direction;
    const int *inv_zigzag;
#endif
    /*** Quantizer ****/



    const int B_Xtab[6] = {0, 1, 0, 1, 2, 3};
    const int B_Ytab[6] = {0, 0, 1, 1, 2, 3};

    int16 *dcac_row, *dcac_col;

    dcac_row = (*DCAC_row)[B_Xtab[comp]];
    dcac_col = (*DCAC_col)[B_Ytab[comp]];
    i = 1;

#ifdef FAST_IDCT
    *((uint32*)bitmapcol) = *((uint32*)(bitmapcol + 4)) = 0;
    *bitmaprow = 0;
#endif

    /* select which Huffman table to be used */
    vlcDecCoeff = video->vlcDecCoeffIntra;

#ifdef PV_ANNEX_IJKT_SUPPORT
    if (comp > 3)        /* ANNEX_T */
    {
        QP = video->QP_CHR;
    }
    if (!video->advanced_INTRA)
    {
#endif

        if ((CBP & (1 << (5 - comp))) == 0)
        {
#ifdef FAST_IDCT
            bitmapcol[0] = 128;
            bitmapcol[1] = bitmapcol[2] = bitmapcol[3] = bitmapcol[4] = bitmapcol[5] = bitmapcol[6] = bitmapcol[7] = 0;
#endif
            datablock[0] <<= 3;  /* no need to clip */
            return 1;//ncoeffs;
        }
        else
        {
            /* enter the zero run decoding loop */
            do
            {
                return_status = (*vlcDecCoeff)(stream, &run_level);
                if (return_status != PV_SUCCESS)
                {
                    last = 1;/*  11/1/2000 let it slips undetected, just like
                             in original version */
                    i = VLC_ERROR;
                    break;
                }

                i += run_level.run;
                last = run_level.last;
                if (i >= 64)
                {
                    /*  i = NCOEFF_BLOCK; */    /*  11/1/00 */
                    i = VLC_NO_LAST_BIT;
                    last = 1;
                    break;
                }
                k = zigzag_inv[i];

                if (run_level.sign == 0)
                {
                    temp = (int32)QP * (2 * run_level.level + 1) - 1 + (QP & 1);
                    if (temp > 2047) temp = 2047;
                }
                else
                {
                    temp = -(int32)QP * (2 * run_level.level + 1) + 1 - (QP & 1);
                    if (temp < -2048) temp = -2048;
                }


                datablock[k] = (int16) temp;

#ifdef FAST_IDCT
                bitmapcol[k&0x7] |= mask[k>>3];
#endif
                i++;
            }
            while (!last);

        }
        /* no ACDC prediction when ACDC disable  */
        if (datablock[0])
        {
#ifdef FAST_IDCT
            bitmapcol[0] |= 128;
#endif
            datablock[0] <<= 3;        /* no need to clip  09/18/2001 */
        }
#ifdef PV_ANNEX_IJKT_SUPPORT
    }
    else  /* advanced_INTRA mode */
    {
        i = 1;
        doDCACPrediction_I(video, comp, datablock);
        /* perform only VLC decoding */
        if (!ACpred_flag)
        {
            direction = 0;
        }
        else
        {
            direction = video->mblock->direction;
        }

        inv_zigzag = zigzag_inv + (ACpred_flag << 6) + (direction << 6);  /*  04/17/01 */

        if (CBP & (1 << (5 - comp)))
        {
            i = 0;
            do
            {
                return_status = (*vlcDecCoeff)(stream, &run_level);
                if (return_status != PV_SUCCESS)
                {
                    last = 1;/*  11/1/2000 let it slips undetected, just like
                                 in original version */
                    i = VLC_ERROR;
                    ACpred_flag = 0;   /* no of coefficients should not get reset   03/07/2002 */
                    break;
                }

                i += run_level.run;
                last = run_level.last;
                if (i >= 64)
                {
                    /*                  i = NCOEFF_BLOCK; */    /*  11/1/00 */
                    ACpred_flag = 0;    /* no of coefficients should not get reset   03/07/2002 */
                    i = VLC_NO_LAST_BIT;
                    last = 1;
                    break;
                }

                k = inv_zigzag[i];

                if (run_level.sign == 0)
                {
                    datablock[k] += (int16)QP * 2 * run_level.level;
                    if (datablock[k] > 2047) datablock[k] = 2047;
                }
                else
                {
                    datablock[k] -= (int16)QP * 2 * run_level.level;
                    if (datablock[k] < -2048) datablock[k] = -2048;
                }
#ifdef FAST_IDCT
                bitmapcol[k&0x7] |= mask[k>>3];
#endif

                i++;
            }
            while (!last);

        }
        ///// NEED TO DEQUANT THOSE PREDICTED AC COEFF
        /* dequantize the rest of AC predicted coeff that haven't been dequant */

        if (ACpred_flag)
        {
            i = NCOEFF_BLOCK;
            for (k = 1; k < 8; k++)
            {
                if (datablock[k])
                {
                    bitmapcol[k] |= 128;
                }

                if (datablock[k<<3])
                {
                    bitmapcol[0] |= mask[k];
                }
            }
        }

        dcac_row[0]  = datablock[1];
        dcac_row[1]  = datablock[2];
        dcac_row[2]  = datablock[3];
        dcac_row[3]  = datablock[4];
        dcac_row[4]  = datablock[5];
        dcac_row[5]  = datablock[6];
        dcac_row[6]  = datablock[7];

        dcac_col[0]  = datablock[8];
        dcac_col[1]  = datablock[16];
        dcac_col[2]  = datablock[24];
        dcac_col[3]  = datablock[32];
        dcac_col[4]  = datablock[40];
        dcac_col[5]  = datablock[48];
        dcac_col[6]  = datablock[56];

        if (datablock[0])
        {
#ifdef FAST_IDCT
            bitmapcol[0] |= 128;
#endif

            datablock[0] |= 1;
            if (datablock[0] < 0)
            {
                datablock[0] = 0;
            }
        }
    }
#endif

#ifdef FAST_IDCT
    if (i > 10)
    {
        for (k = 1; k < 4; k++)  /* if i > 10 then k = 0 does not matter  */
        {
            if (bitmapcol[k] != 0)
            {
                (*bitmaprow) |= mask[k]; /* (1<<(7-i)); */
            }
        }
    }
#endif

    /* Store the qcoeff-values needed later for prediction */
    (*DC)[comp] = datablock[0];
    return i;
}

/***********************************************************CommentBegin******
*
* -- VlcDequantInterH263Block -- Decodes the DCT coefficients of one 8x8 block and perform
            dequantization in H.263 mode for INTER block.
    Date:       08/08/2000
    Modified:             3/21/01
                clean up, added clipping, 16-bit int case
******************************************************************************/


int VlcDequantH263InterBlock(VideoDecData *video, int comp,
                             uint8 *bitmapcol, uint8 *bitmaprow)
{
    BitstreamDecVideo *stream = video->bitstream;
    int16 *datablock = video->mblock->block[comp]; /* 10/20/2000, assume it has been reset of all-zero !!!*/
    int32 temp;
    int mbnum = video->mbnum;
    int QP = video->QPMB[mbnum];

    /*** VLC *****/
    int i, k;
    Tcoef run_level;
    int last, return_status;
    VlcDecFuncP vlcDecCoeff;

    /*** Quantizer ****/


    i = 0;

#ifdef FAST_IDCT
    *((uint32*)bitmapcol) = *((uint32*)(bitmapcol + 4)) = 0;
    *bitmaprow = 0;
#endif

    /* select which Huffman table to be used */
    vlcDecCoeff = video->vlcDecCoeffInter;

    /* enter the zero run decoding loop */
    do
    {
        return_status = (*vlcDecCoeff)(stream, &run_level);
        if (return_status != PV_SUCCESS)
        {


            last = 1;/*  11/1/2000 let it slips undetected, just like
                     in original version */
            i = -1;
            break;
        }

        i += run_level.run;
        last = run_level.last;
        if (i >= 64)
        {
            i = -1;
            last = 1;
            break;
        }

        if (run_level.sign == 0)
        {
            temp = (int32)QP * (2 * run_level.level + 1) - 1 + (QP & 1);
            if (temp > 2047) temp = 2047;

        }
        else
        {
            temp = -(int32)QP * (2 * run_level.level + 1) + 1 - (QP & 1);
            if (temp < -2048) temp = -2048;
        }

        k = zigzag_inv[i];
        datablock[k] = (int16)temp;
#ifdef FAST_IDCT
        bitmapcol[k&0x7] |= mask[k>>3];
#endif
        i++;
    }
    while (!last);

#ifdef FAST_IDCT
    if (i > 10)         /*  07/19/01 */
    {
        for (k = 1; k < 4; k++)       /*  if (i > 10 ) k = 0 does not matter */
        {
            if (bitmapcol[k] != 0)
            {
                (*bitmaprow) |= mask[k]; /* (1<<(7-i)); */
            }
        }
    }
#endif
    return i;
}

