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
	File:		bit_cnt.c

	Content:	Huffman Bitcounter & coder functions

*******************************************************************************/

#include "bit_cnt.h"
#include "aac_rom.h"

#define HI_LTAB(a) (a>>8)
#define LO_LTAB(a) (a & 0xff)

#define EXPAND(a)  ((((Word32)(a&0xff00)) << 8)|(Word32)(a&0xff)) 


/*****************************************************************************
*
* function name: count1_2_3_4_5_6_7_8_9_10_11
* description:  counts tables 1-11 
* returns:      
* input:        quantized spectrum
* output:       bitCount for tables 1-11
*
*****************************************************************************/

static void count1_2_3_4_5_6_7_8_9_10_11(const Word16 *values,
                                         const Word16  width,
                                         Word16       *bitCount)
{
  Word32 t0,t1,t2,t3,i;
  Word32 bc1_2,bc3_4,bc5_6,bc7_8,bc9_10;
  Word16 bc11,sc;
  
  bc1_2=0;                               
  bc3_4=0;                               
  bc5_6=0;                               
  bc7_8=0;                               
  bc9_10=0;                              
  bc11=0;                                
  sc=0;                                  

  for(i=0;i<width;i+=4){
    
    t0= values[i+0];                     
    t1= values[i+1];                     
    t2= values[i+2];                     
    t3= values[i+3];                     
  
    /* 1,2 */

    bc1_2 = bc1_2 + EXPAND(huff_ltab1_2[t0+1][t1+1][t2+1][t3+1]);              

    /* 5,6 */
    bc5_6 = bc5_6 + EXPAND(huff_ltab5_6[t0+4][t1+4]);                          
    bc5_6 = bc5_6 + EXPAND(huff_ltab5_6[t2+4][t3+4]);                          

    t0=ABS(t0);
    t1=ABS(t1);
    t2=ABS(t2);
    t3=ABS(t3);

    
    bc3_4 = bc3_4 + EXPAND(huff_ltab3_4[t0][t1][t2][t3]);                      
    
    bc7_8 = bc7_8 + EXPAND(huff_ltab7_8[t0][t1]);                              
    bc7_8 = bc7_8 + EXPAND(huff_ltab7_8[t2][t3]);                              
    
    bc9_10 = bc9_10 + EXPAND(huff_ltab9_10[t0][t1]);                           
    bc9_10 = bc9_10 + EXPAND(huff_ltab9_10[t2][t3]);                           
    
    bc11 = bc11 + huff_ltab11[t0][t1];
    bc11 = bc11 + huff_ltab11[t2][t3];
   
           
    sc = sc + (t0>0) + (t1>0) + (t2>0) + (t3>0);
  }
  
  bitCount[1]=extract_h(bc1_2);
  bitCount[2]=extract_l(bc1_2);
  bitCount[3]=extract_h(bc3_4) + sc;
  bitCount[4]=extract_l(bc3_4) + sc;
  bitCount[5]=extract_h(bc5_6);
  bitCount[6]=extract_l(bc5_6);
  bitCount[7]=extract_h(bc7_8) + sc;
  bitCount[8]=extract_l(bc7_8) + sc;
  bitCount[9]=extract_h(bc9_10) + sc;
  bitCount[10]=extract_l(bc9_10) + sc;
  bitCount[11]=bc11 + sc;
}


/*****************************************************************************
*
* function name: count3_4_5_6_7_8_9_10_11
* description:  counts tables 3-11 
* returns:      
* input:        quantized spectrum
* output:       bitCount for tables 3-11
*
*****************************************************************************/

