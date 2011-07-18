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
	File:		tns.c

	Content:	Definition TNS tools functions

*******************************************************************************/

#include "basic_op.h"
#include "oper_32b.h"
#include "assert.h"
#include "aac_rom.h"
#include "psy_const.h"
#include "tns.h"
#include "tns_param.h"
#include "psy_configuration.h"
#include "tns_func.h"

#define TNS_MODIFY_BEGIN         2600  /* Hz */
#define RATIO_PATCH_LOWER_BORDER 380   /* Hz */
#define TNS_GAIN_THRESH			 141   /* 1.41*100 */
#define NORM_COEF				 0x028f5c28

static const Word32 TNS_PARCOR_THRESH = 0x0ccccccd; /* 0.1*(1 << 31) */
/* Limit bands to > 2.0 kHz */
static unsigned short tnsMinBandNumberLong[12] =
{ 11, 12, 15, 16, 17, 20, 25, 26, 24, 28, 30, 31 };
static unsigned short tnsMinBandNumberShort[12] =
{ 2, 2, 2, 3, 3, 4, 6, 6, 8, 10, 10, 12 };

/**************************************/
/* Main/Low Profile TNS Parameters    */
/**************************************/
static unsigned short tnsMaxBandsLongMainLow[12] =
{ 31, 31, 34, 40, 42, 51, 46, 46, 42, 42, 42, 39 };

static unsigned short tnsMaxBandsShortMainLow[12] =
{ 9, 9, 10, 14, 14, 14, 14, 14, 14, 14, 14, 14 };


static void CalcWeightedSpectrum(const Word32 spectrum[],
                                 Word16 weightedSpectrum[],
                                 Word32* sfbEnergy,
                                 const Word16* sfbOffset, Word16 lpcStartLine,
                                 Word16 lpcStopLine, Word16 lpcStartBand,Word16 lpcStopBand,
                                 Word32 *pWork32);



void AutoCorrelation(const Word16 input[], Word32 corr[],
                            Word16 samples, Word16 corrCoeff);
static Word16 AutoToParcor(Word32 workBuffer[], Word32 reflCoeff[], Word16 numOfCoeff);

static Word16 CalcTnsFilter(const Word16* signal, const Word32 window[], Word16 numOfLines,
                                              Word16 tnsOrder, Word32 parcor[]);


static void Parcor2Index(const Word32 parcor[], Word16 index[], Word16 order,
                         Word16 bitsPerCoeff);

static void Index2Parcor(const Word16 index[], Word32 parcor[], Word16 order,
                         Word16 bitsPerCoeff);



static void AnalysisFilterLattice(const Word32 signal[], Word16 numOfLines,
                                  const Word32 parCoeff[], Word16 order,
                                  Word32 output[]);


/**
*
* function name: FreqToBandWithRounding
* description:  Retrieve index of nearest band border
* returnt:		index
*
*/
static Word16 FreqToBandWithRounding(Word32 freq,                   /*!< frequency in Hertz */
                                     Word32 fs,                     /*!< Sampling frequency in Hertz */
                                     Word16 numOfBands,             /*!< total number of bands */
                                     const Word16 *bandStartOffset) /*!< table of band borders */
{
  Word32 lineNumber, band;
  Word32 temp, shift;

  /*  assert(freq >= 0);  */
  shift = norm_l(fs);
  lineNumber = (extract_l(fixmul((bandStartOffset[numOfBands] << 2),Div_32(freq << shift,fs << shift))) + 1) >> 1;

  /* freq > fs/2 */
  temp = lineNumber - bandStartOffset[numOfBands] ;
  if (temp >= 0)
    return numOfBands;

  /* find band the line number lies in */
  for (band=0; band<numOfBands; band++) {
    temp = bandStartOffset[band + 1] - lineNumber;
    if (temp > 0) break;
  }

  temp = (lineNumber - bandStartOffset[band]);
  temp = (temp - (bandStartOffset[band + 1] - lineNumber));
  if ( temp > 0 )
  {
    band = band + 1;
  }

  return extract_l(band);
}


