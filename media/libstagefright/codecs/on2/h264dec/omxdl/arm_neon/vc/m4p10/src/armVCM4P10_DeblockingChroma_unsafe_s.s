;//
;// 
;// File Name:  armVCM4P10_DeblockingChroma_unsafe_s.s
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


    IF  CortexA8
        
pAlpha      RN 2
pBeta       RN 3

pThresholds RN 5
pBS         RN 4
bS3210      RN 6

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

;// bSLT4
dTC3210     DN D18.U8   
dTCs        DN D31.S8
dTC         DN D31.U8

dMask_0     DN D14.U8
dMask_1     DN D15.U8    
dMask_4     DN D26.U16

dTemp       DN D28.U8
dDummy      DN D17.U8

;// Computing P0,Q0
qDq0p0      QN Q10.S16
qDp1q1      QN Q11.S16
qDelta      QN Q10.S16  ; reuse qDq0p0
dDelta      DN D20.S8


;// Computing P1,Q1
qP_0n       QN Q14.S16
qQ_0n       QN Q12.S16

dQ_0n       DN D24.U8
dP_0n       DN D29.U8

;// bSGE4

dHSp0q1     DN D13.U8
dHSq0p1     DN D31.U8   

dBS3210     DN D28.U16

dP_0t       DN D13.U8   ;dHSp0q1        
dQ_0t       DN D31.U8   ;Temp1        

dP_0n       DN D29.U8
dQ_0n       DN D24.U8   ;Temp2        

;// Register usage for - armVCM4P10_DeblockingLumabSLT4_unsafe
;//
;// Inputs - Pixels             - p0-p3: D4-D7, q0-q3: D8-D11
;//        - Filter masks       - filt: D16, aqflg: D12, apflg: D17
;//        - Additional Params  - pThresholds: r5
;//         
;// Outputs - Pixels            - P0-P1: D29-D30, Q0-Q1: D24-D25
;//         - Additional Params - pThresholds: r5

;// Registers Corrupted         - D18-D31


        M_START armVCM4P10_DeblockingChromabSLT4_unsafe

        
        ;dTC3210 -18
        ;dTemp-28

        VLD1        d18.U32[0], [pThresholds]! ;here

        ;// delta = (((q0-p0)<<2) + (p1-q1) + 4) >> 3;
        ;// dDelta = (qDp1q1 >> 2 + qDq0p0 + 1)>> 1

        ;// qDp1q1-11
        ;// qDq0p0-10
        VSUBL       qDp1q1, dP_1, dQ_1      
        VMOV        dTemp, dTC3210
        VSUBL       qDq0p0, dQ_0, dP_0      
        VSHR        qDp1q1, qDp1q1, #2      
        VZIP.8      dTC3210, dTemp
    
        ;// qDelta-qDq0p0-10

        ;// dTC = dTC01 + (dAplg & 1) + (dAqflg & 1)

        ;// dTC3210-18
        ;// dTemp-28
        ;// dTC-31
        VBIF        dTC3210, dMask_0, dFilt
        VRHADD      qDelta, qDp1q1, qDq0p0  
        VADD        dTC, dTC3210, dMask_1
        VQMOVN      dDelta, qDelta
        ;// dDelta-d20

        ;// dDelta = (OMX_U8)armClip(0, 255, q0 - delta);
        VLD1        {dAlpha[]}, [pAlpha]
        VMIN        dDelta, dDelta, dTCs
        VNEG        dTCs, dTCs
        VLD1        {dBeta[]}, [pBeta]
        ;1
        VMAX        dDelta, dDelta, dTCs

        ;// dP_0n - 29
        ;// dQ_0n - 24

        ;// pQ0[-1*Step] = (OMX_U8)armClip(0, 255, dP_0 - delta);
        ;// pQ0[0*Step] = (OMX_U8)armClip(0, 255, dQ_0 - delta);

        ;// dP_0n = (OMX_U8)armClip(0, 255, dP_0 - dDelta);
        ;// dQ_0n = (OMX_U8)armClip(0, 255, dP_0 - dDelta);
        
        ;// qP_0n - 14
        ;// qQ_0n - 12
        
        VMOVL       qP_0n, dP_0
        VMOVL       qQ_0n, dQ_0

        ;1
        VADDW       qP_0n, qP_0n, dDelta
        VSUBW       qQ_0n, qQ_0n, dDelta
        
        VQMOVUN     dP_0n, qP_0n
        VQMOVUN     dQ_0n, qQ_0n

        M_END

;// Register usage for - armVCM4P10_DeblockingLumabSGE4_unsafe()
;//
;// Inputs - Pixels             - p0-p3: D4-D7, q0-q3: D8-D11
;//        - Filter masks       - filt: D16, aqflg: D12, apflg: D17
;//        - Additional Params  - alpha: D0, dMask_1: D15
;//         
;// Outputs - Pixels            - P0-P2: D29-D31, Q0-Q2: D24,D25,D28

;// Registers Corrupted         - D18-D31

        M_START armVCM4P10_DeblockingChromabSGE4_unsafe
    
        ;dHSq0p1 - 31
        ;dHSp0q1 - 13
        VHADD       dHSp0q1, dP_0, dQ_1     
        VHADD       dHSq0p1, dQ_0, dP_1         

        ;// Prepare the bS mask

        ;// dHSp0q1-13
        ;// dP_0t-dHSp0q1-13
        ;// dHSq0p1-31
        ;// dQ_0t-Temp1-31
        VLD1        {dAlpha[]}, [pAlpha]
        ADD         pThresholds, pThresholds, #4
        VLD1        {dBeta[]}, [pBeta]

        VRHADD      dP_0t, dHSp0q1, dP_1    
        VRHADD      dQ_0t, dHSq0p1, dQ_1
        
        M_END
        
        ENDIF  

        END
