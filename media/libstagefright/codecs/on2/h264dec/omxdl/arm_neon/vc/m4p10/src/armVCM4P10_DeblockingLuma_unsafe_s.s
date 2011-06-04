;//
;// 
;// File Name:  armVCM4P10_DeblockingLuma_unsafe_s.s
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
        
pThresholds RN 5

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

dFilt       DN D16.U8
dAqflg      DN D12.U8
dApflg      DN D17.U8 

dAp0q0      DN D13.U8

;// bSLT4
dTC0        DN D18.U8   
dTC1        DN D19.U8   
dTC01       DN D18.U8   

dTCs        DN D31.S8
dTC         DN D31.U8

dMask_0     DN D14.U8
dMask_1     DN D15.U8    

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

;// Register usage for - armVCM4P10_DeblockingLumabSLT4_unsafe
;//
;// Inputs - Pixels             - p0-p3: D4-D7, q0-q3: D8-D11
;//        - Filter masks       - filt: D16, aqflg: D12, apflg: D17
;//        - Additional Params  - pThresholds: r5
;//         
;// Outputs - Pixels            - P0-P1: D29-D30, Q0-Q1: D24-D25
;//         - Additional Params - pThresholds: r5

;// Registers Corrupted         - D18-D31


        M_START armVCM4P10_DeblockingLumabSLT4_unsafe

        
        ;// qDq0p0-10
        VSUBL       qDp1q1, dP_1, dQ_1      
        VLD1        {dTC0[]}, [pThresholds]!
        ;// qDp1q1-11
        VSUBL       qDq0p0, dQ_0, dP_0      
        VLD1        {dTC1[]}, [pThresholds]!

        ;// dRp0q0-24
        VSHR        qDp1q1, qDp1q1, #2      
    
        ;// dTC01 = (dTC1 << 4) | dTC0
        ;// dTC01-18
        VEXT        dTC01, dTC0, dTC1, #4
        ;// dTemp-19
        VAND        dTemp, dApflg, dMask_1
        
        VBIF        dTC01, dMask_0, dFilt
    

        ;// delta = (((q0-p0)<<2) + (p1-q1) + 4) >> 3;
        ;// dDelta = (qDp1q1 >> 2 + qDq0p0 + 1)>> 1

        ;// qDelta-qDq0p0-10
        VRHADD      qDelta, qDp1q1, qDq0p0  
        VRHADD      dRp0q0, dP_0, dQ_0      
        VADD        dTC, dTC01, dTemp

        ;// dTC = dTC01 + (dAplg & 1) + (dAqflg & 1)
        
        VAND        dTemp, dAqflg, dMask_1
        VQADD       dMaxP, dP_1, dTC01      
        VQMOVN      dDelta, qDelta
        VADD        dTC, dTC, dTemp

        ;// dMaxP = QADD(dP_1, dTC01)
        ;// dMinP = QSUB(dP_1, dTC01)
 
        ;// dMaxP-d23
        ;// dMinP-d22
        VQSUB       dMinP, dP_1, dTC01      

        ;// dDelta-d20

        ;// dMaxQ = QADD(dQ_1, dTC01)
        ;// dMinQ = QSUB(dQ_1, dTC01)
 
        ;// dMaxQ-19
        ;// dMinQ-21
        VQADD       dMaxQ, dQ_1, dTC01
        VHADD       dDeltaP, dRp0q0, dP_2   
        VMIN        dDelta, dDelta, dTCs

        ;// dDelta = (OMX_U8)armClip(0, 255, q0 - delta);
        VNEG        dTCs, dTCs
        
        VQSUB       dMinQ, dQ_1, dTC01

        ;// delta = (p2 + ((p0+q0+1)>>1) - (p1<<1))>>1;
        ;// delta = armClip(-tC0, tC0, delta);
        ;// pQ0[-2*Step] = (OMX_U8)(p1 + delta);

        ;// dDeltaP = (dP_2 + dRp0q0)>>1;
        ;// dP_1n = armClip(dP_1 - dTC01, dP_1 + dTC01, dDeltaP);
        ;// dP_1n = armClip(MinP, MaxP, dDeltaP);
        
        ;// delta = (q2 + ((p0+q0+1)>>1) - (q1<<1))>>1;
        ;// delta = armClip(-tC0, tC0, delta);
        ;// pQ0[1*Step] = (OMX_U8)(q1 + delta);

        ;// dDeltaQ = (dQ_2 + dRp0q0)>>1;
        ;// dQ_1n = armClip(dQ_1 - dTC01, dQ_1 + dTC01, dDeltaQ);
        ;// dQ_1n = armClip(MinQ, MaxQ, dDeltaQ);
        
        ;// dDeltaP-26
        VHADD       dDeltaQ, dRp0q0, dQ_2   

        ;// dDeltaQ-27
        
        ;// dP_0n - 29
        ;// dP_1n - 30
        ;// dQ_0n - 24
        ;// dQ_1n - 25
        
        ;// delta = (q2 + ((p0+q0+1)>>1) - (q1<<1))>>1;
        ;// dDeltaQ = (dQ_2 + dRp0q0)>>1;

        VMAX        dP_1n, dDeltaP, dMinP   
        VMAX        dDelta, dDelta, dTCs

        ;// pQ0[-1*Step] = (OMX_U8)armClip(0, 255, dP_0 - delta);
        ;// pQ0[0*Step] = (OMX_U8)armClip(0, 255, dQ_0 - delta);

        ;// dP_0n = (OMX_U8)armClip(0, 255, dP_0 - dDelta);
        ;// dQ_0n = (OMX_U8)armClip(0, 255, dP_0 - dDelta);
        
        ;// qP_0n - 14
        ;// qQ_0n - 12
        
        VMOVL       qP_0n, dP_0
        VMOVL       qQ_0n, dQ_0

        VADDW       qP_0n, qP_0n, dDelta
        VSUBW       qQ_0n, qQ_0n, dDelta
        
        VQMOVUN     dP_0n, qP_0n
        VQMOVUN     dQ_0n, qQ_0n
        
        VMAX        dQ_1n, dDeltaQ, dMinQ

        VMIN        dP_1n, dP_1n, dMaxP
        VMIN        dQ_1n, dQ_1n, dMaxQ
        VBIF        dP_0n, dP_0, dFilt      

        VBIF        dP_1n, dP_1, dApflg
        VBIF        dQ_0n, dQ_0, dFilt  
        VBIF        dQ_1n, dQ_1, dAqflg

        M_END

