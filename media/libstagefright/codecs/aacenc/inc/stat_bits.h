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
	File:		stat_bits.h

	Content:	Static bit counter functions

*******************************************************************************/

#ifndef __STAT_BITS_H
#define __STAT_BITS_H

#include "psy_const.h"
#include "interface.h"

Word16 countStaticBitdemand(PSY_OUT_CHANNEL psyOutChannel[MAX_CHANNELS],
                            PSY_OUT_ELEMENT *psyOutElement,
                            Word16 nChannels, 
							Word16 adtsUsed);

#endif /* __STAT_BITS_H */
