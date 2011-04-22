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

/*--------------------------------------------------------------------------*
 *                         P_MED_O.H                                        *
 *--------------------------------------------------------------------------*
 *       Median open-loop lag search				            *
 *--------------------------------------------------------------------------*/

#ifndef __P_MED_O_H__
#define __P_MED_O_H__

Word16 Pitch_med_ol(                       /* output: open loop pitch lag                        */
		Word16 wsp[],                         /* input : signal used to compute the open loop pitch */
		/* wsp[-pit_max] to wsp[-1] should be known   */
		Word16 L_min,                         /* input : minimum pitch lag                          */
		Word16 L_max,                         /* input : maximum pitch lag                          */
		Word16 L_frame,                       /* input : length of frame to compute pitch           */
		Word16 L_0,                           /* input : old_ open-loop pitch                       */
		Word16 * gain,                        /* output: normalize correlation of hp_wsp for the Lag */
		Word16 * hp_wsp_mem,                  /* i:o   : memory of the hypass filter for hp_wsp[] (lg=9)   */
		Word16 * old_hp_wsp,                  /* i:o   : hypass wsp[]                               */
		Word16 wght_flg                       /* input : is weighting function used                 */
		);

Word16 Med_olag(                           /* output : median of  5 previous open-loop lags       */
		Word16 prev_ol_lag,                   /* input  : previous open-loop lag                     */
		Word16 old_ol_lag[5]
	       );

void Hp_wsp(
		Word16 wsp[],                         /* i   : wsp[]  signal       */
		Word16 hp_wsp[],                      /* o   : hypass wsp[]        */
		Word16 lg,                            /* i   : lenght of signal    */
		Word16 mem[]                          /* i/o : filter memory [9]   */
	   );

#endif  //__P_MED_O_H__

