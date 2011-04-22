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
	File:		voAMRWB.h

	Content:	AMR-WB codec APIs & data types

*******************************************************************************/
#ifndef  __VOAMRWB_H__
#define  __VOAMRWB_H__

#include  "voAudio.h"
#ifdef __cplusplus
extern "C" {
#endif /* __cplusplus */
#pragma pack(push, 4)

/*!* the bit rate the codec supports*/
typedef enum { 
	VOAMRWB_MDNONE		= -1,	/*!< Invalid mode */
	VOAMRWB_MD66		= 0,	/*!< 6.60kbps   */
	VOAMRWB_MD885		= 1,    /*!< 8.85kbps   */       
	VOAMRWB_MD1265		= 2,	/*!< 12.65kbps  */
	VOAMRWB_MD1425		= 3,	/*!< 14.25kbps  */
	VOAMRWB_MD1585		= 4,	/*!< 15.85bps   */
	VOAMRWB_MD1825		= 5,	/*!< 18.25bps   */
	VOAMRWB_MD1985		= 6,	/*!< 19.85kbps  */
	VOAMRWB_MD2305		= 7,    /*!< 23.05kbps  */
	VOAMRWB_MD2385          = 8,    /*!< 23.85kbps> */	
	VOAMRWB_N_MODES 	= 9,	/*!< Invalid mode */
	VOAMRWB_MODE_MAX    = VO_MAX_ENUM_VALUE
	
}VOAMRWBMODE;

/*!* the frame format the codec supports*/
typedef enum {
	VOAMRWB_DEFAULT  	= 0,	/*!< the frame type is the header (defined in RFC3267) + rawdata*/
	/*One word (2-byte) for sync word (0x6b21)*/
	/*One word (2-byte) for frame length N.*/
	/*N words (2-byte) containing N bits (bit 0 = 0x007f, bit 1 = 0x0081).*/
	VOAMRWB_ITU         = 1, 
	/*One word (2-byte) for sync word (0x6b21).*/
	/*One word (2-byte) to indicate the frame type.*/	
	/*One word (2-byte) to indicate the mode.*/
	/*N words  (2-byte) containing N bits (bit 0 = 0xff81, bit 1 = 0x007f).*/
	VOAMRWB_RFC3267		= 2,	/* see RFC 3267 */  
    VOAMRWB_TMAX        = VO_MAX_ENUM_VALUE	
}VOAMRWBFRAMETYPE;


#define    VO_PID_AMRWB_Module							0x42261000 
#define    VO_PID_AMRWB_FORMAT                          (VO_PID_AMRWB_Module | 0x0002)
#define    VO_PID_AMRWB_CHANNELS                        (VO_PID_AMRWB_Module | 0x0003)
#define    VO_PID_AMRWB_SAMPLERATE                      (VO_PID_AMRWB_Module | 0x0004)
#define    VO_PID_AMRWB_FRAMETYPE                       (VO_PID_AMRWB_Module | 0x0005)
#define    VO_PID_AMRWB_MODE                            (VO_PID_AMRWB_Module | 0x0006)
#define    VO_PID_AMRWB_DTX                             (VO_PID_AMRWB_Module | 0x0007)

/**
 * Get audio codec API interface
 * \param pEncHandle [out] Return the AMRWB Encoder handle.
 * \retval VO_ERR_OK Succeeded.
 */
VO_S32 VO_API voGetAMRWBEncAPI(VO_AUDIO_CODECAPI *pEncHandle);


#pragma pack(pop)
#ifdef __cplusplus
} /* extern "C" */
#endif /* __cplusplus */


#endif   //__VOAMRWB_H__

