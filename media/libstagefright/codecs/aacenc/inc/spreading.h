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
	File:		spreading.h

	Content:	Spreading of energy functions

*******************************************************************************/

#ifndef _SPREADING_H
#define _SPREADING_H
#include "typedefs.h"


void SpreadingMax(const Word16 pbCnt,
                  const Word16 *maskLowFactor,
                  const Word16 *maskHighFactor,
                  Word32       *pbSpreadedEnergy);

#endif /* #ifndef _SPREADING_H */
