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
	File:		grp_data.c

	Content:	Short block grouping function

*******************************************************************************/

#include "basic_op.h"
#include "psy_const.h"
#include "interface.h"
#include "grp_data.h"

/*****************************************************************************
*
* function name: groupShortData
* description:  group short data for next quantization and coding
*
**********************************************************************************/
void
groupShortData(Word32        *mdctSpectrum,
               Word32        *tmpSpectrum,
               SFB_THRESHOLD *sfbThreshold,
               SFB_ENERGY    *sfbEnergy,
               SFB_ENERGY    *sfbEnergyMS,
               SFB_ENERGY    *sfbSpreadedEnergy,
               const Word16   sfbCnt,
               const Word16  *sfbOffset,
               const Word16  *sfbMinSnr,
               Word16        *groupedSfbOffset,
               Word16        *maxSfbPerGroup,
               Word16        *groupedSfbMinSnr,
               const Word16   noOfGroups,
               const Word16  *groupLen)
{
  Word32 i, j;
  Word32 line;
  Word32 sfb;
  Word32 grp;
  Word32 wnd;
  Word32 offset;
  Word32 highestSfb;

  /* for short: regroup and  */
  /* cumulate energies und thresholds group-wise . */
  
  /* calculate sfbCnt */
  highestSfb = 0;                                        
  for (wnd=0; wnd<TRANS_FAC; wnd++) {
    for (sfb=sfbCnt - 1; sfb>=highestSfb; sfb--) {
      for (line=(sfbOffset[sfb + 1] - 1); line>=sfbOffset[sfb]; line--) {
        
        if (mdctSpectrum[wnd*FRAME_LEN_SHORT+line] != 0) break; 
      }
      
      if (line >= sfbOffset[sfb]) break;
    }
    highestSfb = max(highestSfb, sfb);
  }
  
  if (highestSfb < 0) {
    highestSfb = 0;                                      
  }
  *maxSfbPerGroup = highestSfb + 1;

  /* calculate sfbOffset */
  i = 0;                                                 
  offset = 0;                                            
  for (grp = 0; grp < noOfGroups; grp++) {
    for (sfb = 0; sfb < sfbCnt; sfb++) {
      groupedSfbOffset[i] = offset + sfbOffset[sfb] * groupLen[grp];
      i += 1;
    }
    offset += groupLen[grp] * FRAME_LEN_SHORT;
  }
  groupedSfbOffset[i] = FRAME_LEN_LONG;                  
  i += 1;

  /* calculate minSnr */
  i = 0;                                                 
  offset = 0;                                            
  for (grp = 0; grp < noOfGroups; grp++) {
    for (sfb = 0; sfb < sfbCnt; sfb++) {
      groupedSfbMinSnr[i] = sfbMinSnr[sfb];              
      i += 1;
    }
    offset += groupLen[grp] * FRAME_LEN_SHORT;
  }


  /* sum up sfbThresholds */
  wnd = 0;                                                       
  i = 0;                                                         
  for (grp = 0; grp < noOfGroups; grp++) {
    for (sfb = 0; sfb < sfbCnt; sfb++) {
      Word32 thresh = sfbThreshold->sfbShort[wnd][sfb];          
      for (j=1; j<groupLen[grp]; j++) {
        thresh = L_add(thresh, sfbThreshold->sfbShort[wnd+j][sfb]);
      }
      sfbThreshold->sfbLong[i] = thresh;                         
      i += 1;
    }
    wnd += groupLen[grp];
  }

  /* sum up sfbEnergies left/right */
  wnd = 0;                                                       
  i = 0;                                                         
  for (grp = 0; grp < noOfGroups; grp++) {
    for (sfb = 0; sfb < sfbCnt; sfb++) {
      Word32 energy = sfbEnergy->sfbShort[wnd][sfb];             
      for (j=1; j<groupLen[grp]; j++) {
        energy = L_add(energy, sfbEnergy->sfbShort[wnd+j][sfb]);
      }
      sfbEnergy->sfbLong[i] = energy;                            
      i += 1;
    }
    wnd += groupLen[grp];
  }

  /* sum up sfbEnergies mid/side */
  wnd = 0;                                                       
  i = 0;                                                         
  for (grp = 0; grp < noOfGroups; grp++) {
    for (sfb = 0; sfb < sfbCnt; sfb++) {
      Word32 energy = sfbEnergyMS->sfbShort[wnd][sfb];           
      for (j=1; j<groupLen[grp]; j++) {
        energy = L_add(energy, sfbEnergyMS->sfbShort[wnd+j][sfb]);
      }
      sfbEnergyMS->sfbLong[i] = energy;                          
      i += 1;
    }
    wnd += groupLen[grp];
  }

  /* sum up sfbSpreadedEnergies */
  wnd = 0;                                                       
  i = 0;                                                         
  for (grp = 0; grp < noOfGroups; grp++) {
    for (sfb = 0; sfb < sfbCnt; sfb++) {
      Word32 energy = sfbSpreadedEnergy->sfbShort[wnd][sfb];     
      for (j=1; j<groupLen[grp]; j++) {
        energy = L_add(energy, sfbSpreadedEnergy->sfbShort[wnd+j][sfb]);
      }
      sfbSpreadedEnergy->sfbLong[i] = energy;                    
      i += 1;
    }
    wnd += groupLen[grp];
  }

  /* re-group spectrum */
  wnd = 0;                                                       
  i = 0;                                                         
  for (grp = 0; grp < noOfGroups; grp++) {
    for (sfb = 0; sfb < sfbCnt; sfb++) {
      for (j = 0; j < groupLen[grp]; j++) {
        Word16 lineOffset = FRAME_LEN_SHORT * (wnd + j);
        for (line = lineOffset + sfbOffset[sfb]; line < lineOffset + sfbOffset[sfb+1]; line++) {
          tmpSpectrum[i] = mdctSpectrum[line];                   
          i = i + 1;
        }
      }
    }
    wnd += groupLen[grp];
  }

  for(i=0;i<FRAME_LEN_LONG;i+=4) {
    mdctSpectrum[i] = tmpSpectrum[i];  
	mdctSpectrum[i+1] = tmpSpectrum[i+1];  
	mdctSpectrum[i+2] = tmpSpectrum[i+2];  
	mdctSpectrum[i+3] = tmpSpectrum[i+3];  	
  }
}

