;//
;// 
;// File Name:  omxVCM4P10_PredictIntraChroma_8x8_s.s
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
        
        EXPORT armVCM4P10_pIndexTable8x8
        
;// Define the processor variants supported by this file
         
         M_VARIANTS CortexA8
     
     AREA table, DATA    
;//-------------------------------------------------------
;// This table for implementing switch case of C in asm by
;// the mehtod of two levels of indexing.
;//-------------------------------------------------------

    M_TABLE armVCM4P10_pIndexTable8x8
    DCD  OMX_VC_CHROMA_DC,     OMX_VC_CHROMA_HOR 
    DCD  OMX_VC_CHROMA_VERT,   OMX_VC_CHROMA_PLANE  
    
    M_TABLE armVCM4P10_MultiplierTableChroma8x8,1
    DCW   3, 2, 1,4 
    DCW  -3,-2,-1,0
    DCW   1, 2, 3,4
    
        
        
    IF CortexA8

;//--------------------------------------------
;// Scratch variable
;//--------------------------------------------
 
pc              RN 15   
return          RN 0  
pTable          RN 8 
  
;//--------------------------------------------
;// Input Arguments
;//--------------------------------------------
pSrcLeft        RN 0    ;// input pointer
pSrcAbove       RN 1    ;// input pointer
pSrcAboveLeft   RN 2    ;// input pointer
pDst            RN 3    ;// output pointer
leftStep        RN 4    ;// input variable
dstStep         RN 5    ;// input variable
predMode        RN 6    ;// input variable
availability    RN 7    ;// input variable
pMultiplierTable    RN  2     

pTmp            RN 9
step            RN 10

;//---------------------
;// Neon Registers
;//---------------------

;// OMX_VC_CHROMA_HOR

dLeftVal0       DN  D0.8
dLeftVal1       DN  D1.8
dLeftVal2       DN  D2.8
dLeftVal3       DN  D3.8
dLeftVal4       DN  D4.8
dLeftVal5       DN  D5.8
dLeftVal6       DN  D6.8
dLeftVal7       DN  D7.8

;// OMX_VC_CHROMA_VERT

dAboveVal       DN  D0.U8

;// OMX_VC_CHROMA_DC

dLeftVal        DN  D1.U8
dSumAboveValU16 DN  D2.U16
dSumAboveValU32 DN  D3.U32
dSumAboveValU8  DN  D3.U8
dSumLeftValU16  DN  D2.U16
dSumLeftValU32  DN  D1.U32
dSumLeftValU8   DN  D1.U8
dSumAboveLeft   DN  D2.U32
dSumAboveLeftU8 DN  D2.U8
dIndexRow0U8    DN  D5.U8
dIndexRow0      DN  D5.U64
dIndexRow4U8    DN  D6.U8
dIndexRow4      DN  D6.U64
dDstRow0        DN  D0.U8
dDstRow4        DN  D4.U8
dConst128U8     DN  D0.U8

;// OMX_VC_CHROMA_PLANE

dRevAboveVal    DN  D3.U8  
dRevAboveValU64 DN  D3.U64  
dAboveLeftVal   DN  D2.U8
qAbove7minus0   QN  Q3.S16 
qAboveDiff      QN  Q2.S16 
dIndex          DN  D8.U8  
dDiffAboveU8    DN  D9.U8  
dDiffAboveS16   DN  D9.S16 
dAboveDiff0U8   DN  D4.U8  
dAboveDiff0U64  DN  D4.U64
dAbove7minus0U8 DN  D6.U8  
dMultiplier     DN  D10.S16 
dHorPred        DN  D11.S16 
dRevLeftVal     DN  D3.U8
dRevLeftValU64  DN  D3.U64
qLeft7minus0    QN  Q7.S16
qLeftDiff       QN  Q6.S16
dDiffLeftU8     DN  D16.U8
dDiffLeftS16    DN  D16.S16
dLeftDiff0U8    DN  D12.U8
dLeftDiff0U64   DN  D12.U64
dLeft7minus0U8  DN  D14.U8
dVerPred        DN  D3.S16 
dHVValS16       DN  D3.S16
dHVValS32       DN  D3.S32
dHVTempS32      DN  D2.S32
qA              QN  Q0.S16
qB              QN  Q2.S16
qC              QN  Q3.S16
qMultiplier     QN  Q5.S16
dMultiplier0    DN  D10.S16
dMultiplier1    DN  D11.S16
qC0             QN  Q0.S16
qC1             QN  Q1.S16
qC2             QN  Q4.S16
qC3             QN  Q5.S16
qC4             QN  Q6.S16
qC5             QN  Q7.S16
qC6             QN  Q8.S16
qC7             QN  Q9.S16
qSum0           QN  Q0.S16
qSum1           QN  Q1.S16
qSum2           QN  Q4.S16
qSum3           QN  Q5.S16
qSum4           QN  Q6.S16
qSum5           QN  Q7.S16
qSum6           QN  Q8.S16
qSum7           QN  Q9.S16
dSum0           DN  D0.U8
dSum1           DN  D1.U8
dSum2           DN  D2.U8
dSum3           DN  D3.U8
dSum4           DN  D4.U8
dSum5           DN  D5.U8
dSum6           DN  D6.U8
dSum7           DN  D7.U8

