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
#include "mp4enc_lib.h"
#include "fastquant_inline.h"

#define siz 63
#define LSL 18


const static UChar imask[8] = {128, 64, 32, 16, 8, 4, 2, 1};
#define SIGN0(a)        ( ((a)<0) ? -1 : (((a)>0) ? 1  : 0) )

/* variable bit precision quantization scale */
/* used to avoid using 32-bit multiplication */
const static Short scaleArrayV[32] = {0, 16384, 8192, 5462,  /* 15 */
                                      4096, 3277, 2731, 2341,
                                      4096, 3641, 3277, 2979,  /* 16 */
                                      2731, 2521, 2341, 2185,
                                      4096, 3856, 3641, 3450,  /* 17 */
                                      3277, 3121, 2979, 2850,
                                      5462, 5243, 5042, 4855,  /* 18 */
                                      4682, 4520, 4370, 4229
                                     };

/* scale for dc_scaler and qmat, note, no value smaller than 8 */
const static Short scaleArrayV2[47] = {0, 0, 0, 0, 0, 0, 0, 0, /* 15 */
                                       4096, 3641, 3277, 2979, 2731, 2521, 2341, 2185,
                                       4096, 3856, 3641, 3450, 3277, 3121, 2979, 2850,  /* 16 */
                                       2731, 2622, 2521, 2428, 2341, 2260, 2185, 2115,
                                       4096, 3972, 3856, 3745, 3641, 3543, 3450, 3361,  /* 17 */
                                       3277, 3197, 3121, 3049, 2979, 2913, 2850
                                      };

/* AAN scale and zigzag */
const static Short AANScale[64] =
{
    /* 0 */ 0x1000, 0x0B89, 0x0C3E, 0x0D9B, 0x1000, 0x0A2E, 0x0EC8, 0x0E7F,
    /* 1 */ 0x0B89, 0x0851, 0x08D4, 0x09CF, 0x0B89, 0x0757, 0x0AA8, 0x0A73,
    /* 2 */ 0x0C3E, 0x08D4, 0x095F, 0x0A6A, 0x0C3E, 0x07CB, 0x0B50, 0x0B18,
    /* 3 */ 0x0D9B, 0x09CF, 0x0A6A, 0x0B92, 0x0D9B, 0x08A8, 0x0C92, 0x0C54,
    /* 4 */ 0x1000, 0x0B89, 0x0C3E, 0x0D9B, 0x1000, 0x0A2E, 0x0EC8, 0x0E7F,
    /* 5 */ 0x0A2E, 0x0757, 0x07CB, 0x08A8, 0x0A2E, 0x067A, 0x0968, 0x0939,
    /* 6 */ 0x0EC8, 0x0AA8, 0x0B50, 0x0C92, 0x0EC8, 0x0968, 0x0DA8, 0x0D64,
    /* 7 */ 0x0E7F, 0x0A73, 0x0B18, 0x0C54, 0x0E7F, 0x0939, 0x0D64, 0x0D23
};

const static UShort ZZTab[64] =
{
    /* 0 */ 0x0, 0x2, 0xA, 0xC, 0x1C, 0x1E, 0x36, 0x38,
    /* 1 */ 0x4, 0x8, 0xE, 0x1A, 0x20, 0x34, 0x3A, 0x54,
    /* 2 */ 0x6, 0x10, 0x18, 0x22, 0x32, 0x3C, 0x52, 0x56,
    /* 3 */ 0x12, 0x16, 0x24, 0x30, 0x3E, 0x50, 0x58, 0x6A,
    /* 4 */ 0x14, 0x26, 0x2E, 0x40, 0x4E, 0x5A, 0x68, 0x6C,
    /* 5 */ 0x28, 0x2C, 0x42, 0x4C, 0x5C, 0x66, 0x6E, 0x78,
    /* 6 */ 0x2A, 0x44, 0x4A, 0x5E, 0x64, 0x70, 0x76, 0x7A,
    /* 7 */ 0x46, 0x48, 0x60, 0x62, 0x72, 0x74, 0x7C, 0x7E
};


//Tao need to remove, write another version of abs
//#include <math.h>

