;//
;// 
;// File Name:  omxVCM4P10_FilterDeblockingLuma_VerEdge_I_s.s
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

        IMPORT  armVCM4P10_DeblockingLumabSLT4_unsafe
        IMPORT  armVCM4P10_DeblockingLumabSGE4_unsafe
        
        IF CortexA8

LOOP_COUNT  EQU 0x11000000


;// Function arguments

pSrcDst     RN 0
srcdstStep  RN 1
pAlpha      RN 2
pBeta       RN 3

pThresholds RN 5
pBS         RN 4
bS10        RN 12

pAlpha_0    RN 2
pBeta_0     RN 3

pAlpha_1    RN 7
pBeta_1     RN 8

pTmp        RN 10
pTmpStep    RN 11

;// Loop 

XY          RN 9

;// Rows input
dRow0       DN D7.U8
dRow1       DN D8.U8  
dRow2       DN D5.U8  
dRow3       DN D10.U8  
dRow4       DN D6.U8  
dRow5       DN D9.U8  
dRow6       DN D4.U8 
dRow7       DN D11.U8 

;// dRow0 - dP_3, dRow1 - dQ_0, dRow2 - dP_1, dRow3 - dQ_2
;// dRow4 - dP_2, dRow5 - dQ_1, dRow6 - dP_0, dRow7 - dQ_3

;// Rows output
dRown0      DN D7.U8
dRown1      DN D24.U8
dRown2      DN D30.U8
dRown3      DN D10.U8
dRown4      DN D6.U8
dRown5      DN D25.U8
dRown6      DN D29.U8
dRown7      DN D11.U8

;// dP_0n       DN D29.U8
;// dP_1n       DN D30.U8
;// dP_2n       DN D31.U8
;// 
;// dQ_0n       DN D24.U8   ;!!;Temp2        
;// dQ_1n       DN D25.U8   ;!!;Temp2        
;// dQ_2n       DN D28.U8   ;!!;dQ_0t        
;// 
;// dRown0 - dP_3,  dRown1 - dQ_0n
;// dRown2 - dP_1n, dRown3 - dQ_2
;// dRown4 - dP_2,  dRown5 - dQ_1n
;// dRown6 - dP_0n, dRown7 - dQ_3

dRow0n      DN D7.U8
dRow1n      DN D24.U8
dRow2n      DN D30.U8
dRow3n      DN D28.U8
dRow4n      DN D31.U8
dRow5n      DN D25.U8
dRow6n      DN D29.U8
dRow7n      DN D11.U8

;// dRow0n - dP_3, dRow1n - dQ_0n, dRow2n - dP_1n, dRow3n - dQ_2n
;// dRow4n - dP_2, dRow5n - dQ_1n, dRow6n - dP_0n, dRow7n - dQ_3

;// Pixels
dP_0        DN D4.U8
dP_1        DN D5.U8  
dP_2        DN D6.U8  
dP_3        DN D7.U8  
dQ_0        DN D8.U8  
dQ_1        DN D9.U8  
dQ_2        DN D10.U8 
dQ_3        DN D11.U8 


;// Filtering Decision
dAlpha      DN D0.U8
dBeta       DN D2.U8

dFilt       DN D16.U8
dAqflg      DN D12.U8
dApflg      DN D17.U8 

dAp0q0      DN D13.U8
dAp1p0      DN D12.U8
dAq1q0      DN D18.U8
dAp2p0      DN D19.U8
dAq2q0      DN D17.U8

;// bSLT4
dTC0        DN D18.U8   
dTC1        DN D19.U8   
dTC01       DN D18.U8   

dTCs        DN D31.S8
dTC         DN D31.U8

dMask_0     DN D14.U8
dMask_1     DN D15.U8    

Mask_0      RN 6

dTemp       DN D19.U8

;// Computing P0,Q0
qDq0p0      QN Q10.S16
qDp1q1      QN Q11.S16
qDelta      QN Q10.S16  ; reuse qDq0p0
dDelta      DN D20.S8


;// Computing P1,Q1
dRp0q0      DN D24.U8

