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
	File:		aacenc.c

	Content:	aac encoder interface functions

*******************************************************************************/

#include "voAAC.h"
#include "typedef.h"
#include "aacenc_core.h"
#include "aac_rom.h"
#include "cmnMemory.h"
#include "memalign.h"

/**
* Init the audio codec module and return codec handle
* \param phCodec [OUT] Return the video codec handle
* \param vType	[IN] The codec type if the module support multi codec.
* \param pUserData	[IN] The init param. It is memory operator or alloced memory
* \retval VO_ERR_NONE Succeeded.
*/
VO_U32 VO_API voAACEncInit(VO_HANDLE * phCodec,VO_AUDIO_CODINGTYPE vType, VO_CODEC_INIT_USERDATA *pUserData)
{
	AAC_ENCODER*hAacEnc;
	AACENC_CONFIG config;
	int error;

#ifdef USE_DEAULT_MEM
	VO_MEM_OPERATOR voMemoprator;
#endif
	VO_MEM_OPERATOR *pMemOP;
	int interMem;

	interMem = 0;
	error = 0;

	/* init the memory operator */
	if(pUserData == NULL || pUserData->memflag != VO_IMF_USERMEMOPERATOR || pUserData->memData == NULL )
	{
#ifdef USE_DEAULT_MEM
		voMemoprator.Alloc = cmnMemAlloc;
		voMemoprator.Copy = cmnMemCopy;
		voMemoprator.Free = cmnMemFree;
		voMemoprator.Set = cmnMemSet;
		voMemoprator.Check = cmnMemCheck;

		interMem = 1;

		pMemOP = &voMemoprator;
#else
		*phCodec = NULL;
		return VO_ERR_INVALID_ARG;
#endif
	}
	else
	{
		pMemOP = (VO_MEM_OPERATOR *)pUserData->memData;
	}

	/* init the aac encoder handle */
	hAacEnc = (AAC_ENCODER*)mem_malloc(pMemOP, sizeof(AAC_ENCODER), 32, VO_INDEX_ENC_AAC);
	if(NULL == hAacEnc)
	{
		error = 1;
	}

	if(!error)
	{
		/* init the aac encoder intra memory */
		hAacEnc->intbuf = (short *)mem_malloc(pMemOP, AACENC_BLOCKSIZE*MAX_CHANNELS*sizeof(short), 32, VO_INDEX_ENC_AAC);
		if(NULL == hAacEnc->intbuf)
		{
			error = 1;
		}
	}

	if (!error) {
		/* init the aac encoder psychoacoustic */
		error = (PsyNew(&hAacEnc->psyKernel, MAX_CHANNELS, pMemOP) ||
			PsyOutNew(&hAacEnc->psyOut, pMemOP));
	}

	if (!error) {
		/* init the aac encoder quantization elements */
		error = QCOutNew(&hAacEnc->qcOut,MAX_CHANNELS, pMemOP);
	}

	if (!error) {
		/* init the aac encoder quantization state */
		error = QCNew(&hAacEnc->qcKernel, pMemOP);
	}

	/* uninit the aac encoder if error is nozero */
	if(error)
	{
		AacEncClose(hAacEnc, pMemOP);
		if(hAacEnc)
		{
			mem_free(pMemOP, hAacEnc, VO_INDEX_ENC_AAC);
			hAacEnc = NULL;
		}
		*phCodec = NULL;
		return VO_ERR_OUTOF_MEMORY;
	}

	/* init the aac encoder memory operator  */
#ifdef USE_DEAULT_MEM
	if(interMem)
	{
		hAacEnc->voMemoprator.Alloc = cmnMemAlloc;
		hAacEnc->voMemoprator.Copy = cmnMemCopy;
		hAacEnc->voMemoprator.Free = cmnMemFree;
		hAacEnc->voMemoprator.Set = cmnMemSet;
		hAacEnc->voMemoprator.Check = cmnMemCheck;

		pMemOP = &hAacEnc->voMemoprator;
	}
#endif
	/* init the aac encoder default parameter  */
	if(hAacEnc->initOK == 0)
	{
		 AACENC_CONFIG config;
		 config.adtsUsed = 1;
		 config.bitRate = 128000;
		 config.nChannelsIn = 2;
		 config.nChannelsOut = 2;
		 config.sampleRate = 44100;
		 config.bandWidth = 20000;

		 AacEncOpen(hAacEnc, config);
	}

	hAacEnc->voMemop = pMemOP;

	*phCodec = hAacEnc;

	return VO_ERR_NONE;
}

