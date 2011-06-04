;//
;// 
;// File Name:  omxVCM4P10_FilterDeblockingLuma_VerEdge_I_s.s
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

        IMPORT  armVCM4P10_DeblockingLumabSLT4_unsafe
        IMPORT  armVCM4P10_DeblockingLumabSGE4_unsafe
        
    
    IF ARM1136JS

MASK_0      EQU 0x00000000   
MASK_1      EQU 0x01010101
MASK_2      EQU 0xff00ff00
LOOP_COUNT  EQU 0x11110000

;// Declare input registers

pSrcDst     RN 0
srcdstStep  RN 1
pAlphaArg   RN 2
pBetaArg    RN 3

pThresholds RN 14
pBS         RN 9
pQ0         RN 0
bS          RN 2

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
p_2         RN 4  
p_3         RN 2  
q_0         RN 8  
q_1         RN 9  
q_2         RN 10 
q_3         RN 12 

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
row8        RN 14
row9        RN 7
            
tunpk0      RN 8
tunpk1      RN 9
tunpk2      RN 10
tunpk3      RN 12
tunpk4      RN 0

tunpk5      RN 1
tunpk6      RN 14
tunpk7      RN 2 
tunpk8      RN 5 
tunpk9      RN 6 
 

;// Filtering

dp0q0       RN 12
dp1p0       RN 12
dq1q0       RN 12
dp2p0       RN 12
dq2q0       RN 12
            
ap0q0       RN 1  
filt        RN 2
        
m00         RN 14
m01         RN 11
            
apflg       RN 0 
aqflg       RN 6
apqflg      RN 0
            

;//Declarations for bSLT4 kernel

tC0         RN 7
ptC0        RN 1

pQ0a        RN 0    
Stepa       RN 1    
maska       RN 14
            
P0a         RN 1
P1a         RN 8
Q0a         RN 7
Q1a         RN 11
            
;//Declarations for bSGE4 kernel

pQ0b        RN 0
Stepb       RN 1
maskb       RN 14
            
P0b         RN 6
P1b         RN 7
P2b         RN 1
P3b         RN 3
            
Q0b         RN 9 
Q1b         RN 0   
Q2b         RN 2 
Q3b         RN 3 