static void count3_4_5_6_7_8_9_10_11(const Word16 *values,
                                     const Word16  width,
                                     Word16       *bitCount)
{
  Word32 t0,t1,t2,t3, i;
  Word32 bc3_4,bc5_6,bc7_8,bc9_10;
  Word16 bc11,sc;
    
  bc3_4=0;                               
  bc5_6=0;                               
  bc7_8=0;                               
  bc9_10=0;                              
  bc11=0;                                
  sc=0;                                  

  for(i=0;i<width;i+=4){

    t0= values[i+0];                     
    t1= values[i+1];                     
    t2= values[i+2];                     
    t3= values[i+3];                     
    
    /*
      5,6
    */
    bc5_6 = bc5_6 + EXPAND(huff_ltab5_6[t0+4][t1+4]);                          
    bc5_6 = bc5_6 + EXPAND(huff_ltab5_6[t2+4][t3+4]);                          

    t0=ABS(t0);
    t1=ABS(t1);
    t2=ABS(t2);
    t3=ABS(t3);


    bc3_4 = bc3_4 + EXPAND(huff_ltab3_4[t0][t1][t2][t3]);                      
                                                                                                                
    bc7_8 = bc7_8 + EXPAND(huff_ltab7_8[t0][t1]);                              
    bc7_8 = bc7_8 + EXPAND(huff_ltab7_8[t2][t3]);                              
    
    bc9_10 = bc9_10 + EXPAND(huff_ltab9_10[t0][t1]);                           
    bc9_10 = bc9_10 + EXPAND(huff_ltab9_10[t2][t3]);                           
                                                                                                                
    bc11 = bc11 + huff_ltab11[t0][t1];
    bc11 = bc11 + huff_ltab11[t2][t3];

           
    sc = sc + (t0>0) + (t1>0) + (t2>0) + (t3>0);   
  }
  
  bitCount[1]=INVALID_BITCOUNT;                          
  bitCount[2]=INVALID_BITCOUNT;                          
  bitCount[3]=extract_h(bc3_4) + sc;
  bitCount[4]=extract_l(bc3_4) + sc;
  bitCount[5]=extract_h(bc5_6);
  bitCount[6]=extract_l(bc5_6);
  bitCount[7]=extract_h(bc7_8) + sc;
  bitCount[8]=extract_l(bc7_8) + sc;
  bitCount[9]=extract_h(bc9_10) + sc;
  bitCount[10]=extract_l(bc9_10) + sc;
  bitCount[11]=bc11 + sc;
  
}



/*****************************************************************************
*
* function name: count5_6_7_8_9_10_11
* description:  counts tables 5-11 
* returns:      
* input:        quantized spectrum
* output:       bitCount for tables 5-11
*
*****************************************************************************/
static void count5_6_7_8_9_10_11(const Word16 *values,
                                 const Word16  width,
                                 Word16       *bitCount)
{

  Word32 t0,t1,i;
  Word32 bc5_6,bc7_8,bc9_10;
  Word16 bc11,sc;

  bc5_6=0;                               
  bc7_8=0;                               
  bc9_10=0;                              
  bc11=0;                                
  sc=0;                                  

  for(i=0;i<width;i+=2){

    t0 = values[i+0];                    
    t1 = values[i+1];                    

    bc5_6 = bc5_6 + EXPAND(huff_ltab5_6[t0+4][t1+4]);                  

    t0=ABS(t0);
    t1=ABS(t1);
     
    bc7_8 = bc7_8 + EXPAND(huff_ltab7_8[t0][t1]);                      
    bc9_10 = bc9_10 + EXPAND(huff_ltab9_10[t0][t1]);                   
    bc11 = bc11 + huff_ltab11[t0][t1];
    
       
    sc = sc + (t0>0) + (t1>0);
  }
  bitCount[1]=INVALID_BITCOUNT;                          
  bitCount[2]=INVALID_BITCOUNT;                          
  bitCount[3]=INVALID_BITCOUNT;                          
  bitCount[4]=INVALID_BITCOUNT;                          
  bitCount[5]=extract_h(bc5_6);
  bitCount[6]=extract_l(bc5_6);
  bitCount[7]=extract_h(bc7_8) + sc;
  bitCount[8]=extract_l(bc7_8) + sc;
  bitCount[9]=extract_h(bc9_10) + sc;
  bitCount[10]=extract_l(bc9_10) + sc;
  bitCount[11]=bc11 + sc;
  
}