/* ======================================================================== */
/*  Function : cal_dc_scalerENC                                             */
/*  Date     : 01/25/2000                                                   */
/*  Purpose  : calculation of DC quantization scale according to the
               incoming Q and type;                                         */
/*  In/out   :                                                              */
/*      Int Qp      Quantizer                                               */
/*  Return   :                                                              */
/*          DC Scaler                                                       */
/*  Modified :                                                              */
/* ======================================================================== */
/* ======================================================================== */
Int cal_dc_scalerENC(Int QP, Int type)
{

    Int dc_scaler;
    if (type == 1)
    {
        if (QP > 0 && QP < 5)
            dc_scaler = 8;
        else if (QP > 4 && QP < 9)
            dc_scaler = 2 * QP;
        else if (QP > 8 && QP < 25)
            dc_scaler = QP + 8;
        else
            dc_scaler = 2 * QP - 16;
    }
    else
    {
        if (QP > 0 && QP < 5)
            dc_scaler = 8;
        else if (QP > 4 && QP < 25)
            dc_scaler = (QP + 13) / 2;
        else
            dc_scaler = QP - 6;
    }
    return dc_scaler;
}


/***********************************************************************
 Function: BlckQuantDequantH263
 Date:     June 15, 1999
 Purpose:  Combine BlockQuantH263 and BlockDequantH263ENC
 Input:   coeff=> DCT coefficient
 Output:  qcoeff=> quantized coefficient
          rcoeff=> reconstructed coefficient
          return CBP for this block
          4/2/01,  correct dc_scaler for short_header mode.
          5/14/01,
          changed the division into LUT multiplication/shift and other
          modifications to speed up fastQuant/DeQuant (check for zero 1st, rowq LUT,
          fast bitmaprow mask and borrowed Addition method instead of ifs from , ).
          6/25/01,
          Further optimization (~100K/QCIF), need more testing/comment before integration.

          7/4/01,  break up Inter / Intra function and merge for different cases.
          7/22/01,  combine AAN scaling here and reordering.
          7/24/01, , reorder already done in FDCT, the input here is in the next block and
            it's the
            transpose of the raster scan. Output the same order (for proof of concenpt).
          8/1/01, , change FDCT to do row/column FDCT without reordering, input is still
            in the next block. The reconstructed DCT output is current block in normal
            order. The quantized output is in zigzag scan order for INTER, row/column for
            INTRA. Use bitmapzz for zigzag RunLevel for INTER.  The quantization is done
            in column/row scanning order.
          8/2/01, , change IDCT to do column/row, change bitmaprow/col to the opposite.
          8/3/01, , add clipping to the reconstructed coefficient [-2047,2047]
          9/4/05, , removed scaling for AAN IDCT, use Chen IDCT instead.
 ********************************************************************/

