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


/*-------------------------------------------------------------------*
 *                         WB_VAD.H                                  *
 *-------------------------------------------------------------------*
 * Functions and static memory for Voice Activity Detection.         *
 *-------------------------------------------------------------------*/

#ifndef __WB_VAD_H__
#define __WB_VAD_H__

/******************************************************************************
 *                         INCLUDE FILES
 ******************************************************************************/
#include "typedef.h"
#include "wb_vad_c.h"
#include "voMem.h"

/******************************************************************************
 *                         DEFINITION OF DATA TYPES
 ******************************************************************************/

typedef struct
{
	Word16 bckr_est[COMPLEN];              /* background noise estimate                */
	Word16 ave_level[COMPLEN];             /* averaged input components for stationary */
	/* estimation                               */
	Word16 old_level[COMPLEN];             /* input levels of the previous frame       */
	Word16 sub_level[COMPLEN];             /* input levels calculated at the end of a frame (lookahead)  */
	Word16 a_data5[F_5TH_CNT][2];          /* memory for the filter bank               */
	Word16 a_data3[F_3TH_CNT];             /* memory for the filter bank               */

	Word16 burst_count;                    /* counts length of a speech burst          */
	Word16 hang_count;                     /* hangover counter                         */
	Word16 stat_count;                     /* stationary counter                       */

	/* Note that each of the following two variables holds 15 flags. Each flag reserves 1 bit of the
	 * variable. The newest flag is in the bit 15 (assuming that LSB is bit 1 and MSB is bit 16). */
	Word16 vadreg;                         /* flags for intermediate VAD decisions     */
	Word16 tone_flag;                      /* tone detection flags                     */

	Word16 sp_est_cnt;                     /* counter for speech level estimation      */
	Word16 sp_max;                         /* maximum level                            */
	Word16 sp_max_cnt;                     /* counts frames that contains speech       */
	Word16 speech_level;                   /* estimated speech level                   */
	Word32 prev_pow_sum;                   /* power of previous frame                  */

} VadVars;

/********************************************************************************
 *
 * DECLARATION OF PROTOTYPES
 ********************************************************************************/

Word16 wb_vad_init(VadVars ** st, VO_MEM_OPERATOR *pMemOP);
Word16 wb_vad_reset(VadVars * st);
void wb_vad_exit(VadVars ** st, VO_MEM_OPERATOR *pMemOP);
void wb_vad_tone_detection(VadVars * st, Word16 p_gain);
Word16 wb_vad(VadVars * st, Word16 in_buf[]);

#endif  //__WB_VAD_H__


