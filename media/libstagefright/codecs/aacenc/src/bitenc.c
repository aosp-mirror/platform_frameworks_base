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
	File:		bitenc.c

	Content:	Bitstream encoder functions

*******************************************************************************/

#include "bitenc.h"
#include "bit_cnt.h"
#include "dyn_bits.h"
#include "qc_data.h"
#include "interface.h"


static const  Word16 globalGainOffset = 100;
static const  Word16 icsReservedBit   = 0;


/*****************************************************************************
*
* function name: encodeSpectralData
* description:  encode spectral data
* returns:      spectral bits used
*
*****************************************************************************/
static Word32 encodeSpectralData(Word16             *sfbOffset,
                                 SECTION_DATA       *sectionData,
                                 Word16             *quantSpectrum,
                                 HANDLE_BIT_BUF      hBitStream)
{
  Word16 i,sfb;
  Word16 dbgVal;
  SECTION_INFO* psectioninfo;
  dbgVal = GetBitsAvail(hBitStream);                                     

  for(i=0; i<sectionData->noOfSections; i++) {
    psectioninfo = &(sectionData->sectionInfo[i]);
	/*
       huffencode spectral data for this section
    */
    for(sfb=psectioninfo->sfbStart;
        sfb<psectioninfo->sfbStart+psectioninfo->sfbCnt;
        sfb++) {
      codeValues(quantSpectrum+sfbOffset[sfb],
                 sfbOffset[sfb+1] - sfbOffset[sfb],
                 psectioninfo->codeBook,
                 hBitStream);
    }
  }

  return(GetBitsAvail(hBitStream)-dbgVal);
}

/*****************************************************************************
*
* function name:encodeGlobalGain
* description: encodes Global Gain (common scale factor)
* returns:     none
*
*****************************************************************************/
static void encodeGlobalGain(Word16 globalGain,
                             Word16 logNorm,
                             Word16 scalefac,
                             HANDLE_BIT_BUF hBitStream)
{
  WriteBits(hBitStream, ((globalGain - scalefac) + globalGainOffset-(logNorm << 2)), 8);
}


/*****************************************************************************
*
* function name:encodeIcsInfo
* description: encodes Ics Info
* returns:     none
*
*****************************************************************************/

static void encodeIcsInfo(Word16 blockType,
                          Word16 windowShape,
                          Word16 groupingMask,
                          SECTION_DATA *sectionData,
                          HANDLE_BIT_BUF  hBitStream)
{
  WriteBits(hBitStream,icsReservedBit,1);
  WriteBits(hBitStream,blockType,2);
  WriteBits(hBitStream,windowShape,1);

   
  switch(blockType){
    case LONG_WINDOW:
    case START_WINDOW:
    case STOP_WINDOW:
      WriteBits(hBitStream,sectionData->maxSfbPerGroup,6);

      /* No predictor data present */
      WriteBits(hBitStream, 0, 1);
      break;

    case SHORT_WINDOW:
      WriteBits(hBitStream,sectionData->maxSfbPerGroup,4);

      /*
      Write grouping bits
      */
      WriteBits(hBitStream,groupingMask,TRANS_FAC-1);
      break;
  }
}

/*****************************************************************************
*
* function name: encodeSectionData
* description:  encode section data (common Huffman codebooks for adjacent
*               SFB's)
* returns:      none
*
*****************************************************************************/
static Word32 encodeSectionData(SECTION_DATA *sectionData,
                                HANDLE_BIT_BUF hBitStream)
{
  Word16 sectEscapeVal=0,sectLenBits=0;
  Word16 sectLen;
  Word16 i;
  Word16 dbgVal=GetBitsAvail(hBitStream);
       

   
  switch(sectionData->blockType)
  {
    case LONG_WINDOW:
    case START_WINDOW:
    case STOP_WINDOW:
      sectEscapeVal = SECT_ESC_VAL_LONG;                 
      sectLenBits   = SECT_BITS_LONG;                    
      break;

    case SHORT_WINDOW:
      sectEscapeVal = SECT_ESC_VAL_SHORT;                
      sectLenBits   = SECT_BITS_SHORT;                   
      break;
  }

  for(i=0;i<sectionData->noOfSections;i++) {
    WriteBits(hBitStream,sectionData->sectionInfo[i].codeBook,4);
    sectLen = sectionData->sectionInfo[i].sfbCnt;        

    while(sectLen >= sectEscapeVal) {
       
      WriteBits(hBitStream,sectEscapeVal,sectLenBits);
      sectLen = sectLen - sectEscapeVal;
    }
    WriteBits(hBitStream,sectLen,sectLenBits);
  }
  return(GetBitsAvail(hBitStream)-dbgVal);
}