Int BlockQuantDequantH263Inter(Short *rcoeff, Short *qcoeff, struct QPstruct *QuantParam,
                               UChar bitmapcol[ ], UChar *bitmaprow, UInt *bitmapzz,
                               Int dctMode, Int comp, Int dummy, UChar shortHeader)
{
    Int i, zz;
    Int tmp, coeff, q_value;
    Int QPdiv2 = QuantParam->QPdiv2;
    Int QPx2 = QuantParam->QPx2;
    Int Addition = QuantParam->Addition;
    Int QPx2plus = QuantParam->QPx2plus;
    Int round = 1 << 15;
    Int q_scale = scaleArrayV[QuantParam->QP];
    Int shift = 15 + (QPx2 >> 4);
    Int *temp;
    UChar *bcolptr = bitmapcol;
    Int ac_clip;    /* quantized coeff bound */

    OSCL_UNUSED_ARG(comp);
    OSCL_UNUSED_ARG(dummy);


    if (shortHeader) ac_clip = 126; /* clip between [-127,126] (standard allows 127!) */
    else ac_clip = 2047;  /* clip between [-2048,2047] */

    /* reset all bitmap to zero */
    temp = (Int*) bitmapcol;
    temp[0] = temp[1] = 0;
    bitmapzz[0] = bitmapzz[1] = 0;
    *bitmaprow = 0;
    QPx2plus <<= 4;
    QPx2plus -= 8;

    rcoeff += 64; /* actual data is 64 item ahead */
    //end  = rcoeff + dctMode - 1;
    //rcoeff--;
    bcolptr--;
    i = 0;

    do
    {
        bcolptr++;
        //rcoeff++;
        //i=0;
        coeff = rcoeff[i];
        if (coeff == 0x7fff) /* all zero column */
        {
            i++;
            continue;
        }

        do
        {
            if (coeff >= -QPx2plus && coeff < QPx2plus)  /* quantize to zero */
            {
                i += 8;
                if (i < (dctMode << 3))
                {
                    coeff = rcoeff[i];
                    if (coeff > -QPx2plus && coeff < QPx2plus)  /* quantize to zero */
                    {
                        i += 8;
                        coeff = rcoeff[i];
                        continue;
                    }
                    else
                        goto NONZERO1;
                }
            }
            else
            {
NONZERO1:
                /* scaling */
                q_value = AANScale[i];  /* load scale AAN */
                zz = ZZTab[i];  /* zigzag order */

                coeff = aan_scale(q_value, coeff, round, QPdiv2);
                q_value = coeff_quant(coeff, q_scale, shift);

                /* dequantization  */
                if (q_value)
                {

                    //coeff = PV_MIN(ac_clip,PV_MAX(-ac_clip-1, q_value));
                    q_value = coeff_clip(q_value, ac_clip);
                    qcoeff[zz>>1] = q_value;

                    // dequant and clip
                    //coeff = PV_MIN(2047,PV_MAX(-2048, q_value));
                    tmp = 2047;
                    coeff = coeff_dequant(q_value, QPx2, Addition, tmp);
                    rcoeff[i-64] = coeff;

                    (*bcolptr) |= imask[i>>3];
                    if ((zz >> 1) > 31) bitmapzz[1] |= (1 << (63 - (zz >> 1)));
                    else        bitmapzz[0] |= (1 << (31 - (zz >> 1)));
                }
                i += 8;
                coeff = rcoeff[i];
            }
        }
        while (i < (dctMode << 3));

        i += (1 - (dctMode << 3));
    }
    while (i < dctMode) ;

    i = dctMode;
    tmp = 1 << (8 - i);
    while (i--)
    {
        if (bitmapcol[i])(*bitmaprow) |= tmp;
        tmp <<= 1;
    }

    if (*bitmaprow)
        return 1;
    else
        return 0;
}

