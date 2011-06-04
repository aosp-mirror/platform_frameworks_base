;//
;// 
;// File Name:  omxVCM4P10_FilterDeblockingChroma_VerEdge_I_s.s
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

        IF CortexA8

        IMPORT  armVCM4P10_DeblockingChromabSGE4_unsafe
        IMPORT  armVCM4P10_DeblockingChromabSLT4_unsafe
        
LOOP_COUNT  EQU 0x40000000
MASK_3      EQU 0x03030303
MASK_4      EQU 0x04040404

;// Function arguments

pSrcDst     RN 0
srcdstStep  RN 1
pAlpha      RN 2
pBeta       RN 3

pThresholds RN 5
pBS         RN 4
bS3210      RN 6
pSrcDst_P   RN 10
pSrcDst_Q   RN 12

pTmp        RN 10
pTmp2       RN 12
step        RN 14

;// Loop 

XY          RN 7

;// Rows input
dRow0       DN D7.U8
dRow1       DN D8.U8  
dRow2       DN D5.U8  
dRow3       DN D10.U8  
dRow4       DN D6.U8  
dRow5       DN D9.U8  
dRow6       DN D4.U8 
dRow7       DN D11.U8 


;// Pixels
dP_0        DN D4.U8
dP_1        DN D5.U8  
dP_2        DN D6.U8  
dQ_0        DN D8.U8  
dQ_1        DN D9.U8  
dQ_2        DN D10.U8 

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

qBS3210     QN Q13.U16
dBS3210     DN D26
dMask_bs    DN D27
dFilt_bs    DN D26.U16

;// bSLT4
dMask_0     DN D14.U8
dMask_1     DN D15.U8    
dMask_4     DN D1.U16

Mask_4      RN 8
Mask_3      RN 9

dTemp       DN D19.U8

;// Result
dP_0t       DN D13.U8   
dQ_0t       DN D31.U8   

dP_0n       DN D29.U8
dQ_0n       DN D24.U8

        
        ;// Function header
        M_START omxVCM4P10_FilterDeblockingChroma_VerEdge_I, r12, d15
        
        ;//Arguments on the stack
        M_ARG   ppThresholds, 4
        M_ARG   ppBS, 4
        
        ;// d0-dAlpha_0
        ;// d2-dBeta_0

        ;load alpha1,beta1 somewhere to avoid more loads
        VLD1        {dAlpha[]}, [pAlpha]!
        SUB         pSrcDst, pSrcDst, #4
        VLD1        {dBeta[]}, [pBeta]! 
        
        M_LDR       pBS, ppBS
        M_LDR       pThresholds, ppThresholds 

        LDR         Mask_4, =MASK_4
        LDR         Mask_3, =MASK_3

        ;dMask_0-14
        ;dMask_1-15
        ;dMask_4-19

        VMOV        dMask_0, #0     
        VMOV        dMask_1, #1     
        VMOV        dMask_4, #4     
        
        LDR         XY, =LOOP_COUNT

        ;// p0-p3 - d4-d7
        ;// q0-q3 - d8-d11


