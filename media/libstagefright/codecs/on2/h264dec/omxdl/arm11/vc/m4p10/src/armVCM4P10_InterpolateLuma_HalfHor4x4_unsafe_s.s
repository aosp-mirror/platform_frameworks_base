;//
;// 
;// File Name:  armVCM4P10_InterpolateLuma_HalfHor4x4_unsafe_s.s
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
        
        EXPORT armVCM4P10_InterpolateLuma_HalfHor4x4_unsafe

DEBUG_ON    SETL {FALSE}


    IF ARM1136JS

;// Function: 
;//     armVCM4P10_InterpolateLuma_HalfHor4x4_unsafe
;//
;// Implements horizontal interpolation for a block of size 4x4. Input and output should 
;// be aligned. 
;//
;// Registers used as input for this function
;// r0,r1,r2,r3 where r0,r2  input pointer and r1,r3 corresponding step size
;//
;// Registers preserved for top level function
;// r0,r1,r2,r3,r4,r5,r6,r14
;//
;// Registers modified by the function
;// r7,r8,r9,r10,r11,r12
;//
;// Output registers
;// None. Function will preserve r0-r3


;// Declare input registers
pSrc            RN 0
srcStep         RN 1
pDst            RN 2
dstStep         RN 3

;// Declare inner loop registers
Acc0            RN 4
Acc1            RN 5
Acc2            RN 6
Acc3            RN 7

ValA            RN 4
ValB            RN 5
ValC            RN 6
ValD            RN 7
ValE            RN 8
ValF            RN 9
ValG            RN 12
ValH            RN 14
ValI            RN 1

Temp1           RN 3
Temp2           RN 1
Temp3           RN 12
Temp4           RN 7
Temp5           RN 5
r0x0fe00fe0     RN 3                                    ;// [0 (16*255 - 16) 0 (16*255 - 16)]
r0x00ff00ff     RN 10                                   ;// [0 255 0 255] where 255 is offset
Counter         RN 11

Height          RN 3

        M_ALLOC4 pDstStep, 4
        M_ALLOC4 pSrcStep, 4

        ;// Function header
        M_START armVCM4P10_InterpolateLuma_HalfHor4x4_unsafe, r6
        
        MOV     Counter, #2
        M_STR   dstStep, pDstStep        
        M_STR   srcStep, pSrcStep        
        LDR     r0x00ff00ff, =0x00ff00ff               ;// [0 255 0 255] 255 is offset to avoid negative results 