Int BlockQuantDequantH263Intra(Short *rcoeff, Short *qcoeff, struct QPstruct *QuantParam,
                               UChar bitmapcol[ ], UChar *bitmaprow, UInt *bitmapzz,
                               Int dctMode, Int comp, Int dc_scaler, UChar shortHeader)
{
    Int i;
    Int tmp, coeff, q_value;
    Int QPx2 = QuantParam->QPx2;
    Int Addition = QuantParam->Addition;
    Int QPx2plus = QuantParam->QPx2plus;
    Int round = 1 << 15;
    Int q_scale = scaleArrayV[QuantParam->QP];
    Int shift = 15 + (QPx2 >> 4);
    UChar *bmcolptr = bitmapcol;
    Int ac_clip;    /* quantized coeff bound */

    OSCL_UNUSED_ARG(bitmapzz);
    OSCL_UNUSED_ARG(comp);


    if (shortHeader) ac_clip = 126; /* clip between [-127,126] (standard allows 127!) */
    else ac_clip = 2047;  /* clip between [-2048,2047] */

    *((Int*)bitmapcol) = *((Int*)(bitmapcol + 4)) = 0;
    *bitmaprow = 0;

    QPx2plus = QPx2 << 4;
    QPx2plus -= 8;

    rcoeff += 64; /* actual data is 64 element ahead */
    i = 0;

    /* DC value */
    coeff = *rcoeff;
    /* scaling */
    if (coeff == 0x7fff && !shortHeader) /* all zero column */
    {
        bmcolptr++;
        i++;
    }
    else
    {
        if (coeff == 0x7fff) /* shortHeader on */
        {
            coeff = 1; /* can't be zero */
            qcoeff[0] = coeff;
            coeff = coeff * dc_scaler;
            coeff = PV_MAX(-2048, PV_MIN(2047, coeff));
            rcoeff[-64] = coeff;
            bitmapcol[0] |= 128;
            bmcolptr++;
            //qcoeff++;
            //rcoeff++;
            //i=0;
            i++;
        }
        else
        {
            q_value = round + (coeff << 12);
            coeff = q_value >> 16;
            if (coeff >= 0) coeff += (dc_scaler >> 1) ;
            else            coeff -= (dc_scaler >> 1) ;
            q_value = scaleArrayV2[dc_scaler];
            coeff = coeff * q_value;
            coeff >>= (15 + (dc_scaler >> 4));
            coeff += ((UInt)coeff >> 31);

            if (shortHeader)
                coeff = PV_MAX(1, PV_MIN(254, coeff));

            if (coeff)
            {
                qcoeff[0] = coeff;
                coeff = coeff * dc_scaler;
                coeff = PV_MAX(-2048, PV_MIN(2047, coeff));
                rcoeff[-64] = coeff;
                bitmapcol[0] |= 128;
            }
            i += 8;
        }
    }
    /* AC values */
    do
    {
        coeff = rcoeff[i];
        if (coeff == 0x7fff) /* all zero row */
        {
            bmcolptr++;
            i++;
            continue;
        }
        do
        {
            if (coeff >= -QPx2plus && coeff < QPx2plus)  /* quantize to zero */
            {
                i += 8;
                if (i < dctMode << 3)
                {
                    coeff = rcoeff[i];
                    if (coeff > -QPx2plus && coeff < QPx2plus)  /* quantize to zero */
                    {
                        i += 8;
                        coeff = rcoeff[i];
                        continue;
                    }
                    else
                        goto NONZERO2;
                }
            }
            else
            {
NONZERO2:   /* scaling */
                q_value = AANScale[i]; /*  09/02/05 */

                /* scale aan */
                q_value = smlabb(q_value, coeff, round);
                coeff = q_value >> 16;
                /* quant */
                q_value = smulbb(q_scale, coeff); /*mov     q_value, coeff, lsl #14 */
                /*smull tmp, coeff, q_value, q_scale*/
                q_value >>= shift;
                q_value += ((UInt)q_value >> 31); /* add 1 if negative */

                if (q_value)
                {
                    //coeff = PV_MIN(ac_clip,PV_MAX(-ac_clip-1, q_value));
                    q_value = coeff_clip(q_value, ac_clip);
                    qcoeff[i] = q_value;

                    // dequant and clip
                    //coeff = PV_MIN(2047,PV_MAX(-2048, q_value));
                    tmp = 2047;
                    coeff = coeff_dequant(q_value, QPx2, Addition, tmp);
                    rcoeff[i-64] = coeff;

                    (*bmcolptr) |= imask[i>>3];
                }
                i += 8;
                coeff = rcoeff[i];
            }
        }
        while (i < (dctMode << 3)) ;

        //qcoeff++; /* next column */
        bmcolptr++;
        //rcoeff++;
        i += (1 - (dctMode << 3)); //i = 0;
    }
    while (i < dctMode);//while(rcoeff < end) ;

    i = dctMode;
    tmp = 1 << (8 - i);
    while (i--)
    {
        if (bitmapcol[i])(*bitmaprow) |= tmp;
        tmp <<= 1;
    }

    if (((*bitmaprow)&127) || (bitmapcol[0]&127)) /* exclude DC */
        return 1;
    else
        return 0;
}


/***********************************************************************
 Function: BlckQuantDequantH263DC
 Date:     5/3/2001
 Purpose:   H.263 quantization mode, only for DC component
 6/25/01,
          Further optimization (~100K/QCIF), need more testing/comment before integration.

 ********************************************************************/
