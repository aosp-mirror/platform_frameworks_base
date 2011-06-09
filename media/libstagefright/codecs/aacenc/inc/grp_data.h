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
	File:		grp_data.h

	Content:	Short block grouping function

*******************************************************************************/

#ifndef __GRP_DATA_H__
#define __GRP_DATA_H__
#include "psy_data.h"
#include "typedefs.h"

void
groupShortData(Word32        *mdctSpectrum,
               Word32        *tmpSpectrum,
               SFB_THRESHOLD *sfbThreshold,
               SFB_ENERGY    *sfbEnergy,
               SFB_ENERGY    *sfbEnergyMS,
               SFB_ENERGY    *sfbSpreadedEnergy,
               const Word16   sfbCnt,
               const Word16  *sfbOffset,
               const Word16  *sfbMinSnr,
               Word16        *groupedSfbOffset,
               Word16        *maxSfbPerGroup,
               Word16        *groupedSfbMinSnr,
               const Word16   noOfGroups,
               const Word16  *groupLen);

#endif /* _INTERFACE_H */
