;//
;// 
;// File Name:  omxVCM4P10_FilterDeblockingChroma_HorEdge_I_s.s
;// OpenMAX DL: v1.0.2
;// Revision:   9641
;// Date:       Thursday, February 7, 2008
;// 
;// (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
;// 
;// 
;//


        INCLUDE omxtypes_s.h
        INCLUDE armCOMM_s.h

        M_VARIANTS ARM1136JS

        
        IF ARM1136JS
        
MASK_0      EQU 0x00000000   
MASK_1      EQU 0x01010101
LOOP_COUNT  EQU 0x50000000

;// Declare input registers

pSrcDst     RN 0
srcdstStep  RN 1
pAlphaArg   RN 2
pBetaArg    RN 3

pThresholds RN 6
pBS         RN 9
pQ0         RN 0
bS          RN 10

alpha       RN 6
alpha0      RN 6
alpha1      RN 8

beta        RN 7
beta0       RN 7
beta1       RN 9

;// Declare Local/Temporary variables

;// Pixels
p_0         RN 3  
p_1         RN 5  
q_0         RN 8  
q_1         RN 9  

;// Filtering

dp0q0       RN 12
dp1p0       RN 12
dq1q0       RN 12

ap0q0       RN 4  
filt        RN 2
        
m00         RN 14
m01         RN 11
            
pQ0         RN 0
Step        RN 1
            
;// Output
            
P_0         RN 6
Q_0         RN 7 

;//Declarations for bSLT4 kernel

tC          RN 12
tC0         RN 5
tC1         RN 12
pos         RN 5
neg         RN 9

;//Declarations for bSGE4 kernel


;// Miscellanous
XY          RN 8