/**
*
* function name: InitTnsConfigurationLong
* description:  Fill TNS_CONFIG structure with sensible content for long blocks
* returns:		0 if success
*
*/
Word16 InitTnsConfigurationLong(Word32 bitRate,          /*!< bitrate */
                                Word32 sampleRate,          /*!< Sampling frequency */
                                Word16 channels,            /*!< number of channels */
                                TNS_CONFIG *tC,             /*!< TNS Config struct (modified) */
                                PSY_CONFIGURATION_LONG *pC, /*!< psy config struct */
                                Word16 active)              /*!< tns active flag */
{

  Word32 bitratePerChannel;
  tC->maxOrder     = TNS_MAX_ORDER;
  tC->tnsStartFreq = 1275;
  tC->coefRes      = 4;

  /* to avoid integer division */
  if ( sub(channels,2) == 0 ) {
    bitratePerChannel = bitRate >> 1;
  }
  else {
    bitratePerChannel = bitRate;
  }

  tC->tnsMaxSfb = tnsMaxBandsLongMainLow[pC->sampRateIdx];

  tC->tnsActive = active;

  /* now calc band and line borders */
  tC->tnsStopBand = min(pC->sfbCnt, tC->tnsMaxSfb);
  tC->tnsStopLine = pC->sfbOffset[tC->tnsStopBand];

  tC->tnsStartBand = FreqToBandWithRounding(tC->tnsStartFreq, sampleRate,
                                            pC->sfbCnt, (const Word16*)pC->sfbOffset);

  tC->tnsModifyBeginCb = FreqToBandWithRounding(TNS_MODIFY_BEGIN,
                                                sampleRate,
                                                pC->sfbCnt,
                                                (const Word16*)pC->sfbOffset);

  tC->tnsRatioPatchLowestCb = FreqToBandWithRounding(RATIO_PATCH_LOWER_BORDER,
                                                     sampleRate,
                                                     pC->sfbCnt,
                                                     (const Word16*)pC->sfbOffset);


  tC->tnsStartLine = pC->sfbOffset[tC->tnsStartBand];

  tC->lpcStopBand = tnsMaxBandsLongMainLow[pC->sampRateIdx];
  tC->lpcStopBand = min(tC->lpcStopBand, pC->sfbActive);

  tC->lpcStopLine = pC->sfbOffset[tC->lpcStopBand];

  tC->lpcStartBand = tnsMinBandNumberLong[pC->sampRateIdx];

  tC->lpcStartLine = pC->sfbOffset[tC->lpcStartBand];

  tC->threshold = TNS_GAIN_THRESH;


  return(0);
}

/**
*
* function name: InitTnsConfigurationShort
* description:  Fill TNS_CONFIG structure with sensible content for short blocks
* returns:		0 if success
*
*/
Word16 InitTnsConfigurationShort(Word32 bitRate,              /*!< bitrate */
                                 Word32 sampleRate,           /*!< Sampling frequency */
                                 Word16 channels,             /*!< number of channels */
                                 TNS_CONFIG *tC,              /*!< TNS Config struct (modified) */
                                 PSY_CONFIGURATION_SHORT *pC, /*!< psy config struct */
                                 Word16 active)               /*!< tns active flag */
{
  Word32 bitratePerChannel;
  tC->maxOrder     = TNS_MAX_ORDER_SHORT;
  tC->tnsStartFreq = 2750;
  tC->coefRes      = 3;

  /* to avoid integer division */
  if ( sub(channels,2) == 0 ) {
    bitratePerChannel = L_shr(bitRate,1);
  }
  else {
    bitratePerChannel = bitRate;
  }

  tC->tnsMaxSfb = tnsMaxBandsShortMainLow[pC->sampRateIdx];

  tC->tnsActive = active;

  /* now calc band and line borders */
  tC->tnsStopBand = min(pC->sfbCnt, tC->tnsMaxSfb);
  tC->tnsStopLine = pC->sfbOffset[tC->tnsStopBand];

  tC->tnsStartBand=FreqToBandWithRounding(tC->tnsStartFreq, sampleRate,
                                          pC->sfbCnt, (const Word16*)pC->sfbOffset);

  tC->tnsModifyBeginCb = FreqToBandWithRounding(TNS_MODIFY_BEGIN,
                                                sampleRate,
                                                pC->sfbCnt,
                                                (const Word16*)pC->sfbOffset);

  tC->tnsRatioPatchLowestCb = FreqToBandWithRounding(RATIO_PATCH_LOWER_BORDER,
                                                     sampleRate,
                                                     pC->sfbCnt,
                                                     (const Word16*)pC->sfbOffset);


  tC->tnsStartLine = pC->sfbOffset[tC->tnsStartBand];

  tC->lpcStopBand = tnsMaxBandsShortMainLow[pC->sampRateIdx];

  tC->lpcStopBand = min(tC->lpcStopBand, pC->sfbActive);

  tC->lpcStopLine = pC->sfbOffset[tC->lpcStopBand];

  tC->lpcStartBand = tnsMinBandNumberShort[pC->sampRateIdx];

  tC->lpcStartLine = pC->sfbOffset[tC->lpcStartBand];

  tC->threshold = TNS_GAIN_THRESH;

  return(0);
}

