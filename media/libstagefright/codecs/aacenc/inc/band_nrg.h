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
	File:		band_nrg.h

	Content:	Band/Line energy calculations functions

*******************************************************************************/


#ifndef _BAND_NRG_H
#define _BAND_NRG_H

#include "typedef.h"


void CalcBandEnergy(const Word32 *mdctSpectrum,
                    const Word16 *bandOffset,
                    const Word16  numBands,
                    Word32       *bandEnergy,
                    Word32       *bandEnergySum);


void CalcBandEnergyMS(const Word32 *mdctSpectrumLeft,
                      const Word32 *mdctSpectrumRight,
                      const Word16 *bandOffset,
                      const Word16  numBands,
                      Word32       *bandEnergyMid,
                      Word32       *bandEnergyMidSum,
                      Word32       *bandEnergySide,
                      Word32       *bandEnergySideSum);

#endif
