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
	File:		line_pe.h

	Content:	Perceptual entropie module structure and functions

*******************************************************************************/

#ifndef __LINE_PE_H
#define __LINE_PE_H


#include "psy_const.h"
#include "interface.h"


typedef struct {
   Word16 sfbLdEnergy[MAX_GROUPED_SFB];     /* 4*log(sfbEnergy)/log(2) */
   Word16 sfbNLines4[MAX_GROUPED_SFB];      /* 4*number of relevant lines in sfb */
   Word16 sfbPe[MAX_GROUPED_SFB];           /* pe for each sfb */
   Word16 sfbConstPart[MAX_GROUPED_SFB];    /* constant part for each sfb */
   Word16 sfbNActiveLines[MAX_GROUPED_SFB]; /* number of active lines in sfb */
   Word16 pe;                               /* sum of sfbPe */
   Word16 constPart;                        /* sum of sfbConstPart */
   Word16 nActiveLines;                     /* sum of sfbNActiveLines */
} PE_CHANNEL_DATA; /* size Word16: 303 */


typedef struct {
   PE_CHANNEL_DATA peChannelData[MAX_CHANNELS];
   Word16 pe;
   Word16 constPart;
   Word16 nActiveLines;
   Word16 offset;
   Word16 ahFlag[MAX_CHANNELS][MAX_GROUPED_SFB];
   Word32 thrExp[MAX_CHANNELS][MAX_GROUPED_SFB];
   Word32 sfbPeFactors[MAX_CHANNELS][MAX_GROUPED_SFB];
} PE_DATA; /* size Word16: 303 + 4 + 120 + 240 = 667 */




void prepareSfbPe(PE_DATA *peData,
                  PSY_OUT_CHANNEL  psyOutChannel[MAX_CHANNELS],
                  Word16 logSfbEnergy[MAX_CHANNELS][MAX_GROUPED_SFB],
                  Word16 sfbNRelevantLines[MAX_CHANNELS][MAX_GROUPED_SFB],
                  const Word16 nChannels,
                  const Word16 peOffset);





void calcSfbPe(PE_DATA *peData,
               PSY_OUT_CHANNEL psyOutChannel[MAX_CHANNELS],
               const Word16 nChannels);




#endif
