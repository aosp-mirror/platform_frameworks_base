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
	File:		block_switch.c

	Content:	Block switching functions

*******************************************************************************/

#include "typedef.h"
#include "basic_op.h"
#include "oper_32b.h"
#include "psy_const.h"
#include "block_switch.h"


#define ENERGY_SHIFT (8 - 1)

/**************** internal function prototypes ***********/
static Word16
IIRFilter(const Word16 in, const Word32 coeff[], Word32 states[]);

static Word32
SrchMaxWithIndex(const Word32 *in, Word16 *index, Word16 n);


Word32
CalcWindowEnergy(BLOCK_SWITCHING_CONTROL *blockSwitchingControl,
                 Word16 *timeSignal,
                 Word16 chIncrement,
                 Word16 windowLen);



/****************** Constants *****************************/


/*
  IIR high pass coeffs
*/
Word32 hiPassCoeff[BLOCK_SWITCHING_IIR_LEN] = {
  0xbec8b439, 0x609d4952  /* -0.5095f, 0.7548f */
};

static const Word32 accWindowNrgFac = 0x26666666;                   /* factor for accumulating filtered window energies 0.3 */
static const Word32 oneMinusAccWindowNrgFac = 0x5999999a;			/* 0.7 */
static const Word32 invAttackRatioHighBr = 0x0ccccccd;              /* inverted lower ratio limit for attacks 0.1*/
static const Word32 invAttackRatioLowBr =  0x072b020c;              /* 0.056 */
static const Word32 minAttackNrg = 0x00001e84;                      /* minimum energy for attacks 1e+6 */


/****************** Routines ****************************/


/*****************************************************************************
*
* function name: InitBlockSwitching
* description:  init Block Switching parameter.
* returns:      TRUE if success
*
**********************************************************************************/
Word16 InitBlockSwitching(BLOCK_SWITCHING_CONTROL *blockSwitchingControl,
                          const Word32 bitRate, const Word16 nChannels)
{
  /* select attackRatio */

  if ((sub(nChannels,1)==0 && L_sub(bitRate, 24000) > 0) ||
      (sub(nChannels,1)>0 && bitRate > (nChannels * 16000))) {
    blockSwitchingControl->invAttackRatio = invAttackRatioHighBr;
  }
  else  {
    blockSwitchingControl->invAttackRatio = invAttackRatioLowBr;
  }

  return(TRUE);
}

static Word16 suggestedGroupingTable[TRANS_FAC][MAX_NO_OF_GROUPS] = {
  /* Attack in Window 0 */ {1,  3,  3,  1},
  /* Attack in Window 1 */ {1,  1,  3,  3},
  /* Attack in Window 2 */ {2,  1,  3,  2},
  /* Attack in Window 3 */ {3,  1,  3,  1},
  /* Attack in Window 4 */ {3,  1,  1,  3},
  /* Attack in Window 5 */ {3,  2,  1,  2},
  /* Attack in Window 6 */ {3,  3,  1,  1},
  /* Attack in Window 7 */ {3,  3,  1,  1}
};

