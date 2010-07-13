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
/******************************************************************************
*
* This software module was originally developed by
*
* Robert Danielsen (Telenor / ACTS-MoMuSys).
*
* and edited by
*
* Luis Ducla-Soares (IST / ACTS-MoMuSys).
* Cor Quist (KPN / ACTS-MoMuSys).
*
* in the course of development of the MPEG-4 Video (ISO/IEC 14496-2) standard.
* This software module is an implementation of a part of one or more MPEG-4
* Video (ISO/IEC 14496-2) tools as specified by the MPEG-4 Video (ISO/IEC
* 14496-2) standard.
*
* ISO/IEC gives users of the MPEG-4 Video (ISO/IEC 14496-2) standard free
* license to this software module or modifications thereof for use in hardware
* or software products claiming conformance to the MPEG-4 Video (ISO/IEC
* 14496-2) standard.
*
* Those intending to use this software module in hardware or software products
* are advised that its use may infringe existing patents. The original
* developer of this software module and his/her company, the subsequent
* editors and their companies, and ISO/IEC have no liability for use of this
* software module or modifications thereof in an implementation. Copyright is
* not released for non MPEG-4 Video (ISO/IEC 14496-2) standard conforming
* products.
*
* ACTS-MoMuSys partners retain full right to use the code for his/her own
* purpose, assign or donate the code to a third party and to inhibit third
* parties from using the code for non MPEG-4 Video (ISO/IEC 14496-2) standard
* conforming products. This copyright notice must be included in all copies or
* derivative works.
*
* Copyright (c) 1997
*
*****************************************************************************/

/***********************************************************HeaderBegin*******
*
* File: putvlc.c
*
* Author:   Robert Danielsen, Telenor R&D
* Created:  07.07.96
*
* Description: Functions for writing to bitstream
*
* Notes:    Same kind of tables as in the MPEG-2 software simulation
*       group software.
*
* Modified:
*   28.10.96 Robert Danielsen: Added PutCoeff_Intra(), renamed
*           PutCoeff() to PutCoeff_Inter().
*   06.11.96 Robert Danielsen: Added PutMCBPC_sep()
*      01.05.97 Luis Ducla-Soares: added PutCoeff_Intra_RVLC() and
*                                  PutCoeff_Inter_RVLC().
*
***********************************************************HeaderEnd*********/

/************************    INCLUDE FILES    ********************************/


#include "mp4lib_int.h"
#include "mp4enc_lib.h"
#include "vlc_enc_tab.h"
#include "bitstream_io.h"
#include "m4venc_oscl.h"
#include "vlc_encode_inline.h"

typedef void (*BlockCodeCoeffPtr)(RunLevelBlock*, BitstreamEncVideo*, Int, Int, UChar) ;

const static Int mode_MBtype[] =
{
    3,
    0,
    4,
    1,
    2,
};

const static Int zigzag_inv[NCOEFF_BLOCK] =
{
    0,  1,  8, 16,  9,  2,  3, 10,
    17, 24, 32, 25, 18, 11,  4,  5,
    12, 19, 26, 33, 40, 48, 41, 34,
    27, 20, 13,  6,  7, 14, 21, 28,
    35, 42, 49, 56, 57, 50, 43, 36,
    29, 22, 15, 23, 30, 37, 44, 51,
    58, 59, 52, 45, 38, 31, 39, 46,
    53, 60, 61, 54, 47, 55, 62, 63
};

/* Horizontal zigzag inverse */
const static Int zigzag_h_inv[NCOEFF_BLOCK] =
{
    0, 1, 2, 3, 8, 9, 16, 17,
    10, 11, 4, 5, 6, 7, 15, 14,
    13, 12, 19, 18, 24, 25, 32, 33,
    26, 27, 20, 21, 22, 23, 28, 29,
    30, 31, 34, 35, 40, 41, 48, 49,
    42, 43, 36, 37, 38, 39, 44, 45,
    46, 47, 50, 51, 56, 57, 58, 59,
    52, 53, 54, 55, 60, 61, 62, 63
};

/* Vertical zigzag inverse */
const static Int zigzag_v_inv[NCOEFF_BLOCK] =
{
    0, 8, 16, 24, 1, 9, 2, 10,
    17, 25, 32, 40, 48, 56, 57, 49,
    41, 33, 26, 18, 3, 11, 4, 12,
    19, 27, 34, 42, 50, 58, 35, 43,
    51, 59, 20, 28, 5, 13, 6, 14,
    21, 29, 36, 44, 52, 60, 37, 45,
    53, 61, 22, 30, 7, 15, 23, 31,
    38, 46, 54, 62, 39, 47, 55, 63
};

#ifdef __cplusplus
extern "C"
{
#endif

    Int PutCoeff_Inter(Int run, Int level, BitstreamEncVideo *bitstream);
    Int PutCoeff_Inter_Last(Int run, Int level, BitstreamEncVideo *bitstream);
    Int PutCoeff_Intra(Int run, Int level, BitstreamEncVideo *bitstream);
    Int PutCoeff_Intra_Last(Int run, Int level, BitstreamEncVideo *bitstream);
    Int PutCBPY(Int cbpy, Char intra, BitstreamEncVideo *bitstream);
    Int PutMCBPC_Inter(Int cbpc, Int mode, BitstreamEncVideo *bitstream);
    Int PutMCBPC_Intra(Int cbpc, Int mode, BitstreamEncVideo *bitstream);
    Int PutMV(Int mvint, BitstreamEncVideo *bitstream);
    Int PutDCsize_chrom(Int size, BitstreamEncVideo *bitstream);
    Int PutDCsize_lum(Int size, BitstreamEncVideo *bitstream);
    Int PutDCsize_lum(Int size, BitstreamEncVideo *bitstream);
#ifndef NO_RVLC
    Int PutCoeff_Inter_RVLC(Int run, Int level, BitstreamEncVideo *bitstream);
    Int PutCoeff_Inter_RVLC_Last(Int run, Int level, BitstreamEncVideo *bitstream);
    Int PutCoeff_Intra_RVLC(Int run, Int level, BitstreamEncVideo *bitstream);
    Int PutCoeff_Intra_RVLC_Last(Int run, Int level, BitstreamEncVideo *bitstream);
#endif
    Int PutRunCoeff_Inter(Int run, Int level, BitstreamEncVideo *bitstream);
    Int PutRunCoeff_Inter_Last(Int run, Int level, BitstreamEncVideo *bitstream);
    Int PutRunCoeff_Intra(Int run, Int level, BitstreamEncVideo *bitstream);
    Int PutRunCoeff_Intra_Last(Int run, Int level, BitstreamEncVideo *bitstream);
    Int PutLevelCoeff_Inter(Int run, Int level, BitstreamEncVideo *bitstream);
    Int PutLevelCoeff_Inter_Last(Int run, Int level, BitstreamEncVideo *bitstream);
    Int PutLevelCoeff_Intra(Int run, Int level, BitstreamEncVideo *bitstream);
    Int PutLevelCoeff_Intra_Last(Int run, Int level, BitstreamEncVideo *bitstream);

    void RunLevel(VideoEncData *video, Int intra, Int intraDC_decision, Int ncoefblck[]);
    Int IntraDC_dpcm(Int val, Int lum, BitstreamEncVideo *bitstream);
    Void DCACPred(VideoEncData *video, UChar Mode, Int *intraDC_decision, Int intraDCVlcQP);
    Void find_pmvs(VideoEncData *video, Int block, Int *mvx, Int *mvy);
    Void  WriteMVcomponent(Int f_code, Int dmv, BitstreamEncVideo *bs);
    static Bool IntraDCSwitch_Decision(Int Mode, Int intra_dc_vlc_threshold, Int intraDCVlcQP);

    Void ScaleMVD(Int  f_code, Int  diff_vector, Int  *residual, Int  *vlc_code_mag);

#ifdef __cplusplus
}
#endif

Int
PutDCsize_lum(Int size, BitstreamEncVideo *bitstream)
{
    Int length;

    if (!(size >= 0 && size < 13))
        return -1;

    length = DCtab_lum[size].len;
    if (length)
        BitstreamPutBits(bitstream, length, DCtab_lum[size].code);

    return length;
}

Int
PutDCsize_chrom(Int size, BitstreamEncVideo *bitstream)
{
    Int length;

    if (!(size >= 0 && size < 13))
        return -1;
    length = DCtab_chrom[size].len;
    if (length)
        BitstreamPutBits(bitstream, length, DCtab_chrom[size].code);

    return length;
}

Int
PutMV(Int mvint, BitstreamEncVideo *bitstream)
{
    Int sign = 0;
    Int absmv;
    Int length;

    if (mvint > 32)
    {
        absmv = -mvint + 65;
        sign = 1;
    }
    else
        absmv = mvint;

    length = mvtab[absmv].len;
    if (length)
        BitstreamPutBits(bitstream, length, mvtab[absmv].code);

    if (mvint != 0)
    {
        BitstreamPut1Bits(bitstream, sign);
        return (length + 1);
    }
    else
        return length;
}

Int
PutMCBPC_Intra(Int cbp, Int mode, BitstreamEncVideo *bitstream)
{
    Int ind;
    Int length;

    ind = ((mode_MBtype[mode] >> 1) & 3) | ((cbp & 3) << 2);

    length = mcbpc_intra_tab[ind].len;
    if (length)
        BitstreamPutBits(bitstream, length, mcbpc_intra_tab[ind].code);

    return length;
}

Int
PutMCBPC_Inter(Int cbp, Int mode, BitstreamEncVideo *bitstream)
{
    Int ind;
    Int length;

    ind = (mode_MBtype[mode] & 7) | ((cbp & 3) << 3);

    length = mcbpc_inter_tab[ind].len;
    if (length)
        BitstreamPutBits(bitstream, length, mcbpc_inter_tab[ind].code);

    return length;
}

Int
PutCBPY(Int cbpy, Char intra, BitstreamEncVideo *bitstream)
{
    Int ind;
    Int length;

    if ((intra == 0))
        cbpy = 15 - cbpy;

    ind = cbpy;

    length = cbpy_tab[ind].len;
    if (length)
        BitstreamPutBits(bitstream, length, (UInt)cbpy_tab[ind].code);

    return length;
}

/* 5/16/01, break up function for last and not-last coefficient */
/* Note:::: I checked the ARM assembly for if( run > x && run < y) type
    of code, they do a really good job compiling it to if( (UInt)(run-x) < y-x).
    No need to hand-code it!!!!!, 6/1/2001 */

Int PutCoeff_Inter(Int run, Int level, BitstreamEncVideo *bitstream)
{
    Int length = 0;

    if (run < 2 && level < 13)
    {
        length = coeff_tab0[run][level-1].len;
        if (length)
            BitstreamPutBits(bitstream, length, (UInt)coeff_tab0[run][level-1].code);
    }
    else if (run > 1 && run < 27 && level < 5)
    {
        length = coeff_tab1[run-2][level-1].len;
        if (length)
            BitstreamPutBits(bitstream, length, (UInt)coeff_tab1[run-2][level-1].code);
    }

    return length;
}

Int PutCoeff_Inter_Last(Int run, Int level, BitstreamEncVideo *bitstream)
{
    Int length = 0;

    if (run < 2 && level < 4)
    {
        length = coeff_tab2[run][level-1].len;
        if (length)
            BitstreamPutBits(bitstream, length, (UInt)coeff_tab2[run][level-1].code);
    }
    else if (run > 1 && run < 42 && level == 1)
    {
        length = coeff_tab3[run-2].len;
        if (length)
            BitstreamPutBits(bitstream, length, (UInt)coeff_tab3[run-2].code);
    }

    return length;
}

/* 5/16/01, break up function for last and not-last coefficient */