/*****************************************************************************
*
* function name: encodeScaleFactorData
* description:  encode DPCM coded scale factors
* returns:      none
*
*****************************************************************************/
static Word32 encodeScaleFactorData(UWord16        *maxValueInSfb,
                                    SECTION_DATA   *sectionData,
                                    Word16         *scalefac,
                                    HANDLE_BIT_BUF  hBitStream)
{
  Word16 i,j,lastValScf,deltaScf;
  Word16 dbgVal = GetBitsAvail(hBitStream);
  SECTION_INFO* psectioninfo; 

  lastValScf=scalefac[sectionData->firstScf];                    

  for(i=0;i<sectionData->noOfSections;i++){
    psectioninfo = &(sectionData->sectionInfo[i]); 
    if (psectioninfo->codeBook != CODE_BOOK_ZERO_NO){
      for (j=psectioninfo->sfbStart;
           j<psectioninfo->sfbStart+psectioninfo->sfbCnt; j++){
         
        if(maxValueInSfb[j] == 0) {
          deltaScf = 0;                                          
        }
        else {
          deltaScf = lastValScf - scalefac[j];
          lastValScf = scalefac[j];                              
        }
         
        if(codeScalefactorDelta(deltaScf,hBitStream)){
          return(1);
        }
      }
    }

  }
  return(GetBitsAvail(hBitStream)-dbgVal);
}

/*****************************************************************************
*
* function name:encodeMsInfo
* description: encodes MS-Stereo Info
* returns:     none
*
*****************************************************************************/
static void encodeMSInfo(Word16          sfbCnt,
                         Word16          grpSfb,
                         Word16          maxSfb,
                         Word16          msDigest,
                         Word16         *jsFlags,
                         HANDLE_BIT_BUF  hBitStream)
{
  Word16 sfb, sfbOff;

   
  switch(msDigest)
  {
    case MS_NONE:
      WriteBits(hBitStream,SI_MS_MASK_NONE,2);
      break;

    case MS_ALL:
      WriteBits(hBitStream,SI_MS_MASK_ALL,2);
      break;

    case MS_SOME:
      WriteBits(hBitStream,SI_MS_MASK_SOME,2);
      for(sfbOff = 0; sfbOff < sfbCnt; sfbOff+=grpSfb) {
        for(sfb=0; sfb<maxSfb; sfb++) {
             
          if(jsFlags[sfbOff+sfb] & MS_ON) {
            WriteBits(hBitStream,1,1);
          }
          else{
            WriteBits(hBitStream,0,1);
          }
        }
      }
      break;
  }

}

/*****************************************************************************
*
* function name: encodeTnsData
* description:  encode TNS data (filter order, coeffs, ..)
* returns:      none
*
*****************************************************************************/
static void encodeTnsData(TNS_INFO tnsInfo,
                          Word16 blockType,
                          HANDLE_BIT_BUF hBitStream) {
  Word16 i,k;
  Flag tnsPresent;
  Word16 numOfWindows;
  Word16 coefBits;
  Flag isShort;

       
  if (blockType==2) {
    isShort = 1;
    numOfWindows = TRANS_FAC;
  }
  else {
    isShort = 0;
    numOfWindows = 1;
  }

  tnsPresent=0;                                                  
  for (i=0; i<numOfWindows; i++) {
     
    if (tnsInfo.tnsActive[i]) {
      tnsPresent=1;                                              
    }
  }
   
  if (tnsPresent==0) {
    WriteBits(hBitStream,0,1);
  }
  else{ /* there is data to be written*/
    WriteBits(hBitStream,1,1); /*data_present */
    for (i=0; i<numOfWindows; i++) {
       
      WriteBits(hBitStream,tnsInfo.tnsActive[i],(isShort?1:2));
       
      if (tnsInfo.tnsActive[i]) {
         
        WriteBits(hBitStream,((tnsInfo.coefRes[i] - 4)==0?1:0),1);
         
        WriteBits(hBitStream,tnsInfo.length[i],(isShort?4:6));
         
        WriteBits(hBitStream,tnsInfo.order[i],(isShort?3:5));
         
        if (tnsInfo.order[i]){
          WriteBits(hBitStream, FILTER_DIRECTION, 1);
           
          if(tnsInfo.coefRes[i] == 4) {
            coefBits = 3;                                                
            for(k=0; k<tnsInfo.order[i]; k++) {
                 
              if (tnsInfo.coef[i*TNS_MAX_ORDER_SHORT+k] > 3 ||
                  tnsInfo.coef[i*TNS_MAX_ORDER_SHORT+k] < -4) {
                coefBits = 4;                                            
                break;
              }
            }
          }
          else {
            coefBits = 2;                                                
            for(k=0; k<tnsInfo.order[i]; k++) {
                 
              if (tnsInfo.coef[i*TNS_MAX_ORDER_SHORT+k] > 1 ||
                  tnsInfo.coef[i*TNS_MAX_ORDER_SHORT+k] < -2) {
                coefBits = 3;                                            
                break;
              }
            }
          }
          WriteBits(hBitStream, tnsInfo.coefRes[i] - coefBits, 1); /*coef_compres*/
          for (k=0; k<tnsInfo.order[i]; k++ ) {
            static const Word16 rmask[] = {0,1,3,7,15};
             
            WriteBits(hBitStream,tnsInfo.coef[i*TNS_MAX_ORDER_SHORT+k] & rmask[coefBits],coefBits);
          }
        }
      }
    }
  }

}

