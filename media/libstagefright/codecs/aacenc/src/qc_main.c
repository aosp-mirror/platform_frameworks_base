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
	File:		qc_main.c

	Content:	Quantizing & coding functions

*******************************************************************************/

#include "basic_op.h"
#include "oper_32b.h"
#include "qc_main.h"
#include "quantize.h"
#include "interface.h"
#include "adj_thr.h"
#include "sf_estim.h"
#include "stat_bits.h"
#include "bit_cnt.h"
#include "dyn_bits.h"
#include "channel_map.h"
#include "memalign.h"


typedef enum{
  FRAME_LEN_BYTES_MODULO =  1,
  FRAME_LEN_BYTES_INT    =  2
}FRAME_LEN_RESULT_MODE;

static const Word16 maxFillElemBits = 7 + 270*8;

/* forward declarations */

static Word16 calcMaxValueInSfb(Word16 sfbCnt,
                                Word16 maxSfbPerGroup,
                                Word16 sfbPerGroup,
                                Word16 sfbOffset[MAX_GROUPED_SFB],
                                Word16 quantSpectrum[FRAME_LEN_LONG],
                                UWord16 maxValue[MAX_GROUPED_SFB]);


/*****************************************************************************
*
* function name: calcFrameLen
* description: estimate the frame length according the bitrates
*
*****************************************************************************/
static Word16 calcFrameLen(Word32 bitRate,
                           Word32 sampleRate,
                           FRAME_LEN_RESULT_MODE mode)
{

  Word32 result;
  Word32 quot;

  result = (FRAME_LEN_LONG >> 3) * bitRate;
  quot = result / sampleRate;

   
  if (mode == FRAME_LEN_BYTES_MODULO) {
    result -= quot * sampleRate;
  }
  else { /* FRAME_LEN_BYTES_INT */
    result = quot;                                      
  }

  return result;
}

/*****************************************************************************
*
*  function name:framePadding
*  description: Calculates if padding is needed for actual frame
*  returns: paddingOn or not 
*
*****************************************************************************/
static Word16 framePadding(Word32 bitRate,
                           Word32 sampleRate,
                           Word32 *paddingRest)
{
  Word16 paddingOn;
  Word16 difference;

  paddingOn = 0;                                                 

  difference = calcFrameLen( bitRate,
                             sampleRate,
                             FRAME_LEN_BYTES_MODULO );
  *paddingRest = *paddingRest - difference;

   
  if (*paddingRest <= 0 ) {
    paddingOn = 1;                                               
    *paddingRest = *paddingRest + sampleRate;
  }

  return paddingOn;
}


/*********************************************************************************
*
* function name: QCOutNew
* description: init qcout parameter
* returns:     0 if success
*
**********************************************************************************/

Word16 QCOutNew(QC_OUT *hQC, Word16 nChannels, VO_MEM_OPERATOR *pMemOP)
{
  Word32 i;
  Word16 *quantSpec;
  Word16 *scf;
  UWord16 *maxValueInSfb;	
	
  quantSpec = (Word16 *)mem_malloc(pMemOP, nChannels * FRAME_LEN_LONG * sizeof(Word16), 32, VO_INDEX_ENC_AAC);
  if(NULL == quantSpec)
	  return 1;
  scf = (Word16 *)mem_malloc(pMemOP, nChannels * MAX_GROUPED_SFB * sizeof(Word16), 32, VO_INDEX_ENC_AAC);     
  if(NULL == scf)
  {
	  return 1;
  }
  maxValueInSfb = (UWord16 *)mem_malloc(pMemOP, nChannels * MAX_GROUPED_SFB * sizeof(UWord16), 32, VO_INDEX_ENC_AAC);
  if(NULL == maxValueInSfb)
  {
	  return 1;
  }

  for (i=0; i<nChannels; i++) {
    hQC->qcChannel[i].quantSpec = quantSpec + i*FRAME_LEN_LONG;
    
    hQC->qcChannel[i].maxValueInSfb = maxValueInSfb + i*MAX_GROUPED_SFB;
    
    hQC->qcChannel[i].scf = scf + i*MAX_GROUPED_SFB;
  }
 
  return 0;
}