LoopY        
        LDR         bS3210, [pBS], #8
        ADD         pTmp, pSrcDst, srcdstStep
        ADD         step, srcdstStep, srcdstStep
        
        ;1
        VLD1        dRow0, [pSrcDst], step
        ;1
        VLD1        dRow1, [pTmp], step
        VLD1        dRow2, [pSrcDst], step
        VLD1        dRow3, [pTmp], step
        VLD1        dRow4, [pSrcDst], step
        VLD1        dRow5, [pTmp], step
        VLD1        dRow6, [pSrcDst], step
        VLD1        dRow7, [pTmp], step
        
        
        ;// dRow0 = [q3r0 q2r0 q1r0 q0r0 p0r0 p1r0 p2r0 p3r0]
        ;// dRow1 = [q3r1 q2r1 q1r1 q0r1 p0r1 p1r1 p2r1 p3r1]
        ;// dRow2 = [q3r2 q2r2 q1r2 q0r2 p0r2 p1r2 p2r2 p3r2]
        ;// dRow3 = [q3r3 q2r3 q1r3 q0r3 p0r3 p1r3 p2r3 p3r3]
        ;// dRow4 = [q3r4 q2r4 q1r4 q0r4 p0r4 p1r4 p2r4 p3r4]
        ;// dRow5 = [q3r5 q2r5 q1r5 q0r5 p0r5 p1r5 p2r5 p3r5]
        ;// dRow6 = [q3r6 q2r6 q1r6 q0r6 p0r6 p1r6 p2r6 p3r6]
        ;// dRow7 = [q3r7 q2r7 q1r7 q0r7 p0r7 p1r7 p2r7 p3r7]

        ;// 8x8 Transpose
        VZIP.8      dRow0, dRow1
        VZIP.8      dRow2, dRow3
        VZIP.8      dRow4, dRow5
        VZIP.8      dRow6, dRow7

        VZIP.16     dRow0, dRow2
        VZIP.16     dRow1, dRow3
        VZIP.16     dRow4, dRow6
        VZIP.16     dRow5, dRow7

        VZIP.32     dRow0, dRow4
        VZIP.32     dRow2, dRow6
        VZIP.32     dRow3, dRow7
        VZIP.32     dRow1, dRow5


        ;Realign the pointers

        CMP         bS3210, #0
        VABD        dAp2p0, dP_2, dP_0
        VABD        dAp0q0, dP_0, dQ_0
        BEQ         NoFilterBS0

        VABD        dAp1p0, dP_1, dP_0
        VABD        dAq1q0, dQ_1, dQ_0

        VMOV.U32    dBS3210[0], bS3210
        VCGT        dFilt, dAlpha, dAp0q0
        VMAX        dAp1p0, dAq1q0, dAp1p0
        VMOVL       qBS3210, dBS3210.U8
        VABD        dAq2q0, dQ_2, dQ_0
        VCGT        dMask_bs.S16, dBS3210.S16, #0

        VCGT        dAp1p0, dBeta, dAp1p0
        VCGT        dAp2p0, dBeta, dAp2p0
        VAND        dFilt, dMask_bs.U8

        TST         bS3210, Mask_3

        VCGT        dAq2q0, dBeta, dAq2q0
        VAND        dFilt, dFilt, dAp1p0

        VAND        dAqflg, dFilt, dAq2q0
        VAND        dApflg, dFilt, dAp2p0

        ;// bS < 4 Filtering
        BLNE        armVCM4P10_DeblockingChromabSLT4_unsafe

        TST         bS3210, Mask_4        

        SUB         pSrcDst, pSrcDst, srcdstStep, LSL #3
        VTST        dFilt_bs, dFilt_bs, dMask_4

        ;// bS == 4 Filtering
        BLNE        armVCM4P10_DeblockingChromabSGE4_unsafe

        VBIT        dP_0n, dP_0t, dFilt_bs
        VBIT        dQ_0n, dQ_0t, dFilt_bs

        ;// Result Storage
        ADD         pSrcDst_P, pSrcDst, #3
        VBIF        dP_0n, dP_0, dFilt      
        
        ADD         pTmp2, pSrcDst_P, srcdstStep
        ADD         step, srcdstStep, srcdstStep
        VBIF        dQ_0n, dQ_0, dFilt  

        ADDS        XY, XY, XY
        
        VST1        {dP_0n[0]}, [pSrcDst_P], step
        VST1        {dP_0n[1]}, [pTmp2], step
        VST1        {dP_0n[2]}, [pSrcDst_P], step
        VST1        {dP_0n[3]}, [pTmp2], step
        VST1        {dP_0n[4]}, [pSrcDst_P], step
        VST1        {dP_0n[5]}, [pTmp2], step
        VST1        {dP_0n[6]}, [pSrcDst_P], step
        VST1        {dP_0n[7]}, [pTmp2], step
        
        ADD         pSrcDst_Q, pSrcDst, #4
        ADD         pTmp, pSrcDst_Q, srcdstStep
        
        VST1        {dQ_0n[0]}, [pSrcDst_Q], step
        VST1        {dQ_0n[1]}, [pTmp], step
        VST1        {dQ_0n[2]}, [pSrcDst_Q], step
        VST1        {dQ_0n[3]}, [pTmp], step
        VST1        {dQ_0n[4]}, [pSrcDst_Q], step
        VST1        {dQ_0n[5]}, [pTmp], step
        VST1        {dQ_0n[6]}, [pSrcDst_Q], step
        VST1        {dQ_0n[7]}, [pTmp], step
        
        ADD         pSrcDst, pSrcDst, #4

        BNE         LoopY        
        
        MOV         r0, #OMX_Sts_NoErr
        
        M_EXIT
        
NoFilterBS0
        VLD1        {dAlpha[]}, [pAlpha]
        ADD         pSrcDst, pSrcDst, #4
        SUB         pSrcDst, pSrcDst, srcdstStep, LSL #3
        ADDS        XY, XY, XY
        VLD1        {dBeta[]}, [pBeta]
        ADD         pThresholds, pThresholds, #4
        BNE         LoopY        

        MOV         r0, #OMX_Sts_NoErr

        M_END
        
        ENDIF


        END
        
        