/**
* Set input audio data.
* \param hCodec [IN]] The Codec Handle which was created by Init function.
* \param pInput [IN] The input buffer param.
* \param pOutBuffer [OUT] The output buffer info.
* \retval VO_ERR_NONE Succeeded.
*/
VO_U32 VO_API voAACEncSetInputData(VO_HANDLE hCodec, VO_CODECBUFFER * pInput)
{
	AAC_ENCODER *hAacEnc;
	int  length;

	if(NULL == hCodec || NULL == pInput || NULL == pInput->Buffer)
	{
		return VO_ERR_INVALID_ARG;
	}

	hAacEnc = (AAC_ENCODER *)hCodec;

	/* init input pcm buffer and length*/
	hAacEnc->inbuf = (short *)pInput->Buffer;
	hAacEnc->inlen = pInput->Length / sizeof(short);
	hAacEnc->uselength = 0;

	hAacEnc->encbuf = hAacEnc->inbuf;
	hAacEnc->enclen = hAacEnc->inlen;

	/* rebuild intra pcm buffer and length*/
	if(hAacEnc->intlen)
	{
		length = min(hAacEnc->config.nChannelsIn*AACENC_BLOCKSIZE - hAacEnc->intlen, hAacEnc->inlen);
		hAacEnc->voMemop->Copy(VO_INDEX_ENC_AAC, hAacEnc->intbuf + hAacEnc->intlen,
			hAacEnc->inbuf, length*sizeof(short));

		hAacEnc->encbuf = hAacEnc->intbuf;
		hAacEnc->enclen = hAacEnc->intlen + length;

		hAacEnc->inbuf += length;
		hAacEnc->inlen -= length;
	}

	return VO_ERR_NONE;
}

/**
* Get the outut audio data
* \param hCodec [IN]] The Codec Handle which was created by Init function.
* \param pOutBuffer [OUT] The output audio data
* \param pOutInfo [OUT] The dec module filled audio format and used the input size.
*						 pOutInfo->InputUsed is total used the input size.
* \retval  VO_ERR_NONE Succeeded.
*			VO_ERR_INPUT_BUFFER_SMALL. The input was finished or the input data was not enought.
*/
VO_U32 VO_API voAACEncGetOutputData(VO_HANDLE hCodec, VO_CODECBUFFER * pOutput, VO_AUDIO_OUTPUTINFO * pOutInfo)
{
	AAC_ENCODER* hAacEnc = (AAC_ENCODER*)hCodec;
	Word16 numAncDataBytes=0;
	Word32  inbuflen;
	int ret, length;
	if(NULL == hAacEnc)
		return VO_ERR_INVALID_ARG;

	 inbuflen = AACENC_BLOCKSIZE*hAacEnc->config.nChannelsIn;

	 /* check the input pcm buffer and length*/
	 if(NULL == hAacEnc->encbuf || hAacEnc->enclen < inbuflen)
	 {
		length = hAacEnc->enclen;
		if(hAacEnc->intlen == 0)
		{
			hAacEnc->voMemop->Copy(VO_INDEX_ENC_AAC, hAacEnc->intbuf,
				hAacEnc->encbuf, length*sizeof(short));
			hAacEnc->uselength += length*sizeof(short);
		}
		else
		{
			hAacEnc->uselength += (length - hAacEnc->intlen)*sizeof(short);
		}

		hAacEnc->intlen = length;

		pOutput->Length = 0;
		if(pOutInfo)
			pOutInfo->InputUsed = hAacEnc->uselength;
		return VO_ERR_INPUT_BUFFER_SMALL;
	 }

	 /* check the output aac buffer and length*/
	 if(NULL == pOutput || NULL == pOutput->Buffer || pOutput->Length < (6144/8)*hAacEnc->config.nChannelsOut/(sizeof(Word32)))
		 return VO_ERR_OUTPUT_BUFFER_SMALL;

	 /* aac encoder core function */
	 AacEncEncode( hAacEnc,
			(Word16*)hAacEnc->encbuf,
			NULL,
			&numAncDataBytes,
			pOutput->Buffer,
			&pOutput->Length);

	 /* update the input pcm buffer and length*/
	 if(hAacEnc->intlen)
	 {
		length = inbuflen - hAacEnc->intlen;
		hAacEnc->encbuf = hAacEnc->inbuf;
		hAacEnc->enclen = hAacEnc->inlen;
		hAacEnc->uselength += length*sizeof(short);
		hAacEnc->intlen = 0;
	 }
	 else
	 {
		 hAacEnc->encbuf = hAacEnc->encbuf + inbuflen;
		 hAacEnc->enclen = hAacEnc->enclen - inbuflen;
		 hAacEnc->uselength += inbuflen*sizeof(short);
	 }

	 /* update the output aac information */
	if(pOutInfo)
	{
		pOutInfo->Format.Channels = hAacEnc->config.nChannelsOut;
		pOutInfo->Format.SampleRate = hAacEnc->config.sampleRate;
		pOutInfo->Format.SampleBits = 16;
		pOutInfo->InputUsed = hAacEnc->uselength;
	}

	 return VO_ERR_NONE;
}

