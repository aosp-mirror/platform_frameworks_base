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
	File:		aacenc_core.c

	Content:	aac encoder core functions

*******************************************************************************/

#include "typedef.h"
#include "aacenc_core.h"
#include "bitenc.h"

#include "psy_configuration.h"
#include "psy_main.h"
#include "qc_main.h"
#include "psy_main.h"
#include "channel_map.h"
#include "aac_rom.h"

/********************************************************************************
*
* function name: AacInitDefaultConfig
* description:  gives reasonable default configuration
*
**********************************************************************************/
void AacInitDefaultConfig(AACENC_CONFIG *config)
{
  /* default configurations */
  config->adtsUsed        = 1;
  config->nChannelsIn     = 2;
  config->nChannelsOut    = 2;
  config->bitRate         = 128000;
  config->bandWidth       = 0;
}

/********************************************************************************
*
* function name: AacEncOpen
* description:  allocate and initialize a new encoder instance
* returns:      0 if success
*
**********************************************************************************/
Word16  AacEncOpen(  AAC_ENCODER*      hAacEnc,        /* pointer to an encoder handle, initialized on return */
                     const  AACENC_CONFIG     config   /* pre-initialized config struct */
                     )
{
  Word32 i;
  Word32 error = 0;
  Word16 profile = 1;

  ELEMENT_INFO *elInfo = NULL;

  if (hAacEnc==0) {
    error=1;
  }

  if (!error) {
    hAacEnc->config = config;
  }

  if (!error) {
    error = InitElementInfo (config.nChannelsOut,
                             &hAacEnc->elInfo);
  }

  if (!error) {
    elInfo = &hAacEnc->elInfo;
  }

  if (!error) {
    /* use or not tns tool for long and short block */
	 Word16 tnsMask=3;

	/* init encoder psychoacoustic */
    error = psyMainInit(&hAacEnc->psyKernel,
                        config.sampleRate,
                        config.bitRate,
                        elInfo->nChannelsInEl,
                        tnsMask,
                        hAacEnc->config.bandWidth);
  }

 /* use or not adts header */
  if(!error) {
	  hAacEnc->qcOut.qcElement.adtsUsed = config.adtsUsed;
  }

  /* init encoder quantization */
  if (!error) {
    struct QC_INIT qcInit;

    /*qcInit.channelMapping = &hAacEnc->channelMapping;*/
    qcInit.elInfo = &hAacEnc->elInfo;

    qcInit.maxBits = (Word16) (MAXBITS_COEF*elInfo->nChannelsInEl);
    qcInit.bitRes = qcInit.maxBits;
    qcInit.averageBits = (Word16) ((config.bitRate * FRAME_LEN_LONG) / config.sampleRate);

    qcInit.padding.paddingRest = config.sampleRate;

    qcInit.meanPe = (Word16) ((10 * FRAME_LEN_LONG * hAacEnc->config.bandWidth) /
                                              (config.sampleRate>>1));

    qcInit.maxBitFac = (Word16) ((100 * (MAXBITS_COEF-MINBITS_COEF)* elInfo->nChannelsInEl)/
                                                 (qcInit.averageBits?qcInit.averageBits:1));

    qcInit.bitrate = config.bitRate;

    error = QCInit(&hAacEnc->qcKernel, &qcInit);
  }

  /* init bitstream encoder */
  if (!error) {
    hAacEnc->bseInit.nChannels   = elInfo->nChannelsInEl;
    hAacEnc->bseInit.bitrate     = config.bitRate;
    hAacEnc->bseInit.sampleRate  = config.sampleRate;
    hAacEnc->bseInit.profile     = profile;
  }

  return error;
}

