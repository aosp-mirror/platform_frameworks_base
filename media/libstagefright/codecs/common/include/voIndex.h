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
	File:		voIndex.h

	Content:	module and ID definition

*******************************************************************************/

#ifndef __voIndex_H__
#define __voIndex_H__

#ifdef __cplusplus
extern "C" {
#endif /* __cplusplus */

#include "voType.h"

/* Define the module ID */
#define _MAKE_SOURCE_ID(id, name) \
VO_INDEX_SRC_##name = _VO_INDEX_SOURCE | id,

#define _MAKE_CODEC_ID(id, name) \
VO_INDEX_DEC_##name = _VO_INDEX_DEC | id, \
VO_INDEX_ENC_##name = _VO_INDEX_ENC | id,

#define _MAKE_EFFECT_ID(id, name) \
VO_INDEX_EFT_##name = _VO_INDEX_EFFECT | id,

#define _MAKE_SINK_ID(id, name) \
VO_INDEX_SNK_##name = _VO_INDEX_SINK | id,

#define _MAKE_FILTER_ID(id, name) \
VO_INDEX_FLT_##name = _VO_INDEX_FILTER | id,

#define _MAKE_OMX_ID(id, name) \
VO_INDEX_OMX_##name = _VO_INDEX_OMX | id,

#define _MAKE_MFW_ID(id, name) \
VO_INDEX_MFW_##name = _VO_INDEX_MFW | id,

enum
{
	_VO_INDEX_SOURCE		= 0x01000000,
	_VO_INDEX_DEC			= 0x02000000,
	_VO_INDEX_ENC			= 0x03000000,
	_VO_INDEX_EFFECT		= 0x04000000,
	_VO_INDEX_SINK			= 0x05000000,
	_VO_INDEX_FILTER		= 0x06000000,
	_VO_INDEX_OMX			= 0x07000000,
	_VO_INDEX_MFW			= 0x08000000,

	// define file parser modules
	_MAKE_SOURCE_ID (0x010000, MP4)
	_MAKE_SOURCE_ID (0x020000, AVI)
	_MAKE_SOURCE_ID (0x030000, ASF)
	_MAKE_SOURCE_ID (0x040000, REAL)
	_MAKE_SOURCE_ID (0x050000, AUDIO)
	_MAKE_SOURCE_ID (0x060000, FLASH)
	_MAKE_SOURCE_ID (0x070000, OGG)
	_MAKE_SOURCE_ID (0x080000, MKV)

	// define network source modules
	_MAKE_SOURCE_ID (0x110000, RTSP)
	_MAKE_SOURCE_ID (0x120000, HTTP)

	// define CMMB source modules
	_MAKE_SOURCE_ID (0x200000, CMMB)
	_MAKE_SOURCE_ID (0x210000, CMMB_INNO)
	_MAKE_SOURCE_ID (0x220000, CMMB_TELE)
	_MAKE_SOURCE_ID (0x230000, CMMB_SIANO)

	// define DVBT source modules
	_MAKE_SOURCE_ID (0x300000, DVBT)
	_MAKE_SOURCE_ID (0x310000, DVBT_DIBCOM)

	// define other source modules
	_MAKE_SOURCE_ID (0x400000, ID3)

	// define video codec modules
	_MAKE_CODEC_ID (0x010000, H264)
	_MAKE_CODEC_ID (0x020000, MPEG4)
	_MAKE_CODEC_ID (0x030000, H263)
	_MAKE_CODEC_ID (0x040000, S263)
	_MAKE_CODEC_ID (0x050000, RV)
	_MAKE_CODEC_ID (0x060000, WMV)
	_MAKE_CODEC_ID (0x070000, DIVX3)
	_MAKE_CODEC_ID (0x080000, MJPEG)
	_MAKE_CODEC_ID (0x090000, MPEG2)
	_MAKE_CODEC_ID (0x0A0000, VP6)

	// define audio codec modules
	_MAKE_CODEC_ID (0x210000, AAC)
	_MAKE_CODEC_ID (0x220000, MP3)
	_MAKE_CODEC_ID (0x230000, WMA)
	_MAKE_CODEC_ID (0x240000, RA)
	_MAKE_CODEC_ID (0x250000, AMRNB)
	_MAKE_CODEC_ID (0x260000, AMRWB)
	_MAKE_CODEC_ID (0x270000, AMRWBP)
	_MAKE_CODEC_ID (0x280000, QCELP)
	_MAKE_CODEC_ID (0x290000, EVRC)
	_MAKE_CODEC_ID (0x2A0000, ADPCM)
	_MAKE_CODEC_ID (0x2B0000, MIDI)
	_MAKE_CODEC_ID (0x2C0000, AC3)
	_MAKE_CODEC_ID (0x2D0000, FLAC)
	_MAKE_CODEC_ID (0x2E0000, DRA)
	_MAKE_CODEC_ID (0x2F0000, OGG)
	_MAKE_CODEC_ID (0x300000, G729)

	// define image codec modules
	_MAKE_CODEC_ID (0x410000, JPEG)
	_MAKE_CODEC_ID (0x420000, GIF)
	_MAKE_CODEC_ID (0x430000, PNG)
	_MAKE_CODEC_ID (0x440000, TIF)

	// define effect modules
	_MAKE_EFFECT_ID (0x010000, EQ)

	// define sink modules
	_MAKE_SINK_ID (0x010000, VIDEO)
	_MAKE_SINK_ID (0x020000, AUDIO)
	_MAKE_SINK_ID (0x030000, CCRRR)
	_MAKE_SINK_ID (0x040000, CCRRV)

	_MAKE_SINK_ID (0x110000, MP4)
	_MAKE_SINK_ID (0x120000, AVI)
	_MAKE_SINK_ID (0x130000, AFW)

	// define media frame module ID
	_MAKE_MFW_ID (0x010000, VOMMPLAY)
	_MAKE_MFW_ID (0x020000, VOMMREC)
	_MAKE_MFW_ID (0x030000, VOME)
};


/* define the error ID */
#define VO_ERR_NONE						0x00000000
#define VO_ERR_FINISH					0x00000001
#define VO_ERR_BASE						0X80000000
#define VO_ERR_FAILED					0x80000001
#define VO_ERR_OUTOF_MEMORY				0x80000002
#define VO_ERR_NOT_IMPLEMENT			0x80000003
#define VO_ERR_INVALID_ARG				0x80000004
#define VO_ERR_INPUT_BUFFER_SMALL		0x80000005
#define VO_ERR_OUTPUT_BUFFER_SMALL		0x80000006
#define VO_ERR_WRONG_STATUS				0x80000007
#define VO_ERR_WRONG_PARAM_ID			0x80000008
#define VO_ERR_LICENSE_ERROR			0x80000009

/* xxx is the module ID
#define VO_ERR_FAILED					0x8xxx0001
#define VO_ERR_OUTOF_MEMORY				0x8xxx0002
#define VO_ERR_NOT_IMPLEMENT			0x8xxx0003
#define VO_ERR_INVALID_ARG				0x8xxx0004
#define VO_ERR_INPUT_BUFFER_SMALL		0x8xxx0005
#define VO_ERR_OUTPUT_BUFFER_SMALL		0x8xxx0006
#define VO_ERR_WRONG_STATUS				0x8xxx0007
#define VO_ERR_WRONG_PARAM_ID			0x8xxx0008
#define VO_ERR_LICENSE_ERROR			0x8xxx0009
// Module own error ID
#define VO_ERR_Module					0x8xxx0X00
*/
 
#define	VO_PID_COMMON_BASE				 0x40000000						/*!< The base of common param ID */
#define	VO_PID_COMMON_QUERYMEM			(VO_PID_COMMON_BASE | 0X0001)	/*!< Query the memory needed; Reserved. */
#define	VO_PID_COMMON_INPUTTYPE			(VO_PID_COMMON_BASE | 0X0002)	/*!< Set or get the input buffer type. VO_INPUT_TYPE */
#define	VO_PID_COMMON_HASRESOURCE		(VO_PID_COMMON_BASE | 0X0003)	/*!< Query it has resource to be used. VO_U32 *, 1 have, 0 No */
#define	VO_PID_COMMON_HEADDATA			(VO_PID_COMMON_BASE | 0X0004)	/*!< Decoder track header data. VO_CODECBUFFER * */
#define	VO_PID_COMMON_FLUSH				(VO_PID_COMMON_BASE | 0X0005)	/*!< Flush the codec buffer. VO_U32 *, 1 Flush, 0 No * */

/*
// Module Param ID
#define VO_ID_Mdoule					0x0xxx1000
*/

#ifdef __cplusplus
}
#endif /* __cplusplus */

#endif // __voIndex_H__