Int BlockQuantDequantH263DCInter(Short *rcoeff, Short *qcoeff, struct QPstruct *QuantParam,
                                 UChar *bitmaprow, UInt *bitmapzz, Int dummy, UChar shortHeader)
{
    Int coeff, scale_q;
    Int CBP = 0;
    Int QP = QuantParam->QP;
    Int QPx2plus = QuantParam->QPx2plus;
    Int Addition = QuantParam->Addition;
    Int shift = 15 + (QP >> 3);
    Int ac_clip;    /* quantized coeff bound */
    Int tmp;

    OSCL_UNUSED_ARG(dummy);

    if (shortHeader) ac_clip = 126; /* clip between [-127,126] (standard allows 127!) */
    else ac_clip = 2047;  /* clip between [-2048,2047] */

    *bitmaprow = 0;
    bitmapzz[0] = bitmapzz[1] = 0;
    coeff = rcoeff[0];

    if (coeff >= -QPx2plus && coeff < QPx2plus)
    {
        rcoeff[0] = 0;
        return CBP;//rcoeff[0] = 0; not needed since CBP will be zero
    }
    else
    {
        scale_q = scaleArrayV[QP];

        coeff = aan_dc_scale(coeff, QP);

        scale_q = coeff_quant(coeff, scale_q, shift);

        //coeff = PV_MIN(ac_clip,PV_MAX(-ac_clip-1, tmp));
        scale_q = coeff_clip(scale_q, ac_clip);

        qcoeff[0] = scale_q;

        QP <<= 1;
        //coeff = PV_MIN(2047,PV_MAX(-2048, tmp));
        tmp = 2047;
        coeff = coeff_dequant(scale_q, QP, Addition, tmp);

        rcoeff[0] = coeff;

        (*bitmaprow) = 128;
        bitmapzz[0] = (ULong)1 << 31;
        CBP = 1;
    }
    return CBP;
}


Int BlockQuantDequantH263DCIntra(Short *rcoeff, Short *qcoeff, struct QPstruct *QuantParam,
                                 UChar *bitmaprow, UInt *bitmapzz, Int dc_scaler, UChar shortHeader)
{
    Int tmp, coeff;

    OSCL_UNUSED_ARG(QuantParam);

    *bitmaprow = 0;
    coeff = rcoeff[0];

    if (coeff >= 0) coeff += (dc_scaler >> 1) ;
    else            coeff -= (dc_scaler >> 1) ;
    tmp = scaleArrayV2[dc_scaler];
    tmp = coeff * tmp;
    tmp >>= (15 + (dc_scaler >> 4));
    tmp += ((UInt)tmp >> 31);

    if (shortHeader)
        tmp = PV_MAX(1, PV_MIN(254, tmp));

    if (tmp)
    {
        qcoeff[0] = tmp;
        coeff = tmp * dc_scaler;
        coeff = PV_MAX(-2048, PV_MIN(2047, coeff));
        rcoeff[0] = coeff;
        *bitmaprow = 128;
        bitmapzz[0] = (ULong)1 << 31;
    }

    return 0;
}

#ifndef NO_MPEG_QUANT
/***********************************************************************
 Function: BlckQuantDequantMPEG
 Date:     June 15, 1999
 Purpose:  Combine BlockQuantMPEG and BlockDequantMPEGENC
 Input:   coeff=> DCT coefficient
 Output:  qcoeff=> quantized coefficient
          rcoeff=> reconstructed coefficient
 Modified:  7/5/01, break up function for Intra/Inter
          8/3/01,  update with changes from H263 quant mode.
          8/3/01,  add clipping to the reconstructed coefficient [-2048,2047]
          8/6/01,  optimize using multiplicative lookup-table.
                     can be further optimized using ARM assembly, e.g.,
                     clipping, 16-bit mult., etc !!!!!!!!!!!!!
 ********************************************************************/