Int PutCoeff_Intra(Int run, Int level, BitstreamEncVideo *bitstream)
{
    Int length = 0;

    if (run == 0 && level < 28)
    {
        length = coeff_tab4[level-1].len;
        if (length)
            BitstreamPutBits(bitstream, length, (UInt)coeff_tab4[level-1].code);
    }
    else if (run == 1 && level < 11)
    {
        length = coeff_tab5[level-1].len;
        if (length)
            BitstreamPutBits(bitstream, length, (UInt)coeff_tab5[level-1].code);
    }
    else if (run > 1 && run < 10 && level < 6)
    {
        length = coeff_tab6[run-2][level-1].len;
        if (length)
            BitstreamPutBits(bitstream, length, (UInt)coeff_tab6[run-2][level-1].code);
    }
    else if (run > 9 && run < 15 && level == 1)
    {
        length = coeff_tab7[run-10].len;
        if (length)
            BitstreamPutBits(bitstream, length, (UInt)coeff_tab7[run-10].code);
    }

    return length;
}

Int PutCoeff_Intra_Last(Int run, Int level, BitstreamEncVideo *bitstream)
{
    Int length = 0;

    if (run == 0 && level < 9)
    {
        length = coeff_tab8[level-1].len;
        if (length)
            BitstreamPutBits(bitstream, length, (UInt)coeff_tab8[level-1].code);
    }
    else if (run > 0 && run < 7 && level < 4)
    {
        length = coeff_tab9[run-1][level-1].len;
        if (length)
            BitstreamPutBits(bitstream, length, (UInt)coeff_tab9[run-1][level-1].code);
    }
    else if (run > 6 && run < 21 && level == 1)
    {
        length = coeff_tab10[run-7].len;
        if (length)
            BitstreamPutBits(bitstream, length, (UInt)coeff_tab10[run-7].code);
    }

    return length;
}

/* 5/16/01, break up function for last and not-last coefficient */
#ifndef NO_RVLC
Int PutCoeff_Inter_RVLC(Int run, Int level, BitstreamEncVideo *bitstream)
{
    Int length = 0;

    if (run == 0 && level < 20)
    {
        length =  coeff_RVLCtab14[level-1].len;
        if (length)
            BitstreamPutBits(bitstream, length, (UInt)coeff_RVLCtab14[level-1].code);
    }
    else if (run == 1 && level < 11)
    {
        length = coeff_RVLCtab15[level-1].len;
        if (length)
            BitstreamPutBits(bitstream, length, (UInt)coeff_RVLCtab15[level-1].code);
    }
    else if (run > 1 && run < 4 && level < 8)
    {
        length = coeff_RVLCtab16[run-2][level-1].len;
        if (length)
            BitstreamPutBits(bitstream, length, (UInt)coeff_RVLCtab16[run-2][level-1].code);
    }
    else if (run == 4 && level < 6)
    {
        length = coeff_RVLCtab17[level-1].len;
        if (length)
            BitstreamPutBits(bitstream, length, (UInt)coeff_RVLCtab17[level-1].code);
    }
    else if (run > 4 && run < 8 && level < 5)
    {
        length = coeff_RVLCtab18[run-5][level-1].len;
        if (length)
            BitstreamPutBits(bitstream, length, (UInt)coeff_RVLCtab18[run-5][level-1].code);
    }
    else if (run > 7 && run < 10 && level < 4)
    {
        length = coeff_RVLCtab19[run-8][level-1].len;
        if (length)
            BitstreamPutBits(bitstream, length, (UInt)coeff_RVLCtab19[run-8][level-1].code);
    }
    else if (run > 9 && run < 18 && level < 3)
    {
        length = coeff_RVLCtab20[run-10][level-1].len;
        if (length)
            BitstreamPutBits(bitstream, length, (UInt)coeff_RVLCtab20[run-10][level-1].code);
    }
    else if (run > 17 && run < 39 && level == 1)
    {
        length = coeff_RVLCtab21[run-18].len;
        if (length)
            BitstreamPutBits(bitstream, length, (UInt)coeff_RVLCtab21[run-18].code);
    }

    return length;
}

Int PutCoeff_Inter_RVLC_Last(Int run, Int level, BitstreamEncVideo *bitstream)
{
    Int length = 0;

    if (run >= 0 && run < 2 && level < 6)
    {
        length = coeff_RVLCtab22[run][level-1].len;
        if (length)
            BitstreamPutBits(bitstream, length, (UInt)coeff_RVLCtab22[run][level-1].code);
    }
    else if (run == 2 && level < 4)
    {
        length = coeff_RVLCtab23[level-1].len;
        if (length)
            BitstreamPutBits(bitstream, length, (UInt)coeff_RVLCtab23[level-1].code);
    }
    else if (run > 2 && run < 14 && level < 3)
    {
        length = coeff_RVLCtab24[run-3][level-1].len;
        if (length)
            BitstreamPutBits(bitstream, length, (UInt)coeff_RVLCtab24[run-3][level-1].code);
    }
    else if (run > 13 && run < 45 && level == 1)
    {
        length = coeff_RVLCtab25[run-14].len;
        if (length)
            BitstreamPutBits(bitstream, length, (UInt)coeff_RVLCtab25[run-14].code);
    }

    return length;
}

/* 5/16/01, break up function for last and not-last coefficient */

Int PutCoeff_Intra_RVLC(Int run, Int level, BitstreamEncVideo *bitstream)
{
    Int length = 0;

    if (run == 0 && level < 28)
    {
        length = coeff_RVLCtab1[level-1].len;
        if (length)
            BitstreamPutBits(bitstream, length, (UInt)coeff_RVLCtab1[level-1].code);
    }
    else if (run == 1 && level < 14)
    {
        length = coeff_RVLCtab2[level-1].len;
        if (length)
            BitstreamPutBits(bitstream, length, (UInt)coeff_RVLCtab2[level-1].code);
    }
    else if (run == 2 && level < 12)
    {
        length = coeff_RVLCtab3[level-1].len;
        if (length)
            BitstreamPutBits(bitstream, length, (UInt)coeff_RVLCtab3[level-1].code);
    }
    else if (run == 3 && level < 10)
    {
        length = coeff_RVLCtab4[level-1].len;
        if (length)
            BitstreamPutBits(bitstream, length, (UInt)coeff_RVLCtab4[level-1].code);
    }
    else if (run > 3 && run < 6 && level < 7)
    {
        length = coeff_RVLCtab5[run-4][level-1].len;
        if (length)
            BitstreamPutBits(bitstream, length, (UInt)coeff_RVLCtab5[run-4][level-1].code);
    }
    else if (run > 5 && run < 8 && level < 6)
    {
        length = coeff_RVLCtab6[run-6][level-1].len;
        if (length)
            BitstreamPutBits(bitstream, length, (UInt)coeff_RVLCtab6[run-6][level-1].code);
    }
    else if (run > 7 && run < 10 && level < 5)
    {
        length = coeff_RVLCtab7[run-8][level-1].len;
        if (length)
            BitstreamPutBits(bitstream, length, (UInt)coeff_RVLCtab7[run-8][level-1].code);

    }
    else if (run > 9 && run < 13 && level < 3)
    {
        length = coeff_RVLCtab8[run-10][level-1].len;
        if (length)
            BitstreamPutBits(bitstream, length, (UInt)coeff_RVLCtab8[run-10][level-1].code);
    }
    else if (run > 12 && run < 20 && level == 1)
    {
        length = coeff_RVLCtab9[run-13].len;
        if (length)
            BitstreamPutBits(bitstream, length, (UInt)coeff_RVLCtab9[run-13].code);
    }
    return length;
}

Int PutCoeff_Intra_RVLC_Last(Int run, Int level, BitstreamEncVideo *bitstream)
{
    Int length = 0;

    if (run >= 0 && run < 2 && level < 6)
    {
        length = coeff_RVLCtab10[run][level-1].len;
        if (length)
            BitstreamPutBits(bitstream, length, (UInt)coeff_RVLCtab10[run][level-1].code);
    }
    else if (run == 2 && level < 4)
    {
        length = coeff_RVLCtab11[level-1].len;
        if (length)
            BitstreamPutBits(bitstream, length, (UInt)coeff_RVLCtab11[level-1].code);
    }
    else if (run > 2 && run < 14 && level < 3)
    {
        length = coeff_RVLCtab12[run-3][level-1].len;
        if (length)
            BitstreamPutBits(bitstream, length, (UInt)coeff_RVLCtab12[run-3][level-1].code);
    }
    else if (run > 13 && run < 45 && level == 1)
    {
        length = coeff_RVLCtab13[run-14].len;
        if (length)
            BitstreamPutBits(bitstream, length, (UInt)coeff_RVLCtab13[run-14].code);
    }
    return length;
}
#endif

/* The following is for 3-mode VLC */

Int
PutRunCoeff_Inter(Int run, Int level, BitstreamEncVideo *bitstream)
{
    Int length = 0;

    if (run < 2 && level < 13)
    {
        length = coeff_tab0[run][level-1].len;
        if (length)
        {
            BitstreamPutGT8Bits(bitstream, 7 + 2, 14/*3*/);
            //BitstreamPutBits(bitstream, 2, 2);
            BitstreamPutBits(bitstream, length, (UInt)coeff_tab0[run][level-1].code);
            length += 9;
        }
    }
    else if (run > 1 && run < 27 && level < 5)
    {
        length = coeff_tab1[run-2][level-1].len;
        if (length)
        {
            BitstreamPutGT8Bits(bitstream, 7 + 2, 14/*3*/);
            //BitstreamPutBits(bitstream, 2, 2);
            BitstreamPutBits(bitstream, length, (UInt)coeff_tab1[run-2][level-1].code);
            length += 9;
        }
    }
    return length;
}

Int PutRunCoeff_Inter_Last(Int run, Int level, BitstreamEncVideo *bitstream)
{
    Int length = 0;

    if (run < 2 && level < 4)
    {
        length = coeff_tab2[run][level-1].len;
        if (length)
        {
            BitstreamPutGT8Bits(bitstream, 7 + 2, 14/*3*/);
            //BitstreamPutBits(bitstream, 2, 2);
            BitstreamPutBits(bitstream, length, (UInt)coeff_tab2[run][level-1].code);
            length += 9;
        }
    }
    else if (run > 1 && run < 42 && level == 1)
    {
        length = coeff_tab3[run-2].len;
        if (length)
        {
            BitstreamPutGT8Bits(bitstream, 7 + 2, 14/*3*/);
            //BitstreamPutBits(bitstream, 2, 2);
            BitstreamPutBits(bitstream, length, (UInt)coeff_tab3[run-2].code);
            length += 9;
        }
    }
    return length;
}

Int PutRunCoeff_Intra(Int run, Int level, BitstreamEncVideo *bitstream)
{
    Int length = 0;

    if (run == 0 && level < 28)
    {
        length = coeff_tab4[level-1].len;
        if (length)
        {
            BitstreamPutGT8Bits(bitstream, 7 + 2, 14/*3*/);
            //BitstreamPutBits(bitstream, 2, 2);
            BitstreamPutBits(bitstream, length, (UInt)coeff_tab4[level-1].code);
            length += 9;
        }
    }
    else if (run == 1 && level < 11)
    {
        length = coeff_tab5[level-1].len;
        if (length)
        {
            BitstreamPutGT8Bits(bitstream, 7 + 2, 14/*3*/);
            //BitstreamPutBits(bitstream, 2, 2);
            BitstreamPutBits(bitstream, length, (UInt)coeff_tab5[level-1].code);
            length += 9;
        }
    }
    else if (run > 1 && run < 10 && level < 6)
    {
        length = coeff_tab6[run-2][level-1].len;
        if (length)
        {
            BitstreamPutGT8Bits(bitstream, 7 + 2, 14/*3*/);
            //BitstreamPutBits(bitstream, 2, 2);
            BitstreamPutBits(bitstream, length, (UInt)coeff_tab6[run-2][level-1].code);
            length += 9;
        }
    }
    else if (run > 9 && run < 15 && level == 1)
    {
        length = coeff_tab7[run-10].len;
        if (length)
        {
            BitstreamPutGT8Bits(bitstream, 7 + 2, 14/*3*/);
            //BitstreamPutBits(bitstream, 2, 2);
            BitstreamPutBits(bitstream, length, (UInt)coeff_tab7[run-10].code);
            length += 9;
        }
    }
    return length;
}
Int PutRunCoeff_Intra_Last(Int run, Int level, BitstreamEncVideo *bitstream)
{
    Int length = 0;

    if (run == 0 && level < 9)
    {
        length = coeff_tab8[level-1].len;
        if (length)
        {
            BitstreamPutGT8Bits(bitstream, 7 + 2, 14/*3*/);
            //BitstreamPutBits(bitstream, 2, 2);
            BitstreamPutBits(bitstream, length, (UInt)coeff_tab8[level-1].code);
            length += 9;
        }
    }
    else if (run > 0 && run < 7 && level < 4)
    {
        length = coeff_tab9[run-1][level-1].len;
        if (length)
        {
            BitstreamPutGT8Bits(bitstream, 7 + 2, 14/*3*/);
            //BitstreamPutBits(bitstream, 2, 2);
            BitstreamPutBits(bitstream, length, (UInt)coeff_tab9[run-1][level-1].code);
            length += 9;
        }
    }
    else if (run > 6 && run < 21 && level == 1)
    {
        length = coeff_tab10[run-7].len;
        if (length)
        {
            BitstreamPutGT8Bits(bitstream, 7 + 2, 14/*3*/);
            //BitstreamPutBits(bitstream, 2, 2);
            BitstreamPutBits(bitstream, length, (UInt)coeff_tab10[run-7].code);
            length += 9;
        }
    }
    return length;
}