a           RN 10
t1          RN 10
t2          RN 12
t3          RN 14
t4          RN 6
t5          RN 5

        
        ;// Allocate stack memory 
        M_ALLOC4 ppThresholds,4
        M_ALLOC8 pAlphaBeta0,8
        M_ALLOC8 pAlphaBeta1,8
        M_ALLOC8 pXYBS,4
        M_ALLOC4 ppBS,4
        
        ;// Function header
        M_START omxVCM4P10_FilterDeblockingChroma_HorEdge_I, r11
        
        ;//Input arguments on the stack
        M_ARG   ppThresholdsArg, 4
        M_ARG   ppBSArg, 4
        
        LDRB    alpha1, [pAlphaArg,#1]
        LDRB    beta1,  [pBetaArg,#1]
        M_LDR   pThresholds, ppThresholdsArg
        LDR     a,=MASK_1
        LDRB    beta0,  [pBetaArg]
        M_STR   pThresholds, ppThresholds
        LDRB    alpha0, [pAlphaArg]

        MUL     alpha1, alpha1, a
        MUL     beta1, beta1, a
        MUL     alpha0, alpha0, a
        MUL     beta0, beta0, a

        M_STRD  alpha1, beta1, pAlphaBeta1
        M_LDR   pBS, ppBSArg
        M_STRD  alpha0, beta0, pAlphaBeta0

        LDR     XY,=LOOP_COUNT
        M_STRD  XY, pBS, pXYBS

        SUB     pQ0, pQ0, srcdstStep, LSL #1
LoopY
LoopX
;//---------------Load Pixels-------------------
        LDRH    bS, [pBS], #2
        
        M_STR   pBS, ppBS
        M_LDR   p_1, [pQ0],srcdstStep

        CMP     bS, #0
        
        M_LDR   p_0, [pQ0],srcdstStep
        M_LDR   q_0, [pQ0],srcdstStep
        M_LDR   q_1, [pQ0]
        LDR     m01, =MASK_1                ;//  01010101 mask 
        BEQ     NoFilterBS0

        
        ;// p_0 = [r3p0 r2p0 r1p0 r0p0]
        ;// p_1 = [r3p1 r2p1 r1p1 r0p1]
        ;// q_0 = [r3q0 r2q0 r1q0 r0q0]
        ;// q_1 = [r3q1 r2q1 r1q1 r0q1]

;//--------------Filtering Decision -------------------
        MOV     m00, #MASK_0                ;//  00000000 mask 

        MOV     filt, m01
        TST     bS, #0xff00
        MOVEQ   filt, filt, LSR #16
        TST     bS, #0xff
        MOVEQ   filt, filt, LSL #16
        TST     bS, #4

        
        ;// Check |p0-q0|<Alpha 
        USUB8   dp0q0, p_0, q_0 
        USUB8   a, q_0, p_0
        SEL     ap0q0, a, dp0q0
        USUB8   a, ap0q0, alpha
        SEL     filt, m00, filt
                
        ;// Check |p1-p0|<Beta 
        USUB8   dp1p0, p_1, p_0
        USUB8   a, p_0, p_1
        SEL     a, a, dp1p0
        USUB8   a, a, beta
        SEL     filt, m00, filt

        ;// Check |q1-q0|<Beta 
        USUB8   dq1q0, q_1, q_0
        USUB8   a, q_0, q_1
        SEL     a, a, dq1q0
        USUB8   a, a, beta
        SEL     filt, m00, filt

        BEQ     bSLT4        
;//-------------------Filter--------------------
bSGE4        
        ;//---------bSGE4 Execution---------------
        CMP     filt, #0

        M_LDR   pThresholds, ppThresholds

        ;// Compute P0b
        UHADD8  t1, p_0, q_1
        BEQ     NoFilterFilt0
        MVN     t2, p_1
        UHSUB8  t1, t1, t2
        USUB8   t2, filt, m01
        EOR     t1, t1, m01, LSL #7

        ADD     pThresholds,pThresholds, #2
        
        ;// Compute Q0b 
        UHADD8  t2, q_0, p_1
        MVN     t3, q_1
        UHSUB8  t2, t2, t3
        M_STR   pThresholds, ppThresholds
        SEL     P_0, t1, p_0
        EOR     t2, t2, m01, LSL #7
        SEL     Q_0, t2, q_0

        SUB     pQ0, pQ0, srcdstStep, LSL #1
        B       StoreResultAndExit

;//---------- Exit of LoopX --------------
;//---- for the case of no filtering -----

NoFilterFilt0
NoFilterBS0
        M_LDR   pThresholds, ppThresholds
        SUB     pQ0, pQ0, srcdstStep, LSL #1
        SUB     pQ0, pQ0, srcdstStep
        ADD     pQ0, pQ0, #4
        ADD     pThresholds, pThresholds, #2

        ;// Load counter for LoopX
        M_LDRD  XY, pBS, pXYBS
        M_STR   pThresholds, ppThresholds
        M_LDRD  alpha, beta, pAlphaBeta0

        ;// Align the pointer
        ADDS    XY, XY, XY
        M_STR   XY, pXYBS
        BCC     LoopY
        B       ExitLoopY
        
bSLT4         
        ;//---------bSLT4 Execution---------------
        M_LDR   pThresholds, ppThresholds
        CMP     filt, #0
        
        ;// Since beta <= 18 and alpha <= 255 we know
        ;// -254 <= p0-q0 <= 254
        ;//  -17 <= q1-q0 <= 17
        ;//  -17 <= p1-p0 <= 17

        ;// delta = Clip3( -tC, tC, ((((q0-p0)<<2) + (p1-q1) + 4)>>3))
        ;// 
        ;//    Calculate A = (((q0-p0)<<2) + (p1-q1) + 4)>>3
        ;//                = (4*q0 - 4*p0 + p1 - q1 + 4)>>3
        ;//                = ((p1-p0) - (q1-q0) - 3*(p0-q0) + 4)>>3

        USUB8   t1, p_1, p_0
        USUB8   t2, q_1, q_0
        BEQ     NoFilterFilt0
        
        LDRB    tC0, [pThresholds],#1
        SSUB8   t1, t1, t2
        LDRB    tC1, [pThresholds],#1
        M_STR   pThresholds, ppThresholds
        UHSUB8  t4, p_0, q_0
        ORR     tC, tC0, tC1, LSL #16
        USUB8   t5, p_0, q_0
        AND     t5, t5, m01
        SHSUB8  t1, t1, t5
        ORR     tC, tC, LSL #8        
        SSUB8   t1, t1, t5
        SHSUB8  t1, t1, t4
        UQADD8  tC, tC, m01
        SADD8   t1, t1, m01
        USUB8   t5, filt, m01   
        SHSUB8  t1, t1, t4
        SEL     tC, tC, m00

        ;// Split into positive and negative part and clip 

        SSUB8   t1, t1, m00
        SEL     pos, t1, m00
        USUB8   neg, pos, t1
        USUB8   t3, pos, tC
        SEL     pos, tC, pos
        USUB8   t3, neg, tC
        SEL     neg, tC, neg
        UQADD8  P_0, p_0, pos
        UQSUB8  Q_0, q_0, pos
        UQSUB8  P_0, P_0, neg
        UQADD8  Q_0, Q_0, neg
        
        SUB     pQ0, pQ0, srcdstStep, LSL #1

        ;// Choose to store the filtered
        ;// value or the original pixel
        USUB8   t1, filt, m01    
        SEL     P_0, P_0, p_0
        SEL     Q_0, Q_0, q_0
    
StoreResultAndExit

        ;//---------Store result---------------

        ;// P_0 = [r0p0 r1p0 r2p0 r3p0]
        ;// Q_0 = [r0q0 r1q0 r2q0 r3q0]

        M_STR   P_0, [pQ0], srcdstStep
        STR     Q_0, [pQ0], #4

        M_LDRD  XY, pBS, pXYBS
        M_LDRD  alpha, beta, pAlphaBeta0

        SUB     pQ0, pQ0, srcdstStep, LSL #1

        ADDS    XY, XY, XY
        M_STR   XY, pXYBS
        BCC     LoopX

;//-------- Common Exit of LoopY -----------------
        ;// Align the pointers 

ExitLoopY
        ADD     pBS, pBS, #4
        M_LDRD  alpha, beta, pAlphaBeta1
        SUB     pQ0, pQ0, #8
        ADD     pQ0, pQ0, srcdstStep, LSL #2
        M_STRD  alpha, beta, pAlphaBeta0

        BNE     LoopY
        MOV     r0, #OMX_Sts_NoErr

;//-----------------End Filter--------------------
        M_END

    ENDIF        

        END
        
        