/*****************************************************************************
*
* function name: count7_8_9_10_11
* description:  counts tables 7-11 
* returns:      
* input:        quantized spectrum
* output:       bitCount for tables 7-11
*
*****************************************************************************/

static void count7_8_9_10_11(const Word16 *values,
                             const Word16  width,
                             Word16       *bitCount)
{
  Word32 t0,t1, i;
  Word32 bc7_8,bc9_10;
  Word16 bc11,sc;
    
  bc7_8=0;                       
  bc9_10=0;                      
  bc11=0;                        
  sc=0;                          

  for(i=0;i<width;i+=2){

    t0=ABS(values[i+0]);
    t1=ABS(values[i+1]);

    bc7_8 = bc7_8 + EXPAND(huff_ltab7_8[t0][t1]);                      
    bc9_10 = bc9_10 + EXPAND(huff_ltab9_10[t0][t1]);                   
    bc11 = bc11 + huff_ltab11[t0][t1];
   
       
    sc = sc + (t0>0) + (t1>0);
  }
  bitCount[1]=INVALID_BITCOUNT;                  
  bitCount[2]=INVALID_BITCOUNT;                  
  bitCount[3]=INVALID_BITCOUNT;                  
  bitCount[4]=INVALID_BITCOUNT;                  
  bitCount[5]=INVALID_BITCOUNT;                  
  bitCount[6]=INVALID_BITCOUNT;                  
  bitCount[7]=extract_h(bc7_8) + sc;
  bitCount[8]=extract_l(bc7_8) + sc;
  bitCount[9]=extract_h(bc9_10) + sc;
  bitCount[10]=extract_l(bc9_10) + sc;
  bitCount[11]=bc11 + sc;
  
}

/*****************************************************************************
*
* function name: count9_10_11
* description:  counts tables 9-11 
* returns:      
* input:        quantized spectrum
* output:       bitCount for tables 9-11
*
*****************************************************************************/
static void count9_10_11(const Word16 *values,
                         const Word16  width,
                         Word16       *bitCount)
{

  Word32 t0,t1,i;  
  Word32 bc9_10;
  Word16 bc11,sc;

  bc9_10=0;                              
  bc11=0;                                
  sc=0;                                  

  for(i=0;i<width;i+=2){

    t0=ABS(values[i+0]);
    t1=ABS(values[i+1]);
    

    bc9_10 += EXPAND(huff_ltab9_10[t0][t1]);           
    bc11 = bc11 + huff_ltab11[t0][t1];

       
    sc = sc + (t0>0) + (t1>0);
  }
  bitCount[1]=INVALID_BITCOUNT;          
  bitCount[2]=INVALID_BITCOUNT;          
  bitCount[3]=INVALID_BITCOUNT;          
  bitCount[4]=INVALID_BITCOUNT;          
  bitCount[5]=INVALID_BITCOUNT;          
  bitCount[6]=INVALID_BITCOUNT;          
  bitCount[7]=INVALID_BITCOUNT;          
  bitCount[8]=INVALID_BITCOUNT;          
  bitCount[9]=extract_h(bc9_10) + sc;
  bitCount[10]=extract_l(bc9_10) + sc;
  bitCount[11]=bc11 + sc;
  
}
 
/*****************************************************************************
*
* function name: count11
* description:  counts table 11 
* returns:      
* input:        quantized spectrum
* output:       bitCount for table 11
*
*****************************************************************************/
 static void count11(const Word16 *values,
                    const Word16  width,
                    Word16        *bitCount)
{
  Word32 t0,t1,i;
  Word16 bc11,sc;  

  bc11=0;                        
  sc=0;                          
  for(i=0;i<width;i+=2){
    t0=ABS(values[i+0]);
    t1=ABS(values[i+1]);
    bc11 = bc11 + huff_ltab11[t0][t1];

       
    sc = sc + (t0>0) + (t1>0);
  }

  bitCount[1]=INVALID_BITCOUNT;                  
  bitCount[2]=INVALID_BITCOUNT;                  
  bitCount[3]=INVALID_BITCOUNT;                  
  bitCount[4]=INVALID_BITCOUNT;                  
  bitCount[5]=INVALID_BITCOUNT;                  
  bitCount[6]=INVALID_BITCOUNT;                  
  bitCount[7]=INVALID_BITCOUNT;                  
  bitCount[8]=INVALID_BITCOUNT;                  
  bitCount[9]=INVALID_BITCOUNT;                  
  bitCount[10]=INVALID_BITCOUNT;                 
  bitCount[11]=bc11 + sc;
}

