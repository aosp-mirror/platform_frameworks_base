
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
 *                         MAIN.H	                                    *
 *--------------------------------------------------------------------------*
 *       Main functions							    *
 *--------------------------------------------------------------------------*/

#ifndef __MAIN_H__
#define __MAIN_H__

void coder(
     Word16 * mode,                        /* input :  used mode                             */
     Word16 speech16k[],                   /* input :  320 new speech samples (at 16 kHz)    */
     Word16 prms[],                        /* output:  output parameters           */
     Word16 * ser_size,                    /* output:  bit rate of the used mode   */
     void *spe_state,                      /* i/o   :  State structure                       */
     Word16 allow_dtx                      /* input :  DTX ON/OFF                            */
);



void Reset_encoder(void *st, Word16 reset_all);


Word16 encoder_homing_frame_test(Word16 input_frame[]);

#endif //__MAIN_H__