/*****************************************************************************
*
* function name: BlockSwitching
* description:  detect this frame whether there is an attack
* returns:      TRUE if success
*
**********************************************************************************/
Word16 BlockSwitching(BLOCK_SWITCHING_CONTROL *blockSwitchingControl,
                      Word16 *timeSignal,
					  Word32 sampleRate,
                      Word16 chIncrement)
{
  Word32 i, w;
  Word32 enM1, enMax;

  /* Reset grouping info */
  for (i=0; i<TRANS_FAC; i++) {
    blockSwitchingControl->groupLen[i] = 0;
  }


  /* Search for position and amplitude of attack in last frame (1 windows delay) */
  blockSwitchingControl->maxWindowNrg = SrchMaxWithIndex( &blockSwitchingControl->windowNrg[0][BLOCK_SWITCH_WINDOWS-1],
                                                          &blockSwitchingControl->attackIndex,
                                                          BLOCK_SWITCH_WINDOWS);

  blockSwitchingControl->attackIndex = blockSwitchingControl->lastAttackIndex;

  /* Set grouping info */
  blockSwitchingControl->noOfGroups = MAX_NO_OF_GROUPS;

  for (i=0; i<MAX_NO_OF_GROUPS; i++) {
    blockSwitchingControl->groupLen[i] = suggestedGroupingTable[blockSwitchingControl->attackIndex][i];
  }

  /* if the samplerate is less than 16000, it should be all the short block, avoid pre&post echo */
  if(sampleRate >= 16000) {
	  /* Save current window energy as last window energy */
	  for (w=0; w<BLOCK_SWITCH_WINDOWS; w++) {
		  blockSwitchingControl->windowNrg[0][w] = blockSwitchingControl->windowNrg[1][w];
		  blockSwitchingControl->windowNrgF[0][w] = blockSwitchingControl->windowNrgF[1][w];
	  }


	  /* Calculate unfiltered and filtered energies in subwindows and combine to segments */
	  CalcWindowEnergy(blockSwitchingControl, timeSignal, chIncrement, BLOCK_SWITCH_WINDOW_LEN);

	  /* reset attack */
	  blockSwitchingControl->attack = FALSE;

	  enMax = 0;
	  enM1 = blockSwitchingControl->windowNrgF[0][BLOCK_SWITCH_WINDOWS-1];

	  for (w=0; w<BLOCK_SWITCH_WINDOWS; w++) {
		  Word32 enM1_Tmp, accWindowNrg_Tmp, windowNrgF_Tmp;
		  Word16 enM1_Shf, accWindowNrg_Shf, windowNrgF_Shf;

		  accWindowNrg_Shf = norm_l(blockSwitchingControl->accWindowNrg);
		  enM1_Shf = norm_l(enM1);
		  windowNrgF_Shf = norm_l(blockSwitchingControl->windowNrgF[1][w]);

		  accWindowNrg_Tmp = blockSwitchingControl->accWindowNrg << accWindowNrg_Shf;
		  enM1_Tmp = enM1 << enM1_Shf;
		  windowNrgF_Tmp = blockSwitchingControl->windowNrgF[1][w] << windowNrgF_Shf;

		  /* a sliding average of the previous energies */
		  blockSwitchingControl->accWindowNrg = (fixmul(oneMinusAccWindowNrgFac, accWindowNrg_Tmp) >> accWindowNrg_Shf) +
			  (fixmul(accWindowNrgFac, enM1_Tmp) >> enM1_Shf);


		  /* if the energy with the ratio is bigger than the average, and the attack and short block  */
		  if ((fixmul(windowNrgF_Tmp, blockSwitchingControl->invAttackRatio) >> windowNrgF_Shf) >
			  blockSwitchingControl->accWindowNrg ) {
				  blockSwitchingControl->attack = TRUE;
				  blockSwitchingControl->lastAttackIndex = w;
		  }
		  enM1 = blockSwitchingControl->windowNrgF[1][w];
		  enMax = max(enMax, enM1);
	  }

	  if (enMax < minAttackNrg) {
		  blockSwitchingControl->attack = FALSE;
	  }
  }
  else
  {
	  blockSwitchingControl->attack = TRUE;
  }

  /* Check if attack spreads over frame border */
  if ((!blockSwitchingControl->attack) && (blockSwitchingControl->lastattack)) {

    if (blockSwitchingControl->attackIndex == TRANS_FAC-1) {
      blockSwitchingControl->attack = TRUE;
    }

    blockSwitchingControl->lastattack = FALSE;
  }
  else {
    blockSwitchingControl->lastattack = blockSwitchingControl->attack;
  }

  blockSwitchingControl->windowSequence =  blockSwitchingControl->nextwindowSequence;


  if (blockSwitchingControl->attack) {
    blockSwitchingControl->nextwindowSequence = SHORT_WINDOW;
  }
  else {
    blockSwitchingControl->nextwindowSequence = LONG_WINDOW;
  }

  /* update short block group */
  if (blockSwitchingControl->nextwindowSequence == SHORT_WINDOW) {

    if (blockSwitchingControl->windowSequence== LONG_WINDOW) {
      blockSwitchingControl->windowSequence = START_WINDOW;
    }

    if (blockSwitchingControl->windowSequence == STOP_WINDOW) {
      blockSwitchingControl->windowSequence = SHORT_WINDOW;
      blockSwitchingControl->noOfGroups = 3;
      blockSwitchingControl->groupLen[0] = 3;
      blockSwitchingControl->groupLen[1] = 3;
      blockSwitchingControl->groupLen[2] = 2;
    }
  }

  /* update block type */
  if (blockSwitchingControl->nextwindowSequence == LONG_WINDOW) {

    if (blockSwitchingControl->windowSequence == SHORT_WINDOW) {
      blockSwitchingControl->nextwindowSequence = STOP_WINDOW;
    }
  }

  return(TRUE);
}


