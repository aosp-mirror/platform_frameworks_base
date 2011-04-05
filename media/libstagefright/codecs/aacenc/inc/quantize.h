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
	File:		quantize.h

	Content:	Quantization functions

*******************************************************************************/

#ifndef _QUANTIZE_H_
#define _QUANTIZE_H_
#include "typedefs.h"

/* quantizing */

#define MAX_QUANT 8191

void QuantizeSpectrum(Word16 sfbCnt,
                      Word16 maxSfbPerGroup,
                      Word16 sfbPerGroup,
                      Word16 *sfbOffset, Word32 *mdctSpectrum,
                      Word16 globalGain, Word16 *scalefactors,
                      Word16 *quantizedSpectrum);

Word32 calcSfbDist(const Word32 *spec,
                   Word16  sfbWidth,
                   Word16  gain);

#endif /* _QUANTIZE_H_ */
