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
	File:		dyn_bits.c

	Content:	Noiseless coder module functions

*******************************************************************************/

#include "aac_rom.h"
#include "dyn_bits.h"
#include "bit_cnt.h"
#include "psy_const.h"


/*****************************************************************************
*
* function name: buildBitLookUp
* description:  count bits using all possible tables
*
*****************************************************************************/
static void
buildBitLookUp(const Word16 *quantSpectrum,
               const Word16 maxSfb,
               const Word16 *sfbOffset,
               const UWord16 *sfbMax,
               Word16 bitLookUp[MAX_SFB_LONG][CODE_BOOK_ESC_NDX + 1],
               SECTION_INFO * sectionInfo)
{
  Word32 i;

  for (i=0; i<maxSfb; i++) {
    Word16 sfbWidth, maxVal;

    sectionInfo[i].sfbCnt = 1;
    sectionInfo[i].sfbStart = i;
    sectionInfo[i].sectionBits = INVALID_BITCOUNT;
    sectionInfo[i].codeBook = -1;
    sfbWidth = sfbOffset[i + 1] - sfbOffset[i];
    maxVal = sfbMax[i];
    bitCount(quantSpectrum + sfbOffset[i], sfbWidth, maxVal, bitLookUp[i]);
  }
}


/*****************************************************************************
*
* function name: findBestBook
* description:  essential helper functions
*
*****************************************************************************/
static Word16
findBestBook(const Word16 *bc, Word16 *book)
{
  Word32 minBits, j;
  minBits = INVALID_BITCOUNT;

  for (j=0; j<=CODE_BOOK_ESC_NDX; j++) {

    if (bc[j] < minBits) {
      minBits = bc[j];
      *book = j;
    }
  }
  return extract_l(minBits);
}

static Word16
findMinMergeBits(const Word16 *bc1, const Word16 *bc2)
{
  Word32 minBits, j, sum;
  minBits = INVALID_BITCOUNT;

  for (j=0; j<=CODE_BOOK_ESC_NDX; j++) {
    sum = bc1[j] + bc2[j];
    if (sum < minBits) {
      minBits = sum;
    }
  }
  return extract_l(minBits);
}

static void
mergeBitLookUp(Word16 *bc1, const Word16 *bc2)
{
  Word32 j;

  for (j=0; j<=CODE_BOOK_ESC_NDX; j++) {
    bc1[j] = min(bc1[j] + bc2[j], INVALID_BITCOUNT);
  }
}

static Word16
findMaxMerge(const Word16 mergeGainLookUp[MAX_SFB_LONG],
             const SECTION_INFO *sectionInfo,
             const Word16 maxSfb, Word16 *maxNdx)
{
  Word32 i, maxMergeGain;
  maxMergeGain = 0;

  for (i=0; i+sectionInfo[i].sfbCnt < maxSfb; i += sectionInfo[i].sfbCnt) {

    if (mergeGainLookUp[i] > maxMergeGain) {
      maxMergeGain = mergeGainLookUp[i];
      *maxNdx = i;
    }
  }
  return extract_l(maxMergeGain);
}



static Word16
CalcMergeGain(const SECTION_INFO *sectionInfo,
              Word16 bitLookUp[MAX_SFB_LONG][CODE_BOOK_ESC_NDX + 1],
              const Word16 *sideInfoTab,
              const Word16 ndx1,
              const Word16 ndx2)
{
  Word32 SplitBits;
  Word32 MergeBits;
  Word32 MergeGain;

  /*
    Bit amount for splitted sections
  */
  SplitBits = sectionInfo[ndx1].sectionBits + sectionInfo[ndx2].sectionBits;

  MergeBits = sideInfoTab[sectionInfo[ndx1].sfbCnt + sectionInfo[ndx2].sfbCnt] +
                  findMinMergeBits(bitLookUp[ndx1], bitLookUp[ndx2]);
  MergeGain = (SplitBits - MergeBits);

  return extract_l(MergeGain);
}

/*
  sectioning Stage 0:find minimum codbooks
*/

