;//
;// 
;// File Name:  armVCM4P10_DeblockingLuma_unsafe_s.s
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



    IF  ARM1136JS

MASK_1  EQU 0x01010101

;// Declare input registers

pQ0        RN 0
StepArg    RN 1
tC0Arg     RN 2
alpha      RN 6

beta       RN 14
bS         RN 14
tC0        RN 14
ptC0       RN 1

;// Declare Local/Temporary variables

;// Pixels
p_0     RN 3 
p_1     RN 5  
p_2     RN 4  
p_3     RN 2  
q_0     RN 8  
q_1     RN 9  
q_2     RN 10 
q_3     RN 12 


;// Filtering

ap0q0   RN 1  
filt    RN 2
        
m00     RN 7
m01     RN 11

apflg   RN 0 
aqflg   RN 6

tC      RN 1


;//Declarations for bSLT4 kernel

pos     RN 7
neg     RN 12

P0a     RN 1   
P1a     RN 8   
Q0a     RN 7  
Q1a     RN 4   

u1      RN 3   
max     RN 12
min     RN 2   
               
                
                
;//Declarations for bSGE4 kernel

q_3b    RN 9   
p_3b    RN 0
apqflg  RN 12

P0b     RN 6
P1b     RN 7 
P2b     RN 1

Q0b     RN 9 
Q1b     RN 0 
Q2b     RN 2

;// Miscellanous

a       RN 0
t0      RN 3 
t1      RN 12
t2      RN 7
t3      RN 11
t4      RN 4   
t5      RN 1   
t8      RN 6   
t9      RN 14  
t10     RN 5   
t11     RN 9   

;// Register usage for - armVCM4P10_DeblockingLumabSLT4_unsafe()
;//
;// Inputs - 3,4,5,8,9,10 - Input Pixels (p0-p2,q0-q2)
;//        - 2 - filt, 0 - apflg, 6 - aqflg
;//        - 11 - m01, 7 - tC0
;//         
;// Outputs - 1,8,7,11 - Output Pixels(P0a,P1a,Q0a,Q1a)
;//
;// Registers Corrupted - 0-3,5-12,14


        M_START armVCM4P10_DeblockingLumabSLT4_unsafe, lr

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
        MUL     tC0, t2, m01
        
        USUB8   t2, q_1, q_0
        SSUB8   t1, t1, t2

        USUB8   t2, p_0, q_0
        AND     t2, t2, m01
        SHSUB8  t1, t1, t2
        UHSUB8  t5, p_0, q_0
        SSUB8   t1, t1, t2
        SHSUB8  t1, t1, t5
        MOV     m00, #0
        SADD8   t1, t1, m01
        SHSUB8  t1, t1, t5
        
        ;// tC = tC0
        ;// if (ap < beta) tC++;
        ;// if (aq < beta) tC++;
        USUB8   t5, filt, m01   
        SEL     tC0, tC0, m00
        UQADD8  tC, tC0, apflg
        SSUB8   t1, t1, m00
        UQADD8  tC, tC, aqflg

        ;// Split into positive and negative part and clip 
        SEL     pos, t1, m00
        USUB8   neg, pos, t1
        USUB8   t3, pos, tC
        SEL     pos, tC, pos
        USUB8   t3, neg, tC
        SEL     neg, tC, neg
        
        ;//Reload m01
        LDR     m01,=MASK_1

        UQADD8  P0a, p_0, pos
        UQSUB8  Q0a, q_0, pos
        UQSUB8  P0a, P0a, neg
        UQADD8  Q0a, Q0a, neg
        
        ;// Choose to store the filtered
        ;// value or the original pixel
        USUB8   t1, filt, m01    
        SEL     P0a, P0a, p_0
        SEL     Q0a, Q0a, q_0
    
        ;// delta = (p2 + ((p0+q0+1)>>1) - (p1<<1))>>1;
        ;// u1 = (p0 + q0 + 1)>>1
        ;// u1 = ( (q_0 - p_0')>>1 ) ^ 0x80
        MVN     p_0, p_0
        UHSUB8  u1, q_0, p_0 
        UQADD8  max, p_1, tC0
        EOR     u1, u1, m01 ,LSL #7
    
        ;// Calculate A = (p2+u1)>>1 
        ;// Then delta = Clip3( -tC0, tC0, A - p1)

        ;// Clip P1
        UHADD8  P1a, p_2, u1
        UQSUB8  min, p_1, tC0
        USUB8   t4, P1a, max
        SEL     P1a, max, P1a
        USUB8   t4, P1a, min
        SEL     P1a, P1a, min

        ;// Clip Q1
        UHADD8  Q1a, q_2, u1
        UQADD8  max, q_1, tC0
        UQSUB8  min, q_1, tC0
        USUB8   t0, Q1a, max
        SEL     Q1a, max, Q1a
        USUB8   t0, Q1a, min
        SEL     Q1a, Q1a, min
        
        ;// Choose to store the filtered
        ;// value or the original pixel
        USUB8   t0, apflg, m01
        SEL     P1a, P1a, p_1
        USUB8   t0, aqflg, m01
        SEL     t3, Q1a, q_1
        
        M_END

