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
	File:		adj_thr_data.h

	Content:	Threshold compensation parameter 

*******************************************************************************/

#ifndef __ADJ_THR_DATA_H
#define __ADJ_THR_DATA_H

#include "typedef.h"
#include "psy_const.h"
#include "line_pe.h"

typedef struct {
   Word16 clipSaveLow, clipSaveHigh;
   Word16 minBitSave, maxBitSave;
   Word16 clipSpendLow, clipSpendHigh;
   Word16 minBitSpend, maxBitSpend;
} BRES_PARAM;

typedef struct {
   UWord8 modifyMinSnr;
   Word16 startSfbL, startSfbS;
} AH_PARAM;

typedef struct {
  Word32 maxRed;
  Word32 startRatio, maxRatio;
  Word32 redRatioFac;
  Word32 redOffs;
} MINSNR_ADAPT_PARAM;

typedef struct {
  /* parameters for bitreservoir control */
  Word16 peMin, peMax;
  /* constant offset to pe               */
  Word16  peOffset;
  /* avoid hole parameters               */
  AH_PARAM ahParam;
  /* paramters for adaptation of minSnr */
  MINSNR_ADAPT_PARAM minSnrAdaptParam;
  /* values for correction of pe */
  Word16 peLast;
  Word16 dynBitsLast;
  Word16 peCorrectionFactor;
} ATS_ELEMENT;

typedef struct {
  BRES_PARAM   bresParamLong, bresParamShort; /* Word16 size: 2*8 */
  ATS_ELEMENT  adjThrStateElem;               /* Word16 size: 19 */
} ADJ_THR_STATE;

#endif