static void
gmStage0(SECTION_INFO * sectionInfo,
         Word16 bitLookUp[MAX_SFB_LONG][CODE_BOOK_ESC_NDX + 1],
         const Word16 maxSfb)
{
  Word32 i;

  for (i=0; i<maxSfb; i++) {
    /* Side-Info bits will be calculated in Stage 1!  */

    if (sectionInfo[i].sectionBits == INVALID_BITCOUNT) {
      sectionInfo[i].sectionBits = findBestBook(bitLookUp[i], &(sectionInfo[i].codeBook));
    }
  }
}

/*
  sectioning Stage 1:merge all connected regions with the same code book and
  calculate side info
*/

static void
gmStage1(SECTION_INFO * sectionInfo,
         Word16 bitLookUp[MAX_SFB_LONG][CODE_BOOK_ESC_NDX + 1],
         const Word16 maxSfb,
         const Word16 *sideInfoTab)
{
  SECTION_INFO * sectionInfo_s;
  SECTION_INFO * sectionInfo_e;
  Word32 mergeStart, mergeEnd;
  mergeStart = 0;

  do {

    sectionInfo_s = sectionInfo + mergeStart;
	for (mergeEnd=mergeStart+1; mergeEnd<maxSfb; mergeEnd++) {
      sectionInfo_e = sectionInfo + mergeEnd;
      if (sectionInfo_s->codeBook != sectionInfo_e->codeBook)
        break;
      sectionInfo_s->sfbCnt += 1;
      sectionInfo_s->sectionBits += sectionInfo_e->sectionBits;

      mergeBitLookUp(bitLookUp[mergeStart], bitLookUp[mergeEnd]);
    }

    sectionInfo_s->sectionBits += sideInfoTab[sectionInfo_s->sfbCnt];
    sectionInfo[mergeEnd - 1].sfbStart = sectionInfo_s->sfbStart;      /* speed up prev search */

    mergeStart = mergeEnd;


  } while (mergeStart - maxSfb < 0);
}

/*
  sectioning Stage 2:greedy merge algorithm, merge connected sections with
  maximum bit gain until no more gain is possible
*/
static void
gmStage2(SECTION_INFO *sectionInfo,
         Word16 mergeGainLookUp[MAX_SFB_LONG],
         Word16 bitLookUp[MAX_SFB_LONG][CODE_BOOK_ESC_NDX + 1],
         const Word16 maxSfb,
         const Word16 *sideInfoTab)
{
  Word16 i;

  for (i=0; i+sectionInfo[i].sfbCnt<maxSfb; i+=sectionInfo[i].sfbCnt) {
    mergeGainLookUp[i] = CalcMergeGain(sectionInfo,
                                       bitLookUp,
                                       sideInfoTab,
                                       i,
                                       (i + sectionInfo[i].sfbCnt));
  }

  while (TRUE) {
    Word16 maxMergeGain, maxNdx, maxNdxNext, maxNdxLast;

    maxMergeGain = findMaxMerge(mergeGainLookUp, sectionInfo, maxSfb, &maxNdx);


    if (maxMergeGain <= 0)
      break;


    maxNdxNext = maxNdx + sectionInfo[maxNdx].sfbCnt;

    sectionInfo[maxNdx].sfbCnt = sectionInfo[maxNdx].sfbCnt + sectionInfo[maxNdxNext].sfbCnt;
    sectionInfo[maxNdx].sectionBits = sectionInfo[maxNdx].sectionBits +
                                          (sectionInfo[maxNdxNext].sectionBits - maxMergeGain);


    mergeBitLookUp(bitLookUp[maxNdx], bitLookUp[maxNdxNext]);


    if (maxNdx != 0) {
      maxNdxLast = sectionInfo[maxNdx - 1].sfbStart;
      mergeGainLookUp[maxNdxLast] = CalcMergeGain(sectionInfo,
                                                  bitLookUp,
                                                  sideInfoTab,
                                                  maxNdxLast,
                                                  maxNdx);
    }
    maxNdxNext = maxNdx + sectionInfo[maxNdx].sfbCnt;

    sectionInfo[maxNdxNext - 1].sfbStart = sectionInfo[maxNdx].sfbStart;


    if (maxNdxNext - maxSfb < 0) {
      mergeGainLookUp[maxNdx] = CalcMergeGain(sectionInfo,
                                              bitLookUp,
                                              sideInfoTab,
                                              maxNdx,
                                              maxNdxNext);
    }
  }
}

