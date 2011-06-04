;//
;// 
;// File Name:  omxVCM4P10_DequantTransformResidualFromPairAndAdd_s.s
;// OpenMAX DL: v1.0.2
;// Revision:   12290
;// Date:       Wednesday, April 9, 2008
;// 
;// (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
;// 
;// 
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
        
        M_VARIANTS CortexA8
        
;// Set debugging level        
;//DEBUG_ON    SETL {TRUE}


;// Static Function: armVCM4P10_DequantLumaAC4x4

;// Guarding implementation by the processor name
    
 

;// Guarding implementation by the processor name
    





;// Function: omxVCM4P10_DequantTransformResidualFromPairAndAdd            
    
;// Guarding implementation by the processor name
    
    
    
;// Function: omxVCM4P10_DequantTransformResidualFromPairAndAdd            
    
;// Guarding implementation by the processor name
    
    IF  CortexA8
    

;// ARM Registers

;//Input Registers
ppSrc       RN  0
pPred       RN  1
pDC         RN  2
pDst        RN  3
   

;//Output Registers
result      RN  0

;//Local Scratch Registers

;//Registers used in armVCM4P10_DequantLumaAC4x4
pQPdiv      RN  10
pQPmod      RN  11
pVRow       RN  2
QPmod       RN  12
shift       RN  14
index0      RN  1 
index1      RN  10 

;//Registers used in DequantTransformResidualFromPairAndAdd
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
predstep    RN  1
dstStep     RN  10
PredVal1    RN  3
PredVal2    RN  5




;// Neon Registers

;// Registers used in armVCM4P10_DequantLumaAC4x4

dVmatrix            DN  D6.8  
dindexRow0          DN  D7.32 
dindexRow1          DN  D9.32 
dByteIndexRow0      DN  D7.8
dByteIndexRow1      DN  D9.8
dVRow0              DN  D8.8  
dVRow1              DN  D4.8
dVRow0U16           DN  D8.U16
dVRow1U16           DN  D4.U16
dVRow2U16           DN  D8.U16
dVRow3U16           DN  D4.U16

dShift              DN  D5.U16
dSrcRow0            DN  D0.I16
dSrcRow1            DN  D1.I16
dSrcRow2            DN  D2.I16    
dSrcRow3            DN  D3.I16
dDqntRow0           DN  D0.I16  
dDqntRow1           DN  D1.I16 
dDqntRow2           DN  D2.I16 
dDqntRow3           DN  D3.I16  

;// Registers used in TransformResidual4x4

;// Packed Input pixels
dIn0                DN  D0.S16       
dIn1                DN  D1.S16       
dIn2                DN  D2.S16       
dIn3                DN  D3.S16
qIn01               QN  Q0.32
qIn23               QN  Q1.32

;// Intermediate calculations       
dZero               DN  D4.S16
de0                 DN  D5.S16
de1                 DN  D6.S16
de2                 DN  D7.S16
de3                 DN  D8.S16
dIn1RS              DN  D7.S16
dIn3RS              DN  D8.S16
df0                 DN  D0.S16
df1                 DN  D1.S16
df2                 DN  D2.S16
df3                 DN  D3.S16
qf01                QN  Q0.32
qf23                QN  Q1.32
dg0                 DN  D5.S16
dg1                 DN  D6.S16
dg2                 DN  D7.S16
dg3                 DN  D8.S16
df1RS               DN  D7.S16
df3RS               DN  D8.S16

;// Output pixels
dh0                 DN  D0.S16
dh1                 DN  D1.S16
dh2                 DN  D2.S16
dh3                 DN  D3.S16 

;// Registers used in DequantTransformResidualFromPairAndAdd

dDeltaRow0          DN  D0.S16
dDeltaRow1          DN  D1.S16
dDeltaRow2          DN  D2.S16
dDeltaRow3          DN  D3.S16
qDeltaRow01         QN  Q0.S16
qDeltaRow23         QN  Q1.S16

dPredValRow01       DN  D4.U8
dPredValRow23       DN  D5.U8