/**
*
* function name: TnsDetect
* description:  Calculate TNS filter and decide on TNS usage
* returns:		0 if success
*
*/
Word32 TnsDetect(TNS_DATA* tnsData,        /*!< tns data structure (modified) */
                 TNS_CONFIG tC,            /*!< tns config structure */
                 Word32* pScratchTns,      /*!< pointer to scratch space */
                 const Word16 sfbOffset[], /*!< scalefactor size and table */
                 Word32* spectrum,         /*!< spectral data */
                 Word16 subBlockNumber,    /*!< subblock num */
                 Word16 blockType,         /*!< blocktype (long or short) */
                 Word32 * sfbEnergy)       /*!< sfb-wise energy */
{

  Word32  predictionGain;
  Word32  temp;
  Word32* pWork32 = &pScratchTns[subBlockNumber >> 8];
  Word16* pWeightedSpectrum = (Word16 *)&pScratchTns[subBlockNumber >> 8];


  if (tC.tnsActive) {
    CalcWeightedSpectrum(spectrum,
                         pWeightedSpectrum,
                         sfbEnergy,
                         sfbOffset,
                         tC.lpcStartLine,
                         tC.lpcStopLine,
                         tC.lpcStartBand,
                         tC.lpcStopBand,
                         pWork32);

    temp = blockType - SHORT_WINDOW;
    if ( temp != 0 ) {
        predictionGain = CalcTnsFilter( &pWeightedSpectrum[tC.lpcStartLine],
                                        tC.acfWindow,
                                        tC.lpcStopLine - tC.lpcStartLine,
                                        tC.maxOrder,
                                        tnsData->dataRaw.tnsLong.subBlockInfo.parcor);


        temp = predictionGain - tC.threshold;
        if ( temp > 0 ) {
          tnsData->dataRaw.tnsLong.subBlockInfo.tnsActive = 1;
        }
        else {
          tnsData->dataRaw.tnsLong.subBlockInfo.tnsActive = 0;
        }

        tnsData->dataRaw.tnsLong.subBlockInfo.predictionGain = predictionGain;
    }
    else{

        predictionGain = CalcTnsFilter( &pWeightedSpectrum[tC.lpcStartLine],
                                        tC.acfWindow,
                                        tC.lpcStopLine - tC.lpcStartLine,
                                        tC.maxOrder,
                                        tnsData->dataRaw.tnsShort.subBlockInfo[subBlockNumber].parcor);

        temp = predictionGain - tC.threshold;
        if ( temp > 0 ) {
          tnsData->dataRaw.tnsShort.subBlockInfo[subBlockNumber].tnsActive = 1;
        }
        else {
          tnsData->dataRaw.tnsShort.subBlockInfo[subBlockNumber].tnsActive = 0;
        }

        tnsData->dataRaw.tnsShort.subBlockInfo[subBlockNumber].predictionGain = predictionGain;
    }

  }
  else{

    temp = blockType - SHORT_WINDOW;
    if ( temp != 0 ) {
        tnsData->dataRaw.tnsLong.subBlockInfo.tnsActive = 0;
        tnsData->dataRaw.tnsLong.subBlockInfo.predictionGain = 0;
    }
    else {
        tnsData->dataRaw.tnsShort.subBlockInfo[subBlockNumber].tnsActive = 0;
        tnsData->dataRaw.tnsShort.subBlockInfo[subBlockNumber].predictionGain = 0;
    }
  }

  return(0);
}


