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
	File:		voAAC.h

	Content:	AAC codec APIs & data types

*******************************************************************************/

#ifndef __voAAC_H__
#define __voAAC_H__

#ifdef __cplusplus
extern "C" {
#endif /* __cplusplus */

#include "voAudio.h"

/*!
 * the frame type that the decoder supports
 */
typedef enum {
	VOAAC_RAWDATA			= 0,	/*!<contains only raw aac data in a frame*/
	VOAAC_ADTS				= 1,	/*!<contains ADTS header + raw AAC data in a frame*/
	VOAAC_FT_MAX			= VO_MAX_ENUM_VALUE
} VOAACFRAMETYPE;

/*!
 * the structure for AAC encoder input parameter
 */
typedef  struct {
  int	  sampleRate;          /*! audio file sample rate */
  int	  bitRate;             /*! encoder bit rate in bits/sec */
  short   nChannels;		   /*! number of channels on input (1,2) */
  short   adtsUsed;			   /*! whether write adts header */
} AACENC_PARAM;

/* AAC Param ID */
#define VO_PID_AAC_Mdoule				0x42211000
#define VO_PID_AAC_ENCPARAM				VO_PID_AAC_Mdoule | 0x0040  /*!< get/set AAC encoder parameter, the parameter is a pointer to AACENC_PARAM */

/* AAC decoder error ID */
#define VO_ERR_AAC_Mdoule				0x82210000
#define VO_ERR_AAC_UNSFILEFORMAT		(VO_ERR_AAC_Mdoule | 0xF001)
#define VO_ERR_AAC_UNSPROFILE			(VO_ERR_AAC_Mdoule | 0xF002)

/**
 * Get audio encoder API interface
 * \param pEncHandle [out] Return the AAC Encoder handle.
 * \retval VO_ERR_OK Succeeded.
 */
VO_S32 VO_API voGetAACEncAPI (VO_AUDIO_CODECAPI * pEncHandle);

#ifdef __cplusplus
}
#endif /* __cplusplus */

#endif // __voAAC_H__



