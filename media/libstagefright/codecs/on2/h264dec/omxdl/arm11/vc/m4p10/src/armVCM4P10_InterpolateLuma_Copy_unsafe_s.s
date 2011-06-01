;//
;// 
;// File Name:  armVCM4P10_InterpolateLuma_Copy_unsafe_s.s
;// OpenMAX DL: v1.0.2
;// Revision:   9641
;// Date:       Thursday, February 7, 2008
;// 
;// (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
;// 
;// 
;//

;// Function:
;//     armVCM4P10_InterpolateLuma_Copy4x4_unsafe 
;//
;// Implements copy from an arbitrary aligned source memory location (pSrc) to an aligned
;// destination pointed by (pDst)
;//
;// Registers preserved for top level function
;// r1,r3,r4,r5,r6,r7,r10,r11,r14
;//
;// Registers modified by the function
;// r0,r2,r8,r9,r12

        INCLUDE omxtypes_s.h
        INCLUDE armCOMM_s.h
        
        M_VARIANTS ARM1136JS

        EXPORT armVCM4P10_InterpolateLuma_Copy4x4_unsafe
        
;// Declare input registers
pSrc            RN 0
srcStep         RN 1
pDst            RN 2
dstStep         RN 3

;// Declare other intermediate registers
x0              RN 4
x1              RN 5
x2              RN 8
x3              RN 9
Temp            RN 12

    IF ARM1136JS

        M_START armVCM4P10_InterpolateLuma_Copy4x4_unsafe, r6

Copy4x4Start
        ;// Do Copy and branch to EndOfInterpolation
        AND     Temp, pSrc, #3
        BIC     pSrc, pSrc, #3                        

        M_SWITCH Temp
        M_CASE  Copy4x4Align0
        M_CASE  Copy4x4Align1
        M_CASE  Copy4x4Align2
        M_CASE  Copy4x4Align3
        M_ENDSWITCH

Copy4x4Align0
        M_LDR   x0, [pSrc], srcStep
        M_LDR   x1, [pSrc], srcStep
        M_STR   x0, [pDst], dstStep
        M_LDR   x2, [pSrc], srcStep
        M_STR   x1, [pDst], dstStep
        M_LDR   x3, [pSrc], srcStep
        M_STR   x2, [pDst], dstStep
        M_STR   x3, [pDst], dstStep
        B       Copy4x4End  

Copy4x4Align1
        LDR     x1, [pSrc, #4]
        M_LDR   x0, [pSrc], srcStep
        LDR     x3, [pSrc, #4]
        M_LDR   x2, [pSrc], srcStep
        MOV     x0, x0, LSR #8
        ORR     x0, x0, x1, LSL #24
        M_STR   x0, [pDst], dstStep
        MOV     x2, x2, LSR #8
        ORR     x2, x2, x3, LSL #24
        LDR     x1, [pSrc, #4]
        M_LDR   x0, [pSrc], srcStep
        M_STR   x2, [pDst], dstStep
        LDR     x3, [pSrc, #4]
        M_LDR   x2, [pSrc], srcStep
        MOV     x0, x0, LSR #8
        ORR     x0, x0, x1, LSL #24
        M_STR   x0, [pDst], dstStep
        MOV     x2, x2, LSR #8
        ORR     x2, x2, x3, LSL #24
        M_STR   x2, [pDst], dstStep
        B       Copy4x4End  
      
Copy4x4Align2
        LDR     x1, [pSrc, #4]
        M_LDR   x0, [pSrc], srcStep
        LDR     x3, [pSrc, #4]
        M_LDR   x2, [pSrc], srcStep
        MOV     x0, x0, LSR #16
        ORR     x0, x0, x1, LSL #16
        M_STR   x0, [pDst], dstStep
        MOV     x2, x2, LSR #16
        ORR     x2, x2, x3, LSL #16
        M_STR   x2, [pDst], dstStep        

        LDR     x1, [pSrc, #4]
        M_LDR   x0, [pSrc], srcStep
        LDR     x3, [pSrc, #4]
        M_LDR   x2, [pSrc], srcStep
        MOV     x0, x0, LSR #16
        ORR     x0, x0, x1, LSL #16
        M_STR   x0, [pDst], dstStep
        MOV     x2, x2, LSR #16
        ORR     x2, x2, x3, LSL #16
        M_STR   x2, [pDst], dstStep        
        B       Copy4x4End  

Copy4x4Align3 
        LDR     x1, [pSrc, #4]
        M_LDR   x0, [pSrc], srcStep
        LDR     x3, [pSrc, #4]
        M_LDR   x2, [pSrc], srcStep
        MOV     x0, x0, LSR #24
        ORR     x0, x0, x1, LSL #8
        M_STR   x0, [pDst], dstStep
        MOV     x2, x2, LSR #24
        ORR     x2, x2, x3, LSL #8
        M_STR   x2, [pDst], dstStep

        LDR     x1, [pSrc, #4]
        M_LDR   x0, [pSrc], srcStep
        LDR     x3, [pSrc, #4]
        M_LDR   x2, [pSrc], srcStep
        MOV     x0, x0, LSR #24
        ORR     x0, x0, x1, LSL #8
        M_STR   x0, [pDst], dstStep
        MOV     x2, x2, LSR #24
        ORR     x2, x2, x3, LSL #8
        M_STR   x2, [pDst], dstStep
        B       Copy4x4End  

Copy4x4End
        M_END

    ENDIF

    END
    