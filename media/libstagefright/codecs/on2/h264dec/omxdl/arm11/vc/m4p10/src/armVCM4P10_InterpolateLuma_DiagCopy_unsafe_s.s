;//
;// 
;// File Name:  armVCM4P10_InterpolateLuma_DiagCopy_unsafe_s.s
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

        EXPORT armVCM4P10_InterpolateLuma_HorDiagCopy_unsafe
        EXPORT armVCM4P10_InterpolateLuma_VerDiagCopy_unsafe

;// Functions: 
;//     armVCM4P10_InterpolateLuma_HorDiagCopy_unsafe and
;//     armVCM4P10_InterpolateLuma_VerDiagCopy_unsafe 
;//
;// Implements re-arrangement of data from temporary buffer to a buffer pointed by pBuf.
;// This will do the convertion of data from 16 bit to 8 bit and it also
;// remove offset and check for saturation.
;//
;// Registers used as input for this function
;// r0,r1,r7 where r0 is input pointer and r2 its step size, r7 is output pointer
;//
;// Registers preserved for top level function
;// r4,r5,r6,r8,r9,r14
;//
;// Registers modified by the function
;// r7,r10,r11,r12
;//
;// Output registers
;// r0 - pointer to the destination location
;// r1 - step size to this destination location


DEBUG_ON    SETL {FALSE}
        
MASK            EQU 0x80808080  ;// Mask is used to implement (a+b+1)/2

;// Declare input registers

pSrc0           RN 0
srcStep0        RN 1

;// Declare other intermediate registers
Temp1           RN 4
Temp2           RN 5
Temp3           RN 10
Temp4           RN 11
pBuf            RN 7
r0x0fe00fe0     RN 6
r0x00ff00ff     RN 12
Count           RN 14
ValueA0         RN 10
ValueA1         RN 11

    IF ARM1136JS


        ;// Function header
        M_START armVCM4P10_InterpolateLuma_HorDiagCopy_unsafe, r6

        ;// Code start     
        MOV         Count, #4   
        LDR         r0x0fe00fe0, =0x0fe00fe0
        LDR         r0x00ff00ff, =0x00ff00ff        
LoopStart1
        LDR         Temp4, [pSrc0, #12]
        LDR         Temp3, [pSrc0, #8]        
        LDR         Temp2, [pSrc0, #4]
        M_LDR       Temp1, [pSrc0], srcStep0              
        UQSUB16     Temp4, Temp4, r0x0fe00fe0        
        UQSUB16     Temp3, Temp3, r0x0fe00fe0                 
        UQSUB16     Temp2, Temp2, r0x0fe00fe0        
        UQSUB16     Temp1, Temp1, r0x0fe00fe0                 
        USAT16      Temp4, #13, Temp4
        USAT16      Temp3, #13, Temp3                          
        USAT16      Temp2, #13, Temp2
        USAT16      Temp1, #13, Temp1                                  
        AND         Temp4, r0x00ff00ff, Temp4, LSR #5         
        AND         Temp3, r0x00ff00ff, Temp3, LSR #5         
        AND         Temp2, r0x00ff00ff, Temp2, LSR #5         
        AND         Temp1, r0x00ff00ff, Temp1, LSR #5         
        ORR         ValueA1, Temp3, Temp4, LSL #8             
        ORR         ValueA0, Temp1, Temp2, LSL #8             
        SUBS        Count, Count, #1                   
        STRD        ValueA0, [pBuf], #8 
        BGT         LoopStart1
End1
        SUB        pSrc0, pBuf, #32
        MOV        srcStep0, #8

        M_END


        ;// Function header
        M_START armVCM4P10_InterpolateLuma_VerDiagCopy_unsafe, r6
        
        ;// Code start        
        LDR         r0x0fe00fe0, =0x0fe00fe0
        LDR         r0x00ff00ff, =0x00ff00ff
        MOV         Count, #2

LoopStart    
        LDR         Temp4, [pSrc0, #12]
        LDR         Temp3, [pSrc0, #8]        
        LDR         Temp2, [pSrc0, #4]
        M_LDR       Temp1, [pSrc0], srcStep0
        
        UQSUB16     Temp4, Temp4, r0x0fe00fe0        
        UQSUB16     Temp3, Temp3, r0x0fe00fe0                 
        UQSUB16     Temp2, Temp2, r0x0fe00fe0        
        UQSUB16     Temp1, Temp1, r0x0fe00fe0                 
        
        USAT16      Temp4, #13, Temp4
        USAT16      Temp3, #13, Temp3                          
        USAT16      Temp2, #13, Temp2
        USAT16      Temp1, #13, Temp1
                                  
        AND         Temp4, r0x00ff00ff, Temp4, LSR #5         
        AND         Temp3, r0x00ff00ff, Temp3, LSR #5         
        AND         Temp2, r0x00ff00ff, Temp2, LSR #5         
        AND         Temp1, r0x00ff00ff, Temp1, LSR #5         
        ORR         ValueA1, Temp3, Temp4, LSL #8        ;// [d2 c2 d0 c0]             
        ORR         ValueA0, Temp1, Temp2, LSL #8        ;// [b2 a2 b0 a0]         
                    
        PKHBT       Temp1, ValueA0, ValueA1, LSL #16     ;// [d0 c0 b0 a0]

        STR         Temp1, [pBuf], #8 
        PKHTB       Temp2, ValueA1, ValueA0, ASR #16     ;// [d2 c2 b2 a2]
        STR         Temp2, [pBuf], #-4  

        LDR         Temp4, [pSrc0, #12]
        LDR         Temp3, [pSrc0, #8]        
        LDR         Temp2, [pSrc0, #4]
        M_LDR       Temp1, [pSrc0], srcStep0
        
        UQSUB16     Temp4, Temp4, r0x0fe00fe0        
        UQSUB16     Temp3, Temp3, r0x0fe00fe0                 
        UQSUB16     Temp2, Temp2, r0x0fe00fe0        
        UQSUB16     Temp1, Temp1, r0x0fe00fe0                 
        
        USAT16      Temp4, #13, Temp4
        USAT16      Temp3, #13, Temp3                          
        USAT16      Temp2, #13, Temp2
        USAT16      Temp1, #13, Temp1
                                  
        AND         Temp4, r0x00ff00ff, Temp4, LSR #5         
        AND         Temp3, r0x00ff00ff, Temp3, LSR #5         
        AND         Temp2, r0x00ff00ff, Temp2, LSR #5         
        AND         Temp1, r0x00ff00ff, Temp1, LSR #5         
        ORR         ValueA1, Temp3, Temp4, LSL #8        ;// [d2 c2 d0 c0]             
        ORR         ValueA0, Temp1, Temp2, LSL #8        ;// [b2 a2 b0 a0]         
                    
        PKHBT       Temp1, ValueA0, ValueA1, LSL #16     ;// [d0 c0 b0 a0]
        SUBS        Count, Count, #1
        STR         Temp1, [pBuf], #8 
        PKHTB       Temp2, ValueA1, ValueA0, ASR #16     ;// [d2 c2 b2 a2]
        STR         Temp2, [pBuf], #4  
        
        BGT         LoopStart
End2
        SUB         pSrc0, pBuf, #32-8
        MOV         srcStep0, #4

        M_END

    ENDIF
    
    END
    