/*
  count bits used by the noiseless coder
*/
static void
noiselessCounter(SECTION_DATA *sectionData,
                 Word16 mergeGainLookUp[MAX_SFB_LONG],
                 Word16 bitLookUp[MAX_SFB_LONG][CODE_BOOK_ESC_NDX + 1],
                 const Word16 *quantSpectrum,
                 const UWord16 *maxValueInSfb,
                 const Word16 *sfbOffset,
                 const Word32 blockType)
{
  Word32 grpNdx, i;
  Word16 *sideInfoTab = NULL;
  SECTION_INFO *sectionInfo;

  /*
    use appropriate side info table
  */
  switch (blockType)
  {
    case LONG_WINDOW:
    case START_WINDOW:
    case STOP_WINDOW:
      sideInfoTab = sideInfoTabLong;
      break;
    case SHORT_WINDOW:
      sideInfoTab = sideInfoTabShort;
      break;
  }


  sectionData->noOfSections = 0;
  sectionData->huffmanBits = 0;
  sectionData->sideInfoBits = 0;


  if (sectionData->maxSfbPerGroup == 0)
    return;

  /*
    loop trough groups
  */
  for (grpNdx=0; grpNdx<sectionData->sfbCnt; grpNdx+=sectionData->sfbPerGroup) {

    sectionInfo = sectionData->sectionInfo + sectionData->noOfSections;

    buildBitLookUp(quantSpectrum,
                   sectionData->maxSfbPerGroup,
                   sfbOffset + grpNdx,
                   maxValueInSfb + grpNdx,
                   bitLookUp,
                   sectionInfo);

    /*
       0.Stage
    */
    gmStage0(sectionInfo, bitLookUp, sectionData->maxSfbPerGroup);

    /*
       1.Stage
    */
    gmStage1(sectionInfo, bitLookUp, sectionData->maxSfbPerGroup, sideInfoTab);


    /*
       2.Stage
    */
    gmStage2(sectionInfo,
             mergeGainLookUp,
             bitLookUp,
             sectionData->maxSfbPerGroup,
             sideInfoTab);


    /*
       compress output, calculate total huff and side bits
    */
    for (i=0; i<sectionData->maxSfbPerGroup; i+=sectionInfo[i].sfbCnt) {
      findBestBook(bitLookUp[i], &(sectionInfo[i].codeBook));
      sectionInfo[i].sfbStart = sectionInfo[i].sfbStart + grpNdx;

      sectionData->huffmanBits = (sectionData->huffmanBits +
                                     (sectionInfo[i].sectionBits - sideInfoTab[sectionInfo[i].sfbCnt]));
      sectionData->sideInfoBits = (sectionData->sideInfoBits + sideInfoTab[sectionInfo[i].sfbCnt]);
      sectionData->sectionInfo[sectionData->noOfSections] = sectionInfo[i];
      sectionData->noOfSections = sectionData->noOfSections + 1;
    }
  }
}


/*******************************************************************************
*
* functionname: scfCount
* returns     : ---
* description : count bits used by scalefactors.
*
********************************************************************************/
static void scfCount(const Word16 *scalefacGain,
                     const UWord16 *maxValueInSfb,
                     SECTION_DATA * sectionData)