Int BlockQuantDequantMPEGInter(Short *rcoeff, Short *qcoeff, Int QP, Int *qmat,
                               UChar bitmapcol[ ], UChar *bitmaprow, UInt *bitmapzz,
                               Int dctMode, Int comp, Int dc_scaler)
{
    Int i, zz;
    Int tmp, coeff, q_value = 0;
    Int sum = 0;
    Int stepsize, QPx2 = QP << 1;
    Int CBP = 0;
    Int round = 1 << 15;
    Int q_scale = scaleArrayV[QP];
    Int shift = 15 + (QP >> 3);
    UChar *bcolptr = bitmapcol;

    OSCL_UNUSED_ARG(dc_scaler);
    OSCL_UNUSED_ARG(comp);


    *((Int*)bitmapcol) = *((Int*)(bitmapcol + 4)) = 0;
    bitmapzz[0] = bitmapzz[1] = 0;
    *bitmaprow = 0;

    rcoeff += 64;
    i = 0;
    bcolptr--;

    do
    {
        bcolptr++;
        coeff = rcoeff[i];
        if (coeff == 0x7fff) /* all zero column */
        {
            i++;
            continue;
        }
        do
        {
            q_value = AANScale[i];  /*  09/02/05 scaling for AAN*/
            /* aan scaling */
            q_value = smlabb(q_value, coeff, round);

            coeff = q_value >> 16;

            stepsize = qmat[i];
//          if(coeff>0)     coeff = (16*coeff + (stepsize/2)) / stepsize;
//          else            coeff = (16*coeff - (stepsize/2)) / stepsize;
            coeff <<= 4;
            if (coeff >= 0) coeff += (stepsize >> 1) ;
            else            coeff -= (stepsize >> 1) ;
            q_value = scaleArrayV2[stepsize];
            /* mpeg quant table scale */
            coeff = smulbb(coeff, q_value);

            coeff >>= (15 + (stepsize >> 4));
            coeff += ((UInt)coeff >> 31);

            /* QP scale */
            if (coeff >= -QPx2 && coeff < QPx2)  /* quantized to zero*/
            {
                i += 8;
            }
            else
            {
//              q_value = coeff/(QPx2);
                q_value = coeff_quant(coeff, q_scale, shift);

                if (q_value)                /* dequant */
                {

                    zz = ZZTab[i];  /* zigzag order */

                    tmp = 2047;

                    q_value = clip_2047(q_value, tmp);

                    qcoeff[zz>>1] = q_value;

                    //q_value=(((coeff*2)+SIGN0(coeff))*stepsize*QP)/16;
                    /* no need for SIGN0, no zero coming in this {} */
                    q_value = coeff_dequant_mpeg(q_value, stepsize, QP, tmp);

                    rcoeff[i-64] = q_value;

                    sum += q_value;
                    (*bcolptr) |= imask[i>>3];
                    if ((zz >> 1) > 31) bitmapzz[1] |= (1 << (63 - (zz >> 1)));
                    else        bitmapzz[0] |= (1 << (31 - (zz >> 1)));
                }
                i += 8;
            }
            coeff = rcoeff[i];
        }
        while (i < (dctMode << 3)) ;

        i += (1 - (dctMode << 3));
    }
    while (i < dctMode) ;

    i = dctMode;
    tmp = 1 << (8 - i);
    while (i--)
    {
        if (bitmapcol[i])(*bitmaprow) |= tmp;
        tmp <<= 1;
    }

    if (*bitmaprow)
        CBP = 1;   /* check CBP before mismatch control,  7/5/01 */

    /* Mismatch control,  5/3/01 */
    if (CBP)
    {
        if ((sum&0x1) == 0)
        {
            rcoeff--;  /* rcoeff[63] */
            coeff = *rcoeff;
            coeff ^= 0x1;
            *rcoeff = coeff;
            if (coeff)
            {
                bitmapcol[7] |= 1;
                (*bitmaprow) |= 1;
            }
        }
    }

    return CBP;
}

