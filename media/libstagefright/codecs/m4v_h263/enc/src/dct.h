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
#ifndef _DCT_H_
#define _DCT_H_

const static Int ColThInter[32] = {0, 0x1C, 0x4C, 0x6C, 0x9C, 0xBC, 0xEC, 0x10C,
                                   0x13C, 0x15C, 0x18C, 0x1AC, 0x1DC, 0x1FC, 0x22C, 0x24C,
                                   0x27C, 0x29C, 0x2CC, 0x2EC, 0x31C, 0x33C, 0x36C, 0x38C,
                                   0x3BC, 0x3DC, 0x40C, 0x42C, 0x45C, 0x47C, 0x4AC, 0x4CC
                                  };

const static Int ColThIntra[32] = {0, 0x1C, 0x3C, 0x5C, 0x7C, 0x9C, 0xBC, 0xDC,
                                   0xFC, 0x11C, 0x13C, 0x15C, 0x17C, 0x19C, 0x1BC, 0x1DC,
                                   0x1FC, 0x21C, 0x23C, 0x25C, 0x27C, 0x29C, 0x2BC, 0x2DC,
                                   0x2FC, 0x31C, 0x33C, 0x35C, 0x37C, 0x39C, 0x3BC, 0x3DC
                                  };

/******************************************************/
/********** IDCT part **************************/
const static unsigned char imask[8] = {128, 64, 32, 16, 8, 4, 2, 1};
const static unsigned char mask[8] = {0x7f, 0xbf, 0xdf, 0xef, 0xf7, 0xfb, 0xfd, 0xfe};

#define W1 2841                 /* 2048*sqrt(2)*cos(1*pi/16) */
#define W2 2676                 /* 2048*sqrt(2)*cos(2*pi/16) */
#define W3 2408                 /* 2048*sqrt(2)*cos(3*pi/16) */
#define W5 1609                 /* 2048*sqrt(2)*cos(5*pi/16) */
#define W6 1108                 /* 2048*sqrt(2)*cos(6*pi/16) */
#define W7 565                  /* 2048*sqrt(2)*cos(7*pi/16) */

#ifdef __cplusplus
extern "C"
{
#endif

    /* Reduced input IDCT */
    void idct_col0(Short *blk);
    void idct_col1(Short *blk);
    void idct_col2(Short *blk);
    void idct_col3(Short *blk);
    void idct_col4(Short *blk);
    void idct_col0x40(Short *blk);
    void idct_col0x20(Short *blk);
    void idct_col0x10(Short *blk);

    void idct_rowInter(Short *srce, UChar *rec, Int lx);
    void idct_row0Inter(Short *blk, UChar *rec, Int lx);
    void idct_row1Inter(Short *blk, UChar *rec, Int lx);
    void idct_row2Inter(Short *blk, UChar *rec, Int lx);
    void idct_row3Inter(Short *blk, UChar *rec, Int lx);
    void idct_row4Inter(Short *blk, UChar *rec, Int lx);
    void idct_row0x40Inter(Short *blk, UChar *rec, Int lx);
    void idct_row0x20Inter(Short *blk, UChar *rec, Int lx);
    void idct_row0x10Inter(Short *blk, UChar *rec, Int lx);
    void idct_row0xCCInter(Short *blk, UChar *rec, Int lx);
    void idct_rowIntra(Short *srce, UChar *rec, Int lx);
    void idct_row0Intra(Short *blk, UChar *rec, Int lx);
    void idct_row1Intra(Short *blk, UChar *rec, Int lx);
    void idct_row2Intra(Short *blk, UChar *rec, Int lx);
    void idct_row3Intra(Short *blk, UChar *rec, Int lx);
    void idct_row4Intra(Short *blk, UChar *rec, Int lx);
    void idct_row0x40Intra(Short *blk, UChar *rec, Int lx);
    void idct_row0x20Intra(Short *blk, UChar *rec, Int lx);
    void idct_row0x10Intra(Short *blk, UChar *rec, Int lx);
    void idct_row0xCCIntra(Short *blk, UChar *rec, Int lx);
    void idct_rowzmv(Short *srce, UChar *rec, UChar *prev, Int lx);
    void idct_row0zmv(Short *blk, UChar *rec, UChar *prev, Int lx);
    void idct_row1zmv(Short *blk, UChar *rec, UChar *prev, Int lx);
    void idct_row2zmv(Short *blk, UChar *rec, UChar *prev, Int lx);
    void idct_row3zmv(Short *blk, UChar *rec, UChar *prev, Int lx);
    void idct_row4zmv(Short *blk, UChar *rec, UChar *prev, Int lx);
    void idct_row0x40zmv(Short *blk, UChar *rec, UChar *prev, Int lx);
    void idct_row0x20zmv(Short *blk, UChar *rec, UChar *prev, Int lx);
    void idct_row0x10zmv(Short *blk, UChar *rec, UChar *prev, Int lx);
    void idct_row0xCCzmv(Short *blk, UChar *rec, UChar *prev, Int lx);


#ifdef __cplusplus
}
#endif

/* Look-up table mapping to RIDCT from bitmap */
#ifdef SMALL_DCT

static void (*const idctcolVCA[16])(Short*) =
{
    &idct_col0, &idct_col4, &idct_col3, &idct_col4,
    &idct_col2, &idct_col4, &idct_col3, &idct_col4,
    &idct_col1, &idct_col4, &idct_col3, &idct_col4,
    &idct_col2, &idct_col4, &idct_col3, &idct_col4
};