/*****************************************************************************
*
* function name: TnsSync
* description: update tns parameter
*
*****************************************************************************/
void TnsSync(TNS_DATA *tnsDataDest,
             const TNS_DATA *tnsDataSrc,
             const TNS_CONFIG tC,
             const Word16 subBlockNumber,
             const Word16 blockType)
{
   TNS_SUBBLOCK_INFO *sbInfoDest;
   const TNS_SUBBLOCK_INFO *sbInfoSrc;
   Word32 i, temp;

   temp =  blockType - SHORT_WINDOW;
   if ( temp != 0 ) {
      sbInfoDest = &tnsDataDest->dataRaw.tnsLong.subBlockInfo;
      sbInfoSrc  = &tnsDataSrc->dataRaw.tnsLong.subBlockInfo;
   }
   else {
      sbInfoDest = &tnsDataDest->dataRaw.tnsShort.subBlockInfo[subBlockNumber];
      sbInfoSrc  = &tnsDataSrc->dataRaw.tnsShort.subBlockInfo[subBlockNumber];
   }

   if (100*abs_s(sbInfoDest->predictionGain - sbInfoSrc->predictionGain) <
       (3 * sbInfoDest->predictionGain)) {
      sbInfoDest->tnsActive = sbInfoSrc->tnsActive;
      for ( i=0; i< tC.maxOrder; i++) {
        sbInfoDest->parcor[i] = sbInfoSrc->parcor[i];
      }
   }
}

