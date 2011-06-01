;//
;// 
;// File Name:  omxVCM4P10_TransformDequantChromaDCFromPair_s.s
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
        
        IMPORT armVCM4P10_QPDivTable
        IMPORT armVCM4P10_VMatrixQPModTable
            
        M_VARIANTS CortexA8
    

    
    
    IF CortexA8

;// ARM Registers
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



;// Neon Registers

dZero       DN  D0.U16
dInvTrCoeff DN  D0.S16
dScale      DN  D1.S16
qDqntCoeff  QN  Q1.S32
dDqntCoeff  DN  D2.S16


        ;// Write function header
        M_START omxVCM4P10_TransformDequantChromaDCFromPair, r9
        
        LDR     pSrc, [ppSrc]                        ;// Load pSrc
        VMOV    dZero, #0
        MOV     cstOffset, #31                       ;// To be used in the loop, to compute offset
        
        ;//-----------------------------------------------------------------------
        ;// Firstly, fill all the coefficient values on the <pDst> buffer by zero
        ;//-----------------------------------------------------------------------
        
        VST1    dZero,[pDst]                         ;// pDst[0]  = pDst[1]  = pDst[2]  = pDst[3]  = 0   
        LDRB     Flag,  [pSrc], #1                   ;// Preload <Flag> before <unpackLoop>


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
        
        ;//--------------------------------------------------
        ;//InvTransformDC2x2: Inlined (Implemented in ARM V6)
        ;//--------------------------------------------------
        
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
        
        ;//-------------------------------------------------
        ;//DequantChromaDC2x2: Inlined (Neon Implementation)
        ;//-------------------------------------------------
        
        LSL      Scale, Scale, Shift                 ;// Scale = Scale << Shift
        VMOV     dInvTrCoeff, c0w0, c1w0  
        VREV32   dInvTrCoeff,dInvTrCoeff  
        VDUP     dScale,Scale 
        
        VMULL    qDqntCoeff,dInvTrCoeff,dScale
        VSHRN    dDqntCoeff,qDqntCoeff,#1
        
        
        VST1     dDqntCoeff,[pDst]                   ;// Storing all the coefficients at once
        
        MOV      return, #OMX_Sts_NoErr
        M_END
        
    ENDIF ;// CortexA8
    
    
    END
