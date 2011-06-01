;//
;// 
;// File Name:  omxVCM4P10_FilterDeblockingLuma_HorEdge_I_s.s
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

LOOP_COUNT  EQU 0x55000000


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



;// Loop 

XY          RN 9

pTmp        RN 6
step        RN 10

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

Mask_0      RN 11

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
        M_START omxVCM4P10_FilterDeblockingLuma_HorEdge_I, r11, d15
        
        ;//Arguments on the stack
        M_ARG   ppThresholds, 4
        M_ARG   ppBS, 4
        
        ;// d0-dAlpha_0
        ;// d2-dBeta_0

        ADD         pAlpha_1, pAlpha_0, #1
        ADD         pBeta_1, pBeta_0, #1
        
        VLD1        {dAlpha[]}, [pAlpha_0]
        SUB         pSrcDst, pSrcDst, srcdstStep, LSL #2
        VLD1        {dBeta[]}, [pBeta_0] 
        
        M_LDR       pBS, ppBS
        M_LDR       pThresholds, ppThresholds 

        MOV         Mask_0,#0

        ;dMask_0-14
        ;dMask_1-15

        VMOV        dMask_0, #0     
        VMOV        dMask_1, #1     
        
        ADD         step, srcdstStep, srcdstStep

        LDR         XY,=LOOP_COUNT

        ;// p0-p3 - d4-d7
        ;// q0-q3 - d8-d11
LoopY        
LoopX        
        LDRH        bS10, [pBS], #2
        ADD         pTmp, pSrcDst, srcdstStep
        CMP         bS10, #0
        BEQ         NoFilterBS0

        VLD1        dP_3, [pSrcDst], step
        VLD1        dP_2, [pTmp], step
        VLD1        dP_1, [pSrcDst], step
        VLD1        dP_0, [pTmp], step
        VLD1        dQ_0, [pSrcDst], step
        VABD        dAp1p0, dP_0, dP_1
        VLD1        dQ_1, [pTmp]
        VABD        dAp0q0, dQ_0, dP_0
        VLD1        dQ_2, [pSrcDst], srcdstStep
        
        VABD        dAq1q0, dQ_1, dQ_0
        VABD        dAp2p0, dP_2, dP_0
        VCGT        dFilt, dAlpha, dAp0q0

        TST         bS10, #0xff
        VMAX        dAp1p0, dAq1q0, dAp1p0
        VABD        dAq2q0, dQ_2, dQ_0

        VMOVEQ.U32  dFilt[0], Mask_0
        TST         bS10, #0xff00

        VCGT        dAp2p0, dBeta, dAp2p0
        VCGT        dAp1p0, dBeta, dAp1p0

        VMOVEQ.U32  dFilt[1], Mask_0

        VCGT        dAq2q0, dBeta, dAq2q0
        VLD1        dQ_3, [pSrcDst]
        VAND        dFilt, dFilt, dAp1p0
        TST         bS10, #4 

        VAND        dAqflg, dFilt, dAq2q0
        VAND        dApflg, dFilt, dAp2p0
    
        BNE         bSGE4        
bSLT4
        ;// bS < 4 Filtering
        SUB         pSrcDst, pSrcDst, srcdstStep, LSL #2
        SUB         pSrcDst, pSrcDst, srcdstStep

        BL          armVCM4P10_DeblockingLumabSLT4_unsafe

        ;// Result Storage
        VST1        dP_1n, [pSrcDst], srcdstStep
        VST1        dP_0n, [pSrcDst], srcdstStep
        SUB         pTmp, pSrcDst, srcdstStep, LSL #2
        VST1        dQ_0n, [pSrcDst], srcdstStep
        ADDS        XY, XY, XY
        VST1        dQ_1n, [pSrcDst]
        ADD         pSrcDst, pTmp, #8

        BCC         LoopX
        B           ExitLoopY        

NoFilterBS0
        ADD         pSrcDst, pSrcDst, #8
        ADDS        XY, XY, XY
        ADD         pThresholds, pThresholds, #2
        BCC         LoopX
        B           ExitLoopY        
bSGE4        
        ;// bS >= 4 Filtering
        SUB         pSrcDst, pSrcDst, srcdstStep, LSL #2
        SUB         pSrcDst, pSrcDst, srcdstStep, LSL #1
        BL          armVCM4P10_DeblockingLumabSGE4_unsafe

        ;// Result Storage
        VST1        dP_2n, [pSrcDst], srcdstStep
        VST1        dP_1n, [pSrcDst], srcdstStep
        VST1        dP_0n, [pSrcDst], srcdstStep
        SUB         pTmp, pSrcDst, srcdstStep, LSL #2
        VST1        dQ_0n, [pSrcDst], srcdstStep
        ADDS        XY,XY,XY
        VST1        dQ_1n, [pSrcDst], srcdstStep
        ADD         pThresholds, pThresholds, #2
        VST1        dQ_2n, [pSrcDst]
        
        ADD         pSrcDst, pTmp, #8
        BCC         LoopX

ExitLoopY        

        SUB         pSrcDst, pSrcDst, #16
        VLD1        {dAlpha[]}, [pAlpha_1]
        ADD         pSrcDst, pSrcDst, srcdstStep, LSL #2 
        VLD1        {dBeta[]}, [pBeta_1]
        BNE         LoopY

        MOV         r0, #OMX_Sts_NoErr

        M_END
        
    ENDIF
    

        

        END
        
        
