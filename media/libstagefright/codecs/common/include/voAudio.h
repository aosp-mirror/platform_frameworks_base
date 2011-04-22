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
	File:		voAudio.h

	Content:	Audio types and functions

*******************************************************************************/

#ifndef __voAudio_H__
#define __voAudio_H__

#ifdef __cplusplus
extern "C" {
#endif /* __cplusplus */

#include "voIndex.h"
#include "voMem.h"

#define	VO_PID_AUDIO_BASE			 0x42000000							/*!< The base param ID for AUDIO codec */
#define	VO_PID_AUDIO_FORMAT			(VO_PID_AUDIO_BASE | 0X0001)		/*!< The format data of audio in track */
#define	VO_PID_AUDIO_SAMPLEREATE	(VO_PID_AUDIO_BASE | 0X0002)		/*!< The sample rate of audio  */
#define	VO_PID_AUDIO_CHANNELS		(VO_PID_AUDIO_BASE | 0X0003)		/*!< The channel of audio */
#define	VO_PID_AUDIO_BITRATE		(VO_PID_AUDIO_BASE | 0X0004)		/*!< The bit rate of audio */
#define VO_PID_AUDIO_CHANNELMODE	(VO_PID_AUDIO_BASE | 0X0005)		/*!< The channel mode of audio */

#define	VO_ERR_AUDIO_BASE			0x82000000
#define VO_ERR_AUDIO_UNSCHANNEL		VO_ERR_AUDIO_BASE | 0x0001
#define VO_ERR_AUDIO_UNSSAMPLERATE	VO_ERR_AUDIO_BASE | 0x0002
#define VO_ERR_AUDIO_UNSFEATURE		VO_ERR_AUDIO_BASE | 0x0003


/**
 *Enumeration used to define the possible audio coding formats.
 */
typedef enum VO_AUDIO_CODINGTYPE {
	VO_AUDIO_CodingUnused = 0,  /**< Placeholder value when coding is N/A  */
	VO_AUDIO_CodingPCM,         /**< Any variant of PCM coding */
	VO_AUDIO_CodingADPCM,       /**< Any variant of ADPCM encoded data */
	VO_AUDIO_CodingAMRNB,       /**< Any variant of AMR encoded data */
	VO_AUDIO_CodingAMRWB,       /**< Any variant of AMR encoded data */
	VO_AUDIO_CodingAMRWBP,      /**< Any variant of AMR encoded data */
	VO_AUDIO_CodingQCELP13,     /**< Any variant of QCELP 13kbps encoded data */
	VO_AUDIO_CodingEVRC,        /**< Any variant of EVRC encoded data */
	VO_AUDIO_CodingAAC,         /**< Any variant of AAC encoded data, 0xA106 - ISO/MPEG-4 AAC, 0xFF - AAC */
	VO_AUDIO_CodingAC3,         /**< Any variant of AC3 encoded data */
	VO_AUDIO_CodingFLAC,        /**< Any variant of FLAC encoded data */
	VO_AUDIO_CodingMP1,			/**< Any variant of MP1 encoded data */
	VO_AUDIO_CodingMP3,         /**< Any variant of MP3 encoded data */
	VO_AUDIO_CodingOGG,         /**< Any variant of OGG encoded data */
	VO_AUDIO_CodingWMA,         /**< Any variant of WMA encoded data */
	VO_AUDIO_CodingRA,          /**< Any variant of RA encoded data */
	VO_AUDIO_CodingMIDI,        /**< Any variant of MIDI encoded data */
	VO_AUDIO_CodingDRA,         /**< Any variant of dra encoded data */
	VO_AUDIO_CodingG729,        /**< Any variant of dra encoded data */
	VO_AUDIO_Coding_MAX		= VO_MAX_ENUM_VALUE
} VO_AUDIO_CODINGTYPE;

/*!
* the channel type value
*/
typedef enum {
	VO_CHANNEL_CENTER				= 1,	/*!<center channel*/
	VO_CHANNEL_FRONT_LEFT			= 1<<1,	/*!<front left channel*/
	VO_CHANNEL_FRONT_RIGHT			= 1<<2,	/*!<front right channel*/
	VO_CHANNEL_SIDE_LEFT  			= 1<<3, /*!<side left channel*/
	VO_CHANNEL_SIDE_RIGHT			= 1<<4, /*!<side right channel*/
	VO_CHANNEL_BACK_LEFT			= 1<<5,	/*!<back left channel*/
	VO_CHANNEL_BACK_RIGHT			= 1<<6,	/*!<back right channel*/
	VO_CHANNEL_BACK_CENTER			= 1<<7,	/*!<back center channel*/
	VO_CHANNEL_LFE_BASS				= 1<<8,	/*!<low-frequency effects bass channel*/
	VO_CHANNEL_ALL					= 0xffff,/*!<[default] include all channels */
	VO_CHANNEL_MAX					= VO_MAX_ENUM_VALUE
} VO_AUDIO_CHANNELTYPE;

/**
 * General audio format info
 */
typedef struct
{
	VO_S32	SampleRate;  /*!< Sample rate */
	VO_S32	Channels;    /*!< Channel count */
	VO_S32	SampleBits;  /*!< Bits per sample */
} VO_AUDIO_FORMAT;

/**
 * General audio output info
 */
typedef struct
{
	VO_AUDIO_FORMAT	Format;			/*!< Sample rate */
	VO_U32			InputUsed;		/*!< Channel count */
	VO_U32			Resever;		/*!< Resevered */
} VO_AUDIO_OUTPUTINFO;

/**
 * General audio codec function set
 */
typedef struct VO_AUDIO_CODECAPI
{
	/**
	 * Init the audio codec module and return codec handle
	 * \param phCodec [OUT] Return the video codec handle
	 * \param vType	[IN] The codec type if the module support multi codec.
	 * \param pUserData	[IN] The init param. It is either a memory operator or an allocated memory
	 * \retval VO_ERR_NONE Succeeded.
	 */
	VO_U32 (VO_API * Init) (VO_HANDLE * phCodec, VO_AUDIO_CODINGTYPE vType, VO_CODEC_INIT_USERDATA * pUserData );

	/**
	 * Set input audio data.
	 * \param hCodec [IN]] The codec handle which was created by Init function.
	 * \param pInput [IN] The input buffer param.
	 * \retval VO_ERR_NONE Succeeded.
	 */
	VO_U32 (VO_API * SetInputData) (VO_HANDLE hCodec, VO_CODECBUFFER * pInput);

	/**
	 * Get the outut audio data
	 * \param hCodec [IN]] The codec handle which was created by Init function.
	 * \param pOutBuffer [OUT] The output audio data
	 * \param pOutInfo [OUT] The codec fills audio format and the input data size used in current call.
	 *						 pOutInfo->InputUsed is total used input data size in byte.
	 * \retval  VO_ERR_NONE Succeeded.
	 *			VO_ERR_INPUT_BUFFER_SMALL. The input was finished or the input data was not enought. Continue to input 
	 *										data before next call.
	 */
	VO_U32 (VO_API * GetOutputData) (VO_HANDLE hCodec, VO_CODECBUFFER * pOutBuffer, VO_AUDIO_OUTPUTINFO * pOutInfo);

	/**
	 * Set the parameter for the specified param ID.
	 * \param hCodec [IN]] The codec handle which was created by Init function.
	 * \param uParamID [IN] The param ID.
	 * \param pData [IN] The param value.
	 * \retval VO_ERR_NONE Succeeded.
	 */
	VO_U32 (VO_API * SetParam) (VO_HANDLE hCodec, VO_S32 uParamID, VO_PTR pData);

	/**
	 * Get the parameter for the specified param ID.
	 * \param hCodec [IN]] The codec handle which was created by Init function.
	 * \param uParamID [IN] The param ID.
	 * \param pData [IN] The param value.
	 * \retval VO_ERR_NONE Succeeded.
	 */
	VO_U32 (VO_API * GetParam) (VO_HANDLE hCodec, VO_S32 uParamID, VO_PTR pData);

	/**
	 * Uninit the Codec.
	 * \param hCodec [IN]] The codec handle which was created by Init function.
	 * \retval VO_ERR_NONE Succeeded.
	 */
	VO_U32 (VO_API * Uninit) (VO_HANDLE hCodec);
} VO_AUDIO_CODECAPI;

#ifdef __cplusplus
}
#endif /* __cplusplus */

#endif // __voAudio_H__