/*********************************************************************************
*
* function name: QCOutDelete
* description: unint qcout parameter
* returns:      0 if success
*
**********************************************************************************/
void QCOutDelete(QC_OUT* hQC, VO_MEM_OPERATOR *pMemOP)
{
   Word32 i;
   if(hQC)
   {
      if(hQC->qcChannel[0].quantSpec);
		 mem_free(pMemOP, hQC->qcChannel[0].quantSpec, VO_INDEX_ENC_AAC);
    
      if(hQC->qcChannel[0].maxValueInSfb)
		  mem_free(pMemOP, hQC->qcChannel[0].maxValueInSfb, VO_INDEX_ENC_AAC);
    
	  if(hQC->qcChannel[0].scf)
		  mem_free(pMemOP, hQC->qcChannel[0].scf, VO_INDEX_ENC_AAC);

	  for (i=0; i<MAX_CHANNELS; i++) {
		  hQC->qcChannel[i].quantSpec = NULL;
		  
		  hQC->qcChannel[i].maxValueInSfb = NULL;
		  
		  hQC->qcChannel[i].scf = NULL;
	  }
   } 
}

/*********************************************************************************
*
* function name: QCNew
* description: set QC to zero
* returns:     0 if success
*
**********************************************************************************/
Word16 QCNew(QC_STATE *hQC, VO_MEM_OPERATOR *pMemOP)
{
  pMemOP->Set(VO_INDEX_ENC_AAC, hQC,0,sizeof(QC_STATE));

  return (0);
}

/*********************************************************************************
*
* function name: QCDelete
* description: unint qcout parameter
*
**********************************************************************************/
void QCDelete(QC_STATE *hQC, VO_MEM_OPERATOR *pMemOP)
{
 
  /* 
     nothing to do
  */
  hQC=NULL;
}

/*********************************************************************************
*
* function name: QCInit
* description: init QD parameter
* returns:     0 if success
*
**********************************************************************************/
Word16 QCInit(QC_STATE *hQC,
              struct QC_INIT *init)
{
  hQC->nChannels       = init->elInfo->nChannelsInEl;              
  hQC->maxBitsTot      = init->maxBits;                            
  hQC->bitResTot       = sub(init->bitRes, init->averageBits);
  hQC->averageBitsTot  = init->averageBits;                        
  hQC->maxBitFac       = init->maxBitFac;                          

  hQC->padding.paddingRest = init->padding.paddingRest;            

  hQC->globStatBits    = 3;                          /* for ID_END */ 

  /* channel elements init */
  InitElementBits(&hQC->elementBits,
                  *init->elInfo,
                  init->bitrate,
                  init->averageBits,
                  hQC->globStatBits);

  /* threshold parameter init */
  AdjThrInit(&hQC->adjThr,
             init->meanPe,
             hQC->elementBits.chBitrate);

  return 0;
}


