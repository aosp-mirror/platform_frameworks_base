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
	File:		psy_main.c

	Content:	Psychoacoustic major functions

*******************************************************************************/

#include "typedef.h"
#include "basic_op.h"
#include "oper_32b.h"
#include "psy_const.h"
#include "block_switch.h"
#include "transform.h"
#include "spreading.h"
#include "pre_echo_control.h"
#include "band_nrg.h"
#include "psy_configuration.h"
#include "psy_data.h"
#include "ms_stereo.h"
#include "interface.h"
#include "psy_main.h"
#include "grp_data.h"
#include "tns_func.h"
#include "memalign.h"

/*                                    long       start       short       stop */
static Word16 blockType2windowShape[] = {KBD_WINDOW,SINE_WINDOW,SINE_WINDOW,KBD_WINDOW};

/*
  forward definitions
*/
static Word16 advancePsychLong(PSY_DATA* psyData,
                               TNS_DATA* tnsData,
                               PSY_CONFIGURATION_LONG *hPsyConfLong,
                               PSY_OUT_CHANNEL* psyOutChannel,
                               Word32 *pScratchTns,
                               const TNS_DATA *tnsData2,
                               const Word16 ch);

static Word16 advancePsychLongMS (PSY_DATA  psyData[MAX_CHANNELS],
                                  const PSY_CONFIGURATION_LONG *hPsyConfLong);

static Word16 advancePsychShort(PSY_DATA* psyData,
                                TNS_DATA* tnsData,
                                const PSY_CONFIGURATION_SHORT *hPsyConfShort,
                                PSY_OUT_CHANNEL* psyOutChannel,
                                Word32 *pScratchTns,
                                const TNS_DATA *tnsData2,
                                const Word16 ch);

static Word16 advancePsychShortMS (PSY_DATA  psyData[MAX_CHANNELS],
                                   const PSY_CONFIGURATION_SHORT *hPsyConfShort);


/*****************************************************************************
*
* function name: PsyNew
* description:  allocates memory for psychoacoustic
* returns:      an error code
* input:        pointer to a psych handle
*
*****************************************************************************/
Word16 PsyNew(PSY_KERNEL *hPsy, Word32 nChan, VO_MEM_OPERATOR *pMemOP)
{
  Word16 i;
  Word32 *mdctSpectrum;
  Word32 *scratchTNS;
  Word16 *mdctDelayBuffer;

  mdctSpectrum = (Word32 *)mem_malloc(pMemOP, nChan * FRAME_LEN_LONG * sizeof(Word32), 32, VO_INDEX_ENC_AAC);
  if(NULL == mdctSpectrum)
	  return 1;

  scratchTNS = (Word32 *)mem_malloc(pMemOP, nChan * FRAME_LEN_LONG * sizeof(Word32), 32, VO_INDEX_ENC_AAC);
  if(NULL == scratchTNS)
  {
	  return 1;
  }

  mdctDelayBuffer = (Word16 *)mem_malloc(pMemOP, nChan * BLOCK_SWITCHING_OFFSET * sizeof(Word16), 32, VO_INDEX_ENC_AAC);
  if(NULL == mdctDelayBuffer)
  {
	  return 1;
  }

  for (i=0; i<nChan; i++){
    hPsy->psyData[i].mdctDelayBuffer = mdctDelayBuffer + i*BLOCK_SWITCHING_OFFSET;
    hPsy->psyData[i].mdctSpectrum = mdctSpectrum + i*FRAME_LEN_LONG;
  }

  hPsy->pScratchTns = scratchTNS;

  return 0;
}