Int
PutLevelCoeff_Inter(Int run, Int level, BitstreamEncVideo *bitstream)
{
    Int length = 0;

    if (run < 2 && level < 13)
    {
        length = coeff_tab0[run][level-1].len;
        if (length)
        {
            BitstreamPutBits(bitstream, 7 + 1, 6/*3*/);
            BitstreamPutBits(bitstream, length, (UInt)coeff_tab0[run][level-1].code);
            length += 8;
        }
    }
    else if (run > 1 && run < 27 && level < 5)
    {
        length = coeff_tab1[run-2][level-1].len;
        if (length)
        {
            BitstreamPutBits(bitstream, 7 + 1, 6/*3*/);
            BitstreamPutBits(bitstream, length, (UInt)coeff_tab1[run-2][level-1].code);
            length += 8;
        }
    }
    return length;
}

Int PutLevelCoeff_Inter_Last(Int run, Int level, BitstreamEncVideo *bitstream)
{
    Int length = 0;

    if (run < 2 && level < 4)
    {
        length = coeff_tab2[run][level-1].len;
        if (length)
        {
            BitstreamPutBits(bitstream, 7 + 1, 6/*3*/);
            BitstreamPutBits(bitstream, length, (UInt)coeff_tab2[run][level-1].code);
            length += 8;
        }
    }
    else if (run > 1 && run < 42 && level == 1)
    {
        length = coeff_tab3[run-2].len;
        if (length)
        {
            BitstreamPutBits(bitstream, 7 + 1, 6/*3*/);
            BitstreamPutBits(bitstream, length, (UInt)coeff_tab3[run-2].code);
            length += 8;
        }
    }
    return length;
}

Int PutLevelCoeff_Intra(Int run, Int level, BitstreamEncVideo *bitstream)
{
    Int length = 0;

    if (run == 0 && level < 28)
    {
        length = coeff_tab4[level-1].len;
        if (length)
        {
            BitstreamPutBits(bitstream, 7 + 1, 6/*3*/);
            BitstreamPutBits(bitstream, length, (UInt)coeff_tab4[level-1].code);
            length += 8;
        }
    }
    else if (run == 1 && level < 11)
    {
        length = coeff_tab5[level-1].len;
        if (length)
        {
            BitstreamPutBits(bitstream, 7 + 1, 6/*3*/);
            BitstreamPutBits(bitstream, length, (UInt)coeff_tab5[level-1].code);
            length += 8;
        }
    }
    else if (run > 1 && run < 10 && level < 6)
    {
        length = coeff_tab6[run-2][level-1].len;
        if (length)
        {
            BitstreamPutBits(bitstream, 7 + 1, 6/*3*/);
            BitstreamPutBits(bitstream, length, (UInt)coeff_tab6[run-2][level-1].code);
            length += 8;
        }
    }
    else if (run > 9 && run < 15 && level == 1)
    {
        length = coeff_tab7[run-10].len;
        if (length)
        {
            BitstreamPutBits(bitstream, 7 + 1, 6/*3*/);
            BitstreamPutBits(bitstream, length, (UInt)coeff_tab7[run-10].code);
            length += 8;
        }
    }
    return length;
}
Int PutLevelCoeff_Intra_Last(Int run, Int level, BitstreamEncVideo *bitstream)
{
    Int length = 0;

    if (run == 0 && level < 9)
    {
        length = coeff_tab8[level-1].len;
        if (length)
        {
            BitstreamPutBits(bitstream, 7 + 1, 6/*3*/);
            BitstreamPutBits(bitstream, length, (UInt)coeff_tab8[level-1].code);
            length += 8;
        }
    }
    else if (run > 0 && run < 7 && level < 4)
    {
        length = coeff_tab9[run-1][level-1].len;
        if (length)
        {
            BitstreamPutBits(bitstream, 7 + 1, 6/*3*/);
            BitstreamPutBits(bitstream, length, (UInt)coeff_tab9[run-1][level-1].code);
            length += 8;
        }
    }
    else if (run > 6 && run < 21 && level == 1)
    {
        length = coeff_tab10[run-7].len;
        if (length)
        {
            BitstreamPutBits(bitstream, 7 + 1, 6/*3*/);
            BitstreamPutBits(bitstream, length, (UInt)coeff_tab10[run-7].code);
            length += 8;
        }
    }
    return length;
}



/* ======================================================================== */
/*  Function : MBVlcEncode()                                                */
/*  Date     : 09/10/2000                                                   */
/*  Purpose  : Encode GOV Header                                            */
/*  In/out   :                                                              */
/*  Return   :                                                              */
/*  Modified : 5/21/01, break up into smaller functions                     */
/* ======================================================================== */
#ifndef H263_ONLY
/**************************************/
/* Data Partitioning I-VOP Encoding   */
/**************************************/

void MBVlcEncodeDataPar_I_VOP(
    VideoEncData *video,
    Int ncoefblck[],
    void *blkCodePtr)
{

    BitstreamEncVideo *bs1 = video->bitstream1;
    BitstreamEncVideo *bs2 = video->bitstream2;
    BitstreamEncVideo *bs3 = video->bitstream3;
    int i;
    UChar Mode = video->headerInfo.Mode[video->mbnum];
    UChar CBP;
//  MacroBlock *MB=video->outputMB;
    Int mbnum = video->mbnum;
    Int intraDC_decision, DC;
//  int temp;
    Int dquant; /* 3/15/01 */
    RunLevelBlock *RLB = video->RLB;
    BlockCodeCoeffPtr BlockCodeCoeff = (BlockCodeCoeffPtr) blkCodePtr;

    /* DC and AC Prediction, 5/28/01, compute CBP, intraDC_decision*/
    DCACPred(video, Mode, &intraDC_decision, video->QP_prev);

    /* CBP, Run, Level, and Sign */
    RunLevel(video, 1, intraDC_decision, ncoefblck);
    CBP = video->headerInfo.CBP[mbnum];

    /* Compute DQuant */
    dquant = video->QPMB[mbnum] - video->QP_prev; /* 3/15/01, QP_prev may not equal QPMB[mbnum-1] if mbnum-1 is skipped*/

    video->QP_prev = video->QPMB[mbnum];

    if (dquant && Mode == MODE_INTRA)
    {
        Mode = MODE_INTRA_Q;
    }

    if (dquant >= 0)
        dquant = (PV_ABS(dquant) + 1);
    else
        dquant = (PV_ABS(dquant) - 1);

    /* FIRST PART: ALL TO BS1 */

    PutMCBPC_Intra(CBP, Mode, bs1); /* MCBPC */

    if (Mode == MODE_INTRA_Q)
        /*  MAY NEED TO CHANGE DQUANT HERE  */
        BitstreamPutBits(bs1, 2, dquant);  /* dquant*/


    if (intraDC_decision == 0)
    {
        for (i = 0; i < 6; i++)
        {
            DC = video->RLB[i].level[0];
            if (video->RLB[i].s[0])
                DC = -DC;
            if (i < 4)
                /*temp =*/ IntraDC_dpcm(DC, 1, bs1);        /* dct_dc_size_luminance, */
            else                                    /* dct_dc_differential, and */
                /*temp =*/ IntraDC_dpcm(DC, 0, bs1);        /* marker bit */
        }
    }

    /* SECOND PART: ALL TO BS2*/

    BitstreamPut1Bits(bs2, video->acPredFlag[video->mbnum]);    /* ac_pred_flag */

    /*temp=*/
    PutCBPY(CBP >> 2, (Char)(1), bs2); /* cbpy */


    /* THIRD PART:  ALL TO BS3*/
    /* MB_CodeCoeff(video,bs3); */ /* 5/22/01, replaced with below */
    for (i = 0; i < 6; i++)
    {
        if (CBP&(1 << (5 - i)))
            (*BlockCodeCoeff)(&(RLB[i]), bs3, 1 - intraDC_decision, ncoefblck[i], Mode);/* Code Intra AC*/
    }

    return ;
}

/************************************/
/* Data Partitioning P-VOP Encoding */
/************************************/

