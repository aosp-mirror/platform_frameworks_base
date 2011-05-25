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
	File:		bit_cnt.h

	Content:	Huffman Bitcounter & coder structure and functions

*******************************************************************************/

#ifndef __BITCOUNT_H
#define __BITCOUNT_H

#include "bitbuffer.h"
#include "basic_op.h"
#define INVALID_BITCOUNT (MAX_16/4)

/*
  code book number table
*/

enum codeBookNo{
  CODE_BOOK_ZERO_NO=               0,
  CODE_BOOK_1_NO=                  1,
  CODE_BOOK_2_NO=                  2,
  CODE_BOOK_3_NO=                  3,
  CODE_BOOK_4_NO=                  4,
  CODE_BOOK_5_NO=                  5,
  CODE_BOOK_6_NO=                  6,
  CODE_BOOK_7_NO=                  7,
  CODE_BOOK_8_NO=                  8,
  CODE_BOOK_9_NO=                  9,
  CODE_BOOK_10_NO=                10,
  CODE_BOOK_ESC_NO=               11,
  CODE_BOOK_RES_NO=               12,
  CODE_BOOK_PNS_NO=               13
};

/*
  code book index table
*/

enum codeBookNdx{
  CODE_BOOK_ZERO_NDX=0,
  CODE_BOOK_1_NDX,
  CODE_BOOK_2_NDX,
  CODE_BOOK_3_NDX,
  CODE_BOOK_4_NDX,
  CODE_BOOK_5_NDX,
  CODE_BOOK_6_NDX,
  CODE_BOOK_7_NDX,
  CODE_BOOK_8_NDX,
  CODE_BOOK_9_NDX,
  CODE_BOOK_10_NDX,
  CODE_BOOK_ESC_NDX,
  CODE_BOOK_RES_NDX,
  CODE_BOOK_PNS_NDX,
  NUMBER_OF_CODE_BOOKS
};

/*
  code book lav table
*/

enum codeBookLav{
  CODE_BOOK_ZERO_LAV=0,
  CODE_BOOK_1_LAV=1,
  CODE_BOOK_2_LAV=1,
  CODE_BOOK_3_LAV=2,
  CODE_BOOK_4_LAV=2,
  CODE_BOOK_5_LAV=4,
  CODE_BOOK_6_LAV=4,
  CODE_BOOK_7_LAV=7,
  CODE_BOOK_8_LAV=7,
  CODE_BOOK_9_LAV=12,
  CODE_BOOK_10_LAV=12,
  CODE_BOOK_ESC_LAV=16,
  CODE_BOOK_SCF_LAV=60,
  CODE_BOOK_PNS_LAV=60
};

Word16 bitCount(const Word16 *aQuantSpectrum,
                const Word16  noOfSpecLines,
                Word16        maxVal,
                Word16       *bitCountLut);

Word16 codeValues(Word16 *values, Word16 width, Word16 codeBook, HANDLE_BIT_BUF hBitstream);

Word16 bitCountScalefactorDelta(Word16 delta);
Word16 codeScalefactorDelta(Word16 scalefactor, HANDLE_BIT_BUF hBitstream);



#endif