/*****************************************************************************
*
* function name: PsyDelete
* description:  allocates memory for psychoacoustic
* returns:      an error code
*
*****************************************************************************/
Word16 PsyDelete(PSY_KERNEL  *hPsy, VO_MEM_OPERATOR *pMemOP)
{
  Word32 nch;

  if(hPsy)
  {
	if(hPsy->psyData[0].mdctDelayBuffer)
		mem_free(pMemOP, hPsy->psyData[0].mdctDelayBuffer, VO_INDEX_ENC_AAC);

    if(hPsy->psyData[0].mdctSpectrum)
		mem_free(pMemOP, hPsy->psyData[0].mdctSpectrum, VO_INDEX_ENC_AAC);

    for (nch=0; nch<MAX_CHANNELS; nch++){
	  hPsy->psyData[nch].mdctDelayBuffer = NULL;
	  hPsy->psyData[nch].mdctSpectrum = NULL;
	}

	if(hPsy->pScratchTns)
	{
		mem_free(pMemOP, hPsy->pScratchTns, VO_INDEX_ENC_AAC);
		hPsy->pScratchTns = NULL;
	}
  }

  return 0;
}


/*****************************************************************************
*
* function name: PsyOutNew
* description:  allocates memory for psyOut struc
* returns:      an error code
* input:        pointer to a psych handle
*
*****************************************************************************/
Word16 PsyOutNew(PSY_OUT *hPsyOut, VO_MEM_OPERATOR *pMemOP)
{
  pMemOP->Set(VO_INDEX_ENC_AAC, hPsyOut, 0, sizeof(PSY_OUT));
  /*
    alloc some more stuff, tbd
  */
  return 0;
}

/*****************************************************************************
*
* function name: PsyOutDelete
* description:  allocates memory for psychoacoustic
* returns:      an error code
*
*****************************************************************************/
Word16 PsyOutDelete(PSY_OUT *hPsyOut, VO_MEM_OPERATOR *pMemOP)
{
  hPsyOut=NULL;
  return 0;
}


/*****************************************************************************
*
* function name: psyMainInit
* description:  initializes psychoacoustic
* returns:      an error code
*
*****************************************************************************/

Word16 psyMainInit(PSY_KERNEL *hPsy,
                   Word32 sampleRate,
                   Word32 bitRate,
                   Word16 channels,
                   Word16 tnsMask,
                   Word16 bandwidth)
{
  Word16 ch, err;
  Word32 channelBitRate = bitRate/channels;

  err = InitPsyConfigurationLong(channelBitRate,
                                 sampleRate,
                                 bandwidth,
                                 &(hPsy->psyConfLong));

  if (!err) {
      hPsy->sampleRateIdx = hPsy->psyConfLong.sampRateIdx;
	  err = InitTnsConfigurationLong(bitRate, sampleRate, channels,
                                   &hPsy->psyConfLong.tnsConf, &hPsy->psyConfLong, tnsMask&2);
  }

  if (!err)
    err = InitPsyConfigurationShort(channelBitRate,
                                    sampleRate,
                                    bandwidth,
                                    &hPsy->psyConfShort);
  if (!err) {
    err = InitTnsConfigurationShort(bitRate, sampleRate, channels,
                                    &hPsy->psyConfShort.tnsConf, &hPsy->psyConfShort, tnsMask&1);
  }

  if (!err)
    for(ch=0;ch < channels;ch++){

      InitBlockSwitching(&hPsy->psyData[ch].blockSwitchingControl,
                         bitRate, channels);

      InitPreEchoControl(hPsy->psyData[ch].sfbThresholdnm1,
                         hPsy->psyConfLong.sfbCnt,
                         hPsy->psyConfLong.sfbThresholdQuiet);
      hPsy->psyData[ch].mdctScalenm1 = 0;
    }

	return(err);
}

/*****************************************************************************
*
* function name: psyMain
* description:  psychoacoustic main function
* returns:      an error code
*
*    This function assumes that enough input data is in the modulo buffer.
*
*****************************************************************************/