void MBVlcEncodeDataPar_P_VOP(
    VideoEncData *video,
    Int ncoefblck[],
    void *blkCodePtr)
{

    BitstreamEncVideo *bs1 = video->bitstream1;
    BitstreamEncVideo *bs2 = video->bitstream2;
    BitstreamEncVideo *bs3 = video->bitstream3;
    int i;
    Int mbnum = video->mbnum;
    UChar Mode = video->headerInfo.Mode[mbnum];
    Int QP_tmp = video->QPMB[mbnum];
    UChar CBP;
//  MacroBlock *MB=video->outputMB;
    Int intra, intraDC_decision, DC;
    Int pmvx, pmvy;
//  int temp;
    Int dquant; /* 3/15/01 */
    RunLevelBlock *RLB = video->RLB;
    BlockCodeCoeffPtr BlockCodeCoeff = (BlockCodeCoeffPtr) blkCodePtr;

    intra = (Mode == MODE_INTRA || Mode == MODE_INTRA_Q);

    /* DC and AC Prediction, 5/28/01, compute CBP, intraDC_decision*/

    if (intra)
    {
        if (video->usePrevQP)
        {
            QP_tmp = video->QPMB[mbnum-1];
        }

        DCACPred(video, Mode, &intraDC_decision, QP_tmp);
    }
    else
        intraDC_decision = 0; /* used in RunLevel */

    /* CBP, Run, Level, and Sign */
    RunLevel(video, intra, intraDC_decision, ncoefblck);
    CBP = video->headerInfo.CBP[mbnum];

    /* Compute DQuant */
    dquant = video->QPMB[mbnum] - video->QP_prev; /* 3/15/01, QP_prev may not equal QPMB[mbnum-1] if mbnum-1 is skipped*/

    if (dquant && (Mode == MODE_INTRA || Mode == MODE_INTER))
    {
        Mode += 2;  /* make it MODE_INTRA_Q and MODE_INTER_Q */
    }

    if (dquant >= 0)
        dquant = (PV_ABS(dquant) + 1);
    else
        dquant = (PV_ABS(dquant) - 1);

    /* FIRST PART: ALL TO BS1 */

    if (CBP == 0 && intra == 0)  /* Determine if Skipped MB */
    {
        if ((Mode == MODE_INTER) && (video->mot[mbnum][0].x == 0) && (video->mot[mbnum][0].y == 0))
            Mode = video->headerInfo.Mode[video->mbnum] = MODE_SKIPPED;
        else if ((Mode == MODE_INTER4V) && (video->mot[mbnum][1].x == 0) && (video->mot[mbnum][1].y == 0)
                 && (video->mot[mbnum][2].x == 0) && (video->mot[mbnum][2].y == 0)
                 && (video->mot[mbnum][3].x == 0) && (video->mot[mbnum][3].y == 0)
                 && (video->mot[mbnum][4].x == 0) && (video->mot[mbnum][4].y == 0))
            Mode = video->headerInfo.Mode[video->mbnum] = MODE_SKIPPED;
    }


    if (Mode == MODE_SKIPPED)
    {
        BitstreamPut1Bits(bs1, 1); /* not_coded = 1 */
        return;
    }
    else
        BitstreamPut1Bits(bs1, 0); /* not_coded =0 */

    video->QP_prev = video->QPMB[mbnum];
    video->usePrevQP = 1;

    PutMCBPC_Inter(CBP, Mode, bs1); /* MCBPC */

    video->header_bits -= BitstreamGetPos(bs1); /* Header Bits */

    if (Mode == MODE_INTER || Mode == MODE_INTER_Q)
    {
        find_pmvs(video, 0, &pmvx, &pmvy); /* Get predicted motion vectors */
        WriteMVcomponent(video->currVop->fcodeForward, video->mot[mbnum][0].x - pmvx, bs1); /* Write x to bitstream */
        WriteMVcomponent(video->currVop->fcodeForward, video->mot[mbnum][0].y - pmvy, bs1);     /* Write y to bitstream */
    }
    else if (Mode == MODE_INTER4V)
    {
        for (i = 1; i < 5; i++)
        {
            find_pmvs(video, i, &pmvx, &pmvy);
            WriteMVcomponent(video->currVop->fcodeForward, video->mot[mbnum][i].x - pmvx, bs1);
            WriteMVcomponent(video->currVop->fcodeForward, video->mot[mbnum][i].y - pmvy, bs1);
        }
    }
    video->header_bits += BitstreamGetPos(bs1); /* Header Bits */

    /* SECOND PART: ALL TO BS2 */


    if (intra)
    {
        BitstreamPut1Bits(bs2, video->acPredFlag[video->mbnum]);    /* ac_pred_flag */
        /*temp=*/
        PutCBPY(CBP >> 2, (Char)(Mode == MODE_INTRA || Mode == MODE_INTRA_Q), bs2); /* cbpy */

        if (Mode == MODE_INTRA_Q)
            BitstreamPutBits(bs2, 2, dquant);  /* dquant, 3/15/01*/

        if (intraDC_decision == 0)
        {
            for (i = 0; i < 6; i++)
            {
                DC = video->RLB[i].level[0];
                if (video->RLB[i].s[0])
                    DC = -DC;
                if (i < 4)
                    /*temp =*/ IntraDC_dpcm(DC, 1, bs2);        /* dct_dc_size_luminance, */
                else                                    /* dct_dc_differential, and */
                    /*temp =*/ IntraDC_dpcm(DC, 0, bs2);        /* marker bit */
            }
        }

        /****************************/  /* THIRD PART: ALL TO BS3 */
        for (i = 0; i < 6; i++)
        {
            if (CBP&(1 << (5 - i)))
                (*BlockCodeCoeff)(&(RLB[i]), bs3, 1 - intraDC_decision, ncoefblck[i], Mode);/* Code Intra AC*/
        }
    }
    else
    {
        /*temp=*/
        PutCBPY(CBP >> 2, (Char)(Mode == MODE_INTRA || Mode == MODE_INTRA_Q), bs2); /* cbpy */
        if (Mode == MODE_INTER_Q)
            /*  MAY NEED TO CHANGE DQUANT HERE  */
            BitstreamPutBits(bs2, 2, dquant);  /* dquant, 3/15/01*/

        /****************************/  /* THIRD PART: ALL TO BS3 */
        for (i = 0; i < 6; i++)
        {
            if (CBP&(1 << (5 - i)))
                (*BlockCodeCoeff)(&(RLB[i]), bs3, 0, ncoefblck[i], Mode);/* Code Intra AC*/
        }
    }

    return ;
}
#endif /* H263_ONLY */
/****************************************************************************************/
/* Short Header/Combined Mode with or without Error Resilience I-VOP and P-VOP Encoding */
/* 5/21/01, B-VOP is not implemented yet!!!!                                            */
/****************************************************************************************/

void MBVlcEncodeCombined_I_VOP(
    VideoEncData *video,
    Int ncoefblck[],
    void *blkCodePtr)
{

    BitstreamEncVideo *bs1 = video->bitstream1;
//  BitstreamEncVideo *bs2 = video->bitstream2;
//  BitstreamEncVideo *bs3 = video->bitstream3;
    int i;
    UChar Mode = video->headerInfo.Mode[video->mbnum];
    UChar CBP = video->headerInfo.CBP[video->mbnum];
//  MacroBlock *MB=video->outputMB;
    Int mbnum = video->mbnum;
    Int intraDC_decision;
//  int temp;
    Int dquant; /* 3/15/01 */
    RunLevelBlock *RLB = video->RLB;
    Int DC;
    Int shortVideoHeader = video->vol[video->currLayer]->shortVideoHeader;
    BlockCodeCoeffPtr BlockCodeCoeff = (BlockCodeCoeffPtr) blkCodePtr;

    /* DC and AC Prediction, 5/28/01, compute CBP, intraDC_decision*/

#ifndef H263_ONLY
    if (!shortVideoHeader)
        DCACPred(video, Mode, &intraDC_decision, video->QP_prev);
    else
#endif
    {
        intraDC_decision = 0;
    }

    /* CBP, Run, Level, and Sign */

    RunLevel(video, 1, intraDC_decision, ncoefblck);
    CBP = video->headerInfo.CBP[mbnum];

    /* Compute DQuant */
    dquant = video->QPMB[mbnum] - video->QP_prev; /* 3/15/01, QP_prev may not equal QPMB[mbnum-1] if mbnum-1 is skipped*/

    video->QP_prev = video->QPMB[mbnum];

    if (dquant && Mode == MODE_INTRA)
    {
        Mode = MODE_INTRA_Q;
    }

    if (dquant >= 0)
        dquant = (PV_ABS(dquant) + 1);
    else
        dquant = (PV_ABS(dquant) - 1);

    PutMCBPC_Intra(CBP, Mode, bs1); /* mcbpc I_VOP */

    if (!video->vol[video->currLayer]->shortVideoHeader)
    {
        BitstreamPut1Bits(bs1, video->acPredFlag[video->mbnum]);    /* ac_pred_flag */
    }

    /*temp=*/
    PutCBPY(CBP >> 2, (Char)(1), bs1); /* cbpy */

    if (Mode == MODE_INTRA_Q)
        /*  MAY NEED TO CHANGE DQUANT HERE */
        BitstreamPutBits(bs1, 2, dquant);  /* dquant, 3/15/01*/

    /*MB_CodeCoeff(video,bs1); 5/21/01, replaced by below */
    /*******************/
#ifndef H263_ONLY
    if (shortVideoHeader) /* Short Header DC coefficients */
    {
#endif
        for (i = 0; i < 6; i++)
        {
            DC = RLB[i].level[0];
            if (RLB[i].s[0])
                DC = -DC;
            if (DC != 128)
                BitstreamPutBits(bs1, 8, DC);   /* intra_dc_size_luminance */
            else
                BitstreamPutBits(bs1, 8, 255);          /* intra_dc_size_luminance */
            if (CBP&(1 << (5 - i)))
                (*BlockCodeCoeff)(&(RLB[i]), bs1, 1, ncoefblck[i], Mode); /* Code short header Intra AC*/
        }
#ifndef H263_ONLY
    }
    else if (intraDC_decision == 0)   /* Combined Intra Mode DC and AC coefficients */
    {
        for (i = 0; i < 6; i++)
        {
            DC = RLB[i].level[0];
            if (RLB[i].s[0])
                DC = -DC;

            if (i < 4)
                /*temp =*/ IntraDC_dpcm(DC, 1, bs1);        /* dct_dc_size_luminance, */
            else                                                /* dct_dc_differential, and */
                /*temp =*/ IntraDC_dpcm(DC, 0, bs1);        /* marker bit */
            if (CBP&(1 << (5 - i)))
                (*BlockCodeCoeff)(&(RLB[i]), bs1, 1, ncoefblck[i], Mode);/* Code Intra AC */
        }
    }
    else   /* Combined Mode Intra DC/AC coefficients */
    {
        for (i = 0; i < 6; i++)
        {
            if (CBP&(1 << (5 - i)))
                (*BlockCodeCoeff)(&(RLB[i]), bs1, 0, ncoefblck[i], Mode);/* Code Intra AC */
        }
    }
#endif
    /*******************/
    return ;
}