Int BlockQuantDequantMPEGIntra(Short *rcoeff, Short *qcoeff, Int QP, Int *qmat,
                               UChar bitmapcol[ ], UChar *bitmaprow, UInt *bitmapzz,
                               Int dctMode, Int comp, Int dc_scaler)
{
    Int i;
    Int tmp, coeff, q_value = 0;
    Int sum = 0;
    Int stepsize;
    Int CBP = 0;
    Int round = 1 << 15;
    Int q_scale = scaleArrayV[QP];
    Int shift = 15 + (QP >> 3);
    Int round2 = (3 * QP + 2) >> 2;
    Int QPx2plus = (QP << 1) - round2;
    UChar *bmcolptr = bitmapcol;

    OSCL_UNUSED_ARG(bitmapzz);
    OSCL_UNUSED_ARG(comp);

    *((Int*)bitmapcol) = *((Int*)(bitmapcol + 4)) = 0;
    *bitmaprow = 0;

    rcoeff += 64;
    i = 0;

    /* DC value */
    coeff = *rcoeff;

    if (coeff == 0x7fff) /* all zero column */
    {
        bmcolptr++;
        i++;
    }
    else
    {
        q_value = round + (coeff << 12);
        coeff = q_value >> 16;
        /*if (coeff >= 0)   coeff = (coeff + (dc_scaler/2)) / dc_scaler;
        else            coeff = (coeff - (dc_scaler/2)) / dc_scaler;*/
        if (coeff >= 0) coeff += (dc_scaler >> 1) ;
        else            coeff -= (dc_scaler >> 1) ;
        q_value = scaleArrayV2[dc_scaler];

        /* mpeg quant table scale */
        coeff = smulbb(coeff, q_value);

        coeff >>= (15 + (dc_scaler >> 4));
        coeff += ((UInt)coeff >> 31);

        if (coeff)
        {
            coeff = PV_MAX(1, PV_MIN(254, coeff));
            qcoeff[0] = coeff;

            coeff = smulbb(coeff, dc_scaler);

            q_value = clip_2047(coeff, 2047);

            sum = q_value;

            rcoeff[-64] = q_value;

            bitmapcol[0] |= 128;
        }
        i += 8;
    }
    /* AC values */
    do
    {
        coeff = rcoeff[i];
        if (coeff == 0x7fff) /* all zero row */
        {
            bmcolptr++;
            i++;
            continue;
        }
        do
        {
            /* scaling */
            q_value = AANScale[i]; /*  09/02/05 */

            /* q_value = coeff*q_value + round */
            q_value = smlabb(coeff, q_value, round);
            coeff = q_value >> 16;

            stepsize = qmat[i];
            /*if(coeff>0)       coeff = (16*coeff + (stepsize/2)) / stepsize;
            else            coeff = (16*coeff - (stepsize/2)) / stepsize;*/
            coeff <<= 4;
            if (coeff >= 0) coeff += (stepsize >> 1) ;
            else            coeff -= (stepsize >> 1) ;
            q_value = scaleArrayV2[stepsize];

            /* scale mpeg quant */
            coeff = smulbb(coeff, q_value);

            coeff >>= (15 + (stepsize >> 4));
            coeff += ((UInt)coeff >> 31);

            if (coeff >= -QPx2plus && coeff < QPx2plus)
            {
                i += 8;
            }
            else
            {
                //q_value = ( coeff + SIGN0(coeff)*((3*QP+2)/4))/(2*QP);
                if (coeff > 0) coeff += round2;
                else if (coeff < 0) coeff -= round2;

                q_value = smulbb(coeff, q_scale);
                q_value >>= shift;
                q_value += ((UInt)q_value >> 31);

                if (q_value)
                {
                    tmp = 2047;
                    q_value = clip_2047(q_value, tmp);

                    qcoeff[i] = q_value;

                    stepsize = smulbb(stepsize, QP);
                    q_value =  smulbb(q_value, stepsize);

                    q_value = coeff_dequant_mpeg_intra(q_value, tmp);
                    //q_value = (coeff*stepsize*QP*2)/16;

                    rcoeff[i-64] = q_value;

                    sum += q_value;
                    (*bmcolptr) |= imask[i>>3];
                }
                i += 8;
            }
            coeff = rcoeff[i];
        }
        while (i < (dctMode << 3)) ;

        bmcolptr++;
        i += (1 - (dctMode << 3));
    }
    while (i < dctMode) ;

    i = dctMode;
    tmp = 1 << (8 - i);
    while (i--)
    {
        if (bitmapcol[i])(*bitmaprow) |= tmp;
        tmp <<= 1;
    }

    if (((*bitmaprow) &127) || (bitmapcol[0]&127))
        CBP = 1;  /* check CBP before mismatch control,  7/5/01 */

    /* Mismatch control,  5/3/01 */
    if (CBP || bitmapcol[0])
    {
        if ((sum&0x1) == 0)
        {
            rcoeff--;  /* rcoeff[63] */
            coeff = *rcoeff;
            coeff ^= 0x1;
            *rcoeff = coeff;
            if (coeff)
            {
                bitmapcol[7] |= 1;
                (*bitmaprow) |= 1;
            }
        }
    }

    return CBP;
}


/***********************************************************************
 Function: BlckQuantDequantMPEGDC
 Date:     5/3/2001
 Purpose:  MPEG Quant/Dequant for DC only block.
 ********************************************************************/