/*********************************************************************************
* 
* function name: QCMain
* description:  quantization and coding the spectrum
* returns:      0 if success
*
**********************************************************************************/
Word16 QCMain(QC_STATE* hQC,              
              ELEMENT_BITS* elBits,
              ATS_ELEMENT* adjThrStateElement,
              PSY_OUT_CHANNEL  psyOutChannel[MAX_CHANNELS],  /* may be modified in-place */
              PSY_OUT_ELEMENT* psyOutElement,
              QC_OUT_CHANNEL  qcOutChannel[MAX_CHANNELS],    /* out                      */
              QC_OUT_ELEMENT* qcOutElement,
              Word16 nChannels,
			  Word16 ancillaryDataBytes)      
{
  Word16 maxChDynBits[MAX_CHANNELS];
  Word16 chBitDistribution[MAX_CHANNELS];  
  Word32 ch;
   
  if (elBits->bitResLevel < 0) {
    return -1;
  }
   
  if (elBits->bitResLevel > elBits->maxBitResBits) {
    return -1;
  }

  qcOutElement->staticBitsUsed = countStaticBitdemand(psyOutChannel,
                                                      psyOutElement,
                                                      nChannels, 
													  qcOutElement->adtsUsed);

   
  if (ancillaryDataBytes) {
    qcOutElement->ancBitsUsed = 7 + (ancillaryDataBytes << 3);
     
    if (ancillaryDataBytes >= 15)
      qcOutElement->ancBitsUsed = qcOutElement->ancBitsUsed + 8;
  }
  else {
    qcOutElement->ancBitsUsed = 0; 
  }

  CalcFormFactor(hQC->logSfbFormFactor, hQC->sfbNRelevantLines, hQC->logSfbEnergy, psyOutChannel, nChannels);

  /*adjust thresholds for the desired bitrate */
  AdjustThresholds(&hQC->adjThr,
                   adjThrStateElement,
                   psyOutChannel,
                   psyOutElement,
                   chBitDistribution,
                   hQC->logSfbEnergy,
                   hQC->sfbNRelevantLines,                   
                   qcOutElement,
				   elBits,
				   nChannels,
				   hQC->maxBitFac);

  /*estimate scale factors */
  EstimateScaleFactors(psyOutChannel,
                       qcOutChannel,
                       hQC->logSfbEnergy,
                       hQC->logSfbFormFactor,
                       hQC->sfbNRelevantLines,
                       nChannels);

  /* condition to prevent empty bitreservoir */
  for (ch = 0; ch < nChannels; ch++) {
    Word32 maxDynBits;
    maxDynBits = elBits->averageBits + elBits->bitResLevel - 7; /* -7 bec. of align bits */
    maxDynBits = maxDynBits - qcOutElement->staticBitsUsed + qcOutElement->ancBitsUsed;
    maxChDynBits[ch] = extract_l(chBitDistribution[ch] * maxDynBits / 1000);
  }

  qcOutElement->dynBitsUsed = 0;                                         
  for (ch = 0; ch < nChannels; ch++) {
    Word32 chDynBits;
    Flag   constraintsFulfilled;
    Word32 iter;
    iter = 0;                                                          
    do {
      constraintsFulfilled = 1;                                        

      QuantizeSpectrum(psyOutChannel[ch].sfbCnt,
                       psyOutChannel[ch].maxSfbPerGroup,
                       psyOutChannel[ch].sfbPerGroup,
                       psyOutChannel[ch].sfbOffsets,
                       psyOutChannel[ch].mdctSpectrum,
                       qcOutChannel[ch].globalGain,
                       qcOutChannel[ch].scf,
                       qcOutChannel[ch].quantSpec);
       
      if (calcMaxValueInSfb(psyOutChannel[ch].sfbCnt,
                            psyOutChannel[ch].maxSfbPerGroup,
                            psyOutChannel[ch].sfbPerGroup,
                            psyOutChannel[ch].sfbOffsets,
                            qcOutChannel[ch].quantSpec,
                            qcOutChannel[ch].maxValueInSfb) > MAX_QUANT) {
        constraintsFulfilled = 0;                                        
      }

      chDynBits = dynBitCount(qcOutChannel[ch].quantSpec,
                              qcOutChannel[ch].maxValueInSfb,
                              qcOutChannel[ch].scf,
                              psyOutChannel[ch].windowSequence,
                              psyOutChannel[ch].sfbCnt,
                              psyOutChannel[ch].maxSfbPerGroup,
                              psyOutChannel[ch].sfbPerGroup,
                              psyOutChannel[ch].sfbOffsets,
                              &qcOutChannel[ch].sectionData);
       
      if (chDynBits >= maxChDynBits[ch]) {
        constraintsFulfilled = 0;                                        
      }
       
      if (!constraintsFulfilled) {
        qcOutChannel[ch].globalGain = qcOutChannel[ch].globalGain + 1;
      }

      iter = iter + 1;
       
    } while(!constraintsFulfilled);

    qcOutElement->dynBitsUsed = qcOutElement->dynBitsUsed + chDynBits;

    qcOutChannel[ch].mdctScale    = psyOutChannel[ch].mdctScale;         
    qcOutChannel[ch].groupingMask = psyOutChannel[ch].groupingMask;      
    qcOutChannel[ch].windowShape  = psyOutChannel[ch].windowShape;       
  }

  /* save dynBitsUsed for correction of bits2pe relation */
  AdjThrUpdate(adjThrStateElement, qcOutElement->dynBitsUsed);

  {
    Word16 bitResSpace = elBits->maxBitResBits - elBits->bitResLevel;
    Word16 deltaBitRes = elBits->averageBits -
                        (qcOutElement->staticBitsUsed +
                         qcOutElement->dynBitsUsed + qcOutElement->ancBitsUsed);

    qcOutElement->fillBits = max(0, (deltaBitRes - bitResSpace));
  }

  return 0; /* OK */
}


