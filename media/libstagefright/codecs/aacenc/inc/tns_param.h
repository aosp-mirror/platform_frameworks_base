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
	File:		tns_param.h

	Content:	TNS parameters

*******************************************************************************/

/*
   TNS parameters
 */
#ifndef _TNS_PARAM_H
#define _TNS_PARAM_H

#include "tns.h"

typedef struct{
  Word32 samplingRate;
  Word16 maxBandLong;
  Word16 maxBandShort;
}TNS_MAX_TAB_ENTRY;

typedef struct{
    Word32 bitRateFrom;
    Word32 bitRateTo;
    const TNS_CONFIG_TABULATED *paramMono_Long;  /* contains TNS parameters */
    const TNS_CONFIG_TABULATED *paramMono_Short;
    const TNS_CONFIG_TABULATED *paramStereo_Long;
    const TNS_CONFIG_TABULATED *paramStereo_Short;
}TNS_INFO_TAB;


void GetTnsParam(TNS_CONFIG_TABULATED *tnsConfigTab, 
                 Word32 bitRate, Word16 channels, Word16 blockType);

void GetTnsMaxBands(Word32 samplingRate, Word16 blockType, Word16* tnsMaxSfb);

#endif /* _TNS_PARAM_H */
