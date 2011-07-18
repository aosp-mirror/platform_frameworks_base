/*
 ** Copyright 2003-2010, VisualOn, Inc.
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at
 **
 **     http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ** See the License for the specific language governing permissions and
 ** limitations under the License.
 */
/*******************************************************************************
	File:		aac_rom.h

	Content:	constant tables

*******************************************************************************/

#ifndef ROM_H
#define ROM_H

#include "config.h"
#include "psy_const.h"
#include "tns_param.h"

/*
  mdct
*/
extern const int ShortWindowSine[FRAME_LEN_SHORT/2];
extern const int LongWindowKBD[FRAME_LEN_LONG/2];

extern const unsigned char bitrevTab[17 + 129];
extern const int cossintab[128 + 1024];

#if defined (ARMV5E) && !defined (ARMV7Neon)
extern const int twidTab64[(4*6 + 16*6)/2];
extern const int twidTab512[(8*6 + 32*6 + 128*6)/2];
#else
extern const int twidTab64[4*6 + 16*6];
extern const int twidTab512[8*6 + 32*6 + 128*6];
#endif

/*
  form factor
*/
extern const Word32 formfac_sqrttable[96];

/*
  quantizer
*/
extern const Word32 mTab_3_4[512];
extern const Word32 mTab_4_3[512];
/*! $2^{-\frac{n}{16}}$ table */
extern const Word16 pow2tominusNover16[17] ;

extern Word32 specExpMantTableComb_enc[4][14];
extern const UWord8 specExpTableComb_enc[4][14];

extern const Word16 quantBorders[4][4];
//extern const Word16 quantRecon[3][4];
extern const Word16 quantRecon[4][3];

/*
  huffman
*/
extern const UWord16 huff_ltab1_2[3][3][3][3];
extern const UWord16 huff_ltab3_4[3][3][3][3];
extern const UWord16 huff_ltab5_6[9][9];
extern const UWord16 huff_ltab7_8[8][8];
extern const UWord16 huff_ltab9_10[13][13];
extern const UWord16 huff_ltab11[17][17];
extern const UWord16 huff_ltabscf[121];
extern const UWord16 huff_ctab1[3][3][3][3];
extern const UWord16 huff_ctab2[3][3][3][3];
extern const UWord16 huff_ctab3[3][3][3][3];
extern const UWord16 huff_ctab4[3][3][3][3];
extern const UWord16 huff_ctab5[9][9];
extern const UWord16 huff_ctab6[9][9];
extern const UWord16 huff_ctab7[8][8];
extern const UWord16 huff_ctab8[8][8];
extern const UWord16 huff_ctab9[13][13];
extern const UWord16 huff_ctab10[13][13];
extern const UWord16 huff_ctab11[17][17];
extern const UWord32 huff_ctabscf[121];



/*
  misc
*/
extern const int sampRateTab[NUM_SAMPLE_RATES];
extern const int BandwithCoefTab[8][NUM_SAMPLE_RATES];
extern const int rates[8];
extern const UWord8 sfBandTotalShort[NUM_SAMPLE_RATES];
extern const UWord8 sfBandTotalLong[NUM_SAMPLE_RATES];
extern const int sfBandTabShortOffset[NUM_SAMPLE_RATES];
extern const short sfBandTabShort[76];
extern const int sfBandTabLongOffset[NUM_SAMPLE_RATES];
extern const short sfBandTabLong[325];

extern const Word32 m_log2_table[INT_BITS];

/*
  TNS
*/
extern const Word32 tnsCoeff3[8];
extern const Word32 tnsCoeff3Borders[8];
extern const Word32 tnsCoeff4[16];
extern const Word32 tnsCoeff4Borders[16];
extern const Word32 invSBF[24];
extern const Word16 sideInfoTabLong[MAX_SFB_LONG + 1];
extern const Word16 sideInfoTabShort[MAX_SFB_SHORT + 1];
#endif
