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
	File:		spreading.c

	Content:	Spreading of energy function

*******************************************************************************/

#include "basic_op.h"
#include "oper_32b.h"
#include "spreading.h"

/*********************************************************************************
*
* function name: SpreadingMax
* description:  spreading the energy
*				 higher frequencies thr(n) = max(thr(n), sh(n)*thr(n-1))
*				 lower frequencies  thr(n) = max(thr(n), sl(n)*thr(n+1))
*
**********************************************************************************/
void SpreadingMax(const Word16 pbCnt,
                  const Word16 *maskLowFactor,
                  const Word16 *maskHighFactor,
                  Word32       *pbSpreadedEnergy)
{
  Word32 i;

  /* slope to higher frequencies */
  for (i=1; i<pbCnt; i++) {
    pbSpreadedEnergy[i] = max(pbSpreadedEnergy[i],
                                L_mpy_ls(pbSpreadedEnergy[i-1], maskHighFactor[i]));
  }
  /* slope to lower frequencies */
  for (i=pbCnt - 2; i>=0; i--) {
    pbSpreadedEnergy[i] = max(pbSpreadedEnergy[i],
                                L_mpy_ls(pbSpreadedEnergy[i+1], maskLowFactor[i]));
  }
}