{
  SECTION_INFO *psectionInfo;
  SECTION_INFO *psectionInfom;

  /* counter */
  Word32 i = 0; /* section counter */
  Word32 j = 0; /* sfb counter */
  Word32 k = 0; /* current section auxiliary counter */
  Word32 m = 0; /* other section auxiliary counter */
  Word32 n = 0; /* other sfb auxiliary counter */

  /* further variables */
  Word32 lastValScf     = 0;
  Word32 deltaScf       = 0;
  Flag found            = 0;
  Word32 scfSkipCounter = 0;


  sectionData->scalefacBits = 0;


  if (scalefacGain == NULL) {
    return;
  }

  lastValScf = 0;
  sectionData->firstScf = 0;

  psectionInfo = sectionData->sectionInfo;
  for (i=0; i<sectionData->noOfSections; i++) {

    if (psectionInfo->codeBook != CODE_BOOK_ZERO_NO) {
      sectionData->firstScf = psectionInfo->sfbStart;
      lastValScf = scalefacGain[sectionData->firstScf];
      break;
    }
	psectionInfo += 1;
  }

  psectionInfo = sectionData->sectionInfo;
  for (i=0; i<sectionData->noOfSections; i++, psectionInfo += 1) {

    if (psectionInfo->codeBook != CODE_BOOK_ZERO_NO
        && psectionInfo->codeBook != CODE_BOOK_PNS_NO) {
      for (j = psectionInfo->sfbStart;
           j < (psectionInfo->sfbStart + psectionInfo->sfbCnt); j++) {
        /* check if we can repeat the last value to save bits */

        if (maxValueInSfb[j] == 0) {
          found = 0;

          if (scfSkipCounter == 0) {
            /* end of section */

            if (j - ((psectionInfo->sfbStart + psectionInfo->sfbCnt) - 1) == 0) {
              found = 0;
            }
            else {
              for (k = j + 1; k < psectionInfo->sfbStart + psectionInfo->sfbCnt; k++) {

                if (maxValueInSfb[k] != 0) {
                  int tmp = L_abs(scalefacGain[k] - lastValScf);
				  found = 1;

                  if ( tmp < CODE_BOOK_SCF_LAV) {
                    /* save bits */
                    deltaScf = 0;
                  }
                  else {
                    /* do not save bits */
                    deltaScf = lastValScf - scalefacGain[j];
                    lastValScf = scalefacGain[j];
                    scfSkipCounter = 0;
                  }
                  break;
                }
                /* count scalefactor skip */
                scfSkipCounter = scfSkipCounter + 1;
              }
            }

			psectionInfom = psectionInfo + 1;
            /* search for the next maxValueInSfb[] != 0 in all other sections */
            for (m = i + 1; (m < sectionData->noOfSections) && (found == 0); m++) {

              if ((psectionInfom->codeBook != CODE_BOOK_ZERO_NO) &&
                  (psectionInfom->codeBook != CODE_BOOK_PNS_NO)) {
                for (n = psectionInfom->sfbStart;
                     n < (psectionInfom->sfbStart + psectionInfom->sfbCnt); n++) {

                  if (maxValueInSfb[n] != 0) {
                    found = 1;

                    if ( (abs_s(scalefacGain[n] - lastValScf) < CODE_BOOK_SCF_LAV)) {
                      deltaScf = 0;
                    }
                    else {
                      deltaScf = (lastValScf - scalefacGain[j]);
                      lastValScf = scalefacGain[j];
                      scfSkipCounter = 0;
                    }
                    break;
                  }
                  /* count scalefactor skip */
                  scfSkipCounter = scfSkipCounter + 1;
                }
              }

			  psectionInfom += 1;
            }

            if (found == 0) {
              deltaScf = 0;
              scfSkipCounter = 0;
            }
          }
          else {
            deltaScf = 0;
            scfSkipCounter = scfSkipCounter - 1;
          }
        }
        else {
          deltaScf = lastValScf - scalefacGain[j];
          lastValScf = scalefacGain[j];
        }
        sectionData->scalefacBits += bitCountScalefactorDelta(deltaScf);
      }
    }
  }
}


typedef Word16 (*lookUpTable)[CODE_BOOK_ESC_NDX + 1];


Word16
dynBitCount(const Word16  *quantSpectrum,
            const UWord16 *maxValueInSfb,
            const Word16  *scalefac,
            const Word16   blockType,
            const Word16   sfbCnt,
            const Word16   maxSfbPerGroup,
            const Word16   sfbPerGroup,
            const Word16  *sfbOffset,
            SECTION_DATA  *sectionData)
{
  sectionData->blockType      = blockType;
  sectionData->sfbCnt         = sfbCnt;
  sectionData->sfbPerGroup    = sfbPerGroup;
  if(sfbPerGroup)
	sectionData->noOfGroups   = sfbCnt/sfbPerGroup;
  else
	sectionData->noOfGroups   = 0x7fff;
  sectionData->maxSfbPerGroup = maxSfbPerGroup;

  noiselessCounter(sectionData,
                   sectionData->mergeGainLookUp,
                   (lookUpTable)sectionData->bitLookUp,
                   quantSpectrum,
                   maxValueInSfb,
                   sfbOffset,
                   blockType);

  scfCount(scalefac,
           maxValueInSfb,
           sectionData);


  return (sectionData->huffmanBits + sectionData->sideInfoBits +
	      sectionData->scalefacBits);
}

