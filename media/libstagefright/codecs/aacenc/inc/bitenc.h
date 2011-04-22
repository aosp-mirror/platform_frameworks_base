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
	File:		bitenc.h

	Content:	Bitstream encoder structure and functions

*******************************************************************************/

#ifndef _BITENC_H
#define _BITENC_H

#include "qc_data.h"
#include "tns.h"
#include "channel_map.h"
#include "interface.h"  

struct BITSTREAMENCODER_INIT
{
  Word16 nChannels;
  Word32 bitrate;
  Word32 sampleRate;
  Word16 profile;
};



Word16 WriteBitstream (HANDLE_BIT_BUF hBitstream,
                       ELEMENT_INFO elInfo,
                       QC_OUT *qcOut,
                       PSY_OUT *psyOut,
                       Word16 *globUsedBits,
                       const UWord8 *ancBytes,
					   Word16 samplerate
                       );

#endif /* _BITENC_H */
