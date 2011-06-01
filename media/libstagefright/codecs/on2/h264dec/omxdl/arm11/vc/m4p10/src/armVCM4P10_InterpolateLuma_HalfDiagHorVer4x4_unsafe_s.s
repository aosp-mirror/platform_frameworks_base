;//
;// 
;// File Name:  armVCM4P10_InterpolateLuma_HalfDiagHorVer4x4_unsafe_s.s
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
        
        EXPORT armVCM4P10_InterpolateLuma_HalfDiagHorVer4x4_unsafe

        M_VARIANTS ARM1136JS



    IF ARM1136JS 


        M_ALLOC8 ppDstArgs, 8
        M_ALLOC8 pTempResult1, 8
        M_ALLOC8 pTempResult2, 8
        M_ALLOC4 ppSrc, 4
        M_ALLOC4 ppDst, 4
        M_ALLOC4 pDstStep, 4
        M_ALLOC4 pSrcStep, 4
        M_ALLOC4 pCounter, 4

        ;// Function header
        ;// Function: 
        ;//     armVCM4P10_InterpolateLuma_HalfDiagHorVer4x4_unsafe
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

        M_START armVCM4P10_InterpolateLuma_HalfDiagHorVer4x4_unsafe, r6
        
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
pInterBuf       RN 8

ValCA           RN 8
ValDB           RN 9
ValGE           RN 10
ValHF           RN 11
r0x00140001     RN 12
r0x0014fffb     RN 14

r0x0001fc00     RN 11

Accx            RN 8
Accy            RN 9
Temp6           RN 14

        M_STRD      pDst, dstStep, ppDstArgs

        MOV         pDst, pInterBuf                
        MOV         dstStep, #16

        ;// Set up counter of format, [0]  [0]  [1 (height)]  [8 (width)]                                                                                    
        MOV         Counter, #4
        M_STR       dstStep, pDstStep        
        M_STR       srcStep, pSrcStep        
        LDR         r0x00ff00ff, =0x00ff00ff               ;// [0 255 0 255] 255 is offset to avoid negative results 

HeightLoop
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

        UXTAB16 Acc2, ValC, ValH, ROR #8        
        ADD     ValI, r0x00ff00ff, ValI                 ;// [00 i1 00 i0] + [0 255 0 255]        
        
        ;// Calculate Acc2
        ;// Acc2 = c - 5*d + 20*e + 20*f - 5*g + h
        UXTAB16 Temp1, ValG, ValD, ROR #8
        UXTAB16 Acc3, ValI, ValD, ROR #8
        UXTAB16 Temp2, ValE, ValF, ROR #8
        
        RSB     Temp1, Temp1, Temp2, LSL #2        
        UXTAB16 Temp2, ValG, ValF, ROR #8
        ADD     Temp1, Temp1, Temp1, LSL #2        
        ADD     Acc2, Acc2, Temp1        

        ;// Calculate Acc3
        ;// Acc3 = d - 5*e + 20*f + 20*g - 5*h + i
        UXTAB16 Temp1, ValE, ValH, ROR #8
        RSB     Temp1, Temp1, Temp2, LSL #2
        ADD     Temp1, Temp1, Temp1, LSL #2        
        ADD     Acc3, Acc3, Temp1
        
        M_LDR   dstStep, pDstStep        
        M_LDR   srcStep, pSrcStep

        ;// If Counter is even store Acc0-Acc3 in a temporary buffer
        ;// If Counter is off store Acc0-Acc3 and previous Acc0-Acc3 in a intermediate buf 
        ANDS        Temp3, Counter, #1
        BEQ         NoProcessing        
        
        ;// Packing previous and current Acc0-Acc3 values
        M_LDRD      Accx, Accy, pTempResult1
        PKHBT       Temp6, Accx, Acc0, LSL #16          ;//[0 a2 0 a0] = [0 a3 0 a2] [0 a1 0 a0]
        PKHTB       Acc0, Acc0, Accx, ASR #16           ;//[0 a3 0 a1] = [0 a1 0 a0] [0 a3 0 a2] 
        STR         Acc0, [pDst, dstStep]                        
        STR         Temp6, [pDst], #4                   
        PKHBT       Temp6, Accy, Acc1, LSL #16          ;//[0 b2 0 b0] = [0 b3 0 b2] [0 b1 0 b0]
        PKHTB       Acc1, Acc1, Accy, ASR #16            ;//[0 b3 0 b1] = [0 b1 0 b0] [0 b3 0 b2]
        M_LDRD      Accx, Accy, pTempResult2
        STR         Acc1, [pDst, dstStep]                        
        STR         Temp6, [pDst], #4                   
        
        PKHBT       Temp6, Accx, Acc2, LSL #16          ;//[0 c2 0 c0] = [0 c3 0 c2] [0 c1 0 c0]
        PKHTB       Acc2, Acc2, Accx, ASR #16            ;//[0 c3 0 c1] = [0 c1 0 c0] [0 c3 0 c2]
        STR         Acc2, [pDst, dstStep]                        
        STR         Temp6, [pDst], #4                   
        PKHBT       Temp6, Accy, Acc3, LSL #16          ;//[0 d2 0 d0] = [0 d3 0 d2] [0 d1 0 d0]
        PKHTB       Acc3, Acc3, Accy, ASR #16            ;//[0 d3 0 d1] = [0 d1 0 d0] [0 d3 0 d2]
        STR         Acc3, [pDst, dstStep]                        
        STR         Temp6, [pDst], #-12
        ADD         pDst, pDst, dstStep, LSL #1                   
        B           AfterStore

