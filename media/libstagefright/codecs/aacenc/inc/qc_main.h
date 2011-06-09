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
	File:		qc_main.h

	Content:	Quantizing & coding functions

*******************************************************************************/

#ifndef _QC_MAIN_H
#define _QC_MAIN_H

#include "qc_data.h"
#include "interface.h"
#include "memalign.h"

/* Quantizing & coding stage */

Word16 QCOutNew(QC_OUT *hQC, Word16 nChannels, VO_MEM_OPERATOR *pMemOP);

void QCOutDelete(QC_OUT *hQC, VO_MEM_OPERATOR *pMemOP);

Word16 QCNew(QC_STATE *hQC, VO_MEM_OPERATOR *pMemOP);

Word16 QCInit(QC_STATE *hQC, 
              struct QC_INIT *init);

void QCDelete(QC_STATE *hQC, VO_MEM_OPERATOR *pMemOP);


Word16 QCMain(QC_STATE *hQC,
              ELEMENT_BITS* elBits,
              ATS_ELEMENT* adjThrStateElement,
              PSY_OUT_CHANNEL psyOutChannel[MAX_CHANNELS], /* may be modified in-place */
              PSY_OUT_ELEMENT* psyOutElement,
              QC_OUT_CHANNEL  qcOutChannel[MAX_CHANNELS],   /* out                      */
              QC_OUT_ELEMENT* qcOutElement,
              Word16 nChannels,
			  Word16 ancillaryDataBytes);     /* returns error code       */

void updateBitres(QC_STATE* qcKernel,
                  QC_OUT* qcOut);

Word16 FinalizeBitConsumption(QC_STATE *hQC,
                              QC_OUT* qcOut);

Word16 AdjustBitrate(QC_STATE *hQC,
                     Word32 bitRate,
                     Word32 sampleRate);

#endif /* _QC_MAIN_H */