/*****************************************************************************
*
* function name: TnsEncode
* description: do TNS filtering
* returns:     0 if success
*
*****************************************************************************/
Word16 TnsEncode(TNS_INFO* tnsInfo,     /*!< tns info structure (modified) */
                 TNS_DATA* tnsData,     /*!< tns data structure (modified) */
                 Word16 numOfSfb,       /*!< number of scale factor bands */
                 TNS_CONFIG tC,         /*!< tns config structure */
                 Word16 lowPassLine,    /*!< lowpass line */
                 Word32* spectrum,      /*!< spectral data (modified) */
                 Word16 subBlockNumber, /*!< subblock num */
                 Word16 blockType)      /*!< blocktype (long or short) */
{
  Word32 i;
  Word32 temp_s;
  Word32 temp;
  TNS_SUBBLOCK_INFO *psubBlockInfo;

  temp_s = blockType - SHORT_WINDOW;
  if ( temp_s != 0) {
    psubBlockInfo = &tnsData->dataRaw.tnsLong.subBlockInfo;
	if (psubBlockInfo->tnsActive == 0) {
      tnsInfo->tnsActive[subBlockNumber] = 0;
      return(0);
    }
    else {

      Parcor2Index(psubBlockInfo->parcor,
                   tnsInfo->coef,
                   tC.maxOrder,
                   tC.coefRes);

      Index2Parcor(tnsInfo->coef,
                   psubBlockInfo->parcor,
                   tC.maxOrder,
                   tC.coefRes);

      for (i=tC.maxOrder - 1; i>=0; i--)  {
        temp = psubBlockInfo->parcor[i] - TNS_PARCOR_THRESH;
        if ( temp > 0 )
          break;
        temp = psubBlockInfo->parcor[i] + TNS_PARCOR_THRESH;
        if ( temp < 0 )
          break;
      }
      tnsInfo->order[subBlockNumber] = i + 1;


      tnsInfo->tnsActive[subBlockNumber] = 1;
      for (i=subBlockNumber+1; i<TRANS_FAC; i++) {
        tnsInfo->tnsActive[i] = 0;
      }
      tnsInfo->coefRes[subBlockNumber] = tC.coefRes;
      tnsInfo->length[subBlockNumber] = numOfSfb - tC.tnsStartBand;


      AnalysisFilterLattice(&(spectrum[tC.tnsStartLine]),
                            (min(tC.tnsStopLine,lowPassLine) - tC.tnsStartLine),
                            psubBlockInfo->parcor,
                            tnsInfo->order[subBlockNumber],
                            &(spectrum[tC.tnsStartLine]));

    }
  }     /* if (blockType!=SHORT_WINDOW) */
  else /*short block*/ {
    psubBlockInfo = &tnsData->dataRaw.tnsShort.subBlockInfo[subBlockNumber];
	if (psubBlockInfo->tnsActive == 0) {
      tnsInfo->tnsActive[subBlockNumber] = 0;
      return(0);
    }
    else {

      Parcor2Index(psubBlockInfo->parcor,
                   &tnsInfo->coef[subBlockNumber*TNS_MAX_ORDER_SHORT],
                   tC.maxOrder,
                   tC.coefRes);

      Index2Parcor(&tnsInfo->coef[subBlockNumber*TNS_MAX_ORDER_SHORT],
                   psubBlockInfo->parcor,
                   tC.maxOrder,
                   tC.coefRes);
      for (i=(tC.maxOrder - 1); i>=0; i--)  {
        temp = psubBlockInfo->parcor[i] - TNS_PARCOR_THRESH;
         if ( temp > 0 )
          break;

        temp = psubBlockInfo->parcor[i] + TNS_PARCOR_THRESH;
        if ( temp < 0 )
          break;
      }
      tnsInfo->order[subBlockNumber] = i + 1;

      tnsInfo->tnsActive[subBlockNumber] = 1;
      tnsInfo->coefRes[subBlockNumber] = tC.coefRes;
      tnsInfo->length[subBlockNumber] = numOfSfb - tC.tnsStartBand;


      AnalysisFilterLattice(&(spectrum[tC.tnsStartLine]), (tC.tnsStopLine - tC.tnsStartLine),
                 psubBlockInfo->parcor,
                 tnsInfo->order[subBlockNumber],
                 &(spectrum[tC.tnsStartLine]));

    }
  }

  return(0);
}


/*****************************************************************************
*
* function name: m_pow2_cordic
* description: Iterative power function
*
*	Calculates pow(2.0,x-1.0*(scale+1)) with INT_BITS bit precision
*	using modified cordic algorithm
* returns:     the result of pow2
*
*****************************************************************************/
static Word32 m_pow2_cordic(Word32 x, Word16 scale)
{
  Word32 k;

  Word32 accu_y = 0x40000000;
  accu_y = L_shr(accu_y,scale);

  for(k=1; k<INT_BITS; k++) {
    const Word32 z = m_log2_table[k];

    while(L_sub(x,z) >= 0) {

      x = L_sub(x, z);
      accu_y = L_add(accu_y, (accu_y >> k));
    }
  }
  return(accu_y);
}


