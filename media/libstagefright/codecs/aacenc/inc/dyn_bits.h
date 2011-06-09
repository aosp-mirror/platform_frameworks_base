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
	File:		dyn_bits.h

	Content:	Noiseless coder module structure and functions

*******************************************************************************/

#ifndef __DYN_BITS_H
#define __DYN_BITS_H

#include "psy_const.h"
#include "tns.h"
#include "bit_cnt.h"



#define MAX_SECTIONS          MAX_GROUPED_SFB
#define SECT_ESC_VAL_LONG    31
#define SECT_ESC_VAL_SHORT    7
#define CODE_BOOK_BITS        4
#define SECT_BITS_LONG        5
#define SECT_BITS_SHORT       3

typedef struct
{
  Word16 codeBook;
  Word16 sfbStart;
  Word16 sfbCnt;
  Word16 sectionBits;
}
SECTION_INFO;




typedef struct
{
  Word16 blockType;
  Word16 noOfGroups;
  Word16 sfbCnt;
  Word16 maxSfbPerGroup;
  Word16 sfbPerGroup;
  Word16 noOfSections;
  SECTION_INFO sectionInfo[MAX_SECTIONS];
  Word16 sideInfoBits;             /* sectioning bits       */
  Word16 huffmanBits;              /* huffman    coded bits */
  Word16 scalefacBits;             /* scalefac   coded bits */
  Word16 firstScf;                 /* first scf to be coded */
  Word16 bitLookUp[MAX_SFB_LONG*(CODE_BOOK_ESC_NDX+1)];
  Word16 mergeGainLookUp[MAX_SFB_LONG];
}
SECTION_DATA; /*  Word16 size: 10 + 60(MAX_SECTIONS)*4(SECTION_INFO) + 51(MAX_SFB_LONG)*12(CODE_BOOK_ESC_NDX+1) + 51(MAX_SFB_LONG) = 913 */


Word16 BCInit(void);

Word16 dynBitCount(const Word16 *quantSpectrum,
                   const UWord16 *maxValueInSfb,
                   const Word16 *scalefac,
                   const Word16 blockType,
                   const Word16 sfbCnt,
                   const Word16 maxSfbPerGroup,
                   const Word16 sfbPerGroup,
                   const Word16 *sfbOffset,
                   SECTION_DATA *sectionData);

#endif
