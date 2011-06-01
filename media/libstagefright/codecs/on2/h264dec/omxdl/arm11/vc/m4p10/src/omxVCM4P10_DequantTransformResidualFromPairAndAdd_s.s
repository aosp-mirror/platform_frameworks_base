;//
;// (c) Copyright 2007 ARM Limited. All Rights Reserved.
;//
;// Description:
;// H.264 inverse quantize and transform module
;// 
;// 

        

;// Include standard headers

        INCLUDE omxtypes_s.h
        INCLUDE armCOMM_s.h
        
;// Import symbols required from other files
;// (For example tables)
    
        IMPORT armVCM4P10_UnpackBlock4x4
        IMPORT armVCM4P10_TransformResidual4x4
        IMPORT armVCM4P10_QPDivTable
        IMPORT armVCM4P10_VMatrixU16
        IMPORT armVCM4P10_QPModuloTable 
        
    M_VARIANTS ARM1136JS, ARM1136JS_U
        
;// Set debugging level        
;//DEBUG_ON    SETL {TRUE}


;// Static Function: armVCM4P10_DequantLumaAC4x4

;// Guarding implementation by the processor name
    
    IF  ARM1136JS 
    
;//Input Registers
pSrcDst       RN  0
QP            RN  1


;//Output Registers


;//Local Scratch Registers
pQPdiv          RN  4
pQPmod          RN  5
pVRow           RN  2
QPmod           RN  6
shift           RN  3
rowLuma01       RN  1
rowLuma23       RN  4

SrcDst00        RN  5
SrcDst02        RN  6
SrcDst10        RN  7
SrcDst12        RN  8
SrcDst20        RN  9
SrcDst22        RN  10
SrcDst30        RN  11
SrcDst32        RN  12