void MBVlcEncodeCombined_P_VOP(
    VideoEncData *video,
    Int ncoefblck[],
    void *blkCodePtr)
{

    BitstreamEncVideo *bs1 = video->bitstream1;
//  BitstreamEncVideo *bs2 = video->bitstream2;
//  BitstreamEncVideo *bs3 = video->bitstream3;
    int i;
    Int mbnum = video->mbnum;
    UChar Mode = video->headerInfo.Mode[mbnum];
    Int QP_tmp = video->QPMB[mbnum];
    UChar CBP ;
//  MacroBlock *MB=video->outputMB;
    Int intra, intraDC_decision;
    Int pmvx, pmvy;
//  int temp;
    Int dquant; /* 3/15/01 */
    RunLevelBlock *RLB = video->RLB;
    Int DC;
    Int shortVideoHeader = video->vol[video->currLayer]->shortVideoHeader;
    BlockCodeCoeffPtr BlockCodeCoeff = (BlockCodeCoeffPtr) blkCodePtr;

    intra = (Mode == MODE_INTRA || Mode == MODE_INTRA_Q);

    /* DC and AC Prediction, 5/28/01, compute intraDC_decision*/
#ifndef H263_ONLY
    if (!shortVideoHeader && intra)
    {
        if (video->usePrevQP)
        {
            QP_tmp = video->QPMB[mbnum-1];
        }
        DCACPred(video, Mode, &intraDC_decision, QP_tmp);
    }
    else
#endif
        intraDC_decision = 0;

    /* CBP, Run, Level, and Sign */

    RunLevel(video, intra, intraDC_decision, ncoefblck);
    CBP = video->headerInfo.CBP[mbnum];

    /* Compute DQuant */
    dquant = video->QPMB[mbnum] - video->QP_prev; /* 3/15/01, QP_prev may not equal QPMB[mbnum-1] if mbnum-1 is skipped*/
    if (dquant && (Mode == MODE_INTRA || Mode == MODE_INTER))
    {
        Mode += 2;  /* make it MODE_INTRA_Q and MODE_INTER_Q */
    }

    if (dquant >= 0)
        dquant = (PV_ABS(dquant) + 1);
    else
        dquant = (PV_ABS(dquant) - 1);

    if (CBP == 0 && intra == 0)  /* Determine if Skipped MB */
    {
        if ((Mode == MODE_INTER) && (video->mot[mbnum][0].x == 0) && (video->mot[mbnum][0].y == 0))
            Mode = video->headerInfo.Mode[video->mbnum] = MODE_SKIPPED;
        else if ((Mode == MODE_INTER4V) && (video->mot[mbnum][1].x == 0) && (video->mot[mbnum][1].y == 0)
                 && (video->mot[mbnum][2].x == 0) && (video->mot[mbnum][2].y == 0)
                 && (video->mot[mbnum][3].x == 0) && (video->mot[mbnum][3].y == 0)
                 && (video->mot[mbnum][4].x == 0) && (video->mot[mbnum][4].y == 0))
            Mode = video->headerInfo.Mode[video->mbnum] = MODE_SKIPPED;
    }

    if (Mode == MODE_SKIPPED)
    {
        BitstreamPut1Bits(bs1, 1); /* not_coded = 1 */
        return;
    }
    else
        BitstreamPut1Bits(bs1, 0); /* not_coded =0 */

    video->QP_prev = video->QPMB[mbnum];
    video->usePrevQP = 1;

    PutMCBPC_Inter(CBP, Mode, bs1); /* mcbpc P_VOP */

    if (!video->vol[video->currLayer]->shortVideoHeader && intra)
    {
        BitstreamPut1Bits(bs1, video->acPredFlag[video->mbnum]);    /* ac_pred_flag */
    }

    /*temp=*/
    PutCBPY(CBP >> 2, (Char)(intra), bs1); /* cbpy */

    if (Mode == MODE_INTRA_Q || Mode == MODE_INTER_Q)
        /*  MAY NEED TO CHANGE DQUANT HERE  */
        BitstreamPutBits(bs1, 2, dquant);  /* dquant, 3/15/01*/

    video->header_bits -= BitstreamGetPos(bs1); /* Header Bits */

    if (!((video->vol[video->currLayer]->scalability) && (video->currVop->refSelectCode == 3)))
    {
        if (Mode == MODE_INTER || Mode == MODE_INTER_Q)
        {
            find_pmvs(video, 0, &pmvx, &pmvy); /* Get predicted motion vectors */
            WriteMVcomponent(video->currVop->fcodeForward, video->mot[mbnum][0].x - pmvx, bs1); /* Write x to bitstream */
            WriteMVcomponent(video->currVop->fcodeForward, video->mot[mbnum][0].y - pmvy, bs1);     /* Write y to bitstream */
        }
        else if (Mode == MODE_INTER4V)
        {
            for (i = 1; i < 5; i++)
            {
                find_pmvs(video, i, &pmvx, &pmvy);
                WriteMVcomponent(video->currVop->fcodeForward, video->mot[mbnum][i].x - pmvx, bs1);
                WriteMVcomponent(video->currVop->fcodeForward, video->mot[mbnum][i].y - pmvy, bs1);
            }
        }
    }
    video->header_bits += BitstreamGetPos(bs1); /* Header Bits */

    /* MB_CodeCoeff(video,bs1); */ /* 5/22/01, replaced with below */
    /****************************/
    if (intra)
    {
#ifndef H263_ONLY
        if (shortVideoHeader) /* Short Header DC coefficients */
        {
#endif
            for (i = 0; i < 6; i++)
            {
                DC = RLB[i].level[0];
                if (RLB[i].s[0])
                    DC = -DC;
                if (DC != 128)
                    BitstreamPutBits(bs1, 8, DC);   /* intra_dc_size_luminance */
                else
                    BitstreamPutBits(bs1, 8, 255);          /* intra_dc_size_luminance */
                if (CBP&(1 << (5 - i)))
                    (*BlockCodeCoeff)(&(RLB[i]), bs1, 1, ncoefblck[i], Mode); /* Code short header Intra AC*/
            }
#ifndef H263_ONLY
        }
        else if (intraDC_decision == 0)   /* Combined Intra Mode DC and AC coefficients */
        {
            for (i = 0; i < 6; i++)
            {
                DC = RLB[i].level[0];
                if (RLB[i].s[0])
                    DC = -DC;

                if (i < 4)
                    /*temp =*/ IntraDC_dpcm(DC, 1, bs1);        /* dct_dc_size_luminance, */
                else                                                /* dct_dc_differential, and */
                    /*temp =*/ IntraDC_dpcm(DC, 0, bs1);        /* marker bit */
                if (CBP&(1 << (5 - i)))
                    (*BlockCodeCoeff)(&(RLB[i]), bs1, 1, ncoefblck[i], Mode);/* Code Intra AC */
            }
        }
        else   /* Combined Mode Intra DC/AC coefficients */
        {
            for (i = 0; i < 6; i++)
            {
                if (CBP&(1 << (5 - i)))
                    (*BlockCodeCoeff)(&(RLB[i]), bs1, 0, ncoefblck[i], Mode);/* Code Intra AC */
            }
        }
#endif
    }
    else   /* Shortheader or Combined INTER Mode AC coefficients */
    {
        for (i = 0; i < 6; i++)
        {
            if (CBP&(1 << (5 - i)))
                (*BlockCodeCoeff)(&(RLB[i]), bs1, 0, ncoefblck[i], Mode);/* Code Inter AC*/
        }
    }
    /****************************/

    return ;
}

/* ======================================================================== */
/*  Function : BlockCodeCoeff()                                         */
/*  Date     : 09/18/2000                                                   */
/*  Purpose  : VLC Encode  AC/DC coeffs                                     */
/*  In/out   :                                                              */
/*  Return   :                                                              */
/*  Modified :  5/16/01  grouping BitstreamPutBits calls                    */
/*              5/22/01  break up function                              */
/* ======================================================================== */
#ifndef NO_RVLC
/*****************/
/* RVLC ENCODING */
/*****************/
Void BlockCodeCoeff_RVLC(RunLevelBlock *RLB, BitstreamEncVideo *bs, Int j_start, Int j_stop, UChar Mode)
{
    int length = 0;
    int i;
    Int level;
    Int run;
    Int intra = (Mode == MODE_INTRA || Mode == MODE_INTRA_Q);

    /* Not Last Coefficient */
    for (i = j_start; i < j_stop - 1; i++)
    {
        run = RLB->run[i];
        level = RLB->level[i];
        //if(i==63||RLB->run[i+1] == -1)    /* Don't Code Last Coefficient Here */
        //  break;
        /*ENCODE RUN LENGTH */
        if (level < 28 && run < 39)
        {
            if (intra)
                length = PutCoeff_Intra_RVLC(run, level, bs);
            else
                length = PutCoeff_Inter_RVLC(run, level, bs);
        }
        else
            length = 0;
        /* ESCAPE CODING */
        if (length == 0)
        {
            BitstreamPutBits(bs, 5 + 1, 2); /* ESCAPE + Not Last Coefficient */
            //BitstreamPutBits(bs,1,0); /* Not Last Coefficient */
            BitstreamPutBits(bs, 6 + 1, (run << 1) | 1); /* RUN + MARKER BIT*/
            //BitstreamPutBits(bs,1,1);  /* MARKER BIT */
            BitstreamPutGT8Bits(bs, 11, level); /* LEVEL */
            BitstreamPutBits(bs, 1 + 4, 16); /* MARKER BIT */
            //BitstreamPutBits(bs,4,0);  /* RVLC TRAILING ESCAPE */
        }
        BitstreamPutBits(bs, 1, RLB->s[i]); /* SIGN BIT */
    }
    /* Last Coefficient!!! */
    run = RLB->run[i];
    level = RLB->level[i];

    /*ENCODE RUN LENGTH */
    if (level < 6 && run < 45)
    {
        if (intra)
            length = PutCoeff_Intra_RVLC_Last(run, level, bs);
        else
            length = PutCoeff_Inter_RVLC_Last(run, level, bs);
    }
    else
        length = 0;
    /* ESCAPE CODING */
    if (length == 0)
    {
        BitstreamPutBits(bs, 5 + 1, 3); /* ESCAPE CODE + Last Coefficient*/
        //BitstreamPutBits(bs,1,1); /* Last Coefficient !*/
        BitstreamPutBits(bs, 6 + 1, (run << 1) | 1); /* RUN + MARKER BIT*/
        //BitstreamPutBits(bs,1,1);  /* MARKER BIT */
        BitstreamPutGT8Bits(bs, 11, level); /* LEVEL */
        BitstreamPutBits(bs, 1 + 4, 16); /* MARKER BIT + RVLC TRAILING ESCAPE */
        //BitstreamPutBits(bs,4,0);  /* */
    }
    BitstreamPut1Bits(bs, RLB->s[i]); /* SIGN BIT */

    return ;
}
#endif
/*******************************/
/* SHORT VIDEO HEADER ENCODING */
/*******************************/

Void BlockCodeCoeff_ShortHeader(RunLevelBlock *RLB, BitstreamEncVideo *bs, Int j_start, Int j_stop, UChar Mode)
{
    int length = 0;
    int i;
//  int temp;
    Int level;
    Int run;

    OSCL_UNUSED_ARG(Mode);

    /* Not Last Coefficient */
    for (i = j_start; i < j_stop - 1; i++)
    {
        run = RLB->run[i];
        level = RLB->level[i];
//      if(i==63 ||RLB->run[i+1] == -1) /* Don't Code Last Coefficient Here */
//          break;
        /*ENCODE RUN LENGTH */
        if (level < 13)
        {
            length = PutCoeff_Inter(run, level, bs);
            if (length != 0)
                /*temp =*/ BitstreamPut1Bits(bs, RLB->s[i]); /* Sign Bit */
        }
        else
            length = 0;
        /* ESCAPE CODING */
        if (length == 0)
        {
            if (RLB->s[i])
                level = -level;
            BitstreamPutBits(bs, 7 + 1, 6); /* ESCAPE CODE + Not Last Coefficient */
            //BitstreamPutBits(bs,1,0); /* Not Last Coefficient */
            BitstreamPutBits(bs, 6, run); /* RUN */
            BitstreamPutBits(bs, 8, level&0xFF); /* LEVEL, mask to make sure length 8 */
        }
    }
    /* Last Coefficient!!! */
    run = RLB->run[i];
    level = RLB->level[i];

    /*ENCODE RUN LENGTH */
    if (level < 13)
    {
        length = PutCoeff_Inter_Last(run, level, bs);
        if (length != 0)
            /*temp =*/ BitstreamPut1Bits(bs, RLB->s[i]); /* Sign Bit */
    }
    else
        length = 0;
    /* ESCAPE CODING */
    if (length == 0)
    {
        if (RLB->s[i])
            level = -level;
        BitstreamPutBits(bs, 7 + 1, 7); /* ESCAPE CODE + Last Coefficient */
        //BitstreamPutBits(bs,1,1); /* Last Coefficient !!!*/
        BitstreamPutBits(bs, 6, run); /* RUN */
        BitstreamPutBits(bs, 8, level&0xFF); /* LEVEL, mask to make sure length 8  */
    }

    return ;

}

