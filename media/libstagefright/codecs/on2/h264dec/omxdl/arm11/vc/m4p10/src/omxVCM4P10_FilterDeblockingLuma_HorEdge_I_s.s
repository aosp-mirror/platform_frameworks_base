;//
;// 
;// File Name:  omxVCM4P10_FilterDeblockingLuma_HorEdge_I_s.s
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
        M_START omxVCM4P10_FilterDeblockingLuma_HorEdge_I, r11
        
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
        SUB     pQ0, pQ0, srcdstStep, LSL #2
        M_STR   pThresholds, ppThresholds
LoopY
LoopX
;//---------------Load Pixels-------------------
        M_STR   pQ0, ppQ0Step
        M_LDR   p_3, [pQ0], srcdstStep
        M_LDR   p_2, [pQ0], srcdstStep
        M_STR   p_3, pP_3
        LDRB    bS, [pBS], #1
        M_STR   pBS, ppBS
        M_LDR   p_1, [pQ0], srcdstStep
        CMP     bS, #0
        M_LDR   p_0, [pQ0], srcdstStep
        M_LDR   q_0, [pQ0], srcdstStep
        M_LDR   q_1, [pQ0], srcdstStep
        M_LDR   q_2, [pQ0], srcdstStep
        M_LDR   q_3, [pQ0], srcdstStep
        BEQ     NoFilterBS0
        CMP     bS, #4
        M_STR   q_3, pQ_3

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
        M_LDR   pThresholds, ppThresholds
        MOV     p_2, Q1b
        MOV     p_1, P2b
        M_LDRD  pQ0b, Stepb, ppQ0Step
        ADD     pThresholds, #1
        M_STR   pThresholds, ppThresholds
        M_STR   p_1, [pQ0b, Stepb]!
        M_STR   P1b, [pQ0b, Stepb]!
        M_STR   P0b, [pQ0b, Stepb]!
        M_STR   Q0b, [pQ0b, Stepb]!
        STR     p_2, [pQ0b, Stepb]
        STR     Q2b, [pQ0b, Stepb, LSL #1]


        M_LDRD  XY, pBS, pXYBS
        SUB     pQ0, pQ0b, Stepb, LSL #2
        ADD     pQ0, pQ0, #4
        M_LDRD  alpha, beta, pAlphaBeta0
        ADDS    XY, XY, XY
        M_STR   XY, pXYBS
        BCC     LoopX
        B       ExitLoopY

;//---------- Exit of LoopX --------------
;//---- for the case of no filtering -----

NoFilterBS0
        SUB     pQ0, pQ0, srcdstStep, LSL #3
NoFilterFilt0
        ADD     pQ0, pQ0, #4
        ;// Load counter for LoopX
        M_LDRD  XY, pBS, pXYBS
        M_LDR   pThresholds, ppThresholds
        M_LDRD  alpha, beta, pAlphaBeta0

        ;// Align the pointers
        ADDS    XY, XY, XY
        ADD     pThresholds, pThresholds, #1
        M_STR   pThresholds, ppThresholds
        M_STR   XY, pXYBS
        BCC     LoopX
        B       ExitLoopY

bSLT4        
        ;//---------bSLT4 Execution---------------
        SEL     aqflg, t7, filt            ;// aqflg = filt && (aq<beta) 
        M_LDR   ptC0, ppThresholds
        CMP     filt, #0
        M_LDRD  pQ0, srcdstStep, ppQ0Step, EQ
        BEQ     NoFilterFilt0
        
        LDRB    tC0, [ptC0], #1
        M_STR   ptC0, ppThresholds

        BL      armVCM4P10_DeblockingLumabSLT4_unsafe

        ;//---------Store result---------------
        MOV     p_2, P0a
        M_LDRD  pQ0a, Stepa, ppQ0Step
        M_STR   P1a, [pQ0a, Stepa, LSL #1]!
        M_STR   p_2, [pQ0a, Stepa]!
        M_STR   Q0a, [pQ0a, Stepa]!
        STR     Q1a, [pQ0a, Stepa]
       
        ;// Load counter
        M_LDRD  XY, pBS, pXYBS
        M_LDRD  alpha, beta, pAlphaBeta0

        SUB     pQ0, pQ0a, Stepa, LSL #2
        ADD     pQ0, pQ0, #4

        ADDS    XY, XY, XY
        M_STR   XY, pXYBS
        BCC     LoopX
        
;//-------- Common Exit of LoopY -----------------
        ;// Align the pointers 
ExitLoopY
        M_LDRD  alpha, beta, pAlphaBeta1
        SUB     pQ0, pQ0, #16
        ADD     pQ0, pQ0, srcdstStep, LSL #2
        M_STRD  alpha, beta, pAlphaBeta0

        BNE     LoopY
        MOV     r0, #OMX_Sts_NoErr
;//-----------------End Filter--------------------
        M_END

    ENDIF        
        

        END
        
        