/*********************************************************************************
*
* function name: calcMaxValueInSfb
* description:  search the max Spectrum in one sfb
*
**********************************************************************************/
static Word16 calcMaxValueInSfb(Word16 sfbCnt,
                                Word16 maxSfbPerGroup,
                                Word16 sfbPerGroup,
                                Word16 sfbOffset[MAX_GROUPED_SFB],
                                Word16 quantSpectrum[FRAME_LEN_LONG],
                                UWord16 maxValue[MAX_GROUPED_SFB])
{
  Word16 sfbOffs, sfb;
  Word16 maxValueAll;

  maxValueAll = 0;                                       

  for(sfbOffs=0;sfbOffs<sfbCnt;sfbOffs+=sfbPerGroup) {
    for (sfb = 0; sfb < maxSfbPerGroup; sfb++) {
      Word16 line;
      Word16 maxThisSfb;
      maxThisSfb = 0;                                    

      for (line = sfbOffset[sfbOffs+sfb]; line < sfbOffset[sfbOffs+sfb+1]; line++) {
        Word16 absVal;
        absVal = abs_s(quantSpectrum[line]);
        maxThisSfb = max(maxThisSfb, absVal);
      }

      maxValue[sfbOffs+sfb] = maxThisSfb;                
      maxValueAll = max(maxValueAll, maxThisSfb);
    }
  }
  return maxValueAll;
}


/*********************************************************************************
*
* function name: updateBitres
* description: update bitreservoir
*
**********************************************************************************/
void updateBitres(QC_STATE* qcKernel,
                  QC_OUT*   qcOut)
                  
{
  ELEMENT_BITS *elBits;
 
  qcKernel->bitResTot = 0;                               

  elBits = &qcKernel->elementBits;

   
  if (elBits->averageBits > 0) {
    /* constant bitrate */
    Word16 bitsUsed;
    bitsUsed = (qcOut->qcElement.staticBitsUsed + qcOut->qcElement.dynBitsUsed) +
                   (qcOut->qcElement.ancBitsUsed + qcOut->qcElement.fillBits);
    elBits->bitResLevel = elBits->bitResLevel + (elBits->averageBits - bitsUsed);
    qcKernel->bitResTot = qcKernel->bitResTot + elBits->bitResLevel;
  }
  else {
    /* variable bitrate */
    elBits->bitResLevel = elBits->maxBits;           
    qcKernel->bitResTot = qcKernel->maxBitsTot;      
  }
}

