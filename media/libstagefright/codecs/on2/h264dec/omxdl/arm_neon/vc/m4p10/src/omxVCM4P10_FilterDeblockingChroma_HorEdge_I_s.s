;//
;// 
;// File Name:  omxVCM4P10_FilterDeblockingChroma_HorEdge_I_s.s
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

;// Loop 

XY          RN 7

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
        M_START omxVCM4P10_FilterDeblockingChroma_HorEdge_I, r9, d15
        
        ;//Arguments on the stack
        M_ARG   ppThresholds, 4
        M_ARG   ppBS, 4
        
        ;// d0-dAlpha_0
        ;// d2-dBeta_0

        ;load alpha1,beta1 somewhere to avoid more loads
        VLD1        {dAlpha[]}, [pAlpha]!
        SUB         pSrcDst, pSrcDst, srcdstStep, LSL #1 ;?
        SUB         pSrcDst, pSrcDst, srcdstStep
        VLD1        {dBeta[]}, [pBeta]! 
        
        M_LDR       pBS, ppBS
        M_LDR       pThresholds, ppThresholds 

        LDR         Mask_3, =MASK_3
        LDR         Mask_4, =MASK_4

        VMOV        dMask_0, #0     
        VMOV        dMask_1, #1     
        VMOV        dMask_4, #4     
        
        LDR         XY, =LOOP_COUNT

        ;// p0-p3 - d4-d7
        ;// q0-q3 - d8-d11
LoopY        
        LDR         bS3210, [pBS], #8
        
        VLD1        dP_2, [pSrcDst], srcdstStep
        ;1
        VLD1        dP_1, [pSrcDst], srcdstStep
        CMP         bS3210, #0
        VLD1        dP_0, [pSrcDst], srcdstStep
        ;1
        VLD1        dQ_0, [pSrcDst], srcdstStep
        VABD        dAp2p0, dP_2, dP_0
        VLD1        dQ_1, [pSrcDst], srcdstStep
        VABD        dAp0q0, dP_0, dQ_0
        VLD1        dQ_2, [pSrcDst], srcdstStep
        BEQ         NoFilterBS0

        VABD        dAp1p0, dP_1, dP_0
        VABD        dAq1q0, dQ_1, dQ_0

        VCGT        dFilt, dAlpha, dAp0q0
        VMOV.U32    dBS3210[0], bS3210
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

        SUB         pSrcDst, pSrcDst, srcdstStep, LSL #2
        VTST        dFilt_bs, dFilt_bs, dMask_4

        ;// bS == 4 Filtering
        BLNE        armVCM4P10_DeblockingChromabSGE4_unsafe
                    
        VBIT        dP_0n, dP_0t, dFilt_bs
        VBIT        dQ_0n, dQ_0t, dFilt_bs
        
        VBIF        dP_0n, dP_0, dFilt      
        VBIF        dQ_0n, dQ_0, dFilt  

        ;// Result Storage
        VST1        dP_0n, [pSrcDst], srcdstStep
        ADDS        XY, XY, XY
        VST1        dQ_0n, [pSrcDst], srcdstStep

        BNE         LoopY        
        
        MOV         r0, #OMX_Sts_NoErr

        M_EXIT
        
NoFilterBS0

        VLD1        {dAlpha[]}, [pAlpha]
        SUB         pSrcDst, pSrcDst, srcdstStep, LSL #1
        ADDS        XY, XY, XY
        VLD1        {dBeta[]}, [pBeta]
        ADD         pThresholds, pThresholds, #4
        BNE         LoopY        

        MOV         r0, #OMX_Sts_NoErr
        M_END
        
        ENDIF
        

        END
        
        