Word16 psyMain(Word16                   nChannels,
               ELEMENT_INFO            *elemInfo,
               Word16                  *timeSignal,
               PSY_DATA                 psyData[MAX_CHANNELS],
               TNS_DATA                 tnsData[MAX_CHANNELS],
               PSY_CONFIGURATION_LONG  *hPsyConfLong,
               PSY_CONFIGURATION_SHORT *hPsyConfShort,
               PSY_OUT_CHANNEL          psyOutChannel[MAX_CHANNELS],
               PSY_OUT_ELEMENT         *psyOutElement,
               Word32                  *pScratchTns,
			   Word32				   sampleRate)
{
  Word16 maxSfbPerGroup[MAX_CHANNELS];
  Word16 mdctScalingArray[MAX_CHANNELS];

  Word16 ch;   /* counts through channels          */
  Word16 sfb;  /* counts through scalefactor bands */
  Word16 line; /* counts through lines             */
  Word16 channels;
  Word16 maxScale;

  channels = elemInfo->nChannelsInEl;
  maxScale = 0;

  /* block switching */
  for(ch = 0; ch < channels; ch++) {
    BlockSwitching(&psyData[ch].blockSwitchingControl,
                   timeSignal+elemInfo->ChannelIndex[ch],
				   sampleRate,
                   nChannels);
  }

  /* synch left and right block type */
  SyncBlockSwitching(&psyData[0].blockSwitchingControl,
                     &psyData[1].blockSwitchingControl,
                     channels);

  /* transform
     and get maxScale (max mdctScaling) for all channels */
  for(ch=0; ch<channels; ch++) {
    Transform_Real(psyData[ch].mdctDelayBuffer,
                   timeSignal+elemInfo->ChannelIndex[ch],
                   nChannels,
                   psyData[ch].mdctSpectrum,
                   &(mdctScalingArray[ch]),
                   psyData[ch].blockSwitchingControl.windowSequence);
    maxScale = max(maxScale, mdctScalingArray[ch]);
  }

  /* common scaling for all channels */
  for (ch=0; ch<channels; ch++) {
    Word16 scaleDiff = maxScale - mdctScalingArray[ch];

    if (scaleDiff > 0) {
      Word32 *Spectrum = psyData[ch].mdctSpectrum;
	  for(line=0; line<FRAME_LEN_LONG; line++) {
        *Spectrum = (*Spectrum) >> scaleDiff;
		Spectrum++;
      }
    }
    psyData[ch].mdctScale = maxScale;
  }

  for (ch=0; ch<channels; ch++) {

    if(psyData[ch].blockSwitchingControl.windowSequence != SHORT_WINDOW) {
      /* update long block parameter */
	  advancePsychLong(&psyData[ch],
                       &tnsData[ch],
                       hPsyConfLong,
                       &psyOutChannel[ch],
                       pScratchTns,
                       &tnsData[1 - ch],
                       ch);

      /* determine maxSfb */
      for (sfb=hPsyConfLong->sfbCnt-1; sfb>=0; sfb--) {
        for (line=hPsyConfLong->sfbOffset[sfb+1] - 1; line>=hPsyConfLong->sfbOffset[sfb]; line--) {

          if (psyData[ch].mdctSpectrum[line] != 0) break;
        }
        if (line >= hPsyConfLong->sfbOffset[sfb]) break;
      }
      maxSfbPerGroup[ch] = sfb + 1;

      /* Calc bandwise energies for mid and side channel
         Do it only if 2 channels exist */

      if (ch == 1)
        advancePsychLongMS(psyData, hPsyConfLong);
    }
    else {
      advancePsychShort(&psyData[ch],
                        &tnsData[ch],
                        hPsyConfShort,
                        &psyOutChannel[ch],
                        pScratchTns,
                        &tnsData[1 - ch],
                        ch);

      /* Calc bandwise energies for mid and side channel
         Do it only if 2 channels exist */

      if (ch == 1)
        advancePsychShortMS (psyData, hPsyConfShort);
    }
  }

  /* group short data */
  for(ch=0; ch<channels; ch++) {

    if (psyData[ch].blockSwitchingControl.windowSequence == SHORT_WINDOW) {
      groupShortData(psyData[ch].mdctSpectrum,
                     pScratchTns,
                     &psyData[ch].sfbThreshold,
                     &psyData[ch].sfbEnergy,
                     &psyData[ch].sfbEnergyMS,
                     &psyData[ch].sfbSpreadedEnergy,
                     hPsyConfShort->sfbCnt,
                     hPsyConfShort->sfbOffset,
                     hPsyConfShort->sfbMinSnr,
                     psyOutElement->groupedSfbOffset[ch],
                     &maxSfbPerGroup[ch],
                     psyOutElement->groupedSfbMinSnr[ch],
                     psyData[ch].blockSwitchingControl.noOfGroups,
                     psyData[ch].blockSwitchingControl.groupLen);
    }
  }


#if (MAX_CHANNELS>1)
  /*
    stereo Processing
  */
  if (channels == 2) {
    psyOutElement->toolsInfo.msDigest = MS_NONE;
    maxSfbPerGroup[0] = maxSfbPerGroup[1] = max(maxSfbPerGroup[0], maxSfbPerGroup[1]);


    if (psyData[0].blockSwitchingControl.windowSequence != SHORT_WINDOW)
      MsStereoProcessing(psyData[0].sfbEnergy.sfbLong,
                         psyData[1].sfbEnergy.sfbLong,
                         psyData[0].sfbEnergyMS.sfbLong,
                         psyData[1].sfbEnergyMS.sfbLong,
                         psyData[0].mdctSpectrum,
                         psyData[1].mdctSpectrum,
                         psyData[0].sfbThreshold.sfbLong,
                         psyData[1].sfbThreshold.sfbLong,
                         psyData[0].sfbSpreadedEnergy.sfbLong,
                         psyData[1].sfbSpreadedEnergy.sfbLong,
                         (Word16*)&psyOutElement->toolsInfo.msDigest,
                         (Word16*)psyOutElement->toolsInfo.msMask,
                         hPsyConfLong->sfbCnt,
                         hPsyConfLong->sfbCnt,
                         maxSfbPerGroup[0],
                         (const Word16*)hPsyConfLong->sfbOffset);
      else
        MsStereoProcessing(psyData[0].sfbEnergy.sfbLong,
                           psyData[1].sfbEnergy.sfbLong,
                           psyData[0].sfbEnergyMS.sfbLong,
                           psyData[1].sfbEnergyMS.sfbLong,
                           psyData[0].mdctSpectrum,
                           psyData[1].mdctSpectrum,
                           psyData[0].sfbThreshold.sfbLong,
                           psyData[1].sfbThreshold.sfbLong,
                           psyData[0].sfbSpreadedEnergy.sfbLong,
                           psyData[1].sfbSpreadedEnergy.sfbLong,
                           (Word16*)&psyOutElement->toolsInfo.msDigest,
                           (Word16*)psyOutElement->toolsInfo.msMask,
                           psyData[0].blockSwitchingControl.noOfGroups*hPsyConfShort->sfbCnt,
                           hPsyConfShort->sfbCnt,
                           maxSfbPerGroup[0],
                           (const Word16*)psyOutElement->groupedSfbOffset[0]);
  }

#endif /* (MAX_CHANNELS>1) */

  /*
    build output
  */
  for(ch=0;ch<channels;ch++) {

    if (psyData[ch].blockSwitchingControl.windowSequence != SHORT_WINDOW)
      BuildInterface(psyData[ch].mdctSpectrum,
                     psyData[ch].mdctScale,
                     &psyData[ch].sfbThreshold,
                     &psyData[ch].sfbEnergy,
                     &psyData[ch].sfbSpreadedEnergy,
                     psyData[ch].sfbEnergySum,
                     psyData[ch].sfbEnergySumMS,
                     psyData[ch].blockSwitchingControl.windowSequence,
                     blockType2windowShape[psyData[ch].blockSwitchingControl.windowSequence],
                     hPsyConfLong->sfbCnt,
                     hPsyConfLong->sfbOffset,
                     maxSfbPerGroup[ch],
                     hPsyConfLong->sfbMinSnr,
                     psyData[ch].blockSwitchingControl.noOfGroups,
                     psyData[ch].blockSwitchingControl.groupLen,
                     &psyOutChannel[ch]);
    else
      BuildInterface(psyData[ch].mdctSpectrum,
                     psyData[ch].mdctScale,
                     &psyData[ch].sfbThreshold,
                     &psyData[ch].sfbEnergy,
                     &psyData[ch].sfbSpreadedEnergy,
                     psyData[ch].sfbEnergySum,
                     psyData[ch].sfbEnergySumMS,
                     SHORT_WINDOW,
                     SINE_WINDOW,
                     psyData[0].blockSwitchingControl.noOfGroups*hPsyConfShort->sfbCnt,
                     psyOutElement->groupedSfbOffset[ch],
                     maxSfbPerGroup[ch],
                     psyOutElement->groupedSfbMinSnr[ch],
                     psyData[ch].blockSwitchingControl.noOfGroups,
                     psyData[ch].blockSwitchingControl.groupLen,
                     &psyOutChannel[ch]);
  }

  return(0); /* no error */
}