dMaxP       DN D23.U8
dMinP       DN D22.U8

dMaxQ       DN D19.U8
dMinQ       DN D21.U8

dDeltaP     DN D26.U8
dDeltaQ     DN D27.U8

qP_0n       QN Q14.S16
qQ_0n       QN Q12.S16

dQ_0n       DN D24.U8
dQ_1n       DN D25.U8
dP_0n       DN D29.U8
dP_1n       DN D30.U8

;// bSGE4

qSp0q0      QN Q10.U16

qSp2q1      QN Q11.U16
qSp0q0p1    QN Q12.U16
qSp3p2      QN Q13.U16
dHSp0q1     DN D28.U8

qSq2p1      QN Q11.U16
qSp0q0q1    QN Q12.U16
qSq3q2      QN Q13.U16  ;!!
dHSq0p1     DN D28.U8   ;!!

qTemp1      QN Q11.U16  ;!!;qSp2q1 
qTemp2      QN Q12.U16  ;!!;qSp0q0p1        

dP_0t       DN D28.U8   ;!!;dHSp0q1        
dQ_0t       DN D22.U8   ;!!;Temp1        

dP_0n       DN D29.U8
dP_1n       DN D30.U8
dP_2n       DN D31.U8

dQ_0n       DN D24.U8   ;!!;Temp2        
dQ_1n       DN D25.U8   ;!!;Temp2        
dQ_2n       DN D28.U8   ;!!;dQ_0t        

        
        ;// Function header
        M_START omxVCM4P10_FilterDeblockingLuma_VerEdge_I, r11, d15
        
        ;//Arguments on the stack
        M_ARG   ppThresholds, 4
        M_ARG   ppBS, 4
        
        ;// d0-dAlpha_0
        ;// d2-dBeta_0

        ADD         pAlpha_1, pAlpha_0, #1
        ADD         pBeta_1, pBeta_0, #1
        
        VLD1        {dAlpha[]}, [pAlpha_0]
        SUB         pSrcDst, pSrcDst, #4
        VLD1        {dBeta[]}, [pBeta_0] 
        
        M_LDR       pBS, ppBS
        M_LDR       pThresholds, ppThresholds 

        MOV         Mask_0,#0

        ;dMask_0-14
        ;dMask_1-15

        VMOV        dMask_0, #0     
        VMOV        dMask_1, #1     

        LDR         XY,=LOOP_COUNT
    
        ADD         pTmpStep, srcdstStep, srcdstStep

        ;// p0-p3 - d4-d7
        ;// q0-q3 - d8-d11
