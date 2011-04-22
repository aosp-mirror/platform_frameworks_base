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
	File:		tns_func.h

	Content:	TNS functions

*******************************************************************************/

/*
   Temporal noise shaping
 */
#ifndef _TNS_FUNC_H
#define _TNS_FUNC_H
#include "typedef.h"
#include "psy_configuration.h"

Word16 InitTnsConfigurationLong(Word32 bitrate,
                                Word32 samplerate,
                                Word16 channels,
                                TNS_CONFIG *tnsConfig,
                                PSY_CONFIGURATION_LONG *psyConfig,
                                Word16 active);

Word16 InitTnsConfigurationShort(Word32 bitrate,
                                 Word32 samplerate,
                                 Word16 channels,
                                 TNS_CONFIG *tnsConfig,
                                 PSY_CONFIGURATION_SHORT *psyConfig,
                                 Word16 active);

Word32 TnsDetect(TNS_DATA* tnsData,
                 TNS_CONFIG tC,
                 Word32* pScratchTns,
                 const Word16 sfbOffset[],
                 Word32* spectrum,
                 Word16 subBlockNumber,
                 Word16 blockType,
                 Word32 * sfbEnergy);

void TnsSync(TNS_DATA *tnsDataDest,
             const TNS_DATA *tnsDataSrc,
             const TNS_CONFIG tC,
             const Word16 subBlockNumber,
             const Word16 blockType);

Word16 TnsEncode(TNS_INFO* tnsInfo,
                 TNS_DATA* tnsData,
                 Word16 numOfSfb,
                 TNS_CONFIG tC,
                 Word16 lowPassLine,
                 Word32* spectrum,
                 Word16 subBlockNumber,
                 Word16 blockType);

void ApplyTnsMultTableToRatios(Word16 startCb,
                               Word16 stopCb,
                               TNS_SUBBLOCK_INFO subInfo,
                               Word32 *thresholds);


#endif /* _TNS_FUNC_H */