/*****************************************************************************
*
* function name: encodeGainControlData
* description:  unsupported
* returns:      none
*
*****************************************************************************/
static void encodeGainControlData(HANDLE_BIT_BUF hBitStream)
{
  WriteBits(hBitStream,0,1);
}

/*****************************************************************************
*
* function name: encodePulseData
* description:  not supported yet (dummy)
* returns:      none
*
*****************************************************************************/
static void encodePulseData(HANDLE_BIT_BUF hBitStream)
{
  WriteBits(hBitStream,0,1);
}


/*****************************************************************************
*
* function name: WriteIndividualChannelStream
* description:  management of write process of individual channel stream
* returns:      none
*
*****************************************************************************/
static void
writeIndividualChannelStream(Flag   commonWindow,
                             Word16 mdctScale,
                             Word16 windowShape,
                             Word16 groupingMask,
                             Word16 *sfbOffset,
                             Word16 scf[],
                             UWord16 *maxValueInSfb,
                             Word16 globalGain,
                             Word16 quantSpec[],
                             SECTION_DATA *sectionData,
                             HANDLE_BIT_BUF hBitStream,
                             TNS_INFO tnsInfo)
{
  Word16 logNorm;

  logNorm = LOG_NORM_PCM - (mdctScale + 1);

  encodeGlobalGain(globalGain, logNorm,scf[sectionData->firstScf], hBitStream);

   
  if(!commonWindow) {
    encodeIcsInfo(sectionData->blockType, windowShape, groupingMask, sectionData, hBitStream);
  }

  encodeSectionData(sectionData, hBitStream);

  encodeScaleFactorData(maxValueInSfb,
                        sectionData,
                        scf,
                        hBitStream);

  encodePulseData(hBitStream);

  encodeTnsData(tnsInfo, sectionData->blockType, hBitStream);

  encodeGainControlData(hBitStream);

  encodeSpectralData(sfbOffset,
                     sectionData,
                     quantSpec,
                     hBitStream);

}

/*****************************************************************************
*
* function name: writeSingleChannelElement
* description:  write single channel element to bitstream
* returns:      none
*
*****************************************************************************/
static Word16 writeSingleChannelElement(Word16 instanceTag,
                                        Word16 *sfbOffset,
                                        QC_OUT_CHANNEL* qcOutChannel,
                                        HANDLE_BIT_BUF hBitStream,
                                        TNS_INFO tnsInfo)
{
  WriteBits(hBitStream,ID_SCE,3);
  WriteBits(hBitStream,instanceTag,4);
  writeIndividualChannelStream(0,
                               qcOutChannel->mdctScale,
                               qcOutChannel->windowShape,
                               qcOutChannel->groupingMask,
                               sfbOffset,
                               qcOutChannel->scf,
                               qcOutChannel->maxValueInSfb,
                               qcOutChannel->globalGain,
                               qcOutChannel->quantSpec,
                               &(qcOutChannel->sectionData),
                               hBitStream,
                               tnsInfo
                               );
  return(0);
}



