;//
;// 
;// File Name:  omxVCM4P10_FilterDeblockingChroma_VerEdge_I_s.s
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
MASK_2      EQU 0x0000ff00
LOOP_COUNT  EQU 0x50000000

;// Declare input registers

pSrcDst     RN 0
srcdstStep  RN 1
pAlphaArg   RN 2
pBetaArg    RN 3

pThresholds RN 6
pBS         RN 9
pQ0         RN 0
bS          RN 2
bSTemp      RN 10

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

;// Unpacking
mask        RN 11 

row0        RN 2
row1        RN 4
row2        RN 5
row3        RN 3

row4        RN 8
row5        RN 9
row6        RN 10
row7        RN 12

tunpk0      RN 2
tunpk2      RN 10
tunpk3      RN 12

tunpk4      RN 4
tunpk5      RN 5
tunpk6      RN 14
tunpk7      RN 2 

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
        M_START omxVCM4P10_FilterDeblockingChroma_VerEdge_I, r11
        
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
        
        
LoopY
LoopX
;//---------------Load Pixels-------------------

;//----------------Pack q0-q1-----------------------
        LDRH    bS, [pBS], #8
        LDR     mask, =MASK_2

        M_LDRH  row4, [pQ0], srcdstStep
        CMP     bS, #0
        M_STR   pBS, ppBS
        M_LDRH  row5, [pQ0], srcdstStep
        BEQ.W   NoFilterBS0
        LDRH    row6, [pQ0]
        LDRH    row7, [pQ0, srcdstStep]

        ;// row4 = [0 0 r0q0 r0q1]
        ;// row5 = [0 0 r1q0 r1q1]
        ;// row6 = [0 0 r2q0 r2q1]
        ;// row7 = [0 0 r3q0 r3q1]

        AND     tunpk4, mask, row4
        AND     tunpk5, mask, row4, LSL#8
        UXTAB   tunpk4, tunpk4, row5, ROR#8
        UXTAB   tunpk5, tunpk5, row5
        AND     tunpk6, mask, row6
        AND     tunpk7, mask, row6, LSL#8
        UXTAB   tunpk6, tunpk6, row7, ROR#8
        UXTAB   tunpk7, tunpk7, row7

        ;// tunpk4 = [0 0 r0q0 r1q0]
        ;// tunpk5 = [0 0 r0q1 r1q1]
        ;// tunpk6 = [0 0 r2q0 r3q0]
        ;// tunpk7 = [0 0 r2q1 r3q1]

        SUB     pQ0, pQ0, srcdstStep, LSL #1
        SUB     pQ0, pQ0, #2

        PKHBT   q_1, tunpk6, tunpk4, LSL#16
        PKHBT   q_0, tunpk7, tunpk5, LSL#16

        ;// q_0 = [r0q0 r1q0 r2q0 r3q0]
        ;// q_1 = [r0q1 r1q1 r2q1 r3q1]


;//----------------Pack p0-p1-----------------------

        M_LDRH  row0, [pQ0], srcdstStep          
        M_LDRH  row1, [pQ0], srcdstStep          
        LDRH    row2, [pQ0]
        LDRH    row3, [pQ0, srcdstStep]
        
        ;// row0 = [0 0 r0p0 r0p1]
        ;// row1 = [0 0 r1p0 r1p1]
        ;// row2 = [0 0 r2p0 r2p1]
        ;// row3 = [0 0 r3p0 r3p1]

        AND     tunpk2, mask, row0
        AND     tunpk6, mask, row0, LSL#8
        UXTAB   tunpk2, tunpk2, row1, ROR#8
        UXTAB   tunpk6, tunpk6, row1

        AND     tunpk0, mask, row2
        AND     tunpk3, mask, row2, LSL#8
        UXTAB   tunpk0, tunpk0, row3, ROR#8
        UXTAB   tunpk3, tunpk3, row3

        ;// tunpk2 = [0 0 r0p0 r1p0]
        ;// tunpk6 = [0 0 r0p1 r1p1]
        ;// tunpk0 = [0 0 r2p0 r3p0]
        ;// tunpk3 = [0 0 r2p1 r3p1]

        PKHBT   p_0, tunpk0, tunpk2, LSL#16
        M_LDR   bSTemp, ppBS
        PKHBT   p_1, tunpk3, tunpk6, LSL#16

        ;// p_0 = [r0p0 r1p0 r2p0 r3p0]
        ;// p_1 = [r0p1 r1p1 r2p1 r3p1]

