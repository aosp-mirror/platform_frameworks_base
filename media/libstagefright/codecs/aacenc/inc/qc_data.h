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
	File:		qc_data.h

	Content:	Quantizing & coding structures

*******************************************************************************/

#ifndef _QC_DATA_H
#define _QC_DATA_H

#include "psy_const.h"
#include "dyn_bits.h"
#include "adj_thr_data.h"


#define MAX_MODES 10

typedef enum {
  MODE_INVALID = 0,
  MODE_1,        /* mono      */
  MODE_1_1,      /* dual mono */
  MODE_2         /* stereo    */
} ENCODER_MODE;

typedef enum {
  ID_SCE=0,     /* Single Channel Element   */
  ID_CPE=1,     /* Channel Pair Element     */
  ID_CCE=2,     /* Coupling Channel Element */
  ID_LFE=3,     /* LFE Channel Element      */
  ID_DSE=4,     /* current one DSE element for ancillary is supported */
  ID_PCE=5,
  ID_FIL=6,
  ID_END=7
}ELEMENT_TYPE;

typedef struct {
  ELEMENT_TYPE elType;
  Word16 instanceTag;
  Word16 nChannelsInEl;
  Word16 ChannelIndex[MAX_CHANNELS];
} ELEMENT_INFO;

typedef struct {
  Word32 paddingRest;
} PADDING;


/* Quantizing & coding stage */

struct QC_INIT{
  ELEMENT_INFO *elInfo;
  Word16 maxBits;     /* maximum number of bits in reservoir  */
  Word16 averageBits; /* average number of bits we should use */
  Word16 bitRes;
  Word16 meanPe;
  Word32 chBitrate;
  Word16 maxBitFac;
  Word32 bitrate;

  PADDING padding;
};

typedef struct
{
  Word16          *quantSpec;       /* [FRAME_LEN_LONG];                            */
  UWord16         *maxValueInSfb;   /* [MAX_GROUPED_SFB];                           */
  Word16          *scf;             /* [MAX_GROUPED_SFB];                           */
  Word16          globalGain;
  Word16          mdctScale;
  Word16          groupingMask;
  SECTION_DATA    sectionData;
  Word16          windowShape;
} QC_OUT_CHANNEL;

typedef struct
{
  Word16		  adtsUsed;
  Word16          staticBitsUsed; /* for verification purposes */
  Word16          dynBitsUsed;    /* for verification purposes */
  Word16          pe;
  Word16          ancBitsUsed;
  Word16          fillBits;
} QC_OUT_ELEMENT;

typedef struct
{
  QC_OUT_CHANNEL  qcChannel[MAX_CHANNELS];
  QC_OUT_ELEMENT  qcElement;
  Word16          totStaticBitsUsed; /* for verification purposes */
  Word16          totDynBitsUsed;    /* for verification purposes */
  Word16          totAncBitsUsed;    /* for verification purposes */
  Word16          totFillBits;
  Word16          alignBits;
  Word16          bitResTot;
  Word16          averageBitsTot;
} QC_OUT;

typedef struct {
  Word32 chBitrate;
  Word16 averageBits;               /* brutto -> look ancillary.h */
  Word16 maxBits;
  Word16 bitResLevel;
  Word16 maxBitResBits;
  Word16 relativeBits;            /* Bits relative to total Bits scaled down by 2 */
} ELEMENT_BITS;

typedef struct
{
  /* this is basically struct QC_INIT */
  Word16 averageBitsTot;
  Word16 maxBitsTot;
  Word16 globStatBits;
  Word16 nChannels;
  Word16 bitResTot;

  Word16 maxBitFac;

  PADDING   padding;

  ELEMENT_BITS  elementBits;
  ADJ_THR_STATE adjThr;

  Word16 logSfbFormFactor[MAX_CHANNELS][MAX_GROUPED_SFB];
  Word16 sfbNRelevantLines[MAX_CHANNELS][MAX_GROUPED_SFB];
  Word16 logSfbEnergy[MAX_CHANNELS][MAX_GROUPED_SFB];
} QC_STATE;

#endif /* _QC_DATA_H */