/*****************************************************************************
*
* function name: advancePsychLong
* description:  psychoacoustic for long blocks
*
*****************************************************************************/

static Word16 advancePsychLong(PSY_DATA* psyData,
                               TNS_DATA* tnsData,
                               PSY_CONFIGURATION_LONG *hPsyConfLong,
                               PSY_OUT_CHANNEL* psyOutChannel,
                               Word32 *pScratchTns,
                               const TNS_DATA* tnsData2,
                               const Word16 ch)
{
  Word32 i;
  Word32 normEnergyShift = (psyData->mdctScale + 1) << 1; /* in reference code, mdct spectrum must be multipied with 2, so +1 */
  Word32 clipEnergy = hPsyConfLong->clipEnergy >> normEnergyShift;
  Word32 *data0, *data1, tdata;

  /* low pass */
  data0 = psyData->mdctSpectrum + hPsyConfLong->lowpassLine;
  for(i=hPsyConfLong->lowpassLine; i<FRAME_LEN_LONG; i++) {
    *data0++ = 0;
  }

  /* Calc sfb-bandwise mdct-energies for left and right channel */
  CalcBandEnergy( psyData->mdctSpectrum,
                  hPsyConfLong->sfbOffset,
                  hPsyConfLong->sfbActive,
                  psyData->sfbEnergy.sfbLong,
                  &psyData->sfbEnergySum.sfbLong);

  /*
    TNS detect
  */
  TnsDetect(tnsData,
            hPsyConfLong->tnsConf,
            pScratchTns,
            (const Word16*)hPsyConfLong->sfbOffset,
            psyData->mdctSpectrum,
            0,
            psyData->blockSwitchingControl.windowSequence,
            psyData->sfbEnergy.sfbLong);

  /*  TnsSync */
  if (ch == 1) {
    TnsSync(tnsData,
            tnsData2,
            hPsyConfLong->tnsConf,
            0,
            psyData->blockSwitchingControl.windowSequence);
  }

  /*  Tns Encoder */
  TnsEncode(&psyOutChannel->tnsInfo,
            tnsData,
            hPsyConfLong->sfbCnt,
            hPsyConfLong->tnsConf,
            hPsyConfLong->lowpassLine,
            psyData->mdctSpectrum,
            0,
            psyData->blockSwitchingControl.windowSequence);

  /* first part of threshold calculation */
  data0 = psyData->sfbEnergy.sfbLong;
  data1 = psyData->sfbThreshold.sfbLong;
  for (i=hPsyConfLong->sfbCnt; i; i--) {
    tdata = L_mpy_ls(*data0++, hPsyConfLong->ratio);
    *data1++ = min(tdata, clipEnergy);
  }

  /* Calc sfb-bandwise mdct-energies for left and right channel again */
  if (tnsData->dataRaw.tnsLong.subBlockInfo.tnsActive!=0) {
    Word16 tnsStartBand = hPsyConfLong->tnsConf.tnsStartBand;
    CalcBandEnergy( psyData->mdctSpectrum,
                    hPsyConfLong->sfbOffset+tnsStartBand,
                    hPsyConfLong->sfbActive - tnsStartBand,
                    psyData->sfbEnergy.sfbLong+tnsStartBand,
                    &psyData->sfbEnergySum.sfbLong);

	data0 = psyData->sfbEnergy.sfbLong;
	tdata = psyData->sfbEnergySum.sfbLong;
	for (i=0; i<tnsStartBand; i++)
      tdata += *data0++;

	psyData->sfbEnergySum.sfbLong = tdata;
  }


  /* spreading energy */
  SpreadingMax(hPsyConfLong->sfbCnt,
               hPsyConfLong->sfbMaskLowFactor,
               hPsyConfLong->sfbMaskHighFactor,
               psyData->sfbThreshold.sfbLong);

  /* threshold in quiet */
  data0 = psyData->sfbThreshold.sfbLong;
  data1 = hPsyConfLong->sfbThresholdQuiet;
  for (i=hPsyConfLong->sfbCnt; i; i--)
  {
	  *data0 = max(*data0, (*data1 >> normEnergyShift));
	  data0++; data1++;
  }

  /* preecho control */
  if (psyData->blockSwitchingControl.windowSequence == STOP_WINDOW) {
    data0 = psyData->sfbThresholdnm1;
	for (i=hPsyConfLong->sfbCnt; i; i--) {
      *data0++ = MAX_32;
    }
    psyData->mdctScalenm1 = 0;
  }

  PreEchoControl( psyData->sfbThresholdnm1,
                  hPsyConfLong->sfbCnt,
                  hPsyConfLong->maxAllowedIncreaseFactor,
                  hPsyConfLong->minRemainingThresholdFactor,
                  psyData->sfbThreshold.sfbLong,
                  psyData->mdctScale,
                  psyData->mdctScalenm1);
  psyData->mdctScalenm1 = psyData->mdctScale;


  if (psyData->blockSwitchingControl.windowSequence== START_WINDOW) {
    data0 = psyData->sfbThresholdnm1;
	for (i=hPsyConfLong->sfbCnt; i; i--) {
      *data0++ = MAX_32;
    }
    psyData->mdctScalenm1 = 0;
  }

  /* apply tns mult table on cb thresholds */
  ApplyTnsMultTableToRatios(hPsyConfLong->tnsConf.tnsRatioPatchLowestCb,
                            hPsyConfLong->tnsConf.tnsStartBand,
                            tnsData->dataRaw.tnsLong.subBlockInfo,
                            psyData->sfbThreshold.sfbLong);


  /* spreaded energy */
  data0 = psyData->sfbSpreadedEnergy.sfbLong;
  data1 = psyData->sfbEnergy.sfbLong;
  for (i=hPsyConfLong->sfbCnt; i; i--) {
    //psyData->sfbSpreadedEnergy.sfbLong[i] = psyData->sfbEnergy.sfbLong[i];
	  *data0++ = *data1++;
  }

  /* spreading energy */
  SpreadingMax(hPsyConfLong->sfbCnt,
               hPsyConfLong->sfbMaskLowFactorSprEn,
               hPsyConfLong->sfbMaskHighFactorSprEn,
               psyData->sfbSpreadedEnergy.sfbLong);

  return 0;
}

