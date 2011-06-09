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
	File:		ms_stereo.h

	Content:	Declaration MS stereo processing structure and functions

*******************************************************************************/

#ifndef __MS_STEREO_H__
#define __MS_STEREO_H__
#include "typedef.h"

void MsStereoProcessing(Word32       *sfbEnergyLeft,
                        Word32       *sfbEnergyRight,
                        const Word32 *sfbEnergyMid,
                        const Word32 *sfbEnergySide,
                        Word32       *mdctSpectrumLeft,
                        Word32       *mdctSpectrumRight,
                        Word32       *sfbThresholdLeft,
                        Word32       *sfbThresholdRight,
                        Word32       *sfbSpreadedEnLeft,
                        Word32       *sfbSpreadedEnRight,
                        Word16       *msDigest,
                        Word16       *msMask,
                        const Word16  sfbCnt,
                        const Word16  sfbPerGroup,
                        const Word16  maxSfbPerGroup,
                        const Word16 *sfbOffset);


#endif