/*****************************************************************************
*
* function name: countEsc
* description:  counts table 11 (with Esc) 
* returns:      
* input:        quantized spectrum
* output:       bitCount for tables 11 (with Esc)
*
*****************************************************************************/

static void countEsc(const Word16 *values,
                     const Word16  width,
                     Word16       *bitCount)
{
  Word32 t0,t1,t00,t01,i;
  Word16 bc11,ec,sc;  

  bc11=0;                                
  sc=0;                                  
  ec=0;                                  
  for(i=0;i<width;i+=2){
    t0=ABS(values[i+0]);
    t1=ABS(values[i+1]);
    
       
    sc = sc + (t0>0) + (t1>0);

    t00 = min(t0,16);
    t01 = min(t1,16);
    bc11 = bc11 + huff_ltab11[t00][t01];
    
     
    if(t0 >= 16){
      ec = ec + 5;
      while(sub(t0=(t0 >> 1), 16) >= 0) {
        ec = ec + 2;
      }
    }
    
     
    if(t1 >= 16){
      ec = ec + 5;
      while(sub(t1=(t1 >> 1), 16) >= 0) {
        ec = ec + 2;
      }
    }
  }
  bitCount[1]=INVALID_BITCOUNT;          
  bitCount[2]=INVALID_BITCOUNT;          
  bitCount[3]=INVALID_BITCOUNT;          
  bitCount[4]=INVALID_BITCOUNT;          
  bitCount[5]=INVALID_BITCOUNT;          
  bitCount[6]=INVALID_BITCOUNT;          
  bitCount[7]=INVALID_BITCOUNT;          
  bitCount[8]=INVALID_BITCOUNT;          
  bitCount[9]=INVALID_BITCOUNT;          
  bitCount[10]=INVALID_BITCOUNT;         
  bitCount[11]=bc11 + sc + ec;
}


typedef void (*COUNT_FUNCTION)(const Word16 *values,
                               const Word16  width,
                               Word16       *bitCount);

static COUNT_FUNCTION countFuncTable[CODE_BOOK_ESC_LAV+1] =
  {

    count1_2_3_4_5_6_7_8_9_10_11,  /* 0  */
    count1_2_3_4_5_6_7_8_9_10_11,  /* 1  */
    count3_4_5_6_7_8_9_10_11,      /* 2  */
    count5_6_7_8_9_10_11,          /* 3  */
    count5_6_7_8_9_10_11,          /* 4  */
    count7_8_9_10_11,              /* 5  */
    count7_8_9_10_11,              /* 6  */
    count7_8_9_10_11,              /* 7  */
    count9_10_11,                  /* 8  */
    count9_10_11,                  /* 9  */
    count9_10_11,                  /* 10 */
    count9_10_11,                  /* 11 */
    count9_10_11,                  /* 12 */
    count11,                       /* 13 */
    count11,                       /* 14 */
    count11,                       /* 15 */
    countEsc                       /* 16 */
  };

/*****************************************************************************
*
* function name: bitCount
* description:  count bits 
*
*****************************************************************************/
Word16 bitCount(const Word16 *values,
                const Word16  width,
                Word16        maxVal,
                Word16       *bitCount)
{
  /*
    check if we can use codebook 0
  */
     
  if(maxVal == 0)
    bitCount[0] = 0;
  else
    bitCount[0] = INVALID_BITCOUNT;

  maxVal = min(maxVal, CODE_BOOK_ESC_LAV);
  countFuncTable[maxVal](values,width,bitCount);

  return(0);
}