NoProcessing
        M_STRD      Acc0, Acc1, pTempResult1
        M_STRD      Acc2, Acc3, pTempResult2
AfterStore
        SUBS        Counter, Counter, #1                ;// Loop till height is 10
        ADD         pSrc, pSrc, srcStep, LSL #1
        BPL         HeightLoop

        STR         Acc0, [pDst], #4                    ;//[0 a1 0 a0]
        STR         Acc1, [pDst], #4
        STR         Acc2, [pDst], #4
        STR         Acc3, [pDst], #-12
        
        ;//
        ;// Horizontal interpolation using multiplication
        ;//
    
        SUB         pSrc, pDst, dstStep, LSL #2
        MOV         srcStep, #16
        M_LDRD      pDst, dstStep, ppDstArgs

        MOV         Counter, #4
        LDR         r0x0014fffb, =0x0014fffb
        LDR         r0x00140001, =0x00140001

HeightLoop1
        M_STR       Counter, pCounter

        M_LDR       ValCA, [pSrc], srcStep               ;// Load  [0 c 0 a]
        M_LDR       ValDB, [pSrc], srcStep               ;// Load  [0 d 0 b]
        M_LDR       ValGE, [pSrc], srcStep               ;// Load  [0 g 0 e]
        M_LDR       ValHF, [pSrc], srcStep               ;// Load  [0 h 0 f]


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

        LDRH        ValCA, [pSrc], #4                   ;// 8 = srcStep - 16
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
        M_STRB      Acc0, [pDst], dstStep
        MOV         Acc1, Acc1, LSR #10
        M_STRB      Acc1, [pDst], dstStep
        MOV         Acc2, Acc2, LSR #10
        M_STRB      Acc2, [pDst], dstStep
        MOV         Acc3, Acc3, LSR #10
        M_STRB      Acc3, [pDst], dstStep


        M_LDR       Counter, pCounter
        SUB         pDst, pDst, dstStep, LSL #2
        SUB         pSrc, pSrc, srcStep, LSL #2
        ADD         pDst, pDst, #1
        SUBS        Counter, Counter, #1
        BGT         HeightLoop1
End
        SUB         pDst, pDst, #4
        SUB         pSrc, pSrc, #16

        M_END
    
    ENDIF
    
    END
    