/*********************************************************************************
*
* function name: FinalizeBitConsumption
* description: count bits used
*
**********************************************************************************/
Word16 FinalizeBitConsumption(QC_STATE *qcKernel,
                              QC_OUT* qcOut)
{
  Word32 nFullFillElem;
  Word32 totFillBits;
  Word16 diffBits;  
  Word16 bitsUsed;

  totFillBits = 0;                                       

  qcOut->totStaticBitsUsed = qcKernel->globStatBits;     
  qcOut->totStaticBitsUsed += qcOut->qcElement.staticBitsUsed;
  qcOut->totDynBitsUsed    = qcOut->qcElement.dynBitsUsed;
  qcOut->totAncBitsUsed    = qcOut->qcElement.ancBitsUsed;
  qcOut->totFillBits       = qcOut->qcElement.fillBits;
   
  if (qcOut->qcElement.fillBits) {
    totFillBits += qcOut->qcElement.fillBits;
  }

  nFullFillElem = (max((qcOut->totFillBits - 1), 0) / maxFillElemBits) * maxFillElemBits;
  
  qcOut->totFillBits = qcOut->totFillBits - nFullFillElem;

  /* check fill elements */
   
  if (qcOut->totFillBits > 0) {
    /* minimum Fillelement contains 7 (TAG + byte cnt) bits */
    qcOut->totFillBits = max(7, qcOut->totFillBits);
    /* fill element size equals n*8 + 7 */
    qcOut->totFillBits = qcOut->totFillBits + ((8 - ((qcOut->totFillBits - 7) & 0x0007)) & 0x0007);     
  }

  qcOut->totFillBits = qcOut->totFillBits + nFullFillElem;

  /* now distribute extra fillbits and alignbits over channel elements */
  qcOut->alignBits = 7 - ((qcOut->totDynBitsUsed + qcOut->totStaticBitsUsed +
                           qcOut->totAncBitsUsed + qcOut->totFillBits - 1) & 0x0007);             

     
  if ( (qcOut->alignBits + qcOut->totFillBits - totFillBits == 8) &&
       (qcOut->totFillBits > 8))
    qcOut->totFillBits = qcOut->totFillBits - 8;

   
  diffBits = qcOut->alignBits + qcOut->totFillBits - totFillBits;
   
  if(diffBits>=0) {
    qcOut->qcElement.fillBits += diffBits;
  }

  bitsUsed = qcOut->totDynBitsUsed + qcOut->totStaticBitsUsed + qcOut->totAncBitsUsed;
  bitsUsed = bitsUsed + qcOut->totFillBits + qcOut->alignBits;
   
  if (bitsUsed > qcKernel->maxBitsTot) {
    return -1;
  }
  return bitsUsed;
}


/*********************************************************************************
*
* function name: AdjustBitrate
* description:  adjusts framelength via padding on a frame to frame basis,
*               to achieve a bitrate that demands a non byte aligned
*               framelength
* return:       errorcode
*
**********************************************************************************/
Word16 AdjustBitrate(QC_STATE        *hQC,
                     Word32           bitRate,    /* total bitrate */
                     Word32           sampleRate) /* output sampling rate */
{
  Word16 paddingOn;
  Word16 frameLen;
  Word16 codeBits;
  Word16 codeBitsLast;

  /* Do we need a extra padding byte? */
  paddingOn = framePadding(bitRate,
                           sampleRate,
                           &hQC->padding.paddingRest);

  /* frame length */
  frameLen = paddingOn + calcFrameLen(bitRate,
                                      sampleRate,
                                      FRAME_LEN_BYTES_INT);

  frameLen = frameLen << 3;
  codeBitsLast = hQC->averageBitsTot - hQC->globStatBits;
  codeBits     = frameLen - hQC->globStatBits;

  /* calculate bits for every channel element */   
  if (codeBits != codeBitsLast) {
    Word16 totalBits = 0;                                       

    hQC->elementBits.averageBits = (hQC->elementBits.relativeBits * codeBits) >> 16; /* relativeBits was scaled down by 2 */
    totalBits += hQC->elementBits.averageBits;

    hQC->elementBits.averageBits = hQC->elementBits.averageBits + (codeBits - totalBits);
  }

  hQC->averageBitsTot = frameLen;                        

  return 0;
}