/*****************************************************************************
*
* function name: SrchMaxWithIndex
* description:  search for the biggest value in an array
* returns:      the max value
*
**********************************************************************************/
static Word32 SrchMaxWithIndex(const Word32 in[], Word16 *index, Word16 n)
{
  Word32 max;
  Word32 i, idx;

  /* Search maximum value in array and return index and value */
  max = 0;
  idx = 0;

  for (i = 0; i < n; i++) {

    if (in[i+1]  > max) {
      max = in[i+1];
      idx = i;
    }
  }
  *index = idx;

  return(max);
}

/*****************************************************************************
*
* function name: CalcWindowEnergy
* description:  calculate the energy before iir-filter and after irr-filter
* returns:      TRUE if success
*
**********************************************************************************/
#ifndef ARMV5E
Word32 CalcWindowEnergy(BLOCK_SWITCHING_CONTROL *blockSwitchingControl,
                        Word16 *timeSignal,
                        Word16 chIncrement,
                        Word16 windowLen)
{
  Word32 w, i, wOffset, tidx, ch;
  Word32 accuUE, accuFE;
  Word32 tempUnfiltered;
  Word32 tempFiltered;
  Word32 states0, states1;
  Word32 Coeff0, Coeff1;


  states0 = blockSwitchingControl->iirStates[0];
  states1 = blockSwitchingControl->iirStates[1];
  Coeff0 = hiPassCoeff[0];
  Coeff1 = hiPassCoeff[1];
  tidx = 0;
  for (w=0; w < BLOCK_SWITCH_WINDOWS; w++) {

    accuUE = 0;
    accuFE = 0;

    for(i=0; i<windowLen; i++) {
	  Word32 accu1, accu2, accu3;
	  Word32 out;
	  tempUnfiltered = timeSignal[tidx];
      tidx = tidx + chIncrement;

	  accu1 = L_mpy_ls(Coeff1, tempUnfiltered);
	  accu2 = fixmul( Coeff0, states1 );
	  accu3 = accu1 - states0;
	  out = accu3 - accu2;

	  states0 = accu1;
	  states1 = out;

      tempFiltered = extract_h(out);
      accuUE += (tempUnfiltered * tempUnfiltered) >> ENERGY_SHIFT;
      accuFE += (tempFiltered * tempFiltered) >> ENERGY_SHIFT;
    }

    blockSwitchingControl->windowNrg[1][w] = accuUE;
    blockSwitchingControl->windowNrgF[1][w] = accuFE;

  }

  blockSwitchingControl->iirStates[0] = states0;
  blockSwitchingControl->iirStates[1] = states1;

  return(TRUE);
}
#endif