/*****************************************************************************
*
* function name: CalcWeightedSpectrum
* description: Calculate weighted spectrum for LPC calculation
*
*****************************************************************************/
static void CalcWeightedSpectrum(const Word32  spectrum[],         /*!< input spectrum */
                                 Word16        weightedSpectrum[],
                                 Word32       *sfbEnergy,          /*!< sfb energies */
                                 const Word16 *sfbOffset,
                                 Word16        lpcStartLine,
                                 Word16        lpcStopLine,
                                 Word16        lpcStartBand,
                                 Word16        lpcStopBand,
                                 Word32       *pWork32)
{
    #define INT_BITS_SCAL 1<<(INT_BITS/2)

    Word32 i, sfb, shift;
    Word32 maxShift;
    Word32 tmp_s, tmp2_s;
    Word32 tmp, tmp2;
    Word32 maxWS;
    Word32 tnsSfbMean[MAX_SFB];    /* length [lpcStopBand-lpcStartBand] should be sufficient here */

    maxWS = 0;

    /* calc 1.0*2^-INT_BITS/2/sqrt(en) */
    for( sfb = lpcStartBand; sfb < lpcStopBand; sfb++) {

      tmp2 = sfbEnergy[sfb] - 2;
      if( tmp2 > 0) {
        tmp = rsqrt(sfbEnergy[sfb], INT_BITS);
		if(tmp > INT_BITS_SCAL)
		{
			shift =  norm_l(tmp);
			tmp = Div_32( INT_BITS_SCAL << shift, tmp << shift );
		}
		else
		{
			tmp = 0x7fffffff;
		}
      }
      else {
        tmp = 0x7fffffff;
      }
      tnsSfbMean[sfb] = tmp;
    }

    /* spread normalized values from sfbs to lines */
    sfb = lpcStartBand;
    tmp = tnsSfbMean[sfb];
    for ( i=lpcStartLine; i<lpcStopLine; i++){
      tmp_s = sfbOffset[sfb + 1] - i;
      if ( tmp_s == 0 ) {
        sfb = sfb + 1;
        tmp2_s = sfb + 1 - lpcStopBand;
        if (tmp2_s <= 0) {
          tmp = tnsSfbMean[sfb];
        }
      }
      pWork32[i] = tmp;
    }
    /*filter down*/
    for (i=(lpcStopLine - 2); i>=lpcStartLine; i--){
        pWork32[i] = (pWork32[i] + pWork32[i + 1]) >> 1;
    }
    /* filter up */
    for (i=(lpcStartLine + 1); i<lpcStopLine; i++){
       pWork32[i] = (pWork32[i] + pWork32[i - 1]) >> 1;
    }

    /* weight and normalize */
    for (i=lpcStartLine; i<lpcStopLine; i++){
      pWork32[i] = MULHIGH(pWork32[i], spectrum[i]);
      maxWS |= L_abs(pWork32[i]);
    }
    maxShift = norm_l(maxWS);

	maxShift = 16 - maxShift;
    if(maxShift >= 0)
	{
		for (i=lpcStartLine; i<lpcStopLine; i++){
			weightedSpectrum[i] = pWork32[i] >> maxShift;
		}
    }
	else
	{
		maxShift = -maxShift;
		for (i=lpcStartLine; i<lpcStopLine; i++){
			weightedSpectrum[i] = saturate(pWork32[i] << maxShift);
		}
	}
}




/*****************************************************************************
*
* function name: CalcTnsFilter
* description:  LPC calculation for one TNS filter
* returns:      prediction gain
* input:        signal spectrum, acf window, no. of spectral lines,
*                max. TNS order, ptr. to reflection ocefficients
* output:       reflection coefficients
*(half) window size must be larger than tnsOrder !!*
******************************************************************************/

static Word16 CalcTnsFilter(const Word16 *signal,
                            const Word32 window[],
                            Word16 numOfLines,
                            Word16 tnsOrder,
                            Word32 parcor[])
{
  Word32 parcorWorkBuffer[2*TNS_MAX_ORDER+1];
  Word32 predictionGain;
  Word32 i;
  Word32 tnsOrderPlus1 = tnsOrder + 1;

  assert(tnsOrder <= TNS_MAX_ORDER);      /* remove asserts later? (btg) */

  for(i=0;i<tnsOrder;i++) {
    parcor[i] = 0;
  }

  AutoCorrelation(signal, parcorWorkBuffer, numOfLines, tnsOrderPlus1);

  /* early return if signal is very low: signal prediction off, with zero parcor coeffs */
  if (parcorWorkBuffer[0] == 0)
    return 0;

  predictionGain = AutoToParcor(parcorWorkBuffer, parcor, tnsOrder);

  return(predictionGain);
}