/*****************************************************************************
*
* function name: advancePsychLongMS
* description:   update mdct-energies for left add or minus right channel
*				for long block
*
*****************************************************************************/
static Word16 advancePsychLongMS (PSY_DATA psyData[MAX_CHANNELS],
                                  const PSY_CONFIGURATION_LONG *hPsyConfLong)
{
  CalcBandEnergyMS(psyData[0].mdctSpectrum,
                   psyData[1].mdctSpectrum,
                   hPsyConfLong->sfbOffset,
                   hPsyConfLong->sfbActive,
                   psyData[0].sfbEnergyMS.sfbLong,
                   &psyData[0].sfbEnergySumMS.sfbLong,
                   psyData[1].sfbEnergyMS.sfbLong,
                   &psyData[1].sfbEnergySumMS.sfbLong);

  return 0;
}


/*****************************************************************************
*
* function name: advancePsychShort
* description:  psychoacoustic for short blocks
*
*****************************************************************************/

static Word16 advancePsychShort(PSY_DATA* psyData,
                                TNS_DATA* tnsData,
                                const PSY_CONFIGURATION_SHORT *hPsyConfShort,
                                PSY_OUT_CHANNEL* psyOutChannel,
                                Word32 *pScratchTns,
                                const TNS_DATA *tnsData2,
                                const Word16 ch)
{
  Word32 w;
  Word32 normEnergyShift = (psyData->mdctScale + 1) << 1; /* in reference code, mdct spectrum must be multipied with 2, so +1 */
  Word32 clipEnergy = hPsyConfShort->clipEnergy >> normEnergyShift;
  Word32 wOffset = 0;
  Word32 *data0;
  const Word32 *data1;

  for(w = 0; w < TRANS_FAC; w++) {
    Word32 i, tdata;

    /* low pass */
    data0 = psyData->mdctSpectrum + wOffset + hPsyConfShort->lowpassLine;
	for(i=hPsyConfShort->lowpassLine; i<FRAME_LEN_SHORT; i++){
      *data0++ = 0;
    }

    /* Calc sfb-bandwise mdct-energies for left and right channel */
    CalcBandEnergy( psyData->mdctSpectrum+wOffset,
                    hPsyConfShort->sfbOffset,
                    hPsyConfShort->sfbActive,
                    psyData->sfbEnergy.sfbShort[w],
                    &psyData->sfbEnergySum.sfbShort[w]);
    /*
       TNS
    */
    TnsDetect(tnsData,
              hPsyConfShort->tnsConf,
              pScratchTns,
              (const Word16*)hPsyConfShort->sfbOffset,
              psyData->mdctSpectrum+wOffset,
              w,
              psyData->blockSwitchingControl.windowSequence,
              psyData->sfbEnergy.sfbShort[w]);

    /*  TnsSync */
    if (ch == 1) {
      TnsSync(tnsData,
              tnsData2,
              hPsyConfShort->tnsConf,
              w,
              psyData->blockSwitchingControl.windowSequence);
    }

    TnsEncode(&psyOutChannel->tnsInfo,
              tnsData,
              hPsyConfShort->sfbCnt,
              hPsyConfShort->tnsConf,
              hPsyConfShort->lowpassLine,
              psyData->mdctSpectrum+wOffset,
              w,
              psyData->blockSwitchingControl.windowSequence);

    /* first part of threshold calculation */
    data0 = psyData->sfbThreshold.sfbShort[w];
	data1 = psyData->sfbEnergy.sfbShort[w];
	for (i=hPsyConfShort->sfbCnt; i; i--) {
      tdata = L_mpy_ls(*data1++, hPsyConfShort->ratio);
      *data0++ = min(tdata, clipEnergy);
    }

    /* Calc sfb-bandwise mdct-energies for left and right channel again */
    if (tnsData->dataRaw.tnsShort.subBlockInfo[w].tnsActive != 0) {
      Word16 tnsStartBand = hPsyConfShort->tnsConf.tnsStartBand;
      CalcBandEnergy( psyData->mdctSpectrum+wOffset,
                      hPsyConfShort->sfbOffset+tnsStartBand,
                      (hPsyConfShort->sfbActive - tnsStartBand),
                      psyData->sfbEnergy.sfbShort[w]+tnsStartBand,
                      &psyData->sfbEnergySum.sfbShort[w]);

      tdata = psyData->sfbEnergySum.sfbShort[w];
	  data0 = psyData->sfbEnergy.sfbShort[w];
	  for (i=tnsStartBand; i; i--)
        tdata += *data0++;

	  psyData->sfbEnergySum.sfbShort[w] = tdata;
    }

    /* spreading */
    SpreadingMax(hPsyConfShort->sfbCnt,
                 hPsyConfShort->sfbMaskLowFactor,
                 hPsyConfShort->sfbMaskHighFactor,
                 psyData->sfbThreshold.sfbShort[w]);


    /* threshold in quiet */
    data0 = psyData->sfbThreshold.sfbShort[w];
	data1 = hPsyConfShort->sfbThresholdQuiet;
	for (i=hPsyConfShort->sfbCnt; i; i--)
    {
		*data0 = max(*data0, (*data1 >> normEnergyShift));

		data0++; data1++;
	}


    /* preecho */
    PreEchoControl( psyData->sfbThresholdnm1,
                    hPsyConfShort->sfbCnt,
                    hPsyConfShort->maxAllowedIncreaseFactor,
                    hPsyConfShort->minRemainingThresholdFactor,
                    psyData->sfbThreshold.sfbShort[w],
                    psyData->mdctScale,
                    w==0 ? psyData->mdctScalenm1 : psyData->mdctScale);

    /* apply tns mult table on cb thresholds */
    ApplyTnsMultTableToRatios( hPsyConfShort->tnsConf.tnsRatioPatchLowestCb,
                               hPsyConfShort->tnsConf.tnsStartBand,
                               tnsData->dataRaw.tnsShort.subBlockInfo[w],
                               psyData->sfbThreshold.sfbShort[w]);

    /* spreaded energy */
    data0 = psyData->sfbSpreadedEnergy.sfbShort[w];
	data1 = psyData->sfbEnergy.sfbShort[w];
	for (i=hPsyConfShort->sfbCnt; i; i--) {
	  *data0++ = *data1++;
    }
    SpreadingMax(hPsyConfShort->sfbCnt,
                 hPsyConfShort->sfbMaskLowFactorSprEn,
                 hPsyConfShort->sfbMaskHighFactorSprEn,
                 psyData->sfbSpreadedEnergy.sfbShort[w]);

    wOffset += FRAME_LEN_SHORT;
  } /* for TRANS_FAC */

  psyData->mdctScalenm1 = psyData->mdctScale;

  return 0;
}

/*****************************************************************************
*
* function name: advancePsychShortMS
* description:   update mdct-energies for left add or minus right channel
*				for short block
*
*****************************************************************************/
static Word16 advancePsychShortMS (PSY_DATA psyData[MAX_CHANNELS],
                                   const PSY_CONFIGURATION_SHORT *hPsyConfShort)
{
  Word32 w, wOffset;
  wOffset = 0;
  for(w=0; w<TRANS_FAC; w++) {
    CalcBandEnergyMS(psyData[0].mdctSpectrum+wOffset,
                     psyData[1].mdctSpectrum+wOffset,
                     hPsyConfShort->sfbOffset,
                     hPsyConfShort->sfbActive,
                     psyData[0].sfbEnergyMS.sfbShort[w],
                     &psyData[0].sfbEnergySumMS.sfbShort[w],
                     psyData[1].sfbEnergyMS.sfbShort[w],
                     &psyData[1].sfbEnergySumMS.sfbShort[w]);
    wOffset += FRAME_LEN_SHORT;
  }

  return 0;
}