qSumRow01           QN  Q3.S16
qSumRow23           QN  Q4.S16
dDstRow01           DN  D0.U8
dDstRow23           DN  D1.U8
dDstRow0            DN  D0.32[0]
dDstRow1            DN  D0.32[1]
dDstRow2            DN  D1.32[0]
dDstRow3            DN  D1.32[1]
    
           
    ;// Allocate stack memory required by the function
        M_ALLOC8 pBuffer, 32
               

    ;// Write function header
        M_START omxVCM4P10_DequantTransformResidualFromPairAndAdd,r11,d9
        
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
    
        ;//--------------------------------------------------------
        ;// armVCM4P10_DequantLumaAC4x4 : static function inlined
        ;//--------------------------------------------------------
        
        ;//BL      armVCM4P10_DequantLumaAC4x4
        M_LDR   QP,QPOnStack                                ;// Set up r1 for armVCM4P10_DequantLumaAC4x4
                
        LDR    pQPmod,=armVCM4P10_QPModuloTable
        LDR    pQPdiv,=armVCM4P10_QPDivTable        
        LDR    pVRow,=armVCM4P10_VMatrixU16
        
        
        LDRSB  QPmod,[pQPmod,QP]                    ;// (QP%6) * 6
        LDRSB  shift,[pQPdiv,QP]                    ;// Shift = QP / 6
                
        LDR    index1,=0x03020504 
        LDR    index0,=0x05040100                   ;// Indexes into dVmatrix
        ADD    pVRow,pVRow,QPmod
        VDUP   dindexRow0,index0 
        VDUP   dindexRow1,index1
        VDUP   dShift,shift 
        
        ;// Load all 4x4 pVRow[] values
        VLD1   dVmatrix,[pVRow]                     ;// dVmatrix = [0d|0c|0b|0a]
        
        
        VTBL   dVRow0,dVmatrix,dByteIndexRow0       ;// row0 = row2 = [pVRow[2] | pVRow[0] | pVRow[2] | pVRow[0]]
        VTBL   dVRow1,dVmatrix,dByteIndexRow1       ;// row1 = row3 = [pVRow[1] | pVRow[2] | pVRow[1] | pVRow[2]]
        CMP     pDCTemp,#0
        ;// Load all the 4x4 'src' values  
        VLD1   { dSrcRow0,dSrcRow1,dSrcRow2,dSrcRow3 },[pDelta] 
        
        VSHL   dVRow0U16,dVRow0U16,dShift 
        VSHL   dVRow1U16,dVRow1U16,dShift 
        LDRSHNE DCval,[pDCTemp]
        
        
        ;// Multiply src[] with pVRow[]
        VMUL    dDqntRow0,dSrcRow0,dVRow0U16
        VMUL    dDqntRow1,dSrcRow1,dVRow1U16
        VMUL    dDqntRow2,dSrcRow2,dVRow2U16
        VMUL    dDqntRow3,dSrcRow3,dVRow3U16
        
        
        
        ;//-------------------------------------------------------------
        ;// TransformResidual4x4 : Inlined to avoid Load/Stores
        ;//-------------------------------------------------------------
        
        
        ;//BL      armVCM4P10_TransformResidual4x4
        ;//STRHNE  DCval,[pDelta]
        VMOVNE    dIn0[0],DCval
        
        
        
        ;//*****************************************************************
        ;// Transpose the input pixels : perform Row ops as Col ops
        ;//*****************************************************************
        
        VTRN    dIn0,dIn1
        VTRN    dIn2,dIn3
        VTRN    qIn01,qIn23 
         
        
        VMOV    dZero,#0                                    ;// Used to right shift by 1 
        
        
        ;//**************************************** 
        ;// Row Operations (Performed on columns)
        ;//**************************************** 
        
        
        VADD        de0,dIn0,dIn2                       ;//  e0 = d0 + d2 
        VSUB        de1,dIn0,dIn2                        ;//  e1 = d0 - d2 
        VHADD       dIn1RS,dIn1,dZero                   ;// (f1>>1) constZero is a register holding 0
        VHADD       dIn3RS,dIn3,dZero
        VSUB        de2,dIn1RS,dIn3                     ;//  e2 = (d1>>1) - d3 
        VADD        de3,dIn1,dIn3RS                        ;//  e3 = d1 + (d3>>1) 
        VADD        df0,de0,de3                         ;//  f0 = e0 + e3
        VADD        df1,de1,de2                            ;//  f1 = e1 + e2
        VSUB        df2,de1,de2                            ;//  f2 = e1 - e2
        VSUB        df3,de0,de3                            ;//  f3 = e0 - e3
        
        
        
        ;//*****************************************************************
        ;// Transpose the resultant matrix
        ;//*****************************************************************
        
        VTRN    df0,df1
        VTRN    df2,df3
        VTRN    qf01,qf23 
        
        
        ;//******************************* 
        ;// Coloumn Operations 
        ;//******************************* 
        
        
        VADD        dg0,df0,df2                         ;//  e0 = d0 + d2 
        VSUB        dg1,df0,df2                            ;//  e1 = d0 - d2 
        VHADD       df1RS,df1,dZero                     ;// (f1>>1) constZero is a register holding 0
        VHADD       df3RS,df3,dZero
        VSUB        dg2,df1RS,df3                       ;//  e2 = (d1>>1) - d3 
        VADD        dg3,df1,df3RS                        ;//  e3 = d1 + (d3>>1) 
        VADD        dh0,dg0,dg3                         ;//  f0 = e0 + e3
        VADD        dh1,dg1,dg2                            ;//  f1 = e1 + e2
        VSUB        dh2,dg1,dg2                            ;//  f2 = e1 - e2
        VSUB        dh3,dg0,dg3                            ;//  f3 = e0 - e3
        
             
        ;//************************************************
        ;// Calculate final value (colOp[i][j] + 32)>>6
        ;//************************************************
        
        VRSHR       dh0,#6
        VRSHR       dh1,#6
        VRSHR       dh2,#6
        VRSHR       dh3,#6
        
               
        B       OutDCcase 
        