/*****************************************************************************
*
* function name: AutoCorrelation
* description:  calc. autocorrelation (acf)
* returns:      -
* input:        input values, no. of input values, no. of acf values
* output:       acf values
*
*****************************************************************************/
#ifndef ARMV5E
void AutoCorrelation(const Word16		 input[],
                            Word32       corr[],
                            Word16       samples,
                            Word16       corrCoeff) {
  Word32 i, j, isamples;
  Word32 accu;
  Word32 scf;

  scf = 10 - 1;

  isamples = samples;
  /* calc first corrCoef:  R[0] = sum { t[i] * t[i] } ; i = 0..N-1 */
  accu = 0;
  for(j=0; j<isamples; j++) {
    accu = L_add(accu, ((input[j] * input[j]) >> scf));
  }
  corr[0] = accu;

  /* early termination if all corr coeffs are likely going to be zero */
  if(corr[0] == 0) return ;

  /* calc all other corrCoef:  R[j] = sum { t[i] * t[i+j] } ; i = 0..(N-j-1), j=1..p */
  for(i=1; i<corrCoeff; i++) {
    isamples = isamples - 1;
    accu = 0;
    for(j=0; j<isamples; j++) {
      accu = L_add(accu, ((input[j] * input[j+i]) >> scf));
    }
    corr[i] = accu;
  }
}
#endif

/*****************************************************************************
*
* function name: AutoToParcor
* description:  conversion autocorrelation to reflection coefficients
* returns:      prediction gain
* input:        <order+1> input values, no. of output values (=order),
*               ptr. to workbuffer (required size: 2*order)
* output:       <order> reflection coefficients
*
*****************************************************************************/
static Word16 AutoToParcor(Word32 workBuffer[], Word32 reflCoeff[], Word16 numOfCoeff) {

  Word32 i, j, shift;
  Word32 *pWorkBuffer; /* temp pointer */
  Word32 predictionGain = 0;
  Word32 num, denom;
  Word32 temp, workBuffer0;


  num = workBuffer[0];
  temp = workBuffer[numOfCoeff];

  for(i=0; i<numOfCoeff-1; i++) {
    workBuffer[i + numOfCoeff] = workBuffer[i + 1];
  }
  workBuffer[i + numOfCoeff] = temp;

  for(i=0; i<numOfCoeff; i++) {
    Word32 refc;


    if (workBuffer[0] < L_abs(workBuffer[i + numOfCoeff])) {
      return 0 ;
    }
	shift = norm_l(workBuffer[0]);
	workBuffer0 = Div_32(1 << shift, workBuffer[0] << shift);
    /* calculate refc = -workBuffer[numOfCoeff+i] / workBuffer[0]; -1 <= refc < 1 */
	refc = L_negate(fixmul(workBuffer[numOfCoeff + i], workBuffer0));

    reflCoeff[i] = refc;

    pWorkBuffer = &(workBuffer[numOfCoeff]);

    for(j=i; j<numOfCoeff; j++) {
      Word32 accu1, accu2;
      accu1 = L_add(pWorkBuffer[j], fixmul(refc, workBuffer[j - i]));
      accu2 = L_add(workBuffer[j - i], fixmul(refc, pWorkBuffer[j]));
      pWorkBuffer[j] = accu1;
      workBuffer[j - i] = accu2;
    }
  }

  denom = MULHIGH(workBuffer[0], NORM_COEF);

  if (denom != 0) {
    Word32 temp;
	shift = norm_l(denom);
	temp = Div_32(1 << shift, denom << shift);
    predictionGain = fixmul(num, temp);
  }

  return extract_l(predictionGain);
}



static Word16 Search3(Word32 parcor)
{
  Word32 index = 0;
  Word32 i;
  Word32 temp;

  for (i=0;i<8;i++) {
    temp = L_sub( parcor, tnsCoeff3Borders[i]);
    if (temp > 0)
      index=i;
  }
  return extract_l(index - 4);
}

static Word16 Search4(Word32 parcor)
{
  Word32 index = 0;
  Word32 i;
  Word32 temp;


  for (i=0;i<16;i++) {
    temp = L_sub(parcor, tnsCoeff4Borders[i]);
    if (temp > 0)
      index=i;
  }
  return extract_l(index - 8);
}