;//-----------------------------------------------------------------------------------------------
;// omxVCM4P10_PredictIntraChroma_8x8 starts
;//-----------------------------------------------------------------------------------------------
        
        ;// Write function header
        M_START omxVCM4P10_PredictIntraChroma_8x8, r10, d15
        
        ;// Define stack arguments
        M_ARG    LeftStep,     4
        M_ARG    DstStep,      4
        M_ARG    PredMode,     4
        M_ARG    Availability, 4
        
        LDR      pTable,=armVCM4P10_pIndexTable8x8   ;// Load index table for switch case
        
        ;// Load argument from the stack
        M_LDR    predMode, PredMode                  ;// Arg predMode loaded from stack to reg 
        M_LDR    leftStep, LeftStep                  ;// Arg leftStep loaded from stack to reg 
        M_LDR    dstStep,  DstStep                   ;// Arg dstStep loaded from stack to reg         
        M_LDR    availability, Availability          ;// Arg availability loaded from stack to reg 
        
        
        LDR      pc, [pTable, predMode, LSL #2]      ;// Branch to the case based on preMode

OMX_VC_CHROMA_DC
        
        TST     availability, #OMX_VC_LEFT
        BEQ     DCChroma8x8LeftNotAvailable

        ADD     pTmp, pSrcLeft, leftStep
        ADD     step, leftStep, leftStep

        ;// Load Left Edge
        VLD1    {dLeftVal[0]},[pSrcLeft],step               ;// pSrcLeft[0*leftStep]
        VLD1    {dLeftVal[1]},[pTmp],step                   ;// pSrcLeft[1*leftStep]
        VLD1    {dLeftVal[2]},[pSrcLeft],step               ;// pSrcLeft[2*leftStep]
        VLD1    {dLeftVal[3]},[pTmp],step                   ;// pSrcLeft[3*leftStep]
        VLD1    {dLeftVal[4]},[pSrcLeft],step               ;// pSrcLeft[4*leftStep]
        VLD1    {dLeftVal[5]},[pTmp],step                   ;// pSrcLeft[5*leftStep]
        VLD1    {dLeftVal[6]},[pSrcLeft],step               ;// pSrcLeft[6*leftStep]
        VLD1    {dLeftVal[7]},[pTmp]                        ;// pSrcLeft[7*leftStep]      
        
        TST     availability, #OMX_VC_UPPER
        BEQ     DCChroma8x8LeftOnlyAvailable

        ;// Load Upper Edge also
        VLD1     dAboveVal,[pSrcAbove]                      ;// pSrcAbove[0 to 7]  
        
        MOV      return, #OMX_Sts_NoErr                     ;// returnNoError
        
        VPADDL   dSumAboveValU16, dAboveVal                 ;// pSrcAbove[ 6+7 | 4+5 | 2+3 | 0+1 ]             
        VPADDL   dSumAboveValU32, dSumAboveValU16           ;// pSrcAbove[ 4+5+6+7 |  0+1+2+3 ] 
                
        VPADDL   dSumLeftValU16, dLeftVal                   ;// pSrcLeft[ 6+7 | 4+5 | 2+3 | 0+1 ]             
        VPADDL   dSumLeftValU32, dSumLeftValU16             ;// pSrcLeft[ 4+5+6+7 |  0+1+2+3 ]             
        
        VADD     dSumAboveLeft,dSumAboveValU32,dSumLeftValU32
        VRSHR    dSumAboveLeft,dSumAboveLeft,#3             ;// Sum = (Sum + 4) >> 3
        VRSHR    dSumAboveValU32,dSumAboveValU32,#2         ;// Sum = (Sum + 2) >> 2
        VRSHR    dSumLeftValU32,dSumLeftValU32,#2           ;// Sum = (Sum + 2) >> 2
        
        VMOV     dIndexRow0U8,#0x0c                         
        VMOV     dIndexRow4U8,#0x04
        VSHL     dIndexRow0,dIndexRow0,#32                  ;// index0 = 0x0c0c0c0c00000000 
        VSHR     dIndexRow4,dIndexRow4,#32                  ;// index4 = 0x0000000004040404
        VADD     dIndexRow4U8,dIndexRow4U8,dIndexRow0U8     ;// index4 = 0x0c0c0c0c04040404
        VTBL     dDstRow0,{dSumAboveLeftU8,dSumAboveValU8},dIndexRow0U8
        VTBL     dDstRow4,{dSumLeftValU8,dSumAboveLeftU8},dIndexRow4U8
 
DCChroma8x8LeftStore       
        ADD     pTmp, pDst, dstStep
        ADD     step, dstStep, dstStep
        
        VST1     dDstRow0,[pDst],step                    ;// pDst[0*dstStep+x] :0<= x <= 7
        VST1     dDstRow0,[pTmp],step                    ;// pDst[1*dstStep+x] :0<= x <= 7
        VST1     dDstRow0,[pDst],step                    ;// pDst[2*dstStep+x] :0<= x <= 7
        VST1     dDstRow0,[pTmp],step                    ;// pDst[3*dstStep+x] :0<= x <= 7
        VST1     dDstRow4,[pDst],step                    ;// pDst[4*dstStep+x] :0<= x <= 7
        VST1     dDstRow4,[pTmp],step                    ;// pDst[5*dstStep+x] :0<= x <= 7
        VST1     dDstRow4,[pDst],step                    ;// pDst[6*dstStep+x] :0<= x <= 7
        VST1     dDstRow4,[pTmp]                         ;// pDst[7*dstStep+x] :0<= x <= 7

        M_EXIT
        

DCChroma8x8LeftOnlyAvailable

        MOV      return, #OMX_Sts_NoErr
        
        VPADDL   dSumLeftValU16, dLeftVal                   ;// pSrcLeft[ 6+7 | 4+5 | 2+3 | 0+1 ]             
        VPADDL   dSumLeftValU32, dSumLeftValU16             ;// pSrcLeft[ 4+5+6+7 |  0+1+2+3 ]   
        VRSHR    dSumLeftValU32,dSumLeftValU32,#2           ;// Sum = (Sum + 2) >> 2
        
        VDUP     dDstRow0,dSumLeftValU8[0]
        VDUP     dDstRow4,dSumLeftValU8[4]
        
        B        DCChroma8x8LeftStore  
        

DCChroma8x8LeftNotAvailable
                 
        TST     availability, #OMX_VC_UPPER
        BEQ     DCChroma8x8NoneAvailable

        ;// Load Upper Edge 
        VLD1     dAboveVal,[pSrcAbove]                      ;// pSrcAbove[0 to 7]  
        MOV      return, #OMX_Sts_NoErr                     ;// returnNoError
        
        VPADDL   dSumAboveValU16, dAboveVal                 ;// pSrcAbove[ 6+7 | 4+5 | 2+3 | 0+1 ]             
        VPADDL   dSumAboveValU32, dSumAboveValU16           ;// pSrcAbove[ 4+5+6+7 |  0+1+2+3 ] 
        VRSHR    dSumAboveValU32,dSumAboveValU32,#2         ;// Sum = (Sum + 2) >> 2
        VMOV     dIndexRow0U8,#0x04
        VSHL     dIndexRow0,dIndexRow0,#32                  ;// index = 0x0404040400000000
        VTBL     dDstRow0,{dSumAboveValU8},dIndexRow0U8 
        
        B        DCChroma8x8UpperStore
        

DCChroma8x8NoneAvailable        
        
        VMOV     dConst128U8,#0x80                          ;// 0x8080808080808080 if(count == 0)
        MOV      return, #OMX_Sts_NoErr                     ;// returnNoError

DCChroma8x8UpperStore        
        
        ADD     pTmp, pDst, dstStep
        ADD     step, dstStep, dstStep
        
        VST1     dDstRow0,[pDst],step                    ;// pDst[0*dstStep+x] :0<= x <= 7
        VST1     dDstRow0,[pTmp],step                    ;// pDst[1*dstStep+x] :0<= x <= 7
        VST1     dDstRow0,[pDst],step                    ;// pDst[2*dstStep+x] :0<= x <= 7
        VST1     dDstRow0,[pTmp],step                    ;// pDst[3*dstStep+x] :0<= x <= 7
        VST1     dDstRow0,[pDst],step                    ;// pDst[4*dstStep+x] :0<= x <= 7
        VST1     dDstRow0,[pTmp],step                    ;// pDst[5*dstStep+x] :0<= x <= 7
        VST1     dDstRow0,[pDst],step                    ;// pDst[6*dstStep+x] :0<= x <= 7
        VST1     dDstRow0,[pTmp]                         ;// pDst[7*dstStep+x] :0<= x <= 7
        
        M_EXIT


OMX_VC_CHROMA_VERT
        
        VLD1     dAboveVal,[pSrcAbove]                      ;// pSrcAbove[x]      :0<= x <= 7   
        MOV      return, #OMX_Sts_NoErr
        
        B        DCChroma8x8UpperStore
        

OMX_VC_CHROMA_HOR
        
        ADD     pTmp, pSrcLeft, leftStep
        ADD     step, leftStep, leftStep
        
        VLD1    {dLeftVal0[]},[pSrcLeft],step           ;// pSrcLeft[0*leftStep]
        VLD1    {dLeftVal1[]},[pTmp],step               ;// pSrcLeft[1*leftStep]
        VLD1    {dLeftVal2[]},[pSrcLeft],step           ;// pSrcLeft[2*leftStep]
        VLD1    {dLeftVal3[]},[pTmp],step               ;// pSrcLeft[3*leftStep]
        VLD1    {dLeftVal4[]},[pSrcLeft],step           ;// pSrcLeft[4*leftStep]
        VLD1    {dLeftVal5[]},[pTmp],step               ;// pSrcLeft[5*leftStep]
        VLD1    {dLeftVal6[]},[pSrcLeft],step           ;// pSrcLeft[6*leftStep]
        VLD1    {dLeftVal7[]},[pTmp]                    ;// pSrcLeft[7*leftStep]
        
        B        DCChroma8x8PlaneStore
        
        
OMX_VC_CHROMA_PLANE
        ADD     pTmp, pSrcLeft, leftStep
        ADD     step, leftStep, leftStep
        
        VLD1    dAboveVal,[pSrcAbove]                       ;// pSrcAbove[x]      :0<= x <= 7   
        VLD1    dAboveLeftVal[0],[pSrcAboveLeft]
        
        VLD1    {dLeftVal[0]},[pSrcLeft],step               ;// pSrcLeft[0*leftStep]
        VLD1    {dLeftVal[1]},[pTmp],step                   ;// pSrcLeft[1*leftStep]
        VLD1    {dLeftVal[2]},[pSrcLeft],step               ;// pSrcLeft[2*leftStep]
        VLD1    {dLeftVal[3]},[pTmp],step                   ;// pSrcLeft[3*leftStep]
        VLD1    {dLeftVal[4]},[pSrcLeft],step               ;// pSrcLeft[4*leftStep]
        VLD1    {dLeftVal[5]},[pTmp],step                   ;// pSrcLeft[5*leftStep]
        VLD1    {dLeftVal[6]},[pSrcLeft],step               ;// pSrcLeft[6*leftStep]
        VLD1    {dLeftVal[7]},[pTmp]                        ;// pSrcLeft[7*leftStep] 
        
        
        VREV64  dRevAboveVal,dAboveVal                      ;// Reverse order of bytes = pSrcAbove[0:1:2:3:4:5:6:7]
        VSUBL   qAbove7minus0,dRevAboveVal,dAboveLeftVal    ;// qAbove7minus0[0] = pSrcAbove[7] - pSrcAboveLeft[0]
        VSHR    dRevAboveValU64,dRevAboveValU64,#8          ;// pSrcAbove[X:0:1:2:3:4:5:6]
        VSUBL   qAboveDiff,dRevAboveVal,dAboveVal           ;// pSrcAbove[6] - pSrcAbove[0]
                                                            ;// pSrcAbove[5] - pSrcAbove[1]
                                                            ;// pSrcAbove[4] - pSrcAbove[2]
        
        VREV64  dRevLeftVal,dLeftVal                        ;// Reverse order of bytes = pSrcLeft[0:1:2:3:4:5:6:7]
        VSUBL   qLeft7minus0,dRevLeftVal,dAboveLeftVal      ;// qAbove7minus0[0] = pSrcLeft[7] - pSrcAboveLeft[0]
        VSHR    dRevLeftValU64,dRevLeftValU64,#8            ;// pSrcLeft[X:0:1:2:3:4:5:6]
        VSUBL   qLeftDiff,dRevLeftVal,dLeftVal              ;// pSrcLeft[6] - pSrcLeft[0]
                                                            ;// pSrcLeft[5] - pSrcLeft[1]
                                                            ;// pSrcLeft[4] - pSrcLeft[2]
        
        LDR     pMultiplierTable,=armVCM4P10_MultiplierTableChroma8x8   ;// Used to calculate Hval & Vval  
        VSHL    dAboveDiff0U64,dAboveDiff0U64,#16  
        VEXT    dDiffAboveU8,dAboveDiff0U8,dAbove7minus0U8,#2           ;// pSrcAbove[ 7-0 | 4-2 | 5-1 | 6-0 ]
        VLD1    dMultiplier,[pMultiplierTable]! 
        VSHL    dLeftDiff0U64,dLeftDiff0U64,#16  
        VEXT    dDiffLeftU8,dLeftDiff0U8,dLeft7minus0U8,#2              ;// pSrcLeft[ 7-0 | 4-2 | 5-1 | 6-0 ]                                                   
                                                                    
        
        VMUL    dHorPred,dDiffAboveS16,dMultiplier                      ;// pSrcAbove[ 4*(7-0) | 1*(4-2) | 2*(5-1) | 3*(6-0) ]
        VMUL    dVerPred,dDiffLeftS16,dMultiplier
        VPADD   dHVValS16,dHorPred,dVerPred
        
        
        VPADDL  dHVValS32,dHVValS16                                     ;// [V|H] in 32 bits each
        VSHL    dHVTempS32,dHVValS32,#4                                 ;// 17*H = 16*H + H = (H<<4)+H
        VADD    dHVValS32,dHVValS32,dHVTempS32                          ;// [ 17*V  | 17*H ]in 32 bits each
        VLD1    {dMultiplier0,dMultiplier1},[pMultiplierTable]          ;// qMultiplier = [ 4|3|2|1|0|-1|-2|-3 ]  
        VRSHR   dHVValS32,dHVValS32,#5                                  ;// [c|b] in 16bits each
        VADDL   qA,dAboveVal,dLeftVal
        VDUP    qA,qA[7]
        VSHL    qA,qA,#4                                                ;// [a|a|a|a|a|a|a|a]
        VDUP    qB,dHVValS16[0]                                         ;// [b|b|b|b|b|b|b|b]
        VDUP    qC,dHVValS16[2]                                         ;// [c|c|c|c|c|c|c|c]
        
        
        VMUL    qB,qB,qMultiplier
        VMUL    qC,qC,qMultiplier
        VADD    qB,qB,qA 
        
        VDUP    qC0,qC[0]
        VDUP    qC1,qC[1]
        VDUP    qC2,qC[2]
        VDUP    qC3,qC[3]
        VDUP    qC4,qC[4]
        VDUP    qC5,qC[5]
        VDUP    qC6,qC[6]
        VDUP    qC7,qC[7]
        
        VADD    qSum0,qB,qC0
        VADD    qSum1,qB,qC1
        VADD    qSum2,qB,qC2
        VADD    qSum3,qB,qC3
        VADD    qSum4,qB,qC4
        VADD    qSum5,qB,qC5
        VADD    qSum6,qB,qC6
        VADD    qSum7,qB,qC7
        
        VQRSHRUN dSum0,qSum0,#5                         ;// (OMX_U8)armClip(0,255,(Sum+16)>>5)
        VQRSHRUN dSum1,qSum1,#5
        VQRSHRUN dSum2,qSum2,#5
        VQRSHRUN dSum3,qSum3,#5
        VQRSHRUN dSum4,qSum4,#5
        VQRSHRUN dSum5,qSum5,#5
        VQRSHRUN dSum6,qSum6,#5
        VQRSHRUN dSum7,qSum7,#5      

DCChroma8x8PlaneStore        
        ADD     pTmp, pDst, dstStep
        ADD     step, dstStep, dstStep
        
        VST1    dSum0,[pDst],step                    ;// pDst[0*dstStep+x] :0<= x <= 7
        VST1    dSum1,[pTmp],step                    ;// pDst[1*dstStep+x] :0<= x <= 7
        VST1    dSum2,[pDst],step                    ;// pDst[2*dstStep+x] :0<= x <= 7
        VST1    dSum3,[pTmp],step                    ;// pDst[3*dstStep+x] :0<= x <= 7
        VST1    dSum4,[pDst],step                    ;// pDst[4*dstStep+x] :0<= x <= 7
        VST1    dSum5,[pTmp],step                    ;// pDst[5*dstStep+x] :0<= x <= 7
        VST1    dSum6,[pDst],step                    ;// pDst[6*dstStep+x] :0<= x <= 7
        VST1    dSum7,[pTmp]                         ;// pDst[7*dstStep+x] :0<= x <= 7       
        
        MOV     return, #OMX_Sts_NoErr
        M_END
        
        ENDIF ;// CortexA8
        
        END
;//-----------------------------------------------------------------------------------------------
;// omxVCM4P10_PredictIntraChroma_8x8 ends
;//-----------------------------------------------------------------------------------------------
