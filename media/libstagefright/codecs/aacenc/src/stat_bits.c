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
	File:		stat_bits.c

	Content:	Static bit counter functions

*******************************************************************************/

#include "stat_bits.h"
#include "bitenc.h"
#include "tns.h"


typedef enum {
  SI_ID_BITS                =(3),
  SI_FILL_COUNT_BITS        =(4),
  SI_FILL_ESC_COUNT_BITS    =(8),
  SI_FILL_EXTENTION_BITS    =(4),
  SI_FILL_NIBBLE_BITS       =(4),
  SI_SCE_BITS               =(4),
  SI_CPE_BITS               =(5),
  SI_CPE_MS_MASK_BITS       =(2) ,
  SI_ICS_INFO_BITS_LONG     =(1+2+1+6+1),
  SI_ICS_INFO_BITS_SHORT    =(1+2+1+4+7),
  SI_ICS_BITS               =(8+1+1+1)
} SI_BITS;


/*********************************************************************************
*
* function name: countMsMaskBits
* description:   count ms stereo bits demand
*
**********************************************************************************/
static Word16 countMsMaskBits(Word16   sfbCnt,
                              Word16   sfbPerGroup,
                              Word16   maxSfbPerGroup,
                              struct TOOLSINFO *toolsInfo)
{
  Word16 msBits, sfbOff, sfb;
  msBits = 0;


  switch(toolsInfo->msDigest) {
    case MS_NONE:
    case MS_ALL:
      break;

    case MS_SOME:
      for(sfbOff=0; sfbOff<sfbCnt; sfbOff+=sfbPerGroup)
        for(sfb=0; sfb<maxSfbPerGroup; sfb++)
          msBits += 1;
      break;
  }
  return(msBits);
}

/*********************************************************************************
*
* function name: tnsCount
* description:   count tns bit demand  core function
*
**********************************************************************************/
static Word16 tnsCount(TNS_INFO *tnsInfo, Word16 blockType)
{

  Word32 i, k;
  Flag tnsPresent;
  Word32 numOfWindows;
  Word32 count;
  Word32 coefBits;
  Word16 *ptcoef;

  count = 0;

  if (blockType == 2)
    numOfWindows = 8;
  else
    numOfWindows = 1;
  tnsPresent = 0;

  for (i=0; i<numOfWindows; i++) {

    if (tnsInfo->tnsActive[i]!=0) {
      tnsPresent = 1;
    }
  }

  if (tnsPresent) {
    /* there is data to be written*/
    /*count += 1; */
    for (i=0; i<numOfWindows; i++) {

      if (blockType == 2)
        count += 1;
      else
        count += 2;

      if (tnsInfo->tnsActive[i]) {
        count += 1;

        if (blockType == 2) {
          count += 4;
          count += 3;
        }
        else {
          count += 6;
          count += 5;
        }

        if (tnsInfo->order[i]) {
          count += 1; /*direction*/
          count += 1; /*coef_compression */

          if (tnsInfo->coefRes[i] == 4) {
            ptcoef = tnsInfo->coef + i*TNS_MAX_ORDER_SHORT;
			coefBits = 3;
            for(k=0; k<tnsInfo->order[i]; k++) {

              if ((ptcoef[k] > 3) || (ptcoef[k] < -4)) {
                coefBits = 4;
                break;
              }
            }
          }
          else {
            coefBits = 2;
            ptcoef = tnsInfo->coef + i*TNS_MAX_ORDER_SHORT;
			for(k=0; k<tnsInfo->order[i]; k++) {

              if ((ptcoef[k] > 1) || (ptcoef[k] < -2)) {
                coefBits = 3;
                break;
              }
            }
          }
          for (k=0; k<tnsInfo->order[i]; k++ ) {
            count += coefBits;
          }
        }
      }
    }
  }

  return count;
}

/**********************************************************************************
*
* function name: countTnsBits
* description:   count tns bit demand
*
**********************************************************************************/
static Word16 countTnsBits(TNS_INFO *tnsInfo,Word16 blockType)
{
  return(tnsCount(tnsInfo, blockType));
}

/*********************************************************************************
*
* function name: countStaticBitdemand
* description:   count static bit demand include tns
*
**********************************************************************************/
Word16 countStaticBitdemand(PSY_OUT_CHANNEL psyOutChannel[MAX_CHANNELS],
                            PSY_OUT_ELEMENT *psyOutElement,
                            Word16 channels,
							Word16 adtsUsed)
{
  Word32 statBits;
  Word32 ch;

  statBits = 0;

  /* if adts used, add 56 bits */
  if(adtsUsed) statBits += 56;


  switch (channels) {
    case 1:
      statBits += SI_ID_BITS+SI_SCE_BITS+SI_ICS_BITS;
      statBits += countTnsBits(&(psyOutChannel[0].tnsInfo),
                               psyOutChannel[0].windowSequence);

      switch(psyOutChannel[0].windowSequence){
        case LONG_WINDOW:
        case START_WINDOW:
        case STOP_WINDOW:
          statBits += SI_ICS_INFO_BITS_LONG;
          break;
        case SHORT_WINDOW:
          statBits += SI_ICS_INFO_BITS_SHORT;
          break;
      }
      break;
    case 2:
      statBits += SI_ID_BITS+SI_CPE_BITS+2*SI_ICS_BITS;

      statBits += SI_CPE_MS_MASK_BITS;
      statBits += countMsMaskBits(psyOutChannel[0].sfbCnt,
								  psyOutChannel[0].sfbPerGroup,
								  psyOutChannel[0].maxSfbPerGroup,
								  &psyOutElement->toolsInfo);

      switch (psyOutChannel[0].windowSequence) {
        case LONG_WINDOW:
        case START_WINDOW:
        case STOP_WINDOW:
          statBits += SI_ICS_INFO_BITS_LONG;
          break;
        case SHORT_WINDOW:
          statBits += SI_ICS_INFO_BITS_SHORT;
          break;
      }
      for(ch=0; ch<2; ch++)
        statBits += countTnsBits(&(psyOutChannel[ch].tnsInfo),
                                 psyOutChannel[ch].windowSequence);
      break;
  }

  return statBits;
}

