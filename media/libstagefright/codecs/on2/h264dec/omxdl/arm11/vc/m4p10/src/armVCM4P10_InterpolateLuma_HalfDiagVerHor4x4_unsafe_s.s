;//
;// 
;// File Name:  armVCM4P10_InterpolateLuma_HalfDiagVerHor4x4_unsafe_s.s
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

        EXPORT armVCM4P10_InterpolateLuma_HalfDiagVerHor4x4_unsafe

        M_VARIANTS ARM1136JS

    
    
    

    IF ARM1136JS 
        
        M_ALLOC8 ppDstArgs, 8
        M_ALLOC4 ppSrc, 4
        M_ALLOC4 ppDst, 4
        M_ALLOC4 pCounter, 4

        ;// Function header
        ;// Function:
        ;//     armVCM4P10_InterpolateLuma_HalfDiagVerHor4x4_unsafe
        ;//
        ;// Implements diagonal interpolation for a block of size 4x4. Input and output should 
        ;// be aligned. 
        ;//
        ;// Registers used as input for this function
        ;// r0,r1,r2,r3, r8 where r0,r2  input pointer and r1,r3 step size, r8 intermediate-buf pointer
        ;//
        ;// Registers preserved for top level function
        ;// r0,r1,r2,r3,r4,r5,r6,r14
        ;//
        ;// Registers modified by the function
        ;// r7,r8,r9,r10,r11,r12
        ;//
        ;// Output registers
        ;// None. Function will preserve r0-r3

        M_START armVCM4P10_InterpolateLuma_HalfDiagVerHor4x4_unsafe, r6

;// Declare input registers
pSrc            RN 0
srcStep         RN 1
pDst            RN 2
dstStep         RN 3

;// Declare inner loop registers
ValA            RN 5
ValA0           RN 4
ValA1           RN 5
ValAF0          RN 4
ValAF1          RN 5

ValB            RN 11

ValC            RN 5
ValC0           RN 4
ValC1           RN 5
ValCD0          RN 12
ValCD1          RN 14
ValCF0          RN 4
ValCF1          RN 5

ValD            RN 10

ValE            RN 7
ValE0           RN 6
ValE1           RN 7
ValEB0          RN 10
ValEB1          RN 11
ValED0          RN 6
ValED1          RN 7

ValF            RN 10

ValG            RN 14
ValG0           RN 12
ValG1           RN 14
ValGB0          RN 12
ValGB1          RN 14

Acc0            RN 4
Acc1            RN 5
Acc2            RN 6
Acc3            RN 7

Temp            RN 7
Step            RN 6

pInterBuf       RN 8
Counter         RN 8
r0x00ff00ff     RN 9                                        ;// [0 255 0 255] where 255 is offset
r0x0001fc00     RN 10                                       ;// [0 (16*255 - 16) 0 (16*255 - 16)]

    
;// Declare inner loop registers
ValCA           RN 8
ValDB           RN 9
ValGE           RN 10
ValHF           RN 11
r0x00140001     RN 12
r0x0014fffb     RN 14

r0x00000200     RN 12
r0x000000ff     RN 12
        
        M_STRD      pDst, dstStep, ppDstArgs
        MOV         pDst, pInterBuf                
        MOV         dstStep, #24

        ;// Set up counter of format, [0]  [0]  [1 (height)]  [8 (width)]                                                                                    
        MOV         Counter, #1
        MOV         Temp, #8                                                        
        ADD         Counter, Temp, Counter, LSL #8        ;// [0 0 H W]                        
        
        LDR         r0x00ff00ff, =0x00ff00ff                ;// [0 255 0 255] 255 is offset to avoid negative results 
WidthLoop
        M_STR       pSrc, ppSrc
        M_STR       pDst, ppDst