#ifndef H263_ONLY
/****************/
/* VLC ENCODING */
/****************/
Void BlockCodeCoeff_Normal(RunLevelBlock *RLB, BitstreamEncVideo *bs, Int j_start, Int j_stop, UChar Mode)
{
    int length = 0;
    int i;
    //int temp;
    Int level;
    Int run;
    Int intra = (Mode == MODE_INTRA || Mode == MODE_INTRA_Q);
    Int level_minus_max;
    Int run_minus_max;
    Int(*PutCoeff)(Int, Int, BitstreamEncVideo *); /* pointer to functions, 5/28/01 */

    /* Not Last Coefficient!!! */

    if (intra)
        PutCoeff = &PutCoeff_Intra;
    else
        PutCoeff = &PutCoeff_Inter;

    for (i = j_start; i < j_stop - 1; i++)
    {
        run = RLB->run[i];
        level = RLB->level[i];

        /* Encode Run Length */
        if (level < 28)
        {
            length = (*PutCoeff)(run, level, bs); /* 5/28/01 replaces above */
        }
        else
        {
            length = 0;
        }

        /* First escape mode: LEVEL OFFSET */
        if (length == 0)
        {
            if (intra)
            {
                level_minus_max = level - intra_max_level[0][run];
                if (level_minus_max < 28)
                    length = PutLevelCoeff_Intra(run, level_minus_max, bs);
                else
                    length = 0;
            }
            else
            {
                level_minus_max = level - inter_max_level[0][run];
                if (level_minus_max < 13)
                    length = PutLevelCoeff_Inter(run, level_minus_max, bs);
                else
                    length = 0;
            }

            /* Second escape mode: RUN OFFSET */
            if (length == 0)
            {
                if (level < 28)
                {
                    if (intra)
                    {
                        run_minus_max = run - (intra_max_run0[level] + 1);
                        length = PutRunCoeff_Intra(run_minus_max, level, bs);
                    }
                    else if (level < 13)
                    {
                        run_minus_max = run - (inter_max_run0[level] + 1);
                        length = PutRunCoeff_Inter(run_minus_max, level, bs);
                    }
                    else
                    {
                        length = 0;
                    }
                }
                else
                {
                    length = 0;
                }

                /* Third escape mode: FIXED LENGTH CODE */
                if (length == 0)
                {
                    if (RLB->s[i])
                        level = -level;
                    /*temp =*/
                    BitstreamPutBits(bs, 7 + 2 + 1, 30); /* ESCAPE CODE + Followed by 11 + Not Last Coefficient*/
                    //temp = BitstreamPutBits(bs,2,3); /* Followed by 11 */
                    //temp = BitstreamPutBits(bs, 1, 0); /* Not Last Coefficient*/
                    /*temp =*/
                    BitstreamPutBits(bs, 6 + 1, (run << 1) | 1); /* Encode Run + Marker Bit */
                    //temp = BitstreamPutBits(bs,1,1); /* Marker Bit */
                    /*temp =*/
                    BitstreamPutGT8Bits(bs, 12 + 1, ((level << 1) | 1)&0x1FFF); /* Encode Level, mask to make sure length 12  */
                    //temp = BitstreamPutBits(bs,1,1); /* Marker Bit */
                }
            }
        }

        /* Encode Sign Bit */
        if (length != 0)
            /*temp =*/ BitstreamPut1Bits(bs, RLB->s[i]); /* Sign Bit */

    }
    /* Last Coefficient */
    run = RLB->run[i];
    level = RLB->level[i];

    /* Encode Run Length */
    if (level < 9)
    {
        if (intra)
        {
            length = PutCoeff_Intra_Last(run, level, bs);
        }
        else if (level < 4)
        {
            length = PutCoeff_Inter_Last(run, level, bs);
        }
        else
        {
            length = 0;
        }
    }
    else
    {
        length = 0;
    }

    /* First escape mode: LEVEL OFFSET */
    if (length == 0)
    {
        if (intra)
        {
            level_minus_max = level - intra_max_level[1][run];
            if (level_minus_max < 9)
                length = PutLevelCoeff_Intra_Last(run, level_minus_max, bs);
            else
                length = 0;
        }
        else
        {
            level_minus_max = level - inter_max_level[1][run];
            if (level_minus_max < 4)
                length = PutLevelCoeff_Inter_Last(run, level_minus_max, bs);
            else
                length = 0;
        }
        /* Second escape mode: RUN OFFSET */
        if (length == 0)
        {
            if (level < 9)
            {
                if (intra)
                {
                    run_minus_max = run - (intra_max_run1[level] + 1);
                    length = PutRunCoeff_Intra_Last(run_minus_max, level, bs);
                }
                else if (level < 4)
                {
                    run_minus_max = run - (inter_max_run1[level] + 1);
                    length = PutRunCoeff_Inter_Last(run_minus_max, level, bs);
                }
                else
                {
                    length = 0;
                }
            }
            else
            {
                length = 0;
            }
            /* Third escape mode: FIXED LENGTH CODE */
            if (length == 0)
            {
                if (RLB->s[i])
                    level = -level;
                /*temp =*/
                BitstreamPutGT8Bits(bs, 7 + 2 + 1, 31); /* ESCAPE CODE + Followed by 11 + Last Coefficient*/
                //temp = BitstreamPutBits(bs,2,3); /* Followed by 11 */
                //temp = BitstreamPutBits(bs, 1, 1); /* Last Coefficient!!!*/
                /*temp =*/
                BitstreamPutBits(bs, 6 + 1, (run << 1) | 1); /* Encode Run + Marker Bit */
                //temp = BitstreamPutBits(bs,1,1); /* Marker Bit */
                /*temp =*/
                BitstreamPutGT8Bits(bs, 12 + 1, ((level << 1) | 1)&0x1FFF); /* Encode Level, mask to make sure length 8 */
                //temp = BitstreamPutBits(bs,1,1); /* Marker Bit */
            }
        }
    }

    /* Encode Sign Bit */
    if (length != 0)
        /*temp =*/ BitstreamPut1Bits(bs, RLB->s[i]);


    return ;
}

#endif /* H263_ONLY */
/* ======================================================================== */
/*  Function : RUNLevel                                                     */
/*  Date     : 09/20/2000                                                   */
/*  Purpose  : Get the Coded Block Pattern for each block                   */
/*  In/out   :                                                              */
/*      Int* qcoeff     Quantized DCT coefficients
        Int Mode        Coding Mode
        Int ncoeffs     Number of coefficients                              */
/*  Return   :                                                              */
/*      Int CBP         Coded Block Pattern                                 */
/*  Modified :                                                              */
/* ======================================================================== */

void RunLevel(VideoEncData *video, Int intra, Int intraDC_decision, Int ncoefblck[])
{
    Int i, j;
    Int CBP = video->headerInfo.CBP[video->mbnum];
    Int ShortNacNintra = (!(video->vol[video->currLayer]->shortVideoHeader) && video->acPredFlag[video->mbnum] && intra);
    MacroBlock *MB = video->outputMB;
    Short *dataBlock;
    Int level;
    RunLevelBlock *RLB;
    Int run, idx;
    Int *zz, nc, zzorder;
    UChar imask[6] = {0x1F, 0x2F, 0x37, 0x3B, 0x3D, 0x3E};
    UInt *bitmapzz;

    /* Set Run, Level and CBP for this Macroblock */
    /* ZZ scan is done here.  */

    if (intra)
    {

        if (intraDC_decision != 0)
            intra = 0;              /* DC/AC in Run/Level */

        for (i = 0; i < 6 ; i++)
        {

            zz = (Int *) zigzag_inv;

            RLB = video->RLB + i;

            dataBlock = MB->block[i];

            if (intra)
            {
                RLB->run[0] = 0;
                level = dataBlock[0];
                dataBlock[0] = 0; /* reset to zero */
                if (level < 0)
                {
                    RLB->level[0] = -level;
                    RLB->s[0] = 1;
                }
                else
                {
                    RLB->level[0] = level;
                    RLB->s[0] = 0;
                }
            }

            idx = intra;

            if ((CBP >> (5 - i)) & 1)
            {
                if (ShortNacNintra)
                {
                    switch ((video->zz_direction >> (5 - i))&1)
                    {
                        case 0:
                            zz = (Int *)zigzag_v_inv;
                            break;
                        case 1:
                            zz = (Int *)zigzag_h_inv;
                            break;
                    }
                }
                run = 0;
                nc = ncoefblck[i];
                for (j = intra, zz += intra; j < nc; j++, zz++)
                {
                    zzorder = *zz;
                    level = dataBlock[zzorder];
                    if (level == 0)
                        run++;
                    else
                    {
                        dataBlock[zzorder] = 0; /* reset output */
                        if (level < 0)
                        {
                            RLB->level[idx] = -level;
                            RLB->s[idx] = 1;
                            RLB->run[idx] = run;
                            run = 0;
                            idx++;
                        }
                        else
                        {
                            RLB->level[idx] = level;
                            RLB->s[idx] = 0;
                            RLB->run[idx] = run;
                            run = 0;
                            idx++;
                        }
                    }
                }
            }

            ncoefblck[i] = idx; /* 5/22/01, reuse ncoefblck */

            if (idx == intra) /* reset CBP, nothing to be coded */
                CBP &= imask[i];
        }

        video->headerInfo.CBP[video->mbnum] = CBP;

        return ;
    }
    else
    {
//      zz = (Int *) zigzag_inv;  no need to use it, default

        if (CBP)
        {
            for (i = 0; i < 6 ; i++)
            {
                RLB = video->RLB + i;
                idx = 0;

                if ((CBP >> (5 - i)) & 1)
                {   /* 7/30/01 */
                    /* Use bitmapzz to find the Run,Level,Sign symbols */
                    bitmapzz = video->bitmapzz[i];
                    dataBlock = MB->block[i];
                    nc  = ncoefblck[i];

                    idx = zero_run_search(bitmapzz, dataBlock, RLB, nc);
                }
                ncoefblck[i] = idx; /* 5/22/01, reuse ncoefblck */
                if (idx == 0) /* reset CBP, nothing to be coded */
                    CBP &= imask[i];
            }
            video->headerInfo.CBP[video->mbnum] = CBP;
        }
        return ;
    }
}

#ifndef H263_ONLY
#ifdef __cplusplus
extern "C"
{
#endif
    static Bool IntraDCSwitch_Decision(Int Mode, Int intra_dc_vlc_thr, Int intraDCVlcQP)
    {
        Bool switched = FALSE;

        if (Mode == MODE_INTRA || Mode == MODE_INTRA_Q)
        {
            if (intra_dc_vlc_thr != 0)
            {
                switched = (intra_dc_vlc_thr == 7 || intraDCVlcQP >= intra_dc_vlc_thr * 2 + 11);
            }
        }

        return switched;
    }
#ifdef __cplusplus
}
#endif

Int IntraDC_dpcm(Int val, Int lum, BitstreamEncVideo *bitstream)
{
    Int n_bits;
    Int absval, size = 0;

    absval = (val < 0) ? -val : val;    /* abs(val) */


    /* compute dct_dc_size */

    size = 0;
    while (absval)
    {
        absval >>= 1;
        size++;
    }

    if (lum)
    {   /* luminance */
        n_bits = PutDCsize_lum(size, bitstream);
    }
    else
    {   /* chrominance */
        n_bits = PutDCsize_chrom(size, bitstream);
    }

    if (size != 0)
    {
        if (val >= 0)
        {
            ;
        }
        else
        {
            absval = -val; /* set to "-val" MW 14-NOV-1996 */
            val = absval ^((1 << size) - 1);
        }
        BitstreamPutBits(bitstream, (size), (UInt)(val));
        n_bits += size;

        if (size > 8)
            BitstreamPut1Bits(bitstream, 1);
    }

    return n_bits;  /* # bits for intra_dc dpcm */

}

/* ======================================================================== */
/*  Function : DC_AC_PRED                                                   */
/*  Date     : 09/24/2000                                                   */
/*  Purpose  : DC and AC encoding of Intra Blocks                           */
/*  In/out   :                                                              */
/*      VideoEncData    *video
        UChar           Mode                                                */
/*  Return   :                                                              */
/*                                                                          */
/* ======================================================================== */
Int cal_dc_scalerENC(Int QP, Int type) ;


#define PREDICT_AC  for (m = 0; m < 7; m++){ \
                        tmp = DCAC[0]*QPtmp;\
                        if(tmp<0)   tmp = (tmp-(QP/2))/QP;\
                        else        tmp = (tmp+(QP/2))/QP;\
                        pred[m] = tmp;\
                        DCAC++;\
                    }