Int BlockQuantDequantMPEGDCInter(Short *rcoeff, Short *qcoeff, Int QP, Int *qmat,
                                 UChar bitmapcol[ ], UChar *bitmaprow, UInt *bitmapzz, Int dummy)
{
    Int q_value, coeff, stepsize;
    Int CBP = 0;
    Int q_scale = scaleArrayV[QP];
    Int shift = 15 + (QP >> 3);
    Int QPx2 = QP << 1;

    OSCL_UNUSED_ARG(dummy);

    *((Int*)bitmapcol) = *((Int*)(bitmapcol + 4)) = 0;
    *bitmaprow = 0;
    bitmapzz[0] = bitmapzz[1] = 0;
    coeff = rcoeff[0];
    stepsize = qmat[0];

    /*if(coeff>0)       coeff = (16*coeff + (stepsize/2)) / stepsize;
    else            coeff = (16*coeff - (stepsize/2)) / stepsize;*/
    coeff <<= 4;
    if (coeff >= 0) coeff += (stepsize >> 1) ;
    else            coeff -= (stepsize >> 1) ;
    q_value = scaleArrayV2[stepsize];

    coeff = smulbb(coeff, q_value);

    coeff >>= (15 + (stepsize >> 4));
    coeff += ((UInt)coeff >> 31);

    if (coeff >= -QPx2 && coeff < QPx2)
    {
        rcoeff[0] = 0;
        return CBP;
    }
    else
    {
//      q_value = coeff/(QPx2);
        q_value = coeff_quant(coeff, q_scale, shift);

        if (q_value)
        {

            //PV_MIN(2047,PV_MAX(-2048, q_value));
            q_value = clip_2047(q_value, 2047);
            qcoeff[0] = q_value;
            q_value = coeff_dequant_mpeg(q_value, stepsize, QP, 2047);
            //q_value=(((coeff*2)+SIGN0(coeff))*stepsize*QP)/16;
            rcoeff[0] = q_value;

            bitmapcol[0] = 128;
            (*bitmaprow) = 128;
            bitmapzz[0] = (UInt)1 << 31;
            CBP = 1;

            /* Mismatch control,  5/3/01 */
            if ((q_value&0x1) == 0)
            {
                rcoeff[63] = 1; /* after scaling it remains the same */
                bitmapcol[7] |= 1;
                (*bitmaprow) |= 1;
            }
        }
    }
    return CBP;
}


Int BlockQuantDequantMPEGDCIntra(Short *rcoeff, Short *qcoeff, Int QP, Int *qmat,
                                 UChar bitmapcol[ ], UChar *bitmaprow, UInt *bitmapzz,
                                 Int dc_scaler)
{
    Int tmp, coeff, q_value;

    OSCL_UNUSED_ARG(QP);
    OSCL_UNUSED_ARG(qmat);


    *((Int*)bitmapcol) = *((Int*)(bitmapcol + 4)) = 0;
    *bitmaprow = 0;
    coeff = rcoeff[0];

    /*if (coeff >= 0)   tmp = (coeff + dc_scaler/2) / dc_scaler;
    else            tmp = (coeff - dc_scaler/2) / dc_scaler;*/
    if (coeff >= 0) coeff += (dc_scaler >> 1) ;
    else            coeff -= (dc_scaler >> 1) ;
    tmp = scaleArrayV2[dc_scaler];

    tmp = smulbb(tmp, coeff);
    tmp >>= (15 + (dc_scaler >> 4));
    tmp += ((UInt)tmp >> 31);

    if (tmp)
    {
        coeff = PV_MAX(1, PV_MIN(254, tmp));
        qcoeff[0] = coeff;

        q_value = smulbb(coeff, dc_scaler);
        q_value = clip_2047(q_value, 2047);
        rcoeff[0] = q_value;
        bitmapcol[0] = 128;
        *bitmaprow = 128;
        bitmapzz[0] = (UInt)1 << 31;

        /* Mismatch control,  5/3/01 */
        if ((q_value&0x1) == 0)
        {
            rcoeff[63] = 1; /* after scaling it remains the same */
            bitmapcol[7] |= 1;
            (*bitmaprow) |= 1;
        }
    }

    return 0;
}
#endif

