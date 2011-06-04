;//
;// 
;// File Name:  omxVCM4P10_PredictIntra_4x4_s.s
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
        
;// Define the processor variants supported by this file
         
         M_VARIANTS CortexA8
        
;//-------------------------------------------------------
;// This table for implementing switch case of C in asm by
;// the mehtod of two levels of indexing.
;//-------------------------------------------------------

    M_TABLE armVCM4P10_pSwitchTable4x4
    DCD  OMX_VC_4x4_VERT,     OMX_VC_4x4_HOR 
    DCD  OMX_VC_4x4_DC,       OMX_VC_4x4_DIAG_DL
    DCD  OMX_VC_4x4_DIAG_DR,  OMX_VC_4x4_VR
    DCD  OMX_VC_4x4_HD,       OMX_VC_4x4_VL
    DCD  OMX_VC_4x4_HU   
    
        
        IF CortexA8
        
;//--------------------------------------------
;// Scratch variable
;//--------------------------------------------
return          RN 0
pTable          RN 8
pc              RN 15

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
pDst1           RN 1 
pDst2           RN 4 
pDst3           RN 6 

pSrcTmp         RN 9
srcStep         RN 10
pDstTmp         RN 11
dstep           RN 12

;//-------------------
;// Neon registers
;//-------------------

;// OMX_VC_CHROMA_VERT
dAboveU32       DN  D0.U32

;// OMX_VC_CHROMA_HOR
dLeftVal0       DN  D0.8
dLeftVal1       DN  D1.8
dLeftVal2       DN  D2.8
dLeftVal3       DN  D3.8
dLeftVal0U32    DN  D0.U32
dLeftVal1U32    DN  D1.U32
dLeftVal2U32    DN  D2.U32
dLeftVal3U32    DN  D3.U32

;// OMX_VC_4x4_DC
dLeftVal        DN  D0.U8
dLeftValU32     DN  D0.U32
dSumAboveLeftU16  DN  D1.U16
dSumAboveLeftU32  DN  D1.U32
dSumAboveLeftU64  DN  D1.U64
dSumAboveLeftU8 DN  D1.U8
dSum            DN  D0.U8

dSumLeftValU16  DN  D1.U16
dSumLeftValU32  DN  D1.U32
dSumLeftValU64  DN  D1.U64
dSumLeftValU8   DN  D1.U8

dAboveVal       DN  D0.U8
dSumAboveValU16  DN  D1.U16
dSumAboveValU32  DN  D1.U32
dSumAboveValU64  DN  D1.U64
dSumAboveValU8   DN  D1.U8
dConst128U8     DN  D0.U8


;//OMX_VC_4x4_DIAG_DL

dAbove          DN  D0.U8
dU7             DN  D2.U8
dU3             DN  D2.U8
dAbove0         DN  D3.U8
dAbove1         DN  D4.U8
dAbove2         DN  D5.U8
dTmp            DN  D6.U8
dTmp0           DN  D7.U8
dTmp1           DN  D8.U8
dTmp2            DN  D9.U8
dTmp3            DN  D10.U8
dTmpU32         DN  D6.U32


;//OMX_VC_4x4_DIAG_DR
dLeft           DN  D1.U8
dUL             DN  D2.U8

;//OMX_VC_4x4_VR
dLeft0          DN  D1.U8
dLeft1          DN  D2.U8
dEven0          DN  D3.U8
dEven1          DN  D4.U8
dEven2          DN  D5.U8
dOdd0           DN  D6.U8
dOdd1           DN  D11.U8
dOdd2           DN  D12.U8
dTmp3U32        DN  D10.U32    
dTmp2U32        DN  D9.U32


;//OMX_VC_4x4_HD
dTmp1U64        DN  D8.U64
dTmp0U64        DN  D7.U64
dTmpU64         DN  D6.U64
dTmpU32         DN  D6.U32
dTmp1U32        DN  D8.U32