static void (*const idctrowVCAInter[16])(Short*, UChar*, Int) =
{
    &idct_row0Inter, &idct_row4Inter, &idct_row3Inter, &idct_row4Inter,
    &idct_row2Inter, &idct_row4Inter, &idct_row3Inter, &idct_row4Inter,
    &idct_row1Inter, &idct_row4Inter, &idct_row3Inter, &idct_row4Inter,
    &idct_row2Inter, &idct_row4Inter, &idct_row3Inter, &idct_row4Inter
};

static void (*const idctrowVCAzmv[16])(Short*, UChar*, UChar*, Int) =
{
    &idct_row0zmv, &idct_row4zmv, &idct_row3zmv, &idct_row4zmv,
    &idct_row2zmv, &idct_row4zmv, &idct_row3zmv, &idct_row4zmv,
    &idct_row1zmv, &idct_row4zmv, &idct_row3zmv, &idct_row4zmv,
    &idct_row2zmv, &idct_row4zmv, &idct_row3zmv, &idct_row4zmv
};


static void (*const idctrowVCAIntra[16])(Short*, UChar*, Int) =
{
    &idct_row0Intra, &idct_row4Intra, &idct_row3Intra, &idct_row4Intra,
    &idct_row2Intra, &idct_row4Intra, &idct_row3Intra, &idct_row4Intra,
    &idct_row1Intra, &idct_row4Intra, &idct_row3Intra, &idct_row4Intra,
    &idct_row2Intra, &idct_row4Intra, &idct_row3Intra, &idct_row4Intra
};

#else /* SMALL_DCT */

static void (*const idctcolVCA[16])(Short*) =
{
    &idct_col0, &idct_col0x10, &idct_col0x20, &idct_col4,
    &idct_col0x40, &idct_col4, &idct_col3, &idct_col4,
    &idct_col1, &idct_col4, &idct_col3, &idct_col4,
    &idct_col2, &idct_col4, &idct_col3, &idct_col4
};

static void (*const idctrowVCAInter[16])(Short*, UChar*, Int) =
{
    &idct_row0Inter, &idct_row0x10Inter, &idct_row0x20Inter, &idct_row4Inter,
    &idct_row0x40Inter, &idct_row4Inter, &idct_row3Inter, &idct_row4Inter,
    &idct_row1Inter, &idct_row4Inter, &idct_row3Inter, &idct_row4Inter,
    &idct_row2Inter, &idct_row4Inter, &idct_row3Inter, &idct_row4Inter
};

static void (*const idctrowVCAzmv[16])(Short*, UChar*, UChar*, Int) =
{
    &idct_row0zmv, &idct_row0x10zmv, &idct_row0x20zmv, &idct_row4zmv,
    &idct_row0x40zmv, &idct_row4zmv, &idct_row3zmv, &idct_row4zmv,
    &idct_row1zmv, &idct_row4zmv, &idct_row3zmv, &idct_row4zmv,
    &idct_row2zmv, &idct_row4zmv, &idct_row3zmv, &idct_row4zmv
};

static void (*const idctrowVCAIntra[16])(Short*, UChar*, Int) =
{
    &idct_row0Intra, &idct_row0x10Intra, &idct_row0x20Intra, &idct_row4Intra,
    &idct_row0x40Intra, &idct_row4Intra, &idct_row3Intra, &idct_row4Intra,
    &idct_row1Intra, &idct_row4Intra, &idct_row3Intra, &idct_row4Intra,
    &idct_row2Intra, &idct_row4Intra, &idct_row3Intra, &idct_row4Intra
};

#endif /* SMALL_DCT */

#ifdef __cplusplus
extern "C"
{
#endif
    /* part in AppVCA_dct.c */
//void Block1x1DCTzmv (Short *out,UChar *prev,UChar *cur,UChar *rec,Int lx,Int chroma);
    void Block1x1DCTwSub(Short *out, UChar *cur, UChar *prev, Int pitch_chroma);
    void Block1x1DCTIntra(Short *out, UChar *cur, UChar *dummy1, Int pitch_chroma);
    /* This part is in dct_aan.c */
    Void BlockDCT_AANwSub(Short *out, UChar *cur, UChar *prev, Int pitch_chroma);
    Void Block4x4DCT_AANwSub(Short *out, UChar *cur, UChar *prev, Int pitch_chroma);
    Void Block2x2DCT_AANwSub(Short *out, UChar *cur, UChar *prev, Int pitch_chroma);
//Void BlockDCT_AANzmv(Short *out,UChar *prev,UChar *cur,UChar *rec,Int ColTh,Int lx,Int chroma);
//Void Block4x4DCT_AANzmv(Short *out,UChar *prev,UChar *cur,UChar *rec,Int ColTh,Int lx,Int chroma);
//Void Block2x2DCT_AANzmv(Short *out,UChar *prev,UChar *cur,UChar *rec,Int ColTh,Int lx,Int chroma);
    Void BlockDCT_AANIntra(Short *out, UChar *cur, UChar *dummy1, Int pitch_chroma);
    Void Block4x4DCT_AANIntra(Short *out, UChar *cur, UChar *dummy1, Int pitch_chroma);
    Void Block2x2DCT_AANIntra(Short *out, UChar *cur, UChar *dummy1, Int pitch_chroma);

#ifdef __cplusplus
}
#endif

#endif //_DCT_H_