;// Miscellanous
XY          RN 8
t0          RN 3 
t1          RN 12
t2          RN 14
t7          RN 7
t4          RN 4
t5          RN 1  
t8          RN 6  
a           RN 0

            

        ;// Allocate stack memory 
        M_ALLOC4 ppThresholds,4
        M_ALLOC4 pQ_3,4
        M_ALLOC4 pP_3,4
        M_ALLOC8 pAlphaBeta0,8
        M_ALLOC8 pAlphaBeta1,8
        M_ALLOC8 pXYBS,4
        M_ALLOC4 ppBS,4
        M_ALLOC8 ppQ0Step,4
        M_ALLOC4 pStep,4
        
        ;// Function header
        M_START omxVCM4P10_FilterDeblockingLuma_VerEdge_I, r11
        
        ;//Input arguments on the stack
        M_ARG   ppThresholdsArg, 4
        M_ARG   ppBSArg, 4
        
        LDR     t4,=MASK_1

        LDRB    alpha0, [pAlphaArg]
        LDRB    beta0,  [pBetaArg]
        LDRB    alpha1, [pAlphaArg,#1]
        LDRB    beta1,  [pBetaArg,#1]

        MUL     alpha0, alpha0, t4
        MUL     beta0, beta0, t4
        MUL     alpha1, alpha1, t4
        MUL     beta1, beta1, t4

        M_STRD  alpha0, beta0, pAlphaBeta0
        M_STRD  alpha1, beta1, pAlphaBeta1

        LDR     XY,=LOOP_COUNT
        M_LDR   pBS, ppBSArg
        M_LDR   pThresholds, ppThresholdsArg
        M_STR   srcdstStep, pStep
        M_STRD  XY, pBS, pXYBS
        M_STR   pThresholds, ppThresholds
        
        SUB     pQ0, pQ0, #4
LoopY
;//---------------Load Pixels-------------------

;//----------------Pack p0-p3-----------------------
        LDR     mask, =MASK_2
        
        M_LDR   row0, [pQ0], srcdstStep          
        M_LDR   row1, [pQ0], srcdstStep          
        LDR     row2, [pQ0]
        LDR     row3, [pQ0, srcdstStep]
        SUB     pQ0, pQ0, srcdstStep, LSL #1
        
        ;// row0 = [r0p0 r0p1 r0p2 r0p3]
        ;// row1 = [r1p0 r1p1 r1p2 r1p3]
        ;// row2 = [r2p0 r2p1 r2p2 r2p3]
        ;// row3 = [r3p0 r3p1 r3p2 r3p3]

        AND     tunpk0, mask, row0
        AND     tunpk6, mask, row0, LSL#8
        UXTAB16 tunpk0, tunpk0, row1, ROR#8
        UXTAB16 tunpk6, tunpk6, row1
        AND     tunpk2, mask, row2
        AND     tunpk3, mask, row2, LSL#8
        UXTAB16 tunpk2, tunpk2, row3, ROR#8
        UXTAB16 tunpk3, tunpk3, row3

        ;// tunpk0 = [r0p0 r1p0 r0p2 r1p2]
        ;// tunpk6 = [r0p1 r1p1 r0p3 r1p3]
        ;// tunpk2 = [r2p0 r3p0 r2p2 r3p2]
        ;// tunpk3 = [r2p1 r3p1 r2p3 r3p3]

        PKHTB   p_0, tunpk0, tunpk2, ASR#16
        PKHTB   p_1, tunpk6, tunpk3, ASR#16
        PKHBT   p_2, tunpk2, tunpk0, LSL#16
        PKHBT   p_3, tunpk3, tunpk6, LSL#16


        ;// p_0 = [r0p0 r1p0 r2p0 r3p0]
        ;// p_1 = [r0p1 r1p1 r2p1 r3p1]
        ;// p_2 = [r0p2 r1p2 r2p1 r3p2]
        ;// p_3 = [r0p3 r1p3 r2p3 r3p3]

        M_STR   p_3, pP_3

;//----------------Pack q0-q3-----------------------
LoopX
        LDRB    bS, [pBS], #4
        M_STR   pQ0, ppQ0Step
        LDR     mask, =MASK_2
        CMP     bS, #0
        M_STR   pBS, ppBS

        LDR     row4, [pQ0, #4]!
        BEQ.W   NoFilterBS0
        M_LDR   row5, [pQ0, srcdstStep]!
        M_LDR   row6, [pQ0, srcdstStep]!
        M_LDR   row7, [pQ0, srcdstStep]

        ;// row4 = [r0q3 r0q2 r0q1 r0q0]
        ;// row5 = [r1q3 r1q2 r1q1 r1q0]
        ;// row6 = [r2q3 r2q2 r2q1 r2q0]
        ;// row7 = [r3q3 r3q2 r3q1 r3q0]
    
        AND     tunpk4, mask, row4
        CMP     bS, #4
        AND     tunpk5, mask, row4, LSL#8
        UXTAB16 tunpk4, tunpk4, row5, ROR#8
        UXTAB16 tunpk5, tunpk5, row5
        AND     tunpk6, mask, row6
        AND     tunpk7, mask, row6, LSL#8
        UXTAB16 tunpk6, tunpk6, row7, ROR#8
        UXTAB16 tunpk7, tunpk7, row7

        ;// tunpk4 = [r0q0 r1q0 r0q2 r1q2]
        ;// tunpk5 = [r0q1 r1q1 r0q3 r1q3]
        ;// tunpk6 = [r2q0 r3q0 r2q2 r3q2]
        ;// tunpk7 = [r2q1 r3q1 r2q3 r3q3]

        PKHTB   q_3, tunpk4, tunpk6, ASR#16
        PKHTB   q_2, tunpk5, tunpk7, ASR#16
        PKHBT   q_1, tunpk6, tunpk4, LSL#16
        M_STR   q_3, pQ_3
        PKHBT   q_0, tunpk7, tunpk5, LSL#16


        ;// q_0 = [r0q0 r1q0 r2q0 r3q0]
        ;// q_1 = [r0q1 r1q1 r2q1 r3q1]
        ;// q_2 = [r0q2 r1q2 r2q1 r3q2]
        ;// q_3 = [r0q3 r1q3 r2q3 r3q3]


;//--------------Filtering Decision -------------------
        LDR     m01, =MASK_1                ;//  01010101 mask 
        MOV     m00, #MASK_0                ;//  00000000 mask 

        ;// Check |p0-q0|<Alpha 
        USUB8   dp0q0, p_0, q_0 
        USUB8   a, q_0, p_0
        SEL     ap0q0, a, dp0q0
        USUB8   a, ap0q0, alpha
        SEL     filt, m00, m01
        
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

        ;// Check ap<Beta 
        USUB8   dp2p0, p_2, p_0
        USUB8   a, p_0, p_2
        SEL     a, a, dp2p0
        USUB8   a, a, beta
        SEL     apflg, m00, filt            ;// apflg = filt && (ap<beta)

        ;// Check aq<Beta 
        USUB8   dq2q0, q_2, q_0
        USUB8   t2, q_0, q_2
        SEL     t2, t2, dq2q0
        USUB8   t2, t2, beta
        MOV     t7,#0
        

        BLT     bSLT4        
;//-------------------Filter--------------------
bSGE4        
        ;//---------bSGE4 Execution---------------
        SEL     t1, t7, filt            ;// aqflg = filt && (aq<beta) 
        CMP     filt, #0
        ORR     apqflg, apflg, t1, LSL #1
        M_LDRD  pQ0, srcdstStep, ppQ0Step, EQ
        BEQ     NoFilterFilt0

        BL      armVCM4P10_DeblockingLumabSGE4_unsafe
        
        ;//---------Store result---------------

        LDR     maskb,=MASK_2

        ;// P0b = [r0p0 r1p0 r2p0 r3p0]
        ;// P1b = [r0p1 r1p1 r2p1 r3p1]
        ;// P2b = [r0p2 r1p2 r2p2 r3p2]
        ;// P3b = [r0p3 r1p3 r2p3 r3p3]

        M_LDR   P3b, pP_3   
        M_STR   Q0b, pP_3   

        ;//------Pack p0-p3------
        AND     tunpk0, maskb, P0b
        AND     tunpk2, maskb, P0b, LSL#8
        UXTAB16 tunpk0, tunpk0, P1b, ROR#8
        UXTAB16 tunpk2, tunpk2, P1b

        AND     tunpk3, maskb, P2b
        AND     tunpk8, maskb, P2b, LSL#8
        UXTAB16 tunpk3, tunpk3, P3b, ROR#8
        UXTAB16 tunpk8, tunpk8, P3b

        ;// tunpk0 = [r0p0 r0p1 r2p0 r2p1]
        ;// tunpk2 = [r1p0 r1p1 r3p0 r3p1]
        ;// tunpk3 = [r0p2 r0p3 r2p2 r2p3]
        ;// tunpk8 = [r1p2 r1p3 r3p2 r3p3]

        MOV     p_2, Q1b
        M_LDRD  pQ0b, Stepb, ppQ0Step

        PKHTB   row9, tunpk0, tunpk3, ASR#16
        PKHBT   row7, tunpk3, tunpk0, LSL#16
        PKHTB   row3, tunpk2, tunpk8, ASR#16
        PKHBT   row6, tunpk8, tunpk2, LSL#16

        ;// row9 = [r0p0 r0p1 r0p2 r0p3]
        ;// row3 = [r1p0 r1p1 r1p2 r1p3]
        ;// row7 = [r2p0 r2p1 r2p2 r2p3]
        ;// row6 = [r3p0 r3p1 r3p2 r3p3]

        M_STR   row9, [pQ0b], Stepb
        STR     row7, [pQ0b, Stepb]
        STR     row6, [pQ0b, Stepb, LSL #1]
        STR     row3, [pQ0b], #4
        
        M_LDR   Q3b, pQ_3

        ;// Q0b = [r0q0 r1q0 r2q0 r3q0]
        ;// Q1b = [r0q1 r1q1 r2q1 r3q1]
        ;// Q2b = [r0q2 r1q2 r2q2 r3q2]
        ;// Q3b = [r0q3 r1q3 r2q3 r3q3]

        ;//------Pack q0-q3------
        AND     tunpk0, maskb, p_2
        AND     tunpk2, maskb, p_2, LSL#8
        UXTAB16 tunpk0, tunpk0, Q0b, ROR#8
        UXTAB16 tunpk2, tunpk2, Q0b

        AND     tunpk3, maskb, Q3b
        AND     tunpk8, maskb, Q3b, LSL#8
        UXTAB16 tunpk3, tunpk3, Q2b, ROR#8
        UXTAB16 tunpk8, tunpk8, Q2b

        ;// tunpk0 = [r0q1 r0q0 r2q1 r2q0]
        ;// tunpk2 = [r1q1 r1q0 r3q1 r3q0]
        ;// tunpk3 = [r0q3 r0q2 r2q3 r2q2]
        ;// tunpk8 = [r1q3 r1q2 r3q3 r3q2]

        PKHTB   row8, tunpk3, tunpk0, ASR#16
        PKHBT   row7, tunpk0, tunpk3, LSL#16
        PKHTB   row4, tunpk8, tunpk2, ASR#16
        PKHBT   row6, tunpk2, tunpk8, LSL#16

        ;// row8 = [r0q0 r0q1 r0q2 r0q3]
        ;// row4 = [r1q0 r1q1 r1q2 r1q3]
        ;// row7 = [r2q0 r2q1 r2q2 r2q3]
        ;// row6 = [r3q0 r3q1 r3q2 r3q3]

        STR     row4, [pQ0b]
        STR     row7, [pQ0b, Stepb]
        STR     row6, [pQ0b, Stepb, LSL #1]

        SUB     pQ0, pQ0b, Stepb
        MOV     p_1, Q2b

        STR     row8, [pQ0]

        M_LDRD  XY, pBS, pXYBS
        M_LDR   pThresholds, ppThresholds
        M_LDRD  alpha, beta, pAlphaBeta1

        ADDS    XY, XY, XY
        ADD     pThresholds, #4
        M_STR   pThresholds, ppThresholds
        M_STR   XY, pXYBS
        BCC     LoopX
        B       ExitLoopY

;//---------- Exit of LoopX --------------
;//---- for the case of no filtering -----

NoFilterFilt0
        ADD     pQ0, pQ0, #4
NoFilterBS0
        ;// Load counter for LoopX
        M_LDRD  XY, pBS, pXYBS
        M_LDR   pThresholds, ppThresholds
        M_LDRD  alpha, beta, pAlphaBeta1

        ;// Align the pointer
        ADDS    XY, XY, XY
        ADD     pThresholds, pThresholds, #4
        M_STR   pThresholds, ppThresholds
        M_STR   XY, pXYBS
        BCC     LoopY
        B       ExitLoopY
        
bSLT4        
        ;//---------bSLT4 Execution---------------
        SEL     aqflg, t7, filt            ;// aqflg = filt && (aq<beta) 
        M_LDR   ptC0, ppThresholds
        CMP     filt, #0
        M_LDRD  pQ0, srcdstStep, ppQ0Step, EQ
        BEQ     NoFilterFilt0
        
        LDRB    tC0, [ptC0], #4
        M_STR   ptC0, ppThresholds

        BL      armVCM4P10_DeblockingLumabSLT4_unsafe

        ;//---------Store result---------------
        ;//--------Pack p1,p0,q1,q0------------
        
        ;//Load destination pointer
        LDR     maska,=MASK_2
        M_STR   Q0a, pP_3
        MOV     p_1, q_2

        ;// P1a = [r0p1 r1p1 r2p1 r3p1]
        ;// P0a = [r0p0 r1p0 r2p0 r3p0]
        ;// Q0a = [r0q0 r1q0 r2q0 r3q0]
        ;// Q1a = [r0q1 r1q1 r2q1 r3q1]

        AND     tunpk1, maska, P0a
        AND     tunpk2, maska, P0a, LSL#8
        UXTAB16 tunpk1, tunpk1, P1a, ROR#8
        UXTAB16 tunpk2, tunpk2, P1a

        M_LDRD  pQ0a, Stepa, ppQ0Step

        AND     tunpk9, maska, Q1a
        AND     tunpk3, maska, Q1a, LSL#8
        UXTAB16 tunpk9, tunpk9, Q0a, ROR#8
        UXTAB16 tunpk3, tunpk3, Q0a

        ;// tunpk1 = [r0p0 r0p1 r2p0 r2p1]
        ;// tunpk2 = [r1p0 r1p1 r3p0 r3p1]
        ;// tunpk9 = [r0q1 r0q0 r2q1 r2q0]
        ;// tunpk3 = [r1q1 r1q0 r3q1 r3q0]

        MOV     t4, tunpk1, LSR #16
        MOV     t0, tunpk9, LSR #16

        STRH    t4,[pQ0a, #2]!          ;//Stores [r0p0 r0p1]
        STRH    t0,[pQ0a, #2]           ;//Stores [r0q0 r0q1]

        MOV     t4, tunpk2, LSR #16
        MOV     t0, tunpk3, LSR #16

        M_STRH  t4,[pQ0a, Stepa]!       ;//Stores [r1p0 r1p1]
        STRH    t0,[pQ0a, #2]           ;//Stores [r1q0 r1q1]
        
        M_STRH  tunpk1,[pQ0a, Stepa]!   ;//Stores [r2p0 r2p1]
        STRH    tunpk2,[pQ0a, Stepa]    ;//Stores [r3p0 r3p1]
        STRH    tunpk9,[pQ0a, #2]!        ;//Stores [r2q0 r2q1]
        STRH    tunpk3,[pQ0a, Stepa]    ;//Stores [r3q0 r3q1]

        SUB     pQ0, pQ0a, Stepa, LSL #1

        ;// Load counter
        M_LDRD  XY, pBS, pXYBS

        ;// Reload Pixels
        M_LDR   p_0, pQ_3
        MOV     p_2, Q1a
                
        M_LDRD  alpha, beta, pAlphaBeta1

        ADDS    XY, XY, XY
        M_STR   XY, pXYBS
        BCC     LoopX
        
;//-------- Common Exit of LoopY -----------------
        ;// Align the pointers 
        M_LDR   pThresholds, ppThresholds
ExitLoopY
        SUB     pQ0, pQ0, #16
        ADD     pQ0, pQ0, srcdstStep, LSL #2
        SUB     pBS, pBS, #15
        SUB     pThresholds, pThresholds, #15
        M_STR   pThresholds, ppThresholds

        M_LDRD  alpha, beta, pAlphaBeta0

        BNE     LoopY
        MOV     r0, #OMX_Sts_NoErr

        M_END
;//-----------------End Filter--------------------

    ENDIF        
        
        END
        
        