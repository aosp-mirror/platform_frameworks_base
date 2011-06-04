;//
;// 
;// File Name:  armVCM4P10_InterpolateLuma_Align_unsafe_s.s
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
        
        M_VARIANTS ARM1136JS

        EXPORT armVCM4P10_InterpolateLuma_HorAlign9x_unsafe
        EXPORT armVCM4P10_InterpolateLuma_VerAlign4x_unsafe

DEBUG_ON    SETL {FALSE}

    IF ARM1136JS 

;// Declare input registers
pSrc            RN 0
srcStep         RN 1
pDst            RN 8
iHeight         RN 9

;// Declare inner loop registers
x               RN 7
x0              RN 7
x1              RN 10
x2              RN 11
Scratch         RN 12

;// Function: 
;//     armVCM4P10_InterpolateLuma_HorAlign9x_unsafe
;//
;// Implements copy from an arbitrary aligned source memory location (pSrc) to a 4 byte aligned
;// destination pointed by (pDst) for horizontal interpolation.
;// This function needs to copy 9 bytes in horizontal direction. 
;//
;// Registers used as input for this function
;// r0,r1,r8,r9 where r8 containings aligned memory pointer and r9 no rows to copy
;//
;// Registers preserved for top level function
;// r2,r3,r4,r5,r6
;//
;// Registers modified by the function
;// r7,r8,r9,r10,r11,r12
;//
;// Output registers
;// r0 - pointer to the new aligned location which will be used as pSrc
;// r1 - step size to this aligned location

        ;// Function header
        M_START armVCM4P10_InterpolateLuma_HorAlign9x_unsafe     
        
        ;// Copy pDst to scratch
        MOV     Scratch, pDst

StartAlignedStackCopy
        AND     x, pSrc, #3
        BIC     pSrc, pSrc, #3
        
        M_SWITCH x
        M_CASE   Copy0toAligned
        M_CASE   Copy1toAligned
        M_CASE   Copy2toAligned
        M_CASE   Copy3toAligned
        M_ENDSWITCH

Copy0toAligned  
        LDM     pSrc, {x0, x1, x2}
        SUBS    iHeight, iHeight, #1
        ADD     pSrc, pSrc, srcStep
        
        ;// One cycle stall

        STM     pDst!, {x0, x1, x2}                     ;// Store aligned output row
        BGT     Copy0toAligned
        B       CopyEnd  
      
Copy1toAligned        
        LDM     pSrc, {x0, x1, x2}
        SUBS    iHeight, iHeight, #1
        ADD     pSrc, pSrc, srcStep
        
        ;// One cycle stall

        MOV     x0, x0, LSR #8
        ORR     x0, x0, x1, LSL #24
        MOV     x1, x1, LSR #8
        ORR     x1, x1, x2, LSL #24
        MOV     x2, x2, LSR #8
        STM     pDst!, {x0, x1, x2}                     ;// Store aligned output row
        BGT     Copy1toAligned
        B       CopyEnd  

Copy2toAligned        
        LDM     pSrc, {x0, x1, x2}
        SUBS    iHeight, iHeight, #1
        ADD     pSrc, pSrc, srcStep
        
        ;// One cycle stall

        MOV     x0, x0, LSR #16
        ORR     x0, x0, x1, LSL #16
        MOV     x1, x1, LSR #16
        ORR     x1, x1, x2, LSL #16
        MOV     x2, x2, LSR #16
        STM     pDst!, {x0, x1, x2}                     ;// Store aligned output row
        BGT     Copy2toAligned
        B       CopyEnd  

Copy3toAligned        
        LDM     pSrc, {x0, x1, x2}
        SUBS    iHeight, iHeight, #1
        ADD     pSrc, pSrc, srcStep
        
        ;// One cycle stall

        MOV     x0, x0, LSR #24
        ORR     x0, x0, x1, LSL #8
        MOV     x1, x1, LSR #24
        ORR     x1, x1, x2, LSL #8
        MOV     x2, x2, LSR #24
        STM     pDst!, {x0, x1, x2}                     ;// Store aligned output row
        BGT     Copy3toAligned

CopyEnd  
        
        MOV     pSrc, Scratch
        MOV     srcStep, #12

        M_END
    

;// Function:
;//     armVCM4P10_InterpolateLuma_VerAlign4x_unsafe
;//
;// Implements copy from an arbitrary aligned source memory location (pSrc) to an aligned
;// destination pointed by (pDst) for vertical interpolation.
;// This function needs to copy 4 bytes in horizontal direction 
;//
;// Registers used as input for this function
;// r0,r1,r8,r9 where r8 containings aligned memory pointer and r9 no of rows to copy
;//
;// Registers preserved for top level function
;// r2,r3,r4,r5,r6
;//
;// Registers modified by the function
;// r7,r8,r9,r10,r11,r12
;//
;// Output registers
;// r0 - pointer to the new aligned location which will be used as pSrc
;// r1 - step size to this aligned location

        ;// Function header
        M_START armVCM4P10_InterpolateLuma_VerAlign4x_unsafe     
        
        ;// Copy pSrc to stack
StartVAlignedStackCopy
        AND     x, pSrc, #3
        BIC     pSrc, pSrc, #3                        
        
        
        M_SWITCH x
        M_CASE   Copy0toVAligned
        M_CASE   Copy1toVAligned
        M_CASE   Copy2toVAligned
        M_CASE   Copy3toVAligned
        M_ENDSWITCH
        
Copy0toVAligned  
        M_LDR   x0, [pSrc], srcStep
        SUBS    iHeight, iHeight, #1
        
        ;// One cycle stall

        STR     x0, [pDst], #4                              ;// Store aligned output row
        BGT     Copy0toVAligned
        B       CopyVEnd  
      
Copy1toVAligned        
        LDR     x1, [pSrc, #4]
        M_LDR   x0, [pSrc], srcStep
        SUBS    iHeight, iHeight, #1        
        
        ;// One cycle stall

        MOV     x1, x1, LSL #24
        ORR     x0, x1, x0, LSR #8
        STR     x0, [pDst], #4                              ;// Store aligned output row
        BGT     Copy1toVAligned
        B       CopyVEnd  

Copy2toVAligned        
        LDR     x1, [pSrc, #4]
        M_LDR   x0, [pSrc], srcStep
        SUBS    iHeight, iHeight, #1        
        
        ;// One cycle stall

        MOV     x1, x1, LSL #16
        ORR     x0, x1, x0, LSR #16
        STR     x0, [pDst], #4                              ;// Store aligned output row
        BGT     Copy2toVAligned
        B       CopyVEnd  

Copy3toVAligned        
        LDR     x1, [pSrc, #4]
        M_LDR   x0, [pSrc], srcStep
        SUBS    iHeight, iHeight, #1        
        
        ;// One cycle stall

        MOV     x1, x1, LSL #8
        ORR     x0, x1, x0, LSR #24
        STR     x0, [pDst], #4                              ;// Store aligned output row
        BGT     Copy3toVAligned

CopyVEnd  

        SUB     pSrc, pDst, #28
        MOV     srcStep, #4

        M_END


    ENDIF

    END
    
