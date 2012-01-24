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
	File:		sf_estim.h

	Content:	Scale factor estimation functions

*******************************************************************************/

#ifndef __SF_ESTIM_H__
#define __SF_ESTIM_H__
/*
   Scale factor estimation
 */
#include "psy_const.h"
#include "interface.h"
#include "qc_data.h"

void
CalcFormFactor(Word16          logSfbFormFactor[MAX_CHANNELS][MAX_GROUPED_SFB],
               Word16          sfbNRelevantLines[MAX_CHANNELS][MAX_GROUPED_SFB],
               Word16          logSfbEnergy[MAX_CHANNELS][MAX_GROUPED_SFB],
               PSY_OUT_CHANNEL psyOutChannel[MAX_CHANNELS],
               const Word16    nChannels);

void
EstimateScaleFactors(PSY_OUT_CHANNEL psyOutChannel[MAX_CHANNELS],
                     QC_OUT_CHANNEL  qcOutChannel[MAX_CHANNELS],
                     Word16          logSfbEnergy[MAX_CHANNELS][MAX_GROUPED_SFB],
                     Word16          logSfbFormFactor[MAX_CHANNELS][MAX_GROUPED_SFB],
                     Word16          sfbNRelevantLines[MAX_CHANNELS][MAX_GROUPED_SFB],
                     const Word16    nChannels);
#endif