/********************************************************************************
*
* function name: AacEncEncode
* description:  encode pcm to aac data core function
* returns:      0 if success
*
**********************************************************************************/
Word16 AacEncEncode(AAC_ENCODER *aacEnc,		/*!< an encoder handle */
                    Word16 *timeSignal,         /*!< BLOCKSIZE*nChannels audio samples, interleaved */
                    const UWord8 *ancBytes,     /*!< pointer to ancillary data bytes */
                    Word16 *numAncBytes,		/*!< number of ancillary Data Bytes */
                    UWord8 *outBytes,           /*!< pointer to output buffer (must be large MINBITS_COEF/8*MAX_CHANNELS bytes) */
                    Word32 *numOutBytes         /*!< number of bytes in output buffer after processing */
                    )
{
  ELEMENT_INFO *elInfo = &aacEnc->elInfo;
  Word16 globUsedBits;
  Word16 ancDataBytes, ancDataBytesLeft;

  ancDataBytes = ancDataBytesLeft = *numAncBytes;

  /* init output aac data buffer and length */
  aacEnc->hBitStream = CreateBitBuffer(&aacEnc->bitStream, outBytes, *numOutBytes);

  /* psychoacoustic process */
  psyMain(aacEnc->config.nChannelsOut,
          elInfo,
          timeSignal,
          &aacEnc->psyKernel.psyData[elInfo->ChannelIndex[0]],
          &aacEnc->psyKernel.tnsData[elInfo->ChannelIndex[0]],
          &aacEnc->psyKernel.psyConfLong,
          &aacEnc->psyKernel.psyConfShort,
          &aacEnc->psyOut.psyOutChannel[elInfo->ChannelIndex[0]],
          &aacEnc->psyOut.psyOutElement,
          aacEnc->psyKernel.pScratchTns,
		  aacEnc->config.sampleRate);

  /* adjust bitrate and frame length */
  AdjustBitrate(&aacEnc->qcKernel,
                aacEnc->config.bitRate,
                aacEnc->config.sampleRate);

  /* quantization and coding process */
  QCMain(&aacEnc->qcKernel,
         &aacEnc->qcKernel.elementBits,
         &aacEnc->qcKernel.adjThr.adjThrStateElem,
         &aacEnc->psyOut.psyOutChannel[elInfo->ChannelIndex[0]],
         &aacEnc->psyOut.psyOutElement,
         &aacEnc->qcOut.qcChannel[elInfo->ChannelIndex[0]],
         &aacEnc->qcOut.qcElement,
         elInfo->nChannelsInEl,
		 min(ancDataBytesLeft,ancDataBytes));

  ancDataBytesLeft = ancDataBytesLeft - ancDataBytes;

  globUsedBits = FinalizeBitConsumption(&aacEnc->qcKernel,
                         &aacEnc->qcOut);

  /* write bitstream process */
  WriteBitstream(aacEnc->hBitStream,
                 *elInfo,
                 &aacEnc->qcOut,
                 &aacEnc->psyOut,
                 &globUsedBits,
                 ancBytes,
				 aacEnc->psyKernel.sampleRateIdx);

  updateBitres(&aacEnc->qcKernel,
               &aacEnc->qcOut);

  /* write out the bitstream */
  *numOutBytes = GetBitsAvail(aacEnc->hBitStream) >> 3;

  return 0;
}


/********************************************************************************
*
* function name:AacEncClose
* description: deallocate an encoder instance
*
**********************************************************************************/
void AacEncClose (AAC_ENCODER* hAacEnc, VO_MEM_OPERATOR *pMemOP)
{
  if (hAacEnc) {
    QCDelete(&hAacEnc->qcKernel, pMemOP);

    QCOutDelete(&hAacEnc->qcOut, pMemOP);

    PsyDelete(&hAacEnc->psyKernel, pMemOP);

    PsyOutDelete(&hAacEnc->psyOut, pMemOP);

    DeleteBitBuffer(&hAacEnc->hBitStream);

	if(hAacEnc->intbuf)
	{
		mem_free(pMemOP, hAacEnc->intbuf, VO_INDEX_ENC_AAC);
		hAacEnc->intbuf = NULL;
	}
  }
}