/*****************************************************************************
*
* function name: IIRFilter
* description:  calculate the iir-filter for an array
* returns:      the result after iir-filter
*
**********************************************************************************/
static Word16 IIRFilter(const Word16 in, const Word32 coeff[], Word32 states[])
{
  Word32 accu1, accu2, accu3;
  Word32 out;

  accu1 = L_mpy_ls(coeff[1], in);
  accu3 = accu1 - states[0];
  accu2 = fixmul( coeff[0], states[1] );
  out = accu3 - accu2;

  states[0] = accu1;
  states[1] = out;

  return round16(out);
}


static Word16 synchronizedBlockTypeTable[4][4] = {
  /*                 LONG_WINDOW   START_WINDOW  SHORT_WINDOW  STOP_WINDOW */
  /* LONG_WINDOW  */{LONG_WINDOW,  START_WINDOW, SHORT_WINDOW, STOP_WINDOW},
  /* START_WINDOW */{START_WINDOW, START_WINDOW, SHORT_WINDOW, SHORT_WINDOW},
  /* SHORT_WINDOW */{SHORT_WINDOW, SHORT_WINDOW, SHORT_WINDOW, SHORT_WINDOW},
  /* STOP_WINDOW  */{STOP_WINDOW,  SHORT_WINDOW, SHORT_WINDOW, STOP_WINDOW}
};


/*****************************************************************************
*
* function name: SyncBlockSwitching
* description:  update block type and group value
* returns:      TRUE if success
*
**********************************************************************************/
Word16 SyncBlockSwitching(BLOCK_SWITCHING_CONTROL *blockSwitchingControlLeft,
                          BLOCK_SWITCHING_CONTROL *blockSwitchingControlRight,
                          const Word16 nChannels)
{
  Word16 i;
  Word16 patchType = LONG_WINDOW;


  if (nChannels == 1) { /* Mono */
    if (blockSwitchingControlLeft->windowSequence != SHORT_WINDOW) {
      blockSwitchingControlLeft->noOfGroups = 1;
      blockSwitchingControlLeft->groupLen[0] = 1;

      for (i=1; i<TRANS_FAC; i++) {
        blockSwitchingControlLeft->groupLen[i] = 0;
      }
    }
  }
  else { /* Stereo common Window */
    patchType = synchronizedBlockTypeTable[patchType][blockSwitchingControlLeft->windowSequence];
    patchType = synchronizedBlockTypeTable[patchType][blockSwitchingControlRight->windowSequence];

    /* Set synchronized Blocktype */
    blockSwitchingControlLeft->windowSequence = patchType;
    blockSwitchingControlRight->windowSequence = patchType;

    /* Synchronize grouping info */
    if(patchType != SHORT_WINDOW) { /* Long Blocks */
      /* Set grouping info */
      blockSwitchingControlLeft->noOfGroups = 1;
      blockSwitchingControlRight->noOfGroups = 1;
      blockSwitchingControlLeft->groupLen[0] = 1;
      blockSwitchingControlRight->groupLen[0] = 1;

      for (i=1; i<TRANS_FAC; i++) {
        blockSwitchingControlLeft->groupLen[i] = 0;
        blockSwitchingControlRight->groupLen[i] = 0;
      }
    }
    else {

      if (blockSwitchingControlLeft->maxWindowNrg > blockSwitchingControlRight->maxWindowNrg) {
        /* Left Channel wins */
        blockSwitchingControlRight->noOfGroups = blockSwitchingControlLeft->noOfGroups;
        for (i=0; i<TRANS_FAC; i++) {
          blockSwitchingControlRight->groupLen[i] = blockSwitchingControlLeft->groupLen[i];
        }
      }
      else {
        /* Right Channel wins */
        blockSwitchingControlLeft->noOfGroups = blockSwitchingControlRight->noOfGroups;
        for (i=0; i<TRANS_FAC; i++) {
          blockSwitchingControlLeft->groupLen[i] = blockSwitchingControlRight->groupLen[i];
        }
      }
    }
  } /*endif Mono or Stereo */

  return(TRUE);
}