;// Register usage for - armVCM4P10_DeblockingLumabSGE4_unsafe()
;//
;// Inputs - 3,4,5,8,9,10 - Input Pixels (p0-p2,q0-q2)
;//        - 2 - filt, 0 - apflg,aqflg
;//        - 1 - ap0q0, 6 - alpha
;//        - 7 - m00, 11 - m01
;//         
;// Outputs - 6,7,1,9,0,2 - Output Pixels(P0b,P1b,P2b, Q0b,Q1b,Q2b)
;// 
;// Registers Corrupted - 0-3,5-12,14

        M_START armVCM4P10_DeblockingLumabSGE4_unsafe, lr
    
        ;// apflg = apflg && |p0-q0|<((alpha>>2)+2) 
        ;// apflg = aqflg && |p0-q0|<((alpha>>2)+2) 

        M_ARG   pDummy,4
        M_ARG   pQ_3,4
        M_ARG   pP_3,4
        
        UHADD8  alpha, alpha, m00
        USUB8   t9, p_2, p_0    ;//t9 = dp2p0
        UHADD8  alpha, alpha, m00
        ADD     alpha, alpha, m01, LSL #1        
        USUB8   ap0q0, ap0q0, alpha
        SEL     apqflg, m00, apflg

        ;// P0 = (p2 + 2*p1 + 2*p0 + 2*q0 + q1 + 4)>>3 
        ;//    = ((p2-p0) + 2*(p1-p0) + (q1-q0) + 3*(q0-p0) + 8*p0 + 4)>>3
        ;//    = p0 + (((p2-p0) + 2*(p1-p0) + (q1-q0) - 3*(p0-q0) + 4)>>3)

        ;// P1 = (p2 + p1 + q0 + p0 + 2)>>2
        ;//    = p0 + (((p2-p0) + (p1-p0) - (p0-q0) + 2)>>2)
        
        ;// P2 = (2*p3 + 3*p2 + p1 + p0 + q0 + 4)>>3
        ;//    = (2*(p3-p0) + 3*(p2-p0) + (p1-p0) - (p0-q0) + 8*p0 + 4)>>3
        ;//    = p0 + (((p3-p0) + (p2-p0) + t2 + 2)>>2)

        ;// Compute P0b
        USUB8   t2, p_0, q_0         
        SSUB8   t5, t9, t2           

        USUB8   t8, q_1, q_0         
        SHADD8  t8, t5, t8

        USUB8   t9, p_1, p_0         
        SADD8   t8, t8, t9
        SHSUB8  t8, t8, t2
        SHADD8  t5, t5, t9
        SHADD8  t8, t8, m01
        SHADD8  t9, t5, m01
        SADD8   P0b, p_0, t8         
        ;// P0b ready
        
        ;// Compute P1b
        M_LDR   p_3b, pP_3
        SADD8   P1b, p_0, t9         
        ;// P1b ready
        
        ;// Compute P2b
        USUB8   t9, p_2, p_0         
        SADD8   t5, t5, t9
        UHSUB8  t9, p_3b, p_0        
        EOR     a, p_3b, p_0         
        AND     a, a, m01
        SHADD8  t5, t5, a
        UHADD8  a, p_0, q_1
        SADD8   t5, t5, m01
        SHADD8  t5, t5, t9
        MVN     t9, p_1
        SADD8   P2b, p_0, t5         
        ;// P2b ready
        
        UHSUB8  a, a, t9
        ORR     t9, apqflg, m01
        USUB8   t9, apqflg, t9

        EOR     a, a, m01, LSL #7
        SEL     P0b, P0b, a
        SEL     P1b, P1b, p_1
        SEL     P2b, P2b, p_2

        USUB8   t4, filt, m01
        SEL     P0b, P0b, p_0

        
        ;// Q0 = (q2 + 2*q1 + 2*q0 + 2*p0 + p1 + 4)>>3 
        ;//    = ((q2-q0) + 2*(q1-q0) + (p1-p0) + 3*(p0-q0) + 8*q0 + 4)>>3
        ;//    = q0 + (((q2-q0) + 2*(q1-q0) + (p1-p0) + 3*(p0-q0) + 4)>>3)

        ;// Q1 = (q2 + q1 + p0 + q0 + 2)>>2
        ;//    = q0 + (((q2-q0) + (q1-q0) + (p0-q0) + 2)>>2)

        ;// Q2 = (2*q3 + 3*q2 + q1 + q0 + p0 + 4)>>3
        ;//    = (2*(q3-q0) + 3*(q2-q0) + (q1-q0) + (p0-q0) + 8*q0 + 4)>>3
        ;//    = q0 + (((q3-q0) + (q2-q0) + t2 + 2)>>2)


        ;// Compute Q0b Q1b
        USUB8   t4, q_2, q_0           
        USUB8   a, p_0, q_0
        USUB8   t9, p_1, p_0
        SADD8   t0, t4, a
        SHADD8  t9, t0, t9
        UHADD8  t10, q_0, p_1
        SADD8   t9, t9, a
        USUB8   a, q_1, q_0
        SHADD8  t9, t9, a
        SHADD8  t0, t0, a
        SHADD8  t9, t9, m01
        SHADD8  a, t0, m01
        SADD8   t9, q_0, t9            
        ;// Q0b ready - t9
        
        MOV     t4, #0
        UHADD8  apqflg, apqflg, t4
        
        SADD8   Q1b, q_0, a 
        ;// Q1b ready
       
        USUB8   t4, apqflg, m01
        SEL     Q1b, Q1b, q_1
        MVN     t11, q_1
        UHSUB8  t10, t10, t11
        M_LDR   q_3b, pQ_3
        EOR     t10, t10, m01, LSL #7
        SEL     t9, t9, t10            
        
        ;// Compute Q2b
        USUB8   t4, q_2, q_0
        SADD8   t4, t0, t4
        EOR     t0, q_3b, q_0 
        AND     t0, t0, m01
        SHADD8  t4, t4, t0
        UHSUB8  t10, q_3b, q_0
        SADD8   t4, t4, m01
        SHADD8  t4, t4, t10

        USUB8   t10, filt, m01
        SEL     Q0b, t9, q_0

        SADD8   t4, q_0, t4            
        ;// Q2b ready - t4

        USUB8   t10, apqflg, m01
        SEL     Q2b, t4, q_2

        M_END
    
    ENDIF

        END