/*****************************************************************************
*
* functionname: Parcor2Index
* description:  quantization index for reflection coefficients
*
*****************************************************************************/
static void Parcor2Index(const Word32 parcor[],   /*!< parcor coefficients */
                         Word16 index[],          /*!< quantized coeff indices */
                         Word16 order,            /*!< filter order */
                         Word16 bitsPerCoeff) {   /*!< quantizer resolution */
  Word32 i;
  Word32 temp;

  for(i=0; i<order; i++) {
    temp = bitsPerCoeff - 3;
    if (temp == 0) {
      index[i] = Search3(parcor[i]);
    }
    else {
      index[i] = Search4(parcor[i]);
    }
  }
}

/*****************************************************************************
*
* functionname: Index2Parcor
* description:  Inverse quantization for reflection coefficients
*
*****************************************************************************/
static void Index2Parcor(const Word16 index[],  /*!< quantized values */
                         Word32 parcor[],       /*!< ptr. to reflection coefficients (output) */
                         Word16 order,          /*!< no. of coefficients */
                         Word16 bitsPerCoeff)   /*!< quantizer resolution */
{
  Word32 i;
  Word32 temp;

  for (i=0; i<order; i++) {
    temp = bitsPerCoeff - 4;
    if ( temp == 0 ) {
        parcor[i] = tnsCoeff4[index[i] + 8];
    }
    else {
        parcor[i] = tnsCoeff3[index[i] + 4];
    }
  }
}

/*****************************************************************************
*
* functionname: FIRLattice
* description:  in place lattice filtering of spectral data
* returns:		pointer to modified data
*
*****************************************************************************/
static Word32 FIRLattice(Word16 order,           /*!< filter order */
                         Word32 x,               /*!< spectral data */
                         Word32 *state_par,      /*!< filter states */
                         const Word32 *coef_par) /*!< filter coefficients */
{
   Word32 i;
   Word32 accu,tmp,tmpSave;

   x = x >> 1;
   tmpSave = x;

   for (i=0; i<(order - 1); i++) {

     tmp = L_add(fixmul(coef_par[i], x), state_par[i]);
     x   = L_add(fixmul(coef_par[i], state_par[i]), x);

     state_par[i] = tmpSave;
     tmpSave = tmp;
  }

  /* last stage: only need half operations */
  accu = fixmul(state_par[order - 1], coef_par[(order - 1)]);
  state_par[(order - 1)] = tmpSave;

  x = L_add(accu, x);
  x = L_add(x, x);

  return x;
}

/*****************************************************************************
*
* functionname: AnalysisFilterLattice
* description:  filters spectral lines with TNS filter
*
*****************************************************************************/
static void AnalysisFilterLattice(const  Word32 signal[],  /*!< input spectrum */
                                  Word16 numOfLines,       /*!< no. of lines */
                                  const  Word32 parCoeff[],/*!< PARC coefficients */
                                  Word16 order,            /*!< filter order */
                                  Word32 output[])         /*!< filtered signal values */
{

  Word32 state_par[TNS_MAX_ORDER];
  Word32 j;

  for ( j=0; j<TNS_MAX_ORDER; j++ ) {
    state_par[j] = 0;
  }

  for(j=0; j<numOfLines; j++) {
    output[j] = FIRLattice(order,signal[j],state_par,parCoeff);
  }
}

/*****************************************************************************
*
* functionname: ApplyTnsMultTableToRatios
* description:  Change thresholds according to tns
*
*****************************************************************************/
void ApplyTnsMultTableToRatios(Word16 startCb,
                               Word16 stopCb,
                               TNS_SUBBLOCK_INFO subInfo, /*!< TNS subblock info */
                               Word32 *thresholds)        /*!< thresholds (modified) */
{
  Word32 i;
  if (subInfo.tnsActive) {
    for(i=startCb; i<stopCb; i++) {
      /* thresholds[i] * 0.25 */
      thresholds[i] = (thresholds[i] >> 2);
    }
  }
}