HeightLoop
TwoRowsLoop
        M_LDR       ValC, [pSrc], srcStep                   ;// Load  [c3 c2 c1 c0]
        M_LDR       ValD, [pSrc], srcStep                   ;// Load  [d3 d2 d1 d0]
        M_LDR       ValE, [pSrc], srcStep                   ;// Load  [e3 e2 e1 e0]        
        SUB         pSrc, pSrc, srcStep, LSL #2                
        UXTAB16     ValC0, r0x00ff00ff, ValC                ;// [0 c2 0 c0] + [0 255 0 255]
        UXTAB16     ValC1, r0x00ff00ff, ValC, ROR #8        ;// [0 c3 0 c1] + [0 255 0 255]        
        LDR         ValB, [pSrc]                            ;// Load  [b3 b2 b1 b0]        
        UXTAB16     ValE0, r0x00ff00ff, ValE                ;// [0 e2 0 e0] + [0 255 0 255]
        UXTAB16     ValE1, r0x00ff00ff, ValE, ROR #8        ;// [0 e3 0 e1] + [0 255 0 255]        
        UXTAB16     ValCD0, ValC0, ValD                     ;// [0 c2 0 c0] + [0 255 0 255] + [0 d2 0 d0]
        UXTAB16     ValCD1, ValC1, ValD, ROR #8             ;// [0 c3 0 c1] + [0 255 0 255] + [0 d3 0 d1]                                
        UXTAB16     ValEB0, ValE0, ValB                     ;// [0 e2 0 e0] + [0 255 0 255] + [0 b2 0 b0]
        RSB         ValCD0, ValEB0, ValCD0, LSL #2          ;// 4*(Off+C+D) - (Off+B+E)
        
        LDR         ValD, [pSrc, srcStep, LSL #1]                       ;// Load  [d3 d2 d1 d0]
        UXTAB16     ValEB1, ValE1, ValB, ROR #8             ;// [0 e3 0 e1] + [0 255 0 255] + [0 b3 0 b1]                                               
        RSB         ValCD1, ValEB1, ValCD1, LSL #2                
        
        UXTAB16     ValED0, ValE0, ValD                     ;// [0 e2 0 e0] + [0 255 0 255] + [0 d2 0 d0]
        UXTAB16     ValED1, ValE1, ValD, ROR #8             ;// [0 e3 0 e1] + [0 255 0 255] + [0 d3 0 d1]                                                       
        LDR         ValF, [pSrc, srcStep, LSL #2]           ;// Load  [f3 f2 f1 f0]
        M_LDR       ValB, [pSrc], srcStep                   ;// Load  [b3 b2 b1 b0]                
        ADD         ValCD0, ValCD0, ValCD0, LSL #2          ;// 5 * [4*(Off+C+D) - (Off+B+E)]
        ADD         ValCD1, ValCD1, ValCD1, LSL #2                          
        UXTAB16     ValCF1, ValC1, ValF, ROR #8             ;// [0 c3 0 c1] + [0 255 0 255] + [0 f3 0 f1]                                
        UXTAB16     ValCF0, ValC0, ValF                     ;// [0 c2 0 c0] + [0 255 0 255] + [0 f2 0 f0]        
        RSB         ValED1, ValCF1, ValED1, LSL #2        
        
        SUB         ValA, pSrc, srcStep, LSL #1
        LDR         ValA, [ValA]                            ;// Load  [a3 a2 a1 a0]
        RSB         ValED0, ValCF0, ValED0, LSL #2          ;// 4*(Off+E+D) - (Off+C+F)        
        ADD         ValED1, ValED1, ValED1, LSL #2          
        ADD         ValED0, ValED0, ValED0, LSL #2          ;// 5 * [4*(Off+E+D) - (Off+C+F)]
        UXTAB16     ValA0, r0x00ff00ff, ValA                ;// [0 a2 0 a0] + [0 255 0 255]
        UXTAB16     ValA1, r0x00ff00ff, ValA, ROR #8        ;// [0 a3 0 a1] + [0 255 0 255]
        UXTAB16     ValAF0, ValA0, ValF                     ;// [0 a2 0 a0] + [0 255 0 255] + [0 f2 0 f0]
        UXTAB16     ValAF1, ValA1, ValF, ROR #8             ;// [0 a3 0 a1] + [0 255 0 255] + [0 f3 0 f1]                                        
        ADD         Acc1, ValCD1, ValAF1        
        
        LDR         ValG, [pSrc, srcStep, LSL #2]           ;// Load  [g3 g2 g1 g0]
        ADD         Acc0, ValCD0, ValAF0                    ;// Acc0 = 16*Off + (A+F) + 20*(C+D) - 5*(B+E)        
        STR         Acc1, [pDst, #4]                        ;// Store result & adjust pointer
        M_STR       Acc0, [pDst], dstStep                   ;// Store result & adjust pointer
        UXTAB16     ValG0, r0x00ff00ff, ValG                ;// [0 g2 0 g0] + [0 255 0 255]
        UXTAB16     ValG1, r0x00ff00ff, ValG, ROR #8        ;// [0 g3 0 g1] + [0 255 0 255]
        UXTAB16     ValGB0, ValG0, ValB                     ;// [0 g2 0 g0] + [0 255 0 255] + [0 b2 0 b0]
        UXTAB16     ValGB1, ValG1, ValB, ROR #8             ;// [0 g3 0 g1] + [0 255 0 255] + [0 b3 0 b1]                        
        ADD         Acc2, ValED0, ValGB0                    ;// Acc2 = 16*Off + (B+G) + 20*(D+E) - 5*(C+F)
        ADD         Acc3, ValED1, ValGB1        
        
        STR         Acc3, [pDst, #4]                        ;// Store result & adjust pointer                                       
        M_STR       Acc2, [pDst], dstStep                   ;// Store result & adjust pointer                                               
        
        SUBS        Counter, Counter, #1 << 8               ;// Loop till height is 10
        ADD         pSrc, pSrc, srcStep, LSL #1
        BPL         HeightLoop
        
        M_LDR       pSrc, ppSrc
        M_LDR       pDst, ppDst
        ADDS        Counter, Counter, #(1 << 8)-4           ;// Loop till width is 12
        ADD         pSrc, pSrc, #4
        ADD         pDst, pDst, #8
        ADD         Counter, Counter, #1<<8
        BPL         WidthLoop
    
        ;//
        ;// Horizontal interpolation using multiplication
        ;//
    
        SUB         pSrc, pDst, #24
        MOV         srcStep, #24
        M_LDRD      pDst, dstStep, ppDstArgs

        MOV         Counter, #4
        LDR         r0x0014fffb, =0x0014fffb
        LDR         r0x00140001, =0x00140001

HeightLoop1
        M_STR       Counter, pCounter


        LDR         ValCA, [pSrc], #4                   ;// Load  [0 c 0 a]
        LDR         ValDB, [pSrc], #4                   ;// Load  [0 d 0 b]
        LDR         ValGE, [pSrc], #4                   ;// Load  [0 g 0 e]
        LDR         ValHF, [pSrc], #4                   ;// Load  [0 h 0 f]

        ;// Acc0 = smuad ([0 20 0 1], add([0 c 0 a] + [0 d 0 f])) - (5 * (b + e)) 
        ;// Acc1 = smuad ([0 20 0 1], add([0 e 0 g] + [0 d 0 b])) - (5 * (c + f)) 
        ;// Acc2 = smuad ([0 1 0 20], add([0 c 0 e] + [0 h 0 f])) - (5 * (d + g)) 
        ;// Acc3 = smuad ([0 20 0 1], add([0 d 0 f] + [0 i 0 g])) - (5 * (e + h)) 
        SMUAD       Acc0, ValCA, r0x00140001            ;// Acc0  = [0 c 0 a] * [0 20 0 1]
        SMUAD       Acc1, ValDB, r0x00140001            ;// Acc1  = [0 c 0 a] * [0 20 0 1]
        SMUADX      Acc2, ValGE, r0x0014fffb            ;// Acc2  = [0 g 0 e] * [0 20 0 -5]
        SMUAD       Acc3, ValGE, r0x0014fffb            ;// Acc3  = [0 g 0 e] * [0 20 0 -5]

        SMLAD       Acc0, ValDB, r0x0014fffb, Acc0      ;// Acc0 += [0 d 0 b] * [0 20 0 -5]
        SMLADX      Acc1, ValGE, r0x00140001, Acc1      ;// Acc1 += [0 g 0 e] * [0 20 0 1]
        SMLADX      Acc2, ValHF, r0x00140001, Acc2      ;// Acc2 += [0 h 0 f] * [0 20 0 1]
        SMLADX      Acc3, ValHF, r0x0014fffb, Acc3      ;// Acc3 += [0 h 0 f] * [0 20 0 -5]

        SMLABB      Acc0, ValGE, r0x0014fffb, Acc0      ;// Acc0 += [0 g 0 e] * [0 0 0 -5]
        SMLATB      Acc1, ValCA, r0x0014fffb, Acc1      ;// Acc1 += [0 d 0 b] * [0 0 0 -5]
        SMLATB      Acc2, ValCA, r0x00140001, Acc2      ;// Acc2 += [0 c 0 a] * [0 0 0 1]
        SMLATB      Acc3, ValDB, r0x00140001, Acc3      ;// Acc3 += [0 c 0 a] * [0 0 0 1]

        LDRH        ValCA, [pSrc], #8                   ;// 8 = srcStep - 16
        SMLABB      Acc0, ValHF, r0x00140001, Acc0      ;// Acc0 += [0 h 0 f] * [0 0 0 1]        
        SMLABB      Acc1, ValHF, r0x0014fffb, Acc1      ;// Acc1 += [0 h 0 f] * [0 0 0 -5]
        SMLATB      Acc2, ValDB, r0x0014fffb, Acc2      ;// Acc2 += [0 d 0 b] * [0 0 0 -5]        
        SMLABB      Acc3, ValCA, r0x00140001, Acc3      ;// Acc3 += [0 d 0 b] * [0 0 0 1]
        
        LDR         r0x0001fc00, =0x0001fc00            ;// (0xff * 16 * 32) - 512
        SUB         Acc0, Acc0, r0x0001fc00        
        SUB         Acc1, Acc1, r0x0001fc00        
        SUB         Acc2, Acc2, r0x0001fc00        
        SUB         Acc3, Acc3, r0x0001fc00        

        USAT        Acc0, #18, Acc0
        USAT        Acc1, #18, Acc1
        USAT        Acc2, #18, Acc2
        USAT        Acc3, #18, Acc3
        
        MOV         Acc0, Acc0, LSR #10
        MOV         Acc1, Acc1, LSR #10
        MOV         Acc2, Acc2, LSR #10
        MOV         Acc3, Acc3, LSR #10

        M_LDR       Counter, pCounter        
        ORR         Acc0, Acc0, Acc1, LSL #8
        ORR         Acc2, Acc2, Acc3, LSL #8
        SUBS        Counter, Counter, #1
        ORR         Acc0, Acc0, Acc2, LSL #16
        M_STR       Acc0, [pDst], dstStep
        BGT         HeightLoop1
End
        SUB         pDst, pDst, dstStep, LSL #2
        SUB         pSrc, pSrc, srcStep, LSL #2

        M_END
    
    ENDIF
    
    END
    
