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
	File:		interface.h

	Content:	psychoaccoustic/quantizer structures and interface

*******************************************************************************/

#ifndef _INTERFACE_H
#define _INTERFACE_H

#include "config.h"
#include "psy_const.h"
#include "psy_data.h"
#include "typedefs.h"


enum
{
  MS_NONE = 0,
  MS_SOME = 1,
  MS_ALL  = 2
};

enum
{
  MS_ON = 1
};

struct TOOLSINFO {
  Word16 msDigest;
  Word16 msMask[MAX_GROUPED_SFB];
};


typedef struct {
  Word16  sfbCnt;
  Word16  sfbPerGroup;
  Word16  maxSfbPerGroup;
  Word16  windowSequence;
  Word16  windowShape;
  Word16  groupingMask;
  Word16  sfbOffsets[MAX_GROUPED_SFB+1];
  Word16  mdctScale; 
  Word32 *sfbEnergy; 
  Word32 *sfbSpreadedEnergy;
  Word32 *sfbThreshold;       
  Word32 *mdctSpectrum;        
  Word32  sfbEnSumLR;
  Word32  sfbEnSumMS;
  Word32 sfbDist[MAX_GROUPED_SFB];
  Word32 sfbDistNew[MAX_GROUPED_SFB];
  Word16  sfbMinSnr[MAX_GROUPED_SFB];
  Word16 minSfMaxQuant[MAX_GROUPED_SFB];
  Word16 minScfCalculated[MAX_GROUPED_SFB];
  Word16 prevScfLast[MAX_GROUPED_SFB];
  Word16 prevScfNext[MAX_GROUPED_SFB];
  Word16 deltaPeLast[MAX_GROUPED_SFB];
  TNS_INFO tnsInfo;
} PSY_OUT_CHANNEL; /* Word16 size: 14 + 60(MAX_GROUPED_SFB) + 112(TNS_INFO) = 186 */

typedef struct {
  struct TOOLSINFO toolsInfo;
  Word16 groupedSfbOffset[MAX_CHANNELS][MAX_GROUPED_SFB+1];  /* plus one for last dummy offset ! */
  Word16 groupedSfbMinSnr[MAX_CHANNELS][MAX_GROUPED_SFB];
} PSY_OUT_ELEMENT;

typedef struct {
  /* information shared by both channels  */
  PSY_OUT_ELEMENT  psyOutElement;
  /* information specific to each channel */
  PSY_OUT_CHANNEL  psyOutChannel[MAX_CHANNELS];
}PSY_OUT;

void BuildInterface(Word32                 *mdctSpectrum,
                    const Word16            mdctScale,
                    SFB_THRESHOLD          *sfbThreshold,
                    SFB_ENERGY             *sfbEnergy,
                    SFB_ENERGY             *sfbSpreadedEnergy,
                    const SFB_ENERGY_SUM    sfbEnergySumLR,
                    const SFB_ENERGY_SUM    sfbEnergySumMS,
                    const Word16            windowSequence,
                    const Word16            windowShape,
                    const Word16            sfbCnt,
                    const Word16           *sfbOffset,
                    const Word16            maxSfbPerGroup,
                    const Word16           *groupedSfbMinSnr,
                    const Word16            noOfGroups,
                    const Word16           *groupLen,
                    PSY_OUT_CHANNEL        *psyOutCh);

#endif /* _INTERFACE_H */
