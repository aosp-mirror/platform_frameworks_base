;//
;// 
;// File Name:  omxVCM4P10_PredictIntra_16x16_s.s
;// OpenMAX DL: v1.0.2
;// Revision:   12290
;// Date:       Wednesday, April 9, 2008
;// 
;// (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
;// 
;// 
;//

        INCLUDE omxtypes_s.h
        INCLUDE armCOMM_s.h
        
        M_VARIANTS CortexA8
     
  
;//-------------------------------------------------------
;// This table for implementing switch case of C in asm by
;// the mehtod of two levels of indexing.
;//-------------------------------------------------------

    M_TABLE armVCM4P10_pIndexTable16x16
    DCD  OMX_VC_16X16_VERT, OMX_VC_16X16_HOR 
    DCD  OMX_VC_16X16_DC,   OMX_VC_16X16_PLANE
    

    IF CortexA8

    M_TABLE armVCM4P10_MultiplierTable16x16,1
    DCW   7,  6,  5,  4,  3,  2,  1,  8 
    DCW   0,  1,  2,  3,  4,  5,  6,  7
    DCW   8,  9, 10, 11, 12, 13, 14, 15
        
;//--------------------------------------------
;// Constants 
;//--------------------------------------------  
BLK_SIZE        EQU 0x10
MUL_CONST0      EQU 0x01010101
MUL_CONST1      EQU 0x00060004
MUL_CONST2      EQU 0x00070005
MUL_CONST3      EQU 0x00030001
MASK_CONST      EQU 0x00FF00FF

;//--------------------------------------------
;// Scratch variable
;//--------------------------------------------
y               RN 12   
pc              RN 15   

return          RN 0    
pTable          RN 9    
count           RN 11   
pMultTable      RN 9
; ----------------------------------------------
; Neon registers
; ----------------------------------------------
qAbove          QN Q0.U8
qLeft           QN Q1.U8
qSum8           QN Q0.U16
dSum80          DN D0.U16
dSum81          DN D1.U16
dSum4           DN D0.U16
dSum2           DN D0.U32
dSum1           DN D0.U64
qOut            QN Q3.U8
dSumLeft        DN D6.U64
dSumAbove       DN D7.U64
dSum            DN D8.U64
dSum0           DN D8.U8[0]

qH              QN Q11.S32
qV              QN Q12.S32
qA              QN Q11.S16
qB              QN Q6.S16
qC              QN Q7.S16

qB0             QN Q5.S16
qB1             QN Q6.S16
dA1             DN D23.S16

dH0             DN D22.S32
dH1             DN D23.S32
dV0             DN D24.S32
dV1             DN D25.S32

qHV             QN Q11.S64
qHV0            QN Q11.S32
qHV1            QN Q12.S64

dHV00           DN D22.S32
dHV01           DN D23.S32

dHV0            DN D22.S16[0]
dHV1            DN D23.S16[0]
dHV10           DN D24.S64
dHV11           DN D25.S64

qSum0           QN Q0.S16
qSum1           QN Q1.S16

dOut0           DN D6.U8
dOut1           DN D7.U8

dLeft0          DN D2.U8
dLeft1          DN D3.U8
qConst          QN Q13.S16

dAbove0         DN D0.U8
dAbove1         DN D1.U8

dRevLeft64      DN D12.U64
dRevLeft        DN D12.U8
dRevAbove64     DN D5.U64
dRevAbove       DN D5.U8
qLeftDiff       QN Q8.S16
dLeftDiff1      DN D17.S16
dLeftDiff64     DN D17.S64
qDiffLeft       QN Q8.S16
qDiffAbove      QN Q4.S16
dAboveDiff1     DN D9.S16
dAboveDiff64    DN D9.S64
qAboveDiff      QN Q4.S16

dAboveLeft      DN D4.U8

dDiffLeft0      DN D16.S16
dDiffLeft1      DN D17.S16
dDiffAbove0     DN D8.S16
dDiffAbove1     DN D9.S16

qLeft15minus0   QN Q7.S16
dLeft15minus0   DN D14.S16
qAbove15minus0  QN Q3.S16
dAbove15minus0  DN D6.S16

qMultiplier     QN Q10.S16
qMultiplier0    QN Q10.S16
qMultiplier1    QN Q12.S16
dMultiplier0    DN D20.S16
dMultiplier1    DN D21.S16

dBPlusCMult7    DN D1.S64
dBPlusCMult7S16 DN D1.S16

