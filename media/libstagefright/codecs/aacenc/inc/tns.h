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
	File:		tns.h

	Content:	TNS structures

*******************************************************************************/

#ifndef _TNS_H
#define _TNS_H

#include "typedef.h"
#include "psy_const.h"



#define TNS_MAX_ORDER 12
#define TNS_MAX_ORDER_SHORT 5

#define FILTER_DIRECTION    0

typedef struct{ /*stuff that is tabulated dependent on bitrate etc. */
  Word16     threshOn;                /* min. prediction gain for using tns TABUL * 100*/
  Word32     lpcStartFreq;            /* lowest freq for lpc TABUL*/
  Word32     lpcStopFreq;             /* TABUL */
  Word32     tnsTimeResolution;
}TNS_CONFIG_TABULATED;


typedef struct {   /*assigned at InitTime*/
  Word16 tnsActive;
  Word16 tnsMaxSfb;

  Word16 maxOrder;                /* max. order of tns filter */
  Word16 tnsStartFreq;            /* lowest freq. for tns filtering */
  Word16 coefRes;

  TNS_CONFIG_TABULATED confTab;

  Word32 acfWindow[TNS_MAX_ORDER+1];

  Word16 tnsStartBand;
  Word16 tnsStartLine;

  Word16 tnsStopBand;
  Word16 tnsStopLine;

  Word16 lpcStartBand;
  Word16 lpcStartLine;

  Word16 lpcStopBand;
  Word16 lpcStopLine;

  Word16 tnsRatioPatchLowestCb;
  Word16 tnsModifyBeginCb;

  Word16 threshold; /* min. prediction gain for using tns TABUL * 100 */

}TNS_CONFIG;


typedef struct {
  Word16 tnsActive;
  Word32 parcor[TNS_MAX_ORDER];
  Word16 predictionGain;
} TNS_SUBBLOCK_INFO; /* Word16 size: 26 */

typedef struct{
  TNS_SUBBLOCK_INFO subBlockInfo[TRANS_FAC];
} TNS_DATA_SHORT;

typedef struct{
  TNS_SUBBLOCK_INFO subBlockInfo;
} TNS_DATA_LONG;

typedef struct{
  TNS_DATA_LONG tnsLong;
  TNS_DATA_SHORT tnsShort;
}TNS_DATA_RAW;

typedef struct{
  Word16 numOfSubblocks;
  TNS_DATA_RAW dataRaw;
}TNS_DATA; /* Word16 size: 1 + 8*26 + 26 = 235 */

typedef struct{
  Word16 tnsActive[TRANS_FAC];
  Word16 coefRes[TRANS_FAC];
  Word16 length[TRANS_FAC];
  Word16 order[TRANS_FAC];
  Word16 coef[TRANS_FAC*TNS_MAX_ORDER_SHORT];
}TNS_INFO; /* Word16 size: 72 */

#endif /* _TNS_H */