/*****************************************************************************
*
* function name: codeValues
* description:  write huffum bits 
*
*****************************************************************************/
Word16 codeValues(Word16 *values, Word16 width, Word16 codeBook, HANDLE_BIT_BUF hBitstream)
{

  Word32 i, t0, t1, t2, t3, t00, t01;
  UWord16 codeWord, codeLength;
  Word16 sign, signLength;

   
  switch (codeBook) {
    case CODE_BOOK_ZERO_NO:
      break;

    case CODE_BOOK_1_NO:
      for(i=0; i<width; i+=4) {
        t0         = values[i+0];                                        
        t1         = values[i+1];                                        
        t2         = values[i+2];                                        
        t3         = values[i+3];                                        
        codeWord   = huff_ctab1[t0+1][t1+1][t2+1][t3+1];                 
        codeLength = HI_LTAB(huff_ltab1_2[t0+1][t1+1][t2+1][t3+1]);      
        WriteBits(hBitstream, codeWord, codeLength);        
      }
      break;

    case CODE_BOOK_2_NO:
      for(i=0; i<width; i+=4) {
        t0         = values[i+0];                                        
        t1         = values[i+1];                                        
        t2         = values[i+2];                                        
        t3         = values[i+3];                                        
        codeWord   = huff_ctab2[t0+1][t1+1][t2+1][t3+1];                 
        codeLength = LO_LTAB(huff_ltab1_2[t0+1][t1+1][t2+1][t3+1]);      
        WriteBits(hBitstream,codeWord,codeLength);
      }
      break;

    case CODE_BOOK_3_NO:
      for(i=0; i<width; i+=4) {
        sign=0;                                                          
        signLength=0;                                                    
        t0 = values[i+0];                                                
         
        if(t0 != 0){
          signLength = signLength + 1;
          sign = sign << 1; 
           
          if(t0 < 0){
            sign|=1;                                                     
            t0=-t0;
          }
        }
        t1 = values[i+1];                                                
         
        if(t1 != 0){
          signLength = signLength + 1;
          sign = sign << 1; 
           
          if(t1 < 0){
            sign|=1;                                                     
            t1=-t1;
          }
        }
        t2 = values[i+2];                                                
         
        if(t2 != 0){
          signLength = signLength + 1;
          sign = sign << 1; 
           
          if(t2 < 0){
            sign|=1;                                                     
            t2=-t2;
          }
        }
        t3 = values[i+3];                                                
        if(t3 != 0){
          signLength = signLength + 1;
          sign = sign << 1; 
           
          if(t3 < 0){
            sign|=1;                                                     
            t3=-t3;
          }
        }

        codeWord   = huff_ctab3[t0][t1][t2][t3];                         
        codeLength = HI_LTAB(huff_ltab3_4[t0][t1][t2][t3]);              
        WriteBits(hBitstream,codeWord,codeLength);
        WriteBits(hBitstream,sign,signLength);
      }
      break;

    case CODE_BOOK_4_NO:
      for(i=0; i<width; i+=4) {
        sign=0;                                                          
        signLength=0;                                                    
        t0 = values[i+0];                                                
         
        if(t0 != 0){                                                             
          signLength = signLength + 1;
          sign = sign << 1; 
          if(t0 < 0){                                                            
            sign|=1;                                                     
            t0=-t0;                                                          
          }
        }                                                                        
        t1 = values[i+1];                                                
         
        if(t1 != 0){                                                             
          signLength = signLength + 1;
          sign = sign << 1; 
           
          if(t1 < 0){                                                            
            sign|=1;                                                     
            t1=-t1;                                                          
          }                                                                      
        }                                                                        
        t2 = values[i+2];                                                
         
        if(t2 != 0){                                                    
          signLength = signLength + 1;
          sign = sign << 1; 
           
          if(t2 < 0){                                                   
            sign|=1;                                                     
            t2=-t2;                                                 
          }                                                             
        }                                                               
        t3 = values[i+3];                                                
         
        if(t3 != 0){                                                    
          signLength = signLength + 1;
          sign = sign << 1; 
           
          if(t3 < 0){                                                   
            sign|=1;                                                     
            t3=-t3;                                                 
          }                                                             
        }                                                               
        codeWord   = huff_ctab4[t0][t1][t2][t3];                         
        codeLength = LO_LTAB(huff_ltab3_4[t0][t1][t2][t3]);              
        WriteBits(hBitstream,codeWord,codeLength);                      
        WriteBits(hBitstream,sign,signLength);                          
      }                                                                 
      break;                                                            
                                                                        
    case CODE_BOOK_5_NO:                                                
      for(i=0; i<width; i+=2) {                                         
        t0         = values[i+0];                                         
        t1         = values[i+1];                                        
        codeWord   = huff_ctab5[t0+4][t1+4];                             
        codeLength = HI_LTAB(huff_ltab5_6[t0+4][t1+4]);                  
        WriteBits(hBitstream,codeWord,codeLength);
      }
      break;

    case CODE_BOOK_6_NO:
      for(i=0; i<width; i+=2) {
        t0         = values[i+0];                                        
        t1         = values[i+1];                                        
        codeWord   = huff_ctab6[t0+4][t1+4];                             
        codeLength = LO_LTAB(huff_ltab5_6[t0+4][t1+4]);                  
        WriteBits(hBitstream,codeWord,codeLength);
      }
      break;

    case CODE_BOOK_7_NO:
      for(i=0; i<width; i+=2){
        sign=0;                                                          
        signLength=0;                                                    
        t0 = values[i+0];                                                
         
        if(t0 != 0){
          signLength = signLength + 1;
          sign = sign << 1; 
           
          if(t0 < 0){
            sign|=1;                                                     
            t0=-t0;
          }
        }

        t1 = values[i+1];                                                
         
        if(t1 != 0){
          signLength = signLength + 1;
          sign = sign << 1; 
           
          if(t1 < 0){
            sign|=1;                                                     
            t1=-t1;
          }
        }
        codeWord   = huff_ctab7[t0][t1];                                 
        codeLength = HI_LTAB(huff_ltab7_8[t0][t1]);                      
        WriteBits(hBitstream,codeWord,codeLength);
        WriteBits(hBitstream,sign,signLength);
      }
      break;

    case CODE_BOOK_8_NO:
      for(i=0; i<width; i+=2) {
        sign=0;                                                          
        signLength=0;                                                    
        t0 = values[i+0];                                                
                                                                           
        if(t0 != 0){                                                             
          signLength = signLength + 1;                                       
          sign = sign << 1;                                                   
                                                                           
          if(t0 < 0){                                                            
            sign|=1;                                                     
            t0=-t0;                                                        
          }                                                                      
        }                                                                        
                                                                                 
        t1 = values[i+1];                                                
                                                                           
        if(t1 != 0){                                                             
          signLength = signLength + 1;                                       
          sign = sign << 1;                                                   
                                                                           
          if(t1 < 0){                                                            
            sign|=1;                                                     
            t1=-t1;                                                        
          }                                                                      
        }                                                                        
        codeWord   = huff_ctab8[t0][t1];                                 
        codeLength = LO_LTAB(huff_ltab7_8[t0][t1]);                      
        WriteBits(hBitstream,codeWord,codeLength);
        WriteBits(hBitstream,sign,signLength);
      }
      break;

    case CODE_BOOK_9_NO:
      for(i=0; i<width; i+=2) {
        sign=0;                                                          
        signLength=0;                                                    
        t0 = values[i+0];                                                
                                                                           
        if(t0 != 0){                                                             
          signLength = signLength + 1;                                       
          sign = sign << 1;                                                   
                                                                           
          if(t0 < 0){                                                            
            sign|=1;                                                     
            t0=-t0;                                                        
          }                                                                      
        }                                                                        
                                                                                 
        t1 = values[i+1];                                                
                                                                           
        if(t1 != 0){                                                             
          signLength = signLength + 1;                                       
          sign = sign << 1;                                                   
                                                                           
          if(t1 < 0){                                                            
            sign|=1;                                                     
            t1=-t1;                                                        
          }                                                                      
        }                                                                        
        codeWord   = huff_ctab9[t0][t1];                                 
        codeLength = HI_LTAB(huff_ltab9_10[t0][t1]);                     
        WriteBits(hBitstream,codeWord,codeLength);
        WriteBits(hBitstream,sign,signLength);
      }
      break;

    case CODE_BOOK_10_NO:
      for(i=0; i<width; i+=2) {
        sign=0;                                                          
        signLength=0;                                                    
        t0 = values[i+0];                                                
                                                                           
        if(t0 != 0){                                                             
          signLength = signLength + 1;                                       
          sign = sign << 1;                                                   
                                                                           
          if(t0 < 0){                                                            
            sign|=1;                                                     
            t0=-t0;                                                        
          }                                                                      
        }                                                                        
                                                                                 
        t1 = values[i+1];                                                
                                                                           
        if(t1 != 0){                                                             
          signLength = signLength + 1;                                       
          sign = sign << 1;                                                   
                                                                           
          if(t1 < 0){                                                            
            sign|=1;                                                     
            t1=-t1;                                                        
          }                                                                      
        }                                                                        
        codeWord   = huff_ctab10[t0][t1];                                
        codeLength = LO_LTAB(huff_ltab9_10[t0][t1]);                     
        WriteBits(hBitstream,codeWord,codeLength);
        WriteBits(hBitstream,sign,signLength);
      }
      break;

    case CODE_BOOK_ESC_NO:
      for(i=0; i<width; i+=2) {
        sign=0;                                                  
        signLength=0;                                            
        t0 = values[i+0];                                        
                                                                   
        if(t0 != 0){                                                     
          signLength = signLength + 1;                               
          sign = sign << 1;                                           
                                                                   
          if(t0 < 0){                                                    
            sign|=1;                                             
            t0=-t0;                                                
          }                                                              
        }                                                                
                                                                         
        t1 = values[i+1];                                        
                                                                   
        if(t1 != 0){                                                     
          signLength = signLength + 1;                               
          sign = sign << 1;                                           
                                                                   
          if(t1 < 0){                                                    
            sign|=1;                                             
            t1=-t1;                                                
          }                                                              
        }                                                                
        t00 = min(t0,16);
        t01 = min(t1,16);

        codeWord   = huff_ctab11[t00][t01];                      
        codeLength = huff_ltab11[t00][t01];                      
        WriteBits(hBitstream,codeWord,codeLength);
        WriteBits(hBitstream,sign,signLength);
         
        if(t0 >= 16){
          Word16 n, p;
          n=0;                                                   
          p=t0;                                                  
          while(sub(p=(p >> 1), 16) >= 0){
             
            WriteBits(hBitstream,1,1);
            n = n + 1;
          }
          WriteBits(hBitstream,0,1);
          n = n + 4;
          WriteBits(hBitstream,(t0 - (1 << n)),n);
        }
         
        if(t1 >= 16){
          Word16 n, p;
          n=0;                                                   
          p=t1;                                                  
          while(sub(p=(p >> 1), 16) >= 0){
             
            WriteBits(hBitstream,1,1);
            n = n + 1;
          }
          WriteBits(hBitstream,0,1);
          n = n + 4;
          WriteBits(hBitstream,(t1 - (1 << n)),n);
        }
      }
      break;

    default:
      break;
  }
  return(0);
}

Word16 bitCountScalefactorDelta(Word16 delta)
{
  return(huff_ltabscf[delta+CODE_BOOK_SCF_LAV]);
}

Word16 codeScalefactorDelta(Word16 delta, HANDLE_BIT_BUF hBitstream)
{
  Word32 codeWord; 
  Word16 codeLength;
  
   
  if(delta > CODE_BOOK_SCF_LAV || delta < -CODE_BOOK_SCF_LAV)
    return(1);
  
  codeWord   = huff_ctabscf[delta + CODE_BOOK_SCF_LAV];            
  codeLength = huff_ltabscf[delta + CODE_BOOK_SCF_LAV];            
  WriteBits(hBitstream,codeWord,codeLength);
  return(0);
}