LoopY        
LoopX        
        LDRH        bS10, [pBS], #4

        CMP         bS10, #0
        BEQ         NoFilterBS0

        ;// Load 8 rows of data
        ADD         pTmp, pSrcDst, srcdstStep
        VLD1        dRow0, [pSrcDst], pTmpStep
        VLD1        dRow1, [pTmp], pTmpStep
        VLD1        dRow2, [pSrcDst], pTmpStep
        VZIP.8      dRow0, dRow1
        VLD1        dRow3, [pTmp], pTmpStep
        VLD1        dRow4, [pSrcDst], pTmpStep
        VZIP.8      dRow2, dRow3
        VLD1        dRow5, [pTmp], pTmpStep
        VLD1        dRow6, [pSrcDst], pTmpStep
        VLD1        dRow7, [pTmp], pTmpStep
        VZIP.8      dRow4, dRow5
        VZIP.16     dRow1, dRow3
    

        ;// dRow0 = [q3r0 q2r0 q1r0 q0r0 p0r0 p1r0 p2r0 p3r0]
        ;// dRow1 = [q3r1 q2r1 q1r1 q0r1 p0r1 p1r1 p2r1 p3r1]
        ;// dRow2 = [q3r2 q2r2 q1r2 q0r2 p0r2 p1r2 p2r2 p3r2]
        ;// dRow3 = [q3r3 q2r3 q1r3 q0r3 p0r3 p1r3 p2r3 p3r3]
        ;// dRow4 = [q3r4 q2r4 q1r4 q0r4 p0r4 p1r4 p2r4 p3r4]
        ;// dRow5 = [q3r5 q2r5 q1r5 q0r5 p0r5 p1r5 p2r5 p3r5]
        ;// dRow6 = [q3r6 q2r6 q1r6 q0r6 p0r6 p1r6 p2r6 p3r6]
        ;// dRow7 = [q3r7 q2r7 q1r7 q0r7 p0r7 p1r7 p2r7 p3r7]

        ;// 8x8 Transpose

        VZIP.8      dRow6, dRow7

        SUB         pSrcDst, pSrcDst, srcdstStep, LSL #3
        VZIP.16     dRow0, dRow2
        VZIP.16     dRow5, dRow7
        

        VZIP.16     dRow4, dRow6
        VZIP.32     dRow1, dRow5
        VZIP.32     dRow2, dRow6
        VZIP.32     dRow3, dRow7
        VZIP.32     dRow0, dRow4
        

        ;// dRow0 - dP_3, dRow1 - dQ_0, dRow2 - dP_1, dRow3 - dQ_2
        ;// dRow4 - dP_2, dRow5 - dQ_1, dRow6 - dP_0, dRow7 - dQ_3

        ;// dQ_0 = [q0r7 q0r6 q0r5 q0r4 q0r3 q0r2 q0r1 q0r0]
        ;// dQ_1 = [q1r7 q1r6 q1r5 q1r4 q1r3 q1r2 q1r1 q1r0]
        ;// dQ_2 = [q2r7 q2r6 q2r5 q2r4 q2r3 q2r2 q2r1 q2r0]
        ;// dQ_3 = [q3r7 q3r6 q3r5 q3r4 q3r3 q3r2 q3r1 q3r0]

        ;// dP_0 = [p0r7 p0r6 p0r5 p0r4 p0r3 p0r2 p0r1 p0r0]
        ;// dP_1 = [p1r7 p1r6 p1r5 p1r4 p1r3 p1r2 p1r1 p1r0]
        ;// dP_2 = [p2r7 p2r6 p2r5 p2r4 p2r3 p2r2 p2r1 p2r0]
        ;// dP_3 = [p3r7 p3r6 p3r5 p3r4 p3r3 p3r2 p3r1 p3r0]

        VABD        dAp0q0, dP_0, dQ_0
        VABD        dAp1p0, dP_1, dP_0

        VABD        dAq1q0, dQ_1, dQ_0
        VABD        dAp2p0, dP_2, dP_0
        
        TST         bS10, #0xff
        VCGT        dFilt, dAlpha, dAp0q0

        VMAX        dAp1p0, dAq1q0, dAp1p0
        VABD        dAq2q0, dQ_2, dQ_0

        VMOVEQ.U32  dFilt[0], Mask_0
        TST         bS10, #0xff00

        VCGT        dAp2p0, dBeta, dAp2p0
        VCGT        dAp1p0, dBeta, dAp1p0

        VMOVEQ.U32  dFilt[1], Mask_0

        VCGT        dAq2q0, dBeta, dAq2q0
        VAND        dFilt, dFilt, dAp1p0
        TST         bS10, #4 

        VAND        dAqflg, dFilt, dAq2q0
        VAND        dApflg, dFilt, dAp2p0
    
        BNE         bSGE4        