;//--------------Filtering Decision -------------------
        USUB8   dp0q0, p_0, q_0 
        LDR     m01, =MASK_1
        LDRH    bSTemp, [bSTemp ,#-8]
        MOV     m00, #MASK_0                ;//  00000000 mask 
        
        MOV     filt, m01
        TST     bSTemp, #0xff00
        MOVEQ   filt, filt, LSL #16
        TST     bSTemp, #0xff
        MOVEQ   filt, filt, LSR #16
        TST     bSTemp, #4

        ;// Check |p0-q0|<Alpha 
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

        ADD     pThresholds,pThresholds, #4
        
        ;// Compute Q0b 
        UHADD8  t2, q_0, p_1
        MVN     t3, q_1
        UHSUB8  t2, t2, t3
        M_STR   pThresholds, ppThresholds
        SEL     P_0, t1, p_0
        EOR     t2, t2, m01, LSL #7
        SEL     Q_0, t2, q_0

        B       StoreResultAndExit

;//---------- Exit of LoopX --------------
;//---- for the case of no filtering -----

NoFilterFilt0
        ADD     pQ0, pQ0, #2
NoFilterBS0
        M_LDR   pThresholds, ppThresholds
        SUB     pQ0, pQ0, srcdstStep, LSL #1
        ADD     pQ0, pQ0, #4
        ADD     pThresholds, pThresholds, #4
        ;// Load counter for LoopX
        M_LDRD  XY, pBS, pXYBS
        M_STR   pThresholds, ppThresholds
        M_LDRD  alpha, beta, pAlphaBeta1

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
        
        LDRB    tC0, [pThresholds], #1
        SSUB8   t1, t1, t2
        LDRB    tC1, [pThresholds], #3
        M_STR   pThresholds, ppThresholds
        UHSUB8  t4, p_0, q_0
        ORR     tC, tC1, tC0, LSL #16
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
        
        ;// Choose to store the filtered
        ;// value or the original pixel
        USUB8   t1, filt, m01    
        SEL     P_0, P_0, p_0
        SEL     Q_0, Q_0, q_0
    
StoreResultAndExit

        ;//---------Store result---------------

        ;// P_0 = [r0p0 r1p0 r2p0 r3p0]
        ;// Q_0 = [r0q0 r1q0 r2q0 r3q0]

        SUB     pQ0, pQ0, srcdstStep, LSL #1
        ADD        pQ0, pQ0, #1      
 
        MOV     t1, Q_0, LSR #24
        STRB    t1, [pQ0, #1]
        MOV     t1, P_0, LSR #24
        M_STRB  t1, [pQ0], srcdstStep

        MOV     t1, Q_0, LSR #16
        STRB    t1, [pQ0, #1]
        MOV     t1, P_0, LSR #16
        M_STRB  t1, [pQ0], srcdstStep

        MOV     t1, P_0, LSR #8
        STRB    t1, [pQ0]
        STRB    P_0, [pQ0, srcdstStep]
        MOV     t1, Q_0, LSR #8
        STRB    t1, [pQ0, #1]!
        STRB    Q_0, [pQ0, srcdstStep]

        M_LDRD  XY, pBS, pXYBS
        M_LDRD  alpha, beta, pAlphaBeta1

        SUB     pQ0, pQ0, srcdstStep, LSL #1
        ADD     pQ0, pQ0, #4

        ADDS    XY, XY, XY
        M_STR   XY, pXYBS
        BCC     LoopX

;//-------- Common Exit of LoopY -----------------
        ;// Align the pointers 

ExitLoopY

        M_LDR   pThresholds, ppThresholds
        SUB     pQ0, pQ0, #8
        ADD     pQ0, pQ0, srcdstStep, LSL #2
        SUB     pBS, pBS, #14 
        SUB     pThresholds, pThresholds, #6
        M_STR   pThresholds, ppThresholds

        M_LDRD  alpha, beta, pAlphaBeta0

        BNE     LoopY
        MOV     r0, #OMX_Sts_NoErr
;//-----------------End Filter--------------------

        M_END

        ENDIF        

        END
        
        