qTmp            QN Q0.U8

;//--------------------------------------------
;// Declare input registers
;//--------------------------------------------
pSrcLeft        RN 0    ;// input pointer
pSrcAbove       RN 1    ;// input pointer
pSrcAboveLeft   RN 2    ;// input pointer
pDst            RN 3    ;// output pointer
leftStep        RN 4    ;// input variable
dstStep         RN 5    ;// input variable
predMode        RN 6    ;// input variable
availability    RN 7    ;// input variable

pTmp            RN 8
step            RN 10
pTmp2           RN 11

;//-----------------------------------------------------------------------------------------------
;// omxVCM4P10_PredictIntra_16x16 starts
;//-----------------------------------------------------------------------------------------------
        
        ;// Write function header
        M_START omxVCM4P10_PredictIntra_16x16, r11, d15
        
        ;// Define stack arguments
        M_ARG    LeftStep,     4
        M_ARG    DstStep,      4
        M_ARG    PredMode,     4
        M_ARG    Availability, 4
        
        ;// M_STALL ARM1136JS=4
        
        LDR      pTable,=armVCM4P10_pIndexTable16x16 ;// Load index table for switch case
        
        ;// Load argument from the stack
        M_LDR    predMode, PredMode                  ;// Arg predMode loaded from stack to reg 
        M_LDR    leftStep, LeftStep                  ;// Arg leftStep loaded from stack to reg 
        M_LDR    dstStep,  DstStep                   ;// Arg dstStep loaded from stack to reg         
        M_LDR    availability, Availability          ;// Arg availability loaded from stack to reg
        
        MOV      y, #BLK_SIZE                        ;// Outer Loop Count
        LDR      pc, [pTable, predMode, LSL #2]      ;// Branch to the case based on preMode
        
OMX_VC_16X16_VERT
        VLD1    qAbove,  [pSrcAbove]
        ADD     pTmp, pDst, dstStep
        ADD     step, dstStep, dstStep
        VST1    qAbove, [pDst], step
        VST1    qAbove, [pTmp], step
        VST1    qAbove, [pDst], step
        VST1    qAbove, [pTmp], step
        VST1    qAbove, [pDst], step
        VST1    qAbove, [pTmp], step
        VST1    qAbove, [pDst], step
        VST1    qAbove, [pTmp], step
        VST1    qAbove, [pDst], step
        VST1    qAbove, [pTmp], step
        VST1    qAbove, [pDst], step
        VST1    qAbove, [pTmp], step
        VST1    qAbove, [pDst], step
        VST1    qAbove, [pTmp], step
        VST1    qAbove, [pDst]
        VST1    qAbove, [pTmp]
        MOV     return, #OMX_Sts_NoErr               ;// returnNoError
        M_EXIT
        
OMX_VC_16X16_HOR
        ADD     pTmp, pSrcLeft, leftStep
        ADD     leftStep, leftStep, leftStep
        ADD     pTmp2, pDst, dstStep
        ADD     dstStep, dstStep, dstStep
LoopHor 
        VLD1     {qLeft[]}, [pSrcLeft], leftStep       
        VLD1     {qTmp[]}, [pTmp], leftStep       
        SUBS     y, y, #8
        VST1     qLeft, [pDst], dstStep
        VST1     qTmp, [pTmp2], dstStep
        VLD1     {qLeft[]}, [pSrcLeft], leftStep       
        VLD1     {qTmp[]}, [pTmp], leftStep       
        VST1     qLeft, [pDst], dstStep
        VST1     qTmp, [pTmp2], dstStep
        VLD1     {qLeft[]}, [pSrcLeft], leftStep       
        VLD1     {qTmp[]}, [pTmp], leftStep       
        VST1     qLeft, [pDst], dstStep
        VST1     qTmp, [pTmp2], dstStep
        VLD1     {qLeft[]}, [pSrcLeft], leftStep       
        VLD1     {qTmp[]}, [pTmp], leftStep       
        VST1     qLeft, [pDst], dstStep
        VST1     qTmp, [pTmp2], dstStep
        
        BNE      LoopHor                                  ;// Loop for 16 times
        MOV      return, #OMX_Sts_NoErr
        M_EXIT
        
OMX_VC_16X16_DC
        MOV      count, #0                                 ;// count = 0
        TST      availability, #OMX_VC_LEFT
        BEQ      UpperOrNoneAvailable                      ;// Jump to Upper if not left

        ADD     pTmp, pSrcLeft, leftStep
        ADD     step, leftStep, leftStep

        VLD1    {qLeft[0]}, [pSrcLeft],step    
        VLD1    {qLeft[1]}, [pTmp],step   
        VLD1    {qLeft[2]}, [pSrcLeft],step   
        VLD1    {qLeft[3]}, [pTmp],step
        VLD1    {qLeft[4]}, [pSrcLeft],step   
        VLD1    {qLeft[5]}, [pTmp],step   
        VLD1    {qLeft[6]}, [pSrcLeft],step    
        VLD1    {qLeft[7]}, [pTmp],step
        VLD1    {qLeft[8]}, [pSrcLeft],step    
        VLD1    {qLeft[9]}, [pTmp],step   
        VLD1    {qLeft[10]},[pSrcLeft],step   
        VLD1    {qLeft[11]},[pTmp],step    
        VLD1    {qLeft[12]},[pSrcLeft],step   
        VLD1    {qLeft[13]},[pTmp],step   
        VLD1    {qLeft[14]},[pSrcLeft],step    
        VLD1    {qLeft[15]},[pTmp] 
        
        VPADDL   qSum8, qLeft
        ADD     count, count, #1    
        VPADD    dSum4, dSum80, dSum81
        VPADDL   dSum2, dSum4
        VPADDL   dSumLeft, dSum2
        VRSHR    dSum, dSumLeft, #4
        
UpperOrNoneAvailable
        TST      availability,  #OMX_VC_UPPER              ;// if(availability & #OMX_VC_UPPER)
        BEQ      BothOrNoneAvailable                       ;// Jump to Left if not upper
        VLD1     qAbove, [pSrcAbove]
        ADD      count, count, #1                          ;// if upper inc count by 1
        VPADDL   qSum8, qAbove
        VPADD    dSum4, dSum80, dSum81
        VPADDL   dSum2, dSum4
        VPADDL   dSumAbove, dSum2
        VRSHR    dSum, dSumAbove, #4
        
BothOrNoneAvailable
        CMP      count, #2                                  ;// check if both available
        BNE      NoneAvailable
        VADD     dSum, dSumAbove, dSumLeft
        VRSHR    dSum, dSum, #5
        

NoneAvailable
        VDUP     qOut, dSum0        
        CMP      count, #0                                  ;// check if none available
        ADD      pTmp, pDst, dstStep
        ADD      step, dstStep, dstStep
        BNE      LoopDC
        VMOV     qOut, #128
LoopDC        
        VST1    qOut, [pDst], step
        VST1    qOut, [pTmp], step
        VST1    qOut, [pDst], step
        VST1    qOut, [pTmp], step
        VST1    qOut, [pDst], step
        VST1    qOut, [pTmp], step
        VST1    qOut, [pDst], step
        VST1    qOut, [pTmp], step
        VST1    qOut, [pDst], step
        VST1    qOut, [pTmp], step
        VST1    qOut, [pDst], step
        VST1    qOut, [pTmp], step
        VST1    qOut, [pDst], step
        VST1    qOut, [pTmp], step
        VST1    qOut, [pDst], step
        VST1    qOut, [pTmp], step
        MOV     return, #OMX_Sts_NoErr
        M_EXIT

OMX_VC_16X16_PLANE
        LDR     pMultTable, =armVCM4P10_MultiplierTable16x16
        VLD1    qAbove, [pSrcAbove]                         ;// pSrcAbove[x]      :0<= x <= 7    
        VLD1    dAboveLeft[0],[pSrcAboveLeft]                                               
        ADD     pTmp, pSrcLeft, leftStep
        ADD     step, leftStep, leftStep
        VLD1    {qLeft[0]},  [pSrcLeft],step                                             
        VLD1    {qLeft[1]},  [pTmp],step      
        VLD1    {qLeft[2]},  [pSrcLeft],step  
        VLD1    {qLeft[3]},  [pTmp],step       
        VLD1    {qLeft[4]},  [pSrcLeft],step  
        VLD1    {qLeft[5]},  [pTmp],step      
        VLD1    {qLeft[6]},  [pSrcLeft],step   
        VLD1    {qLeft[7]},  [pTmp],step
        VLD1    {qLeft[8]},  [pSrcLeft],step   
        VLD1    {qLeft[9]},  [pTmp],step      
        VLD1    {qLeft[10]}, [pSrcLeft],step  
        VLD1    {qLeft[11]}, [pTmp],step       
        VLD1    {qLeft[12]}, [pSrcLeft],step  
        VLD1    {qLeft[13]}, [pTmp],step      
        VLD1    {qLeft[14]}, [pSrcLeft],step   
        VLD1    {qLeft[15]}, [pTmp]   

        VREV64  dRevAbove, dAbove1                          ;// pSrcAbove[15:14:13:12:11:10:9:8] 
        VSUBL   qAbove15minus0, dRevAbove, dAboveLeft       ;// qAbove7minus0[0] = pSrcAbove[15] - pSrcAboveLeft[0] 
        VSHR    dRevAbove64, dRevAbove64, #8                ;// pSrcAbove[14:13:12:11:10:9:8:X] 
        VSUBL   qAboveDiff, dRevAbove, dAbove0              
        
        VSHL    dAboveDiff64, dAboveDiff64, #16 
        VEXT    dDiffAbove1, dAboveDiff1, dAbove15minus0, #1  

        VREV64  dRevLeft,dLeft1                             ;// pSrcLeft[15:14:13:12:11:10:9:8] 
        VSUBL   qLeft15minus0,dRevLeft, dAboveLeft          ;// qAbove7minus0[0] = pSrcLeft[7] - pSrcAboveLeft[0] 
        VSHR    dRevLeft64, dRevLeft64, #8                  ;// pSrcLeft[14:13:12:11:10:9:8:X] 
        VSUBL   qLeftDiff,dRevLeft, dLeft0                  
        
        ;// Multiplier = [8|1|2|...|6|7]
        VLD1    qMultiplier, [pMultTable]!                  
        
        VSHL    dLeftDiff64, dLeftDiff64, #16
        VEXT    dDiffLeft1, dLeftDiff1, dLeft15minus0, #1     
        
        VMULL   qH,dDiffAbove0, dMultiplier0                
        VMULL   qV,dDiffLeft0,  dMultiplier0                
        VMLAL   qH,dDiffAbove1, dMultiplier1 
        VMLAL   qV,dDiffLeft1,  dMultiplier1
        
        VPADD   dHV00,dH1,dH0                                 
        VPADD   dHV01,dV1,dV0                                 
        VPADDL  qHV, qHV0
        VSHL    qHV1,qHV,#2
        VADD    qHV,qHV,qHV1 
        
        ;// HV = [c = ((5*V+32)>>6) | b = ((5*H+32)>>6)]
        VRSHR   qHV,qHV,#6
        
        ;// HV1 = [c*7|b*7]
        VSHL    qHV1,qHV,#3
        VSUB    qHV1,qHV1,qHV                             
        
        ;// Multiplier1 = [0|1|2|...|7]
        VLD1    qMultiplier0, [pMultTable]!    
        VDUP    qB, dHV0                                  
        VDUP    qC, dHV1 
        
        VADDL   qA,dAbove1,dLeft1
        VSHL    qA,qA, #4
        VDUP    qA,dA1[3]  
        VADD    dBPlusCMult7, dHV10, dHV11
        
        ;// Multiplier1 = [8|9|10|...|15]
        VLD1    qMultiplier1, [pMultTable]
        ;// Const = a - 7*(b+c)
        VDUP    qConst, dBPlusCMult7S16[0]
        VSUB    qConst, qA, qConst
        
        ;// B0 = [0*b|1*b|2*b|3*b|......|7*b]
        VMUL    qB0,qB,qMultiplier0
        
        ;// B0 = [8*b|9*b|10*b|11*b|....|15*b]
        VMUL    qB1,qB,qMultiplier1
        
        VADD    qSum0, qB0, qConst
        VADD    qSum1, qB1, qConst  
        
        ;// Loops for 16 times
LoopPlane       
        ;// (b*x + c*y + C)>>5
        VQRSHRUN dOut0, qSum0,#5
        VQRSHRUN dOut1, qSum1,#5      
        SUBS     y, y, #1
        VST1     qOut,[pDst],dstStep
        VADD     qSum0,qSum0,qC 
        VADD     qSum1,qSum1,qC 
        BNE      LoopPlane
        
        MOV      return, #OMX_Sts_NoErr

        M_END
        
        ENDIF ;// CortexA8
            
        END
;-----------------------------------------------------------------------------------------------
; omxVCM4P10_PredictIntra_16x16 ends
;-----------------------------------------------------------------------------------------------