DCcase
        ;// Calculate the Transformed DCvalue : (DCval+32)>>6
        LDRSH   DCval,[pDCTemp] 
        ADD     DCval,DCval,#32 
        ASR     DCval,DCval,#6
        
        VDUP    dDeltaRow0, DCval                       ;// pDelta[0]  = pDelta[1]  = pDelta[2]  = pDelta[3] = DCval
        VDUP    dDeltaRow1, DCval                        ;// pDelta[4]  = pDelta[5]  = pDelta[6]  = pDelta[7] = DCval
        VDUP    dDeltaRow2, DCval                        ;// pDelta[8]  = pDelta[9]  = pDelta[10] = pDelta[11] = DCval
        VDUP    dDeltaRow3, DCval
            
                
OutDCcase      
        M_LDR   predstep,predStepOnStack
        M_LDR   dstStep,dstStepOnStack
        
        LDR     PredVal1,[pPredTemp],predstep
        LDR     PredVal2,[pPredTemp],predstep
        VMOV    dPredValRow01,PredVal1,PredVal2
        
        LDR     PredVal1,[pPredTemp],predstep
        LDR     PredVal2,[pPredTemp]
        VMOV    dPredValRow23,PredVal1,PredVal2
 
        
        VADDW   qSumRow01,qDeltaRow01,dPredValRow01
        VADDW   qSumRow23,qDeltaRow23,dPredValRow23
        VQMOVUN dDstRow01,qSumRow01
        VQMOVUN dDstRow23,qSumRow23
        
 
        VST1    dDstRow0,[pDstTemp],dstStep
        VST1    dDstRow1,[pDstTemp],dstStep
        VST1    dDstRow2,[pDstTemp],dstStep
        VST1    dDstRow3,[pDstTemp]
        
        ;// Set return value
        MOV     result,#OMX_Sts_NoErr
        
End                

        
        ;// Write function tail
        
        M_END
        
    ENDIF                                                    ;//CORTEXA8   
    
         
            
    END
