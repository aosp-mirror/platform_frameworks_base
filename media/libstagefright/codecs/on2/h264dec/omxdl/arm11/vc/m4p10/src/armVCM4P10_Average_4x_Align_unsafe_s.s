;//
;// 
;// File Name:  armVCM4P10_Average_4x_Align_unsafe_s.s
;// OpenMAX DL: v1.0.2
;// Revision:   9641
;// Date:       Thursday, February 7, 2008
;// 
;// (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
;// 
;// 
;//


;// Functions:
;//     armVCM4P10_Average_4x4_Align<ALIGNMENT>_unsafe  
;//
;// Implements Average of 4x4 with equation c = (a+b+1)>>1.
;// First operand will be at offset ALIGNMENT from aligned address
;// Second operand will be at aligned location and will be used as output.
;// destination pointed by (pDst) for vertical interpolation.
;// This function needs to copy 4 bytes in horizontal direction 
;//
;// Registers used as input for this function
;// r0,r1,r2,r3 where r2 containings aligned memory pointer and r3 step size
;//
;// Registers preserved for top level function
;// r4,r5,r6,r8,r9,r14
;//
;// Registers modified by the function
;// r7,r10,r11,r12
;//
;// Output registers
;// r2 - pointer to the aligned location
;// r3 - step size to this aligned location

        INCLUDE omxtypes_s.h
        INCLUDE armCOMM_s.h
        
        M_VARIANTS ARM1136JS

        EXPORT armVCM4P10_Average_4x4_Align0_unsafe
        EXPORT armVCM4P10_Average_4x4_Align2_unsafe
        EXPORT armVCM4P10_Average_4x4_Align3_unsafe

DEBUG_ON    SETL {FALSE}

;// Declare input registers
pPred0          RN 0
iPredStep0      RN 1
pPred1          RN 2
iPredStep1      RN 3
pDstPred        RN 2
iDstStep        RN 3

;// Declare other intermediate registers
iPredA0         RN 10
iPredA1         RN 11
iPredB0         RN 12
iPredB1         RN 14
Temp1           RN 4
Temp2           RN 5
ResultA         RN 5
ResultB         RN 4
r0x80808080     RN 7

    IF ARM1136JS
        
        ;// This function calculates average of 4x4 block 
        ;// pPred0 is at alignment offset 0 and pPred1 is alignment 4

        ;// Function header
        M_START armVCM4P10_Average_4x4_Align0_unsafe, r6

        ;// Code start        
        LDR         r0x80808080, =0x80808080

        ;// 1st load
        M_LDR       iPredB0, [pPred1]
        M_LDR       iPredA0, [pPred0], iPredStep0        
        M_LDR       iPredB1, [pPred1, iPredStep1]
        M_LDR       iPredA1, [pPred0], iPredStep0

        ;// (a+b+1)/2 = (a+256-(255-b))/2 = (a-(255-b))/2 + 128
        MVN         iPredB0, iPredB0
        MVN         iPredB1, iPredB1
        UHSUB8      ResultA, iPredA0, iPredB0
        UHSUB8      ResultB, iPredA1, iPredB1
        EOR         ResultA, ResultA, r0x80808080
        M_STR       ResultA, [pDstPred], iDstStep        
        EOR         ResultB, ResultB, r0x80808080
        M_STR       ResultB, [pDstPred], iDstStep        
        
        ;// 2nd load
        M_LDR       iPredA0, [pPred0], iPredStep0        
        M_LDR       iPredB0, [pPred1]
        M_LDR       iPredA1, [pPred0], iPredStep0
        M_LDR       iPredB1, [pPred1, iPredStep1]

        MVN         iPredB0, iPredB0
        UHSUB8      ResultA, iPredA0, iPredB0
        MVN         iPredB1, iPredB1
        UHSUB8      ResultB, iPredA1, iPredB1
        EOR         ResultA, ResultA, r0x80808080        
        M_STR       ResultA, [pDstPred], iDstStep        
        EOR         ResultB, ResultB, r0x80808080
        M_STR       ResultB, [pDstPred], iDstStep                
