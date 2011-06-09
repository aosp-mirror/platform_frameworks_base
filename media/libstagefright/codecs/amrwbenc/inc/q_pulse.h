
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
 *                         Q_PULSE.H                                        *
 *--------------------------------------------------------------------------*
 * Coding and decoding of algebraic codebook			            *
 *--------------------------------------------------------------------------*/

#ifndef  __Q_PULSE_H__
#define  __Q_PULSE_H__

#include "typedef.h"

Word32 quant_1p_N1(                        /* (o) return (N+1) bits           */
		Word16 pos,                           /* (i) position of the pulse       */
		Word16 N);                            /* (i) number of bits for position */

Word32 quant_2p_2N1(                       /* (o) return (2*N)+1 bits         */
		Word16 pos1,                          /* (i) position of the pulse 1     */
		Word16 pos2,                          /* (i) position of the pulse 2     */
		Word16 N);                            /* (i) number of bits for position */

Word32 quant_3p_3N1(                       /* (o) return (3*N)+1 bits         */
		Word16 pos1,                          /* (i) position of the pulse 1     */
		Word16 pos2,                          /* (i) position of the pulse 2     */
		Word16 pos3,                          /* (i) position of the pulse 3     */
		Word16 N);                            /* (i) number of bits for position */

Word32 quant_4p_4N1(                       /* (o) return (4*N)+1 bits         */
		Word16 pos1,                          /* (i) position of the pulse 1     */
		Word16 pos2,                          /* (i) position of the pulse 2     */
		Word16 pos3,                          /* (i) position of the pulse 3     */
		Word16 pos4,                          /* (i) position of the pulse 4     */
		Word16 N);                            /* (i) number of bits for position */

Word32 quant_4p_4N(                        /* (o) return 4*N bits             */
		Word16 pos[],                         /* (i) position of the pulse 1..4  */
		Word16 N);                            /* (i) number of bits for position */

Word32 quant_5p_5N(                        /* (o) return 5*N bits             */
		Word16 pos[],                         /* (i) position of the pulse 1..5  */
		Word16 N);                            /* (i) number of bits for position */

Word32 quant_6p_6N_2(                      /* (o) return (6*N)-2 bits         */
		Word16 pos[],                         /* (i) position of the pulse 1..6  */
		Word16 N);                            /* (i) number of bits for position */


#endif //__Q_PULSE_H__