/*****************************************************************************
*
* function name: writeChannelPairElement
* description:
* returns:      none
*
*****************************************************************************/
static Word16 writeChannelPairElement(Word16 instanceTag,
                                      Word16 msDigest,
                                      Word16 msFlags[MAX_GROUPED_SFB],
                                      Word16 *sfbOffset[2],
                                      QC_OUT_CHANNEL qcOutChannel[2],
                                      HANDLE_BIT_BUF hBitStream,
                                      TNS_INFO tnsInfo[2])
{
  WriteBits(hBitStream,ID_CPE,3);
  WriteBits(hBitStream,instanceTag,4);
  WriteBits(hBitStream,1,1); /* common window */

  encodeIcsInfo(qcOutChannel[0].sectionData.blockType,
                qcOutChannel[0].windowShape,
                qcOutChannel[0].groupingMask,
                &(qcOutChannel[0].sectionData),
                hBitStream);

  encodeMSInfo(qcOutChannel[0].sectionData.sfbCnt,
               qcOutChannel[0].sectionData.sfbPerGroup,
               qcOutChannel[0].sectionData.maxSfbPerGroup,
               msDigest,
               msFlags,
               hBitStream);

  writeIndividualChannelStream(1,
                               qcOutChannel[0].mdctScale,
                               qcOutChannel[0].windowShape,
                               qcOutChannel[0].groupingMask,
                               sfbOffset[0],
                               qcOutChannel[0].scf,
                               qcOutChannel[0].maxValueInSfb,
                               qcOutChannel[0].globalGain,
                               qcOutChannel[0].quantSpec,
                               &(qcOutChannel[0].sectionData),
                               hBitStream,
                               tnsInfo[0]);

  writeIndividualChannelStream(1,
                               qcOutChannel[1].mdctScale,
                               qcOutChannel[1].windowShape,
                               qcOutChannel[1].groupingMask,
                               sfbOffset[1],
                               qcOutChannel[1].scf,
                               qcOutChannel[1].maxValueInSfb,
                               qcOutChannel[1].globalGain,
                               qcOutChannel[1].quantSpec,
                               &(qcOutChannel[1].sectionData),
                               hBitStream,
                               tnsInfo[1]);

  return(0);
}



/*****************************************************************************
*
* function name: writeFillElement
* description:  write fill elements to bitstream
* returns:      none
*
*****************************************************************************/
static void writeFillElement( const UWord8 *ancBytes,
                              Word16 totFillBits,
                              HANDLE_BIT_BUF hBitStream)
{
  Word16 i;
  Word16 cnt,esc_count;

  /*
    Write fill Element(s):
    amount of a fill element can be 7+X*8 Bits, X element of [0..270]
  */
    
  while(totFillBits >= (3+4)) {
    cnt = min(((totFillBits - (3+4)) >> 3), ((1<<4)-1));

    WriteBits(hBitStream,ID_FIL,3);
    WriteBits(hBitStream,cnt,4);

    totFillBits = totFillBits - (3+4);

     
    if ((cnt == (1<<4)-1)) {

      esc_count = min( ((totFillBits >> 3) - ((1<<4)-1)), (1<<8)-1);
      WriteBits(hBitStream,esc_count,8);
      totFillBits = (totFillBits - 8);
      cnt = cnt + (esc_count - 1);
    }

    for(i=0;i<cnt;i++) {
       
      if(ancBytes)
        WriteBits(hBitStream, *ancBytes++,8);
      else
        WriteBits(hBitStream,0,8);
      totFillBits = totFillBits - 8;
    }
  }
}