NextTwoRowsLoop
        LDR     ValD, [pSrc, srcStep]                   ;// Load row 1 [d1 c1 b1 a1]
        LDR     ValA, [pSrc], #4                        ;// Load row 0 [d0 c0 b0 a0]
        LDR     ValH, [pSrc, srcStep]                   ;// Load  [h1 g1 f1 e1]        
        LDR     ValE, [pSrc], #4                        ;// Load  [h0 g0 f0 e0]
        LDRB    Temp2, [pSrc, srcStep]                  ;// Load row 1 [l1 k1 j1 i1]
        LDRB    Temp1, [pSrc], #-8                      ;// Load row 0 [l0 k0 j0 i0]
        
        PKHBT   ValB, ValA, ValD, LSL #16               ;// [b1 a1 b0 a0]
        PKHTB   ValD, ValD, ValA, ASR #16               ;// [d1 c1 d0 c0]
        UXTAB16 ValA, r0x00ff00ff, ValB                 ;// [00 a1 00 a0] + [0 255 0 255]
        UXTAB16 ValC, r0x00ff00ff, ValD                 ;// [00 c1 00 c0] + [0 255 0 255]
        PKHBT   ValI, Temp1, Temp2, LSL #16             ;// [00 i1 00 i0]            
        PKHBT   ValF, ValE, ValH, LSL #16               ;// [f1 e1 f0 e0]
        PKHTB   ValH, ValH, ValE, ASR #16               ;// [h1 g1 h0 g0]
        UXTAB16 ValE, r0x00ff00ff, ValF                 ;// [00 e1 00 e0] + [0 255 0 255]

        ;// Calculate Acc0
        ;// Acc0 = a - 5*b + 20*c + 20*d - 5*e + f
        UXTAB16 Temp1, ValC, ValD, ROR #8
        UXTAB16 Temp3, ValE, ValB, ROR #8
        RSB     Temp1, Temp3, Temp1, LSL #2                
        UXTAB16 Acc0, ValA, ValF, ROR #8
        ADD     Temp1, Temp1, Temp1, LSL #2        
        ADD     Acc0, Acc0, Temp1       

        ;// Calculate Acc1
        ;// Acc1 = b - 5*c + 20*d + 20*e - 5*f + g
        UXTAB16 Temp1, ValE, ValD, ROR #8
        UXTAB16 Temp3, ValC, ValF, ROR #8
        RSB     Temp1, Temp3, Temp1, LSL #2                        
        UXTAB16 ValG, r0x00ff00ff, ValH                 ;// [00 g1 00 g0] + [0 255 0 255]
        ADD     Temp1, Temp1, Temp1, LSL #2        
        UXTAB16 Acc1, ValG, ValB, ROR #8
        ADD     Acc1, Acc1, Temp1        

        LDR     r0x0fe00fe0, =0x0fe00fe0                ;// 0x0fe00fe0 = (16 * Offset) - 16 where Offset is 255        
        UXTAB16 Acc2, ValC, ValH, ROR #8        
        ADD     ValI, r0x00ff00ff, ValI                 ;// [00 i1 00 i0] + [0 255 0 255]        
        UQSUB16 Acc0, Acc0, r0x0fe00fe0                    
        UQSUB16 Acc1, Acc1, r0x0fe00fe0
        USAT16  Acc0, #13, Acc0
        USAT16  Acc1, #13, Acc1        
        
        ;// Calculate Acc2
        ;// Acc2 = c - 5*d + 20*e + 20*f - 5*g + h
        UXTAB16 Temp1, ValG, ValD, ROR #8
        UXTAB16 Acc3, ValI, ValD, ROR #8
        UXTAB16 Temp2, ValE, ValF, ROR #8
        AND     Acc1, r0x00ff00ff, Acc1, LSR #5
        AND     Acc0, r0x00ff00ff, Acc0, LSR #5
        ORR     Acc0, Acc0, Acc1, LSL #8        
        RSB     Temp5, Temp1, Temp2, LSL #2        
        UXTAB16 Temp2, ValG, ValF, ROR #8
        ADD     Temp5, Temp5, Temp5, LSL #2        
        ADD     Acc2, Acc2, Temp5        

        ;// Calculate Acc3
        ;// Acc3 = d - 5*e + 20*f + 20*g - 5*h + i
        UXTAB16 Temp5, ValE, ValH, ROR #8
        RSB     Temp5, Temp5, Temp2, LSL #2
        LDR     r0x0fe00fe0, =0x0fe00fe0
        ADD     Temp5, Temp5, Temp5, LSL #2        
        ADD     Acc3, Acc3, Temp5
        
        UQSUB16 Acc3, Acc3, r0x0fe00fe0        
        UQSUB16 Acc2, Acc2, r0x0fe00fe0        
        USAT16  Acc3, #13, Acc3
        USAT16  Acc2, #13, Acc2        

        M_LDR   dstStep, pDstStep
        AND     Acc3, r0x00ff00ff, Acc3, LSR #5
        AND     Acc2, r0x00ff00ff, Acc2, LSR #5
        ORR     Acc2, Acc2, Acc3, LSL #8
        
        SUBS    Counter, Counter, #1
        M_LDR   srcStep, pSrcStep
        PKHBT   Acc1, Acc0, Acc2, LSL #16   
        M_STR   Acc1, [pDst], dstStep                   ;// Store result1
        PKHTB   Acc2, Acc2, Acc0, ASR #16   
        M_STR   Acc2, [pDst], dstStep                   ;// Store result2
        ADD     pSrc, pSrc, srcStep, LSL #1
        
        BGT     NextTwoRowsLoop
End
        SUB     pDst, pDst, dstStep, LSL #2
        SUB     pSrc, pSrc, srcStep, LSL #2

        M_END
    
    ENDIF

    END
    


























