End0
        M_END

        ;// This function calculates average of 4x4 block 
        ;// pPred0 is at alignment offset 2 and pPred1 is alignment 4

        ;// Function header
        M_START armVCM4P10_Average_4x4_Align2_unsafe, r6

        ;// Code start        
        LDR         r0x80808080, =0x80808080

        ;// 1st load
        LDR         Temp1, [pPred0, #4]
        M_LDR       iPredA0, [pPred0], iPredStep0        
        M_LDR       iPredB0, [pPred1]
        M_LDR       iPredB1, [pPred1, iPredStep1]
        M_LDR       Temp2, [pPred0, #4]
        M_LDR       iPredA1, [pPred0], iPredStep0
        MVN         iPredB0, iPredB0
        MVN         iPredB1, iPredB1        
        MOV         iPredA0, iPredA0, LSR #16
        ORR         iPredA0, iPredA0, Temp1, LSL #16        
        MOV         iPredA1, iPredA1, LSR #16
        ORR         iPredA1, iPredA1, Temp2, LSL #16

        ;// (a+b+1)/2 = (a+256-(255-b))/2 = (a-(255-b))/2 + 128
        UHSUB8      ResultA, iPredA0, iPredB0
        UHSUB8      ResultB, iPredA1, iPredB1
        EOR         ResultA, ResultA, r0x80808080
        M_STR       ResultA, [pDstPred], iDstStep        
        EOR         ResultB, ResultB, r0x80808080
        M_STR       ResultB, [pDstPred], iDstStep        
        
        ;// 2nd load
        LDR         Temp1, [pPred0, #4]
        M_LDR         iPredA0, [pPred0], iPredStep0        
        LDR         iPredB0, [pPred1]
        LDR         iPredB1, [pPred1, iPredStep1]
        LDR         Temp2, [pPred0, #4]
        M_LDR         iPredA1, [pPred0], iPredStep0
        MVN         iPredB0, iPredB0
        MVN         iPredB1, iPredB1
        MOV         iPredA0, iPredA0, LSR #16
        ORR         iPredA0, iPredA0, Temp1, LSL #16        
        MOV         iPredA1, iPredA1, LSR #16
        ORR         iPredA1, iPredA1, Temp2, LSL #16

        UHSUB8      ResultA, iPredA0, iPredB0
        UHSUB8      ResultB, iPredA1, iPredB1
        EOR         ResultA, ResultA, r0x80808080        
        M_STR       ResultA, [pDstPred], iDstStep        
        EOR         ResultB, ResultB, r0x80808080
        M_STR       ResultB, [pDstPred], iDstStep                
End2
        M_END


        ;// This function calculates average of 4x4 block 
        ;// pPred0 is at alignment offset 3 and pPred1 is alignment 4

        ;// Function header
        M_START armVCM4P10_Average_4x4_Align3_unsafe, r6

        ;// Code start        
        LDR         r0x80808080, =0x80808080

        ;// 1st load
        LDR         Temp1, [pPred0, #4]
        M_LDR       iPredA0, [pPred0], iPredStep0        
        LDR         iPredB0, [pPred1]
        LDR         iPredB1, [pPred1, iPredStep1]
        LDR         Temp2, [pPred0, #4]
        M_LDR       iPredA1, [pPred0], iPredStep0

        MVN         iPredB0, iPredB0
        MVN         iPredB1, iPredB1
        MOV         iPredA0, iPredA0, LSR #24
        ORR         iPredA0, iPredA0, Temp1, LSL #8                
        MOV         iPredA1, iPredA1, LSR #24
        ORR         iPredA1, iPredA1, Temp2, LSL #8
        UHSUB8      ResultA, iPredA0, iPredB0
        UHSUB8      ResultB, iPredA1, iPredB1
        EOR         ResultA, ResultA, r0x80808080
        M_STR       ResultA, [pDstPred], iDstStep        
        EOR         ResultB, ResultB, r0x80808080
        M_STR       ResultB, [pDstPred], iDstStep        
        
        ;// 2nd load
        LDR         Temp1, [pPred0, #4]
        M_LDR       iPredA0, [pPred0], iPredStep0        
        LDR         iPredB0, [pPred1]
        LDR         iPredB1, [pPred1, iPredStep1]
        LDR         Temp2, [pPred0, #4]
        M_LDR       iPredA1, [pPred0], iPredStep0

        MVN         iPredB0, iPredB0
        MVN         iPredB1, iPredB1
        MOV         iPredA0, iPredA0, LSR #24
        ORR         iPredA0, iPredA0, Temp1, LSL #8        
        MOV         iPredA1, iPredA1, LSR #24
        ORR         iPredA1, iPredA1, Temp2, LSL #8

        UHSUB8      ResultA, iPredA0, iPredB0
        UHSUB8      ResultB, iPredA1, iPredB1
        EOR         ResultA, ResultA, r0x80808080        
        M_STR       ResultA, [pDstPred], iDstStep        
        EOR         ResultB, ResultB, r0x80808080
        M_STR       ResultB, [pDstPred], iDstStep                
End3
        M_END

    ENDIF
    
    END
    