temp1           RN  2
temp2           RN  3
temp3           RN  14
    
    
        ;// Allocate stack memory required by the function
        
        ;// Write function header
        M_START armVCM4P10_DequantLumaAC4x4,r11
         
        LDR    pQPmod,=armVCM4P10_QPModuloTable
        LDR    pQPdiv,=armVCM4P10_QPDivTable        
        LDR    pVRow,=armVCM4P10_VMatrixU16
         
        LDRSB  QPmod,[pQPmod,QP]                    ;// (QP%6) * 6
        LDRSB  shift,[pQPdiv,QP]                    ;// Shift = QP / 6
                
        LDRH    rowLuma01,[pVRow,QPmod]!             ;// rowLuma01 = [00|0a]
        LDRH    temp3,[pVRow,#2]                     ;// temp3     = [00|0b]   
        LDRH    rowLuma23,[pVRow,#4]                 ;// rowLuma23 = [00|0c] 
        ORR     rowLuma01,rowLuma01,temp3,LSL #16    ;// rowLuma01 = [0b|0a]   
        
        ;// Load all the 16 'src' values
        LDMIA   pSrcDst,{SrcDst00,SrcDst02,SrcDst10,SrcDst12,SrcDst20,SrcDst22,SrcDst30,SrcDst32}
        
        
        ;//*********************************************************************************************
        ;//
        ;// 'Shift' ranges between [0,8] 
        ;// So we can shift the packed rowLuma values [0b|0a] with a single LSL operation
        ;//
        ;//*********************************************************************************************
        
        LSL    rowLuma01,rowLuma01,shift
        LSL    rowLuma23,rowLuma23,shift
        
        
        ;//**********************************************************************************************
        ;//
        ;// The idea is to unroll the Loop completely
        ;// All the 16 src values are loaded at once into 8 registers : SrcDst<y><x> (above)
        ;// 0<= armVCM4P10_PosToVCol4x4[i] <=2 for any 'i<16' 
        ;// So the only values of pVRow[i] that need to be loaded are for i=0,1,2
        ;// These 3 values are loaded into rowLuma01 and rowLuma23 (above)
        ;// We first calculate pVRow[armVCM4P10_PosToVCol4x4[i]]) << Shift which fits into 16 bits (above)
        ;// Then the product pSrcDst[i] * (pVRow[armVCM4P10_PosToVCol4x4[i]] << Shift) is calculated
        ;// Here we interleave the PKHBT operations for various rows  to avoide pipeline stalls
        ;// 
        ;// We then pack the two 16 bit multiplication result into a word and store at one go
        ;//
        ;//**********************************************************************************************
        
        
        ;// Row 1
        
        
        SMULTB  temp1,SrcDst00,rowLuma23                    ;// pSrcDst[1] * (pVRow[2]<<Shift) 
        SMULBB  SrcDst00,SrcDst00,rowLuma01                 ;// pSrcDst[0] * (pVRow[0]<<Shift)  
        
        SMULTB  temp2,SrcDst02,rowLuma23                    ;// pSrcDst[3] * (pVRow[2]<<Shift) 
        SMULBB  SrcDst02,SrcDst02,rowLuma01                 ;// pSrcDst[2] * (pVRow[0]<<Shift)
        
        PKHBT   SrcDst00,SrcDst00,temp1,LSL #16             ;// Pack the first two product values
        
                
        ;// Row 2
        SMULTT  temp1,SrcDst10,rowLuma01                    ;// pSrcDst[5] * (pVRow[1]<<Shift)
        SMULBB  SrcDst10,SrcDst10,rowLuma23                 ;// pSrcDst[4] * (pVRow[2]<<Shift)
        
        PKHBT   SrcDst02,SrcDst02,temp2,LSL #16             ;// Pack the next two product values
        SMULTT  temp2,SrcDst12,rowLuma01                    ;// pSrcDst[7] * (pVRow[1]<<Shift)
        SMULBB  SrcDst12,SrcDst12,rowLuma23                    ;// pSrcDst[6] * (pVRow[2]<<Shift)
        
        PKHBT   SrcDst10,SrcDst10,temp1,LSL #16             ;// Pack the next two product values
        
               
        ;// Row 3    
        
        SMULTB  temp1,SrcDst20,rowLuma23                    ;// pSrcDst[9] * (pVRow[2]<<Shift)         
        SMULBB  SrcDst20,SrcDst20,rowLuma01                    ;// pSrcDst[8] * (pVRow[0]<<Shift)  
       
        PKHBT   SrcDst12,SrcDst12,temp2,LSL #16               ;// Pack the next two product values
        SMULTB  temp2,SrcDst22,rowLuma23                    ;// pSrcDst[11] * (pVRow[2]<<Shift) 
        SMULBB  SrcDst22,SrcDst22,rowLuma01                    ;// pSrcDst[10] * (pVRow[0]<<Shift)
                                                            
        PKHBT   SrcDst20,SrcDst20,temp1,LSL #16             ;// Pack the next two product values
        
        
                        
        ;// Row 4   
        
        SMULTT  temp1,SrcDst30,rowLuma01                    ;// pSrcDst[13] * (pVRow[1]<<Shift)
        SMULBB  SrcDst30,SrcDst30,rowLuma23                    ;// pSrcDst[12] * (pVRow[2]<<Shift)
        
        SMULTT  temp3,SrcDst32,rowLuma01                    ;// pSrcDst[15] * (pVRow[1]<<Shift)
        SMULBB  SrcDst32,SrcDst32,rowLuma23                    ;// pSrcDst[14] * (pVRow[2]<<Shift)
       
        PKHBT   SrcDst22,SrcDst22,temp2,LSL #16             ;// Pack the remaining product values
        PKHBT   SrcDst30,SrcDst30,temp1,LSL #16
        PKHBT   SrcDst32,SrcDst32,temp3,LSL #16
        
        
        STMIA   pSrcDst,{SrcDst00,SrcDst02,SrcDst10,SrcDst12,SrcDst20,SrcDst22,SrcDst30,SrcDst32}
        
        
        ;// Set return value
          
           
      
        ;// Write function tail
        M_END
        
    ENDIF                                                    ;//ARM1136JS        
 

;// Guarding implementation by the processor name
    
    IF  ARM1136JS_U
    
;//Input Registers
pSrcDst       RN  0
QP            RN  1


;//Output Registers


;//Local Scratch Registers
pQPdiv          RN  4
pQPmod          RN  5
pVRow           RN  2
QPmod           RN  6
shift           RN  3
rowLuma01       RN  1
rowLuma23       RN  4

SrcDst00        RN  5
SrcDst02        RN  6
SrcDst10        RN  7
SrcDst12        RN  8
SrcDst20        RN  9
SrcDst22        RN  10
SrcDst30        RN  11
SrcDst32        RN  12

temp1           RN  2
temp2           RN  3
temp3           RN  14
    
    
        ;// Allocate stack memory required by the function
        
        ;// Write function header
        M_START armVCM4P10_DequantLumaAC4x4,r11
         
        LDR    pQPmod,=armVCM4P10_QPModuloTable
        LDR    pQPdiv,=armVCM4P10_QPDivTable        
        LDR    pVRow,=armVCM4P10_VMatrixU16
         
        LDRSB  QPmod,[pQPmod,QP]                    ;// (QP%6) * 6
        LDRSB  shift,[pQPdiv,QP]                    ;// Shift = QP / 6
                
        LDR    rowLuma01,[pVRow,QPmod]!             ;// rowLuma01 = [0b|0a]
        LDR    rowLuma23,[pVRow,#4]                 ;// rowLuma23 = [0d|0c]    

        ;// Load all the 16 'src' values
        LDMIA   pSrcDst,{SrcDst00,SrcDst02,SrcDst10,SrcDst12,SrcDst20,SrcDst22,SrcDst30,SrcDst32}
        
        
        ;//*********************************************************************************************
        ;//
        ;// 'Shift' ranges between [0,8] 
        ;// So we can shift the packed rowLuma values [0b|0a] with a single LSL operation
        ;//
        ;//*********************************************************************************************
        
        LSL    rowLuma01,rowLuma01,shift
        LSL    rowLuma23,rowLuma23,shift
        
        
        ;//**********************************************************************************************
        ;//
        ;// The idea is to unroll the Loop completely
        ;// All the 16 src values are loaded at once into 8 registers : SrcDst<y><x> (above)
        ;// 0<= armVCM4P10_PosToVCol4x4[i] <=2 for any 'i<16' 
        ;// So the only values of pVRow[i] that need to be loaded are for i=0,1,2
        ;// These 3 values are loaded into rowLuma01 and rowLuma23 (above)
        ;// We first calculate pVRow[armVCM4P10_PosToVCol4x4[i]]) << Shift which fits into 16 bits (above)
        ;// Then the product pSrcDst[i] * (pVRow[armVCM4P10_PosToVCol4x4[i]] << Shift) is calculated
        ;// Here we interleave the PKHBT operations for various rows  to avoide pipeline stalls
        ;// 
        ;// We then pack the two 16 bit multiplication result into a word and store at one go
        ;//
        ;//**********************************************************************************************
        
        
        ;// Row 1
        
        
        SMULTB  temp1,SrcDst00,rowLuma23                    ;// pSrcDst[1] * (pVRow[2]<<Shift) 
        SMULBB  SrcDst00,SrcDst00,rowLuma01                 ;// pSrcDst[0] * (pVRow[0]<<Shift)  
        
        SMULTB  temp2,SrcDst02,rowLuma23                    ;// pSrcDst[3] * (pVRow[2]<<Shift) 
        SMULBB  SrcDst02,SrcDst02,rowLuma01                 ;// pSrcDst[2] * (pVRow[0]<<Shift)
        
        PKHBT   SrcDst00,SrcDst00,temp1,LSL #16             ;// Pack the first two product values
        
                
        ;// Row 2
        SMULTT  temp1,SrcDst10,rowLuma01                    ;// pSrcDst[5] * (pVRow[1]<<Shift)
        SMULBB  SrcDst10,SrcDst10,rowLuma23                 ;// pSrcDst[4] * (pVRow[2]<<Shift)
        
        PKHBT   SrcDst02,SrcDst02,temp2,LSL #16             ;// Pack the next two product values
        SMULTT  temp2,SrcDst12,rowLuma01                    ;// pSrcDst[7] * (pVRow[1]<<Shift)
        SMULBB  SrcDst12,SrcDst12,rowLuma23                    ;// pSrcDst[6] * (pVRow[2]<<Shift)
        
        PKHBT   SrcDst10,SrcDst10,temp1,LSL #16             ;// Pack the next two product values
        
               
        ;// Row 3    
        
        SMULTB  temp1,SrcDst20,rowLuma23                    ;// pSrcDst[9] * (pVRow[2]<<Shift)         
        SMULBB  SrcDst20,SrcDst20,rowLuma01                    ;// pSrcDst[8] * (pVRow[0]<<Shift)  
       
        PKHBT   SrcDst12,SrcDst12,temp2,LSL #16               ;// Pack the next two product values
        SMULTB  temp2,SrcDst22,rowLuma23                    ;// pSrcDst[11] * (pVRow[2]<<Shift) 
        SMULBB  SrcDst22,SrcDst22,rowLuma01                    ;// pSrcDst[10] * (pVRow[0]<<Shift)
                                                            
        PKHBT   SrcDst20,SrcDst20,temp1,LSL #16             ;// Pack the next two product values
        
        
                        
        ;// Row 4   
        
        SMULTT  temp1,SrcDst30,rowLuma01                    ;// pSrcDst[13] * (pVRow[1]<<Shift)
        SMULBB  SrcDst30,SrcDst30,rowLuma23                    ;// pSrcDst[12] * (pVRow[2]<<Shift)
        
        SMULTT  temp3,SrcDst32,rowLuma01                    ;// pSrcDst[15] * (pVRow[1]<<Shift)
        SMULBB  SrcDst32,SrcDst32,rowLuma23                    ;// pSrcDst[14] * (pVRow[2]<<Shift)
       
        PKHBT   SrcDst22,SrcDst22,temp2,LSL #16             ;// Pack the remaining product values
        PKHBT   SrcDst30,SrcDst30,temp1,LSL #16
        PKHBT   SrcDst32,SrcDst32,temp3,LSL #16
        
        
        STMIA   pSrcDst,{SrcDst00,SrcDst02,SrcDst10,SrcDst12,SrcDst20,SrcDst22,SrcDst30,SrcDst32}
        
        
        ;// Set return value
          
           
      
        ;// Write function tail
        M_END
        
    ENDIF                                                    ;//ARM1136JS_U        





;// Function: omxVCM4P10_DequantTransformResidualFromPairAndAdd            
    
;// Guarding implementation by the processor name
    
    IF  ARM1136JS
    
;//Input Registers
ppSrc       RN  0
pPred       RN  1
pDC         RN  2
pDst        RN  3
   

;//Output Registers
result      RN  0

;//Local Scratch Registers
pDelta      RN  4
pDeltaTmp   RN  6
AC          RN  5                   ;//Load from stack
pPredTemp   RN  7
pDCTemp     RN  8
pDstTemp    RN  9
pDeltaArg1  RN  1
pDeltaArg0  RN  0
QP          RN  1                   ;//Load from stack
DCval       RN  10  
DCvalCopy   RN  11
predstep    RN  1
dstStep     RN  10
ycounter    RN  0
PredVal1    RN  3
PredVal2    RN  5
DeltaVal1   RN  2
DeltaVal2   RN  11
PredVal     RN  8
tmpDeltaVal RN  6
sum1        RN  12
sum2        RN  14
    
    
           
    ;// Allocate stack memory required by the function
        M_ALLOC8 pBuffer, 32
               

    ;// Write function header
        M_START omxVCM4P10_DequantTransformResidualFromPairAndAdd,r11
        
        ;// Define stack arguments
        M_ARG   predStepOnStack, 4
        M_ARG   dstStepOnStack,4
        M_ARG   QPOnStack, 4
        M_ARG   ACOnStack,4
  
        
        M_ADR   pDelta,pBuffer 
        M_LDR   AC,ACOnStack 
        
         
        ;// Save registers r1,r2,r3 before function call    
        MOV     pPredTemp,pPred
        MOV     pDCTemp,pDC
        MOV     pDstTemp,pDst
        
        CMP     AC,#0
        BEQ     DCcase
        MOV     pDeltaArg1,pDelta                           ;// Set up r1 for armVCM4P10_UnpackBlock4x4
    
        BL      armVCM4P10_UnpackBlock4x4
    
        M_LDR   QP,QPOnStack                                ;// Set up r1 for DequantLumaAC4x4
        MOV     pDeltaArg0,pDelta                           ;// Set up r0 for DequantLumaAC4x4

        BL      armVCM4P10_DequantLumaAC4x4
        
        
        CMP     pDCTemp,#0
        LDRSHNE DCval,[pDCTemp]
        MOV     pDeltaArg0,pDelta                           ;// Set up r0 for armVCM4P10_TransformResidual4x4
        MOV     pDeltaArg1,pDelta                           ;// Set up r1 for armVCM4P10_TransformResidual4x4
        STRHNE  DCval,[pDelta]
        
        BL      armVCM4P10_TransformResidual4x4
        B       OutDCcase 
        

DCcase
        LDRSH   DCval,[pDCTemp] 
        ADD     DCval,DCval,#32 
        ASR     DCval,DCval,#6
        PKHBT   DCval,DCval,DCval,LSL #16                  ;// Duplicating the Lower halfword
        MOV     DCvalCopy, DCval                           ;// Needed for STRD
        STRD    DCval, [pDelta, #0]                        ;// pDelta[0]  = pDelta[1]  = pDelta[2]  = pDelta[3] = DCval
        STRD    DCval, [pDelta, #8]                        ;// pDelta[4]  = pDelta[5]  = pDelta[6]  = pDelta[7] = DCval
        STRD    DCval, [pDelta, #16]                       ;// pDelta[8]  = pDelta[9]  = pDelta[10] = pDelta[11] = DCval
        STRD    DCval, [pDelta, #24]   
        
               
OutDCcase      
        M_LDR   predstep,predStepOnStack
        M_LDR   dstStep,dstStepOnStack
        
        LDMIA   pDelta!,{tmpDeltaVal,DeltaVal2}             ;// Pre load
        MOV     ycounter,#4                                 ;// Counter for the PredPlusDeltaLoop
        LDR     PredVal,[pPredTemp]                         ;// Pre load

PredPlusDeltaLoop
        
       
        SUBS    ycounter,ycounter,#1
        ADD     pPredTemp,pPredTemp,predstep                ;// Increment pPred ptr
        
        PKHBT   DeltaVal1,tmpDeltaVal,DeltaVal2,LSL #16     ;// Deltaval1 = [C A]   
        PKHTB   DeltaVal2,DeltaVal2,tmpDeltaVal,ASR #16     ;// DeltaVal2 = [D B]
        
        UXTB16  PredVal1,PredVal                            ;// PredVal1 = [0c0a]
        UXTB16  PredVal2,PredVal,ROR #8                     ;// PredVal2 = [0d0b]
        
        LDRGT   PredVal,[pPredTemp]                         ;// Pre load
        
        QADD16  sum2,DeltaVal2,PredVal2                     ;// Add and saturate to 16 bits
        QADD16  sum1,DeltaVal1,PredVal1
        
        USAT16  sum2,#8,sum2                                ;// armClip(0,255,sum2)
        USAT16  sum1,#8,sum1
        
        LDMGTIA   pDelta!,{tmpDeltaVal,DeltaVal2}           ;// Pre load
          
        ORR     sum1,sum1,sum2,LSL #8                       ;// sum1 = [dcba]
        STR     sum1,[pDstTemp]
        
        ADD     pDstTemp,pDstTemp,dstStep                   ;// Increment pDst ptr
        BGT     PredPlusDeltaLoop  
        
        
        ;// Set return value
        MOV     result,#OMX_Sts_NoErr
        
End                

        
        ;// Write function tail
        
        M_END
        
    ENDIF                                                    ;//ARM1136JS   
    
    
;// Function: omxVCM4P10_DequantTransformResidualFromPairAndAdd            
    
;// Guarding implementation by the processor name
    
    
         
            
    END