;//OMX_VC_4x4_HU
dL3             DN  D2.U8
dLeftHU0        DN  D3.U8
dLeftHU1        DN  D4.U8
dLeftHU2        DN  D5.U8
dTmp0U32        DN  D7.U32




;//-----------------------------------------------------------------------------------------------
;// omxVCM4P10_PredictIntra_4x4 starts
;//-----------------------------------------------------------------------------------------------
        
        ;// Write function header
        M_START omxVCM4P10_PredictIntra_4x4, r12,d12
        
        ;// Define stack arguments
        M_ARG    LeftStep,     4
        M_ARG    DstStep,      4
        M_ARG    PredMode,     4
        M_ARG    Availability, 4
        
                
        LDR      pTable,=armVCM4P10_pSwitchTable4x4  ;// Load index table for switch case
        
        ;// Load argument from the stack
        M_LDRD   predMode,availability,PredMode     ;// Arg predMode & availability loaded from stack to reg 
        M_LDRD   leftStep,dstStep,LeftStep          ;// Arg leftStep & dstStep loaded from stack to reg 
        
        
        LDR      pc, [pTable, predMode, LSL #2]      ;// Branch to the case based on preMode


OMX_VC_4x4_HOR
        
        ADD     pSrcTmp, pSrcLeft, leftStep
        ADD     srcStep, leftStep, leftStep
        ;// Load Left Edge
        VLD1    {dLeftVal0[]},[pSrcLeft],srcStep           ;// pSrcLeft[0*leftStep]
        VLD1    {dLeftVal1[]},[pSrcTmp],srcStep            ;//    pSrcLeft[1*leftStep]
        VLD1    {dLeftVal2[]},[pSrcLeft]                   ;//    pSrcLeft[2*leftStep]
        VLD1    {dLeftVal3[]},[pSrcTmp]                    ;//    pSrcLeft[3*leftStep]
        
        ADD     pDstTmp, pDst, dstStep
        ADD     dstep, dstStep, dstStep
        
        VST1    dLeftVal0U32[0],[pDst],dstep                ;// pDst[0*dstStep+x] :0<= x <= 7
        VST1    dLeftVal1U32[0],[pDstTmp],dstep             ;// pDst[1*dstStep+x] :0<= x <= 7
        VST1    dLeftVal2U32[0],[pDst]                      ;// pDst[2*dstStep+x] :0<= x <= 7
        VST1    dLeftVal3U32[0],[pDstTmp]                   ;// pDst[3*dstStep+x] :0<= x <= 7
        
        B        ExitPredict4x4                             ;// Branch to exit code
        
OMX_VC_4x4_VERT
        
        ;// Load Upper Edge
        VLD1     dAboveU32[0],[pSrcAbove]
        ADD     pDstTmp, pDst, dstStep
        ADD     dstep, dstStep, dstStep
        
DCPredict4x4VertStore         
        
        VST1     dAboveU32[0],[pDst],dstep
        VST1     dAboveU32[0],[pDstTmp],dstep
        VST1     dAboveU32[0],[pDst]
        VST1     dAboveU32[0],[pDstTmp]

        B        ExitPredict4x4                             ;// Branch to exit code

OMX_VC_4x4_DC
        
        
        TST     availability, #OMX_VC_LEFT
        BEQ     DCPredict4x4LeftNotAvailable

        ADD     pSrcTmp, pSrcLeft, leftStep
        ADD     srcStep, leftStep, leftStep
        ;// Load Left Edge
        VLD1    {dLeftVal[0]},[pSrcLeft],srcStep            ;// pSrcLeft[0*leftStep]
        VLD1    {dLeftVal[1]},[pSrcTmp],srcStep             ;//    pSrcLeft[1*leftStep]
        VLD1    {dLeftVal[2]},[pSrcLeft]                    ;//    pSrcLeft[2*leftStep]
        VLD1    {dLeftVal[3]},[pSrcTmp]                     ;//    pSrcLeft[3*leftStep]
        
        TST     availability, #OMX_VC_UPPER
        BEQ     DCPredict4x4LeftOnlyAvailable

        ;// Load Upper Edge also
        VLD1     dLeftValU32[1],[pSrcAbove]                 ;// pSrcAbove[0 to 3]
        MOV      return, #OMX_Sts_NoErr
        
        VPADDL   dSumAboveLeftU16, dLeftVal                 ;// [pSrcAbove[2+3 | 0+1] | pSrcLeft[2+3 | 0+1]]             
        VPADDL   dSumAboveLeftU32, dSumAboveLeftU16         ;// [pSrcAbove[2+3+0+1] | pSrcLeft[2+3+0+1]] 
        VPADDL   dSumAboveLeftU64, dSumAboveLeftU32         ;// [pSrcAbove[2+3+0+1] + pSrcLeft[2+3+0+1]]                          
        VRSHR    dSumAboveLeftU64,dSumAboveLeftU64,#3       ;// Sum = (Sum + 4) >> 3
        ADD     pDstTmp, pDst, dstStep
        ADD     dstep, dstStep, dstStep
        VDUP     dSum,dSumAboveLeftU8[0]
        
        B        DCPredict4x4VertStore  
        
DCPredict4x4LeftOnlyAvailable

        MOV      return, #OMX_Sts_NoErr                     ;// returnNoError
        
        VPADDL   dSumLeftValU16, dLeftVal                   ;// [ XX | pSrcLeft[2+3 | 0+1]]             
        VPADDL   dSumLeftValU32, dSumLeftValU16             ;// [ XXXX | pSrcLeft[2+3+0+1]] 
        
        VRSHR    dSumLeftValU32,dSumLeftValU32,#2           ;// Sum = (Sum + 2) >> 2
        ADD     pDstTmp, pDst, dstStep
        ADD     dstep, dstStep, dstStep
        VDUP     dSum,dSumLeftValU8[0]
        
        B        DCPredict4x4VertStore   
        
DCPredict4x4LeftNotAvailable
                 
        TST     availability, #OMX_VC_UPPER
        BEQ     DCPredict4x4NoneAvailable

        ;// Load Upper Edge 
        VLD1     dAboveU32[0],[pSrcAbove]                   ;// pSrcAbove[0 to 3]  
        MOV      return, #OMX_Sts_NoErr
        
        VPADDL   dSumAboveValU16, dAboveVal                 ;// [ XX | pSrcAbove[2+3 | 0+1]]             
        VPADDL   dSumAboveValU32, dSumAboveValU16           ;// [ XXXX | pSrcAbove[2+3+0+1]] 
        
        VRSHR    dSumAboveValU32,dSumAboveValU32,#2         ;// Sum = (Sum + 2) >> 2
        ADD     pDstTmp, pDst, dstStep
        ADD     dstep, dstStep, dstStep
        VDUP     dSum,dSumAboveValU8[0]
        
        B        DCPredict4x4VertStore   
        
DCPredict4x4NoneAvailable        
        
        VMOV     dConst128U8,#0x80                          ;// 0x8080808080808080 if(count == 0)
        MOV      return, #OMX_Sts_NoErr
        
        ADD     pDstTmp, pDst, dstStep
        ADD     dstep, dstStep, dstStep
        B        DCPredict4x4VertStore   
        
        
        
OMX_VC_4x4_DIAG_DL
        
        TST     availability, #OMX_VC_UPPER_RIGHT
        BEQ     DiagDLUpperRightNotAvailable
       
        VLD1    dAbove0,[pSrcAbove]                     ;// [U7|U6|U5|U4|U3|U2|U1|U0] 
        VDUP    dU7, dAbove0[7]                         ;// [U7|U7|U7|U7|U7|U7|U7|U7]
        VEXT    dAbove1, dAbove0, dU7, #1               ;// [U7|U7|U6|U5|U4|U3|U2|U1]
        VEXT    dAbove2, dAbove0, dU7, #2               ;// [U7|U7|U7|U6|U5|U4|U3|U2] 
        B       DiagDLPredict4x4Store         
       
DiagDLUpperRightNotAvailable
        VLD1    dAboveU32[1],[pSrcAbove]                ;// [U3|U2|U1|U0|-|-|-|-] 
        VDUP    dU3, dAbove[7]                          ;// [U3 U3 U3 U3 U3 U3 U3 U3]

        VEXT    dAbove0, dAbove, dU3, #4                ;// [U3 U3 U3 U3 U3 U2 U1 U0]
        VEXT    dAbove1, dAbove, dU3, #5                ;// [U3 U3 U3 U3 U3 U3 U2 U1]
        VEXT    dAbove2, dAbove, dU3, #6                ;// [U3 U3 U3 U3 U3 U3 U3 U2]
       
DiagDLPredict4x4Store  
        
        VHADD   dTmp, dAbove0, dAbove2
        VRHADD  dTmp, dTmp, dAbove1                     ;// (a+2*b+c+2)>>2
        

        VST1    dTmpU32[0],[pDst],dstStep
        VEXT    dTmp,dTmp,dTmp,#1
        VST1    dTmpU32[0],[pDst],dstStep
        VEXT    dTmp,dTmp,dTmp,#1
        VST1    dTmpU32[0],[pDst],dstStep
        VEXT    dTmp,dTmp,dTmp,#1
        VST1    dTmpU32[0],[pDst]
        
        B        ExitPredict4x4                         ;// Branch to exit code
        

OMX_VC_4x4_DIAG_DR
        
        
        ;// Load U0,U1,U2,U3
        
        VLD1    dAboveU32[0],[pSrcAbove]                ;// [X|X|X|X|U3|U2|U1|U0]
                
        ;// Load UL,L0,L1,L2,L3                         ;// dLeft = [UL|L0|L1|L2|L3|X|X|X]    
        VLD1    {dLeft[7]},[pSrcAboveLeft]              
        ADD     pSrcTmp, pSrcLeft, leftStep
        ADD     srcStep, leftStep, leftStep
        ADD     pDst1,pDst,dstStep
        
        VLD1    {dLeft[6]},[pSrcLeft],srcStep           ;// pSrcLeft[0*leftStep]
        VLD1    {dLeft[5]},[pSrcTmp],srcStep            ;// pSrcLeft[1*leftStep]
        VLD1    {dLeft[4]},[pSrcLeft]                   ;// pSrcLeft[2*leftStep]
        VLD1    {dLeft[3]},[pSrcTmp]                    ;// pSrcLeft[3*leftStep]
        
        
        VEXT    dAbove0,dLeft,dAbove,#3                 ;// [U2|U1|U0|UL|L0|L1|L2|L3]   
        ADD     pDst2,pDst1,dstStep
        VEXT    dAbove1,dLeft,dAbove,#4                 ;// [U3|U2|U1|U0|UL|L0|L1|L2]   
        ADD     pDst3,pDst2,dstStep
        VEXT    dAbove2,dLeft,dAbove,#5                 ;// [ X|U3|U2|U1|U0|UL|L0|L1]   
        
        VHADD   dTmp, dAbove0, dAbove2
        VRHADD  dTmp, dTmp, dAbove1                     ;// (a+2*b+c+2)>>2
        
        
        VST1    dTmpU32[0],[pDst3]                      ;// Store pTmp[0],[1],[2],[3] @ pDst3
        VEXT    dTmp,dTmp,dTmp,#1
        VST1    dTmpU32[0],[pDst2]                      ;// Store pTmp[1],[2],[3],[4] @ pDst2
        VEXT    dTmp,dTmp,dTmp,#1
        VST1    dTmpU32[0],[pDst1]                      ;// Store pTmp[2],[3],[4],[5] @ pDst1
        VEXT    dTmp,dTmp,dTmp,#1
        VST1    dTmpU32[0],[pDst]                       ;// Store pTmp[3],[4],[5],[6] @ pDst
        
        B        ExitPredict4x4                         ;// Branch to exit code

OMX_VC_4x4_VR

        
        ;// Load UL,U0,U1,U2,U3
        VLD1    dAboveU32[0],[pSrcAbove]
        VLD1    dAbove[7],[pSrcAboveLeft]               ;// [UL|X|X|X|U3|U2|U1|U0]
        
        ;// Load L0,L1,L2                               ;// dLeft0 = [L0|L2|X|X|X|X|X|X]
                                                        ;// dLeft1 = [L1| X|X|X|X|X|X|X]    
        VLD1    {dLeft0[7]},[pSrcLeft],leftStep         ;// pSrcLeft[0*leftStep]
        VLD1    {dLeft1[7]},[pSrcLeft],leftStep         ;// pSrcLeft[1*leftStep]
        VLD1    {dLeft0[6]},[pSrcLeft]                  ;// pSrcLeft[2*leftStep]
        
        
        VEXT    dOdd2,dAbove,dAbove,#7                  ;// [ x x x U3 U2 U1 U0 UL ]
        VEXT    dEven0,dLeft0,dOdd2,#6                  ;// [ x x x U1 U0 UL L0 L2 ]
        VEXT    dEven1,dLeft1,dOdd2,#7                  ;// [ x x x U2 U1 U0 UL L1 ]
        VEXT    dEven2,dLeft0,dAbove,#7                 ;// [ x x x U3 U2 U1 U0 L0 ]
        VEXT    dOdd0,dLeft1,dAbove,#7                  ;// [ x x x U3 U2 U1 U0 L1 ]
        VEXT    dOdd1,dLeft0,dOdd2,#7                   ;// [ x x x U2 U1 U0 UL L0 ]
        
        VHADD   dTmp1, dOdd0, dOdd2
        VRHADD  dTmp1, dTmp1, dOdd1                     ;// Tmp[ x x x 9 7 5 3 1 ]
        
        VHADD   dTmp0, dEven0, dEven2
        VRHADD  dTmp0, dTmp0, dEven1                    ;// Tmp[ x x x 8 6 4 2 0 ]
        
        
        VEXT    dTmp3,dTmp1,dTmp1,#1                    ;// Tmp[ x x x x 9 7 5 3 ] 
        ADD     pDstTmp, pDst, dstStep
        ADD     dstep, dstStep, dstStep
        VEXT    dTmp2,dTmp0,dTmp0,#1                    ;// Tmp[ x x x x 8 6 4 2 ]
        
        
        VST1    dTmp3U32[0],[pDst],dstep                ;// Tmp[9],[7],[5],[3]
        VST1    dTmp2U32[0],[pDstTmp],dstep             ;// Tmp[8],[6],[4],[2]
        VST1    dTmp1U32[0],[pDst],dstep                ;// Tmp[7],[5],[3],[1]
        VST1    dTmp0U32[0],[pDstTmp]                   ;// Tmp[6],[4],[2],[0]
        
        B        ExitPredict4x4                         ;// Branch to exit code
        
OMX_VC_4x4_HD
        
        
        ;// Load U0,U1,U2,U3
        VLD1    dAbove,[pSrcAbove]                      ;//dAboveLeftVal = [U7|U6|U5|U4|U3|U2|U1|U0]
        
        ;// Load UL,L0,L1,L2,L3                         ;// dLeft = [UL|L0|L1|L2|L3|X|X|X] 
        VLD1    {dLeft[7]},[pSrcAboveLeft]   
        ADD     pSrcTmp, pSrcLeft, leftStep
        ADD     srcStep, leftStep, leftStep
        
        VLD1    {dLeft[6]},[pSrcLeft],srcStep           ;// pSrcLeft[0*leftStep]
        VLD1    {dLeft[5]},[pSrcTmp],srcStep            ;// pSrcLeft[1*leftStep]
        VLD1    {dLeft[4]},[pSrcLeft]                   ;// pSrcLeft[2*leftStep]
        VLD1    {dLeft[3]},[pSrcTmp]                    ;// pSrcLeft[3*leftStep]
        
        VEXT    dAbove0,dLeft,dAbove,#3                 ;// [ U2|U1|U0|UL|L0|L1|L2|L3 ]  
        VEXT    dAbove1,dLeft,dAbove,#2                 ;// [ U1|U0|UL|L0|L1|L2|L3|X ]   
        VEXT    dAbove2,dLeft,dAbove,#1                 ;// [ U0|UL|L0|L1|L2|L3|X|X ]     
        
        VHADD   dTmp0, dAbove0, dAbove2
        VRHADD  dTmp0, dTmp0, dAbove1                   ;// Tmp[ 0 | 1 | 2 | 4 | 6 | 8 | X | X ]
        
        
        VRHADD  dTmp1, dAbove1, dAbove0                 ;// (a+b+1)>>1
        VSHL    dTmp1U64,dTmp1U64,#24                   ;// Tmp[ 3|5| 7 |9 | X | X | X | X ]
        
        
        VSHL    dTmpU64,dTmp0U64,#16                    ;// Tmp[ 2|4|6|8| X | X | X | X ]
        VZIP    dTmp1,dTmp                              ;// dTmp = [ 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 ]
        VEXT    dTmp0,dTmp0,dTmp0,#6                    ;// Tmp[  X| X| X| X| X| X| 0 | 1 ]
        VEXT    dTmp1,dTmp,dTmp0,#2                     ;// Tmp[ 0 | 1 | 2 | 3 | 4 | 5 | 6 |7 ]
       
        ADD     pDstTmp, pDst, dstStep
        ADD     dstep, dstStep, dstStep
        
        VST1    dTmp1U32[1],[pDst],dstep                ;// Store pTmp[0|1|2|3]
        VST1    dTmpU32[1],[pDstTmp],dstep              ;// Store pTmp[2|3|4|5]
        VST1    dTmp1U32[0],[pDst]                      ;// Store pTmp[4|5|6|7]
        VST1    dTmpU32[0],[pDstTmp]                    ;// Store pTmp[6|7|8|9]
        
        B        ExitPredict4x4                         ;// Branch to exit code
        
OMX_VC_4x4_VL

        
        TST     availability, #OMX_VC_UPPER_RIGHT
        BEQ     DiagVLUpperRightNotAvailable
       
        VLD1    dAbove0,[pSrcAbove]                      ;// [U7|U6|U5|U4|U3|U2|U1|U0] 
        VEXT    dAbove1,dAbove0,dAbove0,#1               ;// [ X|U7|U6|U5|U4|U3|U2|U1]
        VEXT    dAbove2,dAbove1,dAbove1,#1               ;// [ X| X|U7|U6|U5|U4|U3|U2]
        
        B       DiagVLPredict4x4Store         
       
DiagVLUpperRightNotAvailable
        VLD1    dAboveU32[1],[pSrcAbove]                 ;// [U3|U2|U1|U0|-|-|-|-] 
        VDUP    dU3, dAbove[7]                           ;// [U3 U3 U3 U3 U3 U3 U3 U3]

        VEXT    dAbove0, dAbove, dU3, #4                 ;// [U3 U3 U3 U3 U3 U2 U1 U0]
        VEXT    dAbove1, dAbove, dU3, #5                 ;// [U3 U3 U3 U3 U3 U3 U2 U1]
        VEXT    dAbove2, dAbove, dU3, #6                 ;// [U3 U3 U3 U3 U3 U3 U3 U2]
       
DiagVLPredict4x4Store  
        
        VRHADD  dTmp0, dAbove1, dAbove0                 ;// (a+b+1)>>1
                                                        ;// Tmp[ X| X| X| 8| 6| 4| 2| 0 ]
        
        VHADD   dTmp3, dAbove0, dAbove2
        VRHADD  dTmp3, dTmp3, dAbove1                   ;// (a+2*b+c+2)>>2
                                                        ;// Tmp[ X| X| X| 9| 7| 5| 3| 1 ]
                                                         
        VEXT    dTmp1,dTmp0,dTmp0,#1                    ;// Tmp[ X| X| X| X| 8| 6| 4| 2 ]
        ADD     pDstTmp, pDst, dstStep
        ADD     dstep, dstStep, dstStep
        VEXT    dTmp2,dTmp3,dTmp1,#1                    ;// Tmp[ X| X| X| X| 9| 7| 5| 3 ]
        
        VST1    dTmp0U32[0],[pDst],dstep                ;// Tmp[6],[4],[2],[0]
        VST1    dTmp3U32[0],[pDstTmp],dstep             ;// Tmp[7],[5],[3],[1]
        VST1    dTmp1U32[0],[pDst]                      ;// Tmp[8],[6],[4],[2]
        VST1    dTmp2U32[0],[pDstTmp]                   ;// Tmp[9],[7],[5],[3]
        
        B        ExitPredict4x4                         ;// Branch to exit code
        
OMX_VC_4x4_HU
        ADD     pSrcTmp, pSrcLeft, leftStep
        ADD     srcStep, leftStep, leftStep

        ;// Load Left Edge                              ;// [L3|L2|L1|L0|X|X|X|X]
        VLD1    {dLeft[4]},[pSrcLeft],srcStep           ;// pSrcLeft[0*leftStep]
        VLD1    {dLeft[5]},[pSrcTmp],srcStep            ;// pSrcLeft[1*leftStep]
        VLD1    {dLeft[6]},[pSrcLeft]                   ;// pSrcLeft[2*leftStep]
        VLD1    {dLeft[7]},[pSrcTmp]                    ;// pSrcLeft[3*leftStep]
        
        VDUP    dL3,dLeft[7]                            ;// [L3|L3|L3|L3|L3|L3|L3|L3]
        
        VEXT    dLeftHU0,dLeft,dL3,#4                   ;// [L3|L3|L3|L3|L3|L2|L1|L0]
        VEXT    dLeftHU1,dLeft,dL3,#5                   ;// [L3|L3|L3|L3|L3|L3|L2|L1]
        VEXT    dLeftHU2,dLeft,dL3,#6                   ;// [L3|L3|L3|L3|L3|L3|L3|L2]
        
        VHADD   dTmp0, dLeftHU0, dLeftHU2
        VRHADD  dTmp0, dTmp0, dLeftHU1                  ;// Tmp[ L3 | L3 | L3 | L3 | L3 | 5 | 3 | 1 ]
        
        VRHADD  dTmp1, dLeftHU1, dLeftHU0               ;// (a+b+1)>>1 
                                                        ;//  Tmp[ L3 | L3 | L3 | L3 | L3 | 4 | 2 | 0 ]
                                                      
        VZIP    dTmp1,dTmp0                             ;// dTmp1 = Tmp[7| 6| 5| 4| 3| 2| 1| 0]  
                                                        ;// dTmp0 = [L3|L3|L3|L3|L3|L3|L3|L3]
                                                                                                                            
        
        VST1    dTmp1U32[0],[pDst],dstStep              ;// [3|2|1|0] 
        VEXT    dTmp1,dTmp1,dTmp1,#2
        VST1    dTmp1U32[0],[pDst],dstStep              ;// [5|4|3|2] 
        VEXT    dTmp1,dTmp1,dTmp1,#2
        VST1    dTmp1U32[0],[pDst],dstStep              ;// [7|6|5|4]  
        VST1    dTmp0U32[0],[pDst]                      ;// [9|8|7|6] 
        
        
ExitPredict4x4
        
        MOV      return,  #OMX_Sts_NoErr
        M_END

        ENDIF ;// CortexA8
        
        END
;//-----------------------------------------------------------------------------------------------
;// omxVCM4P10_PredictIntra_4x4 ends
;//-----------------------------------------------------------------------------------------------