Void DCACPred(VideoEncData *video, UChar Mode, Int *intraDC_decision, Int intraDCVlcQP)
{
    MacroBlock *MB = video->outputMB;
    Int mbnum = video->mbnum;
    typeDCStore *DC_store = video->predDC + mbnum;
    typeDCACStore *DCAC_row = video->predDCAC_row;
    typeDCACStore *DCAC_col = video->predDCAC_col;
    Short   *DCAC;
    UChar Mode_top, Mode_left;

    Vol *currVol = video->vol[video->currLayer];
    Int nMBPerRow = currVol->nMBPerRow;
    Int x_pos = video->outputMB->mb_x; /* 5/28/01 */
    Int y_pos = video->outputMB->mb_y;
    UChar QP = video->QPMB[mbnum];
    UChar *QPMB = video->QPMB;
    UChar *slice_nb = video->sliceNo;
    Bool bACPredEnable = video->encParams->ACDCPrediction;
    Int *ACpred_flag = video->acPredFlag;
    Int mid_grey = 128 << 3;
    Int m;
    Int comp;
    Int dc_scale = 8, tmp;

    static const Int Xpos[6] = { -1, 0, -1, 0, -1, -1};
    static const Int Ypos[6] = { -1, -1, 0, 0, -1, -1};
    static const Int Xtab[6] = {1, 0, 3, 2, 4, 5};
    static const Int Ytab[6] = {2, 3, 0, 1, 4, 5};
    static const Int Ztab[6] = {3, 2, 1, 0, 4, 5};

    /* I added these to speed up comparisons */
    static const Int Pos0[6] = { 1, 1, 0, 0, 1, 1};
    static const Int Pos1[6] = { 1, 0, 1, 0, 1, 1};
    static const Int B_Xtab[6] = {0, 1, 0, 1, 2, 3};
    static const Int B_Ytab[6] = {0, 0, 1, 1, 2, 3};

    Int direction[6];       /* 0: HORIZONTAL, 1: VERTICAL */
    Int block_A, block_B, block_C;
    Int grad_hor, grad_ver, DC_pred;
    Short pred[7], *predptr;
    Short pcoeff[42];
    Short *qcoeff;
    Int S = 0, S1, S2;
    Int diff, QPtmp;
    Int newCBP[6];
    UChar mask1[6] = {0x20, 0x10, 0x8, 0x4, 0x2, 0x1};
//  UChar mask2[6] = {0x1f,0x2f,0x37,0x3b,0x3d,0x3e};

    Int y_offset, x_offset, x_tab, y_tab, z_tab;    /* speedup coefficients */
    Int b_xtab, b_ytab;

    video->zz_direction = 0;

    /* Standard MPEG-4 Headers do DC/AC prediction*/
    /* check whether neighbors are INTER */
    if (y_pos > 0)
    {
        Mode_top = video->headerInfo.Mode[mbnum-nMBPerRow];
        if (!(Mode_top == MODE_INTRA || Mode_top == MODE_INTRA_Q))
        {
            DCAC = DC_store[-nMBPerRow];
            *DCAC++ = mid_grey;
            *DCAC++ = mid_grey;
            *DCAC++ = mid_grey;
            *DCAC++ = mid_grey;
            *DCAC++ = mid_grey;
            *DCAC++ = mid_grey;
            /* set to 0 DCAC_row[x_pos][0..3] */
            if (bACPredEnable == TRUE)
            {
                M4VENC_MEMSET(DCAC_row[x_pos][0], 0, sizeof(Short) << 5);
            }
        }
    }
    if (x_pos > 0)
    {
        Mode_left = video->headerInfo.Mode[mbnum-1];
        if (!(Mode_left == MODE_INTRA || Mode_left == MODE_INTRA_Q))
        {
            DCAC = DC_store[-1];
            *DCAC++ = mid_grey;
            *DCAC++ = mid_grey;
            *DCAC++ = mid_grey;
            *DCAC++ = mid_grey;
            *DCAC++ = mid_grey;
            *DCAC++ = mid_grey;
            /* set to 0 DCAC_col[x_pos][0..3] */
            if (bACPredEnable == TRUE)
            {
                M4VENC_MEMSET(DCAC_col[0][0], 0, sizeof(Short) << 5);
            }
        }
    }

    S1 = 0;
    S2 = 0;

    for (comp = 0; comp < 6; comp++)
    {

        if (Ypos[comp] != 0)        y_offset = -nMBPerRow;
        else                    y_offset = 0;
        x_offset = Xpos[comp];
        x_tab = Xtab[comp];
        y_tab = Ytab[comp];
        z_tab = Ztab[comp];

        b_xtab = B_Xtab[comp];
        b_ytab = B_Ytab[comp];

        qcoeff = MB->block[comp];

        /****************************/
        /*  Store DC coefficients */
        /****************************/
        /* Store coeff values for Intra MB */
        if (comp == 0) dc_scale = cal_dc_scalerENC(QP, 1) ;
        if (comp == 4) dc_scale = cal_dc_scalerENC(QP, 2) ;

        QPtmp = qcoeff[0] * dc_scale; /* DC value */

        if (QPtmp > 2047)   /* 10/10/01, add clipping (bug fixed) */
            DC_store[0][comp] = 2047;
        else if (QPtmp < -2048)
            DC_store[0][comp] = -2048;
        else
            DC_store[0][comp] = QPtmp;

        /**************************************************************/
        /* Find the direction of the prediction and the DC prediction */
        /**************************************************************/

        if ((x_pos == 0) && y_pos == 0)
        {   /* top left corner */
            block_A = (comp == 1 || comp == 3) ? DC_store[0][x_tab] : mid_grey;
            block_B = (comp == 3) ? DC_store[x_offset][z_tab] : mid_grey;
            block_C = (comp == 2 || comp == 3) ? DC_store[0][y_tab] : mid_grey;
        }
        else if (x_pos == 0)
        {   /* left edge */
            block_A = (comp == 1 || comp == 3) ? DC_store[0][x_tab] : mid_grey;
            block_B = ((comp == 1 && (slice_nb[mbnum] == slice_nb[mbnum-nMBPerRow])) || comp == 3) ?
                      DC_store[y_offset+x_offset][z_tab] : mid_grey;
            block_C = (comp == 2 || comp == 3 ||
                       (Pos0[comp] && (slice_nb[mbnum] == slice_nb[mbnum-nMBPerRow]))) ?
                      DC_store[y_offset][y_tab] : mid_grey;
        }
        else if (y_pos == 0)
        { /* top row */
            block_A = (comp == 1 || comp == 3 || (Pos1[comp] && (slice_nb[mbnum] == slice_nb[mbnum-1]))) ?
                      DC_store[x_offset][x_tab] : mid_grey;
            block_B = ((comp == 2 && (slice_nb[mbnum] == slice_nb[mbnum-1])) || comp == 3) ?
                      DC_store[y_offset + x_offset][z_tab] : mid_grey;
            block_C = (comp == 2 || comp == 3) ?
                      DC_store[y_offset][y_tab] : mid_grey;
        }
        else
        {
            block_A = (comp == 1 || comp == 3 || (Pos1[comp] && (slice_nb[mbnum] == slice_nb[mbnum-1]))) ?
                      DC_store[x_offset][x_tab] : mid_grey;
            block_B = (((comp == 0 || comp == 4 || comp == 5) &&
                        (slice_nb[mbnum] == slice_nb[mbnum-1-nMBPerRow])) ||
                       (comp == 1 && (slice_nb[mbnum] == slice_nb[mbnum-nMBPerRow])) ||
                       (comp == 2 && (slice_nb[mbnum] == slice_nb[mbnum-1])) || (comp == 3)) ?
                      (DC_store[y_offset + x_offset][z_tab]) : mid_grey;
            block_C = (comp == 2 || comp == 3 || (Pos0[comp] && (slice_nb[mbnum] == slice_nb[mbnum-nMBPerRow]))) ?
                      DC_store[y_offset][y_tab] : mid_grey;
        }
        grad_hor = block_B - block_C;
        grad_ver = block_A - block_B;

        if ((PV_ABS(grad_ver)) < (PV_ABS(grad_hor)))
        {
            DC_pred = block_C;
            direction[comp] = 1;
            video->zz_direction = (video->zz_direction) | mask1[comp];

        }
        else
        {
            DC_pred = block_A;
            direction[comp] = 0;
            //video->zz_direction=video->zz_direction<<1;
        }

        /* DC prediction */
        QPtmp = dc_scale; /* 5/28/01 */
        qcoeff[0] -= (DC_pred + QPtmp / 2) / QPtmp;


        if (bACPredEnable)
        {
            /***********************/
            /* Find AC prediction  */
            /***********************/

            if ((x_pos == 0) && y_pos == 0)     /* top left corner */
            {
                if (direction[comp] == 0)
                {
                    if (comp == 1 || comp == 3)
                    {
                        QPtmp = QPMB[mbnum+x_offset];
                        DCAC = DCAC_col[0][b_ytab];
                        if (QPtmp != QP)
                        {
                            predptr = pred;
                            PREDICT_AC
                        }
                        else
                        {
                            predptr = DCAC;
                        }
                    }
                    else
                    {
                        predptr = pred;
                        pred[0] = pred[1] = pred[2] = pred[3] = pred[4] = pred[5] = pred[6] = 0;
                    }
                }
                else
                {
                    if (comp == 2 || comp == 3)
                    {
                        QPtmp = QPMB[mbnum+ y_offset];
                        DCAC = DCAC_row[x_pos][b_xtab];
                        if (QPtmp != QP)
                        {
                            predptr = pred;
                            PREDICT_AC
                        }
                        else
                        {
                            predptr = DCAC;
                        }
                    }
                    else
                    {
                        predptr = pred;
                        pred[0] = pred[1] = pred[2] = pred[3] = pred[4] = pred[5] = pred[6] = 0;
                    }
                }
            }
            else if (x_pos == 0)    /* left edge */
            {
                if (direction[comp] == 0)
                {
                    if (comp == 1 || comp == 3)
                    {
                        QPtmp = QPMB[mbnum+x_offset];
                        DCAC = DCAC_col[0][b_ytab];
                        if (QPtmp != QP)
                        {
                            predptr = pred;
                            PREDICT_AC
                        }
                        else
                        {
                            predptr = DCAC;
                        }
                    }
                    else
                    {
                        predptr = pred;
                        pred[0] = pred[1] = pred[2] = pred[3] = pred[4] = pred[5] = pred[6] = 0;
                    }
                }
                else
                {

                    if ((Pos0[comp] && (slice_nb[mbnum] == slice_nb[mbnum-nMBPerRow]))
                            || comp == 2 || comp == 3)
                    {
                        QPtmp = QPMB[mbnum+y_offset];
                        DCAC = DCAC_row[x_pos][b_xtab];
                        if (QPtmp != QP)
                        {
                            predptr = pred;
                            PREDICT_AC
                        }
                        else
                        {
                            predptr = DCAC;
                        }
                    }
                    else
                    {
                        predptr = pred;
                        pred[0] = pred[1] = pred[2] = pred[3] = pred[4] = pred[5] = pred[6] = 0;
                    }
                }
            }
            else if (y_pos == 0)  /* top row */
            {
                if (direction[comp] == 0)
                {
                    if ((Pos1[comp] && (slice_nb[mbnum] == slice_nb[mbnum-1]))
                            || comp == 1 || comp == 3)
                    {
                        QPtmp = QPMB[mbnum+x_offset];
                        DCAC = DCAC_col[0][b_ytab];
                        if (QPtmp != QP)
                        {
                            predptr = pred;
                            PREDICT_AC
                        }
                        else
                        {
                            predptr = DCAC;
                        }
                    }
                    else
                    {
                        predptr = pred;
                        pred[0] = pred[1] = pred[2] = pred[3] = pred[4] = pred[5] = pred[6] = 0;
                    }
                }
                else
                {
                    if (comp == 2 || comp == 3)
                    {
                        QPtmp = QPMB[mbnum+y_offset];
                        DCAC = DCAC_row[x_pos][b_xtab];
                        if (QPtmp != QP)
                        {
                            predptr = pred;
                            PREDICT_AC
                        }
                        else
                        {
                            predptr = DCAC;
                        }
                    }
                    else
                    {
                        predptr = pred;
                        pred[0] = pred[1] = pred[2] = pred[3] = pred[4] = pred[5] = pred[6] = 0;
                    }
                }
            }
            else
            {
                if (direction[comp] == 0)
                {
                    if ((Pos1[comp] && (slice_nb[mbnum] == slice_nb[mbnum-1]))
                            || comp == 1 || comp == 3)
                    {
                        QPtmp = QPMB[mbnum+x_offset];
                        DCAC = DCAC_col[0][b_ytab];
                        if (QPtmp != QP)
                        {
                            predptr = pred;
                            PREDICT_AC
                        }
                        else
                        {
                            predptr = DCAC;
                        }
                    }
                    else
                    {
                        predptr = pred;
                        pred[0] = pred[1] = pred[2] = pred[3] = pred[4] = pred[5] = pred[6] = 0;
                    }
                }
                else
                {
                    if ((Pos0[comp] && (slice_nb[mbnum] == slice_nb[mbnum-nMBPerRow]))
                            || comp  == 2 || comp == 3)
                    {
                        QPtmp = QPMB[mbnum+y_offset];
                        DCAC = DCAC_row[x_pos][b_xtab];
                        if (QPtmp != QP)
                        {
                            predptr = pred;
                            PREDICT_AC
                        }
                        else
                        {
                            predptr = DCAC;
                        }
                    }
                    else
                    {
                        predptr = pred;
                        pred[0] = pred[1] = pred[2] = pred[3] = pred[4] = pred[5] = pred[6] = 0;
                    }
                }
            }

            /************************************/
            /* Decide and Perform AC prediction */
            /************************************/
            newCBP[comp] = 0;

            if (direction[comp] == 0)   /* Horizontal, left COLUMN of block A */
            {
                DCAC = pcoeff + comp * 7; /* re-use DCAC as local var */
                qcoeff += 8;
                for (m = 0; m < 7; m++)
                {
                    QPtmp = qcoeff[m<<3];
                    if (QPtmp > 0)  S1 += QPtmp;
                    else        S1 -= QPtmp;
                    QPtmp -= predptr[m];
                    DCAC[m] = QPtmp; /* save prediction residue to pcoeff*/
                    if (QPtmp)  newCBP[comp] = 1;
                    diff = PV_ABS(QPtmp);
                    S2 += diff;
                }
            }
            else            /* Vertical, top ROW of block C */
            {
                qcoeff++;
                DCAC = pcoeff + comp * 7; /* re-use DCAC as local var */
                for (m = 0; m < 7; m++)
                {
                    QPtmp = qcoeff[m];
                    if (QPtmp > 0)  S1 += QPtmp;
                    else        S1 -= QPtmp;
                    QPtmp -= predptr[m];
                    DCAC[m] = QPtmp; /* save prediction residue to pcoeff*/
                    if (QPtmp)  newCBP[comp] = 1;
                    diff = PV_ABS(QPtmp);
                    S2 += diff;
                }
            }

            /****************************/
            /*  Store DCAC coefficients */
            /****************************/
            /* Store coeff values for Intra MB */
            qcoeff = MB->block[comp];
            DCAC = DCAC_row[x_pos][b_xtab];
            DCAC[0] = qcoeff[1];
            DCAC[1] = qcoeff[2];
            DCAC[2] = qcoeff[3];
            DCAC[3] = qcoeff[4];
            DCAC[4] = qcoeff[5];
            DCAC[5] = qcoeff[6];
            DCAC[6] = qcoeff[7];

            DCAC = DCAC_col[0][b_ytab];
            DCAC[0] = qcoeff[8];
            DCAC[1] = qcoeff[16];
            DCAC[2] = qcoeff[24];
            DCAC[3] = qcoeff[32];
            DCAC[4] = qcoeff[40];
            DCAC[5] = qcoeff[48];
            DCAC[6] = qcoeff[56];


        } /* bACPredEnable */

    } /* END COMP FOR LOOP */

    //if (diff > 2047)
    //    break;
    S += (S1 - S2);


    if (S >= 0 && bACPredEnable == TRUE)
    {
        ACpred_flag[mbnum] = 1;
        DCAC = pcoeff; /* prediction residue */
        qcoeff = MB->block[0];

        for (comp = 0; comp < 6; comp++)
        {
            if (direction[comp] == 0)
            {
                qcoeff[8] = DCAC[0];
                qcoeff[16] = DCAC[1];
                qcoeff[24] = DCAC[2];
                qcoeff[32] = DCAC[3];
                qcoeff[40] = DCAC[4];
                qcoeff[48] = DCAC[5];
                qcoeff[56] = DCAC[6];

            }
            else
            {
                qcoeff[1] = DCAC[0];
                qcoeff[2] = DCAC[1];
                qcoeff[3] = DCAC[2];
                qcoeff[4] = DCAC[3];
                qcoeff[5] = DCAC[4];
                qcoeff[6] = DCAC[5];
                qcoeff[7] = DCAC[6];
            }
            if (newCBP[comp]) /* 5/28/01, update CBP */
                video->headerInfo.CBP[mbnum] |= mask1[comp];
            DCAC += 7;
            qcoeff += 64;
        }
    }
    else  /* Only DC Prediction */
    {
        ACpred_flag[mbnum] = 0;
    }

    *intraDC_decision = IntraDCSwitch_Decision(Mode, video->currVop->intraDCVlcThr, intraDCVlcQP);
    if (*intraDC_decision) /* code DC with AC , 5/28/01*/
    {
        qcoeff = MB->block[0];
        for (comp = 0; comp < 6; comp++)
        {
            if (*qcoeff)
                video->headerInfo.CBP[mbnum] |= mask1[comp];
            qcoeff += 64;
        }
    }
    return;
}
#endif /* H263_ONLY */



