;//
;// 
;// File Name:  omxVCM4P10_TransformDequantChromaDCFromPair_s.s
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
        
        IMPORT armVCM4P10_QPDivTable
        IMPORT armVCM4P10_VMatrixQPModTable
            
        M_VARIANTS ARM1136JS
    

    IF ARM1136JS

;//--------------------------------------
;// Declare input registers
;//--------------------------------------
ppSrc       RN 0
pDst        RN 1
QP          RN 2

;//--------------------------------
;// Scratch variable for Unpack2x2 
;//--------------------------------
pSrc        RN 9
Value       RN 4
Value2      RN 5
Flag        RN 6
strOffset   RN 7
cstOffset   RN 8

;//--------------------------------
;// Scratch variable
;//--------------------------------
r0w0        RN  3
r0w1        RN  4

c0w0        RN  5
c1w0        RN  6

return      RN  0
pQPDivTable RN  5
pQPModTable    RN  6
Shift        RN  9
Scale        RN  2

Temp1       RN  3
Temp2       RN  4
Temp3       RN  7
Temp4       RN  8

        ;// Write function header
        M_START omxVCM4P10_TransformDequantChromaDCFromPair, r9
        
        
        LDR     pSrc, [ppSrc]                        ;// Load pSrc
        MOV     cstOffset, #31                       ;// To be used in the loop, to compute offset
        
        ;//-----------------------------------------------------------------------
        ;// Firstly, fill all the coefficient values on the <pDst> buffer by zero
        ;//-----------------------------------------------------------------------
        
        MOV      Value,  #0                          ;// Initialize the zero value
        MOV      Value2,  #0                         ;// Initialize the zero value
        LDRB     Flag,  [pSrc], #1                   ;// Preload <Flag> before <unpackLoop>
        STRD     Value, [pDst, #0]                   ;// pDst[0]  = pDst[1]  = pDst[2]  = pDst[3]  = 0
        

unpackLoop
        TST      Flag,  #0x10                        ;// Computing (Flag & 0x10)
        LDRSBNE  Value2,[pSrc,#1]                  
        LDRBNE   Value, [pSrc], #2                   ;// Load byte wise to avoid unaligned access
        AND      strOffset, cstOffset, Flag, LSL #1  ;// strOffset = (Flag & 15) < 1;
        LDRSBEQ  Value, [pSrc], #1                   ;// Value = (OMX_U8)  *pSrc++
        ORRNE    Value,Value,Value2, LSL #8          ;// Value = (OMX_U16) *pSrc++
        
        TST      Flag,  #0x20                        ;// Computing (Flag & 0x20) to check, if we're done
        LDRBEQ   Flag,  [pSrc], #1                   ;// Flag  = (OMX_U8) *pSrc++, for next iteration
        STRH     Value, [pDst, strOffset]            ;// Store <Value> at offset <strOffset>
        BEQ      unpackLoop                          ;// Branch to the loop beginning
        
        LDMIA    pDst, {r0w0, r0w1}                  ;// r0w0 = |c1|c0| & r0w1 = |c3|c2|


        STR      pSrc, [ppSrc]                       ;// Update the bitstream pointer
        
        LDR      pQPDivTable, =armVCM4P10_QPDivTable ;// QP Division look-up-table base pointer
        LDR      pQPModTable, =armVCM4P10_VMatrixQPModTable ;// QP Modulo look-up-table base pointer
        
        SADDSUBX r0w0, r0w0,  r0w0                   ;// [ c00+c01, c00-c01 ]
        SADDSUBX r0w1, r0w1,  r0w1                   ;// [ c10+c11, c10-c11 ]
        
        LDRSB    Shift, [pQPDivTable, QP]            ;// Shift = pQPDivTable[QP]
        LDRSB    Scale, [pQPModTable, QP]            ;// Scale = pQPModTable[QP]
        
        SADD16   c0w0, r0w0, r0w1                    ;// [ d00+d10, d01+d11 ]
        SSUB16   c1w0, r0w0, r0w1                    ;// [ d00-d10, d01-d11 ]
        
        LSL      Scale, Scale, Shift                 ;// Scale = Scale << Shift
        
        SMULTB   Temp2, c0w0,  Scale                 ;// Temp2 = T(c0w0) * Scale
        SMULTB   Temp4, c1w0,  Scale                 ;// Temp4 = T(c1w0) * Scale
        SMULBB   Temp1, c0w0,  Scale                 ;// Temp1 = B(c0w0) * Scale
        SMULBB   Temp3, c1w0,  Scale                 ;// Temp3 = B(c1w0) * Scale
        MOV      Temp2, Temp2, ASR #1                ;// Temp2 = Temp2 >> 1 & Temp1 = (Temp1 >> 1) << 16
        MOV      Temp4, Temp4, ASR #1                ;// Temp4 = Temp4 >> 1 & Temp3 = (Temp3 >> 1) << 16
        PKHBT    c0w0,  Temp2, Temp1, LSL #15        ;// c0w0  = | Temp1 | Temp2 |
        PKHBT    c1w0,  Temp4, Temp3, LSL #15        ;// c1w0  = | Temp3 | Temp4 |
        STMIA    pDst, {c0w0, c1w0}                  ;// Storing all the coefficients at once
        MOV      return, #OMX_Sts_NoErr
        M_END
        
    ENDIF ;// ARM1136JS
    
    
    
    
    END