/**
* Uninit the Codec.
* \param hCodec [IN]] The Codec Handle which was created by Init function.
* \retval VO_ERR_NONE Succeeded.
*/
VO_U32 VO_API voAACEncUninit(VO_HANDLE hCodec)
{
	AAC_ENCODER* hAacEnc = (AAC_ENCODER*)hCodec;

	if(NULL != hAacEnc)
	{
		/* close the aac encoder */
		AacEncClose(hAacEnc, hAacEnc->voMemop);

		/* free the aac encoder handle*/
		mem_free(hAacEnc->voMemop, hAacEnc, VO_INDEX_ENC_AAC);
		hAacEnc = NULL;
	}

	return VO_ERR_NONE;
}

/**
* Set the param for special target.
* \param hCodec [IN]] The Codec Handle which was created by Init function.
* \param uParamID [IN] The param ID.
* \param pData [IN] The param value depend on the ID>
* \retval VO_ERR_NONE Succeeded.
*/
VO_U32 VO_API voAACEncSetParam(VO_HANDLE hCodec, VO_S32 uParamID, VO_PTR pData)
{
	AACENC_CONFIG config;
	AACENC_PARAM* pAAC_param;
	VO_AUDIO_FORMAT *pWAV_Format;
	AAC_ENCODER* hAacEnc = (AAC_ENCODER*)hCodec;
	int ret, i, bitrate, tmp;
	int SampleRateIdx;

	if(NULL == hAacEnc)
		return VO_ERR_INVALID_ARG;

	switch(uParamID)
	{
	case VO_PID_AAC_ENCPARAM:  /* init aac encoder parameter*/
		AacInitDefaultConfig(&config);
		if(pData == NULL)
			return VO_ERR_INVALID_ARG;
		pAAC_param = (AACENC_PARAM*)pData;
		config.adtsUsed = pAAC_param->adtsUsed;
		config.bitRate = pAAC_param->bitRate;
		config.nChannelsIn = pAAC_param->nChannels;
		config.nChannelsOut = pAAC_param->nChannels;
		config.sampleRate = pAAC_param->sampleRate;

		/* check the channel */
		if(config.nChannelsIn< 1  || config.nChannelsIn > MAX_CHANNELS  ||
             config.nChannelsOut < 1 || config.nChannelsOut > MAX_CHANNELS || config.nChannelsIn < config.nChannelsOut)
			 return VO_ERR_AUDIO_UNSCHANNEL;

		/* check the samplerate */
		ret = -1;
		for(i = 0; i < NUM_SAMPLE_RATES; i++)
		{
			if(config.sampleRate == sampRateTab[i])
			{
				ret = 0;
				break;
			}
		}
		if(ret < 0)
			return VO_ERR_AUDIO_UNSSAMPLERATE;

		SampleRateIdx = i;

		tmp = 441;
		if(config.sampleRate%8000 == 0)
			tmp =480;
		/* check the bitrate */
		if(config.bitRate!=0 && (config.bitRate/config.nChannelsOut < 4000) ||
           (config.bitRate/config.nChannelsOut > 160000) ||
		   (config.bitRate > config.sampleRate*6*config.nChannelsOut))
		{
			config.bitRate = 640*config.sampleRate/tmp*config.nChannelsOut;

			if(config.bitRate/config.nChannelsOut < 4000)
				config.bitRate = 4000 * config.nChannelsOut;
			else if(config.bitRate > config.sampleRate*6*config.nChannelsOut)
				config.bitRate = config.sampleRate*6*config.nChannelsOut;
			else if(config.bitRate/config.nChannelsOut > 160000)
				config.bitRate = config.nChannelsOut*160000;
		}

		/* check the bandwidth */
		bitrate = config.bitRate / config.nChannelsOut;
		bitrate = bitrate * tmp / config.sampleRate;

		for (i = 0; rates[i]; i++)
		{
			if (rates[i] >= bitrate)
				break;
		}

		config.bandWidth = BandwithCoefTab[i][SampleRateIdx];

		/* init aac encoder core */
		ret = AacEncOpen(hAacEnc, config);
		if(ret)
			return VO_ERR_AUDIO_UNSFEATURE;
		break;
	case VO_PID_AUDIO_FORMAT:	/* init pcm channel and samplerate*/
		AacInitDefaultConfig(&config);
		if(pData == NULL)
			return VO_ERR_INVALID_ARG;
		pWAV_Format = (VO_AUDIO_FORMAT*)pData;
		config.adtsUsed = 1;
		config.nChannelsIn = pWAV_Format->Channels;
		config.nChannelsOut = pWAV_Format->Channels;
		config.sampleRate = pWAV_Format->SampleRate;

		/* check the channel */
		if(config.nChannelsIn< 1  || config.nChannelsIn > MAX_CHANNELS  ||
             config.nChannelsOut < 1 || config.nChannelsOut > MAX_CHANNELS || config.nChannelsIn < config.nChannelsOut)
			 return VO_ERR_AUDIO_UNSCHANNEL;

		/* check the samplebits */
		if(pWAV_Format->SampleBits != 16)
		{
			return VO_ERR_AUDIO_UNSFEATURE;
		}

		/* check the samplerate */
		ret = -1;
		for(i = 0; i < NUM_SAMPLE_RATES; i++)
		{
			if(config.sampleRate == sampRateTab[i])
			{
				ret = 0;
				break;
			}
		}
		if(ret < 0)
			return VO_ERR_AUDIO_UNSSAMPLERATE;

		SampleRateIdx = i;

		/* update the bitrates */
		tmp = 441;
		if(config.sampleRate%8000 == 0)
			tmp =480;

		config.bitRate = 640*config.sampleRate/tmp*config.nChannelsOut;

		if(config.bitRate/config.nChannelsOut < 4000)
			config.bitRate = 4000 * config.nChannelsOut;
		else if(config.bitRate > config.sampleRate*6*config.nChannelsOut)
			config.bitRate = config.sampleRate*6*config.nChannelsOut;
		else if(config.bitRate/config.nChannelsOut > 160000)
			config.bitRate = config.nChannelsOut*160000;

		/* check the bandwidth */
		bitrate = config.bitRate / config.nChannelsOut;
		bitrate = bitrate * tmp / config.sampleRate;

		for (i = 0; rates[i]; i++)
		{
			if (rates[i] >= bitrate)
				break;
		}

		config.bandWidth = BandwithCoefTab[i][SampleRateIdx];

		/* init aac encoder core */
		ret = AacEncOpen(hAacEnc, config);
		if(ret)
			return VO_ERR_AUDIO_UNSFEATURE;
		break;
	default:
		return VO_ERR_WRONG_PARAM_ID;
	}

	return VO_ERR_NONE;
}

/**
* Get the param for special target.
* \param hCodec [IN]] The Codec Handle which was created by Init function.
* \param uParamID [IN] The param ID.
* \param pData [IN] The param value depend on the ID>
* \retval VO_ERR_NONE Succeeded.
*/
VO_U32 VO_API voAACEncGetParam(VO_HANDLE hCodec, VO_S32 uParamID, VO_PTR pData)
{
	return VO_ERR_NONE;
}

/**
 * Get audio codec API interface
 * \param pEncHandle [out] Return the AAC Encoder handle.
 * \retval VO_ERR_OK Succeeded.
 */
VO_S32 VO_API voGetAACEncAPI(VO_AUDIO_CODECAPI * pDecHandle)
{
	if(pDecHandle == NULL)
		return VO_ERR_INVALID_ARG;

	pDecHandle->Init = voAACEncInit;
	pDecHandle->SetInputData = voAACEncSetInputData;
	pDecHandle->GetOutputData = voAACEncGetOutputData;
	pDecHandle->SetParam = voAACEncSetParam;
	pDecHandle->GetParam = voAACEncGetParam;
	pDecHandle->Uninit = voAACEncUninit;

	return VO_ERR_NONE;
}