Void find_pmvs(VideoEncData *video, Int block, Int *mvx, Int *mvy)
{
    Vol *currVol = video->vol[video->currLayer];
//  UChar *Mode = video->headerInfo.Mode; /* modes for MBs */
    UChar *slice_nb = video->sliceNo;
    Int nMBPerRow = currVol->nMBPerRow;
    Int mbnum = video->mbnum;

    Int   p1x, p2x, p3x;
    Int   p1y, p2y, p3y;
    Int   xin1, xin2, xin3;
    Int   yin1, yin2, yin3;
    Int   vec1, vec2, vec3;
    Int   rule1, rule2, rule3;
    MOT   **motdata = video->mot;
    Int   x = mbnum % nMBPerRow;
    Int   y = mbnum / nMBPerRow;

    /*
        In a previous version, a MB vector (block = 0) was predicted the same way
        as block 1, which is the most likely interpretation of the VM.

        Therefore, if we have advanced pred. mode, and if all MBs around have
        only one 16x16 vector each, we chose the appropiate block as if these
        MBs have 4 vectors.

        This different prediction affects only 16x16 vectors of MBs with
        transparent blocks.

        In the current version, we choose for the 16x16 mode the first
        non-transparent block in the surrounding MBs
    */

    switch (block)
    {
        case 0:
            vec1 = 2 ;
            yin1 = y  ;
            xin1 = x - 1;
            vec2 = 3 ;
            yin2 = y - 1;
            xin2 = x;
            vec3 = 3 ;
            yin3 = y - 1;
            xin3 = x + 1;
            break;

        case 1:
            vec1 = 2 ;
            yin1 = y  ;
            xin1 = x - 1;
            vec2 = 3 ;
            yin2 = y - 1;
            xin2 = x;
            vec3 = 3 ;
            yin3 = y - 1;
            xin3 = x + 1;
            break;

        case 2:
            vec1 = 1 ;
            yin1 = y  ;
            xin1 = x;
            vec2 = 4 ;
            yin2 = y - 1;
            xin2 = x;
            vec3 = 3 ;
            yin3 = y - 1;
            xin3 = x + 1;
            break;

        case 3:
            vec1 = 4 ;
            yin1 = y  ;
            xin1 = x - 1;
            vec2 = 1 ;
            yin2 = y  ;
            xin2 = x;
            vec3 = 2 ;
            yin3 = y  ;
            xin3 = x;
            break;

        default: /* case 4 */
            vec1 = 3 ;
            yin1 = y  ;
            xin1 = x;
            vec2 = 1 ;
            yin2 = y  ;
            xin2 = x;
            vec3 = 2 ;
            yin3 = y  ;
            xin3 = x;
            break;
    }

    if (block == 0)
    {
        /* according to the motion encoding, we must choose a first non-transparent
        block in the surrounding MBs (16-mode)
            */

        if (x > 0 && slice_nb[mbnum] == slice_nb[mbnum-1])
            rule1 = 0;
        else
            rule1 = 1;

        if (y > 0 && slice_nb[mbnum] == slice_nb[mbnum-nMBPerRow])
            rule2 = 0;
        else
            rule2 = 1;

        if ((x != nMBPerRow - 1) && (y > 0) && slice_nb[mbnum] == slice_nb[mbnum+1-nMBPerRow])
            rule3 = 0;
        else
            rule3 = 1;
    }
    else
    {
        /* check borders for single blocks (advanced mode) */
        /* rule 1 */
        if (((block == 1 || block == 3) &&
                (x == 0 || slice_nb[mbnum] != slice_nb[mbnum-1])))
            rule1 = 1;
        else
            rule1 = 0;

        /* rule 2 */
        if (((block == 1 || block == 2) &&
                (y == 0 || slice_nb[mbnum] != slice_nb[mbnum-nMBPerRow])))
            rule2 = 1;
        else
            rule2 = 0;

        /* rule 3 */
        if (((block == 1 || block == 2) &&
                (x == nMBPerRow - 1 || y == 0 || slice_nb[mbnum] != slice_nb[mbnum+1-nMBPerRow])))
            rule3 = 1;
        else
            rule3 = 0;
    }

    if (rule1)
    {
        p1x = p1y = 0;
    }
    else
    {

        p1x = motdata[yin1*nMBPerRow+xin1][vec1].x;
        p1y = motdata[yin1*nMBPerRow+xin1][vec1].y;
        //p1x = motxdata[xin1*2+(vec1&0x1) + (yin1*2+(vec1>>1))*xB];
        //p1y = motydata[xin1*2+(vec1&0x1) + (yin1*2+(vec1>>1))*xB];
    }

    if (rule2)
    {
        p2x = p2y = 0;
    }
    else
    {
        p2x = motdata[yin2*nMBPerRow+xin2][vec2].x;
        p2y = motdata[yin2*nMBPerRow+xin2][vec2].y;
        //p2x = motxdata[xin2*2+(vec2&0x1) + (yin2*2+(vec2>>1))*xB];
        //p2y = motydata[xin2*2+(vec2&0x1) + (yin2*2+(vec2>>1))*xB];
    }

    if (rule3)
    {
        p3x = p3y = 0;
    }
    else
    {
        p3x = motdata[yin3*nMBPerRow+xin3][vec3].x;
        p3y = motdata[yin3*nMBPerRow+xin3][vec3].y;
        //p3x = motxdata[xin3*2+ (vec3&0x1) + (yin3*2+(vec3>>1))*xB];
        //p3y = motydata[xin3*2+ (vec3&0x1) + (yin3*2+(vec3>>1))*xB];
    }

    if (rule1 && rule2 && rule3)
    {
        /* all MBs are outside the VOP */
        *mvx = *mvy = 0;
    }
    else if (rule1 + rule2 + rule3 == 2)
    {
        /* two of three are zero */
        *mvx = (p1x + p2x + p3x);
        *mvy = (p1y + p2y + p3y);
    }
    else
    {
        *mvx = ((p1x + p2x + p3x - PV_MAX(p1x, PV_MAX(p2x, p3x)) - PV_MIN(p1x, PV_MIN(p2x, p3x))));
        *mvy = ((p1y + p2y + p3y - PV_MAX(p1y, PV_MAX(p2y, p3y)) - PV_MIN(p1y, PV_MIN(p2y, p3y))));
    }

    return;
}


Void WriteMVcomponent(Int f_code, Int dmv, BitstreamEncVideo *bs)
{
    Int residual, vlc_code_mag, bits, entry;

    ScaleMVD(f_code, dmv, &residual, &vlc_code_mag);

    if (vlc_code_mag < 0)
        entry = vlc_code_mag + 65;
    else
        entry = vlc_code_mag;

    bits = PutMV(entry, bs);

    if ((f_code != 1) && (vlc_code_mag != 0))
    {
        BitstreamPutBits(bs, f_code - 1, residual);
        bits += f_code - 1;
    }
    return;
}


Void
ScaleMVD(
    Int  f_code,       /* <-- MV range in 1/2 units: 1=32,2=64,...,7=2048     */
    Int  diff_vector,  /* <-- MV Difference commponent in 1/2 units           */
    Int  *residual,    /* --> value to be FLC coded                           */
    Int  *vlc_code_mag /* --> value to be VLC coded                           */
)
{
    Int   range;
    Int   scale_factor;
    Int   r_size;
    Int   low;
    Int   high;
    Int   aux;

    r_size = f_code - 1;
    scale_factor = 1 << r_size;
    range = 32 * scale_factor;
    low   = -range;
    high  =  range - 1;

    if (diff_vector < low)
        diff_vector += 2 * range;
    else if (diff_vector > high)
        diff_vector -= 2 * range;

    if (diff_vector == 0)
    {
        *vlc_code_mag = 0;
        *residual = 0;
    }
    else if (scale_factor == 1)
    {
        *vlc_code_mag = diff_vector;
        *residual = 0;
    }
    else
    {
        aux = PV_ABS(diff_vector) + scale_factor - 1;
        *vlc_code_mag = aux >> r_size;

        if (diff_vector < 0)
            *vlc_code_mag = -*vlc_code_mag;
        *residual = aux & (scale_factor - 1);
    }
}
