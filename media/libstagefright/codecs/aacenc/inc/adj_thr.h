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
	File:		adj_thr.h

	Content:	Threshold compensation function 

*******************************************************************************/

#ifndef __ADJ_THR_H
#define __ADJ_THR_H

#include "adj_thr_data.h"
#include "qc_data.h"
#include "interface.h"

Word16 bits2pe(const Word16 bits);

Word32 AdjThrNew(ADJ_THR_STATE** phAdjThr,
                 Word32 nElements);

void AdjThrDelete(ADJ_THR_STATE *hAdjThr);

void AdjThrInit(ADJ_THR_STATE *hAdjThr,
                const Word32 peMean,
                Word32 chBitrate);

void AdjustThresholds(ADJ_THR_STATE *adjThrState,
                      ATS_ELEMENT* AdjThrStateElement,
                      PSY_OUT_CHANNEL psyOutChannel[MAX_CHANNELS],
                      PSY_OUT_ELEMENT *psyOutElement,
                      Word16 *chBitDistribution,
                      Word16 logSfbEnergy[MAX_CHANNELS][MAX_GROUPED_SFB],
                      Word16 sfbNRelevantLines[MAX_CHANNELS][MAX_GROUPED_SFB],                      
                      QC_OUT_ELEMENT* qcOE,
					  ELEMENT_BITS* elBits,
					  const Word16 nChannels,
                      const Word16 maxBitFac);

void AdjThrUpdate(ATS_ELEMENT *AdjThrStateElement,
                  const Word16 dynBitsUsed);


#endif