;// Register usage for - armVCM4P10_DeblockingLumabSGE4_unsafe()
;//
;// Inputs - Pixels             - p0-p3: D4-D7, q0-q3: D8-D11
;//        - Filter masks       - filt: D16, aqflg: D12, apflg: D17
;//        - Additional Params  - alpha: D0, dMask_1: D15
;//         
;// Outputs - Pixels            - P0-P2: D29-D31, Q0-Q2: D24,D25,D28

;// Registers Corrupted         - D18-D31

        M_START armVCM4P10_DeblockingLumabSGE4_unsafe
    

        ;// ap<beta && armAbs(p0-q0)<((alpha>>2)+2)        
        ;// aq<beta && armAbs(p0-q0)<((alpha>>2)+2)        

        ;// ( dApflg & dAp0q0 < (dAlpha >> 2 + 2) )
        ;// ( dAqflg & dAp0q0 < (dAlpha >> 2 + 2) )

        ;// ( dApflg = dApflg & dAp0q0 < (dTemp + dMask_1 + dMask_1) )
        ;// ( dAqflg = dAqflg & dAp0q0 < (dTemp + dMask_1 + dMask_1) )

        ;// P Filter

        VSHR        dTemp, dAlpha, #2
        VADD        dTemp, dTemp, dMask_1

        ;// qSp0q0-10
        VADDL       qSp0q0, dQ_0, dP_0      
        VADD        dTemp, dTemp, dMask_1

        ;// qSp2q1-11
        ;// qSp0q0p1-12
        VADDL       qSp2q1, dP_2, dQ_1      
        VADDW       qSp0q0p1, qSp0q0, dP_1  

        VCGT        dTemp, dTemp, dAp0q0
        VSHR        qSp2q1, #1              

        ;// pQ0[-1*Step] = (OMX_U8)((p2 + 2*p1 + 2*p0 + 2*q0 + q1 + 4)>>3);
        ;// pQ0[-1*Step] = ( ( (p0 + q0 + p1) + (p2 + q1)>>1 ) >> 1 + 1 ) >> 1

        ;// dP_0n = ( ( (qSp0q0 + dP_1) + qSp2q1>>1 ) >> 1 + 1 ) >> 1
        ;// dP_0n = ( ( qSp0q0p1 + qSp2q1>>1 ) >> 1 + 1 ) >> 1
        ;// dP_0n = ( qTemp1 + 1 ) >> 1
        
        ;// pQ0[-2*Step] = (OMX_U8)((p2 + p1 + p0 + q0 + 2)>>2);
        
        ;// dP_1n = (OMX_U8)((dP_2 + qSp0q0p1 + 2)>>2);
        ;// dP_1n = (OMX_U8)((qTemp2 + 2)>>2);
        
        ;// pQ0[-3*Step] = (OMX_U8)((2*p3 + 3*p2 + p1 + p0 + q0 + 4)>>3);
        ;// pQ0[-3*Step] = (OMX_U8)(( (p3 + p2) + (p1 + p0 + q0 + p2) >> 1 + 2)>>2);

        ;// dP_2n = (OMX_U8)(( qSp3p2 + (dP_2 + qSp0q0p1) >> 1 + 2) >> 2);
        ;// dP_2n = (OMX_U8)(( qSp3p2 + qTemp2 >> 1 + 2) >> 2);

        ;// qTemp1-qSp2q1-11
        ;// qTemp2-qSp0q0p1-12
        VHADD       qTemp1, qSp0q0p1, qSp2q1
        VADDW       qTemp2, qSp0q0p1, dP_2  
        
        ;// qSp3p2-13
        VADDL       qSp3p2, dP_3, dP_2      

        VAND        dApflg, dApflg, dTemp
        VHADD       dHSp0q1, dP_0, dQ_1     
        VSRA        qSp3p2, qTemp2, #1      
        ;// dHSp0q1-28
        VAND        dAqflg, dAqflg, dTemp

        ;// dP_0n-29
        ;// dP_0t-dHSp0q1-28
        VQRSHRN     dP_0n, qTemp1, #1
        VRHADD      dP_0t, dHSp0q1, dP_1    

        ;// dP_1n-30
        VQRSHRN     dP_1n, qTemp2, #2
        
        VADDL       qSq2p1, dQ_2, dP_1          
        VADDW       qSp0q0q1, qSp0q0, dQ_1      
        
        VBIF        dP_0n, dP_0t, dApflg    

        ;// Q Filter

        ;// pQ0[0*Step] = (OMX_U8)((q2 + 2*q1 + 2*q0 + 2*p0 + p1 + 4)>>3);
        ;// pQ0[0*Step] = ( ( (p0 + q0 + q1) + (q2 + p1)>>1 ) >> 1 + 1 ) >> 1

        ;// dQ_0n = ( ( (qSp0q0 + dQ_1) + qSq2p1>>1 ) >> 1 + 1 ) >> 1
        ;// dQ_0n = ( ( qSp0q0q1 + qSq2p1>>1 ) >> 1 + 1 ) >> 1
        ;// dQ_0n = ( qTemp1 + 1 ) >> 1
        
        ;// pQ0[1*Step] = (OMX_U8)((q2 + q1 + q0 + q0 + 2)>>2);
        
        ;// dQ_1n = (OMX_U8)((dQ_2 + qSp0q0q1 + 2)>>2);
        ;// dQ_1n = (OMX_U8)((qTemp2 + 2)>>2);
        
        ;// pQ0[2*Step] = (OMX_U8)((2*q3 + 3*q2 + q1 + q0 + p0 + 4)>>3);
        ;// pQ0[2*Step] = (OMX_U8)(( (q3 + q2) + (q1 + p0 + q0 + q2) >> 1 + 2)>>2);

        ;// dQ_2n = (OMX_U8)(( qSq3q2 + (dQ_2 + qSp0q0q1) >> 1 + 2) >> 2);
        ;// dQ_2n = (OMX_U8)(( qSq3q2 + qTemp2 >> 1 + 2) >> 2);

        ;// qTemp1-qSp2q1-11
        ;// qTemp2-qSp0q0p1-12
        ;// qSq2p1-11
        ;// qSp0q0q1-12


        ;// qTemp2-qSp0q0p1-12
        ;// qTemp1-qSq2p1-11
        ;// qSq3q2-13
        ;// dP_2n-31
        
        VQRSHRN     dP_2n, qSp3p2, #2
        VADDL       qSq3q2, dQ_3, dQ_2          

        VSHR        qSq2p1, #1                  

        VHADD       qTemp1, qSp0q0q1, qSq2p1
        VADDW       qTemp2, qSp0q0q1, dQ_2      

        ;// dHSq0p1-28
        VHADD       dHSq0p1, dQ_0, dP_1         

        VBIF        dP_0n, dP_0, dFilt
        VBIF        dP_1n, dP_1, dApflg

        VSRA        qSq3q2, qTemp2, #1          

        ;// dQ_1-Temp2-25
        ;// dQ_0-Temp2-24
        VQRSHRN     dQ_1n, qTemp2, #2
        VQRSHRN     dQ_0n, qTemp1, #1

        ;// dQ_0t-Temp1-22
        VRHADD      dQ_0t, dHSq0p1, dQ_1
        VBIF        dQ_1n, dQ_1, dAqflg         

        VBIF        dP_2n, dP_2, dApflg        
        VBIF        dQ_0n, dQ_0t, dAqflg        
        VQRSHRN     dQ_2n, qSq3q2, #2
        VBIF        dQ_0n, dQ_0, dFilt
        VBIF        dQ_2n, dQ_2, dAqflg       

        M_END
        
    ENDIF  


        END