bSLT4
        ;// bS < 4 Filtering

        BL          armVCM4P10_DeblockingLumabSLT4_unsafe

        ;// Transpose

        VZIP.8      dP_3,  dP_2  
        VZIP.8      dP_1n, dP_0n
        VZIP.8      dQ_0n, dQ_1n
        VZIP.8      dQ_2,  dQ_3

        
        VZIP.16     dP_3,  dP_1n
        ADD         pTmp, pSrcDst, srcdstStep
        VZIP.16     dQ_0n, dQ_2
        VZIP.16     dQ_1n, dQ_3
        VZIP.16     dP_2,  dP_0n

        VZIP.32     dP_3,  dQ_0n
        VZIP.32     dP_1n, dQ_2
        VZIP.32     dP_2,  dQ_1n
        VZIP.32     dP_0n, dQ_3

        ;// dRown0 - dP_3,  dRown1 - dQ_0n
        ;// dRown2 - dP_1n, dRown3 - dQ_2
        ;// dRown4 - dP_2,  dRown5 - dQ_1n
        ;// dRown6 - dP_0n, dRown7 - dQ_3

        VST1        dRown0, [pSrcDst], pTmpStep
        VST1        dRown1, [pTmp], pTmpStep
        VST1        dRown2, [pSrcDst], pTmpStep
        VST1        dRown3, [pTmp], pTmpStep
        ;1
        VST1        dRown4, [pSrcDst], pTmpStep
        VST1        dRown5, [pTmp], pTmpStep
        ADDS        XY, XY, XY
        VST1        dRown6, [pSrcDst], pTmpStep
        ADD         pThresholds, pThresholds, #2
        VST1        dRown7, [pTmp], srcdstStep

        SUB         pSrcDst, pSrcDst, srcdstStep, LSL #3
        VLD1        {dAlpha[]}, [pAlpha_1]
        ADD         pSrcDst, pSrcDst, #4
        VLD1        {dBeta[]}, [pBeta_1]

        BCC         LoopX
        B           ExitLoopY        

NoFilterBS0
        ADD         pSrcDst, pSrcDst, #4
        ADDS        XY, XY, XY
        VLD1        {dAlpha[]}, [pAlpha_1]
        ADD         pThresholds, pThresholds, #4
        VLD1        {dBeta[]}, [pBeta_1]
        BCC         LoopX
        B           ExitLoopY        
bSGE4        
        ;// bS >= 4 Filtering
        
        BL          armVCM4P10_DeblockingLumabSGE4_unsafe

        ;// Transpose

        VZIP.8      dP_3,  dP_2n   
        VZIP.8      dP_1n, dP_0n
        VZIP.8      dQ_0n, dQ_1n
        VZIP.8      dQ_2n, dQ_3

        VZIP.16     dP_3,  dP_1n
        ADD         pTmp, pSrcDst, srcdstStep
        VZIP.16     dQ_0n, dQ_2n
        VZIP.16     dQ_1n, dQ_3
        VZIP.16     dP_2n, dP_0n

        VZIP.32     dP_3,  dQ_0n
        VZIP.32     dP_1n, dQ_2n
        VZIP.32     dP_2n, dQ_1n
        VZIP.32     dP_0n, dQ_3

        ;// dRow0n - dP_3, dRow1n - dQ_0n, dRow2n - dP_1n, dRow3n - dQ_2n
        ;// dRow4n - dP_2, dRow5n - dQ_1n, dRow6n - dP_0n, dRow7n - dQ_3
        
        VST1        dRow0n, [pSrcDst], pTmpStep
        VST1        dRow1n, [pTmp], pTmpStep
        VST1        dRow2n, [pSrcDst], pTmpStep
        VST1        dRow3n, [pTmp], pTmpStep
        VST1        dRow4n, [pSrcDst], pTmpStep
        VST1        dRow5n, [pTmp], pTmpStep
        ADDS        XY,XY,XY
        VST1        dRow6n, [pSrcDst], pTmpStep
        ADD         pThresholds, pThresholds, #4
        VST1        dRow7n, [pTmp], pTmpStep

        SUB         pSrcDst, pSrcDst, srcdstStep, LSL #3
        VLD1        {dAlpha[]}, [pAlpha_1]
        ADD         pSrcDst, pSrcDst, #4
        VLD1        {dBeta[]}, [pBeta_1]

        BCC         LoopX

ExitLoopY        
        SUB         pBS, pBS, #14
        SUB         pThresholds, pThresholds, #14
        SUB         pSrcDst, pSrcDst, #16
        VLD1        {dAlpha[]}, [pAlpha_0]
        ADD         pSrcDst, pSrcDst, srcdstStep, LSL #3 
        VLD1        {dBeta[]}, [pBeta_0]
        BNE         LoopY

        MOV         r0, #OMX_Sts_NoErr

        M_END
        
    ENDIF
    
        
        END
        
        