/*****************************************************************************
*
* function name: WriteBitStream
* description:  main function of write bitsteam process
* returns:      0 if success
*
*****************************************************************************/
Word16 WriteBitstream (HANDLE_BIT_BUF hBitStream,
                       ELEMENT_INFO elInfo,
                       QC_OUT *qcOut,
                       PSY_OUT *psyOut,
                       Word16 *globUsedBits,					   
                       const UWord8 *ancBytes,
					   Word16 sampindex
                       ) /* returns error code */
{
  Word16 bitMarkUp;
  Word16 elementUsedBits;
  Word16 frameBits=0;

  /*   struct bitbuffer bsWriteCopy; */
  bitMarkUp = GetBitsAvail(hBitStream); 
  if(qcOut->qcElement.adtsUsed)  /*  write adts header*/
  {
	  WriteBits(hBitStream, 0xFFF, 12); /* 12 bit Syncword */
	  WriteBits(hBitStream, 1, 1); /* ID == 0 for MPEG4 AAC, 1 for MPEG2 AAC */
	  WriteBits(hBitStream, 0, 2); /* layer == 0 */
	  WriteBits(hBitStream, 1, 1); /* protection absent */
	  WriteBits(hBitStream, 1, 2); /* profile */
	  WriteBits(hBitStream, sampindex, 4); /* sampling rate */
	  WriteBits(hBitStream, 0, 1); /* private bit */
	  WriteBits(hBitStream, elInfo.nChannelsInEl, 3); /* ch. config (must be > 0) */
								   /* simply using numChannels only works for
									6 channels or less, else a channel
									configuration should be written */
	  WriteBits(hBitStream, 0, 1); /* original/copy */
	  WriteBits(hBitStream, 0, 1); /* home */	  
	  
	  /* Variable ADTS header */
	  WriteBits(hBitStream, 0, 1); /* copyr. id. bit */
	  WriteBits(hBitStream, 0, 1); /* copyr. id. start */
	  WriteBits(hBitStream, *globUsedBits >> 3, 13);
	  WriteBits(hBitStream, 0x7FF, 11); /* buffer fullness (0x7FF for VBR) */
	  WriteBits(hBitStream, 0, 2); /* raw data blocks (0+1=1) */  
  }

  *globUsedBits=0;                                               

  {

    Word16 *sfbOffset[2];
    TNS_INFO tnsInfo[2];
    elementUsedBits = 0;                                         

    switch (elInfo.elType) {

      case ID_SCE:      /* single channel */
        sfbOffset[0] = psyOut->psyOutChannel[elInfo.ChannelIndex[0]].sfbOffsets;
        tnsInfo[0] = psyOut->psyOutChannel[elInfo.ChannelIndex[0]].tnsInfo;

        writeSingleChannelElement(elInfo.instanceTag,
                                  sfbOffset[0],
                                  &qcOut->qcChannel[elInfo.ChannelIndex[0]],
                                  hBitStream,
                                  tnsInfo[0]);
        break;

      case ID_CPE:     /* channel pair */
        {
          Word16 msDigest;
          Word16 *msFlags = psyOut->psyOutElement.toolsInfo.msMask;
          msDigest = psyOut->psyOutElement.toolsInfo.msDigest;                        
          sfbOffset[0] =
            psyOut->psyOutChannel[elInfo.ChannelIndex[0]].sfbOffsets;
          sfbOffset[1] =
            psyOut->psyOutChannel[elInfo.ChannelIndex[1]].sfbOffsets;

          tnsInfo[0]=
            psyOut->psyOutChannel[elInfo.ChannelIndex[0]].tnsInfo;
          tnsInfo[1]=
            psyOut->psyOutChannel[elInfo.ChannelIndex[1]].tnsInfo;
          writeChannelPairElement(elInfo.instanceTag,
                                  msDigest,
                                  msFlags,
                                  sfbOffset,
                                  &qcOut->qcChannel[elInfo.ChannelIndex[0]],
                                  hBitStream,
                                  tnsInfo);
        }
        break;

      default:
        return(1);

      }   /* switch */

    elementUsedBits = elementUsedBits - bitMarkUp;
    bitMarkUp = GetBitsAvail(hBitStream);
    frameBits = frameBits + elementUsedBits + bitMarkUp;

  }

  writeFillElement(NULL,
                   qcOut->totFillBits, 
                   hBitStream);

  WriteBits(hBitStream,ID_END,3);

  /* byte alignement */
  WriteBits(hBitStream,0, (8 - (hBitStream->cntBits & 7)) & 7);          
  
  *globUsedBits = *globUsedBits- bitMarkUp;
  bitMarkUp = GetBitsAvail(hBitStream);                                  
  *globUsedBits = *globUsedBits + bitMarkUp;
  frameBits = frameBits + *globUsedBits;

   
  if (frameBits !=  (qcOut->totStaticBitsUsed+qcOut->totDynBitsUsed + qcOut->totAncBitsUsed +
                     qcOut->totFillBits + qcOut->alignBits)) {
    return(-1);
  }
  return(0);
}
